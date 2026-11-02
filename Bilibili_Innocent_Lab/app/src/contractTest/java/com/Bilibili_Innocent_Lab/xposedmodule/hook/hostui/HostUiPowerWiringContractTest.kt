package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 省电开关只有一个入口：HookEntry 在创建任何宿主 UI 作用域之前把设置读成进程快照，
 * 两个作用域的省电路径都从这份快照取默认值；关闭开关必须回到完整刷新。
 * 设备上的功耗对照仍可通过构造参数独立控制两条路径。
 */
class HostUiPowerWiringContractTest {
    private fun read(path: String) = SourceContract.read("hook/hostui/$path")

    @Test fun theSwitchIsReadIntoTheProcessPolicyBeforeAnyHostUiScopeExists() {
        val entry = SourceContract.read("hook/HookEntry.kt")
        val configure = entry.indexOf("HostUiPowerPolicy.configure(prefs.getBoolean(FeaturePreferences.HOST_UI_POWER_SAVING, HostUiPowerPolicy.DEFAULT_ENABLED))")
        assertTrue("HookEntry must configure the policy from the host config", configure > 0)
        assertTrue(configure < entry.indexOf("val topBarFxInstaller = createTopBarFx()"))
        assertTrue(configure < entry.indexOf("val bottomBarFxInstaller = createBottomBarFx()"))
    }

    @Test fun bothScopesTakeTheirPowerPathsFromThePolicyByDefault() {
        val cards = read("cards/HostCardMaterialScope.kt")
        assertTrue(cards.contains("reuseGpuFrames: Boolean = HostUiPowerPolicy.current.retainsGpuFrames"))
        assertTrue(cards.contains("optimizeCardRendering: Boolean = HostUiPowerPolicy.current.shortcutsCardRendering"))
        assertTrue(read("common/HostSurfaceScope.kt").contains("reuseGpuFrames: Boolean = HostUiPowerPolicy.current.retainsGpuFrames"))
    }

    @Test fun theRetentionDecisionAlwaysGoesThroughThePolicyAndTheModernSdkConstant() {
        for (path in listOf("cards/HostCardMaterialScope.kt", "common/HostSurfaceScope.kt")) {
            val source = read(path)
            assertTrue("$path must decide through HostUiPowerPolicy", source.contains("HostUiPowerPolicy.canRetainGpuFrame(reuseGpuFrames, AndroidVersion.code)"))
            assertFalse("$path must not hard-code the SDK threshold", source.contains("isAtLeast(32)"))
        }
    }

    /** 预绘制里每次内容变化都会走到这里；引擎状态改由监听器事件送达，不再逐帧取诊断快照。 */
    @Test fun theCardScopeLearnsTheBackendFromEngineEventsInsteadOfPollingDiagnosticsWhileDrawing() {
        val cards = read("cards/HostCardMaterialScope.kt")
        assertTrue(cards.contains("session?.setListener {"))
        val refresh = cards.after("    fun refresh(night: Boolean) {").before("    private fun materialOptions()")
        assertFalse("refresh runs in pre-draw and must not take diagnostics snapshots", refresh.contains("diagnostics()"))
    }

    @Test fun theTrailingFlushRunsThroughTheHostGuardAndCanBeCancelled() {
        val cards = read("cards/HostCardMaterialScope.kt")
        assertTrue(cards.contains("""HostThreadGuard.runnable("host.card.material.refresh") { onContentRefreshTimer() }"""))
        assertFalse("the delayed flush must be the guarded instance", cards.contains("object : Runnable"))
        assertTrue(cards.contains("owner.removeCallbacks(contentRefresh)"))
        val close = cards.after("    override fun close() {").before("paints.keys.toList().forEach { it.close() }")
        assertTrue("close must drop a pending flush", close.contains("cancelContentRefresh()"))
    }

    @Test fun theSurfaceScopeKeepsTheDiagnosticsReadOffTheEveryFramePath() {
        val surface = read("common/HostSurfaceScope.kt")
        val predraw = surface.after("private val backdropPreDraw").before("private val focusListener")
        assertFalse("the pre-draw listener must not read diagnostics itself", predraw.contains("diagnostics()"))
        assertTrue(surface.contains("now - gpuCheckedAt >= gpuRecheckMs"))
    }
}
