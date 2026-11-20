package com.astrovm.crosstune

import com.atilika.kuromoji.dict.CharacterDefinitions
import com.atilika.kuromoji.dict.ConnectionCosts
import com.atilika.kuromoji.dict.InsertedDictionary
import com.atilika.kuromoji.dict.TokenInfoDictionary
import com.atilika.kuromoji.dict.UnknownDictionary
import com.atilika.kuromoji.ipadic.Token
import com.atilika.kuromoji.ipadic.Tokenizer
import com.atilika.kuromoji.trie.DoubleArrayTrie
import com.atilika.kuromoji.util.SimpleResourceResolver
import com.atilika.kuromoji.viterbi.ViterbiLattice
import com.atilika.kuromoji.viterbi.ViterbiNode
import com.atilika.kuromoji.viterbi.ViterbiSearcher

/**
 * IPADIC's tokenizer, kept from starting a word on a small kana. In kana alone IPADIC can split かいしゃのけいひ
 * into かいし and ゃのけいひ, though ゃ is said with the し before it (Issue #924). Where it does, the two words
 * at that place are searched again as Kuromoji searches them, with no word that starts on a small kana after
 * another kana, and with the words in kana JMdict writes in kanji offered in kana too, as かいしゃ for 会社 and
 * けいひ for 経費, as IPADIC knows few words in kana. The rest of the line stays as Kuromoji split it.
 *
 * Small vowels that draw a word out, ありがとぉ, can still be a word of their own: Readings puts them with the
 * word before, which keeps its reading. A small vowel that makes a sound with the kana before, でぃ, is said with it.
 */
internal class SmallKanaTokenizer(private val words: () -> Readings.OldKana) : Tokenizer.Builder() {
    private val plain: Tokenizer = build()
    private val searcher = ViterbiSearcher(mode, connectionCosts, unknownDictionary, penalties)

    /** Kuromoji's own, as it reads them: its builder looks for them next to the class that extends it, here none. */
    override fun loadDictionaries() {
        val files = SimpleResourceResolver(Tokenizer::class.java)
        doubleArrayTrie = DoubleArrayTrie.newInstance(files)
        connectionCosts = ConnectionCosts.newInstance(files)
        tokenInfoDictionary = TokenInfoDictionary.newInstance(files)
        characterDefinitions = CharacterDefinitions.newInstance(files)
        unknownDictionary = UnknownDictionary.newInstance(files, characterDefinitions, totalFeatures)
        insertedDictionary = InsertedDictionary(totalFeatures)
    }

    fun tokenize(text: String): List<Token> {
        val tokens = plain.tokenize(text)
        val out = ArrayList<Token>(tokens.size)
        var first = 0
        while (first < tokens.size) {
            // A word that starts wrong is searched again with the word before it, as the kana it starts on is said
            // with that word's last; words that start wrong one after another are searched again together.
            var last = first
            while (last + 1 < tokens.size && wrongStart(text, tokens[last + 1])) last++
            if (last == first) {
                out += tokens[first++]
                continue
            }
            // A small っ at the end doubles the kana after it, so that word is searched again with it, にっ and き.
            while (last + 1 < tokens.size && (tokens[last].surface.last() in "っッ" || wrongStart(text, tokens[last + 1]))) last++
            val from = tokens[first].position
            val to = tokens[last].position + tokens[last].surface.length
            out += searched(text.substring(from, to), from) ?: tokens.subList(first, last + 1)
            first = last + 1
        }
        return out
    }

