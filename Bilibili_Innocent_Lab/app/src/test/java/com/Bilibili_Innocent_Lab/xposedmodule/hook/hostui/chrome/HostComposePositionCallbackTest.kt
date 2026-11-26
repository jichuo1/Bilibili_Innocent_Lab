package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostComposeCardPositionAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top.HostTopTabCenteringPolicy
import kotlin.math.abs
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HostComposePositionCallbackTest {
    private data class Coordinates(val packed: Long = 0, val failure: Throwable? = null, val root: Long = packed)
    private class State(var value: Int)
    private class Bridge {
        companion object {
            @JvmStatic fun positioned(modifier: Any, callback: (Any) -> Unit): Any = callback
            @JvmStatic fun inWindow(coordinates: Any): Long = (coordinates as Coordinates).let { it.failure?.let { error -> throw error }; it.packed }
            @JvmStatic fun inRoot(coordinates: Any): Long = (coordinates as Coordinates).root
            @JvmStatic fun state(value: Int): Any = State(value)
            @JvmStatic fun read(state: Any): Int = (state as State).value
            @JvmStatic fun write(state: Any, value: Int) { (state as State).value = value }
        }
    }
    private fun access(root: Boolean = true): HostComposeCardPositionAccess {
        fun method(name: String, vararg types: Class<*>) = Bridge::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true }
        val ctor = HostComposeCardPositionAccess::class.java.declaredConstructors.single { it.parameterCount == 8 }.apply { isAccessible = true }
        return ctor.newInstance(method("positioned", Any::class.java, Function1::class.java), method("inWindow", Any::class.java),
            Function1::class.java, Unit, method("state", Int::class.javaPrimitiveType!!), method("read", Any::class.java),
            method("write", Any::class.java, Int::class.javaPrimitiveType!!),
            if (root) method("inRoot", Any::class.java) else null) as HostComposeCardPositionAccess
    }
    @Suppress("UNCHECKED_CAST")
    private fun observer(callback: (Float, Float) -> Unit, onError: ((Throwable) -> Unit)?, root: Boolean = false): (Any) -> Unit =
        access().attach(Any(), callback, onError, root) as (Any) -> Unit
    private fun pack(x: Float, y: Float) = (x.toBits().toLong() shl 32) or (y.toBits().toLong() and 0xFFFFFFFFL)

    fun `position decoder failure reaches error handler without escaping host layout`() {
        val expected = IllegalStateException("detached coordinates")
        var failure: Throwable? = null
        observer({ _, _ -> fail("invalid coordinates reached layout") }, { failure = it })(Coordinates(failure = expected))
        assertTrue(generateSequence(failure) { it.cause }.any { it === expected })
    }
    fun `position consumer and diagnostic failures cannot escape protected callback`() {
        var reports = 0
        observer({ _, _ -> throw IllegalStateException("surface") }, { reports++; throw IllegalStateException("diagnostic") })(Coordinates())
        assertEquals(1, reports)
    }
    fun `valid coordinates retain native float values`() {
        var position: Pair<Float, Float>? = null
        val packed = (12.5f.toBits().toLong() shl 32) or ((-3.25f).toBits().toLong() and 0xFFFFFFFFL)
        observer({ x, y -> position = x to y }, { throw AssertionError(it) })(Coordinates(packed))
        assertEquals(12.5f to -3.25f, position)
    }
    fun `existing callers without error handler retain exception behavior`() {
        val expected = IllegalStateException("existing caller")
        try {
            observer({ _, _ -> throw expected }, null)(Coordinates())
            fail("the opt-in handler changed the existing caller contract")
        } catch (failure: Throwable) {
            assertTrue(generateSequence(failure) { it.cause }.any { it === expected })
        }
    }
    fun `root mode reads the owner-local offset instead of the cached window position`() {
        var position: Pair<Float, Float>? = null
        observer({ x, y -> position = x to y }, { throw AssertionError(it) }, root = true)(Coordinates(pack(0f, 900f), root = pack(4f, 0f)))
        assertEquals(4f to 0f, position)
    }
    fun `root mode is optional and never silently falls back to window coordinates`() {
        assertTrue(access().rootPositions)
        val missing = access(root = false)
        assertFalse(missing.rootPositions)
        try {
            missing.attach(Any(), { _, _ -> }, null, root = true)
            fail("window coordinates were used as root-local offsets")
        } catch (expected: IllegalArgumentException) {}
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (HostComposePositionCallbackTest) -> Unit) {
            val s = HostComposePositionCallbackTest()
            block(s)
        }
        runIsolated { s -> s.`position decoder failure reaches error handler without escaping host layout`() }
        runIsolated { s -> s.`position consumer and diagnostic failures cannot escape protected callback`() }
        runIsolated { s -> s.`valid coordinates retain native float values`() }
        runIsolated { s -> s.`existing callers without error handler retain exception behavior`() }
        runIsolated { s -> s.`root mode reads the owner-local offset instead of the cached window position`() }
        runIsolated { s -> s.`root mode is optional and never silently falls back to window coordinates`() }
    
        HostComposeSemanticsAccessCases().verifyAllCases()
        HostKhomeFloatCases().verifyAllCases()
        HostTopTabLayoutBridgeCases().verifyAllCases()
    }
}

