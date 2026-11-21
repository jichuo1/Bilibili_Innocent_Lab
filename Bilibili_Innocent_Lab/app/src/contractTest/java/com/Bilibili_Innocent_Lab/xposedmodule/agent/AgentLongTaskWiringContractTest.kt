package com.Bilibili_Innocent_Lab.xposedmodule.agent

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.*
import org.junit.Test

/** 纯策略不能证明 Android 监听器接线；长期所有权与单操作期限必须保持不同边界。 */
class AgentLongTaskWiringContractTest {
    fun persistentInputAndAccessibilityListenersUseRenewableTaskOwnership() {
        val window = SourceContract.read("agent/host/HostAgentWindow.kt")
        val input = window.after("private inner class TaskCallback").before("// 此 View 注入宿主窗口")
        assertTrue(input.contains("ownedCallback.get() === this"))
        assertTrue(input.contains("ownershipGeneration == lease.generation"))
        assertFalse(input.contains("session.isTaskActive(lease, now())"))
        assertFalse(input.contains("session.isActive(lease, now())"))
        assertFalse(window.contains("AccessibilityStateChangeListener"))
        val service = SourceContract.read("agent/AgentAccessibilityService.kt")
        assertTrue(service.contains("AgentController.actionAuthorized(task)"))
        assertTrue(service.contains("if (AgentController.usesAccessibility())"))
        assertTrue(service.contains("AgentController.cancel(this, \"accessibility_disconnected\")"))
    }

    fun serviceLifetimeSurvivesRenewalWhileActionsAndResponsesKeepTheirOwnDeadline() {
        val runtime = SourceContract.read("agent/host/HostAgentRuntime.kt")
        val connected = runtime.after("override fun onServiceConnected").before("override fun onServiceDisconnected")
        assertTrue(connected.contains("session.isTaskActive(lease, now())"))
        assertFalse(connected.contains("session.isActive(lease, now())"))
        val mainAction = runtime.after("private fun <T> onMain(").before("private fun stop(")
        assertTrue(mainAction.contains("session.whileActive(lease, now())"))
        val delivery = runtime.after("private fun deliver(").before("private data class Binding")
        assertTrue(delivery.contains("session.isActive(lease, now())"))
        val window = SourceContract.read("agent/host/HostAgentWindow.kt")
        val capture = window.after("fun capture(").before("private data class Capture")
        assertTrue(capture.contains("session.isActive(lease, now())"))
    }

    fun renewableExpiryReadsLatestTaskDeadlineAndOldGenerationCleanupIsScoped() {
        val runtime = SourceContract.read("agent/host/HostAgentRuntime.kt")
        val expiry = runtime.after("private fun scheduleExpiry(").before("private fun bind(")
        assertTrue(expiry.contains("session.current(now())"))
        assertTrue(expiry.contains("current.generation != lease.generation"))
        assertTrue(expiry.contains("current.deadline - now()"))
        assertFalse(expiry.contains("lease.deadline - now()"))
        val release = runtime.after("private fun release(").before("private fun deliver(")
        assertTrue(release.contains("it.generation == lease.generation"))
        assertTrue(release.contains("cacheGeneration == lease.generation"))
        assertTrue(release.contains("windows.releaseTask(lease)"))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (AgentLongTaskWiringContractTest) -> Unit) {
            val s = AgentLongTaskWiringContractTest()
            block(s)
        }
        runIsolated { s -> s.persistentInputAndAccessibilityListenersUseRenewableTaskOwnership() }
        runIsolated { s -> s.serviceLifetimeSurvivesRenewalWhileActionsAndResponsesKeepTheirOwnDeadline() }
        runIsolated { s -> s.renewableExpiryReadsLatestTaskDeadlineAndOldGenerationCleanupIsScoped() }
    }
}
