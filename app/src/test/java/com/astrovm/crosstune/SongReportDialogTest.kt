package com.astrovm.crosstune

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowToast

/** Reporting a problem with the words: what the user says goes with it, and it says how sending goes. */
@RunWith(RobolectricTestRunner::class)
class SongReportDialogTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun string(id: Int) = context.getString(id)

    @Test
    fun whatsSaidIsSentAndSentItClosesSayingSo() {
        val sent = mutableListOf<String>()
        var status by mutableStateOf<ReportStatus?>(null)
        var closed = 0
        rule.setContent { SongReportDialog(status, onSend = { sent += it }, onDismiss = { closed++ }) }
        val send = rule.onNode(hasText(string(R.string.report_send)))
        rule.onNode(hasSetTextAction()).performTextInput("The chorus comes late")
        send.performClick()
        assertEquals(listOf("The chorus comes late"), sent)
        // While it goes, it can't be sent again.
        status = ReportStatus.SENDING
        rule.waitForIdle()
        send.assertIsNotEnabled()
        // Not there: it says so, and it can be sent again.
        status = ReportStatus.FAILED
        rule.waitForIdle()
        rule.onNode(hasText(string(R.string.report_failed))).assertExists()
        send.assertIsEnabled()
        assertEquals(0, closed)
        status = ReportStatus.SENT
        rule.waitForIdle()
        assertEquals(1, closed)
        assertEquals(string(R.string.report_sent), ShadowToast.getTextOfLatestToast())
    }
}
