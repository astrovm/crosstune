package com.astrovm.crosstune

import org.junit.Test
import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * Writes the readings of each of [TitleFuzz]'s titles in this checkout, the engine they were read on first.
 * `baseline.py` runs it in a checkout of main: on an emulator through [main], where `Regex` is ICU as on a phone, or as
 * a test on the JVM.
 */
class WriteTitlesBaseline {
    @Test
    fun write() = write(File("build/titles-baseline.tsv.gz"), TitleFuzz.COUNT)

    companion object {
        /** On an emulator: the file to write, and how many titles, all by default. */
        @JvmStatic
        fun main(args: Array<String>) = write(File(args[0]), args.getOrNull(1)?.toInt() ?: TitleFuzz.COUNT)

        private fun write(file: File, count: Int) {
            GZIPOutputStream(file.outputStream()).bufferedWriter().use { out ->
                out.write(engine())
                out.write("\n")
                TitleFuzz.titles().take(count).forEach { title -> out.write(TitleFuzz.baselineLine(title)); out.write("\n") }
            }
        }

        /** ICU's version and Android's on a phone, the JVM's elsewhere. */
        private fun engine(): String {
            val icu = runCatching { Class.forName("android.icu.util.VersionInfo").getField("ICU_VERSION").get(null) }.getOrNull()
                ?: return "JVM ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}"
            val build = Class.forName("android.os.Build\$VERSION")
            return "ICU $icu, Android ${build.getField("RELEASE").get(null)}, API ${build.getField("SDK_INT").get(null)}"
        }
    }
}
