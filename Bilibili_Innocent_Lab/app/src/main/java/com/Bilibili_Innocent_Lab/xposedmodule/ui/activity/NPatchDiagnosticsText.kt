package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.content.Context
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.ModuleDiagnosticInputs
import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticHostConfigState
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchMode
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchEmbeddedBuildState
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchCompatibility

/** 安装参数与运行回执分别展示；没有回执时不把默认 source=manager 当成运行事实。 */
internal fun nPatchDiagnosticsDetails(context: Context, input: ModuleDiagnosticInputs): String {
    val environment = input.nPatchEnvironment ?: return ""
    val patch = environment.patch
    if (!patch.markerPresent && !input.noRootDesiredEnabled) return ""
    val unknown = context.getString(R.string.npatch_profile_unknown)
    val mode = context.getString(when (patch.mode) {
        NPatchMode.MANAGER -> R.string.npatch_profile_manager
        NPatchMode.EMBEDDED -> R.string.npatch_profile_embedded
        NPatchMode.UNKNOWN -> R.string.npatch_profile_unknown
    })
    return buildList {
        add(context.getString(R.string.npatch_profile_installed, mode,
            patch.signatureLevel?.toString() ?: unknown,
            patch.loaderVersionCode?.toString() ?: unknown,
            patch.loaderApi?.toString() ?: unknown))
        if (com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.EmbeddedModePolicy.isBlocked(
                patch.mode, com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.LspatchMode.UNKNOWN)) {
            add(context.getString(R.string.embedded_mode_unsupported))
            return@buildList
        }
        add(context.getString(R.string.npatch_profile_flags,
            patch.debuggable?.toString() ?: unknown, unknown))
        add(context.getString(R.string.npatch_profile_manager_version,
            environment.managerVersionName ?: unknown,
            environment.managerVersionCode?.toString() ?: unknown))
        add(context.getString(R.string.npatch_profile_host_environment,
            environment.hostVersionName ?: unknown, environment.hostVersionCode?.toString() ?: unknown,
            environment.androidSdk?.toString() ?: unknown,
            environment.deviceAbis.joinToString(", ").ifEmpty { unknown }))
        add(context.getString(when (NPatchCompatibility.assess(environment)) {
            NPatchCompatibility.UNKNOWN -> R.string.npatch_compatibility_unknown
            NPatchCompatibility.BASELINE_PARAMETERS -> R.string.npatch_compatibility_baseline
            NPatchCompatibility.UNVERIFIED_PARAMETERS -> R.string.npatch_compatibility_unverified
            NPatchCompatibility.EMBEDDED_LEVEL_INVALID -> R.string.npatch_compatibility_embed_invalid
        }))
        if (environment.versionMismatch) add(context.getString(R.string.npatch_profile_version_mismatch))
        val versions = input.nPatchObservedModuleVersions.joinToString(", ").ifEmpty { unknown }
        add(when (com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchModuleDrift.assess(
            input.moduleVersionCode, input.nPatchObservedModuleVersions, input.nPatchPreviousModuleVersion, input.nPatchEmbeddedBuild)) {
            com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchModuleDrift.CURRENT_VERSION ->
                context.getString(R.string.npatch_module_version_current, versions, input.moduleVersionCode)
            com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchModuleDrift.RUNTIME_DIFFERENT ->
                context.getString(R.string.npatch_module_version_different, versions, input.moduleVersionCode)
            com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchModuleDrift.EMBEDDED_DIFFERENT ->
                context.getString(R.string.npatch_module_content_different)
            com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchModuleDrift.PREVIOUS_DIFFERENT ->
                context.getString(R.string.npatch_module_version_previous, input.nPatchPreviousModuleVersion,
                    input.nPatchPreviousModuleAt, input.moduleVersionCode)
            else -> context.getString(R.string.npatch_module_version_unknown)
        })
        if (patch.mode == NPatchMode.MANAGER) add(context.getString(R.string.npatch_module_recover_manager))
        if (patch.mode == NPatchMode.EMBEDDED) {
            add(context.getString(R.string.npatch_profile_embed_experimental))
            add(context.getString(R.string.npatch_embed_recovery))
            add(context.getString(when (input.nPatchEmbeddedBuild) {
                NPatchEmbeddedBuildState.MATCHED -> R.string.npatch_embed_build_matched
                NPatchEmbeddedBuildState.DIFFERENT -> R.string.npatch_embed_build_different
                NPatchEmbeddedBuildState.MODULE_MISSING -> R.string.npatch_embed_build_missing
                else -> R.string.npatch_embed_build_unknown
            }))
        }
        if (input.hostRuntimeReceiptAvailable && input.hostConfigState == DiagnosticHostConfigState.ACCEPTED) {
            add(context.getString(R.string.npatch_profile_source, input.hostConfigSource))
        } else add(context.getString(R.string.npatch_profile_source_unknown))
        if (input.noRootSyncDetail?.startsWith("manager_cache_") == true) {
            add(context.getString(R.string.npatch_profile_cache_unavailable))
        }
        add(context.getString(R.string.npatch_manager_health, context.getString(when (input.nPatchManagerReachability) {
            com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchManagerReachability.REACHABLE -> R.string.npatch_manager_reachable
            com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchManagerReachability.NOT_APPLICABLE -> R.string.npatch_manager_not_applicable
            else -> R.string.npatch_profile_unknown
        })))
        add(context.getString(R.string.npatch_process_observation))
        if (input.nPatchProcesses.isEmpty()) add(context.getString(R.string.npatch_process_unknown))
        input.nPatchProcesses.forEach { row ->
            val state = context.getString(when (row.state) {
                com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchProcessState.LOADED -> R.string.npatch_process_loaded
                com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchProcessState.REJECTED -> R.string.npatch_process_rejected
                com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchProcessState.DISABLED -> R.string.npatch_process_disabled
                com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchProcessState.GATED -> R.string.npatch_process_gated
                com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchProcessState.INSTALLED -> R.string.npatch_process_installed
                com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchProcessState.FAILED -> R.string.npatch_process_failed
            })
            add(context.getString(R.string.npatch_process_row, row.processName, row.pid,
                state, row.installedHookCount, row.source))
            val bitness = row.process64Bit?.let { if (it) "64" else "32" } ?: unknown
            add(context.getString(R.string.npatch_process_native, bitness,
                nativeStateText(context, row.dexKitState), nativeStateText(context, row.frameNativeState)))
            val samples = row.startups
            val health = com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchHealth.summarize(samples)
            add(context.getString(R.string.npatch_health_summary, health.observedBoots, health.grantedBoots, health.retryCount))
            add(context.getString(R.string.npatch_startup_history, health.measuredBoots,
                health.p50Ms?.toString() ?: unknown, health.maxMs?.toString() ?: unknown))
            health.routes.forEach { route ->
                add(context.getString(R.string.npatch_health_route, route.route, route.granted, route.completed,
                    route.grantedPercent?.let { "$it%" } ?: unknown))
            }
            add(context.getString(R.string.npatch_health_routes_scope))
            samples.lastOrNull()?.let { latest ->
                add(context.getString(R.string.npatch_startup_latest, latest.admissionMs,
                    latest.installMs?.toString() ?: unknown, latest.attempt - 1, latest.selectedRoute, latest.result))
                add(context.getString(R.string.npatch_startup_routes, latest.routes.joinToString(", ") { "${it.route}=${it.result}" }))
            }
            add(context.getString(R.string.npatch_guard_failures, row.guardedFailures))
            add(context.getString(R.string.npatch_crash_summary, row.support.crashState, row.support.crashes.size, row.support.mediaState))
            row.support.crashes.forEach { crash ->
                add(context.getString(R.string.npatch_crash_row, crash.at, crash.kind, crash.moduleFrame.toString()))
            }
        }
    }.joinToString("\n")
}

private fun nativeStateText(context: Context, state: String): String = context.getString(when (state) {
    "NOT_TRIED", "NOT_PREPARED" -> R.string.npatch_native_not_checked
    "AVAILABLE", "READY" -> R.string.npatch_native_loaded
    "UNAVAILABLE", "FAILED" -> R.string.npatch_native_failed
    "UNSUPPORTED_ABI" -> R.string.npatch_native_unsupported
    "PREPARING", "BUSY" -> R.string.npatch_native_busy
    "RESTART_REQUIRED" -> R.string.npatch_native_restart
    else -> R.string.npatch_profile_unknown
})
