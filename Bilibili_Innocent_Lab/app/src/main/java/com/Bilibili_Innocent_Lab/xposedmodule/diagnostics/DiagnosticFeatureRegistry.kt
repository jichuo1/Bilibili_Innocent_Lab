package com.Bilibili_Innocent_Lab.xposedmodule.diagnostics

import androidx.annotation.StringRes
import com.Bilibili_Innocent_Lab.xposedmodule.R

/** 诊断页使用的稳定功能分类；只描述逻辑能力，不引用宿主类名或设置值。 */
internal enum class DiagnosticFeatureCategory {
    ADVERTISING,
    HOME_AND_DYNAMIC,
    MINE,
    PLAYER_AND_DETAIL,
    COMMENTS,
    GENERAL,
    COMPATIBILITY
}

/**
 * 统一诊断功能描述。
 *
 * [runtimeEvidenceExpected] 仅表示该功能已经接入 OBSERVED/APPLIED 运行时证据；未接入的功能仍会
 * 通过安装器结果证明是否完成注册，不能因缺少运行时证据而被误判为失败。
 */
internal data class DiagnosticFeatureDescriptor(
    val id: String,
    val category: DiagnosticFeatureCategory,
    val runtimeEvidenceExpected: Boolean = false,
    @StringRes val labelRes: Int? = null
)

