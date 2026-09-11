package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isStatic
import com.highcapable.kavaref.extension.isSubclassOf
import java.lang.reflect.Method

/**
 * United 详情页协议层净化：在 `viewunite.v1.ViewMoss` 的响应上按 `ModuleType` 删模块。
 *
 * 判据、链路与"为什么这才是那五项的正确落点"见 [DetailUnitedModulePurifyPolicy]。
 *
 * 边界纪律（与 [DetailModulePurifyFeatureInstaller] 同一套）：
 * - **副本改写**：五层消息全部经 [ProtobufBuilderPlan]，改 builder，不动原实例；
 * - **按需复制**：某一层没有命中就完全不复制那一层，一次响应最多每层复制一次；
 * - **排除默认实例**：`getDefaultInstance()` 是进程级单例，绝不改写；
 * - **改完回读**：`clear*` 对空字段静默成功，不回读就没有"真的删掉了"的证据；
 * - **fail-open 到宿主**：任何异常都交付原响应，不让详情页白屏；
 * - **两条链独立降级**：模块链与 `owner.vip` 链各自解析，一条挂了不拖累另一条。
 */
internal class DetailUnitedModulePurifyFeatureInstaller(
    private val enabledKeys: Set<String>,
    /**
     * 视频提及（游戏推广卡）的协议层总闸。单独一个参数而不是混进 [enabledKeys]：
     * 它沿用的游戏卡开关**默认开**，而 [enabledKeys] 那条路径按"默认关"过滤，
     * 混进去会把老用户的默认行为从"开"改成"关"。
     */
    private val hideVideoMentions: Boolean = false,
    /** 「UP 主分享好物」商品卡。同样沿用默认开的老开关，理由见 [hideVideoMentions]。 */
    private val hideMerchandise: Boolean = false
) : FeatureInstaller {

    override val id: String = DetailUnitedModulePurifyPolicy.ID

    override val capabilityIds: List<String>
        get() = DetailUnitedModulePurifyPolicy.capabilityIdsFor(
            enabledKeys, hideVideoMentions, hideMerchandise
        )

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        val requestedIds = capabilityIds
        if (requestedIds.isEmpty()) {
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val loader = environment.classLoader ?: return missing(environment, "missing-class-loader")
        val moduleTargets = DetailUnitedModulePurifyPolicy.moduleTargetsFor(enabledKeys) +
            listOfNotNull(
                DetailUnitedModulePurifyPolicy.VideoMentions.TARGET.takeIf { hideVideoMentions },
                DetailUnitedModulePurifyPolicy.Merchandise.TARGET.takeIf { hideMerchandise }
            )
        val cleaner = UnitedModuleReplyCleaner.resolve(
            loader,
            moduleTargets,
            DetailUnitedModulePurifyPolicy.upVipLabelEnabled(enabledKeys),
            DetailUnitedModulePurifyPolicy.hotBannerEnabled(enabledKeys)
        ) { url ->
            // 只打一次。留给下一次真机确认横条 url 的判据该不该放宽。
            environment.logInfo(
                "detail_united_guidance_bar_url",
                "[BIL] 引导条类型命中但 URL 不是热搜跳转，未改动: $url"
            )
        } ?: return missing(environment, "not-applicable-host")

        // 解析不到的子项单独记未覆盖，不拖垮其余子项。
        requestedIds.filterNot { it in cleaner.capabilityIds }.forEach { capability ->
            environment.reportCapability(capability, FeatureInstallResult.Skipped("not-applicable-host"))
            environment.logInfo(
                "detail_united_module_absent",
                "[BIL] United 详情页模块判据在本宿主不可用，已跳过该项: $capability"
            )
        }

        val mossClass = KavaMemberLookup.classOrNull(loader, DetailUnitedModulePurifyPolicy.MOSS_CLASS)
            ?: return missing(environment, "not-applicable-host")
        val requestClass = KavaMemberLookup.classOrNull(loader, DetailUnitedModulePurifyPolicy.REQUEST_CLASS)
            ?: return missing(environment, "not-applicable-host")
        val handlerClass = KavaMemberLookup.classOrNull(loader, HANDLER_CLASS)

        var routes = 0
        runCatching {
            environment.registrar.exact(
                "detail_united_module.sync.${DetailUnitedModulePurifyPolicy.SYNC_METHOD}",
                mossClass,
                DetailUnitedModulePurifyPolicy.SYNC_METHOD,
                requestClass
            ) {
                after {
                    if (hasThrowable) return@after
                    val original = result ?: return@after
                    environment.reportRuntimeEvidence(
                        DetailUnitedModulePurifyPolicy.ID, FeatureRuntimeStage.OBSERVED
                    )
                    val updated = cleaner.clean(original, environment)
                    if (updated !== original) result = updated
                }
            }
            routes++
        }.onFailure {
            environment.logError(
                "detail_united_module_sync",
                "[BIL] United 详情页模块同步响应 Hook 注册失败: $it"
            )
        }

        if (handlerClass != null) {
            runCatching {
                environment.registrar.exact(
                    "detail_united_module.async.${DetailUnitedModulePurifyPolicy.ASYNC_METHOD}",
                    mossClass,
                    DetailUnitedModulePurifyPolicy.ASYNC_METHOD,
                    requestClass,
                    handlerClass
                ) {
                    before {
                        val original = argOrNull(1) ?: return@before
                        val proxy = MossResponseHandlerProxy.wrapTransform(handlerClass, original) { reply ->
                            environment.reportRuntimeEvidence(
                                DetailUnitedModulePurifyPolicy.ID, FeatureRuntimeStage.OBSERVED
                            )
                            cleaner.clean(reply, environment)
                        } ?: return@before
                        args[1] = proxy
                    }
                }
                routes++
            }.onFailure {
                environment.logError(
                    "detail_united_module_async",
                    "[BIL] United 详情页模块异步响应 Hook 注册失败: $it"
                )
            }
        } else {
            environment.logError(
                "detail_united_module_handler_absent",
                "[BIL] 未找到 MossResponseHandler，United 详情页模块净化只覆盖同步路径"
            )
        }

        if (routes == 0) return missing(environment, "no-safe-united-path")

        val covered = requestedIds.filter { it in cleaner.capabilityIds }
        covered.forEach {
            environment.reportCapabilityCoverage(
                it,
                ready = true,
                installedPaths = routes,
                expectedPaths = DetailUnitedModulePurifyPolicy.PATHS_PER_TARGET
            )
        }
        val expected = requestedIds.size * DetailUnitedModulePurifyPolicy.PATHS_PER_TARGET
        val installed = covered.size * routes
        val complete = installed == expected && expected > 0
        environment.reportStatus(
            CHANNEL_STATUS,
            if (complete) "success" else "partial:$installed/$expected"
        )
        return FeatureInstallResult.Installed(routes, complete = complete)
    }

    private fun missing(
        environment: HookEnvironment,
        reason: String
    ): FeatureInstallResult.Skipped {
        environment.reportStatus(CHANNEL_STATUS, reason)
        environment.logError(
            "detail_united_module_missing",
            "[BIL] United 详情页模块净化适配不完整: $reason"
        )
        return FeatureInstallResult.Skipped(reason)
    }

    private companion object {
        const val TARGET_PACKAGE = "tv.danmaku.bili"
        const val CHANNEL_STATUS = "detail_united_module_status"
        const val HANDLER_CLASS = "com.bilibili.lib.moss.api.MossResponseHandler"
    }
}

