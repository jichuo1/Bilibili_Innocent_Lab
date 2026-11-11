package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchMode
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchRuntimeProfile
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootDisplayState
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.ScopeRequestResult

/** 免 Root 状态行下方那一个按钮要做什么。 */
internal enum class NoRootActionKind { NONE, VERIFY, JOIN_SCOPE, OPEN_MANAGER }

internal object NoRootActionPolicy {
    /**
     * 只在需要用户出手时给一个入口。`SCOPE_MISSING` 且宿主确实是 Manager 模式修补时，
     * 才提供"加入作用域"：嵌入模式的作用域固定在 APK 内，宿主未修补时加入作用域没有意义。
     */
    fun kind(state: NoRootDisplayState, patch: NPatchRuntimeProfile): NoRootActionKind = when (state) {
        NoRootDisplayState.UNCONFIRMED -> NoRootActionKind.VERIFY
        NoRootDisplayState.NOT_RUNNING, NoRootDisplayState.SCOPE_MISSING -> when {
            patch.mode == NPatchMode.EMBEDDED -> NoRootActionKind.VERIFY
            state == NoRootDisplayState.SCOPE_MISSING && patch.markerPresent && patch.mode == NPatchMode.MANAGER ->
                NoRootActionKind.JOIN_SCOPE
            else -> NoRootActionKind.OPEN_MANAGER
        }
        else -> NoRootActionKind.NONE
    }

    /** 作用域申请结果对应的提示；成功类结果提醒需要重启宿主才会生效。 */
    fun message(result: ScopeRequestResult): Int = when (result) {
        ScopeRequestResult.Added -> R.string.no_root_scope_result_added
        ScopeRequestResult.AlreadyPresent -> R.string.no_root_scope_result_present
        ScopeRequestResult.ManagerMissing -> R.string.no_root_action_manager_unavailable
        ScopeRequestResult.ModuleNotRegistered -> R.string.no_root_scope_result_not_registered
        ScopeRequestResult.ConnectionTimeout -> R.string.no_root_scope_result_timeout
        is ScopeRequestResult.Failure -> R.string.no_root_scope_result_failed
    }
}
