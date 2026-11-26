package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.freecopy

import android.app.Dialog
import android.graphics.Rect
import android.os.Handler
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureRuntimeStage
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FreeCopyGestureDetector
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FreeCopyPressPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import java.lang.ref.WeakReference

/**
 * 自由复制三表面共用的触摸长按手势状态与官方抑制会话。
 *
 * 所有 View / Dialog 引用均使用弱引用；手势状态机通过事件时钟裁决长短按，
 * 主线程回调经过 [HostThreadGuard] 防波堤。
 */
internal object FreeCopyGestureState {
    @Volatile
    private var descTouchedViewRef: WeakReference<View>? = null

    /** 触摸状态只持有弱引用，页面销毁且遗漏 UP/CANCEL 时也不会保活整棵 View 树。 */
    var descTouchedView: View?
        get() = descTouchedViewRef?.get()
        set(value) {
            descTouchedViewRef = value?.let { WeakReference(it) }
        }

    @Volatile
    var descCachedViewRef: WeakReference<View>? = null

    @Volatile
    var descLongPressHandled: Boolean = false

    @Volatile
    var commentLongPressHandled: Boolean = false

    @Volatile
    var commentTouchTargetRef: WeakReference<View>? = null

    @Volatile
    var commentTouchRootRef: WeakReference<View>? = null

    @Volatile
    private var commentTouchedViewRef: WeakReference<View>? = null

    var commentTouchedView: View?
        get() = commentTouchedViewRef?.get()
        set(value) {
            commentTouchedViewRef = value?.let { WeakReference(it) }
        }

    @Volatile
    var commentMoreButtonPassthroughDownTime: Long = 0L

    @Volatile
    var suppressOfficialUntilMs: Long = 0L

    @Volatile
    private var bubbleSessionSerial: Long = 0L

    @Volatile
    private var activeBubbleSessionId: Long = 0L

    @Volatile
    var bubbleShownAtMs: Long = 0L

    @Volatile
    var ourBubbleDialogRef: WeakReference<Dialog>? = null

    /** 仅标记当前线程由自由复制气泡主动发起的同步剪贴板写入。 */
    val popupClipboardWriteInProgress: ThreadLocal<Boolean> = ThreadLocal()

    /**
     * 标题/简介/评论三表面共用的长按手势判定后端。惰性创建：避免 Zygote specialize
     * 阶段类初始化时引用尚未就绪的主线程 Runnable。
     */
    val freeCopyGesture: FreeCopyGestureDetector by lazy {
        FreeCopyGestureDetector(freeCopyLongPressRunnable)
    }

    val freeCopyLongPressRunnable: Runnable =
        HostThreadGuard.runnable("free_copy.long_press") {
            freeCopyGesture.onFired()
            val sessionId = freeCopyGesture.sessionId
            if (sessionId == 0L || !freeCopyGesture.isPending) return@runnable
            val descTarget = descTouchedView
            if (descTarget != null) {
                if (!descTarget.isAttachedToWindow || !descTarget.isShown ||
                    descTarget.windowVisibility != View.VISIBLE
                ) {
                    clearDescTouchSession(resetHandled = true)
                    return@runnable
                }
                if (freeCopyGesture.claim(freeCopyGesture.downTime, SystemClock.uptimeMillis())) {
                    sharedFreeCopyListener.onLongClick(descTarget)
                }
                return@runnable
            }
            if (!tryHandleActiveCommentLongPress(sessionId)) {
                clearCommentTouchSession(resetHandled = true)
            }
        }

