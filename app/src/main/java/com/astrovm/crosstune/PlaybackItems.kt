package com.astrovm.crosstune

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlin.math.abs

/** The fields Android reports for one item, before any duration or name is adjusted. */
internal data class MediaItem(
    val rawId: String?, val videoId: String?, val title: String?, val artist: String?, val album: String?, val durationMs: Long?
) {
    /** Missing fields may be filled in. Two known IDs or durations that disagree mark a new item. */
    fun isSame(other: MediaItem): Boolean {
        if (!agree(rawId, other.rawId) || !agree(videoId, other.videoId) || !agree(durationMs, other.durationMs)) return false
        if (rawId != null && other.rawId != null) return true
        return agree(title, other.title) && agree(artist, other.artist) && agree(album, other.album)
    }

    fun filling(previous: MediaItem) = MediaItem(
        rawId ?: previous.rawId, videoId ?: previous.videoId, title ?: previous.title, artist ?: previous.artist,
        album ?: previous.album, durationMs ?: previous.durationMs
    )

    fun names(song: MusicMetadata): Boolean = SongNames.names(title.orEmpty(), artist.orEmpty(), song)

    fun anotherEdition(next: MediaItem): Boolean =
        !agree(rawId, next.rawId) || (rawId == null || next.rawId == null) && !agree(album, next.album)

    private fun <T> agree(one: T?, other: T?): Boolean = one == null || other == null || one == other
}

internal fun mediaItem(
    id: String?, uri: String?, title: String?, artist: String?, album: String?, duration: Long?
): MediaItem = MediaItem(
    id?.takeIf(String::isNotBlank), RecordingKey.youTubeSessionId(uri, id), title?.takeIf(String::isNotBlank),
    artist?.takeIf(String::isNotBlank), album?.takeIf(String::isNotBlank), duration?.takeIf { it > 0 }
)

internal enum class ItemKind { UNKNOWN, AD, SONG }

/** A revision of a session's item. Only the ledger can keep the same identity through an enrichment. */
internal class ItemId(private val read: () -> ItemReading?) {
    fun current(): ItemReading? = read()?.takeIf { it.id === this }
}

internal data class ItemReading(
    val id: ItemId, val item: MediaItem, val kind: ItemKind, val durationMs: Long?,
    val checkAtMs: Long? = null, val canFinishPlay: Boolean = false, val adForVideo: String? = null
) {
    fun isCurrent(): Boolean = id.current() == this

    /** A filled-in ID cannot identify this item if it contradicts our exact-video evidence. */
    fun matches(next: MediaItem): Boolean =
        item.isSame(next) && (adForVideo == null || next.videoId == null || next.videoId == adForVideo)
}

internal data class ItemRequest(val song: MusicMetadata, val videoId: String, val started: Boolean = false)

internal data class ItemObservation(
    val item: MediaItem, val nowMs: Long, val positionMs: Long?, val asked: MusicMetadata? = null,
    val knownByTitleMs: Long? = null, val reportedAd: Boolean = false, val playing: Boolean = true, val app: String? = null
)

/** The one current item, evidence and startup deadline for each YouTube Music session. */
internal class PlaybackItems {
    private data class ClockProof(val afterStart: Boolean, val startMs: Long?, val advanced: Boolean)

    private data class Current(
        val reading: ItemReading,
        val clock: ClockProof,
        val hint: MusicMetadata? = null,
        val unprovedVideo: String? = null
    )

    private val current = MutableStateFlow<Map<Any, Current>>(emptyMap())
    private data class Wanted(val app: String, val request: ItemRequest)
    private val request = MutableStateFlow<Wanted?>(null)
    private val changes = current.map { entries -> entries.mapValues { it.value.reading } }.distinctUntilChanged()
    private val deadlines = current.map { entries -> entries.values.mapNotNull { it.reading.checkAtMs }.minOrNull() }.distinctUntilChanged()

    fun refreshes(now: () -> Long) = merge(changes.map { Unit }, request.map { Unit }, channelFlow {
        deadlines.collectLatest { until ->
            if (until != null) {
                delay(until - now())
                send(Unit)
            }
        }
    })

    @Synchronized
    fun request(app: String?, song: MusicMetadata?, link: String?) {
        val id = RecordingKey.youTubeIdIn(link)
        request.value = if (app != null && song != null && id != null) Wanted(app, ItemRequest(song, id)) else null
    }

