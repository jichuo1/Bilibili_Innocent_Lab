package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
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
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
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
                View::class.java.isAssignableFrom(candidate.returnType)
        }.isNotEmpty() || KavaMemberLookup.declaredFields(parameterType) { candidate ->
            View::class.java.isAssignableFrom(candidate.type)
        }.isNotEmpty()
    }

    val bindingTypes = cachedMethod.parameterTypes.filter(::looksLikeViewBinding)
    if (bindingTypes.size != 1) return listOf(cachedPoint)
    val bindingType = bindingTypes.single()

    return buildList {
        add(cachedPoint)
        declaredMethods.forEach { method ->
            if (Modifier.isStatic(method.modifiers) || method.isSynthetic || method.isBridge) {
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
        ) { candidate -> commentItemClass.isAssignableFrom(candidate.type) }.firstOrNull()
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
                if (field != null && View::class.java.isAssignableFrom(field.type)) {
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
                        View::class.java.isAssignableFrom(candidate.returnType)
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
        )?.takeIf { candidate -> View::class.java.isAssignableFrom(candidate.type) }
        val field = conventional ?: KavaMemberLookup.fields(
            declaringClass = owner.javaClass,
            includeSuperclasses = true,
            makeAccessible = true
        ) { candidate -> View::class.java.isAssignableFrom(candidate.type) }
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
        val boundViewRef = WeakReference(boundView)
        val targetRowRef = WeakReference(targetRow)
        val commentItemRef = WeakReference(commentItem)
        boundView.postOnAnimation {
            val currentView = boundViewRef.get() ?: return@postOnAnimation
            val currentRow = targetRowRef.get() ?: return@postOnAnimation
            if (!currentRow.isAttachedToWindow) return@postOnAnimation
            val isCurrent = synchronized(bindingTokens) {
                bindingTokens[currentRow] === token
            }
            if (!isCurrent) return@postOnAnimation
            val currentItem = commentItemRef.get() ?: return@postOnAnimation
            if (host.readCommentItemId(currentItem) != expectedCommentItemId) {
                return@postOnAnimation
            }
            val refreshedTarget = findAnchorTarget(currentView) ?: return@postOnAnimation
            if (refreshedTarget.row !== currentRow ||
                (refreshedTarget.scope != ReplyTopologyBindingScope.OWNER &&
                    refreshedTarget.scope != ReplyTopologyBindingScope.PRIMARY)
            ) return@postOnAnimation
            val refreshedSeed = seedStore.get(currentItem, expectedCommentItemId)
                ?: return@postOnAnimation
            showAnchor(refreshedTarget, refreshedSeed, coordinator, environment)
        }
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
        findActivity(target.row.context)?.let { activity ->
            coordinator.onCommentBound(activity, seed.key)
        }
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
        val include = findActionInclude(start) ?: return null
        val scope = resolveBindingScope(start, include)
        val moreButtonId = resolveHostViewId(start, "more_button")
        if (moreButtonId > 0) {
            include.findViewById<View>(moreButtonId)?.let { moreButton ->
                val row = moreButton.parent as? ViewGroup
                if (row != null && isWithin(row, include)) {
                    return AnchorTarget(
                        row = row,
                        insertIndex = row.indexOfChild(moreButton),
                        scope = scope,
                        referenceView = moreButton,
                        beforeReference = true
                    )
                }
            }
        }
        val replyButtonId = resolveHostViewId(start, "reply_button")
        if (replyButtonId > 0) {
            include.findViewById<View>(replyButtonId)?.let { replyButton ->
                val row = replyButton.parent as? ViewGroup
                if (row != null && isWithin(row, include)) {
                    return AnchorTarget(
                        row = row,
                        insertIndex = row.indexOfChild(replyButton) + 1,
                        scope = scope,
                        referenceView = replyButton,
                        beforeReference = false
                    )
                }
            }
        }
        return null
    }

    private fun findActionInclude(start: View): ViewGroup? {
        val id = resolveHostViewId(start, "item_include_actions")
        if (id <= 0) return null
        var current: View? = start
        repeat(MAX_PARENT_SEARCH_DEPTH) {
            if (current is RecyclerView) return null
            val direct = if (current?.id == id) current as? ViewGroup else null
            if (direct != null) return direct
            val nested = (current as? ViewGroup)?.findViewById<View>(id) as? ViewGroup
            if (nested != null) return nested
            val parent = current?.parent as? View ?: return null
            if (parent is RecyclerView) return null
            current = parent
        }
        return null
    }

    private fun resolveBindingScope(
        boundView: View,
        include: ViewGroup
    ): ReplyTopologyBindingScope {
        if (isWithin(include, boundView)) return ReplyTopologyBindingScope.OWNER
        val primaryMessageId = resolveHostViewId(boundView, "primary_message")
        val secondaryMessageId = resolveHostViewId(boundView, "secondary_message")
        val boundGroup = boundView as? ViewGroup
        val primaryMessage = primaryMessageId.takeIf { it > 0 }?.let { id ->
            boundGroup?.findViewById<View>(id)
        }
        val secondaryMessage = secondaryMessageId.takeIf { it > 0 }?.let { id ->
            boundGroup?.findViewById<View>(id)
        }
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

    /** 只有同一 oid/type/root 的评论真正绑定到新页面后才迁移面板，避免附着到无关 Activity。 */
    fun onCommentBound(activity: Activity, key: ReplyTopologyThreadKey) {
        if (Looper.myLooper() !== Looper.getMainLooper()) {
            mainHandler.post { onCommentBound(activity, key) }
            return
        }
        val current = active ?: return
        if (!gate.accepts(current.token) || !current.pendingRebind || current.token.key != key) return
        if (SystemClock.elapsedRealtime() > current.rebindDeadlineMs) {
            finishRebindWindow(current)
            return
        }
        if (activity.packageName != TARGET_PACKAGE) return
        // 源页面可能在启动路由后再次 bind 同一根评论，不能把它误认为目标详情页。
        // 同 Activity 导航时面板仍在原 decor，等待窗口结束后恢复稳态即可。
        if (current.activityRef.get() === activity) return
        current.rebindInProgress = true
        val panel = attachPanel(activity, current)
        if (panel == null) {
            current.rebindInProgress = false
            return
        }
        current.activityRef = WeakReference(activity)
        current.panelSession = panel
        current.rebindRunnable?.let(mainHandler::removeCallbacks)
        current.rebindRunnable = null
        current.pendingRebind = false
        current.detachedWhileRebind = false
        current.rebindInProgress = false
        current.graph?.let {
            panelController.submit(panel, ReplyTopologyRenderSnapshot(it, current.selectedRpid))
        }
        panelController.updateState(panel, current.panelState)
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
                locateReply(state.token, rpid)
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
                val panel = panelRef.get()
                val current = active
                if (current?.token != state.token) return
                if (current.rebindInProgress && reason == ReplyTopologyPanelCloseReason.REPLACED) return
                if (current.pendingRebind && reason == ReplyTopologyPanelCloseReason.HOST_DETACHED) {
                    current.detachedWhileRebind = true
                    if (panel != null && current.panelSession == panel) current.panelSession = null
                    return
                }
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

    private fun locateReply(token: ReplyTopologySessionToken, rpid: Long) {
        val state = active?.takeIf { it.token == token } ?: return
        if (state.pendingRebind) return
        val activity = state.activityRef.get() ?: return
        state.selectedRpid = rpid
        state.panelSession?.let { panelController.selectAndReveal(it, rpid) }
        updatePanelState(
            state,
            ReplyTopologyPanelState.locating(
                loadedCount = loadedCount(state),
                message = state.strings.locatingMessage
            )
        )
        val uri = Uri.Builder()
            .scheme("bilibili")
            .authority("comment3")
            .appendPath("detail")
            .appendPath(token.key.oid.toString())
            .appendPath(token.key.type.toString())
            .appendPath(token.key.rootRpid.toString())
            .appendQueryParameter("rp_id", rpid.toString())
            .build()
        state.pendingRebind = true
        state.detachedWhileRebind = false
        state.rebindDeadlineMs = SystemClock.elapsedRealtime() + REBIND_WINDOW_MS
        val launched = runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(TARGET_PACKAGE))
        }.fold(
            onSuccess = { true },
            onFailure = { throwable ->
                state.pendingRebind = false
                environment.logError(
                    "comment_topology_route",
                    "[BIL] 回复定位失败: $throwable"
                )
                restoreSteadyPanelState(state)
                false
            }
        )
        if (!launched) return
        val rebindTimeout = Runnable {
            val current = active
            if (current !== state || !current.pendingRebind) return@Runnable
            if (SystemClock.elapsedRealtime() >= current.rebindDeadlineMs) {
                finishRebindWindow(current)
            }
        }
        state.rebindRunnable = rebindTimeout
        mainHandler.postDelayed(rebindTimeout, REBIND_WINDOW_MS)
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

    private fun finishRebindWindow(state: Active) {
        if (active !== state || !state.pendingRebind) return
        state.rebindRunnable?.let(mainHandler::removeCallbacks)
        state.rebindRunnable = null
        state.pendingRebind = false
        val panel = state.panelSession
        if (state.detachedWhileRebind || panel == null || !panelController.isAttached(panel)) {
            close(state.token)
        } else {
            restoreSteadyPanelState(state)
        }
    }

    private fun restoreSteadyPanelState(state: Active) {
        val loaded = loadedCount(state)
        updatePanelState(
            state,
            when {
                state.complete -> ReplyTopologyPanelState(
                    ReplyTopologyPanelPhase.COMPLETE,
                    loaded,
                    state.expectedCount ?: loaded
                )
                state.loadingRequest -> ReplyTopologyPanelState.loading(loaded, state.expectedCount)
                else -> ReplyTopologyPanelState.partial(
                    loaded,
                    state.expectedCount,
                    canRetry = state.nextOffset != null
                )
            }
        )
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
        state.rebindRunnable?.let(mainHandler::removeCallbacks)
        state.rebindRunnable = null
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
        var pendingRebind: Boolean = false,
        var rebindDeadlineMs: Long = 0L,
        var rebindInProgress: Boolean = false,
        var detachedWhileRebind: Boolean = false,
        var graphRevision: Long = 0L,
        var timeout: PendingTimeout? = null,
        var rebindRunnable: Runnable? = null,
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
        private const val REBIND_WINDOW_MS = 5_000L
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
