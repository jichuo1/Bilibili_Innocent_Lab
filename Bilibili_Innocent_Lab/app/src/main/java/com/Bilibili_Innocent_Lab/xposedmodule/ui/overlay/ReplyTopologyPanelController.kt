package com.Bilibili_Innocent_Lab.xposedmodule.ui.overlay

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * Activity 内回复脉络面板的唯一入口。
 *
 * Controller 可被进程级安装器长期持有，但自身只弱持 Activity 和 Panel。Panel 的实际
 * 生命周期由宿主 decorView 管理，detach 后会清空 Adapter、回调和所有待执行帧任务。
 */
internal class ReplyTopologyPanelController : ReplyTopologyPanelHost {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val generation = AtomicLong(0L)

    @Volatile
    private var activeSessionId = NO_SESSION

    @Volatile
    private var activityRef: WeakReference<Activity>? = null

    @Volatile
    private var panelRef: WeakReference<ReplyTopologyPanelView>? = null

    /** 退出动画进行中的面板（仅主线程访问）：会话已失效，动画结束后统一移除并回调。 */
    private var exitingPanel: ExitingPanel? = null

    /**
     * 必须从主线程调用；Hook 的评论点击回调本身就在主线程。无效 Activity 会故障开放，
     * 返回 null 而不是抛异常影响宿主。
     */
    fun attach(
        activity: Activity,
        config: ReplyTopologyPanelConfig = ReplyTopologyPanelConfig(
            strings = ReplyTopologyPanelStrings.resolve(activity)
        ),
        listener: ReplyTopologyPanelListener
    ): ReplyTopologyPanelSession? {
        if (Looper.myLooper() !== Looper.getMainLooper()) return null
        if (activity.isFinishing || activity.isDestroyed) return null

        detachOnMain(null, ReplyTopologyPanelCloseReason.REPLACED)
        val parent = findOverlayParent(activity) ?: return null
        val session = ReplyTopologyPanelSession(generation.incrementAndGet())
        val theme = config.theme ?: ReplyTopologyPanelTheme.resolve(activity)
        val panel = ReplyTopologyPanelView(
            context = activity,
            session = session,
            config = config,
            theme = theme,
            listener = listener,
            host = this
        )
        val dimensions = panelDimensions(parent, config)
        val params = if (parent is FrameLayout) {
            FrameLayout.LayoutParams(dimensions.first, dimensions.second, Gravity.TOP or Gravity.START)
        } else {
            ViewGroup.LayoutParams(dimensions.first, dimensions.second)
        }

        return runCatching {
            activeSessionId = session.id
            activityRef = WeakReference(activity)
            panelRef = WeakReference(panel)
            parent.addView(panel, params)
            panel.bindBoundsParent(parent, config.initialPosition.normalized())
            panel.playEntrance()
            session
        }.getOrElse {
            activeSessionId = NO_SESSION
            activityRef = null
            panelRef = null
            runCatching { panel.releaseResources() }
            runCatching { (panel.parent as? ViewGroup)?.removeView(panel) }
            null
        }
    }

    /** 可从任意线程调用；真正的 View 更新会切回主线程并再次核验会话代次。 */
    fun submit(
        session: ReplyTopologyPanelSession,
        snapshot: ReplyTopologyRenderSnapshot
    ): Boolean = withPanel(session) { it.submit(snapshot) }

    /** 可从任意线程调用。 */
    fun updateState(
        session: ReplyTopologyPanelSession,
        state: ReplyTopologyPanelState
    ): Boolean = withPanel(session) { it.updateState(state) }

    /** 可从任意线程调用；只改变背景 Drawable 的 alpha，不降低文字和轨道可读性。 */
    fun setBackgroundOpacity(
        session: ReplyTopologyPanelSession,
        opacity: Float
    ): Boolean = withPanel(session) { it.setBackgroundOpacity(opacity, notify = false) }

    /**
     * 可从任意线程调用。传入 session 时只关闭对应代次；旧会话不能误关后来打开的面板。
     */
    fun detach(
        session: ReplyTopologyPanelSession? = null,
        reason: ReplyTopologyPanelCloseReason = ReplyTopologyPanelCloseReason.PROGRAMMATIC
    ) {
        if (session != null && !isCurrent(session)) return
        if (Looper.myLooper() === Looper.getMainLooper()) {
            detachOnMain(session, reason)
        } else {
            mainHandler.post { detachOnMain(session, reason) }
        }
    }

