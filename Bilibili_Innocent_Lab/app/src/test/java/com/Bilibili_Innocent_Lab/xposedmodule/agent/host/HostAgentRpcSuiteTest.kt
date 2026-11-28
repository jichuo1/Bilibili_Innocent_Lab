package com.Bilibili_Innocent_Lab.xposedmodule.agent.host

import com.Bilibili_Innocent_Lab.xposedmodule.agent.host.fixtures.comments.FeedPaginationReply
import com.Bilibili_Innocent_Lab.xposedmodule.agent.host.fixtures.comments.MainListReply
import com.Bilibili_Innocent_Lab.xposedmodule.agent.host.fixtures.comments.ReplyInfo
import com.Bilibili_Innocent_Lab.xposedmodule.agent.host.fixtures.comments.ReplyMoss
import com.Bilibili_Innocent_Lab.xposedmodule.agent.host.fixtures.dynamic.CardVideoUpList
import com.Bilibili_Innocent_Lab.xposedmodule.agent.host.fixtures.dynamic.DynAllReply
import com.Bilibili_Innocent_Lab.xposedmodule.agent.host.fixtures.dynamic.DynamicItem
import com.Bilibili_Innocent_Lab.xposedmodule.agent.host.fixtures.dynamic.DynamicList
import com.Bilibili_Innocent_Lab.xposedmodule.agent.host.fixtures.dynamic.DynamicMoss
import com.Bilibili_Innocent_Lab.xposedmodule.agent.host.fixtures.dynamic.Extend
import com.Bilibili_Innocent_Lab.xposedmodule.agent.host.fixtures.dynamic.MdlDynArchive
import com.Bilibili_Innocent_Lab.xposedmodule.agent.host.fixtures.dynamic.Module
import com.Bilibili_Innocent_Lab.xposedmodule.agent.host.fixtures.dynamic.ModuleDesc
import com.Bilibili_Innocent_Lab.xposedmodule.agent.host.fixtures.dynamic.ModuleDynamic
import com.Bilibili_Innocent_Lab.xposedmodule.agent.host.fixtures.dynamic.UpListItem
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
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostAgentRpcSuiteTest {

    @Test
    fun commentContentAndMossFactoryRpcContracts() {
        HostAgentCommentRpcCases().verifyAllCases()
        HostAgentContentRpcCases().verifyAllCases()
        HostAgentMossFactoryCases().verifyAllCases()
    }

    @Test
    fun dynamicFeedLiveAndVideoIdentityRpcContracts() {
        HostAgentDynamicRpcCases().verifyAllCases()
    }

    @Test
    fun discoveryProjectionRankingPopularRegionSessionAndWindowPolicies() {
        HostAgentDiscoveryRpcCases().verifyAllCases()
    }
}

private class HostAgentCommentRpcCases {
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

    fun verifyAllCases() {
        fun runIsolated(block: (HostAgentCommentRpcCases) -> Unit) {
            val s = HostAgentCommentRpcCases()
            s.reset()
            try {
                block(s)
            } finally {
                s.reset()
            }
        }
        runIsolated { s -> s.`comments request verified manuscript type time sort and explicit page size`() }
        runIsolated { s -> s.`comment pagination sends native offset exactly once and rejects repeated cursor`() }
        runIsolated { s -> s.`wrong subject duplicates and unknown counters do not become comment facts`() }
        runIsolated { s -> s.`missing semantic enum fails closed instead of using numeric defaults`() }
        runIsolated { s -> s.`BVID must be resolved by detail facts and malformed cursors never issue request`() }
        runIsolated { s -> s.`comments reject timeout drift before issuing a native read`() }
    }
}

private class HostAgentContentRpcCases {
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

