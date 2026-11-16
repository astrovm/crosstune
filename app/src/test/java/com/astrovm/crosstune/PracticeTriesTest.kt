package com.astrovm.crosstune

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Where a card's line plays, for every mix of the apps the phone has, the one songs open in, the one the line came from, whether
 * Spotify has the song, what already has it open and whether Crosstune can show over other apps, and that no card ever brings
 * a music app up. Each case is checked against the rule written out in [expected].
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

    /**
     * What a card did: where the line played, an app or the player; every app it opened, woke, asked for the song or brought to the
     * front; whether it asked anything about the song for Spotify; and whether it offered Allow.
     */
    private data class Outcome(val played: String, val broughtUp: List<String> = emptyList(), val askedSpotify: Boolean = false, val allow: Boolean = false)

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
     * The rule. Crosstune stays in front: a card never opens an app, wakes one, asks one for the song or brings one to the front,
     * and asks nothing about the song for Spotify. The line plays in the app that already has the song open and moves in it,
     * whichever that is, and otherwise in the small player. So there's never anything to Allow.
     */
    private fun expected(case: Case): Outcome = Outcome(
        when (case.plays) {
            Plays.SPOTIFY -> spotify
            Plays.DEEZER -> deezer
            Plays.NOTHING, Plays.SPOTIFY_FREE -> "player"
        }
    )

    /** Runs one card of case [index] on its own fakes and its own thread, and says what it did. */
    private fun run(index: Int, case: Case, player: FakeLinePlayer): Outcome {
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
        // The app's own session, which is also what any app has open, as Android has it.
        fun playing(app: String, canSeek: Boolean) {
            playback.playing.value = Following(PlaybackClock(9_500, System.currentTimeMillis(), 1f), "Music", canSeek = canSeek, appPackage = app, durationMs = 200_000)
            playback.appPlaying[app] = playback.playing
        }
        when (case.plays) {
            Plays.NOTHING -> Unit
            Plays.SPOTIFY -> playing(spotify, canSeek = true)
            Plays.SPOTIFY_FREE -> playing(spotify, canSeek = false)
            Plays.DEEZER -> playing(deezer, canSeek = true)
        }
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
            // The card says it's the small player's as it says it's played, a moment before the player's shown.
            val played = if (screen.value.practice?.video != null) "player" else playback.seekedApps.last().orEmpty()
            val askedSpotify = sites.requestedUrls.any { it.toHttpUrl().host in setOf("labs.api.listenbrainz.org", "open.spotify.com") }
            val broughtUp = playback.opened.map { "opened ${it.first}" } + playback.songsAsked.map { "asked ${it.second}" } +
                playback.broughtToFront.map { "brought ${it.title} to the front" } + playback.appsPlayed.map { "woke $it" } +
                List(playback.cameBack) { "came back" } + List(playback.woken) { "woke the screen" }
            return Outcome(played, broughtUp, askedSpotify, false)
        } finally {
            scope.cancel()
            dispatcher.close()
        }
    }

    @Test
    fun eachCardPlaysWhereTheRuleSaysAndBringsNoAppUp() {
        // Each case's player is a view, made here: views made on many threads at once break Android's shared cache of their settings,
        // and every test after them in this run.
        val players = cases.map { FakeLinePlayer(ApplicationProvider.getApplicationContext()) }
        val pool = Executors.newFixedThreadPool(AT_ONCE)
        try {
            val wrong = cases.mapIndexed { index, case ->
                pool.submit<String?> {
                    val want = expected(case)
                    val got = run(index, case, players[index])
                    "$case\n  want $want\n  got  $got".takeIf { got != want }
                }
            }.mapNotNull { it.get() }
            assertEquals("${wrong.size} of ${cases.size} cases:\n" + wrong.joinToString("\n"), 0, wrong.size)
        } finally {
            pool.shutdownNow()
        }
    }

    private companion object {
        /** Cases run at once: they mostly wait, so a few cores take this many. */
        const val AT_ONCE = 12
        const val SONG_LOAD_MS = 150L
        const val TRACK = "1demoAAAAAAAAAAAAAAAAA"
        const val TRACK_PAGE = """<meta property="og:title" content="Demo"/><meta property="og:description" content="Band · Album · Song · 2000"/><meta name="music:duration" content="200"/>"""
        const val VIDEO = """{"contents":[{"musicResponsiveListItemRenderer":{
            "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Demo"}]}}},
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Band • Album • 3:20"}]}}}],
            "playlistItemData":{"videoId":"demo0000000"}}}]}"""
    }
}
