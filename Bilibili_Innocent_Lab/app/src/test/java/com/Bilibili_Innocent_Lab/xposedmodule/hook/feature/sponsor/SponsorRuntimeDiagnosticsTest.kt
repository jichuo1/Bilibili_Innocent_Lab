package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor

import org.junit.Assert.*
import org.junit.Test

class SponsorRuntimeDiagnosticsTest {
    @Test fun repeatedProgressAndFailuresWriteOnlyOncePerEvent() {
        val logs = mutableListOf<Triple<String, String, Boolean>>()
        val diagnostics = SponsorRuntimeDiagnostics { key, text, error -> logs += Triple(key, text, error) }
        repeat(1000) { diagnostics.record("progress.observed"); diagnostics.record("query.failed.timeout") }
        assertEquals(2, logs.size)
        assertFalse(logs.first().third); assertTrue(logs.last().third)
        assertNotEquals(logs.first().first, logs.last().first)
    }

    @Test fun eventCountAndMessageLengthHaveHardBudgets() {
        val logs = mutableListOf<String>()
        val diagnostics = SponsorRuntimeDiagnostics { _, text, _ -> logs += text }
        repeat(100) { diagnostics.record("event$it", "x".repeat(200)) }
        assertEquals(32, logs.size); assertTrue(logs.all { it.length < 210 })
    }

    @Test fun eachSessionRecordsItsOwnEventsUnderUniqueKeys() {
        val logs = mutableListOf<Triple<String, String, Boolean>>()
        val diagnostics = SponsorRuntimeDiagnostics { key, text, error -> logs += Triple(key, text, error) }
        val first = diagnostics.session(); val second = diagnostics.session()
        first("seek.confirmed", "a"); first("seek.confirmed", "b"); second("seek.confirmed", "c")
        assertEquals(2, logs.size)
        // 宿主 logInfo 按 key 去重，两个会话的同名事件必须是不同的 key，否则第二个视频的日志会被吞掉。
        assertNotEquals(logs[0].first, logs[1].first)
        assertTrue(logs[0].second.contains("#1 seek.confirmed a")); assertTrue(logs[1].second.contains("#2 seek.confirmed c"))
        diagnostics.record("seek.confirmed")
        assertEquals(3, logs.size)
        assertNotEquals(logs[2].first, logs[0].first); assertNotEquals(logs[2].first, logs[1].first)
        first("query.failed.timeout", "")
        assertTrue(logs.last().third)
    }

    @Test fun sessionAndProcessLineBudgetsAreHard() {
        val logs = mutableListOf<String>()
        val diagnostics = SponsorRuntimeDiagnostics { _, text, _ -> logs += text }
        val one = diagnostics.session()
        repeat(100) { one("event$it", "x".repeat(200)) }
        assertEquals(SponsorRuntimeDiagnostics.SESSION_EVENTS, logs.size); assertTrue(logs.all { it.length < 230 })
        logs.clear()
        repeat(200) { index -> val session = diagnostics.session(); repeat(10) { session("e$it", "$index") } }
        assertEquals(SponsorRuntimeDiagnostics.PROCESS_LINES - SponsorRuntimeDiagnostics.SESSION_EVENTS, logs.size)
    }
}