    fun verifyAllCases() {
        fun runIsolated(block: (HostAgentContentRpcCases) -> Unit) {
            val s = HostAgentContentRpcCases()
            s.reset()
            try {
                block(s)
            } finally {
                s.reset()
            }
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

private class HostAgentMossFactoryCases {
    class Moss(val address: String, val port: Int, val options: CallOptions)
    class NoTimeoutMoss

    fun reset() { CallOptions.ignoreTimeout = false }

    fun `native timeout preserves short deadline and caps expensive calls`() {
        val factory = checkNotNull(HostAgentMossFactory.resolve(javaClass.classLoader!!, Moss::class.java))
        val short = factory.create(125) as Moss
        assertEquals("grpc.biliapi.net", short.address)
        assertEquals(443, short.port)
        assertEquals(125L, short.options.getTimeoutInMs())
        assertEquals(12_000L, (factory.create(120_000) as Moss).options.getTimeoutInMs())
        assertEquals(1L, (factory.create(0) as Moss).options.getTimeoutInMs())
    }

    fun `missing timeout constructor does not fall back to unbounded RPC`() {
        assertNull(HostAgentMossFactory.resolve(javaClass.classLoader!!, NoTimeoutMoss::class.java))
    }

    fun `timeout silently ignored by a drifted host is rejected before creating moss`() {
        val factory = checkNotNull(HostAgentMossFactory.resolve(javaClass.classLoader!!, Moss::class.java))
        CallOptions.ignoreTimeout = true
        val failure = runCatching { factory.create(1000) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("rpc_timeout_unavailable", failure?.message)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostAgentMossFactoryCases) -> Unit) {
            val s = HostAgentMossFactoryCases()
            s.reset()
            try {
                block(s)
            } finally {
                s.reset()
            }
        }
        runIsolated { s -> s.`native timeout preserves short deadline and caps expensive calls`() }
        runIsolated { s -> s.`missing timeout constructor does not fall back to unbounded RPC`() }
        runIsolated { s -> s.`timeout silently ignored by a drifted host is rejected before creating moss`() }
    }
}

private class HostAgentDynamicRpcCases {
    private val fixtureBase = "com.Bilibili_Innocent_Lab.xposedmodule.agent.host.fixtures.dynamic."
    private fun rpc(loader: ClassLoader = javaClass.classLoader!!) = HostAgentDynamicRpc(loader, fixtureBase)
    private fun post(id: Int, text: String = "正文$id") = DynamicItem(Extend(id.toString(), 42, "原作者"), listOf(Module(ModuleDesc(text))))
    private fun user(uid: Long, state: Int, uri: String = "bilibili://live/123?ignored=secret") = UpListItem(uid, "UP$uid", state, uri)
    private fun failure(block: () -> Unit): String? = runCatching(block).exceptionOrNull()?.message
    private fun missing(name: String): ClassLoader = object : ClassLoader(javaClass.classLoader) {
        override fun loadClass(value: String, resolve: Boolean): Class<*> {
            if (value == fixtureBase + name) throw ClassNotFoundException(value)
            return super.loadClass(value, resolve)
        }
    }

    fun reset() {
        CallOptions.ignoreTimeout = false
        DynamicMoss.calls = 0
        DynamicMoss.lastRequest = null
        DynamicMoss.lastTimeout = null
        DynamicMoss.reply = DynAllReply(null, null)
    }

    fun `following requests exactly one native page and projects bounded post text and UP states`() {
        DynamicMoss.reply = DynAllReply(DynamicList((1..30).map { post(it, "<em>正文</em>\u0000" + "长".repeat(3000)) }, true, "next"),
            CardVideoUpList(listOf(user(1, 0), user(2, 1), user(3, 2), user(4, 99)), listOf(user(2, 1))))
        val content = rpc()
        assertTrue(content.followingAvailable)
        assertTrue(content.liveStatusAvailable)
        val page = content.following(null, 120_000)
        assertEquals(1, DynamicMoss.calls)
        assertEquals(12_000L, DynamicMoss.lastTimeout)
        assertEquals("", DynamicMoss.lastRequest?.offset)
        assertEquals(1, DynamicMoss.lastRequest?.page)
        assertEquals(0, DynamicMoss.lastRequest?.refresh)
        assertEquals("following_feed", page.data.getString("dataset"))
        val posts = page.data.getJSONArray("posts")
        assertEquals(20, posts.length())
        assertEquals("42", posts.getJSONObject(0).getString("author_uid"))
        assertTrue(posts.getJSONObject(0).isNull("author"))
        assertEquals("原作者", posts.getJSONObject(0).getString("original_author_name"))
        assertTrue(posts.getJSONObject(0).getString("text").length <= 1000)
        assertFalse(page.data.toString().contains("ignored="))
        val users = page.data.getJSONArray("users")
        assertEquals(4, users.length())
        assertEquals(listOf("offline", "live", "replay", "unknown"), (0 until users.length()).map { users.getJSONObject(it).getString("live_status") })
        assertEquals("123", users.getJSONObject(0).getString("live_room_id"))
        assertTrue(page.data.getBoolean("truncated"))
        assertNull(page.next)
    }

    fun `live lookup stays in returned UP list and missing UID never means offline`() {
        DynamicMoss.reply = DynAllReply(null, CardVideoUpList(listOf(user(42, 2)), emptyList()))
        val content = rpc()
        val found = content.liveStatus("42", 100)
        assertEquals("replay", found.getString("live_status"))
        assertEquals("returned_page", found.getString("coverage"))
        assertEquals("current_account_feed_up_list", found.getString("scope"))
        assertEquals(1, DynamicMoss.calls)
        val absent = content.liveStatus("777", 100)
        assertEquals("unknown", absent.getString("live_status"))
        assertEquals("not_in_returned_up_list", absent.getString("reason"))
        assertTrue(absent.isNull("live_room_id"))
        assertEquals(1, absent.getInt("checked_pages"))
        assertEquals(2, DynamicMoss.calls)
    }

    fun `dynamic archives expose real video identities and never attribute forwarder as video owner`() {
        fun video(aid: Long, uri: String = "bilibili://video/$aid", pgc: Boolean = false) = Module(null,
            ModuleDynamic(MdlDynArchive(aid, "", uri, "视频$aid", 123, pgc)))
        val items = listOf(DynamicItem(Extend("1", 42, "转发者"), listOf(video(600, "bilibili://video/601"), video(700, pgc = true)))) +
            (1L..25L).map { DynamicItem(Extend((it + 1).toString(), 42, "转发者"), listOf(video(it), video(it))) }
        DynamicMoss.reply = DynAllReply(DynamicList(items, false, ""), null)
        val result = rpc().following(null, 100)
        assertEquals("available", result.data.getString("video_identity_state"))
        assertEquals((1..19).map { "av$it" }, result.videos.map { it.id })
        assertTrue(result.videos.all { it.mid == null && it.author == null })
        assertEquals("视频1", result.videos.first().title)
        assertEquals("123", result.videos.first().duration)
        val videos = result.data.getJSONArray("videos")
        assertEquals(19, videos.length())
        assertTrue(videos.getJSONObject(0).isNull("author_uid"))
        assertFalse(result.videos.any { it.id == "av600" || it.id == "av700" })
    }

    fun `positive room identity cannot turn unknown wire state into live`() {
        DynamicMoss.reply = DynAllReply(null, CardVideoUpList(listOf(user(42, 999)), emptyList()))
        val result = rpc().liveStatus("42", 100)
        assertEquals("unknown", result.getString("live_status"))
        assertEquals("123", result.getString("live_room_id"))
        assertEquals("unavailable", result.getString("live_status_source"))
    }

    fun `schema drift keeps following text available but disables unsupported live lookup`() {
        val content = rpc(missing("LiveState"))
        assertTrue(content.followingAvailable)
        assertFalse(content.liveStatusAvailable)
        assertEquals("live_status_rpc_unavailable", failure { content.liveStatus("42", 100) })
        assertEquals(0, DynamicMoss.calls)
        DynamicMoss.reply = DynAllReply(DynamicList(listOf(post(1)), false, ""), CardVideoUpList(listOf(user(42, 1)), emptyList()))
        assertEquals("unknown", content.following(null, 100).data.getJSONArray("users").getJSONObject(0).getString("live_status"))
        val missingRefresh = rpc(missing("Refresh"))
        assertFalse(missingRefresh.followingAvailable)
        assertFalse(missingRefresh.liveStatusAvailable)
    }

    fun `explicit pagination uses native offset and history refresh without retaining replies`() {
        DynamicMoss.reply = DynAllReply(DynamicList(listOf(post(1)), true, "native-offset"), null)
        val content = rpc()
        val first = content.following(null, 100)
        val next = checkNotNull(first.next)
        DynamicMoss.reply = DynAllReply(DynamicList(listOf(post(2)), true, "native-offset"), null)
        val second = content.following(next, 100)
        assertEquals(2, DynamicMoss.lastRequest?.page)
        assertEquals(1, DynamicMoss.lastRequest?.refresh)
        assertEquals("native-offset", DynamicMoss.lastRequest?.offset)
        assertNull(second.next)
        assertEquals("1", first.data.getJSONArray("posts").getJSONObject(0).getString("post_id"))
        assertEquals("2", second.data.getJSONArray("posts").getJSONObject(0).getString("post_id"))
        assertEquals(2, DynamicMoss.calls)
    }

    fun `UP projection and lookup scans have hard limits`() {
        DynamicMoss.reply = DynAllReply(null, CardVideoUpList((1L..150L).map { user(it, 1) }, emptyList()))
        val content = rpc()
        val following = content.following(null, 100)
        assertEquals(20, following.data.getJSONArray("users").length())
        assertTrue(following.data.getBoolean("users_truncated"))
        assertEquals("live", content.liveStatus("80", 100).getString("live_status"))
        assertEquals("unknown", content.liveStatus("101", 100).getString("live_status"))
    }

    fun `malformed cursors and account selector are rejected before requesting data`() {
        val content = rpc()
        for (raw in listOf("", "[]", "{}", "{\"offset\":\"x\",\"page\":1}", "{\"offset\":\"x\",\"page\":2.5}",
            "{\"offset\":\"x\",\"page\":1000001}", "{\"offset\":\"x\",\"page\":2,\"url\":\"https://example.org\"}")) {
            assertEquals("invalid_following_cursor", failure { content.following(raw, 100) })
        }
        for (offset in listOf("", "x\n", "x".repeat(1025))) {
            assertEquals("invalid_following_cursor", failure { content.following(JSONObject().put("offset", offset).put("page", 2).toString(), 100) })
        }
        assertEquals("invalid_user_id", failure { content.liveStatus("-1", 100) })
        assertEquals(0, DynamicMoss.calls)
    }

    fun `room identity extraction rejects foreign credentials encoded paths and overflow`() {
        assertEquals("123", HostAgentDynamicLivePolicy.roomId("bilibili://live/123?unused=x"))
        for (value in listOf("https://example.org/live/123", "bilibili://video/123", "bilibili://u@live/123", "bilibili://live:443/123",
            "bilibili://live/%31%32%33", "bilibili://live/0", "bilibili://live/9223372036854775808")) {
            assertNull(HostAgentDynamicLivePolicy.roomId(value))
        }
    }

    fun `native timeout must be honored before executing dynamic read`() {
        val content = rpc()
        CallOptions.ignoreTimeout = true
        assertEquals("rpc_timeout_unavailable", failure { content.following(null, 100) })
        assertEquals("rpc_timeout_unavailable", failure { content.liveStatus("42", 100) })
        assertEquals(0, DynamicMoss.calls)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostAgentDynamicRpcCases) -> Unit) {
            val s = HostAgentDynamicRpcCases()
            s.reset()
            try {
                block(s)
            } finally {
                s.reset()
            }
        }
        runIsolated { s -> s.`following requests exactly one native page and projects bounded post text and UP states`() }
        runIsolated { s -> s.`live lookup stays in returned UP list and missing UID never means offline`() }
        runIsolated { s -> s.`dynamic archives expose real video identities and never attribute forwarder as video owner`() }
        runIsolated { s -> s.`positive room identity cannot turn unknown wire state into live`() }
        runIsolated { s -> s.`schema drift keeps following text available but disables unsupported live lookup`() }
        runIsolated { s -> s.`explicit pagination uses native offset and history refresh without retaining replies`() }
        runIsolated { s -> s.`UP projection and lookup scans have hard limits`() }
        runIsolated { s -> s.`malformed cursors and account selector are rejected before requesting data`() }
        runIsolated { s -> s.`room identity extraction rejects foreign credentials encoded paths and overflow`() }
        runIsolated { s -> s.`native timeout must be honored before executing dynamic read`() }
    }
}

private class HostAgentDiscoveryRpcCases {
    private fun video(id: String) = HostAgentVideo(id, "标题", "作者", 123L, "01:00")

    fun `missing verified host classes disable native datasets and refuse malformed arguments`() {
        val rpc = HostAgentDiscoveryRpc(object : ClassLoader(null) {})
        assertFalse(rpc.rankingAvailable)
        assertFalse(rpc.regionsAvailable)
        assertFalse(rpc.popularAvailable)
        assertEquals("ranking_unavailable", runCatching { rpc.ranking(null, null, 1000) }.exceptionOrNull()?.message)
        assertEquals("ranking_cursor_invalid", runCatching { rpc.ranking(null, "2", 1000) }.exceptionOrNull()?.message)
        assertEquals("ranking_region_invalid", runCatching { rpc.ranking("-1", null, 1000) }.exceptionOrNull()?.message)
        assertEquals("ranking_regions_unavailable", runCatching { rpc.regions(1000) }.exceptionOrNull()?.message)
        assertEquals("popular_unavailable", runCatching { rpc.popular(null, 1000) }.exceptionOrNull()?.message)
        assertEquals("popular_cursor_invalid", runCatching { rpc.popular("unsafe", 1000) }.exceptionOrNull()?.message)
    }

