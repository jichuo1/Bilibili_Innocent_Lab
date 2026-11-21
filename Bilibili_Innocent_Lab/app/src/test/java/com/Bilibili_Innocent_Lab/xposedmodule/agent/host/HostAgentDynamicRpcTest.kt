package com.Bilibili_Innocent_Lab.xposedmodule.agent.host

import com.Bilibili_Innocent_Lab.xposedmodule.agent.host.fixtures.dynamic.CardVideoUpList
import com.Bilibili_Innocent_Lab.xposedmodule.agent.host.fixtures.dynamic.DynAllReply
import com.Bilibili_Innocent_Lab.xposedmodule.agent.host.fixtures.dynamic.DynamicItem
import com.Bilibili_Innocent_Lab.xposedmodule.agent.host.fixtures.dynamic.DynamicList
import com.Bilibili_Innocent_Lab.xposedmodule.agent.host.fixtures.dynamic.DynamicMoss
import com.Bilibili_Innocent_Lab.xposedmodule.agent.host.fixtures.dynamic.Extend
import com.Bilibili_Innocent_Lab.xposedmodule.agent.host.fixtures.dynamic.Module
import com.Bilibili_Innocent_Lab.xposedmodule.agent.host.fixtures.dynamic.ModuleDesc
import com.Bilibili_Innocent_Lab.xposedmodule.agent.host.fixtures.dynamic.ModuleDynamic
import com.Bilibili_Innocent_Lab.xposedmodule.agent.host.fixtures.dynamic.MdlDynArchive
import com.Bilibili_Innocent_Lab.xposedmodule.agent.host.fixtures.dynamic.UpListItem
import com.bilibili.lib.moss.api.CallOptions
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HostAgentDynamicRpcTest {
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

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (HostAgentDynamicRpcTest) -> Unit) {
            val s = HostAgentDynamicRpcTest()
            s.reset()
            block(s)
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
