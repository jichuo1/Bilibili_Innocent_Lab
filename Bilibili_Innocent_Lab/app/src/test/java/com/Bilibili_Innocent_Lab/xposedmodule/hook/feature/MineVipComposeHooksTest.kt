package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.*
import org.junit.Test
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.*
import androidx.compose.ui.unit.Constraints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import tv.danmaku.bili.ui.main2.mineV2.ui.VipFixtureManager
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostComposeHiddenLayout

class MineVipComposeHooksTest {
    @Test fun `native measure failures retain their original throwable and never claim applied`() {
        var applied = 0
        val access = requireNotNull(HostComposeHiddenLayout.resolve(javaClass.classLoader!!) { applied++ })
        val hidden = access.wrap(Modifier) as HiddenModifier
        val failure = IllegalStateException("native measure")
        val child = object : Measurable {
            override fun `measure-fixture`(constraints: Long): Placeable = throw failure
        }
        val scope = object : MeasureScope {
            override fun layout(width: Int, height: Int, lines: Map<Any, Any>, placement: (Any) -> Unit): MeasureResult =
                error("failed measure must not create a layout")
        }
        assertSame(failure, runCatching { hidden.measure(scope, child, Constraints(1L)) }.exceptionOrNull())
        assertEquals(0, applied)
    }
    private class Harness {
        val registrar = PlayerPortTestRegistrar()
        val evidence = arrayListOf<FeatureRuntimeStage>()
        val environment = HookEnvironment("tv.danmaku.bili", javaClass.classLoader,
            HookPointRegistry(javaClass.classLoader), registrar, { _, _ -> }, { _, message -> fail(message) }, { _, _ -> },
            runtimeEvidence = { _, stage, _ -> evidence += stage })
        fun args(modifier: Modifier = Modifier) = arrayOf<Any?>(0, null, modifier, VipFixtureManager())
    }

    @Test fun `hide removes the shared V2 V3 card before native content can execute`() {
        val h = Harness()
        assertTrue(MineVipComposeHooks.install(h.environment, false))
        assertTrue(h.evidence.isEmpty())
        var called = false
        h.registrar.invoke("mine.vip.compose", args = h.args()) { called = true; null }
        assertFalse(called)
        assertEquals(listOf(FeatureRuntimeStage.OBSERVED, FeatureRuntimeStage.APPLIED), h.evidence)
    }

    @Test fun `keep space measures real size but never places the card or duplicates its layout modifier`() {
        val h = Harness()
        assertTrue(MineVipComposeHooks.install(h.environment, true))
        var captured: Modifier? = null
        h.registrar.invoke("mine.vip.compose", args = h.args()) {
            assertEquals(4, it[0]); captured = it[2] as Modifier; null
        }
        assertEquals(listOf(FeatureRuntimeStage.OBSERVED), h.evidence)
        val hidden = captured as HiddenModifier
        var measured = 0
        val child = object : Measurable {
            override fun `measure-fixture`(constraints: Long): Placeable {
                assertEquals(123L, constraints); measured++; return Placeable(300, 108)
            }
        }
        val scope = object : MeasureScope {
            override fun layout(width: Int, height: Int, lines: Map<Any, Any>, placement: (Any) -> Unit) =
                MeasureResult(width, height, placement)
        }
        // 真机 layout 元素传的是装箱 Constraints（issue #44：旧 `as Long` 在此必炸）。
        val result = hidden.measure(scope, child, Constraints(123L))
        assertEquals(1, measured); assertEquals(300, result.width); assertEquals(108, result.height)
        assertEquals(listOf(FeatureRuntimeStage.OBSERVED, FeatureRuntimeStage.APPLIED), h.evidence)
        result.placement(Any()) // There is no child placement, hence no paint, hit target or semantics node.
        h.registrar.invoke("mine.vip.compose", args = h.args(hidden)) { assertSame(hidden, it[2]); null }
        assertEquals(2, h.evidence.size)
    }

    @Test fun `card facade recognizes old default masks and R8 argument reordering`() {
        val modifier = "androidx.compose.ui.Modifier"
        val composer = "androidx.compose.runtime.Composer"
        val manager = "tv.danmaku.bili.ui.main2.mineV2.ui.MineVipComposeManager"
        assertTrue(MineVipComposeHooks.accepts(listOf(modifier, manager, composer, "int", "int"), true, "void"))
        assertTrue(MineVipComposeHooks.accepts(listOf("int", composer, modifier,
            "tv.danmaku.bili.ui.main2.mineV2.ui.F"), true, "void"))
        assertTrue(MineVipComposeHooks.accepts(listOf(modifier,
            "tv.danmaku.bili.ui.main2.mineV2.ui.F", composer, "int"), true, "void"))
        assertFalse(MineVipComposeHooks.accepts(listOf(modifier, manager, "int", "int", composer, "int"), true, "kotlin.Unit"))
        assertFalse(MineVipComposeHooks.accepts(listOf(modifier,
            "tv.danmaku.bili.ui.main2.mine.modularvip.l", composer, "int"), true, "void"))
        assertFalse(MineVipComposeHooks.accepts(listOf(modifier, manager, composer, "int"), false, "void"))
    }
}
