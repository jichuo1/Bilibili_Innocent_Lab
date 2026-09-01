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
    val writeMineScanSnapshot: ((String) -> Unit)? = null,
    /**
     * 仅上报功能阶段；宿主桥对每项阶段只保留首次证据，null 时不影响 Hook。禁止传递设置值、
     * 卡片/评论正文、宿主成员名或异常文本。
     */
    val runtimeEvidence: ((String, FeatureRuntimeStage, Int) -> Unit)? = null
)

internal sealed interface FeatureInstallResult {
    data class Installed(val hookCount: Int = 1) : FeatureInstallResult
    data class Skipped(val reason: String) : FeatureInstallResult
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
            runCatching { installer.install(environment) }
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
        }
}

internal class FunctionalFeatureInstaller(
    override val id: String,
    private val action: (HookEnvironment) -> FeatureInstallResult
) : FeatureInstaller {
    override fun install(environment: HookEnvironment): FeatureInstallResult = action(environment)
}
