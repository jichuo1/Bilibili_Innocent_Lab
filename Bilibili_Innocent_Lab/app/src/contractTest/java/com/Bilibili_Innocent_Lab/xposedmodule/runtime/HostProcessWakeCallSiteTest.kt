package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守住"谁有资格把哔哩哔哩拉起来"。
 *
 * 保底唤起是**用户看得见的副作用**（后台多出一个宿主进程），只有用户主动点开某个
 * 需要宿主扫描结果的面板时才有正当性。两条退化方向都得拦：
 *
 * 1. `ReceiptQueryTransport` 还服务诊断通道，而它的调用方里有 `TelemetryCoordinator`
 *    （自动 24h 一次 + 缺回执排 15 分钟本地重试）和启动时的激活卡检查——
 *    **遥测绝不能顺手启动宿主**；
 * 2. 扫描快照客户端本身也有静默调用方（「自动确认新增屏蔽标签」在模块前台时的那次
 *    后台合并），它必须显式传 `wakeHost = false`。
 *
 * 两个开关都默认安全方向之外的那一侧要靠人写对，所以按源码文本钉住。
 */
class HostProcessWakeCallSiteTest {

    private fun dirOf(relative: String): File =
        sequenceOf(File(relative), File("app/$relative")).firstOrNull(File::isDirectory)
            ?: error("找不到源码目录: $relative")

    private val runtimeDir: File by lazy {
        dirOf("src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/runtime")
    }

    private fun sources(): List<File> = listOf(
        runtimeDir,
        dirOf("src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/activity"),
        dirOf("src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/telemetry")
    ).flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") } }

    fun theScanActuallySeesTheSources() {
        val names = sources().map(File::getName)
        assertTrue("没扫到源码，护栏会静默通过", names.size >= 30)
        listOf("ReceiptQueryTransport.kt", "MineComponentSnapshotQueryClient.kt",
            "HostRuntimeDiagnosticsQueryClient.kt", "TelemetryCoordinator.kt",
            "RecommendationBlocklistDialogs.kt").forEach {
            assertTrue("$it 不在扫描范围内", it in names)
        }
    }

    /** 唯一把 `allowWake` 打开的地方只能是扫描快照客户端，而且要转发自己的参数。 */
    fun onlyTheScanSnapshotClientMayEnableTheTransportWake() {
        val client = File(runtimeDir, "MineComponentSnapshotQueryClient.kt").readText()
        assertTrue("扫描客户端应当把自己的开关转发给 transport",
            client.contains("allowWake = wakeHost"))
        val offenders = sources().filter { it.readText().contains("allowWake = true") }
            .map(File::getName)
        assertEquals("唤起宿主只准由用户主动打开的扫描面板触发", emptyList<String>(), offenders)
    }

    /** 诊断通道（遥测、激活卡检查）必须停在默认值上。 */
    fun theDiagnosticsChannelStaysOnTheDefault() {
        val diagnostics = File(runtimeDir, "HostRuntimeDiagnosticsQueryClient.kt").readText()
        assertTrue("诊断通道仍应调用同一条 transport",
            diagnostics.contains("ReceiptQueryTransport.query("))
        assertFalse(diagnostics.contains("allowWake"))
    }

    /**
     * 扫描客户端的静默调用方必须显式关掉唤起。
     *
     * `wakeHost` 默认是 true（用户点开面板那条路径占多数），所以危险方向是"忘了传"。
     * 这里反过来钉：源码里只准出现 `wakeHost = false`，出现 `wakeHost = true` 说明
     * 有人在静默路径上打开了它。
     */
    fun silentPullsMustOptOutOfWakingTheHost() {
        val offenders = sources().filter { it.readText().contains("wakeHost = true") }
            .map(File::getName)
        assertEquals("静默拉取不准唤起宿主", emptyList<String>(), offenders)
        val autoConfirm = sources().first { it.name == "RecommendationBlocklistDialogs.kt" }.readText()
        assertTrue("自动确认的后台合并必须显式关掉唤起",
            autoConfirm.contains("wakeHost = false"))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (HostProcessWakeCallSiteTest) -> Unit) {
            val s = HostProcessWakeCallSiteTest()
            block(s)
        }
        runIsolated { s -> s.theScanActuallySeesTheSources() }
        runIsolated { s -> s.onlyTheScanSnapshotClientMayEnableTheTransportWake() }
        runIsolated { s -> s.theDiagnosticsChannelStaysOnTheDefault() }
        runIsolated { s -> s.silentPullsMustOptOutOfWakingTheHost() }
    }
}
