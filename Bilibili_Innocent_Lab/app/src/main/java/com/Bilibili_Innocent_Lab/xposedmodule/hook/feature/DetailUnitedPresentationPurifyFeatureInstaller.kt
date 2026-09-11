package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isStatic
import com.highcapable.kavaref.extension.isSubclassOf
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * United 详情页的展示模型净化。
 *
 * 这不是旧 `view.v1.ViewReply` 的协议改写：当前 `UnitedBizDetailsActivity` 已经把 viewunite
 * 数据转成两个短生命周期的渲染模型。这里在模型进入 View 前精确删除：
 *
 * - 标题 `Headline.label` 中 URI 来源为热搜的角标；
 * - `SpecialTag` 映射出的 topic cell。
 *
 * 两者都按 URI 判别，不按可见文字、span 位置或资源图标猜测。
 */
internal class DetailUnitedPresentationPurifyFeatureInstaller(
    private val hideHotSearchBadge: Boolean,
    private val hideSpecialTopicTags: Boolean
) : FeatureInstaller {

    override val id: String = DetailUnitedPresentationPurifyPolicy.ID

    override val capabilityIds: List<String>
        get() = buildList {
            if (hideHotSearchBadge) add(DetailUnitedPresentationPurifyPolicy.HOT_BADGE_CAPABILITY_ID)
            if (hideSpecialTopicTags) add(DetailUnitedPresentationPurifyPolicy.SPECIAL_TOPIC_CAPABILITY_ID)
        }

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!hideHotSearchBadge && !hideSpecialTopicTags) {
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val loader = environment.classLoader
            ?: return missing(environment, "missing-class-loader")

        var expected = 0
        var installed = 0
        val failures = mutableListOf<String>()

        if (hideHotSearchBadge) {
            expected += 1
            val plan = HeadlineBadgeHookPlan.resolve(loader)
            val registered = plan != null && registerHeadlineBadgeHook(environment, plan)
            environment.reportCapabilityCoverage(
                DetailUnitedPresentationPurifyPolicy.HOT_BADGE_CAPABILITY_ID,
                ready = plan != null,
                installedPaths = if (registered) 1 else 0,
                expectedPaths = 1
            )
            if (registered) installed += 1 else failures += "headline"
        }

        if (hideSpecialTopicTags) {
            expected += 1
            val plan = SpecialTopicTagHookPlan.resolve(loader)
            val registered = plan != null && registerSpecialTopicHook(environment, plan)
            environment.reportCapabilityCoverage(
                DetailUnitedPresentationPurifyPolicy.SPECIAL_TOPIC_CAPABILITY_ID,
                ready = plan != null,
                installedPaths = if (registered) 1 else 0,
                expectedPaths = 1
            )
            if (registered) installed += 1 else failures += "special-tag"
        }

        val status = if (installed == expected) {
            "success"
        } else {
            "partial:$installed/$expected:${failures.joinToString("+")}"
        }
        environment.reportStatus(CHANNEL_STATUS, status)
        if (installed == 0) {
            environment.logError(
                "detail_united_presentation_missing",
                "[BIL] United 详情页展示模型净化未找到安全挂点: $status"
            )
            return FeatureInstallResult.Skipped("not-applicable-host")
        }
        environment.reportRuntimeEvidence(id, FeatureRuntimeStage.ADAPTED)
        environment.logInfo(
            "detail_united_presentation_ok",
            "[BIL] United 详情页展示模型净化已安装($installed/$expected)"
        )
        return FeatureInstallResult.Installed(installed, complete = installed == expected)
    }

    private fun registerHeadlineBadgeHook(
        environment: HookEnvironment,
        plan: HeadlineBadgeHookPlan
    ): Boolean = runCatching {
        environment.registrar.exact(
            "detail_united_presentation.headline_badge",
            plan.method.declaringClass,
            plan.method.name,
            *plan.method.parameterTypes
        ) {
            before {
                val original = args.getOrNull(0) ?: return@before
                val replacement = runCatching { plan.cleaner.removeHotSearchBadge(original) }
                    .onFailure { throwable ->
                        environment.reportRuntimeEvidence(
                            DetailUnitedPresentationPurifyPolicy.HOT_BADGE_CAPABILITY_ID,
                            FeatureRuntimeStage.ERROR
                        )
                        environment.logError(
                            "detail_united_headline_clean",
                            "[BIL] United 标题热搜角标处理失败: $throwable"
                        )
                    }
                    .getOrNull()
                    ?: return@before
                environment.reportRuntimeEvidence(
                    DetailUnitedPresentationPurifyPolicy.HOT_BADGE_CAPABILITY_ID,
                    FeatureRuntimeStage.OBSERVED
                )
                args[0] = replacement
                environment.reportRuntimeEvidence(
                    DetailUnitedPresentationPurifyPolicy.HOT_BADGE_CAPABILITY_ID,
                    FeatureRuntimeStage.APPLIED
                )
                environment.reportRuntimeEvidence(id, FeatureRuntimeStage.APPLIED)
            }
        }
        true
    }.getOrElse { throwable ->
        environment.logError(
            "detail_united_headline_register",
            "[BIL] United 标题热搜角标 Hook 注册失败: $throwable"
        )
        false
    }

    private fun registerSpecialTopicHook(
        environment: HookEnvironment,
        plan: SpecialTopicTagHookPlan
    ): Boolean = runCatching {
        environment.registrar.exact(
            "detail_united_presentation.special_topic",
            plan.method.declaringClass,
            plan.method.name,
            *plan.method.parameterTypes
        ) {
            after {
                if (hasThrowable) return@after
                val original = result ?: return@after
                val replacement = runCatching { plan.cleaner.removeSpecialTopicCells(original) }
                    .onFailure { throwable ->
                        environment.reportRuntimeEvidence(
                            DetailUnitedPresentationPurifyPolicy.SPECIAL_TOPIC_CAPABILITY_ID,
                            FeatureRuntimeStage.ERROR
                        )
                        environment.logError(
                            "detail_united_special_topic_clean",
                            "[BIL] United 特殊话题单元处理失败: $throwable"
                        )
                    }
                    .getOrNull()
                    ?: return@after
                if (replacement === original) return@after
                environment.reportRuntimeEvidence(
                    DetailUnitedPresentationPurifyPolicy.SPECIAL_TOPIC_CAPABILITY_ID,
                    FeatureRuntimeStage.OBSERVED
                )
                result = replacement
                environment.reportRuntimeEvidence(
                    DetailUnitedPresentationPurifyPolicy.SPECIAL_TOPIC_CAPABILITY_ID,
                    FeatureRuntimeStage.APPLIED
                )
                environment.reportRuntimeEvidence(id, FeatureRuntimeStage.APPLIED)
            }
        }
        true
    }.getOrElse { throwable ->
        environment.logError(
            "detail_united_special_topic_register",
            "[BIL] United 特殊话题单元 Hook 注册失败: $throwable"
        )
        false
    }

    private fun missing(
        environment: HookEnvironment,
        reason: String
    ): FeatureInstallResult.Skipped {
        environment.reportStatus(CHANNEL_STATUS, reason)
        environment.logError(
            "detail_united_presentation_missing",
            "[BIL] United 详情页展示模型净化适配不完整: $reason"
        )
        return FeatureInstallResult.Skipped(reason)
    }

    private companion object {
        const val ID = "detail_united_presentation_purify"
        const val TARGET_PACKAGE = "tv.danmaku.bili"
        const val CHANNEL_STATUS = "detail_united_presentation_purify_status"
    }
}