    fun `invalid first card cannot promote second video to rank one`() {
        val page = HostAgentDiscoveryProjection.page("ranking", listOf(null, video("av2"), video("av3")))
        val entries = page.data.getJSONArray("videos")
        assertEquals("av2", entries.getJSONObject(0).getString("video_id"))
        assertEquals(2, entries.getJSONObject(0).getInt("ranking_position"))
        assertEquals(3, entries.getJSONObject(1).getInt("ranking_position"))
    }

    fun `duplicate and malformed cards retain source rank gaps and page offsets`() {
        val page = HostAgentDiscoveryProjection.page("ranking",
            listOf(video("av1"), video("av1"), video("https://evil.example"), video("av4")), page = 2)
        assertEquals(listOf("av1", "av4"), page.videos.map { it.id })
        assertEquals(21, page.data.getJSONArray("videos").getJSONObject(0).getInt("ranking_position"))
        assertEquals(24, page.data.getJSONArray("videos").getJSONObject(1).getInt("ranking_position"))
    }

    fun `popular order is explicitly distinct from ranking and opaque cursor is not exposed in data`() {
        val page = HostAgentDiscoveryProjection.page("popular", listOf(null, video("av2")), next = "backend-only")
        val entry = page.data.getJSONArray("videos").getJSONObject(0)
        assertEquals(2, entry.getInt("source_position"))
        assertEquals("popular_feed_order", entry.getString("position_basis"))
        assertFalse(entry.has("ranking_position"))
        assertFalse(page.data.toString().contains("backend-only"))
        assertEquals("backend-only", page.next)
        assertEquals("unknown", entry.getString("official_source"))
    }

    fun `projection and page cursors stay bounded and reject coercions`() {
        val page = HostAgentDiscoveryProjection.page("ranking", (1..300).map { video("av$it") })
        assertEquals(20, page.videos.size)
        assertEquals(1, HostAgentDiscoveryProjection.rankingPage(null))
        assertEquals(10, HostAgentDiscoveryProjection.rankingPage("rank:10"))
        listOf("rank:1", "rank:11", "rank:02", "2", "rank:-1", "rank:2\n", "rank:9999999999999999").forEach {
            assertNull(it, HostAgentDiscoveryProjection.rankingPage(it))
        }
        assertEquals(123, HostAgentDiscoveryProjection.regionId("123"))
        assertEquals(0, HostAgentDiscoveryProjection.regionId("0"))
        listOf("-1", "01", "2147483648", "123x", "123\n", " 123").forEach {
            assertNull(it, HostAgentDiscoveryProjection.regionId(it))
        }
    }

    class RankItem(private val id: String = "123", private val route: String = "bilibili://video/123",
                   private val kind: String = "av", private val seconds: Long = 3661) {
        fun getGoto() = kind
        fun getParam() = id
        fun getUri() = route
        fun getTitle() = "<em>标题</em>\u0000" + "x".repeat(400)
        fun getName() = "作者"
        fun getMid() = 456L
        fun getDuration() = seconds
    }

    class DriftedRankItem {
        fun getGoto() = "av"
        fun getParam() = "123"
        fun getUri() = "bilibili://video/123"
        fun getTitle() = "标题"
        fun getName() = "作者"
        fun getMid() = "456"
        fun getDuration() = 60L
    }

    fun `rank reader rejects nonvideo and conflicting identity and projects bounded direct facts`() {
        val reader = checkNotNull(HostAgentRankItemReader.resolve(RankItem::class.java))
        assertNull(reader.read(RankItem(kind = "bangumi")))
        assertNull(reader.read(RankItem(route = "bilibili://video/999")))
        assertNull(reader.read(RankItem(id = "0", route = "bilibili://search?keyword=123")))
        val first = checkNotNull(reader.read(RankItem()))
        assertEquals("av123", first.id)
        assertTrue(first.title!!.length in 1..300)
        assertFalse(first.title!!.contains("<em>"))
        assertFalse(first.title!!.contains('\u0000'))
        assertEquals("作者", first.author)
        assertEquals(456L, first.mid)
        assertEquals("1:01:01", first.duration)
        assertEquals("0:00", reader.read(RankItem(seconds = 0))?.duration)
        assertNull(reader.read(RankItem(seconds = -1))?.duration)
        assertEquals("av999", reader.read(RankItem("999", "bilibili://video/999"))?.id)
        assertNull(HostAgentRankItemReader.resolve(DriftedRankItem::class.java))
    }

    class Args {
        fun getUpId() = 789L
        fun getUpName() = "热门作者"
    }

    class Base(private val ad: Boolean = false, private val argsPresent: Boolean = true,
               private val id: String = "123", private val kind: String = "av") {
        fun hasAdInfo() = ad
        fun getGoto() = kind
        fun getParam() = id
        fun getIdx() = 987L
        fun getUri() = "bilibili://video/$id"
        fun getTitle() = "热门视频"
        fun hasArgs() = argsPresent
        fun getArgs() = Args()
    }

    class Cover(private val baseValue: Base, private val present: Boolean = true) {
        fun hasBase() = present
        fun getBase() = baseValue
    }

    class Card(private val cover: Cover, private val present: Boolean = true) {
        fun hasSmallCoverV5() = present
        fun getSmallCoverV5() = cover
    }

    class HeaderCard(private val cover: Cover) {
        fun hasPopularTopEntrance() = true
        fun getPopularTopEntrance() = cover
    }

    fun `popular reader excludes ads missing base and nonvideo cards without inventing owner or duration`() {
        val reader = checkNotNull(HostAgentPopularCardReader.resolve(Card::class.java, Base::class.java))
        assertNull(reader.read(Card(Cover(Base(ad = true)))))
        assertNull(reader.read(Card(Cover(Base()), present = false)))
        assertNull(reader.read(Card(Cover(Base(), present = false))))
        assertNull(reader.read(Card(Cover(Base(kind = "article")))))
        val video = checkNotNull(reader.read(Card(Cover(Base()))))
        assertEquals("av123", video.id)
        assertEquals("热门作者", video.author)
        assertEquals(789L, video.mid)
        assertNull(video.duration)
        val unknownOwner = checkNotNull(reader.read(Card(Cover(Base(argsPresent = false)))))
        assertNull(unknownOwner.author)
        assertNull(unknownOwner.mid)
        assertNotNull(reader.read(Card(Cover(Base(id = "999")))))
    }

    fun `popular continuation can follow the actual final nonvideo card without authorizing it as video`() {
        val reader = checkNotNull(HostAgentPopularPositionReader.resolve(HeaderCard::class.java, Base::class.java))
        val header = HeaderCard(Cover(Base(id = "entrance", kind = "popular_top_entrance")))
        assertEquals(987L to "entrance", reader.read(header))
        assertNull(reader.read(HeaderCard(Cover(Base(), present = false))))
        assertNull(reader.read(Any()))
        assertNull(HostAgentPopularCardReader.resolve(HeaderCard::class.java, Base::class.java))
    }

    class Region(private val id: Int, private val type: Int, private val title: String = "分区") {
        fun getTid() = id
        fun getName() = title
        fun getRegionType() = type
    }

    fun `region catalog retains equal ids of distinct categories and bounds returned facts`() {
        val reader = checkNotNull(HostAgentRegionReader.resolve(Region::class.java))
        assertNull(reader.read(Region(-1, 2)))
        assertNull(reader.read(Region(123, -1)))
        assertNull(reader.read(Any()))
        val data = HostAgentDiscoveryProjection.regions(listOf(
            reader.read(Region(0, 0)), reader.read(Region(0, 1)), reader.read(Region(0, 1)),
            reader.read(Region(123, 2, "x".repeat(500)))))
        val regions = data.getJSONArray("regions")
        assertEquals(3, regions.length())
        assertEquals(0, regions.getJSONObject(0).getInt("region_type"))
        assertEquals(1, regions.getJSONObject(1).getInt("region_type"))
        assertEquals(100, regions.getJSONObject(2).getString("name").length)
        assertEquals(100, HostAgentDiscoveryProjection.regions((1..300).map { HostAgentRegion(it, "分区", 2) })
            .getJSONArray("regions").length())
        assertEquals("host_rpc", data.getString("source"))
        assertFalse(data.toString().contains("uri"))
    }

