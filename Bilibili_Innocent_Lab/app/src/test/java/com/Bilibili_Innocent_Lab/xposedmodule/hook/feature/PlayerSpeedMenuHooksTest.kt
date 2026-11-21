package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedLocator
import org.junit.Assert.*
import org.junit.Test

class PlayerSpeedMenuHooksTest {
    class FunctionService
    class WindowDelegate
    class CompactOwner {
        fun getFunctionWidgetService() = FunctionService()
        fun getKeelPlayer() = PlayerSpeedLocatorTest.FakeKeelPlayer()
        fun getWindowSizeDelegate() = WindowDelegate()
        companion object {
            @JvmStatic fun open(@Suppress("UNUSED_PARAMETER") owner: CompactOwner) = Unit
        }
    }
    class AmbiguousOwner {
        fun getFunctionWidgetService() = FunctionService()
        fun getKeelPlayer() = PlayerSpeedLocatorTest.FakeKeelPlayer()
        fun getWindowSizeDelegate() = WindowDelegate()
        companion object {
            @JvmStatic fun one(@Suppress("UNUSED_PARAMETER") owner: AmbiguousOwner) = Unit
            @JvmStatic fun two(@Suppress("UNUSED_PARAMETER") owner: AmbiguousOwner) = Unit
        }
    }

    private class Harness(default: Int = 0, spec: String = "800,101,100,10") {
        val registrar = PlayerPortTestRegistrar()
        val installer = PlayerSpeedFeatureInstaller(false, 275, default, spec)
        val environment = HookEnvironment("tv.danmaku.bili", javaClass.classLoader,
            HookPointRegistry(javaClass.classLoader), registrar, { _, _ -> }, { _, _ -> }, { _, _ -> })
        fun install(point: PlayerSpeedLocator.SpeedMenuPoint) = installer.installSpeedMenu(environment, point)
    }

    private fun modern() = requireNotNull(PlayerSpeedLocator.speedMenuAdapter(
        PlayerSpeedLocatorTest.ModernSpeedAdapter::class.java))
    private fun legacy() = requireNotNull(PlayerSpeedLocator.speedMenuAdapter(
        PlayerSpeedLocatorTest.LegacySpeedAdapter::class.java))

    fun `modern hooks replace actual arguments and allow adjacent hundredth choices`() {
        val factory = requireNotNull(PlayerSpeedLocator.speedMenuFactory(
            listOf(PlayerSpeedLocatorTest.SpeedOptionFactory::class.java, PlayerSpeedLocatorTest.SpeedOptionModel::class.java),
            PlayerSpeedLocatorTest.FakeMediaResource::class.java))
        val h = Harness()
        assertTrue(h.install(PlayerSpeedLocator.SpeedMenuPoint(factory, modern())))
        val options = h.registrar.invoke("player.speed.menu_options_factory", args = arrayOf(null, null, false, 0)) {
            listOf(PlayerSpeedLocatorTest.SpeedOptionModel(2f, false))
        } as List<*>
        assertEquals(listOf(8f, 1.01f, 1f, 0.1f), options.map { (it as PlayerSpeedLocatorTest.SpeedOptionModel).a })
        val adapter = PlayerSpeedLocatorTest.ModernSpeedAdapter(1)
        h.registrar.invoke("player.speed.menu_adapter_populate", adapter, arrayOf(1f, emptyList<Any>())) { args ->
            @Suppress("UNCHECKED_CAST")
            adapter.l0(args[0] as Float, args[1] as List<PlayerSpeedLocatorTest.SpeedOptionModel>)
        }
        assertEquals(listOf(false, false, true, false), adapter.c.map { it.b })
        assertTrue(adapter.c.all { it.c })
        h.registrar.invoke("player.speed.menu_adapter_populate", adapter, arrayOf(1.01f, options)) { args ->
            @Suppress("UNCHECKED_CAST")
            adapter.l0(args[0] as Float, args[1] as List<PlayerSpeedLocatorTest.SpeedOptionModel>)
        }
        assertEquals(listOf(false, true, false, false), adapter.c.map { it.b })
    }

    fun `legacy hook uses custom float array and restores it if host population throws`() {
        val h = Harness()
        assertTrue(h.install(PlayerSpeedLocator.SpeedMenuPoint(null, legacy())))
        val adapter = PlayerSpeedLocatorTest.LegacySpeedAdapter(1)
        val original = adapter.d
        val failure = IllegalStateException("host failure")
        try {
            h.registrar.invoke("player.speed.menu_adapter_populate", adapter, arrayOf(1f)) { throw failure }
            fail("Host exception must remain visible")
        } catch (actual: IllegalStateException) { assertSame(failure, actual) }
        assertSame(original, adapter.d)
        h.registrar.invoke("player.speed.menu_adapter_populate", adapter, arrayOf(1f)) { adapter.K0(it[0] as Float) }
        assertArrayEquals(floatArrayOf(8f, 1.01f, 1f, 0.1f), adapter.d, 0f)
        assertEquals(listOf(false, false, true, false), adapter.c.map { it.b })
    }

