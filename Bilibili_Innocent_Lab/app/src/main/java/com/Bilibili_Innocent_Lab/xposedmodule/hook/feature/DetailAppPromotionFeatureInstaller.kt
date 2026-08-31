package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.widget.FrameLayout
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 隐藏视频详细页播放器下方的应用推广卡片。
 *
 * 宿主的 [IAdUnderPlayer] 为接口，真正实现类由广告 SDK 在运行时返回。本安装器先挂住
 * GAdBizKt#getGAdVideoDetail，再按真实对象类型惰性发现 getUnderPlayer 与两个卡片创建方法。
 * 只缓存进程生命周期内稳定的 Class/Member 注册状态，不缓存广告对象、Activity 或 View。
 */
internal class DetailAppPromotionFeatureInstaller(
    private val enabled: Boolean
) : FeatureInstaller {

    override val id: String = ID

    private val attemptedVideoDetailClasses = ConcurrentHashMap.newKeySet<Class<*>>()
    private val attemptedUnderPlayerClasses = ConcurrentHashMap.newKeySet<Class<*>>()
    private val hitLogged = AtomicBoolean(false)

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
                Modifier.isStatic(it.modifiers) && it.returnType != Void.TYPE
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
                    installUnderPlayerGetter(
                        environment = environment,
                        owner = videoDetail.javaClass,
                        types = types,
                        sceneAccess = sceneAccess
                    )
                }
            }
            environment.reportStatus(CHANNEL_STATUS, "armed")
            environment.logInfo(
                "detail_app_promotion_armed",
                "[BIL] 视频详细页应用推广拦截器已就绪，等待广告实现类"
            )
            FeatureInstallResult.Installed(1)
        }.getOrElse { throwable ->
            environment.logError(
                "detail_app_promotion_install",
                "[BIL] 视频详细页应用推广入口注册失败: $throwable"
            )
            environment.reportStatus(CHANNEL_STATUS, "failed:entry")
            FeatureInstallResult.Skipped("entry-registration-failed")
        }
    }

    private fun installUnderPlayerGetter(
        environment: HookEnvironment,
        owner: Class<*>,
        types: HostTypes,
        sceneAccess: SceneAccess
    ) {
        if (!attemptedVideoDetailClasses.add(owner)) return
        val candidates = mostSpecificMethods(owner) {
            it.name == GET_UNDER_PLAYER && it.parameterCount == 0 &&
                types.underPlayerClass.isAssignableFrom(it.returnType)
        }
        val getter = candidates.singleOrNull()
        if (getter == null) {
            environment.reportStatus(CHANNEL_STATUS, "partial:under-player-getter")
            environment.logError(
                "detail_app_promotion_under_player_missing",
                "[BIL] 无法唯一定位 ${owner.name}#getUnderPlayer，已放行宿主"
            )
            return
        }

        runCatching {
            environment.registrar.exact(
                "detail_app_promotion.under_player.${owner.name}",
                getter.declaringClass,
                getter.name,
                *getter.parameterTypes
            ) {
                after {
                    val underPlayer = result ?: return@after
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
                "[BIL] getUnderPlayer 注册失败，已放行宿主: $throwable"
            )
        }
    }

    private fun installRenderHooks(
        environment: HookEnvironment,
        owner: Class<*>,
        types: HostTypes,
        sceneAccess: SceneAccess
    ) {
        if (!attemptedUnderPlayerClasses.add(owner)) return
        val methods = mostSpecificMethods(owner) { method ->
            method.name in RENDER_METHOD_NAMES &&
                types.callbackClass.isAssignableFrom(method.returnType) &&
                method.parameterTypes.any(FrameLayout::class.java::isAssignableFrom) &&
                method.parameterTypes.any(types.bridgeClass::isAssignableFrom) &&
                method.parameterTypes.any(types.configClass::isAssignableFrom)
        }
        if (methods.isEmpty()) {
            environment.reportStatus(CHANNEL_STATUS, "partial:render-methods")
            environment.logError(
                "detail_app_promotion_render_missing",
                "[BIL] ${owner.name} 未找到受约束的播放器下方推广渲染方法，已放行宿主"
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
                        if (args.none(types.detailAdServiceClass::isInstance)) return@before
                        val config = args.firstOrNull(types.configClass::isInstance)
                            ?: return@before
                        if (!sceneAccess.isUniteDetail(config)) return@before
                        result = null
                        if (hitLogged.compareAndSet(false, true)) {
                            environment.logInfo(
                                "detail_app_promotion_hit",
                                "[BIL] 已隐藏视频详细页应用推广(route=${method.name})"
                            )
                        }
                    }
                }
                installedRoutes += when (method.name) {
                    GET_UPPER_NEST_VIEW -> "nest"
                    GET_UPPER_AD_VIEW -> "list"
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
        environment.reportStatus(CHANNEL_STATUS, "success:$routeSummary")
        environment.logInfo(
            "detail_app_promotion_ready",
            "[BIL] 视频详细页应用推广渲染拦截已安装(route=$routeSummary)"
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
            underPlayerClass = KavaMemberLookup.classOrNull(loader, I_AD_UNDER_PLAYER) ?: return null,
            callbackClass = KavaMemberLookup.classOrNull(loader, I_AD_UNDER_PLAYER_CALLBACK)
                ?: return null,
            bridgeClass = KavaMemberLookup.classOrNull(loader, AD_UPPER_BRIDGE) ?: return null,
            configClass = KavaMemberLookup.classOrNull(loader, AD_UPPER_CONFIG) ?: return null,
            sceneClass = KavaMemberLookup.classOrNull(loader, AD_UPPER_SCENE) ?: return null,
            detailAdServiceClass = KavaMemberLookup.classOrNull(loader, DETAIL_AD_SERVICE)
                ?: return null
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
                types.sceneClass.isAssignableFrom(it.returnType)
        }.firstOrNull()
        val field = if (getter == null) {
            KavaMemberLookup.fields(
                declaringClass = types.configClass,
                includeSuperclasses = true,
                makeAccessible = true
            ) { types.sceneClass.isAssignableFrom(it.type) }.singleOrNull()
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
            "[BIL] 视频详细页应用推广 Hook 不可用，已放行宿主: $reason"
        )
        return FeatureInstallResult.Skipped(reason)
    }

    private data class HostTypes(
        val underPlayerClass: Class<*>,
        val callbackClass: Class<*>,
        val bridgeClass: Class<*>,
        val configClass: Class<*>,
        val sceneClass: Class<*>,
        val detailAdServiceClass: Class<*>
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
            return scene === uniteDetail || scene == uniteDetail
        }
    }

    companion object {
        const val ID = "detail_app_promotion"
        private const val CHANNEL_STATUS = "detail_app_promotion_status"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val G_AD_BIZ_FACADE = "com.bilibili.gripper.api.ad.biz.GAdBizKt"
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
        private const val DETAIL_AD_SERVICE =
            "com.bilibili.ship.theseus.ugc.ad.DetailAdService"
        private const val GET_VIDEO_DETAIL = "getGAdVideoDetail"
        private const val GET_UNDER_PLAYER = "getUnderPlayer"
        private const val GET_UPPER_NEST_VIEW = "getUpperNestView"
        private const val GET_UPPER_AD_VIEW = "getUpperAdView"
        private const val GET_SCENE = "getScene"
        private const val UNITE_DETAIL = "UNITE_DETAIL"
        private val RENDER_METHOD_NAMES = setOf(GET_UPPER_NEST_VIEW, GET_UPPER_AD_VIEW)
    }
}