/**
 * `IntroductionTab.modules` 的按类型删除链。
 *
 * 五层里只有**真的有命中**的那几层才会复制：没命中的 tabModule 原样留在列表里，
 * 一个 tabModule 都没命中就连 `Tab` 都不复制。
 */
internal class UnitedModuleChain(
    private val moduleClass: Class<*>,
    private val byType: Map<Int, DetailUnitedModulePurifyPolicy.ModuleTarget>,
    private val typeValue: Method,
    private val tabPresence: Method,
    private val tabGetter: Method,
    private val tabSetter: Method,
    private val tabPlan: ProtobufBuilderPlan,
    private val tabModuleList: Method,
    private val tabModuleClear: Method,
    private val tabModuleAddAll: Method,
    private val tabModulePlan: ProtobufBuilderPlan,
    private val introPresence: Method,
    private val introGetter: Method,
    private val introSetter: Method,
    private val introPlan: ProtobufBuilderPlan,
    private val modulesList: Method,
    private val modulesClear: Method,
    private val modulesAddAll: Method,
    private val guidanceBar: UnitedGuidanceBarMatch? = null
) {

    val capabilityIds: Set<String> = byType.values.mapTo(linkedSetOf()) { it.capabilityId } +
        setOfNotNull(guidanceBar?.target?.capabilityId)

    /**
     * @param hits 命中的子项 capability id，由调用方用来只给真的删掉的项记运行证据。
     * @return 需要写回的新 `Tab`；没有任何命中时返回 null（调用方据此完全跳过复制）。
     */
    fun rebuildTab(reply: Any, hits: MutableSet<String>): Any? {
        if (present(tabPresence, reply) != true) return null
        val tab = tabGetter.invoke(reply) ?: return null
        // protobuf 列表不会含 null；真出现就整条放弃，绝不用错位的下标去写回。
        val tabModules = (tabModuleList.invoke(tab) as? List<*>)?.map { it ?: return null } ?: return null
        var rebuilt: MutableList<Any>? = null
        tabModules.forEachIndexed { index, tabModule ->
            if (present(introPresence, tabModule) != true) return@forEachIndexed
            val intro = introGetter.invoke(tabModule) ?: return@forEachIndexed
            val modules = modulesList.invoke(intro) as? List<*> ?: return@forEachIndexed
            val retained = ProtobufListRetention.retainOrNull(modules) { module ->
                val target = targetOf(module)
                if (target == null) {
                    true
                } else {
                    hits += target.capabilityId
                    false
                }
            } ?: return@forEachIndexed
            val newIntro = introPlan.edit(intro) { target ->
                modulesClear.invoke(target)
                modulesAddAll.invoke(target, retained)
            }
            val newTabModule = tabModulePlan.edit(tabModule) { target ->
                introSetter.invoke(target, newIntro)
            }
            val list = rebuilt ?: tabModules.toMutableList().also { rebuilt = it }
            list[index] = newTabModule
        }
        val list = rebuilt ?: return null
        return tabPlan.edit(tab) { target ->
            tabModuleClear.invoke(target)
            tabModuleAddAll.invoke(target, list)
        }
    }

    fun writeTab(builder: Any, tab: Any) {
        tabSetter.invoke(builder, tab)
    }

    /** 回读用：改写后任何 introduction tab 里都不允许再有命中类型的模块。 */
    fun remaining(reply: Any): Int {
        if (present(tabPresence, reply) != true) return 0
        val tab = tabGetter.invoke(reply) ?: return 0
        val tabModules = tabModuleList.invoke(tab) as? List<*> ?: return 0
        var count = 0
        tabModules.forEach { tabModule ->
            if (tabModule == null || present(introPresence, tabModule) != true) return@forEach
            val intro = introGetter.invoke(tabModule) ?: return@forEach
            val modules = modulesList.invoke(intro) as? List<*> ?: return@forEach
            count += modules.count { it != null && targetOf(it) != null }
        }
        return count
    }

    private fun targetOf(module: Any): DetailUnitedModulePurifyPolicy.ModuleTarget? {
        if (!moduleClass.isInstance(module)) return null
        val type = typeValue.invoke(module) as? Int ?: return null
        byType[type]?.let { return it }
        // 条件型子项：类型对上还不够，载荷里的判据也要成立（见 HotBannerGuidanceBar）。
        val conditional = guidanceBar ?: return null
        if (conditional.typeValue != type) return null
        return if (conditional.matches(module)) conditional.target else null
    }

    private fun present(method: Method, message: Any): Boolean? =
        method.invoke(message) as? Boolean
}

