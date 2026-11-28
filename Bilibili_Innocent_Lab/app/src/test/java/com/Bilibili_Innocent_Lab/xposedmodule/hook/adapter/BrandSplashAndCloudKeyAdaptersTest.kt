package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import FlatPhase3BrandModel
import FlatWave1BrandDraw
import FlatWave1CloudKeyContract
import Wave1FeatureFlagInterface
import Wave1TypedContractBase
import WrongWave1BrandDraw
import WrongWave1CloudKeyContract
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptDiagnostic
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptState
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapterContract
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapterTest
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistEngine
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.BrandSplashKntrAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.BrandSplashKntrRenderer
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.BrandSplashSkipFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CloudKeyFreezeFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstallResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPortTestRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.testsupport.TestRenamingClassLoader
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class BrandSplashAndCloudKeyAdaptersTest {
    private val baseLoader = javaClass.classLoader!!

    private fun cloudKeyLoader(vararg extraMappings: Pair<String, Class<*>>): ClassLoader =
        object : ClassLoader(baseLoader) {
            private val mappings = mapOf(
                CloudKeyFreezeAdapter.TYPED_CONTRACT_CLASS to Wave1TypedContractBase::class.java,
                CloudKeyFreezeAdapter.FEATURE_FLAG_CONTRACT_CLASS to Wave1FeatureFlagInterface::class.java
            ) + extraMappings

            override fun loadClass(name: String, resolve: Boolean): Class<*> =
                mappings[name] ?: super.loadClass(name, resolve)
        }

    private fun cloudKeyAdapterAndWave1RoundTrip() {
        val loader = cloudKeyLoader()
        assertTrue(CloudKeyFreezeAdapter.applicable(loader))
        assertNull(CloudKeyFreezeAdapter.direct(loader))
        assertTrue(CloudKeyFreezeAdapter.needsQuery(loader, null, enabled = true, attempted = false))
        assertFalse(CloudKeyFreezeAdapter.needsQuery(loader, null, enabled = false, attempted = false))
        assertFalse(CloudKeyFreezeAdapter.needsQuery(loader, null, enabled = true, attempted = true))

        val validMethod = FlatWave1CloudKeyContract::class.java.getDeclaredMethod(
            "getWithDefault", String::class.java, Boolean::class.javaPrimitiveType
        )
        val wrongMethod = WrongWave1CloudKeyContract::class.java.getDeclaredMethod(
            "getWithDefault", String::class.java, Boolean::class.javaPrimitiveType
        )
        val decoyMethod = String::class.java.getDeclaredMethod("length")

        val session = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(listOf(wrongMethod, decoyMethod, validMethod)) },
            listOf("base.apk"), loader, setOf(CloudKeyFreezeAdapter.query)
        )
        val discovered = requireNotNull(
            CloudKeyFreezeAdapter.locate(loader, session, enabled = true, cached = null, planned = true)
        )
        assertEquals("FlatWave1CloudKeyContract", discovered)
        assertEquals(discovered, CloudKeyFreezeAdapter.resolve(loader, discovered))
        assertFalse(CloudKeyFreezeAdapter.needsQuery(loader, discovered, enabled = true, attempted = false))

        val directLoader = cloudKeyLoader("${CloudKeyFreezeAdapter.BLCONFIG_INTERNAL_PACKAGE}s" to FlatWave1CloudKeyContract::class.java)
        assertEquals("FlatWave1CloudKeyContract", CloudKeyFreezeAdapter.direct(directLoader))
        assertFalse(CloudKeyFreezeAdapter.needsQuery(directLoader, null, enabled = true, attempted = false))

        assertFalse(CloudKeyFreezeAdapter.allowedOwnerName("foreign.pkg.Evil"))
        assertNull(CloudKeyFreezeAdapter.resolve(loader, "WrongWave1CloudKeyContract"))
        val ambiguous = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(listOf(validMethod, validMethod.declaringClass.getDeclaredMethod("getWithDefault", String::class.java, Boolean::class.javaPrimitiveType))) },
            listOf("base.apk"), loader, setOf(CloudKeyFreezeAdapter.query)
        )
        assertEquals("FlatWave1CloudKeyContract", CloudKeyFreezeAdapter.locate(loader, ambiguous, true, null, true))
        val tooMany = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(List(33) { validMethod }) },
            listOf("base.apk"), loader, setOf(CloudKeyFreezeAdapter.query)
        )
        assertNull(CloudKeyFreezeAdapter.locate(loader, tooMany, true, null, true))

        val base = VersionAdapterTest().result()
        val withWave1 = base.copy(brandSplashKntrDraw = "gel", cloudKeyFreezeContract = "y5f0")
        val decoded = requireNotNull(AdaptResult.fromJson(JSONObject(withWave1.toJson().toString())))
        assertEquals("gel", decoded.brandSplashKntrDraw)
        assertEquals("y5f0", decoded.cloudKeyFreezeContract)
        assertNull(AdaptResult.fromJson(withWave1.toJson().put("brand_splash_kntr_draw", "foreign.pkg.Bad")))
        assertNull(AdaptResult.fromJson(withWave1.toJson().put("cloud_key_freeze_contract", "foreign.pkg.Bad")))
        assertNull(AdaptResult.fromJson(withWave1.toJson().put("brand_splash_kntr_draw", JSONObject.NULL)))
        assertNull(AdaptResult.fromJson(withWave1.toJson().put("cloud_key_freeze_contract", JSONObject.NULL)))
        assertNull(AdaptResult.fromJson(withWave1.toJson().put("privacy_kneuron", JSONObject.NULL)))
        assertNull(AdaptResult.fromJson(withWave1.toJson().put("guest_player_core", JSONObject.NULL)))
        assertNull(CommentReplyButtonAdapter.fromJson(JSONObject().put("legacy", JSONObject.NULL)))

        val cached = base.copy(
            brandSplashKntrDraw = "gel",
            cloudKeyFreezeContract = "y5f0",
            domainRuleVersions = VersionAdapterContract.DOMAIN_RULE_VERSIONS,
            diagnostics = listOf(
                AdaptDiagnostic(BrandSplashKntrAdapter.DIAGNOSTIC, AdaptState.MISSING, "no_match"),
                AdaptDiagnostic(CloudKeyFreezeAdapter.DIAGNOSTIC, AdaptState.MISSING, "no_match"),
                AdaptDiagnostic(AdaptAssistGate.BLOCK_UPDATE_ID, AdaptState.MISSING, "block-update:no_match")
            )
        )
        val merged = requireNotNull(VersionAdapter.mergeRuntimeWithCached(base, cached))
        assertEquals("gel", merged.brandSplashKntrDraw)
        assertEquals("y5f0", merged.cloudKeyFreezeContract)
        assertFalse(BrandSplashKntrAdapter.allowedOwnerName("com.other.X"))
        assertTrue(BrandSplashKntrAdapter.allowedOwnerName(BrandSplashKntrRenderer.COMPACT_DRAW))

        // CloudKeyFreezeFeatureInstaller hot-retry without re-registering earlier hooks
        class TemplateOverrideFF {
            @Suppress("UNUSED_PARAMETER")
            fun get(key: String, def: Any?): Any? = def
        }
        val ckLoader = cloudKeyLoader("com.bilibili.lib.blconfig.internal.OverrideFF" to TemplateOverrideFF::class.java)
        val contractRef = AtomicReference<String?>(null)
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
    }

    private fun brandSplashKntrDrawExitAndPageDiscovery() {
        assertTrue(BrandSplashKntrAdapter.applicable(baseLoader))
        val drawMethod = FlatWave1BrandDraw::class.java.getDeclaredMethod(
            "invoke", Any::class.java, Any::class.java, Int::class.javaPrimitiveType
        )
        val wrongMethod = WrongWave1BrandDraw::class.java.getDeclaredMethod(
            "invoke", Any::class.java, Any::class.java, Int::class.javaPrimitiveType
        )
        assertTrue(BrandSplashKntrAdapter.verified(baseLoader, FlatWave1BrandDraw::class.java))
        assertFalse(BrandSplashKntrAdapter.verified(baseLoader, WrongWave1BrandDraw::class.java))
        assertNull(BrandSplashKntrAdapter.direct(baseLoader))
        assertTrue(BrandSplashKntrAdapter.needsQuery(baseLoader, null, enabled = true, attempted = false))
        assertFalse(BrandSplashKntrAdapter.needsQuery(baseLoader, null, enabled = false, attempted = false))
        assertFalse(BrandSplashKntrAdapter.needsQuery(baseLoader, null, enabled = true, attempted = true))

        val session = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(listOf(wrongMethod, drawMethod)) },
            listOf("base.apk"), baseLoader, setOf(BrandSplashKntrAdapter.query)
        )
        val located = requireNotNull(
            BrandSplashKntrAdapter.locate(baseLoader, session, enabled = true, cached = null, planned = true)
        )
        assertEquals("FlatWave1BrandDraw", located)
        assertEquals(located, BrandSplashKntrAdapter.resolve(baseLoader, located))
        assertFalse(BrandSplashKntrAdapter.needsQuery(baseLoader, located, enabled = true, attempted = false))
        assertNull(BrandSplashKntrAdapter.resolve(baseLoader, "WrongWave1BrandDraw"))
        assertNull(BrandSplashKntrAdapter.resolve(baseLoader, "foreign.pkg.Bad"))

        val compactLoader = object : ClassLoader(baseLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> =
                if (name == BrandSplashKntrRenderer.COMPACT_DRAW) FlatWave1BrandDraw::class.java
                else super.loadClass(name, resolve)
        }
        assertEquals("FlatWave1BrandDraw", BrandSplashKntrAdapter.direct(compactLoader))
        assertFalse(BrandSplashKntrAdapter.needsQuery(compactLoader, null, enabled = true, attempted = false))

        // Exit & Page Composable discovery + hot-retry + cache validation
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
        val loader = TestRenamingClassLoader(
            baseLoader,
            templates = templates,
            replacements = replacements,
            hiddenNames = setOf(
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
            listOf("base.apk"), loader, setOf(BrandSplashKntrAdapter.queryExit)
        )
        assertEquals(exitClassName, BrandSplashKntrAdapter.locateExit(loader, exitSession, enabled = true, cached = null, planned = true))
        assertEquals(exitClassName, BrandSplashKntrAdapter.resolveExit(loader, exitClassName))
        assertNull(BrandSplashKntrAdapter.resolveExit(loader, "FlatPhase3BadExitCoroutine"))

        val ambiguousExitSession = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(emptyList(), listOf(exitCls, loader.loadClass("${exitClassName}2"))) },
            listOf("base.apk"), loader, setOf(BrandSplashKntrAdapter.queryExit)
        )
        assertNull(BrandSplashKntrAdapter.locateExit(loader, ambiguousExitSession, enabled = true, cached = null, planned = true))

        val skipRegistrar = PlayerPortTestRegistrar()
        val skipEnv = HookEnvironment("tv.danmaku.bili", loader, HookPointRegistry(loader), skipRegistrar, { _, _ -> }, { _, _ -> }, { _, _ -> })
        val skipInstall = BrandSplashSkipFeatureInstaller(enabled = true, kntrExitClass = exitClassName) { true }.install(skipEnv)
        assertTrue(skipInstall is FeatureInstallResult.Installed)
        assertTrue("brand.skip.kntr.exit" in skipRegistrar.hooks)

        val pageHolder = loader.loadClass(pageClassName)
        val pageMethod = pageHolder.declaredMethods.single { it.name == "a" }
        val pageSession = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(listOf(pageMethod)) },
            listOf("base.apk"), loader, setOf(BrandSplashKntrAdapter.queryPage)
        )
        val locatedPage = requireNotNull(BrandSplashKntrAdapter.locatePage(loader, pageSession, enabled = true, cached = null, planned = true))
        assertEquals(pageClassName, locatedPage.className)
        assertNotNull(BrandSplashKntrAdapter.resolvePage(loader, locatedPage))

        val pageMethod2 = loader.loadClass("${pageClassName}2").declaredMethods.single { it.name == "a" }
        val ambiguousPageSession = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(listOf(pageMethod, pageMethod2)) },
            listOf("base.apk"), loader, setOf(BrandSplashKntrAdapter.queryPage)
        )
        assertNull(BrandSplashKntrAdapter.locatePage(loader, ambiguousPageSession, enabled = true, cached = null, planned = true))

        val directPageLoader = TestRenamingClassLoader(
            baseLoader,
            templates = templates + ("kntr.srcs.app.splash.brand.startup.c" to TemplateBrandPageHolder::class.java),
            replacements = replacements
        )
        assertNotNull(BrandSplashKntrAdapter.directPage(directPageLoader))
        assertNull(
            BrandSplashKntrAdapter.locatePage(
                directPageLoader, null, enabled = true, cached = HookPoint("WrongOwner", "wrong", listOf("a", "b")), planned = false
            )
        )
        assertEquals(
            "kntr.srcs.app.splash.brand.startup.c",
            BrandSplashKntrAdapter.locatePage(
                directPageLoader, null, enabled = true, cached = locatedPage.copy(className = "kntr.srcs.app.splash.brand.startup.c"), planned = false
            )?.className
        )

        val base = VersionAdapterTest().result()
        val validResult = base.copy(brandSplashKntrExit = exitClassName, brandSplashKntrPage = locatedPage)
        val roundTripped = requireNotNull(AdaptResult.fromJson(JSONObject(validResult.toJson().toString())))
        assertEquals(exitClassName, roundTripped.brandSplashKntrExit)
        assertEquals(locatedPage, roundTripped.brandSplashKntrPage)
        assertNull(AdaptResult.fromJson(validResult.toJson().put("brand_splash_kntr_exit", JSONObject.NULL)))
        assertNull(AdaptResult.fromJson(validResult.toJson().put("brand_splash_kntr_exit", "foreign.pkg.Evil")))
        assertNull(AdaptResult.fromJson(validResult.toJson().put("brand_splash_kntr_page", JSONObject.NULL)))
        assertNull(AdaptResult.fromJson(validResult.toJson().put("brand_splash_kntr_page", HookPoint(pageClassName, "a", listOf("a", "b")).toJson())))
    }

    private fun brandSplashDecoderAdapterContracts() {
        val first = BrandDecoderFixtures.First::class.java.getDeclaredMethod("a", tv.danmaku.bili.ui.splash.brand.model.BrandShowInfo::class.java)
        val second = BrandDecoderFixtures.Second::class.java.declaredMethods.single()
        fun mapped(vararg owners: Pair<String, Class<*>>) = object : ClassLoader(baseLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> =
                owners.firstOrNull { it.first == name }?.second ?: super.loadClass(name, resolve)
        }

        for (owner in listOf("Or1.a", "Nr1.a")) {
            val host = mapped(owner to first.declaringClass)
            assertEquals(first, BrandSplashDecoderAdapter.direct(host))
            assertFalse(BrandSplashDecoderAdapter.needsQuery(host, null, true, false))
            assertEquals(
                BrandSplashDecoderAdapter.point(first),
                BrandSplashDecoderAdapter.locate(
                    host,
                    DexAssistSession(DexAssistEngine { error("direct opened DEX") }, listOf("base.apk"), host, emptySet()),
                    true, null, false
                )
            )
        }
        assertNull(BrandSplashDecoderAdapter.direct(mapped("Or1.a" to first.declaringClass, "Nr1.a" to second.declaringClass)))

        val point = BrandSplashDecoderAdapter.point(first)
        val noOpenSession = DexAssistSession(DexAssistEngine { error("cache opened DEX") }, listOf("base.apk"), baseLoader, emptySet())
        assertFalse(BrandSplashDecoderAdapter.needsQuery(baseLoader, point, true, false))
        assertEquals(point, BrandSplashDecoderAdapter.locate(baseLoader, noOpenSession, true, point, false))
        assertFalse(BrandSplashDecoderAdapter.needsQuery(baseLoader, null, true, true))
        assertNull(BrandSplashDecoderAdapter.locate(baseLoader, noOpenSession, true, null, false))
        var loads = 0
        val trap = object : ClassLoader(baseLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                loads++; error("disabled loaded host class")
            }
        }
        assertEquals(point, BrandSplashDecoderAdapter.locate(trap, noOpenSession, false, point, false))
        assertEquals(0, loads)

        for (name in listOf("Vx1.a", "Ux1.a")) {
            assertEquals(first, BrandSplashDecoderAdapter.direct(mapped(name to first.declaringClass)))
        }
        val latest = BrandDecoderFixtures.Latest::class.java
        val b = latest.getDeclaredMethod("b", tv.danmaku.bili.ui.splash.brand.model.BrandShowInfo::class.java)
        val latestHost = mapped("cgk" to latest)
        assertEquals(b, BrandSplashDecoderAdapter.direct(latestHost))
        assertFalse(BrandSplashDecoderAdapter.needsQuery(latestHost, null, true, false))
        assertNull(BrandSplashDecoderAdapter.direct(mapped("cgk" to latest, "Vx1.a" to first.declaringClass)))

        fun select(methods: List<java.lang.reflect.Method>) = BrandSplashDecoderAdapter.locate(
            baseLoader,
            DexAssistSession(DexAssistEngine { DexAssistResult.Candidates(methods) }, listOf("base.apk"), baseLoader, setOf(BrandSplashDecoderAdapter.query)),
            true, null, true
        )
        assertEquals(BrandSplashDecoderAdapter.point(first), select(listOf(first, first)))
        assertNull(select(listOf(first, second)))
        assertNull(select(List(33) { first }))
        assertNull(select(BrandDecoderFixtures.Instance::class.java.declaredMethods.toList()))
        assertNull(select(BrandDecoderFixtures.First::class.java.declaredMethods.filter { it.name == "unrelated" }))
        assertNull(BrandSplashDecoderAdapter.resolve(baseLoader, BrandSplashDecoderAdapter.point(first).copy(paramClassNames = emptyList())))
    }

    @Test
    fun verifyAllCases() {
        cloudKeyAdapterAndWave1RoundTrip()
        brandSplashKntrDrawExitAndPageDiscovery()
        brandSplashDecoderAdapterContracts()
    }

    class TemplateComposer
    class TemplateModifier
    interface TemplateState
    interface TemplateMutableState : TemplateState

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
}
