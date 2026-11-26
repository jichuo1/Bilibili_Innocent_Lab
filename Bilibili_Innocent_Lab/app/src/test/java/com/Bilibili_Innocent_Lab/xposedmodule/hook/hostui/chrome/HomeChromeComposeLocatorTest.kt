package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.HomeChromeAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeColors
import org.junit.Assert.*
import org.junit.Test

class HomeChromeComposeLocatorTest {
    fun `independent tab layout accepts native old and optimized restart ABIs only`() {
        val owner = "tv.danmaku.bili.khome.widget.topbar.CustomScrollableTabRowKt"
        val prefix = listOf("int", "androidx.compose.ui.Modifier", "long", "long", "float", "float",
            "kotlin.jvm.functions.Function1", "kotlin.jvm.functions.Function3", "kotlin.jvm.functions.Function2")
        val modern = prefix + listOf("androidx.compose.runtime.internal.ComposableLambda", "androidx.compose.runtime.Composer", "int")
        val old = prefix + listOf("kotlin.jvm.functions.Function2", "androidx.compose.runtime.Composer", "int", "int")
        assertTrue(HomeChromeComposeLocator.accepts("top_layout", owner, old))
        assertTrue(HomeChromeComposeLocator.accepts("top_layout", owner.substringBeforeLast('.') + ".f", modern))
        assertFalse(HomeChromeComposeLocator.accepts("top_layout", "foreign.CustomScrollableTabRowKt", modern))
        assertFalse(HomeChromeComposeLocator.accepts("top_layout", owner, modern.dropLast(1)))
    }
    private val modifier = "androidx.compose.ui.Modifier"
    private val composer = "androidx.compose.runtime.Composer"
    private val f1 = "kotlin.jvm.functions.Function1"
    private val topOwner = "tv.danmaku.bili.khome.widget.topbar.HomeTopTabKt"
    private val prefix = listOf("Qo1.e", modifier, "float", "float", "float", "long", "long")
    private val bottom = listOf(modifier, "java.util.List", f1, f1, "kotlin.jvm.functions.Function4", "No1.b", "boolean", f1, "No1.g", composer, "int")
    fun `foreground barriers accept audited 9161 row ABI and reject decorative or foreign callers`() {
        val owner = "tv.danmaku.bili.khome.widget.topbar.y"
        val p = listOf("fp1.e", "float", modifier, "boolean", "float", f1, composer, "int", "int")
        assertTrue(HomeChromeComposeLocator.accepts("top_foreground", owner, p))
        assertTrue(HomeChromeComposeLocator.accepts("top_foreground", owner, p.dropLast(1)))
        assertFalse(HomeChromeComposeLocator.accepts("top_foreground", owner, listOf("fp1.e", f1, composer, "int")))
        assertFalse(HomeChromeComposeLocator.accepts("top_foreground", "foreign.NormalScreenRow", p))
        assertFalse(HomeChromeComposeLocator.accepts("top_foreground", owner + "\$Content", p))
        assertFalse(HomeChromeComposeLocator.accepts("top_foreground", owner, listOf("java.lang.Object") + p.drop(1)))
        assertFalse(HomeChromeComposeLocator.accepts("top_foreground", owner, p.toMutableList().apply { this[3] = "int" }))
    }
    fun `second row backdrop requires its exact model and restart ABI`() {
        val owner = "tv.danmaku.bili.khome.widget.topbar.y"
        val p = listOf("Qo1.e", f1, composer, "int")
        assertTrue(HomeChromeComposeLocator.accepts("top_backdrop", owner, p))
        assertTrue(HomeChromeComposeLocator.accepts("top_backdrop", owner, p + "int"))
        assertFalse(HomeChromeComposeLocator.accepts("top_backdrop", owner + "\$Restart", p))
        assertFalse(HomeChromeComposeLocator.accepts("top_backdrop", "foreign.SecondRow", p))
        assertFalse(HomeChromeComposeLocator.accepts("top_backdrop", owner, listOf("java.lang.Object") + p.drop(1)))
        assertFalse(HomeChromeComposeLocator.accepts("top_backdrop", owner, listOf("Qo1.e", modifier, f1, composer, "int")))
        assertFalse(HomeChromeComposeLocator.accepts("top_backdrop", owner, p.dropLast(1)))
        assertFalse(HomeChromeComposeLocator.accepts("top_backdrop", owner, p.dropLast(1) + "boolean"))
    }
    fun `top accepts both verified R8 generations without method letter`() {
        assertTrue(HomeChromeComposeLocator.accepts("top", topOwner, prefix + listOf("boolean", f1, f1, composer, "int")))
        assertTrue(HomeChromeComposeLocator.accepts("top", topOwner, prefix + listOf(f1, f1, composer, "int", "int")))
    }
    fun `background right actions and lookalike broad objects are rejected`() {
        assertFalse(HomeChromeComposeLocator.accepts("top", "tv.danmaku.bili.khome.widget.topbar.y", prefix + listOf("boolean", f1, f1, composer, "int")))
        assertFalse(HomeChromeComposeLocator.accepts("top", topOwner, listOf("Qo1.e", modifier, composer, "int")))
        assertFalse(HomeChromeComposeLocator.accepts("top", topOwner, listOf("java.lang.Object") + prefix.drop(1) + listOf("boolean", f1, f1, composer, "int")))
    }
    fun `bottom item accepts both mask generations and excludes publish or generic shapes`() {
        val item = listOf("int", "Qo1.d", "tv.danmaku.bili.khome.widget.bottomtab.a", modifier, "boolean", "kotlin.jvm.functions.Function3", composer, "int")
        assertTrue(HomeChromeComposeLocator.accepts("bottom_item", "tv.danmaku.bili.khome.widget.bottomtab.o", item))
        assertTrue(HomeChromeComposeLocator.accepts("bottom_item", "tv.danmaku.bili.khome.widget.bottomtab.HomeBottomTabItemViewKt", item + "int"))
        assertFalse(HomeChromeComposeLocator.accepts("bottom_item", "tv.danmaku.bili.khome.widget.bottomtab.q", item.filterNot { it == "boolean" || it == "kotlin.jvm.functions.Function3" }))
        assertFalse(HomeChromeComposeLocator.accepts("bottom_item", "tv.danmaku.bili.khome.widget.bottomtab.o", item.toMutableList().apply { this[2] = "java.lang.Object" }))
    }
    fun `bottom accepts mixed owner names and flags but excludes captured lambdas`() {
        assertTrue(HomeChromeComposeLocator.accepts("bottom", "tv.danmaku.bili.khome.widget.bottomtab.i", bottom))
        assertTrue(HomeChromeComposeLocator.accepts("bottom", "tv.danmaku.bili.khome.widget.bottomtab.HomeBottomTabContainerKt", bottom + "int"))
        assertFalse(HomeChromeComposeLocator.accepts("bottom", "tv.danmaku.bili.khome.widget.bottomtab.i\$1", bottom))
        assertFalse(HomeChromeComposeLocator.accepts("bottom", "tv.danmaku.bili.khome.widget.bottomtab.i", bottom.dropLast(1) + "boolean"))
    }

