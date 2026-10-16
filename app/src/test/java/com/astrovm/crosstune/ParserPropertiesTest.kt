package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * What the parsers and name matching always do, whatever they're given: checked on many made-up
 * inputs, from a fixed seed so a failure always comes back the same, and on broken ones that
 * must never crash them.
 */
class ParserPropertiesTest {

    private fun Random.text(alphabet: String, from: Int, until: Int) = String(CharArray(nextInt(from, until)) { alphabet[nextInt(alphabet.length)] })

    /** [text] with a few letters lost, swapped for odd ones, or added. */
    private fun Random.broken(text: String): String {
        val chars = StringBuilder(text)
        repeat(nextInt(1, 6)) {
            val at = nextInt(chars.length + 1)
            when (nextInt(3)) {
                0 -> if (at < chars.length) chars.deleteCharAt(at)
                1 -> if (at < chars.length) chars.setCharAt(at, ODD[nextInt(ODD.length)])
                else -> chars.insert(at, ODD[nextInt(ODD.length)])
            }
        }
        return chars.toString()
    }

    private fun link(text: String) = (MusicLinks.parse(text) as? LinkInput.Link)?.link

    @Test
    fun anyItemsLinkIsReadAsThatItemHoweverItsShared() {
        val random = Random(301)
        val types = listOf("track" to ItemType.TRACK, "album" to ItemType.ALBUM, "artist" to ItemType.ARTIST, "playlist" to ItemType.PLAYLIST)
        repeat(300) {
            val (path, type) = types.random(random)
            val spotify = random.text(ALNUM, 22, 23)
            val video = random.text("$ALNUM-_", 11, 12)
            val deezer = random.nextLong(1, 10_000_000_000).toString()
            val shared = listOf(
                Triple("https://open.spotify.com/$path/$spotify?si=${random.text(ALNUM, 1, 20)}", MusicService.SPOTIFY, spotify),
                Triple("open.spotify.com/intl-es/$path/$spotify", MusicService.SPOTIFY, spotify),
                Triple("spotify:$path:$spotify", MusicService.SPOTIFY, spotify),
                Triple("https://www.deezer.com/${listOf("", "en/", "fr/").random(random)}$path/$deezer", MusicService.DEEZER, deezer),
                Triple("https://www.youtube.com/watch?v=$video&t=${random.nextInt(600)}", MusicService.YOUTUBE, video),
                Triple("https://youtu.be/$video?si=${random.text(ALNUM, 1, 20)}", MusicService.YOUTUBE, video),
                Triple("https://music.youtube.com/watch?v=$video", MusicService.YOUTUBE_MUSIC, video)
            )
            for ((url, service, id) in shared) {
                // On its own, or in a post that says something around it, as apps share it.
                // Copied without its scheme, it's only taken for a link on its own.
                val around = if (!url.contains("/")) listOf(url) else if (!url.startsWith("https://")) listOf(url, " $url\n")
                    else listOf(url, "${random.text(WORDS, 0, 40)} $url ${random.text(WORDS, 0, 40)}", "「$url」", "<$url>.", "\"$url\"", "($url)", "$url${listOf(".", ",", "!", "?", "。", "...").random(random)} ${random.text(WORDS, 0, 20)}")
                for (text in around) {
                    val found = link(text)
                    assertEquals(text, service, found?.service)
                    assertEquals(text, id, found?.id)
                    if (service != MusicService.YOUTUBE && service != MusicService.YOUTUBE_MUSIC) assertEquals(text, type, found?.type)
                }
            }
        }
    }

    @Test
    fun brokenOrMadeUpTextIsNeverACrashAndAnythingReadIsALink() {
        val random = Random(302)
        val seeds = listOf(
            "https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC", "spotify:album:4uLU6hMCjMI75M1A2tKUQC", "https://www.deezer.com/en/track/908604612",
            "https://youtu.be/dQw4w9WgXcQ", "https://music.youtube.com/playlist?list=OLAK5uy_abcdefghijk", "https://music.apple.com/us/album/x/1440811207?i=1440811208",
            "https://www.shazam.com/song/1109658204/iris", "https://tidal.com/browse/track/12", "https://soundcloud.com/band/song",
            "https://band.bandcamp.com/track/song", "https://audiomack.com/band/song/song", "https://spotify.link/abc",
            "Song by Band\nhttps://www.google.com/search?q=Song+by+Band", "https://www.google.com/search?q=x&stick=1", "https://g.co/kgs/abc"
        )
        repeat(3_000) {
            val text = when (random.nextInt(3)) {
                0 -> random.broken(seeds.random(random))
                1 -> random.text("$ALNUM:/.?=&-_ ", 0, 80)
                else -> seeds.random(random).take(random.nextInt(60))
            }
            // Whatever it reads is something that opens.
            when (val read = MusicLinks.parse(text)) {
                is LinkInput.Link -> assertTrue(text, read.link.url.startsWith("https://"))
                is LinkInput.ShortLink -> assertTrue(text, read.url.startsWith("https://"))
                is LinkInput.RecognizedSong -> assertTrue(text, read.url.startsWith("https://"))
                is LinkInput.ShazamTrack, null -> Unit
            }
            MusicLinks.looksLikeALink(text)
        }
    }

