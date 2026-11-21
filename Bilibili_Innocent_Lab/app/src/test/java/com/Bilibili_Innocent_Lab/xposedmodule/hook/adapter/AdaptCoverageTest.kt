package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptAssistGate.Attempt
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AdaptCoverageTest {
    private fun plan(vararg families: PointFamily) = AdaptPlan(1L, families.toSet(), emptySet())

    fun `only attempted families are persisted and the round trip is lossless`() {
        val coverage = AdaptCoverage(mapOf(
            PointFamily.FULL_NUMBERS to Attempt.FOUND,
            PointFamily.BANNER to Attempt.CONCLUSIVE_MISSING,
            PointFamily.COMMENT_FILTER to Attempt.RETRYABLE,
            PointFamily.STORY_FEED to Attempt.NOT_REQUESTED
        ))
        val json = coverage.toJson()
        assertEquals(setOf("full_numbers", "banner", "comment_filter"), json.keys().asSequence().toSet())
        val restored = AdaptCoverage.fromJson(JSONObject(json.toString()))
        assertEquals(coverage, restored)
        assertEquals(Attempt.NOT_REQUESTED, restored.attempt(PointFamily.STORY_FEED))
        assertEquals(setOf(PointFamily.FULL_NUMBERS, PointFamily.BANNER, PointFamily.COMMENT_FILTER), restored.attempted)
    }

    fun `unknown families and malformed states never read as covered`() {
        val restored = AdaptCoverage.fromJson(JSONObject()
            .put("future_family", "FOUND")
            .put("full_numbers", "DONE")
            .put("banner", 3)
            .put("story_feed", "FOUND"))
        assertEquals(Attempt.FOUND, restored.attempt(PointFamily.STORY_FEED))
        assertEquals("非法状态按未请求", Attempt.NOT_REQUESTED, restored.attempt(PointFamily.FULL_NUMBERS))
        assertEquals(Attempt.NOT_REQUESTED, restored.attempt(PointFamily.BANNER))
        assertEquals(setOf(PointFamily.STORY_FEED), restored.attempted)
    }

    fun `every family key is unique and stable`() {
        assertEquals(PointFamily.entries.size, PointFamily.entries.map { it.key }.toSet().size)
        for (family in PointFamily.entries) assertSame(family, PointFamily.fromKey(family.key))
        assertNull(PointFamily.fromKey("nope"))
    }

    fun `merging prefers what the live process actually attempted`() {
        val live = AdaptCoverage(mapOf(PointFamily.FULL_NUMBERS to Attempt.CONCLUSIVE_MISSING))
        val cached = AdaptCoverage(mapOf(
            PointFamily.FULL_NUMBERS to Attempt.FOUND, PointFamily.BANNER to Attempt.FOUND
        ))
        val merged = requireNotNull(AdaptCoverage.merge(live, cached))
        assertEquals(Attempt.CONCLUSIVE_MISSING, merged.attempt(PointFamily.FULL_NUMBERS))
        assertEquals(Attempt.FOUND, merged.attempt(PointFamily.BANNER))
        assertSame(cached, AdaptCoverage.merge(null, cached))
        assertSame(live, AdaptCoverage.merge(live, null))
        assertNull(AdaptCoverage.merge(null, null))
    }

    fun `supplement is needed only for families that were never attempted`() {
        val required = plan(PointFamily.FULL_NUMBERS, PointFamily.BANNER, PointFamily.STORY_FEED, PointFamily.MINE_VIP)
        // 旧缓存（覆盖范围未知）不能当成覆盖完整：计划要求的全部待补。
        assertEquals(required.required, AdaptPlanner.missing(null, required))
        assertEquals(required.required, AdaptPlanner.missing(AdaptCoverage.EMPTY, required))
        val coverage = AdaptCoverage(mapOf(
            PointFamily.FULL_NUMBERS to Attempt.FOUND,
            PointFamily.BANNER to Attempt.CONCLUSIVE_MISSING,
            PointFamily.STORY_FEED to Attempt.RETRYABLE
        ))
        // 确认缺失与可重试都不在快路径上触发补适配（只会在本来就要适配的那一轮里重试）。
        assertEquals(setOf(PointFamily.MINE_VIP), AdaptPlanner.missing(coverage, required))
        // 计划里没有的家族不要求覆盖。
        assertEquals(emptySet<PointFamily>(), AdaptPlanner.missing(coverage, plan(PointFamily.FULL_NUMBERS)))
        assertEquals(emptySet<PointFamily>(), AdaptPlanner.missing(null, plan()))
    }

    fun `enabling a feature later requests exactly that family`() {
        val before = AdaptCoverage(mapOf(PointFamily.FULL_NUMBERS to Attempt.FOUND))
        assertTrue(AdaptPlanner.missing(before, plan(PointFamily.FULL_NUMBERS)).isEmpty())
        assertEquals(setOf(PointFamily.SPLASH_ADS), AdaptPlanner.missing(before, plan(PointFamily.FULL_NUMBERS, PointFamily.SPLASH_ADS)))
    }

    fun `executor skips unplanned families without evaluating them`() {
        val calls = mutableListOf<PointFamily>()
        val run = SyncLocatorRun(plan(PointFamily.BANNER), observer = { family, _, _ -> calls += family })
        var evaluated = false
        val skipped = run.locate(PointFamily.STORY_FEED) { evaluated = true; "x" }
        assertNull(skipped)
        assertFalse(evaluated)
        assertEquals(Attempt.NOT_REQUESTED, run.attemptOf(PointFamily.STORY_FEED))
        assertTrue(calls.isEmpty())
        assertEquals(Attempt.NOT_REQUESTED, run.coverage().attempt(PointFamily.STORY_FEED))
    }

    fun `executor records found missing and retryable`() {
        val outcomes = mutableMapOf<PointFamily, Attempt>()
        val run = SyncLocatorRun(plan(PointFamily.BANNER, PointFamily.STORY_FEED, PointFamily.MINE_VIP, PointFamily.FULL_NUMBERS),
            observer = { family, elapsed, outcome ->
                assertTrue(elapsed >= 0)
                outcomes[family] = outcome
            })
        assertEquals("b", run.locate(PointFamily.BANNER) { "b" })
        assertNull(run.locate<String>(PointFamily.STORY_FEED) { null })
        // 找到但不满足「有效」判定（例如暂停页四个子点全空）按确认缺失记。
        assertEquals("", run.locate<String>(PointFamily.MINE_VIP, found = { it.isNotEmpty() }) { "" })
        assertNull(run.locate<String>(PointFamily.FULL_NUMBERS) { throw IllegalStateException("temporary") })
        assertEquals(Attempt.FOUND, run.attemptOf(PointFamily.BANNER))
        assertEquals(Attempt.CONCLUSIVE_MISSING, run.attemptOf(PointFamily.STORY_FEED))
        assertEquals(Attempt.CONCLUSIVE_MISSING, run.attemptOf(PointFamily.MINE_VIP))
        assertEquals("短暂失败可重试，不是确认缺失", Attempt.RETRYABLE, run.attemptOf(PointFamily.FULL_NUMBERS))
        assertEquals(run.coverage().attempted.associateWith(run::attemptOf), outcomes)
        // 链接类错误同样可重试。
        val linkage = SyncLocatorRun(plan(PointFamily.BANNER))
        assertNull(linkage.locate<String>(PointFamily.BANNER) { throw NoClassDefFoundError("x") })
        assertEquals(Attempt.RETRYABLE, linkage.attemptOf(PointFamily.BANNER))
    }

    fun `timings observer summarises the slowest families for device comparisons`() {
        val timings = LocateTimings()
        val run = SyncLocatorRun(plan(PointFamily.BANNER, PointFamily.STORY_FEED, PointFamily.MINE_VIP), observer = timings)
        run.locate(PointFamily.BANNER) { "a" }
        run.locate<String>(PointFamily.STORY_FEED) { null }
        assertEquals(2, timings.familyCount)
        assertTrue(timings.totalNanos >= 0)
        timings.located(PointFamily.MINE_VIP, 9_000_000L, Attempt.FOUND)
        assertEquals(3, timings.familyCount)
        assertTrue(timings.summary(top = 1).contains("slowest=[mine_vip=9000us]"))
        assertTrue(timings.summary().startsWith("families=3/${PointFamily.entries.size}"))
    }

    fun `virtual machine errors are never swallowed`() {
        val run = SyncLocatorRun(plan(PointFamily.BANNER))
        try {
            run.locate<String>(PointFamily.BANNER) { throw StackOverflowError() }
            fail("StackOverflowError 必须继续抛出")
        } catch (expected: StackOverflowError) {
            // 未记录为任何尝试。
            assertEquals(Attempt.NOT_REQUESTED, run.attemptOf(PointFamily.BANNER))
        }
    }

    fun `unplanned families are carried only when an earlier attempt covers them`() {
        val previous = AdaptCoverage(mapOf(PointFamily.BANNER to Attempt.FOUND))
        val run = SyncLocatorRun(plan(PointFamily.STORY_FEED))
        var evaluated = false
        assertEquals("old", run.locateOrCarry(PointFamily.BANNER, "old", previous) { evaluated = true; "new" })
        assertFalse(evaluated)
        // 没有覆盖证明的旧点不带入（旧缓存 / 此前未请求）。
        assertNull(run.locateOrCarry(PointFamily.MINE_VIP, "stale", previous) { "new" })
        assertNull(run.locateOrCarry(PointFamily.BANNER, "legacy", null) { "new" })
        // 计划内永远重新定位，不沿用旧值。
        assertEquals("fresh", run.locateOrCarry(PointFamily.STORY_FEED, "old", previous) { "fresh" })
        assertEquals(Attempt.FOUND, run.attemptOf(PointFamily.STORY_FEED))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (AdaptCoverageTest) -> Unit) {
            val s = AdaptCoverageTest()
            block(s)
        }
        runIsolated { s -> s.`only attempted families are persisted and the round trip is lossless`() }
        runIsolated { s -> s.`unknown families and malformed states never read as covered`() }
        runIsolated { s -> s.`every family key is unique and stable`() }
        runIsolated { s -> s.`merging prefers what the live process actually attempted`() }
        runIsolated { s -> s.`supplement is needed only for families that were never attempted`() }
        runIsolated { s -> s.`enabling a feature later requests exactly that family`() }
        runIsolated { s -> s.`executor skips unplanned families without evaluating them`() }
        runIsolated { s -> s.`executor records found missing and retryable`() }
        runIsolated { s -> s.`timings observer summarises the slowest families for device comparisons`() }
        runIsolated { s -> s.`virtual machine errors are never swallowed`() }
        runIsolated { s -> s.`unplanned families are carried only when an earlier attempt covers them`() }
    
        StoryQualityResourceAccessCases().verifyAllCases()
    }
}

