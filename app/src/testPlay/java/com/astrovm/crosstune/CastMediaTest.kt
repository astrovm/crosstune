package com.astrovm.crosstune

import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.google.android.gms.cast.RequestData
import com.google.android.gms.cast.tv.media.QueueUpdateRequestData
import com.google.android.gms.cast.tv.media.SeekRequestData
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CastMediaTest {
    private val song = CastSong("Song", "Artist", "https://example.com/song", "https://example.com/art.jpg", 123_000)

    @Test
    fun loadCarriesTheSongInBothPlacesWithAutoplayAndDisplayMetadata() {
        val request = castLoadRequest(song)
        val info = requireNotNull(request.mediaInfo)
        assertEquals(true, request.autoplay)
        assertEquals(song.link, info.contentId)
        assertEquals("audio/*", info.contentType)
        assertEquals(MediaInfo.STREAM_TYPE_BUFFERED, info.streamType)
        assertEquals(123_000L, info.streamDuration)
        val metadata = requireNotNull(info.metadata)
        assertEquals(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK, metadata.mediaType)
        assertEquals(song.title, metadata.getString(MediaMetadata.KEY_TITLE))
        assertEquals(song.artist, metadata.getString(MediaMetadata.KEY_ARTIST))
        assertEquals(song.artworkUrl, metadata.images.single().url.toString())
        assertEquals(song, CastSong.fromJson(info.customData))
        assertEquals(song, CastSong.fromJson(request.customData))
        assertEquals(song, castLoadSong(request))
    }

    @Test
    fun aBareSongUsesItsTitleAndDoesNotInventArtOrDuration() {
        val bare = CastSong("Bare", "")
        val request = castLoadRequest(bare)
        val info = requireNotNull(request.mediaInfo)
        assertEquals("Bare", info.contentId)
        assertTrue(requireNotNull(info.metadata).images.isEmpty())
        assertEquals(-1L, info.streamDuration)
        assertEquals(bare, castLoadSong(request))
    }

    @Test
    fun mediaCustomDataWinsAndInvalidMediaDataFallsBackToRequestData() {
        val fallback = song.copy(title = "Fallback")
        fun request(data: JSONObject?) = MediaLoadRequestData.Builder()
            .setMediaInfo(MediaInfo.Builder("id").setCustomData(data).build())
            .setCustomData(fallback.toJson()).build()
        assertEquals(song, castLoadSong(request(song.toJson())))
        assertEquals(fallback, castLoadSong(request(JSONObject())))
        assertEquals(fallback, castLoadSong(request(null)))
        assertEquals(fallback, castLoadSong(MediaLoadRequestData.Builder().setCustomData(fallback.toJson()).build()))
        assertNull(castLoadSong(MediaLoadRequestData.Builder().build()))
    }

    @Test
    fun queueJumpUsesOnlyItsSignAndSeekKeepsItsExactPosition() {
        listOf(null, 0).forEach { assertNull(castJump(it)) }
        listOf(1, Int.MAX_VALUE).forEach { assertEquals(CastCommand.Next, castJump(it)) }
        listOf(-1, Int.MIN_VALUE).forEach { assertEquals(CastCommand.Previous, castJump(it)) }
        assertEquals(CastCommand.Seek(0), castSeek(null))
        listOf(0L, 1L, Long.MAX_VALUE).forEach { assertEquals(CastCommand.Seek(it), castSeek(it)) }
    }

    @Test
    fun publicationClearsOldMetadataAndPlaybackWhenTheSongEnds() {
        val publication = castPublication(null)
        assertEquals("", publication.metadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE))
        assertEquals("", publication.metadata.getString(MediaMetadataCompat.METADATA_KEY_ARTIST))
        assertEquals(-1L, publication.metadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION))
        assertEquals(PlaybackStateCompat.STATE_NONE, publication.playback.state)
        assertEquals(0L, publication.playback.position)
        assertEquals(0f, publication.playback.playbackSpeed)
        assertEquals(baseActions, publication.playback.actions)
    }

    private val baseActions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_STOP

    @Test
    fun everyControlIsPublishedIndependentlyWithExactMetadataAndPlayback() {
        for (mask in 0..7) for (playing in listOf(false, true)) {
            val state = CastTvState("Song", "Artist", playing, 9_001, 123_000,
                canSeek = mask and 1 != 0, canSkipNext = mask and 2 != 0, canSkipPrevious = mask and 4 != 0)
            val publication = castPublication(state)
            assertEquals("Song", publication.metadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE))
            assertEquals("Artist", publication.metadata.getString(MediaMetadataCompat.METADATA_KEY_ARTIST))
            assertEquals(123_000L, publication.metadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION))
            assertEquals(if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED, publication.playback.state)
            assertEquals(9_001L, publication.playback.position)
            assertEquals(if (playing) 1f else 0f, publication.playback.playbackSpeed)
            var expected = baseActions
            if (mask and 1 != 0) expected = expected or PlaybackStateCompat.ACTION_SEEK_TO
            if (mask and 2 != 0) expected = expected or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
            if (mask and 4 != 0) expected = expected or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            assertEquals(expected, publication.playback.actions)
        }
        assertEquals(-1L, castPublication(CastTvState("", "", false, 0)).metadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION))
    }

    @Test
    fun theSenderReadsPausedPlayingBufferingAndEveryIndependentCapability() {
        val info = requireNotNull(castLoadRequest(song).mediaInfo)
        for (player in listOf("PLAYING", "BUFFERING", "PAUSED", "IDLE", "LOADING", "UNKNOWN")) {
            for (commands in listOf(0L, MediaStatus.COMMAND_SEEK, MediaStatus.COMMAND_QUEUE_NEXT, MediaStatus.COMMAND_QUEUE_PREVIOUS)) {
                val status = MediaStatus(JSONObject().put("mediaSessionId", 1).put("playerState", player).put("supportedMediaCommands", commands))
                for (duration in listOf(-1L, 0L, 1L, 123_000L)) {
                    assertEquals(CastTvState("Song", "Artist", player in listOf("PLAYING", "BUFFERING"), 123,
                        duration.takeIf { it > 0 }, commands == MediaStatus.COMMAND_SEEK,
                        commands == MediaStatus.COMMAND_QUEUE_NEXT, commands == MediaStatus.COMMAND_QUEUE_PREVIOUS),
                        castRemoteState(info, status, 123, duration))
                }
            }
        }
        val status = MediaStatus(JSONObject().put("mediaSessionId", 1))
        assertEquals(CastTvState("", "", false, 0), castRemoteState(MediaInfo.Builder("id").build(), status, 0, 0))
    }
    @Test
    fun receiverCallbacksPostCommandsBeforeAcknowledgingThem() {
        val posted = mutableListOf<() -> Unit>()
        val received = mutableListOf<CastCommand>()
        val callback = castCommandCallback({ received += it }, { posted += it })
        val request = object : RequestData {
            override fun getRequestId() = 1L
            override fun getCustomData(): JSONObject? = null
        }
        val results = listOf(callback.onPlay(null, request), callback.onPause(null, request), callback.onStop(null, request),
            callback.onSeek(null, SeekRequestData(1L, null, 0, 9_001L, null)),
            callback.onSeek(null, SeekRequestData(1L, null, 0, null, null)))
        results.forEach { assertTrue(it.isSuccessful); assertNull(it.result) }
        assertTrue(received.isEmpty())
        posted.forEach { it() }
        assertEquals(listOf(CastCommand.Play, CastCommand.Pause, CastCommand.Stop, CastCommand.Seek(9_001), CastCommand.Seek(0)), received)
        for (jump in listOf(null, 0, 1, -1, Int.MAX_VALUE, Int.MIN_VALUE)) {
            posted.clear()
            received.clear()
            val queue = QueueUpdateRequestData.zza(JSONObject().put("requestId", 1).apply { jump?.let { put("jump", it) } })
            assertEquals(jump, queue.jump)
            assertTrue(callback.onQueueUpdate(null, queue).isSuccessful)
            posted.forEach { it() }
            assertEquals(listOfNotNull(castJump(jump)), received)
        }
    }

    @Test
    fun receiverLoadPostsOnlyValidSongsAndReturnsTheOriginalRequest() {
        val posted = mutableListOf<() -> Unit>()
        val received = mutableListOf<CastSong>()
        val callback = castLoadCallback({ received += it }, { posted += it })
        val request = castLoadRequest(song)
        val result = callback.onLoad("sender", request)
        assertTrue(result.isSuccessful)
        assertSame(request, result.result)
        assertTrue(received.isEmpty())
        posted.single()()
        assertEquals(listOf(song), received)
        posted.clear()
        val empty = MediaLoadRequestData.Builder().build()
        assertSame(empty, callback.onLoad(null, empty).result)
        assertTrue(posted.isEmpty())
    }

}
