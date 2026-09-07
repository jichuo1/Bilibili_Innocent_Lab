package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import java.lang.reflect.Constructor

internal interface HookRegistrar {
    fun first(
        id: String,
        className: String,
        methodName: String,
        block: ModernMemberHookCreator.() -> Unit
    )

    fun all(
        id: String,
        className: String,
        methodName: String,
        block: ModernMemberHookCreator.() -> Unit
    )

    fun exact(
        id: String,
        owner: Class<*>,
        methodName: String,
        vararg parameterTypes: Class<*>,
        block: ModernMemberHookCreator.() -> Unit
    )

    fun adapted(
        id: String,
        point: VersionAdapter.HookPoint,
        block: ModernMemberHookCreator.() -> Unit
    )

    fun constructor(
        id: String,
        constructor: Constructor<*>,
        block: ModernMemberHookCreator.() -> Unit
    )
}

/** 每个功能安装器的最小边界；安装失败不影响后续功能。 */
internal interface FeatureInstaller {
    val id: String
    /** Enabled leaf capabilities expected to produce independent installation evidence. */
    val capabilityIds: List<String> get() = emptyList()

    fun install(environment: HookEnvironment): FeatureInstallResult
}

internal data class HookEnvironment(
    val processName: String,
    val classLoader: ClassLoader?,
    val hookPoints: HookPointRegistry,
    val registrar: HookRegistrar,
    val logInfo: (String, String) -> Unit,
    val logError: (String, String) -> Unit,
    val reportStatus: (String, String) -> Unit,
    /** 将一次性初始化任务排到 Application.attach 返回后的主线程队列；null = 不主动预解析。 */
    val postToMain: ((() -> Unit) -> Unit)? = null,
    /** 写“我的”页可屏蔽项快照（宿主剪枝时产出，供模块 UI 勾选列表读取）。null = 不启用快照。 */
    /**
     * 面判别位（`MineComponentSnapshotCodec.SURFACE_*`）+ 不可变纯数据；编码和写盘由后台桥承担。
     * 2026-09-04 起由单面扩展为多面：我的页 / 底栏 / 首页 Tab / 首页组件共用这一条通道。
     */
    val writeScanSnapshot: ((String, ScanSnapshotContent) -> Boolean)? = null,
    /**
     * 仅上报功能阶段；宿主桥对每项阶段只保留首次证据，null 时不影响 Hook。禁止传递设置值、
     * 卡片/评论正文、宿主成员名或异常文本。
     */
    val runtimeEvidence: ((String, FeatureRuntimeStage, Int) -> Unit)? = null,
    /** 安装器结束后上报一次结构化结果；诊断异常不能反向影响 Hook 安装链。 */
    val installationEvidence: ((FeatureInstallRecord) -> Unit)? = null,
    val capabilityEvidence: ((String, FeatureInstallResult) -> Unit)? = null,
    val runtimePhase: (() -> Boolean)? = null
)

internal sealed interface FeatureInstallResult {
    data class Installed(val hookCount: Int = 1, val complete: Boolean = true) : FeatureInstallResult
    data object Unverified : FeatureInstallResult
    data class Skipped(val reason: String) : FeatureInstallResult {
        val reasonCode: FeatureSkipReason = FeatureSkipReason.fromRaw(reason)
    }
}

/** 把存量安装器的有界 reason 字符串收敛为稳定诊断码，不改变原日志和安装分支。 */
internal enum class FeatureSkipReason {
    DISABLED,
    NOT_APPLICABLE_PROCESS,
    NOT_APPLICABLE_HOST,
    MISSING_ADAPTER_POINT,
    MISSING_HOST_STRUCTURE,
    AMBIGUOUS_HOST_STRUCTURE,
    REGISTRATION_FAILED,
    NO_SAFE_HOOK_POINT,
    OTHER;

    companion object {
        fun fromRaw(raw: String): FeatureSkipReason {
            val reason = raw.trim().lowercase().replace('_', '-')
            return when {
                reason == "disabled" || reason.startsWith("no-types-enabled") ||
                    reason.startsWith("no-rules") -> DISABLED
                reason == "non-main-process" -> NOT_APPLICABLE_PROCESS
                reason == "not-applicable-host" -> NOT_APPLICABLE_HOST
                reason.contains("ambiguous") -> AMBIGUOUS_HOST_STRUCTURE
                reason.contains("registration-failed") ||
                    reason.contains("register-failed") -> REGISTRATION_FAILED
                reason.contains("missing-adapter") -> MISSING_ADAPTER_POINT
                reason.startsWith("no-legacy") -> MISSING_HOST_STRUCTURE
                reason.contains("missing") -> MISSING_HOST_STRUCTURE
                reason.contains("no-hook") || reason.contains("hook-point") ->
                    NO_SAFE_HOOK_POINT
                reason.contains("failed") -> REGISTRATION_FAILED
                else -> OTHER
            }
        }
    }
}

