package com.Bilibili_Innocent_Lab.xposedmodule.agent

import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.AgentModelToolCall
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentNativeMenuRegressionTest {
    private var sequence = 0
    private fun call(operation: String, arguments: JSONObject = JSONObject()) =
        AgentModelToolCall("regression_${++sequence}", operation, arguments)
    private fun ok(data: JSONObject) = JSONObject().put("ok", true).put("data", data)
    private fun args(key: String, value: String, cursor: String? = null) = JSONObject().put(key, value).apply {
        cursor?.let { put("cursor", it) }
    }
    private fun video(id: String) = JSONObject().put("video_id", id).put("title", "公开视频 $id")
    private fun archive(user: String, id: String, cursor: String? = null) = JSONObject().put("source", "host_rpc")
        .put("dataset", "user_videos").put("requested_user_id", user).put("items", JSONArray().put(video(id)))
        .put("next_cursor", cursor ?: JSONObject.NULL)
    private fun comments(id: String, cursor: String? = null) = JSONObject().put("source", "host_rpc")
        .put("dataset", "comments").put("video_id", id).put("comments", JSONArray().put(JSONObject()
            .put("comment_id", "1").put("video_id", id).put("message", "公开评论正文")))
        .put("next_cursor", cursor ?: JSONObject.NULL)
    private fun choices(actions: AgentDecisionActions, operation: String): List<JSONObject> =
        actions.menu(false).values.filter { it.first == operation }.map { it.second }
    private fun initialRouting() = AgentNativeRoutingPolicy().apply { startingNative(); began() }

    fun oneUnsupportedNativeOperationDoesNotRemoveAnotherAvailableNativeMenu() {
        val actions = AgentDecisionActions("查看热榜第一的视频播放量")
        actions.record(call("get_capabilities"), ok(JSONObject().put("get_ranking", false)
            .put("get_video_metrics", true).put("get_host_state", true)))
        actions.record(call("get_host_state"), ok(JSONObject().put("foreground", true).put("interaction_guard", true)
            .put("video_id", "av1")))
        actions.record(call("get_ranking"), JSONObject().put("ok", false).put("error", "use_ui_tools")
            .put("data", JSONObject().put("required_operation", "get_ranking")))
        assertEquals("get_host_state", actions.menu(false).getValue("state").first)
        assertTrue(choices(actions, "get_video_metrics").any { it.optString("video_id") == "av1" })
        assertFalse(actions.state().getBoolean("goal_evidence_complete"))
    }

    fun observingUiDuringAnActiveNativeSessionDoesNotTransferTheMenuToUiOnly() {
        val actions = AgentDecisionActions("查看热榜第一")
        actions.record(call("get_capabilities"), ok(JSONObject().put("get_ranking", true)))
        actions.record(call("get_ui_state"), ok(JSONObject().put("backend", "accessibility")
            .put("native_session_active", true).put("snapshot_id", "12345678-1234-1234-1234-123456789abc")
            .put("nodes", JSONArray())))
        assertEquals("get_host_state", actions.menu(false).getValue("state").first)
        assertTrue(choices(actions, "get_ranking").isNotEmpty())
    }

    fun readingOneUsersArchiveRetainsTheSecondUsersFirstRead() {
        val actions = AgentDecisionActions("查看 UID 1 和 UID 2 的投稿")
        actions.record(call("get_user_videos", args("user_id", "1")), ok(archive("1", "av11")))
        assertFalse(actions.state().getBoolean("goal_evidence_complete"))
        assertTrue(choices(actions, "get_user_videos").any { it.optString("user_id") == "2" && !it.has("cursor") })
    }

    fun eachUsersContinuationRetainsItsOwnUserIdentityAndCursor() {
        val actions = AgentDecisionActions("查看 UID 1 和 UID 2 的投稿")
        actions.record(call("get_user_videos", args("user_id", "1")), ok(archive("1", "av11", "page:1")))
        actions.record(call("get_user_videos", args("user_id", "2")), ok(archive("2", "av22", "page:2")))
        val pages = choices(actions, "get_user_videos").filter { it.has("cursor") }
        assertEquals(setOf("1" to "page:1", "2" to "page:2"), pages.map {
            it.getString("user_id") to it.getString("cursor")
        }.toSet())
        actions.record(call("get_user_videos", args("user_id", "1", "page:1")), ok(archive("1", "av12")))
        assertEquals(listOf("2" to "page:2"), choices(actions, "get_user_videos").filter { it.has("cursor") }.map {
            it.getString("user_id") to it.getString("cursor")
        })
    }

    fun readingOneUsersLiveStatusRetainsTheSecondUsersStatusMenu() {
        val actions = AgentDecisionActions("查看 UID 1 和 UID 2 是否正在直播")
        actions.record(call("get_live_status", args("user_id", "1")), ok(JSONObject().put("source", "host_rpc")
            .put("dataset", "up_live_status").put("user_id", "1").put("live_status", "offline")))
        assertFalse(actions.state().getBoolean("goal_evidence_complete"))
        assertTrue(choices(actions, "get_live_status").any { it.optString("user_id") == "2" })
    }

    fun readingOneVideosMetricsRetainsTheSecondExplicitVideosMenu() {
        val actions = AgentDecisionActions("查看 av1 与 av2 的播放量")
        actions.record(call("get_video_metrics", args("video_id", "av1")), ok(JSONObject().put("source", "host_rpc")
            .put("video_id", "av1").put("statistics", JSONObject().put("status", "reported").put("view_count", 42L))))
        assertFalse(actions.state().getBoolean("goal_evidence_complete"))
        assertTrue(choices(actions, "get_video_metrics").any { it.optString("video_id") == "av2" })
    }

    fun readingOneVideosCommentsRetainsTheSecondExplicitVideosFirstRead() {
        val actions = AgentDecisionActions("查看 av1 与 av2 的评论内容")
        actions.record(call("get_video_comments", args("video_id", "av1")), ok(comments("av1")))
        assertFalse(actions.state().getBoolean("goal_evidence_complete"))
        assertTrue(choices(actions, "get_video_comments").any { it.optString("video_id") == "av2" && !it.has("cursor") })
    }

    fun differentVideoCommentPagesRetainIndependentContinuations() {
        val actions = AgentDecisionActions("查看 av1 与 av2 的评论内容")
        actions.record(call("get_video_comments", args("video_id", "av1")), ok(comments("av1", "page:3")))
        actions.record(call("get_video_comments", args("video_id", "av2")), ok(comments("av2", "page:4")))
        assertEquals(setOf("av1" to "page:3", "av2" to "page:4"), choices(actions, "get_video_comments")
            .filter { it.has("cursor") }.map { it.getString("video_id") to it.getString("cursor") }.toSet())
    }

    fun aDifferentRegionReadCannotOverwriteTheSelectedRegionsContinuation() {
        val actions = AgentDecisionActions("查看游戏区热榜")
        actions.record(call("get_regions"), ok(JSONObject().put("source", "host_rpc").put("dataset", "ranking_regions")
            .put("regions", JSONArray().put(JSONObject().put("name", "游戏").put("region_id", "17").put("region_type", 2))
                .put(JSONObject().put("name", "音乐").put("region_id", "3").put("region_type", 2)))))
        for ((region, cursor) in listOf("17" to "page:5", "3" to "page:6")) {
            actions.record(call("get_ranking", args("region_id", region)), ok(JSONObject().put("source", "host_rpc")
                .put("dataset", "ranking").put("region_id", region).put("videos", JSONArray().put(video("av$region")
                    .put("ranking_position", 1))).put("next_cursor", cursor)))
        }
        assertEquals(listOf("17" to "page:5"), choices(actions, "get_ranking").filter { it.has("cursor") }.map {
            it.getString("region_id") to it.getString("cursor")
        })
    }

    fun cancelledAcknowledgementCannotTransferEvenWhenItClaimsTheWindowWasReleased() {
        val routing = initialRouting()
        assertFalse(routing.handoff(ok(JSONObject().put("status", "cancelled").put("native_window_released", true))))
        assertTrue(routing.nativeActive)
        assertFalse(routing.uiOnly)
    }

    fun finishedAcknowledgementStillRequiresAnExplicitWindowRelease() {
        val routing = initialRouting()
        assertFalse(routing.handoff(ok(JSONObject().put("status", "finished"))))
        assertFalse(routing.handoff(ok(JSONObject().put("status", "finished").put("native_window_released", false))))
        assertTrue(routing.nativeActive)
        assertFalse(routing.uiOnly)
    }

    fun confirmedFinishedReleasePerformsAOneWayTransfer() {
        val routing = initialRouting()
        assertTrue(routing.handoff(ok(JSONObject().put("status", "finished").put("native_window_released", true))))
        assertFalse(routing.nativeActive)
        assertTrue(routing.uiOnly)
        assertThrows(IllegalStateException::class.java) { routing.startingNative() }
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (AgentNativeMenuRegressionTest) -> Unit) {
            val s = AgentNativeMenuRegressionTest()
            block(s)
        }
        runIsolated { s -> s.oneUnsupportedNativeOperationDoesNotRemoveAnotherAvailableNativeMenu() }
        runIsolated { s -> s.observingUiDuringAnActiveNativeSessionDoesNotTransferTheMenuToUiOnly() }
        runIsolated { s -> s.readingOneUsersArchiveRetainsTheSecondUsersFirstRead() }
        runIsolated { s -> s.eachUsersContinuationRetainsItsOwnUserIdentityAndCursor() }
        runIsolated { s -> s.readingOneUsersLiveStatusRetainsTheSecondUsersStatusMenu() }
        runIsolated { s -> s.readingOneVideosMetricsRetainsTheSecondExplicitVideosMenu() }
        runIsolated { s -> s.readingOneVideosCommentsRetainsTheSecondExplicitVideosFirstRead() }
        runIsolated { s -> s.differentVideoCommentPagesRetainIndependentContinuations() }
        runIsolated { s -> s.aDifferentRegionReadCannotOverwriteTheSelectedRegionsContinuation() }
        runIsolated { s -> s.cancelledAcknowledgementCannotTransferEvenWhenItClaimsTheWindowWasReleased() }
        runIsolated { s -> s.finishedAcknowledgementStillRequiresAnExplicitWindowRelease() }
        runIsolated { s -> s.confirmedFinishedReleasePerformsAOneWayTransfer() }
    
        AgentNativePrerequisiteCases().verifyAllCases()
        AgentNavigationEvidenceCases().verifyAllCases()
        AgentNotificationLogModeCases().verifyAllCases()
        AgentNotificationPolicyCases().verifyAllCases()
        AgentPermissionWriteCases().verifyAllCases()
    }
}