    fun `popular cursors preserve actual continuation and reject unbounded coerced or repeated values`() {
        val initial = checkNotNull(HostAgentPopularCursor.parse(null))
        assertEquals(0L, initial.idx)
        assertEquals("", initial.lastParam)
        assertEquals(1, initial.page)
        val encoded = checkNotNull(initial.next(987L, "BV1xx411c7mD", "server-version"))
        val page = checkNotNull(HostAgentPopularCursor.parse(encoded))
        assertEquals(987L, page.idx)
        assertEquals("BV1xx411c7mD", page.lastParam)
        assertEquals("server-version", page.version)
        assertEquals(2, page.page)
        assertNull(page.next(987L, "same", "server-version"))
        assertNull(initial.next(0, "", ""))
        assertNull(initial.next(1, "x".repeat(1001), ""))
        assertNull(initial.next(1, "x\ny", ""))
        assertNull(page.copy(page = 10).next(999L, "next", "version"))
        listOf(JSONObject(encoded).put("idx", 987), JSONObject(encoded).put("idx", "9223372036854775808"),
            JSONObject(encoded).put("page", 2.5), JSONObject(encoded).put("page", 11),
            JSONObject(encoded).put("extra", "hidden"), JSONObject(encoded).put("last_param", JSONObject.NULL)).forEach {
            assertNull(it.toString(), HostAgentPopularCursor.parse(it.toString()))
        }
    }

    fun `popular projection stops continuation when bounding would omit unreturned videos`() {
        val cursor = checkNotNull(HostAgentPopularCursor.parse(null))
        val partial = HostAgentDiscoveryProjection.popular((1..21).map { video("av$it") }, 21,
            cursor, 987L to "last-raw-card", "version")
        assertEquals(20, partial.videos.size)
        assertEquals(true, partial.data.getBoolean("truncated"))
        assertNull(partial.next)
        val full = HostAgentDiscoveryProjection.popular(listOf(video("av1"), null), 2,
            cursor, 987L to "last-raw-card", "version")
        assertFalse(full.data.getBoolean("truncated"))
        val next = checkNotNull(HostAgentPopularCursor.parse(full.next))
        assertEquals(987L, next.idx)
        assertEquals("last-raw-card", next.lastParam)
        assertFalse(full.data.toString().contains("last-raw-card"))
        assertNull(HostAgentDiscoveryProjection.popular(listOf(video("av1")), 1, cursor, null, "version").next)
    }

    fun `ranking refuses guessed page offsets when host returns a different page size`() {
        val videos = (1..20).map { video("av$it") }
        val full = HostAgentDiscoveryProjection.ranking(videos, 20, 1, null)
        assertEquals("rank:2", full.next)
        assertFalse(full.data.getBoolean("truncated"))
        val over = HostAgentDiscoveryProjection.ranking(videos, 100, 1, null)
        assertEquals(true, over.data.getBoolean("truncated"))
        assertNull(over.next)
        assertEquals(1, over.data.getJSONArray("videos").getJSONObject(0).getInt("ranking_position"))
        assertEquals("ranking_page_size_mismatch", runCatching {
            HostAgentDiscoveryProjection.ranking(videos, 100, 2, null)
        }.exceptionOrNull()?.message)
        assertNull(HostAgentDiscoveryProjection.ranking(videos, 20, 10, null).next)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostAgentDiscoveryRpcCases) -> Unit) {
            val s = HostAgentDiscoveryRpcCases()
            block(s)
        }
        runIsolated { s -> s.`missing verified host classes disable native datasets and refuse malformed arguments`() }
        runIsolated { s -> s.`invalid first card cannot promote second video to rank one`() }
        runIsolated { s -> s.`duplicate and malformed cards retain source rank gaps and page offsets`() }
        runIsolated { s -> s.`popular order is explicitly distinct from ranking and opaque cursor is not exposed in data`() }
        runIsolated { s -> s.`projection and page cursors stay bounded and reject coercions`() }
        runIsolated { s -> s.`rank reader rejects nonvideo and conflicting identity and projects bounded direct facts`() }
        runIsolated { s -> s.`popular reader excludes ads missing base and nonvideo cards without inventing owner or duration`() }
        runIsolated { s -> s.`popular continuation can follow the actual final nonvideo card without authorizing it as video`() }
        runIsolated { s -> s.`region catalog retains equal ids of distinct categories and bounds returned facts`() }
        runIsolated { s -> s.`popular cursors preserve actual continuation and reject unbounded coerced or repeated values`() }
        runIsolated { s -> s.`popular projection stops continuation when bounding would omit unreturned videos`() }
        runIsolated { s -> s.`ranking refuses guessed page offsets when host returns a different page size`() }

        HostAgentNativePolicyCases().verifyAllCases()
        HostAgentPolicyCases().verifyAllCases()
        HostAgentVideoStatisticsCases().verifyAllCases()
        HostAgentWindowPolicyCases().verifyAllCases()
    }
}

private class HostAgentNativePolicyCases {
    private fun lease(session: HostAgentSession) = checkNotNull(session.begin("native_task_123", 1, 1000, 0, false).lease)

    fun accountReadsRequireExplicitAuthorizationAndStableCurrentAccount() {
        val session = HostAgentSession()
        val lease = lease(session)
        assertEquals("account_read_not_authorized", session.accountRefusal(lease, 42, 1))
        session.accountAuthorization(lease, true, 42, 1)
        assertNull(session.accountRefusal(lease, 42, 2))
        assertEquals("account_changed", session.accountRefusal(lease, 43, 2))
        assertEquals("account_identity_unavailable", session.accountRefusal(lease, null, 2))
        session.cancel(lease, 3)
        assertEquals("task_inactive", session.accountRefusal(lease, 42, 4))
    }

    fun authorizedButUnavailableIdentityDoesNotAuthorizePersonalData() {
        val session = HostAgentSession()
        val lease = lease(session)
        session.accountAuthorization(lease, true, null, 1)
        assertEquals("account_identity_unavailable", session.accountRefusal(lease, 42, 2))
    }

    fun observedUserAndRegionIdsAreTaskScopedAndCanonical() {
        val session = HostAgentSession()
        val lease = lease(session)
        session.rememberUsers(lease, listOf("42", "042", "0", "-1", "9223372036854775808"), 1)
        assertTrue(session.knowsUser(lease, "42", 2))
        assertFalse(session.knowsUser(lease, "042", 2))
        assertTrue(session.knowsRegion(lease, "0", 2))
        assertFalse(session.knowsRegion(lease, "17", 2))
        session.rememberRegions(lease, listOf("17"), 2)
        assertTrue(session.knowsRegion(lease, "17", 3))
        session.cancel(lease, 4)
        assertFalse(session.knowsUser(lease, "42", 5))
    }

    fun pageTokensDoNotCrossDatasetOrUserBoundaries() {
        val session = HostAgentSession()
        val lease = lease(session)
        val token = checkNotNull(session.rememberCursor(lease, "get_user_videos:42", "private_cursor", 1))
        assertEquals("private_cursor", session.cursor(lease, "get_user_videos:42", token, 2))
        assertNull(session.cursor(lease, "get_user_videos:43", token, 2))
        assertNull(session.cursor(lease, "get_ranking:0", token, 2))
    }

    fun closingLeaseDoesNotEraseWindowTakeoverEvidenceWhileReleaseIsQueued() {
        val session = HostAgentSession()
        val lease = lease(session)
        val window = HostAgentWindowPolicy()
        window.begin(lease.generation, 42)
        assertNotNull(session.admit(lease.taskId, 2, 1000, 1, closing = true).lease)
        assertFalse(session.isTaskActive(lease, 2))
        assertTrue(window.ownsWindow(lease.generation, 42))
        assertFalse(window.ownsWindow(lease.generation, 43))
        window.revoke(lease.generation)
        assertFalse(window.ownsWindow(lease.generation, 42))
    }

    fun absentAndNegativeMetricsRemainUnknownWhileExplicitZeroIsValid() {
        val reader = checkNotNull(HostAgentVideoStatistics.resolve(Arc::class.java))
        assertEquals("unknown", reader.read(Arc(false, Stats(0, 0))).getString("status"))
        assertEquals("unknown", reader.read(Arc(true, Stats.getDefaultInstance())).getString("status"))
        val explicitZero = reader.read(Arc(true, Stats(0, 0)))
        assertEquals("reported", explicitZero.getString("status"))
        assertEquals(0, explicitZero.getLong("view_count"))
        val data = reader.read(Arc(true, Stats(0, -1)))
        assertEquals(0, data.getLong("view_count"))
        assertTrue(data.isNull("like_count"))
        assertTrue(data.isNull("coin_count"))
    }

