package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.earlyload.RecyclerFixtures
import com.bilibili.pegasus.widget.LoadMoreListenerFixtures
import d571
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.coroutines.Continuation
import op2
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

    private class ContinuationRenamingClassLoader(
        parent: ClassLoader,
        private val templates: Map<String, Class<*>>,
        private val replacements: Map<String, String>
    ) : ClassLoader(parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(this) {
            val template = templates[name] ?: return@synchronized super.loadClass(name, resolve)
            val loaded = findLoadedClass(name) ?: run {
                val raw = requireNotNull(template.getResourceAsStream("/${template.name.replace('.', '/')}.class"))
                    .use { it.readBytes() }
                val rewritten = rewriteConstantPool(raw, replacements)
                defineClass(name, rewritten, 0, rewritten.size)
            }
            if (resolve) resolveClass(loaded)
            loaded
        }

        private fun rewriteConstantPool(raw: ByteArray, replacements: Map<String, String>): ByteArray {
            val input = DataInputStream(ByteArrayInputStream(raw))
            val out = ByteArrayOutputStream()
            val output = DataOutputStream(out)
            output.writeInt(input.readInt())
            output.writeShort(input.readUnsignedShort())
            output.writeShort(input.readUnsignedShort())
            val count = input.readUnsignedShort()
            output.writeShort(count)
            var index = 1
            while (index < count) {
                val tag = input.readUnsignedByte()
                output.writeByte(tag)
                if (tag == 1) {
                    var value = input.readUTF()
                    replacements.entries.sortedByDescending { it.key.length }.forEach { (k, v) ->
                        value = value.replace(k, v)
                    }
                    output.writeUTF(value)
                } else {
                    val length = when (tag) {
                        3, 4, 9, 10, 11, 12, 17, 18 -> 4
                        5, 6 -> 8
                        7, 8, 16, 19, 20 -> 2
                        15 -> 3
                        else -> error("Unsupported CP tag: $tag")
                    }
                    repeat(length) { output.writeByte(input.readUnsignedByte()) }
                    if (tag == 5 || tag == 6) index++
                }
                index++
            }
            output.write(input.readBytes())
            output.flush()
            return out.toByteArray()
        }
    }

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

        val defaultContinuationLoader = ContinuationRenamingClassLoader(
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
    }
}
