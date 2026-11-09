package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.ModernFrameworkStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActivationFrameworkSourceTest {
    @Test
    fun `another capable manager cannot name an active LSPatch host`() {
        listOf("NPatch", "NPatch 1.0.9", "LSPosed", "LSPatch-compatible").forEach { name ->
            val source = ActivationFrameworkSource.lspatch(service(name))
            assertEquals("LSPatch", source.name)
            assertNull(source.apiVersion)
        }
    }

    @Test
    fun `matching online manager supplies its verified API version`() {
        val source = ActivationFrameworkSource.lspatch(service(" lspatch "))
        assertEquals("LSPatch", source.name)
        assertEquals(102, source.apiVersion)
    }

    @Test
    fun `missing or incomplete service metadata cannot supply API version`() {
        val service = service("LSPatch")
        listOf(
            service.copy(connected = false),
            service.copy(capable = false),
            service.copy(apiVersion = 0),
            service.copy(apiVersion = -1)
        ).forEach {
            val source = ActivationFrameworkSource.lspatch(it)
            assertEquals("LSPatch", source.name)
            assertNull(source.apiVersion)
        }
    }

    private fun service(name: String) = ModernFrameworkStatus(
        connected = true, capable = true, name = name, apiVersion = 102
    )
}
