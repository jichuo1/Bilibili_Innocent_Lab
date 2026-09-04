package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerInteractiveOverlayFeatureInstallerTest {

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
    fun `skips when disabled`() {
        val result = PlayerInteractiveOverlayFeatureInstaller(
            enabled = false,
            points = VersionAdapter.PlayerInteractiveOverlayPoints(
                families = emptyList(),
                mossExecutes = emptyList(),
                commandGetter = null,
                commandClear = VersionAdapter.HookPoint(
                    "com.bapis.bilibili.community.service.dm.v1.DmViewReply",
                    "clearCommand",
                    emptyList()
                ),
                commandDefault = null
            )
        ).install(environment)

        assertEquals(FeatureInstallResult.Skipped("disabled"), result)
        assertEquals(listOf("player_interactive_overlay_status" to "disabled"), statuses)
    }

    @Test
    fun `skips when adapter points are missing`() {
        val result = PlayerInteractiveOverlayFeatureInstaller(
            enabled = true,
            points = null
        ).install(environment)

        assertEquals(FeatureInstallResult.Skipped("missing-adapter-point"), result)
        assertEquals(
            listOf("player_interactive_overlay_status" to "missing-adapter-point"),
            statuses
        )
    }
}
