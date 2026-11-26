package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 阻止资源组件库清单继续下发。
 *
 * 这个功能故意只在能从返回模型中识别到目标池时改写 builder；池名/模块名尚未被真机
 * 观测确认时保持原响应，避免把基础组件库误扩大为全部资源池。默认关闭，不读宿主文件，
 * 不拉起宿主界面，也不主动发起 IPC。
 */
internal class BlockComponentLibraryFeatureInstaller(
    private val enabled: Boolean,
    private val targetKeywords: Set<String> = ComponentLibraryPoolMatcher.DEFAULT_KEYWORDS,
    private val kmpGrpcWireEngine: com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint? = null
) : FeatureInstaller {
    override val id: String = ID
    override val capabilityIds: List<String> = listOf(CAPABILITY)

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) return skipped(environment, "disabled")
        val loader = environment.classLoader ?: return skipped(environment, "missing-class-loader")
        val wireSurface = com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.KmpGrpcWireAdapter.resolve(loader, kmpGrpcWireEngine)
        val hasKotlinMoss = MOSS_CLASSES.filter { it.contains(".K") }.any {
            KavaMemberLookup.classOrNull(loader, it) != null
        }
        val resolved = MOSS_CLASSES.asSequence().flatMap { mossName ->
            REQUEST_CLASSES.asSequence().mapNotNull { requestName ->
                val moss = KavaMemberLookup.classOrNull(loader, mossName) ?: return@mapNotNull null
                val request = KavaMemberLookup.classOrNull(loader, requestName) ?: return@mapNotNull null
                val sync = METHOD_NAMES.firstNotNullOfOrNull { name ->
                    KavaMemberLookup.methodOrNull(moss, name, request)?.takeIf {
                        !Modifier.isStatic(it.modifiers) && !it.returnType.isPrimitive
                    }
                } ?: return@mapNotNull null
                Triple(moss, request, sync)
            }
        }.firstOrNull()
        val replyClass = resolved?.third?.returnType ?: if (wireSurface != null || hasKotlinMoss) {
            REPLY_CLASSES.firstNotNullOfOrNull { KavaMemberLookup.classOrNull(loader, it) }
        } else null
        if ((resolved == null && wireSurface == null && !hasKotlinMoss) || replyClass == null) {
            return skipped(environment, "not-applicable-host")
        }
        val strategy = ComponentLibraryReplyStrategy.resolve(replyClass, targetKeywords)
            ?: return skipped(environment, "missing-host-structure")
        val observationLogged = AtomicBoolean(false)
        val publisher = ScanSnapshotPublisher(
            environment, MineComponentSnapshotCodec.SURFACE_COMPONENT_POOLS, setOf(CAPABILITY)
        )
        val catalog = ComponentPoolCatalog()

        val observe: (Any) -> Unit = { reply ->
            // **无条件上报候选**（哪怕一个都没勾）：不扫描的话面板永远是空的，
            // 用户就没法勾——四个列表型面共同的纪律，见 AGENTS 勾选面板条目。
            //
            // 这里发布的是 catalog 累积至今的**并集**而不是本次响应的内容：
            // 宿主按需分片下发清单，整份替换会让面板只剩最后一片，见 [ComponentPoolCatalog]。
            catalog.merge(strategy.observePools(reply))
                .takeIf { it.isNotEmpty() }
                ?.let(publisher::publish)
            if (targetKeywords.isEmpty() && observationLogged.compareAndSet(false, true)) {
                strategy.describe(reply)?.let { description ->
                    environment.logInfo(
                        "$ID.observation",
                        "[BIL] 组件库清单观察(仅名称/数量): $description"
                    )
                }
            }
        }
        var installed = 0
        var available = 0
        if (resolved != null) {
            val moss = resolved.first
            val request = resolved.second
            val method = resolved.third
            runCatching {
                environment.registrar.exact("$ID.sync", moss, method.name, request) {
                    after {
                        if (hasThrowable) return@after
                        val reply = result ?: return@after
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                        observe(reply)
                        strategy.clearMatchedPools(reply)?.let { changed ->
                            result = changed
                            environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                        }
                    }
                }
                installed++
            }.onFailure { environment.logError("$ID.sync", "[BIL] 组件库同步清单 Hook 注册失败") }
            available++

            val handlerClass = KavaMemberLookup.classOrNull(loader, MOSS_HANDLER)?.takeIf { it.isInterface }
            if (handlerClass != null) {
                val async = KavaMemberLookup.methods(moss, includeSuperclasses = true, makeAccessible = true) {
                    it.name in METHOD_NAMES && it.parameterTypes.contentEquals(arrayOf(request, handlerClass)) &&
                        it.returnType == Void.TYPE && !Modifier.isStatic(it.modifiers)
                }.singleOrNull()
                if (async != null) {
                    available++
                    if (runCatching {
                        environment.registrar.exact(ID, moss, async.name, request, handlerClass) {
                            before {
                                val delegate = argOrNull(1) ?: return@before
                                val proxy = MossResponseHandlerProxy.wrapTransform(handlerClass, delegate) { reply ->
                                    environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                                    observe(reply)
                                    strategy.clearMatchedPools(reply)?.also {
                                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                                    } ?: reply
                                } ?: return@before
                                args[1] = proxy
                            }
                        }
                    }.isSuccess) {
                        installed++
                    } else {
                        environment.logError("$ID.async", "[BIL] 组件库异步清单 Hook 注册失败")
                    }
                }
            }
        }
        val kmpMossFamily = "com.bapis.bilibili.app.resource.v1.ModuleMoss"
        val transformKmp: (Any) -> Any = { reply ->
            environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
            observe(reply)
            strategy.clearMatchedPools(reply)?.also {
                environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
            } ?: reply
        }
        val kmpWireInstalled = wireSurface != null && KmpGrpcWireChannel.install(
            environment = environment,
            surface = wireSurface,
            javaMossClassName = kmpMossFamily,
            rpc = "list",
            javaReplyClass = replyClass,
            what = "组件库清单拦截",
            logKey = "block_component_library_kmoss",
            transformJava = transformKmp
        )
        val kmpInstalled = kmpWireInstalled || (hasKotlinMoss && KotlinMossChannel.prepare(
            environment, loader, "组件库清单拦截", "block_component_library_kmoss"
        )?.let { bridge ->
            KotlinMossChannel.install(
                environment = environment,
                loader = loader,
                members = bridge,
                javaMossClassName = kmpMossFamily,
                rpc = "list",
                javaReplyClass = replyClass,
                hookId = "$ID.kmoss.list",
                what = "组件库清单拦截",
                logKey = "block_component_library_kmoss",
                transformJava = transformKmp
            )
        } == true)
        if (installed == 0 && kmpInstalled) {
            installed = 1
            available = 1
        }
        if (installed == 0) return skipped(environment, "registration-failed")
        return runCatching {
            environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
            val status = if (installed == available) "success" else "partial:$installed/$available"
            // 一个池都没勾时只扫描不过滤——这是**正常终态**，不是缺陷，所以报
            // `success:scan` 而不是 partial：面板要先有候选，用户才能勾。
            val hasSelection = targetKeywords.isNotEmpty()
            val result = FeatureInstallResult.Installed(
                installed,
                complete = installed == available
            )
            environment.reportStatus(
                STATUS,
                if (!hasSelection) "$status:scan" else status
            )
            environment.reportCapability(CAPABILITY, result)
            result
        }.getOrElse {
            environment.logError("$ID.diagnostics", "[BIL] 组件库清单诊断上报失败")
            skipped(environment, "registration-failed")
        }
    }

    private fun skipped(environment: HookEnvironment, reason: String): FeatureInstallResult.Skipped {
        val result = FeatureInstallResult.Skipped(reason)
        environment.reportStatus(STATUS, reason)
        environment.reportCapability(CAPABILITY, result)
        return result
    }

    companion object {
        const val ID = "block_component_library_download"
        const val CAPABILITY = "block_component_library_download"
        private const val STATUS = "block_component_library_status"
        private const val MOSS_HANDLER = "com.bilibili.lib.moss.api.MossResponseHandler"
        private val MOSS_CLASSES = listOf(
            "com.bapis.bilibili.app.resource.v1.ModuleMoss",
            "com.bapis.bilibili.app.resource.v1.KModuleMoss"
        )
        private val REQUEST_CLASSES = listOf(
            "com.bapis.bilibili.app.resource.v1.ListReq",
            "com.bapis.bilibili.app.resource.v1.KListReq"
        )
        private val REPLY_CLASSES = listOf(
            "com.bapis.bilibili.app.resource.v1.ListReply"
        )
        private val METHOD_NAMES = listOf("executeList", "list")
    }
}

