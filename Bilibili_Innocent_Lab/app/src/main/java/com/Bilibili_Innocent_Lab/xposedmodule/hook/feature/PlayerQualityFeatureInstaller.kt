package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.NormalQualityLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.StoryQualityHostAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.StoryQualityLocator

/** 普通默认入口与 Story 会话各自安装；部分家族失败保留在覆盖分母中。 */
internal class PlayerQualityFeatureInstaller(
    qualityQn: Int,
    private val points: VersionAdapter.PlayerQualityPoints?,
    private val pointsProvider: () -> VersionAdapter.PlayerQualityPoints? = { points }
) : FeatureInstaller {

    override val id: String = ID
    private val normalizedQn = PlayerQualityConfig.normalize(qualityQn)
    private val runtime = StoryQualityRuntime(normalizedQn)
    private val story = StoryQualityFeatureHooks(runtime)
    private val normal = NormalQualityFeatureHooks { story.globalTarget(normalizedQn) }
    private var globalInstalled = false
    private var storyApplicable = true
    private var normalApplicable = false
    val requiresAdaptationRetry: Boolean get() = normalizedQn != 0 &&
        (!globalInstalled || storyApplicable && !story.complete || normalApplicable && !normal.complete)

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (normalizedQn == 0) {
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val current = pointsProvider()
        val loader = environment.classLoader
        normalApplicable = NormalQualityLocator.applicable(current?.defaultQualityMethod)
        if (normalApplicable && !normal.complete && loader != null) {
            NormalQualityLocator.resolve(loader)?.let { normal.install(environment, it) }
        }
        storyApplicable = loader == null || StoryQualityLocator.applicable(loader)
        if (storyApplicable && !story.complete && loader != null) {
            val roles = StoryQualityLocator.resolve(loader, current?.story)
            val access = roles?.let { StoryQualityHostAccess.resolve(loader, it) }
            if (access != null) story.install(environment, access)
        }
        if (!globalInstalled) current?.defaultQualityMethod?.let { point ->
            globalInstalled = runCatching {
                environment.registrar.adapted("player.default_quality", point) {
                    before {
                        story.globalTarget(normalizedQn)?.let { qn ->
                            runCatching { environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED) }
                            result = qn
                            runCatching { environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED) }
                        }
                    }
                }
                true
            }.getOrElse {
                environment.logError("player_quality_registration", "[BIL] 播放器默认画质入口注册失败")
                false
            }
        }
        val installed = (if (globalInstalled) 1 else 0) + (if (story.complete) 1 else 0) +
            (if (normal.complete) 1 else 0)
        val expected = 1 + (if (storyApplicable) 1 else 0) + (if (normalApplicable) 1 else 0)
        val status = when {
            installed == expected -> "success"
            installed > 0 -> "partial:$installed/$expected"
            else -> "missing-adapter-point"
        }
        environment.reportStatus(CHANNEL_STATUS, status)
        if (installed == 0) return FeatureInstallResult.Skipped("missing-adapter-point")
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        environment.logInfo("player_quality_ok", "[BIL] 默认画质家族安装: $installed/$expected")
        return FeatureInstallResult.Installed((if (globalInstalled) 1 else 0) + story.hookCount + normal.hookCount,
            installed == expected)
    }

    companion object {
        const val ID = "player_default_quality"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "player_quality_status"
    }
}
