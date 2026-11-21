package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import android.media.MediaFormat
import android.opengl.GLES30
import io.github.jichuo1.meguviz.media.AndroidMediaSession
import io.github.jichuo1.meguviz.media.FrameGenerationProfile
import io.github.jichuo1.meguviz.media.FrameGenerationSession
import io.github.jichuo1.meguviz.media.WindowBackend
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

/** A failed preflight still owns native work. Retirement must continue on the same EGL thread. */
internal class FrameWindowRetirementPending(val retirement: AutoCloseable, cause: Throwable) : Exception(cause)

internal object FrameRifeAhbBackend {
    private const val INFERRED = 2
    private val syncPaths = intArrayOf(AndroidMediaSession.AHB_GPU_SYNC, AndroidMediaSession.AHB_HOST_WAIT)
    private var preparing = 0

    @Synchronized private fun beginPreparation() { preparing++ }

    @Synchronized private fun finishPreparation() {
        // A scheduling failure must not hide an outstanding EGL-owner retirement receipt.
        if (--preparing == 0) runCatching { AndroidMediaSession.discardPreparedCreationAsync() }
    }

    fun open(holder: FrameInterpolationEngineHolder, width: Int, height: Int, outputWidth: Int, outputHeight: Int,
             sourceFps: Float, targetFps: Int, standard: FrameMediaPolicy.Standard,
             isCurrent: () -> Boolean = { true }, androidColorStandard: Int? = null,
             bypassFrameBudget: Boolean = false): WindowBackend? {
        val engine = holder.windowEngine() ?: return null
        // ISO/IEC 23001-8 colour primaries and matrix coefficients for the captured SDR content.
        val color = when (standard) {
            FrameMediaPolicy.Standard.BT709 -> 1
            FrameMediaPolicy.Standard.BT601 -> when (androidColorStandard) {
                MediaFormat.COLOR_STANDARD_BT601_PAL -> 5
                MediaFormat.COLOR_STANDARD_BT601_NTSC -> 6
                else -> return null
            }
            else -> return null
        }
        if (!sourceFps.isFinite() || sourceFps <= 0f || targetFps <= 0 || !isCurrent()) return null
        val period = (1e9 / sourceFps).roundToLong()
        val budget = if (bypassFrameBudget) TimeUnit.MILLISECONDS.toNanos(120)
            else minOf(period, (FrameInterpolationEngineHolder.CAPABILITY_LIMIT_MS * 1e6).toLong())
        val profile = FrameGenerationProfile(width, height, outputWidth, outputHeight, color, color, true,
            period, 1_000_000_000L / targetFps, budget)
        val sizes = holder.windowPixelBudgets().mapNotNull { pixels ->
            FrameProcessingSize.fit(width, height, pixels, maxOf(outputWidth, outputHeight), minOf(outputWidth, outputHeight))
        }.distinct()
        if (sizes.isEmpty() || !AndroidMediaSession.isCreationProtocolSupported()) return null
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        val searchEnd = deadline - TimeUnit.SECONDS.toNanos(2)
        fun current(until: Long) = isCurrent() && holder.windowEngine() === engine && System.nanoTime() < until

        // A 4K pool can occupy hundreds of MiB. Compare modes sequentially, with one live pool.
        val fixture = FrameAhbProbeSurface()
        var owned: Probe? = null
        var transferred: FrameGenerationSession? = null
        fun retireTrial() {
            owned?.close()
            owned = null
        }
        fun measure(size: FrameProcessingSize.Size, path: Int, flows: List<Int>, until: Long): FrameGenerationProfile.Qualification? {
            check(owned == null)
            try {
                val trial = openPrepared(engine, size, width, height, path) { current(until) } ?: return null
                owned = trial
                for (flow in flows) {
                    if (!current(until)) break
                    val q = trial.qualify(profile, fixture, path, size, flow) { current(until) }
                    holder.reportWindowProbe("path=$path size=${size.width}x${size.height} flow=$flow stage=${trial.stage} sampleNs=${trial.lastNs} captureInferDrawNs=${trial.stagesNs} p95Ns=${q?.pairToDrawP95Ns ?: -1}")
                    if (q != null) return q
                    if (trial.stage != "over-budget") break
                    trial.session.beginEpoch(trial.session.epoch() + 1)
                }
            } catch (failure: AndroidMediaSession.MediaException) {
                holder.reportWindowProbe("path=$path status=${failure.status}")
            }
            return null
        }

        beginPreparation()
        try {
            fixture.prepare(width, height, outputWidth, outputHeight)
            val qualified = mutableListOf<FrameGenerationProfile.Qualification>()
            // Give each sync mode a chance at the smallest allowed extent first. Then search
            // the larger extents in quality order and reserve time to recheck the selected session.
            val floor = sizes.last()
            for (size in listOf(floor) + sizes.dropLast(1)) {
                if (!current(searchEnd)) break
                for ((index, path) in syncPaths.withIndex()) {
                    val now = System.nanoTime()
                    val until = if (size == floor && index == 0) now + (searchEnd - now) / 2 else searchEnd
                    if (!current(until)) continue
                    measure(size, path, FrameInterpolationEngineHolder.STANDARD_TIERS, until)?.let(qualified::add)
                    retireTrial()
                }
                if (size != floor && qualified.any { it.rifeWidth == size.width && it.rifeHeight == size.height }) break
            }
            while (qualified.isNotEmpty() && current(deadline)) {
                val best = profile.selectRife(*qualified.toTypedArray()) ?: break
                qualified.remove(best)
                val size = FrameProcessingSize.Size(best.rifeWidth, best.rifeHeight)
                val fresh = measure(size, best.rifePath, listOf(best.flowScale), deadline)
                if (fresh != null && current(deadline)) {
                    val live = checkNotNull(owned).session
                    live.beginEpoch(live.epoch() + 1)
                    transferred = FrameGenerationSession.openQualified(profile, fresh, live, null)
                    if (transferred != null) {
                        owned = null
                        holder.reportWindowProbe("selected path=${fresh.rifePath} size=${fresh.rifeWidth}x${fresh.rifeHeight} flow=${fresh.flowScale} p95Ns=${fresh.pairToDrawP95Ns}")
                        break
                    }
                }
                retireTrial()
            }
        } finally {
            val retire = AutoCloseable {
                retireTrial()
                check(AndroidMediaSession.servicePendingCreation() == 0) { "AHB creation still retiring" }
                fixture.close()
            }
            try {
                retire.close()
            } catch (failure: Throwable) {
                val selected = transferred
                var selectedClosed = selected == null
                throw FrameWindowRetirementPending(AutoCloseable {
                    if (!selectedClosed) { selected!!.close(); selectedClosed = true }
                    retire.close()
                }, failure)
            } finally {
                finishPreparation()
            }
        }
        return transferred
    }

