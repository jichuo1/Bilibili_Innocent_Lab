package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor

import kotlin.math.abs

/** 一个 StoryPlayer 会话需要的宿主读写；生产实现走反射，单测用假对象。 */
internal interface StorySponsorHost {
    fun alive(): Boolean
    fun resumed(): Boolean
    fun state(): Int
    fun position(): Long
    fun duration(): Long
    fun realPosition(): Long
    fun realDuration(): Long
    fun playable(): StoryPlayableIdentity?
    fun loadedId(): String?
    fun details(): List<StoryDetailSnapshot>
    fun seek(targetMs: Int)
}

/**
 * 一个 StoryPlayer 的商单跳过会话，除 [invalidateExternalSeek] 外都在主线程调用。
 *
 * Story 里一个内核反复装载不同条目，所以每个条目一个新的 [SponsorPlaybackController]：
 * 条目身份通过准入门并且已装载媒体的编号等于数据源游标的编号，才创建控制器并查询。
 * 条目切换、暂停、状态离开播放中都会让控制器停止，回来后重新绑定。
 */
internal class StorySponsorSession(
    private val host: StorySponsorHost,
    private val automatic: Boolean,
    private val lookup: (SponsorVideoId, (SponsorFetchResult) -> Unit) -> SponsorSegmentRepository.Ticket,
    private val budget: StoryLookupBudget,
    private val main: (() -> Unit) -> Unit,
    private val after: (Long, () -> Unit) -> SponsorSegmentRepository.Ticket,
    private val now: () -> Long,
    private val hint: (SponsorPlaybackController.Hint) -> Unit,
    private val applied: () -> Unit,
    private val diagnostic: (String, String) -> Unit
) {
    private class Bound(val video: SponsorVideoId, val playableId: String, val controller: SponsorPlaybackController,
        val port: SponsorPlayerPort)

    @Volatile private var live: Bound? = null
    private var progressSeen = false
    private var stopped = false
    private val granted = LinkedHashMap<SponsorVideoId, Long>()

    val bound: SponsorVideoId? get() = live?.video

    fun progressTick() {
        if (stopped) return
        progressSeen = true
        reconcile()
        live?.controller?.refresh()
    }

    fun stateChanged() {
        if (stopped) return
        reconcile()
        live?.controller?.stateChanged()
    }

    fun resumed() = reconcile()

    /** 宿主即将替换或释放当前条目：旧条目的控制器作废，下一次进度回调再绑定新条目。 */
    fun itemChanged() {
        if (stopped) return
        progressSeen = false
        live?.controller?.invalidateExternalSeek()
        unbind()
    }

    fun seekStarted(targetMs: Long) {
        if (!stopped) live?.controller?.seekStarted(targetMs)
    }

    fun seekCompleted() {
        if (!stopped) live?.controller?.seekCompleted()
    }

    /** 宿主线程同步撤销门禁，模块自己发起的 seek（目标吻合且已进入执行）不受影响。 */
    fun invalidateExternalSeek(targetMs: Long) {
        live?.controller?.invalidateExternalSeek(targetMs)
    }

    fun skip() { live?.controller?.skip() }

    fun undo() { live?.controller?.undo() }

    fun stop() {
        if (stopped) return
        stopped = true
        unbind()
    }

    private fun unbind() {
        val old = live ?: return
        live = null
        old.controller.stop()
    }

    private fun reconcile() {
        if (stopped) return
        if (!host.alive()) { stop(); return }
        live?.let { current ->
            if (current.port.valid(current.video)) return
            unbind()
        }
        if (!progressSeen || !host.resumed() || host.state() != PLAYING) return
        val duration = host.duration()
        if (duration <= 0 || abs(duration - host.realDuration()) > CLIP_TOLERANCE_MS ||
            abs(host.position() - host.realPosition()) > CLIP_TOLERANCE_MS) {
            diagnostic("bind.denied.clip", "")
            return
        }
        when (val verdict = StorySponsorGate.evaluate(host.playable(), host.loadedId(), host.details())) {
            is StorySponsorVerdict.Deny -> diagnostic("bind.denied.${verdict.reason}", "")
            is StorySponsorVerdict.Allow -> bind(verdict)
        }
    }

    private fun bind(verdict: StorySponsorVerdict.Allow) {
        val port = Port(verdict.video, verdict.playableId)
        val controller = SponsorPlaybackController(verdict.video, automatic, port, ::admitted, main, after, now,
            hint, applied, diagnostic, TAIL_GUARD_MS)
        live = Bound(verdict.video, verdict.playableId, controller, port)
        diagnostic("session.bound", "")
        controller.start()
    }

    /** 同一条目短时间内重新绑定不重复消耗额度，仓库缓存本身会命中。 */
    private fun admitted(video: SponsorVideoId, deliver: (SponsorFetchResult) -> Unit): SponsorSegmentRepository.Ticket {
        val current = now()
        val last = granted[video]
        val free = last != null && current - last < REGRANT_MS
        if (!free && !budget.tryAcquire()) {
            diagnostic("query.budget", "")
            deliver(SponsorFetchResult.Unavailable("budget"))
            return SponsorSegmentRepository.Ticket { }
        }
        granted.remove(video)
        granted[video] = current
        while (granted.size > MAX_GRANTED) granted.remove(granted.keys.first())
        return lookup(video, deliver)
    }

    private inner class Port(private val video: SponsorVideoId, private val playableId: String) : SponsorPlayerPort {
        override fun valid(video: SponsorVideoId): Boolean = !stopped && live?.port === this && video == this.video &&
            host.alive() && host.resumed() && host.loadedId() == playableId
        override fun position(): Long = if (progressSeen) host.position() else -1L
        override fun duration(): Long = if (progressSeen) host.duration() else 0L
        override fun playing(): Boolean = host.state() == PLAYING
        override fun seek(targetMs: Int, valid: () -> Boolean, executing: () -> Unit): Boolean {
            if (!progressSeen || !valid()) return false
            // seekTo 是同步的：onSeekStart 会在这一行内回调，所以必须先标记已进入执行。
            executing()
            host.seek(targetMs)
            return true
        }
    }

    companion object {
        const val PLAYING = 4
        const val TAIL_GUARD_MS = 1_500L
        const val CLIP_TOLERANCE_MS = 1_000L
        const val BUDGET_LIMIT = 12
        const val BUDGET_WINDOW_MS = 60_000L
        private const val REGRANT_MS = 600_000L
        private const val MAX_GRANTED = 32
    }
}
