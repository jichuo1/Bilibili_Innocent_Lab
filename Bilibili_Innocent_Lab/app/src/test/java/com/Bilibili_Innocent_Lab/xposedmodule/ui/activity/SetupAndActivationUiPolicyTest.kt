package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.content.SharedPreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.AiDeclaredVideoPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentScanEntry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentSnapshotCodec
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PublishTimePoint
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.ActivationDisplayState
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.HostConfigChannel
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart.RestartRoute
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart.RestartRoutePolicy
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart.RootRestartAccess
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart.ShizukuAvailability
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.ModernFrameworkStatus
import com.lumen.coacervation.engine.touch.GlowConfig
import com.lumen.coacervation.engine.touch.GlowFrame
import com.lumen.coacervation.engine.touch.GlowShape
import com.lumen.coacervation.engine.touch.GlowState
import com.lumen.coacervation.engine.touch.reachablePileRoomPx
import java.lang.reflect.Proxy
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupAndActivationUiPolicyTest {

    @Test
    fun verifyAllCases() {
        ActivationCardVisualSpecCases().verifyAllCases()
        ActivationFrameworkSourceCases().verifyAllCases()
        AdaptiveGlowEdgePileCases().verifyAllCases()
        AdaptiveGlowFlowModelCases().verifyAllCases()
        AdaptiveGlowPolicyCases().verifyAllCases()
        AdvancedCategoryLayoutPolicyCases().verifyAllCases()
        NoRootSetupPolicyCases().verifyAllCases()
        PortraitContentFilterSelectionCases().verifyAllCases()
        PublishTimeDateDraftCases().verifyAllCases()
        RecommendationBlocklistDraftCases().verifyAllCases()
        RestartEntryPolicyCases().verifyAllCases()
        RuntimeSettingsNavigationCatalogCases().allSevenRuntimeControllersAreNavigationOnlyAndNeverGainBooleanAuthority()
    }
}

private class ActivationCardVisualSpecCases {
    fun `unsupported embedded mode remains actionable in the card and summary`() {
        assertEquals(DiagnosticStatusTone.ACTION_REQUIRED,
            ActivationCardVisualSpec.tone(ActivationDisplayState.EMBEDDED_UNSUPPORTED))
        assertEquals(ActivationSummaryState.ACTION_REQUIRED,
            ActivationCardVisualSpec.diagnosticsSummaryState(ActivationDisplayState.EMBEDDED_UNSUPPORTED,
                publishFailed = false, skinFallback = false, noRootNeedsAttention = false))
    }

    fun `NPatch bypass does not warn but preserves real failures and attention`() {
        fun summary(bypass: Boolean, attention: Boolean = false) = ActivationCardVisualSpec.diagnosticsSummaryState(
            ActivationDisplayState.ACTIVE_NPATCH, publishFailed = true, skinFallback = false,
            noRootNeedsAttention = attention, standardPublisherBypassed = bypass)
        assertEquals(ActivationSummaryState.READY, summary(true))
        assertEquals(ActivationSummaryState.ATTENTION, summary(false))
        assertEquals(ActivationSummaryState.ATTENTION, summary(true, attention = true))
    }

    fun `activation states map to stable semantic tones`() {
        assertEquals(DiagnosticStatusTone.OK, ActivationCardVisualSpec.tone(ActivationDisplayState.ACTIVE_LSPOSED))
        assertEquals(DiagnosticStatusTone.OK, ActivationCardVisualSpec.tone(ActivationDisplayState.ACTIVE_NPATCH))
        assertEquals(DiagnosticStatusTone.OK, ActivationCardVisualSpec.tone(ActivationDisplayState.ACTIVE_LSPATCH))
        assertEquals(DiagnosticStatusTone.INFO, ActivationCardVisualSpec.tone(ActivationDisplayState.LSPATCH_WAITING_FOR_HOST))
        assertEquals(DiagnosticStatusTone.ACTION_REQUIRED, ActivationCardVisualSpec.tone(ActivationDisplayState.LSPATCH_HOST_FAILED))
        assertEquals(DiagnosticStatusTone.INFO, ActivationCardVisualSpec.tone(ActivationDisplayState.CHECKING))
        assertEquals(DiagnosticStatusTone.ACTION_REQUIRED, ActivationCardVisualSpec.tone(ActivationDisplayState.UNAVAILABLE))
        assertEquals(DiagnosticStatusTone.ACTION_REQUIRED, ActivationCardVisualSpec.tone(ActivationDisplayState.SCOPE_MISSING))
        assertEquals(DiagnosticStatusTone.INFO, ActivationCardVisualSpec.tone(ActivationDisplayState.CONFIRMING))
        assertEquals(DiagnosticStatusTone.ATTENTION, ActivationCardVisualSpec.tone(ActivationDisplayState.UNCONFIRMED))
        assertEquals(DiagnosticStatusTone.ACTION_REQUIRED, ActivationCardVisualSpec.tone(ActivationDisplayState.NOT_RUNNING))
    }

    fun `accent layers become narrower and stronger toward the edge`() {
        assertTrue(ActivationCardVisualSpec.OUTER_GLOW_WIDTH_DP > ActivationCardVisualSpec.INNER_GLOW_WIDTH_DP)
        assertTrue(ActivationCardVisualSpec.INNER_GLOW_WIDTH_DP > ActivationCardVisualSpec.BORDER_WIDTH_DP)
        assertTrue(ActivationCardVisualSpec.OUTER_GLOW_ALPHA < ActivationCardVisualSpec.INNER_GLOW_ALPHA)
        assertTrue(ActivationCardVisualSpec.INNER_GLOW_ALPHA < ActivationCardVisualSpec.BORDER_ALPHA)
    }

    fun `summary priority preserves actionable unavailable states`() {
        assertEquals(
            ActivationSummaryState.ACTION_REQUIRED,
            ActivationCardVisualSpec.diagnosticsSummaryState(
                displayState = ActivationDisplayState.UNAVAILABLE,
                publishFailed = false,
                skinFallback = true,
                noRootNeedsAttention = true
            )
        )
        assertEquals(
            ActivationSummaryState.ACTION_REQUIRED,
            ActivationCardVisualSpec.diagnosticsSummaryState(
                displayState = ActivationDisplayState.LSPATCH_HOST_FAILED,
                publishFailed = true,
                skinFallback = true,
                noRootNeedsAttention = true
            )
        )
        assertEquals(
            ActivationSummaryState.ACTION_REQUIRED,
            ActivationCardVisualSpec.diagnosticsSummaryState(
                displayState = ActivationDisplayState.SCOPE_MISSING,
                publishFailed = false,
                skinFallback = false,
                noRootNeedsAttention = true
            )
        )
        assertEquals(
            ActivationSummaryState.ACTION_REQUIRED,
            ActivationCardVisualSpec.diagnosticsSummaryState(
                displayState = ActivationDisplayState.NOT_RUNNING,
                publishFailed = true,
                skinFallback = false,
                noRootNeedsAttention = false
            )
        )
        assertEquals(
            ActivationSummaryState.ATTENTION,
            ActivationCardVisualSpec.diagnosticsSummaryState(
                displayState = ActivationDisplayState.UNCONFIRMED,
                publishFailed = false,
                skinFallback = false,
                noRootNeedsAttention = false
            )
        )
        assertEquals(
            ActivationSummaryState.INFO,
            ActivationCardVisualSpec.diagnosticsSummaryState(
                displayState = ActivationDisplayState.CONFIRMING,
                publishFailed = false,
                skinFallback = false,
                noRootNeedsAttention = false
            )
        )
    }

    fun `summary priority orders publish failure waiting and ordinary attention`() {
        assertEquals(
            ActivationSummaryState.ATTENTION,
            ActivationCardVisualSpec.diagnosticsSummaryState(
                displayState = ActivationDisplayState.LSPATCH_WAITING_FOR_HOST,
                publishFailed = true,
                skinFallback = false,
                noRootNeedsAttention = false
            )
        )
        assertEquals(
            ActivationSummaryState.INFO,
            ActivationCardVisualSpec.diagnosticsSummaryState(
                displayState = ActivationDisplayState.LSPATCH_WAITING_FOR_HOST,
                publishFailed = false,
                skinFallback = true,
                noRootNeedsAttention = true
            )
        )
        assertEquals(
            ActivationSummaryState.ATTENTION,
            ActivationCardVisualSpec.diagnosticsSummaryState(
                displayState = ActivationDisplayState.ACTIVE_LSPOSED,
                publishFailed = false,
                skinFallback = true,
                noRootNeedsAttention = false
            )
        )
        assertEquals(
            ActivationSummaryState.READY,
            ActivationCardVisualSpec.diagnosticsSummaryState(
                displayState = ActivationDisplayState.ACTIVE_LSPOSED,
                publishFailed = false,
                skinFallback = false,
                noRootNeedsAttention = false
            )
        )
    }

    fun verifyAllCases() {
        fun runIsolated(block: (ActivationCardVisualSpecCases) -> Unit) {
            val s = ActivationCardVisualSpecCases()
            block(s)
        }
        runIsolated { s -> s.`unsupported embedded mode remains actionable in the card and summary`() }
        runIsolated { s -> s.`NPatch bypass does not warn but preserves real failures and attention`() }
        runIsolated { s -> s.`activation states map to stable semantic tones`() }
        runIsolated { s -> s.`accent layers become narrower and stronger toward the edge`() }
        runIsolated { s -> s.`summary priority preserves actionable unavailable states`() }
        runIsolated { s -> s.`summary priority orders publish failure waiting and ordinary attention`() }
    }
}

private class ActivationFrameworkSourceCases {
    fun `another capable manager cannot name an active LSPatch host`() {
        listOf("NPatch", "NPatch 1.0.9", "LSPosed", "LSPatch-compatible").forEach { name ->
            val source = ActivationFrameworkSource.lspatch(service(name))
            assertEquals("LSPatch", source.name)
            assertNull(source.apiVersion)
        }
    }

    fun `matching online manager supplies its verified API version`() {
        val source = ActivationFrameworkSource.lspatch(service(" lspatch "))
        assertEquals("LSPatch", source.name)
        assertEquals(102, source.apiVersion)
    }

    fun `missing or incomplete service metadata cannot supply API version`() {
        val service = service("LSPatch")
        listOf(
            service.copy(connected = false),
            service.copy(capable = false),
            service.copy(apiVersion = 0),
            service.copy(apiVersion = -1)
        ).forEach {
            val source = ActivationFrameworkSource.lspatch(it)
            assertEquals("LSPatch", source.name)
            assertNull(source.apiVersion)
        }
    }

    private fun service(name: String) = ModernFrameworkStatus(
        connected = true, capable = true, name = name, apiVersion = 102
    )

    fun verifyAllCases() {
        fun runIsolated(block: (ActivationFrameworkSourceCases) -> Unit) {
            val s = ActivationFrameworkSourceCases()
            block(s)
        }
        runIsolated { s -> s.`another capable manager cannot name an active LSPatch host`() }
        runIsolated { s -> s.`matching online manager supplies its verified API version`() }
        runIsolated { s -> s.`missing or incomplete service metadata cannot supply API version`() }
    }
}

private class AdaptiveGlowEdgePileCases {
    private val density = 3f
    private val radiusPx = 192f
    private val barWidth = 960f
    private val barHeight = 192f
    private val cornerPx = barHeight / 2f
    private val oriented = GlowConfig.create(density, 12f, 1.5f, 720f, 84f)
    private val flowing = GlowConfig.create(
        density = density, maxTravelPx = 12f, travelEpsPx = 0f,
        velocityRefPxPerSec = 720f, edgeBandPx = 0f, axialBoost = 0f, oriented = false
    )

