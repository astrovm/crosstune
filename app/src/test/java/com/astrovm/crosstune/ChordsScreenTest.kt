package com.astrovm.crosstune

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** A song's chords over its words, drawn with real text. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ChordsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val song = MusicMetadata("River Road", "The Walkers")

    private val lines = listOf(
        LyricLine(1_000, "Walking down the river road", listOf(TimedWord(1_000, "Walking "), TimedWord(1_600, "down "), TimedWord(2_000, "the "), TimedWord(2_300, "river "), TimedWord(2_900, "road"))),
        LyricLine(5_000, ""),
        LyricLine(9_000, "Singing to the morning light, singing all the way back home again tonight")
    )

    private val sheet = ChordSheet(
        listOf(
            SheetLine("", listOf(Chord(0, "G"), Chord(3, "D"))),
            SheetLine(lines[0].text, listOf(Chord(0, "G"), Chord(8, "C"), Chord(16, "Em"), Chord(27, "D"), Chord(27, "D7"))),
            SheetLine("", listOf(Chord(0, "Am"), Chord(3, "F"))),
            // Crowded at the end of a line that wraps: they keep apart and inside the screen.
            SheetLine(lines[2].text, listOf(Chord(0, "F"), Chord(70, "Bbmaj7"), Chord(71, "C#m7-5"), Chord(72, "Gsus4"), Chord(72, "A/C#")))
        ),
        capo = 2,
        site = ChordSite.ULTIMATE_GUITAR,
        url = "https://tabs.ultimate-guitar.com/tab/river-1"
    )

    /** The chords drawn over [line], once they're worked out. */
    private fun chordsOver(line: String): List<String>? {
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText(line, useUnmergedTree = true).fetchSemanticsNodes().any { it.config.contains(ChordsShown) } }
        return composeRule.onAllNodesWithText(line, useUnmergedTree = true).fetchSemanticsNodes().firstNotNullOfOrNull { it.config.getOrElseNullable(ChordsShown) { null } }
    }

    private fun state(chords: ChordsState, following: Following? = null) = UiState(
        lyricsFor = song,
        lyrics = lines.joinToString("\n") { it.text },
        lyricLines = lines,
        following = following,
        canFollowApps = true,
        chords = chords
    )

    private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

    @Test
    fun chordsShowOverTheWordsWithTheCapo() {
        // Paused partway into the first line, sung word by word: its chord being played lights.
        val following = Following(PlaybackClock(1_700, SystemClock.elapsedRealtime(), playing = false), "Spotify", canSeek = true)
        val chords = ChordsState(on = true, sheet = sheet)
        composeRule.setContent { CrosstuneTheme { LyricsScreen(state(chords, following), ScreenActions()) } }
        assertEquals(listOf("G", "C", "Em", "D", "D7"), chordsOver("Walking down the river road"))
        // Those played before a line, and in a gap, on rows of their own.
        composeRule.onNodeWithText("G   D", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Am   F", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText(string(R.string.lyrics_capo, 2)).assertExists()
        // Where they're from isn't the song's business.
        composeRule.onNodeWithText("Ultimate Guitar").assertDoesNotExist()

        val image = composeRule.onRoot().captureToImage().asAndroidBitmap()
        System.getenv("CHORDS_SCREENSHOT")?.let { path -> File(path).outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        assertTrue(image.width > 0)
    }

    @Test
    fun readingsAndChordsShowTogetherOverTheWords() {
        val text = "夜空の星を見上げて君を想う夜"
        val parts = listOf(Ruby("夜空", "よぞら"), Ruby("の"), Ruby("星", "ほし"), Ruby("を見上げて君を想う夜"))
        val line = LyricLine(1_000, text)
        val chords = ChordsState(on = true, sheet = ChordSheet(listOf(SheetLine(text, listOf(Chord(0, "Am"), Chord(3, "F")))), capo = 0, site = ChordSite.ULTIMATE_GUITAR, url = "https://tabs.ultimate-guitar.com/tab/x-1"))
        val learning = Learning(readings = true, script = Script.JAPANESE, lines = listOf(LineReading(parts, "よぞらのほしをみあげてきみをおもうよる", "yozora no hoshi")))
        val state = UiState(lyricsFor = song, lyrics = text, lyricLines = listOf(line), chords = chords, learning = learning)
        composeRule.setContent { CrosstuneTheme { LyricsScreen(state, ScreenActions()) } }
        assertEquals(listOf("Am", "F"), chordsOver(text))
        val readings = composeRule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().firstNotNullOfOrNull { it.config.getOrElseNullable(ReadingsShown) { null } }
        assertEquals(listOf("よぞら", "ほし"), readings)
        // Drawn: the readings are painted over the words, not only told to screen readers.
        assertTrue(composeRule.onRoot().captureToImage().asAndroidBitmap().width > 0)
    }

    @Test
    fun readingsAndChordsKeepLightingTheWordsAsTheyAreSung() {
        val text = "夜空の星を見上げて君を想う夜"
        val parts = listOf(Ruby("夜空", "よぞら"), Ruby("の"), Ruby("星", "ほし"), Ruby("を見上げて君を想う夜"))
        val line = LyricLine(1_000, text, listOf(TimedWord(1_000, "夜空の"), TimedWord(2_000, "星を見上げて君を想う夜")))
        val chords = ChordsState(on = true, sheet = ChordSheet(listOf(SheetLine(text, listOf(Chord(0, "Am"), Chord(3, "F")))), capo = 0, site = ChordSite.ULTIMATE_GUITAR, url = "https://tabs.ultimate-guitar.com/tab/x-1"))
        val learning = Learning(readings = true, script = Script.JAPANESE, lines = listOf(LineReading(parts, "よぞらのほしをみあげてきみをおもうよる", "yozora no hoshi")))
        val following = Following(PlaybackClock(1_500, SystemClock.elapsedRealtime(), playing = false), "Spotify", canSeek = true)
        val state = UiState(lyricsFor = song, lyrics = text, lyricLines = listOf(line), following = following, canFollowApps = true, chords = chords, learning = learning)
        composeRule.setContent { CrosstuneTheme { LyricsScreen(state, ScreenActions()) } }
        assertEquals(listOf("Am", "F"), chordsOver(text))
        val readings = composeRule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().firstNotNullOfOrNull { it.config.getOrElseNullable(ReadingsShown) { null } }
        assertEquals(listOf("よぞら", "ほし"), readings)
    }

    @Test
    fun switchingChordsOnShowsTheSheetFoundAfterASearchFailure() {
        val fake = FakeSpotify()
        fake.handler = { request ->
            val body = when {
                request.url.queryParameter("exp") == "River Road The Walkers" -> null
                request.url.encodedPath == "/busca.php" -> """<a href="/walkers/">The Walkers</a><li id='r000' lcd='R-1'><a href="javascript:">River Road</a></li><script>var fns=['river_road'];var NMAX=0;</script>"""
                request.url.encodedPath == "/walkers/river_road.shtml" -> "<div id=t_body><pre><A>G</A>       <A>C</A>\n${lines[0].text}\n<A>F</A>\n${lines[2].text}</pre>"
                else -> ""
            }
            FakeSpotify.html(request, body.orEmpty(), code = if (body == null) 500 else 200)
        }
        val finder = ChordFinder(fake.client())
        var chords by mutableStateOf(ChordsState())
        val actions = ScreenActions(onChordsChange = { on ->
            val answer = if (on) runBlocking { finder.chordsOf(song, lines.map { it.text }) } else null
            chords = ChordsState(on = on, tried = on, sheet = (answer as? ChordFinder.Chords.Found)?.sheet)
        })
        composeRule.setContent { CrosstuneTheme { LyricsScreen(state(chords), actions) } }
        composeRule.onNode(SemanticsMatcher.keyIsDefined(ChordsShown), useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(string(R.string.lyrics_learn)).performClick()
        composeRule.onNodeWithText(string(R.string.lyrics_chords)).performClick()
        assertEquals(listOf("G", "C"), chordsOver(lines[0].text))
        assertEquals(listOf("F"), chordsOver(lines[2].text))
        assertEquals(2, fake.requestedUrls.count { "/busca.php" in it })
        composeRule.onNodeWithText(string(R.string.lyrics_chords)).performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(ChordsShown), useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun chordsForOtherWordsDontShow() {
        val chords = ChordsState(on = true, sheet = sheet.copy(lines = listOf(SheetLine("Nothing like these words at all, honestly", listOf(Chord(0, "G"))))))
        composeRule.setContent { CrosstuneTheme { LyricsScreen(state(chords), ScreenActions()) } }
        composeRule.waitForIdle()
        Thread.sleep(200)
        composeRule.waitForIdle()
        composeRule.onNode(SemanticsMatcher.keyIsDefined(ChordsShown), useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText("G   D", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun lookingUpOrFindingNoneShowsNothing() {
        var chords by mutableStateOf(ChordsState(on = true, loading = true))
        composeRule.setContent { CrosstuneTheme { LyricsScreen(state(chords), ScreenActions()) } }
        composeRule.waitForIdle()
        val before = composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes().size
        chords = ChordsState(on = true, tried = true)
        composeRule.waitForIdle()
        assertEquals(before, composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes().size)
        composeRule.onNode(SemanticsMatcher.keyIsDefined(ChordsShown), useUnmergedTree = true).assertDoesNotExist()
        // Without a capo, nothing over the words but the chords.
        chords = ChordsState(on = true, tried = true, sheet = sheet.copy(capo = 0))
        assertEquals(listOf("F", "Bbmaj7", "C#m7-5", "Gsus4", "A/C#"), chordsOver(lines[2].text))
        composeRule.onNodeWithText(string(R.string.lyrics_capo, 0)).assertDoesNotExist()
        // A capo shows, and goes with the chords switched off.
        chords = chords.copy(sheet = sheet)
        composeRule.onNodeWithText(string(R.string.lyrics_capo, 2)).assertExists()
        chords = chords.copy(on = false)
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(ChordsShown), useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText(string(R.string.lyrics_capo, 2)).fetchSemanticsNodes().isEmpty() }
    }
}