private class AgentNativePrerequisiteCases {
    private fun next(evidence: AgentGoalEvidence, operation: String, identity: String? = null): Pair<String, JSONObject> {
        val read = checkNotNull(evidence.nextNativeRead { true })
        assertEquals(operation, read.first)
        if (identity != null) assertEquals(identity, read.second.optString(if (operation in setOf("get_live_status", "get_user_videos")) "user_id" else "video_id"))
        return read
    }
    private fun record(evidence: AgentGoalEvidence, read: Pair<String, JSONObject>, data: JSONObject) =
        evidence.record(read.first, read.second, JSONObject().put("ok", true).put("data", data))
    private fun ranking(vararg rows: Pair<Int, String>, region: String = "0") = JSONObject().put("source", "host_rpc").put("dataset", "ranking")
        .put("region_id", region).put("videos", JSONArray(rows.map { (position, id) -> JSONObject().put("video_id", id).put("ranking_position", position) }))
    private fun metrics(id: String, value: Any = 42L) = JSONObject().put("source", "host_rpc").put("video_id", id)
        .put("statistics", JSONObject().put("status", "reported").put("view_count", value))
    private fun comments(id: String) = JSONObject().put("source", "host_rpc").put("dataset", "comments").put("video_id", id)
        .put("comments", JSONArray().put(JSONObject().put("video_id", id).put("message", "实际评论正文")))

