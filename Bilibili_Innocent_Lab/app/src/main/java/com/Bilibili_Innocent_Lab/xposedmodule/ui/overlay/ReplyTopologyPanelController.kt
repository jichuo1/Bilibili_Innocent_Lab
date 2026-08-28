package com.Bilibili_Innocent_Lab.xposedmodule.ui.overlay

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import java.lang.ref.WeakReference
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

        val parent = findOverlayParent(activity) ?: return null
        val session = ReplyTopologyPanelSession(generation.incrementAndGet())
        val theme = config.theme ?: ReplyTopologyPanelTheme.resolve(activity)
        val panel = runCatching {
            ReplyTopologyPanelView(
                context = activity,
                session = session,
                config = config,
                theme = theme,
                listener = listener,
                host = this
            )
        }.getOrNull() ?: return null
        val dimensions = panelDimensions(parent, config)
        val params = if (parent is FrameLayout) {
            FrameLayout.LayoutParams(dimensions.first, dimensions.second, Gravity.TOP or Gravity.START)
        } else {
            ViewGroup.LayoutParams(dimensions.first, dimensions.second)
        }

        // 先把新面板完整附着并初始化，再替换旧面板；addView 或初始化失败时旧面板
        // 仍保持可操作，不会产生“定位成功但悬浮面板消失”的半迁移状态。
        val prepared = runCatching {
            parent.addView(panel, params)
            panel.bindBoundsParent(parent, config.initialPosition.normalized())
            panel.playEntrance()
        }.onFailure {
            runCatching { panel.releaseResources() }
            runCatching { (panel.parent as? ViewGroup)?.removeView(panel) }
        }.isSuccess
        if (!prepared) return null

        detachOnMain(null, ReplyTopologyPanelCloseReason.REPLACED)
        activeSessionId = session.id
        activityRef = WeakReference(activity)
        panelRef = WeakReference(panel)
        return session
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

    /** 可从任意线程调用；定位时收起内容，用户仍可通过标题栏入口恢复完整面板。 */
    fun setLocateCompact(
        session: ReplyTopologyPanelSession,
        compact: Boolean
    ): Boolean = withPanel(session) { it.setLocateCompact(compact) }

    /**
     * 选中并在已加载列表中滚动到稳定 rpid；不存在时返回 false。该查询必须从主线程
     * 调用，避免向后台线程暴露瞬时 RecyclerView position。
     */
    fun selectAndReveal(
        session: ReplyTopologyPanelSession,
        rpid: Long
    ): Boolean {
        if (!isCurrent(session)) return false
        if (Looper.myLooper() !== Looper.getMainLooper()) return false
        return currentPanel(session)?.selectAndReveal(rpid) == true
    }

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
        val listener = panel.releaseResources()
        runCatching { (panel.parent as? ViewGroup)?.removeView(panel) }
        listener?.onClosed(reason)
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

    private companion object {
        const val NO_SESSION = 0L
    }
}

internal interface ReplyTopologyPanelHost {
    fun onCloseRequested(session: ReplyTopologyPanelSession)

    fun onUnexpectedDetach(
        session: ReplyTopologyPanelSession,
        panel: ReplyTopologyPanelView
    )
}
