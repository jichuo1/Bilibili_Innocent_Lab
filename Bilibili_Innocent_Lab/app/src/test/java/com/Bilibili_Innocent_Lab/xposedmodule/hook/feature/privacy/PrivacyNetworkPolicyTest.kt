package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.bilibili.lib.ighttp.IgHttpRequest
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class PrivacyNetworkPolicyTest {
    private val identity = IncognitoSession.Identity(1, "session-buvid", "session-id")

    fun reportsAndAccountMutationsNeverLeaveIncognito() {
        listOf(
            Triple("api.bilibili.com", "/x/v2/history/report", "POST"),
            Triple("api.bilibili.com", "/x/click-interface/click/web/h5", "POST"),
            Triple("api.bilibili.com", "/x/v2/history/heartbeat", "POST"),
            Triple("app.bilibili.com", "/x/v2/search/history", "GET"),
            Triple("data.bilibili.com", "/log/web", "POST"),
            Triple("dataflow.biliapi.com", "/anything", "GET"),
            Triple("api.bilibili.com", "/x/unknown/future-write", "POST"),
            Triple("api.bilibili.com", "/x/relation/modify", "POST"),
            Triple("dataflow.biliapi.com", "/log/pbmobile/realtime", "POST"),
            Triple("api.bilibili.com", "/x/report/click/now", "POST")
        ).forEach { (host, path, method) -> assertTrue("$host$path", PrivacyNetworkPolicy.blockHttp(host,path,method)) }
    }

    fun terminalTelemetryPathsAndPresenceAreBlockedWithoutMatchingContentNames() {
        listOf("/x/click", "/x/report/", "/x/feedback", "/x/exposure").forEach {
            assertTrue(PrivacyNetworkPolicy.blockHttp("API.BILIBILI.COM.", it, "get"))
        }
        assertFalse(PrivacyNetworkPolicy.blockHttp("api.bilibili.com", "/x/reporters/list", "GET"))
        assertTrue(PrivacyNetworkPolicy.blockRpc("bilibili.main.community.reply.v1.Reply/Room"))
        assertFalse(PrivacyNetworkPolicy.blockRpc("bilibili.main.community.reply.v1.Reply/MainList"))
    }

    fun contentReadsStillWorkAndHostMatchingIsBounded() {
        listOf("/x/v2/view", "/x/v2/reply/main", "/x/player/playurl").forEach {
            assertFalse(PrivacyNetworkPolicy.blockHttp("api.bilibili.com", it, "GET"))
        }
        assertFalse(PrivacyNetworkPolicy.isBiliHost("bilibili.com.attacker.test"))
        assertFalse(PrivacyNetworkPolicy.isBiliHost("evilbilibili.com"))
        assertFalse(PrivacyNetworkPolicy.isBiliHost("notbiligame.net"))
        assertFalse(PrivacyNetworkPolicy.blockHttp("example.com", "/history/report", "POST"))
    }

    fun gameTrackerReportsAreWithheldWithoutReplacingItsConfiguration() {
        assertFalse(PrivacyNetworkPolicy.isBiliHost("line1-log.biligame.net"))
        assertFalse(PrivacyNetworkPolicy.blockHttp("line1-log.biligame.net", "/collector/admin/config", "POST"))
        assertTrue(PrivacyNetworkPolicy.blockHttp("line1-log.biligame.net", "/collector/api/report", "POST"))
        assertTrue(PrivacyNetworkPolicy.blockHttp("LINE2-LOG.BILIGAME.NET.", "/collector/api/report/", "POST"))
        assertFalse(PrivacyNetworkPolicy.blockHttp("notbiligame.net", "/collector/api/report", "POST"))
        assertFalse(PrivacyNetworkPolicy.blockHttp("biligame.net.example.com", "/collector/api/report", "POST"))
        assertFalse(PrivacyNetworkPolicy.blockHttp("line1-log.biligame.net", "/collector/api/reporters", "POST"))
        assertTrue(PrivacyNetworkPolicy.isBiliHost("line3-h5-mobile-api.biligame.com"))
    }

    fun observedAdMeasurementIsWithheldAndItsPathIsRedactedInDiagnostics() {
        val path = "/x/k=sample&m1a=example-device-hash&m11=example-id"
        assertTrue(PrivacyNetworkPolicy.blockHttp("G.CN.MIAOZHEN.COM.", path, "GET"))
        assertTrue(PrivacyNetworkPolicy.blockHttp("g.cn.miaozhen.com", path, "POST"))
        assertEquals("g.cn.miaozhen.com/x/[redacted]", PrivacyNetworkPolicy.diagnosticTarget("g.cn.miaozhen.com", path))
        assertFalse(PrivacyNetworkPolicy.blockHttp("g.cn.miaozhen.com", "/collector/config", "GET"))
        assertFalse(PrivacyNetworkPolicy.blockHttp("g.cn.miaozhen.com", "/x/keys", "GET"))
        assertFalse(PrivacyNetworkPolicy.blockHttp("g.cn.miaozhen.com.example.org", path, "GET"))
        assertFalse(PrivacyNetworkPolicy.blockHttp("evil-g.cn.miaozhen.com", path, "GET"))
    }
    fun protobufHeartbeatsFeedbackAndHistoryWritesAreBlockedButReadsRemain() {
        listOf("bilibili.api.player.v1.Heartbeat/Mobile",
            "bilibili.app.history.v1.History/Report", "bilibili.app.interface.v1.Reply/Add",
            "bilibili.app.show.v1.Recommend/Dislike").forEach {
            assertTrue(it, PrivacyNetworkPolicy.blockRpc(it))
        }
        listOf("bilibili.app.viewunite.v1.View/View",
            "bilibili.app.playerunite.v1.Player/PlayViewUnite",
            "bilibili.main.community.reply.v1.Reply/MainList",
            "bilibili.app.search.v2.Search/SearchAll").forEach {
            assertFalse(it, PrivacyNetworkPolicy.blockRpc(it))
        }
    }

    fun persistentGuestAndHardwareIdsAreReplacedOnEveryIdentityRefresh() {
        val input = mapOf("GuestId" to "persistent-guest", "guest_id" to "persistent-guest",
            "android_id" to "hardware", "oaid" to "hardware", "imei" to "hardware")
        assertEquals(setOf(identity.buvid), PrivacyNetworkPolicy.parameters(input, identity).values.toSet())
        val fresh = identity.copy(generation = 2, buvid = "fresh-session")
        assertEquals(setOf(fresh.buvid), PrivacyNetworkPolicy.parameters(input, fresh).values.toSet())
        assertEquals("persistent-guest", input["GuestId"])
    }

    fun parametersRemoveCredentialsAndRecommendationContextWithoutDroppingMediaKeys() {
        val input = mapOf("access_key" to "real-account", "SESSDATA" to "real-cookie",
            "bili_jct" to "csrf", "buvid" to "real-device", "fp_local" to "real-fingerprint",
            "session_id" to "old-session", "spmid" to "source", "search_id" to "search",
            "aid" to "123", "cid" to "456", "qn" to "80")
        val result = PrivacyNetworkPolicy.parameters(input, identity)
        assertEquals(mapOf("buvid" to "session-buvid", "fp_local" to "session-buvid",
            "session_id" to "session-id", "aid" to "123", "cid" to "456", "qn" to "80"), result)
        assertEquals("real-account", input["access_key"])
    }

    fun writeAndTrackingRpcsHeaderSanitizersAndBodySanitizers() {
        for (method in listOf(
            "Reply", "Ping", "Vote", "Cancel",
            "CreateFolder", "RemoveItem", "EditTag", "SaveDraft", "UploadLog",
            "SubmitForm", "PostAction", "TrackEvent", "LogMetric", "SyncState",
            "UnfollowUser", "UnlikeVideo", "UnfavFolder"
        )) {
            assertTrue("Should block $method", PrivacyNetworkPolicy.blockRpc("bilibili.app.test.v1.Service/$method"))
        }
        for (allowed in listOf("ReplyList", "StatInfo", "MainList", "View", "ViewProgress", "PlayViewUnite")) {
            assertFalse("Should allow $allowed", PrivacyNetworkPolicy.blockRpc("bilibili.app.test.v1.Service/$allowed"))
        }

        // Header sanitizers strip fawkes-req-bin and seal buvid3/buvid4.
        val headerSession = IncognitoSession { "00000000-0000-0000-0000-0000000000aa" }
        val headerId = headerSession.enter()
        val kmpSanitizer = KmpHeaderSanitizer({ b, _ -> b }, { b, _ -> b })
        val ascii = linkedMapOf<Any?, Any?>(
            "X-Bili-Fawkes-Req-Bin" to "leak",
            "Buvid3" to "real-3",
            "Buvid4" to "real-4",
            "User-Agent" to "ua"
        )
        val binary = linkedMapOf<Any?, Any?>("x-bili-fawkes-req-bin" to byteArrayOf(1, 2))
        kmpSanitizer.sanitize(listOf(ascii, binary), headerId)
        assertFalse("X-Bili-Fawkes-Req-Bin" in ascii)
        assertFalse("x-bili-fawkes-req-bin" in binary)
        assertEquals(headerId.buvid, ascii["Buvid3"])
        assertEquals(headerId.buvid, ascii["Buvid4"])
        assertEquals("ua", ascii["User-Agent"])

        val ignetHeaders = linkedMapOf(
            "X-Bili-Fawkes-Req-Bin" to "leak",
            "X-Bili-Metadata-Bin" to "real-meta",
            "X-Bili-Device-Bin" to "real-dev",
            "Buvid3" to "real-3",
            "Buvid4" to "real-4"
        )
        IgnetHttpSanitizer.headers(ignetHeaders, headerId)
        assertFalse("X-Bili-Fawkes-Req-Bin" in ignetHeaders)
        assertFalse("X-Bili-Metadata-Bin" in ignetHeaders)
        assertFalse("X-Bili-Device-Bin" in ignetHeaders)
        assertEquals(headerId.buvid, ignetHeaders["Buvid3"])
        assertEquals(headerId.buvid, ignetHeaders["Buvid4"])

        val sanitizedParams = PrivacyNetworkPolicy.parameters(
            mapOf("buvid3" to "real-3", "buvid4" to "real-4", "access_key" to "secret", "aid" to "1"),
            headerId
        )
        assertEquals(headerId.buvid, sanitizedParams["buvid3"])
        assertEquals(headerId.buvid, sanitizedParams["buvid4"])
        assertFalse("access_key" in sanitizedParams)
        assertEquals("1", sanitizedParams["aid"])

        // Ignet form, json body, and lazy stream provider sanitization.
        val bodyIdentity = IncognitoSession { "11111111-1111-1111-1111-111111111111" }.enter()
        val sign: (Map<String, String>) -> String = { params ->
            params.entries.sortedBy { it.key }.joinToString("&") { "${it.key}=${it.value}" } + "&sign=sealed"
        }
        val cleanBytes = "a=1&b=2".toByteArray()
        assertSame(cleanBytes, IgnetHttpSanitizer.formBody(cleanBytes, "application/x-www-form-urlencoded", bodyIdentity, sign))
        val cleanedForm = String(IgnetHttpSanitizer.formBody(
            "access_key=tok&buvid=real&dedeuserid=9&spmid=trace&a=1&sign=old".toByteArray(),
            "application/x-www-form-urlencoded; charset=utf-8",
            bodyIdentity,
            sign
        ))
        assertFalse(cleanedForm.contains("tok"))
        assertFalse(cleanedForm.contains("dedeuserid="))
        assertFalse(cleanedForm.contains("spmid="))
        assertTrue(cleanedForm.contains("buvid=${bodyIdentity.buvid}"))
        assertTrue(cleanedForm.contains("sign=sealed"))

        val cleanedJson = String(IgnetHttpSanitizer.formBody(
            """{"access_key":"tok","items":[{"buvid":"real","spmid":"trace","k":"v"}]}""".toByteArray(),
            "application/json",
            bodyIdentity,
            sign
        ))
        assertFalse(cleanedJson.contains("access_key"))
        assertFalse(cleanedJson.contains("spmid"))
        assertTrue(cleanedJson.contains(bodyIdentity.buvid))
        assertTrue(cleanedJson.contains(""""k":"v""""))

        var reads = 0
        val chunks = ArrayDeque(listOf("access_key=tok&".toByteArray(), "buvid=real&ok=1".toByteArray(), ByteArray(0)))
        val provider = IgHttpRequest.StreamDataProvider { chunks.removeFirst().also { reads++ } }
        val wrapped = IgnetHttpSanitizer.wrapStreamProvider(
            javaClass.classLoader!!,
            IgHttpRequest.StreamDataProvider::class.java,
            provider,
            IgHttpRequest.StreamDataProvider::class.java.getMethod("onProduceData", Int::class.javaPrimitiveType),
            "application/x-www-form-urlencoded",
            bodyIdentity,
            sign
        ) as IgHttpRequest.StreamDataProvider
        assertEquals(0, reads)
        val first = wrapped.onProduceData(12)
        val rest = wrapped.onProduceData(128)
        assertEquals(0, wrapped.onProduceData(16).size)
        val total = String(first + rest)
        assertFalse(total.contains("access_key"))
        assertTrue(total.contains("buvid=${bodyIdentity.buvid}"))
        assertTrue(total.contains("ok=1"))

        val huge = IgHttpRequest.StreamDataProvider { ByteArray(4096) }
        val capped = IgnetHttpSanitizer.wrapStreamProvider(
            javaClass.classLoader!!,
            IgHttpRequest.StreamDataProvider::class.java,
            huge,
            IgHttpRequest.StreamDataProvider::class.java.getMethod("onProduceData", Int::class.javaPrimitiveType),
            "application/x-www-form-urlencoded",
            bodyIdentity,
            sign,
            maxBytes = 5000
        ) as IgHttpRequest.StreamDataProvider
        val thrown = assertThrows(Throwable::class.java) { capped.onProduceData(4096) }
        val cause = (thrown as? java.lang.reflect.UndeclaredThrowableException)?.undeclaredThrowable ?: thrown
        assertTrue("Expected IOException fail-closed, got $cause", cause is IOException)

        // Non-form raw body sanitizer cleans form/json/text and rejects binary payloads.
        val rawSession = IncognitoSession().apply { enter() }
        val rawIdentity = requireNotNull(rawSession.identity())
        val hooks = IncognitoNetworkHooks(rawSession)
        val rawForm = hooks.sanitizeRawRequestBody(
            "access_key=secret&buvid=real&aid=100".toByteArray(Charsets.UTF_8),
            "application/x-www-form-urlencoded",
            rawIdentity
        ) { "signed" }
        assertEquals("buvid=${rawIdentity.buvid}&aid=100", String(rawForm, Charsets.UTF_8))
        val rawJson = hooks.sanitizeRawRequestBody(
            """{"buvid":999,"session_id":"real-s","aid":42}""".toByteArray(Charsets.UTF_8),
            "application/json; charset=utf-8",
            rawIdentity
        ) { error("unused") }
        assertFalse(String(rawJson, Charsets.UTF_8).contains("real-s"))
        assertTrue(String(rawJson, Charsets.UTF_8).contains(rawIdentity.buvid))
        assertThrows(IOException::class.java) {
            hooks.sanitizeRawRequestBody(byteArrayOf(0x01, 0x00, 0x02), "application/octet-stream", rawIdentity) { "" }
        }
        assertThrows(IOException::class.java) {
            hooks.sanitizeRawRequestBody(byteArrayOf(0xff.toByte(), 0xfe.toByte()), null, rawIdentity) { "" }
        }
        val problemJson = hooks.sanitizeRawRequestBody(
            """{"access_key":"tok","buvid":"real","aid":7}""".toByteArray(Charsets.UTF_8),
            "application/problem+json",
            rawIdentity
        ) { error("unused") }
        assertFalse(String(problemJson, Charsets.UTF_8).contains("tok"))
        assertTrue(String(problemJson, Charsets.UTF_8).contains(rawIdentity.buvid))
        val textForm = hooks.sanitizeRawRequestBody(
            "access_key=tok&buvid=real&aid=8".toByteArray(Charsets.UTF_8),
            "text/plain; charset=utf-8",
            rawIdentity
        ) { error("unused") }
        assertEquals("buvid=${rawIdentity.buvid}&aid=8", String(textForm, Charsets.UTF_8))
        assertThrows(IOException::class.java) {
            hooks.sanitizeRawRequestBody("raw-secret-token-without-delimiters".toByteArray(Charsets.UTF_8), "text/plain", rawIdentity) { "" }
        }
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (PrivacyNetworkPolicyTest) -> Unit) {
            val s = PrivacyNetworkPolicyTest()
            block(s)
        }
        runIsolated { s -> s.reportsAndAccountMutationsNeverLeaveIncognito() }
        runIsolated { s -> s.terminalTelemetryPathsAndPresenceAreBlockedWithoutMatchingContentNames() }
        runIsolated { s -> s.contentReadsStillWorkAndHostMatchingIsBounded() }
        runIsolated { s -> s.gameTrackerReportsAreWithheldWithoutReplacingItsConfiguration() }
        runIsolated { s -> s.observedAdMeasurementIsWithheldAndItsPathIsRedactedInDiagnostics() }
        runIsolated { s -> s.protobufHeartbeatsFeedbackAndHistoryWritesAreBlockedButReadsRemain() }
        runIsolated { s -> s.persistentGuestAndHardwareIdsAreReplacedOnEveryIdentityRefresh() }
        runIsolated { s -> s.parametersRemoveCredentialsAndRecommendationContextWithoutDroppingMediaKeys() }
        runIsolated { s -> s.writeAndTrackingRpcsHeaderSanitizersAndBodySanitizers() }
    
        StoryPressScopeCases().nestedPressesRestoreTheirPreviousValueAndDoNotCrossThreads()
    }
}

private class StoryPressScopeCases {
    fun nestedPressesRestoreTheirPreviousValueAndDoNotCrossThreads() {
        val scope = StoryPressScope<String>()
        assertNull(scope.current())
        val first = scope.enter("first")
        val second = scope.enter("second")
        assertEquals("second", scope.current())
        val otherThreadValue = AtomicReference<String?>("unread")
        Thread { otherThreadValue.set(scope.current()) }.apply { start(); join() }
        assertNull(otherThreadValue.get())
        scope.exit(second)
        assertEquals("first", scope.current())
        scope.exit(first)
        assertNull(scope.current())
    }
}