/** 单次清单响应里看到的一个池；模块名可能取不到，此时只有 [moduleCount] 可用。 */
internal data class ComponentPoolObservation(
    val name: String,
    val moduleNames: List<String>,
    val moduleCount: Int
)

/**
 * 跨请求累积「池名 → 已见模块名」，把分片响应拼成勾选面板的候选全集。
 *
 * **宿主的清单请求是按需分片的，不是全量目录。** 2026-09-15 真机实证：
 * 设备上 `app_mod_resource/manifest/` 有 17 个池（`ad` / `applet` / `appletBasic` /
 * `blink` / `bplus` / `danmaku` / `feOffline` / `game` / `live` / `lynx` / `mainSite` /
 * `mainSiteAndroid` / `mall` / `ogv` / `pink` / `test` / `uper`），其中 `appletBasic`
 * 目录下有 4 个模块；而模块落盘的那份快照只有 `appletBasic（1）` 一条——
 * 因为当时按"整份替换"发布，每个分片都把上一个分片覆盖掉了，
 * 面板里就只剩最后一次请求恰好提到的那个池。
 *
 * 所以这里按池取并集：池名只增不删，模块名去重后计数。
 * 进程重启后的跨场并集由 [MineComponentSnapshotCodec.ACCUMULATING_SURFACES] 接手。
 *
 * 内存有界：池数不超过 [MineComponentSnapshotCodec.MAX_ENTRY_COUNT]，
 * 每池模块名不超过 [MAX_MODULES_PER_POOL]，满了就只丢弃新增、不影响已有计数。
 */
