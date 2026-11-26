package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.freecopy

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.PopupWindow
import android.widget.TextView
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptPlan
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.AdaptRetryableInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentBindProbe
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentComposeCopyBridge
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentFamilyCoverage
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentLegacyCopyBridge
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentNativeBindingCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentNativeSupplementBridge
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstallCoordinator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstallResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureRuntimeStage
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.TitleFreeCopyPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.forCapabilityRuntime
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.reportCapability
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.reportRuntimeEvidence
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ReflectAccess
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.StartupTrace
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isSubclassOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology.ReplyTopologyClipboardWrite
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 自由复制（评论 / 视频简介 / 视频标题）与详情页自动亮暗气泡主题的独立功能安装器。
 *
 * 所有 Hook 统一经 [HookEnvironment.registrar]（及子能力 `forCapabilityRuntime` 的
 * [FeatureScopedRegistrar]）注册，确保异常由 HookFaultBridge 精确归因到 `free_copy*` 能力，
 * 并通过 [AdaptRetryableInstaller] 接入后台版本适配完成后的统一增量重试。
 */
internal class FreeCopyFeatureInstaller(
    private val initialCommentEnabled: Boolean,
    private val initialDescriptionEnabled: Boolean,
    private val initialTitleEnabled: Boolean,
    private val initialLightMode: Boolean,
    private val initialAutoLight: Boolean,
    private val commentTopologyEnabled: Boolean,
    private val adaptPlan: AdaptPlan,
    private val hostVersionCode: () -> Int,
    private val initialAdaptResult: () -> VersionAdapter.AdaptResult?,
    private val onComposeMore: (View, Long, () -> Boolean) -> Unit,
    private val onComposeResetMore: () -> Unit,
    private val lowBindPointProvider: () -> VersionAdapter.HookPoint? = { initialAdaptResult()?.commentLow },
    private val highBindPointProvider: () -> VersionAdapter.HookPoint? = { initialAdaptResult()?.commentHigh }
) : AdaptRetryableInstaller {

    override val id: String = ID

    override val capabilityIds: List<String>
        get() = buildList {
            val commentOn = if (startupConfigured.get()) {
                FreeCopyRuntimeConfig.runtimeCommentFreeCopyEnabled
            } else {
                initialCommentEnabled
            }
            val descOn = if (startupConfigured.get()) {
                FreeCopyRuntimeConfig.runtimeDescriptionFreeCopyEnabled
            } else {
                initialDescriptionEnabled
            }
            val titleOn = if (startupConfigured.get()) {
                FreeCopyRuntimeConfig.runtimeTitleFreeCopyEnabled
            } else {
                initialTitleEnabled
            }
            if (commentOn) add(CAPABILITY_COMMENT)
            if (descOn) add(CAPABILITY_DESCRIPTION)
            if (titleOn) add(CAPABILITY_TITLE)
        }

    private val startupConfigured = AtomicBoolean(false)
    private val commentFreeCopyHooksInstalled = AtomicBoolean(false)
    private val commentFreeCopyBindingVerified = AtomicBoolean(false)
    private val descriptionFreeCopyHooksInstalled = AtomicBoolean(false)
    private val autoLightHookInstalled = AtomicBoolean(false)
    private val commentFamilies = CommentFamilyCoverage()
    private val lowPointRef = AtomicReference<VersionAdapter.HookPoint?>(null)
    private val highPointRef = AtomicReference<VersionAdapter.HookPoint?>(null)
    private val installedLowHookKeys = HashSet<String>()
    private val installedHighHookKeys = HashSet<String>()

    @Volatile
    private var adaptRetryConsumed: Boolean = false

    @Volatile
    private var pendingNewAdaptedPoint: Boolean = false

    override val isAdaptationRetryPending: Boolean
        get() {
            if (adaptRetryConsumed && !pendingNewAdaptedPoint) return false
            val commentOn = if (startupConfigured.get()) {
                FreeCopyRuntimeConfig.runtimeCommentFreeCopyEnabled
            } else {
                initialCommentEnabled
            }
            val descLikeOn = if (startupConfigured.get()) {
                FreeCopyRuntimeConfig.descLikeFreeCopyEnabled
            } else {
                initialDescriptionEnabled || initialTitleEnabled
            }
            return pendingNewAdaptedPoint ||
                (commentOn && (!commentFreeCopyHooksInstalled.get() || !commentFamilies.isComplete())) ||
                ((commentOn || descLikeOn) && !descriptionFreeCopyHooksInstalled.get())
        }

    override fun prepareAdaptedRetry(adapted: VersionAdapter.AdaptResult?): Boolean {
        if (adaptRetryConsumed) return false
        adaptRetryConsumed = true
        var foundNew = false
        adapted?.commentLow?.let { nextLow ->
            val key = "${nextLow.className}#${nextLow.methodName}"
            if (key !in installedLowHookKeys) foundNew = true
            lowPointRef.set(nextLow)
        }
        adapted?.commentHigh?.let { nextHigh ->
            val paramsSig = nextHigh.paramClassNames?.joinToString(",").orEmpty()
            val key = "${nextHigh.className}#${nextHigh.methodName}$paramsSig"
            if (paramsSig.isNotEmpty() && key !in installedHighHookKeys) foundNew = true
            highPointRef.set(nextHigh)
        }
        pendingNewAdaptedPoint = foundNew
        return isAdaptationRetryPending
    }

    override fun retryAdapted(
        environment: HookEnvironment,
        coordinator: FeatureInstallCoordinator
    ) {
        pendingNewAdaptedPoint = false
        coordinator.installAll(listOf(this))
    }

    fun updateLiveConfig(
        commentEnabled: Boolean,
        descriptionEnabled: Boolean,
        lightMode: Boolean
    ) {
        FreeCopyRuntimeConfig.updateLiveConfig(
            commentEnabled = commentEnabled,
            descriptionEnabled = descriptionEnabled,
            lightMode = lightMode
        )
    }

    @Synchronized
    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (startupConfigured.compareAndSet(false, true)) {
            FreeCopyRuntimeConfig.configureStartup(
                commentEnabled = initialCommentEnabled,
                descriptionEnabled = initialDescriptionEnabled,
                titleEnabled = initialTitleEnabled,
                lightMode = initialLightMode,
                autoLight = initialAutoLight
            )
            lowPointRef.set(lowBindPointProvider())
            highPointRef.set(highBindPointProvider())
        }
        FreeCopyRuntimeConfig.configureSinks(
            logInfo = environment.logInfo,
            logError = environment.logError,
            runtimeEvidence = { capabilityId, stage ->
                environment.reportRuntimeEvidence(capabilityId, stage)
            }
        )
        if (environment.processName != FREE_COPY_TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val loader = environment.classLoader ?: return FeatureInstallResult.Skipped("missing-class-loader")
        if (FreeCopyBindScheduler.hostRecyclerViewClass == null) {
            FreeCopyBindScheduler.hostRecyclerViewClass =
                KavaMemberLookup.classOrNull(loader, "androidx.recyclerview.widget.RecyclerView")
        }

        val commentEnv = environment.forCapabilityRuntime(CAPABILITY_COMMENT)
        val descEnv = environment.forCapabilityRuntime(
            if (FreeCopyRuntimeConfig.runtimeDescriptionFreeCopyEnabled) CAPABILITY_DESCRIPTION
            else CAPABILITY_TITLE
        )

        val composeCommentCopy = CommentComposeCopyBridge(
            enabled = { FreeCopyRuntimeConfig.runtimeCommentFreeCopyEnabled },
            open = FreeCopyPopupBridge::showCommentModelBubble,
            observe = { FreeCopyRuntimeConfig.runtimeCommentFreeCopyEnabled || commentTopologyEnabled },
            onMore = onComposeMore,
            resetMore = onComposeResetMore
        )
        val nativeSupplement = CommentNativeSupplementBridge(
            enabled = { FreeCopyRuntimeConfig.runtimeCommentFreeCopyEnabled },
            bind = { view, raw, source ->
                FreeCopyBindScheduler.scheduleCommentBind(
                    view, raw, false, rawTrusted = true, commentItem = source
                )
            },
            rawComment = FreeCopyTextExtractor::extractRawCommentTextV2
        )
        FreeCopyTextExtractor.additionalNativeCopy = nativeSupplement
        val legacyCommentCopy by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            StartupTrace.measure("legacy.comment_prepare") { CommentLegacyCopyBridge(loader) }
        }

        if (FreeCopyRuntimeConfig.runtimeCommentFreeCopyEnabled || commentTopologyEnabled) {
            StartupTrace.measure("legacy.comment") {
                installCommentFreeCopyHooks(
                    commentEnv = commentEnv,
                    loader = loader,
                    composeCommentCopy = composeCommentCopy,
                    nativeSupplement = nativeSupplement,
                    legacyCommentCopy = legacyCommentCopy
                )
            }
        }
        installAutoLightHook(descEnv, loader)
        if (FreeCopyRuntimeConfig.runtimeCommentFreeCopyEnabled ||
            FreeCopyRuntimeConfig.descLikeFreeCopyEnabled
        ) {
            StartupTrace.measure("legacy.description") {
                installDescriptionFreeCopyHooks(
                    sharedEnv = environment,
                    descEnv = descEnv,
                    loader = loader,
                    legacyCommentCopy = legacyCommentCopy,
                    nativeSupplement = nativeSupplement
                )
            }
        }

        val installedCount =
            (if (commentFreeCopyHooksInstalled.get()) 1 else 0) +
                (if (descriptionFreeCopyHooksInstalled.get()) 1 else 0)
        val result = when {
            !FreeCopyRuntimeConfig.runtimeCommentFreeCopyEnabled &&
                !FreeCopyRuntimeConfig.descLikeFreeCopyEnabled ->
                FeatureInstallResult.Skipped("disabled")
            installedCount > 0 -> FeatureInstallResult.Installed(
                hookCount = installedCount,
                complete = (!FreeCopyRuntimeConfig.runtimeCommentFreeCopyEnabled || commentFamilies.isComplete()) &&
                    (!FreeCopyRuntimeConfig.descLikeFreeCopyEnabled || descriptionFreeCopyHooksInstalled.get())
            )
            else -> FeatureInstallResult.Skipped("registration-failed")
        }
        publishFreeCopyCapabilities(environment)
        return result
    }

    private fun installCommentFreeCopyHooks(
        commentEnv: HookEnvironment,
        loader: ClassLoader,
        composeCommentCopy: CommentComposeCopyBridge,
        nativeSupplement: CommentNativeSupplementBridge,
        legacyCommentCopy: CommentLegacyCopyBridge
    ) {
        if (!FreeCopyRuntimeConfig.runtimeCommentFreeCopyEnabled) {
            runCatching { composeCommentCopy.install(commentEnv) }.onFailure {
                commentEnv.logError("comment_topology_compose_observer", "[BIL] Compose 评论菜单观察桥安装失败")
            }
            return
        }
        val isInitialAttempt = commentFreeCopyHooksInstalled.compareAndSet(false, true)
        if (isInitialAttempt) {
            FreeCopyBindScheduler.commentBindProbe.enabled = runCatching {
                Log.isLoggable(CommentBindProbe.LOG_TAG, Log.DEBUG)
            }.getOrDefault(false)
        }
        try {
            val currentLow = lowPointRef.get() ?: lowBindPointProvider()?.also(lowPointRef::set)
            val currentHigh = highPointRef.get() ?: highBindPointProvider()?.also(highPointRef::set)
            val fallbackQuick by lazy(LazyThreadSafetyMode.NONE) {
                initialAdaptResult() ?: VersionAdapter.quickLocate(
                    loader, adaptPlan, hostVersionCode = hostVersionCode()
                )
            }
            val lowPoint = currentLow ?: fallbackQuick?.commentLow
            val highPoint = currentHigh ?: fallbackQuick?.commentHigh

            if (isInitialAttempt) {
                runCatching {
                    val recyclerViewClass = FreeCopyBindScheduler.hostRecyclerViewClass
                        ?: throw ClassNotFoundException("host RecyclerView")
                    commentEnv.registrar.exact(
                        "free-copy:comment:rv-scroll-state",
                        recyclerViewClass,
                        "onScrollStateChanged",
                        classOf<Int>()
                    ) {
                        after {
                            if (!FreeCopyRuntimeConfig.runtimeCommentFreeCopyEnabled) return@after
                            val st = argOrNull(0) as? Int ?: return@after
                            val recyclerView = instance as? View ?: return@after
                            FreeCopyBindScheduler.updateRecyclerViewScrolling(
                                recyclerView,
                                st != androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE
                            )
                            if (!FreeCopyBindScheduler.isAnyRecyclerViewScrolling()) {
                                FreeCopyBindScheduler.rvIdleSinceMs = SystemClock.uptimeMillis()
                                FreeCopyBindScheduler.scheduleCommentBindRetry(120L)
                            }
                        }
                    }
                    commentEnv.registrar.exact(
                        "free-copy:comment:rv-detach",
                        recyclerViewClass,
                        "onDetachedFromWindow"
                    ) {
                        after {
                            if (!FreeCopyRuntimeConfig.runtimeCommentFreeCopyEnabled) return@after
                            val recyclerView = instance as? View ?: return@after
                            if (FreeCopyBindScheduler.updateRecyclerViewScrolling(recyclerView, false)) {
                                FreeCopyBindScheduler.rvIdleSinceMs = SystemClock.uptimeMillis()
                                FreeCopyBindScheduler.scheduleCommentBindRetry(120L)
                            }
                        }
                    }
                }
                if (classExists("com.bilibili.app.comm.comment2.model.BiliComment", loader) &&
                    legacyCommentCopy.isApplicable()
                ) {
                    commentFamilies.record("comment2", legacyCommentCopy.isReady())
                }
            }

            var freeCopyOk = commentFreeCopyBindingVerified.get()
            val lowHolderCls = lowPoint?.className ?: "com.bilibili.app.comment3.ui.holder.t0"
            val lowHolderMethod = lowPoint?.methodName ?: "o0"
            val lowKey = "$lowHolderCls#$lowHolderMethod"
            if (lowKey !in installedLowHookKeys && classExists(lowHolderCls, loader)) {
                if (lowPoint != null) commentFamilies.record("low", false)
                runCatching {
                    commentEnv.registrar.first(
                        "free-copy:comment-low:$lowKey",
                        lowHolderCls,
                        lowHolderMethod
                    ) {
                        after {
                            if (!FreeCopyRuntimeConfig.runtimeCommentFreeCopyEnabled) return@after
                            val holder = instance ?: return@after
                            val itemView = runCatching {
                                FreeCopyBindScheduler.holderItemView(holder)
                            }.getOrNull() ?: return@after
                            val commentItem = argOrNull(0)
                            val rawText = FreeCopyTextExtractor.extractRawCommentText(commentItem)
                            FreeCopyBindScheduler.scheduleCommentBind(
                                itemView,
                                rawText,
                                true,
                                rawTrusted = true,
                                commentItem = commentItem
                            )
                        }
                    }
                }.onSuccess {
                    installedLowHookKeys += lowKey
                    freeCopyOk = true
                    commentFreeCopyBindingVerified.set(true)
                    commentFamilies.record("low", true)
                }.onFailure { t ->
                    commentEnv.logInfo(
                        "free_copy_low_skip",
                        "[BIL] 低版本评论入口不可用，继续尝试高版本 Handler: $t"
                    )
                }
            }

            val highClassNames = LinkedHashSet<String>()
            highPoint?.className?.let(highClassNames::add)
            COMMENT_HIGH_HANDLER_CLASSES.forEach(highClassNames::add)
            val highHandlerExists = highClassNames.any { classExists(it, loader) }
            if (highHandlerExists) {
                val bindBlock: ModernMemberHookCreator.() -> Unit = {
                    before {
                        if (!FreeCopyRuntimeConfig.runtimeCommentFreeCopyEnabled) return@before
                        val handler = thisObject ?: return@before
                        val boundItem = FreeCopyBindScheduler.extractCommentItemForBinding(this, handler) ?: return@before
                        setObjectExtra(
                            COMMENT_BIND_SNAPSHOT_KEY,
                            CapturedCommentBinding(
                                boundItem.value,
                                FreeCopyTextExtractor.extractRawCommentTextV2(boundItem.value)
                            )
                        )
                    }
                    after {
                        if (!FreeCopyRuntimeConfig.runtimeCommentFreeCopyEnabled) return@after
                        val handler = thisObject ?: return@after
                        val captured = getObjectExtra(COMMENT_BIND_SNAPSHOT_KEY) as? CapturedCommentBinding
                        val boundItem = if (captured == null) {
                            FreeCopyBindScheduler.extractCommentItemForBinding(this, handler) ?: return@after
                        } else {
                            BoundCommentItem(captured.commentItem, true)
                        }
                        val rawText = captured?.rawText
                            ?: FreeCopyTextExtractor.extractRawCommentTextV2(boundItem.value)
                        val itemView: View = run {
                            val fromArgs = FreeCopyBindScheduler.extractBindingRootFromArgs(this)
                            fromArgs ?: run {
                                val handlerClassNow = handler.javaClass
                                val cachedField = FreeCopyBindScheduler.cHandlerViewFieldByClass[handlerClassNow]
                                if (cachedField != null) {
                                    runCatching { cachedField.get(handler) as? View }.getOrNull()
                                } else {
                                    var found: View? = null
                                    for (fld in KavaMemberLookup.declaredFields(handlerClassNow, makeAccessible = true)) {
                                        val v = runCatching { fld.get(handler) }.getOrNull()
                                        if (v is View) {
                                            found = v
                                            FreeCopyBindScheduler.cHandlerViewFieldByClass[handlerClassNow] = fld
                                            break
                                        }
                                    }
                                    found
                                }
                            }
                        } ?: return@after
                        FreeCopyBindScheduler.scheduleCommentBind(
                            itemView,
                            rawText,
                            false,
                            rawTrusted = captured != null || boundItem.fromCurrentArguments,
                            commentItem = boundItem.value
                        )
                    }
                }
                for (highCls in highClassNames) {
                    if (!classExists(highCls, loader)) continue
                    if (isInitialAttempt) commentFamilies.record(highCls, false)
                    val highMethod = highPoint?.methodName ?: METHOD_COMMENT_BIND_V2
                    runCatching {
                        val handlerClass = KavaMemberLookup.classOrNull(loader, highCls)
                            ?: throw ClassNotFoundException(highCls)
                        val registered = HashSet<String>()
                        val useCachedSignature =
                            highCls == highPoint?.className ||
                                (highPoint == null && highCls == CLASS_COMMENT_HANDLER_V2)
                        if (useCachedSignature) runCatching {
                            val cachedNames = highPoint?.paramClassNames ?: listOf("Pj.J", "boolean")
                            val cacheParams = commentEnv.hookPoints.resolveParameterClasses(cachedNames)
                                ?: throw ClassNotFoundException(cachedNames.joinToString(","))
                            val sig = "$highMethod${cacheParams.joinToString(",") { it.name }}"
                            val methodKey = "$highCls#$sig"
                            if (methodKey !in installedHighHookKeys) {
                                commentEnv.registrar.exact(
                                    "free-copy:comment-high:$methodKey",
                                    handlerClass,
                                    highMethod,
                                    *cacheParams,
                                    block = bindBlock
                                )
                                installedHighHookKeys += methodKey
                                commentFreeCopyBindingVerified.set(true)
                            }
                            registered.add(sig)
                        }.onFailure { t ->
                            commentEnv.logError("free_copy_v2_err:$highCls", "[BIL] 9.x 评论 hook 注册失败(缓存签名): $t")
                        }
                        for (m in KavaMemberLookup.declaredMethods(handlerClass)) {
                            if (m.parameterCount < 1 || m.parameterCount > 5) continue
                            if (m.isStatic) continue
                            val sig = "${m.name}${m.parameterTypes.joinToString(",") { it.name }}"
                            if (registered.contains(sig)) continue
                            var isBinding = false
                            for (pt in m.parameterTypes) {
                                if (pt.isPrimitive || pt.isArray || pt.isInterface) continue
                                val hasViewField = KavaMemberLookup.declaredFields(pt) {
                                    it.type isSubclassOf classOf<View>()
                                }.isNotEmpty()
                                if (hasViewField) {
                                    isBinding = true
                                    break
                                }
                            }
                            if (!isBinding) continue
                            val methodKey = "$highCls#$sig"
                            if (methodKey in installedHighHookKeys) {
                                registered.add(sig)
                                continue
                            }
                            runCatching {
                                commentEnv.registrar.exact(
                                    "free-copy:comment-high:${m.toGenericString()}",
                                    m.declaringClass,
                                    m.name,
                                    *m.parameterTypes,
                                    block = bindBlock
                                )
                                installedHighHookKeys += methodKey
                                registered.add(sig)
                                commentFreeCopyBindingVerified.set(true)
                            }
                        }
                        if (registered.isEmpty()) {
                            commentEnv.logError(
                                "free_copy_v2_err:$highCls",
                                "[BIL] 9.x 评论 handler 无可用绑定方法: $highCls"
                            )
                        } else {
                            val required = CommentNativeBindingCatalog.bindingMethods(handlerClass).map {
                                "${it.name}${it.parameterTypes.joinToString(",") { type -> type.name }}"
                            }.toSet()
                            commentFamilies.record(
                                highCls,
                                FeatureInstallResult.Installed(
                                    registered.size,
                                    complete = required.isNotEmpty() && registered.containsAll(required)
                                )
                            )
                            commentEnv.logInfo(
                                "free_copy_ok_v2:$highCls",
                                "[BIL] 自由复制 hook 已注册（9.x $highCls ${registered.joinToString(", ") { it }}）"
                            )
                        }
                    }.onFailure { t ->
                        if (classExists(highCls, loader)) {
                            commentEnv.logError("free_copy_v2_err:$highCls", "[BIL] 9.x 评论 hook 注册失败 $highCls: $t")
                        }
                    }
                }
                freeCopyOk = freeCopyOk || commentFamilies.installedCount() > 0
            }

            if (isInitialAttempt) {
                val compose = runCatching { composeCommentCopy.install(commentEnv) }.getOrElse {
                    commentEnv.logError("free_copy_compose_registration", "[BIL] Compose 评论复制安装失败，原生管线继续工作")
                    FeatureInstallResult.Skipped("registration-failed")
                }
                if (compose !is FeatureInstallResult.Skipped || compose.reason != "not-applicable-host") {
                    commentFamilies.record("compose", compose)
                }
                if (compose is FeatureInstallResult.Installed) {
                    freeCopyOk = true
                    commentFreeCopyBindingVerified.set(true)
                }
                commentEnv.reportStatus("free_copy_comment_layers", commentFamilies.describe())
                runCatching { nativeSupplement.install(commentEnv) }.onSuccess { results ->
                    results.forEach { (family, result) ->
                        commentFamilies.record(family, result)
                    }
                }.onFailure {
                    commentFamilies.record("native-supplement", false)
                    commentEnv.logError("free_copy_native_supplement", "[BIL] 图片／推送评论复制安装失败，已有管线继续工作")
                }
                commentEnv.reportStatus("free_copy_native_layers", commentFamilies.describe())
            }
            if (freeCopyOk) {
                commentEnv.logInfo("free_copy_ok", "[BIL] 自由复制 hook 已注册")
            } else {
                commentFreeCopyHooksInstalled.set(false)
                commentEnv.logError("free_copy_hook_err", "[BIL] 自由复制 hook 失败：低版本 t0 和高版本 handler 类都不存在")
            }
        } catch (t: Throwable) {
            commentFreeCopyHooksInstalled.set(false)
            commentEnv.logError("free_copy_hook_err", "[BIL] 自由复制 hook 注册异常: $t")
        }
    }

    private fun installAutoLightHook(descEnv: HookEnvironment, loader: ClassLoader) {
        if (!FreeCopyRuntimeConfig.freeCopyAutoLight || !classExists(FREE_COPY_DETAIL_ACTIVITY_CLASS, loader)) return
        if (!autoLightHookInstalled.compareAndSet(false, true)) return
        runCatching {
            descEnv.registrar.first(
                "free-copy:auto-light:detail-on-create",
                FREE_COPY_DETAIL_ACTIVITY_CLASS,
                "onCreate"
            ) {
                before {
                    val ctx = instance as? Context ?: return@before
                    val night = runCatching {
                        val c = KavaMemberLookup.classOrNull(
                            loader, "com.bilibili.lib.ui.util.NightTheme"
                        ) ?: return@runCatching null
                        ReflectAccess.callStaticMethod(c, "isNightTheme", ctx) as? Boolean
                    }.getOrNull()
                    if (night != null) {
                        FreeCopyRuntimeConfig.freeCopyLightCache = !night
                    } else {
                        FreeCopyRuntimeConfig.freeCopyLightCache = null
                        descEnv.logError("auto_light_theme_err", "[BIL] NightTheme 判定失败，气泡跟随回退手动开关")
                    }
                }
            }
        }.onFailure { t ->
            autoLightHookInstalled.set(false)
            descEnv.logError("auto_light_hook_err", "[BIL] 气泡亮暗色跟随详情页钩子注册失败: $t")
        }
    }

    private fun installDescriptionFreeCopyHooks(
        sharedEnv: HookEnvironment,
        descEnv: HookEnvironment,
        loader: ClassLoader,
        legacyCommentCopy: CommentLegacyCopyBridge,
        nativeSupplement: CommentNativeSupplementBridge
    ) {
        if (!descriptionFreeCopyHooksInstalled.compareAndSet(false, true)) return
        var descHookOk = false
        runCatching {
            val descTextHook: ModernMemberHookCreator.() -> Unit = {
                after {
                    if (!FreeCopyRuntimeConfig.descLikeFreeCopyEnabled) return@after
                    val v = instance as? View ?: return@after
                    FreeCopyBindScheduler.onDetailTextSet(v)
                }
            }
            var narrowed = false
            for (cn in TitleFreeCopyPolicy.WIDGET_CLASS_NAMES) {
                runCatching {
                    val owner = KavaMemberLookup.classOrNull(loader, cn) ?: return@runCatching
                    descEnv.registrar.exact(
                        "free-copy:description:set-text:$cn",
                        owner,
                        "setText",
                        classOf<CharSequence>(),
                        classOf<TextView.BufferType>(),
                        block = descTextHook
                    )
                    narrowed = true
                }
            }
            if (!narrowed) {
                descEnv.registrar.exact(
                    "free-copy:description:set-text:platform",
                    classOf<TextView>(),
                    "setText",
                    classOf<CharSequence>(),
                    classOf<TextView.BufferType>(),
                    block = descTextHook
                )
            }
            sharedEnv.registrar.exact(
                "free-copy:shared:set-on-long-click",
                classOf<View>(),
                "setOnLongClickListener",
                classOf<View.OnLongClickListener>()
            ) {
                after {
                    if (!FreeCopyRuntimeConfig.descLikeFreeCopyEnabled &&
                        !FreeCopyRuntimeConfig.runtimeCommentFreeCopyEnabled
                    ) return@after
                    if (FreeCopyBindScheduler.descStealInProgress) return@after
                    val v = instance as? View ?: return@after
                    if (FreeCopyRuntimeConfig.runtimeTitleFreeCopyEnabled &&
                        FreeCopyTextExtractor.isTitleView(v)
                    ) {
                        FreeCopyBindScheduler.applyTitleFreeCopyListener(v)
                        return@after
                    }
                    if (FreeCopyTextExtractor.descViewId == View.NO_ID) {
                        FreeCopyTextExtractor.resolveDescViewId(v.context)
                    }
                    if (FreeCopyRuntimeConfig.runtimeDescriptionFreeCopyEnabled &&
                        FreeCopyTextExtractor.descViewId != View.NO_ID &&
                        v.id == FreeCopyTextExtractor.descViewId
                    ) {
                        FreeCopyBindScheduler.descStealInProgress = true
                        try {
                            FreeCopyBindScheduler.applyFreeCopyListener(v, null, false)
                        } finally {
                            FreeCopyBindScheduler.descStealInProgress = false
                        }
                        return@after
                    }
                    if (!FreeCopyRuntimeConfig.runtimeCommentFreeCopyEnabled) return@after
                    if (FreeCopyBindScheduler.commentStealInProgress) return@after
                    if (FreeCopyTextExtractor.isCommentBodyTextView(v) || nativeSupplement.isImageBody(v)) {
                        val legacy = if (
                            classExists("com.bilibili.app.comm.comment2.model.BiliComment", loader) &&
                            legacyCommentCopy.isBody(v)
                        ) {
                            legacyCommentCopy.capture(argOrNull(0))
                        } else null
                        FreeCopyBindScheduler.registerCommentRoot(
                            v, legacy?.first, false, rawTrusted = false, commentItem = legacy?.second
                        )
                        FreeCopyBindScheduler.commentStealInProgress = true
                        try {
                            FreeCopyBindScheduler.setLongClickListenerNoHook(
                                v, FreeCopyGestureState.sharedFreeCopyListener
                            )
                        } finally {
                            FreeCopyBindScheduler.commentStealInProgress = false
                        }
                        return@after
                    }
                    var cur: View? = v
                    var root: View? = null
                    synchronized(FreeCopyBindScheduler.commentRootLock) {
                        if (FreeCopyBindScheduler.commentRootRefs.isEmpty()) return@after
                        while (cur != null) {
                            if (FreeCopyBindScheduler.commentRootRefs.containsKey(cur)) {
                                root = cur
                                break
                            }
                            cur = cur.parent as? View
                        }
                    }
                    if (root != null) {
                        val state = synchronized(FreeCopyBindScheduler.commentRootLock) {
                            FreeCopyBindScheduler.commentRootRefs[root]?.get()
                        } ?: CommentRootState(null, true, false, null, 0L)
                        FreeCopyBindScheduler.commentStealInProgress = true
                        try {
                            if (!FreeCopyBindScheduler.applyFreeCopyListener(root, state.rawText, state.checkReply)) {
                                FreeCopyBindScheduler.unregisterCommentRoot(root)
                            }
                        } finally {
                            FreeCopyBindScheduler.commentStealInProgress = false
                        }
                    }
                }
            }
            runCatching {
                sharedEnv.registrar.exact(
                    "free-copy:shared:dispatch-touch",
                    classOf<View>(),
                    "dispatchTouchEvent",
                    classOf<MotionEvent>()
                ) {
                    before {
                        if (!FreeCopyRuntimeConfig.descLikeFreeCopyEnabled &&
                            !FreeCopyRuntimeConfig.runtimeCommentFreeCopyEnabled
                        ) return@before
                        val v = instance as? View ?: return@before
                        val ev = argOrNull(0) as? MotionEvent ?: return@before
                        if (FreeCopyGestureState.handleDispatchTouchEvent(v, ev)) {
                            this.result = true
                        }
                    }
                }
            }
            runCatching {
                val headlineService = KavaMemberLookup.classOrNull(
                    loader,
                    "com.bilibili.ship.theseus.ugc.intro.ugcheadline.UgcHeadlineService"
                )
                val innerOwners = (headlineService?.declaredClasses.orEmpty().map { it.name } + listOf(
                    "com.bilibili.ship.theseus.ugc.intro.ugcheadline.UgcHeadlineService\$b",
                    "com.bilibili.ship.theseus.ugc.intro.ugcheadline.UgcHeadlineService\$c"
                )).distinct().mapNotNull { KavaMemberLookup.classOrNull(loader, it) }
                val clipboardHooks = innerOwners.flatMap { owner ->
                    KavaMemberLookup.declaredMethods(owner, makeAccessible = true) { method ->
                        !Modifier.isStatic(method.modifiers) &&
                            !method.isSynthetic && !method.isBridge &&
                            method.returnType == Void.TYPE && (
                            method.parameterTypes.contentEquals(arrayOf(classOf<String>(), classOf<Boolean>())) ||
                                method.parameterTypes.contentEquals(arrayOf(classOf<Boolean>(), classOf<String>()))
                            )
                    }.takeIf { it.size in 1..2 }.orEmpty()
                }.distinctBy { it.toGenericString() }
                clipboardHooks.forEach { method ->
                    descEnv.registrar.exact(
                        "free-copy:headline:${method.toGenericString()}",
                        method.declaringClass,
                        method.name,
                        *method.parameterTypes
                    ) {
                        before {
                            if (FreeCopyRuntimeConfig.runtimeDescriptionFreeCopyEnabled &&
                                (FreeCopyGestureState.descTouchedView != null ||
                                    FreeCopyGestureState.isOfficialSuppressionActive())
                            ) {
                                this.result = null
                            }
                        }
                    }
                }
            }
            descHookOk = true
            runCatching {
                sharedEnv.registrar.exact(
                    "free-copy:shared:set-primary-clip",
                    classOf<ClipboardManager>(),
                    "setPrimaryClip",
                    classOf<ClipData>()
                ) {
                    before {
                        if (!FreeCopyRuntimeConfig.runtimeCommentFreeCopyEnabled &&
                            !FreeCopyRuntimeConfig.runtimeDescriptionFreeCopyEnabled
                        ) return@before
                        if (FreeCopyGestureState.popupClipboardWriteInProgress.get() == true &&
                            FreeCopyGestureState.isOurBubbleShowing()
                        ) {
                            return@before
                        }
                        val clip = args.getOrNull(0) as? ClipData ?: return@before
                        if (ReplyTopologyClipboardWrite.owns(clip)) return@before
                        val clipText = runCatching {
                            clip.getItemAt(0).coerceToText(null)?.toString()
                        }.getOrNull() ?: return@before
                        if (clipText.isEmpty()) return@before
                        val gestureActive = FreeCopyRuntimeConfig.runtimeCommentFreeCopyEnabled &&
                            FreeCopyGestureState.commentTouchedView != null
                        val handledInSuppressWindow =
                            FreeCopyRuntimeConfig.runtimeCommentFreeCopyEnabled &&
                                FreeCopyGestureState.commentLongPressHandled &&
                                FreeCopyGestureState.isOfficialSuppressionActive()
                        if (gestureActive || handledInSuppressWindow) {
                            val fromSystemSelection = runCatching {
                                Throwable().stackTrace.any { frame ->
                                    val cn = frame.className
                                    cn.startsWith("android.widget.") ||
                                        cn.contains("FloatingToolbar") ||
                                        cn.contains("SelectionToolbar")
                                }
                            }.getOrDefault(false)
                            if (!fromSystemSelection) {
                                this.result = null
                                return@before
                            }
                        }
                        if (!FreeCopyRuntimeConfig.runtimeDescriptionFreeCopyEnabled) return@before
                        val desc = FreeCopyGestureState.descCachedViewRef?.get() ?: return@before
                        val descText = FreeCopyTextExtractor.readDescTextForClipboard(desc) ?: return@before
                        if (descText.isNotEmpty() && clipText == descText) {
                            this.result = null
                        }
                    }
                }
            }
            descEnv.logInfo("free_copy_desc_ok", "[BIL] 简介自由复制 hook 已注册(Modern setText+steal+touch)")
            runCatching {
                val viewClass = classOf<View>()
                for (m in KavaMemberLookup.declaredMethods(viewClass)) {
                    if (m.name != "performHapticFeedback") continue
                    runCatching {
                        sharedEnv.registrar.exact(
                            "free-copy:haptic:${m.toGenericString()}",
                            viewClass,
                            m.name,
                            *m.parameterTypes
                        ) {
                            before {
                                if ((FreeCopyRuntimeConfig.runtimeCommentFreeCopyEnabled &&
                                        (FreeCopyGestureState.commentTouchedView != null ||
                                            (FreeCopyGestureState.commentLongPressHandled &&
                                                FreeCopyGestureState.isOfficialSuppressionActive()))) ||
                                    (FreeCopyRuntimeConfig.descLikeFreeCopyEnabled &&
                                        (FreeCopyGestureState.descTouchedView != null ||
                                            (FreeCopyGestureState.descLongPressHandled &&
                                                FreeCopyGestureState.isOfficialSuppressionActive())))
                                ) {
                                    result = true
                                }
                            }
                        }
                    }
                }
            }
            runCatching {
                val pwClass = classOf<PopupWindow>()
                for (mn in listOf("showAsDropDown", "showAtLocation")) {
                    for (m in KavaMemberLookup.declaredMethods(pwClass)) {
                        if (m.name != mn) continue
                        runCatching {
                            sharedEnv.registrar.exact(
                                "free-copy:popup:${m.toGenericString()}",
                                pwClass,
                                m.name,
                                *m.parameterTypes
                            ) {
                                before {
                                    if (!FreeCopyRuntimeConfig.runtimeCommentFreeCopyEnabled &&
                                        !FreeCopyRuntimeConfig.runtimeDescriptionFreeCopyEnabled
                                    ) return@before
                                    val ourDecor = FreeCopyGestureState.ourBubbleDialogRef?.get()?.window?.decorView
                                    if (ourDecor != null) {
                                        when (val a0 = argOrNull(0)) {
                                            is View -> if (a0.rootView === ourDecor) return@before
                                            is IBinder -> if (a0 === ourDecor.windowToken) return@before
                                        }
                                    }
                                    val popupContent = runCatching {
                                        (thisObject as? PopupWindow)?.contentView
                                    }.getOrNull()
                                    if (popupContent?.javaClass?.name?.contains("HandleView") == true) return@before
                                    if (FreeCopyGestureState.shouldSuppressOfficialOverlay()) {
                                        setObjectExtra(EXTRA_SUPPRESS_POPUP, true)
                                    }
                                }
                                after {
                                    if (getObjectExtra(EXTRA_SUPPRESS_POPUP) == true) {
                                        runCatching {
                                            (thisObject as? PopupWindow)?.takeIf { it.isShowing }?.dismiss()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            runCatching {
                val dlgClass = classOf<Dialog>()
                for (m in KavaMemberLookup.declaredMethods(dlgClass)) {
                    if (m.name != "show") continue
                    runCatching {
                        sharedEnv.registrar.exact(
                            "free-copy:dialog:${m.toGenericString()}",
                            dlgClass,
                            m.name,
                            *m.parameterTypes
                        ) {
                            before {
                                if (!FreeCopyRuntimeConfig.runtimeCommentFreeCopyEnabled &&
                                    !FreeCopyRuntimeConfig.runtimeDescriptionFreeCopyEnabled
                                ) return@before
                                if (FreeCopyGestureState.shouldSuppressOfficialOverlay()) {
                                    if (thisObject != null &&
                                        thisObject === FreeCopyGestureState.ourBubbleDialogRef?.get()
                                    ) return@before
                                    setObjectExtra(EXTRA_SUPPRESS_DIALOG, true)
                                }
                            }
                            after {
                                if (getObjectExtra(EXTRA_SUPPRESS_DIALOG) == true) {
                                    runCatching {
                                        (thisObject as? Dialog)?.takeIf { it.isShowing }?.dismiss()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }.onFailure { t ->
            descEnv.logError("free_copy_desc_reg_err", "[BIL] 简介自由复制 hook 注册失败: $t")
        }
        if (!descHookOk) {
            descriptionFreeCopyHooksInstalled.set(false)
            descEnv.logError("free_copy_desc_reg_err", "[BIL] 简介自由复制 hook 注册失败")
        }
    }

    private fun publishFreeCopyCapabilities(environment: HookEnvironment) {
        if (environment.processName != FREE_COPY_TARGET_PACKAGE) return
        val common = descriptionFreeCopyHooksInstalled.get()
        val comment = commentFreeCopyBindingVerified.get()
        if (FreeCopyRuntimeConfig.runtimeCommentFreeCopyEnabled) {
            val paths = (if (comment) 1 else 0) + (if (common) 1 else 0)
            environment.reportCapability(
                CAPABILITY_COMMENT,
                if (paths > 0) FeatureInstallResult.Installed(
                    paths,
                    complete = paths == 2 && commentFamilies.isComplete()
                ) else FeatureInstallResult.Skipped("registration-failed")
            )
        }
        if (FreeCopyRuntimeConfig.runtimeDescriptionFreeCopyEnabled) {
            environment.reportCapability(
                CAPABILITY_DESCRIPTION,
                if (common) FeatureInstallResult.Installed(1)
                else FeatureInstallResult.Skipped("registration-failed")
            )
        }
        if (FreeCopyRuntimeConfig.runtimeTitleFreeCopyEnabled) {
            environment.reportCapability(
                CAPABILITY_TITLE,
                if (common) FeatureInstallResult.Installed(1)
                else FeatureInstallResult.Skipped("registration-failed")
            )
        }
    }

    private fun classExists(name: String, loader: ClassLoader?): Boolean =
        if (loader == null) false else KavaMemberLookup.hasClass(loader, name)

    companion object {
        const val ID = "free_copy"
        const val CAPABILITY_COMMENT = "free_copy_comment_enabled"
        const val CAPABILITY_DESCRIPTION = "free_copy_description_enabled"
        const val CAPABILITY_TITLE = "free_copy_title_enabled"
        private const val EXTRA_SUPPRESS_POPUP = "bil_suppress_popup_after_show"
        private const val EXTRA_SUPPRESS_DIALOG = "bil_suppress_dialog_after_show"
    }
}