    fun `top content accepts old static helper but rejects the list leaf sharing its trace prefix`() {
        val p = listOf("androidx.compose.foundation.ScrollState", "float", "kotlin.jvm.functions.Function2", "float",
            "androidx.compose.runtime.State", "kotlin.jvm.functions.Function2", "kotlin.jvm.functions.Function3", "int",
            "androidx.compose.runtime.MutableIntState", composer, "int")
        assertTrue(HomeChromeComposeLocator.acceptsTopContent(topOwner, true, "kotlin.Unit", p, emptyList()))
        assertFalse(HomeChromeComposeLocator.acceptsTopContent(topOwner, true, "kotlin.Unit",
            listOf("int", "java.util.List", composer, "int"), emptyList()))
        assertFalse(HomeChromeComposeLocator.acceptsTopContent(topOwner, true, "kotlin.Unit", p.dropLast(1), emptyList()))
    }

    fun `top content accepts bridge and typed lambda only with native tab captures`() {
        val fields = listOf("androidx.compose.foundation.ScrollState", "androidx.compose.runtime.internal.ComposableLambda", "kotlin.jvm.functions.Function3")
        assertTrue(HomeChromeComposeLocator.acceptsTopContent(topOwner, false, "java.lang.Object", List(2) { "java.lang.Object" }, fields))
        assertTrue(HomeChromeComposeLocator.acceptsTopContent(topOwner, false, "void", listOf(composer, "int"), fields))
        assertFalse(HomeChromeComposeLocator.acceptsTopContent(topOwner, false, "void", listOf(composer, "int"), fields.drop(1)))
        assertFalse(HomeChromeComposeLocator.acceptsTopContent("foreign.page.Row", false, "java.lang.Object", List(2) { "java.lang.Object" }, fields))
    }

