package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.HomeChromeAdapter
import org.junit.Assert.*
import org.junit.Test

class HomeChromeComposeLocatorTest {
    @Test fun `independent tab layout accepts native old and optimized restart ABIs only`() {
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
    @Test fun `foreground barriers accept audited 9161 row ABI and reject decorative or foreign callers`() {
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
    @Test fun `second row backdrop requires its exact model and restart ABI`() {
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
    @Test fun `top accepts both verified R8 generations without method letter`() {
        assertTrue(HomeChromeComposeLocator.accepts("top", topOwner, prefix + listOf("boolean", f1, f1, composer, "int")))
        assertTrue(HomeChromeComposeLocator.accepts("top", topOwner, prefix + listOf(f1, f1, composer, "int", "int")))
    }
    @Test fun `background right actions and lookalike broad objects are rejected`() {
        assertFalse(HomeChromeComposeLocator.accepts("top", "tv.danmaku.bili.khome.widget.topbar.y", prefix + listOf("boolean", f1, f1, composer, "int")))
        assertFalse(HomeChromeComposeLocator.accepts("top", topOwner, listOf("Qo1.e", modifier, composer, "int")))
        assertFalse(HomeChromeComposeLocator.accepts("top", topOwner, listOf("java.lang.Object") + prefix.drop(1) + listOf("boolean", f1, f1, composer, "int")))
    }
    @Test fun `bottom item accepts both mask generations and excludes publish or generic shapes`() {
        val item = listOf("int", "Qo1.d", "tv.danmaku.bili.khome.widget.bottomtab.a", modifier, "boolean", "kotlin.jvm.functions.Function3", composer, "int")
        assertTrue(HomeChromeComposeLocator.accepts("bottom_item", "tv.danmaku.bili.khome.widget.bottomtab.o", item))
        assertTrue(HomeChromeComposeLocator.accepts("bottom_item", "tv.danmaku.bili.khome.widget.bottomtab.HomeBottomTabItemViewKt", item + "int"))
        assertFalse(HomeChromeComposeLocator.accepts("bottom_item", "tv.danmaku.bili.khome.widget.bottomtab.q", item.filterNot { it == "boolean" || it == "kotlin.jvm.functions.Function3" }))
        assertFalse(HomeChromeComposeLocator.accepts("bottom_item", "tv.danmaku.bili.khome.widget.bottomtab.o", item.toMutableList().apply { this[2] = "java.lang.Object" }))
    }
    @Test fun `bottom accepts mixed owner names and flags but excludes captured lambdas`() {
        assertTrue(HomeChromeComposeLocator.accepts("bottom", "tv.danmaku.bili.khome.widget.bottomtab.i", bottom))
        assertTrue(HomeChromeComposeLocator.accepts("bottom", "tv.danmaku.bili.khome.widget.bottomtab.HomeBottomTabContainerKt", bottom + "int"))
        assertFalse(HomeChromeComposeLocator.accepts("bottom", "tv.danmaku.bili.khome.widget.bottomtab.i\$1", bottom))
        assertFalse(HomeChromeComposeLocator.accepts("bottom", "tv.danmaku.bili.khome.widget.bottomtab.i", bottom.dropLast(1) + "boolean"))
    }

    @Test fun `top content accepts old static helper but rejects the list leaf sharing its trace prefix`() {
        val p = listOf("androidx.compose.foundation.ScrollState", "float", "kotlin.jvm.functions.Function2", "float",
            "androidx.compose.runtime.State", "kotlin.jvm.functions.Function2", "kotlin.jvm.functions.Function3", "int",
            "androidx.compose.runtime.MutableIntState", composer, "int")
        assertTrue(HomeChromeComposeLocator.acceptsTopContent(topOwner, true, "kotlin.Unit", p, emptyList()))
        assertFalse(HomeChromeComposeLocator.acceptsTopContent(topOwner, true, "kotlin.Unit",
            listOf("int", "java.util.List", composer, "int"), emptyList()))
        assertFalse(HomeChromeComposeLocator.acceptsTopContent(topOwner, true, "kotlin.Unit", p.dropLast(1), emptyList()))
    }

    @Test fun `top content accepts bridge and typed lambda only with native tab captures`() {
        val fields = listOf("androidx.compose.foundation.ScrollState", "androidx.compose.runtime.internal.ComposableLambda", "kotlin.jvm.functions.Function3")
        assertTrue(HomeChromeComposeLocator.acceptsTopContent(topOwner, false, "java.lang.Object", List(2) { "java.lang.Object" }, fields))
        assertTrue(HomeChromeComposeLocator.acceptsTopContent(topOwner, false, "void", listOf(composer, "int"), fields))
        assertFalse(HomeChromeComposeLocator.acceptsTopContent(topOwner, false, "void", listOf(composer, "int"), fields.drop(1)))
        assertFalse(HomeChromeComposeLocator.acceptsTopContent("foreign.page.Row", false, "java.lang.Object", List(2) { "java.lang.Object" }, fields))
    }

    @Test fun `919 repackaged chrome admits only its original roles and exact flat model types`() {
        val top = listOf("rco0") + prefix.drop(1) + listOf("boolean", f1, f1, composer, "int")
        val backdrop = listOf("rco0", f1, composer, "int")
        val bottomRoot = bottom.toMutableList().apply { this[5] = "dlu1"; this[8] = "klu1" }
        val item = listOf("int", "i1o0", "h8k", modifier, "boolean", "kotlin.jvm.functions.Function3", composer, "int")
        assertTrue(HomeChromeComposeLocator.accepts("top", topOwner, top))
        assertTrue(HomeChromeComposeLocator.accepts("top_backdrop", "ulo0", backdrop))
        assertTrue(HomeChromeComposeLocator.accepts("bottom", "w0o0", bottomRoot))
        assertTrue(HomeChromeComposeLocator.accepts("bottom_item", "tv.danmaku.bili.khome.widget.bottomtab.a", item))
        assertFalse(HomeChromeComposeLocator.accepts("top_backdrop", "other0", backdrop))
        assertFalse(HomeChromeComposeLocator.accepts("top_backdrop", "ulo0", listOf("pkg.Other") + backdrop.drop(1)))
        assertFalse(HomeChromeComposeLocator.accepts("bottom", "w0o0", bottom))
        assertFalse(HomeChromeComposeLocator.accepts("bottom_item", "tv.danmaku.bili.khome.widget.bottomtab.b", item))
        assertTrue(HomeChromeAdapter.validCache(mapOf("top_backdrop" to HookPoint("ulo0", "e", backdrop))))
        assertFalse(HomeChromeAdapter.validCache(mapOf("top_layout" to HookPoint("ulo0", "e", backdrop))))
    }

    @Test fun `919 tabs lambda allows widened functions and excludes the trace sibling`() {
        val args = List(2) { "java.lang.Object" }
        val fields = listOf("androidx.compose.foundation.ScrollState", "kotlin.jvm.functions.Function2",
            "kotlin.jvm.functions.Function3", "kotlin.jvm.functions.Function2", "androidx.compose.runtime.State",
            "androidx.compose.runtime.MutableIntState")
        assertTrue(HomeChromeComposeLocator.acceptsTopContent("h330", false, "java.lang.Object", args, fields))
        assertFalse(HomeChromeComposeLocator.acceptsTopContent("j330", false, "java.lang.Object", args, fields))
        assertFalse(HomeChromeComposeLocator.acceptsTopContent("h330", false, "java.lang.Object", args, fields.dropLast(1)))
    }

    @Test fun `old static content allows null divider while requiring native tabs and captures`() {
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
}
