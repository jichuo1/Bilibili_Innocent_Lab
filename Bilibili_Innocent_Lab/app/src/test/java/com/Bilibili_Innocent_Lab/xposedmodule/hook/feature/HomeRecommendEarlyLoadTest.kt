package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.earlyload.RecyclerFixtures
import com.bilibili.pegasus.widget.LoadMoreListenerFixtures
import d571
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import w32

class HomeRecommendEarlyLoadTest {
    private val loader = requireNotNull(javaClass.classLoader)
    private val names = HomeRecommendEarlyLoadFeatureInstaller.HostNames(
        recyclerView = RecyclerFixtures.RecyclerView::class.java.name,
        gridLayoutManager = RecyclerFixtures.GridLayoutManager::class.java.name,
        function0 = RecyclerFixtures.Function0::class.java.name
    )

    private fun environment(
        registrar: HookRegistrar = PlayerPortTestRegistrar(),
        process: String = "tv.danmaku.bili",
        classLoader: ClassLoader? = loader,
        status: (String, String) -> Unit = { _, _ -> },
        evidence: (String, FeatureRuntimeStage, Int) -> Unit = { _, _, _ -> }
    ) = HookEnvironment(
        processName = process,
        classLoader = classLoader,
        hookPoints = HookPointRegistry(classLoader),
        registrar = registrar,
        logInfo = { _, _ -> },
        logError = { _, _ -> },
        reportStatus = status,
        runtimeEvidence = evidence
    )

    private fun small(@Suppress("UNUSED_PARAMETER") position: Int) = 1

    @Test fun `accepts only the verified relocated listener and callback shape`() {
        val callback = w32()
        val listener = d571(callback)
        val access = requireNotNull(shape(listener.javaClass))
        assertSame(callback, access.callback.get(listener))
        assertTrue(access.enabled.getBoolean(listener))
        assertNull(shape(RecyclerFixtures.ForeignLoadMoreListener::class.java))
    }

    // ---- 行数判定 ----

    @Test fun `the lead is counted in rows of the current column count`() {
        // 双列手机：最后挂载第 5 项，后面还有 6 张小卡 = 3 行，在四行以内。
        assertTrue(HomeRecommendEarlyLoadPolicy.withinLead(5, 12, 2, spanSize = ::small))
        // 同样 6 项全是整行大卡 = 6 行，还早。
        assertFalse(HomeRecommendEarlyLoadPolicy.withinLead(5, 12, 2) { 2 })
        // 平板四列：16 张小卡正好四行；再多一张就超出。
        assertTrue(HomeRecommendEarlyLoadPolicy.withinLead(0, 17, 4, spanSize = ::small))
        assertFalse(HomeRecommendEarlyLoadPolicy.withinLead(0, 18, 4, spanSize = ::small))
        // 平板上一张整行大卡占满四个 span。
        assertTrue(HomeRecommendEarlyLoadPolicy.withinLead(0, 5, 4) { 4 })
        assertFalse(HomeRecommendEarlyLoadPolicy.withinLead(0, 6, 4) { 4 })
        // 宿主 lookup 给出越界的 span 时按 1..列数 夹住，不让一张卡吞掉阈值或变成负数。
        assertTrue(HomeRecommendEarlyLoadPolicy.withinLead(0, 9, 2) { -3 })
        assertFalse(HomeRecommendEarlyLoadPolicy.withinLead(0, 6, 2) { 99 })
    }

    @Test fun `invalid positions or counts never trigger`() {
        assertFalse(HomeRecommendEarlyLoadPolicy.withinLead(-1, 3, 2, spanSize = ::small))
        assertFalse(HomeRecommendEarlyLoadPolicy.withinLead(3, 3, 2, spanSize = ::small))
        assertFalse(HomeRecommendEarlyLoadPolicy.withinLead(0, 3, 0, spanSize = ::small))
        assertFalse(HomeRecommendEarlyLoadPolicy.withinLead(0, 3, 2, leadRows = 0, spanSize = ::small))
        // 最后一项已挂载：剩余 0 行，属于“到底了”，应当请求。
        assertTrue(HomeRecommendEarlyLoadPolicy.withinLead(2, 3, 2, spanSize = ::small))
    }