    fun rankingThenItsVerifiedIdentityMetricsAreReadBeforePlanning() {
        val evidence = AgentGoalEvidence("查全站热榜第一名视频的真实视频ID、标题和当前播放量")
        record(evidence, next(evidence, "get_ranking"), ranking(2 to "av2", 1 to "av42"))
        assertFalse(evidence.complete())
        record(evidence, next(evidence, "get_video_metrics", "av42"), metrics("av42"))
        assertTrue(evidence.complete())
        assertNull(evidence.nextNativeRead { true })
    }

    fun desiredRankMetricsDoNotUseFirstReturnedCard() {
        val evidence = AgentGoalEvidence("查热榜第二名视频的播放量")
        record(evidence, next(evidence, "get_ranking"), ranking(1 to "av1", 2 to "av2"))
        record(evidence, next(evidence, "get_video_metrics", "av2"), metrics("av2"))
        assertTrue(evidence.complete())
        assertNull(evidence.nextNativeRead { true })
    }

    fun missingRankingPositionDoesNotGenerateAnInventedMetricIdentity() {
        val evidence = AgentGoalEvidence("查热榜第一名的播放量")
        record(evidence, next(evidence, "get_ranking"), ranking(2 to "av2"))
        assertNull(evidence.nextNativeRead { true })
        assertFalse(evidence.complete())
        assertTrue(evidence.limitation().contains("名次"))
    }

    fun regionalCatalogMustVerifyRegionBeforeReadingRankingAndMetrics() {
        val evidence = AgentGoalEvidence("查游戏分区热榜第一名的播放量")
        record(evidence, next(evidence, "get_regions"), JSONObject().put("source", "host_rpc").put("dataset", "ranking_regions")
            .put("regions", JSONArray().put(JSONObject().put("name", "游戏").put("region_id", "4").put("region_type", 2))))
        val rank = next(evidence, "get_ranking")
        assertEquals("4", rank.second.getString("region_id"))
        record(evidence, rank, ranking(1 to "av4", region = "4"))
        record(evidence, next(evidence, "get_video_metrics", "av4"), metrics("av4"))
        assertTrue(evidence.complete())
    }

    fun unverifiedRegionNeverFallsBackToGlobalRanking() {
        val evidence = AgentGoalEvidence("查游戏分区热榜第一名的播放量")
        record(evidence, next(evidence, "get_regions"), JSONObject().put("source", "host_rpc").put("dataset", "ranking_regions").put("regions", JSONArray()))
        assertNull(evidence.nextNativeRead { true })
        assertFalse(evidence.complete())
    }

    fun missingStatisticIsRetainedAsLimitedAndNotAutomaticallyRetried() {
        val evidence = AgentGoalEvidence("查热榜第一名播放量")
        record(evidence, next(evidence, "get_ranking"), ranking(1 to "av1"))
        record(evidence, next(evidence, "get_video_metrics", "av1"), metrics("av1", JSONObject.NULL))
        repeat(4) { assertNull(evidence.nextNativeRead { true }) }
        assertFalse(evidence.complete())
        assertTrue(evidence.canReportLimitation())
        assertTrue(evidence.limitation().contains("未知数值"))
    }