    /**
     * 精确判断该代次的面板是否仍附着在所属 Activity 的 content/decor。
     *
     * View 的 parent、windowToken 与 isAttachedToWindow 都只能在主线程可靠读取；后台调用
     * 故障开放返回 false，避免 Coordinator 的超时线程越过 Android View 线程边界。页面
     * 跳转重绑超时本身由主线程 Handler 驱动，因此可获得旧面板真实的 decor 附着状态。
     */
    fun isAttached(session: ReplyTopologyPanelSession): Boolean {
        if (Looper.myLooper() !== Looper.getMainLooper()) return false
        val panel = currentPanel(session) ?: return false
        val activity = activityRef?.get() ?: return false
        val parent = panel.parent as? ViewGroup ?: return false
        val expectedParent = findOverlayParent(activity) ?: return false
        return parent === expectedParent &&
            panel.isAttachedToWindow &&
            panel.windowToken != null &&
            !panel.isReleased
    }

    override fun onCloseRequested(session: ReplyTopologyPanelSession) {
        detach(session, ReplyTopologyPanelCloseReason.USER)
    }

    override fun onUnexpectedDetach(
        session: ReplyTopologyPanelSession,
        panel: ReplyTopologyPanelView
    ) {
        if (Looper.myLooper() !== Looper.getMainLooper()) {
            mainHandler.post { onUnexpectedDetach(session, panel) }
            return
        }
        // 退出动画中的面板先于动画被宿主 detach（页面销毁）：按其原始关闭原因统一收尾，
        // 保证 onClosed 恰好一次（releaseResources 在此之前不会发生，listener 仍有效）
        exitingPanel?.takeIf { it.panel === panel }?.let { finishExiting(it) }
        if (!isCurrent(session) || panelRef?.get() !== panel) {
            panel.releaseResources()
            return
        }

        // decor/window 销毁时 View 已脱离，不能再次 removeView；先让会话失效，再通知外层。
        activeSessionId = NO_SESSION
        activityRef = null
        panelRef = null
        val listener = panel.releaseResources()
        listener?.onClosed(ReplyTopologyPanelCloseReason.HOST_DETACHED)
    }

    private fun withPanel(
        session: ReplyTopologyPanelSession,
        action: (ReplyTopologyPanelView) -> Unit
    ): Boolean {
        if (!isCurrent(session)) return false
        if (Looper.myLooper() === Looper.getMainLooper()) {
            currentPanel(session)?.let(action) ?: return false
        } else {
            mainHandler.post {
                currentPanel(session)?.let(action)
            }
        }
        return true
    }

    private fun currentPanel(session: ReplyTopologyPanelSession): ReplyTopologyPanelView? {
        if (!isCurrent(session)) return null
        val activity = activityRef?.get() ?: return null
        if (activity.isFinishing || activity.isDestroyed) return null
        return panelRef?.get()?.takeIf { it.session == session && !it.isReleased }
    }

    private fun isCurrent(session: ReplyTopologyPanelSession): Boolean =
        session.id != NO_SESSION && activeSessionId == session.id

    private fun detachOnMain(
        requestedSession: ReplyTopologyPanelSession?,
        reason: ReplyTopologyPanelCloseReason
    ) {
        if (requestedSession != null && !isCurrent(requestedSession)) return
        // 上一次退出的面板仍在播放离场动画：立即硬移除并按其原始原因回调，
        // 避免与即将挂载的新面板（替换路径）同屏交叠
        exitingPanel?.let { finishExiting(it) }
        val panel = panelRef?.get()
        if (panel == null) {
            activeSessionId = NO_SESSION
            activityRef = null
            panelRef = null
            return
        }

        // 先使代次失效，回调即使同步打开新面板也不会被旧清理覆盖。
        activeSessionId = NO_SESSION
        activityRef = null
        panelRef = null

        // 只有用户关闭与程序化关闭走离场动画；替换（即将挂新面板）与宿主 detach
        //（decor 已销毁或正在销毁）保持同帧移除，与原有语义一致。
        if (reason != ReplyTopologyPanelCloseReason.USER &&
            reason != ReplyTopologyPanelCloseReason.PROGRAMMATIC
        ) {
            val listener = panel.releaseResources()
            runCatching { (panel.parent as? ViewGroup)?.removeView(panel) }
            listener?.onClosed(reason)
            return
        }

        // 与自由复制气泡一致的退出纪律：纯视觉缩小淡出（输入已在 playExit 内摘除，
        // 触摸直接穿透到宿主），动画结束或被取消后统一移除。会话已失效，动画期间
        // submit/updateState/selectAndReveal 均不会再触达面板；onClosed 延后一次动画
        // 时长送达，功能查询 isAttached 立即为 false，对 Coordinator 无影响。
        val exiting = ExitingPanel(panel, reason)
        exitingPanel = exiting
        panel.playExit { finishExiting(exiting) }
        mainHandler.postDelayed(exiting.fallback, EXIT_ANIMATION_FALLBACK_MS)
    }

