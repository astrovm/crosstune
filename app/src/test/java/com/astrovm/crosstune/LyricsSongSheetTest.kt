package com.astrovm.crosstune

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** The sheet about the whole song: what it means, its words to know, its artist, and what to ask. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LyricsSongSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val song = MusicMetadata("River Road", "The Walkers")
    private val signedIn = ChatGptState(email = "fan@example.com")
    private val calls = mutableListOf<String>()
    private val waiting = hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)

    private val actions = ScreenActions(
        onExplainSong = { calls += "explain" },
        onFindWordsToKnow = { calls += "words" },
        onAboutArtist = { calls += "artist" },
        onAskAboutSong = { calls += "ask $it" },
        onToggleSavedWord = { calls += "save ${it.word}" },
        onSongsLike = { calls += "like ${it.title}" },
        onOpenNamedSong = { title, artist -> calls += "open $title by $artist" }
    )

    private fun open(learning: Learning = Learning(), chatGpt: ChatGptState = signedIn, saved: List<SavedLine> = emptyList(), tab: String? = null) {
        val state = UiState(
            lyricsFor = song, lyrics = "Walking down", lyricLines = listOf(LyricLine(1_000, "Walking down")),
            learning = learning, chatGpt = chatGpt, savedLines = saved
        )
        compose.setContent { CrosstuneTheme { LyricsScreen(state, actions) } }
        compose.onNodeWithContentDescription("About the song").performClick()
        tab?.let { compose.onNodeWithText(it).performClick() }
        compose.waitForIdle()
    }

    /** [text] is in the header, and the sheet over it as well [times] in all. */
    private fun assertShown(text: String, times: Int) = assertEquals(times, compose.onAllNodesWithText(text).fetchSemanticsNodes().size)

    @Test
    fun withoutChatGptTheSheetOffersToSignInAndAsksNothing() {
        open(chatGpt = ChatGptState())
        assertShown("River Road", times = 2)
        compose.onAllNodesWithText("Songs like this").assertCountEquals(0)
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun openingTheSheetAsksForWhatTheSongMeansAndEachTabAsksForItsOwn() {
        open()
        assertEquals(listOf("explain"), calls)
        compose.onNodeWithText("Words").performClick()
        compose.onNodeWithText("Artist").performClick()
        assertEquals(listOf("explain", "words", "artist"), calls)
    }

    @Test
    fun theTitleAndArtistAreReadAndMeantAsTheWordsAre() {
        val reading = LineReading(listOf(Ruby("川", "かわ"), Ruby("の道")), "かわのみち", "kawa no michi")
        val named = Learning(
            readings = true, romanized = true, translation = true, titleMeaning = "River Road",
            names = listOf(reading, LineReading(emptyList(), "", ""))
        )
        compose.setContent {
            CrosstuneTheme {
                LyricsScreen(UiState(lyricsFor = MusicMetadata("川の道", "歩く人"), lyrics = "x", learning = named, chatGpt = signedIn), actions)
            }
        }
        compose.onNodeWithContentDescription("About the song").performClick()
        compose.onNodeWithText("kawa no michi").assertExists()
        // The title's meaning is only in the sheet; the artist's name is there as well as in the header.
        assertShown("River Road", times = 1)
        assertShown("歩く人", times = 2)
    }

    @Test
    fun theMeaningIsShownAsItIsWrittenWithWhatItCoversAndSamplesAndQuestionsOnceDone() {
        open(
            Learning(
                songExplanation = LineExplanation(0, "A walk home.\n---\nIn Spanish, a river.", done = true),
                songFacts = SongFacts("Old Road" to "Folk Duo", listOf("Sampled Song" to "Beat Maker")),
                songQuestions = listOf(LineQuestion("Why a river?", "Because.", done = true))
            )
        )
        compose.onNodeWithText("A walk home. In Spanish, a river.", substring = true).assertExists()
        compose.onNodeWithText("Cover of").assertExists()
        compose.onNodeWithText("Samples").assertExists()
        compose.onNodeWithText("Sampled Song").performScrollTo().performClick()
        assertEquals("open Sampled Song by Beat Maker", calls.last())
        compose.onNodeWithText("Why a river?").assertExists()
        compose.onNodeWithText("Because.").assertExists()
    }

    @Test
    fun aSongNotCoveringOrSamplingAnythingShowsNeither() {
        open(Learning(songExplanation = LineExplanation(0, "A walk home.", done = true), songFacts = SongFacts(null, emptyList())))
        compose.onAllNodesWithText("Cover of").assertCountEquals(0)
        compose.onAllNodesWithText("Samples").assertCountEquals(0)
    }

    @Test
    fun aMeaningNotWrittenYetShowsProgress() {
        open(Learning(songExplanation = LineExplanation(0, "")))
        compose.onNode(waiting).assertExists()
    }

    @Test
    fun aMeaningNotAskedForYetShowsProgressToo() {
        open()
        compose.onNode(waiting).assertExists()
    }

    @Test
    fun aMeaningThatFailedCanBeAskedAgain() {
        open(Learning(songExplanation = LineExplanation(0, failed = true)))
        compose.onNodeWithText("Couldn't explain the song.").assertExists()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(listOf("explain", "explain"), calls)
    }

    @Test
    fun aUsedUpPlanSaysSoOnTheMeaningAndCanBeAskedAgain() {
        open(Learning(songExplanation = LineExplanation(0, limitReached = true)))
        compose.onNodeWithText("Your ChatGPT plan is used up for now.").assertExists()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(listOf("explain", "explain"), calls)
    }

    @Test
    fun theSongsQuestionsAreAnsweredFailedOrUsedUpAndAskedFromAField() {
        open(
            Learning(
                songExplanation = LineExplanation(0, "A walk home.", done = true),
                songQuestions = listOf(
                    LineQuestion("Done?", "Yes.", done = true),
                    LineQuestion("Broken?", failed = true),
                    LineQuestion("Out?", limitReached = true),
                    LineQuestion("Writing?")
                )
            )
        )
        compose.onNodeWithText("Yes.").assertExists()
        compose.onNodeWithText("Couldn't answer.").assertExists()
        compose.onAllNodesWithText("Your ChatGPT plan is used up for now.").assertCountEquals(1)
        // One is being written, so another waits.
        compose.onNodeWithText("Ask about the song").performTextInput("Who wrote it?")
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
    }

    @Test
    fun aQuestionTypedIsAskedFromTheFieldAndTheFieldIsEmptiedAfter() {
        open(Learning(songExplanation = LineExplanation(0, "A walk home.", done = true)))
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
        compose.onNodeWithText("Ask about the song").performTextInput("Who wrote it?")
        compose.onNodeWithContentDescription("Send").performClick()
        assertEquals("ask Who wrote it?", calls.last())
        compose.onAllNodesWithText("Who wrote it?").assertCountEquals(0)
    }

    @Test
    fun wordsFoundAreListedAndSavedOrKeptAlreadyAndASavedOneShowsSo() {
        val words = listOf(KeyWord("道", "みち", "road"), KeyWord("川", null, "river"))
        val saved = SavedLine("川", title = song.title, artist = song.artist)
        open(Learning(wordsToKnow = Asking(words)), saved = listOf(saved), tab = "Words")
        compose.onNodeWithText("みち").assertExists()
        compose.onNodeWithText("road").assertExists()
        compose.onNodeWithContentDescription("Save 道").performClick()
        assertEquals("save 道", calls.last())
        compose.onNodeWithContentDescription("川 saved").assertExists()
    }

    @Test
    fun wordsThatCouldntBeFoundCanBeLookedForAgain() {
        open(Learning(wordsToKnow = Asking<List<KeyWord>>(failed = true)), tab = "Words")
        compose.onNodeWithText("Couldn't find the words to know.").assertExists()
        compose.onNodeWithText("Try again").performClick()
        assertEquals("words", calls.last())
    }

    @Test
    fun wordsBeingLookedForShowProgress() {
        open(Learning(wordsToKnow = Asking()), tab = "Words")
        compose.onNode(waiting).assertExists()
    }

    @Test
    fun theArtistIsNamedAndWhatIsWrittenOfThemShownAsASheet() {
        val written = "Origin: Dublin\nThe Walkers play folk.\nfolk · warm · slow\n\"We walk\"\nWe go on foot.\nRiver Road · 2019"
        open(Learning(artist = LineExplanation(0, written, done = true)), tab = "Artist")
        compose.onNodeWithText("Origin").assertExists()
        compose.onNodeWithText("Dublin").assertExists()
        compose.onNodeWithText("The Walkers play folk.").assertExists()
        compose.onNodeWithText("folk").assertExists()
        compose.onNodeWithText("\"We walk\"").assertExists()
        compose.onNodeWithText("We go on foot.").assertExists()
        assertShown("River Road", times = 3)
        compose.onNodeWithText("2019").assertExists()
    }

    @Test
    fun anArtistThatFailedOrUsedUpThePlanCanBeAskedAgain() {
        open(Learning(artist = LineExplanation(0, failed = true)), tab = "Artist")
        compose.onNodeWithText("Couldn't find out about the artist.").assertExists()
        compose.onNodeWithText("Try again").performClick()
        assertEquals("artist", calls.last())
    }

    @Test
    fun anArtistNotAskedYetShowsProgress() {
        open(tab = "Artist")
        // Under the header and the song's names, the artist is named again over what's written.
        assertShown("The Walkers", times = 3)
        compose.onNode(waiting).assertExists()
    }

    @Test
    fun songsLikeThisAreAskedForFromTheSheet() {
        open()
        compose.onNodeWithText("Songs like this").performScrollTo().performClick()
        assertEquals("like River Road", calls.last())
    }

    @Test
    fun aSongOnlySamplingOthersShowsWhatItSamplesAndNotACover() {
        open(Learning(songExplanation = LineExplanation(0, "A walk home.", done = true), songFacts = SongFacts(null, listOf("Sampled Song" to "Beat Maker"))))
        compose.onAllNodesWithText("Cover of").assertCountEquals(0)
        compose.onNodeWithText("Samples").assertExists()
    }

    @Test
    fun aSongWithoutAnArtistNamedShowsOnlyItsTitle() {
        val state = UiState(lyricsFor = MusicMetadata("River Road", ""), lyrics = "x", chatGpt = signedIn)
        compose.setContent { CrosstuneTheme { LyricsScreen(state, actions) } }
        compose.onNodeWithContentDescription("About the song").performClick()
        compose.waitForIdle()
        assertShown("River Road", times = 2)
    }

    @Test
    fun theTitleInLatinLettersIsLeftOutWhenItIsTheTitleOrNothing() {
        val same = LineReading(listOf(Ruby("River Road")), "", "River Road")
        val none = LineReading(listOf(Ruby("The Walkers")), "", " ")
        open(Learning(romanized = true, names = listOf(same, none)))
        assertShown("River Road", times = 2)
        assertShown("The Walkers", times = 2)
    }

    @Test
    fun anArtistBeingWrittenShowsProgress() {
        open(Learning(artist = LineExplanation(0, "")), tab = "Artist")
        compose.onNode(waiting).assertExists()
    }

    @Test
    fun anArtistWhosePlanRanOutCanBeAskedAgain() {
        open(Learning(artist = LineExplanation(0, limitReached = true)), tab = "Artist")
        compose.onNodeWithText("Your ChatGPT plan is used up for now.").assertExists()
        compose.onNodeWithText("Try again").performClick()
        assertEquals("artist", calls.last())
    }

    @Test
    fun anArtistWithOnlyFactsShowsJustThem() {
        open(Learning(artist = LineExplanation(0, "Origin: Dublin", done = true)), tab = "Artist")
        compose.onNodeWithText("Dublin").assertExists()
    }

    @Test
    fun wordsSavedForAnotherSongOrAnotherArtistAreNotMarkedSavedHere() {
        val words = listOf(KeyWord("道", "みち", "road"))
        val saved = listOf(
            SavedLine("道", title = "Other", artist = song.artist),
            SavedLine("道", title = song.title, artist = "Other"),
            SavedLine("川", title = song.title, artist = song.artist)
        )
        open(Learning(wordsToKnow = Asking(words)), saved = saved, tab = "Words")
        compose.onNodeWithContentDescription("Save 道").assertExists()
    }

    @Test
    fun theKeyLineAndTheOtherPartsOfAnArtistAreShownOnlyWhenThereAreSome() {
        open(Learning(artist = LineExplanation(0, "\"We walk\"", done = true)), tab = "Artist")
        compose.onNodeWithText("\"We walk\"").assertExists()
    }
}
