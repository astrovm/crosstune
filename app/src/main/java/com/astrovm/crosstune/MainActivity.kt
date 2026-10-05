package com.astrovm.crosstune

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutManager
import androidx.glance.appwidget.updateAll
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
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

    /** The app picked in Settings for the Recognize button; Shazam, then Google, when none is. */
    private var recognizerPick by mutableStateOf<String?>(null)

    private val viewModel: MainViewModel by viewModels {
        viewModelFactory {
            initializer {
                val client = httpClientFactory()
                MainViewModel(
                    LinkResolver(client),
                    // What was found for songs is kept, so a playlist played again needs no lookups.
                    ExactMatcher(client, cache = LookupCache(File(cacheDir, "lookups.json"), lookupDispatcher)),
                    getSharedPreferences(MainViewModel.PREFERENCES_NAME, MODE_PRIVATE),
                    LinkInterception(applicationContext),
                    ArtworkLoader(client, cacheDir = File(cacheDir, "artwork")),
                    systemDispatcher
                )
            }
        }
    }

    /** Set when launched to read the clipboard, which Android only allows once the window has focus. */
    private var pendingClipboardRead = false

    companion object {
        const val ACTION_PASTE_FROM_CLIPBOARD = "com.astrovm.crosstune.action.PASTE_FROM_CLIPBOARD"

        /** The unexported alias the tile and launcher shortcut use, so only they can trigger a clipboard read. */
        const val PASTE_ALIAS = "com.astrovm.crosstune.PasteFromClipboard"
        /** The widget's ▶: opens a Recent song in the user's app, like Recent's own ▶. */
        const val ACTION_OPEN_RECENT = "com.astrovm.crosstune.action.OPEN_RECENT"
        /** On a link, shows it here first, as the widget's songs do when tapped. */
        const val EXTRA_SHOW_SONG = "com.astrovm.crosstune.extra.SHOW_SONG"


        private const val STATE_PENDING_CLIPBOARD_READ = "pending_clipboard_read"
        private const val STATE_INCOMING_LINK = "incoming_link"

        @VisibleForTesting
        internal var httpClientFactory: () -> OkHttpClient = ::httpClient

        /** Where Android is asked about apps and links; tests run it in step with the screen. */
        @VisibleForTesting
        internal var systemDispatcher: CoroutineDispatcher = Dispatchers.Default

        /** Where what's found for songs is read and written; tests do it in step with the screen. */
        @VisibleForTesting
        internal var lookupDispatcher: CoroutineDispatcher = Dispatchers.IO
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
            CrosstuneTheme {
                CrosstuneScreen(
                    state = viewModel.uiState,
                    actions = ScreenActions(
                        onUrlChange = viewModel::onUrlChange,
                        onResolve = viewModel::resolveTypedInput,
                        onPaste = { viewModel.pasteLink(clipboardText()) },
                        recognizers = recognizers,
                        recognizer = recognizers.firstOrNull { it.packageName == recognizerPick } ?: recognizers.firstOrNull(),
                        onRecognize = { tryStartActivity(it.intent) },
                        onRecognizerChange = { picked ->
                            recognizerPick = picked.packageName
                            getSharedPreferences(MainViewModel.PREFERENCES_NAME, MODE_PRIVATE).edit().putString(SongRecognizers.KEY_PICK, picked.packageName).apply()
                            // The widget's button opens the same app.
                            lifecycleScope.launch { CrosstuneWidget().updateAll(applicationContext) }
                        },
                        onClear = viewModel::clear,
                        onRetry = viewModel::retry,
                        onOpen = { viewModel.openResult() },
                        onOpenWith = { destination -> viewModel.openResult(destination, finishAfterOpen = true) },
                        onOpenOriginal = viewModel::openOriginal,
                        onDismissPicker = viewModel::dismissDestinationPicker,
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
        recognizers = SongRecognizers.available(packageManager)
        recognizerPick = getSharedPreferences(MainViewModel.PREFERENCES_NAME, MODE_PRIVATE).getString(SongRecognizers.KEY_PICK, null)
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