    /** 全树共享的长按监听器单例（点击时刻按 view 身份与当前树状态解析文本）。 */
    val sharedFreeCopyListener: View.OnLongClickListener = View.OnLongClickListener { view ->
        val descId = FreeCopyTextExtractor.descViewId
        val isDescOnly = descId != View.NO_ID && view.id == descId
        val isTitle = !isDescOnly && FreeCopyTextExtractor.isTitleView(view)
        val isDesc = isDescOnly || isTitle
        if (isDescOnly && !FreeCopyRuntimeConfig.runtimeDescriptionFreeCopyEnabled) return@OnLongClickListener false
        if (isTitle && !FreeCopyRuntimeConfig.runtimeTitleFreeCopyEnabled) return@OnLongClickListener false
        if (!isDesc && !FreeCopyRuntimeConfig.runtimeCommentFreeCopyEnabled) return@OnLongClickListener false
        if (isDesc && (!view.isAttachedToWindow || !view.isShown ||
                view.windowVisibility != View.VISIBLE)
        ) return@OnLongClickListener false
        if (!isDesc && !FreeCopyBindScheduler.isRegisteredCommentTreeMember(view)) {
            return@OnLongClickListener false
        }
        if (isDesc && descLongPressHandled) return@OnLongClickListener true
        if (!isDesc && commentLongPressHandled) return@OnLongClickListener true
        if (isDesc && freeCopyGesture.isTracking &&
            (descTouchedView !== view || (!freeCopyGesture.isPending && !freeCopyGesture.isHandled))
        ) return@OnLongClickListener false
        val resolved = if (isTitle) {
            FreeCopyContent(FreeCopyTextExtractor.extractTitleText(view))
        } else if (isDesc) {
            FreeCopyContent(FreeCopyTextExtractor.extractDescText(view))
        } else {
            FreeCopyTextExtractor.resolveCommentTextAtInteraction(view)
        } ?: return@OnLongClickListener false
        if (!FreeCopyTextExtractor.isValidFreeCopyText(resolved.displayText)) return@OnLongClickListener false
        if (isDesc && freeCopyGesture.isTracking && !freeCopyGesture.isHandled &&
            !freeCopyGesture.claim(freeCopyGesture.downTime, SystemClock.uptimeMillis())
        ) return@OnLongClickListener false
        FreeCopyRuntimeConfig.reportRuntimeStage("free_copy", FeatureRuntimeStage.OBSERVED)
        FreeCopyRuntimeConfig.reportRuntimeStage(
            if (isTitle) "free_copy_title_enabled"
            else if (isDesc) "free_copy_description_enabled" else "free_copy_comment_enabled",
            FeatureRuntimeStage.OBSERVED
        )
        if (isDesc) descLongPressHandled = true else commentLongPressHandled = true
        runCatching {
            FreeCopyPopupBridge.showFreeCopyPopup(view, resolved)
            FreeCopyPopupBridge.hapticFeedback(view)
        }
        true
    }

    fun freeCopyPoster(handler: Handler) = object : FreeCopyGestureDetector.Poster {
        override fun remove(runnable: Runnable) = handler.removeCallbacks(runnable)
        override fun post(runnable: Runnable, delayMillis: Long): Boolean = handler.postDelayed(runnable, delayMillis)
    }

    fun freeCopyLongPressThreshold(): Long = FreeCopyPressPolicy.thresholdMs(
        runCatching { ViewConfiguration.getLongPressTimeout().toLong() }.getOrDefault(0L)
    )

    fun freeCopyHoldSlop(v: View): Float =
        FreeCopyPressPolicy.holdSlop(ViewConfiguration.get(v.context).scaledTouchSlop.toFloat())

    fun freeCopyDueDelayMs(): Long = FreeCopyPressPolicy.dueDelayMs(
        freeCopyGesture.downTime, freeCopyGesture.observedAtMs,
        SystemClock.uptimeMillis(), freeCopyGesture.thresholdMs
    )

    fun clearDescTouchSession(resetHandled: Boolean = true) {
        freeCopyGesture.cancelSchedule(FreeCopyRuntimeConfig.mainHandlerRef?.let { freeCopyPoster(it) })
        descTouchedView = null
        if (resetHandled) {
            descLongPressHandled = false
            freeCopyGesture.reset()
        }
    }

    fun cancelDescLongPressCandidate() {
        freeCopyGesture.cancelSchedule(FreeCopyRuntimeConfig.mainHandlerRef?.let { freeCopyPoster(it) })
        descTouchedView?.let { view ->
            if (!FreeCopyTextExtractor.isTitleView(view)) runCatching { view.cancelLongPress() }
        }
        descTouchedView = null
    }

