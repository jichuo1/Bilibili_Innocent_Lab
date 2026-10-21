package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome.HomeChromeComposeLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome.HomeChromeSemanticsPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome.HostComposeSemanticsAccess
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import org.json.JSONObject

/** Semantic local chrome boundaries share the existing background DEX session and host cache. */
internal object HomeChromeAdapter {
    val SEMANTICS_MARKS = listOf("ContentDescription", "StateDescription", "TestTag")
    const val SEMANTICS_DIAGNOSTIC = "home.chrome.semantics"
    val families = linkedMapOf(DexAssistQuery.HOME_CHROME_TOP to "top", DexAssistQuery.HOME_CHROME_BOTTOM to "bottom",
        DexAssistQuery.HOME_CHROME_BOTTOM_ITEM to "bottom_item", DexAssistQuery.HOME_CHROME_BOTTOM_CONTENT to "bottom_content")
    val marks = mapOf(
        DexAssistQuery.HOME_CHROME_TOP to "tv.danmaku.bili.khome.widget.topbar.HomeTopTabRow (",
        DexAssistQuery.HOME_CHROME_BOTTOM to "tv.danmaku.bili.khome.widget.bottomtab.HomeBottomTabContainer (",
        DexAssistQuery.HOME_CHROME_BOTTOM_ITEM to "tv.danmaku.bili.khome.widget.bottomtab.HomeBottomTabItemView (",
        DexAssistQuery.HOME_CHROME_BOTTOM_CONTENT to "tv.danmaku.bili.khome.widget.bottomtab.HomeBottomTabContainer."
    )
    fun diagnostic(query: DexAssistQuery) = "home.chrome.${families.getValue(query)}"
    fun applicable(loader: ClassLoader): Boolean = KavaMemberLookup.classOrNull(loader,
        "tv.danmaku.bili.khome.vm.HomeFrameViewModel") != null

    fun validCache(points: Map<String, HookPoint>): Boolean = points.all { (family, point) ->
        family in families.values && point.className.startsWith("tv.danmaku.bili.khome.widget.${if (family == "top") "topbar" else "bottomtab"}.") && point.methodName.isNotBlank() &&
            point.viewField == null && point.paramClassNames?.let {
                (if (family == "bottom_content") it.size in setOf(2, 3, 12) else it.size in 4..20) && it.all(String::isNotBlank)
            } == true
    }

    /** Only completed structural absence/ambiguity is a negative cache; native failures can retry. */
    fun conclusive(result: DexAssistResult?): Boolean = result is DexAssistResult.Candidates ||
        result is DexAssistResult.Unavailable && result.reason in setOf(
            DexAssistResult.Reason.NO_MATCH, DexAssistResult.Reason.TOO_MANY_MATCHES)

    fun semanticsNeedsQuery(loader: ClassLoader, previous: HomeChromeSemanticsPoint?, enabled: Boolean,
        attempted: Boolean, iconOnly: Boolean): Boolean = enabled && iconOnly && !attempted && applicable(loader) &&
        HostComposeSemanticsAccess.resolve(loader, previous) == null

    fun locateSemantics(loader: ClassLoader, previous: HomeChromeSemanticsPoint?, result: DexAssistResult?): HomeChromeSemanticsPoint? {
        if (previous != null && HostComposeSemanticsAccess.resolve(loader, previous) != null) return previous
        return HostComposeSemanticsAccess.locate(loader, (result as? DexAssistResult.Candidates)?.classes.orEmpty())
    }

    fun planned(loader: ClassLoader, previous: Map<String, HookPoint>, enabled: Boolean,
        attempted: Set<DexAssistQuery>, iconOnly: Boolean = false): Set<DexAssistQuery> {
        if (!enabled || !applicable(loader)) return emptySet()
        val available = HomeChromeComposeLocator.resolve(loader, previous)
        return families.filter { (query, family) -> (family != "bottom_item" || iconOnly) &&
            family !in available && query !in attempted }.keys
    }

    fun locate(loader: ClassLoader, previous: Map<String, HookPoint>, session: DexAssistSession?,
        planned: Set<DexAssistQuery>): Map<String, HookPoint> = buildMap {
        putAll(HomeChromeComposeLocator.resolve(loader, previous))
        planned.forEach { query ->
            val family = families.getValue(query)
            val candidates = (session?.result(query) as? DexAssistResult.Candidates)?.methods.orEmpty()
                .filter { HomeChromeComposeLocator.verified(loader, family, it) }.distinct()
            candidates.singleOrNull()?.let { method ->
                put(family, HookPoint(method.declaringClass.name, method.name, method.parameterTypes.map(Class<*>::getName)))
            }
        }
    }

    fun toJson(points: Map<String, HookPoint>) = JSONObject().apply { points.forEach { (key, point) -> put(key, point.toJson()) } }
    fun fromJson(value: JSONObject): Map<String, HookPoint>? = runCatching {
        value.keys().asSequence().associateWith { HookPoint.fromJson(value.getJSONObject(it)) }.takeIf(::validCache)
    }.getOrNull()
}