internal class ComponentPoolCatalog {
    private val pools = LinkedHashMap<String, MutableSet<String>>()
    private val floors = HashMap<String, Int>()

    /** 并入一份分片，返回累积至今的全部候选。 */
    @Synchronized fun merge(observed: List<ComponentPoolObservation>): List<MineComponentScanEntry> {
        observed.forEach { observation ->
            val modules = pools.getOrPut(observation.name) {
                if (pools.size >= MineComponentSnapshotCodec.MAX_ENTRY_COUNT) return@forEach
                LinkedHashSet()
            }
            observation.moduleNames.forEach { module ->
                if (modules.size < MAX_MODULES_PER_POOL) modules.add(module)
            }
            // 模块名取不到时退到"单次响应里见过的最大模块数"，好过显示 0。
            floors[observation.name] =
                maxOf(floors[observation.name] ?: 0, observation.moduleCount)
        }
        // 按池名排序：同一组池每次都编码出同样的载荷，下游 LatestValuePublisher
        // 才能按值去重，不会因为 map 迭代顺序变化而空转一次编码 + 落盘。
        return pools.entries.sortedBy { it.key }.mapNotNull { (name, modules) ->
            val count = maxOf(modules.size, floors[name] ?: 0)
            MineComponentScanEntry.create(
                kind = "component_pool",
                title = "$name（$count）",
                id = name,
                uri = null,
                showing = true
            )
        }
    }

    private companion object {
        const val MAX_MODULES_PER_POOL = 512
    }
}

/** 组件库池的保守识别规则；未知/空名称永不命中。 */
internal data class ComponentLibraryMatch(
    val wholePool: Boolean,
    val moduleIndexes: Set<Int> = emptySet()
)

