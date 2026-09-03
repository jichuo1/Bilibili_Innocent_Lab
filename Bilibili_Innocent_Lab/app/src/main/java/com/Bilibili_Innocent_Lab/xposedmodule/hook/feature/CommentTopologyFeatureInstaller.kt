package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.highcapable.betterandroid.ui.extension.view.childOrNull
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.InjectedUiLocale
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology.ReplyTopologyGraph
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology.ReplyTopologyGraphBuilder
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology.ReplyTopologyPagingBudget
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology.ReplyTopologyPagingDecision
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology.ReplyTopologyPagingGuard
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology.ReplyTopologyPagingStopReason
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology.ReplyTopologyRequestToken
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology.ReplyTopologySessionGate
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology.ReplyTopologySessionToken
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology.ReplyTopologyThreadKey
import com.Bilibili_Innocent_Lab.xposedmodule.ui.overlay.ReplyTopologyPanelCloseReason
import com.Bilibili_Innocent_Lab.xposedmodule.ui.overlay.ReplyTopologyPanelConfig
import com.Bilibili_Innocent_Lab.xposedmodule.ui.overlay.ReplyTopologyPanelController
import com.Bilibili_Innocent_Lab.xposedmodule.ui.overlay.ReplyTopologyPanelListener
import com.Bilibili_Innocent_Lab.xposedmodule.ui.overlay.ReplyTopologyPanelPhase
import com.Bilibili_Innocent_Lab.xposedmodule.ui.overlay.ReplyTopologyPanelPosition
import com.Bilibili_Innocent_Lab.xposedmodule.ui.overlay.ReplyTopologyPanelSession
import com.Bilibili_Innocent_Lab.xposedmodule.ui.overlay.ReplyTopologyPanelState
import com.Bilibili_Innocent_Lab.xposedmodule.ui.overlay.ReplyTopologyPanelStrings
import com.Bilibili_Innocent_Lab.xposedmodule.ui.overlay.ReplyTopologyRenderSnapshot
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isStatic
import com.highcapable.kavaref.extension.isSubclassOf
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal enum class ReplyTopologyBindingScope {
    OWNER,
    PRIMARY,
    SECONDARY,
    UNKNOWN
}

internal enum class ReplyTopologyBindingAction {
    SHOW,
    CLEAR_AND_RETRY,
    RETRY_ONLY,
    IGNORE
}

internal fun replyTopologyBindingAction(
    scope: ReplyTopologyBindingScope,
    hasSeed: Boolean
): ReplyTopologyBindingAction = when {
    scope == ReplyTopologyBindingScope.SECONDARY -> ReplyTopologyBindingAction.IGNORE
    scope == ReplyTopologyBindingScope.UNKNOWN -> ReplyTopologyBindingAction.RETRY_ONLY
    hasSeed -> ReplyTopologyBindingAction.SHOW
    scope == ReplyTopologyBindingScope.OWNER || scope == ReplyTopologyBindingScope.PRIMARY ->
        ReplyTopologyBindingAction.CLEAR_AND_RETRY
    else -> ReplyTopologyBindingAction.IGNORE
}

internal fun replyTopologyMessageScope(
    primaryPresent: Boolean,
    primaryVisible: Boolean,
    secondaryPresent: Boolean,
    secondaryVisible: Boolean
): ReplyTopologyBindingScope = when {
    primaryVisible && !secondaryVisible -> ReplyTopologyBindingScope.PRIMARY
    secondaryVisible && !primaryVisible -> ReplyTopologyBindingScope.SECONDARY
    primaryPresent && !secondaryPresent -> ReplyTopologyBindingScope.PRIMARY
    secondaryPresent && !primaryPresent -> ReplyTopologyBindingScope.SECONDARY
    else -> ReplyTopologyBindingScope.UNKNOWN
}

/**
 * 高版本评论 Handler 同时存在多个真实绑定分支；Adapter 缓存点只负责给出已验证的
 * Handler 锚点，运行时再按同一 ViewBinding 结构规则补齐精确签名，避免只挂到某个分支。
 */
internal fun collectReplyTopologyHighBindPoints(
    owner: Class<*>,
    cachedPoint: VersionAdapter.HookPoint
): List<VersionAdapter.HookPoint> {
    val declaredMethods = KavaMemberLookup.declaredMethods(owner)
    val cachedParams = cachedPoint.paramClassNames ?: return listOf(cachedPoint)
    val cachedMethod = declaredMethods.singleOrNull { method ->
        method.name == cachedPoint.methodName &&
            method.parameterTypes.map { it.name } == cachedParams
    } ?: return listOf(cachedPoint)

    fun looksLikeViewBinding(parameterType: Class<*>): Boolean {
        if (parameterType.isPrimitive || parameterType.isArray || parameterType.isInterface) {
            return false
        }
        return KavaMemberLookup.methods(
            parameterType,
            includeSuperclasses = true
        ) { candidate ->
            candidate.name == "getRoot" && candidate.parameterCount == 0 &&
                candidate.returnType isSubclassOf classOf<View>()
        }.isNotEmpty() || KavaMemberLookup.declaredFields(parameterType) { candidate ->
            candidate.type isSubclassOf classOf<View>()
        }.isNotEmpty()
    }

    val bindingTypes = cachedMethod.parameterTypes.filter(::looksLikeViewBinding)
    if (bindingTypes.size != 1) return listOf(cachedPoint)
    val bindingType = bindingTypes.single()

    return buildList {
        add(cachedPoint)
        declaredMethods.forEach { method ->
            if (method.isStatic || method.isSynthetic || method.isBridge) {
                return@forEach
            }
            if (method.parameterCount !in 1..5) return@forEach
            if (method.returnType != Void.TYPE || bindingType !in method.parameterTypes) {
                return@forEach
            }
            add(
                VersionAdapter.HookPoint(
                    className = owner.name,
                    methodName = method.name,
                    paramClassNames = method.parameterTypes.map { it.name }
                )
            )
        }
    }.distinctBy { point ->
        "${point.className}#${point.methodName}(${point.paramClassNames.orEmpty()})"
    }
}

/**
 * 回复脉络功能安装器。自由复制的文本、触摸、emoji、气泡和官方三点菜单链路均不在
 * 本文件内修改；入口放在宿主已有 item_include_actions 操作栏中，沿用其触摸直通边界。
 */
