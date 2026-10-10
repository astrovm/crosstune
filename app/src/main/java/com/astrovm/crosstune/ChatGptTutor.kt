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
     * [line] of [song], whose words are [lines], explained in [language]: what it means there, its
     * grammar and any slang or references. [onText] gets the explanation as it's written.
     */
    suspend fun explain(song: MusicMetadata, lines: List<String>, line: String, language: String, onText: (String) -> Unit): ChatGptReply {
        val model = chatGpt.model() ?: return ChatGptReply.Failed
        val key = "explanation|$model|$language|${song.title}|${song.artist}|$line"
        cache.get(key)?.let { return ChatGptReply.Answer(it) }
        val reply = chatGpt.ask(
            "You help someone learn a language through the songs they listen to. The user sends a song's lyrics and one line from it. " +
                "In ${nameOf(language)}, explain that line: what it means in the song, the grammar worth knowing in it, and any slang, idioms or cultural references. " +
                "Keep it short, a few sentences, in plain text without Markdown.",
            "Song: ${song.title} by ${song.artist}\n\nLyrics:\n${lines.joinToString("\n")}\n\nLine: $line",
            onText
        )
        if (reply is ChatGptReply.Answer) {
            cache.put(key, reply.text.trim())
            cache.save()
        }
        return reply
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
