package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy.LocalVirtualLibrary.CorruptLibraryException
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy.LocalVirtualLibrary.Follow
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy.LocalVirtualLibrary.FollowGroup
import java.util.TreeMap
import kmpfixture.modern.Handler
import kmpfixture.modern.KMossException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IgnetHttpSanitizerTest {
    private val identity = IncognitoSession.Identity(1, "session-buvid", "session-id")
    private val noSign: (Map<String, String>) -> String = { error("must not re-sign") }

    fun targetIsHostAndEncodedPath() {
        assertEquals(IgnetHttpSanitizer.Target("app.bilibili.com", "/x/resource/show/tab/v2"),
            IgnetHttpSanitizer.target("https://APP.bilibili.com./x/resource/show/tab/v2?access_key=&a=1#f"))
        assertEquals(IgnetHttpSanitizer.Target("dataflow.biliapi.com", "/"),
            IgnetHttpSanitizer.target("https://user@dataflow.biliapi.com:443"))
        assertThrows(IllegalArgumentException::class.java) { IgnetHttpSanitizer.target("file:///sdcard/a") }
        assertThrows(IllegalArgumentException::class.java) { IgnetHttpSanitizer.target("https:///x") }
    }

    fun cleanUrlIsReturnedUntouched() {
        val url = "https://api.bilibili.com/x/v2/view?aid=170001&keyword=a%20b&sign=abc"
        assertSame(url, IgnetHttpSanitizer.url(url, identity, noSign))
        val bare = "https://api.bilibili.com/x/v2/view"
        assertSame(bare, IgnetHttpSanitizer.url(bare, identity, noSign))
    }

    fun accountParametersAreDroppedAndDeviceIdsReplaced() {
        assertEquals("https://app.bilibili.com/x/tab?a=1&buvid=session-buvid#top",
            IgnetHttpSanitizer.url("https://app.bilibili.com/x/tab?access_key=real&a=1&buvid=real-device#top", identity, noSign))
        assertEquals("https://app.bilibili.com/x/tab",
            IgnetHttpSanitizer.url("https://app.bilibili.com/x/tab?access_key=&csrf=t", identity, noSign))
    }

    fun changedSignedQueryIsResignedFromDecodedParameters() {
        var seen: Map<String, String>? = null
        val out = IgnetHttpSanitizer.url("https://app.bilibili.com/x/tab?access_key=real&keyword=a%20b&sign=old", identity) {
            seen = it
            "keyword=a%20b&sign=new"
        }
        assertEquals(mapOf("keyword" to "a b"), seen)
        assertEquals("https://app.bilibili.com/x/tab?keyword=a%20b&sign=new", out)
    }

    fun malformedEscapesFailClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            IgnetHttpSanitizer.url("https://app.bilibili.com/x?access_key=%zz", identity, noSign)
        }
    }

    fun headersAreSealedCaseInsensitivelyInPlace() {
        val map: MutableMap<String, String> = TreeMap(String.CASE_INSENSITIVE_ORDER)
        map.putAll(mapOf("Authorization" to "identify_v1 real", "Cookie" to "SESSDATA=real", "x-bili-mid" to "42",
            "Buvid" to "real-device", "session_id" to "old", "x-bili-metadata-bin" to "AAAA",
            "is-kmp-neuron" to "1", "User-Agent" to "ua"))
        IgnetHttpSanitizer.headers(map, identity)
        assertEquals(mapOf("Buvid" to "session-buvid", "is-kmp-neuron" to "1", "session_id" to "session-id",
            "User-Agent" to "ua"), map.toMap())
        assertFalse(map.containsKey("AUTHORIZATION"))
    }

    fun jsonNonStringIdentityValuesAreSanitizedOrDropped() {
        val raw = """{"buvid":123456,"fp_local":true,"device_id":{"real":"secret"},"session_id":["tok"],"keep":"ok"}"""
            .toByteArray(Charsets.UTF_8)
        val out = IgnetHttpSanitizer.formBody(raw, "application/json", identity, noSign)
        val obj = JSONObject(String(out, Charsets.UTF_8))
        assertEquals(identity.buvid, obj.getString("buvid"))
        assertEquals(identity.buvid, obj.getString("fp_local"))
        assertFalse(obj.has("device_id"))
        assertFalse(obj.has("session_id"))
        assertEquals("ok", obj.getString("keep"))
    }

    fun plusJsonAndTextBodiesSanitizeOrFailClosedOnMalformedJson() {
        val problemJson = """{"access_key":"tok","buvid":"real","keep":"ok"}""".toByteArray(Charsets.UTF_8)
        val outProblem = JSONObject(
            String(IgnetHttpSanitizer.formBody(problemJson, "application/problem+json; charset=utf-8", identity, noSign), Charsets.UTF_8)
        )
        assertFalse(outProblem.has("access_key"))
        assertEquals(identity.buvid, outProblem.getString("buvid"))
        assertEquals("ok", outProblem.getString("keep"))

        val textJson = """{"session_id":"real-s","ok":1}""".toByteArray(Charsets.UTF_8)
        val outTextJson = JSONObject(
            String(IgnetHttpSanitizer.formBody(textJson, "text/plain; charset=utf-8", identity, noSign), Charsets.UTF_8)
        )
        assertEquals(identity.sessionId, outTextJson.getString("session_id"))
        assertEquals(1, outTextJson.getInt("ok"))

        val textForm = "access_key=tok&buvid=real&aid=100".toByteArray(Charsets.UTF_8)
        assertEquals(
            "buvid=${identity.buvid}&aid=100",
            String(IgnetHttpSanitizer.formBody(textForm, "text/plain", identity, noSign), Charsets.UTF_8)
        )

        for (media in listOf("application/json", "application/problem+json", "text/plain", null)) {
            assertThrows(Exception::class.java) {
                IgnetHttpSanitizer.formBody("""{"access_key":"tok"""".toByteArray(Charsets.UTF_8), media, identity, noSign)
            }
        }
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (IgnetHttpSanitizerTest) -> Unit) {
            val s = IgnetHttpSanitizerTest()
            block(s)
        }
        runIsolated { s -> s.targetIsHostAndEncodedPath() }
        runIsolated { s -> s.cleanUrlIsReturnedUntouched() }
        runIsolated { s -> s.accountParametersAreDroppedAndDeviceIdsReplaced() }
        runIsolated { s -> s.changedSignedQueryIsResignedFromDecodedParameters() }
        runIsolated { s -> s.malformedEscapesFailClosed() }
        runIsolated { s -> s.headersAreSealedCaseInsensitivelyInPlace() }
        runIsolated { s -> s.jsonNonStringIdentityValuesAreSanitizedOrDropped() }
        runIsolated { s -> s.plusJsonAndTextBodiesSanitizeOrFailClosedOnMalformedJson() }
    
        IncognitoSessionCases().verifyAllCases()
        IncognitoStreamSinkCases().verifyAllCases()
        KmpHeaderSanitizerCases().verifyAllCases()
        KmpMossBoundaryObservingCases().verifyAllCases()
        LocalFollowGroupsCases().verifyAllCases()
    }
}

