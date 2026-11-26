package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.freecopy

import android.os.MessageQueue
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentBindBudget
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentBindProbe
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentBindQueue
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernHookParam
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isSubclassOf
import io.github.jichuo1.meguviz.android.extensions.childOrNull
import io.github.jichuo1.meguviz.android.extensions.textToString
import java.lang.reflect.Field
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern

/**
 * 评论/简介绑定队列、分帧预算 drain、RecyclerView 滚动静默与弱引用根登记。
 */
internal object FreeCopyBindScheduler {
    @Volatile
    var hostRecyclerViewClass: Class<*>? = null

    private val scrollingRecyclerViews = WeakHashMap<View, Boolean>()
    private val scrollingRecyclerViewsLock = Any()

    @Volatile
    private var rvScrolling: Boolean = false

    @Volatile
    var rvIdleSinceMs: Long = 0L

    private val COMMENT_DATE_PATTERN = Pattern.compile("[0-9]{1,2}月[0-9]{1,2}日")

    val commentBindClock: CommentBindProbe.Clock = CommentBindProbe.Clock { System.nanoTime() }
    val commentBindProbe: CommentBindProbe = CommentBindProbe(commentBindClock)
    private val commentBindBudgetPolicy = CommentBindBudget.Policy(
        budgetNs = 2_000_000L,
        starvationLimitNs = 500_000_000L
    )

    private val commentBindingGenerationSerial = AtomicLong(0L)
    private val latestCommentBindingGeneration = WeakHashMap<View, Long>()
    private val pendingCommentBinds = CommentBindQueue<PendingCommentBind>()
    private val pendingBindLock = Any()

    @Volatile
    private var bindDrainScheduled: Boolean = false

    private val commentBindRetryRunnable: Runnable =
        HostThreadGuard.runnable("free_copy.bind_drain_retry") { drainCommentBinds() }

    /** 评论 itemView 根 -> 绑定状态引用（弱引用 key，回收自动清理）。 */
    val commentRootRefs = WeakHashMap<View, AtomicReference<CommentRootState>>()
    val commentRootLock = Any()

    @Volatile
    var descStealInProgress: Boolean = false

    @Volatile
    var commentStealInProgress: Boolean = false

    @Volatile
    private var mListenerInfoField: Field? = null

    @Volatile
    private var mOnLongClickListenerField: Field? = null

    @Volatile
    private var getListenerInfoMethod: Method? = null

    @Volatile
    private var cHolderItemViewField: Field? = null

    val cHandlerViewFieldByClass = ConcurrentHashMap<Class<*>, Field>()
    private val cCommentItemArgIndexByMethod = ConcurrentHashMap<Member, Int>()
    private val cHandlerCommentItemFieldByClass = ConcurrentHashMap<Class<*>, Field>()
    private val cViewBindingArgIndexByMethod = ConcurrentHashMap<Member, Int>()
    private val cBindingGetRootMethodByClass = ConcurrentHashMap<Class<*>, Method>()
    private val cBindingRootFieldByClass = ConcurrentHashMap<Class<*>, Field>()

    fun updateRecyclerViewScrolling(view: View, scrolling: Boolean): Boolean {
        synchronized(scrollingRecyclerViewsLock) {
            val wasScrolling = rvScrolling
            if (scrolling) scrollingRecyclerViews[view] = true
            else scrollingRecyclerViews.remove(view)
            rvScrolling = scrollingRecyclerViews.isNotEmpty()
            return wasScrolling && !rvScrolling
        }
    }

    fun isAnyRecyclerViewScrolling(): Boolean {
        synchronized(scrollingRecyclerViewsLock) {
            val iterator = scrollingRecyclerViews.keys.iterator()
            while (iterator.hasNext()) {
                if (!iterator.next().isAttachedToWindow) iterator.remove()
            }
            rvScrolling = scrollingRecyclerViews.isNotEmpty()
            return rvScrolling
        }
    }

    fun isRegisteredCommentTreeMember(view: View): Boolean =
        synchronized(commentRootLock) {
            var current: View? = view
            while (current != null) {
                if (commentRootRefs.containsKey(current)) return@synchronized true
                current = current.parent as? View
            }
            false
        }

