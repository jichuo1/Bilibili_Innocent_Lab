package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.view.View

/** Preserves each row's normal emphasis; disabling never changes the saved setting. */
internal class DependentAppearanceOptions(private val available: () -> Boolean) {
    private val rows = ArrayList<Pair<View, Float>>()

    fun add(view: View) {
        rows += view to view.alpha
        apply(view, view.alpha, available())
    }

    fun refresh() {
        val enabled = available()
        rows.forEach { (view, alpha) -> apply(view, alpha, enabled) }
    }

    private fun apply(view: View, normalAlpha: Float, enabled: Boolean) {
        view.alpha = normalAlpha * if (enabled) 1f else .45f
        // Enabled-state observers (favorites) must see the final visual state too.
        view.isEnabled = enabled
    }
}
