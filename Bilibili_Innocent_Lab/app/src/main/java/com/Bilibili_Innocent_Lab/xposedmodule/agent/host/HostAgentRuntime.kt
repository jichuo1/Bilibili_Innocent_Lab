package com.Bilibili_Innocent_Lab.xposedmodule.agent.host

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.agent.AgentWire
import com.Bilibili_Innocent_Lab.xposedmodule.agent.AgentTaskCache
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** 只由已完成授权的主进程安装链初始化；与诊断查询使用不同 Binder 和事务协议。 */
internal object HostAgentRuntime {
    @Volatile private var runtime: Runtime? = null

    @Synchronized
    fun initialize(context: Context, classLoader: ClassLoader, accountReadAuthorized: Boolean = false) {
        if (runtime != null) return
        val application = (context.applicationContext ?: context) as? Application ?: return
        if (application.packageName != AgentWire.TARGET_PACKAGE) return
        runtime = Runtime(application, classLoader, accountReadAuthorized)
    }

    fun endpoint(): IBinder? = runtime?.endpoint

    private class Runtime(private val app: Application, private val loader: ClassLoader, private val accountReadAuthorized: Boolean) {
        private val main = Handler(Looper.getMainLooper())
        private val session = HostAgentSession(AgentWire.MAX_LEASE_MS, AgentWire.IPC_TIMEOUT_MS)
        private val admissionLock = Any()
        private val operations = executor("BIL-AgentHost", 4)
        private val responses = executor("BIL-AgentReply", 16)
        private val windows = HostAgentWindow(app, main, session, ::stop)
        private val rpc = HostAgentRpc(loader)
        private val discovery = HostAgentDiscoveryRpc(loader)
        private val content = HostAgentContentRpc(loader)
        private val dynamic = HostAgentDynamicRpc(loader)
        private val account by lazy { HostAgentAccountAccess.resolve(loader) }
        private val hostVersion by lazy {
            runCatching { app.packageManager.getPackageInfo(AgentWire.TARGET_PACKAGE, 0) }.getOrNull()
        }
        private val router by lazy { HostAgentRouter.resolve(loader) }
        @Volatile private var moduleUid = -1
        private var binding: Binding? = null // 主线程所有
        private var expiry: Expiry? = null // 一个任务只保留一个可重排的 TTL 回调
        private val cacheLock = Any()
        private val detailCache = AgentTaskCache(AgentTaskCache.DETAIL_CAPACITY, AgentTaskCache.DETAIL_TTL_MS)
        private var cacheGeneration: Long? = null // cacheLock 所有；不在锁内执行 RPC

        init {
            operations.execute {
                moduleUid = runCatching {
                    app.packageManager.getApplicationInfo(BuildConfig.APPLICATION_ID, 0).uid
                        .takeIf { it >= 10_000 && it != android.os.Process.myUid() } ?: -1
                }.getOrDefault(-1)
            }
        }

