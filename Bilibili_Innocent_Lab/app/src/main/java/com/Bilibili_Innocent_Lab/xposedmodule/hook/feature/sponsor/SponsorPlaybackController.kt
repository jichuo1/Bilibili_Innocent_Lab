package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal interface SponsorPlayerPort {
    fun valid(video: SponsorVideoId): Boolean
    fun position(): Long
    fun duration(): Long
    fun playing(): Boolean
    fun seek(targetMs: Int, valid: () -> Boolean, executing: () -> Unit): Boolean

    /** 最近一次 [seek] 返回 false 的原因，只用于诊断；不携带任何视频标识。 */
    fun seekRefusal(): String = ""
}

/** 除执行门禁外均由主线程调用；所有异步回调在主队列重新核对身份和生命周期。 */
internal class SponsorPlaybackController(
    private val video: SponsorVideoId,
    automatic: Boolean,
    private val player: SponsorPlayerPort,
    private val lookup: (SponsorVideoId, (SponsorFetchResult) -> Unit) -> SponsorSegmentRepository.Ticket,
    private val main: (() -> Unit) -> Unit,
    private val after: (Long, () -> Unit) -> SponsorSegmentRepository.Ticket,
    private val now: () -> Long,
    private val hint: (Hint) -> Unit,
    private val applied: () -> Unit,
    private val diagnostic: (String, String) -> Unit = { _, _ -> },
    tailGuardMs: Long = 0L
) {
    enum class Hint { NONE, SKIP, UNDO }
    private val policy = SponsorPlaybackPolicy(automatic, tailGuardMs)
    private val alive = AtomicBoolean(true)
    private val gate = AtomicReference<SponsorPlaybackPolicy.Request?>()
    private val executed = AtomicBoolean(false)
    private var query: SponsorSegmentRepository.Ticket? = null
    private var queryRetry: SponsorSegmentRepository.Ticket? = null
    private var queryAttempts = 0
    private var segments = emptyList<SponsorSegment>()
    private var durationReported = false
    private var deadline: SponsorSegmentRepository.Ticket? = null
    private var undoExpiry: SponsorSegmentRepository.Ticket? = null
    private var seekRecovery: SponsorSegmentRepository.Ticket? = null
    private var pendingAutomatic = false
    private var confirmedTarget: Long? = null

    // 以下只服务诊断：绑定时刻、外部 seek 的各来源计数、上一次的拒绝原因和上一次显示的提示。
    private val automaticMode = automatic
    private val bornMs = now()
    private val externalStarts = hashMapOf<String, Int>()
    private var externalDones = 0
    private var lastDenial: String? = null
    private var shownHint = Hint.NONE

    fun start() {
        if (!live() || queryAttempts != 0) return
        diagnostic("controller.start", "mode=${if (automaticMode) "auto" else "manual"}")
        fetch()
        refresh()
    }

    /** 诊断读数不能影响控制流：宿主已释放播放器时读取可能抛异常，此时只记一个占位。 */
    private fun snapshot(playing: Boolean? = null): String = runCatching {
        "posMs=${player.position()} playing=${playing ?: player.playing()} ageMs=${now() - bornMs}"
    }.getOrDefault("snapshot=unavailable")

    private fun fetch() {
        if (!live()) return
        val attempt = ++queryAttempts
        val delivered = AtomicBoolean(false)
        diagnostic("query.started", "attempt=$attempt")
        query = lookup(video) { result -> main {
            if (!live() || attempt != queryAttempts || !delivered.compareAndSet(false, true)) return@main
            when (result) {
                is SponsorFetchResult.Available -> {
                    segments = result.segments
                    policy.load(segments)
                    diagnostic("query.available", "accepted=${segments.size}")
                    result.summary?.let { summary ->
                        diagnostic("query.parsed", "total=${summary.total} accepted=${segments.size}")
                        if (summary.category > 0) diagnostic("filter.category", "count=${summary.category}")
                        if (summary.action > 0) diagnostic("filter.action", "count=${summary.action}")
                        if (summary.cid > 0) diagnostic("filter.cid", "count=${summary.cid}")
                        if (summary.invalid > 0) diagnostic("filter.invalid", "count=${summary.invalid}")
                    }
                }
                is SponsorFetchResult.Unavailable -> {
                    diagnostic("query.failed.${result.reason}", "attempt=$attempt")
                    if (result.retryAfterMs > 0 && queryAttempts < 3) {
                        val delay = result.retryAfterMs.coerceIn(1_000, 60_000)
                        diagnostic("query.retry", "delayMs=$delay")
                        queryRetry = after(delay) {
                            queryRetry = null
                            if (live() && attempt == queryAttempts) fetch()
                        }
                    }
                }
            }
            refresh()
        } }
    }

    fun refresh() {
        if (!live()) { stop(); return }
        val duration = player.duration()
        policy.progress(player.position(), duration, now())
        if (!durationReported && segments.isNotEmpty() && duration > 0) {
            durationReported = true
            val rejected = segments.count { !it.fits(duration) }
            diagnostic("progress.ready", "durationMs=$duration")
            if (rejected > 0) diagnostic("filter.duration", "count=$rejected")
        }
        if (policy.pending == null) gate.set(null)
        val playing = player.playing()
        val request = policy.request(playing, now())
        if (request != null) send(request, automatic = true) else noteDenial(playing)
        render()
    }

    /** 位置在商单区间内却没有自动跳时，记下为什么；原因变化才记，同一原因在会话内只落一行。 */
    private fun noteDenial(playing: Boolean) {
        if (policy.pending != null) return
        val reason = policy.autoDenial(playing, now())
        if (reason == lastDenial) return
        lastDenial = reason
        if (reason != null) diagnostic("auto.denied.$reason", snapshot(playing))
    }

    fun skip() {
        if (!live()) return
        policy.progress(player.position(), player.duration(), now())
        policy.request(player.playing(), now(), manual = true)?.let { send(it, automatic = false) }
        render()
    }

    fun undo() {
        if (!live()) return
        policy.progress(player.position(), player.duration(), now())
        policy.requestUndo(now())?.let { send(it, automatic = false) }
        render()
    }

    fun seekStarted(targetMs: Long) {
        if (!live()) return
        val pending = gate.get()
        if (pending != null && executed.get() && kotlin.math.abs(pending.targetMs - targetMs) <= 1_000) return
        externalSeekStarted("observer", targetMs)
    }

    /** Hook 当场撤销门禁；主线程随后处理 UI/策略，不能让已排队协程抢先执行。 */
    fun invalidateExternalSeek(targetMs: Long? = null) {
        val pending = gate.get()
        if (targetMs != null && pending != null && executed.get() &&
            kotlin.math.abs(pending.targetMs - targetMs) <= 1_000) return
        gate.set(null)
        executed.set(false)
    }

    /**
     * [source] 区分是宿主 seek 调用被截获（call）还是内核 onSeekStart 回调（observer）；
     * [targetMs] 只在已知时记录。每个来源只记前三次，足够分清宿主续播 seek 与用户拖动。
     */
    fun externalSeekStarted(source: String = "api", targetMs: Long? = null) {
        val count = (externalStarts[source] ?: 0) + 1
        externalStarts[source] = count
        if (count <= 3) diagnostic("seek.external.start.$source.$count", "${targetMs?.let { "toMs=$it " } ?: ""}${snapshot()}")
        gate.set(null)
        executed.set(false)
        deadline?.cancel(); deadline = null
        policy.externalSeekStart(now())
        seekRecovery?.cancel()
        seekRecovery = after(4_000) { if (live()) refresh() }
        render()
    }

    fun seekCompleted() {
        if (!live()) return
        if (gate.get() == null && executed.get() && confirmedTarget?.let {
                kotlin.math.abs(player.position() - it) <= 1_000
            } == true) return // 同一宿主 seek 的重复完成通知。
        seekRecovery?.cancel(); seekRecovery = null
        val position = player.position()
        val request = gate.get()
        val ran = executed.get()
        if (request != null && ran && policy.confirm(position, now())) {
            confirmedTarget = position
            gate.set(null); deadline?.cancel(); deadline = null
            applied()
            // 自动跳过沿用原事件名；手动跳过与撤销各用独立名字，免得会话内被先到的一种吞掉。
            diagnostic(if (request.undo) "seek.undone" else if (pendingAutomatic) "seek.confirmed" else "seek.confirmed.manual",
                "fromMs=${request.originMs} toMs=$position")
            undoExpiry?.cancel()
            undoExpiry = after(6_001) { if (live()) refresh() }
        } else {
            // 模块自己的请求没能确认时单独说明；没有在途请求则只是宿主或用户的 seek。
            if (request != null) diagnostic(if (ran) "seek.unconfirmed.mismatch" else "seek.unconfirmed.not-executed",
                "deltaMs=${position - request.targetMs}")
            confirmedTarget = null
            executed.set(false)
            gate.set(null); deadline?.cancel(); deadline = null
            policy.externalSeekStart(now())
            policy.externalSeekComplete(position)
            val count = ++externalDones
            if (count <= 4) diagnostic("seek.external.done.$count", "inRange=${policy.currentRange != null} ${snapshot()}")
        }
        render()
    }

    fun stateChanged() {
        if (pendingAutomatic && !player.playing()) gate.get()?.let {
            diagnostic("request.dropped.paused", "executed=${executed.get()}")
            gate.set(null); policy.fail(it.serial)
        }
        refresh()
    }

    fun stop() {
        if (!alive.getAndSet(false)) return
        diagnostic("session.ended", "ageMs=${now() - bornMs}")
        gate.set(null)
        query?.cancel(); query = null
        queryRetry?.cancel(); queryRetry = null
        deadline?.cancel(); deadline = null
        undoExpiry?.cancel(); undoExpiry = null
        seekRecovery?.cancel(); seekRecovery = null
        hint(Hint.NONE)
    }

    private fun live(): Boolean = alive.get() && player.valid(video)

    private fun send(request: SponsorPlaybackPolicy.Request, automatic: Boolean) {
        val kind = if (request.undo) "undo" else if (automatic) "auto" else "manual"
        diagnostic("request.$kind", snapshot())
        pendingAutomatic = automatic
        gate.set(request)
        executed.set(false)
        deadline?.cancel()
        deadline = after(4_000) {
            if (gate.compareAndSet(request, null)) {
                // executed=false：请求排进了宿主协程但没被放行；true：已放行却始终没等到确认。
                diagnostic("request.timeout", "kind=$kind executed=${executed.get()}")
                policy.fail(request.serial); if (live()) refresh()
            }
        }
        val accepted = runCatching { player.seek(request.targetMs.toInt(),
            { gate.get() === request && live() && now() < request.deadlineMs && (!automatic || player.playing()) },
            { executed.set(true) }) }.getOrDefault(false)
        if (!accepted) {
            diagnostic("request.rejected", "kind=$kind why=${player.seekRefusal().ifEmpty { "unknown" }}")
            gate.compareAndSet(request, null); policy.fail(request.serial)
            deadline?.cancel(); deadline = null
        }
    }

    private fun render() {
        val next = when {
            !live() || policy.pending != null -> Hint.NONE
            policy.canUndo(now()) -> Hint.UNDO
            policy.currentRange != null -> Hint.SKIP
            else -> Hint.NONE
        }
        if (next != shownHint) {
            // 用户看到手动提示的那一刻：连同没有自动跳过的原因一起记下；none 表示本来可以自动跳。
            if (next == Hint.SKIP) {
                // render 也走在 externalSeekStarted 等原本不读播放器的路径上：这里读不到就按未播放记录。
                val playing = runCatching { player.playing() }.getOrDefault(false)
                diagnostic("hint.skip", "why=${policy.autoDenial(playing, now()) ?: "none"} ${snapshot(playing)}")
            } else if (next == Hint.UNDO) diagnostic("hint.undo", "")
            shownHint = next
        }
        hint(next)
    }
}
