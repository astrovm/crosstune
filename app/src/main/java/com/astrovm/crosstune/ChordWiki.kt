package com.astrovm.crosstune

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Reads one ChordWiki page. The phone's browser does, since the site won't answer a plain request. */
internal fun interface ChordWikiPages {
    /** The page at [url], once it has loaded: its ChordPro text, or the search hits when it has no editor. */
    suspend fun page(url: String): String
}

/** One song a ChordWiki search lists: its title, its singer, and the page that names them. */
internal data class ChordWikiHit(val title: String, val artist: String, val href: String)

/** What one look at a ChordWiki page showed, from the text a person would see. */
internal data class ChordWikiSeen(
    val href: String,
    val title: String,
    val text: String,
    val source: String,
    val hasEditor: Boolean,
    val links: String
)

/** Whether the page is the sheet, or Cloudflare still checking, or a check a person has to click. */
internal enum class ChordWikiCheck { NONE, AUTOMATIC, INTERACTIVE }

/** A page that's ready to read, or one that isn't yet. */
internal sealed interface ChordWikiLook {
    /** [body] is the editor's ChordPro, or the search hits when the page has no editor. */
    data class Ready(val body: String) : ChordWikiLook
    data object Waiting : ChordWikiLook
}

/**
 * ChordWiki in the phone's own web view, behind the screen and see-through, so Cloudflare's check
 * runs in a real browser. Its own user agent is left as it is. One page at a time, and the cookie
 * it earns stays for the next song.
 */
internal class ChordWikiBrowser(
    private val host: Activity,
    private val now: () -> Long = { SystemClock.uptimeMillis() },
    private val gapMs: Long = GAP_MS,
    private val waitMs: Long = WAIT_MS,
    private val screen: () -> ViewGroup? = { host.window?.decorView as? ViewGroup }
) : ChordWikiPages {
    override suspend fun page(url: String): String = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { cont -> open(url, cont) }
    }

    private fun open(url: String, cont: CancellableContinuation<String>) {
        val root = screen()
        if (root == null) {
            cont.resumeWithException(IOException("ChordWiki has nowhere to show"))
            return
        }
        val web = WebView(host)
        val settings = web.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // Pictures stay. What a lookup costs is measured with them on; nothing here pretends to be another browser.
        settings.loadsImagesAutomatically = true
        web.alpha = 0f
        web.isClickable = false
        web.isFocusable = false
        web.isFocusableInTouchMode = false
        web.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        CookieManager.getInstance().setAcceptCookie(true)
        // Behind everything else, so it can't take a tap meant for the lyrics.
        root.addView(web, 0, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val started = now()
        var settled = false
        fun finish(result: Result<String>) {
            if (settled) return
            settled = true
            detach(web)
            CookieManager.getInstance().flush()
            if (!cont.isActive) return
            result.fold(cont::resume, cont::resumeWithException)
        }
        cont.invokeOnCancellation {
            settled = true
            detach(web)
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, loaded: String) {
                ask(view, started, ::finish)
            }
        }
        web.loadUrl(url)
        web.postDelayed({ if (!settled) ask(web, started, ::finish) }, gapMs)
    }

    private fun ask(web: WebView, started: Long, finish: (Result<String>) -> Unit) {
        if (web.parent == null) return
        web.evaluateJavascript(readPage()) { value -> read(web, started, value, finish) }
    }

    private fun read(web: WebView, started: Long, value: String?, finish: (Result<String>) -> Unit) {
        if (web.parent == null) return
        val seen = jsResult(value)?.let {
            try {
                chordWikiSeen(it)
            } catch (e: JSONException) {
                null
            }
        }
        val timedOut = now() - started >= waitMs
        if (seen == null) {
            again(web, started, timedOut, finish)
            return
        }
        try {
            when (val look = chordWikiLook(seen, timedOut)) {
                is ChordWikiLook.Ready -> finish(Result.success(look.body))
                ChordWikiLook.Waiting -> again(web, started, timedOut, finish)
            }
        } catch (e: IOException) {
            finish(Result.failure(e))
        }
    }

    private fun again(web: WebView, started: Long, timedOut: Boolean, finish: (Result<String>) -> Unit) {
        if (web.parent == null) return
        if (timedOut) {
            finish(Result.failure(IOException("ChordWiki didn't finish loading")))
            return
        }
        web.postDelayed({ ask(web, started, finish) }, gapMs)
    }

    private fun detach(web: WebView) {
        val parent = web.parent as? ViewGroup ?: return
        parent.removeView(web)
        web.stopLoading()
        web.destroy()
    }

    private companion object {
        const val GAP_MS = 750L
        const val WAIT_MS = 18_000L

        /** The editor's text, the links to sheets, and the words on screen. Not the page's scripts. */
        fun readPage(): String = """
            (function(){
              var areas=document.getElementsByTagName('textarea');
              var source='';
              for(var i=0;i<areas.length;i++){
                var v=areas[i].value||areas[i].textContent||'';
                if(v.length>source.length) source=v;
              }
              var links=[];
              var as=document.getElementsByTagName('a');
              for(var j=0;j<as.length;j++){
                var href=as[j].href||'';
                if(href.indexOf('/wiki/')<0 && href.indexOf('wiki.cgi')<0) continue;
                links.push((as[j].innerText||'').replace(/\s+/g,' ').trim()+'\t'+href);
              }
              var body=document.body;
              return JSON.stringify({
                href:location.href,
                title:document.title||'',
                text:body?(body.innerText||''):'',
                source:source,
                hasEditor:areas.length>0,
                links:links.join('\n')
              });
            })()
        """.trimIndent()
    }
}