private class HostComposeSemanticsAccessCases {
    // The real receiver is stable while the key type, holder, field and generic setter may be R8-renamed.
    class X(private val q: String, @Suppress("unused") private val d: String? = null,
            @Suppress("unused") private val merge: (Any?, Any?) -> Any? = { a, _ -> a }) {
        override fun toString() = "AccessibilityKey: $q"
    }
    interface Receiver { fun q(key: X, value: Any) }
    class Configuration : Receiver {
        val values = linkedMapOf<X, Any>()
        override fun q(key: X, value: Any) { values[key] = value }
    }
    class P private constructor() {
        companion object {
            @JvmField val z = X("ContentDescription")
            @JvmField val a = X("StateDescription")
            @JvmField val c = X("TestTag", "ContentDescription")
            @JvmField val click = X("OnClick")
            @JvmField val selected = X("Selected")
        }
    }
    class DifferentCanonical private constructor() {
        companion object {
            @JvmField val a = X("ContentDescription")
            @JvmField val b = X("StateDescription")
            @JvmField val c = X("TestTag", "ContentDescription")
        }
    }
    class Lookalike private constructor() {
        companion object { @JvmField val sameName = X("ContentDescription") }
    }
    object Direct {
        @JvmStatic fun setContentDescription(target: Receiver, title: String) { target.q(P.z, listOf(title)) }
    }
    interface Ambiguous { fun set(key: X, value: Any); fun other(key: OtherKey, value: Any) }
    class OtherKey(private val name: String)
    class AmbiguousNameKey(private val a: String, private val b: String)
    interface AmbiguousNameReceiver { fun set(key: AmbiguousNameKey, value: Any) }

    fun renamedHolderUsesCanonicalKeyAndLeavesNativeClickAndSelectedUntouched() {
        val point = HostComposeSemanticsAccess.locateForReceiver(Receiver::class.java, listOf(P::class.java))
        assertEquals(HomeChromeSemanticsPoint(P::class.java.name, "z"), point)
        val access = HostComposeSemanticsAccess.resolveForTypes(Receiver::class.java, null, point, listOf(P::class.java))
        assertNotNull(access)
        val node = Configuration()
        val click = { "native click" }
        node.values[P.click] = click
        node.values[P.selected] = true
        access!!.describe(node, "动态")
        assertEquals(listOf("动态"), node.values[P.z])
        assertSame(P.z, node.values.keys.single { it !== P.click && it !== P.selected })
        assertSame(click, node.values[P.click])
        assertEquals(true, node.values[P.selected])
        assertEquals(3, node.values.size)
    }

