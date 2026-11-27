package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.LongPressCalculatorPoint
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

    fun `actual locator handles both getter shapes and exact interface fields`() {
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
    fun `new flow or getter ambiguity refuses to guess`() {
        assertNull(locate(ExtraFlow::class.java))
        assertNull(locate(ExtraGetter::class.java))
        assertNull(PlayerSpeedLocator.defaultSpeed(NewManager::class.java, Flow::class.java, State::class.java))
        assertNull(PlayerSpeedLocator.defaultSpeed(NewManager::class.java, State::class.java, Mutable::class.java))
    }

    class FakeMediaResource
    class SpeedOptionModel(@JvmField val a: Float, @JvmField val b: Boolean)
    object SpeedOptionFactory {
        @JvmField val a: List<Float> = listOf(2.0f, 1.5f, 1.25f, 1.0f, 0.75f, 0.5f)
        @JvmStatic
        fun a(res: FakeMediaResource?, qn: Integer?, reverse: Boolean, mask: Int): List<SpeedOptionModel> =
            a.map { SpeedOptionModel(it, true) }
    }

    class ModernSpeedAdapter(@Suppress("UNUSED_PARAMETER") theme: Int) : View.OnClickListener {
        class Item {
            @JvmField var a: Float = 1.0f
            @JvmField var b: Boolean = false
            @JvmField var c: Boolean = true
        }
        @JvmField val c: MutableList<Item> = ArrayList()
        fun l0(currentSpeed: Float, options: List<SpeedOptionModel>) {
            c.clear()
            for (opt in options) {
                val item = Item()
                item.a = opt.a
                item.b = kotlin.math.abs(currentSpeed - opt.a) < 0.1f
                item.c = opt.b
                c.add(item)
            }
        }
        override fun onClick(v: View?) = Unit
    }

    class LegacySpeedAdapter(@Suppress("UNUSED_PARAMETER") theme: Int) : View.OnClickListener {
        class Item {
            @JvmField var a: Float = 1.0f
            @JvmField var b: Boolean = false
        }
        @JvmField val c: MutableList<Item> = ArrayList()
        @JvmField var d: FloatArray = floatArrayOf(2.0f, 1.5f, 1.25f, 1.0f, 0.75f, 0.5f)
        fun K0(currentSpeed: Float) {
            c.clear()
            for (speed in d) {
                val item = Item()
                item.a = speed
                item.b = kotlin.math.abs(currentSpeed - speed) < 0.1f
                c.add(item)
            }
        }
        override fun onClick(v: View?) = Unit
    }

    class FakeKeelPlayer(@JvmField val b: NewManager = NewManager())

    fun `speed menu locator resolves modern factory and adapter and refines close speed selection`() {
        val candidates = listOf(SpeedOptionModel::class.java, SpeedOptionFactory::class.java)
        val factoryPoint = requireNotNull(
            PlayerSpeedLocator.speedMenuFactory(candidates, FakeMediaResource::class.java)
        )
        val adapterPoint = requireNotNull(
            PlayerSpeedLocator.speedMenuAdapter(
                adapterClass = ModernSpeedAdapter::class.java,
                knownFactory = factoryPoint
            )
        )
        val customMultipliers = listOf(3.0f, 1.1f, 1.05f, 1.0f, 0.5f)
        val builtOptions = requireNotNull(
            PlayerSpeedFeatureInstaller.buildOptionModels(
                factoryPoint.optionConstructor,
                factoryPoint.optionSpeedField,
                factoryPoint.optionAvailableField,
                customMultipliers
            )
        )
        assertEquals(5, builtOptions.size)
        assertTrue(
            PlayerSpeedFeatureInstaller.matchesOptionModels(
                builtOptions,
                factoryPoint.optionSpeedField,
                factoryPoint.optionAvailableField,
                customMultipliers
            )
        )

        val adapter = ModernSpeedAdapter(1)
        @Suppress("UNCHECKED_CAST")
        adapter.l0(1.05f, builtOptions as List<SpeedOptionModel>)
        // Host's < 0.1f check marks 1.1, 1.05, and 1.0 all selected before refinement:
        assertEquals(3, adapter.c.count { it.b })
        assertTrue(
            PlayerSpeedFeatureInstaller.refineAdapterItems(
                adapter,
                adapterPoint,
                1.05f,
                customMultipliers
            )
        )
        // After refinement, only 1.05f is selected:
        assertEquals(listOf(false, false, true, false, false), adapter.c.map { it.b })
        assertTrue(adapter.c.all { it.c })
    }

    fun `speed menu locator resolves legacy float array adapter`() {
        val legacyPoint = requireNotNull(
            PlayerSpeedLocator.speedMenuAdapter(adapterClass = LegacySpeedAdapter::class.java)
        )
        assertNotNull(legacyPoint.speedsArrayField)
        val adapter = LegacySpeedAdapter(1)
        val custom = listOf(4.0f, 1.25f, 1.2f, 1.0f, 0.25f)
        legacyPoint.speedsArrayField!!.set(adapter, custom.toFloatArray())
        adapter.K0(1.2f)
        assertEquals(2, adapter.c.count { it.b })
        assertTrue(PlayerSpeedFeatureInstaller.refineAdapterItems(adapter, legacyPoint, 1.2f, custom))
        assertEquals(listOf(false, false, true, false, false), adapter.c.map { it.b })

    }

    // 长按倍速计算器夹具：模拟 R8 重打包 owner（静态 final (playable,Float)F）与 playable 接口。
    interface CalcPlayable
    object CalcOwnerI { @JvmStatic fun i(playable: CalcPlayable, pace: Float?): Float = 3f }
    object CalcOwnerH { @JvmStatic fun h(playable: CalcPlayable, pace: Float?): Float = 3f }
    object CalcOwnerE { @JvmStatic fun e(playable: CalcPlayable, pace: Float?): Float = 3f }
    object CalcOwnerA { @JvmStatic fun a(playable: CalcPlayable, pace: Float?): Float = 3f }
    object CalcOwnerD { @JvmStatic fun d(playable: CalcPlayable, pace: Float?): Float = 3f }
    class CalcOwnerNonStatic { fun i(playable: CalcPlayable, pace: Float?): Float = 3f }
    class CalcPlayableClass // 非接口，playable 门禁应拒绝

    private class CalcLoader(private val owner: Class<*>, private val playable: Class<*>) :
        ClassLoader(PlayerSpeedLocatorTest::class.java.classLoader) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = when (name) {
            "tyg1", "h7h1", "jy0", "rv71", "n9h", "jpj", "UC0.a" -> owner
            "mmq2", "apq2", "rpq2", "gcg2", "rbq2", "q7q2",
            "com.bilibili.ship.theseus2.keel.player.m", "com.bilibili.ship.theseus2.keel.player.n" -> playable
            else -> super.loadClass(name, resolve)
        }
    }

    fun `theseus2 calculator resolves per verified build and never leaks across builds`() {
        // 远端 dexq 逐构建验真：9.20/9.21 各构建计算器 owner/方法名/playable 接口各异，start port 结构同 9.19。
        val builds = listOf(
            Triple(9200100, CalcOwnerI::class.java, "i"),
            Triple(9200200, CalcOwnerH::class.java, "h"),
            Triple(9200300, CalcOwnerE::class.java, "e"),
            Triple(9210100, CalcOwnerA::class.java, "a"),
            Triple(9210200, CalcOwnerD::class.java, "d"),
            Triple(9210300, CalcOwnerD::class.java, "d"),
        )
        for ((version, owner, methodName) in builds) {
            val loader = CalcLoader(owner, CalcPlayable::class.java)
            val method = PlayerSpeedLocator.theseus2LongPressCalculator(loader, version)
            assertNotNull("构建 $version 应解析出计算器", method)
            assertEquals(methodName, method!!.name)
            assertEquals(owner, method.declaringClass)
            // 反向：同一 owner 喂给方法名不符的其它已验真构建必须 fail-closed，不跨构建泄漏。
            for ((other, _, otherMethod) in builds) {
                if (other == version || otherMethod == methodName) continue
                assertNull("构建 $version 的 owner 不得满足构建 $other",
                    PlayerSpeedLocator.theseus2LongPressCalculator(loader, other))
            }
        }
        // 未登记构建保持关闭；9210100 与 9210200/9210300 在真实类名仅单边存在时也互不串用。
        val loader = CalcLoader(CalcOwnerI::class.java, CalcPlayable::class.java)
        assertNull(PlayerSpeedLocator.theseus2LongPressCalculator(loader, 9190500))
        assertNull(PlayerSpeedLocator.theseus2LongPressCalculator(loader, 9200400))
        assertNull(PlayerSpeedLocator.theseus2LongPressCalculator(loader, 9210400))
        val only9211Loader = object : ClassLoader(PlayerSpeedLocatorTest::class.java.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> = when (name) {
                "n9h" -> CalcOwnerA::class.java
                "rbq2" -> CalcPlayable::class.java
                else -> super.loadClass(name, resolve)
            }
        }
        assertNotNull(PlayerSpeedLocator.theseus2LongPressCalculator(only9211Loader, 9210100))
        assertNull(PlayerSpeedLocator.theseus2LongPressCalculator(only9211Loader, 9210200))
        assertNull(PlayerSpeedLocator.theseus2LongPressCalculator(only9211Loader, 9210300))
    }

    fun `theseus2 calculator refuses non-interface playable and non-static owner`() {
        assertNull("playable 必须是接口", PlayerSpeedLocator.theseus2LongPressCalculator(
            CalcLoader(CalcOwnerI::class.java, CalcPlayableClass::class.java), 9200100))
        assertNull("计算器必须是静态方法", PlayerSpeedLocator.theseus2LongPressCalculator(
            CalcLoader(CalcOwnerNonStatic::class.java, CalcPlayable::class.java), 9200100))
    }

    fun `dex calculator selection revalidates fully and cache round-trips`() {
        val loader = javaClass.classLoader!!
        val methodI = CalcOwnerI::class.java.getMethod("i", CalcPlayable::class.java, Float::class.javaObjectType)
        val selected = requireNotNull(PlayerSpeedLocator.selectCalculator(loader,
            com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult.Candidates(listOf(methodI))))
        assertEquals(CalcOwnerI::class.java.name, selected.owner)
        assertEquals("i", selected.method)
        val decoded = requireNotNull(LongPressCalculatorPoint.fromJson(selected.toJson()))
        assertEquals(selected, decoded)
        assertEquals(methodI, PlayerSpeedLocator.resolveCalculator(loader, decoded))
        // 两个不同 owner 同时通过全 ABI 验真是歧义，必须缺失而不是猜一个。
        val methodH = CalcOwnerH::class.java.getMethod("h", CalcPlayable::class.java, Float::class.javaObjectType)
        assertNull(PlayerSpeedLocator.selectCalculator(loader,
            com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult.Candidates(listOf(methodI, methodH))))
        assertNull(PlayerSpeedLocator.selectCalculator(loader,
            com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult.Candidates(List(33) { methodI })))
    }

    fun `calculator gates never scan when disabled direct or already attempted`() {
        assertFalse(PlayerSpeedLocator.calculatorNeedsQuery(false, true, false))
        assertFalse(PlayerSpeedLocator.calculatorNeedsQuery(true, false, false))
        assertFalse(PlayerSpeedLocator.calculatorNeedsQuery(true, true, true))
        assertTrue(PlayerSpeedLocator.calculatorNeedsQuery(true, true, false))
        assertTrue(PlayerSpeedLocator.calculatorRefreshCache(true, true, false, false, false))
        assertFalse(PlayerSpeedLocator.calculatorRefreshCache(true, true, false, false, true))
        assertFalse(PlayerSpeedLocator.calculatorRefreshCache(true, true, false, true, false))
    }

    fun `theseus2 calculator keeps legacy 918 and 919 builds resolving method a`() {
        // 版本表 Pair→Triple 重构后，9.18/9.19 旧条目（方法名 a）行为须逐字不变。
        for (version in listOf(9180100, 9180200, 9180300, 9190200, 9190400)) {
            val method = PlayerSpeedLocator.theseus2LongPressCalculator(
                CalcLoader(CalcOwnerA::class.java, CalcPlayable::class.java), version)
            assertNotNull("旧构建 $version 应解析出计算器", method)
            assertEquals("a", method!!.name)
        }
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (PlayerSpeedLocatorTest) -> Unit) {
            val s = PlayerSpeedLocatorTest()
            block(s)
        }
        runIsolated { s -> s.`actual locator handles both getter shapes and exact interface fields`() }
        runIsolated { s -> s.`new flow or getter ambiguity refuses to guess`() }
        runIsolated { s -> s.`speed menu locator resolves modern factory and adapter and refines close speed selection`() }
        runIsolated { s -> s.`speed menu locator resolves legacy float array adapter`() }
        runIsolated { s -> s.`theseus2 calculator resolves per verified build and never leaks across builds`() }
        runIsolated { s -> s.`theseus2 calculator refuses non-interface playable and non-static owner`() }
        runIsolated { s -> s.`dex calculator selection revalidates fully and cache round-trips`() }
        runIsolated { s -> s.`calculator gates never scan when disabled direct or already attempted`() }
        runIsolated { s -> s.`theseus2 calculator keeps legacy 918 and 919 builds resolving method a`() }
    }
}
