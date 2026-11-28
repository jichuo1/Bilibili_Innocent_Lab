package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import FlatPhase3BadPlaylistDirector
import FlatPhase3FollowUpBodyTheseus
import FlatPhase3FollowUpBodyTheseus2
import FlatPhase3FollowUpBodyTheseus2Dup
import FlatPhase3PlaylistDirector
import FlatPhase3PlaylistDirector2
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapterTest
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistEngine
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.AiDeclaredVideoFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.AiPlaylistSkipper
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstallResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FollowUpRecommendFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerEndPageRecommendLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPortTestRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.testsupport.TestRenamingClassLoader
import com.bilibili.lib.moss.api.MossResponseHandler
import com.bilibili.search2.discover.RenamedDelivery
import com.bilibili.search2.discover.WrongDelivery
import com.bilibili.search2.main.data.RenamedState
import com.bilibili.search2.main.data.WrongState
import com.bilibili.ship.theseus.ugc.endpage.relatedrecommand.OtherMerger
import com.bilibili.ship.theseus.ugc.endpage.relatedrecommand.RenamedMerger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicReference

class PageBoundaryAdapterTest {
    private val baseLoader = javaClass.classLoader!!

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

    @Test
    fun pageBoundaryAiPlaylistDirectorAndFollowUpRecommendAdapters() {
        runPageBoundaryCases()
        runAiPlaylistDirectorCases()
        runFollowUpRecommendCases()
    }

    private fun runPageBoundaryCases() {
        val loader = object : ClassLoader(baseLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name in setOf("com.bilibili.search2.discover.q", "com.bilibili.search2.discover.y",
                    "com.bilibili.search2.discover.z", "com.bilibili.search2.main.data.i")) throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }
        val history = RenamedDelivery::class.java.getDeclaredMethod("getHistoryList")
        val merge = RenamedMerger::class.java.getDeclaredMethod("merge", List::class.java, List::class.java)
        fun session(results: Map<DexAssistQuery, DexAssistResult>, planned: Set<DexAssistQuery> = results.keys) =
            DexAssistSession(DexAssistEngine { results[it.query] ?: error("unplanned query") }, listOf("base.apk"), loader, planned)

        // renamedOwnersOutsideTheOldAlphabetResolveThroughTypedProductionLocators
        val queries = setOf(DexAssistQuery.SEARCH_HOME_DELIVERY, DexAssistQuery.SEARCH_HOME_STATE, DexAssistQuery.PLAYER_END_PAGE_MERGER)
        val points = requireNotNull(PageBoundaryAdapter.locate(loader, null, session(mapOf(
            DexAssistQuery.SEARCH_HOME_DELIVERY to DexAssistResult.Candidates(listOf(history)),
            DexAssistQuery.SEARCH_HOME_STATE to DexAssistResult.Candidates(emptyList(), listOf(RenamedState::class.java)),
            DexAssistQuery.PLAYER_END_PAGE_MERGER to DexAssistResult.Candidates(listOf(merge))
        )), queries))
        val search = requireNotNull(SearchHomeRecommendLocator.locate(loader, points))
        assertEquals(RenamedDelivery::class.java, search.delivery!!.method.declaringClass)
        assertNotNull(search.delivery.refresh); assertNotNull(search.delivery.cache)
        assertEquals(RenamedState::class.java, search.state!!.constructor.declaringClass)
        assertEquals(merge, PlayerEndPageRecommendLocator.cachedMerger(loader, points.endPageMerger))
        assertTrue(Modifier.isStatic(merge.modifiers))
        assertEquals(points, PageBoundaryPoints.fromJson(points.toJson()))

        // erasedListDecoysAndMultipleOwnersNeverBecomeHooks
        val wrong = WrongDelivery::class.java.getDeclaredMethod("getHistoryList")
        val candidates = session(mapOf(
            DexAssistQuery.SEARCH_HOME_DELIVERY to DexAssistResult.Candidates(listOf(wrong)),
            DexAssistQuery.SEARCH_HOME_STATE to DexAssistResult.Candidates(emptyList(), listOf(WrongState::class.java)),
            DexAssistQuery.PLAYER_END_PAGE_MERGER to DexAssistResult.Candidates(listOf(merge,
                OtherMerger::class.java.getDeclaredMethod("merge", List::class.java, List::class.java)))
        ))
        assertNull(PageBoundaryAdapter.locate(loader, null, candidates, setOf(DexAssistQuery.SEARCH_HOME_DELIVERY, DexAssistQuery.SEARCH_HOME_STATE, DexAssistQuery.PLAYER_END_PAGE_MERGER)))

        // disabledAndPreviouslyAttemptedQueriesNeverLoadNativeOrOpenAnUnplannedSession
        val trap = object : ClassLoader(loader) { override fun loadClass(name: String, resolve: Boolean): Class<*> = error("disabled class load") }
        assertTrue(PageBoundaryAdapter.planned(trap, null, false, false, emptySet()).isEmpty())
        val expected = setOf(DexAssistQuery.SEARCH_HOME_DELIVERY, DexAssistQuery.SEARCH_HOME_STATE)
        assertEquals(expected, PageBoundaryAdapter.planned(loader, null, true, false, emptySet()))
        assertTrue(PageBoundaryAdapter.planned(loader, null, true, false, expected).isEmpty())
        val unopened = DexAssistSession(DexAssistEngine { error("unplanned native query") }, emptyList(), loader, emptySet())
        assertNull(PageBoundaryAdapter.locate(loader, null, unopened, emptySet()))

