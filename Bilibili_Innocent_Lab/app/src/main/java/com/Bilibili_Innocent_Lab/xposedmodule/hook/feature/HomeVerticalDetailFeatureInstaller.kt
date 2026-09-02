package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isStatic
import com.highcapable.kavaref.extension.isSubclassOf
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 在最终 Activity 启动边界把明确的 Story 视频转换为普通详情页。
 *
 * 这里只保留一个路由写入点：不再提前修改卡片、RouteRequest 或 Builder，避免宿主继续沿用
 * Story 路由创建的业务状态。新版宿主写入完整 United 契约，旧版宿主使用 Legacy Activity。
 */
internal class HomeVerticalDetailFeatureInstaller(
    private val enabled: Boolean,
    private val points: VersionAdapter.HomeRecommendFeedPoints?
) : FeatureInstaller {

    override val id: String = ID

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) {
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val backends = resolveBackends(environment)
        if (backends.isEmpty()) return missing(environment, "missing-detail-activity")
        val instrumentationCount = installActivityLaunchBoundary(environment, backends)
        if (instrumentationCount == 0) {
            return missing(environment, "no-safe-activity-launch-hook-point")
        }
        val playConfigCount = installPlayConfigStorySuppression(environment)
        val intentSanitizerCount = installIntentHandlerSanitizer(environment)
        val installed = instrumentationCount + playConfigCount + intentSanitizerCount

        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        environment.reportStatus(CHANNEL_STATUS, "success")
        environment.logInfo(
            "home_vertical_ok",
            "[BIL] 竖屏视频普通详情路由已安装，backend=" +
                backends.joinToString("+") { it.name.lowercase() } +
                ",instrumentation=$instrumentationCount,playConfig=$playConfigCount," +
                "intentSanitizer=$intentSanitizerCount,status=success"
        )
        return FeatureInstallResult.Installed(installed)
    }

    /**
     * 按偏好顺序返回宿主实际存在的后端。
     *
     * 之前只取第一个匹配并固化到整个进程：United 的任一前置条件不满足就直接放行，即使宿主
     * 同时具备旧详情页也没有第二次机会。现在保留完整列表，由每个 Intent 自行降级。
     */
    private fun resolveBackends(environment: HookEnvironment): List<HomeVerticalDetailBackend> {
        val loader = environment.classLoader ?: return emptyList()
        return HomeVerticalDetailBackend.entries.filter { backend ->
            KavaMemberLookup.classOrNull(loader, backend.activityClassName)
                ?.let { it isSubclassOf classOf<Activity>() } == true
        }
    }

    /** 禁止宿主把横屏普通播放自动提升为 Story，不改变用户主动进入 Story 页的其它开关。 */
    private fun installPlayConfigStorySuppression(environment: HookEnvironment): Int {
        val loader = environment.classLoader ?: return 0
        val boolValueClass = KavaMemberLookup.classOrNull(loader, BOOL_VALUE_CLASS) ?: return 0
        val playConfigClass = KavaMemberLookup.classOrNull(loader, PLAY_CONFIG_CLASS) ?: return 0
        val defaultMethod = KavaMemberLookup.methodOrNull(boolValueClass, "getDefaultInstance")
            ?.takeIf { it.isStatic && it.returnType == boolValueClass }
            ?: return 0
        val defaultValue = runCatching { defaultMethod.invoke(null) }
            .getOrNull()
            ?.takeIf(boolValueClass::isInstance)
            ?: return 0
        var installed = 0
        PLAY_CONFIG_STORY_GETTERS.forEach { methodName ->
            val method = KavaMemberLookup.methodOrNull(playConfigClass, methodName)
                ?.takeIf {
                    !it.isStatic && it.parameterCount == 0 &&
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
     * 唯一路由写入边界。动态查找 Intent 参数，先复制再构造并校验完整契约，任何异常均保留
     * 宿主原 Intent。
     */
    private fun installActivityLaunchBoundary(
        environment: HookEnvironment,
        backends: List<HomeVerticalDetailBackend>
    ): Int {
        val candidates = KavaMemberLookup.declaredMethods(
            classOf<Instrumentation>(),
            makeAccessible = true
        ) { method ->
            method.name == "execStartActivity" && !method.isStatic &&
                method.parameterTypes.count { it == classOf<Intent>() } == 1
        }.distinctBy(Method::toGenericString)
        var installed = 0
        candidates.forEachIndexed { index, method ->
            val intentIndex = method.parameterTypes.indexOf(classOf<Intent>())
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
                        if (!HomeVerticalDetailRoutePolicy.isStrictStoryVideoRoute(
                                intent.data?.toString()
                            )
                        ) {
                            return@before
                        }
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                        val applied = when (
                            val outcome = rewriteIntentSafely(intent, backends, environment)
                        ) {
                            is LaunchRewrite.Applied -> outcome
                            is LaunchRewrite.Skipped -> {
                                logSkipOnce(environment, intent, outcome.reason)
                                return@before
                            }
                        }
                        args[intentIndex] = applied.intent
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                        environment.logInfo(
                            "home_vertical_activity_launch",
                            "[BIL] Story 视频已在 Activity 启动边界按 " +
                                "${applied.backend.name} 完整契约改为普通详情页"
                        )
                    }
                }
                installed += 1
            }.onFailure { throwable ->
                environment.logError(
                    "home_vertical_instrumentation_$index",
                    "[BIL] Activity 启动边界注册失败(${method.parameterCount} 参数): $throwable"
                )
            }
        }
        return installed
    }

    /** IntentHandler 只清理强制 Story 参数，不再承担目标页面改写。 */
    private fun installIntentHandlerSanitizer(environment: HookEnvironment): Int {
        val point = points?.intentHandlerOnCreate ?: return 0
        return runCatching {
            environment.registrar.adapted("home.vertical.intent_handler_sanitizer", point) {
                before {
                    val activity = instance as? Activity ?: return@before
                    val original = activity.intent ?: return@before
                    val sanitizedUri = original.data?.toString()
                        ?.let(HomeVerticalDetailRoutePolicy::sanitizeIntentHandlerUri)
                        ?: return@before
                    val sanitized = Intent(original).apply { data = Uri.parse(sanitizedUri) }
                    activity.intent = sanitized
                    environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                    environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                    environment.logInfo(
                        "home_vertical_intent_handler_sanitized",
                        "[BIL] 已清理宿主 Intent 入口的强制 Story 参数"
                    )
                }
            }
            1
        }.getOrElse { throwable ->
            environment.logError(
                "home_vertical_intent_handler_failed",
                "[BIL] 宿主 Intent 入口参数清理注册失败: $throwable"
            )
            0
        }
    }

    private fun rewriteIntentSafely(
        intent: Intent,
        backends: List<HomeVerticalDetailBackend>,
        environment: HookEnvironment
    ): LaunchRewrite = runCatching {
        val component = intent.component
        // cid 解析要对 player_preload 做 JSON 解析，而宿主内联的 DASH manifest 实测可达
        // 28 KB。先用不含 cid 的快照判定；只有策略层明确说"就差 cid"时才付这笔开销，
        // 跨包、超长、身份冲突等注定放行的 Intent 完全不会触发解析。
        var snapshot = HomeVerticalActivityLaunchSnapshot(
            dataUri = intent.data?.toString(),
            componentPackage = component?.packageName,
            targetPackage = intent.`package`,
            aid = intent.extraToken(AID_EXTRA),
            avid = intent.extraToken(AVID_EXTRA),
            bvid = intent.extraToken(BVID_EXTRA),
            preloadCid = null
        )
        var cidResolved = false
        var firstReason: HomeVerticalLaunchSkip? = null
        // 按偏好顺序逐个尝试：United 缺 cid 或身份不是 aid 时，仍可能由 Legacy 详情页承接。
        backends.forEach { backend ->
            var outcome = HomeVerticalDetailRoutePolicy.planActivityLaunch(snapshot, backend)
            if (
                outcome is HomeVerticalActivityLaunchOutcome.Skipped &&
                outcome.reason == HomeVerticalLaunchSkip.MISSING_PRELOAD_CID &&
                !cidResolved
            ) {
                cidResolved = true
                snapshot = snapshot.copy(preloadCid = resolvePreloadCid(intent))
                if (snapshot.preloadCid != null) {
                    outcome = HomeVerticalDetailRoutePolicy.planActivityLaunch(snapshot, backend)
                }
            }
            when (outcome) {
                is HomeVerticalActivityLaunchOutcome.Skipped -> {
                    if (firstReason == null) firstReason = outcome.reason
                }

                is HomeVerticalActivityLaunchOutcome.Planned -> {
                    // 构造或完整契约校验失败时不记原因，交由 null 表示"非策略层主动放行"。
                    val built = buildIntent(intent, outcome.plan, backend)
                    if (built != null) return@runCatching LaunchRewrite.Applied(built, backend)
                }
            }
        }
        LaunchRewrite.Skipped(firstReason)
    }.getOrElse { throwable ->
        environment.logError(
            "home_vertical_intent_rewrite_failed",
            "[BIL] Story 启动 Intent 构造异常，已保留宿主原 Intent: $throwable"
        )
        LaunchRewrite.Skipped(null)
    }

    /**
     * 多来源解析 United 契约必需的 cid。
     *
     * 原实现只读 URI 查询参数 `player_preload`，而模块自己写回时用的是 Intent extra——读写
     * 方向不对称，且"宿主一定把它放在查询串里"这个前提从未被设备实测确认。这里按 URI 查询、
     * extra JSON、extra 数字 cid、URI 查询 cid 的顺序依次尝试，任一命中即可。
     */
    private fun resolvePreloadCid(intent: Intent): Long? {
        val uri = intent.data
        HomeVerticalDetailRoutePolicy.parsePlayerPreloadCid(
            runCatching { uri?.getQueryParameter(PLAYER_PRELOAD_EXTRA) }.getOrNull()
        )?.let { return it }
        HomeVerticalDetailRoutePolicy.parsePlayerPreloadCid(
            intent.extraToken(PLAYER_PRELOAD_EXTRA)
        )?.let { return it }
        intent.extraToken(CID_EXTRA)?.toLongOrNull()?.takeIf { it > 0L }?.let { return it }
        return runCatching { uri?.getQueryParameter(CID_EXTRA) }
            .getOrNull()
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
    }

    private fun buildIntent(
        intent: Intent,
        plan: HomeVerticalActivityLaunchPlan,
        backend: HomeVerticalDetailBackend
    ): Intent? {
        val rewritten = Intent(intent)
        return when (plan) {
            is HomeVerticalActivityLaunchPlan.Legacy -> {
                rewritten.data = Uri.parse(plan.detailUri)
                rewritten.component = ComponentName(TARGET_PACKAGE, backend.activityClassName)
                rewritten.takeIf { validateLegacyIntent(it, plan, backend) }
            }

            is HomeVerticalActivityLaunchPlan.United -> {
                val preloadToken = nextPlayerPreloadToken()
                UNITED_REPLACED_EXTRAS.forEach(rewritten::removeExtra)
                rewritten.data = Uri.parse(plan.detailUri)
                rewritten.component = ComponentName(TARGET_PACKAGE, backend.activityClassName)
                rewritten.putExtra(PLAYER_PRELOAD_EXTRA, preloadToken)
                rewritten.putExtra(BLROUTER_TARGET_URL_EXTRA, plan.targetUrl)
                rewritten.putExtra(BLROUTER_PAGE_NAME_EXTRA, UNITED_VIDEO_PAGE)
                rewritten.putExtra(BLROUTER_MATCH_RULE_EXTRA, UNITED_VIDEO_PAGE)
                rewritten.putExtra(JUMP_FROM_EXTRA, DETAIL_SOURCE)
                rewritten.putExtra(AID_EXTRA, plan.aid)
                rewritten.putExtra(CID_EXTRA, plan.cid)
                rewritten.putExtra(BVID_EXTRA, "")
                rewritten.putExtra(FROM_EXTRA, DETAIL_SOURCE)
                rewritten.takeIf {
                    validateUnitedIntent(it, plan, backend, preloadToken)
                }
            }
        }
    }

    /**
     * 每个放行原因在本进程只记一条，附带脱敏的 Intent 结构。
     *
     * 只输出路由根、是否有路径身份、查询串 key 列表与 extra 的 key/类型；不输出任何取值、
     * 标题、完整 URI 或跟踪参数。这足以回答"`player_preload` 到底在查询串还是 extra 里"
     * 这类问题，而不必再猜宿主行为。
     */
    private fun logSkipOnce(
        environment: HookEnvironment,
        intent: Intent,
        reason: HomeVerticalLaunchSkip?
    ) {
        val label = reason?.name?.lowercase() ?: "build-or-validate-failed"
        if (!loggedSkipReasons.add(label)) return
        val shape = runCatching { describeIntentShape(intent) }.getOrElse { "shape-unavailable" }
        environment.logInfo(
            "home_vertical_skip_$label",
            "[BIL] Story 视频未改写，reason=$label $shape"
        )
    }

    private fun describeIntentShape(intent: Intent): String {
        val uri = intent.data
        val root = uri?.let { "${it.scheme}://${it.host}" } ?: "none"
        val hasPath = uri?.path?.trim('/')?.isNotEmpty() == true
        val queryKeys = runCatching {
            uri?.queryParameterNames.orEmpty()
                .take(MAX_LOGGED_KEYS)
                .joinToString(",") { it.take(MAX_LOGGED_KEY_LENGTH) }
        }.getOrDefault("")
        val extraKeys = runCatching {
            intent.extras?.keySet().orEmpty()
                .take(MAX_LOGGED_KEYS)
                .joinToString(",") { key ->
                    @Suppress("DEPRECATION")
                    val type = runCatching { intent.extras?.get(key) }
                        .getOrNull()
                        ?.javaClass
                        ?.simpleName
                        ?: "null"
                    "${key.take(MAX_LOGGED_KEY_LENGTH)}:$type"
                }
        }.getOrDefault("")
        return "root=$root hasPath=$hasPath queryKeys=[$queryKeys] extraKeys=[$extraKeys]"
    }

    private fun validateLegacyIntent(
        intent: Intent,
        plan: HomeVerticalActivityLaunchPlan.Legacy,
        backend: HomeVerticalDetailBackend
    ): Boolean = intent.data?.toString() == plan.detailUri &&
        intent.component?.packageName == TARGET_PACKAGE &&
        intent.component?.className == backend.activityClassName

    private fun validateUnitedIntent(
        intent: Intent,
        plan: HomeVerticalActivityLaunchPlan.United,
        backend: HomeVerticalDetailBackend,
        preloadToken: String
    ): Boolean = intent.data?.toString() == plan.detailUri &&
        intent.component?.packageName == TARGET_PACKAGE &&
        intent.component?.className == backend.activityClassName &&
        intent.getStringExtra(PLAYER_PRELOAD_EXTRA) == preloadToken &&
        preloadToken.all(Char::isDigit) &&
        intent.getStringExtra(BLROUTER_TARGET_URL_EXTRA) == plan.targetUrl &&
        intent.getStringExtra(BLROUTER_PAGE_NAME_EXTRA) == UNITED_VIDEO_PAGE &&
        intent.getStringExtra(BLROUTER_MATCH_RULE_EXTRA) == UNITED_VIDEO_PAGE &&
        intent.getLongExtra(AID_EXTRA, -1L) == plan.aid &&
        intent.getLongExtra(CID_EXTRA, -1L) == plan.cid &&
        intent.getStringExtra(BVID_EXTRA) == "" &&
        intent.getIntExtra(JUMP_FROM_EXTRA, -1) == DETAIL_SOURCE &&
        intent.getIntExtra(FROM_EXTRA, -1) == DETAIL_SOURCE

    @Suppress("DEPRECATION")
    private fun Intent.extraToken(key: String): String? = runCatching {
        when (val value = extras?.get(key)) {
            is Number -> value.toLong().takeIf { it > 0L }?.toString()
            is String -> value.trim().takeIf(String::isNotEmpty)
            else -> null
        }
    }.getOrNull()

    private fun nextPlayerPreloadToken(): String = playerPreloadSequence.updateAndGet { current ->
        if (current >= MAX_PLAYER_PRELOAD_TOKEN) 1L else current + 1L
    }.toString()

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

    private sealed interface LaunchRewrite {
        data class Applied(
            val intent: Intent,
            val backend: HomeVerticalDetailBackend
        ) : LaunchRewrite

        /** [reason] 为 null 表示 Intent 构造或完整契约校验失败，而非策略层主动放行。 */
        data class Skipped(val reason: HomeVerticalLaunchSkip?) : LaunchRewrite
    }

    companion object {
        const val ID = "home_vertical_detail"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "home_vertical_detail_status"
        private const val BOOL_VALUE_CLASS = "com.bapis.bilibili.app.distribution.BoolValue"
        private const val PLAY_CONFIG_CLASS =
            "com.bapis.bilibili.app.distribution.setting.play.PlayConfig"
        private const val PLAYER_PRELOAD_EXTRA = "player_preload"
        private const val BLROUTER_TARGET_URL_EXTRA = "blrouter.targeturl"
        private const val BLROUTER_PAGE_NAME_EXTRA = "blrouter.pagename"
        private const val BLROUTER_MATCH_RULE_EXTRA = "blrouter.matchrule"
        private const val JUMP_FROM_EXTRA = "jumpFrom"
        private const val AID_EXTRA = "aid"
        private const val AVID_EXTRA = "avid"
        private const val CID_EXTRA = "cid"
        private const val BVID_EXTRA = "bvid"
        private const val FROM_EXTRA = "from"
        private const val UNITED_VIDEO_PAGE = "bilibili://united_video/"
        private const val DETAIL_SOURCE = 7
        private const val MAX_PLAYER_PRELOAD_TOKEN = 999_999_998L
        private const val MAX_LOGGED_KEYS = 24
        private const val MAX_LOGGED_KEY_LENGTH = 40

        /** 有界：至多 HomeVerticalLaunchSkip 枚举项数 + 1 条构造失败记录。 */
        private val loggedSkipReasons = ConcurrentHashMap.newKeySet<String>()
        private val playerPreloadSequence = AtomicLong(
            (System.nanoTime() and 0x3fff_ffffL).coerceAtLeast(1L)
        )
        private val PLAY_CONFIG_STORY_GETTERS = listOf(
            "getLandscapeAutoStory",
            "getShouldAutoStory"
        )
        private val UNITED_REPLACED_EXTRAS = listOf(
            PLAYER_PRELOAD_EXTRA,
            BLROUTER_TARGET_URL_EXTRA,
            BLROUTER_PAGE_NAME_EXTRA,
            BLROUTER_MATCH_RULE_EXTRA,
            JUMP_FROM_EXTRA,
            AID_EXTRA,
            AVID_EXTRA,
            CID_EXTRA,
            BVID_EXTRA,
            FROM_EXTRA
        )

        internal fun normalizeVideoRouteUri(uri: String): String? =
            HomeVerticalDetailRoutePolicy.normalizeVideoDetailUri(uri)
    }
}
