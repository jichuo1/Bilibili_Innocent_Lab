package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import FlatPhase3BadPlaylistDirector
import FlatPhase3FollowUpBodyTheseus
import FlatPhase3FollowUpBodyTheseus2
import FlatPhase3FollowUpBodyTheseus2Dup
import FlatPhase3LongPressLaunch
import FlatPhase3LongPressLaunch2
import FlatPhase3LongPressPort
import FlatPhase3LongPressPort2
import FlatPhase3PlaylistDirector
import FlatPhase3PlaylistDirector2
import FlatPhase3RelateGameComponent
import FlatPhase3RelateGameComponent2
import FlatPhase3StoryPlayer
import FlatPhase3WrongReturnPort
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapterTest
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistEngine
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.AiDeclaredVideoFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.AiPlaylistSkipper
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.DetailAppPromotionFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstallResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FollowUpRecommendFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPortTestRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerSpeedFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.StoryActionIcon
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.StoryActionIconsFeatureInstaller
import com.bilibili.video.story.action.LegacyToggle
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.atomic.AtomicReference

/** Phase 3C Batch 3 Tier-B 适配器与热重试单元测试（漂移/根包扁平化/歧义 fail-closed/JSON 严格校验/两阶段幂等）。 */
class Phase3Batch3AdaptersTest {
    private val baseLoader = requireNotNull(javaClass.classLoader)

    private fun testEnvironment(
        loader: ClassLoader,
        registrar: PlayerPortTestRegistrar = PlayerPortTestRegistrar(),
        statuses: MutableList<Pair<String, String>> = mutableListOf()
    ) = HookEnvironment(
        processName = "tv.danmaku.bili",
        classLoader = loader,
        hookPoints = HookPointRegistry(loader),
        registrar = registrar,
        logInfo = { _, _ -> },
        logError = { _, _ -> },
        reportStatus = { ch, st -> statuses += ch to st }
    )

    // -------------------------------------------------------------------------
    // Target 3.1: AI_PLAYLIST_DIRECTOR
    // -------------------------------------------------------------------------

