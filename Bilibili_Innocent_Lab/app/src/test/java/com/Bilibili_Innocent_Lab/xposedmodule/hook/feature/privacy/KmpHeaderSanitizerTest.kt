package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KmpHeaderSanitizerTest {
    private val identity = IncognitoSession.Identity(1, "session-buvid", "session-id")
    private val sanitizer = KmpHeaderSanitizer(
        rewriteMetadata = { _, id -> "metadata:${id.buvid}".toByteArray() },
        rewriteDevice = { _, id -> "device:${id.buvid}".toByteArray() })

    private fun maps(ascii: Map<Any?, Any?>, binary: Map<Any?, Any?> = emptyMap()) =
        listOf(LinkedHashMap(ascii), LinkedHashMap(binary))

    @Test fun credentialsAreRemovedAndIdentifiersReplaced() {
        val m = maps(mapOf("Authorization" to "identify_v1 secret", "x-bili-mid" to "123", "Buvid" to "real",
            "session_id" to "real-session", "user-agent" to "ua"))
        val seen = sanitizer.sanitize(m, identity)
        assertFalse(m[0].containsKey("Authorization"))
        assertFalse(m[0].containsKey("x-bili-mid"))
        assertEquals("session-buvid", m[0]["Buvid"])
        assertEquals("session-id", m[0]["session_id"])
        assertEquals("ua", m[0]["user-agent"])
        // Names are reported lower-case; values never are.
        assertTrue("authorization" in seen && "user-agent" in seen)
    }

    @Test fun binaryIdentityHeadersAreRewrittenAndOthersLeftAlone() {
        val other = byteArrayOf(9, 9)
        val m = maps(emptyMap(), mapOf("x-bili-metadata-bin" to byteArrayOf(1), "x-bili-device-bin" to byteArrayOf(2),
            "x-bili-network-bin" to other))
        sanitizer.sanitize(m, identity)
        assertArrayEquals("metadata:session-buvid".toByteArray(), m[1]["x-bili-metadata-bin"] as ByteArray)
        assertArrayEquals("device:session-buvid".toByteArray(), m[1]["x-bili-device-bin"] as ByteArray)
        assertArrayEquals(other, m[1]["x-bili-network-bin"] as ByteArray)
    }

    @Test fun emptyBinaryHeaderIsLeftEmpty() {
        val m = maps(emptyMap(), mapOf("x-bili-metadata-bin" to ByteArray(0)))
        sanitizer.sanitize(m, identity)
        assertEquals(0, (m[1]["x-bili-metadata-bin"] as ByteArray).size)
    }

    @Test fun unexpectedBinaryHeaderTypeAbortsInsteadOfSendingUnsealed() {
        val m = maps(mapOf("x-bili-metadata-bin" to "not-bytes"))
        assertThrows(IllegalStateException::class.java) { sanitizer.sanitize(m, identity) }
    }

    @Test fun rewriteFailureAbortsTheWholeCall() {
        val failing = KmpHeaderSanitizer({ _, _ -> error("parse") }, { b, _ -> b })
        val m = maps(emptyMap(), mapOf("x-bili-metadata-bin" to byteArrayOf(1)))
        assertThrows(IllegalStateException::class.java) { failing.sanitize(m, identity) }
    }
}
