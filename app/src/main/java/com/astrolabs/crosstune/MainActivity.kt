package com.astrolabs.crosstune

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Html
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

    private var uiState by mutableStateOf(UiState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
                        uiState = UiState()
                    },
                    onOpenClick = ::openFromState
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW) {
            val trackId = intent.data?.extractTrackIdFromUri()
            if (trackId != null) {
                uiState = uiState.copy(spotifyUrl = "https://open.spotify.com/track/$trackId")
                resolveTrack(trackId, openWhenReady = true)
            } else {
                uiState = uiState.copy(errorMessage = getString(R.string.error_invalid_url))
            }
        }
    }

    private fun resolveFromInput() {
        val trackId = extractTrackId(uiState.spotifyUrl)
        if (trackId == null) {
            uiState = uiState.copy(errorMessage = getString(R.string.error_invalid_url))
            return
        }

        resolveTrack(trackId, openWhenReady = false)
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
                            openYouTubeMusic(trackName, artistName)
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
        if (value.startsWith("spotify:track:")) {
            return value.substringAfterLast(':').takeIf { it.isNotBlank() }
        }

        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
        return uri.extractTrackIdFromUri()
    }

    private fun Uri.extractTrackIdFromUri(): String? {
        val hostValue = host ?: return null
        if (hostValue != "open.spotify.com") return null

        val segments = pathSegments
        val trackIndex = segments.indexOf("track")
        if (trackIndex == -1 || trackIndex + 1 >= segments.size) return null

        return segments[trackIndex + 1].takeIf { it.isNotBlank() }
    }

    private fun String.decodeHtml(): String {
        return Html.fromHtml(this, Html.FROM_HTML_MODE_LEGACY).toString()
    }

    private fun openFromState() {
        val trackName = uiState.resolvedTrackName ?: return
        val artistName = uiState.resolvedArtistName ?: ""
        openYouTubeMusic(trackName, artistName)
    }

    private fun openYouTubeMusic(trackName: String, artistName: String) {
        val query = listOf(trackName, artistName)
            .filter { it.isNotBlank() }
            .joinToString(" ")

        val targetUri = Uri.parse("https://music.youtube.com/search?q=${Uri.encode(query)}")
        startActivity(Intent(Intent.ACTION_VIEW, targetUri))
    }
}

@Composable
private fun CrosstuneScreen(
    state: UiState,
    onUrlChange: (String) -> Unit,
    onResolveClick: () -> Unit,
    onClearClick: () -> Unit,
    onOpenClick: () -> Unit
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

                                    Button(
                                        onClick = onOpenClick,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 12.dp)
                                    ) {
                                        Text(stringResource(R.string.open_button))
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
    val errorMessage: String? = null
)

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
            onOpenClick = {}
        )
    }
}
