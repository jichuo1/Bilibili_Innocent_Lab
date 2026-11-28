package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import FlatWave1AdvertFloat
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.PausePoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerPopupPromotionAdapter.Family
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistEngine
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistRequest
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstallResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PauseCountdownAdapterTest
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PauseRequestCoverage
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PauseResponseInstallerTest
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PausedAdFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPortTestRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.testsupport.TestRenamingClassLoader
import com.bilibili.adcommon.data.model.OtherPopupAdvert
import com.bilibili.adcommon.data.model.RenamedPopupAdvert
import com.bilibili.bson.common.PojoPropertyDescriptor
import com.bilibili.ship.theseus.ogv.activity.OgvActivityHalfScreenPopup
import com.bilibili.ship.theseus.ogv.activity.OgvActivityVo_JsonDescriptor
import com.bilibili.ship.theseus.united.player.mediaplay.network.PopupAdapterShapes
import com.bilibili.ship.theseus.united.player.mediaplay.network.PromoNetworkServiceFixture
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

class PopupAdaptersAndLocatorsTest {
    private val loader = javaClass.classLoader!!

    @Test
    fun pgcPopupPlayerPromotionAndPausedAdAdapters() {
        runPgcAutoActivityPopupCases()
        runPlayerPopupPromotionCases()
        runPausedAdCountdownAndRequestApiCases()
    }

    private fun runPgcAutoActivityPopupCases() {
        val popup = OgvActivityHalfScreenPopup::class.java
        fun property(name: String, type: Class<*>, nonNull: Boolean = false) =
            PgcAutoActivityPopupLocator.Property(name, type, nonNull)

        // locates typed constructor and renamed descriptor field without a model getter
        val point = requireNotNull(VersionAdapter.locatePgcAutoActivityPopup(loader))
        assertEquals(7, point.popupIndex)
        assertEquals(9, point.constructorParameters.size)
        assertEquals("shiftedName", point.propertiesField)
        val runtime = requireNotNull(PgcAutoActivityPopupLocator.resolveRuntime(loader, point))
        assertEquals(point.popupIndex, runtime.popupIndex)
        assertEquals("half", runtime.popupField.name)

        // a reordered nullable half slot follows the property and parameter contract
        for (index in 0..8) {
            val parameters = MutableList<Class<*>>(9) { String::class.java }
            parameters[index] = popup
            val properties = parameters.mapIndexed { i, type ->
                property(if (i == index) "play_half_container" else "other_$i", type)
            }
            assertEquals(index, PgcAutoActivityPopupLocator.uniquePopupIndex(parameters, popup))
            assertTrue(PgcAutoActivityPopupLocator.matchesNullableHalf(parameters, popup, properties))
        }

        // rejects missing duplicate renamed required or mismatched half fields
        val parameters = listOf(String::class.java, popup)
        val valid = listOf(property("other", String::class.java), property("play_half_container", popup))
        val invalid = listOf(
            valid.dropLast(1),
            listOf(property("play_half_container", String::class.java), valid[1]),
            listOf(valid[0], property("different_popup", popup)),
            listOf(valid[0], property("play_half_container", popup, nonNull = true)),
            listOf(property("other", Int::class.java), valid[1]),
            valid.reversed()
        )
        invalid.forEach { assertFalse(PgcAutoActivityPopupLocator.matchesNullableHalf(parameters, popup, it)) }
        assertNull(PgcAutoActivityPopupLocator.uniquePopupIndex(listOf(popup, popup), popup))
        assertNull(PgcAutoActivityPopupLocator.uniquePopupIndex(listOf(String::class.java), popup))

        // cached slot and nullable semantics are revalidated before registration
        assertNull(PgcAutoActivityPopupLocator.resolveRuntime(loader, point.copy(popupIndex = 0)))
        val properties = OgvActivityVo_JsonDescriptor.shiftedName
        val original = properties[7]
        try {
            properties[7] = PojoPropertyDescriptor("play_half_container", popup, 5)
            assertNull(PgcAutoActivityPopupLocator.resolveRuntime(loader, point))
        } finally {
            properties[7] = original
        }

        // missing descriptor does not fall back to an unverified model constructor
        val hidden = object : ClassLoader(loader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name.endsWith("OgvActivityVo_JsonDescriptor")) throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }
        assertNull(VersionAdapter.locatePgcAutoActivityPopup(hidden))

