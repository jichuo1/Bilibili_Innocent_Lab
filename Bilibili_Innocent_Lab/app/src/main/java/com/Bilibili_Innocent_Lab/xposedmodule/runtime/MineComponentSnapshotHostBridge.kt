package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.os.Build
import androidx.core.content.ContextCompat
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentSnapshotCodec
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.ScanSnapshotContent
import java.util.concurrent.Executors

/**
 * 运行在 B 站主进程中的扫描快照桥。
 *
 * 扫描结果先留在宿主内存并异步写入宿主私有缓存；模块设置页通过受签名权限保护的
 * 有序广播兜底查询；同时异步提交至模块 Provider，支持当前会话 Binder 查询。
 */
internal object MineComponentSnapshotHostBridge {
    private const val CACHE_PREFS = "innocent_lab_mine_component_snapshot"
    /** 载荷和版本均按面隔离，防止先更新的面给其他面的旧载荷标上新版本。 */
    private fun payloadKey(surface: String) = "payload_" + surface
    private const val KEY_TARGET_VERSION = "target_version"
    private const val KEY_TARGET_UPDATE_TIME = "target_update_time"
    private const val KEY_MODULE_VERSION = "module_version"

    private data class CachedSnapshot(
        val payload: String,
        val source: MineComponentSnapshotSource
    )

