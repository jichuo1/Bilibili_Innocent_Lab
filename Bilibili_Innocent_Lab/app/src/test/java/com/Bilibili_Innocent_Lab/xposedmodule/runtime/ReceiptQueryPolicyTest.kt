package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptQueryPolicyTest {
    fun `unhandled ordered broadcast is not a malformed receipt`() {
        val failure = ReceiptQueryPolicy.headerFailure(false, false, false)
        assertEquals(ReceiptQueryFailure.UNHANDLED, failure)
        assertTrue(ReceiptQueryPolicy.isUnavailable(failure))
    }
    fun `handled malformed and nonce mismatches remain validation errors`() {
        assertEquals(ReceiptQueryFailure.MALFORMED_RESPONSE, ReceiptQueryPolicy.headerFailure(true, false, false))
        assertEquals(ReceiptQueryFailure.NONCE_MISMATCH, ReceiptQueryPolicy.headerFailure(true, true, false))
        assertEquals(ReceiptQueryFailure.NONE, ReceiptQueryPolicy.headerFailure(true, true, true))
        assertFalse(ReceiptQueryPolicy.isUnavailable(ReceiptQueryFailure.NONCE_MISMATCH))
        assertFalse(ReceiptQueryPolicy.isUnavailable(ReceiptQueryFailure.DIGEST_MISMATCH))
    }
    fun `timeout and failed sending are unavailable not validation failures`() {
        assertTrue(ReceiptQueryPolicy.isUnavailable(ReceiptQueryFailure.TIMEOUT))
        assertTrue(ReceiptQueryPolicy.isUnavailable(ReceiptQueryFailure.SEND_FAILED))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (ReceiptQueryPolicyTest) -> Unit) {
            val s = ReceiptQueryPolicyTest()
            block(s)
        }
        runIsolated { s -> s.`unhandled ordered broadcast is not a malformed receipt`() }
        runIsolated { s -> s.`handled malformed and nonce mismatches remain validation errors`() }
        runIsolated { s -> s.`timeout and failed sending are unavailable not validation failures`() }
    
        ReceiptSessionGateCases().verifyAllCases()
        ReleaseNotesSourcePolicyCases().verifyAllCases()
        RoamingSettingsLaunchPolicyCases().verifyAllCases()
        ShellCommandRunnerCases().`returns child exit code after draining merged output`()
        StartupTraceCases().verifyAllCases()
    }
}

private class ReceiptSessionGateCases {
    fun `duplicate routes and reordered snapshots acknowledge without replacing newer data`() {
        var saved = 0L
        val stored = mutableListOf<Long>()
        for (sequence in listOf(2L, 1L, 2L, 3L)) {
            when (ReceiptSessionGate.sequenceDecision(saved, sequence)) {
                ReceiptSessionGate.SequenceDecision.STORE -> { stored += sequence; saved = sequence }
                ReceiptSessionGate.SequenceDecision.SUPERSEDED -> Unit
                ReceiptSessionGate.SequenceDecision.INVALID -> fail("valid sequence rejected")
            }
        }
        assertEquals(listOf(2L, 3L), stored)
        assertEquals(ReceiptSessionGate.SequenceDecision.INVALID, ReceiptSessionGate.sequenceDecision(saved, 0L))
        assertEquals(ReceiptSessionGate.SequenceDecision.INVALID, ReceiptSessionGate.sequenceDecision(saved, -1L))
        assertEquals(ReceiptSessionGate.SequenceDecision.STORE, ReceiptSessionGate.sequenceDecision(saved, 4L))
    }

    fun `publication sequence rejects zero duplicates and late snapshots`() {
        assertFalse(ReceiptSessionGate.isNewerSequence(0, 0))
        assertTrue(ReceiptSessionGate.isNewerSequence(0, 1))
        assertFalse(ReceiptSessionGate.isNewerSequence(3, 2))
        assertFalse(ReceiptSessionGate.isNewerSequence(3, 3))
        assertTrue(ReceiptSessionGate.isNewerSequence(3, 4))
    }
    fun `old process cannot replace a newer registered process`() {
        val gate = ReceiptSessionGate<String>()
        assertTrue(gate.accept(100, 1, "old"))
        val old = requireNotNull(gate.current())
        assertTrue(gate.accept(200, 2, "new"))
        assertFalse(gate.accept(100, 1, "old"))
        assertFalse(gate.matches(old))
        gate.remove(old)
        assertEquals("new", gate.current()?.endpoint)
    }

