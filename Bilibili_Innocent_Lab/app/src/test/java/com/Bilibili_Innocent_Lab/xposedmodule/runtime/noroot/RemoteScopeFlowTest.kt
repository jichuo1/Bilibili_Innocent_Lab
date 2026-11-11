package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class RemoteScopeFlowTest {
    private val host = "tv.danmaku.bili"

    /** 复刻上游 `requestScope`：无确认、直接写入；`approve` 控制写入与回调。 */
    private class ScopeModel(
        initial: List<String> = emptyList(),
        var applies: Boolean = true,
        var callback: ScopeCallbackOutcome = ScopeCallbackOutcome.APPROVED
    ) : RemoteScopeChannel {
        val store = initial.toMutableList()
        var scopeReads = 0
        var requests = 0

        override fun scope(): List<String> {
            scopeReads++
            return store.toList()
        }

        override fun requestScope(packages: List<String>): ScopeCallbackOutcome {
            requests++
            if (applies) packages.forEach { if (it !in store) store += it }
            return callback
        }
    }

    @Test fun `an already present scope is not requested again`() {
        val model = ScopeModel(listOf(host))
        assertEquals(ScopeRequestResult.AlreadyPresent, requestScopeFlow(model, listOf(host)) { true })
        assertEquals(0, model.requests)
    }

    @Test fun `a missing scope is requested and confirmed by reading it back`() {
        val model = ScopeModel()
        assertEquals(ScopeRequestResult.Added, requestScopeFlow(model, listOf(host)) { true })
        assertEquals(1, model.requests)
        assertEquals(listOf(host), model.store)
    }

    @Test fun `success does not depend on the callback arriving`() {
        val model = ScopeModel(callback = ScopeCallbackOutcome.NONE)
        assertEquals(ScopeRequestResult.Added, requestScopeFlow(model, listOf(host)) { true })
    }

    @Test fun `an approved callback without a stored scope is not success`() {
        val model = ScopeModel(applies = false, callback = ScopeCallbackOutcome.APPROVED)
        assertEquals(ScopeRequestResult.Failure("scope_not_applied"), requestScopeFlow(model, listOf(host)) { true })
    }

    @Test fun `a refusal from the framework is reported as such`() {
        val model = ScopeModel(applies = false, callback = ScopeCallbackOutcome.FAILED)
        assertEquals(ScopeRequestResult.Failure("scope_request_refused"), requestScopeFlow(model, listOf(host)) { true })
    }

    @Test fun `a superseded intent never writes`() {
        val model = ScopeModel()
        assertNull(requestScopeFlow(model, listOf(host)) { false })
        assertEquals(0, model.requests)

        var calls = 0
        val late = ScopeModel()
        assertNull(requestScopeFlow(late, listOf(host)) { ++calls == 1 })
        assertEquals("read once, then stopped before the request", 0, late.requests)
    }

    @Test fun `duplicate packages are collapsed and an empty request is rejected`() {
        val model = ScopeModel()
        assertEquals(ScopeRequestResult.Added, requestScopeFlow(model, listOf(host, host)) { true })
        assertEquals(listOf(host), model.store)
        assertThrows(IllegalArgumentException::class.java) { requestScopeFlow(ScopeModel(), emptyList()) { true } }
    }

    @Test fun `other scope entries are left alone`() {
        val model = ScopeModel(listOf("com.example.other"))
        assertEquals(ScopeRequestResult.Added, requestScopeFlow(model, listOf(host)) { true })
        assertEquals(listOf("com.example.other", host), model.store)
    }
}
