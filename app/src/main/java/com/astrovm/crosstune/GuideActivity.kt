package com.astrovm.crosstune

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the floating guide shows and does, all from the Crosstune screen that opened it. */
internal interface GuideHost {
    val state: UiState
    val dark: Boolean

    /** Reads again what Android says, as Crosstune does when it comes back. */
    fun refresh()

    /** Opens Android's page the step is done on, from the [guide], or from Crosstune itself without one. */
    fun open(guide: Context?)

    /** Brings Crosstune back, the step done. */
    fun back(guide: Context)
}

/**
 * The step being taken, small, floating over Android's settings in picture-in-picture while the
 * user is there, in a task of its own, so closing it leaves Crosstune as it was. Crosstune comes
 * back once Android says it's done, and the guide closes as it does. Where Android won't float it,
 * Android's page just opens, and the steps wait in Crosstune.
 */
class GuideActivity : ComponentActivity() {
    /** Set once Android's page is open under the guide, so Crosstune coming back closes it. */
    private var opened = false
    private var floated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Made again with nothing to show, e.g. after Android ended Crosstune, it just goes.
        val host = host.takeIf { savedInstanceState == null } ?: return finish()
        // The one it was opened for, though Crosstune moves on to the next once it's done.
        val need = host.state.fixes?.need ?: return finish()
        shown = this
        setContent {
            val state = host.state
            CrosstuneTheme(darkTheme = host.dark, palette = state.palette, pureBlack = state.pureBlack) { Shrunk { FloatingGuide(need, state) } }
        }
        lifecycleScope.launch {
            // Android doesn't say when it's done, so it's asked every second.
            while (host.state.done(need) != true) {
                delay(CHECK_MS)
                host.refresh()
            }
            // Crosstune coming back closes the guide. Where Android won't let it, the guide says
            // done, and stays till the user goes back.
            host.back(this@GuideActivity)
        }
    }

    override fun onResume() {
        super.onResume()
        if (floated) return
        floated = true
        val floating = try {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(3, 4)).build())
        } catch (_: IllegalStateException) {
            false
        }
        if (floating) return
        // Picture-in-picture is turned off for Crosstune, or the phone has none.
        host?.open(null)
        finish()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode && !opened) {
            opened = true
            host?.open(this)
        } else if (!isInPictureInPictureMode) {
            // Made big again, it's Crosstune the user's after.
            back()
        }
    }

    /** Android's pages open in their own task, not in the guide's small window. */
    override fun startActivity(intent: Intent) = super.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    /** Closed by the user, it's done with. */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        if (shown === this) shown = null
        super.onDestroy()
    }

    private fun back() {
        if (isFinishing) return
        host?.back(this)
        finish()
    }

    companion object {
        /** What the guide shows, from the screen that opened it. */
        internal var host: GuideHost? = null
        private var shown: GuideActivity? = null
        private const val CHECK_MS = 1_000L

        /** How wide the guide is laid out, before it's shrunk to its window. */
        private val WIDTH = 260.dp

        /** Crosstune is back in sight, so the guide is done with, once it got the user to Android's page. */
        fun close() {
            shown?.takeIf { it.opened }?.finish()
        }

        /** Laid out as on a phone and shrunk to the window, which is too small to lay it out in. */
        @Composable
        private fun Shrunk(content: @Composable () -> Unit) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density * (maxWidth / WIDTH), density.fontScale)) { content() }
            }
        }
    }
}
