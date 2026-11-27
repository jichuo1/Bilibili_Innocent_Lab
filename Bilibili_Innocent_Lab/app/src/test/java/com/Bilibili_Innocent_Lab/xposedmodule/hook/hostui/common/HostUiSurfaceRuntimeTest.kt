package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common

import android.content.ComponentCallbacks2
import android.view.View
import android.view.ViewTreeObserver
import com.bilab.plugin.api.model.HostThemeSnapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import sun.misc.Unsafe
import java.lang.ref.WeakReference

class HostUiSurfaceRuntimeTest {

    private class StubView : View(null) {
        override fun getViewTreeObserver(): ViewTreeObserver? = null
    }

    @Before
    fun setUp() {
        HostMemoryPressureBridge.resetForTests()
    }

    @After
    fun tearDown() {
        HostMemoryPressureBridge.resetForTests()
    }

    @Test
    fun registerResourceDelegatesToHostResourceRegistryAndSupportsIdempotentClose() {
        val recorded = ArrayList<HostTrimSeverity>()
        val stageCalls = ArrayList<String>()

        val genericLease = HostUiSurfaceRuntime.registerResource("ui-generic") { severity ->
            recorded += severity
        }
        val tieredLease = HostUiSurfaceRuntime.registerResource(
            tag = "ui-tiered",
            onHidden = { stageCalls += "hidden" },
            onModerate = { stageCalls += "moderate" },
            onCritical = null // 回退到 onModerate
        )
        assertEquals(2, HostUiSurfaceRuntime.activeResourceCount())

        @Suppress("DEPRECATION")
        HostMemoryPressureBridge.handleTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        HostMemoryPressureBridge.dispatchModerate()
        HostMemoryPressureBridge.dispatchCritical()

        assertEquals(
            listOf(HostTrimSeverity.HIDDEN, HostTrimSeverity.MODERATE, HostTrimSeverity.CRITICAL),
            recorded
        )
        assertEquals(listOf("hidden", "moderate", "moderate"), stageCalls)

        genericLease.close()
        genericLease.close()
        assertEquals(1, HostUiSurfaceRuntime.activeResourceCount())

        HostMemoryPressureBridge.dispatchModerate()
        assertEquals(3, recorded.size)
        assertEquals(4, stageCalls.size)

        tieredLease.close()
        assertEquals(0, HostUiSurfaceRuntime.activeResourceCount())
    }

    @Test
    fun bindSurfaceScopeClosesOnCriticalWithoutStronglyPinningScope() {
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
        val stubView = unsafe.allocateInstance(StubView::class.java) as StubView
        val scope = HostSurfaceScope(stubView, HostChromeColors(dark = true, accent = 0xFFFF6699.toInt()))
        val lease = HostUiSurfaceRuntime.bindSurfaceScope(scope, tag = "managed-scope")
        assertEquals(1, HostUiSurfaceRuntime.activeResourceCount())

        val closedField = HostSurfaceScope::class.java.getDeclaredField("closed").apply { isAccessible = true }
        assertFalse(closedField.getBoolean(scope))

        HostMemoryPressureBridge.dispatchModerate()
        assertFalse(closedField.getBoolean(scope))

        HostMemoryPressureBridge.dispatchCritical()
        assertTrue(closedField.getBoolean(scope))

        lease.close()
        assertEquals(0, HostUiSurfaceRuntime.activeResourceCount())

        // 验证 WeakReference 绑定在作用域被 GC 后不会阻止回收，且下一次派发自动注销残留租约
        val weakRef = registerEphemeralScope(stubView)
        assertEquals(1, HostUiSurfaceRuntime.activeResourceCount())
        for (attempt in 0 until 20) {
            if (weakRef.get() == null) break
            System.gc()
            System.runFinalization()
            Thread.sleep(20)
        }
        assertNull("bindSurfaceScope must not strongly pin HostSurfaceScope", weakRef.get())
        HostMemoryPressureBridge.dispatchModerate()
        assertEquals(0, HostUiSurfaceRuntime.activeResourceCount())
    }

    private fun registerEphemeralScope(stubView: StubView): WeakReference<HostSurfaceScope> {
        val ephemeral = HostSurfaceScope(stubView, HostChromeColors(dark = false, accent = 0xFF336699.toInt()))
        HostUiSurfaceRuntime.bindSurfaceScope(ephemeral, tag = "ephemeral-scope")
        return WeakReference(ephemeral)
    }

    @Test
    fun snapshotThemeBuildsReadOnlyHostThemeSnapshotWithDefaultsAndCustomRadius() {
        val darkSnapshot = HostUiSurfaceRuntime.snapshotTheme(
            isDark = true,
            surfaceColor = 0xFF141418.toInt(),
            accentColor = 0xFFFB7299.toInt(),
            textPrimaryColor = 0xFFF2F2F7.toInt(),
            textSecondaryColor = 0xFFAEAEB2.toInt(),
            density = 3.0f
        )
        assertEquals(
            HostThemeSnapshot(
                isDark = true,
                surfaceColor = 0xFF141418.toInt(),
                accentColor = 0xFFFB7299.toInt(),
                textPrimaryColor = 0xFFF2F2F7.toInt(),
                textSecondaryColor = 0xFFAEAEB2.toInt(),
                density = 3.0f,
                cornerRadiusDp = 16f
            ),
            darkSnapshot
        )

        val lightSnapshot = HostUiSurfaceRuntime.snapshotTheme(
            isDark = false,
            surfaceColor = 0xFFFFFFFF.toInt(),
            accentColor = 0xFF00AEEC.toInt(),
            textPrimaryColor = 0xFF18191C.toInt(),
            textSecondaryColor = 0xFF61666D.toInt(),
            density = 2.5f,
            cornerRadiusDp = 24f
        )
        assertFalse(lightSnapshot.isDark)
        assertEquals(0xFFFFFFFF.toInt(), lightSnapshot.surfaceColor)
        assertEquals(0xFF00AEEC.toInt(), lightSnapshot.accentColor)
        assertEquals(0xFF18191C.toInt(), lightSnapshot.textPrimaryColor)
        assertEquals(0xFF61666D.toInt(), lightSnapshot.textSecondaryColor)
        assertEquals(2.5f, lightSnapshot.density, 0f)
        assertEquals(24f, lightSnapshot.cornerRadiusDp, 0f)
    }
}
