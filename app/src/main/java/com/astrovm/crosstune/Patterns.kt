package com.astrovm.crosstune

/**
 * Where a run of spaces starts, or where the last match ended. A pattern that starts with spaces tries from
 * here only: from each space of a long run it would read the rest of the run again, and take time squared.
 * It finds what it found before, as a match from inside a run is also one from where the run starts.
 */
internal const val SPACES_START = """(?:(?<!\s)|\G)"""
