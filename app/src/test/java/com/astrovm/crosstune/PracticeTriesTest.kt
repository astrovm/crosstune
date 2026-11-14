package com.astrovm.crosstune

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ApplicationProvider
import java.io.OutputStream
import java.io.PrintStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/**
 * Which apps a card tries for its line, in which order, which one plays it, and when it offers Allow, for every mix of the
 * apps the phone has, the one songs open in, the one the line came from, whether Spotify has the song, what already plays it
 * and whether Crosstune can show over other apps. Each case is checked against the rule written out in [expected].
 */
@RunWith(RobolectricTestRunner::class)
class PracticeTriesTest {
    private val spotify = MusicService.SPOTIFY.packageName
    private val apple = MusicService.APPLE_MUSIC.packageName
    private val deezer = MusicService.DEEZER.packageName
    private val tidal = MusicService.TIDAL.packageName

    /** What already has the song open when the card starts. */
    private enum class Plays { NOTHING, SPOTIFY, SPOTIFY_FREE, DEEZER }

    private data class Case(
        val apps: List<String>, val preferred: Destination, val lineApp: String?, val spotifyHasIt: Boolean, val plays: Plays, val overlay: Boolean
    )

    /** What a card did: the apps it tried, in order, the one that got to the line, or the player, and whether it offered Allow. */
    private data class Outcome(val tried: List<String>, val played: String, val allow: Boolean, val askedSpotify: Boolean = false)

    /** No app: songs open in a browser. */
    private val web = Destination.Custom("web", "Web", "https://example.com/search?q={query}")

    private val cases = buildList {
        for (apps in listOf(listOf(spotify, apple, deezer, tidal), listOf(tidal, apple, spotify, deezer), listOf(spotify)))
            for (preferred in listOf(web, Destination.Service(MusicService.SPOTIFY), Destination.Service(MusicService.TIDAL)))
                for (lineApp in listOf(null, spotify))
                    for (spotifyHasIt in listOf(false, true))
                        for (plays in Plays.entries)
                            for (overlay in listOf(false, true)) add(Case(apps, preferred, lineApp, spotifyHasIt, plays, overlay))
    }

    /**
     * The rule. Apps go best first: the one songs open in, then the rest as installed. A card tries three at most, and stops at
     * the first that gets to the line. The app songs open in, and the one the line came from, are tried whatever their services
     * say; any other only when its service has the song: Apple Music's and Deezer's do, TIDAL's doesn't. Spotify plays only its
     * own link, and opening it would cover the card for good without showing over other apps. Then it's tried only when it
     * already has the song open and can move in it, skipped when it has it and can't, and otherwise left out, which offers Allow.
     * Either way nothing is asked about the song for it.
     */
    private fun expected(case: Case): Outcome {
        val preferred = (case.preferred as? Destination.Service)?.service
        val ranked = case.apps.filter { MusicService.ofPackage(it) == preferred } + case.apps.filter { MusicService.ofPackage(it) != preferred }
        val tried = mutableListOf<String>()
        var allow = false
        for (app in ranked) {
            if (tried.size >= MAX_TRIES) break
            val known = app == case.lineApp || MusicService.ofPackage(app) == preferred
            val worth = when {
                // One that has it open and can't move in it, as on the free plan, wouldn't move once opened either.
                app == spotify && !case.overlay -> (case.plays == Plays.SPOTIFY).also { if (case.plays in setOf(Plays.NOTHING, Plays.DEEZER)) allow = true }
                app == spotify -> known || case.spotifyHasIt
                else -> known || app != tidal
            }
            if (!worth) continue
            tried += app
            val plays = (app == spotify && case.plays == Plays.SPOTIFY) || (app == deezer && case.plays == Plays.DEEZER)
            if (plays) return Outcome(tried, app, allow)
        }
        return Outcome(tried, "player", allow)
    }

    /** What each card's own thread logged, line by line: the cases run side by side, as each mostly waits for an app that won't move. */
    private val logged = ConcurrentHashMap<String, StringBuilder>()

    /** Android's log, as Robolectric writes it, kept apart for each card's thread. */
    private val byThread = PrintStream(object : OutputStream() {
        override fun write(b: Int) {
            mine().append(b.toChar())
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            mine().append(String(b, off, len, Charsets.UTF_8))
        }

        /** This thread's log. In debug mode, as with assertions on, a coroutine adds " @coroutine#1" to its thread's name. */
        private fun mine() = logged.getOrPut(Thread.currentThread().name.substringBefore(" @")) { StringBuilder() }
    }, true, "UTF-8")

