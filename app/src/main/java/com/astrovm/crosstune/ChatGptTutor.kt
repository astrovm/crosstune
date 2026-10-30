package com.astrovm.crosstune

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale

/**
 * Lyrics translated, explained and looked up word by word by ChatGPT, on the user's plan, with
 * what they mean in the song they're in. Each answer is kept in [cache], so asking again costs
 * nothing; each line translated in [lines], so a song's many lines don't push out answers that
 * took longer, e.g. from the web.
 */
internal class ChatGptTutor(private val chatGpt: ChatGpt, private val cache: LookupCache, private val lines: LookupCache = cache) {

    /**
     * [lines] in [language], translated together as the song they are, each line's translation, or
     * null where there's nothing to translate; null for all of them when it couldn't be done.
     */
    suspend fun translate(lines: List<String>, language: String, song: MusicMetadata? = null, onLines: (List<String?>) -> Unit = {}): List<String?>? {
        val words = lines.filter { line -> line.any(Char::isLetter) }.distinct()
        // Each model's own, so picking another translates anew.
        val model = chatGpt.model() ?: return null
        // Kept for [song]: the same line in another song may mean something else there.
        val key = { line: String -> "translation$TRANSLATION_VERSION|$model|$language|${song?.title}|${song?.artist}|$line" }
        val known = words.associateWith { this.lines.get(key(it)) }
        val missing = words.filter { known[it] == null }
        val kept = known.filterValues { it != null }.mapValues { it.value!! }
        // A line already in [language] comes back empty, or as it was, and is no use under itself.
        fun shown(all: Map<String, String>) = lines.map { line -> all[line]?.takeUnless { it.isBlank() || it.equals(line.trim(), ignoreCase = true) } }
        val found = if (missing.isEmpty()) {
            emptyMap()
        } else {
            val name = nameOf(language)
            // Tried on real songs: thinking less, the first line comes in 1.5 s rather than 5, as good.
            // Said first, and as empty rather than repeated: told after, or to repeat it, lines in another
            // variety came back "put right" into this one, and repeating lyrics can cut the answer off.
            val reply = chatGpt.ask(
                "You translate song lyrics into $name. The user sends the song's lines as a JSON array of strings, in order. " +
                    "A line already in ${sameAs(language)}, from anywhere and however it's spelled, isn't translated: its string is empty. " +
                    "Translate only the others, as one song, so each reads naturally in $name and keeps the meaning, tone, slang and wordplay the song gives it. " +
                    // Tried on a Japanese song with GPT-6-Luna: as one song, a sentence over two lines came back in Spanish's order
                    // in 4 of 5 tries, the guitar under the line before "ギター"; held to each line's own part, with an example, in none.
                    "Each translation says only what its own line says, as it's sung under it. Where a sentence runs over several lines, as in Japanese or Korean, " +
                    "each line keeps its own part, even in another order than the sentence would have in one line: " +
                    "[\"誰も知らない\", \"秘密の場所で\"] is [\"that no one knows\", \"in a secret place\"], never [\"in a secret place\", \"that no one knows\"]. " +
                    "Reply with only a JSON array of strings, the same length and in the same order.",
                JSONArray(missing).toString(),
                effort = "low"
            ) { text ->
                // Each line shown as soon as it's translated.
                val so = finishedStrings(text).take(missing.size)
                onLines(shown(kept + missing.zip(so).toMap()))
            }
            val translated = reply.array() ?: return null
            if (translated.length() != missing.size) return null
            missing.indices.associate { missing[it] to translated.getString(it).trim() }
        }
        found.forEach { (line, translation) -> this.lines.put(key(line), translation) }
        this.lines.save()
        return shown(kept + found)
    }

