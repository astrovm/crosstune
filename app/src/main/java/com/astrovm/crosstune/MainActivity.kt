package com.astrovm.crosstune

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.util.Rational
import android.app.UiModeManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutManager
import android.Manifest
import androidx.glance.appwidget.updateAll
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.annotation.VisibleForTesting
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File

class MainActivity : ComponentActivity() {

    /** Apps that name a song playing nearby, looked up again whenever the app comes back. */
    private var recognizers by mutableStateOf(emptyList<SongRecognizer>())

    /** The app picked in Settings for the Recognize button; Crosstune when none is. */
    private var recognizerPick by mutableStateOf<String?>(null)

    private val viewModel: MainViewModel by viewModels {
        viewModelFactory {
            initializer {
                val client = httpClientFactory()
                MainViewModel(
                    LinkResolver(client),
                    // What was found for songs is kept, so a playlist played again needs no lookups.
                    ExactMatcher(client, cache = LookupCache(File(cacheDir, "lookups.json"), lookupDispatcher)),
                    LyricsFinder(client, packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()),
                    SongSearcher(client),
                    playbackFactory(applicationContext),
                    getSharedPreferences(MainViewModel.PREFERENCES_NAME, MODE_PRIVATE),
                    LinkInterception(applicationContext),
                    ArtworkLoader(client, cacheDir = File(cacheDir, "artwork")),
                    hearingFactory?.invoke() ?: SongListener(microphoneFactory(), Shazam(client)),
                    systemDispatcher,
                    listenAlongPauseMs,
                    // Each line translated is kept, so a song read again needs no translating.
                    translator = Translator(client, LookupCache(File(cacheDir, "translations.json"), lookupDispatcher)),
                    translationLanguage = { Translator.languageOf(resources.configuration.locales[0]) }
                )
            }
        }
    }

    /** Set while the words float over other apps, in picture-in-picture. */
    private var floating by mutableStateOf(false)

    /** Set while the words float over other apps in a window of their own, which keeps listening along. */
    private var floatingOverApps = false

