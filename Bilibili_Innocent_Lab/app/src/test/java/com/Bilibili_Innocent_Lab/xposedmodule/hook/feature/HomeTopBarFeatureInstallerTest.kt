package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import com.bilibili.lib.homepage.startdust.menu.a
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeTopBarFeatureInstallerTest {

    /** 模拟 Compose 顶栏的一项（真机 9110400 是 `Nm1.o`，action 在它的 `c` 字段）。 */
    private class TopRightItem(
        @JvmField val title: String,
        @JvmField val uri: String
    )

    private val environment = HookEnvironment(
        processName = "tv.danmaku.bili",
        classLoader = javaClass.classLoader,
        hookPoints = HookPointRegistry(javaClass.classLoader!!),
        registrar = TestHookRegistrar,
        logInfo = { _, _ -> },
        logError = { _, _ -> errors += 1 },
        reportStatus = { _, _ -> }
    )
    private var errors = 0

    @Test
    fun `recognizes only exact game center action from adapted config field`() {
        assertTrue(
            HomeTopBarFeatureInstaller.hasGameMenuAction(
                a("action://game_center/home/menu?from=home"),
                "config"
            )
        )
        assertFalse(
            HomeTopBarFeatureInstaller.hasGameMenuAction(
                a("action://search/home/menu"),
                "config"
            )
        )
        assertFalse(HomeTopBarFeatureInstaller.hasGameMenuAction(a(), "missing"))
    }

    @Test
    fun `the same action predicate serves both layers so they never drift apart`() {
        assertTrue(
            HomeTopBarFeatureInstaller.isGameMenuItem(
                TopRightItem("游戏", "action://game_center/home/menu")
            )
        )
        assertTrue(
            HomeTopBarFeatureInstaller.isGameMenuItem(
                TopRightItem("游戏", "action://game_center/home/menu?from=home")
            )
        )
        // 前缀相同但不是同一个入口，不能误删。
        assertFalse(
            HomeTopBarFeatureInstaller.isGameMenuItem(
                TopRightItem("游戏", "action://game_center/home/menu_v2")
            )
        )
        assertFalse(
            HomeTopBarFeatureInstaller.isGameMenuItem(
                TopRightItem("消息", "bilibili://link/im_home")
            )
        )
    }

    @Test
    fun `compose layer drops only the game entry and keeps list order`() {
        val im = TopRightItem("消息", "bilibili://link/im_home")
        val game = TopRightItem("游戏", "action://game_center/home/menu?from=home")
        val more = TopRightItem("更多", "action://link/home/menu")
        val kept = ComposeTopRightFilter().filter(listOf(im, game, more), environment)
        assertNotNull(kept)
        assertEquals(2, kept!!.size)
        assertSame(im, kept[0])
        assertSame(more, kept[1])
    }

    @Test
    fun `compose layer returns null when there is nothing to drop so the argument is left alone`() {
        val filter = ComposeTopRightFilter()
        val untouched = listOf(TopRightItem("消息", "bilibili://link/im_home"))
        // null 的含义是"别替换参数"，不是"过滤失败"——替换成等价副本是纯浪费。
        assertNull(filter.filter(untouched, environment))
        assertNull(filter.filter(emptyList<Any>(), environment))
        assertFalse(filter.isDegraded)
    }

    @Test
    fun `compose layer tolerates null elements instead of dropping them`() {
        val game = TopRightItem("游戏", "action://game_center/home/menu")
        val kept = ComposeTopRightFilter().filter(listOf(null, game, null), environment)
        assertEquals(listOf(null, null), kept)
    }

    @Test
    fun `compose layer stops after one failure and never touches the argument again`() {
        // 反射读不到字段时 KavaMemberLookup 会抛；这条路径每次列表变化都走，
        // 反复抛会刷满宿主日志，所以必须一次就停手。
        val filter = ComposeTopRightFilter { throw IllegalStateException("host structure changed") }
        assertNull(filter.filter(listOf(TopRightItem("游戏", "action://game_center/home/menu")), environment))
        val errorsAfterFirst = errors
        assertNull(filter.filter(listOf(TopRightItem("游戏", "action://game_center/home/menu")), environment))
        // 已降级：连能删的那次也不再动，且不再重复记日志。
        assertTrue(filter.isDegraded)
        assertEquals(errorsAfterFirst, errors)
    }

    @Test
    fun `layer state distinguishes absent anchor from failed registration`() {
        assertEquals("ok", HomeTopBarFeatureInstaller.layerState(present = true, installed = true))
        assertEquals("failed", HomeTopBarFeatureInstaller.layerState(present = true, installed = false))
        assertEquals("absent", HomeTopBarFeatureInstaller.layerState(present = false, installed = false))
    }

    @Test
    fun `home top bar points survive a json round trip with the compose layer`() {
        val point = VersionAdapter.HookPoint("A", "b", null)
        val composePoint = VersionAdapter.HookPoint("C", "invoke", null)
        val restored = VersionAdapter.HomeTopBarPoints.fromJson(
            VersionAdapter.HomeTopBarPoints(point, composePoint, null, emptyList()).toJson()
        )
        assertEquals(point, restored.gameMenu)
        assertEquals(composePoint, restored.composeGameMenu)
    }

    @Test
    fun `an older cache without the compose key degrades that layer instead of failing`() {
        // 抬 RULE_VERSION 会让旧缓存整体失效，但解析本身也不许因为缺键就炸。
        val legacy = VersionAdapter.HomeTopBarPoints(
            VersionAdapter.HookPoint("A", "b", null), null, null, emptyList()
        ).toJson()
        assertFalse(legacy.has("game_compose"))
        val restored = VersionAdapter.HomeTopBarPoints.fromJson(legacy)
        assertNull(restored.composeGameMenu)
        assertNotNull(restored.gameMenu)
    }

    private class Recorder : HookRegistrar by TestHookRegistrar {
        val ids = mutableListOf<String>()
        override fun adapted(
            id: String,
            point: VersionAdapter.HookPoint,
            exceptionPolicy: HookExceptionPolicy,
            block: ModernMemberHookCreator.() -> Unit
        ) {
            ids += id
        }
    }

    private fun installGameLayers(
        menu: VersionAdapter.HookPoint?,
        compose: VersionAdapter.HookPoint?
    ): Triple<FeatureInstallResult, List<String>, List<Pair<String, String>>> {
        val recorder = Recorder()
        val statuses = mutableListOf<Pair<String, String>>()
        val result = HomeTopBarFeatureInstaller(
            hideGameMenu = true,
            hideSearchDefaultWord = false,
            points = VersionAdapter.HomeTopBarPoints(menu, compose, null, emptyList())
        ).install(
            environment.copy(
                registrar = recorder,
                reportStatus = { channel, value -> statuses += channel to value }
            )
        )
        return Triple(result, recorder.ids, statuses)
    }

    private val menuPoint = VersionAdapter.HookPoint(
        "home.Menu", "b", listOf("android.view.Menu", "android.view.MenuInflater"), viewField = "config"
    )
    private val composePoint = VersionAdapter.HookPoint(
        "home.TopRight", "invoke", listOf("java.util.List", "kotlin.coroutines.Continuation")
    )

    @Test
    fun `both game center layers install side by side`() {
        val (result, ids, statuses) = installGameLayers(menuPoint, composePoint)
        assertEquals(listOf("home.top_bar.game_menu", "home.top_bar.game_compose"), ids)
        assertEquals(FeatureInstallResult.Installed(2, complete = true), result)
        assertTrue("home_game_menu_layers" to "menu=ok,compose=ok" in statuses)
    }

    @Test
    fun `either layer alone still counts as ready`() {
        // 老宿主只有菜单路径、Compose 顶栏放量后可能只剩新路径：要求"两条都装上"
        // 会让正常情况永久报 partial，那反而会掩盖真正的失效。
        val (onlyMenu, menuIds, menuStatuses) = installGameLayers(menuPoint, null)
        assertEquals(listOf("home.top_bar.game_menu"), menuIds)
        assertTrue(onlyMenu is FeatureInstallResult.Installed && onlyMenu.complete)
        assertTrue("home_game_menu_layers" to "menu=ok,compose=absent" in menuStatuses)

        val (onlyCompose, composeIds, composeStatuses) = installGameLayers(null, composePoint)
        assertEquals(listOf("home.top_bar.game_compose"), composeIds)
        assertTrue(onlyCompose is FeatureInstallResult.Installed && onlyCompose.complete)
        assertTrue("home_game_menu_layers" to "menu=absent,compose=ok" in composeStatuses)
    }

    @Test
    fun `losing both layers is reported instead of passing silently`() {
        val (result, ids, statuses) = installGameLayers(null, null)
        assertTrue(ids.isEmpty())
        assertTrue(result is FeatureInstallResult.Skipped)
        assertTrue("home_game_menu_layers" to "menu=absent,compose=absent" in statuses)
    }

    @Test
    fun `the locator picks the typed invoke and never the bridge overload`() {
        // 端到端跑定位器：夹具里两个 invoke 同名同参数个数，只有参数 0 的类型能分开它们。
        val points = VersionAdapter.locateHomeTopBar(javaClass.classLoader!!)
        assertNotNull(points)
        val compose = points!!.composeGameMenu
        assertNotNull("Compose 顶栏落点未定位到", compose)
        assertEquals(
            "tv.danmaku.bili.home.components.topbar.TopRightComponent\$initTopRight\$1\$1",
            compose!!.className
        )
        assertEquals("invoke", compose.methodName)
        // 桥接方法的参数 0 是 Object，被签名判据排除；命中的必须是 List 那个。
        assertEquals(listOf("java.util.List", "kotlin.coroutines.Continuation"), compose.paramClassNames)
    }
}
