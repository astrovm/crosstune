package com.astrovm.crosstune

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The lines due at [now], at most [max], each song's together, in the order they're sung, so the
 * music app goes from one song to the next once, and only forward in each. The song due longest
 * comes first.
 */
internal fun sessionOf(lines: List<SavedLine>, now: Long, max: Int = SESSION_SIZE): List<SavedLine> =
    lines.filter { it.practisable && it.due <= now }
        .sortedBy { it.due }
        .take(max)
        .groupBy { it.title to it.artist }
        .values
        .flatMap { song -> song.sortedWith(compareBy(nullsLast()) { it.startMs }) }

/** How many cards a session has at most: enough to learn something, little enough to finish. */
internal const val SESSION_SIZE = 20

/**
 * The cards left once the first was missed: it comes back after the rest of its song, before the
 * next one, while the music app still has it.
 */
internal fun missedAgain(queue: List<SavedLine>): List<SavedLine> {
    val missed = queue.firstOrNull() ?: return queue
    val rest = queue.drop(1)
    val sameSong = rest.takeWhile { it.title == missed.title && it.artist == missed.artist }
    return sameSong + missed + rest.drop(sameSong.size)
}

/** Whether [one] and [other] are lines of the same song. */
internal fun sameSongOf(one: SavedLine, other: SavedLine?) = other != null && one.title == other.title && one.artist == other.artist

/**
 * Whether [text] is no more than sounds, e.g. "la la la", "Oh, oh" or "ラララ", which aren't worth
 * learning: nothing but letters that sing a sound, or no letters at all.
 */
internal fun isFiller(text: String): Boolean {
    val letters = text.filter { it.isLetter() }
    if (letters.isEmpty()) return true
    if (letters.all { it in FILLER_KANA }) return true
    val words = text.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
    return words.all { word -> FILLER_WORD.matches(word) }
}

private const val FILLER_KANA = "ラらナなアあオおウうーぁッっ"

private val FILLER_WORD = Regex("(la|na|da|oh|ah|uh|eh|ooh|oo|hey|yeah|yeh|ya|woah|whoa|wo|mm+|hm+|ha|doo|ba|sha)+")

/** A line of a song, as it's offered to learn: once, however many times it's sung, from where it's first sung. */
internal data class SongLine(
    val index: Int,
    val text: String,
    /** How many times it's sung. */
    val times: Int,
    /** Only sounds, not worth learning, so not picked to start with. */
    val filler: Boolean,
    /** Has a word worth knowing, as ChatGPT picked them. */
    val newWords: Boolean
)

/** [lines] to pick from to learn, each once, with which are worth it, and which have [words] worth knowing. */
internal fun songLines(lines: List<String>, words: List<String>): List<SongLine> {
    val seen = LinkedHashMap<String, SongLine>()
    lines.forEachIndexed { index, line ->
        val text = line.trim()
        if (text.isEmpty()) return@forEachIndexed
        val key = text.lowercase()
        val known = seen[key]
        seen[key] = known?.copy(times = known.times + 1) ?: SongLine(index, text, 1, isFiller(text), words.any { it.isNotBlank() && text.contains(it, ignoreCase = true) })
    }
    return seen.values.toList()
}

/**
 * Kept lines practised as cards, on [screen]: each card's line ready in the music app, and played
 * once it's turned, then said how well it was known.
 */