    private fun settle(cfg: GlowConfig, x: Float, y: Float, frames: Int = 120): GlowShape {
        val state = GlowState()
        val holder = GlowFrame()
        repeat(frames) {
            holder.press = 1f
            holder.centerX = x
            holder.centerY = y
            holder.boundsWidth = barWidth
            holder.boundsHeight = barHeight
            holder.cornerRadius = cornerPx
            state.update(holder, 1f / 120f, radiusPx, 72, cfg)
        }
        return state.shape
    }

    fun glowSurvivesLeavingTheOutline() {
        for (cfg in listOf(oriented, flowing)) {
            val outside = settle(cfg, barWidth / 2f, -200f)
            assertTrue("越界后仍可见", outside.visible)
            assertTrue(outside.alphaByte > 0)
            assertTrue("堆积量应饱和", outside.pileUnit > 0.95f)
        }
    }

    fun edgeFloorEqualsThePileTarget() {
        assertEquals(
            "EDGE_MIN_SCALE 必须等于 PILE_RECOVER，跨界尺寸才连续",
            GlowConfig.PILE_RECOVER, GlowConfig.EDGE_MIN_SCALE, 0f
        )
    }

    fun sizeNeverRegrowsAcrossTheBoundary() {
        for (cfg in listOf(oriented, flowing)) {
            var lastIso = Float.MAX_VALUE
            for (step in 0..48) {
                val y = barHeight / 2f - step * (barHeight / 2f + cfg.pileRefPx * 1.5f) / 48f
                val shape = settle(cfg, barWidth / 2f, y)
                val iso = sqrt(shape.radiusX * shape.radiusY)
                assertTrue(
                    "等效半径不得回涨（V 形割裂）@step=$step y=$y iso=$iso last=$lastIso",
                    iso <= lastIso + 0.5f
                )
                lastIso = iso
            }
        }
    }

    fun pileSaturatesWithinTheReachableRoom() {
        for (cfg in listOf(oriented, flowing)) {
            val nearEdge = GlowState()
            val holder = GlowFrame()
            holder.boundsWidth = barWidth
            holder.boundsHeight = barHeight
            holder.cornerRadius = cornerPx
            holder.press = 1f
            holder.centerX = barWidth / 2f
            holder.centerY = -40f // 越出 40px
            holder.pileRoomPx = 40f // 屏幕边缘只剩 40px 可用
            repeat(120) { nearEdge.update(holder, 1f / 120f, radiusPx, 72, cfg) }
            assertTrue("可触达空间内必须满额堆积", nearEdge.shape.pileUnit > 0.95f)

            val unbounded = GlowState()
            val open = GlowFrame()
            open.boundsWidth = barWidth
            open.boundsHeight = barHeight
            open.cornerRadius = cornerPx
            open.press = 1f
            open.centerX = barWidth / 2f
            open.centerY = -40f // 同样越出 40px，但空间不受限（默认 +∞）
            repeat(120) { unbounded.update(open, 1f / 120f, radiusPx, 72, cfg) }
            assertTrue("不限空间时同样越界量只能堆出一小截", unbounded.shape.pileUnit < 0.35f)
        }
    }

    fun pileSpanSnapIsAbsorbedByTheLowpass() {
        for (cfg in listOf(oriented, flowing)) {
            val state = GlowState()
            val holder = GlowFrame()
            holder.press = 1f
            holder.centerX = barWidth / 2f
            holder.centerY = -60f // 恒越出 60px
            holder.boundsWidth = barWidth
            holder.boundsHeight = barHeight
            holder.cornerRadius = cornerPx
            holder.pileRoomPx = 300f // 初始只有一轴越界、空间充足
            repeat(120) { state.update(holder, 1f / 120f, radiusPx, 72, cfg) }
            assertTrue("room=300 时 60px 越界只能堆一小截", state.shape.pileUnit < 0.35f)

            holder.pileRoomPx = 40f // 第二轴越界 → room 硬跳 300→40
            repeat(8) { state.update(holder, 1f / 120f, radiusPx, 72, cfg) }
            assertTrue("span 硬跳必须被低通吸收（8 帧内不得越过中点）",
                state.shape.pileUnit < 0.5f)
            repeat(80) { state.update(holder, 1f / 120f, radiusPx, 72, cfg) }
            assertTrue("room 收缩完成后最终仍应饱和", state.shape.pileUnit > 0.9f)
        }
    }

    fun reachableRoomFollowsTheExceededAxes() {
        val w = 200f; val h = 100f
        assertEquals(500f, reachablePileRoomPx(500, 500, 700, 600, 1080, 2340, 100f, -10f, w, h), 1e-3f)
        assertEquals(30f, reachablePileRoomPx(850, 500, 1050, 600, 1080, 2340, 210f, 50f, w, h), 1e-3f)
        assertEquals(30f, reachablePileRoomPx(850, 500, 1050, 600, 1080, 2340, 210f, -10f, w, h), 1e-3f)
        assertEquals(Float.POSITIVE_INFINITY,
            reachablePileRoomPx(500, 500, 700, 600, 1080, 2340, 100f, 50f, w, h))
        assertTrue(reachablePileRoomPx(1200, 500, 1400, 600, 1080, 2340, 210f, 50f, w, h) >= 0f)
    }

    fun pileGrowsMonotonicallyWithOvershoot() {
        for (cfg in listOf(oriented, flowing)) {
            var lastAlpha = -1f
            var lastPile = -1f
            var lastRatio = Float.MAX_VALUE
            for (step in 0..40) {
                val overshoot = cfg.pileRefPx * 1.5f * step / 40f
                val shape = settle(cfg, barWidth / 2f, -overshoot)
                assertTrue("alpha 随越界量单调不减 @$overshoot", shape.alphaUnit >= lastAlpha - 1e-4f)
                assertTrue("堆积量随越界量单调不减 @$overshoot", shape.pileUnit >= lastPile - 1e-4f)
                val ratio = shape.radiusX / shape.radiusY
                assertTrue("越往外越扁（法向/切向半径比单调不增）@$overshoot", ratio <= lastRatio + 1e-4f)
                lastAlpha = shape.alphaUnit
                lastPile = shape.pileUnit
                lastRatio = ratio
            }
            val edge = settle(cfg, barWidth / 2f, 0f)
            val far = settle(cfg, barWidth / 2f, -cfg.pileRefPx * 2f)
            assertTrue("远离边缘必须明显比贴边亮", far.alphaUnit > edge.alphaUnit * 1.5f)
            assertEquals(0f, edge.pileUnit, 1e-3f)
            assertEquals(1f, far.pileUnit, 1e-3f)
        }
    }

    fun pilePinsToTheNearestEdgeAndAlignsToItsNormal() {
        for (cfg in listOf(oriented, flowing)) {
            val above = settle(cfg, barWidth / 2f, -300f)
            assertEquals("钉在上边", 0f, above.centerY, 2f)
            assertEquals(barWidth / 2f, above.centerX, 2f)
            assertEquals("主轴转到轮廓法向（竖直）", 0f, abs(shortestAxisDeltaDeg(above.rotationDeg, 90f)), 2f)
            assertTrue("沿法向压扁、沿切向铺开", above.radiusX < above.radiusY)

            val below = settle(cfg, barWidth / 2f, barHeight + 300f)
            assertEquals("钉在下边", barHeight, below.centerY, 2f)

            val right = settle(cfg, barWidth + 300f, barHeight / 2f)
            assertEquals("钉在右端", barWidth, right.centerX, 2f)
            assertEquals(barHeight / 2f, right.centerY, 2f)
            assertEquals("法向水平", 0f, abs(shortestAxisDeltaDeg(right.rotationDeg, 0f)), 2f)

            val corner = settle(cfg, barWidth + 300f, -300f)
            val dx = corner.centerX - (barWidth - cornerPx)
            val dy = corner.centerY - cornerPx
            assertEquals("钉在端头圆弧上", cornerPx, sqrt(dx * dx + dy * dy), 3f)
        }
    }

    fun crossingTheOutlineIsContinuousFrameToFrame() {
        for (cfg in listOf(oriented, flowing)) {
            val state = GlowState()
            val holder = GlowFrame()
            holder.boundsWidth = barWidth
            holder.boundsHeight = barHeight
            holder.cornerRadius = cornerPx
            var lastAlpha = -1
            var lastX = Float.NaN
            var lastY = Float.NaN
            var lastRx = Float.NaN
            var lastRy = Float.NaN
            var lastRot = Float.NaN
            val path = (0..200).map { barHeight / 2f - it * 2f } + (200 downTo 0).map { barHeight / 2f - it * 2f }
            for ((i, y) in path.withIndex()) {
                holder.press = 1f
                holder.velocityY = if (i <= 200) -240f else 240f
                holder.centerX = barWidth / 2f
                holder.centerY = y
                state.update(holder, 1f / 120f, radiusPx, 72, cfg)
                val s = state.shape
                assertTrue("第 $i 帧必须可见", s.visible)
                if (lastAlpha >= 0) {
                    assertTrue("第 $i 帧 alpha 跳变 ${abs(s.alphaByte - lastAlpha)}", abs(s.alphaByte - lastAlpha) <= 12)
                    assertTrue("第 $i 帧光心跳变", abs(s.centerX - lastX) <= 8f && abs(s.centerY - lastY) <= 8f)
                    assertTrue("第 $i 帧半径跳变", abs(s.radiusX - lastRx) / radiusPx <= 0.03f && abs(s.radiusY - lastRy) / radiusPx <= 0.03f)
                    assertTrue("第 $i 帧角度跳变 ${abs(shortestAxisDeltaDeg(lastRot, s.rotationDeg))}",
                        abs(shortestAxisDeltaDeg(lastRot, s.rotationDeg)) <= 14f)
                }
                lastAlpha = s.alphaByte
                lastX = s.centerX; lastY = s.centerY
                lastRx = s.radiusX; lastRy = s.radiusY
                lastRot = s.rotationDeg
            }
            assertTrue("拖回后堆积量归零", state.shape.pileUnit < 0.02f)
        }
    }

    fun releaseOutsideStillFadesOut() {
        val state = GlowState()
        val holder = GlowFrame()
        holder.boundsWidth = barWidth; holder.boundsHeight = barHeight; holder.cornerRadius = cornerPx
        holder.centerX = barWidth / 2f; holder.centerY = -200f
        repeat(60) { holder.press = 1f; state.update(holder, 1f / 120f, radiusPx, 72, oriented) }
        assertTrue(state.shape.visible)
        repeat(60) { holder.press = 0f; state.update(holder, 1f / 120f, radiusPx, 72, oriented) }
        assertTrue("松手后即便在轮廓外也要熄灭", !state.shape.visible)
    }

    fun extremeOvershootStaysFiniteAndBounded() {
        val wild = floatArrayOf(-1e9f, 1e9f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f)
        for (cfg in listOf(oriented, flowing)) for (x in wild) for (y in wild) {
            val shape = settle(cfg, x, y, frames = 8)
            for (v in floatArrayOf(shape.centerX, shape.centerY, shape.radiusX, shape.radiusY, shape.rotationDeg, shape.coreOffsetX, shape.alphaUnit, shape.pileUnit)) {
                assertTrue("有限 @($x,$y)", v.isFinite())
            }
            assertTrue(shape.centerX in 0f..barWidth && shape.centerY in 0f..barHeight)
            assertTrue(shape.pileUnit in 0f..1f)
            assertTrue(shape.alphaByte in 0..255)
        }
    }

    private fun shortestAxisDeltaDeg(fromDeg: Float, toDeg: Float): Float {
        if (!fromDeg.isFinite() || !toDeg.isFinite()) return 0f
        var delta = (toDeg - fromDeg) % 180f
        if (delta > 90f) delta -= 180f
        if (delta < -90f) delta += 180f
        return delta
    }

