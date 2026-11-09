package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import org.junit.Assert.*
import org.junit.Test

class IncognitoSessionTest {
    @Test fun identityIsEphemeralAndStableWithinOneSession() {
        val session = IncognitoSession()
        assertNull(session.identity())
        val first = session.enter()
        assertSame(first, session.enter())
        assertSame(first, session.identity())
        assertTrue(session.accepts(first))
        assertNotEquals(first.buvid, first.sessionId)
        session.exit()
        assertNull(session.identity())
        assertFalse(session.accepts(first))
        val second = session.enter()
        assertNotEquals(first.buvid, second.buvid)
        assertNotEquals(first.sessionId, second.sessionId)
        assertTrue(second.generation > first.generation)
    }

    @Test fun refreshingInvalidatesOutstandingCallbacks() {
        val session = IncognitoSession()
        val first = session.enter()
        val second = session.refresh()
        assertFalse(session.accepts(first))
        assertTrue(session.accepts(second))
        assertFalse(session.accepts(second.copy()))
        assertNotEquals(first.buvid, second.buvid)
    }

    @Test fun refreshCanInvalidateAnotherProcessWithoutReopeningClosedSessions() {
        val main = IncognitoSession()
        val worker = IncognitoSession()
        main.enter()
        val old = worker.enter()
        val fresh = main.refresh()
        assertTrue(worker.adopt(fresh.buvid, fresh.sessionId))
        assertFalse(worker.accepts(old))
        assertEquals(fresh.buvid, worker.identity()!!.buvid)
        assertFalse(worker.adopt(fresh.buvid, fresh.sessionId))
        worker.exit()
        assertFalse(worker.adopt(fresh.buvid, fresh.sessionId))
        assertNull(worker.identity())
    }

    @Test fun malformedRemoteIdentityCannotReplaceAnActiveIdentity() {
        val session = IncognitoSession()
        val original = session.enter()
        assertFalse(session.adopt("persistent-account-id", original.sessionId))
        assertFalse(session.adopt(original.buvid, original.buvid))
        assertSame(original, session.identity())
    }

    @Test(expected = IllegalStateException::class)
    fun refreshingDoesNotEnableIncognito() {
        IncognitoSession().refresh()
    }
}
