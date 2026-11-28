package com.Bilibili_Innocent_Lab.xposedmodule.agent

import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.AgentModelToolCall
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AgentProgressGuardTest {
    fun nativeEmptyPagesAndNewOpaqueCursorsCannotFakeProgressAndRankChangesRemainFacts() {
        val guard = AgentProgressGuard(2)
        fun ranked(position: Int, cursor: String) = JSONObject().put("ok", true).put("data", JSONObject().put("source", "host_rpc")
            .put("dataset", "ranking").put("region_id", "0").put("next_cursor", cursor).put("videos", JSONArray()
                .put(JSONObject().put("video_id", "av1").put("ranking_position", position))))
        assertTrue(guard.observe(call("get_ranking"), ranked(1, "cursor1")))
        assertTrue(guard.observe(call("get_ranking"), ranked(1, "cursor2")))
        assertFalse(guard.observe(call("get_ranking"), ranked(1, "cursor3")))
        assertTrue(guard.observe(call("get_ranking"), ranked(2, "cursor4")))
        val empty = JSONObject().put("ok", true).put("data", JSONObject().put("dataset", "following_feed").put("posts", JSONArray()))
        assertTrue(guard.observe(call("get_following_feed"), empty))
        assertFalse(guard.observe(call("get_following_feed"), empty.apply { getJSONObject("data").put("next_cursor", "new") }))
    }
    fun postActionSnapshotUsesRealUiFactsWithoutInventingProgressFromDispatch() {
        fun ui(label: String, after: Boolean) = JSONObject().put("ok", true).put("data", JSONObject().put("backend", "accessibility")
            .put("snapshot_id", "fresh").put("nodes", JSONArray().put(JSONObject().put("label", label)))
            .apply { if (after) put("action", "dispatched").put("observation_after_action", true).put("verification_required", false) })
        val guard = AgentProgressGuard(2)
        assertTrue(guard.observe(call("get_ui_state"), ui("same", false)))
        assertTrue(guard.observe(call("click_ui"), ui("same", true)))
        assertFalse(guard.observe(call("get_ui_state"), ui("same", false)))
        assertTrue(guard.observe(call("swipe_ui"), ui("new", true)))
    }
    fun newSnapshotIdsOffsetsAndDispatchedClicksCannotFakeUiProgress() {
        val guard = AgentProgressGuard(3)
        fun same(id: String, offset: Int) = JSONObject().put("ok", true).put("data", JSONObject()
            .put("snapshot_id", id).put("offset", offset).put("next_offset", offset + 12)
            .put("nodes", JSONArray().put(JSONObject().put("label", "相同界面"))))
        assertTrue(guard.observe(call("get_ui_state"), same("one", 0)))
        assertTrue(guard.observe(call("get_ui_state"), same("two", 12)))
        assertTrue(guard.observe(call("click_ui"), JSONObject().put("ok", true).put("data", JSONObject().put("action", "dispatched"))))
        assertFalse(guard.observe(call("get_ui_state"), same("three", 24)))
    }
    private fun call(name: String = "get_host_state", args: JSONObject = JSONObject(), id: String = "fresh") =
        AgentModelToolCall(id, name, args)
    private fun state(time: Long = 1, page: String = "search") = JSONObject().put("ok", true).put("data", JSONObject()
        .put("page", page).put("query", "悟空").put("capture_elapsed", time).put("observed_at_elapsed", time)
        .put("cache_hit", time > 1).put("observations", time).put("source_index", time.toInt()))
    private fun candidates(vararg ids: String, query: String = "悟空", cursor: String = "page:1") = JSONObject().put("ok", true)
        .put("data", JSONObject().put("query", query).put("next_cursor", cursor).put("videos", JSONArray(ids.map { id ->
            JSONObject().put("video_id", id).put("title", "demo $id").put("official_source", "unknown")
        })))

    fun `time counters provenance and cache flags do not fake progress`() {
        val guard = AgentProgressGuard()
        assertTrue(guard.observe(call(), state()))
        repeat(7) { assertTrue(guard.observe(call(id = "call_$it"), state(it + 2L))) }
        assertFalse(guard.observe(call(), state(10)))
    }

    fun `alternating old reads and failures still stalls`() {
        val guard = AgentProgressGuard()
        val failed = JSONObject().put("ok", false).put("error", "same_failure")
        assertTrue(guard.observe(call(), state()))
        assertTrue(guard.observe(call("get_video_details", JSONObject().put("video_id", "av1")), failed))
        repeat(6) { index ->
            assertTrue(if (index % 2 == 0) guard.observe(call(), state(index + 2L)) else
                guard.observe(call("get_video_details", JSONObject().put("video_id", "av1")), failed))
        }
        assertFalse(guard.observe(call(), state(20)))
    }

    fun `only actual candidates details and page identity reset stagnation`() {
        val guard = AgentProgressGuard(2)
        assertTrue(guard.observe(call(), state()))
        assertTrue(guard.observe(call(), state(2)))
        assertTrue(guard.observe(call(), state(3, "video")))
        assertTrue(guard.observe(call("search_videos", JSONObject().put("query", "new query")), state()))
        assertFalse(guard.observe(call("search_videos", JSONObject().put("query", "another query")), state()))
        val candidates = JSONObject().put("ok", true).put("data", JSONObject().put("videos", JSONArray()
            .put(JSONObject().put("video_id", "av2").put("official_source", "unknown"))))
        assertTrue(guard.observe(call("search_videos", JSONObject().put("query", "new query")), candidates))
        assertTrue(guard.observe(call("get_video_details", JSONObject().put("video_id", "av2")),
            JSONObject().put("ok", true).put("data", JSONObject().put("video_id", "av2").put("title", "new details"))))
    }

    fun `stable digest handles field order nested timestamps and retained semantic facts`() {
        val first = JSONObject("{\"query\":\"悟空\",\"data\":{\"title\":\"demo\",\"observed_at_elapsed\":1}}")
        val second = JSONObject("{\"data\":{\"cache_hit\":true,\"observed_at_elapsed\":9,\"title\":\"demo\"},\"query\":\"悟空\"}")
        assertEquals(AgentProgressGuard.stableDigest(first), AgentProgressGuard.stableDigest(second))
        second.getJSONObject("data").put("title", "changed")
        assertNotEquals(AgentProgressGuard.stableDigest(first), AgentProgressGuard.stableDigest(second))
    }

    fun `image digest and pagination remain evidence while raw image and model words are discarded`() {
        val value = JSONObject().put("image_data_url", "data:image/jpeg;base64,one")
            .put("image_digest", "same-image").put("visual_assessment", JSONObject().put("description", "search results"))
            .put("next_cursor", "page:1")
        val before = AgentProgressGuard.stableDigest(value)
        value.put("image_data_url", "data:image/jpeg;base64,two")
        assertEquals(before, AgentProgressGuard.stableDigest(value))
        value.put("next_cursor", "page:2")
        assertNotEquals(before, AgentProgressGuard.stableDigest(value))
        value.put("next_cursor", "page:1").getJSONObject("visual_assessment").put("description", "blocked")
        assertEquals(before, AgentProgressGuard.stableDigest(value))
        value.put("image_digest", "new-image")
        assertNotEquals(before, AgentProgressGuard.stableDigest(value))
    }

    fun `same image with varying auxiliary model wording cannot fake task progress`() {
        val guard = AgentProgressGuard()
        fun response(index: Int) = state(index.toLong()).apply {
            getJSONObject("data").put("image_digest", "fixed-screen")
                .put("visual_assessment", JSONObject().put("description", "model wording $index").put("source_index", index))
                .put("visual_status", "status-$index")
                .put("decision_review", JSONObject().put("suggestion", "review wording $index"))
                .put("decision_review_status", "review-status-$index").put("decision_review_is_unverified", index % 2 == 0)
        }
        assertTrue(guard.observe(call("inspect_screen"), response(1)))
        repeat(7) { assertTrue(guard.observe(call("inspect_screen"), response(it + 2))) }
        assertFalse(guard.observe(call("inspect_screen"), response(10)))
        val newScreen = response(11).apply { getJSONObject("data").put("image_digest", "changed-screen") }
        assertTrue(guard.observe(call("inspect_screen"), newScreen))
    }

    fun `guard remains bounded across many results and clears task evidence`() {
        val guard = AgentProgressGuard(2)
        repeat(1_000) { index -> assertTrue(guard.observe(call("search_videos", JSONObject().put("query", "q$index")), candidates("av${index + 1}"))) }
        assertTrue(guard.observe(call("search_videos", JSONObject().put("query", "q999")), candidates("av1000")))
        assertFalse(guard.observe(call("search_videos", JSONObject().put("query", "q999")), candidates("av1000")))
        guard.clear()
        assertTrue(guard.observe(call("search_videos", JSONObject().put("query", "q999")), candidates("av1000")))
    }

    fun `different failed operations ids and queries all accumulate stagnation`() {
        val guard = AgentProgressGuard()
        repeat(7) { index -> assertTrue(guard.observe(call(if (index % 2 == 0) "search_videos" else "get_video_details",
            JSONObject().put("query", "different $index").put("video_id", "av$index")),
            JSONObject().put("ok", false).put("error", "failure_$index"))) }
        assertFalse(guard.observe(call("search_videos", JSONObject().put("query", "brand new")),
            JSONObject().put("ok", false).put("error", "new_failure")))
    }

    fun `empty search results keep accumulating despite changing query and cursor`() {
        val guard = AgentProgressGuard()
        repeat(7) { index -> assertTrue(guard.observe(call("search_videos", JSONObject().put("query", "q$index")),
            candidates(query = "q$index", cursor = "page:$index"))) }
        assertFalse(guard.observe(call("search_videos", JSONObject().put("query", "new query")),
            candidates(query = "new query", cursor = "new cursor")))
    }

    fun `same candidate set with different ordering queries and cursors is old evidence`() {
        val guard = AgentProgressGuard()
        assertTrue(guard.observe(call("search_videos"), candidates("av1", "av2")))
        repeat(7) { index -> assertTrue(guard.observe(call("search_videos", JSONObject().put("query", "q$index")),
            candidates("av2", "av1", query = "q$index", cursor = "page:$index"))) }
        assertFalse(guard.observe(call("search_videos"), candidates("av1", "av2", query = "fresh phrase")))
        assertTrue(guard.observe(call("search_videos"), candidates("av1", "av3")))
    }

    fun `navigation requested is not a new observation`() {
        val guard = AgentProgressGuard(2)
        val requested = JSONObject().put("ok", true).put("data", JSONObject().put("video_id", "av1")
            .put("navigation", "requested").put("observed", false))
        assertTrue(guard.observe(call("open_video", JSONObject().put("video_id", "av1")), requested))
        assertFalse(guard.observe(call("open_video", JSONObject().put("video_id", "av2")), requested))
        assertTrue(guard.observe(call("get_host_state"), state(page = "video")))
    }

    fun `fgs promotion helper and usesAccessibility policy handle background restrictions safely`() {
        assertTrue(runForegroundSafely { })
        assertFalse(runForegroundSafely { throw SecurityException("fgs denied") })
        assertFalse(runForegroundSafely { throw IllegalStateException("background fgs denied") })

        // 纯原生 RPC 任务即使启动时无障碍已连接也不应因无障碍抖动被取消。
        val routing = AgentNativeRoutingPolicy()
        routing.startingNative()
        routing.began()
        assertFalse(
            agentTaskUsesAccessibility(
                routing.uiOnly,
                usedAccessibility = false
            )
        )
        // 执行过 get_ui_state / UI 操作后断连必须取消。
        assertTrue(
            agentTaskUsesAccessibility(
                routing.uiOnly,
                usedAccessibility = true
            )
        )
        // 原生转交 UI 或纯 UI 任务必须依赖无障碍。
        assertTrue(
            routing.handoff(
                JSONObject().put("ok", true).put(
                    "data",
                    JSONObject().put("status", "finished").put("native_window_released", true)
                )
            )
        )
        assertTrue(
            agentTaskUsesAccessibility(
                routing.uiOnly,
                usedAccessibility = false
            )
        )

        val uiOnlyPolicy = AgentNativeRoutingPolicy().apply { noNative() }
        assertTrue(
            agentTaskUsesAccessibility(
                uiOnlyPolicy.uiOnly,
                usedAccessibility = false
            )
        )
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (AgentProgressGuardTest) -> Unit) {
            val s = AgentProgressGuardTest()
            block(s)
        }
        runIsolated { s -> s.nativeEmptyPagesAndNewOpaqueCursorsCannotFakeProgressAndRankChangesRemainFacts() }
        runIsolated { s -> s.postActionSnapshotUsesRealUiFactsWithoutInventingProgressFromDispatch() }
        runIsolated { s -> s.newSnapshotIdsOffsetsAndDispatchedClicksCannotFakeUiProgress() }
        runIsolated { s -> s.`time counters provenance and cache flags do not fake progress`() }
        runIsolated { s -> s.`alternating old reads and failures still stalls`() }
        runIsolated { s -> s.`only actual candidates details and page identity reset stagnation`() }
        runIsolated { s -> s.`stable digest handles field order nested timestamps and retained semantic facts`() }
        runIsolated { s -> s.`image digest and pagination remain evidence while raw image and model words are discarded`() }
        runIsolated { s -> s.`same image with varying auxiliary model wording cannot fake task progress`() }
        runIsolated { s -> s.`guard remains bounded across many results and clears task evidence`() }
        runIsolated { s -> s.`different failed operations ids and queries all accumulate stagnation`() }
        runIsolated { s -> s.`empty search results keep accumulating despite changing query and cursor`() }
        runIsolated { s -> s.`same candidate set with different ordering queries and cursors is old evidence`() }
        runIsolated { s -> s.`navigation requested is not a new observation`() }
        runIsolated { s -> s.`fgs promotion helper and usesAccessibility policy handle background restrictions safely`() }
    
        AgentScreenshotWorkerCases().verifyAllCases()
        AgentTaskCacheCases().verifyAllCases()
        AgentTaskLimitsCases().verifyAllCases()
        AgentUiPolicyCases().verifyAllCases()
        AgentVisualSnapshotGateCases().verifyAllCases()
    }
}

