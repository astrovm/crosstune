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
    val linkText: String = "",
    val isLoading: Boolean = false,
    val isMatching: Boolean = false,
    val result: MusicMetadata? = null,
    /** Prepared direct links or search fallbacks, reused by Open, Copy and Share for this result. */
    val destinationUrls: Map<Destination, PreparedLink> = emptyMap(),
    /** The link [result] came from, or the incoming link that failed, so it can still be opened as is. */
    val link: MusicLink? = null,
    val error: AppError? = null,
    val canRetry: Boolean = false,
    val selectedDestination: Destination? = null,
    val defaultDestination: Destination = Destination.Service(MusicService.YOUTUBE_MUSIC),
    val destinations: List<Destination> = MusicService.entries.map(Destination::Service),
    /** Per-source destinations; sources without an entry use [defaultDestination]. */
    val rules: Map<MusicService, Destination> = emptyMap(),
    val intercepted: Set<MusicService> = emptySet(),
    val askEachTime: Boolean = false,
    val exactMatch: Boolean = true,
    /** Drops tracking parameters from links Crosstune opens, copies or shares. */
    val cleanLinks: Boolean = true,
    val showDestinationPicker: Boolean = false,
    /** A service the picker leaves out: the one the link came from, when opening it there would just go back. */
    val pickerHides: MusicService? = null,
    val showLinkSettingsHelper: Boolean = false,
    val history: List<HistoryEntry> = emptyList(),
    /** False until the user finishes first-run setup; nothing is intercepted or chosen before that. */
    val setupComplete: Boolean = true,
    val hasDefault: Boolean = true,
    val installed: Set<MusicService> = emptySet(),
    /** Each source's hosts Android doesn't let Crosstune open yet, or null before Android 12, which can't tell. */
    val unapprovedHosts: Map<MusicService, List<String>>? = null,
    /**
     * Installed music apps that still open links Crosstune intercepts, until changed in their own
     * settings; null before Android 12, which can't tell.
     */
    val blockingApps: Set<MusicService>? = null,
    /** Installed music apps that claim links Crosstune intercepts, blocking or not; null before Android 12. */
    val claimingApps: Set<MusicService>? = null,
    /** Set while handling a link from another app, which takes priority over setup. */
    val handlingIncomingLink: Boolean = false,
    /** Set for every link from another app until the screen has left settings, even for a repeated link. */
    val leaveSettings: Boolean = false
) {
    /** Apps the share sheet offers to open a link in directly: the default first, then other installed ones. */
    val shareTargets: List<MusicService>
        get() = if (!setupComplete || !hasDefault) {
            emptyList()
        } else {
            (listOfNotNull((defaultDestination as? Destination.Service)?.service) + MusicService.entries.filter { it in installed }).distinct()
        }

    /** A one-time choice takes precedence over the source rule and global default. */
    val resultDestination: Destination
        get() = selectedDestination ?: link?.let { rules[it.service] } ?: defaultDestination
}

/** One-shot requests for the Activity, delivered even if they arrive while it is being recreated. */
internal sealed interface Effect {
    /** [packageName] is null for custom destinations, which open in whatever app handles the URL. */
    data class Open(val url: String, val packageName: String?, val finishAfterOpen: Boolean) : Effect
    data class Copy(val url: String) : Effect
}