    class Arc(private val present: Boolean, private val value: Stats) {
        fun hasStat() = present
        fun getStat() = value
    }
    class Stats(private val view: Int, private val like: Int) {
        fun getView() = view
        fun getLike() = like
        override fun equals(other: Any?): Boolean = other is Stats && view == other.view && like == other.like
        override fun hashCode(): Int = 31 * view + like
        companion object {
            private val DEFAULT = Stats(0, 0)
            @JvmStatic fun getDefaultInstance() = DEFAULT
        }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostAgentNativePolicyCases) -> Unit) {
            val s = HostAgentNativePolicyCases()
            block(s)
        }
        runIsolated { s -> s.accountReadsRequireExplicitAuthorizationAndStableCurrentAccount() }
        runIsolated { s -> s.authorizedButUnavailableIdentityDoesNotAuthorizePersonalData() }
        runIsolated { s -> s.observedUserAndRegionIdsAreTaskScopedAndCanonical() }
        runIsolated { s -> s.pageTokensDoNotCrossDatasetOrUserBoundaries() }
        runIsolated { s -> s.closingLeaseDoesNotEraseWindowTakeoverEvidenceWhileReleaseIsQueued() }
        runIsolated { s -> s.absentAndNegativeMetricsRemainUnknownWhileExplicitZeroIsValid() }
    }
}

private class HostAgentPolicyCases {
    private val taskId = "task-00000001"
    private fun begin(session: HostAgentSession, vision: Boolean = false) =
        checkNotNull(session.begin(taskId, 1, 120_100, 100, vision).lease)

    fun `begin requires fresh bounded task and first sequence`() {
        val session = HostAgentSession()
        assertEquals("invalid_begin", session.begin("short", 1, 200, 100, false).error)
        assertEquals("invalid_begin", session.begin(taskId, 2, 200, 100, false).error)
        assertEquals("invalid_begin", session.begin(taskId, 1, 100, 100, false).error)
        assertEquals("invalid_begin", session.begin(taskId, 1, 180_101, 100, false).error)
        assertEquals("invalid_begin", session.begin(taskId, 1, 15_101, 180_100, 100, false).error)
        assertNotNull(session.begin(taskId, 1, 15_100, 180_100, 100, false).lease)
        assertEquals("task_busy", session.begin("task-00000002", 1, 200, 100, false).error)
    }

    fun `sequence replays and wrong task are rejected without changing live lease`() {
        val session = HostAgentSession()
        val lease = begin(session)
        assertEquals("task_mismatch", session.admit("task-00000002", 2, 1000, 200).error)
        assertEquals("replayed_sequence", session.admit(taskId, 1, 1000, 200).error)
        assertNotNull(session.admit(taskId, 3, 1000, 200).lease)
        assertEquals("replayed_sequence", session.admit(taskId, 2, 1000, 200).error)
        assertTrue(session.isActive(lease, 201))
    }

    fun `per command deadline cannot extend task and expired command cannot act`() {
        val session = HostAgentSession()
        begin(session)
        assertEquals("invalid_deadline", session.admit(taskId, 2, 120_101, 200).error)
        val command = checkNotNull(session.admit(taskId, 2, 500, 200).lease)
        var actions = 0
        session.whileActive(command, 499) { actions++ }
        session.whileActive(command, 500) { actions++ }
        assertEquals(1, actions)
        assertNotNull(session.current(500))
    }

    fun `cancel immediately invalidates previously queued actions and disallows resurrection`() {
        val session = HostAgentSession()
        val lease = begin(session)
        val queued = checkNotNull(session.admit(taskId, 2, 2_000, 200).lease)
        assertNotNull(session.admit(taskId, 3, 2_000, 201, closing = true).lease)
        var navigated = false
        session.whileActive(queued, 202) { navigated = true }
        assertFalse(navigated)
        assertFalse(session.isActive(lease, 202))
        assertEquals("closed_task", session.begin(taskId, 1, 1000, 203, false).error)
        assertEquals("task_inactive", session.admit(taskId, 4, 1000, 204).error)
    }

    fun `old service death or expiry cannot cancel replacement task`() {
        val session = HostAgentSession()
        val old = begin(session)
        assertTrue(session.cancel(old, 200))
        val fresh = checkNotNull(session.begin("task-00000002", 1, 1000, 201, false).lease)
        assertFalse(session.cancel(old, 202))
        assertTrue(session.isActive(fresh, 202))
        assertNull(session.current(1000))
        assertFalse(session.isActive(fresh, 1000))
    }

    fun `candidate identity is local to task bounded and invalid after cancel`() {
        val session = HostAgentSession()
        val lease = begin(session)
        val admitted = session.remember(lease, (1..300).map { "av$it" }, 101)
        assertEquals(HostAgentSession.MAX_CANDIDATES, admitted.size)
        assertFalse(session.knows(lease, "av1", 102))
        assertTrue(session.knows(lease, "av300", 102))
        assertFalse(session.knows(lease, "123", 102))
        assertEquals(listOf("av1", "av999"), session.remember(lease, listOf("evil", "av1", "av999"), 103))
        session.cancel(lease, 104)
        val fresh = checkNotNull(session.begin("task-00000002", 1, 1000, 105, false).lease)
        assertFalse(session.knows(fresh, "av1", 106))
    }

    fun `opaque page tokens are query and task bound and capped`() {
        val session = HostAgentSession()
        val lease = begin(session)
        val token = checkNotNull(session.rememberCursor(lease, "黑神话", "backend-next", 101))
        assertEquals("backend-next", session.cursor(lease, "黑神话", token, 102))
        assertNull(session.cursor(lease, "其他目标", token, 102))
        assertNull(session.cursor(lease, "黑神话", "backend-next", 102))
        assertNull(session.rememberCursor(lease, "黑神话", "x".repeat(2049), 102))
        repeat(HostAgentSession.MAX_CURSORS - 1) { assertNotNull(session.rememberCursor(lease, "黑神话", "next-$it", 103)) }
        assertNotNull(session.rememberCursor(lease, "黑神话", "next-overflow", 104))
        assertNull(session.cursor(lease, "黑神话", token, 104))
        session.cancel(lease, 105)
        assertNull(session.cursor(lease, "黑神话", token, 106))
    }

    fun `vision permission and requested page both belong to task`() {
        val session = HostAgentSession()
        val lease = begin(session)
        assertFalse(session.allowVision(lease, 101))
        session.expectedPage(lease, query = "悟空", now = 101)
        assertTrue(session.matchesPage(lease, "search", "悟空", 102))
        assertFalse(session.matchesPage(lease, "search", "其他", 102))
        assertFalse(session.matchesPage(lease, "video", "av1", 102))
        session.expectedPage(lease, video = "av1", now = 103)
        assertFalse(session.matchesPage(lease, "search", "悟空", 104))
        assertTrue(session.matchesPage(lease, "video", "av1", 104))
        session.cancel(lease, 105)
        assertFalse(session.matchesPage(lease, "video", "av1", 106))
        val fresh = checkNotNull(session.begin("task-00000002", 1, 1000, 107, true).lease)
        assertTrue(session.allowVision(fresh, 108))
        assertFalse(session.matchesPage(fresh, "video", "av1", 108))
    }

    fun `burst limit recovers and task can still be cancelled`() {
        val session = HostAgentSession()
        val lease = begin(session)
        for (sequence in 2L..HostAgentSession.MAX_COMMANDS_PER_SECOND.toLong()) {
            assertNotNull(session.admit(taskId, sequence, 2_000, 200).lease)
        }
        assertEquals("host_rate_limited", session.admit(taskId, 99, 2_000, 201).error)
        assertNotNull(session.admit(taskId, 100, 2_000, 1_101).lease)
        assertNotNull(session.admit(taskId, 101, 2_000, 1_102, closing = true).lease)
        assertFalse(session.isTaskActive(lease, 1_103))
    }

    fun `renew extends task ownership but never an old command lease`() {
        val session = HostAgentSession()
        val initial = checkNotNull(session.begin(taskId, 1, 1_000, 120_100, 100, true).lease)
        val renewed = checkNotNull(session.renew(taskId, 2, 111_000, 290_000, 110_000).lease)
        assertEquals(initial.generation, renewed.generation)
        assertTrue(session.isTaskActive(initial, 120_101))
        assertFalse(session.isActive(initial, 120_101))
        val command = checkNotNull(session.admit(taskId, 3, 121_000, 120_101).lease)
        assertTrue(session.isActive(command, 120_102))
        checkNotNull(session.renew(taskId, 4, 122_000, 300_000, 120_500).lease)
        assertFalse(session.isActive(command, 121_000))
        assertTrue(session.isTaskActive(command, 121_000))
        assertEquals(300_000L, session.current(121_000)?.deadline)
        assertNull(session.current(300_000))
    }

