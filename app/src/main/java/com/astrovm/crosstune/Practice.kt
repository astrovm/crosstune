package com.astrovm.crosstune

import com.astrovm.crosstune.PracticeClockLog.Companion.clock
import org.json.JSONObject

import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val words = text.lowercase().split(phoneRegex("[^\\p{L}]+")).filter { it.isNotEmpty() }
    return words.all { word -> FILLER_WORD.matches(word) }
}

private const val FILLER_KANA = "ラらナなアあオおウうーぁッっ"

private val FILLER_WORD = phoneRegex("(la|na|da|oh|ah|uh|eh|ooh|oo|hey|yeah|yeh|ya|woah|whoa|wo|mm+|hm+|ha|doo|ba|sha)+")

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
 * Kept lines practised as cards, on [screen]: each card's line ready where it can be heard, and played once it's
 * turned, then said how well it was known. Crosstune stays in front for every card: a line plays in the music app
 * that already has its song open and moves in it, else in the small YouTube player, else it has no sound. No app is
 * opened, woken or asked to find the song for a card. What each app does is learned as cards use it, in [traits].
 */
internal class PracticeCards(
    screen: MutableState<UiState>,
    /**
     * Where a card's work runs: on the thread every ask comes on, the main thread in the app. The work reads the card and
     * writes it back, so an ask made on another thread could come between, and a grade be written over with the card before it.
     */
    private val scope: CoroutineScope,
    private val playback: PlaybackSource?,
    private val matcher: ExactMatcher,
    private val store: SavedLinesStore,
    private val traits: AppTraitsStore,
    private val wallClock: () -> Long,
    private val now: () -> Long,
    private val reminders: PracticeReminders? = null,
    /** How long the app opened to go back to what played before practice is given to start it. */
    private val songLoadMs: Long = MainViewModel.LOAD_SONG_MS,
    /** Makes the small YouTube player a line plays in when no music app has its song open, once it's wanted; nullable so tests need none. */
    private val newPlayer: (() -> LinePlayer)? = null,
    /** Where a card's line is read again: the dictionary loads the first time, and the screen would freeze meanwhile. */
    private val readDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clockLog: PracticeClockLog = PracticeClockLog(false)
) {
    private var uiState by screen
    private var soundJob: Job? = null

    /** The small player, once made: a WebView is heavy, and most people never need it. */
    private var made: LinePlayer? = null

    private var released = false

    private fun player(): LinePlayer? {
        if (released) return null
        return made ?: try {
            newPlayer?.invoke()?.also { made = it }
        } catch (_: android.util.AndroidRuntimeException) {
            // Android can have no WebView provider while it is removed or updated.
            null
        } catch (_: UnsupportedOperationException) {
            // Some devices do not support WebView at all.
            null
        }
    }

    /** The view model ended: no delayed page may play a line or keep a WebView alive. */
    fun release() {
        stopAudio()
        letGo(null)
        made?.release()
        made = null
        released = true
    }

    /** The small player's picture for the card to show, which is made when asked for. */
    fun playerView(): android.view.View? = player()?.view()

    /** Where each app was sent lately, so a report of the last card's line isn't taken for where it stopped on this one. */
    private val sentPlaces = mutableMapOf<Pair<String?, Int>, SentPlaces>()

    /**
     * Where each app said it stopped short, in each song, by package: TIDAL without an account at its 30 s preview's end.
     * Lines that go on past there can't be played out in it, so it's left out of them until Crosstune starts again. Lines
     * that end before that place, and other songs, still try it first (Issue #937).
     */
    private val stoppedAt = mutableMapOf<Pair<String, MusicMetadata>, Long>()

    /** Apps a card stopped, kept paused a moment, see [keepStopped], by package. */
    private data class KeptPause(val app: String, val job: Job)
    private val keptStopped = ConcurrentHashMap<Pair<String?, Int>, KeptPause>()

    /** The controls already sent for this card, even while their answer is pending. */
    private data class NativeTarget(val match: PlaybackMatch, val remember: Boolean) {
        val clockAttempt = PracticeClockLog.nextId()
        val source get() = match.source
        val app get() = match.app
        val key get() = app to match.session
    }
    private data class NativeSound(val song: MusicMetadata, val target: NativeTarget)
    private var nativeSound: NativeSound? = null
    private var audioVersion = 0L

    /** Cancels pending sound and stops only the players this card controlled. */
    private fun stopAudio() {
        soundJob?.cancel()
        soundJob = null
        audioVersion++
        val owned = nativeSound
        nativeSound = null
        owned?.let { it.target.source.playPause(it.song, false, it.target.app) }
        made?.pause()
    }

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
     * Shows the next card, with its line ready, not played: heard, it would say how it reads, which is
     * what's asked. It plays once the card's turned, or at once when hearing it is the question.
     */
    private fun showCard() {
        stopAudio()
        val practice = uiState.practice ?: return
        val line = practice.line ?: return
        uiState = uiState.copy(practice = practice.copy(turned = false, lastSong = line, sound = if (line.timed) LineSound.LOADING else LineSound.NONE, video = null))
        if (line.timed) sound(line)
    }

    /**
     * Prepares [line], then plays it when the visible side has sound or replay was [explicit].
     * The music app that already has its song open plays it, else the small YouTube player.
     */
    private fun sound(line: SavedLine, explicit: Boolean = false) {
        soundJob = scope.launch {
            if (!inOpenApp(line, explicit)) inYouTube(line, explicit)
        }
    }

    /** Automatic sound follows the visible side. Replay is an explicit request on either side. */
    private fun wantsSound(line: SavedLine, explicit: Boolean): Boolean {
        val practice = uiState.practice ?: return false
        val options = typeOf(line).options
        return explicit || (if (practice.turned) options.back else options.front).sound
    }

    /**
     * Has the music app that already has [line]'s song open play it, as its media session says, which needs Android to let
     * Crosstune see what plays. False when none has it, or the one that has it won't move to the line or play it out. Nothing's
     * opened, woken or asked for, so Crosstune stays in front.
     */
    private suspend fun inOpenApp(line: SavedLine, explicit: Boolean): Boolean {
        val source = playback?.takeIf { it.hasAccess() } ?: return false
        val song = MusicMetadata(line.title, line.artist)
        for (target in matchingTargets(source, song, line)) {
            var played = false
            var sawState = false
            val there = target.clockPhase("native_result") { inApp(target.source, song, target.app, onStuck = { following ->
                sawState = following != null
                following?.let { if (usable(it.appPackage, line)) stuck(target.source, song, it) else leftOut(target.source, song, it) }
            }) { following ->
                val app = following() ?: return@inApp
                sawState = true
                if (!usable(app.appPackage, line)) return@inApp leftOut(target.source, song, app)
                step("trying ${target.app ?: "the app"}")
                letGoTarget(target.key)
                played = playLine(target, song, line, explicit, following)
            } }
            target.clockVerdict("native_result", if (played) "used" else "fallback",
                if (there) "line_attempt" else if (sawState) "not_ready" else "no_state")
            if (played) return true
            if (!sawState) stopIn(target, song)
        }
        return false
    }

    /** Keeps session handles through selection and controls. Readiness determines order, not eligibility. */
    private suspend fun matchingTargets(source: PlaybackSource, song: MusicMetadata, line: SavedLine): List<NativeTarget> {
        var matches = source.matching(song)
        if (matches.isEmpty()) withTimeoutOrNull(FIND_SONG_MS) {
            while (matches.isEmpty()) {
                delay(SOUND_CHECK_MS)
                matches = source.matching(song)
            }
        }
        if (matches.isEmpty()) step("no app has the song open")
        val counts = matches.groupBy { it.app }.mapValues { (_, sessions) -> maxOf(sessions.size, sessions.maxOf { it.appSessions }) }
        // Different sessions can have different capabilities. An app-wide failure cannot identify one of them.
        counts.filterValues { it > 1 }.keys.filterNotNull().forEach(traits::forget)
        val targets = matches.map { NativeTarget(it, counts[it.app] == 1) }
        val candidates = targets.filter { target ->
            val allowed = usable(target.app, line)
            val reachable = allowed && reaches(target.app, song, target.match.following, line)
            val accepted = reachable && fits(target.match.following, line)
            val reason = if (!allowed) "service_policy" else if (!reachable) "stopped_short" else if (!accepted) "outside_recording" else "candidate"
            target.clockCandidate(line, accepted, reason)
            accepted
        }
            .sortedWith(compareByDescending<NativeTarget> { it.match.following?.let { state -> state.canSeek && !state.ad } == true }.thenByDescending { it.match.playing })
        pauseOthers(targets, candidates.firstOrNull()?.key, song, line)
        return candidates
    }

    /**
     * False when [app] stopped short in [song] before [line] ends, see [stoppedAt]: it would stop inside the line, and the
     * line be heard cut and then again from the player. So is a line from a little before where it stopped, as apps round.
     */
    private fun reaches(app: String?, song: MusicMetadata, state: Following?, line: SavedLine): Boolean {
        val stop = app?.let { stoppedAt[it to song] } ?: return true
        val window = LineWindow.of(line, lead = if (state?.video == true) line.leadMs else 0L)
        return window.start < stop - LineWindow.SEEK_SLACK_MS && window.end <= stop
    }

    /**
     * Notes that [app] stopped short at [positionMs] in [song]. It's only sent to lines that end by where it stopped before, see
     * [reaches], and it stops short of a line's start, so a new place is always the earlier one.
     */
    private fun stoppedShort(app: String?, song: MusicMetadata, positionMs: Long) {
        app?.let { stoppedAt[it to song] = positionMs }
    }

    private fun fits(state: Following?, line: SavedLine): Boolean =
        state == null || LineWindow.of(line, lead = if (state.video) line.leadMs else 0L).within(state.durationMs) != null

    private fun pauseOthers(targets: List<NativeTarget>, chosen: Pair<String?, Int>?, song: MusicMetadata, line: SavedLine) {
        targets.filter { it.key != chosen }.forEach { target ->
            val state = target.match.following
            if (!fits(state, line)) {
                stopIn(target, song)
                step("${target.app} is left out")
            } else if (state != null) {
                if (target.match.playing || state.ad) leftOut(target.source, song, state)
            } else if (target.match.playing) target.app?.let(target.source::pauseApp)
        }
    }

    /** Pauses the matching session that will not play this card before another player starts. */
    private fun leftOut(source: PlaybackSource, song: MusicMetadata, following: Following) {
        if (following.clock.playing || following.ad) source.playPause(song, false, following.appPackage)
        step("${following.appPackage} is left out")
    }

    /** A matching session can be tried even if an earlier attempt failed. */
    private fun usable(app: String?, line: SavedLine): Boolean =
        app == null || MusicService.ofPackage(app) != MusicService.YOUTUBE || app == line.app

    /**
     * Moves [target] to [line] and plays it when the visible side has sound or replay was [explicit].
     * True only once the app said it's at the line, and, played, played it to its end. One that never gets there, errs,
     * goes on to another song or stops on the way didn't, so the player can try instead.
     */
    private suspend fun playLine(target: NativeTarget, song: MusicMetadata, line: SavedLine, explicit: Boolean, following: () -> Following?): Boolean {
        val app = target.app
        // What the app plays may end before the line does, as TIDAL's 30 s preview without an account: it's paused at its end, before it goes on to its next song.
        val window = LineWindow.of(line, lead = if (following()?.video == true) line.leadMs else 0L).within(following()?.durationMs)
            ?: return notThere(target, song, wantsSound(line, explicit))
        val (reached, playing) = reachAsAsked(target, song, line, window, explicit, following)
        val at = reached ?: return notThere(target, song, playing)
        // The card says the line plays before the sound comes out. A listener can already hear it
        // while the card still says it is loading, if the sound is let out first.
        if (playing) setSound(line, LineSound.PLAYING)
        rememberControl(target, playing = false, works = true)
        if (!playing) {
            nativeSound = null
            setSound(line, LineSound.PLAYED)
            return true
        }
        return playedOut(target, song, line, window, following).also { kept ->
            step(if (kept) "${app ?: "the app"} played the line from ${signed(at - window.start)} ms" else "${app ?: "the app"} stopped playing the line")
        }
    }

    /**
     * Gets [target] to [window]. Checks the visible side again after readiness changes.
     * Returns where it arrived and whether it was asked to play.
     */
    private suspend fun reachAsAsked(target: NativeTarget, song: MusicMetadata, line: SavedLine, window: LineWindow, explicit: Boolean, following: () -> Following?): Pair<Long?, Boolean> {
        val playing = wantsSound(line, explicit)
        val reached = reach(target, song, window, playing, following)
        val wanted = wantsSound(line, explicit)
        if (reached == null || playing == wanted) return reached to playing
        return reach(target, song, window, wanted, following) to wanted
    }

    private fun rememberControl(target: NativeTarget, playing: Boolean, works: Boolean) {
        if (target.remember) target.app?.let { if (playing) traits.keeps(it, works) else traits.seeks(it, works) }
    }

    /** Stops an attempt that did not reach the line before trying the player. */
    private fun notThere(target: NativeTarget, song: MusicMetadata, playing: Boolean): Boolean {
        val app = target.app
        stopIn(target, song)
        nativeSound = null
        step("${app ?: "the app"} did not ${if (playing) "play" else "get to"} the line")
        return false
    }

    /** Lets [target] play [window] out, then pauses it. Remembers a successful play. */
    private suspend fun playedOut(target: NativeTarget, song: MusicMetadata, line: SavedLine, window: LineWindow, following: () -> Following?): Boolean {
        val source = target.source
        val app = target.app
        setSound(line, LineSound.PLAYING)
        val kept = playsThrough(target, window, following)
        if (kept) source.playPause(song, false, app) else stopIn(target, song)
        nativeSound = null
        if (kept) rememberControl(target, playing = true, works = true)
        setSound(line, if (kept) LineSound.PLAYED else LineSound.LOADING)
        return kept
    }

    /**
     * Sends [app] to [window] and has it [play] there, or pause, once it's ready: following the song, able to move, and not in
     * an ad. Then waits for it to say it's at the line, as asked; where it said it was, or nowhere when it doesn't in
     * [LineWindow.SEEK_WAIT_MS], or says it stopped short of it, see [LineWindow.stoppedShort]: TIDAL at its preview's
     * end has nowhere else to go. One that falls out on the way, as YouTube Music errs, and comes back is asked once more.
     */
    private suspend fun reach(target: NativeTarget, song: MusicMetadata, window: LineWindow, play: Boolean, following: () -> Following?): Long? = target.clockPhase("reach_result") {
        val source = target.source
        val app = target.app
        val sent = sentPlaces.getOrPut(target.key) { SentPlaces() }
        var reason = "timeout"
        val result = withTimeoutOrNull(LineWindow.SEEK_WAIT_MS) {
            var asks = 0
            var wasReady = false
            var at: Long? = null
            // When it was last sent, on the app's clock, so a report from before isn't taken as its answer, and the places it was since.
            var sentAt = 0L
            val was = mutableListOf<PlaybackClock>()
            while (at == null) {
                val latest = following()
                target.clockSample("reach", window, latest)
                val ready = latest?.takeIf { it.canSeek && !it.ad }
                if (ready != null && !wasReady && asks < ASKS) {
                    sentAt = now()
                    was += sent.lately(app, sentAt) + ready.clock
                    sent.note(app, window.start, sentAt)
                    nativeSound = NativeSound(song, target)
                    target.clockNote("seek_request") {
                        put("target_ms", window.start)
                        put("requested_ms", sentAt)
                        put("play", play)
                    }
                    source.seekTo(song, window.start, app)
                    currentCoroutineContext().ensureActive()
                    source.playPause(song, play, app)
                    asks++
                } else if (asks > 0) {
                    if (ready != null && ready.clock.atMs >= sentAt && window.stoppedShort(ready.clock, was)) {
                        step("${app ?: "the app"} stopped at ${ready.clock.positionMs} ms, short of the line")
                        stoppedShort(app, song, ready.clock.positionMs)
                        reason = "stopped_short"
                        return@withTimeoutOrNull null
                    }
                    // Said again every look while nothing changes: kept once.
                    latest?.clock?.takeIf { it != was.lastOrNull() }?.let(was::add)
                    at = latest?.clock?.takeIf { it.playing == play && window.near(it.positionMs) && window.holds(it.positionAt(now())) }?.positionMs
                }
                wasReady = ready != null
                delay(SOUND_CHECK_MS)
            }
            reason = "at_line"
            at
        }
        target.clockVerdict("reach_result", if (result != null) "reached" else "not_reached", reason)
        result
    }

    /**
     * Watches the app play [window] out: true once it's at the line's end; false when it stops playing the line on the way
     * for a moment, as YouTube Music without Premium pauses itself behind Crosstune, or errs; at once when it goes on to
     * another song or plays somewhere else in it; or when the line hasn't ended in its time.
     */
    private suspend fun playsThrough(target: NativeTarget, window: LineWindow, following: () -> Following?): Boolean = target.clockPhase("play_result") {
        var over = false
        var reason = "timeout"
        withTimeoutOrNull(window.patience) {
            var offSince: Long? = null
            while (!lasted(offSince, now(), PAUSED_FOR_MS)) {
                // Gone from the song, as to the next one, it's stopped now rather than heard a moment: no pause comes back from that.
                val state = following()
                target.clockSample("play", window, state)
                if (state == null) { reason = "missing_session"; break }
                val clock = state.clock
                val at = clock.positionAt(now())
                over = window.over(at)
                if (over) { reason = "line_end"; break }
                // Playing somewhere else, as TIDAL's next song once its 30 s preview ends under a song it says is whole, is heard: stopped now, without a pause's grace.
                if (clock.playing && !window.holds(at)) { reason = "outside_line"; break }
                offSince = if (clock.playing && window.holds(at)) null else offSince ?: now()
                delay(SOUND_CHECK_MS)
            }
            if (reason == "timeout") reason = "paused"
        }
        target.clockVerdict("play_result", if (over) "played" else "not_played", reason)
        over
    }

    /** Stops [app] once a line asked of it didn't play: the song, or one it went on into; the app playing the song when it didn't say which it is. */
    private fun stopIn(target: NativeTarget, song: MusicMetadata) {
        val app = target.app
        if (app == null) return target.source.playPause(song, false)
        target.source.pauseApp(app)
        keepStopped(target)
    }

    /** Repeats a failed session's pause while it can still advance past the preview. */
    private fun keepStopped(target: NativeTarget) {
        val app = target.app ?: return
        letGoTarget(target.key)
        val job = scope.launch {
            withTimeoutOrNull(KEEP_STOPPED_MS) {
                while (true) {
                    delay(SOUND_CHECK_MS)
                    target.source.pauseApp(app)
                }
            }
        }
        keptStopped[target.key] = KeptPause(app, job)
    }

    private fun letGoTarget(key: Pair<String?, Int>) {
        keptStopped.remove(key)?.job?.cancel()
    }

    /** Releases an app for explicit restoration, or all sessions when practice stops. */
    private fun letGo(packageName: String?) {
        keptStopped.entries.filter { packageName == null || it.value.app == packageName }.forEach { letGoTarget(it.key) }
    }

    /** The session is not ready to seek. Pause it for the player, without remembering a failed control. */
    private fun stuck(source: PlaybackSource, song: MusicMetadata, following: Following) {
        val name = following.appPackage
        step("${name ?: "the app"} plays and does not move")
        if (following.clock.playing || following.ad) source.playPause(song, false, name)
    }

    /** [ms] with its sign, as an offset from the line reads. */
    private fun signed(ms: Long) = if (ms > 0) "+$ms" else "$ms"

    private suspend fun <T> NativeTarget.clockPhase(event: String, read: suspend () -> T): T = try {
        read()
    } catch (cancelled: CancellationException) {
        clockVerdict(event, "cancelled", "cancelled")
        throw cancelled
    }

    private fun NativeTarget.clockNote(event: String, fields: JSONObject.(Long) -> Unit) {
        clockLog.note(event, now) { at ->
            put("app", app.orEmpty())
            put("session", match.session)
            put("attempt", clockAttempt)
            fields(at)
        }
    }

    private fun NativeTarget.clockCandidate(line: SavedLine, accepted: Boolean, reason: String) {
        clockNote("candidate") { at ->
            put("accepted", accepted)
            put("reason", reason)
            put("window_start_ms", line.startMs ?: JSONObject.NULL)
            put("window_end_ms", line.endMs ?: JSONObject.NULL)
            put("lead_ms", line.leadMs)
            clock(match.following, at)
        }
    }

    private fun NativeTarget.clockSample(phase: String, window: LineWindow, state: Following?) {
        clockNote("card_clock") { at ->
            put("phase", phase)
            put("window_start_ms", window.start)
            put("window_end_ms", window.end)
            clock(state, at)
        }
    }

    private fun NativeTarget.clockVerdict(event: String, result: String, reason: String) {
        clockNote(event) { put("result", result); put("reason", reason) }
    }

    /** One step of a card finding its sound, to the log when it's asked for: `adb shell setprop log.tag.$CHAIN_LOG DEBUG`. */
    private fun step(what: String) {
        if (Log.isLoggable(CHAIN_LOG, Log.DEBUG)) Log.d(CHAIN_LOG, what)
    }

    /**
     * Prepares [line] in the small player. Plays it when the visible side has sound or replay was [explicit].
     */
    private suspend fun inYouTube(line: SavedLine, explicit: Boolean) {
        val player = player()
        val clip = if (player == null) null else videoOf(line)
        currentCoroutineContext().ensureActive()
        if (player == null || clip == null) return setSound(line, LineSound.NONE).also { step("no sound") }
        step("the YouTube player")
        val (video, expectedMs) = clip
        val start = line.startMs ?: 0L
        // Stop at the next line when it starts inside the tail, so that line is not heard.
        val end = LineWindow.playedUntil(start, line.endMs ?: start, line.nextMs)
        val now = wantsSound(line, explicit)
        val practice = uiState.practice?.takeIf { it.line?.let { shown -> shown.isSame(line) && shown.cardType == line.cardType } == true } ?: return
        uiState = uiState.copy(practice = practice.copy(video = video, sound = if (now) LineSound.PLAYING else LineSound.PLAYED))
        val version = audioVersion
        player.show(video, start, end, expectedMs, now) { ended ->
            if (version != audioVersion) return@show
            if (ended == LineEnd.ENDED) setSound(line, LineSound.PLAYED) else noVideo(line)
        }
    }

    /** The video that plays [line]'s song, with how long the song is when known, or null when there's none that's the song. */
    private suspend fun videoOf(line: SavedLine): Pair<String, Long?>? {
        val song = MusicMetadata(line.title, line.artist)
        // Only the saved recording's length is trusted. A native session can be a short preview.
        val expectedMs = line.lengthMs
        // Asked once more only when the search failed, as offline or slow: one that found nothing would find the same again,
        // and ask Apple, Deezer and ChatGPT the same again too.
        val asked = matcher.lookFor(MusicService.YOUTUBE_MUSIC, song)
        val found = if (asked == ExactMatcher.Match.Failed) matcher.lookFor(MusicService.YOUTUBE_MUSIC, song) else asked
        val id = (found as? ExactMatcher.Match.Found)?.url?.toHttpUrlOrNull()?.queryParameter("v") ?: return null
        return id to expectedMs
    }

    /** [line]'s video can't be played: it has no sound, and the player's gone from its card. */
    private fun noVideo(line: SavedLine) {
        val practice = uiState.practice?.takeIf { it.line?.let { shown -> shown.isSame(line) && shown.cardType == line.cardType } == true } ?: return
        uiState = uiState.copy(practice = practice.copy(video = null, sound = LineSound.NONE))
    }

    /**
     * Follows [song] in the music app, [app]'s alone when named, then does [block] once it can be moved around in, with where
     * it is as that changes. True when it could. When it's there and won't move, as Spotify's free plan, [onStuck] hears what it
     * was doing. When no app has it, it's opened or asked for only when [orOpen], see [openUntilItCanMove].
     */
    private suspend fun inApp(
        source: PlaybackSource, song: MusicMetadata, app: String? = null, orOpen: Boolean = false,
        onStuck: (Following?) -> Unit = {}, block: suspend (() -> Following?) -> Unit
    ): Boolean = coroutineScope {
        var latest: Following? = null
        val following = launch {
            source.follow(song, app).collect { latest = it }
        }
        try {
            val ready: suspend () -> Unit = { while (latest?.canSeek != true) delay(SOUND_CHECK_MS) }
            // Not already there, it's asked for by name, which takes the app a moment.
            val found = withTimeoutOrNull(FIND_SONG_MS) { while (latest == null) delay(SOUND_CHECK_MS) } != null
            val there = if (found) withTimeoutOrNull(FIND_SONG_MS) { ready() } != null else orOpen && openUntilItCanMove(source, song, app) { latest }
            if (there) block { latest } else if (found) onStuck(latest)
            there
        } finally {
            following.cancel()
        }
    }

    /**
     * Has [app] start [song] again, to go back to what played before practice, then waits until it can be moved: asked for it by
     * name, or, as Spotify, which plays nothing asked of it, opened on its own link. Only [app] is: no other is asked in its place,
     * and with none named nothing is. Opened, an app covers Crosstune, which comes back over it only once it may show over other
     * apps, so it's opened only then.
     */
    private suspend fun openUntilItCanMove(source: PlaybackSource, song: MusicMetadata, app: String?, latest: () -> Following?): Boolean {
        val service = app?.let(MusicService::ofPackage)
        val opens = source.comesBackByItself()
        var opened = false
        val open: suspend (String) -> Unit = { link ->
            if (opens && app != null) {
                // Kept stopped once asking it gave nothing, it's let play when it's opened instead.
                letGo(app)
                opened = source.openSong(app, link)
            }
        }
        val asked = when {
            app == null -> false
            service?.playsAskedSongs != false -> asked(source, song, app, open)
            opens && service.playsSongBySong -> false.also { matcher.find(service, song)?.let { link -> open(link) } }
            else -> false
        }
        val canSeek = (asked || opened) && withTimeoutOrNull(songLoadMs) { while (latest()?.canSeek != true) delay(SOUND_CHECK_MS) } != null
        if (opened) source.comesBack()
        return canSeek
    }

    /**
     * Whether [app] was asked for [song] and is starting it. YouTube Music, and a build patched from it, e.g. Morphe's, is given
     * the song's own link, as it doesn't play what's asked by name; one that can't be woken in the background is [open]ed on it.
     */
    private suspend fun asked(source: PlaybackSource, song: MusicMetadata, app: String, open: suspend (String) -> Unit): Boolean {
        val link = if (MusicService.ofPackage(app) == MusicService.YOUTUBE_MUSIC) matcher.find(MusicService.YOUTUBE_MUSIC, song) else null
        // Followed alone, it's the app asked: another one, playing, would be taken for it.
        val started = source.playSong(song, app, link, mustStart = true, orLastPlayed = false)
        // Given up on, it may still start a moment later, as TIDAL woken cold does: it's kept stopped, unless it's opened next.
        if (!started) stopIn(NativeTarget(PlaybackMatch(null, source, app, -1), remember = true), song)
        if (!started && link != null) open(link)
        return started
    }

    /** How [line] sounds, unless another card's shown by now. */
    private fun setSound(line: SavedLine, sound: LineSound) {
        val practice = uiState.practice?.takeIf { it.line?.let { shown -> shown.isSame(line) && shown.cardType == line.cardType } == true } ?: return
        uiState = uiState.copy(practice = practice.copy(sound = sound))
    }

    /** The kind of card [line]'s is. */
    private fun typeOf(line: SavedLine) = uiState.cardTypes.firstOrNull { it.id == line.cardType } ?: READ_IT

    /** Plays the card's line again, or, while it plays, stops it there, paused, to be played again. */
    fun replay() {
        val practice = uiState.practice ?: return
        val line = practice.line?.takeIf { practice.sound != LineSound.NONE } ?: return
        val playing = practice.sound == LineSound.PLAYING
        stopAudio()
        if (!playing) {
            if (practice.video == null) sound(line, explicit = true)
            else soundJob = scope.launch { inYouTube(line, explicit = true) }
            return
        }
        setSound(line, LineSound.PLAYED)
    }

    /** Turns the card and applies the sound setting of its back. */
    fun turn() {
        val practice = uiState.practice?.takeIf { !it.turned } ?: return
        uiState = uiState.copy(practice = practice.copy(turned = true))
        val line = practice.line ?: return
        if (!wantsSound(line, explicit = false)) {
            stopAudio()
            if (practice.sound == LineSound.LOADING) sound(line) else if (practice.sound == LineSound.PLAYING) setSound(line, LineSound.PLAYED)
        } else if (practice.sound == LineSound.PLAYED) replay()
    }

    /** Card edits, one at a time, so one doesn't undo another. */
    private val editing = Mutex()

    /**
     * The card's line read as [readings] say, one for each of its kanji, put right by hand: only what they change in it as
     * shown, laid over it as it's kept by then, so what was put right meanwhile, by another edit or by UtaTen, stays.
     */
    fun readAs(readings: List<String>) {
        val seen = uiState.practice?.line ?: return
        scope.launch {
            editing.withLock {
                val saved = store.change(readDispatcher) { kept ->
                    val edited = seen.readAs(readings).takeIf { it != seen } ?: return@change null
                    val read = kept.firstOrNull { it.isSame(seen) }?.editedAs(seen, edited) ?: return@change null
                    kept.map { if (it.isSame(read)) read else it }
                } ?: return@withLock
                val read = saved.first { it.isSame(seen) }
                // Practice may have gone on meanwhile: the line's put right wherever it is in it now.
                val practice = uiState.practice
                uiState = uiState.copy(
                    savedLines = saved,
                    practice = practice?.let { now -> now.copy(queue = now.queue.map { if (it.isSame(read)) it.copy(reading = read.reading, romanized = read.romanized, ruby = read.ruby, readByHand = true, typed = read.typed) else it }) }
                )
            }
        }
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
        stopAudio()
        letGo(null)
        uiState = uiState.copy(practice = null)
    }

    /** Out of sight, pending and active card sound stops. Replay remains available on return. */
    fun background() {
        val practice = uiState.practice ?: return
        letGo(null)
        // Replay can still be preparing while the card displays its previous PLAYED state.
        if (soundJob?.isActive != true && practice.sound != LineSound.LOADING && practice.sound != LineSound.PLAYING) return
        stopAudio()
        if (practice.sound == LineSound.LOADING || practice.sound == LineSound.PLAYING) {
            practice.line?.let { setSound(it, LineSound.PLAYED) }
        }
    }

    /**
     * Done, the music app goes back to what it played before, where it was, and practice closes. What named no song,
     * e.g. a radio station, plays on, as there's nowhere in it to go back to. The user asked for it, so the app it played in
     * may be asked for the song again, or opened on it, see [openUntilItCanMove]; no other app is.
     */
    fun backToBefore() {
        val before = uiState.practice?.before ?: return
        val source = playback ?: return
        stop()
        if (before.songless) return source.playPause(before.song, true, before.app)
        soundJob = scope.launch {
            // A card may have stopped it a moment ago: it's let play.
            letGo(before.app)
            inApp(source, before.song, before.app, orOpen = true) {
                source.seekTo(before.song, before.positionMs, before.app)
                source.playPause(before.song, true, before.app)
            }
        }
    }

    private companion object {
        /** How long a song the music app has already takes to be found. */
        const val FIND_SONG_MS = 400L
        const val SOUND_CHECK_MS = 50L
        /** How long an app a card stopped is kept paused: TIDAL started its next song 1.7 s after it was paused. */
        const val KEEP_STOPPED_MS = 5_000L
        /** How long an app that was playing the line may stay paused before it's taken to have stopped playing it. Playing elsewhere, it has at once. */
        const val PAUSED_FOR_MS = 1_500L
        /** How many times an app is sent to the line: once, and again when it fell out on the way and came back. */
        const val ASKS = 2
        const val CHAIN_LOG = "CrosstuneChain"
    }
}
