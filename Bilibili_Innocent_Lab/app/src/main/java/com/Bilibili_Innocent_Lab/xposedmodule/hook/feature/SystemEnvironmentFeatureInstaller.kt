package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SystemEnvironmentAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SystemEnvironmentPoints
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 宿主局部 Root / Hook 检测的结果滤镜。宿主真实检测与其它环境字段照常执行；
 * 不修改 File、PackageManager、类加载、签名、DRM、完整性校验或网络返回。
 */
internal class SystemEnvironmentFeatureInstaller(
    private val enabled: Boolean,
    private val cachedPoints: () -> SystemEnvironmentPoints? = { null }
) : FeatureInstaller {
    override val id = ID
    override val capabilityIds: List<String> get() = if (enabled) listOf(CAP_ROOT, CAP_HOOK) else emptyList()
    @Volatile var requiresAdaptationRetry = false
        private set

    private val registered = linkedSetOf<SystemEnvironmentAdapter.Family>()
    private val attempted = linkedSetOf<SystemEnvironmentAdapter.Family>()
    private val parentAdapted = AtomicBoolean(false)
    private val parentObserved = AtomicBoolean(false)
    private val parentApplied = AtomicBoolean(false)
    private val rootObserved = AtomicBoolean(false)
    private val rootApplied = AtomicBoolean(false)
    private val hookObserved = AtomicBoolean(false)
    private val hookApplied = AtomicBoolean(false)

    @Synchronized
    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) {
            requiresAdaptationRetry = false
            environment.reportStatus(STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != TARGET_PACKAGE) {
            requiresAdaptationRetry = false
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val loader = environment.classLoader ?: run {
            requiresAdaptationRetry = false
            environment.reportStatus(STATUS, "missing-class-loader")
            return FeatureInstallResult.Skipped("missing-class-loader")
        }

        val points = cachedPoints()
        val states = linkedMapOf<String, String>()
        for (family in SystemEnvironmentAdapter.Family.entries) {
            val capabilityId = capability(family)
            if (family in registered) {
                states[family.key] = STATE_INSTALLED
                environment.reportCapability(capabilityId, FeatureInstallResult.Installed(1))
                continue
            }
            val method = SystemEnvironmentAdapter.resolve(loader, family, points)
            if (method == null) {
                val reason = if (family in attempted) STATE_REGISTRATION_FAILED else STATE_MISSING_POINT
                states[family.key] = reason
                environment.reportCapability(capabilityId, FeatureInstallResult.Skipped(reason))
                continue
            }
            if (!attempted.add(family)) {
                states[family.key] = STATE_REGISTRATION_FAILED
                environment.reportCapability(capabilityId, FeatureInstallResult.Skipped(STATE_REGISTRATION_FAILED))
                continue
            }
            val leafEnv = environment.forCapabilityRuntime(capabilityId)
            val state = runCatching {
                environment.registrar.exact(
                    hookId(family),
                    method.declaringClass,
                    method.name,
                    *method.parameterTypes
                ) {
                    after {
                        if (hasThrowable) return@after
                        val original = result
                        val sanitized = if (family == SystemEnvironmentAdapter.Family.ENVIRONMENT) {
                            SystemEnvironmentResult.sanitizeEnvironment(original)
                        } else {
                            SystemEnvironmentResult.sanitizeHook(original)
                        } ?: return@after
                        markObserved(environment, leafEnv, family)
                        if (sanitized.applied) {
                            result = sanitized.value
                            markApplied(environment, leafEnv, family)
                        }
                    }
                }
                registered += family
                leafEnv.reportRuntimeEvidence(capabilityId, FeatureRuntimeStage.ADAPTED)
                STATE_INSTALLED
            }.getOrElse { failure ->
                environment.logError(
                    "system_environment_${family.key}",
                    "[BIL] 环境检测结果 Hook 注册失败(${family.key}): $failure"
                )
                STATE_REGISTRATION_FAILED
            }
            states[family.key] = state
            environment.reportCapability(
                capabilityId,
                if (state == STATE_INSTALLED) FeatureInstallResult.Installed(1)
                else FeatureInstallResult.Skipped(state)
            )
        }

        requiresAdaptationRetry = SystemEnvironmentAdapter.Family.entries.any {
            it !in registered && it !in attempted
        }
        environment.reportStatus(STATUS, states.entries.joinToString(",") { "${it.key}:${it.value}" })
        if (registered.isEmpty()) {
            val skipReason = if (STATE_REGISTRATION_FAILED in states.values) {
                STATE_REGISTRATION_FAILED
            } else {
                STATE_MISSING_POINT
            }
            return FeatureInstallResult.Skipped(skipReason)
        }
        if (parentAdapted.compareAndSet(false, true)) {
            environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        }
        return FeatureInstallResult.Installed(
            registered.size,
            complete = registered.size == SystemEnvironmentAdapter.Family.entries.size
        )
    }

    private fun markObserved(
        environment: HookEnvironment,
        leafEnv: HookEnvironment,
        family: SystemEnvironmentAdapter.Family
    ) {
        if (parentObserved.compareAndSet(false, true)) {
            environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
        }
        val flag = if (family == SystemEnvironmentAdapter.Family.ENVIRONMENT) rootObserved else hookObserved
        if (flag.compareAndSet(false, true)) {
            leafEnv.reportRuntimeEvidence(capability(family), FeatureRuntimeStage.OBSERVED)
        }
    }

    private fun markApplied(
        environment: HookEnvironment,
        leafEnv: HookEnvironment,
        family: SystemEnvironmentAdapter.Family
    ) {
        if (parentApplied.compareAndSet(false, true)) {
            environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
        }
        val flag = if (family == SystemEnvironmentAdapter.Family.ENVIRONMENT) rootApplied else hookApplied
        if (flag.compareAndSet(false, true)) {
            leafEnv.reportRuntimeEvidence(capability(family), FeatureRuntimeStage.APPLIED)
        }
    }

    companion object {
        const val ID = "system_environment_detection"
        const val CAP_ROOT = "system_environment_root"
        const val CAP_HOOK = "system_environment_hook"
        const val STATUS = "system_environment_detection_status"
        const val HOOK_ENVIRONMENT = "system_environment.environment"
        const val HOOK_FEATURES = "system_environment.features"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val STATE_INSTALLED = "installed"
        private const val STATE_MISSING_POINT = "missing-adapter-point"
        private const val STATE_REGISTRATION_FAILED = "registration-failed"

        private fun capability(family: SystemEnvironmentAdapter.Family): String = when (family) {
            SystemEnvironmentAdapter.Family.ENVIRONMENT -> CAP_ROOT
            SystemEnvironmentAdapter.Family.HOOK -> CAP_HOOK
        }

        private fun hookId(family: SystemEnvironmentAdapter.Family): String = when (family) {
            SystemEnvironmentAdapter.Family.ENVIRONMENT -> HOOK_ENVIRONMENT
            SystemEnvironmentAdapter.Family.HOOK -> HOOK_FEATURES
        }
    }
}

