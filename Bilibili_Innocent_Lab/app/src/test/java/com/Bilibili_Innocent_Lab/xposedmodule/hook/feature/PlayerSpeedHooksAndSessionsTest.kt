package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import FlatPlayerSpeedSessionFixture as Fixture
import android.view.View
import android.view.ViewGroup
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.LongPressCalculatorPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedMenuPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedSessionClasses
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedSessionLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.testsupport.TestRenamingClassLoader
import com.bilibili.playerbizcommonv2.widget.speed.RenamedSpeedAdapter
import kotlin.coroutines.Continuation
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.*
import org.junit.Test

class PlayerSpeedHooksAndSessionsTest {
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

    class FunctionService
    class WindowDelegate
    class CompactOwner {
        fun getFunctionWidgetService() = FunctionService()
        fun getKeelPlayer() = FakeKeelPlayer()
        fun getWindowSizeDelegate() = WindowDelegate()
        companion object {
            @JvmStatic fun open(@Suppress("UNUSED_PARAMETER") owner: CompactOwner) = Unit
        }
    }
    class AmbiguousOwner {
        fun getFunctionWidgetService() = FunctionService()
        fun getKeelPlayer() = FakeKeelPlayer()
        fun getWindowSizeDelegate() = WindowDelegate()
        companion object {
            @JvmStatic fun one(@Suppress("UNUSED_PARAMETER") owner: AmbiguousOwner) = Unit
            @JvmStatic fun two(@Suppress("UNUSED_PARAMETER") owner: AmbiguousOwner) = Unit
        }
    }

    interface CalcPlayable
    object CalcOwnerI { @JvmStatic fun i(playable: CalcPlayable, pace: Float?): Float = 3f }
    object CalcOwnerH { @JvmStatic fun h(playable: CalcPlayable, pace: Float?): Float = 3f }
    object CalcOwnerE { @JvmStatic fun e(playable: CalcPlayable, pace: Float?): Float = 3f }
    object CalcOwnerA { @JvmStatic fun a(playable: CalcPlayable, pace: Float?): Float = 3f }
    object CalcOwnerD { @JvmStatic fun d(playable: CalcPlayable, pace: Float?): Float = 3f }
    class CalcOwnerNonStatic { fun i(playable: CalcPlayable, pace: Float?): Float = 3f }
    class CalcPlayableClass

