package com.astrovm.crosstune

import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.platform.testTag
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
import kotlin.math.roundToLong

/** A line put in time by hand: where the words had it, [timedMs], and where it's heard, [heardMs]. */
internal data class HandTiming(val line: String, val timedMs: Long, val heardMs: Long)

/**
 * What a report about the words' timing carries beyond the lines put in time: why they may have been off.
 * [appPackage] is the app that was followed, as Android names it, and [speed] how fast it said it played;
 * [leadMs] how far ahead of the song Crosstune took that app to be, including zero when it used its
 * clock as given, and [earlyMs] how far ahead of the voice it lit each line. When there is no lead,
 * [log] says why, along with the last minute of the timing log ([TimingLog]).
 */
internal data class PlayerTiming(
    val appPackage: String?,
    val speed: Float?,
    val leadMs: Long?,
    val earlyMs: Long,
    val log: String
)

/**
 * A problem to report from app [version], about [song]'s words when there's one: whether they're
 * [timed], put in time by hand, [synced], and the [app] they followed. Filled in for a GitHub issue,
 * or an email, for the user to say what's wrong and send; or sent as it is, with the user's
 * [comment], how long the recording the words are timed for is, [lengthMs], whether they were
 * lined up to the singing, [sung], and each line put in time by hand, [timing]. One Crosstune
 * sends by itself says which [problem] it found. One about timing also has the [player] it followed.
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
    val source: String? = null,
    val player: PlayerTiming? = null
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
                player?.let { putPlayer(it) }
                if (timing.isNotEmpty()) put("timing", JSONArray(timing.map { line -> JSONObject().put("line", line.line).put("timedMs", line.timedMs).put("heardMs", line.heardMs) }))
            })
        }
    }.toString()

    private fun JSONObject.putPlayer(player: PlayerTiming) {
        player.appPackage?.let { put("appPackage", it) }
        // Whole hundredths, as a Float's own digits (1.100000023841858) say nothing more.
        player.speed?.let { put("speed", (it * 100.0).roundToLong() / 100.0) }
        player.leadMs?.let { put("leadMs", it) }
        put("earlyMs", player.earlyMs)
        player.log.takeIf { it.isNotEmpty() }?.let { put("log", it) }
    }

    fun emailUrl(): String = "mailto:$EMAIL?subject=${Uri.encode(title.ifEmpty { "Crosstune" })}&body=${Uri.encode(body)}"

    companion object {
        const val REPO = "https://github.com/astrovm/crosstune"
        const val EMAIL = "crosstune@4st.li"
    }
}

/**
 * A reading the user says is wrong: of [word], as the word's details show it, [shown], in [line] of
 * [song]'s words, from [source], in [script]; with the [right] one, when the user says it. Sent only
 * when the user taps Send, with nothing but what's here.
 */
internal data class ReadingReport(
    private val version: String,
    private val song: MusicMetadata,
    private val source: String?,
    private val line: String,
    private val word: Word,
    private val shown: List<String>,
    private val script: Script,
    private val right: String = "",
    private val android: String = "${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT})"
) {
    fun json(): String = JSONObject().apply {
        put("version", version)
        put("android", android)
        put("problem", WRONG_READING)
        put("song", JSONObject().apply {
            put("title", song.title)
            put("artist", song.artist)
            source?.let { put("source", it) }
        })
        put("reading", JSONObject().apply {
            put("line", line)
            put("word", word.text)
            put("shown", shown.joinToString(" / "))
            put("reader", word.by.joinToString("+") { it.key })
            put("language", script.name.lowercase())
            right.trim().takeIf { it.isNotEmpty() }?.let { put("right", it) }
        })
    }.toString()

    companion object {
        /** The problem the report Worker files it under. */
        const val WRONG_READING = "wrong_reading"
    }
}

/** How sending a report went. */
internal enum class Delivery {
    /** It got there. */
    SENT,

    /**
     * It didn't get there, or the box turned it away for now: too many from this network or from everyone today, or the
     * box is down. It may get there another time.
     */
    LATER,

    /** The box will never take it as it is, as it isn't a report it reads: sending it again changes nothing. */
    REFUSED;

