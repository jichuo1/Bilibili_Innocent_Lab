package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 有超时和合并输出流的子进程执行器。
 *
 * 将 stderr 合并到 stdout，并由独立线程持续排空，避免先读取一个 pipe 而另一个
 * pipe 填满时发生互相等待。调用方只需要退出码，不保留命令输出。
 */
object ShellCommandRunner {

    private const val OUTPUT_DRAIN_TIMEOUT_MS = 1_000L

    @Throws(IOException::class)
    fun run(command: List<String>, timeoutMs: Long): Int {
        require(command.isNotEmpty()) { "command must not be empty" }
        require(timeoutMs > 0L) { "timeout must be positive" }

        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        val executor = Executors.newSingleThreadExecutor()
        val outputDrained = executor.submit<String> {
            process.inputStream.bufferedReader().use { it.readText() }
        }

        try {
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroy()
                if (!process.waitFor(OUTPUT_DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    process.waitFor()
                }
                throw IOException("command timed out after ${timeoutMs}ms")
            }
            // 等待排空合并后的输出；内容刻意丢弃，避免将 shell 输出暴露给 UI。
            outputDrained.get(OUTPUT_DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            return process.exitValue()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("interrupted while running command", e)
        } catch (e: ExecutionException) {
            throw IOException("failed to drain command output", e.cause)
        } catch (e: TimeoutException) {
            throw IOException("timed out while draining command output", e)
        } finally {
            executor.shutdownNow()
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
        }
    }
}
