package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter

/** 原生面板中显式选择模块屏蔽项；官方不感兴趣操作不再隐式写入模块名单。 */
internal class SectionPickFeatureInstaller(
    private val enabled: Boolean,
    private val points: VersionAdapter.HomeRecommendFeedPoints?,
    private val relatedPoints: VersionAdapter.VideoRelatePoints? = null
) : FeatureInstaller {
    override val id: String = ID

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) return FeatureInstallResult.Skipped("disabled")
        if (environment.processName != "tv.danmaku.bili") {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        return NativeFeedbackPanel.install(environment, points, relatedPoints)
    }

    companion object {
        const val ID = "home_recommend_section_pick"
    }
}