    fun verifyAllCases() {
        fun runIsolated(block: (AdaptiveGlowEdgePileCases) -> Unit) {
            val s = AdaptiveGlowEdgePileCases()
            block(s)
        }
        runIsolated { s -> s.glowSurvivesLeavingTheOutline() }
        runIsolated { s -> s.edgeFloorEqualsThePileTarget() }
        runIsolated { s -> s.sizeNeverRegrowsAcrossTheBoundary() }
        runIsolated { s -> s.pileSaturatesWithinTheReachableRoom() }
        runIsolated { s -> s.pileSpanSnapIsAbsorbedByTheLowpass() }
        runIsolated { s -> s.reachableRoomFollowsTheExceededAxes() }
        runIsolated { s -> s.pileGrowsMonotonicallyWithOvershoot() }
        runIsolated { s -> s.pilePinsToTheNearestEdgeAndAlignsToItsNormal() }
        runIsolated { s -> s.crossingTheOutlineIsContinuousFrameToFrame() }
        runIsolated { s -> s.releaseOutsideStillFadesOut() }
        runIsolated { s -> s.extremeOvershootStaysFiniteAndBounded() }
    }
}

private class AdaptiveGlowFlowModelCases {

    private val density = 3f
    private val limitPx = 54f
    private val velocityRefPx = limitPx * 20f
    private val radiusPx = 420f
    private val viewWidth = 1320f
    private val viewHeight = 368f
    private val cornerPx = 45f
    private val flowing = GlowConfig.create(
        density = density,
        maxTravelPx = limitPx,
        travelEpsPx = 0f,
        velocityRefPxPerSec = velocityRefPx,
        edgeBandPx = 0f,
        axialBoost = 0f,
        oriented = false
    )
    private val oriented = GlowConfig.create(
        density = density,
        maxTravelPx = limitPx,
        travelEpsPx = 0f,
        velocityRefPxPerSec = velocityRefPx,
        edgeBandPx = 0f,
        axialBoost = 0f,
        oriented = true
    )

    private class Sample(
        val alpha: Int, val rot: Float, val rx: Float, val ry: Float,
        val core: Float, val cx: Float, val cy: Float
    )

    private fun collect(
        frames: Int,
        cfg: GlowConfig,
        drive: (Int) -> FloatArray
    ): List<Sample> {
        val state = GlowState()
        val holder = GlowFrame()
        holder.boundsWidth = viewWidth
        holder.boundsHeight = viewHeight
        holder.cornerRadius = cornerPx
        val dts = floatArrayOf(1f / 120f, 1f / 90f, 1f / 60f)
        val out = ArrayList<Sample>()
        repeat(frames) { index ->
            val v = drive(index)
            holder.press = 1f
            holder.offsetX = v[0]; holder.offsetY = v[1]
            holder.velocityX = v[2]; holder.velocityY = v[3]
            holder.centerX = v[4]; holder.centerY = v[5]
            state.update(holder, dts[index % 3], radiusPx, 32, cfg)
            val s = state.shape
            if (s.visible) out += Sample(s.alphaByte, s.rotationDeg, s.radiusX, s.radiusY,
                s.coreOffsetX, s.centerX, s.centerY)
        }
        return out
    }

    private fun List<Sample>.maxStep(pick: (Sample) -> Float): Float {
        var m = 0f
        for (i in 1 until size) m = maxOf(m, abs(pick(this[i]) - pick(this[i - 1])))
        return m
    }

    private fun dragDrive(sign: Float, ramp: (Int) -> Float): (Int) -> FloatArray = { index ->
        val r = ramp(index)
        floatArrayOf(limitPx * sign * r, 0f, velocityRefPx * sign * r, 0f,
            viewWidth / 2f, viewHeight / 2f)
    }

    fun flowingModelHasNoOrientationAtAll() {
        val samples = collect(240, flowing) { index ->
            val phase = index / 40
            when (phase % 4) {
                0 -> floatArrayOf(limitPx, 0f, velocityRefPx, 0f, viewWidth / 2f, viewHeight / 2f)
                1 -> floatArrayOf(-limitPx, 0f, -velocityRefPx, 0f, viewWidth / 2f, viewHeight / 2f)
                2 -> floatArrayOf(limitPx * .7f, limitPx * .7f, velocityRefPx * .7f, velocityRefPx * .7f,
                    viewWidth / 2f, viewHeight / 2f)
                else -> floatArrayOf(-limitPx * .7f, -limitPx * .7f, -velocityRefPx * .7f, -velocityRefPx * .7f,
                    viewWidth / 2f, viewHeight / 2f)
            }
        }
        assertTrue("必须有可见帧", samples.size > 100)
        for (s in samples) {
            assertEquals("旋转恒为 0", 0f, s.rot, 0f)
            assertEquals("亮核前移恒为 0", 0f, s.core, 0f)
            assertEquals("两个半轴恒相等", s.rx, s.ry, 1e-3f)
        }
    }

    fun orientedModelStillOrients() {
        val samples = collect(120, oriented, dragDrive(1f) { (it / 40f).coerceAtMost(1f) })
        assertTrue(samples.any { abs(it.rot) < 1f })
        assertTrue(samples.any { it.core > 1f })
        assertTrue(samples.any { it.rx > it.ry * 1.2f })
    }

    fun shapeSwellsContinuouslyWithDragMagnitude() {
        var previous = -1f
        for (step in 0..40) {
            val magnitude = step / 40f
            val samples = collect(60, flowing, dragDrive(1f) { magnitude })
            val rx = samples.last().rx
            assertTrue("半径必须随行程量单调不减", rx >= previous - 1e-3f)
            assertTrue("半径不得超过胀缩上限", rx <= radiusPx * (1f + GlowConfig.SWELL_MAX) + 1f)
            previous = rx
        }
        assertTrue("满额行程必须明显胀大", previous > radiusPx * 1.3f)
        val rest = collect(3, flowing) { floatArrayOf(0f, 0f, 0f, 0f, viewWidth / 2f, viewHeight / 2f) }
        assertEquals(radiusPx, rest.last().rx, radiusPx * 0.02f)
    }

    fun stillHoldIsThePlainCircle() {
        val samples = collect(30, flowing) { floatArrayOf(0f, 0f, 0f, 0f, viewWidth / 2f, viewHeight / 2f) }
        val last = samples.last()
        assertEquals("静止 alpha 与基础值一致", 32, last.alpha)
        assertEquals(radiusPx, last.rx, radiusPx * 0.02f)
        assertEquals(radiusPx, last.ry, radiusPx * 0.02f)
        assertEquals("光心落在触点", viewWidth / 2f, last.cx, 1f)
        assertEquals(viewHeight / 2f, last.cy, 1f)
    }

    fun centerFlowsBehindTheFingerWhileMoving() {
        val samples = collect(90, flowing) { index ->
            val x = viewWidth / 2f + index * 4f
            floatArrayOf(limitPx, 0f, velocityRefPx, 0f, x, viewHeight / 2f)
        }
        var maxLag = 0f
        for (s in samples) maxLag = maxOf(maxLag, abs(s.cx - (viewWidth / 2f +
            (samples.indexOf(s).takeIf { it >= 0 } ?: 0) * 4f)))
        assertTrue("流动滞后必须有界（≤ 底栏尾迹上限量级）", maxLag <= GlowConfig.TAIL_MAX_DP * density + 2f)
        assertTrue("运动中必须读得出滞后", maxLag > 1f)
    }

    private fun settled(
        settle: Int = 60,
        frames: Int,
        drive: (Int) -> FloatArray
    ): List<Sample> = collect(settle + frames, flowing) { index ->
        if (index < settle) dragDrive(1f) { 1f }(index) else drive(index - settle)
    }.drop(settle)

    fun rapidReversalAddsNoJerkOverMonotonicDrag() {
        val monotonic = settled(frames = 240, drive = dragDrive(1f) { 1f })
        val baseAlpha = monotonic.maxStep { it.alpha.toFloat() }
        val baseRx = monotonic.maxStep { it.rx }
        val baseCx = monotonic.maxStep { it.cx }

        val reversal = settled(frames = 240) { index ->
            val sign = if (index / 40 % 2 == 0) 1f else -1f
            dragDrive(sign) { 1f }(index)
        }
        val revAlpha = reversal.maxStep { it.alpha.toFloat() }
        val revRx = reversal.maxStep { it.rx }
        val revCx = reversal.maxStep { it.cx }

        assertTrue("alpha 反向跳变 $revAlpha vs 基线 $baseAlpha", revAlpha <= baseAlpha + 1f)
        assertTrue("半径反向跳变 $revRx vs 基线 $baseRx", revRx <= baseRx + 1f)
        assertTrue("光心反向跳变 $revCx vs 基线 $baseCx", revCx <= baseCx + 1f)
    }

    fun reversalKeepsEveryOutputFrameContinuous() {
        val samples = settled(frames = 240) { index ->
            val sign = if (index / 40 % 2 == 0) 1f else -1f
            dragDrive(sign) { 1f }(index)
        }
        for (i in 1 until samples.size) {
            val a = samples[i - 1]; val b = samples[i]
            assertTrue("第 $i 帧 alpha 跳变 ${abs(b.alpha - a.alpha)}",
                abs(b.alpha - a.alpha) <= 12)
            assertTrue("第 $i 帧半径跳变 ${abs(b.rx - a.rx) / radiusPx}",
                abs(b.rx - a.rx) / radiusPx <= 0.02f)
            val head = sqrt((b.cx - a.cx) * (b.cx - a.cx) + (b.cy - a.cy) * (b.cy - a.cy))
            assertTrue("第 $i 帧光心跳变 $head", head <= 1.5f * density + 0.5f)
        }
    }

    fun cornerClampOutsideCapsuleMustNotKillTheHighlight() {
        val state = GlowState()
        val holder = GlowFrame()
        holder.boundsWidth = viewWidth
        holder.boundsHeight = viewHeight
        holder.cornerRadius = viewHeight * .5f
        holder.press = 1f
        repeat(60) {
            holder.offsetX = limitPx * .7f
            holder.offsetY = limitPx * .7f
            holder.velocityX = velocityRefPx * .7f
            holder.velocityY = velocityRefPx * .7f
            holder.centerX = viewWidth
            holder.centerY = 0f
            state.update(holder, 1f / 120f, radiusPx, 32, flowing)
        }
        assertTrue("触点钳在胶囊外的矩形角时高光不许熄灭", state.shape.visible)
        assertTrue(state.shape.alphaByte > 0)
        val banded = GlowConfig.create(
            density = density, maxTravelPx = limitPx, travelEpsPx = 0f,
            velocityRefPxPerSec = velocityRefPx, edgeBandPx = 84f,
            axialBoost = 0f, oriented = false
        )
        val bandedState = GlowState()
        holder.cornerRadius = viewHeight * .5f
        holder.centerX = viewWidth; holder.centerY = 0f
        bandedState.update(holder, 1f / 120f, radiusPx, 32, banded)
        assertTrue("band > 0 时轮廓外只变暗、不熄灭", bandedState.shape.visible)
        assertTrue(bandedState.shape.alphaUnit < state.shape.alphaUnit)
    }

