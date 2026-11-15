package com.astrovm.crosstune

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/** How a line the [LinePlayer] was asked to play went on. */
internal enum class LineEnd {
    /** It played to its end and paused there. */
    ENDED,

    /** It can't be played: the video won't load, or isn't as long as the song, so isn't the song. */
    FAILED
}

/**
 * A small YouTube player that plays a card's line when the music app can't jump to it, as Spotify's
 * free plan can't. Its [view] sits on the card, as YouTube asks of a player that's heard.
 */
internal interface LinePlayer {
    /** The player's picture, made once, which the card shows while its sound goes through here. */
    fun view(): View

    /**
     * Has YouTube's [videoId] ready at [startMs], and playing to [endMs], where it pauses, when [play];
     * [done] hears how it went. Asked again for the same video, it only goes back to the start. A video
     * not as long as [expectedMs], the song's own length when known, is another song, so it fails.
     */
    fun show(videoId: String, startMs: Long, endMs: Long, expectedMs: Long?, play: Boolean, done: (LineEnd) -> Unit)

    /** Pauses it, wherever it is. */
    fun pause()

    /** Stops the player and releases its view when practice is no longer used. */
    fun release()
}

/** The page the player is in: YouTube's player, which says through `Line` when the line ended or can't be played. */
internal object LinePlayerPage {
    /** How far from the song's length a video's may be to be the song's: a second or so, which no other song of it shares. */
    const val SAME_LENGTH_MS = 1_500L

    /** How often the page looks at where the video is, so a line is stopped within a fraction of a second of its end. */
    const val POLL_MS = 100

    /** The neutral address the page is loaded as: YouTube's own refuses to play inside an app. */
    const val BASE_URL = "https://crosstune.cc"

    /**
     * What a page loaded in the app starts with: the line asked for last, which it asks the app for. A card turned
     * while the page was still loading asked the page that wasn't there yet, and the line it was loaded with never played.
     */
    const val LATEST = "eval(Line.latest(videoId));"

    /** The page's call to play the line from [startMs] to [endMs], or only get ready when not [play]; [expectedMs], the song's length, when known. */
    fun call(startMs: Long, endMs: Long, expectedMs: Long?, play: Boolean, request: Long = 0) = "line(${startMs / 1000.0}, ${endMs / 1000.0}, ${expectedMs ?: 0}, $play, $request);"

    /** The page with its player on [videoId], and [first], the call that says which line to play, made as soon as it can be. */
    fun html(videoId: String, first: String): String = """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>html,body,#p{margin:0;width:100%;height:100%;background:#000;overflow:hidden}</style></head><body>
        <div id="p"></div>
        <script>
        var videoId = '${videoId.filter { it.isLetterOrDigit() || it == '_' || it == '-' }}';
        var player, ready = false, want = null, timer = null, checked = false;
        function onYouTubeIframeAPIReady() {
          player = new YT.Player('p', {
            videoId: videoId, width: '100%', height: '100%',
            playerVars: {controls: 0, playsinline: 1, rel: 0, fs: 0, disablekb: 1, iv_load_policy: 3, autoplay: 0},
            events: {
              onReady: function() { ready = true; if (want) go(); },
              // A card turned as its line was still getting ready: the player drops a play asked for then, so it's asked again once ready.
              onStateChange: function(e) { if (e.data == YT.PlayerState.CUED && want && want.play) go(); },
              onError: failed
            }
          });
        }
        // Where the line is, and whether to play it, as the app says; the song's length, 0 when it isn't known.
        function line(start, end, expected, play, request) {
          want = {start: start, end: end, expected: expected, play: play, request: request};
          checked = expected == 0;
          if (ready) go();
        }
        function go() {
          clearInterval(timer);
          if (want.play) {
            if (!checked) player.mute();
            player.seekTo(want.start, true);
            player.playVideo();
            timer = setInterval(watch, $POLL_MS);
          } else {
            player.cueVideoById({videoId: player.getVideoData().video_id, startSeconds: want.start});
          }
        }
        function watch() {
          if (!checked && player.getDuration() > 0) {
            if (Math.abs(player.getDuration() * 1000 - want.expected) > $SAME_LENGTH_MS) {
              return failed();
            }
            checked = true;
            player.unMute();
          }
          if (player.getCurrentTime() >= want.end) {
            var request = want.request; pause(); Line.ended(request);
          }
        }
        function pause() { clearInterval(timer); if (want) want.play = false; if (ready) player.pauseVideo(); }
        function failed() { var request = want && want.request; pause(); if (request != null) Line.failed(request); }
        $first
        </script>
        <script src="https://www.youtube.com/iframe_api" onerror="failed()"></script></body></html>
    """.trimIndent()
}

