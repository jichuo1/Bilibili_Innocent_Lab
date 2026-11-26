package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import android.content.Context
import android.content.SharedPreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.AtomicJsonCache
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.TargetAppStorage
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigContract
import org.json.JSONObject
import java.io.File

/**
 * 适配缓存读写、宿主版本读取与重置清理存储。
 *
 * 与 [VersionAdapter] 对象体彻底解耦：设置页或冷启动调用 [clearCache] 时仅操作 SharedPreferences
 * 与缓存标记文件，绝不触发 `VersionAdapter.<clinit>` 及重型定位常量表初始化。
 */
internal object AdaptCacheStore {

    internal const val PREF_FILE = "innocent_lab_version_adapter"
    internal const val KEY_ADAPTED_VERSION = "adapted_bili_version"
    internal const val KEY_ADAPT_RESULT = "adapt_result"
    internal const val KEY_RESET_TS = RemoteHookConfigContract.KEY_ADAPTER_RESET_TIMESTAMP
    internal const val CACHE_FILE_NAME = "innocent_lab_adapt.json"
    internal const val DEX_ASSIST_MARKER_FILE_NAME = "innocent_lab_dex_assist.pending"
    internal const val DEX_SOURCE_UNAVAILABLE = "unavailable"

    @Volatile
    private var lastCacheStatus = "not-read"

    internal fun cacheStatus(): String = lastCacheStatus

    internal fun recordCacheStatus(status: String) {
        lastCacheStatus = status
    }

    internal fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    /**
     * 二级缓存文件（B 站自身 cache 目录，loadApp 阶段无 Context 也可同步读；
     * 模块 prefs 在部分设备（官方 LSPosed 无 DirectAccessService）不可用时，
     * 此文件承担「适配完成后快路径」的载体）。
     */
    internal fun cacheFile(): File = TargetAppStorage.cacheFile(CACHE_FILE_NAME)

    /** DexMeguviz 查询进行中的标记，见 [com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistAttemptGuard]。 */
    internal fun dexAssistMarkerFile(context: Context? = null): File =
        TargetAppStorage.cacheFile(DEX_ASSIST_MARKER_FILE_NAME, context)

    /** 当前 B 站 versionCode（读自身包信息，隔离环境对自身可见）。 */
    @Suppress("DEPRECATION")
    internal fun biliVersionCode(context: Context): Int = runCatching {
        context.packageManager.getPackageInfo("tv.danmaku.bili", 0).versionCode
    }.getOrDefault(0)

    /**
     * 手动重适配：写重置标记 + 清除记录（B 站下次启动即重新定位）。
     * reset_ts 写入模块私有权威设置后，由 RemoteHookConfigStore 发布到 API 102 配置组。
     * 不引用 `VersionAdapter` 对象本身，避免 UI 弹窗触发其 `<clinit>`。
     */
    internal fun clearCache(context: Context, modulePrefs: SharedPreferences? = null) {
        runCatching {
            modulePrefs?.edit()?.putLong(KEY_RESET_TS, System.currentTimeMillis())?.apply()
        }
        runCatching {
            prefs(context).edit()
                .remove(KEY_ADAPTED_VERSION)
                .remove(KEY_ADAPT_RESULT)
                .apply()
        }
        runCatching { cacheFile().delete() }
        // 手动重适配也给上次没查完的 DexMeguviz 一次重试机会。
        runCatching { dexAssistMarkerFile(context).delete() }
    }

    internal fun writeCache(result: VersionAdapter.AdaptResult): Boolean {
        val payload = AdaptResultCodec.toJson(result).toString()
        return AtomicJsonCache.write(cacheFile(), payload) { candidate ->
            runCatching { AdaptResultCodec.fromJson(JSONObject(candidate)) != null }.getOrDefault(false)
        }
    }

    internal fun writePrefsCache(context: Context, result: VersionAdapter.AdaptResult): Boolean =
        runCatching {
            prefs(context).edit()
                .putInt(KEY_ADAPTED_VERSION, result.biliVersionCode)
                .putString(KEY_ADAPT_RESULT, AdaptResultCodec.toJson(result).toString())
                .apply()
            true
        }.getOrDefault(false)