    fun wildInputsStayFiniteInTheFlowingModel() {
        val wild = floatArrayOf(
            Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
            -1e30f, 1e30f, -1f, 0f, 1f, 1e-30f
        )
        val random = Random(20260921)
        repeat(3000) {
            val state = GlowState()
            val holder = GlowFrame()
            holder.boundsWidth = if (it % 11 == 0) wild[random.nextInt(wild.size)] else viewWidth
            holder.boundsHeight = if (it % 13 == 0) wild[random.nextInt(wild.size)] else viewHeight
            holder.cornerRadius = wild[random.nextInt(wild.size)]
            holder.press = if (it % 3 == 0) wild[random.nextInt(wild.size)] else random.nextFloat() * 2f
            holder.offsetX = wild[random.nextInt(wild.size)]
            holder.offsetY = wild[random.nextInt(wild.size)]
            holder.velocityX = wild[random.nextInt(wild.size)]
            holder.velocityY = wild[random.nextInt(wild.size)]
            holder.centerX = if (it % 5 == 0) wild[random.nextInt(wild.size)] else random.nextFloat() * viewWidth
            holder.centerY = if (it % 7 == 0) wild[random.nextInt(wild.size)] else random.nextFloat() * viewHeight
            val dt = if (it % 4 == 0) wild[random.nextInt(wild.size)] else random.nextFloat() * 0.2f
            val radius = if (it % 9 == 0) wild[random.nextInt(wild.size)] else radiusPx
            state.update(holder, dt, radius, 32, flowing)
            val s = state.shape
            assertTrue("centerX", s.centerX.isFinite())
            assertTrue("centerY", s.centerY.isFinite())
            assertTrue("radiusX", s.radiusX.isFinite() && s.radiusX >= 0f)
            assertTrue("radiusY", s.radiusY.isFinite() && s.radiusY >= 0f)
            assertTrue("alphaByte", s.alphaByte in 0..255)
            assertTrue("rotationDeg", s.rotationDeg.isFinite())
            if (s.pileUnit == 0f) assertEquals("流动模型轮廓内无取向", 0f, s.rotationDeg, 0f)
            assertEquals("流动模型无亮核前移", 0f, s.coreOffsetX, 0f)
        }
    }

    fun resetReturnsToThePlainCircle() {
        val state = GlowState()
        val holder = GlowFrame()
        holder.boundsWidth = viewWidth; holder.boundsHeight = viewHeight; holder.cornerRadius = cornerPx
        repeat(40) {
            holder.press = 1f
            holder.offsetX = limitPx; holder.velocityX = velocityRefPx
            holder.centerX = viewWidth / 2f; holder.centerY = viewHeight / 2f
            state.update(holder, 1f / 120f, radiusPx, 32, flowing)
        }
        assertTrue(state.shape.radiusX > radiusPx * 1.2f)
        state.reset()
        assertFalse(state.shape.visible)
        holder.offsetX = 0f; holder.velocityX = 0f
        state.update(holder, 1f / 120f, radiusPx, 32, flowing)
        assertEquals(32, state.shape.alphaByte)
        assertEquals(radiusPx, state.shape.radiusX, radiusPx * 0.02f)
        assertEquals(0f, state.shape.rotationDeg, 0f)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (AdaptiveGlowFlowModelCases) -> Unit) {
            val s = AdaptiveGlowFlowModelCases()
            block(s)
        }
        runIsolated { s -> s.flowingModelHasNoOrientationAtAll() }
        runIsolated { s -> s.orientedModelStillOrients() }
        runIsolated { s -> s.shapeSwellsContinuouslyWithDragMagnitude() }
        runIsolated { s -> s.stillHoldIsThePlainCircle() }
        runIsolated { s -> s.centerFlowsBehindTheFingerWhileMoving() }
        runIsolated { s -> s.rapidReversalAddsNoJerkOverMonotonicDrag() }
        runIsolated { s -> s.reversalKeepsEveryOutputFrameContinuous() }
        runIsolated { s -> s.cornerClampOutsideCapsuleMustNotKillTheHighlight() }
        runIsolated { s -> s.wildInputsStayFiniteInTheFlowingModel() }
        runIsolated { s -> s.resetReturnsToThePlainCircle() }
    }
}

private class AdaptiveGlowPolicyCases {
    private val density = 3f
    private val travelEpsPx = 1.5f
    private val maxTravelPx = 12f
    private val velocityRefPx = 720f
    private val edgeBandPx = 84f
    private val radiusPx = 192f
    private val barWidth = 960f
    private val barHeight = 192f
    private val cornerPx = barHeight / 2f
    private val config = GlowConfig.create(density, maxTravelPx, travelEpsPx, velocityRefPx, edgeBandPx)

    private fun frame(block: GlowFrame.() -> Unit) = GlowFrame().apply(block)

    private fun run(
        state: GlowState,
        frames: Int,
        dt: Float,
        baseAlpha: Int = 72,
        cfg: GlowConfig = config,
        radius: Float = radiusPx,
        block: (GlowFrame) -> Unit
    ) {
        val holder = GlowFrame()
        repeat(frames) {
            holder.press = 0f
            holder.offsetX = 0f
            holder.offsetY = 0f
            holder.velocityX = 0f
            holder.velocityY = 0f
            holder.centerX = 0f
            holder.centerY = 0f
            holder.boundsWidth = 0f
            holder.boundsHeight = 0f
            holder.cornerRadius = 0f
            block(holder)
            state.update(holder, dt, radius, baseAlpha, cfg)
        }
    }

    private fun centered(block: GlowFrame.() -> Unit) = frame {
        centerX = barWidth / 2f
        centerY = barHeight / 2f
        boundsWidth = barWidth
        boundsHeight = barHeight
        cornerRadius = cornerPx
        block()
    }

    private fun restingFrame() = centered { press = 1f }

    fun heldStillIsPixelIdenticalToTheLegacyCircle() {
        val state = GlowState()
        state.update(restingFrame(), 1f / 120f, radiusPx, 72, config)
        val shape = state.shape
        assertTrue(shape.visible)
        assertEquals("静止 alpha 必须与改造前逐字一致", 72, shape.alphaByte)
        assertEquals(radiusPx, shape.radiusX, 1e-3f)
        assertEquals(radiusPx, shape.radiusY, 1e-3f)
        assertEquals(0f, shape.rotationDeg, 0f)
        val highlight = GlowState()
        highlight.update(restingFrame(), 1f / 120f, radiusPx, 32, config)
        assertEquals(32, highlight.shape.alphaByte)
    }

    fun horizontalDragStretchesAlongXAndConservesArea() {
        val state = GlowState()
        run(state, frames = 40, dt = 1f / 120f) { holder ->
            holder.press = 1f
            holder.offsetX = maxTravelPx
            holder.velocityX = velocityRefPx
            holder.centerX = barWidth / 2f; holder.centerY = barHeight / 2f
            holder.boundsWidth = barWidth; holder.boundsHeight = barHeight
            holder.cornerRadius = cornerPx
        }
        val shape = state.shape
        assertTrue(shape.radiusX > radiusPx * 1.4f)
        assertTrue(shape.radiusY < radiusPx * 0.72f)
        assertEquals("面积守恒", radiusPx * radiusPx, shape.radiusX * shape.radiusY, radiusPx * radiusPx * 0.001f)
        assertEquals(0f, shape.rotationDeg, 1f)
        assertEquals(127, shape.alphaByte)
    }

    fun verticalDragStretchesAlongYAndGetsNoAxialBoost() {
        val state = GlowState()
        run(state, frames = 40, dt = 1f / 120f) { holder ->
            holder.press = 1f
            holder.offsetY = maxTravelPx
            holder.velocityY = velocityRefPx
            holder.centerX = barWidth / 2f; holder.centerY = barHeight / 2f
            holder.boundsWidth = barWidth; holder.boundsHeight = barHeight
            holder.cornerRadius = cornerPx
        }
        val shape = state.shape
        assertEquals(90f, shape.rotationDeg, 1f)
        assertTrue(shape.radiusX > radiusPx * 1.4f)
        assertEquals(108, shape.alphaByte)
    }

    fun diagonalDirectionIsTheVelocityAngle() {
        val state = GlowState()
        val v = velocityRefPx / sqrt(2f)
        run(state, frames = 40, dt = 1f / 120f) { holder ->
            holder.press = 1f
            holder.velocityX = v; holder.velocityY = v
            holder.centerX = barWidth / 2f; holder.centerY = barHeight / 2f
            holder.boundsWidth = barWidth; holder.boundsHeight = barHeight
            holder.cornerRadius = cornerPx
        }
        assertEquals(45f, state.shape.rotationDeg, 1f)
    }

    fun coreOffsetConcentratesTheGlowAlongTheDeformation() {
        val rest = GlowState()
        rest.update(restingFrame(), 1f / 120f, radiusPx, 72, config)
        assertEquals(0f, rest.shape.coreOffsetX, 0f)

        val horizontal = GlowState()
        run(horizontal, frames = 40, dt = 1f / 120f) { holder ->
            holder.press = 1f
            holder.offsetX = maxTravelPx
            holder.velocityX = velocityRefPx
            holder.centerX = barWidth / 2f; holder.centerY = barHeight / 2f
            holder.boundsWidth = barWidth; holder.boundsHeight = barHeight
            holder.cornerRadius = cornerPx
        }
        assertEquals(config.coreShiftMaxPx, horizontal.shape.coreOffsetX, config.coreShiftMaxPx * 0.02f)

        val vertical = GlowState()
        run(vertical, frames = 40, dt = 1f / 120f) { holder ->
            holder.press = 1f
            holder.offsetY = maxTravelPx
            holder.velocityY = velocityRefPx
            holder.centerX = barWidth / 2f; holder.centerY = barHeight / 2f
            holder.boundsWidth = barWidth; holder.boundsHeight = barHeight
            holder.cornerRadius = cornerPx
        }
        assertEquals(90f, vertical.shape.rotationDeg, 1f)
        assertEquals(config.coreShiftMaxPx, vertical.shape.coreOffsetX, config.coreShiftMaxPx * 0.02f)
    }

    fun coreOffsetGrowsMonotonicallyWithDeformation() {
        var previous = -1f
        for (index in 0..40) {
            val magnitude = index / 40f
            val state = GlowState()
            run(state, frames = 30, dt = 1f / 120f) { holder ->
                holder.press = 1f
                holder.offsetX = maxTravelPx * magnitude
                holder.velocityX = velocityRefPx * magnitude
                holder.centerX = barWidth / 2f; holder.centerY = barHeight / 2f
                holder.boundsWidth = barWidth; holder.boundsHeight = barHeight
                holder.cornerRadius = cornerPx
            }
            val offset = state.shape.coreOffsetX
            assertTrue("前移量必须随形变单调不减", offset >= previous - 1e-4f)
            assertTrue("前移量不得超上限", offset <= config.coreShiftMaxPx + 1e-3f)
            previous = offset
        }
        assertTrue("满额形变必须有明显前移", previous > config.coreShiftMaxPx * 0.95f)
    }

    fun coreOffsetScalesDownNearTheEdge() {
        val atEdge = GlowState()
        atEdge.update(centered {
            press = 1f
            velocityX = velocityRefPx
            offsetX = maxTravelPx
            centerY = 0f
        }, 1f / 120f, radiusPx, 72, config)
        assertTrue(atEdge.shape.visible)
        assertTrue("贴边前移必须随尺寸收缩", atEdge.shape.coreOffsetX <= config.coreShiftMaxPx * GlowConfig.EDGE_MIN_SCALE + 1e-3f)

        val nearEdge = GlowState()
        run(nearEdge, frames = 30, dt = 1f / 120f) { holder ->
            holder.press = 1f
            holder.velocityX = velocityRefPx
            holder.offsetX = maxTravelPx
            holder.centerX = barWidth / 2f
            holder.centerY = edgeBandPx * 0.5f
            holder.boundsWidth = barWidth
            holder.boundsHeight = barHeight
            holder.cornerRadius = cornerPx
        }
        assertTrue(nearEdge.shape.visible)
        assertTrue("贴边衰减必须同时收前移", nearEdge.shape.coreOffsetX < config.coreShiftMaxPx * 0.95f)
        assertTrue(nearEdge.shape.coreOffsetX > config.coreShiftMaxPx * 0.72f)
    }

