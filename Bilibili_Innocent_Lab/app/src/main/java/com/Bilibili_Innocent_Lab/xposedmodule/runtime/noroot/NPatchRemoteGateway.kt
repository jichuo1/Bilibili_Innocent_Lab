package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.compat.CommunicationCompatibilityStore
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.compat.CommunicationCompatibilityPolicy
import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.DeadObjectException
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.core.os.BundleCompat
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.settings.modulePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigContract
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigDecodeResult
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsDecision
import java.io.FileNotFoundException
import java.io.Serializable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.HashMap
import java.util.HashSet
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/**
 * NPatch Remote Provider 的最小模块侧网关。
 *
 * **为什么不用官方 AAR**：其连接字节码在 API 28-32 上直接调用 API 33 才有的
 * `String.isBlank`，而本模块 minSdk 27 且未开启 core library desugaring，装不得。
 * （最初还有一条"AAR 声明 minCompileSdk 37、本项目当时是 compileSdk 35"的理由，
 * 2026-09-03 抬到 compileSdk 37 后已失效，2026-09-08 复核时删除。）
 * 这里依照官方公开的 Provider + IXposedService 102 AIDL 合约，只保留本项目需要的
 * Preferences 原子写入与完整读回、作用域申请（`getScope` / `requestScope`）和 Remote Files。
 * 这是对 NPatch 唯一经 Manager 认证的通道：推送来的 `XposedService` 不校验来源，NPatch 通道下
 * 写入类操作一律走这里。
 *
 * **适用边界**：只支持 NPatch 的**管理器模式**（`--manager`）。集成模式（`--embed`）
 * 把 Remote Store 放在被修补应用自己的数据目录里，注入侧只读，模块设置 App 无法写入，
 * 结构上不可能从这里同步——与 LSPatch 嵌入模式是同一条边界，见
 * `docs/lspatch_compatibility.md` 的 Explicit non-goals。
 *
 * **官方声明的下限**：NPatch ≥ 1.0.7、Android 9（API 28）。模块不做版本探测，
 * 缺失/被拒的两条状态文案已把这个下限写进去（见 `no_root_status_*` 字符串）。
 */
internal object NPatchRemoteGateway {
    const val AUTHORITY = "top.nkbe.npatch.remote"
    private const val MANAGER_PACKAGE = "top.nkbe.npatch"
    private const val METHOD_GET_REMOTE_SERVICE = "getRemoteService"
    private const val KEY_MODULE_PACKAGE = "modulePackageName"
    private const val KEY_BINDER = "binder"
    private const val CONNECTION_RETRY_COOLDOWN_SECONDS = 15L
    private const val SERVICE_DESCRIPTOR = "io.github.libxposed.service.IXposedService"
    /**
     * `IXposedService` 的两个交易码，抄自 libxposed AIDL。
     *
     * 这份 AIDL **显式编号**（2-6 / 11-15 / 21-23 / 31-33 之间有明显空档），所以上游新增
     * 方法不会顶掉这两个号；但重排或改号会让本网关静默打到别的方法上，失败表现只是
     * `remote_service_error`，极难反查。
     *
     * `IXposedServiceTransactionCodesTest` 直接解析运行期类路径上 `IXposedService$Stub.class`
     * 的字节码常量（JVM 单测加载不了继承 `android.os.Binder` 的类，所以不走反射），升级
     * `io.github.libxposed:interface` 后码值不符会自动失败，不再依赖手工 `javap`。
     * wire format（token → group 字符串 → `writeTypedObject` → flags=0 → `readException`）
     * 仍以 2026-09-08 对 `interface-102.0.0` 的逐字段核对为准。
     */
    internal const val TRANSACTION_REQUEST_REMOTE_PREFERENCES = 21
    internal const val TRANSACTION_UPDATE_REMOTE_PREFERENCES = 22

    /** 作用域与文件通道使用的事务码，同样由 `IXposedServiceTransactionCodesTest` 对照生成的 Stub 校验。 */
    internal const val TRANSACTION_GET_SCOPE = 11
    internal const val TRANSACTION_REQUEST_SCOPE = 12
    internal const val TRANSACTION_LIST_REMOTE_FILES = 31
    internal const val TRANSACTION_OPEN_REMOTE_FILE = 32
    internal const val TRANSACTION_DELETE_REMOTE_FILE = 33

