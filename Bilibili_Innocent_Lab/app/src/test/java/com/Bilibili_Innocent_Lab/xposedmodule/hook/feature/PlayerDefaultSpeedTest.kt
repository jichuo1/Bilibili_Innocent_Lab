package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerSpeedFeatureInstaller.DefaultSpeedResult
import org.junit.Assert.*
import org.junit.Test

class PlayerDefaultSpeedTest {
    class Cell(var value: Any?)
    class Manager {
        @JvmField val x = Cell(1f)
        @JvmField val y = Cell(null)
        var staleReadback = false
        fun base(): Float = if (staleReadback) 1f else x.value as Float
        fun composed(): Float = y.value as? Float ?: base()
    }
    private fun point(twoGetters: Boolean = true) = PlayerSpeedLocator.DefaultSpeedPoint(
        Manager::class.java.getDeclaredConstructor(),
        listOf(Manager::class.java.getDeclaredField("x"), Manager::class.java.getDeclaredField("y")),
        Cell::class.java.getDeclaredMethod("getValue"),
        Cell::class.java.getDeclaredMethod("setValue", Any::class.java),
        (if (twoGetters) listOf("base", "composed") else listOf("base")).map { Manager::class.java.getDeclaredMethod(it) }
    )
    private fun apply(target: Manager, value: Float, twoGetters: Boolean = true) =
        PlayerSpeedFeatureInstaller.applyDefaultSpeed(target, point(twoGetters), value)

    @Test fun `old and new getter shapes initialize only the unique base slot`() {
        listOf(true, false).forEach {
            val manager = Manager()
            assertEquals(DefaultSpeedResult.APPLIED, apply(manager, 1.25f, it))
            assertEquals(1.25f, manager.x.value)
            assertNull(manager.y.value)
            assertEquals(1.25f, manager.composed(), 0f)
        }
    }
    @Test fun `temporary playback still overrides base and release restores selected base`() {
        val manager = Manager()
        apply(manager, 1.5f)
        manager.y.value = 3f
        assertEquals(3f, manager.composed(), 0f)
        manager.y.value = null
        assertEquals(1.5f, manager.composed(), 0f)
        manager.x.value = 2f
        assertEquals(2f, manager.composed(), 0f)
    }
    @Test fun `unexpected or previously modified state fails open`() {
        listOf(2f to null, 1f to 3f, 1f to 1f, null to null).forEach { (base, temporary) ->
            val manager = Manager().apply { x.value = base; y.value = temporary }
            assertNotEquals(DefaultSpeedResult.APPLIED, apply(manager, 2.5f))
            assertEquals(base, manager.x.value)
            assertEquals(temporary, manager.y.value)
        }
    }
    @Test fun `failed readback rolls back before reporting failure`() {
        val manager = Manager().apply { staleReadback = true }
        assertEquals(DefaultSpeedResult.FAILED, apply(manager, 1.5f))
        assertEquals(1f, manager.x.value)
        assertNull(manager.y.value)
    }
    @Test fun `one times is unchanged and second invocation never overwrites a different value`() {
        val manager = Manager()
        assertEquals(DefaultSpeedResult.UNCHANGED, apply(manager, 1f))
        assertEquals(DefaultSpeedResult.APPLIED, apply(manager, 1.5f))
        assertEquals(DefaultSpeedResult.UNEXPECTED_STATE, apply(manager, 2f))
        assertEquals(1.5f, manager.x.value)
    }
    @Test fun `invalid runtime multipliers cannot reach the flow`() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 0f, 4.01f).forEach {
            val manager = Manager()
            assertEquals(DefaultSpeedResult.UNEXPECTED_STATE, apply(manager, it))
            assertEquals(1f, manager.x.value)
        }
    }
}