    fun `repackaged and future flattened root chrome accept valid Compose ABIs across R8 renames`() {
        val top = listOf("rco0") + prefix.drop(1) + listOf("boolean", f1, f1, composer, "int")
        val backdrop = listOf("xmo1", f1, composer, "int")
        val bottomRoot = bottom.toMutableList().apply { this[5] = "dlu1"; this[8] = "klu1" }
        val item = listOf("int", "i1o0", "h8k", modifier, "boolean", "kotlin.jvm.functions.Function3", composer, "int")
        assertTrue(HomeChromeComposeLocator.accepts("top", "zn40", top))
        assertTrue(HomeChromeComposeLocator.accepts("top_backdrop", "slq0", backdrop))
        assertTrue(HomeChromeComposeLocator.accepts("bottom", "clq0", bottomRoot))
        assertTrue(HomeChromeComposeLocator.accepts("bottom_item", "tv.danmaku.bili.khome.widget.bottomtab.a", item))
        assertTrue(HomeChromeComposeLocator.accepts("bottom_item", "clq0", item))
        assertFalse(HomeChromeComposeLocator.accepts("top_backdrop", "foreign.pkg.slq0", backdrop))
        assertFalse(HomeChromeComposeLocator.accepts("top_backdrop", "ulo0", listOf("java.lang.Object") + backdrop.drop(1)))
        assertFalse(HomeChromeComposeLocator.accepts("top_backdrop", "slq0", listOf("int") + backdrop.drop(1)))
        assertFalse(HomeChromeComposeLocator.accepts("bottom", "w0o0", bottom.toMutableList().apply { this[5] = "float" }))
        assertFalse(HomeChromeComposeLocator.accepts("bottom_item", "clq0\$1", item))
        assertFalse(HomeChromeComposeLocator.accepts("bottom_item", "clq0", item.toMutableList().apply { this[2] = "androidx.compose.ui.Modifier" }))
        assertTrue(HomeChromeAdapter.validCache(mapOf("top_backdrop" to HookPoint("slq0", "e", backdrop))))
        assertFalse(HomeChromeAdapter.validCache(mapOf("top_layout" to HookPoint("foreign.pkg.ulo0", "e", backdrop))))
        assertFalse(HomeChromeComposeLocator.accepts("top_layout", "ulo0", backdrop))
    }

    fun `flattened tabs lambda allows widened functions across builds while requiring modern captures`() {
        val args = List(2) { "java.lang.Object" }
        val fields = listOf("androidx.compose.foundation.ScrollState", "kotlin.jvm.functions.Function2",
            "kotlin.jvm.functions.Function3", "kotlin.jvm.functions.Function2", "androidx.compose.runtime.State",
            "androidx.compose.runtime.MutableIntState")
        assertTrue(HomeChromeComposeLocator.acceptsTopContent("h330", false, "java.lang.Object", args, fields))
        assertTrue(HomeChromeComposeLocator.acceptsTopContent("wn40", false, "java.lang.Object", args, fields))
        assertTrue(HomeChromeComposeLocator.acceptsTopContent("wm40", false, "java.lang.Object", args, fields))
        assertFalse(HomeChromeComposeLocator.acceptsTopContent("foreign.pkg.wm40", false, "java.lang.Object", args, fields))
        assertFalse(HomeChromeComposeLocator.acceptsTopContent("wn40", false, "java.lang.Object", args, fields.dropLast(1)))
    }

