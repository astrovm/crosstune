package com.astrovm.crosstune

import androidx.annotation.VisibleForTesting
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
        var next = 0
        for (wrong in tokens.indices) {
            if (wrong < next) continue
            val crossing = crossingWord(text, tokens[wrong])
            if (!wrongStart(text, tokens[wrong]) && !crossing) continue
            // A word that starts wrong is searched again with the word before it, as the kana it starts on is said
            // with that word's last, and with the one before that while that one starts on a small kana too, っし in
            // ないっしゅん, so the search knows what kana it comes after.
            var first = wrong - 1
            while (first > next && (smallAfterKana(text, tokens[first].position) ||
                    (crossing && tokens[first - 1].surface.all(Readings::isKana)))) first--
            // Words that start wrong one after another are searched again together, and a small っ at the end doubles
            // the kana after it, so that word is searched again with it, にっ and き.
            var last = wrong
            while (last + 1 < tokens.size && (tokens[last].surface.last() in "っッ" || wrongStart(text, tokens[last + 1]) ||
                    (crossing && tokens[last + 1].surface.all(Readings::isKana)))) last++
            out += tokens.subList(next, first)
            val from = tokens[first].position
            val to = tokens[last].position + tokens[last].surface.length
            out += searched(text.substring(from, to), from) ?: tokens.subList(first, last + 1)
            next = last + 1
        }
        out += tokens.subList(next, tokens.size)
        return out
    }

    /** The word IPADIC knows for [text], when it keeps it whole. */
    fun knownWord(text: String): Token? = plain.tokenize(text).singleOrNull()?.takeIf { it.isKnown }

    /** A noun IPADIC knows as one word, read as written. */
    fun isNoun(text: String): Boolean = knownWord(text)?.let {
        it.partOfSpeechLevel1 == "名詞" && Readings.hiragana(it.reading) == Readings.hiragana(text)
    } == true

    /**
     * [text] as Kuromoji searches it, bar the words that start wrong, with JMdict's words in kana offered too; null
     * when no way through it is left.
     */
    @VisibleForTesting
    internal fun searched(text: String, offset: Int): List<Token>? = search(text, offset, false) ?: search(text, offset, true)

    private fun search(text: String, offset: Int, bridge: Boolean): List<Token>? {
        val lattice = ViterbiLattice(text.length + 2).apply { addBos() }
        val reached = BooleanArray(text.length + 1).also { it[0] = true }
        val offered = HashSet<ViterbiNode>()
        val bridged = HashMap<ViterbiNode, Pair<Boolean, String>>()
        var unknownEnd = -1
        for (at in text.indices) {
            if (!reached[at]) continue
            val kept = (startingAt(text, at, at >= unknownEnd) + offer(text, at).also(offered::addAll))
                .filterNot { wrongStart(text, at, it.type == ViterbiNode.Type.KNOWN, it.surface) }
            val extended = if (bridge) bridge(text, at, kept, offered, bridged) else emptyList()
            for (node in kept + spanning(text, at, kept) + extended) {
                lattice.place(node, at)
                reached[at + node.surface.length] = true
                if (node.type == ViterbiNode.Type.UNKNOWN) unknownEnd = at + node.surface.length
            }
        }
        if (!reached[text.length]) return null
        lattice.addEos()
        return searcher.search(lattice).filterNot { it.type == ViterbiNode.Type.KNOWN && it.wordId == -1 }.map { node ->
            val dictionary = if (node.type == ViterbiNode.Type.UNKNOWN) unknownDictionary else tokenInfoDictionary
            if (node in bridged) {
                val (jmdict, ending) = bridged.getValue(node)
                BridgedToken(node.wordId, node.surface, offset + node.startIndex, tokenInfoDictionary, jmdict, ending)
            }
            else if (node in offered) JmdictToken(node.wordId, node.surface, offset + node.startIndex, tokenInfoDictionary)
            else Token(node.wordId, node.surface, node.type, offset + node.startIndex, dictionary)
        }
    }

    /** Carry stranded small っ on the known word before them, without taking the following word's lookup. */
    private fun bridge(
        text: String, at: Int, nodes: List<ViterbiNode>, offered: Set<ViterbiNode>, bridged: MutableMap<ViterbiNode, Pair<Boolean, String>>
    ): List<ViterbiNode> = nodes.mapNotNull { node ->
        val end = at + node.surface.length
        if (node.type != ViterbiNode.Type.KNOWN || !Readings.isKana(node.surface.last())) return@mapNotNull null
        if (text.getOrNull(end) !in listOf('っ', 'ッ')) return@mapNotNull null
        val ending = text.substring(end).takeWhile { it in "っッ" }
        ViterbiNode(node.wordId, node.surface + ending, tokenInfoDictionary, at, ViterbiNode.Type.KNOWN)
            .also { bridged[it] = (node in offered) to ending }
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
            // 0 says longer words go on from it, and IPADIC keeps no word under 0.
            tokenInfoDictionary.lookupWordIds(entry).mapTo(known) { ViterbiNode(it, text.substring(at, end), tokenInfoDictionary, at, ViterbiNode.Type.KNOWN) }
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
        return (at + 2..minOf(text.length, at + words.longestSearch)).mapNotNull { end ->
            // A word may go on in a small vowel after it: ちょうだぃ is ちょうだい, not 長打 drawn out.
            if (drawsOut(text, end)) return@mapNotNull null
            val kana = text.substring(at, end)
            val written = words.search[kana]?.takeIf { it != kana } ?: return@mapNotNull null
            val word = plain.tokenize(written).singleOrNull()?.takeIf { it.isKnown && Readings.hiragana(it.reading) == kana }
                ?: return@mapNotNull null
            startingAt(written, 0, unknown = false)
                .firstOrNull { it.surface == written && tokenInfoDictionary.getAllFeatures(it.wordId) == word.allFeatures }
                ?.let { ViterbiNode(it.wordId, kana, tokenInfoDictionary, at, ViterbiNode.Type.KNOWN) }
        }
    }

    /**
     * Words the dictionary doesn't know, for where [nodes] leave a small kana no way to be said: the small vowels
     * at [at] that draw out the word before, and from [at] through the small kana said with it, the kana a small っ
     * among them doubles and the small kana said with that, when none of [nodes] reaches as far. So にっき, a word
     * neither knows, is still one, not に and っき, and so is ちゅっちゅ, not ち and ゅっちゅ. None starts on a small
     * kana said with the kana before it.
     */
    private fun spanning(text: String, at: Int, nodes: List<ViterbiNode>): List<ViterbiNode> {
        val end = when {
            drawsOut(text, at) -> (at..text.length).first { it == text.length || !drawsOut(text, it) }
            saidWith(text, at) || !saidWith(text, at + 1) -> null
            else -> saidThrough(text, at + 1).takeIf { end -> nodes.none { at + it.surface.length >= end } }
        }
        return listOfNotNull(end).flatMap { end ->
            unknownDictionary.lookupWordIds(characterDefinitions.lookupCategories(text[at])[0])
                .map { ViterbiNode(it, text.substring(at, end), unknownDictionary, at, ViterbiNode.Type.UNKNOWN) }
        }
    }

    /** Where the small kana said with the kana before them end, from [from] on, past the kana a small っ among them doubles. */
    private fun saidThrough(text: String, from: Int): Int {
        val end = (from..text.length).first { !saidWith(text, it) }
        return if (text[end - 1] in "っッ" && end < text.length && Readings.isKana(text[end])) saidThrough(text, end + 1) else end
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

    /**
     * Whether the small kana at [at] in [text] draws out the sound before it, as ぉ in ありがとぉ, ぅ in ありがとぅ,
     * ぃ in せんせぃ and くださぃ, and ぇ in ねーぇ, rather than make a sound with it, as ぃ does in でぃ, ぇ in ふぇ
     * and ぅ in とぅないと.
     */
    fun drawsOut(text: String, at: Int): Boolean {
        if (!smallAfterKana(text, at) || text[at] !in SMALL_VOWELS) return false
        val pair = text.substring(at - 1, at + 1)
        return pair !in SOUNDS && (pair !in SOUNDS_BEFORE_KANA || !saidAfter(text, at + 1) || endsWord(text, at))
    }

    /** Whether a kana that is said, not a small vowel or ー that draws out what comes before, is at [at]. */
    private fun saidAfter(text: String, at: Int) = text.getOrNull(at)?.let { Readings.isKana(it) && it !in SMALL_VOWELS && it != 'ー' } == true

    /** Whether a word the dictionary has, longer than the kana right before [at], ends there: ありがと in ありがとぅね. */
    private fun endsWord(text: String, at: Int) = (0..at - 2).any { start -> doubleArrayTrie.lookup(text.substring(start, at), 0, 0) > 0 }

    /** A small kana at [at] said with the kana before it, as ゃ in しゃ and ぃ in でぃ. */
    private fun saidWith(text: String, at: Int) = smallAfterKana(text, at) && !drawsOut(text, at)

    private fun wrongStart(text: String, token: Token) = wrongStart(text, token.position, token.isKnown, token.surface)

    /** Search again if a common word with small kana crosses an IPADIC split, including IPADIC's own っ words. */
    private fun crossingWord(text: String, token: Token): Boolean {
        val at = token.position
        if (at == 0 || !Readings.isKana(text[at - 1]) || !Readings.isKana(text[at])) return false
        val known = words()
        for (start in maxOf(0, at - known.longestSearch + 1) until at) {
            for (end in at + 1..minOf(text.length, start + known.longestSearch)) {
                if (text.substring(start, end) in known.small) return true
            }
        }
        return false
    }

    /**
     * A word at [at] that starts on a small kana after another kana. Not small vowels alone that draw out the word
     * before, as they go with it, nor a word IPADIC has that starts there, as only its words that start with っ do:
     * って, っぽい and っす. っ alone, which IPADIC has too, only after て or で, as ていく said short in 消えてった,
     * or where no kana follows for it to double: then it cuts the word before short, as in きゃっ, and goes with it.
     */
    private fun wrongStart(text: String, at: Int, known: Boolean, surface: String) = when {
        !smallAfterKana(text, at) -> false
        drawsOut(text, at) -> surface.indices.any { !drawsOut(text, at + it) }
        cutsShort(text, at, surface) -> false
        else -> !(known && (surface.length > 1 || text[at - 1] in "てで"))
    }
    /** A word IPADIC has from its own dictionary, read for what JMdict writes it as, though the line has it in kana. */
    internal class JmdictToken(wordId: Int, surface: String, position: Int, dictionary: TokenInfoDictionary) :
        Token(wordId, surface, ViterbiNode.Type.KNOWN, position, dictionary)

    /** A known word followed by a small っ that otherwise leaves the search with no way through. */
    internal class BridgedToken(wordId: Int, surface: String, position: Int, dictionary: TokenInfoDictionary, val jmdict: Boolean, val ending: String) :
        Token(wordId, surface, ViterbiNode.Type.KNOWN, position, dictionary)

    internal companion object {
        private const val SMALL_KANA = "ぁぃぅぇぉっゃゅょゎァィゥェォッャュョヮ"
        private const val SMALL_VOWELS = "ぁぃぅぇぉァィゥェォ"

        /**
         * A kana and a small vowel that make a sound of their own, as in words from other languages: ファ, ウィ, ティ,
         * ディ, トゥ; and a kana of the い row with a small ぇ, as in ぴぇん and ひぇー. Hiragana とぅ and どぅ only
         * before more kana and after no word that ends on the と, とぅないと: after a word, ありがとぅね, and at the end
         * of one, ありがとぅ, the ぅ draws out the と, as hiragana lyrics spell a long vowel.
         */
        private val SOUNDS = (
            "ふぁふぃふぇふぉゔぁゔぃゔぇゔぉうぃうぇうぉつぁつぃつぇつぉくぁくぃくぇくぉぐぁすぃずぃてぃでぃしぇじぇちぇいぇ" +
                "きぇぎぇにぇひぇびぇぴぇみぇりぇ" +
                "ファフィフェフォヴァヴィヴェヴォウィウェウォツァツィツェツォクァクィクェクォグァスィズィティディシェジェチェイェトゥドゥ" +
                "キェギェニェヒェビェピェミェリェ"
            ).chunked(2).toSet()
        private val SOUNDS_BEFORE_KANA = setOf("とぅ", "どぅ")

        private fun smallAfterKana(text: String, at: Int) = at in 1 until text.length && text[at] in SMALL_KANA && Readings.isKana(text[at - 1])

        /** Whether [surface] at [at] is a small っ alone with no kana after it to double, as in きゃっ or かいしゃっ！. */
        fun cutsShort(text: String, at: Int, surface: String) = surface.length == 1 && surface[0] in "っッ" && text.getOrNull(at + 1)?.let(Readings::isKana) != true

    }
}