internal class CommentTopologyFeatureInstaller(
    private val enabled: Boolean,
    private val points: VersionAdapter.CommentTopologyPoints?,
    private val lowBindPoint: VersionAdapter.HookPoint?,
    private val highBindPoint: VersionAdapter.HookPoint?
) : FeatureInstaller {

    override val id: String = ID
    private val bindingTokens = WeakHashMap<ViewGroup, Any>()

    /**
     * 每个绑定根实例的入口几何缓存。
     *
     * `findAnchorTarget` 原本对每一条评论绑定都要做四次 `ViewGroup.findViewById`，而它是整棵
     * 子树的深度优先递归；9.10.0 的一条评论展开后约 90–120 个 View，两个绑定点都命中时单条
     * 绑定要走几百个节点，快速滑动时直接压在 UI 线程的帧预算上。
     *
     * RecyclerView 只复用十几个 itemView 且子结构在复用中稳定，因此把"位置"缓存下来，只在
     * 廉价校验失败时重算。**随数据变化的部分不缓存**：`insertIndex` 每次用 `indexOfChild`
     * 重算，`scope` 每次重新读取 primary/secondary 的可见性，判定顺序与原实现完全一致。
     */
    private val anchorGeometries = WeakHashMap<View, AnchorGeometry>()

    /**
     * 每个操作行最多保留一次在途的动画帧补绑回调。
     *
     * 原实现对每条命中 CLEAR_AND_RETRY/RETRY_ONLY 的绑定都 `postOnAnimation` 一个新回调，
     * 同一行被 `d`/`e` 两个绑定点触发时还会翻倍，全部落在滚动的动画帧上。这里改为"最新请求表
     * + 单个在途回调"：回调执行时读取该行的最新请求，语义与原来的 token 校验一致（本来也只有
     * 最新 token 有效）。回调因宿主丢弃 RunQueue 而没跑成时，由时间戳在下一次绑定自愈补发。
     */
    private val pendingSeedRetries = WeakHashMap<ViewGroup, SeedRetryRequest>()
    private val pendingSeedRetryPostedAtMs = WeakHashMap<ViewGroup, Long>()

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) {
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val adapted = points ?: return missing(environment, "missing-adapter-point")
        val host = ReplyTopologyHostAccess.resolve(environment, adapted)
            ?: return missing(environment, "missing-host-access")
        val bindPoints = buildList {
            lowBindPoint?.let(::add)
            highBindPoint?.let { cachedPoint ->
                val owner = environment.classLoader?.let { loader ->
                    KavaMemberLookup.classOrNull(loader, cachedPoint.className)
                }
                if (owner == null) {
                    add(cachedPoint)
                } else {
                    addAll(collectReplyTopologyHighBindPoints(owner, cachedPoint))
                }
            }
        }
            .distinctBy { point ->
                "${point.className}#${point.methodName}(${point.paramClassNames.orEmpty()})"
            }
        if (bindPoints.isEmpty()) return missing(environment, "missing-bind-point")

        val seedStore = ReplyTopologySeedStore()
        val coordinator = ReplyTopologyCoordinator(host, environment)
        val committed = AtomicBoolean(false)
        var installed = 0
        var bindInstalled = 0

        // 先确认至少一个 UI 绑定入口可 Hook，再开启 mapper 数据桥。这样即使宿主 UI 漂移，
        // 也不会留下一个持续快照评论数据、却永远没有可见入口的半安装 Hook。
        bindPoints.forEachIndexed { index, point ->
            runCatching {
                val captures = ThreadLocal<ArrayDeque<Any?>>()
                environment.registrar.adapted("comment.topology.bind.$index", point) {
                    before {
                        if (!committed.get()) return@before
                        val stack = captures.get() ?: ArrayDeque<Any?>().also(captures::set)
                        stack.addLast(findCommentItem(host.commentItemClass, instance, args))
                    }
                    after {
                        if (!committed.get()) {
                            captures.remove()
                            return@after
                        }
                        val stack = captures.get()
                        val commentItem = if (stack.isNullOrEmpty()) {
                            findCommentItem(host.commentItemClass, instance, args)
                        } else {
                            stack.removeLast()
                        }
                        if (stack?.isEmpty() == true) captures.remove()
                        val rootView = findBindingRoot(instance, args)
                        rootView ?: return@after
                        val commentItemId = commentItem?.let(host::readCommentItemId)
                        val seed = commentItem?.let { item ->
                            seedStore.get(item, commentItemId)
                        }
                        bindAnchor(
                            boundView = rootView,
                            commentItem = commentItem,
                            commentItemId = commentItemId,
                            seed = seed,
                            seedStore = seedStore,
                            host = host,
                            coordinator = coordinator,
                            environment = environment
                        )
                    }
                }
                installed++
                bindInstalled++
            }.onFailure { throwable ->
                environment.logError(
                    "comment_topology_bind_$index",
                    "[BIL] 回复脉络入口绑定失败(${point.className}#${point.methodName}): " +
                        throwable
                )
            }
        }
        if (bindInstalled == 0) return missing(environment, "bind-registration-failed")

        var mapperInstalled = 0
        adapted.mapperMethods.forEachIndexed { index, mapperPoint ->
            runCatching {
                environment.registrar.adapted("comment.topology.mapper.$index", mapperPoint) {
                    after {
                        if (!committed.get()) return@after
                        val commentItem = result ?: return@after
                        if (!host.commentItemClass.isInstance(commentItem)) return@after
                        val replyInfo = args.firstOrNull(host.replyInfoClass::isInstance)
                            ?: return@after
                        val seed = host.snapshotRoot(replyInfo) ?: return@after
                        seedStore.put(commentItem, seed)
                    }
                }
                installed++
                mapperInstalled++
            }.onFailure { throwable ->
                environment.logError(
                    "comment_topology_mapper_$index",
                    "[BIL] 回复脉络身份桥注册失败(${mapperPoint.className}#" +
                        "${mapperPoint.methodName}): $throwable"
                )
            }
        }
        if (mapperInstalled != adapted.mapperMethods.size || mapperInstalled == 0) {
            return missing(environment, "mapper-registration-failed")
        }
        committed.set(true)

        val status = if (bindInstalled == bindPoints.size) "success" else "partial"
        environment.reportStatus(CHANNEL_STATUS, status)
        environment.logInfo(
            "comment_topology_ok",
            "[BIL] 回复脉络已安装，hooks=$installed,binds=" +
                bindPoints.joinToString("|") { point ->
                    "${point.methodName}(${point.paramClassNames.orEmpty().joinToString(",")})"
                } + ",status=$status"
        )
        return FeatureInstallResult.Installed(installed)
    }

    private fun findCommentItem(
        commentItemClass: Class<*>,
        instance: Any?,
        args: Array<out Any?>
    ): Any? {
        args.firstOrNull(commentItemClass::isInstance)?.let { return it }
        val owner = instance ?: return null
        commentItemFields[owner.javaClass]?.let { field ->
            return runCatching { field.get(owner) }.getOrNull()
        }
        if (owner.javaClass in noCommentItemFieldClasses) return null
        val field = KavaMemberLookup.fields(
            owner.javaClass,
            includeSuperclasses = true,
            makeAccessible = true
        ) { candidate -> candidate.type isSubclassOf commentItemClass }.firstOrNull()
        if (field == null) {
            noCommentItemFieldClasses += owner.javaClass
            return null
        }
        commentItemFields[owner.javaClass] = field
        return runCatching { field.get(owner) }.getOrNull()
    }

    private fun findBindingRoot(instance: Any?, args: Array<out Any?>): View? {
        val owner = instance
        if (owner != null) {
            holderItemViewFields[owner.javaClass]?.let { field ->
                (runCatching { field.get(owner) }.getOrNull() as? View)?.let { return it }
            }
            if (owner.javaClass !in noHolderItemViewClasses) {
                val field = KavaMemberLookup.fieldOrNull(
                    owner.javaClass,
                    "itemView",
                    includeSuperclasses = true
                )
                if (field != null && field.type isSubclassOf classOf<View>()) {
                    holderItemViewFields[owner.javaClass] = field
                    (runCatching { field.get(owner) }.getOrNull() as? View)?.let { return it }
                } else {
                    noHolderItemViewClasses += owner.javaClass
                }
            }
        }
        args.filterIsInstance<View>().firstOrNull()?.let { return it }
        args.forEach { argument ->
            findViewFieldValue(argument)?.let { return it }
        }
        return findViewFieldValue(owner)
    }

    private fun findViewFieldValue(owner: Any?): View? {
        if (owner == null || owner is View) return owner as? View
        bindingRootMethods[owner.javaClass]?.let { method ->
            (runCatching { method.invoke(owner) }.getOrNull() as? View)?.let { return it }
        }
        if (owner.javaClass !in noBindingRootMethodClasses) {
            val method = KavaMemberLookup.inheritedMethodOrNull(owner.javaClass, "getRoot")
                ?.takeIf { candidate ->
                    candidate.parameterCount == 0 &&
                        candidate.returnType isSubclassOf classOf<View>()
                }
            if (method != null) {
                bindingRootMethods[owner.javaClass] = method
                (runCatching { method.invoke(owner) }.getOrNull() as? View)?.let { return it }
            } else {
                noBindingRootMethodClasses += owner.javaClass
            }
        }
        bindingRootFields[owner.javaClass]?.let { field ->
            (runCatching { field.get(owner) }.getOrNull() as? View)?.let { return it }
        }
        if (owner.javaClass in noBindingRootFieldClasses) return null
        val conventional = KavaMemberLookup.fieldOrNull(
            owner.javaClass,
            "a",
            includeSuperclasses = true
        )?.takeIf { candidate -> candidate.type isSubclassOf classOf<View>() }
        val field = conventional ?: KavaMemberLookup.fields(
            declaringClass = owner.javaClass,
            includeSuperclasses = true,
            makeAccessible = true
        ) { candidate -> candidate.type isSubclassOf classOf<View>() }
            .firstOrNull { candidate ->
                runCatching { candidate.get(owner) is View }.getOrDefault(false)
            }
        if (field == null) {
            noBindingRootFieldClasses += owner.javaClass
            return null
        }
        bindingRootFields[owner.javaClass] = field
        return runCatching { field.get(owner) }.getOrNull() as? View
    }

    private fun bindAnchor(
        boundView: View,
        commentItem: Any?,
        commentItemId: Long?,
        seed: ReplyTopologySeed?,
        seedStore: ReplyTopologySeedStore,
        host: ReplyTopologyHostAccess,
        coordinator: ReplyTopologyCoordinator,
        environment: HookEnvironment
    ) {
        val target = findAnchorTarget(boundView) ?: run {
            environment.logInfo(
                "comment_topology_anchor_target_missing",
                "[BIL] 回复脉络入口未找到宿主操作按钮行"
            )
            return
        }
        val action = replyTopologyBindingAction(target.scope, seed != null)
        if (action == ReplyTopologyBindingAction.IGNORE) return

        val token = Any()
        synchronized(bindingTokens) {
            bindingTokens[target.row] = token
        }

        when (action) {
            ReplyTopologyBindingAction.SHOW -> showAnchor(
                target,
                requireNotNull(seed),
                coordinator,
                environment
            )
            ReplyTopologyBindingAction.CLEAR_AND_RETRY -> {
                findExistingAnchor(target.row)?.clearBinding()
                if (commentItem != null && commentItemId != null) {
                    scheduleSeedRetry(
                        boundView = boundView,
                        targetRow = target.row,
                        token = token,
                        commentItem = commentItem,
                        expectedCommentItemId = commentItemId,
                        seedStore = seedStore,
                        host = host,
                        coordinator = coordinator,
                        environment = environment
                    )
                }
            }
            ReplyTopologyBindingAction.RETRY_ONLY -> {
                if (commentItem != null && commentItemId != null) {
                    scheduleSeedRetry(
                        boundView = boundView,
                        targetRow = target.row,
                        token = token,
                        commentItem = commentItem,
                        expectedCommentItemId = commentItemId,
                        seedStore = seedStore,
                        host = host,
                        coordinator = coordinator,
                        environment = environment
                    )
                }
            }
            ReplyTopologyBindingAction.IGNORE -> Unit
        }
    }

    private fun scheduleSeedRetry(
        boundView: View,
        targetRow: ViewGroup,
        token: Any,
        commentItem: Any,
        expectedCommentItemId: Long,
        seedStore: ReplyTopologySeedStore,
        host: ReplyTopologyHostAccess,
        coordinator: ReplyTopologyCoordinator,
        environment: HookEnvironment
    ) {
        val rowRef = WeakReference(targetRow)
        pendingSeedRetries[targetRow] = SeedRetryRequest(
            boundView = WeakReference(boundView),
            token = token,
            commentItem = WeakReference(commentItem),
            expectedCommentItemId = expectedCommentItemId
        )
        val now = SystemClock.uptimeMillis()
        val postedAt = pendingSeedRetryPostedAtMs[targetRow]
        if (postedAt != null && now - postedAt < SEED_RETRY_STALE_MS) return
        pendingSeedRetryPostedAtMs[targetRow] = now
        boundView.postOnAnimation {
            val row = rowRef.get() ?: return@postOnAnimation
            runSeedRetry(row, seedStore, host, coordinator, environment)
        }
    }

    /** 读取该行最新的补绑请求；校验链与原实现逐条一致。 */
    private fun runSeedRetry(
        targetRow: ViewGroup,
        seedStore: ReplyTopologySeedStore,
        host: ReplyTopologyHostAccess,
        coordinator: ReplyTopologyCoordinator,
        environment: HookEnvironment
    ) {
        pendingSeedRetryPostedAtMs.remove(targetRow)
        val request = pendingSeedRetries.remove(targetRow) ?: return
        val currentView = request.boundView.get() ?: return
        if (!targetRow.isAttachedToWindow) return
        val isCurrent = synchronized(bindingTokens) {
            bindingTokens[targetRow] === request.token
        }
        if (!isCurrent) return
        val currentItem = request.commentItem.get() ?: return
        if (host.readCommentItemId(currentItem) != request.expectedCommentItemId) return
        val refreshedTarget = findAnchorTarget(currentView) ?: return
        if (refreshedTarget.row !== targetRow ||
            (refreshedTarget.scope != ReplyTopologyBindingScope.OWNER &&
                refreshedTarget.scope != ReplyTopologyBindingScope.PRIMARY)
        ) return
        val refreshedSeed = seedStore.get(currentItem, request.expectedCommentItemId) ?: return
        showAnchor(refreshedTarget, refreshedSeed, coordinator, environment)
    }

    private fun showAnchor(
        target: AnchorTarget,
        seed: ReplyTopologySeed,
        coordinator: ReplyTopologyCoordinator,
        environment: HookEnvironment
    ) {
        val anchor = findExistingAnchor(target.row) ?: run {
            runCatching {
                // 宿主 Context 不保证具备模块 AppCompat 主题；入口只需要平台 TextView，
                // 并将构造也纳入失败隔离，避免 after Hook 静默吞掉构造异常。
                val view = ReplyTopologyAnchorView(target.row.context)
                val added = runCatching {
                    // 让宿主真实按钮行生成自己的 LayoutParams 子类，避免自定义 ViewGroup 拒绝通用参数。
                    val index = target.insertIndex.coerceIn(0, target.row.childCount)
                    val referenceId = target.referenceView.id
                    val referenceParams = target.referenceView.layoutParams
                    val paramsClass = referenceParams?.javaClass
                    val constraintLike = paramsClass?.let(::isConstraintLayoutParamsClass) == true
                    val constraintAccess = paramsClass?.let(::resolveConstraintLayoutParamsAccess)
                    if (constraintLike) {
                        check(referenceId != View.NO_ID) { "constraint-reference-id" }
                        addConstrainedAnchor(
                            target = target,
                            view = view,
                            index = index,
                            referenceId = referenceId,
                            access = requireNotNull(constraintAccess) {
                                "constraint-layout-params-access"
                            }
                        )
                    } else {
                        target.row.addView(view, index)
                        view.layoutParams = view.layoutParams.apply {
                            width = ViewGroup.LayoutParams.WRAP_CONTENT
                            height = dp(target.row, 30)
                            if (this is ViewGroup.MarginLayoutParams) {
                                marginStart = dp(target.row, 4)
                            }
                        }
                    }
                }.onFailure { throwable ->
                    environment.logError(
                        "comment_topology_anchor_add_failed",
                        "[BIL] 回复脉络入口注入失败: $throwable"
                    )
                }.isSuccess
                if (!added) {
                    runCatching {
                        if (view.parent === target.row) target.row.removeView(view)
                    }
                    error("anchor-add-failed")
                }
                view
            }.onFailure { throwable ->
                environment.logError(
                    "comment_topology_anchor_create_failed",
                    "[BIL] 回复脉络入口创建失败: $throwable"
                )
            }.getOrNull() ?: return
        }
        anchor.bind(seed, coordinator)
    }

    private fun addConstrainedAnchor(
        target: AnchorTarget,
        view: ReplyTopologyAnchorView,
        index: Int,
        referenceId: Int,
        access: ConstraintLayoutParamsAccess
    ) {
        val neighborToReference = if (target.beforeReference) {
            access.endToStart
        } else {
            access.startToEnd
        }
        val referenceToNeighbor = if (target.beforeReference) {
            access.startToEnd
        } else {
            access.endToStart
        }
        val referenceParams = target.referenceView.layoutParams
            ?: error("constraint-reference-params")
        check(access.paramsClass.isInstance(referenceParams)) {
            "constraint-reference-params-type"
        }
        val neighbors = ArrayList<ConstraintNeighbor>(1)
        for (childIndex in 0 until target.row.childCount) {
            val child = target.row.getChildAt(childIndex)
            if (child === target.referenceView) continue
            if (child.id == View.NO_ID) continue
            val params = child.layoutParams ?: continue
            if (!access.paramsClass.isInstance(params)) continue
            val pointsToReference = runCatching {
                neighborToReference.getInt(params) == referenceId
            }.getOrDefault(false)
            if (pointsToReference) {
                neighbors += ConstraintNeighbor(child, params)
            }
        }
        check(neighbors.size == 1) {
            "constraint-neighbor-count=${neighbors.size}"
        }

        val params = access.intConstructor.newInstance(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(target.row, 30)
        ) as? ViewGroup.LayoutParams ?: error("constraint-layout-params-type")
        view.id = View.generateViewId()
        access.topToTop.setInt(params, referenceId)
        access.bottomToBottom.setInt(params, referenceId)
        val neighbor = neighbors.single()
        val referenceConstraint = referenceToNeighbor.getInt(referenceParams)
        check(referenceConstraint == View.NO_ID || referenceConstraint == neighbor.view.id) {
            "constraint-reference-conflict=$referenceConstraint"
        }
        val hasReciprocalConstraint = referenceConstraint == neighbor.view.id
        if (target.beforeReference) {
            access.endToStart.setInt(params, referenceId)
            if (hasReciprocalConstraint) {
                access.startToEnd.setInt(params, neighbor.view.id)
            }
        } else {
            access.startToEnd.setInt(params, referenceId)
            if (hasReciprocalConstraint) {
                access.endToStart.setInt(params, neighbor.view.id)
            }
        }
        if (params is ViewGroup.MarginLayoutParams) {
            if (target.beforeReference) {
                params.marginEnd = dp(target.row, 4)
            } else {
                params.marginStart = dp(target.row, 4)
            }
        }

        val previousNeighborConstraint = neighborToReference.getInt(neighbor.params)
        try {
            target.row.addView(view, index, params)
            neighborToReference.setInt(neighbor.params, view.id)
            neighbor.view.layoutParams = neighbor.params
            if (hasReciprocalConstraint) {
                referenceToNeighbor.setInt(referenceParams, view.id)
                target.referenceView.layoutParams = referenceParams
            }
        } catch (throwable: Throwable) {
            runCatching {
                neighborToReference.setInt(neighbor.params, previousNeighborConstraint)
                neighbor.view.layoutParams = neighbor.params
            }
            if (hasReciprocalConstraint) runCatching {
                referenceToNeighbor.setInt(referenceParams, referenceConstraint)
                target.referenceView.layoutParams = referenceParams
            }
            runCatching {
                if (view.parent === target.row) target.row.removeView(view)
            }
            throw throwable
        }
    }

    private fun resolveConstraintLayoutParamsAccess(
        paramsClass: Class<*>
    ): ConstraintLayoutParamsAccess? {
        constraintLayoutParamsAccess[paramsClass]?.let { return it }
        if (paramsClass in noConstraintLayoutParamsAccess) return null
        val intType = Integer.TYPE
        val constructor = KavaMemberLookup.constructorOrNull(paramsClass, intType, intType)
        fun intField(name: String): Field? = KavaMemberLookup.fieldOrNull(
            paramsClass,
            name,
            includeSuperclasses = true
        )?.takeIf { field -> field.type == intType }
        val resolved = constructor?.let {
            val startToEnd = intField("startToEnd") ?: return@let null
            val endToStart = intField("endToStart") ?: return@let null
            val topToTop = intField("topToTop") ?: return@let null
            val bottomToBottom = intField("bottomToBottom") ?: return@let null
            ConstraintLayoutParamsAccess(
                paramsClass = paramsClass,
                intConstructor = it,
                startToEnd = startToEnd,
                endToStart = endToStart,
                topToTop = topToTop,
                bottomToBottom = bottomToBottom
            )
        }
        if (resolved == null) {
            noConstraintLayoutParamsAccess += paramsClass
            return null
        }
        return constraintLayoutParamsAccess.putIfAbsent(paramsClass, resolved) ?: resolved
    }

    private fun isConstraintLayoutParamsClass(paramsClass: Class<*>): Boolean {
        var current: Class<*>? = paramsClass
        while (current != null) {
            if (current.name == "androidx.constraintlayout.widget.ConstraintLayout\$LayoutParams" ||
                current.name == "android.support.constraint.ConstraintLayout\$LayoutParams"
            ) {
                return true
            }
            current = current.superclass
        }
        return false
    }

    private fun findExistingAnchor(row: ViewGroup): ReplyTopologyAnchorView? {
        for (index in 0 until row.childCount) {
            val child = row.getChildAt(index)
            if (child is ReplyTopologyAnchorView) return child
        }
        return null
    }

    private fun findAnchorTarget(start: View): AnchorTarget? {
        val geometry = resolveAnchorGeometry(start) ?: return null
        val scope = resolveBindingScope(start, geometry)
        val index = geometry.row.indexOfChild(geometry.referenceView)
        if (index < 0) {
            // 宿主重排了操作行的子节点：作废缓存重算一次，仍失败才按缺失处理。
            anchorGeometries.remove(start)
            val refreshed = resolveAnchorGeometry(start) ?: return null
            val refreshedIndex = refreshed.row.indexOfChild(refreshed.referenceView)
            if (refreshedIndex < 0) return null
            return refreshed.toTarget(refreshedIndex, resolveBindingScope(start, refreshed))
        }
        return geometry.toTarget(index, scope)
    }

    /** 命中缓存时只做 O(层级) 的指针校验，不再触碰任何子树。 */
    private fun resolveAnchorGeometry(start: View): AnchorGeometry? {
        anchorGeometries[start]?.let { cached ->
            if (isGeometryUsable(start, cached)) return cached
            anchorGeometries.remove(start)
        }
        val fresh = buildAnchorGeometry(start) ?: return null
        anchorGeometries[start] = fresh
        return fresh
    }

    private fun isGeometryUsable(start: View, geometry: AnchorGeometry): Boolean {
        if (!start.isAttachedToWindow) return false
        if (!geometry.include.isAttachedToWindow || !geometry.row.isAttachedToWindow) return false
        if (geometry.referenceView.parent !== geometry.row) return false
        if (!isWithin(geometry.row, geometry.include)) return false
        geometry.primaryMessage?.let { if (!isWithin(it, start)) return false }
        geometry.secondaryMessage?.let { if (!isWithin(it, start)) return false }
        return sharesSearchScope(start, geometry.include)
    }

    /** include 仍位于 [findActionInclude] 会检查的祖先范围内，否则缓存不再代表同一棵树。 */
    private fun sharesSearchScope(start: View, include: ViewGroup): Boolean {
        var current: View? = start
        repeat(MAX_PARENT_SEARCH_DEPTH) {
            val node = current ?: return false
            if (node is RecyclerView) return false
            if (node === include || isWithin(include, node)) return true
            val parent = node.parent as? View ?: return false
            if (parent is RecyclerView) return false
            current = parent
        }
        return false
    }

    private fun buildAnchorGeometry(start: View): AnchorGeometry? {
        val include = findActionInclude(start) ?: return null
        val startGroup = start as? ViewGroup
        val primaryMessageId = resolveHostViewId(start, "primary_message")
        val secondaryMessageId = resolveHostViewId(start, "secondary_message")
        // primary/secondary 在 `cmt3_next_experiment3_item_rich_text` 中与根布局一同 inflate，
        // 不是 ViewStub，因此 View 引用可安全缓存；每次绑定重新读取的是它们的可见性。
        val primaryMessage = primaryMessageId.takeIf { it > 0 }?.let {
            startGroup?.findViewById<View>(it)
        }
        val secondaryMessage = secondaryMessageId.takeIf { it > 0 }?.let {
            startGroup?.findViewById<View>(it)
        }
        val moreButtonId = resolveHostViewId(start, "more_button")
        if (moreButtonId > 0) {
            include.findViewById<View>(moreButtonId)?.let { moreButton ->
                val row = moreButton.parent as? ViewGroup
                if (row != null && isWithin(row, include)) {
                    return AnchorGeometry(
                        include = include,
                        row = row,
                        referenceView = moreButton,
                        beforeReference = true,
                        primaryMessage = primaryMessage,
                        secondaryMessage = secondaryMessage
                    )
                }
            }
        }
        val replyButtonId = resolveHostViewId(start, "reply_button")
        if (replyButtonId > 0) {
            include.findViewById<View>(replyButtonId)?.let { replyButton ->
                val row = replyButton.parent as? ViewGroup
                if (row != null && isWithin(row, include)) {
                    return AnchorGeometry(
                        include = include,
                        row = row,
                        referenceView = replyButton,
                        beforeReference = false,
                        primaryMessage = primaryMessage,
                        secondaryMessage = secondaryMessage
                    )
                }
            }
        }
        return null
    }

    /**
     * 逐层向上查找操作行 include。
     *
     * 原实现在每一层都对整棵子树 `findViewById`，而第 k 层的子树完整包含第 k-1 层，重复搜索
     * 使成本随层数近似平方增长。这里保持"由近及远、逐层深度优先"的原有顺序与命中结果，只跳过
     * 上一层已经搜索过、且确认没有命中的那棵子树——跳过它不可能改变结果。
     */
    private fun findActionInclude(start: View): ViewGroup? {
        val id = resolveHostViewId(start, "item_include_actions")
        if (id <= 0) return null
        var current: View? = start
        var searched: View? = null
        repeat(MAX_PARENT_SEARCH_DEPTH) {
            val node = current ?: return null
            if (node is RecyclerView) return null
            if (node.id == id) return node as? ViewGroup
            val group = node as? ViewGroup
            if (group != null) {
                if (searched == null) {
                    (group.findViewById<View>(id) as? ViewGroup)?.let { return it }
                } else {
                    for (index in 0 until group.childCount) {
                        val child = group.childOrNull<View>(index) ?: continue
                        if (child === searched) continue
                        if (child.id == id) {
                            (child as? ViewGroup)?.let { return it }
                            continue
                        }
                        val nested = (child as? ViewGroup)?.findViewById<View>(id) as? ViewGroup
                        if (nested != null) return nested
                    }
                }
            }
            val parent = node.parent as? View ?: return null
            if (parent is RecyclerView) return null
            searched = node
            current = parent
        }
        return null
    }

    /**
     * 判定顺序与原实现逐字一致：先 OWNER，再消息可见性，最后父链兜底。
     * 唯一的变化是 primary/secondary 的 View 引用来自 [AnchorGeometry] 缓存，
     * 可见性仍逐次读取，因此复用的 View 在 PRIMARY/SECONDARY 之间翻转仍能被正确识别。
     */
    private fun resolveBindingScope(
        boundView: View,
        geometry: AnchorGeometry
    ): ReplyTopologyBindingScope {
        val include = geometry.include
        if (isWithin(include, boundView)) return ReplyTopologyBindingScope.OWNER
        val primaryMessageId = resolveHostViewId(boundView, "primary_message")
        val secondaryMessageId = resolveHostViewId(boundView, "secondary_message")
        val boundGroup = boundView as? ViewGroup
        val primaryMessage = geometry.primaryMessage
        val secondaryMessage = geometry.secondaryMessage
        val messageScope = replyTopologyMessageScope(
            primaryPresent = primaryMessage != null,
            primaryVisible = isVisibleWithin(primaryMessage, boundGroup),
            secondaryPresent = secondaryMessage != null,
            secondaryVisible = isVisibleWithin(secondaryMessage, boundGroup)
        )
        if (messageScope != ReplyTopologyBindingScope.UNKNOWN) return messageScope
        var current: View? = boundView
        var depth = 0
        while (depth < MAX_PARENT_SEARCH_DEPTH) {
            when (current?.id) {
                secondaryMessageId -> if (secondaryMessageId > 0) {
                    return ReplyTopologyBindingScope.SECONDARY
                }
                primaryMessageId -> if (primaryMessageId > 0) {
                    return ReplyTopologyBindingScope.PRIMARY
                }
            }
            if (current === include) break
            current = current?.parent as? View ?: break
            depth++
        }
        return ReplyTopologyBindingScope.UNKNOWN
    }

    private fun isVisibleWithin(view: View?, ancestor: ViewGroup?): Boolean {
        if (view == null || ancestor == null) return false
        var current: View? = view
        repeat(MAX_PARENT_SEARCH_DEPTH + 2) {
            val node = current ?: return false
            if (node.visibility != View.VISIBLE) return false
            if (node === ancestor) return true
            current = node.parent as? View
        }
        return false
    }

    private fun isWithin(descendant: View, ancestor: View): Boolean {
        var current: View? = descendant
        repeat(MAX_PARENT_SEARCH_DEPTH + 2) {
            if (current === ancestor) return true
            current = current?.parent as? View ?: return false
        }
        return false
    }

    private fun resolveHostViewId(view: View, name: String): Int {
        hostViewIds[name]?.let { return it }
        val resolved = runCatching {
            view.resources.getIdentifier(name, "id", TARGET_PACKAGE)
        }.getOrDefault(0)
        return hostViewIds.putIfAbsent(name, resolved) ?: resolved
    }

    private fun findActivity(context: Context): Activity? {
        var current: Context? = context
        while (current != null) {
            if (current is Activity) return current
            current = (current as? ContextWrapper)?.baseContext
        }
        return null
    }

    private fun dp(view: View, value: Int): Int =
        (value * view.resources.displayMetrics.density + 0.5f).toInt()

    private fun missing(
        environment: HookEnvironment,
        reason: String
    ): FeatureInstallResult.Skipped {
        environment.reportStatus(CHANNEL_STATUS, reason)
        environment.logError("comment_topology_missing", "[BIL] 回复脉络未安装: $reason")
        return FeatureInstallResult.Skipped(reason)
    }

    companion object {
        const val ID = "comment_topology"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "comment_topology_status"
        private const val MAX_PARENT_SEARCH_DEPTH = 9

        /** 在途补绑回调超过该时长仍未执行时视为已丢失，由下一次绑定重新投递。 */
        private const val SEED_RETRY_STALE_MS = 100L

        private val hostViewIds = ConcurrentHashMap<String, Int>()
        private val commentItemFields = ConcurrentHashMap<Class<*>, Field>()
        private val noCommentItemFieldClasses = ConcurrentHashMap.newKeySet<Class<*>>()
        private val holderItemViewFields = ConcurrentHashMap<Class<*>, Field>()
        private val noHolderItemViewClasses = ConcurrentHashMap.newKeySet<Class<*>>()
        private val bindingRootMethods = ConcurrentHashMap<Class<*>, Method>()
        private val noBindingRootMethodClasses = ConcurrentHashMap.newKeySet<Class<*>>()
        private val bindingRootFields = ConcurrentHashMap<Class<*>, Field>()
        private val noBindingRootFieldClasses = ConcurrentHashMap.newKeySet<Class<*>>()
        private val constraintLayoutParamsAccess =
            ConcurrentHashMap<Class<*>, ConstraintLayoutParamsAccess>()
        private val noConstraintLayoutParamsAccess = ConcurrentHashMap.newKeySet<Class<*>>()
    }

    private class SeedRetryRequest(
        val boundView: WeakReference<View>,
        val token: Any,
        val commentItem: WeakReference<Any>,
        val expectedCommentItemId: Long
    )

    /** 只保存复用中稳定的"位置"；insertIndex 与 scope 一律逐次重算。 */
    private class AnchorGeometry(
        val include: ViewGroup,
        val row: ViewGroup,
        val referenceView: View,
        val beforeReference: Boolean,
        val primaryMessage: View?,
        val secondaryMessage: View?
    ) {
        fun toTarget(referenceIndex: Int, scope: ReplyTopologyBindingScope) = AnchorTarget(
            row = row,
            insertIndex = if (beforeReference) referenceIndex else referenceIndex + 1,
            scope = scope,
            referenceView = referenceView,
            beforeReference = beforeReference
        )
    }

    private data class AnchorTarget(
        val row: ViewGroup,
        val insertIndex: Int,
        val scope: ReplyTopologyBindingScope,
        val referenceView: View,
        val beforeReference: Boolean
    )

    private data class ConstraintNeighbor(
        val view: View,
        val params: ViewGroup.LayoutParams
    )

    private data class ConstraintLayoutParamsAccess(
        val paramsClass: Class<*>,
        val intConstructor: Constructor<*>,
        val startToEnd: Field,
        val endToStart: Field,
        val topToTop: Field,
        val bottomToBottom: Field
    )
}

