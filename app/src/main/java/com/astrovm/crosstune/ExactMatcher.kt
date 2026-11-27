package com.astrovm.crosstune

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.TimeSource

/** A queue in YouTube or YouTube Music: its [url], and the [songs] it plays, as asked for, those found there. */
internal data class YouTubeQueue(val url: String, val songs: List<MusicMetadata>)

/**
 * Raised when songs are matched otherwise, so what was found the old way is looked for again, in the lookups kept and in
 * Recent: 2, as every edition's length counts, which finds a single's official song where an upload was found before;
 * 3, as a name written both ways apart by a wide space goes by each, which finds Anri's song where a fan's upload was;
 * 4, as Qobuz's songs are found in the phone's own store; 5, as another channel's video needs a known matching length.
 * Version 6 uses the link's length and an explicit recording label to choose an edition.
 * Version 7 requires an accented Latin row to name the song.
 * Version 8 doesn't take a row in another script that says it's a live, karaoke or other take for the studio song, and
 * can use an artist's video and a matching store album to name a song in another script.
 * Version 9 learns the US store's name for a song another store found, so YouTube Music's row by Kenshi Yonezu is Lemon
 * by 米津玄師, where a fan's lyrics video was found.
 * Version 10 doesn't take a re-recording, From THE FIRST TAKE or 重生版, for the song unless it's asked for, so First Love
 * opens 宇多田ヒカル's own, and takes a credit of two known names together, G.E.M. 鄧紫棋, as the artist's.
 * Version 11 gives a missing store one retry before playing a fan upload and checks the upload again later.
 * Version 12 can confirm an untranslated search result by its own song ID in another YouTube Music language.
 * Version 13 matches two whole names in a bilingual store title when the artist's video repeats its credit.
 * Version 14 needs evidence from the link before opening a row that names a disco version.
 * Version 15 keeps disco requests separate from plain requests in the cache.
 * Version 16 can prove a parenthesized catalog alias from both credited video titles.
 * Version 17 checks the recording and artist across Apple Music, Deezer and MusicBrainz.
 * Version 18 opens an explicit or a clean edition by Apple's badge, or the first, and a radio edit's album take.
 */
internal const val MATCH_VERSION = 18

/**
 * Finds a direct link to the same item using services' key-less search APIs: Apple's iTunes
 * Search API and Deezer's public API, plus the search behind Bandcamp's, YouTube Music's, TIDAL's,
 * SoundCloud's, Audiomack's and Presto Music's own sites, and Qobuz's store pages, which have no documented API.
 * Any failure or uncertain match returns null so the caller falls back to a search.
 */