private class IncognitoSessionCases {
    fun identityIsEphemeralAndStableWithinOneSession() {
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

    fun refreshingInvalidatesOutstandingCallbacks() {
        val session = IncognitoSession()
        val first = session.enter()
        val second = session.refresh()
        assertFalse(session.accepts(first))
        assertTrue(session.accepts(second))
        assertFalse(session.accepts(second.copy()))
        assertNotEquals(first.buvid, second.buvid)
    }

    fun refreshCanInvalidateAnotherProcessWithoutReopeningClosedSessions() {
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

    fun malformedRemoteIdentityCannotReplaceAnActiveIdentity() {
        val session = IncognitoSession()
        val original = session.enter()
        assertFalse(session.adopt("persistent-account-id", original.sessionId))
        assertFalse(session.adopt(original.buvid, original.buvid))
        assertSame(original, session.identity())
    }

    fun refreshingDoesNotEnableIncognito() {
        IncognitoSession().refresh()
    }

    fun verifyAllCases() {
        fun runIsolated(block: (IncognitoSessionCases) -> Unit) {
            val s = IncognitoSessionCases()
            block(s)
        }
        runIsolated { s -> s.identityIsEphemeralAndStableWithinOneSession() }
        runIsolated { s -> s.refreshingInvalidatesOutstandingCallbacks() }
        runIsolated { s -> s.refreshCanInvalidateAnotherProcessWithoutReopeningClosedSessions() }
        runIsolated { s -> s.malformedRemoteIdentityCannotReplaceAnActiveIdentity() }
        runIsolated { s -> try { s.refreshingDoesNotEnableIncognito(); org.junit.Assert.fail("Expected IllegalStateException") } catch (_: IllegalStateException) {} }
    }
}

private class IncognitoStreamSinkCases {
    fun activityIsAcknowledgedLocallyAndCloseIsIdempotent() {
        val acknowledgements = mutableListOf<Long>()
        var completions = 0
        val sink = IncognitoStreamSink({ acknowledgements += it }, { completions++ })
        assertEquals(1L, sink.acceptForAck())
        assertEquals(2L, sink.acceptForAck())
        assertEquals(listOf(1L, 2L), acknowledgements)
        sink.close()
        sink.close()
        assertEquals(0L, sink.acceptForAck())
        assertEquals(1, completions)
        assertEquals(2, acknowledgements.size)
    }

    fun failedCompletionCannotReopenOrCompleteTheStreamTwice() {
        var calls = 0
        val sink = IncognitoStreamSink({}, { calls++; error("host callback failure") })
        assertTrue(runCatching { sink.close() }.isFailure)
        sink.close()
        assertEquals(0L, sink.acceptForAck())
        assertEquals(1, calls)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (IncognitoStreamSinkCases) -> Unit) {
            val s = IncognitoStreamSinkCases()
            block(s)
        }
        runIsolated { s -> s.activityIsAcknowledgedLocallyAndCloseIsIdempotent() }
        runIsolated { s -> s.failedCompletionCannotReopenOrCompleteTheStreamTwice() }
    }
}

private class KmpHeaderSanitizerCases {
    private val identity = IncognitoSession.Identity(1, "session-buvid", "session-id")
    private val sanitizer = KmpHeaderSanitizer(
        rewriteMetadata = { _, id -> "metadata:${id.buvid}".toByteArray() },
        rewriteDevice = { _, id -> "device:${id.buvid}".toByteArray() })