    companion object {
        /** What the box's answer [code] says. 408 and 429 are "not now", like a server's own trouble (5xx). */
        fun of(code: Int): Delivery = when (code) {
            in 200..299 -> SENT
            408, 429 -> LATER
            in 400..499 -> REFUSED
            else -> LATER
        }
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
    suspend fun send(report: Report): Boolean = deliver(report.json()) == Delivery.SENT

    /** Whether [report], on a wrong reading, got there. */
    suspend fun send(report: ReadingReport): Boolean = deliver(report.json()) == Delivery.SENT

    /** How sending a report as its [json], what [Report.json] says, went. */
    suspend fun deliver(json: String): Delivery = withContext(ioDispatcher) {
        try {
            client.newCall(Request.Builder().url(url).post(json.toRequestBody(JSON)).build()).execute().use { Delivery.of(it.code) }
        } catch (_: IOException) {
            Delivery.LATER
        }
    }

    companion object {
        const val URL = "https://report.crosstune.cc/reports"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /**
         * Where reports go. A build that can be debugged may send them to a box on this phone's own address instead
         * (adb shell setprop debug.crosstune.report_url http://127.0.0.1:PORT/reports, with adb reverse), so a try
         * never reaches the real one. Anything else goes to [URL].
         */
        fun urlFor(debuggable: Boolean, asked: String?): String =
            asked?.takeIf { debuggable && LOCAL.any(it::startsWith) } ?: URL

        private val LOCAL = listOf("http://127.0.0.1:", "http://localhost:")
    }
}

/** What's wrong is said on GitHub, or by email, either filled in with [report]. */
@Composable
internal fun ReportDialog(report: Report, onDismiss: () -> Unit, opener: FocusRequester? = null) {
    val open = rememberWebOpener()
    ScreenAlert(
        onDismissRequest = onDismiss,
        opener = opener,
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
internal fun SongReportDialog(status: ReportStatus?, onSend: (String) -> Unit, onDismiss: () -> Unit, opener: FocusRequester? = null) {
    var comment by rememberSaveable { mutableStateOf("") }
    SendDialog(
        status, R.drawable.ic_feedback, stringResource(R.string.report_problem), R.string.report_what_goes, onSend = { onSend(comment) }, onDismiss = onDismiss, opener = opener
    ) { sending ->
        OutlinedTextField(
            value = comment,
            onValueChange = { comment = it },
            label = { Text(stringResource(R.string.report_comment)) },
            enabled = !sending,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * The reading [shown] for [word] marked wrong, sent in one tap, with the right one when the user
 * knows it: [status] is how it's going. Sent, it says so and closes.
 */
@Composable
internal fun WrongReadingDialog(word: Word, shown: List<String>, status: ReportStatus?, onSend: (right: String) -> Unit, onDismiss: () -> Unit, opener: FocusRequester? = null) {
    var right by rememberSaveable { mutableStateOf("") }
    SendDialog(
        status, R.drawable.ic_flag, stringResource(R.string.reading_wrong), R.string.reading_what_goes, onSend = { onSend(right) }, onDismiss = onDismiss, opener = opener
    ) { sending ->
        // The word and its reading as they showed, so it's plain which is being sent.
        Text(
            listOf(word.text, *shown.toTypedArray()).joinToString("  "),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag(WRONG_READING_TAG)
        )
        OutlinedTextField(
            value = right,
            onValueChange = { right = it },
            label = { Text(stringResource(R.string.reading_right)) },
            singleLine = true,
            enabled = !sending,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** The word and its reading in [WrongReadingDialog], for tests. */
internal const val WRONG_READING_TAG = "wrong_reading"

/** The icon that says what a report sent in one tap is about, for tests. */
internal const val REPORT_ICON_TAG = "report_icon"

/**
 * A report sent in one tap, after what the user may say in [content], [title]d, with [whatGoes]:
 * [status] is how it's going. Sent, it says so and closes; failed, it says so and stays.
 */
@Composable
private fun SendDialog(
    status: ReportStatus?,
    @DrawableRes icon: Int,
    title: String,
    @StringRes whatGoes: Int,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
    opener: FocusRequester?,
    content: @Composable (sending: Boolean) -> Unit
) {
    val sending = status == ReportStatus.SENDING
    val context = LocalContext.current
    val sent = stringResource(R.string.report_sent)
    // With a remote, Send rather than a field, which would open the keyboard for what's optional.
    val send = remember { FocusRequester() }
    LaunchedEffect(status) {
        if (status == ReportStatus.SENT) {
            Toast.makeText(context, sent, Toast.LENGTH_SHORT).show()
            onDismiss()
        }
    }
    ScreenAlert(
        onDismissRequest = onDismiss,
        opener = opener,
        icon = { AppIcon(icon, contentDescription = null, modifier = Modifier.testTag(REPORT_ICON_TAG)) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                content(sending)
                // What goes, said where it's sent, as it's sent without another step.
                Text(stringResource(whatGoes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (status == ReportStatus.FAILED) Text(stringResource(R.string.report_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            // Where Send is, as the dialog's content may be put in the screen a moment after this.
            LaunchedEffect(Unit) { send.requestFocus() }
            Button(onClick = onSend, enabled = !sending, modifier = Modifier.focusRequester(send)) {
                if (sending) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(ButtonDefaults.IconSize))
                else AppIcon(R.drawable.ic_send, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.report_send))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_button)) } }
    )
}