    fun directHostSetterWorksWithoutCanonicalHolderMetadata() {
        val setter = Direct::class.java.getDeclaredMethod("setContentDescription", Receiver::class.java, String::class.java)
        val access = HostComposeSemanticsAccess.resolveForTypes(Receiver::class.java, setter, null, emptyList())
        val node = Configuration()
        access!!.describe(node, "首页")
        assertEquals(mapOf(P.z to listOf("首页")), node.values)
    }

    fun ambiguousAndOneOffSameNamedKeysCannotAuthorizeLabelRemoval() {
        assertNull(HostComposeSemanticsAccess.locateForReceiver(Receiver::class.java,
            listOf(P::class.java, DifferentCanonical::class.java)))
        assertNull(HostComposeSemanticsAccess.resolveForTypes(Receiver::class.java, null, null,
            listOf(P::class.java, DifferentCanonical::class.java)))
        assertNull(HostComposeSemanticsAccess.locateForReceiver(Receiver::class.java, listOf(Lookalike::class.java)))
        assertNull(HostComposeSemanticsAccess.locateForReceiver(Ambiguous::class.java, listOf(P::class.java)))
        assertNull(HostComposeSemanticsAccess.locateForReceiver(AmbiguousNameReceiver::class.java, listOf(P::class.java)))
    }

    fun cachedFieldMustStillBeTheActualDescriptionKey() {
        listOf("missing", "a", "c").forEach { name ->
            assertNull(HostComposeSemanticsAccess.resolveForTypes(Receiver::class.java, null,
                HomeChromeSemanticsPoint(P::class.java.name, name), listOf(P::class.java)))
        }
        val node = Configuration()
        assertTrue(node.values.isEmpty())
    }

    fun cachedMetadataRoundTripsWithoutKeyObjectsOrTitles() {
        val point = HomeChromeSemanticsPoint(P::class.java.name, "z")
        assertEquals(point, HomeChromeSemanticsPoint.fromJson(point.toJson()))
        assertEquals(setOf("holder", "key"), point.toJson().keys().asSequence().toSet())
        for (rootHolder in listOf("ue52", "ngf2", "vlf2", "a")) {
            val rootPoint = HomeChromeSemanticsPoint(rootHolder, "z")
            assertTrue(rootHolder, rootPoint.isStructurallyValid())
            assertEquals(rootHolder, rootPoint, HomeChromeSemanticsPoint.fromJson(rootPoint.toJson()))
        }
        listOf(
            JSONObject(),
            JSONObject().put("holder", "java.lang.String").put("key", "value"),
            JSONObject().put("holder", "kotlin.Unit").put("key", "z"),
            JSONObject().put("holder", "android.view.View").put("key", "z"),
            JSONObject().put("holder", P::class.java.name).put("key", "bad/key"),
            JSONObject().put("holder", "").put("key", "z"),
            JSONObject().put("holder", ".").put("key", "z"),
            JSONObject().put("holder", ".ue52").put("key", "z"),
            JSONObject().put("holder", "ue52.").put("key", "z"),
            JSONObject().put("holder", "ue..52").put("key", "z"),
            JSONObject().put("holder", "1bad").put("key", "z"),
            JSONObject().put("holder", "[Lb1.P;").put("key", "z")
        ).forEach {
            assertNull(it.toString(), HomeChromeSemanticsPoint.fromJson(it))
        }
    }

    fun failedWriterAttachmentKeepsNativeLabelAndCanRetryWithoutStaleTitle() {
        val state = HostBottomLabelState()
        assertFalse(state.captureForRemoval("首页"))
        assertTrue(runCatching { state.attachSemantics { error("native semantics rejected") } }.isFailure)
        assertFalse(state.captureForRemoval("动态"))
        assertEquals("", state.value)
        var attached = false
        state.attachSemantics { attached = true }
        assertTrue(attached)
        assertTrue(state.captureForRemoval("我的"))
        assertEquals("我的", state.value)
    }

