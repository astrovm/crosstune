package com.astrovm.crosstune

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * A music app the test plays from: whether Crosstune may see it, or Android holds that back for now,
 * what it plays, and where it was moved to.
 */
internal class FakePlayback : PlaybackSource {
    var access = false
    var restricted = false
    val playing = MutableStateFlow<Following?>(null)
    val seeks: MutableList<Long> = CopyOnWriteArrayList()
    /** Each ask to play (true) or pause (false). */
    val plays: MutableList<Boolean> = CopyOnWriteArrayList()
    /** Each ask to go on to the next song (true) or back to the one before (false). */
    val skips: MutableList<Boolean> = CopyOnWriteArrayList()
    /** Each ask to shuffle (true) or play in order (false). */
    val shuffled: MutableList<Boolean> = CopyOnWriteArrayList()
    /** Each way it was asked to repeat. */
    val repeated: MutableList<Repeat> = CopyOnWriteArrayList()
    /** How many times its like was pressed. */
    var liked = 0
    /** Each song up next it was asked to play now, by its number. */
    val queuedPlayed: MutableList<Long> = CopyOnWriteArrayList()
    /** Each song asked for by name, and the app asked. */
    val songsAsked: MutableList<Pair<MusicMetadata, String?>> = CopyOnWriteArrayList()
    /** Each song's link given with it, when there was one. */
    val linksAsked: MutableList<String?> = CopyOnWriteArrayList()
    /** Whether there's an app to ask for a song. */
    var canPlaySongs = true
    /** Whether an app asked to play in the background starts; false as YouTube Music without Premium, which never does. */
    var startsInBackground = true
    /** The apps that can be asked for songs in the background, running or woken; any, when null. */
    var running: Set<String>? = null
    /** Whether it was seen not to play in the background, as the phone learns it, by failing to when asked. */
    private var onlyInFront = false
    /** Each app opened on a song's link, and the link. */
    val opened: MutableList<Pair<String, String>> = CopyOnWriteArrayList()
    /** How many times Crosstune came back to the front. */
    var cameBack = 0
    /** Whether the phone's music is muted for a card, see [quiet]. */
    @Volatile
    var quiet = false
    /** Each time the music was muted (true) or let be heard again (false). */
    val quiets: MutableList<Boolean> = CopyOnWriteArrayList()
    private val songs = MutableStateFlow<Heard.Song?>(null)
    var onThePhone: Heard.Song?
        get() = songs.value
        set(value) { songs.value = value }

    override fun hasAccess() = access

    override fun restricted() = restricted

    /** Each song followed, once per time it's asked. */
    val followed: MutableList<MusicMetadata> = CopyOnWriteArrayList()

    /** What each app, by package, plays when a test has it apart from the rest; [playing] for any other. */
    val appPlaying = mutableMapOf<String, MutableStateFlow<Following?>>()

    /** The app each follow was asked for, null for any. */
    val followedApps: MutableList<String?> = CopyOnWriteArrayList()

    /** Till done, following says nothing, as an app may take a moment to say what it plays. */
    @Volatile
    var said: CompletableDeferred<Unit>? = null

    override fun follow(song: MusicMetadata, app: String?): Flow<Following?> {
        followed += song
        followedApps += app
        val flow = followedAs(song, app, app?.let(appPlaying::get) ?: playing)
        return said?.let { later -> flow { later.await(); emitAll(flow) } } ?: flow
    }

    /**
     * What [app] plays, as following [song], matched as the real source does. What names no song, e.g. a station, is
     * followed only asked of its [app], while [unnamed] is still just that there, paused or not: once the app's on to
     * the next station, or a song, it no longer has it. A song is never what [unnamed] is, in its app.
     */
    private fun followedAs(song: MusicMetadata, app: String?, flow: Flow<Following?>): Flow<Following?> {
        val nameless = Songless.namesNoSong(song.title, song.artist)
        if (app == null && !nameless) return flow
        return combine(flow, unnamed) { following, now ->
            following?.takeIf {
                if (nameless) app != null && now?.app == app && now.played.let { it.title == song.title && it.artist == song.artist } else now?.app != app
            }
        }
    }

    /** Whether what started isn't believed yet, as the phone's sound takes a moment to follow what an app says it plays. */
    var quietYet = false

