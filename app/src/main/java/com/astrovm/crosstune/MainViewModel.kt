package com.astrovm.crosstune

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    /** Per-frontend destinations for links from Invidious's or Piped's sites; without one, YouTube's rule or the default applies. */
    val frontendRules: Map<Frontend, Destination> = emptyMap(),
    val intercepted: Set<MusicService> = emptySet(),
    /** Web frontends, like Invidious, whose popular sites' links Crosstune opens: sources of their own. */
    val frontendSources: Set<Frontend> = emptySet(),
    /** Each one's sites Android doesn't let Crosstune open yet; null before Android 12. */
    val unapprovedFrontendHosts: Map<Frontend, List<String>>? = null,
    val askEachTime: Boolean = false,
    val exactMatch: Boolean = true,
    /** Drops tracking parameters from links Crosstune opens, copies or shares. */
    val cleanLinks: Boolean = true,
    /** Whether the share sheet offers "Open in" an app; Android shows those for any shared text. */
    val shareSheetApps: Boolean = true,
    /** Whether a tapped or shared link shows here first instead of opening; see [linkMode]. */
    val showSongFirst: Boolean = false,
    /** YouTube, Invidious and Piped links reach the user's app only when they're music; other videos open as usual. */
    val onlyMusicVideos: Boolean = true,
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
    /** Crosstune's own links are set to open in the browser, so none reach it; see [LinkState.ownLinksOff]. */
    val ownLinksOff: Boolean = false,
    /**
     * Installed apps that still open links Crosstune intercepts, until changed in their own
     * settings; null before Android 12, which can't tell.
     */
    val blockingApps: Set<LinkApp>? = null,
    /** Installed apps that claim links Crosstune intercepts, blocking or not; null before Android 12. */
    val claimingApps: Set<LinkApp>? = null,
    /** For every source, by [Destination.key], installed apps that claim its links and whether each still opens them. */
    val claimingAppsBySource: Map<String, Map<LinkApp, Boolean>>? = null,
    /** The intercepted services' own installed apps, which may take their links when Android can't say. */
    val installedSourceApps: List<LinkApp> = emptyList(),
    /**
     * False until Android has first been asked about installed apps and links, which takes a moment.
     * Until then the fields above don't flag anything, so nothing is shown as a problem before it's known.
     */
    val systemStateKnown: Boolean = true,
    /** Set while handling a link from another app, which takes priority over setup. */
    val handlingIncomingLink: Boolean = false,
    /**
     * Set while a link from another app is on its way to an app, so the screen shows just that
     * instead of all of Crosstune; cleared once the user has something to do here.
     */
    val handingOff: Boolean = false,
    /** Set right after history is cleared, or one item removed, while it can still be brought back. */
    val canUndoClearHistory: Boolean = false,
    /** Whether what can be undone is one item removed rather than all of it cleared. */
    val removedOneFromHistory: Boolean = false,
    /** Counts removals and clears, so each one offers its own undo even while the last offer shows. */
    val historyUndoId: Int = 0,
    /** Set for every link from another app until the screen has left settings, even for a repeated link. */
    val leaveSettings: Boolean = false
) {
    val linkMode: LinkMode
        get() = if (showSongFirst) LinkMode.SHOW else if (askEachTime) LinkMode.ASK else LinkMode.OPEN

    /**
     * Apps the share sheet offers to open a link in directly: the default first, then other installed
     * ones. Crosstune's own entry already opens in the default app, so then it isn't offered twice.
     */
    val shareTargets: List<MusicService>
        get() = if (!setupComplete || !hasDefault || !shareSheetApps) {
            emptyList()
        } else {
            val default = (defaultDestination as? Destination.Service)?.service
            (listOfNotNull(default) + MusicService.entries.filter { it in installed })
                .distinct()
                .filterNot { it == default && linkMode == LinkMode.OPEN }
        }

    /** Whether Crosstune opens any links at all. */
    val interceptsAnything: Boolean get() = intercepted.isNotEmpty() || frontendSources.isNotEmpty()

    /** Whether some link Crosstune is set to open isn't allowed yet; false before Android 12, which can't tell. */
    val someLinksNotAllowed: Boolean
        get() = (ownLinksOff && interceptsAnything) || intercepted.any { unapprovedHosts?.get(it).orEmpty().isNotEmpty() } ||
            frontendSources.any { unapprovedFrontendHosts?.get(it).orEmpty().isNotEmpty() }

    /** A one-time choice takes precedence over the source rule and global default. */
    val resultDestination: Destination
        get() = selectedDestination ?: link?.let(::ruleFor) ?: defaultDestination

    /** Where links like [link] go unless changed for one result: their frontend's rule, then their service's. */
    fun ruleFor(link: MusicLink): Destination? = link.frontend?.let { frontendRules[it] } ?: rules[link.service]
}

