package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostMemoryPressureBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostCardOptimizationTest {
    private class CardModifier
    private class CardComposer
    private class CardModel

    private class Holder(
        @Suppress("unused") var model: CardModel?
    ) {
        @Suppress("unused")
        fun compose(modifier: CardModifier?, composer: CardComposer?, changed: Int) = Unit
    }

    fun `ogv card point accepts lazily reads argument slots and short circuits on mismatch`() {
        val method = Holder::class.java.getDeclaredMethod(
            "compose",
            CardModifier::class.java,
            CardComposer::class.java,
            Int::class.javaPrimitiveType
        )
        val modelField = Holder::class.java.getDeclaredField("model").apply { isAccessible = true }
        val point = HostOgvCardAdapter.Point(
            method = method,
            modifierIndex = 0,
            composerIndex = 1,
            changedIndex = 2,
            defaultsIndex = null,
            radiusIndex = null,
            modifier = CardModifier::class.java,
            composer = CardComposer::class.java,
            model = modelField
        )

        var slotReads = 0
        val rejectedReceiver = Holder(null)
        assertFalse(point.accepts(rejectedReceiver) { idx ->
            slotReads++
            when (idx) {
                0 -> CardModifier()
                1 -> CardComposer()
                else -> 0
            }
        })
        assertEquals(0, slotReads)

        val acceptedReceiver = Holder(CardModel())
        assertFalse(point.accepts(acceptedReceiver) { idx ->
            slotReads++
            when (idx) {
                0 -> "not-a-modifier"
                1 -> error("composer slot should not be read when modifier mismatches")
                else -> 0
            }
        })
        assertEquals(1, slotReads)

        slotReads = 0
        assertTrue(point.accepts(acceptedReceiver) { idx ->
            slotReads++
            when (idx) {
                0 -> CardModifier()
                1 -> CardComposer()
                else -> 0
            }
        })
        assertEquals(3, slotReads)
        assertTrue(point.accepts(acceptedReceiver, arrayOf(CardModifier(), CardComposer(), 0)))
    }

    fun `card host access clears cached holder fields on memory pressure`() {
        HostMemoryPressureBridge.resetForTests()
        val access = HostVideoCardHostAccess(javaClass.classLoader!!)
        assertEquals(0, access.holderCacheSizeForTests())
        access.trimCaches()
        HostVideoCardHostAccess.trimAllCaches()
        assertEquals(0, access.holderCacheSizeForTests())
    }

    @Test
    fun verifyAllCases() {
        fun runIsolated(block: (HostCardOptimizationTest) -> Unit) {
            val s = HostCardOptimizationTest()
            try {
                block(s)
            } finally {
                HostMemoryPressureBridge.resetForTests()
            }
        }
        runIsolated { s -> s.`ogv card point accepts lazily reads argument slots and short circuits on mismatch`() }
        runIsolated { s -> s.`card host access clears cached holder fields on memory pressure`() }
    }
}