    fun registerCommentRoot(
        view: View,
        rawText: String?,
        checkReply: Boolean,
        rawTrusted: Boolean = false,
        commentItem: Any? = null,
        generation: Long = 0L
    ) {
        val state = CommentRootState(rawText, checkReply, rawTrusted, commentItem, generation)
        synchronized(commentRootLock) {
            if (generation > 0L) {
                val latest = latestCommentBindingGeneration[view]
                if (latest != null && latest != generation) return
                latestCommentBindingGeneration[view] = generation
            } else if ((commentRootRefs[view]?.get()?.generation ?: 0L) > 0L) {
                return
            }
            commentRootRefs[view]?.set(state) ?: run {
                commentRootRefs[view] = AtomicReference(state)
            }
        }
    }

    fun unregisterCommentRoot(
        view: View,
        expectedGeneration: Long? = null,
        preserveGeneration: Boolean = false
    ) {
        var removed = false
        synchronized(commentRootLock) {
            if (expectedGeneration != null &&
                latestCommentBindingGeneration[view] != expectedGeneration
            ) return@synchronized
            commentRootRefs.remove(view)
            if (!preserveGeneration) latestCommentBindingGeneration.remove(view)
            removed = true
        }
        if (!removed) return
        if (FreeCopyGestureState.commentTouchRootRef?.get() === view) {
            FreeCopyGestureState.clearCommentTouchSession(
                resetHandled = !FreeCopyGestureState.commentLongPressHandled
            )
        }
    }

    fun scheduleCommentBindRetry(delayMs: Long) {
        val hasPending = synchronized(pendingBindLock) { pendingCommentBinds.size > 0 }
        if (!hasPending) {
            bindDrainScheduled = false
            FreeCopyRuntimeConfig.mainHandlerRef?.removeCallbacks(commentBindRetryRunnable)
            return
        }
        val handler = FreeCopyRuntimeConfig.mainHandlerOrNull()
        if (handler == null) {
            bindDrainScheduled = false
            return
        }
        bindDrainScheduled = true
        handler.removeCallbacks(commentBindRetryRunnable)
        handler.postDelayed(commentBindRetryRunnable, delayMs.coerceAtLeast(0L))
    }

    fun scheduleCommentBindIdle() {
        val handler = FreeCopyRuntimeConfig.mainHandlerOrNull()
        if (handler == null) {
            bindDrainScheduled = false
            return
        }
        bindDrainScheduled = true
        handler.looper.queue.addIdleHandler(
            object : MessageQueue.IdleHandler {
                override fun queueIdle(): Boolean {
                    HostThreadGuard.run("free_copy.bind_drain_idle") { drainCommentBinds() }
                    return false
                }
            }
        )
    }

    fun scheduleCommentBind(
        view: View,
        rawText: String?,
        checkReply: Boolean,
        rawTrusted: Boolean = false,
        commentItem: Any? = null
    ) {
        if (!FreeCopyRuntimeConfig.runtimeCommentFreeCopyEnabled) return
        val generation = commentBindingGenerationSerial.incrementAndGet()
        synchronized(commentRootLock) {
            latestCommentBindingGeneration[view] = generation
        }
        val modelConfirmed = !checkReply || rawText?.let { it.length in 2..3000 } == true
        if (modelConfirmed) {
            registerCommentRoot(view, rawText, checkReply, rawTrusted, commentItem, generation)
        } else {
            unregisterCommentRoot(view, expectedGeneration = generation, preserveGeneration = true)
        }
        val enqueuedAtNanos = commentBindClock.nanoTime()
        var replacedBinds = 0
        var overflowBinds = 0
        var queuedAfter = 0
        synchronized(pendingBindLock) {
            val queued = pendingCommentBinds.enqueue(
                CommentBindQueue.Entry(
                    key = view,
                    generation = generation,
                    enqueuedAtNanos = enqueuedAtNanos,
                    payload = PendingCommentBind(view, rawText, checkReply, rawTrusted, commentItem)
                )
            )
            replacedBinds = queued.replaced
            overflowBinds = queued.overflowDropped
            queuedAfter = pendingCommentBinds.size
        }
        commentBindProbe.onEnqueue(replacedBinds, overflowBinds, queuedAfter)
        if (!bindDrainScheduled) {
            scheduleCommentBindIdle()
        }
    }