/** [LinePlayer] in a WebView, which plays YouTube's own player with no account and no sign-in. */
@SuppressLint("SetJavaScriptEnabled")
internal class YouTubeLinePlayer(context: Context) : LinePlayer {
    private val main = Handler(Looper.getMainLooper())
    private val web = WebView(context)
    @Volatile private var loaded: String? = null
    @Volatile private var released = false
    private var serial = 0L
    private var page = 0L
    private var current: Request? = null
    private var timeout: Runnable? = null

    private class Request(val id: Long, val done: (LineEnd) -> Unit)

    /** The page asks for the latest command, including a pause made before it was ready. */
    @Volatile
    private var latest = "pause();"

    init {
        web.settings.javaScriptEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.setBackgroundColor(android.graphics.Color.BLACK)
        web.addJavascriptInterface(
            object {
                @JavascriptInterface
                fun latest(videoId: String) = if (!released && loaded == videoId) latest else "pause();"

                @JavascriptInterface
                fun ended(request: Long) { main.post { complete(request, LineEnd.ENDED) } }

                @JavascriptInterface
                fun failed(request: Long) { main.post { complete(request, LineEnd.FAILED) } }
            },
            "Line"
        )
    }

    override fun view(): View = web

    override fun show(videoId: String, startMs: Long, endMs: Long, expectedMs: Long?, play: Boolean, done: (LineEnd) -> Unit) {
        if (released) return done(LineEnd.FAILED)
        cancelTimeout()
        val request = Request(++serial, done)
        current = request
        val call = LinePlayerPage.call(startMs, endMs, expectedMs, play, request.id)
        latest = call
        // A missing script or a stalled player cannot leave a card playing forever.
        if (play) {
            val expires = Runnable { complete(request.id, LineEnd.FAILED) }
            timeout = expires
            main.postDelayed(expires, (endMs - startMs).coerceAtLeast(0) + 5_000)
        }
        if (loaded == videoId) return web.evaluateJavascript("if (typeof line == 'function') $call", null)
        loaded = videoId
        val askedPage = ++page
        web.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && askedPage == page) current?.let { complete(it.id, LineEnd.FAILED) }
            }
        }
        web.loadDataWithBaseURL(LinePlayerPage.BASE_URL, LinePlayerPage.html(videoId, LinePlayerPage.LATEST), "text/html", "utf-8", null)
    }

    private fun complete(id: Long, end: LineEnd) {
        val request = current?.takeIf { it.id == id } ?: return
        pause()
        request.done(end)
    }

    private fun cancelTimeout() {
        timeout?.let(main::removeCallbacks)
        timeout = null
    }

    override fun pause() {
        if (released) return
        current = null
        cancelTimeout()
        latest = "pause();"
        web.evaluateJavascript("if (typeof pause == 'function') pause()", null)
    }

    override fun release() {
        if (released) return
        pause()
        released = true
        (web.parent as? android.view.ViewGroup)?.removeView(web)
        web.stopLoading()
        web.removeJavascriptInterface("Line")
        web.destroy()
    }
}
