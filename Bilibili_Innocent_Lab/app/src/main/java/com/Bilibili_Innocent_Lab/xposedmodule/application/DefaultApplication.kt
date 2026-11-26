package com.Bilibili_Innocent_Lab.xposedmodule.application

import android.app.Application
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.DoNotInline
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatDelegate
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsImportApplier
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.ModuleSettingsStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsAuthorizationCoordinator
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.FairRunningMemoryCoordinator
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootUpgradeRecoveryCoordinator
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.runtime.ModuleMemoryPressureHub
import com.lumen.coacervation.engine.LumenEngine
import com.lumen.coacervation.engine.LumenEngineConfig
import com.lumen.coacervation.engine.LumenStorageNames
import io.github.jichuo1.meguviz.android.runtime.platform.MemoryBudgetObserver
import io.github.jichuo1.meguviz.memory.android.MemoryPressureAdapter
import io.github.jichuo1.meguviz.memory.android.NativePressureBackend

class DefaultApplication : Application() {

    private companion object {
        const val API_37_2 = 3700002
        const val NATIVE_ARENA_CAPACITY_BYTES = 16L * 1024L * 1024L
        const val NATIVE_MODERATE_BUDGET_BYTES = 8L * 1024L * 1024L
        const val NATIVE_CRITICAL_BUDGET_BYTES = 4L * 1024L * 1024L
        const val NATIVE_ARENA_CHUNK_BYTES = 64L * 1024L
    }

    private var memoryPressureAdapter: MemoryPressureAdapter? = null
    private var memoryBudgetObserver: MemoryBudgetObserver? = null
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    override fun onCreate() {
        super.onCreate()
        LumenEngine.configure(
            LumenEngineConfig(
                LumenStorageNames(
                    skinPreferences = "ui_skin_preferences",
                    realtimeCapturePreferences = "ui_liquid_effect_preferences",
                    backgroundPreferences = "liquid_background_preferences",
                    backgroundAssetDirectory = "ui_skin_background"
                )
            )
        )
        // 公平内存必须在条款早退之前登记：条款页同样可能被系统 TRIM/KILL。
        // 只服务本进程；不要搬到 HookEntry / 宿主 Application。
        FairRunningMemoryCoordinator.initialize(
            context = this,
            onTrim = { ModuleMemoryPressureHub.releaseGraphics() },
            onPersist = { ModuleMemoryPressureHub.persistForImminentKill() }
        )
        installMemoryPressureMonitoring()
        /**
         * 跟随系统夜间模式
         * Follow system night mode
        */
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        val termsDecision = UserTermsAuthorizationCoordinator
            .initialize(applicationContext)
            .decision
        val modulePrefs = runCatching { prefs() }.onFailure { throwable ->
            Log.w("BilibiliInnocentLab", "open module settings failed", throwable)
        }.getOrNull()
        // 条款未授权时除 Remote Preferences 关闭态配置外不触发任何派生状态补写。
        if (!termsDecision.isAuthorized || modulePrefs == null) return
        runCatching {
            com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundImages.grant(this,
                modulePrefs.getString(com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences.HOST_BACKGROUND_ASSET, "").orEmpty())
        }.onFailure { Log.w("BilibiliInnocentLab", "restore host background image grant failed", it) }
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

    private fun installMemoryPressureMonitoring() {
        val nativeBackend = runCatching {
            System.loadLibrary("mgmem")
            System.loadLibrary("mgmem_pressure_jni")
            NativePressureBackend.createArena(
                NATIVE_ARENA_CAPACITY_BYTES,
                NATIVE_MODERATE_BUDGET_BYTES,
                NATIVE_CRITICAL_BUDGET_BYTES,
                NATIVE_ARENA_CHUNK_BYTES
            )
        }.getOrNull()
        val backend = object : MemoryPressureAdapter.Backend {
            override fun notifyPressure(level: Int): Int {
                val status = nativeBackend?.let { runCatching { it.notifyPressure(level) }.getOrNull() }
                // Android 14+ 平台主要派发 UI_HIDDEN(20) 与 BACKGROUND(40)；adapter 已过滤 20，
                // 将 40(MODERATE) 及 CRITICAL 统一收口为释放可重建的图形资源与背景缓存。
                if (level >= MemoryPressureAdapter.MODERATE) {
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        ModuleMemoryPressureHub.releaseGraphics()
                    } else {
                        mainHandler.post { ModuleMemoryPressureHub.releaseGraphics() }
                    }
                }
                return status ?: 0
            }

            override fun beginClose() {
                nativeBackend?.beginClose()
            }

            override fun awaitClosed(timeoutMs: Long): Boolean =
                nativeBackend?.awaitClosed(timeoutMs) ?: true

            override fun snapshot(): LongArray? = nativeBackend?.snapshot()
        }
        memoryPressureAdapter = runCatching {
            MemoryPressureAdapter(this, backend)
        }.onFailure { throwable ->
            nativeBackend?.beginClose()
            Log.w("BilibiliInnocentLab", "install MemoryPressureAdapter failed", throwable)
        }.getOrNull()

        if (Build.VERSION.SDK_INT >= 37) {
            installMemoryBudgetObserverApi37()
        }
    }

    @RequiresApi(37)
    @DoNotInline
    private fun installMemoryBudgetObserverApi37() {
        if (Build.VERSION.SDK_INT_FULL < API_37_2) return
        memoryBudgetObserver = runCatching {
            MemoryBudgetObserver.observeProcess(this, Looper.getMainLooper()) {
                ModuleMemoryPressureHub.releaseGraphics()
            }
        }.onFailure { throwable ->
            Log.w("BilibiliInnocentLab", "install MemoryBudgetObserver failed", throwable)
        }.getOrNull()
    }
}
