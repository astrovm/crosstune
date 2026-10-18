package com.astrovm.crosstune

import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/** A line kept to study later, with what helped read it then, and the song it's from. */
internal data class SavedLine(
    val text: String,
    val reading: String? = null,
    val romanized: String? = null,
    val translation: String? = null,
    val title: String,
    val artist: String,
    /** What ChatGPT said the line means, when asked. */
    val explanation: String? = null,
    /** How well it's known, from 0, after each time it's practised; odd, its meaning shows first. */
    val box: Int = 0,
    /** When it's next practised, in milliseconds since 1970; 0, right away. */
    val due: Long = 0,
    /** Where it's sung in the song, from its start to the next line's, to play it again; null when not timed. */
    val startMs: Long? = null,
    val endMs: Long? = null,
    /** How far a video the app played had the song in, so it's played from the same place. */
    val leadMs: Long = 0,
    /** The app it played in, by package, which plays it again. */
    val app: String? = null,
    /** The song's cover, when it had one. */
    val artworkUrl: String? = null,
    /** For a word, the line it's sung in. */
    val line: String? = null,
    /** The line in pieces, each with how it reads over it when that isn't plain from it, e.g. kanji with their kana. */
    val ruby: List<Ruby>? = null,
    /** Its reading put right by hand, which stays when the song's are read again. */
    val readByHand: Boolean = false
) {
    /** Whether it can be played in the music app: it knows where it's sung. */
    val timed get() = startMs != null && endMs != null

    /** Whether it can be practised: it has a reading or a meaning to recall, or a sound to recall it by. */
    val practisable get() = translation != null || reading != null || timed

    fun isSame(other: SavedLine) = text == other.text && title == other.title && artist == other.artist

    /** Whether it's from [song]. */
    fun isOf(song: MusicMetadata) = title == song.title && artist == song.artist

    /** What of it can be read otherwise: its kanji, each with its reading, or a word as a whole. */
    val readable: List<Ruby> get() = ruby?.filter { it.reading != null } ?: listOfNotNull(reading?.takeIf { line != null }?.let { Ruby(text, it) })

    /**
     * Read as [readings] say, one for each of [readable], in kana, put right by hand: on its
     * words, in its kana and in its romaji. Kana written after a kanji, れる of 流れる, may be left
     * in or out; a reading that isn't kana is left out.
     */
    fun readAs(readings: List<String>): SavedLine {
        val parts = ruby
        if (parts == null) {
            val word = readings.firstOrNull()?.trim()?.let(Readings::hiragana)?.takeIf { it.isNotEmpty() && it.all(Readings::isKana) && line != null } ?: return this
            return copy(reading = word, romanized = Readings.romaji(word), readByHand = true)
        }
        val shown = readable
        val fixes = shown.indices.mapNotNull { index -> readings.getOrNull(index)?.trim()?.takeIf { it != shown[index].reading }?.let { ReadingFix(0, shown[index].text, it) } }
        val read = Readings.corrected(listOf(LineReading(parts, reading ?: text, romanized.orEmpty())), fixes).first()
        if (read.parts == parts) return this
        return copy(ruby = read.parts, reading = read.reading, romanized = read.romanized.takeIf { it.isNotBlank() }, readByHand = true)
    }
}

/** The lines kept, newest first, on the phone only. */
internal class SavedLinesStore(private val preferences: SharedPreferences) {

    fun load(): List<SavedLine> {
        val array = preferences.getString(KEY, null)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
        return asJapanWrites((0 until array.length()).mapNotNull { index ->
            val json = array.optJSONObject(index) ?: return@mapNotNull null
            val text = json.optString("text").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            SavedLine(
                text, json.text("reading"), json.text("romanized"), json.text("translation"), json.optString("title"), json.optString("artist"), json.text("explanation"),
                json.optInt("box"), json.optLong("due"), json.time("start"), json.time("end"), json.optLong("lead"), json.text("app"), json.text("artwork"),
                json.text("line"), json.ruby(), json.optBoolean("byHand")
            )
        })
    }

    /**
     * Lines of a Japanese song kept as a Chinese site wrote them, as Japan writes them, as the
     * song's words show now: see [Readings.asJapanWrites]. Their readings, worked out from China's
     * kanji, are left to be worked out again, but for those put right by hand.
     */
    private fun asJapanWrites(lines: List<SavedLine>): List<SavedLine> {
        val japanese = lines.filter { line -> line.text.any(Readings::isKana) }.map { it.title to it.artist }.toSet()
        return lines.map { line ->
            val text = Readings.asJapanWrites(line.text)
            val sungIn = line.line?.let(Readings::asJapanWrites)
            when {
                line.title to line.artist !in japanese || (text == line.text && sungIn == line.line) -> line
                line.readByHand -> line.copy(text = text, line = sungIn, ruby = line.ruby?.let { parts ->
                    parts.mapIndexed { index, part -> part.copy(text = if (index == parts.lastIndex) Readings.asJapanWrites(part.text) else Readings.inJapansKanji(part.text)) }
                })
                else -> line.copy(text = text, line = sungIn, reading = null, romanized = null, ruby = null)
            }
        }
    }

    /**
     * Keeps [line], or lets it go when it's kept already. With [MAX] kept, the one learned best makes
     * room, the oldest of those, rather than one still being learned.
     */
    fun toggle(line: SavedLine): List<SavedLine> {
        val saved = load()
        if (saved.any { it.isSame(line) }) return save(saved.filterNot { it.isSame(line) })
        val room = if (saved.size < MAX) saved else saved.minus(saved.asReversed().maxBy { it.box })
        return save(listOf(line) + room.take(MAX - 1))
    }