private class AgentScreenshotWorkerCases {
    private fun awaitFree(worker: AgentScreenshotWorker) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (worker.busy && System.nanoTime() < deadline) Thread.sleep(1)
        assertFalse("worker should release its in-flight slot", worker.busy)
    }

    fun acceptedJobRunsOffTheCallingThread() {
        val worker = AgentScreenshotWorker("BIL-AgentShot-test")
        val ran = CountDownLatch(1)
        val thread = AtomicReference<Thread>()
        assertTrue(worker.trySubmit { thread.set(Thread.currentThread()); ran.countDown() })
        assertTrue(ran.await(5, TimeUnit.SECONDS))
        assertNotEquals(Thread.currentThread(), thread.get())
        assertEquals("BIL-AgentShot-test", thread.get().name)
        awaitFree(worker)
    }

    fun secondSubmissionWhileBusyIsRejectedAndNeverRunsOnTheCaller() {
        val worker = AgentScreenshotWorker("BIL-AgentShot-test")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        assertTrue(worker.trySubmit { started.countDown(); release.await(5, TimeUnit.SECONDS) })
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val ranRejected = AtomicInteger()
        // 满载时拒绝：任务绝不会在提交者线程（主线程）上运行，资源仍归调用方释放。
        repeat(50) { assertFalse(worker.trySubmit { ranRejected.incrementAndGet() }) }
        release.countDown()
        awaitFree(worker)
        assertEquals(0, ranRejected.get())
        val ranAfter = CountDownLatch(1)
        assertTrue(worker.trySubmit { ranAfter.countDown() })
        assertTrue(ranAfter.await(5, TimeUnit.SECONDS))
        awaitFree(worker)
    }

    fun anErrorThrownWhileSubmittingResetsTheSlotAndPropagates() {
        // 例如建线程时的 OutOfMemoryError：不是 RejectedExecutionException，旧实现不会复位在途标志。
        val failing = AgentScreenshotWorker(Executor { throw OutOfMemoryError("unable to create native thread") })
        var thrown: Throwable? = null
        try { failing.trySubmit { fail("job must not run") } } catch (error: Throwable) { thrown = error }
        assertTrue("the original Error keeps propagating: $thrown", thrown is OutOfMemoryError)
        assertFalse("in-flight must be reset when submission did not succeed", failing.busy)
        // 之后仍然可以再次提交（不是永久 screen_busy）。
        val ok = AtomicInteger()
        val recovering = AgentScreenshotWorker(object : Executor {
            var first = true
            override fun execute(command: Runnable) { if (first) { first = false; throw IllegalStateException("boom") }; command.run() }
        })
        try { recovering.trySubmit { ok.incrementAndGet() } } catch (_: IllegalStateException) {}
        assertFalse(recovering.busy)
        assertTrue(recovering.trySubmit { ok.incrementAndGet() })
        assertEquals(1, ok.get())
        assertFalse(recovering.busy)
    }

    fun rejectionStillReturnsFalseAndResetsTheSlot() {
        val rejecting = AgentScreenshotWorker(Executor { throw RejectedExecutionException("saturated") })
        assertFalse(rejecting.trySubmit { fail("job must not run") })
        assertFalse(rejecting.busy)
        assertFalse(rejecting.trySubmit { fail("job must not run") })
    }

    fun failingJobStillReleasesTheSlot() {
        val worker = AgentScreenshotWorker("BIL-AgentShot-test")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
        try {
            assertTrue(worker.trySubmit { throw IllegalStateException("encode failed") })
            awaitFree(worker)
            val ran = CountDownLatch(1)
            assertTrue(worker.trySubmit { ran.countDown() })
            assertTrue(ran.await(5, TimeUnit.SECONDS))
            awaitFree(worker)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    fun inFlightNeverExceedsOneUnderContention() {
        val worker = AgentScreenshotWorker("BIL-AgentShot-test")
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val accepted = AtomicInteger()
        val completed = AtomicInteger()
        val producers = List(4) {
            Thread {
                repeat(300) {
                    val taken = worker.trySubmit {
                        peak.accumulateAndGet(running.incrementAndGet()) { a, b -> maxOf(a, b) }
                        Thread.sleep(0, 200_000)
                        running.decrementAndGet()
                        completed.incrementAndGet()
                    }
                    if (taken) accepted.incrementAndGet()
                    Thread.yield()
                }
            }
        }
        producers.forEach(Thread::start)
        producers.forEach { it.join(10_000) }
        awaitFree(worker)
        assertEquals(1, peak.get())
        assertTrue(accepted.get() > 0)
        assertEquals(accepted.get(), completed.get())
    }

    fun fullFrameCropIsTheWholeBitmap() {
        val region = AgentScreenshotGeometry.cropRegion(0, 0, 1080, 2400, 0, 0, 1080, 2400, 1080, 2400)
        assertArrayEquals(intArrayOf(0, 0, 1080, 2400), region)
    }

    fun cropScalesFromWindowCoordinatesToScreenshotPixels() {
        // 窗口 (100,200)-(1180,2600)，截图分辨率是窗口的一半。
        val region = AgentScreenshotGeometry.cropRegion(100, 400, 1180, 2000, 100, 200, 1080, 2400, 540, 1200)
        assertArrayEquals(intArrayOf(0, 100, 540, 800), region)
    }

    fun cropKeepsAtLeastOnePixelAndTruncatesLikeTheLegacyFormula() {
        val empty = AgentScreenshotGeometry.cropRegion(10, 10, 10, 10, 0, 0, 1000, 1000, 100, 100)
        assertEquals(1, empty[2])
        assertEquals(1, empty[3])
        // (1*5/7)=0, (3*5/7)=2 -> 宽 2
        assertArrayEquals(intArrayOf(0, 0, 2, 2), AgentScreenshotGeometry.cropRegion(1, 1, 3, 3, 0, 0, 7, 7, 5, 5))
    }

    fun scalingNeverUpscalesAndCapsTheLongSideAt960() {
        assertArrayEquals(intArrayOf(432, 960), AgentScreenshotGeometry.scaledSize(1080, 2400))
        assertArrayEquals(intArrayOf(960, 432), AgentScreenshotGeometry.scaledSize(2400, 1080))
        assertArrayEquals(intArrayOf(500, 300), AgentScreenshotGeometry.scaledSize(500, 300))
        assertArrayEquals(intArrayOf(960, 960), AgentScreenshotGeometry.scaledSize(960, 960))
        assertArrayEquals(intArrayOf(1, 960), AgentScreenshotGeometry.scaledSize(1, 5000))
    }

    fun limitsMatchTheHistoricalPolicy() {
        assertEquals(8_500_000L, AgentScreenshotGeometry.MAX_SOURCE_PIXELS)
        assertEquals(130_000, AgentScreenshotGeometry.MAX_ENCODED_BYTES)
        assertEquals(960f, AgentScreenshotGeometry.MAX_SIDE, 0f)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (AgentScreenshotWorkerCases) -> Unit) {
            val s = AgentScreenshotWorkerCases()
            block(s)
        }
        runIsolated { s -> s.acceptedJobRunsOffTheCallingThread() }
        runIsolated { s -> s.secondSubmissionWhileBusyIsRejectedAndNeverRunsOnTheCaller() }
        runIsolated { s -> s.anErrorThrownWhileSubmittingResetsTheSlotAndPropagates() }
        runIsolated { s -> s.rejectionStillReturnsFalseAndResetsTheSlot() }
        runIsolated { s -> s.failingJobStillReleasesTheSlot() }
        runIsolated { s -> s.inFlightNeverExceedsOneUnderContention() }
        runIsolated { s -> s.fullFrameCropIsTheWholeBitmap() }
        runIsolated { s -> s.cropScalesFromWindowCoordinatesToScreenshotPixels() }
        runIsolated { s -> s.cropKeepsAtLeastOnePixelAndTruncatesLikeTheLegacyFormula() }
        runIsolated { s -> s.scalingNeverUpscalesAndCapsTheLongSideAt960() }
        runIsolated { s -> s.limitsMatchTheHistoricalPolicy() }
    }
}

