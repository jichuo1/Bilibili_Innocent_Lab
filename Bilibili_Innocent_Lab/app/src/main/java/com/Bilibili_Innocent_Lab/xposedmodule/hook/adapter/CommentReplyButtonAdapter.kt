package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.commentReplyBindingShape
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isSubclassOf
import org.json.JSONObject
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Discovers and structurally verifies the three native comment action-bar reply-button handler
 * owners (`legacy`, `next`, `exp3`) across obfuscation/R8 flattening without hardcoded root names.
 */
internal object CommentReplyButtonAdapter {
    const val DIAGNOSTIC = "dex.assist.comment_reply_button_native"
    val query = DexAssistQuery.COMMENT_REPLY_BUTTON_NATIVE
    const val COMMENT_ITEM = "com.bilibili.app.comment3.data.model.CommentItem"
    const val HOLDER_PACKAGE = "com.bilibili.app.comment3.ui.holder"
    const val PLANT_SEEDS_VIEW = "com.bilibili.app.comment3.ui.widget.CommentCmPlantSeedsView"
    val FAMILIES = listOf("legacy", "next", "exp3")
    val LEGACY_PACKAGES = mapOf(
        "legacy" to "com.bilibili.app.comment3.ui.holder.handle",
        "next" to "com.bilibili.app.comment3.ui.nextholder.handle",
        "exp3" to "com.bilibili.app.comment3.ui.nextholderexp3.handle"
    )
    private const val MAX_CANDIDATES = 64

    data class NativePlan(val method: Method, val index: Int, val binding: Class<*>)

    fun applicable(loader: ClassLoader): Boolean =
        Lookup.hasClass(loader, COMMENT_ITEM)

    fun allowedOwnerName(name: String): Boolean = name.length in 1..512 &&
        (LEGACY_PACKAGES.values.any { name.startsWith("$it.") } || PlayerSpeedMenuAdapter.flattenedOwner(name))

    fun validCache(owners: Map<String, String>): Boolean =
        owners.keys.all { it in FAMILIES } && owners.values.all(::allowedOwnerName)

    fun toJson(owners: Map<String, String>): JSONObject = JSONObject().apply {
        owners.forEach { (family, owner) -> put(family, owner) }
    }

