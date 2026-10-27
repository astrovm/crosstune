package com.astrovm.crosstune

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PlayQueueTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val music = MusicService.YOUTUBE_MUSIC.packageName
    private val phone = FakePlayback()
    private val file = File(app.noBackupFilesDir, "play_queue.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val tracks = (1..120).map { MusicMetadata("Song $it", "Band") }

    /** Each part looked up, as the songs asked for. */
    private val looked = mutableListOf<List<MusicMetadata>>()

    /** Whether a part's songs are found; each part is found whole, linked by its first song. */
    private var finds = true

    private fun queue(file: File = this.file, scope: CoroutineScope = this.scope) = PlayQueue(
        phone,
        { _, songs -> looked += songs; if (finds) YouTubeQueue("https://music.youtube.com/watch?v=${songs.first().title}&list=TL", songs) else null },
        file,
        scope,
        Dispatchers.Unconfined
    )

    @After
    fun tearDown() {
        scope.cancel()
        MainActivity.playbackFactory = ::MediaSessionPlayback
        MainActivity.lookupDispatcher = Dispatchers.IO
    }

    /** YouTube Music plays [number] of the songs, numbered [id] in its queue, or something else by its name. */
    private fun plays(song: String, id: Long? = null) {
        phone.inApp(music).value = AppPlays(MusicMetadata(song, "Band"), id)
    }

    private fun plays(number: Int, id: Long? = null) = plays("Song $number", id)

    private fun PlayQueue.playing(to: Int = 50) = runBlocking { play(tracks, MusicService.YOUTUBE_MUSIC, music, to, tracks.subList(to - 50, to)) }

    @Test
    fun aLongListPlaysOnPartByPartByTheNumbersYouTubeMusicGivesItsSongs() {
        queue().playing()
        // What played before the first part isn't any of it.
        plays("Before", 7)
        assertTrue(looked.isEmpty())
        // Once it plays, the next part's looked up, so it's there as this one ends.
        plays(1, 100)
        assertEquals(listOf(tracks.subList(50, 100)), looked)
        plays(50, 149)
        assertTrue(phone.listsAsked.isEmpty())
        // On from the last song to what YouTube Music plays after it, the next part plays.
        plays("Radio", 150)
        assertEquals(listOf(music to "https://music.youtube.com/watch?v=Song 51&list=TL"), phone.listsAsked)
        // Until it does, what's still playing is no part of it.
        plays("Radio", 150)
        plays(51, 300)
        assertEquals(tracks.subList(100, 120), looked.last())
        plays(100, 349)
        plays("Radio", 350)
        assertEquals("https://music.youtube.com/watch?v=Song 101&list=TL", phone.listsAsked.last().second)
        // The last part has nothing after it: done, and nothing kept.
        plays(101, 500)
        plays(120, 519)
        plays("Radio", 520)
        assertEquals(2, phone.listsAsked.size)
        assertEquals(2, looked.size)
        assertFalse(file.exists())
    }

    @Test
    fun songsNamedOtherwiseByTheAppAreFollowedByItsNumbers() {
        queue().playing()
        plays(1, 100)
        // Named another way, it's still the 26th of the part.
        plays("Canción 26", 125)
        plays("Last One", 149)
        plays("Radio", 150)
        assertEquals(1, phone.listsAsked.size)
    }

    @Test
    fun withoutNumbersTheLastSongEndsThePartByItsName() {
        // As YouTube, which shows no queue.
        queue().playing()
        plays(1)
        plays(49)
        plays(50)
        assertTrue(phone.listsAsked.isEmpty())
        plays("Radio")
        assertEquals(1, phone.listsAsked.size)
    }

    @Test
    fun somethingElsePickedToPlayStopsIt() {
        queue().playing()
        plays(3, 102)
        // Another list, numbered on after what YouTube Music had queued.
        plays("Other", 900)
        assertFalse(file.exists())
        plays(50, 149)
        plays("Radio", 150)
        assertTrue(phone.listsAsked.isEmpty())
        // Without numbers, by its name.
        queue().playing()
        plays(3)
        plays("Other")
        assertFalse(file.exists())
        // Picked as the last song played, YouTube Music numbers it past what it played after.
        queue().playing()
        plays(1, 100)
        plays(50, 149)
        plays("Other", 180)
        plays("Radio", 150)
        assertTrue(phone.listsAsked.isEmpty())
    }

    @Test
    fun itCarriesOnAfterCrosstuneIsStopped() {
        val stopped = CoroutineScope(Dispatchers.Unconfined)
        queue(scope = stopped).playing()
        plays(1, 100)
        // Android stops Crosstune, and starts it again to show it what plays.
        stopped.cancel()
        val again = queue()
        again.resume()
        // Already playing through, it carries on as it was.
        again.resume()
        plays(50, 149)
        plays("Radio", 150)
        assertEquals(1, phone.listsAsked.size)
    }

    @Test
    fun theListenerAndroidConnectsCarriesItOn() {
        val stopped = CoroutineScope(Dispatchers.Unconfined)
        val sixty = tracks.take(60)
        runBlocking { queue(scope = stopped).play(sixty, MusicService.YOUTUBE_MUSIC, music, 50, sixty.take(50)) }
        plays(1, 100)
        plays(50, 149)
        plays("Radio", 150)
        stopped.cancel()
        // Started again to show Crosstune what plays, it carries the last part on to its end.
        MainActivity.playbackFactory = { phone }
        MainActivity.lookupDispatcher = Dispatchers.Unconfined
        Robolectric.buildService(NowPlayingListener::class.java).create().get().onListenerConnected()
        plays(51, 300)
        plays(60, 309)
        assertTrue(file.exists())
        plays("Radio", 310)
        assertFalse(file.exists())
        assertEquals(1, phone.listsAsked.size)
    }

    @Test
    fun nothingKeptOrNothingReadableResumesNothing() {
        queue().resume()
        for (kept in listOf("not json", "{}", """{"tracks":[],"service":"NOWHERE","app":"x","to":0,"part":[]}""")) {
            file.writeText(kept)
            queue().resume()
            plays(1, 100)
            assertTrue(looked.isEmpty())
        }
    }

    @Test
    fun aListNoLongerThanThePartPlaysNothingOn() {
        val queue = queue()
        runBlocking { queue.play(tracks.take(50), MusicService.YOUTUBE_MUSIC, music, 50, tracks.take(50)) }
        assertFalse(file.exists())
        plays(1, 100)
        assertTrue(looked.isEmpty())
    }

    @Test
    fun anotherListPlayedThroughTakesItsPlace() {
        val queue = queue()
        queue.playing()
        plays(1, 100)
        val others = (1..60).map { MusicMetadata("Other $it", "Band") }
        runBlocking { queue.play(others, MusicService.YOUTUBE_MUSIC, music, 50, others.take(50)) }
        plays(50, 149)
        plays("Radio", 150)
        assertTrue(phone.listsAsked.isEmpty())
        plays("Other 1", 200)
        plays("Other 50", 249)
        plays("Radio", 250)
        assertEquals("https://music.youtube.com/watch?v=Other 51&list=TL", phone.listsAsked.single().second)
    }

    @Test
    fun nothingFoundOrNoAppToAskStopsIt() {
        finds = false
        queue().playing()
        plays(1, 100)
        plays(50, 149)
        plays("Radio", 150)
        assertTrue(phone.listsAsked.isEmpty())
        assertFalse(file.exists())
        finds = true
        phone.canPlaySongs = false
        queue().playing()
        plays(1, 300)
        plays(50, 349)
        plays("Radio", 350)
        assertFalse(file.exists())
    }

    @Test
    fun keptNowhereItStillPlaysOn() {
        // Where it's kept can't be written, as its folder is a file.
        val folder = File(app.cacheDir, "not a folder").apply { writeText("") }
        queue(File(folder, "play_queue.json")).playing()
        plays(1, 100)
        plays(50, 149)
        plays("Radio", 150)
        assertEquals(1, phone.listsAsked.size)
    }
}