    fun observeFreeCopyTouch(ev: MotionEvent) {
        if (!freeCopyGesture.matches(ev.downTime) || !freeCopyGesture.isPending) return
        val action = ev.actionMasked
        if (action == MotionEvent.ACTION_CANCEL ||
            action == MotionEvent.ACTION_POINTER_DOWN ||
            action == MotionEvent.ACTION_POINTER_UP
        ) {
            if (freeCopyGesture.cancel(ev.downTime)) cancelFreeCopyCandidate()
            return
        }
        if (action != MotionEvent.ACTION_MOVE && action != MotionEvent.ACTION_UP) return
        val index = ev.findPointerIndex(freeCopyGesture.pointerId)
        if (index < 0 || ev.pointerCount != 1) {
            if (freeCopyGesture.cancel(ev.downTime)) cancelFreeCopyCandidate()
            return
        }
        val offsetX = ev.rawX - ev.x
        val offsetY = ev.rawY - ev.y
        for (history in 0 until ev.historySize) {
            if (freeCopyGesture.sample(
                    ev.downTime, ev.getPointerId(index), ev.pointerCount,
                    ev.getHistoricalX(index, history) + offsetX,
                    ev.getHistoricalY(index, history) + offsetY
                )
            ) {
                cancelFreeCopyCandidate()
                return
            }
        }
        if (freeCopyGesture.sample(
                ev.downTime, ev.getPointerId(index), ev.pointerCount,
                ev.getX(index) + offsetX, ev.getY(index) + offsetY
            )
        ) {
            cancelFreeCopyCandidate()
        }
    }

    fun cancelFreeCopyCandidate() {
        if (descTouchedView != null) cancelDescLongPressCandidate()
        else clearCommentTouchSession(resetHandled = true)
    }

    fun revokePrematurePopupIfShortTap(ev: MotionEvent) {
        if (ev.eventTime - ev.downTime >= freeCopyGesture.thresholdMs) return
        if (!isOurBubbleShowing()) return
        runCatching { ourBubbleDialogRef?.get()?.dismiss() }
        suppressOfficialUntilMs = 0L
        activeBubbleSessionId = 0L
        ourBubbleDialogRef = null
        descLongPressHandled = false
        commentLongPressHandled = false
        freeCopyGesture.finishHandled()
    }

    fun finishDescLikeTouch(ev: MotionEvent, hitView: View?): Boolean {
        val isUp = ev.actionMasked == MotionEvent.ACTION_UP
        freeCopyGesture.cancelSchedule(FreeCopyRuntimeConfig.mainHandlerRef?.let { freeCopyPoster(it) })
        if (isUp) revokePrematurePopupIfShortTap(ev)
        var handled = descLongPressHandled
        val target = descTouchedView
        if (!handled &&
            (!freeCopyGesture.matches(ev.downTime) || (hitView != null && target !== hitView))
        ) {
            clearDescTouchSession(resetHandled = true)
            return false
        }
        if (freeCopyGesture.resolveTerminal(ev.downTime, ev.eventTime, isUp, handled) ==
            FreeCopyGestureDetector.Terminal.CLAIM_AND_CONSUME && target != null
        ) {
            handled = sharedFreeCopyListener.onLongClick(target)
        }
        clearDescTouchSession(resetHandled = !handled)
        return handled && isUp
    }

    fun isOurBubbleShowing(): Boolean =
        activeBubbleSessionId != 0L && ourBubbleDialogRef?.get()?.isShowing == true

    fun isOfficialSuppressionActive(): Boolean {
        if (activeBubbleSessionId == 0L ||
            SystemClock.uptimeMillis() >= suppressOfficialUntilMs
        ) return false
        return ourBubbleDialogRef?.get()?.isShowing == true
    }

    private fun isOfficialLongPressGestureActive(): Boolean =
        (commentTouchedView != null || descTouchedView != null) &&
            freeCopyGesture.isArmed(SystemClock.uptimeMillis())

    fun shouldSuppressOfficialOverlay(): Boolean =
        isOfficialLongPressGestureActive() || isOfficialSuppressionActive()

