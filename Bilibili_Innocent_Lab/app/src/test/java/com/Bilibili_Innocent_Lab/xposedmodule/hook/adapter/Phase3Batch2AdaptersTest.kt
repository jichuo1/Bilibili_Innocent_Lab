package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import BadPhase3QuickReplyIntent
import FlatPhase3BadIntentCollector
import FlatPhase3BadQuickReplyCollector
import FlatPhase3HomeTabItem
import FlatPhase3HomeTabItem2
import FlatPhase3NoisyHomeDto
import FlatPhase3ShuffledHomeTabItem
import FlatPhase3BrandModel
import FlatPhase3QuickReplyCollector
import FlatPhase3QuickReplyCollectorB
import FlatPhase3UnrelatedCollector1
import FlatPhase3UnrelatedCollector2
import FlatPhase3WeakHomeTabItem
import FlatWave1CloudKeyContract
import Wave1FeatureFlagInterface
import Wave1TypedContractBase
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.PausePoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapterTest
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistEngine
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.BrandSplashKntrAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.BrandSplashSkipFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CloudKeyFreezeFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentPurifyFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentReplyButtonFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstallResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HomeFrameTabLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HomeTabFilterFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PauseCountdownAdapterTest
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PauseRequestCoverage
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PauseResponseInstallerTest
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PausedAdFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPortTestRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import com.bilibili.app.comment3.data.state.PublishDialogIntent
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.CancellationException

/** Phase 3B Batch 2 Tier-A 零兜底点升级单元测试（漂移/根包扁平化/歧义 fail-closed/热重试）。 */
class Phase3Batch2AdaptersTest {
    private val baseLoader = requireNotNull(javaClass.classLoader)

    // -------------------------------------------------------------------------
    // Target 2.1: COMMENT_QUICK_REPLY_COLLECTOR
    // -------------------------------------------------------------------------

