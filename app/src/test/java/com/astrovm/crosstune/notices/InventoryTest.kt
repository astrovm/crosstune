package com.astrovm.crosstune.notices

import com.astrovm.crosstune.Notice
import com.astrovm.crosstune.Notices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** What notices.txt's `covers:` lines leave out of [parts] and the licence lines in [marks], and the lines that cover nothing. */
internal data class Report(val uncovered: List<String>, val unclaimed: List<String>, val stale: List<String>)

/**
 * Checks [notices] against what the APK is built from: each part needs a `covers:` line (or one of
 * Crosstune's own, [own]), and each licence or origin line in a compiled file needs a `source PATH ~ WORDS`
 * line, naming that file exactly, whose words are in it. So a work inside another one, like HLSLParser
 * inside projectM, needs its own notice, as a line for the outer one names only its files and words.
 */
internal fun check(notices: List<Notice>, parts: List<String>, marks: Map<String, List<String>>, own: List<String>, notBuilt: List<String>): Report {
    val lines = notices.flatMap { it.covers }
    val (claims, plain) = (lines + own + notBuilt).partition { " ~ " in it }
    fun claimed(path: String, block: String) = claims.any { it.substringBefore(" ~ ") == "source $path" && it.substringAfter(" ~ ") in block }
    val uncovered = parts.filter { part -> (plain + claims.map { it.substringBefore(" ~ ") }).none { covers(it, part) } }
    val unclaimed = marks.flatMap { (path, blocks) -> blocks.filterNot { claimed(path, it) }.map { "$path: ${it.lines().first().trim()}" } }
    val stale = plain.filter { line -> parts.none { covers(line, it) } } +
        claims.filter { claim -> marks[claim.substringBefore(" ~ ").removePrefix("source ")].orEmpty().none { claim.substringAfter(" ~ ") in it } }
    return Report(uncovered, unclaimed, stale)
}

/** Whether [line], "kind pattern", covers [part], "kind name": exactly, or for files and sources, * as any part of a name and ** as any path. */
internal fun covers(line: String, part: String): Boolean {
    val (kind, pattern) = line.split(' ', limit = 2)
    val (partKind, name) = part.split(' ', limit = 2)
    if (kind != partKind) return false
    if (kind !in setOf("file", "source")) return pattern == name
    val regex = pattern.split("**").joinToString(".*") { piece -> piece.split('*').joinToString("[^/]*") { Regex.escape(it) } }
    return Regex(regex).matches(name)
}

/**
 * Every work by others in the release APK has a notice in **Settings** → **About** → **Licenses**: each
 * library and each licence file inside one, each file it depends on, each submodule, each compiled native
 * file and header with each licence line in it, the NDK's libraries, each bundled file and each download.
 * Run by `:app:notices`, after the release build, whose compile logs it reads.
 */
class InventoryTest {

    private val root = File("..").canonicalFile
    private val inventory = Inventory(
        root,
        File(checkNotNull(System.getProperty("notices.libraries")) { "Run through Gradle's notices task" }),
        File(checkNotNull(System.getProperty("notices.cxx")) { "Run through Gradle's notices task" })
    )
    private val marks = inventory.sources.mapValues { (_, file) -> Inventory.marks(file) }.filterValues { it.isNotEmpty() }

    /** Crosstune's own files, which need no notice. */
    private val own = listOf(
        "file app/src/main/assets/milkdrop-kinds.txt",
        // Widths measured in a browser, numbers rather than the fonts themselves.
        "file app/src/main/resources/com/astrovm/crosstune/jtotal-widths.txt",
        "file app/src/main/resources/com/astrovm/crosstune/jtotal-widths-mono.txt",
        "file app/src/main/resources/com/astrovm/crosstune/notices/*",
        "file app/src/main/res/values*/*",
        "file app/src/main/res/xml/*",
        "file app/src/main/res/layout/*",
        "file app/src/main/res/mipmap-anydpi/*",
        "file app/src/main/res/resources.properties",
        "file app/src/main/generated/baselineProfiles/*",
        "file app/src/main/res/drawable/ic_launcher_background.xml",
        "file app/src/main/res/drawable/ic_launcher_foreground.xml",
        "file app/src/main/res/drawable/ic_launcher_monochrome.xml",
        "file app/src/main/res/drawable/ic_logo_arrow.xml",
        "file app/src/main/res/drawable/ic_logo_note.xml",
        "file app/src/main/res/drawable/ic_chords.xml",
        "file app/src/main/res/drawable/ic_service_qobuz.xml",
        "file app/src/main/res/drawable/widget_background.xml",
        "file app/src/main/res/drawable/widget_preview_row.xml",
        "source app/src/main/cpp/*"
    )

    /** Licence lines in code that no ABI here compiles. */
    private val notBuilt = listOf(
        // OpenVINO's exp_ps_sve, inside #if defined(__ARM_FEATURE_SVE): Android's arm64 targets build without SVE.
        "source third_party/whisper.cpp/ggml/src/ggml-cpu/vec.h ~ openvinotoolkit"
    )

