package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.ModernFrameworkStatus

/**
 * libxposed Service API 没有标准管理器启动接口。这里只返回当前设备上能够解析且允许
 * 普通模块应用启动的显式入口；寄生管理器未导出时保持无入口，不猜测或强行绕过权限。
 */
internal object FrameworkManagerLauncher {
    private const val LSPOSED_MANAGER_PACKAGE = "org.lsposed.manager"
    private const val VECTOR_HOST_PACKAGE = "com.android.shell"
    private const val VECTOR_MANAGER_ACTIVITY = "com.android.shell.BugreportWarningActivity"
    private const val MANAGER_CATEGORY = "org.lsposed.manager.LAUNCH_MANAGER"

    fun resolve(context: Context, status: ModernFrameworkStatus): Intent? {
        val standalone = runCatching {
            context.packageManager.getLaunchIntentForPackage(LSPOSED_MANAGER_PACKAGE)
        }.getOrNull()
        val parasitic = Intent(Intent.ACTION_MAIN)
            .addCategory(MANAGER_CATEGORY)
            .setComponent(ComponentName(VECTOR_HOST_PACKAGE, VECTOR_MANAGER_ACTIVITY))

        val candidates = if (status.name.contains("vector", ignoreCase = true)) {
            listOfNotNull(parasitic, standalone)
        } else {
            listOfNotNull(standalone, parasitic)
        }
        return candidates.firstOrNull { intent -> isLaunchable(context, intent) }
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun isLaunchable(context: Context, intent: Intent): Boolean {
        val resolveInfo = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.resolveActivity(
                    intent,
                    PackageManager.ResolveInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.resolveActivity(intent, 0)
            }
        }.getOrNull() ?: return false
        val activityInfo = resolveInfo.activityInfo ?: return false
        return activityInfo.exported || activityInfo.applicationInfo.uid == Process.myUid()
    }
}
