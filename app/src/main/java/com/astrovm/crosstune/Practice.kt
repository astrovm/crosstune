package com.astrovm.crosstune

import android.util.Log
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
 * Kept lines practised as cards, on [screen]: each card's line ready in the best app the phone has for it, and
 * played once it's turned, then said how well it was known. A card's line plays in the app the user prefers if
 * it moves to the line and goes on playing with Crosstune in front, else in another that does, else in the small
 * YouTube player, else it has no sound. What each app does is learned as cards use it, in [traits].
 */
internal class PracticeCards(
    screen: MutableState<UiState>,
    private val scope: CoroutineScope,
    private val playback: PlaybackSource?,
    private val matcher: ExactMatcher,
    private val store: SavedLinesStore,
    private val traits: AppTraitsStore,
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

    /** The apps that failed a card since Crosstune started, by package, so the next card doesn't wait on them again. */
    private val unusable = mutableSetOf<String>()

    /** The app the card's line plays in, for the pause that stops it. */
    private var soundApp: String? = null

    /** How long each song is, by title and artist, as the music app said when it played it. */
    private val lengths = mutableMapOf<Pair<String, String>, Long>()

    /**
     * While an app opened or asked for the card's song is on its way to the line, it's paused as soon as it plays: it starts
     * the song from its start, which isn't the line, and on a card's front hearing the line is the answer. Off once it's sent there.
     */
    @Volatile
    private var hushed = false

    /** When the app [hushed] first played the song, paused then; null until it has. */
    @Volatile
    private var hushedAt: Long? = null

    /** An app was left out of this card's chain as opening it would have covered the card for good: see [Practice.cantComeBack]. */
    @Volatile
    private var leftOut = false

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
     * Has the best app play [line], from where it's sung to the next line, then pause; or, when not
     * [play] and the card isn't turned yet, only get there. A song an app isn't playing it's asked to find first.
     * When no app moves to the line and goes on playing, the small YouTube player plays it.
     */
    private fun sound(line: SavedLine, play: Boolean = true) {
        val source = playback ?: return
        soundJob?.cancel()
        soundJob = scope.launch {
            val song = MusicMetadata(line.title, line.artist)
            leftOut = false
            val inApp = inBestApp(source, song, line, play)
            if (leftOut) uiState.practice?.let { uiState = uiState.copy(practice = it.copy(cantComeBack = true)) }
            if (!inApp) inYouTube(line, play)
        }
    }

    /** Goes through the apps [ranked] for [line], a few at most, until one plays it; false when none does. */
    private suspend fun inBestApp(source: PlaybackSource, song: MusicMetadata, line: SavedLine, play: Boolean): Boolean {
        val preferred = (uiState.defaultDestination as? Destination.Service)?.service
        var tried = 0
        for (app in ranked(line)) {
            // An app that's neither preferred nor the one the line was kept from needs the song on its service, or it plays whatever it finds by the name.
            val known = app == null || app == line.app || MusicService.ofPackage(app) == preferred
            if (tried >= MAX_APPS || (!known && !hasSong(app, song))) continue
            tried++
            step("trying ${app ?: "any app"}")
            if (inMusicApp(source, song, line, app, play)) return true
        }
        return false
    }

    /** Whether the service [app] is of has [song], as its links say. */
    private suspend fun hasSong(app: String?, song: MusicMetadata): Boolean =
        MusicService.ofPackage(app)?.let { matcher.find(it, song) } != null

    /**
     * The apps to try for [line], best first: the user's preferred app, then those seen to move and go on playing,
     * then those not seen yet, without the ones seen to fail, or failing a card since Crosstune started. The app
     * [line] was kept from is one of them, too; with none known, any app that has the song.
     */
    private fun ranked(line: SavedLine): List<String?> {
        val all = (uiState.musicApps + listOfNotNull(line.app)).distinct().filter { MusicService.ofPackage(it) != MusicService.YOUTUBE }
        if (all.isEmpty()) return listOf(null)
        val live = all.filter { it !in unusable && !traits.of(it).fails }
        val preferred = (uiState.defaultDestination as? Destination.Service)?.let { destination ->
            live.filter { MusicService.ofPackage(it) == destination.service }.sortedBy { it != destination.packageName }
        }.orEmpty()
        val others = live - preferred.toSet()
        return preferred + others.filter { traits.of(it).works } + others.filter { !traits.of(it).works }
    }

    /**
     * Has [app] play [line], moving to it first. False when it can't be moved there, or stops playing before the
     * line is over, which the next app, or the player, then makes up for.
     */
    private suspend fun inMusicApp(source: PlaybackSource, song: MusicMetadata, line: SavedLine, app: String?, play: Boolean): Boolean {
        var kept = true
        val wasLeftOut = leftOut
        leftOut = false
        val moved = inApp(source, song, app, only = app, hush = true, onStuck = { following -> stuck(source, song, app, following) }) { following ->
            kept = playLine(source, song, line, app, play, following)
        }
        // Left out only for want of showing over other apps, it's tried again once that's allowed.
        if ((!moved || !kept) && !leftOut) app?.let(unusable::add)
        leftOut = leftOut || wasLeftOut
        if (!moved) step("${app ?: "any app"} did not move to the line")
        return moved && kept
    }

    /**
     * Moves [app] to [line] and has it play there, or, when not [play] and the card isn't turned yet, only get there.
     * True only once the app said it's at the line, and, played, played it to its end. One that never gets there, errs,
     * goes on to another song or stops on the way didn't, and is remembered not to, for the next app, or the player.
     */
    private suspend fun playLine(source: PlaybackSource, song: MusicMetadata, line: SavedLine, app: String?, play: Boolean, following: () -> Following?): Boolean {
        val packageName = app ?: following()?.appPackage
        // What the app plays may end before the line does, as TIDAL's 30 s preview without an account: it's paused at its end, before it goes on to its next song.
        val window = LineWindow.of(line, lead = if (following()?.video == true) line.leadMs else 0L).within(following()?.durationMs)
            ?: return notThere(source, song, app, packageName, play || uiState.practice?.turned == true)
        val (at, playing) = reachAsAsked(source, song, app, window, play, following)
        if (at == null) return notThere(source, song, app, packageName, playing)
        packageName?.let { traits.seeks(it, true) }
        if (!playing) return true.also { setSound(line, LineSound.PLAYED) }
        return playedOut(source, song, line, app, packageName, window, following).also { kept ->
            step(if (kept) "${packageName ?: "the app"} played the line from ${signed(at - window.start)} ms" else "${packageName ?: "the app"} stopped playing the line")
        }
    }

    /**
     * Gets [app] to [window], playing when [play] or the card's turned; turned while it got there, it plays now. Where it
     * said it was, null when it never did, and whether it was asked to play.
     */
    private suspend fun reachAsAsked(source: PlaybackSource, song: MusicMetadata, app: String?, window: LineWindow, play: Boolean, following: () -> Following?): Pair<Long?, Boolean> {
        val turned = { uiState.practice?.turned == true }
        val playing = play || turned()
        val at = reach(source, song, app, window, playing, following)
        if (at == null || playing || !turned()) return at to playing
        return reach(source, song, app, window, true, following) to true
    }

    /** [packageName] didn't get to the line, or play it once there: it's remembered, and stopped, for the next app. */
    private fun notThere(source: PlaybackSource, song: MusicMetadata, app: String?, packageName: String?, playing: Boolean): Boolean {
        packageName?.let { if (playing) traits.keeps(it, false) else traits.seeks(it, false) }
        stopIn(source, song, app, packageName)
        step("${packageName ?: "the app"} did not ${if (playing) "play" else "get to"} the line")
        return false
    }

    /** Lets [app], at the line and playing, play [window] out, then pauses it; whether it did, which is remembered. */
    private suspend fun playedOut(
        source: PlaybackSource, song: MusicMetadata, line: SavedLine, app: String?, packageName: String?, window: LineWindow, following: () -> Following?
    ): Boolean {
        soundApp = app
        setSound(line, LineSound.PLAYING)
        val kept = playsThrough(window, following)
        if (kept) source.playPause(song, false, app) else stopIn(source, song, app, packageName)
        packageName?.let { traits.keeps(it, kept) }
        setSound(line, if (kept) LineSound.PLAYED else LineSound.LOADING)
        return kept
    }

    /**
     * Sends [app] to [window] and has it [play] there, or pause, once it's ready: following the song, able to move, and not in
     * an ad. Then waits for it to say it's at the line, as asked; where it said it was, or null when it doesn't in
     * [LineWindow.SEEK_WAIT_MS]. One that falls out on the way, as YouTube Music errs, and comes back is asked once more.
     */
    private suspend fun reach(source: PlaybackSource, song: MusicMetadata, app: String?, window: LineWindow, play: Boolean, following: () -> Following?): Long? =
        withTimeoutOrNull(LineWindow.SEEK_WAIT_MS) {
            var asks = 0
            var wasReady = false
            var at: Long? = null
            while (at == null) {
                val latest = following()
                val ready = latest != null && latest.canSeek && !latest.ad
                if (ready && !wasReady && asks < ASKS) {
                    hushed = false
                    source.seekTo(song, window.start, app)
                    source.playPause(song, play, app)
                    asks++
                } else if (asks > 0) {
                    at = latest?.clock?.takeIf { it.playing == play && window.near(it.positionMs) && window.holds(it.positionAt(now())) }?.positionMs
                }
                wasReady = ready
                delay(SOUND_CHECK_MS)
            }
            at
        }

    /**
     * Watches the app play [window] out: true once it's at the line's end; false when it stops playing the line on the way
     * for a moment, as YouTube Music without Premium pauses itself behind Crosstune, errs, or goes on to another song or
     * somewhere else in it; or when the line hasn't ended in its time.
     */
    private suspend fun playsThrough(window: LineWindow, following: () -> Following?): Boolean {
        var over = false
        withTimeoutOrNull(window.patience) {
            var offSince: Long? = null
            while (!lasted(offSince, now(), PAUSED_FOR_MS)) {
                // Gone from the song, as to the next one, it's stopped now rather than heard a moment: no pause comes back from that.
                val clock = (following() ?: break).clock
                val at = clock.positionAt(now())
                over = window.over(at)
                if (over) break
                offSince = if (clock.playing && window.holds(at)) null else offSince ?: now()
                delay(SOUND_CHECK_MS)
            }
        }
        return over
    }

    /** Stops [packageName], [app] as it said it is, once a line asked of it didn't play: the song, or one it went on into. */
    private fun stopIn(source: PlaybackSource, song: MusicMetadata, app: String?, packageName: String?) {
        if (packageName != null) source.pauseApp(packageName) else source.playPause(song, false, app)
    }

    /** [app] plays the song and won't move in it, as Spotify's free plan: that's remembered, and it's paused, for the next app to play the line. */
    private fun stuck(source: PlaybackSource, song: MusicMetadata, app: String?, following: Following?) {
        val name = following?.appPackage ?: app
        name?.let { traits.seeks(it, false) }
        step("${name ?: "the app"} plays and does not move")
        if (following?.clock?.playing == true) source.playPause(song, false, app)
    }

    /** [ms] with its sign, as an offset from the line reads. */
    private fun signed(ms: Long) = if (ms > 0) "+$ms" else "$ms"

    /** One step of a card finding its sound, to the log when it's asked for: `adb shell setprop log.tag.$CHAIN_LOG DEBUG`. */
    private fun step(what: String) {
        if (Log.isLoggable(CHAIN_LOG, Log.DEBUG)) Log.d(CHAIN_LOG, what)
    }

    /**
     * Has the small YouTube player play [line], or only get ready when not [play] and the card isn't turned. With no
     * video that's the song's, there's no sound.
     */
    private suspend fun inYouTube(line: SavedLine, play: Boolean) {
        val player = player()
        val clip = if (player == null) null else videoOf(line)
        if (player == null || clip == null) return setSound(line, LineSound.NONE).also { step("no sound") }
        step("the YouTube player")
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
     * Follows [song] in the music app, [only] the one of that package when named, asking [app] for it by name when no
     * app has it, then does [block] once it can be moved around in, with where it is as that changes. True when it could.
     * When it's there and won't move, as Spotify's free plan, [onStuck] hears what it was doing. With [hush], an app that has to
     * be opened or asked for it is kept paused until [block] sends it somewhere, see [hushed].
     */
    private suspend fun inApp(
        source: PlaybackSource, song: MusicMetadata, app: String?, only: String? = null, hush: Boolean = false,
        onStuck: (Following?) -> Unit = {}, block: suspend (() -> Following?) -> Unit
    ): Boolean = coroutineScope {
        var latest: Following? = null
        val following = launch {
            source.follow(song, only).collect {
                latest = it
                it?.durationMs?.let { length -> lengths[song.title to song.artist] = length }
                if (hushed && it?.clock?.playing == true) {
                    hushedAt = hushedAt ?: now()
                    source.playPause(song, false, only)
                }
            }
        }
        try {
            val ready: suspend () -> Unit = { while (latest?.canSeek != true) delay(SOUND_CHECK_MS) }
            // Not already there, it's asked for by name, which takes the app a moment.
            val found = withTimeoutOrNull(FIND_SONG_MS) { while (latest == null) delay(SOUND_CHECK_MS) } != null
            val there = if (found) withTimeoutOrNull(FIND_SONG_MS) { ready() } != null else openUntilItCanMove(source, song, app, hush, { latest }, onStuck)
            if (there) block { latest } else if (found) onStuck(latest)
            there
        } finally {
            hushed = false
            following.cancel()
        }
    }

    /**
     * Opens [song] on its own link, or asks for it, then waits until it can be moved. Spotify plays
     * nothing asked of it by name; its own link starts the song. On a free plan it never offers a
     * seek, and sending one errors the player, so once that song plays and still can't be moved, [onStuck] hears of it.
     */
    private suspend fun openUntilItCanMove(source: PlaybackSource, song: MusicMetadata, app: String?, hush: Boolean, latest: () -> Following?, onStuck: (Following?) -> Unit): Boolean {
        hushed = hush
        hushedAt = null
        // Opened, an app covers the card, and without showing over other apps Crosstune can't come back, nor open the next app from behind it.
        val opens = source.comesBackByItself()
        val ownLink = ownLinkOf(song, app)
        if (ownLink != null && !opens) return false.also { leaveOut(app) }
        var opened = app?.let { ownLink?.let { link -> source.openSong(it, link) } } == true
        val asked = !opened && asked(source, song, app) { asking, link -> if (opens) opened = source.openSong(asking, link) else leaveOut(asking) }
        val wait = MoveWait()
        val canSeek = (asked || opened) && waitUntilItMoves(source, opened, latest, wait)
        if (opened && !wait.cameBack) source.comesBack()
        if (!canSeek && wait.playingSince != null) onStuck(latest())
        return canSeek
    }

    /** What waiting for an app to offer a seek has seen: since when it plays, and whether Crosstune came back to the card. */
    private class MoveWait {
        var playingSince: Long? = null
        var cameBack = false
    }

    /** [app] would have to be opened, which would cover the card for good: it's left out, and the card says why. */
    private fun leaveOut(app: String?) {
        leftOut = true
        step("${app ?: "the app"} would cover the card")
    }

    /** Waits for the app to offer a seek; false when time runs out, or when it plays a moment and still doesn't, as it never will. */
    private suspend fun waitUntilItMoves(source: PlaybackSource, opened: Boolean, latest: () -> Following?, wait: MoveWait): Boolean {
        withTimeoutOrNull(songLoadMs) {
            while (latest()?.canSeek != true) {
                // Once it played, it's paused at once, see [hushed]: how long it then goes without offering a seek still counts.
                if (opened) wait.playingSince = wait.playingSince ?: hushedAt
                // It's playing, so there's nothing more to see there: back to the card, without waiting to be sure.
                if (wait.playingSince != null && !wait.cameBack) {
                    source.comesBack()
                    wait.cameBack = true
                }
                if (lasted(wait.playingSince, now(), STUCK_AFTER_MS)) break
                delay(SOUND_CHECK_MS)
            }
        }
        return latest()?.canSeek == true
    }

    /** [song]'s own link in [app], when that's an app that plays nothing asked of it but each song's own link. */
    private suspend fun ownLinkOf(song: MusicMetadata, app: String?): String? {
        val service = app?.let(MusicService::ofPackage)?.takeIf { !it.playsAskedSongs && it.playsSongBySong } ?: return null
        return matcher.find(service, song)
    }

    /**
     * Whether [song] was asked for and is starting. Kept from no app in particular, or one that plays nothing
     * asked of it, a song YouTube Music has is asked of YouTube Music, not of whatever played last, e.g. a video,
     * which wouldn't play it. An app that can't be woken in the background is [open]ed on the song, the app asked
     * itself, so a build patched from YouTube Music, e.g. Morphe's, isn't swapped for YouTube Music; as YouTube Music
     * is with no app to ask at all, and left again once it has it, unless it only plays in front.
     */
    private suspend fun asked(source: PlaybackSource, song: MusicMetadata, app: String?, open: (String, String) -> Unit): Boolean {
        val from = app?.takeIf { MusicService.ofPackage(it)?.playsAskedSongs != false }
        val link = youTubeMusicLink(song, from)
        val asking = from ?: MusicService.YOUTUBE_MUSIC.packageName
        return source.playSong(song, asking.takeIf { from != null || link != null }, link, mustStart = true).also { if (!it && link != null) open(asking, link) }
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
        if (practice.video != null) made?.pause() else playback?.playPause(MusicMetadata(line.title, line.artist), false, soundApp)
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
        playing?.takeIf { it.video == null }?.line?.let { playback?.playPause(MusicMetadata(it.title, it.artist), false, soundApp) }
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
        const val SOUND_CHECK_MS = 50L
        /** How long a song plays with no seek offered before the app's taken not to offer one. */
        const val STUCK_AFTER_MS = 1_000L
        /** How many apps a card tries before the small player, so a phone full of music apps doesn't make it wait on all of them. */
        const val MAX_APPS = 3
        /** How long an app that was playing the line may stay paused, or say it's elsewhere, before it's taken to have stopped playing it. */
        const val PAUSED_FOR_MS = 1_500L
        /** How many times an app is sent to the line: once, and again when it fell out on the way and came back. */
        const val ASKS = 2
        const val CHAIN_LOG = "CrosstuneChain"
    }
}