        // cache round trip preserves shape and rejects an invalid or duplicated index
        val restored = VersionAdapter.PgcAutoActivityPopupPoints.fromJson(point.toJson())
        assertEquals(point, restored)
        assertTrue(restored.isValid())
        assertFalse(point.copy(popupIndex = -1).isValid())
        assertFalse(point.copy(popupIndex = 0).isValid())
        assertFalse(point.copy(propertiesField = "").isValid())
        assertFalse(point.copy(constructorParameters = point.constructorParameters + popup.name).isValid())
    }

    private fun runPlayerPopupPromotionCases() {
        val guide = PromoNetworkServiceFixture::class.java.declaredMethods.single { it.name == "f" }
        val advert = RenamedPopupAdvert::class.java.declaredMethods.single { it.name == "floats" }
        fun hidden() = object : ClassLoader(loader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == PlayerPopupPromotionAdapter.GUIDE_CONTINUATION || name == PlayerPopupPromotionAdapter.ADVERT_CLASS)
                    throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }

        // stable continuation derives only its unique validated guide owner
        assertEquals(guide, PlayerPopupPromotionAdapter.directFreeDataGuide(loader))
        val ambiguous = object : ClassLoader(loader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> =
                if (name == PlayerPopupPromotionAdapter.GUIDE_CONTINUATION) PopupAdapterShapes.AmbiguousAnchor::class.java
                else super.loadClass(name, resolve)
        }
        assertNull(PlayerPopupPromotionAdapter.directFreeDataGuide(ambiguous))
        assertFalse(PlayerPopupPromotionAdapter.verifiedFreeDataGuide(PopupAdapterShapes.Broken::class.java.declaredMethods.single()))
        assertNull(PlayerPopupPromotionAdapter.resolveFreeDataGuide(loader, null))

        // disabled and complete direct paths never invoke the dex engine while missing families share a pass
        var batches = 0
        var planned: Set<DexAssistQuery> = emptySet()
        val engine = object : DexAssistEngine {
            override fun resolve(request: DexAssistRequest): DexAssistResult = error("unexpected individual query")
            override fun resolveAll(queries: Set<DexAssistQuery>, codePaths: List<String>, classLoader: ClassLoader): Map<DexAssistQuery, DexAssistResult> {
                batches++; planned = queries
                return queries.associateWith { DexAssistResult.Candidates(listOf(if (it == Family.FREE_DATA_GUIDE.query) guide else advert)) }
            }
        }
        val queries = Family.entries.map { it.query }.toSet()
        val session = DexAssistSession(engine, listOf("base.apk"), loader, queries)
        PlayerPopupPromotionAdapter.locate(loader, session, true)
        assertEquals(0, batches)
        val missing = hidden()
        val fallback = DexAssistSession(engine, listOf("base.apk"), missing, queries)
        PlayerPopupPromotionAdapter.locate(missing, fallback, false)
        assertEquals(0, batches)
        val points = PlayerPopupPromotionAdapter.locate(missing, fallback, true)
        assertEquals(1, batches); assertEquals(queries, planned)
        assertEquals(guide, PlayerPopupPromotionAdapter.resolveFreeDataGuide(missing, points.freeDataGuide))
        assertEquals(advert, PlayerPopupPromotionAdapter.resolveAdvertFloat(missing, points.advertFloat))
        assertTrue(PlayerPopupPromotionAdapter.missing(missing, points).isEmpty())

        // multiple owners wrong signatures and too many candidates are rejected
        fun selected(methods: List<java.lang.reflect.Method>, family: Family): PlayerPopupPromotionPoints =
            PlayerPopupPromotionAdapter.locate(hidden(), DexAssistSession(DexAssistEngine { request ->
                if (request.query == family.query) DexAssistResult.Candidates(methods)
                else DexAssistResult.Unavailable(DexAssistResult.Reason.NO_MATCH)
            }, listOf("base.apk"), hidden(), Family.entries.map { it.query }.toSet()), true)
        assertNull(selected(listOf(guide, PopupAdapterShapes.Other::class.java.declaredMethods.single()), Family.FREE_DATA_GUIDE).freeDataGuide)
        assertNull(selected(listOf(advert, OtherPopupAdvert::class.java.declaredMethods.single()), Family.ADVERT_FLOAT).advertFloat)
        assertNull(selected(RenamedPopupAdvert::class.java.declaredMethods.filter { it.name == "wrong" }, Family.ADVERT_FLOAT).advertFloat)
        assertNull(selected(List(33) { guide }, Family.FREE_DATA_GUIDE).freeDataGuide)
        assertEquals(PlayerPopupPromotionAdapter.point(guide), selected(listOf(guide, guide), Family.FREE_DATA_GUIDE).freeDataGuide)

        // old continuation ABI accepts only the unique semantic candidate and rejects its split helpers
        val old = PopupAdapterShapes.Old::class.java.declaredMethods
        val flow = old.single { it.name == "r" }
        val unrelatedFlow = old.single { it.name == "q" }
        assertTrue(PlayerPopupPromotionAdapter.verifiedFreeDataGuide(flow))
        assertTrue(PlayerPopupPromotionAdapter.verifiedFreeDataGuide(unrelatedFlow))
        for (helper in old.filter { it.name in listOf("counter", "frequency", "display") })
            assertFalse(PlayerPopupPromotionAdapter.verifiedFreeDataGuide(helper))
        fun locateFlow(candidates: List<java.lang.reflect.Method>) = PlayerPopupPromotionAdapter.locate(hidden(),
            DexAssistSession(DexAssistEngine { request ->
                if (request.query == Family.FREE_DATA_GUIDE.query) DexAssistResult.Candidates(candidates)
                else DexAssistResult.Unavailable(DexAssistResult.Reason.NO_MATCH)
            }, listOf("base.apk"), hidden(), Family.entries.map { it.query }.toSet()), true)
        assertEquals(PlayerPopupPromotionAdapter.point(flow), locateFlow(listOf(flow)).freeDataGuide)
        assertNull(locateFlow(listOf(flow, unrelatedFlow)).freeDataGuide)

        // another family or feature refreshing never reopens a previously failed query
        val attempted = setOf(Family.FREE_DATA_GUIDE)
        val partialPlanned = PlayerPopupPromotionAdapter.planned(missing, null, attempted, true)
        assertEquals(setOf(Family.ADVERT_FLOAT), partialPlanned)
        val requests = mutableListOf<DexAssistQuery>()
        val partialSession = DexAssistSession(DexAssistEngine { request ->
            requests += request.query
            assertEquals(Family.ADVERT_FLOAT.query, request.query)
            DexAssistResult.Candidates(listOf(advert))
        }, listOf("base.apk"), missing, partialPlanned.map { it.query }.toSet())
        val partialPoints = PlayerPopupPromotionAdapter.locate(missing, partialSession, true, null, partialPlanned)
        assertNull(partialPoints.freeDataGuide)
        assertEquals(PlayerPopupPromotionAdapter.point(advert), partialPoints.advertFloat)
        assertEquals(listOf(Family.ADVERT_FLOAT.query), requests)
        val allAttempted = PlayerPopupPromotionAdapter.planned(missing, null, Family.entries.toSet(), true)
        assertTrue(allAttempted.isEmpty())
        PlayerPopupPromotionAdapter.locate(missing, DexAssistSession(DexAssistEngine { error("unplanned query") },
            listOf("base.apk"), missing, emptySet()), true, null, allAttempted)

        // verified cached families produce zero queries during another feature refresh
        val cached = PlayerPopupPromotionPoints(PlayerPopupPromotionAdapter.point(guide), PlayerPopupPromotionAdapter.point(advert))
        val cachedPlanned = PlayerPopupPromotionAdapter.planned(missing, cached, emptySet(), true)
        assertTrue(cachedPlanned.isEmpty())
        val noScanSession = DexAssistSession(DexAssistEngine { error("cached member triggered DEX") }, listOf("base.apk"), missing, emptySet())
        assertEquals(cached, PlayerPopupPromotionAdapter.locate(missing, noScanSession, true, cached, cachedPlanned))
        assertTrue(PlayerPopupPromotionAdapter.planned(missing, cached, emptySet(), false).isEmpty())
        val invalid = cached.copy(freeDataGuide = cached.freeDataGuide!!.copy(methodName = "missing"))
        assertEquals(setOf(Family.FREE_DATA_GUIDE), PlayerPopupPromotionAdapter.planned(missing, invalid, emptySet(), true))
        assertTrue(PlayerPopupPromotionAdapter.planned(missing, invalid, setOf(Family.FREE_DATA_GUIDE), true).isEmpty())

        // disabled feature retains pure previous points without loading a single host class
        var loads = 0
        val trap = object : ClassLoader(loader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                loads++
                throw ClassNotFoundException(name)
            }
        }
        assertSame(cached, PlayerPopupPromotionAdapter.locate(trap, null, false, cached, emptySet()))
        assertTrue(PlayerPopupPromotionAdapter.planned(trap, cached, Family.entries.toSet(), false).isEmpty())
        assertEquals(0, loads)
        assertEquals(PlayerPopupPromotionPoints(), PlayerPopupPromotionAdapter.locate(trap, null, false))
        assertEquals(0, loads)

        // optional cache is bounded validated and merged independently for each family
        val guidePoint = PlayerPopupPromotionAdapter.point(guide)
        val floatPoint = PlayerPopupPromotionAdapter.point(advert)
        val fullPoints = PlayerPopupPromotionPoints(guidePoint, floatPoint)
        assertEquals(fullPoints, PlayerPopupPromotionPoints.fromJson(fullPoints.toJson()))
        assertEquals(PlayerPopupPromotionPoints(), PlayerPopupPromotionPoints.fromJson(JSONObject()))
        assertNull(PlayerPopupPromotionPoints.fromJson(JSONObject().put("free_data_guide", "bad")))
        assertNull(PlayerPopupPromotionPoints.fromJson(PlayerPopupPromotionPoints(guidePoint.copy(paramClassNames = emptyList())).toJson()))
        assertNull(PlayerPopupPromotionPoints.fromJson(PlayerPopupPromotionPoints(advertFloat = floatPoint.copy(paramClassNames = listOf("java.lang.String"))).toJson()))
        assertNull(PlayerPopupPromotionPoints.fromJson(PlayerPopupPromotionPoints(guidePoint.copy(methodName = "x".repeat(257))).toJson()))
        for (bad in listOf("bad/name", "bad;name", "bad(name", "bad name", "bad\u0000name"))
            assertNull(PlayerPopupPromotionPoints.fromJson(PlayerPopupPromotionPoints(guidePoint.copy(methodName = bad)).toJson()))
        for (bad in listOf("${PlayerPopupPromotionAdapter.NETWORK_PACKAGE}.Bad/Class", "com.bilibili.ship.theseus.united.player.mediaplay.network..Bad", "com.bilibili.ship.theseus.united.player.mediaplay.network.1Bad"))
            assertNull(PlayerPopupPromotionPoints.fromJson(PlayerPopupPromotionPoints(guidePoint.copy(className = bad)).toJson()))
        assertEquals(fullPoints, PlayerPopupPromotionAdapter.merge(PlayerPopupPromotionPoints(guidePoint), PlayerPopupPromotionPoints(advertFloat = floatPoint)))
        assertNull(PlayerPopupPromotionAdapter.resolveFreeDataGuide(loader, guidePoint.copy(methodName = "missing")))
        assertNull(PlayerPopupPromotionAdapter.resolveAdvertFloat(loader, floatPoint.copy(paramClassNames = listOf("int"))))

        val flatFloat = FlatWave1AdvertFloat::class.java.declaredMethods.single { it.name == "a" }
        assertTrue(PlayerPopupPromotionAdapter.verifiedAdvertFloat(flatFloat))
        val flatPoints = PlayerPopupPromotionPoints(advertFloat = PlayerPopupPromotionAdapter.point(flatFloat))
        assertEquals(flatPoints, PlayerPopupPromotionPoints.fromJson(flatPoints.toJson()))
        assertEquals(flatFloat, PlayerPopupPromotionAdapter.resolveAdvertFloat(loader, flatPoints.advertFloat))
    }

    private fun runPausedAdCountdownAndRequestApiCases() {
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
        val renamedLoader = TestRenamingClassLoader(
            loader,
            templates,
            replacements,
            hiddenNames = setOf(
                PausedAdDexAdapter.DEFAULT_COUNTDOWN,
                "${adPkg}a",
                "${adPkg}b",
                "com.bapis.bilibili.app.viewunite.v1.PlayPauseReply"
            )
        )

        val countdownMethod = renamedLoader.loadClass("FlatPausedCountdownOwner").declaredMethods.single { it.name == "invokeSuspend" }
        val decoyMethod = renamedLoader.loadClass("FlatPausedDecoyOwner").declaredMethods.single { it.name == "invokeSuspend" }
        assertTrue(PausedAdDexAdapter.applicableCountdown(renamedLoader))
        assertNull(PausedAdDexAdapter.directCountdown(renamedLoader))
        assertTrue(PausedAdDexAdapter.needsCountdownQuery(renamedLoader, null, null, enabled = true, attempted = false))

        val countdownSession = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(listOf(countdownMethod)) },
            listOf("base.apk"),
            renamedLoader,
            setOf(PausedAdDexAdapter.queryCountdown)
        )
        val locatedCountdown = requireNotNull(
            PausedAdDexAdapter.locateCountdown(renamedLoader, countdownSession, enabled = true, direct = null, cached = null, planned = true)
        )
        assertEquals("FlatPausedCountdownOwner", locatedCountdown.className)
        assertEquals(locatedCountdown, PausedAdDexAdapter.resolveCountdown(renamedLoader, locatedCountdown))
        assertFalse(PausedAdDexAdapter.needsCountdownQuery(renamedLoader, null, locatedCountdown, enabled = true, attempted = false))

        val ambiguousCountdownSession = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(listOf(countdownMethod, decoyMethod)) },
            listOf("base.apk"),
            renamedLoader,
            setOf(PausedAdDexAdapter.queryCountdown)
        )
        assertNull(
            PausedAdDexAdapter.locateCountdown(renamedLoader, ambiguousCountdownSession, enabled = true, direct = null, cached = null, planned = true)
        )

        val apiMethod = renamedLoader.loadClass("FlatPausedApiOwner").declaredMethods.single { it.name == "x" }
        assertTrue(PausedAdDexAdapter.applicableRequestApi(renamedLoader))
        assertTrue(PausedAdDexAdapter.needsRequestApiQuery(renamedLoader, 9220100, null, enabled = true, attempted = false))
        val apiSession = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(listOf(apiMethod)) },
            listOf("base.apk"),
            renamedLoader,
            setOf(PausedAdDexAdapter.queryRequestApi)
        )
        val locatedApi = requireNotNull(
            PausedAdDexAdapter.locateRequestApi(renamedLoader, 9220100, apiSession, enabled = true, cached = null, planned = true)
        )
        assertEquals("FlatPausedApiOwner", locatedApi.className)
        assertFalse(PauseRequestCoverage.hasVerifiedApiEntry(renamedLoader, 9220100, cachedApiEntry = null))
        assertTrue(PauseRequestCoverage.hasVerifiedApiEntry(renamedLoader, 9220100, cachedApiEntry = locatedApi))
        assertEquals(
            setOf("ad-api"),
            PauseRequestCoverage.covered(renamedLoader, 9220100, setOf("ad-api"), kotlinInstalled = true, cachedApiEntry = locatedApi)
        )
        assertTrue(PauseRequestCoverage.covered(renamedLoader, 9220100, setOf("ad-api"), kotlinInstalled = true, cachedApiEntry = null).isEmpty())

        val pointsWithApi = PausePoints(emptyList(), null, null, locatedCountdown, locatedApi)
        val restored = PausePoints.fromJson(JSONObject(pointsWithApi.toJson().toString()))
        assertEquals(pointsWithApi, restored)
        assertNull(PausePoints.fromJson(JSONObject(pointsWithApi.toJson().toString()).apply { remove("api_entry") }).apiEntry)

        val registrar = PlayerPortTestRegistrar()
        val env = HookEnvironment(
            "tv.danmaku.bili",
            renamedLoader,
            HookPointRegistry(renamedLoader),
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
        val apiOnlyLoader = TestRenamingClassLoader(
            loader,
            apiOnlyTemplates,
            apiOnlyReplacements,
            hiddenNames = setOf(PausedAdDexAdapter.DEFAULT_COUNTDOWN, "${adPkg}a", "${adPkg}b")
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

    class TemplateBaseContinuation
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
}