private class StoryQualityResourceAccessCases {
    enum class DrmType { No, WideVine }
    enum class CanWatchReason { CAN_WATCH_QN_REASON_DEFAULT, CAN_WATCH_QN_REASON_VIP_QN_TRY_WATCH, UNKNOWN }
    enum class LimitWatchReason { LIMIT_WATCH_QN_REASON_DEFAULT, LIMIT_WATCH_QN_REASON_CTCC_HOME_NETWORK_DEVICE_LIMIT, UNRECOGNIZED }
    class PlayIndex(@JvmField var mQuality: Int, var available: Boolean = true) {
        @JvmField var mNeedLogin = false
        @JvmField var mNeedVip = false
        @JvmField var mDrmType: DrmType? = DrmType.No
        @JvmField var qualityInvalid = false
        @JvmField var canWatchReason: CanWatchReason? = null
        @JvmField var limitWatchReason: LimitWatchReason? = null
    }
    class VodIndex(@JvmField var mVodList: ArrayList<PlayIndex>)
    class Resource(@JvmField var mVodIndex: VodIndex) {
        var from = SOURCE_FROM_FLASH
        var resolved = 0
        var hdr = false
        var dolby = false
        var vivid = false
        var drm = false
        var helperCalls = 0
        var writes = 0
        var pairOverride: Pair<Int, Int>? = null
        var throwHelper = false
        var throwWriteOnce = false
        var ignoreWrite = false
        var wrongWriteOnce = false
        var failReadBackOnce = false
        var awaitingReadBack = false
        var dashQns: Set<Int> = emptySet()
        fun sourceFrom() = from
        fun getPlayIndex(): PlayIndex? = mVodIndex.mVodList.getOrNull(resolved)
        fun getResolvedIndex(): Int {
            if (awaitingReadBack && failReadBackOnce) {
                failReadBackOnce = false
                throw IllegalStateException("readback fixture")
            }
            return resolved
        }
        fun setResolvedIndex(index: Int) {
            writes++
            if (ignoreWrite) return
            resolved = if (wrongWriteOnce) 0 else index
            wrongWriteOnce = false
            awaitingReadBack = true
            if (throwWriteOnce) {
                throwWriteOnce = false
                throw IllegalStateException("write fixture")
            }
        }
        fun isHdr() = hdr
        fun isDolbyVision() = dolby
        fun isHdrVivid() = vivid
        fun isDrm() = drm || getPlayIndex()?.mDrmType != DrmType.No
        // 忠实模拟宿主：只判地址/DASH，不判登录、会员、试看或限制原因。
        fun findMaxAvailableQuality(target: Int): Pair<Int, Int>? {
            helperCalls++
            check(!throwHelper)
            pairOverride?.let { return it }
            var best: Pair<Int, Int>? = null
            mVodIndex.mVodList.forEachIndexed { index, play ->
                if (play.mQuality in 1..target && (play.available || play.mQuality in dashQns) &&
                    play.mQuality > (best?.second ?: 0)) best = index to play.mQuality
            }
            return best
        }
        companion object {
            // 故意不用 1，确保生产实现核静态常量而非猜 sourceFrom 数字。
            const val SOURCE_FROM_FLASH = 17
            const val SOURCE_FROM_NORMAL = 31
            const val QUALITY_HDR = 300
            const val QUALITY_DOLBY = 301
            const val QUALITY_HDRVivid = 302
        }
    }

