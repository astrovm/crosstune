package com.astrovm.crosstune

import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

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
    /** How many days it waits to come back once learned, as in Anki; 0, still being learned. */
    val interval: Int = 0,
    /** How fast that grows, in percent, as in Anki: lower, for a line that's hard. */
    val ease: Int = START_EASE,
    /** 1, on its last learning step, or relearning after a miss; 0, on the first. */
    val step: Int = 0,
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
    val readByHand: Boolean = false,
    /** Its romaji as a keyboard spells it, when that's not as [romanized] says it. */
    val typed: String? = null,
    /** How the other kinds of card it makes, by [CardType.id], are going, each as [interval], [ease], [step] and [due] are for the first kind. */
    val reviews: Map<String, Review> = emptyMap(),
    /** Which kind of card it is now, by [CardType.id]; its own schedule is the one in [interval], [ease], [step] and [due]. Not kept. */
    val cardType: String = READ_TYPE
) {
    /** It as the card of kind [id], with that card's schedule in the place of the first kind's. */
    fun forType(id: String): SavedLine {
        if (id == READ_TYPE) return copy(cardType = id)
        val review = reviews[id] ?: Review()
        return copy(cardType = id, interval = review.interval, ease = review.ease, step = review.step, due = review.due)
    }

    /** Whether it can be played in the music app: it knows where it's sung. */
    val timed get() = startMs != null && endMs != null

    /** It in Latin letters, as typed when [typed] and it's spelled otherwise, or as said; null when it has none. */
    fun latin(typed: Boolean): String? = (if (typed) this.typed ?: romanized else romanized)?.takeIf { it.isNotBlank() }

    /** It as [shown], when it can be: its kana, for Japanese, or its Latin letters; otherwise as written. */
    fun shownAs(shown: WordsAs, typed: Boolean): String = when (shown) {
        WordsAs.KANA -> reading?.takeIf { it.any(Readings::isKana) }
        WordsAs.LATIN -> latin(typed)
        WordsAs.WRITTEN -> null
    } ?: text

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
            return copy(reading = word, romanized = Readings.romaji(word), readByHand = true, typed = Readings.romaji(word, typed = true).takeIf { it != Readings.romaji(word) })
        }
        val shown = readable
        val fixes = shown.indices.mapNotNull { index -> readings.getOrNull(index)?.trim()?.takeIf { it != shown[index].reading }?.let { ReadingFix(0, shown[index].text, it) } }
        val read = Readings.corrected(listOf(LineReading(parts, reading ?: text, romanized.orEmpty(), typed ?: romanized.orEmpty())), fixes).first()
        if (read.parts == parts) return this
        return copy(ruby = read.parts, reading = read.reading, romanized = read.romanized.takeIf { it.isNotBlank() }, readByHand = true, typed = read.typed.takeIf { it.isNotBlank() && it != read.romanized })
    }
}

