package com.Bilibili_Innocent_Lab.xposedmodule.ui.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 直接附加到 Activity content/decor 的小尺寸 View；它不是 Dialog、PopupWindow 或系统悬浮窗，
 * 因而不会进入自由复制窗口抑制会话，也不会拦截面板边界之外的宿主触摸。
 */
internal class ReplyTopologyPanelView(
    context: Context,
    val session: ReplyTopologyPanelSession,
    private val config: ReplyTopologyPanelConfig,
    private val theme: ReplyTopologyPanelTheme,
    listener: ReplyTopologyPanelListener,
    host: ReplyTopologyPanelHost
) : LinearLayout(context) {

    private val density = resources.displayMetrics.density
    private val strings = config.strings
    private val panelBackground = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(16).toFloat()
        setStroke(dp(1).coerceAtLeast(1), theme.strokeColor)
    }

    private val titleView = TextView(context)
    private val expandView = TextView(context)
    private val closeView = TextView(context)
    private val opacityLabel = TextView(context)
    private val opacitySeek = SeekBar(context)
    private val statusView = TextView(context)
    private val retryView = actionChip(strings.retry)
    private val continueView = actionChip(strings.continueLoading)
    private val recyclerView = RecyclerView(context)
    private val workflowAdapter = ReplyTopologyWorkflowAdapter(
        theme = theme,
        strings = strings,
        onNodeSelected = { rpid -> panelListener?.onNodeSelected(rpid) },
        onNodeLocateRequested = { rpid -> panelListener?.onNodeLocateRequested(rpid) }
    )
    private val trackDecoration = ReplyTopologyTrackDecoration(workflowAdapter, theme, density)
    private val workflowScrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                workflowAdapter.cancelPendingTap()
            }
        }
    }

    private var panelListener: ReplyTopologyPanelListener? = listener
    private var hostRef = WeakReference(host)
    private var boundsParentRef: WeakReference<ViewGroup>? = null
    private var currentState = ReplyTopologyPanelState(ReplyTopologyPanelPhase.IDLE)
    private var currentOpacity = config.initialBackgroundOpacity
    private var initialPosition = config.initialPosition.normalized()
    private var positionInitialized = false
    private val opacityRow: View by lazy(LazyThreadSafetyMode.NONE) { createOpacityRow() }
    private var locateCompact = false
    private var expandedHeightPx = 0

    @Volatile
    var isReleased: Boolean = false
        private set

    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private var dragDownRawX = 0f
    private var dragDownRawY = 0f
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragging = false
    private var pendingMoveX = 0f
    private var pendingMoveY = 0f
    private var moveFramePosted = false
    private val applyMoveRunnable = Runnable {
        moveFramePosted = false
        if (!isReleased) applyBoundedTranslation(pendingMoveX, pendingMoveY)
    }
    private val applyInitialPositionRunnable = Runnable {
        val parent = boundsParentRef?.get() ?: return@Runnable
        if (!isReleased && this.parent === parent) applyInitialPosition()
    }
    private val entranceRunnable = Runnable {
        if (isReleased || !isAttachedToWindow) return@Runnable
        animate().cancel()
        animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(180L)
            .setInterpolator(PathInterpolator(0f, 0f, 0.2f, 1f))
            .start()
    }
    private val compactLayoutRunnable = Runnable {
        if (!isReleased) applyBoundedTranslation(translationX, translationY)
    }

    private val parentLayoutListener = OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        if (!isReleased) {
            if (!positionInitialized) applyInitialPosition()
            else applyBoundedTranslation(translationX, translationY)
        }
    }

    init {
        orientation = VERTICAL
        isClickable = true
        isFocusable = true
        elevation = dp(12).toFloat()
        background = panelBackground
        setBackgroundOpacity(currentOpacity, notify = false)
        contentDescription = strings.panelDescription
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) defaultFocusHighlightEnabled = false

        addView(createHeader(), LayoutParams(LayoutParams.MATCH_PARENT, dp(48)))
        addView(opacityRow, LayoutParams(LayoutParams.MATCH_PARENT, dp(34)))
        addView(createStatusRow(), LayoutParams(LayoutParams.MATCH_PARENT, dp(38)))
        addView(createWorkflowList(), LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        updateState(currentState)
    }

    fun bindBoundsParent(parent: ViewGroup, position: ReplyTopologyPanelPosition) {
        if (isReleased) return
        boundsParentRef?.get()?.removeOnLayoutChangeListener(parentLayoutListener)
        boundsParentRef = WeakReference(parent)
        initialPosition = position.normalized()
        positionInitialized = false
        parent.addOnLayoutChangeListener(parentLayoutListener)
        removeCallbacks(applyInitialPositionRunnable)
        post(applyInitialPositionRunnable)
    }

    fun playEntrance() {
        if (isReleased) return
        alpha = 0f
        scaleX = 0.97f
        scaleY = 0.97f
        removeCallbacks(entranceRunnable)
        post(entranceRunnable)
    }

    fun submit(snapshot: ReplyTopologyRenderSnapshot) {
        if (isReleased) return
        workflowAdapter.submit(snapshot)
        recyclerView.invalidateItemDecorations()
    }

    fun updateState(state: ReplyTopologyPanelState) {
        if (isReleased) return
        currentState = state
        titleView.text = titleText(state)
        statusView.text = statusText(state)
        statusView.setTextColor(
            if (state.phase == ReplyTopologyPanelPhase.ERROR) theme.errorColor
            else theme.secondaryTextColor
        )
        retryView.visibility = if (state.canRetry) View.VISIBLE else View.GONE
        continueView.visibility = if (state.canContinue) View.VISIBLE else View.GONE
    }

    fun setBackgroundOpacity(opacity: Float, notify: Boolean) {
        if (isReleased) return
        val normalized = opacity.coerceIn(config.minBackgroundOpacity, 1f)
        currentOpacity = normalized
        panelBackground.setColor(withAlpha(theme.backgroundColor, normalized))
        panelBackground.invalidateSelf()
        val progress = (normalized * 100f).roundToInt()
        if (opacitySeek.progress != progress) opacitySeek.progress = progress
        opacityLabel.text = "$progress%"
        if (notify) panelListener?.onOpacityCommitted(normalized)
    }

    fun selectAndReveal(rpid: Long): Boolean {
        if (isReleased) return false
        val position = workflowAdapter.selectRpid(rpid)
        if (position < 0) return false
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return false
        layoutManager.scrollToPositionWithOffset(position, recyclerView.height / 3)
        return true
    }

    /**
     * 定位期间收起列表与透明度控件，只保留标题、状态、展开和关闭入口。尺寸切换不启动
     * 持续动画，也不改变用户设置的背景透明度和当前位置。
     */
    fun setLocateCompact(compact: Boolean) {
        if (isReleased || locateCompact == compact) return
        val params = layoutParams ?: return
        if (compact) {
            expandedHeightPx = params.height.takeIf { it > 0 } ?: height.takeIf { it > 0 } ?: 0
        }
        locateCompact = compact
        opacityRow.visibility = if (compact) View.GONE else View.VISIBLE
        recyclerView.visibility = if (compact) View.GONE else View.VISIBLE
        expandView.visibility = if (compact) View.VISIBLE else View.GONE
        params.height = if (compact) {
            val compactHeight = dp(LOCATE_COMPACT_HEIGHT_DP)
            val parentHeight = boundsParentRef?.get()?.height?.takeIf { it > 0 }
            parentHeight?.let { compactHeight.coerceAtMost(it) } ?: compactHeight
        } else {
            expandedHeightPx.takeIf { it > 0 } ?: params.height
        }
        layoutParams = params
        requestLayout()
        removeCallbacks(compactLayoutRunnable)
        post(compactLayoutRunnable)
    }

    /**
     * 先清除所有强引用和帧回调再从 parent 移除。返回的 Listener 仅供 Controller 在清理
     * 完成后通知一次关闭原因；重复调用返回 null。
     */
    fun releaseResources(): ReplyTopologyPanelListener? {
        if (isReleased) return null
        isReleased = true
        animate().setListener(null)
        animate().cancel()
        removeCallbacks(applyMoveRunnable)
        removeCallbacks(applyInitialPositionRunnable)
        removeCallbacks(entranceRunnable)
        removeCallbacks(compactLayoutRunnable)
        moveFramePosted = false
        boundsParentRef?.get()?.removeOnLayoutChangeListener(parentLayoutListener)
        boundsParentRef = null
        titleView.setOnTouchListener(null)
        expandView.setOnClickListener(null)
        closeView.setOnClickListener(null)
        retryView.setOnClickListener(null)
        continueView.setOnClickListener(null)
        opacitySeek.setOnSeekBarChangeListener(null)
        workflowAdapter.release()
        recyclerView.removeOnScrollListener(workflowScrollListener)
        recyclerView.adapter = null
        recyclerView.recycledViewPool.clear()
        runCatching { recyclerView.removeItemDecoration(trackDecoration) }
        val listener = panelListener
        panelListener = null
        hostRef.clear()
        return listener
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (isReleased) return
        val host = hostRef.get()
        if (host != null) {
            host.onUnexpectedDetach(session, this)
        } else {
            releaseResources()?.onClosed(ReplyTopologyPanelCloseReason.HOST_DETACHED)
        }
    }

    private fun createHeader(): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(6), 0)
        }
        titleView.apply {
            text = config.title
            textSize = 16f
            setTextColor(theme.primaryTextColor)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = android.view.Gravity.CENTER_VERTICAL
            contentDescription = strings.dragDescription
            setOnTouchListener(::onHeaderTouch)
        }
        closeView.apply {
            text = "×"
            textSize = 25f
            gravity = android.view.Gravity.CENTER
            setTextColor(theme.primaryTextColor)
            background = circleRipple()
            contentDescription = strings.closeDescription
            isClickable = true
            isFocusable = true
            setOnClickListener { hostRef.get()?.onCloseRequested(session) }
        }
        expandView.apply {
            text = strings.expandPanel
            textSize = 12f
            gravity = android.view.Gravity.CENTER
            setTextColor(theme.accentColor)
            setPadding(dp(8), 0, dp(8), 0)
            background = roundedRipple()
            contentDescription = strings.expandDescription
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            setOnClickListener { setLocateCompact(false) }
        }
        row.addView(titleView, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        row.addView(expandView, LayoutParams(LayoutParams.WRAP_CONTENT, dp(36)))
        row.addView(closeView, LayoutParams(dp(40), dp(40)))
        return row
    }

    private fun createOpacityRow(): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(10), 0)
        }
        val hint = TextView(context).apply {
            text = strings.opacityLabel
            textSize = 12f
            setTextColor(theme.secondaryTextColor)
            maxLines = 1
        }
        opacitySeek.apply {
            max = 100
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                min = (config.minBackgroundOpacity * 100f).roundToInt()
            }
            progress = (currentOpacity * 100f).roundToInt()
            progressTintList = ColorStateList.valueOf(theme.accentColor)
            thumbTintList = ColorStateList.valueOf(theme.accentColor)
            contentDescription = strings.opacityDescription
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser || isReleased) return
                    setBackgroundOpacity(progress / 100f, notify = false)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    if (!isReleased) panelListener?.onOpacityCommitted(currentOpacity)
                }
            })
        }
        opacityLabel.apply {
            textSize = 12f
            gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            setTextColor(theme.secondaryTextColor)
            text = "${(currentOpacity * 100f).roundToInt()}%"
        }
        row.addView(hint, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        row.addView(opacitySeek, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        row.addView(opacityLabel, LayoutParams(dp(42), LayoutParams.MATCH_PARENT))
        return row
    }

    private fun createStatusRow(): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(10), 0)
        }
        statusView.apply {
            textSize = 12f
            setTextColor(theme.secondaryTextColor)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        retryView.setOnClickListener { panelListener?.onRetryRequested() }
        continueView.setOnClickListener { panelListener?.onContinueRequested() }
        row.addView(statusView, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        row.addView(retryView, LayoutParams(LayoutParams.WRAP_CONTENT, dp(30)))
        row.addView(continueView, LayoutParams(LayoutParams.WRAP_CONTENT, dp(30)))
        return row
    }

    private fun createWorkflowList(): View {
        recyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = workflowAdapter
            setHasFixedSize(true)
            itemAnimator = null
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(0, dp(2), 0, dp(8))
            addItemDecoration(trackDecoration)
            addOnScrollListener(workflowScrollListener)
            contentDescription = strings.listDescription
        }
        return recyclerView
    }

    private fun actionChip(label: String) = TextView(context).apply {
        text = label
        textSize = 12f
        setTextColor(theme.accentColor)
        gravity = android.view.Gravity.CENTER
        setPadding(dp(10), 0, dp(10), 0)
        background = roundedRipple()
        isClickable = true
        isFocusable = true
        visibility = View.GONE
    }

    private fun onHeaderTouch(view: View, event: MotionEvent): Boolean {
        if (isReleased) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                workflowAdapter.cancelPendingTap()
                dragDownRawX = event.rawX
                dragDownRawY = event.rawY
                dragStartX = translationX
                dragStartY = translationY
                pendingMoveX = dragStartX
                pendingMoveY = dragStartY
                dragging = false
                view.parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - dragDownRawX
                val dy = event.rawY - dragDownRawY
                if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) dragging = true
                if (dragging) scheduleMove(dragStartX + dx, dragStartY + dy)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (moveFramePosted) {
                    removeCallbacks(applyMoveRunnable)
                    moveFramePosted = false
                    applyBoundedTranslation(pendingMoveX, pendingMoveY)
                }
                view.parent?.requestDisallowInterceptTouchEvent(false)
                if (dragging && event.actionMasked == MotionEvent.ACTION_UP) {
                    panelListener?.onPositionCommitted(currentNormalizedPosition())
                }
                dragging = false
                return true
            }
        }
        return false
    }

    private fun scheduleMove(x: Float, y: Float) {
        pendingMoveX = x
        pendingMoveY = y
        if (moveFramePosted) return
        moveFramePosted = true
        postOnAnimation(applyMoveRunnable)
    }

    private fun applyInitialPosition() {
        val bounds = movementBounds() ?: return
        val x = bounds.minX + (bounds.maxX - bounds.minX) * initialPosition.horizontalFraction
        val y = bounds.minY + (bounds.maxY - bounds.minY) * initialPosition.verticalFraction
        applyBoundedTranslation(x, y)
        positionInitialized = true
    }

    private fun applyBoundedTranslation(x: Float, y: Float) {
        val bounds = movementBounds() ?: return
        translationX = x.coerceIn(bounds.minX, bounds.maxX)
        translationY = y.coerceIn(bounds.minY, bounds.maxY)
    }

    private fun currentNormalizedPosition(): ReplyTopologyPanelPosition {
        val bounds = movementBounds() ?: return initialPosition
        val xRange = (bounds.maxX - bounds.minX).coerceAtLeast(1f)
        val yRange = (bounds.maxY - bounds.minY).coerceAtLeast(1f)
        return ReplyTopologyPanelPosition(
            ((translationX - bounds.minX) / xRange).coerceIn(0f, 1f),
            ((translationY - bounds.minY) / yRange).coerceIn(0f, 1f)
        )
    }

    private fun movementBounds(): MovementBounds? {
        val parent = boundsParentRef?.get() ?: return null
        if (parent.width <= 0 || parent.height <= 0 || width <= 0 || height <= 0) return null
        val insets = readInsets(parent)
        val edge = dp(8).toFloat()
        val minX = insets.left + edge
        val minY = insets.top + edge
        val maxX = (parent.width - insets.right - width - edge).coerceAtLeast(minX)
        val maxY = (parent.height - insets.bottom - height - edge).coerceAtLeast(minY)
        return MovementBounds(minX, maxX, minY, maxY)
    }

    private fun readInsets(view: View): EdgeInsets {
        val windowInsets = view.rootWindowInsets ?: return EdgeInsets.ZERO
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = windowInsets.getInsets(WindowInsets.Type.systemBars())
            EdgeInsets(insets.left, insets.top, insets.right, insets.bottom)
        } else {
            @Suppress("DEPRECATION")
            EdgeInsets(
                windowInsets.systemWindowInsetLeft,
                windowInsets.systemWindowInsetTop,
                windowInsets.systemWindowInsetRight,
                windowInsets.systemWindowInsetBottom
            )
        }
    }

    private fun titleText(state: ReplyTopologyPanelState): String {
        val expected = state.expectedCount?.takeIf { it > 0 }
        return if (state.loadedCount > 0 || expected != null) {
            val progress = expected?.let { "${state.loadedCount}/$it" } ?: state.loadedCount.toString()
            "${config.title} · $progress"
        } else config.title
    }

    private fun statusText(state: ReplyTopologyPanelState): String {
        state.message?.takeIf { it.isNotBlank() }?.let { return it }
        return when (state.phase) {
            ReplyTopologyPanelPhase.IDLE -> strings.idleMessage
            ReplyTopologyPanelPhase.LOADING -> strings.loadingMessage
            ReplyTopologyPanelPhase.COMPLETE -> strings.completeMessage
            ReplyTopologyPanelPhase.PARTIAL -> strings.partialMessage
            ReplyTopologyPanelPhase.ERROR -> strings.errorMessage
            ReplyTopologyPanelPhase.RESOURCE_LIMIT -> strings.resourceLimitMessage
            ReplyTopologyPanelPhase.LOCATING -> strings.locatingMessage
        }
    }

    private fun circleRipple(): RippleDrawable {
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
        }
        return RippleDrawable(ColorStateList.valueOf(theme.rippleColor), null, mask)
    }

    private fun roundedRipple(): RippleDrawable {
        val content = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(withAlpha(theme.accentColor, 0.10f))
        }
        val mask = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(Color.WHITE)
        }
        return RippleDrawable(ColorStateList.valueOf(theme.rippleColor), content, mask)
    }

    private fun dp(value: Int): Int = (value * density).roundToInt()

    private fun withAlpha(color: Int, alpha: Float): Int =
        (color and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255f).roundToInt() shl 24)

    private data class MovementBounds(
        val minX: Float,
        val maxX: Float,
        val minY: Float,
        val maxY: Float
    )

    private data class EdgeInsets(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        companion object {
            val ZERO = EdgeInsets(0, 0, 0, 0)
        }
    }

    private companion object {
        const val LOCATE_COMPACT_HEIGHT_DP = 86
    }
}
