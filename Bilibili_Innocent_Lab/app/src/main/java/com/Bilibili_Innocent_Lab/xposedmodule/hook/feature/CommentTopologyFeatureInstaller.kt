package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.comment.ReplyTopologyAnchorBinder
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.comment.ReplyTopologySeedBridge
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isSubclassOf
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal typealias ReplyTopologyAnchorView =
    com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.comment.ReplyTopologyAnchorView

internal typealias ReplyTopologyCoordinator =
    com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.comment.ReplyTopologyCoordinator

internal enum class ReplyTopologyBindingScope {
    OWNER,
    PRIMARY,
    SECONDARY,
    UNKNOWN
}

internal enum class ReplyTopologyBindingAction {
    SHOW,
    CLEAR_AND_RETRY,
    RETRY_ONLY,
    IGNORE
}

internal fun replyTopologyBindingAction(
    scope: ReplyTopologyBindingScope,
    hasSeed: Boolean
): ReplyTopologyBindingAction = when {
    scope == ReplyTopologyBindingScope.SECONDARY -> ReplyTopologyBindingAction.IGNORE
    scope == ReplyTopologyBindingScope.UNKNOWN -> ReplyTopologyBindingAction.RETRY_ONLY
    hasSeed -> ReplyTopologyBindingAction.SHOW
    scope == ReplyTopologyBindingScope.OWNER || scope == ReplyTopologyBindingScope.PRIMARY ->
        ReplyTopologyBindingAction.CLEAR_AND_RETRY
    else -> ReplyTopologyBindingAction.IGNORE
}

internal fun replyTopologyMessageScope(
    primaryPresent: Boolean,
    primaryVisible: Boolean,
    secondaryPresent: Boolean,
    secondaryVisible: Boolean
): ReplyTopologyBindingScope = when {
    primaryVisible && !secondaryVisible -> ReplyTopologyBindingScope.PRIMARY
    secondaryVisible && !primaryVisible -> ReplyTopologyBindingScope.SECONDARY
    primaryPresent && !secondaryPresent -> ReplyTopologyBindingScope.PRIMARY
    secondaryPresent && !primaryPresent -> ReplyTopologyBindingScope.SECONDARY
    else -> ReplyTopologyBindingScope.UNKNOWN
}

/**
 * 高版本评论 Handler 同时存在多个真实绑定分支；Adapter 缓存点只负责给出已验证的
 * Handler 锚点，运行时再按同一 ViewBinding 结构规则补齐精确签名，避免只挂到某个分支。
 */
internal fun collectReplyTopologyHighBindPoints(
    owner: Class<*>,
    cachedPoint: VersionAdapter.HookPoint
): List<VersionAdapter.HookPoint> {
    val declaredMethods = KavaMemberLookup.declaredMethods(owner)
    val cachedParams = cachedPoint.paramClassNames ?: return listOf(cachedPoint)
    val cachedMethod = declaredMethods.singleOrNull { method ->
        method.name == cachedPoint.methodName &&
            method.parameterTypes.map { it.name } == cachedParams
    } ?: return listOf(cachedPoint)

    fun looksLikeViewBinding(parameterType: Class<*>): Boolean {
        if (parameterType.isPrimitive || parameterType.isArray || parameterType.isInterface) {
            return false
        }
        return KavaMemberLookup.methods(
            parameterType,
            includeSuperclasses = true
        ) { candidate ->
            candidate.name == "getRoot" && candidate.parameterCount == 0 &&
                candidate.returnType isSubclassOf classOf<View>()
        }.isNotEmpty() || KavaMemberLookup.declaredFields(parameterType) { candidate ->
            candidate.type isSubclassOf classOf<View>()
        }.isNotEmpty()
    }

    val bindingTypes = cachedMethod.parameterTypes.filter(::looksLikeViewBinding)
    if (bindingTypes.size != 1) return listOf(cachedPoint)
    val bindingType = bindingTypes.single()

    return buildList {
        add(cachedPoint)
        declaredMethods.forEach { method ->
            if (method.isStatic || method.isSynthetic || method.isBridge) {
                return@forEach
            }
            if (method.parameterCount !in 1..5) return@forEach
            if (method.returnType != Void.TYPE || bindingType !in method.parameterTypes) {
                return@forEach
            }
            add(
                VersionAdapter.HookPoint(
                    className = owner.name,
                    methodName = method.name,
                    paramClassNames = method.parameterTypes.map { it.name }
                )
            )
        }
    }.distinctBy { point ->
        "${point.className}#${point.methodName}(${point.paramClassNames.orEmpty()})"
    }
}