/** The text layers and sizes picked independently for one side of a practice card. */
internal data class CardSide(
    val words: Boolean = true,
    val wordsAs: WordsAs = WordsAs.WRITTEN,
    val readings: Boolean = false,
    val latin: Boolean = false,
    val translation: Boolean = false,
    val wordsScale: Float = 1f,
    val translationScale: Float = wordsScale,
    val readingScale: Float = 0.55f,
    val romajiTyped: Boolean = false,
    val sound: Boolean = false
) {
    /** Whether no text layer is shown, so the side is the line itself, played, or nothing. */
    val bare: Boolean get() = !words && !readings && !latin && !translation

    fun toJson(): JSONObject = JSONObject().put("words", words).put("wordsAs", wordsAs.name).put("readings", readings).put("latin", latin)
        .put("translation", translation).put("wordsScale", wordsScale.toDouble()).put("translationScale", translationScale.toDouble()).put("readingScale", readingScale.toDouble()).put("romajiTyped", romajiTyped).put("sound", sound)

    companion object {
        fun fromJson(json: JSONObject, defaults: CardSide): CardSide {
            val words = json.optDouble("wordsScale", defaults.wordsScale.toDouble()).toFloat().coerceIn(MIN_WORDS_SCALE, MAX_WORDS_SCALE)
            return CardSide(
                words = json.optBoolean("words", defaults.words),
                wordsAs = WordsAs.entries.firstOrNull { it.name == json.optString("wordsAs") } ?: defaults.wordsAs,
                readings = json.optBoolean("readings", defaults.readings),
                latin = json.optBoolean("latin", defaults.latin),
                translation = json.optBoolean("translation", defaults.translation),
                wordsScale = words,
                // Kept before it had a size of its own, it was as big as the words.
                translationScale = json.optDouble("translationScale", words.toDouble()).toFloat().coerceIn(MIN_WORDS_SCALE, MAX_WORDS_SCALE),
                readingScale = json.optDouble("readingScale", defaults.readingScale.toDouble()).toFloat().coerceIn(MIN_READING_SCALE, MAX_READING_SCALE),
                romajiTyped = json.optBoolean("romajiTyped", defaults.romajiTyped)
            ).let {
                // Kept before it had a layer of its own, a side with no text was the line, played.
                it.copy(sound = if (json.has("sound")) json.optBoolean("sound") else defaults.sound || it.bare)
            }
        }

        /** Read from the keys it was kept under before there were kinds of card. */
        fun load(preferences: SharedPreferences, prefix: String, defaults: CardSide) = CardSide(
            words = preferences.getBoolean("${prefix}_words", defaults.words),
            wordsAs = WordsAs.entries.firstOrNull { it.name == preferences.getString("${prefix}_words_as", defaults.wordsAs.name) } ?: defaults.wordsAs,
            readings = preferences.getBoolean("${prefix}_readings", defaults.readings),
            latin = preferences.getBoolean("${prefix}_latin", defaults.latin),
            translation = preferences.getBoolean("${prefix}_translation", defaults.translation),
            wordsScale = preferences.getFloat("${prefix}_words_scale", defaults.wordsScale).coerceIn(MIN_WORDS_SCALE, MAX_WORDS_SCALE),
            translationScale = preferences.getFloat("${prefix}_words_scale", defaults.wordsScale).coerceIn(MIN_WORDS_SCALE, MAX_WORDS_SCALE),
            readingScale = preferences.getFloat("${prefix}_reading_scale", defaults.readingScale).coerceIn(MIN_READING_SCALE, MAX_READING_SCALE),
            romajiTyped = preferences.getBoolean("${prefix}_romaji_typed", defaults.romajiTyped)
        ).let { it.copy(sound = preferences.getBoolean("${prefix}_sound", defaults.sound || it.bare)) }
    }
}