/** 宿主操作栏内的轻量入口；只保存当前绑定评论的纯数据种子。 */
@SuppressLint("AppCompatCustomView") // 注入宿主进程，不能依赖模块 AppCompat 主题包装。
internal class ReplyTopologyAnchorView(context: Context) : TextView(context) {
    private var seed: ReplyTopologySeed? = null
    private var coordinator: ReplyTopologyCoordinator? = null

    init {
        val localizedText = InjectedUiLocale.messages(context)
        gravity = Gravity.CENTER
        textSize = 12f
        text = localizedText.replyTopologyEntryLabel
        setTextColor(resolveSiblingTextColor() ?: Color.GRAY)
        setPadding(dp(8), 0, dp(8), 0)
        isClickable = true
        isFocusable = true
        contentDescription = localizedText.replyTopologyEntryDescription
        val typedValue = TypedValue()
        if (context.theme.resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless,
                typedValue,
                true
            ) && typedValue.resourceId != 0
        ) {
            setBackgroundResource(typedValue.resourceId)
        }
        setOnClickListener {
            val currentSeed = seed ?: return@setOnClickListener
            val activity = findActivity(context) ?: return@setOnClickListener
            coordinator?.open(activity, currentSeed)
        }
    }

    fun bind(seed: ReplyTopologySeed, coordinator: ReplyTopologyCoordinator) {
        this.seed = seed
        this.coordinator = coordinator
        resolveSiblingTextColor()?.let(::setTextColor)
        visibility = View.VISIBLE
        isEnabled = true
    }

    fun clearBinding() {
        seed = null
        coordinator = null
        visibility = View.GONE
        isEnabled = false
    }

    private fun resolveSiblingTextColor(): Int? {
        val group = parent as? ViewGroup ?: return null
        for (index in 0 until group.childCount) {
            (group.getChildAt(index) as? TextView)?.let { return it.currentTextColor }
        }
        return null
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun findActivity(context: Context): Activity? {
        var current: Context? = context
        while (current != null) {
            if (current is Activity) return current
            current = (current as? ContextWrapper)?.baseContext
        }
        return null
    }
}

