package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.comment

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.ReplyTopologyHostAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.ReplyTopologyHostPage
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.ReplyTopologyPageCall
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.ReplyTopologySeed
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.freecopy.FreeCopyPopupBridge
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology.ReplyTopologyGraph
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology.ReplyTopologyGraphBuilder
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology.ReplyTopologyNodeStore
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology.ReplyTopologyPagingBudget
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology.ReplyTopologyPagingDecision
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology.ReplyTopologyPagingGuard
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology.ReplyTopologyPagingStopReason
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology.ReplyTopologyRequestToken
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology.ReplyTopologySessionGate
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology.ReplyTopologySessionToken
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicReference

/** 单活动会话协调器：顺序分页、资源预算、迟到结果隔离、构图调度和跨详情页面板恢复。 */
internal class ReplyTopologyCoordinator(
    private val host: ReplyTopologyHostAccess,
    private val environment: HookEnvironment
) {
    private val gate = ReplyTopologySessionGate()
    private val panelController = ReplyTopologyPanelController()

    /** 面板全部主线程任务的唯一投递口，统一经过 [HostThreadGuard] 防波堤。 */
    private val mainHandler = object : Handler(Looper.getMainLooper()) {
        override fun dispatchMessage(msg: Message) {
            HostThreadGuard.run("comment_topology.main_message") { super.dispatchMessage(msg) }
        }
    }
    private var active: Active? = null
    private var lastOpacity = 0.90f
    private var lastPosition = ReplyTopologyPanelPosition(0.94f, 0.18f)

    fun open(activity: Activity, seed: ReplyTopologySeed, sourceAnchor: View? = null) {
        if (Looper.myLooper() !== Looper.getMainLooper()) {
            val weakAnchor = sourceAnchor?.let(::WeakReference)
            mainHandler.post {
                val anchor = weakAnchor?.get()
                if (weakAnchor != null && anchor == null) return@post
                open(activity, seed, anchor)
            }
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
            sourceAnchor = sourceAnchor?.let(::WeakReference),
            strings = ReplyTopologyPanelStrings.resolve(activity),
            worker = worker,
            nodes = ReplyTopologyNodeStore(seed.key).apply { append(seed.nodes) },
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
                state.selectedRpid = rpid
            }

            override fun onNodeFullTextRequested(anchor: View, text: CharSequence) {
                runCatching {
                    FreeCopyPopupBridge.showReplyTraceBubble(anchor, text)
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

            override fun onFullTreeRequested() {
                if (active !== state || !gate.accepts(state.token) || !state.canLoadMore) return
                if (!state.loadAll) state.guard.beginBatch(SystemClock.elapsedRealtime(), EXTENDED_BUDGET)
                state.loadAll = true
                if (!state.loadingRequest) restartLoading(state.token, extended = true)
            }

            override fun onOpacityCommitted(opacity: Float) {
                lastOpacity = opacity.coerceIn(0.35f, 1f)
            }

            override fun onPositionCommitted(position: ReplyTopologyPanelPosition) {
                lastPosition = position.normalized()
            }

            override fun onClosed(reason: ReplyTopologyPanelCloseReason) {
                runCatching { FreeCopyPopupBridge.dismissReplyTraceBubbleIfShowing() }
                val panel = panelRef.get()
                val current = active
                if (current?.token != state.token) return
                if (panel != null && current.panelSession != panel) return
                close(state.token)
            }
        }
        return panelController.attach(activity, config, listener, state.sourceAnchor?.get())?.also(panelRef::set)
    }

    private fun requestNext(state: Active) {
        if (active !== state || !gate.accepts(state.token) || state.loadingRequest || !state.canLoadMore) return
        val request = gate.beginRequest(state.token) ?: return
        val offset = state.nextOffset.orEmpty()
        state.loadingRequest = true
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
        val appended = runCatching { state.nodes.append(page.nodes) }.getOrElse {
            if (it is IllegalArgumentException) {
                state.canLoadMore = false
                state.loadAll = false
                scheduleGraphBuild(state, force = true)
                updatePanelState(
                    state,
                    ReplyTopologyPanelState.partial(
                        loadedCount(state), state.expectedCount, state.strings.invalidPageMessage, canRetry = false
                    )
                )
                environment.logError("comment_topology_request", "[BIL] 回复脉络收到非法页，已保留当前结果: $it")
            } else handleFailure(state, it)
            return
        }
        if (!appended.accepted) {
            state.canLoadMore = false
            state.loadAll = false
            scheduleGraphBuild(state, force = true)
            updatePanelState(
                state,
                ReplyTopologyPanelState.partial(
                    loadedCount(state), state.expectedCount, state.strings.memoryLimitMessage, canRetry = false
                )
            )
            return
        }
        page.expectedReplyCount?.let { state.expectedCount = it }
        val decision = state.guard.onPage(
            currentOffset = currentOffset,
            nextOffset = page.nextOffset,
            newUniqueNodes = appended.newUniqueNodes,
            totalUniqueNodes = state.nodes.uniqueCount,
            nowMs = SystemClock.elapsedRealtime(),
            pageTruncated = page.truncated
        )
        scheduleGraphBuild(state, force = decision is ReplyTopologyPagingDecision.Stop)
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
                if (state.loadAll) scheduleNextPage(state) else requestNext(state)
            }
            is ReplyTopologyPagingDecision.Stop -> {
                state.nextOffset = page.nextOffset
                if (decision.complete) {
                    state.complete = true
                    state.canLoadMore = false
                    state.pendingCompletion = ReplyTopologyPanelState(
                        phase = ReplyTopologyPanelPhase.COMPLETE,
                        loadedCount = loaded,
                        expectedCount = state.expectedCount ?: loaded,
                        message = state.strings.completeMessage
                    )
                    updatePanelState(state, ReplyTopologyPanelState.loading(loaded, state.expectedCount))
                } else if (decision.reason in RESOURCE_STOP_REASONS) {
                    if (state.loadAll) {
                        state.guard.beginBatch(SystemClock.elapsedRealtime(), EXTENDED_BUDGET)
                        updatePanelState(state, ReplyTopologyPanelState.loading(loaded, state.expectedCount))
                        scheduleNextPage(state)
                        return
                    }
                    updatePanelState(
                        state,
                        ReplyTopologyPanelState.resourceLimit(
                            loadedCount = loaded,
                            expectedCount = state.expectedCount,
                            message = state.strings.resourcePausedMessage
                        )
                    )
                } else {
                    state.canLoadMore = false
                    state.loadAll = false
                    updatePanelState(
                        state,
                        ReplyTopologyPanelState.partial(
                            loadedCount = loaded,
                            expectedCount = state.expectedCount,
                            message = if (page.truncated) state.strings.pageTruncatedMessage
                            else stopReasonMessage(state.strings, decision.reason),
                            canRetry = false
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
        scheduleGraphBuild(state, force = true)
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

    private fun scheduleNextPage(state: Active) {
        state.nextBatch?.let { mainHandler.removeCallbacks(it) }
        val next = Runnable {
            state.nextBatch = null
            if (active === state && gate.accepts(state.token)) requestNext(state)
        }
        state.nextBatch = next
        mainHandler.postDelayed(next, FULL_TREE_PAGE_YIELD_MS)
    }

    private fun restartLoading(token: ReplyTopologySessionToken, extended: Boolean) {
        val state = active?.takeIf { it.token == token } ?: return
        if (state.complete || !state.canLoadMore || state.loadingRequest || state.nextOffset == null) return
        state.nextBatch?.let { mainHandler.removeCallbacks(it) }
        state.nextBatch = null
        state.guard.beginBatch(SystemClock.elapsedRealtime(), if (extended) EXTENDED_BUDGET else AUTO_BUDGET)
        updatePanelState(
            state,
            ReplyTopologyPanelState.loading(
                loadedCount(state),
                state.expectedCount
            )
        )
        requestNext(state)
    }

    private fun scheduleGraphBuild(state: Active, force: Boolean = false) {
        if (active !== state || !gate.accepts(state.token)) return
        val now = SystemClock.elapsedRealtime()
        if (!force && state.graphRevision > 0L && now - state.lastGraphBuildAtMs < GRAPH_BUILD_INTERVAL_MS) return
        state.lastGraphBuildAtMs = now
        val revision = ++state.graphRevision
        val snapshots = state.nodes.snapshot()
        execute(state.worker) {
            if (!gate.accepts(state.token) || Thread.currentThread().isInterrupted) return@execute
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
                        state.pendingCompletion?.let { complete ->
                            state.pendingCompletion = null
                            updatePanelState(state, complete)
                        }
                    },
                    onFailure = { throwable ->
                        environment.logError(
                            "comment_topology_graph",
                            "[BIL] 回复脉络构图失败，已保留当前面板: $throwable"
                        )
                        if (state.pendingCompletion != null) {
                            state.pendingCompletion = null
                            updatePanelState(
                                state,
                                ReplyTopologyPanelState.partial(
                                    loadedCount(state), state.expectedCount, state.strings.loadErrorMessage, canRetry = false
                                )
                            )
                        }
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
        state.nextBatch?.let { mainHandler.removeCallbacks(it) }
        state.nextBatch = null
        state.worker.shutdownNow()
        val panel = state.panelSession
        state.panelSession = null
        panel?.let { panelController.detach(it, reason) }
        state.activityRef.clear()
        state.nodes.clear()
        state.graph = null
    }

    private fun execute(worker: ExecutorService, action: () -> Unit) {
        try {
            worker.execute { HostThreadGuard.run("comment_topology.worker", action) }
        } catch (_: RejectedExecutionException) {
            // 面板已关闭；宿主异步回调只需静默丢弃。
        }
    }

    private fun newPagingGuard(extended: Boolean) = ReplyTopologyPagingGuard(
        startedAtMs = SystemClock.elapsedRealtime(),
        budget = if (extended) EXTENDED_BUDGET else AUTO_BUDGET
    )

    private fun loadedCount(state: Active): Int =
        state.nodes.replyCount.coerceAtLeast(0)

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
        val sourceAnchor: WeakReference<View>?,
        var strings: ReplyTopologyPanelStrings,
        val worker: ExecutorService,
        val nodes: ReplyTopologyNodeStore,
        var expectedCount: Int?,
        var nextOffset: String?,
        var guard: ReplyTopologyPagingGuard,
        var panelSession: ReplyTopologyPanelSession? = null,
        var graph: ReplyTopologyGraph? = null,
        var panelState: ReplyTopologyPanelState = ReplyTopologyPanelState.loading(0),
        var selectedRpid: Long? = null,
        var loadingRequest: Boolean = false,
        var complete: Boolean = false,
        var loadAll: Boolean = false,
        var canLoadMore: Boolean = true,
        var nextBatch: Runnable? = null,
        var lastGraphBuildAtMs: Long = 0L,
        var pendingCompletion: ReplyTopologyPanelState? = null,
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
        private const val REQUEST_TIMEOUT_MS = 15_000L
        private const val GRAPH_BUILD_INTERVAL_MS = 350L
        private const val FULL_TREE_PAGE_YIELD_MS = 150L
        private val AUTO_BUDGET = ReplyTopologyPagingBudget(
            maxPages = 60,
            maxUniqueNodes = 1_500,
            maxElapsedMs = 12_000L,
            maxConsecutiveNoProgressPages = 2
        )
        private val EXTENDED_BUDGET = ReplyTopologyPagingBudget(
            maxPages = 200,
            maxUniqueNodes = 1_500,
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
