package com.astrovm.crosstune

import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** A line put in time by hand: where the words had it, [timedMs], and where it's heard, [heardMs]. */
internal data class HandTiming(val line: String, val timedMs: Long, val heardMs: Long)

/**
 * A problem to report from app [version], about [song]'s words when there's one: whether they're
 * [timed], put in time by hand, [synced], and the [app] they followed. Filled in for a GitHub issue,
 * or an email, for the user to say what's wrong and send; or sent as it is, with the user's
 * [comment], how long the recording the words are timed for is, [lengthMs], whether they were
 * lined up to the singing, [sung], and each line put in time by hand, [timing]. One Crosstune
 * sends by itself says which [problem] it found.
 */
internal data class Report(
    val version: String,
    val song: MusicMetadata? = null,
    val timed: Boolean = false,
    val synced: Boolean = false,
    val app: String? = null,
    val android: String = "${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT})",
    val comment: String = "",
    val sung: Boolean = false,
    val lengthMs: Long? = null,
    val timing: List<HandTiming> = emptyList(),
    val problem: SongProblem? = null,
    /** Where the words came from, and their timing when it was borrowed. */
    val source: String? = null
) {
    val title: String get() = song?.let { "Lyrics of ${it.title} by ${it.artist}" }.orEmpty()

    /** Room to say what's wrong, then what helps find it. */
    val body: String get() = buildString {
        append("\n\n---\n")
        song?.let {
            append("Song: ${it.title}\nArtist: ${it.artist}\n")
            append("Lyrics: ${if (timed) "timed" else "not timed"}${if (synced) ", put in time by hand" else ""}\n")
            app?.let { name -> append("Following: $name\n") }
        }
        append("Crosstune $version, Android $android")
    }

    fun githubUrl(): String = "$REPO/issues/new".toHttpUrl().newBuilder().addQueryParameter("title", title).addQueryParameter("body", body).build().toString()

    /** What [ReportSender] sends: nothing but what's here. */
    fun json(): String = JSONObject().apply {
        put("version", version)
        put("android", android)
        comment.trim().takeIf { it.isNotEmpty() }?.let { put("comment", it) }
        problem?.let { put("problem", it.key) }
        song?.let {
            put("song", JSONObject().apply {
                put("title", it.title)
                put("artist", it.artist)
                app?.let { name -> put("app", name) }
                put("timed", timed)
                put("synced", synced)
                put("sung", sung)
                lengthMs?.let { ms -> put("lengthMs", ms) }
                source?.let { put("source", it) }
                if (timing.isNotEmpty()) put("timing", JSONArray(timing.map { line -> JSONObject().put("line", line.line).put("timedMs", line.timedMs).put("heardMs", line.heardMs) }))
            })
        }
    }.toString()

    fun emailUrl(): String = "mailto:$EMAIL?subject=${Uri.encode(title.ifEmpty { "Crosstune" })}&body=${Uri.encode(body)}"

    companion object {
        const val REPO = "https://github.com/astrovm/crosstune"
        const val EMAIL = "crosstune@4st.li"
    }
}

/**
 * Sends reports to Crosstune's own box for them, a Cloudflare Worker that keeps them private, to be
 * read by hand: never posted anywhere public, as what's said may be personal.
 */
internal class ReportSender(
    private val client: OkHttpClient,
    /** The app's, which each report says. */
    val version: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val url: String = URL
) {
    /** Whether [report] got there. */
    suspend fun send(report: Report): Boolean = withContext(ioDispatcher) {
        try {
            client.newCall(Request.Builder().url(url).post(report.json().toRequestBody(JSON)).build()).execute().use { it.isSuccessful }
        } catch (_: IOException) {
            false
        }
    }

    companion object {
        const val URL = "https://report.crosstune.4st.li/reports"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/** What's wrong is said on GitHub, or by email, either filled in with [report]. */
@Composable
internal fun ReportDialog(report: Report, onDismiss: () -> Unit) {
    val open = rememberWebOpener()
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { AppIcon(R.drawable.ic_feedback, contentDescription = null) },
        title = { Text(stringResource(R.string.report_problem)) },
        text = { Text(stringResource(R.string.report_problem_text)) },
        confirmButton = {
            Button(onClick = {
                onDismiss()
                open(report.githubUrl())
            }) { Text(stringResource(R.string.report_on_github)) }
        },
        dismissButton = {
            TextButton(onClick = {
                onDismiss()
                open(report.emailUrl())
            }) { Text(stringResource(R.string.report_by_email)) }
        }
    )
}

/**
 * What's wrong with the words, sent in one tap, with what the user says and what helps find it:
 * [status] is how it's going. Sent, it says so and closes.
 */
@Composable
internal fun SongReportDialog(status: ReportStatus?, onSend: (String) -> Unit, onDismiss: () -> Unit) {
    var comment by rememberSaveable { mutableStateOf("") }
    val sending = status == ReportStatus.SENDING
    val context = LocalContext.current
    val sent = stringResource(R.string.report_sent)
    // With a remote, Send rather than the comment, which would open the keyboard for what's optional.
    val send = remember { FocusRequester() }
    LaunchedEffect(Unit) { send.requestFocus() }
    LaunchedEffect(status) {
        if (status == ReportStatus.SENT) {
            Toast.makeText(context, sent, Toast.LENGTH_SHORT).show()
            onDismiss()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { AppIcon(R.drawable.ic_feedback, contentDescription = null) },
        title = { Text(stringResource(R.string.report_problem)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text(stringResource(R.string.report_comment)) },
                    enabled = !sending,
                    modifier = Modifier.fillMaxWidth()
                )
                // What goes, said where it's sent, as it's sent without another step.
                Text(stringResource(R.string.report_what_goes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (status == ReportStatus.FAILED) Text(stringResource(R.string.report_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(onClick = { onSend(comment) }, enabled = !sending, modifier = Modifier.focusRequester(send)) {
                if (sending) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(ButtonDefaults.IconSize))
                else AppIcon(R.drawable.ic_send, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.report_send))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_button)) } }
    )
}
