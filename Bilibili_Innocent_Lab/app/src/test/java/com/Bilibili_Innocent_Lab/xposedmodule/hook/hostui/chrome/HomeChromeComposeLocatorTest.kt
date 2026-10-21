package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import org.junit.Assert.*
import org.junit.Test

class HomeChromeComposeLocatorTest {
    private val modifier = "androidx.compose.ui.Modifier"
    private val composer = "androidx.compose.runtime.Composer"
    private val f1 = "kotlin.jvm.functions.Function1"
    private val topOwner = "tv.danmaku.bili.khome.widget.topbar.HomeTopTabKt"
    private val prefix = listOf("Qo1.e", modifier, "float", "float", "float", "long", "long")
    private val bottom = listOf(modifier, "java.util.List", f1, f1, "kotlin.jvm.functions.Function4", "No1.b", "boolean", f1, "No1.g", composer, "int")
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
