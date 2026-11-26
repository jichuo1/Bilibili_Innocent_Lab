package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import android.widget.ImageView
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** 旧 Story 底部开关：通过稳定访问器和唯一播放器绑定签名定位，不按混淆生命周期名猜测。 */
internal class LegacyStoryDanmakuAccess private constructor(
    val owner: Class<*>, val setter: Method, val bind: Method, private val getter: Method
) {
    fun toggle(controller: Any): Any? = getter.invoke(controller)

    companion object {
        const val OWNER = "com.bilibili.video.story.action.StoryAbsController"
        private const val REPACKAGED_PLAYER_919 = "iwr0"

        internal fun isAllowedPlayerType(type: Class<*>): Boolean {
            if (type.isPrimitive || type.isArray || type.isEnum) return false
            val name = type.name
            return name.startsWith("com.bilibili.video.story.player.") || '.' !in name
        }

        private fun isInstanceMethod(method: Method): Boolean = !Modifier.isStatic(method.modifiers) &&
            !Modifier.isAbstract(method.modifiers) && !method.isBridge

        internal fun matchesBindCandidate(method: Method, playerType: Class<*>): Boolean =
            isInstanceMethod(method) && method.returnType == Void.TYPE &&
                method.parameterTypes.contentEquals(arrayOf(playerType, Int::class.javaPrimitiveType!!))

        private fun resolveCore(owner: Class<*>, toggleType: Class<*>): Triple<Method, Method, Class<*>>? {
            val getter = Lookup.declaredMethods(owner, true) {
                isInstanceMethod(it) && it.name == "getMDanmakuToggle" &&
                    it.parameterCount == 0 && it.returnType == toggleType
            }.singleOrNull() ?: return null
            val setter = Lookup.declaredMethods(owner, true) {
                isInstanceMethod(it) && it.name == "setMDanmakuToggle" &&
                    it.returnType == Void.TYPE && it.parameterTypes.contentEquals(arrayOf(toggleType))
            }.singleOrNull() ?: return null
            val player = Lookup.declaredMethods(owner, true) {
                isInstanceMethod(it) && it.name == "getMPlayer" &&
                    it.parameterCount == 0 && isAllowedPlayerType(it.returnType)
            }.singleOrNull()?.returnType ?: return null
            return Triple(getter, setter, player)
        }

        internal fun resolvePlayerType(owner: Class<*>, toggleType: Class<*> = ImageView::class.java): Class<*>? =
            runCatching { resolveCore(owner, toggleType)?.third }.getOrNull()

        private fun resolveCachedBindMethod(
            owner: Class<*>,
            player: Class<*>,
            cachedBindMethod: VersionAdapter.HookPoint?
        ): Method? {
            val cached = cachedBindMethod ?: return null
            if (cached.className != OWNER || cached.viewField != null) return null
            if (cached.paramClassNames != null && cached.paramClassNames != listOf(player.name, "int")) {
                return null
            }
            return Lookup.declaredMethods(owner, true) {
                it.name == cached.methodName && matchesBindCandidate(it, player)
            }.singleOrNull()
        }

        /** 仅按缓存绑定入口复核；供后台适配器校验 cachedBindMethod，不回退 Tier-1。 */
        internal fun resolveWithCachedBind(
            loader: ClassLoader,
            cachedBindMethod: VersionAdapter.HookPoint,
            toggleType: Class<*> = ImageView::class.java
        ): LegacyStoryDanmakuAccess? = runCatching {
            val owner = Lookup.classOrNull(loader, OWNER) ?: return null
            val (getter, setter, player) = resolveCore(owner, toggleType) ?: return null
            val bind = resolveCachedBindMethod(owner, player, cachedBindMethod) ?: return null
            LegacyStoryDanmakuAccess(owner, setter, bind, getter)
        }.getOrNull()

        /** toggleType 仅供无 Android View 实例的 JVM ABI 夹具替换；生产固定使用 ImageView。 */
        fun resolve(
            loader: ClassLoader,
            toggleType: Class<*> = ImageView::class.java,
            cachedBindMethod: VersionAdapter.HookPoint? = null
        ): LegacyStoryDanmakuAccess? = runCatching {
            val owner = Lookup.classOrNull(loader, OWNER) ?: return null
            val (getter, setter, player) = resolveCore(owner, toggleType) ?: return null
            val directBind = Lookup.declaredMethods(owner, true) {
                matchesBindCandidate(it, player) &&
                    // 9.19 上 StoryAbsController 存在多个 (iwr0, Int) -> Unit 方法，保留精确名字破除歧义；
                    // 9.20/9.21 只有一个唯一 (player, Int) -> Unit 入口（如 S0），由 singleOrNull() 严格唯一判定。
                    (player.name != REPACKAGED_PLAYER_919 || it.name == "K0")
            }.singleOrNull()
            val bind = directBind ?: resolveCachedBindMethod(owner, player, cachedBindMethod) ?: return null
            LegacyStoryDanmakuAccess(owner, setter, bind, getter)
        }.getOrNull()
    }
}
