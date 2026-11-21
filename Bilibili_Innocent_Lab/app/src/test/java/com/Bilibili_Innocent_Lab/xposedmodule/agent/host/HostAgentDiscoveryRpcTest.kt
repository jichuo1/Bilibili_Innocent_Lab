package com.Bilibili_Innocent_Lab.xposedmodule.agent.host

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostAgentDiscoveryRpcTest {
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

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (HostAgentDiscoveryRpcTest) -> Unit) {
            val s = HostAgentDiscoveryRpcTest()
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
