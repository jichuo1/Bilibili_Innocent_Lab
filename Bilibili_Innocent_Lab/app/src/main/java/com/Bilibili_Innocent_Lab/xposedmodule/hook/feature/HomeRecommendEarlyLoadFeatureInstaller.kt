package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.ViewGroup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 首页推荐提前加载下一页。
 *
 * 宿主只在最后一个已挂载卡片之后剩余不到 `预加载行数 × 2` 个 span 时请求下一页（预加载行数由服务端
 * 下发，缺省 1），滑完一页基本都要停在“加载中”页脚上等网络，体感就是几百毫秒的卡住；平板 3/4 列档位下
 * 这个阈值不到一行，等得更久。本功能不改宿主的阈值与状态，只在离末尾 [HomeRecommendEarlyLoadPolicy.LEAD_ROWS]
 * 行时**调用宿主同一个加载回调**；宿主自己的“加载中”“已到上限”“首刷完成前不加载”判断照常生效。
 *
 * 接入方式（8.84–9.17.0 一致）：宿主在 `PegasusFragment#onViewCreated` 里把加载监听器先后注册为列表的
 * 滚动监听与子项挂载监听。这里挂 `RecyclerView#addOnScrollListener`，按 [PegasusLoadMoreShape] 认出那只监听器，
 * 再给同一个列表加一个模块自己的挂载监听（宿主接口的动态代理）。每次有卡片挂载最多投递一次检查到列表的
 * 下一轮消息，检查本身不分配列表、最多读取约 [HomeRecommendEarlyLoadPolicy.LEAD_ROWS] 行的 span。
 *
 * 只读宿主公开的 androidx / kotlin 成员名（各版均未混淆）；监听器类名与字段名逐版漂移，只按结构认。
 */