internal data class ConstrainedAnchorPlan(
    val updateNeighbor: Boolean,
    val updateReference: Boolean
)

/**
 * 约束布局锚点插入规划：
 * - 无邻居（例如传统操作栏独立右对齐 `more_button`）：不修改兄弟节点约束，仅禁止 reference 存在冲突反向约束；
 * - 单邻居（例如 exp3 `dislike_button -> more_button`）：重连邻居与互指约束（若有）；
 * - 多邻居：按歧义失败封闭。
 */
internal fun planConstrainedAnchorInsertion(
    neighborCount: Int,
    referenceInverseConstraint: Int,
    neighborViewId: Int?
): ConstrainedAnchorPlan {
    check(neighborCount <= 1) {
        "constraint-neighbor-count=$neighborCount"
    }
    if (neighborViewId == null) {
        check(referenceInverseConstraint == View.NO_ID) {
            "constraint-reference-conflict=$referenceInverseConstraint"
        }
        return ConstrainedAnchorPlan(updateNeighbor = false, updateReference = false)
    }
    check(referenceInverseConstraint == View.NO_ID || referenceInverseConstraint == neighborViewId) {
        "constraint-reference-conflict=$referenceInverseConstraint"
    }
    return ConstrainedAnchorPlan(
        updateNeighbor = true,
        updateReference = referenceInverseConstraint == neighborViewId
    )
}

/**
 * 回复脉络功能安装器。协调评论绑定定位、Mapper 与 Compose 种子桥、悬浮面板分页会话；
 * 入口放在宿主已有 `item_include_actions` 操作栏或 Compose 更多菜单中。
 */
