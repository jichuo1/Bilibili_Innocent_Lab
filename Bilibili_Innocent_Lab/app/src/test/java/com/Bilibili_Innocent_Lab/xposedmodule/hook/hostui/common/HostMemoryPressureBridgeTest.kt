package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common

import android.app.Application
import android.content.ComponentCallbacks
import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import com.Bilibili_Innocent_Lab.xposedmodule.hook.CommentEmojiAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundController
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPreset
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundRasterCache
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundScene
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostCardMicaField
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostChromeAppearance
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostGlassMaterial
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

class HostMemoryPressureBridgeTest {
    private open class RecordingApplication : Application() {
        override fun getApplicationContext(): Context = this

        override fun registerComponentCallbacks(callback: ComponentCallbacks) {
            recordedCallbacks?.add(callback)
        }

        override fun unregisterComponentCallbacks(callback: ComponentCallbacks) {
            recordedCallbacks?.remove(callback)
            unregisteredCallbacks?.add(callback)
        }
    }

    private fun allocateApp(
        registered: MutableList<ComponentCallbacks>,
        unregistered: MutableList<ComponentCallbacks>
    ): Application {
        recordedCallbacks = registered
        unregisteredCallbacks = unregistered
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
        return unsafe.allocateInstance(RecordingApplication::class.java) as Application
    }

    fun restore() {
        HostMemoryPressureBridge.resetForTests()
        recordedCallbacks = null
        unregisteredCallbacks = null
    }

