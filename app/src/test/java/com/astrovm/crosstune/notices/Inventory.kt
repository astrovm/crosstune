package com.astrovm.crosstune.notices

import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * What the release APK is built from, as lines like notices.txt's `covers:` lines: "maven group:module",
 * "maven group:module!path" for a licence file inside one, "file path", "submodule path", "source path",
 * "native name" and "download url". Read from the checkout and the release build, never written by hand,
 * so a new library, file, source or download shows up here before anyone thinks of its notice.
 */
internal class Inventory(private val root: File, libraries: File, cxx: File) {

    /** The compiled native files, by their path in the checkout (or BUILD/ for one the build makes), and where to read each. */
    val sources: Map<String, File>

    /** The libraries the NDK adds to the native code: its C++ library and runtime, clang's own headers, and the C library's headers. */
    val natives: Set<String>

    val parts: List<String>

    init {
        val compiled = compiledFiles(cxx)
        val native = sortedSetOf<String>()
        val read = sortedMapOf<String, File>()
        compiled.forEach { file ->
            val path = file.invariantSeparatorsPath
            when {
                "/sysroot/usr/include/c++/v1/" in path -> native += "libc++"
                "/sysroot/usr/include/" in path -> native += "bionic"
                "/toolchains/llvm/prebuilt/" in path -> native += "clang"
                else -> read[logical(file)] = file
            }
        }
        // The NDK links its C++ library statically, when CMake is told so, with its ABI and unwinder; clang
        // links its own runtime, compiler-rt's builtins, into every library it makes.
        val stl = cxx.walkTopDown().filter { it.name == "CMakeCache.txt" }.flatMap { it.readLines() }.filter { it.startsWith("ANDROID_STL:") }.map { it.substringAfter('=') }.toSet()
        if ("c++_static" in stl) native += listOf("libc++", "libc++abi", "libunwind")
        if (compiled.isNotEmpty()) native += "compiler-rt"
        sources = read
        natives = native
        parts = libraries.readLines().filter(String::isNotBlank) +
            submodules(root, "") +
            bundled() +
            sources.keys.map { "source $it" } +
            natives.map { "native $it" } +
            downloads()
    }

    /** The checkout's submodules and theirs, by path. */
    private fun submodules(folder: File, prefix: String): List<String> {
        val file = File(folder, ".gitmodules")
        if (!file.isFile) return emptyList()
        return file.readLines().map(String::trim).filter { it.startsWith("path = ") }.map { prefix + it.removePrefix("path = ") }
            .flatMap { listOf("submodule $it") + submodules(File(root, it), "$it/") }
    }

    /** Every file in app/src/main that the APK holds as it is: all but the code, which is compiled. */
    private fun bundled(): List<String> {
        val main = File(root, "app/src/main")
        return main.walkTopDown().onEnter { it == main || it.name !in setOf("java", "cpp") || it.parentFile != main }
            .filter { it.isFile && it != File(main, "AndroidManifest.xml") }
            .map { "file " + it.relativeTo(root).invariantSeparatorsPath }.sorted().toList()
    }

    /** What the app downloads: the addresses the code hands to fetchChecked, which every download goes through. */
    private fun downloads(): List<String> =
        File(root, "app/src/main/java").walkTopDown().filter { it.isFile && it.name != "Fetched.kt" && "fetchChecked(" in it.readText() }
            .flatMap { Regex("\"(https://[^\"$]+)\"").findAll(it.readText()).map { match -> "download " + match.groupValues[1] } }.sorted().toList()

    /** A compiled file's path in the checkout: a patched copy's is the original's, and the build's own start at BUILD/. */
    private fun logical(file: File): String {
        val relative = file.relativeTo(root).invariantSeparatorsPath
        val build = Regex("^app/\\.cxx/[^/]+/[^/]+/[^/]+/(.*)$").find(relative)?.groupValues?.get(1) ?: return if (relative.startsWith("..")) file.path else relative
        return when {
            build == "patched/whisper.cpp/whisper.cpp" -> "third_party/whisper.cpp/src/whisper.cpp"
            build.startsWith("patched/projectm/") -> "third_party/projectm/" + build.removePrefix("patched/projectm/")
            else -> "BUILD/$build"
        }
    }

    companion object {
        /** A line that says where code came from or what licence it's under. */
        val MARK = Regex(
            "copyright|\\blicen[cs]e|public domain|borrowed|adapted from|ported from|taken from|derived from|based on( *$|:? *https?://| (the |an? )?(\\S+ )?(code|work|implementation|original)\\b)|\\(c\\) ?[0-9]{4}|original author|\\bauthor:",
            RegexOption.IGNORE_CASE
        )

        /**
         * The files the newest release build compiled, each ABI's, from ninja's log of what each object read
         * (`.ninja_deps`, version 4): the sources and every header they include.
         */
        fun compiledFiles(cxx: File): Set<File> {
            val logs = cxx.walkTopDown().filter { it.name == ".ninja_deps" }.toList()
            val newest = logs.maxByOrNull { it.lastModified() }?.parentFile?.parentFile ?: return emptySet()
            return logs.filter { it.parentFile?.parentFile == newest }.flatMap { log -> ninjaDeps(log).map { path -> (if (File(path).isAbsolute) File(path) else File(log.parentFile, path)).normalize() } }.toSet()
        }

        /** The paths in a `.ninja_deps` log that an object file depends on. */
        fun ninjaDeps(log: File): Set<String> {
            val input = DataInputStream(log.inputStream().buffered())
            val header = ByteArray(12).also(input::readFully)
            require(String(header) == "# ninjadeps\n") { "$log isn't a ninja deps log" }
            val version = Integer.reverseBytes(input.readInt())
            require(version == 4) { "$log is ninja deps version $version; this reads 4" }
            val paths = mutableListOf<String>()
            val deps = mutableMapOf<Int, IntArray>()
            input.use {
                while (true) {
                    val head = try { Integer.reverseBytes(input.readInt()) } catch (_: EOFException) { break }
                    val record = ByteBuffer.wrap(ByteArray(head and Int.MAX_VALUE).also(input::readFully)).order(ByteOrder.LITTLE_ENDIAN)
                    if (head < 0) {
                        val out = record.getInt()
                        record.position(12)
                        deps[out] = IntArray(record.remaining() / 4) { record.getInt() }
                    } else {
                        paths += String(record.array(), 0, record.capacity() - 4).trimEnd('\u0000')
                    }
                }
            }
            return deps.filterKeys { paths[it].endsWith(".o") }.values.flatMap { ids -> ids.map(paths::get) }.toSet()
        }

        /** A file's licence and origin lines, a block for each run of them no more than 30 lines apart, each with its next line. */
        fun marks(file: File): List<String> {
            val lines = file.readLines()
            val marked = lines.indices.filter { MARK.containsMatchIn(lines[it]) }
            val blocks = mutableListOf<MutableList<Int>>()
            marked.forEach { if (blocks.isNotEmpty() && it - blocks.last().last() <= 30) blocks.last() += it else blocks += mutableListOf(it) }
            return blocks.map { block -> block.flatMap { listOfNotNull(lines[it], lines.getOrNull(it + 1)) }.distinct().joinToString("\n") }
        }
    }
}
