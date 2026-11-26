package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FollowUpRecommendFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FollowUpRecommendFeatureInstaller.Family
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import org.json.JSONObject

/** 定位关注后推荐 UP 请求协程体（theseus / theseus2 双家族 `$requestRecommendUp$1` 漂移与根包扁平化兜底）。 */
internal object FollowUpRecommendAdapter {
    const val DIAGNOSTIC = "dex.assist.follow_up_recommend"
    val query = DexAssistQuery.FOLLOW_UP_RECOMMEND_BODY

    private const val THESEUS2_PACKAGE = "com.bilibili.ship.theseus2.ugc.intro.uprecommend."
    private const val THESEUS_PACKAGE = "com.bilibili.ship.theseus.ugc.intro.uprecommend."
    private const val MAX_CANDIDATES = 32
    private val FAMILY_KEYS = Family.entries.map(Family::key).toSet()

    fun applicable(loader: ClassLoader): Boolean =
        Family.entries.any { Lookup.hasClass(loader, it.repository) }

    fun allowedOwnerName(name: String): Boolean = name.length in 1..512 &&
        (name.startsWith(THESEUS2_PACKAGE) ||
            name.startsWith(THESEUS_PACKAGE) ||
            PlayerSpeedMenuAdapter.flattenedOwner(name))

    fun validCache(cached: Map<String, String>): Boolean =
        cached.all { (key, owner) -> key in FAMILY_KEYS && allowedOwnerName(owner) }

    fun toJson(cached: Map<String, String>): JSONObject = JSONObject().apply {
        cached.toSortedMap().forEach { (key, value) -> put(key, value) }
    }

    fun fromJson(o: JSONObject): Map<String, String>? {
        val result = linkedMapOf<String, String>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key !in FAMILY_KEYS || o.isNull(key)) return null
            val owner = o.opt(key) as? String ?: return null
            if (!allowedOwnerName(owner)) return null
            result[key] = owner
        }
        return result
    }

    fun direct(loader: ClassLoader, family: Family): String? {
        val repo = Lookup.classOrNull(loader, family.repository) ?: return null
        val body = Lookup.classOrNull(loader, family.requestBody) ?: return null
        return body.name.takeIf {
            FollowUpRecommendFeatureInstaller.requestBodyEntry(loader, body, repo) != null
        }
    }

    fun resolve(loader: ClassLoader, family: Family, cachedName: String?): String? {
        val name = cachedName?.takeIf(::allowedOwnerName) ?: return null
        val repo = Lookup.classOrNull(loader, family.repository) ?: return null
        val body = Lookup.classOrNull(loader, name) ?: return null
        return body.name.takeIf {
            FollowUpRecommendFeatureInstaller.requestBodyEntry(loader, body, repo) != null
        }
    }

    fun missingFamilies(loader: ClassLoader, cached: Map<String, String>): List<Family> =
        Family.entries.filter { family ->
            Lookup.hasClass(loader, family.repository) &&
                direct(loader, family) == null &&
                resolve(loader, family, cached[family.key]) == null
        }

    fun resolveAll(loader: ClassLoader, cached: Map<String, String>): Map<String, String> =
        buildMap {
            for (family in Family.entries) {
                if (!Lookup.hasClass(loader, family.repository)) continue
                val directName = direct(loader, family)
                val cachedName = resolve(loader, family, cached[family.key])
                val resolved = directName ?: cachedName
                if (resolved != null) put(family.key, resolved)
            }
        }

    fun needsQuery(
        loader: ClassLoader,
        cached: Map<String, String>,
        enabled: Boolean,
        attempted: Boolean
    ): Boolean = enabled && !attempted && missingFamilies(loader, cached).isNotEmpty()

    fun locate(
        loader: ClassLoader,
        session: DexAssistSession?,
        enabled: Boolean,
        cached: Map<String, String>,
        planned: Boolean
    ): Map<String, String> {
        if (!enabled) return cached
        val missing = missingFamilies(loader, cached)
        val candidates = if (planned && missing.isNotEmpty()) {
            val result = session?.result(query) as? DexAssistResult.Candidates
            if (result != null && result.classes.size <= MAX_CANDIDATES && result.methods.size <= MAX_CANDIDATES) {
                (result.classes + result.methods.map { it.declaringClass })
                    .distinctBy(Class<*>::getName)
            } else {
                emptyList()
            }
        } else {
            emptyList()
        }
        return buildMap {
            for (family in Family.entries) {
                val repo = Lookup.classOrNull(loader, family.repository) ?: continue
                if (direct(loader, family) != null) {
                    resolve(loader, family, cached[family.key])?.let { put(family.key, it) }
                    continue
                }
                val resolvedCached = resolve(loader, family, cached[family.key])
                if (resolvedCached != null) {
                    put(family.key, resolvedCached)
                    continue
                }
                if (!planned || candidates.isEmpty()) continue
                candidates.asSequence()
                    .filter { candidate ->
                        allowedOwnerName(candidate.name) &&
                            FollowUpRecommendFeatureInstaller.requestBodyEntry(loader, candidate, repo) != null
                    }
                    .map(Class<*>::getName)
                    .singleOrNull()
                    ?.let { put(family.key, it) }
            }
        }
    }
}
