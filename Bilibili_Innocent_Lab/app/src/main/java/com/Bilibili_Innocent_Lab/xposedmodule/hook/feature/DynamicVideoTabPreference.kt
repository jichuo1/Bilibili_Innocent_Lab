package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.util.Collections
import java.util.WeakHashMap

/** 等宿主初始 Fragment 事务落地后再切页，避免两个尚不可见的页面同时被 add。 */
internal class DynamicVideoTabPreference {
    private val scheduled = Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    fun schedule(layout: View, tab: Any, onSelected: () -> Unit, onError: (Throwable) -> Unit) {
        if (!scheduled.add(layout)) return
        layout.postOnAnimation(HostThreadGuard.runnable("dynamic_tab.select.anim") {
            // 帧回调可能先于已排队的 commit；再排回主线程，等事务及本帧布局完成。
            layout.post(HostThreadGuard.runnable("dynamic_tab.select.post") {
                selectVideo(layout, tab, onSelected, onError)
            })
        })
    }

    private fun selectVideo(layout: View, tab: Any, onSelected: () -> Unit, onError: (Throwable) -> Unit) {
        if (!layout.isAttachedToWindow) {
            scheduled.remove(layout)
            return
        }
        runCatching {
            val position = KavaMemberLookup.inheritedMethodOrNull(tab.javaClass, "getPosition")
                ?.invoke(tab) as? Int ?: return@runCatching
            if (position < 0) return@runCatching
            val current = KavaMemberLookup.inheritedMethodOrNull(layout.javaClass, "getTabAt",
                Int::class.javaPrimitiveType!!)?.invoke(layout, position)
            // 宿主可能在回调执行前重建标签；旧 Tab 不能再驱动新页面。
            if (current !== tab) {
                scheduled.remove(layout)
                return@runCatching
            }
            val selected = KavaMemberLookup.inheritedMethodOrNull(tab.javaClass, "isSelected")
                ?.invoke(tab) as? Boolean ?: return@runCatching
            if (!selected) {
                val select = KavaMemberLookup.inheritedMethodOrNull(tab.javaClass, "select")
                    ?: return@runCatching
                select.invoke(tab)
            }
            onSelected()
        }.onFailure(onError)
    }
}
