package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.app.Activity
import android.app.Instrumentation
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SponsorStoryAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor.SponsorSegmentRepository
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor.StorySponsorSessions
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Story（竖屏短视频）的商单跳过。独立的安装器与诊断 id：和详情页的 `sponsorblock` 分开报告，
 * Story 结构缺失不会改变详情页功能的状态，反之亦然。所有宿主成员都直接按稳定名称或类型解析，不需要语义 DEX 兜底，也就没有适配缓存。
 */
internal class SponsorBlockStoryFeatureInstaller(
    private val enabled: Boolean,
    private val automatic: Boolean,
    private val repository: () -> SponsorSegmentRepository
) : FeatureInstaller {
    private val registrationStarted = AtomicBoolean(false)
    override val id = ID

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) return FeatureInstallResult.Skipped("disabled")
        if (environment.processName != "tv.danmaku.bili") return FeatureInstallResult.Skipped("non-main-process")
        val loader = environment.classLoader ?: return FeatureInstallResult.Skipped("missing-class-loader")
        val access = SponsorStoryAccess.resolve(loader) ?: run {
            environment.reportStatus(STATUS, MISSING)
            return FeatureInstallResult.Skipped(MISSING)
        }
        val lifecycle = listOf("callActivityOnResume", "callActivityOnPause", "callActivityOnDestroy").map { name ->
            Lookup.methodOrNull(Instrumentation::class.java, name, Activity::class.java)?.takeIf {
                it.returnType == Void.TYPE && it.parameterTypes.toList() == listOf(Activity::class.java)
            } ?: return FeatureInstallResult.Skipped("missing-story-lifecycle")
        }
        // 注册开始后不重放部分 Hook，也不重复创建会话。
        if (!registrationStarted.compareAndSet(false, true)) return FeatureInstallResult.Skipped("already-registered")
        val count = StorySponsorSessions(environment, access, automatic, repository(), ID).install(lifecycle)
        environment.reportStatus(STATUS, "success")
        return FeatureInstallResult.Installed(count)
    }

    companion object {
        const val ID = "sponsorblock_story"
        const val STATUS = "sponsorblock_story_status"
        const val MISSING = "missing-story-structure"
    }
}
