package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 接线回归，不替代设备触摸、动画帧或渲染性能验证。 */
class BubbleMotionIntegrationTest {
    private fun source(name: String): String {
        val path = "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/activity/$name.kt"
        return sequenceOf(File(path), File("app/$path")).first(File::isFile).readText()
    }

    @Test
    fun `late entry cannot reopen a closing or dismissed bubble`() {
        val source = source("BubbleMotionController")
        listOf("prepareFirstFrame", "startEntry").forEach { name ->
            assertTrue(source.substringAfter("fun $name() {").trimStart()
                .startsWith("if (state != MotionState.PREPARING_ENTRY) return"))
        }
        assertTrue(source("MainActivity").contains("if (!dialog.isShowing || bubbleController.isClosing) return true"))
    }

    @Test
    fun `retarget duration is scaled exactly once and predictive start is captured`() {
        val source = source("BubbleMotionController")
        assertEquals(1, Regex("NavigationMotionPolicy.remainingDuration\\(").findAll(source).count())
        assertTrue(source.contains("predictiveStartExpansion = expansion"))
        assertTrue(source.contains("BubbleMotionSpec.predictiveExpansion(predictiveStartExpansion, mapped)"))
        assertTrue(source.contains("NavigationMotionContinuation(start, target, session.velocity(now), actualDuration)"))
    }

    @Test
    fun `dialog placement uses actual root coordinates and keeps first close callback`() {
        val source = source("MainActivity")
        assertTrue(source.contains("bubbleSourceLocation[0] - bubbleRootLocation[0]"))
        assertTrue(source.contains("bubbleSourceLocation[1] - bubbleRootLocation[1]"))
        assertTrue(source.contains("bubbleLayer?.setAnchor(localAnchor)"))
        assertTrue(source.contains("bubbleLayer?.setPlacement(placement)"))
        assertTrue(source.contains("if (!bubbleController.isClosing) pendingAnchoredAfterClose.set(after)"))
        assertTrue(source.contains("removeOnGlobalLayoutListener(bubbleLayoutListener)"))
        assertTrue(source.contains("updateBubbleGeometry() || container.isLayoutRequested"))
    }
}
