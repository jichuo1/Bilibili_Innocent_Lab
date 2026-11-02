package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StartupTraceTest {
    @Test fun nestedTimesRetriesAndFailuresPreserveOriginalAction() {
        var tick = 0L
        val recorder = StartupTraceRecorder(clock = { tick }, mainThread = { true })
        recorder.measure("entry") { tick = 10 }
        recorder.attempt()
        val failure = IllegalStateException("private message must not be exported")
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            recorder.measure("install") {
                tick = 20
                recorder.measure("feature.copy") { tick = 50 }
                tick = 70
                throw failure
            }
        })
        val result = StartupTraceRecorder.validate(recorder.json())
        assertFalse(result.toString().contains("private message"))
        val rows = result.getJSONArray("rows")
        val install = (0 until rows.length()).map(rows::getJSONObject).single { it.getString("id") == "install" }
        assertEquals(60L, install.getLong("ns"))
        assertEquals(30L, install.getLong("ownNs"))
        assertEquals(1, install.getInt("failures"))
        assertEquals(1, install.getInt("attempt"))
        recorder.attempt()
        recorder.measure("install") { tick = 80 }
        assertEquals(4, StartupTraceRecorder.validate(recorder.json()).getJSONArray("rows").length())
    }

    @Test fun repeatedCallsAggregateAndCapacityIsBoundedPerAttempt() {
        var tick = 0L
        val recorder = StartupTraceRecorder(clock = { tick++ }, mainThread = { false })
        repeat(400) { recorder.measure("registration") {} }
        repeat(128) { recorder.measure("phase.$it") {} }
        val root = StartupTraceRecorder.validate(recorder.json())
        assertEquals(128, root.getJSONArray("rows").length())
        assertEquals(1, root.getInt("dropped"))
        assertEquals(400, root.getJSONArray("rows").getJSONObject(0).getInt("calls"))
        repeat(4) { recorder.attempt() }
        recorder.measure("install") {}
        assertEquals(2, StartupTraceRecorder.validate(recorder.json()).getJSONArray("rows").getJSONObject(128).getInt("attempt"))
    }

    @Test fun codecRejectsExtraFieldsTypesAndInvalidTotals() {
        val recorder = StartupTraceRecorder()
        recorder.measure("install") {}
        val raw = recorder.json()
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("secret", "value") }, { it.put("dropped", "0") },
            { it.getJSONArray("rows").getJSONObject(0).put("id", "host#member") },
            { it.getJSONArray("rows").getJSONObject(0).put("ownNs", Long.MAX_VALUE) },
            { it.getJSONArray("rows").getJSONObject(0).put("failures", 2) },
            { it.getJSONArray("rows").getJSONObject(0).put("main", "true") }
        )
        mutations.forEach { mutate -> assertThrows(Exception::class.java) {
            StartupTraceRecorder.validate(JSONObject(raw).apply(mutate).toString())
        } }
        assertThrows(Exception::class.java) { StartupTraceRecorder.validate("$raw trailing") }
    }

    @Test fun explicitRootsExcludeBusinessLookupsAndRestoreScopeAfterFailure() {
        val recorder = StartupTraceRecorder(roots = setOf("entry"))
        recorder.measure("lookup.value") {}
        assertThrows(IllegalStateException::class.java) {
            recorder.measure("entry") { recorder.measure("lookup.value") { error("failure") } }
        }
        recorder.measure("lookup.value") {}
        val rows = StartupTraceRecorder.validate(recorder.json()).getJSONArray("rows")
        assertEquals(2, rows.length())
        val lookup = (0 until rows.length()).map(rows::getJSONObject).single { it.getString("id") == "lookup.value" }
        assertEquals(1, lookup.getInt("calls"))
        assertEquals("entry", lookup.getJSONArray("parents").getString(0))
    }
}
