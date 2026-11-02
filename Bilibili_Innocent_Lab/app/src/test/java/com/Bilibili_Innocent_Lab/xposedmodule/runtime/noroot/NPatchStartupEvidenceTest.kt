package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NPatchStartupEvidenceTest {
    private fun sample(index: Int) = NPatchStartupEvidence("00000000-0000-0000-0000-${index.toString().padStart(12, '0')}",
        index.toLong(), 1, 10, 20, 30, "granted", "provider", listOf(NPatchRouteEvidence("provider", "granted")))

    @Test fun codecRejectsUnknownFieldsTypesRoutesAndUnboundedNumbers() {
        val encoded = NPatchStartupCodec.encode(listOf(sample(1)))
        assertEquals(listOf(sample(1)), NPatchStartupCodec.decode(encoded))
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("token", "secret") }, { it.put("admissionMs", "10") }, { it.put("attempt", 4294967297L) },
            { it.put("totalMs", -1) }, { it.put("selected", "https://secret") }, { it.put("installMs", 3.5) },
            { it.put("result", "secret") }, { it.put("session", "private-path") }
        )
        mutations.forEach { mutate ->
            val row = JSONObject(encoded.getJSONObject(0).toString()).apply(mutate)
            assertThrows(Exception::class.java) { NPatchStartupCodec.decode(JSONArray().put(row)) }
        }
    }

    @Test fun boundedHistoryDeduplicatesBootAndKeepsLastAttempt() {
        val old = (1..8).map(::sample)
        val current = sample(8).copy(attempt = 2, admissionMs = 40)
        val merged = NPatchStartupCodec.merge(old, listOf(current, sample(9)))
        assertEquals(8, merged.size)
        assertEquals(2L, merged.first().startedAt)
        assertEquals(current, merged[6])
        assertEquals(8, NPatchStartupCodec.merge(merged, listOf(current)).size)
        assertEquals(4L, NPatchStartupCodec.p50((1L..8L).toList()))
        assertNull(NPatchStartupCodec.p50(emptyList()))
    }

    @Test fun trackerPreservesRetryAndSeparatesUnknownInstallFromZero() {
        val tracker = NPatchStartupTracker()
        assertTrue(tracker.snapshot().isEmpty())
        tracker.admission(10, "timeout", "unknown", emptyList())
        assertNull(tracker.snapshot().single().installMs)
        tracker.admission(5, "granted", "local", listOf(NPatchRouteEvidence("local", "granted")))
        tracker.installation(0)
        val sample = tracker.snapshot().single()
        assertEquals(2, sample.attempt)
        assertEquals(0L, sample.installMs)
        assertEquals(5L, sample.totalMs)
    }
}
