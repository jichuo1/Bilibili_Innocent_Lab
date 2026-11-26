package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AiPlaylistDirectorAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ReflectAccess
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.InjectedUiLocale
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.TargetAppStorage
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isSubclassOf
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 详情页「含AI生成内容」拦截 + 补位。
 *
 * 落点与 [DetailUnitedModulePurifyFeatureInstaller] 相同（`viewunite.v1.ViewMoss` 的同步
 * `executeView` 与异步 `view`），但**只改 `ecode` / `ecode_config` 两个顶层字段**：
 *
 * - 命中声明、且相关推荐里有可用视频 → `ecode=CODE_404` + `redirect_url=<补位视频路由>`。
 *   宿主 `ViewRepository` 把它映射成 `ViewNotFoundException(jumpUrl)`，
 *   `BusinessScopeDriverImpl` 对非空 jumpUrl 执行 `BLRouter.routeTo` 并关掉当前页
 *   （8.89.0 反汇编实证；"NotFound, jump to:" 日志串与两个异常类 27 版宿主全在）。
 * - 没有可用补位、或连锁跳转超过上限 → `ecode=CODE_ARC_PRIVACY` + `msg=<提示>`，
 *   走宿主原生的"不可见"错误页，页面文案就是我们的提示。
 *
 * 没有命中时，若相关推荐里有本进程已知的 AI 视频，删掉那几张卡（第二条链，独立降级）。
 *
 * 边界纪律同详情页协议层净化：副本改写、排除默认实例、改完回读、任何异常交付原响应。
 */
