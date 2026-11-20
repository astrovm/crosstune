package com.astrovm.crosstune

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusRequesterModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.requestFocus
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.node.currentValueOf

/*
 * A screen that closes takes what the remote was on with it, and Android puts it nowhere: the next
 * press then starts from the top of the screen under it, the link field, rather than where the user
 * was. So each screen keeps what the remote was last on in it, and on the way back the remote goes
 * there again, or to the screen's main button where it was never on anything, as when the cover
 * opens straight on the words.
 */

/** The key of a screen's main button, where the remote goes back to when nothing else in the screen had it. */
internal const val MAIN_FOCUS = "main"

/** What in one screen the remote can go back to, and which of them it was last on. */
internal class ScreenFocus {
    private val here = mutableMapOf<Any, ReturnsFocusNode>()
    private var last: Any? = null

    fun add(key: Any, node: ReturnsFocusNode) {
        here[key] = node
    }

    fun remove(key: Any, node: ReturnsFocusNode) {
        if (here[key] === node) here.remove(key)
    }

    fun focused(key: Any) {
        last = key
    }

    /** [node] now under [key] rather than [old], and still what the remote was last on, if it was. */
    fun rekey(old: Any, key: Any, node: ReturnsFocusNode) {
        remove(old, node)
        add(key, node)
        if (last == old) last = key
    }

    /** The remote back on what it was last on here, or the main button; false when neither takes it. */
    fun restore(): Boolean = listOfNotNull(last, MAIN_FOCUS).mapNotNull { here[it] }.any { it.requestFocus() }
}

/** The [ScreenFocus] of the screen this is in, null outside one. */
internal val LocalScreenFocus = staticCompositionLocalOf<ScreenFocus?> { null }

/** Marks what the remote can come back to in its screen, under [key]: [MAIN_FOCUS] for the screen's main button. */
internal fun Modifier.returnsFocus(key: Any = MAIN_FOCUS): Modifier = this then ReturnsFocusElement(key)

private data class ReturnsFocusElement(val key: Any) : ModifierNodeElement<ReturnsFocusNode>() {
    override fun create() = ReturnsFocusNode(key)

    override fun update(node: ReturnsFocusNode) = node.rekey(key)
}

internal class ReturnsFocusNode(private var key: Any) :
    Modifier.Node(), FocusRequesterModifierNode, FocusEventModifierNode, CompositionLocalConsumerModifierNode, ObserverModifierNode {
    private var screen: ScreenFocus? = null

    override fun onAttach() = onObservedReadsChanged()

    // Watched, so a node that ends up in another screen's keeping moves there.
    override fun onObservedReadsChanged() = observeReads {
        val now = currentValueOf(LocalScreenFocus)
        if (now !== screen) {
            screen?.remove(key, this)
            screen = now?.also { it.add(key, this) }
        }
    }

    override fun onDetach() {
        screen?.remove(key, this)
        screen = null
    }

    fun rekey(key: Any) {
        screen?.rekey(this.key, key, this)
        this.key = key
    }

    override fun onFocusEvent(focusState: FocusState) {
        if (focusState.isFocused) screen?.focused(key)
    }
}
