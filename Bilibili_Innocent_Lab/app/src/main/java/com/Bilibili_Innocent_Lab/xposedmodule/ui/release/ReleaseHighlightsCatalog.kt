package com.Bilibili_Innocent_Lab.xposedmodule.ui.release

import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog

internal enum class HighlightKind { NEW, IMPROVED, FIXED }
internal data class HighlightDestination(val settingId: String, val homeFilterOption: Boolean = false)
internal data class ReleaseHighlight(
    val id: String, val kind: HighlightKind, val descriptionRes: Int,
    val destination: HighlightDestination? = null, val standaloneTitleRes: Int? = null
) {
    val titleRes: Int get() = standaloneTitleRes ?: SettingsCatalog.byId.getValue(requireNotNull(destination).settingId).labelRes
}
internal data class ReleaseHighlightsBatch(
    val revision: Int, val entries: List<ReleaseHighlight>, val automatic: Boolean = true
)

/** Bundled and reviewed with the APK; never executes remote release-note links as navigation. */
internal object ReleaseHighlightsCatalog {
    const val REVIEWED_VERSION_CODE = 16
    const val SETTINGS_BASELINE_VERSION = 13
    val batches = listOf(ReleaseHighlightsBatch(1, listOf(
        ReleaseHighlight("search-home-hidden", HighlightKind.NEW, R.string.highlights_search,
            HighlightDestination("search.home_recommend.hidden")),
        ReleaseHighlight("dynamic-frequent-hidden", HighlightKind.NEW, R.string.highlights_dynamic,
            HighlightDestination("dynamic.frequent_visits.hidden")),
        ReleaseHighlight("home-pgc-filter", HighlightKind.NEW, R.string.highlights_pgc,
            HighlightDestination("home.recommend.pgc.removed", true)),
        ReleaseHighlight("home-special-filter", HighlightKind.NEW, R.string.highlights_special,
            HighlightDestination("home.recommend.special_cards.removed", true)),
        ReleaseHighlight("default-speed-sessions", HighlightKind.IMPROVED, R.string.highlights_speed,
            HighlightDestination(SettingsCatalog.ID_PLAYER_DEFAULT_SPEED)),
        ReleaseHighlight("interactive-response-coverage", HighlightKind.FIXED, R.string.highlights_interactive,
            HighlightDestination("player.interactive_overlays.hidden")),
        ReleaseHighlight("independent-adaptation", HighlightKind.FIXED, R.string.highlights_stability,
            standaloneTitleRes = R.string.highlights_stability_title)
    )))
    val currentRevision: Int get() = batches.maxOf { it.revision }
    val destinations get() = batches.sortedByDescending { it.revision }.flatMap { it.entries }
        .mapNotNull { it.destination }.distinctBy { it.settingId }

    fun entriesAfter(revision: Int, automatic: Boolean = false): List<ReleaseHighlight> =
        ReleaseHighlightsPolicy.entriesAfter(batches,revision,automatic)

    fun currentEntries(): List<ReleaseHighlight> = entriesAfter(currentRevision - 1)
}
