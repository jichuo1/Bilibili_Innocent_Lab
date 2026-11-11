package com.Bilibili_Innocent_Lab.xposedmodule.hook

import android.content.Context
import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticCapabilityCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.*
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexMeguvizAssistEngine
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.*
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameInterpolationFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight.AiInsightFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPreset
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostVideoCardStyleSpec
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.SettingsActionCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.SettingsUiCatalog
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import java.lang.reflect.Constructor
import java.util.concurrent.atomic.AtomicInteger

/**
 * Explicit fixture-only installer inventory. Each registrar validates actual APK members and
 * records a plan, but NEVER invokes the supplied hook configuration or installs a runtime hook.
 * Partial/skipped plans remain visible; collection success is not runtime or visual acceptance.
 */
internal object HostFeaturePlanAudit {
    private data class Case(val name: String, val create: () -> FeatureInstaller)

    fun collect(context: Context, apk: File, loader: ClassLoader, direct: VersionAdapter.AdaptResult,
        report: JSONObject, file: File, hostVersionCode: Int) {
        // All optional features are explicitly requested by this manual fixture. One session
        // batches the existing production queries; no query or policy is added to host callbacks.
        val session = DexAssistSession(DexMeguvizAssistEngine, listOf(apk.path), loader, DexAssistQuery.entries.toSet())
        val pages = PageBoundaryAdapter.locate(loader, direct.pageBoundaries, session,
            PageBoundaryAdapter.planned(loader, direct.pageBoundaries, true, true, emptySet(), styleEnabled = true))
        val follow = FollowFeedAdapter.locate(loader, session, true, direct.followFeedStyle, true)
        val popup = PlayerPopupPromotionAdapter.locate(loader, session, true, direct.playerPopupPromotion,
            PlayerPopupPromotionAdapter.planned(loader, direct.playerPopupPromotion, emptySet(), true))
        val story = StoryQualityLocator.locate(loader, session, direct.playerQuality?.story,
            StoryQualityLocator.planned(loader, direct.playerQuality?.story, true, false))
        val points = direct.copy(
            pageBoundaries = pages,
            followFeedStyle = follow,
            playerPopupPromotion = popup,
            playerQuality = direct.playerQuality?.copy(story = story),
            commentClassicStyle = CommentClassicStyleLocator.locate(loader, session, true),
            brandSplashDecoder = BrandSplashDecoderAdapter.locate(loader, session, true, direct.brandSplashDecoder, true),
            playerSpeedMenu = PlayerSpeedMenuAdapter.locate(loader, session, direct.playerSpeedMenu, true, true),
            sponsorPlayer = (SponsorPlayerLocator.direct(loader) ?: SponsorPlayerLocator.assisted(loader, session))?.classes,
            homeChrome = HomeChromeAdapter.locate(loader, direct.homeChrome, session, HomeChromeAdapter.families.keys),
            homeChromeSemantics = HomeChromeAdapter.locateSemantics(loader, direct.homeChromeSemantics,
                session.result(DexAssistQuery.HOME_CHROME_SEMANTICS))
        )
        report.put("feature_plan_adaptation", points.toJson())
        val moduleApk = File(context.applicationInfo.sourceDir)
        val detailKeys = (DetailModulePurifyPolicy.preferenceKeys + DetailUnitedModulePurifyPolicy.preferenceKeys +
            DetailViewPurifyPolicy.preferenceKeys).toSet()
        val semanticRequests = AtomicInteger()
        val offlineSemantic = SemanticJudge(
            apiKey = "fixture",
            rules = listOf(SemanticRule("fixture", "离线审计：合成匹配规则")),
            endpoint = "https://fixture.invalid/",
            transport = { _, _, _ ->
                semanticRequests.incrementAndGet()
                error("The fixture must not send a semantic request")
            },
            background = { false }
        )
        val cases = arrayListOf<Case>()
        val sharedCards by lazy {
            HostVideoCardStyleFeatureInstaller.shared(true, HostVideoCardStyleSpec.DEFAULT_RADIUS,
                HostBackgroundConfig(preset = HostBackgroundPreset.CUSTOM), cardSurfaces = pages?.cardSurfaces.orEmpty())
        }
        val buffer by lazy {
            PlayerBufferLocator.resolve(loader)?.also {
                report.put("player_buffer_creator_contract", JSONObject()
                    .put("creator_members_expected", it.creatorCount)
                    .put("has_existing_item_overload", it.existingItemParameter != null)
                    .put("scope", "core-interface-only-until-runtime-instance"))
            }
        }
        fun add(name: String, create: () -> FeatureInstaller) { cases += Case(name, create) }
        add("AiDeclaredVideoFeatureInstaller") { AiDeclaredVideoFeatureInstaller(true, true) }
        add("AiInsightFeatureInstaller") {
            // Host structure only. A missing provider keeps the repository and its background work disabled.
            AiInsightFeatureInstaller(true, false, null, File(file.parentFile, "insight-fixture")) {
                points.sponsorPlayer
            }
        }
        add("BlockComponentLibraryFeatureInstaller") { BlockComponentLibraryFeatureInstaller(true) }
        add("BlockUpdateFeatureInstaller") { BlockUpdateFeatureInstaller(true, points.blockUpdate) }
        add("BottomBarFeatureInstaller") { BottomBarFeatureInstaller("会员购", points = points.bottomBar) }
        add("BrandSplashCustomFeatureInstaller") { BrandSplashCustomFeatureInstaller(true, context, decoderPoint = points.brandSplashDecoder) }
        add("BrandSplashSkipFeatureInstaller") { BrandSplashSkipFeatureInstaller(true) }
        add("BvToAvFeatureInstaller") { BvToAvFeatureInstaller(true) }
        add("CloudKeyFreezeFeatureInstaller") { CloudKeyFreezeFeatureInstaller(true, "fixture_ui_flag=true", false) }
        add("CommentClassicStyleFeatureInstaller") { CommentClassicStyleFeatureInstaller(true) { points.commentClassicStyle } }
        add("CommentFilterFeatureInstaller") {
            CommentFilterFeatureInstaller(true, "fixture", true, 3, true, true, "1", points.commentFilter,
                semanticJudge = offlineSemantic, fishFilter = FishScorePolicy.Config(enabled = true))
        }
        add("CommentPurifyFeatureInstaller") { CommentPurifyFeatureInstaller(true, true, true, true, true, true, true, points.commentPurify) }
        add("CommentReplyButtonFeatureInstaller") { CommentReplyButtonFeatureInstaller(true) }
        add("CommentSectionFeatureInstaller") { CommentSectionFeatureInstaller(true, points.commentSection) }
        add("CommentTopologyFeatureInstaller") { CommentTopologyFeatureInstaller(true, points.commentTopology, points.commentLow, points.commentHigh) }
        add("DanmakuPurifyFeatureInstaller") { DanmakuPurifyFeatureInstaller(true, 5, true, semanticJudge = offlineSemantic) }
        add("DetailAppPromotionFeatureInstaller") { DetailAppPromotionFeatureInstaller(true) }
        add("DetailModulePurifyFeatureInstaller") { DetailModulePurifyFeatureInstaller(detailKeys) }
        add("DetailUnitedModulePurifyFeatureInstaller") { DetailUnitedModulePurifyFeatureInstaller(detailKeys, true, true) }
        add("DetailUnitedPresentationPurifyFeatureInstaller") { DetailUnitedPresentationPurifyFeatureInstaller(true, true) }
        add("DetailViewPurifyFeatureInstaller") { DetailViewPurifyFeatureInstaller(detailKeys) }
        add("DynamicPurifyFeatureInstaller") {
            DynamicPurifyFeatureInstaller(true, "fixture", true, "1", true, true, true, true,
                semanticJudge = offlineSemantic)
        }
        add("DynamicPurifyFeatureInstaller:whole_up_list") { DynamicPurifyFeatureInstaller(false, "", false, "", false, false, false, false, true) }
        add("DynamicPurifyFeatureInstaller:topic_only") {
            DynamicPurifyFeatureInstaller(false, "", false, "", false, false, true, false)
        }
        add("DynamicTabsFeatureInstaller") { DynamicTabsFeatureInstaller(true, true, true, points.dynamicTabs) }
        add("ExternalBrowserFeatureInstaller") { ExternalBrowserFeatureInstaller(true) }
        add("FollowFeedFeatureInstaller") { FollowFeedFeatureInstaller(true, points.followFeedStyle) }
        add("FrameInterpolationFeatureInstaller") {
            FrameInterpolationFeatureInstaller(true, false, moduleApk, maxFps = 90, statusToast = true)
        }
        add("FullNumberFeatureInstaller") { FullNumberFeatureInstaller(true, points.fullNumbers) }
        add("GamePromotionFeatureInstaller") { GamePromotionFeatureInstaller(true) }
        add("HomeBannerFeatureInstaller") { HomeBannerFeatureInstaller(true, points.banner) { false } }
        add("HomeComponentFilterFeatureInstaller") { HomeComponentFilterFeatureInstaller("游戏", points = points.homeComponents) }
        add("HomeFrameLockFeatureInstaller") { HomeFrameLockFeatureInstaller(true) }
        add("HomeRecommendEarlyLoadFeatureInstaller") { HomeRecommendEarlyLoadFeatureInstaller(true) }
        add("HomeRecommendPurifyFeatureInstaller") {
            HomeRecommendPurifyFeatureInstaller(true, true, true, true, true, true, "fixture", true, true, true,
                true, 1, 3600, points.homeRecommendFeed, removePgc = true, removeSpecialCards = true,
                removeComic = true, rawBlockedTids = "1", rawBlockedAuthors = "1", sectionPickEnabled = true,
                minPlayCount = 1, maxPlayCount = 1000000, removeAiDeclared = true, aiDeclaredStrongMode = true,
                rawPublishTimeRule = "v1|after|r:2d", semanticJudge = offlineSemantic)
        }
        add("HomeTabFilterFeatureInstaller") { HomeTabFilterFeatureInstaller("热门", points = points.homeTabs) }
        add("HomeTopBarFeatureInstaller") { HomeTopBarFeatureInstaller(true, true, points.homeTopBar) }
        add("HomeVerticalDetailFeatureInstaller") { HomeVerticalDetailFeatureInstaller(true, points.homeRecommendFeed) }
        add("HostBottomBarFxFeatureInstaller") {
            HostBottomBarFxFeatureInstaller(true, true, points.bottomBar, compact = true, iconOnly = true,
                chromePoints = points.homeChrome, semanticsPoint = points.homeChromeSemantics)
        }
        add("HostTopBarFxFeatureInstaller") { HostTopBarFxFeatureInstaller(true, true, points.homeTopBar, chromePoints = points.homeChrome) }
        add("HostTouchGlowFeatureInstaller") { HostTouchGlowFeatureInstaller(true) }
        add("HostVideoCardStyleFeatureInstaller") { sharedCards[0] }
        add("HostVideoCardStyleFeatureInstaller:background") { sharedCards[1] }
        add("LiveHomePromotionFeatureInstaller") { LiveHomePromotionFeatureInstaller(true) }
        add("LiveRoomWidgetFeatureInstaller") { LiveRoomWidgetFeatureInstaller(true, true) }
        add("MerchandiseFeatureInstaller") { MerchandiseFeatureInstaller(true) }
        add("MineComponentFilterFeatureInstaller") { MineComponentFilterFeatureInstaller("大会员", listOf("1"), "", points.mineComponents, points.mineAccountMine) }
        add("MineVipFeatureInstaller") { MineVipFeatureInstaller(true, false, points.mineVip) }
        add("MineVipFeatureInstaller:keep_space") { MineVipFeatureInstaller(true, true, points.mineVip) }
        add("PausedAdFeatureInstaller") { PausedAdFeatureInstaller(true, points.pause, hostVersionCode) }
        add("PgcAutoActivityPopupFeatureInstaller") { PgcAutoActivityPopupFeatureInstaller(true, points.pgcAutoActivityPopup) }
        add("PlayerAiSubtitleFeatureInstaller") { PlayerAiSubtitleFeatureInstaller(true) }
        add("PlayerBufferFeatureInstaller") { PlayerBufferFeatureInstaller(1, 60, 256, 512, false) { buffer } }
        add("PlayerBufferFeatureInstaller:whole") { PlayerBufferFeatureInstaller(3, 60, 256, 512, false) { buffer } }
        add("PlayerCapabilityFeatureInstaller") { PlayerCapabilityFeatureInstaller(PlayerCapabilityOptions(true, true, true)) }
        add("PlayerCodecForceFeatureInstaller") { PlayerCodecForceFeatureInstaller(1, 1) }
        add("PlayerEndPageRecommendFeatureInstaller") { PlayerEndPageRecommendFeatureInstaller(true, { pages?.endPageMerger }) { PlayerEndPageRecommendLocator.resolve(it, pages?.endPageMerger) } }
        add("PlayerInteractiveOverlayFeatureInstaller") { PlayerInteractiveOverlayFeatureInstaller(true, points.playerInteractiveOverlays) }
        add("PlayerPopupPromotionFeatureInstaller") { PlayerPopupPromotionFeatureInstaller(true) { points.playerPopupPromotion } }
        add("PlayerPortraitFeatureInstaller") { PlayerPortraitFeatureInstaller(true, points.playerPortrait) }
        add("PlayerQualityFeatureInstaller") { PlayerQualityFeatureInstaller(80, points.playerQuality) }
        add("PlayerSpeedFeatureInstaller") {
            PlayerSpeedFeatureInstaller(false, 300, 125, cachedMenuPoints = points.playerSpeedMenu,
                hostVersionCode = hostVersionCode)
        }
        add("PlayerSpeedFeatureInstaller:disable_press") {
            PlayerSpeedFeatureInstaller(true, 300, 125, cachedMenuPoints = points.playerSpeedMenu,
                hostVersionCode = hostVersionCode)
        }
        add("PlayerSpeedFeatureInstaller:custom_menu") {
            // Menu customization is inactive when an explicit default speed is configured.
            PlayerSpeedFeatureInstaller(false, 0, 0, speedMenuSpec = "75,100,125,150,200",
                cachedMenuPoints = points.playerSpeedMenu, hostVersionCode = hostVersionCode)
        }
        add("PlayerStatusBarFeatureInstaller") { PlayerStatusBarFeatureInstaller(true, points.playerStatusBar) }
        add("PlayerSubtitleStyleFeatureInstaller") { PlayerSubtitleStyleFeatureInstaller(true, moduleApk) }
        add("SearchHomeRecommendFeatureInstaller") { SearchHomeRecommendFeatureInstaller(true) { pages } }
        add("SearchPurifyFeatureInstaller") { SearchPurifyFeatureInstaller(true, true, "fixture", true, "1") }
        add("SectionPickFeatureInstaller") { SectionPickFeatureInstaller(true, points.homeRecommendFeed, points.videoRelate) }
        add("SharePurifyFeatureInstaller") { SharePurifyFeatureInstaller(true, true) }
        add("SplashAdFeatureInstaller") { SplashAdFeatureInstaller(true, points.splashAds) }
        add("SplashAutoNightFeatureInstaller") { SplashAutoNightFeatureInstaller(true) }
        add("SponsorBlockFeatureInstaller") { SponsorBlockFeatureInstaller(true, true) { points.sponsorPlayer } }
        add("SponsorBlockStoryFeatureInstaller") {
            SponsorBlockStoryFeatureInstaller(true, true) {
                com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor.SponsorSegmentRepository(
                    com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor.SponsorBlockClient("audit"))
            }
        }
        add("StoryActionIconsFeatureInstaller") { StoryActionIconsFeatureInstaller(StoryActionIcon.entries.toSet()) }
        add("StoryPurifyFeatureInstaller") { StoryPurifyFeatureInstaller(true, true, true, true, true, true, true, true, true, true, true, true, points.storyFeed) }
        add("SystemMediaNotificationFeatureInstaller") { SystemMediaNotificationFeatureInstaller(true) }
        add("TeenagersModeFeatureInstaller") { TeenagersModeFeatureInstaller(true, points.teenagersMode) }
        add("VideoRelateFilterFeatureInstaller") { VideoRelateFilterFeatureInstaller(setOf("CM", "GAME", "LIVE", "COURSE", "SPECIAL"), 1, 3600,
            matchingEnhancementEnabled = true, strongModeEnabled = true, reasonFilterEnabled = true,
            rawReasonKeywords = "fixture", points = points.videoRelate, rawBlockedAuthors = "1",
            rawBlockedTags = "fixture", sectionPickEnabled = true, rawPickedTagIds = "1", minPlayCount = 1, maxPlayCount = 1000000,
            rawPublishTimeRule = "v1|after|r:2d", semanticJudge = offlineSemantic) }

        val rows = JSONArray()
        report.put("feature_plans", rows).put("feature_plan_scope", "actual-apk-members-only-no-hooks-installed")
        val types = cases.map { it.name.substringBefore(':') }.toSet()
        assertEquals("Update the explicit inventory when production gains an installer", 72, types.size)
        for (case in cases) {
            val registry = HookPointRegistry(loader)
            val registrar = RecordingRegistrar(registry)
            val leaves = JSONArray()
            val logs = JSONArray()
            val environment = HookEnvironment("tv.danmaku.bili", loader, registry, registrar,
                logInfo = { key, _ -> logs.put(key) }, logError = { key, _ -> logs.put(key) },
                reportStatus = { key, value -> logs.put("$key=$value") },
                // An explicit in-memory sink models the production bridge without touching
                // the user's snapshots or using IPC. Hook callback bodies still never run.
                writeScanSnapshot = { _, _ -> true },
                installationEvidence = { record -> leaves.put(JSONObject().put("id", record.id)
                    .put("result", outcome(record.result)).put("error", record.failure?.javaClass?.name)) })
            val row = JSONObject().put("case", case.name)
            runCatching {
                val installer = case.create()
                row.put("id", installer.id).put("requested_capabilities", JSONArray(installer.capabilityIds))
                val record = FeatureInstallCoordinator(environment).installAll(listOf(installer)).single()
                row.put("result", outcome(record.result)).put("error", record.failure?.javaClass?.name)
            }.onFailure { row.put("error", it.javaClass.name) }
            row.put("members", JSONArray(registrar.members)).put("leaves", leaves).put("logs", logs)
            rows.put(row)
            file.writeText(report.toString(2))
        }
        // The inventory is deliberately separate from successful coverage: unresolved/partial
        // members must be reviewed, never silently dropped to make a compatibility claim pass.
        report.put("feature_plan_installer_types", types.size)
            .put("feature_plan_all_complete", (0 until rows.length()).all { index ->
                val row = rows.getJSONObject(index)
                row.optString("error").isEmpty() && row.optJSONObject("result")?.optBoolean("complete") == true
            })
        val directChecks = HostDirectFeatureAudit.collect(loader, direct, hostVersionCode)
        report.put("direct_member_contracts", directChecks)
        appendCapabilityInventory(report, rows, directChecks)
        report.put("semantic_transport_requests", semanticRequests.get())
        file.writeText(report.toString(2))
        assertEquals("No installer may disappear from the collected denominator", cases.size, rows.length())
        assertEquals("The offline installation audit must not send semantic requests", 0, semanticRequests.get())
        val unresolved = directChecks.keys().asSequence()
            .filterNot { directChecks.getJSONObject(it).getBoolean("resolved") }.toList()
        assertTrue("Unresolved direct or deferred member contracts: $unresolved", unresolved.isEmpty())
    }

