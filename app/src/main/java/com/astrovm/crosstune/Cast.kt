package com.astrovm.crosstune

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import org.json.JSONObject

/*
 * Casting a song to Crosstune on a TV: the phone hands the song over, the TV's Crosstune plays it as it
 * does a song opened there, with the words over it, and the phone becomes its remote. Google Cast does the
 * carrying, which is Google Play Services; everything here is Crosstune's own and knows nothing of Google,
 * so the build without Google Play Services (the foss flavor) has the same app with [NoCast] in its place.
 */

/** The channel the phone and the TV's Crosstune send each other what Cast has no word for, as JSON. */
internal const val CAST_NAMESPACE = "urn:x-cast:com.astrovm.crosstune"

/** A song handed to the TV: by its [link] when it has one, otherwise by its name. */
internal data class CastSong(
    val title: String,
    val artist: String,
    val link: String? = null,
    val artworkUrl: String? = null,
    val durationMs: Long? = null
) {
    fun toMetadata() = MusicMetadata(title, artist, artworkUrl = artworkUrl, url = link, lengthMs = durationMs)

    /** What a LOAD carries in its custom data. */
    fun toJson(): JSONObject = JSONObject().put(SONG, JSONObject().put(TITLE, title).put(ARTIST, artist).apply {
        link?.let { put(LINK, it) }
        artworkUrl?.let { put(ARTWORK, it) }
        durationMs?.let { put(DURATION, it) }
    })

    companion object {
        private const val SONG = "song"
        private const val TITLE = "title"
        private const val ARTIST = "artist"
        private const val LINK = "link"
        private const val ARTWORK = "artwork"
        private const val DURATION = "duration"

        /** The song a LOAD's custom data carries; null when it has none, or no title to go by. */
        fun fromJson(json: JSONObject?): CastSong? {
            val song = json?.optJSONObject(SONG) ?: return null
            val title = song.optString(TITLE).ifBlank { return null }
            return CastSong(
                title,
                song.optString(ARTIST),
                song.optString(LINK).ifBlank { null },
                song.optString(ARTWORK).ifBlank { null },
                if (song.has(DURATION)) song.optLong(DURATION) else null
            )
        }
    }
}

/** What the phone asks of the TV. */
internal sealed interface CastCommand {
    data object Play : CastCommand
    data object Pause : CastCommand
    data object Stop : CastCommand
    data class Seek(val positionMs: Long) : CastCommand
    data object Next : CastCommand
    data object Previous : CastCommand
    data class Translation(val on: Boolean) : CastCommand
    data class Readings(val on: Boolean) : CastCommand

    companion object {
        /** The command a [message] over [CAST_NAMESPACE] is; null for anything else a sender might say. */
        fun fromMessage(message: String): CastCommand? {
            val json = runCatching { JSONObject(message) }.getOrNull() ?: return null
            val on = json.optBoolean(ON)
            return when (json.optString(TYPE)) {
                TRANSLATION -> Translation(on)
                READINGS -> Readings(on)
                else -> null
            }
        }
    }
}

private const val TYPE = "type"
private const val ON = "on"
private const val TRANSLATION = "translation"
private const val READINGS = "readings"

/** What goes over [CAST_NAMESPACE] for the ones Cast has no command for; null for the rest. */
internal fun CastCommand.message(): String? = when (this) {
    is CastCommand.Translation -> JSONObject().put(TYPE, TRANSLATION).put(ON, on).toString()
    is CastCommand.Readings -> JSONObject().put(TYPE, READINGS).put(ON, on).toString()
    else -> null
}

/** What the TV plays and how, as it tells the phone. */
internal data class CastTvState(
    val title: String,
    val artist: String,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long? = null,
    val canSeek: Boolean = false,
    val canSkipNext: Boolean = false,
    val canSkipPrevious: Boolean = false
)

/** The TV the phone casts to: its [name], and, once it says, what it plays. */
internal data class CastTarget(val name: String, val state: CastTvState? = null)

/** The phone's side of casting. */
internal interface CastSender {
    /** Whether a TV with Crosstune is on the network to cast to, or one already is cast to. */
    val around: StateFlow<Boolean>

