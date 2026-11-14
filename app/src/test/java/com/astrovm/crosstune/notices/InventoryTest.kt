package com.astrovm.crosstune.notices

import com.astrovm.crosstune.AppLanguage
import com.astrovm.crosstune.Notice
import com.astrovm.crosstune.Notices
import com.astrovm.crosstune.valuesFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Each work's `covers:` lines in notices.txt, by its name. Only these tests read them; the app doesn't. */
internal val coverLines: Map<String, List<String>> by lazy {
    Notices.blocks(checkNotNull(Notices.open("notices.txt"))).associate { lines ->
        lines.first() to lines.filter { it.startsWith("covers: ") }.map { it.removePrefix("covers: ") }
    }
}

/** What notices.txt's `covers:` lines leave out of [parts] and the licence lines in [marks], and the lines that cover nothing. */
internal data class Report(val uncovered: List<String>, val unclaimed: List<String>, val stale: List<String>)

/**
 * Checks [notices] against what the APK is built from: each part needs a `covers:` line (or one of
 * Crosstune's own, [own]), and each licence or origin line in a compiled file needs a `source PATH ~ WORDS`
 * line, naming that file exactly, whose words are in it. So a work inside another one, like HLSLParser
 * inside projectM, needs its own notice, as a line for the outer one names only its files and words.
 */
internal fun check(notices: List<Notice>, parts: List<String>, marks: Map<String, List<String>>, own: List<String>, notBuilt: List<String>): Report {
    val lines = notices.flatMap { notice -> coverLines[notice.name].orEmpty() + notice.texts.map { "file $TEXTS$it" } }.distinct()
    val (claims, plain) = (lines + own + notBuilt).partition { " ~ " in it }
    fun claimed(path: String, block: String) = claims.any { it.substringBefore(" ~ ") == "source $path" && it.substringAfter(" ~ ") in block }
    val uncovered = parts.filter { part -> (plain + claims.map { it.substringBefore(" ~ ") }).none { covers(it, part) } }
    val unclaimed = marks.flatMap { (path, blocks) -> blocks.filterNot { claimed(path, it) }.map { "$path: ${it.lines().first().trim()}" } }
    val stale = plain.filter { line -> parts.none { covers(line, it) } } +
        claims.filter { claim -> marks[claim.substringBefore(" ~ ").removePrefix("source ")].orEmpty().none { claim.substringAfter(" ~ ") in it } }
    return Report(uncovered, unclaimed, stale)
}

/** The folder of the licence texts, each one covered by the notices that name it. */
internal const val TEXTS = "app/src/main/resources/com/astrovm/crosstune/notices/"

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

    /**
     * Crosstune's own files, which need no notice. Each is named exactly, so a new file next to them,
     * from someone else, needs a notice. Each language's strings.xml and its words for the notices,
     * notices-TAG.txt, are named by the app's languages, so one that isn't an app language needs a notice.
     * The licence texts are their notices' own, by their text lines.
     */
    private val own = listOf(
        "file app/src/main/assets/milkdrop-kinds.txt",
        // Widths measured in a browser, numbers rather than the fonts themselves.
        "file app/src/main/resources/com/astrovm/crosstune/jtotal-widths.txt",
        "file app/src/main/resources/com/astrovm/crosstune/jtotal-widths-mono.txt",
        "file app/src/main/resources/com/astrovm/crosstune/notices/notices.txt",
        "file app/src/main/res/values/themes.xml",
        "file app/src/main/res/values-night/themes.xml",
        "file app/src/main/res/values-night-v31/themes.xml",
        "file app/src/main/res/values-v31/themes.xml",
        "file app/src/main/res/xml/backup_rules.xml",
        "file app/src/main/res/xml/data_extraction_rules.xml",
        "file app/src/main/res/xml/shortcuts.xml",
        "file app/src/main/res/xml/widget_crosstune.xml",
        "file app/src/main/res/xml/widget_lyrics.xml",
        "file app/src/main/res/xml/widget_recognize.xml",
        "file app/src/main/res/layout/widget_preview.xml",
        "file app/src/main/res/mipmap-anydpi/ic_launcher.xml",
        "file app/src/main/res/mipmap-anydpi/ic_launcher_round.xml",
        "file app/src/main/res/resources.properties",
        "file app/src/main/generated/baselineProfiles/baseline-prof.txt",
        "file app/src/main/generated/baselineProfiles/startup-prof.txt",
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
    ) + AppLanguage.tags.map { "file app/src/main/res/${valuesFolder(it)}/strings.xml" } +
        AppLanguage.tags.drop(1).map { "file ${TEXTS}notices-$it.txt" }

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
        val wide = coverLines.values.flatten().filter { line -> '*' in line && line.substringBefore(' ') !in setOf("file", "source") }
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
        // Its licence text too, which no other notice names.
        assertEquals(listOf("maven com.atilika.kuromoji:kuromoji-ipadic!META-INF/NOTICE.md", "file ${TEXTS}ipadic.txt"), report(without("IPADIC")).uncovered)
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
    fun aStringsFileInAFolderOfNoAppLanguageIsUncovered() {
        // Strings for a newer Android, for the dark theme, or in a language the app doesn't have: from
        // someone else until the app has that language.
        val added = listOf("values-v34", "values-night", "values-b+sr+Latn").map { "file app/src/main/res/$it/strings.xml" }
        assertEquals(added, report(parts = inventory.parts + added).uncovered)
    }

    @Test
    fun aNewFileInAnyFolderOfTheApkIsCoveredByNoneOfCrosstunesOwnLines() {
        // Each folder that app/src/main packs as it is, and new ones a change could add. A file of
        // Crosstune's own is named exactly, so a new file from someone else, like a strings file with
        // their words, needs a notice wherever it goes.
        val main = File(root, "app/src/main")
        val folders = main.walkTopDown().onEnter { it == main || it.name !in setOf("java", "cpp") || it.parentFile != main }
            .filter { it.isDirectory && it != main }.map { it.relativeTo(root).invariantSeparatorsPath }.toList() +
            listOf("app/src/main/res/values-eo", "app/src/main/res/raw", "app/src/main/res/font", "app/src/main/res/drawable-night")
        assertTrue(folders.toString(), "app/src/main/res/values" in folders && "app/src/main/res/xml" in folders)
        val added = folders.flatMap { listOf("file $it/added.xml", "file $it/added.txt") }
        assertEquals("New files Crosstune's own lines take for its own", emptyList<String>(), added.filter { part -> own.any { covers(it, part) } })
        // Only the folders a notice takes whole, like the MilkDrop presets', give a new file an owner.
        val owned = added - report(parts = inventory.parts + added).uncovered.toSet()
        assertEquals(emptyList<String>(), owned.filterNot { it.startsWith("file app/src/main/assets/milkdrop") })
    }

    @Test
    fun anOriginLineAddedToAFileOfAnotherWorkIsUnclaimed() {
        val added = marks + ("third_party/projectm/src/libprojectM/ProjectM.cpp" to listOf("// Adapted from Example, Copyright (c) 2024 Someone"))
        assertEquals(listOf("third_party/projectm/src/libprojectM/ProjectM.cpp: // Adapted from Example, Copyright (c) 2024 Someone"), check(Notices.all, inventory.parts, added, own, notBuilt).unclaimed)
    }
}