/** 纯数据规则：没有已验证字段时返回 null，干净结果零拷贝原样放行。 */
internal object SystemEnvironmentResult {
    data class Sanitized<T>(val value: T, val applied: Boolean)

    private val hookKeys = listOf("xposed", "substrate", "frida", "riru")
    private val envDetectionKeys = listOf("root", "is_root", "axposed")

    @Suppress("UNCHECKED_CAST")
    fun sanitizeEnvironment(original: Any?): Sanitized<Map<Any?, Any?>>? {
        val map = original as? Map<*, *> ?: return null
        if (map.keys.any { it !is String }) return null
        if (envDetectionKeys.none { it in map }) return null

        var rootReplacement: String? = null
        if ("root" in map) {
            val rootRaw = map["root"] as? String ?: return null
            val asBoolean = rootRaw.toBooleanStrictOrNull()
            val asLong = rootRaw.toLongOrNull()
            if (asBoolean == null && asLong == null) return null
            rootReplacement = when {
                asBoolean == true -> "false"
                asLong != null && asLong != 0L -> "0"
                else -> null
            }
        }

        var isRootReplacement: String? = null
        if ("is_root" in map) {
            val isRootRaw = map["is_root"] as? String ?: return null
            if (isRootRaw.toBooleanStrictOrNull() == null) return null
            if (isRootRaw == "true") isRootReplacement = "false"
        }

        var axposedReplacement: String? = null
        if ("axposed" in map) {
            val axposedRaw = map["axposed"] as? String ?: return null
            if (axposedRaw.toBooleanStrictOrNull() == null) return null
            if (axposedRaw == "true") axposedReplacement = "false"
        }

        var mapsReplacement: String? = null
        if ("maps" in map) {
            val mapsRaw = map["maps"] as? String ?: return null
            if (mapsRaw == "xposed" || mapsRaw == "substrate") mapsReplacement = ""
        }

        if (rootReplacement == null && isRootReplacement == null &&
            axposedReplacement == null && mapsReplacement == null
        ) {
            return Sanitized(map as Map<Any?, Any?>, applied = false)
        }

        val sanitized = LinkedHashMap<Any?, Any?>(map)
        rootReplacement?.let { sanitized["root"] = it }
        isRootReplacement?.let { sanitized["is_root"] = it }
        axposedReplacement?.let { sanitized["axposed"] = it }
        mapsReplacement?.let { sanitized["maps"] = it }
        return Sanitized(sanitized, applied = true)
    }

    @Suppress("UNCHECKED_CAST")
    fun sanitizeHook(original: Any?): Sanitized<Map<Any?, Any?>>? {
        val map = original as? Map<*, *> ?: return null
        if (map.keys.any { it !is String }) return null
        if (hookKeys.any { map[it] !is Boolean }) return null
        if (hookKeys.none { map[it] == true }) {
            return Sanitized(map as Map<Any?, Any?>, applied = false)
        }
        val sanitized = LinkedHashMap<Any?, Any?>(map)
        hookKeys.forEach { key ->
            if (sanitized[key] == true) sanitized[key] = false
        }
        return Sanitized(sanitized, applied = true)
    }
}
