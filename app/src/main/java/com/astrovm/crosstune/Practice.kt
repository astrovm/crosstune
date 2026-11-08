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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The cards due at [now], at most [max], each song's together, in the order they're sung, so the
 * music app goes from one song to the next once, and only forward in each. The song due longest
 * comes first. A line has a card in the session for each of the [types] that's due. Cards practised
 * before come ahead of new ones, so a kind just turned on doesn't push back the ones that are due.
 */
internal fun sessionOf(lines: List<SavedLine>, now: Long, max: Int = SESSION_SIZE, types: List<CardType> = DEFAULT_CARD_TYPES): List<SavedLine> =
    lines.flatMap { it.cardsDue(now, types) }
        .sortedWith(compareBy({ it.due == 0L }, { it.due }))
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
    private val now: () -> Long,
    private val reminders: PracticeReminders? = null,
    private val songLoadMs: Long = MainViewModel.LOAD_SONG_MS,
    /** Makes the small YouTube player a line plays in when its music app can't move to it, once it's wanted; nullable so tests need none. */
    private val newPlayer: (() -> LinePlayer)? = null
) {
    private var uiState by screen
    private var soundJob: Job? = null

    /** The small player, once made: a WebView is heavy, and most people never need it. */
    private var made: LinePlayer? = null

    private fun player(): LinePlayer? = made ?: newPlayer?.invoke()?.also { made = it }

    /** The small player's picture for the card to show, which is made when asked for. */
    fun playerView(): android.view.View? = player()?.view()

    /** The music apps seen playing a song and never offering to move in it, as Spotify's free plan, by package, so they're not asked again. */
    private val cantMove = mutableSetOf<String>()

    /** How long each song is, by title and artist, as the music app said when it played it. */
    private val lengths = mutableMapOf<Pair<String, String>, Long>()

    /**
     * Practises the lines due, each song's together; or only [song]'s, all of them when none is due,
     * to learn it. The music app goes back to [before] once done.
     */
    fun start(song: MusicMetadata?, before: Before?) {
        val queue = if (song == null) sessionOf(uiState.savedLines, wallClock(), types = uiState.cardTypes) else {
            val its = uiState.savedLines.filter { it.isOf(song) }
            sessionOf(its, wallClock(), types = uiState.cardTypes).ifEmpty { sessionOf(its, Long.MAX_VALUE, types = uiState.cardTypes) }
        }
        uiState = uiState.copy(practice = Practice(queue, total = queue.size, before = before))
        showCard()
    }

    /**
     * Shows the next card, with its line ready in the music app, not played: heard, it would say how
     * it reads, which is what's asked. It plays once the card's turned, or at once when hearing it is the question.
     */
    private fun showCard() {
        soundJob?.cancel()
        made?.pause()
        val practice = uiState.practice ?: return
        val line = practice.line ?: return
        val playable = line.timed && playback?.hasAccess() == true
        uiState = uiState.copy(practice = practice.copy(turned = false, lastSong = line, sound = if (playable) LineSound.LOADING else LineSound.NONE, video = null))
        if (playable) sound(line, play = typeOf(line).hears)
    }

    /**
     * Has the music app play [line], from where it's sung to the next line, then pause; or, when not
     * [play] and the card isn't turned yet, only get there. A song it isn't playing it's asked to find first.
     * When the app can't move to the line, as Spotify's free plan, the small YouTube player plays it.
     */
    private fun sound(line: SavedLine, play: Boolean = true) {
        val source = playback ?: return
        soundJob?.cancel()
        soundJob = scope.launch {
            val song = MusicMetadata(line.title, line.artist)
            if (line.app in cantMove || !inApp(source, song, line.app, onStuck = { following -> handOver(source, song, line, following) }) { app ->
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
            }) {
                inYouTube(line, play)
            }
        }
    }

    /** The music app [following] [song] won't move to the line, so it's paused, and not asked again, for the player to play it. */
    private fun handOver(source: PlaybackSource, song: MusicMetadata, line: SavedLine, following: Following?) {
        (following?.appPackage ?: line.app)?.let(cantMove::add)
        if (following?.clock?.playing == true) source.playPause(song, false)
    }

    /**
     * Has the small YouTube player play [line], or only get ready when not [play] and the card isn't turned. With no
     * video that's the song's, there's no sound.
     */
    private suspend fun inYouTube(line: SavedLine, play: Boolean) {
        val player = player()
        val clip = if (player == null) null else videoOf(line)
        if (player == null || clip == null) return setSound(line, LineSound.NONE)
        val (video, expectedMs) = clip
        val start = line.startMs ?: 0L
        val end = (line.endMs ?: start) + MainViewModel.LINE_TAIL_MS
        val now = play || uiState.practice?.turned == true
        val practice = uiState.practice?.takeIf { it.line?.isSame(line) == true } ?: return
        uiState = uiState.copy(practice = practice.copy(video = video, sound = if (now) LineSound.PLAYING else LineSound.PLAYED))
        player.show(video, start, end, expectedMs, now) { ended ->
            if (ended == LineEnd.ENDED) setSound(line, LineSound.PLAYED) else noVideo(line)
        }
    }

    /** The video that plays [line]'s song, with how long the song is when known, or null when there's none that's the song. */
    private suspend fun videoOf(line: SavedLine): Pair<String, Long?>? {
        val song = MusicMetadata(line.title, line.artist)
        val expectedMs = lengths[line.title to line.artist]
        val music = matcher.find(MusicService.YOUTUBE_MUSIC, song)
        // Any other video is taken only when it's as long as the song, which has to be known to tell.
        val link = music ?: expectedMs?.let { matcher.find(MusicService.YOUTUBE, song) }
        val id = link?.toHttpUrlOrNull()?.queryParameter("v") ?: return null
        return id to expectedMs
    }

    /** [line]'s video can't be played: it has no sound, and the player's gone from its card. */
    private fun noVideo(line: SavedLine) {
        val practice = uiState.practice?.takeIf { it.line?.isSame(line) == true } ?: return
        uiState = uiState.copy(practice = practice.copy(video = null, sound = LineSound.NONE))
    }

    /**
     * Follows [song] in the music app, asking [app] for it by name when no app has it, then does
     * [block] once it can be moved around in, with where it is as that changes. True when it could.
     * When it's there and won't move, as Spotify's free plan, [onStuck] hears what it was doing.
     */
    private suspend fun inApp(
        source: PlaybackSource, song: MusicMetadata, app: String?,
        onStuck: (Following?) -> Unit = {}, block: suspend (() -> Following?) -> Unit
    ): Boolean = coroutineScope {
        var latest: Following? = null
        val following = launch {
            source.follow(song).collect { latest = it; it?.durationMs?.let { length -> lengths[song.title to song.artist] = length } }
        }
        try {
            val ready: suspend () -> Unit = { while (latest?.canSeek != true) delay(SOUND_CHECK_MS) }
            // Not already there, it's asked for by name, which takes the app a moment.
            val found = withTimeoutOrNull(FIND_SONG_MS) { while (latest == null) delay(SOUND_CHECK_MS) } != null
            val there = if (found) withTimeoutOrNull(FIND_SONG_MS) { ready() } != null else openUntilItCanMove(source, song, app, { latest }, onStuck)
            if (there) block { latest } else if (found) onStuck(latest)
            there
        } finally {
            following.cancel()
        }
    }

    /**
     * Opens [song] on its own link, or asks for it, then waits until it can be moved. Spotify plays
     * nothing asked of it by name; its own link starts the song. On a free plan it never offers a
     * seek, and sending one errors the player, so once that song plays and still can't be moved, [onStuck] hears of it.
     */
    private suspend fun openUntilItCanMove(source: PlaybackSource, song: MusicMetadata, app: String?, latest: () -> Following?, onStuck: (Following?) -> Unit): Boolean {
        var opened = openedOnOwnLink(source, song, app)
        val asked = !opened && asked(source, song, app) { link -> source.openSong(MusicService.YOUTUBE_MUSIC.packageName, link).also { opened = it } }
        var playingSince: Long? = null
        var cameBack = false
        val canSeek = (asked || opened) && withTimeoutOrNull(songLoadMs) {
            while (latest()?.canSeek != true) {
                // Playing for a moment and still not offering a seek, it never will: that's the free plan.
                playingSince = if (opened && latest()?.clock?.playing == true) playingSince ?: now() else null
                if (playingSince != null && !cameBack) {
                    // It's playing, so there's nothing more to see there: back to the card, without waiting to be sure.
                    source.comesBack()
                    cameBack = true
                }
                if (playingSince?.let { now() - it >= STUCK_AFTER_MS } == true) return@withTimeoutOrNull false
                delay(SOUND_CHECK_MS)
            }
            true
        } == true
        if (opened && !cameBack) source.comesBack()
        if (!canSeek && playingSince != null) onStuck(latest())
        return canSeek
    }

    /** Whether [app], one that plays nothing asked of it but each song's own link, was opened on [song]'s. */
    private suspend fun openedOnOwnLink(source: PlaybackSource, song: MusicMetadata, app: String?): Boolean {
        val service = app?.let(MusicService::ofPackage)?.takeIf { !it.playsAskedSongs && it.playsSongBySong } ?: return false
        return matcher.find(service, song)?.let { source.openSong(app, it) } == true
    }

    /**
     * Whether [song] was asked for and is starting. Kept from no app in particular, or one that plays nothing
     * asked of it, a song YouTube Music has is asked of YouTube Music, not of whatever played last, e.g. a video,
     * which wouldn't play it. An app that can't be woken in the background is left to [open] on the song, as it
     * is with no app to ask at all, and left again once it has it, unless it only plays in front.
     */
    private suspend fun asked(source: PlaybackSource, song: MusicMetadata, app: String?, open: (String) -> Boolean): Boolean {
        val from = app?.takeIf { MusicService.ofPackage(it)?.playsAskedSongs != false }
        val link = youTubeMusicLink(song, from)
        val asking = from ?: MusicService.YOUTUBE_MUSIC.packageName.takeIf { link != null }
        return source.playSong(song, asking, link, mustStart = true).also { if (!it && link != null) open(link) }
    }

    /** [song]'s link on YouTube Music when that's the app to ask, or may be; null when it isn't, or isn't there. */
    private suspend fun youTubeMusicLink(song: MusicMetadata, app: String?): String? =
        if (app == null || MusicService.ofPackage(app) == MusicService.YOUTUBE_MUSIC) matcher.find(MusicService.YOUTUBE_MUSIC, song) else null

    /** How [line] sounds, unless another card's shown by now. */
    private fun setSound(line: SavedLine, sound: LineSound) {
        val practice = uiState.practice?.takeIf { it.line?.isSame(line) == true } ?: return
        uiState = uiState.copy(practice = practice.copy(sound = sound))
    }

    /** The kind of card [line]'s is. */
    private fun typeOf(line: SavedLine) = uiState.cardTypes.firstOrNull { it.id == line.cardType } ?: READ_IT

    /** Plays the card's line again, or, while it plays, stops it there, paused, to be played again. */
    fun replay() {
        val practice = uiState.practice ?: return
        val line = practice.line?.takeIf { practice.sound != LineSound.NONE } ?: return
        soundJob?.cancel()
        if (practice.sound != LineSound.PLAYING) {
            if (practice.video == null) return sound(line)
            soundJob = scope.launch { inYouTube(line, play = true) }
            return
        }
        if (practice.video != null) made?.pause() else playback?.playPause(MusicMetadata(line.title, line.artist), false)
        setSound(line, LineSound.PLAYED)
    }

    /** Turns the card, to say whether it was known, and plays its line if the back has its sound. */
    fun turn() {
        val practice = uiState.practice?.takeIf { !it.turned } ?: return
        uiState = uiState.copy(practice = practice.copy(turned = true))
        if (practice.sound == LineSound.PLAYED && practice.line?.let { typeOf(it).options.back.sound } == true) replay()
    }

    /** The card's line read as [readings] say, one for each of its kanji, put right by hand. */
    fun readAs(readings: List<String>) {
        val practice = uiState.practice ?: return
        val line = practice.line ?: return
        val read = line.readAs(readings).takeIf { it != line } ?: return
        uiState = uiState.copy(
            savedLines = store.readAs(read),
            practice = practice.copy(queue = practice.queue.map { if (it.isSame(read)) it.copy(reading = read.reading, romanized = read.romanized, ruby = read.ruby, readByHand = true, typed = read.typed) else it })
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
        reminders?.let { if (uiState.practice?.line == null) it.sessionDone() else it.practised() }
        uiState = uiState.copy(reminder = reminders?.state ?: uiState.reminder)
        showCard()
    }

    /** Stops practising, the line playing, or the song on its way, paused. */
    fun stop() {
        val playing = uiState.practice?.takeIf { it.sound == LineSound.PLAYING || it.sound == LineSound.LOADING }
        soundJob?.cancel()
        soundJob = null
        made?.pause()
        playing?.takeIf { it.video == null }?.line?.let { playback?.playPause(MusicMetadata(it.title, it.artist), false) }
        uiState = uiState.copy(practice = null)
    }

    /** Crosstune is out of sight, so the line in the small player stops there, ready to be played again. */
    fun background() {
        val practice = uiState.practice?.takeIf { it.video != null && it.sound == LineSound.PLAYING } ?: return
        made?.pause()
        practice.line?.let { setSound(it, LineSound.PLAYED) }
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
        /** How long a song plays with no seek offered before the app's taken not to offer one. */
        const val STUCK_AFTER_MS = 1_000L
    }
}
