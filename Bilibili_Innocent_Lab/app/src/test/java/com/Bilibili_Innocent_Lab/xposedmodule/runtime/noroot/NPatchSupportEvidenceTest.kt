package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NPatchSupportEvidenceTest {
    val temporary = TemporaryFolder()

    fun exceptionMessagesPathsAndUnknownStackOwnersNeverLeaveProcess() {
        val error = IllegalStateException("token=secret /data/user/0/account https://private.example")
        error.stackTrace = arrayOf(StackTraceElement("com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry", "test", "secret-file", 7),
            StackTraceElement("secret.account", "token", "private", 1))
        val crash = NPatchSupportCodec.crash(error, 123)
        assertTrue(crash.moduleFrame)
        assertEquals(1, crash.frames.size)
        val raw = NPatchSupportCodec.encode(NPatchSupportEvidence("AVAILABLE", listOf(crash))).toString()
        for (secret in listOf("secret", "/data/", "https://", "private", "account")) assertFalse(raw, raw.contains(secret))
        assertEquals(listOf(crash), NPatchSupportCodec.decode(JSONObject(raw)).crashes)
    }

    fun mediaExtractsOnlyEventsAndSafeFrameLocations() {
        val result = NPatchSupportCodec.media("""
            [time][process:1;thread:2][E/NPatchCrash] Uncaught exception in user-secret token=secret
            java.lang.IllegalStateException: secret
                at com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry.test(secret.java:7)
            [time][process:1;thread:2][I/LSPosed] user https://private?token=secret
            [time][process:1;thread:2][I/Host] token=secret
        """.trimIndent())
        assertEquals(listOf("JAVA_CRASH", "com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry.test:7", "FRAMEWORK_EVENT"), result)
        assertEquals(40, NPatchSupportCodec.media((1..250).joinToString("\n") { "[I/NPatch] event secret" }).size)
    }

    fun boundedRotationAndMalformedRecordsAreExplicit() {
        val directory = temporary.newFolder()
        val store = NPatchCrashStore(directory)
        assertEquals("EMPTY", store.read().crashState)
        repeat(5) { store.save(IllegalArgumentException("secret-$it")) }
        assertEquals(3, store.read().crashes.size)
        assertEquals(3, directory.listFiles()!!.size)
        assertTrue(directory.listFiles()!!.all { it.length() <= NPatchCrashStore.MAX_FILE_BYTES })
        java.io.File(directory, "crash-0.json").writeText("{}")
        assertEquals("INVALID", store.read().crashState)
    }

    fun failedPersistenceAlwaysDelegatesOriginalErrorAndThreadOnce() {
        val error = IllegalStateException("original")
        val current = Thread.currentThread()
        var delegated = 0
        var saved = 0
        val previous = Thread.UncaughtExceptionHandler { thread, throwable ->
            assertSame(current, thread); assertSame(error, throwable); delegated++
        }
        val inner = NPatchCrashHandler.chained({ saved++; error("disk failure") }, previous)
        val outer = NPatchCrashHandler.chained({ saved++; error("disk failure") }, inner)
        outer.uncaughtException(current, error)
        assertEquals(1, delegated)
        assertEquals(1, saved)
        outer.uncaughtException(current, error)
        assertEquals(2, delegated)
        assertEquals(2, saved)
    }

    fun attachmentProtocolRejectsTextShapesAndOversizedFrames() {
        val valid = NPatchSupportCodec.encode(NPatchSupportEvidence())
        assertThrows(Exception::class.java) { NPatchSupportCodec.decode(JSONObject(valid.toString()).put("token", "secret")) }
        assertThrows(Exception::class.java) { NPatchSupportCodec.decode(JSONObject(valid.toString()).put("schema", "1")) }
        assertThrows(Exception::class.java) { NPatchSupportCodec.encode(NPatchSupportEvidence(mediaState = "AVAILABLE", mediaLines = listOf("secret"))) }
        assertThrows(Exception::class.java) { NPatchSupportCodec.encode(NPatchSupportEvidence("AVAILABLE", listOf(NPatchCrashEvidence(1, "OTHER", false, listOf("x".repeat(181)))))) }
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (NPatchSupportEvidenceTest) -> Unit) {
            val s = NPatchSupportEvidenceTest()
            s.temporary.create()
            try {
                block(s)
            } finally {
                s.temporary.delete()
            }
        }
        runIsolated { s -> s.exceptionMessagesPathsAndUnknownStackOwnersNeverLeaveProcess() }
        runIsolated { s -> s.mediaExtractsOnlyEventsAndSafeFrameLocations() }
        runIsolated { s -> s.boundedRotationAndMalformedRecordsAreExplicit() }
        runIsolated { s -> s.failedPersistenceAlwaysDelegatesOriginalErrorAndThreadOnce() }
        runIsolated { s -> s.attachmentProtocolRejectsTextShapesAndOversizedFrames() }
    
        RemoteScopeFlowCases().verifyAllCases()
    }
}

