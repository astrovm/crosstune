package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Test

class HeldWordsTest {

    @Test
    fun onlyAListenThatHeardSoundAndNoVoiceHidesHeldWords() {
        // Review of PR #919: any other end shows them, as a mark or a title can be wrong.
        for (listen in Listen.entries) assertEquals(listen.name, listen == Listen.NO_VOICE, hidesHeldWords(listen))
    }
}
