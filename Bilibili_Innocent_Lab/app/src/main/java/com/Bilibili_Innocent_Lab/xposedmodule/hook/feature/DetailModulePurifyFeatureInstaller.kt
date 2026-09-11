package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isSubclassOf
import java.lang.reflect.Method

/**
 * 详细页组件净化：在 `view.v1.ViewMoss` 的同步/异步响应上按名清掉顶层字段。
 *
 * 字段白名单与硬编码依据见 [DetailModulePurifyPolicy]。
 *
 * 边界纪律：
 * - **副本改写**：全部经 [ProtobufBuilderPlan]，改的是 builder，不动原实例；
 * - **先 `has*` 再 `clear*`**，清完还要回读确认——`clear*` 对空字段静默成功，
 *   不能拿"invoke 没抛"当运行证据；
 * - **排除默认实例**：`getDefaultInstance()` 是进程级单例，绝不改写；
 * - **fail-open 到宿主**：任何异常都交付原响应（异步路径由
 *   [MossResponseHandlerProxy.wrapTransform] 再兜一层），不让详情页白屏；
 * - **子项独立降级**：某个字段在将来版本消失，只把该子项标记未覆盖，其余继续工作。
 */
internal class DetailModulePurifyFeatureInstaller(
    private val enabledKeys: Set<String>
) : FeatureInstaller {

    override val id: String = ID

    override val capabilityIds: List<String>
        get() = DetailModulePurifyPolicy.targetsFor(enabledKeys).map { it.capabilityId } +
            if (DetailModulePurifyPolicy.topicTagsEnabled(enabledKeys)) {
                listOf(DetailModulePurifyPolicy.TopicTags.CAPABILITY_ID)
            } else {
                emptyList()
            }

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        val requestedFields = DetailModulePurifyPolicy.targetsFor(enabledKeys)
        val topicTags = DetailModulePurifyPolicy.topicTagsEnabled(enabledKeys)
        if (requestedFields.isEmpty() && !topicTags) {
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        // 面板上每个勾选项都是一个覆盖单位，列表筛选型也算一项。
        val requestedIds = requestedFields.map { it.capabilityId } +
            if (topicTags) listOf(DetailModulePurifyPolicy.TopicTags.CAPABILITY_ID) else emptyList()
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val loader = environment.classLoader ?: return missing(environment, "missing-class-loader")
        val cleaner = DetailModuleReplyCleaner.resolve(loader, requestedFields, topicTags)
            ?: return missing(environment, "not-applicable-host")

        // 解析不到访问器的子项单独记未覆盖，不拖垮其余子项。
        requestedIds.filterNot { it in cleaner.capabilityIds }.forEach { id ->
            environment.reportCapability(id, FeatureInstallResult.Skipped("not-applicable-host"))
            environment.logInfo(
                "detail_module_field_absent",
                "[BIL] 详细页组件字段在本宿主不存在，已跳过该项: $id"
            )
        }

        val mossClass = KavaMemberLookup.classOrNull(loader, DetailModulePurifyPolicy.MOSS_CLASS)
            ?: return missing(environment, "not-applicable-host")
        val requestClass = KavaMemberLookup.classOrNull(loader, DetailModulePurifyPolicy.REQUEST_CLASS)
            ?: return missing(environment, "not-applicable-host")
        val handlerClass = KavaMemberLookup.classOrNull(loader, HANDLER_CLASS)

        var routes = 0
        runCatching {
            environment.registrar.exact(
                "detail_module.sync.${DetailModulePurifyPolicy.SYNC_METHOD}",
                mossClass,
                DetailModulePurifyPolicy.SYNC_METHOD,
                requestClass
            ) {
                after {
                    if (hasThrowable) return@after
                    val original = result ?: return@after
                    environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                    val updated = cleaner.clean(original, environment)
                    if (updated !== original) result = updated
                }
            }
            routes++
        }.onFailure {
            environment.logError("detail_module_sync", "[BIL] 详细页组件同步响应 Hook 注册失败: $it")
        }

        if (handlerClass != null) {
            runCatching {
                environment.registrar.exact(
                    "detail_module.async.${DetailModulePurifyPolicy.ASYNC_METHOD}",
                    mossClass,
                    DetailModulePurifyPolicy.ASYNC_METHOD,
                    requestClass,
                    handlerClass
                ) {
                    before {
                        val original = argOrNull(1) ?: return@before
                        val proxy = MossResponseHandlerProxy.wrapTransform(handlerClass, original) { reply ->
                            environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                            cleaner.clean(reply, environment)
                        } ?: return@before
                        args[1] = proxy
                    }
                }
                routes++
            }.onFailure {
                environment.logError("detail_module_async", "[BIL] 详细页组件异步响应 Hook 注册失败: $it")
            }
        } else {
            environment.logError(
                "detail_module_handler_absent",
                "[BIL] 未找到 MossResponseHandler，详细页组件净化只覆盖同步路径"
            )
        }

        if (routes == 0) return missing(environment, "no-safe-detail-path")

        val covered = requestedIds.filter { it in cleaner.capabilityIds }
        covered.forEach {
            environment.reportCapabilityCoverage(
                it,
                ready = true,
                installedPaths = routes,
                expectedPaths = DetailModulePurifyPolicy.PATHS_PER_TARGET
            )
        }
        val expected = requestedIds.size * DetailModulePurifyPolicy.PATHS_PER_TARGET
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
        environment.logError("detail_module_missing", "[BIL] 详细页组件净化适配不完整: $reason")
        return FeatureInstallResult.Skipped(reason)
    }

    companion object {
        const val ID = "detail_module_purify"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "detail_module_status"
        private const val HANDLER_CLASS = "com.bilibili.lib.moss.api.MossResponseHandler"
    }
}