    private fun maps(ascii: Map<Any?, Any?>, binary: Map<Any?, Any?> = emptyMap()) =
        listOf(LinkedHashMap(ascii), LinkedHashMap(binary))

    fun credentialsAreRemovedAndIdentifiersReplaced() {
        val m = maps(mapOf("Authorization" to "identify_v1 secret", "x-bili-mid" to "123", "Buvid" to "real",
            "session_id" to "real-session", "user-agent" to "ua"))
        val seen = sanitizer.sanitize(m, identity)
        assertFalse(m[0].containsKey("Authorization"))
        assertFalse(m[0].containsKey("x-bili-mid"))
        assertEquals("session-buvid", m[0]["Buvid"])
        assertEquals("session-id", m[0]["session_id"])
        assertEquals("ua", m[0]["user-agent"])
        // Names are reported lower-case; values never are.
        assertTrue("authorization" in seen && "user-agent" in seen)
    }

    fun binaryIdentityHeadersAreRewrittenAndOthersLeftAlone() {
        val other = byteArrayOf(9, 9)
        val m = maps(emptyMap(), mapOf("x-bili-metadata-bin" to byteArrayOf(1), "x-bili-device-bin" to byteArrayOf(2),
            "x-bili-network-bin" to other))
        sanitizer.sanitize(m, identity)
        assertArrayEquals("metadata:session-buvid".toByteArray(), m[1]["x-bili-metadata-bin"] as ByteArray)
        assertArrayEquals("device:session-buvid".toByteArray(), m[1]["x-bili-device-bin"] as ByteArray)
        assertArrayEquals(other, m[1]["x-bili-network-bin"] as ByteArray)
    }

