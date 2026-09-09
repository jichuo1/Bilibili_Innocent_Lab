package com.Bilibili_Innocent_Lab.xposedmodule.ui.release

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences

/** Module-only presentation state; excluded from settings backup, Remote config and telemetry. */
internal object ReleaseHighlightsStore {
    const val PREF_FILE = "release_highlights_state"
    private val lock = Any()
    private const val SCHEMA = 1

    @Suppress("DEPRECATION")
    fun prepare(context: Context): Int? = synchronized(lock) {
        runCatching {
            val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            val revision = ReleaseHighlightsCatalog.currentRevision
            val state = if (prefs.all.isEmpty()) {
                val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
                ReleaseHighlightsPolicy.bootstrap(revision,
                    ReleaseHighlightsPolicy.upgraded(info?.firstInstallTime, info?.lastUpdateTime))
                    .also { check(write(prefs,it)) }
            } else decode(prefs.all) ?: return@runCatching null
            val pending = ReleaseHighlightsPolicy.pendingFrom(state, revision) ?: return@runCatching null
            if (ReleaseHighlightsCatalog.entriesAfter(pending, automatic = true).isEmpty()) {
                write(prefs,ReleaseHighlightsPolicy.skipAutomatic(state,revision))
                null
            } else pending
        }.getOrNull()
    }

    fun markPresented(context: Context): Boolean = synchronized(lock) {
        runCatching {
            val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            val state = decode(prefs.all) ?: return@runCatching false
            write(prefs,ReleaseHighlightsPolicy.presented(state,ReleaseHighlightsCatalog.currentRevision))
        }.getOrDefault(false)
    }

    internal fun decode(values: Map<String, *>): ReleaseHighlightsState? {
        if (values["schema"] != SCHEMA) return null
        val baseline = values["baseline"] as? Int ?: return null
        val presented = values["presented"] as? Int ?: return null
        if (baseline < 0 || presented < 0) return null
        return ReleaseHighlightsState(baseline,presented)
    }

    @SuppressLint("UseKtx") // A failed commit must not be mistaken for a persisted display marker.
    private fun write(prefs: SharedPreferences, state: ReleaseHighlightsState): Boolean =
        prefs.edit().putInt("schema",SCHEMA).putInt("baseline",state.baseline)
            .putInt("presented",state.presented).commit()
}
