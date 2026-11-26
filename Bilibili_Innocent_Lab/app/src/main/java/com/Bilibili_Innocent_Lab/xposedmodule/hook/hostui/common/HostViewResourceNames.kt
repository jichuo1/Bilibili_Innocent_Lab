package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common

import android.content.res.Resources
import android.util.SparseArray
import android.view.View
import java.util.WeakHashMap

/** UI 线程使用。资源名不随滚动或换肤变化；不同宿主/插件的资源表不能混用同一 id。 */
internal object HostViewResourceNames {
    private val tables = WeakHashMap<Resources, SparseArray<String>>()
    private val missing = HashMap<Resources, MutableSet<Int>>()
    private var passDepth = 0

    /** 失败仅在同一轮同步遍历中复用，下一帧仍可识别新加载的资源。 */
    fun <T> inPass(block: () -> T): T {
        passDepth++
        try { return block() } finally {
            if (--passDepth == 0) missing.clear()
        }
    }

    fun name(view: View, id: Int = try { view.id } catch (_: Throwable) { View.NO_ID }): String {
        if (id == View.NO_ID || id ushr 24 == 0) return ""
        val resources = try { view.resources } catch (_: Throwable) { null } ?: return ""
        return name(resources, id)
    }

    internal fun name(resources: Resources, id: Int): String {
        // generateViewId 的无资源 id 没有包号，不向 AssetManager 查询，也不积累缓存。
        if (id == View.NO_ID || id ushr 24 == 0) return ""
        val names = tables.getOrPut(resources) { SparseArray() }
        names[id]?.let { return it }
        if (missing[resources]?.contains(id) == true) return ""
        return try {
            resources.getResourceEntryName(id).orEmpty().also { names.put(id, it) }
        } catch (_: Resources.NotFoundException) {
            if (passDepth > 0) missing.getOrPut(resources) { HashSet() }.add(id)
            ""
        } catch (_: Throwable) {
            ""
        }
    }
}
