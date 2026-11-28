package com.Bilibili_Innocent_Lab.xposedmodule.agent

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 宿主原生代理权限、凭据隔离、窗口接管撤回与视频统计 DTO 接线契约。 */
class HostAgentRuntimeWiringContractTest {

    @Test
    fun hostAuthorizationAccountGuardsCloseReleaseAndVideoMetricsWiringHold() {
        val hook = SourceContract.read("hook/HookEntry.kt")
        assertTrue(hook.contains("config.values[FeaturePreferences.BILI_ACCESS_KEY_AUTHORIZED] == true"))
        assertTrue(hook.contains("activeHookConfig.get()?.getBoolean(FeaturePreferences.BILI_ACCESS_KEY_AUTHORIZED, false) == true"))

        val host = SourceContract.read("agent/host/HostAgentRuntime.kt")
        val page = host.after("private fun readPage(").before("private fun stamp(")
        assertTrue(page.indexOf("if (personal) requireAccount(lease)") < page.indexOf("val page = when"))
        assertTrue(page.after("val page = when").contains("if (personal) requireAccount(lease)"))
        assertFalse(host.contains("getAccessKey"))
        assertFalse(host.contains("getAccessToken"))
        val access = SourceContract.read("agent/host/HostAgentAccountAccess.kt")
        assertTrue(access.contains("probe.state(context)"))
        assertFalse(access.contains("getAccessKey"))
        val delivery = host.after("private fun deliver(").before("private data class Binding(")
        assertTrue(delivery.contains("if (request.operation in PERSONAL_READS) requireAccount(lease)"))
        assertTrue(delivery.before("request.callback.transact").contains("accountRefusal(lease)"))

        val closing = host.after("} else if (closing) {").before("} else {\n                            try")
        assertTrue(closing.indexOf("release(lease)") < closing.indexOf("native_window_released"))
        assertTrue(closing.contains("windows.canHandoff(lease)"))
        assertTrue(closing.contains("handoff && released"))
        val window = SourceContract.read("agent/host/HostAgentWindow.kt")
        val callback = window.after("private inner class TaskCallback").before("fun showStop(")
        assertTrue(callback.contains("ownershipGeneration == lease.generation"))
        assertFalse(callback.contains("session.isTaskActive"))
        val metrics = host.after("\"get_video_metrics\" -> {").before("\"get_video_details\" -> {")
        assertTrue(metrics.contains("detail.query(id, timeout(lease))"))
        assertFalse(metrics.contains("detailCache"))

        val rpc = SourceContract.read("agent/host/HostAgentRpc.kt")
        val detail = rpc.after("internal class HostAgentDetailQuery").before("internal class HostAgentVideoStatistics")
        assertTrue(detail.contains("HostAgentVideoStatistics.resolve(getArc.returnType)"))
        assertTrue(detail.contains("val canReadStatistics: Boolean get() = statistics != null"))
        assertTrue(detail.contains("statistics?.read(arc) ?: HostAgentVideoStatistics.unknown()"))
        val statistics = rpc.after("internal class HostAgentVideoStatistics").before("internal fun hostGetter")
        assertFalse(statistics.contains("getText"))
        assertFalse(statistics.contains("getPureText"))
        assertFalse(statistics.contains("hostText("))
        assertTrue(host.contains("put(\"get_video_metrics\", rpc.details?.canReadStatistics == true)"))
    }

    @Test
    fun ownedWindowContextFocusLossTakeoverAndIdentityStampingRemainStrict() {
        val host = SourceContract.read("agent/host/HostAgentRuntime.kt")
        val state = host.after("\"get_host_state\" ->").before("\"search_videos\" ->")
        assertTrue(state.indexOf("stamp(state)") < state.indexOf("session.remember"))
        assertTrue(state.contains("state.optBoolean(\"foreground\") || state.optBoolean(\"native_read_context\")"))
        assertTrue(state.contains("state.optBoolean(\"interaction_guard\")"))
        val goal = SourceContract.read("agent/AgentGoalEvidence.kt")
        val identity = goal.after("if (operation == \"get_host_state\" && data.optString(\"source\")").before("currentVideo =")
        assertTrue(identity.contains("== \"host_rpc\""))
        assertTrue(identity.contains("data.opt(\"foreground\") == true || data.opt(\"native_read_context\") == true"))
        assertTrue(identity.contains("data.opt(\"interaction_guard\") == true"))
        val navigation = SourceContract.read("agent/AgentNavigationEvidence.kt")
        assertTrue(navigation.contains("data.opt(\"foreground\") == true"))
        assertFalse(navigation.contains("native_read_context"))

        val window = SourceContract.read("agent/host/HostAgentWindow.kt")
        val activity = window.after("fun activity(): Activity?").before("private fun ownedActivity(")
        assertTrue(activity.contains("it.hasWindowFocus()"))
        val owned = window.after("private fun ownedActivity(").before("private fun beginTask(")
        for (guard in listOf(
            "resumed.get()", "ownershipGeneration == lease.generation", "ownedWindow.get() === it.window",
            "it.window?.callback === callback", "callback.lease.generation == lease.generation", "control.canUseWindow(",
            "it.window?.decorView?.isShown == true", "it.hasWindowFocus()"
        )) assertTrue(owned.contains(guard))
        val renew = window.after("fun awaitTaskContext(").before("private fun awaitMainCondition(")
        assertTrue(renew.contains("ownedActivity(lease, readOnly = true)"))
        val read = window.after("fun requireTaskContext(").before("private fun requireFocusedTaskContext(")
        assertTrue(read.contains("ownedActivity(lease, readOnly = true)"))
        val navigate = window.after("fun prepareNavigation(").before("fun navigationFailed(")
        assertTrue(navigate.contains("requireFocusedTaskContext(lease)"))
        val focused = window.after("private fun requireFocusedTaskContext(").before("private fun requireObservedContext(")
        assertTrue(focused.contains("ownedActivity(lease, readOnly = false)"))
        val handoff = window.after("fun canHandoff(").before("fun releaseTask(")
        assertTrue(handoff.contains("ownedActivity(lease, readOnly = false)"))
        val capture = window.after("fun capture(").before("private data class Capture")
        assertTrue(capture.contains("val activity = activity() ?: throw HostAgentFailure(\"host_not_foreground\")"))
        val input = window.after("private inner class TaskCallback").before("fun showStop(")
        assertTrue(input.contains("ownedActivity(lease, readOnly = true) == null) takeOver()"))
        assertTrue(input.contains("if (event.actionMasked == MotionEvent.ACTION_DOWN) takeOver()"))
        assertTrue(input.contains("if (event.action == KeyEvent.ACTION_DOWN) takeOver()"))
        assertTrue(input.contains("MotionEvent.ACTION_SCROLL || event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS) takeOver()"))
        val pause = window.after("override fun onActivityPaused(").before("override fun onActivityDestroyed(")
        assertTrue(pause.contains("!control.pause(lease.generation, windowSerial, now())"))
        assertTrue(pause.contains("resumed.clear()"))
        val windowState = window.after("fun state(").before("private data class Page")
        assertTrue(windowState.contains("put(\"foreground\", current.hasWindowFocus())"))
    }
}
