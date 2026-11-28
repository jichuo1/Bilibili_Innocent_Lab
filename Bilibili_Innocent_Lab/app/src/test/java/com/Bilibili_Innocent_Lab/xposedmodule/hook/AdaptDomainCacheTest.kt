package com.Bilibili_Innocent_Lab.xposedmodule.hook

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptDecision
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptDiagnostic
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptState
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.BannerPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.CommentComposeMenuPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.CommentTopologyPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HostIdentity
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.MineAccountMinePoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.MineEntryPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.PgcAutoActivityPopupPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.PlayerInteractiveGuideFamily
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.PlayerInteractiveOverlayPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.SplitFileMeta
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.StartupCacheSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptAssistGate
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptCoverage
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptDomain
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptDomainCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AiPlaylistDirectorAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.BrandSplashDecoderAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.BrandSplashKntrAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CloudKeyFreezeAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CommentClassicStyleLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CommentClassicStyleLocatorTest
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CommentClassicStylePoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CommentComposeMenuAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CommentQuickReplyAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CommentReplyButtonAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.DetailRelateGameAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.DetailSpecialTagAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.FollowFeedAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.FollowFeedPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.FollowUpRecommendAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.GuestPlayerCoreAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.HomeChromeAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.HomeRecommendEarlyLoadAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.HomeTabResourceAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.KmpGrpcWireAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.KotlinDefaultWordsLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.LegacyStoryDanmakuAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.LongPressCalculatorPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.NormalQualityLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PageBoundaryAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PageBoundaryPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PausedAdDexAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerPopupPromotionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerPopupPromotionPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedMenuAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedMenuPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedSessionClasses
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedSessionLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PointFamily
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PrivacyChannelAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SponsorPlayerClasses
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SponsorPlayerLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.StoryQualityLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.StoryQualityPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SystemEnvironmentAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SystemEnvironmentPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.Theseus2LongPressPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.WeChatShareClasses
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.WeChatShareCompatAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistAttemptGuard
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistEngine
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistRequest
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.withoutDomains
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.DetailUnitedPresentationPurifyPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerEndPageRecommendLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome.HomeChromeSemanticsPoint
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AdaptDomainCacheTest {
    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private class FakeAccountMine {
        @JvmField var sectionListV2: List<Any> = emptyList()
        fun build() {}
    }

    private class FakeAccountMineMissingField {
        fun build() {}
    }

    private companion object {
        val sharedBaseResult by lazy { VersionAdapterTest().result() }
        val sharedQuickResult by lazy { requireNotNull(VersionAdapter.quickLocate(AdaptDomainCacheTest::class.java.classLoader!!)) }
    }

    @Test
    fun splitMetadataFingerprintAndAllModeContentRebind() {
        // 1. Split APK metadata fingerprint
        val singleFp = VersionAdapter.buildHostFingerprint(
            versionCode = 9210200L,
            versionName = "9.21.0",
            baseLength = 120_000_000L,
            baseLastModified = 1_760_000_000L,
            splits = emptyList()
        )
        assertEquals("tv.danmaku.bili|9210200|9.21.0|120000000|1760000000|${VersionAdapterContract.RULE_VERSION}", singleFp)
        val parsedSingle = requireNotNull(VersionAdapter.parseHostFingerprint(singleFp))
        assertNull(parsedSingle.splitsHash)
        assertEquals(VersionAdapterContract.RULE_VERSION, parsedSingle.ruleVersion)

        val splitA = SplitFileMeta("split_config.arm64_v8a.apk", 45_000_000L, 1_760_000_100L)
        val splitB = SplitFileMeta("split_config.xxhdpi.apk", 12_000_000L, 1_760_000_200L)
        val fpOrder1 = VersionAdapter.buildHostFingerprint(9210200L, "9.21.0", 120_000_000L, 1_760_000_000L, listOf(splitA, splitB))
        val fpOrder2 = VersionAdapter.buildHostFingerprint(9210200L, "9.21.0", 120_000_000L, 1_760_000_000L, listOf(splitB, splitA))
        assertEquals("Split order must not affect hash", fpOrder1, fpOrder2)
        val parsedSplit = requireNotNull(VersionAdapter.parseHostFingerprint(fpOrder1))
        assertNotNull(parsedSplit.splitsHash)
        assertEquals(16, parsedSplit.splitsHash!!.length)
        assertTrue(fpOrder1.endsWith("|${VersionAdapterContract.RULE_VERSION}"))
        assertFalse("Directory paths must never appear in fingerprint", fpOrder1.contains("/data/app"))

        val fpLengthChanged = VersionAdapter.buildHostFingerprint(
            9210200L, "9.21.0", 120_000_000L, 1_760_000_000L,
            listOf(splitA.copy(length = splitA.length + 1L), splitB)
        )
        val fpMtimeChanged = VersionAdapter.buildHostFingerprint(
            9210200L, "9.21.0", 120_000_000L, 1_760_000_000L,
            listOf(splitA.copy(lastModified = splitA.lastModified + 1000L), splitB)
        )
        val fpNameChanged = VersionAdapter.buildHostFingerprint(
            9210200L, "9.21.0", 120_000_000L, 1_760_000_000L,
            listOf(splitA.copy(name = "split_config.armeabi_v7a.apk"), splitB)
        )
        assertNotEquals(fpOrder1, fpLengthChanged)
        assertNotEquals(fpOrder1, fpMtimeChanged)
        assertNotEquals(fpOrder1, fpNameChanged)

        for (bad in listOf(
            "com.other|9210200|9.21.0|1000|100|${VersionAdapterContract.RULE_VERSION}",
            "tv.danmaku.bili|9210200|9.21.0|0|100|${VersionAdapterContract.RULE_VERSION}",
            "tv.danmaku.bili|9210200|9.21.0|1000|0|${VersionAdapterContract.RULE_VERSION}",
            "tv.danmaku.bili|9210200|9.21.0|1000|100|splits=nothex1234567890|${VersionAdapterContract.RULE_VERSION}",
            "tv.danmaku.bili|9210200|9.21.0|1000|100|splits=abcdef|${VersionAdapterContract.RULE_VERSION}",
            "tv.danmaku.bili|9210200|9.21.0|1000|100|extra|${VersionAdapterContract.RULE_VERSION}",
            "runtime-no-context|rules=${VersionAdapterContract.RULE_VERSION}"
        )) {
            assertNull("Expected reject for $bad", VersionAdapter.parseHostFingerprint(bad))
        }

        // 2. All-mode DEX + Protocol content rebind
        val loader = requireNotNull(javaClass.classLoader)
        val live = sharedQuickResult
        val actualProtocol = requireNotNull(VersionAdapter.verifyProtocolFingerprint(loader, live))
        assertEquals(live.protocolFingerprint, actualProtocol)

        val validDex = "dex-v1:42:" + "a".repeat(64)
        val fp6Old = VersionAdapter.buildHostFingerprint(9210200L, "9.21.0", 1000L, 100L)
        val fp6New = VersionAdapter.buildHostFingerprint(9210200L, "9.21.0", 2000L, 200L)
        val fp7 = VersionAdapter.buildHostFingerprint(
            9210200L, "9.21.0", 2000L, 200L,
            listOf(SplitFileMeta("split_config.arm64_v8a.apk", 5000L, 300L))
        )

        val stored = live.copy(
            biliVersionCode = 9210200,
            ts = 50_000L,
            hostFingerprint = fp6Old,
            dexSourceFingerprint = validDex,
            diagnostics = live.diagnostics.map {
                if (it.state == AdaptState.NOT_REQUESTED) AdaptDiagnostic(it.id, AdaptState.FOUND, it.detail) else it
            }
        )

        val rebound6 = requireNotNull(
            VersionAdapter.rebindRepackedCache(stored, HostIdentity(9210200, fp6New), 50_000L, validDex, actualProtocol)
        )
        assertEquals(fp6New, rebound6.hostFingerprint)
        assertEquals(50_000L, rebound6.ts)

        val rebound7 = requireNotNull(
            VersionAdapter.rebindRepackedCache(stored, HostIdentity(9210200, fp7), 50_000L, validDex, actualProtocol)
        )
        assertEquals(fp7, rebound7.hostFingerprint)
        val reboundBackTo6 = requireNotNull(
            VersionAdapter.rebindRepackedCache(rebound7, HostIdentity(9210200, fp6Old), 50_000L, validDex, actualProtocol)
        )
        assertEquals(fp6Old, reboundBackTo6.hostFingerprint)

        val zeroProtocol = "protocol-v1:0:" + "a".repeat(24)
        assertNull(VersionAdapter.rebindRepackedCache(stored.copy(protocolFingerprint = zeroProtocol), HostIdentity(9210200, fp6New), 50_000L, validDex, zeroProtocol))
        assertNull(VersionAdapter.rebindRepackedCache(stored, HostIdentity(9210200, fp6New), 50_000L, validDex, actualProtocol.replace('0', 'f').let { if (it == actualProtocol) actualProtocol.dropLast(1) + "0" else it }))
        assertNull(VersionAdapter.rebindRepackedCache(stored, HostIdentity(9210200, fp6New), 50_000L, validDex, null))
        assertNull(VersionAdapter.rebindRepackedCache(stored, HostIdentity(9210200, fp6New), 50_000L, validDex, "invalid"))
        assertNull(VersionAdapter.rebindRepackedCache(stored, HostIdentity(9210200, fp6New), 50_000L, validDex.replace('a', 'b'), actualProtocol))
        assertNull(VersionAdapter.rebindRepackedCache(stored, HostIdentity(9210200, fp6New), 50_001L, validDex, actualProtocol))
        assertNull(VersionAdapter.rebindRepackedCache(stored, HostIdentity(9210200, fp6New.substringBeforeLast('|') + "|${VersionAdapterContract.RULE_VERSION + 1}"), 0L, validDex, actualProtocol))

        val hiddenRelateLoader = object : ClassLoader(loader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == "com.bapis.bilibili.app.view.v1.RelatesFeedReply") {
                    throw ClassNotFoundException(name)
                }
                return super.loadClass(name, resolve)
            }
        }
        val driftedProtocol = VersionAdapter.verifyProtocolFingerprint(hiddenRelateLoader, stored)
        assertNotEquals(actualProtocol, driftedProtocol)
        assertNull(VersionAdapter.rebindRepackedCache(stored, HostIdentity(9210200, fp6New), 0L, validDex, driftedProtocol))

        val zeroEvidenceCache = stored.copy(
            homeRecommendFeed = null, videoRelate = null, commentFilter = null,
            playerQuality = null, splashAds = null, mineAccountMine = null
        )
        assertNull(VersionAdapter.verifyProtocolFingerprint(loader, zeroEvidenceCache))

        val mineOnlyCache = zeroEvidenceCache.copy(
            mineAccountMine = MineAccountMinePoints(
                buildMethods = listOf(HookPoint(FakeAccountMine::class.java.name, "build", emptyList())),
                accountMineClass = FakeAccountMine::class.java.name,
                sectionListV2Field = "sectionListV2",
                groupClass = "g", groupTitleField = null, groupItemListField = "i",
                itemClass = "item", itemTitleField = "t", itemIdField = null, itemUriField = null,
                itemVisibleField = null, itemLocalShowField = null, liveTipField = null,
                vipSectionRightField = null, sectionButtonField = null
            )
        )
        assertNotNull(VersionAdapter.verifyProtocolFingerprint(loader, mineOnlyCache))
        val hiddenMineLoader = object : ClassLoader(loader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == FakeAccountMine::class.java.name) throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }
        assertNull(VersionAdapter.verifyProtocolFingerprint(hiddenMineLoader, mineOnlyCache))
        assertNull(VersionAdapter.verifyProtocolFingerprint(loader, mineOnlyCache.copy(
            mineAccountMine = mineOnlyCache.mineAccountMine!!.copy(accountMineClass = FakeAccountMineMissingField::class.java.name)
        )))
    }

    @Test
    fun domainCatalogCompletenessAndDrvJsonSerialization() {
        // 3. 13-domain catalog completeness & disjointness
        assertEquals(VersionAdapterContract.DOMAIN_RULE_VERSIONS.keys, AdaptDomain.entries.map { it.key }.toSet())
        assertEquals(13, AdaptDomain.entries.size)

        val seenFamilies = mutableListOf<PointFamily>()
        AdaptDomain.entries.forEach { domain ->
            seenFamilies += AdaptDomainCatalog.pointFamiliesByDomain.getValue(domain)
        }
        assertEquals("Every PointFamily appears exactly once", PointFamily.entries.size, seenFamilies.size)
        assertEquals(PointFamily.entries.toSet(), seenFamilies.toSet())
        PointFamily.entries.forEach { assertNotNull(AdaptDomainCatalog.domainOf(it)) }

        val seenQueries = mutableListOf<DexAssistQuery>()
        AdaptDomain.entries.forEach { domain ->
            seenQueries += AdaptDomainCatalog.dexQueriesByDomain.getValue(domain)
        }
        assertEquals("Every DexAssistQuery appears exactly once", DexAssistQuery.entries.size, seenQueries.size)
        assertEquals(DexAssistQuery.entries.toSet(), seenQueries.toSet())
        DexAssistQuery.entries.forEach { assertNotNull(AdaptDomainCatalog.domainOf(it)) }

        val seenDiags = mutableListOf<String>()
        AdaptDomain.entries.forEach { domain ->
            val ids = AdaptDomainCatalog.diagnosticIdsByDomain.getValue(domain)
            seenDiags += ids
            assertFalse("$domain must not own protocol.structure", "protocol.structure" in ids)
        }
        assertEquals("Diagnostic IDs must be mutually exclusive across domains", seenDiags.size, seenDiags.toSet().size)

        // 4. drv JSON serialization & compatibility
        val quick = sharedQuickResult
        assertFalse("quickLocate JSON must omit drv so fixtures remain unchanged", quick.toJson().has("drv"))

        val withDrv = sharedBaseResult.copy(domainRuleVersions = VersionAdapterContract.DOMAIN_RULE_VERSIONS)
        val json = withDrv.toJson()
        assertTrue(json.has("drv"))
        val restored = requireNotNull(AdaptResult.fromJson(JSONObject(json.toString())))
        assertEquals(VersionAdapterContract.DOMAIN_RULE_VERSIONS, restored.domainRuleVersions)

        val allZero = AdaptDomain.entries.associate { it.key to 0 }
        val legacyJson = JSONObject(json.toString()).apply { remove("drv") }
        val legacyRestored = requireNotNull(AdaptResult.fromJson(legacyJson))
        assertEquals(emptyMap<String, Int>(), legacyRestored.domainRuleVersions)
        assertTrue(AdaptDomainCatalog.staleDomains(legacyRestored.domainRuleVersions, allZero).isEmpty())
        val expectedBumpedDomains = VersionAdapterContract.DOMAIN_RULE_VERSIONS
            .filterValues { it > 0 }.keys
            .mapNotNull { key -> AdaptDomain.entries.firstOrNull { it.key == key } }
            .toSet()
        assertEquals(expectedBumpedDomains, AdaptDomainCatalog.staleDomains(legacyRestored.domainRuleVersions))

        val partialJson = JSONObject(json.toString()).apply {
            put("drv", JSONObject().put("update", 1))
        }
        val partialRestored = requireNotNull(AdaptResult.fromJson(partialJson))
        assertEquals(mapOf("update" to 1), partialRestored.domainRuleVersions)
        assertEquals(setOf(AdaptDomain.UPDATE), AdaptDomainCatalog.staleDomains(partialRestored.domainRuleVersions, allZero))
        assertTrue(AdaptDomainCatalog.staleDomains(partialRestored.domainRuleVersions, allZero + ("update" to 1)).isEmpty())

        val futureJson = JSONObject(json.toString()).apply {
            getJSONObject("drv").put("future_domain_xyz", "v2")
        }
        val futureRestored = requireNotNull(AdaptResult.fromJson(futureJson))
        assertFalse(futureRestored.domainRuleVersions.containsKey("future_domain_xyz"))
        assertEquals(VersionAdapterContract.DOMAIN_RULE_VERSIONS, futureRestored.domainRuleVersions)

        assertNull(AdaptResult.fromJson(JSONObject(json.toString()).put("drv", JSONObject.NULL)))
        assertNull(AdaptResult.fromJson(JSONObject(json.toString()).put("drv", "bad")))
        for (badVal in listOf(-1, -1L, Long.MAX_VALUE, 1.5, true, "0", JSONObject.NULL)) {
            val corrupted = JSONObject(json.toString()).apply {
                getJSONObject("drv").put("update", badVal)
            }
            assertNull("Expected null for update=$badVal", AdaptResult.fromJson(corrupted))
        }
    }

    @Test
    fun selectiveDomainStrippingIncrementalDexGatesAndTargetAgreement() {
        val full = fullAll13DomainsResult()
        assertTrue(full.isUsableWith(full.hostFingerprint))

        fun outsidePlanFields(r: AdaptResult): Map<AdaptDomain, List<Boolean>> = linkedMapOf(
            AdaptDomain.PLAYER_SPEED to listOf(
                r.sponsorPlayer != null,
                r.playerSpeedSession != null,
                r.playerSpeedCalculator != null,
                r.playerSpeedMenu != null,
                r.playerSpeedTheseus2LongPress != null
            ),
            AdaptDomain.PLAYER_UI to listOf(
                r.playerPopupPromotion != null,
                r.guestPlayerCore != null,
                r.legacyStoryDanmakuBind != null
            ),
            AdaptDomain.COMMENT to listOf(
                r.commentClassicStyle != null,
                r.commentComposeMenu != null,
                r.commentReplyButtonOwners.isNotEmpty()
            ),
            AdaptDomain.HOME_FEED to listOf(
                r.homeRecommendEarlyLoadListener != null
            ),
            AdaptDomain.HOME_CHROME to listOf(
                r.homeChrome.isNotEmpty(),
                r.homeChromeSemantics != null
            ),
            AdaptDomain.DETAIL_RELATE to listOf(
                r.detailSpecialTagMapper != null,
                r.aiPlaylistDirector != null,
                r.followUpRecommendBodies.isNotEmpty(),
                r.detailRelateGameComponent != null
            ),
            AdaptDomain.FOLLOW_PAGE to listOf(
                r.followFeedStyle != null,
                r.pageBoundaries != null
            ),
            AdaptDomain.SPLASH to listOf(
                r.brandSplashDecoder != null,
                r.brandSplashKntrDraw != null,
                r.brandSplashKntrExit != null,
                r.brandSplashKntrPage != null
            ),
            AdaptDomain.KMP_WIRE to listOf(
                r.kmpGrpcWireEngine != null
            ),
            AdaptDomain.PRIVACY_COMPAT to listOf(
                r.weChatShare != null,
                r.systemEnvironment != null,
                r.cloudKeyFreezeContract != null,
                r.privacyIgnetHttp != null,
                r.privacyKNeuron != null
            )
        )

        val initialFields = outsidePlanFields(full)
        assertEquals(30, initialFields.values.sumOf { it.size })
        assertTrue("Full fixture must populate all 30 outsidePlan fields", initialFields.values.flatten().all { it })

        for (targetDomain in AdaptDomain.entries) {
            val expectedBumped = VersionAdapterContract.DOMAIN_RULE_VERSIONS.toMutableMap().apply {
                put(targetDomain.key, getValue(targetDomain.key) + 1)
            }
            val stale = AdaptDomainCatalog.staleDomains(full.domainRuleVersions, expectedBumped)
            assertEquals("Only $targetDomain should be stale", setOf(targetDomain), stale)

            val stripped = full.withoutDomains(stale)
            assertTrue("Stripped result for $targetDomain must remain usable", stripped.isUsableWith(full.hostFingerprint))
            assertFalse(targetDomain.key in stripped.domainRuleVersions)
            assertEquals(12, stripped.domainRuleVersions.size)

            val strippedFamilies = AdaptDomainCatalog.pointFamiliesByDomain.getValue(targetDomain)
            val strippedDiags = AdaptDomainCatalog.diagnosticIdsByDomain.getValue(targetDomain)
            PointFamily.entries.forEach { family ->
                if (family in strippedFamilies) {
                    assertFalse("$targetDomain family $family should have no points", stripped.hasPoint(family))
                    assertEquals(AdaptAssistGate.Attempt.NOT_REQUESTED, stripped.coverage!!.attempt(family))
                } else {
                    assertTrue("Non-stale family $family should retain points", stripped.hasPoint(family))
                    assertEquals(AdaptAssistGate.Attempt.FOUND, stripped.coverage!!.attempt(family))
                }
            }
            outsidePlanFields(stripped).forEach { (domain, flags) ->
                if (domain == targetDomain) {
                    assertTrue("$targetDomain outsidePlan fields should all be cleared", flags.none { it })
                } else {
                    assertTrue("Non-stale $domain outsidePlan fields should remain intact", flags.all { it })
                }
            }
            strippedDiags.forEach { diagId ->
                assertFalse("Stripped diagnostic $diagId should be removed", stripped.diagnostics.any { it.id == diagId })
            }
            assertTrue(stripped.diagnostics.any { it.id == "protocol.structure" })
            val retainedNonTargetDiag = AdaptDomain.entries
                .first { it != targetDomain }
                .let { AdaptDomainCatalog.diagnosticIdsByDomain.getValue(it).first() }
            assertTrue("Non-stale diagnostic $retainedNonTargetDiag must remain", stripped.diagnostics.any { it.id == retainedNonTargetDiag })
        }

        val wireStripped = full.withoutDomains(setOf(AdaptDomain.KMP_WIRE))
        assertNull(wireStripped.kmpGrpcWireEngine)
        assertNotNull(wireStripped.sponsorPlayer)
        assertNotNull(wireStripped.guestPlayerCore)
        assertNotNull(wireStripped.pause.apiEntry)
        val playerUiStripped = full.withoutDomains(setOf(AdaptDomain.PLAYER_UI))
        assertNull(playerUiStripped.pause.apiEntry)

        val staleFeedCache = full.copy(
            domainRuleVersions = VersionAdapterContract.DOMAIN_RULE_VERSIONS +
                ("home_feed" to (VersionAdapterContract.DOMAIN_RULE_VERSIONS.getValue("home_feed") + 1))
        )
        val quick = full.copy(
            biliVersionCode = 0,
            ts = 0L,
            hostFingerprint = "runtime-no-context|rules=${VersionAdapterContract.RULE_VERSION}",
            kmpGrpcWireEngine = null,
            homeRecommendEarlyLoadListener = null,
            homeTopBar = full.homeTopBar?.copy(kotlinDefaultWords = null),
            diagnostics = listOf(AdaptDiagnostic("protocol.structure", AdaptState.FOUND, full.protocolFingerprint))
        )
        val merged = requireNotNull(VersionAdapter.mergeRuntimeWithCached(quick, staleFeedCache))
        assertNull("Stale HOME_FEED cached earlyLoadListener must not leak through merge", merged.homeRecommendEarlyLoadListener)
        assertNull("Stale HOME_FEED cached kotlinDefaultWords must not leak through merge", merged.homeTopBar?.kotlinDefaultWords)
        assertNotNull("Fresh KMP_WIRE cached point must still merge", merged.kmpGrpcWireEngine)
        assertEquals(AdaptState.FOUND, merged.diagnostics.first { it.id == KmpGrpcWireAdapter.DIAGNOSTIC }.state)
        assertNull(merged.diagnostics.firstOrNull { it.id == HomeRecommendEarlyLoadAdapter.DIAGNOSTIC })

        val missingDiagCache = staleFeedCache.copy(
            homeRecommendEarlyLoadListener = null,
            kmpGrpcWireEngine = null,
            diagnostics = staleFeedCache.diagnostics.map { diag ->
                when (diag.id) {
                    HomeRecommendEarlyLoadAdapter.DIAGNOSTIC,
                    KmpGrpcWireAdapter.DIAGNOSTIC -> AdaptDiagnostic(diag.id, AdaptState.MISSING, "no_match")
                    else -> diag
                }
            }
        )
        val snap = StartupCacheSnapshot(HostIdentity(missingDiagCache.biliVersionCode, missingDiagCache.hostFingerprint), 0L, missingDiagCache)
        assertFalse("Stale HOME_FEED diagnostic must not block retry in cold start", snap.isDiagnosticAttempted(HomeRecommendEarlyLoadAdapter.DIAGNOSTIC))
        assertTrue("Fresh KMP_WIRE diagnostic remains attempted", snap.isDiagnosticAttempted(KmpGrpcWireAdapter.DIAGNOSTIC))
        assertFalse("Reset timestamp mismatch must invalidate diagnostic attempted state", snap.copy(resetTimestamp = missingDiagCache.ts + 1).isDiagnosticAttempted(KmpGrpcWireAdapter.DIAGNOSTIC))
        assertFalse("Fingerprint mismatch must invalidate diagnostic attempted state", snap.copy(identity = snap.identity.copy(fingerprint = "different")).isDiagnosticAttempted(KmpGrpcWireAdapter.DIAGNOSTIC))
        assertFalse("Version mismatch must invalidate diagnostic attempted state", snap.copy(identity = snap.identity.copy(versionCode = 0)).isDiagnosticAttempted(KmpGrpcWireAdapter.DIAGNOSTIC))

        val upgradeStale = setOf(AdaptDomain.KMP_WIRE)
        assertNotNull(full.kmpGrpcWireEngine)
        assertNull(full.withoutDomains(upgradeStale).kmpGrpcWireEngine)
        assertEquals(AdaptDecision.SUPPLEMENT_SILENTLY, VersionAdapter.adaptDecision(full, refreshRequested = false, coverageMissing = emptySet(), staleDomains = upgradeStale))
        assertEquals(AdaptDecision.SUPPLEMENT_SILENTLY, VersionAdapter.adaptDecision(full, false, emptySet(), setOf(AdaptDomain.HOME_FEED)))
        assertEquals(AdaptDecision.ADAPT, VersionAdapter.adaptDecision(full, true, emptySet(), setOf(AdaptDomain.HOME_FEED)))
        assertEquals(AdaptDecision.REUSE, VersionAdapter.adaptDecision(full, false, emptySet(), emptySet()))
        assertNotEquals(
            VersionAdapterContract.domainRulesSignature(),
            VersionAdapterContract.domainRulesSignature(
                VersionAdapterContract.DOMAIN_RULE_VERSIONS +
                    ("update" to (VersionAdapterContract.DOMAIN_RULE_VERSIONS.getValue("update") + 1))
            )
        )

        // 6. Incremental refresh DEX leak prevention
        val loader = CommentClassicStyleLocatorTest.loader(kotlin = true, hideNative = true, missingKotlin = true)
        val nativeMethod = CommentClassicStyleLocatorTest.Native::class.java.declaredMethods.first {
            CommentClassicStyleLocator.verified(it, CommentClassicStyleLocator.Family.NATIVE)
        }
        val kotlinMethod = CommentClassicStyleLocatorTest.KotlinReaders::class.java.declaredMethods.first {
            CommentClassicStyleLocator.verified(it, CommentClassicStyleLocator.Family.KOTLIN)
        }
        val cachedClassic = CommentClassicStylePoints(
            nativeReaders = listOf(HookPoint(nativeMethod.declaringClass.name, nativeMethod.name, nativeMethod.parameterTypes.map(Class<*>::getName))),
            kotlinReaders = listOf(HookPoint(kotlinMethod.declaringClass.name, kotlinMethod.name, kotlinMethod.parameterTypes.map(Class<*>::getName)))
        )
        var dexQueriesRun = 0
        val countingEngine = DexAssistEngine { _: DexAssistRequest ->
            dexQueriesRun++
            DexAssistResult.Unavailable(DexAssistResult.Reason.NO_MATCH)
        }
        val session = DexAssistSession(
            engine = countingEngine,
            codePaths = listOf("/base.apk"),
            classLoader = loader,
            planned = emptySet()
        )
        val located = CommentClassicStyleLocator.locate(
            loader = loader,
            session = session,
            enabled = true,
            previous = cachedClassic,
            planned = emptySet()
        )
        assertEquals(0, dexQueriesRun)
        assertEquals(cachedClassic.kotlinReaders, located.kotlinReaders)
        assertEquals(cachedClassic.nativeReaders, located.nativeReaders)

        val partialQueries = mutableListOf<DexAssistQuery>()
        val partialEngine = DexAssistEngine { req: DexAssistRequest ->
            partialQueries += req.query
            DexAssistResult.Unavailable(DexAssistResult.Reason.NO_MATCH)
        }
        val partialSession = DexAssistSession(
            engine = partialEngine,
            codePaths = listOf("/base.apk"),
            classLoader = loader,
            planned = setOf(CommentClassicStyleLocator.Family.KOTLIN.query)
        )
        val partialLocated = CommentClassicStyleLocator.locate(
            loader = loader,
            session = partialSession,
            enabled = true,
            previous = cachedClassic.copy(kotlinReaders = emptyList()),
            planned = setOf(CommentClassicStyleLocator.Family.KOTLIN)
        )
        assertEquals(listOf(CommentClassicStyleLocator.Family.KOTLIN.query), partialQueries)
        assertEquals(cachedClassic.nativeReaders, partialLocated.nativeReaders)

        val marker = tempFolder.newFile("dex_guard.pending")
        val baseKey = "fp-1|drv=${VersionAdapterContract.domainRulesSignature()}"
        marker.writeText(baseKey)
        var guardCalls = 0
        val guardEngine = DexAssistEngine { _: DexAssistRequest ->
            guardCalls++
            DexAssistResult.Unavailable(DexAssistResult.Reason.NO_MATCH)
        }
        val blockedSession = DexAssistSession(
            engine = DexAssistAttemptGuard(marker, baseKey, guardEngine),
            codePaths = listOf("/base.apk"),
            classLoader = loader,
            planned = setOf(DexAssistQuery.COMMENT_CLASSIC_KOTLIN)
        )
        assertEquals(
            DexAssistResult.Unavailable(DexAssistResult.Reason.PREVIOUS_ATTEMPT_UNFINISHED),
            blockedSession.result(DexAssistQuery.COMMENT_CLASSIC_KOTLIN)
        )
        assertEquals(0, guardCalls)
        assertTrue(marker.exists())

        val bumpedKey = "fp-1|drv=${VersionAdapterContract.domainRulesSignature(VersionAdapterContract.DOMAIN_RULE_VERSIONS + ("update" to (VersionAdapterContract.DOMAIN_RULE_VERSIONS.getValue("update") + 1)))}"
        assertNotEquals(baseKey, bumpedKey)
        val rotatedSession = DexAssistSession(
            engine = DexAssistAttemptGuard(marker, bumpedKey, guardEngine),
            codePaths = listOf("/base.apk"),
            classLoader = loader,
            planned = setOf(DexAssistQuery.COMMENT_CLASSIC_KOTLIN)
        )
        assertEquals(
            DexAssistResult.Unavailable(DexAssistResult.Reason.NO_MATCH),
            rotatedSession.result(DexAssistQuery.COMMENT_CLASSIC_KOTLIN)
        )
        assertEquals(1, guardCalls)
        assertFalse("Completed rotated attempt should clear pending marker", marker.exists())

        assertFalse(KotlinDefaultWordsLocator.refreshCache(enabled = true, applicable = true, directFound = false, cachedFound = true, attempted = false))
        assertFalse(KotlinDefaultWordsLocator.refreshCache(enabled = true, applicable = true, directFound = false, cachedFound = false, attempted = true))
        assertTrue(KotlinDefaultWordsLocator.refreshCache(enabled = true, applicable = true, directFound = false, cachedFound = false, attempted = false))
        assertFalse(SponsorPlayerLocator.refreshCache(enabled = true, applicable = true, directFound = false, cachedFound = true, attempted = false))
        assertFalse(SponsorPlayerLocator.refreshCache(enabled = true, applicable = true, directFound = false, cachedFound = false, attempted = true))

        // 7. Strip agreement across every assisted target
        data class TargetBinding(
            val query: DexAssistQuery,
            val diagnosticId: String,
            val checkCleared: (AdaptResult) -> Boolean
        )
        val targets = listOf(
            TargetBinding(DexAssistQuery.BLOCK_UPDATE, VersionAdapter.DEX_ASSIST_BLOCK_UPDATE_ID) { it.blockUpdate == null },
            TargetBinding(DexAssistQuery.PLAYER_DEFAULT_QUALITY, VersionAdapter.DEX_ASSIST_PLAYER_QUALITY_ID) { it.playerQuality == null },
            TargetBinding(DexAssistQuery.NORMAL_QUALITY_AUTO_SWITCH, NormalQualityLocator.DIAGNOSTIC) { it.playerQuality == null },
            TargetBinding(DexAssistQuery.SPONSOR_PLAYER_WRAPPER, SponsorPlayerLocator.DIAGNOSTIC) { it.sponsorPlayer == null },
            TargetBinding(DexAssistQuery.PLAYER_SPEED_MENU, PlayerSpeedMenuAdapter.DIAGNOSTIC) { it.playerSpeedMenu == null },
            TargetBinding(DexAssistQuery.PLAYER_SPEED_LONG_PRESS_CALCULATOR, PlayerSpeedLocator.CALCULATOR_DIAGNOSTIC) { it.playerSpeedCalculator == null },
            TargetBinding(DexAssistQuery.PLAYER_SPEED_THESEUS2_LONG_PRESS, PlayerSpeedLocator.THESEUS2_LONG_PRESS_DIAGNOSTIC) { it.playerSpeedTheseus2LongPress == null },
            TargetBinding(DexAssistQuery.PLAYER_POPUP_FREE_DATA_GUIDE, PlayerPopupPromotionAdapter.Family.FREE_DATA_GUIDE.diagnostic) { it.playerPopupPromotion == null },
            TargetBinding(DexAssistQuery.GUEST_PLAYER_CORE_SERVICE, GuestPlayerCoreAdapter.DIAGNOSTIC) { it.guestPlayerCore == null },
            TargetBinding(DexAssistQuery.PAUSED_AD_REQUEST_API, PausedAdDexAdapter.DIAGNOSTIC_REQUEST_API) { it.pause.apiEntry == null },
            TargetBinding(DexAssistQuery.LEGACY_STORY_DANMAKU_BIND, LegacyStoryDanmakuAdapter.DIAGNOSTIC) { it.legacyStoryDanmakuBind == null },
            TargetBinding(DexAssistQuery.COMMENT_CLASSIC_NATIVE, CommentClassicStyleLocator.DIAGNOSTIC) { it.commentClassicStyle == null },
            TargetBinding(DexAssistQuery.COMMENT_COMPOSE_MORE_MENU, CommentComposeMenuAdapter.DIAGNOSTIC) { it.commentComposeMenu == null },
            TargetBinding(DexAssistQuery.COMMENT_REPLY_BUTTON_NATIVE, CommentReplyButtonAdapter.DIAGNOSTIC) { it.commentReplyButtonOwners.isEmpty() },
            TargetBinding(DexAssistQuery.COMMENT_QUICK_REPLY_COLLECTOR, CommentQuickReplyAdapter.DIAGNOSTIC) { it.commentPurify == null },
            TargetBinding(DexAssistQuery.SEARCH_DEFAULT_WORDS_KOTLIN, KotlinDefaultWordsLocator.DIAGNOSTIC) { it.homeTopBar == null },
            TargetBinding(DexAssistQuery.HOME_RECOMMEND_EARLY_LOAD_LISTENER, HomeRecommendEarlyLoadAdapter.DIAGNOSTIC) { it.homeRecommendEarlyLoadListener == null },
            TargetBinding(DexAssistQuery.HOME_TAB_RESOURCE, HomeTabResourceAdapter.DIAGNOSTIC) { it.homeTabs == null },
            TargetBinding(DexAssistQuery.HOME_CHROME_TOP, HomeChromeAdapter.diagnostic(DexAssistQuery.HOME_CHROME_TOP)) { it.homeChrome.isEmpty() },
            TargetBinding(DexAssistQuery.HOME_CHROME_SEMANTICS, HomeChromeAdapter.SEMANTICS_DIAGNOSTIC) { it.homeChromeSemantics == null },
            TargetBinding(DexAssistQuery.DETAIL_SPECIAL_TAG_MAPPER, DetailSpecialTagAdapter.DIAGNOSTIC) { it.detailSpecialTagMapper == null },
            TargetBinding(DexAssistQuery.AI_PLAYLIST_DIRECTOR, AiPlaylistDirectorAdapter.DIAGNOSTIC) { it.aiPlaylistDirector == null },
            TargetBinding(DexAssistQuery.FOLLOW_UP_RECOMMEND_BODY, FollowUpRecommendAdapter.DIAGNOSTIC) { it.followUpRecommendBodies.isEmpty() },
            TargetBinding(DexAssistQuery.DETAIL_RELATE_GAME_COMPONENT, DetailRelateGameAdapter.DIAGNOSTIC) { it.detailRelateGameComponent == null },
            TargetBinding(DexAssistQuery.FOLLOW_FEED_STYLE, FollowFeedAdapter.DIAGNOSTIC) { it.followFeedStyle == null },
            TargetBinding(DexAssistQuery.PLAYER_END_PAGE_MERGER, PageBoundaryAdapter.diagnostic(DexAssistQuery.PLAYER_END_PAGE_MERGER)) { it.pageBoundaries == null },
            TargetBinding(DexAssistQuery.BRAND_SPLASH_DECODER, BrandSplashDecoderAdapter.DIAGNOSTIC) { it.brandSplashDecoder == null },
            TargetBinding(DexAssistQuery.BRAND_SPLASH_KNTR_DRAW, BrandSplashKntrAdapter.DIAGNOSTIC) { it.brandSplashKntrDraw == null },
            TargetBinding(DexAssistQuery.BRAND_SPLASH_KNTR_EXIT, BrandSplashKntrAdapter.DIAGNOSTIC_EXIT) { it.brandSplashKntrExit == null },
            TargetBinding(DexAssistQuery.BRAND_SPLASH_KNTR_PAGE, BrandSplashKntrAdapter.DIAGNOSTIC_PAGE) { it.brandSplashKntrPage == null },
            TargetBinding(DexAssistQuery.KMP_GRPC_WIRE_ENGINE, KmpGrpcWireAdapter.DIAGNOSTIC) { it.kmpGrpcWireEngine == null },
            TargetBinding(DexAssistQuery.SYSTEM_ENV_ROOT_RESULT, SystemEnvironmentAdapter.Family.ENVIRONMENT.diagnostic) { it.systemEnvironment == null },
            TargetBinding(DexAssistQuery.CLOUD_KEY_FREEZE_CONTRACT, CloudKeyFreezeAdapter.DIAGNOSTIC) { it.cloudKeyFreezeContract == null },
            TargetBinding(DexAssistQuery.PRIVACY_IGNET_HTTP, PrivacyChannelAdapter.DIAGNOSTIC_IGNET) { it.privacyIgnetHttp == null },
            TargetBinding(DexAssistQuery.PRIVACY_KNEURON, PrivacyChannelAdapter.DIAGNOSTIC_KNEURON) { it.privacyKNeuron == null },
            TargetBinding(DexAssistQuery.WECHAT_SHARE_WRAPPER_DISCOVERY, WeChatShareCompatAccess.DIAGNOSTIC) { it.weChatShare == null }
        )
        assertFalse(targets.any { it.checkCleared(full) })
        targets.forEach { target ->
            val domain = AdaptDomainCatalog.domainOf(target.query)
            val domainDiags = AdaptDomainCatalog.diagnosticIdsByDomain.getValue(domain)
            assertTrue("${target.query} diagnostic ${target.diagnosticId} must belong to $domain", target.diagnosticId in domainDiags)
            val stripped = full.withoutDomains(setOf(domain))
            assertTrue("${target.query} field must be cleared when $domain is stripped", target.checkCleared(stripped))
            assertFalse("${target.diagnosticId} must be removed when $domain is stripped", stripped.diagnostics.any { it.id == target.diagnosticId })
        }
    }

    private fun fullAll13DomainsResult(): AdaptResult {
        val base = sharedBaseResult
        val cov = AdaptCoverage(PointFamily.entries.associateWith { AdaptAssistGate.Attempt.FOUND })
        val reqTopologyKeys = CommentTopologyPoints.REQUIRED_METHOD_KEYS.associateWith {
            HookPoint("com.bapis.Fake", it, emptyList())
        }
        val diagnostics = buildList {
            AdaptDomain.entries.forEach { domain ->
                AdaptDomainCatalog.diagnosticIdsByDomain.getValue(domain).forEach { id ->
                    add(AdaptDiagnostic(id, AdaptState.FOUND, "ok:$id"))
                }
            }
            add(AdaptDiagnostic("protocol.structure", AdaptState.FOUND, base.protocolFingerprint))
        }
        return base.copy(
            mineEntry = MineEntryPoint(
                buildMethods = listOf(HookPoint("mine.Frag", "build", emptyList())),
                groupListField = "groups",
                adapterField = "adapter",
                clickMethod = HookPoint("mine.Frag", "onClick", emptyList())
            ),
            banner = BannerPoint(
                bannerClassName = "com.bilibili.pegasus.holders.bannerv8.V8Banner",
                lifecycleMethods = listOf(HookPoint("com.bilibili.app.comm.list.widget.swiper.SwiperBanner", "onAttachedToWindow", emptyList()))
            ),
            homeTopBar = base.homeTopBar?.copy(
                kotlinDefaultWords = HookPoint(KotlinDefaultWordsLocator.MOSS, "a", listOf("a", "b", "c", "d"))
            ),
            playerInteractiveOverlays = PlayerInteractiveOverlayPoints(
                families = listOf(
                    PlayerInteractiveGuideFamily(
                        replyClassName = "com.bapis.bilibili.app.view.v1.ViewProgressReply",
                        guideGetter = HookPoint("com.bapis.bilibili.app.view.v1.ViewProgressReply", "getVideoGuide", emptyList()),
                        guideClears = listOf(HookPoint("com.bapis.bilibili.app.view.v1.VideoGuide", "clearAttention", emptyList())),
                        guideDefault = HookPoint("com.bapis.bilibili.app.view.v1.VideoGuide", "getDefaultInstance", emptyList())
                    )
                ),
                mossExecutes = listOf(HookPoint("com.bapis.bilibili.app.view.v1.ViewMoss", "executeViewProgress", emptyList())),
                commandGetter = null,
                commandClear = HookPoint("com.bapis.FakeCmd", "clearCommand", emptyList()),
                commandDefault = HookPoint("com.bapis.FakeCmd", "getDefaultInstance", emptyList())
            ),
            pgcAutoActivityPopup = PgcAutoActivityPopupPoints(
                construct = HookPoint(
                    "com.bilibili.ship.theseus.ogv.activity.OgvActivityVo_JsonDescriptor",
                    "constructWith",
                    listOf("[Ljava.lang.Object;")
                ),
                constructorParameters = listOf("com.bilibili.ship.theseus.ogv.activity.OgvActivityHalfScreenPopup"),
                popupIndex = 0,
                propertiesField = "props"
            ),
            mineAccountMine = MineAccountMinePoints(
                buildMethods = listOf(HookPoint("mine.Frag", "onAccount", listOf("com.bapis.AccountMine"))),
                accountMineClass = "com.bapis.AccountMine",
                sectionListV2Field = "sectionListV2",
                groupClass = "com.bapis.MenuSection",
                groupTitleField = "title",
                groupItemListField = "items",
                itemClass = "com.bapis.MenuItem",
                itemTitleField = "title",
                itemIdField = null,
                itemUriField = null,
                itemVisibleField = null,
                itemLocalShowField = null,
                liveTipField = null,
                vipSectionRightField = null,
                sectionButtonField = null
            ),
            commentTopology = CommentTopologyPoints(
                mapperMethods = listOf(HookPoint("com.bilibili.app.comment3.data.source.v1.e", "a", emptyList())),
                replyMossClassName = "com.bapis.bilibili.main.community.reply.v1.ReplyMoss",
                methods = reqTopologyKeys
            ),
            playerQuality = base.playerQuality?.copy(
                story = StoryQualityPoints(
                    preferenceClassName = "${StoryQualityLocator.PREFERENCE_PACKAGE}.FakePref",
                    manualMethod = HookPoint(
                        StoryQualityLocator.SERVICE,
                        "setManual",
                        listOf("${StoryQualityLocator.PREFERENCE_PACKAGE}.FakePref", StoryQualityLocator.CONTINUATION)
                    ),
                    serviceFieldName = "storyQualityService",
                    ownerOnCreate = HookPoint(StoryQualityLocator.OWNER, "onCreate", listOf(StoryQualityLocator.LIFECYCLE_OWNER)),
                    ownerRelease = HookPoint(StoryQualityLocator.OWNER, "release", emptyList()),
                    ownerOnDestroy = HookPoint(StoryQualityLocator.OWNER, "onDestroy", listOf(StoryQualityLocator.LIFECYCLE_OWNER)),
                    policyMethod = HookPoint("${StoryQualityLocator.QUALITY_PACKAGE}.FakePolicy", "p", emptyList()),
                    resourceSelectionMethod = HookPoint(
                        "${StoryQualityLocator.QUALITY_PACKAGE}.FakePolicy",
                        "r",
                        listOf(StoryQualityLocator.MEDIA_RESOURCE)
                    ),
                    matchPreferenceMethod = HookPoint(StoryQualityLocator.SERVICE, "m", listOf(StoryQualityLocator.CONTINUATION))
                ),
                normalAutoSwitch = HookPoint("norm.Auto", "isAuto", emptyList())
            ),
            sponsorPlayer = SponsorPlayerClasses("com.bilibili.ship.theseus.Cont", "com.bilibili.ship.theseus.Wrap", "com.bilibili.ship.theseus.Scope", "com.bilibili.ship.theseus.Seek"),
            playerSpeedSession = PlayerSpeedSessionClasses("com.bilibili.ship.theseus.Owner", "com.bilibili.ship.theseus.Wrap"),
            playerSpeedCalculator = LongPressCalculatorPoint("calc.Owner", "calc", "calc.Playable"),
            weChatShare = WeChatShareClasses("wc.Legacy", "init", null, null, true),
            commentClassicStyle = CommentClassicStylePoints(
                nativeReaders = listOf(HookPoint("a", "b", listOf("java.lang.String", "boolean"))),
                kotlinReaders = listOf(HookPoint("c", "d", listOf("i", "java.lang.String", "boolean", "o")))
            ),
            playerPopupPromotion = PlayerPopupPromotionPoints(
                freeDataGuide = HookPoint("${PlayerPopupPromotionAdapter.NETWORK_PACKAGE}.Guide", "a", listOf("kotlin.coroutines.Continuation")),
                advertFloat = HookPoint("${PlayerPopupPromotionAdapter.MODEL_PACKAGE}.DmAdvert", "getFloatLayers", emptyList())
            ),
            brandSplashDecoder = HookPoint("b.Decoder", "decode", listOf(BrandSplashDecoderAdapter.INFO)),
            followFeedStyle = FollowFeedPoints("p.Owner", "draw", "p.Mod", "p.Item", "p.HomeDeco", "p.Mod"),
            playerSpeedMenu = PlayerSpeedMenuPoints(
                adapter = HookPoint("com.bilibili.playerbizcommonv2.widget.speed.FakeAdapter", "bind", listOf("float"))
            ),
            pageBoundaries = PageBoundaryPoints(
                searchDelivery = "com.bilibili.search2.discover.Deliv",
                searchState = "com.bilibili.search2.main.data.State",
                endPageMerger = HookPoint("${PlayerEndPageRecommendLocator.PACKAGE}M", "merge", listOf("java.util.List", "java.util.List"))
            ),
            homeChrome = mapOf("top" to HookPoint("tv.danmaku.bili.khome.widget.topbar.Top", "invoke", listOf("a", "b", "c", "d"))),
            homeChromeSemantics = HomeChromeSemanticsPoint("tv.danmaku.bili.khome.widget.SemanticsHolder", "key"),
            systemEnvironment = SystemEnvironmentPoints(
                environment = HookPoint("env.A", "b", listOf(SystemEnvironmentAdapter.ENV_PARAM_SET))
            ),
            commentComposeMenu = CommentComposeMenuPoints(bubbleRow = HookPoint("cmp.Bubble", "invoke", listOf("a", "b", "c", "d"))),
            kmpGrpcWireEngine = HookPoint("kmp.Wire", "exec", listOf("a", "b", "c", "d")),
            brandSplashKntrDraw = "kntr.srcs.app.splash.brand.startup.FakeLambda",
            brandSplashKntrExit = "kntr.srcs.app.splash.brand.startup.FakeExit",
            brandSplashKntrPage = HookPoint("kntr.srcs.app.splash.brand.startup.c", "a", listOf("m", "f", "mod", "f2", "comp", "int")),
            cloudKeyFreezeContract = "com.bilibili.lib.blconfig.internal.FakeContract",
            privacyIgnetHttp = HookPoint("com.bilibili.lib.ighttp.FakeReq", "exec", emptyList()),
            privacyKNeuron = "kntr.base.neuron.FakeKNeuron",
            guestPlayerCore = "lf6.p0",
            detailSpecialTagMapper = HookPoint(
                "com.bilibili.ship.theseus.united.page.intro.module.tags.f",
                "map",
                listOf(DetailUnitedPresentationPurifyPolicy.SPECIAL_TAG_CLASS)
            ),
            commentReplyButtonOwners = mapOf("legacy" to "com.bilibili.app.comment3.ui.holder.handle.Fake"),
            homeRecommendEarlyLoadListener = "com.bilibili.pegasus.FakeListener",
            aiPlaylistDirector = "com.bilibili.ship.theseus.playlist.FakeDirector",
            followUpRecommendBodies = mapOf("theseus2" to "com.bilibili.ship.theseus2.ugc.intro.uprecommend.FakeBody"),
            detailRelateGameComponent = "${DetailRelateGameAdapter.RELATE_GAME_PACKAGE}.FakeGame",
            legacyStoryDanmakuBind = HookPoint(LegacyStoryDanmakuAdapter.OWNER, "fakeBind", listOf("FakePlayer", "int")),
            playerSpeedTheseus2LongPress = Theseus2LongPressPoint(
                "${PlayerSpeedLocator.THESEUS2_SPEED_SERVICE}\$FakeLaunch",
                "${PlayerSpeedLocator.THESEUS2_SPEED_SERVICE}\$FakePort",
                "a"
            ),
            pause = base.pause.copy(
                apiEntry = HookPoint("kntr.app.ad.biz.videodetail.pausedpage.b", "c", emptyList())
            ),
            diagnostics = diagnostics,
            coverage = cov,
            domainRuleVersions = VersionAdapterContract.DOMAIN_RULE_VERSIONS
        )
    }
}
