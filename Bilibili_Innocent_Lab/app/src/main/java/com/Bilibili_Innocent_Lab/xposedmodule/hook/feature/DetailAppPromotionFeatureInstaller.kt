package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.ViewGroup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isStatic
import com.highcapable.kavaref.extension.isSubclassOf
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 隐藏视频详细页播放器下方的话题、活动等推广卡片。
 *
 * 宿主的 [IAdUnderPlayer] 为接口，真正实现类由广告 SDK 在运行时返回。本安装器挂住
 * GAdBizKt#getGAdVideoDetail 后会立即解析当前 getUnderPlayer 实现并安装三条渲染路由；同时
 * 保留 getter 兜底，以兼容运行中替换实现类的宿主版本。只缓存进程生命周期内稳定的
 * Class/Member 注册状态，不缓存广告对象、Activity、Context 或 View。
 */
internal class DetailAppPromotionFeatureInstaller(
    private val enabled: Boolean
) : FeatureInstaller {

    override val id: String = ID

    private val attemptedVideoDetailClasses = ConcurrentHashMap.newKeySet<Class<*>>()
    private val attemptedUnderPlayerClasses = ConcurrentHashMap.newKeySet<Class<*>>()
    private val underPlayerGetters = ConcurrentHashMap<Class<*>, Method>()
    private val facadeHitLogged = AtomicBoolean(false)
    private val hitLogged = AtomicBoolean(false)
    private val renderReady = AtomicBoolean(false)

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) {
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }

        val types = resolveHostTypes(environment)
            ?: return missing(environment, "missing-host-types")
        val sceneAccess = resolveSceneAccess(types)
            ?: return missing(environment, "missing-unite-detail-scene")
        val facade = KavaMemberLookup.classOrNull(environment.classLoader, G_AD_BIZ_FACADE)
            ?: return missing(environment, "missing-ad-biz-facade")
        val videoDetailGetter = KavaMemberLookup.declaredMethods(
            facade,
            makeAccessible = true
        ) {
            it.name == GET_VIDEO_DETAIL && it.parameterCount == 0 &&
                it.isStatic &&
                (it.returnType isSubclassOf types.videoDetailClass)
        }.singleOrNull() ?: return missing(environment, "ambiguous-video-detail-getter")

        return runCatching {
            environment.registrar.exact(
                "detail_app_promotion.video_detail",
                videoDetailGetter.declaringClass,
                videoDetailGetter.name,
                *videoDetailGetter.parameterTypes
            ) {
                after {
                    val videoDetail = result ?: return@after
                    if (!types.videoDetailClass.isInstance(videoDetail)) return@after
                    if (facadeHitLogged.compareAndSet(false, true)) {
                        environment.reportStatus(CHANNEL_STATUS, "resolved:video-detail")
                        environment.logInfo(
                            "detail_app_promotion_facade_hit",
                            "[BIL] 视频详细页话题推广门面已命中，开始解析播放器下方实现"
                        )
                    }
                    resolveCurrentUnderPlayer(
                        environment = environment,
                        videoDetail = videoDetail,
                        types = types,
                        sceneAccess = sceneAccess
                    )
                }
            }
            environment.postToMain?.invoke {
                if (!renderReady.get()) {
                    runCatching {
                        findInitializedVideoDetail(
                            facade = facade,
                            videoDetailClass = types.videoDetailClass
                        )
                    }
                        .onSuccess { videoDetail ->
                            if (videoDetail != null) {
                                resolveCurrentUnderPlayer(
                                    environment = environment,
                                    videoDetail = videoDetail,
                                    types = types,
                                    sceneAccess = sceneAccess
                                )
                            }
                        }
                        .onFailure { throwable ->
                            environment.logError(
                                "detail_app_promotion_warmup",
                                "[BIL] 视频详细页话题推广已初始化实例扫描失败，保留门面惰性兜底: $throwable"
                            )
                        }
                }
            }
            environment.reportStatus(CHANNEL_STATUS, "armed:facade")
            environment.logInfo(
                "detail_app_promotion_armed",
                "[BIL] 视频详细页话题推广门面拦截器已就绪"
            )
            FeatureInstallResult.Installed(1)
        }.getOrElse { throwable ->
            environment.logError(
                "detail_app_promotion_install",
                "[BIL] 视频详细页话题推广入口注册失败: $throwable"
            )
            environment.reportStatus(CHANNEL_STATUS, "failed:entry")
            FeatureInstallResult.Skipped("entry-registration-failed")
        }
    }

    /**
     * 只读取已完成初始化的 Kotlin Lazy，绝不因适配而提前创建宿主广告服务。
     */
    private fun findInitializedVideoDetail(
        facade: Class<*>,
        videoDetailClass: Class<*>
    ): Any? {
        val lazyCandidates = KavaMemberLookup.fields(
            declaringClass = facade,
            includeSuperclasses = false,
            makeAccessible = true
        ) { field ->
            field.isStatic && field.type == classOf<Lazy<*>>()
        }.mapNotNull { field ->
            field.get(null) as? Lazy<*>
        }
        return initializedLazyValueOfType(lazyCandidates, videoDetailClass)
    }

    private fun resolveCurrentUnderPlayer(
        environment: HookEnvironment,
        videoDetail: Any,
        types: HostTypes,
        sceneAccess: SceneAccess
    ) {
        val owner = videoDetail.javaClass
        val getter = underPlayerGetters[owner] ?: locateUnderPlayerGetter(
            owner = owner,
            underPlayerClass = types.underPlayerClass
        )?.also { underPlayerGetters[owner] = it }
        if (getter == null) {
            if (!attemptedVideoDetailClasses.add(owner)) return
            environment.reportStatus(CHANNEL_STATUS, "partial:under-player-getter")
            environment.logError(
                "detail_app_promotion_under_player_missing",
                "[BIL] 无法唯一定位 ${owner.name}#getUnderPlayer，已放行宿主"
            )
            return
        }

        installUnderPlayerGetterFallback(
            environment = environment,
            owner = owner,
            getter = getter,
            types = types,
            sceneAccess = sceneAccess
        )

        runCatching { getter.invoke(videoDetail) }
            .onSuccess { underPlayer ->
                if (underPlayer != null && types.underPlayerClass.isInstance(underPlayer)) {
                    installRenderHooks(
                        environment = environment,
                        owner = underPlayer.javaClass,
                        types = types,
                        sceneAccess = sceneAccess
                    )
                }
            }
            .onFailure { throwable ->
                environment.reportStatus(CHANNEL_STATUS, "partial:under-player-read")
                environment.logError(
                    "detail_app_promotion_under_player_read",
                    "[BIL] 读取播放器下方推广实现失败，保留 getter 兜底: $throwable"
                )
            }
    }

    private fun installUnderPlayerGetterFallback(
        environment: HookEnvironment,
        owner: Class<*>,
        getter: Method,
        types: HostTypes,
        sceneAccess: SceneAccess
    ) {
        if (!attemptedVideoDetailClasses.add(owner)) return
        runCatching {
            environment.registrar.exact(
                "detail_app_promotion.under_player.${owner.name}",
                getter.declaringClass,
                getter.name,
                *getter.parameterTypes
            ) {
                after {
                    val underPlayer = result ?: return@after
                    if (!types.underPlayerClass.isInstance(underPlayer)) return@after
                    installRenderHooks(
                        environment = environment,
                        owner = underPlayer.javaClass,
                        types = types,
                        sceneAccess = sceneAccess
                    )
                }
            }
        }.onFailure { throwable ->
            environment.reportStatus(CHANNEL_STATUS, "partial:under-player-registration")
            environment.logError(
                "detail_app_promotion_under_player_hook",
                "[BIL] getUnderPlayer 兜底注册失败，已保留立即解析结果: $throwable"
            )
        }
    }

    private fun locateUnderPlayerGetter(
        owner: Class<*>,
        underPlayerClass: Class<*>
    ): Method? {
        val candidates = mostSpecificMethods(owner) {
            it.name == GET_UNDER_PLAYER && it.parameterCount == 0 &&
                (it.returnType isSubclassOf underPlayerClass)
        }
        return candidates.singleOrNull()
    }

    private fun installRenderHooks(
        environment: HookEnvironment,
        owner: Class<*>,
        types: HostTypes,
        sceneAccess: SceneAccess
    ) {
        if (!attemptedUnderPlayerClasses.add(owner)) return
        val methods = mostSpecificMethods(owner) { method ->
            matchesDetailPromotionRenderMethod(
                method = method,
                callbackClass = types.callbackClass,
                bridgeClass = types.bridgeClass,
                configClass = types.configClass
            )
        }
        if (methods.isEmpty()) {
            environment.reportStatus(CHANNEL_STATUS, "partial:render-methods")
            environment.logError(
                "detail_app_promotion_render_missing",
                "[BIL] ${owner.name} 未找到受约束的话题推广渲染方法，已放行宿主"
            )
            return
        }

        val installedRoutes = linkedSetOf<String>()
        methods.forEachIndexed { index, method ->
            runCatching {
                environment.registrar.exact(
                    "detail_app_promotion.render.${method.declaringClass.name}.${method.name}.$index",
                    method.declaringClass,
                    method.name,
                    *method.parameterTypes
                ) {
                    before {
                        val config = detailPromotionConfigOrNull(args, types.configClass)
                            ?: return@before
                        if (!sceneAccess.isUniteDetail(config)) return@before
                        result = null
                        if (hitLogged.compareAndSet(false, true)) {
                            environment.logInfo(
                                "detail_app_promotion_hit",
                                "[BIL] 已隐藏视频详细页话题推广(route=${method.name})"
                            )
                        }
                    }
                }
                installedRoutes += when (method.name) {
                    GET_UPPER_NEST_VIEW -> "nest"
                    GET_UPPER_AD_VIEW -> "list"
                    GET_UPPER_HD_VIEW -> "hd"
                    else -> method.name
                }
            }.onFailure { throwable ->
                environment.logError(
                    "detail_app_promotion_render_${method.name}_$index",
                    "[BIL] ${method.name} 注册失败，已放行该入口: $throwable"
                )
            }
        }

        if (installedRoutes.isEmpty()) {
            environment.reportStatus(CHANNEL_STATUS, "failed:render-registration")
            return
        }
        val routeSummary = installedRoutes.sorted().joinToString("+")
        renderReady.set(true)
        environment.reportStatus(CHANNEL_STATUS, "ready:$routeSummary")
        environment.logInfo(
            "detail_app_promotion_ready",
            "[BIL] 视频详细页话题推广渲染拦截已安装(route=$routeSummary)"
        )
    }

    /**
     * 由实际类向父类遍历，按方法名与参数签名保留最靠近实际类的实现，避免同时 Hook override 与 super。
     */
    private fun mostSpecificMethods(
        owner: Class<*>,
        predicate: (Method) -> Boolean
    ): List<Method> {
        val signatures = HashSet<String>()
        return KavaMemberLookup.methods(
            declaringClass = owner,
            includeSuperclasses = true,
            makeAccessible = true,
            predicate = predicate
        ).filter { method ->
            signatures.add(
                method.name + method.parameterTypes.joinToString(
                    prefix = "(",
                    postfix = ")"
                ) { it.name }
            )
        }
    }

    private fun resolveHostTypes(environment: HookEnvironment): HostTypes? {
        val loader = environment.classLoader ?: return null
        return HostTypes(
            videoDetailClass = KavaMemberLookup.classOrNull(loader, G_AD_VIDEO_DETAIL)
                ?: return null,
            underPlayerClass = KavaMemberLookup.classOrNull(loader, I_AD_UNDER_PLAYER) ?: return null,
            callbackClass = KavaMemberLookup.classOrNull(loader, I_AD_UNDER_PLAYER_CALLBACK)
                ?: return null,
            bridgeClass = KavaMemberLookup.classOrNull(loader, AD_UPPER_BRIDGE) ?: return null,
            configClass = KavaMemberLookup.classOrNull(loader, AD_UPPER_CONFIG) ?: return null,
            sceneClass = KavaMemberLookup.classOrNull(loader, AD_UPPER_SCENE) ?: return null
        )
    }

    private fun resolveSceneAccess(types: HostTypes): SceneAccess? {
        val uniteDetail = types.sceneClass.enumConstants
            ?.firstOrNull { (it as? Enum<*>)?.name == UNITE_DETAIL }
            ?: return null
        val getter = KavaMemberLookup.methods(
            declaringClass = types.configClass,
            includeSuperclasses = true,
            makeAccessible = true
        ) {
            it.name == GET_SCENE && it.parameterCount == 0 &&
                (it.returnType isSubclassOf types.sceneClass)
        }.firstOrNull()
        val field = if (getter == null) {
            KavaMemberLookup.fields(
                declaringClass = types.configClass,
                includeSuperclasses = true,
                makeAccessible = true
            ) { it.type isSubclassOf types.sceneClass }.singleOrNull()
        } else {
            null
        }
        if (getter == null && field == null) return null
        return SceneAccess(types.configClass, getter, field, uniteDetail)
    }

    private fun missing(environment: HookEnvironment, reason: String): FeatureInstallResult {
        environment.reportStatus(CHANNEL_STATUS, "missing:$reason")
        environment.logError(
            "detail_app_promotion_$reason",
            "[BIL] 视频详细页话题推广 Hook 不可用，已放行宿主: $reason"
        )
        return FeatureInstallResult.Skipped(reason)
    }

    private data class HostTypes(
        val videoDetailClass: Class<*>,
        val underPlayerClass: Class<*>,
        val callbackClass: Class<*>,
        val bridgeClass: Class<*>,
        val configClass: Class<*>,
        val sceneClass: Class<*>
    )

    private class SceneAccess(
        private val configClass: Class<*>,
        private val getter: Method?,
        private val field: Field?,
        private val uniteDetail: Any
    ) {
        fun isUniteDetail(config: Any): Boolean {
            if (!configClass.isInstance(config)) return false
            val scene = runCatching {
                getter?.invoke(config) ?: field?.get(config)
            }.getOrNull()
            return matchesDetailPromotionScene(scene, uniteDetail)
        }
    }

    companion object {
        const val ID = "detail_app_promotion"
        private const val CHANNEL_STATUS = "detail_app_promotion_status"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val G_AD_BIZ_FACADE = "com.bilibili.gripper.api.ad.biz.GAdBizKt"
        private const val G_AD_VIDEO_DETAIL = "com.bilibili.gripper.api.ad.biz.GAdVideoDetail"
        private const val I_AD_UNDER_PLAYER =
            "com.bilibili.gripper.api.ad.biz.videodetail.underplayer.IAdUnderPlayer"
        private const val I_AD_UNDER_PLAYER_CALLBACK =
            "com.bilibili.gripper.api.ad.biz.videodetail.underplayer.IAdUnderPlayerCallback"
        private const val AD_UPPER_BRIDGE =
            "com.bilibili.gripper.api.ad.biz.videodetail.underplayer.AdUpperBridge"
        private const val AD_UPPER_CONFIG =
            "com.bilibili.gripper.api.ad.biz.videodetail.underplayer.AdUpperConfig"
        private const val AD_UPPER_SCENE =
            "com.bilibili.gripper.api.ad.biz.videodetail.underplayer.AdUpperScene"
        private const val GET_VIDEO_DETAIL = "getGAdVideoDetail"
        private const val GET_UNDER_PLAYER = "getUnderPlayer"
        private const val GET_UPPER_NEST_VIEW = "getUpperNestView"
        private const val GET_UPPER_AD_VIEW = "getUpperAdView"
        private const val GET_UPPER_HD_VIEW = "getUpperHDView"
        private const val GET_SCENE = "getScene"
        private const val UNITE_DETAIL = "UNITE_DETAIL"
    }
}

