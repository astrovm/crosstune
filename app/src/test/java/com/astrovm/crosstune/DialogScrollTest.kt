package com.astrovm.crosstune

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A dialog's text where it has the least room: a long language, the largest font and a small screen. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-rDE-w320dp-h480dp")
class DialogScrollTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun theLastLineOfAReportCanBeReachedAtTheLargestFontOnASmallScreen() {
        RuntimeEnvironment.setFontScale(2f)
        rule.setContent {
            CrosstuneTheme {
                WrongReadingDialog(Word("花", "か", "ka"), listOf("か"), ReportStatus.FAILED, onSend = {}, onDismiss = {})
            }
        }
        // Failed, it says so last, under what goes.
        val last = rule.onNodeWithText(context.getString(R.string.report_failed)).performScrollTo()
        assertEquals(last.getUnclippedBoundsInRoot().bottom.value, last.getBoundsInRoot().bottom.value, 1f)
        rule.onNodeWithText(context.getString(R.string.report_send)).assertIsDisplayed()
    }
}
