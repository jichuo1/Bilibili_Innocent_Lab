package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import org.junit.Assert.*
import org.junit.Test

class HostChromeRowSelectionTest {
    private val contentOwner = "fixture.bottom.BottomContent"
    private val contentMethod = "renderTabs"
    private val policy = "rowMeasurePolicy"

    private fun frame(owner: String, method: String) = StackTraceElement(owner, method, "Fixture.kt", 1)
    private fun content() = frame(contentOwner, contentMethod)
    private fun accepts(vararg frames: StackTraceElement) =
        HostChromeRowSelection.directCaller(arrayOf(*frames), policy, contentOwner, contentMethod)

    @Test fun `hook bridge and generated callback owners before policy do not change direct caller`() {
        assertTrue(accepts(
            frame("fixture.bridge.HookBridge", "invoke"),
            frame("fixture.generated.Q7", "callback"),
            frame("fixture.generated.R2", policy),
            content()
        ))
    }

    @Test fun `only exact immediate content caller can select foreground row`() {
        val anchor = frame("fixture.generated.Trampoline", policy)
        assertTrue(accepts(anchor, content()))
        assertFalse(accepts(anchor, frame("fixture.image.Image", "render"), content()))
        assertFalse(accepts(anchor, frame(contentOwner, "renderBackground"), content()))
        assertFalse(accepts(anchor, frame("fixture.other.BottomContent", contentMethod), content()))
    }

    @Test fun `consecutive same method trampolines preserve the real direct caller`() {
        assertTrue(accepts(
            frame("fixture.generated.First", policy),
            frame("fixture.generated.Second", policy),
            content()
        ))
        assertFalse(accepts(
            frame("fixture.generated.First", policy),
            frame("fixture.generated.Second", policy),
            frame("fixture.image.Image", "render"),
            content()
        ))
    }

    @Test fun `absent or too deep policy anchors cannot claim a row`() {
        assertFalse(accepts())
        assertFalse(accepts(frame("fixture.bridge.HookBridge", "invoke"), content()))
        val prefix = List(46) { frame("fixture.bridge.Frame$it", "invoke") }
        val anchor = frame("fixture.generated.Trampoline", policy)
        assertTrue(accepts(*(prefix + anchor + content()).toTypedArray()))
        assertFalse(accepts(*(prefix + frame("fixture.bridge.Extra", "invoke") + anchor + content()).toTypedArray()))
        assertFalse(accepts(*(prefix + List(2) { frame("fixture.bridge.Extra$it", "invoke") } + anchor + content()).toTypedArray()))
    }

    @Test fun `first policy anchor cannot skip a different caller to use a later matching anchor`() {
        assertFalse(accepts(
            frame("fixture.generated.First", policy),
            frame("fixture.image.Image", "render"),
            frame("fixture.generated.Second", policy),
            content()
        ))
    }

    @Test fun `selection retries after unrelated row and stops reading stacks once selected`() {
        val selection = HostChromeRowSelection(contentOwner, contentMethod)
        var reads = 0
        assertFalse(selection.select(policy) {
            reads++
            arrayOf(frame("fixture.generated.First", policy), frame("fixture.image.Image", "render"), content())
        })
        assertFalse(selection.selected)
        assertTrue(selection.select(policy) {
            reads++
            arrayOf(frame("fixture.generated.Second", policy), content())
        })
        assertTrue(selection.selected)
        assertFalse(selection.select(policy) { error("selected row must not allocate another stack") })
        assertEquals(2, reads)
        assertTrue(selection.selected)
    }
}
