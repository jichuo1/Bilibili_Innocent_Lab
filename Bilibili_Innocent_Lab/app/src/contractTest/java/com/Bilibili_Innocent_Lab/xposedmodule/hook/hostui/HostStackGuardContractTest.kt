package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 挂在宿主 View/Handler 上的入口不在框架 PROTECTIVE 的保护范围内，逃逸异常直接杀宿主进程。
 * 这里守住三条：入口过守卫、预绘制故障开放、宿主自身的异常不被守卫吞掉。
 * 守卫语义本身由 HostThreadGuardTest 覆盖。
 */
class HostStackGuardContractTest {
    private fun read(path: String) = SourceContract.read("hook/hostui/$path")

    private fun assertGuarded(source: String, vararg keys: String) {
        for (key in keys) assertTrue("Unguarded host entry: $key", source.contains("\"$key\""))
    }

    @Test fun preDrawEntriesFailOpenSoTheHostKeepsDrawing() {
        val entries = mapOf(
            "bottom/HostBottomBarDockLayer.kt" to "host.bottom.dock.predraw",
            "top/HostTopBarFxController.kt" to "host.top.material.predraw",
            "top/HostTopFusionBinding.kt" to "host.top.fusion.predraw",
            "top/HostTopIslandBinding.kt" to "host.top.island.predraw",
            "cards/HostCardMaterialScope.kt" to "host.card.material.predraw",
            "cards/HostVideoCardStyle.kt" to "host.card.list.predraw",
            "common/HostSurfaceScope.kt" to "host.surface.backdrop.predraw"
        )
        for ((path, key) in entries) {
            assertTrue("$path must return true on failure", read(path).contains("HostThreadGuard.call(\"$key\", true)"))
        }
    }

    /** 融合带的 false 是“修复 padding 时不画错位帧”，不能被守卫改写成恒 true。 */
    @Test fun fusionPreDrawKeepsItsRealResult() {
        assertTrue(read("top/HostTopFusionBinding.kt").contains("""HostThreadGuard.call("host.top.fusion.predraw", true) { sync() }"""))
    }

    @Test fun barEntriesAreGuardedAndKeepTheirRemovableIdentity() {
        val bottom = read("bottom/HostBottomBarFxController.kt")
        assertGuarded(bottom, "host.bottom.sanitize", "host.bottom.hierarchy")
        assertTrue(bottom.contains("val sanitizeRunnable = Runnable {"))
        assertTrue(bottom.contains("tabHost.removeCallbacks(sanitizeRunnable)"))
        val top = read("top/HostTopBarFxController.kt")
        assertGuarded(top, "host.top.sanitize", "host.top.attach", "host.top.detach", "host.top.page_padding")
        assertTrue(top.contains("val sanitizeRunnable = Runnable {"))
        assertTrue(top.contains("topBarDock.removeCallbacks(sanitizeRunnable)"))
        assertFalse(top.contains("viewPager.post {"))
    }

    /** 失败的一帧不再续约；不清 animating 的话岛会卡在半途，之后的 animateTo 也不再续帧。 */
    @Test fun islandFramesAreGuardedAndAFailedSpringFrameReleasesTheAnimation() {
        val island = read("top/HostTopIslandBinding.kt")
        assertGuarded(island, "host.top.island.feedback", "host.top.island.frame")
        assertTrue(island.contains("if (!continued) animating = false"))
    }

    @Test fun backgroundWorkerAndPageListenersAreGuarded() {
        val controller = read("background/HostBackgroundController.kt")
        assertTrue(controller.contains("worker.execute(HostThreadGuard.runnable("))
        assertTrue(controller.contains("main.post(HostThreadGuard.runnable("))
        assertFalse(controller.contains("worker.execute {"))
        assertFalse(controller.contains("main.post {"))
        // 加载器抛异常与“加载不到”同路，用户侧仍能收到回退到柔光的通知。
        assertTrue(controller.contains("""HostThreadGuard.call<Bitmap?>("host.background.load", null)"""))
        assertGuarded(read("background/HostBackgroundPages.kt"), "host.background.page.attach", "host.background.page.detach")
        assertGuarded(read("common/HostSurfaceScope.kt"), "host.surface.focus")
    }

    @Test fun replyPanelMessagesGoThroughAStructuralGuard() {
        val panel = SourceContract.read("ui/overlay/ReplyTopologyPanelController.kt")
        assertTrue(panel.contains("""HostThreadGuard.run("reply_topology.panel_message") { super.dispatchMessage(msg) }"""))
        assertFalse(panel.contains("private val mainHandler = Handler("))
    }

    /** 底栏松手时替宿主点击 tab：那段是宿主业务，守卫只能兜手势状态，见 HostThreadGuard 的纪律。 */
    @Test fun hostClickFailuresPassThroughTheBottomBarGuard() {
        val controller = read("bottom/HostBottomBarFxController.kt")
        assertFalse("Listeners must not bypass the guard", controller.contains("handleTouch("))
        assertEquals(3, Regex("""dockLayer\.dispatchTouch\(""").findAll(controller).count())

        val dock = read("bottom/HostBottomBarDockLayer.kt")
        assertTrue(dock.contains("private fun handleTouch("))
        val dispatch = dock.after("fun dispatchTouch(").before("/** 把点击交还宿主")
        val guard = dispatch.indexOf("""HostThreadGuard.call("host.bottom.touch", false) { handleTouch(event, tabIndex) }""")
        assertTrue(guard >= 0)
        assertTrue("Host failures are rethrown outside the guard", dispatch.indexOf("hostClicks.rethrow()") > guard)

        val click = dock.after("private fun clickTab(").before("private fun detectSelectedTab(")
        val publish = click.indexOf("hostClicks.host {")
        val tab = click.indexOf("hostClicks.host {", publish + 1)
        assertTrue(publish >= 0 && tab > publish)
        assertTrue(click.indexOf("listener.onTouch(publish, event)") > publish)
        assertTrue(click.indexOf("tab.performClick()") > tab)
        assertTrue(click.indexOf("child.performClick()") > tab)
    }

    /** 守卫里再套 runCatching，守卫就永远看不到失败，计数和日志全部失效。 */
    @Test fun chromePainterGuardsSeeFailuresAndKeepTheDegradePath() {
        val painter = read("chrome/HostComposeChromePainter.kt")
        assertTrue(painter.contains("try { block() } catch (error: Throwable) { failure(error); throw error }"))
        assertGuarded(painter, "host.chrome.wake", "host.chrome.backdrop", "host.chrome.layout",
            "host.chrome.scroll", "host.chrome.attach", "host.chrome.detach")
        assertFalse(Regex("""HostThreadGuard\.run\("[^"]+"\) \{\s*runCatching""").containsMatchIn(painter))
        assertFalse(Regex("""guarded\("[^"]+"\) \{\s*runCatching""").containsMatchIn(painter))
    }
}