    /** `IXposedScopeCallback` 的两个回调码（AIDL 显式编号 1、2 加 `FIRST_CALL_TRANSACTION`）。 */
    private const val SCOPE_CALLBACK_DESCRIPTOR = "io.github.libxposed.service.IXposedScopeCallback"
    internal const val CALLBACK_TRANSACTION_SCOPE_APPROVED = 2
    internal const val CALLBACK_TRANSACTION_SCOPE_FAILED = 3
    private const val SCOPE_CALLBACK_WAIT_MS = 2_500L

    /**
     * Manager 1.1.0 起用它在本进程死亡时回收按（模块, uid）缓存的服务；旧版本忽略这个额外键。
     * 必须被强引用到进程结束，否则 Binder 对象被回收会让 Manager 过早丢弃缓存。
     * 惰性创建：JVM 单测加载本对象时不能构造 android.os.Binder。
     */
    private const val KEY_CLIENT_TOKEN = "clientToken"
    private val clientToken: IBinder by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { Binder() }

    sealed interface SyncResult {
        data object Success : SyncResult
        data object ManagerMissing : SyncResult
        data object ModuleNotRegistered : SyncResult
        data object ConnectionTimeout : SyncResult
        data class Failure(val errorCode: String) : SyncResult
    }

    /**
     * 两个有界单线程池分别承担编排与可能阻塞的 Provider/Binder 调用。即使厂商 Binder
     * 无视 interrupt，悬挂线程与排队任务数量也各自至多为 1，不会随页面恢复累积。
     */
    private val coordinatorExecutor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        boundedExecutor("BIL-NPatch-Sync", ThreadPoolExecutor.DiscardOldestPolicy())
    }
    private val connectionExecutor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        boundedExecutor("BIL-NPatch-Provider", ThreadPoolExecutor.AbortPolicy())
    }

    /** 作用域申请独立排队，不能与配置同步共用 DiscardOldest 队列而挤掉待发布的同步。 */
    private val scopeExecutor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        boundedExecutor("BIL-NPatch-ScopeRequest", ThreadPoolExecutor.DiscardOldestPolicy())
    }
    private val connectionBlockedUntilNanos = AtomicLong(0L)

    /**
     * 配置同步、作用域申请和文件通道会从不同线程发起连接，而连接线程池只有一个线程和一格队列：
     * 第三个并发提交会被拒绝并误开 15 秒熔断。进入前先在此串行化，拿不到锁按超时处理但不开熔断。
     */
    private val connectionGate = ReentrantLock()

    /** 调用方必须先确认用户已开启免 Root；关闭路径不会触发此对象的 executor。 */
    fun syncAsync(
        context: Context,
        snapshot: NoRootConfigSnapshot,
        decision: UserTermsDecision,
        stillCurrent: () -> Boolean = { true },
        callback: (SyncResult, com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationIdentity?) -> Unit
    ) {
        val appContext = context.applicationContext
        val identity = com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationAuthorityStore.current(appContext)?.identity
        coordinatorExecutor.execute {
            if (!isStillCurrent(stillCurrent)) return@execute
            val result = sync(appContext, snapshot, decision, stillCurrent) ?: return@execute
            if (isStillCurrent(stillCurrent)) callback(result, identity)
        }
    }

    private fun sync(
        context: Context,
        snapshot: NoRootConfigSnapshot,
        decision: UserTermsDecision,
        stillCurrent: () -> Boolean
    ): SyncResult? {
        if (!isStillCurrent(stillCurrent)) return null
        if (!isAvailable(context)) return SyncResult.ManagerMissing
        return try {
            val rawSource = HashMap<String, Any>().apply {
                context.modulePreferences().all.forEach { (key, value) ->
                    if (value != null) put(key, value)
                }
            }
            val values = resolveRemoteValues(snapshot, rawSource)

            runRemoteWriteWithTimeout(
                context = context,
                snapshot = snapshot,
                decision = decision,
                values = values,
                stillCurrent = stillCurrent
            )
        } catch (throwable: Throwable) {
            classifyFailure(unwrap(throwable))
        }
    }

    /**
     * 把连接/传输阶段的异常归并成状态。Manager 对未登记模块、包名不属于调用 uid 一律返回 null
     * （上游从 1.0.7 起如此），连接层把"Provider 在、服务没给"标成 rejected，这里归为
     * [SyncResult.ModuleNotRegistered]；Provider 本身不存在、被平台拒绝才是 [SyncResult.ManagerMissing]。
     */
    internal fun classifyFailure(root: Throwable): SyncResult = when (root) {
        is ManagerUnavailableException ->
            if (root.rejected) SyncResult.ModuleNotRegistered else SyncResult.ManagerMissing
        is SecurityException -> SyncResult.ModuleNotRegistered
        is ConnectionTimeoutException -> SyncResult.ConnectionTimeout
        is IncompatibleServiceException -> SyncResult.Failure("incompatible_service")
        is RemoteReadBackException -> SyncResult.Failure("remote_readback_mismatch")
        is UnsupportedOperationException -> SyncResult.Failure("remote_read_only")
        is RemoteException -> SyncResult.Failure("remote_service_error")
        else -> SyncResult.Failure(root.javaClass.simpleName.ifBlank { "unknown_failure" })
    }

    private fun isAvailable(context: Context): Boolean = runCatching {
        context.packageManager.resolveContentProvider(AUTHORITY, 0)?.packageName == MANAGER_PACKAGE
    }.getOrDefault(false)

    /**
     * 在认证拉取通道上申请把本模块加入 [packages] 的作用域（阻塞，必须在后台线程调用）。
     *
     * Manager 不弹确认，调用方必须先取得用户的明确同意。嵌入模式的作用域固定在修补包里，
     * 这条通道在那里无意义，由调用方按补丁模式拦截。
     */
    internal fun requestScope(
        context: Context,
        packages: List<String>,
        stillCurrent: () -> Boolean = { true }
    ): ScopeRequestResult? {
        val appContext = context.applicationContext ?: context
        if (!isStillCurrent(stillCurrent)) return null
        if (!isAvailable(appContext)) return ScopeRequestResult.ManagerMissing
        return try {
            boundedConnectionCall(appContext, stillCurrent) {
                val service = connectWithRetry(stillCurrent, beforeRetry = { Thread.sleep(200L) }) {
                    connectServiceBlocking(appContext)
                } ?: return@boundedConnectionCall null
                requestScopeFlow(BinderScopeChannel(service), packages, stillCurrent)
            }
        } catch (throwable: Throwable) {
            classifyFailure(unwrap(throwable)).toScopeResult()
        }
    }

    /** 作用域申请的异步入口；回调在专用线程上触发，调用方负责切回主线程。 */
    internal fun requestScopeAsync(
        context: Context,
        packages: List<String>,
        stillCurrent: () -> Boolean = { true },
        callback: (ScopeRequestResult) -> Unit
    ) {
        val appContext = context.applicationContext ?: context
        scopeExecutor.execute {
            val result = requestScope(appContext, packages, stillCurrent) ?: return@execute
            if (isStillCurrent(stillCurrent)) callback(result)
        }
    }

    /**
     * 连接拉取通道并返回文件服务；连接阶段有界，之后的文件 IO 在调用方线程上进行，
     * 不占用 6 秒的连接截止（模型文件约 10 MB）。
     */
    internal fun connectRemoteFiles(
        context: Context,
        stillCurrent: () -> Boolean = { true }
    ): PulledRemoteFiles {
        val appContext = context.applicationContext ?: context
        if (!isAvailable(appContext)) return PulledRemoteFiles.Unavailable(SyncResult.ManagerMissing)
        return try {
            val service = boundedConnectionCall(appContext, stillCurrent) {
                connectWithRetry(stillCurrent, beforeRetry = { Thread.sleep(200L) }) {
                    connectServiceBlocking(appContext)
                }
            } ?: return PulledRemoteFiles.Unavailable(SyncResult.Failure("superseded"))
            PulledRemoteFiles.Ready(BinderRemoteFiles(service))
        } catch (throwable: Throwable) {
            PulledRemoteFiles.Unavailable(classifyFailure(unwrap(throwable)))
        }
    }

    /**
     * 只做连接并给出分类，用于诊断与集成测试；[modulePackage] 默认是本模块，传入其它包名可以
     * 观察 Manager 对未登记模块的拒绝。
     */
    internal fun probeConnection(
        context: Context,
        modulePackage: String = context.packageName
    ): SyncResult {
        val appContext = context.applicationContext ?: context
        if (!isAvailable(appContext)) return SyncResult.ManagerMissing
        return try {
            boundedConnectionCall(appContext, { true }) {
                connectWithRetry({ true }, beforeRetry = { Thread.sleep(200L) }) {
                    connectServiceBlocking(appContext, modulePackage)
                }
            }
            SyncResult.Success
        } catch (throwable: Throwable) {
            classifyFailure(unwrap(throwable))
        }
    }

    /** 集成测试与诊断用的认证服务 Binder 入口；阻塞，必须在后台线程调用，返回的 Binder 只能在本进程使用。 */
    internal fun connectAuthenticatedBinder(context: Context, modulePackage: String = context.packageName): IBinder {
        val appContext = context.applicationContext ?: context
        return requireNotNull(boundedConnectionCall(appContext, { true }) {
            connectWithRetry({ true }, beforeRetry = { Thread.sleep(200L) }) {
                connectServiceBlocking(appContext, modulePackage)
            }
        }) { "NPatch connection was superseded" }
    }

    internal fun preferenceChannel(service: IBinder): RemotePreferenceChannel =
        BinderRemotePreferenceChannel(service)

    internal fun scopeChannel(service: IBinder): RemoteScopeChannel = BinderScopeChannel(service)

    internal fun remoteFiles(service: IBinder): BinderRemoteFiles = BinderRemoteFiles(service)

    /** Provider 连接与随后的 [work] 共享同一个截止时间；整段串行化并共享熔断。 */
    private fun <T> boundedConnectionCall(
        context: Context,
        stillCurrent: () -> Boolean,
        work: () -> T?
    ): T? {
        val timeoutMillis = CommunicationCompatibilityPolicy.managerTimeout(CommunicationCompatibilityStore.isEnabled(context))
        val gated = try {
            connectionGate.tryLock(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("NPatch connection interrupted", exception)
        }
        if (!gated) throw ConnectionTimeoutException()
        try {
            val nowNanos = SystemClock.elapsedRealtimeNanos()
            if (isConnectionCircuitOpen(nowNanos, connectionBlockedUntilNanos.get())) {
                throw ConnectionTimeoutException()
            }
            val call = try {
                connectionExecutor.submit<T?> {
                    if (!isStillCurrent(stillCurrent)) return@submit null
                    work()
                }
            } catch (exception: RejectedExecutionException) {
                openConnectionCircuit()
                throw ConnectionTimeoutException(exception)
            }
            val result = try {
                call.get(timeoutMillis, TimeUnit.MILLISECONDS)
            } catch (exception: TimeoutException) {
                call.cancel(true)
                connectionExecutor.purge()
                openConnectionCircuit()
                throw ConnectionTimeoutException(exception)
            } catch (exception: InterruptedException) {
                call.cancel(true)
                connectionExecutor.purge()
                Thread.currentThread().interrupt()
                throw IllegalStateException("NPatch connection interrupted", exception)
            } catch (exception: ExecutionException) {
                throw unwrap(exception)
            }
            connectionBlockedUntilNanos.set(0L)
            return result
        } finally {
            connectionGate.unlock()
        }
    }

    /** Provider 连接与 Binder 写入共享同一个截止时间。 */
    private fun runRemoteWriteWithTimeout(
        context: Context,
        snapshot: NoRootConfigSnapshot,
        decision: UserTermsDecision,
        values: Map<String, Any>,
        stillCurrent: () -> Boolean
    ): SyncResult? = boundedConnectionCall(context, stillCurrent) {
        val service = connectWithRetry(stillCurrent,
            beforeRetry = { Thread.sleep(200L) }) { connectServiceBlocking(context) }
            ?: return@boundedConnectionCall null
        if (!isStillCurrent(stillCurrent)) return@boundedConnectionCall null
        val publication = com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationAuthorityStore.current(context)
            ?.takeIf { it.decision == decision &&
                (if (snapshot.enabled) NoRootSupportStore.isDesiredEnabled(context) && it.values == values
                else !NoRootSupportStore.isDesiredEnabled(context)) } ?: return@boundedConnectionCall null
        publishRemotePreferences(BinderRemotePreferenceChannel(service), snapshot, decision, values) {
            stillCurrent() && com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationAuthorityStore.matches(context, publication)
        }
    }

    /**
     * 本模块已连上的认证服务 Binder。重复使用省掉一次 Provider 往返（Manager 已退出时还要冷启动它）和
     * Manager 一侧每次调用新增的死亡监听；仅缓存本模块自己的连接，且只在 Binder 仍存活时复用。
     * Manager 1.1.0 起自己也按（模块, uid）缓存同一个服务对象，所以这里不会比 Manager 更宽松。
     * 只在 [connectionExecutor]（单线程，且在 [connectionGate] 内）里读写；空闲超时会换线程，所以标 volatile。
     */
    @Volatile
    private var cachedOwnService: IBinder? = null

    /** 只能在 [connectionExecutor] 中调用，超时由外层 Future 统一控制。 */
    private fun connectServiceBlocking(context: Context, modulePackage: String = context.packageName): IBinder {
        val ownModule = modulePackage == context.packageName
        if (ownModule) {
            cachedOwnService?.let { cached ->
                if (cached.isBinderAlive && cached.pingBinder()) return cached
                cachedOwnService = null
            }
        }
        val extras = Bundle().apply {
            putString(KEY_MODULE_PACKAGE, modulePackage)
            putBinder(KEY_CLIENT_TOKEN, clientToken)
        }
        fun standardCall() = context.contentResolver.call(
            "content://$AUTHORITY".toUri(), METHOD_GET_REMOTE_SERVICE, null, extras)
        // 两种模式共用稳定协议；仅 Provider 进程死亡可重建一次，不吞身份拒绝。
        val client = try {
            context.contentResolver.acquireUnstableContentProviderClient(AUTHORITY)
        } catch (error: SecurityException) {
            throw ManagerUnavailableException(error)
        } ?: throw ManagerUnavailableException()
        val result = try {
            client.use { it.call(METHOD_GET_REMOTE_SERVICE, null, extras) }
        } catch (_: DeadObjectException) {
            try { standardCall() }
            catch (error: SecurityException) { throw ManagerUnavailableException(error) }
        } catch (error: SecurityException) {
            // Provider 启动/访问被平台拒绝，不等于远端显式拒绝模块身份。
            throw ManagerUnavailableException(error)
        }
        // Provider 已经应答但没给出服务：上游对未登记模块、包名不属于调用 uid 都返回 null。
        // 仍按"不可用"重试一次（Manager 刚启动时模块表可能尚未就绪），重试后再按 rejected 归类。
        val binder = result?.getBinder(KEY_BINDER)
            ?: throw ManagerUnavailableException(rejected = true)
        if (!binder.isBinderAlive || !binder.pingBinder()) {
            throw ManagerUnavailableException()
        }
        val descriptor = binder.interfaceDescriptor
        if (!acceptsServiceDescriptor(descriptor)) {
            throw IncompatibleServiceException(descriptor)
        }
        if (ownModule) cachedOwnService = binder
        return binder
    }

    /** 只重试连接，不重放可能已写入的事务；两次尝试共享外层截止时间与取消条件。 */
    internal fun <T> connectWithRetry(
        stillCurrent: () -> Boolean,
        beforeRetry: () -> Unit = {},
        connect: () -> T
    ): T? {
        repeat(2) { attempt ->
            if (!isStillCurrent(stillCurrent)) return null
            try { return connect() }
            catch (error: Exception) {
                if (attempt != 0 || (error !is ManagerUnavailableException && error !is DeadObjectException)) throw error
                if (!isStillCurrent(stillCurrent)) return null
                beforeRetry()
            }
        }
        return null
    }

    /**
     * 发布一份完整文档并读回校验。
     *
     * 写入是"显式删除过期键 + 整体 put"的单次差异（[buildAtomicPublishDiff]），不使用 `clear`：
     * NPatch 的 `clear` 先于事务单独生效，宿主冷启动落在两步之间会读到空文档并 fail-closed。
     * 读回仍然读取同一 group 的完整快照，任何一项不符都按 [RemoteReadBackException] 失败。
     */
    internal fun publishRemotePreferences(
        channel: RemotePreferenceChannel,
        snapshot: NoRootConfigSnapshot,
        decision: UserTermsDecision,
        values: Map<String, Any>,
        clockMillis: () -> Long = System::currentTimeMillis,
        stillCurrent: () -> Boolean
    ): SyncResult? {
        val currentRaw = channel.request(RemoteHookConfigContract.GROUP)
        val current = RemoteHookConfigContract.decode(currentRaw)
        if (current is RemoteHookConfigDecodeResult.Ready &&
            current.snapshot.moduleVersionCode == BuildConfig.VERSION_CODE.toLong() &&
            current.snapshot.deliveryEnabled == snapshot.enabled &&
            current.snapshot.noRootRevision == snapshot.revision &&
            current.snapshot.decision == decision &&
            current.snapshot.values == values
        ) {
            return SyncResult.Success
        }
        if (!isStillCurrent(stillCurrent)) return null
        val previousGeneration = (currentRaw[RemoteHookConfigContract.KEY_GENERATION] as? Long)
            ?.coerceAtLeast(0L) ?: 0L
        val generation = maxOf(
            clockMillis().coerceAtLeast(1L),
            previousGeneration.nextGeneration()
        )
        val encoded = RemoteHookConfigContract.encode(
            generation = generation,
            moduleVersionCode = BuildConfig.VERSION_CODE.toLong(),
            deliveryEnabled = snapshot.enabled,
            noRootRevision = snapshot.revision,
            decision = decision,
            values = values
        )
        channel.update(
            RemoteHookConfigContract.GROUP,
            buildAtomicPublishDiff(currentKeys = currentRaw.keys, target = encoded)
        )
        if (!isStillCurrent(stillCurrent)) return null
        val readBack = RemoteHookConfigContract.decode(channel.request(RemoteHookConfigContract.GROUP))
        val ready = readBack as? RemoteHookConfigDecodeResult.Ready
            ?: throw RemoteReadBackException(
                (readBack as RemoteHookConfigDecodeResult.Invalid).reason
            )
        if (ready.snapshot.generation != generation ||
            ready.snapshot.moduleVersionCode != BuildConfig.VERSION_CODE.toLong() ||
            ready.snapshot.deliveryEnabled != snapshot.enabled ||
            ready.snapshot.noRootRevision != snapshot.revision ||
            ready.snapshot.decision != decision ||
            ready.snapshot.values != values
        ) {
            throw RemoteReadBackException("content_mismatch")
        }
        return SyncResult.Success
    }

    /**
     * `IXposedService` 的 Parcel 适配层。Parcel/Bundle 在 JVM 单测里不可用，这一层只由模拟器集成
     * 测试（G2）覆盖；发布逻辑本身通过 [RemotePreferenceChannel] 在 JVM 内验证。
     */
    private class BinderRemotePreferenceChannel(private val service: IBinder) : RemotePreferenceChannel {
        override fun request(group: String): Map<String, Any> {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            val result = try {
                data.writeInterfaceToken(SERVICE_DESCRIPTOR)
                data.writeString(group)
                if (!service.transact(TRANSACTION_REQUEST_REMOTE_PREFERENCES, data, reply, 0)) {
                    throw RemoteException("NPatch requestRemotePreferences transaction rejected")
                }
                reply.readException()
                if (reply.readInt() != 0) {
                    Bundle.CREATOR.createFromParcel(reply)
                } else {
                    null
                }
            } finally {
                reply.recycle()
                data.recycle()
            }
            // 与项目里其它 IPC 入口一样用带类型的 compat 读取：API 33+ 由平台校验反序列化出的确是 HashMap。
            val raw = result?.let { BundleCompat.getSerializable(it, "map", HashMap::class.java) }
                ?: return emptyMap()
            return linkedMapOf<String, Any>().apply {
                raw.forEach { (key, value) ->
                    if (key is String && value != null) put(key, value)
                }
            }
        }

        override fun update(group: String, diff: RemotePreferenceDiff) {
            val bundle = Bundle().apply {
                putBoolean("clear", diff.clear)
                putSerializable("delete", HashSet(diff.delete) as Serializable)
                putSerializable("put", HashMap(diff.put) as Serializable)
            }
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(SERVICE_DESCRIPTOR)
                data.writeString(group)
                data.writeInt(1)
                bundle.writeToParcel(data, 0)
                if (!service.transact(TRANSACTION_UPDATE_REMOTE_PREFERENCES, data, reply, 0)) {
                    throw RemoteException("NPatch updateRemotePreferences transaction rejected")
                }
                reply.readException()
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
    }

    /** `getScope` / `requestScope` 的 Parcel 适配层，只由模拟器集成测试覆盖。 */
    private class BinderScopeChannel(private val service: IBinder) : RemoteScopeChannel {
        override fun scope(): List<String> {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(SERVICE_DESCRIPTOR)
                if (!service.transact(TRANSACTION_GET_SCOPE, data, reply, 0)) {
                    throw RemoteException("NPatch getScope transaction rejected")
                }
                reply.readException()
                return reply.createStringArrayList().orEmpty()
            } finally {
                reply.recycle()
                data.recycle()
            }
        }

        override fun requestScope(packages: List<String>): ScopeCallbackOutcome {
            val latch = CountDownLatch(1)
            val outcome = AtomicReference(ScopeCallbackOutcome.NONE)
            val callback = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = when (code) {
                    CALLBACK_TRANSACTION_SCOPE_APPROVED -> {
                        data.enforceInterface(SCOPE_CALLBACK_DESCRIPTOR)
                        data.createStringArrayList()
                        outcome.set(ScopeCallbackOutcome.APPROVED)
                        latch.countDown()
                        true
                    }
                    CALLBACK_TRANSACTION_SCOPE_FAILED -> {
                        data.enforceInterface(SCOPE_CALLBACK_DESCRIPTOR)
                        data.readString()
                        outcome.set(ScopeCallbackOutcome.FAILED)
                        latch.countDown()
                        true
                    }
                    else -> super.onTransact(code, data, reply, flags)
                }
            }
            val data = Parcel.obtain()
            try {
                data.writeInterfaceToken(SERVICE_DESCRIPTOR)
                data.writeStringList(packages)
                data.writeStrongBinder(callback)
                // `oneway`：没有应答 Parcel，结果只经回调返回。
                if (!service.transact(TRANSACTION_REQUEST_SCOPE, data, null, IBinder.FLAG_ONEWAY)) {
                    throw RemoteException("NPatch requestScope transaction rejected")
                }
            } finally {
                data.recycle()
            }
            latch.await(SCOPE_CALLBACK_WAIT_MS, TimeUnit.MILLISECONDS)
            return outcome.get()
        }
    }

    /** `listRemoteFiles` / `openRemoteFile` / `deleteRemoteFile` 的 Parcel 适配层。 */
    internal class BinderRemoteFiles(private val service: IBinder) : RemoteFileService {
        override fun listRemoteFiles(): Array<String> {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(SERVICE_DESCRIPTOR)
                if (!service.transact(TRANSACTION_LIST_REMOTE_FILES, data, reply, 0)) {
                    throw RemoteException("NPatch listRemoteFiles transaction rejected")
                }
                reply.readException()
                return reply.createStringArray() ?: emptyArray()
            } finally {
                reply.recycle()
                data.recycle()
            }
        }

        override fun openRemoteFile(name: String): ParcelFileDescriptor {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(SERVICE_DESCRIPTOR)
                data.writeString(name)
                if (!service.transact(TRANSACTION_OPEN_REMOTE_FILE, data, reply, 0)) {
                    throw RemoteException("NPatch openRemoteFile transaction rejected")
                }
                reply.readException()
                if (reply.readInt() == 0) throw FileNotFoundException(name)
                return ParcelFileDescriptor.CREATOR.createFromParcel(reply)
            } finally {
                reply.recycle()
                data.recycle()
            }
        }

        fun deleteRemoteFile(name: String): Boolean {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(SERVICE_DESCRIPTOR)
                data.writeString(name)
                if (!service.transact(TRANSACTION_DELETE_REMOTE_FILE, data, reply, 0)) {
                    throw RemoteException("NPatch deleteRemoteFile transaction rejected")
                }
                reply.readException()
                return reply.readInt() != 0
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
    }

    private fun SyncResult.toScopeResult(): ScopeRequestResult = when (this) {
        SyncResult.ManagerMissing -> ScopeRequestResult.ManagerMissing
        SyncResult.ModuleNotRegistered -> ScopeRequestResult.ModuleNotRegistered
        SyncResult.ConnectionTimeout -> ScopeRequestResult.ConnectionTimeout
        is SyncResult.Failure -> ScopeRequestResult.Failure(errorCode)
        SyncResult.Success -> ScopeRequestResult.Failure("unexpected_success")
    }

    private fun isStillCurrent(predicate: () -> Boolean): Boolean =
        runCatching(predicate).getOrDefault(false)

    private tailrec fun unwrap(throwable: Throwable): Throwable {
        val cause = throwable.cause ?: return throwable
        return when (throwable) {
            is ExecutionException,
            is java.util.concurrent.CompletionException -> unwrap(cause)
            else -> throwable
        }
    }

    private class ConnectionTimeoutException(cause: Throwable? = null) :
        IllegalStateException("NPatch connection unavailable", cause)

    /**
     * [rejected] 为真表示 Provider 已应答却没有给出服务（模块未登记或包名不属于调用 uid），
     * 与 Provider 不存在、被平台拒绝的"Manager 缺失"分开归类。
     */
    internal class ManagerUnavailableException(cause: Throwable? = null, val rejected: Boolean = false) :
        IllegalStateException("NPatch Manager connection unavailable", cause)

    private class IncompatibleServiceException(descriptor: String?) :
        RemoteException("Unexpected NPatch service descriptor: ${descriptor ?: "null"}")

    private class RemoteReadBackException(reason: String) :
        IllegalStateException("NPatch remote read-back failed: $reason")

    private fun openConnectionCircuit() {
        val cooldownNanos = TimeUnit.SECONDS.toNanos(CONNECTION_RETRY_COOLDOWN_SECONDS)
        connectionBlockedUntilNanos.set(SystemClock.elapsedRealtimeNanos() + cooldownNanos)
    }

    internal fun acceptsServiceDescriptor(descriptor: String?): Boolean =
        descriptor == SERVICE_DESCRIPTOR

    internal fun resolveRemoteValues(
        snapshot: NoRootConfigSnapshot,
        rawSource: Map<String, *>
    ): Map<String, Any> = RemoteHookConfigContract.resolveSourceValues(
        HashMap<String, Any>().apply {
            rawSource.forEach { (key, value) ->
                if (value != null) put(key, value)
            }
            putAll(snapshot.values)
            put(
                RemoteHookConfigContract.KEY_ADAPTER_RESET_TIMESTAMP,
                snapshot.adapterResetRevision
            )
        }
    )

    internal fun isConnectionCircuitOpen(nowNanos: Long, blockedUntilNanos: Long): Boolean =
        blockedUntilNanos != 0L && blockedUntilNanos - nowNanos > 0L

    private fun Long.nextGeneration(): Long = when {
        this < 1L -> 1L
        this == Long.MAX_VALUE -> Long.MAX_VALUE
        else -> this + 1L
    }

    private fun boundedExecutor(
        threadName: String,
        rejectionHandler: java.util.concurrent.RejectedExecutionHandler
    ) = ThreadPoolExecutor(
        1,
        1,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(1),
        { runnable -> Thread(runnable, threadName).apply { isDaemon = true } },
        rejectionHandler
        // 与项目里其它单线程 worker 一致：空闲 30 秒后释放线程，三个池不会各占一个常驻线程。
    ).apply { allowCoreThreadTimeOut(true) }
}