internal class AiDeclaredVideoFeatureInstaller(
    private val enabled: Boolean,
    /** 强力模式 · 屏蔽发布者。 */
    private val strongMode: Boolean,
    /** 强力模式 · 获取 access_key 推荐预检；宿主入口已按「预检开 且 已授权」合成。 */
    private val precheck: Boolean = false,
    private val kmpGrpcWireEngine: VersionAdapter.HookPoint? = null,
    private val aiPlaylistDirector: String? = null,
    private val aiPlaylistDirectorProvider: () -> String? = { aiPlaylistDirector }
) : FeatureInstaller {

    override val id: String = AiDeclaredVideoPolicy.ID

    @Volatile
    var requiresAdaptationRetry: Boolean = false
        private set

    private val mainRoutesInstalled = AtomicBoolean(false)
    private val playlistInstalled = AtomicBoolean(false)
    private val playlistRef = AtomicReference<AiPlaylistSkipper?>(null)
    private var initialInstallDone = false
    private var retryConsumed = false
    private var lastRoutes = 0
    private var lastFeedRoutes = 0
    private var hasPrechecker = false
    private var relateStripReady = false

    override val capabilityIds: List<String>
        get() = if (!enabled) emptyList() else buildList {
            add(AiDeclaredVideoPolicy.CAPABILITY_DETAIL)
            if (strongMode) add(AiDeclaredVideoPolicy.CAPABILITY_AUTHOR)
        }

    @Synchronized
    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) {
            requiresAdaptationRetry = false
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != TARGET_PACKAGE) {
            requiresAdaptationRetry = false
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val loader = environment.classLoader ?: run {
            requiresAdaptationRetry = false
            return missing(environment, "missing-class-loader")
        }
        if (mainRoutesInstalled.get()) {
            retryConsumed = true
            requiresAdaptationRetry = false
            val playlist = if (!playlistInstalled.get()) {
                installPlaylistSkipper(environment, loader, aiPlaylistDirectorProvider())
            } else {
                playlistRef.get()
            }
            environment.reportCapabilityCoverage(
                AiDeclaredVideoPolicy.CAPABILITY_DETAIL, ready = true, lastRoutes, PATHS
            )
            if (strongMode) {
                environment.reportCapabilityCoverage(
                    AiDeclaredVideoPolicy.CAPABILITY_AUTHOR,
                    ready = environment.writeScanSnapshot != null, lastRoutes, PATHS
                )
            }
            val partial = buildList {
                if (lastRoutes < PATHS) add("routes:$lastRoutes/$PATHS")
                if (!relateStripReady) add("missing-relate-writeback")
                if (lastFeedRoutes == 0) add("missing-relates-feed")
                if (precheck && !hasPrechecker) add("missing-precheck")
                if (playlist == null) add("missing-playlist-skip")
                if (strongMode && environment.writeScanSnapshot == null) add("missing-snapshot-sink")
            }
            environment.reportRuntimeEvidence(id, FeatureRuntimeStage.ADAPTED)
            environment.reportStatus(
                CHANNEL_STATUS,
                if (partial.isEmpty()) "success" else partial.joinToString(prefix = "partial:", separator = "+")
            )
            return FeatureInstallResult.Installed(lastRoutes, complete = partial.isEmpty())
        }
        val interceptor = AiDeclaredReplyInterceptor.resolve(loader) ?: run {
            requiresAdaptationRetry = false
            return missing(environment, "not-applicable-host")
        }
        val wireSurface = com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.KmpGrpcWireAdapter.resolve(loader, kmpGrpcWireEngine)
        val kotlinMossName = KotlinMossBridgeMembers.kotlinMossClassName(DetailUnitedModulePurifyPolicy.MOSS_CLASS)
        val hasKotlinMoss = kotlinMossName != null && KavaMemberLookup.classOrNull(loader, kotlinMossName) != null
        val mossClass = KavaMemberLookup.classOrNull(loader, DetailUnitedModulePurifyPolicy.MOSS_CLASS)
        val requestClass = KavaMemberLookup.classOrNull(loader, DetailUnitedModulePurifyPolicy.REQUEST_CLASS)
        val replyClass = KavaMemberLookup.classOrNull(loader, DetailUnitedModulePurifyPolicy.REPLY_CLASS)
        if (((mossClass == null || requestClass == null) && wireSurface == null && !hasKotlinMoss) || replyClass == null) {
            requiresAdaptationRetry = false
            return missing(environment, "not-applicable-host")
        }
        val handlerClass = KavaMemberLookup.classOrNull(loader, HANDLER_CLASS)

        AiDeclaredVideoRegistry.loadAsync {
            TargetAppStorage.cacheFile(AiDeclaredVideoRegistry.fileName())
        }
        val authors = if (strongMode) {
            ScanSnapshotPublisher(environment, MineComponentSnapshotCodec.SURFACE_AUTHOR_PICKS,
                setOf("home_recommend_author_block"))
        } else {
            null
        }
        val guard = AiRedirectGuard()
        // 缺了就一律按详情页处理：退化成"历史页也会拦"，不会退化成"详情页不拦"。
        val spmidGetter = requestClass?.let { KavaMemberLookup.methodOrNull(it, "getSpmid") }
            ?.takeIf { it.returnType == classOf<String>() }
        if (requestClass != null && spmidGetter == null) {
            environment.logError("ai_declared_spmid_absent", "[BIL] ViewReq#getSpmid 不可用，无法区分历史页后台请求")
        }
        fun passive(request: Any?): Boolean = runCatching {
            request != null && spmidGetter != null &&
                AiDeclaredVideoPolicy.isPassiveRequest(spmidGetter.invoke(request) as? String)
        }.getOrDefault(false)
        val playlist = installPlaylistSkipper(environment, loader, aiPlaylistDirectorProvider())
        // 连播的连锁保险单独计数：一个列表里连着几集 AI 很常见，额度比详情页补位宽。
        val playlistGuard = AiRedirectGuard(maxRedirects = PLAYLIST_SKIPS, windowMillis = 60_000L)
        val prechecker = if (precheck) installPrechecker(environment, loader, interceptor) else null
        val handle: (Any, Boolean) -> Any = { reply, passive ->
            interceptor.process(
                reply, environment, guard, passive,
                playlist = playlistRef.get(), playlistGuard = playlistGuard,
                onRelateCandidates = prechecker?.let { it::submit }
            ) { name, mid ->
                authors?.let { recordAuthor(environment, it, name, mid) }
            }
        }

        var routes = 0
        if (mossClass != null && requestClass != null) {
            runCatching {
                environment.registrar.exact(
                    "ai_declared.sync.${DetailUnitedModulePurifyPolicy.SYNC_METHOD}",
                    mossClass,
                    DetailUnitedModulePurifyPolicy.SYNC_METHOD,
                    requestClass
                ) {
                    after {
                        if (hasThrowable) return@after
                        val original = result ?: return@after
                        val updated = handle(original, passive(argOrNull(0)) || AiRelatePrechecker.isQueryThread())
                        if (updated !== original) result = updated
                    }
                }
                routes++
            }.onFailure {
                environment.logError("ai_declared_sync", "[BIL] AI 声明拦截同步响应 Hook 注册失败: $it")
            }
            if (handlerClass != null) {
                runCatching {
                    environment.registrar.exact(
                        "ai_declared.async.${DetailUnitedModulePurifyPolicy.ASYNC_METHOD}",
                        mossClass,
                        DetailUnitedModulePurifyPolicy.ASYNC_METHOD,
                        requestClass,
                        handlerClass
                    ) {
                        before {
                            val original = argOrNull(1) ?: return@before
                            val isPassive = passive(argOrNull(0)) || AiRelatePrechecker.isQueryThread()
                            val proxy = MossResponseHandlerProxy.wrapTransform(handlerClass, original) { reply ->
                                handle(reply, isPassive)
                            } ?: return@before
                            args[1] = proxy
                        }
                    }
                    routes++
                }.onFailure {
                    environment.logError("ai_declared_async", "[BIL] AI 声明拦截异步响应 Hook 注册失败: $it")
                }
            } else {
                environment.logError(
                    "ai_declared_handler_absent",
                    "[BIL] 未找到 MossResponseHandler，AI 声明拦截只覆盖同步路径"
                )
            }
        }

        val kmpViewWireInstalled = wireSurface != null && KmpGrpcWireChannel.install(
            environment = environment,
            surface = wireSurface,
            javaMossClassName = DetailUnitedModulePurifyPolicy.MOSS_CLASS,
            rpc = DetailUnitedModulePurifyPolicy.ASYNC_METHOD,
            javaReplyClass = replyClass,
            what = "AI 声明拦截",
            logKey = "ai_declared_kmoss",
            javaRequestClass = requestClass,
            transformWithRequest = { req, reply ->
                handle(reply, passive(req) || AiRelatePrechecker.isQueryThread())
            },
            transformJava = { reply -> handle(reply, AiRelatePrechecker.isQueryThread()) }
        )
        val kmpViewInstalled = kmpViewWireInstalled || (hasKotlinMoss && KotlinMossChannel.prepare(
            environment, loader, "AI 声明拦截", "ai_declared_kmoss"
        )?.let { bridge ->
            KotlinMossChannel.install(
                environment = environment,
                loader = loader,
                members = bridge,
                javaMossClassName = DetailUnitedModulePurifyPolicy.MOSS_CLASS,
                rpc = DetailUnitedModulePurifyPolicy.ASYNC_METHOD,
                javaReplyClass = replyClass,
                hookId = "$id.kmoss.${DetailUnitedModulePurifyPolicy.ASYNC_METHOD}",
                what = "AI 声明拦截",
                logKey = "ai_declared_kmoss",
                transformJava = { reply -> handle(reply, AiRelatePrechecker.isQueryThread()) }
            )
        } == true)
        if (routes == 0 && kmpViewInstalled) routes = 1

        if (routes == 0) {
            requiresAdaptationRetry = false
            return missing(environment, "no-safe-united-path")
        }
        val feedRoutes = installRelatesFeed(
            environment, loader, mossClass, handlerClass, interceptor, wireSurface, hasKotlinMoss
        )

        lastRoutes = routes
        lastFeedRoutes = feedRoutes
        hasPrechecker = prechecker != null
        relateStripReady = interceptor.relateStripReady
        mainRoutesInstalled.set(true)
        if (initialInstallDone) {
            retryConsumed = true
            requiresAdaptationRetry = false
        } else {
            initialInstallDone = true
            requiresAdaptationRetry = !retryConsumed && !playlistInstalled.get() && AiPlaylistDirectorAdapter.applicable(loader)
        }

        environment.reportCapabilityCoverage(
            AiDeclaredVideoPolicy.CAPABILITY_DETAIL, ready = true, routes, PATHS
        )
        if (strongMode) {
            environment.reportCapabilityCoverage(
                AiDeclaredVideoPolicy.CAPABILITY_AUTHOR,
                ready = environment.writeScanSnapshot != null, routes, PATHS
            )
        }
        environment.reportRuntimeEvidence(id, FeatureRuntimeStage.ADAPTED)
        val partial = buildList {
            if (routes < PATHS) add("routes:$routes/$PATHS")
            if (!interceptor.relateStripReady) add("missing-relate-writeback")
            if (feedRoutes == 0) add("missing-relates-feed")
            if (precheck && prechecker == null) add("missing-precheck")
            if (playlist == null) add("missing-playlist-skip")
            if (strongMode && environment.writeScanSnapshot == null) add("missing-snapshot-sink")
        }
        environment.reportStatus(
            CHANNEL_STATUS,
            if (partial.isEmpty()) "success" else partial.joinToString(prefix = "partial:", separator = "+")
        )
        environment.logInfo(
            "ai_declared_installed",
            "[BIL] AI 声明拦截已安装，routes=$routes, strong=$strongMode, " +
                "relateStrip=${interceptor.relateStripReady}, relatesFeed=$feedRoutes, " +
                "precheck=${prechecker != null}, playlist=${playlist != null}"
        )
        return FeatureInstallResult.Installed(routes, complete = partial.isEmpty())
    }

    /**
     * 强力模式的推荐后台预检。账号令牌状态只在预检线程上读（读 AccountStorage 可能触盘，不放进 attach），
     * 状态变化才上报，报的只是 ready/not_logged_in/expired/unavailable，令牌原文不出这一层。
     */
    private fun installPrechecker(
        environment: HookEnvironment,
        loader: ClassLoader,
        interceptor: AiDeclaredReplyInterceptor
    ): AiRelatePrechecker? {
        val probe = BiliAccessKeyProbe.resolve(loader)
        val query = AiViewQuery.resolve(loader, interceptor::isDeclared)
        if (probe == null || query == null) {
            environment.logError(
                "ai_declared_precheck_missing",
                "[BIL] 推荐预检锚点缺失(account=${probe != null}, view=${query != null})，不做推荐预检"
            )
            return null
        }
        val lastState = java.util.concurrent.atomic.AtomicReference<BiliAccessKeyState?>(null)
        return AiRelatePrechecker(
            query = query::query,
            ready = {
                val context = ReflectAccess.currentApplication()
                val state = context?.let(probe::state) ?: BiliAccessKeyState.UNAVAILABLE
                if (lastState.getAndSet(state) != state) {
                    environment.reportStatus(ACCESS_KEY_STATUS, state.code)
                }
                state == BiliAccessKeyState.READY
            },
            onDeclared = { aid ->
                if (AiDeclaredVideoRegistry.add(aid)) {
                    environment.reportRuntimeEvidence(AiDeclaredVideoPolicy.ID, FeatureRuntimeStage.APPLIED)
                }
            }
        )
    }

    /**
     * 推荐「加载更多」删已知 AI 卡；独立于详情页拦截，缺失只让这一条降级。
     * @return 注册成功的入口数（同步 + 异步，最多 2）。
     */
    private fun installRelatesFeed(
        environment: HookEnvironment,
        loader: ClassLoader,
        mossClass: Class<*>?,
        handlerClass: Class<*>?,
        interceptor: AiDeclaredReplyInterceptor,
        wireSurface: com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.KmpGrpcWireAdapter.WireSurface?,
        hasKotlinMoss: Boolean
    ): Int {
        val stripper = AiRelatesFeedStripper.resolve(loader)
        val requestClass = KavaMemberLookup.classOrNull(loader, AiRelatesFeedStripper.REQUEST_CLASS)
        val replyClass = KavaMemberLookup.classOrNull(loader, AiRelatesFeedStripper.REPLY_CLASS)
        if (stripper == null || (requestClass == null && wireSurface == null && !hasKotlinMoss) || replyClass == null) {
            environment.logError("ai_declared_feed_missing", "[BIL] 推荐续页结构缺失，加载更多的推荐不做 AI 声明过滤")
            return 0
        }
        val errorLogged = java.util.concurrent.atomic.AtomicBoolean(false)
        val transform: (Any) -> Any = transform@{ reply ->
            runCatching {
                val (updated, removed) = stripper.strip(reply, interceptor::isKnownAiCard) ?: return@transform reply
                environment.reportRuntimeEvidence(AiDeclaredVideoPolicy.ID, FeatureRuntimeStage.APPLIED, removed)
                updated
            }.getOrElse {
                environment.reportRuntimeEvidence(AiDeclaredVideoPolicy.ID, FeatureRuntimeStage.ERROR)
                if (errorLogged.compareAndSet(false, true)) {
                    environment.logError("ai_declared_feed_copy", "[BIL] 推荐续页 AI 卡过滤失败，保留原响应: ${it.javaClass.simpleName}")
                }
                reply
            }
        }
        var installed = 0
        if (mossClass != null && requestClass != null) {
            runCatching {
                environment.registrar.exact(
                    "ai_declared.feed.sync", mossClass, AiRelatesFeedStripper.SYNC_METHOD, requestClass
                ) {
                    after {
                        if (hasThrowable) return@after
                        val original = result ?: return@after
                        val updated = transform(original)
                        if (updated !== original) result = updated
                    }
                }
                installed++
            }.onFailure {
                environment.logError("ai_declared_feed_sync", "[BIL] 推荐续页同步 Hook 注册失败: $it")
            }
            if (handlerClass != null) {
                runCatching {
                    environment.registrar.exact(
                        "ai_declared.feed.async", mossClass, AiRelatesFeedStripper.ASYNC_METHOD, requestClass, handlerClass
                    ) {
                        before {
                            val original = argOrNull(1) ?: return@before
                            args[1] = MossResponseHandlerProxy.wrapTransform(handlerClass, original, transform) ?: return@before
                        }
                    }
                    installed++
                }.onFailure {
                    environment.logError("ai_declared_feed_async", "[BIL] 推荐续页异步 Hook 注册失败: $it")
                }
            }
        }
        val kmpWireInstalled = wireSurface != null && KmpGrpcWireChannel.install(
            environment = environment,
            surface = wireSurface,
            javaMossClassName = DetailUnitedModulePurifyPolicy.MOSS_CLASS,
            rpc = AiRelatesFeedStripper.ASYNC_METHOD,
            javaReplyClass = replyClass,
            what = "推荐续页 AI 卡过滤",
            logKey = "ai_declared_feed_kmoss",
            transformJava = transform
        )
        val kmpFeedInstalled = kmpWireInstalled || (hasKotlinMoss && KotlinMossChannel.prepare(
            environment, loader, "推荐续页 AI 卡过滤", "ai_declared_feed_kmoss"
        )?.let { bridge ->
            KotlinMossChannel.install(
                environment = environment,
                loader = loader,
                members = bridge,
                javaMossClassName = DetailUnitedModulePurifyPolicy.MOSS_CLASS,
                rpc = AiRelatesFeedStripper.ASYNC_METHOD,
                javaReplyClass = replyClass,
                hookId = "${AiDeclaredVideoPolicy.ID}.feed.kmoss.${AiRelatesFeedStripper.ASYNC_METHOD}",
                what = "推荐续页 AI 卡过滤",
                logKey = "ai_declared_feed_kmoss",
                transformJava = transform
            )
        } == true)
        if (kmpFeedInstalled && installed == 0) installed++
        return installed
    }

    /**
     * 连播跳过的两个 Hook：记下最新的连播调度对象、在条目构造时给已知 AI 标失效。
     * 任一环节缺失返回 null，连播退回详情页那条行为（离开列表跳转补位），状态报 partial。
     */
    private fun installPlaylistSkipper(
        environment: HookEnvironment,
        loader: ClassLoader,
        cachedDirector: String?
    ): AiPlaylistSkipper? {
        if (playlistInstalled.get()) return playlistRef.get()
        val skipper = AiPlaylistSkipper.resolve(loader, cachedDirector) ?: run {
            environment.logError("ai_declared_playlist_missing", "[BIL] 连播跳过锚点缺失，连播中将按详情页方式改道")
            return null
        }
        return runCatching {
            val director = skipper.directorClass
            val descriptor = checkNotNull(KavaMemberLookup.classOrNull(loader, AiPlaylistSkipper.MEDIA_DESCRIPTOR_CLASS))
            director.declaredConstructors.forEachIndexed { index, constructor ->
                environment.registrar.constructor("ai_declared.playlist.director.$index", constructor) {
                    after { thisObject?.let(skipper::onDirectorCreated) }
                }
            }
            environment.registrar.exact(
                "ai_declared.playlist.construct", descriptor, "constructWith", classOf<Array<Any>>()
            ) {
                before {
                    val target = thisObject ?: return@before
                    @Suppress("UNCHECKED_CAST")
                    val values = argOrNull(0) as? Array<Any?> ?: return@before
                    if (skipper.onConstruct(target, values)) {
                        environment.reportRuntimeEvidence(AiDeclaredVideoPolicy.CAPABILITY_DETAIL, FeatureRuntimeStage.APPLIED)
                    }
                }
            }
            playlistRef.set(skipper)
            playlistInstalled.set(true)
            skipper
        }.getOrElse {
            environment.logError("ai_declared_playlist_register", "[BIL] 连播跳过 Hook 注册失败: $it")
            null
        }
    }

    /**
     * 本进程已发布过的发布者（名字小写）。
     *
     * 发布与否**只**看这份集合：`AuthorPickSession` 上限 64 且与三点面板共用，写满后 `add` 恒为 false，
     * 早先以它的返回值决定发布，会让第 33 位之后的发布者既不即时生效也进不了名单。
     */
    private val publishedAuthors = AiAuthorLedger(MineComponentSnapshotCodec.MAX_ENTRY_COUNT)

    /** 强力模式：本进程立即生效（尽力而为）+ 发布到点选观测面，长期名单由模块 App 并入。 */
    private fun recordAuthor(
        environment: HookEnvironment,
        publisher: ScanSnapshotPublisher,
        name: String?,
        mid: Long
    ) {
        val safeName = name?.trim()?.takeIf(String::isNotEmpty)
        mid.takeIf { it > 0 }?.let { AuthorPickSession.add(it.toString()) }
        if (safeName == null) return
        AuthorPickSession.add(safeName)
        if (!publishedAuthors.claim(safeName)) return
        publisher.accumulate(
            MineComponentScanEntry.create(
                "author", safeName, safeName, AiDeclaredVideoPolicy.AUTHOR_PICK_ORIGIN_URI, true
            )?.copy(selectionToken = java.util.UUID.randomUUID().toString())
        )
        environment.reportRuntimeEvidence(AiDeclaredVideoPolicy.CAPABILITY_AUTHOR, FeatureRuntimeStage.APPLIED)
        // 日志只记事件，不记 UP 名（名字会经观测快照呈现给用户，不必再进日志）。
        environment.logInfo("ai_declared_author_recorded", "[BIL] 强力模式已记下一位发布者，待模块 App 并入名单")
    }

    private fun missing(environment: HookEnvironment, reason: String): FeatureInstallResult.Skipped {
        environment.reportStatus(CHANNEL_STATUS, reason)
        environment.logError("ai_declared_missing", "[BIL] AI 声明拦截适配不完整: $reason")
        return FeatureInstallResult.Skipped(reason)
    }

    private companion object {
        const val TARGET_PACKAGE = "tv.danmaku.bili"
        const val CHANNEL_STATUS = "ai_declared_video_status"
        const val ACCESS_KEY_STATUS = "ai_declared_access_key_status"
        const val HANDLER_CLASS = "com.bilibili.lib.moss.api.MossResponseHandler"
        const val PATHS = 2
        const val PLAYLIST_SKIPS = 10
    }
}

