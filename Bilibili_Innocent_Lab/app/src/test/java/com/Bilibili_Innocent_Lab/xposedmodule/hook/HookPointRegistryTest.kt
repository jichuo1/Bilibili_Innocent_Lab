package com.Bilibili_Innocent_Lab.xposedmodule.hook

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HookPointRegistryTest {

    private class Fixture {
        @Suppress("unused")
        private fun exact(value: Int): String = value.toString()

        @Suppress("unused")
        private fun overloaded(value: Int) = value

        @Suppress("unused")
        private fun overloaded(value: String) = value
    }

    private lateinit var registry: HookPointRegistry

    @Before
    fun setUp() {
        KavaMemberLookup.resetForTests()
        registry = HookPointRegistry(requireNotNull(Fixture::class.java.classLoader))
    }

    @After
    fun tearDown() {
        KavaMemberLookup.resetForTests()
    }

    @Test
    fun `resolves adapted primitive signature and prevents duplicate registration`() {
        val method = registry.resolveAdapted(
            id = "fixture.exact",
            className = Fixture::class.java.name,
            methodName = "exact",
            parameterClassNames = listOf("int")
        )

        assertNotNull(method)
        assertTrue(registry.claim("fixture.exact", requireNotNull(method)))
        registry.markInstalled("fixture.exact", method)
        assertFalse(registry.claim("fixture.exact", method))
        assertEquals(HookPointRegistry.State.DUPLICATE, registry.snapshot().single().state)
    }

    @Test
    fun `reports ambiguous and missing hook points without throwing`() {
        assertNull(
            registry.resolveAdapted(
                id = "fixture.ambiguous",
                className = Fixture::class.java.name,
                methodName = "overloaded",
                parameterClassNames = null
            )
        )
        assertNull(
            registry.resolveFirst(
                id = "fixture.missing",
                className = "missing.Fixture",
                methodName = "none"
            )
        )

        val states = registry.snapshot().associate { it.id to it.state }
        assertEquals(HookPointRegistry.State.AMBIGUOUS_METHOD, states["fixture.ambiguous"])
        assertEquals(HookPointRegistry.State.MISSING_CLASS, states["fixture.missing"])
    }
}