    fun `trim classification distinguishes UI hidden from background and critical levels`() {
        assertEquals(HostMemoryPressureBridge.LEVEL_MODERATE, HostMemoryPressureBridge.classifyTrimLevel(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
        assertEquals(HostMemoryPressureBridge.LEVEL_MODERATE, HostMemoryPressureBridge.classifyTrimLevel(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
        assertEquals(HostMemoryPressureBridge.LEVEL_CRITICAL, HostMemoryPressureBridge.classifyTrimLevel(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL))
        assertEquals(HostMemoryPressureBridge.LEVEL_NONE, HostMemoryPressureBridge.classifyTrimLevel(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
        assertEquals(HostMemoryPressureBridge.LEVEL_MODERATE, HostMemoryPressureBridge.classifyTrimLevel(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND))
        assertEquals(HostMemoryPressureBridge.LEVEL_MODERATE, HostMemoryPressureBridge.classifyTrimLevel(ComponentCallbacks2.TRIM_MEMORY_MODERATE))
        assertEquals(HostMemoryPressureBridge.LEVEL_CRITICAL, HostMemoryPressureBridge.classifyTrimLevel(ComponentCallbacks2.TRIM_MEMORY_COMPLETE))
        assertEquals(HostMemoryPressureBridge.LEVEL_NONE, HostMemoryPressureBridge.classifyTrimLevel(0))
    }

    fun `thermal curve maps status progressively to lumen quality pressure`() {
        assertEquals(0f, HostMemoryPressureBridge.thermalQualityPressure(-1), 0f)
        assertEquals(0f, HostMemoryPressureBridge.thermalQualityPressure(0), 0f)
        assertEquals(0f, HostMemoryPressureBridge.thermalQualityPressure(1), 0f)
        assertEquals(0.5f, HostMemoryPressureBridge.thermalQualityPressure(2), 0f)
        assertEquals(0.75f, HostMemoryPressureBridge.thermalQualityPressure(3), 0f)
        assertEquals(1f, HostMemoryPressureBridge.thermalQualityPressure(4), 0f)
        assertEquals(1f, HostMemoryPressureBridge.thermalQualityPressure(5), 0f)
        assertEquals(1f, HostMemoryPressureBridge.thermalQualityPressure(6), 0f)
    }

    fun `install is idempotent per application and forwards trim levels cleanly`() {
        val registered = mutableListOf<ComponentCallbacks>()
        val unregistered = mutableListOf<ComponentCallbacks>()
        val app = allocateApp(registered, unregistered)
        var moderateCalls = 0
        var criticalCalls = 0
        HostMemoryPressureBridge.registerTrimCallbacks(
            onModerate = { moderateCalls++ },
            onCritical = { criticalCalls++ }
        )

        val observed = ArrayList<HostTrimSeverity>()
        val lease = HostMemoryPressureBridge.registerResource("install-test") { severity ->
            observed += severity
        }
        try {
            HostMemoryPressureBridge.install(app)
            HostMemoryPressureBridge.install(app)
            assertEquals(2, registered.size)

            fun dispatchTrim(level: Int) {
                registered.toList().forEach { (it as ComponentCallbacks2).onTrimMemory(level) }
            }

            @Suppress("DEPRECATION")
            dispatchTrim(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
            assertEquals(0, moderateCalls)
            assertEquals(0, criticalCalls)
            assertEquals(listOf(HostTrimSeverity.HIDDEN), observed)

            @Suppress("DEPRECATION")
            dispatchTrim(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
            assertEquals(1, moderateCalls)
            assertEquals(0, criticalCalls)
            assertEquals(listOf(HostTrimSeverity.HIDDEN, HostTrimSeverity.MODERATE), observed)

            @Suppress("DEPRECATION")
            dispatchTrim(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
            assertEquals(2, moderateCalls)
            assertEquals(1, criticalCalls)
            assertEquals(
                listOf(HostTrimSeverity.HIDDEN, HostTrimSeverity.MODERATE, HostTrimSeverity.CRITICAL),
                observed
            )

            @Suppress("DEPRECATION")
            dispatchTrim(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
            assertEquals(3, moderateCalls)
            assertEquals(2, criticalCalls)

            registered.toList().forEach { (it as ComponentCallbacks2).onLowMemory() }
            assertEquals(4, moderateCalls)
            assertEquals(3, criticalCalls)
            assertEquals(1, observed.count { it == HostTrimSeverity.HIDDEN })

            HostMemoryPressureBridge.resetForTests()
            assertTrue(registered.isEmpty())
            assertEquals(2, unregistered.size)
        } finally {
            lease.close()
        }
    }

    fun `soft floating surface disables refraction and allows render target backdrop only with view content source`() {
        val soft = HostSurfaceStyle.floating(dark = false, radiusDp = 24f, appearance = HostChromeAppearance(HostGlassMaterial.SOFT))
        assertFalse(soft.sampling.refractionEnabled)
        assertEquals(1f, soft.backdropOpacity, 0f)
        assertTrue(HostSurfaceStyle.isRenderTargetBackdropEligible(soft, hasViewContentSource = true))
        assertFalse(HostSurfaceStyle.isRenderTargetBackdropEligible(soft, hasViewContentSource = false))

        val liquid = HostSurfaceStyle.floating(dark = true, radiusDp = 24f, appearance = HostChromeAppearance(HostGlassMaterial.LIQUID))
        assertTrue(liquid.sampling.refractionEnabled)
        assertFalse(HostSurfaceStyle.isRenderTargetBackdropEligible(liquid, hasViewContentSource = true))
    }

    fun `emoji adapter caches can be cleared safely under memory pressure`() {
        CommentEmojiAdapter.clearCaches()
        val res = CommentEmojiAdapter.resolve(commentItem = null, raw = "plain", spans = emptyList())
        assertTrue(res.spanMatches.isEmpty())
        HostMemoryPressureBridge.dispatchModerate()
    }

    fun `background executors allow core thread timeout with positive keepAliveTime`() {
        val executors = listOf(
            HostBackgroundScene.worker,
            HostBackgroundController.worker,
            HostBackgroundRasterCache.worker,
            HostCardMicaField.worker,
            HostGarbTheme.worker
        )
        for (executor in executors) {
            assertTrue("Executor should allow core thread timeout", executor.allowsCoreThreadTimeOut())
            assertTrue("Executor keepAliveTime must be > 0", executor.getKeepAliveTime(TimeUnit.MILLISECONDS) > 0L)
        }
    }

    fun `background scene pressure trim drops offscreen ready tiles and clears hidden scenes`() {
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
        val scene = unsafe.allocateInstance(HostBackgroundScene::class.java) as HostBackgroundScene
        var onscreen = true
        val config = HostBackgroundConfig(preset = HostBackgroundPreset.AURORA)
        fun setSceneField(name: String, value: Any?) {
            HostBackgroundScene::class.java.getDeclaredField(name).apply { isAccessible = true }.set(scene, value)
        }
        val lock = Any()
        val version = AtomicLong()
        val wanted = (0L..3L).map { HostBackgroundScene.Key(config, false, 100, it) }
        val ready = HashMap<HostBackgroundScene.Key, Bitmap>().apply {
            for (key in wanted) put(key, unsafe.allocateInstance(Bitmap::class.java) as Bitmap)
        }
        setSceneField("config", config)
        setSceneField("lock", lock)
        setSceneField("first", 1L)
        setSceneField("last", 2L)
        setSceneField("wanted", wanted)
        setSceneField("ready", ready)
        setSceneField("version", version)
        setSceneField("isOnscreen", { onscreen })

        val scenesLock = HostBackgroundScene::class.java.getDeclaredField("scenesLock").apply { isAccessible = true }.get(null)
        @Suppress("UNCHECKED_CAST")
        val scenes = HostBackgroundScene::class.java.getDeclaredField("scenes").apply { isAccessible = true }.get(null)
            as MutableMap<HostBackgroundScene, Unit>
        synchronized(scenesLock) { scenes[scene] = Unit }
        try {
            assertEquals(4, scene.retainedTileCount)
            HostMemoryPressureBridge.dispatchModerate()
            assertEquals(2, scene.retainedTileCount)
            assertTrue(ready.keys.all { it.band in 1L..2L })
            assertEquals(1L, version.get())

            onscreen = false
            HostMemoryPressureBridge.dispatchCritical()
            assertEquals(0, scene.retainedTileCount)
            assertEquals(2L, version.get())
        } finally {
            synchronized(scenesLock) { scenes.remove(scene) }
        }
    }

    @Test
    fun verifyAllCases() {
        fun runIsolated(block: (HostMemoryPressureBridgeTest) -> Unit) {
            val s = HostMemoryPressureBridgeTest()
            try {
                block(s)
            } finally {
                s.restore()
            }
        }
        runIsolated { s -> s.`trim classification distinguishes UI hidden from background and critical levels`() }
        runIsolated { s -> s.`thermal curve maps status progressively to lumen quality pressure`() }
        runIsolated { s -> s.`install is idempotent per application and forwards trim levels cleanly`() }
        runIsolated { s -> s.`soft floating surface disables refraction and allows render target backdrop only with view content source`() }
        runIsolated { s -> s.`emoji adapter caches can be cleared safely under memory pressure`() }
        runIsolated { s -> s.`background executors allow core thread timeout with positive keepAliveTime`() }
        runIsolated { s -> s.`background scene pressure trim drops offscreen ready tiles and clears hidden scenes`() }
    }

    private companion object {
        var recordedCallbacks: MutableList<ComponentCallbacks>? = null
        var unregisteredCallbacks: MutableList<ComponentCallbacks>? = null
    }
}