    fun failedIdentityIsNotRetriedWhileOtherExplicitVideosStillGetRead() {
        val evidence = AgentGoalEvidence("查看 av1 和 av2 的播放量")
        val first = next(evidence, "get_video_metrics", "av1")
        evidence.record(first.first, first.second, JSONObject().put("ok", false).put("error", "rpc_failed"))
        record(evidence, next(evidence, "get_video_metrics", "av2"), metrics("av2"))
        assertNull(evidence.nextNativeRead { true })
        assertFalse(evidence.complete())
        assertTrue(evidence.canReportLimitation())
    }

    fun explicitMultipleVideosReadBothMetricsAndCommentsWithTheirOwnIdentity() {
        val evidence = AgentGoalEvidence("查看 av1 与 av2 的播放量和评论内容")
        record(evidence, next(evidence, "get_video_comments", "av1"), comments("av1"))
        record(evidence, next(evidence, "get_video_metrics", "av1"), metrics("av1"))
        assertFalse(evidence.complete())
        record(evidence, next(evidence, "get_video_comments", "av2"), comments("av2"))
        record(evidence, next(evidence, "get_video_metrics", "av2"), metrics("av2"))
        assertTrue(evidence.complete())
        assertNull(evidence.nextNativeRead { true })
    }

    fun bvCommentsWaitForActualCanonicalAvThenQueryTheSameVideoMetrics() {
        val bv = "BV0000000001"
        val evidence = AgentGoalEvidence("查看 $bv 的播放量和评论内容")
        record(evidence, next(evidence, "get_video_details", bv), JSONObject().put("source", "host_rpc").put("video_id", bv).put("canonical_video_id", "av42"))
        record(evidence, next(evidence, "get_video_comments", "av42"), comments("av42"))
        record(evidence, next(evidence, "get_video_metrics", "av42"), metrics("av42"))
        assertTrue(evidence.complete())
        assertNull(evidence.nextNativeRead { true })
    }

    fun failedBvResolutionNeverGuessesTheCommentOid() {
        val evidence = AgentGoalEvidence("查看 BV0000000001 的评论内容")
        val read = next(evidence, "get_video_details", "BV0000000001")
        evidence.record(read.first, read.second, JSONObject().put("ok", false).put("error", "rpc_failed"))
        assertNull(evidence.nextNativeRead { true })
        assertFalse(evidence.complete())
    }

    fun actualAliasLearnedAfterMetricsPreservesEvidenceWithoutReadingTheSameVideoAgain() {
        val bv = "BV0000000001"
        val evidence = AgentGoalEvidence("查看 $bv 的播放量")
        record(evidence, next(evidence, "get_video_metrics", bv), metrics(bv))
        evidence.record("get_video_details", JSONObject().put("video_id", bv), JSONObject().put("ok", true)
            .put("data", JSONObject().put("source", "host_rpc").put("video_id", bv).put("canonical_video_id", "av42")))
        assertNull(evidence.nextNativeRead { true })
        assertTrue(evidence.complete())
    }

    fun bothExplicitUsersReceiveSeparateLiveAndArchiveFirstReads() {
        val evidence = AgentGoalEvidence("查看 UID 1 与 UID 2 的直播状态及投稿")
        for (id in listOf("1", "2")) {
            record(evidence, next(evidence, "get_live_status", id), JSONObject().put("source", "host_rpc").put("dataset", "up_live_status")
                .put("user_id", id).put("live_status", "offline"))
            record(evidence, next(evidence, "get_user_videos", id), JSONObject().put("source", "host_rpc").put("dataset", "user_videos")
                .put("requested_user_id", id).put("items", JSONArray().put(JSONObject().put("video_id", "av$id"))))
            if (id == "1") assertFalse(evidence.complete())
        }
        assertTrue(evidence.complete())
        assertNull(evidence.nextNativeRead { true })
    }

    fun unknownLiveStatusDoesNotCauseAutomaticRepeatOrSkipTheOtherUser() {
        val evidence = AgentGoalEvidence("查看 UID 1 和 UID 2 的直播状态")
        for (id in listOf("1", "2")) record(evidence, next(evidence, "get_live_status", id), JSONObject().put("source", "host_rpc")
            .put("dataset", "up_live_status").put("user_id", id).put("live_status", "unknown"))
        assertNull(evidence.nextNativeRead { true })
        assertFalse(evidence.complete())
        assertTrue(evidence.limitation().contains("未知状态"))
    }

    fun modelReadAlsoPreventsDuplicateAutomaticFirstReadButUnlocksItsPrerequisite() {
        val evidence = AgentGoalEvidence("查热榜第一名的播放量")
        evidence.record("get_ranking", JSONObject(), JSONObject().put("ok", true).put("data", ranking(1 to "av42")))
        record(evidence, next(evidence, "get_video_metrics", "av42"), metrics("av42"))
        assertNull(evidence.nextNativeRead { true })
    }

