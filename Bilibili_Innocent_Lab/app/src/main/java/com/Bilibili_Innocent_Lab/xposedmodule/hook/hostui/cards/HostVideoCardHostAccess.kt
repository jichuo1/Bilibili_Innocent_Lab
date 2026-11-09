package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards

import android.content.Context
import android.content.res.Configuration
import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/** 只缓存宿主成员，不持有 Activity；宿主主题接口不可用时回退到系统模式。 */
internal class HostVideoCardHostAccess(loader: ClassLoader) {
    private val dislikeItem = KavaMemberLookup.classOrNull(loader,
        "com.bilibili.pegasus.data.card.DislikeItemData")
    private val holderFields = HashMap<Class<*>, List<Field>>()
    private val nightTheme = KavaMemberLookup.classOrNull(loader, "com.bilibili.lib.ui.util.NightTheme")
        ?.let { KavaMemberLookup.methodOrNull(it, "isNightTheme", Context::class.java) }
    private val roundCover = KavaMemberLookup.classOrNull(loader,
        "com.bilibili.app.comm.list.common.widget.RoundCircleFrameLayout")
    private val setRadius = roundCover?.let {
        KavaMemberLookup.methodOrNull(it, "setRadius", Float::class.javaPrimitiveType!!)
    }

    fun isNight(context: Context): Boolean {
        val hostNight = runCatching { nightTheme?.invoke(null, context) as? Boolean }.getOrNull()
        return hostNight ?: (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES)
    }

    fun setCoverRadius(view: View, radius: Float) {
        if (roundCover?.isInstance(view) == true) setRadius?.invoke(view, radius)
    }

    /** 反馈占位条目没有封面/标题，按宿主数据类型识别，不依赖文案或混淆后的 holder 名称。 */
    fun isDislikeHolder(holder: Any): Boolean {
        val itemType = dislikeItem ?: return false
        val fields = holderFields.getOrPut(holder.javaClass) {
            KavaMemberLookup.fields(holder.javaClass, includeSuperclasses = true, makeAccessible = true) { field ->
                !Modifier.isStatic(field.modifiers) && field.type.isAssignableFrom(itemType)
            }
        }
        return fields.any { field -> runCatching { itemType.isInstance(field.get(holder)) }.getOrDefault(false) }
    }
}
