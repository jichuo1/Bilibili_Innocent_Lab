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

    fun `batch scopes close on exceptions and never cross threads or class loaders`() {
        org.junit.Assume.assumeFalse(BuildConfig.MEGUVIZ_LEGACY_LOOKUP)
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


    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (KavaMemberLookupTest) -> Unit) {
            val s = KavaMemberLookupTest()
            s.resetLookupCaches()
            block(s)
        }
        runIsolated { s -> s.`batch scopes close on exceptions and never cross threads or class loaders`() }
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
    }
}
