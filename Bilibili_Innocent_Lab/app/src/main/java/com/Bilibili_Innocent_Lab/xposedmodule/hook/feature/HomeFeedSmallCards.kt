package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.IdentityHashMap

/**
 * 首页推荐卡片“是否双列小卡”与宿主凑组大小的读取边界，供 [SmallCardGrouping] 使用。
 *
 * 宿主只在 `vm.ActionsKt#avoidEmptyWindow(PegasusResponseWrapper)` 里凑组（8.84–9.17.0 同名同签名，
 * 刷新、加载更多、预载下拉各一次/页）。组大小取自**这一页请求发出时**的窗口档位，而不是当前列数：
 * - 8.x 没有 `PegasusWindowProfile`，固定 2 张一组；
 * - 9.1 起按请求参数的 `windowSizeType` 找同 type 的档位列数（Normal 2 / Medium 3 / Large 4），
 *   找不到按 Normal，与宿主同一回退。平板由服务端设备决策 `dd.adaptive_wide_screen.pegasus_biz` 打开
 *   3/4 列；9.14 起中/大档互切默认保留已加载内容（`pegasus_keep_feeds_on_column_switch`），切换途中
 *   返回的页仍按发请求时的档位凑组，当前 span 数这时与宿主的组大小不一致。
 *
 * 请求参数类与字段名逐版混淆（9.1 `Tp0.c` … 9.17.0 `Xt0.c`），但它是 data class：`toString()` 的
 * `windowSizeType=` 标签来自源码属性名，9.1–9.17.0 全部一致，所以只从这里解析，每页一次。
 *
 * 只对宿主已开始凑组的那几份响应给出组大小（[groupSizeFor]）：先于凑组的读取不重排，凑组时与之后的
 * 读取按同一组大小重排。模块调整的永远是宿主按同一组大小凑组的页，前后读到的排列一致。
 *
 * 不进 VersionAdapter：用到的名字在全部样本中都未混淆，为一个可选的保守改进抬适配规则、让所有宿主
 * 全量重跑不值得。任一环节解析不到时 [resolve] 返回 null，推荐过滤照常，只是不重排。
 */
