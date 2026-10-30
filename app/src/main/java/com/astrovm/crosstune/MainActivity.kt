package com.astrovm.crosstune

import android.app.ActivityManager
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.util.Rational
import android.app.UiModeManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutManager
import android.Manifest
import androidx.glance.appwidget.updateAll
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.textclassifier.TextClassifier
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {

    /** Apps that name a song playing nearby, looked up again whenever the app comes back. */
    private var recognizers by mutableStateOf(emptyList<SongRecognizer>())

    private val viewModel: MainViewModel by viewModels {
        viewModelFactory {
            initializer {
                val client = httpClientFactory()
                // Left out of backups, so the tokens never leave the phone.
                val chatGpt = ChatGpt(client, File(noBackupFilesDir, "chatgpt.json"), packageName)
                // What ChatGPT said is kept, so a song read again asks nothing.
                val tutor = ChatGptTutor(
                    chatGpt,
                    LookupCache(File(cacheDir, "chatgpt_answers.json"), lookupDispatcher, maxEntries = 1000),
                    lines = LookupCache(File(cacheDir, "chatgpt_lines.json"), lookupDispatcher, maxEntries = 6000)
                )
                // One of each, so SoundCloud's key is read once for both.
                val apis = ServiceApis(client, Locale.getDefault().country)
                // Skipping on from the end of a part of a long playlist plays the next part.
                val queue = playQueue(applicationContext)
                // The order songs up next were moved to, over the app's own.
                val order = queueOrder(applicationContext)
                val playback = order.ordering(queue.skipping(playbackFactory(applicationContext)))
                // What was found for songs is kept, so a playlist played again needs no lookups.
                // Signed in, ChatGPT picks a song named otherwise among the results when nothing matches by name.
                val matcher = ExactMatcher(client, cache = LookupCache(File(cacheDir, "lookups.json"), lookupDispatcher), apis = apis, pick = tutor::pickSong, missingForMs = MISSING_FOR_MS)
                MainViewModel(
                    LinkResolver(client, apis = apis),
                    matcher,
                    LyricsFinder(
                        client,
                        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty(),
                        busyPauseMs = lyricsBusyPauseMs,
                        cache = LookupCache(File(cacheDir, "lyrics.json"), lookupDispatcher, maxEntries = 300),
                        youTubeMusicSong = { song -> matcher.find(MusicService.YOUTUBE_MUSIC, song)?.substringAfter("watch?v=", "")?.ifEmpty { null } }
                    ),
                    SongSearcher(client),
                    playback,
                    getSharedPreferences(MainViewModel.PREFERENCES_NAME, MODE_PRIVATE),
                    LinkInterception(applicationContext),
                    ArtworkLoader(client, cacheDir = File(cacheDir, ARTWORK_DIR)),
                    hearingFactory?.invoke().let { hearing ->
                        PlayingFirst(playback, hearing ?: SongListener(microphoneFactory(), Shazam(client)), hearing ?: SongListener(PlayedSound(soundTapFactory, getSharedPreferences(MainViewModel.PREFERENCES_NAME, MODE_PRIVATE)), Shazam(client)))
                    },
                    systemDispatcher,
                    listenAlongPauseMs,
                    quietPauseMs = quietPauseMs,
                    // Each line translated is kept, so a song read again needs no translating.
                    translator = Translator(client, LookupCache(File(cacheDir, "translations.json"), lookupDispatcher)),
                    // Read each time, from the app rather than this screen, which a language change replaces.
                    translationLanguage = {
                        Translator.languageOf(AppLanguage.current(applicationContext)?.let(Locale::forLanguageTag) ?: Locale.getDefault())
                    },
                    chatGpt = chatGpt,
                    tutor = tutor,
                    // Each song's chords are kept, so a song played again needs no lookup.
                    chordFinder = ChordFinder(client, cache = LookupCache(File(cacheDir, "chords.json"), lookupDispatcher, maxEntries = 300)),
                    lyricsLanguage = lyricsLanguageFactory?.invoke() ?: AndroidLyricsLanguage(applicationContext),
                    oneTap = OneTapSetup(applicationContext, shellFactory()),
                    // Each song's sung readings are kept, so a song read again needs no lookup.
                    sungReadings = SungReadings(client, LookupCache(File(cacheDir, "sung_readings.json"), lookupDispatcher, maxEntries = 300)),
                    uniDic = uniDicFactory(applicationContext, client),
                    updates = UpdateChecker(
                        client, getSharedPreferences(MainViewModel.PREFERENCES_NAME, MODE_PRIVATE), packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
                    ).takeIf { checksForUpdates(applicationContext) },
                    mayCalibrate = { checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED },
                    singing = singingFactory(applicationContext, client),
                    reports = ReportSender(client, packageManager.getPackageInfo(packageName, 0).versionName.orEmpty(), lookupDispatcher),
                    microphoneAccess = { microphoneAccess(applicationContext) },
                    phone = phoneFactory(applicationContext),
                    // The words of the songs shown lately, so they show at once after the app's closed too.
                    keptOnDisk = LookupCache(File(cacheDir, "kept_words.json"), lookupDispatcher, maxEntries = MainViewModel.KEPT_SONGS),
                    lookAhead = lookAheadLyrics,
                    playQueue = queue,
                    queueOrder = order,
                    sleepTimer = sleepTimer(applicationContext)
                )
            }
        }
    }

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
        override fun toggleListening() = when {
            viewModel.uiState.listeningAlong -> viewModel.stopListeningAlong()
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> viewModel.listenAlong()
            // The microphone is asked for here, in sight, once back.
            else -> {
                listenAlongWhenBack = true
                startActivity(Intent(this@MainActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
        }
        override val learningActions get() = ScreenActions(
            onWordsAsChange = viewModel::setWordsAs,
            onReadingsChange = viewModel::setReadings,
            onRomanizedChange = viewModel::setRomanized,
            onRomajiTypedChange = viewModel::setRomajiTyped,
            onTranslationChange = viewModel::setTranslation,
            onWordsScaleChange = viewModel::setWordsScale,
            onReadingScaleChange = viewModel::setReadingScale,
            onTranslationScaleChange = viewModel::setTranslationScale
        )
        override val actions get() = screenActions()
        override fun playPause() = viewModel.playPause()
        override fun update(options: FloatingOptions, save: Boolean) = viewModel.setFloating(options, save)
        override fun closed() {
            floatingOverApps = false
            viewModel.uncover()
            viewModel.pauseListeningAlong()
            viewModel.pauseFollowingNext()
        }
    }

    /** Set while away in Android's settings to allow it, so the words float once back, if it was. */
    private var floatOnceAllowed = false

    /**
     * Words over other apps that are locked are unlocked from their notification, which Android 13
     * asks about first; asked or not, they float after.
     */
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { floatNow() }

    /**
     * Signing in with ChatGPT asks for notifications first on Android 13: without them, phones like
     * Xiaomi's freeze Crosstune once the browser's open, keep-alive service and all, and the sign-in
     * waits until Crosstune's opened again. Asked or not, it signs in after.
     */
    private val signInNotificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { signInWithChatGpt() }

    /** Set when the floating words' microphone was tapped without it allowed, to ask once back in sight. */
    private var listenAlongWhenBack = false

    /**
     * What the microphone is for: listening for a song, maybe for its words, listening along with the
     * words, visuals that move with what plays, or nothing yet, just allowed.
     */
    private enum class MicrophoneFor { LISTEN, LISTEN_FOR_LYRICS, LISTEN_ALONG, VISUALS, ALLOW }

    /** What asked for the microphone, kept should the screen be made again while Android asks, e.g. turned. */
    private var afterMicrophone = MicrophoneFor.LISTEN

    /**
     * Asked on the first listen, or when lyrics first open; turned down, the error offers Android's
     * settings for Crosstune. Turned down for good, Android won't ask again, so the steps show how instead.
     */
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            getSharedPreferences(MainViewModel.PREFERENCES_NAME, MODE_PRIVATE).edit().putBoolean(KEY_MICROPHONE_DENIED, true).apply()
        }
        viewModel.refreshMicrophone()
        if (granted) listenFor(afterMicrophone) else if (afterMicrophone != MicrophoneFor.ALLOW) viewModel.showError(AppError.MICROPHONE)
    }

    /** Set when launched to read the clipboard, which Android only allows once the window has focus. */
    private var pendingClipboardRead = false

    companion object {
        /** Memory a phone needs, at least, to hear the words sung: the model takes about 100 MB while it listens. */
        const val SINGING_MEMORY = 3L * 1024 * 1024 * 1024

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
        /** Set once the microphone was asked for as lyrics opened, so they don't ask every time. */
        private const val KEY_ASKED_MICROPHONE = "asked_microphone"
        /** Set when the microphone was turned down for good, so Android won't ask again until it's allowed in settings. */
        private const val KEY_MICROPHONE_DENIED = "microphone_denied"

        /** Whether the microphone may be used, Android still asks for it, or it's turned down for good. */
        private fun microphoneAccess(context: Context): MicAccess = when {
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> MicAccess.ALLOWED
            context.getSharedPreferences(MainViewModel.PREFERENCES_NAME, MODE_PRIVATE).getBoolean(KEY_MICROPHONE_DENIED, false) -> MicAccess.DENIED
            else -> MicAccess.ASKABLE
        }
        /** When the last clip Crosstune looked at, or copied itself, was copied. */
        private const val KEY_CLIP_SEEN = "clip_seen_at"
        /** How long after it's copied a link counts as just copied. */
        private const val FRESH_COPY_MS = 10 * 60 * 1000L

        /** Where the widget is drawn again, outliving the screen that asked. */
        private val widgetUpdates = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

        /** How long an app may wait to be opened, while Crosstune is out of sight, before it's not. */
        private const val STALE_OPEN_MS = 60_000L

        /** How long a song not found somewhere stays not found there, before it's looked for again. */
        private const val MISSING_FOR_MS = 30 * 60 * 1000L
        private const val STATE_PENDING_CLIPBOARD_READ = "pending_clipboard_read"
        private const val STATE_MICROPHONE_FOR = "microphone_for"
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

        /** Crosstune's own queue, and the app it's for. */
        private var playQueue: Pair<Context, PlayQueue>? = null

        /**
         * Crosstune's own queue, playing a long playlist through: one for the whole app, as it outlives
         * the screen, started again by [NowPlayingListener] after Android stops Crosstune.
         */
        internal fun playQueue(context: Context): PlayQueue {
            val app = context.applicationContext
            playQueue?.takeIf { it.first === app }?.let { return it.second }
            // One made for another app, as each test makes, is done with.
            playQueue?.second?.stop()
            // Its own lookups, as it may play on with no screen to share them with.
            val matcher = ExactMatcher(httpClientFactory(), cache = LookupCache(File(app.cacheDir, "queue_lookups.json"), lookupDispatcher), missingForMs = MISSING_FOR_MS)
            return PlayQueue(
                playbackFactory(app),
                { service, tracks -> matcher.youtubeQueue(service, tracks) {} },
                File(app.noBackupFilesDir, "play_queue.json"),
                CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                lookupDispatcher,
                app::hasApp
            ).also { playQueue = app to it }
        }

        private var queueOrder: Pair<Context, QueueOrder>? = null

        /** The order songs up next were moved to, the app's rather than a screen's, so it keeps it with Crosstune closed too. */
        internal fun queueOrder(context: Context): QueueOrder {
            val app = context.applicationContext
            queueOrder?.takeIf { it.first === app }?.let { return it.second }
            // One made for another app, as each test makes, is done with.
            queueOrder?.second?.off()
            return QueueOrder(playbackFactory(app), CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)).also { queueOrder = app to it }
        }

        private var sleepTimer: Pair<Context, SleepTimer>? = null

        /** The sleep timer, the app's rather than a screen's, so it pauses with Crosstune closed too. */
        internal fun sleepTimer(context: Context): SleepTimer {
            val app = context.applicationContext
            sleepTimer?.takeIf { it.first === app }?.let { return it.second }
            // One made for another app, as each test makes, is done with.
            sleepTimer?.second?.off()
            return SleepTimer(playbackFactory(app), CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)).also { sleepTimer = app to it }
        }

        /** Whether a song's lyrics are looked up as its card shows; off where a test counts what's asked. */
        @VisibleForTesting
        internal var lookAheadLyrics: Boolean = true

        /** How long lyrics wait before asking a busy LRCLIB again; tests don't wait. */
        @VisibleForTesting
        internal var lyricsBusyPauseMs: Long = LYRICS_BUSY_PAUSE_MS

        /** How long listening along waits between songs heard; tests don't wait. */
        @VisibleForTesting
        internal var listenAlongPauseMs: Long = MainViewModel.LISTEN_ALONG_PAUSE_MS

        /** How long listening along first rests after hearing nothing; tests don't wait. */
        @VisibleForTesting
        internal var quietPauseMs: Long = MainViewModel.LISTEN_ALONG_PAUSE_MS

        /** Whether this copy looks for a newer Crosstune, so a test can say. */
        @VisibleForTesting
        internal var checksForUpdates: (Context) -> Boolean = ::looksForUpdates

        /** What hears songs nearby, when a test stands in for the microphone and Shazam both. */
        @VisibleForTesting
        internal var hearingFactory: (() -> SongHearing)? = null

        @VisibleForTesting
        internal var microphoneFactory: () -> Microphone = { AudioRecordMicrophone() }

        @VisibleForTesting
        /** What draws the visuals behind the words; replaced in tests, which have no GPU or native library. */
        internal var milkdropFactory: (Context) -> Milkdrop = ::NativeMilkdrop

        @VisibleForTesting
        /** What the visuals hear the phone play through. */
        internal var soundTapFactory: () -> SoundTap? = { OutputMixTap.open() }

        /**
         * What hears the words sung in what the phone plays: whisper.cpp's model, fetched once on an
         * unmetered network into files kept out of backups. None on a phone with little memory, e.g. a
         * TV's 2 GB, where it would push the music app out.
         */
        @VisibleForTesting
        internal var singingFactory: (Context, OkHttpClient) -> SingingHearing? = { context, client ->
            val memory = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(it) }
            val model = WhisperModel(client, File(context.noBackupFilesDir, "whisper"), mayFetch = { context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == false })
            SingingListener(
                PlayedSound(soundTapFactory, context.getSharedPreferences(MainViewModel.PREFERENCES_NAME, MODE_PRIVATE)),
                model::file,
                NativeWhisper::open,
                fetch = model::fetchAnyway
            ).takeIf { memory.totalMem >= SINGING_MEMORY }
        }

        /** UniDic, fetched once on an unmetered network into files kept out of backups; a test may have none. */
        @VisibleForTesting
        internal var uniDicFactory: (Context, OkHttpClient) -> UniDic? = { context, client ->
            UniDic(client, File(context.noBackupFilesDir, "unidic"), mayFetch = { context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == false })
        }

        /** What tells which language a song's words are in, when a test stands in for Android. */
        @VisibleForTesting
        internal var lyricsLanguageFactory: (() -> LyricsLanguage)? = null

        /** Where setup in one tap runs its commands: Shizuku, or else root, unless a test stands in for them. */
        @VisibleForTesting
        internal var shellFactory: () -> List<Shell> = { listOf(ShizukuShell(), RootShell()) }

        /** The kind of phone, whose settings pages the steps to fix things show. */
        @VisibleForTesting
        internal var phoneFactory: (Context) -> Phone = {
            Phone.of(Build.MANUFACTURER, Build.BRAND, tv = it.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingClipboardRead = savedInstanceState?.getBoolean(STATE_PENDING_CLIPBOARD_READ) == true
        savedInstanceState?.getString(STATE_MICROPHONE_FOR)?.let { kept -> MicrophoneFor.entries.firstOrNull { it.name == kept } }?.let { afterMicrophone = it }
        val incomingLink = savedInstanceState?.getString(STATE_INCOMING_LINK)
        when {
            savedInstanceState == null -> handleIntent(intent)
            // After a configuration change the ViewModel already holds this intent's result or request.
            // After Android ends the process in the background it doesn't, so look the link up again.
            incomingLink != null && !viewModel.uiState.handlingIncomingLink -> viewModel.resolveIncoming(incomingLink)
        }

        val shortcuts = AppShortcuts(applicationContext, viewModel.artwork::load)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) { viewModel.watchPlayingNow() }
        }
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
                        // The widget shows Recent too, drawn again even once Crosstune closes, e.g.
                        // right after opening a link shared from another app.
                        widgetUpdates.launch { CrosstuneWidget().updateAll(applicationContext) }
                    }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.effects.collect { effect ->
                    when (effect) {
                        // Asked long ago, e.g. before Crosstune was left, it's no longer what the user is after.
                        is Effect.Open -> if (SystemClock.elapsedRealtime() - effect.atMs <= STALE_OPEN_MS) open(effect)
                        is Effect.Show -> show(effect)
                        Effect.RecognizeElsewhere -> recognizers.firstOrNull { !it.listensHere }?.let { tryStartActivity(it.intent) }
                        is Effect.Share -> {
                            startActivity(shareChooser(effect.url))
                            finish()
                        }
                    }
                }
            }
        }

        // Out of sight too: a song's app opens over Crosstune, which then covers it.
        lifecycleScope.launch {
            snapshotFlow { viewModel.uiState.coveringApp }.filter { it }.collect { cover() }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                snapshotFlow { viewModel.uiState.lyricsFor != null }.distinctUntilChanged().collect { open -> if (open) askMicrophoneOnce() }
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
            CrosstuneTheme(darkTheme = dark, palette = viewModel.uiState.palette, pureBlack = viewModel.uiState.pureBlack) {
                CrosstuneScreen(
                    state = viewModel.uiState,
                    actions = screenActions()
                )
            }
        }
    }

    /** Everything Crosstune's screens do, whether in sight or covering another app. */
    private fun screenActions() = ScreenActions(
        onUrlChange = viewModel::onUrlChange,
        onResolve = viewModel::resolveTypedInput,
        onPaste = { viewModel.pasteLink(clipboardText()) },
        recognizers = recognizers,
        recognizer = recognizers.firstOrNull(),
        onRecognize = { if (it.listensHere) listen() else tryStartActivity(it.intent) },
        onLyricsNow = { withMicrophone(MicrophoneFor.LISTEN_FOR_LYRICS) },
        onStopListening = viewModel::stopListening,
        onOpenMicrophoneSettings = { openAppInfo() },
        onClear = viewModel::clear,
        onRetry = { if (viewModel.retryListens) listen() else viewModel.retry() },
        onOpen = { viewModel.openResult() },
        onPlayResult = viewModel::playResult,
        onClosePlayer = viewModel::closePlayer,
        onPlayerOpenApp = viewModel::openPlayerApp,
        onPlayerOpenWith = viewModel::playerOpenWith,
        onPlayerMenu = viewModel::playerMenu,
        onPlayerPlayPause = viewModel::playerPlayPause,
        onPlayerSkip = viewModel::playerSkip,
        onPlayerShuffle = viewModel::playerShuffle,
        onPlayerRepeat = viewModel::playerRepeat,
        onPlayerLike = viewModel::playerLike,
        onPlayerPlayQueued = viewModel::playerPlayQueued,
        onPlayerPlayLater = viewModel::playerPlayLater,
        onPlayerMoveQueued = viewModel::playerMoveQueued,
        onPlayerCard = viewModel::playerCard,
        onPlayerSleep = viewModel::playerSleep,
        onPlayerSleepOff = viewModel::playerSleepOff,
        onPlayerSeek = viewModel::playerSeek,
        onPlayerLyrics = viewModel::playerLyrics,
        onOpenWith = { destination -> viewModel.openResult(destination, finishAfterOpen = true) },
        onOpenOriginal = viewModel::openOriginal,
        onDismissPicker = viewModel::dismissDestinationPicker,
        onShowLyrics = viewModel::showLyrics,
        onSeekLyrics = viewModel::seekLyrics,
        onSyncLine = viewModel::syncLine,
        onPlayPause = viewModel::playPause,
        onSkipSong = viewModel::skipSong,
        onNudgeSync = viewModel::nudgeSync,
        onResetSync = viewModel::resetSync,
        onSendReport = viewModel::sendReport,
        onCloseReport = viewModel::closeReport,
        onAllowAutoReports = viewModel::allowAutoReports,
        onFetchSingingModel = viewModel::fetchSingingModel,
        onCloseSingingModel = viewModel::closeSingingModel,
        onReadingsChange = viewModel::setReadings,
        onRomanizedChange = viewModel::setRomanized,
        onRomajiTypedChange = viewModel::setRomajiTyped,
        onCardTypesChange = viewModel::setCardTypes,
        onTranslationChange = viewModel::setTranslation,
        onChordsChange = viewModel::setChords,
        onRetryTranslation = viewModel::retryTranslation,
        onToggleSavedLine = viewModel::toggleSavedLine,
        onLookUpWord = viewModel::lookUpWord,
        onExplainLine = viewModel::explainLine,
        onStartPractice = { viewModel.startPractice() },
        onPractiseSong = viewModel::practiseSong,
        onPractiseSongOf = viewModel::startPractice,
        onReadCardAs = viewModel::readCardAs,
        onTurnPractice = viewModel::turnPractice,
        onGradePractice = viewModel::gradePractice,
        onStopPractice = viewModel::stopPractice,
        onReplayLine = viewModel::replayLine,
        onBackToBefore = viewModel::backToBefore,
        onChooseLines = viewModel::chooseLines,
        onKeepAgain = viewModel::keepAgain,
        onAskAboutLine = viewModel::askAboutLine,
        onExplainSong = viewModel::explainSong,
        onAskAboutSong = viewModel::askAboutSong,
        onWordsAsChange = viewModel::setWordsAs,
        onFindWordsToKnow = viewModel::findWordsToKnow,
        onToggleSavedWord = viewModel::toggleSavedWord,
        onAboutArtist = viewModel::aboutArtist,
        onSongsLike = viewModel::songsLike,
        onFindExamples = viewModel::findExamples,
        onOpenNamedSong = viewModel::openNamedSong,
        onPlaylistFromSearch = viewModel::playlistFromSearch,
        onDismissWord = viewModel::dismissWord,
        onRepeatLine = viewModel::repeatLine,
        onStopRepeating = viewModel::stopRepeating,
        onChatGptTranslationChange = viewModel::setChatGptTranslation,
        onChatGptModelChange = viewModel::pickChatGptModel,
        onTranslateIntoChange = viewModel::setTranslateInto,
        onUntranslatedChange = viewModel::setUntranslated,
        onReadingScaleChange = viewModel::setReadingScale,
        onWordsScaleChange = viewModel::setWordsScale,
        onWordsCenteredChange = viewModel::setWordsCentered,
        onTranslationScaleChange = viewModel::setTranslationScale,
        onSignInWithChatGpt = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                signInNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                signInWithChatGpt()
            }
        },
        onCancelChatGptSignIn = viewModel::cancelChatGptSignIn,
        onSignOutOfChatGpt = viewModel::signOutOfChatGpt,
        onDismissChatGptWelcome = viewModel::dismissChatGptWelcome,
        onListenAlong = { withMicrophone(MicrophoneFor.LISTEN_ALONG) },
        onStopListeningAlong = viewModel::stopListeningAlong,
        // With Shizuku or root, in one tap, as in setup; otherwise, or once it couldn't, the steps.
        onAllowFollowing = { if (!viewModel.fixInOneTap(OneTapFix.FOLLOW)) viewModel.fixSteps(listOf(Need.Follow)) },
        onOpenFollowAccess = { openNotificationAccess(viewModel.uiState.phone == Phone.TV) },
        onOpenAppInfo = { openAppInfo() },
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
        onPlayingBarChange = viewModel::setPlayingBar,
        onLinesEarlyChange = viewModel::setLinesEarly,
        onNextLineFirstChange = viewModel::setNextLineFirst,
        onFloat = ::float,
        onVisualsShadeChange = viewModel::setVisualsShade,
        onNextVisual = viewModel::nextVisual,
        onDropVisual = viewModel::dropVisual,
        onVisualOnChange = viewModel::setVisualOn,
        onSkipHeavyVisualsChange = viewModel::setSkipHeavyVisuals,
        onVisualsChangeEveryChange = viewModel::setVisualsChangeEvery,
        onVisualsChange = { on -> if (on) withMicrophone(MicrophoneFor.VISUALS) else viewModel.setVisuals(false) },
        onDismissLyrics = viewModel::dismissLyrics,
        onLyricsToPlayer = viewModel::lyricsToPlayer,
        onPickSong = viewModel::chooseSong,
        onDismissSongSearch = viewModel::dismissSongSearch,
        onTargetChange = viewModel::selectDefault,
        onResultTargetChange = viewModel::selectResultDestination,
        onMakeDefault = { viewModel.selectDefault(viewModel.uiState.resultDestination) },
        onInterceptChange = viewModel::setIntercepted,
        onFrontendRuleChange = viewModel::setFrontendRule,
        onRuleChange = viewModel::setRule,
        onAddCustom = viewModel::addCustomDestination,
        onRemoveCustom = viewModel::removeCustomDestination,
        onFrontendInstanceChange = viewModel::setFrontendInstance,
        onSetUpInOneTap = viewModel::setUpInOneTap,
        onAllowLinks = { if (!viewModel.fixInOneTap(OneTapFix.LINKS)) viewModel.fixSteps(listOf(Need.Links)) },
        onOpenFixes = viewModel::openFixes,
        onFixSteps = viewModel::fixSteps,
        onNextFix = viewModel::nextFix,
        onFixBack = viewModel::fixBack,
        onCloseFixes = viewModel::closeFixes,
        onOpenFix = ::openFix,
        onTryLink = ::tryLink,
        onOpenShizuku = { packageManager.getLaunchIntentForPackage(OneTapSetup.SHIZUKU_PACKAGE)?.let(::tryStartActivity) },
        onShowInCrosstuneFirstChange = viewModel::setShowInCrosstuneFirst,
        onPlayRightAwayChange = viewModel::setPlayRightAway,
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
        onPlayAll = { viewModel.playAll() },
        onShuffleAll = { viewModel.playAll(shuffled = true) },
        onCopySongs = { copyToClipboard("Crosstune songs", viewModel.songList(), R.string.songs_copied) },
        onShareSongs = { viewModel.songList()?.let { startActivity(shareChooser(it)) } },
        onClearHistory = viewModel::clearHistory,
        onUndoClearHistory = viewModel::undoClearHistory,
        onForgetClearedHistory = viewModel::forgetClearedHistory,
        onCancelHandoff = viewModel::cancelHandoff,
        onOpenAppLinkSettings = { openLinkSettingsOf(it.packageName) },
        onDismissNotices = viewModel::dismissNotices,
        onUpdate = viewModel::openUpdate,
        onOpenPlayingNow = viewModel::openPlayingNow,
        onPeekPlayingNow = viewModel::peekPlayingNow,
        onPlayingNowPlayPause = viewModel::playingNowPlayPause,
        onPlayingNowSkip = viewModel::playingNowSkip,
        onSettingsLeft = viewModel::settingsLeft,
        loadArtwork = viewModel.artwork::load,
        covers = viewModel.artwork
    )

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onResume() {
        super.onResume()
        refresh()
        recognizers = SongRecognizers.available(this)
        // Its steps left without allowing it, the words don't float later out of the blue.
        if (viewModel.uiState.fixes?.need != Need.Float) floatOnceAllowed = false
        // Let show over other apps, as just asked to, the words float now.
        if (floatOnceAllowed && viewModel.uiState.canFloatOverApps) {
            floatOnceAllowed = false
            float()
        }
    }

    /**
     * What may have changed outside Crosstune: links just allowed, a music app installed, following
     * music apps allowed, the microphone, or showing over other apps.
     */
    private fun refresh() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            getSharedPreferences(MainViewModel.PREFERENCES_NAME, MODE_PRIVATE).edit().remove(KEY_MICROPHONE_DENIED).apply()
        }
        viewModel.refreshSystemState()
        viewModel.refreshFollowing()
        viewModel.setCanFloatOverApps(Settings.canDrawOverlays(this))
    }

    /** Opens Android's page where [need] is done, with the guide floating over it. */
    private fun openFix(need: Need) {
        // Opened, before Android 12 it's no longer known whether they're allowed, until a test link says.
        if (need == Need.Links) viewModel.dismissLinkSettingsHelper()
        if (need == Need.Float) floatOnceAllowed = true
        val open: Context.() -> Unit = when (need) {
            is Need.StopApp -> fun Context.() = openLinkSettingsOf(need.app.packageName)
            Need.Links -> fun Context.() = openLinkSettingsOf(packageName)
            Need.Microphone -> Context::openAppInfo
            Need.Follow -> (viewModel.uiState.phone == Phone.TV).let { tv -> fun Context.() = openNotificationAccess(tv) }
            Need.Float -> Context::openOverlaySettings
        }
        // Without picture-in-picture, the steps wait here in Crosstune.
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return open()
        GuideActivity.host = guideHost
        guideOpens = open
        startActivity(Intent(this, GuideActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** What Android's page the guide floats over is, opened from it or, without one, from here. */
    private var guideOpens: Context.() -> Unit = {}

    private val guideHost = object : GuideHost {
        override val state get() = viewModel.uiState
        override val dark get() = floatingHost.dark
        override fun refresh() = this@MainActivity.refresh()
        override fun open(guide: Context?) = (guide ?: this@MainActivity).guideOpens()
        override fun back(guide: Context) {
            guide.startActivity(Intent(guide, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
    }

    /** Before Android 12, a link of the service [need] is about, which reaches Crosstune once its links do. */
    private fun tryLink(need: Need) {
        viewModel.uiState.testService(need)?.let { tryStartActivity(Intent(Intent.ACTION_VIEW, testLink(it).toUri()).addCategory(Intent.CATEGORY_BROWSABLE)) }
    }

    /**
     * The words float over other apps now, Crosstune going behind, once Android allows it: with
     * Shizuku or root, in one tap; otherwise the steps show how. On Android 13 their notification,
     * which unlocks them, is asked about once first.
     */
    private fun float() {
        if (!Settings.canDrawOverlays(this)) {
            if (!viewModel.fixInOneTap(OneTapFix.FLOAT) { float() }) viewModel.fixSteps(listOf(Need.Float))
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

    /** Signs in with ChatGPT, kept running while the browser signs in, until it's done, however it ends. */
    private fun signInWithChatGpt() {
        ChatGptSignInService.start(this)
        viewModel.signInWithChatGpt(done = { ChatGptSignInService.stop(applicationContext) })
    }

    private fun floatNow() {
        if (viewModel.uiState.lyricsFor == null) return
        floatingOverApps = true
        viewModel.uncover()
        FloatingLyricsService.start(this, floatingHost)
        moveTaskToBack(true)
    }

    /** On a TV, the whole screen covers the app a song plays in, which goes on playing under it. */
    private fun cover() {
        floatingOverApps = true
        FloatingLyricsService.start(this, floatingHost, whole = true)
    }

    /** Opened from the screen covering another app, an app would show under it, so it goes first. */
    override fun startActivity(intent: Intent, options: Bundle?) {
        if (viewModel.uiState.coveringApp) {
            FloatingLyricsService.stop(this)
            floatingHost.closed()
        }
        super.startActivity(intent, options)
    }

    /** Back in sight, the words stop floating, and listening along with them, and on to the next song, picks up again. */
    override fun onStart() {
        super.onStart()
        // Back from Android's settings, the guide floating over them is done with.
        GuideActivity.close()
        // Even from a screen made again since, in a new look or language.
        if (FloatingLyricsService.host != null) FloatingLyricsService.stop(this)
        floatingOverApps = false
        viewModel.uncover()
        viewModel.resumeListeningAlong()
        viewModel.resumeFollowingNext()
        if (listenAlongWhenBack) {
            listenAlongWhenBack = false
            withMicrophone(MicrophoneFor.LISTEN_ALONG)
        }
    }

    /**
     * Listening stops once Crosstune is out of sight, which it may no longer do, and the words stay on
     * the song they show; a rotation keeps both, and so do words floating in their own window.
     */
    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations) return
        // Left with the words open, they're kept, as Android may close the app before they're shut.
        viewModel.keep()
        if (viewModel.uiState.listening) viewModel.stopListening()
        if (!floatingOverApps) {
            viewModel.pauseListeningAlong()
            viewModel.pauseFollowingNext()
        }
    }

    /** Closed for good, nothing's left for the words to float out of. */
    override fun onDestroy() {
        super.onDestroy()
        if (!isChangingConfigurations && FloatingLyricsService.host === floatingHost) FloatingLyricsService.stop(this)
        // The guide's host is this screen, which mustn't outlive it.
        if (isFinishing && GuideActivity.host === guideHost) GuideActivity.host = null
    }

    private fun listen() = withMicrophone(MicrophoneFor.LISTEN)

    /**
     * Listens as [purpose] says, asking for the microphone first if it hasn't been allowed; turned
     * down for good, Android won't ask, so the steps show how to allow it in settings.
     */
    private fun withMicrophone(purpose: MicrophoneFor) {
        afterMicrophone = purpose
        when (microphoneAccess(this)) {
            MicAccess.ALLOWED -> listenFor(purpose)
            MicAccess.ASKABLE -> microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            MicAccess.DENIED -> viewModel.fixSteps(listOf(Need.Microphone))
        }
    }

    private fun listenFor(purpose: MicrophoneFor) = when (purpose) {
        MicrophoneFor.LISTEN -> viewModel.listen()
        MicrophoneFor.LISTEN_FOR_LYRICS -> viewModel.listen(lyrics = true)
        MicrophoneFor.LISTEN_ALONG -> viewModel.listenAlong()
        MicrophoneFor.VISUALS -> viewModel.setVisuals(true)
        MicrophoneFor.ALLOW -> Unit
    }

    /**
     * Lyrics listen along with the song, so the first time they open, the microphone is asked for,
     * unless it's allowed already; only then, so a screen made again with them open doesn't ask over
     * a question Android is still asking.
     */
    private fun askMicrophoneOnce() {
        val prefs = getSharedPreferences(MainViewModel.PREFERENCES_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ASKED_MICROPHONE, false)) return
        prefs.edit().putBoolean(KEY_ASKED_MICROPHONE, true).apply()
        if (microphoneAccess(this) == MicAccess.ASKABLE) withMicrophone(MicrophoneFor.ALLOW)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_PENDING_CLIPBOARD_READ, pendingClipboardRead)
        outState.putString(STATE_MICROPHONE_FOR, afterMicrophone.name)
        val state = viewModel.uiState
        if (state.handlingIncomingLink) outState.putString(STATE_INCOMING_LINK, state.linkText)
    }

    private fun handleIntent(intent: Intent) {
        // Reopening from Recents replays the original link; show the app instead of opening it again.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return

        when (intent.action) {
            // The widget's songs ask to be shown here rather than opened. Back from signing in with
            // ChatGPT, it's all done by then.
            // A test link Crosstune sent itself says its links reach it, and is nothing to look up.
            Intent.ACTION_VIEW -> if (intent.data?.fragment == TEST_LINK) viewModel.linkTested(intent.data?.host) else if (intent.data?.scheme != ChatGpt.RETURN_SCHEME) viewModel.resolveIncoming(
                intent.dataString,
                show = intent.getBooleanExtra(EXTRA_SHOW_SONG, false)
            )
            ACTION_OPEN_RECENT -> viewModel.openRecent(intent.dataString)
            Intent.ACTION_SEND -> {
                // Some apps share styled text, which getStringExtra would drop.
                val shared = listOf(Intent.EXTRA_TEXT, Intent.EXTRA_SUBJECT)
                    .mapNotNull { intent.getCharSequenceExtra(it)?.toString() }
                resolveShared(shared.firstOrNull { it.isNotBlank() } ?: shared.firstOrNull() ?: return, fromClipboard = false)
            }
            Intent.ACTION_PROCESS_TEXT -> viewModel.resolveIncoming(intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString())
            ACTION_PASTE_FROM_CLIPBOARD -> pendingClipboardRead = intent.component?.className == PASTE_ALIAS
            ACTION_LISTEN -> if (intent.component?.className == LISTEN_ALIAS) {
                withMicrophone(if (intent.getBooleanExtra(EXTRA_LYRICS, false)) MicrophoneFor.LISTEN_FOR_LYRICS else MicrophoneFor.LISTEN)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        if (!pendingClipboardRead) return pasteFreshCopy()
        pendingClipboardRead = false
        resolveShared(clipboardText(), fromClipboard = true)
    }

    /**
     * A music link copied a moment ago, e.g. in another app just left, is looked up as if pasted, once.
     * Android says when a clip was copied, and from Android 12 whether it's a link, without reading
     * it, so other text isn't read, and Android's "pasted" note only shows for a link.
     */
    private fun pasteFreshCopy() {
        if (!viewModel.readyForCopy) return
        val description = getSystemService(ClipboardManager::class.java)?.primaryClipDescription ?: return
        val prefs = getSharedPreferences(MainViewModel.PREFERENCES_NAME, MODE_PRIVATE)
        val copiedAt = description.timestamp
        if (copiedAt <= prefs.getLong(KEY_CLIP_SEEN, 0L) || System.currentTimeMillis() - copiedAt !in 0..FRESH_COPY_MS) return
        prefs.edit().putLong(KEY_CLIP_SEEN, copiedAt).apply()
        if (!description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && description.classificationStatus == ClipDescription.CLASSIFICATION_COMPLETE &&
            description.getConfidenceScore(TextClassifier.TYPE_URL) == 0f
        ) return
        val text = clipboardText() ?: return
        if (MusicLinks.parse(text) != null) viewModel.pasteCopy(text)
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
        val show = action == AppShortcuts.ShareAction.SHOW
        val after = if (action == AppShortcuts.ShareAction.SHARE) AfterLookup.SHARE else AfterLookup.OPEN
        if (fromClipboard) viewModel.resolveClipboard(text, chosen, show, after) else viewModel.resolveIncoming(text, chosen, show, after)
    }

    /** Android only lets the focused app read the clipboard, which it is here: after focus or a tap. */
    private fun clipboardText(): String? {
        val clip = getSystemService(ClipboardManager::class.java)?.primaryClip
        return clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
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

    private fun open(effect: Effect.Open) {
        val uri = effect.url.toUri()
        // A service's own app stands in for the builds patched from it and its TV app, whichever is installed; a build chosen by name is only itself.
        val apps = effect.packageName?.let { MusicService.appLinksIn(it, effect.url) }.orEmpty()
        val opened = apps.any { (app, link) -> tryStartActivity(Intent(Intent.ACTION_VIEW, link.toUri()).setPackage(app)) } ||
            openOutsideCrosstune(uri)
        when {
            !opened -> viewModel.showError(AppError.NO_APP_TO_OPEN)
            effect.finishAfterOpen -> finish()
        }
    }

    private fun show(effect: Effect.Show) {
        val launch = packageManager.getLaunchIntentForPackage(effect.packageName)
        when {
            launch == null || !tryStartActivity(launch) -> if (effect.saysWhenItFails) viewModel.showError(AppError.NO_APP_TO_OPEN)
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
        val others = packageManager.queryIntentActivities(intent, 0).filter { it.activityInfo.packageName != packageName }
        if (others.isEmpty()) return false
        val defaultPackage = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
        // Each app is named outright: resolving the link itself, Android keeps to the apps allowed
        // its site, Crosstune among them, and leaves browsers out, so a chooser could come up empty.
        val targets = others.map { Intent(intent).setClassName(it.activityInfo.packageName, it.activityInfo.name) }
        return when {
            defaultPackage != null && others.any { it.activityInfo.packageName == defaultPackage } -> tryStartActivity(intent.setPackage(defaultPackage))
            targets.size == 1 -> tryStartActivity(targets[0])
            else -> tryStartActivity(
                Intent.createChooser(targets[0], null).putExtra(Intent.EXTRA_INITIAL_INTENTS, targets.drop(1).toTypedArray())
            )
        }
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
        // Copied here, it's not pasted back on coming back.
        getSharedPreferences(MainViewModel.PREFERENCES_NAME, MODE_PRIVATE).edit().putLong(KEY_CLIP_SEEN, System.currentTimeMillis()).apply()
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
}