internal class PracticeCards(
    screen: MutableState<UiState>,
    private val scope: CoroutineScope,
    private val playback: PlaybackSource?,
    private val matcher: ExactMatcher,
    private val store: SavedLinesStore,
    private val wallClock: () -> Long,
    private val now: () -> Long
) {
    private var uiState by screen
    private var soundJob: Job? = null

    /**
     * Practises the lines due, each song's together; or only [song]'s, all of them when none is due,
     * to learn it. The music app goes back to [before] once done.
     */
    fun start(song: MusicMetadata?, before: Before?) {
        val queue = if (song == null) sessionOf(uiState.savedLines, wallClock()) else {
            val its = uiState.savedLines.filter { it.isOf(song) }
            sessionOf(its, wallClock()).ifEmpty { sessionOf(its, Long.MAX_VALUE) }
        }
        uiState = uiState.copy(practice = Practice(queue, total = queue.size, before = before))
        showCard()
    }

    /**
     * Shows the next card, with its line ready in the music app, not played: heard, it would say how
     * it reads, which is what's asked. It plays once the card's turned.
     */
    private fun showCard() {
        soundJob?.cancel()
        val practice = uiState.practice ?: return
        val line = practice.line ?: return
        val playable = line.timed && playback?.hasAccess() == true
        uiState = uiState.copy(practice = practice.copy(turned = false, lastSong = line, sound = if (playable) LineSound.LOADING else LineSound.NONE))
        if (playable) sound(line, play = false)
    }

    /**
     * Has the music app play [line], from where it's sung to the next line, then pause; or, when not
     * [play] and the card isn't turned yet, only get there. A song it isn't playing it's asked to find first.
     */
    private fun sound(line: SavedLine, play: Boolean = true) {
        val source = playback ?: return
        soundJob?.cancel()
        soundJob = scope.launch {
            val song = MusicMetadata(line.title, line.artist)
            val reached = inApp(source, song, line.app) { app ->
                val lead = if (app()?.video == true) line.leadMs else 0L
                val start = (line.startMs ?: 0L) + lead
                // A moment past where the words say it ends, so its last word isn't cut short.
                val end = (line.endMs ?: 0L) + lead + MainViewModel.LINE_TAIL_MS
                source.seekTo(song, start)
                // Turned while the app got there, it plays now.
                val now = play || uiState.practice?.turned == true
                source.playPause(song, now)
                if (!now) return@inApp setSound(line, LineSound.PLAYED)
                setSound(line, LineSound.PLAYING)
                // Once the app says it's there, it plays to the end of the line.
                withTimeoutOrNull(SEEK_WAIT_MS) { while (app()?.clock?.positionAt(now())?.let { it >= start - SEEK_SLACK_MS && it < end } != true) delay(SOUND_CHECK_MS) }
                withTimeoutOrNull(end - start + SEEK_WAIT_MS) { while ((app()?.clock?.positionAt(now()) ?: end) < end) delay(SOUND_CHECK_MS) }
                source.playPause(song, false)
                setSound(line, LineSound.PLAYED)
            }
            if (reached == null) setSound(line, LineSound.NONE)
        }
    }

    /**
     * Follows [song] in the music app, asking [app] for it by name when no app has it, then does
     * [block] once it can be moved around in, with where it is as that changes; null when it never can.
     */
    private suspend fun <T> inApp(source: PlaybackSource, song: MusicMetadata, app: String?, block: suspend (() -> Following?) -> T): T? = coroutineScope {
        var latest: Following? = null
        val following = launch { source.follow(song).collect { latest = it } }
        try {
            val ready: suspend () -> Unit = { while (latest?.canSeek != true) delay(SOUND_CHECK_MS) }
            // Not already there, it's asked for by name, which takes the app a moment. There, but where
            // it can't be moved, e.g. during an ad, asking again would only start it over.
            val found = withTimeoutOrNull(FIND_SONG_MS) { while (latest == null) delay(SOUND_CHECK_MS) } != null
            var opened = false
            val there = if (found) withTimeoutOrNull(FIND_SONG_MS) { ready() } != null else {
                val link = youTubeMusicLink(song, app)
                // Kept from no app in particular, a song YouTube Music has is asked of YouTube Music,
                // not of whatever played last, e.g. a video, which wouldn't play it.
                val asking = app ?: MusicService.YOUTUBE_MUSIC.packageName.takeIf { link != null }
                // YouTube Music not running has nothing to ask in the background, so it's opened on
                // the song, as it is with no app to ask at all, and left again once it has it.
                val asked = (link == null || asking == null || source.runs(asking)) && source.playSong(song, asking, link)
                opened = !asked && link != null && source.openSong(MusicService.YOUTUBE_MUSIC.packageName, link)
                (asked || opened) && withTimeoutOrNull(MainViewModel.LOAD_SONG_MS) { ready() } != null
            }
            if (opened) source.comesBack()
            if (there) block { latest } else null
        } finally {
            following.cancel()
        }
    }

    /** [song]'s link on YouTube Music when that's the app to ask, or may be; null when it isn't, or isn't there. */
    private suspend fun youTubeMusicLink(song: MusicMetadata, app: String?): String? =
        if (app == null || app == MusicService.YOUTUBE_MUSIC.packageName) matcher.find(MusicService.YOUTUBE_MUSIC, song) else null

    /** How [line] sounds, unless another card's shown by now. */
    private fun setSound(line: SavedLine, sound: LineSound) {
        val practice = uiState.practice?.takeIf { it.line?.isSame(line) == true } ?: return
        uiState = uiState.copy(practice = practice.copy(sound = sound))
    }

    /** Plays the card's line again. */
    fun replay() {
        val practice = uiState.practice ?: return
        val line = practice.line?.takeIf { practice.sound != LineSound.NONE } ?: return
        sound(line)
    }

    /** Turns the card, to say whether it was known, and plays its line, now that it's the answer. */
    fun turn() {
        val practice = uiState.practice?.takeIf { !it.turned } ?: return
        uiState = uiState.copy(practice = practice.copy(turned = true))
        if (practice.sound == LineSound.PLAYED) replay()
    }

    /** The card's line read as [readings] say, one for each of its kanji, put right by hand. */
    fun readAs(readings: List<String>) {
        val practice = uiState.practice ?: return
        val line = practice.line ?: return
        val read = line.readAs(readings).takeIf { it != line } ?: return
        uiState = uiState.copy(
            savedLines = store.readAs(read),
            practice = practice.copy(queue = practice.queue.map { if (it.isSame(read)) it.copy(reading = read.reading, romanized = read.romanized, ruby = read.ruby, readByHand = true) else it })
        )
    }

    /** Says how well the card turned was known, and goes on to the next; one missed comes back after its song's others. */
    fun grade(grade: Grade) {
        val practice = uiState.practice?.takeIf { it.turned } ?: return
        val line = practice.line ?: return
        uiState = uiState.copy(savedLines = store.practised(line, grade, wallClock()))
        uiState = uiState.copy(
            practice = if (grade == Grade.AGAIN) practice.copy(queue = missedAgain(practice.queue), missed = practice.missed + 1)
            else practice.copy(queue = practice.queue.drop(1), done = practice.done + 1)
        )
        showCard()
    }

    /** Stops practising, the line playing, or the song on its way, paused. */
    fun stop() {
        val playing = uiState.practice?.takeIf { it.sound == LineSound.PLAYING || it.sound == LineSound.LOADING }?.line
        soundJob?.cancel()
        soundJob = null
        playing?.let { playback?.playPause(MusicMetadata(it.title, it.artist), false) }
        uiState = uiState.copy(practice = null)
    }

    /** Done, the music app goes back to what it played before, where it was, and practice closes. */
    fun backToBefore() {
        val before = uiState.practice?.before ?: return
        val source = playback ?: return
        stop()
        soundJob = scope.launch {
            inApp(source, before.song, before.app) {
                source.seekTo(before.song, before.positionMs)
                source.playPause(before.song, true)
            }
        }
    }

    private companion object {
        /** How long a song the music app has already takes to be found. */
        const val FIND_SONG_MS = 400L
        /** How long the music app has to say it went where it was sent. */
        const val SEEK_WAIT_MS = 3_000L
        /** How far before a line the app may say it is, having gone there, as apps round where they are. */
        const val SEEK_SLACK_MS = 1_500L
        const val SOUND_CHECK_MS = 50L
    }
}