/**
 * 热搜横条的条件判据：类型是 `ACTIVITY_GUIDANCE_BAR` **且** `url` 是热搜跳转。
 *
 * 为什么不能只看类型、以及为什么这一层还没有真机证据，见
 * [DetailUnitedModulePurifyPolicy.HotBannerGuidanceBar]。
 *
 * [unmatchedUrl] 只记**第一条**没匹配上的引导条 URL：下一次真机一眼就能确认
 * 判据该不该放宽，同时不会每条响应都刷日志。
 */
internal class UnitedGuidanceBarMatch(
    val typeValue: Int,
    val target: DetailUnitedModulePurifyPolicy.ModuleTarget,
    private val presence: Method,
    private val getter: Method,
    private val url: Method,
    private val onUnmatched: (String) -> Unit
) {

    @Volatile private var reportedUnmatched = false

    fun matches(module: Any): Boolean {
        if (presence.invoke(module) as? Boolean != true) return false
        val bar = getter.invoke(module) ?: return false
        val value = url.invoke(bar) as? String
        if (DetailUnitedPresentationPurifyPolicy.isSearchJumpLabelUri(value)) return true
        if (!reportedUnmatched) {
            reportedUnmatched = true
            onUnmatched(value.orEmpty())
        }
        return false
    }
}