    fun `old static content allows null divider while requiring native tabs and captures`() {
        val types = Array<Class<*>>(11) { Any::class.java }.apply {
            this[2] = Function2::class.java; this[5] = Function2::class.java; this[6] = Function3::class.java
        }
        val tabs: (Any?, Any?) -> Any? = { _, _ -> Unit }
        val indicator: (Any?, Any?, Any?) -> Any? = { _, _, _ -> Unit }
        val args = Array<Any?>(11) { Any() }.apply { this[2] = null; this[5] = tabs; this[6] = indicator }
        assertTrue(HomeChromeComposeLocator.topContentStaticValues(types) { args[it] })
        args[2] = tabs
        assertTrue(HomeChromeComposeLocator.topContentStaticValues(types) { args[it] })
        args[2] = Any()
        assertFalse(HomeChromeComposeLocator.topContentStaticValues(types) { args[it] })
        args[2] = null; args[5] = null
        assertFalse(HomeChromeComposeLocator.topContentStaticValues(types) { args[it] })
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (HomeChromeComposeLocatorTest) -> Unit) {
            val s = HomeChromeComposeLocatorTest()
            block(s)
        }
        runIsolated { s -> s.`independent tab layout accepts native old and optimized restart ABIs only`() }
        runIsolated { s -> s.`foreground barriers accept audited 9161 row ABI and reject decorative or foreign callers`() }
        runIsolated { s -> s.`second row backdrop requires its exact model and restart ABI`() }
        runIsolated { s -> s.`top accepts both verified R8 generations without method letter`() }
        runIsolated { s -> s.`background right actions and lookalike broad objects are rejected`() }
        runIsolated { s -> s.`bottom item accepts both mask generations and excludes publish or generic shapes`() }
        runIsolated { s -> s.`bottom accepts mixed owner names and flags but excludes captured lambdas`() }
        runIsolated { s -> s.`top content accepts old static helper but rejects the list leaf sharing its trace prefix`() }
        runIsolated { s -> s.`top content accepts bridge and typed lambda only with native tab captures`() }
        runIsolated { s -> s.`repackaged and future flattened root chrome accept valid Compose ABIs across R8 renames`() }
        runIsolated { s -> s.`flattened tabs lambda allows widened functions across builds while requiring modern captures`() }
        runIsolated { s -> s.`old static content allows null divider while requiring native tabs and captures`() }
    
        HostChromeAttachmentCases().verifyAllCases()
        HostChromeBackdropPaintCases().verifyAllCases()
        HostChromeBackdropRecoveryCases().verifyAllCases()
        HostChromeCompositionScopeCases().verifyAllCases()
        HostChromeHeaderBackdropCases().verifyAllCases()
    }
}

private class HostChromeAttachmentCases {
    fun `late host layout retries before reporting actual application once`() {
        val queue = ArrayDeque<Runnable>()
        var attempts = 0
        var applied = 0
        var missing = 0
        val attachment = HostChromeAttachment({ _, task -> queue.addLast(task); true },
            { ++attempts == 3 }, { applied++ }, { missing++ }, { throw AssertionError(it) })
        attachment.start()
        assertEquals(0, applied)
        repeat(2) { queue.removeFirst().run(); assertEquals(0, applied) }
        val final = queue.removeFirst()
        final.run(); final.run()
        assertEquals(3, attempts)
        assertEquals(1, applied)
        assertEquals(0, missing)
        assertTrue(queue.isEmpty())
    }

    fun `unsupported layout stops after a bounded number of attempts`() {
        val queue = ArrayDeque<Runnable>()
        var attempts = 0
        var missing = 0
        HostChromeAttachment({ _, task -> queue.addLast(task); true }, { attempts++; false },
            { fail("unsupported layout was marked applied") }, { missing++ }, { throw AssertionError(it) },
            maxAttempts = 3).start()
        while (queue.isNotEmpty()) queue.removeFirst().run()
        assertEquals(3, attempts)
        assertEquals(1, missing)
    }

