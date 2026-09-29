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
    val isMatching: Boolean = false,
    val result: SpotifyMetadata? = null,
    val error: AppError? = null,
    val canRetry: Boolean = false,
    val selectedTarget: SearchTarget = SearchTarget.YOUTUBE_MUSIC,
    val askEachTime: Boolean = false,
    val exactMatch: Boolean = false,
    val showDestinationPicker: Boolean = false,
    val showLinkSettingsHelper: Boolean = false,
    val history: List<HistoryEntry> = emptyList()
)

/** One-shot requests for the Activity, delivered even if they arrive while it is being recreated. */
internal sealed interface Effect {
    data class Open(val url: String, val packageName: String, val finishAfterOpen: Boolean) : Effect
}

/** Holds screen state across configuration changes and owns in-flight network work. */
internal class MainViewModel(
    private val resolver: SpotifyResolver,
    private val matcher: ExactMatcher,
    private val preferences: SharedPreferences
) : ViewModel() {

    private val historyStore = HistoryStore(preferences)

    var uiState by mutableStateOf(
        UiState(
            selectedTarget = SearchTarget.fromName(preferences.getString(KEY_DEFAULT_TARGET, null)),
            askEachTime = preferences.getBoolean(KEY_ASK_EACH_TIME, false),
            exactMatch = preferences.getBoolean(KEY_EXACT_MATCH, false),
            showLinkSettingsHelper = !preferences.getBoolean(KEY_LINK_SETTINGS_HELPER_DISMISSED, false),
            history = historyStore.load()
        )
    )
        private set

    private val effectChannel = Channel<Effect>(Channel.BUFFERED)
    val effects: Flow<Effect> = effectChannel.receiveAsFlow()

    private var job: Job? = null
    private var lastRequest: Pair<SpotifyInput, Boolean>? = null

    fun onUrlChange(text: String) {
        uiState = uiState.copy(spotifyUrl = text, error = null)
    }

    /** Resolves what the user typed, leaving the text field as typed. */
    fun resolveTypedInput() {
        val input = SpotifyLinks.parse(uiState.spotifyUrl) ?: return showError(AppError.INVALID_URL)
        resolve(input, openWhenReady = false)
    }

    /** Resolves a link from another app and opens it (or offers destinations) as soon as it is ready. */
    fun resolveIncoming(text: String?) {
        val incoming = text?.let { SpotifyLinks.extractFirstUrl(it) ?: it }?.trim().orEmpty()
        uiState = uiState.copy(spotifyUrl = incoming)
        val input = SpotifyLinks.parse(incoming) ?: return showError(AppError.INVALID_URL)
        if (input is SpotifyInput.Item) {
            uiState = uiState.copy(spotifyUrl = input.item.url)
        }
        resolve(input, openWhenReady = true)
    }

    /** Clipboard text from the Quick Settings tile or launcher shortcut. */
    fun resolveClipboard(text: String?) {
        if (text.isNullOrBlank()) return showError(AppError.CLIPBOARD_EMPTY)
        resolveIncoming(text)
    }

    fun retry() {
        val (input, openWhenReady) = lastRequest ?: return
        resolve(input, openWhenReady)
    }

    private fun resolve(input: SpotifyInput, openWhenReady: Boolean) {
        lastRequest = input to openWhenReady
        // A newer request always wins; the older call is cancelled rather than left to overwrite it.
        job?.cancel()
        uiState = uiState.copy(
            isLoading = true,
            isMatching = false,
            error = null,
            result = null,
            showDestinationPicker = false
        )
        job = viewModelScope.launch {
            when (val resolution = resolver.resolve(input)) {
                is Resolution.Failed -> uiState = uiState.copy(
                    isLoading = false,
                    error = resolution.error,
                    canRetry = resolution.error.canRetry
                )
                is Resolution.Resolved -> onResolved(resolution, input, openWhenReady)
            }
        }
    }

    private suspend fun onResolved(resolution: Resolution.Resolved, input: SpotifyInput, openWhenReady: Boolean) {
        val history = historyStore.add(HistoryEntry(resolution.item, resolution.metadata))
        uiState = uiState.copy(isLoading = false, result = resolution.metadata, history = history)
        if (input is SpotifyInput.ShortLink) {
            uiState = uiState.copy(spotifyUrl = resolution.item.url)
        }
        when {
            !openWhenReady -> Unit
            uiState.askEachTime -> uiState = uiState.copy(showDestinationPicker = true)
            else -> open(uiState.selectedTarget, finishAfterOpen = true)
        }
    }

    /** Opens the result from a button tap; the picker passes the destination chosen for this link. */
    fun openResult(target: SearchTarget = uiState.selectedTarget, finishAfterOpen: Boolean = false) {
        uiState = uiState.copy(showDestinationPicker = false)
        job?.cancel()
        job = viewModelScope.launch { open(target, finishAfterOpen) }
    }

    private suspend fun open(target: SearchTarget, finishAfterOpen: Boolean) {
        val metadata = uiState.result ?: return
        val exactUrl = if (uiState.exactMatch) {
            uiState = uiState.copy(isMatching = true)
            matcher.find(target, metadata).also { uiState = uiState.copy(isMatching = false) }
        } else {
            null
        }
        val url = exactUrl ?: target.searchUrl(searchQuery(metadata))
        effectChannel.send(Effect.Open(url, target.packageName, finishAfterOpen))
    }

    fun dismissDestinationPicker() {
        uiState = uiState.copy(showDestinationPicker = false)
    }

    fun showHistoryEntry(entry: HistoryEntry) {
        job?.cancel()
        uiState = uiState.copy(
            spotifyUrl = entry.item.url,
            isLoading = false,
            isMatching = false,
            result = entry.metadata,
            error = null
        )
    }

    fun clearHistory() {
        historyStore.clear()
        uiState = uiState.copy(history = emptyList())
    }

    fun searchQuery(): String? = uiState.result?.let(::searchQuery)

    fun searchUrl(): String? = searchQuery()?.let(uiState.selectedTarget::searchUrl)

    fun clear() {
        job?.cancel()
        lastRequest = null
        uiState = uiState.copy(
            spotifyUrl = "",
            isLoading = false,
            isMatching = false,
            result = null,
            error = null,
            showDestinationPicker = false
        )
    }

    fun selectTarget(target: SearchTarget) {
        uiState = uiState.copy(selectedTarget = target)
        preferences.edit { putString(KEY_DEFAULT_TARGET, target.name) }
    }

    fun setAskEachTime(enabled: Boolean) {
        uiState = uiState.copy(askEachTime = enabled)
        preferences.edit { putBoolean(KEY_ASK_EACH_TIME, enabled) }
    }

    fun setExactMatch(enabled: Boolean) {
        uiState = uiState.copy(exactMatch = enabled)
        preferences.edit { putBoolean(KEY_EXACT_MATCH, enabled) }
    }

    fun dismissLinkSettingsHelper() {
        uiState = uiState.copy(showLinkSettingsHelper = false)
        preferences.edit { putBoolean(KEY_LINK_SETTINGS_HELPER_DISMISSED, true) }
    }

    fun showError(error: AppError) {
        uiState = uiState.copy(isLoading = false, isMatching = false, error = error, canRetry = false)
    }

    companion object {
        const val PREFERENCES_NAME = "crosstune_preferences"
        private const val KEY_LINK_SETTINGS_HELPER_DISMISSED = "link_settings_helper_dismissed"
        private const val KEY_DEFAULT_TARGET = "default_target"
        private const val KEY_ASK_EACH_TIME = "ask_each_time"
        private const val KEY_EXACT_MATCH = "exact_match"
    }
}
