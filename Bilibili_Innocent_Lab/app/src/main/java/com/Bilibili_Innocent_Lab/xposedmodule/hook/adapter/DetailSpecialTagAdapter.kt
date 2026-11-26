package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.DetailUnitedPresentationPurifyPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SpecialTopicTagsModelCleaner
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Discovers and structurally verifies the detail page `SpecialTag -> tags model` static mapper
 * when relocated or flattened by R8; cached points are always revalidated against the live ABI.
 */
internal object DetailSpecialTagAdapter {
    const val DIAGNOSTIC = "dex.assist.detail_special_tag_mapper"
    val query = DexAssistQuery.DETAIL_SPECIAL_TAG_MAPPER
    const val SPECIAL_TAG_CLASS = DetailUnitedPresentationPurifyPolicy.SPECIAL_TAG_CLASS
    const val LEGACY_MAPPER_PACKAGE = "com.bilibili.ship.theseus.united.page.intro.module.tags"
    private const val MAX_CANDIDATES = 32
    private val legacyMapperClasses: List<String> =
        (listOf("f", "j") + ('a'..'z').map(Char::toString))
            .distinct()
            .map { "$LEGACY_MAPPER_PACKAGE.$it" }

    data class Plan(val method: Method, val cleaner: SpecialTopicTagsModelCleaner)

    fun applicable(loader: ClassLoader): Boolean =
        Lookup.hasClass(loader, SPECIAL_TAG_CLASS)

    fun allowedOwnerName(name: String): Boolean = name.length in 1..512 &&
        (name.startsWith("$LEGACY_MAPPER_PACKAGE.") || PlayerSpeedMenuAdapter.flattenedOwner(name))

    fun inspectMapper(loader: ClassLoader, owner: Class<*>): Plan? = runCatching {
        if (!allowedOwnerName(owner.name) || owner.isInterface || Modifier.isAbstract(owner.modifiers)) {
            return null
        }
        val specialTag = Lookup.classOrNull(loader, SPECIAL_TAG_CLASS) ?: return null
        val candidates = Lookup.declaredMethods(owner, makeAccessible = true) { method ->
            Modifier.isStatic(method.modifiers) &&
                !method.isSynthetic &&
                !method.isBridge &&
                method.parameterTypes.contentEquals(arrayOf(specialTag)) &&
                method.returnType != specialTag &&
                !method.returnType.name.startsWith("com.bapis.") &&
                !method.returnType.isPrimitive &&
                method.returnType != Void.TYPE
        }.mapNotNull { method ->
            SpecialTopicTagsModelCleaner.resolve(method.returnType)?.let { cleaner ->
                Plan(method, cleaner)
            }
        }
        candidates.distinctBy { it.method.toGenericString() }.singleOrNull()
    }.getOrNull()

    fun direct(loader: ClassLoader): Plan? = runCatching {
        legacyMapperClasses.mapNotNull { className ->
            Lookup.classOrNull(loader, className)?.let { inspectMapper(loader, it) }
        }.distinctBy { it.method.toGenericString() }.singleOrNull()
    }.getOrNull()

    fun resolve(loader: ClassLoader, cached: HookPoint?): Plan? {
        direct(loader)?.let { return it }
        cached ?: return null
        if (cached.viewField != null ||
            cached.paramClassNames != listOf(SPECIAL_TAG_CLASS) ||
            !allowedOwnerName(cached.className)
        ) {
            return null
        }
        val owner = Lookup.classOrNull(loader, cached.className) ?: return null
        return inspectMapper(loader, owner)?.takeIf { it.method.name == cached.methodName }
    }

    fun needsQuery(
        loader: ClassLoader,
        cached: HookPoint?,
        enabled: Boolean,
        attempted: Boolean
    ): Boolean = enabled && !attempted && applicable(loader) && resolve(loader, cached) == null

    fun locate(
        loader: ClassLoader,
        session: DexAssistSession?,
        enabled: Boolean,
        cached: HookPoint?,
        planned: Boolean
    ): HookPoint? {
        if (!enabled) return cached
        resolve(loader, cached)?.let { return point(it.method) }
        if (!planned) return null
        val candidates = (session?.result(query) as? DexAssistResult.Candidates)?.methods.orEmpty()
        if (candidates.isEmpty() || candidates.size > MAX_CANDIDATES) return null
        val matched = candidates.mapNotNull { method ->
            inspectMapper(loader, method.declaringClass)?.takeIf { it.method == method }
        }.distinctBy { it.method.toGenericString() }.singleOrNull() ?: return null
        return point(matched.method)
    }

    private fun point(method: Method): HookPoint = HookPoint(
        method.declaringClass.name,
        method.name,
        method.parameterTypes.map(Class<*>::getName)
    )
}
