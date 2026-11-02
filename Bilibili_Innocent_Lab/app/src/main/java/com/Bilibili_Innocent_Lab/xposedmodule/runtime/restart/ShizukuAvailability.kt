package com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart

import android.content.Context
import android.content.pm.PackageManager
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.getPackageInfoOrNull
import rikka.shizuku.Shizuku

/** 只读能力探测：不请求授权、不绑定 UserService，也不执行宿主操作。 */
internal object ShizukuAvailability {
    enum class Status { MISSING, STOPPED, UNSUPPORTED, UNAUTHORIZED, DENIED, READY, DISCONNECTED }
    enum class Permission { GRANTED, REQUESTABLE, DENIED }
    data class Snapshot(val status: Status, val uid: Int? = null) {
        val visible: Boolean get() = status != Status.MISSING
        val recommended: Boolean get() = status in setOf(Status.READY, Status.UNAUTHORIZED, Status.DENIED)
    }

    fun read(context: Context): Snapshot = runCatching {
        if (!Shizuku.pingBinder()) {
            return@runCatching classify(context.packageManager.getPackageInfoOrNull(PrivilegedRestartController.MANAGER) != null, false)
        }
        val version = if (Shizuku.isPreV11()) 0 else Shizuku.getVersion()
        val uid = Shizuku.getUid()
        if (version < 13 || uid !in setOf(0, 2000)) return@runCatching Snapshot(Status.UNSUPPORTED)
        val permission = when {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> Permission.GRANTED
            Shizuku.shouldShowRequestPermissionRationale() -> Permission.DENIED
            else -> Permission.REQUESTABLE
        }
        classify(true, true, version, uid, permission)
    }.getOrDefault(Snapshot(Status.DISCONNECTED))

    internal fun classify(installed: Boolean, binderAlive: Boolean, version: Int = 0, uid: Int? = null,
                          permission: Permission = Permission.REQUESTABLE): Snapshot {
        if (!binderAlive) return Snapshot(if (installed) Status.STOPPED else Status.MISSING)
        if (version < 13 || uid !in setOf(0, 2000)) return Snapshot(Status.UNSUPPORTED)
        return Snapshot(when (permission) {
            Permission.GRANTED -> Status.READY
            Permission.REQUESTABLE -> Status.UNAUTHORIZED
            Permission.DENIED -> Status.DENIED
        }, uid)
    }
}
