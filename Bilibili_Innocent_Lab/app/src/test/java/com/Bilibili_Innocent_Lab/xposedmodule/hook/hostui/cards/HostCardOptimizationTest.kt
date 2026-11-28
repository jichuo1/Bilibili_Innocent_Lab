package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostMemoryPressureBridge
import com.Bilibili_Innocent_Lab.xposedmodule.testsupport.TestRenamingClassLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    fun `cardMaterialNightTracksActualThemeFlipWhileRespectingExplicitTestBaseline`() {
        assertTrue(HostCardMaterialScope.resolveEffectiveNight(currentNight = true, previousThemeDark = false, latestThemeDark = false))
        assertTrue(HostCardMaterialScope.resolveEffectiveNight(currentNight = false, previousThemeDark = false, latestThemeDark = true))
        assertFalse(HostCardMaterialScope.resolveEffectiveNight(currentNight = true, previousThemeDark = true, latestThemeDark = false))
    }

    open class FakeLayoutManager {
        fun measureChildWithMargins(child: android.view.View, w: Int, h: Int) = Unit
        fun calculateItemDecorationsForChild(child: android.view.View, out: android.graphics.Rect) = Unit
    }
    open class FakeLinearLayoutManager(private val orient: Int = 1) : FakeLayoutManager() {
        fun getOrientation(): Int = orient
    }
    class FakeGridLayoutManager(private val cols: Int, orient: Int) : FakeLinearLayoutManager(orient) {
        fun measureChild(child: android.view.View, spec: Int, boolFlag: Boolean) = Unit
        fun getSpanCount(): Int = cols
    }
    class FakeGridParams(private val index: Int, private val size: Int) {
        fun getSpanIndex(): Int = index
        fun getSpanSize(): Int = size
    }

    fun `tablet 3 and 4 column grids map columns 0 1 2 3 cleanly and keep layout margin symmetry`() {
        val loader = TestRenamingClassLoader(
            requireNotNull(javaClass.classLoader),
            templates = mapOf(
                "androidx.recyclerview.widget.RecyclerView\$LayoutManager" to FakeLayoutManager::class.java,
                "androidx.recyclerview.widget.LinearLayoutManager" to FakeLinearLayoutManager::class.java,
                "androidx.recyclerview.widget.GridLayoutManager" to FakeGridLayoutManager::class.java,
                "androidx.recyclerview.widget.GridLayoutManager\$LayoutParams" to FakeGridParams::class.java
            ),
            replacements = mapOf(
                FakeLayoutManager::class.java.name.replace('.', '/') to "androidx/recyclerview/widget/RecyclerView\$LayoutManager",
                FakeLinearLayoutManager::class.java.name.replace('.', '/') to "androidx/recyclerview/widget/LinearLayoutManager",
                FakeGridLayoutManager::class.java.name.replace('.', '/') to "androidx/recyclerview/widget/GridLayoutManager",
                FakeGridParams::class.java.name.replace('.', '/') to "androidx/recyclerview/widget/GridLayoutManager\$LayoutParams"
            )
        )
        val access = requireNotNull(HostVideoCardGridAccess.resolve(loader))
        val gridCls = loader.loadClass("androidx.recyclerview.widget.GridLayoutManager")
        val linearCls = loader.loadClass("androidx.recyclerview.widget.LinearLayoutManager")
        val paramsCls = loader.loadClass("androidx.recyclerview.widget.GridLayoutManager\$LayoutParams")
        val grid3 = gridCls.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).newInstance(3, 1)
        val grid4 = gridCls.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).newInstance(4, 1)
        val p = paramsCls.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)

        assertEquals(0, access.spacingSpan(grid3, p.newInstance(0, 1)))
        assertEquals(3, access.spacingSpan(grid3, p.newInstance(1, 1)))
        assertEquals(1, access.spacingSpan(grid3, p.newInstance(2, 1)))
        assertEquals(2, access.spacingSpan(grid3, p.newInstance(0, 3)))

        assertEquals(0, access.spacingSpan(grid4, p.newInstance(0, 1)))
        assertEquals(3, access.spacingSpan(grid4, p.newInstance(1, 1)))
        assertEquals(3, access.spacingSpan(grid4, p.newInstance(2, 1)))
        assertEquals(1, access.spacingSpan(grid4, p.newInstance(3, 1)))
        assertEquals(2, access.spacingSpan(linearCls.getConstructor(Int::class.javaPrimitiveType).newInstance(1), Any()))
        assertEquals(-1, access.spacingSpan(grid3, Any()))

        val spec = HostHomeCardSpacingSpec(12, 16)
        assertNotNull(spec.horizontalMargin(3, left = true, padding = 0, decoration = 0, density = 1f))
        val colSpacing = HostVideoCardSpacing(0, 0, 0, 0, 1f)
        assertEquals(7, colSpacing.left(3))
        assertEquals(7, colSpacing.right(3))
        assertEquals(5, colSpacing.top(3))
        assertEquals(12, colSpacing.bottom(3))
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
        runIsolated { s -> s.`cardMaterialNightTracksActualThemeFlipWhileRespectingExplicitTestBaseline`() }
        runIsolated { s -> s.`tablet 3 and 4 column grids map columns 0 1 2 3 cleanly and keep layout margin symmetry`() }
    }
}
