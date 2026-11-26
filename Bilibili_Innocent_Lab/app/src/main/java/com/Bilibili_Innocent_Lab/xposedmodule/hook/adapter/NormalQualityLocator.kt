package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** 8.90.2 及 9.15+ 默认画质自动切换偏好边界；不接入手动画质切换或资源响应。 */
internal object NormalQualityLocator {
    const val AUTO_KEY = "pref_player_mediaSource_quality_auto_switch"
    const val DIAGNOSTIC = "dex.assist.normal_quality_auto_switch"
    val query = DexAssistQuery.NORMAL_QUALITY_AUTO_SWITCH
    private const val PREFERENCE = "com.bilibili.player.tangram.basic.d"
    private const val BUILDER = "com.bapis.bilibili.playershared.VideoVod\$b"
    private const val THESEUS_QUALITY_SERVICE =
        "com.bilibili.ship.theseus.united.page.videoquality.TheseusPlayerQualityService"
    private val SYSTEM_PREFIXES = listOf("android.", "androidx.", "java.", "javax.", "kotlin.", "kotlinx.", "com.bapis.")
    data class Roles(val preferenceReader: Method, val scopes: List<Method>)

    fun applicable(default: HookPoint?): Boolean = default?.let {
        it.className == "gh6.h" && it.methodName == "c" && it.paramClassNames == emptyList<String>()
    } == true

    fun applicableModern(
        loader: ClassLoader?,
        default: HookPoint?,
        cachedAutoSwitch: HookPoint? = null
    ): Boolean = !applicable(default) &&
        (cachedAutoSwitch != null || (loader != null && Lookup.hasClass(loader, THESEUS_QUALITY_SERVICE)))

    fun allowedAutoSwitchOwner(name: String): Boolean = name.length in 1..512 &&
        SYSTEM_PREFIXES.none { name.startsWith(it) } &&
        name.split('.').all {
            it.isNotEmpty() && Character.isJavaIdentifierStart(it[0]) && it.all(Character::isJavaIdentifierPart)
        }

    fun autoSwitchAbi(method: Method): Boolean = Modifier.isStatic(method.modifiers) &&
        !method.isSynthetic &&
        !method.isBridge &&
        method.parameterCount == 0 &&
        method.returnType == Boolean::class.javaPrimitiveType

    fun selectAutoSwitch(methods: Collection<Method>): Method? = methods.filter {
        autoSwitchAbi(it) && allowedAutoSwitchOwner(it.declaringClass.name)
    }.distinctBy(Method::toGenericString).singleOrNull()

    fun resolveAutoSwitch(loader: ClassLoader, point: HookPoint?): Method? = runCatching {
        point ?: return null
        if (point.viewField != null || point.paramClassNames != emptyList<String>() ||
            !allowedAutoSwitchOwner(point.className)
        ) {
            return null
        }
        val owner = Lookup.classOrNull(loader, point.className) ?: return null
        Lookup.methodOrNull(owner, point.methodName)?.takeIf(::autoSwitchAbi)
    }.getOrNull()

    fun needsQuery(
        loader: ClassLoader,
        defaultPoint: HookPoint?,
        cachedAutoSwitch: HookPoint?,
        enabled: Boolean,
        attempted: Boolean
    ): Boolean = enabled && !attempted && applicableModern(loader, defaultPoint, cachedAutoSwitch) &&
        resolveAutoSwitch(loader, cachedAutoSwitch) == null

    fun locateAutoSwitch(
        loader: ClassLoader,
        session: DexAssistSession?,
        defaultPoint: HookPoint?,
        enabled: Boolean,
        cachedAutoSwitch: HookPoint?,
        planned: Boolean
    ): HookPoint? {
        if (!enabled) return cachedAutoSwitch
        resolveAutoSwitch(loader, cachedAutoSwitch)?.let { return cachedAutoSwitch }
        if (!planned || !applicableModern(loader, defaultPoint, cachedAutoSwitch)) return null
        val candidates = (session?.result(query) as? DexAssistResult.Candidates)?.methods.orEmpty()
        val selected = selectAutoSwitch(candidates) ?: return null
        return HookPoint(selected.declaringClass.name, selected.name, emptyList())
    }

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
