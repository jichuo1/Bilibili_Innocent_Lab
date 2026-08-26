package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry

/** 每个功能安装器的最小边界；安装失败不影响后续功能。 */
internal interface FeatureInstaller {
    val id: String

    fun install(environment: HookEnvironment): FeatureInstallResult
}

internal data class HookEnvironment(
    val processName: String,
    val classLoader: ClassLoader?,
    val hookPoints: HookPointRegistry,
    val log: (String, Throwable?) -> Unit
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
                        environment.log("Feature installer failed: ${installer.id}", throwable)
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
