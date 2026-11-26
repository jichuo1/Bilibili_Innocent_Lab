package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import android.content.Context
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import com.sun.management.ThreadMXBean
import java.io.File
import java.lang.management.ManagementFactory
import java.util.Random
import java.util.WeakHashMap
import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostChromeModifierOwnershipTest {
    private class Node(var modifier: Any)

    fun `material failure can restore surface background and divider independently`() {
        val ownership = HostChromeModifierOwnership()
        val nodes = List(3) { Node(Any()) }
        val originals = nodes.map { it.modifier }
        nodes.forEach { node ->
            val original = node.modifier
            node.modifier = Any()
            ownership.record(node, node.modifier, original)
        }
        val leases = ownership.drain()
        assertEquals(3, leases.size)
        leases.forEach { (native, expected, original) ->
            val node = native as Node
            if (node.modifier === expected) node.modifier = original
        }
        nodes.forEachIndexed { index, node -> assertSame(originals[index], node.modifier) }
        assertTrue(ownership.drain().isEmpty())
    }

    fun `recomposition renews only its node and host replacement keeps ownership`() {
        val ownership = HostChromeModifierOwnership()
        val original = Any()
        val node = Node(Any())
        ownership.record(node, node.modifier, original)
        val nextOriginal = Any()
        node.modifier = Any()
        ownership.record(node, node.modifier, nextOriginal)
        val retained = Node(Any())
        ownership.record(retained, retained.modifier, Any())
        val hostReplacement = Any()
        retained.modifier = hostReplacement
        val leases = ownership.drain()
        assertEquals(2, leases.size)
        leases.forEach { (native, expected, previous) ->
            val target = native as Node
            if (target.modifier === expected) target.modifier = previous
        }
        assertSame(nextOriginal, node.modifier)
        assertSame(hostReplacement, retained.modifier)
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (HostChromeModifierOwnershipTest) -> Unit) {
            val s = HostChromeModifierOwnershipTest()
            block(s)
        }
        runIsolated { s -> s.`material failure can restore surface background and divider independently`() }
        runIsolated { s -> s.`recomposition renews only its node and host replacement keeps ownership`() }
    
        HostChromeRecyclerTypesCases().verifyAllCases()
        HostChromeRootFillCases().verifyAllCases()
        HostChromeRowSelectionCases().verifyAllCases()
        HostChromeTopCapsuleCases().verifyAllCases()
        HostChromeValueLedgerCases().verifyAllCases()
    }
}

private class HostChromeRecyclerTypesCases {
    private class Feed(context: Context) : RecyclerView(context)
    private val classes = arrayOf(FrameLayout::class.java, Feed::class.java, RecyclerView::class.java, ArrayList::class.java)
    private fun previous(type: Class<*>): Boolean = generateSequence(type as Class<*>?) { it.superclass }
        .take(12).any { it.name == "androidx.recyclerview.widget.RecyclerView" }

    fun `host list subclasses are recognized while ordinary containers stay excluded`() {
        val types = HostChromeRecyclerTypes()
        assertFalse(types.matches(FrameLayout::class.java))
        assertTrue(types.matches(Feed::class.java))
        assertTrue(types.matches(RecyclerView::class.java))
        assertFalse(types.matches(ArrayList::class.java))
        repeat(1000) { assertEquals(previous(classes[it % classes.size]), types.matches(classes[it % classes.size])) }
    }

    fun `repeated source discovery preserves classification and can record local allocation cost`() {
        val types = HostChromeRecyclerTypes()
        repeat(10000) { types.matches(classes[it % classes.size]); previous(classes[it % classes.size]) }
        val output = System.getenv("INNOCENT_LAB_REVIEW_PERF_REPORT") ?: return
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        if (bean.isThreadAllocatedMemorySupported) bean.isThreadAllocatedMemoryEnabled = true
        val iterations = 200000
        fun measure(action: (Class<*>) -> Boolean): Triple<Long, Long, Int> {
            val thread = Thread.currentThread().id
            val memory = if (bean.isThreadAllocatedMemorySupported) bean.getThreadAllocatedBytes(thread) else 0L
            val start = System.nanoTime()
            var matched = 0
            repeat(iterations) { if (action(classes[it % classes.size])) matched++ }
            return Triple(System.nanoTime() - start,
                if (bean.isThreadAllocatedMemorySupported) bean.getThreadAllocatedBytes(thread) - memory else 0L, matched)
        }
        val baseline = (1..3).map { measure(::previous) }
        val cached = (1..3).map { measure(types::matches) }
        assertTrue((baseline + cached).all { it.third == iterations / 2 })
        fun rows(values: List<Triple<Long, Long, Int>>) = values.joinToString(prefix = "[", postfix = "]") {
            "{\"nanos\":${it.first},\"allocated_bytes\":${it.second},\"matches\":${it.third}}"
        }
        File(output).writeText("{\"scope\":\"local_jvm_classification_only\",\"iterations\":$iterations," +
            "\"allocation_supported\":${bean.isThreadAllocatedMemorySupported},\"previous\":${rows(baseline)},\"cached\":${rows(cached)}}")
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostChromeRecyclerTypesCases) -> Unit) {
            val s = HostChromeRecyclerTypesCases()
            block(s)
        }
        runIsolated { s -> s.`host list subclasses are recognized while ordinary containers stay excluded`() }
        runIsolated { s -> s.`repeated source discovery preserves classification and can record local allocation cost`() }
    }
}