    fun emptyBinaryHeaderIsLeftEmpty() {
        val m = maps(emptyMap(), mapOf("x-bili-metadata-bin" to ByteArray(0)))
        sanitizer.sanitize(m, identity)
        assertEquals(0, (m[1]["x-bili-metadata-bin"] as ByteArray).size)
    }

    fun unexpectedBinaryHeaderTypeAbortsInsteadOfSendingUnsealed() {
        val m = maps(mapOf("x-bili-metadata-bin" to "not-bytes"))
        assertThrows(IllegalStateException::class.java) { sanitizer.sanitize(m, identity) }
    }

    fun rewriteFailureAbortsTheWholeCall() {
        val failing = KmpHeaderSanitizer({ _, _ -> error("parse") }, { b, _ -> b })
        val m = maps(emptyMap(), mapOf("x-bili-metadata-bin" to byteArrayOf(1)))
        assertThrows(IllegalStateException::class.java) { failing.sanitize(m, identity) }
    }

    fun nonStringKeysAndUnexpectedIdentityTypesFailClosed() {
        assertThrows(IllegalStateException::class.java) {
            sanitizer.sanitize(maps(mapOf(123 to "value")), identity)
        }
        assertThrows(IllegalStateException::class.java) {
            sanitizer.sanitize(maps(mapOf("buvid" to 999)), identity)
        }
        assertThrows(IllegalStateException::class.java) {
            sanitizer.sanitize(maps(mapOf("session_id" to 999)), identity)
        }
    }

    fun utf8BytesAndExtendedRemovedHeadersAreHandled() {
        val m = maps(
            mapOf(
                "x-bili-trace-id" to "trace",
                "x-bili-gaia-vtoken" to "vtok",
                "x-bili-exps-bin" to "exps",
                "x-bili-ticket-bin" to "ticket",
                "buvid" to "real-buvid".toByteArray(),
                "session-id" to "real-session".toByteArray()
            ),
            mapOf("x-bili-network-bin" to byteArrayOf(5, 6))
        )
        sanitizer.sanitize(m, identity)
        assertFalse(m[0].containsKey("x-bili-trace-id"))
        assertFalse(m[0].containsKey("x-bili-gaia-vtoken"))
        assertFalse(m[0].containsKey("x-bili-exps-bin"))
        assertFalse(m[0].containsKey("x-bili-ticket-bin"))
        assertArrayEquals(identity.buvid.toByteArray(Charsets.UTF_8), m[0]["buvid"] as ByteArray)
        assertArrayEquals(identity.sessionId.toByteArray(Charsets.UTF_8), m[0]["session-id"] as ByteArray)
        assertArrayEquals(byteArrayOf(5, 6), m[1]["x-bili-network-bin"] as ByteArray)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (KmpHeaderSanitizerCases) -> Unit) {
            val s = KmpHeaderSanitizerCases()
            block(s)
        }
        runIsolated { s -> s.credentialsAreRemovedAndIdentifiersReplaced() }
        runIsolated { s -> s.binaryIdentityHeadersAreRewrittenAndOthersLeftAlone() }
        runIsolated { s -> s.emptyBinaryHeaderIsLeftEmpty() }
        runIsolated { s -> s.unexpectedBinaryHeaderTypeAbortsInsteadOfSendingUnsealed() }
        runIsolated { s -> s.rewriteFailureAbortsTheWholeCall() }
        runIsolated { s -> s.nonStringKeysAndUnexpectedIdentityTypesFailClosed() }
        runIsolated { s -> s.utf8BytesAndExtendedRemovedHeadersAreHandled() }
    }
}

private class KmpMossBoundaryObservingCases {
    private class Recorder : Handler {
        val bytes = mutableListOf<ByteArray?>()
        var completed = 0
        val errors = mutableListOf<KMossException>()
        override fun onNext(bytes: ByteArray?) { this.bytes += bytes }
        override fun onCompleted() { completed++ }
        override fun onError(error: KMossException) { errors += error }
    }

