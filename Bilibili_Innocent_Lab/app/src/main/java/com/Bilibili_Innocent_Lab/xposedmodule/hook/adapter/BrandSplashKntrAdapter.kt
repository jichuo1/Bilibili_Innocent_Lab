package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import android.graphics.Bitmap
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.BrandSplashKntrAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.BrandSplashKntrRenderer
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.brandSplashDrawConstructorAbi
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.brandSplashKntrModelConstructor
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.brandSplashPageAbi
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** 定位 KMP 自选/跳过品牌开屏（draw lambda、exit 协程与 page Composable）；复用时由当前 ClassLoader 完整验真。 */
internal object BrandSplashKntrAdapter {
    const val TRACE_MARK = "kntr.srcs.app.splash.brand.startup.BrandSplashPage.<anonymous> ("
    const val PAGE_TRACE_MARK = "BrandSplashPage ("
    const val DIAGNOSTIC = "dex.assist.brand_splash_kntr_draw"
    const val DIAGNOSTIC_EXIT = "dex.assist.brand_splash_kntr_exit"
    const val DIAGNOSTIC_PAGE = "dex.assist.brand_splash_kntr_page"
    val query = DexAssistQuery.BRAND_SPLASH_KNTR_DRAW
    val queryExit = DexAssistQuery.BRAND_SPLASH_KNTR_EXIT
    val queryPage = DexAssistQuery.BRAND_SPLASH_KNTR_PAGE
    private const val STARTUP_PACKAGE = "kntr.srcs.app.splash.brand.startup."
    private const val BRAND_SOURCE_ENUM = "kntr.srcs.app.splash.brand.model.BrandSplashSource"
    private const val MAX_CANDIDATES = 32

    fun applicable(loader: ClassLoader): Boolean =
        BrandSplashKntrAccess.present(loader) ||
            Lookup.hasClass(loader, BrandSplashKntrRenderer.PAGE) ||
            Lookup.hasClass(loader, BRAND_SOURCE_ENUM)

    fun allowedOwnerName(name: String): Boolean = name.length in 1..512 &&
        (name.startsWith(STARTUP_PACKAGE) || PlayerSpeedMenuAdapter.flattenedOwner(name))

    fun verified(loader: ClassLoader, owner: Class<*>): Boolean = runCatching {
        if (!allowedOwnerName(owner.name)) return false
        val invokes = Lookup.declaredMethods(owner) {
            !Modifier.isStatic(it.modifiers) && !Modifier.isAbstract(it.modifiers) &&
                it.name == "invoke" && it.parameterCount == 3
        }
        if (invokes.size != 1) return false
        val fields = Lookup.declaredFields(owner) { !Modifier.isStatic(it.modifiers) }
        val modelField = fields.filter { brandSplashKntrModelConstructor(it.type) != null }.singleOrNull()
            ?: return false
        val imageBitmap = Lookup.classOrNull(loader, "androidx.compose.ui.graphics.AndroidImageBitmap_androidKt")
            ?.let { Lookup.methodOrNull(it, "asImageBitmap", classOf<Bitmap>()) }
            ?.returnType
        if (imageBitmap != null) {
            val bitmapFields = fields.filter { it.type == imageBitmap }
            if (bitmapFields.size > 1) return false
            if (bitmapFields.size == 1) {
                val resourceClass = Lookup.classOrNull(loader, "org.jetbrains.compose.resources.DrawableResource")
                    ?: return false
                val validCtor = Lookup.declaredConstructors(owner, true).any {
                    brandSplashDrawConstructorAbi(
                        it.parameterTypes.map(Class<*>::getName),
                        imageBitmap.name,
                        modelField.type.name,
                        resourceClass.name
                    )
                }
                if (!validCtor) return false
            }
        }
        true
    }.getOrDefault(false)

    fun direct(loader: ClassLoader): String? =
        Lookup.classOrNull(loader, BrandSplashKntrRenderer.COMPACT_DRAW)
            ?.takeIf { verified(loader, it) }
            ?.name

    fun resolve(loader: ClassLoader, cached: String?): String? {
        val name = cached?.takeIf(::allowedOwnerName) ?: return null
        return Lookup.classOrNull(loader, name)?.takeIf { verified(loader, it) }?.name
    }

    fun needsQuery(loader: ClassLoader, cached: String?, enabled: Boolean, attempted: Boolean): Boolean =
        enabled && !attempted && applicable(loader) && direct(loader) == null && resolve(loader, cached) == null

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

    fun directExit(loader: ClassLoader): String? =
        BrandSplashKntrAccess.exit(loader)?.owner?.name

    fun resolveExit(loader: ClassLoader, cached: String?): String? {
        val name = cached?.takeIf(::allowedOwnerName) ?: return null
        return BrandSplashKntrAccess.resolveVerified(loader, name)?.owner?.name
    }