    /**
     * [text] as Kuromoji searches it, bar the words that start wrong, with JMdict's words in kana offered too; null
     * when no way through it is left.
     */
    private fun searched(text: String, offset: Int): List<Token>? {
        val lattice = ViterbiLattice(text.length + 2).apply { addBos() }
        val reached = BooleanArray(text.length + 1).also { it[0] = true }
        val offered = HashSet<ViterbiNode>()
        var unknownEnd = -1
        for (at in text.indices) {
            if (!reached[at]) continue
            val kept = (startingAt(text, at, at >= unknownEnd) + offer(text, at).also(offered::addAll))
                .filterNot { wrongStart(text, at, it.type == ViterbiNode.Type.KNOWN, it.surface) }
            for (node in kept + spanning(text, at, kept)) {
                lattice.place(node, at)
                reached[at + node.surface.length] = true
                if (node.type == ViterbiNode.Type.UNKNOWN) unknownEnd = at + node.surface.length
            }
        }
        if (!reached[text.length]) return null
        lattice.addEos()
        return searcher.search(lattice).filterNot { it.type == ViterbiNode.Type.KNOWN && it.wordId == -1 }.map { node ->
            val dictionary = if (node.type == ViterbiNode.Type.UNKNOWN) unknownDictionary else tokenInfoDictionary
            if (node in offered) JmdictToken(node.wordId, node.surface, offset + node.startIndex, tokenInfoDictionary)
            else Token(node.wordId, node.surface, node.type, offset + node.startIndex, dictionary)
        }
    }

    /**
     * The words Kuromoji finds at [at] in [text]: those its dictionary has, looked up a letter longer at a time until
     * none goes on so, and, when [unknown], those it makes up for letters it may not know, a run of them where they
     * come in runs. Kuromoji itself makes them for the rest of the text from there, which on a long line takes long.
     */
    private fun startingAt(text: String, at: Int, unknown: Boolean): List<ViterbiNode> {
        val known = ArrayList<ViterbiNode>()
        for (end in at + 1..text.length) {
            val entry = doubleArrayTrie.lookup(text.substring(at, end), 0, 0)
            if (entry < 0) break
            if (entry > 0) tokenInfoDictionary.lookupWordIds(entry).mapTo(known) { ViterbiNode(it, text.substring(at, end), tokenInfoDictionary, at, ViterbiNode.Type.KNOWN) }
        }
        if (!unknown) return known
        return known + characterDefinitions.lookupCategories(text[at]).withIndex().flatMap { (index, category) ->
            val definition = characterDefinitions.lookupDefinition(category)
            if (definition[CharacterDefinitions.INVOKE] == 0 && known.isNotEmpty()) return@flatMap emptyList()
            var end = at + 1
            if (definition[CharacterDefinitions.GROUP] == 1) {
                while (end < text.length && characterDefinitions.lookupCategories(text[end])?.getOrNull(index) == category) end++
            }
            unknownDictionary.lookupWordIds(category).map { ViterbiNode(it, text.substring(at, end), unknownDictionary, at, ViterbiNode.Type.UNKNOWN) }
        }
    }

    /** JMdict's words in kana at [at] in [text], each as IPADIC's word for how JMdict writes it, when that reads so. */
    private fun offer(text: String, at: Int): List<ViterbiNode> {
        val words = words()
        return (at + 2..minOf(text.length, at + words.longestToday)).mapNotNull { end ->
            val kana = text.substring(at, end)
            val written = words.today[kana]?.takeIf { it != kana } ?: return@mapNotNull null
            val word = plain.tokenize(written).singleOrNull()?.takeIf { it.isKnown && Readings.hiragana(it.reading) == kana }
                ?: return@mapNotNull null
            startingAt(written, 0, unknown = false)
                .firstOrNull { it.surface == written && tokenInfoDictionary.getAllFeatures(it.wordId) == word.allFeatures }
                ?.let { ViterbiNode(it.wordId, kana, tokenInfoDictionary, at, ViterbiNode.Type.KNOWN) }
        }
    }