    fun currentVideoMetricsRequireActualForegroundGuardedIdentity() {
        val evidence = AgentGoalEvidence("查询当前视频播放量")
        record(evidence, next(evidence, "get_host_state"), JSONObject().put("source", "host_rpc").put("foreground", true)
            .put("interaction_guard", true).put("video_id", "av7"))
        record(evidence, next(evidence, "get_video_metrics", "av7"), metrics("av7"))
        assertTrue(evidence.complete())
    }

    fun temporarilyUnfocusedOwnedReadContextSuppliesMetricsIdentityButDoesNotConfirmOpening() {
        val data = JSONObject().put("source", "host_rpc").put("foreground", false).put("native_read_context", true)
            .put("interaction_guard", true).put("video_id", "av7").put("page", "video").put("task_page", true).put("navigation_pending", false)
        val evidence = AgentGoalEvidence("查询当前视频播放量")
        record(evidence, next(evidence, "get_host_state"), data)
        record(evidence, next(evidence, "get_video_metrics", "av7"), metrics("av7"))
        assertTrue(evidence.complete())
        val open = AgentGoalEvidence("打开 av7")
        open.record("open_video", JSONObject().put("video_id", "av7"), JSONObject().put("ok", true)
            .put("data", JSONObject().put("source", "host_rpc").put("navigation", "requested")))
        open.record("get_host_state", JSONObject(), JSONObject().put("ok", true).put("data", data))
        assertFalse(open.complete())
        val navigation = AgentNavigationEvidence().apply { requested("av7") }
        assertFalse(navigation.observe(data))
        assertEquals("av7", navigation.pending)
    }

    fun untrustedOrUnownedOrCoercedContextCannotSupplyCurrentVideoIdentity() {
        fun state() = JSONObject().put("source", "host_rpc").put("foreground", false).put("native_read_context", true)
            .put("interaction_guard", true).put("video_id", "av7")
        for (data in listOf(state().put("source", "accessibility").put("foreground", true), state().apply { remove("source") },
            state().put("interaction_guard", false), state().put("native_read_context", false), state().put("native_read_context", "true"))) {
            val evidence = AgentGoalEvidence("查询当前视频播放量")
            record(evidence, next(evidence, "get_host_state"), data)
            assertTrue(evidence.targetVideoIds().isEmpty())
            assertNull(evidence.nextNativeRead { true })
            assertFalse(evidence.complete())
        }
    }

    fun unavailableNativeOperationIsSkippedForExistingUiFallback() {
        val evidence = AgentGoalEvidence("查热榜第一名的播放量")
        record(evidence, next(evidence, "get_ranking"), ranking(1 to "av42"))
        assertNull(evidence.nextNativeRead { it != "get_video_metrics" })
        assertFalse(evidence.complete())
        assertTrue(evidence.canReportLimitation())
    }

    fun accumulatedFirstReadsStayBoundedInsteadOfRestartingAnUnlimitedAutomaticQueue() {
        val evidence = AgentGoalEvidence("查看 av9999 的播放量")
        repeat(512) { index ->
            evidence.record("get_video_metrics", JSONObject().put("video_id", "av${index + 1}"), JSONObject().put("ok", false).put("error", "rpc_failed"))
        }
        assertNull(evidence.nextNativeRead { true })
        assertFalse(evidence.complete())
        assertTrue(evidence.canReportLimitation())
    }

    fun negativeOpenAndPlaybackDoNotBlockACompletedReadGoal() {
        for (negative in listOf("不要播放视频", "不要打开或播放视频", "不用打开视频", "别自动播放视频", "无需点开视频")) {
            val evidence = AgentGoalEvidence("查热榜第一名视频播放量，$negative")
            assertFalse(evidence.wantsPlaying)
            assertFalse(evidence.wantsOpen)
            record(evidence, next(evidence, "get_ranking"), ranking(1 to "av1"))
            record(evidence, next(evidence, "get_video_metrics", "av1"), metrics("av1"))
            assertTrue(evidence.complete())
        }
    }

    fun affirmativeActionInAnotherClauseRetainsItsRealVerificationRequirement() {
        val evidence = AgentGoalEvidence("不要搜索 av1，打开并播放这个视频")
        assertTrue(evidence.wantsOpen)
        assertTrue(evidence.wantsPlaying)
        assertFalse(evidence.complete())
        val open = AgentGoalEvidence("不要播放 av1，但打开该视频")
        assertFalse(open.wantsPlaying)
        assertTrue(open.wantsOpen)
        assertFalse(open.complete())
    }

