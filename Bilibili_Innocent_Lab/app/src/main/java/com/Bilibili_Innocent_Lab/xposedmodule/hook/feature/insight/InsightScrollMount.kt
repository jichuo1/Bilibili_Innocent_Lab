package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import io.github.jichuo1.meguviz.android.extensions.toPx
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.WeakHashMap

/**
 * 宿主 UIComponent 协议的已验证形状。8.89.0 起类名为 `UIComponent` / `UIComponent$ViewEntry`；8.84.0–8.88.0
 * 两者被混淆为 `g` / `g$b`，绑定方法 `bindToView` 被混淆为 `a`，其余成员名保留（Hostlab 38 版矩阵）。
 * 混淆名不跨版本稳定，因此从未混淆的组件基类 [ANCHORS] 的接口按结构反查，不硬编码 `g`。
 */
internal class InsightComponentContract private constructor(val component: Class<*>, val entry: Class<*>, val bind: Method) {
    companion object {
        const val NAMED = "com.bilibili.app.gemini.ui.UIComponent"
        val ANCHORS = listOf("com.bilibili.app.gemini.ui.DataBindingComponent", "com.bilibili.app.gemini.ui.EmptyViewComponent")
        val KEYS = setOf("viewReusingKey", "identityEqualityKey", "contentEqualityKey")
        private const val CREATE = "createViewEntry"
        private const val ROOT = "getRoot"

        fun resolve(loader: ClassLoader, continuation: Class<*>, named: String = NAMED,
            anchors: List<String> = ANCHORS): InsightComponentContract? {
            Lookup.classOrNull(loader, named)?.let { type -> return verify(type, continuation) }
            return anchors.mapNotNull { Lookup.classOrNull(loader, it) }
                .flatMap { anchor -> generateSequence(anchor) { it.superclass }.flatMap { it.interfaces.asSequence() }.toList() }
                .distinct().mapNotNull { verify(it, continuation) }
                .distinctBy { it.component }.singleOrNull()
        }

        /** Proxy 会把接口的全部实例方法（含默认方法）交给处理器；出现未知成员时拒绝，而不是运行期抛错。 */
        fun verify(component: Class<*>, continuation: Class<*>): InsightComponentContract? {
            if (!component.isInterface) return null
            val entry = Lookup.methodOrNull(component, CREATE, Context::class.java, ViewGroup::class.java)?.returnType
                ?.takeIf { it.isInterface } ?: return null
            if (Lookup.methodOrNull(entry, ROOT)?.returnType != View::class.java) return null
            if (instanceMethods(entry).any { it.name != ROOT }) return null
            val members = instanceMethods(component)
            val bind = members.filter { it.parameterTypes.toList() == listOf(entry, continuation) }.singleOrNull() ?: return null
            if (!KEYS.all { key -> members.any { it.name == key && it.parameterCount == 0 } }) return null
            if (members.any { it.name != CREATE && it.name != bind.name && it.name !in KEYS }) return null
            return InsightComponentContract(component, entry, bind)
        }

        private fun instanceMethods(type: Class<*>): List<Method> =
            // KavaMemberLookup 只沿 superclass 枚举；接口要按 superinterfaces 逐个取声明方法，才能覆盖 Proxy 的分派面。
            generateSequence(listOf(type)) { level -> level.flatMap { it.interfaces.toList() }.takeIf { it.isNotEmpty() } }
                .flatten().distinct().flatMap { owner -> Lookup.declaredMethods(owner) { !it.isStatic && !it.isSynthetic } }
                .toList()
    }
}