    fun `renew validates task sequence finite deadlines and cannot resurrect cancelled or expired task`() {
        val session = HostAgentSession()
        val initial = checkNotNull(session.begin(taskId, 1, 1_000, 120_100, 100, false).lease)
        assertEquals("task_mismatch", session.renew("task-00000002", 2, 1_000, 180_200, 200).error)
        assertEquals("replayed_sequence", session.renew(taskId, 1, 1_000, 180_200, 200).error)
        assertEquals("invalid_deadline", session.renew(taskId, 2, Long.MAX_VALUE, Long.MAX_VALUE, 200).error)
        assertEquals("invalid_deadline", session.renew(taskId, 2, 1_000, 180_201, 200).error)
        assertTrue(session.cancel(initial, 201))
        assertEquals("task_inactive", session.renew(taskId, 2, 1_000, 180_200, 202).error)
        val fresh = checkNotNull(session.begin("task-00000002", 1, 1_000, 1_000, 203, false).lease)
        assertEquals("task_inactive", session.renew(fresh.taskId, 2, 2_000, 180_000, 1_000).error)
        assertFalse(session.isTaskActive(fresh, 1_001))
    }

    fun `long task allows hundreds of finite operations with bounded rolling leases`() {
        val session = HostAgentSession()
        val initial = checkNotNull(session.begin(taskId, 1, 1_000, 180_100, 100, false).lease)
        var sequence = 1L
        repeat(400) { index ->
            val now = 1_100L + index * 1_001L
            checkNotNull(session.renew(taskId, ++sequence, now + 1_000, now + 180_000, now).lease)
            checkNotNull(session.admit(taskId, ++sequence, now + 1_000, now + 1).lease)
        }
        assertTrue(session.isTaskActive(initial, 401_000))
        assertFalse(session.isActive(initial, 401_000))
    }

    fun `candidate lru retains recently read video and cursors deduplicate backend tokens`() {
        val session = HostAgentSession()
        val lease = begin(session)
        session.remember(lease, (1..HostAgentSession.MAX_CANDIDATES).map { "av$it" }, 101)
        assertTrue(session.knows(lease, "av1", 102))
        session.remember(lease, listOf("av999"), 103)
        assertTrue(session.knows(lease, "av1", 104))
        assertFalse(session.knows(lease, "av2", 104))
        val token = session.rememberCursor(lease, "悟空", "same-token", 105)
        repeat(100) { assertEquals(token, session.rememberCursor(lease, "悟空", "same-token", 106)) }
        assertEquals("same-token", session.cursor(lease, "悟空", checkNotNull(token), 107))
    }

    fun `closing deadline may extend past task end without extending authorization`() {
        val session = HostAgentSession()
        val lease = begin(session)
        assertNotNull(session.admit(taskId, 2, 123_000, 120_000, closing = true).lease)
        assertFalse(session.isActive(lease, 120_001))
    }

    fun `video identity canonicalization rejects overflow paths and unknown schemes`() {
        val policy = HostAgentNavigationPolicy
        assertEquals("av123", policy.videoId("123"))
        assertEquals("av123", policy.videoId("av123"))
        assertEquals("BV1xx411c7mD", policy.videoId("BV1xx411c7mD"))
        listOf("0", "av0", "00123", "-1", "9223372036854775808", "123/evil", " BV1xx411c7mD").forEach {
            assertNull(it, policy.videoId(it))
        }
        listOf("https://www.bilibili.com/video/123", "bilibili://video.evil/123", "bilibili://user@video/123",
            "bilibili://video:80/123", "bilibili://video/%31%32%33", "bilibili://video/123/456",
            "bilibili://search?keyword=123", "bilibili://video//123").forEach {
            assertNull(it, policy.videoFromUri(it))
        }
    }

    fun `navigation drops all host supplied query actions and catches conflicting ids`() {
        val policy = HostAgentNavigationPolicy
        val id = checkNotNull(policy.videoFromUri("bilibili://video/123?redirect=https://evil.example#fragment"))
        assertEquals("bilibili://video/123", policy.videoRoute(id))
        assertNull(policy.candidateId("123", "bilibili://video/456"))
        assertEquals("av123", policy.candidateId("123", "bilibili://video/BV1xx411c7mD"))
        assertEquals("av123", policy.candidateId("123", "bilibili://evil/execute"))
    }

    fun `search query is bounded and safely encoded into one keyword`() {
        val policy = HostAgentNavigationPolicy
        assertNull(policy.searchQuery(" "))
        assertNull(policy.searchQuery("a\nb"))
        assertNull(policy.searchQuery("x".repeat(201)))
        assertEquals("悟空", policy.searchQuery("  悟空  "))
        assertEquals("bilibili://search?keyword=a%26redirect%3Db%23c%20d", policy.searchRoute("a&redirect=b#c d"))
    }

    fun `capture denies every privacy or incomplete observation condition`() {
        fun refusal(authorized: Boolean = true, foreground: Boolean = true, taskPage: Boolean = true,
                    secure: Boolean = false, password: Boolean = false, editing: Boolean = false,
                    complete: Boolean = true) = HostAgentScreenPolicy.refusal(authorized, foreground, taskPage,
            secure, password, editing, complete)
        assertNull(refusal())
        assertEquals("vision_not_authorized", refusal(authorized = false))
        assertEquals("host_not_foreground", refusal(foreground = false))
        assertEquals("screen_not_task_page", refusal(taskPage = false))
        assertEquals("screen_secure", refusal(secure = true))
        assertEquals("screen_password", refusal(password = true))
        assertEquals("screen_input_active", refusal(editing = true))
        assertEquals("screen_inspection_incomplete", refusal(complete = false))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostAgentPolicyCases) -> Unit) {
            val s = HostAgentPolicyCases()
            block(s)
        }
        runIsolated { s -> s.`begin requires fresh bounded task and first sequence`() }
        runIsolated { s -> s.`sequence replays and wrong task are rejected without changing live lease`() }
        runIsolated { s -> s.`per command deadline cannot extend task and expired command cannot act`() }
        runIsolated { s -> s.`cancel immediately invalidates previously queued actions and disallows resurrection`() }
        runIsolated { s -> s.`old service death or expiry cannot cancel replacement task`() }
        runIsolated { s -> s.`candidate identity is local to task bounded and invalid after cancel`() }
        runIsolated { s -> s.`opaque page tokens are query and task bound and capped`() }
        runIsolated { s -> s.`vision permission and requested page both belong to task`() }
        runIsolated { s -> s.`burst limit recovers and task can still be cancelled`() }
        runIsolated { s -> s.`renew extends task ownership but never an old command lease`() }
        runIsolated { s -> s.`renew validates task sequence finite deadlines and cannot resurrect cancelled or expired task`() }
        runIsolated { s -> s.`long task allows hundreds of finite operations with bounded rolling leases`() }
        runIsolated { s -> s.`candidate lru retains recently read video and cursors deduplicate backend tokens`() }
        runIsolated { s -> s.`closing deadline may extend past task end without extending authorization`() }
        runIsolated { s -> s.`video identity canonicalization rejects overflow paths and unknown schemes`() }
        runIsolated { s -> s.`navigation drops all host supplied query actions and catches conflicting ids`() }
        runIsolated { s -> s.`search query is bounded and safely encoded into one keyword`() }
        runIsolated { s -> s.`capture denies every privacy or incomplete observation condition`() }
    }
}

private class HostAgentVideoStatisticsCases {
    private fun reader() = checkNotNull(HostAgentVideoStatistics.resolve(Arc::class.java))

    fun viewUniteLongCountsAndNestedValuesPreserveNumbersAboveIntRange() {
        val large = Int.MAX_VALUE.toLong() + 100
        val stat = Stat(vt = StatInfo(large), danmaku = StatInfo(large + 1), count = large + 2)
        val data = reader().read(Arc(true, stat))
        assertEquals("reported", data.getString("status"))
        assertEquals(large, data.getLong("view_count"))
        assertEquals(large + 1, data.getLong("danmaku_count"))
        for (key in listOf("like_count", "coin_count", "favorite_count", "share_count", "comment_count")) assertEquals(large + 2, data.getLong(key))
        assertEquals(8, data.length())
        assertFalse(data.has("follow"))
        assertEquals(0, stat.getVt()?.textReads)
        assertEquals(0, stat.getDanmaku()?.textReads)
    }

    fun outerPresenceAndDefaultStatKeepAllFieldsUnknown() {
        for (arc in listOf(Arc(false, Stat()), Arc(true, Stat.getDefaultInstance()), Arc(true, null))) {
            val data = reader().read(arc)
            assertEquals("unknown", data.getString("status"))
            assertTrue(data.isNull("view_count"))
            assertTrue(data.isNull("like_count"))
        }
    }

    fun absentNestedPresenceDoesNotEvenReadItsGetter() {
        val stat = Stat(vtPresent = false, danmakuPresent = false)
        val data = reader().read(Arc(true, stat))
        assertEquals(0, stat.vtReads)
        assertEquals(0, stat.danmakuReads)
        assertTrue(data.isNull("view_count"))
        assertTrue(data.isNull("danmaku_count"))
        assertEquals(1, data.getLong("like_count"))
    }