    fun edgeProximityFadesAlphaContinuously() {
        val samples = ArrayList<Pair<Float, Float>>()
        for (index in 0..60) {
            val y = cornerPx * index / 60f
            val state = GlowState()
            state.update(centered {
                press = 1f
                centerY = y
            }, 1f / 120f, radiusPx, 72, config)
            samples += y to state.shape.alphaUnit
        }
        for (index in 1 until samples.size) {
            assertTrue("alpha 必须随边距单调不减", samples[index].second >= samples[index - 1].second - 1e-4f)
        }
        assertEquals("贴边 alpha 落在地板而不是 0", GlowConfig.EDGE_ALPHA_FLOOR, samples.first().second, 1e-4f)
        assertEquals("居中无衰减", 1f, samples.last().second, 1e-4f)
        val atEdge = GlowState()
        atEdge.update(centered {
            press = 1f
            centerY = 0f
        }, 1f / 120f, radiusPx, 72, config)
        assertTrue("贴边仍可见", atEdge.shape.visible)
    }

    fun stretchGrowsFromZeroWhenTheDragStarts() {
        val state = GlowState()
        state.update(centered {
            press = 1f
            velocityX = 60f
            offsetX = 1f
        }, 1f / 120f, radiusPx, 72, config)
        assertTrue(state.shape.radiusX < radiusPx * 1.05f)
    }

    fun tailLagsBehindTheMotionAndIsBounded() {
        val state = GlowState()
        run(state, frames = 40, dt = 1f / 120f) { holder ->
            holder.press = 1f
            holder.velocityX = velocityRefPx
            holder.offsetX = maxTravelPx
            holder.centerX = barWidth / 2f; holder.centerY = barHeight / 2f
            holder.boundsWidth = barWidth; holder.boundsHeight = barHeight
            holder.cornerRadius = cornerPx
        }
        val shape = state.shape
        assertTrue(shape.centerX < barWidth / 2f - 1f)
        assertTrue(shape.centerX >= barWidth / 2f - 30f - 1f)
    }

    fun releaseFadesOutAndTailVanishes() {
        val state = GlowState()
        run(state, frames = 30, dt = 1f / 120f) { holder ->
            holder.press = 1f
            holder.velocityX = velocityRefPx
            holder.offsetX = maxTravelPx
            holder.centerX = barWidth / 2f; holder.centerY = barHeight / 2f
            holder.boundsWidth = barWidth; holder.boundsHeight = barHeight
            holder.cornerRadius = cornerPx
        }
        val stretched = state.shape.radiusX
        run(state, frames = 60, dt = 1f / 120f) { holder ->
            holder.press = 0f
            holder.centerX = barWidth / 2f; holder.centerY = barHeight / 2f
            holder.boundsWidth = barWidth; holder.boundsHeight = barHeight
            holder.cornerRadius = cornerPx
        }
        assertFalse("松手后不可见", state.shape.visible)
        assertTrue(state.shape.radiusX < stretched)
    }

    fun everyInputCombinationStaysFiniteAndInRange() {
        val random = Random(20260920)
        val wild = floatArrayOf(
            Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
            -1e30f, 1e30f, -1f, 0f, 1f, 1e-30f
        )
        repeat(4000) {
            val state = GlowState()
            val holder = GlowFrame()
            holder.press = if (it % 3 == 0) wild[random.nextInt(wild.size)] else random.nextFloat() * 2f
            holder.offsetX = wild[random.nextInt(wild.size)]
            holder.offsetY = wild[random.nextInt(wild.size)]
            holder.velocityX = wild[random.nextInt(wild.size)]
            holder.velocityY = wild[random.nextInt(wild.size)]
            holder.centerX = if (it % 5 == 0) wild[random.nextInt(wild.size)] else random.nextFloat() * barWidth
            holder.centerY = if (it % 7 == 0) wild[random.nextInt(wild.size)] else random.nextFloat() * barHeight
            holder.boundsWidth = if (it % 11 == 0) wild[random.nextInt(wild.size)] else barWidth
            holder.boundsHeight = if (it % 13 == 0) wild[random.nextInt(wild.size)] else barHeight
            holder.cornerRadius = wild[random.nextInt(wild.size)]
            val dt = if (it % 4 == 0) wild[random.nextInt(wild.size)] else random.nextFloat() * 0.2f
            val radius = if (it % 9 == 0) wild[random.nextInt(wild.size)] else radiusPx
            val base = intArrayOf(0, 32, 72, 255)[random.nextInt(4)]
            state.update(holder, dt, radius, base, config)
            val shape = state.shape
            val effectiveRadius = if (radius.isFinite()) radius.coerceIn(0f, GlowState.MAX_RADIUS_PX) else 0f
            assertTrue("centerX", shape.centerX.isFinite())
            assertTrue("centerY", shape.centerY.isFinite())
            assertTrue("radiusX", shape.radiusX.isFinite() && shape.radiusX >= 0f)
            assertTrue("radiusY", shape.radiusY.isFinite() && shape.radiusY >= 0f)
            assertTrue("rotationDeg", shape.rotationDeg.isFinite())
            assertTrue("alphaUnit", shape.alphaUnit.isFinite() && shape.alphaUnit >= 0f)
            assertTrue("alphaByte", shape.alphaByte in 0..255)
            assertTrue("rx*ry 恒不超基准面积",
                shape.radiusX * shape.radiusY <= effectiveRadius * effectiveRadius * 1.001f + 1f)
            if (shape.visible) assertTrue("可见即 alpha 为正", shape.alphaByte > 0)
        }
    }

    private fun shortestAngleDeltaDeg(fromDeg: Float, toDeg: Float): Float {
        if (!fromDeg.isFinite() || !toDeg.isFinite()) return 0f
        var delta = (toDeg - fromDeg) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 180f
        return delta
    }

    fun noisyVelocityKeepsTheAngleStable() {
        val random = Random(7)
        var drift = 0.0
        val state = GlowState()
        repeat(180) {
            val jitter = (random.nextFloat() - 0.5f) * 0.42f
            val radians = drift + jitter
            val v = velocityRefPx
            state.update(centered {
                press = 1f
                velocityX = (cos(radians) * v).toFloat()
                velocityY = (sin(radians) * v).toFloat()
            }, 1f / 120f, radiusPx, 72, config)
            val degrees = Math.toDegrees(drift)
            assertTrue("第 $it 帧方向跳变 ${state.shape.rotationDeg} vs $degrees",
                abs(shortestAngleDeltaDeg(degrees.toFloat(), state.shape.rotationDeg)) < 15f)
            drift += (random.nextFloat() - 0.5f) * 0.05f
        }
        val reversal = GlowState()
        run(reversal, frames = 30, dt = 1f / 120f) { holder ->
            holder.press = 1f
            holder.velocityX = velocityRefPx
            holder.centerX = barWidth / 2f; holder.centerY = barHeight / 2f
            holder.boundsWidth = barWidth; holder.boundsHeight = barHeight
            holder.cornerRadius = cornerPx
        }
        run(reversal, frames = 60, dt = 1f / 120f) { holder ->
            holder.press = 1f
            holder.velocityX = -velocityRefPx
            holder.centerX = barWidth / 2f; holder.centerY = barHeight / 2f
            holder.boundsWidth = barWidth; holder.boundsHeight = barHeight
            holder.cornerRadius = cornerPx
        }
        assertTrue(abs(abs(reversal.shape.rotationDeg) - 180f) < 3f)
    }

    fun nanFramesKeepThePreviousDirection() {
        val state = GlowState()
        run(state, frames = 20, dt = 1f / 120f) { holder ->
            holder.press = 1f
            holder.velocityX = velocityRefPx
            holder.centerX = barWidth / 2f; holder.centerY = barHeight / 2f
            holder.boundsWidth = barWidth; holder.boundsHeight = barHeight
            holder.cornerRadius = cornerPx
        }
        val settled = state.shape.rotationDeg
        run(state, frames = 5, dt = 1f / 120f) { holder ->
            holder.press = 1f
            holder.velocityX = Float.NaN
            holder.velocityY = Float.NaN
            holder.centerX = barWidth / 2f; holder.centerY = barHeight / 2f
            holder.boundsWidth = barWidth; holder.boundsHeight = barHeight
            holder.cornerRadius = cornerPx
        }
        assertEquals(settled, state.shape.rotationDeg, 1e-4f)
    }

    fun alphaIsContinuousAcrossTheAxialThreshold() {
        val outputs = ArrayList<Float>()
        for (index in 0..120) {
            val ratio = 0.9f + 0.6f * index / 120f
            val state = GlowState()
            run(state, frames = 24, dt = 1f / 120f) { holder ->
                holder.press = 1f
                holder.velocityX = velocityRefPx * ratio
                holder.velocityY = velocityRefPx
                holder.centerX = barWidth / 2f; holder.centerY = barHeight / 2f
                holder.boundsWidth = barWidth; holder.boundsHeight = barHeight
                holder.cornerRadius = cornerPx
            }
            outputs += state.shape.alphaUnit
        }
        var maxStep = 0f
        for (index in 1 until outputs.size) {
            maxStep = maxOf(maxStep, abs(outputs[index] - outputs[index - 1]))
        }
        assertTrue("临界穿越单步跳变 $maxStep", maxStep < 0.03f)
    }

    fun alphaIsLipschitzInSpeedAndTravel() {
        for (channel in 0..1) {
            var previous = -1f
            var maxStep = 0f
            for (index in 0..600) {
                val magnitude = if (channel == 0) velocityRefPx * 2f * index / 600f
                else maxTravelPx * 2f * index / 600f
                val state = GlowState()
                run(state, frames = 30, dt = 1f / 120f) { holder ->
                    holder.press = 1f
                    if (channel == 0) holder.velocityX = magnitude else holder.offsetX = magnitude
                    holder.centerX = barWidth / 2f; holder.centerY = barHeight / 2f
                    holder.boundsWidth = barWidth; holder.boundsHeight = barHeight
                    holder.cornerRadius = cornerPx
                }
                val alpha = state.shape.alphaUnit
                if (previous >= 0f) maxStep = maxOf(maxStep, abs(alpha - previous))
                previous = alpha
            }
            assertTrue("通道 $channel 单步跳变 $maxStep", maxStep < 0.02f)
        }
    }

