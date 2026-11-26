package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.content.Context
import android.content.SharedPreferences
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Both storage layouts were checked against all 31 original APKs, not selected by version code. */
internal class BrandSplashStorageAccess private constructor(
    val owner: Class<*>,
    private val receiver: Any?,
    val read: Method,
    val write: Method,
    val modeWrite: Method,
    private val preferences: Method,
    private val lastShown: Method
) {
    fun readSelected(): List<*> = read.invoke(receiver, false) as? List<*> ?: emptyList<Any>()
    fun readMode(context: Context): Boolean = prefs(context).getBoolean(CUSTOM_MODE_KEY, false)
    fun lastShown(context: Context): String = lastShown.invoke(receiver, context) as? String ?: ""
    fun rotationKey(context: Context): String = prefs(context).getString(ROTATION_KEY, "").orEmpty()
    fun rememberRotation(context: Context, key: String) { prefs(context).edit().putString(ROTATION_KEY, key).apply() }
    private fun prefs(context: Context): SharedPreferences = preferences.invoke(receiver, context) as SharedPreferences

    /** Initialize the host's own cached preferences off the startup/render thread. */
    fun prepare(context: Context) { prefs(context) }

    companion object {
        const val STORAGE = "tv.danmaku.bili.ui.splash.brand.config.BrandSplashStorage"
        const val CUSTOM_MODE_KEY = "splash.is_custom_mode"
        private const val ROTATION_KEY = "innocent_lab.brand_selection_cursor"
        fun resolve(loader: ClassLoader): BrandSplashStorageAccess? {
            val owner = KavaMemberLookup.classOrNull(loader, STORAGE) ?: return null
            val profiles = listOf(
                arrayOf("r", "u", "x", "k", "g"),
                arrayOf("j", "k", "l", "f", "d")
            )
            val candidates = profiles.mapNotNull { names ->
                val read = KavaMemberLookup.methodOrNull(owner, names[0], Boolean::class.javaPrimitiveType!!)
                    ?.takeIf { it.returnType == classOf<List<*>>() } ?: return@mapNotNull null
                val write = KavaMemberLookup.methodOrNull(owner, names[1], classOf<List<*>>())
                    ?.takeIf { it.returnType == Void.TYPE } ?: return@mapNotNull null
                val mode = KavaMemberLookup.methodOrNull(owner, names[2], Boolean::class.javaPrimitiveType!!)
                    ?.takeIf { it.returnType == Void.TYPE } ?: return@mapNotNull null
                val prefs = KavaMemberLookup.methodOrNull(owner, names[3], classOf<Context>())
                    ?.takeIf { it.returnType == classOf<SharedPreferences>() } ?: return@mapNotNull null
                val last = KavaMemberLookup.methodOrNull(owner, names[4], classOf<Context>())
                    ?.takeIf { it.returnType == classOf<String>() } ?: return@mapNotNull null
                buildAccess(owner, listOf(read, write, mode, prefs, last))
            }
            if (candidates.isNotEmpty()) return candidates.singleOrNull()
            return resolveBySignature(owner)
        }

        private fun resolveBySignature(owner: Class<*>): BrandSplashStorageAccess? {
            val declared = KavaMemberLookup.declaredMethods(owner, makeAccessible = true)
                .filter { !it.isBridge && !it.isSynthetic && it.parameterCount == 1 }
            return listOf(true, false).mapNotNull { wantStatic ->
                val pool = declared.filter { Modifier.isStatic(it.modifiers) == wantStatic }
                fun byAbi(param: Class<*>, returns: Class<*>): Method? = pool.singleOrNull {
                    it.parameterTypes[0] == param && it.returnType == returns
                }
                val read = byAbi(Boolean::class.javaPrimitiveType!!, classOf<List<*>>()) ?: return@mapNotNull null
                val write = byAbi(classOf<List<*>>(), Void.TYPE) ?: return@mapNotNull null
                val mode = byAbi(Boolean::class.javaPrimitiveType!!, Void.TYPE) ?: return@mapNotNull null
                val prefs = byAbi(classOf<Context>(), classOf<SharedPreferences>()) ?: return@mapNotNull null
                val last = byAbi(classOf<Context>(), classOf<String>()) ?: return@mapNotNull null
                buildAccess(owner, listOf(read, write, mode, prefs, last))
            }.singleOrNull()
        }

        private fun buildAccess(owner: Class<*>, members: List<Method>): BrandSplashStorageAccess? {
            val allStatic = members.all { Modifier.isStatic(it.modifiers) }
            if (!allStatic && members.any { Modifier.isStatic(it.modifiers) }) return null
            val receiver: Any? = if (allStatic) null else {
                KavaMemberLookup.declaredFields(owner, makeAccessible = true)
                    .singleOrNull { Modifier.isStatic(it.modifiers) && it.type == owner }?.get(null)
                    ?: return null
            }
            return BrandSplashStorageAccess(owner, receiver, members[0], members[1], members[2], members[3], members[4])
        }
    }
}

internal class BrandSplashModelAccess private constructor(
    val type: Class<*>,
    private val sourceGetter: Method,
    private val idGetter: Method,
    private val thumbGetter: Method,
    private val hashGetter: Method,
    private val modeGetter: Method,
    private val logoGetter: Method
) {
    data class Image(val key: String, val hash: String, val url: String?, val mode: String?, val showLogo: Boolean,
        val source: String?, val id: Long)
    fun source(value: Any): String? = sourceGetter.invoke(value) as? String
    fun key(value: Any): String = "${source(value)}#${idGetter.invoke(value)}"
    fun identity(value: Any): String = idGetter.invoke(value)?.toString() ?: "null"
    fun image(value: Any): Image? {
        val hash = hashGetter.invoke(value) as? String ?: return null
        if (hash.isBlank()) return null
        return Image(key(value), hash, thumbGetter.invoke(value) as? String,
            modeGetter.invoke(value) as? String, logoGetter.invoke(value) as? Boolean ?: true,
            source(value), (idGetter.invoke(value) as? Number)?.toLong() ?: 0L)
    }
    companion object {
        const val MODEL = "tv.danmaku.bili.ui.splash.brand.model.BrandSplash"
        fun resolve(loader: ClassLoader): BrandSplashModelAccess? {
            val type = KavaMemberLookup.classOrNull(loader, MODEL) ?: return null
            fun method(name: String, result: Class<*>): Method? = KavaMemberLookup.methodOrNull(type, name)
                ?.takeIf { it.returnType == result }
            return BrandSplashModelAccess(type,
                method("getSource", classOf<String>()) ?: return null,
                method("getId", Long::class.javaPrimitiveType!!) ?: return null,
                method("getThumb", classOf<String>()) ?: return null,
                method("getThumbHash", classOf<String>()) ?: return null,
                method("getMode", classOf<String>()) ?: return null,
                method("getShowLogo", Boolean::class.javaPrimitiveType!!) ?: return null)
        }
    }
}