    fun nestedDefaultInstancesAreNotReportedAsNumericZero() {
        val stat = Stat(vt = StatInfo.getDefaultInstance(), danmaku = StatInfo.getDefaultInstance())
        val data = reader().read(Arc(true, stat))
        assertTrue(data.isNull("view_count"))
        assertTrue(data.isNull("danmaku_count"))
        assertEquals(1, data.getLong("like_count"))
    }

    fun explicitlyPresentNonDefaultZeroRemainsValidEvenWithStructuralEquality() {
        val info = StatInfo(0)
        assertEquals(StatInfo.getDefaultInstance(), info)
        assertNotSame(StatInfo.getDefaultInstance(), info)
        val stat = Stat(vt = info, danmaku = StatInfo(0), count = 0)
        assertEquals(Stat.getDefaultInstance(), stat)
        assertNotSame(Stat.getDefaultInstance(), stat)
        val data = reader().read(Arc(true, stat))
        assertEquals("reported", data.getString("status"))
        assertEquals(0, data.getLong("view_count"))
        assertEquals(0, data.getLong("danmaku_count"))
        assertEquals(0, data.getLong("like_count"))
    }

    fun negativeNumericValuesDoNotFallBackToFormattedText() {
        val vt = StatInfo(-1)
        val danmaku = StatInfo(-1)
        val data = reader().read(Arc(true, Stat(vt = vt, danmaku = danmaku, count = -1)))
        assertEquals("unknown", data.getString("status"))
        assertTrue(data.isNull("view_count"))
        assertTrue(data.isNull("danmaku_count"))
        assertTrue(data.isNull("coin_count"))
        assertEquals(0, vt.textReads)
        assertEquals(0, danmaku.textReads)
    }

    fun nullNestedPayloadLeavesItsFieldUnknownWithoutDiscardingOtherCounts() {
        val data = reader().read(Arc(true, Stat(vt = null, danmaku = null)))
        assertTrue(data.isNull("view_count"))
        assertTrue(data.isNull("danmaku_count"))
        assertEquals(1, data.getLong("share_count"))
    }

    fun missingNestedPresenceDisablesTheViewCapability() {
        assertNull(HostAgentVideoStatistics.resolve(MissingPresenceArc::class.java))
    }

    fun stringValueCannotEnableNumericViewCapability() {
        assertNull(HostAgentVideoStatistics.resolve(StringValueArc::class.java))
    }

    fun missingNestedDefaultInstanceDisablesTheViewCapability() {
        assertNull(HostAgentVideoStatistics.resolve(MissingDefaultArc::class.java))
    }

    fun boxedLongValueCannotReplaceTheVerifiedPrimitiveLongField() {
        assertNull(HostAgentVideoStatistics.resolve(BoxedValueArc::class.java))
    }

    class Arc(private val present: Boolean, private val stat: Stat?) {
        fun hasStat() = present
        fun getStat() = stat
    }
    class Stat(private val vtPresent: Boolean = true, private val danmakuPresent: Boolean = true,
               private val vt: StatInfo? = StatInfo(1), private val danmaku: StatInfo? = StatInfo(1), private val count: Long = 1) {
        var vtReads = 0
        var danmakuReads = 0
        fun hasVt() = vtPresent
        fun hasDanmaku() = danmakuPresent
        fun getVt(): StatInfo? { vtReads++; return vt }
        fun getDanmaku(): StatInfo? { danmakuReads++; return danmaku }
        fun getLike() = count
        fun getCoin() = count
        fun getFav() = count
        fun getShare() = count
        fun getReply() = count
        fun getFollow() = 999L
        override fun equals(other: Any?) = other is Stat && count == other.count && vt == other.vt && danmaku == other.danmaku
        override fun hashCode() = count.hashCode()
        companion object {
            private val DEFAULT = Stat(vt = StatInfo.getDefaultInstance(), danmaku = StatInfo.getDefaultInstance(), count = 0)
            @JvmStatic fun getDefaultInstance() = DEFAULT
        }
    }
    class StatInfo(private val value: Long) {
        var textReads = 0
        fun getValue() = value
        fun getText(): String { textReads++; return "999亿" }
        fun getPureText(): String { textReads++; return "99900000000" }
        override fun equals(other: Any?) = other is StatInfo && value == other.value
        override fun hashCode() = value.hashCode()
        companion object {
            private val DEFAULT = StatInfo(0)
            @JvmStatic fun getDefaultInstance() = DEFAULT
        }
    }

    class MissingPresenceArc { fun hasStat() = true; fun getStat() = MissingPresenceStat() }
    class MissingPresenceStat {
        fun getVt() = StatInfo(1)
        companion object { @JvmStatic fun getDefaultInstance() = MissingPresenceStat() }
    }
    class StringValueArc { fun hasStat() = true; fun getStat() = StringValueStat() }
    class StringValueStat {
        fun hasVt() = true
        fun getVt() = StringValueInfo()
        companion object { @JvmStatic fun getDefaultInstance() = StringValueStat() }
    }
    class StringValueInfo {
        fun getValue() = "100000000"
        companion object { @JvmStatic fun getDefaultInstance() = StringValueInfo() }
    }
    class MissingDefaultArc { fun hasStat() = true; fun getStat() = MissingDefaultStat() }
    class MissingDefaultStat {
        fun hasVt() = true
        fun getVt() = MissingDefaultInfo()
        companion object { @JvmStatic fun getDefaultInstance() = MissingDefaultStat() }
    }
    class MissingDefaultInfo { fun getValue() = 1L }
    class BoxedValueArc { fun hasStat() = true; fun getStat() = BoxedValueStat() }
    class BoxedValueStat {
        fun hasVt() = true
        fun getVt() = BoxedValueInfo()
        companion object { @JvmStatic fun getDefaultInstance() = BoxedValueStat() }
    }
    class BoxedValueInfo {
        fun getValue(): Long? = 1L
        companion object { @JvmStatic fun getDefaultInstance() = BoxedValueInfo() }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostAgentVideoStatisticsCases) -> Unit) {
            val s = HostAgentVideoStatisticsCases()
            block(s)
        }
        runIsolated { s -> s.viewUniteLongCountsAndNestedValuesPreserveNumbersAboveIntRange() }
        runIsolated { s -> s.outerPresenceAndDefaultStatKeepAllFieldsUnknown() }
        runIsolated { s -> s.absentNestedPresenceDoesNotEvenReadItsGetter() }
        runIsolated { s -> s.nestedDefaultInstancesAreNotReportedAsNumericZero() }
        runIsolated { s -> s.explicitlyPresentNonDefaultZeroRemainsValidEvenWithStructuralEquality() }
        runIsolated { s -> s.negativeNumericValuesDoNotFallBackToFormattedText() }
        runIsolated { s -> s.nullNestedPayloadLeavesItsFieldUnknownWithoutDiscardingOtherCounts() }
        runIsolated { s -> s.missingNestedPresenceDisablesTheViewCapability() }
        runIsolated { s -> s.stringValueCannotEnableNumericViewCapability() }
        runIsolated { s -> s.missingNestedDefaultInstanceDisablesTheViewCapability() }
        runIsolated { s -> s.boxedLongValueCannotReplaceTheVerifiedPrimitiveLongField() }
    }
}

private class HostAgentWindowPolicyCases {
    private val search = HostAgentWindowPolicy.Target("search", "黑神话")
    private val ready = HostAgentWindowPolicy.Observation.READY
    private val navigating = HostAgentWindowPolicy.Observation.NAVIGATING
    private val revoked = HostAgentWindowPolicy.Observation.REVOKED

    fun `begin owns only the initial window and does not authorize a screenshot yet`() {
        val gate = HostAgentWindowPolicy()
        gate.begin(1, 10)
        assertEquals(ready, gate.observe(1, 10, "unknown", null, 100))
        assertFalse(gate.isTaskPage(1))
        assertEquals(revoked, gate.observe(1, 11, "unknown", null, 101))
        assertEquals(revoked, gate.observe(1, 10, "unknown", null, 102))
        assertNull(gate.expectNavigation(1, 10, search, 103))
    }

    fun `unexpected pause revokes even when later resume carries a matching old intent`() {
        val gate = HostAgentWindowPolicy()
        gate.begin(1, 10)
        assertNotNull(gate.expectNavigation(1, 10, search, 100))
        assertEquals(ready, gate.observe(1, 10, search.kind, search.identity, 101))
        assertFalse(gate.pause(1, 10, 102))
        assertFalse(gate.resume(1, 10, search.kind, search.identity, 103))
        assertNull(gate.expectNavigation(1, 10, HostAgentWindowPolicy.Target("video", "av1"), 104))
    }

