package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import java.lang.reflect.Field

/**
 * 宿主首页推荐“加载更多”监听器的结构识别。
 *
 * 宿主只在最后一个已挂载卡片之后剩余不到 N 行时才请求下一页（N 由服务端下发，缺省 1 行，见
 * `PegasusFragment` 创建的监听器），滑完一页基本都要停在“加载中”页脚上等网络。监听器的类名与字段名
 * 逐版漂移（8.84/8.90.2 `widget.g`、9.1/9.11/9.17.0 `widget.h`、9.16.1 `widget.i`），回调字段还会被
 * R8 合并成实现了 `kotlin.jvm.functions.Function0` 的具体类（9.11 起），所以只按结构认：
 * - 父类恰好是宿主 `RecyclerView$OnScrollListener`，同时实现 `RecyclerView$OnChildAttachStateChangeListener`；
 * - 位于 `com.bilibili.pegasus.` 包下；
 * - 实例字段恰好三个：一个可赋值给宿主 `Function0`（加载回调）、一个 `int`（预加载行数）、一个 `boolean`
 *   （首刷完成前为 false 的启用开关）。`BaseListFragment$a` 等同样实现两个接口的类字段形状不同，不会误认。
 *
 * 不读写预加载行数：9.11 起宿主把它的 setter 内联掉了，改它没有稳定入口。模块只在更早的时机调用同一个回调。
 */
internal class PegasusLoadMoreShape private constructor(val callback: Field, val enabled: Field) {
    companion object {
        const val PACKAGE_PREFIX = "com.bilibili.pegasus."

        fun of(type: Class<*>, scrollListener: Class<*>, childAttachListener: Class<*>, function0: Class<*>): PegasusLoadMoreShape? {
            if (type.superclass != scrollListener || !childAttachListener.isAssignableFrom(type)) return null
            if (!type.name.startsWith(PACKAGE_PREFIX)) return null
            val fields = KavaMemberLookup.declaredFields(type) { !it.isStatic }
            if (fields.size != 3) return null
            val callback = fields.singleOrNull { function0.isAssignableFrom(it.type) } ?: return null
            if (fields.count { it.type == Int::class.javaPrimitiveType } != 1) return null
            val enabled = fields.singleOrNull { it.type == Boolean::class.javaPrimitiveType } ?: return null
            val accessible = KavaMemberLookup.declaredFields(type, makeAccessible = true) {
                it === callback || it === enabled
            }
            if (accessible.size != 2) return null
            return PegasusLoadMoreShape(callback, enabled)
        }
    }
}

/**
 * 提前加载的触发判定；纯逻辑，可在 JVM 上完整验证。
 *
 * 宿主自己的阈值是 `预加载行数 × 2` 个 span（8.84–9.17.0 都写死乘 2），按双列手机设计：平板 3/4 列档位下
 * 同样的 span 数不到一行，几乎要等末尾露出来才请求。这里按行计：阈值 = [LEAD_ROWS] × 当前 span 数，
 * 每项占用的 span 取宿主自己的 `SpanSizeLookup`（小卡 1、其余整行），手机、平板、单列都按真实行数算。
 */
internal object HomeRecommendEarlyLoadPolicy {
    /** 约一屏：滑到离末尾四行时请求下一页，把网络往返藏在剩余内容的滑动时间里。 */
    const val LEAD_ROWS = 4

    /**
     * 最后一个已挂载项之后剩余的 span 是否已不超过 [leadRows] 行。与宿主同口径：从下一项起累加 span，
     * 一旦超过阈值立即停止，不遍历整份数据；位置或计数无效时一律不触发。
     */
    fun withinLead(
        lastAttachedPosition: Int,
        itemCount: Int,
        spanCount: Int,
        leadRows: Int = LEAD_ROWS,
        spanSize: (Int) -> Int
    ): Boolean {
        if (lastAttachedPosition < 0 || lastAttachedPosition >= itemCount || spanCount < 1 || leadRows < 1) return false
        val threshold = leadRows.toLong() * spanCount
        var spans = 0L
        for (position in lastAttachedPosition + 1 until itemCount) {
            spans += spanSize(position).coerceIn(1, spanCount)
            if (spans > threshold) return false
        }
        return true
    }
}

/**
 * 单个列表的触发闸门：每个数据长度最多请求一次。
 *
 * 请求发出后宿主会追加加载页脚、随后追加新一页，长度变化后才允许下一次；请求失败时长度不变，
 * 模块不重试，交还宿主在末尾的原生触发与失败重试。下拉刷新让长度变短时重新开放。
 * 只在主线程使用（列表回调与 View.post）。
 */
internal class EarlyLoadGate {
    private var requestedAt = NONE

    fun shouldRequest(
        enabled: Boolean,
        lastAttachedPosition: Int,
        itemCount: Int,
        spanCount: Int,
        spanSize: (Int) -> Int
    ): Boolean {
        if (itemCount < requestedAt) requestedAt = NONE
        if (!enabled || itemCount <= 0 || itemCount == requestedAt) return false
        if (!HomeRecommendEarlyLoadPolicy.withinLead(lastAttachedPosition, itemCount, spanCount, spanSize = spanSize)) {
            return false
        }
        requestedAt = itemCount
        return true
    }

    private companion object {
        const val NONE = -1
    }
}
