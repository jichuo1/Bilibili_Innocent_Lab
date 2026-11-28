package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.View
import android.view.ViewGroup
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptAssistGate
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.HomeRecommendEarlyLoadAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistEngine
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.earlyload.RecyclerFixtures
import com.Bilibili_Innocent_Lab.xposedmodule.testsupport.TestRenamingClassLoader
import com.bilibili.pegasus.widget.LoadMoreListenerFixtures
import d571
import java.util.IdentityHashMap
import kotlin.coroutines.Continuation
import op2
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import w32
import zag1

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

    private class TemplatePegasusFragment
    private class TemplateInitFeeds919(
        @Suppress("UNUSED_PARAMETER") fragment: TemplatePegasusFragment,
        @Suppress("UNUSED_PARAMETER") listener: d571,
        @Suppress("UNUSED_PARAMETER") cont: Continuation<*>
    )
    private class TemplateInitFeeds921(
        @Suppress("UNUSED_PARAMETER") fragment: TemplatePegasusFragment,
        @Suppress("UNUSED_PARAMETER") listener: zag1,
        @Suppress("UNUSED_PARAMETER") cont: Continuation<*>
    )
    private class TemplateInitFeedsAmbiguous {
        @Suppress("UNUSED_PARAMETER")
        constructor(fragment: TemplatePegasusFragment, listener: d571, cont: Continuation<*>)
        @Suppress("UNUSED_PARAMETER")
        constructor(fragment: TemplatePegasusFragment, listener: zag1, cont: Continuation<*>)
    }
    private class TemplateInitFeedsWrongShape(
        @Suppress("UNUSED_PARAMETER") fragment: TemplatePegasusFragment,
        @Suppress("UNUSED_PARAMETER") listener: LoadMoreListenerFixtures.OtherList,
        @Suppress("UNUSED_PARAMETER") cont: Continuation<*>
    )

    private fun resolveRelocated(initFeedsName: String, loader: ClassLoader = this.loader) =
        PegasusLoadMoreShape.resolveRelocatedListener(
            loader = loader,
            scrollListener = RecyclerFixtures.RecyclerView.OnScrollListener::class.java,
            childAttachListener = RecyclerFixtures.RecyclerView.OnChildAttachStateChangeListener::class.java,
            function0 = RecyclerFixtures.Function0::class.java,
            pegasusFragmentName = TemplatePegasusFragment::class.java.name,
            initFeedsContinuationName = initFeedsName
        )

    fun `accepts only the verified relocated listener and callback shape`() {
        // 无 PegasusFragment$initFeeds$5 续体证据时，所有根包监听器一律拒绝。
        assertNull(shape(d571::class.java))
        assertNull(shape(zag1::class.java))
        assertNull(shape(RecyclerFixtures.ForeignLoadMoreListener::class.java))

        val callback919 = w32()
        val listener919 = d571(callback919)
        val verified919 = requireNotNull(resolveRelocated(TemplateInitFeeds919::class.java.name))
        assertSame(d571::class.java, verified919)
        val access919 = requireNotNull(shape(listener919.javaClass, verified919))
        assertSame(callback919, access919.callback.get(listener919))
        assertTrue(access919.enabled.getBoolean(listener919))
        assertNull(shape(zag1::class.java, verified919))
        assertNull(shape(RecyclerFixtures.ForeignLoadMoreListener::class.java, verified919))

        val callback921 = op2()
        val listener921 = zag1(callback921)
        val verified921 = requireNotNull(resolveRelocated(TemplateInitFeeds921::class.java.name))
        assertSame(zag1::class.java, verified921)
        val access921 = requireNotNull(shape(listener921.javaClass, verified921))
        assertSame(callback921, access921.callback.get(listener921))
        assertTrue(access921.enabled.getBoolean(listener921))
        assertNull(shape(d571::class.java, verified921))
    }

    fun `relocated listener discovery fails closed on ambiguity or drifted shapes and works through default continuation names`() {
        assertNull(resolveRelocated("com.bilibili.pegasus.MissingInitFeeds"))
        assertNull(resolveRelocated(TemplateInitFeedsAmbiguous::class.java.name))
        assertNull(resolveRelocated(TemplateInitFeedsWrongShape::class.java.name))

        val defaultContinuationLoader = TestRenamingClassLoader(
            parent = loader,
            templates = mapOf(
                PegasusLoadMoreShape.PEGASUS_FRAGMENT to TemplatePegasusFragment::class.java,
                PegasusLoadMoreShape.INIT_FEEDS_CONTINUATION to TemplateInitFeeds921::class.java
            ),
            replacements = mapOf(
                TemplatePegasusFragment::class.java.name.replace('.', '/') to
                    PegasusLoadMoreShape.PEGASUS_FRAGMENT.replace('.', '/'),
                TemplateInitFeeds921::class.java.name.replace('.', '/') to
                    PegasusLoadMoreShape.INIT_FEEDS_CONTINUATION.replace('.', '/')
            )
        )
        val access = requireNotNull(HomeRecommendEarlyLoadFeatureInstaller.RecyclerAccess.resolve(defaultContinuationLoader, names))
        assertSame(zag1::class.java, access.relocatedListener)
        val watcher = HomeRecommendEarlyLoadFeatureInstaller.LoadMoreWatcher(access, environment(classLoader = defaultContinuationLoader))
        assertNotNull(watcher.shapeOf(zag1::class.java))
        assertNull(watcher.shapeOf(d571::class.java))
    }

    // ---- 行数判定 ----

    fun `the lead is counted in rows of the current column count`() {
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

    fun `invalid positions or counts never trigger`() {
        assertFalse(HomeRecommendEarlyLoadPolicy.withinLead(-1, 3, 2, spanSize = ::small))
        assertFalse(HomeRecommendEarlyLoadPolicy.withinLead(3, 3, 2, spanSize = ::small))
        assertFalse(HomeRecommendEarlyLoadPolicy.withinLead(0, 3, 0, spanSize = ::small))
        assertFalse(HomeRecommendEarlyLoadPolicy.withinLead(0, 3, 2, leadRows = 0, spanSize = ::small))
        // 最后一项已挂载：剩余 0 行，属于“到底了”，应当请求。
        assertTrue(HomeRecommendEarlyLoadPolicy.withinLead(2, 3, 2, spanSize = ::small))
    }

    fun `the gate requests once per list length and reopens after growth or refresh`() {
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

    private fun shape(type: Class<*>, verifiedRelocatedListener: Class<*>? = null) = PegasusLoadMoreShape.of(
        type,
        RecyclerFixtures.RecyclerView.OnScrollListener::class.java,
        RecyclerFixtures.RecyclerView.OnChildAttachStateChangeListener::class.java,
        RecyclerFixtures.Function0::class.java,
        verifiedRelocatedListener
    )

    fun `the home load more listener is recognised by structure and its fields are readable`() {
        val callback = RecyclerFixtures.MergedCallback()
        val listener = LoadMoreListenerFixtures.HomeLoadMore(callback, true)
        val shape = requireNotNull(shape(listener.javaClass))
        assertSame(callback, shape.callback.get(listener))
        assertTrue(shape.enabled.getBoolean(listener))
        assertFalse(shape.enabled.getBoolean(LoadMoreListenerFixtures.HomeLoadMore(callback, false)))
    }

    fun `look alike listeners are not mistaken for the home load more listener`() {
        assertNull(shape(LoadMoreListenerFixtures.OtherList::class.java))
        assertNull(shape(LoadMoreListenerFixtures.ScrollOnly::class.java))
        // 形状一致但不在宿主首页包下。
        assertNull(shape(RecyclerFixtures.ForeignLoadMoreListener::class.java))
    }

    // ---- 安装 ----

    fun `installing hooks only the list scroll listener registration`() {
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

    fun `a list that is not a view is ignored without touching the host`() {
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

    fun `the watcher caches one structural verdict per listener class`() {
        val access = requireNotNull(HomeRecommendEarlyLoadFeatureInstaller.RecyclerAccess.resolve(loader, names))
        val watcher = HomeRecommendEarlyLoadFeatureInstaller.LoadMoreWatcher(access, environment())
        val first = watcher.shapeOf(LoadMoreListenerFixtures.HomeLoadMore::class.java)
        assertNotNull(first)
        assertSame(first, watcher.shapeOf(LoadMoreListenerFixtures.HomeLoadMore::class.java))
        assertNull(watcher.shapeOf(LoadMoreListenerFixtures.OtherList::class.java))
        assertNull(watcher.shapeOf(LoadMoreListenerFixtures.OtherList::class.java))
    }

    fun `disabled and secondary processes register nothing`() {
        val registrar = PlayerPortTestRegistrar()
        val statuses = mutableListOf<String>()
        assertEquals(FeatureInstallResult.Skipped("disabled"),
            HomeRecommendEarlyLoadFeatureInstaller(false, names).install(environment(registrar, status = { _, v -> statuses += v })))
        assertEquals(FeatureInstallResult.Skipped("non-main-process"),
            HomeRecommendEarlyLoadFeatureInstaller(true, names).install(environment(registrar, process = "tv.danmaku.bili:web")))
        assertEquals(listOf("disabled"), statuses)
        assertTrue(registrar.registrationAttempts.isEmpty())
    }

    fun `a missing or reshaped list boundary fails closed`() {
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

    fun `the production names resolve against the real androidx list and kotlin function types`() {
        // 模块自身依赖同一套 androidx RecyclerView 与 Kotlin 标准库：默认名必须能逐个解析出来。
        val registrar = PlayerPortTestRegistrar()
        assertEquals(FeatureInstallResult.Installed(1), HomeRecommendEarlyLoadFeatureInstaller(true).install(environment(registrar)))
        assertEquals("androidx.recyclerview.widget.RecyclerView", registrar.registrationAttempts.single().second.declaringClass.name)
    }

    fun `a registration failure is reported and not counted as installed`() {
        assertEquals(FeatureInstallResult.Skipped("registration-failed"),
            HomeRecommendEarlyLoadFeatureInstaller(true, names).install(
                environment(PlayerPortTestRegistrar(HomeRecommendEarlyLoadFeatureInstaller.LISTENER_HOOK_ID))))
    }

    open class FakeEarlyLoadRecyclerView : ViewGroup(null) {
        open class OnScrollListener
        interface OnChildAttachStateChangeListener {
            fun onChildViewAttachedToWindow(view: View)
            fun onChildViewDetachedFromWindow(view: View)
        }
        abstract class Adapter {
            abstract fun getItemCount(): Int
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) = Unit

        @Suppress("UNUSED_PARAMETER")
        fun addOnScrollListener(listener: OnScrollListener) = Unit

        fun addOnChildAttachStateChangeListener(listener: OnChildAttachStateChangeListener) {
            recordAttach(this, listener)
        }

        fun getAdapter(): Adapter? = null
        @Suppress("UNUSED_PARAMETER")
        fun getChildAdapterPosition(child: View): Int = -1
        fun isComputingLayout(): Boolean = false
        fun getLayoutManager(): Any? = null

        companion object {
            val attachCounts = IdentityHashMap<Any, Int>()
            fun recordAttach(receiver: Any, @Suppress("UNUSED_PARAMETER") listener: Any) {
                synchronized(attachCounts) {
                    attachCounts[receiver] = (attachCounts[receiver] ?: 0) + 1
                }
            }
        }
    }

    class RelocatedEarlyLoadListener(
        @JvmField val callback: RecyclerFixtures.Function0 = RecyclerFixtures.MergedCallback(),
        @JvmField var enabled: Boolean = true,
        @JvmField var lead: Int = 2
    ) : FakeEarlyLoadRecyclerView.OnScrollListener(), FakeEarlyLoadRecyclerView.OnChildAttachStateChangeListener {
        override fun onChildViewAttachedToWindow(view: View) = Unit
        override fun onChildViewDetachedFromWindow(view: View) = Unit
    }

    class ValidRootListener(
        @JvmField val callback: () -> Unit = {},
        @JvmField var enabled: Boolean = true,
        @JvmField var lead: Int = 2
    ) : androidx.recyclerview.widget.RecyclerView.OnScrollListener(),
        androidx.recyclerview.widget.RecyclerView.OnChildAttachStateChangeListener {
        override fun onChildViewAttachedToWindow(view: View) = Unit
        override fun onChildViewDetachedFromWindow(view: View) = Unit
    }

    class ValidRootListener2(
        @JvmField val callback: () -> Unit = {},
        @JvmField var enabled: Boolean = true,
        @JvmField var lead: Int = 2
    ) : androidx.recyclerview.widget.RecyclerView.OnScrollListener(),
        androidx.recyclerview.widget.RecyclerView.OnChildAttachStateChangeListener {
        override fun onChildViewAttachedToWindow(view: View) = Unit
        override fun onChildViewDetachedFromWindow(view: View) = Unit
    }

    class MissingFunction0Listener :
        androidx.recyclerview.widget.RecyclerView.OnScrollListener(),
        androidx.recyclerview.widget.RecyclerView.OnChildAttachStateChangeListener {
        @JvmField var enabled: Boolean = true
        @JvmField var lead: Int = 2
        override fun onChildViewAttachedToWindow(view: View) = Unit
        override fun onChildViewDetachedFromWindow(view: View) = Unit
    }

    class NoiseScrollOnlyListener(
        @JvmField val callback: RecyclerFixtures.Function0 = RecyclerFixtures.MergedCallback(),
        @JvmField var enabled: Boolean = true,
        @JvmField var lead: Int = 2
    ) : FakeEarlyLoadRecyclerView.OnScrollListener()

    class DummyPegasusFragment

    class TemplateInitFeedsForValidRoot(
        @Suppress("UNUSED_PARAMETER") fragment: DummyPegasusFragment,
        @Suppress("UNUSED_PARAMETER") listener: ValidRootListener,
        @Suppress("UNUSED_PARAMETER") cont: Continuation<*>
    )

    private fun allocateView(cls: Class<*>): ViewGroup {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(theUnsafe, cls) as ViewGroup
    }

    fun `adapter verification, LoadMoreWatcher replay, adaptation retry, and cache merge`() {
        val baseLoader = loader
        assertTrue(HomeRecommendEarlyLoadAdapter.allowedClassName("zag1"))
        assertTrue(HomeRecommendEarlyLoadAdapter.allowedClassName("com.example.FastLoader"))
        assertFalse(HomeRecommendEarlyLoadAdapter.allowedClassName("androidx.recyclerview.widget.RecyclerView"))
        assertFalse(HomeRecommendEarlyLoadAdapter.allowedClassName("[Lzag1;"))
        assertFalse(HomeRecommendEarlyLoadAdapter.allowedClassName(""))
        assertFalse(HomeRecommendEarlyLoadAdapter.allowedClassName("123bad"))
        assertTrue(HomeRecommendEarlyLoadAdapter.verify(baseLoader, ValidRootListener::class.java))
        assertFalse(HomeRecommendEarlyLoadAdapter.verify(baseLoader, MissingFunction0Listener::class.java))

        val baseResult = VersionAdapter.AdaptResult(
            biliVersionCode = 9210100, ts = 1L,
            commentLow = null, commentHigh = null, mineEntry = null,
            pause = VersionAdapter.PausePoints(emptyList(), null, null, null),
            banner = null, homeTopBar = null, mineVip = null, blockUpdate = null,
            dynamicTabs = null, fullNumbers = null, playerPortrait = null,
            playerInteractiveOverlays = null, pgcAutoActivityPopup = null, playerStatusBar = null,
            homeRecommendFeed = null, videoRelate = null, homeTabs = null, homeComponents = null,
            mineComponents = null, mineAccountMine = null, storyFeed = null, bottomBar = null,
            playerQuality = null, teenagersMode = null, commentPurify = null, commentFilter = null,
            commentTopology = null, commentSection = null, splashAds = null,
            hostFingerprint = "fp", protocolFingerprint = "protocol-v1:1:000000000000000000000000",
            diagnostics = emptyList()
        )
        val jsonWithNull = baseResult.toJson()
        assertFalse(jsonWithNull.has("home_recommend_early_load_listener"))
        assertNull(VersionAdapter.AdaptResult.fromJson(jsonWithNull)?.homeRecommendEarlyLoadListener)
        val jsonWithListener = baseResult.copy(homeRecommendEarlyLoadListener = "x9a").toJson()
        assertEquals("x9a", jsonWithListener.getString("home_recommend_early_load_listener"))
        assertEquals("x9a", VersionAdapter.AdaptResult.fromJson(jsonWithListener)?.homeRecommendEarlyLoadListener)

        val corruptedJson = baseResult.toJson().put("home_recommend_early_load_listener", JSONObject.NULL)
        assertNull(VersionAdapter.AdaptResult.fromJson(corruptedJson))

        val cachedFound = baseResult.copy(
            homeRecommendEarlyLoadListener = "ValidListener",
            domainRuleVersions = com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapterContract.DOMAIN_RULE_VERSIONS,
            diagnostics = listOf(VersionAdapter.AdaptDiagnostic(HomeRecommendEarlyLoadAdapter.DIAGNOSTIC, VersionAdapter.AdaptState.FOUND, "ok"))
        )
        val mergedFound = requireNotNull(VersionAdapter.mergeRuntimeWithCached(baseResult, cachedFound))
        assertEquals("ValidListener", mergedFound.homeRecommendEarlyLoadListener)
        assertTrue(mergedFound.diagnostics.any {
            it.id == HomeRecommendEarlyLoadAdapter.DIAGNOSTIC && it.state == VersionAdapter.AdaptState.FOUND
        })

        val missingDiag = VersionAdapter.AdaptDiagnostic(HomeRecommendEarlyLoadAdapter.DIAGNOSTIC, VersionAdapter.AdaptState.MISSING, "no-match")
        val mergedMissing = requireNotNull(VersionAdapter.mergeRuntimeWithCached(
            baseResult,
            baseResult.copy(
                domainRuleVersions = com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapterContract.DOMAIN_RULE_VERSIONS,
                diagnostics = listOf(missingDiag)
            )
        ))
        assertNull(mergedMissing.homeRecommendEarlyLoadListener)
        assertTrue(AdaptAssistGate.isAttemptedInCache(missingDiag))
        assertTrue(mergedMissing.diagnostics.any {
            it.id == HomeRecommendEarlyLoadAdapter.DIAGNOSTIC && it.state == VersionAdapter.AdaptState.MISSING
        })

        val noInitFeedsLoader = TestRenamingClassLoader(
            parent = baseLoader,
            hiddenNames = setOf(PegasusLoadMoreShape.INIT_FEEDS_CONTINUATION)
        )
        val oversizedSession = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(emptyList(), List(33) { ValidRootListener::class.java }) },
            listOf("test.apk"), noInitFeedsLoader, setOf(HomeRecommendEarlyLoadAdapter.query)
        )
        assertNull(HomeRecommendEarlyLoadAdapter.locate(noInitFeedsLoader, oversizedSession, enabled = true, cached = null, planned = true))
        val ambiguousSession = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(emptyList(), listOf(ValidRootListener::class.java, ValidRootListener2::class.java)) },
            listOf("test.apk"), noInitFeedsLoader, setOf(HomeRecommendEarlyLoadAdapter.query)
        )
        assertNull(HomeRecommendEarlyLoadAdapter.locate(noInitFeedsLoader, ambiguousSession, enabled = true, cached = null, planned = true))
        val uniqueSession = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(emptyList(), listOf(ValidRootListener::class.java, MissingFunction0Listener::class.java)) },
            listOf("test.apk"), noInitFeedsLoader, setOf(HomeRecommendEarlyLoadAdapter.query)
        )
        assertEquals(ValidRootListener::class.java.name,
            HomeRecommendEarlyLoadAdapter.locate(noInitFeedsLoader, uniqueSession, enabled = true, cached = null, planned = true))

        val watcherNames = HomeRecommendEarlyLoadFeatureInstaller.HostNames(
            recyclerView = FakeEarlyLoadRecyclerView::class.java.name,
            gridLayoutManager = RecyclerFixtures.GridLayoutManager::class.java.name,
            function0 = RecyclerFixtures.Function0::class.java.name
        )
        val access = requireNotNull(HomeRecommendEarlyLoadFeatureInstaller.RecyclerAccess.resolve(baseLoader, watcherNames))
        val stages = mutableListOf<FeatureRuntimeStage>()
        val watcher = HomeRecommendEarlyLoadFeatureInstaller.LoadMoreWatcher(
            recycler = access,
            environment = environment(evidence = { _, stage, _ -> stages += stage }),
            initialRelocatedListener = null,
            initialAwaitingRelocated = true
        )
        val views = List(9) { allocateView(FakeEarlyLoadRecyclerView::class.java) }
        val relocated = RelocatedEarlyLoadListener()
        watcher.onScrollListenerAdded(views[0], relocated)
        assertNull(watcher.shapeOf(RelocatedEarlyLoadListener::class.java))
        assertEquals(1, watcher.pendingCandidateCountForTest())
        watcher.onScrollListenerAdded(allocateView(FakeEarlyLoadRecyclerView::class.java), NoiseScrollOnlyListener())
        assertEquals(1, watcher.pendingCandidateCountForTest())
        for (i in 1 until views.size) watcher.onScrollListenerAdded(views[i], relocated)
        assertEquals(8, watcher.pendingCandidateCountForTest())

        synchronized(FakeEarlyLoadRecyclerView.attachCounts) { FakeEarlyLoadRecyclerView.attachCounts.clear() }
        watcher.updateRelocatedListener(RelocatedEarlyLoadListener::class.java)
        assertEquals(0, watcher.pendingCandidateCountForTest())
        assertNotNull(watcher.shapeOf(RelocatedEarlyLoadListener::class.java))
        assertEquals(1, FakeEarlyLoadRecyclerView.attachCounts[views[0]])
        assertEquals(1, FakeEarlyLoadRecyclerView.attachCounts[views[7]])
        assertNull(FakeEarlyLoadRecyclerView.attachCounts[views[8]])
        assertTrue(FeatureRuntimeStage.OBSERVED in stages)

        val pegasusLoader = TestRenamingClassLoader(
            parent = baseLoader,
            templates = mapOf(PegasusLoadMoreShape.PEGASUS_FRAGMENT to DummyPegasusFragment::class.java),
            hiddenNames = setOf(PegasusLoadMoreShape.INIT_FEEDS_CONTINUATION)
        )
        var cachedRelocatedName: String? = null
        val installerRegistrar = PlayerPortTestRegistrar()
        val installerEnv = environment(registrar = installerRegistrar, classLoader = pegasusLoader)
        val earlyInstaller = HomeRecommendEarlyLoadFeatureInstaller(
            enabled = true,
            cachedRelocatedListenerProvider = { cachedRelocatedName }
        )
        assertEquals(FeatureInstallResult.Installed(1), earlyInstaller.install(installerEnv))
        assertTrue(earlyInstaller.requiresAdaptationRetry)
        assertEquals(1, installerRegistrar.registrationAttempts.size)
        cachedRelocatedName = ValidRootListener::class.java.name
        assertEquals(FeatureInstallResult.Installed(1), earlyInstaller.install(installerEnv))
        assertFalse(earlyInstaller.requiresAdaptationRetry)
        assertEquals(1, installerRegistrar.registrationAttempts.size)

        val cachedAttemptedInstaller = HomeRecommendEarlyLoadFeatureInstaller(
            enabled = true, cachedRelocatedListenerProvider = { null }, adaptationAttemptedInCache = true
        )
        assertEquals(FeatureInstallResult.Installed(1), cachedAttemptedInstaller.install(installerEnv))
        assertFalse(cachedAttemptedInstaller.requiresAdaptationRetry)

        val awaitingInstaller = HomeRecommendEarlyLoadFeatureInstaller(
            enabled = true, cachedRelocatedListenerProvider = { null }, adaptationAttemptedInCache = false
        )
        assertEquals(FeatureInstallResult.Installed(1), awaitingInstaller.install(installerEnv))
        assertTrue(awaitingInstaller.requiresAdaptationRetry)
        awaitingInstaller.finishAdaptationWait()
        assertFalse(awaitingInstaller.requiresAdaptationRetry)

        val directLoader = TestRenamingClassLoader(
            parent = baseLoader,
            templates = mapOf(
                PegasusLoadMoreShape.PEGASUS_FRAGMENT to DummyPegasusFragment::class.java,
                PegasusLoadMoreShape.INIT_FEEDS_CONTINUATION to TemplateInitFeedsForValidRoot::class.java
            ),
            replacements = mapOf(
                DummyPegasusFragment::class.java.name.replace('.', '/') to
                    PegasusLoadMoreShape.PEGASUS_FRAGMENT.replace('.', '/'),
                TemplateInitFeedsForValidRoot::class.java.name.replace('.', '/') to
                    PegasusLoadMoreShape.INIT_FEEDS_CONTINUATION.replace('.', '/')
            )
        )
        val directAccess = requireNotNull(HomeRecommendEarlyLoadFeatureInstaller.RecyclerAccess.resolve(directLoader, watcherNames))
        val directWatcher = HomeRecommendEarlyLoadFeatureInstaller.LoadMoreWatcher(
            recycler = directAccess,
            environment = environment(classLoader = directLoader),
            initialRelocatedListener = RelocatedEarlyLoadListener::class.java,
            initialAwaitingRelocated = false
        )
        assertNotNull(directWatcher.shapeOf(RelocatedEarlyLoadListener::class.java))
        directWatcher.updateRelocatedListener(null)
        assertNotNull(directWatcher.shapeOf(RelocatedEarlyLoadListener::class.java))

        val awaitingWatcher = HomeRecommendEarlyLoadFeatureInstaller.LoadMoreWatcher(
            recycler = directAccess,
            environment = environment(classLoader = directLoader),
            initialRelocatedListener = null,
            initialAwaitingRelocated = true
        )
        awaitingWatcher.onScrollListenerAdded(allocateView(FakeEarlyLoadRecyclerView::class.java), relocated)
        assertNull(awaitingWatcher.shapeOf(RelocatedEarlyLoadListener::class.java))
        assertEquals(1, awaitingWatcher.pendingCandidateCountForTest())
        awaitingWatcher.updateRelocatedListener(null)
        assertEquals(0, awaitingWatcher.pendingCandidateCountForTest())
        awaitingWatcher.onScrollListenerAdded(allocateView(FakeEarlyLoadRecyclerView::class.java), relocated)
        assertEquals(0, awaitingWatcher.pendingCandidateCountForTest())
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (HomeRecommendEarlyLoadTest) -> Unit) {
            val s = HomeRecommendEarlyLoadTest()
            block(s)
        }
        runIsolated { s -> s.`accepts only the verified relocated listener and callback shape`() }
        runIsolated { s -> s.`relocated listener discovery fails closed on ambiguity or drifted shapes and works through default continuation names`() }
        runIsolated { s -> s.`the lead is counted in rows of the current column count`() }
        runIsolated { s -> s.`invalid positions or counts never trigger`() }
        runIsolated { s -> s.`the gate requests once per list length and reopens after growth or refresh`() }
        runIsolated { s -> s.`the home load more listener is recognised by structure and its fields are readable`() }
        runIsolated { s -> s.`look alike listeners are not mistaken for the home load more listener`() }
        runIsolated { s -> s.`installing hooks only the list scroll listener registration`() }
        runIsolated { s -> s.`a list that is not a view is ignored without touching the host`() }
        runIsolated { s -> s.`the watcher caches one structural verdict per listener class`() }
        runIsolated { s -> s.`disabled and secondary processes register nothing`() }
        runIsolated { s -> s.`a missing or reshaped list boundary fails closed`() }
        runIsolated { s -> s.`the production names resolve against the real androidx list and kotlin function types`() }
        runIsolated { s -> s.`a registration failure is reported and not counted as installed`() }
        runIsolated { s -> s.`adapter verification, LoadMoreWatcher replay, adaptation retry, and cache merge`() }
    }
}
