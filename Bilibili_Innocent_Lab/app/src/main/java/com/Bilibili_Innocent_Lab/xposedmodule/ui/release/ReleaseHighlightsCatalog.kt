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
        // 四个子项各自成条：门禁要求每个新增设置都有导航目标，而
        // HighlightDestination 一条只能指一个 settingId。标题自动取设置自己的 labelRes。
        ReleaseHighlight("detail-honor", HighlightKind.NEW,
            R.string.highlights_detail_honor,
            HighlightDestination("purify.detail.honor.removed")),
        ReleaseHighlight("detail-live-order", HighlightKind.NEW,
            R.string.highlights_detail_live_order,
            HighlightDestination("purify.detail.live_order.removed")),
        ReleaseHighlight("detail-ugc-season", HighlightKind.NEW,
            R.string.highlights_detail_ugc_season,
            HighlightDestination("purify.detail.ugc_season.removed")),
        ReleaseHighlight("detail-up-vip-label", HighlightKind.NEW,
            R.string.highlights_detail_up_vip_label,
            HighlightDestination("purify.detail.up_vip_label.removed")),
        ReleaseHighlight("detail-topic-tags", HighlightKind.NEW,
            R.string.highlights_detail_topic_tags,
            HighlightDestination("purify.detail.topic_tags.removed")),
        ReleaseHighlight("detail-staff-follow", HighlightKind.NEW,
            R.string.highlights_detail_staff_follow,
            HighlightDestination("purify.detail.staff_follow.hidden")),
        ReleaseHighlight("detail-hot-banner", HighlightKind.NEW,
            R.string.highlights_detail_hot_banner,
            HighlightDestination("purify.detail.hot_banner.hidden")),
        ReleaseHighlight("panel-window-blur", HighlightKind.NEW, R.string.highlights_panel_blur,
            HighlightDestination("module_ui.appearance.panel_window_blur")),
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
