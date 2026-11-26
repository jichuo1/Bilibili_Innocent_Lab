package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import FlatWave1BrandDraw
import FlatWave1CloudKeyContract
import Wave1FeatureFlagInterface
import Wave1TypedContractBase
import WrongWave1BrandDraw
import WrongWave1CloudKeyContract
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptDiagnostic
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptState
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapterTest
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistEngine
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.BrandSplashKntrRenderer
import org.json.JSONObject
import org.junit.Assert.*

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

    fun `cloud key adapter discovers flattened contract safely and validates cache`() {
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

        // Direct legacy package leaf skips DEX query.
        val directLoader = cloudKeyLoader("${CloudKeyFreezeAdapter.BLCONFIG_INTERNAL_PACKAGE}s" to FlatWave1CloudKeyContract::class.java)
        assertNotNull(CloudKeyFreezeAdapter.direct(directLoader))
        assertEquals("FlatWave1CloudKeyContract", CloudKeyFreezeAdapter.direct(directLoader))
        assertFalse(CloudKeyFreezeAdapter.needsQuery(directLoader, null, enabled = true, attempted = false))

        // Foreign / wrong owner fails closed.
        assertFalse(CloudKeyFreezeAdapter.allowedOwnerName("foreign.pkg.Evil"))
        assertNull(CloudKeyFreezeAdapter.resolve(loader, "WrongWave1CloudKeyContract"))
        val ambiguous = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(listOf(validMethod, validMethod.declaringClass.getDeclaredMethod("getWithDefault", String::class.java, Boolean::class.javaPrimitiveType))) },
            listOf("base.apk"), loader, setOf(CloudKeyFreezeAdapter.query)
        )
        // Duplicate same declaringClass distincts cleanly; >32 fails closed.
        assertEquals("FlatWave1CloudKeyContract", CloudKeyFreezeAdapter.locate(loader, ambiguous, true, null, true))
        val tooMany = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(List(33) { validMethod }) },
            listOf("base.apk"), loader, setOf(CloudKeyFreezeAdapter.query)
        )
        assertNull(CloudKeyFreezeAdapter.locate(loader, tooMany, true, null, true))
    }

    fun `adapt result round-trips wave 1 fields and merges retryable diagnostics cleanly`() {
        val base = VersionAdapterTest().result()
        val withWave1 = base.copy(
            brandSplashKntrDraw = "gel",
            cloudKeyFreezeContract = "y5f0"
        )
        val decoded = requireNotNull(VersionAdapter.AdaptResult.fromJson(JSONObject(withWave1.toJson().toString())))
        assertEquals("gel", decoded.brandSplashKntrDraw)
        assertEquals("y5f0", decoded.cloudKeyFreezeContract)
        assertNull(VersionAdapter.AdaptResult.fromJson(withWave1.toJson().put("brand_splash_kntr_draw", "foreign.pkg.Bad")))
        assertNull(VersionAdapter.AdaptResult.fromJson(withWave1.toJson().put("cloud_key_freeze_contract", "foreign.pkg.Bad")))
        assertNull(VersionAdapter.AdaptResult.fromJson(withWave1.toJson().put("brand_splash_kntr_draw", JSONObject.NULL)))
        assertNull(VersionAdapter.AdaptResult.fromJson(withWave1.toJson().put("cloud_key_freeze_contract", JSONObject.NULL)))
        assertNull(VersionAdapter.AdaptResult.fromJson(withWave1.toJson().put("privacy_kneuron", JSONObject.NULL)))
        assertNull(VersionAdapter.AdaptResult.fromJson(withWave1.toJson().put("guest_player_core", JSONObject.NULL)))
        assertNull(CommentReplyButtonAdapter.fromJson(JSONObject().put("legacy", JSONObject.NULL)))

        val cached = base.copy(
            brandSplashKntrDraw = "gel",
            cloudKeyFreezeContract = "y5f0",
            domainRuleVersions = com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapterContract.DOMAIN_RULE_VERSIONS,
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
    }

    fun `brand splash kntr adapter discovers flattened draw lambda safely and validates cache`() {
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

        // Direct COMPACT_DRAW resolution bypasses DEX query.
        val compactLoader = object : ClassLoader(baseLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> =
                if (name == BrandSplashKntrRenderer.COMPACT_DRAW) FlatWave1BrandDraw::class.java
                else super.loadClass(name, resolve)
        }
        assertEquals("FlatWave1BrandDraw", BrandSplashKntrAdapter.direct(compactLoader))
        assertFalse(BrandSplashKntrAdapter.needsQuery(compactLoader, null, enabled = true, attempted = false))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (BrandSplashAndCloudKeyAdaptersTest) -> Unit) {
            val s = BrandSplashAndCloudKeyAdaptersTest()
            block(s)
        }
        runIsolated { s -> s.`cloud key adapter discovers flattened contract safely and validates cache`() }
        runIsolated { s -> s.`adapt result round-trips wave 1 fields and merges retryable diagnostics cleanly`() }
        runIsolated { s -> s.`brand splash kntr adapter discovers flattened draw lambda safely and validates cache`() }
    }
}
