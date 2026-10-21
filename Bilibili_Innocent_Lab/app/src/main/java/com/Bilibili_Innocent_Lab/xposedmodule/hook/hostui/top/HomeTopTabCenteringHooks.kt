package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top

import android.view.ViewGroup
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome.HostComposeTopTabCentering
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome.HostHomeLifecycleHooks
import java.util.concurrent.atomic.AtomicBoolean

/** Automatic tab layout refinement; material/glow preferences do not control tab alignment. */
internal class HomeTopTabCenteringHooks(
    private val viewPoints: VersionAdapter.HomeTopBarPoints?,
    chromePoints: Map<String, VersionAdapter.HookPoint>
) {
    @Volatile private var chromePoints = relevant(chromePoints)
    private val nativeApplied = AtomicBoolean(false)
    private val composeApplied = AtomicBoolean(false)
    private val errorReported = AtomicBoolean(false)

    fun updateChromePoints(points: Map<String, VersionAdapter.HookPoint>): Boolean {
        val incoming = relevant(points)
        if (incoming.all { (family, point) -> chromePoints[family] == point }) return false
        chromePoints = chromePoints + incoming
        return true
    }

    fun install(environment: HookEnvironment) {
        if (environment.processName != "tv.danmaku.bili") return
        fun applied(path: String, first: AtomicBoolean) {
            if (first.compareAndSet(false, true)) {
                environment.reportStatus(CHANNEL_STATUS, "applied:path=$path")
                environment.logInfo("home_top_tabs_centering_$path", "[BIL] 首页少量标签已按整个顶栏居中(path=$path)")
            }
        }
        fun failed(error: Throwable) {
            if (errorReported.compareAndSet(false, true)) {
                environment.reportStatus(CHANNEL_STATUS, "error:layout")
                environment.logError("home_top_tabs_centering_error", "[BIL] 首页标签居中失败: $error")
            }
        }
        val viewPoint = viewPoints?.baseOnViewCreated
        fun attach(root: ViewGroup, fragment: Any?) {
            runCatching {
                val hierarchy = HostTopBarFxController.locateTopBarHierarchy(root, fragment, viewPoint?.viewField)
                    ?: return@runCatching
                NativeTopTabsCentering.attach(hierarchy.topBarDock, hierarchy.tabContainer) {
                    applied("native", nativeApplied)
                }
            }.onFailure(::failed)
        }
        var native = 0
        if (viewPoint != null) runCatching {
            environment.registrar.adapted("home.top_tabs.centering.view_created", viewPoint) {
                after { if (!hasThrowable) (argOrNull(0) as? ViewGroup)?.let { attach(it, instance) } }
            }
        }.onSuccess { native++ }.onFailure(::failed)
        native += HostHomeLifecycleHooks.install(environment, "home.top_tabs.centering") { root, fragment -> attach(root, fragment) }
        val compose = HostComposeTopTabCentering.install(environment, chromePoints,
            onError = ::failed, onApplied = { applied("compose", composeApplied) })
        if (!nativeApplied.get() && !composeApplied.get()) {
            environment.reportStatus(CHANNEL_STATUS, "installed:legacy=$native,compose=$compose")
        }
    }

    private fun relevant(points: Map<String, VersionAdapter.HookPoint>) = points.filterKeys {
        it == "top" || it == "top_content" || it == "top_right_actions"
    }

    companion object {
        const val CHANNEL_STATUS = "home_top_tabs_centering.status"
    }
}