    fun optionalUiReadWithoutAccessibilityDoesNotStopAnActiveNativeTask() {
        assertFalse(agentHostFailureStopsTask("get_ui_state", "accessibility_not_connected", true))
        assertTrue(agentHostFailureStopsTask("get_ui_state", "accessibility_not_connected", false))
        assertTrue(agentHostFailureStopsTask("click_ui", "accessibility_not_connected", true))
        assertTrue(agentHostFailureStopsTask("get_video_metrics", "host_not_foreground", true))
        assertTrue(agentHostFailureStopsTask("get_ui_state", "device_locked", true))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (AgentNativePrerequisiteCases) -> Unit) {
            val s = AgentNativePrerequisiteCases()
            block(s)
        }
        runIsolated { s -> s.rankingThenItsVerifiedIdentityMetricsAreReadBeforePlanning() }
        runIsolated { s -> s.desiredRankMetricsDoNotUseFirstReturnedCard() }
        runIsolated { s -> s.missingRankingPositionDoesNotGenerateAnInventedMetricIdentity() }
        runIsolated { s -> s.regionalCatalogMustVerifyRegionBeforeReadingRankingAndMetrics() }
        runIsolated { s -> s.unverifiedRegionNeverFallsBackToGlobalRanking() }
        runIsolated { s -> s.missingStatisticIsRetainedAsLimitedAndNotAutomaticallyRetried() }
        runIsolated { s -> s.failedIdentityIsNotRetriedWhileOtherExplicitVideosStillGetRead() }
        runIsolated { s -> s.explicitMultipleVideosReadBothMetricsAndCommentsWithTheirOwnIdentity() }
        runIsolated { s -> s.bvCommentsWaitForActualCanonicalAvThenQueryTheSameVideoMetrics() }
        runIsolated { s -> s.failedBvResolutionNeverGuessesTheCommentOid() }
        runIsolated { s -> s.actualAliasLearnedAfterMetricsPreservesEvidenceWithoutReadingTheSameVideoAgain() }
        runIsolated { s -> s.bothExplicitUsersReceiveSeparateLiveAndArchiveFirstReads() }
        runIsolated { s -> s.unknownLiveStatusDoesNotCauseAutomaticRepeatOrSkipTheOtherUser() }
        runIsolated { s -> s.modelReadAlsoPreventsDuplicateAutomaticFirstReadButUnlocksItsPrerequisite() }
        runIsolated { s -> s.currentVideoMetricsRequireActualForegroundGuardedIdentity() }
        runIsolated { s -> s.temporarilyUnfocusedOwnedReadContextSuppliesMetricsIdentityButDoesNotConfirmOpening() }
        runIsolated { s -> s.untrustedOrUnownedOrCoercedContextCannotSupplyCurrentVideoIdentity() }
        runIsolated { s -> s.unavailableNativeOperationIsSkippedForExistingUiFallback() }
        runIsolated { s -> s.accumulatedFirstReadsStayBoundedInsteadOfRestartingAnUnlimitedAutomaticQueue() }
        runIsolated { s -> s.negativeOpenAndPlaybackDoNotBlockACompletedReadGoal() }
        runIsolated { s -> s.affirmativeActionInAnotherClauseRetainsItsRealVerificationRequirement() }
        runIsolated { s -> s.optionalUiReadWithoutAccessibilityDoesNotStopAnActiveNativeTask() }
    }
}

private class AgentNavigationEvidenceCases {
    private fun state(id: String = "av1") = JSONObject().put("foreground", true).put("task_page", true)
        .put("page", "video").put("video_id", id).put("navigation_pending", false).put("rendered_content_verified", false)
    fun incompleteWrongOrCoercedStatesCannotClearPendingNavigation() {
        val navigation = AgentNavigationEvidence().apply { requested("av1") }
        for (data in listOf(state("av2"), state().put("foreground", false), state().put("task_page", false),
            state().put("navigation_pending", true), state().put("page", "search"), state().put("foreground", "true"),
            state().apply { remove("navigation_pending") })) {
            assertFalse(navigation.observe(data)); assertEquals("av1", navigation.pending)
        }
        assertTrue(navigation.observe(state()))
        assertNull(navigation.pending)
    }
    fun onlyVerifiedHostAliasesCanConfirmTheRequestedBvWithAnAvWindowIdentity() {
        val bv = "BV0000000001"
        val navigation = AgentNavigationEvidence().apply { requested(bv) }
        assertFalse(navigation.observe(state("av42")))
        navigation.recordAliases(JSONObject().put("source", "host_rpc").put("canonical_video_id", "av42")
            .put("video_aliases", JSONArray(listOf(bv, "av42"))))
        assertTrue(navigation.observe(state("av42")))
    }
    fun navigationRequestCannotSatisfyOpeningAndOpeningDoesNotEstablishPlayback() {
        val open = AgentGoalEvidence("打开 av1")
        val requested = JSONObject().put("ok", true).put("data", JSONObject().put("navigation", "requested").put("observed", false))
        open.record("open_video", JSONObject().put("video_id", "av1"), requested)
        assertFalse(open.complete())
        open.record("get_host_state", JSONObject(), JSONObject().put("ok", true).put("data", state()))
        assertTrue(open.complete())
        val play = AgentGoalEvidence("播放 av1")
        play.record("open_video", JSONObject().put("video_id", "av1"), requested)
        play.record("get_host_state", JSONObject(), JSONObject().put("ok", true).put("data", state()))
        assertFalse(play.complete())
        assertTrue(play.limitation().contains("不能证明正在播放"))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (AgentNavigationEvidenceCases) -> Unit) {
            val s = AgentNavigationEvidenceCases()
            block(s)
        }
        runIsolated { s -> s.incompleteWrongOrCoercedStatesCannotClearPendingNavigation() }
        runIsolated { s -> s.onlyVerifiedHostAliasesCanConfirmTheRequestedBvWithAnAvWindowIdentity() }
        runIsolated { s -> s.navigationRequestCannotSatisfyOpeningAndOpeningDoesNotEstablishPlayback() }
    }
}

