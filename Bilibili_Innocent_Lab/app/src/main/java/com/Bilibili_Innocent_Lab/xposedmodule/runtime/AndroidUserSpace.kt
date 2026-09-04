package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process

/**
 * Android 多用户空间（主用户 / 应用分身 / 工作资料 / 次要用户）的单一判定点。
 *
 * 只支持**同包名**的系统级多用户：宿主在分身用户里仍然是 `tv.danmaku.bili`，只是 uid 前缀不同。
 * 改包名克隆（LSPatch/NPatch 多实例、改包工具）与 VirtualApp 类容器不在此范围内——前者被
 * `module.prop` 的 `staticScope=true` 挡在作用域外，后者根本不产生独立的 Android 用户。
 *
 * libxposed Service 102 的 binder 是**框架主动推送**的（`XposedProvider.call("SendBinder")`），
 * 模块侧无法自行绑定或重试。因此"收不到服务"在分身用户里几乎总是"框架没把这个用户下的模块
 * 当成已启用模块"，而不是"框架没装"。这里的判定只用于把这两种情况在 UI 上区分开，不参与
 * 任何授权决策：授权链仍然只认 Remote Preferences 的完整快照。
 */
internal object AndroidUserSpace {

    /** Android uid 编码为 userId * 100000 + appId。 */
    const val UIDS_PER_USER = 100_000

    const val PRIMARY_USER_ID = 0

    /** 只处理系统分配的非负 uid；异常输入一律归为主用户，不制造假的分身判定。 */
    fun userIdFromUid(uid: Int): Int =
        if (uid < 0) PRIMARY_USER_ID else uid / UIDS_PER_USER

    /**
     * 非主用户即视为"可能的分身/工作资料"。平台不向普通应用暴露"这是不是应用双开"的
     * 权威接口，因此这里只做可能性判断，文案也必须保持"可能"的口径。
     */
    fun isSecondaryOrCloneProfile(userId: Int): Boolean = userId != PRIMARY_USER_ID

    /** 目标不可见时返回 null；不猜测"大概是同一个用户"。 */
    fun resolveSameUser(moduleUserId: Int, targetUserId: Int?): Boolean? =
        targetUserId?.let { it == moduleUserId }

    /** 当前进程所属的 Android userId。 */
    fun currentUserId(): Int = userIdFromUid(Process.myUid())

    /**
     * 当前用户下目标包的 uid；不可见、未安装或查询被拒绝时返回 null。
     * 只查当前用户，不枚举其他用户，也不请求跨用户权限。
     */
    @Suppress("DEPRECATION")
    fun targetUidInCurrentUser(context: Context, packageName: String): Int? = runCatching {
        context.packageManager.getApplicationInfo(
            packageName,
            PackageManager.MATCH_DISABLED_COMPONENTS
        ).uid
    }.getOrNull()

    /**
     * 一次性采集模块与目标的用户空间关系。调用方应缓存结果：这是一次 PackageManager 查询，
     * 不应放进每帧或每次状态回调都会走的渲染路径。
     */
    fun capture(context: Context, targetPackage: String): AndroidUserSpaceSnapshot {
        val moduleUserId = currentUserId()
        val targetUserId = targetUidInCurrentUser(context, targetPackage)?.let(::userIdFromUid)
        return AndroidUserSpaceSnapshot(
            moduleUserId = moduleUserId,
            targetUserId = targetUserId,
            sameUser = resolveSameUser(moduleUserId, targetUserId)
        )
    }
}

/** 有界只读快照：只保留 userId 与同用户判断，不保留 uid、包列表或 Binder 信息。 */
internal data class AndroidUserSpaceSnapshot(
    val moduleUserId: Int,
    val targetUserId: Int?,
    val sameUser: Boolean?
) {
    val possibleSecondaryOrCloneProfile: Boolean
        get() = AndroidUserSpace.isSecondaryOrCloneProfile(moduleUserId)

    companion object {
        /** 尚未采集时的中性值：按主用户处理，不产生任何提示。 */
        val PRIMARY = AndroidUserSpaceSnapshot(
            moduleUserId = AndroidUserSpace.PRIMARY_USER_ID,
            targetUserId = null,
            sameUser = null
        )
    }
}