    /**
     * What [song], whose words are [lines], is about, in [language]: what's known of it, its story,
     * its mood and a key line. [onText] gets it as it's written.
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
                "first what you found about it, as two to four lines of the form \"Label: value\", with short labels like the year, the album or the song it covers; " +
                "then its story, who's speaking, to whom and what happens, in one or two short sentences; " +
                "then its mood, as two or three words separated by \" · \"; " +
                "then one of its key lines, quoted as written on a line of its own, and under it, in one short sentence, what it means. " +
                "About 60 words in all. Never say what you didn't find, or that something is missing or absent. " +
                "Plain text, without headings, links or Markdown.",
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

    /**
     * The answer, in [language], to [question] about line [index] of [song], whose words are
     * [lines], after [explanation] of it and the [earlier] questions and answers. Not kept: each
     * question is the user's own. [onText] gets it as it's written.
     */
    suspend fun askAboutLine(
        song: MusicMetadata,
        lines: List<String>,
        index: Int,
        explanation: String?,
        earlier: List<Pair<String, String>>,
        question: String,
        language: String,
        onText: (String) -> Unit
    ): ChatGptReply {
        val line = lines.getOrNull(index) ?: return ChatGptReply.Failed
        // Tried on real questions with GPT-6-Luna: answered in 2 to 4 s, under 60 words, and a
        // question that isn't about the song is turned down.
        return chatGpt.ask(
            "You help someone learn a language through the songs they listen to. The user sends a song's lyrics, numbered, one line of it, what you already said about that line, and their question about it, maybe after earlier ones. Answer the last question in ${nameOf(language)}, about that line in this song, in at most 60 words of plain text, without headings or Markdown. If the question isn't about the song or learning its language, say briefly that you can only help with the song.",
            "Song: ${song.title} by ${song.artist}\n\nLyrics:\n${lines.mapIndexed { number, text -> "${number + 1}. $text" }.joinToString("\n")}" +
                "\n\nLine ${index + 1}: $line\n\n" + explanation?.let { "What you said about it:\n$it\n\n" }.orEmpty() +
                earlier.takeLast(EARLIER_QUESTIONS).joinToString("") { (asked, answered) -> "Question: $asked\nAnswer: $answered\n\n" } + "Question: $question",
            effort = "low",
            onText = onText
        )
    }

    /**
     * [read], the readings the dictionary gave [song]'s Japanese [lines], with those ChatGPT finds
     * wrong in this song put right, e.g. 君 as きみ rather than くん; null when it couldn't check.
     */
    suspend fun checkReadings(song: MusicMetadata, lines: List<String>, read: List<LineReading>): List<LineReading>? {
        val model = chatGpt.model() ?: return null
        // Kept for these words: another copy of the song may have other lines.
        val key = "readings|$model|${song.title}|${song.artist}|${lines.hashCode()}"
        val fixes = cache.get(key) ?: run {
            val asked = JSONArray()
            lines.forEachIndexed { index, line ->
                val readings = JSONArray(read.getOrNull(index)?.parts.orEmpty().filter { it.reading != null }.map { "${it.text}=${it.reading}" })
                asked.put(JSONObject().put("line", index + 1).put("text", line).put("readings", readings))
            }
            // Tried on real songs with GPT-6-Luna: in about 3 s with low thinking it found every
            // reading the dictionary got wrong, 君 as くん, 重ね着's 着 as ちゃく, 身体 as しんたい.
            val reply = chatGpt.ask(
                "You check the readings a Japanese dictionary gave the kanji in a song's lyrics, for someone learning Japanese. The user sends the lyrics as a JSON array of lines, each with its kanji and the reading the dictionary gave, as kanji=reading. Find only the readings that are wrong in this song, as it's sung: names, words read otherwise in context, and readings songs use. Reply with only a JSON array of corrections, each an object with \"line\" (the line's number, from 1), \"kanji\" as given, and \"reading\" in hiragana; an empty array when all are right.",
                asked.toString(),
                effort = "low"
            )
            val array = (reply.array() ?: return null).toString()
            cache.put(key, array)
            cache.save()
            array
        }
        return Readings.corrected(read, JSONArray(fixes).let { list ->
            (0 until list.length()).mapNotNull { list.optJSONObject(it) }.map { ReadingFix(it.optInt("line") - 1, it.optString("kanji"), it.optString("reading")) }
        })
    }

