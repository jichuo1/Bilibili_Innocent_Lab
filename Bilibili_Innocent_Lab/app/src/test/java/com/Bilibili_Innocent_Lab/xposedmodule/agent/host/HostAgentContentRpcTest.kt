package com.Bilibili_Innocent_Lab.xposedmodule.agent.host

import com.bapis.bilibili.app.archive.v1.Author
import com.bapis.bilibili.app.interfaces.v1.Arc
import com.bapis.bilibili.app.interfaces.v1.CardUGC
import com.bapis.bilibili.app.interfaces.v1.Cursor
import com.bapis.bilibili.app.interfaces.v1.CursorItem
import com.bapis.bilibili.app.interfaces.v1.CursorV2Reply
import com.bapis.bilibili.app.interfaces.v1.HistoryMoss
import com.bapis.bilibili.app.interfaces.v1.SearchArchiveReply
import com.bapis.bilibili.app.interfaces.v1.SpaceMoss
import com.bilibili.lib.moss.api.CallOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HostAgentContentRpcTest {
    private fun rpc(loader: ClassLoader = javaClass.classLoader!!) = HostAgentContentRpc(loader)
    private fun item(id: Long, title: String = "视频$id", uri: String = "bilibili://video/$id", ugc: Boolean = true) =
        CursorItem(id, title, uri, 100L + id, if (ugc) CardUGC(42, "作者", 180) else null)
    private fun archive(id: Long, mid: Long? = 42, uri: String = "bilibili://video/$id") = Arc(
        com.bapis.bilibili.app.archive.v1.Arc(id, "投稿$id", 120, mid?.let { Author(it, "作者") }), uri)
    private fun failure(block: () -> Unit): String? = runCatching(block).exceptionOrNull()?.message

    fun reset() {
        CallOptions.ignoreTimeout = false
        HistoryMoss.calls = 0
        HistoryMoss.lastRequest = null
        HistoryMoss.lastTimeout = null
        HistoryMoss.reply = CursorV2Reply(emptyList(), null, false)
        SpaceMoss.calls = 0
        SpaceMoss.lastRequest = null
        SpaceMoss.lastTimeout = null
        SpaceMoss.reply = SearchArchiveReply(emptyList(), 0)
    }

    fun `missing host schema disables only affected capability and never invokes network`() {
        val parent = javaClass.classLoader!!
        val missing = object : ClassLoader(parent) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == "com.bapis.bilibili.app.interfaces.v1.CursorItem") throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }
        val content = rpc(missing)
        assertFalse(content.historyAvailable)
        assertTrue(content.userVideosAvailable)
        assertEquals("history_rpc_unavailable", failure { content.history(null, 1000) })
        assertEquals(0, HistoryMoss.calls)
        assertFalse(content.relatedAvailable)
        assertFalse(content.commentsAvailable)
        assertEquals("related_rpc_schema_unverified", failure { content.related("av1", 1000) })
        assertEquals("comments_rpc_unavailable", failure { content.comments("av1", null, 1000) })
    }

    fun `history projects twenty video facts and never exposes raw routing parameters`() {
        HistoryMoss.reply = CursorV2Reply(listOf(item(900, ugc = false), item(800, uri = "bilibili://video/801")) +
            (1L..100L).map { item(it, "<em>视频</em>\u0000" + "长".repeat(500), "bilibili://video/$it?secret=untrusted") },
            Cursor(1000, 3), true)
        val result = rpc().history(null, 100_000)
        assertEquals(1, HistoryMoss.calls)
        assertEquals(12_000L, HistoryMoss.lastTimeout)
        assertFalse(checkNotNull(HistoryMoss.lastRequest).local)
        assertEquals(0L, HistoryMoss.lastRequest?.cursor?.getMax())
        assertEquals(0, HistoryMoss.lastRequest?.cursor?.getMaxTp())
        assertEquals(20, result.videos.size)
        assertEquals("av1", result.videos.first().id)
        assertEquals("host_rpc", result.data.getString("source"))
        assertEquals("history", result.data.getString("dataset"))
        val rows = result.data.getJSONArray("items")
        assertEquals(20, rows.length())
        assertEquals("42", rows.getJSONObject(0).getString("author_uid"))
        assertEquals(180L, rows.getJSONObject(0).getLong("duration_seconds"))
        assertTrue(result.videos.all { checkNotNull(it.title).length <= 300 })
        assertFalse(result.data.toString().contains("secret="))
        assertFalse(result.data.toString().contains("<em>"))
        assertTrue(result.data.getBoolean("truncated"))
        assertEquals("unavailable_truncated_page", result.data.getString("pagination_status"))
        assertNull(result.next)
    }

    fun `history uses opaque cursor in exactly one RPC and stops repeating backend cursor`() {
        HistoryMoss.reply = CursorV2Reply(listOf(item(1), item(1), item(2)), Cursor(123, 2), true)
        val content = rpc()
        assertEquals("hc1:123:2", content.history(null, 100).next)
        val result = content.history("hc1:123:2", 100)
        assertEquals(100L, HistoryMoss.lastTimeout)
        assertEquals(123L, HistoryMoss.lastRequest?.cursor?.getMax())
        assertEquals(2, HistoryMoss.lastRequest?.cursor?.getMaxTp())
        assertEquals(listOf("av1", "av2"), result.videos.map { it.id })
        assertNull(result.next)
        HistoryMoss.reply = CursorV2Reply(listOf(item(3)), Cursor(0, -1), true)
        val fresh = content.history(null, 100)
        assertEquals(listOf("av3"), fresh.videos.map { it.id })
        assertNull(fresh.next)
        assertEquals(3, HistoryMoss.calls)
    }

    fun `cursor and user ID failures happen before any native request`() {
        val content = rpc()
        for (raw in listOf("", "uv1:2", "hc1:0:0", "hc1:-1:0", "hc1:9223372036854775808:0", "hc1:1:2147483648", "hc1:1:1\n")) {
            assertEquals("invalid_history_cursor", failure { content.history(raw, 100) })
        }
        for (raw in listOf("", "hc1:1:0", "uv1:0", "uv1:1000001", "uv1:1;url=https://example.org")) {
            assertEquals("invalid_user_videos_cursor", failure { content.userVideos("42", raw, 100) })
        }
        for (raw in listOf("", "0", "-1", "+42", "042", "9223372036854775808", "42\n")) {
            assertEquals("invalid_user_id", failure { content.userVideos(raw, null, 100) })
        }
        assertEquals(0, HistoryMoss.calls)
        assertEquals(0, SpaceMoss.calls)
    }

    fun `UP archive query binds exact author requests twenty and rejects identity conflicts`() {
        SpaceMoss.reply = SearchArchiveReply(listOf(archive(500, 43), archive(600, uri = "bilibili://video/601"),
            archive(0)) + (1L..100L).map { archive(it) }, 100)
        val result = rpc().userVideos("42", null, 0)
        val request = checkNotNull(SpaceMoss.lastRequest)
        assertEquals(42L, request.mid)
        assertEquals(1L, request.pn)
        assertEquals(20L, request.ps)
        assertEquals("", request.keyword)
        assertEquals(1L, SpaceMoss.lastTimeout)
        assertEquals(1, SpaceMoss.calls)
        assertEquals(20, result.videos.size)
        assertEquals("av1", result.videos.first().id)
        assertEquals("投稿1", result.videos.first().title)
        assertEquals("42", result.data.getString("requested_user_id"))
        assertEquals("user_videos", result.data.getString("dataset"))
        assertEquals("returned_page", result.data.getString("coverage"))
        assertTrue(result.data.getBoolean("truncated"))
        assertNull(result.next)
    }

    fun `UP pagination is bounded and never fabricates unknown owner or terminal pages`() {
        val content = rpc()
        SpaceMoss.reply = SearchArchiveReply(listOf(archive(1, null)), 40)
        assertEquals("uv1:2", content.userVideos("42", null, 100).next)
        val result = content.userVideos("42", "uv1:2", 100)
        assertEquals(2L, SpaceMoss.lastRequest?.pn)
        assertNull(result.videos.single().mid)
        assertTrue(result.data.getJSONArray("items").getJSONObject(0).isNull("author_uid"))
        assertNull(result.next)
        SpaceMoss.reply = SearchArchiveReply(emptyList(), Long.MAX_VALUE)
        assertNull(content.userVideos("42", "uv1:1000000", 100).next)
        assertNull(content.userVideos("42", null, 100).next)
    }

    fun `native timeout drift is rejected without executing either endpoint`() {
        val content = rpc()
        assertTrue(content.historyAvailable)
        assertTrue(content.userVideosAvailable)
        CallOptions.ignoreTimeout = true
        assertEquals("rpc_timeout_unavailable", failure { content.history(null, 100) })
        assertEquals("rpc_timeout_unavailable", failure { content.userVideos("42", null, 100) })
        assertEquals(0, HistoryMoss.calls)
        assertEquals(0, SpaceMoss.calls)
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (HostAgentContentRpcTest) -> Unit) {
            val s = HostAgentContentRpcTest()
            s.reset()
            block(s)
        }
        runIsolated { s -> s.`missing host schema disables only affected capability and never invokes network`() }
        runIsolated { s -> s.`history projects twenty video facts and never exposes raw routing parameters`() }
        runIsolated { s -> s.`history uses opaque cursor in exactly one RPC and stops repeating backend cursor`() }
        runIsolated { s -> s.`cursor and user ID failures happen before any native request`() }
        runIsolated { s -> s.`UP archive query binds exact author requests twenty and rejects identity conflicts`() }
        runIsolated { s -> s.`UP pagination is bounded and never fabricates unknown owner or terminal pages`() }
        runIsolated { s -> s.`native timeout drift is rejected without executing either endpoint`() }
    }
}