    fun `comment quick reply adapter resolves drifted and flattened collectors and rejects bad intent or ambiguity`() {
        assertTrue(CommentQuickReplyAdapter.applicable(baseLoader))
        val validEmit = FlatPhase3QuickReplyCollector::class.java.getDeclaredMethod(
            "emit", PublishDialogIntent::class.java, Any::class.java
        )
        // Re-map Kotlin Continuation param for JVM reflection verification
        val loaderWithContinuation = RenamingLoader(
            parent = baseLoader,
            templates = mapOf(
                "FlatPhase3QuickReplyCollector" to TemplateQuickReplyWithCommentOwner::class.java,
                "FlatPhase3QuickReplyCollectorB" to TemplateQuickReplyWithCommentOwner::class.java,
                "FlatPhase3UnrelatedCollector1" to TemplateQuickReplyUnrelated::class.java,
                "FlatPhase3UnrelatedCollector2" to TemplateQuickReplyUnrelated::class.java,
                "FlatPhase3BadQuickReplyCollector" to TemplateQuickReplyBadIntent::class.java,
                "FlatPhase3BadIntentCollector" to TemplateQuickReplyMissingPosIntent::class.java,
                "com.example.Bad.PublishDialogIntent" to BadPublishIntentSuffix::class.java
            ),
            replacements = mapOf(
                TemplateBaseContinuation::class.java.name.replace('.', '/') to "kotlin/coroutines/Continuation",
                BadPublishIntentSuffix::class.java.name.replace('.', '/') to "com/example/Bad/PublishDialogIntent"
            )
        )
        val flattenedClass = loaderWithContinuation.loadClass("FlatPhase3QuickReplyCollector")
        val flattenedMethod = flattenedClass.declaredMethods.single { it.name == "emit" }
        val flattenedClassB = loaderWithContinuation.loadClass("FlatPhase3QuickReplyCollectorB")
        val flattenedMethodB = flattenedClassB.declaredMethods.single { it.name == "emit" }
        val badIntentMethod = loaderWithContinuation.loadClass("FlatPhase3BadQuickReplyCollector").declaredMethods.single { it.name == "emit" }
        val badPosMethod = loaderWithContinuation.loadClass("FlatPhase3BadIntentCollector").declaredMethods.single { it.name == "emit" }

        assertTrue(CommentQuickReplyAdapter.verifiedMethod(flattenedMethod))
        assertFalse(CommentQuickReplyAdapter.verifiedMethod(validEmit)) // param 1 is Object, not Continuation
        assertFalse(CommentQuickReplyAdapter.verifiedMethod(badIntentMethod)) // < 2 booleans
        assertFalse(CommentQuickReplyAdapter.verifiedMethod(badPosMethod)) // no Pos enum
        assertFalse(CommentQuickReplyAdapter.allowedOwnerName("evil.pkg.Collector"))

        // Multiple CommentContainerImpl-capturing collectors resolve together
        val session = DexAssistSession(
            DexAssistEngine {
                DexAssistResult.Candidates(listOf(badIntentMethod, badPosMethod, flattenedMethod, flattenedMethodB))
            },
            listOf("base.apk"),
            loaderWithContinuation,
            setOf(CommentQuickReplyAdapter.query)
        )
        val selected = CommentQuickReplyAdapter.locate(
            loaderWithContinuation, session, enabled = true, directMethods = emptyList(), cachedMethods = emptyList(), planned = true
        )
        assertEquals(2, selected.size)
        assertEquals(listOf("FlatPhase3QuickReplyCollector", "FlatPhase3QuickReplyCollectorB"), selected.map { it.className })
        assertEquals(selected, CommentQuickReplyAdapter.resolve(loaderWithContinuation, selected))

        // Uncaptured ambiguity across two unrelated owners fails closed
        val unrelated1 = loaderWithContinuation.loadClass("FlatPhase3UnrelatedCollector1").declaredMethods.single { it.name == "emit" }
        val unrelated2 = loaderWithContinuation.loadClass("FlatPhase3UnrelatedCollector2").declaredMethods.single { it.name == "emit" }
        val ambiguousSession = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(listOf(unrelated1, unrelated2)) },
            listOf("base.apk"),
            loaderWithContinuation,
            setOf(CommentQuickReplyAdapter.query)
        )
        assertTrue(
            CommentQuickReplyAdapter.locate(
                loaderWithContinuation, ambiguousSession, enabled = true, directMethods = emptyList(), cachedMethods = emptyList(), planned = true
            ).isEmpty()
        )
        // Unique uncaptured flattened owner resolves cleanly
        val uniqueSession = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(listOf(unrelated1)) },
            listOf("base.apk"),
            loaderWithContinuation,
            setOf(CommentQuickReplyAdapter.query)
        )
        assertEquals(
            listOf("FlatPhase3UnrelatedCollector1"),
            CommentQuickReplyAdapter.locate(
                loaderWithContinuation, uniqueSession, enabled = true, directMethods = emptyList(), cachedMethods = emptyList(), planned = true
            ).map { it.className }
        )

        // mergeRuntimeWithCached preserves cached DEX quickReplyDialogMethods when quickLocate finds other fields
        val base = VersionAdapterTest().result()
        val cachedQuickReply = HookPoint("FlatPhase3QuickReplyCollector", "emit", listOf(PublishDialogIntent::class.java.name, "kotlin.coroutines.Continuation"))
        val runtimeWithoutQuickReply = base.copy(
            commentPurify = base.commentPurify!!.copy(quickReplyDialogMethods = emptyList())
        )
        val cachedWithQuickReply = base.copy(
            domainRuleVersions = com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapterContract.DOMAIN_RULE_VERSIONS,
            commentPurify = base.commentPurify.copy(quickReplyDialogMethods = listOf(cachedQuickReply))
        )
        val merged = requireNotNull(VersionAdapter.mergeRuntimeWithCached(runtimeWithoutQuickReply, cachedWithQuickReply))
        assertEquals(listOf(cachedQuickReply), merged.commentPurify?.quickReplyDialogMethods)
    }

    // -------------------------------------------------------------------------
    // Target 2.2: PAUSED_AD_COUNTDOWN & PAUSED_AD_REQUEST_API
    // -------------------------------------------------------------------------

    fun `paused ad countdown supports flattened coroutine retry and pause apiEntry enables flattened request coverage`() {
        val pausedPkg = "com.bilibili.ship.theseus.united.page.pausedpage."
        val adPkg = "kntr.app.ad.biz.videodetail.pausedpage."
        val mossPkg = "com.bapis.bilibili.app.viewunite.v1."
        val templates = linkedMapOf<String, Class<*>>(
            "kotlin.coroutines.CoroutineContext" to PauseCountdownAdapterTest.Context::class.java,
            "kotlinx.coroutines.JobKt" to TemplateJobKt::class.java,
            "kntr.common.ad.source.IKSourceContent" to PauseCountdownAdapterTest.Source::class.java,
            "kntr.common.ad.capability.report.IReportable" to PauseCountdownAdapterTest.Reportable::class.java,
            "${pausedPkg}PausedPageService" to TemplatePausedService::class.java,
            "FlatPausedCountdownOwner" to TemplateFlattenedCountdown::class.java,
            "FlatPausedDecoyOwner" to TemplateFlattenedCountdown::class.java,
            "FlatPausedApiOwner" to TemplateFlattenedApiOwner::class.java,
            "${adPkg}AdPausedPageApi\$requestPausedPage\$awaitResponse\$2\$1" to TemplateSuspendLambda::class.java,
            "${adPkg}AdPausedPageApi\$receivePausedPageResult\$2" to TemplateSuspendLambda::class.java,
            "${adPkg}AdPausedPageApi\$requestPausedPage\$1" to TemplateContinuationImpl::class.java,
            "${mossPkg}KTabType" to TemplateKTabType::class.java,
            "${mossPkg}KPlayPauseReq" to TemplateKPlayPauseReq::class.java,
            "${mossPkg}KPlayPauseReply" to TemplateKPlayPauseReply::class.java,
            "${mossPkg}KPauseBar" to TemplateKPauseBar::class.java,
            "${mossPkg}KPlayPauseReply\$IData" to TemplateKIData::class.java,
            "${mossPkg}KViewMoss" to TemplateKViewMoss::class.java,
            "kotlin.coroutines.jvm.internal.SuspendLambda" to TemplateBaseSuspendLambda::class.java,
            "kotlin.coroutines.jvm.internal.ContinuationImpl" to TemplateBaseContImpl::class.java,
            "kotlin.jvm.functions.Function2" to TemplateBaseFunc2::class.java,
            "kotlin.coroutines.Continuation" to TemplateBaseContinuation::class.java
        )
        val replacements = mapOf(
            PauseCountdownAdapterTest.Context::class.java.name.replace('.', '/') to "kotlin/coroutines/CoroutineContext",
            PauseCountdownAdapterTest.Source::class.java.name.replace('.', '/') to "kntr/common/ad/source/IKSourceContent",
            PauseCountdownAdapterTest.Reportable::class.java.name.replace('.', '/') to "kntr/common/ad/capability/report/IReportable",
            TemplatePausedService::class.java.name.replace('.', '/') to "${pausedPkg.replace('.', '/')}PausedPageService",
            TemplateKTabType::class.java.name.replace('.', '/') to "${mossPkg.replace('.', '/')}KTabType",
            TemplateKPlayPauseReq::class.java.name.replace('.', '/') to "${mossPkg.replace('.', '/')}KPlayPauseReq",
            TemplateKPauseBar::class.java.name.replace('.', '/') to "${mossPkg.replace('.', '/')}KPauseBar",
            TemplateBaseSuspendLambda::class.java.name.replace('.', '/') to "kotlin/coroutines/jvm/internal/SuspendLambda",
            TemplateBaseContImpl::class.java.name.replace('.', '/') to "kotlin/coroutines/jvm/internal/ContinuationImpl",
            TemplateBaseFunc2::class.java.name.replace('.', '/') to "kotlin/jvm/functions/Function2",
            TemplateBaseContinuation::class.java.name.replace('.', '/') to "kotlin/coroutines/Continuation"
        )
        val loader = RenamingLoader(
            baseLoader,
            templates,
            replacements,
            hidden = setOf(
                PausedAdDexAdapter.DEFAULT_COUNTDOWN,
                "${adPkg}a",
                "${adPkg}b",
                "com.bapis.bilibili.app.viewunite.v1.PlayPauseReply"
            )
        )

        val countdownMethod = loader.loadClass("FlatPausedCountdownOwner").declaredMethods.single { it.name == "invokeSuspend" }
        val decoyMethod = loader.loadClass("FlatPausedDecoyOwner").declaredMethods.single { it.name == "invokeSuspend" }
        assertTrue(PausedAdDexAdapter.applicableCountdown(loader))
        assertNull(PausedAdDexAdapter.directCountdown(loader))
        assertTrue(PausedAdDexAdapter.needsCountdownQuery(loader, null, null, enabled = true, attempted = false))

        val countdownSession = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(listOf(countdownMethod)) },
            listOf("base.apk"),
            loader,
            setOf(PausedAdDexAdapter.queryCountdown)
        )
        val locatedCountdown = requireNotNull(
            PausedAdDexAdapter.locateCountdown(loader, countdownSession, enabled = true, direct = null, cached = null, planned = true)
        )
        assertEquals("FlatPausedCountdownOwner", locatedCountdown.className)
        assertEquals(locatedCountdown, PausedAdDexAdapter.resolveCountdown(loader, locatedCountdown))
        assertFalse(PausedAdDexAdapter.needsCountdownQuery(loader, null, locatedCountdown, enabled = true, attempted = false))

        // Ambiguity fails closed for countdown
        val ambiguousCountdownSession = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(listOf(countdownMethod, decoyMethod)) },
            listOf("base.apk"),
            loader,
            setOf(PausedAdDexAdapter.queryCountdown)
        )
        assertNull(
            PausedAdDexAdapter.locateCountdown(loader, ambiguousCountdownSession, enabled = true, direct = null, cached = null, planned = true)
        )

        // Request API 16-param flattened discovery + PauseRequestCoverage integration
        val apiMethod = loader.loadClass("FlatPausedApiOwner").declaredMethods.single { it.name == "x" }
        assertTrue(PausedAdDexAdapter.applicableRequestApi(loader))
        assertTrue(PausedAdDexAdapter.needsRequestApiQuery(loader, 9220100, null, enabled = true, attempted = false))
        val apiSession = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(listOf(apiMethod)) },
            listOf("base.apk"),
            loader,
            setOf(PausedAdDexAdapter.queryRequestApi)
        )
        val locatedApi = requireNotNull(
            PausedAdDexAdapter.locateRequestApi(loader, 9220100, apiSession, enabled = true, cached = null, planned = true)
        )
        assertEquals("FlatPausedApiOwner", locatedApi.className)
        assertFalse(PauseRequestCoverage.hasVerifiedApiEntry(loader, 9220100, cachedApiEntry = null))
        assertTrue(PauseRequestCoverage.hasVerifiedApiEntry(loader, 9220100, cachedApiEntry = locatedApi))
        assertEquals(
            setOf("ad-api"),
            PauseRequestCoverage.covered(loader, 9220100, setOf("ad-api"), kotlinInstalled = true, cachedApiEntry = locatedApi)
        )
        assertTrue(PauseRequestCoverage.covered(loader, 9220100, setOf("ad-api"), kotlinInstalled = true, cachedApiEntry = null).isEmpty())

        // PausePoints JSON round-trip preserves optional apiEntry while missing api_entry degrades cleanly
        val pointsWithApi = PausePoints(emptyList(), null, null, locatedCountdown, locatedApi)
        val restored = PausePoints.fromJson(JSONObject(pointsWithApi.toJson().toString()))
        assertEquals(pointsWithApi, restored)
        assertNull(PausePoints.fromJson(JSONObject(pointsWithApi.toJson().toString()).apply { remove("api_entry") }).apiEntry)

        // PausedAdFeatureInstaller 2-stage hot-retry for drifted/flattened countdown
        val registrar = PlayerPortTestRegistrar()
        val env = HookEnvironment(
            "tv.danmaku.bili",
            loader,
            HookPointRegistry(loader),
            registrar,
            { _, _ -> },
            { _, _ -> },
            { _, _ -> }
        )
        var currentPoints = PausePoints(emptyList(), null, null, null, null)
        val installer = PausedAdFeatureInstaller(
            enabled = true,
            points = currentPoints,
            hostVersionCode = 9220100,
            pointsProvider = { currentPoints }
        )
        val run1 = installer.install(env)
        assertTrue(run1 is FeatureInstallResult.Skipped)
        assertTrue(installer.requiresAdaptationRetry)
        assertFalse("paused.countdown" in registrar.hooks)

        currentPoints = pointsWithApi
        val run2 = installer.install(env) as FeatureInstallResult.Installed
        assertEquals(1, run2.hookCount)
        assertFalse(installer.requiresAdaptationRetry)
        assertTrue("paused.countdown" in registrar.hooks)

        // Countdown already installed cold, Kotlin response installed cold, ONLY apiEntry missing -> hot-retry covers ad-api
        val apiOnlyTemplates = templates + mapOf(
            "${mossPkg}PlayPauseReq" to PauseResponseInstallerTest.PauseReq::class.java,
            "${mossPkg}PlayPauseReply" to PauseResponseInstallerTest.PauseReply::class.java,
            "${mossPkg}PlayPauseReply\$Builder" to PauseResponseInstallerTest.PauseReply.Builder::class.java,
            "${mossPkg}PlayPauseReply\$Companion" to PauseResponseInstallerTest.PauseReply.Companion::class.java,
            "${mossPkg}ViewMoss" to PauseResponseInstallerTest.JavaPauseMoss::class.java,
            "kntr.base.moss.MossInterceptor" to TemplateKViewMoss::class.java
        )
        val apiOnlyReplacements = replacements + mapOf(
            PauseResponseInstallerTest.PauseReq::class.java.name.replace('.', '/') to "com/bapis/bilibili/app/viewunite/v1/PlayPauseReq",
            PauseResponseInstallerTest.PauseReply::class.java.name.replace('.', '/') to "com/bapis/bilibili/app/viewunite/v1/PlayPauseReply",
            PauseResponseInstallerTest.PauseReply.Builder::class.java.name.replace('.', '/') to "com/bapis/bilibili/app/viewunite/v1/PlayPauseReply\$Builder",
            PauseResponseInstallerTest.PauseReply.Companion::class.java.name.replace('.', '/') to "com/bapis/bilibili/app/viewunite/v1/PlayPauseReply\$Companion",
            PauseResponseInstallerTest.JavaPauseMoss::class.java.name.replace('.', '/') to "com/bapis/bilibili/app/viewunite/v1/ViewMoss"
        )
        val apiOnlyLoader = RenamingLoader(
            baseLoader,
            apiOnlyTemplates,
            apiOnlyReplacements,
            hidden = setOf(PausedAdDexAdapter.DEFAULT_COUNTDOWN, "${adPkg}a", "${adPkg}b")
        )
        val wireMethod = kmpfixture.modern.GrpcEngine::class.java.declaredMethods.single { it.name == "a" }
        val wirePoint = HookPoint(
            wireMethod.declaringClass.name,
            wireMethod.name,
            wireMethod.parameterTypes.map(Class<*>::getName)
        )
        val apiOnlyRegistrar = PlayerPortTestRegistrar()
        val apiOnlyStatuses = linkedMapOf<String, String>()
        val apiOnlyEnv = HookEnvironment(
            "tv.danmaku.bili",
            apiOnlyLoader,
            HookPointRegistry(apiOnlyLoader),
            apiOnlyRegistrar,
            { _, _ -> },
            { _, _ -> },
            { channel, value -> apiOnlyStatuses[channel] = value }
        )
        var apiOnlyPoints = PausePoints(emptyList(), null, null, locatedCountdown, null)
        val apiOnlyInstaller = PausedAdFeatureInstaller(
            enabled = true,
            points = apiOnlyPoints,
            hostVersionCode = 9220100,
            kmpGrpcWireEngineProvider = { wirePoint },
            pointsProvider = { apiOnlyPoints }
        )
        val apiFirst = apiOnlyInstaller.install(apiOnlyEnv) as FeatureInstallResult.Installed
        assertFalse(apiFirst.complete)
        assertTrue(apiOnlyInstaller.requiresAdaptationRetry)
        assertEquals("ad-api=preserved-unverified", apiOnlyStatuses["adskip_request_routes"])
        apiOnlyPoints = pointsWithApi
        val apiRetry = apiOnlyInstaller.install(apiOnlyEnv) as FeatureInstallResult.Installed
        assertTrue(apiRetry.complete)
        assertFalse(apiOnlyInstaller.requiresAdaptationRetry)
        assertEquals("ad-api=covered-by-kotlin-response", apiOnlyStatuses["adskip_request_routes"])
        assertEquals(1, apiOnlyRegistrar.registrationAttempts.count { it.first == "paused.countdown" })
        assertEquals(1, apiOnlyRegistrar.registrationAttempts.count { it.first == "kmp.grpc.wire.channel" })
    }

    // -------------------------------------------------------------------------
    // Target 2.3: BRAND_SPLASH_KNTR_EXIT & BRAND_SPLASH_KNTR_PAGE
    // -------------------------------------------------------------------------

    fun `brand splash kntr adapter resolves flattened exit and page composable and validates cache strictly`() {
        val pageClassName = "FlatPhase3BrandPageHolder"
        val exitClassName = "FlatPhase3ExitCoroutine"
        val templates = linkedMapOf<String, Class<*>>(
            exitClassName to TemplateCurrentExit::class.java,
            "${exitClassName}2" to TemplateCurrentExit::class.java,
            "FlatPhase3BadExitCoroutine" to TemplateBrokenExit::class.java,
            pageClassName to TemplateBrandPageHolder::class.java,
            "${pageClassName}2" to TemplateBrandPageHolder::class.java,
            "androidx.compose.runtime.Composer" to TemplateComposer::class.java,
            "androidx.compose.ui.Modifier" to TemplateModifier::class.java,
            "androidx.compose.runtime.MutableState" to TemplateMutableState::class.java,
            "androidx.compose.runtime.State" to TemplateState::class.java
        )
        val replacements = mapOf(
            TemplateComposer::class.java.name.replace('.', '/') to "androidx/compose/runtime/Composer",
            TemplateModifier::class.java.name.replace('.', '/') to "androidx/compose/ui/Modifier",
            TemplateMutableState::class.java.name.replace('.', '/') to "androidx/compose/runtime/MutableState",
            TemplateState::class.java.name.replace('.', '/') to "androidx/compose/runtime/State"
        )
        val loader = RenamingLoader(
            baseLoader,
            templates,
            replacements,
            hidden = setOf(
                BrandSplashSkipFeatureInstaller.KNTR_EXIT,
                BrandSplashKntrAccess.CURRENT_EXIT,
                BrandSplashKntrAccess.BITMAP_EXIT,
                "kntr.srcs.app.splash.brand.startup.c"
            )
        )

        assertTrue(BrandSplashKntrAdapter.applicable(loader))
        assertNull(BrandSplashKntrAdapter.directExit(loader))
        assertNull(BrandSplashKntrAdapter.directPage(loader))
        assertTrue(BrandSplashKntrAdapter.needsExitQuery(loader, null, enabled = true, attempted = false))
        assertTrue(BrandSplashKntrAdapter.needsPageQuery(loader, null, enabled = true, attempted = false))

        val exitCls = loader.loadClass(exitClassName)
        val badExitCls = loader.loadClass("FlatPhase3BadExitCoroutine")
        val exitSession = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(emptyList(), listOf(badExitCls, exitCls)) },
            listOf("base.apk"),
            loader,
            setOf(BrandSplashKntrAdapter.queryExit)
        )
        assertEquals(
            exitClassName,
            BrandSplashKntrAdapter.locateExit(loader, exitSession, enabled = true, cached = null, planned = true)
        )
        assertEquals(exitClassName, BrandSplashKntrAdapter.resolveExit(loader, exitClassName))
        assertNull(BrandSplashKntrAdapter.resolveExit(loader, "FlatPhase3BadExitCoroutine"))

        // Ambiguity fails closed on exit
        val ambiguousExitSession = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(emptyList(), listOf(exitCls, loader.loadClass("${exitClassName}2"))) },
            listOf("base.apk"),
            loader,
            setOf(BrandSplashKntrAdapter.queryExit)
        )
        assertNull(BrandSplashKntrAdapter.locateExit(loader, ambiguousExitSession, enabled = true, cached = null, planned = true))

        // Install BrandSplashSkipFeatureInstaller with flattened kntrExitClass
        val skipRegistrar = PlayerPortTestRegistrar()
        val skipEnv = HookEnvironment(
            "tv.danmaku.bili", loader, HookPointRegistry(loader), skipRegistrar, { _, _ -> }, { _, _ -> }, { _, _ -> }
        )
        val skipInstall = BrandSplashSkipFeatureInstaller(enabled = true, kntrExitClass = exitClassName) { true }.install(skipEnv)
        assertTrue(skipInstall is FeatureInstallResult.Installed)
        assertTrue("brand.skip.kntr.exit" in skipRegistrar.hooks)

        // Page Composable discovery & verification
        val pageHolder = loader.loadClass(pageClassName)
        val pageMethod = pageHolder.declaredMethods.single { it.name == "a" }
        val pageSession = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(listOf(pageMethod)) },
            listOf("base.apk"),
            loader,
            setOf(BrandSplashKntrAdapter.queryPage)
        )
        val locatedPage = requireNotNull(
            BrandSplashKntrAdapter.locatePage(loader, pageSession, enabled = true, cached = null, planned = true)
        )
        assertEquals(pageClassName, locatedPage.className)
        assertNotNull(BrandSplashKntrAdapter.resolvePage(loader, locatedPage))

        val pageMethod2 = loader.loadClass("${pageClassName}2").declaredMethods.single { it.name == "a" }
        val ambiguousPageSession = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(listOf(pageMethod, pageMethod2)) },
            listOf("base.apk"),
            loader,
            setOf(BrandSplashKntrAdapter.queryPage)
        )
        assertNull(BrandSplashKntrAdapter.locatePage(loader, ambiguousPageSession, enabled = true, cached = null, planned = true))

        // Direct page route must still validate cached HookPoint and reject dirty/wrong cache
        val directPageLoader = RenamingLoader(
            baseLoader,
            templates + ("kntr.srcs.app.splash.brand.startup.c" to TemplateBrandPageHolder::class.java),
            replacements
        )
        assertNotNull(BrandSplashKntrAdapter.directPage(directPageLoader))
        assertNull(
            BrandSplashKntrAdapter.locatePage(
                directPageLoader,
                null,
                enabled = true,
                cached = HookPoint("WrongOwner", "wrong", listOf("a", "b")),
                planned = false
            )
        )
        assertEquals(
            "kntr.srcs.app.splash.brand.startup.c",
            BrandSplashKntrAdapter.locatePage(
                directPageLoader,
                null,
                enabled = true,
                cached = locatedPage.copy(className = "kntr.srcs.app.splash.brand.startup.c"),
                planned = false
            )?.className
        )

        // AdaptResult JSON round-trip & malformed fail-closed
        val base = VersionAdapterTest().result()
        val validResult = base.copy(brandSplashKntrExit = exitClassName, brandSplashKntrPage = locatedPage)
        val roundTripped = requireNotNull(AdaptResult.fromJson(JSONObject(validResult.toJson().toString())))
        assertEquals(exitClassName, roundTripped.brandSplashKntrExit)
        assertEquals(locatedPage, roundTripped.brandSplashKntrPage)
        assertNull(AdaptResult.fromJson(validResult.toJson().put("brand_splash_kntr_exit", JSONObject.NULL)))
        assertNull(AdaptResult.fromJson(validResult.toJson().put("brand_splash_kntr_exit", "foreign.pkg.Evil")))
        assertNull(AdaptResult.fromJson(validResult.toJson().put("brand_splash_kntr_page", JSONObject.NULL)))
        assertNull(
            AdaptResult.fromJson(
                validResult.toJson().put(
                    "brand_splash_kntr_page",
                    HookPoint(pageClassName, "a", listOf("a", "b")).toJson()
                )
            )
        )
    }

    // -------------------------------------------------------------------------
    // Target 2.4: HOME_TAB_RESOURCE
    // -------------------------------------------------------------------------

    fun `home tab resource adapter resolves generic-erased flattened item classes and fails closed on ambiguity`() {
        val loader = RenamingLoader(
            parent = baseLoader,
            templates = mapOf(
                HomeTabResourceAdapter.HOME_FRAGMENT_V2 to TemplateGenericErasedHomeFragmentV2::class.java
            ),
            replacements = emptyMap()
        )
        // Generic type was erased -> direct locateHomeTabs must return null
        assertNull(VersionAdapter.locateHomeTabs(loader))
        assertTrue(HomeTabResourceAdapter.applicable(loader))
        assertTrue(HomeTabResourceAdapter.needsQuery(loader, directFound = false, cached = null, enabled = true, attempted = false))

        val fragmentCls = loader.loadClass(HomeTabResourceAdapter.HOME_FRAGMENT_V2)
        val buildMethod = fragmentCls.declaredMethods.single { it.name == "buildTabs" }
        assertTrue(HomeTabResourceAdapter.verifiedBuildMethod(buildMethod))

        // Unique valid flattened item (weak <3 String item ignored)
        val session = DexAssistSession(
            DexAssistEngine {
                DexAssistResult.Candidates(
                    methods = listOf(buildMethod),
                    relatedClasses = mapOf(
                        buildMethod to listOf(
                            FlatPhase3WeakHomeTabItem::class.java,
                            FlatPhase3HomeTabItem::class.java
                        )
                    )
                )
            },
            listOf("base.apk"),
            loader,
            setOf(HomeTabResourceAdapter.query)
        )
        val located = requireNotNull(
            HomeTabResourceAdapter.locate(loader, session, enabled = true, direct = null, cached = null, planned = true)
        )
        assertEquals(FlatPhase3HomeTabItem::class.java.name, located.resourceClassName)
        assertEquals(located, HomeTabResourceAdapter.resolve(loader, located))
        assertFalse(HomeTabResourceAdapter.needsQuery(loader, directFound = false, cached = located, enabled = true, attempted = false))

        // Resolve rejects wrong field name or <3 string field class
        assertNull(HomeTabResourceAdapter.resolve(loader, located.copy(titleField = "nonExistent")))
        assertNull(HomeTabResourceAdapter.resolve(loader, located.copy(resourceClassName = FlatPhase3WeakHomeTabItem::class.java.name)))

        // Ambiguity between 2 valid >=3 String item classes fails closed
        val ambiguousSession = DexAssistSession(
            DexAssistEngine {
                DexAssistResult.Candidates(
                    methods = listOf(buildMethod),
                    relatedClasses = mapOf(
                        buildMethod to listOf(
                            FlatPhase3HomeTabItem::class.java,
                            FlatPhase3HomeTabItem2::class.java
                        )
                    )
                )
            },
            listOf("base.apk"),
            loader,
            setOf(HomeTabResourceAdapter.query)
        )
        assertNull(
            HomeTabResourceAdapter.locate(loader, ambiguousSession, enabled = true, direct = null, cached = null, planned = true)
        )

        // Shuffled semantic field order maps roles by name/annotation rather than getDeclaredFields() index,
        // and anchored classes win over noisy helper DTOs in relatedClasses.
        val shuffledPoints = requireNotNull(
            HomeTabResourceAdapter.buildPoints(buildMethod, FlatPhase3ShuffledHomeTabItem::class.java)
        )
        assertEquals("id", shuffledPoints.idField)
        assertEquals("title", shuffledPoints.titleField)
        assertEquals("uri", shuffledPoints.uriField)
        assertEquals("reporterId", shuffledPoints.reporterIdField)

        val anchoredSession = DexAssistSession(
            DexAssistEngine {
                DexAssistResult.Candidates(
                    classes = listOf(FlatPhase3ShuffledHomeTabItem::class.java),
                    methods = listOf(buildMethod),
                    relatedClasses = mapOf(
                        buildMethod to listOf(
                            FlatPhase3NoisyHomeDto::class.java,
                            FlatPhase3ShuffledHomeTabItem::class.java
                        )
                    )
                )
            },
            listOf("base.apk"),
            loader,
            setOf(HomeTabResourceAdapter.query)
        )
        assertEquals(
            shuffledPoints,
            HomeTabResourceAdapter.locate(loader, anchoredSession, enabled = true, direct = null, cached = null, planned = true)
        )

        // HomeTabFilterFeatureInstaller hot-retry installs legacy layer without duplicating already-installed frame hooks
        val tabRef = java.util.concurrent.atomic.AtomicReference<VersionAdapter.HomeTabPoints?>(null)
        val adaptedIds = mutableListOf<String>()
        val tabDelegate = PlayerPortTestRegistrar()
        val tabRegistrar = object : HookRegistrar by tabDelegate {
            override fun adapted(
                id: String,
                point: HookPoint,
                exceptionPolicy: HookExceptionPolicy,
                block: ModernMemberHookCreator.() -> Unit
            ) {
                adaptedIds += id
                tabDelegate.adapted(id, point, exceptionPolicy, block)
            }
        }
        val tabEnv = HookEnvironment(
            "tv.danmaku.bili", loader, HookPointRegistry(loader), tabRegistrar, { _, _ -> }, { _, _ -> }, { _, _ -> }
        )
        val tabInstaller = HomeTabFilterFeatureInstaller(
            rules = "关注",
            points = null,
            frameResolver = { HomeFrameTabLocator.Resolution.Absent },
            pointsProvider = { tabRef.get() }
        )
        assertTrue(tabInstaller.install(tabEnv) is FeatureInstallResult.Skipped)
        assertTrue(tabInstaller.requiresAdaptationRetry)
        tabRef.set(shuffledPoints)
        assertEquals(FeatureInstallResult.Installed(1), tabInstaller.install(tabEnv))
        assertFalse(tabInstaller.requiresAdaptationRetry)
        assertEquals(1, adaptedIds.count { it == "home.tabs.build" })
        assertEquals(FeatureInstallResult.Installed(1), tabInstaller.install(tabEnv))
        assertEquals(1, adaptedIds.count { it == "home.tabs.build" })
    }

    fun `phase 3 remediation installers support single-shot bounded hot retry without duplicate hooks`() {
        // 1. CommentReplyButtonFeatureInstaller on <3-family host clears requiresAdaptationRetry after one retry
        val replyInstaller = CommentReplyButtonFeatureInstaller(enabled = true) { emptyMap() }
        val replyLoader = RenamingLoader(
            parent = baseLoader,
            templates = emptyMap(),
            replacements = emptyMap(),
            hidden = setOf("com.bilibili.app.comm.comment2.phoenix.view.CommentReplyWidget")
        )
        val replyRegistrar = PlayerPortTestRegistrar()
        val replyEnv = HookEnvironment(
            "tv.danmaku.bili", replyLoader, HookPointRegistry(replyLoader), replyRegistrar, { _, _ -> }, { _, _ -> }, { _, _ -> }
        )
        replyInstaller.install(replyEnv)
        assertTrue(replyInstaller.requiresAdaptationRetry)
        replyInstaller.install(replyEnv)
        assertFalse(replyInstaller.requiresAdaptationRetry)

        // 2. CloudKeyFreezeFeatureInstaller retries newly discovered flattened FeatureFlagContract once without re-registering earlier hooks
        class TemplateOverrideFF {
            @Suppress("UNUSED_PARAMETER")
            fun get(key: String, def: Any?): Any? = def
        }
        val ckLoader = object : ClassLoader(baseLoader) {
            private val mappings = mapOf(
                CloudKeyFreezeAdapter.TYPED_CONTRACT_CLASS to Wave1TypedContractBase::class.java,
                CloudKeyFreezeAdapter.FEATURE_FLAG_CONTRACT_CLASS to Wave1FeatureFlagInterface::class.java,
                "com.bilibili.lib.blconfig.internal.OverrideFF" to TemplateOverrideFF::class.java
            )
            override fun loadClass(name: String, resolve: Boolean): Class<*> = mappings[name] ?: super.loadClass(name, resolve)
        }
        val contractRef = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val ckRegistrar = PlayerPortTestRegistrar()
        val ckEnv = HookEnvironment(
            "tv.danmaku.bili", ckLoader, HookPointRegistry(ckLoader), ckRegistrar, { _, _ -> }, { _, _ -> }, { _, _ -> }
        )
        val ckInstaller = CloudKeyFreezeFeatureInstaller(
            enabled = true,
            rules = "test_key=true",
            observe = false,
            cachedFeatureFlagContractProvider = { contractRef.get() }
        )
        val ckRun1 = ckInstaller.install(ckEnv) as FeatureInstallResult.Installed
        assertTrue(ckInstaller.requiresAdaptationRetry)
        assertEquals(0, ckRegistrar.hooks.keys.count { it.contains("contract-ff") })
        contractRef.set(FlatWave1CloudKeyContract::class.java.name)
        val ckRun2 = ckInstaller.install(ckEnv) as FeatureInstallResult.Installed
        assertFalse(ckInstaller.requiresAdaptationRetry)
        assertTrue(ckRun2.hookCount >= ckRun1.hookCount)
        assertTrue(ckRegistrar.hooks.keys.any { it.contains("contract-ff") })

        // 3. CommentPurifyFeatureInstaller retries quickReplyDialogMethods once without duplicating hooks
        val qrLoader = RenamingLoader(
            parent = baseLoader,
            templates = mapOf(
                "FlatPhase3QuickReplyCollector" to TemplateQuickReplyWithCommentOwner::class.java
            ),
            replacements = mapOf(
                TemplateBaseContinuation::class.java.name.replace('.', '/') to "kotlin/coroutines/Continuation"
            )
        )
        val qrPoint = HookPoint(
            "FlatPhase3QuickReplyCollector",
            "emit",
            listOf(PublishDialogIntent::class.java.name, "kotlin.coroutines.Continuation")
        )
        val purifyRef = java.util.concurrent.atomic.AtomicReference<VersionAdapter.CommentPurifyPoints?>(null)
        val purifyAdaptedIds = mutableListOf<String>()
        val purifyDelegate = PlayerPortTestRegistrar()
        val purifyRegistrar = object : HookRegistrar by purifyDelegate {
            override fun adapted(
                id: String,
                point: HookPoint,
                exceptionPolicy: HookExceptionPolicy,
                block: ModernMemberHookCreator.() -> Unit
            ) {
                purifyAdaptedIds += id
                purifyDelegate.adapted(id, point, exceptionPolicy, block)
            }
        }
        val purifyEnv = HookEnvironment(
            "tv.danmaku.bili", qrLoader, HookPointRegistry(qrLoader), purifyRegistrar, { _, _ -> }, { _, _ -> }, { _, _ -> }
        )
        val purifyInstaller = CommentPurifyFeatureInstaller(
            removeSearchLinks = false,
            removeEmptyGuide = false,
            removeVoteWidgets = false,
            removeFollowButtons = false,
            removeQoe = false,
            removeOperations = false,
            blockQuickReply = true,
            points = null,
            pointsProvider = { purifyRef.get() }
        )
        purifyInstaller.install(purifyEnv)
        assertTrue(purifyInstaller.requiresAdaptationRetry)
        purifyRef.set(
            VersionAdapter.CommentPurifyPoints(
                emptyList(), emptyList(), emptyList(), null, null, emptyList(),
                quickReplyDialogMethods = listOf(qrPoint)
            )
        )
        val qrPass2 = purifyInstaller.install(purifyEnv)
        assertTrue(qrPass2 is FeatureInstallResult.Installed)
        assertFalse(purifyInstaller.requiresAdaptationRetry)
        assertEquals(1, purifyAdaptedIds.count { it.startsWith("comment.purify.quick_reply") })
        purifyInstaller.install(purifyEnv)
        assertEquals(1, purifyAdaptedIds.count { it.startsWith("comment.purify.quick_reply") })
    }

    @Test
    fun verifyAllCases() {
        fun runIsolated(block: (Phase3Batch2AdaptersTest) -> Unit) {
            block(Phase3Batch2AdaptersTest())
        }
        runIsolated { it.`comment quick reply adapter resolves drifted and flattened collectors and rejects bad intent or ambiguity`() }
        runIsolated { it.`paused ad countdown supports flattened coroutine retry and pause apiEntry enables flattened request coverage`() }
        runIsolated { it.`brand splash kntr adapter resolves flattened exit and page composable and validates cache strictly`() }
        runIsolated { it.`home tab resource adapter resolves generic-erased flattened item classes and fails closed on ambiguity`() }
        runIsolated { it.`phase 3 remediation installers support single-shot bounded hot retry without duplicate hooks`() }
    }

    // -------------------------------------------------------------------------
    // Templates & Bytecode Renaming ClassLoader
    // -------------------------------------------------------------------------

    class TemplateBaseContinuation
    class BadPublishIntentSuffix
    open class TemplateBaseSuspendLambda
    open class TemplateBaseContImpl
    class TemplateBaseFunc2
    class TemplateKTabType
    class TemplateKPlayPauseReq
    class TemplateKPlayPauseReply {
        interface IData
        fun getBar(): TemplateKPauseBar = TemplateKPauseBar()
        fun getData(): IData? = null
    }
    class TemplateKPauseBar
    class TemplateKIData
    class TemplateComposer
    class TemplateModifier
    interface TemplateState
    interface TemplateMutableState : TemplateState

    class TemplateQuickReplyWithCommentOwner(
        @JvmField val owner: com.bilibili.app.comment3.ui.CommentContainerImpl? = null
    ) {
        @Suppress("UNUSED_PARAMETER")
        fun emit(intent: PublishDialogIntent, cont: TemplateBaseContinuation): Any = Unit
    }

    class TemplateQuickReplyUnrelated {
        @Suppress("UNUSED_PARAMETER")
        fun emit(intent: PublishDialogIntent, cont: TemplateBaseContinuation): Any = Unit
    }

    class TemplateQuickReplyBadIntent {
        @Suppress("UNUSED_PARAMETER")
        fun emit(intent: BadPhase3QuickReplyIntent, cont: TemplateBaseContinuation): Any = Unit
    }

    class TemplateQuickReplyMissingPosIntent {
        @Suppress("UNUSED_PARAMETER")
        fun emit(intent: BadPublishIntentSuffix, cont: TemplateBaseContinuation): Any = Unit
    }

    class TemplateJobKt {
        companion object {
            @JvmStatic
            fun ensureActive(context: PauseCountdownAdapterTest.Context) {
                if (!context.active) throw CancellationException("cancelled")
            }
        }
    }

    class TemplatePausedService(@JvmField var source: PauseCountdownAdapterTest.Source?)
    class TemplateSuspendLambda : TemplateBaseSuspendLambda() {
        fun invokeSuspend(value: Any?): Any? = Unit
    }
    class TemplateContinuationImpl : TemplateBaseContImpl() {
        fun invokeSuspend(value: Any?): Any? = Unit
    }
    class TemplateFlattenedCountdown(
        @JvmField val `this$0`: TemplatePausedService,
        @JvmField var report: PauseCountdownAdapterTest.Reportable? = null
    ) {
        @JvmField var label = 0
        val context = PauseCountdownAdapterTest.Context()
        fun invokeSuspend(value: Any?): Any? = Unit
    }
    class TemplateFlattenedApiOwner {
        @Suppress("UNUSED_PARAMETER")
        fun x(
            a1: Long, a2: Long, a3: TemplateKTabType, a4: String, a5: Int,
            a6: Long, a7: Long, a8: Int, a9: Int, a10: Boolean,
            a11: List<*>, a12: Int, a13: String, a14: String,
            a15: TemplateBaseFunc2, a16: TemplateBaseContImpl
        ): Any = Unit
    }
    class TemplateKViewMoss {
        @Suppress("UNUSED_PARAMETER")
        fun playPause(req: TemplateKPlayPauseReq, cont: TemplateBaseContinuation): Any = Unit
    }

    class TemplateCurrentExit(@JvmField val `$onExit`: () -> Unit) {
        @JvmField var label = 0
        @JvmField var `$customBitmapReady$delegate`: TemplateMutableState? = null
        @JvmField var `$customLogoReady$delegate`: TemplateMutableState? = null
        @JvmField var `$customBitmap$delegate`: TemplateMutableState? = null
        @JvmField var `$currentOnShow`: TemplateState? = null
        fun invokeSuspend(value: Any?): Any? = value
    }

    class TemplateBrokenExit(@JvmField val `$onExit`: () -> Unit) {
        @JvmField var label = 0
        @JvmField var `$customLogoReady$delegate`: TemplateMutableState? = null
        @JvmField var `$currentOnShow`: TemplateState? = null
        fun invokeSuspend(value: Any?): Any? = value
    }

    object TemplateBrandPageHolder {
        @Suppress("UNUSED_PARAMETER")
        @JvmStatic
        fun a(
            model: FlatPhase3BrandModel,
            onExit: () -> Unit,
            modifier: TemplateModifier,
            onLogoClick: () -> Unit,
            composer: TemplateComposer,
            flags: Int
        ) = Unit
    }

    class TemplateGenericErasedHomeFragmentV2 {
        fun buildTabs(raw: List<*>): List<Any> = raw.filterNotNull()
    }

    private class RenamingLoader(
        parent: ClassLoader,
        private val templates: Map<String, Class<*>>,
        private val replacements: Map<String, String>,
        private val hidden: Set<String> = emptySet()
    ) : ClassLoader(parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(this) {
            if (name in hidden) throw ClassNotFoundException(name)
            val template = templates[name] ?: return@synchronized super.loadClass(name, resolve)
            val loaded = findLoadedClass(name) ?: run {
                val raw = requireNotNull(template.getResourceAsStream("/${template.name.replace('.', '/')}.class"))
                    .use { it.readBytes() }
                val selfInternal = template.name.replace('.', '/')
                val targetInternal = name.replace('.', '/')
                val fullMap = replacements + (selfInternal to targetInternal)
                val rewritten = rewrite(raw, fullMap)
                defineClass(name, rewritten, 0, rewritten.size)
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
