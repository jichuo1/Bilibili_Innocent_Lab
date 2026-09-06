package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedLocator
import org.junit.Assert.*
import org.junit.Test

class PlayerSpeedLocatorTest {
    interface State { fun getValue(): Any? }
    interface Mutable : State { fun setValue(value: Any?) }
    class Flow(private var value: Any?) : Mutable {
        override fun getValue() = value
        override fun setValue(value: Any?) { this.value = value }
    }
    class OldManager {
        @JvmField val base: Mutable = Flow(1f)
        @JvmField val temporary: Mutable = Flow(null)
        fun first() = base.getValue() as Float
        fun second() = temporary.getValue() as? Float ?: first()
    }
    class NewManager {
        @JvmField val base: Mutable = Flow(1f)
        @JvmField val temporary: Mutable = Flow(null)
        fun current() = base.getValue() as Float
    }
    class ExtraFlow {
        @JvmField val a: Mutable = Flow(1f)
        @JvmField val b: Mutable = Flow(null)
        @JvmField val c: Mutable = Flow(null)
        fun current() = a.getValue() as Float
    }
    class ExtraGetter {
        @JvmField val a: Mutable = Flow(1f)
        @JvmField val b: Mutable = Flow(null)
        fun one() = 1f
        fun two() = 1f
        fun three() = 1f
    }
    private fun locate(owner: Class<*>) = PlayerSpeedLocator.defaultSpeed(owner, Mutable::class.java, State::class.java)

    @Test fun `actual locator handles both getter shapes and exact interface fields`() {
        listOf(OldManager::class.java to 2, NewManager::class.java to 1).forEach { (owner, count) ->
            val point = requireNotNull(locate(owner))
            assertEquals(count, point.speedGetters.size)
            assertEquals(2, point.flows.size)
            val target = point.constructor.newInstance()
            assertEquals(PlayerSpeedFeatureInstaller.DefaultSpeedResult.APPLIED,
                PlayerSpeedFeatureInstaller.applyDefaultSpeed(target, point, 1.25f))
            assertTrue(point.speedGetters.all { it.invoke(target) == 1.25f })
        }
    }
    @Test fun `new flow or getter ambiguity refuses to guess`() {
        assertNull(locate(ExtraFlow::class.java))
        assertNull(locate(ExtraGetter::class.java))
        assertNull(PlayerSpeedLocator.defaultSpeed(NewManager::class.java, Flow::class.java, State::class.java))
        assertNull(PlayerSpeedLocator.defaultSpeed(NewManager::class.java, State::class.java, Mutable::class.java))
    }
}