/** `ViewReply.owner.vip` 的清除链。UP 会员标不是模块，所以单独一条。 */
internal class UnitedVipChain(
    private val ownerPresence: Method,
    private val ownerGetter: Method,
    private val ownerSetter: Method,
    private val ownerPlan: ProtobufBuilderPlan,
    private val vipPresence: Method,
    private val vipClear: Method
) {

    /** @return 需要写回的新 `Owner`；本来就没有会员标时返回 null。 */
    fun rebuildOwner(reply: Any): Any? {
        if (!populated(reply)) return null
        val owner = ownerGetter.invoke(reply) ?: return null
        return ownerPlan.edit(owner) { target -> vipClear.invoke(target) }
    }

    fun writeOwner(builder: Any, owner: Any) {
        ownerSetter.invoke(builder, owner)
    }

    fun populated(reply: Any): Boolean {
        if (ownerPresence.invoke(reply) as? Boolean != true) return false
        val owner = ownerGetter.invoke(reply) ?: return false
        return vipPresence.invoke(owner) as? Boolean == true
    }
}

/** 安装期解析全部反射，回调内只 `invoke`。 */
internal class UnitedModuleReplyCleaner private constructor(
    private val reply: Class<*>,
    private val replyPlan: ProtobufBuilderPlan,
    private val modules: UnitedModuleChain?,
    private val vip: UnitedVipChain?,
    private val defaultInstance: Any?
) {

    val capabilityIds: Set<String> = (modules?.capabilityIds ?: emptySet()) +
        if (vip != null) setOf(DetailUnitedModulePurifyPolicy.UpVipLabel.CAPABILITY_ID) else emptySet()

    /** 无命中返回原实例（不分配）；异常保留原响应并记诊断。 */
    fun clean(original: Any, environment: HookEnvironment): Any = runCatching {
        if (!reply.isInstance(original)) return@runCatching original
        // 默认实例是进程级单例，改写它会污染整个宿主进程。
        if (defaultInstance != null && original === defaultInstance) return@runCatching original
        val hits = linkedSetOf<String>()
        // 把"要写回的新子消息"和"负责写它的那条链"绑在一起，省掉一串 !!。
        val tabWrite = modules?.let { chain -> chain.rebuildTab(original, hits)?.let { chain to it } }
        val ownerWrite = vip?.let { chain -> chain.rebuildOwner(original)?.let { chain to it } }
        if (tabWrite == null && ownerWrite == null) return@runCatching original
        if (ownerWrite != null) hits += DetailUnitedModulePurifyPolicy.UpVipLabel.CAPABILITY_ID
        hits.forEach { environment.reportRuntimeEvidence(it, FeatureRuntimeStage.OBSERVED) }
        val updated = replyPlan.edit(original) { target ->
            tabWrite?.let { (chain, tab) -> chain.writeTab(target, tab) }
            ownerWrite?.let { (chain, owner) -> chain.writeOwner(target, owner) }
        }
        // 回读确认：clear*/addAll* 对空输入静默成功，不回读就没有"真的删掉了"的证据。
        if (tabWrite != null) {
            check(tabWrite.first.remaining(updated) == 0) { "United module removal readback failed" }
        }
        if (ownerWrite != null) {
            check(!ownerWrite.first.populated(updated)) { "United vip clear readback failed" }
        }
        hits.forEach { environment.reportRuntimeEvidence(it, FeatureRuntimeStage.APPLIED) }
        environment.reportRuntimeEvidence(
            DetailUnitedModulePurifyPolicy.ID, FeatureRuntimeStage.APPLIED
        )
        updated
    }.getOrElse {
        environment.reportRuntimeEvidence(
            DetailUnitedModulePurifyPolicy.ID, FeatureRuntimeStage.ERROR
        )
        environment.logError(
            "detail_united_module_copy_failed",
            "[BIL] United 详情页模块副本清理失败，保留原响应: $it"
        )
        original
    }

    companion object {
        fun resolve(
            loader: ClassLoader,
            targets: Collection<DetailUnitedModulePurifyPolicy.ModuleTarget>,
            upVipLabel: Boolean,
            hotBanner: Boolean = false,
            onUnmatchedGuidanceBar: (String) -> Unit = {}
        ): UnitedModuleReplyCleaner? = runCatching {
            val policy = DetailUnitedModulePurifyPolicy
            val reply = KavaMemberLookup.classOrNull(loader, policy.REPLY_CLASS) ?: return null
            // Builder 类名被混淆（实测 `ViewReply$b`），只能经 newBuilder(...).returnType 拿到。
            val replyPlan = ProtobufBuilderPlan.resolve(reply) ?: return null
            val modules = if (targets.isEmpty() && !hotBanner) {
                null
            } else {
                resolveModuleChain(loader, reply, replyPlan, targets, hotBanner, onUnmatchedGuidanceBar)
            }
            val vip = if (upVipLabel) resolveVipChain(loader, reply, replyPlan) else null
            if (modules == null && vip == null) return null
            val defaultInstance = runCatching {
                KavaMemberLookup.methodOrNull(reply, "getDefaultInstance")?.invoke(null)
            }.getOrNull()
            UnitedModuleReplyCleaner(reply, replyPlan, modules, vip, defaultInstance)
        }.getOrNull()

        /** 任一环节缺失就返回 null：整条模块链降级，`owner.vip` 那条不受影响。 */
        private fun resolveModuleChain(
            loader: ClassLoader,
            reply: Class<*>,
            replyPlan: ProtobufBuilderPlan,
            targets: Collection<DetailUnitedModulePurifyPolicy.ModuleTarget>,
            hotBanner: Boolean,
            onUnmatchedGuidanceBar: (String) -> Unit
        ): UnitedModuleChain? {
            val policy = DetailUnitedModulePurifyPolicy
            val tabClass = KavaMemberLookup.classOrNull(loader, policy.TAB_CLASS) ?: return null
            val tabModuleClass = KavaMemberLookup.classOrNull(loader, policy.TAB_MODULE_CLASS)
                ?: return null
            val introClass = KavaMemberLookup.classOrNull(loader, policy.INTRODUCTION_TAB_CLASS)
                ?: return null
            val moduleClass = KavaMemberLookup.classOrNull(loader, policy.MODULE_CLASS) ?: return null
            val moduleTypeClass = KavaMemberLookup.classOrNull(loader, policy.MODULE_TYPE_CLASS)
                ?: return null

            // ModuleType 上的 static final int 常量；缺的子项直接不进表，其余照常。
            val byType = LinkedHashMap<Int, DetailUnitedModulePurifyPolicy.ModuleTarget>()
            targets.forEach { target ->
                val field = KavaMemberLookup.fieldOrNull(moduleTypeClass, target.typeConstant)
                    ?.takeIf { it.isStatic && it.type == classOf<Int>() } ?: return@forEach
                val value = runCatching { field.getInt(null) }.getOrNull() ?: return@forEach
                // 两个子项映射到同一个枚举值就整条放弃：宁可不生效，也不要删错模块。
                if (byType.put(value, target) != null) return null
            }
            val guidance = if (hotBanner) {
                resolveGuidanceBar(loader, moduleClass, moduleTypeClass, onUnmatchedGuidanceBar)
                    // 条件判据与无条件表撞到同一个枚举值就不装它：宁可这一层不生效，
                    // 也不要让"无条件删"覆盖掉"按 URL 才删"。
                    ?.takeIf { it.typeValue !in byType }
            } else {
                null
            }
            if (byType.isEmpty() && guidance == null) return null

            val typeValue = KavaMemberLookup.methodOrNull(moduleClass, policy.MODULE_TYPE_VALUE)
                ?.takeIf { it.returnType == classOf<Int>() } ?: return null
            val tabPresence = boolMethod(reply, policy.TAB_PRESENCE) ?: return null
            val tabGetter = KavaMemberLookup.methodOrNull(reply, policy.TAB_GETTER)
                ?.takeIf { it.returnType == tabClass } ?: return null
            val tabSetter = replyPlan.method(policy.TAB_SETTER, tabClass) ?: return null
            val tabPlan = ProtobufBuilderPlan.resolve(tabClass) ?: return null
            val tabModuleList = listMethod(tabClass, policy.TAB_MODULE_LIST) ?: return null
            val tabModuleClear = tabPlan.method(policy.TAB_MODULE_CLEAR) ?: return null
            val tabModuleAddAll = tabPlan.method(policy.TAB_MODULE_ADD_ALL, classOf<Iterable<*>>())
                ?: return null
            val tabModulePlan = ProtobufBuilderPlan.resolve(tabModuleClass) ?: return null
            val introPresence = boolMethod(tabModuleClass, policy.INTRODUCTION_PRESENCE) ?: return null
            val introGetter = KavaMemberLookup.methodOrNull(tabModuleClass, policy.INTRODUCTION_GETTER)
                ?.takeIf { it.returnType == introClass } ?: return null
            val introSetter = tabModulePlan.method(policy.INTRODUCTION_SETTER, introClass) ?: return null
            val introPlan = ProtobufBuilderPlan.resolve(introClass) ?: return null
            val modulesList = listMethod(introClass, policy.MODULES_LIST) ?: return null
            val modulesClear = introPlan.method(policy.MODULES_CLEAR) ?: return null
            val modulesAddAll = introPlan.method(policy.MODULES_ADD_ALL, classOf<Iterable<*>>())
                ?: return null

            return UnitedModuleChain(
                moduleClass = moduleClass,
                byType = byType,
                typeValue = typeValue,
                tabPresence = tabPresence,
                tabGetter = tabGetter,
                tabSetter = tabSetter,
                tabPlan = tabPlan,
                tabModuleList = tabModuleList,
                tabModuleClear = tabModuleClear,
                tabModuleAddAll = tabModuleAddAll,
                tabModulePlan = tabModulePlan,
                introPresence = introPresence,
                introGetter = introGetter,
                introSetter = introSetter,
                introPlan = introPlan,
                modulesList = modulesList,
                modulesClear = modulesClear,
                modulesAddAll = modulesAddAll,
                guidanceBar = guidance
            )
        }

        /** 缺任一环节就返回 null：热搜横条这一层降级，其余子项照常。 */
        private fun resolveGuidanceBar(
            loader: ClassLoader,
            moduleClass: Class<*>,
            moduleTypeClass: Class<*>,
            onUnmatched: (String) -> Unit
        ): UnitedGuidanceBarMatch? {
            val spec = DetailUnitedModulePurifyPolicy.HotBannerGuidanceBar
            val payloadClass = KavaMemberLookup.classOrNull(loader, spec.PAYLOAD_CLASS) ?: return null
            val field = KavaMemberLookup.fieldOrNull(moduleTypeClass, spec.TYPE_CONSTANT)
                ?.takeIf { it.isStatic && it.type == classOf<Int>() } ?: return null
            val typeValue = runCatching { field.getInt(null) }.getOrNull() ?: return null
            val presence = boolMethod(moduleClass, spec.PAYLOAD_PRESENCE) ?: return null
            val getter = KavaMemberLookup.methodOrNull(moduleClass, spec.PAYLOAD_GETTER)
                ?.takeIf { it.returnType == payloadClass } ?: return null
            val url = KavaMemberLookup.methodOrNull(payloadClass, spec.URL_GETTER)
                ?.takeIf { it.returnType == classOf<String>() } ?: return null
            return UnitedGuidanceBarMatch(typeValue, spec.TARGET, presence, getter, url, onUnmatched)
        }

        private fun resolveVipChain(
            loader: ClassLoader,
            reply: Class<*>,
            replyPlan: ProtobufBuilderPlan
        ): UnitedVipChain? {
            val spec = DetailUnitedModulePurifyPolicy.UpVipLabel
            val ownerClass = KavaMemberLookup.classOrNull(
                loader, DetailUnitedModulePurifyPolicy.OWNER_CLASS
            ) ?: return null
            val ownerPresence = boolMethod(reply, spec.OWNER_PRESENCE) ?: return null
            val ownerGetter = KavaMemberLookup.methodOrNull(reply, spec.OWNER_GETTER)
                ?.takeIf { it.returnType == ownerClass } ?: return null
            val ownerSetter = replyPlan.method(spec.OWNER_SETTER, ownerClass) ?: return null
            val ownerPlan = ProtobufBuilderPlan.resolve(ownerClass) ?: return null
            val vipPresence = boolMethod(ownerClass, spec.VIP_PRESENCE) ?: return null
            val vipClear = ownerPlan.method(spec.VIP_CLEAR) ?: return null
            return UnitedVipChain(
                ownerPresence, ownerGetter, ownerSetter, ownerPlan, vipPresence, vipClear
            )
        }

        private fun boolMethod(owner: Class<*>, name: String): Method? =
            KavaMemberLookup.methodOrNull(owner, name)
                ?.takeIf { it.returnType == classOf<Boolean>() }

        private fun listMethod(owner: Class<*>, name: String): Method? =
            KavaMemberLookup.methodOrNull(owner, name)
                ?.takeIf { it.returnType isSubclassOf classOf<List<*>>() }
    }
}
