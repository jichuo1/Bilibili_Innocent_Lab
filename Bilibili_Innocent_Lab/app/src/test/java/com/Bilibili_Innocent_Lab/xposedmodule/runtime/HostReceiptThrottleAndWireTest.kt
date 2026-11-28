package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HostReceiptThrottleAndWireTest {

    @Test
    fun executorThrottleAndStartupTraceBehaviors() {
        `executor enables core thread timeout with bounded queue and daemon name`()
        `refresh throttle enforces 30s cooldown, coalesces dirty resumes, and preserves force bypass`()
        `inline StartupTraceRecorder measure tracks nested spans, failures, and non-local return`()
    }

    @Test
    fun sharedPacketAndPayloadGzipCompressionRoundTrip() {
        `shared receipt packet encodes once across concurrent dual channels and short circuits after primary success`()
        `payload gzip compression triggers above 64KiB and round trips within 256KiB oneway budget`()
    }

    private fun `executor enables core thread timeout with bounded queue and daemon name`() {
        val executor = HostReceiptWire.executor("bil-test-wire")
        try {
            assertTrue(executor.allowsCoreThreadTimeOut())
            assertEquals(30L, executor.getKeepAliveTime(TimeUnit.SECONDS))
            assertEquals(1, executor.corePoolSize)
            assertEquals(1, executor.maximumPoolSize)
            assertEquals(8, executor.queue.remainingCapacity())
            assertTrue(executor.rejectedExecutionHandler is ThreadPoolExecutor.AbortPolicy)

            val latch = CountDownLatch(1)
            var threadName = ""
            var daemon = false
            executor.execute {
                threadName = Thread.currentThread().name
                daemon = Thread.currentThread().isDaemon
                latch.countDown()
            }
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            assertEquals("bil-test-wire", threadName)
            assertTrue(daemon)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun `refresh throttle enforces 30s cooldown, coalesces dirty resumes, and preserves force bypass`() {
        var now = 1_000L
        val throttle = RefreshThrottle(cooldownMs = 30_000L, clock = { now })

        // 首次初始化允许立即执行。
        assertTrue(throttle.tryAcquire(force = true))
        assertFalse(throttle.isDirty())

        // 在途期间到来的 resume 只记脏标记，不重复发包。
        now = 1_100L
        assertFalse(throttle.tryAcquire(force = false))
        assertTrue(throttle.isDirty())
        assertFalse(throttle.complete(succeeded = true))
        assertTrue(throttle.isDirty())

        // 冷却窗口未满前重复 onActivityResumed 全部被节流并保留脏标记。
        now = 15_000L
        assertFalse(throttle.tryAcquire(force = false))
        assertFalse(throttle.flushPendingIfReady())
        assertTrue(throttle.isDirty())

        now = 30_999L
        assertFalse(throttle.tryAcquire(force = false))
        assertTrue(throttle.isDirty())

        // 冷却期届满，resume 消费脏标记并重新触发一次刷新。
        now = 31_000L
        assertTrue(throttle.tryAcquire(force = false))
        assertFalse(throttle.isDirty())
        assertFalse(throttle.complete(succeeded = true))
        assertFalse(throttle.isDirty())

        // 显式强制刷新不受冷却期限制；若此时正在执行，则会在当前轮结束后立刻补发一轮。
        now = 31_100L
        assertTrue(throttle.tryAcquire(force = true))
        now = 31_200L
        assertFalse(throttle.tryAcquire(force = true))
        assertTrue(throttle.complete(succeeded = true))
        assertFalse(throttle.complete(succeeded = true))

        // 失败完成保留脏标记，等待下一次冷却窗口届满再尝试。
        now = 70_000L
        assertTrue(throttle.tryAcquire(force = false))
        assertFalse(throttle.complete(succeeded = false))
        assertTrue(throttle.isDirty())
        now = 70_500L
        assertFalse(throttle.flushPendingIfReady())
        now = 100_000L
        assertTrue(throttle.flushPendingIfReady())
        assertFalse(throttle.isDirty())
        assertFalse(throttle.complete(succeeded = true))
    }

    private fun `shared receipt packet encodes once across concurrent dual channels and short circuits after primary success`() {
        val encodeCount = AtomicInteger(0)
        val packet = SharedReceiptPacket(
            builder = {
                val seq = encodeCount.incrementAndGet()
                "encoded-$seq"
            },
            copier = { String(it.toCharArray()) }
        )

        val pool = Executors.newFixedThreadPool(4)
        try {
            val start = CountDownLatch(1)
            val tasks = (0 until 8).map {
                pool.submit<String?> {
                    start.await(5, TimeUnit.SECONDS)
                    packet.getOrBuild()
                }
            }
            start.countDown()
            val results = tasks.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, encodeCount.get())
            assertTrue(results.all { it == "encoded-1" })
            assertNotSame(results[0], results[1])
        } finally {
            pool.shutdownNow()
        }

        assertFalse(packet.isPrimaryDelivered())
        packet.markPrimaryDelivered()
        assertTrue(packet.isPrimaryDelivered())

        // 构建返回 null 时不固化空结果，后续条件就绪可重试构建。
        var ready = false
        val deferred = SharedReceiptPacket(builder = { if (ready) "ok" else null })
        assertNull(deferred.getOrBuild())
        ready = true
        assertEquals("ok", deferred.getOrBuild())
        assertEquals("ok", deferred.getOrBuild())
    }

    private fun `inline StartupTraceRecorder measure tracks nested spans, failures, and non-local return`() {
        var tick = 100L
        val recorder = StartupTraceRecorder(clock = { tick += 10L; tick }, mainThread = { true }, roots = setOf("root"))
        fun helperWithNonLocalReturn(): String {
            recorder.measure("root") {
                recorder.measure("child") {
                    return "early"
                }
            }
            return "unreachable"
        }

        assertEquals("early", helperWithNonLocalReturn())
        try {
            recorder.measure("root") { throw IllegalStateException("boom") }
        } catch (_: IllegalStateException) {
        }

        val parsed = StartupTraceRecorder.validate(recorder.json())
        val rows = parsed.getJSONArray("rows")
        assertEquals(2, rows.length())
        val rootRow = (0 until rows.length()).map { rows.getJSONObject(it) }.first { it.getString("id") == "root" }
        assertEquals(2, rootRow.getInt("calls"))
        assertEquals(1, rootRow.getInt("failures"))
    }

    private fun `payload gzip compression triggers above 64KiB and round trips within 256KiB oneway budget`() {
        val belowThreshold = "a".repeat(HostReceiptWire.GZIP_THRESHOLD_BYTES)
        val encodedPlain = HostReceiptWire.encodePayload(belowThreshold)
        assertTrue(encodedPlain is HostReceiptWire.EncodedPayload.Plain)
        assertEquals(
            belowThreshold,
            HostReceiptWire.decodePayload(null, belowThreshold, null)
        )
        assertTrue(HostReceiptWire.withinOnewayBudget(encodedPlain))

        // 超过 256 KiB UTF-16 预算（250 KiB JSON 文本在 Parcel UTF-16 占 500 KiB），GZIP 后可轻松放进 256 KiB 异步上限。
        val largeSnapshot = "{\"entries\":[" + "\"flag_sample_enabled\":true,".repeat(8_000) + "]}"
        assertTrue(largeSnapshot.toByteArray(Charsets.UTF_8).size > HostReceiptWire.GZIP_THRESHOLD_BYTES)
        assertTrue(HostReceiptWire.estimateUtf16ParcelBytes(largeSnapshot) > HostReceiptWire.MAX_ONEWAY_PARCEL_BYTES)

        val encodedGzip = HostReceiptWire.encodePayload(largeSnapshot)
        assertTrue(encodedGzip is HostReceiptWire.EncodedPayload.Gzip)
        assertTrue(HostReceiptWire.withinOnewayBudget(encodedGzip))

        val restored = HostReceiptWire.decodePayload(
            encoding = HostReceiptWire.ENCODING_GZIP,
            rawPayload = "",
            gzipPayload = (encodedGzip as HostReceiptWire.EncodedPayload.Gzip).bytes
        )
        assertEquals(largeSnapshot, restored)

        // 拒绝超限解压（防 GZIP 炸弹）与未知编码。
        assertNull(
            HostReceiptWire.decodePayload(
                encoding = HostReceiptWire.ENCODING_GZIP,
                rawPayload = "",
                gzipPayload = encodedGzip.bytes,
                maxDecompressedBytes = 1024
            )
        )
        assertNull(HostReceiptWire.decodePayload("br", "", byteArrayOf(1, 2)))
        assertNull(HostReceiptWire.decodePayload(HostReceiptWire.ENCODING_GZIP, "", byteArrayOf(1, 2)))

        // 不可压缩的随机高熵超大数据超过 256 KiB 单向预算时必须被预算门拦截。
        val uncompressibleHuge = "x".repeat(HostReceiptWire.MAX_ONEWAY_PARCEL_BYTES)
        val oversizedPlain = HostReceiptWire.EncodedPayload.Plain(uncompressibleHuge)
        assertFalse(HostReceiptWire.withinOnewayBudget(oversizedPlain))
    }
}
