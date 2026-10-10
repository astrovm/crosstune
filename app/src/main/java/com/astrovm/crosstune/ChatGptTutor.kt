package com.astrovm.crosstune

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale

/**
 * Lyrics translated, explained and looked up word by word by ChatGPT, on the user's plan, with
 * what they mean in the song they're in. Each answer is kept in [cache], so asking again costs
 * nothing.
 */
internal class ChatGptTutor(private val chatGpt: ChatGpt, private val cache: LookupCache) {

    /**
     * [lines] in [language], translated together as the song they are, each line's translation, or
     * null where there's nothing to translate; null for all of them when it couldn't be done.
     */
    suspend fun translate(lines: List<String>, language: String, song: MusicMetadata? = null, onLines: (List<String?>) -> Unit = {}): List<String?>? {
        val words = lines.filter { line -> line.any(Char::isLetter) }.distinct()
        // Each model's own, so picking another translates anew.
        val model = chatGpt.model() ?: return null
        // Kept for [song]: the same line in another song may mean something else there.
        val key = { line: String -> "translation|$model|$language|${song?.title}|${song?.artist}|$line" }
        val known = words.associateWith { cache.get(key(it)) }
        val missing = words.filter { known[it] == null }
        val kept = known.filterValues { it != null }.mapValues { it.value!! }
        // A line already in [language] comes back as it was, and is no use under itself.
        fun shown(all: Map<String, String>) = lines.map { line -> all[line]?.takeUnless { it.equals(line.trim(), ignoreCase = true) } }
        val found = if (missing.isEmpty()) {
            emptyMap()
        } else {
            val name = nameOf(language)
            // Tried on real songs: thinking less, the first line comes in 1.5 s rather than 5, as good.
            val reply = chatGpt.ask(
                "You translate song lyrics into $name. The user sends the song's lines as a JSON array of strings, in order. " +
                    "Translate them as one song, so each line reads naturally in $name and keeps the meaning, tone, slang and wordplay the song gives it. " +
                    "Reply with only a JSON array of strings, the same length and in the same order, one translation for each line. " +
                    "A line already in $name stays as it is.",
                JSONArray(missing).toString(),
                effort = "low"
            ) { text ->
                // Each line shown as soon as it's translated.
                val so = finishedStrings(text).take(missing.size)
                onLines(shown(kept + missing.zip(so).toMap()))
            }
            val translated = (reply as? ChatGptReply.Answer)?.text?.let(::jsonArray) ?: return null
            if (translated.length() != missing.size) return null
            missing.indices.associate { missing[it] to translated.getString(it).trim() }
        }
        found.forEach { (line, translation) -> cache.put(key(line), translation) }
        cache.save()
        return shown(kept + found)
    }

    /**
     * What [song], whose words are [lines], is about, in [language]: its story, who's speaking, its
     * mood, images and references. [onText] gets it as it's written.
     */
    suspend fun explainSong(song: MusicMetadata, lines: List<String>, language: String, onText: (String) -> Unit): ChatGptReply {
        val model = chatGpt.model() ?: return ChatGptReply.Failed
        val key = songKey(model, language, song)
        cache.get(key)?.let { return ChatGptReply.Answer(it) }
        // Tried on real songs with GPT-6-Luna: told to go by the lyrics alone, it never searched;
        // told to search first, it found the album, year and writers, but now and then gave a song
        // another's writers, from a page listing many. Held to pages about the song by name, it
        // got none wrong in 20 tries, leaving the writers out when unsure.
        val reply = chatGpt.ask(
            "You help someone understand the songs they listen to, often in a language they're learning. The user sends a song's lyrics. " +
                "First search the web for this exact song: when and on what album it came out, who wrote it, and any story behind it. " +
                "Only state a fact when a page you found gives it for this song by name: pages listing many of the artist's songs mix them up. " +
                "Leave out who wrote it unless a page names them for this song. " +
                "Then, in ${nameOf(language)}, explain the song in four parts, separated by a line with only $PART_BREAK: " +
                "first what you found about it, with no links or sources; then its story, who's speaking, to whom and what happens; " +
                "then its mood; then one or two of its key lines, quoted as written, each with what it means and why it matters. " +
                "One or two short sentences each, about 120 words in all. Never say what you didn't find, or that something is missing or absent. " +
                "Plain text, without headings, labels, links or Markdown.",
            "Song: ${song.title} by ${song.artist}\n\nLyrics:\n${lines.joinToString("\n")}",
            webSearch = true,
            onText = onText
        )
        return reply.also { keep(key, it) }
    }