    @Test fun `the gate requests once per list length and reopens after growth or refresh`() {
        val gate = EarlyLoadGate()
        assertFalse(gate.shouldRequest(enabled = false, 8, 10, 2, ::small))
        // 还没进入四行范围：不消耗这一长度的名额。
        assertFalse(gate.shouldRequest(true, 0, 20, 2, ::small))
        assertTrue(gate.shouldRequest(true, 12, 20, 2, ::small))
        assertFalse(gate.shouldRequest(true, 13, 20, 2, ::small))
        // 宿主追加加载页脚或新一页后长度变化，允许下一次。
        assertTrue(gate.shouldRequest(true, 18, 21, 2, ::small))
        assertFalse(gate.shouldRequest(true, 19, 21, 2, ::small))
        // 下拉刷新让列表变短：重新开放，哪怕新长度与之前某次相同。
        assertTrue(gate.shouldRequest(true, 8, 10, 2, ::small))
    }

    // ---- 监听器结构识别 ----

    private fun shape(type: Class<*>) = PegasusLoadMoreShape.of(
        type,
        RecyclerFixtures.RecyclerView.OnScrollListener::class.java,
        RecyclerFixtures.RecyclerView.OnChildAttachStateChangeListener::class.java,
        RecyclerFixtures.Function0::class.java
    )

    @Test fun `the home load more listener is recognised by structure and its fields are readable`() {
        val callback = RecyclerFixtures.MergedCallback()
        val listener = LoadMoreListenerFixtures.HomeLoadMore(callback, true)
        val shape = requireNotNull(shape(listener.javaClass))
        assertSame(callback, shape.callback.get(listener))
        assertTrue(shape.enabled.getBoolean(listener))
        assertFalse(shape.enabled.getBoolean(LoadMoreListenerFixtures.HomeLoadMore(callback, false)))
    }

    @Test fun `look alike listeners are not mistaken for the home load more listener`() {
        assertNull(shape(LoadMoreListenerFixtures.OtherList::class.java))
        assertNull(shape(LoadMoreListenerFixtures.ScrollOnly::class.java))
        // 形状一致但不在宿主首页包下。
        assertNull(shape(RecyclerFixtures.ForeignLoadMoreListener::class.java))
    }

    // ---- 安装 ----

    @Test fun `installing hooks only the list scroll listener registration`() {
        val registrar = PlayerPortTestRegistrar()
        val stages = mutableListOf<FeatureRuntimeStage>()
        val statuses = mutableListOf<String>()
        val result = HomeRecommendEarlyLoadFeatureInstaller(true, names).install(
            environment(registrar, status = { _, value -> statuses += value }) { id, stage, _ ->
                assertEquals(HomeRecommendEarlyLoadFeatureInstaller.ID, id)
                stages += stage
            })
        assertEquals(FeatureInstallResult.Installed(1), result)
        val (id, member) = registrar.registrationAttempts.single()
        assertEquals(HomeRecommendEarlyLoadFeatureInstaller.LISTENER_HOOK_ID, id)
        assertEquals("addOnScrollListener", member.name)
        assertEquals(listOf(FeatureRuntimeStage.ADAPTED), stages)
        assertEquals(listOf("installed"), statuses)
    }

