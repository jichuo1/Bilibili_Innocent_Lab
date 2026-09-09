package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid

/** Small controls use inexpensive translucent optics, never additional capture/shader sessions. */
internal object LiquidControlStyle {
    data class State(val enabled: Boolean, val selected: Boolean, val emphasized: Boolean)

    fun resolve(enabled: Boolean, checked: Boolean, pressed: Boolean, focused: Boolean) =
        State(enabled, checked, enabled && (pressed || focused))

    fun opacity(state: State): Int = if (state.enabled) 255 else 100
    fun fillAlpha(state: State): Int = when {
        state.selected -> 190
        state.emphasized -> 160
        else -> 116
    }
}