    fun `death removes current evidence and late registration cannot resurrect it`() {
        val gate = ReceiptSessionGate<String>()
        gate.accept(100, 1, "old")
        gate.remove(requireNotNull(gate.current()))
        assertNull(gate.current())
        assertFalse(gate.accept(100, 1, "old"))
        assertTrue(gate.accept(200, 2, "new"))
    }

    fun `same live endpoint is idempotent but equal start with another endpoint is rejected`() {
        val gate = ReceiptSessionGate<String>()
        assertFalse(gate.accept(0, 1, "bad"))
        assertFalse(gate.accept(100, 0, "bad"))
        assertTrue(gate.accept(100, 1, "a"))
        assertTrue(gate.accept(100, 1, "a"))
        assertFalse(gate.accept(100, 2, "b"))
        assertFalse(gate.accept(100, 1, "b"))
    }

    fun `module restart does not restore an online session from historical state`() {
        val old = ReceiptSessionGate<String>()
        old.accept(100, 1, "old")
        assertNull(ReceiptSessionGate<String>().current())
    }

    fun verifyAllCases() {
        fun runIsolated(block: (ReceiptSessionGateCases) -> Unit) {
            val s = ReceiptSessionGateCases()
            block(s)
        }
        runIsolated { s -> s.`duplicate routes and reordered snapshots acknowledge without replacing newer data`() }
        runIsolated { s -> s.`publication sequence rejects zero duplicates and late snapshots`() }
        runIsolated { s -> s.`old process cannot replace a newer registered process`() }
        runIsolated { s -> s.`death removes current evidence and late registration cannot resurrect it`() }
        runIsolated { s -> s.`same live endpoint is idempotent but equal start with another endpoint is rejected`() }
        runIsolated { s -> s.`module restart does not restore an online session from historical state`() }
    }
}

private class ReleaseNotesSourcePolicyCases {

    fun `normalizes line endings and removes unsafe controls without flattening markdown`() {
        val result = ReleaseNotesSourcePolicy.prepare(
            "## Changes\r\n\r\n- **Fixed** item\u0000\u0007\r\n- `code`"
        )

        assertEquals("## Changes\n\n- **Fixed** item\n- `code`", result.markdown)
        assertFalse(result.truncated)
    }

    fun `truncates at a complete line and closes an open code fence`() {
        val result = ReleaseNotesSourcePolicy.prepare(
            buildString {
                append("## Changes\n\n```kotlin\n")
                repeat(30) { append("println(\"line-$it\")\n") }
                append("```\n")
            },
            maxLength = 160
        )

        assertTrue(result.truncated)
        assertTrue(result.markdown.startsWith("## Changes\n\n```kotlin\n"))
        assertTrue(result.markdown.endsWith("```"))
    }

    fun `does not split a unicode surrogate pair at the hard boundary`() {
        val prefix = "x".repeat(63)
        val result = ReleaseNotesSourcePolicy.prepare(
            prefix + "😀" + "tail".repeat(20),
            maxLength = 64
        )

        assertTrue(result.truncated)
        assertEquals(prefix, result.markdown)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (ReleaseNotesSourcePolicyCases) -> Unit) {
            val s = ReleaseNotesSourcePolicyCases()
            block(s)
        }
        runIsolated { s -> s.`normalizes line endings and removes unsafe controls without flattening markdown`() }
        runIsolated { s -> s.`truncates at a complete line and closes an open code fence`() }
        runIsolated { s -> s.`does not split a unicode surrogate pair at the hard boundary`() }
    }
}