/** The edit page for [title], whose editor holds the sheet as ChordPro. */
internal fun chordWikiEditUrl(title: String): String = "https://ja.chordwiki.org/wiki.cgi?c=edit&t=${chordWikiEncode(title)}"

/** A search for [query]. */
internal fun chordWikiSearchUrl(query: String): String = "https://ja.chordwiki.org/search.html?q=${chordWikiEncode(query)}"

/** The sheet's own page, the one to show for where the chords came from. */
internal fun chordWikiViewUrl(title: String): String = "https://ja.chordwiki.org/wiki/${chordWikiEncode(title)}"

private fun chordWikiEncode(text: String): String = URLEncoder.encode(text, Charsets.UTF_8.name())

/** What evaluateJavascript hands back, which is a JSON string, or null when the page isn't there yet. */
internal fun jsResult(value: String?): String? {
    if (value == null || value == "null") return null
    return try {
        JSONArray("[$value]").getString(0)
    } catch (e: JSONException) {
        null
    }
}

/** [json] as one look at the page. */
internal fun chordWikiSeen(json: String): ChordWikiSeen {
    val page = JSONObject(json)
    return ChordWikiSeen(
        href = page.optString("href"),
        title = page.optString("title"),
        text = page.optString("text"),
        source = page.optString("source"),
        hasEditor = page.optBoolean("hasEditor"),
        links = page.optString("links")
    )
}

/**
 * Whether [title] and [text], the words on screen, are Cloudflare still checking or a check a person
 * has to click. A sheet's own words are neither.
 */
internal fun chordWikiCheck(title: String, text: String): ChordWikiCheck {
    val visible = "${title.lowercase()}\n${text.lowercase()}"
    if (INTERACTIVE.any { it in visible }) return ChordWikiCheck.INTERACTIVE
    if (AUTOMATIC.any { it in visible }) return ChordWikiCheck.AUTOMATIC
    return ChordWikiCheck.NONE
}

/**
 * What to do with [seen]. An editor is the sheet, even an empty one. A check keeps being waited on
 * until [timedOut], and one a person has to click is then given up, not answered for them.
 */
internal fun chordWikiLook(seen: ChordWikiSeen, timedOut: Boolean): ChordWikiLook {
    if (seen.href.isBlank() || seen.href.startsWith("about:")) {
        if (timedOut) throw IOException("ChordWiki didn't load")
        return ChordWikiLook.Waiting
    }
    if (seen.hasEditor) return ChordWikiLook.Ready(seen.source)
    val check = chordWikiCheck(seen.title, seen.text)
    if (check != ChordWikiCheck.NONE) {
        if (!timedOut) return ChordWikiLook.Waiting
        val why = if (check == ChordWikiCheck.INTERACTIVE) "ChordWiki asked for a check a person has to click" else "ChordWiki's check didn't finish"
        throw IOException(why)
    }
    if (seen.links.isNotEmpty() || seen.title.length + seen.text.length > 200) return ChordWikiLook.Ready(seen.links)
    if (timedOut) throw IOException("ChordWiki's page was empty")
    return ChordWikiLook.Waiting
}

/** The songs [page] lists, as tab-separated lines from the browser or as the links in the page. */
internal fun chordWikiHits(page: String): List<ChordWikiHit> {
    val lined = page.lineSequence().mapNotNull(::chordWikiHitLine).toList()
    if (lined.isNotEmpty()) return lined
    return ANCHOR.findAll(page).mapNotNull { match ->
        val text = ChordFinder.unescape(match.groupValues[2].replace(ANCHOR_TAGS, "")).trim()
        chordWikiHit(text, ChordFinder.unescape(match.groupValues[1]))
    }.toList()
}

/**
 * Of [hits], the one whose title is [title] or [also]. The singer's own comes before a sheet with no
 * credit, and that before someone else's.
 */
internal fun chordWikiBest(hits: List<ChordWikiHit>, title: String, artist: String, also: String): ChordWikiHit? =
    hits.filter { SongNames.same(it.title, title) || SongNames.same(it.title, also) }.minByOrNull { hit ->
        when {
            SongNames.sameArtist(hit.artist, artist) -> 0
            hit.artist.isBlank() -> 1
            else -> 2
        }
    }

private fun chordWikiHitLine(line: String): ChordWikiHit? {
    val tab = line.indexOf('\t')
    if (tab <= 0) return null
    return chordWikiHit(line.substring(0, tab), line.substring(tab + 1))
}

private fun chordWikiHit(text: String, href: String): ChordWikiHit? {
    val link = href.trim()
    if ("/wiki/" !in link && "wiki.cgi" !in link) return null
    val title = text.substringBefore(" - ").trim()
    if (title.isEmpty()) return null
    return ChordWikiHit(title, text.substringAfter(" - ", "").trim(), link)
}

private val ANCHOR = Regex("""<a\b[^>]*\bhref="([^"]*)"[^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val ANCHOR_TAGS = Regex("<[^>]+>")

private val INTERACTIVE = listOf(
    "verify you are human",
    "i'm not a robot",
    "i’m not a robot",
    "cf-turnstile",
    "hcaptcha",
    "私はロボットではありません",
    "人間であることを確認"
)

private val AUTOMATIC = listOf(
    "just a moment",
    "checking your browser",
    "enable javascript",
    "cf-challenge",
    "しばらくお待ちください"
)
