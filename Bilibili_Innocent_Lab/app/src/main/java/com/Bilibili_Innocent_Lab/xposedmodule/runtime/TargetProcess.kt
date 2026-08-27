package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Process
import com.highcapable.betterandroid.system.extension.tool.AndroidVersion

/** Android 8.1–current compatible process-name lookup for target-process guards. */
object TargetProcess {

    fun isMainProcess(context: Context, packageName: String): Boolean =
        currentProcessName(context) == packageName

    @Suppress("DEPRECATION")
    private fun currentProcessName(context: Context): String? {
        if (AndroidVersion.isAtLeast(AndroidVersion.P)) {
            return Application.getProcessName()
        }
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return null
        val pid = Process.myPid()
        return manager.runningAppProcesses
            ?.firstOrNull { it.pid == pid }
            ?.processName
    }
}