    fun fromJson(obj: JSONObject): Map<String, String>? = runCatching {
        val result = linkedMapOf<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key !in FAMILIES || obj.isNull(key)) return null
            val owner = (obj.opt(key) as? String)?.takeIf(::allowedOwnerName) ?: return null
            result[key] = owner
        }
        result
    }.getOrNull()

    internal fun bindingShape(type: Class<*>): String? {
        if (type.isPrimitive || type.isArray || type.isInterface) return null
        if (type.interfaces.none { it.name == "androidx.viewbinding.ViewBinding" }) return null
        if (Lookup.declaredMethods(type).none {
                it.name == "getRoot" && it.parameterCount == 0 && it.returnType isSubclassOf classOf<View>()
            }
        ) return null
        val fields = Lookup.declaredFields(type).filterNot { it.isStatic }
        if (fields.any { !Modifier.isFinal(it.modifiers) }) return null
        return commentReplyBindingShape(fields.map { it.type.name })
    }

    internal fun inspectOwnerForAnyFamily(owner: Class<*>): Pair<String, List<NativePlan>>? = runCatching {
        if (!allowedOwnerName(owner.name) || owner.isInterface || Modifier.isAbstract(owner.modifiers)) {
            return null
        }
        val bindingShapes = hashMapOf<Class<*>, String?>()
        val matched = Lookup.declaredMethods(owner).mapNotNull { method ->
            if (method.isBridge || method.isSynthetic || method.returnType != Void.TYPE ||
                method.parameterCount !in 2..5 || method.parameterTypes[0].name != COMMENT_ITEM
            ) return@mapNotNull null
            val hits = method.parameterTypes.indices.mapNotNull { index ->
                val type = method.parameterTypes[index]
                val shape = if (bindingShapes.containsKey(type)) {
                    bindingShapes[type]
                } else {
                    bindingShape(type).also { bindingShapes[type] = it }
                }
                shape?.let { index to (type to it) }
            }
            if (hits.size != 1) null
            else {
                val (index, bindingAndShape) = hits.single()
                NativePlan(method, index, bindingAndShape.first) to bindingAndShape.second
            }
        }
        if (matched.isEmpty()) return null
        val family = matched.map { it.second }.distinct().singleOrNull() ?: return null
        val plans = matched.map { it.first }
        if (plans.map { it.binding }.distinct().size != 1) return null
        family to plans
    }.getOrNull()

    internal fun inspectOwner(owner: Class<*>, family: String): List<NativePlan> =
        inspectOwnerForAnyFamily(owner)?.takeIf { it.first == family }?.second.orEmpty()

    private fun directPlansFor(loader: ClassLoader, families: Collection<String>): Map<String, List<NativePlan>> {
        val result = linkedMapOf<String, List<NativePlan>>()
        LEGACY_PACKAGES.forEach { (family, packageName) ->
            if (family !in families) return@forEach
            val byOwner = ('a'..'z').mapNotNull { suffix ->
                val owner = Lookup.classOrNull(loader, "$packageName.$suffix") ?: return@mapNotNull null
                inspectOwner(owner, family).takeIf { it.isNotEmpty() }
            }
            byOwner.singleOrNull()?.let { result[family] = it }
        }
        return result
    }

    fun directPlans(loader: ClassLoader): Map<String, List<NativePlan>> =
        directPlansFor(loader, FAMILIES)

    fun directOwners(loader: ClassLoader): Map<String, String> =
        directPlans(loader).mapValues { (_, plans) -> plans.first().method.declaringClass.name }

    fun resolvePlans(loader: ClassLoader, cached: Map<String, String>): Map<String, List<NativePlan>> {
        val result = linkedMapOf<String, List<NativePlan>>()
        FAMILIES.forEach { family ->
            val ownerName = cached[family]?.takeIf(::allowedOwnerName) ?: return@forEach
            val owner = Lookup.classOrNull(loader, ownerName) ?: return@forEach
            val plans = inspectOwner(owner, family)
            if (plans.isNotEmpty()) result[family] = plans
        }
        val missing = FAMILIES.filter { it !in result }
        if (missing.isNotEmpty()) {
            result.putAll(directPlansFor(loader, missing))
        }
        return result
    }

    fun resolveOwners(loader: ClassLoader, cached: Map<String, String>): Map<String, String> =
        resolvePlans(loader, cached).mapValues { (_, plans) -> plans.first().method.declaringClass.name }

    fun needsQuery(
        loader: ClassLoader,
        cached: Map<String, String>,
        enabled: Boolean,
        attempted: Boolean
    ): Boolean = enabled && !attempted && applicable(loader) && resolveOwners(loader, cached).size < FAMILIES.size

    fun locate(
        loader: ClassLoader,
        session: DexAssistSession?,
        enabled: Boolean,
        cached: Map<String, String>,
        planned: Boolean
    ): Map<String, String> {
        if (!enabled) return cached
        val resolved = resolveOwners(loader, cached).toMutableMap()
        if (!planned || resolved.size == FAMILIES.size) return resolved
        val candidates = (session?.result(query) as? DexAssistResult.Candidates)?.methods.orEmpty()
        if (candidates.isEmpty() || candidates.size > MAX_CANDIDATES) return resolved
        val discoveredByFamily = linkedMapOf<String, MutableSet<String>>()
        candidates.map { it.declaringClass }.distinct().forEach { owner ->
            val (family, _) = inspectOwnerForAnyFamily(owner) ?: return@forEach
            discoveredByFamily.getOrPut(family) { linkedSetOf() }.add(owner.name)
        }
        FAMILIES.forEach { family ->
            if (family in resolved) return@forEach
            discoveredByFamily[family]?.singleOrNull()?.let { resolved[family] = it }
        }
        return resolved
    }
}
