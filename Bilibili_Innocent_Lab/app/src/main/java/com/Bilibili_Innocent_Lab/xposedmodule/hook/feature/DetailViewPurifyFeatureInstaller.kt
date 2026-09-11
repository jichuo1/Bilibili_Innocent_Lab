package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.View
import android.view.ViewGroup
import androidx.core.view.isGone
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap

/**
 * 详细页 View 层净化：按结构指纹隐藏指定组件。
 *
 * 规则表与"为什么必须走 View 层"的判据见 [DetailViewPurifyPolicy]。
 *
 * ### Hook 选点与开销
 *
 * 挂 `RecyclerView#setAdapter`——**每个列表配置一次**，不是每帧也不是每次触摸。
 * 在它的 after 里给该 RecyclerView 装**一次**子项挂载监听（按实例弱引用去重），
 * **两条规则共用同一个监听**，不会因为多一个功能就多一层回调。
 *
 * 每次子项挂载的代价，按规则形态分两种：
 * - 有 `itemIdName` 的（关注按钮）：**一次 int 比较**就结束，绝大多数列表在这里被挡掉；
 * - 没有 `itemIdName` 的（热搜横条，item 根无资源名）：先用 `id == NO_ID` 免费挡掉
 *   带 id 的项，再对无 id 项做一次小范围 `findViewById` 找主判据。
 *   量级与 `onBindViewHolder` 相同，项目已有功能就挂在这个量级上。
 *
 * 没有按 Activity 过滤：那要依赖宿主 Activity 类名，多一条失效通道。
 *
 * ### 边界纪律
 *
 * - **不写死宿主类名**：只用 androidx 的类 + 资源名；
 * - 规则要用到的资源名缺任意一个，**该条规则**就不启用（不留白跑查找的路径）；
 * - 监听器不持有 Activity/View 强引用；
 * - 回调里任何异常都吞掉并记诊断，绝不让宿主布局流程崩；
 * - APPLIED 只在**这次真的藏掉了一项**时记：回收复用的视图本来就是折叠状态，不重复记，
 *   所以"报了 APPLIED"才等于"真的藏掉了一个东西"；
 * - 隐藏 **item 根**要连尺寸一起归零，并且必须能在回收复用时**还原**
 *   （见 [ItemCollapseBook]）——只 `GONE` 会留下一格空白，
 *   只归零不还原会把别的内容压成永久空白项。
 */
internal class DetailViewPurifyFeatureInstaller(
    private val enabledKeys: Set<String>
) : FeatureInstaller {

    override val id: String = ID

    override val capabilityIds: List<String>
        get() = DetailViewPurifyPolicy.rulesFor(enabledKeys).map { it.capabilityId }

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        val requested = DetailViewPurifyPolicy.rulesFor(enabledKeys)
        if (requested.isEmpty()) {
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val loader = environment.classLoader ?: return missing(environment, "missing-class-loader")
        val recyclerView = KavaMemberLookup.classOrNull(
            loader, DetailViewPurifyPolicy.RECYCLER_VIEW_CLASS
        ) ?: return missing(environment, "not-applicable-host")
        val listenerClass = KavaMemberLookup.classOrNull(
            loader, DetailViewPurifyPolicy.CHILD_ATTACH_LISTENER_CLASS
        )?.takeIf { it.isInterface } ?: return missing(environment, "not-applicable-host")
        val addListener = KavaMemberLookup.methodOrNull(
            recyclerView, DetailViewPurifyPolicy.ADD_LISTENER_METHOD, listenerClass
        ) ?: return missing(environment, "not-applicable-host")

        val hider = DetailViewRuleHider(environment, requested, listenerClass, addListener)
        val installed = runCatching {
            environment.registrar.first(
                "detail_view_purify.${DetailViewPurifyPolicy.SET_ADAPTER_METHOD}",
                DetailViewPurifyPolicy.RECYCLER_VIEW_CLASS,
                DetailViewPurifyPolicy.SET_ADAPTER_METHOD
            ) {
                after {
                    val list = instance as? View ?: return@after
                    hider.observe(list)
                }
            }
            true
        }.getOrElse {
            environment.logError(
                "detail_view_purify_register",
                "[BIL] 详细页 View 层净化 Hook 注册失败: $it"
            )
            false
        }
        if (!installed) return missing(environment, "no-safe-detail-view-path")
        requested.forEach {
            environment.reportCapabilityCoverage(
                it.capabilityId, ready = true, installedPaths = 1, expectedPaths = 1
            )
        }
        environment.reportStatus(CHANNEL_STATUS, "success")
        return FeatureInstallResult.Installed(1)
    }

    private fun missing(
        environment: HookEnvironment,
        reason: String
    ): FeatureInstallResult.Skipped {
        environment.reportStatus(CHANNEL_STATUS, reason)
        environment.logError(
            "detail_view_purify_missing",
            "[BIL] 详细页 View 层净化适配不完整: $reason"
        )
        return FeatureInstallResult.Skipped(reason)
    }

    companion object {
        const val ID = "detail_view_purify"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "detail_view_purify_status"
    }
}