private class AgentTaskCacheCases {
    fun accountFeedHistoryLiveAndFreshMetricDatasetsAreNeverRetainedAsReusableFacts() {
        val cache = AgentTaskCache(8, 100)
        for (dataset in listOf("history", "following_feed", "up_live_status", "video_metrics"))
            cache.put(dataset, JSONObject().put("data", JSONObject().put("dataset", dataset).put("source", "host_rpc")), 100)
        assertEquals(0, cache.size())
        cache.put("public_detail", JSONObject().put("source", "host_rpc").put("video_id", "av1"), 100)
        assertEquals(1, cache.size())
    }
    fun `cache hit is copied and preserves original observation time`() {
        val cache = AgentTaskCache(2, 100)
        val input = JSONObject().put("title", "original")
        cache.put("model:page:query", input, 100)
        input.put("title", "mutated")
        val first = checkNotNull(cache.get("model:page:query", 101))
        assertEquals("original", first.getString("title"))
        assertTrue(first.getBoolean("cache_hit"))
        assertEquals(100L, first.getLong("observed_at_elapsed"))
        first.put("title", "changed read")
        assertEquals("original", cache.get("model:page:query", 102)?.getString("title"))
    }

    fun `ttl expires at boundary and future timestamps cannot become fresh`() {
        val cache = AgentTaskCache(2, 100)
        cache.put("a", JSONObject().put("value", 1), 100)
        assertNotNull(cache.get("a", 199))
        assertNull(cache.get("a", 200))
        cache.put("a", JSONObject(), 300)
        assertNull(cache.get("a", 299))
        assertEquals(0, cache.size())
    }

