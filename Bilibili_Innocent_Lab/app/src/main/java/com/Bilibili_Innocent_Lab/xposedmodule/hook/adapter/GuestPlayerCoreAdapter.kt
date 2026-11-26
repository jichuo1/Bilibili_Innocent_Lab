package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.reflect.Modifier

/**
 * 定位并结构验真游客/无痕播放器的核心实现类（`IPlayerCoreService`）。
 *
 * 云端 8 版本（9.01–9.20）dexq 证实该接口每版仅存在 3 个实现类：
 * 1) `TheseusPlayerContainerProvider$...$1$1$1`
 * 2) `MallCourseDetailFragment$...$1$1$1`
 * 3) 唯一混淆核心类（8.90.2 为 `lf6.p0`，9.x 漂移至两段/根包扁平混淆类）。
 * 本适配器只发现并验真第 3 类，且每次使用前都在宿主 ClassLoader 重新校对完整 ABI。
 */
internal object GuestPlayerCoreAdapter {
    const val I_PLAYER_CORE_SERVICE = "tv.danmaku.biliplayerv2.service.IPlayerCoreService"
    const val MEDIA_RESOURCE = "com.bilibili.lib.media.resource.MediaResource"
    const val MEDIA_ITEM = "tv.danmaku.videoplayer.coreV2.MediaItem"
    const val MEDIA_ITEM_PARAMS = "tv.danmaku.videoplayer.coreV2.transformer.MediaItemParams"
    const val LEGACY_CORE_CLASS = "lf6.p0"
    const val DIAGNOSTIC = "dex.assist.guest_player_core"
    val query = DexAssistQuery.GUEST_PLAYER_CORE_SERVICE
    private const val MAX_CANDIDATES = 32
    private val ANONYMOUS_SUFFIX = Regex("""\$\d+$""")
    private val BLOCKED_PREFIXES = listOf(
        "com.mall.",
        "com.bilibili.ship.theseus.",
        "java.",
        "javax.",
        "android.",
        "androidx.",
        "kotlin.",
        "kotlinx."
    )

    fun applicable(loader: ClassLoader): Boolean =
        Lookup.hasClass(loader, I_PLAYER_CORE_SERVICE) &&
            Lookup.hasClass(loader, MEDIA_RESOURCE) &&
            Lookup.hasClass(loader, MEDIA_ITEM_PARAMS)

    fun allowedOwnerName(name: String): Boolean {
        if (name.length !in 1..512 || name == I_PLAYER_CORE_SERVICE) return false
        if (BLOCKED_PREFIXES.any { name.startsWith(it) } || ANONYMOUS_SUFFIX.containsMatchIn(name)) {
            return false
        }
        return name.split('.').all { segment ->
            segment.isNotEmpty() &&
                Character.isJavaIdentifierStart(segment[0]) &&
                segment.all(Character::isJavaIdentifierPart)
        }
    }

    fun verify(loader: ClassLoader, owner: Class<*>): Boolean = runCatching {
        if (!allowedOwnerName(owner.name) || owner.isInterface || Modifier.isAbstract(owner.modifiers)) {
            return false
        }
        val iface = Lookup.classOrNull(loader, I_PLAYER_CORE_SERVICE) ?: return false
        if (!iface.isAssignableFrom(owner)) return false
        val resource = Lookup.classOrNull(loader, MEDIA_RESOURCE) ?: return false
        val params = Lookup.classOrNull(loader, MEDIA_ITEM_PARAMS) ?: return false
        val item = Lookup.classOrNull(loader, MEDIA_ITEM)
        val hasCurrentPosition = Lookup.methodOrNull(owner, "getCurrentPosition")
            ?.takeIf { !Modifier.isStatic(it.modifiers) && it.returnType == Int::class.javaPrimitiveType } != null
        val hasDuration = Lookup.methodOrNull(owner, "getDuration")
            ?.takeIf {
                !Modifier.isStatic(it.modifiers) &&
                    (it.returnType == Int::class.javaPrimitiveType || it.returnType == Long::class.javaPrimitiveType)
            } != null
        if (!hasCurrentPosition || !hasDuration) return false
        val has3ArgBind = Lookup.methodOrNull(owner, "setMediaResource", resource, Boolean::class.javaPrimitiveType!!, params)
            ?.takeIf { !Modifier.isStatic(it.modifiers) } != null
        val has4ArgBind = item != null && Lookup.methodOrNull(
            owner, "setMediaResource", item, resource, Boolean::class.javaPrimitiveType!!, params
        )?.takeIf { !Modifier.isStatic(it.modifiers) } != null
        has3ArgBind || has4ArgBind
    }.getOrDefault(false)

    fun direct(loader: ClassLoader): Class<*>? =
        Lookup.classOrNull(loader, LEGACY_CORE_CLASS)?.takeIf { verify(loader, it) }

    fun resolve(loader: ClassLoader, cached: String?): Class<*>? {
        direct(loader)?.let { return it }
        val name = cached?.takeIf(::allowedOwnerName) ?: return null
        return Lookup.classOrNull(loader, name)?.takeIf { verify(loader, it) }
    }

    fun needsQuery(loader: ClassLoader, cached: String?, enabled: Boolean, attempted: Boolean): Boolean =
        enabled && !attempted && applicable(loader) && resolve(loader, cached) == null

    fun locate(
        loader: ClassLoader,
        session: DexAssistSession?,
        enabled: Boolean,
        cached: String?,
        planned: Boolean
    ): String? {
        if (!enabled) return cached
        resolve(loader, cached)?.let { return it.name }
        if (!planned) return null
        val candidates = (session?.result(query) as? DexAssistResult.Candidates)?.methods.orEmpty()
        if (candidates.size > MAX_CANDIDATES) return null
        return candidates.map { it.declaringClass }
            .distinct()
            .filter { verify(loader, it) }
            .map { it.name }
            .singleOrNull()
    }
}