        // storedNamesAreRevalidatedAndMalformedCacheCannotExpandThePackageScope
        val invalidPoints = PageBoundaryPoints(WrongDelivery::class.java.name, WrongState::class.java.name,
            VersionAdapter.HookPoint(RenamedMerger::class.java.name, "missing", listOf("java.util.List", "java.util.List")))
        assertNull(PageBoundaryAdapter.locate(loader, invalidPoints, null, emptySet()))
        assertNull(PageBoundaryPoints.fromJson(JSONObject().put("search_delivery", "java.lang.String")))
        assertNull(PageBoundaryPoints.fromJson(JSONObject().put("card_surfaces", JSONArray())))
        assertFalse(PageBoundaryPoints(endPageMerger = VersionAdapter.HookPoint("java.lang.String", "x", emptyList())).isValid())

        // relocatedSearchNamesAreRecognizedWithoutAcceptingUnrelatedOrForeignOwners
        val relocated = PageBoundaryPoints(searchDelivery = "fnc2", searchState = "ode2")
        assertEquals(relocated, PageBoundaryPoints.fromJson(relocated.toJson()))
        assertNull(PageBoundaryAdapter.locate(loader, relocated, null, emptySet()))
        assertFalse(PageBoundaryPoints(searchDelivery = "java.lang.String", searchState = "ode2").isValid())
        assertFalse(PageBoundaryPoints(searchDelivery = "fnc2", searchState = "foreign.pkg.State").isValid())
    }

    private fun runAiPlaylistDirectorCases() {
        val templates = linkedMapOf<String, Class<*>>()
        fun registerTree(sourcePrefix: String, targetPrefix: String, roots: List<Class<*>>) {
            val queue = ArrayDeque(roots)
            while (queue.isNotEmpty()) {
                val cls = queue.removeFirst()
                val rel = cls.name.removePrefix(sourcePrefix)
                templates[targetPrefix + rel] = cls
                cls.declaredClasses.forEach { queue.addLast(it) }
            }
        }
        registerTree(
            "aistub.v1.",
            "com.bapis.bilibili.app.viewunite.v1.",
            listOf(
                aistub.v1.Arc::class.java,
                aistub.v1.ECode::class.java,
                aistub.v1.ECodeConfig::class.java,
                aistub.v1.IntroductionTab::class.java,
                aistub.v1.RelatesFeedReply::class.java,
                aistub.v1.Tab::class.java,
                aistub.v1.TabModule::class.java,
                aistub.v1.ViewReply::class.java
            )
        )
        registerTree(
            "aistub.common.",
            "com.bapis.bilibili.app.viewunite.common.",
            listOf(
                aistub.common.CardBasicInfo::class.java,
                aistub.common.Module::class.java,
                aistub.common.Neutral::class.java,
                aistub.common.Owner::class.java,
                aistub.common.RelateCard::class.java,
                aistub.common.RelateCardType::class.java,
                aistub.common.Relates::class.java,
                aistub.common.UgcIntroduction::class.java
            )
        )
        templates += mapOf(
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
            "com.bapis.bilibili.app.viewunite.v1.ViewMoss" to TemplateAiViewMoss::class.java,
            "com.bapis.bilibili.app.viewunite.v1.ViewReq" to TemplateAiViewReq::class.java
        )
        val loader = TestRenamingClassLoader(
            parent = baseLoader,
            templates = templates,
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

        val ambiguousSession = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(methods = emptyList(), classes = listOf(directorClass, director2Class)) },
            listOf("base.apk"),
            loader,
            setOf(AiPlaylistDirectorAdapter.query)
        )
        assertNull(AiPlaylistDirectorAdapter.locate(loader, ambiguousSession, enabled = true, cached = null, planned = true))

        val baseJson = VersionAdapterTest().result().copy(aiPlaylistDirector = located).toJson()
        val restored = requireNotNull(AdaptResult.fromJson(JSONObject(baseJson.toString())))
        assertEquals("FlatPhase3PlaylistDirector", restored.aiPlaylistDirector)
        assertNull(AdaptResult.fromJson(JSONObject(baseJson.toString()).put("ai_playlist_director", JSONObject.NULL)))
        assertNull(AdaptResult.fromJson(JSONObject(baseJson.toString()).put("ai_playlist_director", "evil.pkg.Director")))

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

    private fun runFollowUpRecommendCases() {
        val theseus2Repo = FollowUpRecommendFeatureInstaller.Family.THESEUS2.repository
        val theseusRepo = FollowUpRecommendFeatureInstaller.Family.THESEUS.repository
        val loader = TestRenamingClassLoader(
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

        val baseJson = VersionAdapterTest().result().copy(followUpRecommendBodies = located).toJson()
        val restored = requireNotNull(AdaptResult.fromJson(JSONObject(baseJson.toString())))
        assertEquals(located, restored.followUpRecommendBodies)
        assertNull(AdaptResult.fromJson(JSONObject(baseJson.toString()).put("follow_up_recommend_bodies", JSONObject().put("unknown_family", "a"))))
        assertNull(AdaptResult.fromJson(JSONObject(baseJson.toString()).put("follow_up_recommend_bodies", JSONObject().put("theseus2", "evil.pkg.Body"))))

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

        installer.install(env)
        assertEquals(2, registrar.registrationAttempts.count { it.first.startsWith("follow.up_recommend.request") })
    }

    class TemplateB3Continuation
    class TemplateAiViewReq {
        fun getSpmid(): String = "dt.0.0.0"
    }
    class TemplateAiViewMoss {
        @Suppress("UNUSED_PARAMETER")
        fun executeView(req: TemplateAiViewReq): aistub.v1.ViewReply = aistub.v1.ViewReply.getDefaultInstance()

        @Suppress("UNUSED_PARAMETER")
        fun view(req: TemplateAiViewReq, handler: MossResponseHandler) {
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
}