/** Holds screen state across configuration changes and owns in-flight network work. */
internal class MainViewModel(
    private val resolver: LinkResolver,
    private val matcher: ExactMatcher,
    private val preferences: SharedPreferences,
    private val interception: LinkInterception,
    /** Lives here so loaded covers survive configuration changes. */
    val artwork: ArtworkLoader
) : ViewModel() {

    private val historyStore = HistoryStore(preferences)
    private val destinationStore = DestinationStore(preferences)

    init {
        migrateExistingInstall()
    }

    var uiState by mutableStateOf(
        UiState(
            askEachTime = preferences.getBoolean(KEY_ASK_EACH_TIME, false),
            exactMatch = preferences.getBoolean(KEY_EXACT_MATCH, true),
            cleanLinks = preferences.getBoolean(KEY_CLEAN_LINKS, true),
            showLinkSettingsHelper = !preferences.getBoolean(KEY_LINK_SETTINGS_HELPER_DISMISSED, false),
            history = historyStore.load(),
            setupComplete = preferences.getBoolean(KEY_SETUP_COMPLETE, false)
        ).withDestinations()
    )
        private set

    /**
     * Versions before first-run setup always intercepted Spotify links. People updating from one
     * skip setup and keep that, so their links don't silently stop opening in Crosstune.
     */
    private fun migrateExistingInstall() {
        if (preferences.contains(KEY_SETUP_COMPLETE)) return
        // Recorded on a fresh install's first launch too, so data created before finishing setup
        // (e.g. history from a link opened right away) is never mistaken for an old install.
        val isExistingInstall = LEGACY_KEYS.any(preferences::contains)
        if (isExistingInstall) interception.setEnabled(MusicService.SPOTIFY, true)
        preferences.edit { putBoolean(KEY_SETUP_COMPLETE, isExistingInstall) }
    }

    private val effectChannel = Channel<Effect>(Channel.BUFFERED)
    val effects: Flow<Effect> = effectChannel.receiveAsFlow()

    private var job: Job? = null
    private var lastRequest: Triple<LinkInput, Boolean, Destination?>? = null
    private var pendingOpen = false

    private fun UiState.withDestinations(): UiState {
        val destinations = destinationStore.allDestinations()
        val intercepted = MusicService.entries.filter { it.canBeSource && interception.isEnabled(it) }.toSet()
        val claiming = interception.claimingApps(intercepted)
        return copy(
            defaultDestination = destinationStore.defaultDestination(),
            destinations = destinations,
            selectedDestination = selectedDestination?.takeIf { it in destinations },
            rules = MusicService.entries.mapNotNull { source -> destinationStore.rule(source)?.let { source to it } }.toMap(),
            intercepted = intercepted,
            hasDefault = destinationStore.hasDefault(),
            installed = interception.installedServices(),
            unapprovedHosts = interception.unapprovedHosts(),
            blockingApps = claiming?.filterValues { it }?.keys,
            claimingApps = claiming?.keys
        )
    }

    /**
     * Re-reads what can change outside this screen. A share can be handled by another Crosstune
     * instance that saves the item and finishes, leaving this one with a stale Recent list.
     */
    fun refreshSystemState() {
        val previousDestination = uiState.resultDestination
        uiState = uiState.withDestinations().copy(history = historyStore.load())
        if (previousDestination != uiState.resultDestination) prepareResultDestination()
    }

    fun completeSetup() {
        preferences.edit {
            putBoolean(KEY_SETUP_COMPLETE, true)
            // Setup already walked through allowing links, so the reminder isn't needed.
            putBoolean(KEY_LINK_SETTINGS_HELPER_DISMISSED, true)
        }
        uiState = uiState.copy(setupComplete = true, showLinkSettingsHelper = false)
    }

    fun onUrlChange(text: String) {
        uiState = uiState.copy(linkText = text, error = null)
    }

    /** Resolves what the user typed, leaving the text field as typed. */
    fun resolveTypedInput() {
        uiState = uiState.copy(handlingIncomingLink = false)
        val input = MusicLinks.parse(uiState.linkText) ?: return rejectInput()
        resolve(input, openWhenReady = false)
    }

    fun settingsLeft() {
        uiState = uiState.copy(leaveSettings = false)
    }

    /**
     * Resolves a link from another app and opens it (or offers destinations) as soon as it is
     * ready; in [destination] when the user already chose one, e.g. with a share sheet target.
     */
    fun resolveIncoming(text: String?, destination: Destination? = null) {
        val incoming = text?.let { MusicLinks.extractFirstUrl(it) ?: it }?.trim().orEmpty()
        uiState = uiState.copy(
            linkText = incoming,
            handlingIncomingLink = true,
            leaveSettings = true
        )
        val input = MusicLinks.parse(incoming)
        if (input == null) {
            // Intercepted services' links include pages Crosstune can't convert, such as a
            // SoundCloud feed; hand those straight to the service's app.
            val service = MusicLinks.serviceFor(incoming) ?: return rejectInput()
            effectChannel.trySend(Effect.Open(incoming, service.packageName, finishAfterOpen = true))
            return
        }
        if (input is LinkInput.Link) {
            uiState = uiState.copy(linkText = input.link.url)
        }
        resolve(input, openWhenReady = true, destination)
    }

    /**
     * Text that isn't a music link replaces the previous result. Otherwise the error would sit above an
     * old song and offer to open it, as if that were the link that failed.
     */
    private fun rejectInput() {
        job?.cancel()
        lastRequest = null
        pendingOpen = false
        uiState = uiState.copy(
            result = null, destinationUrls = emptyMap(), selectedDestination = null,
            link = null, showDestinationPicker = false
        )
        showError(AppError.INVALID_URL)
    }

    /** Clipboard text pasted with the field's Paste button: looked up, but only opened on request. */
    fun pasteLink(text: String?) {
        if (text.isNullOrBlank()) return showError(AppError.CLIPBOARD_EMPTY)
        uiState = uiState.copy(linkText = MusicLinks.extractFirstUrl(text) ?: text.trim(), error = null)
        resolveTypedInput()
    }

    /** Clipboard text from the Quick Settings tile or a launcher shortcut, which may name the app to open it in. */
    fun resolveClipboard(text: String?, destination: Destination?) {
        if (text.isNullOrBlank()) return showError(AppError.CLIPBOARD_EMPTY)
        resolveIncoming(text, destination)
    }

    fun retry() {
        val (input, openWhenReady, destination) = lastRequest ?: return
        resolve(input, openWhenReady, destination)
    }

    private fun resolve(input: LinkInput, openWhenReady: Boolean, destination: Destination? = null) {
        lastRequest = Triple(input, openWhenReady, destination)
        pendingOpen = openWhenReady
        // A newer request always wins; the older call is cancelled rather than left to overwrite it.
        job?.cancel()
        uiState = uiState.copy(
            isLoading = true,
            isMatching = false,
            error = null,
            result = null,
            destinationUrls = emptyMap(),
            selectedDestination = destination,
            link = null,
            showDestinationPicker = false
        )
        job = viewModelScope.launch {
            when (val resolution = resolver.resolve(input)) {
                is Resolution.Failed -> uiState = uiState.copy(
                    isLoading = false,
                    error = resolution.error,
                    canRetry = resolution.error.canRetry,
                    link = resolution.link
                )
                is Resolution.Resolved -> onResolved(resolution)
            }
        }
    }

    private suspend fun onResolved(resolution: Resolution.Resolved) {
        val history = historyStore.add(HistoryEntry(resolution.link, resolution.metadata))
        // The box shows the clean link Crosstune works with, e.g. without "?si=" or a short link's redirect.
        uiState = uiState.copy(
            isLoading = false,
            result = resolution.metadata,
            link = resolution.link,
            linkText = resolution.link.url,
            history = history
        )
        prepareResult()
    }

    /** A destination or exact-match preference change prepares the current result again. */
    private fun prepareResultDestination() {
        if (uiState.result == null || uiState.showDestinationPicker) return
        job?.cancel()
        uiState = uiState.copy(isMatching = false)
        job = viewModelScope.launch { prepareResult() }
    }

    private suspend fun prepareResult() {
        // A link shared from the app the user listens in would only open back in that app, so ask
        // where else it should go instead.
        val ownService = uiState.link?.service?.takeIf {
            uiState.hasDefault && (uiState.resultDestination as? Destination.Service)?.service == it
        }
        // Incoming links without a chosen destination must not search the default before asking.
        val ask = uiState.selectedDestination == null &&
            (uiState.askEachTime || !uiState.setupComplete || ownService != null)
        if (pendingOpen && ask) {
            pendingOpen = false
            uiState = uiState.copy(showDestinationPicker = true, pickerHides = ownService)
            return
        }
        prepareDestination(uiState.resultDestination)
        if (!pendingOpen) return
        pendingOpen = false
        open(uiState.resultDestination, finishAfterOpen = true)
    }

    /** Opens the result from a button tap; the picker passes the destination chosen for this link. */
    fun openResult(destination: Destination? = null, finishAfterOpen: Boolean = false) {
        val chosen = destination ?: uiState.resultDestination
        pendingOpen = false
        uiState = uiState.copy(
            showDestinationPicker = false, isMatching = false,
            selectedDestination = destination ?: uiState.selectedDestination
        )
        job?.cancel()
        job = viewModelScope.launch { open(chosen, finishAfterOpen) }
    }

    private suspend fun open(destination: Destination, finishAfterOpen: Boolean) {
        val url = prepareDestination(destination) ?: return
        val service = (destination as? Destination.Service)?.service
        effectChannel.send(Effect.Open(url, service?.packageName, finishAfterOpen))
    }

    private suspend fun prepareDestination(destination: Destination): String? {
        val metadata = uiState.result ?: return null
        val service = (destination as? Destination.Service)?.service
        // The link already belongs to the destination: open it as is instead of searching.
        uiState.link?.takeIf { it.service == service }?.let { link ->
            return link.url
        }
        uiState.destinationUrls[destination]?.let { return it.url.forSharing() }
        val exactUrl = if (uiState.exactMatch && service != null) {
            uiState = uiState.copy(isMatching = true)
            matcher.find(service, metadata).also { uiState = uiState.copy(isMatching = false) }
        } else {
            null
        }
        val url = exactUrl ?: destination.searchUrl(searchQuery(metadata))
        rememberDestination(destination, PreparedLink(url, exactUrl != null, uiState.exactMatch))
        return url.forSharing()
    }

    /** Opens the link in the app it came from, e.g. when it can't be resolved or the user prefers it. */
    fun openOriginal() {
        val link = uiState.link ?: return
        // An exact match may still be running; it must not open a second app when it finishes.
        job?.cancel()
        pendingOpen = false
        uiState = uiState.copy(showDestinationPicker = false, isMatching = false)
        // Returning from the source app must not restart the canceled lookup, even for Open-first.
        destinationUrl()
        val finishAfterOpen = lastRequest?.second == true
        effectChannel.trySend(Effect.Open(link.url, link.service.packageName, finishAfterOpen))
    }

    fun dismissDestinationPicker() {
        uiState = uiState.copy(showDestinationPicker = false)
        // The result is now available for manual actions using its displayed destination.
        prepareResultDestination()
    }

    private fun selectHistoryEntry(entry: HistoryEntry) {
        job?.cancel()
        // The entry replaces whatever was being looked up, including a link from another app.
        lastRequest = null
        pendingOpen = false
        uiState = uiState.copy(
            linkText = entry.link.url,
            isLoading = false,
            isMatching = false,
            result = entry.metadata,
            destinationUrls = uiState.destinations.mapNotNull { destination ->
                entry.destinationLinks[destination.key]?.takeIf { it.matchingEnabled == uiState.exactMatch }
                    ?.let { destination to it }
            }.toMap(),
            selectedDestination = null,
            link = entry.link,
            error = null,
            canRetry = false,
            showDestinationPicker = false,
            handlingIncomingLink = false
        )
    }

    fun showHistoryEntry(entry: HistoryEntry) {
        selectHistoryEntry(entry)
        prepareResultDestination()
    }

    fun openHistoryEntry(entry: HistoryEntry) {
        viewModelScope.launch {
            val destination = destinationFor(entry)
            val url = urlForHistory(entry, destination) ?: return@launch
            val service = (destination as? Destination.Service)?.service
            effectChannel.send(Effect.Open(url, service?.packageName, finishAfterOpen = false))
        }
    }

    fun copyHistoryEntry(entry: HistoryEntry) {
        viewModelScope.launch {
            val url = urlForHistory(entry, destinationFor(entry)) ?: return@launch
            effectChannel.send(Effect.Copy(url))
        }
    }

    /** Recent Open and Copy use the saved default, not whatever result is on screen. */
    private fun destinationFor(entry: HistoryEntry): Destination =
        uiState.rules[entry.link.service] ?: uiState.defaultDestination

    private suspend fun urlForHistory(entry: HistoryEntry, destination: Destination): String? {
        val service = (destination as? Destination.Service)?.service
        if (service == entry.link.service) return entry.link.url
        entry.destinationLinks[destination.key]?.takeIf { it.matchingEnabled == uiState.exactMatch }?.let { return it.url.forSharing() }
        val exactUrl = if (uiState.exactMatch && service != null) matcher.find(service, entry.metadata) else null
        val url = exactUrl ?: destination.searchUrl(searchQuery(entry.metadata))
        val history = historyStore.remember(entry.link.url, destination.key, PreparedLink(url, exactUrl != null, uiState.exactMatch))
        uiState = uiState.copy(history = history)
        return url.forSharing()
    }

    fun clearHistory() {
        historyStore.clear()
        uiState = uiState.copy(history = emptyList())
    }

    fun searchQuery(): String? = uiState.result?.let(::searchQuery)

    fun destinationUrl(): String? {
        val destination = uiState.resultDestination
        uiState.link?.takeIf { (destination as? Destination.Service)?.service == it.service }?.let { return it.url }
        uiState.destinationUrls[destination]?.let { return it.url.forSharing() }
        if (uiState.isMatching) return null
        return searchQuery()?.let(destination::searchUrl)?.also { url ->
            rememberDestination(destination, PreparedLink(url, exact = false, matchingEnabled = uiState.exactMatch))
        }
    }

    /** The link the result came from, as Crosstune would share it. */
    fun originalUrl(): String? = uiState.link?.url?.forSharing()

    fun clear() {
        job?.cancel()
        lastRequest = null
        pendingOpen = false
        uiState = uiState.copy(
            linkText = "",
            isLoading = false,
            isMatching = false,
            result = null,
            destinationUrls = emptyMap(),
            selectedDestination = null,
            link = null,
            error = null,
            showDestinationPicker = false,
            handlingIncomingLink = false
        )
    }

    private fun rememberDestination(destination: Destination, prepared: PreparedLink) {
        val history = uiState.link?.let { historyStore.remember(it.url, destination.key, prepared) } ?: uiState.history
        uiState = uiState.copy(destinationUrls = uiState.destinationUrls + (destination to prepared), history = history)
    }

    fun selectResultDestination(destination: Destination) {
        uiState = uiState.copy(selectedDestination = destination)
        prepareResultDestination()
    }

    fun selectDefault(destination: Destination) {
        destinationStore.setDefault(destination)
        refreshSystemState()
    }

    fun setRule(source: MusicService, destination: Destination?) {
        destinationStore.setRule(source, destination)
        refreshSystemState()
    }

    fun setIntercepted(source: MusicService, enabled: Boolean) {
        interception.setEnabled(source, enabled)
        // Android still has to be told to let Crosstune open the newly added domains.
        uiState = uiState.withDestinations().copy(showLinkSettingsHelper = enabled || uiState.showLinkSettingsHelper)
    }

    fun addCustomDestination(name: String, template: String): Boolean {
        if (name.isBlank() || !Destination.isValidTemplate(template)) return false
        destinationStore.addCustom(name, template)
        uiState = uiState.withDestinations()
        return true
    }

    fun removeCustomDestination(custom: Destination.Custom) {
        destinationStore.removeCustom(custom)
        refreshSystemState()
    }

    fun setAskEachTime(enabled: Boolean) {
        uiState = uiState.copy(askEachTime = enabled)
        preferences.edit { putBoolean(KEY_ASK_EACH_TIME, enabled) }
    }

    fun setExactMatch(enabled: Boolean) {
        uiState = uiState.copy(exactMatch = enabled, destinationUrls = emptyMap())
        preferences.edit { putBoolean(KEY_EXACT_MATCH, enabled) }
        prepareResultDestination()
    }

    fun setCleanLinks(enabled: Boolean) {
        uiState = uiState.copy(cleanLinks = enabled)
        preferences.edit { putBoolean(KEY_CLEAN_LINKS, enabled) }
    }

    /** Saved links keep what the service returned, so turning the setting off brings it back. */
    private fun String.forSharing(): String = if (uiState.cleanLinks) TrackingLinks.clean(this) else this

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
        private const val KEY_ASK_EACH_TIME = "ask_each_time"
        private const val KEY_EXACT_MATCH = "exact_match"
        private const val KEY_CLEAN_LINKS = "clean_links"
        private const val KEY_SETUP_COMPLETE = "setup_complete"

        /** Preferences only an install from before first-run setup can have. */
        private val LEGACY_KEYS = listOf(
            KEY_LINK_SETTINGS_HELPER_DISMISSED, "default_target", "history", KEY_ASK_EACH_TIME, KEY_EXACT_MATCH
        )
    }
}
