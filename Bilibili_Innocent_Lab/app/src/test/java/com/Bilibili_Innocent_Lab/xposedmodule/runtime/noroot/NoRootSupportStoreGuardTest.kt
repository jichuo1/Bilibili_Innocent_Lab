package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoRootSupportStoreGuardTest {
    private val enabledSnapshot = NoRootConfigSnapshot(
        schemaVersion = NoRootConfigSnapshotCodec.CURRENT_SCHEMA_VERSION,
        catalogVersion = 1,
        modulePackage = "com.example.module",
        moduleVersionCode = 1L,
        revision = 42L,
        adapterResetRevision = 0L,
        enabled = true,
        values = mapOf("feature" to true)
    )

    @Test
    fun `rejects late callback after user disables support`() {
        assertFalse(
            NoRootSupportStore.acceptsEnabledStateWrite(
                desiredEnabled = false,
                currentSnapshot = enabledSnapshot,
                expectedRevision = enabledSnapshot.revision,
                stillCurrent = true
            )
        )
    }

    @Test
    fun `rejects stale generation even when revision still matches`() {
        assertFalse(
            NoRootSupportStore.acceptsEnabledStateWrite(
                desiredEnabled = true,
                currentSnapshot = enabledSnapshot,
                expectedRevision = enabledSnapshot.revision,
                stillCurrent = false
            )
        )
    }

    @Test
    fun `rejects state from an older snapshot revision`() {
        assertFalse(
            NoRootSupportStore.acceptsEnabledStateWrite(
                desiredEnabled = true,
                currentSnapshot = enabledSnapshot.copy(revision = 43L),
                expectedRevision = enabledSnapshot.revision,
                stillCurrent = true
            )
        )
    }

    @Test
    fun `accepts current matching enabled state`() {
        assertTrue(
            NoRootSupportStore.acceptsEnabledStateWrite(
                desiredEnabled = true,
                currentSnapshot = enabledSnapshot,
                expectedRevision = enabledSnapshot.revision,
                stillCurrent = true
            )
        )
    }
}
