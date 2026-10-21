package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.Bilibili_Innocent_Lab.xposedmodule.settings.ModuleUiSettings
import com.highcapable.betterandroid.ui.extension.view.child

/** Activity-owned bindings; preferences are observed only until disposal. */
internal class SettingsDependencyBindings {
    private data class Node(val view: View, val enabled: Boolean, val focusable: Boolean,
        val touchFocusable: Boolean)
    private class Row(val view: View) {
        val ids = linkedSetOf<String>()
        var normalAlpha = 1f
        var nodes = emptyList<Node>()
        var available: Boolean? = null
    }
    private val rows = linkedMapOf<View, Row>()
    private val handler = Handler(Looper.getMainLooper())
    private var preferences: SharedPreferences? = null
    private var disposed = false
    private var started = false
    private val refresh = Runnable { if (!disposed) apply(ModuleUiSettings.read(preferences)) }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key in SettingsDependencies.parentKeys) {
            if (Looper.myLooper() == Looper.getMainLooper()) refresh.run()
            else { handler.removeCallbacks(refresh); handler.post(refresh) }
        }
    }

    fun bind(view: View, id: String) {
        if (id !in SettingsDependencies.requirements) return
        rows.getOrPut(view) { Row(view) }.ids += id
    }

    /** Called after inflation/styling, so normal emphasis and child states are authoritative. */
    fun start(source: SharedPreferences?) {
        if (disposed) return
        // Capture normal states once. A repeated start must not promote disabled/dimmed states
        // from the previous application to the new baseline.
        if (!started) {
            rows.values.forEach { row ->
                row.normalAlpha = row.view.alpha
                val nodes = mutableListOf<Node>()
                fun collect(view: View) {
                    nodes += Node(view, view.isEnabled, view.isFocusable, view.isFocusableInTouchMode)
                    if (view is ViewGroup) for (i in 0 until view.childCount) collect(view.child(i))
                }
                collect(row.view)
                row.nodes = nodes
            }
            started = true
        }
        if (preferences !== source) {
            preferences?.unregisterOnSharedPreferenceChangeListener(listener)
            preferences = source
            source?.registerOnSharedPreferenceChangeListener(listener)
        }
        refresh.run()
    }

    private fun apply(settings: ModuleUiSettings) {
        rows.values.forEach { row ->
            val available = row.ids.all { SettingsDependencies.available(it, settings) }
            if (row.available == available) return@forEach
            row.available = available
            row.view.alpha = row.normalAlpha * if (available) 1f else .45f
            row.nodes.forEach { node ->
                node.view.isEnabled = available && node.enabled
                node.view.isFocusable = available && node.focusable
                node.view.isFocusableInTouchMode = available && node.touchFocusable
            }
            if (!available) row.view.clearFocus()
        }
    }

    fun dispose() {
        disposed = true
        preferences?.unregisterOnSharedPreferenceChangeListener(listener)
        preferences = null
        handler.removeCallbacks(refresh)
        rows.clear()
    }
}