    @Synchronized
    fun started(link: String) {
        val before = request.value ?: return
        if (before.request.videoId == RecordingKey.youTubeIdIn(link)) request.value = before.copy(request = before.request.copy(started = true))
    }

    @Synchronized
    fun retain(sessions: Set<Any>) {
        current.value = current.value.filterKeys { it in sessions }
    }

    fun reading(session: Any, item: MediaItem): ItemReading? =
        current.value[session]?.reading?.takeIf { it.matches(item) }

    @Synchronized
    fun observe(session: Any, seen: ItemObservation, knownVideoMs: (String) -> Long?): ItemReading {
        val last = current.value[session]
        val previous = last?.takeIf { it.reading.matches(seen.item) }
        val id = previous?.reading?.id ?: ItemId { current.value[session]?.reading }
        val item = previous?.reading?.item?.let(seen.item::filling) ?: seen.item
        val active = request.value?.takeIf { it.app == seen.app }?.request
        val clock = clockProof(previous?.clock, seen, active?.started == true)
        val hint = matchingHint(item, seen.asked, previous)
        val unproved = previous?.unprovedVideo ?: unprovedAfter(last, item)
        val evidence = evidenceFor(item, active, hint, unproved, knownVideoMs)
        val exact = evidence.videoId?.let(knownVideoMs)
        val result = classify(previous?.reading, id, item, seen, evidence, exact, clock.advanced)
        current.value = current.value + (session to Current(result, clock, hint, unproved))
        return result
    }

    private fun unprovedAfter(last: Current?, item: MediaItem): String? =
        last?.reading?.adForVideo?.takeIf { item.videoId == null && last.reading.item.anotherEdition(item) }

    private fun clockProof(before: ClockProof?, seen: ItemObservation, afterStart: Boolean): ClockProof {
        val prior = before?.takeIf { it.afterStart == afterStart }
        val start = prior?.startMs ?: seen.positionMs
        val advanced = afterStart && (prior?.advanced == true || seen.playing && advancedFrom(seen.positionMs, start))
        return ClockProof(afterStart, start, advanced)
    }

    private fun matchingHint(item: MediaItem, asked: MusicMetadata?, previous: Current?): MusicMetadata? {
        val old = previous?.hint
        val hint = asked?.takeIf(item::names) ?: return old
        val id = RecordingKey.youTubeId(hint)
        if (id != null && item.videoId != null && id != item.videoId) return old
        return mergeHint(old, hint, id)
    }

    /** Another reader may supply missing information, but cannot toggle the item between competing guesses. */
    private fun mergeHint(old: MusicMetadata?, next: MusicMetadata, nextId: String?): MusicMetadata? {
        if (old == null) return next.takeIf { nextId != null || (it.lengthMs ?: 0) > 0 }
        val oldId = RecordingKey.youTubeId(old)
        if (oldId == null && nextId != null) return next.copy(lengthMs = next.lengthMs ?: old.lengthMs)
        if (oldId != null && nextId != null && oldId != nextId) return old
        val length = old.lengthMs?.takeIf { it > 0 } ?: next.lengthMs
        return old.copy(lengthMs = length)
    }

    private fun advancedFrom(position: Long?, start: Long?): Boolean =
        position != null && start != null && position > start

    private data class Evidence(
        val videoId: String?, val sameSong: Boolean, val asked: ItemRequest?, val softMs: Long?, val requestedExact: Long?, val active: Boolean
    )

    private fun evidenceFor(
        item: MediaItem, active: ItemRequest?, hint: MusicMetadata?, unproved: String?, knownVideoMs: (String) -> Long?
    ): Evidence {
        val request = active ?: hint?.let { song -> RecordingKey.youTubeId(song)?.let { ItemRequest(song, it) } }
        val byName = request?.let { item.names(it.song) } == true
        val sameSong = item.videoId?.let { it == request?.videoId } ?: byName
        return Evidence(item.videoId ?: request?.videoId?.takeIf { sameSong && it != unproved }, sameSong, request,
            request?.song?.lengthMs ?: hint?.lengthMs, request?.videoId?.let(knownVideoMs), active != null)
    }