    fun `expected navigation permits one pause and a matching target in its time window`() {
        val gate = HostAgentWindowPolicy()
        gate.begin(1, 10)
        assertNotNull(gate.expectNavigation(1, 10, search, 100))
        assertTrue(gate.awaitingNavigation(1, 101))
        assertEquals(navigating, gate.observe(1, 10, "unknown", null, 102))
        assertFalse(gate.isTaskPage(1))
        assertTrue(gate.pause(1, 10, 103))
        assertTrue(gate.resume(1, 11, search.kind, search.identity, 104))
        assertEquals(ready, gate.observe(1, 11, search.kind, search.identity, 105))
        assertTrue(gate.isTaskPage(1))
        assertFalse(gate.awaitingNavigation(1, 106))
    }

    fun `expected navigation does not accept a login screen or different query`() {
        for ((kind, identity) in listOf("unknown" to null, "search" to "其他搜索", "video" to "av1")) {
            val gate = HostAgentWindowPolicy()
            gate.begin(1, 10)
            assertNotNull(gate.expectNavigation(1, 10, search, 100))
            assertTrue(gate.pause(1, 10, 101))
            assertFalse(gate.resume(1, 11, kind, identity, 102))
            assertFalse(gate.resume(1, 12, search.kind, search.identity, 103))
            assertFalse(gate.isTaskPage(1))
        }
    }

    fun `same Activity navigation requires a new matching observation`() {
        val gate = HostAgentWindowPolicy()
        gate.begin(1, 10)
        val token = checkNotNull(gate.expectNavigation(1, 10, search, 100))
        assertEquals(navigating, gate.observe(1, 10, "search", "旧搜索", 101))
        assertNull(gate.expectNavigation(1, 10, HostAgentWindowPolicy.Target("video", "av1"), 102))
        assertEquals(ready, gate.observe(1, 10, "search", "黑神话", 103))
        assertTrue(gate.isTaskPage(1))
        assertFalse(gate.expireNavigation(1, token, 6000))
    }

    fun `deadline cannot be revived by a late matching resume`() {
        val gate = HostAgentWindowPolicy(500)
        gate.begin(1, 10)
        val token = checkNotNull(gate.expectNavigation(1, 10, search, 100))
        assertFalse(gate.expireNavigation(1, token, 599))
        assertFalse(gate.awaitingNavigation(1, 600))
        assertTrue(gate.expireNavigation(1, token, 600))
        assertFalse(gate.resume(1, 11, search.kind, search.identity, 601))
    }

    fun `matching target exactly at deadline still rejects navigation`() {
        val gate = HostAgentWindowPolicy(500)
        gate.begin(1, 10)
        assertNotNull(gate.expectNavigation(1, 10, search, 100))
        assertTrue(gate.pause(1, 10, 101))
        assertFalse(gate.resume(1, 11, search.kind, search.identity, 600))
    }

    fun `user takeover revokes late navigation and capture despite an unchanged intent`() {
        val session = HostAgentSession()
        val lease = checkNotNull(session.begin("task-00000001", 1, 120_100, 100, true).lease)
        val gate = HostAgentWindowPolicy()
        gate.begin(lease.generation, 10)
        assertNotNull(gate.expectNavigation(lease.generation, 10, search, 101))
        assertEquals(ready, gate.observe(lease.generation, 10, search.kind, search.identity, 102))
        gate.revoke(lease.generation)
        session.cancel(lease, 103)
        assertEquals(revoked, gate.observe(lease.generation, 10, search.kind, search.identity, 104))
        assertFalse(gate.isTaskPage(lease.generation))
        assertFalse(session.allowVision(lease, 104))
        var navigationRan = false
        session.whileActive(lease, 104) { navigationRan = true }
        assertFalse(navigationRan)
    }

    fun `old timers and old window callbacks cannot revoke a new task or navigation`() {
        val gate = HostAgentWindowPolicy()
        gate.begin(1, 10)
        val oldTask = checkNotNull(gate.expectNavigation(1, 10, search, 100))
        gate.begin(2, 20)
        val oldNavigation = checkNotNull(gate.expectNavigation(2, 20, search, 101))
        assertEquals(ready, gate.observe(2, 20, search.kind, search.identity, 102))
        val current = checkNotNull(gate.expectNavigation(2, 20, HostAgentWindowPolicy.Target("video", "av1"), 103))
        gate.revoke(1)
        assertFalse(gate.expireNavigation(1, oldTask, 6000))
        assertFalse(gate.expireNavigation(2, oldNavigation, 6000))
        assertTrue(gate.tracks(2))
        assertTrue(gate.expireNavigation(2, current, 6000))
    }

    fun `settled page identity drift revokes instead of reacquiring an arbitrary foreground page`() {
        val gate = HostAgentWindowPolicy()
        gate.begin(1, 10)
        assertNotNull(gate.expectNavigation(1, 10, search, 100))
        assertEquals(ready, gate.observe(1, 10, search.kind, search.identity, 101))
        assertEquals(revoked, gate.observe(1, 10, "search", "别的目标", 102))
        assertEquals(revoked, gate.observe(1, 10, search.kind, search.identity, 103))
    }

    fun `system display focus loss preserves only owned resumed visible read access`() {
        val gate = HostAgentWindowPolicy()
        gate.begin(1, 10)
        assertTrue(gate.canUseWindow(1, 10, resumed = true, shown = true, focused = false, readOnly = true))
        assertFalse(gate.canUseWindow(1, 10, resumed = true, shown = true, focused = false, readOnly = false))
        assertTrue(gate.canUseWindow(1, 10, resumed = true, shown = true, focused = true, readOnly = false))
        assertEquals(ready, gate.observe(1, 10, "unknown", null, 100))
    }

    fun `read access cannot transfer to another window generation or hidden paused Activity`() {
        val gate = HostAgentWindowPolicy()
        assertFalse(gate.canUseWindow(1, 10, resumed = true, shown = true, focused = true, readOnly = true))
        gate.begin(1, 10)
        assertFalse(gate.canUseWindow(2, 10, resumed = true, shown = true, focused = false, readOnly = true))
        assertFalse(gate.canUseWindow(1, 11, resumed = true, shown = true, focused = false, readOnly = true))
        assertFalse(gate.canUseWindow(1, 10, resumed = false, shown = true, focused = false, readOnly = true))
        assertFalse(gate.canUseWindow(1, 10, resumed = true, shown = false, focused = false, readOnly = true))
    }

    fun `opening another Activity still revokes and cannot return through the read exception`() {
        val gate = HostAgentWindowPolicy()
        gate.begin(1, 10)
        assertTrue(gate.canUseWindow(1, 10, resumed = true, shown = true, focused = false, readOnly = true))
        assertFalse(gate.pause(1, 10, 100))
        assertFalse(gate.canUseWindow(1, 10, resumed = true, shown = true, focused = true, readOnly = true))
        assertFalse(gate.resume(1, 10, "unknown", null, 101))
    }

    fun `real input takeover removes read access as well as action access`() {
        val gate = HostAgentWindowPolicy()
        gate.begin(1, 10)
        gate.revoke(1)
        for (focused in listOf(false, true)) for (readOnly in listOf(false, true)) {
            assertFalse(gate.canUseWindow(1, 10, resumed = true, shown = true, focused = focused, readOnly = readOnly))
        }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostAgentWindowPolicyCases) -> Unit) {
            val s = HostAgentWindowPolicyCases()
            block(s)
        }
        runIsolated { s -> s.`begin owns only the initial window and does not authorize a screenshot yet`() }
        runIsolated { s -> s.`unexpected pause revokes even when later resume carries a matching old intent`() }
        runIsolated { s -> s.`expected navigation permits one pause and a matching target in its time window`() }
        runIsolated { s -> s.`expected navigation does not accept a login screen or different query`() }
        runIsolated { s -> s.`same Activity navigation requires a new matching observation`() }
        runIsolated { s -> s.`deadline cannot be revived by a late matching resume`() }
        runIsolated { s -> s.`matching target exactly at deadline still rejects navigation`() }
        runIsolated { s -> s.`user takeover revokes late navigation and capture despite an unchanged intent`() }
        runIsolated { s -> s.`old timers and old window callbacks cannot revoke a new task or navigation`() }
        runIsolated { s -> s.`settled page identity drift revokes instead of reacquiring an arbitrary foreground page`() }
        runIsolated { s -> s.`system display focus loss preserves only owned resumed visible read access`() }
        runIsolated { s -> s.`read access cannot transfer to another window generation or hidden paused Activity`() }
        runIsolated { s -> s.`opening another Activity still revokes and cannot return through the read exception`() }
        runIsolated { s -> s.`real input takeover removes read access as well as action access`() }
    }
}