/** 给 RecyclerView 装一次子项挂载监听，命中规则就隐藏目标。资源 id 首次拿到 View 时解析。 */
internal class DetailViewRuleHider(
    private val environment: HookEnvironment,
    private val rules: List<DetailViewPurifyPolicy.Rule>,
    private val listenerClass: Class<*>,
    private val addListener: Method
) {

    private val observed: MutableSet<Any> =
        Collections.newSetFromMap(WeakHashMap<Any, Boolean>())

    private val collapsed = ItemCollapseBook()

    @Volatile private var ids: Map<String, Int>? = null
    @Volatile private var active: List<DetailViewPurifyPolicy.Rule> = emptyList()

    fun observe(list: View) {
        if (!resolve(list)) return
        synchronized(observed) {
            if (!observed.add(list)) return
        }
        runCatching { addListener.invoke(list, newListener()) }.onFailure {
            synchronized(observed) { observed.remove(list) }
            environment.logError(
                "detail_view_purify_listen",
                "[BIL] 详细页 View 层净化监听安装失败: $it"
            )
        }
    }

    /** @return 是否还有可用规则。资源名解析只做一次，之后不再查 identifier。 */
    @Suppress("DiscouragedApi")
    private fun resolve(view: View): Boolean {
        if (ids == null) {
            val resources = view.resources ?: return false
            val table = rules.flatMap { it.idNames }.distinct().associateWith { name ->
                // DiscouragedApi 的建议（改用 R.foo.bar）不成立：这是**宿主**的资源，
                // 模块自己的 R 类里没有它，按名解析是唯一办法；结果已缓存。
                runCatching { resources.getIdentifier(name, "id", TARGET_PACKAGE) }
                    .getOrDefault(0)
            }
            ids = table
            active = rules.filter { DetailViewPurifyPolicy.usable(it, table) }
            rules.filterNot { it in active }.forEach {
                environment.reportCapability(
                    it.capabilityId, FeatureInstallResult.Skipped("not-applicable-host")
                )
                environment.logError(
                    "detail_view_purify_ids",
                    "[BIL] 详细页组件资源 id 未解析，已跳过该项: ${it.capabilityId} " +
                        "(${it.idNames.joinToString { name -> "$name=${table[name]}" }})"
                )
            }
        }
        return active.isNotEmpty()
    }

    private fun newListener(): Any = Proxy.newProxyInstance(
        listenerClass.classLoader, arrayOf(listenerClass), Handler(this)
    )

    /**
     * 子项挂载回调（**只在主线程**：由 RecyclerView 的布局流程调用），
     * 所以 [collapsed] 不加锁。
     *
     * 折叠过 item 根之后必须管**还原**：RecyclerView 会回收复用视图，
     * 同一个 View 对象可能被拿去承载别的内容。若那次不命中而尺寸还留在 0，
     * 用户看到的就是一条永远空白的项——比原来的问题更糟。
     */
    fun onChildAttached(child: Any?) {
        val view = child as? View ?: return
        val table = ids ?: return
        var keepCollapsed = false
        active.forEach { rule ->
            if (applyRule(rule, view, table)) keepCollapsed = true
        }
        if (!keepCollapsed) restore(view)
    }

    /** @return 这一项的 item 根是否应保持折叠（决定回收复用时要不要还原）。 */
    private fun applyRule(
        rule: DetailViewPurifyPolicy.Rule,
        child: View,
        table: Map<String, Int>
    ): Boolean {
        // 最便宜的前置判据先走：有 itemIdName 的一次 int 比较；没有的先用 NO_ID 挡。
        val itemId = rule.itemIdName?.let { table[it] }
        if (itemId != null) {
            if (child.id != itemId) return false
        } else if (child.id != View.NO_ID) {
            return false
        }
        return runCatching {
            // 结构指纹必须**全部**命中才动手；任一缺失就原样放行。
            rule.requiredIdNames.forEach { name ->
                child.findViewById<View>(table.getValue(name)) ?: return false
            }
            val target = rule.hideIdName?.let { name ->
                child.findViewById<View>(table.getValue(name)) ?: return false
            } ?: child
            environment.reportRuntimeEvidence(rule.capabilityId, FeatureRuntimeStage.OBSERVED)
            val hidden = if (rule.hidesItemRoot) {
                // 光 GONE 不收缩那一格，要连尺寸一起归零；见 Rule.hidesItemRoot。
                collapsed.collapse(ViewItemBox(target))
            } else if (target.visibility != View.GONE) {
                target.visibility = View.GONE
                true
            } else {
                false
            }
            if (hidden) {
                environment.reportRuntimeEvidence(rule.capabilityId, FeatureRuntimeStage.APPLIED)
                environment.reportRuntimeEvidence(
                    DetailViewPurifyFeatureInstaller.ID, FeatureRuntimeStage.APPLIED
                )
            }
            rule.hidesItemRoot
        }.getOrElse {
            environment.reportRuntimeEvidence(
                DetailViewPurifyFeatureInstaller.ID, FeatureRuntimeStage.ERROR
            )
            environment.logError(
                "detail_view_purify_hide",
                "[BIL] 详细页组件隐藏失败(${rule.capabilityId}): $it"
            )
            false
        }
    }

    /** 没有记录就什么都不做——绝大多数子项走的是这条零成本路径。 */
    private fun restore(child: View) {
        if (!collapsed.tracks(child)) return
        runCatching { collapsed.restore(ViewItemBox(child)) }.onFailure {
            environment.reportRuntimeEvidence(
                DetailViewPurifyFeatureInstaller.ID, FeatureRuntimeStage.ERROR
            )
            environment.logError(
                "detail_view_purify_restore",
                "[BIL] 详细页组件尺寸还原失败: $it"
            )
        }
    }

    /**
     * 代理只处理挂载回调。
     *
     * `equals`/`hashCode`/`toString` 必须自己答——它们也会走到代理上，
     * 交给宿主接口会抛（接口没有这些方法的实现）。
     */
    private class Handler(private val owner: DetailViewRuleHider) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? =
            when (method.name) {
                DetailViewPurifyPolicy.ATTACHED_CALLBACK -> {
                    owner.onChildAttached(args?.firstOrNull())
                    null
                }
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "DetailViewRuleHider"
                else -> null
            }
    }

    private companion object {
        const val TARGET_PACKAGE = "tv.danmaku.bili"
    }
}

