package com.Bilibili_Innocent_Lab.xposedmodule.diagnostics

import android.content.Context
import android.os.Looper
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.*
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.getPackageInfoOrNull
import io.github.jichuo1.meguviz.android.extensions.versionCodeCompat

/** Only explicit local support exports call this collector; ordinary reports and telemetry stay unchanged. */
internal object NPatchSupportBundleCollector {
    fun collect(context: Context, snapshot: ModuleDiagnosticSnapshot): ByteArray {
        check(Looper.myLooper() != Looper.getMainLooper())
        require(snapshot.inputs.moduleVersionCode == BuildConfig.VERSION_CODE.toLong())
        requireCurrentTarget(context, snapshot.inputs)
        val observation = NPatchProcessDiagnostics.observe(context, includeMedia = true)
        val current = observation.copy(processes = NPatchStartupHistory.enrich(context, observation.processes))
        val document = NPatchSupportDocument(System.currentTimeMillis(), NPatchRuntimeProfiles.environment(context),
            NPatchEmbeddedBuild.read(context), NPatchScopeProbe.probeReachability(context), current)
        // The system picker may have stayed open during a package update. Do not mix installations.
        requireCurrentTarget(context, snapshot.inputs)
        return NPatchSupportArchive.encode(snapshot, document)
    }

    private fun requireCurrentTarget(context: Context, input: ModuleDiagnosticInputs) {
        val target = context.packageManager.getPackageInfoOrNull(NoRootSupportState.TARGET_PACKAGE)
        require(input.targetInstalled == (target != null))
        if (target != null) require(target.lastUpdateTime == input.targetLastUpdateAtEpochMs &&
            target.versionCodeCompat == input.targetVersionCode)
    }
}
