package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isSubclassOf
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/** 按相关推荐卡片的公开枚举/路由类型做精确过滤。 */
internal class VideoRelateFilterFeatureInstaller(
    private val hiddenTypes: Set<String>,
    minDurationSeconds: Int,
    maxDurationSeconds: Int,
    private val matchingEnhancementEnabled: Boolean = false,
    private val strongModeEnabled: Boolean = false,
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
        val strongModeActive = matchingEnhancementEnabled && strongModeEnabled
        val reasonFilteringActive = promotionReasonEnhancementActive ||
            customReasonKeywords.isNotEmpty() || strongModeActive
        val typeObservationActive = normalizedHidden.isNotEmpty() || strongModeActive
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
        val typeEvidence = if (typeObservationActive) {
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
        val relateTypeEvidence = if (typeObservationActive) {
            adapted.relateCardTypeGetters.mapIndexedNotNull { index, point ->
                resolve(environment, "relate_type.$index", point)
            }.distinctBy(Method::toGenericString)
        } else {
            emptyList()
        }
        val sourceTypeEvidence = if (typeObservationActive) {
            adapted.fromSourceTypeGetters.mapIndexedNotNull { index, point ->
                resolve(environment, "source_type.$index", point)
            }.distinctBy(Method::toGenericString)
        } else {
            emptyList()
        }
        val sourceTypeChainEvidence = if (typeObservationActive) {
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
        val relateTypeValueEvidence = if (typeObservationActive) {
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
        val commercialEvidencePaths = resolveCommercialEvidencePaths(
            environment,
            adapted,
            strongModeActive
        )
        val partialReasons = mutableListOf<String>()
        if (typeObservationActive && !hasTypeEvidence) {
            if (!strongModeActive && durationPaths.isEmpty() && reasonPaths.isEmpty()) {
                return missing(environment, "missing-type-getter")
            }
            partialReasons += "missing-type-getter"
            environment.logError(
                "video_relate_type_missing",
                "[BIL] 详情页推荐类型读取适配不完整，按当前模式继续处理"
            )
        }
        if (durationRange.isEnabled && durationPaths.isEmpty()) {
            if (!strongModeActive && !hasTypeEvidence && reasonPaths.isEmpty()) {
                return missing(environment, "missing-duration-accessor")
            }
            partialReasons += "missing-duration-accessor"
            environment.logError(
                "video_relate_duration_missing",
                "[BIL] 详情页推荐时长读取适配不完整，现有类型过滤继续生效"
            )
        }
        if (reasonFilteringActive && reasonPaths.isEmpty()) {
            if (!strongModeActive && !hasTypeEvidence && durationPaths.isEmpty()) {
                return missing(environment, "missing-reason-accessor")
            }
            partialReasons += "missing-reason-accessor"
            environment.logError(
                "video_relate_reason_missing",
                "[BIL] 详情页结构化推荐理由读取适配不完整，按当前模式继续处理"
            )
        }

        val responseListFields = adapted.responseItemGetters.map { point ->
            resolveResponseListField(environment, point)
        }
        if (responseListFields.any { it == null }) {
            partialReasons += "missing-response-writeback"
            environment.logError(
                "video_relate_response_writeback_missing",
                "[BIL] 部分详情页推荐响应缺少唯一列表字段，返回值过滤仍继续生效"
            )
        }
        val detailServiceAccess = adapted.detailRelateService?.let { point ->
            resolveDetailRelateServiceAccess(environment, point)
        }
        if (detailServiceAccess == null) {
            partialReasons += "missing-service-layer"
            environment.logError(
                "video_relate_service_missing",
                "[BIL] 详情页推荐组件第二层未唯一定位，第一层过滤仍继续生效"
            )
        }

        val shouldRemoveResponseItem: (Any) -> Boolean = filter@{ item ->
            val evidence = if (typeObservationActive) {
                extractEvidence(
                    item,
                    typeEvidence,
                    relateTypeEvidence,
                    sourceTypeEvidence,
                    sourceTypeChainEvidence,
                    relateTypeValueEvidence
                )
            } else {
                null
            }
            val typeMatched = if (normalizedHidden.isNotEmpty() && evidence != null) {
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
            if (typeMatched) return@filter true
            if (durationRange.shouldRemove(
                    VideoDurationReader.fromMethods(item, durationPaths)
                )) return@filter true
            if (strongModeActive && VideoRelateBooleanEvidenceReader
                    .hasPositiveEvidence(item, commercialEvidencePaths)
            ) return@filter true
            if (strongModeActive && (
                    evidence == null || !hasRecognizedTypeEvidence(evidence)
                )
            ) return@filter true
            if (!reasonFilteringActive) return@filter false
            val observation = VideoRelateReasonReader.observe(item, reasonPaths)
            val reasons = observation.reasons
            val regularReasonMatched = VideoRelateReasonMatcher.matchesCustom(
                reasons,
                customReasonKeywords
            ) || (
                promotionReasonEnhancementActive &&
                    VideoRelateReasonMatcher.matchesHighConfidencePromotion(reasons)
                )
            regularReasonMatched || (
                strongModeActive && (
                    !observation.hasUsableReason ||
                        VideoRelateReasonMatcher.matchesStrongModePromotion(reasons) ||
                        VideoRelateReasonMatcher.matchesLikeCount(reasons)
                    )
                )
        }
        val writeBackFailureLogged = AtomicBoolean(false)

        var installed = 0
        adapted.responseItemGetters.forEachIndexed { index, point ->
            val responseListField = responseListFields[index]
            runCatching {
                environment.registrar.adapted("video.relate.response.$index", point) {
                    after {
                        val source = result as? List<*> ?: return@after
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                        val filtered = CopyOnFilter.list(source, shouldRemoveResponseItem)
                        if (filtered !== source) {
                            result = filtered
                            if (responseListField != null && !writeBackVideoRelateItems(
                                    target = thisObject,
                                    field = responseListField,
                                    items = filtered
                                ) && writeBackFailureLogged.compareAndSet(false, true)
                            ) {
                                environment.logError(
                                    "video_relate_response_writeback_failed",
                                    "[BIL] 详情页推荐响应列表写回失败，已保留返回值过滤结果"
                                )
                            }
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

        detailServiceAccess?.let { access ->
            runCatching {
                environment.registrar.exact(
                    "video.relate.service",
                    access.componentFactory.declaringClass,
                    access.componentFactory.name,
                    *access.componentFactory.parameterTypes
                ) {
                    before {
                        val item = args.firstOrNull() ?: return@before
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                        if (!shouldRemoveDetailServiceItem(
                                item = item,
                                access = access,
                                normalizedHidden = normalizedHidden,
                                typeMethods = typeEvidence,
                                relateTypeMethods = relateTypeEvidence,
                                sourceTypeMethods = sourceTypeEvidence,
                                sourceTypePaths = sourceTypeChainEvidence,
                                relateTypeValueMethods = relateTypeValueEvidence,
                                reasonPaths = reasonPaths,
                                commercialEvidencePaths = commercialEvidencePaths,
                                promotionReasonEnhancementActive = promotionReasonEnhancementActive,
                                strongModeActive = strongModeActive
                            )
                        ) return@before
                        result = null
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                        environment.logInfo(
                            "video_relate_service_blocked",
                            "[BIL] 详情页推荐组件第二层已阻止一个匹配条目"
                        )
                    }
                }
                installed += 1
            }.onFailure { throwable ->
                partialReasons += "service-registration-failed"
                environment.logError(
                    "video_relate_service_registration",
                    "[BIL] 详情页推荐组件第二层注册失败，第一层仍继续生效: $throwable"
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
                "strongMode=$strongModeActive," +
                "responseWriteback=${responseListFields.count { it != null }}," +
                "service=${detailServiceAccess != null}," +
                "commercialEvidence=${commercialEvidencePaths.size}," +
                "customReasonKeywords=${customReasonKeywords.size}"
        )
        return FeatureInstallResult.Installed(installed)
    }

    private fun resolveResponseListField(
        environment: HookEnvironment,
        point: VersionAdapter.HookPoint
    ): Field? {
        val fieldName = point.viewField?.takeIf(String::isNotBlank) ?: return null
        val loader = environment.classLoader ?: return null
        val owner = KavaMemberLookup.classOrNull(loader, point.className)
            ?: return null
        return KavaMemberLookup.fields(
            owner,
            includeSuperclasses = true,
            makeAccessible = true
        ) { field -> field.name == fieldName && field.type isSubclassOf classOf<List<*>>() }
            .distinctBy(Field::toGenericString)
            .singleOrNull()
    }

    private fun resolveDetailRelateServiceAccess(
        environment: HookEnvironment,
        point: VersionAdapter.DetailRelateServicePoint
    ): DetailRelateServiceAccess? {
        val componentFactory = resolve(environment, "service.factory", point.componentFactory)
            ?: return null
        if (componentFactory.parameterCount != 1 || componentFactory.returnType.isPrimitive ||
            componentFactory.returnType == Void.TYPE
        ) return null
        val itemClass = componentFactory.parameterTypes.single()
        val typeField = point.typeField?.let { fieldName ->
            KavaMemberLookup.fields(
                itemClass,
                includeSuperclasses = true,
                makeAccessible = true
            ) { field -> field.name == fieldName }
                .distinctBy(Field::toGenericString)
                .singleOrNull()
        }
        val typeGetter = point.typeGetter?.let {
            resolve(environment, "service.type", it)
        }
        val titleGetter = point.titleGetter?.let {
            resolve(environment, "service.title", it)
        }
        return DetailRelateServiceAccess(
            componentFactory = componentFactory,
            typeField = typeField,
            typeGetter = typeGetter,
            titleGetter = titleGetter
        )
    }

    private fun shouldRemoveDetailServiceItem(
        item: Any,
        access: DetailRelateServiceAccess,
        normalizedHidden: Set<String>,
        typeMethods: List<Method>,
        relateTypeMethods: List<Method>,
        sourceTypeMethods: List<Method>,
        sourceTypePaths: List<Pair<Method, Method>>,
        relateTypeValueMethods: List<Method>,
        reasonPaths: List<VideoRelateReasonMethodPath>,
        commercialEvidencePaths: List<VideoRelateBooleanMethodPath>,
        promotionReasonEnhancementActive: Boolean,
        strongModeActive: Boolean
    ): Boolean {
        val baseEvidence = extractEvidence(
            item,
            typeMethods,
            relateTypeMethods,
            sourceTypeMethods,
            sourceTypePaths,
            relateTypeValueMethods
        )
        val serviceTypes = buildSet {
            access.typeGetter?.let { getter ->
                if (getter.declaringClass.isInstance(item)) {
                    runCatching { getter.invoke(item) }.getOrNull()
                        ?.let(::normalizedObservedType)
                        ?.let(::add)
                }
            }
            access.typeField?.let { field ->
                if (field.declaringClass.isInstance(item)) {
                    runCatching { field.get(item) }.getOrNull()
                        ?.let(::normalizedObservedType)
                        ?.let(::add)
                }
            }
        }
        val evidence = baseEvidence.copy(types = baseEvidence.types + serviceTypes)
        if (normalizedHidden.isNotEmpty() && matchesNormalizedEvidence(
                evidence.types,
                evidence.relateCardTypes,
                evidence.fromSourceTypes,
                evidence.relateCardTypeValues,
                normalizedHidden
            )
        ) return true
        if (strongModeActive && VideoRelateBooleanEvidenceReader.hasPositiveEvidence(
                item,
                commercialEvidencePaths
            )
        ) return true
        if (strongModeActive && !hasRecognizedTypeEvidence(evidence)) return true

        val reasonObservation = VideoRelateReasonReader.observe(item, reasonPaths)
        val reasons = reasonObservation.reasons
        if (VideoRelateReasonMatcher.matchesCustom(reasons, customReasonKeywords) ||
            promotionReasonEnhancementActive &&
            VideoRelateReasonMatcher.matchesHighConfidencePromotion(reasons)
        ) return true
        val title = access.titleGetter?.takeIf { it.declaringClass.isInstance(item) }
            ?.let { getter -> runCatching { getter.invoke(item) }.getOrNull() as? CharSequence }
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_SERVICE_TITLE_LENGTH }
        if (promotionReasonEnhancementActive && title != null &&
            VideoRelateReasonMatcher.matchesHighConfidencePromotion(setOf(title))
        ) return true
        return strongModeActive && (
            !reasonObservation.hasUsableReason ||
                VideoRelateReasonMatcher.matchesStrongModePromotion(reasons) ||
                VideoRelateReasonMatcher.matchesLikeCount(reasons) ||
                title != null && (
                    VideoRelateReasonMatcher.matchesStrongModePromotion(setOf(title)) ||
                        VideoRelateReasonMatcher.matchesLikeCount(setOf(title))
                    )
            )
    }

    private fun normalizedObservedType(raw: Any): String? {
        val value = (raw as? Enum<*>)?.name ?: raw.toString()
        return normalizeType(value).takeIf { it.isNotBlank() && it !in UNKNOWN_TYPES }
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

    private fun resolveCommercialEvidencePaths(
        environment: HookEnvironment,
        points: VersionAdapter.VideoRelatePoints,
        enabled: Boolean
    ): List<VideoRelateBooleanMethodPath> {
        if (!enabled) return emptyList()
        val chains = points.commercialEvidenceChains.mapIndexedNotNull { chainIndex, chain ->
            val methods = chain.steps.mapIndexed { stepIndex, point ->
                resolve(environment, "commercial.$chainIndex.$stepIndex", point)
            }
            if (methods.any { it == null }) return@mapIndexedNotNull null
            methods.filterNotNull()
        }
        return VideoRelateBooleanEvidenceReader.buildMethodPaths(chains)
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
        private const val MAX_SERVICE_TITLE_LENGTH = 256
        private val UNKNOWN_TYPES = setOf("UNKNOWN", "CARD_NOT_SET")
        private val KNOWN_CARD_TYPES = setOf(
            "AV",
            "BANGUMI",
            "RESOURCE",
            "GAME",
            "CM",
            "LIVE",
            "BANGUMI_AV",
            "AI_CARD",
            "BANGUMI_UGC",
            "SPECIAL",
            "COURSE",
            "MINI_PROGRAM",
            "HISTORY_AV"
        )
        private val KNOWN_RELATE_TYPE_VALUES = setOf(1, 3, 4, 5, 10)

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

        internal fun hasRecognizedTypeEvidence(evidence: VideoRelateTypeEvidence): Boolean {
            fun tokenRecognized(raw: String): Boolean {
                val token = normalizeType(raw)
                return token in KNOWN_CARD_TYPES || HostContentSemanticClassifier.classify(
                    HostContentSignals(
                        cardCase = token,
                        cardType = token,
                        goTo = token,
                        relateCardType = token
                    )
                ).isNotEmpty()
            }
            if (evidence.types.any(::tokenRecognized) ||
                evidence.relateCardTypes.any(::tokenRecognized)
            ) return true
            if (evidence.fromSourceTypes.any { sourceType ->
                    HostContentSemanticClassifier.classify(
                        HostContentSignals(fromSourceType = sourceType)
                    ).isNotEmpty()
                }
            ) return true
            return evidence.relateCardTypeValues.any { it in KNOWN_RELATE_TYPE_VALUES }
        }

        internal fun shouldRemove(type: String?, hiddenTypes: Set<String>): Boolean =
            type != null && matchesAnyType(setOf(type), hiddenTypes)
    }
}

internal data class DetailRelateServiceAccess(
    val componentFactory: Method,
    val typeField: Field?,
    val typeGetter: Method?,
    val titleGetter: Method?
)

/** 同步响应对象内部列表，避免宿主后续绕过 getter 返回值继续读取原集合。 */
internal fun writeBackVideoRelateItems(
    target: Any?,
    field: Field,
    items: List<*>
): Boolean {
    if (target == null || !field.declaringClass.isInstance(target)) return false
    if (!(items.javaClass isSubclassOf field.type)) return false
    return runCatching {
        field.set(target, items)
        true
    }.getOrDefault(false)
}

internal data class VideoRelateTypeEvidence(
    val types: Set<String>,
    val relateCardTypes: Set<String>,
    val fromSourceTypes: Set<Long>,
    val relateCardTypeValues: Set<Int>
)