internal class HomeRecommendEarlyLoadFeatureInstaller(
    private val enabled: Boolean,
    private val names: HostNames = HostNames()
) : FeatureInstaller {

    override val id: String = ID

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) {
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val loader = environment.classLoader ?: return missing(environment, "missing-class-loader")
        val recycler = RecyclerAccess.resolve(loader, names) ?: return missing(environment, "missing-recycler-boundary")
        val watcher = LoadMoreWatcher(recycler, environment)
        val registered = runCatching {
            environment.registrar.exact(
                LISTENER_HOOK_ID,
                recycler.recyclerView,
                "addOnScrollListener",
                recycler.scrollListener
            ) {
                after {
                    if (!hasThrowable) watcher.onScrollListenerAdded(thisObject, argOrNull(0))
                }
            }
        }.onFailure { throwable ->
            environment.logError("home_recommend_early_load_register", "[BIL] 首页推荐提前加载 Hook 注册失败: $throwable")
        }.isSuccess
        if (!registered) return missing(environment, "registration-failed")

        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        environment.reportStatus(CHANNEL_STATUS, "installed")
        environment.logInfo("home_recommend_early_load_ok", "[BIL] 首页推荐提前加载已安装")
        return FeatureInstallResult.Installed(1)
    }

    private fun missing(environment: HookEnvironment, reason: String): FeatureInstallResult.Skipped {
        environment.reportStatus(CHANNEL_STATUS, reason)
        environment.logError("home_recommend_early_load_missing", "[BIL] 首页推荐提前加载适配不完整: $reason")
        return FeatureInstallResult.Skipped(reason)
    }

    /** 宿主类名；测试用同形状的替身类替换，生产始终是默认值。 */
    internal data class HostNames(
        val recyclerView: String = "androidx.recyclerview.widget.RecyclerView",
        val gridLayoutManager: String = "androidx.recyclerview.widget.GridLayoutManager",
        val function0: String = "kotlin.jvm.functions.Function0"
    )

    /** 安装期一次解析的宿主 androidx 列表成员；全部是公开 API 名。 */
    internal class RecyclerAccess private constructor(
        val recyclerView: Class<*>,
        val scrollListener: Class<*>,
        val attachListener: Class<*>,
        val function0: Class<*>,
        private val gridLayoutManager: Class<*>,
        private val addAttachListener: Method,
        private val getAdapter: Method,
        private val childAdapterPosition: Method,
        private val isComputingLayout: Method,
        private val getLayoutManager: Method,
        private val itemCount: Method,
        private val spanCount: Method,
        private val spanSizeLookup: Method,
        private val spanSize: Method,
        private val invoke: Method
    ) {
        fun addAttachListener(recycler: Any, listener: Any) {
            addAttachListener.invoke(recycler, listener)
        }

        fun computingLayout(recycler: Any): Boolean = isComputingLayout.invoke(recycler) == true

        fun itemCount(recycler: Any): Int {
            val adapter = getAdapter.invoke(recycler) ?: return 0
            return itemCount.invoke(adapter) as? Int ?: 0
        }

        fun adapterPosition(recycler: Any, child: Any): Int = childAdapterPosition.invoke(recycler, child) as? Int ?: -1

        /** 非网格布局按单列处理：每项一整行，与宿主监听器的回退一致。 */
        fun grid(recycler: Any): Pair<Int, Any?> {
            val manager = getLayoutManager.invoke(recycler)
            if (manager == null || !gridLayoutManager.isInstance(manager)) return 1 to null
            val columns = spanCount.invoke(manager) as? Int ?: return 1 to null
            return columns to spanSizeLookup.invoke(manager)
        }

        fun spanSize(lookup: Any?, position: Int): Int =
            if (lookup == null) 1 else spanSize.invoke(lookup, position) as? Int ?: 1

        fun request(callback: Any) {
            invoke.invoke(callback)
        }

        companion object {
            fun resolve(loader: ClassLoader, names: HostNames): RecyclerAccess? {
                fun type(name: String) = KavaMemberLookup.classOrNull(loader, name)
                val int = Integer.TYPE
                val boolean = java.lang.Boolean.TYPE
                val recycler = type(names.recyclerView) ?: return null
                val scroll = type("${names.recyclerView}\$OnScrollListener") ?: return null
                val attach = type("${names.recyclerView}\$OnChildAttachStateChangeListener")?.takeIf { it.isInterface } ?: return null
                val adapter = type("${names.recyclerView}\$Adapter") ?: return null
                val grid = type(names.gridLayoutManager) ?: return null
                val lookup = type("${names.gridLayoutManager}\$SpanSizeLookup") ?: return null
                val function0 = type(names.function0)?.takeIf { it.isInterface } ?: return null
                return RecyclerAccess(
                    recyclerView = recycler,
                    scrollListener = scroll,
                    attachListener = attach,
                    function0 = function0,
                    gridLayoutManager = grid,
                    addAttachListener = KavaMemberLookup.methodOrNull(recycler, "addOnChildAttachStateChangeListener", attach)
                        ?: return null,
                    getAdapter = KavaMemberLookup.methodOrNull(recycler, "getAdapter")
                        ?.takeIf { it.returnType == adapter } ?: return null,
                    childAdapterPosition = KavaMemberLookup.methodOrNull(recycler, "getChildAdapterPosition", android.view.View::class.java)
                        ?.takeIf { it.returnType == int } ?: return null,
                    isComputingLayout = KavaMemberLookup.methodOrNull(recycler, "isComputingLayout")
                        ?.takeIf { it.returnType == boolean } ?: return null,
                    getLayoutManager = KavaMemberLookup.methodOrNull(recycler, "getLayoutManager") ?: return null,
                    itemCount = KavaMemberLookup.methodOrNull(adapter, "getItemCount")
                        ?.takeIf { it.returnType == int } ?: return null,
                    spanCount = KavaMemberLookup.methodOrNull(grid, "getSpanCount")
                        ?.takeIf { it.returnType == int } ?: return null,
                    spanSizeLookup = KavaMemberLookup.methodOrNull(grid, "getSpanSizeLookup")
                        ?.takeIf { it.returnType == lookup } ?: return null,
                    spanSize = KavaMemberLookup.methodOrNull(lookup, "getSpanSize", int)
                        ?.takeIf { it.returnType == int } ?: return null,
                    invoke = KavaMemberLookup.methodOrNull(function0, "invoke") ?: return null
                )
            }
        }
    }

    /**
     * 认出宿主加载监听器并给它所在的列表挂上检查。只在主线程被调用（View 注册路径）；
     * 列表与监听器都只弱引用，页面销毁后随列表一起回收。
     */
    internal class LoadMoreWatcher(
        private val recycler: RecyclerAccess,
        private val environment: HookEnvironment
    ) {
        private val shapes = ConcurrentHashMap<Class<*>, Any>()
        private val watched = WeakHashMap<Any, Watch>()
        private val firstObserved = AtomicBoolean(false)
        private val firstApplied = AtomicBoolean(false)

        fun onScrollListenerAdded(list: Any?, listener: Any?) {
            if (list !is ViewGroup || listener == null) return
            val shape = shapeOf(listener.javaClass) ?: return
            val watch = synchronized(watched) {
                if (watched.containsKey(list)) return
                Watch(list, listener, shape).also { watched[list] = it }
            }
            val attached = runCatching { recycler.addAttachListener(list, watch.proxy) }
                .onFailure { throwable ->
                    synchronized(watched) { watched.remove(list) }
                    environment.logError("home_recommend_early_load_attach", "[BIL] 首页推荐提前加载挂接列表失败: $throwable")
                }.isSuccess
            if (attached && firstObserved.compareAndSet(false, true)) {
                environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
            }
        }

        internal fun shapeOf(type: Class<*>): PegasusLoadMoreShape? {
            val cached = shapes[type]
            if (cached != null) return cached as? PegasusLoadMoreShape
            val shape = PegasusLoadMoreShape.of(type, recycler.scrollListener, recycler.attachListener, recycler.function0)
            shapes.putIfAbsent(type, shape ?: NOT_A_LOAD_MORE_LISTENER)
            return shape
        }

        private fun requested(watch: Watch) {
            if (firstApplied.compareAndSet(false, true)) {
                environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                environment.reportStatus(CHANNEL_STATUS, "applied")
                environment.logInfo("home_recommend_early_load_hit", "[BIL] 首页推荐已提前请求下一页(列数=${watch.lastSpanCount})")
            }
        }

        inner class Watch(list: ViewGroup, listener: Any, private val shape: PegasusLoadMoreShape) {
            private val list = WeakReference(list)
            private val listener = WeakReference(listener)
            private val gate = EarlyLoadGate()
            private var scheduled = false
            internal var lastSpanCount = 0
                private set

            private val check = HostThreadGuard.runnable(GUARD_KEY) {
                scheduled = false
                evaluate()
            }

            val proxy: Any = Proxy.newProxyInstance(
                recycler.attachListener.classLoader,
                arrayOf(recycler.attachListener),
                InvocationHandler { proxy, method, args ->
                    when (method.name) {
                        "onChildViewAttachedToWindow" -> {
                            HostThreadGuard.run(GUARD_KEY) { schedule() }
                            null
                        }
                        "equals" -> proxy === args?.getOrNull(0)
                        "hashCode" -> System.identityHashCode(proxy)
                        "toString" -> "BilHomeEarlyLoad@" + Integer.toHexString(System.identityHashCode(proxy))
                        else -> null
                    }
                }
            )

            /** 同一轮布局里连续挂载多张卡只检查一次；检查放到布局结束后的下一条消息。 */
            private fun schedule() {
                if (scheduled) return
                val view = list.get() ?: return
                scheduled = view.post(check)
            }

            private fun evaluate() {
                val view = list.get() ?: return
                val owner = listener.get() ?: return
                if (!view.isAttachedToWindow || view.childCount <= 0) return
                // 宿主首刷完成前关着这个开关；关着时连列表状态都不读。
                if (!shape.enabled.getBoolean(owner) || recycler.computingLayout(view)) return
                val itemCount = recycler.itemCount(view)
                val last = recycler.adapterPosition(view, view.getChildAt(view.childCount - 1))
                val (spanCount, lookup) = recycler.grid(view)
                lastSpanCount = spanCount
                if (!gate.shouldRequest(true, last, itemCount, spanCount) { recycler.spanSize(lookup, it) }) return
                val callback = shape.callback.get(owner) ?: return
                recycler.request(callback)
                requested(this)
            }
        }

        private companion object {
            val NOT_A_LOAD_MORE_LISTENER = Any()
        }
    }

    companion object {
        const val ID = "home_recommend_early_load"
        internal const val LISTENER_HOOK_ID = "home.recommend.early_load.listener"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "home_recommend_early_load_status"
        private const val GUARD_KEY = "home.recommend.early_load"
    }
}
