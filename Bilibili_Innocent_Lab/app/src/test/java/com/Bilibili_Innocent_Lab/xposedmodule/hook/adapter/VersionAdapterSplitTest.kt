package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
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
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.StoryFeedPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.TeenagersModePoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.VideoRelatePoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapterContract
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptAssistGate.Attempt
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.locators.MediaQualityAndPlayerLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.DetailUnitedPresentationPurifyPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FollowUpRecommendFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome.HomeChromeSemanticsPoint
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigContract
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 覆盖 [VersionAdapter] 拆分重构后的核心模块契约与等价性：
 * 1. [AdaptCacheStore.clearCache] 清理与严格阻断 `VersionAdapter.<clinit>`；
 * 2. [AdaptResult.diagnosticsById] O(1) 惰性索引；
 * 3. [AdaptResultCodec] 68 键 JSON 往返与 [AdaptResultValidator] fail-closed 约束；
 * 4. [AdaptResultMerger] 决策、点合并、quick-locate 保留与域失效清理；
 * 5. [ProtocolFingerprintBuilder]、[DexFeatureRegistry] 与域定位门面。
 */
class VersionAdapterSplitTest {

    @Test
    fun clearCacheIsolationDoesNotLoadOrInitVersionAdapter() {
        val cacheDir = Files.createTempDirectory("bil-split-clear-isolated").toFile()
        try {
            val marker = File(cacheDir, AdaptCacheStore.DEX_ASSIST_MARKER_FILE_NAME).apply {
                writeText("pending")
            }
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
                    method.parameterTypes.contentEquals(
                        arrayOf(Context::class.java, SharedPreferences::class.java)
                    )
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
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun versionAdapterClearCacheDelegatesToStoreAndDeletesMarker() {
        val cacheDir = Files.createTempDirectory("bil-split-clear-facade").toFile()
        try {
            val marker = File(cacheDir, AdaptCacheStore.DEX_ASSIST_MARKER_FILE_NAME).apply {
                writeText("pending")
            }
            val modulePrefs = InMemorySharedPreferences()
            val adapterPrefs = InMemorySharedPreferences(
                mapOf(
                    AdaptCacheStore.KEY_ADAPTED_VERSION to 9200000,
                    AdaptCacheStore.KEY_ADAPT_RESULT to "{}"
                )
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
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun diagnosticsByIdReturnsEmptyAndMatchesLastDiagnosticPerId() {
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

    @Test
    fun codecFull68KeyJsonRoundTripMatchesOriginalResult() {
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

    @Test
    fun codecAndValidatorFailClosedOnMalformedInputs() {
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

    @Test
    fun mergerAdaptDecisionAndMergeRuntimeWithCachedPreserveParity() {
        val base = minimalValidResult()
        assertEquals(AdaptDecision.ADAPT, AdaptResultMerger.adaptDecision(null, false, emptySet()))
        assertEquals(AdaptDecision.ADAPT, AdaptResultMerger.adaptDecision(base, true, emptySet()))
        assertEquals(
            AdaptDecision.SUPPLEMENT_SILENTLY,
            AdaptResultMerger.adaptDecision(base, false, setOf(PointFamily.BANNER))
        )
        assertEquals(
            AdaptDecision.SUPPLEMENT_SILENTLY,
            AdaptResultMerger.adaptDecision(base, false, emptySet(), setOf(AdaptDomain.COMMENT))
        )
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

        // Domain rule version drift strips stale domains before merging.
        val staleCached = cached.copy(
            domainRuleVersions = VersionAdapterContract.DOMAIN_RULE_VERSIONS.toMutableMap().apply {
                put(AdaptDomain.UPDATE.key, (this[AdaptDomain.UPDATE.key] ?: 0) + 1)
            }
        )
        val mergedWithStale = requireNotNull(VersionAdapter.mergeRuntimeWithCached(runtime.copy(blockUpdate = null), staleCached))
        assertNull(mergedWithStale.blockUpdate)
        assertNotNull(mergedWithStale.videoRelate)
    }

    @Test
    fun protocolFingerprintBuilderRoundTripAndPlanDiagnostic() {
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

        // Split order invariance.
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

    @Test
    fun dexFeatureRegistryInvariantsAndLocatorFacadesOperateDeterministically() {
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

        // Facade delegation smoke checks against empty classloader (fail-closed -> null).
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

    @Suppress("unused")
    private class ProtocolMethodFixture {
        companion object {
            const val ITEMS_FIELD_NUMBER = 1
        }
        fun getItems(): List<String> = emptyList()
        fun getHolderType(): String = "av"
        fun getUri(): String = "bilibili://video/1"
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
        val pageSurface = HostCardSemanticAdapter.families.values.first()
        val storyPrefClass = "${StoryQualityLocator.PREFERENCE_PACKAGE}.TestPref"
        val coverage = AdaptCoverage(
            mapOf(
                PointFamily.COMMENT_LOW to Attempt.FOUND,
                PointFamily.COMMENT_HIGH to Attempt.FOUND,
                PointFamily.MINE_ENTRY to Attempt.FOUND,
                PointFamily.PAUSE to Attempt.FOUND,
                PointFamily.BANNER to Attempt.FOUND,
                PointFamily.HOME_TOP_BAR to Attempt.FOUND,
                PointFamily.MINE_VIP to Attempt.FOUND,
                PointFamily.BLOCK_UPDATE to Attempt.FOUND,
                PointFamily.DYNAMIC_TABS to Attempt.FOUND,
                PointFamily.FULL_NUMBERS to Attempt.FOUND,
                PointFamily.PLAYER_PORTRAIT to Attempt.FOUND,
                PointFamily.PLAYER_INTERACTIVE_OVERLAYS to Attempt.FOUND,
                PointFamily.PGC_AUTO_ACTIVITY_POPUP to Attempt.FOUND,
                PointFamily.PLAYER_STATUS_BAR to Attempt.FOUND,
                PointFamily.HOME_RECOMMEND_FEED to Attempt.FOUND,
                PointFamily.VIDEO_RELATE to Attempt.FOUND,
                PointFamily.HOME_TABS to Attempt.FOUND,
                PointFamily.HOME_COMPONENTS to Attempt.FOUND,
                PointFamily.MINE_COMPONENTS to Attempt.FOUND,
                PointFamily.MINE_ACCOUNT_MINE to Attempt.FOUND,
                PointFamily.STORY_FEED to Attempt.FOUND,
                PointFamily.BOTTOM_BAR to Attempt.FOUND,
                PointFamily.PLAYER_QUALITY to Attempt.FOUND,
                PointFamily.TEENAGERS_MODE to Attempt.FOUND,
                PointFamily.COMMENT_PURIFY to Attempt.FOUND,
                PointFamily.COMMENT_FILTER to Attempt.FOUND,
                PointFamily.COMMENT_TOPOLOGY to Attempt.FOUND,
                PointFamily.COMMENT_SECTION to Attempt.FOUND,
                PointFamily.SPLASH_ADS to Attempt.FOUND
            )
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
                construct = HookPoint(
                    "com.bilibili.ship.theseus.ogv.activity.OgvActivityVo_JsonDescriptor",
                    "constructWith",
                    listOf("[Ljava.lang.Object;")
                ),
                constructorParameters = listOf(
                    "a",
                    "b",
                    "com.bilibili.ship.theseus.ogv.activity.OgvActivityHalfScreenPopup"
                ),
                popupIndex = 2,
                propertiesField = "props"
            ),
            playerStatusBar = PlayerStatusBarPoints(listOf(HookPoint("com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity", "onCreate", listOf("android.os.Bundle")))),
            homeRecommendFeed = HomeRecommendFeedPoints(
                responseItemGetters = listOf(HookPoint("com.bilibili.PegasusResponse", "getItems", emptyList())),
                holderTypeGetter = HookPoint("com.bilibili.PegasusHolderData", "getHolderType", emptyList()),
                bizTypeGetter = null,
                adInfoGetter = null,
                cardTypeGetter = HookPoint("com.bilibili.BasePegasusData", "getCardType", emptyList()),
                cardGotoGetter = HookPoint("com.bilibili.BasePegasusData", "getCardGoto", emptyList()),
                goToGetter = HookPoint("com.bilibili.BasePegasusData", "getGoTo", emptyList()),
                uriGetter = HookPoint("com.bilibili.BasePegasusData", "getUri", emptyList()),
                paramGetter = HookPoint("com.bilibili.BasePegasusData", "getParam", emptyList()),
                titleGetter = HookPoint("com.bilibili.BasePegasusData", "getTitle", emptyList()),
                subtitleGetter = null,
                descGetter = null,
                playerArgsGetter = HookPoint("com.bilibili.BasePegasusData", "getPlayerArgs", emptyList()),
                playerArgsDurationField = "duration"
            ),
            videoRelate = VideoRelatePoints(
                responseItemGetters = listOf(HookPoint("com.bapis.Relates", "getCardsList", emptyList())),
                cardCaseGetters = listOf(HookPoint("com.bapis.RelateCard", "getCardCase", emptyList())),
                gotoGetters = emptyList(),
                cardTypeGetters = emptyList(),
                relateCardTypeGetters = emptyList(),
                fromSourceTypeGetters = emptyList(),
                fromSourceTypeChains = listOf(SourceTypeMethodChain(HookPoint("c.Card", "getBasicInfo", emptyList()), HookPoint("c.Basic", "getFromSourceType", emptyList()))),
                relateCardTypeValueGetters = emptyList(),
                directDurationGetters = emptyList(),
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
                idField = "id",
                titleField = "title",
                uriField = "uri",
                reporterIdField = "reporterId"
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
                groupTitleField = "title",
                groupItemListField = "itemList",
                itemClass = "com.bilibili.lib.homepage.mine.MenuGroup\$Item",
                itemTitleField = "title",
                itemIdField = "id",
                itemUriField = "uri",
                itemVisibleField = "visible",
                itemLocalShowField = "localShow",
                liveTipField = "liveTip",
                vipSectionRightField = "vipSectionRight",
                sectionButtonField = "button"
            ),
            storyFeed = StoryFeedPoints(
                responseItemGetters = listOf(HookPoint("com.bilibili.video.story.api.StoryFeedResponse", "getItems", emptyList())),
                pagerListMethods = emptyList(),
                adGetter = HookPoint("com.bilibili.video.story.StoryDetail", "isAd", emptyList()),
                liveGetter = null,
                gameGetter = null,
                bangumiGetter = null,
                courseGetter = null,
                musicGetter = null,
                cartInfoGetter = null,
                dramaPromptGetter = null,
                seasonInfoGetter = null,
                seasonTypeGetter = null
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
            sponsorPlayer = SponsorPlayerClasses("com.bilibili.ship.theseus.Run", "com.bilibili.ship.theseus.Wrap", null, "com.bilibili.ship.theseus.Seek"),
            playerSpeedSession = PlayerSpeedSessionClasses("com.bilibili.SpeedSession", null),
            playerSpeedCalculator = LongPressCalculatorPoint("com.bilibili.Calc", "calc", "com.bilibili.Playable"),
            weChatShare = WeChatShareClasses("com.bilibili.wx.Legacy", "init", "com.bilibili.wx.Modern", "init", false),
            commentClassicStyle = CommentClassicStylePoints(
                listOf(HookPoint("com.bilibili.lib.dd.DeviceDecision", "getBoolean", listOf("java.lang.String", "boolean"))),
                listOf(HookPoint("kntr.base.dd.IDeviceDecisionKt", "getBool", listOf("kntr.base.dd.IDeviceDecision", "java.lang.String", "boolean", "kntr.base.dd.IDeviceDecision\$Context")))
            ),
            playerPopupPromotion = PlayerPopupPromotionPoints(
                advertFloat = HookPoint("com.bilibili.adcommon.data.model.DmAdvert", "getFloatLayers", emptyList())
            ),
            brandSplashDecoder = HookPoint("Nr1.a", "decode", listOf(BrandSplashDecoderAdapter.INFO)),
            followFeedStyle = FollowFeedPoints("iU.d", "f", "tS.G", "tS.H", "com.bilibili.bplus.followinglist.home.Q", "current"),
            playerSpeedMenu = PlayerSpeedMenuPoints(adapter = HookPoint("${PlayerSpeedLocator.SPEED_WIDGET_PKG}.MenuAdapter", "populate", listOf("float"))),
            pageBoundaries = PageBoundaryPoints(
                cardSurfaces = mapOf(pageSurface to HookPoint("com.bilibili.TestCard", "bind", listOf("a", "b", "c")))
            ),
            homeChrome = mapOf(chromeFamily to HookPoint("$chromePkg.HomeChrome", "invoke", listOf("a", "b", "c", "d"))),
            homeChromeSemantics = HomeChromeSemanticsPoint("tv.danmaku.bili.khome.SemanticsHolder", "KEY"),
            systemEnvironment = SystemEnvironmentPoints(
                environment = HookPoint("com.bilibili.Env", "check", listOf(SystemEnvironmentAdapter.ENV_PARAM_SET))
            ),
            commentComposeMenu = CommentComposeMenuPoints(
                bubbleRow = HookPoint("com.bilibili.ComposeMenu", "bubble", listOf("a", "b", "c", "d"))
            ),
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
            commentReplyButtonOwners = mapOf("legacy" to "${CommentReplyButtonAdapter.LEGACY_PACKAGES["legacy"]}.ButtonHandler"),
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
