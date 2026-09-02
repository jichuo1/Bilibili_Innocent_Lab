package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** 全局规范化具有明确视频身份的 Story 路由，首页卡片改写仅作为前置优化。 */
internal class HomeVerticalDetailFeatureInstaller(
    private val enabled: Boolean,
    private val points: VersionAdapter.HomeRecommendFeedPoints?
) : FeatureInstaller {

    override val id: String = ID

    private val routeMutator = ConcreteHomeVerticalRouteMutator()

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) {
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val routeHooks = installRouteNormalizer(environment)
        val responseHooks = runCatching { installOptionalCardLayer(environment) }
            .getOrElse { throwable ->
                environment.logError(
                    "home_vertical_card_layer_failed",
                    "[BIL] 首页卡片前置改写安装异常，全局路由规范化不受影响: $throwable"
                )
                0
            }
        val installed = routeHooks.total + responseHooks
        if (installed == 0) return missing(environment, "registration-failed")

        val status = if (routeHooks.finalizerCount == EXPECTED_ROUTE_FINALIZER_HOOKS) {
            "success"
        } else {
            "partial:route-finalizer=${routeHooks.finalizerCount}/$EXPECTED_ROUTE_FINALIZER_HOOKS"
        }
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        environment.reportStatus(CHANNEL_STATUS, status)
        environment.logInfo(
            "home_vertical_ok",
            "[BIL] 竖屏视频普通详情路由已安装，routeBuilder=${routeHooks.builderCount}," +
                "routeFinalizer=${routeHooks.finalizerCount},playConfig=${routeHooks.playConfigCount}," +
                "instrumentation=${routeHooks.instrumentationCount}," +
                "intentFallback=${routeHooks.intentCount},cardResponse=$responseHooks,status=$status"
        )
        return FeatureInstallResult.Installed(installed)
    }

    private fun installOptionalCardLayer(environment: HookEnvironment): Int {
        val adapted = points ?: run {
            environment.logInfo(
                "home_vertical_card_layer_unavailable",
                "[BIL] 首页卡片前置改写适配点不可用，全局路由规范化不受影响"
            )
            return 0
        }
        val playerArgs = resolveOptional(environment, "player_args", adapted.playerArgsGetter)
        val accessors = ReadAccessors(
            holderType = resolve(environment, "holder_type", adapted.holderTypeGetter)
                ?: return cardLayerUnavailable(environment, "missing-holder-getter"),
            bizType = resolveOptional(environment, "biz_type", adapted.bizTypeGetter),
            adInfo = resolveOptional(environment, "ad_info", adapted.adInfoGetter),
            cardType = resolveOptional(environment, "card_type", adapted.cardTypeGetter),
            cardGoto = resolveOptional(environment, "card_goto", adapted.cardGotoGetter),
            goTo = resolveOptional(environment, "goto", adapted.goToGetter),
            uri = adapted.uriGetter?.let { resolve(environment, "uri", it) }
                ?: return cardLayerUnavailable(environment, "missing-uri-getter"),
            param = resolveOptional(environment, "param", adapted.paramGetter),
            playerArgs = playerArgs,
            playerFields = playerArgs?.returnType?.let(::resolvePlayerFields)
        )

        var responseHooks = 0
        adapted.responseItemGetters.forEachIndexed { index, point ->
            runCatching {
                environment.registrar.adapted("home.vertical.response.$index", point) {
                    after {
                        val items = result as? List<*> ?: return@after
                        handleItems(items, accessors, environment)
                    }
                }
                responseHooks += 1
            }.onFailure { throwable ->
                environment.logError(
                    "home_vertical_response_$index",
                    "[BIL] 首页竖屏详情响应 Hook 注册失败(" +
                        "${point.className}#${point.methodName}): $throwable"
                )
            }
        }
        return responseHooks
    }

    private fun handleItems(
        items: List<*>,
        accessors: ReadAccessors,
        environment: HookEnvironment
    ) {
        var observed = 0
        var eligible = 0
        var applied = 0
        var unsafe = 0
        var noAccessor = 0
        var rolledBack = 0
        var rollbackIncomplete = 0
        val reasonCounts = linkedMapOf<HomeVerticalRouteDecision.Reason, Int>()
        items.forEach { item ->
            if (item == null || !accessors.holderType.declaringClass.isInstance(item)) return@forEach
            val snapshot = snapshot(item, accessors)
            when (val decision = HomeVerticalDetailRoutePolicy.decide(snapshot)) {
                HomeVerticalRouteDecision.NotVertical -> Unit
                is HomeVerticalRouteDecision.KeepOriginal -> {
                    observed += 1
                    unsafe += 1
                    reasonCounts[decision.reason] = (reasonCounts[decision.reason] ?: 0) + 1
                }
                is HomeVerticalRouteDecision.Rewrite -> {
                    observed += 1
                    eligible += 1
                    when (
                        routeMutator.apply(
                            item,
                            snapshot,
                            decision.plan,
                            HomeVerticalReadAccessors(
                                cardGoto = accessors.cardGoto,
                                goTo = accessors.goTo,
                                uri = accessors.uri
                            )
                        )
                    ) {
                        HomeVerticalMutationResult.APPLIED -> applied += 1
                        HomeVerticalMutationResult.NO_SAFE_ACCESSOR -> noAccessor += 1
                        HomeVerticalMutationResult.ROLLED_BACK -> rolledBack += 1
                        HomeVerticalMutationResult.ROLLBACK_INCOMPLETE -> rollbackIncomplete += 1
                    }
                }
            }
        }
        if (observed == 0) return
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED, observed)
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED, applied)
        val reasonSummary = reasonCounts.entries
            .sortedBy { it.key.ordinal }
            .joinToString("|") { "${it.key.name.lowercase()}=${it.value}" }
            .ifEmpty { "none" }
        environment.logInfo(
            "home_vertical_runtime",
            "[BIL] 首页竖屏详情处理 observed=$observed,eligible=$eligible,applied=$applied," +
                "unsafe=$unsafe,noAccessor=$noAccessor,rolledBack=$rolledBack," +
                "reasons=$reasonSummary"
        )
        if (rollbackIncomplete > 0) {
            environment.logError(
                "home_vertical_rollback_incomplete",
                "[BIL] 首页竖屏详情存在 $rollbackIncomplete 项未能完整回滚，已停止继续修改对应卡片"
            )
        }
    }

    private fun snapshot(item: Any, accessors: ReadAccessors): HomeVerticalRouteSnapshot {
        val playerArgs = invokeCompatible(accessors.playerArgs, item)
        val playerFields = accessors.playerFields
        return HomeVerticalRouteSnapshot(
            holderType = invokeString(accessors.holderType, item),
            bizType = invokeString(accessors.bizType, item),
            cardType = invokeString(accessors.cardType, item),
            cardGoto = invokeString(accessors.cardGoto, item),
            goTo = invokeString(accessors.goTo, item),
            uri = invokeString(accessors.uri, item),
            param = invokeString(accessors.param, item),
            playerAid = readPositiveLong(playerFields?.aid, playerArgs),
            playerNonUgc = playerArgs != null && playerFields != null && (
                readPositiveLong(playerFields.isLive, playerArgs) != null ||
                    readPositiveLong(playerFields.roomId, playerArgs) != null ||
                    readPositiveLong(playerFields.epid, playerArgs) != null ||
                    readPositiveLong(playerFields.seasonId, playerArgs) != null
                ),
            hasAdInfo = invokeCompatible(accessors.adInfo, item) != null
        )
    }

    private fun resolvePlayerFields(type: Class<*>): PlayerFields {
        val numericFields = KavaMemberLookup.fields(
            type,
            includeSuperclasses = true,
            makeAccessible = true
        ) { field ->
            !Modifier.isStatic(field.modifiers) && field.type in NUMERIC_FIELD_TYPES
        }.distinctBy(Field::toGenericString)
        fun field(vararg serializedNames: String): Field? {
            val names = serializedNames.toSet()
            val annotated = numericFields.filter { it.serializedNameValue() in names }
            if (annotated.size == 1) return annotated.single()
            val named = numericFields.filter { it.name in names }
            return named.singleOrNull()
        }
        return PlayerFields(
            aid = field("aid"),
            isLive = field("is_live", "isLive"),
            roomId = field("room_id", "roomId"),
            epid = field("epid", "ep_id"),
            seasonId = field("pgc_season_id", "season_id", "pgcSeasonId")
        )
    }

    private fun readPositiveLong(field: Field?, target: Any?): Long? {
        if (field == null || target == null || !field.declaringClass.isInstance(target)) return null
        return runCatching { (field.get(target) as? Number)?.toLong()?.takeIf { it > 0L } }
            .getOrNull()
    }

    private fun Field.serializedNameValue(): String? =
        declaredAnnotations.firstNotNullOfOrNull { annotation ->
            val annotationType = annotation.annotationClass.java
            val attribute = when (annotationType.name) {
                GSON_SERIALIZED_NAME -> "value"
                FASTJSON_JSON_FIELD -> "name"
                else -> null
            } ?: return@firstNotNullOfOrNull null
            runCatching { annotationType.getMethod(attribute).invoke(annotation) as? String }.getOrNull()
        }

    private fun installRouteNormalizer(environment: HookEnvironment): RouteHookInstallCount {
        var builderCount = 0
        var finalizerCount = 0
        var playConfigCount = 0
        var instrumentationCount = 0
        var intentCount = 0
        val stringConstructor = environment.hookPoints.resolveConstructor(
            "home.vertical.route.string.resolve",
            ROUTE_REQUEST_BUILDER_CLASS,
            listOf(String::class.java.name)
        )
        if (stringConstructor != null) {
            runCatching {
                environment.registrar.constructor(
                    "home.vertical.route.string",
                    stringConstructor
                ) {
                    before {
                        val original = args.getOrNull(0) as? String ?: return@before
                        val rewritten = normalizeRouteSafely(original, environment) ?: return@before
                        args[0] = rewritten
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                        environment.logInfo(
                            "home_vertical_route_normalized",
                            "[BIL] Story 视频已在字符串路由构造阶段改为普通详情页"
                        )
                    }
                }
                builderCount += 1
            }.onFailure { throwable ->
                environment.logError(
                    "home_vertical_route_string_failed",
                    "[BIL] 首页竖屏字符串路由兜底注册失败: $throwable"
                )
            }
        }

        val uriConstructor = environment.hookPoints.resolveConstructor(
            "home.vertical.route.uri.resolve",
            ROUTE_REQUEST_BUILDER_CLASS,
            listOf(Uri::class.java.name)
        )
        if (uriConstructor != null) {
            runCatching {
                environment.registrar.constructor("home.vertical.route.uri", uriConstructor) {
                    before {
                        val original = args.getOrNull(0) as? Uri ?: return@before
                        val rewritten = normalizeRouteSafely(original.toString(), environment)
                            ?: return@before
                        args[0] = Uri.parse(rewritten)
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                        environment.logInfo(
                            "home_vertical_route_normalized",
                            "[BIL] Story 视频已在 URI 路由构造阶段改为普通详情页"
                        )
                    }
                }
                builderCount += 1
            }.onFailure { throwable ->
                environment.logError(
                    "home_vertical_route_uri_failed",
                    "[BIL] 首页竖屏 URI 路由兜底注册失败: $throwable"
                )
            }
        }

        finalizerCount = installRouteRequestFinalizer(environment)
        playConfigCount = installPlayConfigStorySuppression(environment)
        instrumentationCount = installInstrumentationFallback(environment)

        points?.intentHandlerOnCreate?.let { point ->
            runCatching {
                environment.registrar.adapted("home.vertical.intent_handler", point) {
                    before {
                        val activity = instance as? Activity ?: return@before
                        val intent = activity.intent ?: return@before
                        val rewritten = rewriteIntentSafely(
                            intent,
                            environment,
                            retargetComponent = false
                        ) ?: return@before
                        activity.intent = rewritten
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                        environment.logInfo(
                            "home_vertical_intent_handler_fallback",
                            "[BIL] Story 视频已在宿主 Intent 入口改为普通详情页"
                        )
                    }
                }
                intentCount += 1
            }.onFailure { throwable ->
                environment.logError(
                    "home_vertical_intent_handler_failed",
                    "[BIL] 首页竖屏宿主 Intent 入口兜底注册失败: $throwable"
                )
            }
        }
        return RouteHookInstallCount(
            builderCount = builderCount,
            finalizerCount = finalizerCount,
            playConfigCount = playConfigCount,
            instrumentationCount = instrumentationCount,
            intentCount = intentCount
        )
    }

    /**
     * Builder.build()、RouteRequest(Uri)、newBuilder().build() 最终都会经过该构造器；在宿主
     * 路由请求冻结前改写 Builder，覆盖创建后又被拦截器改回 Story 的链路。
     */
    private fun installRouteRequestFinalizer(environment: HookEnvironment): Int {
        val routeRequestClass = environment.hookPoints.resolveClass(
            "home.vertical.route.finalizer.request",
            ROUTE_REQUEST_CLASS
        ) ?: return 0
        val builderClass = environment.hookPoints.resolveClass(
            "home.vertical.route.finalizer.builder",
            ROUTE_REQUEST_BUILDER_CLASS
        ) ?: return 0
        val constructor = environment.hookPoints.resolveConstructor(
            "home.vertical.route.finalizer.constructor",
            ROUTE_REQUEST_CLASS,
            listOf(ROUTE_REQUEST_BUILDER_CLASS)
        ) ?: return 0
        val getTargetUri = KavaMemberLookup.methodOrNull(builderClass, "getTargetUri")
            ?.takeIf { it.returnType == Uri::class.java && !Modifier.isStatic(it.modifiers) }
            ?: return 0
        val setTargetUri = KavaMemberLookup.methodOrNull(builderClass, "setTargetUri", Uri::class.java)
            ?.takeIf { !Modifier.isStatic(it.modifiers) }
            ?: return 0
        if (constructor.declaringClass != routeRequestClass) return 0
        return runCatching {
            environment.registrar.constructor("home.vertical.route.finalizer", constructor) {
                before {
                    val builder = args.getOrNull(0)
                        ?.takeIf(builderClass::isInstance) ?: return@before
                    val original = runCatching { getTargetUri.invoke(builder) as? Uri }.getOrNull()
                        ?: return@before
                    val rewritten = normalizeRouteSafely(original.toString(), environment)
                        ?: return@before
                    val applied = runCatching {
                        setTargetUri.invoke(builder, Uri.parse(rewritten))
                    }.isSuccess
                    if (!applied) return@before
                    environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                    environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                    environment.logInfo(
                        "home_vertical_route_normalized",
                        "[BIL] Story 视频已在路由请求冻结阶段改为普通详情页"
                    )
                }
            }
            1
        }.getOrElse { throwable ->
            environment.logError(
                "home_vertical_route_finalizer_failed",
                "[BIL] 路由请求最终封口注册失败，已保留其他兜底层: $throwable"
            )
            0
        }
    }

    /** 禁止宿主把横屏普通播放自动提升为 Story，不改变用户主动进入 Story 页的其它开关。 */
    private fun installPlayConfigStorySuppression(environment: HookEnvironment): Int {
        val loader = environment.classLoader ?: return 0
        val boolValueClass = KavaMemberLookup.classOrNull(loader, BOOL_VALUE_CLASS) ?: return 0
        val playConfigClass = KavaMemberLookup.classOrNull(loader, PLAY_CONFIG_CLASS) ?: return 0
        val defaultMethod = KavaMemberLookup.methodOrNull(boolValueClass, "getDefaultInstance")
            ?.takeIf { Modifier.isStatic(it.modifiers) && it.returnType == boolValueClass }
            ?: return 0
        val defaultValue = runCatching { defaultMethod.invoke(null) }
            .getOrNull()
            ?.takeIf(boolValueClass::isInstance)
            ?: return 0
        var installed = 0
        PLAY_CONFIG_STORY_GETTERS.forEach { methodName ->
            val method = KavaMemberLookup.methodOrNull(playConfigClass, methodName)
                ?.takeIf {
                    !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 &&
                        it.returnType == boolValueClass
                } ?: return@forEach
            runCatching {
                environment.registrar.exact(
                    "home.vertical.play_config.$methodName",
                    method.declaringClass,
                    method.name
                ) {
                    before {
                        result = defaultValue
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                    }
                }
                installed += 1
            }.onFailure { throwable ->
                environment.logError(
                    "home_vertical_play_config_$methodName",
                    "[BIL] PlayConfig 自动 Story 抑制注册失败($methodName): $throwable"
                )
            }
        }
        return installed
    }

    /**
     * 最终启动边界兜底。只枚举名称精确为 execStartActivity 且仅含一个 Intent 参数的系统
     * 签名；不扫描宿主对象图，也不接管非 bilibili Story 路由。
     */
    private fun installInstrumentationFallback(environment: HookEnvironment): Int {
        val intentHandlerAvailable = environment.classLoader?.let { loader ->
            KavaMemberLookup.classOrNull(loader, INTENT_HANDLER_ACTIVITY_CLASS)
                ?.let { owner -> Activity::class.java.isAssignableFrom(owner) }
        } == true
        val candidates = KavaMemberLookup.declaredMethods(
            Instrumentation::class.java,
            makeAccessible = true
        ) { method ->
            method.name == "execStartActivity" && !Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.count { it == Intent::class.java } == 1
        }.distinctBy(Method::toGenericString)
        var installed = 0
        candidates.forEachIndexed { index, method ->
            val intentIndex = method.parameterTypes.indexOf(Intent::class.java)
            if (intentIndex < 0) return@forEachIndexed
            runCatching {
                environment.registrar.exact(
                    "home.vertical.instrumentation.$index",
                    method.declaringClass,
                    method.name,
                    *method.parameterTypes
                ) {
                    before {
                        val intent = args.getOrNull(intentIndex) as? Intent ?: return@before
                        val rewritten = rewriteIntentSafely(
                            intent,
                            environment,
                            retargetComponent = intentHandlerAvailable
                        ) ?: return@before
                        args[intentIndex] = rewritten
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                        environment.logInfo(
                            "home_vertical_instrumentation_fallback",
                            "[BIL] Story 视频已在 Activity 启动边界改为普通详情页"
                        )
                    }
                }
                installed += 1
            }.onFailure { throwable ->
                environment.logError(
                    "home_vertical_instrumentation_$index",
                    "[BIL] Activity 启动兜底注册失败(${method.parameterCount} 参数): $throwable"
                )
            }
        }
        return installed
    }

    private fun normalizeRouteSafely(uri: String, environment: HookEnvironment): String? =
        runCatching { HomeVerticalDetailRoutePolicy.normalizeVideoDetailUri(uri) }
            .getOrElse { throwable ->
                environment.logError(
                    "home_vertical_route_normalize_failed",
                    "[BIL] Story 视频路由规范化异常，已保留宿主原路由: $throwable"
                )
                null
            }

    private fun rewriteIntentSafely(
        intent: Intent,
        environment: HookEnvironment,
        retargetComponent: Boolean
    ): Intent? = runCatching {
        val component = intent.component
        val plan = HomeVerticalDetailRoutePolicy.planIntentFallback(
            HomeVerticalIntentRouteSnapshot(
                dataUri = intent.data?.toString(),
                componentPackage = component?.packageName,
                componentClass = component?.className,
                targetPackage = intent.`package`,
                aid = intent.extraToken("aid"),
                avid = intent.extraToken("avid"),
                bvid = intent.extraToken("bvid")
            )
        ) ?: return@runCatching null
        val rewritten = Intent(intent)
        rewritten.data = Uri.parse(plan.detailUri)
        val targetUrl = intent.getStringExtra(BLROUTER_TARGET_URL_EXTRA)
        if (targetUrl != null && STORY_ROUTE_PREFIXES.any {
                targetUrl.startsWith(it, ignoreCase = true)
            }
        ) {
            rewritten.putExtra(BLROUTER_TARGET_URL_EXTRA, plan.detailUri)
        }
        if (retargetComponent && plan.retargetToIntentHandler) {
            rewritten.component = ComponentName(TARGET_PACKAGE, INTENT_HANDLER_ACTIVITY_CLASS)
        }
        rewritten
    }.getOrElse { throwable ->
        environment.logError(
            "home_vertical_intent_rewrite_failed",
            "[BIL] Story 启动 Intent 规范化异常，已保留宿主原 Intent: $throwable"
        )
        null
    }

    @Suppress("DEPRECATION")
    private fun Intent.extraToken(key: String): String? = runCatching {
        when (val value = extras?.get(key)) {
            is Number -> value.toLong().takeIf { it > 0L }?.toString()
            is String -> value.trim().takeIf(String::isNotEmpty)
            else -> null
        }
    }.getOrNull()

    private fun cardLayerUnavailable(environment: HookEnvironment, reason: String): Int {
        environment.logError(
            "home_vertical_card_layer_unavailable",
            "[BIL] 首页卡片前置改写不可用($reason)，全局路由规范化不受影响"
        )
        return 0
    }

    private fun resolveOptional(
        environment: HookEnvironment,
        suffix: String,
        point: VersionAdapter.HookPoint?
    ): Method? = point?.let { resolve(environment, suffix, it) }

    private fun resolve(
        environment: HookEnvironment,
        suffix: String,
        point: VersionAdapter.HookPoint
    ): Method? = environment.hookPoints.resolveAdapted(
        "home.vertical.resolve.$suffix",
        point.className,
        point.methodName,
        point.paramClassNames
    )

    private fun invokeString(method: Method?, target: Any): String? =
        invokeCompatible(method, target)?.toString()

    private fun invokeCompatible(method: Method?, target: Any): Any? {
        if (method == null || !method.declaringClass.isInstance(target)) return null
        return runCatching { method.invoke(target) }.getOrNull()
    }

    private fun missing(
        environment: HookEnvironment,
        reason: String
    ): FeatureInstallResult.Skipped {
        environment.reportStatus(CHANNEL_STATUS, reason)
        environment.logError(
            "home_vertical_missing",
            "[BIL] 首页竖屏视频详情路由适配不完整: $reason"
        )
        return FeatureInstallResult.Skipped(reason)
    }

    private data class ReadAccessors(
        val holderType: Method,
        val bizType: Method?,
        val adInfo: Method?,
        val cardType: Method?,
        val cardGoto: Method?,
        val goTo: Method?,
        val uri: Method,
        val param: Method?,
        val playerArgs: Method?,
        val playerFields: PlayerFields?
    )

    private data class PlayerFields(
        val aid: Field?,
        val isLive: Field?,
        val roomId: Field?,
        val epid: Field?,
        val seasonId: Field?
    )

    private data class RouteHookInstallCount(
        val builderCount: Int,
        val finalizerCount: Int,
        val playConfigCount: Int,
        val instrumentationCount: Int,
        val intentCount: Int
    ) {
        val total: Int
            get() = builderCount + finalizerCount + playConfigCount + instrumentationCount +
                intentCount
    }

    companion object {
        const val ID = "home_vertical_detail"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "home_vertical_detail_status"
        private const val EXPECTED_ROUTE_FINALIZER_HOOKS = 1
        private const val ROUTE_REQUEST_CLASS = "com.bilibili.lib.blrouter.RouteRequest"
        private const val ROUTE_REQUEST_BUILDER_CLASS =
            "com.bilibili.lib.blrouter.RouteRequest\$Builder"
        private const val BOOL_VALUE_CLASS = "com.bapis.bilibili.app.distribution.BoolValue"
        private const val PLAY_CONFIG_CLASS =
            "com.bapis.bilibili.app.distribution.setting.play.PlayConfig"
        private const val INTENT_HANDLER_ACTIVITY_CLASS =
            "tv.danmaku.bili.ui.intent.IntentHandlerActivity"
        private const val BLROUTER_TARGET_URL_EXTRA = "blrouter.targeturl"
        private const val GSON_SERIALIZED_NAME = "com.google.gson.annotations.SerializedName"
        private const val FASTJSON_JSON_FIELD = "com.alibaba.fastjson.annotation.JSONField"
        private val PLAY_CONFIG_STORY_GETTERS = listOf(
            "getLandscapeAutoStory",
            "getShouldAutoStory"
        )
        private val STORY_ROUTE_PREFIXES = listOf(
            "bilibili://story/",
            "bilibili://story?",
            "bilibili://story_translucent/",
            "bilibili://story_translucent?"
        )
        private val NUMERIC_FIELD_TYPES = setOf(
            java.lang.Byte.TYPE,
            java.lang.Short.TYPE,
            java.lang.Integer.TYPE,
            java.lang.Long.TYPE,
            Byte::class.javaObjectType,
            Short::class.javaObjectType,
            Int::class.javaObjectType,
            Long::class.javaObjectType
        )

        internal fun normalizeVideoRouteUri(uri: String): String? =
            HomeVerticalDetailRoutePolicy.normalizeVideoDetailUri(uri)
    }
}