    private fun access() = requireNotNull(StoryQualityResourceAccess.resolve(
        Resource::class.java, VodIndex::class.java, PlayIndex::class.java))
    private fun resource(vararg qualities: Int) = Resource(VodIndex(qualities.map { PlayIndex(it) }.toCollection(arrayListOf())))

    fun `proposal is read only and application verifies selected index and quality`() {
        val access = access()
        val resource = resource(16, 64, 80, 120)
        val candidate = requireNotNull(access.selection(resource, 112, login = true, vip = false))
        assertEquals(2, candidate.index)
        assertEquals(80, candidate.qn)
        assertEquals(0, resource.resolved)
        assertEquals(0, resource.writes)
        assertTrue(access.apply(resource, candidate))
        assertEquals(2, resource.resolved)
        assertEquals(80, access.currentQuality(resource))
        assertFalse(access.apply(resource, candidate))
        assertEquals(1, resource.writes)
    }

    fun `source identity uses host constants and unknown source remains unchanged`() {
        val access = access()
        val resource = resource(16, 80)
        assertTrue(access.isPreloaded(resource))
        resource.from = Resource.SOURCE_FROM_NORMAL
        assertFalse(access.isPreloaded(resource))
        assertNotNull(access.selection(resource, 80, true, false))
        resource.from = 1
        assertFalse(access.isPreloaded(resource))
        assertNull(access.selection(resource, 80, true, false))
        assertEquals(0, resource.writes)
    }