    /** Songs that match [description], what someone remembers about them, most likely first, as title to artist. */
    suspend fun songsFor(description: String): List<Pair<String, String>>? {
        val model = chatGpt.model() ?: return null
        val key = "describe|$model|${description.trim().lowercase()}"
        val kept = cache.get(key) ?: run {
            // Tried on real descriptions with GPT-6-Luna: "plastic algo" found Plastic Love and "una
            // ciudad de la furia" Soda Stereo's, in about 3 s; searching the web found no more. A mood,
            // "rainy sunday city pop", gave 15 for a playlist, in about 12 s, still one for "plastic algo".
            val reply = chatGpt.ask(
                "You find songs from what someone remembers about them. The user sends a description, maybe vague, in any language. " +
                    "Name up to five songs that match it, most likely first, only real songs you're sure exist. " +
                    "When it describes a mood, a genre, a scene or a moment rather than one song, name $PLAYLIST_SONGS different songs that fit it instead, for a playlist. " +
                    "Reply with only a JSON array of objects with \"title\" and \"artist\", as the songs are named on music services.",
                description.trim()
            )
            val array = reply.array() ?: return null
            if (array.length() > 0) cache.put(key, array.toString())
            cache.save()
            array.toString()
        }
        return objects(kept).mapNotNull(::named)
    }

    /**
     * Which of [rows], a music service's search results as "title | artist • album • length", is
     * [song], by its index; null when none is, or it couldn't say. [videos] says the rows are videos
     * as "title | channel • views • length" instead, put up by anyone.
     */
    suspend fun pickSong(song: MusicMetadata, rows: List<String>, videos: Boolean = false): Int? {
        if (rows.isEmpty()) return null
        // Tried on real YouTube Music results with GPT-6-Luna: 7 of 7 right in about 2.5 s, e.g.
        // Stay With Me as 真夜中のドア〜stay with me, and none for Sam Smith's among Miki Matsubara's.
        // Videos are asked apart: among songs, a video's uploader read as its artist and it said none
        // for The Blue Hearts' Owaranai Uta 4 times in 6; asked apart, 15 of 16 picked its upload, and the
        // other a lyrics video of the same recording, with none for a made-up song.
        val reply = if (videos) {
            chatGpt.ask(
                "You match songs to videos. The user sends a song, as title and artist, and a numbered list of YouTube videos, each as title | channel • views • length. Anyone may have put a video up, so its title, not its channel, says whose song it is; it may be written in another script or language. Pick the video that plays this song's original recording by this artist, e.g. with lyrics on screen. Leave out covers, karaoke, instrumentals, live and other versions, unless the song's own title names one. Reply with only the number, or 0 when none is.",
                "Song: ${song.title} by ${song.artist}\n\nVideos:\n${rows.mapIndexed { index, row -> "${index + 1}. $row" }.joinToString("\n")}",
                effort = "low"
            )
        } else {
            chatGpt.ask(
                "You match songs across music services. The user sends a song, as title and artist, and a numbered list of search results from another service, each as title | artist • album • length. Pick the result that is this same song by this same artist: titles may be written in another script or language, or with a subtitle. Prefer the original recording over remixes, covers, live or later versions, unless the song's own title names one. Reply with only the number, or 0 when none is this song.",
                "Song: ${song.title} by ${song.artist}\n\nResults:\n${rows.mapIndexed { index, row -> "${index + 1}. $row" }.joinToString("\n")}",
                effort = "low"
            )
        }
        val answer = reply.written() ?: return null
        val number = answer.trim().takeWhile(Char::isDigit).toIntOrNull() ?: return null
        return (number - 1).takeIf { it in rows.indices }
    }