    fun `asynchronous attachment exceptions report failure without retrying or escaping`() {
        val queue = ArrayDeque<Runnable>()
        val failure = IllegalStateException("changed host structure")
        var observed: Throwable? = null
        HostChromeAttachment({ _, task -> queue.addLast(task); true }, { throw failure },
            { fail("failed layout was marked applied") }, { fail("exception is not missing layout") },
            { observed = it }).start()
        queue.removeFirst().run()
        assertSame(failure, observed)
        assertTrue(queue.isEmpty())
    }

    fun `throwing scheduler reports failure and cannot leave an active attempt`() {
        val failure = IllegalStateException("detached queue")
        var reported: Throwable? = null
        HostChromeAttachment({ _, _ -> throw failure }, { fail("task was not posted"); false },
            { fail("failed scheduling was marked applied") }, { fail("failed scheduling is not missing layout") },
            { reported = it }).start()
        assertSame(failure, reported)
    }

    fun `rejected scheduling terminates without claiming application`() {
        var missing = 0
        HostChromeAttachment({ _, _ -> false }, { fail("rejected task ran"); false },
            { fail("rejected task was marked applied") }, { missing++ }, { throw AssertionError(it) }).start()
        assertEquals(1, missing)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostChromeAttachmentCases) -> Unit) {
            val s = HostChromeAttachmentCases()
            block(s)
        }
        runIsolated { s -> s.`late host layout retries before reporting actual application once`() }
        runIsolated { s -> s.`unsupported layout stops after a bounded number of attempts`() }
        runIsolated { s -> s.`asynchronous attachment exceptions report failure without retrying or escaping`() }
        runIsolated { s -> s.`throwing scheduler reports failure and cannot leave an active attempt`() }
        runIsolated { s -> s.`rejected scheduling terminates without claiming application`() }
    }
}

private class HostChromeBackdropPaintCases {
    private interface Modifier {
        fun then(other: Modifier): Modifier
    }
    private class Scope(val content: () -> Unit)
    private class Element(val name: String, val paint: (Scope, MutableList<String>) -> Unit)
    private class Chain(val elements: List<Element>) : Modifier {
        override fun then(other: Modifier) = Chain(elements + (other as Chain).elements)
        fun draw(events: MutableList<String>) {
            fun visit(index: Int) {
                if (index == elements.size) { events += "native-image"; return }
                elements[index].paint(Scope { visit(index + 1) }, events)
            }
            visit(0)
        }
    }
    private class Native {
        companion object {
            @JvmStatic fun drawWithContent(original: Modifier, draw: (Any) -> Unit): Modifier =
                original.then(Chain(listOf(Element("draw-boundary") { scope, _ -> draw(scope) })))
        }
    }
    private val empty = Chain(emptyList())
    private val withContent = Native::class.java.getDeclaredMethod("drawWithContent", Modifier::class.java,
        Function1::class.java).apply { isAccessible = true }
    private val then = Modifier::class.java.getDeclaredMethod("then", Modifier::class.java).apply { isAccessible = true }
    private fun suppress(original: Modifier, active: () -> Boolean = { true }) = HostChromeBackdropPaint.suppress(original, empty,
        withContent, then, Function1::class.java, Unit, active) { (it as Scope).content() } as Chain
    private fun native(): Chain = Chain(listOf(
        Element("native-solid") { scope, events -> events += "black-fill"; scope.content() },
        Element("native-gradient") { scope, events -> events += "native-gradient"; scope.content() },
        Element("native-layout") { scope, _ -> scope.content() }
    ))

    fun `outer boundary removes solid gradient and native image paint together`() {
        val original = native()
        val before = mutableListOf<String>()
        original.draw(before)
        assertEquals(listOf("black-fill", "native-gradient", "native-image"), before)
        val after = mutableListOf<String>()
        suppress(original).draw(after)
        assertTrue(after.isEmpty())
        val restored = mutableListOf<String>()
        original.draw(restored)
        assertEquals(before, restored)
    }

