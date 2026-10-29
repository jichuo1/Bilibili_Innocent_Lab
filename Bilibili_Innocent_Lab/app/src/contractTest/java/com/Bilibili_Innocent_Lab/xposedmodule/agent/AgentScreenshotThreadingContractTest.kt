package com.Bilibili_Innocent_Lab.xposedmodule.agent

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import com.Bilibili_Innocent_Lab.xposedmodule.contract.afterLast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 性能报告 P1-05：inspect_screen 的像素后处理不得在主线程回调里执行。
 * 主线程回调只做准入与交出；ARGB 复制、裁剪、缩放、JPEG、Base64 在有界 worker 里，释放责任唯一。
 */
class AgentScreenshotThreadingContractTest {
    private val service = SourceContract.read("agent/AgentAccessibilityService.kt")
    private val callback = service.after("override fun onSuccess(screenshot: ScreenshotResult)").before("/** worker 线程")
    private val encode = service.after("private fun encodeScreenshot(").before("/** 主线程：核对任务仍有效")
    private val deliver = service.after("private fun deliverScreenshot(").before("companion object")

    @Test fun mainThreadCallbackDoesNoPixelWork() {
        for (heavy in listOf("hardware.copy(", ".copy(Bitmap.Config", "createBitmap(", "createScaledBitmap(", ".compress(",
            "Base64.encodeToString", "wrapHardwareBuffer", "ByteArrayOutputStream")) {
            assertFalse("主线程回调不应出现 $heavy", callback.contains(heavy))
        }
        assertTrue(callback.contains("screenshotWorker.trySubmit"))
    }

    @Test fun pixelWorkLivesInTheWorkerFunctionAndNeverTouchesViewsOrNodes() {
        for (heavy in listOf("wrapHardwareBuffer", ".copy(Bitmap.Config.ARGB_8888", "createBitmap(", "createScaledBitmap(",
            ".compress(Bitmap.CompressFormat.JPEG", "Base64.encodeToString")) {
            assertTrue("worker 函数应包含 $heavy", encode.contains(heavy))
        }
        for (forbidden in listOf("scan()", "windows", "hostRoot", "getChild", "performAction", "visualSnapshot", "snapshot")) {
            assertFalse("worker 函数不应访问 $forbidden", encode.contains(forbidden))
        }
    }

    @Test fun hardwareBufferHasExactlyOneOwnerOnEveryBranch() {
        // 交出之前归回调（含拒绝、满载、异常）；交出之后归 worker 函数，在 finally 里关闭。
        assertTrue(callback.contains("var handedOff = false"))
        assertTrue(callback.contains("finally { if (!handedOff) buffer.close() }"))
        assertTrue(callback.contains("check(handedOff) { \"screen_busy\" }"))
        assertEquals(1, Regex("""buffer\.close\(\)""").findAll(encode).count())
        assertTrue(encode.afterLast("finally {").contains("buffer.close()"))
        assertTrue(encode.indexOf("if (result.isDone) return") in 0 until encode.indexOf("wrapHardwareBuffer"))
    }

    @Test fun staleResultsAreDroppedOnTheMainThread() {
        assertTrue(callback.contains("main.post { deliverScreenshot("))
        assertTrue(deliver.contains("if (result.isDone) return"))
        assertTrue(deliver.contains("check(snapshot === shot)"))
        assertTrue(deliver.indexOf("admit()") < deliver.indexOf("visualSnapshot = shot.id"))
    }

    @Test fun workerNeverFallsBackToTheCallerThread() {
        val worker = SourceContract.read("agent/AgentScreenshotWorker.kt")
        assertFalse(worker.contains("CallerRunsPolicy"))
        assertTrue(worker.contains("ThreadPoolExecutor.AbortPolicy()"))
        assertTrue(worker.contains("ArrayBlockingQueue<Runnable>(1)"))
    }
}
