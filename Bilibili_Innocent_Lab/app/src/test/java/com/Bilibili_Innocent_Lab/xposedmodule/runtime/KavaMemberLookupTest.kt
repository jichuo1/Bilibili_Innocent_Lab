package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KavaMemberLookupTest {

    @Before
    fun resetLookupCaches() {
        KavaMemberLookup.resetForTests()
    }

    private open class HiddenBase {
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

    @Test
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

    @Test
    fun `returns null for expected version drift`() {
        assertNull(
            KavaMemberLookup.methodOrNull(
                HiddenMethods::class.java,
                "missingMethod",
                java.lang.Boolean.TYPE
            )
        )
    }

    @Test
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

    @Test
    fun `enumerates inherited fields through cached Kava boundary`() {
        val fields = KavaMemberLookup.fields(
            HiddenMethods::class.java,
            includeSuperclasses = true,
            makeAccessible = true
        ) { it.name == "inheritedText" }

        assertEquals(1, fields.size)
        assertEquals("base", fields.single().get(HiddenMethods.create()))
    }

    @Test
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
    }

    @Test
    fun `loads classes through explicit class loader`() {
        val loader = requireNotNull(HiddenMethods::class.java.classLoader)

        assertSame(
            HiddenMethods::class.java,
            KavaMemberLookup.classOrNull(loader, HiddenMethods::class.java.name)
        )
        assertEquals(true, KavaMemberLookup.hasClass(loader, HiddenMethods::class.java.name))
        assertNull(KavaMemberLookup.classOrNull(loader, "missing.Type"))
    }

    @Test
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
}