private val DETAIL_PROMOTION_RENDER_METHOD_NAMES = setOf(
    "getUpperNestView",
    "getUpperAdView",
    "getUpperHDView"
)

/** 公开方法名 + 参数角色约束；供安装器与 JVM 多版本契约测试共用。 */
internal fun matchesDetailPromotionRenderMethod(
    method: Method,
    callbackClass: Class<*>,
    bridgeClass: Class<*>,
    configClass: Class<*>
): Boolean =
    method.name in DETAIL_PROMOTION_RENDER_METHOD_NAMES &&
        (method.returnType isSubclassOf callbackClass) &&
        method.parameterTypes.any { it isSubclassOf classOf<ViewGroup>() } &&
        method.parameterTypes.any { it isSubclassOf bridgeClass } &&
        method.parameterTypes.any { it isSubclassOf configClass }

/** 从渲染实参中定位场景配置；调用者类型不是渲染方法的参数，不参与热路径门禁。 */
internal fun detailPromotionConfigOrNull(
    args: Array<out Any?>,
    configClass: Class<*>
): Any? = args.firstOrNull { candidate -> candidate != null && configClass.isInstance(candidate) }

/** 仅统一视频详细页场景允许拦截；读取失败或未知场景均失败开放。 */
internal fun matchesDetailPromotionScene(scene: Any?, uniteDetail: Any): Boolean =
    scene === uniteDetail || scene == uniteDetail

/** 返回首个已初始化且类型匹配的值；未初始化候选不会执行 initializer。 */
internal fun initializedLazyValueOfType(
    candidates: Iterable<Lazy<*>>,
    expectedType: Class<*>
): Any? {
    candidates.forEach { candidate ->
        if (!candidate.isInitialized()) return@forEach
        val value = candidate.value
        if (expectedType.isInstance(value)) return value
    }
    return null
}