internal object ComponentLibraryPoolMatcher {
    fun match(
        poolName: String?,
        moduleNames: List<String>,
        keywords: Set<String> = DEFAULT_KEYWORDS
    ): ComponentLibraryMatch? {
        val normalizedKeywords = keywords.asSequence()
            .map(::normalize)
            .filterNotNull()
            .toSet()
        if (normalizedKeywords.isEmpty()) return null
        // 全量：清空每一个池的模块列表，不逐个枚举池名。
        // 枚举写法挡不住宿主新增的池——2026-09-15 真机实测当时就有 17 个池，
        // 名单式必然随宿主漂移而漏；而这个功能的语义本来就是"整份不要"。
        if (MATCH_ALL_POOLS in normalizedKeywords) {
            return ComponentLibraryMatch(wholePool = true)
        }
        if (normalize(poolName) in normalizedKeywords) {
            return ComponentLibraryMatch(wholePool = true)
        }
        val moduleIndexes = moduleNames.mapIndexedNotNull { index, name ->
            normalize(name)?.takeIf { it in normalizedKeywords }?.let { index }
        }.toSet()
        return ComponentLibraryMatch(wholePool = false, moduleIndexes = moduleIndexes)
            .takeIf { it.moduleIndexes.isNotEmpty() }
    }

    fun matches(
        poolName: String?,
        moduleNames: List<String>,
        keywords: Set<String> = DEFAULT_KEYWORDS
    ): Boolean = match(poolName, moduleNames, keywords) != null

    private fun normalize(value: String?): String? = value?.trim()
        ?.lowercase(Locale.ROOT)
        ?.takeIf(String::isNotEmpty)

    /**
     * 全量哨兵；出现在关键词集里就命中所有池，见 [match]。
     *
     * 两个入口：手填规则里直接写 `*`；或者扫描没结果时面板给出的「全量禁止」开关，
     * 它写的就是这个值——那种情况下没有候选可勾，只能按"整份都不要"处理。
     */
    const val MATCH_ALL_POOLS = "*"

    /**
     * 勾选（JSON 数组）与手填（逗号/分号/换行分隔）取并集，与其余四个勾选面同规则。
     *
     * 勾选项存的是 `component_pool:<池名>` 形式的 selector 键（见
     * [MineComponentSelector.key]），这里剥掉前缀还原成池名；手填项原样进集合，
     * 所以 `*` 这个哨兵从手填或「全量禁止」开关写进来都认。
     */
    fun selection(selectors: String, rules: String): Set<String> {
        val picked = MineComponentSelectionCodec.decode(selectors)
            .map { it.removePrefix(SELECTOR_PREFIX) }
        val typed = rules.split(',', '，', ';', '；', '\n', '\r')
        return (picked + typed).map(String::trim).filter(String::isNotEmpty).toSet()
    }

    private const val SELECTOR_PREFIX = "component_pool:"

    /**
     * 默认**不拦任何池**——拦谁由用户在勾选面板里决定。
     *
     * 真机取证把口径定死了：哔哩哔哩存储设置里的「App基础组件库」是整棵
     * `app_mod_resource/`，不是某一个池 —— 实测 8549 个文件 / 442.6 MiB，
     * 与 UI 显示的 443 MB 逐字对上（`du` 报 473.8 MiB 是 4K 块对齐，UI 算实际字节）。
     * 当时该设备有 17 个池：`appletBasic`(102MB) / `uper`(97MB) / `live`(74MB,172 模块) /
     * `ogv`(66MB) / `pink`(38MB) / `feOffline`(33MB) / `mall` / `mainSiteAndroid` / …
     *
     * 正因为代价按池而异（拦 `live` 断直播间、拦 `uper` 断创作中心），才做成勾选面板
     * 而不是一个总开关。**空集时只扫描不过滤**，否则面板永远没有候选可勾——
     * 这是四个列表型面共同的纪律，见 AGENTS 勾选面板条目。
     *
     * 另：清单里消失的模块会被宿主当作弃用模块**删除本地文件**（`lib.mod.T#r`，日志
     * `remote config delete abandon mod`，8.84.0–9.13.0 都有），所以拦截不只是"不再下载"。
     */
    val DEFAULT_KEYWORDS: Set<String> = emptySet()
}

