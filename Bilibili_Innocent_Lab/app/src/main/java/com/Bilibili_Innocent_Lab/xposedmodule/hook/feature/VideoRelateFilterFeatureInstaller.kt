package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import java.lang.reflect.Method

/** 按相关推荐卡片的公开枚举/路由类型做精确过滤。 */
internal class VideoRelateFilterFeatureInstaller(
    private val hiddenTypes: Set<String>,
    minDurationSeconds: Int,
    maxDurationSeconds: Int,
    private val matchingEnhancementEnabled: Boolean = false,
    private val reasonFilterEnabled: Boolean = false,
    rawReasonKeywords: String = "",
    private val points: VersionAdapter.VideoRelatePoints?
) : FeatureInstaller {

    private val durationRange = VideoDurationRange(minDurationSeconds, maxDurationSeconds)
    private val customReasonKeywords = if (matchingEnhancementEnabled && reasonFilterEnabled) {
        VideoRelateReasonMatcher.parseCustom(rawReasonKeywords)
    } else {
        emptySet()
    }

    override val id: String = ID

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        val normalizedHidden = hiddenTypes.mapTo(linkedSetOf(), ::normalizeType)
            .filterTo(linkedSetOf()) { it.isNotBlank() }
        val promotionReasonEnhancementActive = matchingEnhancementEnabled &&
            normalizedHidden.any { hidden ->
                HostContentSemanticClassifier.hiddenKind(hidden) in setOf(
                    HostContentKind.ADVERTISEMENT,
                    HostContentKind.SPECIAL
                )
            }
        val reasonFilteringActive = promotionReasonEnhancementActive ||
            customReasonKeywords.isNotEmpty()
        if (durationRange.isConfigured && !durationRange.isValid) {
            environment.logError(
                "video_relate_duration_invalid",
                "[BIL] 推荐视频时长范围无效，已保守放行: " +
                    "min=${durationRange.minSeconds},max=${durationRange.maxSeconds}"
            )
        }
        if (normalizedHidden.isEmpty() && !durationRange.isEnabled && !reasonFilteringActive) {
            val reason = if (durationRange.isConfigured && !durationRange.isValid) {
                "invalid-duration-range"
            } else {
                "disabled"
            }
            environment.reportStatus(CHANNEL_STATUS, reason)
            return FeatureInstallResult.Skipped(reason)
        }
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val adapted = points ?: return missing(environment, "missing-adapter-point")
        val typeEvidence = if (normalizedHidden.isNotEmpty()) {
            buildList {
                adapted.cardCaseGetters.forEachIndexed { index, point ->
                    resolve(environment, "case.$index", point)?.let(::add)
                }
                adapted.gotoGetters.forEachIndexed { index, point ->
                    resolve(environment, "goto.$index", point)?.let(::add)
                }
                adapted.cardTypeGetters.forEachIndexed { index, point ->
                    resolve(environment, "type.$index", point)?.let(::add)
                }
            }
                .distinctBy(Method::toGenericString)
        } else {
            emptyList()
        }
        val relateTypeEvidence = if (normalizedHidden.isNotEmpty()) {
            adapted.relateCardTypeGetters.mapIndexedNotNull { index, point ->
                resolve(environment, "relate_type.$index", point)
            }.distinctBy(Method::toGenericString)
        } else {
            emptyList()
        }
        val sourceTypeEvidence = if (normalizedHidden.isNotEmpty()) {
            adapted.fromSourceTypeGetters.mapIndexedNotNull { index, point ->
                resolve(environment, "source_type.$index", point)
            }.distinctBy(Method::toGenericString)
        } else {
            emptyList()
        }
        val sourceTypeChainEvidence = if (normalizedHidden.isNotEmpty()) {
            adapted.fromSourceTypeChains.mapIndexedNotNull { index, chain ->
                val itemGetter = resolve(
                    environment,
                    "source_type_chain.item.$index",
                    chain.itemGetter
                ) ?: return@mapIndexedNotNull null
                val sourceTypeGetter = resolve(
                    environment,
                    "source_type_chain.value.$index",
                    chain.sourceTypeGetter
                ) ?: return@mapIndexedNotNull null
                itemGetter to sourceTypeGetter
            }.distinctBy { (itemGetter, sourceTypeGetter) ->
                itemGetter.toGenericString() + "->" + sourceTypeGetter.toGenericString()
            }
        } else {
            emptyList()
        }
        val relateTypeValueEvidence = if (normalizedHidden.isNotEmpty()) {
            adapted.relateCardTypeValueGetters.mapIndexedNotNull { index, point ->
                resolve(environment, "relate_type_value.$index", point)
            }.distinctBy(Method::toGenericString)
        } else {
            emptyList()
        }
        val hasTypeEvidence = typeEvidence.isNotEmpty() || relateTypeEvidence.isNotEmpty() ||
            sourceTypeEvidence.isNotEmpty() || sourceTypeChainEvidence.isNotEmpty() ||
            relateTypeValueEvidence.isNotEmpty()
        val durationPaths = resolveDurationPaths(environment, adapted)
        val reasonPaths = resolveReasonPaths(environment, adapted, reasonFilteringActive)
        val partialReasons = mutableListOf<String>()
        if (normalizedHidden.isNotEmpty() && !hasTypeEvidence) {
            if (durationPaths.isEmpty() && reasonPaths.isEmpty()) {
                return missing(environment, "missing-type-getter")
            }
            partialReasons += "missing-type-getter"
            environment.logError(
                "video_relate_type_missing",
                "[BIL] 详情页推荐类型读取适配不完整，时长过滤继续生效"
            )
        }
        if (durationRange.isEnabled && durationPaths.isEmpty()) {
            if (!hasTypeEvidence && reasonPaths.isEmpty()) {
                return missing(environment, "missing-duration-accessor")
            }
            partialReasons += "missing-duration-accessor"
            environment.logError(
                "video_relate_duration_missing",
                "[BIL] 详情页推荐时长读取适配不完整，现有类型过滤继续生效"
            )
        }
        if (reasonFilteringActive && reasonPaths.isEmpty()) {
            if (!hasTypeEvidence && durationPaths.isEmpty()) {
                return missing(environment, "missing-reason-accessor")
            }
            partialReasons += "missing-reason-accessor"
            environment.logError(
                "video_relate_reason_missing",
                "[BIL] 详情页结构化推荐理由读取适配不完整，现有类型/时长过滤继续生效"
            )
        }

        var installed = 0
        adapted.responseItemGetters.forEachIndexed { index, point ->
            runCatching {
                environment.registrar.adapted("video.relate.response.$index", point) {
                    after {
                        val source = result as? List<*> ?: return@after
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                        val filtered = CopyOnFilter.list(source) { item ->
                            val typeMatched = if (normalizedHidden.isNotEmpty()) {
                                val evidence = extractEvidence(
                                    item,
                                    typeEvidence,
                                    relateTypeEvidence,
                                    sourceTypeEvidence,
                                    sourceTypeChainEvidence,
                                    relateTypeValueEvidence
                                )
                                matchesNormalizedEvidence(
                                    evidence.types,
                                    evidence.relateCardTypes,
                                    evidence.fromSourceTypes,
                                    evidence.relateCardTypeValues,
                                    normalizedHidden
                                )
                            } else {
                                false
                            }
                            if (typeMatched) return@list true
                            if (durationRange.shouldRemove(
                                    VideoDurationReader.fromMethods(item, durationPaths)
                                )) return@list true
                            if (reasonPaths.isEmpty()) return@list false
                            val reasons = VideoRelateReasonReader.read(item, reasonPaths)
                            VideoRelateReasonMatcher.matchesCustom(
                                reasons,
                                customReasonKeywords
                            ) || (
                                promotionReasonEnhancementActive &&
                                    VideoRelateReasonMatcher.matchesHighConfidencePromotion(reasons)
                                )
                        }
                        if (filtered !== source) {
                            result = filtered
                            environment.reportRuntimeEvidence(
                                ID,
                                FeatureRuntimeStage.APPLIED,
                                source.size - filtered.size
                            )
                            environment.logInfo(
                                "video_relate_removed",
                                "[BIL] 视频相关推荐过滤已移除 " +
                                    "${source.size - filtered.size} 项"
                            )
                        }
                    }
                }
                installed += 1
            }.onFailure { throwable ->
                environment.logError(
                    "video_relate_$index",
                    "[BIL] 视频相关推荐类型过滤 Hook 注册失败(" +
                        "${point.className}#${point.methodName}): $throwable"
                )
            }
        }
        if (installed == 0) return missing(environment, "registration-failed")
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        environment.reportStatus(
            CHANNEL_STATUS,
            partialReasons.takeIf { it.isNotEmpty() }
                ?.joinToString(prefix = "partial:", separator = "+")
                ?: "success"
        )
        environment.logInfo(
            "video_relate_ok",
            "[BIL] 视频相关推荐过滤已安装，types=$normalizedHidden," +
                "duration=${durationRange.isEnabled}," +
                "reasonEnhancement=$promotionReasonEnhancementActive," +
                "customReasonKeywords=${customReasonKeywords.size}"
        )
        return FeatureInstallResult.Installed(installed)
    }

    private fun resolveDurationPaths(
        environment: HookEnvironment,
        points: VersionAdapter.VideoRelatePoints
    ): List<VideoDurationMethodPath> {
        if (!durationRange.isEnabled) return emptyList()
        val direct = points.directDurationGetters.mapIndexedNotNull { index, point ->
            resolve(environment, "duration.direct.$index", point)
        }
        val chains = points.durationChains.mapIndexedNotNull { index, chain ->
            val itemGetter = resolve(environment, "duration.item.$index", chain.itemGetter)
                ?: return@mapIndexedNotNull null
            val durationGetter = resolve(
                environment,
                "duration.value.$index",
                chain.durationGetter
            ) ?: return@mapIndexedNotNull null
            itemGetter to durationGetter
        }
        return VideoDurationReader.buildMethodPaths(direct, chains)
    }

    private fun resolveReasonPaths(
        environment: HookEnvironment,
        points: VersionAdapter.VideoRelatePoints,
        enabled: Boolean
    ): List<VideoRelateReasonMethodPath> {
        if (!enabled) return emptyList()
        val chains = points.reasonChains.mapIndexedNotNull { chainIndex, chain ->
            val methods = chain.steps.mapIndexed { stepIndex, point ->
                resolve(environment, "reason.$chainIndex.$stepIndex", point)
            }
            if (methods.any { it == null }) return@mapIndexedNotNull null
            methods.filterNotNull()
        }
        return VideoRelateReasonReader.buildMethodPaths(chains)
    }

    private fun extractEvidence(
        item: Any,
        typeMethods: List<Method>,
        relateTypeMethods: List<Method>,
        sourceTypeMethods: List<Method>,
        sourceTypePaths: List<Pair<Method, Method>>,
        relateTypeValueMethods: List<Method>
    ): VideoRelateTypeEvidence {
        val types = extractTypes(item, typeMethods)
        val relateCardTypes = extractTypes(item, relateTypeMethods)
        val fromSourceTypes = buildSet {
            addAll(extractNumbers(item, sourceTypeMethods) { it.toLong() })
            addAll(extractNumbersFromPaths(item, sourceTypePaths) { it.toLong() })
        }
        val relateCardTypeValues = extractNumbers(item, relateTypeValueMethods) { it.toInt() }
        return VideoRelateTypeEvidence(
            types,
            relateCardTypes,
            fromSourceTypes,
            relateCardTypeValues
        )
    }

    private fun extractTypes(item: Any, methods: List<Method>): Set<String> = buildSet {
        methods.forEach { method ->
            if (!method.declaringClass.isInstance(item)) return@forEach
            val raw = runCatching { method.invoke(item) }.getOrNull() ?: return@forEach
            val value = (raw as? Enum<*>)?.name ?: raw.toString()
            val normalized = normalizeType(value)
            if (normalized.isNotBlank() && normalized !in UNKNOWN_TYPES) add(normalized)
        }
    }

    private fun <T> extractNumbers(
        item: Any,
        methods: List<Method>,
        convert: (Number) -> T
    ): Set<T> = buildSet {
        methods.forEach { method ->
            if (!method.declaringClass.isInstance(item)) return@forEach
            val raw = runCatching { method.invoke(item) }.getOrNull() as? Number
                ?: return@forEach
            add(convert(raw))
        }
    }

    private fun <T> extractNumbersFromPaths(
        item: Any,
        paths: List<Pair<Method, Method>>,
        convert: (Number) -> T
    ): Set<T> = buildSet {
        paths.forEach { (itemGetter, valueGetter) ->
            if (!itemGetter.declaringClass.isInstance(item)) return@forEach
            val raw = runCatching {
                val nested = itemGetter.invoke(item) ?: return@runCatching null
                if (!valueGetter.declaringClass.isInstance(nested)) return@runCatching null
                valueGetter.invoke(nested)
            }.getOrNull() as? Number ?: return@forEach
            add(convert(raw))
        }
    }

    private fun resolve(
        environment: HookEnvironment,
        suffix: String,
        point: VersionAdapter.HookPoint
    ): Method? = environment.hookPoints.resolveAdapted(
        "video.relate.resolve.$suffix",
        point.className,
        point.methodName,
        point.paramClassNames
    )

    private fun missing(
        environment: HookEnvironment,
        reason: String
    ): FeatureInstallResult.Skipped {
        environment.reportStatus(CHANNEL_STATUS, reason)
        environment.logError(
            "video_relate_missing",
            "[BIL] 视频相关推荐类型过滤适配不完整: $reason"
        )
        return FeatureInstallResult.Skipped(reason)
    }

    companion object {
        const val ID = "video_relate_filter"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "video_relate_filter_status"
        private val UNKNOWN_TYPES = setOf("UNKNOWN", "CARD_NOT_SET")

        internal fun normalizeType(raw: String): String =
            HostContentSemanticClassifier.normalizedToken(raw).orEmpty()

        internal fun matchesAnyType(types: Set<String>, hiddenTypes: Set<String>): Boolean {
            return matchesEvidence(types, emptySet(), emptySet(), hiddenTypes)
        }

        internal fun matchesEvidence(
            types: Set<String>,
            fromSourceTypes: Set<Long>,
            relateCardTypeValues: Set<Int>,
            hiddenTypes: Set<String>,
            relateCardTypes: Set<String> = emptySet()
        ): Boolean {
            if (hiddenTypes.isEmpty()) return false
            val normalizedHidden = hiddenTypes.mapTo(linkedSetOf(), ::normalizeType)
            return matchesNormalizedEvidence(
                types,
                relateCardTypes,
                fromSourceTypes,
                relateCardTypeValues,
                normalizedHidden
            )
        }

        private fun matchesNormalizedEvidence(
            types: Set<String>,
            relateCardTypes: Set<String>,
            fromSourceTypes: Set<Long>,
            relateCardTypeValues: Set<Int>,
            normalizedHidden: Set<String>
        ): Boolean {
            if (types.any { normalizeType(it) in normalizedHidden } ||
                relateCardTypes.any { normalizeType(it) in normalizedHidden }
            ) return true
            val kinds = buildSet {
                types.forEach { type ->
                    addAll(
                        HostContentSemanticClassifier.classify(
                            HostContentSignals(
                                cardCase = type,
                                cardType = type,
                                goTo = type,
                                relateCardType = type
                            )
                        )
                    )
                }
                relateCardTypes.forEach { type ->
                    addAll(
                        HostContentSemanticClassifier.classify(
                            HostContentSignals(relateCardType = type)
                        )
                    )
                }
                fromSourceTypes.forEach { sourceType ->
                    addAll(
                        HostContentSemanticClassifier.classify(
                            HostContentSignals(fromSourceType = sourceType)
                        )
                    )
                }
                relateCardTypeValues.forEach { typeValue ->
                    addAll(
                        HostContentSemanticClassifier.classify(
                            HostContentSignals(relateCardTypeValue = typeValue)
                        )
                    )
                }
            }
            return normalizedHidden.any { hidden ->
                HostContentSemanticClassifier.hiddenKind(hidden)?.let(kinds::contains) == true
            }
        }

        internal fun shouldRemove(type: String?, hiddenTypes: Set<String>): Boolean =
            type != null && matchesAnyType(setOf(type), hiddenTypes)
    }
}

private data class VideoRelateTypeEvidence(
    val types: Set<String>,
    val relateCardTypes: Set<String>,
    val fromSourceTypes: Set<Long>,
    val relateCardTypeValues: Set<Int>
)
