package com.Bilibili_Innocent_Lab.xposedmodule.hook

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.withoutDomains
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptCallback
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptDecision
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptDiagnostic
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptState
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.BannerPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.BooleanMethodChain
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.BottomBarPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.CommentComposeMenuPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.CommentEmptyPagePoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.CommentFilterPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.CommentFollowPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.CommentOptionalPayloadPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.CommentPurifyPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.CommentSectionPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.CommentTopologyPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.DetailRelateServicePoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.DurationMethodChain
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.DynamicTabsPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.FullNumberPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HomeComponentPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HomeRecommendFeedPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HomeTabPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HomeTopBarPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HostIdentity
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.ListConstructorPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.MineAccountMinePoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.MineComponentPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.MineEntryPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.MineVipPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.PausePoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.PgcAutoActivityPopupPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.PlayCountMethodChain
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.PlayerInteractiveGuideFamily
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.PlayerInteractiveOverlayPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.PlayerPortraitPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.PlayerQualityPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.PlayerStatusBarPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.ReasonMethodChain
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.SourceTypeMethodChain
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.SplashAdPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.SplashItemSignalPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.SplitFileMeta
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.StartupCacheSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.StoryFeedPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.TeenagersModePoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.VideoRelatePoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptAssistGate
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptAssistGate.Attempt
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptCacheStore
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptCoverage
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptDomain
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptPlan
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptResultCodec
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptResultMerger
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptResultValidator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.BrandSplashDecoderAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.BrandSplashKntrAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.DetailRelateGameAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.DetailSpecialTagAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.DexFeatureFlags
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.DexFeatureRefreshContext
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.DexFeatureRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.FollowFeedAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.HomeChromeAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.KmpGrpcWireAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.KotlinDefaultWordsLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.LegacyStoryDanmakuAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedMenuAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedSessionLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PointFamily
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PrivacyChannelAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.ProtocolFingerprintBuilder
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.StoryQualityLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.StoryQualityPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SystemEnvironmentAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SystemEnvironmentPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.Theseus2LongPressPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.WeChatShareClasses
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexSourceFingerprint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.locators.MediaQualityAndPlayerLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.DetailUnitedPresentationPurifyPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FollowUpRecommendFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome.HomeChromeSemanticsPoint
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigContract
import com.Bilibili_Innocent_Lab.xposedmodule.testsupport.TestRenamingClassLoader
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class VersionAdapterTest {
    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()
    fun `startup omission survives cached found points without becoming cacheable`() {
        val stored = result().copy(diagnostics = listOf(
            VersionAdapter.AdaptDiagnostic("number.full", VersionAdapter.AdaptState.FOUND, "cached"),
            VersionAdapter.AdaptDiagnostic("protocol.structure", VersionAdapter.AdaptState.FOUND, "cached")))
        val projected = with(VersionAdapter) {
            stored.copy(startupExcluded = setOf("FULL_NUMBERS")).applyStartupExclusions()
        }
        val merged = requireNotNull(VersionAdapter.mergeRuntimeWithCached(projected, stored))
        assertNull(merged.fullNumbers)
        assertEquals(stored.commentLow, merged.commentLow)
        assertTrue(merged.diagnostics.all { it.state == VersionAdapter.AdaptState.NOT_REQUESTED })
        assertTrue(merged.diagnosticSummary().contains("not_requested=2"))
        assertFalse(merged.isUsableWith(stored.hostFingerprint))
        org.junit.Assert.assertThrows(IllegalStateException::class.java) { merged.toJson() }
        assertNotNull(VersionAdapter.AdaptResult.fromJson(stored.toJson()))
        val poisoned = stored.toJson().apply {
            getJSONArray("diag").getJSONObject(0).put("state", "NOT_REQUESTED")
        }
        assertNull(VersionAdapter.AdaptResult.fromJson(poisoned))
    }

    fun `requested live point still wins when another startup family is omitted`() {
        val stored = result()
        val liveNumbers = VersionAdapter.FullNumberPoints(listOf(VersionAdapter.HookPoint("runtime.Numbers", "format")))
        val live = with(VersionAdapter) {
            stored.copy(fullNumbers = liveNumbers, startupExcluded = setOf("COMMENT_PURIFY")).applyStartupExclusions()
        }
        val merged = requireNotNull(VersionAdapter.mergeRuntimeWithCached(live, stored))
        assertEquals(liveNumbers, merged.fullNumbers)
        assertNull(merged.commentPurify)
        assertEquals(stored.commentHigh, merged.commentHigh)
    }

    fun `child projection cannot restore main only relate points from a complete cache`() {
        val stored = result().copy(diagnostics = listOf(
            VersionAdapter.AdaptDiagnostic("video.relate", VersionAdapter.AdaptState.FOUND, "cached")))
        assertNotNull(stored.videoRelate)
        val live = with(VersionAdapter) {
            stored.copy(videoRelate = null, startupExcluded = setOf("VIDEO_RELATE")).applyStartupExclusions()
        }
        val merged = requireNotNull(VersionAdapter.mergeRuntimeWithCached(live, stored))
        assertNull(merged.videoRelate)
        assertEquals(stored.commentLow, merged.commentLow)
        assertEquals(stored.commentHigh, merged.commentHigh)
        assertEquals(stored.banner, merged.banner)
        assertEquals(VersionAdapter.AdaptState.NOT_REQUESTED, merged.diagnostics.single().state)
        assertNotNull(VersionAdapter.AdaptResult.fromJson(stored.toJson()))
    }

    fun `planned locator omits only selected families and full offline lookup remains available`() {
        val loader = requireNotNull(javaClass.classLoader)
        val full = requireNotNull(VersionAdapter.quickLocate(loader))
        assertNotNull(full.commentPurify)
        val partial = requireNotNull(VersionAdapter.quickLocateForStartup(loader, setOf("COMMENT_PURIFY", "COMMENT_TOPOLOGY")))
        assertNull(partial.commentPurify)
        assertNull(partial.commentTopology)
        assertEquals(full.commentLow, partial.commentLow)
        assertEquals(VersionAdapter.AdaptState.NOT_REQUESTED, partial.diagnostics.single { it.id == "comment.purify.search" }.state)
        assertEquals(full.commentPurify, VersionAdapter.quickLocateForStartup(loader, setOf("future-unknown-family"))?.commentPurify)
    }

    fun `main process never prunes a family required by its accepted plan`() {
        val loader = requireNotNull(javaClass.classLoader)
        val plan = com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptPlan.full()
        val result = requireNotNull(VersionAdapter.quickLocateForStartup(
            loader, setOf("COMMENT_PURIFY"), plan, allowPruneRequested = false
        ))
        assertNotNull(result.commentPurify)
        assertTrue("COMMENT_PURIFY" !in result.startupExcluded)
    }

    private val repackDex = "dex-v1:38:" + "a".repeat(64)
    private val repackProtocol = "protocol-v1:1:0123456789abcdef01234567"
    private val repackOld = "tv.danmaku.bili|9090300|9.9.3|1000|100|${VersionAdapterContract.RULE_VERSION}"
    private val repackNew = "tv.danmaku.bili|9090300|9.9.3|2000|200|${VersionAdapterContract.RULE_VERSION}"

    fun `repack preserves verified member results and original timestamp after content agreement`() {
        val previous = result().copy(hostFingerprint = repackOld, dexSourceFingerprint = repackDex)
        val identity = VersionAdapter.HostIdentity(previous.biliVersionCode, repackNew)
        assertNull(VersionAdapter.StartupCacheSnapshot(identity, previous.ts, previous).usableCache(false))
        val rebound = requireNotNull(VersionAdapter.rebindRepackedCache(previous, identity, previous.ts, repackDex, repackProtocol))
        assertEquals(previous.copy(hostFingerprint = repackNew), rebound)
        assertEquals(rebound, VersionAdapter.StartupCacheSnapshot(identity, previous.ts, rebound).usableCache(false))
        assertNull(VersionAdapter.StartupCacheSnapshot(identity, previous.ts + 1, rebound).usableCache(false))
    }

    fun `repack rejects changed unknown or unavailable original dex`() {
        val previous = result().copy(hostFingerprint = repackOld, dexSourceFingerprint = repackDex)
        val identity = VersionAdapter.HostIdentity(previous.biliVersionCode, repackNew)
        for (dex in listOf(null, "unavailable", repackDex.replace('a', 'b'), "dex-v1:0:" + "a".repeat(64)))
            assertNull(VersionAdapter.rebindRepackedCache(previous, identity, 0, dex, repackProtocol))
        assertNull(VersionAdapter.rebindRepackedCache(previous.copy(dexSourceFingerprint = "unavailable"), identity, 0, repackDex, repackProtocol))
    }

    fun `repack cannot bypass version rule reset or structural validity`() {
        val previous = result().copy(hostFingerprint = repackOld, dexSourceFingerprint = repackDex)
        val identity = VersionAdapter.HostIdentity(previous.biliVersionCode, repackNew)
        for (fingerprint in listOf(repackNew.replace("9.9.3", "9.9.4"),
            repackNew.substringBeforeLast('|') + "|${VersionAdapterContract.RULE_VERSION + 1}",
            repackNew.replace("9090300", "9090301"), repackOld, "runtime-no-context|rules=79",
            repackNew.replace("|2000|", "|0|")))
            assertNull(VersionAdapter.rebindRepackedCache(previous, identity.copy(fingerprint = fingerprint), 0, repackDex, repackProtocol))
        assertNull(VersionAdapter.rebindRepackedCache(previous, identity.copy(versionCode = 0), 0, repackDex, repackProtocol))
        assertNull(VersionAdapter.rebindRepackedCache(previous, identity, previous.ts + 1, repackDex, repackProtocol))
        assertNull(VersionAdapter.rebindRepackedCache(previous.copy(commentLow = VersionAdapter.HookPoint("", "")), identity, 0, repackDex, repackProtocol))
    }

    fun `speed menu optional cache and failure history are bound to host and reset`() {
        val point = com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedMenuPoints(
            adapter = VersionAdapter.HookPoint("com.bilibili.playerbizcommonv2.widget.speed.RenamedAdapter", "populate", listOf("float")))
        val id = com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedMenuAdapter.DIAGNOSTIC
        val failed = VersionAdapter.AdaptDiagnostic(id, VersionAdapter.AdaptState.MISSING, "no_match")
        val stored = result().copy(
            playerSpeedMenu = point,
            diagnostics = listOf(failed),
            domainRuleVersions = VersionAdapterContract.DOMAIN_RULE_VERSIONS
        )
        assertEquals(point, VersionAdapter.AdaptResult.fromJson(stored.toJson())?.playerSpeedMenu)
        assertNull(VersionAdapter.AdaptResult.fromJson(result().toJson())?.playerSpeedMenu)
        assertNull(VersionAdapter.AdaptResult.fromJson(stored.toJson().put("player_speed_menu",
            point.copy(adapter = point.adapter!!.copy(paramClassNames = null)).toJson())))
        val live = result().copy(diagnostics = listOf(failed.copy(state = VersionAdapter.AdaptState.NOT_APPLICABLE, detail = "quick-locate")))
        val merged = VersionAdapter.mergeRuntimeWithCached(live, stored)!!
        assertEquals(point, merged.playerSpeedMenu)
        assertEquals(failed, merged.diagnostics.single { it.id == id })
        val snapshot = VersionAdapter.StartupCacheSnapshot(VersionAdapter.HostIdentity(stored.biliVersionCode, stored.hostFingerprint), stored.ts, stored)
        assertEquals(point, snapshot.usableCache(false)?.playerSpeedMenu)
        assertNull(snapshot.copy(identity = snapshot.identity.copy(fingerprint = "different-host")).usableCache(false))
        assertNull(snapshot.copy(resetTimestamp = stored.ts + 1).usableCache(false))
    }

    fun `follow optional descriptor and failure history remain within the same host snapshot`() {
        val point = com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.FollowFeedPoints("iU.d", "f", "tS.G", "tS.H",
            "com.bilibili.bplus.followinglist.home.Q", "current")
        val id = com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.FollowFeedAdapter.DIAGNOSTIC
        val failed = VersionAdapter.AdaptDiagnostic(id, VersionAdapter.AdaptState.MISSING, "no_match")
        val stored = result().copy(
            followFeedStyle = point,
            diagnostics = listOf(failed),
            domainRuleVersions = VersionAdapterContract.DOMAIN_RULE_VERSIONS
        )
        assertEquals(point, VersionAdapter.AdaptResult.fromJson(stored.toJson())?.followFeedStyle)
        assertNull(VersionAdapter.AdaptResult.fromJson(result().toJson())?.followFeedStyle)
        assertNull(VersionAdapter.AdaptResult.fromJson(stored.toJson().put("follow_feed_style", point.copy(modelFamily = "guess").toJson())))
        val live = result().copy(diagnostics = listOf(failed.copy(state = VersionAdapter.AdaptState.NOT_APPLICABLE, detail = "quick-locate")))
        val merged = VersionAdapter.mergeRuntimeWithCached(live, stored)!!
        assertEquals(point, merged.followFeedStyle)
        assertEquals(failed, merged.diagnostics.single { it.id == id })
        val snapshot = VersionAdapter.StartupCacheSnapshot(VersionAdapter.HostIdentity(stored.biliVersionCode, stored.hostFingerprint), stored.ts, stored)
        assertEquals(point, snapshot.usableCache(false)?.followFeedStyle)
        assertNull(snapshot.copy(identity = snapshot.identity.copy(versionCode = stored.biliVersionCode + 1)).usableCache(false))
        assertNull(snapshot.copy(resetTimestamp = stored.ts + 1).usableCache(false))
    }

    fun `brand decoder optional cache preserves validated snapshot and completed failure independently`() {
        val point = VersionAdapter.HookPoint("Nr1.a", "a", listOf(com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.BrandSplashDecoderAdapter.INFO))
        val id = com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.BrandSplashDecoderAdapter.DIAGNOSTIC
        val failed = VersionAdapter.AdaptDiagnostic(id, VersionAdapter.AdaptState.MISSING, "no_match")
        val stored = result().copy(brandSplashDecoder = point, diagnostics = listOf(failed))
        assertEquals(point, VersionAdapter.AdaptResult.fromJson(stored.toJson())?.brandSplashDecoder)
        assertNull(VersionAdapter.AdaptResult.fromJson(result().toJson())?.brandSplashDecoder)
        assertNull(VersionAdapter.AdaptResult.fromJson(stored.toJson().put("brand_splash_decoder", point.copy(paramClassNames = emptyList()).toJson())))
        val live = result().copy(diagnostics = listOf(failed.copy(state = VersionAdapter.AdaptState.NOT_APPLICABLE, detail = "quick-locate")))
        val merged = VersionAdapter.mergeRuntimeWithCached(live, stored)!!
        assertEquals(point, merged.brandSplashDecoder)
        assertEquals(failed, merged.diagnostics.single { it.id == id })
        val snapshot = VersionAdapter.StartupCacheSnapshot(VersionAdapter.HostIdentity(stored.biliVersionCode, stored.hostFingerprint), stored.ts, stored)
        assertEquals(point, snapshot.usableCache(false)?.brandSplashDecoder)
        assertNull(snapshot.copy(identity = snapshot.identity.copy(fingerprint = "different-host")).usableCache(false))
        assertNull(snapshot.copy(resetTimestamp = stored.ts + 1).usableCache(false))
    }

    fun `popup failed diagnostics survive quick merge independently without retaining other missing families`() {
        val families = com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerPopupPromotionAdapter.Family.entries
        val preserved = families.map { VersionAdapter.AdaptDiagnostic(it.diagnostic, VersionAdapter.AdaptState.MISSING, "no_match") }
        val other = VersionAdapter.AdaptDiagnostic("dex.assist.other", VersionAdapter.AdaptState.MISSING, "unrelated")
        val stored = result().copy(diagnostics = preserved + other)
        val live = result().copy(diagnostics = (preserved + other).map { it.copy(state = VersionAdapter.AdaptState.NOT_APPLICABLE, detail = "quick-locate") })
        val merged = VersionAdapter.mergeRuntimeWithCached(live, stored)!!
        for (failure in preserved) assertEquals(failure, merged.diagnostics.single { it.id == failure.id })
        assertEquals(VersionAdapter.AdaptState.NOT_APPLICABLE, merged.diagnostics.single { it.id == other.id }.state)
        val found = live.copy(diagnostics = live.diagnostics.map { it.copy(state = VersionAdapter.AdaptState.FOUND, detail = "direct") })
        assertTrue(VersionAdapter.mergeRuntimeWithCached(found, stored)!!.diagnostics.all { it.state == VersionAdapter.AdaptState.FOUND })
    }

    fun `popup inheritance uses only the same fingerprint version and reset snapshot`() {
        val point = VersionAdapter.HookPoint("com.bilibili.adcommon.data.model.DmAdvert", "getFloatLayers", emptyList())
        val stored = result().copy(commentHigh = null, playerPopupPromotion = com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerPopupPromotionPoints(advertFloat = point))
        val snapshot = VersionAdapter.StartupCacheSnapshot(VersionAdapter.HostIdentity(stored.biliVersionCode, stored.hostFingerprint), stored.ts, stored)
        assertNull(snapshot.usableCache(highCandidateExists = true))
        assertEquals(stored.playerPopupPromotion, snapshot.usableCache(false)?.playerPopupPromotion)
        assertNull(snapshot.copy(resetTimestamp = stored.ts + 1).usableCache(false))
        assertNull(snapshot.copy(identity = snapshot.identity.copy(fingerprint = "changed-host")).usableCache(false))
        assertNull(snapshot.copy(identity = snapshot.identity.copy(versionCode = stored.biliVersionCode + 1)).usableCache(false))
        assertNull(snapshot.copy(cached = null).usableCache(false))
    }

    fun `popup promotion cache is optional rejects malformed members and merges missing families`() {
        val points = com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerPopupPromotionPoints(
            VersionAdapter.HookPoint("com.bilibili.ship.theseus.united.player.mediaplay.network.Guide", "f", listOf("kotlin.coroutines.jvm.internal.ContinuationImpl")),
            VersionAdapter.HookPoint("com.bilibili.adcommon.data.model.DmAdvert", "getFloatLayers", emptyList()))
        val sample = result().copy(playerPopupPromotion = points)
        assertEquals(points, VersionAdapter.AdaptResult.fromJson(sample.toJson())?.playerPopupPromotion)
        assertNull(VersionAdapter.AdaptResult.fromJson(result().toJson())?.playerPopupPromotion)
        assertNull(VersionAdapter.AdaptResult.fromJson(sample.toJson().put("player_popup_promotion", JSONObject().put("free_data_guide", "bad"))))
        val live = sample.copy(playerPopupPromotion = points.copy(advertFloat = null))
        assertEquals(points, VersionAdapter.mergeRuntimeWithCached(live, sample)?.playerPopupPromotion)
    }

    fun `classic comment cache is optional verified and merged per family`() {
        val native = VersionAdapter.HookPoint("com.bilibili.lib.dd.DeviceDecision", "getBoolean", listOf("java.lang.String", "boolean"))
        val kotlin = VersionAdapter.HookPoint("kntr.base.dd.IDeviceDecisionKt", "getBool", listOf("kntr.base.dd.IDeviceDecision", "java.lang.String", "boolean", "kntr.base.dd.IDeviceDecision\$Context"))
        val points = com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CommentClassicStylePoints(listOf(native), listOf(kotlin))
        val sample = result().copy(commentClassicStyle = points)
        assertEquals(points, VersionAdapter.AdaptResult.fromJson(sample.toJson())?.commentClassicStyle)
        assertNull(VersionAdapter.AdaptResult.fromJson(result().toJson())?.commentClassicStyle)
        assertNull(VersionAdapter.AdaptResult.fromJson(sample.toJson().put("comment_classic", JSONObject().put("native", "bad"))))
        val live = sample.copy(commentClassicStyle = points.copy(kotlinReaders = emptyList()))
        assertEquals(points, VersionAdapter.mergeRuntimeWithCached(live, sample)?.commentClassicStyle)
    }
    fun `sponsor optional classes round trip and live results retain priority`() {
        val point = com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SponsorPlayerClasses(
            "com.bilibili.ship.theseus.Run", "com.bilibili.ship.theseus.Wrapper", null, "com.bilibili.ship.theseus.Seek")
        val cached = result().copy(sponsorPlayer = point)
        assertEquals(point, VersionAdapter.AdaptResult.fromJson(cached.toJson())?.sponsorPlayer)
        assertEquals(point, VersionAdapter.mergeRuntimeWithCached(result(), cached)?.sponsorPlayer)
        val live = point.copy(wrapper = "com.bilibili.ship.theseus.NewWrapper")
        assertEquals(live, VersionAdapter.mergeRuntimeWithCached(result().copy(sponsorPlayer = live), cached)?.sponsorPlayer)
        assertNull(VersionAdapter.AdaptResult.fromJson(cached.toJson().apply { remove("sponsor_player") })?.sponsorPlayer)
    }

    fun `K default words optional point survives full cache validation and runtime merge`() {
        val method = com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.KotlinDefaultWordsTest.RenamedSearch::class.java
            .declaredMethods.single { it.name == "x" }
        val point = com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.KotlinDefaultWordsLocator.point(method)
        val home = VersionAdapter.HomeTopBarPoints(null, null, null, emptyList(), point)
        val cached = result().copy(homeTopBar = home)
        assertEquals(home, VersionAdapter.AdaptResult.fromJson(cached.toJson())?.homeTopBar)
        val live = cached.copy(homeTopBar = home.copy(kotlinDefaultWords = null))
        assertEquals(point, VersionAdapter.mergeRuntimeWithCached(live, cached)?.homeTopBar?.kotlinDefaultWords)
        assertNull(VersionAdapter.AdaptResult.fromJson(cached.toJson().apply {
            getJSONObject("home_top").getJSONObject("kotlin_words").put("params", org.json.JSONArray(listOf("java.lang.Object")))
        }))
    }

    fun `cached K default words query failure remains observable in quick merge`() {
        val id = com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.KotlinDefaultWordsLocator.DIAGNOSTIC
        val cached = result().copy(diagnostics = listOf(VersionAdapter.AdaptDiagnostic(id,
            VersionAdapter.AdaptState.MISSING, "no-match")))
        val live = cached.copy(diagnostics = listOf(VersionAdapter.AdaptDiagnostic(id,
            VersionAdapter.AdaptState.NOT_APPLICABLE, "quick-locate")))
        assertEquals(VersionAdapter.AdaptState.MISSING,
            VersionAdapter.mergeRuntimeWithCached(live, cached)?.diagnostics?.single { it.id == id }?.state)
    }

    fun `startup snapshot preserves reset version fingerprint and structural cache rejection`() {
        val cached = result()
        val identity = VersionAdapter.HostIdentity(cached.biliVersionCode, cached.hostFingerprint)
        val snapshot = VersionAdapter.StartupCacheSnapshot(identity, cached.ts, cached)
        assertEquals(cached, snapshot.usableCache(highCandidateExists = true))
        assertNull(snapshot.copy(resetTimestamp = cached.ts + 1).usableCache(true))
        assertNull(snapshot.copy(identity = identity.copy(versionCode = identity.versionCode + 1))
            .usableCache(true))
        assertNull(snapshot.copy(identity = identity.copy(fingerprint = "different-host"))
            .usableCache(true))
        assertNull(snapshot.copy(cached = cached.copy(protocolFingerprint = "invalid"))
            .usableCache(true))
        assertNull(snapshot.copy(cached = null).usableCache(true))
    }

    fun `startup snapshot cannot turn a low only cache into a high capable hit`() {
        val cached = result().copy(commentHigh = null)
        val snapshot = VersionAdapter.StartupCacheSnapshot(
            VersionAdapter.HostIdentity(cached.biliVersionCode, cached.hostFingerprint), 0L, cached
        )
        assertNull(snapshot.usableCache(highCandidateExists = true))
        assertEquals(cached, snapshot.usableCache(highCandidateExists = false))
        // 原始缓存与实时合并结果分别持有，不能把实时 high 点当成磁盘缓存已覆盖。
        val merged = VersionAdapter.mergeRuntimeWithCached(result(), snapshot.cached)
        assertNotNull(merged?.commentHigh)
        assertNull(snapshot.usableCache(highCandidateExists = true))
    }

    fun `PGC construction point participates in full cache validation and merge`() {
        val points = requireNotNull(VersionAdapter.locatePgcAutoActivityPopup(javaClass.classLoader!!))
        val current = result().copy(pgcAutoActivityPopup = points)
        val restored = VersionAdapter.AdaptResult.fromJson(current.toJson())
        assertEquals(points, restored?.pgcAutoActivityPopup)
        assertEquals(62, current.toJson().getInt("sv"))
        assertNull(VersionAdapter.AdaptResult.fromJson(current.toJson().put("sv", 53)))
        assertNull(VersionAdapter.AdaptResult.fromJson(
            current.toJson().apply { getJSONObject("pgc_auto_activity_popup").put("index", -1) }
        ))
        val live = current.copy(pgcAutoActivityPopup = points.copy(propertiesField = "newField"))
        assertEquals(live.pgcAutoActivityPopup,
            VersionAdapter.mergeRuntimeWithCached(live, current)?.pgcAutoActivityPopup)
        assertEquals(points,
            VersionAdapter.mergeRuntimeWithCached(current.copy(pgcAutoActivityPopup = null), current)
                ?.pgcAutoActivityPopup)
    }

    /**
     * 评论过滤的四条判据共用一份缓存结构；@ 与发布者读取路径必须能原样往返，
     * 否则升级后这两条判据会静默退化成"开了但读不到"。
     */
    fun `comment filter judgement points survive the cache round trip`() {
        val current = result()
        val restored = requireNotNull(VersionAdapter.AdaptResult.fromJson(current.toJson()))
        assertEquals(current.commentFilter, restored.commentFilter)
        val points = requireNotNull(restored.commentFilter)
        assertEquals("getAtNameToMidCount", points.atNameCountGetter?.methodName)
        assertEquals("getAtNameToMidMap", points.atNameMapGetter?.methodName)
        assertEquals("getName", points.memberNameGetter?.methodName)
        assertEquals("getMid", points.memberMidGetter?.methodName)

        // 老缓存缺这些键时不能整份作废，只让对应判据缺席。
        val legacy = current.toJson().apply {
            getJSONObject("comment_filter").apply {
                remove("at_count")
                remove("at_map")
                remove("member_name")
                remove("member_mid")
            }
        }
        val downgraded = requireNotNull(VersionAdapter.AdaptResult.fromJson(legacy)).commentFilter
        assertNotNull(downgraded)
        assertNull(downgraded?.atNameCountGetter)
        assertNull(downgraded?.memberMidGetter)
        assertEquals(current.commentFilter?.messageGetter, downgraded?.messageGetter)
    }

    fun `runtime result keeps live points and fills missing dex assisted point from cache`() {
        val cached = result()
        val runtimePoint = VersionAdapter.HookPoint("runtime.Comment", "bind", emptyList())
        val runtime = cached.copy(
            biliVersionCode = 0,
            ts = 0L,
            commentHigh = runtimePoint,
            blockUpdate = null,
            hostFingerprint = "runtime-no-context|rules=41",
            dexSourceFingerprint = "unavailable",
            diagnostics = listOf(
                VersionAdapter.AdaptDiagnostic(
                    "update.block",
                    VersionAdapter.AdaptState.MISSING
                ),
                VersionAdapter.AdaptDiagnostic(
                    "dex.assist",
                    VersionAdapter.AdaptState.NOT_APPLICABLE,
                    "quick-locate"
                )
            )
        )

        val merged = VersionAdapter.mergeRuntimeWithCached(runtime, cached)

        assertEquals(runtimePoint, merged?.commentHigh)
        assertEquals(cached.blockUpdate, merged?.blockUpdate)
        assertEquals(cached.hostFingerprint, merged?.hostFingerprint)
    }

    fun `dex assisted quality implementation wins over the live legacy wrapper`() {
        val helper = VersionAdapter.PlayerQualityPoints(
            VersionAdapter.HookPoint("com.bilibili.playerbizcommon.utils.PlayerSettingHelper", "getDefaultQuality")
        )
        val implementation = VersionAdapter.PlayerQualityPoints(VersionAdapter.HookPoint("Zz9.q", "a"))
        val newest = VersionAdapter.PlayerQualityPoints(VersionAdapter.HookPoint("ut1.h", "a"))
        val cached = result().copy(playerQuality = implementation)

        // 模块落后宿主：实时候选表只落到稳定包装层，后台 DexKit 找到的实现优先。
        assertEquals(
            implementation,
            VersionAdapter.mergeRuntimeWithCached(cached.copy(playerQuality = helper), cached)?.playerQuality
        )
        // 实时定位到真实实现时仍以实时为准。
        assertEquals(
            newest,
            VersionAdapter.mergeRuntimeWithCached(cached.copy(playerQuality = newest), cached)?.playerQuality
        )
        // 8.84.0–8.87.0：包装层本身就是实现，缓存同样是它，不换。
        val legacyCached = cached.copy(playerQuality = helper)
        assertEquals(
            helper,
            VersionAdapter.mergeRuntimeWithCached(legacyCached, legacyCached)?.playerQuality
        )
    }

    fun `dex assisted quality method skips the candidate table`() {
        val assisted = Class.forName("gh6.h").getDeclaredMethod("c")
        val point = VersionAdapter.locateDefaultVideoQuality(requireNotNull(javaClass.classLoader), assisted)
            ?.defaultQualityMethod
        assertEquals("gh6.h", point?.className)
        assertEquals("c", point?.methodName)
    }

    internal fun result(): VersionAdapter.AdaptResult = VersionAdapter.AdaptResult(
        biliVersionCode = 9090300,
        ts = 1234L,
        commentLow = VersionAdapter.HookPoint("comment.Low", "bind"),
        commentHigh = VersionAdapter.HookPoint(
            "comment.High",
            "bind",
            listOf("int", "android.view.View")
        ),
        mineEntry = null,
        pause = VersionAdapter.PausePoints(
            requestMethods = listOf(VersionAdapter.HookPoint("paused.Request", "invokeSuspend")),
            legacyCallback = null,
            panelShow = null,
            countdown = null
        ),
        banner = null,
        homeTopBar = VersionAdapter.HomeTopBarPoints(
            gameMenu = VersionAdapter.HookPoint(
                "home.Menu",
                "bind",
                listOf("android.view.Menu", "android.view.MenuInflater"),
                viewField = "config"
            ),
            composeGameMenu = VersionAdapter.HookPoint(
                "home.TopRight",
                "invoke",
                listOf("java.util.List", "kotlin.coroutines.Continuation")
            ),
            baseOnViewCreated = VersionAdapter.HookPoint(
                "home.Base",
                "onViewCreated",
                listOf("android.view.View", "android.os.Bundle"),
                viewField = "searchText"
            ),
            defaultWordMethods = listOf(
                VersionAdapter.HookPoint("home.Main", "setWord", listOf("word.Model"))
            )
        ),
        mineVip = VersionAdapter.MineVipPoint(
            onResume = VersionAdapter.HookPoint(
                "tv.danmaku.bili.ui.main2.mine.HomeUserCenterFragment",
                "onResume",
                emptyList(),
                viewField = "vipManager"
            ),
            bindingField = "stableBinding",
            rootGetter = VersionAdapter.HookPoint(
                "tv.danmaku.bili.ui.main2.mine.modularvip.TestVipBinding",
                "getRoot",
                emptyList()
            )
        ),
        blockUpdate = VersionAdapter.HookPoint(
            "vd6.c",
            "c",
            listOf("android.content.Context")
        ),
        dynamicTabs = VersionAdapter.DynamicTabsPoint(
            listGetter = VersionAdapter.HookPoint("dynamic.Mediator", "tabs", emptyList()),
            addTab = VersionAdapter.HookPoint(
                "com.google.android.material.tabs.TabLayout",
                "addTab",
                listOf("com.google.android.material.tabs.TabLayout\$Tab", "boolean")
            ),
            tabCustomViewGetter = VersionAdapter.HookPoint(
                "com.google.android.material.tabs.TabLayout\$Tab",
                "getCustomView",
                emptyList()
            ),
            mediatorTabClassName = "dynamic.MediatorTabLayout",
            itemClassName = "dynamic.TabItem",
            itemTitleField = "a",
            itemNameField = "b"
        ),
        fullNumbers = VersionAdapter.FullNumberPoints(
            listOf(
                VersionAdapter.HookPoint(
                    "kntr.base.localization.NumberFormat_androidKt",
                    "format",
                    listOf("java.lang.Long")
                )
            )
        ),
        playerPortrait = VersionAdapter.PlayerPortraitPoints(
            listOf(
                VersionAdapter.HookPoint(
                    "com.bilibili.app.gemini.player.widget.story.GeminiPlayerFullStoryWidget",
                    "setVisibility",
                    listOf("int")
                )
            )
        ),
        playerStatusBar = VersionAdapter.PlayerStatusBarPoints(
            listOf(
                VersionAdapter.HookPoint(
                    "com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity",
                    "onCreate",
                    listOf("android.os.Bundle")
                )
            )
        ),
        homeRecommendFeed = VersionAdapter.HomeRecommendFeedPoints(
            responseItemGetters = listOf(
                VersionAdapter.HookPoint(
                    "com.bilibili.pegasus.data.base.PegasusResponse",
                    "getItems",
                    emptyList()
                )
            ),
            holderTypeGetter = VersionAdapter.HookPoint(
                "com.bilibili.pegasus.PegasusHolderData",
                "getHolderType",
                emptyList()
            ),
            bizTypeGetter = VersionAdapter.HookPoint(
                "com.bilibili.pegasus.data.base.BasePegasusData",
                "getBizType",
                emptyList()
            ),
            adInfoGetter = VersionAdapter.HookPoint(
                "com.bilibili.pegasus.data.base.BasePegasusData",
                "getAdInfo",
                emptyList()
            ),
            cardGotoGetter = VersionAdapter.HookPoint(
                "com.bilibili.pegasus.data.base.BasePegasusData",
                "getCardGoto",
                emptyList()
            ),
            goToGetter = VersionAdapter.HookPoint(
                "com.bilibili.pegasus.data.base.BasePegasusData",
                "getGoTo",
                emptyList()
            ),
            uriGetter = VersionAdapter.HookPoint(
                "com.bilibili.pegasus.data.base.BasePegasusData",
                "getUri",
                emptyList()
            ),
            paramGetter = VersionAdapter.HookPoint(
                "com.bilibili.pegasus.data.base.BasePegasusData",
                "getParam",
                emptyList()
            ),
            titleGetter = VersionAdapter.HookPoint(
                "com.bilibili.pegasus.data.base.BasePegasusData",
                "getTitle",
                emptyList()
            ),
            subtitleGetter = VersionAdapter.HookPoint(
                "com.bilibili.pegasus.data.base.BasePegasusData",
                "getSubtitle",
                emptyList()
            ),
            descGetter = VersionAdapter.HookPoint(
                "com.bilibili.pegasus.data.base.BasePegasusData",
                "getDesc",
                emptyList()
            ),
            playerArgsGetter = VersionAdapter.HookPoint(
                "com.bilibili.pegasus.data.base.BasePegasusData",
                "getPlayerArgs",
                emptyList()
            ),
            playerArgsDurationField = "fakeDuration",
            intentHandlerOnCreate = VersionAdapter.HookPoint(
                "tv.danmaku.bili.ui.intent.IntentHandlerActivity",
                "onCreate",
                listOf("android.os.Bundle")
            )
        ),
        videoRelate = VersionAdapter.VideoRelatePoints(
            responseItemGetters = listOf(
                VersionAdapter.HookPoint(
                    "com.bapis.bilibili.app.viewunite.v1.Relates",
                    "getCardsList",
                    emptyList()
                ),
                VersionAdapter.HookPoint(
                    "com.bapis.bilibili.app.view.v1.RelatesFeedReply",
                    "getListList",
                    emptyList()
                )
            ),
            cardCaseGetters = listOf(
                VersionAdapter.HookPoint(
                    "com.bapis.bilibili.app.viewunite.common.RelateCard",
                    "getCardCase",
                    emptyList()
                )
            ),
            gotoGetters = listOf(
                VersionAdapter.HookPoint(
                    "com.bapis.bilibili.app.view.v1.Relate",
                    "getGoto",
                    emptyList()
                )
            ),
            cardTypeGetters = emptyList(),
            relateCardTypeGetters = listOf(
                VersionAdapter.HookPoint(
                    "com.bapis.bilibili.app.viewunite.common.RelateCard",
                    "getRelateCardType",
                    emptyList()
                )
            ),
            fromSourceTypeGetters = listOf(
                VersionAdapter.HookPoint(
                    "com.bapis.bilibili.app.view.v1.Relate",
                    "getFromSourceType",
                    emptyList()
                )
            ),
            fromSourceTypeChains = listOf(
                VersionAdapter.SourceTypeMethodChain(
                    itemGetter = VersionAdapter.HookPoint(
                        "com.bapis.bilibili.app.viewunite.common.RelateCard",
                        "getBasicInfo",
                        emptyList()
                    ),
                    sourceTypeGetter = VersionAdapter.HookPoint(
                        "com.bapis.bilibili.app.viewunite.common.CardBasicInfo",
                        "getFromSourceType",
                        emptyList()
                    )
                )
            ),
            relateCardTypeValueGetters = listOf(
                VersionAdapter.HookPoint(
                    "com.bapis.bilibili.app.viewunite.common.RelateCard",
                    "getRelateCardTypeValue",
                    emptyList()
                )
            ),
            directDurationGetters = listOf(
                VersionAdapter.HookPoint(
                    "com.bapis.bilibili.app.view.v1.Relate",
                    "getDuration",
                    emptyList()
                )
            ),
            durationChains = listOf(
                VersionAdapter.DurationMethodChain(
                    itemGetter = VersionAdapter.HookPoint(
                        "com.bapis.bilibili.app.viewunite.common.RelateCard",
                        "getAv",
                        emptyList()
                    ),
                    durationGetter = VersionAdapter.HookPoint(
                        "com.bapis.bilibili.app.viewunite.common.RelateAVCard",
                        "getDuration",
                        emptyList()
                    )
                ),
                VersionAdapter.DurationMethodChain(
                    itemGetter = VersionAdapter.HookPoint(
                        "com.bapis.bilibili.app.viewunite.common.RelateCard",
                        "getHistoryAv",
                        emptyList()
                    ),
                    durationGetter = VersionAdapter.HookPoint(
                        "com.bapis.bilibili.app.viewunite.common.RelateHistoryAVCard",
                        "getDuration",
                        emptyList()
                    )
                ),
                VersionAdapter.DurationMethodChain(
                    itemGetter = VersionAdapter.HookPoint(
                        "com.bapis.bilibili.app.viewunite.common.RelateCard",
                        "getAiCard",
                        emptyList()
                    ),
                    durationGetter = VersionAdapter.HookPoint(
                        "com.bapis.bilibili.app.viewunite.common.RelatedAICard",
                        "getDuration",
                        emptyList()
                    )
                )
            ),
            reasonChains = listOf(
                VersionAdapter.ReasonMethodChain(
                    steps = listOf(
                        VersionAdapter.HookPoint(
                            "com.bapis.bilibili.app.view.v1.Relate",
                            "getRcmdReason",
                            emptyList()
                        )
                    )
                )
            ),
            commercialEvidenceChains = listOf(
                VersionAdapter.BooleanMethodChain(
                    steps = listOf(
                        VersionAdapter.HookPoint(
                            "com.bapis.bilibili.app.viewunite.common.RelateCard",
                            "getThreePoint",
                            emptyList()
                        ),
                        VersionAdapter.HookPoint(
                            "com.bapis.bilibili.app.viewunite.common.RelateThreePoint",
                            "hasFeedback",
                            emptyList()
                        )
                    )
                )
            ),
            detailRelateService = VersionAdapter.DetailRelateServicePoint(
                componentFactory = VersionAdapter.HookPoint(
                    "com.bilibili.ship.theseus.united.page.intro.module.relate.DetailRelateService",
                    "d",
                    listOf(
                        "com.bilibili.ship.theseus.united.page.intro.module.relate.D0"
                    )
                ),
                typeField = "type",
                typeGetter = VersionAdapter.HookPoint(
                    "com.bilibili.ship.theseus.united.page.intro.module.relate.D0",
                    "getType",
                    emptyList()
                ),
                titleGetter = VersionAdapter.HookPoint(
                    "com.bilibili.ship.theseus.united.page.intro.module.relate.D0",
                    "d",
                    emptyList()
                )
            )
        ),
        homeTabs = VersionAdapter.HomeTabPoints(
            buildMethod = VersionAdapter.HookPoint(
                "tv.danmaku.bili.ui.main2.HomeFragmentV2",
                "build",
                listOf("java.util.List")
            ),
            resourceClassName = "tv.danmaku.bili.ui.main2.resource.z",
            idField = "id",
            titleField = "title",
            uriField = "uri",
            reporterIdField = "reporterId"
        ),
        homeComponents = VersionAdapter.HomeComponentPoints(
            onViewCreated = VersionAdapter.HookPoint(
                "androidx.fragment.app.Fragment",
                "onViewCreated",
                listOf("android.view.View", "android.os.Bundle")
            ),
            parentFragmentGetter = VersionAdapter.HookPoint(
                "androidx.fragment.app.Fragment",
                "getParentFragment",
                emptyList()
            )
        ),
        mineComponents = VersionAdapter.MineComponentPoints(
            itemListGetters = listOf(
                VersionAdapter.HookPoint(
                    "com.bilibili.lib.homepage.mine.MenuGroupV2",
                    "getItemList",
                    emptyList()
                )
            ),
            itemTitleGetters = listOf(
                VersionAdapter.HookPoint(
                    "com.bilibili.lib.homepage.mine.MenuGroupV2\$Item",
                    "getTitle",
                    emptyList()
                )
            )
        ),
        storyFeed = VersionAdapter.StoryFeedPoints(
            responseItemGetters = listOf(
                VersionAdapter.HookPoint(
                    "com.bilibili.video.story.api.StoryFeedResponse",
                    "getItems",
                    emptyList()
                )
            ),
            pagerListMethods = listOf(
                VersionAdapter.HookPoint(
                    "com.bilibili.video.story.player.StoryPagerPlayer",
                    "append",
                    listOf("java.util.List")
                )
            ),
            adGetter = VersionAdapter.HookPoint(
                "com.bilibili.video.story.StoryDetail",
                "isAd",
                emptyList()
            ),
            liveGetter = VersionAdapter.HookPoint(
                "com.bilibili.video.story.StoryDetail",
                "isLive",
                emptyList()
            ),
            gameGetter = VersionAdapter.HookPoint(
                "com.bilibili.video.story.StoryDetail",
                "isGame",
                emptyList()
            ),
            bangumiGetter = VersionAdapter.HookPoint(
                "com.bilibili.video.story.StoryDetail",
                "isBangumi",
                emptyList()
            ),
            courseGetter = VersionAdapter.HookPoint(
                "com.bilibili.video.story.StoryDetail",
                "isCheese",
                emptyList()
            ),
            musicGetter = VersionAdapter.HookPoint(
                "com.bilibili.video.story.StoryDetail",
                "isMusic",
                emptyList()
            ),
            cartInfoGetter = VersionAdapter.HookPoint(
                "com.bilibili.video.story.StoryDetail",
                "getCartIconInfo",
                emptyList()
            ),
            dramaPromptGetter = VersionAdapter.HookPoint(
                "com.bilibili.video.story.StoryDetail",
                "getDramaPromptBar",
                emptyList()
            ),
            seasonInfoGetter = VersionAdapter.HookPoint(
                "com.bilibili.video.story.StoryDetail",
                "getSeasonInfo",
                emptyList()
            ),
            seasonTypeGetter = VersionAdapter.HookPoint(
                "com.bilibili.video.story.StoryDetail\$SeasonCardInfo",
                "getSeasonType",
                emptyList()
            )
        ),
        bottomBar = VersionAdapter.BottomBarPoints(
            tabsGetter = VersionAdapter.HookPoint(
                "com.bilibili.lib.homepage.widget.TabHost",
                "getTabs",
                emptyList()
            ),
            bindTabMethod = VersionAdapter.HookPoint(
                "com.bilibili.lib.homepage.widget.TabHost",
                "bind",
                listOf("int", "android.view.View")
            ),
            itemClassName = "com.bilibili.lib.homepage.widget.TabHost\$h",
            itemStringFields = listOf("name", "uri")
        ),
        playerQuality = VersionAdapter.PlayerQualityPoints(
            VersionAdapter.HookPoint("gh6.h", "c", emptyList())
        ),
        teenagersMode = VersionAdapter.TeenagersModePoints(
            listOf(
                VersionAdapter.HookPoint(
                    "com.bilibili.teenagersmode.ui.TeenagersModeDialogActivity",
                    "onCreate",
                    listOf("android.os.Bundle")
                )
            )
        ),
        commentPurify = VersionAdapter.CommentPurifyPoints(
            listOf(
                VersionAdapter.HookPoint(
                    "com.bapis.bilibili.main.community.reply.v1.Content",
                    "getUrlsMap",
                    emptyList()
                )
            ),
            listOf(
                VersionAdapter.CommentEmptyPagePoint(
                    contentGetter = VersionAdapter.HookPoint(
                        "com.bapis.bilibili.main.community.reply.v1.SubjectControl",
                        "getEmptyPage",
                        emptyList()
                    ),
                    defaultInstanceGetter = VersionAdapter.HookPoint(
                        "com.bapis.bilibili.main.community.reply.v1.EmptyPage",
                        "getDefaultInstance",
                        emptyList()
                    )
                )
            ),
            listOf(
                VersionAdapter.HookPoint(
                    "com.bilibili.app.comment3.ui.widget.CommentVoteView",
                    "setVoteData",
                    listOf("comment.VoteData")
                )
            ),
            VersionAdapter.CommentFollowPoints(
                widgetStateMethods = listOf(
                    VersionAdapter.HookPoint(
                        "com.bilibili.app.comm.comment2.phoenix.view.CommentFollowWidget",
                        "bind",
                        listOf("comment.FollowData")
                    )
                ),
                headerBindMethods = listOf(
                    VersionAdapter.HookPoint(
                        "com.bilibili.app.comment3.ui.widget.CommentHeaderDecorativeView",
                        "bind",
                        listOf("java.util.List", "comment.HeaderContext")
                    )
                ),
                followButtonClassName = "com.bilibili.relation.widget.FollowButton"
            ),
            VersionAdapter.CommentOptionalPayloadPoint(
                presenceGetter = VersionAdapter.HookPoint(
                    "com.bapis.bilibili.main.community.reply.v1.MainListReply",
                    "hasQoe",
                    emptyList()
                ),
                contentGetter = VersionAdapter.HookPoint(
                    "com.bapis.bilibili.main.community.reply.v1.MainListReply",
                    "getQoe",
                    emptyList()
                ),
                defaultInstanceGetter = VersionAdapter.HookPoint(
                    "com.bapis.bilibili.main.community.reply.v1.QoeInfo",
                    "getDefaultInstance",
                    emptyList()
                )
            ),
            listOf(
                VersionAdapter.CommentOptionalPayloadPoint(
                    presenceGetter = VersionAdapter.HookPoint(
                        "com.bapis.bilibili.main.community.reply.v1.MainListReply",
                        "hasOperation",
                        emptyList()
                    ),
                    contentGetter = VersionAdapter.HookPoint(
                        "com.bapis.bilibili.main.community.reply.v1.MainListReply",
                        "getOperation",
                        emptyList()
                    ),
                    defaultInstanceGetter = VersionAdapter.HookPoint(
                        "com.bapis.bilibili.main.community.reply.v1.Operation",
                        "getDefaultInstance",
                        emptyList()
                    )
                ),
                VersionAdapter.CommentOptionalPayloadPoint(
                    presenceGetter = VersionAdapter.HookPoint(
                        "com.bapis.bilibili.main.community.reply.v1.MainListReply",
                        "hasOperationV2",
                        emptyList()
                    ),
                    contentGetter = VersionAdapter.HookPoint(
                        "com.bapis.bilibili.main.community.reply.v1.MainListReply",
                        "getOperationV2",
                        emptyList()
                    ),
                    defaultInstanceGetter = VersionAdapter.HookPoint(
                        "com.bapis.bilibili.main.community.reply.v1.OperationV2",
                        "getDefaultInstance",
                        emptyList()
                    )
                )
            ),
            listOf(
                VersionAdapter.HookPoint(
                    "com.bilibili.app.comment3.ui.CommentContainerImpl\$attachRepository\$5",
                    "emit",
                    listOf(
                        "com.bilibili.app.comment3.data.state.PublishDialogIntent",
                        "kotlin.coroutines.Continuation"
                    )
                )
            )
        ),
        commentFilter = VersionAdapter.CommentFilterPoints(
            replyListGetters = listOf(
                VersionAdapter.HookPoint(
                    "com.bapis.bilibili.main.community.reply.v1.MainListReply",
                    "getRepliesList",
                    emptyList()
                ),
                VersionAdapter.HookPoint(
                    "com.bapis.bilibili.main.community.reply.v1.ReplyInfo",
                    "getRepliesList",
                    emptyList()
                )
            ),
            contentGetter = VersionAdapter.HookPoint(
                "com.bapis.bilibili.main.community.reply.v1.ReplyInfo",
                "getContent",
                emptyList()
            ),
            messageGetter = VersionAdapter.HookPoint(
                "com.bapis.bilibili.main.community.reply.v1.Content",
                "getMessage",
                emptyList()
            ),
            memberGetter = VersionAdapter.HookPoint(
                "com.bapis.bilibili.main.community.reply.v1.ReplyInfo",
                "getMember",
                emptyList()
            ),
            levelGetter = VersionAdapter.HookPoint(
                "com.bapis.bilibili.main.community.reply.v1.Member",
                "getLevel",
                emptyList()
            ),
            atNameCountGetter = VersionAdapter.HookPoint(
                "com.bapis.bilibili.main.community.reply.v1.Content",
                "getAtNameToMidCount",
                emptyList()
            ),
            atNameMapGetter = VersionAdapter.HookPoint(
                "com.bapis.bilibili.main.community.reply.v1.Content",
                "getAtNameToMidMap",
                emptyList()
            ),
            memberNameGetter = VersionAdapter.HookPoint(
                "com.bapis.bilibili.main.community.reply.v1.Member",
                "getName",
                emptyList()
            ),
            memberMidGetter = VersionAdapter.HookPoint(
                "com.bapis.bilibili.main.community.reply.v1.Member",
                "getMid",
                emptyList()
            )
        ),
        commentSection = VersionAdapter.CommentSectionPoints(
            listConstructors = listOf(
                VersionAdapter.ListConstructorPoint(
                    "com.bilibili.ship.theseus.united.page.tab.TabConfig",
                    listOf("java.util.List", "java.lang.String", "java.lang.String"),
                    0
                )
            ),
            locatableTagGetter = VersionAdapter.HookPoint(
                "com.bilibili.ship.theseus.united.page.tab.TabPage",
                "getLocatableTag",
                emptyList()
            )
        ),
        splashAds = VersionAdapter.SplashAdPoints(
            listOf(
                VersionAdapter.HookPoint(
                    "tv.danmaku.bili.splash.ad.model.SplashListResponse",
                    "getSplashList",
                    emptyList()
                )
            )
        ),
        hostFingerprint = "host|9090300|rules=24",
        protocolFingerprint = "protocol-v1:1:0123456789abcdef01234567",
        diagnostics = listOf(
            VersionAdapter.AdaptDiagnostic(
                "comment.low",
                VersionAdapter.AdaptState.FOUND,
                "comment.Low#bind"
            ),
            VersionAdapter.AdaptDiagnostic(
                "home.banner",
                VersionAdapter.AdaptState.NOT_APPLICABLE
            )
        ),
        domainRuleVersions = VersionAdapterContract.DOMAIN_RULE_VERSIONS
    )

    fun `round trips schema fingerprint and diagnostics`() {
        val source = result()
        val restored = VersionAdapter.AdaptResult.fromJson(
            JSONObject(source.toJson().toString())
        )

        assertEquals(source, restored)
        assertTrue(requireNotNull(restored).isUsableWith(source.hostFingerprint))
        assertFalse(restored.isUsableWith("different-host"))
        assertEquals("found=1,missing=0,not_applicable=1", restored.diagnosticSummary())
    }

    fun `locates player interactive overlays from stable protobuf stubs`() {
        val points = requireNotNull(
            VersionAdapter.locatePlayerInteractiveOverlays(
                requireNotNull(javaClass.classLoader)
            )
        )
        // 精确集合，不是 contains：类路径上还放了一个同样带 `.viewunite.` 的诱饵包
        // （com.bapis.bilibili.mall.tab3.viewunite.v1），任何"按包名子串放宽匹配"的改动都会在这里挂。
        assertEquals(
            setOf(
                "com.bapis.bilibili.app.view.v1.ViewProgressReply",
                "com.bapis.bilibili.app.viewunite.v1.ViewProgressReply"
            ),
            points.families.map { it.replyClassName }.toSet()
        )
        assertTrue(
            points.families.none { family ->
                family.guideClears.any { it.methodName == "clearVideoPoint" }
            }
        )
        assertNotNull(points.commandClear)
        // 运营活动横幅（TV 版推广）：文案烘焙在图里，只能按字段清。
        assertEquals(
            "clearActivityMeta",
            requireNotNull(points.commandActivityMetaClear).methodName
        )
        assertTrue(points.mossExecutes.any { it.methodName == "executeViewProgress" })
        assertTrue(points.mossExecutes.any { it.methodName == "executeDmView" })
    }

    fun `player interactive families carry their own whitelist and default instance`() {
        val points = requireNotNull(
            VersionAdapter.locatePlayerInteractiveOverlays(
                requireNotNull(javaClass.classLoader)
            )
        )
        val byReply = points.families.associateBy { it.replyClassName }
        val viewV1 = requireNotNull(byReply["com.bapis.bilibili.app.view.v1.ViewProgressReply"])
        val unite = requireNotNull(
            byReply["com.bapis.bilibili.app.viewunite.v1.ViewProgressReply"]
        )
        assertEquals(6, viewV1.guideClears.size)
        assertEquals(
            listOf("clearContractCard", "clearMaterial", "clearRightMaterial"),
            unite.guideClears.map { it.methodName }
        )
        // 空 Guide 判定依赖它；缺了就只能退回"无条件清 + 恒报生效"。
        points.families.forEach { family ->
            val default = requireNotNull(family.guideDefault)
            assertEquals("getDefaultInstance", default.methodName)
        }
        assertNotNull(points.commandDefault)

        // 第二载体 DmResource 只存在于 viewunite；view.v1 的 ViewProgressReply 没有 dm 字段。
        assertEquals("getDm", requireNotNull(unite.dmGetter).methodName)
        assertEquals(
            listOf("clearAttention", "clearCards", "clearCommandDms"),
            unite.dmClears.map { it.methodName }
        )
        assertEquals("getDefaultInstance", requireNotNull(unite.dmDefault).methodName)
        assertNull(viewV1.dmGetter)
        assertTrue(viewV1.dmClears.isEmpty())
    }

    fun `player interactive points survive a cache round trip`() {
        val points = requireNotNull(
            VersionAdapter.locatePlayerInteractiveOverlays(
                requireNotNull(javaClass.classLoader)
            )
        )
        val restored = VersionAdapter.PlayerInteractiveOverlayPoints.fromJson(points.toJson())
        assertEquals(points, restored)
    }

    fun `quick adaptation emits a versioned protocol structure fingerprint`() {
        val located = requireNotNull(
            VersionAdapter.quickLocate(requireNotNull(javaClass.classLoader))
        )

        assertTrue(
            located.protocolFingerprint.matches(
                Regex("protocol-v1:[1-9][0-9]*:[0-9a-f]{24}")
            )
        )
        assertEquals(
            located.protocolFingerprint,
            located.diagnostics.single { it.id == "protocol.structure" }.detail
        )
    }

    fun `rejects stale schema and structurally invalid hook point`() {
        val stale = JSONObject(result().toJson().toString()).put("sv", 9)
        val invalid = JSONObject(result().toJson().toString()).apply {
            getJSONObject("low").put("m", "")
        }
        val incompleteHomeDuration = JSONObject(result().toJson().toString()).apply {
            getJSONObject("home_recommend_feed").remove("player_args_duration")
        }
        val invalidHomeIntentHandler = JSONObject(result().toJson().toString()).apply {
            getJSONObject("home_recommend_feed")
                .getJSONObject("intent_handler_on_create")
                .put("m", "")
        }
        val invalidRelateDurationChain = JSONObject(result().toJson().toString()).apply {
            getJSONObject("video_relate")
                .getJSONArray("duration_chains")
                .getJSONObject(0)
                .getJSONObject("duration")
                .put("m", "")
        }
        val invalidRelateSourceType = JSONObject(result().toJson().toString()).apply {
            getJSONObject("video_relate")
                .getJSONArray("source_type")
                .getJSONObject(0)
                .put("m", "")
        }
        val invalidRelateSourceTypeChain = JSONObject(result().toJson().toString()).apply {
            getJSONObject("video_relate")
                .getJSONArray("source_type_chains")
                .getJSONObject(0)
                .getJSONObject("source")
                .put("m", "")
        }
        val invalidRelateReasonChain = JSONObject(result().toJson().toString()).apply {
            getJSONObject("video_relate")
                .getJSONArray("reason_chains")
                .getJSONObject(0)
                .getJSONArray("steps")
                .getJSONObject(0)
                .put("m", "")
        }
        val invalidRelateCommercialChain = JSONObject(result().toJson().toString()).apply {
            getJSONObject("video_relate")
                .getJSONArray("commercial_evidence_chains")
                .getJSONObject(0)
                .getJSONArray("steps")
                .getJSONObject(1)
                .put("m", "")
        }
        val invalidRelateService = JSONObject(result().toJson().toString()).apply {
            getJSONObject("video_relate")
                .getJSONObject("detail_relate_service")
                .getJSONObject("factory")
                .put("m", "")
        }

        assertNull(VersionAdapter.AdaptResult.fromJson(stale))
        assertNull(VersionAdapter.AdaptResult.fromJson(invalid))
        assertNull(VersionAdapter.AdaptResult.fromJson(incompleteHomeDuration))
        assertNull(VersionAdapter.AdaptResult.fromJson(invalidHomeIntentHandler))
        assertNull(VersionAdapter.AdaptResult.fromJson(invalidRelateDurationChain))
        assertNull(VersionAdapter.AdaptResult.fromJson(invalidRelateSourceType))
        assertNull(VersionAdapter.AdaptResult.fromJson(invalidRelateSourceTypeChain))
        assertNull(VersionAdapter.AdaptResult.fromJson(invalidRelateReasonChain))
        assertNull(VersionAdapter.AdaptResult.fromJson(invalidRelateCommercialChain))
        assertNull(VersionAdapter.AdaptResult.fromJson(invalidRelateService))
    }

    fun `locates home top bar by signatures instead of obfuscated method names`() {
        val points = VersionAdapter.locateHomeTopBar(requireNotNull(javaClass.classLoader))

        assertEquals("c", points?.gameMenu?.methodName)
        assertEquals("config", points?.gameMenu?.viewField)
        assertEquals("onViewCreated", points?.baseOnViewCreated?.methodName)
        assertEquals("searchText", points?.baseOnViewCreated?.viewField)
        assertEquals(2, points?.defaultWordMethods?.size)
        assertTrue(points?.defaultWordMethods.orEmpty().all {
            it.paramClassNames == listOf("com.bilibili.app.comm.list.common.api.b")
        })
    }

    fun `locates mine vip through manager and view binding structure`() {
        val point = VersionAdapter.locateMineVip(requireNotNull(javaClass.classLoader))

        assertEquals("onResume", point?.onResume?.methodName)
        assertEquals("vipManager", point?.onResume?.viewField)
        assertEquals("stableBinding", point?.bindingField)
        assertEquals("getRoot", point?.rootGetter?.methodName)
        assertEquals(emptyList<String>(), point?.rootGetter?.paramClassNames)
    }

    fun `locates only the dedicated teenagers mode prompt activity onCreate`() {
        val points = VersionAdapter.locateTeenagersMode(requireNotNull(javaClass.classLoader))

        assertEquals(1, points?.onCreateMethods?.size)
        assertEquals(
            "com.bilibili.teenagersmode.ui.TeenagersModeDialogActivity",
            points?.onCreateMethods?.single()?.className
        )
        assertEquals("onCreate", points?.onCreateMethods?.single()?.methodName)
        assertEquals(
            listOf("android.os.Bundle"),
            points?.onCreateMethods?.single()?.paramClassNames
        )
    }

    fun `prefers latest verified update implementation`() {
        val point = VersionAdapter.locateBlockUpdate(requireNotNull(javaClass.classLoader))

        // 9.14.0：Dr1.c 是网络入口，同签名 Dr1.a 是包装层，不能误选。
        assertEquals("Dr1.c", point?.className)
        assertNotEquals("Dr1.a", point?.className)
        assertEquals("a", point?.methodName)
        assertEquals(listOf("android.content.Context"), point?.paramClassNames)
    }

    fun `914 owners missing still select 913 network and quality implementations`() {
        val loader = object : ClassLoader(requireNotNull(javaClass.classLoader)) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == "Dr1.c" || name == "ut1.h") throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }
        assertEquals("ar1.c", VersionAdapter.locateBlockUpdate(loader)?.className)
        assertEquals("Rs1.j", VersionAdapter.locateDefaultVideoQuality(loader)?.defaultQualityMethod?.className)
    }

    fun `new owners missing still select 912 network and quality implementations`() {
        val loader = object : ClassLoader(requireNotNull(javaClass.classLoader)) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == "Dr1.c" || name == "ut1.h" || name == "ar1.c" || name == "Rs1.j") {
                    throw ClassNotFoundException(name)
                }
                return super.loadClass(name, resolve)
            }
        }
        assertEquals("gr1.c", VersionAdapter.locateBlockUpdate(loader)?.className)
        assertEquals("Xs1.j", VersionAdapter.locateDefaultVideoQuality(loader)?.defaultQualityMethod?.className)
    }

    fun `falls back to previous verified update implementation`() {
        val parent = requireNotNull(javaClass.classLoader)
        val previousLoader = object : ClassLoader(parent) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == "Dr1.c" || name == "ar1.c" || name == "gr1.c") throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }

        val point = VersionAdapter.locateBlockUpdate(previousLoader)

        // 9120100 的 owner 不在，回退到 9.11.0 的网络边界。
        assertEquals("qq1.c", point?.className)
        assertEquals("a", point?.methodName)
        assertEquals(listOf("android.content.Context"), point?.paramClassNames)
    }

    /**
     * 缓存聚合器**永远不许**被选成更新检查入口。
     *
     * `qq1.a`（9110400）与 `mq1.a`（9110200）跟各自的网络边界**签名完全相同**
     * （`(Context) -> BiliUpgradeInfo`），区别只在方法体：网络侧带一整组 http 常量
     * （`Do sync http request.` / `fawkes.update.info.supplier`），缓存侧一个字符串都没有。
     * 签名相同就随手加进候选表，会把屏蔽点挂到缓存层上——网络请求照发，静默失效。
     *
     * 所以哪怕两代网络边界都不在，也必须继续往下找旧候选，而不是退到同包的 `*.a`。
     */
    fun `the cache aggregator is never selected even when both network owners are gone`() {
        val parent = requireNotNull(javaClass.classLoader)
        val loader = object : ClassLoader(parent) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == "Dr1.c" || name == "ar1.c" || name == "gr1.c" || name == "qq1.c" || name == "mq1.c") {
                    throw ClassNotFoundException(name)
                }
                return super.loadClass(name, resolve)
            }
        }

        val point = VersionAdapter.locateBlockUpdate(loader)

        assertNotEquals("Dr1.a", point?.className)
        assertNotEquals("ar1.a", point?.className)
        assertNotEquals("gr1.a", point?.className)
        assertNotEquals("qq1.a", point?.className)
        assertNotEquals("mq1.a", point?.className)
        assertEquals("Ip1.c", point?.className)
    }

    fun `falls back to older update leaf implementation`() {
        val parent = requireNotNull(javaClass.classLoader)
        val legacyLoader = object : ClassLoader(parent) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == "Dr1.c" || name == "ar1.c" || name == "gr1.c" || name == "qq1.c" ||
                    name == "mq1.c" || name == "Ip1.c"
                ) {
                    throw ClassNotFoundException(name)
                }
                return super.loadClass(name, resolve)
            }
        }

        val point = VersionAdapter.locateBlockUpdate(legacyLoader)

        assertEquals("vd6.c", point?.className)
        assertEquals("c", point?.methodName)
        assertEquals(listOf("android.content.Context"), point?.paramClassNames)
    }

    fun `locates full number overloads and rejects unrelated signatures`() {
        val points = VersionAdapter.locateFullNumbers(requireNotNull(javaClass.classLoader))
            ?.formatterMethods.orEmpty()

        assertEquals(4, points.size)
        assertTrue(points.all {
            it.className == "kntr.base.localization.NumberFormat_androidKt"
        })
        assertTrue(points.any {
            it.methodName == "format" && it.paramClassNames == listOf("java.lang.Long")
        })
        assertTrue(points.any {
            it.methodName == "format" && it.paramClassNames == listOf("java.lang.String")
        })
        assertTrue(points.any { it.methodName == "formatNumber" })
        assertTrue(points.any { it.methodName == "format\$default" })
    }

    fun `locates player portrait control by its exact visibility override`() {
        val points = VersionAdapter.locatePlayerPortrait(requireNotNull(javaClass.classLoader))
            ?.visibilityMethods.orEmpty()

        assertEquals(1, points.size)
        assertEquals(
            "com.bilibili.app.gemini.player.widget.story.GeminiPlayerFullStoryWidget",
            points.single().className
        )
        assertEquals("setVisibility", points.single().methodName)
        assertEquals(listOf("int"), points.single().paramClassNames)
    }

    fun `locates only the exact video detail activity lifecycle`() {
        val points = VersionAdapter.locatePlayerStatusBar(requireNotNull(javaClass.classLoader))
            ?.onCreateMethods.orEmpty()

        assertEquals(1, points.size)
        assertEquals(
            "com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity",
            points.single().className
        )
        assertEquals("onCreate", points.single().methodName)
        assertEquals(listOf("android.os.Bundle"), points.single().paramClassNames)
    }

    fun `locates home feed public response and card getters`() {
        val points = VersionAdapter.locateHomeRecommendFeed(requireNotNull(javaClass.classLoader))

        assertEquals(1, points?.responseItemGetters?.size)
        assertEquals("getItems", points?.responseItemGetters?.single()?.methodName)
        assertEquals("getHolderType", points?.holderTypeGetter?.methodName)
        assertEquals("getCardGoto", points?.cardGotoGetter?.methodName)
        assertEquals("getGoTo", points?.goToGetter?.methodName)
        assertEquals("getUri", points?.uriGetter?.methodName)
        assertEquals("getParam", points?.paramGetter?.methodName)
        assertEquals("getBizType", points?.bizTypeGetter?.methodName)
        assertEquals("getCardType", points?.cardTypeGetter?.methodName)
        assertEquals("getAdInfo", points?.adInfoGetter?.methodName)
        assertEquals("getTitle", points?.titleGetter?.methodName)
        assertEquals("getPlayerArgs", points?.playerArgsGetter?.methodName)
        assertEquals(
            "com.bilibili.pegasus.data.base.BasePegasusData",
            points?.playerArgsGetter?.className
        )
        assertEquals("fakeDuration", points?.playerArgsDurationField)
        assertEquals("getDuration", points?.playerArgsDurationGetter?.methodName)
    }

    fun `locates related video direct and nested duration getters`() {
        val points = VersionAdapter.locateVideoRelate(requireNotNull(javaClass.classLoader))

        assertEquals(
            setOf(
                "com.bapis.bilibili.app.viewunite.common.Relates#getCardsList",
                "com.bapis.bilibili.app.viewunite.v1.Relates#getCardsList",
                "com.bapis.bilibili.app.viewunite.v1.RelatesFeedReply#getRelatesList",
                "com.bapis.bilibili.app.view.v1.RelatesFeedReply#getListList",
                "com.bapis.bilibili.app.view.v1.ViewReply#getRelatesList"
            ),
            points?.responseItemGetters
                  ?.map { "${it.className}#${it.methodName}" }
                  ?.toSet()
          )
          assertTrue(points?.responseItemGetters.orEmpty().all {
              !it.viewField.isNullOrBlank()
          })
          assertEquals(
              "com.bilibili.ship.theseus.united.page.intro.module.relate.DetailRelateService",
              points?.detailRelateService?.componentFactory?.className
          )
          assertEquals("d", points?.detailRelateService?.componentFactory?.methodName)
          assertEquals("type", points?.detailRelateService?.typeField)
          assertEquals("getType", points?.detailRelateService?.typeGetter?.methodName)
          assertEquals("d", points?.detailRelateService?.titleGetter?.methodName)
          assertEquals(1, points?.cardCaseGetters?.size)
        assertEquals("getCardCase", points?.cardCaseGetters?.single()?.methodName)
        assertEquals("getGoto", points?.gotoGetters?.single()?.methodName)
        assertEquals(
            "getRelateCardType",
            points?.relateCardTypeGetters?.single()?.methodName
        )
        assertEquals("getFromSourceType", points?.fromSourceTypeGetters?.single()?.methodName)
        assertEquals(
            "getBasicInfo",
            points?.fromSourceTypeChains?.single()?.itemGetter?.methodName
        )
        assertEquals(
            "com.bapis.bilibili.app.viewunite.common.CardBasicInfo",
            points?.fromSourceTypeChains?.single()?.sourceTypeGetter?.className
        )
        assertEquals(
            "getFromSourceType",
            points?.fromSourceTypeChains?.single()?.sourceTypeGetter?.methodName
        )
        assertEquals(
            "getRelateCardTypeValue",
            points?.relateCardTypeValueGetters?.single()?.methodName
        )
        assertEquals("getDuration", points?.directDurationGetters?.single()?.methodName)
        assertEquals(
            "com.bapis.bilibili.app.view.v1.Relate",
            points?.directDurationGetters?.single()?.className
        )
        assertEquals(
            mapOf(
                "getAv" to "com.bapis.bilibili.app.viewunite.common.RelateAVCard",
                "getHistoryAv" to
                    "com.bapis.bilibili.app.viewunite.common.RelateHistoryAVCard",
                "getAiCard" to "com.bapis.bilibili.app.viewunite.common.RelatedAICard"
            ),
            points?.durationChains?.associate {
                it.itemGetter.methodName to it.durationGetter.className
            }
        )
        assertTrue(points?.durationChains.orEmpty().all {
            it.durationGetter.methodName == "getDuration"
        })
        assertEquals(
            mapOf(
                "getAv" to "com.bapis.bilibili.app.viewunite.common.RelateAVCard",
                "getAiCard" to "com.bapis.bilibili.app.viewunite.common.RelatedAICard"
            ),
            points?.playCountChains?.associate {
                it.itemGetter.methodName to it.statGetter.className
            }
        )
        assertTrue(points?.playCountChains.orEmpty().none {
            it.itemGetter.methodName == "getHistoryAv"
        })
        assertTrue(points?.playCountChains.orEmpty().all {
            it.statGetter.methodName == "getStat" &&
                it.vtGetter.methodName == "getVt" &&
                it.valueGetter.methodName == "getValue"
        })
        assertEquals(8, points?.reasonChains?.size)
        assertEquals(
            setOf(
                "getRcmdReason",
                "getRcmdReasonExtra",
                "getRcmdReasonStyle",
                "getAv",
                "getBangumi",
                "getResource",
                "getGame",
                "getSpecial"
            ),
            points?.reasonChains?.map { it.steps.first().methodName }?.toSet()
        )
        assertTrue(points?.reasonChains.orEmpty().all { it.steps.size in 1..3 })
        assertEquals(
            setOf("hasCm", "hasCmStock", "getThreePoint"),
            points?.commercialEvidenceChains
                ?.map { it.steps.first().methodName }
                ?.toSet()
        )
        assertTrue(points?.commercialEvidenceChains.orEmpty().all { chain ->
            chain.steps.size in 1..2 && chain.steps.last().methodName in setOf(
                "hasCm",
                "hasCmStock",
                "hasFeedback"
            )
        })
    }

    fun `locates home tab builder and stable resource fields by generic signature`() {
        val points = VersionAdapter.locateHomeTabs(requireNotNull(javaClass.classLoader))

        assertEquals("build", points?.buildMethod?.methodName)
        assertEquals("tv.danmaku.bili.ui.main2.resource.z", points?.resourceClassName)
        assertEquals("id", points?.idField)
        assertEquals("title", points?.titleField)
        assertEquals("uri", points?.uriField)
        assertEquals("reporterId", points?.reporterIdField)
    }

    fun `locates host fragment lifecycle for home component filtering`() {
        val points = VersionAdapter.locateHomeComponents(requireNotNull(javaClass.classLoader))

        assertEquals("onViewCreated", points?.onViewCreated?.methodName)
        assertEquals(
            listOf("android.view.View", "android.os.Bundle"),
            points?.onViewCreated?.paramClassNames
        )
        assertEquals("getParentFragment", points?.parentFragmentGetter?.methodName)
    }

    fun `locates mine menu list and item title public getters`() {
        val points = VersionAdapter.locateMineComponents(requireNotNull(javaClass.classLoader))

        assertEquals(setOf("getItemList"), points?.itemListGetters?.map { it.methodName }?.toSet())
        assertEquals(setOf("getTitle"), points?.itemTitleGetters?.map { it.methodName }?.toSet())
    }

    fun `mine account model selects sectionListV2 when both section lists exist`() {
        val points = VersionAdapter.locateMineAccountMinePoints(
            requireNotNull(javaClass.classLoader)
        )

        assertNotNull(points)
        assertEquals("sectionListV2", points?.sectionListV2Field)
        assertTrue(requireNotNull(points).buildMethods.isNotEmpty())
        assertEquals(
            points,
            points.toJson().let(VersionAdapter.MineAccountMinePoints::fromJson)
        )
    }

    fun `locates and serializes legacy mine public field boundary`() {
        val mineEntry = VersionAdapter.MineEntryPoint(
            buildMethods = listOf(VersionAdapter.HookPoint("legacy.MineFragment", "build")),
            groupListField = "groups",
            adapterField = "adapter",
            clickMethod = VersionAdapter.HookPoint("legacy.Click", "invoke")
        )

        val points = VersionAdapter.locateLegacyMineComponents(
            requireNotNull(javaClass.classLoader),
            mineEntry
        )
        val restored = points?.toJson()?.let(VersionAdapter.MineComponentPoints::fromJson)

        assertTrue(points?.itemListGetters.isNullOrEmpty())
        assertTrue(points?.itemTitleGetters.isNullOrEmpty())
        assertEquals("groups", restored?.legacyGroupListField)
        assertEquals("adapter", restored?.legacyAdapterField)
        assertEquals("com.bilibili.lib.homepage.mine.MenuGroup", restored?.legacyGroupClassName)
        assertEquals("itemList", restored?.legacyItemListField)
        assertEquals("com.bilibili.lib.homepage.mine.MenuGroup\$Item", restored?.legacyItemClassName)
        assertEquals("title", restored?.legacyItemTitleField)
        assertEquals("build", restored?.legacyBuildMethods?.single()?.methodName)
    }

    fun `locates story list boundaries and exact public type getters`() {
        val points = VersionAdapter.locateStoryFeed(requireNotNull(javaClass.classLoader))

        assertEquals(setOf("getItems"), points?.responseItemGetters?.map { it.methodName }?.toSet())
        assertEquals(setOf("append", "insert"), points?.pagerListMethods?.map { it.methodName }?.toSet())
        assertEquals("isAd", points?.adGetter?.methodName)
        assertEquals("isLive", points?.liveGetter?.methodName)
        assertEquals("isGame", points?.gameGetter?.methodName)
        assertEquals("isBangumi", points?.bangumiGetter?.methodName)
        assertEquals("isCheese", points?.courseGetter?.methodName)
        assertEquals("isMusic", points?.musicGetter?.methodName)
        assertEquals("getCartIconInfo", points?.cartInfoGetter?.methodName)
        assertEquals("getDramaPromptBar", points?.dramaPromptGetter?.methodName)
        assertEquals("getSeasonInfo", points?.seasonInfoGetter?.methodName)
        assertEquals("getSeasonType", points?.seasonTypeGetter?.methodName)
    }

    fun `locates comment tab configuration by generic item tag`() {
        val points = VersionAdapter.locateCommentSection(requireNotNull(javaClass.classLoader))

        assertEquals(1, points?.listConstructors?.size)
        assertEquals(
            "com.bilibili.ship.theseus.united.page.tab.TabConfig",
            points?.listConstructors?.single()?.className
        )
        assertEquals(0, points?.listConstructors?.single()?.listParameterIndex)
        assertEquals("getLocatableTag", points?.locatableTagGetter?.methodName)
    }

    fun `locates only whitelisted splash response list getters`() {
        val points = VersionAdapter.locateSplashAds(requireNotNull(javaClass.classLoader))

        assertEquals(
            setOf("getSplashList", "getStrategyList"),
            points?.listGetters?.map { it.methodName }?.toSet()
        )
        assertEquals(3, points?.listGetters?.size)
        assertFalse(points?.listGetters.orEmpty().any { it.methodName == "getKeepIds" })
        assertEquals(
            setOf("is_ad", "is_ad_loc", "cm_mark", "ad_cb", "uri", "card_type", "server_type"),
            points?.itemSignalGetters?.map { it.role }?.toSet()
        )
    }

    fun `locates bottom tab public list and structural bind method`() {
        val points = VersionAdapter.locateBottomBar(requireNotNull(javaClass.classLoader))

        assertEquals("getTabs", points?.tabsGetter?.methodName)
        assertEquals("bind", points?.bindTabMethod?.methodName)
        assertEquals("com.bilibili.lib.homepage.widget.TabHost\$h", points?.itemClassName)
        assertEquals(listOf("name", "uri"), points?.itemStringFields)
    }

    fun `prefers newest verified quality owner over older residual candidate`() {
        val points = VersionAdapter.locateDefaultVideoQuality(requireNotNull(javaClass.classLoader))
        val point = points?.defaultQualityMethod

        // 9.14.0：实现搬到 ut1.h，旧版残留入口（Rs1.j 等）不能抢占它。
        assertEquals("ut1.h", point?.className)
        assertEquals("a", point?.methodName)
        assertEquals(emptyList<String>(), point?.paramClassNames)
        assertEquals(
            setOf("stream_quality", "vip_entitlement", "codec"),
            points?.capabilitySignals?.toSet()
        )
    }

    fun `falls back to previous verified quality owner`() {
        val parent = requireNotNull(javaClass.classLoader)
        val previousLoader = object : ClassLoader(parent) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == "ut1.h" || name == "Rs1.j" || name == "Xs1.j") throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }

        val point = VersionAdapter.locateDefaultVideoQuality(previousLoader)
            ?.defaultQualityMethod

        assertEquals("is1.h", point?.className)
        assertEquals("a", point?.methodName)
        assertEquals(emptyList<String>(), point?.paramClassNames)
    }

    fun `falls back to older verified quality owner`() {
        val parent = requireNotNull(javaClass.classLoader)
        val previousLoader = object : ClassLoader(parent) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == "ut1.h" || name == "Rs1.j" || name == "Xs1.j" || name == "is1.h") {
                    throw ClassNotFoundException(name)
                }
                return super.loadClass(name, resolve)
            }
        }

        val point = VersionAdapter.locateDefaultVideoQuality(previousLoader)
            ?.defaultQualityMethod

        assertEquals("es1.i", point?.className)
        assertEquals("a", point?.methodName)
        assertEquals(emptyList<String>(), point?.paramClassNames)
    }

    fun `uses legacy default quality helper without selecting settings getter`() {
        val parent = requireNotNull(javaClass.classLoader)
        val legacyOnlyLoader = object : ClassLoader(parent) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name in setOf("ut1.h", "Rs1.j", "Xs1.j", "is1.h", "es1.i", "Ar1.l", "Jq1.l", "gh6.h")) {
                    throw ClassNotFoundException(name)
                }
                return super.loadClass(name, resolve)
            }
        }

        val point = VersionAdapter.locateDefaultVideoQuality(legacyOnlyLoader)
            ?.defaultQualityMethod

        assertEquals("com.bilibili.playerbizcommon.utils.PlayerSettingHelper", point?.className)
        assertEquals("getDefaultQuality", point?.methodName)
        assertEquals(emptyList<String>(), point?.paramClassNames)
    }

    fun `locates public comment url map getters without touching message getter`() {
        val points = VersionAdapter.locateCommentPurify(requireNotNull(javaClass.classLoader))
            ?.urlMapGetters.orEmpty()

        assertEquals(setOf("getUrls", "getUrlsMap"), points.map { it.methodName }.toSet())
        assertTrue(points.all {
            it.className == "com.bapis.bilibili.main.community.reply.v1.Content" &&
                it.paramClassNames == emptyList<String>()
        })
    }

    /**
     * 搜索链接的第二道防线：`Url#getAppUrlSchema`。
     *
     * 替身上同时放了 `getTitle` / `getPcUrl` 和一个带参重载，用来证明定位器
     * 只按"无参 + 返回 String + 方法名"选中唯一那一个，没有顺手捞别的读取方法。
     */
    fun `locates the comment search jump target getter and nothing else on Url`() {
        val points = VersionAdapter.locateCommentPurify(requireNotNull(javaClass.classLoader))
            ?.urlSchemaGetters.orEmpty()

        assertEquals(1, points.size)
        assertEquals("com.bapis.bilibili.main.community.reply.v1.Url", points.single().className)
        assertEquals("getAppUrlSchema", points.single().methodName)
        assertEquals(emptyList<String>(), points.single().paramClassNames)
    }

    /**
     * 老缓存里没有 `url_schemas` 这个键时降级成空列表，不能解析失败。
     *
     * 这正是只抬 `RULE_VERSION`、不抬 `SCHEMA_VERSION` 的前提：形状兼容 ⇒ 老缓存能读，
     * 抬 rule ⇒ 指纹变化 ⇒ 下次启动重定位补上第二道防线。
     */
    fun `a cached comment purify payload without the schema key degrades instead of failing`() {
        val full = VersionAdapter.CommentPurifyPoints(
            urlMapGetters = listOf(VersionAdapter.HookPoint("A", "getUrlsMap", emptyList())),
            emptyPageGetters = emptyList(),
            voteWidgetMethods = emptyList(),
            follow = null,
            qoe = null,
            operations = emptyList(),
            urlSchemaGetters = listOf(VersionAdapter.HookPoint("B", "getAppUrlSchema", emptyList()))
        )
        val json = full.toJson()
        assertEquals(full, VersionAdapter.CommentPurifyPoints.fromJson(json))

        json.remove("url_schemas")
        val degraded = VersionAdapter.CommentPurifyPoints.fromJson(json)
        assertEquals(emptyList<VersionAdapter.HookPoint>(), degraded.urlSchemaGetters)
        assertEquals(full.urlMapGetters, degraded.urlMapGetters)
    }

    fun `a cached video relate payload without play count chains degrades instead of failing`() {
        val located = requireNotNull(
            VersionAdapter.locateVideoRelate(requireNotNull(javaClass.classLoader))
        )
        assertTrue(located.playCountChains.isNotEmpty())
        val json = located.toJson()
        assertEquals(
            located.playCountChains,
            VersionAdapter.VideoRelatePoints.fromJson(json).playCountChains
        )

        json.remove("play_count_chains")
        val degraded = VersionAdapter.VideoRelatePoints.fromJson(json)
        assertEquals(emptyList<VersionAdapter.PlayCountMethodChain>(), degraded.playCountChains)
        assertEquals(located.durationChains, degraded.durationChains)
        assertEquals(located.responseItemGetters, degraded.responseItemGetters)
    }

    fun `locates comment filter through exact public protobuf list and signal getters`() {
        val points = VersionAdapter.locateCommentFilter(requireNotNull(javaClass.classLoader))

        assertEquals(
            setOf("getRepliesList", "getTopRepliesList"),
            points?.replyListGetters?.map { it.methodName }?.toSet()
        )
        assertEquals(3, points?.replyListGetters?.size)
        assertEquals("getContent", points?.contentGetter?.methodName)
        assertEquals("getMessage", points?.messageGetter?.methodName)
        assertEquals("getMember", points?.memberGetter?.methodName)
        assertEquals("getLevel", points?.levelGetter?.methodName)
        assertEquals("getMemberV2", points?.memberV2Getter?.methodName)
        assertEquals("getBasic", points?.memberV2BasicGetter?.methodName)
        assertEquals("getLevel", points?.memberV2LevelGetter?.methodName)
        assertEquals(
            setOf("getUpTop", "getAdminTop", "getVoteTop"),
            points?.topReplyGetters?.map { it.methodName }?.toSet()
        )
        assertEquals("getDefaultInstance", points?.replyDefaultInstanceGetter?.methodName)
        assertTrue(points?.replyListGetters.orEmpty().all {
            it.paramClassNames == emptyList<String>()
        })
    }

    fun `locates reply topology mapper facade after obfuscated class name drifts`() {
        val points = VersionAdapter.locateCommentTopology(requireNotNull(javaClass.classLoader))

        assertNotNull(points)
        val mappers = requireNotNull(points).mapperMethods
        // 9.6.0 与 9.10.0 的 facade 名为 b；旧的 c/d/e 写死名单会在这里落空。
        assertTrue(
            mappers.any { it.className == "com.bilibili.app.comment3.data.source.v1.b" }
        )
        // 同 owner 内首参不是 ReplyInfo 的重载（b#M）必须被结构过滤排除。
        assertEquals(setOf("B"), mappers.map { it.methodName }.toSet())
        assertEquals(
            "com.bapis.bilibili.main.community.reply.v1.ReplyMoss",
            points.replyMossClassName
        )
        assertTrue(points.hasRequiredMethods())
    }

    fun `falls back to older reply topology mapper facade when latest name is absent`() {
        val parent = requireNotNull(javaClass.classLoader)
        val legacyLoader = object : ClassLoader(parent) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == "com.bilibili.app.comment3.data.source.v1.b") {
                    throw ClassNotFoundException(name)
                }
                return super.loadClass(name, resolve)
            }
        }

        val points = VersionAdapter.locateCommentTopology(legacyLoader)

        assertEquals(
            listOf("com.bilibili.app.comment3.data.source.v1.c"),
            points?.mapperMethods?.map { it.className }
        )
    }

    fun `locates empty comment guides with matching protobuf default getters`() {
        val points = VersionAdapter.locateCommentPurify(requireNotNull(javaClass.classLoader))
            ?.emptyPageGetters.orEmpty()

        assertEquals(2, points.size)
        assertEquals(
            setOf("SubjectControl", "SubjectDescriptionReply"),
            points.map { it.contentGetter.className.substringAfterLast('.') }.toSet()
        )
        assertTrue(points.all {
            it.contentGetter.methodName == "getEmptyPage" &&
                it.defaultInstanceGetter.methodName == "getDefaultInstance" &&
                it.contentGetter.paramClassNames == emptyList<String>() &&
                it.defaultInstanceGetter.paramClassNames == emptyList<String>()
        })
    }

    fun `locates vote widget binders by component structure across naming drift`() {
        val points = VersionAdapter.locateCommentPurify(requireNotNull(javaClass.classLoader))
            ?.voteWidgetMethods.orEmpty()

        assertEquals(3, points.size)
        assertEquals(
            setOf("CmtVoteWidget", "CmtMountWidget", "CommentVoteView"),
            points.map { it.className.substringAfterLast('.') }.toSet()
        )
        assertTrue(points.all { it.paramClassNames?.isNotEmpty() == true })
    }

    fun `locates follow visibility state machine and decorative header binder`() {
        val points = VersionAdapter.locateCommentPurify(requireNotNull(javaClass.classLoader))
            ?.follow

        assertEquals(4, points?.widgetStateMethods?.size)
        assertEquals(1, points?.headerBindMethods?.size)
        assertTrue(points?.widgetStateMethods.orEmpty().any { it.viewField != null })
        assertEquals(
            "com.bilibili.relation.widget.FollowButton",
            points?.followButtonClassName
        )
    }

    fun `locates qoe public presence content and default instance boundaries`() {
        val point = VersionAdapter.locateCommentPurify(requireNotNull(javaClass.classLoader))
            ?.qoe

        assertEquals("hasQoe", point?.presenceGetter?.methodName)
        assertEquals("getQoe", point?.contentGetter?.methodName)
        assertEquals("getDefaultInstance", point?.defaultInstanceGetter?.methodName)
        assertEquals(
            "com.bapis.bilibili.main.community.reply.v1.MainListReply",
            point?.contentGetter?.className
        )
        assertEquals(
            "com.bapis.bilibili.main.community.reply.v1.QoeInfo",
            point?.defaultInstanceGetter?.className
        )
    }

    fun `locates both operation payloads at public read boundaries`() {
        val points = VersionAdapter.locateCommentPurify(requireNotNull(javaClass.classLoader))
            ?.operations.orEmpty()

        assertEquals(2, points.size)
        assertEquals(
            setOf("getOperation", "getOperationV2"),
            points.map { it.contentGetter.methodName }.toSet()
        )
        assertEquals(
            setOf("hasOperation", "hasOperationV2"),
            points.map { it.presenceGetter.methodName }.toSet()
        )
        assertEquals(
            setOf("Operation", "OperationV2"),
            points.map { it.defaultInstanceGetter.className.substringAfterLast('.') }.toSet()
        )
    }

    @Test
    fun startupPlanningRepackClearCacheAndHostIdentity() {
        `startup omission survives cached found points without becoming cacheable`()
        `requested live point still wins when another startup family is omitted`()
        `child projection cannot restore main only relate points from a complete cache`()
        `planned locator omits only selected families and full offline lookup remains available`()
        `main process never prunes a family required by its accepted plan`()
        `repack preserves verified member results and original timestamp after content agreement`()
        `repack rejects changed unknown or unavailable original dex`()
        `repack cannot bypass version rule reset or structural validity`()
        clearCacheIsolationDoesNotLoadOrInitVersionAdapter()
        versionAdapterClearCacheDelegatesToStoreAndDeletesMarker()
        protocolFingerprintBuilderRoundTripAndPlanDiagnostic()
        honorsFlagsReadHostIdentityPublicSourceFallbackAndFailedDexReason()
    }

    @Test
    fun optionalCachesCodecMergerAndSnapshots() {
        `speed menu optional cache and failure history are bound to host and reset`()
        `follow optional descriptor and failure history remain within the same host snapshot`()
        `brand decoder optional cache preserves validated snapshot and completed failure independently`()
        `popup failed diagnostics survive quick merge independently without retaining other missing families`()
        `popup inheritance uses only the same fingerprint version and reset snapshot`()
        `popup promotion cache is optional rejects malformed members and merges missing families`()
        `classic comment cache is optional verified and merged per family`()
        `sponsor optional classes round trip and live results retain priority`()
        `K default words optional point survives full cache validation and runtime merge`()
        `cached K default words query failure remains observable in quick merge`()
        `startup snapshot preserves reset version fingerprint and structural cache rejection`()
        `startup snapshot cannot turn a low only cache into a high capable hit`()
        `PGC construction point participates in full cache validation and merge`()
        `comment filter judgement points survive the cache round trip`()
        `runtime result keeps live points and fills missing dex assisted point from cache`()
        `dex assisted quality implementation wins over the live legacy wrapper`()
        `dex assisted quality method skips the candidate table`()
        diagnosticsByIdReturnsEmptyAndMatchesLastDiagnosticPerId()
        codecFull68KeyJsonRoundTripMatchesOriginalResult()
        codecAndValidatorFailClosedOnMalformedInputs()
        mergerAdaptDecisionAndMergeRuntimeWithCachedPreserveParity()
    }

    @Test
    fun directLocatorsRegistryAndRefreshGates() {
        `round trips schema fingerprint and diagnostics`()
        `locates player interactive overlays from stable protobuf stubs`()
        `player interactive families carry their own whitelist and default instance`()
        `player interactive points survive a cache round trip`()
        `quick adaptation emits a versioned protocol structure fingerprint`()
        `rejects stale schema and structurally invalid hook point`()
        `locates home top bar by signatures instead of obfuscated method names`()
        `locates mine vip through manager and view binding structure`()
        `locates only the dedicated teenagers mode prompt activity onCreate`()
        `prefers latest verified update implementation`()
        `914 owners missing still select 913 network and quality implementations`()
        `new owners missing still select 912 network and quality implementations`()
        `falls back to previous verified update implementation`()
        `the cache aggregator is never selected even when both network owners are gone`()
        `falls back to older update leaf implementation`()
        `locates full number overloads and rejects unrelated signatures`()
        `locates player portrait control by its exact visibility override`()
        `locates only the exact video detail activity lifecycle`()
        `locates home feed public response and card getters`()
        `locates related video direct and nested duration getters`()
        `locates home tab builder and stable resource fields by generic signature`()
        `locates host fragment lifecycle for home component filtering`()
        `locates mine menu list and item title public getters`()
        `mine account model selects sectionListV2 when both section lists exist`()
        `locates and serializes legacy mine public field boundary`()
        `locates story list boundaries and exact public type getters`()
        `locates comment tab configuration by generic item tag`()
        `locates only whitelisted splash response list getters`()
        `locates bottom tab public list and structural bind method`()
        `prefers newest verified quality owner over older residual candidate`()
        `falls back to previous verified quality owner`()
        `falls back to older verified quality owner`()
        `uses legacy default quality helper without selecting settings getter`()
        `locates public comment url map getters without touching message getter`()
        `locates the comment search jump target getter and nothing else on Url`()
        `a cached comment purify payload without the schema key degrades instead of failing`()
        `a cached video relate payload without play count chains degrades instead of failing`()
        `locates comment filter through exact public protobuf list and signal getters`()
        `locates reply topology mapper facade after obfuscated class name drifts`()
        `falls back to older reply topology mapper facade when latest name is absent`()
        `locates empty comment guides with matching protobuf default getters`()
        `locates vote widget binders by component structure across naming drift`()
        `locates follow visibility state machine and decorative header binder`()
        `locates qoe public presence content and default instance boundaries`()
        `locates both operation payloads at public read boundaries`()
        dexFeatureRegistryInvariantsAndLocatorFacadesOperateDeterministically()
        strippedStaleDomainsRequireFeatureRefreshEvaluation()
    }

    private fun clearCacheIsolationDoesNotLoadOrInitVersionAdapter() {
        val cacheDir = tempFolder.newFolder("split-clear-isolated")
        val marker = File(cacheDir, AdaptCacheStore.DEX_ASSIST_MARKER_FILE_NAME).apply { writeText("pending") }
        val modulePrefs = InMemorySharedPreferences()
        val prefStore = InMemorySharedPreferences(
            mapOf(
                AdaptCacheStore.KEY_ADAPTED_VERSION to 9090300,
                AdaptCacheStore.KEY_ADAPT_RESULT to "{\"sv\":62}",
                "keep_unrelated" to "kept"
            )
        )
        val context = fakeAdapterContext(
            cacheDir = cacheDir,
            prefsByName = mutableMapOf(AdaptCacheStore.PREF_FILE to prefStore)
        )
        val appLoader = AdaptCacheStore::class.java.classLoader!!
        val forbiddenLoad = AtomicBoolean(false)
        val forbiddenClasses = setOf(
            "com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter",
            "com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptResultCodec",
            "com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptResultValidator",
            "com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.ProtocolFingerprintBuilder",
            "com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.DexFeatureRegistry"
        )
        val isolatedLoader = object : ClassLoader(appLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name in forbiddenClasses) {
                    forbiddenLoad.set(true)
                    throw AssertionError("AdaptCacheStore.clearCache must not load $name")
                }
                if (name == AdaptCacheStore::class.java.name ||
                    name.startsWith("${AdaptCacheStore::class.java.name}\$")
                ) {
                    findLoadedClass(name)?.let { return it }
                    val resource = name.replace('.', '/') + ".class"
                    val bytes = appLoader.getResourceAsStream(resource)?.use { it.readBytes() }
                        ?: return super.loadClass(name, resolve)
                    val defined = defineClass(name, bytes, 0, bytes.size)
                    if (resolve) resolveClass(defined)
                    return defined
                }
                return super.loadClass(name, resolve)
            }
        }
        val storeClass = Class.forName(AdaptCacheStore::class.java.name, true, isolatedLoader)
        val instance = storeClass.getField("INSTANCE").get(null)
        val clearMethod = storeClass.declaredMethods.single { method ->
            (method.name == "clearCache" || method.name.startsWith("clearCache\$")) &&
                !method.name.endsWith("\$default") &&
                method.parameterTypes.contentEquals(arrayOf(Context::class.java, SharedPreferences::class.java))
        }.apply { isAccessible = true }

        clearMethod.invoke(instance, context, modulePrefs.instance)

        assertFalse("Heavy adapter classes must stay unloaded", forbiddenLoad.get())
        assertFalse(marker.exists())
        assertFalse(prefStore.values.containsKey(AdaptCacheStore.KEY_ADAPTED_VERSION))
        assertFalse(prefStore.values.containsKey(AdaptCacheStore.KEY_ADAPT_RESULT))
        assertEquals("kept", prefStore.values["keep_unrelated"])
        val resetTs = modulePrefs.values[RemoteHookConfigContract.KEY_ADAPTER_RESET_TIMESTAMP] as? Long
        assertNotNull(resetTs)
        assertTrue(resetTs!! > 0L)
    }

    private fun versionAdapterClearCacheDelegatesToStoreAndDeletesMarker() {
        val cacheDir = tempFolder.newFolder("split-clear-facade")
        val marker = File(cacheDir, AdaptCacheStore.DEX_ASSIST_MARKER_FILE_NAME).apply { writeText("pending") }
        val modulePrefs = InMemorySharedPreferences()
        val adapterPrefs = InMemorySharedPreferences(
            mapOf(AdaptCacheStore.KEY_ADAPTED_VERSION to 9200000, AdaptCacheStore.KEY_ADAPT_RESULT to "{}")
        )
        val context = fakeAdapterContext(
            cacheDir = cacheDir,
            prefsByName = mutableMapOf(AdaptCacheStore.PREF_FILE to adapterPrefs)
        )
        VersionAdapter.clearCache(context, modulePrefs.instance)

        assertFalse(marker.exists())
        assertFalse(adapterPrefs.values.containsKey(AdaptCacheStore.KEY_ADAPTED_VERSION))
        assertFalse(adapterPrefs.values.containsKey(AdaptCacheStore.KEY_ADAPT_RESULT))
        assertTrue(modulePrefs.values.containsKey(AdaptCacheStore.KEY_RESET_TS))
        assertEquals("unavailable", AdaptCacheStore.DEX_SOURCE_UNAVAILABLE)
    }

    private fun diagnosticsByIdReturnsEmptyAndMatchesLastDiagnosticPerId() {
        val empty = minimalValidResult().copy(diagnostics = emptyList())
        assertTrue(empty.diagnosticsById.isEmpty())
        assertNull(empty.diagnosticsById["missing.id"])

        val first = AdaptDiagnostic("dup.id", AdaptState.MISSING, "first")
        val unique = AdaptDiagnostic("unique.id", AdaptState.FOUND, "ok")
        val second = AdaptDiagnostic("dup.id", AdaptState.FOUND, "last")
        val indexed = minimalValidResult().copy(diagnostics = listOf(first, unique, second))

        assertEquals(2, indexed.diagnosticsById.size)
        assertEquals(second, indexed.diagnosticsById["dup.id"])
        assertEquals(unique, indexed.diagnosticsById["unique.id"])
        assertNull(indexed.diagnosticsById["absent.id"])
    }

    private fun codecFull68KeyJsonRoundTripMatchesOriginalResult() {
        val full = full68KeyValidResult()
        val json = full.toJson()

        assertEquals(68, json.length())
        assertEquals(VersionAdapterContract.SCHEMA_VERSION, json.getInt("sv"))
        assertEquals(AdaptResultCodec.toJson(full).toString(), json.toString())

        val restored = requireNotNull(AdaptResult.fromJson(JSONObject(json.toString())))
        assertEquals(full, restored)
        assertEquals(full.diagnosticsById, restored.diagnosticsById)
        assertEquals(restored, AdaptResultCodec.fromJson(JSONObject(json.toString())))
    }

    private fun codecAndValidatorFailClosedOnMalformedInputs() {
        val valid = full68KeyValidResult()
        val validJson = valid.toJson()

        assertNull(AdaptResult.fromJson(JSONObject(validJson.toString()).put("sv", VersionAdapterContract.SCHEMA_VERSION - 1)))
        assertNull(AdaptResult.fromJson(JSONObject(validJson.toString()).put("sv", VersionAdapterContract.SCHEMA_VERSION + 1)))
        assertNull(AdaptResult.fromJson(JSONObject(validJson.toString()).put("fp", "")))
        assertNull(AdaptResult.fromJson(JSONObject(validJson.toString()).put("dex_fp", "dex-v1:0:bad")))
        assertNull(AdaptResult.fromJson(JSONObject(validJson.toString()).put("protocol_fp", "bad-proto")))

        val poisonedNotRequested = JSONObject(validJson.toString()).apply {
            getJSONArray("diag").getJSONObject(0).put("state", AdaptState.NOT_REQUESTED.name)
        }
        assertNull(AdaptResult.fromJson(poisonedNotRequested))

        val poisonedInteractiveClear = JSONObject(validJson.toString()).apply {
            val families = getJSONObject("player_interactive_overlays").getJSONArray("families")
            families.getJSONObject(0).put(
                "clears",
                JSONArray().put(HookPoint("com.bapis.Guide", AdaptResultValidator.PLAYER_INTERACTIVE_PRESERVED_VIDEO_POINT_CLEAR, emptyList()).toJson())
            )
        }
        assertNull(AdaptResult.fromJson(poisonedInteractiveClear))

        val validLegacyBind = HookPoint(
            LegacyStoryDanmakuAdapter.OWNER,
            "bind",
            listOf("com.bilibili.video.story.StoryDetail", "int")
        )
        assertTrue(AdaptResultValidator.isValidLegacyStoryDanmakuBind(validLegacyBind))
        assertFalse(AdaptResultValidator.isValidLegacyStoryDanmakuBind(validLegacyBind.copy(className = "other.Owner")))
        assertFalse(AdaptResultValidator.isValidLegacyStoryDanmakuBind(validLegacyBind.copy(viewField = "leak")))
        assertFalse(AdaptResultValidator.isValidLegacyStoryDanmakuBind(validLegacyBind.copy(paramClassNames = listOf("a", "long"))))

        val partial = valid.copy(startupExcluded = setOf("SPLASH"))
        assertThrows(IllegalStateException::class.java) { partial.toJson() }
        val notRequested = valid.copy(
            diagnostics = listOf(AdaptDiagnostic("home.banner", AdaptState.NOT_REQUESTED, "startup-not-requested"))
        )
        assertThrows(IllegalStateException::class.java) { notRequested.toJson() }
    }

    private fun mergerAdaptDecisionAndMergeRuntimeWithCachedPreserveParity() {
        val base = minimalValidResult()
        assertEquals(AdaptDecision.ADAPT, AdaptResultMerger.adaptDecision(null, false, emptySet()))
        assertEquals(AdaptDecision.ADAPT, AdaptResultMerger.adaptDecision(base, true, emptySet()))
        assertEquals(AdaptDecision.SUPPLEMENT_SILENTLY, AdaptResultMerger.adaptDecision(base, false, setOf(PointFamily.BANNER)))
        assertEquals(AdaptDecision.SUPPLEMENT_SILENTLY, AdaptResultMerger.adaptDecision(base, false, emptySet(), setOf(AdaptDomain.COMMENT)))
        assertEquals(AdaptDecision.REUSE, AdaptResultMerger.adaptDecision(base, false, emptySet()))

        val legacyWrapper = HookPoint(MediaQualityAndPlayerLocator.PLAYER_QUALITY_LEGACY_HELPER_CLASS, "getDefaultQuality")
        val assistedQuality = HookPoint("Zz9.q", "a")
        val retainedId = BrandSplashDecoderAdapter.DIAGNOSTIC
        val unretainedId = PlayerSpeedSessionLocator.DIAGNOSTIC
        val planNotRequestedId = "home.banner"
        val dexAssistId = VersionAdapter.DEX_ASSIST_BLOCK_UPDATE_ID

        val cached = full68KeyValidResult().copy(
            commentLow = HookPoint("cached.Low", "o0"),
            banner = BannerPoint("com.bilibili.pegasus.holders.bannerv8.V8Banner", listOf(HookPoint("s.Banner", "onAttachedToWindow", emptyList()))),
            playerQuality = PlayerQualityPoints(assistedQuality, listOf("codec")),
            commentPurify = CommentPurifyPoints(
                urlMapGetters = listOf(HookPoint("c.Content", "getUrls", emptyList())),
                emptyPageGetters = emptyList(),
                voteWidgetMethods = emptyList(),
                follow = null,
                qoe = null,
                operations = emptyList(),
                quickReplyDialogMethods = listOf(HookPoint("c.Collector", "emit", listOf("a.PublishDialogIntent", "kotlin.coroutines.Continuation"))),
                urlSchemaGetters = emptyList()
            ),
            diagnostics = listOf(
                AdaptDiagnostic(retainedId, AdaptState.MISSING, "no_match"),
                AdaptDiagnostic(unretainedId, AdaptState.MISSING, "no_match"),
                AdaptDiagnostic(planNotRequestedId, AdaptState.FOUND, "cached-found"),
                AdaptDiagnostic(dexAssistId, AdaptState.FOUND, "cached-dex-assist"),
                AdaptDiagnostic(KotlinDefaultWordsLocator.DIAGNOSTIC, AdaptState.MISSING, "no-match")
            )
        )
        val liveBanner = BannerPoint("com.bilibili.pegasus.holders.bannerv8.V8Banner", listOf(HookPoint("live.Banner", "onAttachedToWindow", emptyList())))
        val runtime = cached.copy(
            biliVersionCode = 0,
            ts = 0L,
            commentLow = HookPoint("live.Low", "q0"),
            blockUpdate = null,
            homeTopBar = cached.homeTopBar?.copy(kotlinDefaultWords = null),
            banner = liveBanner,
            playerQuality = PlayerQualityPoints(legacyWrapper, listOf("stream_quality")),
            commentPurify = cached.commentPurify?.copy(quickReplyDialogMethods = emptyList()),
            hostFingerprint = "runtime-no-context|rules=${VersionAdapterContract.RULE_VERSION}",
            dexSourceFingerprint = AdaptCacheStore.DEX_SOURCE_UNAVAILABLE,
            diagnostics = listOf(
                AdaptDiagnostic(retainedId, AdaptState.NOT_APPLICABLE, "quick-locate"),
                AdaptDiagnostic(unretainedId, AdaptState.NOT_APPLICABLE, "quick-locate"),
                AdaptDiagnostic(planNotRequestedId, AdaptState.NOT_APPLICABLE, VersionAdapter.PLAN_NOT_REQUESTED),
                AdaptDiagnostic(dexAssistId, AdaptState.MISSING, "runtime-missed"),
                AdaptDiagnostic(KotlinDefaultWordsLocator.DIAGNOSTIC, AdaptState.NOT_APPLICABLE, "quick-locate")
            )
        )

        val merged = requireNotNull(VersionAdapter.mergeRuntimeWithCached(runtime, cached))
        assertEquals(cached.biliVersionCode, merged.biliVersionCode)
        assertEquals(HookPoint("live.Low", "q0"), merged.commentLow)
        assertEquals(cached.blockUpdate, merged.blockUpdate)
        assertEquals(liveBanner, merged.banner)
        assertEquals(assistedQuality, merged.playerQuality?.defaultQualityMethod)
        assertEquals(listOf("stream_quality", "codec"), merged.playerQuality?.capabilitySignals)
        assertEquals(cached.commentPurify?.quickReplyDialogMethods, merged.commentPurify?.quickReplyDialogMethods)
        assertEquals(AdaptState.MISSING, merged.diagnosticsById[retainedId]?.state)
        assertEquals(AdaptState.NOT_APPLICABLE, merged.diagnosticsById[unretainedId]?.state)
        assertEquals(AdaptState.FOUND, merged.diagnosticsById[planNotRequestedId]?.state)
        assertEquals(AdaptState.FOUND, merged.diagnosticsById[dexAssistId]?.state)
        assertEquals(AdaptState.MISSING, merged.diagnosticsById[KotlinDefaultWordsLocator.DIAGNOSTIC]?.state)

        val staleCached = cached.copy(
            domainRuleVersions = VersionAdapterContract.DOMAIN_RULE_VERSIONS.toMutableMap().apply {
                put(AdaptDomain.UPDATE.key, (this[AdaptDomain.UPDATE.key] ?: 0) + 1)
            }
        )
        val mergedWithStale = requireNotNull(VersionAdapter.mergeRuntimeWithCached(runtime.copy(blockUpdate = null), staleCached))
        assertNull(mergedWithStale.blockUpdate)
        assertNotNull(mergedWithStale.videoRelate)
    }

    private fun protocolFingerprintBuilderRoundTripAndPlanDiagnostic() {
        val splits = listOf(
            SplitFileMeta("split_config.arm64_v8a.apk", 2048L, 1700000100L),
            SplitFileMeta("split_feature.apk", 1024L, 1700000200L)
        )
        val fp = ProtocolFingerprintBuilder.buildHostFingerprint(
            versionCode = 9210200L,
            versionName = "9.21.2",
            baseLength = 4096L,
            baseLastModified = 1700000000L,
            splits = splits.reversed()
        )
        val parsed = requireNotNull(ProtocolFingerprintBuilder.parseHostFingerprint(fp))
        assertEquals(9210200L, parsed.versionCode)
        assertEquals("9.21.2", parsed.versionName)
        assertEquals(4096L, parsed.baseLength)
        assertEquals(1700000000L, parsed.baseLastModified)
        assertNotNull(parsed.splitsHash)
        assertEquals(VersionAdapterContract.RULE_VERSION, parsed.ruleVersion)

        assertEquals(
            fp,
            ProtocolFingerprintBuilder.buildHostFingerprint(9210200L, "9.21.2", 4096L, 1700000000L, splits)
        )
        assertNull(ProtocolFingerprintBuilder.parseHostFingerprint("com.other|1|1.0|10|10|99"))
        assertNull(ProtocolFingerprintBuilder.parseHostFingerprint("tv.danmaku.bili|1|1.0|0|10|99"))

        val loader = ProtocolMethodFixture::class.java.classLoader!!
        val computed = ProtocolFingerprintBuilder.buildProtocolStructureFingerprint(
            loader = loader,
            home = HomeRecommendFeedPoints(
                responseItemGetters = listOf(HookPoint(ProtocolMethodFixture::class.java.name, "getItems", emptyList())),
                holderTypeGetter = HookPoint(ProtocolMethodFixture::class.java.name, "getHolderType", emptyList()),
                bizTypeGetter = null,
                adInfoGetter = null,
                cardTypeGetter = null,
                cardGotoGetter = null,
                goToGetter = null,
                uriGetter = HookPoint(ProtocolMethodFixture::class.java.name, "getUri", emptyList()),
                paramGetter = null,
                titleGetter = null,
                subtitleGetter = null,
                descGetter = null,
                playerArgsGetter = null,
                playerArgsDurationField = "duration"
            ),
            relate = null,
            comment = null,
            quality = null,
            splash = null,
            mine = null
        )
        assertEquals(4, computed.evidenceCount)
        assertEquals(3, computed.liveResolvedCount)
        assertTrue(computed.value.matches(AdaptResultValidator.REBIND_PROTOCOL_FINGERPRINT_PATTERN))
        assertEquals(AdaptState.FOUND, computed.toDiagnostic(AdaptPlan.full()).state)

        val emptyProto = ProtocolFingerprintBuilder.ProtocolFingerprint("protocol-v1:0:${"0".repeat(24)}", 0, 0)
        assertEquals(AdaptState.MISSING, emptyProto.toDiagnostic(AdaptPlan.full()).state)
        assertEquals(AdaptState.NOT_APPLICABLE, emptyProto.toDiagnostic(AdaptPlan(1L, emptySet(), emptySet())).state)
    }

    private fun dexFeatureRegistryInvariantsAndLocatorFacadesOperateDeterministically() {
        assertEquals(38, DexFeatureRegistry.adapters.size)
        val featureIds = DexFeatureRegistry.adapters.map { it.featureId }
        assertEquals(featureIds.size, featureIds.toSet().size)

        val diagIds = DexFeatureRegistry.adapters.flatMap { it.diagnosticIds }
        assertTrue(diagIds.all { it.isNotBlank() })
        assertEquals(diagIds.size, diagIds.toSet().size)

        assertTrue(DexFeatureRegistry.quickLocateMissingRetainedDiagnosticIds.contains(BrandSplashDecoderAdapter.DIAGNOSTIC))
        assertFalse(DexFeatureRegistry.quickLocateMissingRetainedDiagnosticIds.contains(PlayerSpeedSessionLocator.DIAGNOSTIC))
        assertFalse(DexFeatureRegistry.quickLocateMissingRetainedDiagnosticIds.contains(HomeChromeAdapter.SEMANTICS_DIAGNOSTIC))

        val emptyLoader = object : ClassLoader(null) {}
        val ctx = DexFeatureRefreshContext(
            loader = emptyLoader,
            hostVersionCode = 9210200,
            flags = DexFeatureFlags(homeTabsRequested = false)
        )
        assertFalse(DexFeatureRegistry.requiresFeatureRefresh(ctx, minimalValidResult()))

        assertNull(VersionAdapter.locateMineEntry(emptyLoader))
        assertNull(VersionAdapter.locateMineVip(emptyLoader))
        assertNull(VersionAdapter.locateBlockUpdate(emptyLoader, 9210200))
        assertNull(VersionAdapter.locateDynamicTabs(emptyLoader))
        assertNull(VersionAdapter.locateFullNumbers(emptyLoader))
        assertNull(VersionAdapter.locatePlayerPortrait(emptyLoader))
        assertNull(VersionAdapter.locatePlayerInteractiveOverlays(emptyLoader))
        assertNull(VersionAdapter.locatePlayerStatusBar(emptyLoader))
        assertNull(VersionAdapter.locateHomeRecommendFeed(emptyLoader))
        assertNull(VersionAdapter.locateVideoRelate(emptyLoader))
        assertNull(VersionAdapter.locateHomeTabs(emptyLoader))
        assertNull(VersionAdapter.locateHomeComponents(emptyLoader))
        assertNull(VersionAdapter.locateMineAccountMinePoints(emptyLoader))
        assertNull(VersionAdapter.locateMineComponents(emptyLoader))
        assertNull(VersionAdapter.locateStoryFeed(emptyLoader))
        assertNull(VersionAdapter.locateCommentSection(emptyLoader))
        assertNull(VersionAdapter.locateSplashAds(emptyLoader))
        assertNull(VersionAdapter.locateBottomBar(emptyLoader))
        assertNull(VersionAdapter.locateDefaultVideoQuality(emptyLoader, hostVersionCode = 9210200))
        assertNull(VersionAdapter.locateTeenagersMode(emptyLoader))
        assertNull(VersionAdapter.locateCommentFilter(emptyLoader))
        assertNull(VersionAdapter.locateCommentTopology(emptyLoader, 9210200))
        assertNull(VersionAdapter.locateCommentPurify(emptyLoader))
        assertNull(VersionAdapter.locateHomeTopBar(emptyLoader))
    }

    private fun strippedStaleDomainsRequireFeatureRefreshEvaluation() {
        val wireMethod = kmpfixture.modern.GrpcEngine::class.java.declaredMethods.single { it.name == "a" }
        val wirePoint = KmpGrpcWireAdapter.point(wireMethod)
        val full = full68KeyValidResult().copy(
            kmpGrpcWireEngine = wirePoint,
            domainRuleVersions = VersionAdapterContract.DOMAIN_RULE_VERSIONS
        )
        val loader = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> = when (name) {
                KmpGrpcWireAdapter.REPLY_MOSS -> kmpfixture.modern.GrpcEngine::class.java
                else -> super.loadClass(name, resolve)
            }
        }
        val flags = DexFeatureFlags(kmpGrpcWireEnabled = true, homeTabsRequested = false)
        val ctx = DexFeatureRefreshContext(loader = loader, hostVersionCode = 9210200, flags = flags)
        assertFalse(DexFeatureRegistry.requiresFeatureRefresh(ctx, full))

        val stale = setOf(AdaptDomain.KMP_WIRE)
        val stripped = full.withoutDomains(stale)
        assertNull(stripped.kmpGrpcWireEngine)
        assertTrue(DexFeatureRegistry.requiresFeatureRefresh(ctx, stripped))
    }

    private fun createFakePackageManager(hostPackageInfo: PackageInfo): PackageManager {
        val className = "test.pm.FakePackageManager"
        val pmLoader = TestRenamingClassLoader(
            javaClass.classLoader!!,
            templates = mapOf(className to FakePackageManagerTemplate::class.java),
            replacements = mapOf(
                FakePackageManagerTemplate::class.java.name.replace('.', '/') to className.replace('.', '/')
            ),
            exactReplacements = mapOf("java/lang/Object" to "android/content/pm/PackageManager")
        )
        return pmLoader.loadClass(className)
            .getDeclaredConstructor(PackageInfo::class.java)
            .apply { isAccessible = true }
            .newInstance(hostPackageInfo) as PackageManager
    }

    private fun invokeVersionAdapterAdapt(
        context: Context,
        loader: ClassLoader,
        followFeedStyleEnabled: Boolean,
        playerSpeedSessionEnabled: Boolean,
        guestBrowsingEnabled: Boolean,
        dexSource: DexSourceFingerprint.Result?,
        vc: Int,
        hostFingerprint: String,
        splashAutoNightEnabled: Boolean
    ): AdaptResult {
        val adaptMethod = VersionAdapter::class.java.declaredMethods.single { it.name == "adapt" }
            .apply { isAccessible = true }
        val types = adaptMethod.parameterTypes
        assertEquals(10, types.size)
        val historyClass = types[3]
        val emptyHistorySnapshot = runCatching {
            historyClass.getDeclaredField("EMPTY").apply { isAccessible = true }.get(null)
        }.getOrElse {
            val comp = historyClass.getDeclaredField("Companion").apply { isAccessible = true }.get(null)
            comp.javaClass.getMethod("getEMPTY").apply { isAccessible = true }.invoke(comp)
        }
        val assistHistoryClass = types[4]
        val emptyAssistHistory = runCatching {
            assistHistoryClass.getDeclaredField("EMPTY").apply { isAccessible = true }.get(null)
        }.getOrElse {
            val comp = assistHistoryClass.getDeclaredField("Companion").apply { isAccessible = true }.get(null)
            comp.javaClass.getMethod("getEMPTY").apply { isAccessible = true }.invoke(comp)
        }
        val flags = DexFeatureFlags(
            followFeedStyleEnabled = followFeedStyleEnabled,
            playerSpeedSessionEnabled = playerSpeedSessionEnabled,
            guestBrowsingEnabled = guestBrowsingEnabled,
            splashAutoNightEnabled = splashAutoNightEnabled
        )
        return adaptMethod.invoke(
            VersionAdapter,
            context,
            loader,
            flags,
            emptyHistorySnapshot,
            emptyAssistHistory,
            AdaptPlan.full(),
            null,
            dexSource,
            vc,
            hostFingerprint
        ) as AdaptResult
    }

    private inline fun suspendOrAwaitAdapt(
        runningAtomic: AtomicBoolean,
        invoke: (CountDownLatch, AdaptCallback) -> Unit
    ) {
        runningAtomic.set(false)
        val started = AtomicBoolean(false)
        val finished = CountDownLatch(1)
        val cb = object : AdaptCallback {
            override fun onAdaptStarted() { started.set(true) }
            override fun onAdaptFinished(ok: Boolean) { finished.countDown() }
        }
        invoke(finished, cb)
        assertTrue("Expected ensureAdapted to trigger adapt via requiresFeatureRefresh", started.get())
    }

    private fun honorsFlagsReadHostIdentityPublicSourceFallbackAndFailedDexReason() {
        val dir = tempFolder.newFolder("version-adapter-wiring")
        val baseApk = File(dir, "base.apk").apply { writeBytes(ByteArray(64)) }
        val splitApk = File(dir, "split_config.arm64_v8a.apk").apply { writeBytes(ByteArray(32)) }
        val cacheDir = File(dir, "cache").apply { mkdirs() }
        @Suppress("DEPRECATION")
        val pkgInfo = PackageInfo().apply {
            packageName = "tv.danmaku.bili"
            this.versionCode = 9210200
            this.versionName = "9.21.0"
            applicationInfo = ApplicationInfo().apply {
                packageName = "tv.danmaku.bili"
                sourceDir = baseApk.absolutePath
            }
        }
        val pm = createFakePackageManager(pkgInfo)
        val context = object : ContextWrapper(null) {
            override fun getPackageManager(): PackageManager = pm
            override fun getCacheDir(): File = cacheDir
        }

        val readIdentity = VersionAdapter::class.java.declaredMethods
            .single { it.name == "readHostIdentity" }
            .apply { isAccessible = true }
        val identity = readIdentity.invoke(VersionAdapter, context) as HostIdentity
        assertEquals(9210200, identity.versionCode)
        assertTrue(identity.fingerprint.startsWith("tv.danmaku.bili|9210200|9.21.0|64|"))

        val adapterLoader = TestRenamingClassLoader(
            javaClass.classLoader!!,
            templates = mapOf(
                PrivacyChannelAdapter.IGNET_ENGINE to IgnetEngineStub::class.java,
                "kntr.srcs.app.splash.brand.model.BrandSplashSource" to SplashSourceStub::class.java,
                PlayerSpeedSessionLocator.PARAMS to SpeedParamsStub::class.java,
                PlayerSpeedLocator.SPEED_MANAGER to SpeedManagerStub::class.java
            ),
            replacements = mapOf(
                IgnetEngineStub::class.java.name.replace('.', '/') to PrivacyChannelAdapter.IGNET_ENGINE.replace('.', '/'),
                SplashSourceStub::class.java.name.replace('.', '/') to "kntr/srcs/app/splash/brand/model/BrandSplashSource",
                SpeedParamsStub::class.java.name.replace('.', '/') to PlayerSpeedSessionLocator.PARAMS.replace('.', '/'),
                SpeedManagerStub::class.java.name.replace('.', '/') to PlayerSpeedLocator.SPEED_MANAGER.replace('.', '/')
            ),
            hiddenNames = setOf(
                PrivacyChannelAdapter.IGNET_REQUEST,
                PlayerSpeedSessionLocator.RUN,
                "kntr.srcs.app.splash.brand.startup.BrandSplashPageKt",
                "rU.d", "sU.d", "ey80", "mU.d", "gv1.c", "iU.d"
            )
        )
        val dexSource = DexSourceFingerprint.Result(
            value = "dex-v1:1:" + "a".repeat(64),
            codePaths = listOf(baseApk.absolutePath),
            dexEntryCount = 1
        )
        val baseline = invokeVersionAdapterAdapt(
            context, adapterLoader, followFeedStyleEnabled = false, playerSpeedSessionEnabled = false,
            guestBrowsingEnabled = false, dexSource = dexSource, vc = 9210200, hostFingerprint = identity.fingerprint,
            splashAutoNightEnabled = false
        )
        val baseIgnetDiag = baseline.diagnostics.single { it.id == PrivacyChannelAdapter.DIAGNOSTIC_IGNET }
        val basePageDiag = baseline.diagnostics.single { it.id == BrandSplashKntrAdapter.DIAGNOSTIC_PAGE }
        assertEquals(AdaptState.NOT_APPLICABLE, baseIgnetDiag.state)
        assertEquals("feature-disabled", baseIgnetDiag.detail)
        assertEquals(AdaptState.NOT_APPLICABLE, basePageDiag.state)
        assertEquals("feature-disabled", basePageDiag.detail)

        val adapted = invokeVersionAdapterAdapt(
            context, adapterLoader, followFeedStyleEnabled = true, playerSpeedSessionEnabled = true,
            guestBrowsingEnabled = true, dexSource = dexSource, vc = 9210200, hostFingerprint = identity.fingerprint,
            splashAutoNightEnabled = true
        )
        val ignetDiag = adapted.diagnostics.single { it.id == PrivacyChannelAdapter.DIAGNOSTIC_IGNET }
        val pageDiag = adapted.diagnostics.single { it.id == BrandSplashKntrAdapter.DIAGNOSTIC_PAGE }
        val speedDiag = adapted.diagnostics.single { it.id == PlayerSpeedSessionLocator.DIAGNOSTIC }
        val followDiag = adapted.diagnostics.single { it.id == FollowFeedAdapter.DIAGNOSTIC }
        assertEquals(AdaptState.MISSING, ignetDiag.state); assertEquals("native_unavailable", ignetDiag.detail)
        assertEquals(AdaptState.MISSING, pageDiag.state); assertEquals("native_unavailable", pageDiag.detail)
        assertEquals(AdaptState.MISSING, speedDiag.state); assertEquals("native_unavailable", speedDiag.detail)
        assertFalse(AdaptAssistGate.isConclusiveMissing(speedDiag))
        assertEquals(AdaptState.MISSING, followDiag.state); assertEquals("native_unavailable", followDiag.detail)
        assertFalse(AdaptAssistGate.isConclusiveMissing(followDiag))

        val runningField = VersionAdapter::class.java.getDeclaredField("adaptationRunning").apply { isAccessible = true }
        val runningAtomic = runningField.get(VersionAdapter) as AtomicBoolean
        try {
            runningAtomic.set(false)
            val baselineSnap = StartupCacheSnapshot(identity, 0L, baseline)
            val reuseStarted = AtomicBoolean(false)
            VersionAdapter.ensureAdapted(
                context = context,
                classLoader = adapterLoader,
                resetTimestamp = 0L,
                callback = object : AdaptCallback {
                    override fun onAdaptStarted() { reuseStarted.set(true) }
                    override fun onAdaptFinished(ok: Boolean) = Unit
                },
                startupCache = baselineSnap,
                guestBrowsingEnabled = false,
                splashAutoNightEnabled = false
            )
            assertFalse("Baseline cache should hit fast-path when guest & splashAutoNight disabled", reuseStarted.get())

            suspendOrAwaitAdapt(runningAtomic) { latch, cb ->
                VersionAdapter.ensureAdapted(
                    context = context, classLoader = adapterLoader, resetTimestamp = 0L, callback = cb,
                    startupCache = baselineSnap, guestBrowsingEnabled = true, splashAutoNightEnabled = false
                )
                assertTrue(latch.await(5, TimeUnit.SECONDS))
            }
            suspendOrAwaitAdapt(runningAtomic) { latch, cb ->
                VersionAdapter.ensureAdapted(
                    context = context, classLoader = adapterLoader, resetTimestamp = 0L, callback = cb,
                    startupCache = baselineSnap, guestBrowsingEnabled = false, splashAutoNightEnabled = true
                )
                assertTrue(latch.await(5, TimeUnit.SECONDS))
            }
        } finally {
            runningAtomic.set(false)
        }

        // publicSourceDir / splitPublicSourceDirs fallback + ensureAdapted disabled probe skip
        @Suppress("DEPRECATION")
        val fallbackPkgInfo = PackageInfo().apply {
            packageName = "tv.danmaku.bili"
            this.versionCode = 9210200
            this.versionName = "9.21.0"
            applicationInfo = ApplicationInfo().apply {
                packageName = "tv.danmaku.bili"
                sourceDir = null
                publicSourceDir = baseApk.absolutePath
                splitSourceDirs = null
                splitPublicSourceDirs = arrayOf(splitApk.absolutePath)
            }
        }
        val fallbackPm = createFakePackageManager(fallbackPkgInfo)
        val fallbackContext = object : ContextWrapper(null) {
            override fun getPackageManager(): PackageManager = fallbackPm
            override fun getCacheDir(): File = cacheDir
        }
        val fallbackIdentity = readIdentity.invoke(VersionAdapter, fallbackContext) as HostIdentity
        assertEquals(9210200, fallbackIdentity.versionCode)
        val expectedFallbackFp = VersionAdapter.buildHostFingerprint(
            versionCode = 9210200L, versionName = "9.21.0",
            baseLength = baseApk.length(), baseLastModified = baseApk.lastModified(),
            splits = listOf(SplitFileMeta(splitApk.name, splitApk.length(), splitApk.lastModified()))
        )
        assertEquals(expectedFallbackFp, fallbackIdentity.fingerprint)
        assertTrue(fallbackIdentity.fingerprint.contains("|splits="))

        val fallbackBaseline = invokeVersionAdapterAdapt(
            fallbackContext, javaClass.classLoader!!, followFeedStyleEnabled = false, playerSpeedSessionEnabled = false,
            guestBrowsingEnabled = false, dexSource = null, vc = 9210200, hostFingerprint = fallbackIdentity.fingerprint,
            splashAutoNightEnabled = false
        )
        val probedClasses = mutableListOf<String>()
        val probeLoader = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                probedClasses += name
                if (name == PlayerSpeedSessionLocator.PARAMS ||
                    name == PlayerSpeedLocator.THESEUS2_SPEED_SERVICE ||
                    name == "com.tencent.mm.opensdk.openapi.IWXAPI"
                ) {
                    throw ClassNotFoundException(name)
                }
                return super.loadClass(name, resolve)
            }
        }
        try {
            runningAtomic.set(false)
            val adaptStarted = AtomicBoolean(false)
            VersionAdapter.ensureAdapted(
                context = fallbackContext,
                classLoader = probeLoader,
                resetTimestamp = 0L,
                callback = object : AdaptCallback {
                    override fun onAdaptStarted() { adaptStarted.set(true) }
                    override fun onAdaptFinished(ok: Boolean) = Unit
                },
                startupCache = StartupCacheSnapshot(fallbackIdentity, 0L, fallbackBaseline),
                playerSpeedSessionEnabled = false,
                playerSpeedCalculatorEnabled = false,
                weChatShareEnabled = false
            )
            assertFalse(adaptStarted.get())
            assertFalse(PlayerSpeedSessionLocator.PARAMS in probedClasses)
            assertFalse(PlayerSpeedLocator.THESEUS2_SPEED_SERVICE in probedClasses)
            assertFalse("com.tencent.mm.opensdk.openapi.IWXAPI" in probedClasses)
        } finally {
            runningAtomic.set(false)
        }
    }

    @Suppress("unused")
    private class ProtocolMethodFixture {
        companion object { const val ITEMS_FIELD_NUMBER = 1 }
        fun getItems(): List<String> = emptyList()
        fun getHolderType(): String = "av"
        fun getUri(): String = "bilibili://video/1"
    }

    private class IgnetEngineStub
    private class SplashSourceStub
    private class SpeedParamsStub
    private class SpeedManagerStub

    internal class FakePackageManagerTemplate(private val hostPackageInfo: PackageInfo) {
        fun getPackageInfo(packageName: String, flags: Int): PackageInfo {
            if (packageName == hostPackageInfo.packageName) return hostPackageInfo
            throw PackageManager.NameNotFoundException(packageName)
        }
    }

    private fun fakeAdapterContext(
        cacheDir: File,
        prefsByName: MutableMap<String, InMemorySharedPreferences>
    ): Context = object : ContextWrapper(null) {
        override fun getCacheDir(): File = cacheDir
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            prefsByName.getOrPut(name) { InMemorySharedPreferences() }.instance
    }

    private class InMemorySharedPreferences(
        initial: Map<String, Any?> = emptyMap()
    ) {
        val values = LinkedHashMap<String, Any?>(initial)

        val instance: SharedPreferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "edit" -> createEditor()
                "getAll" -> HashMap(values)
                "contains" -> values.containsKey(args?.get(0) as? String)
                "getString" -> (values[args?.get(0) as? String] as? String) ?: (args?.getOrNull(1) as? String)
                "getLong" -> (values[args?.get(0) as? String] as? Long) ?: (args?.getOrNull(1) as? Long ?: 0L)
                "getInt" -> (values[args?.get(0) as? String] as? Int) ?: (args?.getOrNull(1) as? Int ?: 0)
                "getBoolean" -> (values[args?.get(0) as? String] as? Boolean) ?: (args?.getOrNull(1) as? Boolean ?: false)
                else -> null
            }
        } as SharedPreferences

        private fun createEditor(): SharedPreferences.Editor {
            val puts = LinkedHashMap<String, Any?>()
            val removes = mutableListOf<String>()
            lateinit var editor: SharedPreferences.Editor
            editor = Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)
            ) { _, method, args ->
                when (method.name) {
                    "putLong", "putInt", "putString", "putBoolean" -> {
                        puts[args!![0] as String] = args[1]
                        editor
                    }
                    "remove" -> {
                        removes += args!![0] as String
                        editor
                    }
                    "clear" -> {
                        values.clear()
                        editor
                    }
                    "apply" -> {
                        removes.forEach { values.remove(it) }
                        values.putAll(puts)
                        Unit
                    }
                    "commit" -> {
                        removes.forEach { values.remove(it) }
                        values.putAll(puts)
                        true
                    }
                    else -> editor
                }
            } as SharedPreferences.Editor
            return editor
        }
    }

    private fun minimalValidResult(): AdaptResult = AdaptResult(
        biliVersionCode = 9090300,
        ts = 1000L,
        commentLow = null,
        commentHigh = null,
        mineEntry = null,
        pause = PausePoints(emptyList(), null, null, null),
        banner = null,
        homeTopBar = null,
        mineVip = null,
        blockUpdate = null,
        dynamicTabs = null,
        fullNumbers = null,
        playerPortrait = null,
        playerInteractiveOverlays = null,
        pgcAutoActivityPopup = null,
        playerStatusBar = null,
        homeRecommendFeed = null,
        videoRelate = null,
        homeTabs = null,
        homeComponents = null,
        mineComponents = null,
        mineAccountMine = null,
        storyFeed = null,
        bottomBar = null,
        playerQuality = null,
        teenagersMode = null,
        commentPurify = null,
        commentFilter = null,
        commentTopology = null,
        commentSection = null,
        splashAds = null,
        hostFingerprint = "tv.danmaku.bili|9090300|9.9.3|1000|100|${VersionAdapterContract.RULE_VERSION}",
        protocolFingerprint = "protocol-v1:1:${"a".repeat(24)}",
        diagnostics = emptyList(),
        dexSourceFingerprint = "dex-v1:38:${"b".repeat(64)}",
        domainRuleVersions = VersionAdapterContract.DOMAIN_RULE_VERSIONS
    )

    private fun full68KeyValidResult(): AdaptResult {
        val topologyMethods = linkedMapOf<String, HookPoint>().apply {
            CommentTopologyPoints.REQUIRED_METHOD_KEYS.forEach { key ->
                put(key, HookPoint("com.bapis.Topology", "m_${key.replace('.', '_')}", emptyList()))
            }
        }
        val chromeFamily = HomeChromeAdapter.families.values.first()
        val chromePkg = HomeChromeAdapter.ownerPackage(chromeFamily)
        val followUpFamily = FollowUpRecommendFeatureInstaller.Family.entries.first().key
        val pageSurface = com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.HostCardSemanticAdapter.families.values.first()
        val storyPrefClass = "${StoryQualityLocator.PREFERENCE_PACKAGE}.TestPref"
        val coverage = AdaptCoverage(
            PointFamily.entries.associateWith { Attempt.FOUND }
        )
        return AdaptResult(
            biliVersionCode = 9090300,
            ts = 123456L,
            commentLow = HookPoint("comment.Low", "o0"),
            commentHigh = HookPoint("comment.High", "bind", listOf("int", "android.view.View")),
            mineEntry = MineEntryPoint(
                buildMethods = listOf(HookPoint("tv.danmaku.bili.ui.main2.mine.HomeUserCenterFragment", "build", listOf("tv.danmaku.bili.ui.main2.api.AccountMine"))),
                groupListField = "groups",
                adapterField = "adapter",
                clickMethod = HookPoint("tv.danmaku.bili.ui.main2.mine.HomeUserCenterFragment\$e", "onClick", listOf("com.bilibili.lib.homepage.mine.MenuGroup\$Item"))
            ),
            pause = PausePoints(
                requestMethods = listOf(HookPoint("paused.Request", "invokeSuspend", listOf("java.lang.Object"))),
                legacyCallback = HookPoint("paused.Legacy", "invoke", emptyList()),
                panelShow = HookPoint("com.bilibili.ship.theseus.united.page.ad.AdPanelRepository", "showPanel", listOf("a", "b")),
                countdown = HookPoint("com.bilibili.ship.theseus.united.page.pausedpage.Countdown", "invokeSuspend", listOf("java.lang.Object")),
                apiEntry = HookPoint("kntr.app.ad.biz.videodetail.pausedpage.b", "a", listOf("java.lang.Object", "kotlin.coroutines.Continuation"))
            ),
            banner = BannerPoint("com.bilibili.pegasus.holders.bannerv8.V8Banner", listOf(HookPoint("s.Banner", "onAttachedToWindow", emptyList()))),
            homeTopBar = HomeTopBarPoints(
                gameMenu = HookPoint("home.Menu", "bind", listOf("android.view.Menu", "android.view.MenuInflater"), viewField = "cfg"),
                composeGameMenu = HookPoint("home.TopRight", "invoke", listOf("java.util.List", "kotlin.coroutines.Continuation")),
                baseOnViewCreated = HookPoint("home.Base", "onViewCreated", listOf("android.view.View", "android.os.Bundle"), viewField = "tv"),
                defaultWordMethods = listOf(HookPoint("home.Main", "setWord", listOf("word.Model"))),
                kotlinDefaultWords = HookPoint("home.MainKt", "x", listOf("a", "b", "c", "d"))
            ),
            mineVip = MineVipPoint(
                onResume = HookPoint("mine.Fragment", "onResume", emptyList(), viewField = "vipMgr"),
                bindingField = "binding",
                rootGetter = HookPoint("mine.VipBinding", "getRoot", emptyList())
            ),
            blockUpdate = HookPoint("vd6.c", "a", listOf("android.content.Context")),
            dynamicTabs = DynamicTabsPoint(
                listGetter = HookPoint("dynamic.Mediator", "getTabs", emptyList()),
                addTab = HookPoint("com.google.android.material.tabs.TabLayout", "addTab", listOf("Tab", "boolean")),
                tabCustomViewGetter = HookPoint("Tab", "getCustomView", emptyList()),
                mediatorTabClassName = "dynamic.TabLayout",
                itemClassName = "dynamic.TabItem",
                itemTitleField = "a",
                itemNameField = "b"
            ),
            fullNumbers = FullNumberPoints(listOf(HookPoint("kntr.NumberFormat", "format", listOf("long")))),
            playerPortrait = PlayerPortraitPoints(listOf(HookPoint("com.bilibili.GeminiPlayerFullStoryWidget", "setVisibility", listOf("int")))),
            playerInteractiveOverlays = PlayerInteractiveOverlayPoints(
                families = listOf(
                    PlayerInteractiveGuideFamily(
                        replyClassName = "com.bapis.Reply",
                        guideGetter = HookPoint("com.bapis.Reply", "getVideoGuide", emptyList()),
                        guideClears = listOf(HookPoint("com.bapis.Guide", "clearCard", emptyList()))
                    )
                ),
                mossExecutes = listOf(HookPoint("com.bapis.Moss", "execute", listOf("req"))),
                commandGetter = HookPoint("com.bapis.DmReply", "getCommand", emptyList()),
                commandClear = HookPoint("com.bapis.DmReply", "clearCommand", emptyList()),
                commandDefault = HookPoint("com.bapis.Command", "getDefaultInstance", emptyList())
            ),
            pgcAutoActivityPopup = PgcAutoActivityPopupPoints(
                construct = HookPoint("com.bilibili.ship.theseus.ogv.activity.OgvActivityVo_JsonDescriptor", "constructWith", listOf("[Ljava.lang.Object;")),
                constructorParameters = listOf("a", "b", "com.bilibili.ship.theseus.ogv.activity.OgvActivityHalfScreenPopup"),
                popupIndex = 2,
                propertiesField = "props"
            ),
            playerStatusBar = PlayerStatusBarPoints(listOf(HookPoint("com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity", "onCreate", listOf("android.os.Bundle")))),
            homeRecommendFeed = HomeRecommendFeedPoints(
                responseItemGetters = listOf(HookPoint("com.bilibili.PegasusResponse", "getItems", emptyList())),
                holderTypeGetter = HookPoint("com.bilibili.PegasusHolderData", "getHolderType", emptyList()),
                bizTypeGetter = null, adInfoGetter = null,
                cardTypeGetter = HookPoint("com.bilibili.BasePegasusData", "getCardType", emptyList()),
                cardGotoGetter = HookPoint("com.bilibili.BasePegasusData", "getCardGoto", emptyList()),
                goToGetter = HookPoint("com.bilibili.BasePegasusData", "getGoTo", emptyList()),
                uriGetter = HookPoint("com.bilibili.BasePegasusData", "getUri", emptyList()),
                paramGetter = HookPoint("com.bilibili.BasePegasusData", "getParam", emptyList()),
                titleGetter = HookPoint("com.bilibili.BasePegasusData", "getTitle", emptyList()),
                subtitleGetter = null, descGetter = null,
                playerArgsGetter = HookPoint("com.bilibili.BasePegasusData", "getPlayerArgs", emptyList()),
                playerArgsDurationField = "duration"
            ),
            videoRelate = VideoRelatePoints(
                responseItemGetters = listOf(HookPoint("com.bapis.Relates", "getCardsList", emptyList())),
                cardCaseGetters = listOf(HookPoint("com.bapis.RelateCard", "getCardCase", emptyList())),
                gotoGetters = emptyList(), cardTypeGetters = emptyList(), relateCardTypeGetters = emptyList(),
                fromSourceTypeGetters = emptyList(),
                fromSourceTypeChains = listOf(SourceTypeMethodChain(HookPoint("c.Card", "getBasicInfo", emptyList()), HookPoint("c.Basic", "getFromSourceType", emptyList()))),
                relateCardTypeValueGetters = emptyList(), directDurationGetters = emptyList(),
                durationChains = listOf(DurationMethodChain(HookPoint("c.Card", "getAv", emptyList()), HookPoint("c.Av", "getDuration", emptyList()))),
                playCountChains = listOf(PlayCountMethodChain(HookPoint("c.Card", "getAv", emptyList()), HookPoint("c.Av", "getStat", emptyList()), HookPoint("c.Stat", "getVt", emptyList()), HookPoint("c.Vt", "getValue", emptyList()))),
                reasonChains = listOf(ReasonMethodChain(listOf(HookPoint("c.Relate", "getRcmdReason", emptyList())))),
                commercialEvidenceChains = listOf(BooleanMethodChain(listOf(HookPoint("c.Card", "hasCm", emptyList())))),
                detailRelateService = DetailRelateServicePoint(
                    componentFactory = HookPoint("s.DetailRelateService", "C0", listOf("s.r2")),
                    typeField = "type",
                    typeGetter = HookPoint("s.r2", "y", emptyList()),
                    titleGetter = HookPoint("s.r2", "d", emptyList())
                )
            ),
            homeTabs = HomeTabPoints(
                buildMethod = HookPoint("tv.danmaku.bili.ui.main2.HomeFragmentV2", "build", listOf("java.util.List")),
                resourceClassName = "tv.danmaku.bili.ui.main2.resource.z",
                idField = "id", titleField = "title", uriField = "uri", reporterIdField = "reporterId"
            ),
            homeComponents = HomeComponentPoints(
                onViewCreated = HookPoint("androidx.fragment.app.Fragment", "onViewCreated", listOf("android.view.View", "android.os.Bundle")),
                parentFragmentGetter = HookPoint("androidx.fragment.app.Fragment", "getParentFragment", emptyList())
            ),
            mineComponents = MineComponentPoints(
                itemListGetters = listOf(HookPoint("com.bilibili.lib.homepage.mine.MenuGroupV2", "getItemList", emptyList())),
                itemTitleGetters = listOf(HookPoint("com.bilibili.lib.homepage.mine.MenuGroupV2\$Item", "getTitle", emptyList()))
            ),
            mineAccountMine = MineAccountMinePoints(
                buildMethods = listOf(HookPoint("mine.Frag", "build", listOf("mine.AccountMine"))),
                accountMineClass = "tv.danmaku.bili.ui.main2.api.AccountMine",
                sectionListV2Field = "sectionListV2",
                groupClass = "com.bilibili.lib.homepage.mine.MenuGroup",
                groupTitleField = "title", groupItemListField = "itemList",
                itemClass = "com.bilibili.lib.homepage.mine.MenuGroup\$Item",
                itemTitleField = "title", itemIdField = "id", itemUriField = "uri",
                itemVisibleField = "visible", itemLocalShowField = "localShow",
                liveTipField = "liveTip", vipSectionRightField = "vipSectionRight", sectionButtonField = "button"
            ),
            storyFeed = StoryFeedPoints(
                responseItemGetters = listOf(HookPoint("com.bilibili.video.story.api.StoryFeedResponse", "getItems", emptyList())),
                pagerListMethods = emptyList(),
                adGetter = HookPoint("com.bilibili.video.story.StoryDetail", "isAd", emptyList()),
                liveGetter = null, gameGetter = null, bangumiGetter = null, courseGetter = null,
                musicGetter = null, cartInfoGetter = null, dramaPromptGetter = null, seasonInfoGetter = null, seasonTypeGetter = null
            ),
            bottomBar = BottomBarPoints(
                tabsGetter = HookPoint("com.bilibili.lib.homepage.widget.TabHost", "getTabs", emptyList()),
                bindTabMethod = HookPoint("com.bilibili.lib.homepage.widget.TabHost", "bindTab", listOf("int", "android.view.View")),
                itemClassName = "com.bilibili.lib.homepage.widget.TabItem",
                itemStringFields = listOf("tabId", "title")
            ),
            playerQuality = PlayerQualityPoints(
                defaultQualityMethod = HookPoint("Zz9.q", "a", emptyList()),
                capabilitySignals = listOf("stream_quality", "codec"),
                story = StoryQualityPoints(
                    preferenceClassName = storyPrefClass,
                    manualMethod = HookPoint(StoryQualityLocator.SERVICE, "setManual", listOf(storyPrefClass, StoryQualityLocator.CONTINUATION)),
                    serviceFieldName = "service",
                    ownerOnCreate = HookPoint(StoryQualityLocator.OWNER, "onCreate", listOf(StoryQualityLocator.LIFECYCLE_OWNER)),
                    ownerRelease = HookPoint(StoryQualityLocator.OWNER, "release", emptyList()),
                    ownerOnDestroy = HookPoint(StoryQualityLocator.OWNER, "onDestroy", listOf(StoryQualityLocator.LIFECYCLE_OWNER))
                ),
                normalAutoSwitch = HookPoint("com.bilibili.player.NormalSwitch", "isAuto", emptyList())
            ),
            teenagersMode = TeenagersModePoints(listOf(HookPoint("tv.danmaku.bili.teenagers.DialogActivity", "onCreate", listOf("android.os.Bundle")))),
            commentPurify = CommentPurifyPoints(
                urlMapGetters = listOf(HookPoint("com.bapis.Content", "getUrlsMap", emptyList())),
                emptyPageGetters = listOf(CommentEmptyPagePoint(HookPoint("c.Main", "getEmptyPage", emptyList()), HookPoint("c.EmptyPage", "getDefaultInstance", emptyList()))),
                voteWidgetMethods = listOf(HookPoint("c.Vote", "bind", listOf("int"))),
                follow = CommentFollowPoints(listOf(HookPoint("c.Follow", "bind", emptyList())), emptyList(), null),
                qoe = CommentOptionalPayloadPoint(HookPoint("c.Main", "hasQoe", emptyList()), HookPoint("c.Main", "getQoe", emptyList()), HookPoint("c.Qoe", "getDefaultInstance", emptyList())),
                operations = emptyList(),
                quickReplyDialogMethods = listOf(HookPoint("c.Collector", "emit", listOf("a.PublishDialogIntent", "kotlin.coroutines.Continuation"))),
                urlSchemaGetters = listOf(HookPoint("c.Url", "getAppUrlSchema", emptyList()))
            ),
            commentFilter = CommentFilterPoints(
                replyListGetters = listOf(HookPoint("com.bapis.MainListReply", "getRepliesList", emptyList())),
                contentGetter = HookPoint("com.bapis.ReplyInfo", "getContent", emptyList()),
                messageGetter = HookPoint("com.bapis.Content", "getMessage", emptyList()),
                memberGetter = HookPoint("com.bapis.ReplyInfo", "getMember", emptyList()),
                levelGetter = HookPoint("com.bapis.Member", "getLevel", emptyList())
            ),
            commentTopology = CommentTopologyPoints(
                mapperMethods = listOf(HookPoint("com.bilibili.comment.Mapper", "map", listOf("com.bapis.ReplyInfo"))),
                replyMossClassName = "com.bapis.ReplyMoss",
                methods = topologyMethods
            ),
            commentSection = CommentSectionPoints(
                listConstructors = listOf(ListConstructorPoint("com.bilibili.TheseusTabPagerService", listOf("java.util.List"), 0)),
                locatableTagGetter = HookPoint("com.bilibili.TabPage", "getTag", emptyList())
            ),
            splashAds = SplashAdPoints(
                listGetters = listOf(HookPoint("com.bilibili.SplashResponse", "getList", emptyList())),
                itemSignalGetters = listOf(SplashItemSignalPoint("is_ad", HookPoint("com.bilibili.SplashItem", "isAd", emptyList())))
            ),
            hostFingerprint = "tv.danmaku.bili|9090300|9.9.3|1000|100|${VersionAdapterContract.RULE_VERSION}",
            protocolFingerprint = "protocol-v1:4:${"c".repeat(24)}",
            diagnostics = listOf(
                AdaptDiagnostic("comment.low", AdaptState.FOUND, "comment.Low#o0"),
                AdaptDiagnostic(BrandSplashDecoderAdapter.DIAGNOSTIC, AdaptState.FOUND, "verified")
            ),
            dexSourceFingerprint = "dex-v1:38:${"b".repeat(64)}",
            sponsorPlayer = com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SponsorPlayerClasses("com.bilibili.ship.theseus.Run", "com.bilibili.ship.theseus.Wrap", null, "com.bilibili.ship.theseus.Seek"),
            playerSpeedSession = com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedSessionClasses("com.bilibili.SpeedSession", null),
            playerSpeedCalculator = com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.LongPressCalculatorPoint("com.bilibili.Calc", "calc", "com.bilibili.Playable"),
            weChatShare = WeChatShareClasses("com.bilibili.wx.Legacy", "init", "com.bilibili.wx.Modern", "init", false),
            commentClassicStyle = com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CommentClassicStylePoints(
                listOf(HookPoint("com.bilibili.lib.dd.DeviceDecision", "getBoolean", listOf("java.lang.String", "boolean"))),
                listOf(HookPoint("kntr.base.dd.IDeviceDecisionKt", "getBool", listOf("kntr.base.dd.IDeviceDecision", "java.lang.String", "boolean", "kntr.base.dd.IDeviceDecision\$Context")))
            ),
            playerPopupPromotion = com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerPopupPromotionPoints(
                advertFloat = HookPoint("com.bilibili.adcommon.data.model.DmAdvert", "getFloatLayers", emptyList())
            ),
            brandSplashDecoder = HookPoint("Nr1.a", "decode", listOf(BrandSplashDecoderAdapter.INFO)),
            followFeedStyle = com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.FollowFeedPoints("iU.d", "f", "tS.G", "tS.H", "com.bilibili.bplus.followinglist.home.Q", "current"),
            playerSpeedMenu = com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedMenuPoints(adapter = HookPoint("${PlayerSpeedLocator.SPEED_WIDGET_PKG}.MenuAdapter", "populate", listOf("float"))),
            pageBoundaries = com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PageBoundaryPoints(
                cardSurfaces = mapOf(pageSurface to HookPoint("com.bilibili.TestCard", "bind", listOf("a", "b", "c")))
            ),
            homeChrome = mapOf(chromeFamily to HookPoint("$chromePkg.HomeChrome", "invoke", listOf("a", "b", "c", "d"))),
            homeChromeSemantics = HomeChromeSemanticsPoint("tv.danmaku.bili.khome.SemanticsHolder", "KEY"),
            systemEnvironment = SystemEnvironmentPoints(
                environment = HookPoint("com.bilibili.Env", "check", listOf(SystemEnvironmentAdapter.ENV_PARAM_SET))
            ),
            commentComposeMenu = CommentComposeMenuPoints(bubbleRow = HookPoint("com.bilibili.ComposeMenu", "bubble", listOf("a", "b", "c", "d"))),
            kmpGrpcWireEngine = HookPoint("kntr.WireEngine", "invoke", listOf("a", "b", "c", "d")),
            brandSplashKntrDraw = "kntr.srcs.app.splash.brand.startup.BrandDraw",
            brandSplashKntrExit = "kntr.srcs.app.splash.brand.startup.BrandExit",
            brandSplashKntrPage = HookPoint("kntr.srcs.app.splash.brand.startup.BrandPage", "invoke", List(6) { "java.lang.Object" }),
            cloudKeyFreezeContract = "com.bilibili.lib.blconfig.internal.FeatureContract",
            privacyIgnetHttp = HookPoint("com.bilibili.lib.ighttp.TestRequest", "execute", emptyList()),
            privacyKNeuron = "kntr.base.neuron.TestNeuron",
            guestPlayerCore = "com.bilibili.video.player.GuestPlayerCore",
            detailSpecialTagMapper = HookPoint("${DetailSpecialTagAdapter.LEGACY_MAPPER_PACKAGE}.Mapper", "map", listOf(DetailUnitedPresentationPurifyPolicy.SPECIAL_TAG_CLASS)),
            aiPlaylistDirector = "com.bilibili.ship.theseus.playlist.TestDirector",
            followUpRecommendBodies = mapOf(followUpFamily to "com.bilibili.ship.theseus.ugc.intro.uprecommend.ReqBody"),
            detailRelateGameComponent = "${DetailRelateGameAdapter.RELATE_GAME_PACKAGE}.GameComponent",
            commentReplyButtonOwners = mapOf("legacy" to "${com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CommentReplyButtonAdapter.LEGACY_PACKAGES["legacy"]}.ButtonHandler"),
            homeRecommendEarlyLoadListener = "com.bilibili.pegasus.EarlyLoadListener",
            legacyStoryDanmakuBind = HookPoint(LegacyStoryDanmakuAdapter.OWNER, "bind", listOf("com.bilibili.video.story.StoryDetail", "int")),
            playerSpeedTheseus2LongPress = Theseus2LongPressPoint(
                "com.bilibili.ship.theseus2.united.player.LaunchPort",
                "com.bilibili.ship.theseus2.united.player.SpeedPort",
                "setSpeed"
            ),
            coverage = coverage,
            domainRuleVersions = VersionAdapterContract.DOMAIN_RULE_VERSIONS
        )
    }
}
