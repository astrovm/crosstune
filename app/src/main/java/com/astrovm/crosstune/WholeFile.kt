package com.astrovm.crosstune

import java.io.File

/** Writes [text] aside first, then swaps it in, so a write cut short never leaves a broken file. */
internal fun File.writeWhole(text: String) {
    parentFile?.mkdirs()
    val temporary = File("$path.tmp")
    temporary.writeText(text)
    temporary.renameTo(this)
}