    fun `failed adapter registration never publishes factory options without precision correction`() {
        val factory = requireNotNull(PlayerSpeedLocator.speedMenuFactory(
            listOf(PlayerSpeedLocatorTest.SpeedOptionFactory::class.java, PlayerSpeedLocatorTest.SpeedOptionModel::class.java),
            PlayerSpeedLocatorTest.FakeMediaResource::class.java))
        val h = Harness()
        h.registrar.failId = "player.speed.menu_adapter_populate"
        try {
            h.install(PlayerSpeedLocator.SpeedMenuPoint(factory, modern()))
            fail("Registration must fail")
        } catch (_: IllegalStateException) { }
        assertFalse("player.speed.menu_options_factory" in h.registrar.hooks)
        val missing = Harness()
        assertFalse(missing.install(PlayerSpeedLocator.SpeedMenuPoint(factory, null)))
        assertTrue(missing.registrar.hooks.isEmpty())
    }

    fun `host 2x alias is highlighted without conflating an explicit 1_99 option`() {
        val point = legacy()
        val adapter = PlayerSpeedLocatorTest.LegacySpeedAdapter(1)
        adapter.d = floatArrayOf(2f, 1f)
        adapter.K0(1.99f)
        assertTrue(PlayerSpeedFeatureInstaller.refineAdapterItems(adapter, point, 1.99f, listOf(2f, 1f)))
        assertEquals(listOf(true, false), adapter.c.map { it.b })
        adapter.d = floatArrayOf(2f, 1.99f, 1f)
        adapter.K0(1.99f)
        assertTrue(PlayerSpeedFeatureInstaller.refineAdapterItems(adapter, point, 1.99f, listOf(2f, 1.99f, 1f)))
        assertEquals(listOf(false, true, false), adapter.c.map { it.b })
    }

    fun `invalid and conflicting configurations install no menu hooks and preserve long press capability`() {
        for (h in listOf(Harness(default = 150), Harness(spec = "100,bad"), Harness(spec = ""))) {
            assertFalse(h.install(PlayerSpeedLocator.SpeedMenuPoint(null, legacy())))
            assertTrue(h.registrar.hooks.isEmpty())
            assertTrue("player_long_press_speed_percent" in h.installer.capabilityIds)
            assertFalse(PlayerSpeedFeatureInstaller.MENU_CAPABILITY in h.installer.capabilityIds)
        }
    }

    fun `compact route chooses host native menu only inside the matching opener and cleans up on failure`() {
        val route = requireNotNull(PlayerSpeedLocator.speedMenuCompactRoute(
            CompactOwner::class.java, FunctionService::class.java, PlayerSpeedLocatorTest.FakeKeelPlayer::class.java))
        assertNull(PlayerSpeedLocator.speedMenuCompactRoute(
            AmbiguousOwner::class.java, FunctionService::class.java, PlayerSpeedLocatorTest.FakeKeelPlayer::class.java))
        val h = Harness()
        h.install(PlayerSpeedLocator.SpeedMenuPoint(null, modern(), compactRoute = route))
        val target = CompactOwner()
        val other = CompactOwner()
        val window = WindowDelegate()
        fun windowFor(owner: CompactOwner) = h.registrar.invoke("player.speed.menu_compact_window_delegate", owner) { window }
        assertSame(window, windowFor(target))
        try {
            h.registrar.invoke("player.speed.menu_compact_open", args = arrayOf(target)) {
                assertNull(windowFor(target))
                assertSame(window, windowFor(other))
                h.registrar.invoke("player.speed.menu_compact_open", args = arrayOf(other)) {
                    assertNull(windowFor(other))
                }
                assertNull(windowFor(target))
                throw IllegalStateException("host failure")
            }
            fail("Host exception must remain visible")
        } catch (_: IllegalStateException) { }
        assertSame(window, windowFor(target))
        assertSame(window, windowFor(other))
    }

    fun `malformed host items are left untouched and never treated as matching speeds`() {
        val point = modern()
        val adapter = PlayerSpeedLocatorTest.ModernSpeedAdapter(1)
        adapter.l0(1f, listOf(PlayerSpeedLocatorTest.SpeedOptionModel(Float.NaN, true)))
        assertFalse(PlayerSpeedFeatureInstaller.refineAdapterItems(adapter, point, 1f, listOf(1f)))
        assertFalse(adapter.c.single().b)
        assertFalse(PlayerSpeedFeatureInstaller.matchesOptionModels(
            listOf(PlayerSpeedLocatorTest.SpeedOptionModel(Float.NaN, true)),
            PlayerSpeedLocatorTest.SpeedOptionModel::class.java.getField("a"),
            PlayerSpeedLocatorTest.SpeedOptionModel::class.java.getField("b"), listOf(1f)))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (PlayerSpeedMenuHooksTest) -> Unit) {
            val s = PlayerSpeedMenuHooksTest()
            block(s)
        }
        runIsolated { s -> s.`modern hooks replace actual arguments and allow adjacent hundredth choices`() }
        runIsolated { s -> s.`legacy hook uses custom float array and restores it if host population throws`() }
        runIsolated { s -> s.`failed adapter registration never publishes factory options without precision correction`() }
        runIsolated { s -> s.`host 2x alias is highlighted without conflating an explicit 1_99 option`() }
        runIsolated { s -> s.`invalid and conflicting configurations install no menu hooks and preserve long press capability`() }
        runIsolated { s -> s.`compact route chooses host native menu only inside the matching opener and cleans up on failure`() }
        runIsolated { s -> s.`malformed host items are left untouched and never treated as matching speeds`() }
    }
}
