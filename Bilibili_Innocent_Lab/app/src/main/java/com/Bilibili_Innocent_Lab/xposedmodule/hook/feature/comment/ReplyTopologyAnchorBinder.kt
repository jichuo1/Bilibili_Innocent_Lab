package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.comment

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.ReplyTopologyBindingAction
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.ReplyTopologyBindingScope
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.ReplyTopologyHostAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.ReplyTopologySeed
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.ReplyTopologySeedStore
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.planConstrainedAnchorInsertion
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.replyTopologyBindingAction
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.replyTopologyMessageScope
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.InjectedUiLocale
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isSubclassOf
import io.github.jichuo1.meguviz.android.extensions.childOrNull
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * 回复脉络入口按钮的树定位、几何缓存、主/子楼层范围判定、补绑调度与 ConstraintLayout 注入。
 *
 * 仅使用 WeakHashMap / WeakReference 保存 View 实例引用，Class/Method/Field 元数据静态缓存。
 */
internal class ReplyTopologyAnchorBinder {

    private val bindingTokens = WeakHashMap<ViewGroup, Any>()
    private val anchorGeometries = WeakHashMap<View, AnchorGeometry>()
    private val pendingSeedRetries = WeakHashMap<ViewGroup, SeedRetryRequest>()
    private val pendingSeedRetryPostedAtMs = WeakHashMap<ViewGroup, Long>()

    fun findCommentItem(
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
        val field = KavaMemberLookup.firstFieldOrNull(
            owner.javaClass,
            includeSuperclasses = true,
            makeAccessible = true
        ) { candidate -> candidate.type isSubclassOf commentItemClass }
        if (field == null) {
            noCommentItemFieldClasses += owner.javaClass
            return null
        }
        commentItemFields[owner.javaClass] = field
        return runCatching { field.get(owner) }.getOrNull()
    }

