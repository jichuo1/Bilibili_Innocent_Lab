package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isStatic
import com.highcapable.kavaref.extension.isSubclassOf
import java.lang.reflect.Method

/**
 * 在 Activity 启动边界把宿主内置 WebView 的站外网页改交系统浏览器打开。
 *
 * 复用与 [HomeVerticalDetailFeatureInstaller] 同一个平台边界（`Instrumentation`），但两者
 * 判据完全不相交：那边只认 Story 视频路由，这边只认 `*MWebActivity` + 站外 http(s) 域名，
 * 因此同时启用也不会互相覆盖。
 *
 * 落地前会先用发起方 Context 的 PackageManager 确认真的有应用能接住这个 URL。没有浏览器
 * 时保留宿主原 Intent——宁可继续用内置 WebView，也不能让宿主抛
 * `ActivityNotFoundException`。这次查询只发生在用户真的点开站外链接时，不在任何热路径上。
 */
internal class ExternalBrowserFeatureInstaller(
    private val enabled: Boolean
) : FeatureInstaller {

    override val id: String = ID

    /**
     * "系统没有应用能接住这个链接"只值得记一次。
     *
     * Android 11+ 的软件包可见性会让 `resolveActivity` 在没有 `<queries>` 声明时恒返回 null；
     * 宿主 9.7.0–9.11.0 都声明了 `QUERY_ALL_PACKAGES`，所以当前不受影响。真要遇到宿主收紧
     * 声明的那天，这条日志就是唯一线索，但它绝不能随每次点击刷屏。
     */
    private val noHandlerLogged = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) {
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }

        // 必须同时拿得到 Intent 和发起方 Context：没有 Context 就无法确认有没有浏览器接住，
        // 那种重载宁可不装，也不留一个永远走不到改写分支的空 Hook。
        val candidates = KavaMemberLookup.declaredMethods(
            classOf<Instrumentation>(),
            makeAccessible = true
        ) { method ->
            method.name == "execStartActivity" && !method.isStatic &&
                method.parameterTypes.count { it == classOf<Intent>() } == 1 &&
                method.parameterTypes.any { it isSubclassOf classOf<Context>() }
        }.distinctBy(Method::toGenericString)
        if (candidates.isEmpty()) return missing(environment, "no-safe-activity-launch-hook-point")

        var installed = 0
        candidates.forEachIndexed { index, method ->
            val intentIndex = method.parameterTypes.indexOf(classOf<Intent>())
            val contextIndex = method.parameterTypes.indexOfFirst {
                it isSubclassOf classOf<Context>()
            }
            runCatching {
                environment.registrar.exact(
                    "external.browser.instrumentation.$index",
                    method.declaringClass,
                    method.name,
                    *method.parameterTypes
                ) {
                    before {
                        val intent = args.getOrNull(intentIndex) as? Intent ?: return@before
                        val data = intent.data ?: return@before
                        if (!ExternalBrowserPolicy.shouldOpenExternally(
                                intent.component?.className,
                                data.scheme,
                                data.host
                            )
                        ) {
                            return@before
                        }
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                        val caller = args.getOrNull(contextIndex) as? Context
                        val external = buildExternalIntent(intent, data)
                        if (!canBeHandled(caller, external)) {
                            if (noHandlerLogged.compareAndSet(false, true)) {
                                environment.logInfo(
                                    "external_browser_no_handler",
                                    "[BIL] 系统没有可接住该链接的应用，保留宿主内置浏览器"
                                )
                            }
                            return@before
                        }
                        args[intentIndex] = external
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                    }
                }
                installed += 1
            }.onFailure { throwable ->
                environment.logError(
                    "external_browser_instrumentation_$index",
                    "[BIL] 外部浏览器跳转注册失败(${method.parameterCount} 参数): $throwable"
                )
            }
        }

        if (installed == 0) return missing(environment, "registration-failed")
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        val status = if (installed == candidates.size) {
            "success"
        } else {
            "partial:$installed/${candidates.size}"
        }
        environment.reportStatus(CHANNEL_STATUS, status)
        if (status == "success") {
            environment.logInfo(
                "external_browser_ok",
                "[BIL] 站外链接外部浏览器打开已安装，hooks=$installed"
            )
        } else {
            environment.logError(
                "external_browser_partial",
                "[BIL] 站外链接外部浏览器打开部分安装，status=$status"
            )
        }
        return FeatureInstallResult.Installed(installed)
    }

    /**
     * 只带链接本身出门。
     *
     * 不复制宿主 extra：那些 key 是给内置 WebView 用的，交给第三方浏览器既无意义，也可能
     * 把宿主内部标识泄露出去。`FLAG_ACTIVITY_NEW_TASK` 仅在原 Intent 已经带了它时保留。
     */
    private fun buildExternalIntent(original: Intent, data: android.net.Uri): Intent =
        Intent(Intent.ACTION_VIEW, data).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            if (original.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

    /** 解析失败一律当作"没人接"，让调用方保留宿主原 Intent。 */
    private fun canBeHandled(caller: Context?, intent: Intent): Boolean {
        val packageManager = caller?.packageManager ?: return false
        return runCatching {
            @Suppress("DEPRECATION")
            packageManager.resolveActivity(intent, 0) != null
        }.getOrDefault(false)
    }

    private fun missing(
        environment: HookEnvironment,
        reason: String
    ): FeatureInstallResult.Skipped {
        environment.reportStatus(CHANNEL_STATUS, reason)
        environment.logError(
            "external_browser_missing",
            "[BIL] 站外链接外部浏览器打开适配不完整: $reason"
        )
        return FeatureInstallResult.Skipped(reason)
    }

    companion object {
        const val ID = "external_browser"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "external_browser_status"
    }
}