    /** What the words floating in their own window show and do, all from here. */
    private val floatingHost = object : FloatingHost {
        override val state get() = viewModel.uiState
        override val dark get() = when (viewModel.uiState.theme) {
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
            ThemeMode.SYSTEM -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        override val artwork get() = viewModel.artwork
        override fun toggleListening() = when {
            viewModel.uiState.listeningAlong -> viewModel.stopListeningAlong()
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> viewModel.listenAlong()
            // The microphone is asked for here, in sight.
            else -> startActivity(Intent(this@MainActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        override fun update(options: FloatingOptions, save: Boolean) = viewModel.setFloating(options, save)
    }

    /** Set while saying why Android's about to be asked to let the words float over other apps. */
    private var askingToFloat by mutableStateOf(false)

    /** Set while away in Android's settings to allow it, so the words float once back, if it was. */
    private var floatOnceAllowed = false

    /**
     * Words over other apps that are locked are unlocked from their notification, which Android 13
     * asks about first; asked or not, they float after.
     */
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { floatNow() }

    /** What asked for the microphone: listening for a song, or listening along with the words. */
    private var afterMicrophone: () -> Unit = { viewModel.listen() }

    /** Asked on the first listen; without it, the error offers Android's settings for Crosstune. */
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) afterMicrophone() else viewModel.showError(AppError.MICROPHONE)
    }

    /** Set when launched to read the clipboard, which Android only allows once the window has focus. */
    private var pendingClipboardRead = false

    companion object {
        const val ACTION_PASTE_FROM_CLIPBOARD = "com.astrovm.crosstune.action.PASTE_FROM_CLIPBOARD"

        /** The unexported alias the tile and launcher shortcut use, so only they can trigger a clipboard read. */
        const val PASTE_ALIAS = "com.astrovm.crosstune.PasteFromClipboard"
        /** The widget's ▶: opens a Recent song in the user's app, like Recent's own ▶. */
        const val ACTION_OPEN_RECENT = "com.astrovm.crosstune.action.OPEN_RECENT"
        /** Listens for a song playing nearby, from the widget. */
        const val ACTION_LISTEN = "com.astrovm.crosstune.action.LISTEN"
        /** The unexported alias the widget listens through, so no other app can make Crosstune listen. */
        const val LISTEN_ALIAS = "com.astrovm.crosstune.ListenForSong"
        /** On a listen, opens the words of the song as soon as it's named, and keeps listening along. */
        const val EXTRA_LYRICS = "com.astrovm.crosstune.extra.LYRICS"

        /** Listens for the song playing nearby and shows its words, as the widget's and tile's Lyrics do. */
        fun lyricsIntent(context: Context): Intent = Intent(ACTION_LISTEN).setClassName(context, LISTEN_ALIAS).putExtra(EXTRA_LYRICS, true)

        /** On a link, shows it here first, as the widget's songs do when tapped. */
        const val EXTRA_SHOW_SONG = "com.astrovm.crosstune.extra.SHOW_SONG"


        /** Set once Android 13 has asked about notifications for floating words, so it isn't asked again. */
        private const val KEY_ASKED_NOTIFICATIONS = "asked_floating_notifications"
        private const val STATE_PENDING_CLIPBOARD_READ = "pending_clipboard_read"
        private const val STATE_INCOMING_LINK = "incoming_link"

        /** The navigation bar's scrims, as Android's own edge-to-edge default draws them. */
        private val LIGHT_SCRIM = android.graphics.Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        private val DARK_SCRIM = android.graphics.Color.argb(0x80, 0x1b, 0x1b, 0x1b)

        @VisibleForTesting
        internal var httpClientFactory: () -> OkHttpClient = ::httpClient

        /** Where Android is asked about apps and links; tests run it in step with the screen. */
        @VisibleForTesting
        internal var systemDispatcher: CoroutineDispatcher = Dispatchers.Default

        /** Where what's found for songs is read and written; tests do it in step with the screen. */
        @VisibleForTesting
        internal var lookupDispatcher: CoroutineDispatcher = Dispatchers.IO

        /** What music apps are playing, which a song's timed words follow. */
        @VisibleForTesting
        internal var playbackFactory: (Context) -> PlaybackSource = ::MediaSessionPlayback

        /** How long listening along waits between songs heard; tests don't wait. */
        @VisibleForTesting
        internal var listenAlongPauseMs: Long = MainViewModel.LISTEN_ALONG_PAUSE_MS

        /** What hears songs nearby, when a test stands in for the microphone and Shazam both. */
        @VisibleForTesting
        internal var hearingFactory: (() -> SongHearing)? = null

        @VisibleForTesting
        internal var microphoneFactory: () -> Microphone = { AudioRecordMicrophone() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The floating window's microphone: listens along, or stops.
        lifecycleScope.launch {
            FloatingListenReceiver.taps.collect {
                if (viewModel.uiState.listeningAlong) viewModel.stopListeningAlong() else withMicrophone(viewModel::listenAlong)
            }
        }
        pendingClipboardRead = savedInstanceState?.getBoolean(STATE_PENDING_CLIPBOARD_READ) == true
        val incomingLink = savedInstanceState?.getString(STATE_INCOMING_LINK)
        when {
            savedInstanceState == null -> handleIntent(intent)
            // After a configuration change the ViewModel already holds this intent's result or request.
            // After Android ends the process in the background it doesn't, so look the link up again.
            incomingLink != null && !viewModel.uiState.handlingIncomingLink -> viewModel.resolveIncoming(incomingLink)
        }

        val shortcuts = AppShortcuts(applicationContext, viewModel.artwork::load)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Android limits how often a background app may change shortcuts, so only while shown.
                // Converted links saved on an entry don't change its shortcut, so they don't restart it.
                snapshotFlow {
                    val state = viewModel.uiState
                    // They wait for what Android says about apps, rather than drop out for a moment and come back.
                    if (!state.systemStateKnown) return@snapshotFlow null
                    val recent = state.history.take(AppShortcuts.MAX_RECENT).map { it.copy(destinationLinks = emptyMap()) }
                    Triple(recent, state.shareSheetEntries, state.shareSheetApp)
                }
                    .filterNotNull()
                    .distinctUntilChanged()
                    .collectLatest { (recent, entries, app) ->
                        shortcuts.update(recent, entries, app)
                        // The widget shows Recent too.
                        CrosstuneWidget().updateAll(applicationContext)
                    }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.effects.collect { effect ->
                    when (effect) {
                        is Effect.Open -> open(effect)
                        is Effect.Share -> {
                            startActivity(shareChooser(effect.url))
                            finish()
                        }
                    }
                }
            }
        }

        setContent {
            val theme = viewModel.uiState.theme
            val dark = if (theme == ThemeMode.DARK) true else if (theme == ThemeMode.LIGHT) false else isSystemInDarkTheme()
            // The bars' icons follow the app's look, which may not be the phone's.
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark }
                )
            }
            // Words on screen float over other apps when Crosstune is left, with the microphone as it is now.
            // Words that show what's below float in a window of their own instead; see onUserLeaveHint.
            val lyricsShown = viewModel.uiState.lyricsFor != null && !viewModel.uiState.canFloatOverApps
            val listening = viewModel.uiState.listeningAlong
            LaunchedEffect(lyricsShown, listening) { setPictureInPictureParams(floatingParams(lyricsShown, listening)) }
            CrosstuneTheme(darkTheme = dark, palette = viewModel.uiState.palette, pureBlack = viewModel.uiState.pureBlack) {
                if (floating) {
                    FloatingLyrics(viewModel.uiState, viewModel.artwork::load)
                    return@CrosstuneTheme
                }
                CrosstuneScreen(
                    state = viewModel.uiState,
                    actions = ScreenActions(
                        onUrlChange = viewModel::onUrlChange,
                        onResolve = viewModel::resolveTypedInput,
                        onPaste = { viewModel.pasteLink(clipboardText()) },
                        recognizers = recognizers,
                        recognizer = recognizers.firstOrNull { it.packageName == recognizerPick } ?: recognizers.firstOrNull(),
                        onRecognize = { if (it.listensHere) listen() else tryStartActivity(it.intent) },
                        onStopListening = viewModel::stopListening,
                        onOpenMicrophoneSettings = ::openAppInfo,
                        onRecognizerChange = { picked ->
                            recognizerPick = picked.packageName
                            getSharedPreferences(MainViewModel.PREFERENCES_NAME, MODE_PRIVATE).edit().putString(SongRecognizers.KEY_PICK, picked.packageName).apply()
                            // The widget's button opens the same app.
                            lifecycleScope.launch { CrosstuneWidget().updateAll(applicationContext) }
                        },
                        onClear = viewModel::clear,
                        onRetry = { if (viewModel.retryListens) listen() else viewModel.retry() },
                        onOpen = { viewModel.openResult() },
                        onOpenWith = { destination -> viewModel.openResult(destination, finishAfterOpen = true) },
                        onOpenOriginal = viewModel::openOriginal,
                        onDismissPicker = viewModel::dismissDestinationPicker,
                        onShowLyrics = viewModel::showLyrics,
                        onSeekLyrics = viewModel::seekLyrics,
                        onReadingsChange = viewModel::setReadings,
                        onRomanizedChange = viewModel::setRomanized,
                        onTranslationChange = viewModel::setTranslation,
                        onRetryTranslation = viewModel::retryTranslation,
                        onToggleSavedLine = viewModel::toggleSavedLine,
                        onRemoveSavedLine = viewModel::removeSavedLine,
                        onLookUpWord = viewModel::lookUpWord,
                        onDismissWord = viewModel::dismissWord,
                        onRepeatLine = viewModel::repeatLine,
                        onStopRepeating = viewModel::stopRepeating,
                        onTranslationServerChange = viewModel::setTranslationServer,
                        onListenAlong = { withMicrophone(viewModel::listenAlong) },
                        onStopListeningAlong = viewModel::stopListeningAlong,
                        onAllowFollowing = { if (viewModel.allowFollowing()) openNotificationAccess() },
                        onOpenFollowAccess = {
                            viewModel.openingFollowAccess()
                            openNotificationAccess()
                        },
                        onOpenAppInfo = ::openAppInfo,
                        onDismissFollowHelp = viewModel::dismissFollowHelp,
                        onThemeChange = ::selectTheme,
                        onSearchAnyway = viewModel::searchAnyway,
                        onNotFoundActionChange = viewModel::selectNotFoundAction,
                        onTakeNotFoundOffer = viewModel::takeNotFoundOffer,
                        onDismissNotFoundOffer = viewModel::dismissNotFoundOffer,
                        onPaletteChange = { palette ->
                            viewModel.selectPalette(palette)
                            // The widgets wear the app's color too.
                            lifecycleScope.launch { CrosstuneWidget.updateAllWidgets(applicationContext) }
                        },
                        onPureBlackChange = viewModel::setPureBlack,
                        onFloat = ::float,
                        onDismissLyrics = viewModel::dismissLyrics,
                        onPickSong = viewModel::chooseSong,
                        onDismissSongSearch = viewModel::dismissSongSearch,
                        onTargetChange = viewModel::selectDefault,
                        onResultTargetChange = viewModel::selectResultDestination,
                        onMakeDefault = { viewModel.selectDefault(viewModel.uiState.resultDestination) },
                        onInterceptChange = viewModel::setIntercepted,
                        onFrontendInterceptChange = viewModel::setFrontendIntercepted,
                        onFrontendRuleChange = viewModel::setFrontendRule,
                        onRuleChange = viewModel::setRule,
                        onAddCustom = viewModel::addCustomDestination,
                        onRemoveCustom = viewModel::removeCustomDestination,
                        onFrontendInstanceChange = viewModel::setFrontendInstance,
                        onCompleteSetup = viewModel::completeSetup,
                        onPreselectSources = viewModel::preselectSources,
                        onLinkModeChange = viewModel::setLinkMode,
                        onExactMatchChange = viewModel::setExactMatch,
                        onCleanLinksChange = viewModel::setCleanLinks,
                        onShareSheetAppsChange = viewModel::setShareSheetApps,
                        onOnlyMusicVideosChange = viewModel::setOnlyMusicVideos,
                        onLanguageChange = { AppLanguage.set(this, it) },
                        onCopySearch = ::copySearch,
                        onCopyLink = ::copyLink,
                        onShareSearch = ::shareSearch,
                        onHistoryEntryClick = viewModel::showHistoryEntry,
                        onHistoryOpen = viewModel::openHistoryEntry,
                        onRemoveHistory = viewModel::removeHistoryEntry,
                        onOpenTrack = viewModel::openTrack,
                        onPlayAll = viewModel::playAll,
                        onCopySongs = { copyToClipboard("Crosstune songs", viewModel.songList(), R.string.songs_copied) },
                        onShareSongs = { viewModel.songList()?.let { startActivity(shareChooser(it)) } },
                        onClearHistory = viewModel::clearHistory,
                        onUndoClearHistory = viewModel::undoClearHistory,
                        onForgetClearedHistory = viewModel::forgetClearedHistory,
                        onCancelHandoff = viewModel::cancelHandoff,
                        onOpenLinkSettings = ::openAppLinkSettings,
                        onOpenAppLinkSettings = { openLinkSettingsOf(it.packageName) },
                        onDismissLinkSettingsHelper = viewModel::dismissLinkSettingsHelper,
                        onSettingsLeft = viewModel::settingsLeft,
                        loadArtwork = viewModel.artwork::load
                    )
                )
                if (askingToFloat) {
                    FloatPermissionDialog(
                        onAllow = {
                            askingToFloat = false
                            floatOnceAllowed = true
                            tryStartActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()))
                        },
                        onDismiss = { askingToFloat = false }
                    )
                }
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onResume() {
        super.onResume()
        // The user may have just allowed links, or installed a music app, outside Crosstune.
        viewModel.refreshSystemState()
        // Or let Crosstune follow what music apps play.
        viewModel.refreshFollowing()
        recognizers = SongRecognizers.available(this)
        recognizerPick = SongRecognizers.pick(getSharedPreferences(MainViewModel.PREFERENCES_NAME, MODE_PRIVATE))
        // Or let it show over other apps, as just asked to, so the words float now.
        val canFloat = Settings.canDrawOverlays(this)
        viewModel.setCanFloatOverApps(canFloat)
        if (floatOnceAllowed && canFloat) float()
        floatOnceAllowed = false
    }

    /**
     * The words float over other apps now, Crosstune going behind, once Android allows it; until
     * then, it's said why it's about to be asked. On Android 13 their notification, which unlocks
     * them, is asked about once first.
     */
    private fun float() {
        if (!Settings.canDrawOverlays(this)) {
            askingToFloat = true
            return
        }
        val prefs = getSharedPreferences(MainViewModel.PREFERENCES_NAME, MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !prefs.getBoolean(KEY_ASKED_NOTIFICATIONS, false) &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            prefs.edit().putBoolean(KEY_ASKED_NOTIFICATIONS, true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        floatNow()
    }

    private fun floatNow() {
        if (viewModel.uiState.lyricsFor == null) return
        floatingOverApps = true
        FloatingLyricsService.start(this, floatingHost)
        moveTaskToBack(true)
    }

    /**
     * The floating window: wide enough for a line, with the microphone to listen along or stop. From
     * Android 12 it opens by itself when Crosstune is left with words on screen; before, see onUserLeaveHint.
     */
    private fun floatingParams(lyricsShown: Boolean, listening: Boolean): PictureInPictureParams {
        val toggle = PendingIntent.getBroadcast(this, 0, Intent(this, FloatingListenReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
        val label = getString(if (listening) R.string.lyrics_stop_listening else R.string.lyrics_listen_along)
        val action = RemoteAction(Icon.createWithResource(this, R.drawable.ic_recognize), label, label, toggle)
        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setActions(listOf(action))
            .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAutoEnterEnabled(lyricsShown) }
            .build()
    }

    /**
     * Leaving Crosstune with words on screen floats them: in a window of their own over other apps,
     * started while Crosstune is still in sight so it may keep listening along, or, before Android 12,
     * in picture-in-picture.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (viewModel.uiState.lyricsFor == null) return
        if (viewModel.uiState.canFloatOverApps && !floatingOverApps) {
            floatingOverApps = true
            FloatingLyricsService.start(this, floatingHost)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
        // A phone, or a user, that turned picture-in-picture off just leaves.
        runCatching { enterPictureInPictureMode(floatingParams(lyricsShown = true, listening = viewModel.uiState.listeningAlong)) }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        floating = isInPictureInPictureMode
    }

    /** Back in sight, the words stop floating, and listening along with them picks up again. */
    override fun onStart() {
        super.onStart()
        // Even from a screen made again since, in a new look or language.
        if (FloatingLyricsService.host != null) FloatingLyricsService.stop(this)
        floatingOverApps = false
        viewModel.resumeListeningAlong()
    }

    /**
     * Listening stops once Crosstune is out of sight, which it may no longer do; a rotation keeps it,
     * and so do words floating in their own window, which listen along.
     */
    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations) return
        if (viewModel.uiState.listening) viewModel.stopListening()
        if (!floatingOverApps) viewModel.pauseListeningAlong()
    }

    /** Closed for good, nothing's left for the words to float out of. */
    override fun onDestroy() {
        super.onDestroy()
        if (!isChangingConfigurations && FloatingLyricsService.host === floatingHost) FloatingLyricsService.stop(this)
    }

    private fun listen() = withMicrophone { viewModel.listen() }

    /** Does [action], asking for the microphone first if it hasn't been allowed. */
    private fun withMicrophone(action: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            afterMicrophone = action
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_PENDING_CLIPBOARD_READ, pendingClipboardRead)
        val state = viewModel.uiState
        if (state.handlingIncomingLink) outState.putString(STATE_INCOMING_LINK, state.linkText)
    }

    private fun handleIntent(intent: Intent) {
        // Reopening from Recents replays the original link; show the app instead of opening it again.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return

        when (intent.action) {
            // The widget's songs ask to be shown here rather than opened.
            Intent.ACTION_VIEW -> viewModel.resolveIncoming(
                intent.dataString,
                show = viewModel.uiState.showSongFirst || intent.getBooleanExtra(EXTRA_SHOW_SONG, false)
            )
            ACTION_OPEN_RECENT -> viewModel.openRecent(intent.dataString)
            Intent.ACTION_SEND -> {
                // Some apps share styled text, which getStringExtra would drop.
                val shared = listOf(Intent.EXTRA_TEXT, Intent.EXTRA_SUBJECT)
                    .mapNotNull { intent.getCharSequenceExtra(it)?.toString() }
                resolveShared(shared.firstOrNull { it.isNotBlank() } ?: shared.firstOrNull() ?: return, fromClipboard = false)
            }
            Intent.ACTION_PROCESS_TEXT -> viewModel.resolveIncoming(
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString(),
                show = viewModel.uiState.showSongFirst
            )
            ACTION_PASTE_FROM_CLIPBOARD -> pendingClipboardRead = intent.component?.className == PASTE_ALIAS
            ACTION_LISTEN -> if (intent.component?.className == LISTEN_ALIAS) withMicrophone { viewModel.listen(lyrics = intent.getBooleanExtra(EXTRA_LYRICS, false)) }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || !pendingClipboardRead) return
        pendingClipboardRead = false
        resolveShared(clipboardText(), fromClipboard = true)
    }

    /**
     * A link shared to Crosstune, or copied and opened from a launcher shortcut. A share sheet
     * entry may have picked an app to open it in, or to share or show it instead; either beats
     * showing it first. Each use is reported, so Android puts the entries used most up front.
     */
    private fun resolveShared(text: String?, fromClipboard: Boolean) {
        val chosen = AppShortcuts.chosenDestination(intent)
        val action = AppShortcuts.chosenAction(intent)
        intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID)?.let { getSystemService(ShortcutManager::class.java)?.reportShortcutUsed(it) }
        val show = action == AppShortcuts.ShareAction.SHOW || chosen == null && action == null && viewModel.uiState.showSongFirst
        val after = if (action == AppShortcuts.ShareAction.SHARE) AfterLookup.SHARE else AfterLookup.OPEN
        if (fromClipboard) viewModel.resolveClipboard(text, chosen, show, after) else viewModel.resolveIncoming(text, chosen, show, after)
    }

    /** Android only lets the focused app read the clipboard, which it is here: after focus or a tap. */
    private fun clipboardText(): String? {
        val clip = getSystemService(ClipboardManager::class.java)?.primaryClip
        return clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
    }

    /** Crosstune's App info: where the microphone is allowed, and restricted settings. */
    private fun openAppInfo() {
        tryStartActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
    }

    /** Light, dark or as the phone is: from Android 12 Android itself is told, so the launch screen matches too. */
    private fun selectTheme(theme: ThemeMode) {
        viewModel.selectTheme(theme)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val mode = when (theme) {
                ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
                ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
                ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
            }
            getSystemService(UiModeManager::class.java).setApplicationNightMode(mode)
        }
    }

    /** Android's page for letting Crosstune see what music apps play, at its own entry where Android has one. */
    private fun openNotificationAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val own = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(this, NowPlayingListener::class.java).flattenToString())
            if (tryStartActivity(own)) return
        }
        tryStartActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun open(effect: Effect.Open) {
        val uri = effect.url.toUri()
        val packageName = effect.packageName
        val opened = packageName != null && tryStartActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(packageName)) ||
            openOutsideCrosstune(uri)
        when {
            !opened -> viewModel.showError(AppError.NO_APP_TO_OPEN)
            effect.finishAfterOpen -> finish()
        }
    }

    /**
     * Opens [uri] in any app but Crosstune. Crosstune may be the default handler for music links,
     * so a plain VIEW intent could loop straight back here.
     */
    private fun openOutsideCrosstune(uri: Uri): Boolean {
        // Custom destinations may use an app's own scheme, which Crosstune never handles.
        if (uri.scheme != "https" && uri.scheme != "http") return tryStartActivity(Intent(Intent.ACTION_VIEW, uri))
        val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        val (own, others) = packageManager.queryIntentActivities(intent, 0)
            .partition { it.activityInfo.packageName == packageName }
        if (others.isEmpty()) return false
        val defaultPackage = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
        return when {
            others.any { it.activityInfo.packageName == defaultPackage } -> tryStartActivity(intent)
            others.size == 1 -> tryStartActivity(
                intent.setClassName(others[0].activityInfo.packageName, others[0].activityInfo.name)
            )
            else -> tryStartActivity(
                Intent.createChooser(intent, null).putExtra(
                    Intent.EXTRA_EXCLUDE_COMPONENTS,
                    own.map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }.toTypedArray()
                )
            )
        }
    }

    private fun tryStartActivity(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    private fun copySearch() {
        copyToClipboard("Crosstune search query", viewModel.searchQuery(), R.string.search_copied_to_clipboard)
    }

    private fun copyLink() {
        copyToClipboard("Crosstune link", viewModel.destinationUrl(), R.string.link_copied_to_clipboard)
    }

    private fun copyToClipboard(label: String, text: String?, confirmationRes: Int) {
        text ?: return
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        // Android 13+ shows its own confirmation whenever the clipboard changes.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, getString(confirmationRes), Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareSearch() {
        val url = viewModel.destinationUrl() ?: return
        startActivity(shareChooser(url))
    }

    /**
     * The share sheet for [url]. Crosstune itself is left out, its share sheet entries included:
     * sharing to it from here would only loop.
     */
    private fun shareChooser(url: String): Intent {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        return Intent.createChooser(shareIntent, getString(R.string.share_search_link))
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(this, MainActivity::class.java)))
    }

    private fun openAppLinkSettings() {
        openLinkSettingsOf(packageName)
        viewModel.dismissLinkSettingsHelper()
    }

    /** Opens Android's link settings for one app: Crosstune's to allow links, another's to turn its links off. */
    private fun openLinkSettingsOf(appPackage: String) {
        val packageUri = "package:$appPackage".toUri()
        val openedDefaults = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            tryStartActivity(Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, packageUri))
        if (!openedDefaults) {
            tryStartActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
        }
    }
}
