package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.material.FrostedMaterialLifecycle
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before

class FrostedMaterialLifecycleTest {
    fun initialBindingCanPrepareBeforeOnStartWithoutDuplicatingTheRequest() {
        val state = FrostedMaterialLifecycle()
        val initial = state.beginRequest()!!
        assertTrue(state.accepts(initial))
        assertTrue(state.resume())
        assertTrue("onStart must not invalidate the onCreate preparation", state.accepts(initial))
    }

    fun stoppedLayoutsCannotScheduleAndAnOldCompletionStaysStaleAfterResume() {
        val state = FrostedMaterialLifecycle()
        val oldRequest = state.beginRequest()!!
        state.stop()
        assertFalse(state.canWork)
        repeat(3) { assertNull("Background layout must not submit new bitmap work", state.beginRequest()) }
        assertFalse(state.accepts(oldRequest))
        assertTrue(state.resume())
        assertFalse("Late result from the prior foreground remains stale", state.accepts(oldRequest))
        val newRequest = state.beginRequest()!!
        assertTrue(state.accepts(newRequest))
    }

    fun closedDrawablesCannotRegisterOrResumeEvenWhenCallbacksArriveLate() {
        val state = FrostedMaterialLifecycle()
        val pending = state.beginRequest()!!
        state.close()
        assertFalse(state.canWork)
        assertFalse(state.accepts(pending))
        assertNull(state.beginRequest())
        assertFalse(state.resume())
        assertFalse(state.canWork)
    }

    fun replacementAndMemoryReleaseRejectEarlierBitmapPairs() {
        val state = FrostedMaterialLifecycle()
        val first = state.beginRequest()!!
        val resized = state.beginRequest()!!
        assertFalse(state.accepts(first))
        assertTrue(state.accepts(resized))
        state.invalidate()
        assertFalse(state.accepts(resized))
        val next = state.beginRequest()!!
        assertTrue(state.accepts(next))
    }

    fun productionWiresRequestDeliveryAndRegistrationToTheSameLifecycle() {
        fun source(path: String) = SourceContract.read("src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/skin/$path")
        val renderer = source("material/FrostedMaterialRenderer.kt")
        val request = renderer.after("private fun requestBackdrop").before("private fun acceptBackdrop")
        assertTrue(request.indexOf("!lifecycle.canWork") < request.indexOf("worker.submit"))
        val delivery = renderer.after("private fun acceptBackdrop").before("internal fun drawSample")
        assertTrue(delivery.indexOf("!lifecycle.accepts(token)") < delivery.indexOf("frame = result"))
        val registration = renderer.after("internal fun register").before("fun notifyPositionChanged")
        assertTrue(registration.indexOf("!lifecycle.canWork") < registration.indexOf("surfaces[view]"))
        // 已交付帧继续由生命周期管理；后台工厂可清理尚未交付的失败分配。
        assertFalse(renderer.before("private data class ModernBackdropFrame").contains(".recycle()"))
        assertFalse(renderer.after("private class ModernSurfaceDrawable").contains(".recycle()"))
        assertTrue(renderer.contains("val recipient = WeakReference(this)"))
        // 休眠的柔光回退引擎必须同样跟随生命周期（Liquid 可能在前台会话中途失败切过来）：
        // 会话把启停发给**全部**引擎，柔光的启停就是 resume/stop。
        val session = source("runtime/ActivitySkinSession.kt")
        assertTrue(session.contains("listOfNotNull(liquidRenderer, materialRenderer)"))
        assertTrue(session.contains("if (!isClosed) engines.forEach(GlowEngine::onStart)"))
        assertTrue(session.contains("if (!isClosed) engines.forEach(GlowEngine::onStop)"))
        assertTrue(renderer.contains("override fun onStart() = resume()"))
        assertTrue(renderer.contains("override fun onStop() = stop()"))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (FrostedMaterialLifecycleTest) -> Unit) {
            val s = FrostedMaterialLifecycleTest()
            block(s)
        }
        runIsolated { s -> s.initialBindingCanPrepareBeforeOnStartWithoutDuplicatingTheRequest() }
        runIsolated { s -> s.stoppedLayoutsCannotScheduleAndAnOldCompletionStaysStaleAfterResume() }
        runIsolated { s -> s.closedDrawablesCannotRegisterOrResumeEvenWhenCallbacksArriveLate() }
        runIsolated { s -> s.replacementAndMemoryReleaseRejectEarlierBitmapPairs() }
        runIsolated { s -> s.productionWiresRequestDeliveryAndRegistrationToTheSameLifecycle() }
    }
}