private class AgentNotificationLogModeCases {
    private val current = "00000000-0000-4000-8000-000000000001"
    private val other = "00000000-0000-4000-8000-000000000002"
    private val old = "00000000-0000-4000-8000-000000000003"
    private val next = "00000000-0000-4000-8000-000000000004"

    fun activeTaskCanToggleLogsAndReturnToStatusWithoutChangingTaskIdentity() {
        val display = AgentNotificationLogMode()
        display.synchronize(current)
        assertFalse(display.showing(current))
        assertTrue(display.toggle(current, current, true))
        assertTrue(display.showing(current))
        assertFalse(display.showing(other))
        assertTrue(display.toggle(current, current, true))
        assertFalse(display.showing(current))
    }

    fun staleNotificationCannotToggleTheNextTaskOrEraseItsCurrentDisplay() {
        val display = AgentNotificationLogMode()
        display.synchronize(old)
        assertTrue(display.toggle(old, old, true))
        display.synchronize(next)
        assertFalse(display.showing(next))
        assertFalse(display.toggle(old, next, true))
        assertTrue(display.toggle(next, next, true))
        assertFalse(display.toggle(old, next, true))
        assertTrue(display.showing(next))
    }

    fun stoppedMissingAndMismatchedOwnershipCannotEnterLogMode() {
        val display = AgentNotificationLogMode()
        assertFalse(display.toggle(current, current, true))
        display.synchronize(current)
        assertFalse(display.toggle(current, current, false))
        assertFalse(display.toggle(current, null, true))
        assertFalse(display.toggle(current, other, true))
        assertFalse(display.toggle("", "", true))
        assertFalse(display.showing(current))
    }

    fun observingTheSameTaskRetainsDisplayButDestroyingItsOwnerClearsIt() {
        val display = AgentNotificationLogMode()
        display.synchronize(current)
        display.toggle(current, current, true)
        display.synchronize(current)
        assertTrue(display.showing(current))
        display.synchronize(null)
        assertFalse(display.showing(current))
        assertFalse(display.toggle(current, current, true))
    }

    fun notificationSelectsOnlyTheLastSixCurrentTaskEnumEvents() {
        var now = 0L
        val log = AgentExecutionLog({ now }, { 1_000L })
        assertTrue(log.begin(current))
        repeat(20) { index ->
            now++
            assertTrue(log.record(current, AgentLogPhase.READING, AgentLogStatus.SUCCEEDED,
                source = 1, tool = AgentLogTool.DETAILS, step = index.toLong()))
        }
        val snapshot = log.snapshot()
        val selected = AgentNotificationLogContent.recent(snapshot, current)
        assertEquals(6, selected.size)
        assertEquals((14L..19L).toList(), selected.map { it.step })
        assertTrue(selected.all { it.tool == AgentLogTool.DETAILS })
        assertEquals(21, snapshot.tasks.single().events.size)
    }

    fun missingCurrentTaskDoesNotFallBackToAnotherTasksExecutionLog() {
        val log = AgentExecutionLog({ 0L }, { 1_000L })
        assertTrue(log.begin(other))
        assertTrue(log.record(other, AgentLogPhase.SEARCHING, tool = AgentLogTool.SEARCH))
        assertTrue(AgentNotificationLogContent.recent(log.snapshot(), current).isEmpty())
    }

    fun currentTaskSelectionDoesNotExposeEarlierTaskEvents() {
        val log = AgentExecutionLog({ 0L }, { 1_000L })
        assertTrue(log.begin(other))
        assertTrue(log.record(other, AgentLogPhase.SEARCHING, source = 2, tool = AgentLogTool.SEARCH))
        assertTrue(log.begin(current))
        assertTrue(log.record(current, AgentLogPhase.READING, source = 1, tool = AgentLogTool.DETAILS))
        val selected = AgentNotificationLogContent.recent(log.snapshot(), current)
        assertEquals(2, selected.size)
        assertEquals(AgentLogTool.DETAILS, selected.last().tool)
        assertTrue(selected.none { it.source == 2 || it.tool == AgentLogTool.SEARCH })
    }

