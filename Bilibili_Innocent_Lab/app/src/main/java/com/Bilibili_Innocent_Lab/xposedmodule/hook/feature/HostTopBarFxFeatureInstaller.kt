package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.ViewGroup
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPreset
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome.HostComposeChromeHooks
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome.HostHomeLifecycleHooks
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top.HostTopBarFxConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top.HostTopBarFxController
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostChromeAppearance
import java.util.concurrent.atomic.AtomicBoolean

/** Old View and new khome Compose coexist; registration alone is not application evidence. */
internal class HostTopBarFxFeatureInstaller(
    private val liquidGlass: Boolean,
    private val touchGlow: Boolean,
    private val points: VersionAdapter.HomeTopBarPoints?,
    private val appearance: HostChromeAppearance = HostChromeAppearance(),
    private val backgroundPreset: HostBackgroundPreset = HostBackgroundPreset.OFF,
    chromePoints: Map<String, HookPoint> = emptyMap()
) : FeatureInstaller {
    override val id: String = ID
    private val firstHit = AtomicBoolean(false)
    private val firstObserved = AtomicBoolean(false)
    private val firstError = AtomicBoolean(false)
    private val firstMissing = AtomicBoolean(false)
    @Volatile private var chromePoints = chromePoints.filterKeys { it == "top" }

    /** Background adaptation can add points without resetting evidence captured by live callbacks. */
    fun updateChromePoints(points: Map<String, HookPoint>): Boolean {
        val relevant = points.filterKeys { it == "top" }
        if (relevant.all { (family, point) -> chromePoints[family] == point }) return false
        chromePoints = chromePoints + relevant
        return true
    }

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (environment.processName != TARGET_PACKAGE) return FeatureInstallResult.Skipped("non-main-process")
        if (!liquidGlass && !touchGlow) return FeatureInstallResult.Skipped("disabled")
        fun observed() { if (firstObserved.compareAndSet(false, true)) environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED) }
        fun applied() {
            if (firstHit.compareAndSet(false, true)) {
                environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                environment.reportStatus(CHANNEL_STATUS, "applied:surface-attached")
                environment.logInfo("host_top_bar_fx_hit", "[BIL] 宿主顶栏视觉增强已装配(liquidGlass=$liquidGlass, touchGlow=$touchGlow, appearance=$appearance)")
            }
        }
        fun failed(error: Throwable) {
            if (firstError.compareAndSet(false, true)) {
                environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ERROR)
                environment.logError("host_top_bar_fx_error", "[BIL] 宿主顶栏视觉增强失败: $error")
            }
        }
        val config = HostTopBarFxConfig(liquidGlass, touchGlow, appearance, backgroundPreset)
        val viewPoint = points?.baseOnViewCreated
        fun attach(root: ViewGroup, fragment: Any?) {
            observed()
            HostTopBarFxController.attach(root, fragment, config, viewPoint?.viewField,
                onApplied = ::applied, onMissing = {
                    if (!firstHit.get() && firstMissing.compareAndSet(false, true)) {
                        environment.reportStatus(CHANNEL_STATUS, "observed:missing-laid-out-top-bar")
                        environment.logInfo("host_top_bar_fx_layout_missing", "[BIL] 首页入口已命中，顶栏布局尚未就绪或不属于原生栏")
                    }
                }, onError = ::failed)
        }
        var count = 0
        if (viewPoint != null) runCatching {
            environment.registrar.adapted("host_top_bar_fx.view_created", viewPoint) {
                after { if (!hasThrowable) (argOrNull(0) as? ViewGroup)?.let { attach(it, instance) } }
            }
        }.onSuccess { count++ }.onFailure(::failed)
        count += HostHomeLifecycleHooks.install(environment, ID) { root, fragment -> attach(root, fragment) }
        val compose = HostComposeChromeHooks.install(environment, "top", chromePoints, liquidGlass, touchGlow,
            appearance, null, ::observed, ::applied, ::failed)
        if (compose) count++
        if (count == 0) {
            environment.reportStatus(CHANNEL_STATUS, "missing:missing-view-created-point")
            return FeatureInstallResult.Skipped("missing-view-created-point")
        }
        val composeExpected = environment.classLoader?.let {
            KavaMemberLookup.classOrNull(it, "tv.danmaku.bili.khome.widget.topbar.HomeTopTabKt") != null
        } == true
        if (!firstHit.get()) {
            environment.reportStatus(CHANNEL_STATUS, "installed:legacy=${count - if (compose) 1 else 0},compose=$compose")
            environment.logInfo("host_top_bar_fx_ok", "[BIL] 宿主顶栏入口已注册，等待实际装配(legacy=${count - if (compose) 1 else 0},compose=$compose)")
        }
        return FeatureInstallResult.Installed(count, complete = !composeExpected || compose)
    }

    companion object {
        const val ID = "host_top_bar_fx"
        const val CHANNEL_STATUS = "host_top_bar_fx.status"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
    }
}