    fun beginBubbleSession(dialog: Dialog): Long {
        val sessionId = bubbleSessionSerial + 1L
        bubbleSessionSerial = sessionId
        activeBubbleSessionId = sessionId
        ourBubbleDialogRef = WeakReference(dialog)
        suppressOfficialUntilMs = SystemClock.uptimeMillis() + 1500L
        return sessionId
    }

    fun finishBubbleSession(dialog: Dialog, sessionId: Long) {
        if (activeBubbleSessionId != sessionId || ourBubbleDialogRef?.get() !== dialog) return
        suppressOfficialUntilMs = 0L
        activeBubbleSessionId = 0L
        ourBubbleDialogRef = null
        commentLongPressHandled = false
        descLongPressHandled = false
        freeCopyGesture.finishHandled()
    }

    fun clearCommentTouchSession(resetHandled: Boolean = true) {
        freeCopyGesture.cancelSchedule(FreeCopyRuntimeConfig.mainHandlerRef?.let { freeCopyPoster(it) })
        commentTouchTargetRef = null
        commentTouchRootRef = null
        commentTouchedView = null
        if (resetHandled) {
            commentLongPressHandled = false
            freeCopyGesture.reset()
        }
    }

    fun beginOrRetargetCommentTouch(v: View, root: View, ev: MotionEvent) {
        if (!freeCopyGesture.matches(ev.downTime)) {
            clearCommentTouchSession(resetHandled = true)
            freeCopyGesture.begin(
                ev.downTime, SystemClock.uptimeMillis(),
                ev.getPointerId(0), ev.rawX, ev.rawY,
                freeCopyHoldSlop(v), freeCopyLongPressThreshold()
            )
            commentLongPressHandled = false
        }
        commentTouchTargetRef = WeakReference(v)
        commentTouchRootRef = WeakReference(root)
        commentTouchedView = v
        val handler = FreeCopyRuntimeConfig.mainHandlerOrNull() ?: return
        freeCopyGesture.schedule(freeCopyGesture.sessionId, freeCopyDueDelayMs(), freeCopyPoster(handler))
    }

    fun activeCommentTouchTarget(sessionId: Long): View? {
        if (sessionId == 0L || sessionId != freeCopyGesture.sessionId) return null
        val target = commentTouchTargetRef?.get() ?: return null
        val root = commentTouchRootRef?.get() ?: return null
        if (!target.isAttachedToWindow || !root.isAttachedToWindow ||
            !target.isShown || !root.isShown ||
            target.windowVisibility != View.VISIBLE || root.windowVisibility != View.VISIBLE
        ) return null
        var cur: View? = target
        var belongsToRoot = false
        while (cur != null) {
            if (cur === root) {
                belongsToRoot = true
                break
            }
            cur = cur.parent as? View
        }
        if (!belongsToRoot) return null
        val rootStillRegistered = synchronized(FreeCopyBindScheduler.commentRootLock) {
            FreeCopyBindScheduler.commentRootRefs.containsKey(root)
        }
        if (!rootStillRegistered) return null
        val visibleRect = Rect()
        if (!target.getGlobalVisibleRect(visibleRect) || visibleRect.isEmpty) return null
        return target
    }

    fun tryHandleActiveCommentLongPress(sessionId: Long): Boolean {
        if (sessionId == 0L || sessionId != freeCopyGesture.sessionId) return false
        if (commentLongPressHandled) return true
        val v = activeCommentTouchTarget(sessionId) ?: return false
        val text = FreeCopyTextExtractor.resolveCommentTextAtInteraction(v) ?: return false
        if (!FreeCopyTextExtractor.isValidFreeCopyText(text.displayText)) return false
        commentLongPressHandled = true
        runCatching {
            FreeCopyPopupBridge.showFreeCopyPopup(v, text)
            FreeCopyPopupBridge.hapticFeedback(v)
        }
        return true
    }

