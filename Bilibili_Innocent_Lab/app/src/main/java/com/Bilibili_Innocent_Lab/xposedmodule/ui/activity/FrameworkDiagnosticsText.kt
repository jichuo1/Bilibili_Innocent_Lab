package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.content.Context
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.highcapable.betterandroid.system.extension.utils.AndroidVersion
import io.github.libxposed.service.XposedService

/** 只格式化已经采集的状态；不做 Binder 或 PackageManager 查询。 */
internal fun frameworkDiagnosticsDetails(
    context: Context,
    name: String,
    version: String?,
    versionCode: Long?,
    properties: Long?,
    connectionId: Long,
    failureCode: String?
): List<String> = buildList {
    if (connectionId > 0L) {
        val unknown = context.getString(R.string.diagnostics_value_unknown)
        add(context.getString(
            R.string.diagnostics_framework_build,
            version ?: unknown, versionCode?.toString() ?: unknown, connectionId
        ))
        fun flag(mask: Long): String = context.getString(when {
            properties == null -> R.string.diagnostics_value_unknown
            properties and mask != 0L -> R.string.diagnostics_value_yes
            else -> R.string.diagnostics_value_no
        })
        add(context.getString(
            R.string.diagnostics_framework_properties,
            flag(XposedService.PROP_CAP_REMOTE), flag(XposedService.PROP_CAP_SYSTEM),
            flag(XposedService.PROP_RT_API_PROTECTION)
        ))
    }
    if (failureCode != null) {
        add(context.getString(R.string.diagnostics_framework_metadata_failure, failureCode))
    }
    if (name.contains("vector", ignoreCase = true)) {
        if (!AndroidVersion.isAtLeast(AndroidVersion.P)) {
            add(context.getString(R.string.diagnostics_vector_api27))
        }
        if (versionCode == 3080L) {
            add(context.getString(R.string.diagnostics_vector_3080))
        }
    }
}