    fun `highest available eligible quality respects login and vip independently`() {
        val access = access()
        val resource = resource(16, 64, 80, 120)
        resource.mVodIndex.mVodList[2].mNeedLogin = true
        resource.mVodIndex.mVodList[3].mNeedVip = true
        assertEquals(64, access.selection(resource, 120, false, false)?.qn)
        assertEquals(80, access.selection(resource, 120, true, false)?.qn)
        assertEquals(120, access.selection(resource, 120, true, true)?.qn)
        assertNull(access.selection(resource, 120, false, false, ownVideo = true))
    }

    fun `try watch and device limit never become rights grants`() {
        val access = access()
        val resource = resource(16, 64, 80, 120)
        resource.mVodIndex.mVodList[3].canWatchReason = CanWatchReason.CAN_WATCH_QN_REASON_VIP_QN_TRY_WATCH
        resource.mVodIndex.mVodList[2].limitWatchReason = LimitWatchReason.LIMIT_WATCH_QN_REASON_CTCC_HOME_NETWORK_DEVICE_LIMIT
        assertEquals(64, access.selection(resource, 120, true, true)?.qn)
        resource.mVodIndex.mVodList[0].canWatchReason = CanWatchReason.UNKNOWN
        assertNull(access.selection(resource, 120, true, true))
        assertEquals(0, resource.writes)
    }

