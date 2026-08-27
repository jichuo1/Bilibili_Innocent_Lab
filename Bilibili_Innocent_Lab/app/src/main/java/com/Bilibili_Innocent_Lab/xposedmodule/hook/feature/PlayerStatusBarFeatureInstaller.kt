package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.app.Activity
import android.graphics.Color
import android.view.View
import android.view.WindowInsetsController
import android.view.WindowManager
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.highcapable.betterandroid.system.extension.tool.AndroidVersion

/** 仅在 Adapter 确认的视频详情 Activity 生命周期末尾应用透明播放器状态栏。 */
internal class PlayerStatusBarFeatureInstaller(
    private val enabled: Boolean,
    private val points: VersionAdapter.PlayerStatusBarPoints?
) : FeatureInstaller {

    override val id: String = ID

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) {
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val methods = points?.onCreateMethods?.takeIf { it.isNotEmpty() }
            ?: return missing(environment, "missing-adapter-point")

        var installed = 0
        methods.forEachIndexed { index, point ->
            runCatching {
                environment.registrar.adapted("player.status_bar.create.$index", point) {
                    after {
                        val activity = instance as? Activity ?: return@after
                        applyTransparentStatusBar(activity)
                    }
                }
                installed += 1
            }.onFailure { throwable ->
                environment.logError(
                    "player_status_bar_$index",
                    "[BIL] 播放器透明状态栏 Hook 注册失败(" +
                        "${point.className}#${point.methodName}): $throwable"
                )
            }
        }
        if (installed == 0) return missing(environment, "registration-failed")
        val status = if (installed == methods.size) "success" else "partial:$installed/${methods.size}"
        environment.reportStatus(CHANNEL_STATUS, status)
        environment.logInfo(
            "player_status_bar_ok",
            "[BIL] 播放器透明状态栏已安装，hooks=$installed/${methods.size}"
        )
        return FeatureInstallResult.Installed(installed)
    }

    @Suppress("DEPRECATION")
    private fun applyTransparentStatusBar(activity: Activity) {
        val window = activity.window ?: return
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        // 仅改目标状态栏，不让控制器接管宿主导航栏与 Insets 生命周期。
        //noinspection ReplaceWithSystemBarsController
        window.statusBarColor = Color.TRANSPARENT
        if (AndroidVersion.isAtLeast(AndroidVersion.R)) {
            //noinspection ReplaceWithSystemBarsController
            window.setDecorFitsSystemWindows(false)
            //noinspection ReplaceWithSystemBarsController
            window.insetsController?.setSystemBarsAppearance(
                0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            )
        } else {
            window.decorView.systemUiVisibility =
                (window.decorView.systemUiVisibility or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN) and
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }
    }

    private fun missing(
        environment: HookEnvironment,
        reason: String
    ): FeatureInstallResult.Skipped {
        environment.reportStatus(CHANNEL_STATUS, reason)
        environment.logError(
            "player_status_bar_missing",
            "[BIL] 播放器透明状态栏适配不完整: $reason"
        )
        return FeatureInstallResult.Skipped(reason)
    }

    companion object {
        const val ID = "player_status_bar"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "player_status_bar_status"
    }
}