/**
 * 安装期解析全部反射，回调内只 `invoke`。
 *
 * 与 [PlayerInteractiveReplyCleaner] 同一套做法，但目标是顶层字段，所以不需要
 * carrier/setter 那一层：直接在 `ViewReply` 的 builder 上清。
 */
internal class DetailModuleReplyCleaner private constructor(
    private val reply: Class<*>,
    private val builder: ProtobufBuilderPlan,
    private val fields: List<Field>,
    private val tags: TagFilter?,
    private val defaultInstance: Any?
) {

    internal class Field(val id: String, private val presence: Method, val clear: Method) {
        fun populated(target: Any): Boolean = DetailModulePurifyPolicy.presenceOf(presence, target)
    }

    /**
     * repeated 列表的按特征筛选。
     *
     * 写回方式是 `clear* + addAll*`（builder 上），配合
     * [ProtobufListRetention.retainOrNull]：**全部保留时返回 null**，
     * 调用方据此完全跳过写回——既省一次列表重建，也不会把"本来就没东西可删"
     * 误报成生效。
     */
    internal class TagFilter(
        val id: String,
        private val count: Method,
        private val list: Method,
        private val uri: Method,
        private val clear: Method,
        private val addAll: Method
    ) {
        /** @return 需要写回的保留列表；无命中返回 null。 */
        fun retain(message: Any): List<Any>? {
            if ((count.invoke(message) as? Int ?: 0) <= 0) return null
            val source = list.invoke(message) as? List<*> ?: return null
            return ProtobufListRetention.retainOrNull(source) { !matches(it) }
        }

        fun write(target: Any, retained: List<Any>) {
            clear.invoke(target)
            addAll.invoke(target, retained)
        }

        /** 回读用：改写后列表里不允许再有命中项。 */
        fun remaining(message: Any): Int =
            (list.invoke(message) as? List<*>).orEmpty().count { it != null && matches(it) }

        private fun matches(tag: Any): Boolean =
            DetailModulePurifyPolicy.TopicTags.isTopicTag(uri.invoke(tag) as? String)
    }

    val capabilityIds: Set<String> =
        fields.mapTo(linkedSetOf()) { it.id } + setOfNotNull(tags?.id)

    /** 无命中返回原实例（不分配）；异常保留原响应并记诊断。 */
    fun clean(original: Any, environment: HookEnvironment): Any = runCatching {
        if (!reply.isInstance(original)) return@runCatching original
        // 默认实例是进程级单例，改写它会污染整个宿主进程。
        if (defaultInstance != null && original === defaultInstance) return@runCatching original
        val populated = fields.filter { it.populated(original) }
        // 列表筛选与字段清除**共用同一次副本**：一次响应最多复制一次。
        val retainedTags = tags?.retain(original)
        if (populated.isEmpty() && retainedTags == null) return@runCatching original
        populated.forEach {
            environment.reportRuntimeEvidence(it.id, FeatureRuntimeStage.OBSERVED)
        }
        if (retainedTags != null) {
            environment.reportRuntimeEvidence(tags!!.id, FeatureRuntimeStage.OBSERVED)
        }
        val updated = builder.edit(original) { target ->
            populated.forEach { it.clear.invoke(target) }
            if (retainedTags != null) tags!!.write(target, retainedTags)
        }
        // 回读确认：clear* 对空字段静默成功，不回读就没有"真的清掉了"的证据。
        check(populated.none { it.populated(updated) }) { "Detail module clear readback failed" }
        if (retainedTags != null) {
            check(tags!!.remaining(updated) == 0) { "Detail tag filter readback failed" }
        }
        populated.forEach {
            environment.reportRuntimeEvidence(it.id, FeatureRuntimeStage.APPLIED)
        }
        if (retainedTags != null) {
            environment.reportRuntimeEvidence(tags!!.id, FeatureRuntimeStage.APPLIED)
        }
        environment.reportRuntimeEvidence(
            DetailModulePurifyFeatureInstaller.ID,
            FeatureRuntimeStage.APPLIED
        )
        updated
    }.getOrElse {
        environment.reportRuntimeEvidence(
            DetailModulePurifyFeatureInstaller.ID,
            FeatureRuntimeStage.ERROR
        )
        environment.logError(
            "detail_module_copy_failed",
            "[BIL] 详细页组件副本清理失败，保留原响应: $it"
        )
        original
    }

    companion object {
        fun resolve(
            loader: ClassLoader,
            targets: Collection<DetailModulePurifyPolicy.Target>,
            topicTags: Boolean = false
        ): DetailModuleReplyCleaner? = runCatching {
            val reply = KavaMemberLookup.classOrNull(loader, DetailModulePurifyPolicy.REPLY_CLASS)
                ?: return null
            // Builder 的类名是被混淆的（实测 9.11.0 上是 `ViewReply$b`），
            // 所以只能经 newBuilder(...).returnType 反射拿到，绝不能按名字找。
            val plan = ProtobufBuilderPlan.resolve(reply) ?: return null
            val fields = targets.mapNotNull { target ->
                val presence = KavaMemberLookup.methodOrNull(reply, target.presence)
                    ?.takeIf { it.returnType == classOf<Boolean>() } ?: return@mapNotNull null
                val clear = plan.method(target.clear)
                    ?.takeIf(DetailModulePurifyPolicy::callable) ?: return@mapNotNull null
                Field(target.capabilityId, presence, clear)
            }
            val tagFilter = if (topicTags) resolveTagFilter(loader, reply, plan) else null
            if (fields.isEmpty() && tagFilter == null) return null
            val defaultInstance = runCatching {
                KavaMemberLookup.methodOrNull(reply, "getDefaultInstance")?.invoke(null)
            }.getOrNull()
            DetailModuleReplyCleaner(reply, plan, fields, tagFilter, defaultInstance)
        }.getOrNull()

        /** 任一环节解析不到就返回 null：该子项降级为未覆盖，其余子项照常工作。 */
        private fun resolveTagFilter(
            loader: ClassLoader,
            reply: Class<*>,
            plan: ProtobufBuilderPlan
        ): TagFilter? {
            val spec = DetailModulePurifyPolicy.TopicTags
            val tagClass = KavaMemberLookup.classOrNull(loader, spec.TAG_CLASS) ?: return null
            val count = KavaMemberLookup.methodOrNull(reply, spec.presenceCount)
                ?.takeIf { it.returnType == classOf<Int>() } ?: return null
            val list = KavaMemberLookup.methodOrNull(reply, spec.list)
                ?.takeIf { it.returnType isSubclassOf classOf<List<*>>() } ?: return null
            val uri = KavaMemberLookup.methodOrNull(tagClass, "getUri")
                ?.takeIf { it.returnType == classOf<String>() } ?: return null
            val clear = plan.method(spec.clear)
                ?.takeIf(DetailModulePurifyPolicy::callable) ?: return null
            val addAll = plan.method(spec.addAll, classOf<Iterable<*>>()) ?: return null
            return TagFilter(spec.CAPABILITY_ID, count, list, uri, clear, addAll)
        }
    }
}