/** 原生 UIComponent：重用键只返回稳定字符串，宿主进程级 view-type 缓存不持有 Activity/View。 */
internal class InsightHeaderComponent(private val componentType: Class<*>, private val entryType: Class<*>,
    private val unit: Any, private val root: (Context) -> ViewGroup, private val bindName: String = "bindToView",
    private val bind: (ViewGroup) -> Unit) : InvocationHandler {
    private val readRoot = checkNotNull(Lookup.methodOrNull(entryType, "getRoot"))
    val value: Any = Proxy.newProxyInstance(componentType.classLoader, arrayOf(componentType), this)
    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
        in InsightComponentContract.KEYS -> KEY
        "createViewEntry" -> {
            val container = root(args?.getOrNull(0) as Context); bind(container)
            Proxy.newProxyInstance(entryType.classLoader, arrayOf(entryType)) { entry, call, values -> when (call.name) {
                "getRoot" -> container
                "hashCode" -> System.identityHashCode(entry)
                "equals" -> entry === values?.getOrNull(0)
                "toString" -> KEY
                else -> error("unsupported-insight-entry")
            } }
        }
        bindName -> {
            val entry = args?.getOrNull(0) ?: error("missing-insight-entry")
            val container = readRoot.invoke(entry) as? ViewGroup
                ?: error("invalid-insight-entry")
            bind(container); unit
        }
        "hashCode" -> System.identityHashCode(proxy)
        "equals" -> proxy === args?.getOrNull(0)
        "toString" -> KEY
        else -> error("unsupported-insight-component")
    }
    companion object {
        const val KEY = "com.Bilibili_Innocent_Lab.insight.header"
        fun prepend(items: List<*>, header: Any): List<Any?> = listOf(header) + items.filterNot {
            it != null && Proxy.isProxyClass(it.javaClass) && Proxy.getInvocationHandler(it) is InsightHeaderComponent
        }
    }
}

/** 保留宿主 adapter 与所有原生项，仅在其组件列表开头加入一个标准 UIComponent。 */
internal class InsightIntroHeaders private constructor(private val environment: HookEnvironment,
    private val recycler: Class<*>, private val adapterType: Class<*>, private val getAdapter: Method,
    private val contract: InsightComponentContract, private val unit: Any, private val scrollTo: Method) {
    private val component: Class<*> get() = contract.component
    private val owners = WeakHashMap<View, WeakReference<InsightScrollMount>>()
    private val adapters = WeakHashMap<Any, WeakReference<InsightScrollMount>>()
    private val plans = mutableMapOf<Class<*>, InsightAdapterItems?>()
    private val hooked = mutableSetOf<Method>()
    fun install(): Int {
        environment.registrar.exact("insight.intro.adapter", recycler, "setAdapter", adapterType) { after {
            if (!hasThrowable) (instance as? View)?.let { list -> owners[list]?.get()?.let { observe(list, it) } }
        } }
        return 1
    }
    fun attach(root: ViewGroup, card: InsightCard): InsightScrollMount? {
        // 宿主资源只能按宿主包名解析，模块 R.id 不包含这份布局。
        val id = root.resources.getIdentifier("recycler", "id", root.context.packageName)
        val list = root.findViewById<View>(id) as? ViewGroup ?: return null
        if (!recycler.isInstance(list)) return null
        val mount = InsightScrollMount(root, list, card, contract, unit, scrollTo) { observe(list, it) }
        environment.logInfo("insight.header.attach", "AI Insight header attached")
        owners[list] = WeakReference(mount)
        observe(list, mount)
        return mount
    }
    private fun observe(list: View, mount: InsightScrollMount) = HostThreadGuard.run("insight.header.adapter") {
        if (mount.closed) return@run
        val adapter = getAdapter.invoke(list) ?: return@run
        val type = adapter.javaClass
        environment.logInfo("insight.header.adapter.${type.name}", "AI Insight header adapter=${type.name}")
        val plan = plans.getOrPut(type) {
            InsightAdapterItems.resolve(type).also { resolved ->
                environment.logInfo("insight.header.plan.${type.name}",
                    "AI Insight header plan read=${resolved?.readKind ?: "none"} write=${resolved != null}")
            }
        } ?: return@run
        adapters[adapter] = WeakReference(mount)
        if (hooked.add(plan.write)) environment.registrar.exact("insight.intro.items.${type.name}",
            plan.write.declaringClass, plan.write.name, *plan.write.parameterTypes) { before {
            val target = instance?.let { adapters[it]?.get() } ?: return@before
            val items = argOrNull(0) as? List<*> ?: return@before
            if (!target.closed) {
                target.beforeUpdate()
                args[0] = target.items(items)
                environment.logInfo("insight.header.items", "AI Insight header items=${(args[0] as List<*>).size}")
            }
        } }
        // 数据可能先于 setAdapter 到达；下一次主线程任务补入，避开 RecyclerView 正在布局的阶段。
        list.post(HostThreadGuard.runnable("insight.header.initial") {
            if (!mount.closed && getAdapter.invoke(list) === adapter) {
                val items = plan.read(adapter) ?: return@runnable
                if (items.any { it == null || !component.isInstance(it) }) return@runnable
                if (items.firstOrNull() !== mount.component) {
                    mount.beforeUpdate(); plan.write.invoke(adapter, mount.items(items))
                }
            }
        })
    }
    companion object {
        fun resolve(environment: HookEnvironment): InsightIntroHeaders? = runCatching {
            val loader = environment.classLoader ?: return null
            val recycler = Lookup.classOrNull(loader, "androidx.recyclerview.widget.RecyclerView") ?: return null
            val adapter = Lookup.classOrNull(loader, "androidx.recyclerview.widget.RecyclerView\$Adapter") ?: return null
            val continuation = Lookup.classOrNull(loader, "kotlin.coroutines.Continuation") ?: return null
            val contract = InsightComponentContract.resolve(loader, continuation) ?: return null
            val unitClass = Lookup.classOrNull(loader, "kotlin.Unit") ?: return null
            val unit = Lookup.fieldOrNull(unitClass, "INSTANCE")?.get(null) ?: return null
            InsightIntroHeaders(environment, recycler, adapter,
                Lookup.inheritedMethodOrNull(recycler, "getAdapter") ?: return null, contract, unit,
                Lookup.inheritedMethodOrNull(recycler, "scrollToPosition", Int::class.javaPrimitiveType!!) ?: return null)
        }.getOrNull()
    }
}