/**
 * 强力模式"这位发布者本进程发布过没有"的账本；与 [AuthorPickSession] 的容量彻底脱钩。
 * 上限对齐观测快照的条目上限——再多发布也会被快照截断。
 */
internal class AiAuthorLedger(private val max: Int) {
    private val names = HashSet<String>()

    /** 返回 true 表示这次应当发布（首次见到且未超上限）。 */
    @Synchronized
    fun claim(name: String): Boolean {
        val key = name.trim().lowercase()
        if (key.isEmpty() || key in names || names.size >= max) return false
        return names.add(key)
    }
}

/** 安装期解析全部反射，回调内只 `invoke`。 */
internal class AiDeclaredReplyInterceptor private constructor(
    private val reply: Class<*>,
    private val replyPlan: ProtobufBuilderPlan,
    private val defaultInstance: Any?,
    private val read: Readers,
    private val rewrite: EcodeWriter,
    private val strip: RelateStripChain?
) {
    val relateStripReady: Boolean get() = strip != null

    /** 一份 View 响应是不是带声明（预检查询用）。 */
    fun isDeclared(candidate: Any): Boolean = reply.isInstance(candidate) && read.facts(candidate).declared

    /** 推荐卡是不是本进程已知的 AI 视频；推荐续页复用同一判定。 */
    fun isKnownAiCard(card: Any): Boolean = read.isKnownAiCard(card)

    /** 从一份响应里读出的最小事实；宿主消息不越过这一层。 */
    private data class Facts(
        val aid: Long,
        val ownerName: String?,
        val ownerMid: Long,
        val declared: Boolean,
        val candidates: List<AiDeclaredVideoPolicy.RelateCandidate>
    )

    /**
     * @param passive 非详情页的后台请求（见 [AiDeclaredVideoPolicy.isPassiveRequest]）：
     *   只把命中的 aid 记进注册表，不改写、不提示、不记发布者、不占连锁保险。
     */
    fun process(
        original: Any,
        environment: HookEnvironment,
        guard: AiRedirectGuard,
        passive: Boolean = false,
        playlist: AiPlaylistRoute? = null,
        playlistGuard: AiRedirectGuard = guard,
        onRelateCandidates: ((List<Long>) -> Unit)? = null,
        onDeclaredAuthor: (String?, Long) -> Unit
    ): Any = runCatching {
        if (!reply.isInstance(original)) return@runCatching original
        if (defaultInstance != null && original === defaultInstance) return@runCatching original
        environment.reportRuntimeEvidence(AiDeclaredVideoPolicy.ID, FeatureRuntimeStage.OBSERVED)
        val facts = read.facts(original)
        if (passive) {
            if (facts.declared) AiDeclaredVideoRegistry.add(facts.aid)
            return@runCatching original
        }
        // 服务端已经下发了错误码（真的 404、仅自己可见、青少年模式）就交给宿主，不覆盖。
        if (rewrite.hasHostError(original)) return@runCatching original
        if (facts.declared) {
            environment.reportRuntimeEvidence(AiDeclaredVideoPolicy.CAPABILITY_DETAIL, FeatureRuntimeStage.OBSERVED)
            AiDeclaredVideoRegistry.add(facts.aid)
            runCatching { onDeclaredAuthor(facts.ownerName, facts.ownerMid) }.onFailure {
                environment.logError("ai_declared_author_failed", "[BIL] 强力模式记录发布者失败: ${it.javaClass.simpleName}")
            }
            playlist?.takeIf { it.ownsPlaylistItem(facts.aid) }?.let { route ->
                return@runCatching skipInPlaylist(original, route, environment, playlistGuard)
            }
            return@runCatching intercept(original, facts, environment, guard)
        }
        // 强力模式预检：只为用户真正打开的详情页排队，失败不影响本次响应。
        onRelateCandidates?.let { submit ->
            runCatching { submit(facts.candidates.filter { it.isVideo }.map { it.aid }) }
        }
        val chain = strip ?: return@runCatching original
        if (facts.candidates.none { it.isVideo && AiDeclaredVideoRegistry.contains(it.aid) }) {
            return@runCatching original
        }
        val removed = intArrayOf(0)
        val updated = chain.rebuild(original) { card ->
            val hit = read.isKnownAiCard(card)
            if (hit) removed[0]++
            !hit
        } ?: return@runCatching original
        check(read.facts(updated).candidates.none { it.isVideo && AiDeclaredVideoRegistry.contains(it.aid) }) {
            "relate strip readback failed"
        }
        environment.reportRuntimeEvidence(AiDeclaredVideoPolicy.ID, FeatureRuntimeStage.APPLIED, removed[0])
        updated
    }.getOrElse {
        environment.reportRuntimeEvidence(AiDeclaredVideoPolicy.ID, FeatureRuntimeStage.ERROR)
        environment.logError("ai_declared_copy_failed", "[BIL] AI 声明拦截改写失败，保留原响应: $it")
        original
    }

    /**
     * 连播里的条目：不改写跳转（那会离开整个列表），让宿主自己切下一集，响应原样交付。
     * 没有下一集、连播已销毁或连续跳太多次 → 宿主原生提示页，仍停留在列表里。
     */
    private fun skipInPlaylist(
        original: Any,
        route: AiPlaylistRoute,
        environment: HookEnvironment,
        playlistGuard: AiRedirectGuard
    ): Any {
        val messages = InjectedUiLocale.messages()
        val skipped = playlistGuard.tryAcquire() && route.skipToNext()
        val updated = if (skipped) original else rewrite.block(original, messages.aiDeclaredBlockedHint)
        environment.reportRuntimeEvidence(AiDeclaredVideoPolicy.CAPABILITY_DETAIL, FeatureRuntimeStage.APPLIED)
        environment.reportRuntimeEvidence(AiDeclaredVideoPolicy.ID, FeatureRuntimeStage.APPLIED)
        environment.logInfo(
            "ai_declared_playlist",
            "[BIL] 连播中遇到含 AI 生成声明的视频，" + if (skipped) "已切到下一集" else "无下一集，显示提示页"
        )
        ReflectAccess.currentApplication()?.let { context ->
            VersionAdapter.showAdaptToast(
                context,
                if (skipped) messages.aiDeclaredPlaylistSkippedToast else messages.aiDeclaredBlockedHint
            )
        }
        return updated
    }

    private fun intercept(
        original: Any,
        facts: Facts,
        environment: HookEnvironment,
        guard: AiRedirectGuard
    ): Any {
        val candidate = facts.candidates.firstOrNull { candidate ->
            AiDeclaredVideoPolicy.isReplacementCandidate(
                candidate, facts.aid, facts.ownerMid,
                AiDeclaredVideoRegistry::contains, AuthorPickSession::matches
            )
        }
        // 先找到候选再占用额度：没有候选时不该消耗连锁保险的次数。
        val redirect = candidate?.uri?.takeIf { guard.tryAcquire() }
        val messages = InjectedUiLocale.messages()
        val updated = if (redirect != null) {
            rewrite.redirect(original, redirect)
        } else {
            rewrite.block(original, messages.aiDeclaredBlockedHint)
        }
        environment.reportRuntimeEvidence(AiDeclaredVideoPolicy.CAPABILITY_DETAIL, FeatureRuntimeStage.APPLIED)
        environment.reportRuntimeEvidence(AiDeclaredVideoPolicy.ID, FeatureRuntimeStage.APPLIED)
        environment.logInfo(
            "ai_declared_intercepted",
            "[BIL] 已拦截含 AI 生成声明的视频，" + if (redirect != null) "已改为跳转补位视频" else "无可用补位，显示提示页"
        )
        ReflectAccess.currentApplication()?.let { context ->
            VersionAdapter.showAdaptToast(
                context,
                if (redirect != null) messages.aiDeclaredRedirectToast else messages.aiDeclaredBlockedHint
            )
        }
        return updated
    }

    /** 读链：全部是 getter，缺任一环节在安装期就判不可用。 */
    private class Readers(
        val arcPresence: Method,
        val arcGetter: Method,
        val aidGetter: Method,
        val ownerPresence: Method,
        val ownerGetter: Method,
        val ownerMid: Method,
        val ownerTitle: Method,
        val tabPresence: Method,
        val tabGetter: Method,
        val tabModuleList: Method,
        val introPresence: Method,
        val introGetter: Method,
        val modulesList: Method,
        val moduleClass: Class<*>,
        val ugcPresence: Method,
        val ugcGetter: Method,
        val neutralPresence: Method,
        val neutralGetter: Method,
        val neutralTitle: Method,
        val relatesPresence: Method,
        val relatesGetter: Method,
        val cardsList: Method,
        val cardTypeValue: Method,
        val avTypeValue: Int,
        val basicPresence: Method,
        val basicGetter: Method,
        val basicId: Method,
        val basicUri: Method,
        val authorPresence: Method,
        val authorGetter: Method
    ) {
        fun facts(reply: Any): Facts {
            val aid = if (bool(arcPresence, reply)) {
                arcGetter.invoke(reply)?.let { (aidGetter.invoke(it) as? Number)?.toLong() } ?: 0L
            } else 0L
            val owner = if (bool(ownerPresence, reply)) ownerGetter.invoke(reply) else null
            var declared = false
            val candidates = ArrayList<AiDeclaredVideoPolicy.RelateCandidate>()
            forEachModule(reply) { module ->
                if (!declared && bool(ugcPresence, module)) {
                    val ugc = ugcGetter.invoke(module)
                    if (ugc != null && bool(neutralPresence, ugc)) {
                        val title = neutralGetter.invoke(ugc)?.let { neutralTitle.invoke(it) as? String }
                        if (AiDeclaredVideoPolicy.isAiDeclaration(title)) declared = true
                    }
                }
                if (bool(relatesPresence, module)) {
                    val relates = relatesGetter.invoke(module) ?: return@forEachModule
                    (cardsList.invoke(relates) as? List<*>)?.forEach { card ->
                        if (card != null) candidates += candidate(card)
                    }
                }
            }
            return Facts(
                aid = aid,
                ownerName = owner?.let { ownerTitle.invoke(it) as? String },
                ownerMid = owner?.let { (ownerMid.invoke(it) as? Number)?.toLong() } ?: 0L,
                declared = declared,
                candidates = candidates
            )
        }

        fun candidate(card: Any): AiDeclaredVideoPolicy.RelateCandidate {
            val isVideo = (cardTypeValue.invoke(card) as? Int) == avTypeValue
            val basic = if (bool(basicPresence, card)) basicGetter.invoke(card) else null
            val author = basic?.takeIf { bool(authorPresence, it) }?.let { authorGetter.invoke(it) }
            return AiDeclaredVideoPolicy.RelateCandidate(
                isVideo = isVideo,
                aid = basic?.let { (basicId.invoke(it) as? Number)?.toLong() } ?: 0L,
                uri = basic?.let { basicUri.invoke(it) as? String },
                authorName = author?.let { ownerTitle.invoke(it) as? String },
                authorMid = author?.let { (ownerMid.invoke(it) as? Number)?.toLong() } ?: 0L
            )
        }

        fun isKnownAiCard(card: Any): Boolean {
            val fact = candidate(card)
            return fact.isVideo && AiDeclaredVideoRegistry.contains(fact.aid)
        }

        private inline fun forEachModule(reply: Any, block: (Any) -> Unit) {
            if (!bool(tabPresence, reply)) return
            val tab = tabGetter.invoke(reply) ?: return
            (tabModuleList.invoke(tab) as? List<*>)?.forEach { tabModule ->
                if (tabModule == null || !bool(introPresence, tabModule)) return@forEach
                val intro = introGetter.invoke(tabModule) ?: return@forEach
                (modulesList.invoke(intro) as? List<*>)?.forEach { module ->
                    if (module != null && moduleClass.isInstance(module)) block(module)
                }
            }
        }
    }

    /** 只写 `ecode` 与 `ecode_config`，写完回读 `getEcodeValue`。 */
    private class EcodeWriter(
        private val replyPlan: ProtobufBuilderPlan,
        private val setEcodeValue: Method,
        private val setEcodeConfig: Method,
        private val getEcodeValue: Method,
        private val configPlan: ProtobufBuilderPlan,
        private val configDefault: Any,
        private val setRedirectUrl: Method,
        private val setMsg: Method,
        private val notFoundValue: Int,
        private val privacyValue: Int
    ) {
        /** 同一份响应再过一次（或服务端本就报错）时保持幂等：已有非 0 错误码就不再改写。 */
        fun hasHostError(reply: Any): Boolean = (getEcodeValue.invoke(reply) as? Int ?: 0) != 0

        fun redirect(original: Any, url: String): Any = write(original, notFoundValue, url, "")

        fun block(original: Any, message: String): Any = write(original, privacyValue, "", message)

        private fun write(original: Any, code: Int, url: String, message: String): Any {
            val config = configPlan.edit(configDefault) { target ->
                setRedirectUrl.invoke(target, url)
                setMsg.invoke(target, message)
            }
            val updated = replyPlan.edit(original) { target ->
                setEcodeValue.invoke(target, code)
                setEcodeConfig.invoke(target, config)
            }
            check(getEcodeValue.invoke(updated) == code) { "ecode readback failed" }
            return updated
        }
    }

    /**
     * `ViewReply → Tab → TabModule → IntroductionTab → Module → Relates.cards` 的按需复制链。
     * 某一层没有命中就完全不复制那一层；一个都没命中返回 null。
     */
    private class RelateStripChain(
        private val readers: Readers,
        private val replyPlan: ProtobufBuilderPlan,
        private val setTab: Method,
        private val tabPlan: ProtobufBuilderPlan,
        private val clearTabModule: Method,
        private val addAllTabModule: Method,
        private val tabModulePlan: ProtobufBuilderPlan,
        private val setIntroduction: Method,
        private val introPlan: ProtobufBuilderPlan,
        private val clearModules: Method,
        private val addAllModules: Method,
        private val modulePlan: ProtobufBuilderPlan,
        private val setRelates: Method,
        private val relatesPlan: ProtobufBuilderPlan,
        private val clearCards: Method,
        private val addAllCards: Method
    ) {
        fun rebuild(reply: Any, keep: (Any) -> Boolean): Any? = with(readers) {
            if (!bool(tabPresence, reply)) return null
            val tab = tabGetter.invoke(reply) ?: return null
            val tabModules = (tabModuleList.invoke(tab) as? List<*>)?.map { it ?: return null } ?: return null
            var newTabModules: MutableList<Any>? = null
            tabModules.forEachIndexed { tabIndex, tabModule ->
                if (!bool(introPresence, tabModule)) return@forEachIndexed
                val intro = introGetter.invoke(tabModule) ?: return@forEachIndexed
                val modules = (modulesList.invoke(intro) as? List<*>)?.map { it ?: return null } ?: return@forEachIndexed
                var newModules: MutableList<Any>? = null
                modules.forEachIndexed { moduleIndex, module ->
                    if (!moduleClass.isInstance(module) || !bool(relatesPresence, module)) return@forEachIndexed
                    val relates = relatesGetter.invoke(module) ?: return@forEachIndexed
                    val cards = cardsList.invoke(relates) as? List<*> ?: return@forEachIndexed
                    val retained = ProtobufListRetention.retainOrNull(cards, keep) ?: return@forEachIndexed
                    val newRelates = relatesPlan.edit(relates) { target ->
                        clearCards.invoke(target)
                        addAllCards.invoke(target, retained)
                    }
                    val newModule = modulePlan.edit(module) { target -> setRelates.invoke(target, newRelates) }
                    (newModules ?: modules.toMutableList().also { newModules = it })[moduleIndex] = newModule
                }
                val rebuiltModules = newModules ?: return@forEachIndexed
                val newIntro = introPlan.edit(intro) { target ->
                    clearModules.invoke(target)
                    addAllModules.invoke(target, rebuiltModules)
                }
                val newTabModule = tabModulePlan.edit(tabModule) { target -> setIntroduction.invoke(target, newIntro) }
                (newTabModules ?: tabModules.toMutableList().also { newTabModules = it })[tabIndex] = newTabModule
            }
            val rebuiltTabModules = newTabModules ?: return null
            val newTab = tabPlan.edit(tab) { target ->
                clearTabModule.invoke(target)
                addAllTabModule.invoke(target, rebuiltTabModules)
            }
            replyPlan.edit(reply) { target -> setTab.invoke(target, newTab) }
        }
    }

    companion object {
        private const val V1 = "com.bapis.bilibili.app.viewunite.v1."
        private const val COMMON = "com.bapis.bilibili.app.viewunite.common."

        /**
         * @param v1 / [common] 只为单测替身开的口子：共享的 `com.bapis` 替身被 VersionAdapter 的
         *   结构定位测试依赖，往里加 getter 会改变它们的唯一性判定，所以本功能的替身住在独立包里。
         */
        fun resolve(
            loader: ClassLoader,
            v1: String = V1,
            common: String = COMMON
        ): AiDeclaredReplyInterceptor? = runCatching {
            fun cls(name: String) = KavaMemberLookup.classOrNull(loader, name)
            fun getter(owner: Class<*>, name: String): Method? = KavaMemberLookup.methodOrNull(owner, name)
            fun bool(owner: Class<*>, name: String): Method? =
                getter(owner, name)?.takeIf { it.returnType == classOf<Boolean>() }
            fun list(owner: Class<*>, name: String): Method? =
                getter(owner, name)?.takeIf { it.returnType isSubclassOf classOf<List<*>>() }
            fun staticInt(owner: Class<*>, name: String): Int? =
                KavaMemberLookup.fieldOrNull(owner, name)
                    ?.takeIf { it.isStatic && it.type == classOf<Int>() }
                    ?.let { runCatching { it.getInt(null) }.getOrNull() }

            val reply = cls(v1 + "ViewReply") ?: return null
            val arc = cls(v1 + "Arc") ?: return null
            val owner = cls(common + "Owner") ?: return null
            val tab = cls(v1 + "Tab") ?: return null
            val tabModule = cls(v1 + "TabModule") ?: return null
            val intro = cls(v1 + "IntroductionTab") ?: return null
            val module = cls(common + "Module") ?: return null
            val ugc = cls(common + "UgcIntroduction") ?: return null
            val neutral = cls(common + "Neutral") ?: return null
            val relates = cls(common + "Relates") ?: return null
            val card = cls(common + "RelateCard") ?: return null
            val cardType = cls(common + "RelateCardType") ?: return null
            val basic = cls(common + "CardBasicInfo") ?: return null
            val ecode = cls(v1 + "ECode") ?: return null
            val ecodeConfig = cls(v1 + "ECodeConfig") ?: return null

            val readers = Readers(
                arcPresence = bool(reply, "hasArc") ?: return null,
                arcGetter = getter(reply, "getArc")?.takeIf { it.returnType == arc } ?: return null,
                aidGetter = getter(arc, "getAid")?.takeIf { it.returnType == classOf<Long>() } ?: return null,
                ownerPresence = bool(reply, "hasOwner") ?: return null,
                ownerGetter = getter(reply, "getOwner")?.takeIf { it.returnType == owner } ?: return null,
                ownerMid = getter(owner, "getMid")?.takeIf { it.returnType == classOf<Long>() } ?: return null,
                ownerTitle = getter(owner, "getTitle")?.takeIf { it.returnType == classOf<String>() } ?: return null,
                tabPresence = bool(reply, "hasTab") ?: return null,
                tabGetter = getter(reply, "getTab")?.takeIf { it.returnType == tab } ?: return null,
                tabModuleList = list(tab, "getTabModuleList") ?: return null,
                introPresence = bool(tabModule, "hasIntroduction") ?: return null,
                introGetter = getter(tabModule, "getIntroduction")?.takeIf { it.returnType == intro } ?: return null,
                modulesList = list(intro, "getModulesList") ?: return null,
                moduleClass = module,
                ugcPresence = bool(module, "hasUgcIntroduction") ?: return null,
                ugcGetter = getter(module, "getUgcIntroduction")?.takeIf { it.returnType == ugc } ?: return null,
                neutralPresence = bool(ugc, "hasNeutral") ?: return null,
                neutralGetter = getter(ugc, "getNeutral")?.takeIf { it.returnType == neutral } ?: return null,
                neutralTitle = getter(neutral, "getTitle")?.takeIf { it.returnType == classOf<String>() } ?: return null,
                relatesPresence = bool(module, "hasRelates") ?: return null,
                relatesGetter = getter(module, "getRelates")?.takeIf { it.returnType == relates } ?: return null,
                cardsList = list(relates, "getCardsList") ?: return null,
                cardTypeValue = getter(card, "getRelateCardTypeValue")?.takeIf { it.returnType == classOf<Int>() }
                    ?: return null,
                avTypeValue = staticInt(cardType, AiDeclaredVideoPolicy.RELATE_AV_TYPE_CONSTANT) ?: return null,
                basicPresence = bool(card, "hasBasicInfo") ?: return null,
                basicGetter = getter(card, "getBasicInfo")?.takeIf { it.returnType == basic } ?: return null,
                basicId = getter(basic, "getId")?.takeIf { it.returnType == classOf<Long>() } ?: return null,
                basicUri = getter(basic, "getUri")?.takeIf { it.returnType == classOf<String>() } ?: return null,
                authorPresence = bool(basic, "hasAuthor") ?: return null,
                authorGetter = getter(basic, "getAuthor")?.takeIf { it.returnType == owner } ?: return null
            )

            // Builder 类名被混淆（实测 `ViewReply$b`），一律经 newBuilder(...).returnType 取。
            val replyPlan = ProtobufBuilderPlan.resolve(reply) ?: return null
            val configPlan = ProtobufBuilderPlan.resolve(ecodeConfig) ?: return null
            val writer = EcodeWriter(
                replyPlan = replyPlan,
                setEcodeValue = replyPlan.method("setEcodeValue", classOf<Int>()) ?: return null,
                setEcodeConfig = replyPlan.method("setEcodeConfig", ecodeConfig) ?: return null,
                getEcodeValue = getter(reply, "getEcodeValue")?.takeIf { it.returnType == classOf<Int>() }
                    ?: return null,
                configPlan = configPlan,
                configDefault = KavaMemberLookup.methodOrNull(ecodeConfig, "getDefaultInstance")
                    ?.invoke(null) ?: return null,
                setRedirectUrl = configPlan.method("setRedirectUrl", classOf<String>()) ?: return null,
                setMsg = configPlan.method("setMsg", classOf<String>()) ?: return null,
                notFoundValue = staticInt(ecode, "CODE_404_VALUE") ?: return null,
                privacyValue = staticInt(ecode, "CODE_ARC_PRIVACY_VALUE") ?: return null
            )

            // 相关推荐删卡是第二条链：缺任一环节只让它降级，拦截与补位照常。
            val strip = runCatching {
                val tabPlan = ProtobufBuilderPlan.resolve(tab) ?: return@runCatching null
                val tabModulePlan = ProtobufBuilderPlan.resolve(tabModule) ?: return@runCatching null
                val introPlan = ProtobufBuilderPlan.resolve(intro) ?: return@runCatching null
                val modulePlan = ProtobufBuilderPlan.resolve(module) ?: return@runCatching null
                val relatesPlan = ProtobufBuilderPlan.resolve(relates) ?: return@runCatching null
                val iterable = classOf<Iterable<*>>()
                RelateStripChain(
                    readers = readers,
                    replyPlan = replyPlan,
                    setTab = replyPlan.method("setTab", tab) ?: return@runCatching null,
                    tabPlan = tabPlan,
                    clearTabModule = tabPlan.method("clearTabModule") ?: return@runCatching null,
                    addAllTabModule = tabPlan.method("addAllTabModule", iterable) ?: return@runCatching null,
                    tabModulePlan = tabModulePlan,
                    setIntroduction = tabModulePlan.method("setIntroduction", intro) ?: return@runCatching null,
                    introPlan = introPlan,
                    clearModules = introPlan.method("clearModules") ?: return@runCatching null,
                    addAllModules = introPlan.method("addAllModules", iterable) ?: return@runCatching null,
                    modulePlan = modulePlan,
                    setRelates = modulePlan.method("setRelates", relates) ?: return@runCatching null,
                    relatesPlan = relatesPlan,
                    clearCards = relatesPlan.method("clearCards") ?: return@runCatching null,
                    addAllCards = relatesPlan.method("addAllCards", iterable) ?: return@runCatching null
                )
            }.getOrNull()

            val defaultInstance = runCatching {
                KavaMemberLookup.methodOrNull(reply, "getDefaultInstance")?.invoke(null)
            }.getOrNull()
            AiDeclaredReplyInterceptor(reply, replyPlan, defaultInstance, readers, writer, strip)
        }.getOrNull()

        private fun bool(method: Method, target: Any): Boolean = method.invoke(target) as? Boolean == true
    }
}