    fun `exact default reasons are accepted while invalid or unknown candidate is preserved`() {
        val access = access()
        val resource = resource(16, 80)
        val higher = resource.mVodIndex.mVodList[1]
        higher.canWatchReason = CanWatchReason.CAN_WATCH_QN_REASON_DEFAULT
        higher.limitWatchReason = LimitWatchReason.LIMIT_WATCH_QN_REASON_DEFAULT
        assertEquals(80, access.selection(resource, 80, true, true)?.qn)
        higher.qualityInvalid = true
        assertNull(access.selection(resource, 80, true, true))
        higher.qualityInvalid = false
        higher.limitWatchReason = LimitWatchReason.UNRECOGNIZED
        assertNull(access.selection(resource, 80, true, true))
    }

    fun `special current resources and DRM candidates never change`() {
        val access = access()
        for (setSpecial in listOf<(Resource) -> Unit>({ it.hdr = true }, { it.dolby = true },
            { it.vivid = true }, { it.drm = true })) {
            val resource = resource(16, 80)
            setSpecial(resource)
            assertEquals(true, access.isSpecial(resource))
            assertNull(access.selection(resource, 80, true, true))
            assertEquals(0, resource.writes)
        }
        val resource = resource(16, 80, Resource.QUALITY_HDR)
        resource.mVodIndex.mVodList[1].mDrmType = DrmType.WideVine
        assertNull(access.selection(resource, Resource.QUALITY_HDR, true, true))
        resource.mVodIndex.mVodList[1].mDrmType = null
        assertNull(access.selection(resource, 80, true, true))
        assertNull(access.isSpecial(Any()))
    }

    fun `DASH availability can be used without treating isPlayable as rights check`() {
        val access = access()
        val resource = resource(16, 80)
        resource.mVodIndex.mVodList[1].available = false
        resource.dashQns = setOf(80)
        assertEquals(80, access.selection(resource, 80, true, false)?.qn)
        resource.mVodIndex.mVodList[1].mNeedVip = true
        assertNull(access.selection(resource, 80, true, false))
    }

    fun `host fallback to unavailable candidate is accepted only for another eligible stream`() {
        val access = access()
        val resource = resource(16, 80, 120)
        resource.mVodIndex.mVodList[2].available = false
        assertEquals(80, access.selection(resource, 120, true, false)?.qn)
        val denied = PlayIndex(96).also { it.mNeedVip = true }
        resource.mVodIndex.mVodList.add(denied)
        // 宿主 helper 会越过无地址 120 而命中无权限 96；整提案放行，不能直接写 96。
        assertNull(access.selection(resource, 120, true, false))
    }

    fun `forged host pair cannot bypass quality rights or index correspondence`() {
        val access = access()
        val resource = resource(16, 80, 120)
        resource.mVodIndex.mVodList[2].mNeedVip = true
        for (pair in listOf(1 to 64, 2 to 120, -1 to 80, 9 to 80)) {
            resource.pairOverride = pair
            assertNull(access.selection(resource, 80, true, false))
        }
        assertEquals(0, resource.writes)
    }

    fun `list overflow is rejected before invoking potentially unbounded host helper`() {
        val access = access()
        val resource = Resource(VodIndex(ArrayList(List(129) { PlayIndex(16 + it) })))
        assertNull(access.selection(resource, 200, true, true))
        assertEquals(0, resource.helperCalls)
        resource.mVodIndex.mVodList.removeAt(128)
        assertNotNull(access.selection(resource, 200, true, true))
    }

    @Suppress("UNCHECKED_CAST")
    fun `malformed list item preserves entire original resource`() {
        val access = access()
        val resource = resource(16, 80)
        (resource.mVodIndex.mVodList as MutableList<Any?>).add(Any())
        assertNull(access.selection(resource, 80, true, false))
        assertEquals(0, resource.helperCalls)
    }