private class RoamingSettingsLaunchPolicyCases {
    private val hostPackage = "tv.danmaku.bili"
    private val hostUid = 10_123
    private val nonce = "4d8d066a-2b31-49ca-a1f2-844576f7e756"
    private val otherNonce = "6a43ff24-083f-40ad-8f29-75a2f6491131"

    fun freshnessIncludesTheFiveSecondBoundary() {
        assertTrue(RoamingSettingsLaunchPolicy.isFresh(1_000L, 1_000L))
        assertTrue(RoamingSettingsLaunchPolicy.isFresh(1_000L, 6_000L))
        assertFalse(RoamingSettingsLaunchPolicy.isFresh(1_000L, 6_001L))
    }

    fun invalidAndFutureTimestampsNeverBecomeFreshThroughOverflow() {
        assertFalse(RoamingSettingsLaunchPolicy.isFresh(0L, 0L))
        assertFalse(RoamingSettingsLaunchPolicy.isFresh(-1L, 1L))
        assertFalse(RoamingSettingsLaunchPolicy.isFresh(1_000L, 999L))
        assertFalse(RoamingSettingsLaunchPolicy.isFresh(Long.MAX_VALUE, Long.MIN_VALUE))
        assertFalse(RoamingSettingsLaunchPolicy.isFresh(1L, Long.MAX_VALUE))
        assertTrue(RoamingSettingsLaunchPolicy.isFresh(Long.MAX_VALUE - 5_000L, Long.MAX_VALUE))
    }

    fun oldAndroidStillRequiresTheHostCreatorIdentity() {
        for (sdk in listOf(27, 30, 31, 33)) {
            assertTrue(accepts(sdk = sdk, sentFromPackage = null, sentFromUid = -1))
            assertFalse(accepts(sdk = sdk, creatorPackage = "other.app", sentFromPackage = null, sentFromUid = -1))
            assertFalse(accepts(sdk = sdk, creatorUid = hostUid + 1, sentFromPackage = null, sentFromUid = -1))
        }
    }

    fun missingOrInvalidExpectedAndCreatorIdentitiesAreRejected() {
        assertFalse(accepts(expectedPackage = ""))
        assertFalse(accepts(expectedPackage = " "))
        assertFalse(accepts(expectedUid = -1, creatorUid = -1, sentFromUid = -1))
        assertFalse(accepts(expectedUid = 0, creatorUid = 0, sentFromUid = 0))
        assertFalse(accepts(creatorPackage = null))
        assertFalse(accepts(creatorUid = -1))
    }

    fun modernAndroidRequiresTheSystemSenderToMatchTheHostProof() {
        for (sdk in listOf(34, 35, 36, 37)) {
            assertTrue(accepts(sdk = sdk))
            assertFalse(accepts(sdk = sdk, sentFromPackage = null))
            assertFalse(accepts(sdk = sdk, sentFromUid = -1))
            assertFalse(accepts(sdk = sdk, sentFromPackage = "other.app"))
            assertFalse(accepts(sdk = sdk, sentFromUid = hostUid + 1))
            assertFalse(accepts(sdk = sdk, creatorPackage = "other.app"))
            assertFalse(accepts(sdk = sdk, creatorUid = hostUid + 1))
        }
    }

    fun senderModesRespectThePlatformApiBoundaries() {
        assertEquals(RoamingSettingsLaunchPolicy.SenderMode.PLATFORM_DEFAULT, RoamingSettingsLaunchPolicy.senderMode(27))
        assertEquals(RoamingSettingsLaunchPolicy.SenderMode.PLATFORM_DEFAULT, RoamingSettingsLaunchPolicy.senderMode(33))
        assertEquals(RoamingSettingsLaunchPolicy.SenderMode.ALLOW_VISIBLE_CALLER, RoamingSettingsLaunchPolicy.senderMode(34))
        assertEquals(RoamingSettingsLaunchPolicy.SenderMode.ALLOW_VISIBLE_CALLER, RoamingSettingsLaunchPolicy.senderMode(35))
        assertEquals(RoamingSettingsLaunchPolicy.SenderMode.ALLOW_IF_VISIBLE, RoamingSettingsLaunchPolicy.senderMode(36))
        assertEquals(RoamingSettingsLaunchPolicy.SenderMode.ALLOW_IF_VISIBLE, RoamingSettingsLaunchPolicy.senderMode(37))
    }

