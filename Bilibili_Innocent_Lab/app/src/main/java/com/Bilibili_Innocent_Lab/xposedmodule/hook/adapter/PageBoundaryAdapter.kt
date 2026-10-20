package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerEndPageRecommendLocator
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import org.json.JSONObject

data class PageBoundaryPoints(val searchDelivery: String? = null, val searchState: String? = null,
    val endPageMerger: HookPoint? = null, val cardSurfaces: Map<String, HookPoint> = emptyMap()) {
    fun isValid(): Boolean = HostCardSemanticAdapter.validCache(cardSurfaces) &&
        (searchDelivery == null || searchDelivery.startsWith(SearchHomeRecommendLocator.DISCOVER)) &&
        (searchState == null || searchState.startsWith(SearchHomeRecommendLocator.STATE)) &&
        (endPageMerger == null || endPageMerger.className.startsWith(PlayerEndPageRecommendLocator.PACKAGE) &&
            endPageMerger.methodName.isNotBlank() && endPageMerger.viewField == null && endPageMerger.paramClassNames == listOf("java.util.List", "java.util.List"))
    fun toJson() = JSONObject().apply {
        searchDelivery?.let { put("search_delivery", it) }
        searchState?.let { put("search_state", it) }
        endPageMerger?.let { put("end_page_merger", it.toJson()) }
        if (cardSurfaces.isNotEmpty()) put("card_surfaces", JSONObject().apply {
            cardSurfaces.forEach { (family, point) -> put(family, point.toJson()) }
        })
    }
    companion object {
        fun fromJson(value: JSONObject): PageBoundaryPoints? = runCatching {
            PageBoundaryPoints(if (value.has("search_delivery")) value.getString("search_delivery") else null,
                if (value.has("search_state")) value.getString("search_state") else null,
                if (value.has("end_page_merger")) HookPoint.fromJson(value.getJSONObject("end_page_merger")) else null,
                if (value.has("card_surfaces")) value.getJSONObject("card_surfaces").let { cards -> cards.keys().asSequence().associateWith {
                    HookPoint.fromJson(cards.getJSONObject(it))
                } } else emptyMap())
                .takeIf { it.isValid() }
        }.getOrNull()
    }
}

/** Page-specific, typed boundaries; persisted only inside the exact host/rule fingerprint. */
internal object PageBoundaryAdapter {
    val queries = setOf(DexAssistQuery.SEARCH_HOME_DELIVERY, DexAssistQuery.SEARCH_HOME_STATE,
        DexAssistQuery.PLAYER_END_PAGE_MERGER) + HostCardSemanticAdapter.queries
    fun diagnostic(query: DexAssistQuery) = "dex.assist.page_boundary." + query.name.lowercase()


    fun ready(loader: ClassLoader, cached: PageBoundaryPoints?, query: DexAssistQuery): Boolean = when (query) {
        DexAssistQuery.SEARCH_HOME_DELIVERY -> SearchHomeRecommendLocator.locate(loader, cached)?.delivery != null
        DexAssistQuery.SEARCH_HOME_STATE -> SearchHomeRecommendLocator.locate(loader, cached)?.let { !it.stateExpected || it.state != null } == true
        DexAssistQuery.PLAYER_END_PAGE_MERGER -> PlayerEndPageRecommendLocator.resolve(loader, cached?.endPageMerger)?.mergedList != null
        else -> HostCardSemanticAdapter.ready(loader, cached?.cardSurfaces.orEmpty(), query)
    }

    fun planned(loader: ClassLoader, cached: PageBoundaryPoints?, searchEnabled: Boolean, endPageEnabled: Boolean,
        attempted: Set<DexAssistQuery>, styleEnabled: Boolean = false): Set<DexAssistQuery> = buildSet {
        if (styleEnabled) HostCardSemanticAdapter.queries.filterTo(this) { query ->
            query !in attempted && HostCardSemanticAdapter.applicable(loader, query) &&
                !HostCardSemanticAdapter.ready(loader, cached?.cardSurfaces.orEmpty(), query)
        }
        if (searchEnabled) SearchHomeRecommendLocator.locate(loader, cached)?.let { search ->
            if (search.delivery == null && DexAssistQuery.SEARCH_HOME_DELIVERY !in attempted) add(DexAssistQuery.SEARCH_HOME_DELIVERY)
            if (search.stateExpected && search.state == null && DexAssistQuery.SEARCH_HOME_STATE !in attempted) add(DexAssistQuery.SEARCH_HOME_STATE)
        }
        if (endPageEnabled && Lookup.classOrNull(loader, PlayerEndPageRecommendLocator.SERVICE) != null &&
            PlayerEndPageRecommendLocator.resolve(loader, cached?.endPageMerger)?.mergedList == null &&
            DexAssistQuery.PLAYER_END_PAGE_MERGER !in attempted) add(DexAssistQuery.PLAYER_END_PAGE_MERGER)
    }

    fun locate(loader: ClassLoader, cached: PageBoundaryPoints?, session: DexAssistSession?, planned: Set<DexAssistQuery>): PageBoundaryPoints? {
        fun candidates(query: DexAssistQuery): DexAssistResult.Candidates? = if (query in planned)
            session?.result(query) as? DexAssistResult.Candidates else null
        val delivery = candidates(DexAssistQuery.SEARCH_HOME_DELIVERY)?.methods.orEmpty()
            .map { it.declaringClass }.distinct().filter { it.name.startsWith(SearchHomeRecommendLocator.DISCOVER) }
            .filter { SearchHomeRecommendLocator.delivery(it) != null }.singleOrNull()?.name
        val state = candidates(DexAssistQuery.SEARCH_HOME_STATE)?.classes.orEmpty().distinct()
            .filter { it.name.startsWith(SearchHomeRecommendLocator.STATE) }
            .filter { SearchHomeRecommendLocator.state(it) != null }.singleOrNull()?.name
        val merger = candidates(DexAssistQuery.PLAYER_END_PAGE_MERGER)?.methods.orEmpty()
            .filter(PlayerEndPageRecommendLocator::mergerAbi).distinct().singleOrNull()
            ?.let { HookPoint(it.declaringClass.name, it.name, it.parameterTypes.map(Class<*>::getName)) }
        // Revalidate every stored owner with the current host loader; never trust a deserialized name.
        return PageBoundaryPoints(delivery ?: cached?.searchDelivery?.takeIf { name ->
            Lookup.classOrNull(loader, name)?.let(SearchHomeRecommendLocator::delivery) != null
        }, state ?: cached?.searchState?.takeIf { name ->
            Lookup.classOrNull(loader, name)?.let(SearchHomeRecommendLocator::state) != null
        }, merger ?: cached?.endPageMerger?.takeIf { PlayerEndPageRecommendLocator.cachedMerger(loader, it) != null },
            HostCardSemanticAdapter.locate(loader, cached?.cardSurfaces.orEmpty(), session, planned))
            .takeIf { it.searchDelivery != null || it.searchState != null || it.endPageMerger != null || it.cardSurfaces.isNotEmpty() }
    }
}
