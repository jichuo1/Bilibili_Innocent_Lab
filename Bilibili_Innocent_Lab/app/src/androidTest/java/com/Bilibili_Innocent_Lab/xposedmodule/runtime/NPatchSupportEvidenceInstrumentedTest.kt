package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Explicit opt-in: deliberately crashes only the current test host, then cold-starts it. */
@RunWith(AndroidJUnit4::class)
class NPatchSupportEvidenceInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun readsSupportEvidenceWithoutCrashingHost() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("verifySupportRead") == "true")
        val minimum = requireNotNull(args.getString("minimumCrashAt")).toLong()
        val expected = NPatchMode.valueOf(requireNotNull(args.getString("expectedMode")))
        val row = awaitInstalled()
        assertEquals(expected, row.mode)
        assertEquals("AVAILABLE", row.support.crashState)
        assertTrue(row.support.crashes.any { it.at >= minimum && it.moduleFrame })
        assertNotEquals("NOT_QUERIED", row.support.mediaState)
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "\nCurrent host support evidence: ${NPatchProcessCodec.encode(listOf(row))}\n")
        })
    }

    @Test fun controlledCrashIsRecoveredAfterColdStartWithBoundedMediaEvidence() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifySupportCrash") == "true")
        // Instrumentation keeps the settings endpoints alive; a previous manual restart may have
        // exhausted admission before this test process existed. Start the opted-in host afresh.
        shell("am force-stop tv.danmaku.bili")
        shell("am start -n tv.danmaku.bili/.MainActivityV2")
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val before = awaitInstalled()
        val started = System.currentTimeMillis()
        NPatchProcessDiagnostics.requestTestCrash(context)
        // Some ROM crash prompts need an external stylus dismissal; keep the exit assertion strict.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (System.nanoTime() < deadline && shell("pidof tv.danmaku.bili").trim() == before.pid.toString()) {
            dismissKnownHostCrashPrompt()
            Thread.sleep(200)
        }
        assertNotEquals("Original host PID must exit via its exception handler", before.pid.toString(), shell("pidof tv.danmaku.bili").trim())
        shell("am start -n tv.danmaku.bili/.MainActivityV2")
        val after = awaitInstalled()
        assertNotEquals(before.pid, after.pid)
        val fresh = after.support.crashes.filter { it.at >= started }
        assertTrue("No fresh crash persisted", fresh.isNotEmpty())
        assertTrue(fresh.any { it.moduleFrame && it.kind == "STATE" })
        assertEquals("AVAILABLE", after.support.crashState)
        assertNotEquals("NOT_QUERIED", after.support.mediaState)
        assertTrue(NPatchProcessCodec.encode(listOf(after)).length <= NPatchProcessCodec.MAX_CHARS)
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nRecovered host evidence: ${NPatchProcessCodec.encode(listOf(after))}\n") })
    }

    private fun awaitInstalled(): NPatchProcessSnapshot {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        while (System.nanoTime() < deadline) {
            val row = NPatchProcessDiagnostics.query(context, includeMedia = true).singleOrNull { it.processName == "tv.danmaku.bili" }
            if (row?.state == NPatchProcessState.INSTALLED) return row
            Thread.sleep(500)
        }
        error("Current host did not respond with installed state")
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    private fun dismissKnownHostCrashPrompt() {
        val roots = instrumentation.uiAutomation.windows.mapNotNull { it.root }
        val root = roots.firstOrNull { it.findAccessibilityNodeInfosByText("哔哩哔哩已停止运行").isNotEmpty() ||
            it.findAccessibilityNodeInfosByText("三方应用异常").isNotEmpty() &&
            it.findAccessibilityNodeInfosByText("哔哩哔哩").isNotEmpty() } ?: return
        val node = root.findAccessibilityNodeInfosByText("关闭应用").singleOrNull()
            ?: root.findAccessibilityNodeInfosByText("忽略").singleOrNull() ?: return
        val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
        if (!bounds.isEmpty) shell("input stylus tap ${bounds.centerX()} ${bounds.centerY()}")
    }
}
