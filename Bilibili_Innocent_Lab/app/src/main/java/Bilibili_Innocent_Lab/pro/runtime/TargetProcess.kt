package Bilibili_Innocent_Lab.pro.runtime

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process

/** Android 8.1–current compatible process-name lookup for target-process guards. */
object TargetProcess {

    fun isMainProcess(context: Context, packageName: String): Boolean =
        currentProcessName(context) == packageName

    @Suppress("DEPRECATION")
    private fun currentProcessName(context: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
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
