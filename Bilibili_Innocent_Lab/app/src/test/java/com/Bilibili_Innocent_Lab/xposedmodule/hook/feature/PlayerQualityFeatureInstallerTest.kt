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

    fun `normalizes unknown qn to disabled`() {
        assertEquals(0, PlayerQualityConfig.normalize(-1))
        assertEquals(0, PlayerQualityConfig.normalize(999))
        assertEquals(80, PlayerQualityConfig.normalize(80))
    }

    fun `installs verified normal policy without claiming missing Story coverage`() {
        val result = PlayerQualityFeatureInstaller(
            qualityQn = 80,
            points = VersionAdapter.PlayerQualityPoints(
                VersionAdapter.HookPoint("gh6.h", "c", emptyList())
            )
        ).install(environment)

        assertEquals(FeatureInstallResult.Installed(5, complete = false), result)
        assertEquals(listOf("player_quality_status" to "partial:2/3"), statuses)
    }

    fun `host without Story does not add a missing family to coverage`() {
        val loader = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == "com.bilibili.video.story.player.quality.StoryQualityService") throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }
        val result = PlayerQualityFeatureInstaller(80, VersionAdapter.PlayerQualityPoints(
            VersionAdapter.HookPoint("gh6.h", "c", emptyList()))).install(environment.copy(classLoader = loader))
        assertEquals(FeatureInstallResult.Installed(5), result)
        assertEquals(listOf("player_quality_status" to "success"), statuses)
    }

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

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (PlayerQualityFeatureInstallerTest) -> Unit) {
            val s = PlayerQualityFeatureInstallerTest()
            block(s)
        }
        runIsolated { s -> s.`normalizes unknown qn to disabled`() }
        runIsolated { s -> s.`installs verified normal policy without claiming missing Story coverage`() }
        runIsolated { s -> s.`host without Story does not add a missing family to coverage`() }
        runIsolated { s -> s.`keeps follow host configuration hook free`() }
    }
}
