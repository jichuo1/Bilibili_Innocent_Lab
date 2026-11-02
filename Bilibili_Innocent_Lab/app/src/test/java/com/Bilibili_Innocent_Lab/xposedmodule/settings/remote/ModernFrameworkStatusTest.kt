package com.Bilibili_Innocent_Lab.xposedmodule.settings.remote

import io.github.libxposed.service.XposedService
import org.junit.Assert.*
import org.junit.Test

class ModernFrameworkStatusTest {
    @Test fun `framework routing identity precedes optional version metadata`() {
        val core = readModernFrameworkCapabilities({ 102 }, { XposedService.PROP_CAP_REMOTE })
        val named = core.withModernFrameworkIdentity { "LSPatch" }
        assertTrue(named.capable)
        assertTrue(named.isLspatch)
        val versionUnavailable = named.withModernFrameworkVersions({ error("slow or unavailable") }, { 487 })
        assertTrue(versionUnavailable.capable)
        assertTrue(versionUnavailable.isLspatch)
        assertFalse(core.withModernFrameworkIdentity { error("identity unknown") }.capable)
        assertFalse(core.withModernFrameworkIdentity { "" }.capable)
    }
    @Test
    fun `LSPatch identity is exact presentation metadata and never a capability grant`() {
        assertTrue(isLspatchFrameworkName("LSPatch"))
        assertTrue(isLspatchFrameworkName(" lspatch "))
        assertFalse(isLspatchFrameworkName("LSPosed-LSPatch"))
        assertFalse(isLspatchFrameworkName("Vector"))

        val status = readModernFrameworkStatus(
            { 100 }, { 0L }, { "LSPatch" }, { "1.2" }, { 487L }
        )
        assertTrue(status.isLspatch)
        assertFalse(status.capable)
    }

    @Test
    fun `Irena Modern API 101 is accepted with remote capability and older APIs stay closed`() {
        val irena = readModernFrameworkStatus(
            { 101 }, { XposedService.PROP_CAP_REMOTE or XposedService.PROP_CAP_SYSTEM },
            { "LSPosed" }, { "2.0.0" }, { 7316L }
        )
        assertTrue(irena.capable)
        assertEquals("LSPosed", irena.name)
        assertEquals(101, irena.apiVersion)
        assertFalse(readModernFrameworkStatus(
            { 100 }, { XposedService.PROP_CAP_REMOTE }, { "LSPosed" }, { "old" }, { 1L }
        ).capable)
        assertFalse(readModernFrameworkStatus({ 101 }, { 0L }, { "Irena" }, { "2.0.0" }, { 1L }).capable)
    }
    @Test
    fun `Vector build and independent capabilities are retained`() {
        val flags = XposedService.PROP_CAP_REMOTE or XposedService.PROP_CAP_SYSTEM or
            XposedService.PROP_RT_API_PROTECTION
        val status = readModernFrameworkStatus({ 102 }, { flags }, { "Vector" }, { "2.2 (3110) c4a701aa" }, { 3110L })
        assertTrue(status.capable)
        assertEquals(3110L, status.versionCode)
        assertEquals(flags, status.properties)
        assertNull(status.failureCode)
    }

    @Test
    fun `optional metadata failure does not disable a capable framework`() {
        val status = readModernFrameworkStatus(
            { 102 }, { XposedService.PROP_CAP_REMOTE }, { "LSPosed" }, { error("binder failure") }, { 100L }
        )
        assertTrue(status.connected)
        assertTrue(status.capable)
        assertNull(status.version)
        assertEquals("framework_metadata_unavailable", status.failureCode)
    }

    @Test
    fun `missing API or capability is never inferred from a framework name`() {
        val missingApi = readModernFrameworkStatus(
            { error("unavailable") }, { XposedService.PROP_CAP_REMOTE }, { "Vector" }, { "2.2" }, { 3080L }
        )
        val missingCapability = readModernFrameworkStatus({ 102 }, { 0L }, { "Vector" }, { "2.2" }, { 3080L })
        assertFalse(missingApi.capable)
        assertFalse(missingCapability.capable)
        assertTrue(missingApi.connected)
    }

    @Test
    fun `framework metadata remains bounded and failed values remain unavailable`() {
        val status = readModernFrameworkStatus(
            { 102 }, { error("unavailable") }, { "v".repeat(1000) }, { "b".repeat(1000) }, { -1L }
        )
        assertEquals(128, status.name.length)
        assertEquals(128, status.version?.length)
        assertNull(status.properties)
        assertNull(status.versionCode)
        assertFalse(status.capable)
    }

    @Test
    fun `only the NPatch no-root channel is gated by the no-root switch`() {
        val remoteOnly = XposedService.PROP_CAP_REMOTE
        val rootFlags = XposedService.PROP_CAP_REMOTE or XposedService.PROP_CAP_SYSTEM

        assertTrue(isNpatchConfigChannel(
            readModernFrameworkStatus({ 102 }, { remoteOnly }, { "NPatch" }, { "1.0.9" }, { 833L })
        ))
        // 改名后仍靠能力位识别：没有 CAP_SYSTEM 且不是 LSPatch 的实现同样算免 Root 通道。
        assertTrue(isNpatchConfigChannel(
            readModernFrameworkStatus({ 102 }, { remoteOnly }, { "RenamedPatch" }, { "1.0" }, { 1L })
        ))
        // 真 Root 框架与 LSPatch 的标准发布不受免 Root 开关影响。
        assertFalse(isNpatchConfigChannel(
            readModernFrameworkStatus({ 102 }, { rootFlags }, { "LSPosed" }, { "2.0" }, { 7316L })
        ))
        assertFalse(isNpatchConfigChannel(
            readModernFrameworkStatus({ 102 }, { remoteOnly }, { "LSPatch" }, { "1.0" }, { 487L })
        ))
        // 元数据未就绪时绝不判定，宁可退回文档判据也不能关掉 LSPosed 的发布。
        assertFalse(isNpatchConfigChannel(ModernFrameworkStatus(
            connected = false, capable = false, name = "", apiVersion = 0
        )))
    }
}