    override fun nowPlaying() = onThePhone.takeUnless { quietYet }

    override fun recording(song: MusicMetadata) = onThePhone?.metadata

    override fun nowPlayingUpdates(): Flow<Heard.Song?> = songs

    /** How many are listening for what's on the phone. */
    val nowPlayingWatchers get() = songs.subscriptionCount.value

    /**
     * What an app has with no song named, e.g. a radio station, playing or paused, as its session says. It counts
     * as [PlaybackSource.playingUnnamed] only while it plays, as Android has it: while what the app plays, its own
     * in [appPlaying] or else [playing], isn't paused. A test that doesn't say how it plays has it playing.
     */
    val unnamed = MutableStateFlow<Unnamed?>(null)

    private fun playingOnly(unnamed: Unnamed?, following: Following?) = unnamed?.takeIf { following?.clock?.playing != false }

    /** Whether [unnamed] is cast to a speaker: it plays, but not on the phone, so there's nothing to hear. */
    var unnamedCast = false

    /** Whether [onThePhone] is a song another device plays, as KDE Connect shows a computer's: last of what plays. */
    var songElsewhere = false

    override fun playingUnnamed() = playingOnly(unnamed.value, flowOf(unnamed.value?.app).value).takeUnless { unnamedCast }

    // As Android ranks them: a song on the phone first, then what names none, on it or cast, then a song elsewhere.
    override fun whatPlays(): WhatPlays? {
        val song = nowPlaying()?.let(WhatPlays::Named)
        val nameless = playingOnly(unnamed.value, flowOf(unnamed.value?.app).value)?.let(WhatPlays::Nameless)
        return if (songElsewhere) nameless ?: song else song ?: nameless
    }

    override fun unnamedUpdates(): Flow<Unnamed?> =
        channelFlow { unnamed.collectLatest { now -> flowOf(now?.app).collect { send(playingOnly(now, it)?.takeUnless { unnamedCast }) } } }.distinctUntilChanged()

    /** Whether the phone's music plays out loud, where the microphone hears it. */
    var aloud = true

    override fun playsAloud() = aloud

    /** The app each seek was sent to, null for any. */
    val seekedApps: MutableList<String?> = CopyOnWriteArrayList()

    /** The app each ask to play or pause was sent to, null for any. */
    val playedApps: MutableList<String?> = CopyOnWriteArrayList()

    /** Runs when an app is asked to play, for a test to have the app do as it would, e.g. pause itself. */
    var onPlay: (String?, Boolean) -> Unit = { _, _ -> }

    /** Runs when the music is muted or let out, so a test can see the card at that moment. */
    var onQuiet: (Boolean) -> Unit = {}

    /**
     * Whether the app goes where it's sent, and plays or pauses when asked, saying so as it does, as a real one does. Off,
     * it only ever says what a test has it say, as one that ignores what it's asked, or for a test that says it all itself.
     */
    var obeys = false

    /** The clock the app says where it is on, as the code under test reads it. */
    var clock: () -> Long = System::currentTimeMillis

    /** What [app] plays, as followed: its own when a test has it apart, else [playing]. */
    private fun flowOf(app: String?) = app?.let(appPlaying::get) ?: playing

    /** Has what [app] plays say it's at [positionMs], playing or not, from now, as an app does once it's done what it was asked. */
    private fun moved(app: String?, positionMs: (PlaybackClock, Long) -> Long, playing: (PlaybackClock) -> Boolean) {
        val flow = flowOf(app)
        val now = flow.value ?: return
        val at = clock()
        if (obeys) flow.value = now.copy(clock = PlaybackClock(positionMs(now.clock, at), at, now.clock.speed, playing(now.clock)))
    }

    override fun seekTo(song: MusicMetadata, positionMs: Long, app: String?) {
        seeks += positionMs
        seekedApps += app
        moved(app, { _, _ -> positionMs }, { it.playing })
    }

    override fun playPause(song: MusicMetadata, play: Boolean, app: String?) {
        plays += play
        playedApps += app
        moved(app, { clock, at -> clock.positionAt(at) }, { play })
        onPlay(app, play)
    }