internal class CommentTopologyFeatureInstaller(
    private val enabled: Boolean,
    private val points: VersionAdapter.CommentTopologyPoints?,
    private val lowBindPoint: VersionAdapter.HookPoint?,
    private val highBindPoint: VersionAdapter.HookPoint?,
    private val composeMenuPoints: VersionAdapter.CommentComposeMenuPoints? = null,
    private val kmpGrpcWireEngine: VersionAdapter.HookPoint? = null
) : AdaptRetryableInstaller {
    private var composeMenu: ReplyTopologyComposeMenu? = null
    private val seedBridge = ReplyTopologySeedBridge()
    private val anchorBinder = ReplyTopologyAnchorBinder()

    fun clearComposeMenu() {
        composeMenu?.clear()
    }

    fun rememberComposeMenu(anchor: View, rootId: Long, sourceCurrent: () -> Boolean = { true }) {
        val seed = seedBridge.findSeed(rootId)
        composeMenu?.remember(anchor, seed, sourceCurrent)
    }

    override val id: String = ID
    private val pointsRef = AtomicReference(points)
    private val lowBindRef = AtomicReference(lowBindPoint)
    private val highBindRef = AtomicReference(highBindPoint)
    private val composeMenuPointsRef = AtomicReference(composeMenuPoints)
    private val kmpGrpcWireRef = AtomicReference(kmpGrpcWireEngine)

    @Volatile
    var requiresAdaptationRetry: Boolean = false
        private set

    @Volatile
    var requiresComposeAdaptationRetry: Boolean = false
        private set

    override val isAdaptationRetryPending: Boolean
        get() = requiresAdaptationRetry || requiresComposeAdaptationRetry

    private val adaptedRetryConsumed = AtomicBoolean(false)
    private val installedOnce = AtomicBoolean(false)
    private val registeredBindKeys = hashSetOf<String>()
    private val registeredMapperKeys = hashSetOf<String>()

    @Volatile private var lastInstallResult: FeatureInstallResult? = null
    @Volatile private var resolvedHostRef: ReplyTopologyHostAccess? = null
    @Volatile private var coordinatorRef: Lazy<ReplyTopologyCoordinator>? = null
    @Volatile private var composeMenuInstalledComplete: Boolean = false
    @Volatile private var composeSeedsInstalledComplete: Boolean = false
    @Volatile private var lastBaseComplete: Boolean = false
    @Volatile private var lastInstalledCount: Int = 0
    @Volatile private var lastMenuHookCount: Int = 0
    @Volatile private var lastSeedHookCount: Int = 0

    fun updateAdaptedPoints(
        points: VersionAdapter.CommentTopologyPoints?,
        lowBindPoint: VersionAdapter.HookPoint?,
        highBindPoint: VersionAdapter.HookPoint?,
        composeMenuPoints: VersionAdapter.CommentComposeMenuPoints? = composeMenuPointsRef.get(),
        kmpGrpcWireEngine: VersionAdapter.HookPoint? = kmpGrpcWireRef.get()
    ) {
        pointsRef.set(points)
        lowBindRef.set(lowBindPoint)
        highBindRef.set(highBindPoint)
        composeMenuPointsRef.set(composeMenuPoints)
        kmpGrpcWireRef.set(kmpGrpcWireEngine)
        adaptedRetryConsumed.set(true)
    }

    override fun prepareAdaptedRetry(adapted: VersionAdapter.AdaptResult?): Boolean {
        if (!isAdaptationRetryPending) return false
        updateAdaptedPoints(
            points = adapted?.commentTopology,
            lowBindPoint = adapted?.commentLow,
            highBindPoint = adapted?.commentHigh,
            composeMenuPoints = adapted?.commentComposeMenu,
            kmpGrpcWireEngine = adapted?.kmpGrpcWireEngine
        )
        if (requiresAdaptationRetry && adapted?.commentTopology == null) {
            requiresAdaptationRetry = false
        }
        if (requiresComposeAdaptationRetry &&
            adapted?.commentComposeMenu == null &&
            adapted?.kmpGrpcWireEngine == null
        ) {
            requiresComposeAdaptationRetry = false
        }
        return isAdaptationRetryPending
    }

    override fun retryAdapted(
        environment: HookEnvironment,
        coordinator: FeatureInstallCoordinator
    ) {
        if (requiresAdaptationRetry) {
            coordinator.installAll(listOf(this))
        } else if (requiresComposeAdaptationRetry) {
            retryComposeInstallIfNeeded(environment)
        }
    }

    @Suppress("unused")
    private fun isRecyclerViewInstance(view: View?): Boolean =
        anchorBinder.isRecyclerViewInstance(view)

    private data class LegacyInstallResult(
        val hooks: Int,
        val baseComplete: Boolean,
        val failureReason: String?
    )

    private data class ComposeInstallResult(
        val hooks: Int,
        val complete: Boolean
    )

    @Synchronized
    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (installedOnce.get()) {
            return lastInstallResult ?: FeatureInstallResult.Installed(0)
        }
        if (!enabled) {
            requiresAdaptationRetry = false
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != TARGET_PACKAGE) {
            requiresAdaptationRetry = false
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val adapted = pointsRef.get() ?: run {
            requiresAdaptationRetry = !adaptedRetryConsumed.get()
            return missing(environment, "missing-adapter-point")
        }
        requiresAdaptationRetry = false
        val host = ReplyTopologyHostAccess.resolve(environment, adapted)
            ?: return missing(environment, "missing-host-access")

        val coordinator = lazy(LazyThreadSafetyMode.SYNCHRONIZED) { ReplyTopologyCoordinator(host, environment) }
        val legacy = installLegacyListTopology(environment, host, adapted, coordinator)
        val compose = installComposeTopology(environment, host, coordinator)
        val installed = legacy.hooks + compose.hooks

        if (legacy.failureReason != null && compose.hooks == 0) {
            return missing(environment, legacy.failureReason)
        }

        lastBaseComplete = legacy.baseComplete
        lastInstalledCount = installed
        val complete = legacy.baseComplete && compose.complete
        val status = if (complete) "success" else "partial"
        environment.reportStatus(CHANNEL_STATUS, status)
        environment.logInfo(
            "comment_topology_ok",
            "[BIL] 回复脉络已安装，hooks=$installed,status=$status"
        )
        requiresAdaptationRetry = false
        installedOnce.set(true)
        return FeatureInstallResult.Installed(
            installed,
            complete = complete
        ).also { lastInstallResult = it }
    }

    private fun installLegacyListTopology(
        environment: HookEnvironment,
        host: ReplyTopologyHostAccess,
        adapted: VersionAdapter.CommentTopologyPoints,
        coordinator: Lazy<ReplyTopologyCoordinator>
    ): LegacyInstallResult {
        val resolvedLowBindPoint = lowBindRef.get()
        val resolvedHighBindPoint = highBindRef.get()
        val expectedBindOwners = linkedSetOf<String>()
        val bindPoints = buildList {
            resolvedLowBindPoint?.className?.let(expectedBindOwners::add)
            resolvedLowBindPoint?.let(::add)
            CommentNativeBindingCatalog.mainHandlers.forEach { className ->
                val owner = environment.classLoader?.let { KavaMemberLookup.classOrNull(it, className) }
                    ?: return@forEach
                expectedBindOwners += className
                CommentNativeBindingCatalog.bindingMethods(owner).forEach { method ->
                    add(VersionAdapter.HookPoint(owner.name, method.name, method.parameterTypes.map { it.name }))
                }
            }
            resolvedHighBindPoint?.let { cachedPoint ->
                expectedBindOwners += cachedPoint.className
                val owner = environment.classLoader?.let { loader ->
                    KavaMemberLookup.classOrNull(loader, cachedPoint.className)
                }
                if (owner == null) {
                    add(cachedPoint)
                } else {
                    addAll(collectReplyTopologyHighBindPoints(owner, cachedPoint))
                }
            }
        }
            .distinctBy { point ->
                "${point.className}#${point.methodName}(${point.paramClassNames.orEmpty()})"
            }
        if (bindPoints.isEmpty()) {
            return LegacyInstallResult(hooks = 0, baseComplete = false, failureReason = "missing-bind-point")
        }
        if (adapted.mapperMethods.isEmpty()) {
            return LegacyInstallResult(hooks = 0, baseComplete = false, failureReason = "mapper-registration-failed")
        }

        val seedStore = ReplyTopologySeedStore()
        val committed = AtomicBoolean(false)
        var installed = 0
        var bindInstalled = 0
        val installedBindOwners = linkedSetOf<String>()
        val newlyRegisteredBinds = linkedSetOf<String>()

        bindPoints.forEachIndexed { index, point ->
            val bindKey = "${point.className}#${point.methodName}(${point.paramClassNames.orEmpty()})"
            if (bindKey in registeredBindKeys) {
                installed++
                bindInstalled++
                installedBindOwners += point.className
                return@forEachIndexed
            }
            runCatching {
                val captures = ThreadLocal<ArrayDeque<Any?>>()
                environment.registrar.adapted("comment.topology.bind.$index", point) {
                    before {
                        if (!committed.get()) return@before
                        val stack = captures.get() ?: ArrayDeque<Any?>().also(captures::set)
                        stack.addLast(anchorBinder.findCommentItem(host.commentItemClass, instance, args))
                    }
                    after {
                        if (!committed.get()) {
                            captures.remove()
                            return@after
                        }
                        val stack = captures.get()
                        val commentItem = if (stack.isNullOrEmpty()) {
                            anchorBinder.findCommentItem(host.commentItemClass, instance, args)
                        } else {
                            stack.removeLast()
                        }
                        if (stack?.isEmpty() == true) captures.remove()
                        val rootView = anchorBinder.findBindingRoot(instance, args) ?: return@after
                        val commentItemId = commentItem?.let(host::readCommentItemId)
                        val seed = commentItem?.let { item -> seedStore.get(item, commentItemId) }
                        anchorBinder.bindAnchor(
                            boundView = rootView,
                            commentItem = commentItem,
                            commentItemId = commentItemId,
                            seed = seed,
                            seedStore = seedStore,
                            host = host,
                            coordinator = coordinator.value,
                            environment = environment
                        )
                    }
                }
                newlyRegisteredBinds += bindKey
                installed++
                bindInstalled++
                installedBindOwners += point.className
            }.onFailure { throwable ->
                environment.logError(
                    "comment_topology_bind_$index",
                    "[BIL] 回复脉络入口绑定失败(${point.className}#${point.methodName}): $throwable"
                )
            }
        }
        if (bindInstalled == 0) {
            return LegacyInstallResult(hooks = 0, baseComplete = false, failureReason = "bind-registration-failed")
        }

        var mapperInstalled = 0
        val newlyRegisteredMappers = linkedSetOf<String>()
        adapted.mapperMethods.forEachIndexed { index, mapperPoint ->
            val mapperKey = "${mapperPoint.className}#${mapperPoint.methodName}(${mapperPoint.paramClassNames.orEmpty()})"
            if (mapperKey in registeredMapperKeys) {
                installed++
                mapperInstalled++
                return@forEachIndexed
            }
            runCatching {
                environment.registrar.adapted("comment.topology.mapper.$index", mapperPoint) {
                    after {
                        if (!committed.get()) return@after
                        val commentItem = result ?: return@after
                        if (!host.commentItemClass.isInstance(commentItem)) return@after
                        val replyInfo = args.firstOrNull(host.replyInfoClass::isInstance) ?: return@after
                        val seed = host.snapshotRoot(replyInfo) ?: return@after
                        seedStore.put(commentItem, seed)
                    }
                }
                newlyRegisteredMappers += mapperKey
                installed++
                mapperInstalled++
            }.onFailure { throwable ->
                environment.logError(
                    "comment_topology_mapper_$index",
                    "[BIL] 回复脉络身份桥注册失败(${mapperPoint.className}#${mapperPoint.methodName}): $throwable"
                )
            }
        }
        if (mapperInstalled != adapted.mapperMethods.size || mapperInstalled == 0) {
            return LegacyInstallResult(hooks = 0, baseComplete = false, failureReason = "mapper-registration-failed")
        }
        committed.set(true)
        registeredBindKeys += newlyRegisteredBinds
        registeredMapperKeys += newlyRegisteredMappers
        val baseComplete = bindInstalled == bindPoints.size && installedBindOwners.containsAll(expectedBindOwners)
        return LegacyInstallResult(hooks = installed, baseComplete = baseComplete, failureReason = null)
    }

    private fun installComposeTopology(
        environment: HookEnvironment,
        host: ReplyTopologyHostAccess,
        coordinator: Lazy<ReplyTopologyCoordinator>
    ): ComposeInstallResult {
        var composeComplete = true
        var installed = 0
        val loader = environment.classLoader
        if (loader != null && KavaMemberLookup.hasClass(loader, "kntr.common.comment.card.model.comment.CommentModel")) {
            resolvedHostRef = host
            coordinatorRef = coordinator
            val menu = ReplyTopologyComposeMenu(composeMenuPointsRef.get()) { anchor, seed ->
                commentOwnerActivity(anchor.context)?.let { coordinator.value.open(it, seed, anchor) }
            }
            val menuResult = menu.install(environment)
            if (menuResult is FeatureInstallResult.Installed) composeMenu = menu
            val capture = seedBridge.installComposeSeeds(environment, host, kmpGrpcWireRef.get())
            lastMenuHookCount = (menuResult as? FeatureInstallResult.Installed)?.hookCount ?: 0
            lastSeedHookCount = (capture as? FeatureInstallResult.Installed)?.hookCount ?: 0
            installed += lastMenuHookCount
            installed += lastSeedHookCount
            composeMenuInstalledComplete = menuResult is FeatureInstallResult.Installed && menuResult.complete
            composeSeedsInstalledComplete = capture is FeatureInstallResult.Installed && capture.complete
            composeComplete = composeMenuInstalledComplete && composeSeedsInstalledComplete
            val composeWireRetryNeeded = seedBridge.isWireRetryNeeded(loader)
            if (!composeComplete || composeWireRetryNeeded) {
                requiresComposeAdaptationRetry = !adaptedRetryConsumed.get()
                if (!composeComplete) {
                    environment.logError("comment_topology_compose_missing", "[BIL] 回复脉络 Compose 菜单／数据桥未完整接入")
                }
            } else {
                requiresComposeAdaptationRetry = false
            }
        } else {
            requiresComposeAdaptationRetry = false
        }
        return ComposeInstallResult(hooks = installed, complete = composeComplete)
    }

    @Synchronized
    fun retryComposeInstallIfNeeded(environment: HookEnvironment) {
        if (!installedOnce.get() || !requiresComposeAdaptationRetry || !enabled || environment.processName != TARGET_PACKAGE) {
            return
        }
        requiresComposeAdaptationRetry = false
        val loader = environment.classLoader ?: return
        if (!KavaMemberLookup.hasClass(loader, "kntr.common.comment.card.model.comment.CommentModel")) return
        val host = resolvedHostRef ?: return
        val coordinator = coordinatorRef ?: return
        if (!composeMenuInstalledComplete) {
            val menu = composeMenu?.apply { updateCachedPoints(composeMenuPointsRef.get()) }
                ?: ReplyTopologyComposeMenu(composeMenuPointsRef.get()) { anchor, seed ->
                    commentOwnerActivity(anchor.context)?.let { coordinator.value.open(it, seed, anchor) }
                }
            val menuResult = menu.install(environment)
            if (menuResult is FeatureInstallResult.Installed) {
                composeMenu = menu
                composeMenuInstalledComplete = menuResult.complete
                val delta = (menuResult.hookCount - lastMenuHookCount).coerceAtLeast(0)
                lastInstalledCount += delta
                lastMenuHookCount = maxOf(lastMenuHookCount, menuResult.hookCount)
            }
        }
        val engine = kmpGrpcWireRef.get()
        if (seedBridge.allWireBound()) {
            composeSeedsInstalledComplete = true
            seedBridge.markAllWireBound()
        } else if (seedBridge.shouldRetryWire(engine)) {
            val capture = seedBridge.installComposeSeeds(
                environment = environment,
                host = host,
                kmpGrpcWireEngine = engine,
                wireOnly = seedBridge.channelKind.isNotEmpty()
            )
            if (capture is FeatureInstallResult.Installed) {
                composeSeedsInstalledComplete = composeSeedsInstalledComplete || capture.complete
                val delta = (capture.hookCount - lastSeedHookCount).coerceAtLeast(0)
                lastInstalledCount += delta
                lastSeedHookCount = maxOf(lastSeedHookCount, capture.hookCount)
            }
        }
        val complete = lastBaseComplete && composeMenuInstalledComplete && composeSeedsInstalledComplete
        environment.reportStatus(CHANNEL_STATUS, if (complete) "success" else "partial")
        lastInstallResult = FeatureInstallResult.Installed(lastInstalledCount, complete = complete)
    }

    private fun missing(
        environment: HookEnvironment,
        reason: String
    ): FeatureInstallResult.Skipped {
        environment.reportStatus(CHANNEL_STATUS, reason)
        environment.logError("comment_topology_missing", "[BIL] 回复脉络未安装: $reason")
        return FeatureInstallResult.Skipped(reason)
    }

    companion object {
        const val ID = "comment_topology"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "comment_topology_status"
    }
}
