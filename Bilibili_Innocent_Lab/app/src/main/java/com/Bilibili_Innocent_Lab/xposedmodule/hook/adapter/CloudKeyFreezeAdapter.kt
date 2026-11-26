package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.reflect.Modifier

/** 发现被 R8 扁平化的 TypedContract FeatureFlagContract 子类；复用时重新验真继承与方法签名。 */
internal object CloudKeyFreezeAdapter {
    const val TYPED_CONTRACT_CLASS = "com.bilibili.lib.blconfig.internal.TypedContract"
    const val FEATURE_FLAG_CONTRACT_CLASS = "com.bilibili.lib.blconfig.FeatureFlagContract"
    const val BLCONFIG_INTERNAL_PACKAGE = "com.bilibili.lib.blconfig.internal."
    const val DIAGNOSTIC = "dex.assist.cloud_key_freeze_contract"
    val query = DexAssistQuery.CLOUD_KEY_FREEZE_CONTRACT
    private const val MAX_CANDIDATES = 32
    private val LEAF_NAMES = ('a'..'z').map(Char::toString) + ('A'..'Z').map(Char::toString)

    fun applicable(loader: ClassLoader): Boolean =
        Lookup.hasClass(loader, TYPED_CONTRACT_CLASS) && Lookup.hasClass(loader, FEATURE_FLAG_CONTRACT_CLASS)

    fun allowedOwnerName(name: String): Boolean = name.length in 1..512 &&
        (name.startsWith(BLCONFIG_INTERNAL_PACKAGE) || PlayerSpeedMenuAdapter.flattenedOwner(name))

    fun verified(loader: ClassLoader, owner: Class<*>): Boolean = runCatching {
        if (!allowedOwnerName(owner.name)) return false
        val base = Lookup.classOrNull(loader, TYPED_CONTRACT_CLASS) ?: return false
        val flag = Lookup.classOrNull(loader, FEATURE_FLAG_CONTRACT_CLASS) ?: return false
        if (owner.superclass != base || !flag.isAssignableFrom(owner)) return false
        Lookup.declaredMethods(owner) {
            !Modifier.isStatic(it.modifiers) && !Modifier.isAbstract(it.modifiers) &&
                it.name == "getWithDefault" && it.returnType == Boolean::class.javaPrimitiveType &&
                it.parameterTypes.contentEquals(arrayOf(String::class.java, Boolean::class.javaPrimitiveType!!))
        }.size == 1
    }.getOrDefault(false)

    fun direct(loader: ClassLoader): String? =
        LEAF_NAMES.mapNotNull { Lookup.classOrNull(loader, BLCONFIG_INTERNAL_PACKAGE + it) }
            .filter { verified(loader, it) }
            .map { it.name }
            .distinct()
            .singleOrNull()

    fun resolve(loader: ClassLoader, cached: String?): String? {
        val name = cached?.takeIf(::allowedOwnerName) ?: return null
        return Lookup.classOrNull(loader, name)?.takeIf { verified(loader, it) }?.name
    }

    fun needsQuery(loader: ClassLoader, cached: String?, enabled: Boolean, attempted: Boolean): Boolean =
        enabled && !attempted && applicable(loader) && resolve(loader, cached) == null && direct(loader) == null

    fun locate(
        loader: ClassLoader,
        session: DexAssistSession?,
        enabled: Boolean,
        cached: String?,
        planned: Boolean
    ): String? {
        if (!enabled) return cached
        direct(loader)?.let { return it }
        resolve(loader, cached)?.let { return it }
        if (!planned) return null
        val candidates = (session?.result(query) as? DexAssistResult.Candidates)?.methods.orEmpty()
        if (candidates.size > MAX_CANDIDATES) return null
        return candidates.map { it.declaringClass }
            .distinct()
            .filter { verified(loader, it) }
            .map { it.name }
            .singleOrNull()
    }
}