    /**
     * [song]'s [lines], Japanese written in romaji or Korean in Latin letters, as [script] says,
     * written in it as its lyrics are, line by line; null when it couldn't be done.
     */
    suspend fun fromRomaji(song: MusicMetadata, lines: List<String>, script: Script = Script.JAPANESE): List<String>? {
        val model = chatGpt.model() ?: return null
        val korean = script == Script.KOREAN
        val key = "${if (korean) "hangul" else "romaji"}|$model|${song.title}|${song.artist}|${lines.hashCode()}"
        val kept = cache.get(key) ?: run {
            // Tried on real songs with GPT-6-Luna: Owaranai Uta and Gurenge came back as they're
            // written, 終わらない歌を歌おう, 強くなれる理由を知った, a romaji typo put right and English
            // kept, in about 2 s. ILLIT's You're Under Arrest came back as 새끼손가락 건 약속은 안
            // 했지만, 체포하겠어 crime signs, English kept and gaps left as they were.
            val reply = chatGpt.ask(
                if (korean) {
                    "You restore song lyrics written in Latin letters to Korean. The user sends a song, as title and artist, and its lines as a JSON array of strings, in order. " +
                        "Write each line in Korean as the song's lyrics are officially written, in Hangul; keep words sung in English as they are. " +
                        "Reply with only a JSON array of strings, the same length and in the same order. A line that isn't Korean stays as it is."
                } else {
                    "You restore song lyrics written in romaji to Japanese. The user sends a song, as title and artist, and its lines as a JSON array of strings, in order. " +
                        "Write each line in Japanese as the song's lyrics are officially written, in kanji and kana; keep words sung in English as they are. " +
                        "Reply with only a JSON array of strings, the same length and in the same order. A line that isn't Japanese stays as it is."
                },
                JSONObject().put("song", "${song.title} by ${song.artist}").put("lines", JSONArray(lines)).toString(),
                effort = "low"
            )
            (reply.array() ?: return null).toString()
        }
        val japanese = JSONArray(kept).let { list -> (0 until list.length()).map { list.optString(it) } }
        if (japanese.size != lines.size) return null
        cache.put(key, kept)
        cache.save()
        // A line with no words, like a gap, stays one.
        return japanese.mapIndexed { index, line -> if (lines[index].isBlank()) lines[index] else line.trim() }
    }

    /**
     * The words and phrases of [song], whose words are [lines], most worth learning before listening,
     * in the order they come, with what they mean there in [language]; null when it couldn't say.
     */
    suspend fun wordsToKnow(song: MusicMetadata, lines: List<String>, language: String): List<KeyWord>? {
        val model = chatGpt.model() ?: return null
        val key = "words|$model|$language|${song.title}|${song.artist}"
        val kept = cache.get(key) ?: run {
            // Tried on real songs with GPT-6-Luna: Plastic Love gave ten, 孤独 as soledad, 皮肉なもの as
            // qué ironía, each read in kana, in about 6 s.
            val reply = chatGpt.ask(
                "You help someone learn a language through the songs they listen to. The user sends a song's lyrics. " +
                    "Pick the 6 to 10 words or set phrases from it most worth learning before listening: common, useful, or key to understanding the song, not names. " +
                    "Reply with only a JSON array of objects, in the order they first appear, each with \"word\" exactly as written in the lyrics, " +
                    "\"reading\", how it's read in kana or pinyin when it's in Japanese or Chinese, else an empty string, and \"meaning\", what it means in this song in a few words of ${nameOf(language)}.",
                "Song: ${song.title} by ${song.artist}\n\nLyrics:\n${lines.joinToString("\n")}",
                effort = "low"
            )
            (reply.array() ?: return null).toString()
        }
        val words = objects(kept).mapNotNull { json ->
            val word = json.optString("word").trim()
            val meaning = json.optString("meaning").trim()
            if (word.isEmpty() || meaning.isEmpty()) null else KeyWord(word, json.optString("reading").trim().takeIf { it.isNotEmpty() && it != word }, meaning)
        }.distinctBy { it.word }
        if (words.isEmpty()) return null
        cache.put(key, kept)
        cache.save()
        return words
    }

    /** The song [song] covers, when it's a cover, and the songs it samples, from the web. Null when it couldn't say, or neither. */
    suspend fun songFacts(song: MusicMetadata): SongFacts? {
        val model = chatGpt.model() ?: return null
        val key = "facts2|$model|${song.title}|${song.artist}"
        val kept = cache.get(key) ?: run {
            // Tried on real songs with GPT-6-Luna, searching the web, by name alone: Johnny Cash's Hurt
            // covers Nine Inch Nails', Gold Digger samples I Got a Woman, and Plastic Love and Kaneko
            // Ayano's Romance Sengen are originals; in 5 to 10 s.
            val reply = chatGpt.ask(
                "You find what a song covers and samples. The user sends a song, as title and artist. Search the web for this exact song: whether it's a cover of another song, and what songs it samples. " +
                    "Only name songs a page you found gives for this song by name. " +
                    "Reply with only a JSON object with \"original\", an object with \"title\" and \"artist\" of the song it covers, or null when it's the original, and \"samples\", an array of objects with \"title\" and \"artist\" of the songs it samples, empty when none.",
                "Song: ${song.title} by ${song.artist}",
                webSearch = true,
                effort = "low"
            )
            ((reply as? ChatGptReply.Answer)?.let { jsonObject(it.text) } ?: return null).toString()
        }
        val json = JSONObject(kept)
        val original = json.optJSONObject("original")?.let(::named)
        val samples = json.optJSONArray("samples")?.let { list -> (0 until list.length()).mapNotNull { list.optJSONObject(it)?.let(::named) } }.orEmpty()
        // Nothing to say is kept too, so the web isn't searched again each time.
        cache.put(key, kept)
        cache.save()
        if (original == null && samples.isEmpty()) return null
        return SongFacts(original, samples)
    }

