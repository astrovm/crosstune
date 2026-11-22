package com.astrovm.crosstune

/** projectM, when it can't start: visuals for tests that don't look at them. */
object NoMilkdrop : Milkdrop {
    override fun open(width: Int, height: Int) = false
    override fun size(width: Int, height: Int) = Unit
    override fun show(preset: String, smooth: Boolean) = false
    override fun hear(samples: ByteArray, count: Int) = Unit
    override fun draw() = Unit
    override fun darken(shade: Float, ceiling: Float) = Unit
    override fun finish() = Unit
    override fun close() = Unit
}