internal class InsightScrollMount(private val owner: View, private val list: View, private val card: InsightCard,
    contract: InsightComponentContract, unit: Any, private val scrollTo: Method, private val attached: (InsightScrollMount) -> Unit) : AutoCloseable {
    var closed = false; private set
    private val header = InsightHeaderComponent(contract.component, contract.entry, unit, { context -> FrameLayout(context).apply {
        layoutParams = ViewGroup.LayoutParams(-1, -2)
        val inset = 8.toPx(context); setPadding(inset, inset, inset, inset)
    } }, contract.bind.name) { container ->
        if (!closed && card.parent !== container) {
            (card.parent as? ViewGroup)?.removeView(card)
            container.removeAllViews(); container.addView(card, ViewGroup.LayoutParams(-1, -2))
        }
    }
    val component: Any get() = header.value
    private val attachment = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { attached(this@InsightScrollMount) }
        override fun onViewDetachedFromWindow(v: View) = Unit
    }
    init { owner.addOnAttachStateChangeListener(attachment) }
    private var inserted = false
    fun beforeUpdate() {
        if (inserted) return
        inserted = true
        // DiffUtil 会保留原首项锚点，把新插入的入口留在屏幕上方；仅首次且原本在顶部时恢复第 0 项。
        if (!list.canScrollVertically(-1)) list.post(HostThreadGuard.runnable("insight.header.top") {
            if (!closed) scrollTo.invoke(list, 0)
        })
    }
    fun items(original: List<*>): List<Any?> = InsightHeaderComponent.prepend(original, component)
    override fun close() {
        if (closed) return
        closed = true; owner.removeOnAttachStateChangeListener(attachment)
        card.dispose(); (card.parent as? ViewGroup)?.removeView(card)
    }
}
