package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    private fun locatedPoints(): VersionAdapter.PlayerInteractiveOverlayPoints =
        requireNotNull(
            VersionAdapter.locatePlayerInteractiveOverlays(requireNotNull(javaClass.classLoader))
        )

    /** 覆盖单位 = Guide 家族 + DmResource 第二载体 + Moss 双保险 + 指令弹幕 + 运营活动横幅。 */
    private fun expectedUnits(
        points: VersionAdapter.PlayerInteractiveOverlayPoints
    ): Int = points.families.size +
        points.families.count { it.dmGetter != null && it.dmClears.isNotEmpty() } +
        points.mossExecutes.size +
        1 +
        (if (points.commandActivityMetaClear != null) 1 else 0)

    private fun status(): String = statuses.single()
        .also { assertEquals(CHANNEL, it.first) }
        .second

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
        assertEquals(listOf(CHANNEL to "disabled"), statuses)
    }

    @Test
    fun `skips when adapter points are missing`() {
        val result = PlayerInteractiveOverlayFeatureInstaller(
            enabled = true,
            points = null
        ).install(environment)

        assertEquals(FeatureInstallResult.Skipped("missing-adapter-point"), result)
        assertEquals(listOf(CHANNEL to "missing-adapter-point"), statuses)
    }

    @Test
    fun `reports success when every coverage unit installs`() {
        val points = locatedPoints()
        val result = PlayerInteractiveOverlayFeatureInstaller(
            enabled = true,
            points = points
        ).install(environment)

        val expected = expectedUnits(points)
        assertEquals(FeatureInstallResult.Installed(expected), result)
        assertEquals("success", status())
    }

    @Test
    fun `a family whose whitelist cannot resolve is reported as partial`() {
        val points = locatedPoints()
        val broken = points.copy(
            families = points.families.map { family ->
                if (family.replyClassName.startsWith("com.bapis.bilibili.app.viewunite.")) {
                    family.copy(
                        guideClears = listOf(
                            VersionAdapter.HookPoint(
                                "com.bapis.bilibili.app.viewunite.v1.VideoGuide",
                                "clearNothingHere",
                                emptyList()
                            )
                        )
                    )
                } else {
                    family
                }
            }
        )

        PlayerInteractiveOverlayFeatureInstaller(enabled = true, points = broken)
            .install(environment)

        // 家族被跳过也必须计入分母，否则整组白名单失效会被算成 success。
        val expected = expectedUnits(broken)
        assertEquals("partial:${expected - 1}/$expected", status())
    }

    @Test
    fun `command danmaku is not claimed when neither getter rewrite nor moss clear works`() {
        val points = locatedPoints()
        val stranded = points.copy(
            // 拿不到默认 Command，getter 的 after 改不动本次返回值，不注册。
            commandDefault = null,
            // 同时去掉 DM 的 Moss 兜底，指令弹幕就真的没有清除路径了。
            mossExecutes = points.mossExecutes.filterNot {
                it.className == VersionAdapter.PLAYER_INTERACTIVE_DM_MOSS_CLASS
            }
        )

        PlayerInteractiveOverlayFeatureInstaller(enabled = true, points = stranded)
            .install(environment)

        // 指令弹幕与运营活动横幅共用这两条通路，两条都断就是两个单位一起丢。
        val expected = expectedUnits(stranded)
        assertEquals("partial:${expected - 2}/$expected", status())
    }

    @Test
    fun `command danmaku falls back to the moss boundary when the default instance is missing`() {
        val points = locatedPoints()
        val fallback = points.copy(commandDefault = null)

        PlayerInteractiveOverlayFeatureInstaller(enabled = true, points = fallback)
            .install(environment)

        // DMMoss 的 executeDmView 仍在，指令弹幕算被覆盖。
        assertTrue(
            fallback.mossExecutes.any {
                it.className == VersionAdapter.PLAYER_INTERACTIVE_DM_MOSS_CLASS
            }
        )
        assertEquals("success", status())
    }

    private companion object {
        const val CHANNEL = "player_interactive_overlay_status"
    }
}