        val endpoint = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != AgentWire.REQUEST) return super.onTransact(code, data, reply, flags)
                // 每笔核验平台给出的 UID；载荷里的身份与 shell/root 都不能代替模块身份。
                if (moduleUid < 0 || getCallingUid() != moduleUid ||
                    flags and IBinder.FLAG_ONEWAY == 0 || data.dataSize() > AgentWire.MAX_REQUEST_BYTES) return false
                return runCatching {
                    data.enforceInterface(AgentWire.DESCRIPTOR)
                    val task = data.readString().orEmpty()
                    val sequence = data.readLong()
                    val deadline = data.readLong()
                    val operation = data.readString().orEmpty()
                    val raw = data.readString().orEmpty()
                    val callback = data.readStrongBinder() ?: return@runCatching false
                    if (data.dataAvail() != 0 || task.length > 96 || operation.length > 40 || raw.length > 12_000) return@runCatching false
                    val request = Request(task, sequence, deadline, operation, callback)
                    val arguments = runCatching { JSONObject(raw) }.getOrNull()
                    if (arguments == null || !validArguments(operation, arguments)) {
                        deliver(request, failure("invalid_arguments")); return@runCatching true
                    }
                    synchronized(admissionLock) {
                        val closing = operation == "cancel" || operation == "finish"
                        val admission = when (operation) {
                            "begin" -> session.begin(task, sequence, deadline, arguments.getLong("lease_until"), now(), arguments.optBoolean("allow_vision", false))
                            "renew" -> session.renew(task, sequence, deadline, arguments.getLong("lease_until"), now())
                            else -> session.admit(task, sequence, deadline, now(), closing)
                        }
                        val lease = admission.lease
                        if (lease == null) {
                            deliver(request, failure(admission.error ?: "task_inactive"))
                        } else if (closing) {
                            // 即使 RPC 正在阻塞，取消也在收包线程立刻使租约失效。
                            main.post {
                                try {
                                    val handoff = operation == "finish" && session.current(now()) == null && windows.canHandoff(lease)
                                    val released = release(lease)
                                    deliver(request, if (now() >= request.deadline || operation == "finish" && (!handoff || !released)) failure("native_handoff_unconfirmed")
                                    else success(JSONObject().put("status", if (operation == "cancel") "cancelled" else "finished")
                                        .put("native_window_released", handoff && released)))
                                } catch (_: Throwable) { deliver(request, failure("native_handoff_unconfirmed")) }
                            }
                        } else {
                            try {
                                operations.execute { execute(request, arguments, lease) }
                            } catch (_: java.util.concurrent.RejectedExecutionException) {
                                if (operation == "begin" || operation == "renew") stop(lease)
                                deliver(request, failure("host_queue_busy"))
                            }
                        }
                    }
                    true
                }.getOrDefault(false)
            }
        }

        private fun execute(request: Request, args: JSONObject, lease: HostAgentSession.Lease) {
            val value = try {
                if (!session.isActive(lease, now())) throw HostAgentFailure("task_inactive")
                val result = when (request.operation) {
                    "begin" -> {
                        windows.awaitTaskWindow(lease)
                        onMain(lease) {
                            if (!bind(lease)) throw HostAgentFailure("module_service_unavailable")
                            scheduleExpiry(lease)
                        }
                        synchronized(cacheLock) { cacheGeneration = lease.generation; detailCache.clear() }
                        session.remember(lease, args.optString("seed_video_ids").split(',').filter(String::isNotBlank), now())
                        session.rememberUsers(lease, args.optString("seed_user_ids").split(',').filter(String::isNotBlank), now())
                        val authorized = accountReadAuthorized && args.optBoolean("account_read_authorized", false)
                        val identity = if (authorized) account?.state(app)?.takeIf { it.code == "ready" }?.mid else null
                        session.accountAuthorization(lease, authorized, identity, now())
                        JSONObject().put("status", "started").put("deadline_elapsed", session.current(now())?.deadline)
                    }
                    "renew" -> {
                        // 续租必须复核前台控制权；允许等待已发起的有限导航，但不能接管其它页面。
                        windows.awaitTaskContext(lease)
                        onMain(lease) { scheduleExpiry(lease) }
                        JSONObject().put("status", "renewed").put("deadline_elapsed", session.current(now())?.deadline)
                    }
                    "capabilities", "get_capabilities" -> JSONObject().put("search_videos", rpc.search != null && router != null)
                        .put("pagination", rpc.search?.canPaginate == true)
                        .put("get_video_details", rpc.details != null).put("owner_verification", rpc.details?.canVerifyOwner == true)
                        .put("open_video", router != null).put("get_host_state", true)
                        .put("inspect_screen", session.allowVision(lease, now()))
                        .put("screen_requires_task_page", true).put("account_write_actions", false)
                        .put("get_ranking", discovery.rankingAvailable).put("get_popular", discovery.popularAvailable)
                        .put("get_regions", discovery.regionsAvailable).put("get_video_metrics", rpc.details?.canReadStatistics == true)
                        .put("get_user_videos", content.userVideosAvailable)
                        .put("get_video_comments", content.commentsAvailable)
                        .put("get_history", content.historyAvailable && accountRefusal(lease) == null)
                        .put("get_following_feed", dynamic.followingAvailable && accountRefusal(lease) == null)
                        .put("get_live_status", dynamic.liveStatusAvailable && accountRefusal(lease) == null)
                        .put("account_read_state", accountRefusal(lease) ?: "ready")
                        .put("unsupported_native", JSONArray(listOf("related_recommendations", "favorites", "social_write", "download", "payment")))
                    "get_host_state" -> onMain(lease) { windows.state(lease) }.also { state ->
                        stamp(state)
                        if ((state.optBoolean("foreground") || state.optBoolean("native_read_context")) && state.optBoolean("interaction_guard"))
                            HostAgentNavigationPolicy.videoId(state.optString("video_id"))?.let { session.remember(lease, listOf(it), now()) }
                    }
                    "search_videos" -> search(lease, args)
                    "get_ranking", "get_popular", "get_user_videos", "get_history", "get_following_feed", "get_video_comments" -> readPage(lease, request.operation, args)
                    "get_regions" -> {
                        onMain(lease) { windows.requireTaskContext(lease) }
                        discovery.regions(timeout(lease)).also { data -> rememberFacts(lease, data); stamp(data) }
                    }
                    "get_live_status" -> {
                        val user = knownUser(lease, args)
                        requireAccount(lease)
                        onMain(lease) { windows.requireTaskContext(lease) }
                        dynamic.liveStatus(user, timeout(lease)).also { requireAccount(lease); stamp(it); rememberFacts(lease, it) }
                    }
                    "get_video_metrics" -> {
                        val id = knownId(lease, args)
                        onMain(lease) { windows.requireTaskContext(lease) }
                        val detail = rpc.details?.takeIf { it.canReadStatistics } ?: throw HostAgentFailure("video_metrics_unavailable")
                        // 播放量等随时变化，强制新查询，不复用详情 TTL 缓存。
                        detail.query(id, timeout(lease)).also { stamp(it); rememberFacts(lease, it); it.put("cache_hit", false) }
                    }
                    "get_video_details" -> {
                        val id = knownId(lease, args)
                        onMain(lease) { windows.requireTaskContext(lease) }
                        val key = "${lease.generation}:$id"
                        val cached = synchronized(cacheLock) {
                            if (cacheGeneration != lease.generation) { detailCache.clear(); cacheGeneration = lease.generation }
                            detailCache.get(key, now())
                        }
                        cached ?: (rpc.details ?: throw HostAgentFailure("video_details_unavailable"))
                            .query(id, (lease.deadline - now()).coerceAtLeast(1)).also { value ->
                                val observedAt = now()
                                value.put("cache_hit", false).put("observed_at_elapsed", observedAt)
                                stamp(value)
                                rememberFacts(lease, value)
                                if (!session.isActive(lease, now())) throw HostAgentFailure("task_inactive")
                                synchronized(cacheLock) {
                                    if (cacheGeneration != lease.generation) throw HostAgentFailure("task_inactive")
                                    detailCache.put(key, value, observedAt)
                                }
                            }
                    }
                    "open_video" -> {
                        val id = knownId(lease, args)
                        navigate(lease, checkNotNull(HostAgentNavigationPolicy.videoRoute(id)), video = id)
                        JSONObject().put("video_id", id).put("navigation", "requested").put("observed", false)
                    }
                    "inspect_screen" -> windows.capture(lease)
                    else -> throw HostAgentFailure("unsupported_operation")
                }
                success(result)
            } catch (caught: HostAgentFailure) {
                if (request.operation == "begin" || request.operation == "renew") stop(lease)
                failure(caught.reason)
            } catch (_: Throwable) {
                if (request.operation == "begin" || request.operation == "renew") stop(lease)
                failure("host_operation_failed")
            }
            deliver(request, value, lease)
        }

        private fun search(lease: HostAgentSession.Lease, args: JSONObject): JSONObject {
            val query = HostAgentNavigationPolicy.searchQuery(args.optString("query")) ?: throw HostAgentFailure("invalid_query")
            val cursorToken = if (args.isNull("cursor")) null else args.optString("cursor").takeIf { it.isNotBlank() }
            val cursor = cursorToken?.let { session.cursor(lease, query, it, now()) ?: throw HostAgentFailure("invalid_cursor") }
            val search = rpc.search ?: throw HostAgentFailure("search_unavailable")
            if (cursor != null && !search.canPaginate) throw HostAgentFailure("pagination_unavailable")
            // 后续 RPC 分页不假装推动可见列表的滚动；响应明确标注数据来自后台查询。
            if (cursor == null) navigate(lease, checkNotNull(HostAgentNavigationPolicy.searchRoute(query)), query = query)
            else onMain(lease) { windows.requireTaskContext(lease) }
            val result = search.query(query, cursor, (lease.deadline - now()).coerceAtLeast(1))
            if (!session.isActive(lease, now())) throw HostAgentFailure("task_inactive")
            val permitted = session.remember(lease, result.videos.map { it.id }, now()).toSet()
            val videos = JSONArray()
            result.videos.filter { it.id in permitted }.forEach { videos.put(it.json()) }
            val next = session.rememberCursor(lease, query, result.next, now())
            return JSONObject().put("query", query).put("source", "host_rpc").put("videos", videos)
                .put("navigation", if (cursor == null) "requested" else "unchanged")
                .put("truncated_by_task_budget", result.videos.any { it.id !in permitted })
                .put("candidate_window", HostAgentSession.MAX_CANDIDATES).put("cursor_window", HostAgentSession.MAX_CURSORS)
                .put("visible_results_verified", false).put("next_cursor", next ?: JSONObject.NULL)
                .also { stamp(it); rememberFacts(lease, it) }
        }

        private fun readPage(lease: HostAgentSession.Lease, operation: String, args: JSONObject): JSONObject {
            onMain(lease) { windows.requireTaskContext(lease) }
            val personal = operation == "get_history" || operation == "get_following_feed"
            if (personal) requireAccount(lease)
            val identity = when (operation) {
                "get_user_videos" -> knownUser(lease, args)
                "get_video_comments" -> knownId(lease, args)
                "get_ranking" -> args.optString("region_id", "0").ifBlank { "0" }.also {
                    if (!session.knowsRegion(lease, it, now())) throw HostAgentFailure("region_not_in_task")
                }
                else -> ""
            }
            val scope = "$operation:$identity"
            val token = args.optString("cursor").takeIf { !args.isNull("cursor") && it.isNotBlank() }
            val cursor = token?.let { session.cursor(lease, scope, it, now()) ?: throw HostAgentFailure("invalid_cursor") }
            val page = when (operation) {
                "get_ranking" -> discovery.ranking(identity, cursor, timeout(lease))
                "get_popular" -> discovery.popular(cursor, timeout(lease))
                "get_user_videos" -> content.userVideos(identity, cursor, timeout(lease))
                "get_history" -> content.history(cursor, timeout(lease))
                "get_following_feed" -> dynamic.following(cursor, timeout(lease))
                "get_video_comments" -> content.comments(identity, cursor, timeout(lease))
                else -> throw HostAgentFailure("unsupported_operation")
            }
            if (personal) requireAccount(lease)
            if (!session.isActive(lease, now())) throw HostAgentFailure("task_inactive")
            val retained = session.remember(lease, page.videos.map { it.id }, now()).toSet()
            if (page.videos.any { it.id !in retained }) throw HostAgentFailure("candidate_limit_reached")
            rememberFacts(lease, page.data)
            val next = session.rememberCursor(lease, scope, page.next, now())
            return page.data.put("next_cursor", next ?: JSONObject.NULL).put("visible_results_verified", false).also { stamp(it) }
        }

        private fun stamp(data: JSONObject) {
            data.put("source", "host_rpc").put("observed_at_elapsed", now()).put("observed_at_unix_ms", System.currentTimeMillis())
                .put("freshness", "query_time_snapshot")
                .put("host_version", hostVersion?.versionName ?: JSONObject.NULL)
        }

        private fun timeout(lease: HostAgentSession.Lease) = (lease.deadline - now()).coerceIn(1L, 12_000L)

        private fun accountRefusal(lease: HostAgentSession.Lease): String? {
            if (!accountReadAuthorized) return "account_read_not_authorized"
            // 先核验任务授权，避免关闭选项时读取账号/令牌状态。
            session.accountRefusal(lease, null, now())?.takeIf { it == "account_read_not_authorized" || it == "task_inactive" }?.let { return it }
            val state = account?.state(app) ?: return "account_identity_unavailable"
            if (state.code != "ready") return state.code
            return session.accountRefusal(lease, state.mid, now())
        }

        private fun requireAccount(lease: HostAgentSession.Lease) {
            accountRefusal(lease)?.let {
                if (it == "account_changed") stop(lease)
                throw HostAgentFailure(it)
            }
        }

        private fun knownUser(lease: HostAgentSession.Lease, args: JSONObject): String {
            val id = HostAgentSession.userId(args.optString("user_id")) ?: throw HostAgentFailure("invalid_user_id")
            if (!session.knowsUser(lease, id, now())) throw HostAgentFailure("user_not_in_task")
            return id
        }

        /** 只记录 DTO 已投影出的数字身份，不递归遍历任意宿主返回树。 */
        private fun rememberFacts(lease: HostAgentSession.Lease, data: JSONObject) {
            val aliases = data.optJSONArray("video_aliases")
            if (aliases != null) session.remember(lease, (0 until minOf(aliases.length(), 2)).mapNotNull {
                HostAgentNavigationPolicy.videoId(aliases.optString(it))
            }, now())
            val users = mutableListOf<String>()
            HostAgentSession.userId(data.optString("author_uid"))?.let(users::add)
            for (name in listOf("videos", "items", "users", "posts")) {
                val values = data.optJSONArray(name) ?: continue
                for (i in 0 until minOf(values.length(), 100)) {
                    val value = values.optJSONObject(i) ?: continue
                    HostAgentSession.userId(value.optString(if (name == "users") "user_id" else "author_uid"))?.let(users::add)
                }
            }
            session.rememberUsers(lease, users, now())
            val regions = data.optJSONArray("regions")
            if (regions != null) session.rememberRegions(lease, (0 until minOf(regions.length(), 256)).mapNotNull {
                regions.optJSONObject(it)?.takeIf { value -> value.optString("region_type") == "ugc" || value.optInt("region_type", -1) == 2 }
                    ?.optString("region_id")
            }, now())
        }

        private fun knownId(lease: HostAgentSession.Lease, args: JSONObject): String {
            val id = HostAgentNavigationPolicy.videoId(args.optString("video_id")) ?: throw HostAgentFailure("invalid_video_id")
            if (!session.knows(lease, id, now())) throw HostAgentFailure("video_not_in_task")
            return id
        }

        private fun navigate(lease: HostAgentSession.Lease, uri: String, query: String? = null, video: String? = null) {
            val resolved = router ?: throw HostAgentFailure("navigation_unavailable")
            onMain(lease) {
                val activity = windows.prepareNavigation(lease, query, video)
                try { resolved.open(activity, uri) }
                catch (failure: Throwable) { windows.navigationFailed(lease); throw failure }
            }
        }

        private fun <T> onMain(lease: HostAgentSession.Lease, action: () -> T): T {
            val future = CompletableFuture<T>()
            main.post {
                if (future.isDone) return@post
                try {
                    val value = session.whileActive(lease, now()) {
                        if (future.isDone) throw HostAgentFailure("main_thread_timeout")
                        action()
                    } ?: throw HostAgentFailure("task_inactive")
                    future.complete(value)
                } catch (failure: Throwable) { future.completeExceptionally(failure) }
            }
            return try { future.get(minOf(2000L, (lease.deadline - now()).coerceAtLeast(1)), TimeUnit.MILLISECONDS) }
            catch (failure: Exception) {
                future.cancel(false)
                throw failure.cause as? HostAgentFailure ?: HostAgentFailure("main_thread_timeout")
            }
        }

        private fun stop(lease: HostAgentSession.Lease) {
            session.cancel(lease, now())
            main.post { release(lease) }
        }

        /** 最新 task deadline 决定 TTL；旧 command deadline 不影响长期窗口监听，也不获得延长。 */
        private fun scheduleExpiry(lease: HostAgentSession.Lease) {
            expiry?.let { previous ->
                if (previous.generation == lease.generation) return
                main.removeCallbacks(previous.callback)
            }
            val callback = object : Runnable {
                override fun run() {
                    try {
                        val current = session.current(now())
                        if (current == null || current.generation != lease.generation) {
                            release(lease)
                            return
                        }
                        main.postDelayed(this, (current.deadline - now()).coerceAtLeast(1))
                    } catch (_: Throwable) { stop(lease) }
                }
            }
            expiry = Expiry(lease.generation, callback)
            val current = session.current(now()) ?: return
            main.postDelayed(callback, (current.deadline - now()).coerceAtLeast(1))
        }

        /** Activity 已可见后才绑定；不请求后台启动权限，不永久保活模块。 */
        private fun bind(lease: HostAgentSession.Lease): Boolean {
            binding?.let { release(it.lease) }
            val component = ComponentName(BuildConfig.APPLICATION_ID,
                "com.Bilibili_Innocent_Lab.xposedmodule.agent.AgentSessionService")
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    if (name != component || !session.isTaskActive(lease, now())) { stop(lease); return }
                    try {
                        responses.execute {
                            if (!session.isTaskActive(lease, now())) return@execute
                            val data = Parcel.obtain()
                            try {
                                data.writeInterfaceToken(AgentWire.SERVICE_DESCRIPTOR)
                                data.writeString(lease.taskId)
                                if (!service.transact(AgentWire.SERVICE_KEEP_ALIVE, data, null, IBinder.FLAG_ONEWAY)) stop(lease)
                            } catch (_: Throwable) { stop(lease) } finally { data.recycle() }
                        }
                    } catch (_: java.util.concurrent.RejectedExecutionException) { stop(lease) }
                }
                override fun onServiceDisconnected(name: ComponentName) { stop(lease) }
                override fun onBindingDied(name: ComponentName) { stop(lease) }
                override fun onNullBinding(name: ComponentName) { stop(lease) }
            }
            val accepted = runCatching { app.bindService(Intent().setComponent(component), connection, Context.BIND_AUTO_CREATE) }
                .getOrDefault(false)
            if (accepted) binding = Binding(lease, connection)
            else runCatching { app.unbindService(connection) }
            return accepted
        }

        private fun release(lease: HostAgentSession.Lease): Boolean {
            expiry?.takeIf { it.generation == lease.generation }?.let {
                main.removeCallbacks(it.callback)
                expiry = null
            }
            val current = binding
            if (current != null && current.lease.generation == lease.generation) {
                binding = null
                runCatching { app.unbindService(current.connection) }
            }
            // 窗口层也按 generation 回收，旧任务迟到的 TTL/Service 回调不能覆盖新任务 callback。
            val released = windows.releaseTask(lease)
            val taskClosed = !session.isTaskActive(lease, now())
            synchronized(cacheLock) {
                // 缓存只有 JSON 文本；清理本身是短锁，不等待 operations 中的网络查询。
                if (cacheGeneration == lease.generation && taskClosed) {
                    detailCache.clear()
                    cacheGeneration = null
                }
            }
            return released
        }

        private fun deliver(request: Request, value: JSONObject, lease: HostAgentSession.Lease? = null) {
            try {
                responses.execute {
                    // 取消后绝不交付成功结果（尤其截图）；已收敛的失败原因仍可用于解释禁用边界。
                    val activeValue = if (lease != null && value.optBoolean("ok")) {
                        if (!session.isActive(lease, now())) failure("task_inactive") else try {
                            if (request.operation in PERSONAL_READS) requireAccount(lease)
                            value
                        } catch (caught: HostAgentFailure) { failure(caught.reason) }
                    } else value
                    var data = Parcel.obtain()
                    try {
                        fun write(parcel: Parcel, json: JSONObject) {
                            parcel.writeInterfaceToken(AgentWire.DESCRIPTOR)
                            parcel.writeString(request.task)
                            parcel.writeLong(request.sequence)
                            parcel.writeString(json.toString())
                        }
                        write(data, activeValue)
                        if (data.dataSize() > AgentWire.MAX_RESPONSE_BYTES) {
                            data.recycle(); data = Parcel.obtain()
                            write(data, failure("response_too_large"))
                        }
                        // 序列化/排队结束后再挡一次取消与账号切换；不交付成功的旧账号载荷。
                        if (lease != null && activeValue.optBoolean("ok")) {
                            val refusal = if (!session.isActive(lease, now())) "task_inactive"
                                else if (request.operation in PERSONAL_READS) accountRefusal(lease) else null
                            if (refusal != null) {
                                if (refusal == "account_changed") stop(lease)
                                data.recycle(); data = Parcel.obtain()
                                write(data, failure(refusal))
                            }
                        }
                        request.callback.transact(AgentWire.RESPONSE, data, null, IBinder.FLAG_ONEWAY)
                    } catch (_: Throwable) { /* 模块回收/取消时不重试，避免执行或回传重复结果。 */ }
                    finally { data.recycle() }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) { /* 有界背压，由模块请求超时结束。 */ }
        }

        private data class Binding(val lease: HostAgentSession.Lease, val connection: ServiceConnection)
        private data class Expiry(val generation: Long, val callback: Runnable)
        private data class Request(val task: String, val sequence: Long, val deadline: Long, val operation: String, val callback: IBinder)
    }

    private fun validArguments(operation: String, args: JSONObject): Boolean {
        val permitted = when (operation) {
            "begin" -> setOf("allow_vision", "lease_until", "account_read_authorized", "seed_video_ids", "seed_user_ids")
            "renew" -> setOf("lease_until")
            "capabilities", "get_capabilities", "get_regions", "get_host_state", "inspect_screen", "cancel", "finish" -> emptySet()
            "search_videos" -> setOf("query", "cursor")
            "get_video_details", "get_video_metrics", "open_video" -> setOf("video_id")
            "get_ranking" -> setOf("region_id", "cursor")
            "get_popular", "get_history", "get_following_feed" -> setOf("cursor")
            "get_user_videos" -> setOf("user_id", "cursor")
            "get_live_status" -> setOf("user_id")
            "get_video_comments" -> setOf("video_id", "cursor")
            else -> return false
        }
        if (args.keys().asSequence().any { it !in permitted }) return false
        return when (operation) {
            "begin" -> (args.opt("lease_until") is Long || args.opt("lease_until") is Int) &&
                (!args.has("allow_vision") || args.opt("allow_vision") is Boolean) &&
                (!args.has("account_read_authorized") || args.opt("account_read_authorized") is Boolean) &&
                validSeeds(args, "seed_video_ids", HostAgentNavigationPolicy::videoId) && validSeeds(args, "seed_user_ids", HostAgentSession::userId)
            "renew" -> args.opt("lease_until") is Long || args.opt("lease_until") is Int
            "search_videos" -> args.opt("query") is String && HostAgentNavigationPolicy.searchQuery(args.getString("query")) != null &&
                (!args.has("cursor") || args.isNull("cursor") || (args.opt("cursor") is String && args.getString("cursor").length <= 32))
            "get_video_details", "get_video_metrics", "open_video" -> args.opt("video_id") is String && HostAgentNavigationPolicy.videoId(args.getString("video_id")) != null
            "get_user_videos", "get_live_status" -> args.opt("user_id") is String && HostAgentSession.userId(args.getString("user_id")) != null && validCursor(args)
            "get_ranking" -> (!args.has("region_id") || args.opt("region_id") is String && args.getString("region_id").toIntOrNull()?.let { it >= 0 } == true) && validCursor(args)
            "get_popular", "get_history", "get_following_feed" -> validCursor(args)
            "get_video_comments" -> args.opt("video_id") is String && HostAgentNavigationPolicy.videoId(args.getString("video_id")) != null && validCursor(args)
            else -> true
        }
    }

    private fun validCursor(args: JSONObject): Boolean = !args.has("cursor") || args.isNull("cursor") ||
        args.opt("cursor") is String && args.getString("cursor").length <= 32

    private fun validSeeds(args: JSONObject, name: String, normalize: (String?) -> String?): Boolean {
        if (!args.has(name)) return true
        val value = args.opt(name) as? String ?: return false
        if (value.isEmpty()) return true
        return value.length <= 200 && value.split(',').let { it.size <= 8 && it.all { id -> normalize(id) == id } }
    }

    private fun now() = SystemClock.elapsedRealtime()
    private val PERSONAL_READS = setOf("get_history", "get_following_feed", "get_live_status")
    private fun success(data: JSONObject) = JSONObject().put("ok", true).put("data", data)
    private fun failure(reason: String) = JSONObject().put("ok", false).put("error", reason)
    private fun executor(name: String, capacity: Int) = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
        ArrayBlockingQueue(capacity), { runnable -> Thread(runnable, name).apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }
}
