package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.bapis.bilibili.app.resource.v1.ListReply
import com.bapis.bilibili.app.resource.v1.ModuleMoss
import com.bapis.bilibili.app.resource.v1.ModuleReply
import com.bapis.bilibili.app.resource.v1.PoolReply
import com.bilibili.lib.moss.api.MossResponseHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockComponentLibraryFeatureInstallerTest {
    private val loader = javaClass.classLoader!!

    private fun environment(
        registrar: PlayerPortTestRegistrar,
        stages: MutableList<FeatureRuntimeStage> = mutableListOf(),
        statuses: MutableList<Pair<String, String>> = mutableListOf(),
        published: MutableList<Pair<String, ScanSnapshotContent>> = mutableListOf()
    ) = HookEnvironment(
        writeScanSnapshot = { surface, content -> published += surface to content; true },
        processName = "tv.danmaku.bili:download",
        classLoader = loader,
        hookPoints = HookPointRegistry(loader),
        registrar = registrar,
        logInfo = { _, _ -> },
        logError = { _, _ -> },
        reportStatus = { id, status -> statuses += id to status },
        runtimeEvidence = { id, stage, _ ->
            assertEquals(BlockComponentLibraryFeatureInstaller.ID, id)
            stages += stage
        }
    )

    /**
     * 替身必须复刻 protobuf-lite 的继承形状，否则这组测试证明不了任何事。
     *
     * 2026-09-15 真机故障：解析器按 `toBuilder().returnType` 找 builder，而真实
     * `GeneratedMessageLite.toBuilder()` 声明返回的是**基类**，于是
     * `install=SKIPPED, reason=MISSING_HOST_STRUCTURE`，功能一次都没装上——
     * 而当时的替身把 `toBuilder()` 写成返回具体 `Builder`，单测一路绿。
     *
     * 这条把那个陷阱钉在测试里：`toBuilder()` 的**声明**返回类型上不能有
     * 返回具体消息的 `build()`；具体 builder 只能从静态 `newBuilder(owner)` 拿。
     * 替身要是被改回具体返回类型，这条会红。
     */
    fun `the doubles reproduce the protobuf-lite builder trap instead of hiding it`() {
        listOf(ListReply::class.java, PoolReply::class.java).forEach { message ->
            val declared = message.methods.single { it.name == "toBuilder" && it.parameterCount == 0 }
            assertFalse(
                "${message.simpleName}.toBuilder() 的声明返回类型不该直接给出具体 build()",
                declared.returnType.methods.any { it.name == "build" && it.returnType == message }
            )
            val factory = message.methods.single {
                it.name == "newBuilder" && it.parameterTypes.contentEquals(arrayOf(message))
            }
            assertTrue(
                "${message.simpleName}.newBuilder(owner) 必须给出具体 builder",
                factory.returnType.methods.any { it.name == "build" && it.returnType == message }
            )
        }
    }

    fun `real response hook clears only the recognized component pool`() {
        val registrar = PlayerPortTestRegistrar()
        val stages = mutableListOf<FeatureRuntimeStage>()
        assertEquals(
            FeatureInstallResult.Installed(2),
            BlockComponentLibraryFeatureInstaller(true, setOf("基础组件库"))
                .install(environment(registrar, stages))
        )
        val ordinary = PoolReply("video", listOf(ModuleReply("video-core")))
        val component = PoolReply("基础组件库", listOf(ModuleReply("module-a")))
        val source = ListReply(listOf(ordinary, component))
        val filtered = registrar.invoke("${BlockComponentLibraryFeatureInstaller.ID}.sync", ModuleMoss()) { source }
            as ListReply

        assertFalse(filtered === source)
        assertEquals(2, filtered.getPoolsList().size)
        assertEquals(1, filtered.getPoolsList()[0].getModulesList().size)
        assertTrue(filtered.getPoolsList()[1].getModulesList().isEmpty())
        assertEquals(listOf(FeatureRuntimeStage.ADAPTED, FeatureRuntimeStage.OBSERVED, FeatureRuntimeStage.APPLIED), stages)
        assertEquals(1, source.getPoolsList()[1].getModulesList().size)
    }

    fun `module name hit removes only that module and reuses untouched pools`() {
        val registrar = PlayerPortTestRegistrar()
        val sourcePool = PoolReply(
            "ordinary",
            listOf(ModuleReply("module-a"), ModuleReply("video-core"))
        )
        val untouchedPool = PoolReply("other", listOf(ModuleReply("other-core")))
        val source = ListReply(listOf(sourcePool, untouchedPool))
        BlockComponentLibraryFeatureInstaller(true, setOf("module-a"))
            .install(environment(registrar))

        val filtered = registrar.invoke("${BlockComponentLibraryFeatureInstaller.ID}.sync", ModuleMoss()) { source }
            as ListReply

        assertNotSame(source, filtered)
        assertNotSame(sourcePool, filtered.getPoolsList()[0])
        assertSame(untouchedPool, filtered.getPoolsList()[1])
        assertEquals(
            listOf("video-core"),
            filtered.getPoolsList()[0].getModulesList().map { it.getModuleName() }
        )
        assertEquals(listOf("module-a", "video-core"), sourcePool.getModulesList().map { it.getModuleName() })
    }

    fun `async response hook transforms only the delegated onNext reply`() {
        val registrar = PlayerPortTestRegistrar()
        val stages = mutableListOf<FeatureRuntimeStage>()
        BlockComponentLibraryFeatureInstaller(true, setOf("基础组件库"))
            .install(environment(registrar, stages))
        val ordinary = PoolReply("video", listOf(ModuleReply("video-core")))
        val component = PoolReply("基础组件库", listOf(ModuleReply("module-a")))
        val source = ListReply(listOf(ordinary, component))
        var delivered: ListReply? = null
        val delegate = object : MossResponseHandler {
            override fun onNext(reply: Any?) { delivered = reply as? ListReply }
            override fun onError(error: Throwable) = Unit
            override fun onCompleted() = Unit
        }

        registrar.invoke(BlockComponentLibraryFeatureInstaller.ID, ModuleMoss(), arrayOf(null, delegate)) { args ->
            (args[1] as MossResponseHandler).onNext(source)
            null
        }

        assertEquals(0, delivered?.getPoolsList()?.get(1)?.getModulesList()?.size)
        assertEquals(1, delivered?.getPoolsList()?.get(0)?.getModulesList()?.size)
        assertEquals(1, source.getPoolsList()[1].getModulesList().size)
        assertEquals(
            listOf(FeatureRuntimeStage.ADAPTED, FeatureRuntimeStage.OBSERVED, FeatureRuntimeStage.APPLIED),
            stages
        )
    }

    /**
     * 一个池都没勾（[ComponentLibraryPoolMatcher.DEFAULT_KEYWORDS] 为空）时只扫描不过滤。
     *
     * 这是勾选面板的**正常终态**，不是装配缺陷：面板要先有候选，用户才能勾。
     * 所以 `complete` 仍为 true（两处 Hook 都装上了），"还没选"由状态串
     * 尾部的 `:scan` 表达——2026-09-15 之前这里是 `observing-target-unconfirmed`，
     * 那是"模块自己猜目标池"的旧模型，已随勾选面板作废。
     */
    fun `unrecognized response is preserved by identity`() {
        val registrar = PlayerPortTestRegistrar()
        val stages = mutableListOf<FeatureRuntimeStage>()
        val statuses = mutableListOf<Pair<String, String>>()
        val result = BlockComponentLibraryFeatureInstaller(true).install(environment(registrar, stages, statuses))
        val source = ListReply(listOf(PoolReply("video", listOf(ModuleReply("video-core")))))

        assertEquals(FeatureInstallResult.Installed(2, true), result)
        assertEquals(
            listOf("block_component_library_status" to "success:scan"),
            statuses
        )
        assertSame(
            source,
            registrar.invoke("${BlockComponentLibraryFeatureInstaller.ID}.sync", ModuleMoss()) { source }
        )
        assertEquals(listOf(FeatureRuntimeStage.ADAPTED, FeatureRuntimeStage.OBSERVED), stages)
    }

    /**
     * 宿主**按需分片**下发清单，整份替换会让面板只剩最后一片。
     *
     * 2026-09-15 真机实证（宿主 9.x，单次冷启动内）：日志
     * `组件库清单观察(仅名称/数量): pink:1/1[video_detail_like_animation.zip]`，
     * 而同一场里落盘快照先后是 `appletBasic（1）`、`uper（1）` —— 三个不同的单池分片
     * 互相覆盖，面板里永远只有一条；磁盘上 `app_mod_resource/manifest/` 实际有 17 个池。
     */
    fun `fragmented manifest responses accumulate instead of overwriting the candidate list`() {
        val registrar = PlayerPortTestRegistrar()
        val published = mutableListOf<Pair<String, ScanSnapshotContent>>()
        BlockComponentLibraryFeatureInstaller(true)
            .install(environment(registrar, published = published))
        val sync = "${BlockComponentLibraryFeatureInstaller.ID}.sync"

        listOf(
            ListReply(listOf(PoolReply("pink", listOf(ModuleReply("video_detail_like_animation.zip"))))),
            ListReply(listOf(PoolReply("uper", listOf(ModuleReply("uper_tab"))))),
            ListReply(listOf(PoolReply("appletBasic", listOf(ModuleReply("miniprogram_engine")))))
        ).forEach { fragment -> registrar.invoke(sync, ModuleMoss()) { fragment } }

        assertEquals(
            MineComponentSnapshotCodec.SURFACE_COMPONENT_POOLS,
            published.last().first
        )
        assertEquals(
            listOf("appletBasic（1）", "pink（1）", "uper（1）"),
            published.last().second.entries.mapNotNull { it.title }.sorted()
        )
        // 第一片发布时确实只有一个池——增长来自累积，不是某次响应恰好给了全量。
        assertEquals(listOf("pink（1）"), published.first().second.entries.mapNotNull { it.title })
    }

    /** 同一个池被多次分片提到时，模块数取并集计数，而不是只报最后一片的数量。 */
    fun `module counts union per pool across fragments`() {
        val registrar = PlayerPortTestRegistrar()
        val published = mutableListOf<Pair<String, ScanSnapshotContent>>()
        BlockComponentLibraryFeatureInstaller(true)
            .install(environment(registrar, published = published))
        val sync = "${BlockComponentLibraryFeatureInstaller.ID}.sync"

        listOf(
            listOf("bangumi_tab_card3"),
            listOf("calendar_alert"),
            listOf("calendar_alert")   // 重复出现的模块不重复计数
        ).forEach { modules ->
            val fragment = ListReply(listOf(PoolReply("ogv", modules.map(::ModuleReply))))
            registrar.invoke(sync, ModuleMoss()) { fragment }
        }

        assertEquals(listOf("ogv（2）"), published.last().second.entries.mapNotNull { it.title })
    }

    /** 模块名读不出来时退到"单次响应见过的最大模块数"，不显示 0；池数与模块名都有上界。 */
    fun `the catalog falls back to observed counts and stays bounded`() {
        val catalog = ComponentPoolCatalog()
        assertEquals(
            listOf("ogv（4）"),
            catalog.merge(listOf(ComponentPoolObservation("ogv", emptyList(), 4))).mapNotNull { it.title }
        )
        // 后来的一片更小，不该把已知的更大数量抹低。
        assertEquals(
            listOf("ogv（4）"),
            catalog.merge(listOf(ComponentPoolObservation("ogv", emptyList(), 1))).mapNotNull { it.title }
        )
        // 名字能读出来时以并集为准，仍不低于已观测到的数量。
        assertEquals(
            listOf("ogv（5）"),
            catalog.merge(
                listOf(ComponentPoolObservation("ogv", listOf("a", "b", "c", "d", "e"), 2))
            ).mapNotNull { it.title }
        )
        val overflow = (1..MineComponentSnapshotCodec.MAX_ENTRY_COUNT + 20).map {
            ComponentPoolObservation("pool-$it", listOf("m"), 1)
        }
        assertEquals(MineComponentSnapshotCodec.MAX_ENTRY_COUNT, catalog.merge(overflow).size)
    }

    /** 勾过池之后就不再是"只扫描"，状态串不该再带 `:scan` 尾巴。 */
    fun `a picked pool drops the scan only status suffix`() {
        val statuses = mutableListOf<Pair<String, String>>()
        BlockComponentLibraryFeatureInstaller(true, setOf("基础组件库"))
            .install(environment(PlayerPortTestRegistrar(), mutableListOf(), statuses))
        assertEquals(listOf("block_component_library_status" to "success"), statuses)
    }

    /**
     * 勾选面板落到拦截判据的整条路：面板存的是 `component_pool:<池名>` 形式的
     * selector 键，手填框存的是分隔符串，两边取并集后才交给 [ComponentLibraryPoolMatcher.match]。
     * 前缀要是没剥干净，池名就永远对不上，功能会静默不拦。
     */
    fun `selection unions picked selectors with typed rules and strips the selector prefix`() {
        val selectors = MineComponentSelectionCodec.encode(
            listOf("component_pool:mod-fitness", "component_pool:mod-search")
        )
        assertEquals(
            setOf("mod-fitness", "mod-search", "mod-danmaku"),
            ComponentLibraryPoolMatcher.selection(selectors, " mod-danmaku ，mod-search ,, \n ")
        )
        // 两边都空 = 不拦任何池，DEFAULT_KEYWORDS 也是空集。
        assertEquals(emptySet<String>(), ComponentLibraryPoolMatcher.selection("", ""))
        assertEquals(emptySet<String>(), ComponentLibraryPoolMatcher.DEFAULT_KEYWORDS)
    }

    /**
     * 「全量禁止」开关写进手填规则的就是 `*`。它必须先于池名比对短路，
     * 否则宿主新增的池会漏——真机当时 17 个池，名单式拦不全。
     */
    fun `the block all sentinel clears every pool regardless of its name`() {
        val keywords = ComponentLibraryPoolMatcher.selection("", ComponentLibraryPoolMatcher.MATCH_ALL_POOLS)
        assertEquals(setOf("*"), keywords)
        listOf("mod-fitness", "从未见过的新池", null).forEach { pool ->
            val match = ComponentLibraryPoolMatcher.match(pool, listOf("whatever"), keywords)
            assertEquals(ComponentLibraryMatch(wholePool = true), match)
        }
    }

    /** 没有哨兵时仍是保守判据：只清命中的池/模块，认不出来的一律放行。 */
    fun `picked pool names clear that pool only and unknown names never match`() {
        val keywords = ComponentLibraryPoolMatcher.selection(
            MineComponentSelectionCodec.encode(listOf("component_pool:MOD-Fitness")),
            ""
        )
        assertEquals(
            ComponentLibraryMatch(wholePool = true),
            ComponentLibraryPoolMatcher.match("mod-fitness", listOf("a"), keywords)
        )
        assertEquals(null, ComponentLibraryPoolMatcher.match("mod-search", listOf("a"), keywords))
        assertEquals(null, ComponentLibraryPoolMatcher.match("", emptyList(), keywords))
        // 关键词集为空 = 功能实质关闭，任何池都不命中。
        assertEquals(null, ComponentLibraryPoolMatcher.match("mod-fitness", listOf("a"), emptySet()))
    }

    fun `disabled feature never resolves or registers the host hook`() {
        val registrar = PlayerPortTestRegistrar()
        assertEquals(
            FeatureInstallResult.Skipped("disabled"),
            BlockComponentLibraryFeatureInstaller(false).install(environment(registrar))
        )
        assertTrue(registrar.hooks.isEmpty())
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (BlockComponentLibraryFeatureInstallerTest) -> Unit) {
            val s = BlockComponentLibraryFeatureInstallerTest()
            block(s)
        }
        runIsolated { s -> s.`the doubles reproduce the protobuf-lite builder trap instead of hiding it`() }
        runIsolated { s -> s.`real response hook clears only the recognized component pool`() }
        runIsolated { s -> s.`module name hit removes only that module and reuses untouched pools`() }
        runIsolated { s -> s.`async response hook transforms only the delegated onNext reply`() }
        runIsolated { s -> s.`unrecognized response is preserved by identity`() }
        runIsolated { s -> s.`fragmented manifest responses accumulate instead of overwriting the candidate list`() }
        runIsolated { s -> s.`module counts union per pool across fragments`() }
        runIsolated { s -> s.`the catalog falls back to observed counts and stays bounded`() }
        runIsolated { s -> s.`a picked pool drops the scan only status suffix`() }
        runIsolated { s -> s.`selection unions picked selectors with typed rules and strips the selector prefix`() }
        runIsolated { s -> s.`the block all sentinel clears every pool regardless of its name`() }
        runIsolated { s -> s.`picked pool names clear that pool only and unknown names never match`() }
        runIsolated { s -> s.`disabled feature never resolves or registers the host hook`() }
    }
}
