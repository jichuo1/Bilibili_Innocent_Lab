package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstallResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPortTestRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundController
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundDrawable
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPageHooks
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPages
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPreset
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostDetailOverlay
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostPageChrome
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostCardMaterialScope
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostVideoCardHostAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeColors
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeTheme
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostGarbTheme
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostLatestWorkQueue
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostMemoryPressureBridge
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostRecyclerTypes
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostSurfaceScope
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostChromeAppearance
import com.Bilibili_Innocent_Lab.xposedmodule.testsupport.TestRenamingClassLoader
import com.Bilibili_Innocent_Lab.xposedmodule.ui.PredictiveBack
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.subtractWindowInsets
import com.Bilibili_Innocent_Lab.xposedmodule.ui.theme.MonetColors
import com.lumen.coacervation.engine.host.LumenSurfaceBackend
import com.lumen.coacervation.engine.host.LumenSurfaceBinding
import com.lumen.coacervation.engine.host.LumenSurfaceOptions
import com.lumen.coacervation.engine.host.LumenSurfaceSampling
import com.lumen.coacervation.engine.host.LumenSurfaceTarget
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

internal object HostThemeTestBridge {
    @Volatile var nightMode: Boolean = false
    @Volatile var accentColor: Int = 0xFFFF6699.toInt()
    @Volatile var pageColor: Int = 0xFFF1F2F3.toInt()
    @Volatile var nightReads: Int = 0
    @Volatile var colorReads: Int = 0

    fun reset() {
        nightMode = false
        accentColor = 0xFFFF6699.toInt()
        pageColor = 0xFFF1F2F3.toInt()
        nightReads = 0
        colorReads = 0
    }
}

internal object TplNightTheme {
    @JvmStatic
    fun isNightTheme(@Suppress("UNUSED_PARAMETER") context: Context): Boolean {
        HostThemeTestBridge.nightReads++
        return HostThemeTestBridge.nightMode
    }
}

internal object TplThemeUtils {
    @JvmStatic
    fun getColorById(@Suppress("UNUSED_PARAMETER") context: Context, id: Int): Int {
        HostThemeTestBridge.colorReads++
        return when (id) {
            0x7f010001 -> HostThemeTestBridge.accentColor
            0x7f010002 -> HostThemeTestBridge.pageColor
            else -> 0
        }
    }
}

class HostUiPerfHardeningTest {
    private val unsafe: Unsafe = Unsafe::class.java.getDeclaredField("theUnsafe")
        .apply { isAccessible = true }.get(null) as Unsafe

    @Suppress("UNCHECKED_CAST")
    private fun <T> alloc(clazz: Class<T>): T = unsafe.allocateInstance(clazz) as T

    private fun awaitGc(ref: WeakReference<*>) {
        val queue = ReferenceQueue<Any>()
        val sentinel = ref.get()?.let { WeakReference<Any>(it, queue) } ?: return
        repeat(20) {
            if (ref.get() == null) return
            System.gc()
            System.runFinalization()
            if (queue.remove(5L) != null || ref.get() == null) return
            checkNotNull(sentinel)
        }
    }

    private open class StubView : View(null) {
        var bg: Drawable? = null
        var shownFlag: Boolean = true
        var attachedFlag: Boolean = true
        var onInvalidate: (() -> Unit)? = null

        override fun getBackground(): Drawable? = bg
        override fun setBackground(background: Drawable?) { bg = background }
        override fun isShown(): Boolean = shownFlag
        override fun isAttachedToWindow(): Boolean = attachedFlag
        override fun invalidate() { onInvalidate?.invoke() }
    }

