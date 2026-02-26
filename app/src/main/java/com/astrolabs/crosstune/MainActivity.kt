package com.astrolabs.crosstune

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Html
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.astrolabs.crosstune.ui.theme.CrosstuneTheme
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class MainActivity : ComponentActivity() {

    private val client = OkHttpClient()
    private val ogDescriptionRegex = Regex("""<meta property=\"og:description\" content=\"([^\"]+)\"""")
    private val ogTitleRegex = Regex("""<meta property=\"og:title\" content=\"([^\"]+)\"""")
    private val sharedUrlRegex = Regex("""https?://[^\s]+""", RegexOption.IGNORE_CASE)
    private val spotifyTrackIdRegex = Regex("""^[A-Za-z0-9]{22}$""")
    private val preferences by lazy { getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE) }

    private var uiState by mutableStateOf(UiState())
    
    companion object {
        private const val PREFERENCES_NAME = "crosstune_preferences"
        private const val KEY_LINK_SETTINGS_HELPER_DISMISSED = "link_settings_helper_dismissed"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        uiState = uiState.copy(
            showLinkSettingsHelper = !preferences.getBoolean(KEY_LINK_SETTINGS_HELPER_DISMISSED, false)
        )
        handleIntent(intent)

        setContent {
            CrosstuneTheme {
                CrosstuneScreen(
                    state = uiState,
                    onUrlChange = { text ->
                        uiState = uiState.copy(spotifyUrl = text, errorMessage = null)
                    },
                    onResolveClick = ::resolveFromInput,
                    onClearClick = {
                        uiState = UiState(
                            selectedTarget = uiState.selectedTarget,
                            showLinkSettingsHelper = uiState.showLinkSettingsHelper
                        )
                    },
                    onOpenClick = ::openFromState,
                    onTargetChange = { target ->
                        uiState = uiState.copy(selectedTarget = target)
                    },
                    onCopySearchClick = ::copySearchFromState,
                    onShareSearchClick = ::shareSearchFromState,
                    onOpenLinkSettingsClick = ::openAppLinkSettings,
                    onDismissLinkSettingsHelper = ::dismissLinkSettingsHelper
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val incoming = intent.dataString?.trim().orEmpty()
                if (incoming.isBlank()) {
                    uiState = uiState.copy(errorMessage = getString(R.string.error_invalid_url))
                    return
                }

                uiState = uiState.copy(spotifyUrl = incoming, errorMessage = null)
                resolveFromAnyInput(incoming, openWhenReady = true, canonicalizeUrl = true)
            }

            Intent.ACTION_SEND -> {
                val sharedPayload = intent.getStringExtra(Intent.EXTRA_TEXT)
                    ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
                    ?: return

                val incoming = extractFirstUrl(sharedPayload) ?: sharedPayload.trim()
                if (incoming.isBlank()) {
                    uiState = uiState.copy(errorMessage = getString(R.string.error_invalid_url))
                    return
                }

                uiState = uiState.copy(spotifyUrl = incoming, errorMessage = null)
                resolveFromAnyInput(incoming, openWhenReady = true, canonicalizeUrl = true)
            }
        }
    }

    private fun resolveFromInput() {
        resolveFromAnyInput(
            input = uiState.spotifyUrl,
            openWhenReady = false,
            canonicalizeUrl = false
        )
    }

    private fun resolveFromAnyInput(
        input: String,
        openWhenReady: Boolean,
        canonicalizeUrl: Boolean
    ) {
        val normalizedInput = (extractFirstUrl(input) ?: input).trim()
        if (normalizedInput.isBlank()) {
            uiState = uiState.copy(errorMessage = getString(R.string.error_invalid_url))
            return
        }

        val trackId = extractTrackId(normalizedInput)
        if (trackId != null) {
            if (canonicalizeUrl) {
                uiState = uiState.copy(spotifyUrl = spotifyTrackUrl(trackId), errorMessage = null)
            }
            resolveTrack(trackId, openWhenReady)
            return
        }

        val uri = runCatching { Uri.parse(normalizedInput) }.getOrNull()
        if (uri != null && uri.isSpotifyShortLink()) {
            resolveShortLinkTrackId(normalizedInput, openWhenReady)
            return
        }

        uiState = uiState.copy(errorMessage = getString(R.string.error_invalid_url))
    }

    private fun resolveShortLinkTrackId(shortLink: String, openWhenReady: Boolean) {
        uiState = uiState.copy(
            isLoading = true,
            errorMessage = null,
            resolvedTrackName = null,
            resolvedArtistName = null
        )

        val request = Request.Builder()
            .url(shortLink)
            .get()
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                runOnUiThread {
                    uiState = uiState.copy(
                        isLoading = false,
                        errorMessage = getString(R.string.error_network)
                    )
                }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val redirectedUrl = response.request.url.toString()
                val trackId = extractTrackId(redirectedUrl)
                response.close()

                runOnUiThread {
                    if (trackId == null) {
                        uiState = uiState.copy(
                            isLoading = false,
                            errorMessage = getString(R.string.error_invalid_url)
                        )
                        return@runOnUiThread
                    }

                    uiState = uiState.copy(spotifyUrl = spotifyTrackUrl(trackId))
                    resolveTrack(trackId, openWhenReady)
                }
            }
        })
    }

    private fun resolveTrack(trackId: String, openWhenReady: Boolean) {
        uiState = uiState.copy(
            isLoading = true,
            errorMessage = null,
            resolvedTrackName = null,
            resolvedArtistName = null
        )

        val request = Request.Builder()
            .url("https://open.spotify.com/track/$trackId")
            .get()
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                runOnUiThread {
                    uiState = uiState.copy(
                        isLoading = false,
                        errorMessage = getString(R.string.error_network)
                    )
                }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                try {
                    val html = response.body?.string() ?: ""
                    val metadata = extractTrackAndArtist(html)
                        ?: extractTrackTitle(html)?.let { it to "" }

                    runOnUiThread {
                        if (metadata == null) {
                            uiState = uiState.copy(
                                isLoading = false,
                                errorMessage = getString(R.string.error_metadata_unavailable)
                            )
                            return@runOnUiThread
                        }

                        val (trackName, artistName) = metadata
                        uiState = uiState.copy(
                            isLoading = false,
                            resolvedTrackName = trackName,
                            resolvedArtistName = artistName,
                            errorMessage = null
                        )

                        if (openWhenReady) {
                            openPrimaryTarget(trackName, artistName)
                        }
                    }
                } catch (_: Exception) {
                    runOnUiThread {
                        uiState = uiState.copy(
                            isLoading = false,
                            errorMessage = getString(R.string.error_metadata_unavailable)
                        )
                    }
                } finally {
                    response.close()
                }
            }
        })
    }

    private fun extractTrackAndArtist(html: String): Pair<String, String>? {
        val description = ogDescriptionRegex.find(html)?.groupValues?.getOrNull(1)?.decodeHtml() ?: return null
        val parts = description.split(" · ")
        if (parts.size < 2) return null

        val artistName = parts[0].trim()
        val trackName = parts[1].trim()

        if (trackName.isBlank()) return null
        return trackName to artistName
    }

    private fun extractTrackTitle(html: String): String? {
        return ogTitleRegex.find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.decodeHtml()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun extractTrackId(input: String): String? {
        val value = input.trim()
        if (value.isBlank()) return null

        if (spotifyTrackIdRegex.matches(value)) {
            return value
        }

        if (value.startsWith("spotify:track:", ignoreCase = true)) {
            return value.substringAfterLast(':').takeIf { spotifyTrackIdRegex.matches(it) }
        }

        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
        uri.getQueryParameter("uri")
            ?.let { embeddedUri -> extractTrackId(embeddedUri) }
            ?.let { return it }

        return uri.extractTrackIdFromUri()
    }

    private fun Uri.extractTrackIdFromUri(): String? {
        val hostValue = host?.lowercase() ?: return null
        val isSpotifyHost = hostValue == "spotify.com" || hostValue.endsWith(".spotify.com")
        if (!isSpotifyHost) return null

        val segments = pathSegments
        val trackIndex = segments.indexOf("track")
        if (trackIndex == -1 || trackIndex + 1 >= segments.size) return null

        return segments[trackIndex + 1].takeIf { spotifyTrackIdRegex.matches(it) }
    }

    private fun Uri.isSpotifyShortLink(): Boolean {
        val hostValue = host?.lowercase() ?: return false
        return hostValue == "spotify.link" || hostValue.endsWith(".spotify.link")
    }

    private fun String.decodeHtml(): String {
        return Html.fromHtml(this, Html.FROM_HTML_MODE_LEGACY).toString()
    }

    private fun extractFirstUrl(text: String): String? {
        val match = sharedUrlRegex.find(text)?.value ?: return null
        return match.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}')
    }

    private fun spotifyTrackUrl(trackId: String): String {
        return "https://open.spotify.com/track/$trackId"
    }

    private fun openFromState() {
        val trackName = uiState.resolvedTrackName ?: return
        val artistName = uiState.resolvedArtistName ?: ""
        openPrimaryTarget(trackName, artistName)
    }

    private fun copySearchFromState() {
        val trackName = uiState.resolvedTrackName ?: return
        val artistName = uiState.resolvedArtistName ?: ""
        val query = buildSearchQuery(trackName, artistName)
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Crosstune search query", query))
        Toast.makeText(this, getString(R.string.search_copied_to_clipboard), Toast.LENGTH_SHORT).show()
    }

    private fun shareSearchFromState() {
        val trackName = uiState.resolvedTrackName ?: return
        val artistName = uiState.resolvedArtistName ?: ""
        val query = buildSearchQuery(trackName, artistName)
        val targetUri = buildTargetSearchUri(uiState.selectedTarget, query)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, targetUri.toString())
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_search_link)))
    }

    private fun openAppLinkSettings() {
        val packageUri = Uri.parse("package:$packageName")
        val openByDefaultIntent = Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS).apply {
            data = packageUri
        }
        val fallbackIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = packageUri
        }

        try {
            startActivity(openByDefaultIntent)
        } catch (_: ActivityNotFoundException) {
            startActivity(fallbackIntent)
        }

        dismissLinkSettingsHelper()
    }

    private fun dismissLinkSettingsHelper() {
        uiState = uiState.copy(showLinkSettingsHelper = false)
        preferences.edit().putBoolean(KEY_LINK_SETTINGS_HELPER_DISMISSED, true).apply()
    }

    private fun openPrimaryTarget(trackName: String, artistName: String) {
        val query = buildSearchQuery(trackName, artistName)
        val targetUri = buildTargetSearchUri(uiState.selectedTarget, query)
        val preferredPackage = when (uiState.selectedTarget) {
            SearchTarget.YOUTUBE_MUSIC -> "com.google.android.apps.youtube.music"
            SearchTarget.YOUTUBE -> "com.google.android.youtube"
        }

        val packagedIntent = Intent(Intent.ACTION_VIEW, targetUri).apply {
            setPackage(preferredPackage)
        }

        try {
            startActivity(packagedIntent)
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Intent.ACTION_VIEW, targetUri))
        }
    }

    private fun buildSearchQuery(trackName: String, artistName: String): String {
        return listOf(trackName, artistName)
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }

    private fun buildTargetSearchUri(target: SearchTarget, query: String): Uri {
        val encoded = Uri.encode(query)
        return when (target) {
            SearchTarget.YOUTUBE_MUSIC -> Uri.parse("https://music.youtube.com/search?q=$encoded")
            SearchTarget.YOUTUBE -> Uri.parse("https://www.youtube.com/results?search_query=$encoded")
        }
    }
}

