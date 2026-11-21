package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import org.junit.Assert.*
import org.junit.Test

class MineVipVisibilityTest {
    private class Card(initial: Int) : View(null) {
        var current = initial
        var writes = 0
        override fun getVisibility() = current
        override fun setVisibility(value: Int) { current = value; writes++ }
        override fun isAttachedToWindow() = false
        override fun addOnAttachStateChangeListener(listener: OnAttachStateChangeListener) = Unit
    }
    private class Harness {
        val evidence = arrayListOf<FeatureRuntimeStage>()
        val environment = HookEnvironment("tv.danmaku.bili", javaClass.classLoader,
            HookPointRegistry(javaClass.classLoader), TestHookRegistrar, { _, _ -> }, { _, message -> fail(message) }, { _, _ -> },
            runtimeEvidence = { _, stage, _ -> evidence += stage })
    }
    fun `host hidden cards never acquire blank space or false applied evidence`() {
        for (initial in listOf(View.GONE, View.INVISIBLE)) {
            val h = Harness(); val card = Card(initial)
            MineVipVisibility(h.environment, true).hide(card)
            assertEquals(initial, card.current)
            assertEquals(0, card.writes)
            assertEquals(listOf(FeatureRuntimeStage.OBSERVED), h.evidence)
        }
    }
    fun `asynchronous host updates stay hidden without reviving a naturally absent card`() {
        val h = Harness(); val card = Card(View.VISIBLE)
        val guard = MineVipVisibility(h.environment, true)
        guard.hide(card); assertEquals(View.INVISIBLE, card.current)
        card.current = View.GONE
        guard.hide(card); assertEquals(View.GONE, card.current)
        card.current = View.VISIBLE
        guard.hide(card); assertEquals(View.INVISIBLE, card.current)
        assertEquals(2, card.writes)
        assertEquals(1, h.evidence.count { it == FeatureRuntimeStage.APPLIED })
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (MineVipVisibilityTest) -> Unit) {
            val s = MineVipVisibilityTest()
            block(s)
        }
        runIsolated { s -> s.`host hidden cards never acquire blank space or false applied evidence`() }
        runIsolated { s -> s.`asynchronous host updates stay hidden without reviving a naturally absent card`() }
    }
}
