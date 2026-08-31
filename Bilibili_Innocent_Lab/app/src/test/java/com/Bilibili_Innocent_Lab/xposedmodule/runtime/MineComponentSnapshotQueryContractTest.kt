package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MineComponentSnapshotQueryContractTest {

    @Test
    fun `digest is deterministic and rejects changed payload`() {
        val digest = MineComponentSnapshotQueryContract.sha256("abc")
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            digest
        )
        assertTrue(MineComponentSnapshotQueryContract.digestMatches("abc", digest.uppercase()))
        assertFalse(MineComponentSnapshotQueryContract.digestMatches("abd", digest))
    }

    @Test
    fun `nonce accepts uuid shape and rejects blank short or punctuation`() {
        assertTrue(
            MineComponentSnapshotQueryContract.isValidNonce(
                "123e4567-e89b-12d3-a456-426614174000"
            )
        )
        assertFalse(MineComponentSnapshotQueryContract.isValidNonce(""))
        assertFalse(MineComponentSnapshotQueryContract.isValidNonce("too-short"))
        assertFalse(MineComponentSnapshotQueryContract.isValidNonce("a".repeat(16) + ":"))
    }

    @Test
    fun `source identity requires all version fields`() {
        assertTrue(MineComponentSnapshotSource(1L, 2L, 3L).isComplete)
        assertFalse(MineComponentSnapshotSource(0L, 2L, 3L).isComplete)
        assertFalse(MineComponentSnapshotSource(1L, 0L, 3L).isComplete)
        assertFalse(MineComponentSnapshotSource(1L, 2L, 0L).isComplete)
    }
}