    private fun openPrepared(engine: FrameInterpolationBackend, size: FrameProcessingSize.Size,
                             width: Int, height: Int, path: Int, current: () -> Boolean): Probe? {
        AndroidMediaSession.prepareCreationAsync()
        val limit = System.nanoTime() + 500_000_000L
        while (current()) {
            try {
                return Probe(engine.openWindowSession(size.width, size.height, width, height, path))
            } catch (failure: AndroidMediaSession.MediaException) {
                if (failure.status != -7 || System.nanoTime() >= limit) throw failure
                AndroidMediaSession.servicePendingCreation()
                Thread.sleep(8)
            }
        }
        return null
    }

    private class Probe(val session: AndroidMediaSession) : AutoCloseable {
        private val frames = mutableListOf<AndroidMediaSession.Frame>()
        private var job = 0L
        private var cancelled = false
        private var closed = false
        private var resourcesVerified = false
        var stage = "resources"
            private set
        var lastNs = 0L
            private set
        var stagesNs = ""
            private set

        fun qualify(profile: FrameGenerationProfile, fixture: FrameAhbProbeSurface, path: Int,
                    size: FrameProcessingSize.Size, flow: Int, current: () -> Boolean): FrameGenerationProfile.Qualification? {
            stage = "resources"
            if (!resourcesVerified) {
                val fields = session.probe()
                if (fields.size < 13 || fields[0] != 0L || fields[1] != size.width.toLong() ||
                    fields[2] != size.height.toLong() || fields[3] < size.width || (6..9).any { fields[it] != 0L } ||
                    path == AndroidMediaSession.AHB_GPU_SYNC && (fields[10] != 0L || fields[11] != 0L)) return null
                resourcesVerified = true
            }
            val times = LongArray(5)
            fixture.input(0)
            var left = session.capture(fixture.oes, fixture.transform, 1, 0, profile.primaries, profile.matrix)
            frames.add(left)
            for (sample in 0..times.size) {
                if (!current()) return null
                // The SDK qualifies pair-to-draw submission. Verify the completed pixels separately.
                stage = "capture"
                fixture.clearForVerification()
                fixture.input(sample + 1)
                val rightStart = System.nanoTime()
                val right = session.capture(fixture.oes, fixture.transform, sample + 2L,
                    (sample + 1L) * profile.sourcePeriodNs / 1000, profile.primaries, profile.matrix)
                frames.add(right)
                val capturedAt = System.nanoTime()
                val limit = rightStart + TimeUnit.MILLISECONDS.toNanos(if (sample == 0) 800 else 160)
                stage = "submit"
                job = session.submit(left, right, 0.5, limit, flow)
                var result: AndroidMediaSession.Completed? = null
                while (current() && System.nanoTime() < limit) {
                    result = drain()
                    if (result != null) break
                    Thread.sleep(1)
                }
                stage = "completion:${result?.status}/${result?.frameKind}/${result?.effectiveFlowScale}"
                if (result == null || result.status != 0 || result.frameKind != INFERRED || result.effectiveFlowScale != flow)
                    return null
                val output = result.frame ?: return null
                val completedAt = System.nanoTime()
                fixture.bindOutput()
                stage = "draw"
                while (true) {
                    try {
                        session.draw(output, profile.outputWidth, profile.outputHeight)
                        break
                    } catch (failure: AndroidMediaSession.MediaException) {
                        if (failure.status !in TEMPORARY || System.nanoTime() >= limit || !current()) throw failure
                        session.poll(0)
                        Thread.sleep(1)
                    }
                }
                GLES30.glFlush()
                val submittedAt = System.nanoTime()
                val elapsed = submittedAt - rightStart
                lastNs = elapsed
                stagesNs = "${capturedAt - rightStart}/${completedAt - capturedAt}/${submittedAt - completedAt}"
                stage = "pixels"
                if (GLES30.glGetError() != GLES30.GL_NO_ERROR || !fixture.validDraw()) return null
                output.close()
                frames.remove(output)
                left.close()
                frames.remove(left)
                left = right
                if (sample > 0) {
                    times[sample - 1] = elapsed
                    if (elapsed > profile.midpointBudgetNs) {
                        releaseFrames()
                        stage = "over-budget"
                        return null // Nearest-rank p95 over five measurements is their maximum.
                    }
                }
            }
            releaseFrames()
            stage = "qualified"
            return profile.qualifyRife(size.width, size.height, path, flow, times.max())
        }

