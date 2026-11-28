package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.TextView
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.KmpGrpcWireAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.KotlinDefaultWordsLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import com.Bilibili_Innocent_Lab.xposedmodule.testsupport.TestRenamingClassLoader
import com.bapis.bilibili.app.interfaces.v1.DefaultWordsReply
import com.bilibili.lib.homepage.startdust.menu.a
import kmpfixture.modern.Descriptor
import kmpfixture.modern.GrpcEngine
import kmpfixture.modern.Handler
import kmpfixture.modern.KMossException
import kmpfixture.modern.Service
import kotlinx.serialization.SerializationStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeTopBarFeatureInstallerTest {
    private class SharedRouteItem(@JvmField val uri: String) {
        companion object { const val GAME = "action://game_center/home/menu" }
    }

    fun `shared static routes never identify a different menu instance as game center`() {
        assertFalse(HomeTopBarFeatureInstaller.isGameMenuItem(SharedRouteItem("action://scan")))
        assertTrue(HomeTopBarFeatureInstaller.isGameMenuItem(SharedRouteItem("action://game_center/home/menu?from=home")))
        assertFalse(HomeTopBarFeatureInstaller.isGameMenuItem(SharedRouteItem("action://scan")))
    }

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

    fun `compose layer returns null when there is nothing to drop so the argument is left alone`() {
        val filter = ComposeTopRightFilter()
        val untouched = listOf(TopRightItem("消息", "bilibili://link/im_home"))
        // null 的含义是"别替换参数"，不是"过滤失败"——替换成等价副本是纯浪费。
        assertNull(filter.filter(untouched, environment))
        assertNull(filter.filter(emptyList<Any>(), environment))
        assertFalse(filter.isDegraded)
    }

    fun `compose layer tolerates null elements instead of dropping them`() {
        val game = TopRightItem("游戏", "action://game_center/home/menu")
        val kept = ComposeTopRightFilter().filter(listOf(null, game, null), environment)
        assertEquals(listOf(null, null), kept)
    }

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

    fun `layer state distinguishes absent anchor from failed registration`() {
        assertEquals("ok", HomeTopBarFeatureInstaller.layerState(present = true, installed = true))
        assertEquals("failed", HomeTopBarFeatureInstaller.layerState(present = true, installed = false))
        assertEquals("absent", HomeTopBarFeatureInstaller.layerState(present = false, installed = false))
    }

    fun `home top bar points survive a json round trip with the compose layer`() {
        val point = VersionAdapter.HookPoint("A", "b", null)
        val composePoint = VersionAdapter.HookPoint("C", "invoke", null)
        val restored = VersionAdapter.HomeTopBarPoints.fromJson(
            VersionAdapter.HomeTopBarPoints(point, composePoint, null, emptyList()).toJson()
        )
        assertEquals(point, restored.gameMenu)
        assertEquals(composePoint, restored.composeGameMenu)
    }

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

    private class Recorder(private val failId: String? = null) : HookRegistrar by TestHookRegistrar {
        val ids = mutableListOf<String>()
        override fun adapted(
            id: String,
            point: VersionAdapter.HookPoint,
            exceptionPolicy: HookExceptionPolicy,
            block: ModernMemberHookCreator.() -> Unit
        ) {
            ids += id
            check(id != failId) { "simulated registration failure" }
        }
    }

    private fun installGameLayers(
        menu: VersionAdapter.HookPoint?,
        compose: VersionAdapter.HookPoint?,
        failId: String? = null
    ): Triple<FeatureInstallResult, List<String>, List<Pair<String, String>>> {
        val recorder = Recorder(failId)
        val statuses = mutableListOf<Pair<String, String>>()
        val result = HomeTopBarFeatureInstaller(
            hideGameMenu = true,
            hideSearchDefaultWord = false,
            points = VersionAdapter.HomeTopBarPoints(menu, compose, null, emptyList()),
            frameResolver = { HomeFrameTabLocator.Resolution.Absent }
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

    fun `both game center layers install side by side`() {
        val (result, ids, statuses) = installGameLayers(menuPoint, composePoint)
        assertEquals(listOf("home.top_bar.game_menu", "home.top_bar.game_compose"), ids)
        assertEquals(FeatureInstallResult.Installed(2, complete = true), result)
        assertTrue("home_game_menu_layers" to "menu=ok,compose=ok" in statuses)
    }

    fun `an existing menu layer failure cannot be reported as complete by its sibling`() {
        val (result, _, _) = installGameLayers(menuPoint, composePoint, "home.top_bar.game_menu")
        assertTrue(result is FeatureInstallResult.Installed)
        assertFalse((result as FeatureInstallResult.Installed).complete)
    }

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

    fun `losing both layers is reported instead of passing silently`() {
        val (result, ids, statuses) = installGameLayers(null, null)
        assertTrue(ids.isEmpty())
        assertTrue(result is FeatureInstallResult.Skipped)
        assertTrue("home_game_menu_layers" to "menu=absent,compose=absent" in statuses)
    }

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

    private object WireDummyStrategy : SerializationStrategy<Any>

    private class WireStrippedDescriptor(
        pkg: String,
        svc: String,
        mtd: String
    ) : Descriptor(pkg, svc, mtd, Any::class, WireDummyStrategy) {
        override fun toString(): String = "kmp.Descriptor@${Integer.toHexString(System.identityHashCode(this))}"
    }

    private fun wireEnginePoint(): VersionAdapter.HookPoint {
        val method = GrpcEngine::class.java.getDeclaredMethod(
            "a",
            Service::class.java,
            Descriptor::class.java,
            ByteArray::class.java,
            Handler::class.java,
            Any::class.java
        )
        return KmpGrpcWireAdapter.point(method)
    }

    fun `default words filter cleans reply over Wire-First`() {
        val topReg = PlayerPortTestRegistrar()
        val loader = requireNotNull(javaClass.classLoader)
        HomeTopBarFeatureInstaller(
            hideGameMenu = false,
            hideSearchDefaultWord = true,
            points = null,
            kmpGrpcWireEngine = wireEnginePoint()
        ).install(
            HookEnvironment(
                processName = "tv.danmaku.bili",
                classLoader = loader,
                hookPoints = HookPointRegistry(loader),
                registrar = topReg,
                logInfo = { _, _ -> },
                logError = { _, _ -> errors += 1 },
                reportStatus = { _, _ -> }
            )
        )
        var received: ByteArray? = null
        val handler = object : Handler {
            override fun onNext(bytes: ByteArray?) { received = bytes }
            override fun onCompleted() = Unit
            override fun onError(error: KMossException) = Unit
        }
        val descriptor = WireStrippedDescriptor("bilibili.app.interface.v1", "Search", "DefaultWords")
        val args = arrayOf<Any?>(Service("host"), descriptor, ByteArray(0), handler, null)
        topReg.invoke("kmp.grpc.wire.channel", GrpcEngine(emptyList()), args) { passed ->
            (passed[3] as Handler).onNext(DefaultWordsReply("hot show", "hot word", "hot val", "route").toByteArray())
            null
        }
        val cleanedWords = DefaultWordsReply.parseFrom(requireNotNull(received))
        assertEquals("", cleanedWords.getShow())
        assertEquals("", cleanedWords.getWord())
        assertEquals("", cleanedWords.getValue())
    }

    interface TemplateHintModel {
        fun getShow(): String
        fun getAnimationTimeMilli(): Long
        fun enableAnimation(): Boolean
        fun isShowFront(): Boolean
    }
    class SwitchTextView(context: Context) : TextView(context)
    class TemplateBaseMainFrameFragment {
        @Suppress("unused")
        private var searchBox: SwitchTextView? = null
        @Suppress("UNUSED_PARAMETER")
        fun onViewCreated(view: View, savedInstanceState: Bundle?) = Unit
    }
    class TemplateMainFragment {
        @Suppress("UNUSED_PARAMETER")
        fun obfuscatedBind(hint: TemplateHintModel?) = Unit
    }

    fun `locateHomeTopBar matches default word entry with arbitrary method and interface names`() {
        val mainName = "tv.danmaku.bili.ui.main2.MainFragment"
        val baseName = "tv.danmaku.bili.ui.main2.basic.BaseMainFrameFragment"
        val hintName = "x.y.Z"
        val loader = TestRenamingClassLoader(
            javaClass.classLoader!!,
            templates = mapOf(
                baseName to TemplateBaseMainFrameFragment::class.java,
                mainName to TemplateMainFragment::class.java,
                hintName to TemplateHintModel::class.java
            ),
            replacements = mapOf(
                TemplateHintModel::class.java.name.replace('.', '/') to hintName.replace('.', '/')
            ),
            hiddenPrefixes = setOf(
                "com.bilibili.lib.homepage.startdust.menu.a",
                "tv.danmaku.bili.home.components.topbar.",
                KotlinDefaultWordsLocator.MOSS
            )
        )
        val points = requireNotNull(VersionAdapter.locateHomeTopBar(loader))
        assertEquals(baseName, points.baseOnViewCreated?.className)
        assertEquals("searchBox", points.baseOnViewCreated?.viewField)
        val entry = points.defaultWordMethods.single()
        assertEquals(mainName, entry.className)
        assertEquals("obfuscatedBind", entry.methodName)
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (HomeTopBarFeatureInstallerTest) -> Unit) {
            val s = HomeTopBarFeatureInstallerTest()
            block(s)
        }
        runIsolated { s -> s.`shared static routes never identify a different menu instance as game center`() }
        runIsolated { s -> s.`recognizes only exact game center action from adapted config field`() }
        runIsolated { s -> s.`the same action predicate serves both layers so they never drift apart`() }
        runIsolated { s -> s.`compose layer drops only the game entry and keeps list order`() }
        runIsolated { s -> s.`compose layer returns null when there is nothing to drop so the argument is left alone`() }
        runIsolated { s -> s.`compose layer tolerates null elements instead of dropping them`() }
        runIsolated { s -> s.`compose layer stops after one failure and never touches the argument again`() }
        runIsolated { s -> s.`layer state distinguishes absent anchor from failed registration`() }
        runIsolated { s -> s.`home top bar points survive a json round trip with the compose layer`() }
        runIsolated { s -> s.`an older cache without the compose key degrades that layer instead of failing`() }
        runIsolated { s -> s.`both game center layers install side by side`() }
        runIsolated { s -> s.`an existing menu layer failure cannot be reported as complete by its sibling`() }
        runIsolated { s -> s.`either layer alone still counts as ready`() }
        runIsolated { s -> s.`losing both layers is reported instead of passing silently`() }
        runIsolated { s -> s.`the locator picks the typed invoke and never the bridge overload`() }
        runIsolated { s -> s.`default words filter cleans reply over Wire-First`() }
        runIsolated { s -> s.`locateHomeTopBar matches default word entry with arbitrary method and interface names`() }
    }
}