    /**
     * Line [index] of [song], whose words are [lines], explained in [language] as part of the song:
     * what it means at that point, who's saying it, how it ties to the lines around it, then its
     * grammar, slang and references. What the song is about goes along when it's known. [onText] gets
     * the explanation as it's written.
     */
    suspend fun explain(song: MusicMetadata, lines: List<String>, index: Int, language: String, onText: (String) -> Unit): ChatGptReply {
        val line = lines.getOrNull(index) ?: return ChatGptReply.Failed
        val model = chatGpt.model() ?: return ChatGptReply.Failed
        val key = "line4|$model|$language|${song.title}|${song.artist}|$index|$line"
        cache.get(key)?.let { return ChatGptReply.Answer(it) }
        val about = cache.get(songKey(model, language, song))?.let { "What the song is about:\n$it\n\n" }.orEmpty()
        // Tried on real songs with GPT-6-Luna: held to 45 words and thinking less, explanations
        // came to 47 words in 3.7 s, from 59 in 5.7 s, as good.
        val reply = chatGpt.ask(
            "You help someone learn a language through the songs they listen to. The user sends a song's lyrics, numbered, and the number of one line. " +
                "First read the whole song to understand its story. Then, in ${nameOf(language)}, explain that line as part of the song, " +
                "in two parts separated by a line with only $PART_BREAK: first, in one or two short sentences, what it means at that point, " +
                "who's saying it and to whom; mention the lines around it only when that's needed to understand it. " +
                "Then, in one short sentence, one note on grammar, slang, an idiom or a reference in it worth knowing. " +
                "When there's nothing worth noting, end after the first part, without saying so. " +
                "At most 45 words in all, in plain text, without headings, labels or Markdown.",
            "Song: ${song.title} by ${song.artist}\n\n$about" +
                "Lyrics:\n${lines.mapIndexed { number, text -> "${number + 1}. $text" }.joinToString("\n")}\n\nLine ${index + 1}: $line",
            effort = "low",
            onText = onText
        )
        return reply.also { keep(key, it) }
    }

    private fun songKey(model: String, language: String, song: MusicMetadata) = "song5|$model|$language|${song.title}|${song.artist}"

    /** An answer kept, so it's not asked again; what couldn't be answered is. */
    private suspend fun keep(key: String, reply: ChatGptReply) {
        if (reply !is ChatGptReply.Answer) return
        cache.put(key, reply.text.trim())
        cache.save()
    }

    /** What [word] means as used in [line], in [language], and what kind of word it is there; null when it couldn't be found. */
    suspend fun meaning(word: String, line: String, language: String): Definition? {
        val model = chatGpt.model() ?: return null
        val key = "word|$model|$language|$line|$word"
        val answer = cache.get(key) ?: run {
            val name = nameOf(language)
            val reply = chatGpt.ask(
                "You help someone learn a language through song lyrics. The user sends a line from a song and a word in it. " +
                    "Reply with only a JSON object with two strings: \"meaning\", what the word means as used in that line, in a few words of $name, " +
                    "and \"type\", what kind of word it is there, in one or two words of $name, such as a noun, or a verb in the past tense.",
                JSONObject().put("line", line).put("word", word).toString()
            )
            (reply as? ChatGptReply.Answer)?.text ?: return null
        }
        val json = jsonObject(answer) ?: return null
        val meaning = json.optString("meaning").trim().ifEmpty { return null }
        cache.put(key, answer)
        cache.save()
        return Definition(json.optString("type").trim().ifEmpty { null }, meaning)
    }

    /** The JSON array in [text], which may come with words or a code block around it. */
    private fun jsonArray(text: String): JSONArray? = try {
        JSONArray(text.substring(text.indexOf('['), text.lastIndexOf(']') + 1))
    } catch (_: JSONException) {
        null
    } catch (_: IndexOutOfBoundsException) {
        null
    }

    private fun jsonObject(text: String): JSONObject? = try {
        JSONObject(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1))
    } catch (_: JSONException) {
        null
    } catch (_: IndexOutOfBoundsException) {
        null
    }

    /** [language] as ChatGPT is told it, e.g. "Portuguese (Brazil)" for "pt-BR". */
    private fun nameOf(language: String): String = Locale.forLanguageTag(language).getDisplayName(Locale.ENGLISH)
}

/** What ChatGPT puts between the parts of an explanation. */
internal const val PART_BREAK = "---"

/**
 * The parts of an explanation, in order, empty where it left one out; while it's still being
 * written, those so far, without the start of a break that's only half there. Asked to break
 * them with a line of dashes, ChatGPT sometimes leaves a blank line instead, which then does.
 */
internal fun explanationParts(text: String): List<String> {
    val parts = plainText(text).split(Regex("""\n[ \t]*-{3,}[ \t]*(?:\n|$)""")).map { it.trim().trimEnd('-').trim() }
    if (parts.size > 1) return parts
    return parts.single().split(Regex("""\n[ \t]*\n\s*""")).map { it.trim() }
}

/**
 * [text] as plain words: ChatGPT, told not to, still writes *album names* in Markdown and adds
 * sources as "([site](https://…))", which would show as they are.
 */
internal fun plainText(text: String): String = text
    .replace(Regex("""\s*\(\[[^\]]*]\([^)]*\)\)"""), "")
    .replace(Regex("""\[([^\]]+)]\(https?://[^)]*\)"""), "$1")
    .replace(Regex("""\*\*(.+?)\*\*"""), "$1")
    .replace(Regex("""(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?![\w*])"""), "$1")

/**
 * The strings of a JSON array finished so far in [text], an answer still being written: those
 * whose closing quote has come, not the one still on its way.
 */
internal fun finishedStrings(text: String): List<String> {
    val start = text.indexOf('[').takeIf { it >= 0 } ?: return emptyList()
    val strings = mutableListOf<String>()
    var at = start + 1
    while (true) {
        while (at < text.length && (text[at].isWhitespace() || text[at] == ',')) at++
        if (at >= text.length || text[at] != '"') return strings
        var end = at + 1
        while (end < text.length && text[end] != '"') end += if (text[end] == '\\') 2 else 1
        if (end >= text.length) return strings
        strings += try {
            JSONArray("[" + text.substring(at, end + 1) + "]").getString(0)
        } catch (_: JSONException) {
            return strings
        }
        at = end + 1
    }
}