/** 宿主协议、诊断页面和报告共同使用的唯一功能 ID 白名单。 */
internal object DiagnosticFeatureRegistry {
    private val groups: List<DiagnosticFeatureDescriptor> = listOf(
        DiagnosticFeatureDescriptor("system_environment_detection", DiagnosticFeatureCategory.COMPATIBILITY, runtimeEvidenceExpected = true, labelRes = R.string.block_system_env_detection),
        DiagnosticFeatureDescriptor("host_predictive_back", DiagnosticFeatureCategory.COMPATIBILITY, runtimeEvidenceExpected = true, labelRes = R.string.host_predictive_back_enable),
        DiagnosticFeatureDescriptor("ai_insight", DiagnosticFeatureCategory.PLAYER_AND_DETAIL, labelRes = R.string.ai_insight_title),
        DiagnosticFeatureDescriptor("guest_browsing", DiagnosticFeatureCategory.GENERAL, runtimeEvidenceExpected = true, labelRes = R.string.no_login_enabled),
        DiagnosticFeatureDescriptor("incognito_privacy", DiagnosticFeatureCategory.GENERAL, runtimeEvidenceExpected = true, labelRes = R.string.incognito_enabled),
        DiagnosticFeatureDescriptor("player_audio_rescue", DiagnosticFeatureCategory.PLAYER_AND_DETAIL, labelRes = R.string.player_audio_rescue),
        DiagnosticFeatureDescriptor("host_video_cards", DiagnosticFeatureCategory.GENERAL, runtimeEvidenceExpected = true, labelRes = R.string.host_video_cards),
        DiagnosticFeatureDescriptor("host_background", DiagnosticFeatureCategory.GENERAL, runtimeEvidenceExpected = true, labelRes = R.string.host_background_title),
        DiagnosticFeatureDescriptor("search_home_recommend_hidden", DiagnosticFeatureCategory.HOME_AND_DYNAMIC, runtimeEvidenceExpected = true, labelRes = R.string.hide_search_home_recommend),
        DiagnosticFeatureDescriptor("paused_ad", DiagnosticFeatureCategory.ADVERTISING, labelRes = R.string.paused_page_ad_enable),
        DiagnosticFeatureDescriptor("game_mentioned_promotion", DiagnosticFeatureCategory.ADVERTISING, labelRes = R.string.gamecard_ad_enable),
        DiagnosticFeatureDescriptor(
            "detail_app_promotion",
            DiagnosticFeatureCategory.ADVERTISING,
            runtimeEvidenceExpected = true,
            labelRes = R.string.hide_video_detail_app_promotion
        ),
        DiagnosticFeatureDescriptor("follow_up_recommend", DiagnosticFeatureCategory.ADVERTISING, runtimeEvidenceExpected = true, labelRes = R.string.hide_follow_up_recommend),
        DiagnosticFeatureDescriptor("live_home_promotion", DiagnosticFeatureCategory.HOME_AND_DYNAMIC, runtimeEvidenceExpected = true, labelRes = R.string.hide_live_home_promotion),
        DiagnosticFeatureDescriptor("home_banner", DiagnosticFeatureCategory.ADVERTISING, labelRes = R.string.banner_ad_enable),
        DiagnosticFeatureDescriptor("merchandise", DiagnosticFeatureCategory.ADVERTISING, labelRes = R.string.merch_ad_enable),
        DiagnosticFeatureDescriptor(
            "splash_ad_purify",
            DiagnosticFeatureCategory.ADVERTISING,
            runtimeEvidenceExpected = true,
            labelRes = R.string.diagnostics_host_feature_splash
        ),
        DiagnosticFeatureDescriptor("home_top_bar_purify", DiagnosticFeatureCategory.HOME_AND_DYNAMIC, labelRes = R.string.home_top_bar_settings),
        // 该功能可能长期"已安装但从未生效"（宿主路由缺 cid 等）。标记为期望运行时证据，
        // 诊断页才会显示 OBSERVED/APPLIED 行，让"装了却不工作"可见。
        DiagnosticFeatureDescriptor(
            "home_vertical_detail",
            DiagnosticFeatureCategory.HOME_AND_DYNAMIC,
            runtimeEvidenceExpected = true,
            labelRes = R.string.home_vertical_open_detail
        ),
        DiagnosticFeatureDescriptor(
            "home_recommend_purify",
            DiagnosticFeatureCategory.HOME_AND_DYNAMIC,
            runtimeEvidenceExpected = true,
            labelRes = R.string.diagnostics_host_feature_home_feed
        ),
        DiagnosticFeatureDescriptor("home_tab_filter", DiagnosticFeatureCategory.HOME_AND_DYNAMIC, labelRes = R.string.custom_home_tab_hide),
        DiagnosticFeatureDescriptor("home_component_filter", DiagnosticFeatureCategory.HOME_AND_DYNAMIC, labelRes = R.string.custom_home_component_hide),
        DiagnosticFeatureDescriptor("bottom_bar", DiagnosticFeatureCategory.HOME_AND_DYNAMIC, labelRes = R.string.custom_bottom_bar_hide),
        DiagnosticFeatureDescriptor("story_purify", DiagnosticFeatureCategory.HOME_AND_DYNAMIC, labelRes = R.string.story_purify_settings),
        DiagnosticFeatureDescriptor("story_action_icons", DiagnosticFeatureCategory.HOME_AND_DYNAMIC,
            runtimeEvidenceExpected = true, labelRes = R.string.story_action_icons_title),
        DiagnosticFeatureDescriptor("dynamic_tabs_purify", DiagnosticFeatureCategory.HOME_AND_DYNAMIC, labelRes = R.string.dynamic_page_settings),
        DiagnosticFeatureDescriptor(
            "dynamic_purify",
            DiagnosticFeatureCategory.HOME_AND_DYNAMIC,
            runtimeEvidenceExpected = true,
            labelRes = R.string.diagnostics_host_feature_dynamic_feed
        ),
        DiagnosticFeatureDescriptor(
            "search_purify",
            DiagnosticFeatureCategory.HOME_AND_DYNAMIC,
            runtimeEvidenceExpected = true,
            labelRes = R.string.diagnostics_host_feature_search
        ),
        DiagnosticFeatureDescriptor("mine_vip_purify", DiagnosticFeatureCategory.MINE, labelRes = R.string.hide_mine_vip),
        DiagnosticFeatureDescriptor(
            "mine_component_filter",
            DiagnosticFeatureCategory.MINE,
            runtimeEvidenceExpected = true,
            labelRes = R.string.diagnostics_host_feature_mine
        ),
        DiagnosticFeatureDescriptor("player_portrait_control", DiagnosticFeatureCategory.PLAYER_AND_DETAIL, labelRes = R.string.hide_player_portrait_control),
        DiagnosticFeatureDescriptor("player_end_page_recommend", DiagnosticFeatureCategory.PLAYER_AND_DETAIL,
            runtimeEvidenceExpected = true, labelRes = R.string.hide_player_end_page_recommend),
        DiagnosticFeatureDescriptor(
            "player_popup_promotion",
            DiagnosticFeatureCategory.PLAYER_AND_DETAIL,
            runtimeEvidenceExpected = true,
            labelRes = R.string.hide_player_popup_promotion
        ),
        DiagnosticFeatureDescriptor(
            "player_interactive_overlay",
            DiagnosticFeatureCategory.PLAYER_AND_DETAIL,
            runtimeEvidenceExpected = true,
            labelRes = R.string.hide_player_interactive_overlays
        ),
        DiagnosticFeatureDescriptor(
            "pgc_auto_activity_popup",
            DiagnosticFeatureCategory.PLAYER_AND_DETAIL,
            runtimeEvidenceExpected = true,
            labelRes = R.string.hide_pgc_auto_activity_popup
        ),
        DiagnosticFeatureDescriptor("player_status_bar", DiagnosticFeatureCategory.PLAYER_AND_DETAIL, labelRes = R.string.transparent_player_status_bar),
        DiagnosticFeatureDescriptor("player_capabilities", DiagnosticFeatureCategory.PLAYER_AND_DETAIL,
            runtimeEvidenceExpected = true, labelRes = R.string.player_capabilities_title),
        DiagnosticFeatureDescriptor("player_speed", DiagnosticFeatureCategory.PLAYER_AND_DETAIL,
            runtimeEvidenceExpected = true, labelRes = R.string.player_speed_title),
        DiagnosticFeatureDescriptor("player_subtitle_style", DiagnosticFeatureCategory.PLAYER_AND_DETAIL,
            runtimeEvidenceExpected = true, labelRes = R.string.player_subtitle_style),
        DiagnosticFeatureDescriptor("player_ai_subtitle", DiagnosticFeatureCategory.PLAYER_AND_DETAIL,
            runtimeEvidenceExpected = true, labelRes = R.string.player_ai_subtitle),
        DiagnosticFeatureDescriptor("player_buffer", DiagnosticFeatureCategory.PLAYER_AND_DETAIL,
            runtimeEvidenceExpected = true, labelRes = R.string.player_buffer_strategy),
        DiagnosticFeatureDescriptor("sponsorblock", DiagnosticFeatureCategory.PLAYER_AND_DETAIL,
            runtimeEvidenceExpected = true, labelRes = R.string.sponsorblock_title),
        DiagnosticFeatureDescriptor("sponsorblock_story", DiagnosticFeatureCategory.PLAYER_AND_DETAIL,
            runtimeEvidenceExpected = true, labelRes = R.string.sponsorblock_story_enabled),
        DiagnosticFeatureDescriptor(
            "danmaku_purify",
            DiagnosticFeatureCategory.PLAYER_AND_DETAIL,
            runtimeEvidenceExpected = true,
            labelRes = R.string.diagnostics_host_feature_danmaku
        ),
        DiagnosticFeatureDescriptor(
            "live_room_widgets",
            DiagnosticFeatureCategory.PLAYER_AND_DETAIL,
            runtimeEvidenceExpected = true,
            labelRes = R.string.diagnostics_host_feature_live_room
        ),
        DiagnosticFeatureDescriptor(
            "video_relate_filter",
            DiagnosticFeatureCategory.PLAYER_AND_DETAIL,
            runtimeEvidenceExpected = true,
            labelRes = R.string.diagnostics_host_feature_relate
        ),
        // 详细页组件净化：各子项在清除成功后报 APPLIED（见 DetailModuleReplyCleaner）。
        DiagnosticFeatureDescriptor(
            "detail_module_purify",
            DiagnosticFeatureCategory.PLAYER_AND_DETAIL,
            runtimeEvidenceExpected = true,
            labelRes = R.string.detail_module_purify_settings
        ),
        // 详细页 View 层净化（关注按钮、热搜横条），命中时报 APPLIED。
        DiagnosticFeatureDescriptor(
            "detail_view_purify",
            DiagnosticFeatureCategory.PLAYER_AND_DETAIL,
            runtimeEvidenceExpected = true,
            labelRes = R.string.detail_module_purify_settings
        ),
        // United 详情页的标题 label / SpecialTag 在渲染模型层按 URI 精确过滤。
        DiagnosticFeatureDescriptor(
            "detail_united_presentation_purify",
            DiagnosticFeatureCategory.PLAYER_AND_DETAIL,
            runtimeEvidenceExpected = true,
            labelRes = R.string.detail_module_purify_settings
        ),
        // United 详情页协议层：按 ModuleType 删模块 + 清 owner.vip。
        // 这是 detail_module_purify 那五项的正确落点，两者互为保底。
        DiagnosticFeatureDescriptor(
            "detail_united_module_purify",
            DiagnosticFeatureCategory.PLAYER_AND_DETAIL,
            runtimeEvidenceExpected = true,
            labelRes = R.string.detail_module_purify_settings
        ),
        // 同一对 ViewMoss 方法上的另一个安装器：只改 ecode/ecode_config 与相关推荐卡。
        DiagnosticFeatureDescriptor(
            "ai_declared_video_block",
            DiagnosticFeatureCategory.PLAYER_AND_DETAIL,
            runtimeEvidenceExpected = true,
            labelRes = R.string.block_ai_declared_videos
        ),
        DiagnosticFeatureDescriptor(
            "player_default_quality",
            DiagnosticFeatureCategory.PLAYER_AND_DETAIL,
            runtimeEvidenceExpected = true,
            labelRes = R.string.diagnostics_host_feature_quality
        ),
        DiagnosticFeatureDescriptor("comment_section", DiagnosticFeatureCategory.COMMENTS, labelRes = R.string.hide_comment_section),
        DiagnosticFeatureDescriptor("comment_classic_style", DiagnosticFeatureCategory.COMMENTS, runtimeEvidenceExpected = true, labelRes = R.string.comment_classic_style),
        DiagnosticFeatureDescriptor("comment_reply_button", DiagnosticFeatureCategory.COMMENTS, runtimeEvidenceExpected = true, labelRes = R.string.remove_comment_reply_button),
        DiagnosticFeatureDescriptor(
            "comment_purify",
            DiagnosticFeatureCategory.COMMENTS,
            runtimeEvidenceExpected = true,
            labelRes = R.string.diagnostics_host_feature_comment_purify
        ),
        DiagnosticFeatureDescriptor(
            "comment_filter",
            DiagnosticFeatureCategory.COMMENTS,
            runtimeEvidenceExpected = true,
            labelRes = R.string.diagnostics_host_feature_comment_filter
        ),
        DiagnosticFeatureDescriptor("comment_topology", DiagnosticFeatureCategory.COMMENTS, labelRes = R.string.reply_topology_enabled),
        DiagnosticFeatureDescriptor(
            "free_copy",
            DiagnosticFeatureCategory.COMMENTS,
            runtimeEvidenceExpected = true,
            labelRes = R.string.free_copy_enable
        ),
        DiagnosticFeatureDescriptor("block_app_update", DiagnosticFeatureCategory.GENERAL, labelRes = R.string.block_app_update),
        DiagnosticFeatureDescriptor("block_component_library_download", DiagnosticFeatureCategory.GENERAL, labelRes = R.string.block_component_library_download),
        DiagnosticFeatureDescriptor("full_number_display", DiagnosticFeatureCategory.GENERAL, labelRes = R.string.show_full_numbers),
        DiagnosticFeatureDescriptor("teenagers_mode_prompt", DiagnosticFeatureCategory.GENERAL, labelRes = R.string.block_teenagers_mode_prompt),
        DiagnosticFeatureDescriptor(
            "share_purify",
            DiagnosticFeatureCategory.GENERAL,
            runtimeEvidenceExpected = true,
            labelRes = R.string.diagnostics_host_feature_share
        ),
        DiagnosticFeatureDescriptor("wechat_share_compat", DiagnosticFeatureCategory.COMPATIBILITY,
            runtimeEvidenceExpected = true, labelRes = R.string.wechat_share_compat),
        DiagnosticFeatureDescriptor(
            "external_browser",
            DiagnosticFeatureCategory.GENERAL,
            runtimeEvidenceExpected = true,
            labelRes = R.string.force_external_browser
        ),
        DiagnosticFeatureDescriptor(
            "system_media_notification",
            DiagnosticFeatureCategory.GENERAL,
            runtimeEvidenceExpected = true,
            labelRes = R.string.system_media_notification
        ),
        DiagnosticFeatureDescriptor(
            "home_frame_lock",
            DiagnosticFeatureCategory.GENERAL,
            runtimeEvidenceExpected = true,
            labelRes = R.string.home_frame_legacy_lock
        ),
        DiagnosticFeatureDescriptor(
            "home_recommend_early_load",
            DiagnosticFeatureCategory.GENERAL,
            runtimeEvidenceExpected = true,
            labelRes = R.string.home_recommend_early_load
        ),
        DiagnosticFeatureDescriptor(
            "cloud_key_freeze",
            DiagnosticFeatureCategory.GENERAL,
            runtimeEvidenceExpected = true,
            labelRes = R.string.cloud_key_freeze
        ),
        DiagnosticFeatureDescriptor(
            "splash_auto_night",
            DiagnosticFeatureCategory.GENERAL,
            runtimeEvidenceExpected = true,
            labelRes = R.string.splash_auto_night
        ),
        DiagnosticFeatureDescriptor("brand_splash_skip", DiagnosticFeatureCategory.GENERAL, runtimeEvidenceExpected = true, labelRes = R.string.brand_splash_skip),
        DiagnosticFeatureDescriptor("brand_splash_custom", DiagnosticFeatureCategory.GENERAL, runtimeEvidenceExpected = true, labelRes = R.string.brand_splash_custom),
        DiagnosticFeatureDescriptor(
            "bv_to_av",
            DiagnosticFeatureCategory.GENERAL,
            runtimeEvidenceExpected = true,
            labelRes = R.string.show_bv_as_av
        ),
        DiagnosticFeatureDescriptor("roaming_compat", DiagnosticFeatureCategory.COMPATIBILITY, labelRes = R.string.roaming_compat_enable)
    ).also { values ->
        require(values.map(DiagnosticFeatureDescriptor::id).toSet().size == values.size) {
            "Duplicate diagnostic feature id"
        }
    }

