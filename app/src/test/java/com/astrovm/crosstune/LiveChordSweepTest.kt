package com.astrovm.crosstune

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * A live sweep of the chord finder against the real sites, for a list of songs. Skipped unless
 * LIVE_CHORDS=1. Lyrics come the way the app gets them, and the chords are looked up with the lines
 * the app shows. One row per song goes to /tmp/chord-sweep/report.tsv as it goes.
 */
@RunWith(RobolectricTestRunner::class)
class LiveChordSweepTest {
    private data class Song(val title: String, val artist: String, val genre: String, val country: String)

    private val songs = parse("""
        Bad Guy|Billie Eilish|pop|USA
        Shape of You|Ed Sheeran|pop|UK
        Bohemian Rhapsody - Remastered 2011|Queen|rock|UK
        Hotel California (2013 Remaster)|Eagles|rock|USA
        Wonderwall|Oasis|rock|UK
        Smells Like Teen Spirit|Nirvana|rock|USA
        Like a Rolling Stone|Bob Dylan|folk|USA
        Hey Jude|The Beatles|pop|UK
        Imagine|John Lennon|pop|UK
        Dancing Queen|ABBA|pop|Sweden
        Waterloo|ABBA|pop|Sweden
        Euphoria|Loreen|pop|Sweden
        Take On Me|a-ha|synth pop|Norway
        Creep|Radiohead|rock|UK
        Stairway to Heaven|Led Zeppelin|rock|UK
        Sweet Child O' Mine|Guns N' Roses|rock|USA
        Mr. Brightside|The Killers|rock|USA
        Down Under|Men at Work|pop|Australia
        Khe Sanh|Cold Chisel|rock|Australia
        Enter Sandman|Metallica|metal|USA
        Master of Puppets (Remastered)|Metallica|metal|USA
        Paranoid|Black Sabbath|metal|UK
        Holy Diver|Dio|metal|USA
        Killing in the Name|Rage Against the Machine|rap metal|USA
        Basket Case|Green Day|punk|USA
        Anarchy in the U.K.|Sex Pistols|punk|UK
        Blitzkrieg Bop|Ramones|punk|USA
        Lose Yourself|Eminem|hip-hop|USA
        Juicy|The Notorious B.I.G.|hip-hop|USA
        Gangsta's Paradise (feat. L.V.)|Coolio|hip-hop|USA
        Mi Gente (feat. Beyoncé)|J Balvin|reggaeton|Colombia
        Despacito (feat. Daddy Yankee)|Luis Fonsi|reggaeton|Puerto Rico
        Dákiti|Bad Bunny|reggaeton|Puerto Rico
        Tusa|Karol G|reggaeton|Colombia
        Hawái|Maluma|reggaeton|Colombia
        La Bicicleta|Carlos Vives|vallenato|Colombia
        Amor Prohibido|Selena|cumbia|USA
        Corazón Espinado (feat. Maná)|Santana|rock latino|Mexico
        Volver, Volver|Vicente Fernández|ranchera|Mexico
        Bésame Mucho|Consuelo Velázquez|bolero|Mexico
        Macarena|Los del Río|pop|Spain
        Hijo de la Luna|Mecano|pop|Spain
        Eres Tú|Mocedades|pop|Spain
        Bailando (feat. Descemer Bueno & Gente de Zona)|Enrique Iglesias|pop|Spain
        Por una Cabeza|Carlos Gardel|tango|Argentina
        Mi Buenos Aires Querido|Carlos Gardel|tango|Argentina
        De Música Ligera|Soda Stereo|rock|Argentina
        Flaca|Andrés Calamaro|rock|Argentina
        Gracias a la Vida|Violeta Parra|folk|Chile
        Te Recuerdo Amanda|Víctor Jara|folk|Chile
        El Condor Pasa (If I Could)|Simon & Garfunkel|folk|Peru
        Señorita|Shawn Mendes & Camila Cabello|pop|Cuba
        The Girl from Ipanema|Antônio Carlos Jobim|bossa nova|Brazil
        Chega de Saudade|João Gilberto|bossa nova|Brazil
        Águas de Março|Elis Regina|bossa nova|Brazil
        Aquarela do Brasil|Ary Barroso|samba|Brazil
        Trem das Onze|Adoniran Barbosa|samba|Brazil
        Gangnam Style|PSY|K-pop|Korea
        Dynamite|BTS|K-pop|Korea
        Ddu-Du Ddu-Du|BLACKPINK|K-pop|Korea
        Gee|Girls' Generation|K-pop|Korea
        上を向いて歩こう|坂本九|J-pop|Japan
        プラスティック・ラブ|竹内まりや|city pop|Japan
        Plastic Love|Mariya Takeuchi|city pop|Japan
        Lemon|米津玄師|J-pop|Japan
        Pretender|Official髭男dism|J-pop|Japan
        Gurenge|LiSA|anime|Japan
        Unravel|TK from 凛として時雨|anime|Japan
        Cha-La Head-Cha-La|Hironobu Kageyama|anime|Japan
        A Cruel Angel's Thesis|Yoko Takahashi|anime|Japan
        晴天|周杰伦|C-pop|Taiwan
        小幸运|田馥甄|C-pop|Taiwan
        江南|林俊杰|C-pop|China
        Tum Hi Ho|Arijit Singh|Bollywood|India
        Kesariya|Arijit Singh|Bollywood|India
        Kabira|Tochi Raina|Bollywood|India
        Essence (feat. Tems)|Wizkid|Afrobeats|Nigeria
        Last Last|Burna Boy|Afrobeats|Nigeria
        Water No Get Enemy|Fela Kuti|Afrobeat|Nigeria
        No Woman, No Cry|Bob Marley & The Wailers|reggae|Jamaica
        Redemption Song|Bob Marley|reggae|Jamaica
        Pressure Drop|Toots and the Maytals|reggae|Jamaica
        Nour El Ein|Amr Diab|Arabic pop|Egypt
        حبيبي يا نور العين|عمرو دياب|Arabic pop|Egypt
        Enta Omri|Umm Kulthum|Arabic classic|Egypt
        La Vie en rose|Édith Piaf|chanson|France
        Ne me quitte pas|Jacques Brel|chanson|Belgium
        Les Champs-Élysées|Joe Dassin|chanson|France
        Formidable|Stromae|French pop|Belgium
        Volare (Nel blu dipinto di blu)|Domenico Modugno|Italian pop|Italy
        Azzurro|Adriano Celentano|Italian pop|Italy
        Con te partirò|Andrea Bocelli|Italian pop|Italy
        Nessun dorma|Giacomo Puccini|opera|Italy
        99 Luftballons|Nena|German rock|Germany
        Wind of Change|Scorpions|German rock|Germany
        Sonne|Rammstein|German rock|Germany
        Atemlos durch die Nacht|Helene Fischer|German pop|Germany
        Şımarık|Tarkan|Turkish pop|Turkey
        Группа крови|Кино|Russian rock|Russia
        Звезда по имени Солнце|Кино|Russian rock|Russia
        Levels|Avicii|electronic|Sweden
        Lean On (feat. MØ & DJ Snake)|Major Lazer|electronic|Denmark
        Jolene|Dolly Parton|country|USA
        Friends in Low Places|Garth Brooks|country|USA
        Ring of Fire|Johnny Cash|country|USA
        Take Me Home, Country Roads|John Denver|country|USA
        Sweet Home Chicago|Robert Johnson|blues|USA
        Hoochie Coochie Man|Muddy Waters|blues|USA
        The Thrill Is Gone|B.B. King|blues|USA
        Fly Me to the Moon|Frank Sinatra|jazz|USA
        Autumn Leaves|Nat King Cole|jazz|USA
        Ave Maria|Franz Schubert|classical crossover|Germany
        Blowin' in the Wind|Bob Dylan|folk|USA
        Titanium (feat. Sia)|David Guetta|electronic|France
        Take Five|Dave Brubeck|instrumental (control)|USA
        Clair de Lune|Claude Debussy|instrumental (control)|France
        Canon in D|Johann Pachelbel|instrumental (control)|Germany
        Libertango|Astor Piazzolla|instrumental (control)|Argentina
        Tank!|The Seatbelts|instrumental (control)|Japan
        Sandstorm|Darude|instrumental (control)|Finland
        Strobe|deadmau5|instrumental (control)|Canada
    """)

