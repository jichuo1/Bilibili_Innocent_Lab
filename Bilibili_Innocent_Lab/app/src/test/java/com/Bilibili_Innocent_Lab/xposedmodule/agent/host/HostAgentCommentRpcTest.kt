package com.Bilibili_Innocent_Lab.xposedmodule.agent.host

import com.Bilibili_Innocent_Lab.xposedmodule.agent.host.fixtures.comments.FeedPaginationReply
import com.Bilibili_Innocent_Lab.xposedmodule.agent.host.fixtures.comments.MainListReply
import com.Bilibili_Innocent_Lab.xposedmodule.agent.host.fixtures.comments.ReplyInfo
import com.Bilibili_Innocent_Lab.xposedmodule.agent.host.fixtures.comments.ReplyMoss
import com.bilibili.lib.moss.api.CallOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HostAgentCommentRpcTest {
    private val base = "com.Bilibili_Innocent_Lab.xposedmodule.agent.host.fixtures.comments."
    private fun rpc(loader: ClassLoader = javaClass.classLoader!!) = HostAgentContentRpc(loader, base, base, base + "SubjectType")
    private fun comment(id: Long, oid: Long = 123, type: Long = 1, mid: Long = 42, likes: Long = 2, count: Long = 3) =
        ReplyInfo(id, oid, type, "<em>评论</em>\u0000" + "长".repeat(2000), mid, likes, count)
    private fun failure(block: () -> Unit): String? = runCatching(block).exceptionOrNull()?.message

    fun reset() {
        CallOptions.ignoreTimeout = false
        ReplyMoss.calls = 0
        ReplyMoss.lastRequest = null
        ReplyMoss.lastTimeout = null
        ReplyMoss.reply = MainListReply(emptyList(), null)
    }

    fun `comments request verified manuscript type time sort and explicit page size`() {
        ReplyMoss.reply = MainListReply(listOf(comment(900, oid = 124), comment(901, type = 17)) +
            (1L..100L).map { comment(it) }, FeedPaginationReply("native-next"))
        val content = rpc()
        assertTrue(content.commentsAvailable)
        val page = content.comments("av123", null, 20_000)
        val request = checkNotNull(ReplyMoss.lastRequest)
        assertEquals(123L, request.oid)
        assertEquals(1L, request.type)
        assertEquals(2, request.mode)
        assertEquals(20, request.pagination?.size)
        assertEquals("", request.pagination?.offset)
        assertEquals(12_000L, ReplyMoss.lastTimeout)
        assertEquals(1, ReplyMoss.calls)
        val comments = page.data.getJSONArray("comments")
        assertEquals(20, comments.length())
        assertEquals("1", comments.getJSONObject(0).getString("comment_id"))
        assertEquals("av123", comments.getJSONObject(0).getString("video_id"))
        assertEquals("42", comments.getJSONObject(0).getString("author_uid"))
        assertTrue(comments.getJSONObject(0).getString("message").length <= 1000)
        assertFalse(page.data.toString().contains("<em>"))
        assertTrue(page.data.getBoolean("truncated"))
        assertNull(page.next)
        assertEquals("returned_top_level_replies", page.data.getString("coverage"))
    }

    fun `comment pagination sends native offset exactly once and rejects repeated cursor`() {
        val content = rpc()
        ReplyMoss.reply = MainListReply(listOf(comment(1)), FeedPaginationReply("native-next"))
        val first = content.comments("av123", null, 100)
        assertEquals("native-next", first.next)
        ReplyMoss.reply = MainListReply(listOf(comment(2)), FeedPaginationReply("native-next"))
        val next = content.comments("av123", "native-next", 100)
        assertEquals("native-next", ReplyMoss.lastRequest?.pagination?.offset)
        assertNull(next.next)
        assertEquals("1", first.data.getJSONArray("comments").getJSONObject(0).getString("comment_id"))
        assertEquals("2", next.data.getJSONArray("comments").getJSONObject(0).getString("comment_id"))
        assertEquals(2, ReplyMoss.calls)
    }

    fun `wrong subject duplicates and unknown counters do not become comment facts`() {
        ReplyMoss.reply = MainListReply(listOf(comment(0), comment(1, oid = 999), comment(2, type = 17),
            comment(3, mid = 0, likes = -1, count = -1), comment(3)), null)
        val comments = rpc().comments("av123", null, 100).data.getJSONArray("comments")
        assertEquals(1, comments.length())
        val row = comments.getJSONObject(0)
        assertEquals("3", row.getString("comment_id"))
        assertTrue(row.isNull("author_uid"))
        assertTrue(row.isNull("author"))
        assertTrue(row.isNull("likes"))
        assertTrue(row.isNull("reply_count"))
    }

    fun `missing semantic enum fails closed instead of using numeric defaults`() {
        val loader = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == base + "SubjectType") throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }
        val content = rpc(loader)
        assertFalse(content.commentsAvailable)
        assertEquals("comments_rpc_unavailable", failure { content.comments("av123", null, 100) })
        assertEquals(0, ReplyMoss.calls)
    }

    fun `BVID must be resolved by detail facts and malformed cursors never issue request`() {
        val content = rpc()
        for (id in listOf("BV1xxxxxxxxx", "av0", "av-1", "https://example.org/video/123")) {
            assertEquals("comment_aid_required", failure { content.comments(id, null, 100) })
        }
        for (cursor in listOf("", "x\n", "x".repeat(2049))) {
            assertEquals("invalid_comments_cursor", failure { content.comments("av123", cursor, 100) })
        }
        assertEquals(0, ReplyMoss.calls)
    }

    fun `comments reject timeout drift before issuing a native read`() {
        CallOptions.ignoreTimeout = true
        assertEquals("rpc_timeout_unavailable", failure { rpc().comments("av123", null, 100) })
        assertEquals(0, ReplyMoss.calls)
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (HostAgentCommentRpcTest) -> Unit) {
            val s = HostAgentCommentRpcTest()
            s.reset()
            block(s)
        }
        runIsolated { s -> s.`comments request verified manuscript type time sort and explicit page size`() }
        runIsolated { s -> s.`comment pagination sends native offset exactly once and rejects repeated cursor`() }
        runIsolated { s -> s.`wrong subject duplicates and unknown counters do not become comment facts`() }
        runIsolated { s -> s.`missing semantic enum fails closed instead of using numeric defaults`() }
        runIsolated { s -> s.`BVID must be resolved by detail facts and malformed cursors never issue request`() }
        runIsolated { s -> s.`comments reject timeout drift before issuing a native read`() }
    }
}
