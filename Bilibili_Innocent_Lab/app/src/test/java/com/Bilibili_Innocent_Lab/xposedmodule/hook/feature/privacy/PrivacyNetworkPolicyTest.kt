package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import org.junit.Assert.*
import org.junit.Test

class PrivacyNetworkPolicyTest {
    private val identity = IncognitoSession.Identity(1, "session-buvid", "session-id")

    @Test fun reportsAndAccountMutationsNeverLeaveIncognito() {
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

    @Test fun terminalTelemetryPathsAndPresenceAreBlockedWithoutMatchingContentNames() {
        listOf("/x/click", "/x/report/", "/x/feedback", "/x/exposure").forEach {
            assertTrue(PrivacyNetworkPolicy.blockHttp("API.BILIBILI.COM.", it, "get"))
        }
        assertFalse(PrivacyNetworkPolicy.blockHttp("api.bilibili.com", "/x/reporters/list", "GET"))
        assertTrue(PrivacyNetworkPolicy.blockRpc("bilibili.main.community.reply.v1.Reply/Room"))
        assertFalse(PrivacyNetworkPolicy.blockRpc("bilibili.main.community.reply.v1.Reply/MainList"))
    }

    @Test fun contentReadsStillWorkAndHostMatchingIsBounded() {
        listOf("/x/v2/view", "/x/v2/reply/main", "/x/player/playurl").forEach {
            assertFalse(PrivacyNetworkPolicy.blockHttp("api.bilibili.com", it, "GET"))
        }
        assertFalse(PrivacyNetworkPolicy.isBiliHost("bilibili.com.attacker.test"))
        assertFalse(PrivacyNetworkPolicy.isBiliHost("evilbilibili.com"))
        assertFalse(PrivacyNetworkPolicy.isBiliHost("notbiligame.net"))
        assertFalse(PrivacyNetworkPolicy.blockHttp("example.com", "/history/report", "POST"))
    }

    @Test fun gameTrackerReportsAreWithheldWithoutReplacingItsConfiguration() {
        assertFalse(PrivacyNetworkPolicy.isBiliHost("line1-log.biligame.net"))
        assertFalse(PrivacyNetworkPolicy.blockHttp("line1-log.biligame.net", "/collector/admin/config", "POST"))
        assertTrue(PrivacyNetworkPolicy.blockHttp("line1-log.biligame.net", "/collector/api/report", "POST"))
        assertTrue(PrivacyNetworkPolicy.blockHttp("LINE2-LOG.BILIGAME.NET.", "/collector/api/report/", "POST"))
        assertFalse(PrivacyNetworkPolicy.blockHttp("notbiligame.net", "/collector/api/report", "POST"))
        assertFalse(PrivacyNetworkPolicy.blockHttp("biligame.net.example.com", "/collector/api/report", "POST"))
        assertFalse(PrivacyNetworkPolicy.blockHttp("line1-log.biligame.net", "/collector/api/reporters", "POST"))
        assertTrue(PrivacyNetworkPolicy.isBiliHost("line3-h5-mobile-api.biligame.com"))
    }

    @Test fun observedAdMeasurementIsWithheldAndItsPathIsRedactedInDiagnostics() {
        val path = "/x/k=sample&m1a=example-device-hash&m11=example-id"
        assertTrue(PrivacyNetworkPolicy.blockHttp("G.CN.MIAOZHEN.COM.", path, "GET"))
        assertTrue(PrivacyNetworkPolicy.blockHttp("g.cn.miaozhen.com", path, "POST"))
        assertEquals("g.cn.miaozhen.com/x/[redacted]", PrivacyNetworkPolicy.diagnosticTarget("g.cn.miaozhen.com", path))
        assertFalse(PrivacyNetworkPolicy.blockHttp("g.cn.miaozhen.com", "/collector/config", "GET"))
        assertFalse(PrivacyNetworkPolicy.blockHttp("g.cn.miaozhen.com", "/x/keys", "GET"))
        assertFalse(PrivacyNetworkPolicy.blockHttp("g.cn.miaozhen.com.example.org", path, "GET"))
        assertFalse(PrivacyNetworkPolicy.blockHttp("evil-g.cn.miaozhen.com", path, "GET"))
    }
    @Test fun protobufHeartbeatsFeedbackAndHistoryWritesAreBlockedButReadsRemain() {
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

    @Test fun persistentGuestAndHardwareIdsAreReplacedOnEveryIdentityRefresh() {
        val input = mapOf("GuestId" to "persistent-guest", "guest_id" to "persistent-guest",
            "android_id" to "hardware", "oaid" to "hardware", "imei" to "hardware")
        assertEquals(setOf(identity.buvid), PrivacyNetworkPolicy.parameters(input, identity).values.toSet())
        val fresh = identity.copy(generation = 2, buvid = "fresh-session")
        assertEquals(setOf(fresh.buvid), PrivacyNetworkPolicy.parameters(input, fresh).values.toSet())
        assertEquals("persistent-guest", input["GuestId"])
    }

    @Test fun parametersRemoveCredentialsAndRecommendationContextWithoutDroppingMediaKeys() {
        val input = mapOf("access_key" to "real-account", "SESSDATA" to "real-cookie",
            "bili_jct" to "csrf", "buvid" to "real-device", "fp_local" to "real-fingerprint",
            "session_id" to "old-session", "spmid" to "source", "search_id" to "search",
            "aid" to "123", "cid" to "456", "qn" to "80")
        val result = PrivacyNetworkPolicy.parameters(input, identity)
        assertEquals(mapOf("buvid" to "session-buvid", "fp_local" to "session-buvid",
            "session_id" to "session-id", "aid" to "123", "cid" to "456", "qn" to "80"), result)
        assertEquals("real-account", input["access_key"])
    }
}
