package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import FlatWave1AdvertFloat
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerPopupPromotionAdapter.Family
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistEngine
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistRequest
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.bilibili.adcommon.data.model.OtherPopupAdvert
import com.bilibili.adcommon.data.model.RenamedPopupAdvert
import com.bilibili.ship.theseus.united.player.mediaplay.network.PopupAdapterShapes
import com.bilibili.ship.theseus.united.player.mediaplay.network.PromoNetworkServiceFixture
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlayerPopupPromotionAdapterTest {
    private val loader = javaClass.classLoader!!
    private val guide = PromoNetworkServiceFixture::class.java.declaredMethods.single { it.name == "f" }
    private val advert = RenamedPopupAdvert::class.java.declaredMethods.single { it.name == "floats" }
    private fun hidden() = object : ClassLoader(loader) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name == PlayerPopupPromotionAdapter.GUIDE_CONTINUATION || name == PlayerPopupPromotionAdapter.ADVERT_CLASS)
                throw ClassNotFoundException(name)
            return super.loadClass(name, resolve)
        }
    }

    fun `stable continuation derives only its unique validated guide owner`() {
        assertEquals(guide, PlayerPopupPromotionAdapter.directFreeDataGuide(loader))
        val ambiguous = object : ClassLoader(loader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> =
                if (name == PlayerPopupPromotionAdapter.GUIDE_CONTINUATION) PopupAdapterShapes.AmbiguousAnchor::class.java
                else super.loadClass(name, resolve)
        }
        assertNull(PlayerPopupPromotionAdapter.directFreeDataGuide(ambiguous))
        assertFalse(PlayerPopupPromotionAdapter.verifiedFreeDataGuide(PopupAdapterShapes.Broken::class.java.declaredMethods.single()))
        assertNull(PlayerPopupPromotionAdapter.resolveFreeDataGuide(loader, null))
    }

    fun `disabled and complete direct paths never invoke the dex engine while missing families share a pass`() {
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
    }

    fun `multiple owners wrong signatures and too many candidates are rejected`() {
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
    }

    fun `old continuation ABI accepts only the unique semantic candidate and rejects its split helpers`() {
        val old = PopupAdapterShapes.Old::class.java.declaredMethods
        val flow = old.single { it.name == "r" }
        val unrelatedFlow = old.single { it.name == "q" }
        assertTrue(PlayerPopupPromotionAdapter.verifiedFreeDataGuide(flow))
        assertTrue(PlayerPopupPromotionAdapter.verifiedFreeDataGuide(unrelatedFlow))
        // 同 owner 的另一提醒具有相同 ABI，必须由方法体语义 query 区分，不能按方法数量猜测。
        for (helper in old.filter { it.name in listOf("counter", "frequency", "display") })
            assertFalse(PlayerPopupPromotionAdapter.verifiedFreeDataGuide(helper))
        fun locate(candidates: List<java.lang.reflect.Method>) = PlayerPopupPromotionAdapter.locate(hidden(),
            DexAssistSession(DexAssistEngine { request ->
                if (request.query == Family.FREE_DATA_GUIDE.query) DexAssistResult.Candidates(candidates)
                else DexAssistResult.Unavailable(DexAssistResult.Reason.NO_MATCH)
            }, listOf("base.apk"), hidden(), Family.entries.map { it.query }.toSet()), true)
        assertEquals(PlayerPopupPromotionAdapter.point(flow), locate(listOf(flow)).freeDataGuide)
        assertNull(locate(listOf(flow, unrelatedFlow)).freeDataGuide)
    }

    fun `another family or feature refreshing never reopens a previously failed query`() {
        val missing = hidden()
        val attempted = setOf(Family.FREE_DATA_GUIDE)
        val planned = PlayerPopupPromotionAdapter.planned(missing, null, attempted, true)
        assertEquals(setOf(Family.ADVERT_FLOAT), planned)
        val requests = mutableListOf<DexAssistQuery>()
        val session = DexAssistSession(DexAssistEngine { request ->
            requests += request.query
            assertEquals(Family.ADVERT_FLOAT.query, request.query)
            DexAssistResult.Candidates(listOf(advert))
        }, listOf("base.apk"), missing, planned.map { it.query }.toSet())
        val points = PlayerPopupPromotionAdapter.locate(missing, session, true, null, planned)
        assertNull(points.freeDataGuide)
        assertEquals(PlayerPopupPromotionAdapter.point(advert), points.advertFloat)
        assertEquals(listOf(Family.ADVERT_FLOAT.query), requests)
        val allAttempted = PlayerPopupPromotionAdapter.planned(missing, null, Family.entries.toSet(), true)
        assertTrue(allAttempted.isEmpty())
        PlayerPopupPromotionAdapter.locate(missing, DexAssistSession(DexAssistEngine { error("unplanned query") },
            listOf("base.apk"), missing, emptySet()), true, null, allAttempted)
    }

    fun `verified cached families produce zero queries during another feature refresh`() {
        val missing = hidden()
        val cached = PlayerPopupPromotionPoints(PlayerPopupPromotionAdapter.point(guide), PlayerPopupPromotionAdapter.point(advert))
        val planned = PlayerPopupPromotionAdapter.planned(missing, cached, emptySet(), true)
        assertTrue(planned.isEmpty())
        val session = DexAssistSession(DexAssistEngine { error("cached member triggered DEX") }, listOf("base.apk"), missing, emptySet())
        assertEquals(cached, PlayerPopupPromotionAdapter.locate(missing, session, true, cached, planned))
        assertTrue(PlayerPopupPromotionAdapter.planned(missing, cached, emptySet(), false).isEmpty())
        val invalid = cached.copy(freeDataGuide = cached.freeDataGuide!!.copy(methodName = "missing"))
        assertEquals(setOf(Family.FREE_DATA_GUIDE), PlayerPopupPromotionAdapter.planned(missing, invalid, emptySet(), true))
        assertTrue(PlayerPopupPromotionAdapter.planned(missing, invalid, setOf(Family.FREE_DATA_GUIDE), true).isEmpty())
    }

    fun `disabled feature retains pure previous points without loading a single host class`() {
        var loads = 0
        val trap = object : ClassLoader(loader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                loads++
                throw ClassNotFoundException(name)
            }
        }
        val cached = PlayerPopupPromotionPoints(PlayerPopupPromotionAdapter.point(guide), PlayerPopupPromotionAdapter.point(advert))
        assertSame(cached, PlayerPopupPromotionAdapter.locate(trap, null, false, cached, emptySet()))
        assertTrue(PlayerPopupPromotionAdapter.planned(trap, cached, Family.entries.toSet(), false).isEmpty())
        assertEquals(0, loads)
        assertEquals(PlayerPopupPromotionPoints(), PlayerPopupPromotionAdapter.locate(trap, null, false))
        assertEquals(0, loads)
    }

    fun `optional cache is bounded validated and merged independently for each family`() {
        val guidePoint = PlayerPopupPromotionAdapter.point(guide)
        val floatPoint = PlayerPopupPromotionAdapter.point(advert)
        val points = PlayerPopupPromotionPoints(guidePoint, floatPoint)
        assertEquals(points, PlayerPopupPromotionPoints.fromJson(points.toJson()))
        assertEquals(PlayerPopupPromotionPoints(), PlayerPopupPromotionPoints.fromJson(JSONObject()))
        assertNull(PlayerPopupPromotionPoints.fromJson(JSONObject().put("free_data_guide", "bad")))
        assertNull(PlayerPopupPromotionPoints.fromJson(PlayerPopupPromotionPoints(guidePoint.copy(paramClassNames = emptyList())).toJson()))
        assertNull(PlayerPopupPromotionPoints.fromJson(PlayerPopupPromotionPoints(advertFloat = floatPoint.copy(paramClassNames = listOf("java.lang.String"))).toJson()))
        assertNull(PlayerPopupPromotionPoints.fromJson(PlayerPopupPromotionPoints(guidePoint.copy(methodName = "x".repeat(257))).toJson()))
        for (bad in listOf("bad/name", "bad;name", "bad(name", "bad name", "bad\u0000name"))
            assertNull(PlayerPopupPromotionPoints.fromJson(PlayerPopupPromotionPoints(guidePoint.copy(methodName = bad)).toJson()))
        for (bad in listOf("${PlayerPopupPromotionAdapter.NETWORK_PACKAGE}.Bad/Class", "com.bilibili.ship.theseus.united.player.mediaplay.network..Bad", "com.bilibili.ship.theseus.united.player.mediaplay.network.1Bad"))
            assertNull(PlayerPopupPromotionPoints.fromJson(PlayerPopupPromotionPoints(guidePoint.copy(className = bad)).toJson()))
        assertEquals(points, PlayerPopupPromotionAdapter.merge(PlayerPopupPromotionPoints(guidePoint), PlayerPopupPromotionPoints(advertFloat = floatPoint)))
        assertNull(PlayerPopupPromotionAdapter.resolveFreeDataGuide(loader, guidePoint.copy(methodName = "missing")))
        assertNull(PlayerPopupPromotionAdapter.resolveAdvertFloat(loader, floatPoint.copy(paramClassNames = listOf("int"))))

        val flatFloat = FlatWave1AdvertFloat::class.java.declaredMethods.single { it.name == "a" }
        assertTrue(PlayerPopupPromotionAdapter.verifiedAdvertFloat(flatFloat))
        val flatPoints = PlayerPopupPromotionPoints(advertFloat = PlayerPopupPromotionAdapter.point(flatFloat))
        assertEquals(flatPoints, PlayerPopupPromotionPoints.fromJson(flatPoints.toJson()))
        assertEquals(flatFloat, PlayerPopupPromotionAdapter.resolveAdvertFloat(loader, flatPoints.advertFloat))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (PlayerPopupPromotionAdapterTest) -> Unit) {
            val s = PlayerPopupPromotionAdapterTest()
            block(s)
        }
        runIsolated { s -> s.`stable continuation derives only its unique validated guide owner`() }
        runIsolated { s -> s.`disabled and complete direct paths never invoke the dex engine while missing families share a pass`() }
        runIsolated { s -> s.`multiple owners wrong signatures and too many candidates are rejected`() }
        runIsolated { s -> s.`old continuation ABI accepts only the unique semantic candidate and rejects its split helpers`() }
        runIsolated { s -> s.`another family or feature refreshing never reopens a previously failed query`() }
        runIsolated { s -> s.`verified cached families produce zero queries during another feature refresh`() }
        runIsolated { s -> s.`disabled feature retains pure previous points without loading a single host class`() }
        runIsolated { s -> s.`optional cache is bounded validated and merged independently for each family`() }
    }
}