    fun frameToFrameOutputsHaveNoVisibleJumps() {
        val state = GlowState()
        val pressSpring = ModernNavigationSpring(0f, 1f)
        var lastAlpha = -1
        var lastRotation = 0f
        var lastRadiusX = radiusPx
        var lastTailOffset = 0f
        var frameIndex = 0
        fun step(holder: GlowFrame) {
            val dts = floatArrayOf(1f / 120f, 1f / 90f, 1f / 60f)
            val dt = dts[frameIndex % 3]
            state.update(holder, dt, radiusPx, 72, config)
            val shape = state.shape
            if (shape.visible) {
                if (lastAlpha >= 0) {
                    assertTrue("第 $frameIndex 帧 alpha 跳变 ${abs(shape.alphaByte - lastAlpha)}",
                        abs(shape.alphaByte - lastAlpha) <= 12)
                    val angleBound = GlowConfig.MAX_ANGULAR_SPEED_DEG_PER_SEC * dt + 0.5f
                    assertTrue("第 $frameIndex 帧角度跳变 ${abs(shortestAngleDeltaDeg(lastRotation, shape.rotationDeg))}",
                        abs(shortestAngleDeltaDeg(lastRotation, shape.rotationDeg)) <= angleBound)
                    assertTrue("第 $frameIndex 帧半径跳变 ${abs(shape.radiusX - lastRadiusX) / radiusPx}",
                        abs(shape.radiusX - lastRadiusX) / radiusPx <= 0.02f)
                    val tailOffset = abs(shape.centerX - holder.centerX)
                    assertTrue("第 $frameIndex 帧拖尾跳变 ${abs(tailOffset - lastTailOffset)}",
                        abs(tailOffset - lastTailOffset) <= 1.5f * density + 0.5f)
                }
                lastAlpha = shape.alphaByte
                lastRotation = shape.rotationDeg
                lastRadiusX = shape.radiusX
                lastTailOffset = abs(shape.centerX - holder.centerX)
            }
            frameIndex++
        }
        val holder = GlowFrame()
        holder.boundsWidth = barWidth; holder.boundsHeight = barHeight; holder.cornerRadius = cornerPx
        for (index in 0 until 24) {
            val seconds = index / 120f
            holder.press = pressSpring.value(seconds).coerceIn(0f, 1f)
            holder.centerX = barWidth / 2f; holder.centerY = barHeight / 2f
            step(holder)
        }
        for (index in 0 until 60) {
            val progress = index / 60f
            holder.press = 1f
            holder.offsetX = maxTravelPx * progress
            holder.velocityX = velocityRefPx * progress
            holder.centerX = barWidth / 2f + progress * 40f
            holder.centerY = barHeight / 2f
            step(holder)
        }
        val releaseSpring = ModernNavigationSpring(1f, 0f, 0f)
        for (index in 0 until 40) {
            val seconds = index / 120f
            val progress = index / 40f
            holder.press = releaseSpring.value(seconds).coerceIn(0f, 1f)
            holder.offsetX = maxTravelPx * (1f - progress)
            holder.velocityX = velocityRefPx * (1f - 2f * progress)
            holder.centerX = barWidth / 2f + 40f
            holder.centerY = barHeight / 2f
            step(holder)
        }
        assertFalse(state.shape.visible)
    }

    fun frameRateDoesNotChangeTheResponse() {
        fun simulate(dt: Float, steps: Int): GlowShape {
            val state = GlowState()
            val holder = GlowFrame()
            holder.boundsWidth = barWidth; holder.boundsHeight = barHeight; holder.cornerRadius = cornerPx
            repeat(steps) { index ->
                val progress = ((index + 1) / 60f).coerceIn(0f, 1f)
                holder.press = 1f
                holder.offsetX = maxTravelPx * progress
                holder.velocityX = velocityRefPx * progress
                holder.centerX = barWidth / 2f
                holder.centerY = barHeight / 2f
                state.update(holder, dt, radiusPx, 72, config)
            }
            return state.shape
        }
        val fast = simulate(1f / 120f, 120)
        val slow = simulate(1f / 60f, 60)
        assertTrue(abs(fast.radiusX - slow.radiusX) / radiusPx < 0.03f)
        assertTrue(abs(fast.rotationDeg - slow.rotationDeg) < 5f)
        assertTrue(abs(fast.alphaByte - slow.alphaByte) <= 5)
    }

    fun resetOnlyMarksTheInvisibleBoundary() {
        val state = GlowState()
        run(state, frames = 20, dt = 1f / 120f) { holder ->
            holder.press = 1f
            holder.velocityX = velocityRefPx
            holder.centerX = barWidth / 2f; holder.centerY = barHeight / 2f
            holder.boundsWidth = barWidth; holder.boundsHeight = barHeight
            holder.cornerRadius = cornerPx
        }
        state.reset()
        assertFalse(state.shape.visible)
        assertEquals(0f, state.shape.alphaUnit, 0f)
        state.update(restingFrame(), 1f / 120f, radiusPx, 72, config)
        assertEquals(72, state.shape.alphaByte)
        assertEquals(radiusPx, state.shape.radiusX, 1e-3f)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (AdaptiveGlowPolicyCases) -> Unit) {
            val s = AdaptiveGlowPolicyCases()
            block(s)
        }
        runIsolated { s -> s.heldStillIsPixelIdenticalToTheLegacyCircle() }
        runIsolated { s -> s.horizontalDragStretchesAlongXAndConservesArea() }
        runIsolated { s -> s.verticalDragStretchesAlongYAndGetsNoAxialBoost() }
        runIsolated { s -> s.diagonalDirectionIsTheVelocityAngle() }
        runIsolated { s -> s.coreOffsetConcentratesTheGlowAlongTheDeformation() }
        runIsolated { s -> s.coreOffsetGrowsMonotonicallyWithDeformation() }
        runIsolated { s -> s.coreOffsetScalesDownNearTheEdge() }
        runIsolated { s -> s.edgeProximityFadesAlphaContinuously() }
        runIsolated { s -> s.stretchGrowsFromZeroWhenTheDragStarts() }
        runIsolated { s -> s.tailLagsBehindTheMotionAndIsBounded() }
        runIsolated { s -> s.releaseFadesOutAndTailVanishes() }
        runIsolated { s -> s.everyInputCombinationStaysFiniteAndInRange() }
        runIsolated { s -> s.noisyVelocityKeepsTheAngleStable() }
        runIsolated { s -> s.nanFramesKeepThePreviousDirection() }
        runIsolated { s -> s.alphaIsContinuousAcrossTheAxialThreshold() }
        runIsolated { s -> s.alphaIsLipschitzInSpeedAndTravel() }
        runIsolated { s -> s.frameToFrameOutputsHaveNoVisibleJumps() }
        runIsolated { s -> s.frameRateDoesNotChangeTheResponse() }
        runIsolated { s -> s.resetOnlyMarksTheInvisibleBoundary() }
    }
}

private class AdvancedCategoryLayoutPolicyCases {
    fun `keeps the last control or description immediately before the next heading`() {
        assertEquals(
            listOf(
                AdvancedCategoryChildRange(1, 5),
                AdvancedCategoryChildRange(6, 10),
                AdvancedCategoryChildRange(11, 15),
                AdvancedCategoryChildRange(16, 20)
            ),
            AdvancedCategoryLayoutPolicy.resolve(
                markerIndices = listOf(0, 5, 10, 15),
                childCount = 20
            )
        )
    }

    fun `every non heading child belongs to exactly one group for different menu sizes`() {
        for (groupCount in 1..12) {
            val markers = List(groupCount) { it * 4 }
            val childCount = groupCount * 4
            val ranges = requireNotNull(AdvancedCategoryLayoutPolicy.resolve(markers, childCount))
            val moved = ranges.flatMap { (it.startInclusive until it.endExclusive).toList() }
            assertEquals((0 until childCount).filterNot { it in markers }, moved)
            assertEquals(moved.size, moved.distinct().size)
        }
    }

    fun `rejects incomplete or unordered marker layout`() {
        assertNull(AdvancedCategoryLayoutPolicy.resolve(emptyList(), childCount = 20))
        assertNull(AdvancedCategoryLayoutPolicy.resolve(listOf(1, 5), childCount = 20))
        assertNull(AdvancedCategoryLayoutPolicy.resolve(listOf(0, 0), childCount = 20))
        assertNull(
            AdvancedCategoryLayoutPolicy.resolve(
                markerIndices = listOf(0, 10, 5, 15),
                childCount = 20
            )
        )
        assertNull(
            AdvancedCategoryLayoutPolicy.resolve(
                markerIndices = listOf(0, 5, 10, 20),
                childCount = 20
            )
        )
    }

    fun verifyAllCases() {
        fun runIsolated(block: (AdvancedCategoryLayoutPolicyCases) -> Unit) {
            val s = AdvancedCategoryLayoutPolicyCases()
            block(s)
        }
        runIsolated { s -> s.`keeps the last control or description immediately before the next heading`() }
        runIsolated { s -> s.`every non heading child belongs to exactly one group for different menu sizes`() }
        runIsolated { s -> s.`rejects incomplete or unordered marker layout`() }
    }
}

private class NoRootSetupPolicyCases {
    fun `LSPatch uses its own manager without an ineffective NPatch opt in`() {
        val policy = NoRootSetupPolicy.forChannel(HostConfigChannel.LSPATCH)
        assertTrue(policy.lspatch)
        assertFalse(policy.needsOptIn)
        assertEquals("org.lsposed.lspatch", policy.managerPackage)
    }

    fun `NPatch and setup before patching retain explicit opt in`() {
        listOf(HostConfigChannel.NPATCH, HostConfigChannel.STANDARD).forEach { channel ->
            val policy = NoRootSetupPolicy.forChannel(channel)
            assertFalse(policy.lspatch)
            assertTrue(policy.needsOptIn)
            assertEquals("top.nkbe.npatch", policy.managerPackage)
        }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (NoRootSetupPolicyCases) -> Unit) {
            val s = NoRootSetupPolicyCases()
            block(s)
        }
        runIsolated { s -> s.`LSPatch uses its own manager without an ineffective NPatch opt in`() }
        runIsolated { s -> s.`NPatch and setup before patching retain explicit opt in`() }
    }
}

private class PortraitContentFilterSelectionCases {

    fun `catalog keeps thirteen unique existing preference keys`() {
        val options = PortraitContentFilterCatalog.options

        assertEquals(13, options.size)
        assertEquals(13, options.map { it.preferenceKey }.distinct().size)
        assertEquals(
            mapOf(
                PortraitContentFilterGroup.HOME to 1,
                PortraitContentFilterGroup.STORY to 7,
                PortraitContentFilterGroup.SERIES to 5
            ),
            options.groupingBy { it.group }.eachCount()
        )
    }

    fun `all series covers children without erasing their draft values`() {
        val movieKey = FeaturePreferences.REMOVE_STORY_MOVIES
        val parentKey = FeaturePreferences.REMOVE_STORY_BANGUMI
        val movie = PortraitContentFilterCatalog.options.first { it.preferenceKey == movieKey }
        val draft = PortraitContentFilterDraft(mapOf(movieKey to true))

        draft[parentKey] = true
        assertTrue(draft.isCovered(movie))
        assertTrue(draft[movieKey])

        draft[parentKey] = false
        assertFalse(draft.isCovered(movie))
        assertTrue(draft[movieKey])
    }

    fun `changed values contain only explicit draft differences`() {
        val adsKey = FeaturePreferences.REMOVE_STORY_ADS
        val liveKey = FeaturePreferences.REMOVE_STORY_LIVE
        val draft = PortraitContentFilterDraft(mapOf(adsKey to true, liveKey to false))

        draft[adsKey] = false
        draft[liveKey] = true

        assertEquals(mapOf(adsKey to false, liveKey to true), draft.changedValues())
        assertEquals(1, draft.selectedCount())
    }

    fun verifyAllCases() {
        fun runIsolated(block: (PortraitContentFilterSelectionCases) -> Unit) {
            val s = PortraitContentFilterSelectionCases()
            block(s)
        }
        runIsolated { s -> s.`catalog keeps thirteen unique existing preference keys`() }
        runIsolated { s -> s.`all series covers children without erasing their draft values`() }
        runIsolated { s -> s.`changed values contain only explicit draft differences`() }
    }
}

private class PublishTimeDateDraftCases {
    private fun draft(year: String, month: String, day: String) = PublishTimeDateDraft().apply {
        yearText = year; monthText = month; dayText = day
    }

    fun `an unset date stays unset instead of defaulting to today`() {
        val value = PublishTimeDateDraft()
        assertNull(value.toDate())
        assertEquals(listOf("", "", ""), listOf(value.yearText, value.monthText, value.dayText))
    }

