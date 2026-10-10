package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Test

class ExplanationPartsTest {

    @Test
    fun anExplanationIsSplitIntoItsParts() {
        assertEquals(listOf("What it means.", "A note."), explanationParts("What it means.\n---\nA note."))
        // Spaces or more dashes around the break, and a part left empty.
        assertEquals(listOf("Story.", "", "Refs."), explanationParts("Story.\n  ----  \n\n---\nRefs."))
        assertEquals(listOf("Story.", "Mood.", ""), explanationParts("Story.\n---\nMood.\n---\n"))
        // Without a break, it's all one part; dashes in a sentence aren't one.
        assertEquals(listOf("All of it -- in one."), explanationParts("All of it -- in one."))
    }

    @Test
    fun whileItsWrittenAHalfWrittenBreakIsntShown() {
        assertEquals(listOf("What it means."), explanationParts("What it means.\n-"))
        assertEquals(listOf("What it means."), explanationParts("What it means.\n--"))
        assertEquals(listOf("What it means.", ""), explanationParts("What it means.\n---"))
        assertEquals(listOf(""), explanationParts(""))
    }

    @Test
    fun partsLeftOnlyABlankLineApartAreStillParts() {
        assertEquals(listOf("Story.", "Mood.", "「風が噂をはこんでく」: rumors on the wind."), explanationParts("Story.\n\nMood.\n \n「風が噂をはこんでく」: rumors on the wind."))
        // A line break alone keeps a part whole.
        assertEquals(listOf("Story,\ngoing on."), explanationParts("Story,\ngoing on."))
        // Breaks win over blank lines, which are then part of a part.
        assertEquals(listOf("Story.\n\nMore story.", "Mood."), explanationParts("Story.\n\nMore story.\n---\nMood."))
    }

    @Test
    fun markdownAndSourcesChatGptAddsAnywayAreLeftOut() {
        // As GPT-6-Luna and GPT-6.1-Sol really answered.
        assertEquals(
            "“November Blue” apareció en el álbum FIRST FINALE de 1985.",
            plainText("“November Blue” apareció en el álbum *FIRST FINALE* de 1985.")
        )
        assertEquals(
            "November Blue apareció en First Finale en 1985.",
            plainText("November Blue apareció en First Finale en 1985. ([qobuz.com](https://www.qobuz.com/jp-ja/album/first-finale?utm_source=openai))")
        )
        assertEquals("Released in 1985 on First Finale.", plainText("Released in **1985** on [First Finale](https://example.org/album)."))
        // Asterisks that aren't Markdown stay, and so does Japanese.
        assertEquals("5 * 3 = 15, 「君の瞳」*", plainText("5 * 3 = 15, 「君の瞳」*"))
        // Within parts, too.
        assertEquals(listOf("FIRST FINALE, 1985.", "Story."), explanationParts("*FIRST FINALE*, 1985. ([vap.co.jp](https://vap.co.jp))\n\nStory."))
    }
}