    @Test
    fun aSongNowPlayingSharesIsReadByItsNameInEveryLanguage() {
        val random = Random(303)
        repeat(300) {
            // Names with no word a wording uses, so only one way to split them.
            val title = random.text("ABCKLMRSTZqwxzáñüöçあいう&!?'0123456789", 1, 12).trim().ifEmpty { "Q" }
            val artist = random.text("ABCKLMRSTZqwxzáñüöçあいう&!?'0123456789", 1, 12).trim().ifEmpty { "Z" }
            fun share(wording: Pair<String, String>, title: String, artist: String) = wording.toList()
                .map { it.replace("%1\$s", title).replace("%2\$s", artist) }.let { (shared, searched) -> "$shared\nhttps://www.google.com/search?q=${searched.replace(' ', '+')}" }
            val wording = NowPlayingShares.patterns.random(random)
            val text = share(wording, title, artist)
            val song = (MusicLinks.parse(text) as? LinkInput.RecognizedSong)?.metadata
            if (wording == NowPlayingShares.patterns.first()) {
                assertEquals(text, MusicMetadata(title, artist), song?.let { MusicMetadata(it.title, it.artist) })
            } else {
                // Wordings can overlap, "%1$s de %2$s" and "%1$s de la %2$s": either way, it's a song that one of them shares.
                assertTrue(text, song != null && NowPlayingShares.patterns.any { share(it, song.title, song.artist) == text })
            }
        }
    }

    /** "[mm:ss.xx]", written any of the ways LRC files write it, and the time it says. */
    private fun Random.stamp(): Pair<String, Long> {
        val minutes = nextInt(0, 1000)
        val seconds = nextInt(0, 60)
        val fraction = listOf("", ".", ":").random(this).let { mark -> if (mark.isEmpty()) "" else mark + text("0123456789", 1, 4) }
        val millis = fraction.drop(1).padEnd(3, '0').take(3).ifEmpty { "0" }.toLong()
        val minuteText = minutes.toString().let { if (it.length < 2 && nextBoolean()) "0$it" else it }
        val secondText = seconds.toString().let { if (it.length < 2 && nextBoolean()) "0$it" else it }
        return "[$minuteText:$secondText$fraction]" to (minutes * 60L + seconds) * 1000 + millis
    }

    @Test
    fun timedLyricsKeepEveryLineAtItsTimeInOrderMovedByTheirOffset() {
        val random = Random(304)
        repeat(300) {
            val offset = if (random.nextBoolean()) random.nextLong(-5_000, 5_000) else 0L
            val expected = mutableListOf<LyricLine>()
            val lrc = buildList {
                if (offset != 0L || random.nextBoolean()) add("[offset:${if (offset > 0 && random.nextBoolean()) "+" else ""}$offset]")
                add("[ar: ${random.text(WORDS, 1, 10)}]")
                repeat(random.nextInt(0, 30)) {
                    val text = random.text(WORDS, 0, 30)
                    // Sung once, or more than once, with a stamp for each time.
                    val stamps = List(if (random.nextInt(5) == 0) random.nextInt(2, 4) else 1) { random.stamp() }
                    stamps.forEach { (_, ms) -> expected += LyricLine((ms - offset).coerceAtLeast(0), text.trim()) }
                    add(stamps.joinToString("") { it.first } + text)
                    if (random.nextInt(4) == 0) add(random.text(WORDS, 0, 20).replace("[", ""))
                }
            }.joinToString(if (random.nextBoolean()) "\n" else "\r\n")
            val lines = SyncedLyrics.parse(lrc)
            assertEquals(lrc, expected.sortedBy { it.timeMs }, lines.map { it.copy(text = it.text.trim(), words = emptyList()) })
        }
    }