/**
 * 一个 item 根在"占位"这件事上的全部可变状态。
 *
 * 抽成接口只为一件事：把**折叠/还原的簿记**（真正会出 bug 的部分）
 * 从 Android 框架里摘出来，可以直接单测。工程没有 Robolectric，
 * 真 `View` 在单测里是会抛的桩，测不了。
 */
internal interface ItemBox {
    /** 弱引用记录用的身份键。对真 View 就是它自己。 */
    val key: Any
    var height: Int
    var topMargin: Int
    var bottomMargin: Int
    var gone: Boolean
}

/** [ItemBox] 到真 `View` 的适配；只有属性转发，没有判断。 */
private class ViewItemBox(private val view: View) : ItemBox {

    private val params: ViewGroup.LayoutParams? = view.layoutParams
    private val margins: ViewGroup.MarginLayoutParams? =
        params as? ViewGroup.MarginLayoutParams

    override val key: Any get() = view

    override var height: Int
        get() = params?.height ?: 0
        set(value) {
            params?.let {
                it.height = value
                view.layoutParams = it
            }
        }

    override var topMargin: Int
        get() = margins?.topMargin ?: 0
        set(value) {
            margins?.let {
                it.topMargin = value
                view.layoutParams = it
            }
        }

    override var bottomMargin: Int
        get() = margins?.bottomMargin ?: 0
        set(value) {
            margins?.let {
                it.bottomMargin = value
                view.layoutParams = it
            }
        }

