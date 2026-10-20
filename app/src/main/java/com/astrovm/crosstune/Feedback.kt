package com.astrovm.crosstune

import android.net.Uri
import android.os.Build
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * A problem to report from app [version], about [song]'s words when there's one: whether they're
 * [timed], put in time by hand, [synced], and the [app] they followed. Filled in for a GitHub issue,
 * or an email, for the user to say what's wrong and send.
 */
internal data class Report(
    val version: String,
    val song: MusicMetadata? = null,
    val timed: Boolean = false,
    val synced: Boolean = false,
    val app: String? = null,
    val android: String = "${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT})"
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

    fun emailUrl(): String = "mailto:$EMAIL?subject=${Uri.encode(title.ifEmpty { "Crosstune" })}&body=${Uri.encode(body)}"

    companion object {
        const val REPO = "https://github.com/astrovm/crosstune"
        const val EMAIL = "crosstune@4st.li"
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