internal class ExactMatcher(
    private val client: OkHttpClient,
    private val country: String = Locale.getDefault().country,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Where what's found is remembered, so the same song isn't looked up again. */
    private val cache: LookupCache? = null,
    private val apis: ServiceApis = ServiceApis(client, country, ioDispatcher),
    /**
     * The one of [rows], a service's search results as "title | details", that is the song, by its
     * index; null for none. The last Boolean says the rows are videos anyone put up. Asked last, when
     * nothing matches by name: ChatGPT, when signed in.
     */
    private val pick: suspend (MusicMetadata, List<String>, Boolean) -> Int? = { _, _, _ -> null },
    /**
     * How long a song looked for and not found stays not found, so it isn't looked for again, and
     * ChatGPT asked again, each time it's opened; none by default.
     */
    private val missingForMs: Long = 0,
    private val now: () -> Long = SystemClock::elapsedRealtime,
    /** How long a search has, and then ChatGPT to pick from what it found. */
    private val timeoutMs: Long = TIMEOUT_MS,
    private val pickTimeoutMs: Long = PICK_TIMEOUT_MS,
    /** A Spotify artist's id by their name, see [MusicBrainz.spotifyArtist]; none by default. */
    private val spotifyArtist: suspend (String) -> String? = { null },
    /** Independent names of an artist, or none when they cannot be established. */
    private val artistAliases: suspend (String) -> Set<String>? = { null },
    private val wallNow: () -> Long = { System.currentTimeMillis() },
    private val lookupTime: TimeSource = TimeSource.Monotonic,
    private val artistAliasTimeoutMs: Long = ARTIST_ALIAS_WAIT_MS,
    private val appleProofWaitMs: Long = APPLE_PROOF_WAIT_MS,
    private val explicitWaitMs: Long = EXPLICIT_WAIT_MS
) {

    /** What looking for something came to: found, not there, or no answer, e.g. offline, which says neither. */
    sealed interface Match {
        /** An upload found during a store outage can open now and still need another lookup later. */
        data class Found(val url: String, val lasting: Boolean = true) : Match
        data object WaitingForNames : Match
        data object Missing : Match
        data object Failed : Match
    }

    /** A lookup to try again because a store that could name the song was down. */
    data class Retry(val heldAtMs: Long? = null) {
        fun toJson(): JSONObject = JSONObject().putOpt("heldAtMs", heldAtMs)

        companion object {
            fun fromJson(json: JSONObject): Retry = Retry(json.optLong("heldAtMs", -1).takeIf { it >= 0 })
        }
    }

    data class Lookup(val match: Match, val retry: Retry? = null)

    private data class SavedAnswer(val url: String?, val retry: Retry? = null) {
        val confirmedUrl: String? get() = url.takeIf { retry == null }
        val provisionalUrl: String? get() = url.takeIf { retry != null }

        fun forCache(): String = retry?.toJson()?.putOpt("url", url)?.toString() ?: url.orEmpty()
    }

    private suspend fun savedAnswer(key: String): SavedAnswer? = cache?.get(key)?.let { value ->
        if (value.startsWith("{")) {
            answeredOr(null) {
                val json = JSONObject(value)
                SavedAnswer(json.optString("url").ifEmpty { null }, Retry.fromJson(json))
            }
        } else {
            SavedAnswer(value)
        }
    }

    /** When each thing looked for and not found was, by its [cacheKey]. */
    private val missing = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** When each thing was looked for while a store that could have named it failed, and what that came to, by its [cacheKey]. */
    private val unsure = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Lookup>>()

    /** When the wait started, by the clock used during it and the clock that survives a restart. */
    private data class NameHold(val startedAt: Long, val wallTime: Long) {
        fun waiting(at: Long): Boolean = at - startedAt < UNSURE_FOR_MS
        fun pending(): Lookup = Lookup(Match.WaitingForNames, Retry(wallTime))
    }

    private val waitingForNames = java.util.concurrent.ConcurrentHashMap<String, NameHold>()

    /** Keeps checking saved answers and writing them together while lookups run independently. */
    private val savingAnswers = Mutex()

    /** What an artist's own Bandcamp page name may add to their name. */
    private val pageSuffixes = listOf("", "music", "band", "official", "officialmusic")

    /**
     * Whether a YouTube video is music: a song or an official music video, as YouTube Music counts
     * them, or someone's own upload they put under Music, like a concert they filmed; not a tutorial
     * or a vlog. Null when it can't be told, e.g. offline.
     */
    suspend fun isMusicVideo(videoId: String): Boolean? = withTimeoutOrNull(TIMEOUT_MS) {
        answeredOr(null) {
            val body = YouTubeMusicApi.body(language = null).put("videoId", videoId)
            musicVideoTypeIn(fetchJson(YouTubeMusicApi.NEXT_URL, body))?.let { it in MUSIC_VIDEO_TYPES || uploadedAsMusic(videoId) }
        }
    }

    /** Whether whoever put the video up filed it under Music, as YouTube tells whoever plays it. */
    private suspend fun uploadedAsMusic(videoId: String): Boolean {
        val client = JSONObject().put("clientName", "WEB").put("clientVersion", YOUTUBE_CLIENT_VERSION)
        val body = JSONObject().put("context", JSONObject().put("client", client)).put("videoId", videoId)
        val video = fetchJson(YOUTUBE_PLAYER_URL, body).optJSONObject("microformat")?.optJSONObject("playerMicroformatRenderer")
        return video?.optString("category") == "Music"
    }

    suspend fun find(target: MusicService, metadata: MusicMetadata): String? = (lookFor(target, metadata) as? Match.Found)?.url

    /** What [target] has for [metadata], telling a song that isn't there from one that couldn't be looked for. */
    suspend fun lookFor(target: MusicService, metadata: MusicMetadata): Match = lookup(target, metadata).match

    /** Recent can supply the retry and its provisional URL if the cache was cleared. */
    suspend fun lookup(
        target: MusicService, metadata: MusicMetadata, retry: Retry? = null, provisionalUrl: String? = null
    ): Lookup = match(target, metadata, retry?.let { SavedAnswer(provisionalUrl, it) }).also { cache?.save() }

    private suspend fun match(target: MusicService, metadata: MusicMetadata, previous: SavedAnswer? = null): Lookup {
        // A playlist whose songs aren't known may be an album put up as one, e.g. a SoundCloud set,
        // so it's found as that album, by the same artist, or not at all. Presto only looks up album links.
        val maybeAlbum = target != MusicService.PRESTO_MUSIC && metadata.type == ItemType.PLAYLIST && metadata.tracks.isEmpty()
        val item = if (maybeAlbum) metadata.copy(type = ItemType.ALBUM) else metadata
        // Other apps need an account to search, so there's nothing to look up or remember.
        if (!canMatchExactly(target, item.type)) return Lookup(Match.Missing)
        return remembered("find", target.name, item, previous) { lookUp(target, item) }
    }

    /**
     * For a queue, where a song that plays beats one that is left out: the song's exact match, or else
     * the first song by the same artist whose title has all the words of the other's, e.g. a title
     * written in another script or a classical work's long name. Kept apart from [match]'s answers,
     * which a single song is opened with.
     */
    private suspend fun matchForQueue(target: MusicService, metadata: MusicMetadata): String? {
        if (metadata.type != ItemType.TRACK || target !in setOf(MusicService.YOUTUBE_MUSIC, MusicService.YOUTUBE)) {
            return (match(target, metadata).match as? Match.Found)?.url
        }
        metadata.url?.let(MusicLinks::linkFor)?.youtubeVideoOn(target)?.let { return it }
        val single = savedAnswer(cacheKey("find", target.name, metadata))
        single?.confirmedUrl?.let { return it }
        val queued = remembered("queue", target.name, metadata, single) { lookUp(target, metadata, loosely = true, retrying = true) }
        return (queued.match as? Match.Found)?.url
    }

    /**
     * A search's answer: what it found, or else, on YouTube, the songs and videos it turned up for
     * ChatGPT to pick from, given its own while, as it takes a few seconds.
     */
    private class Answer(
        val url: String?, val toPick: ToPick? = null, val final: Boolean = true, val lasting: Boolean = true,
        val waitForNames: Boolean = false, val artistVideo: String? = null,
        val localizedUrl: (suspend () -> String?)? = null
    )

    private class ToPick(
        val metadata: MusicMetadata, val songs: List<Row>, val videos: List<Row>, val watchUrl: String,
        val names: Names, val songIds: Set<String>, val conflictingLengths: List<Long>, val requiresNamedVideo: Boolean
    )

    /**
     * What [lookUp] came to, and whether it's only for now, as a store that could have named the song failed: [unsure].
     * Then a miss is no answer, and what's found still needs a retry.
     */
    private class Looked(
        val match: Match, val unsure: Boolean = false, val waitForNames: Boolean = false,
        private val artistVideo: String? = null
    ) {
        fun matchAfterOutage(afterOutage: Boolean): Match =
            if (afterOutage && artistVideo != null) Match.Found(artistVideo) else match
    }

    /**
     * What [target] has for [metadata]. A search that failed or timed out is asked once more when
     * [retrying], e.g. for a queue, where one song in fifty must not be lost to a slow moment; an
     * answer of "none" is final, unless what would have proved the song failed: then it's no answer,
     * and not asked again at once, as the store that failed is likely still down.
     */
    private suspend fun lookUp(target: MusicService, metadata: MusicMetadata, loosely: Boolean = false, retrying: Boolean = false): Looked {
        repeat(if (retrying) 2 else 1) {
            val start = lookupTime.markNow()
            val answer = withTimeoutOrNull(timeoutMs) {
                answeredOr(null) { findOn(target, metadata, loosely) }
            }
            if (answer != null) {
                // A stalled localized search spends only what's left of the search time and doesn't lose the picker.
                val url = answer.url ?: answer.localizedUrl?.let { localize ->
                    withTimeoutOrNull(timeoutMs - start.elapsedNow().inWholeMilliseconds) { localize() }
                }
                val match = url?.let { Match.Found(it, answer.lasting) } ?: answer.toPick?.let { picked(it, answer.lasting) } ?: Match.Missing
                return if (match == Match.Missing && !answer.final) Looked(Match.Failed, unsure = true)
                else Looked(match, unsure = !answer.lasting, waitForNames = answer.waitForNames, artistVideo = answer.artistVideo)
            }
        }
        return Looked(Match.Failed)
    }

    private suspend fun findOn(target: MusicService, metadata: MusicMetadata, loosely: Boolean): Answer = when (target) {
        MusicService.APPLE_MUSIC -> Answer(findOnAppleMusic(metadata)?.url)
        MusicService.DEEZER -> Answer(findOnDeezer(metadata))
        MusicService.BANDCAMP -> Answer(findOnBandcamp(metadata))
        MusicService.TIDAL -> Answer(findOnTidal(metadata))
        MusicService.SPOTIFY -> Answer(findOnSpotify(metadata))
        MusicService.SOUNDCLOUD -> Answer(findOnSoundCloud(metadata))
        MusicService.AUDIOMACK -> Answer(findOnAudiomack(metadata))
        MusicService.PRESTO_MUSIC -> Answer(findOnPresto(metadata))
        MusicService.QOBUZ -> Answer(findOnQobuz(metadata))
        MusicService.YOUTUBE_MUSIC -> findOnYouTubeMusic(metadata, YOUTUBE_MUSIC_WATCH_URL, loosely)
        // YouTube.
        else -> findOnYouTubeMusic(metadata, YOUTUBE_WATCH_URL, loosely)
    }

    /** The song ChatGPT picks among the songs, then the videos; no answer in time is no answer. */
    private suspend fun picked(toPick: ToPick, lasting: Boolean): Match {
        val url = withTimeoutOrNull(pickTimeoutMs) {
            Answer(pickedFrom(toPick, videos = false) ?: pickedFrom(toPick, videos = true))
        } ?: return Match.Failed
        return url.url?.let { Match.Found(it, lasting) } ?: Match.Missing
    }

    /** By the song's length too, when it's known, as an edit of a song is often named just like it; and by how songs are matched, so what was found before is looked for again. */
    private fun cacheKey(kind: String, target: String, metadata: MusicMetadata): String {
        val edition = if (metadata.requestsDisco()) "/disco" else ""
        return (listOf("$kind@$MATCH_VERSION$edition", target, metadata.type.name, metadata.title, metadata.artist, metadata.url.orEmpty()) +
            listOfNotNull(metadata.lengthMs)).joinToString("|")
    }

    /**
     * What was found for [metadata] before, or [lookUp]'s answer, which is kept when it found
     * something; when it found nothing, that's kept for [missingForMs]. One reached while a store that
     * could have named the song failed is held for a while, at most as long, and then asked again: what
     * it found may be a fan's video where that store's name finds the song. Held, asking again at once,
     * as a card does, doesn't ask every service again for the store that's down.
     * Give the store one 30-second hold if it could name a competing result and there is no provisional URL.
     * After that, open the saved provisional URL if the next lookup still fails. Each open asks again after the
     * short in-memory pause, so the artist's video can replace the upload once the store answers.
     */
    private suspend fun remembered(
        kind: String, target: String, metadata: MusicMetadata, previous: SavedAnswer? = null, lookUp: suspend () -> Looked
    ): Lookup {
        val key = cacheKey(kind, target, metadata)
        val changes = CacheChanges()
        try {
            return changes.findAnswer(key, previous, lookUp).also(changes::keepRetry)
        } finally {
            changes.flush()
        }
    }

    private suspend fun CacheChanges.findAnswer(key: String, previous: SavedAnswer?, lookUp: suspend () -> Looked): Lookup {
        val (outage, hold, earlier) = savingAnswers.withLock {
            val saved = savedAnswer(key)
            // Recent can still have an upload after another open found the artist's video.
            saved?.confirmedUrl?.let { return Lookup(Match.Found(it)) }
            val outage = previousOutage(saved, previous)
            if (outage == null) {
                missing[key]?.takeIf { now() - it < missingForMs }?.let { return Lookup(Match.Missing) }
            }

            val hold = nameHold(key, saved, outage)
            hold?.takeIf { it.waiting(now()) }?.let { return it.pending() }
            val earlier = unsure[key]
            earlier?.takeIf { now() - it.first < minOf(missingForMs, UNSURE_FOR_MS) }?.let { return it.second }
            Triple(outage, hold, earlier?.second)
        }
        val looked = lookUp()
        return answerLookup(key, outage, hold, earlier, looked)
    }

    private fun previousOutage(saved: SavedAnswer?, previous: SavedAnswer?): SavedAnswer? {
        // The cache can have corrected a hold timestamp that Recent still has from before a clock change.
        val retry = saved?.retry?.takeIf { it.heldAtMs != null } ?: previous?.retry ?: saved?.retry ?: return null
        return SavedAnswer(saved?.provisionalUrl ?: previous?.provisionalUrl, retry)
    }

    private suspend fun CacheChanges.answerLookup(
        key: String, outage: SavedAnswer?, hold: NameHold?, earlier: Lookup?, looked: Looked
    ): Lookup = savingAnswers.withLock {
        val saved = savedAnswer(key)
        // Another lookup may have saved the artist's video while this one was waiting for the store.
        saved?.confirmedUrl?.let { return@withLock Lookup(Match.Found(it)) }
        val retry = saved?.retry ?: hold?.let { Retry(it.wallTime) } ?: outage?.retry
        val earlierUrl = (earlier?.match as? Match.Found)?.url
        val provisional = saved?.provisionalUrl ?: outage?.url ?: earlierUrl.takeIf { looked.waitForNames }
        if (provisional == null && (looked.waitForNames || looked.match == Match.Failed)) {
            waitingAnswer(key, saved, retry, canStart = looked.waitForNames)?.let { return@withLock it }
        }
        val match = if (looked.unsure && provisional != null) Match.Found(provisional, lasting = false)
        else looked.matchAfterOutage(retry != null || earlier != null)
        rememberAnswer(key, saved, looked, match, retry, provisional)
    }

    private suspend fun CacheChanges.nameHold(key: String, saved: SavedAnswer?, outage: SavedAnswer?): NameHold? {
        if (outage?.url != null) return null
        val heldAt = outage?.retry?.heldAtMs ?: return waitingForNames[key]
        val at = now()
        val wall = wallNow()
        val start = minOf(heldAt, wall)
        val hold = waitingForNames.computeIfAbsent(key) { NameHold(at - (wall - start), start) }
        if (hold.wallTime < heldAt) {
            // Keep the corrected start so the next restart counts time spent waiting here.
            saveAnswer(key, saved, SavedAnswer(null, Retry(hold.wallTime)))
        }
        return hold
    }

    private suspend fun CacheChanges.waitingAnswer(key: String, saved: SavedAnswer?, retry: Retry?, canStart: Boolean): Lookup? {
        val existing = waitingForNames[key]
        if (existing != null) return existing.takeIf { it.waiting(now()) }?.pending()
        if (!canStart || retry?.heldAtMs != null) return null
        val hold = NameHold(now(), wallNow())
        waitingForNames[key] = hold
        val pending = hold.pending()
        saveAnswer(key, saved, SavedAnswer(null, pending.retry))
        return pending
    }

    private suspend fun CacheChanges.rememberAnswer(
        key: String, saved: SavedAnswer?, looked: Looked, match: Match, previous: Retry?, provisional: String?
    ): Lookup {
        if (looked.unsure) {
            val retry = if (match is Match.Found) {
                (previous ?: Retry()).also { saveAnswer(key, saved, SavedAnswer(match.url, it)) }
            } else {
                previous
            }
            return Lookup(match, retry).also { unsure[key] = now() to it }
        }
        if (match == Match.Failed) return Lookup(provisional?.let { Match.Found(it, lasting = false) } ?: match, previous)

        waitingForNames.remove(key)
        unsure.remove(key)
        if (match is Match.Found) saveAnswer(key, saved, SavedAnswer(match.url))
        if (match == Match.Missing) {
            saved?.let { discardAnswer(key, it) }
            missing[key] = now()
        }
        return Lookup(match)
    }

    private inner class CacheChanges {
        private var needsFlush = false

        suspend fun saveAnswer(key: String, saved: SavedAnswer?, answer: SavedAnswer) {
            if (cache == null || saved == answer) return
            cache.put(key, answer.forCache())
            if (answer.retry != null || saved?.retry != null) needsFlush = true
        }

        suspend fun discardAnswer(key: String, saved: SavedAnswer) {
            cache?.discard(key, saved.forCache())
            needsFlush = true
        }

        fun keepRetry(answer: Lookup) {
            // Another lookup may have saved this retry and still be writing it to disk.
            if (answer.retry != null) needsFlush = true
        }

        suspend fun flush() {
            if (needsFlush) cache?.flush()
        }
    }

    /**
     * One queue for [tracks] in YouTube Music or YouTube ([target]): each song is matched to its
     * video, then YouTube makes a temporary playlist of those videos, opened at the first. No
     * account needed. [onProgress] hears how many have been looked up. Null when none match.
     */
    suspend fun youtubeQueue(target: MusicService, tracks: List<MusicMetadata>, onProgress: (Int) -> Unit): YouTubeQueue? {
        val looked = AtomicInteger()
        val lookups = Semaphore(QUEUE_LOOKUPS_AT_ONCE)
        val found = coroutineScope {
            tracks.map { track ->
                async {
                    lookups.withPermit { matchForQueue(target, track) }.also { onProgress(looked.incrementAndGet()) }
                }
            }.awaitAll()
        }.zip(tracks).mapNotNull { (url, track) -> url?.toHttpUrl()?.queryParameter("v")?.let { it to track } }.distinctBy { it.first }
        cache?.save()
        if (found.isEmpty()) return null
        val ids = found.map { it.first }
        val watch = if (target == MusicService.YOUTUBE_MUSIC) YOUTUBE_MUSIC_WATCH_URL else YOUTUBE_WATCH_URL
        val list = try {
            temporaryPlaylist(ids)
        } catch (_: IOException) {
            null
        }
        // Without the playlist, the first song still plays, on its own.
        val songs = if (list != null) found.map { it.second } else listOf(found.first().second)
        return YouTubeQueue(watch + ids.first() + (list?.let { "&list=$it" } ?: ""), songs)
    }

    /** YouTube answers a list of videos with a redirect to a temporary playlist of them. */
    private suspend fun temporaryPlaylist(ids: List<String>): String? {
        val url = YOUTUBE_WATCH_VIDEOS_URL.toHttpUrl().newBuilder().addQueryParameter("video_ids", ids.joinToString(",")).build()
        val noRedirects = client.newBuilder().followRedirects(false).build()
        return noRedirects.newCall(Request.Builder().url(url).get().build()).answer(ioDispatcher) { response ->
            response.header("Location")?.toHttpUrlOrNull()?.queryParameter("list")
        }
    }

    data class AppleMusicMatch(val url: String, val lengthMs: Long?)

    /** Radio queue text needs its own title check, without using a cached general match. */
    suspend fun findRadioSong(metadata: MusicMetadata): AppleMusicMatch? =
        withTimeoutOrNull(timeoutMs) { answeredOr(null) { findOnAppleMusic(metadata, radio = true) } }

    private suspend fun findOnAppleMusic(metadata: MusicMetadata, radio: Boolean = false): AppleMusicMatch? {
        val (entity, nameKey, urlKey) = when (metadata.type) {
            ItemType.TRACK -> Triple("song", "trackName", "trackViewUrl")
            ItemType.ALBUM -> Triple("album", "collectionName", "collectionViewUrl")
            else -> Triple("musicArtist", "artistName", "artistLinkUrl")
        }
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", searchQuery(metadata))
            .addQueryParameter("entity", entity)
            .addQueryParameter("limit", "10")
            .apply { if (country.length == 2) addQueryParameter("country", country) }
            .build()
        val results = fetchJson(url.toString()).optJSONArray("results") ?: return null
        val named = results.objects().filter { result ->
            // Apple marks some albums in their name, e.g. "Name - Single", which the source doesn't.
            val name = result.optString(nameKey).removeSuffix(" - Single").removeSuffix(" - EP")
            matches(metadata, name, result.optString("artistName"), radio = radio)
        }
        val result = asLongAs(metadata, named) { it.optLong("trackTimeMillis") } ?: return null
        val link = result.optString(urlKey).linkTo(MusicService.APPLE_MUSIC) ?: return null
        val length = result.optLong("trackTimeMillis").takeIf { it in 1L..Int.MAX_VALUE.toLong() }
        return AppleMusicMatch(link, length)
    }

    private suspend fun findOnDeezer(metadata: MusicMetadata): String? {
        val named = searchDeezer(metadata).filter { result ->
            val name = result.optString("title").ifEmpty { result.optString("name") }
            val artist = result.optJSONObject("artist")?.optString("name") ?: name
            matches(metadata, name, artist)
        }
        return asLongAs(metadata, named) { it.optLong("duration") * 1000 }?.let { it.optString("link").linkTo(MusicService.DEEZER) }
    }

    /** An album link must name the same edition and lead artist, and one release must fit those credits. */
    private suspend fun findOnPresto(metadata: MusicMetadata): String? {
        val title = SongNames.normalize(metadata.title)
        val artists = Presto.albumArtists(metadata.artist) ?: return null
        if (title.isEmpty()) return null
        val results = apis.prestoAlbums(searchQuery(metadata)) ?: return null
        // A row without its title or credits can't be ruled out as another release of the same album.
        if (results.any { it.nonBlankString("title") == null || it.nonBlankString("artists") == null }) return null
        val matches = results.filter { result ->
            SongNames.normalize(result.nonBlankString("title").orEmpty()) == title &&
                Presto.matchesAlbumArtists(artists, result.nonBlankString("artists").orEmpty())
        }
        // Keep a missing code in the list. The row can still be a different release from a row with a code.
        val codes = matches.map { result ->
            result.optString("prestoCode").toLongOrNull()?.takeIf { it > 0 }
        }
        return codes.distinct().singleOrNull()?.let { Presto.albumUrl(it.toString()) }
    }

    /**
     * The first of [named], what a search found under the song's name, that's as long as the song,
     * when its length is known, as the same name is often on an edit or another mix of it; or else
     * the first.
     */
    private fun <T> asLongAs(metadata: MusicMetadata, named: List<T>, lengthMs: (T) -> Long?): T? {
        val wanted = metadata.lengthMs ?: return named.firstOrNull()
        return named.firstOrNull { found -> lengthMs(found)?.let { kotlin.math.abs(it - wanted) <= SAME_LENGTH_MS } == true } ?: named.firstOrNull()
    }

    private suspend fun searchDeezer(metadata: MusicMetadata): List<JSONObject> {
        val path = when (metadata.type) {
            ItemType.TRACK -> "search/track"
            ItemType.ALBUM -> "search/album"
            else -> "search/artist"
        }
        return searchDeezer(path, searchQuery(metadata))
    }

    private suspend fun searchDeezer(path: String, query: String): List<JSONObject> {
        val url = "https://api.deezer.com/$path".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("limit", "10")
            .build()
        val answer = fetchJson(url.toString())
        // Deezer says it's busy, e.g. over its quota, with an error, not with no results.
        if (answer.has("error")) throw IOException("Deezer answered ${answer.optJSONObject("error")?.optInt("code")}")
        return answer.optJSONArray("data")?.objects().orEmpty()
    }

    /**
     * A song's album cover found by its name, for songs that come without one (Now Playing) or with
     * a video frame instead (YouTube). A cover doesn't need the exact recording, so "Song (2003
     * Remaster)" by "The Band" is close enough to "Song" by "Band". Null when no song is close,
     * offline, or slower than [timeoutMs].
     */
    suspend fun cover(metadata: MusicMetadata, timeoutMs: Long = TIMEOUT_MS): String? = coverOf(metadata, timeoutMs).also { cache?.save() }

    /**
     * Covers for a playlist's [tracks] that came without one, a few at a time; [onFound] hears each
     * one's index and cover as it's found.
     */
    suspend fun covers(tracks: List<MusicMetadata>, onFound: (Int, String) -> Unit) {
        val lookups = Semaphore(QUEUE_LOOKUPS_AT_ONCE)
        try {
            coroutineScope {
                tracks.forEachIndexed { index, track ->
                    async { lookups.withPermit { coverOf(track, TIMEOUT_MS) }?.let { onFound(index, it) } }
                }
            }
        } finally {
            // Stopped partway, e.g. for another playlist, what was found is still kept.
            cache?.save()
        }
    }

    private suspend fun coverOf(metadata: MusicMetadata, timeoutMs: Long): String? = (remembered("cover", "", metadata) {
        withTimeoutOrNull(timeoutMs) {
            answeredOr(null) {
                // A Spotify song's own cover is known by its link, with no guessing by name. When Spotify
                // doesn't answer, e.g. it's busy with the rest of the playlist, the name still finds it.
                val own = if (metadata.url?.startsWith(SPOTIFY_TRACK_URL) == true) {
                    answeredOr(null) { spotifyCover(metadata.url) }
                } else {
                    null
                }
                // Apple has some Deezer doesn't, e.g. The Blue Hearts'.
                own ?: deezerCover(metadata) ?: itunesCover(metadata)
            }
        }?.let { Looked(Match.Found(it)) } ?: Looked(Match.Failed)
    }.match as? Match.Found)?.url

    private suspend fun spotifyCover(url: String): String? {
        val oEmbed = "https://open.spotify.com/oembed".toHttpUrl().newBuilder().addQueryParameter("url", url).build()
        return fetchJson(oEmbed.toString()).optString("thumbnail_url").ifBlank { null }
    }

    /** Deezer's cover for the song, under any of its names. */
    private suspend fun deezerCover(metadata: MusicMetadata): String? =
        SongNames.titleNames(metadata.title).firstNotNullOfOrNull { title -> deezerCoverNamed(metadata.copy(title = title)) }

    /** Apple's cover for the song, under any of its names, as large as it makes them. */
    private suspend fun itunesCover(metadata: MusicMetadata): String? =
        SongNames.titleNames(metadata.title).firstNotNullOfOrNull { title -> itunesCoverNamed(metadata.copy(title = title)) }

    private suspend fun itunesCoverNamed(metadata: MusicMetadata): String? {
        val title = SongNames.normalize(SongNames.withoutEditionTag(metadata.title))
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", searchQuery(metadata.copy(title = SongNames.withoutEditionTag(metadata.title), type = ItemType.TRACK)))
            .addQueryParameter("entity", "song")
            .addQueryParameter("limit", "10")
            .apply { if (country.length == 2) addQueryParameter("country", country) }
            .build()
        return fetchJson(url.toString()).optJSONArray("results")?.objects()?.firstOrNull { result ->
            val name = SongNames.normalize(SongNames.withoutEditionTag(result.optString("trackName")))
            title.isNotEmpty() && name.startsWith(title) && sameArtist(metadata, result.optString("artistName"))
        }?.let { it.optString("artworkUrl100").ifBlank { null }?.replace("100x100bb", "600x600bb") }
    }

    private suspend fun deezerCoverNamed(metadata: MusicMetadata): String? {
        val title = SongNames.normalize(SongNames.withoutEditionTag(metadata.title))
        // Searched for without its edition tag too: Deezer finds nothing for "Song (2018 Remix) (2024
        // Remaster)", though it has "Song (2018 Remix)", whose cover is the same.
        val searched = metadata.copy(title = SongNames.withoutEditionTag(metadata.title), type = ItemType.TRACK)
        return searchDeezer(searched).firstOrNull { result ->
            val name = SongNames.normalize(SongNames.withoutEditionTag(MetadataParsers.deezerTitle(result)))
            // "Song" by "A, B" is credited to just "A" on Deezer, so any one of the names will do. Being
            // inside a longer name won't: Adele Harley isn't Adele, and has a Hello of their own.
            title.isNotEmpty() && name.startsWith(title) && sameArtist(metadata, result.optJSONObject("artist")?.optString("name").orEmpty())
        }?.optJSONObject("album")?.let { it.optString("cover_big").ifBlank { null } }
    }

    /** TIDAL's catalogue is the labels' own, so a match by name and artist is the item itself. */
    private suspend fun findOnTidal(metadata: MusicMetadata): String? {
        val (kind, path) = when (metadata.type) {
            ItemType.TRACK -> "tracks" to "track"
            ItemType.ALBUM -> "albums" to "album"
            else -> "artists" to "artist"
        }
        val named = apis.tidalSearch(kind, searchQuery(metadata)).filter { result ->
            when (metadata.type) {
                ItemType.TRACK -> ServiceApis.tidalTrack(result)?.let { matches(metadata, it.title, it.artist) } == true
                ItemType.ALBUM -> matches(metadata, result.optString("title"), result.optJSONArray("artists").objects().joinToString(", ") { it.optString("name") })
                else -> matches(metadata, result.optString("name"), "")
            }
        }
        return asLongAs(metadata, named) { it.optLong("duration") * 1000 }?.let { "https://tidal.com/browse/$path/${it.optLong("id")}" }
    }

    /**
     * Spotify's search needs an account, but ListenBrainz knows which Spotify songs go by a name, and
     * each song's own page says its name, artist and length, so the one that's this song is picked
     * from the first few. Only songs: ListenBrainz knows no albums or artists, see [canMatchExactly].
     */
    private suspend fun findOnSpotify(metadata: MusicMetadata): String? {
        val ids = apis.spotifyIds(metadata.title, metadata.artist).take(SPOTIFY_PAGES)
        val songs = coroutineScope { ids.map { id -> async { apis.spotifySong(id) } }.awaitAll() }.filterNotNull()
        val named = songs.filter { matches(metadata, it.title, it.artist) }
        // ListenBrainz learns of a song a while after it's out; a new one is among its artist's most played.
        val found = named.ifEmpty { artistsSongs(metadata).filter { matches(metadata, it.title, it.artist) } }
        return asLongAs(metadata, found) { it.lengthMs }?.url
    }

    /** The most played songs on Spotify of the first artist [metadata] credits. */
    private suspend fun artistsSongs(metadata: MusicMetadata): List<MusicMetadata> {
        val artist = SongNames.artistNames(metadata.artist).firstOrNull() ?: return emptyList()
        return spotifyArtist(artist)?.let { apis.spotifyArtistSongs(it) }.orEmpty()
    }

    /**
     * Anyone can upload to SoundCloud under any name, so a song or album counts only when it's the
     * artist's own, or a verified account's, and an artist only when verified.
     */
    private suspend fun findOnSoundCloud(metadata: MusicMetadata): String? {
        fun owned(user: JSONObject?) = user != null && (user.optBoolean("verified") || sameArtist(metadata, user.optString("username")))
        val found = when (metadata.type) {
            ItemType.TRACK -> apis.soundCloudSearch("tracks", searchQuery(metadata)).firstOrNull { result ->
                ServiceApis.soundCloudTrack(result)?.let { matches(metadata, it.title, it.artist) } == true && owned(result.optJSONObject("user"))
            }
            ItemType.ALBUM -> apis.soundCloudSearch("albums", searchQuery(metadata)).firstOrNull { result ->
                val user = result.optJSONObject("user")
                matches(metadata, result.optString("title"), user?.optString("username").orEmpty()) && owned(user)
            }
            else -> apis.soundCloudSearch("users", searchQuery(metadata)).firstOrNull { result ->
                result.optBoolean("verified") && matches(metadata, result.optString("username"), "")
            }
        }
        return found?.let { it.optString("permalink_url").linkTo(MusicService.SOUNDCLOUD) }
    }

    /** Like SoundCloud, anyone can upload to Audiomack, so only the artist's own or a verified account's upload counts. */
    private suspend fun findOnAudiomack(metadata: MusicMetadata): String? {
        fun owned(uploader: JSONObject?) = uploader != null && (uploader.optString("verified") == "yes" || sameArtist(metadata, uploader.optString("name")))
        val kind = when (metadata.type) {
            ItemType.TRACK -> "songs"
            ItemType.ALBUM -> "albums"
            else -> "artists"
        }
        val found = apis.audiomackSearch(kind, searchQuery(metadata)).firstOrNull { result ->
            if (metadata.type == ItemType.ARTIST) {
                result.optString("verified") == "yes" && matches(metadata, result.optString("name"), "")
            } else {
                // One that can't be played here is no use, e.g. kept from this country by its rights holder.
                matches(metadata, result.optString("title"), result.optString("artist")) && owned(result.optJSONObject("uploader")) &&
                    !result.optBoolean("geo_restricted")
            }
        } ?: return null
        val slug = found.optString("url_slug").ifBlank { return null }
        if (metadata.type == ItemType.ARTIST) return "https://audiomack.com/$slug"
        val owner = found.optJSONObject("uploader")?.let { it.optString("url_slug").ifBlank { null } } ?: return null
        return "https://audiomack.com/$owner/${if (metadata.type == ItemType.TRACK) "song" else "album"}/$slug"
    }

    /**
     * Qobuz's API needs an account, but its store's search page lists each song with its album:
     * enough for songs and albums. Its artists aren't told apart there, so they're left to the search.
     * It answers only a phone's browser: anything else, a desktop browser too, gets a 403.
     * A song's number can differ between its stores, and Qobuz's app shows only a loading page for one
     * the account can't play, so [QobuzNumbers] tries another. The US has its own; the rest share the
     * UK's, but Japan's store names songs in Japanese, so they'd never match, and Latin America's stores
     * and Portugal's list none.
     */
    private suspend fun findOnQobuz(metadata: MusicMetadata): String? =
        if (metadata.type != ItemType.TRACK && metadata.type != ItemType.ALBUM) null else findOnQobuzStore(qobuzStore, metadata)

    /** The store [findOnQobuz] asks. */
    private val qobuzStore = if (country == "US") "us-en" else "gb-en"

    /**
     * The number of [song] in the store [findOnQobuz] doesn't ask, when it isn't [link]'s: stores differ on about one song
     * in four, and which one an account follows can't be told. Null with no other, or no answer.
     *
     * Android lets Crosstune open an app again only 9.5 s after the first launch, see [QobuzNumbers], which gives up on this
     * then, so this gives the store all of that time. One try that failed isn't evidence that the store has no song, so a second
     * goes out at once; one that is only slow goes on, with a second beside it after [timeoutMs] in case it's stuck. Cutting it
     * off at [timeoutMs] and starting over, as it did, lost a store that takes 7 s to answer, and cost its data twice.
     */
    suspend fun otherQobuzLink(song: MusicMetadata, link: String): String? {
        val other = if (qobuzStore == "us-en") "gb-en" else "us-en"
        // Apart from this coroutine's job, as a try waiting on the store can't be interrupted, and mustn't hold back the answer.
        val tries = CoroutineScope(currentCoroutineContext().minusKey(Job) + Job())
        try {
            val first = tries.async { qobuzTry(other, song) }
            val wait = tries.async { delay(timeoutMs) }
            // Null for a try that failed too: then the second goes out at once.
            val answer = select<Answer?> {
                first.onAwait { it }
                wait.onAwait { null }
            } ?: run {
                val second = tries.async { qobuzTry(other, song) }
                select<Answer?> {
                    first.onAwait { it ?: second.await() }
                    second.onAwait { it ?: first.await() }
                }
            }
            return answer?.url?.takeIf { it != link }
        } finally {
            tries.cancel()
        }
    }

    /** What the store said, or null when it couldn't be asked. */
    private suspend fun qobuzTry(store: String, song: MusicMetadata): Answer? =
        try {
            Answer(findOnQobuzStore(store, song))
        } catch (_: IOException) {
            null
        }

    private suspend fun findOnQobuzStore(store: String, metadata: MusicMetadata): String? {
        val url = "https://www.qobuz.com/$store/search/tracks".toHttpUrl().newBuilder().addPathSegment(inOnePathSegment(searchQuery(metadata))).build()
        val page = client.newCall(Request.Builder().url(url).asPhoneBrowser().get().build()).answer(ioDispatcher) { response ->
            if (!response.isSuccessful) throw IOException("Qobuz answered ${response.code}")
            response.body.stringAtMost()
        }
        val songs = qobuzItems.allIn(page).mapNotNull { item ->
            val html = page.substring(item.whole)
            val (title, credit) = qobuzTitle(html)?.toList()?.map(::htmlText) ?: return@mapNotNull null
            val (artist, album) = credit.split("•").map(String::trim).let { it.first() to it.getOrElse(1) { "" } }
            QobuzSong(title, artist, album, qobuzTrackRegex.find(html)?.groupValues?.get(1), qobuzAlbumRegex.find(html)?.groupValues?.get(1))
        }
        return if (metadata.type == ItemType.TRACK) {
            songs.firstOrNull { it.track != null && matches(metadata, it.title, it.artist) }?.let { "https://play.qobuz.com/track/${it.track}" }
        } else {
            songs.firstOrNull { it.album != null && matches(metadata, it.albumTitle, it.artist) }?.let { "https://play.qobuz.com/album/${it.album}" }
        }
    }

    /** This, when it's a link to [service]: an answer that has something else where its link goes, e.g. cut short, has none. */
    private fun String.linkTo(service: MusicService): String? = takeIf { MusicLinks.serviceFor(it) == service }

    private class QobuzSong(val title: String, val artist: String, val albumTitle: String, val track: String?, val album: String?)

    private fun htmlText(html: String) = android.text.Html.fromHtml(withoutTags(html, with = " "), android.text.Html.FROM_HTML_MODE_LEGACY)
        .toString().replace(spacesRegex, " ").trim()

    private val spacesRegex = phoneRegex("\\s+")

    /** Whether [name] is one of [metadata]'s artists, e.g. the account an upload is on. */
    private fun sameArtist(metadata: MusicMetadata, name: String): Boolean {
        val wanted = SongNames.artists(metadata.artist)
        return SongNames.artists(name).any(wanted::contains)
    }

    /**
     * Songs and albums only. An artist match would rest on the name alone, and anyone can register a page
     * named after a famous artist (or share their name), so artists are left to the search.
     */
    private suspend fun findOnBandcamp(metadata: MusicMetadata): String? {
        val filter = when (metadata.type) {
            ItemType.TRACK -> "t"
            ItemType.ALBUM -> "a"
            else -> return null
        }
        val body = JSONObject()
            .put("search_text", searchQuery(metadata))
            .put("search_filter", filter)
            .put("fan_id", JSONObject.NULL)
            .put("full_page", false)
        val results = fetchJson(BANDCAMP_SEARCH_URL, body).optJSONObject("auto")?.optJSONArray("results") ?: return null
        return results.objects().firstOrNull { result ->
            matches(metadata, result.optString("name"), result.optString("band_name").ifEmpty { result.optString("name") }) &&
                isOnArtistsOwnPage(metadata, result.optString("item_url_root"))
        }?.let { it.optString("item_url_path").linkTo(MusicService.BANDCAMP) }
    }

    /**
     * Anyone can upload to Bandcamp under any artist name, so a fan's cover credited to "Rick Astley"
     * would match. Real artists sit on a page named after them, like catpower.bandcamp.com, or with a
     * usual suffix, like catpowermusic. A whole name is required: "sia" must not pass on asia.bandcamp.com.
     * Anything else, e.g. a label's page, is left to the search fallback.
     */
    private fun isOnArtistsOwnPage(metadata: MusicMetadata, pageUrl: String): Boolean {
        val host = SongNames.normalize(pageUrl.toHttpUrlOrNull()?.host?.removeSuffix(".bandcamp.com").orEmpty())
        if (host.isEmpty()) return false
        // Without an artist there is nothing to check the page against, so leave it to the search.
        return SongNames.artistNames(metadata.artist).any { name ->
            // "The" only counts as an article when it is a word of its own: not in "Thelonious".
            listOf(name, SongNames.withoutArticle(name)).map(SongNames::normalize).any { candidate ->
                candidate.isNotEmpty() && pageSuffixes.any { suffix -> host == candidate + suffix }
            }
        }
    }

    /**
     * YouTube Music's search, filtered to songs, albums or artists. Songs are also watchable on
     * YouTube itself, which has no such filter, so [watchUrl] picks the app the video id opens in.
     */
    private suspend fun findOnYouTubeMusic(metadata: MusicMetadata, watchUrl: String, loosely: Boolean): Answer {
        val (params, isSong) = when (metadata.type) {
            ItemType.TRACK -> YOUTUBE_MUSIC_SONGS to true
            ItemType.ALBUM -> YOUTUBE_MUSIC_ALBUMS to false
            else -> YOUTUBE_MUSIC_ARTISTS to false
        }
        // Only songs can be opened in the YouTube app, and it would just be a search for the rest.
        if (!isSong && watchUrl == YOUTUBE_WATCH_URL) return Answer(null)
        val body = YouTubeMusicApi.body().put("query", searchQuery(metadata)).put("params", params)
        val items = fetchJson(YouTubeMusicApi.SEARCH_URL, body).listItems()
        val disco = DiscoRequest(metadata)
        val recording = requestedRecording(metadata.title.takeIf { isSong }.orEmpty())
        fun found(item: JSONObject, loose: Boolean): String? {
            val columns = item.optJSONArray("flexColumns")?.objects()
                ?.map { it.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.text().orEmpty() }
                ?: return null
            val name = columns.firstOrNull().orEmpty()
            // The second line is "Artist • Album • 4:46" for songs and "Album • Artist • 2012" for albums.
            val details = columns.getOrNull(1).orEmpty().split(" • ")
            val artist = if (isSong) details.firstOrNull().orEmpty() else details.getOrNull(1).orEmpty()
            // A song's album may say what version it is, "Song" on "… Remix".
            val album = if (isSong) details.getOrNull(1).orEmpty() else ""
            if (isSong && !keepsRequestedRecording(recording, name, album)) return null
            val named = if (isSong && metadata.lengthMs != null) keepNotesAfterGuests(name) else name
            val same = if (loose) looselyMatches(metadata, named, artist) else matches(metadata, named, artist.ifEmpty { named }, album)
            if (!same) return null
            return when (metadata.type) {
                ItemType.TRACK -> item.optJSONObject("playlistItemData")?.let { it.optString("videoId").ifBlank { null } }
                    ?.let { watchUrl + it }
                else -> item.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.let { it.optString("browseId").ifBlank { null } }
                    ?.let { (if (metadata.type == ItemType.ALBUM) YOUTUBE_MUSIC_ALBUM_URL else YOUTUBE_MUSIC_ARTIST_URL) + it }
            }
        }
        // An exact match anywhere in the results beats a close one at the top.
        fun named(loose: Boolean) = items.mapNotNull { item -> found(item, loose)?.let { it to row(item) } }
        val exact = named(false)
        if (!isSong) return Answer(asLongAs(metadata, if (loosely && exact.isEmpty()) named(true) else exact) { it.second.lengthMs }?.first)
        val direct = chooseDirectSong(exact, metadata, disco)
        val edition = namedLinkedEdition(metadata, items, (direct ?: exact.firstOrNull())?.second?.lengthMs)
        if (edition != null && disco.allows(edition)) return Answer(watchUrl + edition.id)
        if (direct != null) return Answer(direct.first)
        if (loosely) chooseDirectSong(named(true), metadata, disco)?.let { return Answer(it.first) }
        // Not there by its name, it may be by another, e.g. in Japanese, or only as a video, e.g. one
        // never released, or only put up by others, as The Blue Hearts' are.
        val (videos, names) = coroutineScope {
            val videos = async { searchVideosOnYouTubeMusic(metadata) }
            val names = async { namesOf(metadata) }
            videos.await() to names.await()
        }
        val rows = items.map(::row)
        val sourceLength = metadata.lengthMs?.takeIf { it > 0 }
        val ambiguous = indistinguishableCandidates(rows, sourceLength)
        val plausible = rows.filter { row -> knownArtist(row.details.first(), names) &&
            (names.lengthsMs.isEmpty() || row.lengthMs == null || names.lengthsMs.any { sameRecordingLength(row.lengthMs, it) }) }
        val videoAmbiguous = indistinguishableCandidates(plausible, sourceLength)
        val conflictingLengths = conflictingLengths(rows, names, sourceLength)
        val listedVideos = videos.map(::row)
        val rejectedFrontVideo = listedVideos.firstOrNull()?.let { namesVideoSource(it, names) && videoNamesDifferentRecording(it, names) } == true
        val linkedVideoLength = sourceLength ?: names.primaryEdition?.lengthMs ?: names.lengthsMs.firstOrNull()
        val videoRows = listedVideos.filter { video ->
            val fitsLength = conflictingLengths.isEmpty() || knownVideoFitsSource(video, names, sourceLength, conflictingLengths)
            val fitsAmbiguity = videoAmbiguous.isEmpty() || safeVideoBesideAmbiguousSongs(video, metadata, names)
            val fitsRecording = !tooShortToBeTheSong(video, names) && (!rejectedFrontVideo || fitsReleaseLength(video, linkedVideoLength))
            fitsLength && fitsAmbiguity && fitsRecording
        }
        val appleSong = appleSongRow(rows, names, metadata, recording, disco)
        val initialSong = songRow(metadata, rows, videoRows, names, recording, appleSong, disco)
        if (names.appleFound) {
            appleSong?.let { return Answer(watchUrl + it.id, lasting = names.everyStore) }
            if (initialSong == null) {
                fun eligibleVideo(row: Row) = isTheSongsVideo(row, names) && (names.complete || isOwnVideo(row, names)) &&
                    keepsRequestedRecording(recording, row, video = true) && disco.allows(row, video = true)
                val first = videoRows.firstOrNull(::eligibleVideo)
                videoRows.firstOrNull {
                    (!names.everyStore || !appleNamedArtist(metadata, names)) && eligibleVideo(it) && isOwnVideo(it, names) &&
                        (it === first || first != null && sameVideoRecording(first, it, metadata, names))
                }?.let { return Answer(watchUrl + it.id, lasting = names.everyStore) }
            }
        }
        val resolved = resolveNamesAndCandidate(metadata, rows, videoRows, names, recording, watchUrl, disco)
        resolved.first?.let { return it }
        val available = resolved.second
        val song = if (available.lengthsMs == names.lengthsMs && available.deezerNames.isEmpty() && available.rejectedCandidates.isEmpty()) {
            initialSong
        } else {
            songRow(metadata, rows, videoRows, available, recording, appleSong, disco)
        }
        song?.let { return Answer(watchUrl + it.id, lasting = available.everyStore) }
        matchingVideo(metadata, rows, videoRows, available, recording, watchUrl, disco)?.let { return it }
        namedFallback(exact, rows, available, metadata, disco)?.let { return Answer(it.first, lasting = available.everyStore) }
        val localize = if (available.complete) null else suspend {
            localizedSong(metadata, items, available, recording, disco)?.let { watchUrl + it }
        }
        // Last, the songs, then the videos, are picked from, each asked about apart.
        val videoNamesAnotherRecording = listedVideos.any { namesVideoSource(it, available) && videoNamesDifferentRecording(it, available) }
        val otherTakes = rows.filter { pickerNamesDifferentSongTake(it, available) }.toSet()
        val pickableSongs = rows.mapNotNull { row ->
            row.id?.takeIf { id -> id !in available.rejectedCandidates && fitsReleaseLength(row, sourceLength) &&
                pickerMayKnowSong(row, metadata, available, candidateName(row) in ambiguous) &&
                row !in otherTakes &&
                (!videoNamesAnotherRecording || namedPickerSong(row, available)) &&
                (conflictingLengths.isEmpty() || namesTitleInRow(row, available)) }
        }.toSet()
        val namedVideos = listedVideos.filter { ambiguous.isNotEmpty() && videoTitleMatches(it.name, available) }
        return Answer(null, ToPick(metadata, rows.filter { it.id != null }.take(PICK_FROM),
            namedVideos.ifEmpty { listedVideos }.filter { it.id != null }.take(PICK_FROM), watchUrl, available, pickableSongs, conflictingLengths,
            videoNamesAnotherRecording),
            final = available.complete, lasting = available.everyStore, localizedUrl = localize)
    }

    /**
     * The first row of the song's exact name when none is as long as the link, as main opened: a radio edit's link
     * finds only the album's take. A duration is no edition name, but a live recording is another: not when the link
     * or the catalog's edition as long as it says live and the row doesn't, or the other way round; nor when the
     * artist's live take of the song is as long as the link, and so is the link's take.
     */
    private fun namedFallback(
        exact: List<Pair<String, Row>>, rows: List<Row>, names: Names, metadata: MusicMetadata, disco: DiscoRequest
    ): Pair<String, Row>? {
        val sourceLength = metadata.lengthMs?.takeIf { it > 0 } ?: return null
        if (exact.any { fitsReleaseLength(it.second, sourceLength) }) return null
        val catalog = names.linkedEdition ?: names.primaryEdition?.takeIf { sameRecordingLength(sourceLength, it.lengthMs) }
        val live = saysLive(metadata.title) || catalog?.live == true
        val artists = exact.map { it.second.details.first() }.toSet()
        if (rows.any { row -> row.details.first() in artists && otherTakeFits(row, live, sourceLength, metadata.title) }) return null
        return exact.firstOrNull { disco.allows(it.second) && rowIsLive(it.second) == live }
    }

    /** The song's other take, live where the link's isn't or the other way round, as long as the link. */
    private fun otherTakeFits(row: Row, live: Boolean, sourceLength: Long, title: String) =
        rowIsLive(row) != live && fitsReleaseLength(row, sourceLength) && onlyLiveTakeOf(row, title)

    /** The song's name with a live note and nothing else: no remix, no re-recording, as "Song - Live" is. */
    private fun onlyLiveTakeOf(row: Row, title: String): Boolean {
        val album = row.details.getOrNull(1).orEmpty()
        val notes = SongNames.normalize(row.name + album)
        return SongNames.same(SongNames.bareTitle(row.name), title) && !Takes.saysAnotherVersionBesidesLive(row.name) &&
            rerecordingLabels.none { it in notes }
    }

    private fun pickerMayKnowSong(row: Row, metadata: MusicMetadata, names: Names, ambiguous: Boolean): Boolean {
        val artist = row.details.first()
        val mightNameArtist = knownArtist(artist, names) || namedInAnotherScript(artist) || namedInAnotherScript(metadata.artist)
        if (!mightNameArtist && !(saysLive(metadata.title) && rowIsLive(row))) return false
        if (requestedRecording(metadata.title).part == null && numberedPartBeforeNotes(row.name) != null) return false
        if (names.linkedEdition?.let { it.live != rowIsLive(row) } == true) return false
        return !ambiguous || namesTitleInRow(row, names) && !rowIsLive(row)
    }

    private fun pickerNamesDifferentSongTake(row: Row, names: Names): Boolean {
        val album = row.details.getOrNull(1).orEmpty()
        if (videoNamesDifferentEdition(row.name, names) || unexpectedPart(listOf(row.name), names)) return true
        if (karaokeAlbum(album) && names.titles.none { karaokeAlbum(it) }) return true
        val take = pickedVideoTake(row.name, names) ?: Takes.Take.LIVE.takeIf { liveAlbum(album) }
        if (take != null && names.titles.none { namesTake(it, take) }) {
            val catalogNamesThisTake = names.takeOnAlbum && names.corroboratingAlbum == SongNames.normalize(album)
            return take != Takes.Take.LIVE || names.linkedEdition?.live != true && !catalogNamesThisTake
        }
        return false
    }

    private fun namedPickerSong(row: Row, names: Names): Boolean {
        if (namesTitleInRow(row, names) || isTheSong(row, names)) return true
        if (!knownArtist(row.details.first(), names)) return false
        val title = SongNames.bareTitle(row.name).inHiragana(true)
        return names.titles.any { SongNames.same(it.inHiragana(true), title) }
    }

    private fun karaokeAlbum(title: String): Boolean = "karaoke" in SongNames.words(title)

    private fun unexpectedPart(titles: List<String>, names: Names): Boolean =
        titles.any { numberedPartBeforeNotes(it) != null } && names.titles.none { numberedPartBeforeNotes(it) != null }

    private fun conflictingLengths(rows: List<Row>, names: Names, sourceLength: Long?): List<Long> =
        rows.filter { row -> disprovesSongLength(row, names, sourceLength) ||
            isTheSongInAnotherScript(row, names) && !fitsSource(row, sourceLength) }.mapNotNull(Row::lengthMs)

    private fun disprovesSongLength(row: Row, names: Names, sourceLength: Long?): Boolean =
        row.id != null && !fitsReleaseLength(row, sourceLength) && knownArtist(row.details.first(), names) &&
            names.titles.any { SongNames.same(it, row.name) || SongNames.same(it, SongNames.bareTitle(row.name)) }

    private fun knownVideoFitsSource(row: Row, names: Names, sourceLength: Long?, rejectedLengths: List<Long>): Boolean {
        val length = row.lengthMs ?: return false
        if (videoNamesDifferentEdition(row.name, names)) return false
        if (names.lengthsMs.isNotEmpty() && !sameLength(row, names)) return false
        val own = isOwnVideo(row, names)
        if (own && names.appleFound && videoRecordingTitle(row.name, names) == SongNames.normalize(names.requestedTitle)) return true
        if (sourceLength == null || kotlin.math.abs(length - sourceLength) > SAME_LENGTH_MS) return false
        val named = videoTitleMatches(row.name, names, allowAlias = true) || own && explicitlyNamedVideo(row, names)
        return named && (own || rejectedLengths.none { sameRecordingLength(length, it) })
    }

    /**
     * The first title and credit that fits decides, as main's first row did. A later credit, as a collaboration's
     * version, is another recording: an unclear first one goes on to the catalogs, never to it.
     */
    private suspend fun chooseDirectSong(named: List<Pair<String, Row>>, metadata: MusicMetadata, disco: DiscoRequest): Pair<String, Row>? {
        val sourceLength = metadata.lengthMs?.takeIf { it > 0 }
        val fitting = named.filter { (_, row) -> fitsReleaseLength(row, sourceLength) && disco.allows(row) }
        val group = fitting.groupBy { candidateName(it.second) }.values.firstOrNull() ?: return null
        val rows = group.map { it.second }
        fun chosen(explicit: Boolean?) = chooseNamedRecording(rows, metadata.album, metadata.title, disco, explicit)
        val first = chosen(null) ?: return null
        // Apple is asked only when the badge alone tells the explicit and the clean edition apart.
        val row = if (chosen(true) === chosen(false)) first else chosen(sourceExplicitness(metadata))
        return group.first { it.second === row }
    }

    /**
     * Whether the link's edition is explicit, by Apple's editions of the song as long as the link: null when Apple has
     * both, can't say or doesn't answer in time, and then YouTube Music's first row opens, as on main.
     */
    private suspend fun sourceExplicitness(metadata: MusicMetadata): Boolean? {
        val found = withTimeoutOrNull(explicitWaitMs) { appleSong(metadata, Asked(), mutableMapOf()) } ?: return null
        return explicitness(metadata, found.editions)
    }

    private fun explicitness(metadata: MusicMetadata, editions: List<JSONObject>): Boolean? {
        val length = metadata.lengthMs?.takeIf { it > 0 }
        return editions.filter { edition ->
            matches(metadata, edition.optString("trackName"), edition.optString("artistName")) &&
                (length == null || sameRecordingLength(edition.optLong("trackTimeMillis"), length))
        }.mapNotNull { EXPLICITNESS[it.optString("trackExplicitness")] }.distinct().singleOrNull()
    }

    /** A displayed second is rounded, unlike the source duration. An untimed row remains possible. */
    private fun fitsSource(row: Row, sourceLength: Long?): Boolean =
        sourceLength == null || row.lengthMs == null || sameRecordingLength(row.lengthMs, ((sourceLength + 500) / 1000) * 1000)

    private fun fitsReleaseLength(row: Row, sourceLength: Long?): Boolean =
        sourceLength == null || row.lengthMs == null || kotlin.math.abs(row.lengthMs - sourceLength) <= SAME_LENGTH_MS

    /**
     * When a store couldn't name the artist, ask YouTube Music how it credits its own song. The same song ID
     * and one linked artist ID must agree in both replies. A second artist is not another spelling of the first.
     */
    private suspend fun localizedSong(metadata: MusicMetadata, rows: List<JSONObject>, names: Names, recording: RequestedRecording, disco: DiscoRequest): String? {
        val languages = when (SongNames.homeStore(metadata.artist)) {
            "JP" -> listOf("ja")
            "KR" -> listOf("ko")
            // Han alone does not say whether an artist is Japanese or Chinese.
            "TW" -> listOf("ja", "zh-TW")
            else -> return null
        }
        val candidates = rows.mapNotNull(::localizableRow).filter { fitsLocalizedSong(it.row, metadata, names, recording) }.associateBy { it.id }
        if (candidates.isEmpty()) return null
        for (language in languages) {
            val body = YouTubeMusicApi.body(language = language).put("query", searchQuery(metadata)).put("params", YOUTUBE_MUSIC_SONGS)
            val items = answeredOr(emptyList()) { fetchJson(YouTubeMusicApi.SEARCH_URL, body).listItems() }
            val proved = items.mapNotNull(::localizableRow).filter { local ->
                val original = candidates[local.id] ?: return@filter false
                fitsLocalizedSong(local.row, metadata, names, recording) && localizedCredit(metadata.artist, original, local)
            }
            if (proved.isNotEmpty()) {
                val id = proved.map { it.id }.toSet().singleOrNull() ?: return null
                return id.takeIf { disco.allows(candidates.getValue(id).row) && proved.any { disco.allows(it.row) } }
            }
        }
        return null
    }

    private fun fitsLocalizedSong(row: Row, metadata: MusicMetadata, names: Names, recording: RequestedRecording): Boolean {
        val length = metadata.lengthMs?.takeIf { it > 0 }
        val fitsLength = length?.let { sameRecordingLength(row.lengthMs, it) } ?: sameLength(row, names)
        if (!fitsLength) return false
        val album = row.details.getOrNull(1).orEmpty()
        if (SongNames.saysAnotherVersion(album, metadata.title, metadata.artist)) return false
        // One side of a dash could be a recording label, as in "First Love - 2022 Mix".
        return SongNames.normalize(metadata.title) == SongNames.normalize(row.name) &&
            keepsRequestedRecording(recording, row) && !isAnotherTake(row, names)
    }

    private class LocalizableRow(val id: String, val row: Row, val artistId: String)

    /** A song's credit is before the first bullet. Collaborators have separate runs, even in Japanese. */
    private fun localizableRow(item: JSONObject): LocalizableRow? {
        val song = row(item)
        val id = song.id ?: return null
        val credit = item.optJSONArray("flexColumns")?.optJSONObject(1)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")?.optJSONArray("runs").objects().takeWhile { it.optString("text") != " • " }.singleOrNull()
        val browse = credit?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint") ?: return null
        val artist = browse.optString("browseId").takeIf(String::isNotBlank) ?: return null
        return LocalizableRow(id, song, artist)
    }

    private fun localizedCredit(wanted: String, original: LocalizableRow, local: LocalizableRow): Boolean {
        if (original.artistId != local.artistId) return false
        val localName = local.row.details.first()
        if (SongNames.normalize(localName) == SongNames.normalize(wanted)) return true
        val english = SongNames.unaccented(original.row.details.first())
        val native = SongNames.unaccented(wanted)
        val credit = SongNames.unaccented(localName)
        // One linked credit can keep both names, as in "BTS (방탄소년단)". Do not erase the separator of a collaboration.
        return credit in listOf("$english ($native)", "$native ($english)", "$english $native", "$native $english")
    }

    private fun safeVideoBesideAmbiguousSongs(video: Row, metadata: MusicMetadata, names: Names): Boolean {
        val lengths = listOfNotNull(metadata.lengthMs?.takeIf { it > 0 }).ifEmpty { names.lengthsMs }
        val length = video.lengthMs ?: return false
        val title = videoRecordingTitle(video.name, names)
        return names.titles.any { SongNames.normalize(it) == title } && !isAnotherVideoVersion(video.name, names) &&
            lengths.any { sameRecordingLength(length, it) }
    }

    private suspend fun resolveNamesAndCandidate(
        metadata: MusicMetadata, rows: List<Row>, videos: List<Row>, names: Names, recording: RequestedRecording, watchUrl: String, disco: DiscoRequest
    ): Pair<Answer?, Names> = coroutineScope {
        val deezerNames = names.copyForDeezer()
        val apple = async { appleProvedCandidate(metadata, rows, names, recording, disco) }
        val deezer = async { deezerNames.also {
            val ambiguous = indistinguishableCandidates(rows, metadata.lengthMs?.takeIf { length -> length > 0 }).isNotEmpty()
            addDeezerNames(metadata, it, rows.any { row -> row.id != null && row.lengthMs != null }, ambiguous)
        } }
        select {
            apple.onAwait { proved ->
                if (proved != null) {
                    deezer.cancel()
                    Answer(watchUrl + proved.id, lasting = names.everyStore) to names
                } else {
                    val available = deezer.await()
                    mergeAppleEvidence(names, available)
                    null to available
                }
            }
            deezer.onAwait { available ->
                val named = deezerSongRow(metadata, rows, available, recording, disco)
                val fallback = named?.let { Answer(watchUrl + it.id) } ?: matchingVideo(metadata, rows, videos, available, recording, watchUrl, disco)
                if (fallback?.url != null && !apple.isCompleted) {
                    waitForAppleOrFallback(apple, fallback, names, available, watchUrl)
                } else {
                    val proved = apple.await()
                    mergeAppleEvidence(names, available)
                    proved?.let { Answer(watchUrl + it.id, lasting = names.everyStore) } to available
                }
            }
        }
    }

    private suspend fun waitForAppleOrFallback(
        apple: Deferred<Row?>, fallback: Answer, names: Names, available: Names, watchUrl: String
    ): Pair<Answer?, Names> {
        // Give an in-flight Apple proof a short chance to outrank this provisional answer.
        val proved = withTimeoutOrNull(appleProofWaitMs) { apple.await() }
        if (proved != null) return Answer(watchUrl + proved.id, lasting = names.everyStore) to names
        if (apple.isCompleted) {
            mergeAppleEvidence(names, available)
            return null to available
        }
        apple.cancel()
        return Answer(fallback.url, lasting = false, waitForNames = fallback.waitForNames, artistVideo = fallback.artistVideo) to available
    }

    private fun mergeAppleEvidence(names: Names, available: Names) {
        if (!names.complete) available.complete = false
        available.rejectedCandidates.addAll(names.rejectedCandidates)
    }

    private fun sameVideoRecording(first: Row, candidate: Row, metadata: MusicMetadata, names: Names): Boolean {
        val firstTitle = videoRecordingTitle(first.name, names)
        val candidateTitle = videoRecordingTitle(candidate.name, names)
        val knownTitles = names.titles.map(SongNames::normalize).toSet()
        val wanted = metadata.lengthMs?.takeIf { it > 0 }?.let(::listOf) ?: names.lengthsMs
        return sameVideoEvidence(firstTitle, candidateTitle, knownTitles, first.lengthMs, candidate.lengthMs, wanted)
    }

    /** Strip only a whole known artist's prefix; keep recording and language labels in the title. */
    private fun videoRecordingTitle(title: String, names: Names): String {
        val known = names.artists.map(SongNames::normalize).filter(String::isNotEmpty).toSet()
        fun artistBefore(at: Int) = SongNames.normalize(title.substring(0, at)) in known
        title.indexOfFirst { it == '「' || it == '『' }.takeIf { it != -1 }?.takeIf(::artistBefore)?.let { open ->
            val close = title.indexOf(if (title[open] == '「') '」' else '』', open + 1)
            if (close != -1) {
                val after = title.substring(close + 1)
                val suffix = after.takeUnless { presentationVideoSuffix(it) }.orEmpty()
                return SongNames.normalize(title.substring(open + 1, close) + suffix)
            }
        }
        val dash = title.indices.firstOrNull { title[it] in "-–—" && artistBefore(it) }
        if (dash != null) return SongNames.normalize(title.substring(dash + 1))
        title.indexOf(" MV ", ignoreCase = true).takeIf { it != -1 }?.takeIf(::artistBefore)?.let { mv ->
            return SongNames.normalize(title.substring(mv + 4))
        }
        return SongNames.normalize(title)
    }

    private fun presentationVideoSuffix(after: String): Boolean {
        val normalized = SongNames.normalize(after)
        return normalized in PRESENTATION_VIDEO_SUFFIXES
    }

    private fun appleSongRow(rows: List<Row>, names: Names, metadata: MusicMetadata, recording: RequestedRecording, disco: DiscoRequest): Row? {
        val matching = rows.filter { isTheSong(it, names) && keepsRequestedRecording(recording, it) && !isAnotherTake(it, names) }
        val first = matching.firstOrNull() ?: return null
        val sourceLength = metadata.lengthMs?.takeIf { it > 0 }
        val sameName = matching.filter { candidateName(it) == candidateName(first) && disco.allows(it) &&
            fitsNamedSong(it, metadata, names, sourceLength) }
        val abroad = rows.filter { isTheSongInAnotherScript(it, names) && keepsRerecording(recording, it.name, it.details.getOrNull(1).orEmpty()) }
        val base = sameName.firstOrNull() ?: first
        val edition = linkedEditionInAnotherScript(base, abroad, names.linkedEdition)?.takeIf { disco.allows(it) }
        return edition ?: chooseNamedRecording(sameName, metadata.album ?: names.corroboratingAlbum, metadata.title, disco, names.sourceExplicit)
            ?: pickKnownStudioBesideLive(sameName, names)
            ?: sourceLength?.let { sourceNamesBilingualRelease(sameName, metadata.title, it) }
            ?: equivalentAlbumReleases(sameName, names, recording.live).singleOrNull()
    }

    private fun sourceNamesBilingualRelease(rows: List<Row>, title: String, length: Long): Row? {
        if (rows.size < 2) return null
        if (rows.any { rowIsLive(it) || saysTake(it.name) || saysTake(it.details.getOrNull(1).orEmpty()) }) return null
        if (rows.any { row -> !namedInAnotherScript(row.name) || SongNames.titleNames(row.name).drop(1).none { SongNames.same(it, title) } }) return null
        return rows.singleOrNull { fitsSource(it, length) }
    }

    private fun pickKnownStudioBesideLive(rows: List<Row>, names: Names): Row? {
        if (names.corroboratingAlbum == null || names.takeOnAlbum || names.titles.any(::saysLive)) return null
        if (rows.none(::rowIsLive)) return null
        return rows.singleOrNull { !rowIsLive(it) && namesTitleInRow(it, names) }
    }

    private fun fitsNamedSong(row: Row, metadata: MusicMetadata, names: Names, sourceLength: Long?): Boolean {
        names.linkedEdition?.let { return fitsLinkedEdition(row, it) }
        return fitsReleaseLength(row, sourceLength) || appleNamedArtist(metadata, names) && SongNames.same(metadata.title, row.name)
    }

    private fun fitsLinkedEdition(row: Row, edition: LinkedEdition): Boolean {
        val length = row.lengthMs ?: return !rowIsLive(row) || edition.live
        return rowIsLive(row) == edition.live && sameRecordingLength(length, edition.lengthMs)
    }

    private fun chooseNamedRecording(rows: List<Row>, album: String?, requestedTitle: String, disco: DiscoRequest, explicit: Boolean?): Row? {
        val first = rows.firstOrNull() ?: return null
        if (rows.any { disco.marked(it) }) {
            return if (disco.named) rows.first { disco.marked(it) } else first
        }
        val edition = sourceEdition(rows, explicit)
        if (sameDisplayedRows(rows)) return edition
        val namedAlbum = album?.takeIf(String::isNotBlank)?.let { name ->
            rows.singleOrNull { SongNames.normalize(it.details.getOrNull(1).orEmpty()) == SongNames.normalize(name) }
        }
        if (namedAlbum != null) return namedAlbum
        if (saysLive(requestedTitle)) return rows.singleOrNull(::rowIsLive)
        val length = first.lengthMs ?: return null
        // A different release may carry the same studio recording. A version or time disagreement is evidence against that.
        return edition.takeIf {
            SongNames.same(requestedTitle, first.name) && rows.all { row ->
                sameRecordingLength(row.lengthMs, length) && !rowIsLive(row) && !saysTake(row.name) && !saysTake(row.details.getOrNull(1).orEmpty())
            }
        }
    }

    /** A store can connect a row's own name to the song by the same recording ID. */
    private suspend fun appleProvedCandidate(metadata: MusicMetadata, rows: List<Row>, names: Names, recording: RequestedRecording, disco: DiscoRequest): Row? {
        if (names.appleIds.isEmpty()) return null
        val candidates = rows.filter { offersAppleProof(it, metadata, names, recording) }.groupBy(::candidateName).values
            .mapNotNull { group -> sourceEdition(group, names.sourceExplicit).takeIf { sameDisplayedRows(group) } }.take(2)
        return coroutineScope {
            val answers = Channel<Pair<Row?, Boolean>>(Channel.UNLIMITED)
            val searches = candidates.map { candidate -> launch { answers.send(safeAppleCandidateProof(candidate, names)) } }
            repeat(searches.size) {
                val (proved, failed) = answers.receive()
                if (failed) names.complete = false
                if (proved != null && disco.allows(proved)) {
                    searches.forEach { it.cancel() }
                    return@coroutineScope proved
                }
            }
            null
        }
    }

    private fun offersAppleProof(row: Row, metadata: MusicMetadata, names: Names, recording: RequestedRecording): Boolean {
        if (row.id == null || row.lengthMs == null || names.lengthsMs.none { sameRecordingLength(row.lengthMs, it) }) return false
        if (!fitsSource(row, metadata.lengthMs?.takeIf { it > 0 }) || !namedInAnotherScript(row.name) || isAnotherTake(row, names)) return false
        return keepsRequestedRecording(recording, row) &&
            !SongNames.saysAnotherVersion(row.name, metadata.title, metadata.artist) &&
            !SongNames.saysAnotherVersion(row.details.getOrNull(1).orEmpty(), metadata.title, metadata.artist)
    }

    private suspend fun safeAppleCandidateProof(candidate: Row, names: Names): Pair<Row?, Boolean> = try {
        appleCandidateProof(candidate, names)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // A broken candidate response must not cancel evidence from another store.
        null to true
    }

    private suspend fun appleCandidateProof(candidate: Row, names: Names): Pair<Row?, Boolean> {
        val artist = candidate.details.first()
        val wanted = MusicMetadata(candidate.name, artist, lengthMs = candidate.lengthMs)
        val store = SongNames.homeStore(candidate.name + artist) ?: return null to false
        val asked = Asked()
        val answer = appleSong(wanted, store, asked, mutableMapOf())
        val matching = answer?.editions.orEmpty().filter { edition ->
            matches(wanted, edition.optString("trackName"), edition.optString("artistName")) &&
                sameRecordingLength(candidate.lengthMs, edition.optLong("trackTimeMillis"))
        }
        val source = matching.any { it.optLong("trackId") in names.appleIds }
        val conflictingTake = matching.any { edition ->
            edition.optLong("trackId") !in names.appleIds &&
                SongNames.saysAnotherVersion(edition.optString("collectionName"), wanted.title, wanted.artist)
        }
        if (conflictingTake) candidate.id?.let(names.rejectedCandidates::add)
        val proved = candidate.takeIf { source && !conflictingTake }
        return proved to asked.failed
    }

    private fun songRow(metadata: MusicMetadata, rows: List<Row>, videos: List<Row>, names: Names, recording: RequestedRecording, appleSong: Row?, disco: DiscoRequest): Row? {
        // An Apple-proved row wins even when a Deezer-only row comes first.
        appleSong?.let { return it }
        val abroad = rowsBesideAnArtistVideo(rows, videos, names, recording, metadata)
        val named = deezerSongRow(metadata, rows, names, recording, disco)
        if (named != null) return linkedEditionInAnotherScript(named, abroad, names.linkedEdition)?.takeIf { disco.allows(it) } ?: named
        abroad.singleOrNull { disco.allows(it) && catalogParenthesizedName(it, names) }?.let { return it }
        abroad.singleOrNull { disco.allows(it) && repeatsSourceTitle(it.name, metadata.title) }?.let { return it }
        val shorterList = abroad.takeUnless { hasNamedTake(rows, names, recording) }.orEmpty()
        return (videoNamesSong(metadata, abroad, videos, names) ?: anotherScriptRecording(shorterList, recording, names))?.takeIf { disco.allows(it) }
    }

    private fun rowsBesideAnArtistVideo(
        rows: List<Row>, videos: List<Row>, names: Names, recording: RequestedRecording, metadata: MusicMetadata
    ): List<Row> {
        val ownVideo = videos.any { isOwnVideo(it, names) && safeVideoBesideAmbiguousSongs(it, metadata, names) &&
            keepsRequestedRecording(recording, it, video = true) }
        val ambiguous = indistinguishableCandidates(rows, metadata.lengthMs?.takeIf { it > 0 })
        return anotherScriptRows(rows, names, recording, metadata).filter { row ->
            !ownVideo || namesTitleInRow(row, names) || candidateName(row) !in ambiguous
        }
    }

    private fun hasNamedTake(rows: List<Row>, names: Names, recording: RequestedRecording): Boolean =
        // A row the song's name names, but that says it's a take, is the song's, not its studio recording: no row only known by its
        // artist and length takes its place, as the song may be written otherwise, and this one is the song by name.
        rows.any { (isTheSong(it, names) || isDeezerSong(it, names)) && keepsRequestedRecording(recording, it) }

    private fun candidateName(row: Row) = row.candidateName

    private fun deezerSongRow(metadata: MusicMetadata, rows: List<Row>, names: Names, recording: RequestedRecording, disco: DiscoRequest): Row? {
        val ambiguous = indistinguishableCandidates(rows, metadata.lengthMs?.takeIf { it > 0 })
        val eligible = rows.filter { isDeezerSong(it, names) && keepsRequestedRecording(recording, it) && !isAnotherTake(it, names) &&
            !SongNames.saysAnotherVersion(it.name, metadata.title, metadata.artist) &&
            !SongNames.saysAnotherVersion(it.details.getOrNull(1).orEmpty(), metadata.title, metadata.artist) }
        val byAlbum = eligible.filter { row -> names.deezerNames.any { name ->
            name.album.isNotBlank() && SongNames.same(name.album, row.details.getOrNull(1).orEmpty())
        } }.groupBy(::candidateName).mapNotNull { (name, group) -> group.singleOrNull()?.let { name to it.id } }.toMap()
        return eligible.firstOrNull { disco.allows(it) && (candidateName(it) !in ambiguous || byAlbum[candidateName(it)] == it.id) }
    }

    /** An explicit and a clean edition are one recording: the link's badge picks, or else the first. */
    private fun sourceEdition(rows: List<Row>, explicit: Boolean?) = rows.firstOrNull { it.explicit == explicit } ?: rows.first()

    /** The same name and second line. An explicit and a clean edition of one recording differ only by the badge. */
    private fun sameDisplayedRows(rows: List<Row>) = rows.map { it.name to it.details }.distinct().size == 1

    private fun anotherScriptRows(rows: List<Row>, names: Names, recording: RequestedRecording, metadata: MusicMetadata): List<Row> {
        val sourceLength = metadata.lengthMs?.takeIf { it > 0 }
        val sameArtist = rows.filter { knownArtist(it.details.first(), names) }
        val plausible = sameArtist.filter { plausibleInAnotherScript(it, names, recording) }
        val wantsLive = saysLive(metadata.title)
        val ambiguityRows = if (!wantsLive && !names.takeOnAlbum && names.corroboratingAlbum != null) {
            sameArtist.filterNot { isAnotherTake(it, names) }
        } else sameArtist
        val ambiguous = indistinguishableCandidates(ambiguityRows, sourceLength)
        val eligible = eligibleUntranslatedRows(plausible, rows, names, sourceLength)
        val known = eligible.filter { knownUntranslatedTitle(it, names) }
        val byAlbum = albumVerifiedRows(known, names, metadata, recording)
        val byVersion = versionSeparatedRows(rows, known, names, recording)
        val byRelease = equivalentAlbumReleases(eligible, names, wantsLive)
        val byRequestedLive = if (wantsLive) uniqueLiveRows(eligible) else emptySet()
        return eligible.filter { row ->
            val name = candidateName(row)
            name !in ambiguous || byAlbum[name] == row.id || byVersion[name] == row.id ||
                row in byRelease || row in byRequestedLive
        }
    }

    private fun eligibleUntranslatedRows(plausible: List<Row>, rows: List<Row>, names: Names, sourceLength: Long?): List<Row> {
        val markedAliases = rows.filter { isAnotherTake(it, names) && Takes.saysAnotherTake(it.name, names.artists) != null }
            .map(::candidateName).toSet()
        return plausible.filter {
            fitsUntranslatedSong(it, names, sourceLength) && candidateName(it) !in markedAliases &&
                !unprovedSubstitution(it, plausible, rows, names, sourceLength)
        }
    }

    private fun knownUntranslatedTitle(row: Row, names: Names): Boolean = names.titles.any { title ->
        SongNames.same(title, row.name) || samePart(numberedPartBeforeNotes(title), numberedPartBeforeNotes(row.name))
    }

    private fun albumVerifiedRows(
        known: List<Row>, names: Names, metadata: MusicMetadata, recording: RequestedRecording
    ): Map<Pair<String, String>, String?> {
        val album = names.corroboratingAlbum.takeIf { plainLinkedTitle(metadata) || recording.part != null } ?: return emptyMap()
        return known.filter { SongNames.normalize(it.details.getOrNull(1).orEmpty()) == album &&
            (names.takeOnAlbum || !isAnotherVideoVersion(it.name, names)) }
            .groupBy(::candidateName).mapNotNull { (name, group) -> group.singleOrNull()?.let { name to it.id } }.toMap()
    }

    private fun plausibleInAnotherScript(row: Row, names: Names, recording: RequestedRecording): Boolean =
        row.id != null && row.id !in names.rejectedCandidates && isTheSongInAnotherScript(row, names) &&
            keepsRerecording(recording, row.name, row.details.getOrNull(1).orEmpty())

    private fun fitsUntranslatedSong(row: Row, names: Names, sourceLength: Long?): Boolean {
        if (unprovedParenthesizedName(row, names)) return false
        if (names.ambiguousSourceEdition && !namesTitleInRow(row, names)) return false
        val edition = names.linkedEdition
        if (edition == null) return fitsSource(row, sourceLength)
        return fitsLinkedEdition(row, edition)
    }

    private fun unprovedParenthesizedName(row: Row, names: Names): Boolean {
        val alias = parenthesizedAlias(row.name) ?: return false
        if (!namedInAnotherScript(alias.first) || !SongNames.same(names.requestedTitle, alias.second)) return false
        return !catalogParenthesizedName(row, names)
    }

    private fun catalogParenthesizedName(row: Row, names: Names): Boolean {
        val (base, alias) = parenthesizedAlias(row.name) ?: return false
        if (!namedInAnotherScript(base) || !SongNames.same(names.requestedTitle, alias)) return false
        return names.titles.any { SongNames.same(it, base) } ||
            names.appleFound && SongNames.same(row.details.getOrNull(1).orEmpty(), names.requestedTitle)
    }

    private fun uniqueLiveRows(eligible: List<Row>): Set<Row> =
        eligible.filter(::rowIsLive).groupBy(::candidateName).values.mapNotNull { it.singleOrNull() }.toSet()

    /** Keep the usual release beside a compilation when both carry the same unmarked studio take. */
    private fun equivalentAlbumReleases(rows: List<Row>, names: Names, live: Boolean): Set<Row> {
        if (live || !names.appleFound) return emptySet()
        return rows.groupBy(::candidateName).values.mapNotNull { group ->
            val primary = names.primaryEdition
            if (primary != null && group.size > 1) {
                val atPrimary = group.filter { fitsLinkedEdition(it, primary) }
                if (atPrimary.size == 1 && group.any { rowIsLive(it) != primary.live }) return@mapNotNull atPrimary.first()
            }
            val first = group.first()
            val length = first.lengthMs ?: return@mapNotNull null
            if (group.none { "best" in SongNames.words(it.details.getOrNull(1).orEmpty()) }) return@mapNotNull null
            first.takeIf { group.size > 1 && group.all { !rowIsLive(it) && it.lengthMs == length } }
        }.toSet()
    }

    private fun unprovedSubstitution(row: Row, plausible: List<Row>, rows: List<Row>, names: Names, sourceLength: Long?): Boolean {
        if (sourceLength == null) return false
        val first = plausible.takeWhile { it !== row }.firstOrNull { !fitsSource(it, sourceLength) }
        // A live take's length says nothing of the studio recording's, nor the other way round.
        val conflict = rows.any { disprovesSongLength(it, names, sourceLength) && rowIsLive(it) == rowIsLive(row) } ||
            first != null && candidateName(first) != candidateName(row)
        if (!conflict) return false
        if (namesTitleInRow(row, names) || catalogParenthesizedName(row, names) || repeatsSourceTitle(row.name, names.requestedTitle)) return false
        val edition = names.linkedEdition
        return edition == null || rowIsLive(row) != edition.live ||
            first == null || !SongNames.same(SongNames.bareTitle(first.name), SongNames.bareTitle(row.name))
    }

    private fun namesTitleInRow(row: Row, names: Names): Boolean = SongNames.titleNames(row.name).any { part ->
        names.titles.any { SongNames.same(it, part) || SongNames.same(it, SongNames.bareTitle(part)) }
    }

    /** The same Latin name twice, once with the other script and once with its reading. */
    private fun repeatsSourceTitle(row: String, title: String): Boolean {
        val parts = row.split(" - ", limit = 3)
        if (parts.size != 2) return false
        val native = parenthesizedAlias(parts.first()) ?: return false
        val reading = parenthesizedAlias(parts.last()) ?: return false
        if (!SongNames.same(native.first, title) || !SongNames.same(reading.first, title)) return false
        if (!namedInAnotherScript(native.second) || !reading.second.hasLatin()) return false
        return SongNames.same(Readings.of(listOf(native.second), Script.JAPANESE).single().typed, reading.second)
    }

    private fun versionSeparatedRows(rows: List<Row>, eligible: List<Row>, names: Names, recording: RequestedRecording): Map<Pair<String, String>, String?> {
        if (names.corroboratingAlbum == null || names.takeOnAlbum) return emptyMap()
        val live = recording.live || names.titles.any(::saysLive)
        return eligible.filter { row -> rowIsLive(row) == live && (live || !isAnotherVideoVersion(row.name, names)) }.groupBy(::candidateName)
            .filter { (name, _) -> rows.any { candidateName(it) == name && rowIsLive(it) != live } }
            .mapNotNull { (name, group) -> group.singleOrNull()?.let { name to it.id } }.toMap()
    }

    private fun indistinguishableCandidates(rows: List<Row>, sourceLength: Long?): Set<Pair<String, String>> = rows.filter { row ->
        row.id != null && fitsSource(row, sourceLength)
    }.groupBy(::candidateName).filterValues { group -> group.map(Row::id).distinct().size > 1 && !sameDisplayedRows(group) }.keys

    private fun matchingVideo(metadata: MusicMetadata, songs: List<Row>, videos: List<Row>, names: Names, recording: RequestedRecording, watchUrl: String, disco: DiscoRequest): Answer? {
        val known = videos.filter { isTheSongsVideo(it, names) && keepsRequestedRecording(recording, it, video = true) }
        val matching = if (known.any { disco.allows(it, video = true) }) known else {
            videos.filter { isTheSongsVideo(it, names, allowAlias = true) && keepsRequestedRecording(recording, it, video = true) }
        }
        val first = matching.firstOrNull { disco.allows(it, video = true) } ?: return null
        val artist = matching.firstOrNull { isOwnVideo(it, names) && disco.allows(it, video = true) && (it === first || sameVideoRecording(first, it, metadata, names)) }
            ?.id?.let { watchUrl + it }
        // A later lookup can use the artist's video to replace what an outage left it with.
        if (names.everyStore) return Answer(watchUrl + first.id, artistVideo = artist)
        if (artist != null) return Answer(artist, lasting = false)
        return fanVideoDuringOutage(songs, videos, matching, names, recording, watchUrl, disco)
    }

    private fun fanVideoDuringOutage(
        songs: List<Row>, videos: List<Row>, matching: List<Row>, names: Names, recording: RequestedRecording, watchUrl: String, disco: DiscoRequest
    ): Answer? {
        // A failed search may have lost a length, even if there is no song row.
        if (names.searchFailed) return null
        // A title can hide an unconfirmed name in brackets, as on a piano upload.
        val fans = matching.filterNot { names.lookupCouldName(unconfirmedVideoText(it.name, names)) }
        val fan = fans.firstOrNull { disco.allows(it, video = true) } ?: return Answer(null, final = false, lasting = false)
        // A lookup can prove a playable song in its length window, or a video that could be the artist's.
        val competingSong = !names.complete && songs.any { it.id != null && sameLength(it, names) }
        val competingVideo = videos.any { it.id != fan.id && it !in fans && couldBeOwnVideo(it, names, recording) }
        return Answer(watchUrl + fan.id, lasting = false, waitForNames = competingSong || competingVideo)
    }

    private fun couldBeOwnVideo(row: Row, names: Names, recording: RequestedRecording): Boolean {
        if (row.id == null || !keepsRequestedRecording(recording, row, video = true)) return false
        val channel = row.details.first().removeSuffix(" - Topic")
        val knownChannel = isOwnVideo(row, names)
        if (!knownChannel && !names.lookupCouldName(channel)) return false
        if (videoTitleMatches(row.name, names)) return true
        if (isAnotherVideoVersion(row.name, names) || !names.lookupCouldName(unconfirmedVideoText(row.name, names))) return false
        // Close videos can need both names. An artist's video may run long when one name is already known.
        val closeLength = row.lengthMs != null && sameLength(row, names)
        return knownChannel || closeLength || sameJapaneseVideoTitle(row.name, names)
    }

    private fun sameJapaneseVideoTitle(title: String, names: Names): Boolean {
        if (title.none(Readings::isKana)) return false
        val latin = names.titles.filter(String::hasLatin)
        if (latin.isEmpty()) return false
        val reading = Readings.of(listOf(title), Script.JAPANESE).single().typed
        return latin.any { SongNames.same(it, reading) }
    }

    /** What remains of the video's title, including brackets, after removing known names and tags. */
    private fun unconfirmedVideoText(title: String, names: Names): String {
        val known = names.titles.map(SongNames::withoutEditionTag) + names.artists.flatMap(SongNames::artistNames)
        val words = SongNames.words(title) - known.flatMap(SongNames::words).toSet() - VIDEO_TAGS
        val withoutNames = removeVideoNames(title, known.map(SongNames::normalize) + VIDEO_TAG_MARKS)
        return withoutNames.filter { it.code > 0x7f } + words.joinToString(" ")
    }

    /** Keep a recording note separate when an unbracketed guest credit runs up to it. */
    private fun keepNotesAfterGuests(name: String): String {
        val title = name.trim()
        val base = SongNames.bareTitle(title)
        if (base == title || !title.startsWith(base)) return name
        return SongNames.withoutFeaturing(base).trimEnd() + title.substring(base.length)
    }

    /** The songs or videos among [items] that play. */
    private fun playable(items: List<JSONObject>): List<Row> = items.map(::row).filter { it.id != null }

    /** The link's length can tell which recording it plays when a plain title left its recording off. */
    private fun namedLinkedEdition(metadata: MusicMetadata, items: List<JSONObject>, firstLength: Long?): Row? {
        val link = metadata.lengthMs ?: return null
        if (firstLength == null || kotlin.math.abs(firstLength - link) <= SAME_LENGTH_MS || !plainLinkedTitle(metadata)) return null
        val recording = requestedRecording(metadata.title)
        return playable(items).singleOrNull {
            keepsRerecording(recording, it.name, it.details.getOrNull(1).orEmpty()) && sameRecordingLength(it.lengthMs, link) &&
                sameArtist(metadata, it.details.first()) && SongNames.same(metadata.title, SongNames.bareTitle(it.name)) &&
                takeSaysLive(it.name) == true && liveAlbum(it.details.getOrNull(1).orEmpty())
        }
    }

    private fun saysLive(text: String): Boolean {
        val title = text.trim()
        val base = SongNames.bareTitle(title)
        val note = title.removePrefix(base).ifEmpty { title }.trim { !it.isLetterOrDigit() }.lowercase(Locale.ROOT)
        val quoted = title.substringAfterLast('«', "").substringBefore('»', "").trim()
        return quoted.equals("live", ignoreCase = true) || liveAtEnd(title) || liveAtEnd(base) || note in liveLabels
    }

    private fun liveAlbum(text: String): Boolean {
        val album = text.trim().lowercase(Locale.ROOT)
        return saysLive(album) || liveAlbumStarts.any(album::startsWith)
    }

    private val liveLabels = setOf(
        "live", "live ver", "live version", "en vivo", "ao vivo", "ライブ", "ライヴ", "ライブバージョン", "ライヴバージョン",
        "现场版", "現場版", "라이브", "라이브버전"
    )
    private val liveAlbumStarts = listOf("live at ", "live in ", "live from ")

    private fun MusicMetadata.requestsDisco(): Boolean = Takes.requestsDisco(title) || Takes.requestsDisco(album.orEmpty())

    /** A disco note can rule out opening its row, not using it as evidence. */
    private inner class DiscoRequest(metadata: MusicMetadata) {
        val named = metadata.requestsDisco()
        private val length = metadata.lengthMs?.takeIf { it > 0 }

        fun marked(row: Row, video: Boolean = false): Boolean =
            Takes.namesDisco(row.name) || !video && Takes.namesDisco(row.details.getOrNull(1).orEmpty())

        fun allows(row: Row, video: Boolean = false): Boolean {
            if (named) return true
            if (!marked(row, video)) return true
            val wanted = length ?: return false
            return sameRecordingLength(row.lengthMs?.takeIf { it > 0 }, wanted)
        }
    }

    private class RequestedRecording(val live: Boolean, val part: NumberedPart?, val title: String)

    /** These labels name a new performance, even when it runs as long as the original. */
    private val rerecordingLabels = listOf("fromthefirsttake", "重生版")

    private class NumberedPart(val title: String, val number: String)

    private fun numberedPart(title: String): NumberedPart? {
        val text = title.trimEnd { it.isWhitespace() || it in TitleDelimiters.CLOSES || it == '»' }
        val number = text.takeLastWhile { it in '0'..'9' }
        if (number.isEmpty()) return romanPart(text)
        return partWithLabel(text.dropLast(number.length), number.trimStart('0').ifEmpty { "0" })
    }

    private fun partWithLabel(beforeNumber: String, number: String): NumberedPart? {
        val before = beforeNumber.trimEnd().removeSuffix(".")
        val label = before.takeLastWhile(Char::isLetter)
        if (!label.equals("part", ignoreCase = true) && !label.equals("pt", ignoreCase = true)) return null
        val name = before.dropLast(label.length).trimEnd { it.isWhitespace() || it in TitleDelimiters.OPENS || it in ",:«" }
        return NumberedPart(name, number)
    }

    // A localized title may spell the same part with a Unicode Roman numeral after its name.
    private fun romanPart(title: String): NumberedPart? {
        val numeral = title.lastOrNull() ?: return null
        if (numeral !in '\u2160'..'\u216B' && numeral !in '\u2170'..'\u217B') return null
        val before = title.dropLast(1)
        val number = Character.getNumericValue(numeral).toString()
        partWithLabel(before, number)?.let { return it }
        val separator = before.lastOrNull() ?: return null
        if (!separator.isWhitespace() && separator !in TitleDelimiters.OPENS) return null
        return NumberedPart(before.trimEnd { it.isWhitespace() || it in TitleDelimiters.OPENS }, number)
    }

    private fun liveAtEnd(title: String): Boolean {
        if (title.substringAfterLast(':', "").trim().equals("live", ignoreCase = true)) return true
        val quoted = title.substringAfterLast('«', "")
        if (quoted.endsWith('»') && quoted.dropLast(1).trim().equals("live", ignoreCase = true)) return true
        val word = title.takeLastWhile { !it.isWhitespace() }
        return when (word.lowercase(Locale.ROOT)) {
            "ver", "ver.", "version" ->
                title.dropLast(word.length).trimEnd().takeLastWhile { !it.isWhitespace() }.equals("live", ignoreCase = true)
            else -> false
        }
    }

    private fun requestedRecording(title: String): RequestedRecording {
        val text = title.trim()
        return RequestedRecording(liveAtEnd(text), numberedPart(text), SongNames.normalize(text))
    }

    private fun keepsRequestedRecording(wanted: RequestedRecording, name: String, album: String, fullName: String = name): Boolean {
        val part = wanted.part?.number
        val keepsPart = part == null || part == (numberedPartBeforeNotes(name) ?: partOnAlbum(name, album))?.number
        if (!keepsRerecording(wanted, fullName, album)) return false
        return keepsPart && (!wanted.live || saysLive(name) || liveAlbum(album))
    }

    private fun keepsRerecording(wanted: RequestedRecording, name: String, album: String): Boolean {
        val labels = SongNames.normalize("$name $album")
        return rerecordingLabels.none { it in labels && it !in wanted.title }
    }

    private fun partOnAlbum(name: String, album: String): NumberedPart? =
        numberedPartBeforeNotes(album)?.takeIf { SongNames.same(it.title, SongNames.bareTitle(name)) }

    private fun keepsRequestedRecording(wanted: RequestedRecording, row: Row, video: Boolean = false): Boolean =
        if (video) SongNames.titleNames(row.name).any { keepsRequestedRecording(wanted, it, "", fullName = row.name) }
        else keepsRequestedRecording(wanted, row.name, row.details.getOrNull(1).orEmpty())

    private fun anotherScriptRecording(rows: List<Row>, recording: RequestedRecording, names: Names): Row? {
        // A numbered song needs a known name with that part. A candidate can add a recording note after its number.
        if (recording.part != null) return rows.firstOrNull { row ->
            val part = numberedPartBeforeNotes(row.name)
            part != null && part.number == recording.part.number && names.titles.any { samePart(numberedPartBeforeNotes(it), part) }
        }
        val first = rows.firstOrNull() ?: return null
        if (recording.live && !keepsRequestedRecording(recording, first)) {
            val base = SongNames.bareTitle(first.name)
            return rows.singleOrNull { keepsRequestedRecording(recording, it) && SongNames.bareTitle(it.name) == base }
        }
        return linkedEditionInAnotherScript(first, rows, names.linkedEdition)
            ?: sourceEdition(rows.filter { sameDisplayedRows(listOf(first, it)) }, names.sourceExplicit)
    }

    /** An artist's video can name a store recording both ways, when its album and length also match. */
    private fun videoNamesSong(metadata: MusicMetadata, rows: List<Row>, videos: List<Row>, names: Names): Row? {
        if (rows.size < 2) return null
        val album = names.corroboratingAlbum ?: return null
        if (!plainLinkedTitle(metadata)) return null
        val title = SongNames.normalize(metadata.title)
        val aliases = videos.filter { isTheSongsVideo(it, names) && isOwnVideo(it, names) && it.lengthMs != null && sameLength(it, names) }
            .mapNotNull { video ->
                val halves = video.name.split(" - ").takeIf { it.size == 2 } ?: return@mapNotNull null
                val own = halves.indexOfFirst { SongNames.normalize(it) == title }
                halves.getOrNull(1 - own)?.let(SongNames::normalize)
            }.toSet()
        return rows.singleOrNull { SongNames.normalize(it.name) in aliases && SongNames.normalize(it.details.getOrNull(1).orEmpty()) == album }
    }

    /** Keep an explicit title ahead of any inference from the length. */
    private fun plainLinkedTitle(metadata: MusicMetadata): Boolean {
        val title = SongNames.withoutEditionTag(metadata.title)
        return SongNames.bareTitle(title) == title.trim() && !SongNames.saysAnotherVersion(title, "", metadata.artist) &&
            numberedPart(title) == null
    }

    /** Compare the matched row with one alternate take of its own title, at the other known recording's time. */
    private fun linkedEditionInAnotherScript(first: Row, rows: List<Row>, edition: LinkedEdition?): Row? {
        if (edition == null || first !in rows || sameRecordingLength(first.lengthMs, edition.lengthMs)) return null
        val base = SongNames.bareTitle(first.name)
        val other = rows.singleOrNull { it !== first && SongNames.same(SongNames.bareTitle(it.name), base) } ?: return null
        if (!sameRecordingLength(other.lengthMs, edition.lengthMs)) return null
        val titleSaysLive = takeSaysLive(other.name) ?: return null
        if ((titleSaysLive || liveAlbum(other.details.getOrNull(1).orEmpty())) != edition.live) return null
        val firstMarked = rowIsLive(first) || SongNames.saysAnotherVersion(SongNames.withoutFeaturing(first.name), base, "")
        return other.takeIf { firstMarked != edition.live }
    }

    /** Read each note on its own so a guest credit cannot hide a later recording label. */
    private fun takeSaysLive(name: String): Boolean? {
        val title = name.trim()
        val base = SongNames.bareTitle(title)
        if (!title.startsWith(base)) return null
        var at = base.length
        var live = false
        while (at < title.length) {
            if (title[at].isWhitespace()) {
                at++
                continue
            }
            val bracket = TitleDelimiters.OPENS.indexOf(title[at])
            val end = takeNoteEnd(title, at, bracket) ?: return null
            val note = title.substring(at, end)
            val label = note.trim { !it.isLetterOrDigit() }.lowercase(Locale.ROOT)
            when {
                label in liveLabels -> live = true
                bracket >= 0 && SongNames.withGuests("", note).isNotBlank() && (at + 1 until end).none { takeDash(title, it) } -> Unit
                neutralTakeNote(label) -> Unit
                else -> return null
            }
            at = end
        }
        return live
    }

    private fun takeNoteEnd(title: String, at: Int, bracket: Int): Int? {
        if (bracket >= 0) return title.indexOf(TitleDelimiters.CLOSES[bracket], at + 1).takeIf { it >= 0 }?.plus(1)
        if (!takeDash(title, at)) return null
        return (at + 1 until title.length).firstOrNull { takeDash(title, it) || title[it] in TitleDelimiters.OPENS } ?: title.length
    }

    private fun takeDash(title: String, at: Int): Boolean = title[at] in TitleDelimiters.DASHES &&
        title.getOrNull(at - 1)?.isWhitespace() == true && title.getOrNull(at + 1)?.isWhitespace() == true

    private fun neutralTakeNote(label: String): Boolean {
        if (label in neutralTakeLabels) return true
        val words = SongNames.words(label)
        return words.any { it in remasterLabels } && words.all { it in remasterLabels || it.length == 4 && it.all(Char::isDigit) }
    }

    private val neutralTakeLabels = setOf("mono", "stereo", "original", "album version")
    private val remasterLabels = setOf("remaster", "remastered", "remasterizado", "remasterizada")

    private fun rowIsLive(row: Row): Boolean = saysLive(row.name) || liveAlbum(row.details.getOrNull(1).orEmpty())

    /**
     * The one among [rows] that [pick] says the song is, each shown as "title | details". Their titles
     * are anyone's, and may say anything, so one far from all of the song's [lengthsMs] isn't taken: a few
     * seconds for a song, more for a video, which may open with more.
     */
    private suspend fun pickedFrom(search: ToPick, videos: Boolean): String? {
        val metadata = search.metadata
        val rows = if (videos) search.videos else search.songs
        val recording = requestedRecording(metadata.title)
        val disco = DiscoRequest(metadata)
        val part = recording.part
        // Keep the row numbers while omitting any names that local evidence already rules out.
        val offered = rows.map { it.takeIf { row -> disco.allows(row, video = videos) && keepsRequestedRecording(recording, row, video = videos) &&
            (if (videos) pickerMayKnowVideo(row, search) else row.id in search.songIds) &&
            (part == null || namesThePickedPart(part, row, videos)) } }
        if (offered.none { it != null }) return null
        val shown = offered.map { it?.let { row -> "${row.name} | ${row.details.joinToString(" • ")}" }.orEmpty() }
        val index = pick(metadata, shown, videos) ?: return null
        val row = offered.getOrNull(index) ?: return null
        val off = row.lengthMs?.let { length -> search.names.lengthsMs.minOfOrNull { kotlin.math.abs(it - length) } } ?: 0L
        return (search.watchUrl + row.id).takeIf { off <= if (videos) PICKED_VIDEO_LENGTH_MS else SAME_LENGTH_MS }
    }

    private fun pickerMayKnowVideo(row: Row, search: ToPick): Boolean {
        if (videoNamesDifferentRecording(row, search.names) ||
            unexpectedPart(SongNames.titleNames(row.name), search.names)) return false
        val take = pickedVideoTake(row.name, search.names)
        if (take != null && search.names.titles.none { namesTake(it, take) }) return false
        if (search.conflictingLengths.isNotEmpty()) {
            return knownVideoFitsSource(row, search.names, search.metadata.lengthMs, search.conflictingLengths)
        }
        if (requestedRerecordingVideo(row, search.metadata)) return true
        return videoTitleMatches(row.name, search.names, allowAlias = true) || explicitlyNamedVideo(row, search.names) ||
            !search.requiresNamedVideo && namedInAnotherScript(SongNames.bareTitle(row.name)) &&
            search.names.titles.none { namedInAnotherScript(SongNames.bareTitle(it)) }
    }

    private fun videoNamesDifferentRecording(row: Row, names: Names): Boolean =
        videoNamesDifferentEdition(row.name, names) || tooShortToBeTheSong(row, names)

    /** A video can give the artist, title and recording label in three different dash-delimited fields. */
    private fun namesVideoSource(row: Row, names: Names): Boolean = namesTitleInRow(row, names) ||
        row.name.split(" - ").any { field -> names.titles.any { SongNames.same(it, field) } }

    /** With a catalog recording to compare to, a video less than half as long cannot be the whole song. */
    private fun tooShortToBeTheSong(row: Row, names: Names): Boolean {
        val length = row.lengthMs ?: return false
        val recorded = names.lengthsMs.minOrNull() ?: return false
        return length < recorded / 2 && names.titles.none(::saysTake)
    }

    /** A dash can introduce the title, not a take. "Artist - Short Hair" is not a short version. */
    private fun pickedVideoTake(name: String, names: Names): Takes.Take? {
        val parts = name.split(" - ", limit = 3)
        val title = parts.getOrNull(1)?.takeIf { right ->
            parts.size == 2 && names.titles.any { SongNames.same(SongNames.bareTitle(it), SongNames.bareTitle(right)) }
        }
        return Takes.saysAnotherTake(title ?: name, names.artists, includeUnclearLive = false)
    }

    private fun requestedRerecordingVideo(row: Row, metadata: MusicMetadata): Boolean {
        val requested = SongNames.normalize(metadata.title)
        val candidate = SongNames.normalize(row.name)
        return rerecordingLabels.any { it in requested && it in candidate }
    }

    private fun namesTake(title: String, take: Takes.Take): Boolean = take == Takes.saysAnotherTake(title) || take == Takes.Take.LIVE && saysLive(title)

    private fun explicitlyNamedVideo(row: Row, names: Names): Boolean = SongNames.titleNames(withoutShowCredit(row.name)).any { title ->
        val beforeCaption = withoutLyricsCaption(SongNames.bareTitle(title))
        val base = SongNames.bareTitle(beforeCaption).substringBefore('～')
        names.titles.any { known ->
            SongNames.sameVersion(known, title) || SongNames.same(known, base) ||
                samePart(numberedPartBeforeNotes(known), numberedPartBeforeNotes(title))
        }.let { it || sameLatinSpelling(base, names) || videoReadingMatches(base, names) } && !videoNamesDifferentEdition(title, names)
    }

    /** Read from the end so a long run of spaces before the caption is only read once. */
    private fun withoutLyricsCaption(title: String): String {
        val beforeVideo = title.beforeTrailingWord("video") ?: title
        return beforeVideo.beforeTrailingWord("lyrics") ?: title
    }

    private fun String.beforeTrailingWord(word: String): String? {
        val start = length - word.length
        if (start < 1 || !isPatternSpace(this[start - 1]) || !endsWith(word, ignoreCase = true)) return null
        return substring(0, start).trimEnd(::isPatternSpace)
    }

    private fun videoReadingMatches(title: String, names: Names): Boolean =
        title.isNotEmpty() && title.all { it in '\u3041'..'\u3096' || it in '\u30a1'..'\u30fa' } &&
            names.romanizedJapanese?.let { SongNames.same(it, Readings.of(listOf(title), Script.JAPANESE).single().typed) } == true

    private fun namesThePickedPart(part: NumberedPart, row: Row, video: Boolean): Boolean {
        val names = if (video) SongNames.titleNames(row.name) else listOf(row.name)
        return names.any { samePart(part, numberedPartBeforeNotes(it)) } ||
            !video && samePart(part, partOnAlbum(row.name, row.details.getOrNull(1).orEmpty()))
    }

    /**
     * The song as a video on YouTube, which YouTube Music plays too: e.g. one its artist put up but
     * never released. A video's title names more, "Artist - Song (Official Video)", so it counts when
     * it's the song's words, its artist's and nothing else but such tags; a remix or a live take has
     * words of its own and stays out.
     */
    private suspend fun searchVideosOnYouTubeMusic(metadata: MusicMetadata): List<JSONObject> {
        val body = YouTubeMusicApi.body().put("query", searchQuery(metadata)).put("params", YOUTUBE_MUSIC_VIDEOS)
        return fetchJson(YouTubeMusicApi.SEARCH_URL, body).listItems()
    }

    /** A YouTube Music search result: its video, its name, its second line split up, and its length when it says. */
    private class Row(val id: String?, val name: String, val details: List<String>, val lengthMs: Long?, val explicit: Boolean) {
        val candidateName by lazy { SongNames.normalize(name) to SongNames.normalize(details.first()) }
    }

    private fun row(item: JSONObject): Row {
        val columns = item.optJSONArray("flexColumns")?.objects()
            ?.map { it.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.text().orEmpty() }.orEmpty()
        val details = columns.getOrNull(1).orEmpty().split(" • ")
        val explicit = item.optJSONArray("badges")?.objects().orEmpty().any {
            it.optJSONObject("musicInlineBadgeRenderer")?.optJSONObject("icon")?.optString("iconType") == "MUSIC_EXPLICIT_BADGE"
        }
        return Row(
            item.optJSONObject("playlistItemData")?.let { it.optString("videoId").ifBlank { null } },
            columns.firstOrNull().orEmpty(), details, lengthMs(details.last()), explicit
        )
    }

    /** "4:46" or "1:02:03" as milliseconds, or null for anything else. */
    private fun lengthMs(text: String): Long? {
        val parts = text.trim().split(":").takeIf { it.size in 2..3 } ?: return null
        val numbers = parts.map { it.toLongOrNull() ?: return null }
        return numbers.fold(0L) { total, part -> total * 60 + part } * 1000
    }

    /**
     * What a song goes by and how long it is. Apple Music names it in each store as it's written
     * there, so its Japanese, Korean and Chinese stores give 終わらない歌 for Owaranai Uta, 봄날 for
     * Spring Day and 松原みき for Miki Matsubara. Deezer can name a song row when its title and length match.
     */
    private class Names(
        val titles: Set<String>, val artists: Set<String>, var lengthsMs: List<Long>, private val japaneseTitle: String?,
        val requestedTitle: String, val requestedArtist: String,
        val linkedEdition: LinkedEdition?, val corroboratingAlbum: String?, var deezerNames: List<DeezerName>, val appleFound: Boolean,
        val appleIds: Set<Long>, val ambiguousSourceEdition: Boolean, val primaryEdition: LinkedEdition?,
        /** Whether Apple's editions as long as the link are all explicit, or all clean; null when not one or the other. */
        val sourceExplicit: Boolean?,
        /** An album Apple has the song on, or a name it has for it, says it's a live, karaoke or other take. */
        val takeOnAlbum: Boolean,
        /** A failed Apple search could have supplied a length. */
        val searchFailed: Boolean,
        /**
         * No store that could prove a row failed to answer: when one did, a name or a length that would have proved
         * the artist's own row may be missing. See [namesOf].
         */
        var complete: Boolean,
        val failedLookups: Set<String>
    ) {
        val rejectedCandidates: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        fun copyForDeezer() = withTitles(titles)

        fun withTitles(titles: Set<String>) = Names(titles, artists, lengthsMs, japaneseTitle, requestedTitle, requestedArtist, linkedEdition, corroboratingAlbum, deezerNames,
            appleFound, appleIds, ambiguousSourceEdition, primaryEdition, sourceExplicit, takeOnAlbum, searchFailed, complete, failedLookups)

        // A failed lookup can leave a name missing even where the names are complete, as for Lemon (Music Box).
        val everyStore get() = complete && failedLookups.isEmpty()

        fun lookupCouldName(text: String): Boolean =
            SongNames.storesWriting(text).any(failedLookups::contains) ||
                ("US" in failedLookups && SongNames.words(text).any { word -> word.any(Char::isLetter) })

        /** Apple, or Deezer, knows recordings of the song that differ in length: a live take may be one. */
        val severalRecordings get() = lengthsMs.any { one -> lengthsMs.any { other -> one - other > SAME_SONG_LENGTH_MS } }

        val artistKeys by lazy { artists.flatMap(SongNames::artists).toSet() }

        val romanizedJapanese by lazy {
            japaneseTitle?.let { Readings.of(listOf(it), Script.JAPANESE).single().typed }
        }
    }

    private class LinkedEdition(val lengthMs: Long, val live: Boolean)

    private class DeezerName(val title: String, val artist: String, val lengthMs: Long, val album: String)

    /**
     * The song's [Names]. They're [Names.complete] unless a store that could still name it failed: a search, when no
     * store found the song; a lookup in the US store, which writes names in Latin letters, or in the store of the
     * country the song's names are written in, as YouTube Music writes its rows either way; or Deezer, when the length
     * isn't known elsewhere. Japan's lookup of a Korean song failing loses no name a row would have.
     */
    private suspend fun namesOf(metadata: MusicMetadata): Names {
        val titles = mutableSetOf(metadata.title)
        val artists = mutableSetOf(metadata.artist)
        var japaneseTitle: String? = null
        val searching = Asked()
        val searched = java.util.concurrent.ConcurrentHashMap<String, List<JSONObject>>()
        val found = appleSong(metadata, searching, searched)
        val song = found?.song
        var linkedArtist = false
        // Every edition it has, as the album's take of a song is often a minute longer than the single's, and a
        // music app may have only the single's.
        val lengthsMs = found?.editions.orEmpty().mapNotNull { it.optLong("trackTimeMillis").takeIf { length -> length > 0 } }.toMutableList()
        var unanswered = emptySet<String>()
        song?.optLong("trackId")?.takeIf { it > 0 }?.let { id ->
            // And by the US store's name, in Latin letters as YouTube Music writes it, when another store found it under a
            // name in another script: Japan's finds Lemon by 米津玄師, which only the US store names Kenshi Yonezu, as YouTube
            // Music does. One found in Latin letters, as Argentina's Owaranai Uta, has that name already. A store whose
            // search already turned the song up isn't asked again.
            // Found by the US store, its search has that name, so it isn't looked up either.
            val written = song.optString("trackName") + song.optString("artistName")
            val stores = NAMING_STORES + listOfNotNull("US".takeIf { SongNames.storesWriting(written).isNotEmpty() })
            val elsewhere = namesIn(stores, id, searched)
            linkedArtist = elsewhere.mapNotNull { it.first }.any { SongNames.sameArtist(it.optString("artistName"), metadata.artist) }
            japaneseTitle = elsewhere[NAMING_STORES.indexOf("JP")].first?.optString("trackName")?.takeIf(String::isNotBlank)
            (listOf(song) + elsewhere.mapNotNull { it.first }).forEach {
                titles += it.optString("trackName").ifBlank { null } ?: return@forEach
                artists += it.optString("artistName").ifBlank { null } ?: return@forEach
            }
            unanswered = stores.zip(elsewhere).filter { it.second.second }.map { it.first }.toSet()
        }
        val homes = SongNames.storesWriting((titles + artists).joinToString(""))
        val album = found?.editions?.singleOrNull()?.takeIf {
            (matches(metadata, it.optString("trackName"), it.optString("artistName")) ||
                linkedArtist && SongNames.sameVersion(metadata.title, it.optString("trackName"), "")) &&
                metadata.lengthMs?.let { length -> sameRecordingLength(it.optLong("trackTimeMillis"), length) } != false
        }?.optString("collectionName")?.let(SongNames::normalize)?.takeIf(String::isNotEmpty)
        val takeOnAlbum = found?.editions.orEmpty().any {
            val collection = it.optString("collectionName")
            liveAlbum(collection) || Takes.saysAnotherTake(collection) != null
        }
        val searchFailed = found == null && searching.failed
        val complete = !searchFailed && unanswered.none { it == "US" || it in homes }
        val ids = appleRecordingIds(metadata, found)
        return Names(
            titles, artists, lengthsMs, japaneseTitle, metadata.title, metadata.artist, linkedAppleEdition(metadata, found?.editions.orEmpty()), album,
            emptyList(), found != null, ids, hasAmbiguousSourceEdition(metadata, found), primaryEdition(metadata, found),
            explicitness(metadata, found?.editions.orEmpty()), takeOnAlbum, searchFailed, complete, unanswered
        )
    }

    private fun primaryEdition(metadata: MusicMetadata, found: AppleSong?): LinkedEdition? {
        val song = found?.song ?: return null
        val title = song.optString("trackName")
        if (!matches(metadata, title, song.optString("artistName"))) return null
        val length = song.optLong("trackTimeMillis").takeIf { it > 0 } ?: return null
        return LinkedEdition(length, saysLive(title) || liveAlbum(song.optString("collectionName")))
    }

    private fun hasAmbiguousSourceEdition(metadata: MusicMetadata, found: AppleSong?): Boolean {
        val length = metadata.lengthMs?.takeIf { it > 0 } ?: return false
        return found?.editions.orEmpty().filter { sameRecordingLength(it.optLong("trackTimeMillis"), length) }
            .map { it.optLong("trackId") }.distinct().size > 1
    }

    private fun appleRecordingIds(metadata: MusicMetadata, found: AppleSong?): Set<Long> {
        val proved = found?.song?.takeIf { matches(metadata, it.optString("trackName"), it.optString("artistName")) }
        val provedLength = metadata.lengthMs?.takeIf { it > 0 } ?: proved?.optLong("trackTimeMillis")
        val sourceId = proved?.optLong("trackId")?.takeIf { it > 0 }
        return setOfNotNull(sourceId) + found?.editions.orEmpty().filter { edition ->
            proved != null && provedLength != null && sameRecordingLength(edition.optLong("trackTimeMillis"), provedLength) &&
                !SongNames.saysAnotherVersion(edition.optString("trackName"), metadata.title, metadata.artist) &&
                !SongNames.saysAnotherVersion(edition.optString("collectionName"), metadata.title, metadata.artist)
        }.mapNotNull { it.optLong("trackId").takeIf { id -> id > 0 } }.toSet()
    }

    /**
     * Song [id]'s row in each of [stores]: the one its search turned up, in [searched], or else its lookup there; and
     * whether that lookup failed.
     */
    private suspend fun namesIn(stores: List<String>, id: Long, searched: Map<String, List<JSONObject>>): List<Pair<JSONObject?, Boolean>> =
        coroutineScope {
            stores.map { store ->
                async {
                    val looking = Asked()
                    (searched[store]?.firstOrNull { it.optLong("trackId") == id } ?: looking.answer { appleLookup(id, store) }) to looking.failed
                }
            }.awaitAll()
        }

    /** How long Deezer says [metadata]'s song is, when its search has it. */
    private fun deezerLength(metadata: MusicMetadata, results: List<JSONObject>): Long? =
        results.firstOrNull { matches(metadata, MetadataParsers.deezerTitle(it), it.optJSONObject("artist")?.optString("name").orEmpty()) }
            ?.optLong("duration")?.takeIf { it > 0 }?.times(1000)

    private fun addDeezerLength(metadata: MusicMetadata, results: List<JSONObject>, lengths: MutableList<Long>) {
        if (lengths.isEmpty()) deezerLength(metadata, results)?.let(lengths::add)
    }

    private fun appleNamedArtist(metadata: MusicMetadata, names: Names): Boolean {
        val wanted = SongNames.artists(metadata.artist)
        return names.artists.any { artist -> SongNames.artists(artist).none(wanted::contains) }
    }

    /** Ask Deezer only after the names already known can't prove a row or the artist's own video. */
    private suspend fun addDeezerNames(metadata: MusicMetadata, names: Names, hasSongs: Boolean, ambiguous: Boolean) {
        val lengths = listOfNotNull(metadata.lengthMs?.takeIf { it > 0 }) + names.lengthsMs
        val needArtist = hasSongs && (!appleNamedArtist(metadata, names) || !names.complete || ambiguous) && metadata.artist.isNotBlank()
        val needsLength = names.lengthsMs.isEmpty()
        if (!needArtist && !needsLength) return
        val trackSearch = Asked()
        val artistSearch = Asked()
        val (tracks, artists) = coroutineScope {
            val tracks = async { trackSearch.answer { searchDeezer(metadata) }.orEmpty() }
            val artists = async { if (needArtist) artistSearch.answer { searchDeezer("search/artist", metadata.artist) }.orEmpty() else emptyList() }
            tracks.await() to artists.await()
        }
        val foundLengths = names.lengthsMs.toMutableList()
        addDeezerLength(metadata, tracks, foundLengths)
        // Without another length, deezerNames keeps only lengths belonging to the identified artist and title.
        val knownLengths = (lengths + foundLengths).ifEmpty {
            tracks.map { it.optLong("duration").coerceAtLeast(0) * 1000 }
        }
        val first = artists.firstOrNull()
        val candidates = deezerNames(metadata, tracks, knownLengths, first)
        val sameArtist = if (candidates.isNotEmpty()) deezerArtistMatches(metadata.artist, first?.optString("name").orEmpty()) {
            withTimeoutOrNull(artistAliasTimeoutMs) { artistAliases(metadata.artist) }
        } else false
        if (sameArtist == null) names.complete = false
        names.deezerNames = if (sameArtist == true) candidates else emptyList()
        if (foundLengths.isEmpty()) foundLengths.addAll(names.deezerNames.map { it.lengthMs })
        names.lengthsMs = foundLengths
        if (trackSearch.failed || artistSearch.failed) names.complete = false
    }

    private fun deezerCollaboration(name: String): Boolean =
        name.any { it in ",+&" } || SongNames.artistNames(name).let { it.size != 1 || it.first() != name.trim() }

    private fun variousArtists(name: String) = "variousartists" in SongNames.artists(name)

    /** Only the asked title and artist were searched, so an empty name cannot identify an alternate artist. */
    private fun deezerNames(metadata: MusicMetadata, results: List<JSONObject>, lengths: List<Long>, first: JSONObject?): List<DeezerName> {
        val artistId = deezerArtistId(first) ?: return emptyList()
        return results.mapNotNull { result -> deezerName(metadata, result, lengths, artistId) }
    }

    private fun deezerArtistId(first: JSONObject?): Long? {
        val name = first?.optString("name").orEmpty()
        val artistId = first?.optLong("id") ?: 0
        if (artistId <= 0 || name.isBlank()) return null
        if (variousArtists(name) || deezerCollaboration(name)) return null
        return artistId
    }

    private fun deezerName(metadata: MusicMetadata, result: JSONObject, lengths: List<Long>, artistId: Long): DeezerName? {
        if (metadata.title.isBlank()) return null
        val title = MetadataParsers.deezerTitle(result)
        val credit = result.optJSONObject("artist")
        val artist = credit?.optString("name").orEmpty()
        val album = result.optJSONObject("album")?.optString("title").orEmpty()
        val length = result.optLong("duration") * 1000
        if (credit?.optLong("id") != artistId || variousArtists(artist)) return null
        if (length <= 0) return null
        if (SongNames.saysAnotherVersion(title, metadata.title, metadata.artist) || SongNames.saysAnotherVersion(album, metadata.title, metadata.artist)) return null
        if (!SongNames.same(metadata.title, title) || lengths.none { sameRecordingLength(length, it) }) return null
        return DeezerName(title, artist, length, album)
    }

    /**
     * The song on Apple Music, by its name, or one of the same name and artist with a tag, as "(with
     * Delon)". A store may only have it in another script, as Argentina's has 夜に駆ける, so the US
     * store, which names it in Latin letters too, is asked when this one has no match or its search fails; and last,
     * at once, for a song named in Chinese, Japanese or Korean, the stores that name it as its own
     * country does, as only Taiwan's credits 光年之外 to 鄧紫棋, where the rest say G.E.M.
     */
    private suspend fun appleSong(metadata: MusicMetadata, asked: Asked, searched: MutableMap<String, List<JSONObject>>): AppleSong? {
        val stores = listOf(country.takeIf { it.length == 2 }, "US").distinct()
        stores.firstNotNullOfOrNull { appleSong(metadata, it, asked, searched) }?.let { return it }
        if ((metadata.title + metadata.artist).none { Character.UnicodeScript.of(it.code) in EAST_ASIAN }) return null
        return coroutineScope { (NAMING_STORES - stores.toSet()).map { store -> async { appleSong(metadata, store, asked, searched) } }.awaitAll() }.firstNotNullOfOrNull { it }
    }

    /** The [song] Apple has, and all its [editions] in the same store: its album's take, its single's, its remasters. */
    private class AppleSong(val song: JSONObject, val editions: List<JSONObject>)

    private fun linkedAppleEdition(metadata: MusicMetadata, editions: List<JSONObject>): LinkedEdition? {
        val length = metadata.lengthMs ?: return null
        if (editions.size < 2 || !plainLinkedTitle(metadata)) return null
        val timed = editions.filter { it.optLong("trackTimeMillis") > 0 && matches(metadata, it.optString("trackName"), it.optString("artistName")) }
        if (timed.size < 2) return null
        val chosen = timed.singleOrNull { sameRecordingLength(it.optLong("trackTimeMillis"), length) } ?: return null
        val title = chosen.optString("trackName")
        val album = chosen.optString("collectionName")
        if (!keepsRerecording(requestedRecording(metadata.title), title, album)) return null
        return LinkedEdition(chosen.optLong("trackTimeMillis"), saysLive(title) || liveAlbum(album))
    }

    /** The song in [store]'s search, whose results are kept in [searched], so its names there aren't looked up again. */
    private suspend fun appleSong(metadata: MusicMetadata, store: String?, asked: Asked, searched: MutableMap<String, List<JSONObject>>): AppleSong? = asked.answer {
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", searchQuery(metadata))
            .addQueryParameter("entity", "song")
            .addQueryParameter("limit", "10")
            .apply { store?.let { addQueryParameter("country", it) } }
            .build()
        val results = appleJson(url.toString()).optJSONArray("results")?.objects().orEmpty()
        searched[store ?: "US"] = results
        val song = results.firstOrNull { matches(metadata, it.optString("trackName"), it.optString("artistName")) }
            ?: results.firstOrNull {
                looselyMatches(metadata, it.optString("trackName"), it.optString("artistName")) &&
                    !SongNames.saysAnotherVersion(it.optString("trackName"), metadata.title, metadata.artist)
            }
            ?: results.firstOrNull { inLatinLetters(metadata, it) }
            ?: results.firstOrNull { song -> metadata.lengthMs?.let { kotlin.math.abs(song.optLong("trackTimeMillis") - it) <= SAME_RECORDING_LENGTH_MS } != false }
                ?.takeIf { namedAtHome(metadata, it, searched) }
        song?.let { AppleSong(it, listOf(it) + results.filter { other -> other !== it && matches(metadata, other.optString("trackName"), other.optString("artistName")) }) }
    }

    /**
     * Whether [song], under other names, is [metadata]'s: what the store of the country that writes
     * those names calls it, as Korea's calls ILLIT's You're Under Arrest 체포하겠어, by 아일릿.
     */
    private suspend fun namedAtHome(metadata: MusicMetadata, song: JSONObject, searched: Map<String, List<JSONObject>>): Boolean {
        val store = SongNames.homeStore(metadata.title + metadata.artist) ?: return false
        val id = song.optLong("trackId")
        val home = (searched[store]?.firstOrNull { it.optLong("trackId") == id } ?: appleLookup(id, store)) ?: return false
        return SongNames.same(home.optString("trackName"), metadata.title) && SongNames.sameArtist(home.optString("artistName"), metadata.artist)
    }

    /**
     * Whether [song] is [metadata]'s, named in Latin letters where it's given in another script, as
     * Korea's store names ILLIT 아일릿: the same title, by an artist the store writes otherwise, and as long.
     */
    private fun inLatinLetters(metadata: MusicMetadata, song: JSONObject): Boolean {
        val lengthMs = metadata.lengthMs ?: return false
        return !metadata.artist.hasLatin() && SongNames.sameVersion(metadata.title, song.optString("trackName"), "") &&
            kotlin.math.abs(song.optLong("trackTimeMillis") - lengthMs) <= SAME_RECORDING_LENGTH_MS
    }

    private suspend fun appleLookup(id: Long, store: String): JSONObject? {
        val url = "https://itunes.apple.com/lookup".toHttpUrl().newBuilder()
            .addQueryParameter("id", id.toString())
            .addQueryParameter("country", store)
            .build()
        return appleJson(url.toString()).optJSONArray("results")?.optJSONObject(0)
    }

    /**
     * Apple's answer at [url]. A store that doesn't exist, as Iran's or Cuba's, is answered 400: a store with
     * nothing, as that is no failure that asking again would mend.
     */
    private suspend fun appleJson(url: String): JSONObject = try {
        fetchJson(url)
    } catch (e: ServiceApis.ServiceException) {
        if (e.code != HTTP_BAD_REQUEST) throw e
        JSONObject()
    }

    /** The stores asked for a song's names, and whether one of them failed to answer. */
    private class Asked {
        @Volatile
        var failed = false
            private set

        /** [lookUp]'s answer, or null when it failed, so one of many asked at once doesn't sink the rest; which is kept in [failed]. */
        suspend fun <T> answer(lookUp: suspend () -> T?): T? = try {
            lookUp()
        } catch (_: IOException) {
            failed = true
            null
        } catch (_: JSONException) {
            failed = true
            null
        }
    }

    /** As long as the song, or one of its editions, give or take what another release of it adds; true when either isn't known. */
    private fun sameLength(row: Row, names: Names): Boolean {
        val length = row.lengthMs ?: return true
        return names.lengthsMs.isEmpty() || names.lengthsMs.any { kotlin.math.abs(length - it) <= SAME_LENGTH_MS }
    }

    /** A song result, "Name | Artist • Album • 4:46", under any of the song's names, by any of its artist's. */
    private fun isTheSong(row: Row, names: Names): Boolean {
        if (row.id == null) return false
        val album = row.details.getOrNull(1).orEmpty()
        val named = SongNames.titleNames(row.name).any { name -> names.titles.any { SongNames.sameVersion(it, name, album) } }
        val by = knownArtist(row.details.first(), names)
        return named && by && sameLength(row, names)
    }

    /** Deezer's searched name proves only a song row of the same title, artist and recording length. */
    private fun isDeezerSong(row: Row, names: Names): Boolean = row.id != null && names.deezerNames.any {
        (SongNames.same(it.title, row.name) || it.album.isNotBlank() && SongNames.same(it.album, row.details.getOrNull(1).orEmpty()) &&
            SongNames.titleNames(row.name).any { name -> SongNames.same(it.title, name) }) &&
            SongNames.artists(row.details.first()).any(SongNames.artists(it.artist)::contains) &&
            sameRecordingLength(row.lengthMs, it.lengthMs)
    }

    /** A credit can put two known names together, as "G.E.M. 鄧紫棋"; both whole names must be known. */
    private fun knownArtist(credit: String, names: Names): Boolean {
        val wanted = names.artistKeys
        fun known(name: String) = SongNames.artists(name).any(wanted::contains)
        if (known(credit)) return true
        return credit.indices.filter { credit[it].isWhitespace() }.any { at ->
            known(credit.substring(0, at)) && known(credit.substring(at + 1))
        }
    }

    private fun numberedPartBeforeNotes(title: String): NumberedPart? = numberedPart(title) ?: numberedPart(SongNames.bareTitle(title))

    /** The same title can spell its number with Part or Pt. */
    private fun samePart(wanted: NumberedPart?, other: NumberedPart?): Boolean =
        wanted != null && other != null && wanted.title.isNotEmpty() && wanted.number == other.number && SongNames.same(wanted.title, other.title)

    /**
     * A song result named in non-Latin letters ("残酷な天使のテーゼ - Zankoku na Tenshi no
     * Thesis" for "A Cruel Angel's Thesis"), or a Latin spelling of a known title. Its artist is the song's
     * and it is within three seconds of a known recording.
     */
    private fun isTheSongInAnotherScript(row: Row, names: Names): Boolean {
        val length = row.lengthMs ?: return false
        if (row.id == null || names.lengthsMs.none { kotlin.math.abs(length - it) <= SAME_SONG_LENGTH_MS }) return false
        if (row.name.none { it.isLetter() && it.code > 0x7f }) return false
        val anotherScript = namedInAnotherScript(row.name)
        if (!anotherScript && !sameLatinSpelling(row.name, names)) return false
        if (isAnotherTake(row, names)) return false
        return knownArtist(row.details.first(), names)
    }

    /**
     * [row] says it's a live, karaoke or other take, by a tag or by the live album it's on, and nothing says the song is that
     * take: not its names, nor the albums Apple has it on. A live take may be as long as the studio's, a karaoke one always is,
     * so its length shows nothing; but where Apple knows recordings of different lengths, a live row may be one of them.
     */
    private fun isAnotherTake(row: Row, names: Names): Boolean {
        val album = row.details.getOrNull(1).orEmpty()
        val take = Takes.saysAnotherTake(row.name, names.artists + row.details.first()) ?: Takes.Take.LIVE.takeIf { liveAlbum(album) } ?: return false
        if (names.takeOnAlbum || names.titles.any(::saysTake)) return false
        return take == Takes.Take.OTHER || !names.severalRecordings
    }

    /** A title with a tag, or a word, that says it's a take of the song and not the song itself, in any script. */
    private fun saysTake(title: String): Boolean =
        saysLive(title) || Takes.saysAnotherTake(title) != null || Takes.saysAnotherVersionBesidesLive(title)

    private fun namedInAnotherScript(name: String): Boolean =
        name.codePoints().filter(Character::isLetter).anyMatch { Character.UnicodeScript.of(it) !in NO_OTHER_SCRIPT }

    /** A Latin title can use another spelling of the same whole title, including a title the Japanese store names. */
    private fun sameLatinSpelling(title: String, names: Names): Boolean {
        val latin = title.lowercase(Locale.ROOT)
        val withO = latin.replace('ø', 'o')
        val writtenOut = latin.replace("ø", "oe").replace("ō", "ou").replace("ū", "uu").replace("ü", "ue")
        val spellings = listOf(withO, writtenOut).filter { it != latin }.flatMap { listOf(it, withoutLatinCredit(it)) }.distinct()
        if (names.titles.any { known -> spellings.any { SongNames.same(known, it) } }) return true
        val macron = 'ō' in latin || 'ū' in latin
        return macron && names.romanizedJapanese?.let { known -> spellings.any { SongNames.same(known, it) } } == true
    }

    /** A guest or a TV anime theme credit can follow the written-out Latin name. */
    private fun withoutLatinCredit(title: String): String {
        val open = title.lastIndexOf('(')
        if (open < 1 || !title.endsWith(')')) return title
        if (!title[open - 1].isWhitespace()) return title
        val credit = title.substring(open + 1, title.lastIndex)
        if (')' in credit) return title
        val guest = credit.startsWith("with ") && credit.drop("with ".length).isNotBlank()
        val theme = credit.startsWith("tv anime \"") && credit.endsWith("\" opening theme")
        return if (guest || theme) title.substring(0, open) else title
    }

    /**
     * A video result, "Title | Channel • 1.2M views • 3:45", that plays the song: one of its names in
     * the title, nothing in it saying it's another version, e.g. a cover or karaoke, and its artist
     * the one who put it up, or named in the title of one as long as the song, e.g. with its lyrics.
     * Its title says nothing else but such tags outside brackets, so "Perfect Day" isn't "Perfect";
     * what's in them, "(終わらない歌 An Endless Song)", is what it's called elsewhere or said of it.
     */
    private fun isTheSongsVideo(row: Row, names: Names, allowAlias: Boolean = false): Boolean {
        if (row.id == null || !videoTitleMatches(row.name, names, allowAlias)) return false
        // A channel is the artist's own by its whole name, maybe with what YouTube adds to one:
        // "Band - Topic", "BandVEVO".
        return isOwnVideo(row, names) || names.artists.any { artistIn(it, row.name) } && row.lengthMs != null && names.lengthsMs.isNotEmpty() && sameLength(row, names)
    }

    private fun videoTitleMatches(title: String, names: Names, allowAlias: Boolean = false): Boolean {
        if (names.titles.none { titleIn(it, title) }) return false
        if (isAnotherVideoVersion(title, names)) return false
        val said = names.titles.flatMap { SongNames.words(SongNames.withoutEditionTag(it)) } + names.artists.flatMap(SongNames::words)
        val outside = outsideBrackets(title)
        val rest = SongNames.words(outside) - said.toSet() - VIDEO_TAGS
        if (rest.any { !YEAR.matches(it) }) return false
        // Nor anything in another script, which has no words to tell apart: "桜の雨" isn't "桜". Named
        // both ways, "プラスティック・ラブ - Plastic Love", the other way is the song's name too.
        val known = names.titles.map { SongNames.normalize(SongNames.withoutEditionTag(it)) }
        val bothWays = SongNames.titleNames(outside.trim()).drop(1).takeIf { halves -> halves.any { SongNames.normalize(it) in known } }.orEmpty()
        val written = (known + bothWays.map(SongNames::normalize) +
            names.artists.flatMap(SongNames::artistNames).map { SongNames.normalize(SongNames.withoutArticle(it)) } + VIDEO_TAG_MARKS)
        return removeVideoNames(outside, written).none { it.code > 0x7f } || repeatsBilingualTitle(title, names) ||
            allowAlias && repeatsParenthesizedTitle(title, names)
    }

    /** A store's bracketed alias proves a video only when both whole names and both credits agree. */
    private fun repeatsParenthesizedTitle(title: String, names: Names): Boolean {
        val parts = title.split(',', '，', limit = 3)
        if (parts.size != 2) return false
        val artist = aliasText(names.requestedArtist)
        val credited = parts.map { part ->
            val pair = part.split(" - ", limit = 2)
            if (pair.size != 2 || !aliasText(pair[0]).equals(artist, ignoreCase = true)) return false
            aliasText(pair[1])
        }.toSet()
        val requested = aliasText(names.requestedTitle)
        return names.titles.any { name ->
            val pair = parenthesizedAlias(name) ?: return@any false
            val native = aliasText(pair.first)
            val alias = aliasText(pair.second)
            native != alias && alias == requested && setOf(native, alias) == credited &&
                plainVideoNameMatches(native, names) && plainVideoNameMatches(alias, names)
        }
    }

    /** Ask the ordinary video check about each name alone, with the requested title still known. */
    private fun plainVideoNameMatches(name: String, names: Names): Boolean {
        val plain = names.withTitles(setOf(names.requestedTitle, name))
        return videoTitleMatches("${names.requestedArtist} - $name", plain)
    }

    private fun aliasText(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC).trim()

    private fun parenthesizedAlias(name: String): Pair<String, String>? {
        val text = name.trim()
        val open = text.indexOfFirst { it == '(' || it == '（' }
        if (open <= 0) return null
        val close = if (text[open] == '(') ')' else '）'
        if (!text.endsWith(close)) return null
        val native = text.substring(0, open).trim()
        if (aliasText(native).any { it in ALIAS_BRACKETS }) return null
        val alias = text.substring(open + 1, text.lastIndex).trim()
        val nested = alias.indexOfFirst { it == '(' || it == '（' }
        val plain = if (nested < 0) alias else {
            val end = if (alias[nested] == '(') ')' else '）'
            if (!alias.endsWith(end)) return null
            val note = aliasText(alias.substring(nested + 1, alias.lastIndex)).lowercase(Locale.ROOT)
            if (note !in NEUTRAL_ALIAS_WORDS) return null
            alias.substring(0, nested).trim()
        }
        if (plain.isEmpty() || aliasText(plain).any { it in ALIAS_BRACKETS }) return null
        return native to plain
    }

    /** The video repeats the artist before each store name. Compare whole names, so 愛 can't explain 愛愛. */
    private fun repeatsBilingualTitle(title: String, names: Names): Boolean {
        val parts = title.split(',', '，', limit = 3)
        if (parts.size != 2) return false
        val artists = names.artists.map(SongNames::normalize).toSet()
        val first = creditedVideoTitle(parts[0], artists) ?: return false
        val second = creditedVideoTitle(parts[1], artists) ?: return false
        val inAnotherScript = listOf(first, second).partition(::namedInAnotherScript)
        val latin = inAnotherScript.second.singleOrNull()?.takeIf(String::hasLatin) ?: return false
        val other = inAnotherScript.first.single()
        val titles = setOf(SongNames.normalize(latin), SongNames.normalize(other))
        return names.titles.any { name ->
            val pair = name.split(" - ", limit = 3).map(String::trim)
            pair.size == 2 && pair.map(SongNames::normalize).toSet() == titles && pair.none(::isVideoTitleTag)
        }
    }

    private fun creditedVideoTitle(part: String, artists: Set<String>): String? {
        val (credit, title) = part.split(" - ", limit = 2).takeIf { it.size == 2 } ?: return null
        return title.trim().takeIf { SongNames.normalize(credit) in artists }
    }

    private fun isVideoTitleTag(name: String): Boolean {
        // Read a dash half as a note before treating it as a name, even when the store put it first.
        val asTag = "Song ($name)"
        return SongNames.withoutEditionTag(asTag) != asTag || Takes.saysAnotherTake(asTag) != null
    }

    private fun isAnotherVideoVersion(name: String, names: Names): Boolean = videoNamesDifferentEdition(name, names) ||
        names.titles.all { title -> SongNames.saysAnotherVersion(name, title, names.artists.joinToString(" ")) }

    private fun videoNamesDifferentEdition(name: String, names: Names): Boolean {
        val notes = Takes.videoRecordingNotes(name)
        if (notes.isEmpty()) return false
        val namedRerecording = "thefirsttake" in notes && "fromthefirsttake" in SongNames.normalize(names.requestedTitle)
        val unproved = if (namedRerecording) notes - "thefirsttake" else notes
        return unproved.isNotEmpty() && names.titles.none { Takes.videoRecordingNotes(it).containsAll(unproved) }
    }

    /** A show called ライブ does not make its theme a live recording. Keep separate recording notes. */
    private fun withoutShowCredit(title: String): String = SHOW_CREDIT.replace(title, " ").trim()

    private fun removeVideoNames(title: String, names: List<String>): String =
        names.filter(String::isNotEmpty).sortedByDescending(String::length)
            .fold(SongNames.normalize(title)) { left, name -> left.replace(name, "") }

    private fun isOwnVideo(row: Row, names: Names): Boolean {
        val uploader = SongNames.normalize(SongNames.withoutArticle(row.details.first()))
        return names.artistKeys.any { it == uploader || uploader.startsWith(it) && uploader.removePrefix(it) in CHANNEL_SUFFIXES }
    }

    /**
     * [title] is in [name]: all its words, or, written in another script, the whole of it. A title
     * that's only a tag, or one Latin letter, says too little to go on; "桜" says enough, as what
     * else a video's title says in that script is looked at too.
     */
    private fun titleIn(title: String, name: String): Boolean {
        val bare = SongNames.withoutEditionTag(title)
        val words = SongNames.words(bare)
        val letters = SongNames.normalize(bare)
        return when {
            letters.isEmpty() -> false
            letters.any { it.code > 0x7f } -> letters in SongNames.normalize(name)
            else -> words.isNotEmpty() && SongNames.words(name).containsAll(words)
        }
    }

    /** One of the artist's names in [name], as [titleIn] tells. */
    private fun artistIn(artist: String, name: String): Boolean =
        SongNames.artistNames(artist).any { titleIn(SongNames.withoutArticle(it), name) }

    private fun JSONObject.text(): String = optJSONObject("text").runsText()

    /** The result rows, which sit at varying depths in the response depending on how it is sectioned. */
    private fun Any?.listItems(): List<JSONObject> = when (this) {
        is JSONObject -> listOfNotNull(optJSONObject("musicResponsiveListItemRenderer")) +
            keys().asSequence().flatMap { optJSONObject(it).listItems() + optJSONArray(it).listItems() }.toList()
        is JSONArray -> (0 until length()).flatMap { optJSONObject(it).listItems() + optJSONArray(it).listItems() }
        else -> emptyList()
    }

    private suspend fun fetchJson(url: String, body: JSONObject? = null): JSONObject {
        val request = Request.Builder().url(url)
            .apply { if (body == null) get() else post(body.toString().toRequestBody("application/json".toMediaType())) }
            .build()
        return client.newCall(request).answer(ioDispatcher) { response ->
            // Busy, or down, it's no answer, and says nothing about whether it has the song.
            if (!response.isSuccessful) throw ServiceApis.ServiceException(response.code)
            JSONObject(response.body.stringAtMost())
        }
    }

    /**
     * Requires the same name and at least one artist in common, so remixes, covers and deluxe
     * editions don't win by rank. Whole names are compared: "Sia" must not match "Asia".
     */
    private fun matches(metadata: MusicMetadata, name: String, artist: String, album: String = "", radio: Boolean = false): Boolean {
        val titleMatches = if (radio) RadioGarden.sameTitle(metadata.title, name) else SongNames.sameVersion(metadata.title, name, album)
        if (!titleMatches) return false
        if (metadata.type == ItemType.ARTIST) return true
        // With no artist to check, e.g. a Google link that only names the song, any song of that name
        // would do, so none is taken for it: it's searched for instead.
        val wanted = SongNames.artists(metadata.artist)
        return SongNames.artists(artist).any(wanted::contains)
    }

    private fun looselyMatches(metadata: MusicMetadata, name: String, artist: String): Boolean =
        SongNames.wordsMatch(name, metadata.title) && SongNames.artistInside(artist, metadata.artist)

    internal companion object {
        private const val APPLE_PROOF_WAIT_MS = 500L
        private const val EXPLICIT_WAIT_MS = 1_000L

        /**
         * How long MusicBrainz's own answer may take once it's the matcher's turn. Its turn can wait out the gap MusicBrainz
         * asks between two requests, [MusicBrainz.ASK_GAP_MS], after another lookup of the app, so that's added.
         */
        private const val ARTIST_ALIAS_WAIT_MS = 1_000L + MusicBrainz.ASK_GAP_MS

        /** Apple's trackExplicitness: an explicit edition, or a clean one, edited or never explicit. */
        private val EXPLICITNESS = mapOf("explicit" to true, "cleaned" to false, "notExplicit" to false)
        const val TIMEOUT_MS = 5_000L

        /** How many of the Spotify songs ListenBrainz knows by a name are read to find the one. */
        const val SPOTIFY_PAGES = 6

        /** How long ChatGPT has to pick a song, after the searches it picks from; it takes a few seconds. */
        const val PICK_TIMEOUT_MS = 8_000L

        /** How long a song missed while a store that could have proved it was down stays no answer, see [remembered]. */
        const val UNSURE_FOR_MS = 30_000L

        private const val HTTP_BAD_REQUEST = 400
        const val QUEUE_LOOKUPS_AT_ONCE = 6
        const val SPOTIFY_TRACK_URL = "https://open.spotify.com/track/"
        const val YOUTUBE_WATCH_VIDEOS_URL = "https://www.youtube.com/watch_videos"
        /** One song in Qobuz's search: its title and "artist • album", and the numbers of the song and its album. */
        internal val qobuzItems = PageParts(phoneRegex("""<div class="ListItem">"""), phoneRegex("</li>"), tagEnd = null)
        internal val qobuzTitles = PageParts(phoneRegex("""class="ListItem__title${'"'}"""), phoneRegex("""</a>\s*+<p class="ListItem__artists">"""))

        /**
         * A Qobuz song's title and credit, as main's `class="ListItem__title"[^>]*>(.*?)</a>\s*<p class="ListItem__artists">(.*?)</p>`
         * read them: the title up to the first `</a>` that the credit follows, then the credit up to the first `</p>`.
         */
        fun qobuzTitle(html: String): Pair<String, String>? {
            val title = qobuzTitles.firstIn(html) ?: return null
            val credit = title.whole.last + 1
            val end = html.indexOf("</p>", credit).orNone() ?: return null
            return html.substring(title.between) to html.substring(credit, end)
        }
        private val qobuzTrackRegex = phoneRegex("""track&#x2F;(\d+)""")
        private val qobuzAlbumRegex = phoneRegex("""href="/[a-z]{2}-[a-z]{2}/album/[^"/]+/([A-Za-z0-9]+)${'"'}""")

        const val BANDCAMP_SEARCH_URL = "https://bandcamp.com/api/bcsearch_public_api/1/autocomplete_elastic"

        /** Songs (ATV), official music videos (OMV) and label uploads; UGC and podcasts aren't music. */
        val MUSIC_VIDEO_TYPES = setOf("ATV", "OMV", "OFFICIAL_SOURCE_MUSIC")
        const val YOUTUBE_MUSIC_WATCH_URL = "https://music.youtube.com/watch?v="
        const val YOUTUBE_MUSIC_ALBUM_URL = "https://music.youtube.com/browse/"
        const val YOUTUBE_MUSIC_ARTIST_URL = "https://music.youtube.com/channel/"
        const val YOUTUBE_WATCH_URL = "https://www.youtube.com/watch?v="
        /** How many search results the song is picked from. */
        const val PICK_FROM = 6
        const val YOUTUBE_PLAYER_URL = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
        const val YOUTUBE_CLIENT_VERSION = "2.20240101.00.00"

        // Search filters as YouTube Music itself encodes them: the song, album and artist tabs.
        const val YOUTUBE_MUSIC_SONGS = "EgWKAQIIAWoKEAoQAxAEEAkQBQ=="
        const val YOUTUBE_MUSIC_ALBUMS = "EgWKAQIYAWoKEAoQAxAEEAkQBQ=="
        const val YOUTUBE_MUSIC_ARTISTS = "EgWKAQIgAWoKEAoQAxAEEAkQBQ=="
        const val YOUTUBE_MUSIC_VIDEOS = "EgWKAQIQAWoKEAoQAxAEEAkQBQ=="

        /** What YouTube adds to an artist's own channel name. */
        private val CHANNEL_SUFFIXES = setOf("topic", "vevo", "official", "music", "tv")

        /** What a video's title says of it besides the song's name, not another recording: "Official Video", "Lyrics", "Sub Español". */
        private val VIDEO_TAGS = setOf(
            "official", "video", "audio", "music", "lyric", "lyrics", "visualizer", "visualiser", "hd", "hq", "4k", "mv", "pv",
            "clip", "videoclip", "full", "version", "feat", "ft", "the", "with", "on", "screen", "high", "quality", "remastered",
            "remaster", "sub", "subs", "subtitles", "subtitulado", "subtitulada", "legendado", "letra", "traduccion",
            "traducida", "translation", "espanol", "english", "eng", "romaji", "kanji"
        )

        /** The same, as other scripts write them: "歌詞付き", "公式". */
        private val VIDEO_TAG_MARKS = listOf("歌詞付き", "歌詞付", "歌詞", "公式", "字幕", "高音質", "日本語", "中日羅", "가사").map(SongNames::normalize)

        private const val ALIAS_BRACKETS = "()（）[]［］{}｛｝【】〔〕「」『』〈〉《》〘〙〚〛"
        private val NEUTRAL_ALIAS_WORDS = setOf("original", "remaster", "remastered", "mono", "stereo")
        private val PRESENTATION_VIDEO_SUFFIXES = setOf("mv", "musicvideo", "officialmusicvideo", "musicvideo4kupgrade")
        private val SHOW_CREDIT = phoneRegex(
            """[-–—(（]?\s{0,3}+(?:(?:TV ?アニメ|アニメ|映画|ドラマ)[「『][^「」『』]*+[」』](?:オープニング(?:テーマ)?|エンディング(?:テーマ)?|主題歌|OP|ED)|(?:TV Anime "[^"]*+" (?:Opening|Ending) Theme|From "[^"]*+"))[)）]?""",
            RegexOption.IGNORE_CASE
        )

        /** A year, as a video's title may give the song's: "1984". */
        private val YEAR = phoneRegex("""(?:19|20)\d\d""")


        /** Apple Music stores that write names as they are in Japanese, Korean and Chinese. */
        private val NAMING_STORES = listOf("JP", "KR", "TW", "HK", "CN")

        /** The scripts the stores in [NAMING_STORES] write names in. */
        private val EAST_ASIAN = setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL)
        private val NO_OTHER_SCRIPT = setOf(Character.UnicodeScript.LATIN, Character.UnicodeScript.COMMON, Character.UnicodeScript.INHERITED, Character.UnicodeScript.UNKNOWN)

        /** How far another release of a song, e.g. a remaster or a video's, may be from its length. */
        const val SAME_LENGTH_MS = 8_000L
        const val SAME_SONG_LENGTH_MS = 3_000L

        /** How far a video ChatGPT picks may be from the song's length, as a video may open or end with more. */
        const val PICKED_VIDEO_LENGTH_MS = 45_000L

    }
}

