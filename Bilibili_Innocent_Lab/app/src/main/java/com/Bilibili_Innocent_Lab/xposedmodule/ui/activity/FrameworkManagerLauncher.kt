package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.ModernFrameworkStatus
import com.highcapable.betterandroid.system.extension.utils.AndroidVersion

/**
 * libxposed Service API 没有标准管理器启动接口。这里只返回当前设备上能够解析且允许
 * 普通模块应用启动的显式入口；寄生管理器未导出时保持无入口，不猜测或强行绕过权限。
 */
internal object FrameworkManagerLauncher {
    fun resolve(context: Context, status: ModernFrameworkStatus): Intent? {
        for (target in frameworkManagerTargets(status.name)) {
            val intent = runCatching {
                if (target.activityName == null) {
                    context.packageManager.getLaunchIntentForPackage(target.packageName)
                } else {
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(requireNotNull(target.category))
                        .setComponent(ComponentName(target.packageName, target.activityName))
                }
            }.getOrNull() ?: continue
            if (isLaunchable(context, intent)) return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return null
    }

    private fun isLaunchable(context: Context, intent: Intent): Boolean {
        val resolveInfo = runCatching {
            if (AndroidVersion.isAtLeast(AndroidVersion.T)) {
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
        val applicationInfo = activityInfo.applicationInfo ?: return false
        val permissionGranted = activityInfo.permission.isNullOrEmpty() || runCatching {
            context.checkSelfPermission(activityInfo.permission) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        return canLaunchFrameworkManager(
            activityEnabled = activityInfo.enabled,
            applicationEnabled = applicationInfo.enabled,
            exported = activityInfo.exported,
            sameUid = applicationInfo.uid == Process.myUid(),
            permissionGranted = permissionGranted
        )
    }
}
