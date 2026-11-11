package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import java.lang.reflect.Field
import java.lang.reflect.Method

/** Verified brand-only roles. The older coroutine name is an image loader on 9.17. */
internal data class BrandSplashKntrExit(
    val owner: Class<*>, val resume: Method, val label: Field, val callback: Field,
    val invoke: Method, val unit: Any, val waiting: Any?, val failure: Class<*>?
) {
    val waitsForImages: Boolean get() = waiting != null
    fun exited(instance: Any?, result: Any?): Boolean {
        if (instance == null || result !== unit) return false
        val state = label.getInt(instance)
        return state == 1 || state == BrandSplashCoroutineSkip.COMPLETED_LABEL
    }
}

internal object BrandSplashKntrAccess {
    const val CURRENT_EXIT = "kntr.srcs.app.splash.brand.startup.BrandSplashPageKt\$BrandSplashPage\$5\$1"
    const val BITMAP_EXIT = "kntr.srcs.app.splash.brand.startup.BrandSplashPageKt\$BrandSplashPage\$6\$1"
    private const val FUNCTION = "kotlin.jvm.functions.Function0"
    private val readyExitNames = listOf(CURRENT_EXIT, BITMAP_EXIT)

    fun present(loader: ClassLoader): Boolean =
        Lookup.hasClass(loader, BrandSplashSkipFeatureInstaller.KNTR_EXIT) ||
            readyExitNames.any { Lookup.hasClass(loader, it) }

    fun exit(loader: ClassLoader): BrandSplashKntrExit? {
        val ready = readyExitNames.mapNotNull { resolve(loader, it, true) }
        return if (ready.isNotEmpty()) ready.singleOrNull()
            else resolve(loader, BrandSplashSkipFeatureInstaller.KNTR_EXIT, false)
    }

    private fun resolve(loader: ClassLoader, name: String, current: Boolean): BrandSplashKntrExit? = runCatching {
        val owner = Lookup.classOrNull(loader, name) ?: return null
        val resume = Lookup.methodOrNull(owner, "invokeSuspend", classOf<Any>())
            ?.takeIf { !it.isStatic && it.returnType == classOf<Any>() } ?: return null
        val label = Lookup.fieldOrNull(owner, "label")?.takeIf { it.type == classOf<Int>() } ?: return null
        val fields = Lookup.declaredFields(owner, true)
        val callback = fields.singleOrNull { it.type.name == FUNCTION } ?: return null
        val invoke = Lookup.methodOrNull(callback.type, "invoke") ?: return null
        val unit = Lookup.classOrNull(loader, "kotlin.Unit")?.let {
            Lookup.fieldOrNull(it, "INSTANCE")?.get(null)
        } ?: return null
        var waiting: Any? = null
        var failure: Class<*>? = null
        if (current) {
            val mutable = Lookup.classOrNull(loader, "androidx.compose.runtime.MutableState") ?: return null
            val state = Lookup.classOrNull(loader, "androidx.compose.runtime.State") ?: return null
            if (callback.name != "\$onExit" || listOf("\$customBitmapReady\$delegate", "\$customLogoReady\$delegate").any { key ->
                    fields.singleOrNull { it.name == key }?.type != mutable
                } || fields.singleOrNull { it.name == "\$currentOnShow" }?.type != state) return null
            // 9.18/9.19 persist the shown model only if this image state holds a bitmap.
            if (name == BITMAP_EXIT && fields.singleOrNull { it.name == "\$customBitmap\$delegate" }?.type != mutable) return null
            val intrinsics = Lookup.classOrNull(loader, "kotlin.coroutines.intrinsics.IntrinsicsKt") ?: return null
            waiting = Lookup.inheritedMethodOrNull(intrinsics, "getCOROUTINE_SUSPENDED")?.invoke(null) ?: return null
            failure = Lookup.classOrNull(loader, "kotlin.Result\$Failure") ?: return null
        }
        BrandSplashKntrExit(owner, resume, label, callback, invoke, unit, waiting, failure)
    }.getOrNull()
}

internal data class BrandSplashPageAbi(val composerIndex: Int, val flagsIndex: Int)

internal fun brandSplashPageAbi(parameters: List<String>): BrandSplashPageAbi? {
    val p = parameters
    if (p.size !in 6..7 || p[1] != "kotlin.jvm.functions.Function0" ||
        p[2] != "androidx.compose.ui.Modifier") return null
    if (p[0] == "bgk" && p.drop(3) == listOf("kotlin.jvm.functions.Function0", "androidx.compose.runtime.Composer", "int"))
        return BrandSplashPageAbi(4, 5)
    if (p[3] != "java.lang.String") return null
    return when {
        p[4] == "androidx.compose.runtime.Composer" && p.drop(5).all { it == "int" } -> BrandSplashPageAbi(4, 5)
        p.size == 7 && p[4] == "kotlin.jvm.functions.Function0" &&
            p[5] == "androidx.compose.runtime.Composer" && p[6] == "int" -> BrandSplashPageAbi(5, 6)
        else -> null
    }
}