    /**
     * Who [song]'s artist is, from the web, in [language]: facts about them, their style and
     * influence, then their best known work. [onText] gets it as it's written.
     */
    suspend fun aboutArtist(song: MusicMetadata, language: String, onText: (String) -> Unit): ChatGptReply {
        val model = chatGpt.model() ?: return ChatGptReply.Failed
        val key = "artist3|$model|$language|${song.artist}"
        cache.get(key)?.let { return ChatGptReply.Answer(it) }
        // Tried on real artists with GPT-6-Luna, searching the web: Mariya Takeuchi came as a singer
        // from Shimane who debuted in 1978, city pop, and Variety (1984) with Plastic Love, in about 6 s.
        // A duet, Say Say Say, came as sentences about the song till each row was asked to cover each artist:
        // "From: Liverpool, England; Gary, Indiana", 3 of 3 times.
        val reply = chatGpt.ask(
            "You help someone get to know the artists they listen to. First search the web for this artist. Only state a fact a page you found gives for this artist by name. " +
                "Then, in ${nameOf(language)}, write about them in three parts, separated by a line with only $PART_BREAK: " +
                "first two to four lines of the form \"Label: value\", with short labels like where they're from, their years or their genre; " +
                "then, with no label, their style and influence, in one or two short sentences; " +
                "then two to four of their best known albums or songs, each on a line of its own as its name, then \" · \" and the year. " +
                "Each value is a few words, never a sentence. It's about the artists, not this song: when it's by more than one, " +
                "each row covers each of them, as \"From: Liverpool, England; Gary, Indiana\". About 50 words in all. Never say what you didn't find. Plain text, without headings, links or Markdown.",
            "Artist: ${song.artist}\nA song of theirs: ${song.title}",
            webSearch = true,
            effort = "low",
            onText = onText
        )
        return reply.also { keep(key, it) }
    }

    /**
     * Three everyday sentences using [word] the way [line], from [song], does, each with what it
     * means in [language]; null when they can't be had.
     */
    suspend fun examples(word: String, line: String, song: MusicMetadata, language: String): List<Example>? {
        val model = chatGpt.model() ?: return null
        val key = "examples2|$model|$language|${song.title}|${song.artist}|$line|$word"
        val kept = cache.get(key) ?: run {
            // Tried on real words with GPT-6-Luna: 狂わせ in 恋のプログラムを 狂わせないでね gave 予定を狂わせないで,
            // "no dejes que altere mis planes", and two more; "thumb" in "Under my thumb" gave "under his
            // thumb" sentences. About 3 s each.
            val reply = chatGpt.ask(
                "You help someone learn a language through song lyrics. The user sends a word from a song and the line it's in. " +
                    "Write three short, everyday example sentences in the line's language that use the word in the same sense, each with what it means in ${nameOf(language)}. " +
                    "Reply with only a JSON array of objects with \"sentence\" and \"meaning\".",
                JSONObject().put("word", word).put("line", line).put("song", "${song.title} by ${song.artist}").toString(),
                effort = "low"
            )
            (reply.array() ?: return null).toString()
        }
        val examples = objects(kept).mapNotNull { json ->
            val sentence = json.optString("sentence").trim()
            val meaning = json.optString("meaning").trim()
            if (sentence.isEmpty() || meaning.isEmpty()) null else Example(sentence, meaning)
        }
        if (examples.isEmpty()) return null
        cache.put(key, kept)
        cache.save()
        return examples
    }

