package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.os.Build
import androidx.core.content.ContextCompat
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstallRecord
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstallResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureRuntimeStage
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureSkipReason
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * B 站主进程中的只读诊断回执桥。每项阶段只在首次发生时更新内存 Map；持久化由单线程合并，
 * 查询端只能通过签名权限保护的有序广播读取固定白名单阶段证据。
 */
internal object HostRuntimeDiagnosticsBridge {
    private const val CACHE_PREFS = "innocent_lab_host_runtime_diagnostics"
    private const val KEY_PAYLOAD = "payload"
    private const val KEY_TARGET_VERSION = "target_version"
    private const val KEY_TARGET_UPDATE_TIME = "target_update_time"
    private const val KEY_MODULE_VERSION = "module_version"
    private const val PERSIST_DELAY_MS = 2_000L
    private const val MIN_PERSIST_INTERVAL_MS = 30_000L

    private val lock = Any()
    private val evidence = linkedMapOf<String, HostRuntimeFeatureEvidence>()
    private val stageMasks = ConcurrentHashMap<String, AtomicInteger>()
    private val persistenceScheduled = AtomicBoolean(false)
    private val persistenceExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "bil-host-diagnostics").apply { isDaemon = true }
    }

    @Volatile private var appContext: Context? = null
    @Volatile private var source: HostRuntimeDiagnosticsSource? = null
    @Volatile private var receiverRegistered = false
    @Volatile private var capturedAtEpochMs = 1L
    @Volatile private var lastPersistAtEpochMs = 0L
    private var bootstrap = HostRuntimeBootstrapEvidence()

    fun initialize(
        context: Context,
        processName: String,
        logError: (String) -> Unit = {}
    ): Boolean {
        if (processName != HostRuntimeDiagnosticsQueryContract.TARGET_PACKAGE) return false
        val application = context.applicationContext ?: context
        synchronized(lock) {
            val currentSource = currentSource(application) ?: return false
            if (receiverRegistered && appContext === application && source == currentSource) {
                return true
            }
            appContext = application
            source = currentSource
            restoreLocked(application, currentSource)
            bootstrap = HostRuntimeBootstrapEvidence(bootstrapReached = true)
            capturedAtEpochMs = System.currentTimeMillis().coerceAtLeast(1L)
            if (receiverRegistered) return true
            return runCatching {
                ContextCompat.registerReceiver(
                    application,
                    createQueryReceiver(application),
                    IntentFilter(HostRuntimeDiagnosticsQueryContract.ACTION_QUERY),
                    HostRuntimeDiagnosticsQueryContract.PERMISSION_QUERY,
                    null,
                    ContextCompat.RECEIVER_EXPORTED
                )
            }.onSuccess {
                receiverRegistered = true
            }.onFailure { throwable ->
                logError("宿主运行时诊断查询接收器注册失败: $throwable")
            }.isSuccess.also { registered ->
                if (registered) schedulePersistence()
            }
        }
    }

    fun recordConfigAccepted(generation: Long, authorized: Boolean) {
        if (generation <= 0L) return
        updateBootstrap {
            copy(
                configState = if (authorized) HostConfigState.ACCEPTED
                else HostConfigState.NOT_AUTHORIZED,
                configGeneration = generation,
                configReasonCode = null
            )
        }
    }

    fun recordConfigRejected(reasonCode: String) {
        val bounded = reasonCode.takeIf {
            it in HostRuntimeDiagnosticsCodec.allowedConfigReasonCodes
        } ?: "unknown"
        updateBootstrap {
            copy(
                configState = HostConfigState.REJECTED,
                configGeneration = 0L,
                configReasonCode = bounded,
                installChainState = HostInstallChainState.NOT_STARTED
            )
        }
    }

    fun recordInstallChainStarted() {
        updateBootstrap { copy(installChainState = HostInstallChainState.STARTED) }
    }

    fun recordInstallChainCompleted() {
        updateBootstrap { copy(installChainState = HostInstallChainState.COMPLETED) }
    }

    fun recordInstallChainFailed() {
        updateBootstrap { copy(installChainState = HostInstallChainState.FAILED) }
    }

    fun recordHookPointSummary(diagnostics: List<HookPointRegistry.Diagnostic>) {
        var resolved = 0
        var installed = 0
        var missing = 0
        var failed = 0
        diagnostics.forEach { diagnostic ->
            when (diagnostic.state) {
                HookPointRegistry.State.RESOLVED -> resolved++
                HookPointRegistry.State.INSTALLED -> installed++
                HookPointRegistry.State.MISSING_CLASS,
                HookPointRegistry.State.MISSING_PARAMETER_CLASS,
                HookPointRegistry.State.MISSING_METHOD,
                HookPointRegistry.State.MISSING_FIELD,
                HookPointRegistry.State.MISSING_CONSTRUCTOR,
                HookPointRegistry.State.AMBIGUOUS_METHOD -> missing++
                HookPointRegistry.State.FAILED -> failed++
                HookPointRegistry.State.DUPLICATE -> Unit
            }
        }
        updateBootstrap {
            copy(
                hookPointResolvedCount = resolved.coerceAtMost(
                    HostRuntimeDiagnosticsCodec.MAX_HOOK_COUNT
                ),
                hookPointInstalledCount = installed.coerceAtMost(
                    HostRuntimeDiagnosticsCodec.MAX_HOOK_COUNT
                ),
                hookPointMissingCount = missing.coerceAtMost(
                    HostRuntimeDiagnosticsCodec.MAX_HOOK_COUNT
                ),
                hookPointFailedCount = failed.coerceAtMost(
                    HostRuntimeDiagnosticsCodec.MAX_HOOK_COUNT
                )
            )
        }
    }

    fun recordInstallation(record: FeatureInstallRecord) {
        if (record.id !in HostRuntimeDiagnosticsCodec.allowedFeatureIds) return
        val outcome = when (val result = record.result) {
            is FeatureInstallResult.Installed -> InstallOutcome(
                HostFeatureInstallState.INSTALLED,
                result.hookCount.coerceAtLeast(1),
                null
            )
            is FeatureInstallResult.Skipped -> when (result.reasonCode) {
                FeatureSkipReason.DISABLED -> InstallOutcome(
                    HostFeatureInstallState.DISABLED, 0, result.reasonCode.name
                )
                FeatureSkipReason.NOT_APPLICABLE_PROCESS -> InstallOutcome(
                    HostFeatureInstallState.NOT_APPLICABLE, 0, result.reasonCode.name
                )
                else -> InstallOutcome(
                    HostFeatureInstallState.SKIPPED, 0, result.reasonCode.name
                )
            }
            null -> InstallOutcome(
                HostFeatureInstallState.FAILED,
                0,
                "INSTALLER_EXCEPTION"
            )
        }
        synchronized(lock) {
            val updated = HostRuntimeDiagnosticsCodec.withInstallOutcome(
                evidence[record.id],
                record.id,
                outcome.state,
                outcome.hookCount,
                outcome.reasonCode
            ) ?: return
            evidence[record.id] = updated
            capturedAtEpochMs = System.currentTimeMillis().coerceAtLeast(1L)
        }
        schedulePersistence()
    }

    fun publishAdaptation(result: VersionAdapter.AdaptResult?) {
        result ?: return
        val pause = result.pause
        if (pause.requestMethods.isNotEmpty() || pause.legacyCallback != null ||
            pause.panelShow != null || pause.countdown != null
        ) record("paused_ad", FeatureRuntimeStage.ADAPTED)
        if (result.banner != null) record("home_banner", FeatureRuntimeStage.ADAPTED)
        if (result.homeTopBar != null) record("home_top_bar_purify", FeatureRuntimeStage.ADAPTED)
        if (result.mineVip != null) record("mine_vip_purify", FeatureRuntimeStage.ADAPTED)
        if (result.blockUpdate != null) record("block_app_update", FeatureRuntimeStage.ADAPTED)
        if (result.dynamicTabs != null) record("dynamic_tabs_purify", FeatureRuntimeStage.ADAPTED)
        if (result.fullNumbers != null) record("full_number_display", FeatureRuntimeStage.ADAPTED)
        if (result.playerPortrait != null) record(
            "player_portrait_control", FeatureRuntimeStage.ADAPTED
        )
        if (result.playerStatusBar != null) record("player_status_bar", FeatureRuntimeStage.ADAPTED)
        if (result.homeRecommendFeed != null) record(
            "home_recommend_purify", FeatureRuntimeStage.ADAPTED
        )
        if (result.videoRelate != null) record("video_relate_filter", FeatureRuntimeStage.ADAPTED)
        if (result.commentFilter != null) record("comment_filter", FeatureRuntimeStage.ADAPTED)
        if (result.commentPurify != null) record("comment_purify", FeatureRuntimeStage.ADAPTED)
        if (result.playerQuality != null) record(
            "player_default_quality", FeatureRuntimeStage.ADAPTED
        )
        if (result.splashAds != null) record("splash_ad_purify", FeatureRuntimeStage.ADAPTED)
        if (result.mineAccountMine != null || result.mineComponents != null) record(
            "mine_component_filter", FeatureRuntimeStage.ADAPTED
        )
        if (result.homeTabs != null) record("home_tab_filter", FeatureRuntimeStage.ADAPTED)
        if (result.homeComponents != null) record(
            "home_component_filter", FeatureRuntimeStage.ADAPTED
        )
        if (result.bottomBar != null) record("bottom_bar", FeatureRuntimeStage.ADAPTED)
        if (result.storyFeed != null) record("story_purify", FeatureRuntimeStage.ADAPTED)
        if (result.teenagersMode != null) record(
            "teenagers_mode_prompt", FeatureRuntimeStage.ADAPTED
        )
        if (result.commentSection != null) record("comment_section", FeatureRuntimeStage.ADAPTED)
        if (result.commentTopology != null) record("comment_topology", FeatureRuntimeStage.ADAPTED)
        if (result.commentLow != null || result.commentHigh != null) record(
            "free_copy", FeatureRuntimeStage.ADAPTED
        )
    }

    fun record(featureId: String, stage: FeatureRuntimeStage, delta: Int = 1) {
        if (featureId !in HostRuntimeDiagnosticsCodec.allowedFeatureIds || delta <= 0) return
        if (!markStageFirst(featureId, stage)) return
        synchronized(lock) {
            val updated = HostRuntimeDiagnosticsCodec.increment(
                evidence[featureId], featureId, stage, delta
            ) ?: return
            if (updated == evidence[featureId]) return
            evidence[featureId] = updated
            capturedAtEpochMs = System.currentTimeMillis().coerceAtLeast(1L)
        }
        schedulePersistence()
    }

    private fun markStageFirst(featureId: String, stage: FeatureRuntimeStage): Boolean {
        val bit = 1 shl stage.ordinal
        val mask = stageMasks.computeIfAbsent(featureId) { AtomicInteger(0) }
        while (true) {
            val current = mask.get()
            if (current and bit != 0) return false
            if (mask.compareAndSet(current, current or bit)) return true
        }
    }

    internal fun snapshot(): HostRuntimeDiagnosticsSnapshot = synchronized(lock) {
        HostRuntimeDiagnosticsSnapshot(
            capturedAtEpochMs = capturedAtEpochMs.coerceAtLeast(1L),
            processName = HostRuntimeDiagnosticsCodec.TARGET_PACKAGE,
            features = evidence.values.toList(),
            bootstrap = bootstrap
        )
    }

    private fun updateBootstrap(transform: HostRuntimeBootstrapEvidence.() -> HostRuntimeBootstrapEvidence) {
        synchronized(lock) {
            bootstrap = bootstrap.transform()
            capturedAtEpochMs = System.currentTimeMillis().coerceAtLeast(1L)
        }
        schedulePersistence()
    }

    private fun schedulePersistence() {
        if (!persistenceScheduled.compareAndSet(false, true)) return
        val elapsed = (System.currentTimeMillis() - lastPersistAtEpochMs).coerceAtLeast(0L)
        val delay = maxOf(PERSIST_DELAY_MS, MIN_PERSIST_INTERVAL_MS - elapsed)
        runCatching {
            persistenceExecutor.schedule({
                persistenceScheduled.set(false)
                persist()
            }, delay, TimeUnit.MILLISECONDS)
        }.onFailure { persistenceScheduled.set(false) }
    }

    private fun persist() {
        val context = appContext ?: return
        val expectedSource = source ?: return
        val payload = runCatching { HostRuntimeDiagnosticsCodec.encode(snapshot()) }.getOrNull()
            ?: return
        runCatching {
            context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PAYLOAD, payload)
                .putLong(KEY_TARGET_VERSION, expectedSource.targetVersionCode)
                .putLong(KEY_TARGET_UPDATE_TIME, expectedSource.targetUpdateTime)
                .putLong(KEY_MODULE_VERSION, expectedSource.moduleVersionCode)
                .commit()
        }.getOrDefault(false).also { committed ->
            if (committed) lastPersistAtEpochMs = System.currentTimeMillis()
        }
    }

    private fun restoreLocked(context: Context, expectedSource: HostRuntimeDiagnosticsSource) {
        evidence.clear()
        stageMasks.clear()
        bootstrap = HostRuntimeBootstrapEvidence()
        capturedAtEpochMs = 1L
        val prefs = context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
        val storedSource = HostRuntimeDiagnosticsSource(
            prefs.getLong(KEY_TARGET_VERSION, 0L),
            prefs.getLong(KEY_TARGET_UPDATE_TIME, 0L),
            prefs.getLong(KEY_MODULE_VERSION, 0L)
        )
        if (storedSource != expectedSource) return
        val restored = HostRuntimeDiagnosticsCodec.decodeOrNull(
            prefs.getString(KEY_PAYLOAD, null).orEmpty()
        ) ?: return
        evidence.clear()
        restored.features.forEach { feature ->
            evidence[feature.featureId] = feature
            var mask = 0
            if (feature.adaptedCount > 0L) mask = mask or (1 shl FeatureRuntimeStage.ADAPTED.ordinal)
            if (feature.observedCount > 0L) mask = mask or (1 shl FeatureRuntimeStage.OBSERVED.ordinal)
            if (feature.appliedCount > 0L) mask = mask or (1 shl FeatureRuntimeStage.APPLIED.ordinal)
            stageMasks[feature.featureId] = AtomicInteger(mask)
        }
        capturedAtEpochMs = restored.capturedAtEpochMs
        bootstrap = restored.bootstrap
        lastPersistAtEpochMs = restored.capturedAtEpochMs
    }

    private fun createQueryReceiver(application: Context): BroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != HostRuntimeDiagnosticsQueryContract.ACTION_QUERY ||
                    !isOrderedBroadcast
                ) return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    sentFromPackage != HostRuntimeDiagnosticsQueryContract.MODULE_PACKAGE
                ) return
                val nonce = intent.getStringExtra(
                    HostRuntimeDiagnosticsQueryContract.EXTRA_REQUEST_NONCE
                ).orEmpty()
                if (!HostRuntimeDiagnosticsQueryContract.isValidNonce(nonce)) return
                val extras = getResultExtras(true)
                extras.putBoolean(HostRuntimeDiagnosticsQueryContract.EXTRA_HANDLED, true)
                extras.putString(HostRuntimeDiagnosticsQueryContract.EXTRA_REQUEST_NONCE, nonce)
                if (intent.getIntExtra(
                        HostRuntimeDiagnosticsQueryContract.EXTRA_PROTOCOL_VERSION, 0
                    ) != HostRuntimeDiagnosticsQueryContract.PROTOCOL_VERSION
                ) {
                    extras.putString(
                        HostRuntimeDiagnosticsQueryContract.EXTRA_STATUS,
                        HostRuntimeDiagnosticsQueryContract.STATUS_UNSUPPORTED
                    )
                    resultCode = HostRuntimeDiagnosticsQueryContract.RESULT_CODE_HANDLED
                    return
                }
                val currentSource = currentSource(application) ?: return
                val payload = HostRuntimeDiagnosticsCodec.encode(snapshot())
                extras.putString(
                    HostRuntimeDiagnosticsQueryContract.EXTRA_STATUS,
                    HostRuntimeDiagnosticsQueryContract.STATUS_READY
                )
                extras.putString(HostRuntimeDiagnosticsQueryContract.EXTRA_PAYLOAD, payload)
                extras.putString(
                    HostRuntimeDiagnosticsQueryContract.EXTRA_PAYLOAD_SHA256,
                    HostRuntimeDiagnosticsQueryContract.sha256(payload)
                )
                extras.putLong(
                    HostRuntimeDiagnosticsQueryContract.EXTRA_TARGET_VERSION,
                    currentSource.targetVersionCode
                )
                extras.putLong(
                    HostRuntimeDiagnosticsQueryContract.EXTRA_TARGET_UPDATE_TIME,
                    currentSource.targetUpdateTime
                )
                extras.putLong(
                    HostRuntimeDiagnosticsQueryContract.EXTRA_MODULE_VERSION,
                    currentSource.moduleVersionCode
                )
                resultCode = HostRuntimeDiagnosticsQueryContract.RESULT_CODE_HANDLED
            }
        }

    private fun currentSource(context: Context): HostRuntimeDiagnosticsSource? = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        HostRuntimeDiagnosticsSource(
            targetVersionCode = info.versionCodeCompat(),
            targetUpdateTime = info.lastUpdateTime,
            moduleVersionCode = BuildConfig.VERSION_CODE.toLong()
        ).takeIf { it.isComplete }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun PackageInfo.versionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()

    private data class InstallOutcome(
        val state: HostFeatureInstallState,
        val hookCount: Int,
        val reasonCode: String?
    )
}
