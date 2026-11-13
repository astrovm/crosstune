package com.astrovm.crosstune

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayInputStream
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
internal data class ChordWikiHit(val title: String, val artist: String, val href: String) {
    /**
     * The page's own name, as its address spells it. The link's text can be cut short with "..." or
     * lead with the credits, so the address is the one thing that always names the page.
     */
    val page: String get() = chordWikiPageName(href) ?: title
}

private fun chordWikiPageName(href: String): String? {
    val raw = if ("/wiki/" in href) {
        href.substringAfter("/wiki/").substringBefore('?').substringBefore('#')
    } else {
        href.substringAfter("t=", "").substringBefore('&').substringBefore('#')
    }
    return try {
        java.net.URLDecoder.decode(raw, "UTF-8").takeIf { it.isNotBlank() }
    } catch (e: IllegalArgumentException) {
        null
    }
}

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
    private val screen: () -> ViewGroup? = { host.window?.decorView as? ViewGroup },
    /** Pictures and fonts. Off only while a lookup is measured without them. */
    private val loadPictures: Boolean = true,
    /** Each address the page asks for, so a measurement can see images and fonts. */
    private val onRequest: ((String) -> Unit)? = null,
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
        // Pictures stay for a real lookup. A measurement can turn them off and see whether the editor still arrives.
        settings.loadsImagesAutomatically = loadPictures
        settings.blockNetworkImage = !loadPictures
        web.alpha = 0f
        web.isClickable = false
        // Not focusable at all, which takes touch mode with it: a view can't be focusable in touch mode and not otherwise.
        web.isFocusable = false
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
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                onRequest?.invoke(request.url.toString())
                if (loadPictures || !skipsPicture(request)) return null
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }

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

    /** An image or a font. The editor's text doesn't need either, and a measurement can leave them out. */
    private fun skipsPicture(request: WebResourceRequest): Boolean {
        val url = request.url.toString().substringBefore('?').lowercase()
        val accept = request.requestHeaders["Accept"].orEmpty().lowercase()
        return pictureFile(url) || accept.startsWith("image/") || "font/" in accept
    }

    private fun pictureFile(url: String): Boolean =
        listOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico", ".avif", ".bmp", ".woff", ".woff2", ".ttf", ".otf", ".eot").any(url::endsWith)

    /** The view belongs to the main thread. Cancelling a lookup runs on whatever thread the other sites finished on. */
    private fun detach(web: WebView) {
        if (Looper.myLooper() == Looper.getMainLooper()) remove(web) else web.post { remove(web) }
    }

    private fun remove(web: WebView) {
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
        throw ChordWikiStopped(check)
    }
    // The menu's links are there before the search has answered, and its words are too. Only a song's
    // link, or the search saying nothing matched, is the answer.
    if (chordWikiHits(seen.links).isNotEmpty() || NO_MATCH in seen.text) return ChordWikiLook.Ready(seen.links)
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
 * Of [hits], the one whose title is [title], [also], or one of [more]. The singer's own comes before
 * a sheet with no credit, and that before someone else's.
 */
internal fun chordWikiBest(
    hits: List<ChordWikiHit>,
    title: String,
    artist: String,
    also: String,
    more: List<String> = emptyList(),
): ChordWikiHit? =
    hits.filter { hit -> chordWikiNames(hit.title, title, also, more) || chordWikiNames(hit.page, title, also, more) }.minByOrNull { hit ->
        when {
            SongNames.sameArtist(hit.artist, artist) -> 0
            hit.artist.isBlank() -> 1
            else -> 2
        }
    }

private fun chordWikiNames(title: String, one: String, other: String, more: List<String>): Boolean =
    chordWikiTitles(title).any { name -> SongNames.same(name, one) || SongNames.same(name, other) || more.any { SongNames.same(name, it) } }

/** A page's name as listed, and without the credit ChordWiki hangs on it, as in "夜に駆ける （歌：YOASOBI 作詞・作曲・編曲：Ayase）". */
internal fun chordWikiTitles(title: String): List<String> = listOf(title, withoutCredit(title))

/**
 * [title] without the credit in its last tag, and the spaces around it, which may name someone with brackets of their
 * own, "（歌：YOASOBI（ヨアソビ））". Where a bracket doesn't close in order, the credit runs from a bracket after the one
 * that closes before the last, to the last, as main read it.
 */
private fun withoutCredit(title: String): String {
    val close = title.indexOfLast { it in CREDIT_CLOSES }.takeIf { it != -1 } ?: return title
    // Only spaces may follow, and a line break the end of the line leaves.
    val end = spacesEnd(title, close + 1).takeIf { it == title.length || it == title.length - 1 && isLineBreak(title[it]) } ?: return title
    val credit = if (bracketsInOrder(title, CREDIT_OPENS, CREDIT_CLOSES)) {
        bracketTags(title, CREDIT_OPENS, CREDIT_CLOSES).last().first.takeIf { creditAt(title, it) }
    } else {
        val after = title.lastIndexOfAny(CREDIT_CLOSES.toCharArray(), close - 1)
        (after + 1 until close).firstOrNull { creditAt(title, it) }
    } ?: return title
    return title.substring(0, spacesStart(title, credit)) + title.substring(end)
}

private const val CREDIT_OPENS = "（("
private const val CREDIT_CLOSES = "）)"

/**
 * Whether a credit starts at [at], as `[（(]\s*+(?:歌|唄|作詞|作曲|編曲|原曲)` read it: a bracket, spaces, and who did
 * what. Read in code: on a phone a pattern copies the whole title before each look, so a look at each bracket of a
 * long title took time squared.
 */
private fun creditAt(title: String, at: Int) = title[at] in CREDIT_OPENS && CREDIT_WORDS.any { title.startsWith(it, spacesEnd(title, at + 1)) }
private val CREDIT_WORDS = listOf("歌", "唄", "作詞", "作曲", "編曲", "原曲")

private fun chordWikiHitLine(line: String): ChordWikiHit? {
    val (text, href) = line.split('\t', limit = 2).takeIf { it.size == 2 } ?: return null
    return chordWikiHit(text, href)
}

private fun chordWikiHit(text: String, href: String): ChordWikiHit? {
    val link = href.trim()
    // A song's page, not the menu's own "wiki.cgi?c=history".
    if ("/wiki/" !in link && !("wiki.cgi" in link && "t=" in link)) return null
    val title = text.substringBefore(" - ").trim()
    if (title.isEmpty()) return null
    return ChordWikiHit(title, text.substringAfter(" - ", "").trim(), link)
}

private val ANCHOR = phoneRegex("""<a\b[^<>]*\bhref="([^"<>]*+)"[^<>]*+>((?:[^<]++|<(?!/a>|a\b))*+)</a>""", RegexOption.IGNORE_CASE)
private val ANCHOR_TAGS = phoneRegex("<[^<>]++>")

/** What ChordWiki's search says when no song matches. */
private const val NO_MATCH = "一致するものは見つかりませんでした"

/** ChordWiki's check stopped the lookup. [check] says which kind, so the screen can say it too. */
internal class ChordWikiStopped(val check: ChordWikiCheck) : IOException(
    // A person's check is given up, not clicked for them. The other kind just ran out of time.
    if (check == ChordWikiCheck.INTERACTIVE) "ChordWiki asked for a check a person has to click" else "ChordWiki's check didn't finish"
)

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