    /** Songs like [song], close in sound, mood and era, in its language, as title to artist. */
    suspend fun songsLike(song: MusicMetadata): List<Pair<String, String>>? {
        val model = chatGpt.model() ?: return null
        val key = "like|$model|${song.title}|${song.artist}"
        val kept = cache.get(key) ?: run {
            // Tried on real songs with GPT-6-Luna: Plastic Love gave twelve city pop songs, Miki
            // Matsubara's Stay With Me, Anri's Remember Summer Days, Junko Ohashi's Telephone Number,
            // in about 8 s; one named twice in two ways, so no song is asked for twice.
            val reply = chatGpt.ask(
                "You recommend songs. The user sends a song they like, as title and artist. Name 12 other songs like it, by other artists too, in the same language and close in sound, mood and era, " +
                    "mixing well known and lesser known ones, no song twice. Only real songs you're sure exist. " +
                    "Reply with only a JSON array of objects with \"title\" and \"artist\", as the songs are named on music services.",
                "${song.title} by ${song.artist}",
                effort = "low"
            )
            // None found isn't kept, so it's asked again another time.
            val array = reply.array() ?: return null
            if (array.length() > 0) cache.put(key, array.toString())
            cache.save()
            array.toString()
        }
        return objects(kept).mapNotNull(::named)
    }

    /**
     * The answer, in [language], to [question] about [song], whose words are [lines], after
     * [explanation] of it and the [earlier] questions and answers. Not kept: each question is the
     * user's own. [onText] gets it as it's written.
     */
    suspend fun askAboutSong(
        song: MusicMetadata,
        lines: List<String>,
        explanation: String?,
        earlier: List<Pair<String, String>>,
        question: String,
        language: String,
        onText: (String) -> Unit
    ): ChatGptReply = chatGpt.ask(
        "You help someone understand the songs they listen to, often in a language they're learning. The user sends a song's lyrics, what you already said about it, and their question about it, maybe after earlier ones. " +
            "Answer the last question in ${nameOf(language)}, about this song, in at most 80 words of plain text, without headings or Markdown. " +
            "If the question isn't about the song, its artist or learning its language, say briefly that you can only help with the song.",
        "Song: ${song.title} by ${song.artist}\n\nLyrics:\n${lines.joinToString("\n")}\n\n" +
            explanation?.let { "What you said about it:\n$it\n\n" }.orEmpty() +
            earlier.takeLast(EARLIER_QUESTIONS).joinToString("") { (asked, answered) -> "Question: $asked\nAnswer: $answered\n\n" } + "Question: $question",
        effort = "low",
        onText = onText
    )

    /** The objects in the JSON array [text]. */
    private fun objects(text: String): List<JSONObject> = JSONArray(text).let { list -> (0 until list.length()).mapNotNull { list.optJSONObject(it) } }

    /** A song named in [json] by its title and artist, or null when either is missing. */
    private fun named(json: JSONObject): Pair<String, String>? {
        val title = json.optString("title").trim()
        val artist = json.optString("artist").trim()
        return if (title.isEmpty() || artist.isEmpty()) null else title to artist
    }

    private fun songKey(model: String, language: String, song: MusicMetadata) = "song6|$model|$language|${song.title}|${song.artist}"

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
            reply.written() ?: return null
        }
        val json = jsonObject(answer) ?: return null
        val meaning = json.optString("meaning").trim().ifEmpty { return null }
        cache.put(key, answer)
        cache.save()
        return Definition(json.optString("type").trim().ifEmpty { null }, meaning)
    }

    /** What the reply says, when it's an answer. */
    private fun ChatGptReply.written(): String? = (this as? ChatGptReply.Answer)?.text

    private fun ChatGptReply.array(): JSONArray? = (this as? ChatGptReply.Answer)?.let { jsonArray(it.text) }

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

    /** What a line already in [language] is in: any variety of it, e.g. Spanish for "es-AR", but Chinese by how it's written. */
    private fun sameAs(language: String): String = when (language) {
        "zh-CN" -> "Simplified Chinese"
        "zh-TW" -> "Traditional Chinese"
        else -> Locale.forLanguageTag(language).getDisplayLanguage(Locale.ENGLISH)
    }
}

/** A word or phrase worth learning from a song, [word] as it's written there, how it reads, and what it [meaning]s there. */
internal data class KeyWord(val word: String, val reading: String?, val meaning: String)

