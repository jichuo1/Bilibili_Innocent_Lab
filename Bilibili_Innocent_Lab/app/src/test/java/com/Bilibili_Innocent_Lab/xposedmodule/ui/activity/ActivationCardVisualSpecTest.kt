package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.ActivationDisplayState
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.ModernFrameworkStatus
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

class ActivationCardVisualSpecTest {
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
        assertEquals(
            DiagnosticStatusTone.OK,
            ActivationCardVisualSpec.tone(ActivationDisplayState.ACTIVE_LSPOSED)
        )
        assertEquals(
            DiagnosticStatusTone.OK,
            ActivationCardVisualSpec.tone(ActivationDisplayState.ACTIVE_NPATCH)
        )
        assertEquals(
            DiagnosticStatusTone.OK,
            ActivationCardVisualSpec.tone(ActivationDisplayState.ACTIVE_LSPATCH)
        )
        assertEquals(
            DiagnosticStatusTone.INFO,
            ActivationCardVisualSpec.tone(ActivationDisplayState.LSPATCH_WAITING_FOR_HOST)
        )
        assertEquals(
            DiagnosticStatusTone.ACTION_REQUIRED,
            ActivationCardVisualSpec.tone(ActivationDisplayState.LSPATCH_HOST_FAILED)
        )
        assertEquals(
            DiagnosticStatusTone.INFO,
            ActivationCardVisualSpec.tone(ActivationDisplayState.CHECKING)
        )
        assertEquals(
            DiagnosticStatusTone.ACTION_REQUIRED,
            ActivationCardVisualSpec.tone(ActivationDisplayState.UNAVAILABLE)
        )
        assertEquals(
            DiagnosticStatusTone.ACTION_REQUIRED,
            ActivationCardVisualSpec.tone(ActivationDisplayState.SCOPE_MISSING)
        )
        assertEquals(
            DiagnosticStatusTone.INFO,
            ActivationCardVisualSpec.tone(ActivationDisplayState.CONFIRMING)
        )
        assertEquals(
            DiagnosticStatusTone.ATTENTION,
            ActivationCardVisualSpec.tone(ActivationDisplayState.UNCONFIRMED)
        )
        assertEquals(
            DiagnosticStatusTone.ACTION_REQUIRED,
            ActivationCardVisualSpec.tone(ActivationDisplayState.NOT_RUNNING)
        )
    }

    fun `accent layers become narrower and stronger toward the edge`() {
        assertTrue(
            ActivationCardVisualSpec.OUTER_GLOW_WIDTH_DP >
                ActivationCardVisualSpec.INNER_GLOW_WIDTH_DP
        )
        assertTrue(
            ActivationCardVisualSpec.INNER_GLOW_WIDTH_DP >
                ActivationCardVisualSpec.BORDER_WIDTH_DP
        )
        assertTrue(
            ActivationCardVisualSpec.OUTER_GLOW_ALPHA <
                ActivationCardVisualSpec.INNER_GLOW_ALPHA
        )
        assertTrue(
            ActivationCardVisualSpec.INNER_GLOW_ALPHA <
                ActivationCardVisualSpec.BORDER_ALPHA
        )
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

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (ActivationCardVisualSpecTest) -> Unit) {
            val s = ActivationCardVisualSpecTest()
            block(s)
        }
        runIsolated { s -> s.`unsupported embedded mode remains actionable in the card and summary`() }
        runIsolated { s -> s.`NPatch bypass does not warn but preserves real failures and attention`() }
        runIsolated { s -> s.`activation states map to stable semantic tones`() }
        runIsolated { s -> s.`accent layers become narrower and stronger toward the edge`() }
        runIsolated { s -> s.`summary priority preserves actionable unavailable states`() }
        runIsolated { s -> s.`summary priority orders publish failure waiting and ordinary attention`() }
    
        ActivationFrameworkSourceCases().verifyAllCases()
        AdaptiveGlowEdgePileCases().verifyAllCases()
        AdaptiveGlowFlowModelCases().verifyAllCases()
        AdaptiveGlowPolicyCases().verifyAllCases()
        AdvancedCategoryLayoutPolicyCases().verifyAllCases()
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

    /**
     * 贴边收缩的下限必须等于堆积收敛目标：否则跨界瞬间等效半径先跌穿目标值、
     * 再由堆积拉回——"刚出界先缩小、再集中放大"的 V 形割裂（用户 2026-09-21 反馈）。
     * 这条不变式一旦破坏，下面的单调性用例会跟着失败。
     */
    fun edgeFloorEqualsThePileTarget() {
        assertEquals(
            "EDGE_MIN_SCALE 必须等于 PILE_RECOVER，跨界尺寸才连续",
            GlowConfig.PILE_RECOVER, GlowConfig.EDGE_MIN_SCALE, 0f
        )
    }

    /**
     * 从轮廓内侧一路拖到堆积饱和：等效半径 iso = √rx·ry 必须**单调不增**——
     * 界内收缩到目标值后只能持平（取向模型）或继续收拢（流动模型），绝不允许回涨。
     */
    fun sizeNeverRegrowsAcrossTheBoundary() {
        for (cfg in listOf(oriented, flowing)) {
            var lastIso = Float.MAX_VALUE
            for (step in 0..48) {
                val y = barHeight / 2f - step * (barHeight / 2f + cfg.pileRefPx * 1.5f) / 48f
                val shape = settle(cfg, barWidth / 2f, y)
                val iso = kotlin.math.sqrt(shape.radiusX * shape.radiusY)
                assertTrue(
                    "等效半径不得回涨（V 形割裂）@step=$step y=$y iso=$iso last=$lastIso",
                    iso <= lastIso + 0.5f
                )
                lastIso = iso
            }
        }
    }

    /**
     * 贴屏场景：可触达空间只剩 40px 时，满额堆积必须在 40px 内走完（而不是默认的
     * pileRefPx）——否则屏幕边缘的控件永远堆不出完整"集中"（用户 2026-09-21：
     * 贴屏拖拽时光效随可用空间忽强忽弱）。
     */
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

    /**
     * 弧形路径上第二根轴越界/回界的瞬间，room（对已越界轴取 min）会硬跳——span 必须先过
     * 低通再进 smoothStep，否则 pileTarget 随之跳变，强度读作"瞬间减弱/增强"（用户
     * 2026-09-21 抓帧实证）。断言：room 300→40 硬切后 pile 不得立刻上窜，但最终仍收敛满额。
     */
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
        // 控件位于屏幕中（四周 500px 空间）：向上越界 → 可用空间 = 上边到屏幕顶
        assertEquals(500f, reachablePileRoomPx(500, 500, 700, 600, 1080, 2340, 100f, -10f, w, h), 1e-3f)
        // 控件右缘距屏幕右缘只剩 30px：向右越界 → 30
        assertEquals(30f, reachablePileRoomPx(850, 500, 1050, 600, 1080, 2340, 210f, 50f, w, h), 1e-3f)
        // 斜向越界：取两轴较小值（更近的屏幕缘先截断行程）
        assertEquals(30f, reachablePileRoomPx(850, 500, 1050, 600, 1080, 2340, 210f, -10f, w, h), 1e-3f)
        // 控件未越界 → 不限空间
        assertEquals(Float.POSITIVE_INFINITY,
            reachablePileRoomPx(500, 500, 700, 600, 1080, 2340, 100f, 50f, w, h))
        // 控件已在屏幕外（异常输入）→ 非负
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

            // 斜向拖出全圆角端头：钉在圆弧上而不是矩形角
            val corner = settle(cfg, barWidth + 300f, -300f)
            val dx = corner.centerX - (barWidth - cornerPx)
            val dy = corner.centerY - cornerPx
            assertEquals("钉在端头圆弧上", cornerPx, kotlin.math.sqrt(dx * dx + dy * dy), 3f)
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
            // 从居中以 2px/帧（240px/s）匀速向上拖到轮廓外 300px，再原路拖回。
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

    fun outwardNormalMatchesHandComputedDirections() {
        val out = FloatArray(2)
        roundedRectOutwardNormal(barWidth / 2f, -50f, barWidth, barHeight, cornerPx, out)
        assertEquals(0f, out[0], 1e-4f); assertEquals(-1f, out[1], 1e-4f)
        roundedRectOutwardNormal(barWidth / 2f, barHeight + 50f, barWidth, barHeight, cornerPx, out)
        assertEquals(0f, out[0], 1e-4f); assertEquals(1f, out[1], 1e-4f)
        roundedRectOutwardNormal(barWidth + 50f, barHeight / 2f, barWidth, barHeight, cornerPx, out)
        assertEquals(1f, out[0], 1e-4f); assertEquals(0f, out[1], 1e-4f)
        roundedRectOutwardNormal(-50f, barHeight / 2f, barWidth, barHeight, cornerPx, out)
        assertEquals(-1f, out[0], 1e-4f); assertEquals(0f, out[1], 1e-4f)
        // 端头圆弧外 45°：法向指向圆弧圆心到触点的方向
        val cx = barWidth - cornerPx; val cy = cornerPx
        roundedRectOutwardNormal(cx + 100f, cy - 100f, barWidth, barHeight, cornerPx, out)
        assertEquals(0.7071f, out[0], 1e-3f); assertEquals(-0.7071f, out[1], 1e-3f)
        // 无效边界：退化为固定方向且不产生 NaN
        roundedRectOutwardNormal(1f, 1f, 0f, 0f, 0f, out)
        assertTrue(out[0].isFinite() && out[1].isFinite())
    }

    fun axisDeltaIsModulo180() {
        assertEquals(0f, shortestAxisDeltaDeg(0f, 180f), 1e-4f)
        assertEquals(90f, shortestAxisDeltaDeg(0f, 90f), 1e-4f)
        assertEquals(-10f, shortestAxisDeltaDeg(0f, 170f), 1e-4f)
        assertEquals(10f, shortestAxisDeltaDeg(175f, 5f), 1e-4f)
        assertEquals(0f, shortestAxisDeltaDeg(Float.NaN, 30f), 1e-4f)
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
        runIsolated { s -> s.outwardNormalMatchesHandComputedDirections() }
        runIsolated { s -> s.axisDeltaIsModulo180() }
    }
}