    fun unrelatedTargetIsRejectedBeforeAnyPropertyWrite() {
        val access = HostComposeSemanticsAccess.resolveForTypes(Receiver::class.java, null, null, listOf(P::class.java))!!
        val node = Configuration()
        assertTrue(runCatching { access.describe(Any(), "wrong target") }.isFailure)
        assertTrue(node.values.isEmpty())
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostComposeSemanticsAccessCases) -> Unit) {
            val s = HostComposeSemanticsAccessCases()
            block(s)
        }
        runIsolated { s -> s.renamedHolderUsesCanonicalKeyAndLeavesNativeClickAndSelectedUntouched() }
        runIsolated { s -> s.directHostSetterWorksWithoutCanonicalHolderMetadata() }
        runIsolated { s -> s.ambiguousAndOneOffSameNamedKeysCannotAuthorizeLabelRemoval() }
        runIsolated { s -> s.cachedFieldMustStillBeTheActualDescriptionKey() }
        runIsolated { s -> s.cachedMetadataRoundTripsWithoutKeyObjectsOrTitles() }
        runIsolated { s -> s.failedWriterAttachmentKeepsNativeLabelAndCanRetryWithoutStaleTitle() }
        runIsolated { s -> s.unrelatedTargetIsRejectedBeforeAnyPropertyWrite() }
    }
}

private class HostKhomeFloatCases {
    private class Box(var value: Int)

    private val ledger = HostChromeValueLedger<Box, Int>({ it.value }, { box, value -> box.value = value })

    fun `offsets apply to the host base and follow a changed shift without accumulating`() {
        val list = Box(12)
        assertTrue(ledger.apply(list) { it + 300 })
        assertEquals(312, list.value)
        assertFalse("Re-applying the same shift writes nothing", ledger.apply(list) { it + 300 })
        assertTrue(ledger.apply(list) { it + 250 })
        assertEquals("A new shift is computed from the host base, not stacked", 262, list.value)
        assertTrue(ledger.restore(list))
        assertEquals(12, list.value)
        assertFalse(ledger.owns(list))
    }

    fun `a host rewrite becomes the new base and is never overwritten on restore`() {
        val list = Box(0)
        ledger.apply(list) { it + 300 }
        list.value = 8
        ledger.apply(list) { it + 300 }
        assertEquals(308, list.value)
        list.value = 40
        assertFalse("Restore leaves a value the host wrote after us", ledger.restore(list))
        assertEquals(40, list.value)
    }

    fun `restoreAll keeps the current owner and gives back stale keys`() {
        val old = Box(0); val current = Box(0)
        ledger.apply(old) { it - 300 }
        ledger.apply(current) { it - 300 }
        assertTrue(ledger.restoreAll(except = current))
        assertEquals(0, old.value)
        assertEquals(-300, current.value)
        assertEquals(listOf(current), ledger.keys())
        assertFalse("only the excepted key is left", ledger.restoreAll(except = current))
        assertEquals(-300, current.value)
        assertTrue(ledger.restoreAll())
        assertEquals(0, current.value)
        assertFalse("an empty ledger has nothing to restore", ledger.restoreAll())
    }

    fun `restore can write an equivalent value instead of the raw base`() {
        val refresh = Box(-40)
        val mapped = HostChromeValueLedger<Box, Int>({ it.value }, { box, value -> box.value = value }, { it - 1 })
        mapped.apply(refresh) { it + 10 }
        mapped.restore(refresh)
        assertEquals(-41, refresh.value)
    }

    fun `content margin cancels the expanded AppBar bottom and keeps the measured bottom`() {
        val statusBar = 96; val appBar = 336; val base = 0
        val margin = HostKhomeFloatPolicy.contentMargin(base, statusBar, appBar)
        assertEquals(-432, margin)
        // HeaderScrollingViewBehavior: top = header.bottom + margin, height grows by -margin.
        val nativeTop = statusBar + appBar; val nativeHeight = 2000
        val top = nativeTop + margin; val height = nativeHeight - margin
        assertEquals(0, top)
        assertEquals(nativeTop + nativeHeight, top + height)
    }

