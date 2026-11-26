package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isAbstract
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isSubclassOf
import java.lang.reflect.Method

/** 定位详情页相关游戏推广组件与 Gemini UI 抽象基类（支持字母表外扩展与 R8 根包扁平化）。 */
internal object DetailRelateGameAdapter {
    const val DIAGNOSTIC = "dex.assist.detail_relate_game"
    val query = DexAssistQuery.DETAIL_RELATE_GAME_COMPONENT

    const val RELATE_GAME_PACKAGE = "com.bilibili.ship.theseus.united.page.intro.module.relate.game"
    const val GEMINI_UI_PACKAGE = "com.bilibili.app.gemini.ui"
    const val ANDROIDX_VIEW_BINDING = "androidx.viewbinding.ViewBinding"
    const val GEMINI_SIMPLE_VIEW_ENTRY = "com.bilibili.app.gemini.ui.UIComponent\$b"
    const val KOTLIN_CONTINUATION = "kotlin.coroutines.Continuation"
    const val CREATE_VIEW_ENTRY = "createViewEntry"
    const val BIND_TO_VIEW = "bindToView"
    private const val G_AD_BIZ_FACADE = "com.bilibili.gripper.api.ad.biz.GAdBizKt"
    private const val G_AD_VIDEO_DETAIL = "com.bilibili.gripper.api.ad.biz.GAdVideoDetail"
    private const val I_AD_VIDEO_RELATE = "com.bilibili.gripper.api.ad.biz.videodetail.relate.IAdVideoRelate"
    private const val MAX_CANDIDATES = 32

    private val RELATE_GAME_COMPONENT_CLASSES: List<String> =
        ('a'..'z').map { letter -> "$RELATE_GAME_PACKAGE.$letter" } +
            "$RELATE_GAME_PACKAGE.RelateGameComponent"

    private val GEMINI_BINDING_COMPONENT_CLASSES: List<String> = (
        listOf("l", "m", "k", "j", "c", "d", "e") + ('a'..'z').map(Char::toString)
        ).distinct().map { letter -> "$GEMINI_UI_PACKAGE.$letter" }

    data class ResolvedPair(val gameComponent: Class<*>, val baseComponent: Class<*>)

    fun applicable(loader: ClassLoader): Boolean =
        Lookup.hasClass(loader, G_AD_BIZ_FACADE) &&
            Lookup.hasClass(loader, G_AD_VIDEO_DETAIL) &&
            Lookup.hasClass(loader, I_AD_VIDEO_RELATE) &&
            Lookup.hasClass(loader, ANDROIDX_VIEW_BINDING) &&
            Lookup.hasClass(loader, GEMINI_SIMPLE_VIEW_ENTRY)

    fun allowedComponentName(name: String): Boolean = name.length in 1..512 &&
        (name.startsWith("$RELATE_GAME_PACKAGE.") || PlayerSpeedMenuAdapter.flattenedOwner(name))

    private fun verifiedBaseComponent(candidate: Class<*>): Boolean =
        candidate.isAbstract && !candidate.isInterface &&
            Lookup.methods(candidate, includeSuperclasses = true, makeAccessible = true) { method ->
                method.name == CREATE_VIEW_ENTRY && method.parameterTypes.contentEquals(
                    arrayOf(classOf<Context>(), classOf<ViewGroup>())
                )
            }.distinctBy(Method::toGenericString).size == 1 &&
            Lookup.methods(candidate, includeSuperclasses = true, makeAccessible = true) { method ->
                method.name == BIND_TO_VIEW && method.parameterCount == 2 &&
                    method.parameterTypes[1].name == KOTLIN_CONTINUATION
            }.distinctBy(Method::toGenericString).size == 1

    private fun findBaseComponent(component: Class<*>): Class<*>? {
        var current: Class<*>? = component.superclass
        while (current != null && current != Any::class.java) {
            if (verifiedBaseComponent(current)) return current
            current = current.superclass
        }
        return null
    }

