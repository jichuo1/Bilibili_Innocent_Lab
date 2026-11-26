package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common

/** 缓存类层级判定（支持宿主与模块隔离 ClassLoader），避免热路径反复分配继承链序列。 */
internal class HostRecyclerTypes(
    private val excludeViewPager2: Boolean = false,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxDepth: Int = DEFAULT_MAX_DEPTH,
    private val clearOnOverflow: Boolean = false
) {
    private val types = HashMap<Class<*>, Boolean>()

    fun matches(type: Class<*>): Boolean {
        types[type]?.let { return it }
        val resolved = inherits(type, RECYCLER_VIEW, if (excludeViewPager2) VIEWPAGER2_PREFIX else null, maxDepth)
        if (clearOnOverflow && types.size >= maxEntries) types.clear()
        if (types.size < maxEntries) types[type] = resolved
        return resolved
    }

    fun clear() {
        types.clear()
    }

    companion object {
        internal const val RECYCLER_VIEW = "androidx.recyclerview.widget.RecyclerView"
        private const val VIEWPAGER2_PREFIX = "androidx.viewpager2."
        private const val DEFAULT_MAX_ENTRIES = 128
        private const val DEFAULT_MAX_DEPTH = 12

        /** 零分配遍历类继承链（上限 [maxDepth] 层），支持跨宿主/插件 ClassLoader 的类型识别。 */
        fun inherits(
            type: Class<*>,
            targetName: String,
            excludePrefix: String? = null,
            maxDepth: Int = DEFAULT_MAX_DEPTH
        ): Boolean {
            var current: Class<*>? = type
            var remaining = maxDepth
            while (current != null && remaining-- > 0) {
                val name = current.name
                if (excludePrefix != null && name.startsWith(excludePrefix)) return false
                if (name == targetName) return true
                current = current.superclass
            }
            return false
        }
    }
}
