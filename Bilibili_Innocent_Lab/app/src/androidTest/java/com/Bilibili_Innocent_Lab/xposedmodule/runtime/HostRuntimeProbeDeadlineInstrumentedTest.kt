package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class HostRuntimeProbeDeadlineInstrumentedTest {
    @Test fun expiredRepliesSettleCallbacksOnMainWithoutTheDelayedTimeoutTask() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val main = Handler(Looper.getMainLooper())
        for (verify in listOf(false, true)) for (installed in listOf(false, true)) {
            var time = 0L
            val session = HostRuntimeProbeSession({ time }) { main.post(it) }
            val done = CountDownLatch(1)
            val count = AtomicInteger()
            val onMain = AtomicBoolean()
            val status = AtomicReference<HostRuntimeProbeStatus>()
            session.request("request", verify, 1_500) {
                onMain.set(Looper.myLooper() == Looper.getMainLooper())
                status.set(it); count.incrementAndGet(); done.countDown()
            }
            // 只推进生产状态机的时钟，故意不执行计时任务；不阻塞系统或应用主线程。
            time = 1_501
            assertEquals(HostRuntimeProbeTracker.ReplyResult.REJECTED, session.onReply("request", installed))
            assertTrue(done.await(3, TimeUnit.SECONDS))
            assertTrue(onMain.get())
            assertEquals(if (verify) HostRuntimeProbeStatus.NOT_RUNNING else HostRuntimeProbeStatus.UNCONFIRMED, status.get())
            assertFalse(session.hasPendingRequest)
            assertFalse(session.onTimeout("request"))
            assertEquals(HostRuntimeProbeTracker.ReplyResult.REJECTED, session.onReply("request", installed))
            instrumentation.runOnMainSync {}
            assertEquals(1, count.get())
        }
    }

    @Test fun realHostReplyStillConfirmsAfterAColdStart() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyHostProbe") == "true")
        val app = instrumentation.targetContext.applicationContext
        fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
            ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { input -> input.readText() }
        }
        shell("am force-stop tv.danmaku.bili")
        shell("am start -n tv.danmaku.bili/.MainActivityV2")
        val deadline = SystemClock.elapsedRealtime() + 40_000
        var installed = false
        while (!installed && SystemClock.elapsedRealtime() < deadline) {
            val done = CountDownLatch(1)
            val response = AtomicReference<HostRuntimeDiagnosticsQueryClient.Result>()
            HostRuntimeDiagnosticsQueryClient.query(app) { response.set(it); done.countDown() }
            if (done.await(4, TimeUnit.SECONDS)) {
                installed = response.get()?.let { it.status == HostRuntimeDiagnosticsQueryClient.Status.READY &&
                    it.snapshot?.bootstrap?.installChainState == HostInstallChainState.COMPLETED } == true
            }
            if (!installed) Thread.sleep(200)
        }
        assertTrue("Host did not complete current Hook installation", installed)
        val done = CountDownLatch(1)
        val status = AtomicReference<HostRuntimeProbeStatus>()
        HostRuntimeProbeClient.probeAsync(app, verify = true) { status.set(it); done.countDown() }
        assertTrue("Real host probe did not settle", done.await(18, TimeUnit.SECONDS))
        assertEquals(HostRuntimeProbeStatus.CONFIRMED, status.get())
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "\nCold host installed current Hooks; real signed cross-process probe CONFIRMED\n")
        })
    }
}