/**
 * 安装期解析 protobuf builder；热路径只调用已解析的 Method。
 *
 * ⚠️ **Builder 一律经 [ProtobufBuilderPlan]（`newBuilder(owner)` 静态工厂）取，不要用
 * `toBuilder()`。** 2026-09-15 真机实证：这个功能上线后 `install=SKIPPED,
 * reason=MISSING_HOST_STRUCTURE`，一次都没装上。原因是原实现拿
 * `replyClass.methods` 里的 `toBuilder`——它继承自 `GeneratedMessageLite`，
 * **声明返回类型是基类 `GeneratedMessageLite$Builder`**，于是后面
 * `build().returnType == replyClass` 这条自检必然不成立，整条 resolve 返回 null。
 * 而 `ListReply.newBuilder(ListReply)` 返回的是**具体**的 `ListReply$b`。
 * 这是 AGENTS「内层 Builder 是混淆的，只能经 `newBuilder(...).returnType` 取」
 * 那条红线的另一个入口。
 */
private class ComponentLibraryReplyStrategy private constructor(
    private val replyPlan: ProtobufBuilderPlan,
    private val poolPlan: ProtobufBuilderPlan,
    private val getPools: Method,
    private val clearPools: Method,
    private val addPool: Method,
    private val poolClearModules: Method,
    private val addModule: Method?,
    private val poolName: Method?,
    private val poolModules: Method?,
    private val moduleName: Method?,
    private val targetKeywords: Set<String>
) {
    fun clearMatchedPools(reply: Any): Any? = runCatching {
        if (targetKeywords.isEmpty()) return@runCatching null
        val pools = (getPools.invoke(reply) as? List<*>)?.filterNotNull().orEmpty()
        if (pools.isEmpty()) return@runCatching null
        val rebuiltPools = ArrayList<Any>(pools.size)
        var changed = false
        pools.forEach { pool ->
            val poolText = poolName?.invoke(pool) as? String
            val moduleList = (poolModules?.invoke(pool) as? List<*>)
                .orEmpty()
                .filterNotNull()
            // 匹配器返回的下标是对"传入名单"而言的：名字读不到的模块必须保留原下标
            // （记成 原下标→名字 对），否则过滤后名单的下标套回未过滤的 moduleList
            // 会整体错位，勾掉的模块拦不住、反而删掉别的模块。名字读不到按保守放行。
            val namedModules = moduleList.withIndex().mapNotNull { (index, module) ->
                runCatching { moduleName?.invoke(module) as? String }.getOrNull()
                    ?.let { index to it }
            }
            val match = ComponentLibraryPoolMatcher.match(
                poolText, namedModules.map { it.second }, targetKeywords
            )
            val blockedIndexes = match?.moduleIndexes
                ?.mapTo(HashSet()) { namedModules[it].first }
                .orEmpty()
            val updated = when {
                match == null -> pool
                match.wholePool -> {
                    changed = true
                    rebuildPool(pool, emptyList())
                }
                addModule == null -> return@runCatching null
                else -> {
                    changed = true
                    rebuildPool(pool, moduleList.filterIndexed { index, _ -> index !in blockedIndexes })
                }
            }
            rebuiltPools += updated
        }
        if (!changed) return@runCatching null
        replyPlan.edit(reply) { builder ->
            clearPools.invoke(builder)
            rebuiltPools.forEach { addPool.invoke(builder, it) }
        }
    }.getOrNull()

    private fun rebuildPool(pool: Any, modules: List<Any>): Any = poolPlan.edit(pool) { builder ->
        poolClearModules.invoke(builder)
        if (modules.isNotEmpty()) {
            val addModuleMethod = checkNotNull(addModule)
            modules.forEach { addModuleMethod.invoke(builder, it) }
        }
    }

    /**
     * 读出**这一份**清单里的池。
     *
     * 只取池名、模块名与模块数，不取模块文件路径也不取任何 URL——累积器只需要这些，
     * 也不会把宿主下发的资源清单原样搬进模块存储。
     *
     * ⚠️ 返回的是**分片**而不是全量目录，累积由 [ComponentPoolCatalog] 负责，见那里的注释。
     */
    fun observePools(reply: Any): List<ComponentPoolObservation> = runCatching {
        val pools = (getPools.invoke(reply) as? List<*>)?.filterNotNull().orEmpty()
        pools.mapNotNull { pool ->
            val name = (poolName?.invoke(pool) as? String)?.trim()?.takeIf(String::isNotEmpty)
                ?: return@mapNotNull null
            val moduleList = (poolModules?.invoke(pool) as? List<*>).orEmpty().filterNotNull()
            val moduleNames = moduleList.mapNotNull { module ->
                runCatching { moduleName?.invoke(module) as? String }.getOrNull()
                    ?.trim()?.takeIf(String::isNotEmpty)
            }
            ComponentPoolObservation(name, moduleNames, moduleList.size)
        }
    }.getOrDefault(emptyList())

    fun describe(reply: Any): String? = runCatching {
        val pools = (getPools.invoke(reply) as? List<*>)?.filterNotNull().orEmpty()
        pools.take(8).map { pool ->
            val name = (poolName?.invoke(pool) as? String).orEmpty().take(64)
            val moduleList = (poolModules?.invoke(pool) as? List<*>).orEmpty().filterNotNull()
            val modules = moduleList
                .take(5)
                .mapNotNull { module -> runCatching { moduleName?.invoke(module) as? String }.getOrNull() }
                .map { it.take(64) }
            "$name:${modules.size}/${moduleList.size}[${modules.joinToString(",")}]"
        }.joinToString(" | ")
            .takeIf(String::isNotEmpty)
    }.getOrNull()

    companion object {
        fun resolve(
            replyClass: Class<*>,
            targetKeywords: Set<String>
        ): ComponentLibraryReplyStrategy? = runCatching {
            val replyPlan = ProtobufBuilderPlan.resolve(replyClass) ?: return null
            val getPools = replyClass.methods.firstOrNull { method ->
                method.name == "getPoolsList" && method.parameterCount == 0 &&
                    List::class.java.isAssignableFrom(method.returnType)
            } ?: return null
            val clearPools = replyPlan.method("clearPools") ?: return null
            // 元素类型取**非泛型**的 `getPools(int)` 返回值，不靠 genericReturnType：
            // R8 可能剥掉签名属性，那时泛型实参拿不到，落点会静默降级。
            val poolClass = replyClass.methods.firstOrNull { method ->
                method.name == "getPools" && method.parameterTypes.contentEquals(
                    arrayOf(Int::class.javaPrimitiveType)
                ) && !method.returnType.isPrimitive
            }?.returnType ?: (getPools.genericReturnType as? ParameterizedType)
                ?.actualTypeArguments?.singleOrNull() as? Class<*> ?: return null
            // builder 上 addPools 有 4 个重载（含 int 位置版与 Builder 参数版），
            // 必须按参数类型精确选中"整条 PoolReply"那个。
            val addPool = replyPlan.method("addPools", poolClass) ?: return null
            val poolPlan = ProtobufBuilderPlan.resolve(poolClass) ?: return null
            val poolClearModules = poolPlan.method("clearModules") ?: return null
            val poolName = poolClass.methods.firstOrNull { method ->
                method.name in setOf("getName", "getPoolName") && method.parameterCount == 0 &&
                    method.returnType == String::class.java
            }
            val poolModules = poolClass.methods.firstOrNull { method ->
                method.name == "getModulesList" && method.parameterCount == 0 &&
                    List::class.java.isAssignableFrom(method.returnType)
            }
            val moduleClass = poolClass.methods.firstOrNull { method ->
                method.name == "getModules" && method.parameterTypes.contentEquals(
                    arrayOf(Int::class.javaPrimitiveType)
                ) && !method.returnType.isPrimitive
            }?.returnType ?: (poolModules?.genericReturnType as? ParameterizedType)
                ?.actualTypeArguments?.singleOrNull() as? Class<*>
            val addModule = moduleClass?.let { poolPlan.method("addModules", it) }
            val moduleName = moduleClass?.methods?.firstOrNull { method ->
                method.name in setOf("getName", "getModuleName", "getFilename", "getFileName") &&
                    method.parameterCount == 0 && method.returnType == String::class.java
            }
            ComponentLibraryReplyStrategy(
                replyPlan, poolPlan, getPools, clearPools, addPool,
                poolClearModules, addModule,
                poolName, poolModules, moduleName,
                targetKeywords
            )
        }.getOrNull()
    }
}
