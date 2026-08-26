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

    @Test
    fun `paused ad reports disabled without resolving hook points`() {
        val result = PausedAdFeatureInstaller(enabled = false, points = null).install(environment)

        assertEquals(FeatureInstallResult.Skipped("disabled"), result)
        assertEquals(listOf("adskip_status" to "disabled"), statuses)
    }

    @Test
    fun `home banner reports disabled without resolving hook points`() {
        val result = HomeBannerFeatureInstaller(
            enabled = false,
            point = null,
            collapseBanner = {}
        ).install(environment)

        assertEquals(FeatureInstallResult.Skipped("disabled"), result)
        assertEquals(listOf("banner_ad_status" to "disabled"), statuses)
    }
}
