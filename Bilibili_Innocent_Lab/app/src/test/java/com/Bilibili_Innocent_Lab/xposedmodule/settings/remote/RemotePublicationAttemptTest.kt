package com.Bilibili_Innocent_Lab.xposedmodule.settings.remote
import org.junit.Assert.*
import org.junit.Test
class RemotePublicationAttemptTest {
    @Test fun onlyOneCurrentResultIsDeliveredAndCancellationDoesNotPretendRollback() {
        var now=1L; val results=mutableListOf<RemoteHookConfigPublishResult>()
        val attempt=RemotePublicationAttempt(1,2,3,10,{now},{results.add(it)})
        val success=RemoteHookConfigPublishResult.Success(1,true)
        assertTrue(attempt.complete(success)); assertFalse(attempt.complete(success)); assertEquals(1,results.size)
        val cancelled=RemotePublicationAttempt(2,2,3,10,{now},{results.add(it)})
        cancelled.cancel(); assertFalse(cancelled.complete(success)); assertEquals(1,results.size)
        val late=RemotePublicationAttempt(3,2,3,10,{now},{results.add(it)})
        now=11; assertFalse(late.isActive()); assertFalse(late.complete(success)); assertEquals(1,results.size)
    }
}
