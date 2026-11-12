package com.astrovm.crosstune

/**
 * What a listen for a voice in a song came to. Words LRCLIB's marks, or the song's title, say no one sings are held
 * while it runs (Issue #861), and only [hidesHeldWords] decides from this whether they stay hidden.
 */
internal enum class Listen {
    /** No listen could start: no microphone, no speech model, Crosstune out of sight, or no app on this phone playing it. */
    NOT_STARTED,

    /** It ran and heard nothing it could tell from: no sound, no model, or the model failing. */
    FAILED,

    /** Nothing came of it in the time a listen takes: it stopped, or was never started again. */
    TIMED_OUT,

    /** It heard someone sing. */
    VOICE,

    /** It ran to the end on this recording, heard sound, and heard no voice. */
    NO_VOICE
}

/**
 * Whether held words stay hidden after [listen]. Only hearing the recording's sound with no voice in it says no one
 * sings: any other end, even one not thought of yet, shows them, as a mark or a title can be wrong.
 */
internal fun hidesHeldWords(listen: Listen): Boolean = listen == Listen.NO_VOICE
