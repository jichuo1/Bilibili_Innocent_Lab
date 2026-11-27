package com.Bilibili_Innocent_Lab.xposedmodule.agent

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

internal object AgentAccessibility {
    enum class State { CONNECTED, ENABLED_PENDING, DISABLED, UNKNOWN }
    fun state(context: Context): State {
        if (AgentAccessibilityService.connected()) return State.CONNECTED
        return runCatching {
            val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
                ?: return@runCatching State.UNKNOWN
            val component = ComponentName(context, AgentAccessibilityService::class.java)
            val enabled = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
                val service = it.resolveInfo?.serviceInfo
                service != null && ComponentName(service.packageName, service.name) == component
            }
            if (enabled) State.ENABLED_PENDING else State.DISABLED
        }.getOrDefault(State.UNKNOWN)
    }

    /** 只打开系统授权界面，不写 Secure 设置，不自动点击授权。 */
    fun settingsIntent(context: Context): Intent {
        // 使用公开入口，避免 OEM 私有详情页的参数或组件名差异。
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }

    /** API 33+ 侧载安装的应用启用无障碍服务前需要先在应用信息页右上角允许"受限设置"。 */
    fun mayRequireRestrictedSettingsUnlock(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt >= Build.VERSION_CODES.TIRAMISU

    fun shouldSuggestRestrictedSettings(
        sdkInt: Int,
        serviceState: State,
        isSideloaded: Boolean = true
    ): Boolean = mayRequireRestrictedSettingsUnlock(sdkInt) && serviceState == State.DISABLED && isSideloaded

    fun needsRestrictedSettingsGuide(
        context: Context,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): Boolean {
        if (!mayRequireRestrictedSettingsUnlock(sdkInt)) return false
        val currentState = state(context)
        if (currentState != State.DISABLED) return false
        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && sdkInt >= Build.VERSION_CODES.R) {
                context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(context.packageName)
            }
        }.getOrNull()
        // 商店安装或系统包通常不受侧载受限设置阻断；未知来源或手动安装器视为需要引导。
        val isSideloaded = installer == null || installer == "com.android.packageinstaller" ||
            installer == "com.google.android.packageinstaller" || installer == "com.miui.packageinstaller" ||
            installer == "com.android.shell"
        return shouldSuggestRestrictedSettings(sdkInt, currentState, isSideloaded)
    }

    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }

    fun restrictedSettingsGuideIntent(
        context: Context,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): Intent? = if (mayRequireRestrictedSettingsUnlock(sdkInt)) appDetailsIntent(context) else null
}
