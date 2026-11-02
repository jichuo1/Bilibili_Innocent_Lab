package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NPatchSupportEvidenceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun exceptionMessagesPathsAndUnknownStackOwnersNeverLeaveProcess() {
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

    @Test fun mediaExtractsOnlyEventsAndSafeFrameLocations() {
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

    @Test fun boundedRotationAndMalformedRecordsAreExplicit() {
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

    @Test fun failedPersistenceAlwaysDelegatesOriginalErrorAndThreadOnce() {
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

    @Test fun attachmentProtocolRejectsTextShapesAndOversizedFrames() {
        val valid = NPatchSupportCodec.encode(NPatchSupportEvidence())
        assertThrows(Exception::class.java) { NPatchSupportCodec.decode(JSONObject(valid.toString()).put("token", "secret")) }
        assertThrows(Exception::class.java) { NPatchSupportCodec.decode(JSONObject(valid.toString()).put("schema", "1")) }
        assertThrows(Exception::class.java) { NPatchSupportCodec.encode(NPatchSupportEvidence(mediaState = "AVAILABLE", mediaLines = listOf("secret"))) }
        assertThrows(Exception::class.java) { NPatchSupportCodec.encode(NPatchSupportEvidence("AVAILABLE", listOf(NPatchCrashEvidence(1, "OTHER", false, listOf("x".repeat(181)))))) }
    }
}