    fun verifyAllCases() {
        fun runIsolated(block: (AgentNotificationLogModeCases) -> Unit) {
            val s = AgentNotificationLogModeCases()
            block(s)
        }
        runIsolated { s -> s.activeTaskCanToggleLogsAndReturnToStatusWithoutChangingTaskIdentity() }
        runIsolated { s -> s.staleNotificationCannotToggleTheNextTaskOrEraseItsCurrentDisplay() }
        runIsolated { s -> s.stoppedMissingAndMismatchedOwnershipCannotEnterLogMode() }
        runIsolated { s -> s.observingTheSameTaskRetainsDisplayButDestroyingItsOwnerClearsIt() }
        runIsolated { s -> s.notificationSelectsOnlyTheLastSixCurrentTaskEnumEvents() }
        runIsolated { s -> s.missingCurrentTaskDoesNotFallBackToAnotherTasksExecutionLog() }
        runIsolated { s -> s.currentTaskSelectionDoesNotExposeEarlierTaskEvents() }
    }
}

private class AgentNotificationPolicyCases {
    private fun waiting(step: Long = 1, operation: String = "click_ui", success: Boolean = true) =
        AgentTaskState(true, "waiting_response", step, lastOperation = operation, lastOperationSucceeded = success, lastOperationStep = step)
    fun fastActionResultSurvivesTheFollowingWait() {
        val policy = AgentNotificationPolicy()
        assertEquals(0L, policy.offer(waiting(), 100))
        val visible = policy.take(100)!!
        assertEquals("waiting_response", visible.phase)
        assertEquals("click_ui", visible.lastOperation)
        assertTrue(visible.showRecentOperation)
        assertEquals(900L, policy.delay(100))
    }
    fun repeatedWaitsDoNotRestartOrLoseTheExpiryDeadline() {
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
    fun realNewActionReplacesOldStatusWithoutReplayingAQueue() {
        val policy = AgentNotificationPolicy()
        policy.offer(waiting(), 0); policy.take(0)
        policy.offer(waiting(2, "swipe_ui"), 50)
        val next = policy.take(200)!!
        assertEquals("swipe_ui", next.lastOperation)
        assertEquals(2L, next.lastOperationStep)
    }
    fun failedOperationsAreNeverShownAsSuccess() {
        val policy = AgentNotificationPolicy()
        policy.offer(waiting(success = false), 0)
        assertEquals(false, policy.take(0)!!.lastOperationSucceeded)
    }
    fun stoppingAndTaskTerminationTakePriority() {
        val policy = AgentNotificationPolicy()
        policy.offer(waiting(), 0); policy.take(0)
        assertEquals(0L, policy.offer(AgentTaskState(true, "stopping"), 20))
        assertEquals("stopping", policy.take(20)!!.phase)
        policy.offer(AgentTaskState(phase = "finished"), 21)
        assertFalse(policy.take(21)!!.running)
        assertNull(policy.delay(21))
    }
    fun resettingForAnotherTaskRemovesPreviousAction() {
        val policy = AgentNotificationPolicy()
        policy.offer(waiting(), 0); policy.take(0); policy.reset()
        policy.offer(AgentTaskState(true, "connecting"), 10)
        assertFalse(policy.take(10)!!.showRecentOperation)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (AgentNotificationPolicyCases) -> Unit) {
            val s = AgentNotificationPolicyCases()
            block(s)
        }
        runIsolated { s -> s.fastActionResultSurvivesTheFollowingWait() }
        runIsolated { s -> s.repeatedWaitsDoNotRestartOrLoseTheExpiryDeadline() }
        runIsolated { s -> s.realNewActionReplacesOldStatusWithoutReplayingAQueue() }
        runIsolated { s -> s.failedOperationsAreNeverShownAsSuccess() }
        runIsolated { s -> s.stoppingAndTaskTerminationTakePriority() }
        runIsolated { s -> s.resettingForAnotherTaskRemovesPreviousAction() }
    }
}

private class AgentPermissionWriteCases {
    fun failedEnableRestoresMemoryEvenWhenDiskRemainsUnavailable() {
        var memory = false
        val writes = mutableListOf<Boolean>()
        val saved = AgentPreferences.writeEnabled(false, true) { value ->
            memory = value // Android preferences publish to memory before the disk result.
            writes += value
            false
        }
        assertFalse(saved)
        assertFalse("a subsequent task must still read disabled", memory)
        assertEquals(listOf(true, false), writes)
    }
    fun failedDisableReportsFailureAndRestoresThePreviouslyEnabledValue() {
        var memory = true
        assertFalse(AgentPreferences.writeEnabled(true, false) { value -> memory = value; false })
        assertTrue(memory)
    }
    fun successfulCommitDoesNotPerformASecondWrite() {
        val writes = mutableListOf<Boolean>()
        assertTrue(AgentPreferences.writeEnabled(false, true) { writes += it; true })
        assertEquals(listOf(true), writes)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (AgentPermissionWriteCases) -> Unit) {
            val s = AgentPermissionWriteCases()
            block(s)
        }
        runIsolated { s -> s.failedEnableRestoresMemoryEvenWhenDiskRemainsUnavailable() }
        runIsolated { s -> s.failedDisableReportsFailureAndRestoresThePreviouslyEnabledValue() }
        runIsolated { s -> s.successfulCommitDoesNotPerformASecondWrite() }
    }
}
