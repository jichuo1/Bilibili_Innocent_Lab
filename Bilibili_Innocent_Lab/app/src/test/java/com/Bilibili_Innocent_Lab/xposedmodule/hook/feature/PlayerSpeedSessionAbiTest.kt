package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import FlatPlayerSpeedSessionFixture as Fixture
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedSessionLocator
import kotlin.coroutines.Continuation
import org.junit.Assert.*
import org.junit.Test

class PlayerSpeedSessionAbiTest {
    @Test fun `relocated playable requires bound accessor payload and matching core wrapper`() {
        val owner = Fixture.Owner::class.java
        val params = Fixture.Params::class.java
        val manager = Fixture.Manager::class.java
        val wrapper = Fixture.Wrapper::class.java
        val point = requireNotNull(PlayerSpeedSessionLocator.modern(owner, params, manager, wrapper))
        assertEquals("run", point.run.name)
        assertEquals("active", point.active.name)
        assertEquals("params", point.params.name)
        assertEquals(wrapper, point.wrapper?.declaringClass)
        assertNull(PlayerSpeedSessionLocator.modern(owner, params, manager, null))
        assertNull(PlayerSpeedSessionLocator.modern(owner, Any::class.java, manager, wrapper))
    }

    class PressService
    class PressLaunch(service: PressService, speed: Float, lease: Any, continuation: Continuation<*>)
    class PressPort(val service: PressService) { fun start(): Float? = 3f }
    class AmbiguousPort(val service: PressService) { fun start(): Float? = 2f }

    companion object {
        @JvmStatic fun calculate(playable: Any?, scriptPace: Float?): Float = 3f
    }

    private fun environment(registrar: PlayerPortTestRegistrar, stages: MutableList<FeatureRuntimeStage>): HookEnvironment {
        val loader = javaClass.classLoader!!
        return HookEnvironment("tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
            { _, _ -> }, { _, _ -> }, { _, _ -> }, runtimeEvidence = { _, stage, _ -> stages += stage })
    }

    @Test fun `theseus2 press off returns no lease without executing the start callback`() {
        val registrar = PlayerPortTestRegistrar()
        val stages = mutableListOf<FeatureRuntimeStage>()
        val service = PressService::class.java
        val launch = PressLaunch::class.java
        val continuation = Continuation::class.java
        val port = PressPort::class.java
        val method = requireNotNull(PlayerSpeedLocator.theseus2LongPress(service, launch, continuation, listOf(port)))
        assertEquals(port.getMethod("start"), method)
        assertNull(PlayerSpeedLocator.theseus2LongPress(service, launch, continuation,
            listOf(port, AmbiguousPort::class.java)))

        PlayerSpeedFeatureInstaller(true, 300, 100).installTheseus2LongPressOff(environment(registrar, stages), method)
        val id = "player.speed.disable_long_press_theseus2"
        assertEquals(setOf(id), registrar.hooks.keys)
        assertNull(registrar.invoke(id, PressPort(PressService())) { error("host start must not run") })
        assertEquals(listOf(FeatureRuntimeStage.OBSERVED, FeatureRuntimeStage.APPLIED), stages)
    }

    @Test fun `theseus2 custom press calculates one speed for playback and its indicator`() {
        val registrar = PlayerPortTestRegistrar()
        val stages = mutableListOf<FeatureRuntimeStage>()
        val port = PressPort(PressService())
        val start = port.javaClass.getMethod("start")
        val calculator = javaClass.getMethod("calculate", Any::class.java, Float::class.javaObjectType)
        PlayerSpeedFeatureInstaller(false, 175, 0)
            .installTheseus2LongPressSpeed(environment(registrar, stages), start, calculator, 1.75f)
        val startId = "player.speed.theseus2_long_press_scope"
        val calculateId = "player.speed.theseus2_calculate_long_press"
        fun calculate(pace: Float? = null) = registrar.invoke(calculateId, args = arrayOf(Any(), pace)) { 3f }

        assertEquals(3f, calculate())
        var playbackSpeed: Any? = null
        val indicatorSpeed = registrar.invoke(startId, port) {
            calculate().also { playbackSpeed = it }
        }
        assertEquals(1.75f, playbackSpeed)
        assertEquals(playbackSpeed, indicatorSpeed)
        assertEquals(listOf(FeatureRuntimeStage.OBSERVED, FeatureRuntimeStage.APPLIED), stages)

        stages.clear()
        assertEquals(3f, registrar.invoke(startId, port) { calculate(4f) })
        assertThrows(IllegalStateException::class.java) {
            registrar.invoke(startId, port) { calculate(); error("start failed") }
        }
        assertEquals(3f, calculate())
        assertTrue(stages.isEmpty())
        assertNull(PlayerSpeedLocator.theseus2LongPressCalculator(javaClass.classLoader!!, 9190500))
    }
}
