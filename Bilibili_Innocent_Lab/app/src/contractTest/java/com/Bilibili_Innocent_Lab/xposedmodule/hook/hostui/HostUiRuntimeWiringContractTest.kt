package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 宿主 UI 运行时接线护栏：
 * - 省电开关在任何宿主 UI 作用域创建前写入进程快照，渲染保留判定走策略与现代 SDK 常量，预绘制不读诊断快照；
 * - 关注页每次 update 只建立一次附着行索引，各遍复用并保持 surface 预算。
 */
class HostUiRuntimeWiringContractTest {

    @Test
    fun `host ui power saving reads process snapshot early and keeps diagnostics off draw path`() {
        val entry = SourceContract.read("hook/HookEntry.kt")
        val configure = entry.indexOf("HostUiPowerPolicy.configure(prefs.getBoolean(FeaturePreferences.HOST_UI_POWER_SAVING, HostUiPowerPolicy.DEFAULT_ENABLED))")
        assertTrue("HookEntry must configure the policy from the host config", configure > 0)
        assertTrue(configure < entry.indexOf("val topBarFxInstaller = createTopBarFx()"))
        assertTrue(configure < entry.indexOf("val bottomBarFxInstaller = createBottomBarFx()"))

        val cards = SourceContract.read("hook/hostui/cards/HostCardMaterialScope.kt")
        val surface = SourceContract.read("hook/hostui/common/HostSurfaceScope.kt")
        assertTrue(cards.contains("reuseGpuFrames: Boolean = HostUiPowerPolicy.current.retainsGpuFrames"))
        assertTrue(cards.contains("optimizeCardRendering: Boolean = HostUiPowerPolicy.current.shortcutsCardRendering"))
        assertTrue(surface.contains("reuseGpuFrames: Boolean = HostUiPowerPolicy.current.retainsGpuFrames"))

        for ((path, source) in listOf(
            "cards/HostCardMaterialScope.kt" to cards,
            "common/HostSurfaceScope.kt" to surface
        )) {
            assertTrue("$path must decide through HostUiPowerPolicy", source.contains("HostUiPowerPolicy.canRetainGpuFrame(reuseGpuFrames, AndroidVersion.code)"))
            assertFalse("$path must not hard-code the SDK threshold", source.contains("isAtLeast(32)"))
        }

        // 预绘制里每次内容变化都会走到这里；引擎状态改由监听器事件送达，不再逐帧取诊断快照。
        assertTrue(cards.contains("session?.setListener {"))
        val refresh = cards.after("    fun refresh(night: Boolean) {").before("    private fun materialOptions()")
        assertFalse("refresh runs in pre-draw and must not take diagnostics snapshots", refresh.contains("diagnostics()"))

        assertTrue(cards.contains("""HostThreadGuard.runnable("host.card.material.refresh") { onContentRefreshTimer() }"""))
        assertFalse("the delayed flush must be the guarded instance", cards.contains("object : Runnable"))
        assertTrue(cards.contains("owner.removeCallbacks(contentRefresh)"))
        val close = cards.after("    override fun close() {").before("paints.keys.toList().forEach { it.close() }")
        assertTrue("close must drop a pending flush", close.contains("cancelContentRefresh()"))

        val predraw = surface.after("private val backdropPreDraw").before("private val focusListener")
        assertFalse("the pre-draw listener must not read diagnostics itself", predraw.contains("diagnostics()"))
        assertTrue(surface.contains("now - gpuCheckedAt >= gpuRecheckMs"))
    }

    @Test
    fun `follow feed update builds row index once, preserves budgets, and falls back on stale snapshots`() {
        val update = SourceContract.read("hook/hostui/follow/FollowFeedController.kt")
            .after("fun update() {").before("private fun updateBottomPadding()")

        assertEquals(1, Regex("""updateIndex\.rebuild\(""").findAll(update).count())
        assertEquals(
            "the controller map is consulted once per child for the snapshot, plus the decoration pass's RowState",
            2,
            Regex("""rows\[""").findAll(update).count()
        )
        assertFalse("no per-pass key allocation of the reference identity", update.contains("referenceKey(it)") || update.contains("::referenceKey"))
        // 清理离屏引用不再为每个存量引用重扫所有子 View。
        val stale = update.after("val staleReferences = references.iterator()").before("for (i in 0 until list.childCount)")
        assertTrue(stale.contains("updateIndex.hasReference(entry.key)"))
        assertFalse(stale.contains("list.getChildAt"))
        assertFalse(stale.contains("none {"))

        assertTrue(update.contains("panel.place(cardOptions, 0)"))
        assertTrue(update.contains("panel.place(referenceOptions, FollowFeedStyle.REFERENCE_INSET_DP)"))
        assertTrue(update.contains("FollowFeedStyle.MAX_CARD_SURFACES"))
        assertTrue(update.contains("generation++"))
        assertTrue(update.contains("updateBottomPadding()"))

        assertTrue(update.contains("if (state.row === updateIndex.rowAt(i)) updateIndex.keyAt(i) else state.row?.referenceKey()"))
    }
}