/** 标题模型的安全替换：只在经审计 label 形状里的字符串字段具有精确热搜来源 URI 时构造无 label 副本。 */
internal class HeadlineBadgeModelCleaner private constructor(
    private val modelClass: Class<*>,
    private val content: Field,
    private val label: Field,
    private val labelStrings: List<Field>,
    private val constructor: Constructor<*>
) {

    /**
     * 构造"无 label 副本"时把 label 位传 null。
     *
     * 如果某个宿主把该构造参数声明为 Kotlin 非空类型，`newInstance` 会被
     * intrinsic 空检查拒掉——那不是偶发故障，而是**这个宿主上必然每次都失败**。
     * 所以第一次失败就熄火（[degraded]），否则每渲染一个标题刷一条错误日志。
     * 熄火后回到原样交付，热搜角标这一项不生效，其它层不受影响。
     */
    @Volatile private var degraded = false

    val isDegraded: Boolean get() = degraded

    fun removeHotSearchBadge(model: Any): Any? {
        if (degraded) return null
        if (!modelClass.isInstance(model)) return null
        val labelValue = label.get(model) ?: return null
        val isHotSearch = labelStrings.any { field ->
            // 按类别（跳搜索）判，不按某个 from 值——「热搜」「活动」及未来变体同覆盖。
            DetailUnitedPresentationPurifyPolicy.isSearchJumpLabelUri(field.get(labelValue) as? String)
        }
        if (!isHotSearch) return null
        val title = content.get(model) as? String ?: return null
        return try {
            constructor.newInstance(title, null)
        } catch (throwable: Throwable) {
            degraded = true
            throw throwable
        }
    }

    companion object {
        fun resolve(modelClass: Class<*>): HeadlineBadgeModelCleaner? = runCatching {
            val fields = KavaMemberLookup.declaredFields(modelClass, makeAccessible = true) {
                !it.isStatic
            }
            // 只接受当前已核实的 HeadlineData = String + label 形状；未来字段扩张时 fail-open。
            if (fields.size != 2) return null
            val contentFields = fields.filter { it.type == classOf<String>() }
            val labelFields = fields.filter { field ->
                !field.type.isPrimitive && field.type != classOf<String>()
            }
            val candidates = contentFields.flatMap { content ->
                labelFields.mapNotNull { label ->
                    val constructor = KavaMemberLookup.declaredConstructors(
                        modelClass,
                        makeAccessible = true
                    ) { candidate ->
                        candidate.parameterTypes.contentEquals(
                            arrayOf(classOf<String>(), label.type)
                        )
                    }.singleOrNull() ?: return@mapNotNull null
                    val labelFields = KavaMemberLookup.declaredFields(label.type, makeAccessible = true) {
                        !it.isStatic
                    }
                    // 五个已审计宿主的 Label 均为 int + 5 String + 2 long + Map；
                    // 任意扩张或类型漂移都拒绝，以免把未来的非标题 label 当作热搜角标。
                    if (labelFields.size != 9 ||
                        labelFields.count { it.type == classOf<Int>() } != 1 ||
                        labelFields.count { it.type == classOf<Long>() } != 2 ||
                        labelFields.count { it.type isSubclassOf classOf<Map<*, *>>() } != 1
                    ) return@mapNotNull null
                    val uriFields = labelFields.filter {
                        it.type == classOf<String>()
                    }
                    uriFields.takeIf { it.size == 5 }?.let {
                        HeadlineBadgeModelCleaner(modelClass, content, label, it, constructor)
                    }
                }
            }.distinctBy { it.content.name to it.label.name }
            candidates.singleOrNull()
        }.getOrNull()
    }
}

