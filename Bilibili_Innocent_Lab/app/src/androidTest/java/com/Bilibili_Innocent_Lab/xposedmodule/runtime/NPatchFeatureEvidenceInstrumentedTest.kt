package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.util.Log
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchMode
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchRuntimeProfiles
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationAuthorityStore
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 手动触发业务后显式运行：只查询当前进程证据，不启动宿主、改配置或模拟业务成功。
 * 参数 applied / observed / quiet 为公开能力 ID；quiet 只证明没有回调，须与独立 UI 行为证据配对。
 */
@RunWith(AndroidJUnit4::class)
class NPatchFeatureEvidenceInstrumentedTest {
    @Test fun currentEmbeddedHostReportsExpectedFeatureStages() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun report(message: String) {
            Log.i("BilibiliInnocentLab", message)
            // ROM 的小日志缓冲可能覆盖 logcat；同一份有界公开证据也写入本次测试输出。
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\n$message\n") })
        }
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        fun ids(key: String): Set<String> = args.getString(key).orEmpty()
            .split(',').map(String::trim).filter(String::isNotEmpty).toSet()
        val applied = ids("applied")
        val observed = ids("observed")
        val quiet = ids("quiet")
        val selected = applied + observed + quiet
        assumeTrue("Explicit feature expectations are required", selected.isNotEmpty())
        assertTrue("Unknown feature ID", selected.all { it in HostRuntimeDiagnosticsCodec.allowedFeatureIds })
        assertTrue("Conflicting feature expectations", quiet.intersect(applied + observed).isEmpty())
        val expectedMode = NPatchMode.valueOf(args.getString("expectedMode") ?: "EMBEDDED")
        assertEquals(expectedMode, NPatchRuntimeProfiles.read(context).mode)
        val current = requireNotNull(PublicationAuthorityStore.current(context))
        val done = CountDownLatch(1)
        val response = AtomicReference<HostRuntimeDiagnosticsQueryClient.Result>()
        HostRuntimeDiagnosticsQueryClient.query(context) { response.set(it); done.countDown() }
        assertTrue("Current host query timed out", done.await(5, TimeUnit.SECONDS))
        val result = response.get()
        assertEquals("Current host query: ${result.failure}", HostRuntimeDiagnosticsQueryClient.Status.READY, result.status)
        val snapshot = requireNotNull(result.snapshot)
        assertEquals(HostRuntimeDiagnosticsCodec.TARGET_PACKAGE, snapshot.processName)
        assertEquals(HostConfigState.ACCEPTED, snapshot.bootstrap.configState)
        assertEquals(HostInstallChainState.COMPLETED, snapshot.bootstrap.installChainState)
        assertEquals(HostAdmissionContract.DIRECT, snapshot.bootstrap.configSource)
        assertEquals(current.identity, snapshot.bootstrap.admission)
        assertEquals("Configuration changed during query", current.identity,
            PublicationAuthorityStore.current(context)?.identity)
        selected.sorted().forEach { id ->
            val row = snapshot.features.singleOrNull { it.featureId == id }
            if (row == null) {
                assertTrue("No current evidence for $id", id in quiet)
                // The protocol omits some disabled leaves. Absence proves no reported callback,
                // never that a setting is disabled or that its business behavior passed.
                report("NPatch feature acceptance: id=$id report=absent callbacks=none")
                return@forEach
            }
            assertFalse("Runtime error for $id", row.runtimeError)
            if (id in applied) assertTrue("No applied callback for $id", row.appliedCount > 0)
            if (id in observed) assertTrue("No observed callback for $id", row.observedCount > 0)
            if (id in quiet) {
                assertEquals(0L, row.observedCount)
                assertEquals(0L, row.appliedCount)
            }
            // Only public capability IDs, bounded counts and enum states leave this boundary.
            report("NPatch feature acceptance: id=$id install=${row.installState} " +
                "observed=${row.observedCount} applied=${row.appliedCount} hooks=${row.installedHookCount}")
        }
    }
}
