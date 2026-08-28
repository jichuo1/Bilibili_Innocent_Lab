package com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyTopologySessionGateTest {

    private val firstKey = ReplyTopologyThreadKey(oid = 1L, type = 1L, rootRpid = 100L)
    private val secondKey = ReplyTopologyThreadKey(oid = 2L, type = 1L, rootRpid = 200L)

    @Test
    fun allowsOnlyOneInFlightRequestAndReleasesItOnCompletion() {
        val gate = ReplyTopologySessionGate()
        val session = gate.open(firstKey, pageEpoch = 7L)
        val first = gate.beginRequest(session)

        assertNotNull(first)
        assertNull(gate.beginRequest(session))
        assertTrue(gate.accepts(first!!))
        assertTrue(gate.completeRequest(first))
        assertFalse(gate.accepts(first))
        assertNotNull(gate.beginRequest(session))
    }

    @Test
    fun openingNewSessionInvalidatesLateResultFromOldRoot() {
        val gate = ReplyTopologySessionGate()
        val oldSession = gate.open(firstKey, pageEpoch = 1L)
        val oldRequest = checkNotNull(gate.beginRequest(oldSession))

        val newSession = gate.open(secondKey, pageEpoch = 2L)

        assertNotEquals(oldSession.sessionId, newSession.sessionId)
        assertFalse(gate.accepts(oldSession))
        assertFalse(gate.accepts(oldRequest))
        assertFalse(gate.completeRequest(oldRequest))
        assertNotNull(gate.beginRequest(newSession))
    }

    @Test
    fun staleCloseCannotCloseNewSession() {
        val gate = ReplyTopologySessionGate()
        val oldSession = gate.open(firstKey, pageEpoch = 1L)
        val newSession = gate.open(secondKey, pageEpoch = 2L)

        assertFalse(gate.close(oldSession))
        assertTrue(gate.accepts(newSession))
        assertTrue(gate.close(newSession))
        assertFalse(gate.accepts(newSession))
    }
}