private class HostChromeRootFillCases {
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

    fun `renamed four field element is certified by native factory and cached`() {
        Native.calls = 0
        val fill = recognizer("current")
        assertTrue(fill.matches(Current(0, null, 1f, Shape(), 0)))
        assertTrue(fill.matches(Current(123, Brush(), .5f, Shape(), 0)))
        assertEquals(1, Native.calls)
    }
    fun `older inspector field is allowed without relying on private class name`() {
        Native.calls = 0
        assertTrue(recognizer("early").matches(Early(0, null, 1f, Shape()) {}))
        assertEquals(1, Native.calls)
    }
    fun `matching fields and constructor alone cannot remove another modifier role`() {
        Native.calls = 0
        val fill = recognizer("current")
        assertFalse(fill.matches(Lookalike(0, null, 1f, Shape())))
        assertFalse(fill.matches(Lookalike(1, null, 1f, Shape())))
        assertEquals(1, Native.calls)
    }
    fun `node caches class ids and unrelated constructors are rejected before probing`() {
        Native.calls = 0
        val fill = recognizer("current")
        assertFalse(fill.matches(Node(0, null, 1f, Shape(), 0)))
        assertFalse(fill.matches(Merged(0, null, 1f, Shape(), 2)))
        assertFalse(fill.matches(NoConstructor(Any())))
        assertFalse(fill.matches(Any()))
        assertEquals(0, Native.calls)
    }

    fun `solid and gradient decorations become fresh transparent native fills without mutating originals`() {
        Native.calls = 0
        val fill = recognizer("current")
        val shape = Shape()
        val solid = Current(123L, null, 1f, shape, 0)
        val gradient = Current(456L, Brush(), .6f, shape, 0)
        val cleared = listOf(solid, gradient).map { fill.transparent(it) as Current }
        cleared.forEach {
            assertEquals(0L, it.color)
            assertNull(it.brush)
            assertSame(shape, it.shape)
            assertEquals(1f, it.alpha, 0f)
        }
        assertNotSame(cleared[0], cleared[1])
        assertNotSame(solid, cleared[0])
        assertEquals(123L, solid.color)
        assertEquals(456L, gradient.color)
        assertNotNull(gradient.brush)
        assertEquals(.6f, gradient.alpha, 0f)
        assertEquals("one class certification and one factory call per replacement", 3, Native.calls)
    }

    fun `lookalikes and unrelated layout or pointer modifiers cannot become transparent fills`() {
        val fill = recognizer("current")
        assertNull(fill.transparent(Lookalike(123L, null, 1f, Shape())))
        assertNull(fill.transparent(Merged(123L, null, 1f, Shape(), 1)))
        assertNull(fill.transparent(Any()))
    }

    private class SixFieldFill(val color: Long, val brush: Brush?, val alpha: Float, val shape: Shape, val extra1: Int, val extra2: String) : Element
    private class NativeSixField {
        companion object {
            var calls = 0
            @JvmStatic fun create(empty: Any, color: Long, shape: Shape): Any {
                calls++
                return SixFieldFill(color, null, 1f, shape, 0, "ok")
            }
        }
    }

