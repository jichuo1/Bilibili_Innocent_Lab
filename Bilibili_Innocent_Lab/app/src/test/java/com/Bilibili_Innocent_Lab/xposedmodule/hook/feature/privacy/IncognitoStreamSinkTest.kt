package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import org.junit.Assert.*
import org.junit.Test

class IncognitoStreamSinkTest {
    @Test fun activityIsAcknowledgedLocallyAndCloseIsIdempotent() {
        val acknowledgements = mutableListOf<Long>()
        var completions = 0
        val sink = IncognitoStreamSink({ acknowledgements += it }, { completions++ })
        assertEquals(1L, sink.acceptForAck())
        assertEquals(2L, sink.acceptForAck())
        assertEquals(listOf(1L, 2L), acknowledgements)
        sink.close()
        sink.close()
        assertEquals(0L, sink.acceptForAck())
        assertEquals(1, completions)
        assertEquals(2, acknowledgements.size)
    }

    @Test fun failedCompletionCannotReopenOrCompleteTheStreamTwice() {
        var calls = 0
        val sink = IncognitoStreamSink({}, { calls++; error("host callback failure") })
        assertTrue(runCatching { sink.close() }.isFailure)
        sink.close()
        assertEquals(0L, sink.acceptForAck())
        assertEquals(1, calls)
    }
}