    fun `ai playlist director adapter resolves flattened director fails closed on ambiguity and supports hot retry`() {
        val loader = RenamingLoader(
            parent = baseLoader,
            templates = mapOf(
                AiPlaylistSkipper.SERVICE_CLASS to TemplatePlaylistService::class.java,
                AiPlaylistSkipper.MEDIA_DESCRIPTOR_CLASS to TemplateMediaDescriptor::class.java,
                "com.bilibili.bson.common.PojoClassDescriptor" to TemplatePojoClassDescriptor::class.java,
                "com.bilibili.bson.common.PojoPropertyDescriptor" to TemplatePojoPropertyDescriptor::class.java,
                "kotlinx.coroutines.CoroutineScope" to TemplateCoroutineScope::class.java,
                "kotlinx.coroutines.CoroutineScopeKt" to TemplateCoroutineScopeKt::class.java,
                "FlatPhase3PlaylistDirector" to TemplatePlaylistDirector::class.java,
                "FlatPhase3PlaylistDirector2" to TemplatePlaylistDirector::class.java,
                "FlatPhase3BadPlaylistDirector" to FlatPhase3BadPlaylistDirector::class.java,
                "evil.pkg.Director" to TemplatePlaylistDirector::class.java,
                // AiDeclaredVideoFeatureInstaller main routes stubs
                "com.bapis.bilibili.app.viewunite.v1.ViewMoss" to TemplateAiViewMoss::class.java,
                "com.bapis.bilibili.app.viewunite.v1.ViewReq" to TemplateAiViewReq::class.java
            ),
            packageAliases = mapOf(
                "com.bapis.bilibili.app.viewunite.v1." to "aistub.v1.",
                "com.bapis.bilibili.app.viewunite.common." to "aistub.common."
            ),
            replacements = mapOf(
                TemplatePlaylistService::class.java.name.replace('.', '/') to AiPlaylistSkipper.SERVICE_CLASS.replace('.', '/'),
                TemplateCoroutineScope::class.java.name.replace('.', '/') to "kotlinx/coroutines/CoroutineScope",
                TemplatePojoPropertyDescriptor::class.java.name.replace('.', '/') to "com/bilibili/bson/common/PojoPropertyDescriptor",
                TemplateAiViewReq::class.java.name.replace('.', '/') to "com/bapis/bilibili/app/viewunite/v1/ViewReq",
                "aistub/v1/" to "com/bapis/bilibili/app/viewunite/v1/",
                "aistub/common/" to "com/bapis/bilibili/app/viewunite/common/"
            )
        )

        assertTrue(AiPlaylistDirectorAdapter.applicable(loader))
        assertNull(AiPlaylistDirectorAdapter.direct(loader))
        assertTrue(AiPlaylistDirectorAdapter.needsQuery(loader, cached = null, enabled = true, attempted = false))
        assertFalse(AiPlaylistDirectorAdapter.needsQuery(loader, cached = null, enabled = true, attempted = true))

        val directorClass = loader.loadClass("FlatPhase3PlaylistDirector")
        val director2Class = loader.loadClass("FlatPhase3PlaylistDirector2")
        val badDirectorClass = loader.loadClass("FlatPhase3BadPlaylistDirector")
        val evilClass = loader.loadClass("evil.pkg.Director")

        assertFalse(AiPlaylistDirectorAdapter.allowedOwnerName("evil.pkg.Director"))
        assertTrue(AiPlaylistDirectorAdapter.allowedOwnerName("FlatPhase3PlaylistDirector"))

        val session = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(methods = emptyList(), classes = listOf(badDirectorClass, evilClass, directorClass)) },
            listOf("base.apk"),
            loader,
            setOf(AiPlaylistDirectorAdapter.query)
        )
        val located = AiPlaylistDirectorAdapter.locate(loader, session, enabled = true, cached = null, planned = true)
        assertEquals("FlatPhase3PlaylistDirector", located)
        assertEquals("FlatPhase3PlaylistDirector", AiPlaylistDirectorAdapter.resolve(loader, located))
        assertFalse(AiPlaylistDirectorAdapter.needsQuery(loader, cached = located, enabled = true, attempted = false))

        // Ambiguity fails closed
        val ambiguousSession = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(methods = emptyList(), classes = listOf(directorClass, director2Class)) },
            listOf("base.apk"),
            loader,
            setOf(AiPlaylistDirectorAdapter.query)
        )
        assertNull(AiPlaylistDirectorAdapter.locate(loader, ambiguousSession, enabled = true, cached = null, planned = true))

        // AdaptResult JSON round-trip and fail-closed
        val baseJson = VersionAdapterTest().result().copy(aiPlaylistDirector = located).toJson()
        val restored = requireNotNull(AdaptResult.fromJson(JSONObject(baseJson.toString())))
        assertEquals("FlatPhase3PlaylistDirector", restored.aiPlaylistDirector)
        assertNull(AdaptResult.fromJson(JSONObject(baseJson.toString()).put("ai_playlist_director", JSONObject.NULL)))
        assertNull(AdaptResult.fromJson(JSONObject(baseJson.toString()).put("ai_playlist_director", "evil.pkg.Director")))

        // 2-pass hot-retry idempotency for AiDeclaredVideoFeatureInstaller
        val directorRef = AtomicReference<String?>(null)
        val registrar = PlayerPortTestRegistrar()
        val env = testEnvironment(loader, registrar)
        val installer = AiDeclaredVideoFeatureInstaller(
            enabled = true,
            strongMode = false,
            aiPlaylistDirectorProvider = { directorRef.get() }
        )
        val pass1 = installer.install(env)
        assertTrue(pass1 is FeatureInstallResult.Installed)
        assertTrue(installer.requiresAdaptationRetry)
        val mainAttempts = registrar.registrationAttempts.count { it.first.contains("executeView") }

        directorRef.set(located)
        val pass2 = installer.install(env)
        assertTrue(pass2 is FeatureInstallResult.Installed)
        assertFalse(installer.requiresAdaptationRetry)
        assertEquals(mainAttempts, registrar.registrationAttempts.count { it.first.contains("executeView") })
        assertTrue(registrar.registrationAttempts.any { it.first == "ai_declared.playlist.construct" })
        assertTrue(registrar.registrationAttempts.any { it.first.startsWith("ai_declared.playlist.director.") })
    }

    // -------------------------------------------------------------------------
    // Target 3.2: FOLLOW_UP_RECOMMEND_BODY
    // -------------------------------------------------------------------------

    fun `follow up recommend adapter resolves dual flattened coroutine bodies fails closed on ambiguity and retries cleanly`() {
        val theseus2Repo = FollowUpRecommendFeatureInstaller.Family.THESEUS2.repository
        val theseusRepo = FollowUpRecommendFeatureInstaller.Family.THESEUS.repository
        val loader = RenamingLoader(
            parent = baseLoader,
            templates = mapOf(
                theseus2Repo to TemplateTheseus2Repo::class.java,
                theseusRepo to TemplateTheseusRepo::class.java,
                "FlatPhase3FollowUpBodyTheseus2" to TemplateFollowUpBodyTheseus2::class.java,
                "FlatPhase3FollowUpBodyTheseus2Dup" to TemplateFollowUpBodyTheseus2::class.java,
                "FlatPhase3FollowUpBodyTheseus" to TemplateFollowUpBodyTheseus::class.java,
                "FlatPhase3BadFollowUpBody" to TemplateBadFollowUpBody::class.java,
                "evil.pkg.Body" to TemplateFollowUpBodyTheseus2::class.java
            ),
            replacements = mapOf(
                TemplateB3Continuation::class.java.name.replace('.', '/') to "kotlin/coroutines/Continuation",
                TemplateTheseus2Repo::class.java.name.replace('.', '/') to theseus2Repo.replace('.', '/'),
                TemplateTheseusRepo::class.java.name.replace('.', '/') to theseusRepo.replace('.', '/')
            )
        )

        assertTrue(FollowUpRecommendAdapter.applicable(loader))
        assertEquals(2, FollowUpRecommendAdapter.missingFamilies(loader, emptyMap()).size)
        assertTrue(FollowUpRecommendAdapter.needsQuery(loader, emptyMap(), enabled = true, attempted = false))

        val bodyT2 = loader.loadClass("FlatPhase3FollowUpBodyTheseus2")
        val bodyT2Dup = loader.loadClass("FlatPhase3FollowUpBodyTheseus2Dup")
        val bodyT1 = loader.loadClass("FlatPhase3FollowUpBodyTheseus")
        val badBody = loader.loadClass("FlatPhase3BadFollowUpBody")
        val evilBody = loader.loadClass("evil.pkg.Body")

        val session = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(methods = emptyList(), classes = listOf(badBody, evilBody, bodyT2, bodyT1)) },
            listOf("base.apk"),
            loader,
            setOf(FollowUpRecommendAdapter.query)
        )
        val located = FollowUpRecommendAdapter.locate(loader, session, enabled = true, cached = emptyMap(), planned = true)
        assertEquals(
            mapOf("theseus2" to "FlatPhase3FollowUpBodyTheseus2", "theseus" to "FlatPhase3FollowUpBodyTheseus"),
            located
        )
        assertFalse(FollowUpRecommendAdapter.needsQuery(loader, located, enabled = true, attempted = false))

        // Ambiguity in theseus2 fails closed for theseus2 while still resolving unique theseus
        val ambiguousSession = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(methods = emptyList(), classes = listOf(bodyT2, bodyT2Dup, bodyT1)) },
            listOf("base.apk"),
            loader,
            setOf(FollowUpRecommendAdapter.query)
        )
        assertEquals(
            mapOf("theseus" to "FlatPhase3FollowUpBodyTheseus"),
            FollowUpRecommendAdapter.locate(loader, ambiguousSession, enabled = true, cached = emptyMap(), planned = true)
        )

        // JSON round-trip & fail-closed
        val baseJson = VersionAdapterTest().result().copy(followUpRecommendBodies = located).toJson()
        val restored = requireNotNull(AdaptResult.fromJson(JSONObject(baseJson.toString())))
        assertEquals(located, restored.followUpRecommendBodies)
        assertNull(AdaptResult.fromJson(JSONObject(baseJson.toString()).put("follow_up_recommend_bodies", JSONObject().put("unknown_family", "a"))))
        assertNull(AdaptResult.fromJson(JSONObject(baseJson.toString()).put("follow_up_recommend_bodies", JSONObject().put("theseus2", "evil.pkg.Body"))))

        // 2-pass hot-retry idempotency for FollowUpRecommendFeatureInstaller
        val bodiesRef = AtomicReference<Map<String, String>>(emptyMap())
        val registrar = PlayerPortTestRegistrar()
        val env = testEnvironment(loader, registrar)
        val installer = FollowUpRecommendFeatureInstaller(
            enabled = true,
            cachedBodiesProvider = { bodiesRef.get() }
        )
        installer.install(env)
        assertTrue(installer.requiresAdaptationRetry)
        assertEquals(0, registrar.registrationAttempts.count { it.first.startsWith("follow.up_recommend.request") })

        bodiesRef.set(located)
        val pass2 = installer.install(env)
        assertTrue(pass2 is FeatureInstallResult.Installed)
        assertFalse(installer.requiresAdaptationRetry)
        assertEquals(2, registrar.registrationAttempts.count { it.first.startsWith("follow.up_recommend.request") })

        // Re-running install must not duplicate already installed hooks
        installer.install(env)
        assertEquals(2, registrar.registrationAttempts.count { it.first.startsWith("follow.up_recommend.request") })
    }

    // -------------------------------------------------------------------------
    // Target 3.3: DETAIL_RELATE_GAME_COMPONENT
    // -------------------------------------------------------------------------

    fun `detail relate game adapter resolves flattened component over base hierarchy and retries cleanly`() {
        val inPkgGame = "${DetailRelateGameAdapter.RELATE_GAME_PACKAGE}.aa"
        val loader = RenamingLoader(
            parent = baseLoader,
            templates = mapOf(
                "androidx.viewbinding.ViewBinding" to TemplateB3ViewBinding::class.java,
                DetailRelateGameAdapter.GEMINI_SIMPLE_VIEW_ENTRY to TemplateSimpleViewEntry::class.java,
                "${DetailRelateGameAdapter.GEMINI_UI_PACKAGE}.zz" to TemplateGeminiBaseComponent::class.java,
                "FlatPhase3RelateGameComponent" to TemplateRelateGameComponent::class.java,
                "FlatPhase3RelateGameComponent2" to TemplateRelateGameComponent::class.java,
                inPkgGame to TemplateRelateGameComponent::class.java,
                "com.bilibili.gripper.api.ad.biz.GAdBizKt" to TemplateGAdBizKt::class.java,
                "com.bilibili.gripper.api.ad.biz.GAdVideoDetail" to TemplateGAdVideoDetail::class.java,
                "com.bilibili.gripper.api.ad.biz.videodetail.relate.IAdVideoRelate" to TemplateIAdVideoRelate::class.java
            ),
            replacements = mapOf(
                TemplateB3ViewBinding::class.java.name.replace('.', '/') to "androidx/viewbinding/ViewBinding",
                TemplateB3Continuation::class.java.name.replace('.', '/') to "kotlin/coroutines/Continuation",
                TemplateGeminiBaseComponent::class.java.name.replace('.', '/') to "${DetailRelateGameAdapter.GEMINI_UI_PACKAGE.replace('.', '/')}/zz",
                TemplateGAdVideoDetail::class.java.name.replace('.', '/') to "com/bilibili/gripper/api/ad/biz/GAdVideoDetail",
                TemplateIAdVideoRelate::class.java.name.replace('.', '/') to "com/bilibili/gripper/api/ad/biz/videodetail/relate/IAdVideoRelate"
            )
        )

        assertTrue(DetailRelateGameAdapter.applicable(loader))
        assertNull(DetailRelateGameAdapter.direct(loader))
        assertTrue(DetailRelateGameAdapter.needsQuery(loader, cached = null, enabled = true, attempted = false))

        val flatComp = loader.loadClass("FlatPhase3RelateGameComponent")
        val flatComp2 = loader.loadClass("FlatPhase3RelateGameComponent2")
        val inPkgComp = loader.loadClass(inPkgGame)

        // Single flattened candidate resolves
        val flatSession = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(methods = emptyList(), classes = listOf(flatComp)) },
            listOf("base.apk"),
            loader,
            setOf(DetailRelateGameAdapter.query)
        )
        val located = DetailRelateGameAdapter.locate(loader, flatSession, enabled = true, cached = null, planned = true)
        assertEquals("FlatPhase3RelateGameComponent", located)
        assertNotNull(DetailRelateGameAdapter.resolvePair(loader, located))

        // Ambiguity between 2 flattened components fails closed
        val ambigSession = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(methods = emptyList(), classes = listOf(flatComp, flatComp2)) },
            listOf("base.apk"),
            loader,
            setOf(DetailRelateGameAdapter.query)
        )
        assertNull(DetailRelateGameAdapter.locate(loader, ambigSession, enabled = true, cached = null, planned = true))

        // Prefers relate.game.* candidate over outside class if in-package candidate exists uniquely
        val inPkgSession = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(methods = emptyList(), classes = listOf(inPkgComp, flatComp)) },
            listOf("base.apk"),
            loader,
            setOf(DetailRelateGameAdapter.query)
        )
        assertEquals(inPkgGame, DetailRelateGameAdapter.locate(loader, inPkgSession, enabled = true, cached = null, planned = true))

        // AdaptResult JSON fail-closed
        val baseJson = VersionAdapterTest().result().copy(detailRelateGameComponent = located).toJson()
        val restored = requireNotNull(AdaptResult.fromJson(JSONObject(baseJson.toString())))
        assertEquals("FlatPhase3RelateGameComponent", restored.detailRelateGameComponent)
        assertNull(AdaptResult.fromJson(JSONObject(baseJson.toString()).put("detail_relate_game_component", "evil.pkg.Comp")))

        // 2-pass hot-retry for DetailAppPromotionFeatureInstaller (facade installed once, game component retries once)
        val gameRef = AtomicReference<String?>(null)
        val registrar = PlayerPortTestRegistrar()
        val capEvents = mutableListOf<Pair<String, FeatureInstallResult>>()
        val env = testEnvironment(loader, registrar).copy(
            capabilityEvidence = { id, result -> capEvents += id to result }
        )
        val installer = DetailAppPromotionFeatureInstaller(
            enabled = false,
            relateAdEnabled = true,
            cachedRelateGameComponentProvider = { gameRef.get() }
        )
        val pass1 = installer.install(env) as FeatureInstallResult.Installed
        assertFalse(pass1.complete)
        assertTrue(installer.requiresAdaptationRetry)
        assertTrue(
            capEvents.any { (id, res) ->
                id == "detail_promotion_related_game" &&
                    res is FeatureInstallResult.Skipped &&
                    res.reason == "missing-capability-dependency"
            }
        )
        assertEquals(1, registrar.registrationAttempts.count { it.first == "detail_app_promotion.video_detail" })

        gameRef.set(located)
        val pass2 = installer.install(env) as FeatureInstallResult.Installed
        assertTrue(pass2.complete)
        assertFalse(installer.requiresAdaptationRetry)
        assertTrue(
            capEvents.any { (id, res) ->
                id == "detail_promotion_related_game" &&
                    res is FeatureInstallResult.Installed &&
                    res.complete
            }
        )
        assertEquals(1, registrar.registrationAttempts.count { it.first == "detail_app_promotion.video_detail" })
        assertEquals(1, registrar.registrationAttempts.count { it.first == "detail_app_promotion.relate_game.create" })
        assertEquals(1, registrar.registrationAttempts.count { it.first == "detail_app_promotion.relate_game.bind" })
    }

    // -------------------------------------------------------------------------
    // Target 3.4: LEGACY_STORY_DANMAKU_BIND
    // -------------------------------------------------------------------------

    fun `legacy story danmaku adapter disambiguates multi-overload bind with dex query and retries once`() {
        val loader = RenamingLoader(
            parent = baseLoader,
            templates = mapOf(
                LegacyStoryDanmakuAccess.OWNER to TemplateAmbiguousStoryAbsController::class.java,
                "FlatPhase3StoryPlayer" to FlatPhase3StoryPlayer::class.java
            ),
            replacements = mapOf(
                TemplatePlaceholderStoryPlayer::class.java.name.replace('.', '/') to "FlatPhase3StoryPlayer"
            ),
            hidden = setOf("com.bilibili.video.story.action.widget.StoryDanmakuToggleWidget")
        )

        assertTrue(LegacyStoryDanmakuAdapter.applicable(loader, LegacyToggle::class.java))
        assertNull(LegacyStoryDanmakuAdapter.direct(loader, LegacyToggle::class.java))
        assertTrue(LegacyStoryDanmakuAdapter.needsQuery(loader, null, enabled = true, attempted = false, LegacyToggle::class.java))

        val owner = loader.loadClass(LegacyStoryDanmakuAccess.OWNER)
        val k0Method = owner.declaredMethods.single { it.name == "k0" }
        val b0Method = owner.declaredMethods.single { it.name == "b0" }

        val session = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(methods = listOf(k0Method)) },
            listOf("base.apk"),
            loader,
            setOf(LegacyStoryDanmakuAdapter.query)
        )
        val located = LegacyStoryDanmakuAdapter.locate(
            loader, session, enabled = true, cached = null, planned = true, toggleType = LegacyToggle::class.java
        )
        assertEquals(HookPoint(LegacyStoryDanmakuAccess.OWNER, "k0", listOf("FlatPhase3StoryPlayer", "int")), located)

        // Ambiguity fails closed
        val ambigSession = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(methods = listOf(k0Method, b0Method)) },
            listOf("base.apk"),
            loader,
            setOf(LegacyStoryDanmakuAdapter.query)
        )
        assertNull(
            LegacyStoryDanmakuAdapter.locate(
                loader, ambigSession, enabled = true, cached = null, planned = true, toggleType = LegacyToggle::class.java
            )
        )

        // AdaptResult JSON fail-closed
        val baseJson = VersionAdapterTest().result().copy(legacyStoryDanmakuBind = located).toJson()
        val restored = requireNotNull(AdaptResult.fromJson(JSONObject(baseJson.toString())))
        assertEquals(located, restored.legacyStoryDanmakuBind)
        assertNull(
            AdaptResult.fromJson(
                JSONObject(baseJson.toString()).put(
                    "legacy_story_danmaku_bind",
                    HookPoint("evil.Owner", "k0", listOf("FlatPhase3StoryPlayer", "int")).toJson()
                )
            )
        )

        // 2-pass hot-retry in StoryActionIconsFeatureInstaller (setter installed once, bind retries once)
        val ref = AtomicReference<HookPoint?>(null)
        val registrar = PlayerPortTestRegistrar()
        val env = testEnvironment(loader, registrar)
        val installer = StoryActionIconsFeatureInstaller(
            hidden = setOf(StoryActionIcon.DANMAKU_TOGGLE),
            legacyResolver = { cl -> LegacyStoryDanmakuAccess.resolve(cl, LegacyToggle::class.java, ref.get()) }
        )
        installer.install(env)
        assertTrue(installer.requiresAdaptationRetry)

        ref.set(located)
        val pass2 = installer.install(env)
        assertTrue(pass2 is FeatureInstallResult.Installed && pass2.complete)
        assertFalse(installer.requiresAdaptationRetry)
        assertEquals(1, registrar.registrationAttempts.count { it.first == "story_action_icons.legacy_danmaku.setter" })
        assertEquals(1, registrar.registrationAttempts.count { it.first == "story_action_icons.legacy_danmaku.bind" })
    }

    // -------------------------------------------------------------------------
    // Target 3.5: PLAYER_SPEED_THESEUS2_LONG_PRESS
    // -------------------------------------------------------------------------

    fun `player speed theseus2 long press locates flattened launch and port with renamed method and retries cleanly`() {
        val serviceName = PlayerSpeedLocator.THESEUS2_SPEED_SERVICE
        val loader = RenamingLoader(
            parent = baseLoader,
            templates = mapOf(
                serviceName to TemplateTripleSpeedService::class.java,
                "FlatPhase3LongPressLaunch" to TemplateLongPressLaunch::class.java,
                "FlatPhase3LongPressLaunch2" to TemplateLongPressLaunch::class.java,
                "FlatPhase3LongPressPort" to TemplateLongPressPort::class.java,
                "FlatPhase3LongPressPort2" to TemplateLongPressPort::class.java,
                "FlatPhase3WrongReturnPort" to TemplateWrongReturnPort::class.java,
                "evil.pkg.Port" to TemplateLongPressPort::class.java
            ),
            replacements = mapOf(
                TemplateB3Continuation::class.java.name.replace('.', '/') to "kotlin/coroutines/Continuation",
                TemplateTripleSpeedService::class.java.name.replace('.', '/') to serviceName.replace('.', '/')
            )
        )

        val service = requireNotNull(PlayerSpeedLocator.theseus2LongPressService(loader))
        assertNull(PlayerSpeedLocator.theseus2LongPress(loader, service, cached = null))
        assertTrue(PlayerSpeedLocator.theseus2LongPressNeedsQuery(loader, cached = null, enabled = true, attempted = false))

        val launchClass = loader.loadClass("FlatPhase3LongPressLaunch")
        val launch2Class = loader.loadClass("FlatPhase3LongPressLaunch2")
        val portClass = loader.loadClass("FlatPhase3LongPressPort")
        val port2Class = loader.loadClass("FlatPhase3LongPressPort2")
        val wrongReturn = loader.loadClass("FlatPhase3WrongReturnPort")
        val evilPort = loader.loadClass("evil.pkg.Port")

        val session = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(methods = emptyList(), classes = listOf(wrongReturn, evilPort, launchClass, portClass)) },
            listOf("base.apk"),
            loader,
            PlayerSpeedLocator.theseus2LongPressQueries
        )
        val located = requireNotNull(
            PlayerSpeedLocator.locateTheseus2LongPress(loader, session, enabled = true, cached = null, planned = true)
        )
        assertEquals("FlatPhase3LongPressLaunch", located.launchClass)
        assertEquals("FlatPhase3LongPressPort", located.portClass)
        assertEquals("p", located.portMethod)
        assertFalse(PlayerSpeedLocator.theseus2LongPressNeedsQuery(loader, located, enabled = true, attempted = false))

        // Ambiguity in launch or port fails closed
        val ambigLaunch = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(methods = emptyList(), classes = listOf(launchClass, launch2Class, portClass)) },
            listOf("base.apk"),
            loader,
            PlayerSpeedLocator.theseus2LongPressQueries
        )
        assertNull(PlayerSpeedLocator.locateTheseus2LongPress(loader, ambigLaunch, enabled = true, cached = null, planned = true))

        val ambigPort = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(methods = emptyList(), classes = listOf(launchClass, portClass, port2Class)) },
            listOf("base.apk"),
            loader,
            PlayerSpeedLocator.theseus2LongPressQueries
        )
        assertNull(PlayerSpeedLocator.locateTheseus2LongPress(loader, ambigPort, enabled = true, cached = null, planned = true))

        // JSON fail-closed & round-trip
        val baseJson = VersionAdapterTest().result().copy(playerSpeedTheseus2LongPress = located).toJson()
        val restored = requireNotNull(AdaptResult.fromJson(JSONObject(baseJson.toString())))
        assertEquals(located, restored.playerSpeedTheseus2LongPress)
        assertNull(AdaptResult.fromJson(JSONObject(baseJson.toString()).put("player_speed_theseus2_long_press", JSONObject.NULL)))
        assertNull(
            AdaptResult.fromJson(
                JSONObject(baseJson.toString()).put(
                    "player_speed_theseus2_long_press",
                    JSONObject().put("launch", "evil.Launch").put("port", "FlatPhase3LongPressPort").put("m", "p")
                )
            )
        )

        // 2-pass hot-retry in PlayerSpeedFeatureInstaller
        val longPressRef = AtomicReference<Theseus2LongPressPoint?>(null)
        val registrar = PlayerPortTestRegistrar()
        val env = testEnvironment(loader, registrar)
        val installer = PlayerSpeedFeatureInstaller(
            disableLongPress = true,
            longPressPercent = 0,
            defaultPercent = 0,
            cachedTheseus2LongPressProvider = { longPressRef.get() }
        )
        installer.install(env)
        assertTrue(installer.requiresAdaptationRetry)
        assertEquals(0, registrar.registrationAttempts.count { it.first == "player.speed.disable_long_press_theseus2" })

        longPressRef.set(located)
        installer.install(env)
        assertFalse(installer.requiresAdaptationRetry)
        assertEquals(1, registrar.registrationAttempts.count { it.first == "player.speed.disable_long_press_theseus2" })
    }

    @Test
    fun verifyAllCases() {
        fun runIsolated(block: (Phase3Batch3AdaptersTest) -> Unit) {
            block(Phase3Batch3AdaptersTest())
        }
        runIsolated { it.`ai playlist director adapter resolves flattened director fails closed on ambiguity and supports hot retry`() }
        runIsolated { it.`follow up recommend adapter resolves dual flattened coroutine bodies fails closed on ambiguity and retries cleanly`() }
        runIsolated { it.`detail relate game adapter resolves flattened component over base hierarchy and retries cleanly`() }
        runIsolated { it.`legacy story danmaku adapter disambiguates multi-overload bind with dex query and retries once`() }
        runIsolated { it.`player speed theseus2 long press locates flattened launch and port with renamed method and retries cleanly`() }
    }

    // -------------------------------------------------------------------------
    // Templates & Bytecode Renaming ClassLoader
    // -------------------------------------------------------------------------

    class TemplateB3Continuation
    class TemplateB3ViewBinding
    class TemplateAiViewReq {
        fun getSpmid(): String = "dt.0.0.0"
    }
    class TemplateAiViewMoss {
        @Suppress("UNUSED_PARAMETER")
        fun executeView(req: TemplateAiViewReq): aistub.v1.ViewReply = aistub.v1.ViewReply.getDefaultInstance()

        @Suppress("UNUSED_PARAMETER")
        fun view(req: TemplateAiViewReq, handler: com.bilibili.lib.moss.api.MossResponseHandler) {
            handler.onNext(aistub.v1.ViewReply.getDefaultInstance())
            handler.onCompleted()
        }
    }
    class TemplateCoroutineScope
    object TemplateCoroutineScopeKt {
        @Suppress("UNUSED_PARAMETER")
        @JvmStatic
        fun isActive(scope: TemplateCoroutineScope): Boolean = true
    }

    class TemplatePojoPropertyDescriptor(private val name: String) {
        fun getKeyName(): String = name
    }

    class TemplatePojoClassDescriptor {
        fun getProperties(): Array<TemplatePojoPropertyDescriptor> = emptyArray()
    }

    class TemplateMediaDescriptor {
        @Suppress("UNUSED_PARAMETER")
        fun constructWith(values: Array<Any>): Any = Any()

        companion object {
            @JvmField
            val INSTANCE = TemplatePojoClassDescriptor()
        }
    }

    class TemplatePlaylistService(@JvmField val scope: TemplateCoroutineScope? = null)

    class TemplatePlaylistDirector(@JvmField var svc: TemplatePlaylistService? = null) {
        @Suppress("UNUSED_PARAMETER")
        fun switchToNext(fromAutoPlay: Boolean) = Unit

        @Suppress("UNUSED_PARAMETER")
        fun hasNext(fromAutoPlay: Boolean): Boolean = true
    }

    class TemplateTheseus2Repo
    class TemplateTheseusRepo

    class TemplateFollowUpBodyTheseus2(
        @Suppress("UNUSED_PARAMETER") repo: TemplateTheseus2Repo,
        @Suppress("UNUSED_PARAMETER") cont: TemplateB3Continuation
    ) : Function2<Any?, Any?, Any?> {
        fun invokeSuspend(@Suppress("UNUSED_PARAMETER") result: Any?): Any? = Unit
        override fun invoke(p1: Any?, p2: Any?): Any? = Unit
    }

    class TemplateFollowUpBodyTheseus(
        @Suppress("UNUSED_PARAMETER") repo: TemplateTheseusRepo,
        @Suppress("UNUSED_PARAMETER") cont: TemplateB3Continuation
    ) : Function2<Any?, Any?, Any?> {
        fun invokeSuspend(@Suppress("UNUSED_PARAMETER") result: Any?): Any? = Unit
        override fun invoke(p1: Any?, p2: Any?): Any? = Unit
    }

    class TemplateBadFollowUpBody(
        @Suppress("UNUSED_PARAMETER") wrongArg: String,
        @Suppress("UNUSED_PARAMETER") cont: TemplateB3Continuation
    ) : Function2<Any?, Any?, Any?> {
        fun invokeSuspend(@Suppress("UNUSED_PARAMETER") result: Any?): Any? = Unit
        override fun invoke(p1: Any?, p2: Any?): Any? = Unit
    }

    class TemplateSimpleViewEntry(@Suppress("UNUSED_PARAMETER") view: View)

    abstract class TemplateGeminiBaseComponent {
        @Suppress("UNUSED_PARAMETER")
        fun createViewEntry(context: Context, parent: ViewGroup): Any = Any()

        @Suppress("UNUSED_PARAMETER")
        open fun bindToView(binding: Any, cont: TemplateB3Continuation): Any? = Unit
    }

    class TemplateRelateGameComponent : TemplateGeminiBaseComponent() {
        @Suppress("UNUSED_PARAMETER")
        fun g(context: Context, inflater: LayoutInflater, parent: ViewGroup): TemplateB3ViewBinding =
            TemplateB3ViewBinding()

        @Suppress("UNUSED_PARAMETER")
        fun h(binding: TemplateB3ViewBinding, cont: TemplateB3Continuation): Any? = Unit
    }

    class TemplateIAdVideoRelate

    class TemplateGAdVideoDetail {
        fun getRelate(): TemplateIAdVideoRelate? = null
    }

    object TemplateGAdBizKt {
        @JvmStatic
        fun getGAdVideoDetail(): TemplateGAdVideoDetail? = null
    }

    class TemplatePlaceholderStoryPlayer

    class TemplateAmbiguousStoryAbsController {
        @JvmField
        var toggle: LegacyToggle? = null

        @JvmField
        var player: TemplatePlaceholderStoryPlayer? = null

        fun getMDanmakuToggle(): LegacyToggle? = toggle
        fun setMDanmakuToggle(value: LegacyToggle?) {
            toggle = value
        }

        fun getMPlayer(): TemplatePlaceholderStoryPlayer? = player

        @Suppress("UNUSED_PARAMETER")
        fun b0(player: TemplatePlaceholderStoryPlayer, pos: Int) = Unit

        @Suppress("UNUSED_PARAMETER")
        fun k0(player: TemplatePlaceholderStoryPlayer, pos: Int) = Unit
    }

    class TemplateTripleSpeedService

    class TemplateLongPressLaunch(
        @Suppress("UNUSED_PARAMETER") svc: TemplateTripleSpeedService,
        @Suppress("UNUSED_PARAMETER") speed: Float,
        @Suppress("UNUSED_PARAMETER") obj: Any?,
        @Suppress("UNUSED_PARAMETER") cont: TemplateB3Continuation
    )

    class TemplateLongPressPort(@JvmField val svc: TemplateTripleSpeedService) {
        fun p(): Float? = 2.0f
    }

    class TemplateWrongReturnPort(@JvmField val svc: TemplateTripleSpeedService) {
        fun p(): Int = 1
    }

    private class RenamingLoader(
        parent: ClassLoader,
        private val templates: Map<String, Class<*>>,
        private val replacements: Map<String, String>,
        private val hidden: Set<String> = emptySet(),
        private val packageAliases: Map<String, String> = emptyMap()
    ) : ClassLoader(parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(this) {
            if (name in hidden) throw ClassNotFoundException(name)
            findLoadedClass(name)?.let { return@synchronized it }
            val template = templates[name]
            val loaded = if (template != null) {
                val raw = requireNotNull(template.getResourceAsStream("/${template.name.replace('.', '/')}.class"))
                    .use { it.readBytes() }
                val selfInternal = template.name.replace('.', '/')
                val targetInternal = name.replace('.', '/')
                val rewritten = rewrite(raw, replacements + (selfInternal to targetInternal))
                defineClass(name, rewritten, 0, rewritten.size)
            } else {
                val alias = packageAliases.entries.firstOrNull { (targetPrefix, _) -> name.startsWith(targetPrefix) }
                val sourceName = alias?.let { (targetPrefix, sourcePrefix) ->
                    sourcePrefix + name.removePrefix(targetPrefix)
                }
                val stream = sourceName?.let { parent.getResourceAsStream("${it.replace('.', '/')}.class") }
                if (stream != null) {
                    val raw = stream.use { it.readBytes() }
                    val selfInternal = sourceName.replace('.', '/')
                    val targetInternal = name.replace('.', '/')
                    val rewritten = rewrite(raw, replacements + (selfInternal to targetInternal))
                    defineClass(name, rewritten, 0, rewritten.size)
                } else {
                    return@synchronized super.loadClass(name, resolve)
                }
            }
            if (resolve) resolveClass(loaded)
            loaded
        }

        private fun rewrite(raw: ByteArray, replacements: Map<String, String>): ByteArray {
            val input = DataInputStream(ByteArrayInputStream(raw))
            val outBuffer = ByteArrayOutputStream()
            val output = DataOutputStream(outBuffer)
            output.writeInt(input.readInt())
            output.writeShort(input.readUnsignedShort())
            output.writeShort(input.readUnsignedShort())
            val count = input.readUnsignedShort()
            output.writeShort(count)
            var index = 1
            while (index < count) {
                val tag = input.readUnsignedByte()
                output.writeByte(tag)
                if (tag == 1) {
                    var value = input.readUTF()
                    replacements.entries.sortedByDescending { it.key.length }.forEach { (k, v) ->
                        value = value.replace(k, v)
                    }
                    output.writeUTF(value)
                } else {
                    val length = when (tag) {
                        3, 4, 9, 10, 11, 12, 17, 18 -> 4
                        5, 6 -> 8
                        7, 8, 16, 19, 20 -> 2
                        15 -> 3
                        else -> error("Unsupported CP tag: $tag")
                    }
                    repeat(length) { output.writeByte(input.readUnsignedByte()) }
                    if (tag == 5 || tag == 6) index++
                }
                index++
            }
            output.write(input.readBytes())
            output.flush()
            return outBuffer.toByteArray()
        }
    }
}