    fun `probed root fill caches native owner regardless of field count and probes only once`() {
        NativeSixField.calls = 0
        val probeShape = Shape()
        val factory = NativeSixField::class.java.getDeclaredMethod(
            "create", Any::class.java, Long::class.javaPrimitiveType, Shape::class.java
        ).apply { isAccessible = true }
        val fill = HostChromeRootFill(Element::class.java, Brush::class.java, Shape::class.java,
            Function1::class.java, Any(), factory, probeShape)
        assertEquals("lazy probe waits for first Element check", 0, NativeSixField.calls)
        val first = SixFieldFill(1L, null, 1f, Shape(), 1, "a")
        val second = SixFieldFill(2L, Brush(), .5f, Shape(), 2, "b")
        assertTrue(fill.matches(first))
        assertTrue(fill.matches(second))
        assertFalse(fill.matches(Lookalike(0L, null, 1f, Shape())))
        assertEquals(1, NativeSixField.calls)
        val cleared = fill.transparent(first) as SixFieldFill
        assertEquals(0L, cleared.color)
        assertSame(first.shape, cleared.shape)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostChromeRootFillCases) -> Unit) {
            val s = HostChromeRootFillCases()
            block(s)
        }
        runIsolated { s -> s.`renamed four field element is certified by native factory and cached`() }
        runIsolated { s -> s.`older inspector field is allowed without relying on private class name`() }
        runIsolated { s -> s.`matching fields and constructor alone cannot remove another modifier role`() }
        runIsolated { s -> s.`node caches class ids and unrelated constructors are rejected before probing`() }
        runIsolated { s -> s.`solid and gradient decorations become fresh transparent native fills without mutating originals`() }
        runIsolated { s -> s.`lookalikes and unrelated layout or pointer modifiers cannot become transparent fills`() }
        runIsolated { s -> s.`probed root fill caches native owner regardless of field count and probes only once`() }
    }
}

private class HostChromeRowSelectionCases {
    private val contentOwner = "fixture.bottom.BottomContent"
    private val contentMethod = "renderTabs"
    private val policy = "rowMeasurePolicy"

    private fun frame(owner: String, method: String) = StackTraceElement(owner, method, "Fixture.kt", 1)
    private fun content() = frame(contentOwner, contentMethod)
    private fun accepts(vararg frames: StackTraceElement) =
        HostChromeRowSelection.directCaller(arrayOf(*frames), policy, contentOwner, contentMethod)

    fun `hook bridge and generated callback owners before policy do not change direct caller`() {
        assertTrue(accepts(
            frame("fixture.bridge.HookBridge", "invoke"),
            frame("fixture.generated.Q7", "callback"),
            frame("fixture.generated.R2", policy),
            content()
        ))
    }

    fun `only exact immediate content caller can select foreground row`() {
        val anchor = frame("fixture.generated.Trampoline", policy)
        assertTrue(accepts(anchor, content()))
        assertFalse(accepts(anchor, frame("fixture.image.Image", "render"), content()))
        assertFalse(accepts(anchor, frame(contentOwner, "renderBackground"), content()))
        assertFalse(accepts(anchor, frame("fixture.other.BottomContent", contentMethod), content()))
    }

    fun `consecutive same method trampolines preserve the real direct caller`() {
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

    fun `absent or too deep policy anchors cannot claim a row`() {
        assertFalse(accepts())
        assertFalse(accepts(frame("fixture.bridge.HookBridge", "invoke"), content()))
        val prefix = List(46) { frame("fixture.bridge.Frame$it", "invoke") }
        val anchor = frame("fixture.generated.Trampoline", policy)
        assertTrue(accepts(*(prefix + anchor + content()).toTypedArray()))
        assertFalse(accepts(*(prefix + frame("fixture.bridge.Extra", "invoke") + anchor + content()).toTypedArray()))
        assertFalse(accepts(*(prefix + List(2) { frame("fixture.bridge.Extra$it", "invoke") } + anchor + content()).toTypedArray()))
    }

    fun `first policy anchor cannot skip a different caller to use a later matching anchor`() {
        assertFalse(accepts(
            frame("fixture.generated.First", policy),
            frame("fixture.image.Image", "render"),
            frame("fixture.generated.Second", policy),
            content()
        ))
    }

    fun `selection retries after unrelated row and stops reading stacks once selected`() {
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

    fun `top background box and separator must be direct leaves and never a nested indicator or image`() {
        val owner = "tv.danmaku.bili.khome.widget.topbar.y"
        val boundary = frame(owner, "e")
        for (leaf in listOf("Box", "Spacer")) {
            val anchor = frame("fixture.generated.Decoration", leaf)
            assertTrue(HostChromeRowSelection.directCaller(arrayOf(anchor, boundary), leaf, owner, "e"))
            assertFalse(HostChromeRowSelection.directCaller(arrayOf(anchor,
                frame("tv.danmaku.bili.khome.widget.topbar.Indicator", "draw"), boundary), leaf, owner, "e"))
            assertFalse(HostChromeRowSelection.directCaller(arrayOf(anchor,
                frame("kntr.base.imageloader.Image", "render"), boundary), leaf, owner, "e"))
            assertFalse(HostChromeRowSelection.directCaller(arrayOf(anchor, frame(owner, "f"), boundary), leaf, owner, "e"))
            assertFalse(HostChromeRowSelection.directCaller(arrayOf(boundary), leaf, owner, "e"))
        }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostChromeRowSelectionCases) -> Unit) {
            val s = HostChromeRowSelectionCases()
            block(s)
        }
        runIsolated { s -> s.`hook bridge and generated callback owners before policy do not change direct caller`() }
        runIsolated { s -> s.`only exact immediate content caller can select foreground row`() }
        runIsolated { s -> s.`consecutive same method trampolines preserve the real direct caller`() }
        runIsolated { s -> s.`absent or too deep policy anchors cannot claim a row`() }
        runIsolated { s -> s.`first policy anchor cannot skip a different caller to use a later matching anchor`() }
        runIsolated { s -> s.`selection retries after unrelated row and stops reading stacks once selected`() }
        runIsolated { s -> s.`top background box and separator must be direct leaves and never a nested indicator or image`() }
    }
}

