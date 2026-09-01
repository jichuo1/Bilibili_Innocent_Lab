package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoRelateFilterFeatureInstallerTest {

    private fun environment(statuses: MutableList<Pair<String, String>>) = HookEnvironment(
        processName = "tv.danmaku.bili",
        classLoader = javaClass.classLoader,
        hookPoints = HookPointRegistry(javaClass.classLoader),
        registrar = TestHookRegistrar,
        logInfo = { _, _ -> },
        logError = { _, _ -> },
        reportStatus = { channel, status -> statuses += channel to status }
    )

    @Test
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

    @Test
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

    @Test
    fun `duration-only configuration installs while an empty range stays hook free`() {
        val durationStatuses = mutableListOf<Pair<String, String>>()
        val disabledStatuses = mutableListOf<Pair<String, String>>()
        val points = requireNotNull(
            VersionAdapter.locateVideoRelate(requireNotNull(javaClass.classLoader))
        )

        assertEquals(
            FeatureInstallResult.Installed(points.responseItemGetters.size),
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

    @Test
    fun `missing duration paths do not disable an existing type filter`() {
        val statuses = mutableListOf<Pair<String, String>>()
        val points = requireNotNull(
            VersionAdapter.locateVideoRelate(requireNotNull(javaClass.classLoader))
        ).copy(directDurationGetters = emptyList(), durationChains = emptyList())

        assertEquals(
            FeatureInstallResult.Installed(points.responseItemGetters.size),
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

    @Test
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
            FeatureInstallResult.Installed(points.responseItemGetters.size),
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

    @Test
    fun `custom reason filter requires enhancement and installs without type filters`() {
        val enabledStatuses = mutableListOf<Pair<String, String>>()
        val disabledStatuses = mutableListOf<Pair<String, String>>()
        val points = requireNotNull(
            VersionAdapter.locateVideoRelate(requireNotNull(javaClass.classLoader))
        )

        assertEquals(
            FeatureInstallResult.Installed(points.responseItemGetters.size),
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

    @Test
    fun `missing reason paths do not disable existing type filtering`() {
        val statuses = mutableListOf<Pair<String, String>>()
        val points = requireNotNull(
            VersionAdapter.locateVideoRelate(requireNotNull(javaClass.classLoader))
        ).copy(reasonChains = emptyList())

        assertEquals(
            FeatureInstallResult.Installed(points.responseItemGetters.size),
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
}