    private fun drainCommentBinds() {
        if (isAnyRecyclerViewScrolling()) {
            commentBindProbe.onDeferred(CommentBindProbe.DEFER_SCROLL)
            scheduleCommentBindRetry(120L)
            return
        }
        if (FreeCopyGestureState.commentTouchedView != null ||
            FreeCopyGestureState.descTouchedView != null ||
            (FreeCopyGestureState.bubbleShownAtMs > 0L &&
                SystemClock.uptimeMillis() - FreeCopyGestureState.bubbleShownAtMs < 500L)
        ) {
            commentBindProbe.onDeferred(CommentBindProbe.DEFER_TOUCH)
            scheduleCommentBindRetry(80L)
            return
        }
        if (rvIdleSinceMs > 0L) {
            val quiet = SystemClock.uptimeMillis() - rvIdleSinceMs
            if (quiet < 120L) {
                commentBindProbe.onDeferred(CommentBindProbe.DEFER_QUIET)
                scheduleCommentBindRetry(120L - quiet + 20L)
                return
            }
        }
        bindDrainScheduled = false
        val drainStartedNs = commentBindClock.nanoTime()
        val drainToken = commentBindProbe.beginDrain()
        var queueBefore = 0
        val batch = synchronized(pendingBindLock) {
            queueBefore = pendingCommentBinds.size
            pendingCommentBinds.takeBatch(drainStartedNs, commentBindBudgetPolicy.starvationLimitNs)
        }
        val outcome = CommentBindBudget.run(
            batch, drainStartedNs, commentBindBudgetPolicy, commentBindClock
        ) { entry ->
            val generation = entry.generation
            val enqueuedAtNanos = entry.enqueuedAtNanos
            val (v, raw, checkReply, rawTrusted, commentItem) = entry.payload
            val stillLatest = synchronized(commentRootLock) {
                latestCommentBindingGeneration[v] == generation
            }
            if (!stillLatest) {
                commentBindProbe.noteStale()
                return@run
            }
            if (v.isAttachedToWindow) {
                val itemToken = commentBindProbe.beginItem()
                val validComment = applyFreeCopyListener(v, raw, checkReply)
                if (validComment) {
                    registerCommentRoot(v, raw, checkReply, rawTrusted, commentItem, generation)
                } else {
                    unregisterCommentRoot(v, expectedGeneration = generation)
                }
                commentBindProbe.endItem(itemToken, enqueuedAtNanos, validComment)
            } else {
                commentBindProbe.noteDetached()
                unregisterCommentRoot(v, expectedGeneration = generation)
            }
        }
        var queueAfter = 0
        val hasMore = synchronized(pendingBindLock) {
            pendingCommentBinds.giveBack(outcome.unprocessed)
            queueAfter = pendingCommentBinds.size
            pendingCommentBinds.size > 0
        }
        commentBindProbe.endDrain(drainToken, queueBefore, batch.size, queueAfter)
        if (hasMore) {
            scheduleCommentBindIdle()
        }
        commentBindProbe.takeReportIfDue(queueEmpty = !hasMore)?.let {
            FreeCopyRuntimeConfig.frameworkLog(it)
        }
    }

    fun onDetailTextSet(v: View) {
        if (FreeCopyRuntimeConfig.runtimeTitleFreeCopyEnabled) {
            runCatching { FreeCopyTextExtractor.onTitleCandidateTextSet(v) }
        }
        if (!FreeCopyRuntimeConfig.runtimeDescriptionFreeCopyEnabled) return
        if (FreeCopyTextExtractor.descViewId == View.NO_ID) {
            FreeCopyTextExtractor.resolveDescViewId(v.context)
            if (FreeCopyTextExtractor.descViewId == View.NO_ID) return
        }
        if (v.id != FreeCopyTextExtractor.descViewId) return
        FreeCopyGestureState.descCachedViewRef = java.lang.ref.WeakReference(v)
        v.post {
            HostThreadGuard.run("free_copy.desc_apply") {
                applyFreeCopyListener(v, null, false)
            }
        }
    }

    fun applyTitleFreeCopyListener(v: View) {
        if (!FreeCopyRuntimeConfig.runtimeTitleFreeCopyEnabled || !FreeCopyTextExtractor.isTitleView(v)) return
        setLongClickListenerNoHook(v, FreeCopyGestureState.sharedFreeCopyListener)
    }