private class RemoteScopeFlowCases {
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

    fun `an already present scope is not requested again`() {
        val model = ScopeModel(listOf(host))
        assertEquals(ScopeRequestResult.AlreadyPresent, requestScopeFlow(model, listOf(host)) { true })
        assertEquals(0, model.requests)
    }

    fun `a missing scope is requested and confirmed by reading it back`() {
        val model = ScopeModel()
        assertEquals(ScopeRequestResult.Added, requestScopeFlow(model, listOf(host)) { true })
        assertEquals(1, model.requests)
        assertEquals(listOf(host), model.store)
    }

    fun `success does not depend on the callback arriving`() {
        val model = ScopeModel(callback = ScopeCallbackOutcome.NONE)
        assertEquals(ScopeRequestResult.Added, requestScopeFlow(model, listOf(host)) { true })
    }

    fun `an approved callback without a stored scope is not success`() {
        val model = ScopeModel(applies = false, callback = ScopeCallbackOutcome.APPROVED)
        assertEquals(ScopeRequestResult.Failure("scope_not_applied"), requestScopeFlow(model, listOf(host)) { true })
    }

    fun `a refusal from the framework is reported as such`() {
        val model = ScopeModel(applies = false, callback = ScopeCallbackOutcome.FAILED)
        assertEquals(ScopeRequestResult.Failure("scope_request_refused"), requestScopeFlow(model, listOf(host)) { true })
    }

    fun `a superseded intent never writes`() {
        val model = ScopeModel()
        assertNull(requestScopeFlow(model, listOf(host)) { false })
        assertEquals(0, model.requests)

        var calls = 0
        val late = ScopeModel()
        assertNull(requestScopeFlow(late, listOf(host)) { ++calls == 1 })
        assertEquals("read once, then stopped before the request", 0, late.requests)
    }

    fun `duplicate packages are collapsed and an empty request is rejected`() {
        val model = ScopeModel()
        assertEquals(ScopeRequestResult.Added, requestScopeFlow(model, listOf(host, host)) { true })
        assertEquals(listOf(host), model.store)
        assertThrows(IllegalArgumentException::class.java) { requestScopeFlow(ScopeModel(), emptyList()) { true } }
    }

    fun `other scope entries are left alone`() {
        val model = ScopeModel(listOf("com.example.other"))
        assertEquals(ScopeRequestResult.Added, requestScopeFlow(model, listOf(host)) { true })
        assertEquals(listOf("com.example.other", host), model.store)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (RemoteScopeFlowCases) -> Unit) {
            val s = RemoteScopeFlowCases()
            block(s)
        }
        runIsolated { s -> s.`an already present scope is not requested again`() }
        runIsolated { s -> s.`a missing scope is requested and confirmed by reading it back`() }
        runIsolated { s -> s.`success does not depend on the callback arriving`() }
        runIsolated { s -> s.`an approved callback without a stored scope is not success`() }
        runIsolated { s -> s.`a refusal from the framework is reported as such`() }
        runIsolated { s -> s.`a superseded intent never writes`() }
        runIsolated { s -> s.`duplicate packages are collapsed and an empty request is rejected`() }
        runIsolated { s -> s.`other scope entries are left alone`() }
    }
}
