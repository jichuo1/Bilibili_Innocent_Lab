package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoRelateFilterFeatureInstallerTest {
    fun normalizedTypeAndMissingDurationPublishSeparateCapabilityOutcomes() {
        val points = requireNotNull(VersionAdapter.locateVideoRelate(requireNotNull(javaClass.classLoader)))
            .copy(directDurationGetters = emptyList(), durationChains = emptyList())
        val records = mutableListOf<FeatureInstallRecord>()
        val env = environment(mutableListOf()).copy(installationEvidence = { records += it })
        FeatureInstallCoordinator(env).installAll(listOf(VideoRelateFilterFeatureInstaller(
            hiddenTypes = setOf("game"), minDurationSeconds = 30, maxDurationSeconds = 0, points = points
        )))
        assertTrue(records.last { it.id == "video_related_game_removed" }.result is FeatureInstallResult.Installed)
        assertEquals(FeatureSkipReason.MISSING_HOST_STRUCTURE,
            (records.last { it.id == "video_related_duration_filter" }.result as FeatureInstallResult.Skipped).reasonCode)
    }

    fun normalizedTypeAndMissingPlayCountPublishSeparateCapabilityOutcomes() {
        val points = requireNotNull(VersionAdapter.locateVideoRelate(requireNotNull(javaClass.classLoader)))
            .copy(playCountChains = emptyList())
        val records = mutableListOf<FeatureInstallRecord>()
        val env = environment(mutableListOf()).copy(installationEvidence = { records += it })
        FeatureInstallCoordinator(env).installAll(listOf(VideoRelateFilterFeatureInstaller(
            hiddenTypes = setOf("game"), minDurationSeconds = 0, maxDurationSeconds = 0,
            minPlayCount = 10_000, maxPlayCount = 0, points = points
        )))
        assertTrue(records.last { it.id == "video_related_game_removed" }.result is FeatureInstallResult.Installed)
        assertEquals(FeatureSkipReason.MISSING_HOST_STRUCTURE,
            (records.last { it.id == "video_related_play_count_filter" }.result as FeatureInstallResult.Skipped).reasonCode)
    }


    fun `publish text chains cover the basic info desc and the legacy relate desc`() {
        val points = requireNotNull(VersionAdapter.locateVideoRelate(requireNotNull(javaClass.classLoader)))
        val labels = points.publishTextChains.map { chain -> chain.steps.joinToString("->") { it.methodName } }

        assertTrue(labels.toString(), "getBasicInfo->getDesc" in labels)
        assertTrue(labels.toString(), "getDesc" in labels)
        assertEquals(
            points.publishTextChains,
            VersionAdapter.VideoRelatePoints.fromJson(points.toJson()).publishTextChains
        )
        // 老缓存没有这个键：降级成空表，不影响其余维度解析。
        val legacy = points.toJson().apply { remove("publish_text_chains") }
        assertEquals(emptyList<VersionAdapter.ReasonMethodChain>(),
            VersionAdapter.VideoRelatePoints.fromJson(legacy).publishTextChains)
    }

    fun `publish time filter alone installs and reports usable coverage`() {
        val statuses = mutableListOf<Pair<String, String>>()
        val points = requireNotNull(VersionAdapter.locateVideoRelate(requireNotNull(javaClass.classLoader)))
        val records = mutableListOf<FeatureInstallRecord>()
        val env = environment(statuses).copy(installationEvidence = { records += it })

        FeatureInstallCoordinator(env).installAll(listOf(VideoRelateFilterFeatureInstaller(
            hiddenTypes = emptySet(), minDurationSeconds = 0, maxDurationSeconds = 0,
            rawPublishTimeRule = "v1|after|r:2d", points = points
        )))

        assertTrue(records.last { it.id == "video_related_publish_time_filter" }.result is FeatureInstallResult.Installed)
        assertEquals(listOf("video_relate_filter_status" to "success"), statuses)
    }

    fun `missing publish text chains are partial instead of silently never matching`() {
        val statuses = mutableListOf<Pair<String, String>>()
        val points = requireNotNull(VersionAdapter.locateVideoRelate(requireNotNull(javaClass.classLoader)))
            .copy(publishTextChains = emptyList())
        val records = mutableListOf<FeatureInstallRecord>()
        val env = environment(statuses).copy(installationEvidence = { records += it })

        FeatureInstallCoordinator(env).installAll(listOf(VideoRelateFilterFeatureInstaller(
            hiddenTypes = setOf("game"), minDurationSeconds = 0, maxDurationSeconds = 0,
            rawPublishTimeRule = "v1|before|d:2021-06-04", points = points
        )))

        assertTrue(records.last { it.id == "video_related_game_removed" }.result is FeatureInstallResult.Installed)
        assertEquals(FeatureSkipReason.MISSING_HOST_STRUCTURE,
            (records.last { it.id == "video_related_publish_time_filter" }.result as FeatureInstallResult.Skipped).reasonCode)
        assertEquals(
            listOf("video_relate_filter_status" to "partial:missing-publish-time-accessor"),
            statuses
        )
    }

    fun `corrupt publish time rule alone leaves the feature disabled`() {
        val statuses = mutableListOf<Pair<String, String>>()
        val result = VideoRelateFilterFeatureInstaller(
            hiddenTypes = emptySet(), minDurationSeconds = 0, maxDurationSeconds = 0,
            rawPublishTimeRule = "v1|between|d:2021-09-01|d:2021-07-08",
            points = VersionAdapter.locateVideoRelate(requireNotNull(javaClass.classLoader))
        ).install(environment(statuses))

        assertEquals(FeatureInstallResult.Skipped("invalid-publish-time-rule"), result)
    }

    private fun hookCount(points: VersionAdapter.VideoRelatePoints): Int =
        points.responseItemGetters.size + if (points.detailRelateService != null) 1 else 0

    private fun environment(statuses: MutableList<Pair<String, String>>) = HookEnvironment(
        processName = "tv.danmaku.bili",
        classLoader = javaClass.classLoader,
        hookPoints = HookPointRegistry(javaClass.classLoader),
        registrar = TestHookRegistrar,
        logInfo = { _, _ -> },
        logError = { _, _ -> },
        reportStatus = { channel, status -> statuses += channel to status }
    )

    fun `normalizes protobuf card type prefix and matches exact type`() {
        assertTrue(
            VideoRelateFilterFeatureInstaller.shouldRemove(
                "CARD_TYPE_GAME",
                setOf("game")
            )
        )
        assertFalse(
            VideoRelateFilterFeatureInstaller.shouldRemove(
                "BANGUMI_AV",
                setOf("AV")
            )
        )
    }

    fun `matches old and new protocol evidence without coupling independent switches`() {
        assertTrue(
            VideoRelateFilterFeatureInstaller.matchesEvidence(
                types = emptySet(),
                fromSourceTypes = setOf(2L),
                relateCardTypeValues = emptySet(),
                hiddenTypes = setOf("CM")
            )
        )
        assertTrue(
            VideoRelateFilterFeatureInstaller.matchesEvidence(
                types = emptySet(),
                fromSourceTypes = setOf(2L),
                relateCardTypeValues = emptySet(),
                hiddenTypes = setOf("SPECIAL")
            )
        )
        assertTrue(
            VideoRelateFilterFeatureInstaller.matchesEvidence(
                types = emptySet(),
                fromSourceTypes = emptySet(),
                relateCardTypeValues = setOf(3),
                hiddenTypes = setOf("ADVERTISEMENT")
            )
        )
        assertTrue(
            VideoRelateFilterFeatureInstaller.matchesEvidence(
                types = emptySet(),
                fromSourceTypes = emptySet(),
                relateCardTypeValues = setOf(3, 5),
                hiddenTypes = setOf("SPECIAL")
            )
        )
        assertTrue(
            VideoRelateFilterFeatureInstaller.matchesEvidence(
                types = emptySet(),
                fromSourceTypes = emptySet(),
                relateCardTypeValues = emptySet(),
                hiddenTypes = setOf("CM"),
                relateCardTypes = setOf("RELATE_CARD_TYPE_RESOURCE")
            )
        )
        assertTrue(
            VideoRelateFilterFeatureInstaller.matchesEvidence(
                types = setOf("RESOURCE"),
                fromSourceTypes = emptySet(),
                relateCardTypeValues = emptySet(),
                hiddenTypes = setOf("SPECIAL")
            )
        )
        assertTrue(
            VideoRelateFilterFeatureInstaller.matchesEvidence(
                types = emptySet(),
                fromSourceTypes = emptySet(),
                relateCardTypeValues = setOf(4),
                hiddenTypes = setOf("GAME")
            )
        )
        assertTrue(
            VideoRelateFilterFeatureInstaller.matchesEvidence(
                types = emptySet(),
                fromSourceTypes = emptySet(),
                relateCardTypeValues = setOf(4),
                hiddenTypes = setOf("SPECIAL")
            )
        )
        assertTrue(
            VideoRelateFilterFeatureInstaller.matchesEvidence(
                types = emptySet(),
                fromSourceTypes = emptySet(),
                relateCardTypeValues = setOf(10),
                hiddenTypes = setOf("SPECIAL")
            )
        )
        assertFalse(
            VideoRelateFilterFeatureInstaller.matchesEvidence(
                types = emptySet(),
                fromSourceTypes = setOf(99L),
                relateCardTypeValues = setOf(99),
                hiddenTypes = setOf("CM", "GAME", "SPECIAL")
            )
        )
        assertFalse(
            VideoRelateFilterFeatureInstaller.matchesEvidence(
                types = emptySet(),
                fromSourceTypes = emptySet(),
                relateCardTypeValues = setOf(4),
                hiddenTypes = setOf("CM")
            )
        )
    }

    fun `strong mode distinguishes recognized and unknown card types`() {
        assertTrue(
            VideoRelateFilterFeatureInstaller.hasRecognizedTypeEvidence(
                VideoRelateTypeEvidence(
                    types = setOf("AV"),
                    relateCardTypes = emptySet(),
                    fromSourceTypes = emptySet(),
                    relateCardTypeValues = emptySet()
                )
            )
        )
        assertTrue(
            VideoRelateFilterFeatureInstaller.hasRecognizedTypeEvidence(
                VideoRelateTypeEvidence(
                    types = setOf("AI_CARD"),
                    relateCardTypes = emptySet(),
                    fromSourceTypes = emptySet(),
                    relateCardTypeValues = emptySet()
                )
            )
        )
        assertTrue(
            VideoRelateFilterFeatureInstaller.hasRecognizedTypeEvidence(
                VideoRelateTypeEvidence(
                    types = emptySet(),
                    relateCardTypes = emptySet(),
                    fromSourceTypes = setOf(2L),
                    relateCardTypeValues = emptySet()
                )
            )
        )
        assertFalse(
            VideoRelateFilterFeatureInstaller.hasRecognizedTypeEvidence(
                VideoRelateTypeEvidence(
                    types = setOf("FUTURE_UNSUPPORTED_CARD"),
                    relateCardTypes = emptySet(),
                    fromSourceTypes = setOf(99L),
                    relateCardTypeValues = setOf(99)
                )
            )
        )
    }

    fun `response writeback replaces the verified internal list only`() {
        val response = ResponseFixture()
        val replacement = arrayListOf("kept")
        val field = ResponseFixture::class.java.getDeclaredField("items").apply {
            isAccessible = true
        }

        assertTrue(writeBackVideoRelateItems(response, field, replacement))
        assertSame(replacement, response.items)
        assertFalse(writeBackVideoRelateItems(Any(), field, replacement))
    }

    fun `duration-only configuration installs while an empty range stays hook free`() {
        val durationStatuses = mutableListOf<Pair<String, String>>()
        val disabledStatuses = mutableListOf<Pair<String, String>>()
        val points = requireNotNull(
            VersionAdapter.locateVideoRelate(requireNotNull(javaClass.classLoader))
        )

        assertEquals(
            FeatureInstallResult.Installed(hookCount(points)),
            VideoRelateFilterFeatureInstaller(
                hiddenTypes = emptySet(),
                minDurationSeconds = 30,
                maxDurationSeconds = 0,
                points = points
            ).install(environment(durationStatuses))
        )
        assertEquals(
            FeatureInstallResult.Skipped("disabled"),
            VideoRelateFilterFeatureInstaller(
                hiddenTypes = emptySet(),
                minDurationSeconds = 0,
                maxDurationSeconds = 0,
                points = null
            ).install(environment(disabledStatuses))
        )
        assertEquals(listOf("video_relate_filter_status" to "success"), durationStatuses)
        assertEquals(listOf("video_relate_filter_status" to "disabled"), disabledStatuses)
    }

    fun `play-count-only configuration installs while an empty range stays hook free`() {
        val playCountStatuses = mutableListOf<Pair<String, String>>()
        val points = requireNotNull(
            VersionAdapter.locateVideoRelate(requireNotNull(javaClass.classLoader))
        )

        assertEquals(
            FeatureInstallResult.Installed(hookCount(points)),
            VideoRelateFilterFeatureInstaller(
                hiddenTypes = emptySet(),
                minDurationSeconds = 0,
                maxDurationSeconds = 0,
                minPlayCount = 10_000,
                maxPlayCount = 0,
                points = points
            ).install(environment(playCountStatuses))
        )
        assertEquals(listOf("video_relate_filter_status" to "success"), playCountStatuses)
    }

    fun `missing play count paths do not disable an existing type filter`() {
        val statuses = mutableListOf<Pair<String, String>>()
        val points = requireNotNull(
            VersionAdapter.locateVideoRelate(requireNotNull(javaClass.classLoader))
        ).copy(playCountChains = emptyList())

        assertEquals(
            FeatureInstallResult.Installed(hookCount(points), complete = false),
            VideoRelateFilterFeatureInstaller(
                hiddenTypes = setOf("game"),
                minDurationSeconds = 0,
                maxDurationSeconds = 0,
                minPlayCount = 10_000,
                maxPlayCount = 0,
                points = points
            ).install(environment(statuses))
        )
        assertEquals(
            listOf("video_relate_filter_status" to "partial:missing-play-count-accessor"),
            statuses
        )
    }

    fun `missing duration paths do not disable an existing type filter`() {
        val statuses = mutableListOf<Pair<String, String>>()
        val points = requireNotNull(
            VersionAdapter.locateVideoRelate(requireNotNull(javaClass.classLoader))
        ).copy(directDurationGetters = emptyList(), durationChains = emptyList())

        assertEquals(
            FeatureInstallResult.Installed(hookCount(points), complete = false),
            VideoRelateFilterFeatureInstaller(
                hiddenTypes = setOf("game"),
                minDurationSeconds = 30,
                maxDurationSeconds = 0,
                points = points
            ).install(environment(statuses))
        )
        assertEquals(
            listOf("video_relate_filter_status" to "partial:missing-duration-accessor"),
            statuses
        )
    }

    fun `missing type getters do not disable an adapted duration filter`() {
        val statuses = mutableListOf<Pair<String, String>>()
        val points = requireNotNull(
            VersionAdapter.locateVideoRelate(requireNotNull(javaClass.classLoader))
        ).copy(
            cardCaseGetters = emptyList(),
            gotoGetters = emptyList(),
            cardTypeGetters = emptyList(),
            relateCardTypeGetters = emptyList(),
            fromSourceTypeGetters = emptyList(),
            fromSourceTypeChains = emptyList(),
            relateCardTypeValueGetters = emptyList()
        )

        assertEquals(
            FeatureInstallResult.Installed(hookCount(points), complete = false),
            VideoRelateFilterFeatureInstaller(
                hiddenTypes = setOf("game"),
                minDurationSeconds = 30,
                maxDurationSeconds = 0,
                points = points
            ).install(environment(statuses))
        )
        assertEquals(
            listOf("video_relate_filter_status" to "partial:missing-type-getter"),
            statuses
        )
    }

    fun `custom reason filter requires enhancement and installs without type filters`() {
        val enabledStatuses = mutableListOf<Pair<String, String>>()
        val disabledStatuses = mutableListOf<Pair<String, String>>()
        val points = requireNotNull(
            VersionAdapter.locateVideoRelate(requireNotNull(javaClass.classLoader))
        )

        assertEquals(
            FeatureInstallResult.Installed(hookCount(points)),
            VideoRelateFilterFeatureInstaller(
                hiddenTypes = emptySet(),
                minDurationSeconds = 0,
                maxDurationSeconds = 0,
                matchingEnhancementEnabled = true,
                reasonFilterEnabled = true,
                rawReasonKeywords = "大家都在看",
                points = points
            ).install(environment(enabledStatuses))
        )
        assertEquals(
            FeatureInstallResult.Skipped("disabled"),
            VideoRelateFilterFeatureInstaller(
                hiddenTypes = emptySet(),
                minDurationSeconds = 0,
                maxDurationSeconds = 0,
                matchingEnhancementEnabled = false,
                reasonFilterEnabled = true,
                rawReasonKeywords = "大家都在看",
                points = points
            ).install(environment(disabledStatuses))
        )
        assertEquals(listOf("video_relate_filter_status" to "success"), enabledStatuses)
        assertEquals(listOf("video_relate_filter_status" to "disabled"), disabledStatuses)
    }

    fun `missing reason paths do not disable existing type filtering`() {
        val statuses = mutableListOf<Pair<String, String>>()
        val points = requireNotNull(
            VersionAdapter.locateVideoRelate(requireNotNull(javaClass.classLoader))
        ).copy(reasonChains = emptyList())

        assertEquals(
            FeatureInstallResult.Installed(hookCount(points), complete = false),
            VideoRelateFilterFeatureInstaller(
                hiddenTypes = setOf("CM"),
                minDurationSeconds = 0,
                maxDurationSeconds = 0,
                matchingEnhancementEnabled = true,
                reasonFilterEnabled = false,
                points = points
            ).install(environment(statuses))
        )
        assertEquals(
            listOf("video_relate_filter_status" to "partial:missing-reason-accessor"),
            statuses
        )
    }

    fun `strong mode requires enhancement and keeps missing reasons as an aggressive signal`() {
        val enabledStatuses = mutableListOf<Pair<String, String>>()
        val disabledStatuses = mutableListOf<Pair<String, String>>()
        val points = requireNotNull(
            VersionAdapter.locateVideoRelate(requireNotNull(javaClass.classLoader))
        ).copy(reasonChains = emptyList())

        assertEquals(
            FeatureInstallResult.Installed(hookCount(points), complete = false),
            VideoRelateFilterFeatureInstaller(
                hiddenTypes = emptySet(),
                minDurationSeconds = 0,
                maxDurationSeconds = 0,
                matchingEnhancementEnabled = true,
                strongModeEnabled = true,
                points = points
            ).install(environment(enabledStatuses))
        )
        assertEquals(
            FeatureInstallResult.Skipped("disabled"),
            VideoRelateFilterFeatureInstaller(
                hiddenTypes = emptySet(),
                minDurationSeconds = 0,
                maxDurationSeconds = 0,
                matchingEnhancementEnabled = false,
                strongModeEnabled = true,
                points = points
            ).install(environment(disabledStatuses))
        )
        assertEquals(
            listOf("video_relate_filter_status" to "partial:missing-reason-accessor"),
            enabledStatuses
        )
        assertEquals(listOf("video_relate_filter_status" to "disabled"), disabledStatuses)
    }
    private class ResponseFixture {
        var items: List<String> = listOf("original")
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (VideoRelateFilterFeatureInstallerTest) -> Unit) {
            val s = VideoRelateFilterFeatureInstallerTest()
            block(s)
        }
        runIsolated { s -> s.normalizedTypeAndMissingDurationPublishSeparateCapabilityOutcomes() }
        runIsolated { s -> s.normalizedTypeAndMissingPlayCountPublishSeparateCapabilityOutcomes() }
        runIsolated { s -> s.`publish text chains cover the basic info desc and the legacy relate desc`() }
        runIsolated { s -> s.`publish time filter alone installs and reports usable coverage`() }
        runIsolated { s -> s.`missing publish text chains are partial instead of silently never matching`() }
        runIsolated { s -> s.`corrupt publish time rule alone leaves the feature disabled`() }
        runIsolated { s -> s.`normalizes protobuf card type prefix and matches exact type`() }
        runIsolated { s -> s.`matches old and new protocol evidence without coupling independent switches`() }
        runIsolated { s -> s.`strong mode distinguishes recognized and unknown card types`() }
        runIsolated { s -> s.`response writeback replaces the verified internal list only`() }
        runIsolated { s -> s.`duration-only configuration installs while an empty range stays hook free`() }
        runIsolated { s -> s.`play-count-only configuration installs while an empty range stays hook free`() }
        runIsolated { s -> s.`missing play count paths do not disable an existing type filter`() }
        runIsolated { s -> s.`missing duration paths do not disable an existing type filter`() }
        runIsolated { s -> s.`missing type getters do not disable an adapted duration filter`() }
        runIsolated { s -> s.`custom reason filter requires enhancement and installs without type filters`() }
        runIsolated { s -> s.`missing reason paths do not disable existing type filtering`() }
        runIsolated { s -> s.`strong mode requires enhancement and keeps missing reasons as an aggressive signal`() }
    }
}
