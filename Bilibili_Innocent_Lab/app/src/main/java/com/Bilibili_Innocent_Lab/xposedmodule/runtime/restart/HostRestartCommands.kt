package com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart

import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 固定参数和一次性启动票据。与 Android Binder 分离，便于验证失败与取消边界。 */
internal class HostRestartCommands(
    private val hostUid: Int,
    private val run: (List<String>) -> Output = { execute(it) },
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val pause: () -> Unit = { Thread.sleep(100) }
) {
    data class Output(val exit: Int, val text: String)
    data class Stopped(val token: String, val oldPids: List<Int>)
    private var pending: Stopped? = null
    private var deadline = 0L

    init { require(hostUid >= 10_000) }

    @Synchronized fun stop(): Stopped {
        pending = null
        val old = processes().values.toMutableSet()
        val command = listOf("/system/bin/am", "force-stop", "--user", user(), TARGET)
        checked(command)
        var attempts = 1
        var emptySince: Long? = null
        val until = now() + 4_000
        while (true) {
            val running = processes()
            old += running.values
            if (running.isEmpty()) {
                val since = emptySince
                if (since != null && now() - since >= 300) break
                if (since == null) emptySince = now()
            } else {
                emptySince = null
                // 部分 ROM 在首次 force-stop 的死亡回调中重建 sticky 下载进程。
                // 只重试同一个固定目标，最多三次；持续残留仍失败，不伪造强停完成。
                if (attempts < 3) { checked(command); attempts++ }
            }
            check(now() < until) { "stop_failed" }
            pause()
        }
        return Stopped(UUID.randomUUID().toString(), old.toList()).also {
            pending = it
            deadline = now() + 15_000
        }
    }

    @Synchronized fun start(token: String): Int {
        val stopped = pending
        pending = null // 即使启动失败也消耗票据；迟到/重复调用不能再次启动。
        check(stopped != null && stopped.token == token && now() < deadline) { "stale_restart" }
        check(processes().isEmpty()) { "host_reappeared" }
        val output = checked(listOf("/system/bin/am", "start", "--user", user(), "-W", "-n", "$TARGET/.MainActivityV2"))
        check(!Regex("(?im)^\\s*(error|exception|securityexception)[: ]").containsMatchIn(output)) { "launch_failed" }
        val until = now() + 3_000
        while (true) {
            val pid = processes()[TARGET]
            if (pid != null && pid !in stopped.oldPids) return pid
            check(now() < until) { "launch_failed" }
            pause()
        }
    }

    private fun user() = (hostUid / 100_000).toString()
    private fun checked(command: List<String>): String {
        val result = run(command)
        check(result.exit == 0) { "command_failed" }
        return result.text
    }
    private fun processes(): Map<String, Int> = parseProcesses(
        checked(listOf("/system/bin/ps", "-A", "-o", "UID,PID,NAME")), hostUid)

    companion object {
        const val TARGET = "tv.danmaku.bili"
        fun parseProcesses(raw: String, uid: Int): Map<String, Int> {
            val lines = raw.lineSequence().filter { it.isNotBlank() }.toList()
            check(lines.firstOrNull()?.trim()?.split(Regex("\\s+")) == listOf("UID", "PID", "NAME")) { "invalid_ps" }
            return buildMap {
                lines.drop(1).forEach { line ->
                    val row = line.trim().split(Regex("\\s+"), limit = 3)
                    check(row.size == 3 && row[0].toIntOrNull() != null && row[1].toIntOrNull() != null) { "invalid_ps" }
                    if (row[0].toInt() == uid && (row[2] == TARGET || row[2].startsWith("$TARGET:"))) {
                        val pid = row[1].toInt()
                        check(pid > 0 && !containsKey(row[2])) { "invalid_ps" }
                        put(row[2], pid)
                    }
                }
            }
        }

        // 现有 ShellCommandRunner 刻意丢弃输出；这里需要受限的 ps/启动结果，且必须持续排空 pipe。
        internal fun execute(command: List<String>, timeoutMs: Long = 12_000): Output {
            require(timeoutMs > 0)
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val reader = Executors.newSingleThreadExecutor()
            val future = reader.submit<String> {
                process.inputStream.bufferedReader().use { input ->
                    val result = StringBuilder()
                    val buffer = CharArray(2048)
                    while (true) {
                        val length = input.read(buffer)
                        if (length < 0) break
                        // ps 可能列出很多系统进程；截断必须导致解析失败，不能漏掉宿主后误判已停止。
                        check(result.length + length <= 256 * 1024) { "output_too_large" }
                        result.append(buffer, 0, length)
                    }
                    result.toString()
                }
            }
            try {
                if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) throw IOException("command_timeout")
                return Output(process.exitValue(), future.get(1, TimeUnit.SECONDS))
            } finally {
                process.destroyForcibly()
                runCatching { process.inputStream.close() }
                runCatching { process.outputStream.close() }
                reader.shutdownNow()
            }
        }
    }
}
