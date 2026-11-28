package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import FlatPhase3RelateGameComponent
import FlatPhase3RelateGameComponent2
import FlatWave1Divider
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapterTest
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.*
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.DetailAppPromotionFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstallResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HostCardHookAdapterTest.*
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HostVideoCardDividerHooks
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPortTestRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostOgvCardAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.testsupport.TestRenamingClassLoader
import com.bilibili.pegasus.widget.LazyDivider
import com.bilibili.pegasus.widget.RenamedDivider
import com.bilibili.pegasus.widget.WrongDivider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class HostCardSemanticAdapterTest {
    private val baseLoader = requireNotNull(javaClass.classLoader)
    private val loader = object : ClassLoader(baseLoader) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = when (name) {
            "androidx.compose.ui.Modifier" -> CardModifier::class.java
            "androidx.compose.runtime.Composer" -> CardComposer::class.java
            "com.bilibili.ogv.operation3.module.operablecard.a" -> CardModel::class.java
            else -> super.loadClass(name, resolve)
        }
    }
    private val card = FollowingUi::class.java.declaredMethods.single { it.name == "card" }
    private val second = LegacyFollowing::class.java.declaredMethods.single { it.name == "card" }
    private fun point(method: java.lang.reflect.Method) =
        HookPoint(method.declaringClass.name, method.name, method.parameterTypes.map(Class<*>::getName))

    private fun cardAndDividerCases() {
        val query = DexAssistQuery.OGV_TRENDING_CARD
        val session = DexAssistSession(DexAssistEngine { DexAssistResult.Candidates(listOf(card)) }, listOf("base.apk"), loader, setOf(query))
        val points = HostCardSemanticAdapter.locate(loader, emptyMap(), session, setOf(query))
        val family = HostOgvCardAdapter.resolve(loader, points).families.single { it.id == "trending" }
        assertTrue(family.applicable); assertEquals(card, family.points.single().method)
        val args = arrayOf<Any?>(CardItem(), Helper(), CardModifier(), CardComposer(), 0x7ff)
        val supplied = CardModifier()
        family.points.single().rewrite(args, supplied, 16f)
        assertSame(supplied, args[2]); assertEquals(0x7ff and (14 shl 6).inv(), args[4])
        assertEquals(points, PageBoundaryPoints.fromJson(PageBoundaryPoints(cardSurfaces = points).toJson())!!.cardSurfaces)

        val doubleQuery = DexAssistQuery.OGV_DOUBLE_CARD
        val ambigSession = DexAssistSession(DexAssistEngine { DexAssistResult.Candidates(listOf(card, second)) }, listOf("base.apk"), loader, setOf(doubleQuery))
        assertTrue(HostCardSemanticAdapter.locate(loader, emptyMap(), ambigSession, setOf(doubleQuery)).isEmpty())
        val trap = DexAssistSession(DexAssistEngine { error("unplanned native scan") }, emptyList(), loader, emptySet())
        assertEquals(mapOf("double" to point(card)), HostCardSemanticAdapter.locate(loader, mapOf("double" to point(card)), trap, emptySet()))
        assertFalse(HostCardSemanticAdapter.validCache(mapOf("outer_page" to point(card))))

        val method = RenamedDivider::class.java.declaredMethods.single { it.name == "onDrawOver" }
        val wrong = WrongDivider::class.java.declaredMethods.single { it.name == "onDrawOver" }
        assertTrue(HostVideoCardDividerHooks.verifiedPegasus(loader, method))
        assertFalse(HostVideoCardDividerHooks.verifiedPegasus(loader, wrong))
        val lazy = LazyDivider::class.java.declaredMethods.single { it.name == "onDrawOver" }
        assertFalse(HostVideoCardDividerHooks.verifiedPegasus(loader, lazy))
        assertEquals(method, HostVideoCardDividerHooks.pegasusBoundary(loader, point(method)))
        val dividerQuery = DexAssistQuery.PEGASUS_DIVIDER
        val dividerSession = DexAssistSession(DexAssistEngine { DexAssistResult.Candidates(listOf(wrong, lazy, method)) }, listOf("base.apk"), loader, setOf(dividerQuery))
        assertEquals(point(method), HostCardSemanticAdapter.locate(loader, emptyMap(), dividerSession, setOf(dividerQuery))["pegasus_divider"])
        val hcardsLoader = object : ClassLoader(loader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> = when (name) {
                "com.bilibili.ogv.operation3.module.operablecard.a" -> throw ClassNotFoundException(name)
                HostOgvCardAdapter.HCARDS -> CardModel::class.java
                else -> super.loadClass(name, resolve)
            }
        }
        assertTrue(HostCardSemanticAdapter.applicable(hcardsLoader, DexAssistQuery.OGV_DOUBLE_CARD))

        val flatDivider = FlatWave1Divider::class.java.declaredMethods.single { it.name == "onDrawOver" }
        assertTrue(HostVideoCardDividerHooks.verifiedPegasus(loader, flatDivider))
        assertEquals(flatDivider, HostVideoCardDividerHooks.pegasusBoundary(loader, point(flatDivider)))
    }

    private fun detailRelateGameAdapterCases() {
        assertNull(DetailRelateGameAdapter.resolve(baseLoader, "evil.pkg.Comp"))

        val inPkgGame = "${DetailRelateGameAdapter.RELATE_GAME_PACKAGE}.aa"
        val gameLoader = TestRenamingClassLoader(
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

        assertTrue(DetailRelateGameAdapter.applicable(gameLoader))
        assertNull(DetailRelateGameAdapter.direct(gameLoader))
        assertTrue(DetailRelateGameAdapter.needsQuery(gameLoader, cached = null, enabled = true, attempted = false))

        val flatComp = gameLoader.loadClass("FlatPhase3RelateGameComponent")
        val flatComp2 = gameLoader.loadClass("FlatPhase3RelateGameComponent2")
        val inPkgComp = gameLoader.loadClass(inPkgGame)

        val flatSession = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(methods = emptyList(), classes = listOf(flatComp)) },
            listOf("base.apk"), gameLoader, setOf(DetailRelateGameAdapter.query)
        )
        val located = DetailRelateGameAdapter.locate(gameLoader, flatSession, enabled = true, cached = null, planned = true)
        assertEquals("FlatPhase3RelateGameComponent", located)
        assertNotNull(DetailRelateGameAdapter.resolvePair(gameLoader, located))

        val ambigSession = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(methods = emptyList(), classes = listOf(flatComp, flatComp2)) },
            listOf("base.apk"), gameLoader, setOf(DetailRelateGameAdapter.query)
        )
        assertNull(DetailRelateGameAdapter.locate(gameLoader, ambigSession, enabled = true, cached = null, planned = true))

        val inPkgSession = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(methods = emptyList(), classes = listOf(inPkgComp, flatComp)) },
            listOf("base.apk"), gameLoader, setOf(DetailRelateGameAdapter.query)
        )
        assertEquals(inPkgGame, DetailRelateGameAdapter.locate(gameLoader, inPkgSession, enabled = true, cached = null, planned = true))

        val baseJson = VersionAdapterTest().result().copy(detailRelateGameComponent = located).toJson()
        val restored = requireNotNull(AdaptResult.fromJson(JSONObject(baseJson.toString())))
        assertEquals("FlatPhase3RelateGameComponent", restored.detailRelateGameComponent)
        assertNull(AdaptResult.fromJson(JSONObject(baseJson.toString()).put("detail_relate_game_component", "evil.pkg.Comp")))

        val gameRef = AtomicReference<String?>(null)
        val registrar = PlayerPortTestRegistrar()
        val capEvents = mutableListOf<Pair<String, FeatureInstallResult>>()
        val env = HookEnvironment(
            processName = "tv.danmaku.bili",
            classLoader = gameLoader,
            hookPoints = HookPointRegistry(gameLoader),
            registrar = registrar,
            logInfo = { _, _ -> },
            logError = { _, _ -> },
            reportStatus = { _, _ -> },
            capabilityEvidence = { id, result -> capEvents += id to result }
        )
        val installer = DetailAppPromotionFeatureInstaller(
            enabled = false, relateAdEnabled = true, cachedRelateGameComponentProvider = { gameRef.get() }
        )
        val pass1 = installer.install(env) as FeatureInstallResult.Installed
        assertFalse(pass1.complete)
        assertTrue(installer.requiresAdaptationRetry)
        assertTrue(capEvents.any { (id, res) -> id == "detail_promotion_related_game" && res is FeatureInstallResult.Skipped && res.reason == "missing-capability-dependency" })
        assertEquals(1, registrar.registrationAttempts.count { it.first == "detail_app_promotion.video_detail" })

        gameRef.set(located)
        val pass2 = installer.install(env) as FeatureInstallResult.Installed
        assertTrue(pass2.complete)
        assertFalse(installer.requiresAdaptationRetry)
        assertTrue(capEvents.any { (id, res) -> id == "detail_promotion_related_game" && res is FeatureInstallResult.Installed && res.complete })
        assertEquals(1, registrar.registrationAttempts.count { it.first == "detail_app_promotion.video_detail" })
        assertEquals(1, registrar.registrationAttempts.count { it.first == "detail_app_promotion.relate_game.create" })
        assertEquals(1, registrar.registrationAttempts.count { it.first == "detail_app_promotion.relate_game.bind" })
    }

    @Test
    fun verifyAllCases() {
        cardAndDividerCases()
        detailRelateGameAdapterCases()
    }

    class TemplateB3Continuation
    class TemplateB3ViewBinding
    class TemplateSimpleViewEntry(@Suppress("UNUSED_PARAMETER") view: View)
    abstract class TemplateGeminiBaseComponent {
        @Suppress("UNUSED_PARAMETER")
        fun createViewEntry(context: Context, parent: ViewGroup): Any = Any()
        @Suppress("UNUSED_PARAMETER")
        open fun bindToView(binding: Any, cont: TemplateB3Continuation): Any? = Unit
    }
    class TemplateRelateGameComponent : TemplateGeminiBaseComponent() {
        @Suppress("UNUSED_PARAMETER")
        fun g(context: Context, inflater: LayoutInflater, parent: ViewGroup): TemplateB3ViewBinding = TemplateB3ViewBinding()
        @Suppress("UNUSED_PARAMETER")
        fun h(binding: TemplateB3ViewBinding, cont: TemplateB3Continuation): Any? = Unit
    }
    class TemplateIAdVideoRelate
    class TemplateGAdVideoDetail { fun getRelate(): TemplateIAdVideoRelate? = null }
    object TemplateGAdBizKt { @JvmStatic fun getGAdVideoDetail(): TemplateGAdVideoDetail? = null }
}