    fun `appended content suppression leaves the native black fill visible`() {
        val original = native()
        val wrong = Native.drawWithContent(original) {} as Chain
        val events = mutableListOf<String>()
        wrong.draw(events)
        assertEquals(listOf("black-fill", "native-gradient"), events)
        events.clear()
        suppress(original).draw(events)
        assertTrue(events.isEmpty())
    }

    fun `native layout and input elements retain identity and foreground stays outside boundary`() {
        val original = native()
        val cleared = suppress(original)
        assertEquals(original.elements.size + 1, cleared.elements.size)
        original.elements.forEachIndexed { index, element -> assertSame(element, cleared.elements[index + 1]) }
        val events = mutableListOf<String>()
        cleared.draw(events)
        // The native foreground sibling is drawn by its own scope, after this decorative child.
        native().draw(events)
        assertEquals(listOf("black-fill", "native-gradient", "native-image"), events)
        assertNotSame(cleared, suppress(original))
    }

    fun `material failure restores all native paint even after image derives another modifier`() {
        var active = false
        val original = native()
        val supplied = suppress(original) { active }
        val imageDerived = supplied.then(Chain(listOf(Element("image-layout") { scope, _ -> scope.content() }))) as Chain
        val events = mutableListOf<String>()
        imageDerived.draw(events)
        assertEquals(listOf("black-fill", "native-gradient", "native-image"), events)
        events.clear()
        active = true
        imageDerived.draw(events)
        assertTrue(events.isEmpty())
        active = false
        imageDerived.draw(events)
        assertEquals(listOf("black-fill", "native-gradient", "native-image"), events)
        events.clear()
        imageDerived.draw(events)
        assertEquals(listOf("black-fill", "native-gradient", "native-image"), events)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostChromeBackdropPaintCases) -> Unit) {
            val s = HostChromeBackdropPaintCases()
            block(s)
        }
        runIsolated { s -> s.`outer boundary removes solid gradient and native image paint together`() }
        runIsolated { s -> s.`appended content suppression leaves the native black fill visible`() }
        runIsolated { s -> s.`native layout and input elements retain identity and foreground stays outside boundary`() }
        runIsolated { s -> s.`material failure restores all native paint even after image derives another modifier`() }
    }
}

private class HostChromeBackdropRecoveryCases {
    private class Page(var visible: Boolean = true, var attached: Boolean = true)
    private class Scene {
        val recovery = HostChromeBackdropRecovery<Page>()
        var page: Page? = null
        var probes = 0
        var now = 0L
        fun draw() = recovery.select(now, { it.visible && it.attached }) { probes++; page }
        fun nextProbe() {
            now += requireNotNull(recovery.retryAfter(now))
        }
    }

    fun `home dynamic mine home restores the live source after the fallback window expires`() {
        val scene = Scene()
        val home = Page()
        scene.page = home
        assertSame(home, scene.draw())
        // Both intermediate pages are Compose-only and initially have no independent source.
        home.visible = false; scene.page = null; scene.now = 100
        assertNull(scene.draw())
        repeat(5) { scene.nextProbe(); assertNull(scene.draw()) }
        assertNull(scene.recovery.retryAfter(scene.now))
        val settled = scene.probes
        scene.now += 5000
        assertNull(scene.draw()); assertEquals(settled, scene.probes)
        // Returning to home must re-arm discovery, even though the fallback itself was valid.
        home.visible = true; scene.page = home
        scene.recovery.request(scene.now)
        assertSame(home, scene.draw())
        assertNull(scene.recovery.retryAfter(scene.now))
    }

    fun `late page creation is discovered without requiring another navigation event`() {
        val scene = Scene()
        assertNull(scene.draw())
        scene.nextProbe(); assertNull(scene.draw())
        val page = Page(); scene.page = page
        scene.nextProbe()
        assertSame(page, scene.draw())
        assertEquals(3, scene.probes)
    }

    fun `scrolling and repeated chrome recomposition reuse a healthy source without hierarchy scans`() {
        val scene = Scene().apply { page = Page() }
        val source = scene.draw()
        repeat(240) {
            scene.now += 16
            assertFalse(scene.recovery.request(scene.now))
            assertSame(source, scene.draw())
            assertNull(scene.recovery.retryAfter(scene.now))
        }
        assertEquals(1, scene.probes)
    }

