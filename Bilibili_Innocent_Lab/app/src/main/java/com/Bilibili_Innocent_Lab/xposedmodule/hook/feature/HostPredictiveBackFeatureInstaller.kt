package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.PersistableBundle
import android.view.Window
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.PredictiveBackApi33
import io.github.jichuo1.meguviz.android.extensions.AndroidVersion
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 强制启用宿主预见式返回特性（Android 13+，推荐 Android 14+）。
 *
 * 机制：
 * 1. 通过隔离层探测平台返回回调分发器与门禁类，在仅限于合法宿主标准页面时将其可用性判定由 `false` 提升为 `true`，
 *    并在系统级总开关显式关闭时保持尊重，不强行改写。
 * 2. 在 `Instrumentation.callActivityOnCreate` 之前，按同一策略为标准页面尽力同步 `ActivityInfo` 标志位与
 *    `Window` 开关（均做自检回滚，不强缓存任何宿主实例）。
 * 3. 对重写了自定义返回或按键分发逻辑的宿主页面（如全屏播放页、WebView、首页等）保持原状，避免破坏现有交互。
 */
internal class HostPredictiveBackFeatureInstaller(
    private val enabled: Boolean,
    private val apiSupported: () -> Boolean = { AndroidVersion.isAtLeast(AndroidVersion.T) },
    private val systemFeatureEnabled: () -> Boolean = {
        if (AndroidVersion.isAtLeast(AndroidVersion.T)) PredictiveBackApi33.isSystemPredictiveBackEnabled() else false
    },
    private val dispatcherClassProvider: () -> Class<*>? = {
        if (AndroidVersion.isAtLeast(AndroidVersion.T)) PredictiveBackApi33.windowDispatcherClassOrNull() else null
    },
    private val checkerClassProvider: () -> Class<*>? = {
        if (AndroidVersion.isAtLeast(AndroidVersion.T)) PredictiveBackApi33.windowDispatcherCheckerClassOrNull() else null
    },
    private val activityInfoEnabler: (Activity) -> Boolean = ::tryEnableActivityInfoAndWindow,
    private val policy: HostPredictiveBackPolicy = HostPredictiveBackPolicy()
) : FeatureInstaller {
    override val id: String = ID

    private val adaptedReported = AtomicBoolean(false)
    private val observedReported = AtomicBoolean(false)
    private val appliedReported = AtomicBoolean(false)

    @Synchronized
    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) {
            environment.reportStatus(STATUS, STATE_DISABLED)
            return FeatureInstallResult.Skipped(STATE_DISABLED)
        }
        if (!isSupportedProcess(environment.processName)) {
            return FeatureInstallResult.Skipped(STATE_NON_TARGET_PROCESS)
        }
        if (!apiSupported()) {
            environment.reportStatus(STATUS, STATE_NOT_APPLICABLE_HOST)
            return FeatureInstallResult.Skipped(STATE_NOT_APPLICABLE_HOST)
        }

        val checkerResult = installEnabledGateHook(
            environment = environment,
            hookId = HOOK_CHECKER_ENABLED,
            targetClass = runCatching { checkerClassProvider() }.getOrNull(),
            tag = "checker"
        )
        val dispatcherResult = installEnabledGateHook(
            environment = environment,
            hookId = HOOK_DISPATCHER_ENABLED,
            targetClass = runCatching { dispatcherClassProvider() }.getOrNull(),
            tag = "dispatcher"
        )

        val coreInstalledCount =
            (if (checkerResult.installed) 1 else 0) + (if (dispatcherResult.installed) 1 else 0)
        val hadCoreFailure = checkerResult.failed || dispatcherResult.failed

        if (coreInstalledCount == 0) {
            val reason = if (hadCoreFailure) STATE_REGISTRATION_FAILED else STATE_MISSING_DISPATCHER
            environment.reportStatus(STATUS, reason)
            return FeatureInstallResult.Skipped(reason)
        }

        val instrumentationResult = installInstrumentationSyncHooks(environment)
        val totalInstalled = coreInstalledCount + instrumentationResult.installedCount
        val complete = !hadCoreFailure && !instrumentationResult.failed

        environment.reportStatus(
            STATUS,
            "checker:${checkerResult.state},dispatcher:${dispatcherResult.state}," +
                "instrumentation:${instrumentationResult.installedCount}"
        )
        if (adaptedReported.compareAndSet(false, true)) {
            environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        }
        return FeatureInstallResult.Installed(hookCount = totalInstalled, complete = complete)
    }

    private data class GateInstallOutcome(
        val installed: Boolean,
        val failed: Boolean,
        val state: String
    )

    private fun installEnabledGateHook(
        environment: HookEnvironment,
        hookId: String,
        targetClass: Class<*>?,
        tag: String
    ): GateInstallOutcome {
        if (targetClass == null) {
            return GateInstallOutcome(installed = false, failed = false, state = STATE_MISSING)
        }
        val method = KavaMemberLookup.methodOrNull(
            targetClass,
            METHOD_IS_CALLBACK_ENABLED,
            classOf<Context>()
        ) ?: return GateInstallOutcome(installed = false, failed = false, state = STATE_MISSING_METHOD)

        return runCatching {
            environment.registrar.exact(
                hookId,
                method.declaringClass,
                method.name,
                *method.parameterTypes
            ) {
                after {
                    if (hasThrowable || result == true) return@after
                    if (!systemFeatureEnabled()) return@after
                    val context = args.firstOrNull() as? Context ?: return@after
                    val activity = HostPredictiveBackPolicy.unwrapActivity(context) ?: return@after
                    markObserved(environment)
                    if (policy.classify(activity.javaClass).eligible) {
                        result = true
                        markApplied(environment)
                    }
                }
            }
            GateInstallOutcome(installed = true, failed = false, state = STATE_INSTALLED)
        }.getOrElse { failure ->
            environment.logError(
                "host_predictive_back_$tag",
                "[BIL] 宿主预见式返回门禁 Hook 注册失败($tag): $failure"
            )
            GateInstallOutcome(installed = false, failed = true, state = STATE_REGISTRATION_FAILED)
        }
    }

    private data class InstrumentationOutcome(
        val installedCount: Int,
        val failed: Boolean
    )

    private fun installInstrumentationSyncHooks(environment: HookEnvironment): InstrumentationOutcome {
        val instrumentationClass = classOf<Instrumentation>()
        val candidates = listOf(
            Triple(
                HOOK_INSTRUMENTATION_ON_CREATE,
                KavaMemberLookup.methodOrNull(
                    instrumentationClass,
                    METHOD_CALL_ACTIVITY_ON_CREATE,
                    classOf<Activity>(),
                    classOf<Bundle>()
                ),
                "two_args"
            ),
            Triple(
                HOOK_INSTRUMENTATION_ON_CREATE_PERSISTABLE,
                KavaMemberLookup.methodOrNull(
                    instrumentationClass,
                    METHOD_CALL_ACTIVITY_ON_CREATE,
                    classOf<Activity>(),
                    classOf<Bundle>(),
                    classOf<PersistableBundle>()
                ),
                "three_args"
            )
        )

        var installedCount = 0
        var anyFailed = false
        for ((hookId, method, variant) in candidates) {
            if (method == null) continue
            runCatching {
                environment.registrar.exact(
                    hookId,
                    method.declaringClass,
                    method.name,
                    *method.parameterTypes
                ) {
                    before {
                        if (!systemFeatureEnabled()) return@before
                        val activity = args.firstOrNull() as? Activity ?: return@before
                        markObserved(environment)
                        if (policy.classify(activity.javaClass).eligible && activityInfoEnabler(activity)) {
                            markApplied(environment)
                        }
                    }
                }
                installedCount++
            }.onFailure { failure ->
                anyFailed = true
                environment.logError(
                    "host_predictive_back_instrumentation_$variant",
                    "[BIL] 宿主预见式返回生命周期 Hook 注册失败($variant): $failure"
                )
            }
        }
        return InstrumentationOutcome(installedCount = installedCount, failed = anyFailed)
    }

    private fun markObserved(environment: HookEnvironment) {
        if (observedReported.compareAndSet(false, true)) {
            environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
        }
    }

    private fun markApplied(environment: HookEnvironment) {
        if (appliedReported.compareAndSet(false, true)) {
            environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
        }
    }

    companion object {
        const val ID = "host_predictive_back"
        const val STATUS = "host_predictive_back_status"
        const val HOOK_CHECKER_ENABLED = "host_predictive_back.checker_enabled"
        const val HOOK_DISPATCHER_ENABLED = "host_predictive_back.dispatcher_enabled"
        const val HOOK_INSTRUMENTATION_ON_CREATE = "host_predictive_back.instrumentation_on_create"
        const val HOOK_INSTRUMENTATION_ON_CREATE_PERSISTABLE =
            "host_predictive_back.instrumentation_on_create_persistable"

        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val METHOD_IS_CALLBACK_ENABLED = "isOnBackInvokedCallbackEnabled"
        private const val METHOD_CALL_ACTIVITY_ON_CREATE = "callActivityOnCreate"
        private const val DEFAULT_ENABLE_PRIVATE_FLAG = 1 shl 2
        private const val DEFAULT_DISABLE_PRIVATE_FLAG = 1 shl 3

        private const val STATE_DISABLED = "disabled"
        private const val STATE_NON_TARGET_PROCESS = "non-main-process"
        private const val STATE_NOT_APPLICABLE_HOST = "not-applicable-host"
        private const val STATE_MISSING_DISPATCHER = "missing-platform-dispatcher"
        private const val STATE_REGISTRATION_FAILED = "registration-failed"
        private const val STATE_INSTALLED = "installed"
        private const val STATE_MISSING = "missing-class"
        private const val STATE_MISSING_METHOD = "missing-method"

        private fun isSupportedProcess(processName: String): Boolean =
            processName == TARGET_PACKAGE || processName.startsWith("$TARGET_PACKAGE:")

        @Volatile
        private var activityGetInfoMethod: Method? = null

        @Volatile
        private var activityInfoField: Field? = null

        @Volatile
        private var activityMembersResolved = false

        @Volatile
        private var infoPrivateFlagsField: Field? = null

        @Volatile
        private var infoIsEnabledMethod: Method? = null

        @Volatile
        private var infoEnableFlagMask: Int = DEFAULT_ENABLE_PRIVATE_FLAG

        @Volatile
        private var infoDisableFlagMask: Int = DEFAULT_DISABLE_PRIVATE_FLAG

        @Volatile
        private var activityInfoMembersResolved = false

        @Volatile
        private var windowEnableMethod: Method? = null

        @Volatile
        private var windowMethodResolved = false

        private fun ensureActivityMembers() {
            if (activityMembersResolved) return
            val activityCls = classOf<Activity>()
            activityGetInfoMethod = KavaMemberLookup.methodOrNull(activityCls, "getActivityInfo")
            activityInfoField = KavaMemberLookup.fieldOrNull(activityCls, "mActivityInfo")
            activityMembersResolved = true
        }

        private fun ensureActivityInfoMembers() {
            if (activityInfoMembersResolved) return
            val infoCls = classOf<ActivityInfo>()
            infoPrivateFlagsField = KavaMemberLookup.fieldOrNull(infoCls, "privateFlags")
            infoIsEnabledMethod = KavaMemberLookup.methodOrNull(infoCls, METHOD_IS_CALLBACK_ENABLED)
            infoEnableFlagMask = readStaticIntMask(
                infoCls,
                "PRIVATE_FLAG_ENABLE_ON_BACK_INVOKED_CALLBACK",
                DEFAULT_ENABLE_PRIVATE_FLAG
            )
            infoDisableFlagMask = readStaticIntMask(
                infoCls,
                "PRIVATE_FLAG_DISABLE_ON_BACK_INVOKED_CALLBACK",
                DEFAULT_DISABLE_PRIVATE_FLAG
            )
            activityInfoMembersResolved = true
        }

        private fun readStaticIntMask(cls: Class<*>, name: String, fallback: Int): Int {
            val field = KavaMemberLookup.fieldOrNull(cls, name)
                ?.takeIf { Modifier.isStatic(it.modifiers) }
                ?: return fallback
            return runCatching { (field.get(null) as? Int) ?: fallback }.getOrDefault(fallback)
        }

        private fun resolveWindowEnableMethod(): Method? {
            if (!windowMethodResolved) {
                windowEnableMethod = KavaMemberLookup.methodOrNull(
                    classOf<Window>(),
                    "setEnableOnBackInvokedCallback",
                    java.lang.Boolean.TYPE
                )
                windowMethodResolved = true
            }
            return windowEnableMethod
        }

        private fun readActivityInfo(activity: Activity): ActivityInfo? {
            ensureActivityMembers()
            val getter = activityGetInfoMethod
            if (getter != null) {
                val info = runCatching { getter.invoke(activity) as? ActivityInfo }.getOrNull()
                if (info != null) return info
            }
            val field = activityInfoField ?: return null
            return runCatching { field.get(activity) as? ActivityInfo }.getOrNull()
        }

        /**
         * 同步启用单个合格 Activity 的 `ActivityInfo` 私有标志与 `Window` 开关。
         * 修改 `privateFlags` 后立即通过系统校验方法确认生效；不匹配时回滚原值，绝不破坏其它标志位。
         */
        internal fun tryEnableActivityInfoAndWindow(activity: Activity): Boolean {
            var applied = false
            ensureActivityInfoMembers()
            val info = readActivityInfo(activity)
            val flagsField = infoPrivateFlagsField
            val enabledChecker = infoIsEnabledMethod
            if (info != null && flagsField != null && enabledChecker != null) {
                runCatching {
                    val alreadyEnabled = (enabledChecker.invoke(info) as? Boolean) == true
                    if (alreadyEnabled) {
                        applied = true
                    } else {
                        val previous = flagsField.getInt(info)
                        val updated = HostPredictiveBackPolicy.computeEnabledPrivateFlags(
                            previous,
                            infoEnableFlagMask,
                            infoDisableFlagMask
                        )
                        flagsField.setInt(info, updated)
                        if ((enabledChecker.invoke(info) as? Boolean) == true) {
                            applied = true
                        } else {
                            // 掩码口径若与当前 ROM 不一致则立即恢复，避免污染其它私有标志位。
                            flagsField.setInt(info, previous)
                        }
                    }
                }
            }

            val window = runCatching { activity.window }.getOrNull()
            val windowMethod = resolveWindowEnableMethod()
            if (window != null && windowMethod != null) {
                runCatching {
                    windowMethod.invoke(window, true)
                    applied = true
                }
            }
            return applied
        }
    }
}