    override var gone: Boolean
        get() = view.isGone
        set(value) {
            view.isGone = value
        }
}

/**
 * 折叠过的 item 根的原始尺寸账本。
 *
 * 为什么需要它：`LinearLayoutManager` 自己测量子项，**不跳过 GONE 的孩子**，
 * 所以隐藏 item 根必须把高度和上下 margin 一起归零；
 * 而 RecyclerView 会**回收复用**视图，归零后必须能还原，
 * 否则同一个 View 对象被拿去承载别的内容时会变成一条永久空白项。
 *
 * 用 `WeakHashMap` 持键：账本不该让任何 item 视图活过它的 RecyclerView。
 */
internal class ItemCollapseBook {

    private class Saved(
        val height: Int,
        val topMargin: Int,
        val bottomMargin: Int,
        val gone: Boolean
    )

    private val saved = WeakHashMap<Any, Saved>()

    fun tracks(key: Any): Boolean = saved.containsKey(key)

    /**
     * @return 这次是否**真的**新折叠了一项。已折叠的返回 false，
     *   调用方据此避免把回收复用的视图重复记成 APPLIED——
     *   "报了 APPLIED"必须等于"真的藏掉了一个东西"。
     */
    fun collapse(box: ItemBox): Boolean {
        if (saved.containsKey(box.key)) {
            // 关键：**不能**重新记账，否则原始尺寸会被已经归零的值覆盖，还原就永远回不去。
            // 但 RecyclerView 的绑定过程可能重新写回 LayoutParams；重复挂载时必须重新压平
            // 当前几何，否则只设 GONE 仍会留下旧高度的空位。
            applyCollapsedGeometry(box)
            return false
        }
        saved[box.key] = Saved(box.height, box.topMargin, box.bottomMargin, box.gone)
        applyCollapsedGeometry(box)
        return true
    }

    private fun applyCollapsedGeometry(box: ItemBox) {
        box.height = 0
        box.topMargin = 0
        box.bottomMargin = 0
        box.gone = true
    }

    /**
     * 还原到折叠前的样子，**包括可见性**——宿主自己就把它设成 GONE 的情况下，
     * 不能"顺手"帮它变可见。
     *
     * @return 是否有记录可还原。
     */
    fun restore(box: ItemBox): Boolean {
        val original = saved.remove(box.key) ?: return false
        box.height = original.height
        box.topMargin = original.topMargin
        box.bottomMargin = original.bottomMargin
        box.gone = original.gone
        return true
    }
}
