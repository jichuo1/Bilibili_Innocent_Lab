package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import org.junit.Assert.assertEquals
import org.junit.Test

class SplashAdFeatureInstallerTest {

    @Test
    fun `installs only adapted splash response getters`() {
        val environment = HookEnvironment(
            processName = "tv.danmaku.bili",
            classLoader = javaClass.classLoader,
            hookPoints = HookPointRegistry(javaClass.classLoader),
            registrar = TestHookRegistrar,
            logInfo = { _, _ -> },
            logError = { _, _ -> },
            reportStatus = { _, _ -> }
        )
        val result = SplashAdFeatureInstaller(
            enabled = true,
            points = VersionAdapter.SplashAdPoints(
                listOf(
                    VersionAdapter.HookPoint(
                        "tv.danmaku.bili.splash.ad.model.SplashListResponse",
                        "getSplashList",
                        emptyList()
                    ),
                    VersionAdapter.HookPoint(
                        "tv.danmaku.bili.splash.ad.model.SplashListResponse",
                        "getStrategyList",
                        emptyList()
                    )
                )
            )
        ).install(environment)

        assertEquals(FeatureInstallResult.Installed(2), result)
    }
}