    fun `recently read entries survive lru eviction and different keys cannot reuse a result`() {
        val cache = AgentTaskCache(2, 100)
        cache.put("source1:page1", JSONObject(), 100)
        cache.put("source2:page1", JSONObject(), 100)
        cache.get("source1:page1", 101)
        cache.put("source1:page2", JSONObject(), 102)
        assertNull(cache.get("source2:page1", 103))
        assertNotNull(cache.get("source1:page1", 103))
        assertNull(cache.get("source1:page3", 103))
    }

    fun `large or image payloads are never retained`() {
        val cache = AgentTaskCache(2, 100)
        cache.put("image", JSONObject().put("data", JSONObject().put("image_data_url", "data:image/jpeg;base64,abc")), 100)
        cache.put("alternate", JSONObject().put("text", "data:image/png;base64,abc"), 100)
        cache.put("large", JSONObject().put("text", "x".repeat(AgentTaskCache.MAX_ENTRY_CHARS)), 100)
        assertEquals(0, cache.size())
    }

    fun `clear removes all task facts`() {
        val cache = AgentTaskCache(2, 100)
        repeat(1_000) { cache.put("$it", JSONObject().put("value", it), 100) }
        assertEquals(2, cache.size())
        cache.clear()
        assertNull(cache.get("999", 101))
        assertEquals(0, cache.size())
    }

