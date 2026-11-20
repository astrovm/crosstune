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
import com.atilika.kuromoji.viterbi.ViterbiBuilder
import com.atilika.kuromoji.viterbi.ViterbiLattice
import com.atilika.kuromoji.viterbi.ViterbiNode
import com.atilika.kuromoji.viterbi.ViterbiSearcher

/**
 * IPADIC's tokenizer, kept from starting a word on a small kana. In kana alone IPADIC can split かいしゃのけいひ
 * into かいし and ゃのけいひ, though ゃ is said with the し before it (Issue #924). Where it does, that part of the
 * line is searched again as Kuromoji searches it, with no word that starts on a small kana after another kana, and
 * with the [words] in kana JMdict writes in kanji offered in kana too, as かいしゃ for 会社 and けいひ for 経費, as
 * IPADIC knows few words in kana. A line it splits right comes back as Kuromoji split it.
 */
internal class SmallKanaTokenizer(private val words: () -> Map<String, String>) : Tokenizer.Builder() {
    private val plain: Tokenizer = build()
    private val lattices = ViterbiBuilder(doubleArrayTrie, tokenInfoDictionary, unknownDictionary, userDictionary, mode)
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
        if (tokens.none { wrongStart(text, it.position, it.isKnown, it.surface) }) return tokens
        // Kuromoji reads a line in parts that each end at a 。 or 、, so each part is searched again alone.
        var from = 0
        return text.split(PART_END).filter(String::isNotEmpty).flatMap { part ->
            val at = from
            from += part.length
            val own = tokens.filter { it.position in at until from }
            if (own.none { wrongStart(text, it.position, it.isKnown, it.surface) }) own else searched(part, at) ?: own
        }
    }

    /**
     * [text] as Kuromoji searches it, bar the words that start wrong, with JMdict's words in kana offered too; null
     * when no way through it is left. The words Kuromoji would find at each place are those it finds at the start of
     * the rest of the text, but for a word it doesn't know that starts inside one it doesn't know before it.
     */
    private fun searched(text: String, offset: Int): List<Token>? {
        val lattice = ViterbiLattice(text.length + 2).apply { addBos() }
        val reached = BooleanArray(text.length + 1).also { it[0] = true }
        val offered = HashSet<ViterbiNode>()
        var unknownEnd = -1
        for (at in text.indices) {
            if (!reached[at]) continue
            val found = lattices.build(text.substring(at)).startIndexArr[1].filterNotNull()
                .filter { it.type == ViterbiNode.Type.KNOWN || at >= unknownEnd }
                .map { ViterbiNode(it.wordId, it.surface, it.leftId, it.rightId, it.wordCost, at, it.type) }
            val kept = (found + offer(text, at).also(offered::addAll))
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

    /** JMdict's words in kana at [at] in [text], each as IPADIC's word for how JMdict writes it, when that reads so. */
    private fun offer(text: String, at: Int): List<ViterbiNode> = (at + 2..text.length).mapNotNull { end ->
        val kana = text.substring(at, end)
        val written = words()[kana]?.takeIf { it != kana } ?: return@mapNotNull null
        val word = plain.tokenize(written).singleOrNull()?.takeIf { it.isKnown && Readings.hiragana(it.reading) == kana }
            ?: return@mapNotNull null
        lattices.build(written).startIndexArr[1]
            .firstOrNull { it != null && it.surface == written && tokenInfoDictionary.getAllFeatures(it.wordId) == word.allFeatures }
            ?.let { ViterbiNode(it.wordId, kana, tokenInfoDictionary, at, ViterbiNode.Type.KNOWN) }
    }

    /**
     * A word the dictionary doesn't know from [at] through the small kana right after it, and the kana a small っ
     * doubles, when none of [nodes] holds them: so にっき, a word neither knows, is still one, not に and っき.
     */
    private fun spanning(text: String, at: Int, nodes: List<ViterbiNode>): List<ViterbiNode> {
        if (!smallAfterKana(text, at + 1) || nodes.any { it.surface.length > 1 }) return emptyList()
        var end = at + 1
        while (smallAfterKana(text, end)) end++
        if (text[end - 1] in "っッ" && end < text.length && Readings.isKana(text[end])) end++
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

    private companion object {
        val PART_END = Regex("(?<=[。、])")

        const val SMALL_KANA = "ぁぃぅぇぉっゃゅょゎァィゥェォッャュョヮ"

        fun smallAfterKana(text: String, at: Int) = at in 1 until text.length && text[at] in SMALL_KANA && Readings.isKana(text[at - 1])

        /**
         * A word at [at] that starts on a small kana after another kana, but IPADIC's own words that start with っ:
         * って and っぽい, and っ alone after て or で, as in 消えてった.
         */
        fun wrongStart(text: String, at: Int, known: Boolean, surface: String) =
            smallAfterKana(text, at) && !(known && (surface.length > 1 || text[at - 1] in "てで"))
    }
}
