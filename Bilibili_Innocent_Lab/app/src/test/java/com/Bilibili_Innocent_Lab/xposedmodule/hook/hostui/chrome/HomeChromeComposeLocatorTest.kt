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
}