    fun remove(line: SavedLine): List<SavedLine> = removeAll(listOf(line))

    fun removeAll(lines: List<SavedLine>): List<SavedLine> = save(load().filterNot { kept -> lines.any(kept::isSame) })

    /** Keeps each of [lines] not kept yet, in their order, before the rest, as far as there's room. */
    fun addAll(lines: List<SavedLine>): List<SavedLine> {
        val saved = load()
        val new = lines.filter { line -> saved.none { it.isSame(line) } }.distinctBy { Triple(it.text, it.title, it.artist) }
        var kept = saved
        new.asReversed().forEach { line ->
            kept = if (kept.size < MAX) listOf(line) + kept else listOf(line) + kept.minus(kept.asReversed().maxBy { it.box })
        }
        return save(kept)
    }

    /** Puts timing, the app and the cover on [line], kept before without them, or [again] over its timing, for the next time it's practised. */
    fun timed(line: SavedLine, again: Boolean = false): List<SavedLine> = save(load().map { if (it.isSame(line) && (again || !it.timed)) it.copy(startMs = line.startMs, endMs = line.endMs, leadMs = line.leadMs, app = line.app ?: it.app, artworkUrl = it.artworkUrl ?: line.artworkUrl) else it })

    /** Puts how [lines] read, as kept, on the same lines kept before, but for those read by hand. */
    fun reread(lines: List<SavedLine>): List<SavedLine> = save(
        load().map { kept ->
            lines.firstOrNull { it.isSame(kept) }?.takeIf { !kept.readByHand }?.let { kept.copy(reading = it.reading, romanized = it.romanized, ruby = it.ruby) } ?: kept
        }
    )

    /** Keeps [line] read as it is now, put right by hand. */
    fun readAs(line: SavedLine): List<SavedLine> =
        save(load().map { if (it.isSame(line)) it.copy(reading = line.reading, romanized = line.romanized, ruby = line.ruby, readByHand = true) else it })

    /** Keeps [explanation] with the line [text] of [title] by [artist], when that line's kept. */
    fun explain(text: String, title: String, artist: String, explanation: String): List<SavedLine> {
        val key = SavedLine(text, title = title, artist = artist)
        return save(load().map { if (it.isSame(key)) it.copy(explanation = explanation) else it })
    }

    /**
     * [line] practised at [now], as [grade] says: known, it comes back after longer each time, 1, 3,
     * 7, 16, 35, 75 then 150 days, and known easily, a step further; missed, in ten minutes, from the
     * start again.
     */
    fun practised(line: SavedLine, grade: Grade, now: Long): List<SavedLine> = save(
        load().map {
            if (!it.isSame(line)) it
            else when (grade) {
                Grade.AGAIN -> it.copy(box = 0, due = now + waitFor(it, grade))
                Grade.GOOD -> it.copy(box = minOf(it.box + 1, PRACTICE_DAYS.size), due = now + waitFor(it, grade))
                Grade.EASY -> it.copy(box = minOf(it.box + 2, PRACTICE_DAYS.size), due = now + waitFor(it, grade))
            }
        }
    )

    private fun save(lines: List<SavedLine>): List<SavedLine> {
        val array = JSONArray()
        lines.forEach { line ->
            array.put(
                JSONObject().put("text", line.text).put("reading", line.reading).put("romanized", line.romanized)
                    .put("translation", line.translation).put("title", line.title).put("artist", line.artist)
                    .put("explanation", line.explanation).put("box", line.box).put("due", line.due)
                    .put("start", line.startMs).put("end", line.endMs).put("lead", line.leadMs).put("app", line.app)
                    .put("artwork", line.artworkUrl).put("line", line.line)
                    .put("ruby", line.ruby?.let { parts -> JSONArray(parts.map { JSONArray().put(it.text).put(it.reading ?: JSONObject.NULL) }) })
                    .apply { if (line.readByHand) put("byHand", true) }
            )
        }
        preferences.edit { putString(KEY, array.toString()) }
        return lines
    }

    private fun JSONObject.text(name: String): String? = optString(name).takeIf { has(name) && it.isNotEmpty() }

    /** The pieces of a line with their readings, as [text, reading or null] each. */
    private fun JSONObject.ruby(): List<Ruby>? = optJSONArray("ruby")?.let { array ->
        (0 until array.length()).mapNotNull { index -> array.optJSONArray(index)?.let { Ruby(it.optString(0), it.optString(1).takeIf { _ -> !it.isNull(1) }) } }
    }?.takeIf { it.isNotEmpty() }

    private fun JSONObject.time(name: String): Long? = if (has(name)) optLong(name, -1).takeIf { it >= 0 } else null

    companion object {
        private const val KEY = "saved_lines"

        /** How many lines are kept at most. */
        const val MAX = 500
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private const val AGAIN_MS = 10 * 60 * 1000L

        /** How long [line] waits before it's practised again, answered as [grade]. */
        fun waitFor(line: SavedLine, grade: Grade): Long = when (grade) {
            Grade.AGAIN -> AGAIN_MS
            Grade.GOOD -> PRACTICE_DAYS[minOf(line.box, PRACTICE_DAYS.lastIndex)] * DAY_MS
            Grade.EASY -> PRACTICE_DAYS[minOf(line.box + 1, PRACTICE_DAYS.lastIndex)] * DAY_MS
        }
    }
}

/** How many days a line known waits before it's practised again, the more times it's known in a row. */
private val PRACTICE_DAYS = listOf(1L, 3L, 7L, 16L, 35L, 75L, 150L)

/** How a card was answered, once turned: missed, known, or known without a thought. */
internal enum class Grade { AGAIN, GOOD, EASY }
