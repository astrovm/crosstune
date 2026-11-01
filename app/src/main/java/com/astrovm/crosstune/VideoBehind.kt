package com.astrovm.crosstune

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * Prototype for #463, dev builds only: the song's YouTube video behind its words, muted under the
 * music app, kept where the app's clock says the song is. Logs what it measures under "VideoBehind".
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun VideoBehind(videoId: String, following: Following?, shade: Float, modifier: Modifier = Modifier) {
    val clock by rememberUpdatedState(following?.clock)
    var view by remember { mutableStateOf<WebView?>(null) }
    Box(modifier) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    setBackgroundColor(Color.BLACK)
                    addJavascriptInterface(VideoBridge(videoId), "Crosstune")
                    loadDataWithBaseURL(ORIGIN, page(videoId), "text/html", "utf-8", null)
                    view = this
                }
            },
            onRelease = { it.destroy() },
            modifier = Modifier.fillMaxSize()
        )
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = shade)))
    }
    val web = view ?: return
    LaunchedEffect(web, videoId) {
        val startBytes = TrafficStats.getUidRxBytes(Process.myUid())
        val start = SystemClock.elapsedRealtime()
        var seeks = 0
        while (true) {
            delay(SYNC_EVERY_MS)
            val at = clock ?: continue
            val videoS = web.js("p && p.getCurrentTime ? p.getCurrentTime() : -1")?.toDoubleOrNull() ?: continue
            val state = web.js("p && p.getPlayerState ? p.getPlayerState() : -9")
            val songMs = at.positionAt(SystemClock.elapsedRealtime())
            val driftMs = (videoS * 1000).toLong() - songMs
            val mb = (TrafficStats.getUidRxBytes(Process.myUid()) - startBytes) / 1e6
            val seconds = (SystemClock.elapsedRealtime() - start) / 1000
            Log.i(TAG, "video=$videoId t=${seconds}s state=$state song=${songMs}ms video=${(videoS * 1000).toLong()}ms drift=${driftMs}ms seeks=$seeks rx=${"%.1f".format(mb)}MB")
            if (at.playing && videoS >= 0 && abs(driftMs) > MAX_DRIFT_MS) {
                seeks++
                web.evaluateJavascript("p.seekTo(${songMs / 1000.0}, true); p.playVideo()", null)
            }
            if (!at.playing && state == "1") web.evaluateJavascript("p.pauseVideo()", null)
            if (at.playing && state == "2") web.evaluateJavascript("p.playVideo()", null)
        }
    }
}

private class VideoBridge(private val videoId: String) {
    @JavascriptInterface
    fun state(what: String) {
        Log.i(TAG, "video=$videoId $what")
    }
}

private suspend fun WebView.js(script: String): String? = suspendCancellableCoroutine { done ->
    evaluateJavascript(script) { result -> done.resume(result?.trim('"')?.takeUnless { it == "null" }) }
}

/** YouTube's embedded player, filling the screen, cut to its height, with nothing of its own on top. */
private fun page(videoId: String) = """
<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}
#p{position:absolute;top:0;left:50%;pointer-events:none}</style></head>
<body><div id="p"></div>
<script>
var p;
// YouTube picks the quality by the player's size on screen, which full-screen is 4K, about 3 MB a
// second. Behind the words 360p is plenty, so the player is that small and drawn bigger.
// (vh units come out as 0 in this WebView, so it's sized in pixels.)
function size(){ var f = document.getElementById('p'); var h = Math.round(360 / devicePixelRatio);
  f.style.height = h + 'px'; f.style.width = Math.round(h * 16 / 9) + 'px';
  f.style.transformOrigin = 'top center'; f.style.transform = 'translateX(-50%) scale(' + (window.innerHeight / h) + ')'; }
window.addEventListener('resize', size);
function onYouTubeIframeAPIReady(){
  p = new YT.Player('p', {videoId: '$videoId', host: 'https://www.youtube-nocookie.com',
    playerVars: {autoplay: 1, mute: 1, controls: 0, playsinline: 1, rel: 0, iv_load_policy: 3, disablekb: 1, fs: 0, origin: '$ORIGIN'},
    events: {
      onReady: function(e){ size(); e.target.mute(); e.target.playVideo(); Crosstune.state('ready ' + e.target.getDuration() + ' s');
        setInterval(function(){ Crosstune.state('quality ' + p.getPlaybackQuality()); }, 15000); },
      onStateChange: function(e){ Crosstune.state('state ' + e.data); },
      onError: function(e){ Crosstune.state('error ' + e.data); }
    }});
  // Sized before it loads, so it starts at 360p too.
  size();
}
</script>
<script src="https://www.youtube.com/iframe_api"></script>
</body></html>
"""

private const val TAG = "VideoBehind"
private const val ORIGIN = "https://crosstune.astrovm.com"
private const val SYNC_EVERY_MS = 1_000L
private const val MAX_DRIFT_MS = 400L