    fun `saved dates initialize all three fields and round trip`() {
        val date = LocalDate.of(2026, 10, 21)
        val value = PublishTimeDateDraft(date)
        assertEquals(listOf("2026", "10", "21"), listOf(value.yearText, value.monthText, value.dayText))
        assertEquals(date, value.toDate())
    }

    fun `incomplete and invalid input is preserved through validation and later editing`() {
        val value = draft("202", "02", "30")
        assertNull(value.toDate())
        value.yearText = "2024"
        assertNull(value.toDate())
        assertEquals(listOf("2024", "02", "30"), listOf(value.yearText, value.monthText, value.dayText))
        value.dayText = "29"
        assertEquals(LocalDate.of(2024, 2, 29), value.toDate())
        assertEquals("02", value.monthText)
    }

    fun `leap days and month lengths are validated without clamping`() {
        assertEquals(LocalDate.of(2024, 2, 29), draft("2024", "2", "29").toDate())
        for ((year, month, day) in listOf(
            Triple("2023", "2", "29"), Triple("2100", "2", "29"),
            Triple("2026", "4", "31"), Triple("2026", "0", "1"), Triple("2026", "13", "1"),
            Triple("2026", "1", "0"), Triple("2026", "1", "32")
        )) {
            val value = draft(year, month, day)
            assertNull(value.toDate())
            assertEquals(day, value.dayText)
        }
    }

    fun `date limits match the persisted publish time protocol`() {
        val first = LocalDate.of(PublishTimePoint.MIN_YEAR, 1, 1)
        val last = LocalDate.of(PublishTimePoint.MAX_YEAR, 12, 31)
        assertEquals(first, PublishTimeDateDraft(first).toDate())
        assertEquals(last, PublishTimeDateDraft(last).toDate())
        assertNull(draft((PublishTimePoint.MIN_YEAR - 1).toString(), "12", "31").toDate())
        assertNull(draft((PublishTimePoint.MAX_YEAR + 1).toString(), "1", "1").toDate())
    }

    fun `non integer fields cannot silently become a date`() {
        for (value in listOf(
            draft("+2026", "1", "1"), draft("2026 ", "1", "1"),
            draft("2026", "1.0", "1"), draft("2026", "1", "-1"), draft("2026", "", "1")
        )) {
            assertNull(value.toDate())
        }
    }

    fun `explicit date replacement updates all fields together`() {
        val value = draft("202", "", "31")
        value.setDate(LocalDate.of(2026, 10, 21))
        assertEquals(LocalDate.of(2026, 10, 21), value.toDate())
        assertEquals(listOf("2026", "10", "21"), listOf(value.yearText, value.monthText, value.dayText))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (PublishTimeDateDraftCases) -> Unit) {
            val s = PublishTimeDateDraftCases()
            block(s)
        }
        runIsolated { s -> s.`an unset date stays unset instead of defaulting to today`() }
        runIsolated { s -> s.`saved dates initialize all three fields and round trip`() }
        runIsolated { s -> s.`incomplete and invalid input is preserved through validation and later editing`() }
        runIsolated { s -> s.`leap days and month lengths are validated without clamping`() }
        runIsolated { s -> s.`date limits match the persisted publish time protocol`() }
        runIsolated { s -> s.`non integer fields cannot silently become a date`() }
        runIsolated { s -> s.`explicit date replacement updates all fields together`() }
    }
}

private class RecommendationBlocklistDraftCases {
    private fun picks(token: String? = "event-1", id: String = "8318") = MineComponentSnapshot(
        targetPackage = "tv.danmaku.bili", processName = "tv.danmaku.bili",
        surface = MineComponentSnapshotCodec.SURFACE_SECTION_PICKS,
        generatedAt = 1L, capabilities = emptySet(),
        entries = listOf(checkNotNull(MineComponentScanEntry.create("section", "鬼畜", id, null, true))
            .copy(selectionToken = token))
    )

    fun removalPreservesOtherNamesIdsAndAuthors() {
        val prefs = MemoryPreferences("8318,东方 Project,163", "UP A,123")
        val draft = RecommendationBlocklistDraft("8318,东方 Project,163", "UP A,123", listOf(picks()), "")
        draft.setSelected(RecommendationBlockRule(RecommendationBlockKind.TAG, "8318"), false)
        draft.setSelected(RecommendationBlockRule(RecommendationBlockKind.AUTHOR, "123"), false)
        assertTrue(draft.save(prefs.instance))
        assertEquals("东方 project,163", prefs.tags())
        assertEquals("up a", prefs.authors())
    }

    fun cancelledDraftDoesNotWriteOrAcknowledge() {
        val prefs = MemoryPreferences("163", "UP A")
        val draft = RecommendationBlocklistDraft("163", "UP A", listOf(picks()), "")
        draft.rows.forEach { draft.setSelected(it.rule, false) }
        assertEquals("163", prefs.tags())
        assertEquals("UP A", prefs.authors())
        assertNull(prefs.values[RecommendationBlocklistDraft.REVIEWED_EVENTS_KEY])
    }

    fun rejectedPickDoesNotReturnWhenAnotherPickUpdatesTheSnapshot() {
        val prefs = MemoryPreferences("", "")
        val first = RecommendationBlocklistDraft("", "", listOf(picks()), "")
        first.rows.forEach { first.setSelected(it.rule, false) }
        assertTrue(first.save(prefs.instance))
        val updated = picks().copy(generatedAt = 2L, entries = picks().entries + picks("event-2", "163").entries)
        val reopened = RecommendationBlocklistDraft("", "", listOf(updated), prefs.reviewed())
        assertEquals(listOf("163"), reopened.rows.map { it.rule.value })
    }

    fun aNewExplicitPickOfTheSameTagCanBeConfirmedAgain() {
        val first = RecommendationBlocklistDraft("", "", listOf(picks()), "")
        val reopened = RecommendationBlocklistDraft("", "", listOf(picks("new-session-event")), first.acknowledgedValue())
        assertEquals("8318", reopened.selectedValue(RecommendationBlockKind.TAG))
        assertTrue(reopened.rows.single().pending)
    }

    fun reviewedSavedPickRemainsEditableButNotPending() {
        val first = RecommendationBlocklistDraft("", "", listOf(picks()), "")
        val reopened = RecommendationBlocklistDraft("8318", "", listOf(picks()), first.acknowledgedValue())
        assertFalse(reopened.rows.single().pending)
        assertEquals("鬼畜 (8318)", reopened.rows.single().label)
    }

    fun legacySnapshotsCanBeDismissedWithoutBlockingNewEvents() {
        val legacy = RecommendationBlocklistDraft("", "", listOf(picks(null)), "")
        assertTrue(RecommendationBlocklistDraft("", "", listOf(picks(null).copy(generatedAt = 20)), legacy.acknowledgedValue()).rows.isEmpty())
        assertEquals(1, RecommendationBlocklistDraft("", "", listOf(picks()), legacy.acknowledgedValue()).rows.size)
    }

    fun missingOrUnrelatedSnapshotsNeverClearManualRules() {
        val draft = RecommendationBlocklistDraft("东方 Project,8318", "UP A", listOf(picks().copy(surface = "mine")), "")
        assertEquals("东方 project,8318", draft.selectedValue(RecommendationBlockKind.TAG))
        assertEquals("up a", draft.selectedValue(RecommendationBlockKind.AUTHOR))
        assertTrue(draft.rows.none { it.pending })
    }

    fun failedCommitDoesNotAcknowledgeOrLoseLists() {
        val prefs = MemoryPreferences("163", "up a", fail = true)
        val draft = RecommendationBlocklistDraft("163", "up a", listOf(picks()), "")
        assertFalse(draft.save(prefs.instance))
        assertEquals("163", prefs.tags())
        assertEquals("", prefs.reviewed())
    }

    fun concurrentEditIsPreserved() {
        val prefs = MemoryPreferences("new tag", "up a")
        val draft = RecommendationBlocklistDraft("163", "up a", listOf(picks()), "")
        assertFalse(draft.save(prefs.instance))
        assertEquals("new tag", prefs.tags())
    }

    fun selectionIdentitySurvivesSnapshotEncodingAndOldPayloadsRemainReadable() {
        val token = picks().entries.single()
        assertEquals(token, MineComponentScanEntry.fromJsonOrNull(token.toJson()))
        val old = token.toJson().apply { remove("selectionToken") }
        assertNull(checkNotNull(MineComponentScanEntry.fromJsonOrNull(old)).selectionToken)
        assertNull(MineComponentScanEntry.fromJsonOrNull(old.put("selectionToken", "x".repeat(65))))
    }

    fun authorPicksAreReviewedSeparatelyAndNamesKeepTheirSpaces() {
        val author = picks().copy(surface = MineComponentSnapshotCodec.SURFACE_AUTHOR_PICKS,
            entries = listOf(checkNotNull(MineComponentScanEntry.create("author", "UP A", "UP A", null, true))
                .copy(selectionToken = "author-event")))
        val prefs = MemoryPreferences("8318", "")
        val draft = RecommendationBlocklistDraft("8318", "", listOf(author), "")
        assertEquals("up a", draft.selectedValue(RecommendationBlockKind.AUTHOR))
        draft.setSelected(RecommendationBlockRule(RecommendationBlockKind.AUTHOR, "up a"), false)
        assertTrue(draft.save(prefs.instance))
        assertEquals("8318", prefs.tags())
        assertTrue(RecommendationBlocklistDraft("", "", listOf(author), prefs.reviewed()).rows.isEmpty())
    }

    fun boundedReviewHistoryAlwaysKeepsTheCurrentSnapshotAcknowledged() {
        val first = RecommendationBlocklistDraft("", "", listOf(picks()), "")
        val reviewed = first.acknowledgedValue() + "\n" + (1..512).joinToString("\n") { "older-$it" }
        val draft = RecommendationBlocklistDraft("", "", listOf(picks(), picks("new-event", "163")), reviewed)
        val saved = draft.acknowledgedValue()
        assertEquals(512, saved.lines().size)
        assertTrue(RecommendationBlocklistDraft("", "", listOf(picks()), saved).rows.isEmpty())
    }

    fun autoConfirmIsOffUntilItIsTurnedOnAndThenSurvivesAReread() {
        val prefs = MemoryPreferences("", "")
        assertFalse(RecommendationBlocklistDraft.isAutoConfirmEnabled(prefs.instance))
        assertFalse(RecommendationBlocklistDraft.autoConfirm(prefs.instance, listOf(picks())))
        assertEquals("", prefs.tags())

        assertTrue(RecommendationBlocklistDraft.setAutoConfirmEnabled(prefs.instance, true))
        assertTrue(RecommendationBlocklistDraft.isAutoConfirmEnabled(prefs.instance))
    }

    fun autoConfirmMergesPendingPicksAndMarksThemReviewed() {
        val prefs = MemoryPreferences("163", "UP A")
        RecommendationBlocklistDraft.setAutoConfirmEnabled(prefs.instance, true)
        assertTrue(RecommendationBlocklistDraft.autoConfirm(prefs.instance, listOf(picks())))
        assertEquals("163,8318", prefs.tags())
        assertEquals("up a", prefs.authors())
        assertTrue(RecommendationBlocklistDraft.of(prefs.instance, listOf(picks()))
            .rows.none(RecommendationBlockRow::pending))
    }

    fun autoConfirmDoesNothingWhenThereIsNothingNew() {
        val prefs = MemoryPreferences("", "")
        RecommendationBlocklistDraft.setAutoConfirmEnabled(prefs.instance, true)
        assertTrue(RecommendationBlocklistDraft.autoConfirm(prefs.instance, listOf(picks())))
        val reviewedAfterFirst = prefs.reviewed()
        assertFalse(RecommendationBlocklistDraft.autoConfirm(prefs.instance, listOf(picks())))
        assertEquals(reviewedAfterFirst, prefs.reviewed())
        assertFalse(RecommendationBlocklistDraft.autoConfirm(prefs.instance, emptyList()))
    }