/** A [sentence] using a line's word or phrase, and what it [meaning]s. */
internal data class Example(val sentence: String, val meaning: String)

/**
 * What's known of a song beyond its words: how hard they are, as a CEFR [level], and [why]; the
 * song it covers, as title to artist, when it's a cover; and those it [samples].
 */
internal data class SongFacts(val original: Pair<String, String>?, val samples: List<Pair<String, String>>)


/** How many songs ChatGPT names for a mood, as a playlist. */
private const val PLAYLIST_SONGS = 15

/** How many earlier questions and answers a new question about a line or a song is asked with. */
private const val EARLIER_QUESTIONS = 6

/** What ChatGPT puts between the parts of an explanation. */
internal const val PART_BREAK = "---"

/** Bumped when ChatGPT is asked to translate another way, so lines translated before are asked again: 2 kept each line's part on it. */
internal const val TRANSLATION_VERSION = 2

/**
 * The parts of an explanation, in order, empty where it left one out; while it's still being
 * written, those so far, without the start of a break that's only half there. Asked to break
 * them with a line of dashes, ChatGPT sometimes leaves a blank line instead, which then does, or
 * a line of em or en dashes, in Spanish.
 */
internal fun explanationParts(text: String): List<String> {
    val parts = plainText(text).split(Regex("""\n[ \t]*(?:-{3,}|[—–―]+)[ \t]*(?:\n|$)""")).map { it.trim().trimEnd('-').trim() }
    if (parts.size > 1) return parts
    return parts.single().split(Regex("""\n[ \t]*\n\s*""")).map { it.trim() }
}

/**
 * What a song's or an artist's explanation says, sorted by what each line is rather than by where
 * the `---` breaks fall, as ChatGPT leaves them out now and then: [facts] as label and value (the
 * label empty when the line has none), the [text] that tells the story or style, the [mood]'s
 * words, the [quote] of a key line with what it [means], and the [known] work as name and year.
 * Whatever fits none of those goes into [text], so nothing said is lost.
 */
internal data class Sheet(
    val facts: List<Pair<String, String>>,
    val text: String,
    val mood: List<String>,
    val quote: String?,
    val means: String?,
    val known: List<Pair<String, String>>
)

private val FACT = Regex("""^([^:.,()·]{1,30}): (.+)$""")
private val KNOWN = Regex("""^(.+) · (\d{4}\S*)$""")
private const val QUOTE_MARKS = "\"“«„「『‘'"

/** [text] sorted into a [Sheet]; while it's still being written, what's come so far. */
internal fun sheet(text: String): Sheet {
    val facts = mutableListOf<Pair<String, String>>()
    val said = mutableListOf<String>()
    val known = mutableListOf<Pair<String, String>>()
    var mood = emptyList<String>()
    var quote: String? = null
    var means: String? = null
    val lines = plainText(text).lines().map { it.trim() }.filter { it.isNotEmpty() && !it.matches(Regex("""-{3,}|[—–―]+""")) }
    var at = 0
    while (at < lines.size) {
        val line = lines[at++]
        val parts = line.split(" · ")
        val label = FACT.matchEntire(line)?.takeIf { it.groupValues[1].split(' ').size <= 4 }
        val work = KNOWN.matchEntire(line)
        when {
            // Work comes before a quote: its names are often quoted too.
            work != null -> known += work.groupValues[1].trim('«', '»', '“', '”', '"', ' ') to work.groupValues[2]
            quote == null && line[0] in QUOTE_MARKS -> {
                quote = line
                // The sentence under it says what it means.
                means = lines.getOrNull(at++)
            }
            mood.isEmpty() && parts.size in 2..4 && parts.all { it.split(' ').size <= 3 && it.none { c -> c.isDigit() || c in ".!?" } } -> mood = parts
            // Facts come first: once the story or the key line has started, a line with a colon is just part of it.
            said.isEmpty() && quote == null && label != null -> facts += label.groupValues[1] to label.groupValues[2]
            said.isEmpty() && quote == null && line.length <= 80 && line.last() !in ".!?。" -> facts += "" to line
            else -> said += line
        }
    }
    return Sheet(facts, said.joinToString(" "), mood, quote, means, known)
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
