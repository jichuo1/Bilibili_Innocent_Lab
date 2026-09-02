package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.lang.reflect.Method
import java.lang.reflect.Modifier
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
        val backend = resolveBackend(environment) ?: return missing(
            environment,
            "missing-detail-activity"
        )
        val instrumentationCount = installActivityLaunchBoundary(environment, backend)
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
            "[BIL] 竖屏视频普通详情路由已安装，backend=${backend.name.lowercase()}," +
                "instrumentation=$instrumentationCount,playConfig=$playConfigCount," +
                "intentSanitizer=$intentSanitizerCount,status=success"
        )
        return FeatureInstallResult.Installed(installed)
    }

    /** 以宿主实际存在的 Activity 选择后端；新旧类同时存在时优先 United。 */
    private fun resolveBackend(environment: HookEnvironment): HomeVerticalDetailBackend? {
        val loader = environment.classLoader ?: return null
        return HomeVerticalDetailBackend.entries.firstOrNull { backend ->
            KavaMemberLookup.classOrNull(loader, backend.activityClassName)
                ?.let(Activity::class.java::isAssignableFrom) == true
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
     * 唯一路由写入边界。动态查找 Intent 参数，先复制再构造并校验完整契约，任何异常均保留
     * 宿主原 Intent。
     */
    private fun installActivityLaunchBoundary(
        environment: HookEnvironment,
        backend: HomeVerticalDetailBackend
    ): Int {
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
                        if (!HomeVerticalDetailRoutePolicy.isStrictStoryVideoRoute(
                                intent.data?.toString()
                            )
                        ) {
                            return@before
                        }
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                        val rewritten = rewriteIntentSafely(intent, backend, environment)
                            ?: return@before
                        args[intentIndex] = rewritten
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                        environment.logInfo(
                            "home_vertical_activity_launch",
                            "[BIL] Story 视频已在 Activity 启动边界按 " +
                                "${backend.name} 完整契约改为普通详情页"
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
        backend: HomeVerticalDetailBackend,
        environment: HookEnvironment
    ): Intent? = runCatching {
        val component = intent.component
        val plan = HomeVerticalDetailRoutePolicy.planActivityLaunch(
            HomeVerticalActivityLaunchSnapshot(
                dataUri = intent.data?.toString(),
                componentPackage = component?.packageName,
                targetPackage = intent.`package`,
                aid = intent.extraToken(AID_EXTRA),
                avid = intent.extraToken(AVID_EXTRA),
                bvid = intent.extraToken(BVID_EXTRA),
                preloadCid = HomeVerticalDetailRoutePolicy.parsePlayerPreloadCid(
                    intent.data?.getQueryParameter(PLAYER_PRELOAD_EXTRA)
                )
            ),
            backend
        ) ?: return@runCatching null
        val rewritten = Intent(intent)
        when (plan) {
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
    }.getOrElse { throwable ->
        environment.logError(
            "home_vertical_intent_rewrite_failed",
            "[BIL] Story 启动 Intent 构造异常，已保留宿主原 Intent: $throwable"
        )
        null
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