    fun verifyAllCases() {
        fun runIsolated(block: (AgentTaskCacheCases) -> Unit) {
            val s = AgentTaskCacheCases()
            block(s)
        }
        runIsolated { s -> s.accountFeedHistoryLiveAndFreshMetricDatasetsAreNeverRetainedAsReusableFacts() }
        runIsolated { s -> s.`cache hit is copied and preserves original observation time`() }
        runIsolated { s -> s.`ttl expires at boundary and future timestamps cannot become fresh`() }
        runIsolated { s -> s.`recently read entries survive lru eviction and different keys cannot reuse a result`() }
        runIsolated { s -> s.`large or image payloads are never retained`() }
        runIsolated { s -> s.`clear removes all task facts`() }
    }
}

private class AgentTaskLimitsCases {
    fun `default limits preserve two minute twelve step budget`() {
        val limits = AgentTaskLimits()
        assertFalse(limits.timeExceeded(100, 120_099))
        assertTrue(limits.timeExceeded(100, 120_100))
        assertTrue(limits.allowsStep(11))
        assertFalse(limits.allowsStep(12))
        assertEquals(120_100L, limits.requestDeadline(100, 119_100, 15_000))
    }

    fun `unlimited dimensions are independent and requests remain finite`() {
        val time = AgentTaskLimits(0, 12)
        assertFalse(time.timeExceeded(100, 9_000_000))
        assertFalse(time.allowsStep(12))
        assertEquals(9_015_000L, time.requestDeadline(100, 9_000_000, 15_000))
        val steps = AgentTaskLimits(120_000, 0)
        assertTrue(steps.timeExceeded(100, 120_100))
        assertTrue(steps.allowsStep(10_000_000))
        assertTrue(AgentTaskLimits(0, 0).allowsStep(Long.MAX_VALUE - 1))
    }