    fun `switching away from a detached or invisible source selects the new page`() {
        val scene = Scene().apply { page = Page() }
        val first = scene.draw()!!
        first.attached = false
        val second = Page(); scene.page = second
        assertSame(second, scene.draw())
        second.visible = false
        val third = Page(); scene.page = third
        assertSame(third, scene.draw())
        assertEquals(3, scene.probes)
    }

    fun `invalid candidates never become a sticky source and requests are rate bounded`() {
        val scene = Scene().apply { page = Page(visible = false) }
        assertNull(scene.draw())
        repeat(63) {
            scene.now++
            scene.recovery.request(scene.now)
            assertNull(scene.draw())
        }
        assertEquals(1, scene.probes)
        scene.now = 64; scene.page!!.visible = true
        assertSame(scene.page, scene.draw())
        assertEquals(2, scene.probes)
    }

    fun `detach clears the old source and permits a fresh attach immediately`() {
        val scene = Scene().apply { page = Page() }
        val old = scene.draw()
        scene.recovery.clear()
        val next = Page(); scene.page = next
        assertSame(next, scene.draw()); assertNotSame(old, next)
        assertEquals(2, scene.probes)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostChromeBackdropRecoveryCases) -> Unit) {
            val s = HostChromeBackdropRecoveryCases()
            block(s)
        }
        runIsolated { s -> s.`home dynamic mine home restores the live source after the fallback window expires`() }
        runIsolated { s -> s.`late page creation is discovered without requiring another navigation event`() }
        runIsolated { s -> s.`scrolling and repeated chrome recomposition reuse a healthy source without hierarchy scans`() }
        runIsolated { s -> s.`switching away from a detached or invisible source selects the new page`() }
        runIsolated { s -> s.`invalid candidates never become a sticky source and requests are rate bounded`() }
        runIsolated { s -> s.`detach clears the old source and permits a fresh attach immediately`() }
    }
}

private class HostChromeCompositionScopeCases {
    fun `top layout restart shares one root claim and still works without its outer caller`() {
        val scope = HostChromeCompositionScope<String>()
        val outer = scope.enter("top")
        val layout = scope.enter("top")
        assertEquals("top", scope.claimTogether { it == "top" })
        scope.exit(layout)
        assertNull(scope.current()); assertNull(scope.claim())
        scope.exit(outer)
        val restart = scope.enter("top")
        assertEquals("top", scope.claimTogether { it == "top" })
        scope.exit(restart)
        assertNull(scope.current())
    }
    fun `only first root is claimed and outside composition stays untouched`() {
        val scope = HostChromeCompositionScope<String>()
        assertNull(scope.current()); assertNull(scope.claim())
        val outer = scope.enter("top")
        assertEquals("top", scope.current()); assertEquals("top", scope.claim())
        assertNull(scope.current()); assertNull(scope.claim())
        scope.exit(outer); assertNull(scope.claim())
    }
    fun `nested containers have independent roots and restore outer scope`() {
        val scope = HostChromeCompositionScope<String>()
        val outer = scope.enter("top")
        val inner = scope.enter("bottom")
        assertEquals("bottom", scope.claim()); scope.exit(inner)
        assertEquals("top", scope.claim()); scope.exit(outer)
        assertNull(scope.current())
    }
    fun `native failures still release exact invocation`() {
        val scope = HostChromeCompositionScope<String>()
        val outer = scope.enter("top")
        runCatching {
            val inner = scope.enter("bottom")
            try { throw IllegalStateException("native") } finally { scope.exit(inner) }
        }
        assertEquals("top", scope.current())
        scope.exit(outer); assertNull(scope.claim())
    }
    fun `stale exit cannot clear another invocation`() {
        val scope = HostChromeCompositionScope<String>()
        val stale = scope.enter("top"); scope.exit(stale)
        val current = scope.enter("top")
        scope.exit(stale); assertEquals("top", scope.claim())
        scope.exit(current)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostChromeCompositionScopeCases) -> Unit) {
            val s = HostChromeCompositionScopeCases()
            block(s)
        }
        runIsolated { s -> s.`top layout restart shares one root claim and still works without its outer caller`() }
        runIsolated { s -> s.`only first root is claimed and outside composition stays untouched`() }
        runIsolated { s -> s.`nested containers have independent roots and restore outer scope`() }
        runIsolated { s -> s.`native failures still release exact invocation`() }
        runIsolated { s -> s.`stale exit cannot clear another invocation`() }
    }
}