@Composable
private fun CrosstuneScreen(
    state: UiState,
    onUrlChange: (String) -> Unit,
    onResolveClick: () -> Unit,
    onClearClick: () -> Unit,
    onOpenClick: () -> Unit,
    onTargetChange: (SearchTarget) -> Unit,
    onCopySearchClick: () -> Unit,
    onShareSearchClick: () -> Unit,
    onOpenLinkSettingsClick: () -> Unit,
    onDismissLinkSettingsHelper: () -> Unit
) {
    val gradient = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
            MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)
        )
    )

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(gradient)
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(gradient)
                .safeDrawingPadding()
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = stringResource(R.string.app_tagline),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = 8.dp, bottom = 20.dp)
                        .widthIn(max = 520.dp)
                )

                if (state.showLinkSettingsHelper) {
                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 680.dp)
                            .padding(bottom = 12.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.link_settings_helper_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.link_settings_helper_body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick = onOpenLinkSettingsClick,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(stringResource(R.string.open_link_settings_button))
                                }
                                TextButton(
                                    onClick = onDismissLinkSettingsHelper,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(stringResource(R.string.dismiss_button))
                                }
                            }
                        }
                    }
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 680.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        OutlinedTextField(
                            value = state.spotifyUrl,
                            onValueChange = onUrlChange,
                            singleLine = true,
                            label = { Text(stringResource(R.string.spotify_link_label)) },
                            placeholder = { Text(stringResource(R.string.spotify_link_placeholder)) },
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(onDone = { onResolveClick() }),
                            enabled = !state.isLoading,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = onResolveClick,
                                enabled = !state.isLoading,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.resolve_button))
                            }

                            OutlinedButton(
                                onClick = onClearClick,
                                enabled = !state.isLoading,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.clear_button))
                            }
                        }

                        if (state.isLoading) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 16.dp)
                            )
                            Text(
                                text = stringResource(R.string.loading_text),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }

                        val error = state.errorMessage
                        if (!error.isNullOrBlank()) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer
                                )
                            ) {
                                Text(
                                    text = error,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }

                        val trackName = state.resolvedTrackName
                        if (!trackName.isNullOrBlank()) {
                            val openButtonLabel = when (state.selectedTarget) {
                                SearchTarget.YOUTUBE_MUSIC -> stringResource(R.string.open_in_youtube_music)
                                SearchTarget.YOUTUBE -> stringResource(R.string.open_in_youtube)
                            }

                            ElevatedCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 16.dp)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = stringResource(R.string.result_title),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = trackName,
                                        style = MaterialTheme.typography.titleLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(top = 8.dp)
                                    )

                                    if (!state.resolvedArtistName.isNullOrBlank()) {
                                        Text(
                                            text = state.resolvedArtistName,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }

                                    Text(
                                        text = stringResource(R.string.open_with_label),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 12.dp)
                                    )

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        if (state.selectedTarget == SearchTarget.YOUTUBE_MUSIC) {
                                            Button(
                                                onClick = { onTargetChange(SearchTarget.YOUTUBE_MUSIC) },
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text(stringResource(R.string.target_youtube_music))
                                            }
                                        } else {
                                            OutlinedButton(
                                                onClick = { onTargetChange(SearchTarget.YOUTUBE_MUSIC) },
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text(stringResource(R.string.target_youtube_music))
                                            }
                                        }

                                        if (state.selectedTarget == SearchTarget.YOUTUBE) {
                                            Button(
                                                onClick = { onTargetChange(SearchTarget.YOUTUBE) },
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text(stringResource(R.string.target_youtube))
                                            }
                                        } else {
                                            OutlinedButton(
                                                onClick = { onTargetChange(SearchTarget.YOUTUBE) },
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text(stringResource(R.string.target_youtube))
                                            }
                                        }
                                    }

                                    Button(
                                        onClick = onOpenClick,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 12.dp)
                                    ) {
                                        Text(openButtonLabel)
                                    }

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = onCopySearchClick,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(stringResource(R.string.copy_search_button))
                                        }
                                        OutlinedButton(
                                            onClick = onShareSearchClick,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(stringResource(R.string.share_search_button))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.built_by),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        }
    }
}

private data class UiState(
    val spotifyUrl: String = "",
    val isLoading: Boolean = false,
    val resolvedTrackName: String? = null,
    val resolvedArtistName: String? = null,
    val errorMessage: String? = null,
    val selectedTarget: SearchTarget = SearchTarget.YOUTUBE_MUSIC,
    val showLinkSettingsHelper: Boolean = false
)

private enum class SearchTarget {
    YOUTUBE_MUSIC,
    YOUTUBE
}

@Preview(showBackground = true)
@Composable
private fun CrosstuneScreenPreview() {
    CrosstuneTheme {
        CrosstuneScreen(
            state = UiState(
                spotifyUrl = "https://open.spotify.com/track/11dFghVXANMlKmJXsNCbNl",
                resolvedTrackName = "Cut To The Feeling",
                resolvedArtistName = "Carly Rae Jepsen"
            ),
            onUrlChange = {},
            onResolveClick = {},
            onClearClick = {},
            onOpenClick = {},
            onTargetChange = {},
            onCopySearchClick = {},
            onShareSearchClick = {},
            onOpenLinkSettingsClick = {},
            onDismissLinkSettingsHelper = {}
        )
    }
}