    fun `expired task gives no fresh operation deadline`() {
        assertEquals(120_101L, AgentTaskLimits().requestDeadline(100, 120_101, 15_000))
    }

    fun `large budgets and near maximum timestamps never overflow`() {
        val limits = AgentTaskLimits(Long.MAX_VALUE, 0)
        assertEquals(10L, limits.elapsedMs(Long.MAX_VALUE - 10, Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, limits.requestDeadline(Long.MAX_VALUE - 10, Long.MAX_VALUE - 5, 15_000))
        assertFalse(limits.allowsStep(Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, AgentTaskLimits.saturatedAdd(Long.MAX_VALUE - 1, 100))
    }

    fun `clock rollback does not become a negative elapsed duration`() {
        assertEquals(0L, AgentTaskLimits().elapsedMs(200, 100))
    }

    fun `invalid values and unbounded operation timeout are rejected`() {
        fun rejected(action: () -> Unit) { try { action(); fail("Expected invalid budget") } catch (_: IllegalArgumentException) { } }
        rejected { AgentTaskLimits(-1, 1) }
        rejected { AgentTaskLimits(1, -1) }
        rejected { AgentTaskLimits().allowsStep(-1) }
        rejected { AgentTaskLimits().requestDeadline(0, 1, 0) }
        rejected { AgentTaskLimits(0, 0).requestDeadline(0, 1, Long.MAX_VALUE) }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (AgentTaskLimitsCases) -> Unit) {
            val s = AgentTaskLimitsCases()
            block(s)
        }
        runIsolated { s -> s.`default limits preserve two minute twelve step budget`() }
        runIsolated { s -> s.`unlimited dimensions are independent and requests remain finite`() }
        runIsolated { s -> s.`expired task gives no fresh operation deadline`() }
        runIsolated { s -> s.`large budgets and near maximum timestamps never overflow`() }
        runIsolated { s -> s.`clock rollback does not become a negative elapsed duration`() }
        runIsolated { s -> s.`invalid values and unbounded operation timeout are rejected`() }
    }
}

private class AgentUiPolicyCases {
    fun delayedOrCancelledOperationsCannotAcquireActionPermission() {
        assertTrue(AgentUiPolicy.mayAct(true, false, 100, 101))
        assertFalse(AgentUiPolicy.mayAct(true, false, 101, 101))
        assertFalse(AgentUiPolicy.mayAct(true, true, 100, 101))
        assertFalse(AgentUiPolicy.mayAct(false, false, 100, 101))
    }
    fun sensitiveAccountAndFinancialControlsAreDenied() {
        listOf("修改密码", "更改实名", "确认付款 ¥30", "开通大会员", "更换手机", "输入验证码", "银行卡",
            "change password", "Checkout", "Renew subscription", "delete account", "OTP", "CVV", "PIN", "手机号码").forEach {
            assertTrue(it, AgentUiPolicy.sensitiveControl(it))
        }
        listOf("搜索", "播放视频", "查看合集", "点赞", "返回").forEach { assertFalse(it, AgentUiPolicy.sensitiveControl(it)) }
    }
    fun emptyClickableWrapperCannotHideProtectedDescendant() {
        assertTrue(AgentUiPolicy.protectedControl("", listOf("立即付款"), false))
        assertTrue(AgentUiPolicy.protectedControl("下一步", listOf("请输入身份证"), false))
        assertTrue(AgentUiPolicy.protectedControl("搜索", emptyList(), true))
        assertFalse(AgentUiPolicy.protectedControl("", listOf("执行搜索"), false))
        assertFalse(AgentUiPolicy.mayClick("", false))
        assertFalse(AgentUiPolicy.mayClick("执行搜索", true))
        assertTrue(AgentUiPolicy.mayClick("执行搜索", false))
    }
    fun inputIsPlainBoundedAndNeverCredentialMaterial() {
        assertTrue(AgentUiPolicy.allowInput("搜索", false, "黑神话悟空 官方演示"))
        assertFalse(AgentUiPolicy.allowInput("密码", false, "hello"))
        assertFalse(AgentUiPolicy.allowInput("", true, "hello"))
        assertFalse(AgentUiPolicy.allowInput("", false, "6217000000000000000"))
        assertFalse(AgentUiPolicy.allowInput("", false, "Bearer abc"))
        assertFalse(AgentUiPolicy.allowInput("", false, "x".repeat(501)))
    }
    fun unlabeledFinancialConfirmationAndStaticCredentialsRemainProtected() {
        assertTrue(AgentUiPolicy.sensitiveConfirmation("确定", "金额 ￥30.00"))
        assertTrue(AgentUiPolicy.sensitiveConfirmation("Confirm", "$ 19.99"))
        assertFalse(AgentUiPolicy.sensitiveConfirmation("返回", "金额 ￥30"))
        assertFalse(AgentUiPolicy.sensitiveConfirmation("确定", "搜索关键词"))
        assertEquals("[受保护内容]", AgentUiPolicy.publicText("身份证 110101199001011234"))
        assertEquals("[受保护内容]", AgentUiPolicy.publicText("手机 13800138000"))
        assertEquals("搜索视频", AgentUiPolicy.publicText("搜索视频"))
    }
    fun windowAgeAndTargetAllHaveToMatch() {
        assertTrue(AgentUiPolicy.snapshotCurrent(3, 3, 20_000, true))
        assertFalse(AgentUiPolicy.snapshotCurrent(3, 4, 1, true))
        assertFalse(AgentUiPolicy.snapshotCurrent(3, 3, 60_001, true))
        assertFalse(AgentUiPolicy.snapshotCurrent(3, 3, -1, true))
        assertFalse(AgentUiPolicy.snapshotCurrent(3, 3, 1, false))
    }
    fun promotionUsesSystemRouteAndOnlyOlderVersionsUseOverlay() {
        assertFalse(AgentUiPolicy.systemNotification(27))
        assertFalse(AgentUiPolicy.systemNotification(35))
        assertTrue(AgentUiPolicy.systemNotification(36))
        assertTrue(AgentUiPolicy.systemNotification(37))
    }
    fun toolCoordinatesAndSnapshotsCannotEscapeTheirStrictShape() {
        val snapshot = "12345678-1234-1234-1234-123456789abc"
        val tap = JSONObject().put("snapshot_id", snapshot).put("x", "500").put("y", "700")
        assertTrue(AgentToolCatalog.valid("tap_ui", tap, true))
        assertFalse(AgentToolCatalog.valid("tap_ui", tap, false))
        assertFalse(AgentToolCatalog.valid("tap_ui", JSONObject(tap.toString()).put("x", "-1"), true))
        assertFalse(AgentToolCatalog.valid("tap_ui", JSONObject(tap.toString()).put("x", "NaN"), true))
        assertFalse(AgentToolCatalog.valid("tap_ui", JSONObject(tap.toString()).put("y", "1000"), true))
        assertFalse(AgentToolCatalog.valid("click_ui", JSONObject().put("snapshot_id", snapshot).put("node_id", "../../secret"), false))
        assertFalse(AgentToolCatalog.valid("press_back", JSONObject().put("snapshot_id", "old"), false))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (AgentUiPolicyCases) -> Unit) {
            val s = AgentUiPolicyCases()
            block(s)
        }
        runIsolated { s -> s.delayedOrCancelledOperationsCannotAcquireActionPermission() }
        runIsolated { s -> s.sensitiveAccountAndFinancialControlsAreDenied() }
        runIsolated { s -> s.emptyClickableWrapperCannotHideProtectedDescendant() }
        runIsolated { s -> s.inputIsPlainBoundedAndNeverCredentialMaterial() }
        runIsolated { s -> s.unlabeledFinancialConfirmationAndStaticCredentialsRemainProtected() }
        runIsolated { s -> s.windowAgeAndTargetAllHaveToMatch() }
        runIsolated { s -> s.promotionUsesSystemRouteAndOnlyOlderVersionsUseOverlay() }
        runIsolated { s -> s.toolCoordinatesAndSnapshotsCannotEscapeTheirStrictShape() }
    }
}

