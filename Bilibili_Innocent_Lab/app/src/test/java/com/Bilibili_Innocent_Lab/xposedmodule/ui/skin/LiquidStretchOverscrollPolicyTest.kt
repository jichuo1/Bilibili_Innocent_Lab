package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidStretchEdge
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidStretchOverscrollPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidStretchUnconsumedAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidStretchOverscrollPolicyTest {

    @Test
    fun `unconsumed direction selects matching edge`() {
        assertEquals(LiquidStretchEdge.TOP, LiquidStretchOverscrollPolicy.pullEdge(-24))
        assertEquals(LiquidStretchEdge.NONE, LiquidStretchOverscrollPolicy.pullEdge(0))
        assertEquals(LiquidStretchEdge.BOTTOM, LiquidStretchOverscrollPolicy.pullEdge(24))
    }

    @Test
    fun `reverse scroll only releases an active opposite edge`() {
        assertEquals(
            LiquidStretchEdge.TOP,
            LiquidStretchOverscrollPolicy.releaseEdge(12, topDistance = 0.2f, bottomDistance = 0f)
        )
        assertEquals(
            LiquidStretchEdge.BOTTOM,
            LiquidStretchOverscrollPolicy.releaseEdge(-12, topDistance = 0f, bottomDistance = 0.2f)
        )
        assertEquals(
            LiquidStretchEdge.NONE,
            LiquidStretchOverscrollPolicy.releaseEdge(12, topDistance = 0f, bottomDistance = 0.2f)
        )
    }

    @Test
    fun `distance normalizes by viewport and clamps invalid extremes`() {
        assertEquals(0.25f, LiquidStretchOverscrollPolicy.normalizedDistance(50, 200), 0.0001f)
        assertEquals(1f, LiquidStretchOverscrollPolicy.normalizedDistance(-500, 200), 0.0001f)
        assertEquals(0f, LiquidStretchOverscrollPolicy.normalizedDistance(50, 0), 0.0001f)
    }

    @Test
    fun `bottom displacement mirrors top around viewport center`() {
        assertEquals(
            0.25f,
            LiquidStretchOverscrollPolicy.displacement(25f, 100, LiquidStretchEdge.TOP),
            0.0001f
        )
        assertEquals(
            0.75f,
            LiquidStretchOverscrollPolicy.displacement(25f, 100, LiquidStretchEdge.BOTTOM),
            0.0001f
        )
        assertEquals(
            0.5f,
            LiquidStretchOverscrollPolicy.displacement(25f, 0, LiquidStretchEdge.TOP),
            0.0001f
        )
    }

    @Test
    fun `consumption signs follow nested scroll direction`() {
        assertEquals(-20, LiquidStretchOverscrollPolicy.consumedPixels(
            LiquidStretchEdge.TOP, 0.1f, 200
        ))
        assertEquals(20, LiquidStretchOverscrollPolicy.consumedPixels(
            LiquidStretchEdge.BOTTOM, 0.1f, 200
        ))
        assertEquals(20, LiquidStretchOverscrollPolicy.releaseConsumedPixels(
            LiquidStretchEdge.TOP, -0.1f, 200
        ))
        assertEquals(-20, LiquidStretchOverscrollPolicy.releaseConsumedPixels(
            LiquidStretchEdge.BOTTOM, -0.1f, 200
        ))
    }

    @Test
    fun `absorb velocity is positive and bounded`() {
        assertEquals(720, LiquidStretchOverscrollPolicy.absorbVelocity(-720f))
        assertEquals(1, LiquidStretchOverscrollPolicy.absorbVelocity(0f))
        assertEquals(100_000, LiquidStretchOverscrollPolicy.absorbVelocity(Float.MAX_VALUE))
    }

    @Test
    fun `touch pulls and consumes while fling always propagates`() {
        assertEquals(
            LiquidStretchUnconsumedAction.PULL_AND_CONSUME,
            LiquidStretchOverscrollPolicy.unconsumedAction(
                isTouch = true,
                hasFlingVelocity = true
            )
        )
        assertEquals(
            LiquidStretchUnconsumedAction.ABSORB_AND_PROPAGATE,
            LiquidStretchOverscrollPolicy.unconsumedAction(
                isTouch = false,
                hasFlingVelocity = true
            )
        )
        assertEquals(
            LiquidStretchUnconsumedAction.PROPAGATE,
            LiquidStretchOverscrollPolicy.unconsumedAction(
                isTouch = false,
                hasFlingVelocity = false
            )
        )
    }

    @Test
    fun `stop releases touch and adjusted fling but preserves absorb`() {
        assertTrue(
            LiquidStretchOverscrollPolicy.shouldReleaseOnStop(
                isTouch = true,
                nonTouchAbsorbed = false,
                nonTouchAdjusted = false
            )
        )
        assertTrue(
            LiquidStretchOverscrollPolicy.shouldReleaseOnStop(
                isTouch = false,
                nonTouchAbsorbed = false,
                nonTouchAdjusted = true
            )
        )
        assertFalse(
            LiquidStretchOverscrollPolicy.shouldReleaseOnStop(
                isTouch = false,
                nonTouchAbsorbed = true,
                nonTouchAdjusted = true
            )
        )
    }
}