    fun findBindingRoot(instance: Any?, args: Array<out Any?>): View? {
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
        if (owner == null) return null
        if (owner is View) return owner
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

    fun bindAnchor(
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
        boundView.postOnAnimation(HostThreadGuard.runnable("comment.topology.seed_retry") {
            val row = rowRef.get() ?: return@runnable
            runSeedRetry(row, seedStore, host, coordinator, environment)
        })
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
                val view = ReplyTopologyAnchorView(target.row.context)
                val added = runCatching {
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
        val neighbor = neighbors.singleOrNull()
        val referenceConstraint = referenceToNeighbor.getInt(referenceParams)
        val linkPlan = planConstrainedAnchorInsertion(
            neighborCount = neighbors.size,
            referenceInverseConstraint = referenceConstraint,
            neighborViewId = neighbor?.view?.id
        )

        val params = access.intConstructor.newInstance(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(target.row, 30)
        ) as? ViewGroup.LayoutParams ?: error("constraint-layout-params-type")
        view.id = View.generateViewId()
        access.topToTop.setInt(params, referenceId)
        access.bottomToBottom.setInt(params, referenceId)
        if (target.beforeReference) {
            access.endToStart.setInt(params, referenceId)
            if (linkPlan.updateReference && neighbor != null) {
                access.startToEnd.setInt(params, neighbor.view.id)
            }
        } else {
            access.startToEnd.setInt(params, referenceId)
            if (linkPlan.updateReference && neighbor != null) {
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

        val previousNeighborConstraint = neighbor?.let { neighborToReference.getInt(it.params) } ?: View.NO_ID
        try {
            target.row.addView(view, index, params)
            if (linkPlan.updateNeighbor && neighbor != null) {
                neighborToReference.setInt(neighbor.params, view.id)
                neighbor.view.layoutParams = neighbor.params
            }
            if (linkPlan.updateReference) {
                referenceToNeighbor.setInt(referenceParams, view.id)
                target.referenceView.layoutParams = referenceParams
            }
        } catch (throwable: Throwable) {
            if (linkPlan.updateNeighbor && neighbor != null) runCatching {
                neighborToReference.setInt(neighbor.params, previousNeighborConstraint)
                neighbor.view.layoutParams = neighbor.params
            }
            if (linkPlan.updateReference) runCatching {
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
        val row = geometry.row.get() ?: return null
        val referenceView = geometry.referenceView.get() ?: return null
        val index = row.indexOfChild(referenceView)
        if (index < 0) {
            anchorGeometries.remove(start)
            val refreshed = resolveAnchorGeometry(start) ?: return null
            val refreshedRow = refreshed.row.get() ?: return null
            val refreshedRef = refreshed.referenceView.get() ?: return null
            val refreshedIndex = refreshedRow.indexOfChild(refreshedRef)
            if (refreshedIndex < 0) return null
            return refreshed.toTarget(refreshedRow, refreshedRef, refreshedIndex, resolveBindingScope(start, refreshed))
        }
        return geometry.toTarget(row, referenceView, index, scope)
    }

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
        val include = geometry.include.get() ?: return false
        val row = geometry.row.get() ?: return false
        val referenceView = geometry.referenceView.get() ?: return false
        if (!include.isAttachedToWindow || !row.isAttachedToWindow) return false
        if (referenceView.parent !== row) return false
        if (!isWithin(row, include)) return false
        geometry.primaryMessage?.let { ref ->
            val primary = ref.get() ?: return false
            if (!isWithin(primary, start)) return false
        }
        geometry.secondaryMessage?.let { ref ->
            val secondary = ref.get() ?: return false
            if (!isWithin(secondary, start)) return false
        }
        return sharesSearchScope(start, include)
    }

    private fun sharesSearchScope(start: View, include: ViewGroup): Boolean {
        var current: View? = start
        repeat(MAX_PARENT_SEARCH_DEPTH) {
            val node = current ?: return false
            if (isRecyclerViewInstance(node)) return false
            if (node === include || isWithin(include, node)) return true
            val parent = node.parent as? View ?: return false
            if (isRecyclerViewInstance(parent)) return false
            current = parent
        }
        return false
    }

    internal fun isRecyclerViewInstance(view: View?): Boolean {
        var current: Class<*>? = view?.javaClass
        repeat(MAX_SUPERCLASS_DEPTH) {
            val type = current ?: return false
            if (type.name == RECYCLER_VIEW_CLASS) return true
            current = type.superclass
        }
        return false
    }

    private fun buildAnchorGeometry(start: View): AnchorGeometry? {
        val include = findActionInclude(start) ?: return null
        val startGroup = start as? ViewGroup
        val primaryMessageId = resolveHostViewId(start, "primary_message")
        val secondaryMessageId = resolveHostViewId(start, "secondary_message")
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
                        include = WeakReference(include),
                        row = WeakReference(row),
                        referenceView = WeakReference(moreButton),
                        beforeReference = true,
                        primaryMessage = primaryMessage?.let(::WeakReference),
                        secondaryMessage = secondaryMessage?.let(::WeakReference)
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
                        include = WeakReference(include),
                        row = WeakReference(row),
                        referenceView = WeakReference(replyButton),
                        beforeReference = false,
                        primaryMessage = primaryMessage?.let(::WeakReference),
                        secondaryMessage = secondaryMessage?.let(::WeakReference)
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
        var searched: View? = null
        repeat(MAX_PARENT_SEARCH_DEPTH) {
            val node = current ?: return null
            if (isRecyclerViewInstance(node)) return null
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
            if (isRecyclerViewInstance(parent)) return null
            searched = node
            current = parent
        }
        return null
    }

    private fun resolveBindingScope(
        boundView: View,
        geometry: AnchorGeometry
    ): ReplyTopologyBindingScope {
        val include = geometry.include.get() ?: return ReplyTopologyBindingScope.UNKNOWN
        if (isWithin(include, boundView)) return ReplyTopologyBindingScope.OWNER
        val primaryMessageId = resolveHostViewId(boundView, "primary_message")
        val secondaryMessageId = resolveHostViewId(boundView, "secondary_message")
        val boundGroup = boundView as? ViewGroup
        val primaryMessage = geometry.primaryMessage?.get()
        val secondaryMessage = geometry.secondaryMessage?.get()
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

    private fun dp(view: View, value: Int): Int =
        (value * view.resources.displayMetrics.density + 0.5f).toInt()

    private class SeedRetryRequest(
        val boundView: WeakReference<View>,
        val token: Any,
        val commentItem: WeakReference<Any>,
        val expectedCommentItemId: Long
    )

    private class AnchorGeometry(
        val include: WeakReference<ViewGroup>,
        val row: WeakReference<ViewGroup>,
        val referenceView: WeakReference<View>,
        val beforeReference: Boolean,
        val primaryMessage: WeakReference<View>?,
        val secondaryMessage: WeakReference<View>?
    ) {
        fun toTarget(
            row: ViewGroup,
            referenceView: View,
            referenceIndex: Int,
            scope: ReplyTopologyBindingScope
        ) = AnchorTarget(
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

    companion object {
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val RECYCLER_VIEW_CLASS = "androidx.recyclerview.widget.RecyclerView"
        private const val MAX_PARENT_SEARCH_DEPTH = 9
        private const val MAX_SUPERCLASS_DEPTH = 12
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
        isSingleLine = true
        maxLines = 1
        text = localizedText.replyTopologyEntryLabel
        setTextColor(resolveSiblingTextColor() ?: Color.GRAY)
        setPadding(dp(6), 0, dp(6), 0)
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
            HostThreadGuard.run("comment_topology.entry_click") {
                val currentSeed = seed ?: return@run
                val activity = findActivity(context) ?: return@run
                coordinator?.open(activity, currentSeed, this@ReplyTopologyAnchorView)
            }
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
