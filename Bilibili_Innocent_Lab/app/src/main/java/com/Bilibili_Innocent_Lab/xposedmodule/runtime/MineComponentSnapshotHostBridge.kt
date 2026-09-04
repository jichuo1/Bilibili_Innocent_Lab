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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * 运行在 B 站主进程中的扫描快照桥。
 *
 * 扫描结果先留在宿主内存并异步写入宿主私有缓存；模块设置页通过受签名权限保护的
 * 有序广播主动查询。这样不再依赖该设备已确认不可用的宿主 -> 模块 Provider/广播通道。
 */
internal object MineComponentSnapshotHostBridge {
    private const val CACHE_PREFS = "innocent_lab_mine_component_snapshot"
    /** 每个宿主 UI 面一个槽位；目标/模块版本三元组是共享元数据，仍只存一份。 */
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
                // 逐面回填：任何一面缺失/过期都只是这一面没有，不影响其它面。
                MineComponentSnapshotCodec.ALLOWED_SURFACES.forEach { surface ->
                    readCachedSnapshot(appContext, surface)?.let { latest[surface] = it }
                }
                cacheLoaded = true
            }
            if (receiverRegistered) return true
            return runCatching {
                ContextCompat.registerReceiver(
                    appContext,
                    createQueryReceiver(appContext),
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
        payload: String,
        logError: (String) -> Unit = {}
    ): Boolean {
        if (surface !in MineComponentSnapshotCodec.ALLOWED_SURFACES) return false
        val snapshot = MineComponentSnapshotCodec.decodeOrNull(payload, allowLegacy = false)
            ?: return false
        // 载荷自述的面必须和调用方声明的一致，避免写串槽位。
        if (snapshot.surface != surface) return false
        if (snapshot.processName != MineComponentSnapshotQueryContract.TARGET_PACKAGE ||
            snapshot.entries.isEmpty()
        ) return false
        val appContext = context.applicationContext ?: context
        val source = currentSource(appContext) ?: return false
        val updated = CachedSnapshot(payload, source)
        if (latest[surface] == updated) return true
        latest[surface] = updated
        runCatching {
            persistenceExecutor.execute {
                val committed = runCatching {
                    appContext.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .putString(payloadKey(surface), payload)
                        .putLong(KEY_TARGET_VERSION, source.targetVersionCode)
                        .putLong(KEY_TARGET_UPDATE_TIME, source.targetUpdateTime)
                        .putLong(KEY_MODULE_VERSION, source.moduleVersionCode)
                        .commit()
                }.getOrDefault(false)
                if (!committed) logError("“我的”页扫描结果宿主缓存写入失败")
            }
        }.onFailure { throwable ->
            logError("“我的”页扫描结果宿主缓存调度失败: $throwable")
        }
        return true
    }

    private fun createQueryReceiver(appContext: Context): BroadcastReceiver =
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
                val cached = latest[requestedSurface]
                    ?.takeIf { it.source == currentSource(appContext) }
                if (cached == null) {
                    extras.putString(
                        MineComponentSnapshotQueryContract.EXTRA_STATUS,
                        MineComponentSnapshotQueryContract.STATUS_WAITING_PAGE
                    )
                    resultCode = MineComponentSnapshotQueryContract.RESULT_CODE_HANDLED
                    return
                }
                extras.putString(
                    MineComponentSnapshotQueryContract.EXTRA_STATUS,
                    MineComponentSnapshotQueryContract.STATUS_READY
                )
                extras.putString(MineComponentSnapshotQueryContract.EXTRA_PAYLOAD, cached.payload)
                extras.putString(
                    MineComponentSnapshotQueryContract.EXTRA_PAYLOAD_SHA256,
                    MineComponentSnapshotQueryContract.sha256(cached.payload)
                )
                extras.putLong(
                    MineComponentSnapshotQueryContract.EXTRA_TARGET_VERSION,
                    cached.source.targetVersionCode
                )
                extras.putLong(
                    MineComponentSnapshotQueryContract.EXTRA_TARGET_UPDATE_TIME,
                    cached.source.targetUpdateTime
                )
                extras.putLong(
                    MineComponentSnapshotQueryContract.EXTRA_MODULE_VERSION,
                    cached.source.moduleVersionCode
                )
                resultCode = MineComponentSnapshotQueryContract.RESULT_CODE_HANDLED
            }
        }

    private fun readCachedSnapshot(context: Context, surface: String): CachedSnapshot? {
        val source = currentSource(context) ?: return null
        val prefs = context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
        val cachedSource = MineComponentSnapshotSource(
            targetVersionCode = prefs.getLong(KEY_TARGET_VERSION, 0L),
            targetUpdateTime = prefs.getLong(KEY_TARGET_UPDATE_TIME, 0L),
            moduleVersionCode = prefs.getLong(KEY_MODULE_VERSION, 0L)
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