/** SpecialTag 映射结果的安全过滤器；无命中时返回同一实例，避免无意义的模型重建。 */
internal class SpecialTopicTagsModelCleaner private constructor(
    private val modelClass: Class<*>,
    private val cells: Field,
    private val refresh: Field,
    private val constructor: Constructor<*>
) {

    private val cellStringFields = ConcurrentHashMap<Class<*>, List<Field>>()

    fun removeSpecialTopicCells(model: Any): Any {
        if (!modelClass.isInstance(model)) return model
        val source = cells.get(model) as? List<*> ?: return model
        // 正常视频没有话题单元：先扫描，确认命中前不分配过滤列表。
        val firstTopicIndex = source.indexOfFirst(::isSpecialTopicCell)
        if (firstTopicIndex < 0) return model
        val retained = ArrayList<Any?>(source.size - 1)
        for (index in 0 until firstTopicIndex) retained += source[index]
        for (index in (firstTopicIndex + 1) until source.size) {
            if (!isSpecialTopicCell(source[index])) retained += source[index]
        }
        // 空列表必须同时关闭 refresh；否则 TagsService 仍可能创建一个没有 cell 的容器。
        val nextRefresh = if (retained.isEmpty()) false else refresh.getBoolean(model)
        return constructor.newInstance(retained, nextRefresh)
    }

    private fun isSpecialTopicCell(cell: Any?): Boolean {
        if (cell == null) return false
        val fields = cellStringFields.computeIfAbsent(cell.javaClass) { cellClass ->
            KavaMemberLookup.declaredFields(cellClass, makeAccessible = true) { field ->
                !field.isStatic && field.type == classOf<String>()
            }
        }
        return fields.any { field ->
            DetailUnitedPresentationPurifyPolicy.isSpecialTopicUri(field.get(cell) as? String)
        }
    }

    companion object {
        fun resolve(modelClass: Class<*>): SpecialTopicTagsModelCleaner? = runCatching {
            val fields = KavaMemberLookup.declaredFields(modelClass, makeAccessible = true) {
                !it.isStatic
            }
            // TagsData 只有 cells + refresh 两个状态；多出字段时不能靠不完整副本猜测。
            if (fields.size != 2) return null
            val cells = fields.filter { it.type isSubclassOf classOf<List<*>>() }.singleOrNull()
                ?: return null
            val refresh = fields.filter { it.type == classOf<Boolean>() }.singleOrNull()
                ?: return null
            val constructor = KavaMemberLookup.declaredConstructors(modelClass, makeAccessible = true) {
                it.parameterTypes.contentEquals(arrayOf(cells.type, refresh.type))
            }.singleOrNull() ?: return null
            SpecialTopicTagsModelCleaner(modelClass, cells, refresh, constructor)
        }.getOrNull()
    }
}