    /** Argentine rock. Titles are from memory; a Skipped or None row may be a wrong title, not the finder. */
    private val argentina = parse("""
        Persiana Americana - Remasterizado|Soda Stereo|rock|Argentina
        Cuando Pase el Temblor|Soda Stereo|rock|Argentina
        Nada Personal|Soda Stereo|rock|Argentina
        Ella Usó Mi Cabeza Como un Revólver|Soda Stereo|rock|Argentina
        Signos|Soda Stereo|rock|Argentina
        Un Millón de Años Luz|Soda Stereo|rock|Argentina
        Los Dinosaurios|Charly García|rock|Argentina
        Rasguña las Piedras|Charly García|rock|Argentina
        Inconsciente Colectivo|Charly García|rock|Argentina
        Cerca de la Revolución|Charly García|rock|Argentina
        Demoliendo Hoteles|Charly García|rock|Argentina
        Muchacha (Ojos de Papel)|Luis Alberto Spinetta|rock|Argentina
        Seminare|Serú Girán|rock|Argentina
        Ana No Duerme|Luis Alberto Spinetta|rock|Argentina
        Plegaria para un Niño Dormido|Almendra|rock|Argentina
        Mariposa Tecnicolor|Fito Páez|rock|Argentina
        El Amor Después del Amor|Fito Páez|rock|Argentina
        Circo Beat|Fito Páez|rock|Argentina
        Ciudad de Pobres Corazones|Fito Páez|rock|Argentina
        Ji Ji Ji - Remasterizado|Patricio Rey y sus Redonditos de Ricota|rock|Argentina
        El Árbol del Gran Bonete|Patricio Rey y sus Redonditos de Ricota|rock|Argentina
        Amor Amarillo|Gustavo Cerati|rock|Argentina
        Spaghetti del Rock|Divididos|rock|Argentina
        Divididos por la Felicidad|Sumo|rock|Argentina
        Los Viejos Vinagres|Sumo|rock|Argentina
        Matador|Los Fabulosos Cadillacs|ska rock|Argentina
        Mal Bicho|Los Fabulosos Cadillacs|ska rock|Argentina
        Vasos Vacíos|Los Fabulosos Cadillacs|ska rock|Argentina
        Estadio Azteca|Andrés Calamaro|rock|Argentina
        Loco|Andrés Calamaro|rock|Argentina
        Lamento Boliviano|Los Enanitos Verdes|rock|Argentina
        Mi Enfermedad|Los Rodriguez|rock|Argentina
        Tus Viejas Cartas|Los Enanitos Verdes|rock|Argentina
        Falsas Esperanzas|Attaque 77|rock|Argentina
        Ángel|Attaque 77|rock|Argentina
        Ropa Sucia|Patricio Rey y sus Redonditos de Ricota|rock|Argentina
        Alejado de la Red|La Renga|rock|Argentina
        Día Feliz|Las Pelotas|rock|Argentina
        Río Gris|Las Pelotas|rock|Argentina
        Lago en el Cielo|Gustavo Cerati|rock|Argentina
        Puente|Gustavo Cerati|rock|Argentina
        Adiós|Gustavo Cerati|rock|Argentina
        Crimen|Gustavo Cerati|rock|Argentina
        Mil Horas|Los Abuelos de la Nada|rock|Argentina
        Bajando la Calle|Vicentico|rock|Argentina
        Fuera del Mundo|Vicentico|rock|Argentina
        Sol Naranja|Babasónicos|rock|Argentina
        Deléctrico|Babasónicos|rock|Argentina
        Quemado|Los Piojos|rock|Argentina
        Fijate|Los Piojos|rock|Argentina
        Yo Tomo|Bersuit Vergarabat|rock|Argentina
        Pappo's Blues|Pappo|blues rock|Argentina
        La Sirenita y el Lobo de Mar|Memphis La Blusera|blues rock|Argentina
        Canción para mi Muerte|Sui Generis|rock|Argentina
        A Los Jóvenes de Ayer|Serú Girán|rock|Argentina
        Viernes 3 AM|Serú Girán|rock|Argentina
        Borrachos en la Esquina|Flema|punk|Argentina
        Semen de Buey|Flema|punk|Argentina
        El Blanco Cristal|Flema|punk|Argentina
        Represión|Los Violadores|punk|Argentina
        Comunicado 166|Los Violadores|punk|Argentina
        Viva la Libertad|Bulldog|punk|Argentina
        Reencarnación|Bulldog|punk|Argentina
        Sucia Estrella|Los Ratones Paranoicos|punk|Argentina
        Isabel|Los Ratones Paranoicos|punk|Argentina
        Rock del Gato|Los Ratones Paranoicos|punk|Argentina
        Plan B|Massacre|punk|Argentina
        Mariposa|Massacre|punk|Argentina
        Gatillo Fácil|2 Minutos|punk|Argentina
        Qué Yeta|2 Minutos|punk|Argentina
        Recuerdos en la Arena|2 Minutos|punk|Argentina
        Valentín Alsina|2 Minutos|punk|Argentina
        Aeropuerto|2 Minutos|punk|Argentina
        Loco (Tu Forma de Ser)|Los Auténticos Decadentes|punk|Argentina
        Divina Decadencia|Los Auténticos Decadentes|punk|Argentina
        Entregá el Marrón|Los Auténticos Decadentes|punk|Argentina
        Muy, Muy Temprano|Los Fabulosos Cadillacs|punk|Argentina
        Mi Novia se Cayó en un Pozo Ciego|Los Fabulosos Cadillacs|punk|Argentina
        Corriendo el Riesgo|Mal Momento|punk|Argentina
        El Juego del Poder|Mal Momento|punk|Argentina
        Qué Saben las Flores|Mal Momento|punk|Argentina
    """)