    @Test fun `a list that is not a view is ignored without touching the host`() {
        val registrar = PlayerPortTestRegistrar()
        val stages = mutableListOf<FeatureRuntimeStage>()
        HomeRecommendEarlyLoadFeatureInstaller(true, names).install(environment(registrar) { _, stage, _ -> stages += stage })
        val list = RecyclerFixtures.RecyclerView()
        val listener = LoadMoreListenerFixtures.HomeLoadMore(RecyclerFixtures.MergedCallback(), true)
        registrar.invoke(HomeRecommendEarlyLoadFeatureInstaller.LISTENER_HOOK_ID, receiver = list, args = arrayOf(listener)) {
            list.addOnScrollListener(listener)
        }
        assertEquals(1, list.scrollListeners)
        assertEquals(0, list.attachListeners)
        assertEquals(listOf(FeatureRuntimeStage.ADAPTED), stages)
    }

    @Test fun `the watcher caches one structural verdict per listener class`() {
        val access = requireNotNull(HomeRecommendEarlyLoadFeatureInstaller.RecyclerAccess.resolve(loader, names))
        val watcher = HomeRecommendEarlyLoadFeatureInstaller.LoadMoreWatcher(access, environment())
        val first = watcher.shapeOf(LoadMoreListenerFixtures.HomeLoadMore::class.java)
        assertNotNull(first)
        assertSame(first, watcher.shapeOf(LoadMoreListenerFixtures.HomeLoadMore::class.java))
        assertNull(watcher.shapeOf(LoadMoreListenerFixtures.OtherList::class.java))
        assertNull(watcher.shapeOf(LoadMoreListenerFixtures.OtherList::class.java))
    }

    @Test fun `disabled and secondary processes register nothing`() {
        val registrar = PlayerPortTestRegistrar()
        val statuses = mutableListOf<String>()
        assertEquals(FeatureInstallResult.Skipped("disabled"),
            HomeRecommendEarlyLoadFeatureInstaller(false, names).install(environment(registrar, status = { _, v -> statuses += v })))
        assertEquals(FeatureInstallResult.Skipped("non-main-process"),
            HomeRecommendEarlyLoadFeatureInstaller(true, names).install(environment(registrar, process = "tv.danmaku.bili:web")))
        assertEquals(listOf("disabled"), statuses)
        assertTrue(registrar.registrationAttempts.isEmpty())
    }

    @Test fun `a missing or reshaped list boundary fails closed`() {
        assertEquals(FeatureInstallResult.Skipped("missing-class-loader"),
            HomeRecommendEarlyLoadFeatureInstaller(true, names).install(environment(classLoader = null)))
        val registrar = PlayerPortTestRegistrar()
        assertEquals(FeatureInstallResult.Skipped("missing-recycler-boundary"),
            HomeRecommendEarlyLoadFeatureInstaller(true, names.copy(gridLayoutManager = RecyclerFixtures.WrongGridLayoutManager::class.java.name))
                .install(environment(registrar)))
        assertEquals(FeatureInstallResult.Skipped("missing-recycler-boundary"),
            HomeRecommendEarlyLoadFeatureInstaller(true, names.copy(function0 = "missing.Function0")).install(environment(registrar)))
        assertTrue(registrar.registrationAttempts.isEmpty())
    }

    @Test fun `the production names resolve against the real androidx list and kotlin function types`() {
        // 模块自身依赖同一套 androidx RecyclerView 与 Kotlin 标准库：默认名必须能逐个解析出来。
        val registrar = PlayerPortTestRegistrar()
        assertEquals(FeatureInstallResult.Installed(1), HomeRecommendEarlyLoadFeatureInstaller(true).install(environment(registrar)))
        assertEquals("androidx.recyclerview.widget.RecyclerView", registrar.registrationAttempts.single().second.declaringClass.name)
    }

    @Test fun `a registration failure is reported and not counted as installed`() {
        assertEquals(FeatureInstallResult.Skipped("registration-failed"),
            HomeRecommendEarlyLoadFeatureInstaller(true, names).install(
                environment(PlayerPortTestRegistrar(HomeRecommendEarlyLoadFeatureInstaller.LISTENER_HOOK_ID))))
    }
}
