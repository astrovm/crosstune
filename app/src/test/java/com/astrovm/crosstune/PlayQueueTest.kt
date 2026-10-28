package com.astrovm.crosstune

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    private val tracks = (1..120).map { MusicMetadata("Song $it", "Band", url = "https://open.spotify.com/track/$it".takeIf { _ -> it % 2 == 0 }) }

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

    /** YouTube Music plays [number] of the songs, numbered [id] in its queue, or something else by its name; or stopped at its end, once [ended]. */
    private fun plays(song: String, id: Long? = null, ended: Boolean = false) {
        phone.inApp(music).value = AppPlays(MusicMetadata(song, "Band"), id, ended)
    }

    private fun plays(number: Int, id: Long? = null, ended: Boolean = false) = plays("Song $number", id, ended)

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
    fun stoppedAtTheEndOfThePartsLastSongTheNextPartPlays() {
        // As YouTube, which plays nothing after a list's last song.
        queue().playing()
        plays(1)
        // Stopped at another song's end, it only waits.
        plays(10, ended = true)
        assertTrue(phone.listsAsked.isEmpty())
        assertTrue(file.exists())
        plays(50)
        plays(50, ended = true)
        assertEquals(1, phone.listsAsked.size)
    }

    @Test
    fun whatPlayedAsThePartWasAskedForIsFromBefore() {
        // One of its songs, played on its own just before, by the number YouTube Music gave it then.
        plays(1, 5)
        val queue = queue()
        queue.playing()
        plays(1, 100)
        // All YouTube Music queued then is from before too, that song's number included.
        plays(3, 4)
        plays("Another Name", 5)
        plays(50, 149)
        plays("Radio", 150)
        assertEquals(1, phone.listsAsked.size)
        // Stopped at the end of the part's last song, played through before; the part plays first.
        plays(50, ended = true)
        queue.playing()
        assertEquals(1, phone.listsAsked.size)
        plays(1)
        plays(50)
        plays(50, ended = true)
        assertEquals(2, phone.listsAsked.size)
    }

    @Test
    fun stoppedItLetsGoOfTheAppAndOfALookupStillGoing() {
        val lookup = CompletableDeferred<YouTubeQueue?>()
        var cancelled = false
        val queue = PlayQueue(phone, { _, _ -> try { lookup.await() } catch (stop: CancellationException) { cancelled = true; throw stop } }, file, scope, Dispatchers.Unconfined)
        queue.playing()
        plays(1, 100)
        assertEquals(1, phone.inApp(music).subscriptionCount.value)
        queue.stop()
        assertEquals(0, phone.inApp(music).subscriptionCount.value)
        assertTrue(cancelled)
    }

    @Test
    fun aPartShuffledInTheAppPlaysOnOnceItsSongsAllPlayed() {
        val queue = queue()
        queue.playing()
        plays(1, 100)
        // Shuffled, YouTube Music plays the part's songs out of order, each by its number.
        plays(50, 149)
        (2..49).forEach { plays(it, 99L + it) }
        assertTrue(phone.listsAsked.isEmpty())
        // Then on to what it plays after them all.
        plays("Radio", 150)
        assertEquals(listOf(music to "https://music.youtube.com/watch?v=Song 51&list=TL"), phone.listsAsked)

        // Something picked before they all played is something else to play, as without shuffle.
        queue.playing()
        plays(1, 1000)
        plays(50, 1049)
        plays(2, 1001)
        plays("Other", 1050)
        assertFalse(file.exists())
        assertEquals(1, phone.listsAsked.size)
    }

    @Test
    fun somethingElsePickedToPlayStopsIt() {
        val queue = queue()
        queue.playing()
        plays(1, 100)
        // Another list, numbered on after what YouTube Music had queued.
        plays("Other", 900)
        assertFalse(file.exists())
        plays(50, 149)
        plays("Radio", 150)
        assertTrue(phone.listsAsked.isEmpty())
        // Picked as the last song played, YouTube Music numbers it past what it played after.
        queue.playing()
        plays(1, 1000)
        plays(50, 1049)
        plays("Other", 1080)
        plays("Radio", 1050)
        assertTrue(phone.listsAsked.isEmpty())
    }

    @Test
    fun withoutNumbersTheAppsOwnNamesForTheSongsAreFollowed() {
        // As YouTube names what it found: another version of the song, its video, or something else.
        queue().playing()
        plays(1)
        plays("Something Else")
        assertTrue(file.exists())
        // Another's song by the same name isn't the list's.
        phone.inApp(music).value = AppPlays(MusicMetadata("Song 50", "Someone Else"), null, ended = true)
        assertTrue(phone.listsAsked.isEmpty())
        plays("Song 50 (Live at Home) [2019 Remaster]")
        plays("Radio")
        assertEquals(1, phone.listsAsked.size)
        plays("Band - Song 51 (Official Video)")
        plays("Band - Song 100 (Official Video)", ended = true)
        assertEquals(2, phone.listsAsked.size)
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
        // Already playing through, it carries on as it was: by YouTube Music's numbers, and the songs' links.
        again.resume()
        plays("Last One", 149)
        assertEquals(tracks.subList(50, 100), looked.last())
        plays("Radio", 150)
        assertEquals(1, phone.listsAsked.size)
    }

    @Test
    fun aShuffledPartCarriesOnAfterCrosstuneIsStopped() {
        val stopped = CoroutineScope(Dispatchers.Unconfined)
        queue(scope = stopped).playing()
        plays(1, 100)
        plays(50, 149)
        (2..49).forEach { plays(it, 99L + it) }
        stopped.cancel()
        // Started again, it still knows they all played, so it plays on.
        queue().resume()
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

    private val tidal = MusicService.TIDAL.packageName

    /** TIDAL plays [song], numbered by what it is, after the song numbered [after] in its queue; or stopped at its end, once [ended]. */
    private fun tidalPlays(song: String, id: Long?, after: Long? = null, ended: Boolean = false) {
        phone.inApp(tidal).value = AppPlays(MusicMetadata(song, "Band"), id, ended, after)
    }

    /** The first [songs] of the list played in TIDAL, which takes no list, from the first, just asked for. */
    private fun PlayQueue.playingInTidal(songs: Int = 3) = runBlocking { play(tracks.take(songs), MusicService.TIDAL, tidal, 1, tracks.take(1)) }

    @Test
    fun anAppThatTakesNoListIsAskedForTheSongsOneByOne() {
        // What TIDAL played before, numbered higher than the list's songs, as it numbers them by what they are.
        tidalPlays("Before", 9_000)
        queue().playingInTidal()
        tidalPlays("Song 1", 155)
        assertTrue(phone.songsAsked.isEmpty())
        // On to its radio, right after the song: the next is asked for by name, with nothing looked up.
        tidalPlays("Radio", 143, after = 155)
        assertEquals(listOf("Song 2" to tidal), phone.songsAsked.map { it.first.title to it.second })
        // Until it plays, the radio playing on is no part of it.
        tidalPlays("More Radio", 144, after = 143)
        tidalPlays("Song 2", 20)
        // Played to its end with nothing after it, as with TIDAL's radio off, the next plays too.
        tidalPlays("Song 2", 20, ended = true)
        assertEquals(listOf("Song 2", "Song 3"), phone.songsAsked.map { it.first.title })
        assertTrue(file.exists())
        // The last has nothing after it: done, and nothing kept.
        tidalPlays("Song 3", 7)
        tidalPlays("Radio", 8, after = 7)
        assertEquals(2, phone.songsAsked.size)
        assertFalse(file.exists())
        assertTrue(looked.isEmpty())
    }

    @Test
    fun somethingElsePickedInAnAppThatTakesNoListStopsIt() {
        queue().playingInTidal()
        tidalPlays("Song 1", 155)
        // Another album, which takes the place of TIDAL's queue.
        tidalPlays("Other", 300, after = 299)
        assertFalse(file.exists())
        tidalPlays("Radio", 143, after = 155)
        assertTrue(phone.songsAsked.isEmpty())
        // With no app to ask for the next, it stops too.
        queue().playingInTidal()
        tidalPlays("Song 1", 156)
        phone.canPlaySongs = false
        tidalPlays("Radio", 143, after = 156)
        assertFalse(file.exists())
    }

    @Test
    fun skippingOnInAnAppThatTakesNoListAsksForTheNextSong() {
        val queue = queue()
        val playback = queue.skipping(phone)
        phone.playing.value = Following(PlaybackClock(0, 0), "TIDAL", canSeek = true)
        queue.playingInTidal()
        tidalPlays("Song 1", 155)
        assertEquals(true, playback.canSkipOn(1))
        playback.skipFrom(1)
        assertEquals(listOf("Song 2"), phone.songsAsked.map { it.first.title })
        assertTrue(phone.skips.isEmpty())
    }

    /** Whether the app playing [number] can skip on from it, as Crosstune shows. */
    private fun PlaybackSource.canSkipOn(number: Int) = runBlocking { follow(MusicMetadata("Song $number", "Band")).first() }?.canSkipNext

    private fun PlaybackSource.skipFrom(number: Int, next: Boolean = true) = skip(MusicMetadata("Song $number", "Band"), next)

    @Test
    fun skippingOnFromAPartsLastSongPlaysTheNextPartAtOnce() {
        val queue = queue()
        val playback = queue.skipping(phone)
        // As YouTube at a list's last video, which it can't skip on from.
        phone.playing.value = Following(PlaybackClock(0, 0), "YouTube Music", canSeek = true)
        queue.playing()
        for (number in 1..49) plays(number, 99L + number)
        assertEquals(false, playback.canSkipOn(49))
        playback.skipFrom(49)
        assertEquals(listOf(true), phone.skips)
        plays(50, 149)
        assertEquals(true, playback.canSkipOn(50))
        // Another song isn't the one ending the part.
        assertEquals(false, playback.canSkipOn(12))
        // Back stays the app's.
        playback.skipFrom(50, next = false)
        assertEquals(listOf(true, false), phone.skips)
        playback.skipFrom(50)
        assertEquals(listOf(music to "https://music.youtube.com/watch?v=Song 51&list=TL"), phone.listsAsked)
        assertEquals(listOf(true, false), phone.skips)
        // Asked again before the next part plays, the app skips on as it would.
        playback.skipFrom(50)
        assertEquals(listOf(true, false, true), phone.skips)
        assertEquals(1, phone.listsAsked.size)
        // With nothing playing, there's nothing to show.
        phone.playing.value = null
        assertNull(playback.canSkipOn(50))
    }

    @Test
    fun aPartPlayedBackAndOnAgainEndsOnItsLastSong() {
        val queue = queue()
        val playback = queue.skipping(phone)
        phone.playing.value = Following(PlaybackClock(0, 0), "YouTube Music", canSeek = true)
        queue.playing()
        for (number in 1..50) plays(number, 99L + number)
        plays(49, 148)
        assertEquals(false, playback.canSkipOn(49))
        plays(50, 149)
        assertEquals(true, playback.canSkipOn(50))
    }

    @Test
    fun shuffledThePartEndsOnTheLastOfItsSongsToPlay() {
        val queue = queue()
        val playback = queue.skipping(phone)
        phone.playing.value = Following(PlaybackClock(0, 0), "YouTube Music", canSeek = true)
        queue.playing()
        // YouTube Music keeps its numbers for the songs, and plays them out of order.
        plays(50, 149)
        assertEquals(false, playback.canSkipOn(50))
        for (number in 1..48) plays(number, 99L + number)
        plays(49, 148)
        assertEquals(true, playback.canSkipOn(49))
        playback.skipFrom(49)
        assertEquals(1, phone.listsAsked.size)
        assertTrue(phone.skips.isEmpty())
    }

    @Test
    fun theLastPartOrOneJumpedToTheEndOfSkipsOnAsTheAppWould() {
        val queue = queue()
        val playback = queue.skipping(phone)
        phone.playing.value = Following(PlaybackClock(0, 0), "YouTube Music", canSeek = true)
        // Jumped to its last song, the rest of the part wasn't played, and may still be.
        queue.playing()
        plays(1, 100)
        plays(50, 149)
        assertEquals(false, playback.canSkipOn(50))
        playback.skipFrom(50)
        assertEquals(listOf(true), phone.skips)
        // The list's last part has nothing after it.
        val sixty = tracks.take(60)
        runBlocking { queue.play(sixty, MusicService.YOUTUBE_MUSIC, music, 50, sixty.take(50)) }
        for (number in 1..50) plays(number, 199L + number)
        plays("Radio", 250)
        assertEquals(1, phone.listsAsked.size)
        for (number in 51..60) plays(number, 249L + number)
        assertEquals(false, playback.canSkipOn(60))
        playback.skipFrom(60)
        assertEquals(listOf(true, true), phone.skips)
        // Stopped, it's the app's alone.
        queue.stop()
        assertEquals(false, playback.canSkipOn(60))
    }

    @Test
    fun aSongTheListHoldsTwiceEndsOnlyThePartItEnds() {
        // The song ending the first part comes again in the middle of the next.
        val twice = tracks.take(100).mapIndexed { index, song -> if (index == 74) tracks[49] else song }
        val queue = queue()
        val playback = queue.skipping(phone)
        phone.playing.value = Following(PlaybackClock(0, 0), "YouTube Music", canSeek = true)
        runBlocking { queue.play(twice, MusicService.YOUTUBE_MUSIC, music, 50, twice.take(50)) }
        for (number in 1..50) plays(number, 99L + number)
        assertEquals(true, playback.canSkipOn(50))
        // YouTube Music plays it to its end, and the next part plays.
        plays(50, 149, ended = true)
        assertEquals(1, phone.listsAsked.size)
        for (number in 51..74) plays(number, 149L + number)
        plays(50, 224)
        assertEquals(false, playback.canSkipOn(50))
    }

    @Test
    fun skippedOnWhileTheAppMovesOnTooTheNextPartPlaysOnce() {
        val lookup = CompletableDeferred<YouTubeQueue?>()
        val queue = PlayQueue(phone, { _, _ -> lookup.await() }, file, scope, Dispatchers.Unconfined)
        val playback = queue.skipping(phone)
        phone.playing.value = Following(PlaybackClock(0, 0), "YouTube Music", canSeek = true)
        queue.playing()
        for (number in 1..50) plays(number, 99L + number)
        playback.skipFrom(50)
        // The next part, still being looked up, as YouTube Music plays on from the last song.
        plays("Radio", 150)
        lookup.complete(YouTubeQueue("https://music.youtube.com/watch?v=next&list=TL", tracks.subList(50, 100)))
        assertEquals(listOf(music to "https://music.youtube.com/watch?v=next&list=TL"), phone.listsAsked)
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