/** Each side keeps its own text layers, without changing the lyrics display settings. */
internal data class CardOptions(
    val front: CardSide = CardSide(),
    val back: CardSide = CardSide(readings = true, latin = true, translation = true, sound = true)
) {
    companion object {
        /** What was kept before there were kinds of card. */
        fun load(preferences: SharedPreferences): CardOptions {
            val typed = preferences.getBoolean("lyrics_romaji_typed", false)
            val front = CardSide(
                wordsAs = WordsAs.entries.firstOrNull { it.name == preferences.getString("card_front", null) } ?: WordsAs.WRITTEN,
                romajiTyped = typed
            )
            val back = CardSide(
                readings = preferences.getBoolean("card_readings", true),
                latin = preferences.getBoolean("card_latin", true),
                translation = preferences.getBoolean("card_translation", true),
                romajiTyped = typed,
                sound = true
            )
            return CardOptions(CardSide.load(preferences, "card_front", front), CardSide.load(preferences, "card_back", back))
        }
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
                json.interval(), json.optInt("ease", START_EASE), json.optInt("step"), json.optLong("due"), json.time("start"), json.time("end"), json.optLong("lead"), json.text("app"), json.text("artwork"),
                json.text("line"), json.ruby(), json.optBoolean("byHand"), json.text("typed"), json.reviews()
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
                else -> line.copy(text = text, line = sungIn, reading = null, romanized = null, ruby = null, typed = null)
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
        val room = if (saved.size < MAX) saved else saved.minus(saved.asReversed().maxBy { it.interval })
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
            kept = if (kept.size < MAX) listOf(line) + kept else listOf(line) + kept.minus(kept.asReversed().maxBy { it.interval })
        }
        return save(kept)
    }

    /** Puts timing, the app and the cover on [line], kept before without them, or [again] over its timing, for the next time it's practised. */
    fun timed(line: SavedLine, again: Boolean = false): List<SavedLine> = update(line) {
        if (again || !it.timed) it.copy(startMs = line.startMs, endMs = line.endMs, leadMs = line.leadMs, app = line.app ?: it.app, artworkUrl = it.artworkUrl ?: line.artworkUrl) else it
    }

    /** Keeps each line kept as the first of [changes] as the second, practised as far as it had been. */
    fun replaceAll(changes: List<Pair<SavedLine, SavedLine>>): List<SavedLine> =
        save(load().map { kept -> changes.firstOrNull { it.first.isSame(kept) }?.second ?: kept })

    /** Puts how [lines] read, as kept, on the same lines kept before, but for those read by hand. */
    fun reread(lines: List<SavedLine>): List<SavedLine> = save(
        load().map { kept ->
            lines.firstOrNull { it.isSame(kept) }?.takeIf { !kept.readByHand }?.let { kept.copy(reading = it.reading, romanized = it.romanized, ruby = it.ruby, typed = it.typed) } ?: kept
        }
    )

    /** Keeps [line] read as it is now, put right by hand. */
    fun readAs(line: SavedLine): List<SavedLine> = update(line) { it.copy(reading = line.reading, romanized = line.romanized, ruby = line.ruby, readByHand = true, typed = line.typed) }

    /** Keeps [explanation] with the line [text] of [title] by [artist], when that line's kept. */
    fun explain(text: String, title: String, artist: String, explanation: String): List<SavedLine> =
        update(SavedLine(text, title = title, artist = artist)) { it.copy(explanation = explanation) }

    /** [line], as the kind of card it is, practised at [now], as [grade] says, and when it's due next, as in Anki. */
    fun practised(line: SavedLine, grade: Grade, now: Long): List<SavedLine> = update(line) { kept ->
        val card = answered(kept.forType(line.cardType), grade, now)
        if (line.cardType == READ_TYPE) kept.copy(interval = card.interval, ease = card.ease, step = card.step, due = card.due)
        else kept.copy(reviews = kept.reviews + (line.cardType to Review(card.interval, card.ease, card.step, card.due)))
    }

    /** Keeps the line kept as [line] as [change] makes it. */
    private fun update(line: SavedLine, change: (SavedLine) -> SavedLine): List<SavedLine> = save(load().map { if (it.isSame(line)) change(it) else it })

    private fun save(lines: List<SavedLine>): List<SavedLine> {
        val array = JSONArray()
        lines.forEach { line ->
            array.put(
                JSONObject().put("text", line.text).put("reading", line.reading).put("romanized", line.romanized)
                    .put("translation", line.translation).put("title", line.title).put("artist", line.artist)
                    .put("explanation", line.explanation).put("interval", line.interval).put("ease", line.ease).put("step", line.step).put("due", line.due)
                    .put("start", line.startMs).put("end", line.endMs).put("lead", line.leadMs).put("app", line.app)
                    .put("artwork", line.artworkUrl).put("line", line.line).put("typed", line.typed)
                    .put("ruby", line.ruby?.let { parts -> JSONArray(parts.map { JSONArray().put(it.text).put(it.reading ?: JSONObject.NULL) }) })
                    .apply { if (line.readByHand) put("byHand", true) }
                    .apply { if (line.reviews.isNotEmpty()) put("reviews", reviewsJson(line.reviews)) }
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

    /** The days it waits, or, saved before Anki's way, as long as its old box did. */
    private fun JSONObject.interval(): Int = if (has("interval")) optInt("interval") else optInt("box").let { if (it > 0) LEGACY_DAYS[minOf(it, LEGACY_DAYS.size) - 1] else 0 }

    private fun JSONObject.reviews(): Map<String, Review> = optJSONObject("reviews")?.let { json ->
        json.keys().asSequence().mapNotNull { id ->
            json.optJSONObject(id)?.let { id to Review(it.optInt("interval"), it.optInt("ease", START_EASE), it.optInt("step"), it.optLong("due")) }
        }.toMap()
    } ?: emptyMap()

    private fun reviewsJson(reviews: Map<String, Review>) = JSONObject().apply {
        reviews.forEach { (id, review) -> put(id, JSONObject().put("interval", review.interval).put("ease", review.ease).put("step", review.step).put("due", review.due)) }
    }

    private fun JSONObject.time(name: String): Long? = if (has(name)) optLong(name, -1).takeIf { it >= 0 } else null

    companion object {
        private const val KEY = "saved_lines"

        /** How many lines are kept at most. */
        const val MAX = 500
        private const val MINUTE_MS = 60 * 1000L
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private val LEGACY_DAYS = listOf(1, 3, 7, 16, 35, 75, 150)

        /** [line] once answered as [grade] at [now], with when it's next due: Anki's steps of 1 and 10 minutes, then days. */
        fun answered(line: SavedLine, grade: Grade, now: Long): SavedLine {
            val learning = line.interval == 0 || line.step == 1
            if (learning) {
                val first = line.interval == 0 && line.step == 0
                return when (grade) {
                    Grade.AGAIN -> line.copy(step = if (line.interval == 0) 0 else 1, due = now + if (line.interval == 0) MINUTE_MS else 10 * MINUTE_MS)
                    Grade.HARD -> line.copy(due = now + if (first) 11 * MINUTE_MS / 2 else 10 * MINUTE_MS)
                    Grade.GOOD -> if (first) line.copy(step = 1, due = now + 10 * MINUTE_MS) else line.copy(interval = maxOf(line.interval, 1), step = 0, due = now + maxOf(line.interval, 1) * DAY_MS)
                    Grade.EASY -> (if (line.interval == 0) 4 else line.interval + 1).let { line.copy(interval = it, step = 0, due = now + it * DAY_MS) }
                }
            }
            val hard = maxOf(line.interval + 1, (line.interval * 1.2).roundToInt())
            val good = maxOf(hard + 1, (line.interval * line.ease / 100.0).roundToInt())
            val easy = maxOf(good + 1, (line.interval * line.ease * 1.3 / 100.0).roundToInt())
            return when (grade) {
                // A miss starts it over from a day, after ten minutes, and it's harder from then on.
                Grade.AGAIN -> line.copy(interval = 1, ease = maxOf(MIN_EASE, line.ease - 20), step = 1, due = now + 10 * MINUTE_MS)
                Grade.HARD -> line.copy(ease = maxOf(MIN_EASE, line.ease - 15)).returnsIn(hard, now)
                Grade.GOOD -> line.returnsIn(good, now)
                Grade.EASY -> line.copy(ease = line.ease + 15).returnsIn(easy, now)
            }
        }

        private fun SavedLine.returnsIn(days: Int, now: Long) = minOf(days, MAX_DAYS).let { copy(interval = it, due = now + it * DAY_MS) }

        /** How long [line] waits before it's practised again, answered as [grade]. */
        fun waitFor(line: SavedLine, grade: Grade): Long = answered(line, grade, 0).due
    }
}

/**
 * Where a line kept from a song's words, [kept], is in its words now, [lines], once they've changed,
 * e.g. found again on another site, or written in Japanese from romaji: the same words but for their
 * spacing, punctuation, case or kanji; else, when kept sung at [keptAtMs] and the words are timed,
 * [timesMs], the one line sung then; else words much the same, clearly more than any other line's.
 * -1 when none is clearly it.
 */
internal fun lineNow(kept: String, keptAtMs: Long?, lines: List<String>, timesMs: List<Long>?): Int {
    val words = plainWords(kept).ifEmpty { return -1 }
    val plain = lines.map(::plainWords)
    plain.indexOf(words).takeIf { it >= 0 }?.let { return it }
    if (keptAtMs != null && timesMs != null) {
        timesMs.indices.filter { plain[it].isNotEmpty() && abs(timesMs[it] - keptAtMs) <= SAME_MOMENT_MS }.singleOrNull()?.let { return it }
    }
    val likeness = plain.map { likeness(words, it) }
    val best = likeness.indices.maxByOrNull { likeness[it] } ?: return -1
    return best.takeIf { likeness[it] >= MUCH_THE_SAME && likeness.count { like -> like == likeness[it] } == 1 } ?: -1
}

/** A line's words alone, as Japan writes them, in lower case, without spaces or punctuation. */
private fun plainWords(line: String) = Readings.inJapansKanji(line).lowercase().filter(Char::isLetterOrDigit)

/** How alike [one] and [other] are, from 0 to 1, by the pairs of letters they share. */
private fun likeness(one: String, other: String): Double {
    if (one.length < 2 || other.length < 2) return if (one == other) 1.0 else 0.0
    val left = other.windowed(2).groupingBy { it }.eachCount().toMutableMap()
    var shared = 0
    one.windowed(2).forEach { pair ->
        val count = left[pair] ?: 0
        if (count > 0) {
            shared++
            left[pair] = count - 1
        }
    }
    return 2.0 * shared / (one.length - 1 + other.length - 1)
}

/** How close in time a line sung is to the one kept, to be the same line written otherwise. */
private const val SAME_MOMENT_MS = 500L

/** How alike a line's words are to a kept line's to be that line, changed a little. */
private const val MUCH_THE_SAME = 0.7

/** Where a line's ease starts, and the least it falls to, in percent, as in Anki. */
private const val START_EASE = 250
private const val MIN_EASE = 130

/** How a line's card of one kind is going: the days it waits, its ease, its step, and when it's due; as for a new card, by default. */
internal data class Review(val interval: Int = 0, val ease: Int = START_EASE, val step: Int = 0, val due: Long = 0)

/** The longest a line waits, as in Anki: a hundred years. */
private const val MAX_DAYS = 36_500

/** How a card was answered, once turned, as Anki has it: missed, known with trouble, known, or known without a thought. */
internal enum class Grade { AGAIN, HARD, GOOD, EASY }
