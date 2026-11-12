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

    /**
     * What [ReportSender] sends: nothing but what's here, and nothing [ReportBox] turns away. Of the lines put in
     * time by hand, those that tell most are kept, to its count and then its size; past its size without them,
     * the log goes too. The rest, cut to its lengths, always fits.
     */
    fun json(): String {
        val usable = timing.filter { ReportBox.takes(it.timedMs, it.heardMs) }
        var points = usable.slice(ReportBox.telling(usable.map { it.timedMs to it.heardMs }, ReportBox.MAX_POINTS))
        var log = player?.log
        var json = jsonWith(points, log)
        while (json.toByteArray().size > ReportBox.MAX_BYTES && (points.isNotEmpty() || log != null)) {
            if (points.isEmpty()) log = null else points = points.slice(ReportBox.telling(points.map { it.timedMs to it.heardMs }, points.size - 1))
            json = jsonWith(points, log)
        }
        return json
    }

    private fun jsonWith(points: List<HandTiming>, log: String?): String = JSONObject().apply {
        put("version", ReportBox.cut(version, "version"))
        put("android", ReportBox.cut(android, "android"))
        ReportBox.cut(comment, "comment").takeIf { it.isNotEmpty() }?.let { put("comment", it) }
        problem?.let { put("problem", it.key) }
        // The box takes a song only with its title and its artist.
        song?.takeIf { ReportBox.cut(it.title, "title").isNotEmpty() && ReportBox.cut(it.artist, "artist").isNotEmpty() }?.let {
            put("song", JSONObject().apply {
                put("title", ReportBox.cut(it.title, "title"))
                put("artist", ReportBox.cut(it.artist, "artist"))
                app?.let { name -> put("app", ReportBox.cut(name, "app")) }
                put("timed", timed)
                put("synced", synced)
                put("sung", sung)
                lengthMs?.let { ms -> put("lengthMs", ms) }
                source?.let { put("source", ReportBox.cut(it, "source")) }
                player?.let { putPlayer(it, log) }
                if (points.isNotEmpty()) put("timing", JSONArray(points.map { line -> JSONObject().put("line", ReportBox.cut(line.line, "line")).put("timedMs", line.timedMs).put("heardMs", line.heardMs) }))
            })
        }
    }.toString()

    private fun JSONObject.putPlayer(player: PlayerTiming, log: String?) {
        player.appPackage?.let { put("appPackage", ReportBox.cut(it, "appPackage")) }
        // Whole hundredths, as a Float's own digits (1.100000023841858) say nothing more.
        player.speed?.let { put("speed", (it * 100.0).roundToLong() / 100.0) }
        player.leadMs?.let { put("leadMs", it) }
        put("earlyMs", player.earlyMs)
        log?.let { ReportBox.cut(it, "log") }?.takeIf { it.isNotEmpty() }?.let { put("log", it) }
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
    /** What [ReportSender] sends, each text cut to what [ReportBox] keeps of it. */
    fun json(): String = JSONObject().apply {
        put("version", ReportBox.cut(version, "version"))
        put("android", ReportBox.cut(android, "android"))
        put("problem", WRONG_READING)
        put("song", JSONObject().apply {
            put("title", ReportBox.cut(song.title, "title"))
            put("artist", ReportBox.cut(song.artist, "artist"))
            source?.let { put("source", ReportBox.cut(it, "source")) }
        })
        put("reading", JSONObject().apply {
            put("line", ReportBox.cut(line, "line"))
            put("word", ReportBox.cut(word.text, "word"))
            put("shown", ReportBox.cut(shown.joinToString(" / "), "shown"))
            put("reader", ReportBox.cut(word.by.joinToString("+") { it.key }, "reader"))
            put("language", ReportBox.cut(script.name.lowercase(), "language"))
            ReportBox.cut(right, "right").takeIf { it.isNotEmpty() }?.let { put("right", it) }
        })
    }.toString()

    companion object {
        /** The problem the report Worker files it under. */
        const val WRONG_READING = "wrong_reading"
    }
}