private class AgentVisualSnapshotGateCases {
    fun commitWithoutAnyWindowChangeRecordsTheSnapshot() {
        val gate = AgentVisualSnapshotGate()
        val token = gate.token()
        assertTrue(gate.commit(token, "s1", now = 1_000L))
        assertTrue(gate.allows("s1", now = 1_000L))
        assertTrue(gate.allows("s1", now = 61_000L))
        assertFalse("older than 60 s", gate.allows("s1", now = 61_001L))
        assertFalse("another snapshot", gate.allows("s2", now = 1_000L))
        assertFalse("clock going backwards is invalid", gate.allows("s1", now = 999L))
    }

    fun aWindowChangeDuringEncodingStopsTheLateWriteBack() {
        val gate = AgentVisualSnapshotGate()
        gate.commit(gate.token(), "old", 100L)
        // 回调内记下 token，随后进入 worker 编码……
        val token = gate.token()
        // ……编码期间 TYPE_WINDOW_STATE_CHANGED 到达：记录立即失效。
        gate.invalidate()
        assertFalse(gate.allows("old", 150L))
        // 交付：旧实现会在这里把记录写回；现在代次变了，拒绝写回。
        assertFalse(gate.isCurrent(token))
        assertFalse(gate.commit(token, "shot", 200L))
        assertFalse("the late result must not become a valid visual snapshot", gate.allows("shot", 200L))
        assertFalse(gate.allows("old", 200L))
    }

