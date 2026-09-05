package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

internal data class FrameworkManagerTarget(
    val packageName: String,
    val activityName: String? = null,
    val category: String? = null
)

/** 已识别框架只打开它自己的管理器；未知框架只尝试真正安装的独立入口。 */
internal fun frameworkManagerTargets(frameworkName: String): List<FrameworkManagerTarget> {
    val vector = FrameworkManagerTarget("org.matrix.vector.manager")
    val lsposed = FrameworkManagerTarget("org.lsposed.manager")
    val manager = when {
        frameworkName.contains("vector", ignoreCase = true) -> vector
        frameworkName.contains("lsposed", ignoreCase = true) -> lsposed
        else -> return listOf(lsposed, vector)
    }
    return listOf(
        manager,
        FrameworkManagerTarget(
            packageName = "com.android.shell",
            activityName = "com.android.shell.BugreportWarningActivity",
            category = "${manager.packageName}.LAUNCH_MANAGER"
        )
    )
}

internal fun canLaunchFrameworkManager(
    activityEnabled: Boolean,
    applicationEnabled: Boolean,
    exported: Boolean,
    sameUid: Boolean,
    permissionGranted: Boolean
): Boolean = activityEnabled && applicationEnabled &&
    (sameUid || exported && permissionGranted)