        private fun drain(): AndroidMediaSession.Completed? {
            val state = session.poll(0)
            val result = session.pollCompleted()
            if (result != null) {
                result.frame?.let(frames::add)
                if (result.jobId != 0L) {
                    check(result.jobId == job)
                    job = 0
                    return result
                }
            }
            if (state < 0 && state !in TEMPORARY) throw IllegalStateException("RIFE AHB poll $state")
            return null
        }

        private fun releaseFrames() {
            while (frames.isNotEmpty()) {
                frames.last().close()
                frames.removeAt(frames.lastIndex)
            }
        }

        override fun close() {
            if (closed) return
            if (job != 0L) {
                if (!cancelled) { session.cancel(job); cancelled = true }
                val limit = System.nanoTime() + 100_000_000L
                while (job != 0L && System.nanoTime() < limit) {
                    if (drain() == null) Thread.sleep(1)
                }
                check(job == 0L) { "AHB calibration awaiting retirement" }
            }
            releaseFrames()
            val limit = System.nanoTime() + 180_000_000L
            while (!closed) {
                // Deferred frame release/fences can outlive the completion notification.
                // AndroidMediaSession also permits retrying close after only its final wake failed.
                try { session.poll(0) } catch (_: IllegalStateException) { /* native close may already have completed */ }
                try {
                    session.close()
                    closed = true
                } catch (failure: AndroidMediaSession.MediaException) {
                    if (failure.status !in TEMPORARY || System.nanoTime() >= limit) throw failure
                    Thread.sleep(2)
                }
            }
        }
    }

    private val TEMPORARY = setOf(-7, -8, -12)
}
