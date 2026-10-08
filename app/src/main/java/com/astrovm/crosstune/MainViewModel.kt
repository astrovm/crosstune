package com.astrovm.crosstune

import android.content.SharedPreferences
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astrovm.crosstune.ui.theme.Palette
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

internal data class UiState(
    val linkText: String = "",
    val isLoading: Boolean = false,
    val isMatching: Boolean = false,
    /** Set while the microphone is listening for a song playing nearby. */
    val listening: Boolean = false,
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
    /** While Play all matches a collection's songs: how many have been looked up, out of how many. */
    val queueProgress: Pair<Int, Int>? = null,
    /** Where in the list the songs Play all is matching start, so a later part shows its progress by itself. */
    val queueFrom: Int = 0,
    /** What happens once a shared link is looked up, when a share sheet action asked for something other than opening. */
    val afterLookup: AfterLookup = AfterLookup.OPEN,
    /** YouTube, Invidious and Piped links reach the user's app only when they're music; other videos open as usual. */
    val onlyMusicVideos: Boolean = true,
    val showDestinationPicker: Boolean = false,
    /** A service the picker leaves out: the one the link came from, when opening it there would just go back. */
    val pickerHides: MusicService? = null,
    /** What a song's lyrics are being fetched for, or null when they aren't shown. */
    val lyricsFor: MusicMetadata? = null,
    /** The words fetched, empty while they're on the way or when the service has none. */
    val lyrics: String = "",
    /** Set while the words are being fetched. */
    val isLoadingLyrics: Boolean = false,
    /** Set when the service wouldn't answer, which isn't the same as the song having no words. */
    val lyricsFailed: Boolean = false,
    /** The words with when each is sung, so they can follow the song; empty when they aren't timed. */
    val lyricLines: List<LyricLine> = emptyList(),
    /** Where the song is as it plays, while something says so: a music app, or what Crosstune heard. */
    val following: Following? = null,
    /** Whether Android lets Crosstune see what music apps play, which timed words follow. */
    val canFollowApps: Boolean = false,
    /**
     * Set while the microphone keeps listening along with the words, to follow the song, or the next
     * one; it stays set while that's paused with Crosstune out of sight.
     */
    val listeningAlong: Boolean = false,
    /** Set while showing how to let Crosstune see what music apps play, step by step. */
    val followHelp: Boolean = false,
    /** Light, dark, or as the phone is. */
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** The app's color: Crosstune's own, another, or the wallpaper's. */
    val palette: Palette = Palette.CROSSTUNE,
    /** True black grounds in the dark. */
    val pureBlack: Boolean = false,
    /** Songs found for typed text that wasn't a link, offered to pick from. */
    val songSearch: List<MusicMetadata> = emptyList(),
    /** What was searched for, so an empty list can say what it found nothing for. */
    val songSearchQuery: String = "",
    /** Set while the songs for typed text are being looked up. */
    val isSearchingSongs: Boolean = false,
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
    /**
     * A playlist with its songs listed, which play from here: elsewhere than its own service, or
     * YouTube's, where it opens as itself, a search for its name rarely finds it.
     */
    val isSongList: Boolean
        get() = result?.type == ItemType.PLAYLIST && result.tracks.isNotEmpty() && link?.service != resultDestination.matchService &&
            link?.youtubePlaylistOn(resultDestination.matchService) == null

    /**
     * Whether a collection's songs can play as one queue where it goes: YouTube Music and YouTube
     * make one for any list of videos. A YouTube playlist opens as itself there instead.
     */
    val canPlayAll: Boolean
        get() = result?.tracks.orEmpty().isNotEmpty() &&
            resultDestination.matchService.let { it == MusicService.YOUTUBE_MUSIC || it == MusicService.YOUTUBE } &&
            link?.youtubePlaylistOn(resultDestination.matchService) == null

    val linkMode: LinkMode
        get() = if (showSongFirst) LinkMode.SHOW else if (askEachTime) LinkMode.ASK else LinkMode.OPEN

    /** Whether the share sheet's top row offers Crosstune's entries; Android shows them for any shared text. */
    val shareSheetEntries: Boolean
        get() = setupComplete && hasDefault && shareSheetApps

    /** The app the top row offers to open a shared link in, when the default is one. */
    val shareSheetApp: MusicService?
        get() = (defaultDestination as? Destination.Service)?.service

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
internal enum class AfterLookup { OPEN, SHARE }

/** The app's look: as the phone is set, or always light or dark. */
internal enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** What a tapped or shared link does: opens in the default app, asks which app, or shows here first. */
internal enum class LinkMode { OPEN, ASK, SHOW }

internal sealed interface Effect {
    /** [packageName] is null for custom destinations, which open in whatever app handles the URL. */
    data class Open(val url: String, val packageName: String?, val finishAfterOpen: Boolean) : Effect
    data class Share(val url: String) : Effect
}

/** Holds screen state across configuration changes and owns in-flight network work. */
internal class MainViewModel(
    private val resolver: LinkResolver,
    private val matcher: ExactMatcher,
    /** Where a song's words come from; nullable so tests need no lyrics service. */
    private val lyricsFinder: LyricsFinder? = null,
    /** Where a song typed by name is looked up; nullable so tests need no catalogue. */
    private val songSearcher: SongSearcher? = null,
    /** What music apps are playing, which a song's timed words follow; nullable so tests need none. */
    private val playback: PlaybackSource? = null,
    private val preferences: SharedPreferences,
    private val interception: LinkInterception,
    /** Lives here so loaded covers survive configuration changes. */
    val artwork: ArtworkLoader,
    /** Names a song playing nearby. */
    private val listener: SongHearing,
    /** Where Android is asked about apps and links: many slow calls that mustn't hold up the screen. */
    private val systemDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** How long listening along waits after naming a song before it listens again. */
    private val listenAlongPauseMs: Long = LISTEN_ALONG_PAUSE_MS,
    /** The clock a song's place is kept on, the one that counts since the phone started. */
    private val now: () -> Long = SystemClock::elapsedRealtime
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
            theme = ThemeMode.entries.firstOrNull { it.name == preferences.getString(KEY_THEME, null) } ?: ThemeMode.SYSTEM,
            palette = Palette.entries.firstOrNull { it.name == preferences.getString(KEY_PALETTE, null) } ?: Palette.CROSSTUNE,
            pureBlack = preferences.getBoolean(KEY_PURE_BLACK, false),
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
        val input = parse(uiState.linkText)
        if (input == null) {
            // Not a link, so it may be a song's name. One with a catalogue behind it gets a list to
            // pick from; anything else is still not something Crosstune can open.
            searchSongs(uiState.linkText)
            return
        }
        resolve(input, openWhenReady = false)
    }

    private var searchJob: Job? = null

    /**
     * Looks [query] up as a song's name and offers what was found to pick from, so a song can be
     * converted without its link. Nothing is searched for a link, which is already all it needs.
     */
    fun searchSongs(query: String) {
        val searcher = songSearcher
        val words = query.trim()
        // A link Crosstune can't open is reported as one, not looked up as a song named after it.
        if (searcher == null || words.isEmpty() || MusicLinks.looksLikeALink(words)) return rejectInput()
        cancelContentWork()
        searchJob = viewModelScope.launch {
            // Whatever was shown before is gone, so the songs found are what the screen is about.
            uiState = uiState.copy(
                isSearchingSongs = true,
                // The sheet says what it's looking for while it looks.
                songSearchQuery = words,
                songSearch = emptyList(),
                result = null,
                destinationUrls = emptyMap(),
                link = null
            )
            val songs = searcher.search(words)
            // The sheet says what was found, an empty list included, so it is the one place that
            // does. NOT_FOUND reads as a link that went missing, and would sit over the songs of
            // the next search, so any error from before is cleared with it.
            uiState = uiState.copy(
                isSearchingSongs = false,
                songSearch = songs,
                songSearchQuery = words,
                error = null
            )
        }
    }

    /** Closes the songs offered, leaving the typed text as it was. */
    fun dismissSongSearch() {
        searchJob?.cancel()
        uiState = uiState.copy(isSearchingSongs = false, songSearch = emptyList(), songSearchQuery = "")
    }

    /**
     * Opens the chosen song where the result goes, matched like a song of its own. Its own link,
     * where it has one, is what gets resolved, so it opens in the default app exactly as if that
     * link had been pasted.
     */
    fun chooseSong(song: MusicMetadata) {
        dismissSongSearch()
        // The field shows the link about to be resolved, so a chosen song reads like a pasted one.
        song.url?.let { MusicLinks.parse(it) }?.let { parsed ->
            uiState = uiState.copy(linkText = (parsed as? LinkInput.Link)?.link?.url ?: song.url)
            resolve(parsed, openWhenReady = false)
            return
        }
        val input = MusicLinks.recognizedSong(song)
        uiState = uiState.copy(linkText = input.text)
        resolve(input, openWhenReady = false)
    }

    /** A recognized song keeps its "Song by Artist" text, so Convert reads it again from the box. */
    private fun fieldText(link: MusicLink, metadata: MusicMetadata) =
        if (link.service == null) LinkInput.RecognizedSong(link.url, metadata).text else link.url

    /**
     * A recognized song needs the text shared with its search link, but Recent and the widget
     * reopen it from the link alone.
     */
    private fun parse(text: String): LinkInput? = MusicLinks.parse(text)
        ?: uiState.history.firstOrNull { it.link.service == null && it.link.url == text.trim() }
            ?.let { LinkInput.RecognizedSong(it.link.url, it.metadata) }

    fun settingsLeft() {
        uiState = uiState.copy(leaveSettings = false)
    }

    /**
     * Resolves a link from another app and opens it (or offers destinations) as soon as it is
     * ready; in [destination] when the user already chose one, e.g. with a share sheet target.
     * With [show], it only shows the result, for the user to pick what to do.
     */
    fun resolveIncoming(text: String?, destination: Destination? = null, show: Boolean = false, after: AfterLookup = AfterLookup.OPEN) {
        cancelContentWork()
        val incoming = text?.let { MusicLinks.extractFirstUrl(it) ?: it }?.trim().orEmpty()
        uiState = uiState.copy(
            linkText = incoming,
            handlingIncomingLink = true,
            handingOff = !show,
            afterLookup = after,
            leaveSettings = true
        )
        val input = parse(text.orEmpty())
        if (input == null) {
            // Intercepted services' links include pages Crosstune can't convert, such as a
            // SoundCloud feed; hand those straight to the service's app.
            val service = MusicLinks.serviceFor(incoming) ?: return rejectInput()
            effectChannel.trySend(Effect.Open(incoming, service.packageName, finishAfterOpen = true))
            return
        }
        if (input is LinkInput.Link) uiState = uiState.copy(linkText = input.link.url)
        if (input is LinkInput.RecognizedSong) uiState = uiState.copy(linkText = input.text)
        resolve(input, openWhenReady = !show, destination)
    }

    /**
     * Text that isn't a music link replaces the previous result. Otherwise the error would sit above an
     * old song and offer to open it, as if that were the link that failed.
     */
    private fun rejectInput() {
        cancelContentWork()
        uiState = uiState.copy(
            result = null, destinationUrls = emptyMap(), selectedDestination = null,
            link = null, showDestinationPicker = false
        )
        showError(AppError.INVALID_URL)
    }

    /** Clipboard text pasted with the field's Paste button: looked up, but only opened on request. */
    fun pasteLink(text: String?) {
        if (text.isNullOrBlank()) return showError(AppError.CLIPBOARD_EMPTY)
        val recognized = MusicLinks.parse(text) as? LinkInput.RecognizedSong
        uiState = uiState.copy(linkText = recognized?.text ?: MusicLinks.extractFirstUrl(text) ?: text.trim(), error = null)
        resolveTypedInput()
    }

    /** Clipboard text from the Quick Settings tile or a launcher shortcut, which may name the app to open it in. */
    fun resolveClipboard(text: String?, destination: Destination?, show: Boolean, after: AfterLookup) {
        if (text.isNullOrBlank()) return showError(AppError.CLIPBOARD_EMPTY)
        resolveIncoming(text, destination, show, after)
    }

    fun retry() {
        val (input, openWhenReady, destination) = lastRequest ?: return
        resolve(input, openWhenReady, destination)
    }

    /** Whether Try again listens again, since listening failed before a song was found. */
    var retryListens = false
        private set

    /**
     * Listens for a song playing nearby, then shows it as a pasted link would: by its Apple Music
     * link when Shazam knows it, like a Shazam link, or else by its name, like a Now Playing song.
     * The microphone permission is already granted.
     */
    fun listen() {
        cancelContentWork()
        heardSong = null
        heardClock = null
        retryListens = true
        uiState = uiState.copy(
            listening = true, isLoading = false, isMatching = false, error = null, linkText = "", result = null,
            destinationUrls = emptyMap(), selectedDestination = null, link = null, showDestinationPicker = false,
            handlingIncomingLink = false, handingOff = false
        )
        job = viewModelScope.launch {
            val heard = try {
                listener.listen()
            } finally {
                if (isActive) uiState = uiState.copy(listening = false)
            }
            when (heard) {
                is Heard.Song -> {
                    retryListens = false
                    heardSong = heard.metadata
                    heardClock = heard.clock
                    val input = heard.appleMusicId?.let { MusicLinks.parse("https://www.shazam.com/song/$it") }
                        ?: MusicLinks.recognizedSong(heard.metadata)
                    uiState = uiState.copy(linkText = (input as? LinkInput.RecognizedSong)?.text ?: (input as LinkInput.Link).link.url)
                    resolve(input, openWhenReady = false)
                }
                Heard.Nothing -> showListenError(AppError.NO_MATCH)
                is Heard.Failed -> showListenError(heard.error)
            }
        }
    }

    fun stopListening() {
        job?.cancel()
        uiState = uiState.copy(listening = false)
    }

    private fun showListenError(error: AppError) {
        uiState = uiState.copy(error = error, canRetry = error.canRetry)
    }

    private fun resolve(input: LinkInput, openWhenReady: Boolean, destination: Destination? = null) {
        cancelContentWork()
        lastRequest = Triple(input, openWhenReady, destination)
        pendingOpen = openWhenReady
        // A newer request always wins; the older call is cancelled rather than left to overwrite it.
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
            val lookup = async { saved?.let { Resolution.Resolved(it.link, it.metadata) } ?: withCover(resolver.resolve(input)) }
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

    /**
     * The album cover, for songs that don't come with one: none from Now Playing, and a video frame
     * from YouTube. A YouTube video keeps its frame when no song matches, e.g. a tutorial, and only
     * waits briefly for the cover, since it has a picture either way.
     */
    private suspend fun withCover(resolution: Resolution): Resolution {
        if (resolution !is Resolution.Resolved || resolution.link.type != ItemType.TRACK) return resolution
        val timeoutMs = when (resolution.link.service) {
            null -> if (resolution.metadata.artworkUrl == null) COVER_TIMEOUT_MS else return resolution
            MusicService.YOUTUBE, MusicService.YOUTUBE_MUSIC -> VIDEO_COVER_TIMEOUT_MS
            else -> return resolution
        }
        val cover = matcher.cover(resolution.metadata, timeoutMs) ?: return resolution
        return resolution.copy(metadata = resolution.metadata.copy(artworkUrl = cover))
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
            linkText = fieldText(resolution.link, resolution.metadata),
            destinationUrls = savedDestinations(entry),
            history = history
        )
        loadTrackCovers()
        prepareResult()
    }

    private var coverJob: Job? = null

    /**
     * Covers for a playlist's songs that came without one, shown as they're found and kept with
     * it in Recent, so it shows them all straight away next time.
     */
    private fun loadTrackCovers() {
        coverJob?.cancel()
        val link = uiState.link ?: return
        val result = uiState.result?.takeIf { it.type == ItemType.PLAYLIST } ?: return
        val missing = result.tracks.indices.filter { result.tracks[it].artworkUrl == null }.take(MAX_COVER_LOOKUPS)
        if (missing.isEmpty()) return
        coverJob = viewModelScope.launch {
            val tracks = result.tracks.toMutableList()
            try {
                matcher.covers(missing.map(tracks::get)) { found, cover ->
                    tracks[missing[found]] = tracks[missing[found]].copy(artworkUrl = cover)
                    // Only while it's still the one on screen.
                    if (uiState.link?.url == link.url) uiState = uiState.copy(result = uiState.result?.copy(tracks = tracks.toList()))
                }
            } finally {
                // Stopped for another link, what was found is still kept with this one.
                withContext(NonCancellable) {
                    uiState = uiState.copy(history = historyStore.update(link.url, result.copy(tracks = tracks.toList())))
                }
            }
        }
    }

    /** The links saved with [entry] that are still good: found with exact matching as it's set now. */
    private fun savedDestinations(entry: HistoryEntry): Map<Destination, PreparedLink> =
        uiState.destinations.mapNotNull { destination ->
            entry.destinationLinks[destination.key]?.takeIf { it.matchingEnabled == uiState.exactMatch }?.let { destination to it }
        }.toMap()

    /** A destination or exact-match preference change prepares the current result again. */
    private fun prepareResultDestination() {
        cancelTrackWork()
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
        // A share sheet action asked to share the converted link rather than open it.
        if (pendingOpen && uiState.afterLookup != AfterLookup.OPEN) {
            pendingOpen = false
            val url = prepareDestination(uiState.resultDestination) ?: return
            effectChannel.send(Effect.Share(url))
            return
        }
        // A playlist can't open as one in another app, so its songs show here to pick from,
        // unless the user already picked where it goes.
        val opensWhole = uiState.link?.youtubePlaylistOn(uiState.resultDestination.matchService) != null
        if (pendingOpen && uiState.selectedDestination == null && !opensWhole &&
            uiState.result?.type == ItemType.PLAYLIST && uiState.result?.tracks.orEmpty().isNotEmpty()
        ) {
            pendingOpen = false
            uiState = uiState.copy(handingOff = false)
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

    private var trackJob: Job? = null

    /**
     * Plays the result's songs as one queue in YouTube Music or YouTube, [MAX_QUEUE] at most, which
     * is all YouTube takes: a longer list plays in parts, [from] the first song of one.
     */
    fun playAll(from: Int) {
        val destination = uiState.resultDestination
        val service = destination.matchService ?: return
        val tracks = uiState.result?.tracks.orEmpty().drop(from).take(MAX_QUEUE)
        cancelTrackWork()
        trackJob = viewModelScope.launch {
            uiState = uiState.copy(queueProgress = 0 to tracks.size, queueFrom = from)
            try {
                val url = matcher.youtubeQueue(service, tracks) { looked -> uiState = uiState.copy(queueProgress = looked to tracks.size) }
                if (url == null) showError(AppError.NOT_FOUND) else effectChannel.send(Effect.Open(url, destination.packageName, finishAfterOpen = false))
            } finally {
                if (isActive) uiState = uiState.copy(queueProgress = null)
            }
        }
    }

    /** Opens one of a playlist's songs where the result goes, matched like a song of its own. */
    fun openTrack(track: MusicMetadata) {
        val destination = uiState.resultDestination
        cancelTrackWork()
        trackJob = viewModelScope.launch {
            // A song from a playlist on the same service is opened by its own link.
            track.url?.takeIf { destination.matchService != null && MusicLinks.serviceFor(it) == destination.matchService }?.let { url ->
                effectChannel.send(Effect.Open(destination.adapt(url).forSharing(), destination.packageName, finishAfterOpen = false))
                return@launch
            }
            // YouTube and YouTube Music play the same videos, so one opens as itself in the other.
            track.url?.let { MusicLinks.linkFor(it) }?.youtubeVideoOn(destination.matchService)?.let { url ->
                effectChannel.send(Effect.Open(destination.adapt(url).forSharing(), destination.packageName, finishAfterOpen = false))
                return@launch
            }
            val exactUrl = destination.matchService?.takeIf { uiState.exactMatch }?.let { service ->
                uiState = uiState.copy(isMatching = true)
                try {
                    matcher.find(service, track)
                } finally {
                    if (isActive) uiState = uiState.copy(isMatching = false)
                }
            }
            val url = exactUrl?.let(destination::adapt) ?: destination.searchUrl(searchQuery(track))
            effectChannel.send(Effect.Open(url.forSharing(), destination.packageName, finishAfterOpen = false))
        }
    }

    /** Opens the result from a button tap; the picker passes the destination chosen for this link. */
    fun openResult(destination: Destination? = null, finishAfterOpen: Boolean = false) {
        val chosen = destination ?: uiState.resultDestination
        destination?.let(::rememberLastApp)
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

    private var lyricsJob: Job? = null
    private var followJob: Job? = null

    /** The last song Crosstune heard, so its words can follow on from where it was heard. */
    private var heardSong: MusicMetadata? = null

    /** Where [heardSong] is, as far as hearing it can tell, kept steady against what one hearing gets wrong. */
    private var heardClock: PlaybackClock? = null

    /** A place heard far from where the words are, held until a second hearing agrees with it. */
    private var unconfirmed: PlaybackClock? = null

    /**
     * Fetches the shown song's words and shows them. A song with no words of its own says so
     * rather than showing nothing, so it isn't read as a lookup that hasn't answered. Timed words
     * follow the song while something says where it is.
     */
    fun showLyrics() {
        // Retry asks again for the words shown, which after listening along may be another song than the result's.
        showLyricsFor(uiState.lyricsFor ?: uiState.result ?: return)
    }

    private fun showLyricsFor(song: MusicMetadata) {
        // Only a song has words, and one whose artist is unknown can't be looked up safely.
        val finder = lyricsFinder ?: return
        if (song.type != ItemType.TRACK || song.artist.isBlank()) return
        // Already showing these words, or on the way, so a second tap doesn't fetch them again. A lookup
        // that failed is worth asking again, so Retry is the one case that does fetch.
        if (uiState.lyricsFor == song && (uiState.lyrics.isNotEmpty() || uiState.isLoadingLyrics)) return
        lyricsJob?.cancel()
        stopFollowing()
        lyricsJob = viewModelScope.launch {
            uiState = uiState.copy(
                lyricsFor = song, lyrics = "", lyricLines = emptyList(), lyricsFailed = false, isLoadingLyrics = true,
                canFollowApps = playback?.hasAccess() == true
            )
            try {
                // A lookup that failed says so, rather than claiming the song has no words.
                when (val answer = finder.lyricsOf(song)) {
                    is LyricsFinder.Lyrics.Found -> uiState = uiState.copy(lyrics = answer.words, lyricLines = answer.lines, lyricsFailed = false)
                    LyricsFinder.Lyrics.None -> uiState = uiState.copy(lyrics = "", lyricsFailed = false)
                    LyricsFinder.Lyrics.Unavailable -> uiState = uiState.copy(lyrics = "", lyricsFailed = true)
                }
            } finally {
                if (isActive) uiState = uiState.copy(isLoadingLyrics = false)
            }
            follow(song)
        }
        // A song Crosstune just heard is still playing nearby, so it keeps listening, to stay in time with it.
        if (heardSong?.let { sameSong(it, song) } == true) listenAlong()
    }

    private fun sameSong(one: MusicMetadata, other: MusicMetadata) =
        SongNames.same(one.title, other.title) && SongNames.sameArtist(one.artist, other.artist)

    /** Where [song] is, as Crosstune last heard it, when that's the song it heard. */
    private fun heardFollowing(song: MusicMetadata): Following? =
        heardClock?.takeIf { heardSong?.let { sameSong(it, song) } == true }?.let { Following(it, app = null, canSeek = false) }

    private var alongJob: Job? = null

    /**
     * Keeps the microphone listening along with the words: every little while it asks what's
     * playing nearby and how far into it, so a pause, a skip or the next song is followed. Only
     * while the words are on screen and Crosstune is in sight.
     */
    fun listenAlong() {
        if (alongJob?.isActive == true) return
        uiState = uiState.copy(listeningAlong = true)
        alongJob = viewModelScope.launch {
            while (isActive) {
                when (val heard = listener.listen()) {
                    is Heard.Song -> {
                        heardAlong(heard)
                        delay(listenAlongPauseMs)
                    }
                    // Nothing heard, the music has most likely stopped, so the words stop where they are.
                    Heard.Nothing -> heardClock?.takeIf { it.playing }?.let { clock ->
                        heardClock = clock.copy(positionMs = clock.positionAt(now()), atMs = now(), playing = false)
                        keepInTime()
                    }
                    is Heard.Failed -> {
                        // Without the microphone there's nothing to listen with; anything else, e.g. the
                        // network or Shazam being busy, is tried again in a while.
                        if (heard.error == AppError.MICROPHONE) {
                            stopListeningAlong()
                            uiState = uiState.copy(error = AppError.MICROPHONE, canRetry = false)
                            return@launch
                        }
                        delay(listenAlongPauseMs * 2)
                    }
                }
            }
        }
    }

    /** The song heard: the one shown keeps in time with it; another one takes over the words. */
    private fun heardAlong(heard: Heard.Song) {
        val song = heard.metadata
        val shown = uiState.lyricsFor
        if (heardSong?.let { sameSong(it, song) } != true || shown == null || !sameSong(song, shown)) {
            heardSong = song
            heardClock = heard.clock
            unconfirmed = null
            if (shown == null || !sameSong(song, shown)) {
                remember(heard)
                return showLyricsFor(song)
            }
        } else {
            heardClock = steadied(heardClock, heard.clock ?: return)
        }
        keepInTime()
    }

    /**
     * A song heard along the way joins Recent, as one Crosstune listened for does: by its Apple Music
     * link when Shazam knows it, or else by its name. Nothing needs looking up to keep it.
     */
    private fun remember(heard: Heard.Song) {
        val link = heard.appleMusicId?.let { (MusicLinks.parse("https://www.shazam.com/song/$it") as? LinkInput.Link)?.link }
            ?: MusicLinks.recognizedSong(heard.metadata).let { MusicLink(null, ItemType.TRACK, it.url, it.url) }
        uiState = uiState.copy(history = historyStore.add(HistoryEntry(link, heard.metadata)))
    }

    /**
     * Where the song is, from a new hearing of it. Each hearing is off by a moment, so one close to
     * where the words already are changes nothing; one far off, e.g. the chorus heard as its other
     * time, is only believed once a second hearing agrees, as a skip would. A song that had stopped
     * starts again from where it's heard.
     */
    private fun steadied(current: PlaybackClock?, heard: PlaybackClock): PlaybackClock {
        if (current == null || !current.playing) return heard.also { unconfirmed = null }
        val at = now()
        if (abs(heard.positionAt(at) - current.positionAt(at)) <= STEADY_MS) return current.also { unconfirmed = null }
        val earlier = unconfirmed
        if (earlier != null && abs(heard.positionAt(at) - earlier.positionAt(at)) <= STEADY_MS) return heard.also { unconfirmed = null }
        unconfirmed = heard
        return current
    }

    /** The words shown keep to where the song was heard, unless a music app playing it says, more exactly. */
    private fun keepInTime() {
        val shown = uiState.lyricsFor ?: return
        if (uiState.following?.app == null) uiState = uiState.copy(following = heardFollowing(shown))
    }

    fun stopListeningAlong() {
        pauseListeningAlong()
        uiState = uiState.copy(listeningAlong = false)
    }

    /** Crosstune went out of sight: the microphone stops, and starts again when it's back. */
    fun pauseListeningAlong() {
        alongJob?.cancel()
        alongJob = null
    }

    fun resumeListeningAlong() {
        if (uiState.listeningAlong) listenAlong()
    }

    /**
     * Follows [song] as it plays, for its timed words: in the music app playing it, or else from
     * where Crosstune heard it, which can't tell a pause or a skip but is close while it plays on.
     */
    private fun follow(song: MusicMetadata) {
        stopFollowing()
        if (uiState.lyricLines.isEmpty()) return
        uiState = uiState.copy(following = heardFollowing(song))
        val source = playback?.takeIf { it.hasAccess() } ?: return
        followJob = viewModelScope.launch {
            source.follow(song).collect { playing -> uiState = uiState.copy(following = playing ?: heardFollowing(song)) }
        }
    }

    private fun stopFollowing() {
        followJob?.cancel()
        followJob = null
        uiState = uiState.copy(following = null)
    }

    /** Set once Android's page for the access was opened, so coming back without it can say what to try. */
    private var askedForAccess = false

    /**
     * Back from Android's settings, where following music apps may have just been allowed. Back
     * without it, the steps show, since Android may have kept the switch from turning on.
     */
    fun refreshFollowing() {
        val access = playback?.hasAccess() == true
        if (access) {
            askedForAccess = false
            uiState = uiState.copy(followHelp = false)
        } else if (askedForAccess) {
            uiState = uiState.copy(followHelp = true)
        }
        if (access == uiState.canFollowApps) return
        uiState = uiState.copy(canFollowApps = access)
        uiState.lyricsFor?.takeIf { !uiState.isLoadingLyrics }?.let(::follow)
    }

    /**
     * Allow, for the words to follow music apps: true when Android's page for it can just open.
     * Where Android restricts it first, the steps show instead, that one included.
     */
    fun allowFollowing(): Boolean {
        if (playback?.restricted() == true) {
            uiState = uiState.copy(followHelp = true)
            return false
        }
        askedForAccess = true
        return true
    }

    /** Android's page for the access is opening, from the steps. */
    fun openingFollowAccess() {
        askedForAccess = true
    }

    fun dismissFollowHelp() {
        askedForAccess = false
        uiState = uiState.copy(followHelp = false)
    }

    fun selectPalette(palette: Palette) {
        preferences.edit { putString(KEY_PALETTE, palette.name) }
        uiState = uiState.copy(palette = palette)
    }

    fun setPureBlack(on: Boolean) {
        preferences.edit { putBoolean(KEY_PURE_BLACK, on) }
        uiState = uiState.copy(pureBlack = on)
    }

    fun selectTheme(theme: ThemeMode) {
        preferences.edit { putString(KEY_THEME, theme.name) }
        uiState = uiState.copy(theme = theme)
    }

    /** Moves the music app playing the song to a line of its words. */
    fun seekLyrics(positionMs: Long) {
        val song = uiState.lyricsFor ?: return
        if (uiState.following?.canSeek != true) return
        playback?.seekTo(song, positionMs)
    }

    /** Closes the words. The next song starts without them, rather than showing the last one's. */
    fun dismissLyrics() {
        lyricsJob?.cancel()
        lyricsJob = null
        stopFollowing()
        stopListeningAlong()
        askedForAccess = false
        uiState = uiState.copy(
            lyricsFor = null, lyrics = "", lyricLines = emptyList(), lyricsFailed = false, isLoadingLyrics = false, followHelp = false
        )
    }

    private suspend fun prepareDestination(destination: Destination): String? {
        val metadata = uiState.result ?: return null
        val service = destination.matchService
        // The link already belongs to the destination's service: open it as is instead of searching.
        uiState.link?.takeIf { service != null && it.service == service }?.let { link ->
            return destination.adapt(link.url)
        }
        // YouTube and YouTube Music share playlists and videos, so one opens as itself in the other.
        uiState.link?.youtubePlaylistOn(service)?.let { return destination.adapt(it) }
        uiState.link?.takeIf { metadata.type == ItemType.TRACK }?.youtubeVideoOn(service)?.let { return destination.adapt(it) }
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
        cancelTrackWork()
        pendingOpen = false
        uiState = uiState.copy(showDestinationPicker = false, isMatching = false)
        val finishAfterOpen = lastRequest?.second == true
        effectChannel.trySend(Effect.Open(link.url, link.service?.packageName, finishAfterOpen))
    }

    fun dismissDestinationPicker() {
        uiState = uiState.copy(showDestinationPicker = false)
        // The result is now available for manual actions using its displayed destination.
        prepareResultDestination()
    }

    private fun selectHistoryEntry(entry: HistoryEntry) {
        cancelContentWork()
        // The entry replaces whatever was being looked up, including a link from another app.
        lastRequest = null
        pendingOpen = false
        uiState = uiState.copy(
            linkText = fieldText(entry.link, entry.metadata),
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
        loadTrackCovers()
        prepareResultDestination()
    }

    fun openHistoryEntry(entry: HistoryEntry) = openSaved(entry, finishAfterOpen = false)

    /**
     * A Recent song from the widget's ▶: opened in the user's app like Recent's own ▶, from what
     * was saved, with Crosstune going away after. Gone from Recent meanwhile, it's looked up again.
     */
    fun openRecent(url: String?) {
        val entry = uiState.history.firstOrNull { it.link.url == url } ?: return resolveIncoming(url)
        uiState = uiState.copy(handlingIncomingLink = true, handingOff = true, leaveSettings = true)
        openSaved(entry, finishAfterOpen = true)
    }

    private fun openSaved(entry: HistoryEntry, finishAfterOpen: Boolean) {
        cancelContentWork()
        job = viewModelScope.launch {
            val destination = destinationFor(entry)
            val url = urlForHistory(entry, destination) ?: return@launch
            effectChannel.send(Effect.Open(url, destination.packageName, finishAfterOpen))
        }
    }

    /** Recent's Open uses the saved default, not whatever result is on screen. */
    private fun destinationFor(entry: HistoryEntry): Destination =
        uiState.ruleFor(entry.link) ?: uiState.defaultDestination

    private suspend fun urlForHistory(entry: HistoryEntry, destination: Destination): String? {
        val service = destination.matchService
        if (service != null && service == entry.link.service) return destination.adapt(entry.link.url)
        // YouTube and YouTube Music play the same videos, so one opens as itself in the other.
        if (entry.metadata.type == ItemType.TRACK) {
            entry.link.youtubeVideoOn(service)?.let { return destination.adapt(it) }
        }
        // A playlist plays as a queue of its first songs, as the Play button in the app does, not as a search.
        // An album has its own page there, which the exact match below finds.
        if (entry.metadata.type == ItemType.PLAYLIST && entry.metadata.tracks.isNotEmpty() && (service == MusicService.YOUTUBE_MUSIC || service == MusicService.YOUTUBE)) {
            entry.link.youtubePlaylistOn(service)?.let { return destination.adapt(it) }
            matcher.youtubeQueue(service, entry.metadata.tracks.take(MAX_QUEUE)) {}?.let { return it }
        }
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

    /** The result's songs as "Artist - Title" lines, which song list transfer sites read. */
    fun songList(): String? = uiState.result?.tracks?.takeIf { it.isNotEmpty() }?.joinToString("\n") { track ->
        if (track.artist.isBlank()) track.title else "${track.artist} - ${track.title}"
    }

    fun destinationUrl(): String? {
        val destination = uiState.resultDestination
        uiState.link?.takeIf { it.service != null && destination.matchService == it.service }?.let { return destination.adapt(it.url) }
        // YouTube and YouTube Music play the same videos, so one is shared as itself in the other.
        uiState.link?.takeIf { uiState.result?.type == ItemType.TRACK }?.youtubeVideoOn(destination.matchService)
            ?.let { return destination.adapt(it) }
        // Every shown result has its link prepared before Copy and Share are enabled.
        return uiState.destinationUrls[destination]?.url?.forSharing()
    }


    fun clear() {
        cancelContentWork()
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

    /** A new song or an empty screen replaces every operation owned by the previous content. */
    private fun cancelContentWork() {
        job?.cancel()
        job = null
        lastRequest = null
        pendingOpen = false
        retryListens = false
        dismissSongSearch()
        dismissLyrics()
        cancelTrackWork()
        coverJob?.cancel()
        coverJob = null
        uiState = uiState.copy(isLoading = false, isMatching = false, listening = false, canRetry = false)
    }

    private fun cancelTrackWork() {
        trackJob?.cancel()
        trackJob = null
        uiState = uiState.copy(queueProgress = null, isMatching = false)
    }

    private fun rememberDestination(destination: Destination, prepared: PreparedLink) {
        val history = uiState.link?.let { historyStore.remember(it.url, destination.key, prepared) } ?: uiState.history
        uiState = uiState.copy(destinationUrls = uiState.destinationUrls + (destination to prepared), history = history)
    }

    fun selectResultDestination(destination: Destination) {
        rememberLastApp(destination)
        uiState = uiState.copy(selectedDestination = destination)
        prepareResultDestination()
    }

    /**
     * Asking which app, or showing the song first, there's no default to make; the app picked last
     * is offered next time instead. It's stored as the default, which is what the Open button,
     * Recent and the share sheet use, without changing what tapped links do.
     */
    private fun rememberLastApp(destination: Destination) {
        if (uiState.linkMode == LinkMode.OPEN) return
        destinationStore.setDefault(destination)
        uiState = uiState.withDestinations()
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
        retryListens = false
        uiState = uiState.copy(isLoading = false, isMatching = false, error = error, canRetry = false, handingOff = false)
    }

    companion object {
        const val PREFERENCES_NAME = "crosstune_preferences"
        /** A song without any picture can wait a little longer for its cover than a video with a frame. */
        private const val COVER_TIMEOUT_MS = 5_000L
        private const val VIDEO_COVER_TIMEOUT_MS = 2_000L
        private const val KEY_LINK_SETTINGS_HELPER_DISMISSED = "link_settings_helper_dismissed"
        private const val KEY_ASK_EACH_TIME = "ask_each_time"
        private const val KEY_EXACT_MATCH = "exact_match"
        private const val KEY_CLEAN_LINKS = "clean_links"
        private const val KEY_SHARE_SHEET_APPS = "share_sheet_apps"
        private const val KEY_SHOW_SONG_FIRST = "show_song_first"
        private const val KEY_THEME = "theme"
        private const val KEY_PALETTE = "palette"
        private const val KEY_PURE_BLACK = "pure_black"

        /** How far apart two hearings of a song can be and still be the same place in it. */
        const val STEADY_MS = 2_000L

        /** Long enough not to keep Shazam busy, short enough to catch a skip or the next song soon. */
        const val LISTEN_ALONG_PAUSE_MS = 8_000L
        /** YouTube makes temporary playlists of up to 50 videos. */
        const val MAX_QUEUE = 50
        /** Songs whose covers are looked up when a playlist shows: Spotify lists 100, the first 30 with covers. */
        private const val MAX_COVER_LOOKUPS = 100
        private const val KEY_ONLY_MUSIC_VIDEOS = "only_music_videos"
        private const val KEY_SOURCES_PRESELECTED = "sources_preselected"
        private const val KEY_SETUP_COMPLETE = "setup_complete"

        /** Preferences only an install from before first-run setup can have. */
        private val LEGACY_KEYS = listOf(
            KEY_LINK_SETTINGS_HELPER_DISMISSED, "default_target", "history", KEY_ASK_EACH_TIME, KEY_EXACT_MATCH
        )
    }
}