    fun verifiedComponent(
        loader: ClassLoader,
        candidate: Class<*>,
        viewBindingClass: Class<*>? = Lookup.classOrNull(loader, ANDROIDX_VIEW_BINDING)
    ): ResolvedPair? {
        val vb = viewBindingClass ?: return null
        if (candidate.isInterface || candidate.isAbstract || !allowedComponentName(candidate.name)) return null
        val base = findBaseComponent(candidate) ?: return null
        val hasCreateBinding = Lookup.declaredMethods(candidate, makeAccessible = true) { method ->
            method.parameterTypes.contentEquals(
                arrayOf(classOf<Context>(), classOf<LayoutInflater>(), classOf<ViewGroup>())
            ) && method.returnType isSubclassOf vb
        }.isNotEmpty()
        if (!hasCreateBinding) return null
        val hasBindMethod = Lookup.declaredMethods(candidate, makeAccessible = true) { method ->
            method.parameterCount == 2 &&
                method.parameterTypes[0] isSubclassOf vb &&
                method.parameterTypes[1].name == KOTLIN_CONTINUATION
        }.isNotEmpty()
        if (!hasBindMethod) return null
        return ResolvedPair(candidate, base)
    }

    fun directPair(loader: ClassLoader): ResolvedPair? {
        val viewBindingClass = Lookup.classOrNull(loader, ANDROIDX_VIEW_BINDING) ?: return null
        val baseComponent = GEMINI_BINDING_COMPONENT_CLASSES.firstNotNullOfOrNull { className ->
            Lookup.classOrNull(loader, className)?.takeIf(::verifiedBaseComponent)
        }
        val candidates = RELATE_GAME_COMPONENT_CLASSES.mapNotNull { Lookup.classOrNull(loader, it) }
        val pairs = candidates.mapNotNull { candidate ->
            val pair = verifiedComponent(loader, candidate, viewBindingClass) ?: return@mapNotNull null
            if (baseComponent != null && !(candidate isSubclassOf baseComponent)) return@mapNotNull null
            if (baseComponent != null) ResolvedPair(pair.gameComponent, baseComponent) else pair
        }.distinctBy { it.gameComponent.name }
        return pairs.singleOrNull()
    }

    fun resolvePair(loader: ClassLoader, cachedComponentName: String?): ResolvedPair? =
        resolveCachedPair(loader, cachedComponentName) ?: directPair(loader)

    private fun resolveCachedPair(loader: ClassLoader, cachedComponentName: String?): ResolvedPair? {
        val name = cachedComponentName?.takeIf(::allowedComponentName) ?: return null
        val candidate = Lookup.classOrNull(loader, name) ?: return null
        return verifiedComponent(loader, candidate)
    }

    fun direct(loader: ClassLoader): String? = directPair(loader)?.gameComponent?.name

    fun resolve(loader: ClassLoader, cached: String?): String? =
        resolveCachedPair(loader, cached)?.gameComponent?.name

    fun needsQuery(
        loader: ClassLoader,
        cached: String?,
        enabled: Boolean,
        attempted: Boolean
    ): Boolean = enabled && !attempted && applicable(loader) &&
        resolve(loader, cached) == null && direct(loader) == null

    fun locate(
        loader: ClassLoader,
        session: DexAssistSession?,
        enabled: Boolean,
        cached: String?,
        planned: Boolean
    ): String? {
        if (!enabled) return cached
        resolve(loader, cached)?.let { return it }
        direct(loader)?.let { return it }
        if (!planned) return null
        val result = session?.result(query) as? DexAssistResult.Candidates ?: return null
        if (result.classes.size > MAX_CANDIDATES || result.methods.size > MAX_CANDIDATES) return null
        val viewBindingClass = Lookup.classOrNull(loader, ANDROIDX_VIEW_BINDING) ?: return null
        val verified = (result.classes + result.methods.map { it.declaringClass })
            .distinctBy(Class<*>::getName)
            .mapNotNull { verifiedComponent(loader, it, viewBindingClass) }
            .distinctBy { it.gameComponent.name }
        val inPkg = verified.filter { it.gameComponent.name.startsWith("$RELATE_GAME_PACKAGE.") }
        val pool = inPkg.ifEmpty { verified }
        return pool.singleOrNull()?.gameComponent?.name
    }
}
