package com.Bilibili_Innocent_Lab.xposedmodule.hook.roaming

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.Bilibili_Innocent_Lab.xposedmodule.hook.RoamingCompatHook
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernHookHandle
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernHookLog
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernHookRuntime
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchRuntimeProfiles
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.getPackageInfoOrNull
import io.github.jichuo1.meguviz.android.extensions.versionCodeCompat
import io.github.libxposed.api.XposedInterface
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import dalvik.system.BaseDexClassLoader

/** Optional adapter for the verified 833 isolated loader. No Legacy API or global callback table. */
internal object NPatchRoamingCache {
    @Volatile var status = RoamingCacheStatus.UNAVAILABLE
        private set
    @Volatile private var enabled = false
    @Volatile private var observing = false
    @Volatile private var identity: RoamingCacheIdentity? = null
    @Volatile private var minimumTimestamp = 0L
    @Volatile private var peerObserved = false
    @Volatile private var preparedStatus = RoamingCacheStatus.UNAVAILABLE
    private val started = AtomicBoolean(false)
    private val capturing = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())

    fun disable() { enabled = false; status = RoamingCacheStatus.DISABLED }

    fun statusForReset(requestedReset: Long): RoamingCacheStatus {
        val current = status
        return if (current in setOf(RoamingCacheStatus.READY_NEXT_START, RoamingCacheStatus.REUSED) &&
            identity?.reset != requestedReset) RoamingCacheStatus.UNAVAILABLE else current
    }

    fun start(context: Context, runtime: ModernHookRuntime, reset: Long, enabled: Boolean, hostLoader: ClassLoader?): Boolean {
        return try { startChecked(context, runtime, reset, enabled, hostLoader) } catch (error: Throwable) {
            observing = false
            status = RoamingCacheStatus.FAILED
            ModernHookLog.error("[BIL-RoamingCompat] optional cache adapter unavailable", error)
            true
        }
    }

    private fun startChecked(context: Context, runtime: ModernHookRuntime, reset: Long, enabled: Boolean, hostLoader: ClassLoader?): Boolean {
        val profile = NPatchRuntimeProfiles.read(context, context.packageName)
        if (!profile.markerPresent) return false
        if (!enabled) { disable(); return false }
        this.enabled = true
        if (!started.compareAndSet(false, true)) return true
        if (profile.loaderVersionCode != 833L || profile.loaderApi != 102) return false
        status = RoamingCacheStatus.UNAVAILABLE
        val app = context.applicationContext ?: context
        // Fixed 833 exposes the actual selected APK paths for its own native cache cleaner.
        // Read only this one peer; installed-package information is not a substitute for this path.
        val loaderType = Lookup.classOrNull(XposedInterface::class.java.classLoader,
            "top.nkbe.npatch.loader.LSPLoader") ?: return true
        val paths = Lookup.methodOrNull(loaderType, "getActiveModuleApkPaths")?.invoke(null) as? Map<*, *> ?: return true
        require(paths.size <= 32)
        val path = paths[RoamingCompatHook.BILIROAMING_PACKAGE] as? String ?: run {
            status = RoamingCacheStatus.UNAVAILABLE; return true
        }
        prepare(app, path, reset)
        // Vector prohibits hooks on itself. Android's DEX lookup boundary supplies the real peer
        // ClassLoader; filter before doing any work, then remove this optional observer immediately.
        val method = Lookup.methodOrNull(BaseDexClassLoader::class.java, "findClass", String::class.java) ?: run {
            status = RoamingCacheStatus.UNAVAILABLE; return true
        }
        val claimed = AtomicBoolean(false)
        var handle: ModernHookHandle? = null
        observing = true
        handle = runtime.install("roaming:peer-loader", method) {
            after {
                val loaded = result as? Class<*>
                val loader = thisObject as? ClassLoader
                if (loader?.javaClass?.name != "org.matrix.vector.impl.utils.VectorModuleClassLoader" || loaded == null ||
                    loaded.classLoader !== loader || !NPatchRoamingCache.enabled || !observing) return@after
                val actualPath = Lookup.fieldOrNull(loader.javaClass, "apkPath", includeSuperclasses = true)?.get(loader) as? String
                if (actualPath == null || File(actualPath).canonicalPath != File(path).canonicalPath ||
                    !claimed.compareAndSet(false, true)) return@after
                observing = false
                handle?.unhook()
                HostThreadGuard.run("roaming.prepare") {
                    peerObserved = true
                    status = preparedStatus
                    RoamingCompatHook.patchRoamingAnalysis(loader)
                    RoamingCompatHook.patchMineEntry(hostLoader)
                }
            }
        }
        main.postDelayed({
            if (observing) {
                observing = false
                runCatching { handle.unhook() }
                status = if (NPatchRoamingCache.enabled) RoamingCacheStatus.UNAVAILABLE else RoamingCacheStatus.DISABLED
            }
        }, 30_000)
        return true
    }

    @Suppress("DEPRECATION") // BetterAndroid 1.1.6 has no archive-manifest query; inspect the actual loaded APK.
    private fun prepare(context: Context, path: String, reset: Long) {
        val module = context.packageManager.getPackageArchiveInfo(path, 0)
        if (module?.packageName != RoamingCompatHook.BILIROAMING_PACKAGE) {
            status = RoamingCacheStatus.UNAVAILABLE
            ModernHookLog.info("[BIL-RoamingCompat] npatch_cache_unavailable=peer-manifest")
            return
        }
        val host = RoamingArchiveIdentity.inspect(listOfNotNull(context.applicationInfo.sourceDir?.let(::File)) +
            context.applicationInfo.splitSourceDirs.orEmpty().sorted().map(::File), allowOrigin = true)
        val peer = RoamingArchiveIdentity.inspect(listOf(File(path)), allowOrigin = false)
        if (host == null || peer == null || module.versionName.isNullOrBlank()) {
            status = RoamingCacheStatus.UNAVAILABLE
            ModernHookLog.info("[BIL-RoamingCompat] npatch_cache_unavailable=archive-identity")
            return
        }
        val hostPackage = context.packageManager.getPackageInfoOrNull(context.packageName) ?: return
        val current = RoamingCacheIdentity(host, peer, hostPackage.versionCodeCompat.toInt(),
            module.versionCodeCompat.toInt(), module.versionName!!, reset)
        minimumTimestamp = maxOf(hostPackage.lastUpdateTime,
            context.packageManager.getPackageInfoOrNull(module.packageName)?.lastUpdateTime ?: 0L)
        identity = current
        val store = RoamingCacheStore(context.cacheDir)
        val file = File(context.cacheDir, RoamingCompatHook.HOOK_INFO_FILE_NAME)
        preparedStatus = store.prepare(file, current, minimumTimestamp, System.currentTimeMillis())
        status = RoamingCacheStatus.WAITING
        ModernHookLog.info("[BIL-RoamingCompat] npatch_cache_prepare=${preparedStatus.name}")
    }

    /** Post to main first: all synchronous onCreate hooks/writers must have returned before sampling. */
    fun afterApplicationCreated(context: Context) {
        if (!enabled || !started.get() || !capturing.compareAndSet(false, true)) return
        val app = context.applicationContext ?: context
        main.post {
            Thread(HostThreadGuard.runnable("roaming.capture") {
                val store = RoamingCacheStore(app.cacheDir)
                val file = File(app.cacheDir, RoamingCompatHook.HOOK_INFO_FILE_NAME)
                repeat(30) {
                    if (!enabled) return@runnable
                    val source = identity
                    val first = store.read(file)
                    Thread.sleep(300)
                    val second = store.read(file)
                    if (peerObserved && source != null && first != null && second != null && first.contentEquals(second)) {
                        val parsed = RoamingHookInfo.parse(second)
                        if (parsed != null && source.accepts(parsed) && parsed.timestamp >= minimumTimestamp) {
                            val completed = RoamingHookInfo.completeAccessKey(second)
                            // Re-read immediately before publication; never overwrite another writer's new bytes.
                            if (!enabled || store.read(file)?.contentEquals(second) != true) return@repeat
                            if (store.atomic(file, completed) && store.save(completed, source)) {
                                if (status != RoamingCacheStatus.REUSED) status = RoamingCacheStatus.READY_NEXT_START
                                ModernHookLog.info("[BIL-RoamingCompat] npatch_cache_ready=${status.name}")
                                return@runnable
                            }
                        }
                    }
                    Thread.sleep(700)
                }
                if (enabled && status != RoamingCacheStatus.UNAVAILABLE) status = RoamingCacheStatus.FAILED
            }, "BIL-RoamingCache").apply { isDaemon = true; start() }
        }
    }
}