internal data class FeatureInstallRecord(
    val id: String,
    val result: FeatureInstallResult?,
    val failure: Throwable?
)

/** 保持安装顺序并隔离单功能失败，为 HookEntry 逐步拆分提供稳定骨架。 */
internal class FeatureInstallCoordinator(
    private val environment: HookEnvironment
) {
    fun installAll(installers: Iterable<FeatureInstaller>): List<FeatureInstallRecord> =
        installers.map { installer ->
            val capabilityLock = Any()
            val reported = hashSetOf<String>()
            val installedPhase = java.util.concurrent.atomic.AtomicBoolean(false)
            val planned = runCatching { installer.capabilityIds.toSet() }.getOrDefault(emptySet())
            val scoped = environment.copy(
                runtimePhase = { installedPhase.get() },
                logError = { key, message ->
                    if (installedPhase.get()) environment.reportRuntimeEvidence(installer.id, FeatureRuntimeStage.ERROR)
                    environment.logError(key, message)
                },
                capabilityEvidence = { id, result ->
                if (id in planned) synchronized(capabilityLock) {
                    environment.installationEvidence?.invoke(FeatureInstallRecord(id, result, null))
                    reported += id
                }
            })
            val record = runCatching { installer.install(scoped) }
                .fold(
                    onSuccess = { result ->
                        FeatureInstallRecord(installer.id, result, failure = null)
                    },
                    onFailure = { throwable ->
                        environment.logError(
                            "feature_installer_${installer.id}",
                            "[BIL] 功能安装器 ${installer.id} 失败，已隔离并继续: $throwable"
                        )
                        FeatureInstallRecord(installer.id, result = null, failure = throwable)
                    }
                )
            runCatching { environment.installationEvidence?.invoke(record) }
                .onFailure { throwable ->
                    environment.logError(
                        "feature_diagnostics_${installer.id}",
                        "[BIL] 功能安装诊断上报失败，业务 Hook 状态不受影响: $throwable"
                    )
                }
            for (id in planned) {
                // A group-wide failure before completion blocks its remaining declared paths.
                // A successful parent NEVER supplies success for an unverified leaf.
                synchronized(capabilityLock) {
                    if (id !in reported) {
                        val outcome = when (val result = record.result) {
                            is FeatureInstallResult.Skipped -> result
                            else -> FeatureInstallResult.Unverified
                        }
                        runCatching {
                            environment.installationEvidence?.invoke(
                                if (record.failure != null) FeatureInstallRecord(id, null, record.failure)
                                else FeatureInstallRecord(id, outcome, null)
                            )
                        }
                    }
                }
            }
            installedPhase.set(true)
            record
        }
}

/** Installation-time only; evidence collection failures must not affect business hooks. */
internal fun HookEnvironment.reportCapability(id: String, result: FeatureInstallResult) {
    runCatching { capabilityEvidence?.invoke(id, result) }
}

/** Call with the leaf's own prerequisites, not the overall parent's success flag. */
internal fun HookEnvironment.reportCapabilityCoverage(
    id: String,
    ready: Boolean,
    installedPaths: Int,
    expectedPaths: Int
) {
    reportCapability(id, when {
        !ready -> FeatureInstallResult.Skipped("missing-capability-dependency")
        installedPaths <= 0 -> FeatureInstallResult.Skipped("registration-failed")
        else -> FeatureInstallResult.Installed(installedPaths, installedPaths >= expectedPaths && expectedPaths > 0)
    })
}

/** Bound once during installation; maps shared helper evidence to its actual leaf. */
internal fun HookEnvironment.forCapabilityRuntime(id: String): HookEnvironment {
    val sink = runtimeEvidence ?: return this
    return copy(
        runtimeEvidence = { _, stage, count -> sink(id, stage, count) },
        logError = { key, message ->
            if (runtimePhase?.invoke() == true) reportRuntimeEvidence(id, FeatureRuntimeStage.ERROR)
            logError(key, message)
        }
    )
}

internal class FunctionalFeatureInstaller(
    override val id: String,
    private val action: (HookEnvironment) -> FeatureInstallResult
) : FeatureInstaller {
    override fun install(environment: HookEnvironment): FeatureInstallResult = action(environment)
}
