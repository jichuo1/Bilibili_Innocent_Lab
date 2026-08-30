package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoRootRestartFlushGuardTest {
    @Test
    fun `accepts confirmed enabled revision that is still current`() {
        assertTrue(
            accepts(
                desiredEnabled = true,
                expectedEnabled = true,
                snapshotEnabled = true
            )
        )
    }

    @Test
    fun `accepts confirmed tombstone revision that is still current`() {
        assertTrue(
            accepts(
                desiredEnabled = false,
                expectedEnabled = false,
                snapshotEnabled = false
            )
        )
    }

    @Test
    fun `rejects enabled completion after user disables support`() {
        assertFalse(
            accepts(
                desiredEnabled = false,
                expectedEnabled = true,
                snapshotEnabled = false
            )
        )
    }

    @Test
    fun `rejects tombstone completion after user enables support`() {
        assertFalse(
            accepts(
                desiredEnabled = true,
                expectedEnabled = false,
                snapshotEnabled = true
            )
        )
    }

    @Test
    fun `rejects stale generation revision and unconfirmed writes`() {
        assertFalse(accepts(generationMatches = false))
        assertFalse(accepts(snapshotRevision = 43L))
        assertFalse(accepts(remoteWriteConfirmed = false))
    }

    private fun accepts(
        generationMatches: Boolean = true,
        desiredEnabled: Boolean = true,
        expectedEnabled: Boolean = true,
        snapshotEnabled: Boolean? = true,
        snapshotRevision: Long = 42L,
        expectedRevision: Long = 42L,
        remoteWriteConfirmed: Boolean = true
    ) = NoRootRestartFlushGuard.accepts(
        generationMatches = generationMatches,
        desiredEnabled = desiredEnabled,
        expectedEnabled = expectedEnabled,
        snapshotEnabled = snapshotEnabled,
        snapshotRevision = snapshotRevision,
        expectedRevision = expectedRevision,
        remoteWriteConfirmed = remoteWriteConfirmed
    )
}