    private val groupsById: Map<String, DiagnosticFeatureDescriptor> =
        groups.associateBy(DiagnosticFeatureDescriptor::id)

    val descriptors: List<DiagnosticFeatureDescriptor> = groups + DiagnosticCapabilityCatalog.definitions
        .mapNotNull { capability ->
            if (capability.id == capability.parentId) return@mapNotNull null
            val parent = requireNotNull(groupsById[capability.parentId]) {
                "Missing diagnostic parent group: ${capability.parentId}"
            }
            DiagnosticFeatureDescriptor(
                capability.id,
                parent.category,
                runtimeEvidenceExpected = DiagnosticCapabilityCatalog.explicitRuntimeSupport(capability.id) > 0,
                labelRes = capability.labelRes
            )
        }

    val ids: Set<String> = descriptors.mapTo(linkedSetOf(), DiagnosticFeatureDescriptor::id)
    fun isAggregate(id: String): Boolean = id in DiagnosticCapabilityCatalog.splitParents

    private val byId = descriptors.associateBy(DiagnosticFeatureDescriptor::id)

    fun descriptorOrNull(id: String): DiagnosticFeatureDescriptor? = byId[id]

    @StringRes
    internal fun titleResId(featureId: String): Int? =
        DiagnosticCapabilityCatalog.byId[featureId]?.labelRes ?: byId[featureId]?.labelRes
}