    fun anEventBeforeTheTokenDoesNotBlockTheCommit() {
        val gate = AgentVisualSnapshotGate()
        gate.invalidate()
        gate.invalidate()
        val token = gate.token()
        assertTrue(gate.commit(token, "s", 5L))
        assertTrue(gate.allows("s", 5L))
    }

    fun anyInvalidationAfterCommitDropsTheRecord() {
        val gate = AgentVisualSnapshotGate()
        gate.commit(gate.token(), "s", 1L)
        gate.invalidate()
        assertFalse(gate.allows("s", 2L))
    }

    fun tokensFromBeforeALaterCommitAreNotResurrected() {
        val gate = AgentVisualSnapshotGate()
        val first = gate.token()
        assertTrue(gate.commit(first, "a", 1L))
        // 同一个 token 的第二次提交仍然成立（代次没变）；变化后任何旧 token 都失效。
        assertTrue(gate.commit(first, "b", 2L))
        gate.invalidate()
        assertFalse(gate.commit(first, "c", 3L))
        assertFalse(gate.allows("c", 3L))
    }

    fun differentialAgainstTheSynchronousLegacyModel() {
        // 旧同步实现：整段回调（采样 + 写入）不会被事件打断，事件只能排在回调之前或之后。
        // 把它与「token + 事件可能插在编码期间」的新实现比较：事件没有插入时结果一致；插入时新实现只会更保守（不写回）。
        val random = Random(20261029L)
        repeat(5_000) {
            val gate = AgentVisualSnapshotGate()
            var legacyId: String? = null
            var legacyAt = 0L
            val events = random.nextInt(4)
            repeat(events) { gate.invalidate(); legacyId = null }
            val token = gate.token()
            val interleaved = random.nextBoolean()
            if (interleaved) gate.invalidate() // 事件落在编码期间
            val committed = gate.commit(token, "shot", 10L)
            if (!interleaved) {
                legacyId = "shot"; legacyAt = 10L
                assertTrue(committed)
            } else {
                assertFalse(committed)
            }
            val now = 10L + random.nextInt(70_000)
            val legacyAllows = legacyId == "shot" && now - legacyAt in 0..60_000
            assertEquals("interleaved=$interleaved events=$events now=$now", legacyAllows, gate.allows("shot", now))
        }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (AgentVisualSnapshotGateCases) -> Unit) {
            val s = AgentVisualSnapshotGateCases()
            block(s)
        }
        runIsolated { s -> s.commitWithoutAnyWindowChangeRecordsTheSnapshot() }
        runIsolated { s -> s.aWindowChangeDuringEncodingStopsTheLateWriteBack() }
        runIsolated { s -> s.anEventBeforeTheTokenDoesNotBlockTheCommit() }
        runIsolated { s -> s.anyInvalidationAfterCommitDropsTheRecord() }
        runIsolated { s -> s.tokensFromBeforeALaterCommitAreNotResurrected() }
        runIsolated { s -> s.differentialAgainstTheSynchronousLegacyModel() }
    }
}
