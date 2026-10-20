package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.*
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HostCardHookAdapterTest.*
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HostVideoCardDividerHooks
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostOgvCardAdapter
import com.bilibili.pegasus.widget.LazyDivider
import com.bilibili.pegasus.widget.RenamedDivider
import com.bilibili.pegasus.widget.WrongDivider
import org.junit.Assert.*
import org.junit.Test

class HostCardSemanticAdapterTest {
    private val loader = object : ClassLoader(javaClass.classLoader) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = when (name) {
            "androidx.compose.ui.Modifier" -> CardModifier::class.java
            "androidx.compose.runtime.Composer" -> CardComposer::class.java
            "com.bilibili.ogv.operation3.module.operablecard.a" -> CardModel::class.java
            else -> super.loadClass(name, resolve)
        }
    }
    private val card = FollowingUi::class.java.declaredMethods.single { it.name == "card" }
    private val second = LegacyFollowing::class.java.declaredMethods.single { it.name == "card" }
    private fun point(method: java.lang.reflect.Method) = HookPoint(method.declaringClass.name,
        method.name, method.parameterTypes.map(Class<*>::getName))

    @Test fun semanticLeafSurvivesOwnerAndHelperRenamingAndReachesTheActualOgvConsumer() {
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
    }
    @Test fun multipleSemanticLeavesAreRejectedAndUnplannedQueryNeverOpensDex() {
        val query = DexAssistQuery.OGV_DOUBLE_CARD
        val session = DexAssistSession(DexAssistEngine { DexAssistResult.Candidates(listOf(card, second)) }, listOf("base.apk"), loader, setOf(query))
        assertTrue(HostCardSemanticAdapter.locate(loader, emptyMap(), session, setOf(query)).isEmpty())
        val trap = DexAssistSession(DexAssistEngine { error("unplanned native scan") }, emptyList(), loader, emptySet())
        assertEquals(mapOf("double" to point(card)), HostCardSemanticAdapter.locate(loader, mapOf("double" to point(card)), trap, emptySet()))
        assertFalse(HostCardSemanticAdapter.validCache(mapOf("outer_page" to point(card))))
    }
    @Test fun renamedPegasusSeparatorRequiresTheCompleteDrawingAndFieldContract() {
        val method = RenamedDivider::class.java.declaredMethods.single { it.name == "onDrawOver" }
        val wrong = WrongDivider::class.java.declaredMethods.single { it.name == "onDrawOver" }
        assertTrue(HostVideoCardDividerHooks.verifiedPegasus(loader, method))
        assertFalse(HostVideoCardDividerHooks.verifiedPegasus(loader, wrong))
        val lazy = LazyDivider::class.java.declaredMethods.single { it.name == "onDrawOver" }
        assertFalse(HostVideoCardDividerHooks.verifiedPegasus(loader, lazy))
        assertEquals(method, HostVideoCardDividerHooks.pegasusBoundary(loader, point(method)))
        val query = DexAssistQuery.PEGASUS_DIVIDER
        val session = DexAssistSession(DexAssistEngine { DexAssistResult.Candidates(listOf(wrong, lazy, method)) }, listOf("base.apk"), loader, setOf(query))
        val points = HostCardSemanticAdapter.locate(loader, emptyMap(), session, setOf(query))
        assertEquals(point(method), points["pegasus_divider"])
    }
}