    fun aMatchingFreshVisibleResponseCanOnlyBeConsumedOnce() {
        val lease = RoamingSettingsLaunchLease(nonce, 1_000L)
        assertTrue(lease.consume(nonce, 6_000L, true))
        assertFalse(lease.consume(nonce, 6_000L, true))
        assertFalse(lease.close())
    }

    fun unrelatedOrMissingResponseNoncesDoNotConsumeTheCurrentClick() {
        val lease = RoamingSettingsLaunchLease(nonce, 1_000L)
        assertFalse(lease.consume(null, 1_000L, true))
        assertFalse(lease.consume(otherNonce, 1_000L, true))
        assertFalse(lease.consume("invalid", 1_000L, true))
        assertTrue(lease.consume(nonce, 1_000L, true))
    }

    fun leasesRejectNonCanonicalOrMalformedUuidNonces() {
        for (invalid in listOf("", "nonce", "a".repeat(36), "1-2-3-4-5", nonce.replace('-', '_'), "$nonce ")) {
            val lease = RoamingSettingsLaunchLease(invalid, 1_000L)
            assertFalse(lease.consume(invalid, 1_000L, true))
            assertFalse(lease.close())
        }
        val upper = nonce.uppercase()
        assertTrue(RoamingSettingsLaunchLease(upper, 1_000L).consume(upper, 1_000L, true))
    }

    fun expiredOrFutureResponsesCloseTheLeasePermanently() {
        val expired = RoamingSettingsLaunchLease(nonce, 1_000L)
        assertFalse(expired.consume(nonce, 6_001L, true))
        assertFalse(expired.consume(nonce, 1_000L, true))
        assertFalse(expired.close())
        val future = RoamingSettingsLaunchLease(nonce, 1_000L)
        assertFalse(future.consume(nonce, 999L, true))
        assertFalse(future.consume(nonce, 1_000L, true))
    }

    fun losingVisibilityCannotBeUndoneByReturningToTheApp() {
        val lease = RoamingSettingsLaunchLease(nonce, 1_000L)
        assertFalse(lease.consume(nonce, 1_000L, false))
        assertFalse(lease.consume(nonce, 1_001L, true))
        assertFalse(lease.close())
    }

    fun closingBeforeTheResponseIsIdempotentAndFinal() {
        val lease = RoamingSettingsLaunchLease(nonce, 1_000L)
        assertTrue(lease.close())
        assertFalse(lease.close())
        assertFalse(lease.consume(nonce, 1_000L, true))
    }

    fun racingResponsesLaunchAtMostOnce() {
        val lease = RoamingSettingsLaunchLease(nonce, 1_000L)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val futures = List(8) {
                pool.submit(Callable {
                    ready.countDown()
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    lease.consume(nonce, 1_000L, true)
                })
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertEquals(1, futures.count { it.get(5, TimeUnit.SECONDS) })
        } finally {
            start.countDown()
            pool.shutdownNow()
        }
    }

