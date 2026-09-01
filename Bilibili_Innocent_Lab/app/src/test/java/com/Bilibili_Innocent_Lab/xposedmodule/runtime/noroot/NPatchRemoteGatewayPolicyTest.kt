package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NPatchRemoteGatewayPolicyTest {

    @Test
    fun `only the API 102 service descriptor is accepted`() {
        assertTrue(
            NPatchRemoteGateway.acceptsServiceDescriptor(
                "io.github.libxposed.service.IXposedService"
            )
        )
        assertFalse(NPatchRemoteGateway.acceptsServiceDescriptor(null))
        assertFalse(
            NPatchRemoteGateway.acceptsServiceDescriptor(
                "io.github.libxposed.service.ILegacyXposedService"
            )
        )
    }

    @Test
    fun `connection circuit stays open only before its monotonic deadline`() {
        assertFalse(NPatchRemoteGateway.isConnectionCircuitOpen(100L, 0L))
        assertTrue(NPatchRemoteGateway.isConnectionCircuitOpen(100L, 101L))
        assertFalse(NPatchRemoteGateway.isConnectionCircuitOpen(100L, 100L))
        assertFalse(NPatchRemoteGateway.isConnectionCircuitOpen(101L, 100L))
    }

    @Test
    fun `NPatch document reuses the canonical values and snapshot revisions`() {
        val snapshot = NoRootConfigSnapshot(
            schemaVersion = NoRootConfigSnapshotCodec.CURRENT_SCHEMA_VERSION,
            catalogVersion = SettingsCatalog.CATALOG_VERSION,
            modulePackage = BuildConfig.APPLICATION_ID,
            moduleVersionCode = BuildConfig.VERSION_CODE.toLong(),
            revision = 21L,
            adapterResetRevision = 34L,
            enabled = true,
            values = mapOf(HookEntry.PREF_FREE_COPY_ENABLED to false)
        )

        val resolved = NPatchRemoteGateway.resolveRemoteValues(
            snapshot,
            mapOf(
                HookEntry.PREF_FREE_COPY_ENABLED to true,
                RemoteHookConfigContract.KEY_FREE_COPY_CONFIG_REVISION to 55L,
                RemoteHookConfigContract.KEY_ADAPTER_RESET_TIMESTAMP to 1L,
                "private_token" to "must-not-leak"
            )
        )

        assertEquals(RemoteHookConfigContract.hookValueKeys, resolved.keys)
        assertEquals(false, resolved[HookEntry.PREF_FREE_COPY_ENABLED])
        assertEquals(55L, resolved[RemoteHookConfigContract.KEY_FREE_COPY_CONFIG_REVISION])
        assertEquals(34L, resolved[RemoteHookConfigContract.KEY_ADAPTER_RESET_TIMESTAMP])
        assertFalse("private_token" in resolved)
    }
}
