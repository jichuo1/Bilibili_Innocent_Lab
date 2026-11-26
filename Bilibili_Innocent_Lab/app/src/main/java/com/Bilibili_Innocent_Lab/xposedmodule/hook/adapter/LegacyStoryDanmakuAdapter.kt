package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import android.widget.ImageView
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.reflect.Method

/** 定位旧 Story 底部弹幕开关绑定方法（同 owner 多 `(player, Int) -> Unit` 重载时用 DEX 读写事实消歧）。 */
internal object LegacyStoryDanmakuAdapter {
    const val DIAGNOSTIC = "dex.assist.legacy_story_danmaku"
    val query = DexAssistQuery.LEGACY_STORY_DANMAKU_BIND

    const val OWNER = LegacyStoryDanmakuAccess.OWNER
    private const val MAX_CANDIDATES = 16

    fun applicable(loader: ClassLoader, toggleType: Class<*> = ImageView::class.java): Boolean {
        val owner = Lookup.classOrNull(loader, OWNER) ?: return false
        return LegacyStoryDanmakuAccess.resolvePlayerType(owner, toggleType) != null
    }

    fun direct(loader: ClassLoader, toggleType: Class<*> = ImageView::class.java): HookPoint? {
        val access = LegacyStoryDanmakuAccess.resolve(loader, toggleType, cachedBindMethod = null) ?: return null
        return access.bind.toHookPoint()
    }

    fun resolve(
        loader: ClassLoader,
        cached: HookPoint?,
        toggleType: Class<*> = ImageView::class.java
    ): HookPoint? {
        val point = cached ?: return null
        if (point.className != OWNER || point.viewField != null ||
            point.paramClassNames?.size != 2 || point.paramClassNames[1] != "int"
        ) {
            return null
        }
        return LegacyStoryDanmakuAccess.resolveWithCachedBind(loader, point, toggleType)?.bind?.toHookPoint()
    }

    fun needsQuery(
        loader: ClassLoader,
        cached: HookPoint?,
        enabled: Boolean,
        attempted: Boolean,
        toggleType: Class<*> = ImageView::class.java
    ): Boolean = enabled && !attempted && applicable(loader, toggleType) &&
        direct(loader, toggleType) == null && resolve(loader, cached, toggleType) == null

    fun locate(
        loader: ClassLoader,
        session: DexAssistSession?,
        enabled: Boolean,
        cached: HookPoint?,
        planned: Boolean,
        toggleType: Class<*> = ImageView::class.java
    ): HookPoint? {
        if (!enabled) return cached
        direct(loader, toggleType)?.let { return cached?.takeIf { resolve(loader, it, toggleType) != null } }
        resolve(loader, cached, toggleType)?.let { return it }
        if (!planned) return null
        val owner = Lookup.classOrNull(loader, OWNER) ?: return null
        val player = LegacyStoryDanmakuAccess.resolvePlayerType(owner, toggleType) ?: return null
        val candidates = (session?.result(query) as? DexAssistResult.Candidates)?.methods.orEmpty()
        if (candidates.size > MAX_CANDIDATES) return null
        return candidates.asSequence()
            .filter {
                it.declaringClass == owner && LegacyStoryDanmakuAccess.matchesBindCandidate(it, player)
            }
            .distinctBy(Method::toGenericString)
            .singleOrNull()
            ?.toHookPoint()
    }

    private fun Method.toHookPoint(): HookPoint = HookPoint(
        className = declaringClass.name,
        methodName = name,
        paramClassNames = parameterTypes.map(Class<*>::getName)
    )
}