    private fun report(notices: List<Notice> = Notices.all, parts: List<String> = inventory.parts) = check(notices, parts, marks, own, notBuilt)

    private fun without(name: String): List<Notice> {
        assertTrue("No notice named $name", Notices.all.any { it.name == name })
        return Notices.all.filter { it.name != name }
    }

    @Test
    fun theInventoryReadsTheReleaseBuild() {
        listOf(
            "maven androidx.core:core",
            "maven com.atilika.kuromoji:kuromoji-ipadic!META-INF/NOTICE.md",
            "submodule third_party/projectm",
            "submodule third_party/projectm/vendor/projectm-eval",
            "submodule third_party/whisper.cpp",
            "source third_party/projectm/src/libprojectM/ProjectM.cpp",
            "source third_party/projectm/vendor/hlslparser/src/GLSLGenerator.cpp",
            "source third_party/whisper.cpp/src/whisper.cpp",
            "source app/src/main/cpp/projectm/Framebuffer.cpp",
            "native clang",
            "native libc++",
            "native libc++abi",
            "native libunwind",
            "native compiler-rt",
            "native bionic",
            "file app/src/main/res/drawable/ic_add.xml",
            "file app/src/main/resources/com/astrovm/crosstune/jmdict-readings.txt",
            "download https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin",
            "download https://repo1.maven.org/maven2/com/atilika/kuromoji/kuromoji-unidic/0.9.0/kuromoji-unidic-0.9.0.jar"
        ).forEach { assertTrue(it, it in inventory.parts) }
        assertTrue("The native code isn't read", inventory.sources.size > 300)
        assertTrue(marks.getValue("third_party/projectm/vendor/hlslparser/src/HLSLParser.cpp").single().contains("Unknown Worlds Entertainment"))
    }

    @Test
    fun everythingInTheReleaseApkHasANotice() {
        val report = report()
        assertEquals("Parts of the APK with no notice in notices/notices.txt", emptyList<String>(), report.uncovered)
        assertEquals("Licence lines in compiled code with no notice naming their file and words", emptyList<String>(), report.unclaimed)
    }

    @Test
    fun everyCoversLineCoversSomething() {
        assertEquals("Lines that cover nothing in the APK", emptyList<String>(), report().stale)
    }

    @Test
    fun onlyFilesAndSourcesTakeWildcards() {
        val wide = Notices.all.flatMap { it.covers }.filter { line -> '*' in line && line.substringBefore(' ') !in setOf("file", "source") }
        assertEquals(emptyList<String>(), wide)
        // A wildcard over a library's group once let IPADIC's and protobuf's notices go unseen.
        assertTrue(!covers("maven androidx.*:*", "maven androidx.core:core"))
    }

    @Test
    fun everyNoticeIsNeeded() {
        Notices.all.forEach { notice ->
            val report = report(Notices.all - notice)
            assertTrue("Removing ${notice.name} goes unseen", report.uncovered.isNotEmpty() || report.unclaimed.isNotEmpty())
        }
    }

    @Test
    fun withoutHlslParsersNoticeItsFilesAreUnclaimed() {
        val unclaimed = report(without("HLSLParser")).unclaimed
        assertTrue(unclaimed.toString(), unclaimed.any { it.startsWith("third_party/projectm/vendor/hlslparser/src/HLSLParser.cpp: ") })
    }

    @Test
    fun withoutWhispersNoticeTheModelDownloadIsUncovered() {
        assertTrue("download https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin" in report(without("Whisper")).uncovered)
    }

    @Test
    fun withoutIpadicsNoticeItsNoticeFileIsUncovered() {
        assertEquals(listOf("maven com.atilika.kuromoji:kuromoji-ipadic!META-INF/NOTICE.md"), report(without("IPADIC")).uncovered)
    }

    @Test
    fun withoutProtobufsNoticeGlancesCopyIsUncovered() {
        assertTrue(report(without("Protocol Buffers")).uncovered.contains("maven androidx.glance:glance-appwidget-external-protobuf"))
    }

    @Test
    fun aNewFontSoundIconNativeLibraryOrFileDependencyIsUncovered() {
        val added = listOf(
            "file app/src/main/res/font/added.ttf",
            "file app/src/main/res/raw/added.ogg",
            "file app/src/main/res/drawable/ic_added.xml",
            "file app/src/main/jniLibs/arm64-v8a/libadded.so",
            "file app/libs/added.jar",
            "maven com.example:added",
            "source third_party/added/added.c",
            "download https://example.com/added.bin"
        )
        assertEquals(added, report(parts = inventory.parts + added).uncovered)
    }

    @Test
    fun anOriginLineAddedToAFileOfAnotherWorkIsUnclaimed() {
        val added = marks + ("third_party/projectm/src/libprojectM/ProjectM.cpp" to listOf("// Adapted from Example, Copyright (c) 2024 Someone"))
        assertEquals(listOf("third_party/projectm/src/libprojectM/ProjectM.cpp: // Adapted from Example, Copyright (c) 2024 Someone"), check(Notices.all, inventory.parts, added, own, notBuilt).unclaimed)
    }
}
