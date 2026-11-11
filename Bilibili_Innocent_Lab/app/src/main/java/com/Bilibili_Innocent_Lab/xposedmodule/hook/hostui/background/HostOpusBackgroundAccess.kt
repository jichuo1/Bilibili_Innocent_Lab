package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background

import android.content.res.ColorStateList
import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.FollowFeedAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.follow.FollowFeedHostAccess
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** 只复用已验证的动态模型；9.16 颜色入口已变成 static，页面边界仍由真实 View 判断。 */
internal class HostOpusBackgroundAccess private constructor(
    val color: Method,
    private val opus: Class<*>,
    private val host: FollowFeedHostAccess
) {
    fun accepts(receiver: Any?, module: Any?): Boolean = module != null &&
        (Modifier.isStatic(color.modifiers) || opus.isInstance(receiver)) &&
        host.isQuotedModule(module) == false

    companion object {
        fun resolve(loader: ClassLoader): HostOpusBackgroundAccess? {
            val points = FollowFeedAdapter.direct(loader) ?: return null
            val follow = FollowFeedAdapter.resolve(loader, points) ?: return null
            val painter = follow.paint.declaringClass
            val opus = FollowFeedAdapter.opusDecorationCandidates(points)
                .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
                .filter { painter.isAssignableFrom(it) }.singleOrNull() ?: return null
            val color = KavaMemberLookup.declaredMethods(painter, true) {
                !Modifier.isAbstract(it.modifiers) && it.returnType == Int::class.javaPrimitiveType &&
                    it.parameterTypes.contentEquals(arrayOf(ColorStateList::class.java,
                        follow.host.moduleClass, View::class.java))
            }.singleOrNull() ?: return null
            return HostOpusBackgroundAccess(color, opus, follow.host)
        }
    }
}
