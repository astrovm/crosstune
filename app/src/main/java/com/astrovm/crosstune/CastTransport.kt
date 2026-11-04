package com.astrovm.crosstune

/** Commands the Google connection carries; a fake can record the same calls without a TV. */
internal interface CastTransport {
    fun play()
    fun pause()
    fun stop()
    fun seek(positionMs: Long)
    fun next()
    fun previous()
    fun message(value: String)
}

internal fun sendCastCommand(command: CastCommand, transport: CastTransport) {
    when (command) {
        CastCommand.Play -> transport.play()
        CastCommand.Pause -> transport.pause()
        CastCommand.Stop -> transport.stop()
        is CastCommand.Seek -> transport.seek(command.positionMs)
        CastCommand.Next -> transport.next()
        CastCommand.Previous -> transport.previous()
        is CastCommand.Translation, is CastCommand.Readings ->
            command.message()?.let(transport::message)
    }
}