    fun closingAndConsumingCompeteForTheSameLease() {
        val lease = RoamingSettingsLaunchLease(nonce, 1_000L)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val consume = pool.submit(Callable {
                assertTrue(start.await(5, TimeUnit.SECONDS))
                lease.consume(nonce, 1_000L, true)
            })
            val close = pool.submit(Callable {
                assertTrue(start.await(5, TimeUnit.SECONDS))
                lease.close()
            })
            start.countDown()
            assertEquals(1, listOf(consume.get(5, TimeUnit.SECONDS), close.get(5, TimeUnit.SECONDS)).count { it })
            assertFalse(lease.consume(nonce, 1_000L, true))
        } finally {
            start.countDown()
            pool.shutdownNow()
        }
    }

    fun filteredCreatorRequiresAnAlreadyVerifiedPrivateReply() {
        val modulePackage = "com.example.module"
        assertTrue(RoamingSettingsLaunchPolicy.acceptsPrivateReplyCreator(modulePackage, null, true))
        assertFalse(RoamingSettingsLaunchPolicy.acceptsPrivateReplyCreator(modulePackage, null, false))
        assertFalse(RoamingSettingsLaunchPolicy.acceptsPrivateReplyCreator(modulePackage, modulePackage, false))
    }

    fun privateReplyStillRejectsAnExplicitlyWrongCreator() {
        val modulePackage = "com.example.module"
        assertTrue(RoamingSettingsLaunchPolicy.acceptsPrivateReplyCreator(modulePackage, modulePackage, true))
        assertFalse(RoamingSettingsLaunchPolicy.acceptsPrivateReplyCreator(modulePackage, "com.example.other", true))
        assertFalse(RoamingSettingsLaunchPolicy.acceptsPrivateReplyCreator(modulePackage, "", true))
        assertFalse(RoamingSettingsLaunchPolicy.acceptsPrivateReplyCreator("", null, true))
    }

    private fun accepts(
        expectedPackage: String = hostPackage,
        expectedUid: Int = hostUid,
        creatorPackage: String? = hostPackage,
        creatorUid: Int = hostUid,
        sdk: Int = 34,
        sentFromPackage: String? = hostPackage,
        sentFromUid: Int = hostUid
    ): Boolean = RoamingSettingsLaunchPolicy.acceptsCaller(
        expectedPackage, expectedUid, creatorPackage, creatorUid, sdk, sentFromPackage, sentFromUid
    )

    fun verifyAllCases() {
        fun runIsolated(block: (RoamingSettingsLaunchPolicyCases) -> Unit) {
            val s = RoamingSettingsLaunchPolicyCases()
            block(s)
        }
        runIsolated { s -> s.freshnessIncludesTheFiveSecondBoundary() }
        runIsolated { s -> s.invalidAndFutureTimestampsNeverBecomeFreshThroughOverflow() }
        runIsolated { s -> s.oldAndroidStillRequiresTheHostCreatorIdentity() }
        runIsolated { s -> s.missingOrInvalidExpectedAndCreatorIdentitiesAreRejected() }
        runIsolated { s -> s.modernAndroidRequiresTheSystemSenderToMatchTheHostProof() }
        runIsolated { s -> s.senderModesRespectThePlatformApiBoundaries() }
        runIsolated { s -> s.aMatchingFreshVisibleResponseCanOnlyBeConsumedOnce() }
        runIsolated { s -> s.unrelatedOrMissingResponseNoncesDoNotConsumeTheCurrentClick() }
        runIsolated { s -> s.leasesRejectNonCanonicalOrMalformedUuidNonces() }
        runIsolated { s -> s.expiredOrFutureResponsesCloseTheLeasePermanently() }
        runIsolated { s -> s.losingVisibilityCannotBeUndoneByReturningToTheApp() }
        runIsolated { s -> s.closingBeforeTheResponseIsIdempotentAndFinal() }
        runIsolated { s -> s.racingResponsesLaunchAtMostOnce() }
        runIsolated { s -> s.closingAndConsumingCompeteForTheSameLease() }
        runIsolated { s -> s.filteredCreatorRequiresAnAlreadyVerifiedPrivateReply() }
        runIsolated { s -> s.privateReplyStillRejectsAnExplicitlyWrongCreator() }
    }
}

private class ShellCommandRunnerCases {

    fun `returns child exit code after draining merged output`() {
        val command = if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
            listOf("cmd.exe", "/c", "echo stdout & echo stderr 1>&2 & exit /b 7")
        } else {
            listOf("/bin/sh", "-c", "echo stdout; echo stderr >&2; exit 7")
        }

        assertEquals(7, ShellCommandRunner.run(command, timeoutMs = 5_000L))
    }
}

