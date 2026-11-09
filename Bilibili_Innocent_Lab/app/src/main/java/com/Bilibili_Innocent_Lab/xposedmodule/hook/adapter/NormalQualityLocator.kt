package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** 8.90.2 实际 APK 核验的同步默认画质边界；不接入手动画质切换或资源响应。 */
internal object NormalQualityLocator {
    const val AUTO_KEY = "pref_player_mediaSource_quality_auto_switch"
    private const val PREFERENCE = "com.bilibili.player.tangram.basic.d"
    private const val BUILDER = "com.bapis.bilibili.playershared.VideoVod\$b"
    data class Roles(val preferenceReader: Method, val scopes: List<Method>)

    fun applicable(default: HookPoint?): Boolean = default?.let {
        it.className == "gh6.h" && it.methodName == "c" && it.paramClassNames == emptyList<String>()
    } == true

    fun resolve(loader: ClassLoader): Roles? = runCatching {
        fun unique(owner: String, name: String, parameters: List<String>, result: String,
            static: Boolean, public: Boolean): Method? {
            val type = Lookup.classOrNull(loader, owner) ?: return null
            return Lookup.declaredMethods(type, makeAccessible = true) {
                it.name == name && it.parameterTypes.map { parameter -> parameter.name } == parameters &&
                    it.returnType.name == result && Modifier.isStatic(it.modifiers) == static &&
                    Modifier.isPublic(it.modifiers) == public && !it.isSynthetic && !it.isBridge
            }.singleOrNull()
        }
        val reader = unique(StoryQualityLocator.SETTING_COMPANION, "getBoolean",
            listOf("java.lang.String", "boolean"), "boolean", static = false, public = true) ?: return null
        val request = unique("com.bilibili.ship.theseus.united.page.playview.PlayViewRepository",
            "b", listOf(BUILDER), BUILDER, static = false, public = false) ?: return null
        val preference = unique("com.bilibili.ship.theseus.united.player.mediaplay.n",
            "a", emptyList(), PREFERENCE, static = true, public = true) ?: return null
        val keel = unique("com.bilibili.ship.theseus.keel.player.h",
            "c", listOf("boolean"), PREFERENCE, static = true, public = false) ?: return null
        Roles(reader, listOf(request, preference, keel))
    }.getOrNull()
}