    /** 只读枚举 file → prefs 中的缓存候选，不修改 [lastCacheStatus] 也不因旧文件遮蔽 prefs 新缓存。 */
    internal fun readRawCacheCandidates(context: Context?, resetTimestamp: Long): List<VersionAdapter.AdaptResult> {
        val resetTs = resetTimestamp.coerceAtLeast(0L)
        val candidates = ArrayList<VersionAdapter.AdaptResult>(2)
        runCatching {
            val f = cacheFile()
            if (f.exists()) {
                AdaptResultCodec.fromJson(JSONObject(f.readText()))
                    ?.takeIf { it.ts >= resetTs }
                    ?.let(candidates::add)
            }
        }
        if (context != null) {
            runCatching {
                val p = prefs(context)
                val v = p.getInt(KEY_ADAPTED_VERSION, 0)
                val json = p.getString(KEY_ADAPT_RESULT, null) ?: return@runCatching
                val r = AdaptResultCodec.fromJson(JSONObject(json)) ?: return@runCatching
                if (r.biliVersionCode == v && r.ts >= resetTs) candidates.add(r)
            }
        }
        return candidates
    }

    internal fun loadCached(
        context: Context?,
        resetTimestamp: Long
    ): VersionAdapter.AdaptResult? =
        loadCached(context, resetTimestamp, context?.let(ProtocolFingerprintBuilder::readHostIdentity))

    internal fun loadCached(
        context: Context?,
        resetTimestamp: Long,
        identity: VersionAdapter.HostIdentity?
    ): VersionAdapter.AdaptResult? {
        val resetTs = resetTimestamp.coerceAtLeast(0L)
        val expectedFingerprint = identity?.fingerprint

        fun accepted(result: VersionAdapter.AdaptResult, source: String): VersionAdapter.AdaptResult? {
            if (result.ts < resetTs) {
                lastCacheStatus = "$source-stale-reset"
                return null
            }
            if (expectedFingerprint != null && !AdaptResultValidator.isUsableWith(result, expectedFingerprint)) {
                lastCacheStatus = "$source-fingerprint-mismatch"
                return null
            }
            val versionCode = identity?.versionCode ?: 0
            if (versionCode != 0 && result.biliVersionCode != versionCode) {
                lastCacheStatus = "$source-version-mismatch"
                return null
            }
            lastCacheStatus = "$source-hit"
            return result
        }

        // 文件缓存（loadApp 阶段可读）
        val fileResult = runCatching {
            val f = cacheFile()
            if (!f.exists()) return@runCatching null
            AdaptResultCodec.fromJson(JSONObject(f.readText()))
                ?: run {
                    lastCacheStatus = "file-invalid"
                    null
                }
        }.onFailure {
            lastCacheStatus = "file-read-failed:${it.javaClass.simpleName}"
        }.getOrNull()
        fileResult?.let { accepted(it, "file") }?.let { return it }

        // prefs 缓存（兜底；同样检查手动重置标记）
        if (context != null) {
            runCatching {
                val p = prefs(context)
                val v = p.getInt(KEY_ADAPTED_VERSION, 0)
                val json = p.getString(KEY_ADAPT_RESULT, null) ?: return@runCatching null
                val r = AdaptResultCodec.fromJson(JSONObject(json)) ?: return@runCatching null
                if (r.biliVersionCode == v) r else null
            }.onFailure {
                lastCacheStatus = "prefs-read-failed:${it.javaClass.simpleName}"
            }.getOrNull()?.let { accepted(it, "prefs") }?.let { return it }
        }
        if (!lastCacheStatus.contains("invalid") && !lastCacheStatus.contains("mismatch") &&
            !lastCacheStatus.contains("failed") && !lastCacheStatus.contains("stale")
        ) {
            lastCacheStatus = "miss"
        }
        return null
    }

    internal fun readStartupCache(context: Context, resetTimestamp: Long): VersionAdapter.StartupCacheSnapshot {
        val identity = ProtocolFingerprintBuilder.readHostIdentity(context)
        val resetTs = resetTimestamp.coerceAtLeast(0L)
        return VersionAdapter.StartupCacheSnapshot(identity, resetTs, loadCached(context, resetTs, identity))
    }

    internal fun isCached(context: Context, resetTimestamp: Long): Boolean {
        val cached = loadCached(context, resetTimestamp) ?: return false
        return cached.biliVersionCode == biliVersionCode(context)
    }
}