    private val receiverLock = Any()
    private val latest = java.util.concurrent.ConcurrentHashMap<String, CachedSnapshot>()
    private val persistenceExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "bil-mine-host-cache").apply { isDaemon = true }
    }
    private data class Publication(val context: Context, val content: ScanSnapshotContent)
    @Volatile private var processSource: MineComponentSnapshotSource? = null
    private val publications = LatestValuePublisher<String, Publication>(
        schedule = { action -> persistenceExecutor.execute(action) },
        publish = { surface, value -> persist(value.context, surface, value.content) }
    )

    @Volatile
    private var cacheLoaded = false

    @Volatile
    private var receiverRegistered = false

    fun initialize(
        context: Context,
        processName: String,
        logError: (String) -> Unit = {}
    ): Boolean {
        if (processName != MineComponentSnapshotQueryContract.TARGET_PACKAGE) return false
        val appContext = context.applicationContext ?: context
        synchronized(receiverLock) {
            if (!cacheLoaded) {
                // 外层 runCatching 只覆盖 execute 提交；任务体跑在宿主进程的后台线程上，
                // 逃逸异常按 Android 默认 handler 直接杀宿主，必须自带防波堤。
                cacheLoaded = runCatching { persistenceExecutor.execute {
                    HostThreadGuard.run("mine_snapshot.cache_load") {
                        val source = currentSource(appContext) ?: return@run
                        processSource = source
                        // 旧磁盘快照不覆盖已经收到的新内容；所有磁盘读取留在后台。
                        MineComponentSnapshotCodec.ALLOWED_SURFACES.forEach { surface ->
                            runCatching { readCachedSnapshot(appContext, surface, source) }
                                .getOrNull()
                                ?.let { latest.putIfAbsent(surface, it) }
                        }
                    }
                } }.isSuccess
            }
            if (receiverRegistered) return true
            return runCatching {
                ContextCompat.registerReceiver(
                    appContext,
                    createQueryReceiver(),
                    IntentFilter(MineComponentSnapshotQueryContract.ACTION_QUERY),
                    MineComponentSnapshotQueryContract.PERMISSION_QUERY,
                    null,
                    ContextCompat.RECEIVER_EXPORTED
                )
            }.onSuccess {
                receiverRegistered = true
            }.onFailure { throwable ->
                logError("“我的”页扫描结果查询接收器注册失败: $throwable")
            }.isSuccess
        }
    }

    fun update(
        context: Context,
        surface: String,
        content: ScanSnapshotContent,
        logError: (String) -> Unit = {}
    ): Boolean {
        if (surface !in MineComponentSnapshotCodec.ALLOWED_SURFACES) return false
        if (content.processName != MineComponentSnapshotQueryContract.TARGET_PACKAGE ||
            content.entries.isEmpty() || content.entries.size > MineComponentSnapshotCodec.MAX_ENTRY_COUNT) return false
        val accepted = publications.submit(surface, Publication(context.applicationContext ?: context, content))
        if (!accepted) logError("扫描快照后台调度失败，等待下次提交")
        return accepted
    }

    private fun persist(context: Context, surface: String, content: ScanSnapshotContent): Boolean = runCatching {
        val payload = MineComponentSnapshotCodec.encode(content.processName, content.capabilities,
            content.entries, surface = surface)
        val snapshot = MineComponentSnapshotCodec.decodeOrNull(payload, allowLegacy = false)
            ?: return@runCatching false
        // 载荷自述的面必须和调用方声明的一致，避免写串槽位。
        if (snapshot.surface != surface) return@runCatching false
        if (snapshot.processName != MineComponentSnapshotQueryContract.TARGET_PACKAGE ||
            snapshot.entries.isEmpty()
        ) return@runCatching false
        val appContext = context.applicationContext ?: context
        val source = processSource ?: currentSource(appContext)?.also { processSource = it }
            ?: return@runCatching false
        val updated = CachedSnapshot(payload, source)
        val committed = runCatching {
            appContext.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(payloadKey(surface), payload)
                .putLong("${KEY_TARGET_VERSION}_$surface", source.targetVersionCode)
                .putLong("${KEY_TARGET_UPDATE_TIME}_$surface", source.targetUpdateTime)
                .putLong("${KEY_MODULE_VERSION}_$surface", source.moduleVersionCode)
                .commit()
        }.getOrDefault(false)
        if (committed) {
            latest[surface] = updated
            HostReceiptHost.publish(surface, payload, HostRuntimeDiagnosticsSource(
                source.targetVersionCode, source.targetUpdateTime, source.moduleVersionCode))
        }
        committed
    }.getOrDefault(false)

    /** 两种实时传输读取同一份内存快照，Binder 线程不做磁盘读取。 */
    internal fun response(surface: String, nonce: String): android.os.Bundle {
        val cached = latest[surface]?.takeIf { it.source == processSource }
        return android.os.Bundle().apply {
            putBoolean(MineComponentSnapshotQueryContract.EXTRA_HANDLED, true)
            putString(MineComponentSnapshotQueryContract.EXTRA_REQUEST_NONCE, nonce)
            putString(MineComponentSnapshotQueryContract.EXTRA_STATUS,
                if (cached == null) MineComponentSnapshotQueryContract.STATUS_WAITING_PAGE else MineComponentSnapshotQueryContract.STATUS_READY)
            if (cached != null) {
                putString(MineComponentSnapshotQueryContract.EXTRA_PAYLOAD, cached.payload)
                putString(MineComponentSnapshotQueryContract.EXTRA_PAYLOAD_SHA256, MineComponentSnapshotQueryContract.sha256(cached.payload))
                putLong(MineComponentSnapshotQueryContract.EXTRA_TARGET_VERSION, cached.source.targetVersionCode)
                putLong(MineComponentSnapshotQueryContract.EXTRA_TARGET_UPDATE_TIME, cached.source.targetUpdateTime)
                putLong(MineComponentSnapshotQueryContract.EXTRA_MODULE_VERSION, cached.source.moduleVersionCode)
            }
        }
    }

    private fun createQueryReceiver(): BroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != MineComponentSnapshotQueryContract.ACTION_QUERY ||
                    !isOrderedBroadcast
                ) return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    sentFromPackage != MineComponentSnapshotQueryContract.MODULE_PACKAGE
                ) return
                val nonce = intent.getStringExtra(
                    MineComponentSnapshotQueryContract.EXTRA_REQUEST_NONCE
                ).orEmpty()
                if (!MineComponentSnapshotQueryContract.isValidNonce(nonce)) return

                val extras = getResultExtras(true)
                extras.putBoolean(MineComponentSnapshotQueryContract.EXTRA_HANDLED, true)
                extras.putString(MineComponentSnapshotQueryContract.EXTRA_REQUEST_NONCE, nonce)
                val requestedProtocol = intent.getIntExtra(
                    MineComponentSnapshotQueryContract.EXTRA_PROTOCOL_VERSION,
                    0
                )
                if (requestedProtocol != MineComponentSnapshotQueryContract.PROTOCOL_VERSION) {
                    extras.putString(
                        MineComponentSnapshotQueryContract.EXTRA_STATUS,
                        MineComponentSnapshotQueryContract.STATUS_UNSUPPORTED
                    )
                    resultCode = MineComponentSnapshotQueryContract.RESULT_CODE_HANDLED
                    return
                }

                val requestedSurface = intent.getStringExtra(
                    MineComponentSnapshotQueryContract.EXTRA_SURFACE
                )?.takeIf { it in MineComponentSnapshotCodec.ALLOWED_SURFACES }
                    ?: MineComponentSnapshotCodec.SURFACE_MINE
                setResultExtras(response(requestedSurface, nonce))
                resultCode = MineComponentSnapshotQueryContract.RESULT_CODE_HANDLED
            }
        }

    private fun readCachedSnapshot(context: Context, surface: String, source: MineComponentSnapshotSource): CachedSnapshot? {
        val prefs = context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
        // 旧全局版本无法证明某一面的载荷来源；等待该面重新采集，不拿旧缓存清理勾选。
        val cachedSource = MineComponentSnapshotSource(
            targetVersionCode = prefs.getLong("${KEY_TARGET_VERSION}_$surface", 0L),
            targetUpdateTime = prefs.getLong("${KEY_TARGET_UPDATE_TIME}_$surface", 0L),
            moduleVersionCode = prefs.getLong("${KEY_MODULE_VERSION}_$surface", 0L)
        )
        if (cachedSource != source) return null
        val payload = prefs.getString(payloadKey(surface), null).orEmpty()
        val snapshot = MineComponentSnapshotCodec.decodeOrNull(payload, allowLegacy = false)
            ?: return null
        if (snapshot.processName != MineComponentSnapshotQueryContract.TARGET_PACKAGE ||
            snapshot.surface != surface ||
            snapshot.entries.isEmpty()
        ) return null
        return CachedSnapshot(payload, cachedSource)
    }

    private fun currentSource(context: Context): MineComponentSnapshotSource? = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        MineComponentSnapshotSource(
            targetVersionCode = info.versionCodeCompat(),
            targetUpdateTime = info.lastUpdateTime,
            moduleVersionCode = BuildConfig.VERSION_CODE.toLong()
        ).takeIf { it.isComplete }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun PackageInfo.versionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()
}