    private open class StubViewGroup : ViewGroup(null) {
        var attachedFlag: Boolean = true
        var shownFlag: Boolean = true

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) = Unit
        override fun isAttachedToWindow(): Boolean = attachedFlag
        override fun isShown(): Boolean = shownFlag
    }

    private open class RetainingDrawable(var pinnedView: View?) : Drawable() {
        override fun draw(canvas: Canvas) = Unit
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = 0
    }

    private open class StubResources : Resources(null, null, null) {
        var cfg: Configuration? = null
        override fun getConfiguration(): Configuration = requireNotNull(cfg)
        override fun getIdentifier(name: String, defType: String, defPackage: String): Int = when (name) {
            "theme_color_secondary" -> 0x7f010001
            "Ga1" -> 0x7f010002
            else -> 0
        }
    }

    private open class StubThemeContext : ContextWrapper(null) {
        var loader: ClassLoader? = null
        var res: Resources? = null
        override fun getClassLoader(): ClassLoader = requireNotNull(loader)
        override fun getResources(): Resources = requireNotNull(res)
        override fun getPackageName(): String = "tv.danmaku.bili"
        override fun getApplicationContext(): Context = this
    }

    fun `card paint drops target view strong reference and trimCaches prunes stale entries`() {
        val paintClass = Class.forName("com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostCardMaterialScope\$CardPaint")
        for (field in paintClass.declaredFields) {
            assertFalse("CardPaint must not retain View strongly: ${field.name}", View::class.java.isAssignableFrom(field.type))
            assertFalse("CardPaint must not retain Context strongly: ${field.name}", Context::class.java.isAssignableFrom(field.type))
        }
        val densityField = paintClass.getDeclaredField("density")
        assertEquals(Float::class.javaPrimitiveType, densityField.type)

        val scope = alloc(HostCardMaterialScope::class.java)
        val owner = alloc(StubViewGroup::class.java).apply { shownFlag = false }
        val cards = WeakHashMap<View, WeakReference<Any>>()
        val paints = WeakHashMap<Any, WeakReference<View>>()
        fun setScope(name: String, value: Any?) {
            HostCardMaterialScope::class.java.getDeclaredField(name).apply { isAccessible = true }.set(scope, value)
        }
        setScope("owner", owner)
        setScope("visible", false)
        setScope("closed", false)
        setScope("cards", cards)
        setScope("paints", paints)

        var cardView: StubView? = alloc(StubView::class.java)
        val viewRef = WeakReference(cardView)
        var cardPaint: Any? = alloc(paintClass).apply {
            paintClass.getDeclaredField("host").apply { isAccessible = true }.set(this, WeakReference(cardView))
            paintClass.getDeclaredField("disposed").apply { isAccessible = true }.setBoolean(this, false)
        }
        cards[cardView!!] = WeakReference(cardPaint!!)
        paints[cardPaint!!] = WeakReference(cardView)
        cardView.bg = null

        cardView = null
        // Keep CardPaint strongly alive to prove CardPaint no longer pins View.
        awaitGc(viewRef)
        assertNull("CardPaint must allow View reclamation while CardPaint is still referenced", viewRef.get())

        paintClass.getDeclaredField("disposed").apply { isAccessible = true }.setBoolean(cardPaint, true)
        scope.trimCaches()
        assertTrue(cards.isEmpty())
        assertTrue(paints.isEmpty())
        cardPaint = null
    }

    fun `surface scope avoids strong view cycle in Surface and caches resolved effectiveOptions`() {
        val surfaceClass = Class.forName("com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostSurfaceScope\$Surface")
        for (field in surfaceClass.declaredFields) {
            assertFalse("Surface must not retain View: ${field.name}", View::class.java.isAssignableFrom(field.type))
            assertFalse("Surface must not retain Binding strongly: ${field.name}",
                LumenSurfaceBinding::class.java.isAssignableFrom(field.type))
            assertFalse("Surface must not retain Drawable strongly: ${field.name}",
                Drawable::class.java.isAssignableFrom(field.type))
        }

        val scope = alloc(HostSurfaceScope::class.java)
        @Suppress("UNCHECKED_CAST")
        val surfaces = WeakHashMap<View, Any>()
        fun setScope(name: String, value: Any?) {
            HostSurfaceScope::class.java.getDeclaredField(name).apply { isAccessible = true }.set(scope, value)
        }
        setScope("anchor", WeakReference<View>(null))
        setScope("surfaces", surfaces)
        setScope("samplingBackend", LumenSurfaceBackend.SOFTWARE)
        setScope("appearance", HostChromeAppearance())
        setScope("dark", false)
        setScope("garbAccent", 0xFFFF6699.toInt())
        setScope("optionsEpoch", 0)
        setScope("closed", false)

        val baseOptions = LumenSurfaceOptions(sampling = LumenSurfaceSampling(enabled = true, backend = LumenSurfaceBackend.AUTO))
        val surfaceRecord = alloc(surfaceClass).apply {
            surfaceClass.getDeclaredField("options").apply { isAccessible = true }.set(this, baseOptions)
            surfaceClass.getDeclaredField("resolvedEpoch").apply { isAccessible = true }.setInt(this, -1)
        }

        val resolvedMethod = HostSurfaceScope::class.java.getDeclaredMethod("resolvedOptions", surfaceClass)
            .apply { isAccessible = true }
        val firstResolved = resolvedMethod.invoke(scope, surfaceRecord) as LumenSurfaceOptions
        val secondResolved = resolvedMethod.invoke(scope, surfaceRecord) as LumenSurfaceOptions
        assertSame("Repeated frames must reuse cached resolvedOptions without copy()", firstResolved, secondResolved)
        assertEquals(LumenSurfaceBackend.SOFTWARE, firstResolved.sampling.backend)

        setScope("dark", true)
        setScope("optionsEpoch", 1)
        val afterThemeChange = resolvedMethod.invoke(scope, surfaceRecord) as LumenSurfaceOptions
        assertNotSame(firstResolved, afterThemeChange)
        assertSame(afterThemeChange, resolvedMethod.invoke(scope, surfaceRecord))

        var view: StubView? = alloc(StubView::class.java)
        var drawable: RetainingDrawable? = alloc(RetainingDrawable::class.java).apply { pinnedView = view }
        var binding: LumenSurfaceBinding? = alloc(LumenSurfaceBinding::class.java)
        surfaceClass.getDeclaredMethod("setBinding", LumenSurfaceBinding::class.java).apply { isAccessible = true }
            .invoke(surfaceRecord, binding)
        surfaceClass.getDeclaredMethod("setBackground", Drawable::class.java).apply { isAccessible = true }
            .invoke(surfaceRecord, drawable)
        surfaces[view!!] = surfaceRecord

        val viewRef = WeakReference(view)
        val drawableRef = WeakReference(drawable)
        view = null
        drawable = null
        binding = null
        awaitGc(viewRef)
        awaitGc(drawableRef)
        assertNull("HostSurfaceScope must not pin View keys through Surface.background/binding", viewRef.get())
        assertNull(drawableRef.get())

        scope.close()
        assertTrue(surfaces.isEmpty())
    }

    fun `HostChromeTheme caches reads within 250ms TTL and invalidates on uiMode change or invalidate`() {
        HostThemeTestBridge.reset()
        val cfg = alloc(Configuration::class.java).apply { uiMode = Configuration.UI_MODE_NIGHT_NO }
        val resources = alloc(StubResources::class.java).apply { this.cfg = cfg }
        val context = alloc(StubThemeContext::class.java).apply {
            loader = TestRenamingClassLoader(
                HostUiPerfHardeningTest::class.java.classLoader!!,
                templates = mapOf(
                    "com.bilibili.lib.ui.util.NightTheme" to TplNightTheme::class.java,
                    "com.bilibili.magicasakura.utils.ThemeUtils" to TplThemeUtils::class.java
                ),
                hiddenNames = setOf("com.bilibili.lib.ui.garb.GarbManager", "com.bilibili.lib.ui.garb.Garb")
            )
            res = resources
        }
        var now = 1_000L
        val theme = HostChromeTheme(context, cacheTtlMs = 250L, clock = { now })

        val c0 = theme.read()
        assertFalse(c0.dark)
        assertEquals(0xFFFF6699.toInt(), c0.accent)
        assertEquals(1, HostThemeTestBridge.colorReads)
        assertEquals(1, HostThemeTestBridge.nightReads)

        // Mutate backing host theme within TTL: should hit cache.
        HostThemeTestBridge.accentColor = 0xFF33B5E5.toInt()
        now = 1_249L
        val c1 = theme.read()
        assertSame(c0, c1)
        assertEquals(1, HostThemeTestBridge.colorReads)

        // Expire TTL (250ms): refreshes immediately.
        now = 1_250L
        val c2 = theme.read()
        assertEquals(0xFF33B5E5.toInt(), c2.accent)
        assertEquals(2, HostThemeTestBridge.colorReads)

        // Configuration uiMode change invalidates before TTL expires.
        HostThemeTestBridge.nightMode = true
        HostThemeTestBridge.accentColor = 0xFF112233.toInt()
        cfg.uiMode = Configuration.UI_MODE_NIGHT_YES
        now = 1_260L
        val c3 = theme.read()
        assertTrue(c3.dark)
        assertEquals(0xFF112233.toInt(), c3.accent)
        assertEquals(3, HostThemeTestBridge.colorReads)

        // Explicit invalidate resets cache cleanly.
        HostThemeTestBridge.accentColor = 0xFF445566.toInt()
        theme.invalidate()
        val c4 = theme.read()
        assertEquals(0xFF445566.toInt(), c4.accent)
        assertEquals(4, HostThemeTestBridge.colorReads)

        // pageBase caches independently for same dark/uiMode and refreshes after TTL / invalidation.
        val countBeforePage = HostThemeTestBridge.colorReads
        HostThemeTestBridge.pageColor = 0xFF121314.toInt()
        assertEquals(0xFF121314.toInt(), theme.pageBase(dark = true))
        HostThemeTestBridge.pageColor = 0xFF18191A.toInt()
        now = 1_300L
        assertEquals(0xFF121314.toInt(), theme.pageBase(dark = true))
        assertEquals(countBeforePage + 1, HostThemeTestBridge.colorReads)

        theme.invalidate()
        assertEquals(0xFF18191A.toInt(), theme.pageBase(dark = true))
        assertEquals(countBeforePage + 2, HostThemeTestBridge.colorReads)
        assertEquals(0xFF18191A.toInt(), HostChromeColors.pageBase(0xFF18191A.toInt(), dark = true))
    }

    fun `HostRecyclerTypes excludes ViewPager2 when requested and inherits matches NestedScrollView`() {
        val adapter = HostRecyclerTypes(excludeViewPager2 = true, maxEntries = 2, clearOnOverflow = true)
        class CustomRecycler(context: Context) : RecyclerView(context)
        assertTrue(adapter.matches(RecyclerView::class.java))
        assertTrue(adapter.matches(CustomRecycler::class.java))
        assertFalse(adapter.matches(StubViewGroup::class.java))
        assertTrue(HostRecyclerTypes.inherits(NestedScrollView::class.java, "androidx.core.widget.NestedScrollView"))
        assertFalse(HostRecyclerTypes.inherits(StubViewGroup::class.java, "androidx.core.widget.NestedScrollView"))
        adapter.clear()
    }

    fun `HostThreadGuard protects HostBackgroundDrawable, HostDetailOverlay, and HostGarbTheme worker`() {
        HostThreadGuard.resetForTests()

        val drawable = alloc(HostBackgroundDrawable::class.java)
        HostBackgroundDrawable::class.java.getDeclaredMethod("onBoundsChange", Rect::class.java)
            .apply { isAccessible = true }
            .invoke(drawable, alloc(Rect::class.java))
        drawable.draw(alloc(Canvas::class.java))
        val countsAfterDrawable = HostThreadGuard.failureCounts()
        assertTrue((countsAfterDrawable["host.background.drawable.bounds"] ?: 0L) >= 1L)
        assertTrue((countsAfterDrawable["host.background.drawable.draw"] ?: 0L) >= 1L)

        val overlay = alloc(HostDetailOverlay::class.java)
        overlay.draw(alloc(Canvas::class.java))
        val targetFieldType = HostDetailOverlay::class.java.getDeclaredField("target").type
        val targetClass = targetFieldType.takeUnless { it.isInterface }
            ?: Class.forName("com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostDetailOverlay\$target\$1")
        val target = alloc(targetClass) as LumenSurfaceTarget
        val failingView = alloc(StubView::class.java).apply {
            onInvalidate = { error("simulated invalidate failure") }
        }
        HostDetailOverlay::class.java.getDeclaredField("view").apply { isAccessible = true }
            .set(overlay, WeakReference<View>(failingView))
        targetClass.declaredFields.first { it.type == HostDetailOverlay::class.java }
            .apply { isAccessible = true }
            .set(target, overlay)
        target.invalidate()
        assertFalse(target.localToScreen(FloatArray(2)))
        val countsAfterOverlay = HostThreadGuard.failureCounts()
        assertTrue((countsAfterOverlay["host.detail.overlay.draw"] ?: 0L) >= 1L)
        assertTrue((countsAfterOverlay["host.detail.overlay.invalidate"] ?: 0L) >= 1L)
        assertTrue((countsAfterOverlay["host.detail.overlay.matrix"] ?: 0L) >= 1L)

        val latch = CountDownLatch(1)
        val queue = HostLatestWorkQueue { task ->
            HostGarbTheme.worker.execute(HostThreadGuard.runnable("host.garb.worker") { task.run() })
            HostGarbTheme.worker.execute { latch.countDown() }
        }
        queue.submit { error("simulated garb sampling failure") }
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue((HostThreadGuard.failureCounts()["host.garb.worker"] ?: 0L) >= 1L)
    }

    fun `HostPageChrome trimCaches prunes unattached targets and closes detached host`() {
        val root = alloc(StubViewGroup::class.java).apply { attachedFlag = true }
        val owned = WeakHashMap<View, Drawable>()
        val chrome = alloc(HostPageChrome::class.java)
        val targets = WeakHashMap<View, Any>()
        val targetClass = Class.forName("com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostPageChrome\$Target")
        val roleClass = Class.forName("com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostPageChrome\$Role")
        val toolbarRole = roleClass.enumConstants!![0]
        val target = alloc(targetClass).apply {
            targetClass.getDeclaredField("role").apply { isAccessible = true }.set(this, toolbarRole)
        }
        val child = alloc(StubView::class.java).apply { attachedFlag = false }
        targets[child] = target
        owned[child] = alloc(RetainingDrawable::class.java)

        fun setChrome(name: String, value: Any?) {
            HostPageChrome::class.java.getDeclaredField(name).apply { isAccessible = true }.set(chrome, value)
        }
        setChrome("root", WeakReference(root))
        setChrome("targets", targets)
        setChrome("owned", owned)
        setChrome("recyclerTypes", HostRecyclerTypes(excludeViewPager2 = true, maxDepth = 24))

        chrome.trimCaches()
        assertTrue(targets.isEmpty())
        assertTrue(owned.isEmpty())

        targets[child] = target
        owned[child] = alloc(RetainingDrawable::class.java)
        root.attachedFlag = false
        chrome.trimCaches()
        assertTrue(targets.isEmpty())
        assertTrue(owned.isEmpty())
    }

    fun `HostBackgroundPageHooks supports kntr bilitheme and obfuscated comment3 section header`() {
        val baseLoader = requireNotNull(javaClass.classLoader)
        val loader = phase5ComposeHostLoader(
            baseLoader,
            hideLayoutNode = false,
            use2ParamFillMaxSize = true,
            omitRowKt = true,
            includeKntrTheme = true,
            includeObfuscatedComment3 = true
        )
        val registrar = PlayerPortTestRegistrar()
        val errors = mutableListOf<String>()
        val env = HookEnvironment(
            "tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
            { _, _ -> }, { _, _ -> }, { id, msg -> errors += "$id:$msg" }
        )
        val controller = HostBackgroundController(
            HostBackgroundConfig(preset = HostBackgroundPreset.STARRY),
            onError = { errors += "bg:$it" }
        )
        val pages = HostBackgroundPages(controller, HostVideoCardHostAccess(loader)) { errors += "pages:$it" }
        val count = HostBackgroundPageHooks.install(env, pages)
        assertTrue("expected hooks installed, errors=$errors, hooks=${registrar.hooks.keys}", count > 0)
        assertTrue("host_background.compose_night.kntr" in registrar.hooks)
        assertTrue("host_background.compose_color.kntr" in registrar.hooks)
        assertTrue(registrar.hooks.keys.any { it.startsWith("host_background.comment_section.") })
    }

    fun `oem predictive back inset clamping and monet wallpaper gates hold`() {
        assertFalse(PredictiveBack.shouldApplyRuntimeToggle(sdkInt = 33))
        assertTrue(PredictiveBack.shouldApplyRuntimeToggle(sdkInt = 34))
        assertTrue(PredictiveBack.shouldApplyRuntimeToggle(sdkInt = 35))
        assertFalse(PredictiveBack.shouldApplyRuntimeToggle(sdkInt = 36))
        assertFalse(PredictiveBack.shouldApplyRuntimeToggle(sdkInt = 37))

        assertEquals(1080 to 1710, subtractWindowInsets(1080, 1920, left = 0, top = 84, right = 0, bottom = 126))
        assertEquals(1 to 1, subtractWindowInsets(320, 480, left = 400, top = 500, right = 0, bottom = 0))
        assertEquals(720 to 1280, subtractWindowInsets(720, 1280, left = -10, top = 0, right = 0, bottom = 0))

        assertEquals(0.5, MonetColors.sanitizeContrastLevel(0.5f), 0.0001)
        assertEquals(1.0, MonetColors.sanitizeContrastLevel(3.0f), 0.0001)
        assertEquals(-1.0, MonetColors.sanitizeContrastLevel(-2.0f), 0.0001)
        assertEquals(0.0, MonetColors.sanitizeContrastLevel(Float.NaN), 0.0001)
        assertFalse(MonetColors.canQueryWallpaperColors(sdkInt = 26, wallpaperSupported = true))
        assertTrue(MonetColors.canQueryWallpaperColors(sdkInt = 27, wallpaperSupported = true))
        assertTrue(MonetColors.canQueryWallpaperColors(sdkInt = 30, wallpaperSupported = true))
        assertTrue(MonetColors.canQueryWallpaperColors(sdkInt = 35, wallpaperSupported = true))
        assertFalse(MonetColors.canQueryWallpaperColors(sdkInt = 29, wallpaperSupported = false))
    }

    @Test
    fun verifyAllCases() {
        fun runIsolated(block: (HostUiPerfHardeningTest) -> Unit) {
            val s = HostUiPerfHardeningTest()
            try {
                block(s)
            } finally {
                HostThreadGuard.resetForTests()
                HostMemoryPressureBridge.resetForTests()
                HostThemeTestBridge.reset()
            }
        }
        runIsolated { s -> s.`card paint drops target view strong reference and trimCaches prunes stale entries`() }
        runIsolated { s -> s.`surface scope avoids strong view cycle in Surface and caches resolved effectiveOptions`() }
        runIsolated { s -> s.`HostChromeTheme caches reads within 250ms TTL and invalidates on uiMode change or invalidate`() }
        runIsolated { s -> s.`HostRecyclerTypes excludes ViewPager2 when requested and inherits matches NestedScrollView`() }
        runIsolated { s -> s.`HostThreadGuard protects HostBackgroundDrawable, HostDetailOverlay, and HostGarbTheme worker`() }
        runIsolated { s -> s.`HostPageChrome trimCaches prunes unattached targets and closes detached host`() }
        runIsolated { s -> s.`HostBackgroundPageHooks supports kntr bilitheme and obfuscated comment3 section header`() }
        runIsolated { s -> s.`oem predictive back inset clamping and monet wallpaper gates hold`() }
    }
}