    private fun wrap(original: Handler, seen: MutableList<ByteArray>) =
        KmpMossBoundary.observing(Handler::class.java, original) { seen += it } as Handler

    fun observerSeesTheBytesAndTheHostHandlerStillGetsThemUntouched() {
        val original = Recorder()
        val seen = mutableListOf<ByteArray>()
        val payload = byteArrayOf(1, 2, 3)
        val wrapped = wrap(original, seen)
        wrapped.onNext(payload)
        wrapped.onCompleted()
        assertEquals(1, seen.size)
        assertArrayEquals(payload, seen[0])
        assertSame(payload, original.bytes.single())
        assertEquals(1, original.completed)
    }

    fun anObserverFailureNeverReachesTheHost() {
        val original = Recorder()
        val wrapped = KmpMossBoundary.observing(Handler::class.java, original) { error("observer bug") } as Handler
        wrapped.onNext(byteArrayOf(9))
        assertEquals(1, original.bytes.size)
    }

    fun nullRepliesAndErrorsAreForwardedAsIs() {
        val original = Recorder()
        val seen = mutableListOf<ByteArray>()
        val wrapped = wrap(original, seen)
        wrapped.onNext(null)
        val failure = KMossException("boom", RuntimeException())
        wrapped.onError(failure)
        assertTrue(seen.isEmpty())
        assertEquals(listOf<ByteArray?>(null), original.bytes)
        assertSame(failure, original.errors.single())
    }

    fun exceptionsFromTheHostHandlerAreRethrownUnwrapped() {
        val throwing = object : Handler {
            override fun onNext(bytes: ByteArray?) { throw IllegalStateException("host") }
            override fun onCompleted() {}
            override fun onError(error: KMossException) {}
        }
        val wrapped = wrap(throwing, mutableListOf())
        assertThrows(IllegalStateException::class.java) { wrapped.onNext(byteArrayOf(1)) }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (KmpMossBoundaryObservingCases) -> Unit) {
            val s = KmpMossBoundaryObservingCases()
            block(s)
        }
        runIsolated { s -> s.observerSeesTheBytesAndTheHostHandlerStillGetsThemUntouched() }
        runIsolated { s -> s.anObserverFailureNeverReachesTheHost() }
        runIsolated { s -> s.nullRepliesAndErrorsAreForwardedAsIs() }
        runIsolated { s -> s.exceptionsFromTheHostHandlerAreRethrownUnwrapped() }
    }
}

private class LocalFollowGroupsCases {
    private class Store(var text: String? = null) {
        var writes = 0
        var failWrites = false
        fun library(maxEntries: Int = LocalVirtualLibrary.MAX_ENTRIES) = LocalVirtualLibrary(
            load = { text },
            save = {
                if (failWrites) error("write failed")
                text = it
                writes++
            },
            maxEntries = maxEntries
        )
    }

