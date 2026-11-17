package com.astrovm.crosstune

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/** Settings → About → Licenses: every work by others in the app, with who made it and its licence. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2000dp")
class LicensesTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val fake = FakeSpotify()
    private var controller: ActivityController<MainActivity>? = null

    @Before
    fun setUp() {
        MainActivity.httpClientFactory = { fake.client() }
        MainActivity.systemDispatcher = Dispatchers.Unconfined
        MainActivity.lookupDispatcher = Dispatchers.Unconfined
        MainActivity.checksForUpdates = { false }
        app.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear().putBoolean("setup_complete", true).commit()
    }

    /** Each licence read and split, by its work, with the thread that did it. */
    private val splits = Collections.synchronizedList(mutableListOf<Pair<String, Thread>>())
    private val split = LicenseCache.split
    private val open = Notices.open
    private val background = Executors.newSingleThreadExecutor { Thread(it, "licence-split") }

    /** Splits on a thread of their own, as on a phone, and counts each read and split. */
    private fun splitsCounted() {
        MainActivity.systemDispatcher = background.asCoroutineDispatcher()
        LicenseCache.split = { notice -> splits += notice.name to Thread.currentThread(); split(notice) }
    }

    /** Waits for [words], drawn once a licence's split comes back from its thread. */
    private fun waitForText(words: String) {
        composeRule.waitUntil(10_000) {
            runCatching { scrollTo(hasText(words, substring = true)) }.isSuccess && shown(words)
        }
    }

    private fun splitWorks() = synchronized(splits) { splits.map { it.first } }

    private fun splitOnTheMainThread() = synchronized(splits) { splits.filter { it.second == Looper.getMainLooper().thread }.map { it.first } }

    @After
    fun tearDown() {
        LicenseCache.split = split
        Notices.open = open
        controller?.destroyAndSettle()
        // A read a test held, then let go, may not have woken yet: it ends on its own. Interrupted, it would
        // throw into the page's coroutine, and Compose would fail the test with it.
        background.shutdown()
        assertTrue("A licence read still runs after the test", background.awaitTermination(10, TimeUnit.SECONDS))
        MainActivity.httpClientFactory = ::httpClient
        MainActivity.systemDispatcher = Dispatchers.Default
        MainActivity.lookupDispatcher = Dispatchers.IO
        MainActivity.checksForUpdates = ::looksForUpdates
    }

    private fun cacheOwner() = ViewModelProvider(checkNotNull(controller).get())[LicenseCacheOwner::class.java]

    private fun pageCache() = checkNotNull(cacheOwner().cache)

    private fun string(id: Int): String = app.getString(id)

    private fun shown(text: String) = composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    /** Opens Settings, then About's Licenses. */
    private fun openLicenses() {
        controller = Robolectric.buildActivity(MainActivity::class.java, Intent(app, MainActivity::class.java)).setup()
        composeRule.waitForIdle()
        composeRule.onNode(hasContentDescription(string(R.string.settings_button))).performClick()
        composeRule.waitForIdle()
        showLicenses()
    }

    private fun showLicenses() {
        composeRule.onNode(hasText(string(R.string.settings_licenses)) and hasClickAction()).performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    /** The list draws only what's on screen: scrolled to [matcher] first, so it's there to find. */
    private fun scrollTo(matcher: SemanticsMatcher) {
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(matcher)
        composeRule.waitForIdle()
    }

    private fun work(name: String) = (hasText(name) and hasClickAction()).let {
        // Notices load off the main thread. Compose can be idle while the list is still empty.
        waitForText(name)
        scrollTo(it)
        composeRule.onNode(it)
    }

    private fun text(words: String) = scrollTo(hasText(words, substring = true)).let { composeRule.onAllNodesWithText(words, substring = true).onFirst() }

    @Test
    fun aboutOpensTheLicensesWithJmdictsAcknowledgementAndEveryWork() {
        openLicenses()
        // JMdict's acknowledgement, as its licence asks of an app: who made it, its licence, and that it was changed.
        work("JMdict")
        assertTrue(shown("Electronic Dictionary Research and Development Group · CC BY-SA 4.0"))
        assertTrue(shown("word lists made from JMdict are changed"))
        assertTrue(shown("https://www.edrdg.org/edrdg/licence.html"))
        // Opened, its licence, CC BY-SA's and JMdict's documentation, as the licence asks.
        work("JMdict").performClick()
        composeRule.waitForIdle()
        text("ELECTRONIC DICTIONARY RESEARCH AND DEVELOPMENT GROUP").assertExists()
        text("Attribution-ShareAlike 4.0 International").assertExists()
        text("JMdict-EDICT Dictionary Project").assertExists()
        work("JMdict").performClick()
        composeRule.waitForIdle()
        // The works the app is built from, by name, each with a row of its own.
        val names = listOf(
            "Kuromoji", "IPADIC", "UniDic", "projectM", "whisper.cpp and ggml", "Whisper", "MilkDrop presets",
            "MilkDrop textures", "Shizuku API", "OkHttp and Okio", "Guava", "AndroidX, Jetpack Compose and Media3",
            "Kotlin and kotlinx.coroutines", "Reorderable"
        )
        (names + Notices.all.map { it.name }).distinct().forEach { work(it).assertExists() }
    }

    @Test
    @Config(qualifiers = "+es")
    fun inSpanishCrosstunesOwnWordsAreSpanishAndTheLicencesAndTheWorksOwnNamesAsPublished() {
        openLicenses()
        // What a work is, who it's by and its licence, in Crosstune's words.
        work("Presets de MilkDrop")
        assertTrue(shown("Sus autores, nombrados en el nombre de cada preset · No indica ninguna"))
        work("JMdict")
        assertTrue(shown("Las listas de palabras que Crosstune hizo con JMdict están cambiadas"))
        assertTrue(!shown("word lists made from JMdict are changed"))
        // The licence itself, and the names works and groups give themselves, stay as they wrote them.
        assertTrue(shown("Electronic Dictionary Research and Development Group · CC BY-SA 4.0"))
        work("JMdict").performClick()
        composeRule.waitForIdle()
        text("Attribution-ShareAlike 4.0 International").assertExists()
        work("Guava").assertExists()
    }

    @Test
    @Config(qualifiers = "+es")
    fun aLanguagesWordsForTheNoticesAreReadOffTheMainThread() {
        val reads = Collections.synchronizedList(mutableListOf<Pair<String, Thread>>())
        MainActivity.systemDispatcher = background.asCoroutineDispatcher()
        Notices.open = { name -> reads += name to Thread.currentThread(); open(name) }
        openLicenses()
        waitForText("Presets de MilkDrop")
        val words = synchronized(reads) { reads.filter { it.first == "notices-es.txt" } }
        assertEquals(listOf("notices-es.txt"), words.map { it.first })
        assertTrue("notices-es.txt was read on the main thread", words.none { it.second == Looper.getMainLooper().thread })
    }

    @Test
    @Config(qualifiers = "+es")
    fun aLanguagesWordsAreKeptWhileThePageShowsAndLetGoOnceItIsLeft() {
        val reads = Collections.synchronizedList(mutableListOf<String>())
        Notices.open = { name -> reads += name; open(name) }
        openLicenses()
        controller = checkNotNull(controller).recreate()
        composeRule.waitForIdle()
        // Turned, the page shows the words it read.
        assertEquals(listOf("notices-es.txt"), reads.filter { it == "notices-es.txt" })
        checkNotNull(controller).get().onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()
        composeRule.onNode(hasText(string(R.string.settings_licenses)) and hasClickAction()).performScrollTo().performClick()
        composeRule.waitForIdle()
        work("Presets de MilkDrop").assertExists()
        // Left, the page let go of them: open again, it reads them again.
        assertEquals(listOf("notices-es.txt", "notices-es.txt"), reads.filter { it == "notices-es.txt" })
    }

    @Test
    @Config(qualifiers = "+es")
    fun aHeldTranslationDoesNotReplaceTheWordsOnAReopenedPage() {
        val reads = AtomicInteger()
        val reading = CountDownLatch(1)
        val letGo = CountDownLatch(1)
        MainActivity.systemDispatcher = background.asCoroutineDispatcher()
        Notices.open = { name ->
            if (name == "notices-es.txt" && reads.incrementAndGet() == 1) {
                reading.countDown()
                assertTrue(letGo.await(10, TimeUnit.SECONDS))
                "MilkDrop presets\nname: Palabras de la visita anterior"
            } else {
                open(name)
            }
        }
        try {
            openLicenses()
            val closed = pageCache()
            assertTrue("The first translation never started", reading.await(5, TimeUnit.SECONDS))
            composeRule.onNode(hasContentDescription(string(R.string.back_button))).performClick()
            composeRule.waitForIdle()
            assertNull(cacheOwner().cache)

            MainActivity.systemDispatcher = Dispatchers.Unconfined
            showLicenses()
            work("Presets de MilkDrop").assertExists()
            val reopened = pageCache()
            assertNotSame(closed, reopened)
            val words = checkNotNull(reopened.wordsRead("es"))

            letGo.countDown()
            background.submit {}.get(5, TimeUnit.SECONDS)
            assertNull(closed.wordsRead("es"))
            assertSame(words, reopened.wordsRead("es"))
            work("Presets de MilkDrop").assertExists()
            assertEquals(2, reads.get())
        } finally {
            letGo.countDown()
        }
    }

    @Test
    fun backWhileTheWordsAreReadAgainAfterAndroidStoppedTheAppLeavesThePage() {
        splitsCounted()
        openLicenses()
        work("projectM").performClick()
        waitForText("END OF TERMS AND CONDITIONS")
        // Android stops the app behind another: what was read is gone, the page's state is not. The words come back slowly.
        cacheOwner().leave()
        val reading = CountDownLatch(1)
        val letGo = CountDownLatch(1)
        Notices.open = { name ->
            if (name == "notices-en.txt") {
                reading.countDown()
                letGo.await(5, TimeUnit.SECONDS)
            }
            open(name)
        }
        try {
            controller = checkNotNull(controller).recreate()
            composeRule.waitForIdle()
            assertTrue("The words were never read", reading.await(5, TimeUnit.SECONDS))
            // Nothing is shown to close yet, so Back leaves, and the main thread doesn't wait for the notices.
            checkNotNull(controller).get().onBackPressedDispatcher.onBackPressed()
            composeRule.waitForIdle()
            assertTrue("Back while the words were read didn't leave the page", shown(string(R.string.settings_about_title)))
        } finally {
            letGo.countDown()
        }
    }

    @Test
    fun aWorkOpensToItsFullLicenceAndWhereItIsWhichOpensOnlyOnATap() {
        openLicenses()
        assertTrue(!shown("GNU LESSER GENERAL PUBLIC LICENSE"))
        work("projectM").performClick()
        composeRule.waitForIdle()
        // projectM's own notice, then the LGPL's whole text.
        text("projectM -- Milkdrop-esque visualisation SDK").assertExists()
        text("GNU LESSER GENERAL PUBLIC LICENSE").assertExists()
        text("END OF TERMS AND CONDITIONS").assertExists()
        assertEquals(null, shadowOf(app).nextStartedActivity)

        scrollTo(hasText("github.com/projectM-visualizer/projectm"))
        composeRule.onNodeWithText("github.com/projectM-visualizer/projectm").performClick()
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("https://github.com/projectM-visualizer/projectm", started.dataString)

        // Tapped again, it closes.
        work("projectM").performClick()
        composeRule.waitForIdle()
        assertTrue(!shown("GNU LESSER GENERAL PUBLIC LICENSE"))
    }

    @Test
    fun theIpadicNoticeIsReproducedInFull() {
        openLicenses()
        work("IPADIC").performClick()
        composeRule.waitForIdle()
        text("Nara Institute of Science and Technology (NAIST)").assertExists()
        text("Each user will be deemed to have agreed").assertExists()
    }

    @Test
    fun backGoesToTheSettingsList() {
        openLicenses()
        checkNotNull(controller).get().onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()
        assertTrue(shown(string(R.string.settings_about_title)))
        assertTrue(!shown("Electronic Dictionary Research and Development Group"))
    }

    @Test
    fun openingAnotherWorkDoesNotSplitAnOpenOneAgain() {
        LicenseCache.split = { notice -> splits += notice.name to Thread.currentThread(); split(notice) }
        openLicenses()
        work("projectM").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("projectM"), splitWorks())
        work("JMdict").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("projectM", "JMdict"), splitWorks())
    }

    @Test
    fun openingOneMoreWorkReadsOnlyItOffTheMainThread() {
        splitsCounted()
        openLicenses()
        work("projectM").performClick()
        waitForText("GNU LESSER GENERAL PUBLIC LICENSE")
        work("JMdict").performClick()
        waitForText("Attribution-ShareAlike 4.0 International")
        work("Kuromoji").performClick()
        waitForText("Kuromoji Japanese Morphological Analyzer")
        assertEquals(listOf("projectM", "JMdict", "Kuromoji"), splitWorks())
        assertEquals(emptyList<String>(), splitOnTheMainThread())
    }

    @Test
    fun turnedWithALicenceOpenThePageReadsNoTextAgain() {
        splitsCounted()
        openLicenses()
        work("projectM").performClick()
        waitForText("END OF TERMS AND CONDITIONS")
        val piece = composeRule.onAllNodesWithText("END OF TERMS AND CONDITIONS", substring = true).onFirst().fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }

        controller = checkNotNull(controller).recreate()
        composeRule.waitForIdle()

        // Still open, where it was, from the pieces split before it turned.
        composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(AnnotatedString(piece)))).assertIsDisplayed()
        assertEquals(listOf("projectM"), splitWorks())
        assertEquals(emptyList<String>(), splitOnTheMainThread())
    }

    @Test
    fun turningDuringTheLicensesExitReleasesTheCacheAndKeepsTheDepartingText() {
        openLicenses()
        work("projectM").performClick()
        val piece = text("GNU LESSER GENERAL PUBLIC LICENSE")
        piece.assertIsDisplayed()
        val cache = pageCache()
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.onNode(hasContentDescription(string(R.string.back_button))).performClick()
            composeRule.mainClock.advanceTimeByFrame()
            piece.assertIsDisplayed()
            assertNull("The outgoing Licenses frame still owns the cache", cacheOwner().cache)
            controller = checkNotNull(controller).recreate()
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        composeRule.waitForIdle()
        assertTrue(shown(string(R.string.settings_about_title)))
        assertNull("The restored Settings list kept the Licenses owner", cacheOwner().cache)
        assertNull(cache.pieces("projectM"))
        assertNull(cache.wordsRead("en"))
    }

    @Test
    fun reopeningLicensesDuringItsExitKeepsThePlaceInAnOpenLicence() {
        openLicenses()
        work("JMdict").performClick()
        val piece = text("An early technical report from 1993")
        piece.assertIsDisplayed()
        fun position() = composeRule.onNode(hasScrollToNodeAction()).fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        val before = position()
        val closed = pageCache()

        val reading = CountDownLatch(1)
        val letGo = CountDownLatch(1)
        splitsCounted()
        val countedSplit = LicenseCache.split
        LicenseCache.split = { notice ->
            reading.countDown()
            assertTrue(letGo.await(10, TimeUnit.SECONDS))
            countedSplit(notice)
        }
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.onNode(hasContentDescription(string(R.string.back_button))).performClick()
            composeRule.mainClock.advanceTimeBy(144)
            piece.assertIsDisplayed()
            assertNull(cacheOwner().cache)
            val reopen = composeRule.onNode(hasText(string(R.string.settings_licenses)) and hasClickAction())
            reopen.assertIsDisplayed().performClick()
            composeRule.mainClock.autoAdvance = true
            composeRule.waitForIdle()
            assertTrue("The reopened page did not split its open licence", reading.await(5, TimeUnit.SECONDS))
            composeRule.waitForIdle()
            val reopened = pageCache()
            assertNotSame(closed, reopened)
            assertNull(reopened.pieces("JMdict"))
        } finally {
            composeRule.mainClock.autoAdvance = true
            letGo.countDown()
        }
        background.submit {}.get(5, TimeUnit.SECONDS)
        composeRule.waitForIdle()
        assertEquals(listOf("JMdict"), splitWorks())
        assertEquals("The licence scroll position changed", before, position(), 0.01f)
        piece.assertIsDisplayed()
    }

    @Test
    fun turningDuringAnOuterSettingsExitReleasesTheLicensesCache() {
        openLicenses()
        work("projectM").performClick()
        val piece = text("GNU LESSER GENERAL PUBLIC LICENSE")
        piece.assertIsDisplayed()
        val cache = pageCache()
        composeRule.mainClock.autoAdvance = false
        try {
            val activity = checkNotNull(controller)
            val viewModel = ViewModelProvider(activity.get())[MainViewModel::class.java]
            activity.newIntent(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "not a music link")
            })
            // Wait for the outer route to handle the request, then draw its first exit frame.
            assertTrue(viewModel.uiState.leaveSettings)
            composeRule.waitUntil(5_000) {
                composeRule.runOnUiThread { Snapshot.sendApplyNotifications() }
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.waitForIdle()
                !viewModel.uiState.leaveSettings
            }
            composeRule.runOnUiThread { Snapshot.sendApplyNotifications() }
            composeRule.mainClock.advanceTimeByFrame()
            piece.assertIsDisplayed()
            assertNull("The outgoing Settings frame still owns the cache", cacheOwner().cache)
            controller = activity.recreate()
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        composeRule.waitForIdle()
        assertTrue(shown(string(R.string.error_invalid_url)))
        assertNull("The restored main screen kept the Licenses owner", cacheOwner().cache)
        assertNull(cache.pieces("projectM"))
        assertNull(cache.wordsRead("en"))
    }

    @Test
    fun aRestoredPageDrawsPiecesThatArriveBeforeItsSplitEffectChecksTheCache() {
        openLicenses()
        work("projectM").performClick()
        text("GNU LESSER GENERAL PUBLIC LICENSE").assertIsDisplayed()

        // Restore the expanded row as another page's split of that work is about to finish.
        val owner = cacheOwner()
        owner.leave()
        owner.enter()
        val cache = pageCache()
        val notice = Notices.all.first { it.name == "projectM" }
        val pieces = split(notice)
        val reading = CountDownLatch(1)
        val letGo = CountDownLatch(1)
        val reads = AtomicInteger()
        LicenseCache.split = {
            reads.incrementAndGet()
            reading.countDown()
            assertTrue(letGo.await(10, TimeUnit.SECONDS))
            pieces
        }
        val sharedSplit = background.submit { cache.make(notice) }
        try {
            assertTrue("The shared split never started", reading.await(5, TimeUnit.SECONDS))
            Notices.open = { name ->
                // The page has read its initial units when its words effect starts. Finish the split
                // before the next effect checks whether those units still need to be made.
                if (name == "notices-en.txt") {
                    assertNull(cache.pieces("projectM"))
                    letGo.countDown()
                    sharedSplit.get(5, TimeUnit.SECONDS)
                }
                open(name)
            }
            controller = checkNotNull(controller).recreate()
            composeRule.waitForIdle()
            assertSame(pieces, cache.pieces("projectM"))
            assertEquals(1, reads.get())
            text("GNU LESSER GENERAL PUBLIC LICENSE").assertIsDisplayed()
        } finally {
            letGo.countDown()
        }
    }

    @Test
    fun madeAgainAfterAndroidStoppedTheAppAnOpenLicenceIsReadOffTheMainThreadAndShownWhereItWas() {
        splitsCounted()
        openLicenses()
        work("projectM").performClick()
        waitForText("END OF TERMS AND CONDITIONS")
        val piece = composeRule.onAllNodesWithText("END OF TERMS AND CONDITIONS", substring = true).onFirst().fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }

        // Android stops the app behind another and starts it again: what was split is gone, the page's state is not.
        cacheOwner().leave()
        controller = checkNotNull(controller).recreate()
        val shownAgain = SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(AnnotatedString(piece)))
        composeRule.waitUntil(10_000) { composeRule.onAllNodes(shownAgain).fetchSemanticsNodes().isNotEmpty() }

        composeRule.onNode(shownAgain).assertIsDisplayed()
        assertEquals(listOf("projectM", "projectM"), splitWorks())
        assertEquals(emptyList<String>(), splitOnTheMainThread())
    }

    @Test
    fun aLicenceSplitForThePageIsLetGoOnceThePageIsLeft() {
        openLicenses()
        work("projectM").performClick()
        composeRule.waitForIdle()
        val cache = pageCache()
        assertNotNull(cache.pieces("projectM"))
        // Once to close the licence, once to leave the page.
        repeat(2) {
            checkNotNull(controller).get().onBackPressedDispatcher.onBackPressed()
            composeRule.waitForIdle()
        }
        assertTrue(shown(string(R.string.settings_about_title)))
        assertNull(cache.pieces("projectM"))
    }

    @Test
    fun leavingTheLastPageDropsTheHeldSplitAndSkipsTheNextWorkInItsBatch() {
        openLicenses()
        work("projectM").performClick()
        work("JMdict").performClick()
        composeRule.waitForIdle()

        // Restore both open works without their cached pieces. Hold the first split in the batch.
        cacheOwner().leave()
        splitsCounted()
        val reading = CountDownLatch(1)
        val letGo = CountDownLatch(1)
        val first = Notices.all.first { it.name == "projectM" || it.name == "JMdict" }.name
        val countedSplit = LicenseCache.split
        LicenseCache.split = { notice ->
            if (notice.name == first) {
                reading.countDown()
                assertTrue(letGo.await(10, TimeUnit.SECONDS))
            }
            countedSplit(notice)
        }
        val cache: LicenseCache
        try {
            controller = checkNotNull(controller).recreate()
            composeRule.waitForIdle()
            cache = pageCache()
            assertTrue("The first split never started", reading.await(5, TimeUnit.SECONDS))
            composeRule.onNode(hasContentDescription(string(R.string.back_button))).performClick()
            composeRule.waitForIdle()
            assertTrue(shown(string(R.string.settings_about_title)))
        } finally {
            letGo.countDown()
        }
        background.submit {}.get(5, TimeUnit.SECONDS)
        assertNull(cache.pieces("projectM"))
        assertNull(cache.pieces("JMdict"))
        assertEquals(listOf(first), splitWorks())
    }

    @Test
    fun anotherActivityLeavingKeepsTheOpenPagesCachesUntilItsOwnerFinishes() {
        splitsCounted()
        openLicenses()
        work("JMdict").performClick()
        waitForText("Attribution-ShareAlike 4.0 International")
        val first = checkNotNull(controller)
        val cache = pageCache()
        val pieces = checkNotNull(cache.pieces("JMdict"))
        val words = checkNotNull(cache.wordsRead("en"))
        first.pause().stop()
        controller = null
        try {
            openLicenses()
            assertSame(cache, pageCache())
            checkNotNull(controller).destroyAndSettle()
            controller = null
            assertTrue(pieces === cache.pieces("JMdict"))
            assertTrue(words === cache.wordsRead("en"))
            first.start().resume()
            controller = first.recreate()
            composeRule.waitForIdle()
            assertSame(cache, pageCache())
            assertTrue(pieces === cache.pieces("JMdict"))
            assertTrue(words === cache.wordsRead("en"))
            work("JMdict").performClick()
            work("JMdict").performClick()
            waitForText("Attribution-ShareAlike 4.0 International")
            assertEquals(listOf("JMdict"), splitWorks())
            checkNotNull(controller).destroyAndSettle()
            controller = null
            assertNull(cache.pieces("JMdict"))
            assertNull(cache.wordsRead("en"))
        } finally {
            runCatching { first.destroyAndSettle() }
        }
    }

    /** A phone, as a TV: Back from inside a long licence closes it and returns to its row, and the next Back leaves. */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun backFromInsideALongLicenceClosesItAndASecondBackLeavesThePage() {
        openLicenses()
        composeRule.onRoot().performKeyInput { pressKey(Key.ShiftLeft) }
        val row = hasText("projectM") and hasClickAction()
        scrollTo(row)
        composeRule.onNode(row).requestFocus()
        composeRule.onNode(row).performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()
        repeat(6) {
            composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Focused, true)).performKeyInput { pressKey(Key.DirectionDown) }
            composeRule.waitForIdle()
        }
        checkNotNull(controller).get().onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithText(string(R.string.settings_about_title)).fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("GNU LESSER GENERAL PUBLIC LICENSE").fetchSemanticsNodes().isEmpty())
        composeRule.onNode(row).assertIsFocused()

        checkNotNull(controller).get().onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithText(string(R.string.settings_about_title)).fetchSemanticsNodes().isNotEmpty())
        composeRule.onNode(hasText(string(R.string.settings_licenses)) and hasClickAction()).assertIsFocused()
    }
}
