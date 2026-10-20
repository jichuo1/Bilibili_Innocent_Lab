package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HostVideoCardDividerHooks
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostOgvCardAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.reflect.Method

/** Source trace markers select single-card leaves, never their Lazy/outer page lambdas. */
internal object HostCardSemanticAdapter {
    val families = linkedMapOf(
        DexAssistQuery.OGV_TRENDING_CARD to "trending", DexAssistQuery.OGV_DOUBLE_CARD to "double",
        DexAssistQuery.OGV_UPCOMING_CARD to "upcoming", DexAssistQuery.PEGASUS_DIVIDER to "pegasus_divider")
    val queries: Set<DexAssistQuery> get() = families.keys
    val marks = mapOf(
        DexAssistQuery.OGV_TRENDING_CARD to "com.bilibili.ogv.kmm.operation.trending.TrendingInnerItem (",
        DexAssistQuery.OGV_DOUBLE_CARD to "com.bilibili.ogv.kmm.operation.doublefeed.FeedItemUi (",
        DexAssistQuery.OGV_UPCOMING_CARD to "com.bilibili.ogv.kmm.operation.coming.ComingCardItem (")

    fun validCache(points: Map<String, HookPoint>): Boolean = points.all { (family, point) ->
        family in families.values && point.className.isNotBlank() && point.methodName.isNotBlank() &&
            point.viewField == null && point.paramClassNames?.let { it.size in 3..6 && it.all(String::isNotBlank) } == true
    }
    fun ready(loader: ClassLoader, cached: Map<String, HookPoint>, query: DexAssistQuery): Boolean {
        val family = families[query] ?: return false
        return if (query == DexAssistQuery.PEGASUS_DIVIDER) HostVideoCardDividerHooks.pegasusBoundary(loader, cached[family]) != null
        else HostOgvCardAdapter.resolve(loader, cached).families.singleOrNull { it.id == family }?.points?.isNotEmpty() == true
    }
    fun applicable(loader: ClassLoader, query: DexAssistQuery): Boolean = Lookup.classOrNull(loader,
        if (query == DexAssistQuery.PEGASUS_DIVIDER) "com.bilibili.pegasus.PegasusFragment"
        else "com.bilibili.ogv.operation3.module.operablecard.a") != null

    fun verified(loader: ClassLoader, family: String, method: Method): Boolean =
        if (family == "pegasus_divider") HostVideoCardDividerHooks.verifiedPegasus(loader, method)
        else family in families.values && HostOgvCardAdapter.semanticPoint(loader, method) != null

    fun resolve(loader: ClassLoader, family: String, point: HookPoint?): Method? {
        point ?: return null
        if (!validCache(mapOf(family to point))) return null
        val owner = Lookup.classOrNull(loader, point.className) ?: return null
        return Lookup.declaredMethods(owner, true) { it.name == point.methodName &&
            it.parameterTypes.map(Class<*>::getName) == point.paramClassNames && verified(loader, family, it) }.singleOrNull()
    }
    fun locate(loader: ClassLoader, cached: Map<String, HookPoint>, session: DexAssistSession?,
        planned: Set<DexAssistQuery>): Map<String, HookPoint> = buildMap {
        families.forEach { (query, family) ->
            val candidates = if (query in planned) (session?.result(query) as? DexAssistResult.Candidates)?.methods.orEmpty() else emptyList()
            val method = candidates.filter { verified(loader, family, it) }.distinct().singleOrNull()
                ?: if (query !in planned) resolve(loader, family, cached[family]) else null
            method?.let { put(family, HookPoint(it.declaringClass.name, it.name, it.parameterTypes.map(Class<*>::getName))) }
        }
    }
}