private class HostChromeHeaderBackdropCases {
    private class Header(var alpha: Float = 1f)
    private class Root(var background: Any? = null)
    private class Fill(var color: Int)

    private val backdrop = HostChromeHeaderBackdrop(object : HostChromeHeaderBackdrop.Ops<Header, Root> {
        override fun alpha(header: Header) = header.alpha
        override fun setAlpha(header: Header, alpha: Float) { header.alpha = alpha }
        override fun background(root: Root) = root.background
        override fun setBackground(root: Root, value: Any?) { root.background = value }
        override fun newFill(color: Int): Any = Fill(color)
        override fun recolor(fill: Any, color: Int) { (fill as Fill).color = color }
    })

    fun `hides the native header and fills only an empty root, then restores both`() {
        val header = Header(.8f); val root = Root()
        backdrop.apply(header, root, 0xFFF1F2F3.toInt())
        assertEquals(0f, header.alpha)
        assertEquals(0xFFF1F2F3.toInt(), (root.background as Fill).color)
        backdrop.apply(header, root, 0xFF0A0B0C.toInt())
        assertEquals("Theme change recolors the same fill", 0xFF0A0B0C.toInt(), (root.background as Fill).color)
        backdrop.restore()
        assertEquals(.8f, header.alpha)
        assertNull(root.background)
        assertFalse(backdrop.hidden)
    }

    fun `host-owned background and later host values win`() {
        val header = Header(); val hostBackground = Any(); val root = Root(hostBackground)
        backdrop.apply(header, root, 0xFFF1F2F3.toInt())
        assertSame("An existing host background already paints the region", hostBackground, root.background)
        header.alpha = .5f
        backdrop.restore()
        assertEquals("A host alpha change after hiding is not overwritten", .5f, header.alpha)
        assertSame(hostBackground, root.background)
    }

    fun `wallpaper keeps the root transparent and removes an earlier fill`() {
        val header = Header(); val root = Root()
        backdrop.apply(header, root, 0xFFF1F2F3.toInt())
        backdrop.apply(header, root, null)
        assertNull(root.background)
        assertEquals(0f, header.alpha)
    }

    fun `a replaced header or root view is restored before the new one is taken`() {
        val first = Header(); val second = Header(); val root = Root(); val other = Root()
        backdrop.apply(first, root, 1)
        backdrop.apply(second, other, 2)
        assertEquals(1f, first.alpha)
        assertNull(root.background)
        assertEquals(0f, second.alpha)
        assertEquals(2, (other.background as Fill).color)
    }

    fun `page base must agree with the resolved light or dark host theme`() {
        assertEquals(0xFFF1F2F3.toInt(), HostChromeColors.pageBase(0xFFF1F2F3.toInt(), dark = false))
        assertEquals(0xFF0A0B0C.toInt(), HostChromeColors.pageBase(0x000A0B0C, dark = true))
        assertNull(HostChromeColors.pageBase(0xFFF1F2F3.toInt(), dark = true))
        assertNull(HostChromeColors.pageBase(0xFF0A0B0C.toInt(), dark = false))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostChromeHeaderBackdropCases) -> Unit) {
            val s = HostChromeHeaderBackdropCases()
            block(s)
        }
        runIsolated { s -> s.`hides the native header and fills only an empty root, then restores both`() }
        runIsolated { s -> s.`host-owned background and later host values win`() }
        runIsolated { s -> s.`wallpaper keeps the root transparent and removes an earlier fill`() }
        runIsolated { s -> s.`a replaced header or root view is restored before the new one is taken`() }
        runIsolated { s -> s.`page base must agree with the resolved light or dark host theme`() }
    }
}
