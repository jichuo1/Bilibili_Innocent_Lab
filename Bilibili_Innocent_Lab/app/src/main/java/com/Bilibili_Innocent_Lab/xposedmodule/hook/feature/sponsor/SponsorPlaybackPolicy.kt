package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor

import kotlin.math.abs

/**
 * 主线程会话状态；网络与反射由外层承担，void seek 返回值不代表已跳转。
 *
 * [tailGuardMs] 大于 0 时，终点距视频结尾不足该值的区间不自动跳转，只保留手动「跳过」：
 * 短视频流里 seek 到结尾会让条目播完、循环或触发翻页，自动执行有副作用。
 */
internal class SponsorPlaybackPolicy(private val automatic: Boolean, private val tailGuardMs: Long = 0L) {
    data class Range(val startMs: Long, val endMs: Long, val ids: Set<String>)
    data class Request(val serial: Long, val originMs: Long, val targetMs: Long,
        val range: Range, val undo: Boolean, val deadlineMs: Long)
    private data class Undo(val request: Request, val expiresMs: Long)
    private var segments = emptyList<SponsorSegment>()
    private var ranges = emptyList<Range>()
    private var duration = 0L
    private var position = -1L
    private var sequence = 0L
    private var seeking = false
    private var seekDeadline = 0L
    private var autoSuspended = false
    private var userTarget: Long? = null
    private val blocked = hashSetOf<String>()
    private var undo: Undo? = null
    var pending: Request? = null
        private set
    val currentRange: Range? get() = ranges.firstOrNull { position >= it.startMs && position < it.endMs }

    fun load(items: List<SponsorSegment>) {
        segments = items.take(512).sortedBy { it.startMs }
        rebuild()
    }

    fun progress(positionMs: Long, durationMs: Long, nowMs: Long) {
        position = positionMs
        if (duration != durationMs) { duration = durationMs; rebuild() }
        expire(nowMs)
    }

    fun request(playing: Boolean, nowMs: Long, manual: Boolean = false): Request? {
        expire(nowMs)
        if (pending != null || seeking || position !in 0 until duration || (!manual && (!automatic || !playing || autoSuspended))) return null
        val range = currentRange ?: return null
        if (!manual && range.ids.any(blocked::contains)) return null
        if (!manual && tailGuardMs > 0 && duration - range.endMs < tailGuardMs) return null
        return Request(++sequence, position, range.endMs, range, false, nowMs + 4_000).also { pending = it; undo = null }
    }

    fun confirm(positionMs: Long, nowMs: Long): Boolean {
        val request = pending ?: return false
        if (nowMs >= request.deadlineMs || abs(positionMs - request.targetMs) > 1_000) return false
        pending = null
        seeking = false
        position = positionMs
        blocked += request.range.ids
        undo = if (request.undo) null else Undo(request, nowMs + 6_000)
        return true
    }

    fun canUndo(nowMs: Long): Boolean {
        expire(nowMs)
        return pending == null && !seeking && undo?.let { nowMs <= it.expiresMs && it.request.range in ranges } == true
    }

    fun requestUndo(nowMs: Long): Request? {
        if (!canUndo(nowMs)) return null
        val previous = undo!!.request
        undo = null
        blocked += previous.range.ids
        return Request(++sequence, position, previous.originMs, previous.range, true, nowMs + 4_000).also { pending = it }
    }

    fun externalSeekStart(nowMs: Long) {
        pending?.let { blocked += it.range.ids }
        pending = null
        undo = null
        seeking = true
        seekDeadline = nowMs + 4_000
    }

    fun externalSeekComplete(positionMs: Long) {
        seeking = false
        autoSuspended = false
        position = positionMs
        userTarget = positionMs
        currentRange?.let { blocked += it.ids }
    }

    fun fail(serial: Long) {
        val request = pending?.takeIf { it.serial == serial } ?: return
        blocked += request.range.ids
        pending = null
        undo = null
    }

    private fun expire(nowMs: Long) {
        if (seeking && nowMs >= seekDeadline) {
            seeking = false
            // 缺少完成回调时只恢复手动操作；不能推断用户已经结束拖动。
            autoSuspended = true
        }
        pending?.takeIf { nowMs >= it.deadlineMs }?.let { fail(it.serial) }
        if (undo?.expiresMs?.let { nowMs > it } == true) undo = null
    }

    private fun rebuild() {
        val merged = mutableListOf<Range>()
        for (segment in segments.filter { it.fits(duration) }) {
            val previous = merged.lastOrNull()
            if (previous != null && segment.startMs <= previous.endMs) {
                merged[merged.lastIndex] = Range(previous.startMs, maxOf(previous.endMs, segment.endMs), previous.ids + segment.uuid)
            } else merged += Range(segment.startMs, segment.endMs, setOf(segment.uuid))
        }
        ranges = merged
        userTarget?.let { target -> ranges.firstOrNull { target >= it.startMs && target < it.endMs }?.let { blocked += it.ids } }
        if (pending?.let { it.targetMs !in 0..duration || it.range !in ranges } == true) pending?.let { fail(it.serial) }
    }
}
