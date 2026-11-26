package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common

import android.content.ComponentCallbacks2
import android.view.View
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import sun.misc.Unsafe
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy
import java.net.URLClassLoader

class HostResourceRegistryAndCacheTest {

    @Before
    fun setUp() {
        HostMemoryPressureBridge.resetForTests()
    }

    @After
    fun tearDown() {
        HostMemoryPressureBridge.resetForTests()
    }

    @Test
    fun registryLeasesAutoUnregisterAndDispatchBySeverityWithoutBreakingLegacyUiHiddenContract() {
        val observed = ArrayList<HostTrimSeverity>()
        var legacyModerate = 0
        var legacyCritical = 0
        HostMemoryPressureBridge.registerTrimCallbacks(
            onModerate = { legacyModerate++ },
            onCritical = { legacyCritical++ }
        )

        val lease = HostMemoryPressureBridge.registerResource("test-cache") { severity ->
            observed += severity
        }
        val sibling = HostResourceRegistry.register("sibling", onTrim = { })
        assertEquals(2, HostResourceRegistry.activeCount())

        // UI_HIDDEN 只驱动 HostResourceRegistry HIDDEN，绝不触发旧的 moderate/critical 回调。
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

        // 关闭租约需幂等并立即注销。
        lease.close()
        lease.close()
        assertEquals(1, HostResourceRegistry.activeCount())

        HostMemoryPressureBridge.dispatchModerate()
        assertEquals(3, observed.size)

        sibling.close()
        assertEquals(0, HostResourceRegistry.activeCount())
    }

    @Test
    fun registryIsolatesExceptionsAndSelfClosingDuringDispatch() {
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
        val tailLease = HostResourceRegistry.register("tail", onTrim = {
            log += "tail"
        })

        val count = HostResourceRegistry.dispatch(HostTrimSeverity.MODERATE)
        assertEquals(3, count)
        assertEquals(listOf("throwing", "self-closing", "tail"), log)
        assertEquals(2, HostResourceRegistry.activeCount())

        // 已被关闭的 selfClosingLease 不再收到下一轮裁剪。
        log.clear()
        HostResourceRegistry.dispatch(HostTrimSeverity.CRITICAL)
        assertEquals(listOf("throwing", "tail"), log)

        throwingLease.close()
        tailLease.close()
    }

    @Test
    fun boundedClassCacheEnforcesLruCapAndTrimSeveritiesAndClosesLease() {
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
            // 刷新 c1 为最新访问，插入 c4 应驱逐最久未使用的 c2。
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
        listOf(c1, c2, c3, c4, c5, c6, c7, c8).forEachIndexed { idx, cls ->
            cache.put(cls, "v$idx")
        }
        assertEquals(8, cache.size)

        @Suppress("DEPRECATION")
        HostMemoryPressureBridge.handleTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        assertEquals(4, cache.size)
        // 默认 50% 保留最新访问的 4 项（c5..c8）。
        assertNull(cache.get(c1))
        assertEquals("v7", cache.get(c8))

        HostMemoryPressureBridge.dispatchModerate()
        assertEquals(1, cache.size)

        HostMemoryPressureBridge.dispatchCritical()
        assertEquals(0, cache.size)

        cache.close()
        assertEquals(0, HostResourceRegistry.activeCount())
    }

    @Test
    fun boundedClassCacheSupportsNullableNegativeCachingAndGuardsInvalidValues() {
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

    @Test
    fun boundedClassCacheDoesNotStronglyRetainDynamicClassOrClassLoader() {
        val cache = BoundedClassCache<String>(maxEntries = 16, registerTrim = false)
        try {
            val (loaderRef, classRef) = populateWithDynamicProxyClass(cache)
            assertTrue(cache.size >= 1)

            // 触发 GC 与弱键清理；缓存只能弱持有 Class，不可阻止动态 ClassLoader 卸载。
            for (attempt in 0 until 20) {
                if (classRef.get() == null && loaderRef.get() == null) break
                System.gc()
                System.runFinalization()
                val sink = ArrayList<ByteArray>(32)
                repeat(8) { sink += ByteArray(64 * 1024) }
                Thread.sleep(25)
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

    @Suppress("DEPRECATION")
    private fun populateWithDynamicProxyClass(
        cache: BoundedClassCache<String>
    ): Pair<WeakReference<ClassLoader>, WeakReference<Class<*>>> {
        val loader = URLClassLoader(emptyArray(), javaClass.classLoader)
        val proxyClass = Proxy.getProxyClass(loader, Runnable::class.java)
        cache.put(proxyClass, "dynamic-marker")
        assertEquals("dynamic-marker", cache.get(proxyClass))
        return WeakReference<ClassLoader>(loader) to WeakReference<Class<*>>(proxyClass)
    }

    private class SampleTypeA
    private class SampleTypeB
    private class SampleTypeC
    private class SampleTypeD
    private class SampleTypeE
    private class SampleTypeF
    private class SampleTypeG
    private class SampleTypeH
}
