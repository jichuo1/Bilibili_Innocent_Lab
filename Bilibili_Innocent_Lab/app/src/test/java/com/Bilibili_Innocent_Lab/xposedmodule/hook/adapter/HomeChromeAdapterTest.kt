package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import FlatPhase3HomeTabItem
import FlatPhase3HomeTabItem2
import FlatPhase3NoisyHomeDto
import FlatPhase3ShuffledHomeTabItem
import FlatPhase3WeakHomeTabItem
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptDiagnostic
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptState
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HomeTabPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistEngine
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstallResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HomeFrameTabLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HomeTabFilterFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPortTestRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import com.Bilibili_Innocent_Lab.xposedmodule.testsupport.TestRenamingClassLoader
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class HomeChromeAdapterTest {
    private val top = HookPoint(
        "tv.danmaku.bili.khome.widget.topbar.Renamed", "render",
        listOf("model.HomeState", "androidx.compose.ui.Modifier", "androidx.compose.runtime.Composer", "int")
    )

    private fun homeChromeCases() {
        val points = mapOf("top" to top)
        assertEquals(points, HomeChromeAdapter.fromJson(HomeChromeAdapter.toJson(points)))
        assertEquals(emptyMap<String, HookPoint>(), HomeChromeAdapter.fromJson(JSONObject()))

        assertNull(HomeChromeAdapter.fromJson(JSONObject().put("player", top.toJson())))
        assertNull(HomeChromeAdapter.fromJson(JSONObject().put("top", top.copy(className = "java.lang.String").toJson())))
        assertNull(HomeChromeAdapter.fromJson(JSONObject().put("top", top.copy(viewField = "root").toJson())))
        assertNull(HomeChromeAdapter.fromJson(JSONObject().put("top", JSONObject().put("c", "missing"))))
        assertFalse(HomeChromeAdapter.validCache(mapOf("top" to top.copy(paramClassNames = null))))
        assertFalse(HomeChromeAdapter.validCache(mapOf("top" to top.copy(paramClassNames = listOf("")))))

        val trap = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> = error("disabled feature loaded $name")
        }
        assertTrue(HomeChromeAdapter.planned(trap, emptyMap(), false, emptySet()).isEmpty())
        assertFalse(HomeChromeAdapter.semanticsNeedsQuery(trap, null, false, false, true))
        assertFalse(HomeChromeAdapter.semanticsNeedsQuery(trap, null, true, false, false))
        assertFalse(HomeChromeAdapter.semanticsNeedsQuery(trap, null, true, true, true))

        assertFalse(HomeChromeAdapter.conclusive(null))
        DexAssistResult.Reason.entries.filterNot {
            it in setOf(DexAssistResult.Reason.NO_MATCH, DexAssistResult.Reason.TOO_MANY_MATCHES)
        }.forEach { reason -> assertFalse(reason.name, HomeChromeAdapter.conclusive(DexAssistResult.Unavailable(reason))) }
        assertTrue(HomeChromeAdapter.conclusive(DexAssistResult.Candidates(emptyList())))
        assertTrue(HomeChromeAdapter.conclusive(DexAssistResult.Unavailable(DexAssistResult.Reason.NO_MATCH)))
        assertTrue(HomeChromeAdapter.conclusive(DexAssistResult.Unavailable(DexAssistResult.Reason.TOO_MANY_MATCHES)))

        val loader = object : ClassLoader(null) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == "tv.danmaku.bili.khome.vm.HomeFrameViewModel") return Any::class.java
                throw ClassNotFoundException(name)
            }
        }
        val expected = setOf(
            DexAssistQuery.HOME_CHROME_TOP,
            DexAssistQuery.HOME_CHROME_TOP_CONTENT,
            DexAssistQuery.HOME_CHROME_TOP_RIGHT_ACTIONS
        )
        assertEquals(expected, HomeChromeAdapter.planned(loader, emptyMap(), false, emptySet(), topTabCentering = true))
        assertEquals(
            expected - DexAssistQuery.HOME_CHROME_TOP_CONTENT,
            HomeChromeAdapter.planned(loader, emptyMap(), false, setOf(DexAssistQuery.HOME_CHROME_TOP_CONTENT), topTabCentering = true)
        )
        assertFalse(HomeChromeAdapter.enabled("top_content", true, false, false))
        assertFalse(HomeChromeAdapter.enabled("top_right_actions", true, false, false))

        val contentPoint = HookPoint(
            "tv.danmaku.bili.khome.widget.topbar.RenamedLambda", "invoke",
            listOf("java.lang.Object", "java.lang.Object")
        )
        val contentPoints = mapOf("top_content" to contentPoint)
        assertEquals(contentPoints, HomeChromeAdapter.fromJson(HomeChromeAdapter.toJson(contentPoints)))
        assertFalse(HomeChromeAdapter.validCache(mapOf("top_content" to contentPoint.copy(paramClassNames = listOf("java.lang.Object")))))

        val backdrop = HookPoint(
            "tv.danmaku.bili.khome.widget.topbar.y", "e",
            listOf("Qo1.e", "kotlin.jvm.functions.Function1", "androidx.compose.runtime.Composer", "int")
        )
        val withBackdrop = mapOf("top" to top, "top_backdrop" to backdrop)
        assertEquals(withBackdrop, HomeChromeAdapter.fromJson(HomeChromeAdapter.toJson(withBackdrop)))
        assertEquals(mapOf("top" to top), HomeChromeAdapter.fromJson(HomeChromeAdapter.toJson(mapOf("top" to top))))
        assertTrue(HomeChromeAdapter.enabled("top_backdrop", true, false, false))
        assertFalse(HomeChromeAdapter.enabled("top_backdrop", false, false, true))
        assertEquals("top_backdrop", HomeChromeAdapter.families[DexAssistQuery.HOME_CHROME_TOP_BACKDROP])

        assertTrue(HomeChromeAdapter.acceptsOwner("top", "zn40"))
        assertTrue(HomeChromeAdapter.acceptsOwner("top_backdrop", "cmq0"))
        assertTrue(HomeChromeAdapter.acceptsOwner("bottom", "clq0"))
        assertFalse(HomeChromeAdapter.acceptsOwner("bottom", "clq0\$1"))
        assertTrue(HomeChromeAdapter.acceptsOwner("top_content", "en40"))
        assertTrue(HomeChromeAdapter.acceptsOwner("top_content", "h330\$1"))
        assertTrue(HomeChromeAdapter.acceptsOwner("bottom_content", "r0o0\$2"))
        assertFalse(HomeChromeAdapter.acceptsOwner("top_backdrop", "foreign.pkg.cmq0"))

        val requiredQueries = setOf(
            DexAssistQuery.HOME_CHROME_TOP,
            DexAssistQuery.HOME_CHROME_TOP_CONTENT,
            DexAssistQuery.HOME_CHROME_TOP_RIGHT_ACTIONS
        )
        for (reason in listOf("query_failed", "previous_attempt_unfinished", "no-dex-source")) {
            val diagnostics = requiredQueries.map { query ->
                AdaptDiagnostic(HomeChromeAdapter.diagnostic(query), AdaptState.NOT_APPLICABLE, reason)
            } + AdaptDiagnostic(HomeChromeAdapter.SEMANTICS_DIAGNOSTIC, AdaptState.NOT_APPLICABLE, reason)

            val coldChromeAttempted = HomeChromeAdapter.families.keys.filterTo(linkedSetOf()) { query ->
                diagnostics.any { it.id == HomeChromeAdapter.diagnostic(query) && AdaptAssistGate.isAttemptedInCache(it) }
            }
            val coldSemanticsAttempted = diagnostics.any {
                it.id == HomeChromeAdapter.SEMANTICS_DIAGNOSTIC && AdaptAssistGate.isAttemptedInCache(it)
            }
            assertTrue(reason, HomeChromeAdapter.planned(loader, emptyMap(), false, coldChromeAttempted, topTabCentering = true).isEmpty())
            assertFalse(reason, HomeChromeAdapter.semanticsNeedsQuery(loader, null, true, coldSemanticsAttempted, true))

            val inRoundChromeAttempted = HomeChromeAdapter.families.keys.filterTo(linkedSetOf()) { query ->
                diagnostics.any { it.id == HomeChromeAdapter.diagnostic(query) && AdaptAssistGate.isConclusiveMissing(it) }
            }
            val inRoundSemanticsAttempted = diagnostics.any {
                it.id == HomeChromeAdapter.SEMANTICS_DIAGNOSTIC && AdaptAssistGate.isConclusiveMissing(it)
            }
            assertEquals(reason, requiredQueries, HomeChromeAdapter.planned(loader, emptyMap(), false, inRoundChromeAttempted, topTabCentering = true))
            assertTrue(reason, HomeChromeAdapter.semanticsNeedsQuery(loader, null, true, inRoundSemanticsAttempted, true))
        }

        val disabledDiagnostics = requiredQueries.map { query ->
            AdaptDiagnostic(HomeChromeAdapter.diagnostic(query), AdaptState.NOT_APPLICABLE, "disabled-or-absent")
        } + AdaptDiagnostic(HomeChromeAdapter.SEMANTICS_DIAGNOSTIC, AdaptState.NOT_APPLICABLE, "disabled-or-absent")
        val disabledAttempted = HomeChromeAdapter.families.keys.filterTo(linkedSetOf()) { query ->
            disabledDiagnostics.any { it.id == HomeChromeAdapter.diagnostic(query) && AdaptAssistGate.isAttemptedInCache(it) }
        }
        val disabledSemanticsAttempted = disabledDiagnostics.any {
            it.id == HomeChromeAdapter.SEMANTICS_DIAGNOSTIC && AdaptAssistGate.isAttemptedInCache(it)
        }
        assertEquals(requiredQueries, HomeChromeAdapter.planned(loader, emptyMap(), false, disabledAttempted, topTabCentering = true))
        assertTrue(HomeChromeAdapter.semanticsNeedsQuery(loader, null, true, disabledSemanticsAttempted, true))
    }

    private fun homeTabResourceAdapterAndRoleMappingCases() {
        val baseLoader = requireNotNull(javaClass.classLoader)
        val loader = TestRenamingClassLoader(
            parent = baseLoader,
            templates = mapOf(HomeTabResourceAdapter.HOME_FRAGMENT_V2 to TemplateGenericErasedHomeFragmentV2::class.java)
        )
        assertNull(VersionAdapter.locateHomeTabs(loader))
        assertTrue(HomeTabResourceAdapter.applicable(loader))
        assertTrue(HomeTabResourceAdapter.needsQuery(loader, directFound = false, cached = null, enabled = true, attempted = false))

        val fragmentCls = loader.loadClass(HomeTabResourceAdapter.HOME_FRAGMENT_V2)
        val buildMethod = fragmentCls.declaredMethods.single { it.name == "buildTabs" }
        assertTrue(HomeTabResourceAdapter.verifiedBuildMethod(buildMethod))

        val session = DexAssistSession(
            DexAssistEngine {
                DexAssistResult.Candidates(
                    methods = listOf(buildMethod),
                    relatedClasses = mapOf(buildMethod to listOf(FlatPhase3WeakHomeTabItem::class.java, FlatPhase3HomeTabItem::class.java))
                )
            },
            listOf("base.apk"), loader, setOf(HomeTabResourceAdapter.query)
        )
        val located = requireNotNull(HomeTabResourceAdapter.locate(loader, session, enabled = true, direct = null, cached = null, planned = true))
        assertEquals(FlatPhase3HomeTabItem::class.java.name, located.resourceClassName)
        assertEquals(located, HomeTabResourceAdapter.resolve(loader, located))
        assertFalse(HomeTabResourceAdapter.needsQuery(loader, directFound = false, cached = located, enabled = true, attempted = false))

        assertNull(HomeTabResourceAdapter.resolve(loader, located.copy(titleField = "nonExistent")))
        assertNull(HomeTabResourceAdapter.resolve(loader, located.copy(resourceClassName = FlatPhase3WeakHomeTabItem::class.java.name)))

        val ambiguousSession = DexAssistSession(
            DexAssistEngine {
                DexAssistResult.Candidates(
                    methods = listOf(buildMethod),
                    relatedClasses = mapOf(buildMethod to listOf(FlatPhase3HomeTabItem::class.java, FlatPhase3HomeTabItem2::class.java))
                )
            },
            listOf("base.apk"), loader, setOf(HomeTabResourceAdapter.query)
        )
        assertNull(HomeTabResourceAdapter.locate(loader, ambiguousSession, enabled = true, direct = null, cached = null, planned = true))

        val shuffledPoints = requireNotNull(HomeTabResourceAdapter.buildPoints(buildMethod, FlatPhase3ShuffledHomeTabItem::class.java))
        assertEquals("id", shuffledPoints.idField)
        assertEquals("title", shuffledPoints.titleField)
        assertEquals("uri", shuffledPoints.uriField)
        assertEquals("reporterId", shuffledPoints.reporterIdField)

        val anchoredSession = DexAssistSession(
            DexAssistEngine {
                DexAssistResult.Candidates(
                    classes = listOf(FlatPhase3ShuffledHomeTabItem::class.java),
                    methods = listOf(buildMethod),
                    relatedClasses = mapOf(buildMethod to listOf(FlatPhase3NoisyHomeDto::class.java, FlatPhase3ShuffledHomeTabItem::class.java))
                )
            },
            listOf("base.apk"), loader, setOf(HomeTabResourceAdapter.query)
        )
        assertEquals(shuffledPoints, HomeTabResourceAdapter.locate(loader, anchoredSession, enabled = true, direct = null, cached = null, planned = true))

        // HomeTabFilterFeatureInstaller hot-retry
        val tabRef = AtomicReference<HomeTabPoints?>(null)
        val adaptedIds = mutableListOf<String>()
        val tabDelegate = PlayerPortTestRegistrar()
        val tabRegistrar = object : HookRegistrar by tabDelegate {
            override fun adapted(id: String, point: HookPoint, exceptionPolicy: HookExceptionPolicy, block: ModernMemberHookCreator.() -> Unit) {
                adaptedIds += id
                tabDelegate.adapted(id, point, exceptionPolicy, block)
            }
        }
        val tabEnv = HookEnvironment("tv.danmaku.bili", loader, HookPointRegistry(loader), tabRegistrar, { _, _ -> }, { _, _ -> }, { _, _ -> })
        val tabInstaller = HomeTabFilterFeatureInstaller(
            rules = "关注", points = null, frameResolver = { HomeFrameTabLocator.Resolution.Absent }, pointsProvider = { tabRef.get() }
        )
        assertTrue(tabInstaller.install(tabEnv) is FeatureInstallResult.Skipped)
        assertTrue(tabInstaller.requiresAdaptationRetry)
        tabRef.set(shuffledPoints)
        assertEquals(FeatureInstallResult.Installed(1), tabInstaller.install(tabEnv))
        assertFalse(tabInstaller.requiresAdaptationRetry)
        assertEquals(1, adaptedIds.count { it == "home.tabs.build" })
        assertEquals(FeatureInstallResult.Installed(1), tabInstaller.install(tabEnv))
        assertEquals(1, adaptedIds.count { it == "home.tabs.build" })

        // Flattened role inference
        val getterMapped = requireNotNull(HomeTabResourceAdapter.resolveFieldMapping(ObfHomeTabWithGettersAndScheme::class.java))
        assertEquals("z", getterMapped.idField)
        assertEquals("w", getterMapped.titleField)
        assertEquals("a", getterMapped.uriField)
        assertEquals("m", getterMapped.reporterIdField)

        val alphaFallback = requireNotNull(HomeTabResourceAdapter.resolveFieldMapping(ObfHomeTabPlainAlpha::class.java))
        assertEquals("a", alphaFallback.idField)
        assertEquals("b", alphaFallback.titleField)
        assertEquals("c", alphaFallback.uriField)
        assertEquals("d", alphaFallback.reporterIdField)
    }

    @Test
    fun verifyAllCases() {
        homeChromeCases()
        homeTabResourceAdapterAndRoleMappingCases()
    }

    class TemplateGenericErasedHomeFragmentV2 {
        fun buildTabs(raw: List<*>): List<Any> = raw.filterNotNull()
    }

    class ObfHomeTabWithGettersAndScheme(
        @JvmField var z: String = "",
        @JvmField var w: String = "",
        @JvmField var a: String = "bilibili://main/home",
        @JvmField var m: String = ""
    ) {
        fun getId(): String = z
        fun getTitle(): String = w
        fun getReportId(): String = m
    }

    class ObfHomeTabPlainAlpha(
        @JvmField var b: String = "",
        @JvmField var a: String = "",
        @JvmField var d: String = "",
        @JvmField var c: String = ""
    )
}
