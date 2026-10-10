package com.Bilibili_Innocent_Lab.xposedmodule.agent

import org.junit.Assert.*
import org.junit.Test

class AgentNotificationPolicyTest {
    private fun waiting(step: Long = 1, operation: String = "click_ui", success: Boolean = true) =
        AgentTaskState(true, "waiting_response", step, lastOperation = operation, lastOperationSucceeded = success, lastOperationStep = step)
    @Test fun fastActionResultSurvivesTheFollowingWait() {
        val policy = AgentNotificationPolicy()
        assertEquals(0L, policy.offer(waiting(), 100))
        val visible = policy.take(100)!!
        assertEquals("waiting_response", visible.phase)
        assertEquals("click_ui", visible.lastOperation)
        assertTrue(visible.showRecentOperation)
        assertEquals(900L, policy.delay(100))
    }
    @Test fun repeatedWaitsDoNotRestartOrLoseTheExpiryDeadline() {
        val policy = AgentNotificationPolicy()
        policy.offer(waiting(), 0); policy.take(0)
        assertEquals(50L, policy.offer(waiting(), 150))
        policy.offer(waiting(), 180)
        assertEquals(20L, policy.delay(180))
        assertNull(policy.take(200))
        assertEquals(700L, policy.delay(200))
        val expired = policy.take(900)!!
        assertFalse(expired.showRecentOperation)
        assertNull(policy.delay(900))
    }
    @Test fun realNewActionReplacesOldStatusWithoutReplayingAQueue() {
        val policy = AgentNotificationPolicy()
        policy.offer(waiting(), 0); policy.take(0)
        policy.offer(waiting(2, "swipe_ui"), 50)
        val next = policy.take(200)!!
        assertEquals("swipe_ui", next.lastOperation)
        assertEquals(2L, next.lastOperationStep)
    }
    @Test fun failedOperationsAreNeverShownAsSuccess() {
        val policy = AgentNotificationPolicy()
        policy.offer(waiting(success = false), 0)
        assertEquals(false, policy.take(0)!!.lastOperationSucceeded)
    }
    @Test fun stoppingAndTaskTerminationTakePriority() {
        val policy = AgentNotificationPolicy()
        policy.offer(waiting(), 0); policy.take(0)
        assertEquals(0L, policy.offer(AgentTaskState(true, "stopping"), 20))
        assertEquals("stopping", policy.take(20)!!.phase)
        policy.offer(AgentTaskState(phase = "finished"), 21)
        assertFalse(policy.take(21)!!.running)
        assertNull(policy.delay(21))
    }
    @Test fun resettingForAnotherTaskRemovesPreviousAction() {
        val policy = AgentNotificationPolicy()
        policy.offer(waiting(), 0); policy.take(0); policy.reset()
        policy.offer(AgentTaskState(true, "connecting"), 10)
        assertFalse(policy.take(10)!!.showRecentOperation)
    }
}