    @Test
    fun brokenTimedLyricsAreNeverACrashAndComeInOrder() {
        val random = Random(305)
        val seed = "[offset:+250]\n[ar: Band]\n[00:01.50]<00:01.50> Night <00:02.10>sky\n[00:03.00][01:10.5]Stars\n[12:34]\n[00:05,00] (0,1,0)x"
        repeat(3_000) {
            val lrc = if (random.nextBoolean()) random.broken(seed) else random.text("[]<>():.,+-0123456789 abc\n", 0, 120)
            for (lines in listOf(SyncedLyrics.parse(lrc), SyncedLyrics.parseWordByWord(lrc))) {
                assertTrue(lrc, lines.zipWithNext().all { (one, next) -> one.timeMs <= next.timeMs })
                assertTrue(lrc, lines.all { line -> line.timeMs >= 0 && line.words.all { it.timeMs >= 0 } })
            }
        }
    }

    @Test
    fun aNameIsTheSameWhateverItsCaseAccentsAndPunctuation() {
        val random = Random(306)
        repeat(1_000) {
            val name = random.text("$ALNUM áéíóúñüçøあいう漢字.,!?'&-()[]", 0, 30)
            val normal = SongNames.normalize(name)
            assertEquals(name, normal, SongNames.normalize(normal))
            assertEquals(name, normal, SongNames.normalize(name.uppercase(java.util.Locale.ROOT)))
            assertEquals(name, normal, SongNames.normalize(java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)))
            assertEquals(name, normal, SongNames.normalize(name.map { if (random.nextInt(5) == 0) "$it." else "$it" }.joinToString("")))
            assertTrue(name, SongNames.same(name, name))
            assertTrue(name, SongNames.same(name, name.uppercase(java.util.Locale.ROOT)))
            val other = random.text("$ALNUM áéíóúñü.,!?'&-()[]", 0, 30)
            assertEquals("$name / $other", SongNames.same(name, other), SongNames.same(other, name))
            // An artist with no name to tell by is anyone, so only two names count both ways.
            if (SongNames.artists(name).isNotEmpty() && SongNames.artists(other).isNotEmpty()) {
                assertEquals("$name / $other", SongNames.sameArtist(name, other), SongNames.sameArtist(other, name))
            }
        }
    }

    @Test
    fun wordsLineUpOnlyWithTheSameLettersInOrder() {
        val random = Random(307)
        repeat(500) {
            val a = random.text("abcde", 0, 60)
            assertEquals(a, a.indices.toList(), BorrowedTimings.lineUp(a, a)!!.toList())
            val b = if (random.nextBoolean()) random.broken(a).lowercase().filter { it in "abcde" } else random.text("abcde", 0, 60)
            val matched = BorrowedTimings.lineUp(a, b)!!
            assertEquals(b.length, matched.size)
            val pairs = matched.withIndex().filter { it.value >= 0 }
            assertTrue("$a / $b", pairs.all { (j, i) -> a[i] == b[j] })
            assertTrue("$a / $b", pairs.zipWithNext().all { (one, next) -> one.value < next.value })
        }
    }

    @Test
    fun timesBorrowedFromAnotherCopyGoOnInOrderAndMatchItWhenItsTheSame() {
        val random = Random(308)
        repeat(300) {
            val lines = List(random.nextInt(2, 15)) { random.text(WORDS, 1, 30).ifBlank { "la" } }
            val sounds = lines.map(BorrowedTimings::sound).map { it.ifEmpty { "la" } }
            var at = random.nextLong(0, 20_000)
            val timed = lines.map { LyricLine(at, it).also { at += random.nextLong(1, 8_000) } }
            // The same words: the same times.
            assertEquals(timed.map { it.timeMs }, BorrowedTimings.retime(lines, sounds, timed, sounds)?.map { it.timeMs })
            // Written a little otherwise, whatever comes is in order and never before the song starts.
            val otherSounds = sounds.map { sound -> if (random.nextInt(3) == 0) random.broken(sound).lowercase().filter { it.isLetterOrDigit() } else sound }
            // Sometimes with a line out of order, as copies timed by hand have.
            val other = if (random.nextBoolean()) timed else timed.toMutableList().also { copy ->
                val line = random.nextInt(1, copy.size)
                copy[line] = copy[line].copy(timeMs = random.nextLong(0, copy[line - 1].timeMs + 1))
            }
            val retimed = BorrowedTimings.retime(lines, sounds, other, otherSounds) ?: return@repeat
            assertEquals(lines, retimed.map { it.text })
            assertTrue(retimed.all { it.timeMs >= 0 })
            assertTrue(retimed.zipWithNext().all { (one, next) -> one.timeMs <= next.timeMs })
        }
    }

    private companion object {
        const val ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        const val WORDS = "abcdefghijklmnopqrstuvwxyz  éñü,.'!?"
        const val ODD = "\u0000\n\t /?#:@%[]{}<>\"'「」​😀ü漢"
    }
}
