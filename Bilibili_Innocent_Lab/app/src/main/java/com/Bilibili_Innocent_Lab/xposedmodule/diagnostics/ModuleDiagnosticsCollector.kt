package com.Bilibili_Innocent_Lab.xposedmodule.diagnostics

import android.annotation.SuppressLint
import android.content.Context
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.ActivationDisplayState
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostRuntimeDiagnosticsSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootDisplayState
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootSupportState
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootSupportStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.RestorePolicy
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.settings.modulePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigPublishState
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigStore
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.runtime.SkinSessionDiagnostics
import com.highcapable.betterandroid.system.extension.component.versionCodeCompat
import com.highcapable.betterandroid.system.extension.utils.AndroidVersion

internal object ModuleDiagnosticsCollector {
    @SuppressLint("UseKtx")
    fun collect(
        context: Context,
        skin: SkinSessionDiagnostics?,
        frameworkCheckPending: Boolean = false,
        hostRuntime: HostRuntimeDiagnosticsSnapshot? = null,
        nowEpochMs: Long = System.currentTimeMillis()
    ): ModuleDiagnosticSnapshot {
        val appContext = context.applicationContext ?: context
        @Suppress("DEPRECATION")
        val targetInfo = runCatching {
            appContext.packageManager.getPackageInfo(NoRootSupportState.TARGET_PACKAGE, 0)
        }.getOrNull()
        val targetVersionCode = targetInfo?.versionCodeCompat ?: 0L
        val targetUpdateTime = targetInfo?.lastUpdateTime ?: 0L
        val noRootStatus = NoRootSupportStore.readStatus(appContext)
        val noRootState = NoRootSupportState.displayState(
            sdkInt = AndroidVersion.code,
            status = noRootStatus,
            currentSnapshot = NoRootSupportStore.readSnapshot(appContext),
            currentTargetVersionCode = targetVersionCode,
            currentTargetUpdateTime = targetUpdateTime
        )
        val framework = RemoteHookConfigStore.status()
        val activation = NoRootSupportState.activationDisplayState(
            rootActive = framework.capable,
            frameworkCheckPending = frameworkCheckPending && !framework.connected,
            displayState = noRootState
        )
        val remote = RemoteHookConfigStore.diagnostics()
        val preferences = appContext.modulePreferences()
        val automaticCount = SettingsCatalog.specs.count {
            it.restorePolicy == RestorePolicy.AUTOMATIC
        }
        val manualCount = SettingsCatalog.specs.size - automaticCount
        val requestedSkin = skin?.requestedSkin?.name ?: "NOT_PREPARED"
        val effectiveSkin = skin?.effectiveSkin?.name ?: "NOT_PREPARED"
        return ModuleHealthEvaluator.evaluate(
            ModuleDiagnosticInputs(
                collectedAtEpochMs = nowEpochMs.coerceAtLeast(1L),
                moduleVersionName = BuildConfig.VERSION_NAME,
                moduleVersionCode = BuildConfig.VERSION_CODE.toLong(),
                debugBuild = BuildConfig.DEBUG,
                targetInstalled = targetInfo != null,
                targetVersionName = targetInfo?.versionName,
                targetVersionCode = targetVersionCode,
                targetLastUpdateAtEpochMs = targetUpdateTime,
                frameworkConnected = framework.connected,
                frameworkCapable = framework.capable,
                frameworkName = framework.name,
                frameworkApiVersion = framework.apiVersion,
                remotePublishState = remote.state.toDiagnosticState(),
                remoteLastAttemptAtEpochMs = remote.lastAttemptAtEpochMs,
                remoteLastSuccessAtEpochMs = remote.lastSuccessAtEpochMs,
                remoteGeneration = remote.generation,
                remoteFailureCode = remote.failureCode,
                remotePublishPending = remote.publishPending,
                activationState = activation.toDiagnosticState(),
                noRootDesiredEnabled = noRootStatus.desiredEnabled,
                noRootState = noRootState.toDiagnosticState(),
                requestedSkin = requestedSkin,
                effectiveSkin = effectiveSkin,
                skinFallbackCode = skin?.fallbackReason,
                liquidBackendName = skin?.liquidBackendName,
                settingsCatalogVersion = SettingsCatalog.CATALOG_VERSION,
                settingsTotalCount = SettingsCatalog.specs.size,
                settingsAutomaticCount = automaticCount,
                settingsManualCount = manualCount,
                loggingEnabled = preferences.getBoolean(HookEntry.PREF_LOG_ENABLED, true),
                verboseLogging = preferences.getString(
                    HookEntry.PREF_LOG_LEVEL,
                    HookEntry.LOG_LEVEL_COMPLETE
                ) != HookEntry.LOG_LEVEL_MINIMAL,
                hostRuntimeReceiptAvailable = hostRuntime != null,
                hostRuntimeCapturedAtEpochMs = hostRuntime?.capturedAtEpochMs ?: 0L,
                hostAdaptedFeatureCount = hostRuntime?.adaptedFeatureCount ?: 0,
                hostObservedFeatureCount = hostRuntime?.observedFeatureCount ?: 0,
                hostAppliedFeatureCount = hostRuntime?.appliedFeatureCount ?: 0,
                hostFeatures = hostRuntime?.features.orEmpty().map { feature ->
                    DiagnosticHostFeature(
                        featureId = feature.featureId,
                        evidence = when {
                            feature.appliedCount > 0L -> DiagnosticEvidence.APPLIED
                            feature.observedCount > 0L -> DiagnosticEvidence.OBSERVED
                            feature.adaptedCount > 0L -> DiagnosticEvidence.ADAPTED
                            else -> DiagnosticEvidence.NOT_AVAILABLE
                        }
                    )
                }
            )
        )
    }

    private fun RemoteHookConfigPublishState.toDiagnosticState() =
        DiagnosticRemotePublishState.valueOf(name)

    private fun ActivationDisplayState.toDiagnosticState() =
        DiagnosticActivationState.valueOf(name)

    private fun NoRootDisplayState.toDiagnosticState() =
        DiagnosticNoRootState.valueOf(name)
}
