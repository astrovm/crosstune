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
import androidx.compose.ui.platform.InspectorInfo

/*
 * A screen that closes takes what the remote was on with it, and Android puts it nowhere: the next
 * press then starts from the top of the screen under it, the link field, rather than where the user
 * was. So each screen keeps what the remote was on in it as it left, and on the way back the remote
 * goes there again, or to the screen's main button where it was on nothing it can go back to: as
 * when the cover opens straight on the words, or what it was on was a button that opens no screen.
 */

/** The key of a screen's main button, where the remote goes back to when nothing else in the screen had it. */
internal const val MAIN_FOCUS = "main"

/** What in one screen the remote can go back to, and which of them it was on as the screen was left. */
internal class ScreenFocus {
    private val here = mutableMapOf<Any, ReturnsFocusNode>()
    private var last: Any? = null

    fun add(key: Any, node: ReturnsFocusNode) {
        here[key] = node
    }

    fun remove(key: Any, node: ReturnsFocusNode) {
        if (here[key] === node) here.remove(key)
    }

    /** Leaving the screen: what the remote is on now, if it's something to go back to; else nothing, so not what it was on before. */
    fun left() {
        last = here.entries.firstOrNull { it.value.focused }?.key
    }

    /** Back on the screen, the remote goes to [key], whatever it was on as the screen was left. */
    fun goBackTo(key: Any) {
        last = key
    }

    /** [node] now under [key] rather than [old], and still what the remote was on, if it was. */
    fun rekey(old: Any, key: Any, node: ReturnsFocusNode) {
        remove(old, node)
        add(key, node)
        if (last == old) last = key
    }

    /** The remote back on what it was on here, or the main button; false when neither takes it. */
    fun restore(): Boolean = listOfNotNull(last, MAIN_FOCUS).mapNotNull { here[it] }.any { it.requestFocus() }
}

/** Each screen's [ScreenFocus], told as the app turns from one screen to another. */
internal class ScreenFocuses(private var now: Screen) {
    private val each = Screen.entries.associateWith { ScreenFocus() }

    fun of(screen: Screen) = each.getValue(screen)

    /** Turning to [screen]: what the remote is on in the one it turns from is kept, while it's still on it. */
    fun turnTo(screen: Screen) {
        if (screen == now) return
        of(now).left()
        now = screen
    }
}

/** The [ScreenFocus] of the screen this is in, null outside one. */
internal val LocalScreenFocus = staticCompositionLocalOf<ScreenFocus?> { null }

/** Marks what the remote can come back to in its screen, under [key]: [MAIN_FOCUS] for the screen's main button. */
internal fun Modifier.returnsFocus(key: Any = MAIN_FOCUS): Modifier = this then ReturnsFocusElement(key)

private data class ReturnsFocusElement(val key: Any) : ModifierNodeElement<ReturnsFocusNode>() {
    override fun create() = ReturnsFocusNode(key)

    override fun update(node: ReturnsFocusNode) = node.rekey(key)

    override fun InspectorInfo.inspectableProperties() {
        name = "returnsFocus"
        properties["key"] = key
    }
}

internal class ReturnsFocusNode(private var key: Any) :
    Modifier.Node(), FocusRequesterModifierNode, FocusEventModifierNode, CompositionLocalConsumerModifierNode, ObserverModifierNode {
    private var screen: ScreenFocus? = null

    /** Whether the remote is on it. */
    var focused = false
        private set

    override fun onAttach() = onObservedReadsChanged()

    // Read inside observeReads, the way lint asks a node to read a composition local. The local is a
    // static one, which tells no node it changed, so this runs once, as the node is attached: the
    // screen it's in then is the one it's kept by until it's detached.
    override fun onObservedReadsChanged() = observeReads {
        screen = currentValueOf(LocalScreenFocus)?.also { it.add(key, this) }
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
        focused = focusState.isFocused
    }
}
