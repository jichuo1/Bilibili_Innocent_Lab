package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerQualityFeatureInstallerTest {

    private val statuses = mutableListOf<Pair<String, String>>()
    private val environment = HookEnvironment(
        processName = "tv.danmaku.bili",
        classLoader = javaClass.classLoader,
        hookPoints = HookPointRegistry(javaClass.classLoader),
        registrar = TestHookRegistrar,
        logInfo = { _, _ -> },
        logError = { _, _ -> },
        reportStatus = { channel, status -> statuses += channel to status }
    )

    @Test
    fun `normalizes unknown qn to disabled`() {
        assertEquals(0, PlayerQualityConfig.normalize(-1))
        assertEquals(0, PlayerQualityConfig.normalize(999))
        assertEquals(80, PlayerQualityConfig.normalize(80))
    }

    @Test
    fun `installs only the adapted default quality boundary`() {
        val result = PlayerQualityFeatureInstaller(
            qualityQn = 80,
            points = VersionAdapter.PlayerQualityPoints(
                VersionAdapter.HookPoint("gh6.h", "c", emptyList())
            )
        ).install(environment)

        assertEquals(FeatureInstallResult.Installed(1), result)
        assertEquals(listOf("player_quality_status" to "success"), statuses)
    }

    @Test
    fun `keeps follow host configuration hook free`() {
        val result = PlayerQualityFeatureInstaller(
            qualityQn = 0,
            points = VersionAdapter.PlayerQualityPoints(
                VersionAdapter.HookPoint("gh6.h", "c", emptyList())
            )
        ).install(environment)

        assertEquals(FeatureInstallResult.Skipped("disabled"), result)
        assertEquals(listOf("player_quality_status" to "disabled"), statuses)
    }
}
