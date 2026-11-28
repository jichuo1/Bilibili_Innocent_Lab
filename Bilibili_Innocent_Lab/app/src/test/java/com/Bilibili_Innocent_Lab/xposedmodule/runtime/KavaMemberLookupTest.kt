package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import io.github.jichuo1.meguviz.ref.scope.CodeScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KavaMemberLookupTest {

    @Test
    fun `batch scopes close on exceptions and never cross threads or class loaders`() {
        org.junit.Assume.assumeFalse(BuildConfig.MEGUVIZ_LEGACY_LOOKUP)
        resetLookupCaches()
        val loader = javaClass.classLoader!!
        val other = object : ClassLoader(loader) {}
        KavaMemberLookup.withLookupBatch(loader) {
            KavaMemberLookup.withMemberScope(loader) { first ->
                KavaMemberLookup.withLookupBatch(loader) {
                    KavaMemberLookup.withMemberScope(loader) { assertSame(first, it) }
                }
                lateinit var second: CodeScope
                org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                    KavaMemberLookup.withLookupBatch(other) {
                        KavaMemberLookup.withMemberScope(other) { second = it }
                        error("interrupted batch")
                    }
                }
                assertEquals(CodeScope.State.CLOSED, second.state())
                KavaMemberLookup.withMemberScope(loader) { assertSame(first, it) }
                val thread = java.util.concurrent.FutureTask {
                    KavaMemberLookup.withMemberScope(loader) { it }
                }
                Thread(thread).start()
                val isolated = thread.get(5, java.util.concurrent.TimeUnit.SECONDS)
                assertEquals(CodeScope.State.CLOSED, isolated.state())
                assertEquals(CodeScope.State.OPEN, first.state())
                first
            }
        }.also { assertEquals(CodeScope.State.CLOSED, it.state()) }
    }

    private open class OverloadedBase {
        private fun inherited() = "zero"
        private fun inherited(value: Int) = value
        private fun onlyWithArgument(value: Int) = value
    }

    private class Overloaded : OverloadedBase {
        constructor() : super()
        constructor(@Suppress("UNUSED_PARAMETER") value: Int) : super()
        private fun read() = "zero"
        private fun read(value: Int) = value
        private fun onlyWithArgument(value: Int) = value
    }

    fun `zero parameter constructor lookup is exact even with overloaded constructors`() {
        val constructor = requireNotNull(KavaMemberLookup.constructorOrNull(Overloaded::class.java))
        assertEquals(0, constructor.parameterCount)
        assertTrue(constructor.newInstance() is Overloaded)
        assertNull(KavaMemberLookup.constructorOrNull(HiddenMethods::class.java))
        assertEquals(1, KavaMemberLookup.constructorOrNull(Overloaded::class.java, Integer.TYPE)?.parameterCount)
    }

    fun `zero parameter method lookup rejects argument only overloads`() {
        val method = requireNotNull(KavaMemberLookup.methodOrNull(Overloaded::class.java, "read"))
        assertEquals(0, method.parameterCount)
        assertEquals("zero", method.invoke(Overloaded()))
        assertNull(KavaMemberLookup.methodOrNull(Overloaded::class.java, "onlyWithArgument"))
        assertEquals(1, KavaMemberLookup.methodOrNull(Overloaded::class.java, "read", Integer.TYPE)?.parameterCount)
    }

    fun `inherited zero parameter lookup stays exact and caches absent overloads`() {
        repeat(2) {
            val method = requireNotNull(KavaMemberLookup.inheritedMethodOrNull(Overloaded::class.java, "inherited"))
            assertEquals(0, method.parameterCount)
            assertEquals("zero", method.invoke(Overloaded()))
            assertNull(KavaMemberLookup.inheritedMethodOrNull(Overloaded::class.java, "onlyWithArgument"))
        }
    }

    @Before
    fun resetLookupCaches() {
        KavaMemberLookup.resetForTests()
    }

    private open class HiddenGrandBase {
        @Suppress("unused")
        private fun inheritedBuild() = "built"
    }

    private open class HiddenBase : HiddenGrandBase() {
        @Suppress("unused")
        private val inheritedText = "base"

        @Suppress("unused")
        private fun inheritedFlag(value: Boolean) = value
    }

    private class HiddenMethods private constructor(private val seed: Int) : HiddenBase() {
        private var received = false

        private fun acceptFlag(value: Boolean) {
            received = value
        }

        @Suppress("unused")
        private fun acceptFlag(value: String) = value

        fun receivedFlag() = received

        fun seed() = seed

        companion object {
            fun create() = HiddenMethods(0)
        }
    }

    fun `resolves exact private method and exposes raw member`() {
        val method = KavaMemberLookup.methodOrNull(
            HiddenMethods::class.java,
            "acceptFlag",
            java.lang.Boolean.TYPE
        )
        val target = HiddenMethods.create()

        assertSame(HiddenMethods::class.java, method?.declaringClass)
        method?.invoke(target, true)
        assertEquals(true, target.receivedFlag())
    }

    fun `returns null for expected version drift`() {
        assertNull(
            KavaMemberLookup.methodOrNull(
                HiddenMethods::class.java,
                "missingMethod",
                java.lang.Boolean.TYPE
            )
        )
    }

    private open class OverrideBase {
        open fun overridden() = "base"
    }

    private class OverrideChild : OverrideBase() {
        override fun overridden() = "child"
    }

    fun `inherited lookup returns the most derived override`() {
        val method = requireNotNull(KavaMemberLookup.inheritedMethodOrNull(OverrideChild::class.java, "overridden"))

        assertEquals(OverrideChild::class.java, method.declaringClass)
    }

    fun `resolves inherited method and field`() {
        val target = HiddenMethods.create()
        val method = KavaMemberLookup.inheritedMethodOrNull(
            HiddenMethods::class.java,
            "inheritedFlag",
            java.lang.Boolean.TYPE
        )
        val field = KavaMemberLookup.fieldOrNull(
            HiddenMethods::class.java,
            "inheritedText",
            includeSuperclasses = true
        )

        assertEquals(true, method?.invoke(target, true))
        assertEquals("base", field?.get(target))
    }

    fun `enumerates inherited fields through cached Kava boundary`() {
        val fields = KavaMemberLookup.fields(
            HiddenMethods::class.java,
            includeSuperclasses = true,
            makeAccessible = true
        ) { it.name == "inheritedText" }

        assertEquals(1, fields.size)
        assertEquals("base", fields.single().get(HiddenMethods.create()))
    }

    fun `enumerates methods across the complete superclass chain`() {
        val methods = KavaMemberLookup.methods(
            HiddenMethods::class.java,
            includeSuperclasses = true,
            makeAccessible = true
        ) { it.name == "inheritedBuild" && it.parameterCount == 0 }

        assertEquals(1, methods.size)
        assertEquals("built", methods.single().invoke(HiddenMethods.create()))
        assertSame(HiddenGrandBase::class.java, methods.single().declaringClass)
    }

    @Suppress("DEPRECATION")
    fun `resolves private constructor and declared member collections`() {
        val constructor = KavaMemberLookup.constructorOrNull(
            HiddenMethods::class.java,
            Integer.TYPE
        )
        val target = constructor?.newInstance(7) as? HiddenMethods
        val methods = KavaMemberLookup.declaredMethods(HiddenMethods::class.java) {
            it.name == "acceptFlag"
        }
        val fields = KavaMemberLookup.declaredFields(HiddenMethods::class.java) {
            it.name == "received"
        }

        assertEquals(7, target?.seed())
        assertEquals(2, methods.size)
        assertEquals(1, fields.size)
        assertTrue(methods.none { it.isAccessible })
        assertTrue(fields.none { it.isAccessible })
    }

    fun `loads classes through explicit class loader`() {
        val loader = requireNotNull(HiddenMethods::class.java.classLoader)

        assertSame(
            HiddenMethods::class.java,
            KavaMemberLookup.classOrNull(loader, HiddenMethods::class.java.name)
        )
        assertEquals(true, KavaMemberLookup.hasClass(loader, HiddenMethods::class.java.name))
        assertNull(KavaMemberLookup.classOrNull(loader, "missing.Type"))
    }

    fun `caches positive and negative lookups and exposes diagnostics`() {
        val loader = requireNotNull(HiddenMethods::class.java.classLoader)

        repeat(2) {
            assertSame(
                HiddenMethods::class.java,
                KavaMemberLookup.classOrNull(loader, HiddenMethods::class.java.name)
            )
            assertNull(KavaMemberLookup.classOrNull(loader, "missing.CachedType"))
            assertNull(
                KavaMemberLookup.methodOrNull(
                    HiddenMethods::class.java,
                    "missingCachedMethod"
                )
            )
        }

        val diagnostics = KavaMemberLookup.diagnostics()
        assertTrue(diagnostics.cacheHits >= 3)
        assertTrue(diagnostics.cacheMisses >= 3)
        assertEquals(2, diagnostics.cachedClasses)
        assertEquals(1, diagnostics.cachedMethods)
        assertEquals(0, diagnostics.lookupFailures)
    }

    fun `cancelled member lookup does not poison the stable cache`() {
        Thread.currentThread().interrupt()
        try {
            assertNull(KavaMemberLookup.methodOrNull(HiddenMethods::class.java, "seed"))
            assertTrue(KavaMemberLookup.declaredMethods(HiddenMethods::class.java).isEmpty())
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(0, KavaMemberLookup.diagnostics().cachedMethods)
            assertEquals(0, KavaMemberLookup.diagnostics().cachedMemberCollections)
        } finally {
            Thread.interrupted()
        }
        assertEquals("seed", KavaMemberLookup.methodOrNull(HiddenMethods::class.java, "seed")?.name)
        assertTrue(KavaMemberLookup.declaredMethods(HiddenMethods::class.java).isNotEmpty())
    }

    fun `transient loader exception does not permanently poison positive lookup cache`() {
        var failNext = true
        val flakyLoader = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == HiddenMethods::class.java.name && failNext) {
                    failNext = false
                    throw IllegalStateException("transient dex fault")
                }
                return super.loadClass(name, resolve)
            }
        }
        assertNull(KavaMemberLookup.classOrNull(flakyLoader, HiddenMethods::class.java.name))
        assertEquals(1L, KavaMemberLookup.diagnostics().lookupFailures)
        assertSame(HiddenMethods::class.java, KavaMemberLookup.classOrNull(flakyLoader, HiddenMethods::class.java.name))
        assertSame(HiddenMethods::class.java, KavaMemberLookup.classOrNull(flakyLoader, HiddenMethods::class.java.name))
        assertTrue(KavaMemberLookup.diagnostics().cacheHits >= 1L)
    }

    fun `superclass collections reuse cached declared class collections and batch boot scope`() {
        val loader = requireNotNull(HiddenMethods::class.java.classLoader)
        KavaMemberLookup.withLookupBatch(loader) {
            val baseMethods = KavaMemberLookup.declaredMethods(HiddenBase::class.java)
            assertTrue(baseMethods.isNotEmpty())
            val beforeChildMisses = KavaMemberLookup.diagnostics().cacheMisses
            val childInherited = KavaMemberLookup.methods(HiddenMethods::class.java, includeSuperclasses = true)
            assertTrue(childInherited.any { it.declaringClass == HiddenBase::class.java })
            assertTrue(childInherited.any { it.declaringClass == Any::class.java })
            val afterChildMisses = KavaMemberLookup.diagnostics().cacheMisses
            assertEquals("only (HiddenMethods, true) outer collection lookup counts as a miss", beforeChildMisses + 1, afterChildMisses)
            assertTrue(KavaMemberLookup.fields(HiddenMethods::class.java, includeSuperclasses = true).any { it.name == "inheritedText" })
        }
    }


    private class DynamicTargetFixture {
        fun ping(): String = "pong"
    }

    private class TestDynamicPluginClassLoader(parent: ClassLoader) : ClassLoader(parent) {
        fun defineFixture(name: String, bytes: ByteArray): Class<*> = defineClass(name, bytes, 0, bytes.size)
    }

    private fun exerciseDynamicLoaderAndCreateRef(
        refQueue: java.lang.ref.ReferenceQueue<ClassLoader>
    ): java.lang.ref.WeakReference<ClassLoader> {
        val parent = requireNotNull(DynamicTargetFixture::class.java.classLoader)
        val resource = DynamicTargetFixture::class.java.name.replace('.', '/') + ".class"
        val bytes = requireNotNull(parent.getResourceAsStream(resource)).use { it.readBytes() }
        val dynamicLoader = TestDynamicPluginClassLoader(parent)
        val definedClass = dynamicLoader.defineFixture(DynamicTargetFixture::class.java.name, bytes)
        assertSame(dynamicLoader, definedClass.classLoader)
        assertFalse(KavaMemberLookup.isCacheableLoader(dynamicLoader))
        assertSame(definedClass, KavaMemberLookup.classOrNull(dynamicLoader, DynamicTargetFixture::class.java.name))
        assertEquals("ping", KavaMemberLookup.methodOrNull(definedClass, "ping")?.name)
        assertTrue(KavaMemberLookup.declaredMethods(definedClass).any { it.name == "ping" })
        return java.lang.ref.WeakReference(dynamicLoader, refQueue)
    }

    fun `registered host loader uses volatile fast path and keeps third party child loaders out of cache`() {
        val baseLoader = requireNotNull(HiddenMethods::class.java.classLoader)
        val hostLoader = object : ClassLoader(baseLoader) {}
        val thirdPartyChild = object : ClassLoader(hostLoader) {}

        KavaMemberLookup.registerHostClassLoader(hostLoader)
        assertTrue(KavaMemberLookup.isCacheableLoader(hostLoader))
        assertFalse(KavaMemberLookup.isCacheableLoader(thirdPartyChild))

        assertSame(HiddenMethods::class.java, KavaMemberLookup.classOrNull(hostLoader, HiddenMethods::class.java.name))
        assertSame(HiddenMethods::class.java, KavaMemberLookup.classOrNull(hostLoader, HiddenMethods::class.java.name))
        val hitsAfterHost = KavaMemberLookup.diagnostics().cacheHits
        val cachedAfterHost = KavaMemberLookup.diagnostics().cachedClasses
        assertTrue(hitsAfterHost >= 1L)
        assertEquals(1, cachedAfterHost)

        // 第三方子 ClassLoader 不进入全局缓存，也不驱逐宿主快路径命中收益。
        assertSame(HiddenMethods::class.java, KavaMemberLookup.classOrNull(thirdPartyChild, HiddenMethods::class.java.name))
        assertEquals(cachedAfterHost, KavaMemberLookup.diagnostics().cachedClasses)
        assertSame(HiddenMethods::class.java, KavaMemberLookup.classOrNull(hostLoader, HiddenMethods::class.java.name))
        assertEquals(hitsAfterHost + 1L, KavaMemberLookup.diagnostics().cacheHits)
    }

    fun `isolated and url class loaders bypass persistent loaderCaches and remain collectable`() {
        val urlLoader = java.net.URLClassLoader(emptyArray(), javaClass.classLoader)
        try {
            assertFalse(KavaMemberLookup.isCacheableLoader(urlLoader))
            assertSame(HiddenMethods::class.java, KavaMemberLookup.classOrNull(urlLoader, HiddenMethods::class.java.name))
            assertEquals(0, KavaMemberLookup.diagnostics().cachedClasses)
        } finally {
            urlLoader.close()
        }

        val refQueue = java.lang.ref.ReferenceQueue<ClassLoader>()
        val dynamicRef = exerciseDynamicLoaderAndCreateRef(refQueue)
        val diagnostics = KavaMemberLookup.diagnostics()
        assertEquals(0, diagnostics.cachedClasses)
        assertEquals(0, diagnostics.cachedMethods)
        assertEquals(0, diagnostics.cachedMemberCollections)

        var collected = dynamicRef.get() == null
        val deadlineNs = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while (!collected && System.nanoTime() < deadlineNs) {
            System.gc()
            @Suppress("DEPRECATION")
            System.runFinalization()
            if (refQueue.remove(20L) === dynamicRef || dynamicRef.get() == null) {
                collected = true
                break
            }
        }
        assertTrue("dynamic ClassLoader should not be pinned by Value -> Key cycle in loaderCaches", collected)
    }

    fun `negative cache separation preserves positive hits when clearNegativeCaches runs`() {
        val loader = requireNotNull(HiddenMethods::class.java.classLoader)

        assertSame(HiddenMethods::class.java, KavaMemberLookup.classOrNull(loader, HiddenMethods::class.java.name))
        assertEquals("seed", KavaMemberLookup.methodOrNull(HiddenMethods::class.java, "seed")?.name)
        assertNull(KavaMemberLookup.classOrNull(loader, "missing.PatchClass"))
        assertNull(KavaMemberLookup.methodOrNull(HiddenMethods::class.java, "missingPatchMethod"))
        assertNull(KavaMemberLookup.fieldOrNull(HiddenMethods::class.java, "missingPatchField"))
        assertNull(KavaMemberLookup.constructorOrNull(HiddenMethods::class.java, String::class.java, Int::class.java))

        val beforeClear = KavaMemberLookup.diagnostics()
        assertEquals(2, beforeClear.cachedClasses)
        assertEquals(2, beforeClear.cachedMethods)
        assertEquals(1, beforeClear.cachedFields)
        assertEquals(1, beforeClear.cachedConstructors)

        KavaMemberLookup.clearNegativeCaches()

        val afterClear = KavaMemberLookup.diagnostics()
        assertEquals(1, afterClear.cachedClasses)
        assertEquals(1, afterClear.cachedMethods)
        assertEquals(0, afterClear.cachedFields)
        assertEquals(0, afterClear.cachedConstructors)

        val missesBeforeRetry = afterClear.cacheMisses
        val hitsBeforeRetry = afterClear.cacheHits
        assertSame(HiddenMethods::class.java, KavaMemberLookup.classOrNull(loader, HiddenMethods::class.java.name))
        assertEquals("seed", KavaMemberLookup.methodOrNull(HiddenMethods::class.java, "seed")?.name)
        assertEquals(missesBeforeRetry, KavaMemberLookup.diagnostics().cacheMisses)
        assertEquals(hitsBeforeRetry + 2, KavaMemberLookup.diagnostics().cacheHits)
    }

    private fun assertFalse(condition: Boolean) = org.junit.Assert.assertFalse(condition)

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (KavaMemberLookupTest) -> Unit) {
            val s = KavaMemberLookupTest()
            s.resetLookupCaches()
            block(s)
        }
        runIsolated { s -> s.`zero parameter constructor lookup is exact even with overloaded constructors`() }
        runIsolated { s -> s.`zero parameter method lookup rejects argument only overloads`() }
        runIsolated { s -> s.`inherited zero parameter lookup stays exact and caches absent overloads`() }
        runIsolated { s -> s.`resolves exact private method and exposes raw member`() }
        runIsolated { s -> s.`returns null for expected version drift`() }
        runIsolated { s -> s.`inherited lookup returns the most derived override`() }
        runIsolated { s -> s.`resolves inherited method and field`() }
        runIsolated { s -> s.`enumerates inherited fields through cached Kava boundary`() }
        runIsolated { s -> s.`enumerates methods across the complete superclass chain`() }
        runIsolated { s -> s.`resolves private constructor and declared member collections`() }
        runIsolated { s -> s.`loads classes through explicit class loader`() }
        runIsolated { s -> s.`caches positive and negative lookups and exposes diagnostics`() }
        runIsolated { s -> s.`cancelled member lookup does not poison the stable cache`() }
        runIsolated { s -> s.`transient loader exception does not permanently poison positive lookup cache`() }
        runIsolated { s -> s.`superclass collections reuse cached declared class collections and batch boot scope`() }
        runIsolated { s -> s.`registered host loader uses volatile fast path and keeps third party child loaders out of cache`() }
        runIsolated { s -> s.`isolated and url class loaders bypass persistent loaderCaches and remain collectable`() }
        runIsolated { s -> s.`negative cache separation preserves positive hits when clearNegativeCaches runs`() }
    }
}