    private fun appendCapabilityInventory(report: JSONObject, rows: JSONArray, directChecks: JSONObject) {
        val observations = linkedMapOf<String, MutableList<JSONObject>>()
        fun record(id: String, name: String, result: JSONObject?) {
            if (id.isBlank()) return
            observations.getOrPut(id) { mutableListOf() } += JSONObject()
                .put("case", name).put("result", result ?: JSONObject().put("state", "not_reported"))
        }
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val name = row.getString("case")
            val recorded = mutableSetOf<String>()
            val leaves = row.getJSONArray("leaves")
            for (leafIndex in 0 until leaves.length()) {
                val leaf = leaves.getJSONObject(leafIndex)
                val id = leaf.getString("id")
                record(id, name, leaf.optJSONObject("result"))
                recorded += id
            }
            val id = row.optString("id")
            if (id !in recorded) record(id, name, row.optJSONObject("result"))
            val requested = row.optJSONArray("requested_capabilities") ?: continue
            for (requestIndex in 0 until requested.length()) {
                val requestedId = requested.getString(requestIndex)
                if (requestedId != id && requestedId !in recorded) record(requestedId, name, null)
            }
        }
        val definitions = DiagnosticCapabilityCatalog.definitions
        val bySetting = mutableMapOf<String, MutableList<String>>()
        val frameOptions = setOf("player_frame_interpolation_max_fps", "player_frame_interpolation_status_toast",
            "player_frame_interpolation_thermal_control")
        val liveOnly = mapOf(
            "guest_browsing" to "live host admission and guest state",
            "incognito_privacy" to "live host admission and privacy state",
            "roaming_compat" to "separate module and live admission",
            "wechat_share_compat" to "existing signature-compatibility profile"
        )
        val capabilities = definitions.map { definition ->
            definition.settingIds.forEach { setting ->
                bySetting.getOrPut(setting) { mutableListOf() } += definition.id
            }
            val results = observations[definition.id].orEmpty()
            val direct = directChecks.optJSONObject(definition.id)
            JSONObject().put("id", definition.id)
                .put("parent", definition.parentId)
                .put("status", when {
                    results.isNotEmpty() -> "observed"
                    direct != null -> "direct_member_contract"
                    definition.id in frameOptions -> "runtime_configuration"
                    definition.id in liveOnly -> "requires_separate_environment"
                    else -> "no_art_observation"
                })
                .put("direct_member_contract", direct)
                .put("related_installation_case",
                    "FrameInterpolationFeatureInstaller".takeIf { definition.id in frameOptions })
                .put("environment_requirement", liveOnly[definition.id])
                .put("observations", JSONArray(results))
        }
        val searchable = (SettingsUiCatalog.descriptors() + SettingsActionCatalog.descriptors())
            .mapTo(hashSetOf()) { it.id }
        val settings = (SettingsCatalog.byId.keys + searchable).sorted().map { id ->
            JSONObject().put("id", id)
                .put("in_backup_catalog", id in SettingsCatalog.byId)
                .put("in_ui_catalog", id in searchable)
                .put("catalog_capabilities", JSONArray(bySetting[id].orEmpty().sorted()))
        }
        report.put("capability_inventory", JSONArray(capabilities))
            .put("settings_inventory", JSONArray(settings))
            .put("installer_inventory_without_art_case", JSONObject()
                .put("FunctionalFeatureInstaller", "delegating wrapper; inspect each reported capability")
                .put("GuestBrowsingFeatureInstaller", "live host admission and account state required")
                .put("WeChatShareCompatFeatureInstaller", "existing signature-compatibility scope"))
    }

    private fun outcome(result: FeatureInstallResult?): JSONObject = when (result) {
        is FeatureInstallResult.Installed -> JSONObject().put("state", "members_resolved")
            .put("complete", result.complete).put("planned_hooks", result.hookCount)
        is FeatureInstallResult.Skipped -> JSONObject().put("state", "skipped").put("complete", false).put("reason", result.reason)
        else -> JSONObject().put("state", "unverified").put("complete", false)
    }

    private class RecordingRegistrar(private val registry: HookPointRegistry) : HookRegistrar {
        val members = linkedSetOf<String>()
        override fun first(id: String, className: String, methodName: String, block: ModernMemberHookCreator.() -> Unit) {
            members += requireNotNull(registry.resolveFirst(id, className, methodName)).toGenericString()
        }
        override fun all(id: String, className: String, methodName: String, block: ModernMemberHookCreator.() -> Unit) {
            val resolved = registry.resolveAll(id, className, methodName)
            check(resolved.isNotEmpty())
            members += resolved.map { it.toGenericString() }
        }
        override fun exact(id: String, owner: Class<*>, methodName: String, vararg parameterTypes: Class<*>, block: ModernMemberHookCreator.() -> Unit) {
            members += requireNotNull(registry.resolveExact(id, owner, methodName, *parameterTypes)).toGenericString()
        }
        override fun adapted(id: String, point: VersionAdapter.HookPoint, exceptionPolicy: HookExceptionPolicy, block: ModernMemberHookCreator.() -> Unit) {
            members += requireNotNull(registry.resolveAdapted(id, point.className, point.methodName, point.paramClassNames)).toGenericString()
        }
        override fun constructor(id: String, constructor: Constructor<*>, block: ModernMemberHookCreator.() -> Unit) {
            members += constructor.toGenericString()
        }
    }
}
