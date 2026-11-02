package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight

import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InsightStreamingRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val source = AgentModelSource(1, "https://example.test/v1", "secret", "model")
    private val video = InsightVideo("BV1xx411c7mD", 1)
    private val context = InsightContext(video, 900, "title", "description", listOf(
        InsightCue(0.0, 300.0, "开始"), InsightCue(300.0, 600.0, "原理"), InsightCue(600.0, 900.0, "结论")))
    private fun payload(text: String) = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
        .put("message", JSONObject().put("role", "assistant").put("content", text)))).toString()
    private fun client(emit: ((String) -> Unit) -> String) = AgentModelClient(object : AgentHttpTransport {
        override fun post(source: AgentModelSource, body: ByteArray, timeoutMs: Int, cancelled: () -> Boolean): String =
            error("must request streaming")
        override fun postStreaming(source: AgentModelSource, body: ByteArray, timeoutMs: Int, cancelled: () -> Boolean,
            onDelta: (String) -> Unit): String {
            assertTrue(JSONObject(body.toString(Charsets.UTF_8)).getBoolean("stream"))
            return payload(emit(onDelta))
        }
    })
    @Test fun `preview arrives before completion and only validated result becomes a reusable cache`() {
        val root = temporary.newFolder(); val updates = mutableListOf<String>(); var networkFinished = false
        val json = """{"topics":[{"title":"方向","summary":"具体讨论"}]}"""
        val repo = InsightRepository(source, InsightCache(root), { context.copy(comments = listOf("评论")) }, client { emit ->
            json.forEach { emit(it.toString()); assertTrue(root.listFiles().orEmpty().isEmpty()) }
            networkFinished = true; json
        })
        val result = repo.generate(video, InsightMode.COMMENTS, onProgress = {
            assertFalse(networkFinished); updates += it
        })
        assertTrue(updates.any { it.contains("具体") }); assertEquals("方向\n具体讨论", result.output.text)
        assertEquals(result, repo.cached(video, InsightMode.COMMENTS)); assertEquals(1, root.listFiles()!!.size)
    }
    @Test fun `interrupted partial answer cannot become a cache entry`() {
        val root = temporary.newFolder(); val updates = mutableListOf<String>()
        val repo = InsightRepository(source, InsightCache(root), { context }, client { emit ->
            emit("""{"overview":"已收到一部分""" )
            throw AgentModelException(AgentModelException.Reason.NETWORK)
        })
        assertTrue(runCatching { repo.generate(video, InsightMode.SUMMARY, onProgress = updates::add) }.isFailure)
        assertEquals(listOf("已收到一部分"), updates)
        assertTrue(root.listFiles().orEmpty().isEmpty()); assertNull(repo.cached(video, InsightMode.SUMMARY))
    }
    @Test fun `format repair resets the preview and stores only the corrected result`() {
        val root = temporary.newFolder(); val updates = mutableListOf<String>(); var calls = 0
        val repo = InsightRepository(source, InsightCache(root), { context.copy(comments = listOf("评论")) }, client { emit ->
            calls++
            val json = """{"topics":[{"title":"方向","summary":"${if (calls == 1) "坏片段},{" else "正确讨论"}"}]}"""
            emit(json); json
        })
        assertEquals("方向\n正确讨论", repo.generate(video, InsightMode.COMMENTS, onProgress = updates::add).output.text)
        assertTrue(updates.contains("")); assertEquals(2, calls)
        assertFalse(root.listFiles()!!.single().readText().contains("坏片段"))
    }
    @Test fun `cancellation discards late deltas without caching or completing the answer`() {
        val root = temporary.newFolder(); val updates = mutableListOf<String>(); var cancelled = false
        val repo = InsightRepository(source, InsightCache(root), { context }, client { emit ->
            emit("""{"answer":"早期正文""" ); cancelled = true; emit("迟到内容"); "{}"
        })
        assertTrue(runCatching { repo.generate(video, InsightMode.ASK, "问题", onProgress = updates::add) { cancelled } }.isFailure)
        assertTrue(updates.none { it.contains("迟到") }); assertTrue(root.listFiles().orEmpty().isEmpty())
    }
    @Test fun `cached summaries bypass context network and preview animation callbacks`() {
        val cache = InsightCache(temporary.newFolder()); val entry = InsightCache.Entry(InsightOutput("已缓存"), 900, true)
        cache.write(InsightCache.key(video, source, InsightMode.SUMMARY), entry)
        val repo = InsightRepository(source, cache, { error("no context") }, client { error("no network") })
        assertEquals(entry, repo.generate(video, InsightMode.SUMMARY, onProgress = { error("no preview") }))
    }
}
