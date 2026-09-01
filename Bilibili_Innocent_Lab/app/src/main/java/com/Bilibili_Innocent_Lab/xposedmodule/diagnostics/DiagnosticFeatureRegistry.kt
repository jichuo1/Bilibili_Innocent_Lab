package com.Bilibili_Innocent_Lab.xposedmodule.diagnostics

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
    val runtimeEvidenceExpected: Boolean = false
)

/** 宿主协议、诊断页面和报告共同使用的唯一功能 ID 白名单。 */
internal object DiagnosticFeatureRegistry {
    val descriptors: List<DiagnosticFeatureDescriptor> = listOf(
        DiagnosticFeatureDescriptor("paused_ad", DiagnosticFeatureCategory.ADVERTISING),
        DiagnosticFeatureDescriptor("game_mentioned_promotion", DiagnosticFeatureCategory.ADVERTISING),
        DiagnosticFeatureDescriptor(
            "detail_app_promotion",
            DiagnosticFeatureCategory.ADVERTISING,
            runtimeEvidenceExpected = true
        ),
        DiagnosticFeatureDescriptor("home_banner", DiagnosticFeatureCategory.ADVERTISING),
        DiagnosticFeatureDescriptor("merchandise", DiagnosticFeatureCategory.ADVERTISING),
        DiagnosticFeatureDescriptor(
            "splash_ad_purify",
            DiagnosticFeatureCategory.ADVERTISING,
            runtimeEvidenceExpected = true
        ),
        DiagnosticFeatureDescriptor("home_top_bar_purify", DiagnosticFeatureCategory.HOME_AND_DYNAMIC),
        DiagnosticFeatureDescriptor("home_vertical_detail", DiagnosticFeatureCategory.HOME_AND_DYNAMIC),
        DiagnosticFeatureDescriptor(
            "home_recommend_purify",
            DiagnosticFeatureCategory.HOME_AND_DYNAMIC,
            runtimeEvidenceExpected = true
        ),
        DiagnosticFeatureDescriptor("home_tab_filter", DiagnosticFeatureCategory.HOME_AND_DYNAMIC),
        DiagnosticFeatureDescriptor("home_component_filter", DiagnosticFeatureCategory.HOME_AND_DYNAMIC),
        DiagnosticFeatureDescriptor("bottom_bar", DiagnosticFeatureCategory.HOME_AND_DYNAMIC),
        DiagnosticFeatureDescriptor("story_purify", DiagnosticFeatureCategory.HOME_AND_DYNAMIC),
        DiagnosticFeatureDescriptor("dynamic_tabs_purify", DiagnosticFeatureCategory.HOME_AND_DYNAMIC),
        DiagnosticFeatureDescriptor("mine_vip_purify", DiagnosticFeatureCategory.MINE),
        DiagnosticFeatureDescriptor(
            "mine_component_filter",
            DiagnosticFeatureCategory.MINE,
            runtimeEvidenceExpected = true
        ),
        DiagnosticFeatureDescriptor("player_portrait_control", DiagnosticFeatureCategory.PLAYER_AND_DETAIL),
        DiagnosticFeatureDescriptor("player_status_bar", DiagnosticFeatureCategory.PLAYER_AND_DETAIL),
        DiagnosticFeatureDescriptor(
            "video_relate_filter",
            DiagnosticFeatureCategory.PLAYER_AND_DETAIL,
            runtimeEvidenceExpected = true
        ),
        DiagnosticFeatureDescriptor(
            "player_default_quality",
            DiagnosticFeatureCategory.PLAYER_AND_DETAIL,
            runtimeEvidenceExpected = true
        ),
        DiagnosticFeatureDescriptor("comment_section", DiagnosticFeatureCategory.COMMENTS),
        DiagnosticFeatureDescriptor(
            "comment_purify",
            DiagnosticFeatureCategory.COMMENTS,
            runtimeEvidenceExpected = true
        ),
        DiagnosticFeatureDescriptor(
            "comment_filter",
            DiagnosticFeatureCategory.COMMENTS,
            runtimeEvidenceExpected = true
        ),
        DiagnosticFeatureDescriptor("comment_topology", DiagnosticFeatureCategory.COMMENTS),
        DiagnosticFeatureDescriptor(
            "free_copy",
            DiagnosticFeatureCategory.COMMENTS,
            runtimeEvidenceExpected = true
        ),
        DiagnosticFeatureDescriptor("block_app_update", DiagnosticFeatureCategory.GENERAL),
        DiagnosticFeatureDescriptor("full_number_display", DiagnosticFeatureCategory.GENERAL),
        DiagnosticFeatureDescriptor("teenagers_mode_prompt", DiagnosticFeatureCategory.GENERAL),
        DiagnosticFeatureDescriptor("roaming_compat", DiagnosticFeatureCategory.COMPATIBILITY)
    ).also { values ->
        require(values.map(DiagnosticFeatureDescriptor::id).toSet().size == values.size) {
            "Duplicate diagnostic feature id"
        }
    }

    val ids: Set<String> = descriptors.mapTo(linkedSetOf(), DiagnosticFeatureDescriptor::id)

    private val byId = descriptors.associateBy(DiagnosticFeatureDescriptor::id)

    fun descriptorOrNull(id: String): DiagnosticFeatureDescriptor? = byId[id]
}