    private fun parse(block: String): List<Song> = block.trimIndent().lines().filter { it.isNotBlank() }.map { line ->
        line.split("|").map { it.trim() }.let { Song(it[0], it[1], it[2], it[3]) }
    }

    private fun String.cell() = replace(Regex("[\t\r\n]"), " ")

    /** Quality columns, filled for a Found song in CHORD_SWEEP_SET=all only; other rows leave them empty. */
    private val qualityColumns = listOf("dump", "lines_with_chords", "lines_total", "distinct_chords", "first_eq_last", "lyrics_seconds", "known_seconds", "flags")

    private data class Quality(
        val dump: String,
        val withChords: Int,
        val total: Int,
        val distinct: List<String>,
        val firstEqLast: Boolean?,
        val lyricsSeconds: String,
        val flags: List<String>
    ) {
        /** known_seconds stays empty: the sweep gives the finder title and artist only, with no length. */
        fun cells() = listOf(dump, withChords.toString(), total.toString(), distinct.size.toString(), firstEqLast?.toString() ?: "", lyricsSeconds, "", flags.joinToString(";"))
    }

    /** A chord name as a sheet writes it, e.g. C, F#m7b5, Dsus4, E7(#9), Am7/G. Loose on purpose. */
    private val validChord = Regex("""^[A-G](#|b|♯|♭)?(m|M|maj|min|dim|aug|sus|add|°|º|ø|\+|-|\d|\(|\)|#|b|♯|♭|\*)*(/[A-G](#|b|♯|♭)?)?$|^N\.?C\.?$""")