private class StartupTraceCases {
    fun nestedTimesRetriesAndFailuresPreserveOriginalAction() {
        var tick = 0L
        val recorder = StartupTraceRecorder(clock = { tick }, mainThread = { true })
        recorder.measure("entry") { tick = 10 }
        recorder.attempt()
        val failure = IllegalStateException("private message must not be exported")
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            recorder.measure("install") {
                tick = 20
                recorder.measure("feature.copy") { tick = 50 }
                tick = 70
                throw failure
            }
        })
        val result = StartupTraceRecorder.validate(recorder.json())
        assertFalse(result.toString().contains("private message"))
        val rows = result.getJSONArray("rows")
        val install = (0 until rows.length()).map(rows::getJSONObject).single { it.getString("id") == "install" }
        assertEquals(60L, install.getLong("ns"))
        assertEquals(30L, install.getLong("ownNs"))
        assertEquals(1, install.getInt("failures"))
        assertEquals(1, install.getInt("attempt"))
        recorder.attempt()
        recorder.measure("install") { tick = 80 }
        assertEquals(4, StartupTraceRecorder.validate(recorder.json()).getJSONArray("rows").length())
    }

    fun repeatedCallsAggregateAndCapacityIsBoundedPerAttempt() {
        var tick = 0L
        val recorder = StartupTraceRecorder(clock = { tick++ }, mainThread = { false })
        repeat(400) { recorder.measure("registration") {} }
        repeat(128) { recorder.measure("phase.$it") {} }
        val root = StartupTraceRecorder.validate(recorder.json())
        assertEquals(128, root.getJSONArray("rows").length())
        assertEquals(1, root.getInt("dropped"))
        assertEquals(400, root.getJSONArray("rows").getJSONObject(0).getInt("calls"))
        repeat(4) { recorder.attempt() }
        recorder.measure("install") {}
        assertEquals(2, StartupTraceRecorder.validate(recorder.json()).getJSONArray("rows").getJSONObject(128).getInt("attempt"))
    }

    fun codecRejectsExtraFieldsTypesAndInvalidTotals() {
        val recorder = StartupTraceRecorder()
        recorder.measure("install") {}
        val raw = recorder.json()
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("secret", "value") }, { it.put("dropped", "0") },
            { it.getJSONArray("rows").getJSONObject(0).put("id", "host#member") },
            { it.getJSONArray("rows").getJSONObject(0).put("ownNs", Long.MAX_VALUE) },
            { it.getJSONArray("rows").getJSONObject(0).put("failures", 2) },
            { it.getJSONArray("rows").getJSONObject(0).put("main", "true") }
        )
        mutations.forEach { mutate -> assertThrows(Exception::class.java) {
            StartupTraceRecorder.validate(JSONObject(raw).apply(mutate).toString())
        } }
        assertThrows(Exception::class.java) { StartupTraceRecorder.validate("$raw trailing") }
    }

    fun explicitRootsExcludeBusinessLookupsAndRestoreScopeAfterFailure() {
        val recorder = StartupTraceRecorder(roots = setOf("entry"))
        recorder.measure("lookup.value") {}
        assertThrows(IllegalStateException::class.java) {
            recorder.measure("entry") { recorder.measure("lookup.value") { error("failure") } }
        }
        recorder.measure("lookup.value") {}
        val rows = StartupTraceRecorder.validate(recorder.json()).getJSONArray("rows")
        assertEquals(2, rows.length())
        val lookup = (0 until rows.length()).map(rows::getJSONObject).single { it.getString("id") == "lookup.value" }
        assertEquals(1, lookup.getInt("calls"))
        assertEquals("entry", lookup.getJSONArray("parents").getString(0))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (StartupTraceCases) -> Unit) {
            val s = StartupTraceCases()
            block(s)
        }
        runIsolated { s -> s.nestedTimesRetriesAndFailuresPreserveOriginalAction() }
        runIsolated { s -> s.repeatedCallsAggregateAndCapacityIsBoundedPerAttempt() }
        runIsolated { s -> s.codecRejectsExtraFieldsTypesAndInvalidTotals() }
        runIsolated { s -> s.explicitRootsExcludeBusinessLookupsAndRestoreScopeAfterFailure() }
    }
}
