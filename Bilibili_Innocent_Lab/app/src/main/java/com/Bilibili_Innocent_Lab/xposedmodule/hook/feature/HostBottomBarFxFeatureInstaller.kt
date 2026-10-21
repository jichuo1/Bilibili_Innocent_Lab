package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.ViewGroup
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.bottom.HostBottomBarFxConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.bottom.HostBottomBarFxController
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome.HostComposeChromeHooks
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome.HomeChromeSemanticsPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome.HostHomeLifecycleHooks
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostChromeAppearance
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostGlassRenderer
import java.util.concurrent.atomic.AtomicBoolean

internal class HostBottomBarFxFeatureInstaller(
    private val liquidGlass: Boolean,
    private val touchGlow: Boolean,
    private val points: VersionAdapter.BottomBarPoints?,
    private val compact: Boolean = false,
    private val iconOnly: Boolean = false,
    private val appearance: HostChromeAppearance = HostChromeAppearance(renderer = HostGlassRenderer.SOFTWARE),
    chromePoints: Map<String, HookPoint> = emptyMap(),
    semanticsPoint: HomeChromeSemanticsPoint? = null
) : FeatureInstaller {
    override val id: String = ID
    private val firstHit = AtomicBoolean(false)
    private val firstObserved = AtomicBoolean(false)
    private val firstError = AtomicBoolean(false)
    private val firstMissing = AtomicBoolean(false)
    @Volatile private var chromePoints = chromePoints.filterKeys { it in FAMILIES }
    @Volatile private var chromeSemantics = semanticsPoint

    /** Background adaptation can add points without resetting evidence captured by live callbacks. */
    fun updateChromePoints(points: Map<String, HookPoint>, semanticsPoint: HomeChromeSemanticsPoint? = null): Boolean {
        val relevant = points.filterKeys { it in FAMILIES }
        val semanticsChanged = semanticsPoint != null && semanticsPoint != chromeSemantics
        if (!semanticsChanged && relevant.all { (family, point) -> chromePoints[family] == point }) return false
        chromePoints = chromePoints + relevant
        if (semanticsChanged) chromeSemantics = semanticsPoint
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
                environment.logInfo("host_bottom_bar_fx_hit", "[BIL] 宿主底栏视觉增强已装配(liquidGlass=$liquidGlass, touchGlow=$touchGlow, appearance=$appearance)")
            }
        }
        fun failed(error: Throwable) {
            if (firstError.compareAndSet(false, true)) {
                environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ERROR)
                environment.logError("host_bottom_bar_fx_error", "[BIL] 宿主底栏视觉增强失败: $error")
            }
        }
        val config = HostBottomBarFxConfig(liquidGlass, touchGlow, liquidGlass && compact, liquidGlass && iconOnly, appearance)
        fun attach(host: ViewGroup) {
            observed()
            HostBottomBarFxController.attach(host, config, onApplied = ::applied, onMissing = {
                if (!firstHit.get() && firstMissing.compareAndSet(false, true)) environment.reportStatus(CHANNEL_STATUS, "observed:missing-laid-out-bottom-bar")
            }, onError = ::failed)
        }
        var count = 0
        points?.bindTabMethod?.let { point ->
            runCatching {
                environment.registrar.adapted("host_bottom_bar_fx.bind", point) {
                    after { if (!hasThrowable) (instance as? ViewGroup)?.let(::attach) }
                }
            }.onSuccess { count++ }.onFailure(::failed)
            val owner = environment.classLoader?.let { KavaMemberLookup.classOrNull(it, point.className) }
            if (owner != null && ViewGroup::class.java.isAssignableFrom(owner)) {
                count += HostHomeLifecycleHooks.install(environment, ID) { root, _ ->
                    HostHomeLifecycleHooks.find(root, owner)?.let(::attach)
                }
            }
        }
        val compose = HostComposeChromeHooks.install(environment, "bottom", chromePoints, liquidGlass, touchGlow,
            appearance, config, ::observed, ::applied, ::failed, semanticsPoint = chromeSemantics)
        if (compose) count++
        if (count == 0) {
            environment.reportStatus(CHANNEL_STATUS, "missing:missing-bottom-bar-points")
            return FeatureInstallResult.Skipped("missing-bottom-bar-points")
        }
        val composeExpected = environment.classLoader?.let {
            KavaMemberLookup.classOrNull(it, "tv.danmaku.bili.khome.widget.topbar.HomeTopTabKt") != null
        } == true
        if (!firstHit.get()) {
            environment.reportStatus(CHANNEL_STATUS, "installed:legacy=${count - if (compose) 1 else 0},compose=$compose")
            environment.logInfo("host_bottom_bar_fx_ok", "[BIL] 宿主底栏入口已注册，等待实际装配(legacy=${count - if (compose) 1 else 0},compose=$compose)")
        }
        return FeatureInstallResult.Installed(count, complete = !composeExpected || compose)
    }

    companion object {
        private val FAMILIES = setOf("bottom", "bottom_item", "bottom_content")
        const val ID = "host_bottom_bar_fx"
        const val CHANNEL_STATUS = "host_bottom_bar_fx.status"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
    }
}