    /**
     * 退出动画的统一收尾（幂等）：由动画结束、动画取消或超时兜底触发均恰好执行一次。
     * 若面板已被宿主 detach 路径先释放（onClosed 已按其路径送达），此处只补做移除，
     * 不重复回调；先 release 再 removeView 的顺序避免触发 onUnexpectedDetach 级联。
     */
    private fun finishExiting(exiting: ExitingPanel) {
        mainHandler.removeCallbacks(exiting.fallback)
        if (!exiting.finished.compareAndSet(false, true)) return
        if (exitingPanel === exiting) exitingPanel = null
        if (exiting.panel.isReleased) {
            runCatching { (exiting.panel.parent as? ViewGroup)?.removeView(exiting.panel) }
            return
        }
        val listener = exiting.panel.releaseResources()
        runCatching { (exiting.panel.parent as? ViewGroup)?.removeView(exiting.panel) }
        listener?.onClosed(exiting.reason)
    }

    private fun findOverlayParent(activity: Activity): ViewGroup? {
        val content = activity.findViewById<ViewGroup?>(android.R.id.content)
        if (content != null) return content
        return activity.window?.decorView as? ViewGroup
    }

    private fun panelDimensions(
        parent: ViewGroup,
        config: ReplyTopologyPanelConfig
    ): Pair<Int, Int> {
        val density = parent.resources.displayMetrics.density
        val display = parent.resources.displayMetrics
        val availableWidth = parent.width.takeIf { it > 0 } ?: display.widthPixels
        val availableHeight = parent.height.takeIf { it > 0 } ?: display.heightPixels
        val edge = (8f * density).roundToInt()
        val maxAvailableWidth = (availableWidth - edge * 2).coerceAtLeast(1)
        val maxAvailableHeight = (availableHeight - edge * 2).coerceAtLeast(1)

        val minWidth = ((config.minWidthDp * density).roundToInt()).coerceAtMost(maxAvailableWidth)
        val maxWidth = ((config.maxWidthDp * density).roundToInt()).coerceAtMost(maxAvailableWidth)
        val minHeight = ((config.minHeightDp * density).roundToInt()).coerceAtMost(maxAvailableHeight)
        val maxHeight = ((config.maxHeightDp * density).roundToInt()).coerceAtMost(maxAvailableHeight)
        val width = (availableWidth * config.widthFraction).roundToInt()
            .coerceIn(minWidth.coerceAtMost(maxWidth), maxWidth.coerceAtLeast(minWidth))
        val height = (availableHeight * config.heightFraction).roundToInt()
            .coerceIn(minHeight.coerceAtMost(maxHeight), maxHeight.coerceAtLeast(minHeight))
        return width to height
    }

    /** 退出动画进行中的面板记录：finished 保证移除与 onClosed 恰好执行一次。 */
    private inner class ExitingPanel(
        val panel: ReplyTopologyPanelView,
        val reason: ReplyTopologyPanelCloseReason,
    ) {
        val finished = AtomicBoolean(false)
        val fallback = Runnable { finishExiting(this@ExitingPanel) }
    }

    private companion object {
        const val NO_SESSION = 0L

        /** 离场动画 150ms 的超时兜底：主线程繁忙导致动画回调延迟时仍保证移除与回调。 */
        const val EXIT_ANIMATION_FALLBACK_MS = 300L
    }
}

internal interface ReplyTopologyPanelHost {
    fun onCloseRequested(session: ReplyTopologyPanelSession)

    fun onUnexpectedDetach(
        session: ReplyTopologyPanelSession,
        panel: ReplyTopologyPanelView
    )
}
