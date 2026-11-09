package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.app.Activity
import android.app.Instrumentation
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SponsorPlayerClasses
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SponsorPlayerLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor.SponsorBlockClient
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor.SponsorPlayerSessions
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor.SponsorSegmentRepository
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup

internal class SponsorBlockFeatureInstaller(private val enabled: Boolean, private val automatic: Boolean,
    /** 与 Story 安装器共用同一个仓库，缓存和 429 退避状态才是一份。 */
    private val repository: () -> SponsorSegmentRepository = {
        SponsorSegmentRepository(SponsorBlockClient(BuildConfig.VERSION_NAME))
    },
    // 保持最后一个参数：现有调用方用尾随 lambda 传它。
    private val cachedClasses: () -> SponsorPlayerClasses? = { null }) : FeatureInstaller {
    @Volatile var requiresAdaptationRetry = false
        private set
    private val registrationStarted = java.util.concurrent.atomic.AtomicBoolean(false)
    override val id = ID
    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) return FeatureInstallResult.Skipped("disabled")
        if (environment.processName != "tv.danmaku.bili") return FeatureInstallResult.Skipped("non-main-process")
        val loader = environment.classLoader ?: return FeatureInstallResult.Skipped("missing-class-loader")
        val resolved = SponsorPlayerLocator.direct(loader)
            ?: cachedClasses()?.let { SponsorPlayerLocator.resolve(loader, it) }
        if (resolved == null) {
            requiresAdaptationRetry = true
            return FeatureInstallResult.Skipped("missing-player-structure")
        }
        requiresAdaptationRetry = false
        val player = resolved.player
        val seek = resolved.seek
        val detail = Lookup.classOrNull(loader, DETAIL)?.takeIf { Activity::class.java.isAssignableFrom(it) }
            ?: return FeatureInstallResult.Skipped("missing-detail-activity")
        val lifecycle = listOf("callActivityOnResume", "callActivityOnPause", "callActivityOnDestroy").map { name ->
            Lookup.methodOrNull(Instrumentation::class.java, name, Activity::class.java)?.takeIf {
                it.returnType == Void.TYPE && it.parameterTypes.toList() == listOf(Activity::class.java)
            }
                ?: return FeatureInstallResult.Skipped("missing-detail-lifecycle")
        }
        val seekMethod = Lookup.methodOrNull(player.wrapper.declaringClass, "seekTo", Int::class.javaPrimitiveType!!,
            Boolean::class.javaPrimitiveType!!) ?: return FeatureInstallResult.Skipped("missing-wrapper-seek")
        // 只重试尚未解析的结构；注册开始后不重放部分 Hook 或重复创建会话。
        if (!registrationStarted.compareAndSet(false, true)) return FeatureInstallResult.Skipped("already-registered")
        val sessions = SponsorPlayerSessions(environment, player, seek, detail, automatic, repository())
        val count = sessions.install(lifecycle, seekMethod)
        environment.reportStatus("sponsorblock_status", "success")
        return FeatureInstallResult.Installed(count)
    }
    companion object {
        const val ID = "sponsorblock"
        const val DETAIL = "com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity"
    }
}
