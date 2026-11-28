package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common

import android.app.Application
import android.content.ComponentCallbacks
import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.drawable.Drawable
import android.util.SparseIntArray
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPages
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostPageChrome
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostWallpaperText
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostVideoCardStyle
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostRuntimeDiagnosticsBridge
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.MineComponentSnapshotHostBridge
import com.lumen.coacervation.engine.host.LumenSourceAvailability
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import sun.misc.Unsafe
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy
import java.net.URLClassLoader
import java.util.WeakHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class HostResourceRegistryAndCacheTest {

    @Before
    fun setUp() {
        HostMemoryPressureBridge.resetForTests()
    }

    @After
    fun tearDown() {
        HostMemoryPressureBridge.resetForTests()
        recordedAppCallbacks = null
        unregisteredAppCallbacks = null
    }

    @Test
    fun verifyResourceRegistryDispatchAndBridgeIntegration() {
        // registryLeasesAutoUnregisterAndDispatchBySeverityWithoutBreakingLegacyUiHiddenContract
        try {
            val observed = ArrayList<HostTrimSeverity>()
            var legacyModerate = 0
            var legacyCritical = 0
            HostMemoryPressureBridge.registerTrimCallbacks(
                onModerate = { legacyModerate++ },
                onCritical = { legacyCritical++ }
            )

            val lease = HostMemoryPressureBridge.registerResource("test-cache") { severity -> observed += severity }
            val sibling = HostResourceRegistry.register("sibling", onTrim = { })
            assertEquals(2, HostResourceRegistry.activeCount())

            @Suppress("DEPRECATION")
            HostMemoryPressureBridge.handleTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
            assertEquals(listOf(HostTrimSeverity.HIDDEN), observed)
            assertEquals(0, legacyModerate)
            assertEquals(0, legacyCritical)

            HostMemoryPressureBridge.dispatchModerate()
            HostMemoryPressureBridge.dispatchCritical()
            assertEquals(
                listOf(HostTrimSeverity.HIDDEN, HostTrimSeverity.MODERATE, HostTrimSeverity.CRITICAL),
                observed
            )

            lease.close()
            lease.close()
            assertEquals(1, HostResourceRegistry.activeCount())

            HostMemoryPressureBridge.dispatchModerate()
            assertEquals(3, observed.size)

            sibling.close()
            assertEquals(0, HostResourceRegistry.activeCount())
        } finally {
            HostMemoryPressureBridge.resetForTests()
        }

        // registryIsolatesExceptionsAndSelfClosingDuringDispatch
        run {
            val log = ArrayList<String>()
            lateinit var selfClosingLease: AutoCloseable
            val throwingLease = HostResourceRegistry.register("throwing", onTrim = {
                log += "throwing"
                error("simulated trim failure")
            })
            selfClosingLease = HostResourceRegistry.register("self-closing", onTrim = {
                log += "self-closing"
                selfClosingLease.close()
            })
            val tailLease = HostResourceRegistry.register("tail", onTrim = { log += "tail" })

            val count = HostResourceRegistry.dispatch(HostTrimSeverity.MODERATE)
            assertEquals(3, count)
            assertEquals(listOf("throwing", "self-closing", "tail"), log)
            assertEquals(2, HostResourceRegistry.activeCount())

            log.clear()
            HostResourceRegistry.dispatch(HostTrimSeverity.CRITICAL)
            assertEquals(listOf("throwing", "tail"), log)

            throwingLease.close()
            tailLease.close()
        }

        // installedBridgeDispatchesUiHiddenEvenWithActivePressureAdapterAndUnregistersOnReset
        try {
            val registered = ArrayList<ComponentCallbacks>()
            val unregistered = ArrayList<ComponentCallbacks>()
            val app = allocateRecordingApp(registered, unregistered)
            val observed = ArrayList<HostTrimSeverity>()
            var legacyModerate = 0
            var legacyCritical = 0

            HostMemoryPressureBridge.registerTrimCallbacks(
                onModerate = { legacyModerate++ },
                onCritical = { legacyCritical++ }
            )
            val cache = BoundedClassCache<String>(maxEntries = 4, tag = "ui-hidden-cache")
            listOf(
                SampleTypeA::class.java,
                SampleTypeB::class.java,
                SampleTypeC::class.java,
                SampleTypeD::class.java
            ).forEachIndexed { idx, cls -> cache.put(cls, "v$idx") }
            val lease = HostResourceRegistry.register("ui-hidden-observer", onTrim = { observed += it })

            try {
                HostMemoryPressureBridge.install(app)
                assertEquals(2, registered.size)

                @Suppress("DEPRECATION")
                registered.toList().forEach {
                    (it as ComponentCallbacks2).onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
                }
                assertEquals(listOf(HostTrimSeverity.HIDDEN), observed)
                assertEquals(0, legacyModerate)
                assertEquals(0, legacyCritical)
                assertEquals(2, cache.size)

                HostMemoryPressureBridge.resetForTests()
                assertTrue(registered.isEmpty())
                assertEquals(2, unregistered.size)
            } finally {
                lease.close()
                cache.close()
            }
        } finally {
            HostMemoryPressureBridge.resetForTests()
            recordedAppCallbacks = null
            unregisteredAppCallbacks = null
        }

        // bridgeExecutorsEnableCoreThreadTimeoutWithPositiveKeepAlive
        run {
            val executors = listOf(
                HostRuntimeDiagnosticsBridge::class.java.getDeclaredField("versionSignalExecutor")
                    .apply { isAccessible = true }.get(HostRuntimeDiagnosticsBridge) as ThreadPoolExecutor,
                HostRuntimeDiagnosticsBridge::class.java.getDeclaredField("persistenceExecutor")
                    .apply { isAccessible = true }.get(HostRuntimeDiagnosticsBridge) as ThreadPoolExecutor,
                MineComponentSnapshotHostBridge::class.java.getDeclaredField("persistenceExecutor")
                    .apply { isAccessible = true }.get(MineComponentSnapshotHostBridge) as ThreadPoolExecutor
            )
            for (executor in executors) {
                assertTrue("Bridge executor should allow core thread timeout", executor.allowsCoreThreadTimeOut())
                assertTrue("Bridge executor keepAliveTime must be > 0", executor.getKeepAliveTime(TimeUnit.MILLISECONDS) > 0L)
            }
        }
    }

    @Test
    fun verifyBoundedClassCacheSemanticsAndClassLoaderReclaim() {
        // boundedClassCacheEnforcesLruCapAndTrimSeveritiesAndClosesLease
        try {
            val c1 = SampleTypeA::class.java
            val c2 = SampleTypeB::class.java
            val c3 = SampleTypeC::class.java
            val c4 = SampleTypeD::class.java
            val c5 = SampleTypeE::class.java
            val c6 = SampleTypeF::class.java
            val c7 = SampleTypeG::class.java
            val c8 = SampleTypeH::class.java

            BoundedClassCache<String>(maxEntries = 3, registerTrim = false).use { lru ->
                lru.put(c1, "v1")
                lru.put(c2, "v2")
                lru.put(c3, "v3")
                assertEquals("v1", lru.get(c1))
                lru.put(c4, "v4")
                assertEquals(3, lru.size)
                assertNull(lru.get(c2))
                assertEquals("v1", lru.get(c1))
                assertEquals("v3", lru.get(c3))
                assertEquals("v4", lru.get(c4))
            }

            val cache = BoundedClassCache<String>(maxEntries = 8, tag = "bounded-test")
            assertEquals(1, HostResourceRegistry.activeCount())
            listOf(c1, c2, c3, c4, c5, c6, c7, c8).forEachIndexed { idx, cls -> cache.put(cls, "v$idx") }
            assertEquals(8, cache.size)

            @Suppress("DEPRECATION")
            HostMemoryPressureBridge.handleTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
            assertEquals(4, cache.size)
            assertNull(cache.get(c1))
            assertEquals("v7", cache.get(c8))

            HostMemoryPressureBridge.dispatchModerate()
            assertEquals(1, cache.size)

            HostMemoryPressureBridge.dispatchCritical()
            assertEquals(0, cache.size)

            cache.close()
            assertEquals(0, HostResourceRegistry.activeCount())
        } finally {
            HostMemoryPressureBridge.resetForTests()
        }

        // boundedClassCacheSupportsNullableNegativeCachingAndGuardsInvalidValues
        run {
            BoundedClassCache<String>(maxEntries = 8, registerTrim = false).use { cache ->
                var computeCount = 0
                val first = cache.getOrPutNullable(SampleTypeA::class.java) {
                    computeCount++
                    null
                }
                val second = cache.getOrPutNullable(SampleTypeA::class.java) {
                    computeCount++
                    "should-not-run"
                }
                assertNull(first)
                assertNull(second)
                assertEquals(1, computeCount)
                assertTrue(cache.containsKey(SampleTypeA::class.java))
            }

            BoundedClassCache<Any>(maxEntries = 4, registerTrim = false).use { rawCache ->
                assertThrows(IllegalArgumentException::class.java) {
                    rawCache.put(SampleTypeA::class.java, URLClassLoader(emptyArray(), javaClass.classLoader))
                }
                val unsafeField = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
                val unsafe = unsafeField.get(null) as Unsafe
                val fakeView = unsafe.allocateInstance(View::class.java) as View
                assertThrows(IllegalArgumentException::class.java) {
                    rawCache.put(SampleTypeB::class.java, fakeView)
                }
            }
        }

        // boundedClassCacheDoesNotStronglyRetainDynamicClassOrClassLoader
        run {
            val cache = BoundedClassCache<String>(maxEntries = 16, registerTrim = false)
            try {
                val queue = ReferenceQueue<Any>()
                val (loaderRef, classRef) = populateWithDynamicProxyClass(cache, queue)
                assertTrue(cache.size >= 1)

                for (attempt in 0 until 20) {
                    if (classRef.get() == null && loaderRef.get() == null) break
                    System.gc()
                    System.runFinalization()
                    val sink = ArrayList<ByteArray>(8)
                    repeat(4) { sink += ByteArray(32 * 1024) }
                    queue.remove(5L)
                    cache.expungeStale()
                }
                cache.expungeStale()
                assertNull("WeakClassKey must not retain dynamic Class", classRef.get())
                assertNull("WeakClassKey must not retain dynamic ClassLoader", loaderRef.get())
                assertEquals(0, cache.size)
            } finally {
                cache.close()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun populateWithDynamicProxyClass(
        cache: BoundedClassCache<String>,
        queue: ReferenceQueue<Any>
    ): Pair<WeakReference<ClassLoader>, WeakReference<Class<*>>> {
        val loader = URLClassLoader(emptyArray(), javaClass.classLoader)
        val proxyClass = Proxy.getProxyClass(loader, Runnable::class.java)
        cache.put(proxyClass, "dynamic-marker")
        assertEquals("dynamic-marker", cache.get(proxyClass))
        return WeakReference<ClassLoader>(loader, queue) to WeakReference<Class<*>>(proxyClass, queue)
    }

    @Test
    fun hostUiWeakReferenceHoldersBreakValueToKeyLeakCycles() {
        // 1. HostChromeContentSource: coordinateView 弱引用不滞留宿主 View。
        for (field in HostChromeContentSource::class.java.declaredFields) {
            assertFalse("HostChromeContentSource must not retain View strongly: ${field.name}",
                View::class.java.isAssignableFrom(field.type))
        }
        val (contentSource, contentViewRef) = createCollectedContentSourceSample()
        awaitGc(contentViewRef)
        assertTrue("HostChromeContentSource must allow coordinateView collection", contentViewRef.get() == null)
        assertNull(contentSource.coordinateViewOrNull)
        assertEquals(LumenSourceAvailability.TEMPORARILY_UNAVAILABLE, contentSource.availability)

        // 2. HostPageChrome: statusBand 弱引用不反向钉住宿主根容器。
        for (field in HostPageChrome::class.java.declaredFields) {
            assertFalse("HostPageChrome must not retain View strongly: ${field.name}",
                View::class.java.isAssignableFrom(field.type))
        }
        val (chromeKeeper, bandRef, pageRootRef) = createPageChromeSample()
        awaitGc(bandRef)
        awaitGc(pageRootRef)
        assertTrue("HostPageChrome must not retain statusBand strongly", bandRef.get() == null)
        assertTrue("HostPageChrome must not retain root via statusBand", pageRootRef.get() == null)
        assertNotNull(chromeKeeper)

        // 3. HostSurfaceScope$Surface & HostPrismSurface: prismSurface 弱引用 + detach() 显式清理。
        val surfaceClass = Class.forName("com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostSurfaceScope\$Surface")
        for (field in surfaceClass.declaredFields) {
            assertFalse("Surface must not retain HostPrismSurface strongly: ${field.name}",
                HostPrismSurface::class.java.isAssignableFrom(field.type))
        }
        val (scopeMap, surfaceViewRef, prismRef) = createSurfaceScopeSample(surfaceClass)
        awaitGc(surfaceViewRef)
        awaitGc(prismRef)
        assertNull("Surface.prismSurface must not retain HostPrismSurface/View", surfaceViewRef.get())
        assertNull(prismRef.get())
        assertTrue(scopeMap.isEmpty())

        val liveScope = HostSurfaceScope(alloc(StubView::class.java), HostChromeColors(dark = false, accent = 0xFF336699.toInt()))
        val liveView = alloc(StubView::class.java)
        val attachedPrism = alloc(HostPrismSurface::class.java).also {
            HostPrismSurface::class.java.getDeclaredField("closed")
                .apply { isAccessible = true }.setBoolean(it, true)
        }
        val liveSurface = alloc(surfaceClass).also {
            surfaceClass.getDeclaredMethod("setPrismSurface", HostPrismSurface::class.java)
                .apply { isAccessible = true }.invoke(it, attachedPrism)
        }
        @Suppress("UNCHECKED_CAST")
        val liveSurfaces = HostSurfaceScope::class.java.getDeclaredField("surfaces")
            .apply { isAccessible = true }.get(liveScope) as WeakHashMap<View, Any>
        liveSurfaces[liveView] = liveSurface
        liveScope.detach()
        assertNull(surfaceClass.getDeclaredMethod("prismSurface").apply { isAccessible = true }.invoke(liveSurface))
        liveScope.close()

        // 4. HostVideoCardStyle$State: surface 弱引用不钉住 View 且在失效/trim 时清理。
        val cardStateClass = Class.forName("com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostVideoCardStyle\$State")
        for (field in cardStateClass.declaredFields) {
            assertFalse("HostVideoCardStyle.State must not retain Drawable strongly: ${field.name}",
                Drawable::class.java.isAssignableFrom(field.type))
        }
        val (cardStates, cardViewRef) = createCardStyleGcSample(cardStateClass)
        val retainedView = alloc(StubView::class.java).apply { attachedFlag = false }
        val (retainedStateObj, retainedDrawableRef) = createRetainedCardState(cardStateClass)
        val retainedStatesMap = WeakHashMap<View, Any>().apply { put(retainedView, retainedStateObj) }
        val cardStyle = alloc(HostVideoCardStyle::class.java).also {
            HostVideoCardStyle::class.java.getDeclaredField("states")
                .apply { isAccessible = true }.set(it, retainedStatesMap)
            HostVideoCardStyle::class.java.getDeclaredField("listWatchers")
                .apply { isAccessible = true }.set(it, WeakHashMap<ViewGroup, Any>())
            HostVideoCardStyle::class.java.getDeclaredField("resourceKinds")
                .apply { isAccessible = true }.set(it, alloc(StubSparseIntArray::class.java))
            HostVideoCardStyle::class.java.getDeclaredField("recyclerTypes")
                .apply { isAccessible = true }.set(it, HostRecyclerTypes(maxDepth = 4))
        }
        awaitGc(cardViewRef)
        awaitGc(retainedDrawableRef)
        assertTrue("State.surface must not pin card View", cardViewRef.get() == null)
        assertTrue("Retained surface Drawable should be reclaimable", retainedDrawableRef.get() == null)
        // 触发 WeakHashMap 清理并验证 trimCaches 安全执行且清空已回收状态。
        assertEquals(0, cardStates.size)
        cardStyle.trimCaches()
        assertNull(cardStateClass.getDeclaredMethod("surface").apply { isAccessible = true }.invoke(retainedStateObj))

        // 5. HostBackgroundPages: detailBackgrounds 弱值、materialFills 接入 HostResourceRegistry、dispose() 移出 pages。
        val weakMapClass = Class.forName("com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPages\$WeakDrawableMap")
        @Suppress("UNCHECKED_CAST")
        val ownedMap = weakMapClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance() as MutableMap<View, Drawable>
        val bgViewRef = populateWeakDrawableMap(ownedMap)
        awaitGc(bgViewRef)
        assertTrue("WeakDrawableMap must not retain View via Drawable", bgViewRef.get() == null)
        assertTrue(ownedMap.isEmpty())

        val pageClass = Class.forName("com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPages\$Page")
        val pagesMap = WeakHashMap<ViewGroup, Any>()
        val materialCache = BoundedClassCache.forReflection<Any>(maxEntries = 32, tag = "host.background.material_fills")
        assertEquals(1, HostResourceRegistry.activeCount())
        val pagesHost = alloc(HostBackgroundPages::class.java).also {
            HostBackgroundPages::class.java.getDeclaredField("pages").apply { isAccessible = true }.set(it, pagesMap)
            HostBackgroundPages::class.java.getDeclaredField("detailBackgrounds").apply { isAccessible = true }.set(it, ownedMap)
            HostBackgroundPages::class.java.getDeclaredField("materialFills").apply { isAccessible = true }.set(it, materialCache)
            HostBackgroundPages::class.java.getDeclaredField("recyclerTypes").apply { isAccessible = true }.set(it, HostRecyclerTypes(maxDepth = 4))
        }
        val disposedRoot = alloc(StubViewGroup::class.java)
        val pageRecord = alloc(pageClass).also {
            pageClass.declaredFields.first { field -> field.type == HostBackgroundPages::class.java }
                .apply { isAccessible = true }.set(it, pagesHost)
            pageClass.getDeclaredField("root").apply { isAccessible = true }.set(it, WeakReference(disposedRoot))
            pageClass.getDeclaredField("textContrast").apply { isAccessible = true }.set(it, HostWallpaperText())
            pageClass.getDeclaredField("positions").apply { isAccessible = true }.set(it, WeakHashMap<ViewGroup, Any>())
            pageClass.getDeclaredField("containers").apply { isAccessible = true }.set(it, emptyList<Any>())
            pageClass.getDeclaredField("lists").apply { isAccessible = true }.set(it, emptyList<Any>())
            pageClass.getDeclaredField("texts").apply { isAccessible = true }.set(it, emptyList<Any>())
            pageClass.getDeclaredField("windowBackground").apply { isAccessible = true }.set(it, WeakReference<Drawable>(null))
        }
        pagesMap[disposedRoot] = pageRecord
        pageClass.getDeclaredMethod("dispose").apply { isAccessible = true }.invoke(pageRecord)
        assertTrue("Page.dispose() must remove entry from pages", pagesMap.isEmpty())

        pagesMap[disposedRoot] = pageRecord
        pagesHost.dispose()
        assertTrue("HostBackgroundPages.dispose() must clear pages", pagesMap.isEmpty())
        assertEquals("HostBackgroundPages.dispose() must release materialFills registry lease", 0, HostResourceRegistry.activeCount())
    }

    private fun createCollectedContentSourceSample(): Pair<HostChromeContentSource, WeakReference<StubView>> {
        val contentView = alloc(StubView::class.java)
        val contentSource = alloc(HostChromeContentSource::class.java).also {
            HostChromeContentSource::class.java.getDeclaredField("coordinateViewRef")
                .apply { isAccessible = true }.set(it, WeakReference(contentView))
        }
        assertEquals(LumenSourceAvailability.READY, contentSource.availability)
        return contentSource to WeakReference(contentView)
    }

    private fun createPageChromeSample(): Triple<HostPageChrome, WeakReference<StubView>, WeakReference<StubViewGroup>> {
        val bandView = alloc(StubView::class.java)
        val pageRoot = alloc(StubViewGroup::class.java)
        bandView.pinnedOwner = pageRoot
        val chrome = alloc(HostPageChrome::class.java).also {
            HostPageChrome::class.java.getDeclaredField("root")
                .apply { isAccessible = true }.set(it, WeakReference(pageRoot))
            HostPageChrome::class.java.getDeclaredField("statusBand")
                .apply { isAccessible = true }.set(it, WeakReference(bandView))
        }
        return Triple(chrome, WeakReference(bandView), WeakReference(pageRoot))
    }

    private fun createSurfaceScopeSample(surfaceClass: Class<*>): Triple<WeakHashMap<View, Any>, WeakReference<StubView>, WeakReference<HostPrismSurface>> {
        val surfaceView = alloc(StubView::class.java)
        val prismDrawable = alloc(RetainingDrawable::class.java).apply { pinnedView = surfaceView }
        val prism = alloc(HostPrismSurface::class.java).also {
            HostPrismSurface::class.java.getDeclaredField("original")
                .apply { isAccessible = true }.set(it, prismDrawable)
            HostPrismSurface::class.java.getDeclaredField("closed")
                .apply { isAccessible = true }.setBoolean(it, true)
        }
        val surfaceRecord = alloc(surfaceClass).also {
            surfaceClass.getDeclaredMethod("setPrismSurface", HostPrismSurface::class.java)
                .apply { isAccessible = true }.invoke(it, prism)
        }
        val scopeMap = WeakHashMap<View, Any>().apply { put(surfaceView, surfaceRecord) }
        return Triple(scopeMap, WeakReference(surfaceView), WeakReference(prism))
    }

    private fun createCardStyleGcSample(cardStateClass: Class<*>): Pair<WeakHashMap<View, Any>, WeakReference<StubView>> {
        val cardView = alloc(StubView::class.java).apply { attachedFlag = false }
        val cardDrawable = alloc(RetainingDrawable::class.java).apply { pinnedView = cardView }
        val cardState = createCardState(cardStateClass, cardDrawable)
        val states = WeakHashMap<View, Any>().apply { put(cardView, cardState) }
        return states to WeakReference(cardView)
    }

    private fun createRetainedCardState(cardStateClass: Class<*>): Pair<Any, WeakReference<Drawable>> {
        val drawable = alloc(RetainingDrawable::class.java)
        val state = createCardState(cardStateClass, drawable)
        return state to WeakReference(drawable)
    }

    private fun createCardState(cardStateClass: Class<*>, drawable: Drawable?): Any {
        return alloc(cardStateClass).also {
            cardStateClass.getDeclaredField("decorated").apply { isAccessible = true }.setBoolean(it, true)
            cardStateClass.getDeclaredMethod("setSurface", Drawable::class.java)
                .apply { isAccessible = true }.invoke(it, drawable)
        }
    }

    private fun populateWeakDrawableMap(ownedMap: MutableMap<View, Drawable>): WeakReference<StubView> {
        val bgView = alloc(StubView::class.java)
        val bgDrawable = alloc(RetainingDrawable::class.java).apply { pinnedView = bgView }
        ownedMap[bgView] = bgDrawable
        return WeakReference(bgView)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> alloc(clazz: Class<T>): T = unsafe.allocateInstance(clazz) as T

    private fun awaitGc(ref: WeakReference<*>) {
        for (attempt in 0 until 30) {
            if (ref.get() == null) return
            System.gc()
            System.runFinalization()
            @Suppress("UNUSED_VARIABLE")
            val pressure = ByteArray(32 * 1024)
            Thread.sleep(5)
        }
    }

    private fun allocateRecordingApp(
        registered: MutableList<ComponentCallbacks>,
        unregistered: MutableList<ComponentCallbacks>
    ): Application {
        recordedAppCallbacks = registered
        unregisteredAppCallbacks = unregistered
        return alloc(RecordingApplication::class.java)
    }

    private open class RecordingApplication : Application() {
        override fun getApplicationContext(): Context = this

        override fun registerComponentCallbacks(callback: ComponentCallbacks) {
            recordedAppCallbacks?.add(callback)
        }

        override fun unregisterComponentCallbacks(callback: ComponentCallbacks) {
            recordedAppCallbacks?.remove(callback)
            unregisteredAppCallbacks?.add(callback)
        }
    }

    private open class StubView : View(null) {
        var attachedFlag: Boolean = true
        var shownFlag: Boolean = true
        var pinnedOwner: View? = null

        override fun isAttachedToWindow(): Boolean = attachedFlag
        override fun isShown(): Boolean = shownFlag
        override fun getViewTreeObserver(): ViewTreeObserver? = null
        override fun addOnAttachStateChangeListener(listener: OnAttachStateChangeListener?) = Unit
        override fun removeOnAttachStateChangeListener(listener: OnAttachStateChangeListener?) = Unit
    }

    private open class StubViewGroup : ViewGroup(null) {
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) = Unit
        override fun addOnAttachStateChangeListener(listener: OnAttachStateChangeListener?) = Unit
        override fun removeOnAttachStateChangeListener(listener: OnAttachStateChangeListener?) = Unit
    }

    private open class StubSparseIntArray : SparseIntArray() {
        override fun clear() = Unit
        override fun size(): Int = 0
        override fun get(key: Int, valueIfKeyNotFound: Int): Int = valueIfKeyNotFound
        override fun put(key: Int, value: Int) = Unit
    }

    private open class RetainingDrawable(var pinnedView: View?) : Drawable() {
        override fun draw(canvas: Canvas) = Unit
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = 0
    }

    private class SampleTypeA
    private class SampleTypeB
    private class SampleTypeC
    private class SampleTypeD
    private class SampleTypeE
    private class SampleTypeF
    private class SampleTypeG
    private class SampleTypeH

    private companion object {
        val unsafe: Unsafe = Unsafe::class.java.getDeclaredField("theUnsafe")
            .apply { isAccessible = true }.get(null) as Unsafe
        var recordedAppCallbacks: MutableList<ComponentCallbacks>? = null
        var unregisteredAppCallbacks: MutableList<ComponentCallbacks>? = null
    }
}
