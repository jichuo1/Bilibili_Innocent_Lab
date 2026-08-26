package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.highcapable.yukihookapi.hook.core.YukiMemberHookCreator.MemberHookCreator

internal interface HookRegistrar {
    fun first(
        id: String,
        className: String,
        methodName: String,
        block: MemberHookCreator.() -> Unit
    )

    fun all(
        id: String,
        className: String,
        methodName: String,
        block: MemberHookCreator.() -> Unit
    )

    fun exact(
        id: String,
        owner: Class<*>,
        methodName: String,
        vararg parameterTypes: Class<*>,
        block: MemberHookCreator.() -> Unit
    )

    fun adapted(
        id: String,
        point: VersionAdapter.HookPoint,
        block: MemberHookCreator.() -> Unit
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
    val reportStatus: (String, String) -> Unit
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
