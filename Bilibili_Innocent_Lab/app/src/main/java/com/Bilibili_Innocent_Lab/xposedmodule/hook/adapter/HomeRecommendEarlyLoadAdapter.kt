package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PegasusLoadMoreShape
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.reflect.Modifier

/**
 * 定位并结构验真首页推荐“加载更多”监听器的底层类名。
 *
 * 同步快路径优先走 `PegasusFragment$initFeeds$5` 与 8.84–9.18 的 `com.bilibili.pegasus.widget.*` 边界；
 * 当挂起 lambda 编号漂移导致同步发现落空时，由共享后台 DexMeguviz 从 `PegasusFragment` 出边与任意
 * `PegasusFragment$*` 三参续体构造器发现候选，并经宿主 ClassLoader 复核三字段形状后才允许缓存与安装。
 */
internal object HomeRecommendEarlyLoadAdapter {
    const val DIAGNOSTIC = "dex.assist.home_recommend_early_load"
    val query = DexAssistQuery.HOME_RECOMMEND_EARLY_LOAD_LISTENER
    private const val RECYCLER_SCROLL_LISTENER = "androidx.recyclerview.widget.RecyclerView\$OnScrollListener"
    private const val RECYCLER_ATTACH_LISTENER = "androidx.recyclerview.widget.RecyclerView\$OnChildAttachStateChangeListener"
    private const val FUNCTION0 = "kotlin.jvm.functions.Function0"
    private const val LEGACY_WIDGET_PACKAGE = "com.bilibili.pegasus.widget."
    private const val MAX_CANDIDATES = 32
    private val BLOCKED_PREFIXES = listOf(
        "java.",
        "javax.",
        "android.",
        "androidx.",
        "kotlin.",
        "kotlinx.",
        "dalvik."
    )

    fun allowedClassName(name: String): Boolean {
        if (name.length !in 1..512 || name.startsWith('[')) return false
        if (BLOCKED_PREFIXES.any { name.startsWith(it) }) return false
        return name.split('.').all { segment ->
            segment.isNotEmpty() &&
                Character.isJavaIdentifierStart(segment[0]) &&
                segment.all(Character::isJavaIdentifierPart)
        }
    }

    fun applicable(loader: ClassLoader): Boolean =
        Lookup.hasClass(loader, PegasusLoadMoreShape.PEGASUS_FRAGMENT) &&
            Lookup.hasClass(loader, RECYCLER_SCROLL_LISTENER) &&
            Lookup.hasClass(loader, RECYCLER_ATTACH_LISTENER) &&
            Lookup.hasClass(loader, FUNCTION0)

    fun verify(loader: ClassLoader, candidate: Class<*>): Boolean = runCatching {
        if (!allowedClassName(candidate.name) || candidate.isInterface || Modifier.isAbstract(candidate.modifiers)) {
            return false
        }
        val scroll = Lookup.classOrNull(loader, RECYCLER_SCROLL_LISTENER) ?: return false
        val attach = Lookup.classOrNull(loader, RECYCLER_ATTACH_LISTENER)?.takeIf { it.isInterface } ?: return false
        val function0 = Lookup.classOrNull(loader, FUNCTION0)?.takeIf { it.isInterface } ?: return false
        candidate.superclass == scroll &&
            attach.isAssignableFrom(candidate) &&
            PegasusLoadMoreShape.hasValidFields(candidate, function0) != null
    }.getOrDefault(false)

    fun direct(loader: ClassLoader): Class<*>? {
        val scroll = Lookup.classOrNull(loader, RECYCLER_SCROLL_LISTENER) ?: return null
        val attach = Lookup.classOrNull(loader, RECYCLER_ATTACH_LISTENER)?.takeIf { it.isInterface } ?: return null
        val function0 = Lookup.classOrNull(loader, FUNCTION0)?.takeIf { it.isInterface } ?: return null
        PegasusLoadMoreShape.resolveRelocatedListener(loader, scroll, attach, function0)
            ?.takeIf { verify(loader, it) }
            ?.let { return it }
        return ('a'..'z').mapNotNull { suffix ->
            Lookup.classOrNull(loader, "$LEGACY_WIDGET_PACKAGE$suffix")?.takeIf { verify(loader, it) }
        }.singleOrNull()
    }

    fun resolve(loader: ClassLoader, cached: String?): Class<*>? {
        direct(loader)?.let { return it }
        val name = cached?.takeIf(::allowedClassName) ?: return null
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
        val candidates = (session?.result(query) as? DexAssistResult.Candidates)?.classes.orEmpty()
        if (candidates.size > MAX_CANDIDATES) return null
        return candidates
            .asSequence()
            .filter { verify(loader, it) }
            .distinct()
            .map { it.name }
            .singleOrNull()
    }
}