    fun handleDispatchTouchEvent(v: View, ev: MotionEvent): Boolean {
        val action = ev.actionMasked
        observeFreeCopyTouch(ev)
        if (action == MotionEvent.ACTION_DOWN &&
            commentMoreButtonPassthroughDownTime != 0L &&
            commentMoreButtonPassthroughDownTime != ev.downTime
        ) {
            commentMoreButtonPassthroughDownTime = 0L
        }
        if (commentMoreButtonPassthroughDownTime == ev.downTime) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                commentMoreButtonPassthroughDownTime = 0L
            }
            return false
        }
        val descId = FreeCopyTextExtractor.descViewId
        val isTitleTarget = FreeCopyRuntimeConfig.runtimeTitleFreeCopyEnabled && FreeCopyTextExtractor.isTitleView(v)
        val isDescLikeTarget =
            (FreeCopyRuntimeConfig.runtimeDescriptionFreeCopyEnabled &&
                descId != View.NO_ID && v.id == descId) ||
                isTitleTarget
        if (!isDescLikeTarget) {
            if (action == MotionEvent.ACTION_DOWN) {
                if (freeCopyGesture.isTracking && !freeCopyGesture.matches(ev.downTime)) {
                    clearDescTouchSession(resetHandled = true)
                }
                if (!FreeCopyRuntimeConfig.runtimeCommentFreeCopyEnabled) return false
                if (freeCopyGesture.sessionId != 0L && !freeCopyGesture.matches(ev.downTime)) {
                    clearCommentTouchSession(resetHandled = true)
                }
                if (synchronized(FreeCopyBindScheduler.commentRootLock) {
                        FreeCopyBindScheduler.commentRootRefs.isEmpty()
                    }
                ) return false
                var root: View? = null
                var cur: View? = v
                synchronized(FreeCopyBindScheduler.commentRootLock) {
                    while (cur != null) {
                        if (FreeCopyBindScheduler.commentRootRefs.containsKey(cur)) {
                            root = cur
                            break
                        }
                        cur = cur.parent as? View
                    }
                }
                if (root != null &&
                    (FreeCopyTextExtractor.isCommentMoreButton(v) ||
                        FreeCopyTextExtractor.isCommentActionsContainer(v))
                ) {
                    clearCommentTouchSession(resetHandled = true)
                    commentMoreButtonPassthroughDownTime = ev.downTime
                    return false
                }
                root?.let { beginOrRetargetCommentTouch(v, it, ev) }
                return false
            }

            if ((descTouchedView != null || descLongPressHandled) &&
                freeCopyGesture.matches(ev.downTime) &&
                (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
            ) {
                return finishDescLikeTouch(ev, null)
            }
            if (!FreeCopyRuntimeConfig.runtimeCommentFreeCopyEnabled) return false
            if (freeCopyGesture.sessionId == 0L || !freeCopyGesture.matches(ev.downTime)) return false
            when (action) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val sessionId = freeCopyGesture.sessionId
                    if (action == MotionEvent.ACTION_UP) revokePrematurePopupIfShortTap(ev)
                    var handled = commentLongPressHandled
                    if (freeCopyGesture.resolveTerminal(
                            ev.downTime, ev.eventTime, action == MotionEvent.ACTION_UP, handled
                        ) == FreeCopyGestureDetector.Terminal.CLAIM_AND_CONSUME
                    ) {
                        handled = tryHandleActiveCommentLongPress(sessionId)
                    }
                    clearCommentTouchSession(resetHandled = !handled)
                    if (handled && action == MotionEvent.ACTION_UP) return true
                }
            }
            return false
        }
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                if (freeCopyGesture.sessionId != 0L && !freeCopyGesture.matches(ev.downTime)) {
                    clearCommentTouchSession(resetHandled = true)
                }
                clearDescTouchSession(resetHandled = true)
                freeCopyGesture.begin(
                    ev.downTime, SystemClock.uptimeMillis(),
                    ev.getPointerId(0), ev.rawX, ev.rawY,
                    freeCopyHoldSlop(v), freeCopyLongPressThreshold()
                )
                descLongPressHandled = false
                descTouchedView = v
                val handler = FreeCopyRuntimeConfig.mainHandlerOrNull()
                if (handler != null) {
                    freeCopyGesture.schedule(
                        freeCopyGesture.sessionId, freeCopyDueDelayMs(), freeCopyPoster(handler)
                    )
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (finishDescLikeTouch(ev, v)) return true
            }
        }
        return false
    }
}
