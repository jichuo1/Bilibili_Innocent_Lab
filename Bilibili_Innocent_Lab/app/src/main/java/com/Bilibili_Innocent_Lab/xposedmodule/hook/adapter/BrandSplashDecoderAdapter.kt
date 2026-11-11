package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** 只描述宿主资源解码 helper；不持有 Bitmap、宿主对象或 DexMeguviz 桥。 */
internal object BrandSplashDecoderAdapter {
    const val INFO = "tv.danmaku.bili.ui.splash.brand.model.BrandShowInfo"
    const val BITMAP = "android.graphics.Bitmap"
    const val MARK = "get getBitmap url"
    const val DIAGNOSTIC = "dex.assist.brand_splash_decoder"
    val query = DexAssistQuery.BRAND_SPLASH_DECODER

    // BaseBrandSplashFragment 的实际调用目标；9.19 将 owner 和方法名一并重排。
    private val decoders = listOf("Vx1.a" to "a", "Ux1.a" to "a", "cgk" to "b") + listOf(
        "Nr1.a", "Or1.a",
        "Cg1.a", "Cq1.a", "Eg1.a", "Ip1.a", "Jc1.a", "Mp1.a", "Sa1.a", "Sl1.a", "Wg1.a", "Zq1.a",
        "dc6.a", "eg1.a", "ep1.a", "fg1.a", "j46.a", "jd6.a", "ka1.a", "nb1.a", "nh6.a", "no1.a",
        "on1.a", "qc6.a", "qj1.a", "rb6.a", "rg1.a", "sb6.a", "sm1.a", "v86.a", "wq1.a", "zk1.a"
    ).map { it to "a" }

    fun verified(method: Method): Boolean = Modifier.isStatic(method.modifiers) &&
        !Modifier.isAbstract(method.modifiers) && !method.isBridge &&
        method.returnType.name == BITMAP && method.parameterTypes.map(Class<*>::getName) == listOf(INFO)

    fun direct(loader: ClassLoader): Method? = decoders.mapNotNull { (name, method) ->
        Lookup.classOrNull(loader, name)?.let { owner ->
            Lookup.declaredMethods(owner, true) { it.name == method && verified(it) }.singleOrNull()
        }
    }.distinctBy(Method::toGenericString).singleOrNull()

    fun resolve(loader: ClassLoader, point: HookPoint?): Method? {
        point ?: return null
        if (point.viewField != null || point.paramClassNames != listOf(INFO)) return null
        val owner = Lookup.classOrNull(loader, point.className) ?: return null
        return Lookup.declaredMethods(owner, true) { it.name == point.methodName && verified(it) }.singleOrNull()
    }

    fun needsQuery(loader: ClassLoader, cached: HookPoint?, enabled: Boolean, attempted: Boolean): Boolean =
        enabled && !attempted && direct(loader) == null && resolve(loader, cached) == null

    fun locate(loader: ClassLoader, session: DexAssistSession?, enabled: Boolean,
               cached: HookPoint?, planned: Boolean): HookPoint? {
        if (!enabled) return cached
        direct(loader)?.let { return point(it) }
        resolve(loader, cached)?.let { return point(it) }
        if (!planned) return null // 未登记的 result() 会补开桥，必须先挡住。
        val candidates = (session?.result(query) as? DexAssistResult.Candidates)?.methods.orEmpty()
        if (candidates.size > 32) return null
        return candidates.mapNotNull { resolve(loader, point(it)) }
            .distinctBy(Method::toGenericString).singleOrNull()?.let(::point)
    }

    fun point(method: Method) = HookPoint(method.declaringClass.name, method.name, listOf(INFO))
}