private fun sameRecordingLength(time: Long?, wanted: Long): Boolean =
    time != null && kotlin.math.abs(time - wanted) <= ExactMatcher.SAME_SONG_LENGTH_MS

/** Compare already-normalized video titles and all known recording durations. */
internal fun sameVideoEvidence(
    firstTitle: String, candidateTitle: String, knownTitles: Set<String>, firstLength: Long?, candidateLength: Long?, wanted: List<Long>
): Boolean {
    if (firstTitle != candidateTitle && (firstTitle !in knownTitles || candidateTitle !in knownTitles)) return false
    if (candidateLength == null) return firstLength == null && firstTitle == candidateTitle
    if (firstLength?.let { !sameRecordingLength(candidateLength, it) } == true) return false
    return wanted.isEmpty() || wanted.any { sameRecordingLength(candidateLength, it) }
}

/** A missing alias answer is uncertain; an empty answer rules out the alias. */
internal suspend fun deezerArtistMatches(artist: String, deezerArtist: String, aliases: suspend () -> Set<String>?): Boolean? {
    if (artist.isBlank() || deezerArtist.isBlank()) return false
    if (SongNames.sameArtist(artist, deezerArtist)) return true
    return aliases()?.any { SongNames.sameArtist(it, deezerArtist) }
}

