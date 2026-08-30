package com.Bilibili_Innocent_Lab.xposedmodule.application

import android.app.Application
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsImportApplier
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.ModuleSettingsStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsConsentStore
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootUpgradeRecoveryCoordinator

class DefaultApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        /**
         * 跟随系统夜间模式
         * Follow system night mode
        */
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        val termsDecision = UserTermsConsentStore.readOrInitialize(applicationContext)
        RemoteHookConfigStore.initialize(applicationContext, termsDecision).also(
            RemoteHookConfigStore::logFailure
        )
        val modulePrefs = runCatching { prefs() }.onFailure { throwable ->
            Log.w("BilibiliInnocentLab", "open module settings failed", throwable)
        }.getOrNull()
        // 条款未授权时除 Remote Preferences 关闭态配置外不触发任何派生状态补写。
        if (!termsDecision.isAuthorized || modulePrefs == null) return
        // 若上次导入在 prefs 提交后、自由复制镜像落盘前中断，启动时幂等补写。
        runCatching {
            check(
                SettingsImportApplier.recoverPending(
                    applicationContext,
                    ModuleSettingsStore(modulePrefs)
                )
            ) { "pending settings import is not fully recovered" }
        }.onFailure { throwable ->
            Log.w("BilibiliInnocentLab", "recover pending settings import failed", throwable)
        }
        // 这里只登记进程级依赖；覆盖升级 Receiver 或宿主完成当前严格快照查询后
        // 再启动后台恢复，避免 Application 冷启动时与宿主 800ms 查询争用快照锁。
        NoRootUpgradeRecoveryCoordinator.initialize(
            context = applicationContext,
            bridge = modulePrefs,
            authorized = termsDecision.isAuthorized
        )
    }
}