    fun groupsAndSpecialFollowsRoundTripAndSurviveMetadataRefresh() {
        val store = Store()
        val library = store.library(maxEntries = 2)
        library.follow(Follow(11, "first"))
        library.follow(Follow(12, "second"))
        val first = library.createFollowGroup("学习")
        val second = library.createFollowGroup("朋友")
        val third = library.createFollowGroup("视频")
        library.setFollowGroups(setOf(11, 12), setOf(first.id, second.id, third.id))
        library.setSpecialFollow(11, true)
        library.follow(Follow(11, "renamed", "face"))
        val expected = library.readSnapshot().collections
        assertEquals(setOf(first.id, second.id, third.id), expected.followGroupAssignments[11])
        assertEquals(setOf(11L), expected.specialFollows)
        assertEquals(4, JSONObject(store.text!!).getInt("version"))
        assertEquals(expected, store.library(maxEntries = 2).readSnapshot().collections)
        assertThrows(UnsupportedOperationException::class.java) { (expected.followGroups as MutableList<FollowGroup>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (expected.specialFollows as MutableSet<Long>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (expected.followGroupAssignments.getValue(11) as MutableSet<Long>).clear() }
    }

    fun deletingGroupsAndUnfollowingRemoveOnlyTheirAssociationsWithoutReusingIds() {
        val store = Store()
        val library = store.library()
        library.follow(Follow(11, "first"))
        library.follow(Follow(12, "second"))
        val first = library.createFollowGroup("one")
        val second = library.createFollowGroup("two")
        library.setFollowGroups(setOf(11), setOf(first.id, second.id))
        library.setFollowGroups(setOf(12), setOf(first.id))
        library.setSpecialFollow(11, true)
        library.setSpecialFollow(12, true)
        library.deleteFollowGroup(first.id)
        var state = library.readSnapshot().collections
        assertEquals(mapOf(11L to setOf(second.id)), state.followGroupAssignments)
        assertEquals(setOf(11L, 12L), state.specialFollows)
        assertEquals(3L, library.createFollowGroup("three").id)
        library.unfollow(11)
        state = library.readSnapshot().collections
        assertTrue(state.followGroupAssignments.isEmpty())
        assertEquals(setOf(12L), state.specialFollows)
        library.deleteFollowGroup(second.id)
        library.deleteFollowGroup(3)
        val reopened = store.library()
        assertEquals(4, JSONObject(store.text!!).getInt("version"))
        assertEquals(4L, reopened.createFollowGroup("after restart").id)
    }

    fun privateGroupsNeverReadOrWriteTheOrdinaryLibrary() {
        val store = Store()
        val library = store.library()
        library.follow(Follow(1, "ordinary"))
        val ordinary = library.createFollowGroup("ordinary group")
        library.setFollowGroups(setOf(1), setOf(ordinary.id))
        library.setSpecialFollow(1, true)
        val original = library.readSnapshot().collections
        val disk = store.text
        val writes = store.writes
        library.enterIncognito()
        assertTrue(library.readSnapshot().collections.followGroups.isEmpty())
        library.follow(Follow(2, "private"))
        val temporary = library.createFollowGroup("private group")
        library.setFollowGroups(setOf(2), setOf(temporary.id))
        library.setSpecialFollow(2, true)
        assertEquals(disk, store.text)
        assertEquals(writes, store.writes)
        library.exitIncognito()
        assertEquals(original, library.readSnapshot().collections)
        library.enterIncognito()
        assertTrue(library.readSnapshot().collections.followGroups.isEmpty())
        assertTrue(library.readSnapshot().collections.specialFollows.isEmpty())
    }

    fun failedPersistenceLeavesGroupsAssignmentsAndNextIdUnchanged() {
        val store = Store()
        val library = store.library()
        library.follow(Follow(1, "one"))
        val group = library.createFollowGroup("first")
        val before = library.readSnapshot()
        val disk = store.text
        store.failWrites = true
        assertThrows(IllegalStateException::class.java) { library.setFollowGroups(setOf(1), setOf(group.id)) }
        assertThrows(IllegalStateException::class.java) { library.setSpecialFollow(1, true) }
        assertThrows(IllegalStateException::class.java) { library.createFollowGroup("second") }
        assertEquals(before, library.readSnapshot())
        assertEquals(disk, store.text)
        store.failWrites = false
        assertEquals(2L, library.createFollowGroup("second").id)
    }

    fun invalidAssignmentsAndSnapshotsNeverPartiallyChangeTheLibrary() {
        val store = Store()
        val library = store.library()
        library.follow(Follow(1, "one"))
        val group = library.createFollowGroup("first")
        val before = library.readSnapshot()
        val writes = store.writes
        assertThrows(IllegalArgumentException::class.java) { library.setFollowGroups(setOf(1, 2), setOf(group.id)) }
        assertThrows(IllegalArgumentException::class.java) { library.setFollowGroups(setOf(1), setOf(999)) }
        assertThrows(IllegalArgumentException::class.java) { library.setSpecialFollow(2, true) }
        assertThrows(IllegalArgumentException::class.java) { library.createFollowGroup("first") }
        val collections = before.collections
        val invalid = listOf(
            collections.copy(followGroups = listOf(group, group)),
            collections.copy(followGroups = listOf(group, FollowGroup(2, group.name)), nextFollowGroupId = 3),
            collections.copy(nextFollowGroupId = group.id),
            collections.copy(followGroupAssignments = mapOf(1L to emptySet())),
            collections.copy(followGroupAssignments = mapOf(1L to setOf(999L))),
            collections.copy(followGroupAssignments = mapOf(2L to setOf(group.id))),
            collections.copy(specialFollows = setOf(2L))
        )
        invalid.forEach { candidate ->
            assertThrows(IllegalArgumentException::class.java) { library.writeSnapshot(before.copy(collections = candidate)) }
            assertEquals(before, library.readSnapshot())
        }
        assertEquals(writes, store.writes)
    }

    fun malformedVersionFourCannotSilentlyOverwriteStoredData() {
        val original = Store()
        val library = original.library()
        library.follow(Follow(1, "one"))
        val group = library.createFollowGroup("first")
        library.setFollowGroups(setOf(1), setOf(group.id))
        library.setSpecialFollow(1, true)
        val valid = original.text!!
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.getJSONArray("followGroups").put(it.getJSONArray("followGroups").getJSONObject(0)) },
            { it.getJSONArray("followGroupAssignments").getJSONObject(0).getJSONArray("groupIds").put(group.id) },
            { it.getJSONArray("followGroupAssignments").getJSONObject(0).put("groupIds", JSONArray().put(999)) },
            { it.getJSONArray("specialFollows").put(1) },
            { it.put("nextFollowGroupId", group.id) },
            { it.remove("followGroups") }
        )
        mutations.forEach { mutate ->
            val broken = JSONObject(valid).also(mutate).toString()
            val store = Store(broken)
            val reopened = store.library()
            assertThrows(CorruptLibraryException::class.java) { reopened.readSnapshot() }
            assertThrows(CorruptLibraryException::class.java) { reopened.createFollowGroup("another") }
            assertEquals(broken, store.text)
            assertEquals(0, store.writes)
        }
    }