    /**
     * Words the dictionary doesn't know, for where [nodes] leave a small kana no way to be said: the small vowels
     * at [at] that draw out the word before, and from [at] through the small kana right after it, and the kana a
     * small っ doubles, when none of [nodes] holds them, so にっき, a word neither knows, is still one, not に and っき.
     */
    private fun spanning(text: String, at: Int, nodes: List<ViterbiNode>): List<ViterbiNode> {
        val end = when {
            drawsOut(text, at) -> (at..text.length).first { it == text.length || !drawsOut(text, it) }
            !saidWith(text, at + 1) || nodes.any { it.surface.length > 1 } -> return emptyList()
            else -> {
                var end = at + 1
                while (saidWith(text, end)) end++
                if (text[end - 1] in "っッ" && end < text.length && Readings.isKana(text[end])) end + 1 else end
            }
        }
        val surface = text.substring(at, end)
        return unknownDictionary.lookupWordIds(characterDefinitions.lookupCategories(text[at])[0])
            .map { ViterbiNode(it, surface, unknownDictionary, at, ViterbiNode.Type.UNKNOWN) }
    }

    /** Put [node], which starts at [at] in the text, where Kuromoji's lattice keeps it: by where it starts, and where it ends. */
    private fun ViterbiLattice.place(node: ViterbiNode, at: Int) {
        put(startIndexArr, startSizeArr, at + 1, node)
        put(endIndexArr, endSizeArr, at + node.surface.length + 1, node)
    }

    private fun put(rows: Array<Array<ViterbiNode?>?>, sizes: IntArray, i: Int, node: ViterbiNode) {
        val row = rows[i] ?: arrayOfNulls(0)
        rows[i] = (if (sizes[i] < row.size) row else row.copyOf(sizes[i] * 2 + 2)).also { it[sizes[i]++] = node }
    }

    /** A word IPADIC has from its own dictionary, read for what JMdict writes it as, though the line has it in kana. */
    internal class JmdictToken(wordId: Int, surface: String, position: Int, dictionary: TokenInfoDictionary) :
        Token(wordId, surface, ViterbiNode.Type.KNOWN, position, dictionary)

    internal companion object {
        private const val SMALL_KANA = "ぁぃぅぇぉっゃゅょゎァィゥェォッャュョヮ"
        private val VOWELS = listOf("あかさたなはまやらわがざだばぱぁゃゎ", "いきしちにひみりぎじぢびぴぃ", "うくすつぬふむゆるぐずづぶぷぅゅゔ", "えけせてねへめれげぜでべぺぇ", "おこそとのほもよろをごぞどぼぽぉょ")

        private fun smallAfterKana(text: String, at: Int) = at in 1 until text.length && text[at] in SMALL_KANA && Readings.isKana(text[at - 1])

        /**
         * Whether [small] after [before] draws out its vowel, とぉ and めぇ, rather than make a sound with it, as ぃ
         * does in でぃ and ぇ in ふぇ.
         */
        fun drawsOut(before: Char, small: Char) = small in "ぁぃぅぇぉァィゥェォ" && vowel(small).let { it >= 0 && it == vowel(before) }

        private fun vowel(kana: Char) = Readings.hiragana(kana.toString()).single().let { c -> VOWELS.indexOfFirst { c in it } }

        private fun drawsOut(text: String, at: Int) = smallAfterKana(text, at) && drawsOut(text[at - 1], text[at])

        /** A small kana at [at] said with the kana before it, as ゃ in しゃ and ぃ in でぃ. */
        private fun saidWith(text: String, at: Int) = smallAfterKana(text, at) && !drawsOut(text, at)

        private fun wrongStart(text: String, token: Token) = wrongStart(text, token.position, token.isKnown, token.surface)

        /**
         * A word at [at] that starts on a small kana after another kana, but small vowels alone that draw out the
         * word before, as they go with it, and IPADIC's own words that start with っ: って and っぽい, and っ alone after
         * て or で, as in 消えてった.
         */
        private fun wrongStart(text: String, at: Int, known: Boolean, surface: String) = when {
            !smallAfterKana(text, at) -> false
            drawsOut(text, at) -> surface.indices.any { !drawsOut(text, at + it) }
            else -> !(known && (surface.length > 1 || text[at - 1] in "てで"))
        }
    }
}