    private class CalcLoader(private val owner: Class<*>, private val playable: Class<*>) :
        ClassLoader(PlayerSpeedHooksAndSessionsTest::class.java.classLoader) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = when (name) {
            "tyg1", "h7h1", "jy0", "rv71", "n9h", "jpj", "UC0.a" -> owner
            "mmq2", "apq2", "rpq2", "gcg2", "rbq2", "q7q2",
            "com.bilibili.ship.theseus2.keel.player.m", "com.bilibili.ship.theseus2.keel.player.n" -> playable
            else -> super.loadClass(name, resolve)
        }
    }

    class PressService
    class PressLaunch(service: PressService, speed: Float, lease: Any, continuation: Continuation<*>)
    class PressPort(val service: PressService) { fun start(): Float? = 3f }
    class AmbiguousPort(val service: PressService) { fun start(): Float? = 2f }
    class FakeTheseus2Port { fun start(): Float? = 3f }

    class FakeListOnlyOptionModel(@JvmField val speed: Float, @JvmField val available: Boolean)
    class FakeListOnlySpeedAdapter(@Suppress("UNUSED_PARAMETER") theme: Int) :
        androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>(),
        View.OnClickListener {
        class Item {
            @JvmField var speed: Float = 1f
            @JvmField var selected: Boolean = false
        }
        @JvmField val items: List<Item> = arrayListOf()
        @Suppress("UNUSED_PARAMETER")
        fun populate(current: Float, options: List<FakeListOnlyOptionModel>) = Unit
        override fun getItemCount(): Int = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder = error("unused")
        override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) = Unit
        override fun onClick(v: View?) = Unit
    }

    class Cell(var value: Any?)
    class Manager {
        @JvmField val base = Cell(1f)
        @JvmField val temporary = Cell(null)
        fun speed() = base.value as Float
    }
    class Index(@JvmField var mFrom: String = "vod")
    class Resource {
        var reads = 0
        var throwAt = -1
        fun getPlayIndex(): Index { check(++reads != throwAt); return Index(source) }
        var source = "vod"
    }
    class Item(val id: String)
    interface CoreApi {
        fun getMediaResource(): Resource
        fun getCurrentMediaItem(): Item
        fun getPlaySpeed(actual: Boolean): Float
        fun setPlaySpeed(value: Float)
    }
    class Core : CoreApi {
        var item = Item("a")
        val resource = Resource()
        var current = 1f
        override fun getMediaResource() = resource
        override fun getCurrentMediaItem() = item
        override fun getPlaySpeed(actual: Boolean) = current
        override fun setPlaySpeed(value: Float) { current = value }
    }
    interface Prepared { fun onPrepared(player: Any) }
    class Listener(@JvmField val core: Core) : Prepared { override fun onPrepared(player: Any) = Unit }
    class Context { @Suppress("UNUSED_PARAMETER") fun setOnPreparedListener(listener: Prepared) = Unit }
    class Params(val avid: Long, val cid: Long)
    class Playable(val params: Params)
    class Owner {
        @JvmField val manager = Manager()
        var active: Playable? = null
        @Suppress("UNUSED_PARAMETER") fun run(value: Playable?, continuation: Any?): Any = Any()
    }

    interface TemplateStateFlow { fun getValue(): Any? }
    interface TemplateMutableFlow : TemplateStateFlow { fun setValue(value: Any?) }
    class TemplateFlowImpl(private var current: Any?) : TemplateMutableFlow {
        override fun getValue(): Any? = current
        override fun setValue(value: Any?) { current = value }
    }
    class TemplateSpeedManager {
        @JvmField val base: TemplateMutableFlow = TemplateFlowImpl(1f)
        @JvmField val temporary: TemplateMutableFlow = TemplateFlowImpl(null)
        fun speed(): Float = base.getValue() as Float
    }
    class TemplateParams { fun getAvid(): Long = 101L; fun getCid(): Long = 202L }
    class TemplatePlayIndex {
        @JvmField var mFrom: String = "vod"
        companion object { const val FROM__VOD_COMMON: String = "vod" }
    }
    class TemplateMediaResource { fun getPlayIndex(): TemplatePlayIndex = TemplatePlayIndex() }
    class TemplateMediaItem { fun getId(): String = "item-1" }
    interface TemplateIMediaPlayer
    interface TemplateOnPreparedListener { fun onPrepared(player: TemplateIMediaPlayer) }
    interface TemplateCoreApi {
        fun getMediaResource(): TemplateMediaResource
        fun getCurrentMediaItem(): TemplateMediaItem
        fun getPlaySpeed(actual: Boolean): Float
    }
    class TemplateCoreImpl : TemplateCoreApi {
        @JvmField var currentSpeed: Float = 1f
        override fun getMediaResource(): TemplateMediaResource = TemplateMediaResource()
        override fun getCurrentMediaItem(): TemplateMediaItem = TemplateMediaItem()
        override fun getPlaySpeed(actual: Boolean): Float = currentSpeed
        fun setPlaySpeed(value: Float) { currentSpeed = value }
    }
    class TemplatePreparedListener(@JvmField val core: TemplateCoreApi) : TemplateOnPreparedListener {
        override fun onPrepared(player: TemplateIMediaPlayer) = Unit
    }
    class TemplateMediaPlayContext {
        @Suppress("UNUSED_PARAMETER") fun setOnPreparedListener(listener: TemplateOnPreparedListener) = Unit
    }
    interface TemplatePlayable { fun params(): TemplateParams }
    class TemplateSessionOwner {
        @JvmField val manager: TemplateSpeedManager = TemplateSpeedManager()
        fun active(): TemplatePlayable? = null
        @Suppress("UNUSED_PARAMETER") fun run(playable: TemplatePlayable?, continuation: Continuation<*>): Any = Unit
    }
    class TemplateSessionWrapper(
        @Suppress("UNUSED_PARAMETER") core: TemplateCoreApi,
        @Suppress("UNUSED_PARAMETER") owner: TemplateSessionOwner,
        @Suppress("UNUSED_PARAMETER") scope: CoroutineScope
    )

    companion object {
        @JvmStatic fun calculate(playable: Any?, scriptPace: Float?): Float = 3f
        @JvmStatic fun fakeTheseus2Calculate(@Suppress("UNUSED_PARAMETER") playable: Any?, @Suppress("UNUSED_PARAMETER") pace: Float?): Float = 3f
    }

    private fun locate(owner: Class<*>) = PlayerSpeedLocator.defaultSpeed(owner, Mutable::class.java, State::class.java)

    private class MenuHarness(default: Int = 0, spec: String = "800,101,100,10") {
        val registrar = PlayerPortTestRegistrar()
        val installer = PlayerSpeedFeatureInstaller(false, 275, default, spec)
        val environment = HookEnvironment(
            "tv.danmaku.bili", javaClass.classLoader, HookPointRegistry(javaClass.classLoader),
            registrar, { _, _ -> }, { _, _ -> }, { _, _ -> }
        )
        fun install(point: PlayerSpeedLocator.SpeedMenuPoint) = installer.installSpeedMenu(environment, point)
    }

    private fun modernAdapterPoint() = requireNotNull(PlayerSpeedLocator.speedMenuAdapter(ModernSpeedAdapter::class.java))
    private fun legacyAdapterPoint() = requireNotNull(PlayerSpeedLocator.speedMenuAdapter(LegacySpeedAdapter::class.java))

    private fun speedPoint() = PlayerSpeedLocator.DefaultSpeedPoint(
        Manager::class.java.getConstructor(),
        listOf("base", "temporary").map { Manager::class.java.getField(it) },
        Cell::class.java.getMethod("getValue"),
        Cell::class.java.getMethod("setValue", Any::class.java),
        listOf(Manager::class.java.getMethod("speed"))
    )

    private fun preparedPoint() = PlayerSpeedSessionLocator.Prepared(
        Context::class.java.getMethod("setOnPreparedListener", Prepared::class.java),
        CoreApi::class.java,
        Prepared::class.java,
        Any::class.java,
        CoreApi::class.java.getMethod("getMediaResource"),
        CoreApi::class.java.getMethod("getCurrentMediaItem"),
        Item::class.java.getMethod("getId"),
        Resource::class.java.getMethod("getPlayIndex"),
        Index::class.java.getField("mFrom"),
        CoreApi::class.java.getMethod("getPlaySpeed", Boolean::class.javaPrimitiveType),
        setOf("vod", "bangumi")
    )

    private inner class SessionHarness(requested: Float = 2f) {
        val registrar = PlayerPortTestRegistrar()
        val session = PlayerSpeedSessions(requested)
        val events = mutableListOf<FeatureRuntimeStage>()
        val env = HookEnvironment(
            "tv.danmaku.bili", javaClass.classLoader, HookPointRegistry(javaClass.classLoader),
            registrar, { _, _ -> }, { _, _ -> }, { _, _ -> },
            runtimeEvidence = { _, stage, _ -> events += stage }
        )
        init { session.installPrepared(env, preparedPoint(), speedPoint()) }
        fun attach(core: Core, context: Context = Context()): Pair<Listener, Context> {
            val listener = Listener(core)
            registrar.invoke("player.speed.prepared_registration", context, arrayOf(listener))
            return listener to context
        }
        fun set(core: Core, value: Float) {
            registrar.invoke("player.speed.prepared_value." + Core::class.java.name, core, arrayOf(value)) {
                core.setPlaySpeed(it[0] as Float)
            }
        }
        fun ready(listener: Listener, action: () -> Unit = { set(listener.core, listener.core.current) }) {
            registrar.invoke("player.speed.prepared_scope." + Listener::class.java.name, listener, arrayOf(Any())) { action() }
        }
    }

    @Test
    fun verifiesLocatorsMenuHooksAndTheseus2LongPress() {
        // Default speed locator shapes and ambiguity guards.
        listOf(OldManager::class.java to 2, NewManager::class.java to 1).forEach { (owner, count) ->
            val point = requireNotNull(locate(owner))
            assertEquals(count, point.speedGetters.size)
            assertEquals(2, point.flows.size)
            val target = point.constructor.newInstance()
            assertEquals(PlayerSpeedFeatureInstaller.DefaultSpeedResult.APPLIED,
                PlayerSpeedFeatureInstaller.applyDefaultSpeed(target, point, 1.25f))
            assertTrue(point.speedGetters.all { it.invoke(target) == 1.25f })
        }
        assertNull(locate(ExtraFlow::class.java))
        assertNull(locate(ExtraGetter::class.java))
        assertNull(PlayerSpeedLocator.defaultSpeed(NewManager::class.java, Flow::class.java, State::class.java))
        assertNull(PlayerSpeedLocator.defaultSpeed(NewManager::class.java, State::class.java, Mutable::class.java))

        // Speed menu locator (modern + legacy) & precision refinement.
        run {
            val candidates = listOf(SpeedOptionModel::class.java, SpeedOptionFactory::class.java)
            val factoryPoint = requireNotNull(PlayerSpeedLocator.speedMenuFactory(candidates, FakeMediaResource::class.java))
            val adapterPoint = requireNotNull(PlayerSpeedLocator.speedMenuAdapter(ModernSpeedAdapter::class.java, knownFactory = factoryPoint))
            val customMultipliers = listOf(3.0f, 1.1f, 1.05f, 1.0f, 0.5f)
            val builtOptions = requireNotNull(PlayerSpeedFeatureInstaller.buildOptionModels(
                factoryPoint.optionConstructor, factoryPoint.optionSpeedField, factoryPoint.optionAvailableField, customMultipliers
            ))
            assertEquals(5, builtOptions.size)
            assertTrue(PlayerSpeedFeatureInstaller.matchesOptionModels(
                builtOptions, factoryPoint.optionSpeedField, factoryPoint.optionAvailableField, customMultipliers
            ))
            val adapter = ModernSpeedAdapter(1)
            @Suppress("UNCHECKED_CAST")
            adapter.l0(1.05f, builtOptions as List<SpeedOptionModel>)
            assertEquals(3, adapter.c.count { it.b })
            assertTrue(PlayerSpeedFeatureInstaller.refineAdapterItems(adapter, adapterPoint, 1.05f, customMultipliers))
            assertEquals(listOf(false, false, true, false, false), adapter.c.map { it.b })
            assertTrue(adapter.c.all { it.c })

            val legacyPoint = legacyAdapterPoint()
            assertNotNull(legacyPoint.speedsArrayField)
            val legacyAdapter = LegacySpeedAdapter(1)
            val custom = listOf(4.0f, 1.25f, 1.2f, 1.0f, 0.25f)
            legacyPoint.speedsArrayField!!.set(legacyAdapter, custom.toFloatArray())
            legacyAdapter.K0(1.2f)
            assertEquals(2, legacyAdapter.c.count { it.b })
            assertTrue(PlayerSpeedFeatureInstaller.refineAdapterItems(legacyAdapter, legacyPoint, 1.2f, custom))
            assertEquals(listOf(false, false, true, false, false), legacyAdapter.c.map { it.b })
        }

        // Theseus2 calculator resolution across builds, dex revalidation, gates, and legacy fallback.
        run {
            val builds = listOf(
                Triple(9200100, CalcOwnerI::class.java, "i"),
                Triple(9200200, CalcOwnerH::class.java, "h"),
                Triple(9200300, CalcOwnerE::class.java, "e"),
                Triple(9210100, CalcOwnerA::class.java, "a"),
                Triple(9210200, CalcOwnerD::class.java, "d"),
                Triple(9210300, CalcOwnerD::class.java, "d")
            )
            for ((version, owner, methodName) in builds) {
                val buildLoader = CalcLoader(owner, CalcPlayable::class.java)
                val method = PlayerSpeedLocator.theseus2LongPressCalculator(buildLoader, version)
                assertNotNull("Build $version should resolve calculator", method)
                assertEquals(methodName, method!!.name)
                assertEquals(owner, method.declaringClass)
                for ((other, _, otherMethod) in builds) {
                    if (other == version || otherMethod == methodName) continue
                    assertNull(PlayerSpeedLocator.theseus2LongPressCalculator(buildLoader, other))
                }
            }
            val loader920 = CalcLoader(CalcOwnerI::class.java, CalcPlayable::class.java)
            assertNull(PlayerSpeedLocator.theseus2LongPressCalculator(loader920, 9190500))
            assertNull(PlayerSpeedLocator.theseus2LongPressCalculator(loader920, 9200400))
            assertNull(PlayerSpeedLocator.theseus2LongPressCalculator(loader920, 9210400))
            val only9211Loader = TestRenamingClassLoader(
                parent = javaClass.classLoader!!,
                templates = mapOf("n9h" to CalcOwnerA::class.java, "rbq2" to CalcPlayable::class.java)
            )
            // Keep CalcLoader exact mapping for static verification without CP side effects.
            val exactOnly9211 = object : ClassLoader(javaClass.classLoader) {
                override fun loadClass(name: String, resolve: Boolean): Class<*> = when (name) {
                    "n9h" -> CalcOwnerA::class.java
                    "rbq2" -> CalcPlayable::class.java
                    else -> super.loadClass(name, resolve)
                }
            }
            assertNotNull(only9211Loader)
            assertNotNull(PlayerSpeedLocator.theseus2LongPressCalculator(exactOnly9211, 9210100))
            assertNull(PlayerSpeedLocator.theseus2LongPressCalculator(exactOnly9211, 9210200))
            assertNull(PlayerSpeedLocator.theseus2LongPressCalculator(exactOnly9211, 9210300))

            assertNull(PlayerSpeedLocator.theseus2LongPressCalculator(CalcLoader(CalcOwnerI::class.java, CalcPlayableClass::class.java), 9200100))
            assertNull(PlayerSpeedLocator.theseus2LongPressCalculator(CalcLoader(CalcOwnerNonStatic::class.java, CalcPlayable::class.java), 9200100))

            val loader = javaClass.classLoader!!
            val methodI = CalcOwnerI::class.java.getMethod("i", CalcPlayable::class.java, Float::class.javaObjectType)
            val selected = requireNotNull(PlayerSpeedLocator.selectCalculator(loader, DexAssistResult.Candidates(listOf(methodI))))
            assertEquals(CalcOwnerI::class.java.name, selected.owner)
            assertEquals("i", selected.method)
            val decoded = requireNotNull(LongPressCalculatorPoint.fromJson(selected.toJson()))
            assertEquals(selected, decoded)
            assertEquals(methodI, PlayerSpeedLocator.resolveCalculator(loader, decoded))
            val methodH = CalcOwnerH::class.java.getMethod("h", CalcPlayable::class.java, Float::class.javaObjectType)
            assertNull(PlayerSpeedLocator.selectCalculator(loader, DexAssistResult.Candidates(listOf(methodI, methodH))))
            assertNull(PlayerSpeedLocator.selectCalculator(loader, DexAssistResult.Candidates(List(33) { methodI })))

            assertFalse(PlayerSpeedLocator.calculatorNeedsQuery(false, true, false))
            assertFalse(PlayerSpeedLocator.calculatorNeedsQuery(true, false, false))
            assertFalse(PlayerSpeedLocator.calculatorNeedsQuery(true, true, true))
            assertTrue(PlayerSpeedLocator.calculatorNeedsQuery(true, true, false))
            assertTrue(PlayerSpeedLocator.calculatorRefreshCache(true, true, false, false, false))
            assertFalse(PlayerSpeedLocator.calculatorRefreshCache(true, true, false, false, true))
            assertFalse(PlayerSpeedLocator.calculatorRefreshCache(true, true, false, true, false))

            for (version in listOf(9180100, 9180200, 9180300, 9190200, 9190400)) {
                val method = PlayerSpeedLocator.theseus2LongPressCalculator(CalcLoader(CalcOwnerA::class.java, CalcPlayable::class.java), version)
                assertNotNull(method)
                assertEquals("a", method!!.name)
            }
        }

        // Menu hooks (modern, legacy, rollback, precision, config rejection, compact route, malformed items).
        run {
            val factory = requireNotNull(PlayerSpeedLocator.speedMenuFactory(
                listOf(SpeedOptionFactory::class.java, SpeedOptionModel::class.java), FakeMediaResource::class.java
            ))
            val h = MenuHarness()
            assertTrue(h.install(PlayerSpeedLocator.SpeedMenuPoint(factory, modernAdapterPoint())))
            val options = h.registrar.invoke("player.speed.menu_options_factory", args = arrayOf(null, null, false, 0)) {
                listOf(SpeedOptionModel(2f, false))
            } as List<*>
            assertEquals(listOf(8f, 1.01f, 1f, 0.1f), options.map { (it as SpeedOptionModel).a })
            val adapter = ModernSpeedAdapter(1)
            h.registrar.invoke("player.speed.menu_adapter_populate", adapter, arrayOf(1f, emptyList<Any>())) { args ->
                @Suppress("UNCHECKED_CAST")
                adapter.l0(args[0] as Float, args[1] as List<SpeedOptionModel>)
            }
            assertEquals(listOf(false, false, true, false), adapter.c.map { it.b })
            assertTrue(adapter.c.all { it.c })
            h.registrar.invoke("player.speed.menu_adapter_populate", adapter, arrayOf(1.01f, options)) { args ->
                @Suppress("UNCHECKED_CAST")
                adapter.l0(args[0] as Float, args[1] as List<SpeedOptionModel>)
            }
            assertEquals(listOf(false, true, false, false), adapter.c.map { it.b })

            val hLegacy = MenuHarness()
            assertTrue(hLegacy.install(PlayerSpeedLocator.SpeedMenuPoint(null, legacyAdapterPoint())))
            val legacyAdapter = LegacySpeedAdapter(1)
            val original = legacyAdapter.d
            val failure = IllegalStateException("host failure")
            assertSame(failure, assertThrows(IllegalStateException::class.java) {
                hLegacy.registrar.invoke("player.speed.menu_adapter_populate", legacyAdapter, arrayOf(1f)) { throw failure }
            })
            assertSame(original, legacyAdapter.d)
            hLegacy.registrar.invoke("player.speed.menu_adapter_populate", legacyAdapter, arrayOf(1f)) { legacyAdapter.K0(it[0] as Float) }
            assertArrayEquals(floatArrayOf(8f, 1.01f, 1f, 0.1f), legacyAdapter.d, 0f)
            assertEquals(listOf(false, false, true, false), legacyAdapter.c.map { it.b })

            val hFail = MenuHarness()
            hFail.registrar.failId = "player.speed.menu_adapter_populate"
            assertThrows(IllegalStateException::class.java) {
                hFail.install(PlayerSpeedLocator.SpeedMenuPoint(factory, modernAdapterPoint()))
            }
            assertFalse("player.speed.menu_options_factory" in hFail.registrar.hooks)
            val missing = MenuHarness()
            assertFalse(missing.install(PlayerSpeedLocator.SpeedMenuPoint(factory, null)))
            assertTrue(missing.registrar.hooks.isEmpty())

            val point2x = legacyAdapterPoint()
            val adapter2x = LegacySpeedAdapter(1)
            adapter2x.d = floatArrayOf(2f, 1f)
            adapter2x.K0(1.99f)
            assertTrue(PlayerSpeedFeatureInstaller.refineAdapterItems(adapter2x, point2x, 1.99f, listOf(2f, 1f)))
            assertEquals(listOf(true, false), adapter2x.c.map { it.b })
            adapter2x.d = floatArrayOf(2f, 1.99f, 1f)
            adapter2x.K0(1.99f)
            assertTrue(PlayerSpeedFeatureInstaller.refineAdapterItems(adapter2x, point2x, 1.99f, listOf(2f, 1.99f, 1f)))
            assertEquals(listOf(false, true, false), adapter2x.c.map { it.b })

            for (bad in listOf(MenuHarness(default = 150), MenuHarness(spec = "100,bad"), MenuHarness(spec = ""))) {
                assertFalse(bad.install(PlayerSpeedLocator.SpeedMenuPoint(null, legacyAdapterPoint())))
                assertTrue(bad.registrar.hooks.isEmpty())
                assertTrue("player_long_press_speed_percent" in bad.installer.capabilityIds)
                assertFalse(PlayerSpeedFeatureInstaller.MENU_CAPABILITY in bad.installer.capabilityIds)
            }

            val route = requireNotNull(PlayerSpeedLocator.speedMenuCompactRoute(
                CompactOwner::class.java, FunctionService::class.java, FakeKeelPlayer::class.java
            ))
            assertNull(PlayerSpeedLocator.speedMenuCompactRoute(
                AmbiguousOwner::class.java, FunctionService::class.java, FakeKeelPlayer::class.java
            ))
            val hCompact = MenuHarness()
            hCompact.install(PlayerSpeedLocator.SpeedMenuPoint(null, modernAdapterPoint(), compactRoute = route))
            val target = CompactOwner()
            val other = CompactOwner()
            val window = WindowDelegate()
            fun windowFor(owner: CompactOwner) = hCompact.registrar.invoke("player.speed.menu_compact_window_delegate", owner) { window }
            assertSame(window, windowFor(target))
            try {
                hCompact.registrar.invoke("player.speed.menu_compact_open", args = arrayOf(target)) {
                    assertNull(windowFor(target))
                    assertSame(window, windowFor(other))
                    hCompact.registrar.invoke("player.speed.menu_compact_open", args = arrayOf(other)) {
                        assertNull(windowFor(other))
                    }
                    assertNull(windowFor(target))
                    throw IllegalStateException("host failure")
                }
                fail("Host exception must remain visible")
            } catch (_: IllegalStateException) {
            }
            assertSame(window, windowFor(target))
            assertSame(window, windowFor(other))

            val malformedAdapter = ModernSpeedAdapter(1)
            malformedAdapter.l0(1f, listOf(SpeedOptionModel(Float.NaN, true)))
            assertFalse(PlayerSpeedFeatureInstaller.refineAdapterItems(malformedAdapter, modernAdapterPoint(), 1f, listOf(1f)))
            assertFalse(malformedAdapter.c.single().b)
            assertFalse(PlayerSpeedFeatureInstaller.matchesOptionModels(
                listOf(SpeedOptionModel(Float.NaN, true)),
                SpeedOptionModel::class.java.getField("a"),
                SpeedOptionModel::class.java.getField("b"),
                listOf(1f)
            ))
        }

        // PlayerSpeedSessionAbi + null menu retry + theseus2 shared press scope failure recovery.
        run {
            val abiPoint = requireNotNull(PlayerSpeedSessionLocator.modern(
                Fixture.Owner::class.java, Fixture.Params::class.java, Fixture.Manager::class.java, Fixture.Wrapper::class.java
            ))
            assertEquals("run", abiPoint.run.name)
            assertEquals("active", abiPoint.active.name)
            assertEquals("params", abiPoint.params.name)
            assertEquals(Fixture.Wrapper::class.java, abiPoint.wrapper?.declaringClass)
            assertNull(PlayerSpeedSessionLocator.modern(Fixture.Owner::class.java, Fixture.Params::class.java, Fixture.Manager::class.java, null))
            assertNull(PlayerSpeedSessionLocator.modern(Fixture.Owner::class.java, Any::class.java, Fixture.Manager::class.java, Fixture.Wrapper::class.java))

            val loader = javaClass.classLoader!!
            fun abiEnv(registrar: PlayerPortTestRegistrar, stages: MutableList<FeatureRuntimeStage>) = HookEnvironment(
                "tv.danmaku.bili", loader, HookPointRegistry(loader), registrar, { _, _ -> }, { _, _ -> }, { _, _ -> },
                runtimeEvidence = { _, stage, _ -> stages += stage }
            )

            val offReg = PlayerPortTestRegistrar()
            val offStages = mutableListOf<FeatureRuntimeStage>()
            val method = requireNotNull(PlayerSpeedLocator.theseus2LongPress(
                PressService::class.java, PressLaunch::class.java, Continuation::class.java, listOf(PressPort::class.java)
            ))
            assertEquals(PressPort::class.java.getMethod("start"), method)
            assertNull(PlayerSpeedLocator.theseus2LongPress(
                PressService::class.java, PressLaunch::class.java, Continuation::class.java,
                listOf(PressPort::class.java, AmbiguousPort::class.java)
            ))
            PlayerSpeedFeatureInstaller(true, 300, 100).installTheseus2LongPressOff(abiEnv(offReg, offStages), method)
            assertEquals(setOf("player.speed.disable_long_press_theseus2"), offReg.hooks.keys)
            assertNull(offReg.invoke("player.speed.disable_long_press_theseus2", PressPort(PressService())) { error("host start must not run") })
            assertEquals(listOf(FeatureRuntimeStage.OBSERVED, FeatureRuntimeStage.APPLIED), offStages)

            val speedReg = PlayerPortTestRegistrar()
            val speedStages = mutableListOf<FeatureRuntimeStage>()
            val port = PressPort(PressService())
            val start = port.javaClass.getMethod("start")
            val calculator = PlayerSpeedHooksAndSessionsTest::class.java.getMethod("calculate", Any::class.java, Float::class.javaObjectType)
            PlayerSpeedFeatureInstaller(false, 175, 0).installTheseus2LongPressSpeed(abiEnv(speedReg, speedStages), start, calculator, 1.75f)
            val startId = "player.speed.theseus2_long_press_scope"
            val calculateId = "player.speed.theseus2_calculate_long_press"
            fun calculate(pace: Float? = null) = speedReg.invoke(calculateId, args = arrayOf(Any(), pace)) { 3f }
            assertEquals(3f, calculate())
            var playbackSpeed: Any? = null
            val indicatorSpeed = speedReg.invoke(startId, port) { calculate().also { playbackSpeed = it } }
            assertEquals(1.75f, playbackSpeed)
            assertEquals(playbackSpeed, indicatorSpeed)
            assertEquals(listOf(FeatureRuntimeStage.OBSERVED, FeatureRuntimeStage.APPLIED), speedStages)
            speedStages.clear()
            assertEquals(3f, speedReg.invoke(startId, port) { calculate(4f) })
            assertThrows(IllegalStateException::class.java) { speedReg.invoke(startId, port) { calculate(); error("start failed") } }
            assertEquals(3f, calculate())
            assertTrue(speedStages.isEmpty())
            assertNull(PlayerSpeedLocator.theseus2LongPressCalculator(loader, 9190500))

            // Preserves menuAdapterInstalled on null retry & shares theseus2PressScope across Hook2 failure.
            val menuTemplates = linkedMapOf<String, Class<*>>(
                "${PlayerSpeedLocator.SPEED_WIDGET_PKG}.f" to FakeListOnlySpeedAdapter::class.java,
                "${PlayerSpeedLocator.SPEED_WIDGET_PKG}.f\$Item" to FakeListOnlySpeedAdapter.Item::class.java,
                "test.phase1.ListOptionModel" to FakeListOnlyOptionModel::class.java
            )
            val menuReplacements = mapOf(
                FakeListOnlySpeedAdapter::class.java.name.replace('.', '/') to "${PlayerSpeedLocator.SPEED_WIDGET_PKG.replace('.', '/')}/f",
                FakeListOnlySpeedAdapter.Item::class.java.name.replace('.', '/') to "${PlayerSpeedLocator.SPEED_WIDGET_PKG.replace('.', '/')}/f\$Item",
                FakeListOnlyOptionModel::class.java.name.replace('.', '/') to "test/phase1/ListOptionModel"
            )
            val menuLoader = TestRenamingClassLoader(parent = loader, templates = menuTemplates, replacements = menuReplacements)
            val regMenu = PlayerPortTestRegistrar()
            val menuInstaller = PlayerSpeedFeatureInstaller(disableLongPress = false, longPressPercent = 0, defaultPercent = 0, speedMenuSpec = "200,150,100")
            val envMenu1 = HookEnvironment("tv.danmaku.bili", menuLoader, HookPointRegistry(menuLoader), regMenu, { _, _ -> }, { _, _ -> }, { _, _ -> })
            val menuRun1 = menuInstaller.install(envMenu1) as FeatureInstallResult.Installed
            assertEquals(1, menuRun1.hookCount)
            assertFalse("player.speed.menu_options_factory" in regMenu.hooks.keys)
            assertTrue(menuInstaller.requiresAdaptationRetry)

            val hiddenMenuLoader = TestRenamingClassLoader(parent = loader, hiddenPrefixes = setOf("${PlayerSpeedLocator.SPEED_WIDGET_PKG}."))
            val envMenu2 = envMenu1.copy(classLoader = hiddenMenuLoader, hookPoints = HookPointRegistry(hiddenMenuLoader))
            val menuRun2 = menuInstaller.install(envMenu2) as FeatureInstallResult.Installed
            assertEquals(1, menuRun2.hookCount)
            assertFalse(menuInstaller.requiresAdaptationRetry)
            assertEquals(1, regMenu.registrationAttempts.size)

            val scopeReg = PlayerPortTestRegistrar(failId = "player.speed.theseus2_long_press_scope")
            val scopeInstaller = PlayerSpeedFeatureInstaller(disableLongPress = false, longPressPercent = 175, defaultPercent = 0)
            val scopeEnv = HookEnvironment("tv.danmaku.bili", loader, HookPointRegistry(loader), scopeReg, { _, _ -> }, { _, _ -> }, { _, _ -> })
            val startMethod = FakeTheseus2Port::class.java.getMethod("start")
            val calcMethod = PlayerSpeedHooksAndSessionsTest::class.java.getMethod("fakeTheseus2Calculate", Any::class.java, Float::class.javaObjectType)
            assertTrue(runCatching { scopeInstaller.installTheseus2LongPressSpeed(scopeEnv, startMethod, calcMethod, 1.75f) }.isFailure)
            assertEquals(1, scopeReg.hooks.size)
            assertTrue("player.speed.theseus2_calculate_long_press" in scopeReg.hooks.keys)
            scopeReg.failId = null
            scopeInstaller.installTheseus2LongPressSpeed(scopeEnv, startMethod, calcMethod, 1.75f)
            scopeInstaller.installTheseus2LongPressSpeed(scopeEnv, startMethod, calcMethod, 1.75f)
            assertEquals(1, scopeReg.registrationAttempts.count { it.first == "player.speed.theseus2_calculate_long_press" })
            assertEquals(2, scopeReg.registrationAttempts.count { it.first == "player.speed.theseus2_long_press_scope" })
            assertEquals(2, scopeReg.hooks.size)
            var playbackResult: Any? = null
            val indicatorResult = scopeReg.invoke("player.speed.theseus2_long_press_scope", FakeTheseus2Port()) {
                scopeReg.invoke("player.speed.theseus2_calculate_long_press", args = arrayOf(Any(), null)) { 3f }.also { playbackResult = it }
            }
            assertEquals(1.75f, playbackResult)
            assertEquals(1.75f, indicatorResult)
        }
    }

    @Test
    fun verifiesSessionsPreparedHooksAndCoordinatorRetries() {
        // PlayerSpeedSessions behaviors.
        run {
            val manager = Manager()
            val session = PlayerSpeedSessions(2f)
            session.capture(manager, speedPoint())
            assertTrue(session.applyForMedia(manager, "1:1", speedPoint()))
            manager.base.value = 1.5f
            manager.temporary.value = 3f
            assertFalse(session.applyForMedia(manager, "1:1", speedPoint()))
            assertEquals(1.5f, manager.base.value)
            assertEquals(3f, manager.temporary.value)
            assertTrue(session.applyForMedia(manager, "1:2", speedPoint()))
            assertEquals(2f, manager.base.value)
            assertEquals(3f, manager.temporary.value)
            manager.temporary.value = null
            assertEquals(2f, manager.speed(), 0f)
        }
        run {
            val session = PlayerSpeedSessions(1f)
            val first = Manager()
            val second = Manager()
            session.capture(first, speedPoint())
            session.capture(second, speedPoint())
            first.base.value = 2f
            second.base.value = 3f
            assertTrue(session.applyForMedia(first, "1:1", speedPoint()))
            assertEquals(3f, second.base.value)
            assertTrue(session.applyForMedia(second, "1:1", speedPoint()))
            assertEquals(1f, second.base.value)
        }
        run {
            val session = PlayerSpeedSessions(2f)
            val manager = Manager()
            assertFalse(session.applyForMedia(manager, "a", speedPoint()))
            manager.temporary.value = 1f
            session.capture(manager, speedPoint())
            assertFalse(session.applyForMedia(manager, "a", speedPoint()))
            assertEquals(1f, manager.base.value)
        }
        run {
            val h = SessionHarness()
            val core = Core()
            val (listener, context) = h.attach(core)
            h.ready(listener); assertEquals(2f, core.current, 0f)
            h.set(core, 1.5f)
            h.ready(listener); assertEquals(1.5f, core.current, 0f)
            core.item = Item("b")
            h.ready(listener); assertEquals(2f, core.current, 0f)
            assertNotNull(context)
        }
        run {
            val h = SessionHarness()
            val context = Context()
            val first = Core()
            val (listener, _) = h.attach(first, context)
            h.ready(listener)
            val next = Core().apply { current = 1.5f }
            val (nextListener, _) = h.attach(next, context)
            h.ready(nextListener)
            assertEquals(1.5f, next.current, 0f)
        }
        run {
            val h = SessionHarness()
            val core = Core()
            val (listener, context) = h.attach(core)
            val manager = Manager()
            h.session.capture(manager, speedPoint())
            h.session.bind(core, manager)
            h.session.applyForMedia(manager, "1:1", speedPoint())
            h.ready(listener); assertEquals(2f, core.current, 0f)
            manager.temporary.value = 3f
            h.ready(listener); assertEquals(3f, core.current, 0f)
            manager.temporary.value = null; manager.base.value = 1.5f
            h.ready(listener); assertEquals(1.5f, core.current, 0f)
            assertNotNull(context)
        }
        run {
            val h = SessionHarness()
            val core = Core()
            val (listener, context) = h.attach(core)
            val failure = IllegalStateException("host")
            assertSame(failure, assertThrows(IllegalStateException::class.java) {
                h.ready(listener) {
                    h.registrar.invoke("player.speed.prepared_value." + Core::class.java.name, core, arrayOf(1f)) { throw failure }
                }
            })
            h.set(core, 1.25f); assertEquals(1.25f, core.current, 0f)
            h.ready(listener); assertEquals(2f, core.current, 0f)
            assertNotNull(context)
        }
        run {
            val h = SessionHarness()
            val one = Core()
            val two = Core()
            val (a, c1) = h.attach(one)
            val (b, c2) = h.attach(two)
            h.ready(a) {
                h.set(two, 1.25f); assertEquals(1.25f, two.current, 0f)
                h.ready(b); assertEquals(2f, two.current, 0f)
                h.set(one, 1f)
            }
            assertEquals(2f, one.current, 0f)
            h.set(one, 1.75f); assertEquals(1.75f, one.current, 0f)
            assertNotNull(c1); assertNotNull(c2)
        }
        run {
            val h = SessionHarness()
            val core = Core()
            val (listener, context) = h.attach(core)
            for (source in listOf("live", "music", "unknown", "")) {
                core.resource.source = source; core.current = 1.25f
                h.ready(listener); assertEquals(1.25f, core.current, 0f)
            }
            core.resource.source = "vod"; core.item = Item("")
            h.ready(listener); assertEquals(1.25f, core.current, 0f)
            assertFalse(FeatureRuntimeStage.APPLIED in h.events)
            assertNotNull(context)
        }
        run {
            val h = SessionHarness()
            val owner = Owner()
            val point = PlayerSpeedSessionLocator.Modern(
                Owner::class.java.getMethod("run", Playable::class.java, Any::class.java),
                Owner::class.java.getMethod("getActive"),
                Playable::class.java.getMethod("getParams"),
                Params::class.java.getMethod("getAvid"),
                Params::class.java.getMethod("getCid"),
                Owner::class.java.getField("manager"),
                null
            )
            h.session.capture(owner.manager, speedPoint())
            h.session.installModern(h.env, point, speedPoint())
            val first = Playable(Params(1, 1))
            val queued = Playable(Params(1, 2))
            owner.active = first
            fun invoke(argument: Playable?) = h.registrar.invoke("player.speed.media_session", owner, arrayOf(argument, null)) { Any() }
            invoke(first); assertEquals(2f, owner.manager.base.value)
            owner.manager.base.value = 1.5f
            invoke(queued); assertEquals(1.5f, owner.manager.base.value)
            owner.active = queued
            invoke(null); assertEquals(2f, owner.manager.base.value)
            owner.manager.base.value = 1.25f
            owner.active = Playable(Params(1, 0))
            invoke(null); assertEquals(1.25f, owner.manager.base.value)
            val failure = IllegalStateException("host coroutine")
            assertSame(failure, assertThrows(IllegalStateException::class.java) {
                h.registrar.invoke("player.speed.media_session", owner, arrayOf(queued, null)) { throw failure }
            })
        }
        for (value in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, 0.09f, 8.01f)) {
            assertThrows(IllegalArgumentException::class.java) { PlayerSpeedSessions(value) }
        }
        run {
            val session = PlayerSpeedSessions(2f)
            val manager = Manager()
            session.capture(manager, speedPoint())
            PlayerSpeedFeatureInstaller.applyDefaultSpeed(manager, speedPoint(), 2f)
            session.initialized(manager)
            manager.base.value = 1.5f
            assertFalse(session.applyForMedia(manager, "1:1", speedPoint()))
            assertEquals(1.5f, manager.base.value)
            assertTrue(session.applyForMedia(manager, "1:2", speedPoint()))
            assertEquals(2f, manager.base.value)
        }
        run {
            val h = SessionHarness()
            val core = Core()
            val (listener, context) = h.attach(core)
            core.resource.throwAt = 2
            h.ready(listener)
            assertEquals(1f, core.current, 0f)
            assertFalse(FeatureRuntimeStage.APPLIED in h.events)
            h.set(core, 1.5f)
            assertEquals(1.5f, core.current, 0f)
            assertNotNull(context)
        }
        run {
            val session = PlayerSpeedSessions(2f)
            val manager = Manager()
            session.capture(manager, speedPoint())
            session.discardCapture(manager)
            assertFalse(session.applyForMedia(manager, "1:1", speedPoint()))
            assertEquals(1f, manager.base.value)
        }

        // Coordinator retry preserving verified capabilities and live prepared counts.
        val parent = requireNotNull(javaClass.classLoader)
        fun createSpeedLoader(): ClassLoader {
            val templates = linkedMapOf<String, Class<*>>(
                "kotlinx.coroutines.flow.StateFlow" to TemplateStateFlow::class.java,
                "kotlinx.coroutines.flow.MutableStateFlow" to TemplateMutableFlow::class.java,
                "test.phase1.FlowImpl" to TemplateFlowImpl::class.java,
                PlayerSpeedLocator.SPEED_MANAGER to TemplateSpeedManager::class.java,
                PlayerSpeedSessionLocator.PARAMS to TemplateParams::class.java,
                PlayerSpeedSessionLocator.INDEX to TemplatePlayIndex::class.java,
                "${PlayerSpeedSessionLocator.INDEX}\$Companion" to TemplatePlayIndex.Companion::class.java,
                PlayerSpeedSessionLocator.RESOURCE to TemplateMediaResource::class.java,
                PlayerSpeedSessionLocator.ITEM to TemplateMediaItem::class.java,
                PlayerSpeedSessionLocator.PLAYER to TemplateIMediaPlayer::class.java,
                PlayerSpeedSessionLocator.PREPARED to TemplateOnPreparedListener::class.java,
                PlayerSpeedSessionLocator.CORE to TemplateCoreApi::class.java,
                PlayerSpeedSessionLocator.CONTEXT to TemplateMediaPlayContext::class.java,
                "com.bilibili.ship.theseus.keel.player.Phase1Playable" to TemplatePlayable::class.java,
                "test.phase1.SessionOwner" to TemplateSessionOwner::class.java,
                "test.phase1.SessionWrapper" to TemplateSessionWrapper::class.java,
                "test.phase1.CoreImpl" to TemplateCoreImpl::class.java,
                "test.phase1.PreparedListener" to TemplatePreparedListener::class.java
            )
            val replacements = templates.entries.associate { (targetName, tpl) ->
                tpl.name.replace('.', '/') to targetName.replace('.', '/')
            }
            return TestRenamingClassLoader(
                parent = parent,
                templates = templates,
                replacements = replacements,
                hiddenNames = setOf(
                    PlayerSpeedSessionLocator.RUN,
                    PlayerSpeedLocator.THESEUS_SPEED_WIDGET,
                    PlayerSpeedLocator.LONG_PRESS_COROUTINE
                )
            )
        }

        run {
            val loader = createSpeedLoader()
            val registrar = PlayerPortTestRegistrar()
            val statuses = linkedMapOf<String, String>()
            val events = mutableListOf<FeatureInstallRecord>()
            val env = HookEnvironment(
                processName = "tv.danmaku.bili",
                classLoader = loader,
                hookPoints = HookPointRegistry(loader),
                registrar = registrar,
                logInfo = { _, _ -> },
                logError = { _, _ -> },
                reportStatus = { k, v -> statuses[k] = v },
                installationEvidence = { events += it }
            )
            val coordinator = FeatureInstallCoordinator(env)
            var sessionClasses: PlayerSpeedSessionClasses? = null
            val installer = PlayerSpeedFeatureInstaller(
                disableLongPress = true, longPressPercent = 0, defaultPercent = 150, speedMenuSpec = "",
                hostVersionCode = 9210100, cachedSessionClassesProvider = { sessionClasses }
            )
            coordinator.installAll(listOf(installer))
            val run1 = events.last { it.id == PlayerSpeedFeatureInstaller.ID }.result as FeatureInstallResult.Installed
            assertEquals(3, run1.hookCount)
            assertFalse(run1.complete)
            assertTrue(installer.requiresAdaptationRetry)
            assertEquals(3, registrar.registrationAttempts.size)

            events.clear()
            sessionClasses = PlayerSpeedSessionClasses("test.phase1.SessionOwner", "test.phase1.SessionWrapper")
            coordinator.installAll(listOf(installer))
            assertFalse(installer.requiresAdaptationRetry)
            assertEquals(5, registrar.registrationAttempts.size)
            val counts = registrar.registrationAttempts.map { it.first }.groupingBy { it }.eachCount()
            assertEquals(1, counts["player.speed.disable_long_press"])
            assertEquals(1, counts["player.speed.initial_value"])
            assertEquals(1, counts["player.speed.prepared_registration"])
            assertEquals(1, counts["player.speed.media_session"])
            assertEquals(1, counts["player.speed.core_bridge"])
            for (cap in installer.capabilityIds) {
                assertTrue(events.lastOrNull { it.id == cap }?.result is FeatureInstallResult.Installed)
            }
            events.clear()
            val ctx = loader.loadClass(PlayerSpeedSessionLocator.CONTEXT).getConstructor().newInstance()
            val core = loader.loadClass("test.phase1.CoreImpl").getConstructor().newInstance()
            val listener = loader.loadClass("test.phase1.PreparedListener")
                .getConstructor(loader.loadClass(PlayerSpeedSessionLocator.CORE)).newInstance(core)
            registrar.invoke("player.speed.prepared_registration", receiver = ctx, args = arrayOf(listener))
            assertEquals("success", statuses["player_speed_status"])
            assertEquals(FeatureInstallResult.Installed(5, complete = true), events.last { it.id == PlayerSpeedFeatureInstaller.ID }.result)
        }

        run {
            val loader = createSpeedLoader()
            val registrar = PlayerPortTestRegistrar()
            val statuses = linkedMapOf<String, String>()
            val events = mutableListOf<FeatureInstallRecord>()
            val env = HookEnvironment(
                processName = "tv.danmaku.bili",
                classLoader = loader,
                hookPoints = HookPointRegistry(loader),
                registrar = registrar,
                logInfo = { _, _ -> },
                logError = { _, _ -> },
                reportStatus = { k, v -> statuses[k] = v },
                installationEvidence = { events += it }
            )
            val coordinator = FeatureInstallCoordinator(env)
            var sessionClasses: PlayerSpeedSessionClasses? = null
            val installer = PlayerSpeedFeatureInstaller(
                disableLongPress = false, longPressPercent = 275, defaultPercent = 150, speedMenuSpec = "",
                hostVersionCode = 9210100, cachedSessionClassesProvider = { sessionClasses }
            )
            coordinator.installAll(listOf(installer))
            assertEquals(2, registrar.registrationAttempts.size)
            assertTrue(installer.requiresAdaptationRetry)

            sessionClasses = PlayerSpeedSessionClasses("test.phase1.SessionOwner", "test.phase1.SessionWrapper")
            coordinator.installAll(listOf(installer))
            assertEquals(4, registrar.registrationAttempts.size)
            assertFalse(installer.requiresAdaptationRetry)
            events.clear()
            val ctx = loader.loadClass(PlayerSpeedSessionLocator.CONTEXT).getConstructor().newInstance()
            val core = loader.loadClass("test.phase1.CoreImpl").getConstructor().newInstance()
            val listener = loader.loadClass("test.phase1.PreparedListener")
                .getConstructor(loader.loadClass(PlayerSpeedSessionLocator.CORE)).newInstance(core)
            registrar.invoke("player.speed.prepared_registration", receiver = ctx, args = arrayOf(listener))
            assertEquals("partial:4/5", statuses["player_speed_status"])
            assertEquals(FeatureInstallResult.Installed(4, complete = false), events.last { it.id == PlayerSpeedFeatureInstaller.ID }.result)
        }

        run {
            val loader = createSpeedLoader()
            val registrar = PlayerPortTestRegistrar()
            val events = mutableListOf<FeatureInstallRecord>()
            val env = HookEnvironment(
                processName = "tv.danmaku.bili",
                classLoader = loader,
                hookPoints = HookPointRegistry(loader),
                registrar = registrar,
                logInfo = { _, _ -> },
                logError = { _, _ -> },
                reportStatus = { _, _ -> },
                installationEvidence = { events += it }
            )
            val coordinator = FeatureInstallCoordinator(env)
            var menuPoints: PlayerSpeedMenuPoints? = null
            val installer = PlayerSpeedFeatureInstaller(
                disableLongPress = true, longPressPercent = 0, defaultPercent = 0, speedMenuSpec = "200,150,100",
                hostVersionCode = 9210100, cachedMenuPointsProvider = { menuPoints }
            )
            coordinator.installAll(listOf(installer))
            assertEquals(1, registrar.registrationAttempts.size)
            assertTrue(installer.requiresAdaptationRetry)

            events.clear()
            menuPoints = PlayerSpeedMenuPoints(
                adapter = VersionAdapter.HookPoint(RenamedSpeedAdapter::class.java.name, "populate", listOf("float"))
            )
            coordinator.installAll(listOf(installer))
            assertFalse(installer.requiresAdaptationRetry)
            assertEquals(2, registrar.registrationAttempts.size)
            val counts = registrar.registrationAttempts.map { it.first }.groupingBy { it }.eachCount()
            assertEquals(1, counts["player.speed.disable_long_press"])
            assertEquals(1, counts["player.speed.menu_adapter_populate"])
            assertTrue(events.last { it.id == "player_long_press_disabled" }.result is FeatureInstallResult.Installed)
            assertTrue(events.last { it.id == PlayerSpeedFeatureInstaller.MENU_CAPABILITY }.result is FeatureInstallResult.Installed)
        }
    }
}
