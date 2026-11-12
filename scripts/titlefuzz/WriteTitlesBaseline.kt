package com.astrovm.crosstune

import org.junit.Test
import java.io.File
import java.util.zip.GZIPOutputStream

/** Writes the readings of each of [TitleFuzz]'s titles in this checkout. `baseline.py` runs it in a checkout of main. */
class WriteTitlesBaseline {
    @Test
    fun write() {
        GZIPOutputStream(File("build/titles-baseline.tsv.gz").outputStream()).bufferedWriter().use { out ->
            TitleFuzz.titles().forEach { title -> out.write(TitleFuzz.baselineLine(title)); out.write("\n") }
        }
    }
}
