package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Runs under Robolectric for the real org.json. */
@RunWith(RobolectricTestRunner::class)
class SheetTest {

    private val song = """
        Año: 1986
        Álbum: True Confessions
        Versión de “Venus”, de Shocking Blue
        Autor original: Robbie van Leeuwen
        ---
        La banda llevaba años tocándola y quiso convertirla en un tema bailable. La voz celebra a Venus como una mujer irresistible que se dirige a quien la desea.
        ---
        Sensual · triunfal · bailable
        ---
        “I'm your Venus, I'm your fire”
        Se presenta como fuente de deseo y pasión.
    """.trimIndent()

    private val artist = """
        Origen: Londres, Inglaterra
        Formación: comienzos de los años 80
        Género: pop
        Integrantes actuales: Sara Dallin y Keren Woodward
        Su estilo combina una actitud independiente con melodías pop y ritmos bailables. Su manera de hacer música inspiró a una generación de grupos femeninos.
        Venus · 1986
        Cruel Summer · 1983
        I Heard a Rumour · 1987
        Deep Sea Skiving · 1983
    """.trimIndent()

    private val venus = Sheet(
        facts = listOf("Año" to "1986", "Álbum" to "True Confessions", "" to "Versión de “Venus”, de Shocking Blue", "Autor original" to "Robbie van Leeuwen"),
        text = "La banda llevaba años tocándola y quiso convertirla en un tema bailable. La voz celebra a Venus como una mujer irresistible que se dirige a quien la desea.",
        mood = listOf("Sensual", "triunfal", "bailable"),
        quote = "“I'm your Venus, I'm your fire”",
        means = "Se presenta como fuente de deseo y pasión.",
        known = emptyList()
    )

    private val bananarama = Sheet(
        facts = listOf("Origen" to "Londres, Inglaterra", "Formación" to "comienzos de los años 80", "Género" to "pop", "Integrantes actuales" to "Sara Dallin y Keren Woodward"),
        text = "Su estilo combina una actitud independiente con melodías pop y ritmos bailables. Su manera de hacer música inspiró a una generación de grupos femeninos.",
        mood = emptyList(),
        quote = null,
        means = null,
        known = listOf("Venus" to "1986", "Cruel Summer" to "1983", "I Heard a Rumour" to "1987", "Deep Sea Skiving" to "1983")
    )

    @Test
    fun aSongSplitsIntoItsFactsStoryMoodAndKeyLine() {
        assertEquals(venus, sheet(song))
    }

    @Test
    fun anArtistSplitsIntoItsFactsStyleAndKnownWork() {
        assertEquals(bananarama, sheet(artist))
        assertEquals(bananarama, sheet(artist.replace("\n", "\n---\n")))
    }

    @Test
    fun theBreaksDontMatterWhetherOrNotTheyAreThere() {
        assertEquals(venus, sheet(song.replace("---\n", "")))
        assertEquals(venus, sheet(song.replace("---", "\n—\n")))
        assertEquals(venus, sheet(song.replace("\n---\n", "\n\n")))
    }

    @Test
    fun anAnswerStillBeingWrittenShowsWhatIsThereSoFar() {
        assertEquals(Sheet(emptyList(), "", emptyList(), null, null, emptyList()), sheet(""))
        assertEquals(listOf("Año" to "1986"), sheet("Año: 1986\nÁl").facts.take(1))
        assertEquals("“I'm your", sheet("Año: 1986\n---\nHistoria larga que termina bien.\n---\nCalm · warm\n---\n“I'm your").quote)
        assertNull(sheet("Año: 1986\n---\nHistoria larga que termina bien.\n---\nCalm · warm\n---\n“I'm your").means)
        assertEquals(listOf("Venus" to "1986"), sheet(artist.substringBefore("Cruel")).known)
    }

    @Test
    fun whatFitsNowhereIsKeptAsText() {
        // After the story, a colon is just a colon, and a second quote is just words.
        val text = "Año: 1986\nEs una canción sobre algo: el amor, la distancia y todo lo demás que pasa.\nNota: dicen que sí.\nTriste · lento\n“Otra cita”\n“Una más”"
        val sheet = sheet(text)
        assertEquals(listOf("Año" to "1986"), sheet.facts)
        assertEquals(listOf("Triste", "lento"), sheet.mood)
        assertEquals("“Otra cita”", sheet.quote)
        assertEquals("“Una más”", sheet.means)
        assertEquals("Es una canción sobre algo: el amor, la distancia y todo lo demás que pasa. Nota: dicen que sí.", sheet.text)
        assertEquals("“Dos” “Tres”", sheet("“Uno”\nSignifica uno.\n“Dos”\n“Tres”").text)
        // A second mood line, or a long label, is part of the story too.
        val twice = sheet("Lo que dijo en una larga entrevista fue: nada.\nTriste · lento\nAlegre · rápido")
        assertEquals("Lo que dijo en una larga entrevista fue: nada. Alegre · rápido", twice.text)
        assertEquals(listOf("Triste", "lento"), twice.mood)
        // Plain prose with no structure at all is shown as it is.
        assertEquals("Una canción. Otra frase.", sheet("Una canción.\nOtra frase.").text)
    }

    @Test
    fun knownWorkLosesItsQuotesAndSourcesAndMarkdownAreCleaned() {
        val sheet = sheet("Origen: Roma\nEstilo breve y claro, sin más.\n«Plastic Love» · 1984\n“Variety” · 1984–85\n**Moonlight** · 1990 ([site](https://x.org))")
        assertEquals(listOf("Plastic Love" to "1984", "Variety" to "1984–85", "Moonlight" to "1990"), sheet.known)
    }

    @Test
    fun aLabelHasUpToFourWords() {
        // Four words before the colon is a label, five is part of the story.
        assertEquals(listOf("Uno dos tres cuatro" to "valor"), sheet("Uno dos tres cuatro: valor").facts)
        assertEquals(listOf("" to "Uno dos tres cuatro cinco: valor"), sheet("Uno dos tres cuatro cinco: valor").facts)
    }

    @Test
    fun aMoodHasUpToFourParts() {
        assertEquals(listOf("Uno", "dos", "tres", "cuatro"), sheet("Uno · dos · tres · cuatro").mood)
        assertEquals(emptyList<String>(), sheet("Uno · dos · tres · cuatro · cinco").mood)
    }

    @Test
    fun aMoodPartHasUpToThreeWords() {
        assertEquals(listOf("Uno dos tres", "otro"), sheet("Uno dos tres · otro").mood)
        assertEquals(emptyList<String>(), sheet("Uno dos tres cuatro · otro").mood)
    }

    @Test
    fun aLineWithNoLabelIsAFactUpToEightyCharacters() {
        val eighty = "a".repeat(80)
        assertEquals(listOf("" to eighty), sheet(eighty).facts)
        val long = "a".repeat(81)
        assertEquals(emptyList<Pair<String, String>>(), sheet(long).facts)
        assertEquals(long, sheet(long).text)
    }
}