private data class HeadlineBadgeHookPlan(
    val method: Method,
    val cleaner: HeadlineBadgeModelCleaner
) {
    companion object {
        /**
         * 8.84.0 / 8.89.0 / 9.1.0 / 9.7.0 / 9.11.0 样本都有 UgcHeadlineService，
         * 但入口方法名随版本漂移（r/q/a）。因此只按参数模型形状选择，不写死方法名。
         */
        fun resolve(classLoader: ClassLoader): HeadlineBadgeHookPlan? = runCatching {
            val service = KavaMemberLookup.classOrNull(
                classLoader,
                DetailUnitedPresentationPurifyPolicy.HEADLINE_SERVICE_CLASS
            ) ?: return null
            val runningUiComponent = KavaMemberLookup.classOrNull(
                classLoader,
                DetailUnitedPresentationPurifyPolicy.RUNNING_UI_COMPONENT_CLASS
            ) ?: return null
            KavaMemberLookup.declaredMethods(service, makeAccessible = true) { method ->
                !method.isStatic &&
                    method.returnType == runningUiComponent &&
                    method.parameterTypes.size == 1
            }.mapNotNull { method ->
                HeadlineBadgeModelCleaner.resolve(method.parameterTypes.single())?.let { cleaner ->
                    HeadlineBadgeHookPlan(method, cleaner)
                }
            }.distinctBy { it.method.toGenericString() }.singleOrNull()
        }.getOrNull()
    }
}

private data class SpecialTopicTagHookPlan(
    val method: Method,
    val cleaner: SpecialTopicTagsModelCleaner
) {
    companion object {
        /**
         * 8.84.0 / 8.89.0 的 `tags.f#c(SpecialTag)` 与 9.11.0 的
         * `tags.j#a(SpecialTag)` 都已核对为 TagsData 映射。9.1.0 没有这两个形状，9.7.0
         * 同名 `j` 是另一种 ViewTagInfo 模型；所有不匹配形状一律降级，不能用泛
         * RecyclerView/文字规则冒充兼容。
         */
        fun resolve(classLoader: ClassLoader): SpecialTopicTagHookPlan? = runCatching {
            val specialTag = KavaMemberLookup.classOrNull(
                classLoader,
                DetailUnitedPresentationPurifyPolicy.SPECIAL_TAG_CLASS
            ) ?: return null
            val candidates = DetailUnitedPresentationPurifyPolicy.specialTagMapperClasses.flatMap { className ->
                val mapper = KavaMemberLookup.classOrNull(classLoader, className)
                    ?: return@flatMap emptyList()
                KavaMemberLookup.declaredMethods(mapper, makeAccessible = true) { method ->
                    method.isStatic && method.parameterTypes.contentEquals(arrayOf(specialTag))
                }.mapNotNull { method ->
                    SpecialTopicTagsModelCleaner.resolve(method.returnType)?.let { cleaner ->
                        SpecialTopicTagHookPlan(method, cleaner)
                    }
                }
            }
            candidates.distinctBy { it.method.toGenericString() }.singleOrNull()
        }.getOrNull()
    }
}
