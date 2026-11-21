package com.Bilibili_Innocent_Lab.xposedmodule.ui.overlay

import org.junit.Assert.*
import org.junit.Test

class ReplyTopologyTreeViewportTest {
    fun pinchKeepsTheWorldPointUnderTheFingersStationary() {
        val view = ReplyTopologyTreeViewport()
        view.pan(30.0, -60.0)
        val x = view.worldX(170.0)
        val y = view.worldY(210.0)
        assertTrue(view.zoom(2.3, 170.0, 210.0))
        assertEquals(x, view.worldX(170.0), 0.0000001)
        assertEquals(y, view.worldY(210.0), 0.0000001)
    }

    fun panningCanGoPastTheContentAndBackWithoutLayoutIntegerLimits() {
        val view = ReplyTopologyTreeViewport()
        assertTrue(view.pan(-9_000_000_000.0, 8_000_000_000.0))
        assertEquals(-9_000_000_000.0, view.x, 0.0)
        assertTrue(view.pan(9_000_000_000.0, -8_000_000_000.0))
        assertEquals(0.0, view.x, 0.0)
        assertEquals(0.0, view.y, 0.0)
    }

    fun entireDeepTreeCanFitAtOverviewScaleAndCoordinatesRemainFinite() {
        val view = ReplyTopologyTreeViewport()
        assertTrue(view.fit(1_400_000.0, 700_000.0, 400.0, 700.0, 10.0))
        assertTrue(view.scale < 0.01)
        assertTrue(view.screenX(1_400_000.0) <= 400.0)
        assertTrue(view.screenY(700_000.0) <= 700.0)
        assertTrue(view.zoom(1_000_000.0, 200.0, 300.0))
        assertEquals(ReplyTopologyTreeViewport.MAX_SCALE, view.scale, 0.0)
    }

    fun invalidGestureInputDoesNotCorruptTheExistingViewport() {
        val view = ReplyTopologyTreeViewport()
        view.pan(10.0, 20.0)
        assertFalse(view.pan(Double.NaN, 1.0))
        assertFalse(view.zoom(0.0, 50.0, 50.0))
        assertFalse(view.zoom(Double.NaN, 50.0, 50.0))
        assertFalse(view.place(Double.POSITIVE_INFINITY, 0.0, 0.0, 0.0))
        assertEquals(10.0, view.x, 0.0)
        assertEquals(20.0, view.y, 0.0)
        assertEquals(1.0, view.scale, 0.0)
    }

    fun viewportRoundTripRemainsPreciseAroundLargeWorldCoordinates() {
        val view = ReplyTopologyTreeViewport()
        view.place(10_000_000.0, 8_000_000.0, 100.0, 200.0, 0.75)
        assertEquals(10_000_000.0, view.worldX(view.screenX(10_000_000.0)), 0.000001)
        assertEquals(8_000_000.0, view.worldY(view.screenY(8_000_000.0)), 0.000001)
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (ReplyTopologyTreeViewportTest) -> Unit) {
            val s = ReplyTopologyTreeViewportTest()
            block(s)
        }
        runIsolated { s -> s.pinchKeepsTheWorldPointUnderTheFingersStationary() }
        runIsolated { s -> s.panningCanGoPastTheContentAndBackWithoutLayoutIntegerLimits() }
        runIsolated { s -> s.entireDeepTreeCanFitAtOverviewScaleAndCoordinatesRemainFinite() }
        runIsolated { s -> s.invalidGestureInputDoesNotCorruptTheExistingViewport() }
        runIsolated { s -> s.viewportRoundTripRemainsPreciseAroundLargeWorldCoordinates() }
    }
}