/** The apps whose search needs no account and can look up songs, albums and artists. */
private val matchable = setOf(
    MusicService.APPLE_MUSIC, MusicService.DEEZER, MusicService.BANDCAMP, MusicService.YOUTUBE_MUSIC, MusicService.YOUTUBE,
    MusicService.TIDAL, MusicService.SOUNDCLOUD, MusicService.AUDIOMACK
)

/**
 * Whether Crosstune can look [type] up in [target] itself. A playlist is someone's own, and is never anywhere else.
 * Spotify songs are found through ListenBrainz, Qobuz's artists aren't told apart, and Presto Music only lists albums.
 */
internal fun canMatchExactly(target: MusicService, type: ItemType): Boolean = when (target) {
    MusicService.PRESTO_MUSIC -> type == ItemType.ALBUM
    MusicService.QOBUZ -> type == ItemType.ALBUM || type == ItemType.TRACK
    MusicService.SPOTIFY -> type == ItemType.TRACK
    else -> type != ItemType.PLAYLIST && target in matchable
}

/**
 * Whether [item] not being on [target] is worth saying, rather than searching for it: a song or album
 * that could have been matched, or a playlist whose songs aren't known, which is someone's own and
 * so on no other service. Where only the artist's own page or account counts, a release on a
 * label's page isn't found, though it's there, so it's searched for.
 */
internal fun canBeMissing(target: MusicService, item: MusicMetadata): Boolean =
    (canMatchExactly(target, item.type) && target !in partlyKnown) || (item.type == ItemType.PLAYLIST && item.tracks.isEmpty())

/**
 * The apps where a song not found may still be there: on Bandcamp, SoundCloud and Audiomack only its
 * artist's own page or a verified account counts, ListenBrainz doesn't know every Spotify song, and Presto Music
 * may find more than one edition of the same album.
 */
private val partlyKnown = setOf(MusicService.BANDCAMP, MusicService.SOUNDCLOUD, MusicService.AUDIOMACK, MusicService.SPOTIFY, MusicService.PRESTO_MUSIC)

/** [name] with what's in brackets put as a space, of any kind a title uses: "Song (An Endless Song (1987))" is "Song  ". */
internal fun outsideBrackets(name: String) = replacingBracketTags(name, "([（【「『［", ")]）】」』］", " ")