    fun `candidate cannot be applied to another resource or changed list`() {
        val access = access()
        val resource = resource(16, 80)
        val candidate = requireNotNull(access.selection(resource, 80, true, false))
        assertFalse(access.apply(resource(16, 80), candidate))
        resource.mVodIndex.mVodList = ArrayList(resource.mVodIndex.mVodList)
        assertFalse(access.apply(resource, candidate))
        assertEquals(0, resource.writes)
    }

    fun `rights or original selection changes after proposal prevent any write`() {
        val access = access()
        val resource = resource(16, 64, 80)
        val candidate = requireNotNull(access.selection(resource, 80, true, false))
        resource.mVodIndex.mVodList[2].mNeedVip = true
        assertFalse(access.apply(resource, candidate))
        assertEquals(0, resource.writes)
        resource.mVodIndex.mVodList[2].mNeedVip = false
        val next = requireNotNull(access.selection(resource, 80, true, false))
        resource.resolved = 1
        assertFalse(access.apply(resource, next))
        assertEquals(1, resource.resolved)
        assertEquals(0, resource.writes)
    }

    fun `setter writing before throwing is restored and never reported as success`() {
        val access = access()
        val resource = resource(16, 80)
        val candidate = requireNotNull(access.selection(resource, 80, true, false))
        resource.throwWriteOnce = true
        assertFalse(access.apply(resource, candidate))
        assertEquals(0, resource.resolved)
        assertEquals(2, resource.writes)
    }

    fun `silent setter failure and readback error restore original index`() {
        val access = access()
        for (setFailure in listOf<(Resource) -> Unit>({ it.ignoreWrite = true },
            { it.wrongWriteOnce = true }, { it.failReadBackOnce = true })) {
            val resource = resource(16, 80)
            val candidate = requireNotNull(access.selection(resource, 80, true, false))
            setFailure(resource)
            assertFalse(access.apply(resource, candidate))
            assertEquals(0, resource.resolved)
        }
    }

    fun `unknown helper error no op and invalid input keep original behavior`() {
        val access = access()
        val resource = resource(16, 80)
        assertNull(access.selection(resource, 0, true, true))
        assertNull(access.selection(resource, 16, true, true))
        resource.throwHelper = true
        assertNull(access.selection(resource, 80, true, true))
        assertEquals(0, resource.writes)
        assertNull(access.currentQuality(Any()))
        assertNull(StoryQualityResourceAccess.resolve(Any::class.java, VodIndex::class.java, PlayIndex::class.java))
    }

    fun `accepted proves exact current best stream instead of absence of proposal`() {
        val access = access()
        val resource = resource(16, 80)
        assertTrue(access.currentEligible(resource, true, false))
        assertFalse(access.accepted(resource, 80, true, false))
        val candidate = requireNotNull(access.selection(resource, 80, true, false))
        assertTrue(access.apply(resource, candidate))
        assertTrue(access.accepted(resource, 80, true, false))
        assertNull(access.selection(resource, 80, true, false))
        resource.throwHelper = true
        assertFalse(access.accepted(resource, 80, true, false))
        assertTrue(access.currentEligible(resource, true, false))
    }

    fun `local accepted lower quality does not claim server target availability`() {
        val access = access()
        val resource = resource(16)
        assertTrue(access.isPreloaded(resource))
        assertEquals(16, access.currentQuality(resource))
        assertTrue(access.currentEligible(resource, true, false))
        assertTrue(access.accepted(resource, 120, true, false))
        assertFalse(access.accepted(resource, 0, true, false))
    }

    fun `current eligibility and accepted both reject unknown rights source and oversized list`() {
        val access = access()
        val resource = resource(16, 80)
        resource.mVodIndex.mVodList[0].limitWatchReason = LimitWatchReason.UNRECOGNIZED
        assertFalse(access.currentEligible(resource, true, true))
        assertFalse(access.accepted(resource, 80, true, true))
        resource.mVodIndex.mVodList[0].limitWatchReason = null
        resource.from = -1
        assertFalse(access.currentEligible(resource, true, true))
        resource.from = Resource.SOURCE_FROM_NORMAL
        repeat(127) { resource.mVodIndex.mVodList.add(PlayIndex(32)) }
        assertFalse(access.currentEligible(resource, true, true))
        assertFalse(access.accepted(resource, 80, true, true))
    }

