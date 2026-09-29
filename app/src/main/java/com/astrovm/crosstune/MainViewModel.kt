package com.astrovm.crosstune

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

internal data class UiState(
    val spotifyUrl: String = "",
    val isLoading: Boolean = false,
    val result: TrackMetadata? = null,
    val error: AppError? = null,
    val selectedTarget: SearchTarget = SearchTarget.YOUTUBE_MUSIC,
    val showLinkSettingsHelper: Boolean = false
)

/** One-shot requests for the Activity, delivered even if they arrive while it is being recreated. */
internal sealed interface Effect {
    data class OpenSearch(val url: String, val packageName: String, val finishAfterOpen: Boolean) : Effect
}

/** Holds screen state across configuration changes and owns the in-flight resolution. */
internal class MainViewModel(
    private val resolver: SpotifyResolver,
    private val preferences: SharedPreferences
) : ViewModel() {

    var uiState by mutableStateOf(
        UiState(
            selectedTarget = SearchTarget.fromName(preferences.getString(KEY_DEFAULT_TARGET, null)),
            showLinkSettingsHelper = !preferences.getBoolean(KEY_LINK_SETTINGS_HELPER_DISMISSED, false)
        )
    )
        private set

    private val effectChannel = Channel<Effect>(Channel.BUFFERED)
    val effects: Flow<Effect> = effectChannel.receiveAsFlow()

    private var resolveJob: Job? = null

    fun onUrlChange(text: String) {
        uiState = uiState.copy(spotifyUrl = text, error = null)
    }

    /** Resolves what the user typed, leaving the text field as typed. */
    fun resolveTypedInput() {
        val input = SpotifyLinks.parse(uiState.spotifyUrl) ?: return showError(AppError.INVALID_URL)
        resolve(input, openWhenReady = false)
    }

    /** Resolves a link from another app and opens the search as soon as it is ready. */
    fun resolveIncoming(text: String?) {
        val incoming = text?.let { SpotifyLinks.extractFirstUrl(it) ?: it }?.trim().orEmpty()
        uiState = uiState.copy(spotifyUrl = incoming)
        val input = SpotifyLinks.parse(incoming) ?: return showError(AppError.INVALID_URL)
        if (input is SpotifyInput.Track) {
            uiState = uiState.copy(spotifyUrl = SpotifyLinks.trackUrl(input.id))
        }
        resolve(input, openWhenReady = true)
    }

    private fun resolve(input: SpotifyInput, openWhenReady: Boolean) {
        // A newer request always wins; the older call is cancelled rather than left to overwrite it.
        resolveJob?.cancel()
        uiState = uiState.copy(isLoading = true, error = null, result = null)
        resolveJob = viewModelScope.launch {
            when (val resolution = resolver.resolve(input)) {
                is Resolution.Failed -> uiState = uiState.copy(isLoading = false, error = resolution.error)
                is Resolution.Resolved -> {
                    uiState = uiState.copy(isLoading = false, result = resolution.metadata)
                    if (input is SpotifyInput.ShortLink) {
                        uiState = uiState.copy(spotifyUrl = SpotifyLinks.trackUrl(resolution.trackId))
                    }
                    if (openWhenReady) openResult(finishAfterOpen = true)
                }
            }
        }
    }

    fun openResult(finishAfterOpen: Boolean = false) {
        val target = uiState.selectedTarget
        val url = searchUrl() ?: return
        effectChannel.trySend(Effect.OpenSearch(url, target.packageName, finishAfterOpen))
    }

    fun searchQuery(): String? = uiState.result?.let(::searchQuery)

    fun searchUrl(): String? = searchQuery()?.let(uiState.selectedTarget::searchUrl)

    fun clear() {
        resolveJob?.cancel()
        uiState = UiState(
            selectedTarget = uiState.selectedTarget,
            showLinkSettingsHelper = uiState.showLinkSettingsHelper
        )
    }

    fun selectTarget(target: SearchTarget) {
        uiState = uiState.copy(selectedTarget = target)
        preferences.edit { putString(KEY_DEFAULT_TARGET, target.name) }
    }

    fun dismissLinkSettingsHelper() {
        uiState = uiState.copy(showLinkSettingsHelper = false)
        preferences.edit { putBoolean(KEY_LINK_SETTINGS_HELPER_DISMISSED, true) }
    }

    fun showError(error: AppError) {
        uiState = uiState.copy(isLoading = false, error = error)
    }

    companion object {
        const val PREFERENCES_NAME = "crosstune_preferences"
        private const val KEY_LINK_SETTINGS_HELPER_DISMISSED = "link_settings_helper_dismissed"
        private const val KEY_DEFAULT_TARGET = "default_target"
    }
}
