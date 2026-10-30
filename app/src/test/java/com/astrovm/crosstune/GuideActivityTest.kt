package com.astrovm.crosstune

import android.content.Context
import android.content.res.Configuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The floating guide when what it was opened for goes away, or Android moves it between small and big. */
@RunWith(RobolectricTestRunner::class)
class GuideActivityTest {

    private class Host : GuideHost {
        override val state = UiState(fixes = Fixes(steps = listOf(Need.Follow)))
        override val dark = false
        val opened = mutableListOf<Context?>()
        var backs = 0
        override fun refresh() = Unit
        override fun open(guide: Context?) {
            opened += guide
        }
        override fun back(guide: Context) {
            backs++
        }
    }

    @After
    fun tearDown() {
        GuideActivity.host = null
        NoPictureInPicture.throws = false
    }

    @Test
    fun madeBigAgainItBringsCrosstuneBackOnceAndEnteringTwiceOpensAndroidsPageOnce() {
        val host = Host().also { GuideActivity.host = it }
        val guide = Robolectric.buildActivity(GuideActivity::class.java).create().start().get()
        guide.onPictureInPictureModeChanged(true, Configuration())
        guide.onPictureInPictureModeChanged(true, Configuration())
        assertEquals(listOf<Context?>(guide), host.opened)
        assertEquals(0, host.backs)
        guide.onPictureInPictureModeChanged(false, Configuration())
        assertEquals(1, host.backs)
        assertTrue(guide.isFinishing)
        // Closing already, there's nothing to bring back twice.
        guide.onPictureInPictureModeChanged(false, Configuration())
        assertEquals(1, host.backs)
    }

    @Test
    fun withCrosstuneGoneTheGuideJustCloses() {
        GuideActivity.host = Host()
        val controller = Robolectric.buildActivity(GuideActivity::class.java).create().start()
        val guide = controller.get()
        GuideActivity.host = null
        guide.onPictureInPictureModeChanged(true, Configuration())
        assertFalse(guide.isFinishing)
        guide.onPictureInPictureModeChanged(false, Configuration())
        assertTrue(guide.isFinishing)
    }

    @Test
    @Config(shadows = [NoPictureInPicture::class])
    fun withCrosstuneGoneAndNoPictureInPictureTheGuideJustCloses() {
        GuideActivity.host = Host()
        val controller = Robolectric.buildActivity(GuideActivity::class.java).create().start()
        GuideActivity.host = null
        controller.resume()
        assertTrue(controller.get().isFinishing)
    }
}