    /** The TV cast to, null while none is. */
    val target: StateFlow<CastTarget?>

    /** Asks which TV to cast to; the first one picked is [target]. */
    fun chooseTv(context: Context)

    /** Shows the TV cast to, with the way to stop casting. */
    fun showTv(context: Context)

    /** Has the TV play [song]. */
    fun load(song: CastSong)

    fun send(command: CastCommand)

    /** Stops casting; the TV's Crosstune carries on with what it plays. */
    fun disconnect()
}

/** The TV's side of casting: Crosstune answering the phones that cast to it. */
internal interface CastReceiver {
    /** Starts answering: [onLoad] is a song to play, [onCommand] something asked of it. */
    fun start(onLoad: (CastSong) -> Unit, onCommand: (CastCommand) -> Unit)

    /** What plays now, which the phones show; null once nothing does. */
    fun publish(state: CastTvState?)

    /** Hands over an intent that may be a phone's cast, which opened Crosstune; true when it was. */
    fun handle(intent: Intent): Boolean

    fun stop()
}

/** No casting at all, as in a build without Google Play Services, or on a phone that has none. */
internal object NoCast : CastSender, CastReceiver {
    override val around: StateFlow<Boolean> = MutableStateFlow(false)
    override val target: StateFlow<CastTarget?> = MutableStateFlow(null)
    override fun chooseTv(context: Context) = Unit
    override fun showTv(context: Context) = Unit
    override fun load(song: CastSong) = Unit
    override fun send(command: CastCommand) = Unit
    override fun disconnect() = Unit
    override fun start(onLoad: (CastSong) -> Unit, onCommand: (CastCommand) -> Unit) = Unit
    override fun publish(state: CastTvState?) = Unit
    override fun handle(intent: Intent) = false
    override fun stop() = Unit
}

/**
 * [source] with the TV in the place of the app that played [castSong] on the phone, while it is cast: where it
 * is, and play, pause, move and skip, are the TV's. Everything else, and every other song, is [source]'s.
 */
internal class CastPlayback(
    private val source: PlaybackSource,
    private val cast: CastSender,
    private val now: () -> Long
) : PlaybackSource by source {
    @Volatile
    private var casting: MusicMetadata? = null

    /** The song handed to the TV, whose controls are the TV's from now on; null for none. */
    fun casting(song: MusicMetadata?) {
        casting = song
    }

    private fun tv(song: MusicMetadata): CastTarget? = cast.target.value?.takeIf { casting?.let { c -> sameSong(c, song) } == true }

    // Cast to a TV, what plays is known without Android's say.
    override fun hasAccess() = source.hasAccess() || (casting != null && cast.target.value != null)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun follow(song: MusicMetadata): Flow<Following?> = cast.target.flatMapLatest { target ->
        if (tv(song) == null) source.follow(song) else flowOf(target?.let(::following))
    }

    private fun following(target: CastTarget): Following? = target.state?.let {
        Following(
            PlaybackClock(it.positionMs, now(), playing = it.playing),
            app = target.name,
            canSeek = it.canSeek,
            canSkipNext = it.canSkipNext,
            canSkipPrevious = it.canSkipPrevious,
            durationMs = it.durationMs
        )
    }

    override fun seekTo(song: MusicMetadata, positionMs: Long) =
        if (tv(song) != null) cast.send(CastCommand.Seek(positionMs)) else source.seekTo(song, positionMs)

    override fun playPause(song: MusicMetadata, play: Boolean) =
        if (tv(song) != null) cast.send(if (play) CastCommand.Play else CastCommand.Pause) else source.playPause(song, play)

    override fun skip(song: MusicMetadata, next: Boolean) =
        if (tv(song) != null) cast.send(if (next) CastCommand.Next else CastCommand.Previous) else source.skip(song, next)

    // The song is the TV's: asked to play, the phone's app must not start it again.
    override suspend fun playSong(song: MusicMetadata, app: String?, link: String?, mustStart: Boolean) =
        tv(song) != null || source.playSong(song, app, link, mustStart)
}