    fun autoConfirmDoesNotResurrectAnEntryTheUserAlreadyRemoved() {
        val prefs = MemoryPreferences("", "")
        val draft = RecommendationBlocklistDraft("", "", listOf(picks()), "")
        draft.rows.forEach { draft.setSelected(it.rule, false) }
        assertTrue(draft.save(prefs.instance))
        assertEquals("", prefs.tags())

        RecommendationBlocklistDraft.setAutoConfirmEnabled(prefs.instance, true)
        assertFalse(RecommendationBlocklistDraft.autoConfirm(prefs.instance, listOf(picks())))
        assertEquals("", prefs.tags())
        assertTrue(RecommendationBlocklistDraft.autoConfirm(prefs.instance, listOf(picks("event-2"))))
        assertEquals("8318", prefs.tags())
    }

    fun autoConfirmReportsFailureWhenTheStoreRejectsTheCommit() {
        val prefs = MemoryPreferences("", "", fail = true)
        prefs.values[RecommendationBlocklistDraft.AUTO_CONFIRM_KEY] = true
        assertFalse(RecommendationBlocklistDraft.autoConfirm(prefs.instance, listOf(picks())))
    }

    private fun authorPick(name: String, token: String, aiOrigin: Boolean) = checkNotNull(
        MineComponentScanEntry.create("author", name, name,
            if (aiOrigin) AiDeclaredVideoPolicy.AUTHOR_PICK_ORIGIN_URI else null, true)
    ).copy(selectionToken = token)

    private fun authorPicks(vararg entries: MineComponentScanEntry) = picks().copy(
        surface = MineComponentSnapshotCodec.SURFACE_AUTHOR_PICKS, entries = entries.toList()
    )

    private fun MemoryPreferences.enableAiStrongMode(main: Boolean = true) {
        values[FeaturePreferences.BLOCK_AI_DECLARED_VIDEOS] = main
        values[FeaturePreferences.BLOCK_AI_DECLARED_VIDEOS_STRONG_MODE] = true
    }

    fun strongModeMergesOnlyAiOriginAuthorsAndLeavesOtherPicksPending() {
        val prefs = MemoryPreferences("163", "UP A")
        prefs.enableAiStrongMode()
        val snapshots = listOf(picks(), authorPicks(
            authorPick("AI UP", "ai-1", aiOrigin = true),
            authorPick("Manual UP", "manual-1", aiOrigin = false)
        ))
        assertFalse(RecommendationBlocklistDraft.isAutoConfirmEnabled(prefs.instance))
        assertTrue(RecommendationBlocklistDraft.needsBackgroundMerge(prefs.instance))
        assertTrue(RecommendationBlocklistDraft.autoConfirm(prefs.instance, snapshots))
        assertEquals("up a,ai up", prefs.authors())
        assertEquals("163", prefs.tags())
        val reopened = RecommendationBlocklistDraft.of(prefs.instance, snapshots)
        assertEquals(setOf("8318", "manual up"), reopened.rows.filter { it.pending }.map { it.rule.value }.toSet())
        val reviewed = prefs.reviewed()
        assertFalse(RecommendationBlocklistDraft.autoConfirm(prefs.instance, snapshots))
        assertEquals(reviewed, prefs.reviewed())
    }

    fun strongModeWithoutTheMainSwitchDoesNothing() {
        val prefs = MemoryPreferences("", "")
        prefs.enableAiStrongMode(main = false)
        assertFalse(RecommendationBlocklistDraft.needsBackgroundMerge(prefs.instance))
        assertFalse(RecommendationBlocklistDraft.autoConfirm(prefs.instance,
            listOf(authorPicks(authorPick("AI UP", "ai-1", aiOrigin = true)))))
        assertEquals("", prefs.authors())
    }

    fun aRemovedAiOriginAuthorIsNotMergedAgainFromTheSameRecord() {
        val prefs = MemoryPreferences("", "")
        prefs.enableAiStrongMode()
        val snapshots = listOf(authorPicks(authorPick("AI UP", "ai-1", aiOrigin = true)))
        assertTrue(RecommendationBlocklistDraft.autoConfirm(prefs.instance, snapshots))
        val draft = RecommendationBlocklistDraft.of(prefs.instance, snapshots)
        draft.setSelected(RecommendationBlockRule(RecommendationBlockKind.AUTHOR, "ai up"), false)
        assertTrue(draft.save(prefs.instance))
        assertEquals("", prefs.authors())
        assertFalse(RecommendationBlocklistDraft.autoConfirm(prefs.instance, snapshots))
        assertEquals("", prefs.authors())
    }

    fun generalAutoConfirmStillMergesEverything() {
        val prefs = MemoryPreferences("", "")
        prefs.enableAiStrongMode()
        RecommendationBlocklistDraft.setAutoConfirmEnabled(prefs.instance, true)
        assertTrue(RecommendationBlocklistDraft.autoConfirm(prefs.instance, listOf(authorPicks(
            authorPick("AI UP", "ai-1", aiOrigin = true),
            authorPick("Manual UP", "manual-1", aiOrigin = false)
        ))))
        assertEquals("ai up,manual up", prefs.authors())
    }

    private class MemoryPreferences(tags: String, authors: String, private val fail: Boolean = false) {
        val values = mutableMapOf<String, Any>(
            FeaturePreferences.HOME_RECOMMEND_BLOCKED_TIDS to tags,
            FeaturePreferences.HOME_RECOMMEND_BLOCKED_AUTHORS to authors
        )
        fun tags() = values[FeaturePreferences.HOME_RECOMMEND_BLOCKED_TIDS] as? String
        fun authors() = values[FeaturePreferences.HOME_RECOMMEND_BLOCKED_AUTHORS] as? String
        fun reviewed() = (values[RecommendationBlocklistDraft.REVIEWED_EVENTS_KEY] as? String).orEmpty()
        val instance = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString" -> values[args!![0]] as? String ?: args[1]
                "getBoolean" -> values[args!![0]] as? Boolean ?: args[1]
                "edit" -> editor()
                else -> error(method.name)
            }
        } as SharedPreferences
        private fun editor(): SharedPreferences.Editor {
            val pending = mutableMapOf<String, Any>()
            return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                when (method.name) {
                    "putString" -> { pending[args!![0] as String] = args[1] as String; proxy }
                    "putBoolean" -> { pending[args!![0] as String] = args[1] as Boolean; proxy }
                    "commit" -> { values.putAll(pending); !fail }
                    else -> error(method.name)
                }
            } as SharedPreferences.Editor
        }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (RecommendationBlocklistDraftCases) -> Unit) {
            val s = RecommendationBlocklistDraftCases()
            block(s)
        }
        runIsolated { s -> s.removalPreservesOtherNamesIdsAndAuthors() }
        runIsolated { s -> s.cancelledDraftDoesNotWriteOrAcknowledge() }
        runIsolated { s -> s.rejectedPickDoesNotReturnWhenAnotherPickUpdatesTheSnapshot() }
        runIsolated { s -> s.aNewExplicitPickOfTheSameTagCanBeConfirmedAgain() }
        runIsolated { s -> s.reviewedSavedPickRemainsEditableButNotPending() }
        runIsolated { s -> s.legacySnapshotsCanBeDismissedWithoutBlockingNewEvents() }
        runIsolated { s -> s.missingOrUnrelatedSnapshotsNeverClearManualRules() }
        runIsolated { s -> s.failedCommitDoesNotAcknowledgeOrLoseLists() }
        runIsolated { s -> s.concurrentEditIsPreserved() }
        runIsolated { s -> s.selectionIdentitySurvivesSnapshotEncodingAndOldPayloadsRemainReadable() }
        runIsolated { s -> s.authorPicksAreReviewedSeparatelyAndNamesKeepTheirSpaces() }
        runIsolated { s -> s.boundedReviewHistoryAlwaysKeepsTheCurrentSnapshotAcknowledged() }
        runIsolated { s -> s.autoConfirmIsOffUntilItIsTurnedOnAndThenSurvivesAReread() }
        runIsolated { s -> s.autoConfirmMergesPendingPicksAndMarksThemReviewed() }
        runIsolated { s -> s.autoConfirmDoesNothingWhenThereIsNothingNew() }
        runIsolated { s -> s.autoConfirmDoesNotResurrectAnEntryTheUserAlreadyRemoved() }
        runIsolated { s -> s.autoConfirmReportsFailureWhenTheStoreRejectsTheCommit() }
        runIsolated { s -> s.strongModeMergesOnlyAiOriginAuthorsAndLeavesOtherPicksPending() }
        runIsolated { s -> s.strongModeWithoutTheMainSwitchDoesNothing() }
        runIsolated { s -> s.aRemovedAiOriginAuthorIsNotMergedAgainFromTheSameRecord() }
        runIsolated { s -> s.generalAutoConfirmStillMergesEverything() }
    }
}

private class RestartEntryPolicyCases {
    fun `stopped Shizuku exposes recovery without becoming a restart capability`() {
        val stopped = ShizukuAvailability.Snapshot(ShizukuAvailability.Status.STOPPED)
        assertTrue(canOpenRestartControls(false, RestartRoute.SHIZUKU, stopped))
        assertFalse(stopped.recommended)
        assertEquals(RestartRoute.MANUAL, RestartRoutePolicy.preferred(
            RootRestartAccess.Snapshot(RootRestartAccess.Status.MISSING), stopped))
    }

    fun `unsettled checks never expose privileged controls`() {
        RestartRoute.entries.forEach { route ->
            ShizukuAvailability.Status.entries.forEach { status ->
                assertFalse(canOpenRestartControls(true, route, ShizukuAvailability.Snapshot(status)))
            }
        }
    }

    fun `existing authorization and unavailable service boundaries remain intact`() {
        ShizukuAvailability.Status.entries.forEach { status ->
            val expected = status in setOf(ShizukuAvailability.Status.READY,
                ShizukuAvailability.Status.UNAUTHORIZED, ShizukuAvailability.Status.DENIED,
                ShizukuAvailability.Status.STOPPED)
            assertEquals(expected, canOpenRestartControls(false, RestartRoute.SHIZUKU,
                ShizukuAvailability.Snapshot(status)))
        }
        assertFalse(canOpenRestartControls(false, RestartRoute.SHIZUKU, null))
        assertFalse(canOpenRestartControls(false, null, null))
        assertFalse(canOpenRestartControls(false, RestartRoute.MANUAL, null))
        assertTrue(canOpenRestartControls(false, RestartRoute.ROOT, null))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (RestartEntryPolicyCases) -> Unit) {
            val s = RestartEntryPolicyCases()
            block(s)
        }
        runIsolated { s -> s.`stopped Shizuku exposes recovery without becoming a restart capability`() }
        runIsolated { s -> s.`unsettled checks never expose privileged controls`() }
        runIsolated { s -> s.`existing authorization and unavailable service boundaries remain intact`() }
    }
}

private class RuntimeSettingsNavigationCatalogCases {
    fun allSevenRuntimeControllersAreNavigationOnlyAndNeverGainBooleanAuthority() {
        val descriptors = RuntimeSettingsNavigationCatalog.descriptors()
        assertEquals(7, descriptors.size)
        assertEquals(7, descriptors.map { it.id }.distinct().size)
        assertTrue(descriptors.all { !it.favoriteEligible && !it.directToggle && it.page == 4 })
        assertTrue(SettingStore(descriptors, emptyMap()).snapshot().isEmpty())
        assertEquals(setOf("APPEARANCE", "COMPATIBILITY"), descriptors.map { it.section }.toSet())
    }
}
