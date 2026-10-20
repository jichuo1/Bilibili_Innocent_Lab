package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.*
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerEndPageRecommendLocator
import com.bilibili.search2.discover.RenamedDelivery
import com.bilibili.search2.discover.WrongDelivery
import com.bilibili.search2.main.data.RenamedState
import com.bilibili.search2.main.data.WrongState
import com.bilibili.ship.theseus.ugc.endpage.relatedrecommand.RenamedMerger
import com.bilibili.ship.theseus.ugc.endpage.relatedrecommand.OtherMerger
import org.junit.Assert.*
import org.junit.Test

class PageBoundaryAdapterTest {
    private val loader = object : ClassLoader(javaClass.classLoader) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name in setOf("com.bilibili.search2.discover.q", "com.bilibili.search2.discover.y",
                "com.bilibili.search2.discover.z", "com.bilibili.search2.main.data.i")) throw ClassNotFoundException(name)
            return super.loadClass(name, resolve)
        }
    }
    private val history = RenamedDelivery::class.java.getDeclaredMethod("getHistoryList")
    private val merge = RenamedMerger::class.java.getDeclaredMethod("merge", List::class.java, List::class.java)
    private fun session(results: Map<DexAssistQuery, DexAssistResult>, planned: Set<DexAssistQuery> = results.keys) =
        DexAssistSession(DexAssistEngine { results[it.query] ?: error("unplanned query") }, listOf("base.apk"), loader, planned)

    @Test fun renamedOwnersOutsideTheOldAlphabetResolveThroughTypedProductionLocators() {
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
        assertTrue(java.lang.reflect.Modifier.isStatic(merge.modifiers))
        assertEquals(points, PageBoundaryPoints.fromJson(points.toJson()))
    }

    @Test fun erasedListDecoysAndMultipleOwnersNeverBecomeHooks() {
        val wrong = WrongDelivery::class.java.getDeclaredMethod("getHistoryList")
        val candidates = session(mapOf(
            DexAssistQuery.SEARCH_HOME_DELIVERY to DexAssistResult.Candidates(listOf(wrong)),
            DexAssistQuery.SEARCH_HOME_STATE to DexAssistResult.Candidates(emptyList(), listOf(WrongState::class.java)),
            DexAssistQuery.PLAYER_END_PAGE_MERGER to DexAssistResult.Candidates(listOf(merge,
                OtherMerger::class.java.getDeclaredMethod("merge", List::class.java, List::class.java)))
        ))
        assertNull(PageBoundaryAdapter.locate(loader, null, candidates, setOf(DexAssistQuery.SEARCH_HOME_DELIVERY, DexAssistQuery.SEARCH_HOME_STATE, DexAssistQuery.PLAYER_END_PAGE_MERGER)))
    }

    @Test fun disabledAndPreviouslyAttemptedQueriesNeverLoadNativeOrOpenAnUnplannedSession() {
        val trap = object : ClassLoader(loader) { override fun loadClass(name: String, resolve: Boolean): Class<*> = error("disabled class load") }
        assertTrue(PageBoundaryAdapter.planned(trap, null, false, false, emptySet()).isEmpty())
        val expected = setOf(DexAssistQuery.SEARCH_HOME_DELIVERY, DexAssistQuery.SEARCH_HOME_STATE)
        assertEquals(expected, PageBoundaryAdapter.planned(loader, null, true, false, emptySet()))
        assertTrue(PageBoundaryAdapter.planned(loader, null, true, false, expected).isEmpty())
        val unopened = DexAssistSession(DexAssistEngine { error("unplanned native query") }, emptyList(), loader, emptySet())
        assertNull(PageBoundaryAdapter.locate(loader, null, unopened, emptySet()))
    }

    @Test fun storedNamesAreRevalidatedAndMalformedCacheCannotExpandThePackageScope() {
        val points = PageBoundaryPoints(WrongDelivery::class.java.name, WrongState::class.java.name,
            VersionAdapter.HookPoint(RenamedMerger::class.java.name, "missing", listOf("java.util.List", "java.util.List")))
        assertNull(PageBoundaryAdapter.locate(loader, points, null, emptySet()))
        assertNull(PageBoundaryPoints.fromJson(org.json.JSONObject().put("search_delivery", "java.lang.String")))
        assertNull(PageBoundaryPoints.fromJson(org.json.JSONObject().put("card_surfaces", org.json.JSONArray())))
        assertFalse(PageBoundaryPoints(endPageMerger = VersionAdapter.HookPoint("java.lang.String", "x", emptyList())).isValid())
    }
}