    /** Each app paused whatever it played, by package, once per pause: an app a card stopped is paused again while it's kept so. */
    val pausedApps: MutableList<String> = CopyOnWriteArrayList()

    /** Each app asked, by package, to play whatever it has. */
    val appsPlayed: MutableList<String> = CopyOnWriteArrayList()

    override fun playApp(app: String) {
        appsPlayed += app
    }

    override fun pauseApp(app: String) {
        pausedApps += app
        moved(app, { clock, at -> clock.positionAt(at) }, { false })
    }

    // Asked to play on, as the phone's, it does unless it only plays in front.
    override suspend fun resume(song: MusicMetadata, app: String?): Boolean {
        plays += true
        playedApps += app
        onPlay(app, true)
        return startsInBackground.also { if (!it) onlyInFront = true }
    }

    /** Each song an app was brought to the front to play on. */
    val broughtToFront: MutableList<MusicMetadata> = CopyOnWriteArrayList()

    override suspend fun bringToFront(song: MusicMetadata, app: String?): Boolean {
        if (!opens) return false
        broughtToFront += song
        return true
    }

    override suspend fun playSong(song: MusicMetadata, app: String?, link: String?, mustStart: Boolean, orLastPlayed: Boolean): Boolean {
        // An app that can't be, with a link only it has, or for a card that follows it alone, leaves no other to ask.
        if (!canPlaySongs || (app != null && (link != null || !orLastPlayed) && running?.contains(app) == false)) return false
        if (mustStart && !startsInBackground) return false.also { onlyInFront = true }
        songsAsked += song to app
        linksAsked += link
        return true
    }

    /** Whether an app can be opened on a song; false as if it couldn't, e.g. gone since. */
    var opens = true

    override fun openSong(app: String, link: String): Boolean {
        if (!opens) return false
        opened += app to link
        return true
    }

    // As the phone's: an app seen not to play in the background is left in front, where it plays.
    override fun playsOnlyInFront(app: String) = onlyInFront

    /** Whether the phone's screen is on and unlocked. */
    var unlocked = true

    override fun unlocked() = unlocked

    /** Whether the phone has no PIN, pattern or password. */
    var lockless = false

    override fun lockless() = lockless

    /** How many times the screen was turned on for a moment. */
    var woken = 0

    override fun wakeScreen() {
        woken++
    }

    override fun comesBack() {
        if (!onlyInFront) cameBack++
    }

    /** Whether Crosstune may show over other apps, so it comes back over one it opened by itself. */
    var comesBackByItself = true

    override fun comesBackByItself() = comesBackByItself

    override fun quiet(on: Boolean) {
        if (on == quiet) return
        quiet = on
        quiets += on
        onQuiet(on)
    }

    /** The app each control other than play, pause and seek was sent to, null for any: skips, shuffle, repeat, like and a queued song. */
    val controlledApps: MutableList<String?> = CopyOnWriteArrayList()

    override fun skip(song: MusicMetadata, next: Boolean, app: String?) {
        skips += next
        controlledApps += app
    }

    override fun shuffle(song: MusicMetadata, on: Boolean, app: String?) {
        shuffled += on
        controlledApps += app
    }

    override fun repeat(song: MusicMetadata, how: Repeat, app: String?) {
        repeated += how
        controlledApps += app
    }

    override fun like(song: MusicMetadata, app: String?) {
        liked++
        controlledApps += app
    }

    override fun playQueued(song: MusicMetadata, id: Long, app: String?) {
        queuedPlayed += id
        controlledApps += app
    }

    private val inApps = mutableMapOf<String, MutableStateFlow<AppPlays?>>()

    /** What [app] plays, as a test has it. */
    fun inApp(app: String): MutableStateFlow<AppPlays?> = inApps.getOrPut(app) { MutableStateFlow(null) }

    override fun plays(app: String): Flow<AppPlays?> = inApp(app)

    /** Each list an app was asked to play, and its link. */
    val listsAsked: MutableList<Pair<String, String>> = CopyOnWriteArrayList()

    override suspend fun playList(app: String, link: String, mustStart: Boolean): Boolean {
        if (!canPlaySongs) return false
        if (mustStart && !startsInBackground) return false.also { onlyInFront = true }
        listsAsked += app to link
        return true
    }
}