/** 单活动会话协调器：顺序分页、资源预算、迟到结果隔离和跨详情页面板恢复。 */
internal class ReplyTopologyCoordinator(
    private val host: ReplyTopologyHostAccess,
    private val environment: HookEnvironment
) {
    private val gate = ReplyTopologySessionGate()
    private val panelController = ReplyTopologyPanelController()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var active: Active? = null
    private var lastOpacity = 0.90f
    private var lastPosition = ReplyTopologyPanelPosition(0.94f, 0.18f)

    fun open(activity: Activity, seed: ReplyTopologySeed) {
        if (Looper.myLooper() !== Looper.getMainLooper()) {
            mainHandler.post { open(activity, seed) }
            return
        }
        closeActive()
        val token = gate.open(seed.key, SystemClock.elapsedRealtime())
        val worker = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "BIL-ReplyTopology").apply { isDaemon = true }
        }
        val state = Active(
            token = token,
            activityRef = WeakReference(activity),
            strings = ReplyTopologyPanelStrings.resolve(activity),
            worker = worker,
            nodes = ArrayList(seed.nodes),
            uniqueRpids = seed.nodes.mapTo(HashSet()) { it.rpid },
            expectedCount = seed.expectedReplyCount,
            nextOffset = "",
            guard = newPagingGuard(extended = false)
        )
        val panel = attachPanel(activity, state) ?: run {
            gate.close(token)
            worker.shutdownNow()
            return
        }
        state.panelSession = panel
        active = state
        updatePanelState(
            state,
            ReplyTopologyPanelState.loading(
                loadedCount(state),
                state.expectedCount,
                state.strings.loadingMessage
            )
        )
        scheduleGraphBuild(state)
        requestNext(state)
    }

    private fun attachPanel(activity: Activity, state: Active): ReplyTopologyPanelSession? {
        val panelRef = AtomicReference<ReplyTopologyPanelSession?>(null)
        val localizedStrings = ReplyTopologyPanelStrings.resolve(activity)
        if (state.strings != localizedStrings) {
            // 系统语言切换后的 Activity 重建不能重放上一语言的具体状态提示；phase 自身
            // 足以让新面板选择当前语言的默认文案，分页等后续事件仍会写入精确提示。
            state.panelState = state.panelState.copy(message = null)
        }
        state.strings = localizedStrings
        val config = ReplyTopologyPanelConfig(
            strings = state.strings,
            initialBackgroundOpacity = lastOpacity,
            initialPosition = lastPosition
        )
        val listener = object : ReplyTopologyPanelListener {
            override fun onNodeSelected(rpid: Long) {
                // 仅记录选中目标（渲染快照携带，保证跨分页/迁移高亮保持）。
                state.selectedRpid = rpid
            }

            override fun onNodeFullTextRequested(anchor: android.view.View, text: CharSequence) {
                // 复用自由复制气泡的完整 UI/动画/防越界/防泄漏链路；失败 fail-open 不影响面板。
                runCatching {
                    HookEntry.showReplyTraceBubble(anchor, text)
                }.onFailure { throwable ->
                    environment.logError(
                        "comment_topology_full_text",
                        "[BIL] 脉络全文气泡弹出失败: $throwable"
                    )
                }
            }

            override fun onRetryRequested() {
                restartLoading(state.token, extended = false)
            }

            override fun onContinueRequested() {
                restartLoading(state.token, extended = true)
            }

            override fun onOpacityCommitted(opacity: Float) {
                lastOpacity = opacity.coerceIn(0.35f, 1f)
            }

            override fun onPositionCommitted(position: ReplyTopologyPanelPosition) {
                lastPosition = position.normalized()
            }

            override fun onClosed(reason: ReplyTopologyPanelCloseReason) {
                // 面板关闭/跨页迁移时收尾由脉络弹出的全文气泡，防孤儿弹窗；幂等。
                runCatching { HookEntry.dismissReplyTraceBubbleIfShowing() }
                val panel = panelRef.get()
                val current = active
                if (current?.token != state.token) return
                if (panel != null && current.panelSession != panel) return
                close(state.token)
            }
        }
        return panelController.attach(activity, config, listener)?.also(panelRef::set)
    }

    private fun requestNext(state: Active) {
        if (active !== state || !gate.accepts(state.token) || state.loadingRequest) return
        val request = gate.beginRequest(state.token) ?: return
        val offset = state.nextOffset.orEmpty()
        state.loadingRequest = true
        // 与构图共用顺序 worker；先用一个栅栏等此前构图结束，再从真实 MOSS 调用前开始计时。
        execute(state.worker) {
            if (!gate.accepts(request)) return@execute
            mainHandler.post {
                if (active !== state || !gate.accepts(request)) return@post
                val timeout = Runnable { onRequestTimeout(state, request) }
                state.timeout?.let { mainHandler.removeCallbacks(it.runnable) }
                state.timeout = PendingTimeout(request, timeout)
                mainHandler.postDelayed(timeout, REQUEST_TIMEOUT_MS)
                execute(state.worker) {
                    if (!gate.accepts(request)) return@execute
                    val call = host.requestPage(state.token.key, offset) { result ->
                        mainHandler.post {
                            state.timeout?.takeIf { it.request == request }?.let { pending ->
                                mainHandler.removeCallbacks(pending.runnable)
                                state.timeout = null
                            }
                            state.currentCall?.takeIf { it.request == request }?.let { pending ->
                                pending.call.cancel()
                                state.currentCall = null
                            }
                            handlePage(state, request, offset, result)
                        }
                    }
                    mainHandler.post {
                        if (active === state && gate.accepts(request)) {
                            state.currentCall?.call?.cancel()
                            state.currentCall = PendingPageCall(request, call)
                        } else {
                            call.cancel()
                        }
                    }
                }
            }
        }
    }

    private fun handlePage(
        state: Active,
        request: ReplyTopologyRequestToken,
        currentOffset: String,
        result: Result<ReplyTopologyHostPage>
    ) {
        if (!gate.completeRequest(request)) return
        if (active !== state) return
        state.loadingRequest = false
        result.fold(
            onSuccess = { page -> handleSuccessfulPage(state, currentOffset, page) },
            onFailure = { throwable -> handleFailure(state, throwable) }
        )
    }

    private fun handleSuccessfulPage(
        state: Active,
        currentOffset: String,
        page: ReplyTopologyHostPage
    ) {
        var newUnique = 0
        page.nodes.forEach { node ->
            if (state.uniqueRpids.size >= HARD_NODE_LIMIT && node.rpid !in state.uniqueRpids) {
                return@forEach
            }
            if (state.uniqueRpids.add(node.rpid)) {
                state.nodes += node
                newUnique++
            } else if (state.duplicateMergeRpids.add(node.rpid)) {
                // 每个 rpid 最多保留一个后续完整快照，供构图器补全 mapper 的轻量预览。
                state.nodes += node
            }
        }
        page.expectedReplyCount?.let { state.expectedCount = it }
        scheduleGraphBuild(state)
        val decision = state.guard.onPage(
            currentOffset = currentOffset,
            nextOffset = page.nextOffset,
            newUniqueNodes = newUnique,
            totalUniqueNodes = state.uniqueRpids.size,
            nowMs = SystemClock.elapsedRealtime()
        )
        val loaded = loadedCount(state)
        when (decision) {
            is ReplyTopologyPagingDecision.LoadNext -> {
                state.nextOffset = decision.offset
                updatePanelState(
                    state,
                    ReplyTopologyPanelState.loading(
                        loadedCount = loaded,
                        expectedCount = state.expectedCount
                    )
                )
                requestNext(state)
            }
            is ReplyTopologyPagingDecision.Stop -> {
                state.nextOffset = page.nextOffset
                if (decision.complete) {
                    state.complete = true
                    updatePanelState(
                        state,
                        ReplyTopologyPanelState(
                            phase = ReplyTopologyPanelPhase.COMPLETE,
                            loadedCount = loaded,
                            expectedCount = state.expectedCount ?: loaded,
                            message = state.strings.completeMessage
                        )
                    )
                } else if (decision.reason in RESOURCE_STOP_REASONS) {
                    updatePanelState(
                        state,
                        ReplyTopologyPanelState.resourceLimit(
                            loadedCount = loaded,
                            expectedCount = state.expectedCount,
                            message = state.strings.resourcePausedMessage
                        )
                    )
                } else {
                    updatePanelState(
                        state,
                        ReplyTopologyPanelState.partial(
                            loadedCount = loaded,
                            expectedCount = state.expectedCount,
                            message = stopReasonMessage(state.strings, decision.reason),
                            canRetry = decision.reason == ReplyTopologyPagingStopReason.INVALID_PAGE
                        )
                    )
                }
            }
        }
    }

    private fun handleFailure(state: Active, throwable: Throwable) {
        if (!gate.accepts(state.token) || active !== state) return
        environment.logError(
            "comment_topology_request",
            "[BIL] 回复脉络分页失败，已保留当前结果: $throwable"
        )
        val loaded = loadedCount(state)
        updatePanelState(
            state,
            if (loaded > 0) {
                ReplyTopologyPanelState.partial(
                    loadedCount = loaded,
                    expectedCount = state.expectedCount,
                    message = state.strings.networkPartialMessage,
                    canRetry = true
                )
            } else {
                ReplyTopologyPanelState.error(state.strings.loadErrorMessage, canRetry = true)
            }
        )
    }

    private fun onRequestTimeout(state: Active, request: ReplyTopologyRequestToken) {
        if (!gate.completeRequest(request) || active !== state) return
        state.timeout = null
        state.currentCall?.takeIf { it.request == request }?.let { pending ->
            pending.call.cancel()
            state.currentCall = null
        }
        state.loadingRequest = false
        handleFailure(state, IllegalStateException("DetailList request timeout"))
    }

    private fun restartLoading(token: ReplyTopologySessionToken, extended: Boolean) {
        val state = active?.takeIf { it.token == token } ?: return
        if (state.complete || state.loadingRequest || state.nextOffset == null) return
        state.guard = newPagingGuard(extended)
        updatePanelState(
            state,
            ReplyTopologyPanelState.loading(
                loadedCount(state),
                state.expectedCount
            )
        )
        requestNext(state)
    }

    private fun scheduleGraphBuild(state: Active) {
        val revision = ++state.graphRevision
        val snapshots = state.nodes.toList()
        execute(state.worker) {
            val result = runCatching { ReplyTopologyGraphBuilder.build(state.token.key, snapshots) }
            mainHandler.post {
                if (active !== state || !gate.accepts(state.token) || revision != state.graphRevision) {
                    return@post
                }
                result.fold(
                    onSuccess = { graph ->
                        state.graph = graph
                        state.panelSession?.let { panel ->
                            panelController.submit(
                                panel,
                                ReplyTopologyRenderSnapshot(graph, state.selectedRpid)
                            )
                        }
                    },
                    onFailure = { throwable ->
                        environment.logError(
                            "comment_topology_graph",
                            "[BIL] 回复脉络构图失败，已保留当前面板: $throwable"
                        )
                    }
                )
            }
        }
    }

    private fun updatePanelState(state: Active, panelState: ReplyTopologyPanelState) {
        if (!gate.accepts(state.token) || active !== state) return
        state.panelState = panelState
        state.panelSession?.let { panelController.updateState(it, panelState) }
    }

    private fun close(token: ReplyTopologySessionToken) {
        val state = active?.takeIf { it.token == token } ?: return
        active = null
        gate.close(token)
        releaseState(state, ReplyTopologyPanelCloseReason.PROGRAMMATIC)
    }

    private fun closeActive() {
        val state = active ?: return
        active = null
        gate.close(state.token)
        releaseState(state, ReplyTopologyPanelCloseReason.REPLACED)
    }

    private fun releaseState(state: Active, reason: ReplyTopologyPanelCloseReason) {
        state.timeout?.let { mainHandler.removeCallbacks(it.runnable) }
        state.timeout = null
        state.currentCall?.call?.cancel()
        state.currentCall = null
        state.worker.shutdownNow()
        val panel = state.panelSession
        state.panelSession = null
        panel?.let { panelController.detach(it, reason) }
        state.activityRef.clear()
        state.nodes.clear()
        state.uniqueRpids.clear()
        state.duplicateMergeRpids.clear()
        state.graph = null
    }

    private fun execute(worker: ExecutorService, action: () -> Unit) {
        try {
            worker.execute(action)
        } catch (_: RejectedExecutionException) {
            // 面板已关闭；宿主异步回调只需静默丢弃。
        }
    }

    private fun newPagingGuard(extended: Boolean) = ReplyTopologyPagingGuard(
        startedAtMs = SystemClock.elapsedRealtime(),
        budget = if (extended) EXTENDED_BUDGET else AUTO_BUDGET
    )

    private fun loadedCount(state: Active): Int =
        (state.uniqueRpids.size - 1).coerceAtLeast(0)

    private fun stopReasonMessage(
        strings: ReplyTopologyPanelStrings,
        reason: ReplyTopologyPagingStopReason
    ): String = when (reason) {
        ReplyTopologyPagingStopReason.REPEATED_OFFSET -> strings.repeatedOffsetMessage
        ReplyTopologyPagingStopReason.NO_PROGRESS -> strings.noProgressMessage
        ReplyTopologyPagingStopReason.INVALID_PAGE -> strings.invalidPageMessage
        else -> strings.partialRepliesMessage
    }

    private data class Active(
        val token: ReplyTopologySessionToken,
        var activityRef: WeakReference<Activity>,
        var strings: ReplyTopologyPanelStrings,
        val worker: ExecutorService,
        val nodes: ArrayList<com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology.ReplyTopologyNodeSnapshot>,
        val uniqueRpids: HashSet<Long>,
        val duplicateMergeRpids: HashSet<Long> = HashSet(),
        var expectedCount: Int?,
        var nextOffset: String?,
        var guard: ReplyTopologyPagingGuard,
        var panelSession: ReplyTopologyPanelSession? = null,
        var graph: ReplyTopologyGraph? = null,
        var panelState: ReplyTopologyPanelState = ReplyTopologyPanelState.loading(0),
        var selectedRpid: Long? = null,
        var loadingRequest: Boolean = false,
        var complete: Boolean = false,

        /** 当前未确认定位的目标节点；null=无未确认定位。防抖与可重定向以此判定。 */
        var graphRevision: Long = 0L,
        var timeout: PendingTimeout? = null,
        var currentCall: PendingPageCall? = null
    )

    private data class PendingTimeout(
        val request: ReplyTopologyRequestToken,
        val runnable: Runnable
    )

    private data class PendingPageCall(
        val request: ReplyTopologyRequestToken,
        val call: ReplyTopologyPageCall
    )

    companion object {
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val REQUEST_TIMEOUT_MS = 15_000L
        private const val HARD_NODE_LIMIT = 5_000
        private val AUTO_BUDGET = ReplyTopologyPagingBudget(
            maxPages = 60,
            maxUniqueNodes = 1_500,
            maxElapsedMs = 12_000L,
            maxConsecutiveNoProgressPages = 2
        )
        private val EXTENDED_BUDGET = ReplyTopologyPagingBudget(
            maxPages = 200,
            maxUniqueNodes = HARD_NODE_LIMIT,
            maxElapsedMs = 60_000L,
            maxConsecutiveNoProgressPages = 3
        )
        private val RESOURCE_STOP_REASONS = setOf(
            ReplyTopologyPagingStopReason.PAGE_LIMIT,
            ReplyTopologyPagingStopReason.NODE_LIMIT,
            ReplyTopologyPagingStopReason.TIME_LIMIT
        )
    }
}