/** How a report sent went, as [ReportSender] tells by the box's answer. */
internal enum class Delivery {
    /** The box has it. */
    SENT,

    /** The box turned it away, as it always will: it isn't one it takes. */
    REFUSED,

    /** It didn't get there for now: no network, the box down, busy (408) or full for the while (429). */
    FAILED;

    companion object {
        fun of(code: Int): Delivery = when {
            code in 200..299 -> SENT
            code in 400..499 && code != 408 && code != 429 -> REFUSED
            else -> FAILED
        }
    }
}

/**
 * What Crosstune's box for reports, report/worker.js, takes. A report past [MAX_BYTES], or with a line put in time
 * out of [takes], it turns away for good; a text past its length in [LIMITS] it cuts, and of more than [MAX_POINTS]
 * lines it keeps the first. Each must be as worker.js has it: ReportTest reads it to check.
 */
internal object ReportBox {
    const val MAX_BYTES = 48 * 1024
    const val MAX_POINTS = 300

    /** The longest each text may be, in characters, by the name the box gives it. */
    val LIMITS = mapOf(
        "version" to 40, "android" to 40, "title" to 300, "artist" to 300, "app" to 100, "source" to 100, "comment" to 2000, "line" to 300,
        "appPackage" to 150, "log" to 8192, "recording" to 200, "word" to 100, "shown" to 300, "reader" to 100, "language" to 20, "right" to 100
    )

    /** A day, the furthest a time in a report may be. */
    const val DAY_MS = 24 * 60 * 60 * 1000L

    /** [text] as the box keeps its [field]: trimmed, and no longer than its limit, in characters, not UTF-16 units. */
    fun cut(text: String, field: String): String {
        val trimmed = text.trim()
        val max = LIMITS.getValue(field)
        return if (trimmed.codePointCount(0, trimmed.length) <= max) trimmed else trimmed.substring(0, trimmed.offsetByCodePoints(0, max))
    }

    /** Whether the box takes a line the words had at [timedMs] and that's heard at [heardMs]. */
    fun takes(timedMs: Long, heardMs: Long): Boolean = timedMs in 0..DAY_MS && heardMs in -DAY_MS..DAY_MS

    /**
     * Which of [points], each where the words had a line and where it's heard, to keep, at most [max], in their
     * order. The words move in a straight line between two lines put in time ([LyricsSync.timeOf]), so a line
     * that's where the two either side put it says nothing they don't: the one they miss by least goes first,
     * again and again. The first and the last, which say where the rest go on from, stay to the end.
     */
    fun telling(points: List<Pair<Long, Long>>, max: Int): List<Int> {
        val kept = points.indices.sortedBy { points[it].first }.toMutableList()
        while (kept.size > max) {
            val inOrder = kept.map(points::get)
            kept.removeAt((1 until kept.lastIndex).minByOrNull { missedBy(inOrder, it) } ?: kept.lastIndex)
        }
        return kept.sorted()
    }

    /** How far from where the lines either side of it put it the line [at] in [points] is heard. */
    private fun missedBy(points: List<Pair<Long, Long>>, at: Int): Long {
        val (beforeMs, beforeHeard) = points[at - 1]
        val (afterMs, afterHeard) = points[at + 1]
        val (lineMs, heard) = points[at]
        val span = afterMs - beforeMs
        val put = if (span == 0L) beforeHeard else beforeHeard + (lineMs - beforeMs) * (afterHeard - beforeHeard) / span
        return kotlin.math.abs(heard - put)
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
    /** How [report] went. */
    suspend fun send(report: Report): Delivery = post(report.json())

    /** How [report], on a wrong reading, went. */
    suspend fun send(report: ReadingReport): Delivery = post(report.json())

    /** How a report that was kept as its [json], what [Report.json] said, went. */
    suspend fun send(json: String): Delivery = post(json)

    private suspend fun post(json: String): Delivery = withContext(ioDispatcher) {
        try {
            client.newCall(Request.Builder().url(url).post(json.toRequestBody(JSON)).build()).execute().use { Delivery.of(it.code) }
        } catch (_: IOException) {
            Delivery.FAILED
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
