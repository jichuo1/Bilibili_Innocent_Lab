package com.Bilibili_Innocent_Lab.xposedmodule.settings.terms

import android.content.Context
import android.os.Process
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.AndroidUserSpace
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigPublishState
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigStore

internal const val USER_TERMS_TARGET_PACKAGE = "tv.danmaku.bili"
internal const val ANDROID_UIDS_PER_USER = AndroidUserSpace.UIDS_PER_USER
internal const val USER_TERMS_FAILURE_SERVICE_NOT_CONNECTED = "service_not_connected"
internal const val USER_TERMS_FAILURE_API_UNSUPPORTED = "api_unsupported"
internal const val USER_TERMS_FAILURE_REMOTE_PUBLISH = "remote_publish_failed"

internal data class UserTermsGateDiagnosticsSnapshot(
    val moduleUid: Int,
    val moduleUserId: Int,
    val possibleSecondaryOrCloneProfile: Boolean,
    val frameworkConnected: Boolean,
    val frameworkName: String,
    val frameworkApiVersion: Int,
    val remoteCapabilityAvailable: Boolean,
    val targetPackageVisible: Boolean,
    val targetUid: Int?,
    val targetUserId: Int?,
    val sameAndroidUser: Boolean?,
    val failureCode: String?
)

internal fun resolveSameAndroidUser(
    moduleUserId: Int,
    targetUserId: Int?
): Boolean? = AndroidUserSpace.resolveSameUser(moduleUserId, targetUserId)

/** Android UID 编码为 userId * 100000 + appId；只处理系统分配的非负 UID。 */
internal fun androidUserIdFromUid(uid: Int): Int = AndroidUserSpace.userIdFromUid(uid)

/**
 * 把发布器内部的有界错误码归一化成条款门禁可直接复制、检索的稳定诊断码。
 * 当前连接状态只作为无显式错误码时的兜底，不暴露异常文本或 Binder 信息。
 */
internal fun resolveUserTermsGateFailureCode(
    explicitFailureCode: String?,
    frameworkConnected: Boolean,
    frameworkCapable: Boolean,
    publishState: RemoteHookConfigPublishState
): String? {
    val normalizedExplicit = when (explicitFailureCode) {
        UserTermsAuthorizationCoordinator.FAILURE_LOCAL_WRITE -> explicitFailureCode
        "service_not_connected" -> USER_TERMS_FAILURE_SERVICE_NOT_CONNECTED
        "remote_preferences_unsupported" -> USER_TERMS_FAILURE_API_UNSUPPORTED
        "publish_failed" -> USER_TERMS_FAILURE_REMOTE_PUBLISH
        null, "" -> null
        else -> USER_TERMS_FAILURE_REMOTE_PUBLISH
    }
    if (normalizedExplicit != null) return normalizedExplicit
    return when {
        !frameworkConnected -> USER_TERMS_FAILURE_SERVICE_NOT_CONNECTED
        !frameworkCapable -> USER_TERMS_FAILURE_API_UNSUPPORTED
        publishState == RemoteHookConfigPublishState.FAILED ->
            USER_TERMS_FAILURE_REMOTE_PUBLISH
        else -> null
    }
}

/**
 * 条款门禁专用的只读环境快照。查询仅发生在模块设置页创建或框架状态变化时，
 * 不访问其他用户、不启动组件，也不进入宿主或 Hook 热路径。
 */
internal object UserTermsGateDiagnostics {
    fun capture(
        context: Context,
        authorizationSnapshot: UserTermsAuthorizationSnapshot?
    ): UserTermsGateDiagnosticsSnapshot {
        val appContext = context.applicationContext ?: context
        val moduleUid = Process.myUid()
        val moduleUserId = userIdOf(moduleUid)
        val targetUid = currentUserTargetUid(appContext)
        val targetUserId = targetUid?.let(::userIdOf)
        val framework = RemoteHookConfigStore.status()
        val remote = RemoteHookConfigStore.diagnostics()
        return UserTermsGateDiagnosticsSnapshot(
            moduleUid = moduleUid,
            moduleUserId = moduleUserId,
            possibleSecondaryOrCloneProfile =
                AndroidUserSpace.isSecondaryOrCloneProfile(moduleUserId),
            frameworkConnected = framework.connected,
            frameworkName = framework.name,
            frameworkApiVersion = framework.apiVersion,
            remoteCapabilityAvailable = framework.connected && framework.capable,
            targetPackageVisible = targetUid != null,
            targetUid = targetUid,
            targetUserId = targetUserId,
            sameAndroidUser = resolveSameAndroidUser(moduleUserId, targetUserId),
            failureCode = resolveUserTermsGateFailureCode(
                explicitFailureCode = authorizationSnapshot?.failureCode
                    ?: remote.failureCode,
                frameworkConnected = framework.connected,
                frameworkCapable = framework.capable,
                publishState = remote.state
            )
        )
    }

    private fun userIdOf(uid: Int): Int = androidUserIdFromUid(uid)

    private fun currentUserTargetUid(context: Context): Int? =
        AndroidUserSpace.targetUidInCurrentUser(context, USER_TERMS_TARGET_PACKAGE)
}
