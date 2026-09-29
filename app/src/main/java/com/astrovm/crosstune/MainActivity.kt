package com.astrovm.crosstune

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.VisibleForTesting
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        viewModelFactory {
            initializer {
                MainViewModel(
                    SpotifyResolver(httpClientFactory()),
                    getSharedPreferences(MainViewModel.PREFERENCES_NAME, MODE_PRIVATE)
                )
            }
        }
    }

    companion object {
        @VisibleForTesting
        internal var httpClientFactory: () -> OkHttpClient = { OkHttpClient() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // After a configuration change the ViewModel already holds this intent's result or request.
        if (savedInstanceState == null) handleIntent(intent)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.effects.collect { effect ->
                    when (effect) {
                        is Effect.OpenSearch -> openSearch(effect)
                    }
                }
            }
        }

        setContent {
            CrosstuneTheme {
                CrosstuneScreen(
                    state = viewModel.uiState,
                    onUrlChange = viewModel::onUrlChange,
                    onResolveClick = viewModel::resolveTypedInput,
                    onClearClick = viewModel::clear,
                    onOpenClick = { viewModel.openResult() },
                    onTargetChange = viewModel::selectTarget,
                    onCopySearchClick = ::copySearch,
                    onShareSearchClick = ::shareSearch,
                    onOpenLinkSettingsClick = ::openAppLinkSettings,
                    onDismissLinkSettingsHelper = viewModel::dismissLinkSettingsHelper
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        // Reopening from Recents replays the original link; show the app instead of opening it again.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return

        when (intent.action) {
            Intent.ACTION_VIEW -> viewModel.resolveIncoming(intent.dataString)
            Intent.ACTION_SEND -> viewModel.resolveIncoming(
                intent.getStringExtra(Intent.EXTRA_TEXT)
                    ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
                    ?: return
            )
        }
    }

    private fun openSearch(effect: Effect.OpenSearch) {
        val uri = effect.url.toUri()
        val opened = tryStartActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(effect.packageName)) ||
            tryStartActivity(Intent(Intent.ACTION_VIEW, uri))
        when {
            !opened -> viewModel.showError(AppError.NO_APP_TO_OPEN)
            effect.finishAfterOpen -> finish()
        }
    }

    private fun tryStartActivity(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    private fun copySearch() {
        val query = viewModel.searchQuery() ?: return
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Crosstune search query", query))
        // Android 13+ shows its own confirmation whenever the clipboard changes.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, getString(R.string.search_copied_to_clipboard), Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareSearch() {
        val url = viewModel.searchUrl() ?: return
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_search_link)))
    }

    private fun openAppLinkSettings() {
        val packageUri = "package:$packageName".toUri()
        val openedDefaults = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            tryStartActivity(Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, packageUri))
        if (!openedDefaults) {
            tryStartActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
        }
        viewModel.dismissLinkSettingsHelper()
    }
}