internal fun phase5ComposeHostLoader(
    baseLoader: ClassLoader,
    hideLayoutNode: Boolean,
    brokenConstructor: Boolean = false,
    use2ParamFillMaxSize: Boolean = true,
    omitRowKt: Boolean = false,
    includeKntrTheme: Boolean = false,
    includeObfuscatedComment3: Boolean = false
): ClassLoader {
    val templates = linkedMapOf<String, Class<*>>(
        "androidx.compose.ui.Modifier" to TplModifier::class.java,
        "androidx.compose.ui.Modifier\$Companion" to TplModifier.Companion::class.java,
        "androidx.compose.ui.Modifier\$Element" to TplModifierElement::class.java,
        "androidx.compose.ui.graphics.Brush" to TplBrush::class.java,
        "androidx.compose.ui.graphics.Shape" to TplShape::class.java,
        "androidx.compose.ui.graphics.RectangleShapeKt" to TplRectangleShapeKt::class.java,
        "androidx.compose.foundation.BackgroundKt" to TplBackgroundKt::class.java,
        "androidx.compose.foundation.layout.PaddingKt" to TplPaddingKt::class.java,
        "androidx.compose.foundation.layout.BoxKt" to TplBoxKt::class.java,
        "androidx.compose.foundation.layout.SpacerKt" to TplSpacerKt::class.java,
        "androidx.compose.ui.draw.DrawModifierKt" to TplDrawModifierKt::class.java,
        "androidx.compose.ui.graphics.drawscope.DrawScope" to TplDrawScope::class.java,
        "androidx.compose.ui.graphics.drawscope.ContentDrawScope" to TplContentDrawScope::class.java,
        "androidx.compose.ui.graphics.drawscope.DrawContext" to TplDrawContext::class.java,
        "androidx.compose.ui.graphics.Canvas" to TplCanvas::class.java,
        "androidx.compose.ui.graphics.AndroidCanvas_androidKt" to TplAndroidCanvasKt::class.java,
        "androidx.compose.runtime.Composer" to TplComposer::class.java,
        "androidx.compose.runtime.CompositionLocal" to TplCompositionLocal::class.java,
        "androidx.compose.ui.platform.AndroidCompositionLocals_androidKt" to TplAndroidLocalsKt::class.java,
        "androidx.compose.ui.platform.AndroidComposeView" to TplAndroidComposeView::class.java,
        "androidx.compose.ui.node.LayoutNodeDrawScope" to TplLayoutNodeDrawScope::class.java,
        "androidx.compose.runtime.SnapshotIntStateKt" to TplSnapshotIntStateKt::class.java,
        "androidx.compose.runtime.IntState" to TplIntState::class.java,
        "androidx.compose.runtime.MutableIntState" to TplMutableIntState::class.java,
        "androidx.compose.ui.layout.OnGloballyPositionedModifierKt" to TplOnGloballyPositionedKt::class.java,
        "androidx.compose.ui.layout.LayoutCoordinates" to TplLayoutCoordinates::class.java,
        "androidx.compose.ui.layout.LayoutCoordinatesKt" to TplLayoutCoordinatesKt::class.java,
        "androidx.compose.ui.Alignment" to TplAlignment::class.java,
        "androidx.compose.ui.unit.LayoutDirection" to TplLayoutDirection::class.java,
        "androidx.compose.ui.layout.SubcomposeMeasureScope" to TplSubcomposeMeasureScope::class.java,
        "androidx.compose.ui.layout.SubcomposeLayoutKt" to TplSubcomposeLayoutKt::class.java,
        "androidx.compose.foundation.shape.RoundedCornerShape" to TplRoundedCornerShape::class.java,
        "androidx.compose.foundation.shape.RoundedCornerShapeKt" to TplRoundedCornerShapeKt::class.java,
        "androidx.compose.ui.draw.ShadowKt" to TplShadowKt::class.java,
        "androidx.compose.ui.draw.ClipKt" to TplClipKt::class.java,
        "kntr.base.imageloader.ImmutableImageRequest" to TplImmutableImageRequest::class.java,
        "kntr.base.imageloader.BiliImageKt" to TplBiliImageKt::class.java,
        "androidx.compose.ui.node.ComposeUiNode" to (if (brokenConstructor) TplBrokenComposeUiNode::class.java else TplComposeUiNode::class.java),
        "androidx.compose.ui.node.ComposeUiNode\$Companion" to (if (brokenConstructor) TplBrokenComposeUiNode.Companion::class.java else TplComposeUiNode.Companion::class.java),
        "renamed.compose.LayoutNodeCtorFn" to (if (brokenConstructor) TplBrokenConstructorFn::class.java else TplLayoutNodeConstructorFn::class.java),
        "renamed.compose.RectShape" to TplRectShape::class.java,
        "renamed.compose.FullModifier" to TplFullModifier::class.java,
        "renamed.compose.MutableIntStateImpl" to TplMutableIntStateImpl::class.java,
        "renamed.compose.BgElement6" to TplBgElement6::class.java
    )
    if (hideLayoutNode) {
        templates["renamed.compose.LayoutNodeImpl"] = TplLayoutNode::class.java
    } else {
        templates["androidx.compose.ui.node.LayoutNode"] = TplLayoutNode::class.java
    }
    templates["androidx.compose.foundation.layout.SizeKt"] =
        if (use2ParamFillMaxSize) TplSizeKtNoDefault::class.java else TplSizeKtDefault::class.java
    if (!omitRowKt) {
        templates["androidx.compose.foundation.layout.RowKt"] = TplRowKt::class.java
        templates["androidx.compose.ui.layout.MeasurePolicy"] = TplMeasurePolicy::class.java
        templates["androidx.compose.foundation.layout.Arrangement\$Horizontal"] = TplArrHorizontal::class.java
        templates["androidx.compose.ui.Alignment\$Vertical"] = TplAlignVertical::class.java
    }
    if (includeKntrTheme) {
        templates["kntr.common.bilitheme.BiliThemeKt"] = TplKntrBiliThemeKt::class.java
        templates["kntr.common.bilitheme.ThemeStrategy"] = TplThemeStrategy::class.java
        templates["kntr.common.bilitheme.BiliColors"] = TplBiliColors::class.java
        templates["androidx.fragment.app.Fragment"] = TplFragment::class.java
    }
    if (includeObfuscatedComment3) {
        templates["androidx.recyclerview.widget.RecyclerView"] = TplDummy::class.java
        templates["androidx.recyclerview.widget.RecyclerView\$ItemDecoration"] = TplItemDecoration::class.java
        templates["com.bilibili.app.comment3.ui.view.CommentDetailLayer"] = TplCommentDetailLayer::class.java
        templates["com.bilibili.app.comment3.data.model.CommentBase"] = TplCommentBase::class.java
        templates["com.bilibili.app.comment3.data.model.CommentItem"] = TplCommentItem::class.java
        templates["com.bilibili.app.comment3.data.model.obfuscatedR"] = TplObfuscatedSectionHeader::class.java
        templates["o.SectionDecor"] = TplCommentDecoration::class.java
    }

    val replacements = templates.entries.associate { (targetName, srcCls) ->
        srcCls.name.replace('.', '/') to targetName.replace('.', '/')
    }

    val hiddenNames = buildSet {
        if (hideLayoutNode) add("androidx.compose.ui.node.LayoutNode")
        if (omitRowKt) add("androidx.compose.foundation.layout.RowKt")
        if (includeObfuscatedComment3) add("com.bilibili.app.comment3.data.model.r")
        if (includeKntrTheme) {
            add("com.bilibili.compose.theme.BiliThemeKt")
            add("com.bilibili.compose.theme.BiliColors")
        }
    }

    return TestRenamingClassLoader(
        parent = baseLoader,
        templates = templates,
        replacements = replacements,
        hiddenNames = hiddenNames
    )
}