private class AdaptiveGlowFlowModelCases {

    private val density = 3f
    private val limitPx = 54f                     // 18dp：典型卡片行程上限
    private val velocityRefPx = limitPx * 20f     // 与 TouchHighlight.VELOCITY_REF_FACTOR 一致
    private val radiusPx = 420f                   // 0.7 × max(宽,高)
    private val viewWidth = 1320f
    private val viewHeight = 368f
    private val cornerPx = 45f                    // 15dp 卡圆角
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

    /**
     * 逐帧喂入。[drive] 每帧给出 (位移x, 位移y, 速度x, 速度y, 触点x, 触点y)。
     * dt 混 120/90/60Hz——与真实帧节奏一致。
     */
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

    /** 触点固定在中点；位移/速度按给定符号与 ramp 走。 */
    private fun dragDrive(sign: Float, ramp: (Int) -> Float): (Int) -> FloatArray = { index ->
        val r = ramp(index)
        floatArrayOf(limitPx * sign * r, 0f, velocityRefPx * sign * r, 0f,
            viewWidth / 2f, viewHeight / 2f)
    }

    // ---------------- ① 没有取向量 ----------------

    fun flowingModelHasNoOrientationAtAll() {
        val samples = collect(240, flowing) { index ->
            // 剧烈的多方向甩动：右 → 左 → 右上 → 左下
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
        // 反向对照：取向模型仍然产出朝向（底栏/scrub 条不受影响）
        val samples = collect(120, oriented, dragDrive(1f) { (it / 40f).coerceAtMost(1f) })
        assertTrue(samples.any { abs(it.rot) < 1f })
        assertTrue(samples.any { it.core > 1f })
        assertTrue(samples.any { it.rx > it.ry * 1.2f })
    }

    // ---------------- ② 形状只由行程量决定 ----------------

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
        // 静止（无位移、无速度）回到基准圆
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

    // ---------------- ③ 运动由流动表达 ----------------

    fun centerFlowsBehindTheFingerWhileMoving() {
        // 触点持续向右移动；光心应滞后于触点（泻在后方），但滞后量有界。
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

    // ---------------- ④ 反向不引入额外跳变 ----------------

    /** 先跑 [settle] 帧同向满额拖动让状态稳定，再进入正题；只返回稳定后的样本。 */
    private fun settled(
        settle: Int = 60,
        frames: Int,
        drive: (Int) -> FloatArray
    ): List<Sample> = collect(settle + frames, flowing) { index ->
        if (index < settle) dragDrive(1f) { 1f }(index) else drive(index - settle)
    }.drop(settle)

    fun rapidReversalAddsNoJerkOverMonotonicDrag() {
        // 两条曲线都先稳定到满额拖动，再分道：一条继续同向，一条剧烈来回甩。
        // 这样比出来的差异只来自"方向改变"本身，与起步 ramp 无关。
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

        // 反向不得比同向更跳。裕量只吸收相位差，不吸收量级差。
        assertTrue("alpha 反向跳变 $revAlpha vs 基线 $baseAlpha", revAlpha <= baseAlpha + 1f)
        assertTrue("半径反向跳变 $revRx vs 基线 $baseRx", revRx <= baseRx + 1f)
        assertTrue("光心反向跳变 $revCx vs 基线 $baseCx", revCx <= baseCx + 1f)
    }

    fun reversalKeepsEveryOutputFrameContinuous() {
        val samples = settled(frames = 240) { index ->
            val sign = if (index / 40 % 2 == 0) 1f else -1f
            dragDrive(sign) { 1f }(index)
        }
        // 逐帧硬上界：alpha 12 bytes、半径 2% 基准、光心 1.5dp+0.5px
        // （与取向模型 frameToFrameOutputsHaveNoVisibleJumps 同口径）。
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
        // 2026-09-21 真机：顶部胶囊（full-round，r = h/2）向四个斜向拖动时高光瞬间消失。
        // 机制：moveTo 把触点钳进**矩形**边界，而矩形四角本就在胶囊轮廓外（SDF > 0）；
        // edgeBandPx = 0 让 smoothStep 退化成 0 处硬阶跃，insideDistance < 0 即整团熄灭。
        // 轮廓裁剪由 TouchHighlight 的 clipPath 负责，这里不许再有一道边缘门。
        val state = GlowState()
        val holder = GlowFrame()
        holder.boundsWidth = viewWidth
        holder.boundsHeight = viewHeight
        holder.cornerRadius = viewHeight * .5f       // 胶囊：全圆角
        holder.press = 1f
        repeat(60) {
            holder.offsetX = limitPx * .7f
            holder.offsetY = limitPx * .7f           // 45° 斜向满额拖动
            holder.velocityX = velocityRefPx * .7f
            holder.velocityY = velocityRefPx * .7f
            holder.centerX = viewWidth               // 钳在矩形右上角：胶囊轮廓外
            holder.centerY = 0f
            state.update(holder, 1f / 120f, radiusPx, 32, flowing)
        }
        assertTrue("触点钳在胶囊外的矩形角时高光不许熄灭", state.shape.visible)
        assertTrue(state.shape.alphaByte > 0)
        // 对照：band > 0 的表面保留边缘衰减语义（底栏/scrub 条贴边变暗），但同样不许熄灭。
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
        val random = kotlin.random.Random(20260921)
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
    private val travelEpsPx = 1.5f             // 0.5dp：渲染位移的"有方向"闸门（不是 touchSlop）
    private val maxTravelPx = 12f              // 4dp
    private val velocityRefPx = 720f           // 240dp/s
    private val edgeBandPx = 84f               // 28dp
    private val radiusPx = 192f                // 64dp
    private val barWidth = 960f
    private val barHeight = 192f
    private val cornerPx = barHeight / 2f      // 胶囊圆角，与底栏 outline 一致
    private val config = GlowConfig.create(density, maxTravelPx, travelEpsPx, velocityRefPx, edgeBandPx)

    private fun frame(block: GlowFrame.() -> Unit) = GlowFrame().apply(block)

    /** 逐帧喂入；每帧先用零值清空 holder，再交给 block 填。 */
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
        // 高亮表面的基础 alpha 32 同样逐字保持
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
        // 1.30 × 1.15 × 1.18 × 72 = 127.0
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
        // 纵向没有横向增亮：1.30 × 1.15 × 72 = 107.6 ⇒ 108
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
        // 静止：无方向 ⇒ 不前移（正圆光晕不能无端偏心头）
        val rest = GlowState()
        rest.update(restingFrame(), 1f / 120f, radiusPx, 72, config)
        assertEquals(0f, rest.shape.coreOffsetX, 0f)

        // 满额横向形变：前移达到上限；rotation 仍为 0（局部 +X 就是运动方向）
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

        // 垂直形变：前移同样满额，靠 rotation=90° 转到垂直方向
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
        // 贴边仍可见（alpha 只降到地板），但前移与半径一起收到 EDGE_MIN_SCALE
        val atEdge = GlowState()
        atEdge.update(centered {
            press = 1f
            velocityX = velocityRefPx
            offsetX = maxTravelPx
            centerY = 0f
        }, 1f / 120f, radiusPx, 72, config)
        assertTrue(atEdge.shape.visible)
        assertTrue("贴边前移必须随尺寸收缩", atEdge.shape.coreOffsetX <= config.coreShiftMaxPx * GlowConfig.EDGE_MIN_SCALE + 1e-3f)

        // 半 band 处稳定形变：前移与半径同乘 edgeScale（≈0.85，下限已与堆积目标对齐），不到满额
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
            val y = cornerPx * index / 60f // 0（贴上边）→ 96（居中）
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
        // 起拖第一帧：形变必须接近 0（latent 拉伸会在这时一步到位）
        val state = GlowState()
        state.update(centered {
            press = 1f
            velocityX = 60f // 刚过静止闸门的微小速度
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
        // 向右拖：光心滞后到触点左侧，且不超过 TAIL_MAX(10dp=30px)
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
            // 面积守恒要与**实际生效的半径**比（policy 内部会把半径钳到 MAX_RADIUS_PX）
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

    fun sdfMatchesHandComputedDistances() {
        // 胶囊中心到边界的距离 = 半高
        assertEquals(96f, -roundedRectSignedDistance(480f, 96f, 960f, 192f, 96f), 1e-3f)
        // 距上边 4px
        assertEquals(4f, -roundedRectSignedDistance(480f, 4f, 960f, 192f, 96f), 1e-3f)
        // 上方外 10px 为负
        assertEquals(-10f, -roundedRectSignedDistance(480f, -10f, 960f, 192f, 96f), 1e-3f)
        // 矩形退化：(5,5) 距最近边 5px
        assertEquals(5f, -roundedRectSignedDistance(5f, 5f, 100f, 100f, 0f), 1e-3f)
        // 圆角外侧：(-10,-10) 在 100×100 r=20 的左上角外
        assertEquals(-(sqrt(2f) * 30f - 20f), -roundedRectSignedDistance(-10f, -10f, 100f, 100f, 20f), 1e-2f)
        // 边界本身为 0
        assertEquals(0f, -roundedRectSignedDistance(480f, 0f, 960f, 192f, 96f), 1e-3f)
    }

    fun noisyVelocityKeepsTheAngleStable() {
        val random = Random(7)
        var drift = 0.0
        val state = GlowState()
        repeat(180) {
            val jitter = (random.nextFloat() - 0.5f) * 0.42f // ±12°
            val radians = drift + jitter
            val v = velocityRefPx
            state.update(centered {
                press = 1f
                velocityX = (cos(radians).toDouble() * v).toFloat()
                velocityY = (sin(radians).toDouble() * v).toFloat()
            }, 1f / 120f, radiusPx, 72, config)
            val degrees = Math.toDegrees(drift)
            assertTrue("第 $it 帧方向跳变 ${state.shape.rotationDeg} vs $degrees",
                abs(shortestAngleDeltaDeg(degrees.toFloat(), state.shape.rotationDeg)) < 15f)
            drift += (random.nextFloat() - 0.5f) * 0.05f
        }
        // 反向时走短弧，不翻烧饼（±180 等价）。转速护栏 540°/s ⇒ 180° 需约 333ms，
        // 这里给 60 帧（500ms）让它收敛到位。
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

    // ---------------- 无极性质（连续域） ----------------

    fun alphaIsContinuousAcrossTheAxialThreshold() {
        // 斜向拖过 |vx|/|vy| = 1.15 的临界方向：亮度必须连续爬升，不许"咯噔"
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
                // 通道 0 扫速度，通道 1 扫行程；步长都远小于各自饱和区宽度
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
        // 一次完整手势帧序列：按下（真实 press 弹簧）、60 帧拖动、松手回弹。
        // dt 混 8.3/11.1/16.7ms。上界按真实弹簧的最大单帧增量给：press 弹簧从静止
        // 到 1 的峰值速率约 8/s，60Hz 单帧 Δpress≈0.13 ⇒ Δalpha≈9.6 bytes。
        // 分档/折点/模式切换会一次跳几十，仍会被抓住。
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
                    // 角度上界 = 转速护栏 × dt（dt 归一化；没护栏时反转一帧可搬 51°）
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
            // 回弹按物理一致建模：位移单调归零，差分速度在转向点连续过零
            // （早期草案让位移钉在上限、速度却从 +708 突翻 -216，真实信号不可能如此）。
            holder.offsetX = maxTravelPx * (1f - progress)
            holder.velocityX = velocityRefPx * (1f - 2f * progress)
            holder.centerX = barWidth / 2f + 40f
            holder.centerY = barHeight / 2f
            step(holder)
        }
        assertFalse(state.shape.visible)
    }

    fun frameRateDoesNotChangeTheResponse() {
        // 同一次手势分别以 120Hz / 60Hz 推进：matched-time 输出必须一致（dt 归一化的直接证据）
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
        val fast = simulate(1f / 120f, 120) // 1 秒
        val slow = simulate(1f / 60f, 60)   // 1 秒
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
        // reset 之后重新按下：从正圆开始
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
        runIsolated { s -> s.sdfMatchesHandComputedDistances() }
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