    private fun classify(
        before: ItemReading?, id: ItemId, item: MediaItem, seen: ItemObservation, evidence: Evidence, exact: Long?, advanced: Boolean
    ): ItemReading {
        val raw = item.durationMs
        val history = seen.knownByTitleMs.takeIf { item.videoId == null }
        confirmedAd(before, id, item, seen, evidence, exact, history)?.let { return it }
        val mayBeShort = if (evidence.active) shorter(raw, evidence.softMs) else plausibleAd(raw, evidence.softMs)
        val anonymousNext = isAmbiguousNext(item, evidence, mayBeShort)
        if (anonymousNext) return ambiguous(before, id, item, seen, evidence, advanced)
        val usingEstimate = evidence.asked == null || evidence.sameSong && exact == null
        if (mayBeShort && usingEstimate) {
            return provisionalSong(before, id, item, seen, evidence.softMs)
        }
        val estimate = if (raw != null && raw < AD_DURATION_HINT_MS) evidence.softMs else history
        val known = exact ?: estimate ?: before?.durationMs?.takeIf { before.kind == ItemKind.SONG }
        return ItemReading(id, item, ItemKind.SONG, rounded(raw, known))
    }

    private fun confirmedAd(
        before: ItemReading?, id: ItemId, item: MediaItem, seen: ItemObservation, evidence: Evidence, exact: Long?, history: Long?
    ): ItemReading? {
        val stored = before?.adForVideo?.takeIf { evidence.videoId == null || it == evidence.videoId }
        val adFor = exactAdFor(item.durationMs, evidence, exact) ?: stored
        val byHistory = item.rawId == null && evidence.softMs == null && plausibleAd(item.durationMs, history)
        if (adFor == null && !seen.reportedAd && !byHistory) return null
        val sharedDuration = before?.durationMs?.takeIf { stored != null }
        return ItemReading(id, item, ItemKind.AD, exact ?: sharedDuration ?: history ?: item.durationMs, adForVideo = adFor)
    }

    /** A passive read may be of a trimmed video. There is no request to hold while that is established. */
    private fun exactAdFor(raw: Long?, evidence: Evidence, exact: Long?): String? =
        evidence.videoId?.takeIf { raw != null && shorter(raw, exact) && (evidence.active || raw < AD_DURATION_HINT_MS) }

    private fun isAmbiguousNext(item: MediaItem, evidence: Evidence, mayBeShort: Boolean): Boolean {
        val request = evidence.asked ?: return false
        if (item.videoId != null || evidence.sameSong) return false
        if (item.durationMs == null) return request.started
        return mayBeShort || shorter(item.durationMs, evidence.requestedExact)
    }

    private fun ambiguous(
        before: ItemReading?, id: ItemId, item: MediaItem, seen: ItemObservation, evidence: Evidence, advanced: Boolean
    ): ItemReading {
        if (advanced) return ItemReading(id, item, ItemKind.SONG, item.durationMs)
        val canWait = evidence.asked?.started == false && evidence.requestedExact == null
        return waiting(before, id, item, seen.nowMs, item.durationMs, canWait)
    }

    private fun provisionalSong(before: ItemReading?, id: ItemId, item: MediaItem, seen: ItemObservation, expected: Long?): ItemReading {
        val wait = waiting(before, id, item, seen.nowMs, expected, allow = true)
        if (wait.canFinishPlay && seen.positionMs != null) return wait.copy(kind = ItemKind.SONG, durationMs = item.durationMs)
        return wait
    }

    private fun waiting(previous: ItemReading?, id: ItemId, item: MediaItem, now: Long, duration: Long?, allow: Boolean): ItemReading {
        if (previous?.canFinishPlay == true) return ItemReading(id, item, ItemKind.UNKNOWN, duration, canFinishPlay = true)
        if (!allow) return ItemReading(id, item, ItemKind.UNKNOWN, duration)
        val until = previous?.checkAtMs ?: now + UNCONFIRMED_AD_WAIT_MS
        return ItemReading(id, item, ItemKind.UNKNOWN, duration, until.takeIf { now < it }, canFinishPlay = now >= until)
    }

    private fun rounded(actual: Long?, expected: Long?): Long? =
        expected?.takeIf { it > 0 && actual != null && abs(it - actual) <= SteadyLengths.SAME_MS } ?: actual

    private fun shorter(actual: Long?, expected: Long?): Boolean =
        actual != null && expected != null && expected - actual > SteadyLengths.SAME_MS

    private fun plausibleAd(actual: Long?, expected: Long?): Boolean =
        actual != null && expected != null && isYouTubeAdLength(actual, expected)
}