private class HostChromeTopCapsuleCases {
    fun `shadow and capsule stay inside the host row across densities`() {
        for (density in listOf(1f, 2f, 2.625f, 2.75f, 3f, 3.5f, 4f)) for (heightDp in listOf(32f, 36f, 40f, 48f)) {
            val width = 411f * density; val height = heightDp * density
            val capsule = requireNotNull(HostChromeTopCapsule.fit(width, height, density))
            val extent = HostChromeTopCapsule.shadowExtent(capsule.shadowRadius)
            assertTrue("vertical $density/$heightDp: $capsule", extent <= capsule.insetY)
            assertTrue("horizontal $density/$heightDp: $capsule", extent <= capsule.insetX)
            // The indicator sits a few dp above the row bottom; the inset never reaches it.
            assertTrue(capsule.insetY <= 2f * density + 1e-3f)
            assertEquals((height - 2f * capsule.insetY) / 2f, capsule.cornerRadius(height), 1e-3f)
        }
    }

    fun `typical phone row keeps a visible contact shadow`() {
        val capsule = requireNotNull(HostChromeTopCapsule.fit(1080f, 40f * 2.75f, 2.75f))
        assertTrue(capsule.shadowRadius > 0f)
    }

    fun `degenerate rows keep the full row and no shadow`() {
        assertNull(HostChromeTopCapsule.fit(0f, 100f, 3f))
        assertNull(HostChromeTopCapsule.fit(100f, Float.NaN, 3f))
        val tiny = requireNotNull(HostChromeTopCapsule.fit(200f, 10f, 3f))
        assertEquals(0f, tiny.shadowRadius, 0f)
        assertEquals(0f, HostChromeTopCapsule.shadowExtent(0f), 0f)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostChromeTopCapsuleCases) -> Unit) {
            val s = HostChromeTopCapsuleCases()
            block(s)
        }
        runIsolated { s -> s.`shadow and capsule stay inside the host row across densities`() }
        runIsolated { s -> s.`typical phone row keeps a visible contact shadow`() }
        runIsolated { s -> s.`degenerate rows keep the full row and no shadow`() }
    }
}

private class HostChromeValueLedgerCases {
    private class Box(var value: Int)

    /** 优化前的实现，逐字保留，用来对照语义并比较分配。 */
    private class LegacyLedger<K : Any, V>(private val read: (K) -> V, private val write: (K, V) -> Unit) {
        private class Entry<V>(val base: V, val applied: V)
        private val entries = WeakHashMap<K, Entry<V>>()
        fun owns(key: K) = entries.containsKey(key)
        fun apply(key: K, target: (base: V) -> V): Boolean {
            val current = read(key)
            val previous = entries[key]
            val base = if (previous != null && current == previous.applied) previous.base else current
            val next = target(base)
            entries[key] = Entry(base, next)
            if (current == next) return false
            write(key, next)
            return true
        }
        fun restore(key: K): Boolean {
            val entry = entries.remove(key) ?: return false
            if (entry.applied == entry.base || read(key) != entry.applied) return false
            write(key, entry.base)
            return true
        }
    }