internal class HomeFeedSmallCards private constructor(
    private val holderStyle: Method,
    private val isSmallCard: Method,
    private val responseOf: Method,
    private val groupSizeOf: (Any) -> Int?
) {
    /** 弱引用响应：宿主丢弃旧页后不因这里滞留。 */
    private class Page(response: Any, val groupSize: Int) {
        val response = WeakReference(response)
    }

    /** 最近开始凑组的几页，按开始顺序淘汰；只由 avoidEmptyWindow 的 before 回调写入。 */
    private val pages = ArrayDeque<Page>(PAGE_CAPACITY)

    /** 宿主已开始对 [response] 凑组时返回这一页的组大小；还没轮到、早已淘汰或不可知时返回 null。 */
    fun groupSizeFor(response: Any?): Int? {
        if (response == null) return null
        synchronized(pages) {
            return pages.firstOrNull { it.response.get() === response }?.groupSize
        }
    }

    internal fun observe(wrapper: Any?) {
        if (wrapper == null) return
        val (response, size) = runCatching {
            val response = responseOf.invoke(wrapper) ?: return
            val size = groupSizeOf(wrapper)?.takeIf { it in 2..MAX_COLUMNS } ?: return
            response to size
        }.getOrNull() ?: return
        synchronized(pages) {
            pages.removeAll { page -> page.response.get().let { it == null || it === response } }
            if (pages.size >= PAGE_CAPACITY) pages.removeFirst()
            pages.addLast(Page(response, size))
        }
    }

    /** 一次判定内复用样式读取：同一张卡最多读一次，读不出的记为 null。 */
    fun classifier(): (Any?) -> Boolean? {
        val seen = IdentityHashMap<Any, Boolean?>()
        return classify@{ item ->
            if (item == null) return@classify null
            if (seen.containsKey(item)) return@classify seen[item]
            read(item).also { seen[item] = it }
        }
    }

    private fun read(item: Any): Boolean? {
        if (!holderStyle.declaringClass.isInstance(item)) return null
        val style = runCatching { holderStyle.invoke(item) }.getOrNull() ?: return null
        if (!isSmallCard.declaringClass.isInstance(style)) return null
        return runCatching { isSmallCard.invoke(style) as? Boolean }.getOrNull()
    }

    /** 窗口档位 type → 列数；首次凑组时才读取枚举常量，安装期不触发宿主类初始化。 */
    private class WindowColumns(private val profile: Class<*>, private val type: Method, private val columns: Method) {
        private val table by lazy(LazyThreadSafetyMode.PUBLICATION) {
            val byType = HashMap<Int, Int>()
            var normal: Int? = null
            profile.enumConstants?.forEach { constant ->
                val key = type.invoke(constant) as? Int ?: return@forEach
                val value = columns.invoke(constant) as? Int ?: return@forEach
                byType[key] = value
                if ((constant as? Enum<*>)?.name == NORMAL_PROFILE) normal = value
            }
            byType to normal
        }

        fun forType(windowSizeType: Int): Int? {
            val (byType, normal) = table
            return byType[windowSizeType] ?: normal
        }

        companion object {
            fun of(profile: Class<*>): WindowColumns? {
                if (!profile.isEnum) return null
                val int = Int::class.javaPrimitiveType
                val type = KavaMemberLookup.methodOrNull(profile, "getType")?.takeIf { it.returnType == int } ?: return null
                val columns = KavaMemberLookup.methodOrNull(profile, "getColumns")?.takeIf { it.returnType == int } ?: return null
                return WindowColumns(profile, type, columns)
            }
        }
    }

    /** 宿主类名；测试用同形状的替身类替换，生产始终是默认值。 */
    internal data class HostNames(
        val holderData: String = "com.bilibili.pegasus.PegasusHolderData",
        val holderStyle: String = "com.bilibili.pegasus.HolderStyle",
        val actions: String = "com.bilibili.pegasus.vm.ActionsKt",
        val responseWrapper: String = "com.bilibili.pegasus.data.request.PegasusResponseWrapper",
        val windowProfile: String = "com.bilibili.pegasus.PegasusWindowProfile"
    )

    companion object {
        /** 宿主最大档位为 4 列；再大的值不是这个规则的产物，一律不信。 */
        internal const val MAX_COLUMNS = 8
        private const val LEGACY_GROUP_SIZE = 2
        /** 与 [FilteredListMemo.CAPACITY] 同量级：同时在途的刷新、加载与预载页不会更多。 */
        internal const val PAGE_CAPACITY = 6
        private const val NORMAL_PROFILE = "Normal"
        private const val WINDOW_SIZE_TYPE_LABEL = "windowSizeType="
        internal const val GROUPING_HOOK_ID = "home.recommend.purify.grouping"

        fun resolve(environment: HookEnvironment, names: HostNames = HostNames()): HomeFeedSmallCards? {
            val loader = environment.classLoader ?: return null
            val holder = KavaMemberLookup.classOrNull(loader, names.holderData) ?: return null
            val style = KavaMemberLookup.classOrNull(loader, names.holderStyle) ?: return null
            val getter = KavaMemberLookup.inheritedMethodOrNull(holder, "getHolderStyle")
                ?.takeIf { it.parameterCount == 0 && it.returnType == style } ?: return null
            val small = KavaMemberLookup.inheritedMethodOrNull(style, "isSmallCard")
                ?.takeIf { it.parameterCount == 0 && it.returnType == Boolean::class.javaPrimitiveType } ?: return null
            val wrapper = KavaMemberLookup.classOrNull(loader, names.responseWrapper) ?: return null
            val actions = KavaMemberLookup.classOrNull(loader, names.actions) ?: return null
            KavaMemberLookup.methodOrNull(actions, "avoidEmptyWindow", wrapper)
                ?.takeIf { it.isStatic && it.returnType == wrapper } ?: return null
            val response = KavaMemberLookup.methodOrNull(wrapper, "getResponse")
                ?.takeIf { it.parameterCount == 0 && !it.returnType.isPrimitive } ?: return null
            val profile = KavaMemberLookup.classOrNull(loader, names.windowProfile)
            val groupSizeOf: (Any) -> Int? = if (profile == null) {
                { LEGACY_GROUP_SIZE }
            } else {
                val columns = WindowColumns.of(profile) ?: return null
                val request = KavaMemberLookup.methodOrNull(wrapper, "getRequestPram")
                    ?.takeIf { it.parameterCount == 0 && !it.returnType.isPrimitive } ?: return null
                { page -> windowSizeType(request.invoke(page)?.toString())?.let(columns::forType) }
            }
            val cards = HomeFeedSmallCards(getter, small, response, groupSizeOf)
            val registered = runCatching {
                environment.registrar.exact(GROUPING_HOOK_ID, actions, "avoidEmptyWindow", wrapper) {
                    before { cards.observe(argOrNull(0)) }
                }
            }.isSuccess
            return cards.takeIf { registered }
        }

        /**
         * 从请求参数的 data class 文本里取 `windowSizeType=` 后的整数；标签前必须是分隔符，
         * 不会误读 `rawWindowSizeType=`。缺标签或不是整数时返回 null。
         */
        internal fun windowSizeType(text: String?): Int? {
            if (text == null) return null
            var from = 0
            while (true) {
                val at = text.indexOf(WINDOW_SIZE_TYPE_LABEL, from)
                if (at < 0) return null
                from = at + WINDOW_SIZE_TYPE_LABEL.length
                if (at > 0 && text[at - 1].isLetterOrDigit()) continue
                var end = from
                if (end < text.length && text[end] == '-') end++
                while (end < text.length && text[end].isDigit()) end++
                return text.substring(from, end).toIntOrNull()
            }
        }
    }
}