    fun olderSchemasUpgradeWithoutLosingExistingData() {
        for (oldVersion in 1..3) {
            val store = Store()
            val library = store.library()
            library.follow(Follow(1, "one", if (oldVersion == 3) "face" else ""))
            if (oldVersion == 2) library.createFavoriteFolder("folder", LocalVirtualLibrary.FolderInfo(99))
            assertEquals(oldVersion, JSONObject(store.text!!).getInt("version"))
            val original = store.library().readSnapshot().collections
            val reopened = store.library()
            reopened.createFollowGroup("new")
            val updated = store.library().readSnapshot().collections
            assertEquals(original.follows, updated.follows)
            assertEquals(original.favorites, updated.favorites)
            assertEquals(original.folderInfo, updated.folderInfo)
            assertEquals(4, JSONObject(store.text!!).getInt("version"))
        }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (LocalFollowGroupsCases) -> Unit) {
            val s = LocalFollowGroupsCases()
            block(s)
        }
        runIsolated { s -> s.groupsAndSpecialFollowsRoundTripAndSurviveMetadataRefresh() }
        runIsolated { s -> s.deletingGroupsAndUnfollowingRemoveOnlyTheirAssociationsWithoutReusingIds() }
        runIsolated { s -> s.privateGroupsNeverReadOrWriteTheOrdinaryLibrary() }
        runIsolated { s -> s.failedPersistenceLeavesGroupsAssignmentsAndNextIdUnchanged() }
        runIsolated { s -> s.invalidAssignmentsAndSnapshotsNeverPartiallyChangeTheLibrary() }
        runIsolated { s -> s.malformedVersionFourCannotSilentlyOverwriteStoredData() }
        runIsolated { s -> s.olderSchemasUpgradeWithoutLosingExistingData() }
    }
}
