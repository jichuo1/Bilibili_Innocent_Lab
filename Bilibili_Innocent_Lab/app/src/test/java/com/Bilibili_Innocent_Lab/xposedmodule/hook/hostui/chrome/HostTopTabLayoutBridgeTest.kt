package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top.HostTopTabCenteringPolicy
import org.junit.Assert.*
import org.junit.Test

class HostTopTabLayoutBridgeTest {
    interface Alignment { fun align(child: Long, available: Long, direction: Any): Long }
    interface Scope { fun subcompose(slot: Any, content: (Any?, Any?) -> Any?): List<Any>; fun density(): Float }
    private enum class Slot { Tabs, Indicator }
    private fun packed(x: Int, y: Int) = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)
    private fun bridge(onError: (Throwable) -> Unit = {}, onApplied: () -> Unit = {},
                       onTabsMeasured: (Int) -> Unit = {},
                       center: (Int, Int, Int) -> Int? = { count, width, available ->
                           HostTopTabCenteringPolicy.centeredOffset(count, available, 0, available, width)
                       }): HostTopTabLayoutBridge = HostTopTabLayoutBridge(
        Alignment::class.java, Function2::class.java, Scope::class.java,
        Scope::class.java.getMethod("subcompose", Any::class.java, Function2::class.java),
        Alignment::class.java.getMethod("align", Long::class.javaPrimitiveType, Long::class.javaPrimitiveType, Any::class.java),
        Function2::class.java.getMethod("invoke", Any::class.java, Any::class.java), center, onApplied, onError, onTabsMeasured)

    @Test fun `native constraints content and whole measure result are delegated unchanged`() {
        var measured = -1
        val bridge = bridge(onTabsMeasured = { measured = it })
        val constraint = Any()
        val result = Any()
        val content: (Any?, Any?) -> Any? = { _, _ -> Any() }
        val original = object : Scope {
            override fun density() = 2.5f
            override fun subcompose(slot: Any, received: (Any?, Any?) -> Any?): List<Any> {
                assertSame(Slot.Tabs, slot); assertSame(content, received)
                return listOf(Any(), Any())
            }
        }
        val native: (Any?, Any?) -> Any? = { scope, constraints ->
            assertSame(constraint, constraints)
            val actual = scope as Scope
            assertEquals(2.5f, actual.density())
            actual.subcompose(Slot.Tabs, content)
            result
        }
        @Suppress("UNCHECKED_CAST") val measure = bridge.measure(native) as (Any?, Any?) -> Any?
        assertSame(result, measure(original, constraint))
        assertEquals(2, measured)
        val alignment = bridge.alignment(object : Alignment {
            override fun align(child: Long, available: Long, direction: Any) = packed(0, 17)
        }) as Alignment
        assertEquals(packed(100, 17), alignment.align(packed(100, 20), packed(300, 30), Any()))
    }

    @Test fun `three tabs and unknown slots restore original alignment on the next measure`() {
        val bridge = bridge()
        var slot: Any = Slot.Tabs
        var count = 2
        val scope = object : Scope {
            override fun density() = 1f
            override fun subcompose(slot: Any, content: (Any?, Any?) -> Any?) = List(count) { Any() }
        }
        val native: (Any?, Any?) -> Any? = { original, _ -> (original as Scope).subcompose(slot) { _, _ -> Unit } }
        @Suppress("UNCHECKED_CAST") val measure = bridge.measure(native) as (Any?, Any?) -> Any?
        val alignment = bridge.alignment(object : Alignment {
            override fun align(child: Long, available: Long, direction: Any) = packed(5, 7)
        }) as Alignment
        measure(scope, Any())
        assertEquals(packed(100, 7), alignment.align(packed(100, 20), packed(300, 30), Any()))
        count = 3; measure(scope, Any())
        assertEquals(packed(5, 7), alignment.align(packed(100, 20), packed(300, 30), Any()))
        slot = Slot.Indicator; count = 1; measure(scope, Any())
        assertEquals(packed(5, 7), alignment.align(packed(100, 20), packed(300, 30), Any()))
    }

    @Test fun `native measure exception escapes once with its original identity`() {
        val expected = IllegalStateException("native layout")
        val native: (Any?, Any?) -> Any? = { _, _ -> throw expected }
        @Suppress("UNCHECKED_CAST") val measure = bridge().measure(native) as (Any?, Any?) -> Any?
        val scope = object : Scope {
            override fun density() = 1f
            override fun subcompose(slot: Any, content: (Any?, Any?) -> Any?) = emptyList<Any>()
        }
        try { measure(scope, Any()); fail("native exception was swallowed") }
        catch (failure: Throwable) { assertSame(expected, failure) }
    }

    @Test fun `module geometry failure reports and preserves native placement`() {
        val expected = IllegalArgumentException("geometry")
        var reported: Throwable? = null
        val alignment = bridge(onError = { reported = it }, center = { _, _, _ -> throw expected })
            .alignment(object : Alignment {
                override fun align(child: Long, available: Long, direction: Any) = packed(8, 9)
            }) as Alignment
        assertEquals(packed(8, 9), alignment.align(packed(100, 20), packed(300, 30), Any()))
        assertSame(expected, reported)
    }

    @Test fun `local viewport fill follows count transitions without repeated invalidation`() {
        var invalidations = 0
        val state = HostTopTabWidthState { invalidations++ }
        assertFalse(state.fill)
        state.measured(0); state.measured(4)
        assertEquals(0, invalidations)
        state.measured(1); assertTrue(state.fill)
        state.measured(1); state.measured(2)
        assertEquals(1, invalidations)
        state.measured(3); assertFalse(state.fill)
        state.measured(4); state.measured(0)
        assertEquals(2, invalidations)
        state.measured(2); assertTrue(state.fill)
        assertEquals(3, invalidations)
    }
}
