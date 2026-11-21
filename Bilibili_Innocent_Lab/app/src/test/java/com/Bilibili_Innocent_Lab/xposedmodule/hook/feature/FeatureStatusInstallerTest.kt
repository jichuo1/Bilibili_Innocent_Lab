package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class FeatureStatusInstallerTest {

    private val statuses = mutableListOf<Pair<String, String>>()
    private val environment = HookEnvironment(
        processName = "test",
        classLoader = javaClass.classLoader,
        hookPoints = HookPointRegistry(javaClass.classLoader),
        registrar = TestHookRegistrar,
        logInfo = { _, _ -> },
        logError = { _, _ -> },
        reportStatus = { channel, status -> statuses += channel to status }
    )

    fun `paused ad reports disabled without resolving hook points`() {
        val result = PausedAdFeatureInstaller(enabled = false, points = null).install(environment)

        assertEquals(FeatureInstallResult.Skipped("disabled"), result)
        assertEquals(listOf("adskip_status" to "disabled"), statuses)
    }

    fun `home banner reports disabled without resolving hook points`() {
        val result = HomeBannerFeatureInstaller(
            enabled = false,
            point = null,
            collapseBanner = { false }
        ).install(environment)

        assertEquals(FeatureInstallResult.Skipped("disabled"), result)
        assertEquals(listOf("banner_ad_status" to "disabled"), statuses)
    }

    fun `game promotion reports disabled without resolving hook points`() {
        val result = GamePromotionFeatureInstaller(enabled = false).install(environment)

        assertEquals(FeatureInstallResult.Skipped("disabled"), result)
        assertEquals(listOf("gamecard_ad_status" to "disabled"), statuses)
    }

    fun `video detail app promotion reports disabled without resolving host classes`() {
        val result = DetailAppPromotionFeatureInstaller(enabled = false).install(environment)

        assertEquals(FeatureInstallResult.Skipped("disabled"), result)
        assertEquals(listOf("detail_app_promotion_status" to "disabled"), statuses)
    }

    fun `home top bar reports disabled without resolving hook points`() {
        val result = HomeTopBarFeatureInstaller(
            hideGameMenu = false,
            hideSearchDefaultWord = false,
            points = null
        ).install(environment)

        assertEquals(FeatureInstallResult.Skipped("disabled"), result)
        assertEquals(listOf("home_top_bar_status" to "disabled"), statuses)
    }

    fun `dynamic tabs reports disabled without resolving hook points`() {
        val result = DynamicTabsFeatureInstaller(
            hideCity = false,
            hideSchool = false,
            preferVideo = false,
            point = null
        ).install(environment)

        assertEquals(FeatureInstallResult.Skipped("disabled"), result)
        assertEquals(listOf("dynamic_tabs_status" to "disabled"), statuses)
    }

    fun `full numbers reports disabled without resolving hook points`() {
        val result = FullNumberFeatureInstaller(enabled = false, points = null)
            .install(environment)

        assertEquals(FeatureInstallResult.Skipped("disabled"), result)
        assertEquals(listOf("full_number_status" to "disabled"), statuses)
    }

    fun `player portrait control reports disabled without resolving hook points`() {
        val result = PlayerPortraitFeatureInstaller(enabled = false, points = null)
            .install(environment)

        assertEquals(FeatureInstallResult.Skipped("disabled"), result)
        assertEquals(listOf("player_portrait_status" to "disabled"), statuses)
    }

    fun `teenagers mode prompt reports disabled without resolving hook points`() {
        val result = TeenagersModeFeatureInstaller(enabled = false, points = null)
            .install(environment)

        assertEquals(FeatureInstallResult.Skipped("disabled"), result)
        assertEquals(listOf("teenagers_mode_status" to "disabled"), statuses)
    }

    fun `comment purify reports disabled without resolving hook points`() {
        val result = CommentPurifyFeatureInstaller(
            removeSearchLinks = false,
            removeEmptyGuide = false,
            removeVoteWidgets = false,
            removeFollowButtons = false,
            removeQoe = false,
            removeOperations = false,
            points = null
        )
            .install(environment)

        assertEquals(FeatureInstallResult.Skipped("disabled"), result)
        assertEquals(listOf("comment_purify_status" to "disabled"), statuses)
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (FeatureStatusInstallerTest) -> Unit) {
            val s = FeatureStatusInstallerTest()
            block(s)
        }
        runIsolated { s -> s.`paused ad reports disabled without resolving hook points`() }
        runIsolated { s -> s.`home banner reports disabled without resolving hook points`() }
        runIsolated { s -> s.`game promotion reports disabled without resolving hook points`() }
        runIsolated { s -> s.`video detail app promotion reports disabled without resolving host classes`() }
        runIsolated { s -> s.`home top bar reports disabled without resolving hook points`() }
        runIsolated { s -> s.`dynamic tabs reports disabled without resolving hook points`() }
        runIsolated { s -> s.`full numbers reports disabled without resolving hook points`() }
        runIsolated { s -> s.`player portrait control reports disabled without resolving hook points`() }
        runIsolated { s -> s.`teenagers mode prompt reports disabled without resolving hook points`() }
        runIsolated { s -> s.`comment purify reports disabled without resolving hook points`() }
    }
}
