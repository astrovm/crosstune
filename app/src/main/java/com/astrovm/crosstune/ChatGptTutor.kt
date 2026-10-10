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
    suspend fun translate(lines: List<String>, language: String): List<String?>? {
        val words = lines.filter { line -> line.any(Char::isLetter) }.distinct()
        // Each model's own, so picking another translates anew.
        val model = chatGpt.model() ?: return null
        val key = { line: String -> "translation|$model|$language|$line" }
        val known = words.associateWith { cache.get(key(it)) }
        val missing = words.filter { known[it] == null }
        val found = if (missing.isEmpty()) {
            emptyMap()
        } else {
            val name = nameOf(language)
            val reply = chatGpt.ask(
                "You translate song lyrics into $name. The user sends the song's lines as a JSON array of strings, in order. " +
                    "Translate them as one song, so each line reads naturally in $name and keeps the meaning, tone, slang and wordplay the song gives it. " +
                    "Reply with only a JSON array of strings, the same length and in the same order, one translation for each line. " +
                    "A line already in $name stays as it is.",
                JSONArray(missing).toString()
            )
            val translated = (reply as? ChatGptReply.Answer)?.text?.let(::jsonArray) ?: return null
            if (translated.length() != missing.size) return null
            missing.indices.associate { missing[it] to translated.getString(it).trim() }
        }
        found.forEach { (line, translation) -> cache.put(key(line), translation) }
        cache.save()
        val all = known.filterValues { it != null }.mapValues { it.value!! } + found
        // A line already in [language] comes back as it was, and is no use under itself.
        return lines.map { line -> all[line]?.takeUnless { it.equals(line.trim(), ignoreCase = true) } }
    }

    /**
     * What [song], whose words are [lines], is about, in [language]: its story, who's speaking, its
     * mood, images and references. [onText] gets it as it's written.
     */
    suspend fun explainSong(song: MusicMetadata, lines: List<String>, language: String, onText: (String) -> Unit): ChatGptReply {
        val model = chatGpt.model() ?: return ChatGptReply.Failed
        val key = songKey(model, language, song)
        cache.get(key)?.let { return ChatGptReply.Answer(it) }
        val reply = chatGpt.ask(
            "You help someone understand the songs they listen to, often in a language they're learning. The user sends a song's lyrics. " +
                "In ${nameOf(language)}, explain what the song is about, going by the lyrics, without inventing facts about the artist. " +
                "Reply in three parts, separated by a line with only $PART_BREAK: first its story, who's speaking, to whom and what happens; " +
                "then its mood; then any cultural or historical references, left empty when there are none. " +
                "One or two short sentences each, about 100 words in all, in plain text, without headings, labels or Markdown.",
            "Song: ${song.title} by ${song.artist}\n\nLyrics:\n${lines.joinToString("\n")}",
            onText
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
        val key = "line2|$model|$language|${song.title}|${song.artist}|$index|$line"
        cache.get(key)?.let { return ChatGptReply.Answer(it) }
        val about = cache.get(songKey(model, language, song))?.let { "What the song is about:\n$it\n\n" }.orEmpty()
        val reply = chatGpt.ask(
            "You help someone learn a language through the songs they listen to. The user sends a song's lyrics, numbered, and the number of one line. " +
                "First read the whole song to understand its story. Then, in ${nameOf(language)}, explain that line as part of the song, " +
                "in two parts separated by a line with only $PART_BREAK: first, in one or two short sentences, what it means at that point, " +
                "who's saying it and to whom, and how it ties to the lines around it; then one short note on grammar, slang, an idiom or a reference " +
                "in it worth knowing, left empty when there's none. About 50 words in all, in plain text, without headings, labels or Markdown.",
            "Song: ${song.title} by ${song.artist}\n\n$about" +
                "Lyrics:\n${lines.mapIndexed { number, text -> "${number + 1}. $text" }.joinToString("\n")}\n\nLine ${index + 1}: $line",
            onText
        )
        return reply.also { keep(key, it) }
    }

    private fun songKey(model: String, language: String, song: MusicMetadata) = "song2|$model|$language|${song.title}|${song.artist}"

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
 * written, those so far, without the start of a break that's only half there.
 */
internal fun explanationParts(text: String): List<String> =
    text.split(Regex("""\n\s*-{3,}\s*(?:\n|$)""")).map { it.trim().trimEnd('-').trim() }
