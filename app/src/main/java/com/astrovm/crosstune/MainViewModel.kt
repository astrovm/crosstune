package com.astrovm.crosstune

import android.content.SharedPreferences
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astrovm.crosstune.ui.theme.Palette
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal sealed interface Effect {
    /**
     * [packageName] is null for custom destinations, which open in whatever app handles the URL.
     * [atMs] is when it was asked, so one that waited while Crosstune was away isn't opened long after.
     */
    data class Open(val url: String, val packageName: String?, val finishAfterOpen: Boolean, val atMs: Long = SystemClock.elapsedRealtime()) : Effect
    data class Share(val url: String) : Effect

    /**
     * Brings [packageName] forward as it is, for a song it already plays, which opening it on the song would restart.
     * The player covers where an error would show, so it's left unsaid there.
     */
    data class Show(val packageName: String, val finishAfterOpen: Boolean, val saysWhenItFails: Boolean = true) : Effect

    /** Shazam can't be reached, so another app that names songs, when there's one, listens instead. */
    data object RecognizeElsewhere : Effect
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
    playback: PlaybackSource?,
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
    /**
     * How long listening along rests after hearing nothing, doubled each time it hears nothing again,
     * up to [QUIET_PAUSE_DOUBLINGS] times, so a paused song doesn't keep the microphone on.
     */
    private val quietPauseMs: Long = LISTEN_ALONG_PAUSE_MS,
    /** The clock a song's place is kept on, the one that counts since the phone started. */
    private val now: () -> Long = SystemClock::elapsedRealtime,
    /** Where a song's words are translated; nullable so tests need no translation service. */
    private val translator: Translator? = null,
    /** The language words are translated into: the app's. */
    private val translationLanguage: () -> String = { Translator.languageOf(java.util.Locale.getDefault()) },
    /** The ChatGPT account the AI features use; nullable so tests need none. */
    private val chatGpt: ChatGpt? = null,
    /** What the AI features ask ChatGPT, with that account. */
    private val tutor: ChatGptTutor? = null,
    /** The time of day, for when saved lines are next practised. */
    private val wallClock: () -> Long = System::currentTimeMillis,
    /** Where a song's chords come from; nullable so tests need no chord sites. */
    private val chordFinder: ChordFinder? = null,
    /** Which language a song's words are in; nullable so tests need none. */
    private val lyricsLanguage: LyricsLanguage? = null,
    /** Setup in one tap, for people who run Shizuku; nullable so tests need none. */
    private val oneTap: OneTapSetup? = null,
    /** How a Japanese song's kanji are sung, which a dictionary can't say; nullable so tests need none. */
    private val sungReadings: SungReadings? = null,
    /** UniDic, the Japanese dictionary fetched once to read kanji better; nullable so tests need none. */
    private val uniDic: UniDic? = null,
    /** Where a newer Crosstune is looked for; null for a copy an app store updates. */
    private val updates: UpdateChecker? = null,
    /** Calibration may use the microphone only when its permission has already been granted. */
    private val mayCalibrate: () -> Boolean = { false },
    /** Hears the words sung in what the phone plays, to line the timed words up to them; nullable so tests need none. */
    private val singing: SingingHearing? = null,
    /** Where reports on the words go, kept private; nullable so tests need none. */
    private val reports: ReportSender? = null,
    /** Whether the microphone may be used, which Android says; asked again whenever Crosstune comes back. */
    private val microphoneAccess: () -> MicAccess = { MicAccess.ALLOWED },
    /** The kind of phone, whose settings pages the steps to fix things show. */
    phone: Phone,
    /** Where the words of the songs shown lately are kept for after the app's closed; nullable so tests need none. */
    private val keptOnDisk: LookupCache? = null,
    /** Whether a song's lyrics are looked up as its card shows, see [lookAhead]. */
    private val lookAhead: Boolean = true,
    /** Plays a playlist longer than one queue through, see [playAll]. */
    private val playQueue: PlayQueue? = null,
    private val queueOrder: QueueOrder? = null,
    /** Pauses the music after a while, see [playerSleep]. */
    private val sleepTimer: SleepTimer? = null
) : ViewModel() {
    /**
     * An app opened on a song is covered rather than left, once Android lets Crosstune show over other
     * apps: on a TV, where brought in front of it, YouTube stops playing, and on a phone, when the app
     * only plays in front, as YouTube Music without Premium, which stops as soon as it's left.
     */
    private val playback: PlaybackSource? = playback?.let { source ->
        object : PlaybackSource by source {
            // Covered as it's opened, while Crosstune's still in sight, which Android wants for the cover to listen along.
            override fun openSong(app: String, link: String): Boolean {
                if (uiState.canFloatOverApps && (phone == Phone.TV || source.playsOnlyInFront(app))) uiState = uiState.copy(coveringApp = true)
                return source.openSong(app, link).also { if (!it) uncover() }
            }

            // Played on from behind where it can be; one that only plays in front is brought there for it, covered the same way.
            override fun playPause(song: MusicMetadata, play: Boolean) {
                leftJob?.cancel()
                if (!play) return source.playPause(song, false)
                viewModelScope.launch {
                    if (source.resume(song)) return@launch
                    if (uiState.canFloatOverApps) uiState = uiState.copy(coveringApp = true)
                    if (!source.bringToFront(song)) uncover()
                }
            }

            // Already in front of it, the words of the song just played show there.
            override fun comesBack() = if (!uiState.coveringApp) source.comesBack() else if (uiState.lyricsFor == null) playerLyrics() else Unit
        }
    }


    private val historyStore = HistoryStore(preferences)
    private val savedLinesStore = SavedLinesStore(preferences)
    private val syncStore = LyricsSyncStore(preferences)
    private val leadStore = LeadStore(preferences)
    private val gapStore = ShazamGapStore(preferences)
    private val sungStore = SungStore(preferences)
    private val autoReports = AutoReports(preferences)

    /** The timed words as found, before the lines put in time by hand, [syncPoints], move them. */
    private var foundLines: List<LyricLine> = emptyList()
    private var found: LyricsFinder.Lyrics.Found? = null

    /** The words found in Latin letters, written in Japanese or Korean by ChatGPT, line by line, once they are. */
    private var restored: List<String>? = null

    /** [lines] as they show: in the song's script when found in Latin letters and written so, timed as found. */
    private fun shown(lines: List<LyricLine>): List<LyricLine> =
        // Written otherwise, the line's words are no longer the ones timed.
        restored?.let { written -> lines.mapIndexed { index, line -> written.getOrNull(index)?.let { line.copy(text = it, words = emptyList()) } ?: line } } ?: lines
    private var syncPoints: List<SyncPoint> = emptyList()

    /** The words of the songs shown lately, by recording, the last one shown last. */
    private val kept = object : LinkedHashMap<String, KeptWords>(KEPT_SONGS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, KeptWords>) = size > KEPT_SONGS
    }
    private val destinationStore = DestinationStore(preferences, interception::isInstalled)

    init {
        migrateExistingInstall()
        migrateLinkMode()
        migrateExactMatch()
    }

    /** The last look at what Android says about apps and links, null until the first one finishes. */
    private var linkState: LinkState? = null
    private var systemJob: Job? = null
    /** What [UiState.playingNow] is made of: the song heard playing, and the one an app is followed on. */
    private var heardNow: MusicMetadata? = null
    private var followedNow: Pair<MusicMetadata, Following>? = null

    /** The screen's state, which the parts work is handed to change too. */
    private val screen = mutableStateOf(
        UiState(
            linksInCrosstune = preferences.getBoolean(KEY_LINKS_IN_CROSSTUNE, false),
            playRightAway = preferences.getBoolean(KEY_PLAY_RIGHT_AWAY, true),
            cleanLinks = preferences.getBoolean(KEY_CLEAN_LINKS, true),
            shareSheetApps = preferences.getBoolean(KEY_SHARE_SHEET_APPS, true),
            onlyMusicVideos = preferences.getBoolean(KEY_ONLY_MUSIC_VIDEOS, true),
            theme = ThemeMode.entries.firstOrNull { it.name == preferences.getString(KEY_THEME, null) } ?: ThemeMode.SYSTEM,
            palette = savedPalette(preferences.getString(KEY_PALETTE, null)),
            pureBlack = preferences.getBoolean(KEY_PURE_BLACK, false),
            playingBar = preferences.getBoolean(KEY_PLAYING_BAR, true),
            linesEarlyMs = preferences.getLong(KEY_LINES_EARLY, LINES_EARLY_MS),
            nextLineFirst = preferences.getBoolean(KEY_NEXT_LINE_FIRST, false),
            visuals = preferences.getBoolean(KEY_VISUALS, true),
            visualsShade = preferences.getFloat(KEY_VISUALS_SHADE, DEFAULT_VISUALS_SHADE),
            visualsSettings = VisualsStore(preferences).load(),
            canFollowApps = playback?.hasAccess() == true,
            followRestricted = playback?.restricted() == true,
            notFoundAction = NotFoundAction.entries.firstOrNull { it.name == preferences.getString(KEY_NOT_FOUND, null) } ?: NotFoundAction.ASK,
            chords = ChordsState(on = preferences.getBoolean(KEY_CHORDS, false)),
            autoReports = autoReports.allowed,
            cardTypes = loadCardTypes(preferences),
            learning = Learning(
                readings = preferences.getBoolean(KEY_READINGS, true),
                romanized = preferences.getBoolean(KEY_ROMANIZED, false),
                translation = preferences.getBoolean(KEY_TRANSLATION, false),
                chatGptTranslation = preferences.getBoolean(KEY_TRANSLATION_CHATGPT, true),
                into = preferences.getString(KEY_TRANSLATE_INTO, null)?.let(AppLanguage::translationTag),
                untranslated = preferences.getStringSet(KEY_UNTRANSLATED, null).orEmpty().filter { it in AppLanguage.untranslatedTags }.toSet(),
                readingScale = preferences.getFloat(KEY_READING_SCALE, READING_SCALE).coerceIn(MIN_READING_SCALE, MAX_READING_SCALE),
                wordsScale = preferences.getFloat(KEY_WORDS_SCALE, 1f).coerceIn(MIN_WORDS_SCALE, MAX_WORDS_SCALE),
                // Until it's set, it's as big as the words, as it was when they went together.
                translationScale = preferences.getFloat(KEY_TRANSLATION_SCALE, preferences.getFloat(KEY_WORDS_SCALE, 1f)).coerceIn(MIN_WORDS_SCALE, MAX_WORDS_SCALE),
                centered = if (preferences.contains(KEY_WORDS_CENTERED)) preferences.getBoolean(KEY_WORDS_CENTERED, false) else null,
                wordsAs = WordsAs.entries.firstOrNull { it.name == preferences.getString(KEY_WORDS_AS, null) } ?: WordsAs.WRITTEN,
                romajiTyped = preferences.getBoolean(KEY_ROMAJI_TYPED, false)
            ),
            floating = FloatingOptions(
                background = preferences.getFloat(KEY_FLOATING_BACKGROUND, FloatingOptions().background),
                scale = preferences.getFloat(KEY_FLOATING_SCALE, 1f),
                previousLine = preferences.getBoolean(KEY_FLOATING_PREVIOUS_LINE, false),
                nextLine = preferences.getBoolean(KEY_FLOATING_NEXT_LINE, true),
                locked = preferences.getBoolean(KEY_FLOATING_LOCKED, false),
                left = preferences.getInt(KEY_FLOATING_LEFT, -1),
                top = preferences.getInt(KEY_FLOATING_TOP, -1),
                visuals = preferences.getBoolean(KEY_FLOATING_VISUALS, false)
            ),
            showLinkSettingsHelper = !preferences.getBoolean(KEY_LINK_SETTINGS_HELPER_DISMISSED, false),
            dismissedNotices = preferences.getStringSet(KEY_DISMISSED_NOTICES, null).orEmpty().toSet(),
            history = historyStore.load(),
            savedLines = savedLinesStore.load(),
            systemStateKnown = false,
            phone = phone,
            microphone = microphoneAccess()
        ).withOneTap().withDestinations()
    )

    init {
        // The timer is the app's, so one set from another screen, or before Crosstune was closed, shows too.
        sleepTimer?.let { timer -> viewModelScope.launch { timer.set.collect { uiState = uiState.copy(sleep = it) } } }
    }

    var uiState by screen
        private set

    /** Kept lines practised as cards. */
    private val cards = PracticeCards(screen, viewModelScope, playback, matcher, savedLinesStore, wallClock, now)

    /** Shizuku can start or stop at any time, which takes setting up in one tap with it. */
    private val stopWatchingOneTap = oneTap?.watch { uiState = uiState.withOneTap() }

    /** Whether there's one tap now, and through what, or Shizuku stopped, which takes it away. */
    private fun UiState.withOneTap(): UiState =
        copy(canSetUpInOneTap = oneTap?.available() == true, oneTapRoot = oneTap?.root() == true, shizukuStopped = oneTap?.shizukuStopped() == true)

    private val visualsStore = VisualsStore(preferences)

    /** Visuals settings changed, here or by the visuals themselves, e.g. one found too slow, show at once. */
    private val visualsChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in VisualsStore.KEYS) uiState = uiState.copy(visualsSettings = visualsStore.load())
    }.also(preferences::registerOnSharedPreferenceChangeListener)

    override fun onCleared() {
        stopWatchingOneTap?.invoke()
        preferences.unregisterOnSharedPreferenceChangeListener(visualsChanged)
    }

    /**
     * Does all there is to fix, through Shizuku or root: stops the apps in the way, allows the
     * links, the microphone, and lyrics following music apps. What it couldn't do is still there to fix by hand.
     */
    fun setUpInOneTap() {
        val setup = oneTap ?: return
        if (uiState.settingUpInOneTap) return
        val state = uiState
        uiState = uiState.copy(settingUpInOneTap = true, oneTapFailed = false)
        viewModelScope.launch {
            val worked = inOneTap(
                setup, apps = state.blockingApps.orEmpty().map { it.packageName }, links = state.interceptsAnything,
                follow = !state.canFollowApps, microphone = state.microphone != MicAccess.ALLOWED
            )
            uiState = uiState.copy(settingUpInOneTap = false, oneTapFailed = !worked)
            refreshSystemState()
            refreshFollowing()
        }
    }

    /**
     * Fixes [fix] in one tap, through Shizuku or root, after setup as during it, rather than by hand,
     * and [then] does what it was for; what it couldn't is left to the steps. Whether there's one tap to try.
     */
    fun fixInOneTap(fix: OneTapFix, then: () -> Unit = {}): Boolean {
        val setup = oneTap ?: return false
        if (!uiState.canSetUpInOneTap) return false
        if (uiState.fixingInOneTap != null) return true
        uiState = uiState.copy(fixingInOneTap = fix)
        viewModelScope.launch {
            val worked = inOneTap(setup, links = fix == OneTapFix.LINKS, follow = fix == OneTapFix.FOLLOW, floating = fix == OneTapFix.FLOAT)
            val need = if (fix == OneTapFix.LINKS) Need.Links else if (fix == OneTapFix.FOLLOW) Need.Follow else Need.Float
            uiState = uiState.copy(fixingInOneTap = null, fixes = if (worked) uiState.fixes else Fixes(list = uiState.fixes?.list == true, steps = listOf(need), failed = true))
            refreshSystemState()
            refreshFollowing()
            if (worked) then()
        }
        return true
    }

    private suspend fun inOneTap(
        setup: OneTapSetup,
        apps: List<String> = emptyList(),
        links: Boolean,
        follow: Boolean,
        microphone: Boolean = false,
        floating: Boolean = false
    ): Boolean {
        val worked = setup.setUp(apps = apps, links = links, follow = follow, microphone = microphone, floating = floating)
        // Android lets Crosstune follow music apps a moment after it's told to.
        var waits = 0
        while (worked && follow && playback?.hasAccess() != true && waits++ < FOLLOW_WAITS) delay(FOLLOW_WAIT_MS)
        return worked
    }

    /**
     * Versions before first-run setup always intercepted Spotify links. People updating from one
     * keep that, so their links don't silently stop opening in Crosstune.
     */
    private fun migrateExistingInstall() {
        if (preferences.contains(KEY_SETUP_COMPLETE)) return
        // Recorded on a fresh install's first launch too, so data created after it (e.g. history
        // from a link opened right away) is never mistaken for an old install.
        val isExistingInstall = LEGACY_KEYS.any(preferences::contains)
        if (isExistingInstall) interception.setEnabled(MusicService.SPOTIFY, true)
        preferences.edit { putBoolean(KEY_SETUP_COMPLETE, isExistingInstall) }
    }

    /**
     * Links show in Crosstune first for whoever had them ask or show the song first, both of which
     * the song's card does now, and they wait for Play there. Everyone else's go straight to the app.
     */
    private fun migrateLinkMode() {
        if (preferences.contains(KEY_LINKS_IN_CROSSTUNE)) return
        val shownFirst = preferences.getBoolean(KEY_ASK_EACH_TIME, false) || preferences.getBoolean(KEY_SHOW_SONG_FIRST, false)
        preferences.edit {
            putBoolean(KEY_LINKS_IN_CROSSTUNE, shownFirst)
            putBoolean(KEY_PLAY_RIGHT_AWAY, !shownFirst)
        }
    }

    /**
     * Open the exact match was a setting of its own. Turned off, links opening straight in an app
     * opened a search there, which is what not playing right away does now; songs opening in
     * Crosstune are always looked up.
     */
    private fun migrateExactMatch() {
        if (!preferences.contains(KEY_EXACT_MATCH)) return
        val searched = !preferences.getBoolean(KEY_EXACT_MATCH, true) && !preferences.getBoolean(KEY_LINKS_IN_CROSSTUNE, false)
        preferences.edit {
            if (searched) putBoolean(KEY_PLAY_RIGHT_AWAY, false)
            remove(KEY_EXACT_MATCH)
        }
    }

    private val effectChannel = Channel<Effect>(Channel.BUFFERED)
    val effects: Flow<Effect> = effectChannel.receiveAsFlow()

    init {
        // A fresh install opens every source's links from the start, rather than in a setup first.
        if (!preferences.getBoolean(KEY_SETUP_COMPLETE, false)) {
            preferences.edit { putBoolean(KEY_SETUP_COMPLETE, true) }
            preselectSources()
        }
        // Read as the app starts, before anyone can open them, so they show at once as they were left.
        keptOnDisk?.let { disk ->
            viewModelScope.launch {
                val read = withContext(systemDispatcher) { disk.entries().mapNotNull { (key, json) -> KeptWords.from(json)?.let { key to it } } }
                // Any shown since are newer.
                read.forEach { (key, words) -> if (key !in kept) kept[key] = words }
            }
        }
        updates?.let { checker ->
            viewModelScope.launch {
                // Asked first, so what changed on screen meanwhile is kept.
                val update = checker.newer()
                uiState = uiState.copy(update = update)
            }
        }
        chatGpt?.let { account ->
            account.picked = preferences.getString(KEY_CHATGPT_MODEL, null)
            viewModelScope.launch {
                // Read before the state is, which may change while the account's read.
                val email = account.email() ?: return@launch
                uiState = uiState.copy(chatGpt = uiState.chatGpt.copy(email = email))
                loadModels(account)
            }
        }
    }

    private var chatGptJob: Job? = null

    /** Opens the browser to sign in with ChatGPT, and waits for it to come back; [done] once it's over. */
    fun signInWithChatGpt(done: () -> Unit) {
        val account = chatGpt ?: return
        chatGptJob?.cancel()
        uiState = uiState.copy(chatGpt = uiState.chatGpt.copy(signingIn = true, problem = null))
        chatGptJob = viewModelScope.launch {
            try {
                val result = account.signIn(open = { url -> effectChannel.send(Effect.Open(url, packageName = null, finishAfterOpen = false)) })
                val signedIn = result == ChatGptSignInResult.SIGNED_IN
                val email = if (signedIn) account.email() else null
                // Said once, the first time the plan's used.
                val welcome = signedIn && !preferences.getBoolean(KEY_CHATGPT_WELCOMED, false)
                uiState = uiState.copy(chatGpt = ChatGptState(email = email, problem = result.takeUnless { signedIn }, welcome = welcome))
                if (signedIn) loadModels(account)
            } finally {
                // Unless another sign-in took over, which still needs Crosstune kept running.
                if (chatGptJob === coroutineContext[Job]) done()
            }
        }
    }

    /** Stops waiting for the browser, which won't be listened to anymore. */
    fun cancelChatGptSignIn() {
        chatGptJob?.cancel()
        uiState = uiState.copy(chatGpt = uiState.chatGpt.copy(signingIn = false))
    }

    fun dismissChatGptWelcome() {
        preferences.edit { putBoolean(KEY_CHATGPT_WELCOMED, true) }
        uiState = uiState.copy(chatGpt = uiState.chatGpt.copy(welcome = false))
    }

    /** The models [account] offers to pick from in Settings, and the one asked. */
    private suspend fun loadModels(account: ChatGpt) {
        val models = account.models() ?: return
        val model = account.model()
        uiState = uiState.copy(chatGpt = uiState.chatGpt.copy(models = models, model = model))
    }

    /** Asks [slug] from now on: the words are translated again, and lines explained again, by it. */
    fun pickChatGptModel(slug: String) {
        val account = chatGpt ?: return
        preferences.edit { putString(KEY_CHATGPT_MODEL, slug) }
        account.picked = slug
        explanationJob?.cancel()
        songExplanationJob?.cancel()
        uiState = uiState.copy(chatGpt = uiState.chatGpt.copy(model = slug))
        translateAgain(uiState.learning.copy(explanation = null, songExplanation = null))
    }

    fun signOutOfChatGpt() {
        val account = chatGpt ?: return
        chatGptJob?.cancel()
        uiState = uiState.copy(chatGpt = ChatGptState())
        viewModelScope.launch { account.signOut() }
    }

    private var job: Job? = null
    private var lastRequest: Request? = null
    private var pendingOpen = false
    /** A link opened in Crosstune plays as soon as it's found, once. */
    private var pendingPlay = false

    /** What was last looked up, and how, for Try again. */
    private data class Request(val input: LinkInput, val openWhenReady: Boolean, val destination: Destination?, val here: Boolean)

    /**
     * The user's choices and which sources Crosstune opens, which are quick to read, so anything
     * reading them right after a change sees it. What Android says about other apps and links comes
     * from the last look at it, see [loadSystemState].
     */
    private fun UiState.withDestinations(): UiState {
        val destinations = destinationStore.allDestinations()
        val picked = destinationStore.hasDefault(destinations)
        // None picked yet, the first music app on the phone is offered, YouTube, mostly videos, last.
        val installed = linkState?.serviceApps?.keys.orEmpty()
        val offered = destinations.filterIsInstance<Destination.Service>().filter { it.service in installed }.minByOrNull { it.service == MusicService.YOUTUBE }
        return copy(
            defaultDestination = offered?.takeIf { !picked } ?: destinationStore.defaultDestination(destinations),
            destinations = destinations,
            selectedDestination = selectedDestination?.takeIf { it in destinations },
            rules = MusicService.entries.mapNotNull { source -> destinationStore.rule(source, destinations)?.let { source to it } }.toMap(),
            frontendRules = Frontend.SOURCES.mapNotNull { frontend -> destinationStore.rule(frontend, destinations)?.let { frontend to it } }.toMap(),
            intercepted = MusicService.entries.filter { it.canBeSource && interception.isEnabled(it) }.toSet(),
            hasDefault = picked
        ).withLinkState()
    }

    /** What Android last said about apps and links, as it concerns the sources Crosstune opens now. */
    private fun UiState.withLinkState(): UiState {
        val state = linkState ?: return this
        val claiming = state.claimingApps(intercepted)
        return copy(
            systemStateKnown = true,
            installed = state.serviceApps.keys,
            unapprovedHosts = state.unapprovedHosts,
            ownLinksOff = state.ownLinksOff,
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
        uiState = uiState.withDestinations().withOneTap().copy(microphone = microphoneAccess())
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
            pickTheOnlyMusicApp()
        }
    }

    /**
     * With one music app on the phone, but YouTube, which comes with most and is mostly videos,
     * that's where songs play, without asking; it can be changed in settings.
     */
    private fun pickTheOnlyMusicApp() {
        if (uiState.hasDefault) return
        val only = (uiState.installed - MusicService.YOUTUBE).singleOrNull()?.let(Destination::Service)?.takeIf { it in uiState.destinations } ?: return
        selectDefault(only)
    }

    /** Without an app songs play in yet, the first one picked on a song becomes it. */
    private fun adoptDefault(destination: Destination) {
        if (!uiState.hasDefault) selectDefault(destination)
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
            // Signed in, a description, more than a name, is asked of ChatGPT too.
            val describes = usesChatGpt() && words.split(Regex("\\s+")).size >= DESCRIPTION_WORDS
            uiState = uiState.copy(
                isSearchingSongs = false,
                isAskingForSongs = describes,
                songSearch = songs,
                songsFromChatGpt = 0,
                songSearchQuery = words,
                error = null
            )
            if (!describes) return@launch
            val named = try {
                found(searcher, tutor?.songsFor(words).orEmpty())
            } finally {
                if (isActive) uiState = uiState.copy(isAskingForSongs = false)
            }
            val rest = songs.filterNot { song -> named.any { SongNames.same(it.title, song.title) && SongNames.sameArtist(it.artist, song.artist) } }
            uiState = uiState.copy(songSearch = named + rest, songsFromChatGpt = named.size)
        }
    }

    /**
     * The songs [named], as title to artist, each looked up all at once, so they're real songs with
     * their links, in the order named; those not found are left out.
     */
    private suspend fun found(searcher: SongSearcher, named: List<Pair<String, String>>): List<MusicMetadata> = coroutineScope {
        named.map { (title, artist) ->
            async {
                // Titles and artists swapped happen.
                searcher.search("$title $artist").firstOrNull { found ->
                    (SongNames.same(found.title, title) && SongNames.sameArtist(found.artist, artist)) ||
                        (SongNames.same(found.title, artist) && SongNames.sameArtist(found.artist, title))
                }
            }
        }.awaitAll().filterNotNull().distinctBy { SongNames.normalize(it.title) + "|" + SongNames.normalize(it.artist) }
    }

    /** The songs ChatGPT named for the description searched, as a playlist to play or share. */
    fun playlistFromSearch() {
        val songs = uiState.songSearch.take(uiState.songsFromChatGpt).takeIf { it.size >= MIN_PLAYLIST } ?: return
        val title = uiState.songSearchQuery
        cancelContentWork()
        showSongList(title, songs)
    }

    /** Songs like [song], from ChatGPT, each found in the catalogue, as a playlist. */
    fun songsLike(song: MusicMetadata) {
        val searcher = songSearcher ?: return
        val tutor = tutor ?: return
        cancelContentWork()
        uiState = uiState.copy(isLoading = true, result = null, link = null, error = null, linkText = "")
        job = viewModelScope.launch {
            val songs = tutor.songsLike(song)?.let { found(searcher, it) }.orEmpty()
            uiState = uiState.copy(isLoading = false)
            if (songs.isEmpty()) return@launch showError(AppError.NO_SONGS_LIKE)
            showSongList(song.title, songs, like = true)
        }
    }

    /**
     * [songs] shown as a list named [title], to play all at once, open one by one, copy or share,
     * like a playlist; [like] when they're songs like the one [title] names. Not kept in Recent:
     * there's no link to open it again by.
     */
    private fun showSongList(title: String, songs: List<MusicMetadata>, like: Boolean = false) {
        uiState = uiState.copy(
            result = MusicMetadata(title, "", ItemType.PLAYLIST, songs.firstNotNullOfOrNull { it.artworkUrl }, songs),
            link = null,
            songsLike = like,
            linkText = "",
            destinationUrls = emptyMap(),
            error = null
        )
    }

    /** Closes the songs offered, leaving the typed text as it was. */
    fun dismissSongSearch() {
        searchJob?.cancel()
        uiState = uiState.copy(isSearchingSongs = false, isAskingForSongs = false, songSearch = emptyList(), songsFromChatGpt = 0, songSearchQuery = "")
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

    /** Opens a song ChatGPT named, by [title] and [artist], like one picked from a search. */
    fun openNamedSong(title: String, artist: String) = chooseSong(MusicMetadata(title, artist))

    /** A link stays in the box; a song found by its name leaves it empty, as the song shows right under it. */
    private fun fieldText(link: MusicLink) = if (link.service == null) "" else link.url

    /**
     * A recognized song shows only its name in the field, so the name reads back as the song just
     * looked up, or one in Recent. Recent and the widget also reopen it from its link alone.
     */
    private fun parse(text: String): LinkInput? {
        MusicLinks.parse(text)?.let { return it }
        val typed = text.trim()
        (lastRequest?.input as? LinkInput.RecognizedSong)?.takeIf { it.text == typed }?.let { return it }
        return uiState.history.asSequence().filter { it.link.service == null }
            .map { LinkInput.RecognizedSong(it.link.url, it.metadata) }
            .firstOrNull { it.url == typed || it.text == typed }
    }

    fun settingsLeft() {
        uiState = uiState.copy(leaveSettings = false)
    }

    /**
     * Resolves a link from another app and opens it (or offers destinations) as soon as it is
     * ready; in [destination] when the user already chose one, e.g. with a share sheet target.
     * With [show], it only shows the result, for the user to pick what to do. Otherwise it goes
     * where links go: straight to the app, or to the song's card here.
     */
    fun resolveIncoming(text: String?, destination: Destination? = null, show: Boolean = false, after: AfterLookup = AfterLookup.OPEN) {
        cancelContentWork()
        val incoming = text?.let { MusicLinks.extractFirstUrl(it) ?: it }?.trim().orEmpty()
        // Without an app songs play in yet, it shows here, where one's picked.
        val here = !show && destination == null && after == AfterLookup.OPEN && (uiState.linksInCrosstune || !uiState.hasDefault)
        uiState = uiState.copy(
            linkText = incoming,
            handlingIncomingLink = true,
            handingOff = !show && !here,
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
        resolve(input, openWhenReady = !show && !here, destination, here = here)
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

    /** Whether a link just copied may be filled in: nothing else is shown or under way. */
    val readyForCopy get() = uiState.let { it.linkText.isBlank() && it.result == null && !it.isLoading && !it.listening && it.lyricsFor == null }

    /** A music link copied a moment ago, filled in as if pasted; a YouTube video only once it's known to be music. */
    fun pasteCopy(text: String) {
        val input = MusicLinks.parse(text)
        if (input !is LinkInput.Link || !input.link.mayNotBeMusic) return pasteLink(text)
        resolve(input, openWhenReady = false, copied = true)
    }

    /** A YouTube video, which Crosstune only takes once YouTube Music says it's music, unless any video may be. */
    private val MusicLink.mayNotBeMusic get() = uiState.onlyMusicVideos && service == MusicService.YOUTUBE && type == ItemType.TRACK

    @VisibleForTesting
    internal val lookingUp get() = job?.isActive == true

    /** Clipboard text pasted with the field's Paste button: looked up, but only opened on request. */
    fun pasteLink(text: String?) {
        if (text.isNullOrBlank()) return showError(AppError.CLIPBOARD_EMPTY)
        val recognized = MusicLinks.parse(text) as? LinkInput.RecognizedSong
        uiState = uiState.copy(linkText = recognized?.text ?: MusicLinks.extractFirstUrl(text) ?: text.trim(), error = null)
        // The box shows only the song's name, so the song itself is looked up, not the name.
        if (recognized != null) {
            uiState = uiState.copy(handlingIncomingLink = false, handingOff = false)
            return resolve(recognized, openWhenReady = false)
        }
        resolveTypedInput()
    }

    /** Clipboard text from the Quick Settings tile or a launcher shortcut, which may name the app to open it in. */
    fun resolveClipboard(text: String?, destination: Destination?, show: Boolean, after: AfterLookup) {
        if (text.isNullOrBlank()) return showError(AppError.CLIPBOARD_EMPTY)
        resolveIncoming(text, destination, show, after)
    }

    fun retry() {
        val (input, openWhenReady, destination, here) = lastRequest ?: return
        resolve(input, openWhenReady, destination, here = here)
    }

    /** Whether Try again listens again, since listening failed before a song was found. */
    var retryListens = false
        private set

    /**
     * Listens for a song playing nearby, then shows it as a pasted link would: by its Apple Music
     * link when Shazam knows it, like a Shazam link, or else by its name, like a Now Playing song.
     * With [lyrics], its words open as soon as it's named. The microphone permission is already granted.
     */
    fun listen(lyrics: Boolean = false) {
        cancelContentWork()
        heardSong = null
        hear(null)
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
                is Heard.Song -> showHeard(heard, lyrics)
                Heard.Nothing -> showListenError(AppError.NO_MATCH)
                is Heard.Failed -> showListenError(heard.error)
            }
        }
    }

    /**
     * Offers what music apps play while Crosstune is in sight, fetching each song's words ahead. A
     * song an app paused stays offered, with its app's buttons, as long as the app has it.
     */
    suspend fun watchPlayingNow() {
        val previous = uiState.playingNow?.song
        heardNow = null
        followedNow = null
        val source = playback?.takeIf { it.hasAccess() }
        if (source == null) {
            uiState = uiState.copy(playingNow = null)
            return
        }
        // Registration, callbacks and cancellation share the main thread, so none can reattach
        // a controller while the lifecycle is releasing its listeners.
        coroutineScope {
            launch {
                source.nowPlayingUpdates()
                    .onEach { heard ->
                        heardNow = heard?.metadata
                        showPlayingNow()
                    }
                    // Clock updates keep the offer current without restarting its lyrics lookup.
                    .distinctUntilChangedBy { it?.metadata }
                    .collectLatest { heard -> heard?.let { lyricsFinder?.firstLook(it.metadata) } }
            }
            launch {
                source.nowPlayingUpdates()
                    .mapNotNull { it?.metadata }
                    // Paused apps don't announce a playing song; ask the last one's app whether it still has it.
                    .onStart { previous?.let { emit(it) } }
                    .distinctUntilChanged()
                    .collectLatest { song ->
                        source.follow(song).collect { following ->
                            followedNow = following?.let { song to it }
                            showPlayingNow()
                        }
                    }
            }
        }
    }

    private fun showPlayingNow() {
        val song = heardNow ?: followedNow?.first
        uiState = uiState.copy(playingNow = song?.let { NowPlaying(it, followedNow?.takeIf { (followed) -> followed == song }?.second) })
    }

    /**
     * Opens the player on the song playing now, which then stays in sight. Its card is shown under
     * the player, so its link, Copy and Share are a swipe down away.
     */
    fun openPlayingNow() {
        val now = uiState.playingNow ?: return
        if (uiState.result?.let { sameSong(it, now.song) } != true) showSong(now.song)
        if (uiState.player?.song != now.song) openPlayer(now.song, now.following?.appPackage, played = true)
    }

    /** The player on the song playing now, alone, as a swipe up from the bar opens it before it's let go. */
    fun peekPlayingNow() {
        val now = uiState.playingNow ?: return
        openPlayer(now.song, now.following?.appPackage, played = true)
    }

    /** Pauses the song playing now, or plays it again: as its button shows, playing when its app doesn't say where it is. */
    fun playingNowPlayPause() {
        val now = uiState.playingNow ?: return
        playback?.playPause(now.song, now.following?.clock?.playing == false)
    }

    fun playingNowSkip() {
        val now = uiState.playingNow ?: return
        playback?.skip(now.song, true)
    }

    /**
     * Shows [song] as a result, like a pasted link, by the Shazam page [appleMusicId] names when it's
     * known. The player's own song's card goes under it; any other song takes the player's place.
     */
    private fun showSong(song: MusicMetadata, appleMusicId: String? = null) {
        val input = appleMusicId?.let { MusicLinks.parse("https://www.shazam.com/song/$it") } ?: MusicLinks.recognizedSong(song)
        uiState = uiState.copy(linkText = (input as? LinkInput.RecognizedSong)?.text ?: (input as LinkInput.Link).link.url)
        resolve(input, openWhenReady = false, underPlayer = uiState.player?.let { sameSong(it.song, song) } == true)
    }

    private fun showHeard(heard: Heard.Song, lyrics: Boolean) {
        retryListens = false
        showSong(heard.metadata, heard.appleMusicId)
        // Once it's shown, as showing it forgets what was heard for whatever was shown before.
        heardSong = heard.metadata
        // Its words aren't found yet, so the gap for them moves it once they are.
        hear(heard.clock, heard.matchId)
        heardAtMs = now()
        // Asked for its words, they open straight away, and keep listening along, song after song.
        if (lyrics) showLyricsFor(heard.metadata)
    }

    fun stopListening() {
        job?.cancel()
        uiState = uiState.copy(listening = false)
    }

    private fun showListenError(error: AppError) {
        uiState = uiState.copy(error = error, canRetry = error.canRetry)
        if (error == AppError.RECOGNITION_UNAVAILABLE) effectChannel.trySend(Effect.RecognizeElsewhere)
    }

    /**
     * Looks [input] up, and opens it when [openWhenReady]. A video [copied] a moment ago shows
     * nothing until YouTube Music says it's music, and then only if nothing else has been shown since.
     * A link that came [here], as links do, plays as soon as it's found when set to.
     */
    private fun resolve(input: LinkInput, openWhenReady: Boolean, destination: Destination? = null, copied: Boolean = false, here: Boolean = false, underPlayer: Boolean = false) {
        cancelContentWork(closesPlayer = !underPlayer)
        lastRequest = Request(input, openWhenReady, destination, here)
        pendingOpen = openWhenReady
        pendingPlay = here && uiState.playsRightAway
        // A newer request always wins; the older call is cancelled rather than left to overwrite it.
        fun showLoading() {
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
        }
        if (!copied) showLoading()
        // A link already in Recent is shown from there rather than looked up again. A short link's
        // target isn't known until it's followed, so it's always looked up.
        val link = (input as? LinkInput.Link)?.link
        // The link as parsed is kept, since Recent doesn't save which frontend's site it came from.
        val saved = link?.let { wanted -> uiState.history.firstOrNull { it.link.url == wanted.url }?.copy(link = wanted) }
            // An album or playlist kept without its songs, e.g. by a version that couldn't read them, is looked up again for them.
            ?.takeUnless { (it.link.type == ItemType.ALBUM || it.link.type == ItemType.PLAYLIST) && it.metadata.tracks.isEmpty() }
        job = viewModelScope.launch {
            // A video from another app that isn't music, like a tutorial, opens as it would without
            // Crosstune; one copied, or that YouTube Music can't tell about, e.g. offline, is let be.
            val video = link?.takeIf { (copied || here || openWhenReady && destination == null) && it.mayNotBeMusic }
            // Looked up while asking whether it's music, so a music video costs one wait, not two.
            val lookup = async { saved?.let { Resolution.Resolved(it.link, it.metadata) } ?: withCover(resolver.resolve(input)) }
            val music = video?.let { matcher.isMusicVideo(it.id) }
            if (copied) {
                if (video == null || music != true || !readyForCopy) return@launch lookup.cancel()
                uiState = uiState.copy(linkText = video.frontendUrl ?: video.url, handlingIncomingLink = false, handingOff = false)
                showLoading()
            } else if (video != null && music == false) {
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
            songsLike = false,
            link = resolution.link,
            linkText = fieldText(resolution.link),
            destinationUrls = savedDestinations(entry),
            history = history
        )
        lookAhead()
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
        // Incoming links without an app to go to must not search one before asking.
        val ask = uiState.selectedDestination == null && (!uiState.hasDefault || ownService != null)
        if (pendingOpen && ask) {
            pendingOpen = false
            uiState = uiState.copy(showDestinationPicker = true, pickerHides = ownService, handingOff = false)
            return
        }
        // Opened in Crosstune, it plays from here, when its app can be asked to; otherwise the card has the choices.
        if (pendingPlay) {
            pendingPlay = false
            // Whether its app is on the phone is only known once Android says, which a song from Recent can beat.
            snapshotFlow { uiState.systemStateKnown }.first { it }
            if (uiState.canPlayResult) return playResult()
        }
        prepareDestination(uiState.resultDestination)
        if (!pendingOpen) return
        pendingOpen = false
        open(uiState.resultDestination, finishAfterOpen = true)
    }

    private var trackJob: Job? = null

    /**
     * Plays the result's songs in YouTube Music or YouTube, as one queue of the first [MAX_QUEUE],
     * which is all YouTube takes; [playQueue] plays a longer list on from there. In an app that takes
     * no list, they play song by song. [shuffled], the whole list is shuffled first, as the app would
     * only shuffle the part it was given.
     */
    fun playAll(shuffled: Boolean = false) {
        val destination = uiState.resultDestination
        val service = destination.matchService ?: return
        val all = uiState.result?.tracks.orEmpty().let { if (shuffled) it.shuffled() else it }
        val name = uiState.result?.title
        if (service.playsSongBySong) return playSongBySong(all, service, destination, name)
        val tracks = all.take(MAX_QUEUE)
        cancelTrackWork()
        trackJob = viewModelScope.launch {
            uiState = uiState.copy(queueProgress = 0 to tracks.size)
            try {
                val queue = matcher.youtubeQueue(service, tracks) { looked -> uiState = uiState.copy(queueProgress = looked to tracks.size) }
                if (queue == null) return@launch showError(AppError.NOT_FOUND)
                // In Crosstune's player, as one song plays, from the list's first song found.
                val app = destination.packageName
                val first = queue.songs.first()
                openPlayer(first, app, queue.url)
                // Before the app's asked, so what it played until then isn't taken for the list's start.
                playOn(all, service, destination, queue, name)
                val asked = app != null && playback?.playList(app, queue.url, mustStart = true) == true
                if (!playThere(first, app, queue.url, asked, exact = true)) effectChannel.send(Effect.Open(queue.url, app, finishAfterOpen = false))
            } finally {
                if (isActive) uiState = uiState.copy(queueProgress = null)
            }
        }
    }

    /**
     * Plays [tracks] in the app [destination] is, which takes no list, one after another: the first
     * asked for here, with the player on it, and [playQueue] asking for each next one as the one before
     * ends. With no app to ask, the first opens there, as it would on its own. An app that plays none
     * asked for is opened on each song's own link instead, coming to the front for it.
     */
    private fun playSongBySong(tracks: List<MusicMetadata>, service: MusicService, destination: Destination, name: String?) {
        val app = destination.packageName ?: return
        val first = tracks.first()
        cancelTrackWork()
        trackJob = viewModelScope.launch {
            if (!service.playsAskedSongs) {
                uiState = uiState.copy(isMatching = true)
                val link = try {
                    matcher.find(service, first)
                } finally {
                    if (isActive) uiState = uiState.copy(isMatching = false)
                }
                link ?: return@launch openTrack(first)
                openPlayer(first, app, link)
                // Before the app's opened, so what it played until then isn't taken for the list's start.
                playQueue?.play(tracks, service, app, 1, listOf(first), name)
                if (!playThere(first, app, link, asked = false, exact = true)) {
                    playQueue?.stop()
                    effectChannel.send(Effect.Open(link, app, finishAfterOpen = false))
                }
                return@launch
            }
            openPlayer(first, app)
            if (playback?.playSong(first, app, mustStart = true) == true) {
                playQueue?.play(tracks, service, app, 1, listOf(first), name)
            } else {
                closePlayer()
                openTrack(first)
            }
        }
    }

    /** Plays the rest of [tracks], the list called [name], on in the app [destination] is, after [queue], their first [MAX_QUEUE]. */
    private suspend fun playOn(tracks: List<MusicMetadata>, service: MusicService, destination: Destination, queue: YouTubeQueue, name: String?) {
        playQueue?.play(tracks, service, destination.packageName ?: return, MAX_QUEUE, queue.songs, name)
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
            val match = destination.matchService?.takeIf { uiState.exactMatch }?.let { service ->
                uiState = uiState.copy(isMatching = true)
                try {
                    matcher.lookFor(service, track)
                } finally {
                    if (isActive) uiState = uiState.copy(isMatching = false)
                }
            }
            val exactUrl = (match as? ExactMatcher.Match.Found)?.url
            val url = exactUrl?.let(destination::adapt) ?: destination.searchUrl(searchQuery(track))
            val service = destination.matchService
            // Not found there, as opposed to no answer, e.g. offline, which just searches.
            if (match == ExactMatcher.Match.Missing && service != null && canBeMissing(service, track)) {
                return@launch openMissing(track, track.url?.let(MusicLinks::linkFor), destination, url, finishAfterOpen = false)
            }
            effectChannel.send(Effect.Open(url.forSharing(), destination.packageName, finishAfterOpen = false))
        }
    }

    /** Opens the result from a button tap; the picker passes the destination chosen for this link. */
    fun openResult(destination: Destination? = null, finishAfterOpen: Boolean = false) {
        val chosen = destination ?: uiState.resultDestination
        adoptDefault(chosen)
        pendingOpen = false
        uiState = uiState.copy(
            showDestinationPicker = false, isMatching = false,
            selectedDestination = destination ?: uiState.selectedDestination
        )
        job?.cancel()
        job = viewModelScope.launch { open(chosen, finishAfterOpen, tapped = true) }
    }

    /**
     * Has the song's app play it in the background, in Crosstune's player; opened there instead
     * when it can't be asked, or, for YouTube Music, has only a search for it.
     */
    fun playResult() {
        val destination = uiState.resultDestination as? Destination.Service ?: return
        adoptDefault(destination)
        val song = uiState.result ?: return
        // On a TV the song's words cover the app it plays in, which Android has to let them first.
        if (uiState.phone == Phone.TV && !uiState.canFloatOverApps) {
            // Allowed now, which only the activity looks up again when it resumes, so play goes on knowing it.
            if (!fixInOneTap(OneTapFix.FLOAT) { setCanFloatOverApps(true); playResult() }) fixSteps(listOf(Need.Float))
            return
        }
        job?.cancel()
        job = viewModelScope.launch {
            uiState = uiState.copy(playingResult = true)
            try {
                val link = prepareDestination(destination) ?: return@launch
                // The link only a search when it was looked up and not found.
                val exact = uiState.destinationUrls[destination]?.exact != false
                val source = playback
                val app = destination.packageName
                openPlayer(song, app, link)
                // Already in that app, playing or paused, it carries on from where it is, not from the start.
                val there = source?.takeIf { it.hasAccess() }?.follow(song)?.first()?.takeIf { sameApp(app, it.appPackage, interception::isInstalled) }
                if (there != null) {
                    if (!there.clock.playing) source.playPause(song, true)
                    return@launch
                }
                // Asked by name, other apps find it; YouTube Music only plays its own link.
                val asked = (exact || destination.service != MusicService.YOUTUBE_MUSIC) && source?.playSong(song, app, link, mustStart = true) == true
                if (!playThere(song, app, link, asked, exact)) open(destination, finishAfterOpen = false, tapped = true)
            } finally {
                uiState = uiState.copy(playingResult = false)
            }
        }
    }

    /**
     * [song] playing in [app], with the player on it, once [app] was [asked] to play it in the background: seen to play it
     * there, or with no way to see, that's that, as an app may say yes and not play it. An app that couldn't be asked is
     * opened on its [exact] [link], and, when Crosstune can see it play there, left again once it does, as practice does,
     * unless it wouldn't play in the background, where it would stop.
     * False, with the player closed, when it could be neither.
     */
    private suspend fun playThere(song: MusicMetadata, app: String?, link: String, asked: Boolean, exact: Boolean): Boolean {
        val source = playback
        val sees = source?.hasAccess() == true
        // Played in that app, not another that has it too.
        if (asked && (!sees || withTimeoutOrNull(LOAD_SONG_MS) { source.follow(song).first { it != null && sameApp(app, it.appPackage, interception::isInstalled) } } != null)) return true
        val back = source?.takeIf { exact && sees && app != null && it.openSong(app, link) } ?: return false.also { closePlayer() }
        // Left once it plays, not once it shows the song: YouTube Music, left before it starts, never does.
        withTimeoutOrNull(LOAD_SONG_MS) { back.follow(song).first { it != null && it.clock.playing && sameApp(app, it.appPackage, interception::isInstalled) } }
        back.comesBack()
        watchLeft(song)
        return true
    }

    private var leftJob: Job? = null

    /**
     * An app that stops as soon as Crosstune comes back over it only plays in front, as YouTube Music without Premium,
     * which Crosstune can't learn by asking it from behind when it isn't running: asked to play on, it's brought back
     * there, covered. Paused from Crosstune meanwhile, it's left paused.
     */
    private fun watchLeft(song: MusicMetadata) {
        leftJob?.cancel()
        val source = playback ?: return
        if (uiState.coveringApp) return
        leftJob = viewModelScope.launch {
            if (withTimeoutOrNull(START_MS) { source.follow(song).first { it != null && !it.clock.playing } } != null) source.playPause(song, true)
        }
    }

    private var playerJob: Job? = null

    /** Opens the player on [song], asked of [app] at [link], and follows it there as it plays. */
    private fun openPlayer(song: MusicMetadata, app: String?, link: String? = null, played: Boolean = false) {
        uiState = uiState.copy(player = PlayerState(song, app, link, played = played), canFollowApps = playback?.hasAccess() == true)
        followPlayer()
        peekWords()
    }

    private var peekJob: Job? = null

    /**
     * The player's song's words, for the line being sung under its buttons: as they were shown, or
     * looked up as the song's card looks them up ahead, which opening them then shares.
     */
    private fun peekWords() {
        peekJob?.cancel()
        val song = uiState.player?.song ?: return
        val asked = asPlayed(song)
        val key = RecordingKey.of(asked)
        kept[key]?.let { return showPeek(song, it.lines, it.lyrics) }
        val finder = lyricsFinder?.takeIf { lookAhead && asked.type == ItemType.TRACK && asked.artist.isNotBlank() } ?: return
        val look = lookingAhead[key] ?: viewModelScope.async { finder.firstLook(asked) }.also {
            lookingAhead[key] = it
            it.invokeOnCompletion { lookingAhead.remove(key) }
        }
        peekJob = viewModelScope.launch {
            (look.await().lyrics as? LyricsFinder.Lyrics.Found)?.let { showPeek(song, it.lines, it.words) }
        }
    }

    /** [lines] under [song]'s player, while it shows it; words that aren't timed show their first line. */
    private fun showPeek(song: MusicMetadata, lines: List<LyricLine>, words: String) {
        val player = uiState.player?.takeIf { it.song == song } ?: return
        val shown = lines.ifEmpty { words.lines().filter { it.isNotBlank() }.take(1).map { LyricLine(0, it) } }
        uiState = uiState.copy(player = player.copy(lines = shown))
    }

    /**
     * Follows the player's song in its app. Once it played, the next song the app plays takes over,
     * as the words do; not in practice, which moves the app from song to song.
     */
    private fun followPlayer() {
        playerJob?.cancel()
        playerJob = null
        val song = uiState.player?.song ?: return
        val source = playback?.takeIf { it.hasAccess() } ?: return
        playerJob = viewModelScope.launch {
            combine(source.follow(song), source.nowPlayingUpdates(), ::Pair).collect { (following, playing) ->
                val player = uiState.player ?: return@collect
                val played = player.played || following?.clock?.playing == true
                // Paused, the app still has the song, maybe by another name, e.g. in its own language; only once it's on another is it the next.
                val next = playing?.takeIf { played && following == null && uiState.practice == null && !sameSong(it.metadata, song) }
                // In the app that plays it, which may not be the one the song before played in.
                if (next != null) return@collect openPlayer(next.metadata, next.app ?: player.following?.appPackage ?: player.app, played = true)
                uiState = uiState.copy(player = player.copy(following = following, played = played))
            }
        }
    }

    fun closePlayer() {
        playerJob?.cancel()
        playerJob = null
        peekJob?.cancel()
        uiState = uiState.copy(player = null)
    }

    /** Brings the app the player's song plays in forward as it is, which opening the song there would restart. */
    fun openPlayerApp() {
        val player = uiState.player ?: return
        val app = player.following?.appPackage ?: player.app ?: return
        effectChannel.trySend(Effect.Show(app, finishAfterOpen = false, saysWhenItFails = false))
    }

    /**
     * The player's song's card under it, for its menu: looked up once that's opened on a song the
     * card isn't of, e.g. the next one its app went on to, rather than each song as it plays.
     */
    fun playerMenu() {
        val song = uiState.player?.song ?: return
        if (uiState.result?.let { sameSong(it, song) } != true) showSong(song)
    }

    /** Closes the player on the song's card, with the apps to open it in over it. */
    fun playerOpenWith() {
        closePlayer()
        uiState = uiState.copy(showDestinationPicker = true, pickerHides = null)
    }

    /** Plays or pauses the player's song; asked of its app again when it isn't playing it. */
    fun playerPlayPause() {
        val player = uiState.player ?: return
        val following = player.following ?: return run {
            // Still being asked for, a second ask would start it over.
            if (!uiState.playingResult) viewModelScope.launch {
                // On a TV, YouTube lets go of a song once Crosstune is in front, so it's opened on it again, covered.
                if (playback?.playSong(player.song, player.app, player.link) == false && uiState.phone == Phone.TV) player.app?.let { app -> player.link?.let { playback.openSong(app, it) } }
            }
        }
        playback?.playPause(player.song, !following.clock.playing)
    }

    /** Has the app go on to its [next] song, or back to the one before, which the player then shows. */
    fun playerSkip(next: Boolean) {
        val player = uiState.player ?: return
        val following = player.following ?: return
        if (!(if (next) following.canSkipNext else following.canSkipPrevious)) return
        uiState = uiState.copy(player = player.copy(played = true))
        playback?.skip(player.song, next)
    }

    /** Has the app shuffle, or play in order again. */
    fun playerShuffle() {
        val player = uiState.player ?: return
        val shuffles = player.following?.shuffle ?: return
        playback?.shuffle(player.song, !shuffles)
    }

    /** Has the app repeat the next way, as music apps go round them: not at all, all of its list, then the song. */
    fun playerRepeat() {
        val player = uiState.player ?: return
        val repeats = player.following?.repeat ?: return
        playback?.repeat(player.song, Repeat.entries[(repeats.ordinal + 1) % Repeat.entries.size])
    }

    /** Presses the app's own like for the player's song. */
    fun playerLike() {
        val player = uiState.player ?: return
        if (player.following?.like == null) return
        playback?.like(player.song)
    }

    /** Has the app play one of the songs it has up next now, which the player then shows. */
    fun playerPlayQueued(id: Long) {
        val player = uiState.player ?: return
        uiState = uiState.copy(player = player.copy(played = true))
        playback?.playQueued(player.song, id)
    }

    /** Plays the list on from the song up next at [index], one it still has to ask the app for. */
    fun playerPlayLater(index: Int) {
        val player = uiState.player ?: return
        val apps = player.following?.upNext.orEmpty().count { it.id != null }
        uiState = uiState.copy(player = player.copy(played = true))
        viewModelScope.launch { playQueue?.playFrom(index - apps) }
    }

    /**
     * Moves the song up next at [from] to [to]: the app's songs among themselves, by the order
     * Crosstune keeps over the app's, and a list's still to ask for among theirs.
     */
    fun playerMoveQueued(from: Int, to: Int) {
        val following = uiState.player?.following ?: return
        val upNext = following.upNext
        val moved = upNext.getOrNull(from) ?: return
        val onto = upNext.getOrNull(to) ?: return
        val apps = upNext.count { it.id != null }
        when {
            moved.id != null && onto.id != null -> queueOrder?.move(following, from, to)
            moved.id == null && onto.id == null -> viewModelScope.launch { playQueue?.move(from - apps, to - apps) }
        }
    }

    /** The player's song's card, under it, with its links. */
    fun playerCard() {
        val song = uiState.player?.song ?: return
        closePlayer()
        if (uiState.result?.let { sameSong(it, song) } != true) showSong(song)
    }

    /** Pauses the music in [minutes], or once the player's song ends, without them. */
    fun playerSleep(minutes: Int?) {
        val timer = sleepTimer ?: return
        if (minutes != null) return timer.after(minutes)
        uiState.player?.song?.let(timer::afterSong)
    }

    fun playerSleepOff() {
        sleepTimer?.off()
    }

    fun playerSeek(positionMs: Long) {
        val player = uiState.player ?: return
        if (player.following?.canSeek != true) return
        playback?.seekTo(player.song, positionMs)
    }

    /** The player's song's words, over it. */
    fun playerLyrics() {
        val player = uiState.player ?: return
        showLyricsFor(player.song, playing = player.played)
    }

    /** [tapped] is a tap on its button or in the picker, rather than a link that came in opening on its own. */
    private suspend fun open(destination: Destination, finishAfterOpen: Boolean, tapped: Boolean = false) {
        val url = prepareDestination(destination) ?: return
        val original = uiState.link?.takeIf { it.service != null }
        if (uiState.resultMissing) {
            when (uiState.notFoundAction) {
                // It stays here, saying it isn't there, with both ways on. With nowhere else to open,
                // e.g. a song typed in, a tap on "Search in" searches.
                NotFoundAction.ASK -> if (!tapped || original != null) {
                    pendingOpen = false
                    // Still the link that came in, so it shows over first-run setup.
                    uiState = uiState.copy(handingOff = false, selectedDestination = destination)
                    return
                }
                NotFoundAction.ORIGINAL -> if (original != null) {
                    effectChannel.send(Effect.Open(original.url, original.service?.packageName, finishAfterOpen))
                    return
                }
                NotFoundAction.SEARCH -> Unit
            }
        }
        // Already in that app, playing or paused, it's brought forward, not asked for again, which would restart it.
        // Only a song plays: an album or a playlist named as the song playing opens as itself.
        val there = uiState.result?.takeIf { tapped && it.type == ItemType.TRACK }?.let { song ->
            playback?.takeIf { it.hasAccess() }?.follow(song)?.first()?.takeIf { sameApp(destination.packageName, it.appPackage, interception::isInstalled) }
        }
        if (there?.appPackage != null) return effectChannel.send(Effect.Show(there.appPackage, finishAfterOpen))
        effectChannel.send(Effect.Open(url, destination.packageName, finishAfterOpen))
    }

    /** Searches where the result goes, though it wasn't found there, e.g. under another name. */
    fun searchAnyway() {
        val destination = uiState.resultDestination
        val url = uiState.destinationUrls[destination]?.url ?: return
        effectChannel.trySend(Effect.Open(url.forSharing(), destination.packageName, finishAfterOpen = false))
    }

    /**
     * A song from Recent or a playlist that [destination] doesn't have: searched, opened where it's
     * from, or offered both, as set.
     */
    private suspend fun openMissing(song: MusicMetadata, original: MusicLink?, destination: Destination, searchUrl: String, finishAfterOpen: Boolean) {
        val opensWhere = original?.takeIf { it.service != null }
        when {
            uiState.notFoundAction == NotFoundAction.SEARCH || (uiState.notFoundAction == NotFoundAction.ORIGINAL && opensWhere == null) ->
                effectChannel.send(Effect.Open(searchUrl.forSharing(), destination.packageName, finishAfterOpen))
            uiState.notFoundAction == NotFoundAction.ORIGINAL ->
                effectChannel.send(Effect.Open(opensWhere!!.url, opensWhere.service?.packageName, finishAfterOpen))
            else -> uiState = uiState.copy(
                notFoundOffer = NotFoundOffer(song, destination, opensWhere, searchUrl), handingOff = false
            )
        }
    }

    /** The offer's way: where the song is, or a search where it was going. */
    fun takeNotFoundOffer(original: Boolean) {
        val offer = uiState.notFoundOffer ?: return
        uiState = uiState.copy(notFoundOffer = null)
        val link = offer.original
        effectChannel.trySend(
            if (original && link != null) Effect.Open(link.url, link.service?.packageName, finishAfterOpen = false)
            else Effect.Open(offer.searchUrl.forSharing(), offer.destination.packageName, finishAfterOpen = false)
        )
    }

    fun dismissNotFoundOffer() {
        uiState = uiState.copy(notFoundOffer = null)
    }

    fun selectNotFoundAction(action: NotFoundAction) {
        preferences.edit { putString(KEY_NOT_FOUND, action.name) }
        uiState = uiState.copy(notFoundAction = action)
    }

    private var lyricsJob: Job? = null

    @VisibleForTesting
    internal val findingWords get() = lyricsJob?.isActive == true

    private var followJob: Job? = null

    /** The last song Crosstune heard, so its words can follow on from where it was heard. */
    private var heardSong: MusicMetadata? = null

    /** Where [heardSong] is, as far as hearing it can tell, kept steady against what one hearing gets wrong. */
    private var heardClock: PlaybackClock? = null

    /** The recording Shazam matched [heardClock] to, and the gap moved it by, so a gap learnt later moves it too. */
    private var heardMatch: String? = null
    private var heardGapMs = 0L

    /** A place heard far from where the words are, held until a second hearing agrees with it. */
    private var unconfirmed: PlaybackClock? = null

    /** When the song was last heard, so a hearing long ago isn't held against an app's say. */
    private var heardAtMs = 0L

    /** Where the app playing the song says it is, before taking off how far its video is ahead. */
    private var appFollowing: Following? = null

    /** How far the video an app plays is ahead of its song, as last heard; null until it's been heard. */
    private var appLeadMs: Long? = null
    private var appLeadVerified = false
    /**
     * How far the video is ahead by the last hearing alone, while hearings don't agree on it yet: the
     * words follow from there meanwhile, rather than stand still, but it's never kept.
     */
    private var guessedLeadMs: Long? = null
    /** The recording Shazam matched the hearings [appLeadMs] was learnt from to, this time. */
    private var leadMatch: String? = null

    /**
     * Shazam's gap for the recording it [match]ed, as it was before the words were put in time by hand
     * by moving it, and the music app's lead then, so Reset puts both back; and the line that moved it.
     */
    private data class HandGap(val match: String, val gapMs: Long?, val leadMs: Long?, val lineMs: Long)
    private var handGap: HandGap? = null
    private var leadTiming = LeadTiming()
    private var gapTiming = LeadTiming()
    /** The recording Shazam matched the hearings [gapTiming] holds to, as each has a gap of its own. */
    private var gapMatch: String? = null
    private var autoCalibrating = false
    private var calibrationAttempted = false

    private var nextJob: Job? = null

    /** Hearing the words sung, to line them up; [singingTries] since the app's clock last started or jumped. */
    private var singingJob: Job? = null
    private var singingTries = 0

    /**
     * Whether the app's clock is known to be where the song is: Crosstune saw it say so as the song
     * started. Picked up partway, or after a pause or a move, it may be most of a second behind, as
     * YouTube Music's often is, so the singing heard then puts this time right, and only this time.
     */
    private var clockSure = false
    /** The app's clock as last said, to tell when it jumps rather than plays on. */
    private var lastAppClock: PlaybackClock? = null
    /** Where lines were heard sung by the app's clock, unsure, so for this time only; and whether they were. */
    private var heardPoints: List<SyncPoint> = emptyList()
    /** Whether no singing was heard where the words are timed, and none found yet: no line is shown sung till it is. */
    private var unheard = false
    private var heardThisTime = false
    /** Whether the words are being lined up to the singing, through the song, with the app's clock sure; lines heard so far stay till it's done. */
    private var liningUp = false
    /** Whether the speech model, offered to be fetched by hand, was turned down; it isn't offered again till Crosstune opens again. */
    private var modelClosed = false

    /** Set once the song shown was playing while its words were open, so the next song an app plays takes over. */
    private var shownPlayed = false

    /** Set while Crosstune is out of sight with no words floating, when the words don't go on to the next song. */
    private var nextPaused = false

    /**
     * Fetches the shown song's words and shows them. A song with no words of its own says so
     * rather than showing nothing, so it isn't read as a lookup that hasn't answered. Timed words
     * follow the song while something says where it is.
     */
    fun showLyrics() {
        // Retry asks again for the words shown, which after listening along may be another song than the result's.
        showLyricsFor(uiState.lyricsFor ?: uiState.result ?: return)
    }

    /** [requested] as its words are looked up: as the app playing it has it, when one does. */
    private fun asPlayed(requested: MusicMetadata): MusicMetadata {
        val current = playback?.recording(requested)?.takeIf { sameSong(it, requested) && it.recordingId != null }
        return current?.let { RecordingKey.asPlayed(requested, it) } ?: requested
    }

    /** The words of songs shown, by recording, while they're being looked up ahead. */
    private val lookingAhead = HashMap<String, Deferred<LyricsFinder.FirstLook>>()

    /**
     * Looks up the words of the song shown as it shows, so they're there, or on their way, when
     * opened; once found they're kept by the finder, so only one still on its way is held here.
     */
    private fun lookAhead() {
        // A link going straight to the user's app shows nothing here to open the words of.
        val finder = lyricsFinder?.takeIf { lookAhead && !uiState.handingOff } ?: return
        val song = uiState.result?.takeIf { it.type == ItemType.TRACK }?.let(::asPlayed) ?: return
        val key = RecordingKey.of(song)
        if (key in lookingAhead || key in kept) return
        lookingAhead[key] = viewModelScope.async { finder.firstLook(song) }.also { it.invokeOnCompletion { lookingAhead.remove(key) } }
    }

    private fun showLyricsFor(requested: MusicMetadata, playing: Boolean = false) {
        val song = asPlayed(requested)
        // Only a song has words, and one whose artist is unknown can't be looked up safely.
        val finder = lyricsFinder ?: return
        if (song.type != ItemType.TRACK || song.artist.isBlank()) return
        // Already showing these words, or on the way, so a second tap doesn't fetch them again. A lookup
        // that failed is worth asking again, so Retry is the one case that does fetch.
        if (uiState.lyricsFor == song && (uiState.lyrics.isNotEmpty() || uiState.isLoadingLyrics)) return
        // A song just heard, or playing as it's shown, is followed on to the next one; one picked to read stays.
        shownPlayed = playing || heardSong?.let { sameSong(it, song) } == true
        leaveWords()
        keepShown()
        lyricsJob?.cancel()
        stopLearning()
        stopFollowing()
        foundLines = emptyList()
        found = null
        restored = null
        syncPoints = emptyList()
        handGap = null
        if (showKept(song)) {
            follow(song)
        } else {
            lyricsJob = viewModelScope.launch {
                var first: LyricsFinder.FirstLook? = null
                uiState = uiState.copy(
                    lyricsFor = song, lyrics = "", lyricLines = emptyList(), lyricsSynced = false, lyricsOffTake = false, lyricsFailed = false, isLoadingLyrics = true, learning = uiState.learning.fresh(), chords = uiState.chords.fresh(),
                    canFollowApps = playback?.hasAccess() == true
                )
                try {
                    // A lookup that failed says so, rather than claiming the song has no words. The words
                    // show as soon as they're found, and their timing may get better after.
                    first = lookingAhead.remove(RecordingKey.of(song))?.await() ?: finder.firstLook(song)
                    when (val answer = first.lyrics) {
                        is LyricsFinder.Lyrics.Found -> {
                            foundLines = answer.lines
                            found = answer
                            syncPoints = syncStore.load(song, answer.lines)
                            uiState = uiState.copy(
                                lyrics = answer.words, lyricLines = LyricsSync.adjust(answer.lines, syncPoints), lyricsSynced = syncPoints.isNotEmpty(), lyricsFailed = false,
                                lyricsOffTake = timedForAnother(song)
                            )
                            uiState = uiState.copy(learning = uiState.learning.fresh(Readings.displayScriptOf(wordLines())))
                            followSavedLines(song)
                            timeSavedLines(song)
                            learn()
                            chords()
                            // Japanese in romaji or Korean in Latin letters is written back in its own script, so it
                            // reads like any song in it.
                            if (usesChatGpt()) romanizedScript(answer.words)?.let { writeInOwnScript(song, it) }
                        }
                        // No words were shown while looking, so there's none to clear.
                        LyricsFinder.Lyrics.None -> problemFound(SongProblem.NO_LYRICS)
                        // Having no words is what an instrumental is, not a problem.
                        LyricsFinder.Lyrics.Instrumental -> Unit
                        LyricsFinder.Lyrics.Unavailable -> uiState = uiState.copy(lyrics = "", lyricsFailed = true)
                    }
                } finally {
                    if (isActive) uiState = uiState.copy(isLoadingLyrics = false)
                }
                follow(song)
                first.takeIf { !it.final }?.let { retime(song, it.lyrics) }
                // Not timed even after looking further.
                if (found?.lines?.isEmpty() == true) problemFound(SongProblem.NO_TIMING)
            }
        }
        watchNext(song)
        // A song Crosstune just heard is still playing nearby, so it keeps listening, to stay in time with it.
        if (heardSong?.let { sameSong(it, song) } == true) listenAlong()
    }

    /**
     * Keeps the words shown as the app's left, for when they're opened again: once all that's on for
     * them is worked out, as it still is while Android lets the app run, e.g. timing them better.
     */
    fun keep() {
        val busy = listOfNotNull(lyricsJob, readingsJob, restoreJob, translationJob, chordsJob).filter { it.isActive }
        if (busy.isEmpty()) keepShown() else viewModelScope.launch { busy.joinAll(); keep() }
    }

    /**
     * Keeps the words shown for when they're opened again, once all that's on for them is worked out;
     * left before that, they're looked up again.
     */
    private fun keepShown() {
        val song = uiState.lyricsFor ?: return
        val learning = uiState.learning
        val working = uiState.isLoadingLyrics || lyricsJob?.isActive == true || readingsJob?.isActive == true || restoreJob?.isActive == true || learning.translating || uiState.chords.loading
        if (uiState.lyrics.isEmpty() || working) return
        val key = RecordingKey.of(song)
        // Where lines were heard this time only isn't kept: next time the app's clock may be sure.
        val lines = if (heardPoints.isEmpty()) uiState.lyricLines else shown(LyricsSync.adjust(foundLines, syncPoints))
        val words = KeptWords(
            uiState.lyrics, lines, uiState.lyricsSynced, found, foundLines, restored, syncPoints,
            // A translation that failed is asked for again.
            learning.copy(translations = if (learning.translationFailed) emptyList() else learning.translations),
            // Chords none were found for are looked for again.
            madeWith(), uiState.chords.takeIf { it.sheet != null } ?: uiState.chords.fresh()
        )
        kept[key] = words
        keptOnDisk?.let { disk ->
            viewModelScope.launch {
                disk.put(key, words.json())
                disk.save()
            }
        }
    }

    /**
     * Shows [song]'s words as they were left, when they're kept and were worked out as they would
     * be now, and says whether they were. Words with more in them than would be shown now, e.g. a
     * credit since left out, are worked out again.
     */
    private fun showKept(song: MusicMetadata): Boolean {
        val kept = kept[RecordingKey.of(song)]?.takeIf { it.madeWith == madeWith() && it.found?.let { found -> LyricsFinder.cleaned(found, song) == found } != false } ?: return false
        found = kept.found
        foundLines = kept.foundLines
        restored = kept.japanese
        syncPoints = kept.syncPoints
        uiState = uiState.copy(
            lyricsFor = song, lyrics = kept.lyrics, lyricLines = kept.lines, lyricsSynced = kept.synced, lyricsFailed = false, isLoadingLyrics = false,
            learning = uiState.learning.fresh(kept.learning.script).copy(lines = kept.learning.lines, names = kept.learning.names, translations = kept.learning.translations, titleMeaning = kept.learning.titleMeaning, fromRomaji = kept.learning.fromRomaji),
            chords = kept.chords.copy(on = uiState.chords.on), canFollowApps = playback?.hasAccess() == true
        )
        uiState = uiState.copy(lyricsOffTake = timedForAnother(song))
        followSavedLines(song)
        timeSavedLines(song)
        // Whatever's switched on since, and wasn't worked out then.
        learn()
        chords()
        return true
    }

    /**
     * What a song's words are worked out with: how they're looked up, as words looked up before a
     * fix are looked up again, the language they're translated into, those left untranslated, and,
     * signed in, ChatGPT, how it's asked to translate and its model, which translate them, put their
     * readings right and write romaji in Japanese.
     */
    private fun madeWith() =
        listOf(LyricsFinder.CACHE_VERSION, translateInto(), uiState.learning.untranslated.sorted(), usesChatGpt(), uiState.learning.chatGptTranslation, TRANSLATION_VERSION, uiState.chatGpt.model).toString()

    /**
     * [found], [song]'s words as shown, timed better once that's worked out, e.g. from another copy
     * of the song; kept as they are when they're put in time by hand meanwhile.
     */
    private suspend fun retime(song: MusicMetadata, found: LyricsFinder.Lyrics) {
        val better = lyricsFinder?.retimed(song, found) as? LyricsFinder.Lyrics.Found ?: return
        if (found !is LyricsFinder.Lyrics.Found || better.lines == found.lines || uiState.lyricsFor?.let { sameSong(it, song) } != true || syncPoints.isNotEmpty() || handGap != null) return
        foundLines = better.lines
        this.found = better
        uiState = uiState.copy(lyricLines = better.lines, lyricsOffTake = timedForAnother(song))
        followSavedLines(song)
    }

    private fun sameSong(one: MusicMetadata, other: MusicMetadata) =
        SongNames.titleNames(one.title).any { title -> SongNames.titleNames(other.title).any { SongNames.same(title, it) } } && SongNames.sameArtist(one.artist, other.artist) &&
            (one.recordingId == null || other.recordingId == null || one.recordingId == other.recordingId)

    /** Where [song] is, as Crosstune last heard it, when that's the song it heard. */
    private fun heardFollowing(song: MusicMetadata): Following? =
        heardClock?.takeIf { heardSong?.let { sameSong(it, song) } == true }?.let { Following(it, app = null, canSeek = false) }

    private var alongJob: Job? = null

    /** Told when the app playing the song shown starts playing it, e.g. after a pause, so listening along wakes. */
    private val appPlaysAgain = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Keeps the microphone listening along with the words: every little while it asks what's
     * playing nearby and how far into it, so a pause, a skip or the next song is followed. Only
     * while the words are on screen and Crosstune is in sight.
     */
    fun listenAlong() {
        if (alongJob?.isActive == true) return
        uiState = uiState.copy(listeningAlong = true)
        alongJob = listeningAlong()
        // Being lined up by ear, the words aren't offered to put in time by hand meanwhile.
        uiState.lyricsFor?.let { uiState = uiState.copy(lyricsOffTake = timedForAnother(it)) }
    }

    /**
     * Whether Shazam hears what a music app plays, though it says where it is: to tell how far off the
     * words are ([leading]), or, timed for it, to learn Shazam's own gap to them.
     */
    private val lining get() = leading || appFollowing?.let { !it.video && it.clock.playing && learnsGap(it) } == true

    /**
     * Whether Shazam hears how far off the words are now, which the singing waits for; while it only
     * learns its own gap, which says nothing of where they're sung, both hear the same sound at once.
     */
    private val shazamLeads get() = leading && alongJob?.isActive == true

    /** Whether a music app plays a release the words aren't timed for, and how far off they are isn't known yet. */
    private val leading get() = appFollowing?.let { !it.video && it.clock.playing && needsLead(it) && appLeadMs == null } == true

    private fun listeningAlong() = viewModelScope.launch {
        var quiet = 0
        var calibrationTries = 0
        while (isActive) {
            // A video's long intro isn't the song: what's heard there, before the song may have started, is no try.
            if (autoCalibrating && !songMayNotHaveStarted() && calibrationTries++ >= 5) {
                stopListeningAlong()
                return@launch
            }
            // Only to learn Shazam's gap, nobody asked to listen, so never with the microphone.
            val quietly = autoCalibrating && appFollowing?.let(::learnsGap) == true
            when (val heard = if (lining) listener.listenToPlaying(byMicrophone = !quietly) else listener.listen()) {
                is Heard.Song -> {
                    quiet = 0
                    heardAlong(heard)
                    delay(listenAlongPauseMs)
                }
                // Nothing heard, the music has most likely stopped, so the words stop where they are,
                // and the microphone rests, longer each time, until the app playing it plays again.
                Heard.Nothing -> {
                    heardClock?.takeIf { it.playing }?.let { clock ->
                        heardClock = clock.copy(positionMs = clock.positionAt(now()), atMs = now(), playing = false)
                        keepInTime()
                    }
                    val rest = quietPauseMs * (1L shl minOf(quiet, QUIET_PAUSE_DOUBLINGS))
                    quiet++
                    withTimeoutOrNull(rest) { appPlaysAgain.first() }
                }
                is Heard.Failed -> {
                    // Without the microphone there's nothing to listen with; anything else, e.g. the
                    // network or Shazam being busy, is tried again in a while.
                    if (heard.error == AppError.MICROPHONE) {
                        stopListeningAlong()
                        if (!quietly) uiState = uiState.copy(error = AppError.MICROPHONE, canRetry = false)
                        return@launch
                    }
                    delay(listenAlongPauseMs * 2)
                }
            }
        }
    }

    /**
     * The song heard: the one shown keeps in time with it; another one takes over the words, once
     * the one shown was heard playing, as the next song. A song picked to read stays.
     */
    private fun heardAlong(hearing: Heard.Song) {
        val song = hearing.metadata
        val shown = uiState.lyricsFor ?: return
        if (!sameSong(song, shown) && heardSong?.let { sameSong(it, shown) } != true) return
        // While a music app plays the song shown, it says exactly what plays: a hearing naming it
        // otherwise, as Shazam calls "Song (2018 Remix)" just "Song (Remix)", is the same song.
        if (!sameSong(song, shown) && appFollowing?.clock?.playing == true) return
        if (hearing.clock != null) heardAtMs = now()
        appFollowing?.takeIf(::learnsGap)?.let { learnGap(it, shown, hearing) }
        val gap = if (sameSong(song, shown)) gapOf(hearing) else 0L
        val heard = hearing.copy(offsetMs = hearing.offsetMs?.plus(gap))
        appFollowing?.takeIf { needsLead(it) && syncPoints.isEmpty() }?.let { app ->
            val extra = app.durationMs?.let { playing -> found?.lengthMs?.let { playing - it } }
            heard.clock?.let { clock -> leadTiming.heard(app.clock, clock, extra) }?.let { lead ->
                appLeadMs = lead
                appLeadVerified = true
                leadMatch = hearing.matchId
                leadStore.save(shown, lead)
                if (app.video) leadSavedLines(shown, app.appPackage, lead)
                if (autoCalibrating) stopListeningAlong()
            }
        }
        // Heard by another name right where the song shown is, well into it, it's the same recording
        // on another release, as Shazam sometimes names it, not the next song, which starts over.
        val clock = heardClock
        val at = heard.clock
        if (at != null && clock != null && clock.playing && heardSong?.let { sameSong(it, shown) } == true &&
            clock.positionAt(now()) >= SAME_RECORDING_AFTER_MS && abs(at.positionAt(now()) - clock.positionAt(now())) <= SAME_RECORDING_MS
        ) {
            heardClock = steadied(clock, at)
            return keepInTime()
        }
        if (heardSong?.let { sameSong(it, song) } != true || !sameSong(song, shown)) {
            heardSong = song
            hear(heard.clock, hearing.matchId, gap)
            unconfirmed = null
            if (!sameSong(song, shown)) {
                remember(heard)
                return showLyricsFor(song)
            }
        } else {
            hear(steadied(heardClock, heard.clock ?: return), hearing.matchId, gap)
        }
        keepInTime()
    }

    /** Whether the video playing may still be before its song: it isn't yet as far in as it's longer than the song. */
    private fun songMayNotHaveStarted(): Boolean {
        val app = appFollowing?.takeIf { it.video } ?: return false
        val extra = app.durationMs?.let { playing -> found?.lengthMs?.let { playing - it } } ?: return false
        return app.clock.positionAt(now()) < extra
    }

    /**
     * Whether [app] plays the very release the words are timed for, as long, so where it is is where
     * they are: what's heard then tells how far Shazam's place is from theirs, once for the song.
     */
    private fun learnsGap(app: Following): Boolean {
        val words = wordsMs ?: return false
        val shown = uiState.lyricsFor ?: return false
        return !app.video && app.durationMs != null && found?.timedFor(app.durationMs) == true && syncPoints.isEmpty() && !gapStore.knows(shown, words)
    }

    /** How long the recording the words are timed for is, when they're timed and say. */
    private val wordsMs get() = found?.lengthMs?.takeIf { foundLines.isNotEmpty() }

    private fun learnGap(app: Following, shown: MusicMetadata, heard: Heard.Song) {
        val match = heard.matchId ?: return
        val words = wordsMs ?: return
        if (match != gapMatch) {
            gapMatch = match
            gapTiming = LeadTiming()
        }
        val gap = heard.clock?.let { gapTiming.heard(app.clock, it, -MAX_GAP_MS..MAX_GAP_MS) } ?: return
        gapStore.save(shown, words, match, gap)
        if (autoCalibrating) stopListeningAlong()
    }

    /** How far the words are from Shazam's place in [heard], as learnt for the recording it matched. */
    private fun gapOf(heard: Heard.Song): Long = heard.matchId?.let { match -> wordsMs?.let { gapStore.load(it, match) } } ?: 0L

    /** Where the song is heard to be, by Shazam's place in the recording it matched, [match], moved by [gapMs]. */
    private fun hear(clock: PlaybackClock?, match: String? = null, gapMs: Long = 0L) {
        heardClock = clock
        heardMatch = match
        heardGapMs = gapMs
    }

    /**
     * Where the song was heard, moved by the gap now known for the recording Shazam matched, e.g.
     * once the words are found, or after they're put in time by hand.
     */
    private fun placeHeard() {
        val match = heardMatch ?: return
        val words = wordsMs ?: return
        val gap = gapStore.load(words, match) ?: 0L
        if (gap == heardGapMs) return
        heardClock = heardClock?.let { it.copy(positionMs = it.positionMs + gap - heardGapMs) }
        heardGapMs = gap
        unconfirmed = null
    }

    /**
     * A song heard along the way joins Recent, as one Crosstune listened for does: by its Apple Music
     * link when Shazam knows it, or else by its name, its cover found by its name when it came with none,
     * or only with the one its app shows, which is kept for a while only.
     */
    private fun remember(heard: Heard.Song) {
        val link = heard.appleMusicId?.let { (MusicLinks.parse("https://www.shazam.com/song/$it") as? LinkInput.Link)?.link }
            ?: MusicLinks.recognizedSong(heard.metadata).let { MusicLink(null, ItemType.TRACK, it.url, it.url) }
        uiState = uiState.copy(history = historyStore.add(HistoryEntry(link, heard.metadata)))
        // Named by an app that gave no cover on the web, it's found by its name, so Recent and its words show one.
        if (heard.metadata.artworkUrl?.startsWith("https://") == true) return
        viewModelScope.launch {
            val cover = withContext(systemDispatcher) { matcher.cover(heard.metadata, COVER_TIMEOUT_MS) } ?: return@launch
            val shown = uiState.lyricsFor?.takeIf { it == heard.metadata }
            uiState = uiState.copy(
                history = historyStore.update(link.url, heard.metadata.copy(artworkUrl = cover)),
                lyricsFor = shown?.copy(artworkUrl = cover) ?: uiState.lyricsFor
            )
        }
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

    /**
     * The words shown keep to where the song was heard, unless a music app playing it says, more
     * exactly. A video the app plays may open with more than the song, so how far it's ahead is
     * taken from where the song was just heard, and taken off.
     */
    private fun keepInTime() {
        val shown = uiState.lyricsFor ?: return
        appFollowing?.takeIf { it.video && !appLeadVerified && !calibrationAttempted && syncPoints.isEmpty() }?.let { app -> leadOf(app, shown)?.let { appLeadMs = it } }
        // Put in time by hand, the words keep to the guess they were put in time from.
        appFollowing?.takeIf { it.video && appLeadMs == null && calibrationAttempted && syncPoints.isEmpty() && !songMayNotHaveStarted() }?.let { app -> guessLead(app, shown) }
        uiState = uiState.copy(following = inSong(appFollowing) ?: heardFollowing(shown), lyricsOffTake = timedForAnother(shown))
        hearSinging()
    }

    /**
     * Lines the timed words up to the singing, once for the song and its words: what the phone plays
     * is heard a while at a time, through the song, and each line heard moves to where it's sung, as
     * putting it in time by hand would, which Reset undoes. Only while a music app on the phone plays
     * it, lined up otherwise, with nothing put in time by hand, and Crosstune in sight with the words.
     */
    private fun hearSinging() {
        val hearing = singing ?: return
        val song = uiState.lyricsFor ?: return
        val lines = foundLines
        if (singingJob?.isActive == true || singingTries >= SINGING_TRIES) return
        if (shazamLeads || nextPaused) return
        if (lines.isEmpty() || !playingLinedUp() || !mayCalibrate()) return
        // With the app's clock sure, the words are heard once for the song; unsure, once each time it's picked up.
        if (if (clockSure) (uiState.lyricsSynced && !liningUp) || timedByHand || handGap != null || sungStore.heard(song, lines) else heardThisTime) return
        singingJob = viewModelScope.launch {
            try {
                hearOn(hearing, song, lines)
            } finally {
                // Given up on, or stopped, nothing says the words are wrong, so they're shown as timed.
                doubtTiming(false)
            }
        }
    }

    /** Hears the singing a while at a time, through the song, and puts the words where it's heard. */
    private suspend fun hearOn(hearing: SingingHearing, song: MusicMetadata, lines: List<LyricLine>) {
        // Where in the song the singing was heard up to, so the next while heard is further on.
        var heardTo: Long? = null
        // Heard nothing to match, the words may be timed far off, so where they're timed says nothing.
        var straightOn = false
        // Whether any singing was found where the words are, while hearing on.
        var found = false
        while (true) {
            // Heard against the lines as they're shown, so already where they're sung, a little off at most.
            val shown = LyricsSync.adjust(lines, shownPoints)
            // Heard where lines are sung, so there are words to hear.
            delay(untilSung(shown, uiState.following?.clock ?: return, heardTo, straightOn) ?: return)
            val clock = uiState.following?.clock?.takeIf { playingLinedUp() } ?: return
            singingTries++
            val sung = hearOnce(hearing, lines, heardFirst(song, lines, clock, shown, heardTo == null)) ?: return
            if (!stillPlays(song, lines, clock)) return
            // Nothing heard sung where the words are timed, as when they're timed into an intro, the next
            // while is heard straight on, wherever the words are timed: the singing may be well after them.
            val shift = SungAlignment.shiftMs(sung, clock::positionAt, shown)
            straightOn = shift == null
            found = foundSinging(found, sung, clock, shown, shift)
            val first = heardTo == null
            heardTo = clock.positionAt(sung.startedAtMs) + SingingListener.SECONDS * 1000L
            if (shift == null) {
                if (singingTries >= SINGING_TRIES) return
                continue
            }
            singingTries = 0
            if (!clockSure) placeThisTime(sung, clock, shown, shift)
            else if (placeForGood(song, lines, sung, clock, shown, shift, first)) return
        }
    }

    /**
     * The first few seconds of a while, told as soon as they're heard: lines sung in them are put where
     * they're heard at once, not once the whole while is, as words timed far off are wrong all the while.
     * On the song's [first] while, no voice where the [shown] lines are timed doubts them instead: lines
     * timed into an intro aren't shown sung all the while it's heard.
     */
    private fun heardFirst(song: MusicMetadata, lines: List<LyricLine>, clock: PlaybackClock, shown: List<LyricLine>, first: Boolean): suspend (Sung) -> Unit = { heard ->
        if (stillPlays(song, lines, clock)) {
            val shift = SungAlignment.shiftMs(heard, clock::positionAt, shown)
            if (shift != null) placeHeard(song, heard, clock, shown, shift)
            else if (first && noVoice(heard, clock, shown)) doubtTiming(true)
        }
    }

    /** The lines heard in [sung] put where they're sung, [shift] off as a whole, before the rest of the while is in. */
    private fun placeHeard(song: MusicMetadata, sung: Sung, clock: PlaybackClock, shown: List<LyricLine>, shift: Long) {
        doubtTiming(false)
        if (!clockSure) placeThisTime(sung, clock, shown, shift)
        else placeSung(song, SungAlignment.linesMs(sung, clock::positionAt, shown, shift.takeIf { abs(it) >= SINGING_NUDGE_MS } ?: 0L))
    }

    /** What's heard sung, in the words' language, its [first] seconds told as they're heard; null when nothing is, as for want of the speech model, which is offered then. */
    private suspend fun hearOnce(hearing: SingingHearing, lines: List<LyricLine>, first: (suspend (Sung) -> Unit)?): Sung? {
        val sung = hearing.hear(lyricsLanguage?.of(lines.map { it.text })?.substringBefore('-'), first)
        // The model isn't fetched on mobile data by itself: it's offered by hand.
        if (sung == null && hearing.missesModel && !modelClosed) uiState = uiState.copy(singingModel = ModelOffer.MISSING)
        return sung
    }

    /** Whether [song] still plays as it did by [clock], with the same words: moved, paused or gone on, what was heard can't be placed. */
    private fun stillPlays(song: MusicMetadata, lines: List<LyricLine>, clock: PlaybackClock): Boolean {
        val playing = uiState.following?.clock ?: return false
        return uiState.lyricsFor == song && foundLines == lines && playing.playing && abs(playing.positionAt(now()) - clock.positionAt(now())) <= STEADY_CLOCK_MS
    }

    /**
     * Whether singing's been found where the words are, [found] before or now, [shift] off. Till it is,
     * no voice heard where they're timed doubts them, so no line's shown sung.
     */
    private fun foundSinging(found: Boolean, sung: Sung, clock: PlaybackClock, shown: List<LyricLine>, shift: Long?): Boolean {
        if (found) return true
        if (shift != null || noVoice(sung, clock, shown)) doubtTiming(shift == null)
        return shift != null
    }

    /** Whether no voice was heard in [sung] by when the first lines [shown] in it should have been sung. */
    private fun noVoice(sung: Sung, clock: PlaybackClock, shown: List<LyricLine>): Boolean {
        val voiceAt = sung.voiceAtMs?.let { clock.positionAt(sung.startedAtMs + it) } ?: return true
        val sungBy = shown.filter { it.text.isNotBlank() && it.timeMs >= clock.positionAt(sung.startedAtMs) }.getOrNull(SINGING_LINES - 1)?.timeMs
        return sungBy != null && voiceAt > sungBy
    }

    /**
     * The lines heard in [sung] put where they're sung for this time only, the app's [clock] unsure:
     * it may be off as much as the words. Words timed for another take drift further off as it goes,
     * so it's heard on through the song. Lines heard but none told where, the first is [shift] off.
     */
    private fun placeThisTime(sung: Sung, clock: PlaybackClock, shown: List<LyricLine>, shift: Long) {
        heardThisTime = true
        val placed = SungAlignment.linesMs(sung, clock::positionAt, shown, shift).ifEmpty { mapOf(shown.indexOfFirst { it.timeMs + shift >= clock.positionAt(sung.startedAtMs) }.coerceAtLeast(0) to null) }
        heardPoints = placed.entries.fold(shownPoints) { kept, (line, ms) -> LyricsSync.with(kept, SyncPoint(foundLines[line].timeMs, ms ?: (shown[line].timeMs + shift))) }
        showPoints()
    }

    /**
     * The lines heard in [sung] put where they're sung for good, the app's [clock] sure; the [first]
     * heard far off is a problem found. Whether it's done: heard to the end, or as near as there's
     * enough singing left to hear.
     */
    private fun placeForGood(song: MusicMetadata, lines: List<LyricLine>, sung: Sung, clock: PlaybackClock, shown: List<LyricLine>, shift: Long, first: Boolean): Boolean {
        if (first && abs(shift) >= SUNG_OFF_MS) problemFound(SongProblem.SUNG_OFF)
        val wholeMs = shift.takeIf { abs(it) >= SINGING_NUDGE_MS } ?: 0L
        placeSung(song, SungAlignment.linesMs(sung, clock::positionAt, shown, wholeMs))
        val done = untilSung(LyricsSync.adjust(lines, shownPoints), clock, clock.positionAt(sung.startedAtMs) + SingingListener.SECONDS * 1000L) == null
        liningUp = !done
        if (done) sungStore.save(song, lines)
        return done
    }

    /**
     * Each line of the words [placed] where it was heard sung, by its index, as putting it in time by
     * hand would: the lines between stretch to fit, and those past the last go on at the pace they set.
     */
    private fun placeSung(song: MusicMetadata, placed: Map<Int, Long>) {
        val shown = LyricsSync.adjust(foundLines, syncPoints)
        if (placed.none { (line, ms) -> ms != shown[line].timeMs }) return
        setSync(song, placed.entries.fold(syncPoints) { kept, (line, ms) -> LyricsSync.with(kept, SyncPoint(foundLines[line].timeMs, ms)) })
    }

    /** The lines put in time: where they were heard this time, with the app's clock unsure, or else as kept. */
    private val shownPoints get() = heardPoints.ifEmpty { syncPoints }

    private fun showPoints() {
        uiState = uiState.copy(lyricLines = shown(LyricsSync.adjust(foundLines, shownPoints)))
    }

    /** Forgets where lines were heard this time, as the app's clock is sure now, or it stopped. */
    private fun forgetHeard() {
        if (heardPoints.isEmpty()) return
        heardPoints = emptyList()
        showPoints()
    }

    /**
     * Whether the app's [clock] plays on from where it was, or jumped: picked up, moved, or played
     * again after a pause from somewhere else. Said fresh as the song starts, it's sure; jumped
     * otherwise, it may be behind, so the singing is heard again.
     */
    private fun noteClock(clock: PlaybackClock) {
        val at = now()
        val last = lastAppClock
        lastAppClock = clock
        val starts = clock.playing && clock.positionMs <= SURE_START_MS && at - clock.atMs <= SURE_START_MS
        if (starts) {
            // Each time it's played again, the singing may be heard a few more times.
            if (!clockSure || heardPoints.isNotEmpty()) {
                heardThisTime = false
                singingTries = 0
            }
            clockSure = true
            forgetHeard()
        } else if (last == null || abs(clock.positionAt(at) - last.positionAt(at)) > SAME_CLOCK_MS) {
            clockSure = false
            heardThisTime = false
            singingTries = 0
        }
    }

    /** Whether a music app on the phone plays the song, lined up with its words. */
    private fun playingLinedUp() = uiState.following?.let { it.app != null && it.clock.playing && (it.timingKnown || unheard) } == true

    /** Whether to [doubt] where the words are timed, with no singing heard there, so no line's shown sung till it's found. */
    private fun doubtTiming(doubt: Boolean) {
        if (unheard == doubt) return
        unheard = doubt
        uiState = uiState.copy(following = inSong(appFollowing) ?: uiState.following)
    }

    /**
     * How long until what's heard from then on has lines sung in it, by [clock]; null when no stretch
     * of the song left has enough. Only from [after] on, when the song was heard up to there, and
     * [straightOn] from there, wherever the lines are timed.
     */
    private fun untilSung(lines: List<LyricLine>, clock: PlaybackClock, after: Long?, straightOn: Boolean = false): Long? {
        val here = clock.positionAt(now())
        val at = maxOf(here, after ?: here)
        val heardMs = SingingListener.SECONDS * 1000L
        // Heard from just before a line starts, or now, as one just has: a while opening on music alone, the model may hear only that.
        val starts = lines.filter { it.text.isNotBlank() && it.timeMs + SINGING_LEAD_MS >= at }.map { maxOf(at, it.timeMs - SINGING_LEAD_MS) }
        val from = if (straightOn) at else starts.firstOrNull { from ->
            lines.count { it.text.isNotBlank() && it.timeMs in from until from + heardMs - SINGING_TAIL_MS } >= SINGING_LINES
        } ?: return null
        return ((from - here) / clock.speed).toLong()
    }

    /**
     * Whether what [app] plays may be ahead of, or behind, where the words are timed: a video, which
     * may open with more than the song, or a release not as long as the one they're timed for.
     */
    private fun needsLead(app: Following) = app.video || found?.timedFor(app.durationMs) == false

    /** Whether [song]'s lines are timed for a recording not as long as the one playing (the app's own, for a video), so likely off, and not put right yet. */
    private fun timedForAnother(song: MusicMetadata) =
        foundLines.isNotEmpty() && syncPoints.isEmpty() && handGap == null && appLeadMs == null && found?.timedFor(song.lengthMs ?: appFollowing?.durationMs) == false &&
            !(lining && alongJob?.isActive == true)

    /** Takes how far [app]'s video is ahead of [song] from its last hearing, when that's a lead a video could have. */
    private fun guessLead(app: Following, song: MusicMetadata) {
        val extra = app.durationMs?.let { playing -> found?.lengthMs?.let { playing - it } }
        leadOf(app, song)?.takeIf { it in LeadTiming.possible(extra) }?.let { guessedLeadMs = it }
    }

    /** How far [app]'s video is ahead of [song], by where the song was heard a moment ago, both playing. */
    private fun leadOf(app: Following, song: MusicMetadata): Long? {
        val heard = heardClock?.takeIf { it.playing && app.clock.playing && now() - heardAtMs <= HEARD_LATELY_MS } ?: return null
        if (heardSong?.let { sameSong(it, song) } != true) return null
        val at = now()
        return app.clock.positionAt(at) - heard.positionAt(at)
    }

    /**
     * Where the song is in what [app] plays: its place less how far it was heard to be ahead of the
     * words. A video not yet heard, so not lined up, gives way to where the song was heard, if it was;
     * while it's being lined up, it goes by the last hearing. With no singing heard where the words
     * are timed, and none found yet, no line's shown sung.
     */
    private fun inSong(app: Following?): Following? = leadTaken(app)?.let { if (unheard) it.copy(timingKnown = false) else it }

    /** [app]'s place less how far it was heard to be ahead of the words, as [inSong] has it. */
    private fun leadTaken(app: Following?): Following? {
        if (app == null || (!app.video && appLeadMs == null)) return app
        val lead = leadNow ?: return if (calibrationAttempted || syncPoints.isNotEmpty()) app.copy(timingKnown = syncPoints.isNotEmpty())
            else uiState.lyricsFor?.let(::heardFollowing) ?: app.copy(timingKnown = false)
        return app.copy(clock = app.clock.copy(positionMs = app.clock.positionMs - lead))
    }

    /** [positionMs] in the song, where the app playing it has it: later, in a video ahead of it. */
    private fun inApp(positionMs: Long) = positionMs + (leadNow?.takeIf { appFollowing != null } ?: 0L)

    /** How far the video is ahead: as lined up, or else as last heard. */
    private val leadNow get() = appLeadMs ?: guessedLeadMs

    fun stopListeningAlong() {
        autoCalibrating = false
        pauseListeningAlong()
        // Not lined up by ear after all, the words may be put in time by hand.
        uiState = uiState.copy(listeningAlong = false, lyricsOffTake = uiState.lyricsFor?.let(::timedForAnother) == true)
        // Shazam done with the phone's sound, the singing in it is heard.
        hearSinging()
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
     * Words that aren't timed still follow the app, to play and pause it from them.
     */
    private fun follow(song: MusicMetadata) {
        stopFollowing()
        leadTiming = LeadTiming()
        gapTiming = LeadTiming()
        gapMatch = null
        calibrationAttempted = false
        placeHeard()
        uiState = uiState.copy(following = heardFollowing(song))
        val source = playback?.takeIf { it.hasAccess() } ?: return
        followJob = viewModelScope.launch {
            source.follow(song).collect { playing ->
                if (playing?.clock?.playing == true && appFollowing?.clock?.playing != true) appPlaysAgain.tryEmit(Unit)
                // The app stopped it, was closed or went on to another song: the words stop where it left
                // them, rather than run on from where the song was heard before, until it's heard again.
                val left = inSong(appFollowing)?.clock?.takeIf { playing == null }?.let { PlaybackClock(it.positionAt(now()), now(), playing = false) }
                if (left != null && heardSong?.let { sameSong(it, song) } == true) hear(left)
                appFollowing = playing
                playing?.clock?.let(::noteClock)
                // Listening by itself only lines up what the app plays: once it plays this no more, there's
                // nothing for it, and the microphone would hear the room, as between two songs.
                if (playing == null && autoCalibrating) stopListeningAlong()
                if (playing?.let(::needsLead) == true && appLeadMs == null) {
                    appLeadMs = leadStore.load(song)?.also { if (playing.video) leadSavedLines(song, playing.appPackage, it) }
                    appLeadVerified = appLeadMs != null
                }
                // Once it's being listened for, only an intro heard to agree in several places lines the video up.
                if (playing?.video == true && appLeadMs == null && !calibrationAttempted && syncPoints.isEmpty()) leadOf(playing, song)?.let { appLeadMs = it }
                // Playing the release the words are timed for, it's heard too, once, to put them right on any other.
                val lines = playing?.let(::needsLead) == true && appLeadMs == null && syncPoints.isEmpty()
                if (playing != null && playing.clock.playing && (lines || learnsGap(playing)) && !calibrationAttempted && mayCalibrate()) {
                    calibrationAttempted = true
                    if (!uiState.listeningAlong) autoCalibrating = true
                    listenAlong()
                }
                // What the app plays tells whether the words are timed for it.
                uiState = uiState.copy(following = inSong(playing) ?: heardFollowing(song) ?: left?.let { Following(it, app = null, canSeek = false) }, lyricsOffTake = timedForAnother(song))
                hearSinging()
            }
        }
    }

    /**
     * Once the song shown was playing, the next song a music app plays takes over its words: when the
     * app goes on to it, or it's skipped to, or another app plays it instead. Until then, e.g. for a
     * song picked to read while another plays, the words stay. Not while Crosstune is out of sight,
     * with no words floating; back in sight, it catches up.
     */
    private fun watchNext(song: MusicMetadata) {
        nextJob?.cancel()
        nextJob = null
        val source = playback?.takeIf { it.hasAccess() && !nextPaused } ?: return
        nextJob = viewModelScope.launch {
            combine(source.follow(song), source.nowPlayingUpdates(), ::Pair).collect { (app, playing) ->
                if (app?.clock?.playing == true) shownPlayed = true
                // Practice moves the music app from song to song, which the words stay out of.
                if (uiState.practice != null) return@collect
                val skips = (app?.canSkipNext == true) to (app?.canSkipPrevious == true)
                if (skips != (uiState.canSkipNext to uiState.canSkipPrevious)) uiState = uiState.copy(canSkipNext = skips.first, canSkipPrevious = skips.second)
                // An app still on the song shown, by another name, e.g. "Song - Remastered", or paused there, plays no other.
                val next = playing?.takeIf { shownPlayed && app == null && !sameSong(it.metadata, song) } ?: return@collect
                remember(next)
                showLyricsFor(next.metadata, playing = true)
            }
        }
    }

    /** Crosstune went out of sight with no words floating: the words stay on the song they show until it's back. */
    fun pauseFollowingNext() {
        nextPaused = true
        nextJob?.cancel()
        nextJob = null
        // Out of sight, the singing isn't heard either, until it's back.
        singingJob?.cancel()
    }

    fun resumeFollowingNext() {
        if (!nextPaused) return
        nextPaused = false
        uiState.lyricsFor?.let(::watchNext)
        hearSinging()
    }

    /** Has the music app the words follow go on to its [next] song, or back to the one before, whose words then show. */
    fun skipSong(next: Boolean) {
        val song = uiState.lyricsFor ?: return
        if (!(if (next) uiState.canSkipNext else uiState.canSkipPrevious)) return
        shownPlayed = true
        playback?.skip(song, next)
    }

    private fun stopFollowing() {
        followJob?.cancel()
        followJob = null
        singingJob?.cancel()
        singingJob = null
        singingTries = 0
        clockSure = false
        lastAppClock = null
        forgetHeard()
        heardThisTime = false
        liningUp = false
        appFollowing = null
        appLeadMs = null
        guessedLeadMs = null
        appLeadVerified = false
        leadMatch = null
        uiState = uiState.copy(following = null)
    }

    /** Back from Android's settings, where following music apps may have just been allowed. */
    fun refreshFollowing() {
        val access = playback?.hasAccess() == true
        if (access == uiState.canFollowApps) return
        uiState = uiState.copy(canFollowApps = access)
        uiState.lyricsFor?.takeIf { !uiState.isLoadingLyrics }?.let(::follow)
        followPlayer()
    }

    /** Whether the microphone may be used now, just asked about. */
    fun refreshMicrophone() {
        uiState = uiState.copy(microphone = microphoneAccess())
    }

    /** The list of things to fix, from the bar; one tap starts afresh there. */
    fun openFixes() {
        uiState = uiState.copy(fixes = Fixes(list = true), oneTapFailed = false)
    }

    /** The steps of [needs], one at a time, from the list or wherever they're needed. */
    fun fixSteps(needs: List<Need>) {
        uiState = uiState.copy(fixes = Fixes(list = uiState.fixes?.list == true, steps = needs))
    }

    /** On to the next thing to fix, done or skipped; after the last, back to the list, or out. */
    fun nextFix() {
        val fixes = uiState.fixes ?: return
        uiState = uiState.copy(fixes = if (fixes.at + 1 < fixes.steps.size) fixes.copy(at = fixes.at + 1) else fixes.listed())
    }

    /** Out of the steps, back to the list they came from, if they did. */
    fun fixBack() {
        uiState = uiState.copy(fixes = uiState.fixes?.listed())
    }

    private fun Fixes.listed(): Fixes? = Fixes(list = true).takeIf { list }

    fun closeFixes() {
        uiState = uiState.copy(fixes = null)
    }

    /**
     * Before Android 12, which can't tell, a test link to [host] reached Crosstune: its links open
     * here, so the app of the service the link is of doesn't take them.
     */
    fun linkTested(host: String?) {
        val service = LinkInterception.HOSTS.keys.firstOrNull { host == testHost(it) }
        val apps = uiState.installedSourceApps.filter { service != null && MusicService.ofPackage(it.packageName) == service }
        uiState = uiState.copy(linksTested = uiState.linksTested + Need.Links.key + apps.map { Need.StopApp(it).key })
    }

    fun selectPalette(palette: Palette) {
        preferences.edit { putString(KEY_PALETTE, palette.name) }
        uiState = uiState.copy(palette = palette)
    }

    fun setPureBlack(on: Boolean) {
        preferences.edit { putBoolean(KEY_PURE_BLACK, on) }
        uiState = uiState.copy(pureBlack = on)
    }

    fun setPlayingBar(on: Boolean) {
        preferences.edit { putBoolean(KEY_PLAYING_BAR, on) }
        uiState = uiState.copy(playingBar = on)
    }

    fun setLinesEarly(ms: Long) {
        preferences.edit { putLong(KEY_LINES_EARLY, ms) }
        uiState = uiState.copy(linesEarlyMs = ms)
    }

    fun setNextLineFirst(on: Boolean) {
        preferences.edit { putBoolean(KEY_NEXT_LINE_FIRST, on) }
        uiState = uiState.copy(nextLineFirst = on)
    }

    /** Kept only once let go, [save], rather than at every step of the slider. */
    fun setVisualsShade(shade: Float, save: Boolean) {
        if (save) preferences.edit { putFloat(KEY_VISUALS_SHADE, shade) }
        uiState = uiState.copy(visualsShade = shade)
    }

    fun nextVisual() {
        uiState = uiState.copy(visualsSkips = uiState.visualsSkips + 1)
    }

    /** Never shows the visual showing again, and moves on. */
    fun dropVisual() {
        uiState = uiState.copy(visualsDrops = uiState.visualsDrops + 1)
    }

    /** Visual [name] on or off, as Settings lists them. */
    fun setVisualOn(name: String, on: Boolean) = visualsStore.turn(name, on)

    fun setSkipHeavyVisuals(on: Boolean) = visualsStore.skipHeavy(on)

    fun setVisualsChangeEvery(ms: Long) = visualsStore.changeEvery(ms)

    fun setVisuals(on: Boolean) {
        preferences.edit { putBoolean(KEY_VISUALS, on) }
        uiState = uiState.copy(visuals = on)
    }

    /**
     * How the floating words look and where they are, as changed on them; kept once a change is done,
     * e.g. at the end of a drag, rather than at every step of it.
     */
    fun setFloating(options: FloatingOptions, save: Boolean) {
        uiState = uiState.copy(floating = options)
        if (save) preferences.edit {
            putFloat(KEY_FLOATING_BACKGROUND, options.background)
            putFloat(KEY_FLOATING_SCALE, options.scale)
            putBoolean(KEY_FLOATING_PREVIOUS_LINE, options.previousLine)
            putBoolean(KEY_FLOATING_NEXT_LINE, options.nextLine)
            putBoolean(KEY_FLOATING_LOCKED, options.locked)
            putInt(KEY_FLOATING_LEFT, options.left)
            putInt(KEY_FLOATING_TOP, options.top)
            putBoolean(KEY_FLOATING_VISUALS, options.visuals)
        }
    }

    fun setCanFloatOverApps(can: Boolean) {
        uiState = uiState.copy(canFloatOverApps = can)
    }

    fun selectTheme(theme: ThemeMode) {
        preferences.edit { putString(KEY_THEME, theme.name) }
        uiState = uiState.copy(theme = theme)
    }

    /** Moves the music app playing the song to a line of its words. */
    fun seekLyrics(positionMs: Long) {
        val song = uiState.lyricsFor ?: return
        if (uiState.following?.let { it.canSeek && it.timingKnown } != true) return
        playback?.seekTo(song, inApp(positionMs))
    }

    /**
     * Line [index] is being sung right now, the user says: it's put there, and the lines around it
     * move with it. Only while something says where the song is.
     */
    fun syncLine(index: Int) {
        val song = uiState.lyricsFor ?: return
        val line = foundLines.getOrNull(index) ?: return
        val position = uiState.following?.clock?.positionAt(now()) ?: return
        handTimed()
        val point = SyncPoint(line.timeMs, position.coerceAtLeast(0L))
        val hand = handGap
        if (syncPoints.isEmpty() && (hand == null || hand.lineMs == line.timeMs) && moveGap(song, point.songMs - point.lineMs, line.timeMs)) return
        // Another line put in time after the gap moved stretches the words from the one that moved it, now in place.
        setSync(song, LyricsSync.with(if (syncPoints.isEmpty() && hand != null) listOf(SyncPoint(hand.lineMs, hand.lineMs)) else syncPoints, point))
    }

    /** Plays or pauses the song in the music app the words follow. */
    fun playPause() {
        val song = uiState.lyricsFor ?: return
        val app = uiState.following?.takeIf { it.app != null } ?: return
        playback?.playPause(song, !app.clock.playing)
    }

    /** All the words [deltaMs] later in the song (earlier, when it's negative), for a song heard a little off. */
    fun nudgeSync(deltaMs: Long) {
        if (uiState.lyricsFor != null && foundLines.isNotEmpty()) handTimed()
        nudge(deltaMs)
    }

    /** The words being put in time by hand: offered to be reported by itself now, while they're seen, but sent once they're left. */
    private fun handTimed() {
        timedByHand = true
        // Put in time by hand, the singing isn't heard to put them in time too.
        liningUp = false
        singingJob?.cancel()
        if (autoReports.allowed == null) problemFound(SongProblem.TIMED_BY_HAND)
    }

    private fun nudge(deltaMs: Long) {
        val song = uiState.lyricsFor ?: return
        val first = foundLines.firstOrNull() ?: return
        if (syncPoints.isEmpty() && moveGap(song, deltaMs, handGap?.lineMs ?: first.timeMs)) return
        setSync(song, if (syncPoints.isEmpty()) listOf(SyncPoint(first.timeMs, first.timeMs + deltaMs)) else syncPoints.map { it.copy(songMs = it.songMs + deltaMs) })
    }

    /** A report on the words shown, [song]'s: how they're timed, and each line put in time by hand. */
    private fun reportOn(sender: ReportSender, song: MusicMetadata?, comment: String = "", problem: SongProblem? = null) = Report(
        sender.version, song, timed = uiState.lyricLines.isNotEmpty(), synced = uiState.lyricsSynced, app = uiState.following?.app, comment = comment,
        sung = song != null && sungStore.heard(song, foundLines), lengthMs = found?.lengthMs, timing = handTiming(), problem = problem, source = found?.source
    )

    /** Sends a report on the words shown, with what the user says is wrong. [UiState.report] says how it goes. */
    fun sendReport(comment: String) {
        val sender = reports ?: return
        val report = reportOn(sender, uiState.lyricsFor, comment)
        uiState = uiState.copy(report = ReportStatus.SENDING)
        viewModelScope.launch {
            val sent = sender.send(report)
            uiState = uiState.copy(report = if (sent) ReportStatus.SENT else ReportStatus.FAILED)
        }
    }

    fun closeReport() {
        uiState = uiState.copy(report = null)
    }

    /** What the offer to report problems by itself was made for, to be sent if it's taken. */
    private var offeredFor: SongProblem? = null

    /** Whether the words shown were put in time by hand, rather than moved to the singing heard. */
    private var timedByHand = false

    /**
     * [problem] found with the words shown: reported by itself, once a song, when the user lets it;
     * offered to be from now on, while they haven't said.
     */
    private fun problemFound(problem: SongProblem) {
        val song = uiState.lyricsFor ?: return
        when (autoReports.allowed) {
            true -> reportProblem(song, problem)
            null -> if (problem.offered) {
                offeredFor = problem
                uiState = uiState.copy(reportOffer = true)
            }
            false -> Unit
        }
    }

    private fun reportProblem(song: MusicMetadata, problem: SongProblem) {
        val sender = reports ?: return
        if (autoReports.reported(song, problem)) return
        val report = reportOn(sender, song, problem = problem)
        // One that didn't get there goes the next time it's found.
        viewModelScope.launch { if (sender.send(report)) autoReports.remember(song, problem) }
    }

    /**
     * The words shown are left: put in time by hand, they're reported, so with the timing settled;
     * an offer made for them goes.
     */
    private fun leaveWords() {
        if (timedByHand && (syncPoints.isNotEmpty() || handGap != null) && autoReports.allowed == true) uiState.lyricsFor?.let { reportProblem(it, SongProblem.TIMED_BY_HAND) }
        timedByHand = false
        offeredFor = null
        uiState = uiState.copy(reportOffer = false)
    }

    /** Fetches the speech model offered, on any network, then hears the singing again with it. */
    fun fetchSingingModel() {
        val hearing = singing ?: return
        uiState = uiState.copy(singingModel = ModelOffer.FETCHING)
        viewModelScope.launch {
            val fetched = hearing.fetchModel()
            uiState = uiState.copy(singingModel = if (fetched) null else ModelOffer.FAILED)
            if (fetched) {
                singingTries = 0
                hearSinging()
            }
        }
    }

    /** The speech model isn't wanted for now. */
    fun closeSingingModel() {
        modelClosed = true
        uiState = uiState.copy(singingModel = null)
    }

    /** Problems found from now on are reported by itself, or never, and the one offered for goes too. */
    fun allowAutoReports(allowed: Boolean) {
        autoReports.allowed = allowed
        uiState = uiState.copy(autoReports = allowed, reportOffer = false)
        val offered = offeredFor
        offeredFor = null
        // The words put in time by hand go once they're left.
        if (allowed && offered != null && offered != SongProblem.TIMED_BY_HAND) problemFound(offered)
    }

    /**
     * Each line put in time by hand, and where it's heard: those the words moved from, and the one
     * that moved Shazam's gap, by as much as it moved.
     */
    private fun handTiming(): List<HandTiming> {
        fun line(ms: Long) = foundLines.firstOrNull { it.timeMs == ms }?.text.orEmpty()
        val gap = handGap?.let { hand ->
            val now = wordsMs?.let { gapStore.load(it, hand.match) } ?: return@let null
            HandTiming(line(hand.lineMs), hand.lineMs, hand.lineMs + (hand.gapMs ?: 0L) - now)
        }
        return syncPoints.map { HandTiming(line(it.lineMs), it.lineMs, it.songMs) } + listOfNotNull(gap)
    }

    /** The words back in time as they were found, and Shazam's gap as it was before they were moved by hand. */
    fun resetSync() {
        val song = uiState.lyricsFor ?: return
        timedByHand = false
        // Undone while the singing was being heard through the song, it isn't heard on.
        if (liningUp) {
            liningUp = false
            singingJob?.cancel()
            sungStore.save(song, foundLines)
        }
        handGap?.let { hand ->
            handGap = null
            wordsMs?.let { words -> hand.gapMs?.let { gapStore.save(song, words, hand.match, it) } ?: gapStore.forget(words, hand.match) }
            hand.leadMs?.let { moveLead(song, it) }
            placeHeard()
        }
        setSync(song, emptyList())
    }

    /**
     * The words heard by Shazam put [shiftMs] later, by hand: it's how far Shazam's place in the
     * recording it matched is from them, so it's learnt as that recording's gap, for every release
     * Shazam matches to it, rather than kept for this one alone. False when they're not lined up by
     * Shazam, or the gap would be too far to believe.
     */
    private fun moveGap(song: MusicMetadata, shiftMs: Long, lineMs: Long): Boolean {
        val words = wordsMs ?: return false
        val app = appFollowing
        // Words following a music app playing the release they're timed for are off themselves, not Shazam.
        val match = (if (app == null) heardMatch?.takeIf { heardClock != null } else leadMatch?.takeIf { needsLead(app) && appLeadMs != null }) ?: return false
        val before = gapStore.load(words, match)
        val gap = (before ?: 0L) - shiftMs
        if (abs(gap) > MAX_GAP_MS) return false
        if (handGap == null) handGap = HandGap(match, before, appLeadMs.takeIf { app != null }, lineMs)
        gapStore.save(song, words, match, gap)
        appLeadMs?.takeIf { app != null }?.let { moveLead(song, it + shiftMs) }
        placeHeard()
        uiState = uiState.copy(lyricsSynced = true, lyricsOffTake = false)
        keepInTime()
        return true
    }

    /** The music app's video put [leadMs] ahead of the words, and kept so, for it and the lines saved from it. */
    private fun moveLead(song: MusicMetadata, leadMs: Long) {
        appLeadMs = leadMs
        leadStore.save(song, leadMs)
        appFollowing?.takeIf { it.video }?.let { leadSavedLines(song, it.appPackage, leadMs) }
        // Hearings from before were taken with the gap as it was.
        leadTiming = LeadTiming()
    }

    private fun setSync(song: MusicMetadata, points: List<SyncPoint>) {
        syncPoints = points
        // Put in time by hand or for good, what was heard this time only gives way.
        heardPoints = emptyList()
        // Kept for the words as found, which is how they're found next time.
        syncStore.save(song, foundLines, points)
        uiState = uiState.copy(lyricLines = shown(LyricsSync.adjust(foundLines, points)), lyricsSynced = points.isNotEmpty(), lyricsOffTake = false)
        keepInTime()
        // Lines kept to practise play from where they're heard now.
        timeSavedLines(song, again = true)
    }

    private var restoreJob: Job? = null

    /**
     * Has ChatGPT write [song]'s words, found in Latin letters, in [script], Japanese or Korean, each
     * line timed as found; then they're worked out again in it, with readings of their own.
     */
    private fun writeInOwnScript(song: MusicMetadata, script: Script) {
        val lines = wordLines()
        restoreJob = viewModelScope.launch {
            val written = tutor?.fromRomaji(song, lines, script) ?: return@launch
            if (uiState.lyricsFor != song || wordLines() != lines) return@launch
            restored = written
            stopLearning()
            uiState = uiState.copy(
                lyrics = written.joinToString("\n"),
                lyricLines = shown(uiState.lyricLines),
                learning = uiState.learning.fresh(Readings.displayScriptOf(written)).copy(fromRomaji = true),
                chords = uiState.chords.fresh()
            )
            followSavedLines(song)
            learn()
            chords()
        }
    }

    /** Crosstune no longer covers the app playing, closed or back in sight itself. */
    fun uncover() {
        uiState = uiState.copy(coveringApp = false)
    }

    /**
     * Closes the words. The next song starts without them, rather than showing the last one's, and
     * what was heard is forgotten, so words opened later don't listen along to what plays by then.
     */
    /**
     * From the words down to their song's player: the one under them, or one opened on their song when
     * they were opened on their own, over that song's card, as the words may have gone on to another.
     */
    fun lyricsToPlayer() {
        val song = uiState.lyricsFor ?: return
        if (uiState.player == null) {
            openPlayer(song, uiState.following?.appPackage, played = shownPlayed)
            if (uiState.result?.let { sameSong(it, song) } != true) showSong(song)
        }
        dismissLyrics()
    }

    fun dismissLyrics() {
        heardSong = null
        leaveWords()
        keepShown()
        lyricsJob?.cancel()
        lyricsJob = null
        nextJob?.cancel()
        nextJob = null
        shownPlayed = false
        uiState = uiState.copy(canSkipNext = false, canSkipPrevious = false)
        stopLearning()
        stopFollowing()
        stopListeningAlong()
        uiState = uiState.copy(
            lyricsFor = null, lyrics = "", lyricLines = emptyList(), lyricsSynced = false, lyricsOffTake = false, lyricsFailed = false, isLoadingLyrics = false,
            learning = uiState.learning.fresh(), chords = uiState.chords.fresh()
        )
    }

    private var readingsJob: Job? = null
    private var translationJob: Job? = null

    /** What was being worked out is for words no longer shown. */
    private fun stopLearning() {
        chordsJob?.cancel()
        readingsJob?.cancel()
        translationJob?.cancel()
        restoreJob?.cancel()
        wordJob?.cancel()
        explanationJob?.cancel()
        songExplanationJob?.cancel()
        songFactsJob?.cancel()
        questionJob?.cancel()
        songQuestionJob?.cancel()
        wordsToKnowJob?.cancel()
        artistJob?.cancel()
        examplesJob?.cancel()
        stopRepeating()
        uiState = uiState.copy(word = null)
    }

    /** Keeps line [index] of the words shown, with what helps read it, or lets it go when it's kept. */
    fun toggleSavedLine(index: Int) {
        val line = savedLineAt(index) ?: return
        uiState = uiState.copy(savedLines = savedLinesStore.toggle(line))
    }

    /**
     * The lines of a song chosen to learn: lines [add] of the words shown kept, each with what helps
     * read it, and [remove] let go; then, when asked to [practise], the song's practised on its own.
     */
    fun chooseLines(add: List<Int>, remove: List<SavedLine>, practise: Boolean) {
        val lines = add.sorted().mapNotNull(::savedLineAt)
        if (lines.isNotEmpty()) uiState = uiState.copy(savedLines = savedLinesStore.addAll(lines))
        if (remove.isNotEmpty()) uiState = uiState.copy(savedLines = savedLinesStore.removeAll(remove))
        if (practise) practiseSong()
    }

    /** Keeps [lines] let go again, as they were, practised as far as they had been. */
    fun keepAgain(lines: List<SavedLine>) {
        uiState = uiState.copy(savedLines = savedLinesStore.addAll(lines))
    }

    /** Practises the lines of the song whose words show, and no other's. */
    fun practiseSong() {
        startPractice(uiState.lyricsFor ?: return)
    }

    /**
     * Line [index] of the words shown, as it's kept: with what helps read it, where it's sung, to
     * the next line, and the app playing it, to play it again.
     */
    private fun savedLineAt(index: Int): SavedLine? {
        val song = uiState.lyricsFor ?: return null
        val text = wordLines().getOrNull(index) ?: return null
        val learning = uiState.learning
        val reading = learning.lines.getOrNull(index)
        return SavedLine(
            text,
            reading?.reading?.takeIf { it != text },
            reading?.romanized?.takeIf { it.isNotBlank() && it != reading.reading },
            learning.translations.getOrNull(index),
            song.title,
            song.artist,
            learning.explanation?.takeIf { it.index == index && it.done }?.text,
            ruby = reading?.parts?.takeIf { parts -> parts.any { it.reading != null } },
            typed = reading?.typed?.takeIf { it.isNotBlank() && it != reading.romanized }
        ).timedAt(index)
    }

    /** This line, timed as line [index] of the timed words shown, when they are, with the app playing them. */
    private fun SavedLine.timedAt(index: Int): SavedLine {
        val lines = uiState.lyricLines
        val start = lines.getOrNull(index)?.timeMs ?: return copy(artworkUrl = uiState.lyricsFor?.artworkUrl)
        // Timed again, it stays with the app it was kept from, when none follows the words now.
        val app = appFollowing
        return copy(
            startMs = start, endMs = endOf(lines, index), leadMs = appLeadMs?.takeIf { app?.video == true } ?: leadMs.takeIf { app == null } ?: 0L,
            app = app?.appPackage ?: this.app, artworkUrl = uiState.lyricsFor?.artworkUrl ?: artworkUrl
        )
    }

    /**
     * Where line [index] of [lines] ends: where the words say, when they're timed that closely, or
     * else where the next line starts; the last line, with no next one, is given a few seconds.
     */
    private fun endOf(lines: List<LyricLine>, index: Int): Long {
        val line = lines[index]
        return line.endMs ?: lines.drop(index + 1).firstOrNull { it.timeMs > line.timeMs }?.timeMs ?: (line.timeMs + LAST_LINE_MS)
    }

    /**
     * Lines of [song] kept are read as its lines are now, put right, but for the ones put right by
     * hand; only those with no reading yet when [unread].
     */
    private fun rereadSavedLines(song: MusicMetadata, unread: Boolean = false) {
        val lines = wordLines()
        val again = uiState.savedLines.filter { it.isOf(song) && it.line == null && !it.readByHand && (!unread || it.ruby == null) }
            .mapNotNull { saved -> lines.indexOf(saved.text).takeIf { it >= 0 }?.let(::savedLineAt) }
        if (again.isNotEmpty()) uiState = uiState.copy(savedLines = savedLinesStore.reread(again))
    }

    /**
     * Lines of [song] kept from its words as they were, before they changed, e.g. found again on another
     * site, are kept as the lines they are now, practised as far as they had been: read and translated as
     * these are, and where these are sung. One kept already as it is now is left as it was.
     */
    private fun followSavedLines(song: MusicMetadata) {
        val lines = wordLines()
        val times = uiState.lyricLines.takeIf { it.isNotEmpty() }?.map { it.timeMs }
        val kept = uiState.savedLines.filter { it.isOf(song) }
        val taken = kept.filter { it.line == null }.mapTo(mutableSetOf()) { it.text }
        val changes = kept.mapNotNull { saved ->
            val was = saved.line ?: saved.text
            if (was in lines) return@mapNotNull null
            val index = lineNow(was, saved.startMs, lines, times).takeIf { it >= 0 } ?: return@mapNotNull null
            val now = lines[index]
            // A word kept stays with its line, while it's still sung in it.
            if (saved.line != null) return@mapNotNull (saved to saved.copy(line = now)).takeIf { saved.text in now }
            if (!taken.add(now)) return@mapNotNull null
            val read = savedLineAt(index) ?: return@mapNotNull null
            val moved = saved.copy(text = now, reading = read.reading, romanized = read.romanized, ruby = read.ruby, readByHand = false, typed = read.typed, translation = read.translation ?: saved.translation)
            saved to if (times != null) moved.timedAt(index) else moved
        }
        if (changes.isNotEmpty()) uiState = uiState.copy(savedLines = savedLinesStore.replaceAll(changes))
    }

    /** Lines of [song] kept are translated as its lines are now, e.g. into another language. */
    private fun retranslateSavedLines(song: MusicMetadata) {
        val lines = wordLines()
        val translations = uiState.learning.translations
        val changes = uiState.savedLines.filter { it.isOf(song) && it.line == null }.mapNotNull { saved ->
            val now = translations.getOrNull(lines.indexOf(saved.text))?.takeIf { it != saved.translation } ?: return@mapNotNull null
            saved to saved.copy(translation = now)
        }
        if (changes.isNotEmpty()) uiState = uiState.copy(savedLines = savedLinesStore.replaceAll(changes))
    }

    /**
     * Lines of [song] kept from the video [app] plays, before its intro was known, start where the song
     * does in it now that it is, so practice plays them, not the intro.
     */
    private fun leadSavedLines(song: MusicMetadata, app: String?, lead: Long) {
        val off = uiState.savedLines.filter { it.isOf(song) && it.timed && it.app == app && it.leadMs != lead }
        if (off.isNotEmpty()) uiState = uiState.copy(savedLines = savedLinesStore.replaceAll(off.map { it to it.copy(leadMs = lead) }))
    }

    /**
     * Lines of [song] kept before they could be timed are timed now, by the timed words found for it;
     * [again], all its lines are, as when the words were put in time by hand.
     */
    private fun timeSavedLines(song: MusicMetadata, again: Boolean = false) {
        val lines = uiState.lyricLines.takeIf { it.isNotEmpty() } ?: return
        val untimed = uiState.savedLines.filter { it.isOf(song) && (again || !it.timed) }
        untimed.forEach { saved ->
            val index = lines.indexOfFirst { it.text == (saved.line ?: saved.text) }.takeIf { it >= 0 } ?: return@forEach
            uiState = uiState.copy(savedLines = savedLinesStore.timed(saved.timedAt(index), again))
        }
    }

    /**
     * Practises the lines due, as cards, each song's together; or only [song]'s, all of them when none
     * is due, to learn it. What the music app plays now is kept, to go back to once done.
     */
    fun startPractice(song: MusicMetadata? = null) {
        stopRepeating()
        // The song the words follow, in its app; or else whatever plays, which the last app played has.
        val playing = uiState.following?.takeIf { it.clock.playing && it.appPackage != null }
        val before = uiState.lyricsFor?.let { shown -> playing?.let { Before(shown, inApp(it.clock.positionAt(now())), it.appPackage) } }
            ?: playback?.nowPlaying()?.let { heard -> heard.clock?.let { Before(heard.metadata, it.positionAt(now()), app = null) } }
        cards.start(song, before)
    }

    fun replayLine() = cards.replay()

    fun turnPractice() = cards.turn()

    fun readCardAs(readings: List<String>) = cards.readAs(readings)

    fun gradePractice(grade: Grade) = cards.grade(grade)

    fun stopPractice() = cards.stop()

    fun backToBefore() = cards.backToBefore()

    private var wordJob: Job? = null

    /**
     * Finds what [word], in line [index], means in the app's language: as used in that line, from
     * ChatGPT when signed in, or else the way the words are translated.
     */
    fun lookUpWord(word: Word, index: Int) {
        val translator = translator ?: return
        val line = wordLines().getOrNull(index)
        wordJob?.cancel()
        examplesJob?.cancel()
        uiState = uiState.copy(word = WordMeaning(word, looking = true), learning = uiState.learning.copy(examples = null))
        wordJob = viewModelScope.launch {
            val language = translateInto()
            val inLine = if (usesChatGpt() && line != null) tutor?.meaning(word.lookup, line, language) else null
            if (inLine != null) {
                uiState = uiState.copy(word = WordMeaning(word, inLine.meaning, type = inLine.type))
                return@launch
            }
            val translated = translator.translate(listOf(word.lookup), language)
            // Already in the app's language, a word means what the dictionary says.
            val definition = if (translated != null && translated.firstOrNull() == null) translator.define(word.lookup, language) else null
            val meaning = translated?.firstOrNull() ?: definition?.meaning
            uiState = uiState.copy(word = WordMeaning(word, meaning, failed = translated == null, type = definition?.type))
        }
    }

    fun dismissWord() {
        wordJob?.cancel()
        examplesJob?.cancel()
        uiState = uiState.copy(word = null, learning = uiState.learning.copy(examples = null))
    }

    /** Signed in with ChatGPT, which the AI features ask. */
    private fun usesChatGpt() = tutor != null && uiState.chatGpt.email != null

    private var explanationJob: Job? = null

    /** Has ChatGPT explain line [index]: what it means in the song, its grammar and references. */
    fun explainLine(index: Int) {
        val tutor = tutor ?: return
        val song = uiState.lyricsFor ?: return
        val lines = wordLines()
        val line = lines.getOrNull(index) ?: return
        explanationJob?.cancel()
        setExplanation(LineExplanation(index))
        explanationJob = viewModelScope.launch {
            val reply = tutor.explain(song, lines, index, translateInto()) { text ->
                // Written as it comes, unless it's done by the time this is shown.
                viewModelScope.launch { if (uiState.learning.explanation?.let { it.index == index && it.writing } == true) setExplanation(LineExplanation(index, text)) }
            }
            val explanation = explained(index, reply)
            setExplanation(explanation)
            // A line kept already keeps its explanation too.
            if (explanation.done) uiState = uiState.copy(savedLines = savedLinesStore.explain(line, song.title, song.artist, explanation.text))
        }
    }

    private var questionJob: Job? = null

    /** Asks ChatGPT [question] about line [index], after its explanation and what was asked before. */
    fun askAboutLine(index: Int, question: String) {
        val tutor = tutor ?: return
        val song = uiState.lyricsFor ?: return
        val asked = question.trim().takeIf { it.isNotEmpty() } ?: return
        val learning = uiState.learning
        // Questions about another line start over.
        val before = if (learning.questionsAbout == index) learning.questions.filter { it.done } else emptyList()
        val explanation = learning.explanation?.takeIf { it.index == index && it.done }?.text
        questionJob?.cancel()
        setQuestions(index, before + LineQuestion(asked))
        questionJob = viewModelScope.launch {
            val reply = tutor.askAboutLine(song, wordLines(), index, explanation, before.map { it.question to it.answer }, asked, translateInto()) { text ->
                viewModelScope.launch {
                    if (uiState.learning.questions.lastOrNull()?.writing == true) setQuestions(index, before + LineQuestion(asked, text))
                }
            }
            setQuestions(index, before + answered(asked, reply))
        }
    }

    private fun setQuestions(index: Int, questions: List<LineQuestion>) {
        uiState = uiState.copy(learning = uiState.learning.copy(questionsAbout = index, questions = questions))
    }

    private var songExplanationJob: Job? = null

    /** Has ChatGPT explain what the whole song is about. */
    fun explainSong() {
        val tutor = tutor ?: return
        val song = uiState.lyricsFor ?: return
        val lines = wordLines().takeIf { it.isNotEmpty() } ?: return
        findSongFacts(song)
        // Already explained, or on its way, it's just shown.
        if (uiState.learning.songExplanation?.let { it.done || it.writing } == true) return
        songExplanationJob?.cancel()
        setSongExplanation(LineExplanation(SONG))
        songExplanationJob = viewModelScope.launch {
            val reply = tutor.explainSong(song, lines, translateInto()) { text ->
                viewModelScope.launch { if (uiState.learning.songExplanation?.writing == true) setSongExplanation(LineExplanation(SONG, text)) }
            }
            setSongExplanation(explained(SONG, reply))
        }
    }

    private var songFactsJob: Job? = null

    /** Finds what the song covers or samples, unless it's known. */
    private fun findSongFacts(song: MusicMetadata) {
        val tutor = tutor ?: return
        if (uiState.learning.songFacts != null || songFactsJob?.isActive == true) return
        songFactsJob = viewModelScope.launch {
            val facts = tutor.songFacts(song) ?: return@launch
            if (uiState.lyricsFor?.let { sameSong(it, song) } == true) uiState = uiState.copy(learning = uiState.learning.copy(songFacts = facts))
        }
    }

    private var songQuestionJob: Job? = null

    /** Asks ChatGPT [question] about the whole song, after what it said about it and what was asked before. */
    fun askAboutSong(question: String) {
        val tutor = tutor ?: return
        val song = uiState.lyricsFor ?: return
        val asked = question.trim().takeIf { it.isNotEmpty() } ?: return
        val before = uiState.learning.songQuestions.filter { it.done }
        val explanation = uiState.learning.songExplanation?.takeIf { it.done }?.text
        songQuestionJob?.cancel()
        setSongQuestions(before + LineQuestion(asked))
        songQuestionJob = viewModelScope.launch {
            val reply = tutor.askAboutSong(song, wordLines(), explanation, before.map { it.question to it.answer }, asked, translateInto()) { text ->
                viewModelScope.launch { if (uiState.learning.songQuestions.lastOrNull()?.writing == true) setSongQuestions(before + LineQuestion(asked, text)) }
            }
            setSongQuestions(before + answered(asked, reply))
        }
    }

    private fun setSongQuestions(questions: List<LineQuestion>) {
        uiState = uiState.copy(learning = uiState.learning.copy(songQuestions = questions))
    }

    private var wordsToKnowJob: Job? = null

    /** Has ChatGPT pick the song's words most worth learning, unless it has or is. */
    fun findWordsToKnow() {
        val tutor = tutor ?: return
        val song = uiState.lyricsFor ?: return
        val lines = wordLines().takeIf { it.isNotEmpty() } ?: return
        if (uiState.learning.wordsToKnow?.failed == false) return
        setWordsToKnow(Asking())
        wordsToKnowJob = viewModelScope.launch {
            val words = tutor.wordsToKnow(song, lines, translateInto())
            setWordsToKnow(Asking(words, failed = words == null))
        }
    }

    private fun setWordsToKnow(asking: Asking<List<KeyWord>>) {
        uiState = uiState.copy(learning = uiState.learning.copy(wordsToKnow = asking))
    }

    /** Keeps [word], one of the song's words to know, to practise with the saved lines, or lets it go. */
    fun toggleSavedWord(word: KeyWord) {
        val song = uiState.lyricsFor ?: return
        // With the line it's first sung in, to practise it there, played.
        val index = wordLines().indexOfFirst { it.contains(word.word, ignoreCase = true) }
        val kept = SavedLine(word.word, word.reading, translation = word.meaning, title = song.title, artist = song.artist, line = wordLines().getOrNull(index))
        uiState = uiState.copy(savedLines = savedLinesStore.toggle(if (index >= 0) kept.timedAt(index) else kept))
    }

    private var artistJob: Job? = null

    /** Has ChatGPT say who the song's artist is, unless it has or is. */
    fun aboutArtist() {
        val tutor = tutor ?: return
        val song = uiState.lyricsFor ?: return
        if (uiState.learning.artist?.let { it.done || it.writing } == true) return
        setArtist(LineExplanation(SONG))
        artistJob = viewModelScope.launch {
            val reply = tutor.aboutArtist(song, translateInto()) { text ->
                viewModelScope.launch { if (uiState.learning.artist?.writing == true) setArtist(LineExplanation(SONG, text)) }
            }
            setArtist(explained(SONG, reply))
        }
    }

    private fun setArtist(explanation: LineExplanation) {
        uiState = uiState.copy(learning = uiState.learning.copy(artist = explanation))
    }

    private var examplesJob: Job? = null

    /** Has ChatGPT write sentences using the word tapped in line [index], the way the line does. */
    fun findExamples(index: Int) {
        val tutor = tutor ?: return
        val song = uiState.lyricsFor ?: return
        val word = uiState.word?.word ?: return
        val line = wordLines().getOrNull(index) ?: return
        examplesJob?.cancel()
        setExamples(WordExamples(word, index))
        examplesJob = viewModelScope.launch {
            val examples = tutor.examples(word.lookup, line, song, translateInto())
            setExamples(WordExamples(word, index, Asking(examples, failed = examples == null)))
        }
    }

    private fun setExamples(examples: WordExamples?) {
        uiState = uiState.copy(learning = uiState.learning.copy(examples = examples))
    }

    /** A question [asked], answered as [reply] says. */
    private fun answered(asked: String, reply: ChatGptReply) = when (reply) {
        is ChatGptReply.Answer -> LineQuestion(asked, reply.text.trim(), done = true)
        ChatGptReply.LimitReached -> LineQuestion(asked, limitReached = true)
        ChatGptReply.Failed -> LineQuestion(asked, failed = true)
    }

    /** Line [index], or the song, explained as [reply] says. */
    private fun explained(index: Int, reply: ChatGptReply) = when (reply) {
        is ChatGptReply.Answer -> LineExplanation(index, reply.text.trim(), done = true)
        ChatGptReply.LimitReached -> LineExplanation(index, limitReached = true)
        ChatGptReply.Failed -> LineExplanation(index, failed = true)
    }

    private fun setSongExplanation(explanation: LineExplanation) {
        uiState = uiState.copy(learning = uiState.learning.copy(songExplanation = explanation))
    }

    private fun setExplanation(explanation: LineExplanation) {
        uiState = uiState.copy(learning = uiState.learning.copy(explanation = explanation))
    }

    private var repeatJob: Job? = null

    /**
     * Has the music app play line [index] over and over: back to its start each time it reaches the
     * next line, until stopped. Only while the words follow an app that can be moved.
     */
    fun repeatLine(index: Int) {
        val song = uiState.lyricsFor ?: return
        val lines = uiState.lyricLines
        val start = lines.getOrNull(index)?.timeMs ?: return
        if (uiState.following?.let { it.canSeek && it.timingKnown } != true) return
        val end = endOf(lines, index) + LINE_TAIL_MS
        repeatJob?.cancel()
        uiState = uiState.copy(repeating = index)
        playback?.seekTo(song, inApp(start))
        var sought = now()
        repeatJob = viewModelScope.launch {
            while (isActive) {
                delay(REPEAT_CHECK_MS)
                val position = uiState.following?.clock?.positionAt(now()) ?: continue
                // The app takes a moment to say where it went, so it isn't sent back again meanwhile.
                if (position >= end && now() - sought >= SEEK_SETTLE_MS) {
                    playback?.seekTo(song, inApp(start))
                    sought = now()
                }
            }
        }
    }

    fun stopRepeating() {
        repeatJob?.cancel()
        repeatJob = null
        uiState = uiState.copy(repeating = null)
    }

    /** The words shown, line by line: the timed ones, or else the plain ones. */
    private fun wordLines(): List<String> = uiState.lyricLines.map { it.text }.ifEmpty { uiState.lyrics.lines() }

    private var uniDicJob: Job? = null

    /**
     * Japanese is read with UniDic too, once it's on the phone. Until then it's fetched, whatever
     * song shows, and the words shown are read again with it once it's here.
     */
    private suspend fun readWithUniDic() {
        val dictionary = uniDic ?: return
        if (Readings.uniDic != null || uniDicJob?.isActive == true) return
        Readings.uniDic = dictionary.tokenizer(fetch = false)
        if (Readings.uniDic != null) return
        uniDicJob = viewModelScope.launch {
            Readings.uniDic = dictionary.tokenizer(fetch = true) ?: return@launch
            if (uiState.learning.script == Script.JAPANESE && uiState.learning.lines.isNotEmpty()) {
                readingsJob?.cancel()
                uiState = uiState.copy(learning = uiState.learning.copy(lines = emptyList()))
                learn()
            }
        }
    }

    /** Works out what's switched on for the words shown, and isn't worked out yet. */
    private fun learn() {
        val learning = uiState.learning
        val lines = wordLines()
        val script = learning.script
        if ((learning.readings || learning.romanized || learning.wordsAs != WordsAs.WRITTEN) && script != null && learning.lines.isEmpty() && readingsJob?.isActive != true) {
            readingsJob = viewModelScope.launch {
                if (script == Script.JAPANESE) readWithUniDic()
                // Japanese loads its dictionary the first time, a moment better spent away from the screen.
                val song = uiState.lyricsFor
                var read = withContext(systemDispatcher) { Readings.of(lines, script) }
                // The song's name and its artist's read the same way, to show how to say them.
                val names = song?.let { withContext(systemDispatcher) { Readings.of(listOf(it.title, it.artist), script) } }.orEmpty()
                uiState = uiState.copy(learning = uiState.learning.copy(lines = read, names = names))
                // Lines kept with no reading, as ones once written otherwise, are read as they are now.
                song?.let { rereadSavedLines(it, unread = true) }
                if (script != Script.JAPANESE || song == null) return@launch
                // Sung readings, 都会 as まち, from a site that writes them over the words.
                sungReadings?.of(song, lines, read)?.takeIf { it.isNotEmpty() }?.let { fixes ->
                    if (wordLines() != lines || uiState.learning.lines != read) return@launch
                    read = Readings.corrected(read, fixes)
                    uiState = uiState.copy(learning = uiState.learning.copy(lines = read))
                    rereadSavedLines(song)
                }
                // Signed in, ChatGPT puts right the readings the dictionary gets wrong in this song.
                if (usesChatGpt()) {
                    val checked = tutor?.checkReadings(song, lines, read) ?: return@launch
                    if (wordLines() == lines && uiState.learning.lines == read) {
                        uiState = uiState.copy(learning = uiState.learning.copy(lines = checked))
                        rereadSavedLines(song)
                    }
                }
            }
        }
        val translator = translator ?: return
        if (learning.translation && uiState.lyrics.isNotEmpty() && learning.translations.isEmpty() && !learning.translating) {
            translationJob = viewModelScope.launch {
                uiState = uiState.copy(learning = uiState.learning.copy(translating = true, translationFailed = false))
                val language = translateInto()
                // A song in a language the user reads needs none, line by line, and nor does one already in
                // the language it'd be translated into, in whatever variety: a Spanish song into Argentina's.
                val sung = lyricsLanguage?.of(lines)
                if (sung != null && (sung in learning.untranslated || sung == AppLanguage.untranslatedTag(language))) {
                    uiState = uiState.copy(learning = uiState.learning.copy(translations = lines.map { null }, translating = false))
                    return@launch
                }
                // ChatGPT, signed in, translates the song as a whole; the usual way when it can't.
                val translated = (
                    if (usesChatGpt() && learning.chatGptTranslation) {
                        tutor?.translate(lines, language, uiState.lyricsFor) { so ->
                            // Shown line by line as they come, while it's still translating these words.
                            viewModelScope.launch {
                                if (uiState.learning.translating && wordLines() == lines) uiState = uiState.copy(learning = uiState.learning.copy(translations = so))
                            }
                        }
                    } else {
                        null
                    }
                    ) ?: translator.translate(lines, language)
                uiState = uiState.copy(
                    learning = uiState.learning.copy(
                        translations = translated.orEmpty(), translating = false, translationFailed = translated == null
                    )
                )
                uiState.lyricsFor?.let(::retranslateSavedLines)
                // The song's name too, to know what it means, the same way.
                val song = uiState.lyricsFor ?: return@launch
                val title = listOf(song.title)
                val meaning = ((if (usesChatGpt() && learning.chatGptTranslation) tutor?.translate(title, language, song) else null) ?: translator.translate(title, language))
                    ?.firstOrNull()?.takeUnless { it.equals(song.title.trim(), ignoreCase = true) }
                if (uiState.lyricsFor == song) uiState = uiState.copy(learning = uiState.learning.copy(titleMeaning = meaning))
            }
        }
    }

    private var chordsJob: Job? = null

    /** Looks up the song's chords, while they're switched on and haven't been. */
    private fun chords() {
        val finder = chordFinder ?: return
        val song = uiState.lyricsFor ?: return
        val chords = uiState.chords
        val lines = wordLines()
        if (!chords.on || uiState.lyrics.isEmpty() || chords.loading || chords.tried) return
        chordsJob = viewModelScope.launch {
            uiState = uiState.copy(chords = uiState.chords.copy(loading = true))
            val answer = finder.chordsOf(song, lines)
            uiState = uiState.copy(chords = uiState.chords.copy(loading = false, tried = true, sheet = (answer as? ChordFinder.Chords.Found)?.sheet))
            if (answer == ChordFinder.Chords.None) problemFound(SongProblem.NO_CHORDS)
        }
    }

    /**
     * Shows the song's chords over its words, from now on, or stops, and stops looking them up.
     * Switched on again, a song with none found is looked up again.
     */
    fun setChords(on: Boolean) {
        preferences.edit { putBoolean(KEY_CHORDS, on) }
        if (!on) chordsJob?.cancel()
        val chords = uiState.chords
        uiState = uiState.copy(chords = chords.copy(on = on, loading = on && chords.loading, tried = chords.tried && chords.sheet != null))
        chords()
    }

    fun setReadings(on: Boolean) {
        preferences.edit { putBoolean(KEY_READINGS, on) }
        uiState = uiState.copy(learning = uiState.learning.copy(readings = on))
        learn()
    }

    /** Kept only once let go, [save], rather than at every step of the slider. */
    fun setReadingScale(scale: Float, save: Boolean) {
        if (save) preferences.edit { putFloat(KEY_READING_SCALE, scale) }
        uiState = uiState.copy(learning = uiState.learning.copy(readingScale = scale))
    }

    /** Kept only once let go, [save], rather than at every step of the slider. */
    fun setWordsScale(scale: Float, save: Boolean) {
        if (save) preferences.edit { putFloat(KEY_WORDS_SCALE, scale) }
        uiState = uiState.copy(learning = uiState.learning.copy(wordsScale = scale))
    }

    fun setWordsCentered(centered: Boolean) {
        preferences.edit { putBoolean(KEY_WORDS_CENTERED, centered) }
        uiState = uiState.copy(learning = uiState.learning.copy(centered = centered))
    }

    /** Kept only once let go, [save], rather than at every step of the slider. */
    fun setTranslationScale(scale: Float, save: Boolean) {
        if (save) preferences.edit { putFloat(KEY_TRANSLATION_SCALE, scale) }
        uiState = uiState.copy(learning = uiState.learning.copy(translationScale = scale))
    }

    fun setRomanized(on: Boolean) {
        preferences.edit { putBoolean(KEY_ROMANIZED, on) }
        uiState = uiState.copy(learning = uiState.learning.copy(romanized = on))
        learn()
    }

    fun setTranslation(on: Boolean) {
        preferences.edit { putBoolean(KEY_TRANSLATION, on) }
        uiState = uiState.copy(learning = uiState.learning.copy(translation = on))
        learn()
    }

    /** Spells romaji as typed, sensei, or as said, sensee, from now on. */
    fun setRomajiTyped(on: Boolean) {
        preferences.edit { putBoolean(KEY_ROMAJI_TYPED, on) }
        uiState = uiState.copy(learning = uiState.learning.copy(romajiTyped = on))
    }

    /** The kinds of practice card, from now on; one is always on. */
    fun setCardTypes(types: List<CardType>, save: Boolean) {
        val kinds = if (types.none { it.on }) types.mapIndexed { index, type -> type.copy(on = type.on || index == 0) } else types
        if (save) saveCardTypes(preferences, kinds)
        uiState = uiState.copy(cardTypes = kinds)
    }

    /** Shows the words as written, or only in kana or Latin letters, from now on. */
    fun setWordsAs(wordsAs: WordsAs) {
        preferences.edit { putString(KEY_WORDS_AS, wordsAs.name) }
        uiState = uiState.copy(learning = uiState.learning.copy(wordsAs = wordsAs))
        learn()
    }

    /** Asks for the translation again, after it couldn't be done. */
    fun retryTranslation() {
        uiState = uiState.copy(learning = uiState.learning.copy(translationFailed = false))
        learn()
    }

    /** Translates with ChatGPT, signed in, or with MyMemory, from now on. */
    fun setChatGptTranslation(on: Boolean) {
        preferences.edit { putBoolean(KEY_TRANSLATION_CHATGPT, on) }
        // What the other said is no answer from this one.
        translateAgain(uiState.learning.copy(chatGptTranslation = on))
    }

    /** The words shown translated again, as [learning] says to now, rather than as they were. */
    private fun translateAgain(learning: Learning) {
        translationJob?.cancel()
        uiState = uiState.copy(learning = learning.copy(translations = emptyList(), translating = false, translationFailed = false))
        learn()
    }

    /** The language lyrics and words are translated into: the one picked, or else the app's. */
    private fun translateInto(): String =
        uiState.learning.into?.let { Translator.languageOf(java.util.Locale.forLanguageTag(it)) } ?: translationLanguage()

    /** Leaves songs in [languages], of [AppLanguage.untranslatedTags], untranslated from now on. */
    fun setUntranslated(languages: Set<String>) {
        val untranslated = languages.filter { it in AppLanguage.untranslatedTags }.toSet()
        preferences.edit { putStringSet(KEY_UNTRANSLATED, untranslated) }
        // The song shown may be in one of them now, or not any more.
        translateAgain(uiState.learning.copy(untranslated = untranslated))
    }

    /** Translates into [tag], one of [AppLanguage.translationTags], from now on, or into the app's own with null. */
    fun setTranslateInto(tag: String?) {
        val into = tag?.takeIf { it in AppLanguage.translationTags }
        preferences.edit { if (into == null) remove(KEY_TRANSLATE_INTO) else putString(KEY_TRANSLATE_INTO, into) }
        // Lines translated into the last language are no use in this one.
        uiState = uiState.copy(word = null)
        translateAgain(uiState.learning.copy(into = into))
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
        val match = if (uiState.exactMatch && service != null) {
            uiState = uiState.copy(isMatching = true)
            matcher.lookFor(service, metadata).also { uiState = uiState.copy(isMatching = false) }
        } else {
            null
        }
        val exactUrl = (match as? ExactMatcher.Match.Found)?.url
        val url = exactUrl?.let(destination::adapt) ?: destination.searchUrl(searchQuery(metadata))
        rememberDestination(destination, PreparedLink(url, exactUrl != null, matched(match)))
        return url.forSharing()
    }

    /**
     * Whether a link was prepared with exact matching: not when the lookup got no answer, e.g.
     * offline, so it isn't taken as not being there, and is looked for again next time.
     */
    private fun matched(match: ExactMatcher.Match?) = uiState.exactMatch && match != ExactMatcher.Match.Failed

    /** Opens the link in the app it came from, e.g. when it can't be resolved or the user prefers it. */
    fun openOriginal() {
        val link = uiState.link ?: return
        // An exact match may still be running; it must not open a second app when it finishes.
        job?.cancel()
        cancelTrackWork()
        pendingOpen = false
        uiState = uiState.copy(showDestinationPicker = false, isMatching = false)
        val finishAfterOpen = lastRequest?.openWhenReady == true
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
            linkText = fieldText(entry.link),
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

    /** Shows [entry]'s card; [play], it plays from there, as a link shown here first does. */
    fun showHistoryEntry(entry: HistoryEntry, play: Boolean = false) {
        selectHistoryEntry(entry)
        pendingPlay = play
        lookAhead()
        loadTrackCovers()
        prepareResultDestination()
    }

    /**
     * Recent's ▶ goes where a tapped link does: to the song's card here first, playing from there
     * when it plays right away, or else straight to the user's app, from what was saved.
     */
    fun openHistoryEntry(entry: HistoryEntry) =
        if (uiState.linksInCrosstune) showHistoryEntry(entry, play = uiState.playsRightAway) else openSaved(entry, finishAfterOpen = false)

    /**
     * A Recent song from the widget's ▶: as Recent's own ▶, with Crosstune going away after when it
     * goes straight to the user's app. Gone from Recent meanwhile, it's looked up again.
     */
    fun openRecent(url: String?) {
        val entry = uiState.history.firstOrNull { it.link.url == url } ?: return resolveIncoming(url)
        uiState = uiState.copy(leaveSettings = true)
        if (uiState.linksInCrosstune) return openHistoryEntry(entry)
        uiState = uiState.copy(handlingIncomingLink = true, handingOff = true)
        openSaved(entry, finishAfterOpen = true)
    }

    private fun openSaved(entry: HistoryEntry, finishAfterOpen: Boolean) {
        cancelContentWork()
        job = viewModelScope.launch {
            val destination = destinationFor(entry)
            val url = urlForHistory(entry, destination) ?: return@launch
            val prepared = uiState.history.firstOrNull { it.link.url == entry.link.url }?.destinationLinks?.get(destination.key)
            val service = destination.matchService
            if (prepared != null && prepared.url.forSharing() == url && !prepared.exact && prepared.matchingEnabled && service != null &&
                canBeMissing(service, entry.metadata)
            ) {
                return@launch openMissing(entry.metadata, entry.link, destination, url, finishAfterOpen)
            }
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
            matcher.youtubeQueue(service, entry.metadata.tracks.take(MAX_QUEUE)) {}?.let { queue ->
                playOn(entry.metadata.tracks, service, destination, queue, entry.metadata.title)
                return queue.url
            }
        }
        entry.destinationLinks[destination.key]?.takeIf { it.matchingEnabled == uiState.exactMatch }?.let { return it.url.forSharing() }
        val match = if (uiState.exactMatch && service != null) matcher.lookFor(service, entry.metadata) else null
        val exactUrl = (match as? ExactMatcher.Match.Found)?.url
        val url = exactUrl?.let(destination::adapt) ?: destination.searchUrl(searchQuery(entry.metadata))
        val history = historyStore.remember(entry.link.url, destination.key, PreparedLink(url, exactUrl != null, matched(match)))
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
        // Not there, the song is shared where it is, rather than as a search that won't find it, unless
        // searching anyway was picked.
        uiState.link?.takeIf { uiState.resultMissing && uiState.notFoundAction != NotFoundAction.SEARCH && it.service != null }?.let { return it.url }
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
    private fun cancelContentWork(closesPlayer: Boolean = true) {
        job?.cancel()
        job = null
        lastRequest = null
        pendingOpen = false
        pendingPlay = false
        retryListens = false
        dismissSongSearch()
        dismissLyrics()
        if (closesPlayer) closePlayer()
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
        adoptDefault(destination)
        uiState = uiState.copy(selectedDestination = destination)
        prepareResultDestination()
    }

    fun selectDefault(destination: Destination) {
        destinationStore.setDefault(destination)
        // Opening links in the app, Crosstune would only hand it its own links back, unless a rule sends them elsewhere.
        (destination as? Destination.Service)?.service
            ?.takeIf { !uiState.linksInCrosstune && interception.isEnabled(it) && destinationStore.rule(it) == null }
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
     * Opens the big services' links on a fresh install, but the one the user listens in, whose
     * links already open there. Each site is a box to tick in Android's settings, so the rest wait
     * in settings: YouTube's links are mostly videos, SoundCloud's, Bandcamp's and Audiomack's songs
     * are rarely anywhere else, and Invidious and Piped are a dozen sites of their own.
     */
    private fun preselectSources() {
        // A TV has nowhere to allow links in, and links rarely reach one anyway.
        if (preferences.getBoolean(KEY_SOURCES_PRESELECTED, false) || uiState.phone == Phone.TV) return
        preferences.edit { putBoolean(KEY_SOURCES_PRESELECTED, true) }
        if (uiState.interceptsAnything) return
        val listening = uiState.listeningService()
        STARTING_SOURCES.filter { it != listening }.forEach { interception.setEnabled(it, true) }
        uiState = uiState.withDestinations().copy(showLinkSettingsHelper = true)
        loadSystemState()
    }

    fun setFrontendRule(frontend: Frontend, destination: Destination?) {
        destinationStore.setRule(frontend, destination)
        refreshSystemState()
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

    /** Whether tapped and shared links show in Crosstune first, or go straight to the music app. */
    fun setShowInCrosstuneFirst(enabled: Boolean) {
        lookUpAsNow(uiState.copy(linksInCrosstune = enabled))
        preferences.edit { putBoolean(KEY_LINKS_IN_CROSSTUNE, enabled) }
        // Straight to the app, Crosstune would only hand it its own links back; shown here first, they come here too.
        val app = (uiState.defaultDestination as? Destination.Service)?.service
        if (!enabled) selectDefault(uiState.defaultDestination)
        else if (app?.opensTappedLinks == true) setIntercepted(app, true)
    }

    fun setPlayRightAway(enabled: Boolean) {
        lookUpAsNow(uiState.copy(playRightAway = enabled))
        preferences.edit { putBoolean(KEY_PLAY_RIGHT_AWAY, enabled) }
    }

    /** Takes [state], and prepares the song shown again when that changes whether it opens itself or a search. */
    private fun lookUpAsNow(state: UiState) {
        val exact = uiState.exactMatch
        uiState = state
        if (state.exactMatch != exact) {
            uiState = uiState.copy(destinationUrls = emptyMap())
            prepareResultDestination()
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

    /** Downloads the newer Crosstune, which Android then offers to install over this one. */
    fun openUpdate() {
        val update = uiState.update ?: return
        viewModelScope.launch { effectChannel.send(Effect.Open(update.apkUrl, packageName = null, finishAfterOpen = false)) }
    }

    /** Closes the main screen's notices about [keys]; one about anything else still shows. */
    fun dismissNotices(keys: Set<String>) {
        val dismissed = uiState.dismissedNotices + keys
        uiState = uiState.copy(dismissedNotices = dismissed)
        preferences.edit { putStringSet(KEY_DISMISSED_NOTICES, dismissed) }
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
        private const val KEY_DISMISSED_NOTICES = "dismissed_notices"
        private const val KEY_ASK_EACH_TIME = "ask_each_time"
        private const val KEY_EXACT_MATCH = "exact_match"
        private const val KEY_CLEAN_LINKS = "clean_links"
        private const val KEY_SHARE_SHEET_APPS = "share_sheet_apps"
        private const val KEY_SHOW_SONG_FIRST = "show_song_first"
        private const val KEY_LINKS_IN_CROSSTUNE = "links_in_crosstune"
        private const val KEY_PLAY_RIGHT_AWAY = "play_right_away"
        private const val KEY_THEME = "theme"
        const val KEY_PALETTE = "palette"
        private const val KEY_NOT_FOUND = "not_found"
        private const val KEY_PURE_BLACK = "pure_black"
        private const val KEY_PLAYING_BAR = "playing_bar"
        private const val KEY_LINES_EARLY = "lines_early_ms"
        private const val KEY_NEXT_LINE_FIRST = "next_line_first"
        private const val KEY_VISUALS = "visuals"
        private const val KEY_VISUALS_SHADE = "visuals_shade"
        private const val KEY_READINGS = "lyrics_readings"
        private const val KEY_CHORDS = "lyrics_chords"
        private const val KEY_ROMANIZED = "lyrics_romanized"
        private const val KEY_ROMAJI_TYPED = "lyrics_romaji_typed"
        private const val KEY_TRANSLATION = "lyrics_translation"
        private const val KEY_TRANSLATION_CHATGPT = "translation_chatgpt"
        private const val KEY_WORDS_AS = "lyrics_words_as"
        private const val KEY_CHATGPT_MODEL = "chatgpt_model"

        /** Typed text of this many words or more may describe a song rather than name it. */
        const val DESCRIPTION_WORDS = 3

        /** The fewest songs ChatGPT names for a description that make a playlist. */
        const val MIN_PLAYLIST = 3

        /** Where a song's explanation, which is no line's, says it's from. */
        const val SONG = -1
        private const val KEY_TRANSLATE_INTO = "translate_into"

        /** How many songs' words are kept to show again at once. */
        const val KEPT_SONGS = 20
        private const val KEY_UNTRANSLATED = "untranslated_languages"
        private const val KEY_READING_SCALE = "reading_scale"
        private const val KEY_WORDS_SCALE = "words_scale"
        private const val KEY_WORDS_CENTERED = "words_centered"
        private const val KEY_TRANSLATION_SCALE = "translation_scale"
        private const val KEY_CHATGPT_WELCOMED = "chatgpt_welcomed"
        private const val KEY_FLOATING_BACKGROUND = "floating_background"
        private const val KEY_FLOATING_SCALE = "floating_scale"
        private const val KEY_FLOATING_PREVIOUS_LINE = "floating_previous_line"
        private const val KEY_FLOATING_LEFT = "floating_left"
        private const val KEY_FLOATING_VISUALS = "floating_visuals"
        private const val KEY_FLOATING_NEXT_LINE = "floating_next_line"
        private const val KEY_FLOATING_LOCKED = "floating_locked"
        private const val KEY_FLOATING_TOP = "floating_top"

        /** How far apart two hearings of a song can be and still be the same place in it. */
        const val STEADY_MS = 2_000L

        /** How close a song heard by another name must be to where the one shown is to be the same recording. */
        const val SAME_RECORDING_MS = 10_000L

        /** How far into the song shown that's believed: earlier, it may just as well be the next song. */
        const val SAME_RECORDING_AFTER_MS = 30_000L
        /** How long ago the song can have been heard and still line up a video with it. */
        const val HEARD_LATELY_MS = 60_000L
        /** How far Shazam's place in a song may be from where its words are timed. */
        const val MAX_GAP_MS = 60_000L

        /** Times the singing is heard for a song, at most, while too few words heard match its words to tell. */
        const val SINGING_TRIES = 3
        /** Lines sung in what's heard, at least, so enough words to match are heard. */
        const val SINGING_LINES = 3
        /** The last moment of what's heard, where a line starting hasn't been sung yet. */
        const val SINGING_TAIL_MS = 3_000L
        /** How long before a line's timed to start it's heard from, as the words may be timed a little late. */
        const val SINGING_LEAD_MS = 1_500L
        /** Words heard sung less off than this stay as they are: hearing them is about as exact. */
        const val SINGING_NUDGE_MS = 150L
        /** An app saying it plays from no further than this into the song, as it says it, starts it: where it says is sure. */
        const val SURE_START_MS = 1_500L
        /** An app's clock as far as this from where it was playing on to has jumped. */
        const val SAME_CLOCK_MS = 250L

        /** How far off the singing heard has to be from the words for them to be reported as off. */
        const val SUNG_OFF_MS = 1_000L
        /** Where the song is may drift this much while it's heard, and still be where it was. */
        const val STEADY_CLOCK_MS = 300L

        /** Long enough not to keep Shazam busy, short enough to catch a skip or the next song soon. */
        const val LISTEN_ALONG_PAUSE_MS = 8_000L

        /** How many wrong meanings a practised line's right one is picked among. */

        /** How many times the rest after hearing nothing doubles: 8 s, then 16, 32 and at most 64. */
        const val QUIET_PAUSE_DOUBLINGS = 3

        /** How long the last line repeats for, with no next line to end at. */
        const val LAST_LINE_MS = 6_000L
        /**
         * How long a line plays on past where the words say it ends. Words are timed to show a little
         * before they're sung, half a second in songs heard, so the next line's time is about when
         * this one's last sound starts fading, not when it's gone; cut there, its last word is lost.
         */
        const val LINE_TAIL_MS = 1_000L
        private const val REPEAT_CHECK_MS = 100L
        private const val SEEK_SETTLE_MS = 1_000L
        /** How long the music app has to find a song asked for by name, and start it. */
        const val LOAD_SONG_MS = 12_000L
        /** YouTube makes temporary playlists of up to 50 videos. */
        const val MAX_QUEUE = 50
        /** Songs whose covers are looked up when a playlist shows: Spotify lists 100, the first 30 with covers. */
        private const val MAX_COVER_LOOKUPS = 100
        private const val KEY_ONLY_MUSIC_VIDEOS = "only_music_videos"
        private const val KEY_SOURCES_PRESELECTED = "sources_preselected"

        /** The sources whose links open in Crosstune from the start. */
        private val STARTING_SOURCES = listOf(MusicService.SPOTIFY, MusicService.YOUTUBE_MUSIC, MusicService.APPLE_MUSIC, MusicService.DEEZER, MusicService.TIDAL)

        /** How many times, and how long apart, setup in one tap looks for lyrics being let follow music apps. */
        private const val FOLLOW_WAITS = 10
        private const val FOLLOW_WAIT_MS = 100L
        private const val KEY_SETUP_COMPLETE = "setup_complete"

        /** Preferences only an install from before first-run setup can have. */
        private val LEGACY_KEYS = listOf(
            KEY_LINK_SETTINGS_HELPER_DISMISSED, "default_target", "history", KEY_ASK_EACH_TIME, KEY_EXACT_MATCH
        )
    }
}