    private fun ledger() = HostChromeValueLedger<Box, Int>({ it.value }, { box, value -> box.value = value })
    private fun legacy() = LegacyLedger<Box, Int>({ it.value }, { box, value -> box.value = value })

    fun firstApplyEstablishesOwnershipEvenWhenNoWriteIsNeeded() {
        val box = Box(300)
        val ledger = ledger()
        assertFalse("the host value already equals the target: nothing to write", ledger.apply(box) { 300 })
        assertTrue("but the key is owned from the first observation", ledger.owns(box))
        assertFalse(ledger.apply(box) { 300 })
        assertFalse("applied == base: restore writes nothing", ledger.restore(box))
        assertFalse(ledger.owns(box))
    }

    fun aHostRewriteRecordsTheNewBaseWithoutReusingTheOldEntry() {
        val box = Box(0)
        val ledger = ledger()
        ledger.apply(box) { it + 300 }
        ledger.apply(box) { it + 300 } // 稳定重复
        box.value = 8
        assertTrue(ledger.apply(box) { it + 300 })
        assertEquals(308, box.value)
        box.value = 40
        assertFalse("restore leaves a value the host wrote after us", ledger.restore(box))
        assertEquals(40, box.value)
    }

    fun changedTargetReplacesTheEntryAndStillRestoresTheOriginalBase() {
        val box = Box(12)
        val ledger = ledger()
        ledger.apply(box) { it + 300 }
        ledger.apply(box) { it + 300 }
        assertTrue(ledger.apply(box) { it + 250 })
        assertEquals(262, box.value)
        assertTrue(ledger.restore(box))
        assertEquals(12, box.value)
    }

    fun randomOperationsMatchTheLegacyLedgerExactly() {
        val random = Random(20261029L)
        val boxes = List(3) { val initial = random.nextInt(50); Box(initial) to Box(initial) }
        val ledger = ledger(); val legacy = legacy()
        repeat(30_000) {
            val (a, b) = boxes[random.nextInt(boxes.size)]
            when (random.nextInt(6)) {
                0 -> { val host = random.nextInt(60); a.value = host; b.value = host } // 宿主改写
                1, 2, 3 -> {
                    val shift = random.nextInt(4) * 100
                    val x = ledger.apply(a) { it + shift }; val y = legacy.apply(b) { it + shift }
                    assertEquals("apply result", y, x)
                }
                4 -> assertEquals("restore result", legacy.restore(b), ledger.restore(a))
                else -> assertEquals("owns", legacy.owns(b), ledger.owns(a))
            }
            assertEquals("value drift", b.value, a.value)
        }
    }

    fun steadyStateApplyAllocatesLessThanTheLegacyLedger() {
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        fun allocated() = threads.getThreadAllocatedBytes(Thread.currentThread().id)
        val box = Box(7); val twin = Box(7)
        val ledger = ledger(); val legacy = legacy()
        val target: (Int) -> Int = { it + 100 } // 小整数落在装箱缓存内，隔离出 Entry 本身的分配
        repeat(20_000) { ledger.apply(box, target); legacy.apply(twin, target) } // 预热 JIT 与 WeakHashMap
        val rounds = 100_000
        var start = allocated()
        repeat(rounds) { ledger.apply(box, target) }
        val optimized = allocated() - start
        start = allocated()
        repeat(rounds) { legacy.apply(twin, target) }
        val before = allocated() - start
        println("HostChromeValueLedger steady-state apply: optimized=${optimized / rounds.toDouble()} B/call legacy=${before / rounds.toDouble()} B/call")
        assertTrue("legacy allocates an Entry per call: ${before / rounds.toDouble()} B", before / rounds.toDouble() >= 16.0)
        assertTrue("optimized steady state allocates nothing per call: ${optimized / rounds.toDouble()} B", optimized / rounds.toDouble() < 2.0)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostChromeValueLedgerCases) -> Unit) {
            val s = HostChromeValueLedgerCases()
            block(s)
        }
        runIsolated { s -> s.firstApplyEstablishesOwnershipEvenWhenNoWriteIsNeeded() }
        runIsolated { s -> s.aHostRewriteRecordsTheNewBaseWithoutReusingTheOldEntry() }
        runIsolated { s -> s.changedTargetReplacesTheEntryAndStillRestoresTheOriginalBase() }
        runIsolated { s -> s.randomOperationsMatchTheLegacyLedgerExactly() }
        runIsolated { s -> s.steadyStateApplyAllocatesLessThanTheLegacyLedger() }
    }
}
