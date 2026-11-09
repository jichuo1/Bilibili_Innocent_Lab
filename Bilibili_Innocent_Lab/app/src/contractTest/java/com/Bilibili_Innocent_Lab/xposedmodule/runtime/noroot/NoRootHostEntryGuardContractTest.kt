package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动态接收器挂在宿主进程里，不在框架保护范围内：逃逸异常直接杀宿主。
 * 免 Root 在宿主里注册的三个接收器（热应用、存活探测、进程诊断）都必须整体过 HostThreadGuard，
 * 连 Intent 的解析也不能放在守卫之外。
 */
class NoRootHostEntryGuardContractTest {
    private fun read(path: String) = SourceContract.read("runtime/noroot/$path")

    /** onReceive 的第一条语句必须就是守卫入口。 */
    private fun assertGuardedFromTheFirstStatement(source: String, receiverHeader: String, key: String) {
        val body = source.after(receiverHeader).trimStart()
        val guard = "HostThreadGuard.run(\"$key\")"
        assertTrue("$key must wrap the whole onReceive body", body.startsWith(guard) ||
            // 允许守卫入口前只有注释行。
            body.lines().dropWhile { it.trimStart().startsWith("//") }.joinToString("\n").trimStart().startsWith(guard))
    }

    @Test fun theLivenessProbeReceiverRunsUnderTheHostGuard() {
        assertGuardedFromTheFirstStatement(read("HostRuntimeProbeResponder.kt"),
            "override fun onReceive(context: Context, intent: Intent) {", "host.runtime_probe.receive")
    }

    @Test fun theProcessDiagnosticsReceiverRunsUnderTheHostGuardIncludingIntentParsing() {
        assertGuardedFromTheFirstStatement(read("NPatchProcessDiagnostics.kt"),
            "override fun onReceive(context: Context, intent: Intent) {", "npatch.process_diagnostics.receive")
    }

    @Test fun theLiveConfigReceiverStillRunsUnderTheHostGuard() {
        val source = read("NPatchLiveConfig.kt")
        assertTrue(source.contains("HostThreadGuard.run(\"live_config.receive\")"))
    }
}