internal interface TplModifier {
    fun foldIn(initial: Any?, operation: kotlin.jvm.functions.Function2<Any?, Any?, Any?>): Any?
    fun then(other: TplModifier): TplModifier
    interface Element : TplModifier
    companion object : TplModifier {
        override fun foldIn(initial: Any?, operation: kotlin.jvm.functions.Function2<Any?, Any?, Any?>): Any? = initial
        override fun then(other: TplModifier): TplModifier = other
    }
}
internal interface TplModifierElement : TplModifier
internal class TplFullModifier : TplModifier {
    override fun foldIn(initial: Any?, operation: kotlin.jvm.functions.Function2<Any?, Any?, Any?>): Any? = initial
    override fun then(other: TplModifier): TplModifier = other
}
internal interface TplShape
internal class TplRectShape : TplShape
internal class TplBrush
internal object TplRectangleShapeKt {
    private val rect = TplRectShape()
    @JvmStatic fun getRectangleShape(): TplShape = rect
}
internal class TplBgElement6(
    @JvmField val color: Long,
    @JvmField val brush: TplBrush?,
    @JvmField val alpha: Float,
    @JvmField val shape: TplShape,
    @JvmField val extra1: Int,
    @JvmField val extra2: String
) : TplModifierElement {
    override fun foldIn(initial: Any?, operation: kotlin.jvm.functions.Function2<Any?, Any?, Any?>): Any? = operation.invoke(initial, this)
    override fun then(other: TplModifier): TplModifier = other
}
internal object TplBackgroundKt {
    @JvmStatic fun `background-bw27NRU`(mod: TplModifier, color: Long, shape: TplShape): TplModifier =
        TplBgElement6(color, null, 1f, shape, 0, "bg")
}
internal object TplPaddingKt {
    @JvmStatic fun `padding-qDBjuR0`(mod: TplModifier, a: Float, b: Float, c: Float, d: Float): TplModifier = mod
}
internal object TplBoxKt {
    @JvmStatic fun Box(mod: TplModifier, c: TplComposer, changed: Int) = Unit
}
internal object TplSpacerKt {
    @JvmStatic fun Spacer(mod: TplModifier, c: TplComposer, changed: Int) = Unit
}
internal object TplSizeKtDefault {
    private val full = TplFullModifier()
    @JvmStatic fun `fillMaxSize$default`(m: TplModifier, fraction: Float, mask: Int, obj: Any?): TplModifier = full
    @JvmStatic fun `height-3ABfNKs`(m: TplModifier, h: Float): TplModifier = m
    @JvmStatic fun wrapContentSize(m: TplModifier, a: TplAlignment, u: Boolean): TplModifier = m
    @JvmStatic fun fillMaxWidth(m: TplModifier, f: Float): TplModifier = m
}
internal object TplSizeKtNoDefault {
    private val full = TplFullModifier()
    @JvmStatic fun fillMaxSize(m: TplModifier, fraction: Float): TplModifier = full
    @JvmStatic fun `height-3ABfNKs`(m: TplModifier, h: Float): TplModifier = m
    @JvmStatic fun wrapContentSize(m: TplModifier, a: TplAlignment, u: Boolean): TplModifier = m
    @JvmStatic fun fillMaxWidth(m: TplModifier, f: Float): TplModifier = m
}
internal class TplLayoutNodeConstructorFn : kotlin.jvm.functions.Function0<Any> {
    override fun invoke(): Any = TplLayoutNode()
}
internal class TplBrokenConstructorFn : kotlin.jvm.functions.Function0<Any> {
    override fun invoke(): Any = Any()
}
internal interface TplComposeUiNode {
    fun setModifier(mod: TplModifier)
    fun getModifier(): TplModifier
    companion object {
        fun getConstructor(): kotlin.jvm.functions.Function0<Any> = TplLayoutNodeConstructorFn()
    }
}
internal interface TplBrokenComposeUiNode {
    fun setModifier(mod: TplModifier)
    fun getModifier(): TplModifier
    companion object {
        fun getConstructor(): kotlin.jvm.functions.Function0<Any> = TplBrokenConstructorFn()
    }
}
internal class TplLayoutNode : TplComposeUiNode {
    private var m: TplModifier = TplModifier.Companion
    override fun setModifier(mod: TplModifier) { m = mod }
    override fun getModifier(): TplModifier = m
}
internal interface TplDrawScope {
    fun `getSize-NH-jbRc`(): Long
    fun getDrawContext(): TplDrawContext
}
internal interface TplContentDrawScope : TplDrawScope {
    fun drawContent()
}
internal interface TplDrawContext { fun getCanvas(): TplCanvas }
internal class TplCanvas
internal object TplAndroidCanvasKt {
    @JvmStatic fun getNativeCanvas(c: TplCanvas): Canvas = Canvas()
}
internal object TplDrawModifierKt {
    @JvmStatic fun drawBehind(m: TplModifier, fn: kotlin.jvm.functions.Function1<Any?, Any?>): TplModifier = m
    @JvmStatic fun drawWithContent(m: TplModifier, fn: kotlin.jvm.functions.Function1<Any?, Any?>): TplModifier = m
}
internal interface TplComposer { fun consume(local: TplCompositionLocal): Any? }
internal open class TplCompositionLocal
internal object TplAndroidLocalsKt {
    private val local = TplCompositionLocal()
    @JvmStatic fun getLocalView(): TplCompositionLocal = local
}
internal class TplAndroidComposeView { fun dispatchDraw(c: Canvas) = Unit }
internal interface TplIntState { fun getIntValue(): Int }
internal interface TplMutableIntState : TplIntState { fun setIntValue(v: Int) }
internal class TplMutableIntStateImpl(private var v: Int) : TplMutableIntState {
    override fun getIntValue(): Int = v
    override fun setIntValue(value: Int) { v = value }
}
internal object TplSnapshotIntStateKt {
    @JvmStatic fun mutableIntStateOf(init: Int): TplMutableIntState = TplMutableIntStateImpl(init)
}
internal object TplOnGloballyPositionedKt {
    @JvmStatic fun onGloballyPositioned(m: TplModifier, fn: kotlin.jvm.functions.Function1<Any?, Any?>): TplModifier = m
}
internal interface TplLayoutCoordinates {
    fun isAttached(): Boolean
    fun getParentLayoutCoordinates(): TplLayoutCoordinates?
    fun `getSize-YbymL2g`(): Long
    fun `localPositionOf-R5De75A`(sourceCoordinates: TplLayoutCoordinates, relativeToSource: Long): Long
}
internal object TplLayoutCoordinatesKt {
    @JvmStatic fun positionInWindow(c: TplLayoutCoordinates): Long = 0L
    @JvmStatic fun positionInRoot(c: TplLayoutCoordinates): Long = 0L
}
internal interface TplAlignment {
    fun `align-KFBX0sM`(size: Long, space: Long, dir: TplLayoutDirection): Long
}
internal enum class TplLayoutDirection { Ltr }
internal interface TplSubcomposeMeasureScope {
    fun subcompose(slotId: Any?, content: kotlin.jvm.functions.Function2<Any?, Any?, Any?>): List<Any>
}
internal object TplSubcomposeLayoutKt {
    @JvmStatic fun SubcomposeLayout(m: TplModifier, policy: kotlin.jvm.functions.Function2<Any?, Any?, Any?>, c: TplComposer, changed: Int, def: Int) = Unit
}
internal class TplRoundedCornerShape : TplShape
internal object TplRoundedCornerShapeKt {
    @JvmStatic fun RoundedCornerShape(percent: Int): TplRoundedCornerShape = TplRoundedCornerShape()
    @JvmStatic fun `RoundedCornerShape-0680j_4`(size: Float): TplRoundedCornerShape = TplRoundedCornerShape()
}
internal object TplShadowKt {
    @JvmStatic fun `shadow-s4CzXII`(m: TplModifier, elevation: Float, shape: TplShape, clip: Boolean, ambient: Long, spot: Long): TplModifier = m
}
internal object TplClipKt {
    @JvmStatic fun clip(m: TplModifier, shape: TplShape): TplModifier = m
}
internal class TplImmutableImageRequest
internal object TplBiliImageKt {
    @JvmStatic fun BiliImage(req: TplImmutableImageRequest, mod: TplModifier, desc: String?, c: TplComposer, changed: Int) = Unit
}
internal object TplRowKt {
    @JvmStatic fun rowMeasurePolicy(h: TplArrHorizontal, v: TplAlignVertical, c: TplComposer, changed: Int): TplMeasurePolicy = TplMeasurePolicy()
}
internal class TplMeasurePolicy
internal class TplArrHorizontal
internal class TplAlignVertical
internal enum class TplThemeStrategy { Auto, FollowApp, ForceNight }
internal object TplKntrBiliThemeKt {
    @JvmStatic fun BiliTheme(strategy: TplThemeStrategy, a: Any?, b: Any?, c: TplComposer, changed: Int, defaults: Int) = Unit
}
internal class TplBiliColors { fun `getBg1-0d7_KjU`(): Long = 0L }
internal class TplLayoutNodeDrawScope {
    fun `drawRect-n-J9OG0`(color: Long, topLeft: Long, size: Long, alpha: Float, style: Any?, filter: Any?, blendMode: Int) = Unit
}
internal class TplFragment {
    fun performViewCreated() = Unit
    fun getView(): View? = null
}
internal class TplDummy
internal open class TplItemDecoration
internal class TplCommentDetailLayer(@JvmField val decor: TplCommentDecoration? = null)
internal open class TplCommentBase
internal class TplCommentItem : TplCommentBase()
internal class TplObfuscatedSectionHeader : TplCommentBase()
internal class TplCommentDecoration : TplItemDecoration() {
    @Suppress("unused")
    private fun drawSection(c: Canvas, rv: TplDummy, child: View, a: TplCommentBase, b: TplCommentBase) = Unit
}