    enum class LegacyError { WithMultiDeviceLoginErr }
    class LegacyIndex(@JvmField var mQuality: Int) {
        @JvmField var mNeedLogin = false
        @JvmField var mNeedVip = false
        @JvmField var mDrmType = DrmType.No
        @JvmField var mPlayError: LegacyError? = null
    }
    class LegacyVod(@JvmField var mVodList: ArrayList<LegacyIndex>)
    class LegacyResource(@JvmField var mVodIndex: LegacyVod) {
        var resolved = 0
        fun sourceFrom() = SOURCE_FROM_FLASH
        fun getPlayIndex(): LegacyIndex = mVodIndex.mVodList[resolved]
        fun getResolvedIndex() = resolved
        fun setResolvedIndex(index: Int) { resolved = index }
        fun isHdr() = false
        fun isDolbyVision() = false
        fun isDrm() = false
        fun findMaxAvailableQuality(target: Int): Pair<Int, Int>? = mVodIndex.mVodList.withIndex()
            .filter { it.value.mQuality <= target }.maxByOrNull { it.value.mQuality }?.let { it.index to it.value.mQuality }
        companion object {
            const val SOURCE_FROM_FLASH = 17
            const val SOURCE_FROM_NORMAL = 31
            const val QUALITY_HDR = 300
            const val QUALITY_DOLBY = 301
        }
    }

    fun `legacy play error is independent of modern nullable rights shape`() {
        val access = requireNotNull(StoryQualityResourceAccess.resolve(LegacyResource::class.java,
            LegacyVod::class.java, LegacyIndex::class.java))
        val resource = LegacyResource(LegacyVod(arrayListOf(LegacyIndex(16), LegacyIndex(80))))
        assertEquals(80, access.selection(resource, 80, true, false)?.qn)
        resource.mVodIndex.mVodList[1].mPlayError = LegacyError.WithMultiDeviceLoginErr
        assertNull(access.selection(resource, 80, true, false))
        resource.mVodIndex.mVodList[1].mPlayError = null
        resource.mVodIndex.mVodList[0].mPlayError = LegacyError.WithMultiDeviceLoginErr
        assertNull(access.selection(resource, 80, true, false))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (StoryQualityResourceAccessCases) -> Unit) {
            val s = StoryQualityResourceAccessCases()
            block(s)
        }
        runIsolated { s -> s.`proposal is read only and application verifies selected index and quality`() }
        runIsolated { s -> s.`source identity uses host constants and unknown source remains unchanged`() }
        runIsolated { s -> s.`highest available eligible quality respects login and vip independently`() }
        runIsolated { s -> s.`try watch and device limit never become rights grants`() }
        runIsolated { s -> s.`exact default reasons are accepted while invalid or unknown candidate is preserved`() }
        runIsolated { s -> s.`special current resources and DRM candidates never change`() }
        runIsolated { s -> s.`DASH availability can be used without treating isPlayable as rights check`() }
        runIsolated { s -> s.`host fallback to unavailable candidate is accepted only for another eligible stream`() }
        runIsolated { s -> s.`forged host pair cannot bypass quality rights or index correspondence`() }
        runIsolated { s -> s.`list overflow is rejected before invoking potentially unbounded host helper`() }
        runIsolated { s -> s.`malformed list item preserves entire original resource`() }
        runIsolated { s -> s.`candidate cannot be applied to another resource or changed list`() }
        runIsolated { s -> s.`rights or original selection changes after proposal prevent any write`() }
        runIsolated { s -> s.`setter writing before throwing is restored and never reported as success`() }
        runIsolated { s -> s.`silent setter failure and readback error restore original index`() }
        runIsolated { s -> s.`unknown helper error no op and invalid input keep original behavior`() }
        runIsolated { s -> s.`accepted proves exact current best stream instead of absence of proposal`() }
        runIsolated { s -> s.`local accepted lower quality does not claim server target availability`() }
        runIsolated { s -> s.`current eligibility and accepted both reject unknown rights source and oversized list`() }
        runIsolated { s -> s.`legacy play error is independent of modern nullable rights shape`() }
    }
}
