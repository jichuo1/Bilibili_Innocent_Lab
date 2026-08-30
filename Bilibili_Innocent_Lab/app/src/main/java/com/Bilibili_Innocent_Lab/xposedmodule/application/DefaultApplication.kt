package com.Bilibili_Innocent_Lab.xposedmodule.application

import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.highcapable.yukihookapi.hook.xposed.application.ModuleApplication
import com.highcapable.yukihookapi.hook.factory.prefs
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsImportApplier
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.YukiModuleSettingsStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsConsentStore
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootUpgradeRecoveryCoordinator

class DefaultApplication : ModuleApplication() {

    override fun onCreate() {
        super.onCreate()
        /**
         * 跟随系统夜间模式
         * Follow system night mode
        */
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        val termsDecision = UserTermsConsentStore.readOrInitialize(applicationContext)
        val modulePrefs = runCatching { prefs() }.onFailure { throwable ->
            Log.w("BilibiliInnocentLab", "open module prefs for terms mirror failed", throwable)
        }.getOrNull()
        if (modulePrefs != null &&
            !UserTermsConsentStore.syncHookMirror(modulePrefs, termsDecision)
        ) {
            Log.w("BilibiliInnocentLab", "sync terms hook mirror failed")
        }
        // 条款未授权时除关闭态授权镜像外不触发任何配置补写。
        if (!termsDecision.isAuthorized || modulePrefs == null) return
        // 若上次导入在 prefs 提交后、自由复制镜像落盘前中断，启动时幂等补写。
        runCatching {
            check(
                SettingsImportApplier.recoverPending(
                    applicationContext,
                    YukiModuleSettingsStore(modulePrefs)
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
