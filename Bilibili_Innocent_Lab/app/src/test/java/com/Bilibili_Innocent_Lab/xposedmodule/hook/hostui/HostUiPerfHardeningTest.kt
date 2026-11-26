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
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundDrawable
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostDetailOverlay
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostPageChrome
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostCardMaterialScope
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeColors
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeTheme
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostGarbTheme
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostLatestWorkQueue
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostMemoryPressureBridge
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostRecyclerTypes
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostSurfaceScope
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostChromeAppearance
import com.lumen.coacervation.engine.host.LumenSurfaceBackend
import com.lumen.coacervation.engine.host.LumenSurfaceBinding
import com.lumen.coacervation.engine.host.LumenSurfaceOptions
import com.lumen.coacervation.engine.host.LumenSurfaceSampling
import com.lumen.coacervation.engine.host.LumenSurfaceTarget
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
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
        repeat(25) {
            if (ref.get() == null) return
            System.gc()
            System.runFinalization()
            Thread.sleep(10)
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

    private class HostThemeClassLoader(parent: ClassLoader) : ClassLoader(parent) {
        private val templates = mapOf(
            "com.bilibili.lib.ui.util.NightTheme" to TplNightTheme::class.java,
            "com.bilibili.magicasakura.utils.ThemeUtils" to TplThemeUtils::class.java
        )
        private val hidden = setOf("com.bilibili.lib.ui.garb.GarbManager", "com.bilibili.lib.ui.garb.Garb")

        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(this) {
            if (name in hidden) throw ClassNotFoundException(name)
            val template = templates[name] ?: return@synchronized super.loadClass(name, resolve)
            val loaded = findLoadedClass(name) ?: run {
                val raw = requireNotNull(template.getResourceAsStream("/${template.name.replace('.', '/')}.class"))
                    .use { it.readBytes() }
                val rewritten = rewriteInternalName(raw, template.name.replace('.', '/'), name.replace('.', '/'))
                defineClass(name, rewritten, 0, rewritten.size)
            }
            if (resolve) resolveClass(loaded)
            loaded
        }

        private fun rewriteInternalName(raw: ByteArray, from: String, to: String): ByteArray {
            val input = DataInputStream(ByteArrayInputStream(raw))
            val outBuffer = ByteArrayOutputStream()
            val output = DataOutputStream(outBuffer)
            output.writeInt(input.readInt())
            output.writeShort(input.readUnsignedShort())
            output.writeShort(input.readUnsignedShort())
            val count = input.readUnsignedShort()
            output.writeShort(count)
            var index = 1
            while (index < count) {
                val tag = input.readUnsignedByte()
                output.writeByte(tag)
                when (tag) {
                    1 -> {
                        val value = input.readUTF()
                        output.writeUTF(value.replace(from, to))
                    }
                    7, 8, 16, 19, 20 -> output.writeShort(input.readUnsignedShort())
                    3, 4 -> output.writeInt(input.readInt())
                    5, 6 -> { output.writeLong(input.readLong()); index++ }
                    9, 10, 11, 12, 18 -> { output.writeShort(input.readUnsignedShort()); output.writeShort(input.readUnsignedShort()) }
                    15 -> { output.writeByte(input.readUnsignedByte()); output.writeShort(input.readUnsignedShort()) }
                    17 -> { output.writeShort(input.readUnsignedShort()); output.writeShort(input.readUnsignedShort()) }
                    else -> error("Unsupported constant pool tag: $tag")
                }
                index++
            }
            output.write(input.readBytes())
            return outBuffer.toByteArray()
        }
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
            loader = HostThemeClassLoader(HostUiPerfHardeningTest::class.java.classLoader!!)
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
    }
}