    fun `refresh shift keeps the native drag distance and resting gap`() {
        val density = 3; val shift = 432
        val start = -40 * density; val end = 64 * density
        val (newStart, newEnd) = HostKhomeFloatPolicy.refreshOffsets(start, end, shift)
        // Native, non-custom: drag distance = end, rest = end - |start|. Custom: end - start, end.
        assertEquals(end, newEnd - newStart)
        assertEquals(end - abs(start) + shift, newEnd)
        val (restoredStart, restoredEnd) = HostKhomeFloatPolicy.restoredRefreshOffsets(start, end)
        assertEquals(start, restoredStart)
        assertEquals("Restored custom pair rests where the native pair did", end - abs(start), restoredEnd)
        assertEquals("…and drags the same distance", end, restoredEnd - restoredStart)
    }

    fun `a custom refresh start shifts as a pair`() {
        assertEquals(500 to 620, HostKhomeFloatPolicy.refreshOffsets(68, 188, 432))
        assertEquals(68 to 188, HostKhomeFloatPolicy.restoredRefreshOffsets(68, 188))
    }

    fun `only a list spanning its page is the feed`() {
        assertTrue(HostKhomeFloatPolicy.primaryList(1080, 1900, 1080, 2000))
        assertFalse("Carousel", HostKhomeFloatPolicy.primaryList(1080, 400, 1080, 2000))
        assertFalse("Side column", HostKhomeFloatPolicy.primaryList(500, 2000, 1080, 2000))
        assertFalse("Unlaid page", HostKhomeFloatPolicy.primaryList(1080, 2000, 0, 0))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostKhomeFloatCases) -> Unit) {
            val s = HostKhomeFloatCases()
            block(s)
        }
        runIsolated { s -> s.`offsets apply to the host base and follow a changed shift without accumulating`() }
        runIsolated { s -> s.`a host rewrite becomes the new base and is never overwritten on restore`() }
        runIsolated { s -> s.`restoreAll keeps the current owner and gives back stale keys`() }
        runIsolated { s -> s.`restore can write an equivalent value instead of the raw base`() }
        runIsolated { s -> s.`content margin cancels the expanded AppBar bottom and keeps the measured bottom`() }
        runIsolated { s -> s.`refresh shift keeps the native drag distance and resting gap`() }
        runIsolated { s -> s.`a custom refresh start shifts as a pair`() }
        runIsolated { s -> s.`only a list spanning its page is the feed`() }
    }
}

private class HostTopTabLayoutBridgeCases {
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

    fun `native constraints content and whole measure result are delegated unchanged`() {
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

    fun `three tabs and unknown slots restore original alignment on the next measure`() {
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

    fun `native measure exception escapes once with its original identity`() {
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

    fun `module geometry failure reports and preserves native placement`() {
        val expected = IllegalArgumentException("geometry")
        var reported: Throwable? = null
        val alignment = bridge(onError = { reported = it }, center = { _, _, _ -> throw expected })
            .alignment(object : Alignment {
                override fun align(child: Long, available: Long, direction: Any) = packed(8, 9)
            }) as Alignment
        assertEquals(packed(8, 9), alignment.align(packed(100, 20), packed(300, 30), Any()))
        assertSame(expected, reported)
    }

    fun `local viewport fill follows count transitions without repeated invalidation`() {
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

    fun verifyAllCases() {
        fun runIsolated(block: (HostTopTabLayoutBridgeCases) -> Unit) {
            val s = HostTopTabLayoutBridgeCases()
            block(s)
        }
        runIsolated { s -> s.`native constraints content and whole measure result are delegated unchanged`() }
        runIsolated { s -> s.`three tabs and unknown slots restore original alignment on the next measure`() }
        runIsolated { s -> s.`native measure exception escapes once with its original identity`() }
        runIsolated { s -> s.`module geometry failure reports and preserves native placement`() }
        runIsolated { s -> s.`local viewport fill follows count transitions without repeated invalidation`() }
    }
}
