package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoRootTargetConfigBridgeTest {

    @Test
    fun `disabled envelope remains explicit and carries tombstone revision`() {
        assertEquals(
            NoRootTargetConfigBridge.Resolution.Disabled(17L),
            NoRootTargetConfigBridge.resolve(true, false, 17L, null, "provider")
        )
    }

    @Test
    fun `invalid or incomplete enabled envelope fails closed`() {
        assertEquals(
            NoRootTargetConfigBridge.Resolution.Invalid,
            NoRootTargetConfigBridge.resolve(false, true, 17L, "{}", "provider")
        )
        assertEquals(
            NoRootTargetConfigBridge.Resolution.Invalid,
            NoRootTargetConfigBridge.resolve(true, true, 17L, null, "provider")
        )
    }

    @Test
    fun `ready snapshot must match envelope revision`() {
        val snapshot = NoRootConfigSnapshot(
            schemaVersion = NoRootConfigSnapshotCodec.CURRENT_SCHEMA_VERSION,
            catalogVersion = 3,
            modulePackage = BuildConfig.APPLICATION_ID,
            moduleVersionCode = BuildConfig.VERSION_CODE.toLong(),
            revision = 23L,
            adapterResetRevision = 0L,
            enabled = true,
            values = mapOf("feature.enabled" to true)
        )
        val payload = NoRootConfigSnapshotCodec.encode(snapshot)
        val ready = NoRootTargetConfigBridge.resolve(true, true, 23L, payload, "callback")
        assertTrue(ready is NoRootTargetConfigBridge.Resolution.Ready)
        assertEquals(
            NoRootTargetConfigBridge.Resolution.Invalid,
            NoRootTargetConfigBridge.resolve(true, true, 24L, payload, "callback")
        )
    }
}
