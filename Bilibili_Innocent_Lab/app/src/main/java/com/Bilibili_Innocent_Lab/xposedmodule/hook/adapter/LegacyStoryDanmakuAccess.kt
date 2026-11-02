package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import android.widget.ImageView
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

        /** toggleType 仅供无 Android View 实例的 JVM ABI 夹具替换；生产固定使用 ImageView。 */
        fun resolve(loader: ClassLoader, toggleType: Class<*> = ImageView::class.java): LegacyStoryDanmakuAccess? = runCatching {
            val owner = Lookup.classOrNull(loader, OWNER) ?: return null
            fun instance(method: Method) = !Modifier.isStatic(method.modifiers) &&
                !Modifier.isAbstract(method.modifiers) && !method.isBridge
            val getter = Lookup.declaredMethods(owner, true) {
                instance(it) && it.name == "getMDanmakuToggle" && it.parameterCount == 0 && it.returnType == toggleType
            }.singleOrNull() ?: return null
            val setter = Lookup.declaredMethods(owner, true) {
                instance(it) && it.name == "setMDanmakuToggle" && it.returnType == Void.TYPE &&
                    it.parameterTypes.contentEquals(arrayOf(toggleType))
            }.singleOrNull() ?: return null
            val player = Lookup.declaredMethods(owner, true) {
                instance(it) && it.name == "getMPlayer" && it.parameterCount == 0 &&
                    it.returnType.name.startsWith("com.bilibili.video.story.player.")
            }.singleOrNull()?.returnType ?: return null
            val bind = Lookup.declaredMethods(owner, true) {
                instance(it) && it.returnType == Void.TYPE &&
                    it.parameterTypes.contentEquals(arrayOf(player, Int::class.javaPrimitiveType!!))
            }.singleOrNull() ?: return null
            LegacyStoryDanmakuAccess(owner, setter, bind, getter)
        }.getOrNull()
    }
}
