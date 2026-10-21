package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HomeChromeAdapterTest {
    private val top = HookPoint("tv.danmaku.bili.khome.widget.topbar.Renamed", "render",
        listOf("model.HomeState", "androidx.compose.ui.Modifier", "androidx.compose.runtime.Composer", "int"))

    @Test fun cacheRoundTripRetainsCompleteDescriptorsWithoutEncodingRuntimeInstances() {
        val points = mapOf("top" to top)
        assertEquals(points, HomeChromeAdapter.fromJson(HomeChromeAdapter.toJson(points)))
        assertEquals(emptyMap<String, HookPoint>(), HomeChromeAdapter.fromJson(JSONObject()))
    }

    @Test fun cacheCannotAddOtherPagesOrAnUnverifiedFieldBoundary() {
        assertNull(HomeChromeAdapter.fromJson(JSONObject().put("player", top.toJson())))
        assertNull(HomeChromeAdapter.fromJson(JSONObject().put("top", top.copy(className = "java.lang.String").toJson())))
        assertNull(HomeChromeAdapter.fromJson(JSONObject().put("top", top.copy(viewField = "root").toJson())))
        assertNull(HomeChromeAdapter.fromJson(JSONObject().put("top", JSONObject().put("c", "missing"))))
        assertFalse(HomeChromeAdapter.validCache(mapOf("top" to top.copy(paramClassNames = null))))
        assertFalse(HomeChromeAdapter.validCache(mapOf("top" to top.copy(paramClassNames = listOf("")))))
    }

    @Test fun disabledChromeDoesNotLoadHostClassesOrPlanNativeWork() {
        val trap = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> = error("disabled feature loaded $name")
        }
        assertTrue(HomeChromeAdapter.planned(trap, emptyMap(), false, emptySet()).isEmpty())
        assertFalse(HomeChromeAdapter.semanticsNeedsQuery(trap, null, false, false, true))
        assertFalse(HomeChromeAdapter.semanticsNeedsQuery(trap, null, true, false, false))
        assertFalse(HomeChromeAdapter.semanticsNeedsQuery(trap, null, true, true, true))
    }

    @Test fun failedOrUnfinishedDexPreparationNeverBecomesPermanentNegativeCache() {
        assertFalse(HomeChromeAdapter.conclusive(null))
        DexAssistResult.Reason.entries.filterNot { it in setOf(
            DexAssistResult.Reason.NO_MATCH, DexAssistResult.Reason.TOO_MANY_MATCHES)
        }.forEach { reason -> assertFalse(reason.name, HomeChromeAdapter.conclusive(DexAssistResult.Unavailable(reason))) }
    }

    @Test fun completedAbsenceAndAmbiguityAreConclusiveWithoutInventingAHook() {
        assertTrue(HomeChromeAdapter.conclusive(DexAssistResult.Candidates(emptyList())))
        assertTrue(HomeChromeAdapter.conclusive(DexAssistResult.Unavailable(DexAssistResult.Reason.NO_MATCH)))
        assertTrue(HomeChromeAdapter.conclusive(DexAssistResult.Unavailable(DexAssistResult.Reason.TOO_MANY_MATCHES)))
    }
}
