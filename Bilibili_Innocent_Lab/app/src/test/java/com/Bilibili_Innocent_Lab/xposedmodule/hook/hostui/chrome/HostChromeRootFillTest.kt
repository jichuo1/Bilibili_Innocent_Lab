package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import org.junit.Assert.*
import org.junit.Test

class HostChromeRootFillTest {
    private interface Element
    private class Brush
    private class Shape
    private class Current(val color: Long, val brush: Brush?, val alpha: Float, val shape: Shape, mask: Int) : Element
    private class Early(val color: Long, val brush: Brush?, val alpha: Float, val shape: Shape, val inspector: (Any) -> Unit) : Element
    private class Lookalike(val color: Long, val brush: Brush?, val alpha: Float, val shape: Shape) : Element
    private class Node(val color: Long, val brush: Brush?, val alpha: Float, val shape: Shape, val cachedSize: Long) : Element
    private class Merged(val color: Long, val brush: Brush?, val alpha: Float, val shape: Shape, val classId: Int) : Element
    private class NoConstructor(value: Any) : Element {
        val color = 0L
        val brush: Brush? = null
        val alpha = 1f
        val shape = Shape()
    }
    private class Native {
        companion object {
            var calls = 0
            @JvmStatic fun current(empty: Any, color: Long, shape: Shape): Any { calls++; return Current(color, null, 1f, shape, 0) }
            @JvmStatic fun early(empty: Any, color: Long, shape: Shape): Any { calls++; return Early(color, null, 1f, shape) {} }
        }
    }
    private fun recognizer(factory: String) = HostChromeRootFill(Element::class.java, Brush::class.java, Shape::class.java,
        Function1::class.java, Any(), Native::class.java.getDeclaredMethod(factory, Any::class.java, Long::class.javaPrimitiveType, Shape::class.java).apply { isAccessible = true })

    @Test fun `renamed four field element is certified by native factory and cached`() {
        Native.calls = 0
        val fill = recognizer("current")
        assertTrue(fill.matches(Current(0, null, 1f, Shape(), 0)))
        assertTrue(fill.matches(Current(123, Brush(), .5f, Shape(), 0)))
        assertEquals(1, Native.calls)
    }
    @Test fun `older inspector field is allowed without relying on private class name`() {
        Native.calls = 0
        assertTrue(recognizer("early").matches(Early(0, null, 1f, Shape()) {}))
        assertEquals(1, Native.calls)
    }
    @Test fun `matching fields and constructor alone cannot remove another modifier role`() {
        Native.calls = 0
        val fill = recognizer("current")
        assertFalse(fill.matches(Lookalike(0, null, 1f, Shape())))
        assertFalse(fill.matches(Lookalike(1, null, 1f, Shape())))
        assertEquals(1, Native.calls)
    }
    @Test fun `node caches class ids and unrelated constructors are rejected before probing`() {
        Native.calls = 0
        val fill = recognizer("current")
        assertFalse(fill.matches(Node(0, null, 1f, Shape(), 0)))
        assertFalse(fill.matches(Merged(0, null, 1f, Shape(), 2)))
        assertFalse(fill.matches(NoConstructor(Any())))
        assertFalse(fill.matches(Any()))
        assertEquals(0, Native.calls)
    }
}
