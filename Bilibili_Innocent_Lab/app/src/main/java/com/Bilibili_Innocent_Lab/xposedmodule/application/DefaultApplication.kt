package com.Bilibili_Innocent_Lab.xposedmodule.application

import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.highcapable.yukihookapi.hook.xposed.application.ModuleApplication
import com.highcapable.yukihookapi.hook.factory.prefs
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsImportApplier
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.YukiModuleSettingsStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsConsentStore

class DefaultApplication : ModuleApplication() {

    override fun onCreate() {
        super.onCreate()
        /**
         * 跟随系统夜间模式
         * Follow system night mode
         */
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        // 条款未授权时不触发任何配置补写；同意后 MainActivity 仍会幂等恢复。
        if (!UserTermsConsentStore.readOrInitialize(applicationContext).isAuthorized) return
        // 若上次导入在 prefs 提交后、自由复制镜像落盘前中断，启动时幂等补写。
        runCatching {
            check(
                SettingsImportApplier.recoverPending(
                    applicationContext,
                    YukiModuleSettingsStore(prefs())
                )
            ) { "pending settings import is not fully recovered" }
        }.onFailure { throwable ->
            Log.w("BilibiliInnocentLab", "recover pending settings import failed", throwable)
        }
    }
}