    fun clearModuleLongClickListener(v: View) {
        runCatching {
            val listenerInfoField = mListenerInfoField
                ?: KavaMemberLookup.fieldOrNull(classOf<View>(), "mListenerInfo")
                    ?.also { mListenerInfoField = it }
                ?: return
            val listenerInfo = listenerInfoField.get(v) ?: return
            val listenerField = mOnLongClickListenerField
                ?: KavaMemberLookup.fieldOrNull(listenerInfo.javaClass, "mOnLongClickListener")
                    ?.also { mOnLongClickListenerField = it }
                ?: return
            val current = listenerField.get(listenerInfo)
            if (current === FreeCopyGestureState.sharedFreeCopyListener) {
                listenerField.set(listenerInfo, null)
                v.isLongClickable = false
            }
        }
    }

    fun applyFreeCopyListener(root: View, rawText: String?, checkReply: Boolean = true): Boolean {
        val descId = FreeCopyTextExtractor.descViewId
        val isDescription = descId != View.NO_ID && root.id == descId
        if (isDescription && !FreeCopyRuntimeConfig.runtimeDescriptionFreeCopyEnabled) return false
        if (!isDescription && !FreeCopyRuntimeConfig.runtimeCommentFreeCopyEnabled) return false
        val views = ArrayList<View>(32)
        var hasReply = false
        var hasDate = false
        val probing = commentBindProbe.enabled
        fun collect(v: View): Boolean {
            if (probing) commentBindProbe.countNode()
            val subtreeStart = views.size
            val isRecyclerView = hostRecyclerViewClass?.isInstance(v) == true
            if (v is TextView) {
                val t = v.textToString()
                if (!hasReply && t == "回复") hasReply = true
                if (!hasDate && COMMENT_DATE_PATTERN.matcher(t).find()) hasDate = true
            }
            var containsImage = v is ImageView
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    if (collect(v.childOrNull<View>(i) ?: continue)) containsImage = true
                }
            }
            val protectsMediaBranch = v !== root &&
                v is ViewGroup &&
                !isRecyclerView &&
                containsImage &&
                (v.isClickable || v.hasOnClickListeners())
            val protectsCommentActions = v !== root && FreeCopyTextExtractor.isCommentActionsContainer(v)
            if (protectsMediaBranch || protectsCommentActions) {
                for (i in subtreeStart until views.size) clearModuleLongClickListener(views[i])
                if (subtreeStart < views.size) views.subList(subtreeStart, views.size).clear()
                clearModuleLongClickListener(v)
            } else {
                val bindable = v !is ImageView &&
                    !isRecyclerView &&
                    !v.isClickable &&
                    !v.hasOnClickListeners()
                if (bindable) {
                    views.add(v)
                } else {
                    clearModuleLongClickListener(v)
                }
            }
            return containsImage
        }
        collect(root)
        if (probing) commentBindProbe.noteBindable(views.size)
        if (checkReply && !hasReply && !hasDate) {
            for (v in views) clearModuleLongClickListener(v)
            return false
        }
        for (v in views) {
            setLongClickListenerNoHook(v, FreeCopyGestureState.sharedFreeCopyListener)
        }
        return true
    }

    fun setLongClickListenerNoHook(v: View, l: View.OnLongClickListener?) {
        runCatching {
            val mliField = mListenerInfoField
                ?: KavaMemberLookup.fieldOrNull(classOf<View>(), "mListenerInfo")
                    ?.also { mListenerInfoField = it }
                ?: error("View.mListenerInfo unavailable")
            var li = mliField.get(v)
            if (li == null) {
                val method = getListenerInfoMethod
                    ?: KavaMemberLookup.methodOrNull(classOf<View>(), "getListenerInfo")
                        ?.also { getListenerInfoMethod = it }
                    ?: error("View.getListenerInfo unavailable")
                li = method.invoke(v)
            }
            val field = mOnLongClickListenerField
                ?: KavaMemberLookup.fieldOrNull(li.javaClass, "mOnLongClickListener")
                    ?.also { mOnLongClickListenerField = it }
                ?: error("ListenerInfo.mOnLongClickListener unavailable")
            field.set(li, l)
            if (l != null && !v.isLongClickable) v.isLongClickable = true
        }.onFailure {
            v.setOnLongClickListener(l)
        }
    }

    fun holderItemView(holder: Any): View? {
        val f = cHolderItemViewField
            ?: KavaMemberLookup.fieldOrNull(
                holder.javaClass, "itemView", includeSuperclasses = true
            )?.also { cHolderItemViewField = it }
            ?: return null
        return f.get(holder) as? View
    }

    fun extractBindingRoot(binding: Any): View? {
        if (binding is View) return binding
        val bindingClass = binding.javaClass
        cBindingGetRootMethodByClass[bindingClass]?.let { cached ->
            runCatching { cached.invoke(binding) as? View }.getOrNull()?.let { return it }
        }
        KavaMemberLookup.inheritedMethodOrNull(bindingClass, "getRoot")
            ?.takeIf { it.returnType isSubclassOf classOf<View>() }
            ?.let { method ->
                cBindingGetRootMethodByClass[bindingClass] = method
                runCatching { method.invoke(binding) as? View }.getOrNull()?.let { return it }
            }
        cBindingRootFieldByClass[bindingClass]?.let { cached ->
            runCatching { cached.get(binding) as? View }.getOrNull()?.let { return it }
        }
        runCatching {
            val f = KavaMemberLookup.fieldOrNull(
                bindingClass, "a", includeSuperclasses = true
            ) ?: return@runCatching
            val v = f.get(binding) as? View
            if (v != null) {
                cBindingRootFieldByClass[bindingClass] = f
                return v
            }
        }
        for (fld in KavaMemberLookup.declaredFields(bindingClass, makeAccessible = true)) {
            val x = runCatching { fld.get(binding) }.getOrNull()
            if (x is View) {
                cBindingRootFieldByClass[bindingClass] = fld
                return x
            }
        }
        return null
    }

    fun extractBindingRootFromArgs(param: ModernHookParam): View? {
        val method = param.method
        cViewBindingArgIndexByMethod[method]?.let { cachedIndex ->
            val cachedRoot = param.argOrNull(cachedIndex)?.let { binding ->
                runCatching { extractBindingRoot(binding) }.getOrNull()
            }
            if (cachedRoot != null) return cachedRoot
            cViewBindingArgIndexByMethod.remove(method, cachedIndex)
        }
        val parameterTypes = (method as? Method)?.parameterTypes
        val paramCount = method.parameterCount
        for (i in 0 until paramCount) {
            val binding = param.argOrNull(i) ?: continue
            val type = parameterTypes?.getOrNull(i) ?: binding.javaClass
            if (type.isPrimitive || type.isArray) continue
            val looksLikeBinding =
                KavaMemberLookup.methods(type, includeSuperclasses = true) {
                    it.name == "getRoot" && it.parameterCount == 0 &&
                        it.returnType isSubclassOf classOf<View>()
                }.isNotEmpty() || KavaMemberLookup.declaredFields(type) {
                    it.type isSubclassOf classOf<View>()
                }.isNotEmpty()
            if (!looksLikeBinding) continue
            val root = runCatching { extractBindingRoot(binding) }.getOrNull() ?: continue
            cViewBindingArgIndexByMethod[method] = i
            return root
        }
        return null
    }

    fun extractCommentItemForBinding(param: ModernHookParam, handler: Any): BoundCommentItem? {
        val method = param.method
        cCommentItemArgIndexByMethod[method]?.let { cachedIndex ->
            val cached = param.argOrNull(cachedIndex)
            if (cached != null && cached.javaClass.name.endsWith(".CommentItem")) {
                return BoundCommentItem(cached, true)
            }
            cCommentItemArgIndexByMethod.remove(method, cachedIndex)
        }
        val paramCount = method.parameterCount
        for (i in 0 until paramCount) {
            val candidate = param.argOrNull(i) ?: continue
            if (candidate.javaClass.name.endsWith(".CommentItem")) {
                cCommentItemArgIndexByMethod[method] = i
                return BoundCommentItem(candidate, true)
            }
        }
        val handlerClass = handler.javaClass
        cHandlerCommentItemFieldByClass[handlerClass]?.let { cachedField ->
            runCatching { cachedField.get(handler) }.getOrNull()?.let {
                return BoundCommentItem(it, false)
            }
        }
        for (field in KavaMemberLookup.declaredFields(
            handlerClass, makeAccessible = true
        ) { it.type.name.endsWith(".CommentItem") }) {
            val value = runCatching { field.get(handler) }.getOrNull() ?: continue
            cHandlerCommentItemFieldByClass[handlerClass] = field
            return BoundCommentItem(value, false)
        }
        return null
    }
}