    /** Writes the song's dump, then the quality numbers and flags, from the same placement the app shows. */
    private fun qualityAndDump(n: Int, song: Song, sheet: ChordSheet, texts: List<String>, timed: List<LyricLine>): Quality {
        val placed = ChordSheets.place(sheet, texts)
        // Chords on their own before a line count too, in line order.
        val chordNames = placed?.flatMap { line -> line.before + line.over.map { it.name } } ?: emptyList()
        val withChords = placed?.count { it.before.isNotEmpty() || it.over.isNotEmpty() } ?: 0
        val distinct = chordNames.distinct()
        val firstEqLast = if (chordNames.isEmpty()) null else chordNames.first() == chordNames.last()
        val lyricsSeconds = timed.maxOfOrNull { it.timeMs }?.let { (it / 1000).toString() } ?: ""
        val sheetWordLines = sheet.lines.count { line -> line.text.any { it.isLetterOrDigit() } }
        val flags = buildList {
            if (placed == null) add("no_placement")
            if (withChords < texts.size * 0.4) add("low_coverage")
            if (distinct.size == 1) add("one_chord")
            if (distinct.size > 14) add("many_chords")
            // A sheet can shorten repeats, so only a big gap in lines counts.
            if (sheetWordLines * 2 < texts.size || sheetWordLines > texts.size * 2) add("line_count_mismatch")
            val bad = distinct.filterNot { validChord.matches(it) }
            if (bad.isNotEmpty()) add("invalid_chord:" + bad.joinToString(","))
        }
        val slug = song.title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "song" }
        val name = "$n-$slug.txt"
        val dump = buildString {
            appendLine("title: ${song.title}")
            appendLine("artist: ${song.artist}")
            appendLine("lyrics: LyricsFinder (source and url not exposed by Lyrics.Found), timed: ${if (timed.isEmpty()) "no" else "yes"}")
            appendLine("lyrics_seconds: ${lyricsSeconds.ifEmpty { "n/a" }}")
            appendLine("sheet: ${sheet.site.name} ${sheet.url}")
            appendLine("capo: ${sheet.capo}")
            appendLine("lines_with_chords: $withChords / ${texts.size}")
            appendLine("distinct_chords (${distinct.size}): ${distinct.joinToString(" ")}")
            appendLine("flags: ${flags.ifEmpty { listOf("none") }.joinToString(", ")}")
            appendLine()
            appendLine("-- first 12 lines, as the app shows them --")
            texts.take(12).forEach { appendLine(it) }
            appendLine()
            appendLine("-- same lines with chords --")
            if (placed == null) appendLine("(no placement: the sheet's words do not line up with the lyrics)")
            else append(render(texts.take(12), placed.take(12)))
        }
        File("/tmp/chord-sweep/dumps").apply { mkdirs() }.resolve(name).writeText(dump)
        return Quality(name, withChords, texts.size, distinct, firstEqLast, lyricsSeconds, flags)
    }

    /** Chords over each line, the way the app puts them: chords on their own above, then the line with its chords above the words. */
    private fun render(texts: List<String>, placed: List<LineChords>): String = buildString {
        texts.zip(placed).forEach { (text, line) ->
            if (line.before.isNotEmpty()) appendLine(line.before.joinToString("  "))
            if (line.over.isNotEmpty()) {
                val row = StringBuilder()
                line.over.forEach { chord ->
                    val minStart = if (row.isEmpty()) 0 else row.length + 1
                    while (row.length < maxOf(chord.at, minStart)) row.append(' ')
                    row.append(chord.name)
                }
                appendLine(row.toString())
            }
            appendLine(text)
        }
    }

    @Test
    fun sweep() {
        assumeTrue("LIVE_CHORDS=1 turns the sweep on", System.getenv("LIVE_CHORDS") == "1")
        // CHORD_SWEEP_SET=ar runs only the Argentine list, into its own report.
        // CHORD_SWEEP_SET=all runs both lists once, into quality.tsv and dumps/ (the reports stay as they are).
        val mode = System.getenv("CHORD_SWEEP_SET").orEmpty()
        val everything = mode == "all"
        val list = when (mode) {
            "ar" -> argentina
            "all" -> songs + argentina
            // CHORD_SWEEP_SET=retry: only the titles listed in /tmp/chord-sweep/retry.txt, one per line.
            "retry" -> File("/tmp/chord-sweep/retry.txt").readLines().toSet().let { titles -> (songs + argentina).filter { it.title in titles } }
            else -> songs
        }
        val reportPath = when (mode) {
            "ar" -> "/tmp/chord-sweep/report-ar.tsv"
            "all" -> "/tmp/chord-sweep/quality.tsv"
            "retry" -> "/tmp/chord-sweep/retry.tsv"
            else -> "/tmp/chord-sweep/report.tsv"
        }
        if (everything) File("/tmp/chord-sweep/dumps").apply { deleteRecursively(); mkdirs() }
        val client = httpClient()
        // YouTube Music as the app asks it: the matcher finds the song's video there.
        val matcher = ExactMatcher(client, country = "AR")
        val lyrics = LyricsFinder(
            client,
            versionName = "live-sweep",
            youTubeMusicSong = { song -> runBlocking { matcher.find(MusicService.YOUTUBE_MUSIC, song) }?.substringAfter("watch?v=", "")?.ifEmpty { null } }
        )
        val chords = ChordFinder(client)
        val file = File(reportPath).apply { parentFile.mkdirs() }
        val baseColumns = listOf("title", "artist", "genre", "country", "lyrics", "lyric_lines", "result", "site", "url", "ms", "error")
        file.writeText((baseColumns + if (everything) qualityColumns else emptyList()).joinToString("\t") + "\n")

        list.forEachIndexed { index, song ->
            if (index > 0) Thread.sleep(1_000)
            val started = System.currentTimeMillis()
            val meta = MusicMetadata(song.title, song.artist)
            var lyricsStatus = "?"
            var lineCount = 0
            var result = ""
            var site = ""
            var url = ""
            var error = ""
            var timed = emptyList<LyricLine>()
            var quality: Quality? = null
            try {
                val answer = runBlocking { lyrics.lyricsOf(meta) }
                val texts = when (answer) {
                    is LyricsFinder.Lyrics.Found -> {
                        lyricsStatus = "found"
                        timed = answer.lines
                        answer.lines.map { it.text }.ifEmpty {
                            answer.words.lines().map(String::trim).filter(String::isNotEmpty)
                        }
                    }
                    LyricsFinder.Lyrics.Instrumental -> {
                        lyricsStatus = "instrumental"
                        emptyList()
                    }
                    LyricsFinder.Lyrics.None -> {
                        lyricsStatus = "none"
                        emptyList()
                    }
                    LyricsFinder.Lyrics.Unavailable -> {
                        lyricsStatus = "unavailable"
                        emptyList()
                    }
                }
                lineCount = texts.size
                if (texts.isEmpty()) {
                    // The app only looks up chords for a song with lyrics, so none are asked for here.
                    result = "Skipped"
                } else {
                    when (val found = runBlocking { chords.chordsOf(meta, texts) }) {
                        is ChordFinder.Chords.Found -> {
                            result = "Found"
                            site = found.sheet.site.name
                            url = found.sheet.url
                            if (everything) {
                                // A quality check that fails is noted in its flags, so the rest of the sweep goes on.
                                quality = try {
                                    qualityAndDump(index + 1, song, found.sheet, texts, timed)
                                } catch (e: Exception) {
                                    Quality("", 0, texts.size, emptyList(), null, "", listOf("quality_error: ${e.javaClass.simpleName}: ${e.message}"))
                                }
                            }
                        }
                        ChordFinder.Chords.None -> result = "None"
                        ChordFinder.Chords.Unavailable -> result = "Unavailable"
                    }
                }
            } catch (e: Exception) {
                result = "ERROR"
                error = "${e.javaClass.simpleName}: ${e.message}"
            }
            val ms = System.currentTimeMillis() - started
            val base = listOf(song.title, song.artist, song.genre, song.country, lyricsStatus, lineCount.toString(), result, site, url, ms.toString(), error)
            val extra = if (everything) quality?.cells() ?: List(qualityColumns.size) { "" } else emptyList()
            file.appendText((base + extra).joinToString("\t") { it.cell() } + "\n")
        }
    }
}