/** One-shot requests for the Activity, delivered even if they arrive while it is being recreated. */
/** What a tapped or shared link does: opens in the default app, asks which app, or shows here first. */
internal enum class LinkMode { OPEN, ASK, SHOW }

internal sealed interface Effect {
    /** [packageName] is null for custom destinations, which open in whatever app handles the URL. */
    data class Open(val url: String, val packageName: String?, val finishAfterOpen: Boolean) : Effect
}

/** Holds screen state across configuration changes and owns in-flight network work. */
internal class MainViewModel(
    private val resolver: LinkResolver,
    private val matcher: ExactMatcher,
    private val preferences: SharedPreferences,
    private val interception: LinkInterception,
    /** Lives here so loaded covers survive configuration changes. */
    val artwork: ArtworkLoader,
    /** Where Android is asked about apps and links: many slow calls that mustn't hold up the screen. */
    private val systemDispatcher: CoroutineDispatcher = Dispatchers.Default
) : ViewModel() {

    private val historyStore = HistoryStore(preferences)
    private val destinationStore = DestinationStore(preferences, interception::isInstalled)

    init {
        migrateExistingInstall()
    }

    /** The last look at what Android says about apps and links, null until the first one finishes. */
    private var linkState: LinkState? = null
    private var systemJob: Job? = null

    var uiState by mutableStateOf(
        UiState(
            askEachTime = preferences.getBoolean(KEY_ASK_EACH_TIME, false),
            exactMatch = preferences.getBoolean(KEY_EXACT_MATCH, true),
            cleanLinks = preferences.getBoolean(KEY_CLEAN_LINKS, true),
            shareSheetApps = preferences.getBoolean(KEY_SHARE_SHEET_APPS, true),
            showSongFirst = preferences.getBoolean(KEY_SHOW_SONG_FIRST, false),
            onlyMusicVideos = preferences.getBoolean(KEY_ONLY_MUSIC_VIDEOS, true),
            showLinkSettingsHelper = !preferences.getBoolean(KEY_LINK_SETTINGS_HELPER_DISMISSED, false),
            history = historyStore.load(),
            setupComplete = preferences.getBoolean(KEY_SETUP_COMPLETE, false),
            systemStateKnown = false
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

    /**
     * The user's choices and which sources Crosstune opens, which are quick to read, so anything
     * reading them right after a change sees it. What Android says about other apps and links comes
     * from the last look at it, see [loadSystemState].
     */
    private fun UiState.withDestinations(): UiState {
        val destinations = destinationStore.allDestinations()
        return copy(
            defaultDestination = destinationStore.defaultDestination(destinations),
            destinations = destinations,
            selectedDestination = selectedDestination?.takeIf { it in destinations },
            rules = MusicService.entries.mapNotNull { source -> destinationStore.rule(source, destinations)?.let { source to it } }.toMap(),
            frontendRules = Frontend.SOURCES.mapNotNull { frontend -> destinationStore.rule(frontend, destinations)?.let { frontend to it } }.toMap(),
            intercepted = MusicService.entries.filter { it.canBeSource && interception.isEnabled(it) }.toSet(),
            frontendSources = Frontend.SOURCES.filter(interception::isEnabled).toSet(),
            hasDefault = destinationStore.hasDefault(destinations)
        ).withLinkState()
    }

    /** What Android last said about apps and links, as it concerns the sources Crosstune opens now. */
    private fun UiState.withLinkState(): UiState {
        val state = linkState ?: return this
        val claiming = state.claimingApps(intercepted, frontendSources)
        return copy(
            systemStateKnown = true,
            installed = state.serviceApps.keys,
            unapprovedHosts = state.unapprovedHosts,
            ownLinksOff = state.ownLinksOff,
            unapprovedFrontendHosts = state.unapprovedFrontendHosts,
            blockingApps = claiming?.filterValues { it }?.keys,
            claimingApps = claiming?.keys,
            claimingAppsBySource = state.claimingAppsBySource,
            installedSourceApps = state.installedSourceApps(intercepted)
        )
    }

    /**
     * Re-reads what can change outside this screen. A share can be handled by another Crosstune
     * instance that saves the item and finishes, leaving this one with a stale Recent list.
     */
    fun refreshSystemState() {
        val previousDestination = uiState.resultDestination
        uiState = uiState.withDestinations()
        if (previousDestination != uiState.resultDestination) prepareResultDestination()
        loadSystemState()
    }

    /**
     * Asks Android about installed apps and links, and reloads Recent, away from the main thread:
     * it takes long enough to hold up the screen every time the app comes back. Until it's done
     * the screen keeps what it had. A newer look replaces one still running, so an older answer
     * never overwrites it.
     */
    private fun loadSystemState() {
        systemJob?.cancel()
        val history = uiState.history
        systemJob = viewModelScope.launch {
            val (stored, state) = withContext(systemDispatcher) { historyStore.load() to interception.linkState() }
            linkState = state
            // Anything looked up or cleared meanwhile is newer than what was read.
            val unchanged = uiState.history === history
            uiState = uiState.withLinkState().let { if (unchanged) it.copy(history = stored) else it }
        }
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
        uiState = uiState.copy(handlingIncomingLink = false, handingOff = false)
        val input = MusicLinks.parse(uiState.linkText) ?: return rejectInput()
        resolve(input, openWhenReady = false)
    }

    fun settingsLeft() {
        uiState = uiState.copy(leaveSettings = false)
    }

    /**
     * Resolves a link from another app and opens it (or offers destinations) as soon as it is
     * ready; in [destination] when the user already chose one, e.g. with a share sheet target.
     * With [show], it only shows the result, for the user to pick what to do.
     */
    fun resolveIncoming(text: String?, destination: Destination? = null, show: Boolean = false) {
        val incoming = text?.let { MusicLinks.extractFirstUrl(it) ?: it }?.trim().orEmpty()
        uiState = uiState.copy(
            linkText = incoming,
            handlingIncomingLink = true,
            handingOff = !show,
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
        resolve(input, openWhenReady = !show, destination)
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
        // A link already in Recent is shown from there rather than looked up again. A short link's
        // target isn't known until it's followed, so it's always looked up.
        val link = (input as? LinkInput.Link)?.link
        // The link as parsed is kept, since Recent doesn't save which frontend's site it came from.
        val saved = link?.let { wanted -> uiState.history.firstOrNull { it.link.url == wanted.url }?.copy(link = wanted) }
        job = viewModelScope.launch {
            // A video from another app that isn't music, like a tutorial, opens as it would without Crosstune.
            val video = link?.takeIf {
                openWhenReady && destination == null && uiState.onlyMusicVideos && it.service == MusicService.YOUTUBE
            }
            // Looked up while asking whether it's music, so a music video costs one wait, not two.
            val lookup = async { saved?.let { Resolution.Resolved(it.link, it.metadata) } ?: resolver.resolve(input) }
            if (video != null && matcher.isMusicVideo(video.id) == false) {
                lookup.cancel()
                return@launch openAsIs(video)
            }
            when (val resolution = lookup.await()) {
                is Resolution.Failed -> uiState = uiState.copy(
                    isLoading = false,
                    error = resolution.error,
                    canRetry = resolution.error.canRetry,
                    link = resolution.link,
                    handingOff = false
                )
                // A search page saved while exact matching was on means matching failed that time,
                // e.g. on a flaky network, so a new request for the link tries it again.
                is Resolution.Resolved -> onResolved(resolution, saved?.destinationLinks.orEmpty().filterValues { it.exact || !it.matchingEnabled })
            }
        }
    }

    /** [prepared] are the links already found for it, when it came from Recent. */
    private suspend fun onResolved(resolution: Resolution.Resolved, prepared: Map<String, PreparedLink>) {
        val entry = HistoryEntry(resolution.link, resolution.metadata, prepared)
        val history = historyStore.add(entry)
        // The box shows the clean link Crosstune works with, e.g. without "?si=" or a short link's redirect.
        uiState = uiState.copy(
            isLoading = false,
            result = resolution.metadata,
            link = resolution.link,
            linkText = resolution.link.url,
            destinationUrls = savedDestinations(entry),
            history = history
        )
        prepareResult()
    }

    /** The links saved with [entry] that are still good: found with exact matching as it's set now. */
    private fun savedDestinations(entry: HistoryEntry): Map<Destination, PreparedLink> =
        uiState.destinations.mapNotNull { destination ->
            entry.destinationLinks[destination.key]?.takeIf { it.matchingEnabled == uiState.exactMatch }?.let { destination to it }
        }.toMap()

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
        // A frontend's link, e.g. Invidious's, is worth opening in the service's own app.
        val ownService = uiState.link?.takeUnless { it.viaFrontend }?.service?.takeIf {
            uiState.hasDefault && (uiState.resultDestination as? Destination.Service)?.service == it
        }
        // Incoming links without a chosen destination must not search the default before asking.
        val ask = uiState.selectedDestination == null &&
            (uiState.askEachTime || !uiState.setupComplete || ownService != null)
        if (pendingOpen && ask) {
            pendingOpen = false
            uiState = uiState.copy(showDestinationPicker = true, pickerHides = ownService, handingOff = false)
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
        effectChannel.send(Effect.Open(url, destination.packageName, finishAfterOpen))
    }

    private suspend fun prepareDestination(destination: Destination): String? {
        val metadata = uiState.result ?: return null
        val service = destination.matchService
        // The link already belongs to the destination's service: open it as is instead of searching.
        uiState.link?.takeIf { it.service == service }?.let { link ->
            return destination.adapt(link.url)
        }
        uiState.destinationUrls[destination]?.let { return it.url.forSharing() }
        val exactUrl = if (uiState.exactMatch && service != null) {
            uiState = uiState.copy(isMatching = true)
            matcher.find(service, metadata).also { uiState = uiState.copy(isMatching = false) }
        } else {
            null
        }
        val url = exactUrl?.let(destination::adapt) ?: destination.searchUrl(searchQuery(metadata))
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
            destinationUrls = savedDestinations(entry),
            selectedDestination = null,
            link = entry.link,
            error = null,
            canRetry = false,
            showDestinationPicker = false,
            handlingIncomingLink = false,
            handingOff = false
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
            effectChannel.send(Effect.Open(url, destination.packageName, finishAfterOpen = false))
        }
    }

    /** Recent's Open uses the saved default, not whatever result is on screen. */
    private fun destinationFor(entry: HistoryEntry): Destination =
        uiState.ruleFor(entry.link) ?: uiState.defaultDestination

    private suspend fun urlForHistory(entry: HistoryEntry, destination: Destination): String? {
        val service = destination.matchService
        if (service == entry.link.service) return destination.adapt(entry.link.url)
        entry.destinationLinks[destination.key]?.takeIf { it.matchingEnabled == uiState.exactMatch }?.let { return it.url.forSharing() }
        val exactUrl = if (uiState.exactMatch && service != null) matcher.find(service, entry.metadata) else null
        val url = exactUrl?.let(destination::adapt) ?: destination.searchUrl(searchQuery(entry.metadata))
        val history = historyStore.remember(entry.link.url, destination.key, PreparedLink(url, exactUrl != null, uiState.exactMatch))
        uiState = uiState.copy(history = history)
        return url.forSharing()
    }

    /** Kept until the undo offer goes away, so clearing by mistake loses nothing. */
    private var clearedHistory: List<HistoryEntry> = emptyList()

    fun clearHistory() {
        clearedHistory = uiState.history
        historyStore.clear()
        uiState = uiState.copy(
            history = emptyList(),
            canUndoClearHistory = true,
            removedOneFromHistory = false,
            historyUndoId = uiState.historyUndoId + 1
        )
    }

    /** Drops one item, e.g. swiped away; undo puts it back where it was. */
    fun removeHistoryEntry(entry: HistoryEntry) {
        clearedHistory = uiState.history
        uiState = uiState.copy(
            history = historyStore.replace(uiState.history.filterNot { it.link.url == entry.link.url }),
            canUndoClearHistory = true,
            removedOneFromHistory = true,
            historyUndoId = uiState.historyUndoId + 1
        )
    }

    fun undoClearHistory() {
        // Anything looked up since stays on top, and the rest goes back in its old order.
        val kept = clearedHistory.map { it.link.url }.toSet()
        val restored = uiState.history.filterNot { it.link.url in kept } + clearedHistory
        uiState = uiState.copy(history = historyStore.replace(restored), canUndoClearHistory = false)
        clearedHistory = emptyList()
    }

    fun forgetClearedHistory() {
        clearedHistory = emptyList()
        uiState = uiState.copy(canUndoClearHistory = false)
    }

    /** Stops a link from another app on its way out and shows Crosstune instead. */
    fun cancelHandoff() {
        job?.cancel()
        pendingOpen = false
        uiState = uiState.copy(handingOff = false, isLoading = false, isMatching = false)
    }

    fun searchQuery(): String? = uiState.result?.let(::searchQuery)

    fun destinationUrl(): String? {
        val destination = uiState.resultDestination
        uiState.link?.takeIf { destination.matchService == it.service }?.let { return destination.adapt(it.url) }
        // Every shown result has its link prepared before Copy and Share are enabled.
        return uiState.destinationUrls[destination]?.url?.forSharing()
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
            handlingIncomingLink = false,
            handingOff = false
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
        // Crosstune would only hand the app its own links back, unless a rule sends them elsewhere.
        (destination as? Destination.Service)?.service
            ?.takeIf { interception.isEnabled(it) && destinationStore.rule(it) == null }
            ?.let { interception.setEnabled(it, false) }
        refreshSystemState()
    }

    fun setRule(source: MusicService, destination: Destination?) {
        destinationStore.setRule(source, destination)
        // Without its rule, the app the user listens in would only get its own links back.
        if (destination == null && interception.isEnabled(source) && source == uiState.listeningService()) {
            interception.setEnabled(source, false)
        }
        refreshSystemState()
    }

    /**
     * Ticks every music service the first time setup lists them, but the one the user listens in;
     * video sources stay off, since most videos aren't music. Changing them later is up to the user.
     */
    fun preselectSources() {
        if (preferences.getBoolean(KEY_SOURCES_PRESELECTED, false)) return
        preferences.edit { putBoolean(KEY_SOURCES_PRESELECTED, true) }
        if (uiState.interceptsAnything) return
        val listening = uiState.listeningService()
        MusicService.entries.filter { it.canBeSource && it != MusicService.YOUTUBE && it != listening }
            .forEach { interception.setEnabled(it, true) }
        uiState = uiState.withDestinations().copy(showLinkSettingsHelper = true)
        loadSystemState()
    }

    fun setFrontendRule(frontend: Frontend, destination: Destination?) {
        destinationStore.setRule(frontend, destination)
        refreshSystemState()
    }

    fun setFrontendIntercepted(frontend: Frontend, enabled: Boolean) {
        interception.setEnabled(frontend, enabled)
        uiState = uiState.withDestinations().copy(showLinkSettingsHelper = enabled || uiState.showLinkSettingsHelper)
        loadSystemState()
    }

    fun setIntercepted(source: MusicService, enabled: Boolean) {
        interception.setEnabled(source, enabled)
        // Android still has to be told to let Crosstune open the newly added domains.
        uiState = uiState.withDestinations().copy(showLinkSettingsHelper = enabled || uiState.showLinkSettingsHelper)
        loadSystemState()
    }

    fun addCustomDestination(name: String, template: String): Boolean {
        if (name.isBlank() || !Destination.isValidTemplate(template)) return false
        destinationStore.addCustom(name, template)
        uiState = uiState.withDestinations()
        return true
    }

    /** Moves a web frontend to another site; false if [address] isn't a web address. */
    fun setFrontendInstance(frontend: Frontend, address: String): Boolean {
        if (!destinationStore.setInstance(frontend, address)) return false
        // Links saved for it point at the old site, which the user may have left because it's down.
        uiState = uiState.copy(history = historyStore.forget(Destination.Alternative(frontend).key))
        refreshSystemState()
        return true
    }

    fun removeCustomDestination(custom: Destination.Custom) {
        destinationStore.removeCustom(custom)
        refreshSystemState()
    }

    fun setExactMatch(enabled: Boolean) {
        uiState = uiState.copy(exactMatch = enabled, destinationUrls = emptyMap())
        preferences.edit { putBoolean(KEY_EXACT_MATCH, enabled) }
        prepareResultDestination()
    }

    /** A frontend's video goes back to its site, in a browser; any other to the YouTube app. */
    private suspend fun openAsIs(video: MusicLink) {
        pendingOpen = false
        val packageName = if (video.frontendUrl == null) MusicService.YOUTUBE.packageName else null
        effectChannel.send(Effect.Open(video.frontendUrl ?: video.url, packageName, finishAfterOpen = true))
    }

    fun setOnlyMusicVideos(enabled: Boolean) {
        uiState = uiState.copy(onlyMusicVideos = enabled)
        preferences.edit { putBoolean(KEY_ONLY_MUSIC_VIDEOS, enabled) }
    }

    /** What a tapped or shared link does, picked with the default app: open in it, ask, or show here first. */
    fun setLinkMode(mode: LinkMode) {
        uiState = uiState.copy(askEachTime = mode == LinkMode.ASK, showSongFirst = mode == LinkMode.SHOW)
        preferences.edit {
            putBoolean(KEY_ASK_EACH_TIME, mode == LinkMode.ASK)
            putBoolean(KEY_SHOW_SONG_FIRST, mode == LinkMode.SHOW)
        }
    }

    fun setShareSheetApps(enabled: Boolean) {
        uiState = uiState.copy(shareSheetApps = enabled)
        preferences.edit { putBoolean(KEY_SHARE_SHEET_APPS, enabled) }
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
        uiState = uiState.copy(isLoading = false, isMatching = false, error = error, canRetry = false, handingOff = false)
    }

    companion object {
        const val PREFERENCES_NAME = "crosstune_preferences"
        private const val KEY_LINK_SETTINGS_HELPER_DISMISSED = "link_settings_helper_dismissed"
        private const val KEY_ASK_EACH_TIME = "ask_each_time"
        private const val KEY_EXACT_MATCH = "exact_match"
        private const val KEY_CLEAN_LINKS = "clean_links"
        private const val KEY_SHARE_SHEET_APPS = "share_sheet_apps"
        private const val KEY_SHOW_SONG_FIRST = "show_song_first"
        private const val KEY_ONLY_MUSIC_VIDEOS = "only_music_videos"
        private const val KEY_SOURCES_PRESELECTED = "sources_preselected"
        private const val KEY_SETUP_COMPLETE = "setup_complete"

        /** Preferences only an install from before first-run setup can have. */
        private val LEGACY_KEYS = listOf(
            KEY_LINK_SETTINGS_HELPER_DISMISSED, "default_target", "history", KEY_ASK_EACH_TIME, KEY_EXACT_MATCH
        )
    }
}
