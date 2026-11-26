package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptDiagnostic
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptState
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptAssistGate
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HomeChromeAdapterTest {
    private val top = HookPoint("tv.danmaku.bili.khome.widget.topbar.Renamed", "render",
        listOf("model.HomeState", "androidx.compose.ui.Modifier", "androidx.compose.runtime.Composer", "int"))

    fun cacheRoundTripRetainsCompleteDescriptorsWithoutEncodingRuntimeInstances() {
        val points = mapOf("top" to top)
        assertEquals(points, HomeChromeAdapter.fromJson(HomeChromeAdapter.toJson(points)))
        assertEquals(emptyMap<String, HookPoint>(), HomeChromeAdapter.fromJson(JSONObject()))
    }

    fun cacheCannotAddOtherPagesOrAnUnverifiedFieldBoundary() {
        assertNull(HomeChromeAdapter.fromJson(JSONObject().put("player", top.toJson())))
        assertNull(HomeChromeAdapter.fromJson(JSONObject().put("top", top.copy(className = "java.lang.String").toJson())))
        assertNull(HomeChromeAdapter.fromJson(JSONObject().put("top", top.copy(viewField = "root").toJson())))
        assertNull(HomeChromeAdapter.fromJson(JSONObject().put("top", JSONObject().put("c", "missing"))))
        assertFalse(HomeChromeAdapter.validCache(mapOf("top" to top.copy(paramClassNames = null))))
        assertFalse(HomeChromeAdapter.validCache(mapOf("top" to top.copy(paramClassNames = listOf("")))))
    }

    fun disabledChromeDoesNotLoadHostClassesOrPlanNativeWork() {
        val trap = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> = error("disabled feature loaded $name")
        }
        assertTrue(HomeChromeAdapter.planned(trap, emptyMap(), false, emptySet()).isEmpty())
        assertFalse(HomeChromeAdapter.semanticsNeedsQuery(trap, null, false, false, true))
        assertFalse(HomeChromeAdapter.semanticsNeedsQuery(trap, null, true, false, false))
        assertFalse(HomeChromeAdapter.semanticsNeedsQuery(trap, null, true, true, true))
    }

    fun failedOrUnfinishedDexPreparationNeverBecomesPermanentNegativeCache() {
        assertFalse(HomeChromeAdapter.conclusive(null))
        DexAssistResult.Reason.entries.filterNot { it in setOf(
            DexAssistResult.Reason.NO_MATCH, DexAssistResult.Reason.TOO_MANY_MATCHES)
        }.forEach { reason -> assertFalse(reason.name, HomeChromeAdapter.conclusive(DexAssistResult.Unavailable(reason))) }
    }

    fun completedAbsenceAndAmbiguityAreConclusiveWithoutInventingAHook() {
        assertTrue(HomeChromeAdapter.conclusive(DexAssistResult.Candidates(emptyList())))
        assertTrue(HomeChromeAdapter.conclusive(DexAssistResult.Unavailable(DexAssistResult.Reason.NO_MATCH)))
        assertTrue(HomeChromeAdapter.conclusive(DexAssistResult.Unavailable(DexAssistResult.Reason.TOO_MANY_MATCHES)))
    }

    fun centeringWithoutGlassPlansOnlyItsThreeSemanticBoundaries() {
        val loader = object : ClassLoader(null) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == "tv.danmaku.bili.khome.vm.HomeFrameViewModel") return Any::class.java
                throw ClassNotFoundException(name)
            }
        }
        val expected = setOf(DexAssistQuery.HOME_CHROME_TOP, DexAssistQuery.HOME_CHROME_TOP_CONTENT,
            DexAssistQuery.HOME_CHROME_TOP_RIGHT_ACTIONS)
        assertEquals(expected, HomeChromeAdapter.planned(loader, emptyMap(), false, emptySet(), topTabCentering = true))
        assertEquals(expected - DexAssistQuery.HOME_CHROME_TOP_CONTENT, HomeChromeAdapter.planned(loader, emptyMap(), false,
            setOf(DexAssistQuery.HOME_CHROME_TOP_CONTENT), topTabCentering = true))
        assertFalse(HomeChromeAdapter.enabled("top_content", true, false, false))
        assertFalse(HomeChromeAdapter.enabled("top_right_actions", true, false, false))
    }

    fun contentBridgeCacheRetainsItsTwoParameterAbi() {
        val point = HookPoint("tv.danmaku.bili.khome.widget.topbar.RenamedLambda", "invoke",
            listOf("java.lang.Object", "java.lang.Object"))
        val points = mapOf("top_content" to point)
        assertEquals(points, HomeChromeAdapter.fromJson(HomeChromeAdapter.toJson(points)))
        assertFalse(HomeChromeAdapter.validCache(mapOf("top_content" to point.copy(paramClassNames = listOf("java.lang.Object")))))
    }

    fun secondRowBackdropIsOptionalInOldCachesAndRetainedInNewCaches() {
        val backdrop = HookPoint("tv.danmaku.bili.khome.widget.topbar.y", "e",
            listOf("Qo1.e", "kotlin.jvm.functions.Function1", "androidx.compose.runtime.Composer", "int"))
        val points = mapOf("top" to top, "top_backdrop" to backdrop)
        assertEquals(points, HomeChromeAdapter.fromJson(HomeChromeAdapter.toJson(points)))
        assertEquals(mapOf("top" to top), HomeChromeAdapter.fromJson(HomeChromeAdapter.toJson(mapOf("top" to top))))
        assertTrue(HomeChromeAdapter.enabled("top_backdrop", true, false, false))
        assertFalse(HomeChromeAdapter.enabled("top_backdrop", false, false, true))
        assertEquals("top_backdrop", HomeChromeAdapter.families[DexAssistQuery.HOME_CHROME_TOP_BACKDROP])
    }

    fun arbitraryFlattenedRootOwnersRespectNestingPolicy() {
        assertTrue(HomeChromeAdapter.acceptsOwner("top", "zn40"))
        assertTrue(HomeChromeAdapter.acceptsOwner("top_backdrop", "cmq0"))
        assertTrue(HomeChromeAdapter.acceptsOwner("bottom", "clq0"))
        assertFalse(HomeChromeAdapter.acceptsOwner("bottom", "clq0\$1"))
        assertTrue(HomeChromeAdapter.acceptsOwner("top_content", "en40"))
        assertTrue(HomeChromeAdapter.acceptsOwner("top_content", "h330\$1"))
        assertTrue(HomeChromeAdapter.acceptsOwner("bottom_content", "r0o0\$2"))
        assertFalse(HomeChromeAdapter.acceptsOwner("top_backdrop", "foreign.pkg.cmq0"))
    }

    fun retryableChromeAndSemanticsFailuresStopColdStartLoopsWhilePiggybackRetrying() {
        val loader = object : ClassLoader(null) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == "tv.danmaku.bili.khome.vm.HomeFrameViewModel") return Any::class.java
                throw ClassNotFoundException(name)
            }
        }
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
            assertTrue(
                reason,
                HomeChromeAdapter.planned(loader, emptyMap(), false, coldChromeAttempted, topTabCentering = true).isEmpty()
            )
            assertFalse(
                reason,
                HomeChromeAdapter.semanticsNeedsQuery(loader, null, true, coldSemanticsAttempted, true)
            )

            // 当其它新开功能或换版真正进入 adapt() 时，非确定性失败仍保留在 in-round 待重查集合内。
            val inRoundChromeAttempted = HomeChromeAdapter.families.keys.filterTo(linkedSetOf()) { query ->
                diagnostics.any { it.id == HomeChromeAdapter.diagnostic(query) && AdaptAssistGate.isConclusiveMissing(it) }
            }
            val inRoundSemanticsAttempted = diagnostics.any {
                it.id == HomeChromeAdapter.SEMANTICS_DIAGNOSTIC && AdaptAssistGate.isConclusiveMissing(it)
            }
            assertEquals(
                reason,
                requiredQueries,
                HomeChromeAdapter.planned(loader, emptyMap(), false, inRoundChromeAttempted, topTabCentering = true)
            )
            assertTrue(
                reason,
                HomeChromeAdapter.semanticsNeedsQuery(loader, null, true, inRoundSemanticsAttempted, true)
            )
        }

        // 功能此前关闭写成的 disabled-or-absent 不算已尝试：后续首次启用仍须触发一次补扫。
        val disabledDiagnostics = requiredQueries.map { query ->
            AdaptDiagnostic(HomeChromeAdapter.diagnostic(query), AdaptState.NOT_APPLICABLE, "disabled-or-absent")
        } + AdaptDiagnostic(HomeChromeAdapter.SEMANTICS_DIAGNOSTIC, AdaptState.NOT_APPLICABLE, "disabled-or-absent")
        val disabledAttempted = HomeChromeAdapter.families.keys.filterTo(linkedSetOf()) { query ->
            disabledDiagnostics.any { it.id == HomeChromeAdapter.diagnostic(query) && AdaptAssistGate.isAttemptedInCache(it) }
        }
        val disabledSemanticsAttempted = disabledDiagnostics.any {
            it.id == HomeChromeAdapter.SEMANTICS_DIAGNOSTIC && AdaptAssistGate.isAttemptedInCache(it)
        }
        assertEquals(
            requiredQueries,
            HomeChromeAdapter.planned(loader, emptyMap(), false, disabledAttempted, topTabCentering = true)
        )
        assertTrue(HomeChromeAdapter.semanticsNeedsQuery(loader, null, true, disabledSemanticsAttempted, true))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (HomeChromeAdapterTest) -> Unit) {
            val s = HomeChromeAdapterTest()
            block(s)
        }
        runIsolated { s -> s.cacheRoundTripRetainsCompleteDescriptorsWithoutEncodingRuntimeInstances() }
        runIsolated { s -> s.cacheCannotAddOtherPagesOrAnUnverifiedFieldBoundary() }
        runIsolated { s -> s.disabledChromeDoesNotLoadHostClassesOrPlanNativeWork() }
        runIsolated { s -> s.failedOrUnfinishedDexPreparationNeverBecomesPermanentNegativeCache() }
        runIsolated { s -> s.completedAbsenceAndAmbiguityAreConclusiveWithoutInventingAHook() }
        runIsolated { s -> s.centeringWithoutGlassPlansOnlyItsThreeSemanticBoundaries() }
        runIsolated { s -> s.contentBridgeCacheRetainsItsTwoParameterAbi() }
        runIsolated { s -> s.secondRowBackdropIsOptionalInOldCachesAndRetainedInNewCaches() }
        runIsolated { s -> s.arbitraryFlattenedRootOwnersRespectNestingPolicy() }
        runIsolated { s -> s.retryableChromeAndSemanticsFailuresStopColdStartLoopsWhilePiggybackRetrying() }
    }
}
