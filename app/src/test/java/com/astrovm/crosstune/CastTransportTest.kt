package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CastTransportTest {
    @Test
    fun eachCommandUsesExactlyItsOwnTransportAndLearningOptionsUseTheChannel() {
        val calls = mutableListOf<Any>()
        val transport = object : CastTransport {
            override fun play() { calls += "play" }
            override fun pause() { calls += "pause" }
            override fun stop() { calls += "stop" }
            override fun seek(positionMs: Long) { calls += positionMs }
            override fun next() { calls += "next" }
            override fun previous() { calls += "previous" }
            override fun message(value: String) { calls += requireNotNull(CastCommand.fromMessage(value)) }
        }
        val cases = listOf(
            CastCommand.Play to "play", CastCommand.Pause to "pause", CastCommand.Stop to "stop",
            CastCommand.Next to "next", CastCommand.Previous to "previous", CastCommand.Seek(9_001) to 9_001L,
            CastCommand.Translation(true) to CastCommand.Translation(true),
            CastCommand.Translation(false) to CastCommand.Translation(false),
            CastCommand.Readings(true) to CastCommand.Readings(true),
            CastCommand.Readings(false) to CastCommand.Readings(false)
        )
        cases.forEach { (command, expected) ->
            calls.clear()
            sendCastCommand(command, transport)
            assertEquals(listOf(expected), calls)
        }
    }
}