    fun needsExitQuery(loader: ClassLoader, cached: String?, enabled: Boolean, attempted: Boolean): Boolean =
        enabled && !attempted && applicable(loader) && directExit(loader) == null && resolveExit(loader, cached) == null

    fun locateExit(
        loader: ClassLoader,
        session: DexAssistSession?,
        enabled: Boolean,
        cached: String?,
        planned: Boolean
    ): String? {
        if (!enabled) return cached
        directExit(loader)?.let { return cached?.takeIf { resolveExit(loader, it) != null } }
        resolveExit(loader, cached)?.let { return it }
        if (!planned) return null
        val result = session?.result(queryExit) as? DexAssistResult.Candidates ?: return null
        if (result.classes.size > MAX_CANDIDATES || result.methods.size > MAX_CANDIDATES) return null
        val owners = (result.classes + result.methods.map { it.declaringClass }).distinctBy(Class<*>::getName)
        val verified = owners.mapNotNull { owner ->
            if (!allowedOwnerName(owner.name)) null else BrandSplashKntrAccess.resolveVerified(loader, owner.name)
        }
        val currentMatches = verified.filter { it.waitsForImages }
        val chosen = if (currentMatches.isNotEmpty()) currentMatches else verified
        return chosen.map { it.owner.name }.distinct().singleOrNull()
    }

    fun verifiedPageMethod(loader: ClassLoader, method: Method): Boolean = runCatching {
        val composer = Lookup.classOrNull(loader, "androidx.compose.runtime.Composer") ?: return false
        val modifier = Lookup.classOrNull(loader, "androidx.compose.ui.Modifier") ?: return false
        val function0 = Lookup.classOrNull(loader, "kotlin.jvm.functions.Function0") ?: return false
        if (!Modifier.isStatic(method.modifiers) || Modifier.isAbstract(method.modifiers) ||
            method.isBridge || method.isSynthetic || method.returnType != Void.TYPE ||
            !allowedOwnerName(method.declaringClass.name)
        ) {
            return false
        }
        val params = method.parameterTypes
        val abi = brandSplashPageAbi(params.map(Class<*>::getName)) ?: return false
        params[1] == function0 &&
            params[2] == modifier &&
            params[abi.composerIndex] == composer &&
            brandSplashKntrModelConstructor(params[0]) != null
    }.getOrDefault(false)

    private fun pageOwnerCandidates(loader: ClassLoader): List<Class<*>> =
        (('a'..'z').map { STARTUP_PACKAGE + it } + "${STARTUP_PACKAGE}BrandSplashPageKt")
            .mapNotNull { Lookup.classOrNull(loader, it) }

    fun directPage(loader: ClassLoader): Method? =
        pageOwnerCandidates(loader)
            .flatMap(Lookup::declaredMethods)
            .singleOrNull { verifiedPageMethod(loader, it) }

    fun resolvePage(loader: ClassLoader, cached: HookPoint?): Method? {
        val point = cached ?: return null
        val paramNames = point.paramClassNames ?: return null
        if (point.viewField != null || paramNames.size !in 6..7 || !allowedOwnerName(point.className)) {
            return null
        }
        val owner = Lookup.classOrNull(loader, point.className) ?: return null
        return Lookup.declaredMethods(owner) { method ->
            method.name == point.methodName &&
                method.parameterTypes.map(Class<*>::getName) == paramNames &&
                verifiedPageMethod(loader, method)
        }.singleOrNull()
    }

    fun needsPageQuery(loader: ClassLoader, cached: HookPoint?, enabled: Boolean, attempted: Boolean): Boolean =
        enabled && !attempted && applicable(loader) && resolvePage(loader, cached) == null && directPage(loader) == null

    fun locatePage(
        loader: ClassLoader,
        session: DexAssistSession?,
        enabled: Boolean,
        cached: HookPoint?,
        planned: Boolean
    ): HookPoint? {
        if (!enabled) return cached
        directPage(loader)?.let { return cached?.takeIf { resolvePage(loader, it) != null } }
        resolvePage(loader, cached)?.let { return cached }
        if (!planned) return null
        val candidates = (session?.result(queryPage) as? DexAssistResult.Candidates)?.methods.orEmpty()
        if (candidates.size > MAX_CANDIDATES) return null
        return candidates.asSequence()
            .filter { verifiedPageMethod(loader, it) }
            .distinctBy(Method::toGenericString)
            .singleOrNull()
            ?.let { method ->
                HookPoint(
                    className = method.declaringClass.name,
                    methodName = method.name,
                    paramClassNames = method.parameterTypes.map(Class<*>::getName)
                )
            }
    }
}