    /** Runs one card of case [index] on its own fakes and its own thread, and says what it did. */
    private fun run(index: Int, case: Case): Outcome {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val crashed = AtomicReference<Throwable?>()
        val thread = "card-$index"
        val dispatcher = Executors.newSingleThreadExecutor { Thread(it, thread) }.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, error -> crashed.compareAndSet(null, error) })
        val sites = FakeSpotify()
        sites.handler = { request ->
            val url = request.url
            when {
                url.host == "music.youtube.com" -> FakeSpotify.html(request, VIDEO)
                url.host == "itunes.apple.com" -> FakeSpotify.html(request, """{"results":[{"trackName":"Demo","artistName":"Band","trackViewUrl":"https://music.apple.com/us/song/demo/1"}]}""")
                url.host == "api.deezer.com" -> FakeSpotify.html(request, """{"data":[{"title":"Demo","artist":{"name":"Band"},"link":"https://www.deezer.com/track/1"}]}""")
                case.spotifyHasIt && url.host == "labs.api.listenbrainz.org" -> FakeSpotify.html(request, """[{"spotify_track_ids":["$TRACK"]}]""")
                case.spotifyHasIt && url.encodedPath == "/track/$TRACK" -> FakeSpotify.html(request, TRACK_PAGE)
                else -> FakeSpotify.html(request, "{}", code = 404)
            }
        }
        val playback = FakePlayback().apply {
            obeys = true
            access = true
            comesBackByItself = case.overlay
        }
        fun playing(app: String, canSeek: Boolean) {
            playback.appPlaying[app] = MutableStateFlow(Following(PlaybackClock(9_500, System.currentTimeMillis(), 1f), "Music", canSeek = canSeek, appPackage = app, durationMs = 200_000))
        }
        when (case.plays) {
            Plays.NOTHING -> Unit
            Plays.SPOTIFY -> playing(spotify, canSeek = true)
            Plays.SPOTIFY_FREE -> playing(spotify, canSeek = false)
            Plays.DEEZER -> playing(deezer, canSeek = true)
        }
        val player = FakeLinePlayer(context)
        val screen = mutableStateOf(UiState(musicApps = case.apps, defaultDestination = case.preferred))
        val store = SavedLinesStore(context.getSharedPreferences("practice-tries-$index", Context.MODE_PRIVATE).also { it.edit().clear().commit() })
        val traits = AppTraitsStore(context.getSharedPreferences("practice-tries-traits-$index", Context.MODE_PRIVATE).also { it.edit().clear().commit() }) { 1_000_000L }
        val line = SavedLine("Night sky", translation = "Cielo", title = "Demo", artist = "Band", startMs = 10_000, endMs = 15_000, app = case.lineApp, cardType = READ_TYPE)
        screen.value = screen.value.copy(savedLines = store.addAll(listOf(line)), cardTypes = DEFAULT_CARD_TYPES)
        val cards = PracticeCards(
            screen, scope, playback, ExactMatcher(sites.client(), "AR"), store, traits, { 1_000_000L }, { System.currentTimeMillis() },
            songLoadMs = SONG_LOAD_MS, newPlayer = { player }
        )
        try {
            cards.start(null, before = null)
            val deadline = System.currentTimeMillis() + 60_000
            while (screen.value.practice?.sound != LineSound.PLAYED && player.shown.isEmpty()) {
                crashed.get()?.let { throw AssertionError("The card's work failed for $case", it) }
                check(screen.value.practice?.sound != LineSound.NONE) { "No sound for $case" }
                check(System.currentTimeMillis() < deadline) { "Never ready: $case" }
                Thread.sleep(10)
            }
            val steps = logged[thread]?.toString().orEmpty().lines().mapNotNull { it.substringAfter("/$CHAIN_LOG: ", "").ifEmpty { null } }
            // The card says it's the small player's as it says it's played, a moment before the player's shown.
            val played = if (screen.value.practice?.video != null) "player" else playback.seekedApps.last().orEmpty()
            val askedSpotify = sites.requestedUrls.any { it.toHttpUrl().host in setOf("labs.api.listenbrainz.org", "open.spotify.com") }
            return Outcome(steps.filter { it.startsWith("trying ") }.map { it.removePrefix("trying ") }, played, screen.value.practice?.cantComeBack == true, askedSpotify)
        } finally {
            scope.cancel()
            dispatcher.close()
        }
    }

    @Test
    fun eachCardTriesTheAppsTheRuleSaysAndOffersAllowWheneverItLeftSpotifyOutForWantOfIt() {
        ShadowLog.setLoggable(CHAIN_LOG, Log.DEBUG)
        val before = ShadowLog.stream
        ShadowLog.stream = byThread
        val pool = Executors.newFixedThreadPool(AT_ONCE)
        try {
            val wrong = cases.mapIndexed { index, case ->
                pool.submit<String?> {
                    val want = expected(case)
                    // Without showing over other apps nothing is asked about the song for Spotify; with it, only for a Spotify it opens.
                    val got = run(index, case).let { if (case.overlay) it.copy(askedSpotify = false) else it }
                    "$case\n  want $want\n  got  $got".takeIf { got != want }
                }
            }.mapNotNull { it.get() }
            assertEquals("${wrong.size} of ${cases.size} cases:\n" + wrong.joinToString("\n"), 0, wrong.size)
        } finally {
            pool.shutdownNow()
            ShadowLog.stream = before
        }
    }

    private companion object {
        const val MAX_TRIES = 3
        /** Cases run at once: they mostly wait, so a few cores take this many. */
        const val AT_ONCE = 12
        const val SONG_LOAD_MS = 150L
        const val CHAIN_LOG = "CrosstuneChain"
        const val TRACK = "1demoAAAAAAAAAAAAAAAAA"
        const val TRACK_PAGE = """<meta property="og:title" content="Demo"/><meta property="og:description" content="Band · Album · Song · 2000"/><meta name="music:duration" content="200"/>"""
        const val VIDEO = """{"contents":[{"musicResponsiveListItemRenderer":{
            "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Demo"}]}}},
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Band • Album • 3:20"}]}}}],
            "playlistItemData":{"videoId":"demo0000000"}}}]}"""
    }
}
