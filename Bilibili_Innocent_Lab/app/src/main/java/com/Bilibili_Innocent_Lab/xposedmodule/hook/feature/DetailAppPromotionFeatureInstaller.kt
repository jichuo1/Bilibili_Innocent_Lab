package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isAbstract
import com.highcapable.kavaref.extension.isStatic
import com.highcapable.kavaref.extension.isSubclassOf
import com.highcapable.kavaref.extension.makeAccessible
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
    private val enabled: Boolean,
    private val relateAdEnabled: Boolean = enabled
) : FeatureInstaller {

    override val id: String = ID

    private val attemptedVideoDetailClasses = ConcurrentHashMap.newKeySet<Class<*>>()
    private val attemptedUnderPlayerClasses = ConcurrentHashMap.newKeySet<Class<*>>()
    private val attemptedRelateVideoDetailClasses = ConcurrentHashMap.newKeySet<Class<*>>()
    private val attemptedRelateClasses = ConcurrentHashMap.newKeySet<Class<*>>()
    private val underPlayerGetters = ConcurrentHashMap<Class<*>, Method>()
    private val relateGetters = ConcurrentHashMap<Class<*>, Method>()
    private val facadeHitLogged = AtomicBoolean(false)
    private val hitLogged = AtomicBoolean(false)
    private val relateHitLogged = AtomicBoolean(false)
    private val renderReady = AtomicBoolean(false)
    private val relateRenderReady = AtomicBoolean(false)

    @Volatile
    private var underPlayerRouteSummary: String? = null

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled && !relateAdEnabled) {
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }

        val loader = environment.classLoader ?: return missing(environment, "missing-class-loader")
        val videoDetailClass = KavaMemberLookup.classOrNull(loader, G_AD_VIDEO_DETAIL)
            ?: return missing(environment, "missing-video-detail-type")
        val types = if (enabled) resolveHostTypes(environment) else null
        val sceneAccess = types?.let(::resolveSceneAccess)
        val relateClass = if (relateAdEnabled) {
            KavaMemberLookup.classOrNull(loader, I_AD_VIDEO_RELATE)
        } else {
            null
        }
        val underPlayerAvailable = enabled && types != null && sceneAccess != null
        val relateAvailable = relateAdEnabled && relateClass != null
        if (!underPlayerAvailable && !relateAvailable) {
            return missing(environment, "missing-enabled-route")
        }
        if (enabled && !underPlayerAvailable) {
            environment.logError(
                "detail_app_promotion_under_player_dependencies",
                "[BIL] 播放器下方推广依赖不完整，广告推荐渲染拦截仍继续安装"
            )
        }
        if (relateAdEnabled && !relateAvailable) {
            environment.logError(
                "detail_app_promotion_relate_dependencies",
                "[BIL] 广告推荐接口未定位，播放器下方推广拦截仍继续安装"
            )
        }
        val facade = KavaMemberLookup.classOrNull(loader, G_AD_BIZ_FACADE)
            ?: return missing(environment, "missing-ad-biz-facade")
        val videoDetailGetter = KavaMemberLookup.declaredMethods(
            facade,
            makeAccessible = true
        ) {
            it.name == GET_VIDEO_DETAIL && it.parameterCount == 0 &&
                it.isStatic &&
                (it.returnType isSubclassOf videoDetailClass)
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
                    if (!videoDetailClass.isInstance(videoDetail)) return@after
                    if (facadeHitLogged.compareAndSet(false, true)) {
                        environment.reportStatus(CHANNEL_STATUS, "resolved:video-detail")
                        environment.logInfo(
                            "detail_app_promotion_facade_hit",
                            "[BIL] 视频详细页广告门面已命中，开始解析已启用渲染路径"
                        )
                    }
                    if (underPlayerAvailable) {
                        resolveCurrentUnderPlayer(
                            environment = environment,
                            videoDetail = videoDetail,
                            types = requireNotNull(types),
                            sceneAccess = requireNotNull(sceneAccess)
                        )
                    }
                    if (relateAvailable) {
                        resolveCurrentRelate(
                            environment = environment,
                            videoDetail = videoDetail,
                            relateClass = requireNotNull(relateClass)
                        )
                    }
                }
            }
            environment.postToMain?.invoke {
                if ((underPlayerAvailable && !renderReady.get()) ||
                    (relateAvailable && !relateRenderReady.get())
                ) {
                    runCatching {
                        findInitializedVideoDetail(
                            facade = facade,
                            videoDetailClass = videoDetailClass
                        )
                    }
                        .onSuccess { videoDetail ->
                            if (videoDetail != null) {
                                if (underPlayerAvailable) {
                                    resolveCurrentUnderPlayer(
                                        environment = environment,
                                        videoDetail = videoDetail,
                                        types = requireNotNull(types),
                                        sceneAccess = requireNotNull(sceneAccess)
                                    )
                                }
                                if (relateAvailable) {
                                    resolveCurrentRelate(
                                        environment = environment,
                                        videoDetail = videoDetail,
                                        relateClass = requireNotNull(relateClass)
                                    )
                                }
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
                "[BIL] 视频详细页广告门面拦截器已就绪" +
                    "(underPlayer=$underPlayerAvailable,relate=$relateAvailable)"
            )
            val componentHooks = if (relateAdEnabled) {
                installRelateGameComponentBlock(environment)
            } else {
                0
            }
            FeatureInstallResult.Installed(1 + componentHooks)
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

    private fun installRelateGameComponentBlock(environment: HookEnvironment): Int {
        val loader = environment.classLoader ?: return 0
        val viewBindingClass = KavaMemberLookup.classOrNull(loader, ANDROIDX_VIEW_BINDING)
            ?: return 0
        val baseComponent = GEMINI_BINDING_COMPONENT_CLASSES.firstNotNullOfOrNull { className ->
            KavaMemberLookup.classOrNull(loader, className)?.takeIf { candidate ->
                // 接口的 isAbstract 同样为 true；gemini 包里的 UIComponent 正是接口，
                // 必须显式排除，否则字母表候选会把它当成基类。
                candidate.isAbstract && !candidate.isInterface &&
                    KavaMemberLookup.methods(
                        candidate,
                        includeSuperclasses = true,
                        makeAccessible = true
                    ) { it.name == CREATE_VIEW_ENTRY && it.parameterCount == 2 }.isNotEmpty() &&
                    KavaMemberLookup.methods(
                        candidate,
                        includeSuperclasses = true,
                        makeAccessible = true
                    ) { it.name == BIND_TO_VIEW && it.parameterCount == 2 }.isNotEmpty()
            }
        } ?: return 0
        val gameComponent = RELATE_GAME_COMPONENT_CLASSES.mapNotNull { className ->
            KavaMemberLookup.classOrNull(loader, className)?.takeIf { candidate ->
                candidate isSubclassOf baseComponent &&
                    !candidate.isInterface &&
                    !candidate.isAbstract &&
                    KavaMemberLookup.declaredMethods(candidate, makeAccessible = true) { method ->
                        method.parameterTypes.contentEquals(
                            arrayOf(
                                classOf<Context>(),
                                classOf<LayoutInflater>(),
                                classOf<ViewGroup>()
                            )
                        ) && method.returnType isSubclassOf viewBindingClass
                    }.isNotEmpty() &&
                    KavaMemberLookup.declaredMethods(candidate, makeAccessible = true) { method ->
                        method.parameterCount == 2 &&
                            method.parameterTypes[0] isSubclassOf viewBindingClass &&
                            method.parameterTypes[1].name == KOTLIN_CONTINUATION
                    }.isNotEmpty()
            }
        }.distinctBy { it.name }.singleOrNull() ?: return 0
        val simpleViewEntry = KavaMemberLookup.classOrNull(loader, GEMINI_SIMPLE_VIEW_ENTRY)
            ?: return 0
        val simpleViewEntryConstructor = simpleViewEntry.declaredConstructors
            .filter { it.parameterTypes.contentEquals(arrayOf(classOf<View>())) }
            .singleOrNull()
            ?.apply { makeAccessible() }
            ?: return 0
        val createViewEntry = KavaMemberLookup.methods(
            baseComponent,
            includeSuperclasses = true,
            makeAccessible = true
        ) { method ->
            method.name == CREATE_VIEW_ENTRY && method.parameterTypes.contentEquals(
                arrayOf(classOf<Context>(), classOf<ViewGroup>())
            )
        }.distinctBy(Method::toGenericString).singleOrNull() ?: return 0
        val bindToView = KavaMemberLookup.methods(
            baseComponent,
            includeSuperclasses = true,
            makeAccessible = true
        ) { method ->
            method.name == BIND_TO_VIEW && method.parameterCount == 2 &&
                method.parameterTypes[1].name == KOTLIN_CONTINUATION
        }.distinctBy(Method::toGenericString).singleOrNull() ?: return 0

        var installed = 0
        runCatching {
            environment.registrar.exact(
                "detail_app_promotion.relate_game.create",
                createViewEntry.declaringClass,
                createViewEntry.name,
                *createViewEntry.parameterTypes
            ) {
                before {
                    if (!gameComponent.isInstance(thisObject)) return@before
                    val context = args.firstOrNull() as? Context ?: return@before
                    val emptyEntry = runCatching {
                        simpleViewEntryConstructor.newInstance(View(context))
                    }.getOrNull() ?: return@before
                    environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                    result = emptyEntry
                    environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                }
            }
            installed += 1
        }.onFailure { throwable ->
            environment.logError(
                "detail_app_promotion_relate_game_create",
                "[BIL] 游戏推荐空视图入口注册失败，已放行该入口: $throwable"
            )
        }
        runCatching {
            environment.registrar.exact(
                "detail_app_promotion.relate_game.bind",
                bindToView.declaringClass,
                bindToView.name,
                *bindToView.parameterTypes
            ) {
                before {
                    if (!gameComponent.isInstance(thisObject)) return@before
                    result = Unit
                }
            }
            installed += 1
        }.onFailure { throwable ->
            environment.logError(
                "detail_app_promotion_relate_game_bind",
                "[BIL] 游戏推荐绑定入口注册失败，已放行该入口: $throwable"
            )
        }
        if (installed > 0) {
            environment.logInfo(
                "detail_app_promotion_relate_game_ready",
                "[BIL] 视频详细页游戏推荐组件兜底已安装(hooks=$installed)"
            )
        }
        return installed
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

    private fun resolveCurrentRelate(
        environment: HookEnvironment,
        videoDetail: Any,
        relateClass: Class<*>
    ) {
        val owner = videoDetail.javaClass
        val getter = relateGetters[owner] ?: locateRelateGetter(owner, relateClass)
            ?.also { relateGetters[owner] = it }
        if (getter == null) {
            if (!attemptedRelateVideoDetailClasses.add(owner)) return
            environment.reportStatus(CHANNEL_STATUS, "partial:relate-getter")
            environment.logError(
                "detail_app_promotion_relate_getter_missing",
                "[BIL] 无法唯一定位 ${owner.name}#getRelate，已放行该广告推荐路径"
            )
            return
        }

        installRelateGetterFallback(environment, owner, getter, relateClass)
        runCatching { getter.invoke(videoDetail) }
            .onSuccess { relate ->
                if (relate != null && relateClass.isInstance(relate)) {
                    installRelateRenderHooks(environment, relate.javaClass, relateClass)
                }
            }
            .onFailure { throwable ->
                environment.reportStatus(CHANNEL_STATUS, "partial:relate-read")
                environment.logError(
                    "detail_app_promotion_relate_read",
                    "[BIL] 读取广告推荐实现失败，保留 getRelate 兜底: $throwable"
                )
            }
    }

    private fun installRelateGetterFallback(
        environment: HookEnvironment,
        owner: Class<*>,
        getter: Method,
        relateClass: Class<*>
    ) {
        if (!attemptedRelateVideoDetailClasses.add(owner)) return
        runCatching {
            environment.registrar.exact(
                "detail_app_promotion.relate.${owner.name}",
                getter.declaringClass,
                getter.name,
                *getter.parameterTypes
            ) {
                after {
                    val relate = result ?: return@after
                    if (!relateClass.isInstance(relate)) return@after
                    installRelateRenderHooks(environment, relate.javaClass, relateClass)
                }
            }
        }.onFailure { throwable ->
            environment.reportStatus(CHANNEL_STATUS, "partial:relate-registration")
            environment.logError(
                "detail_app_promotion_relate_hook",
                "[BIL] getRelate 兜底注册失败，已保留立即解析结果: $throwable"
            )
        }
    }

    private fun locateRelateGetter(owner: Class<*>, relateClass: Class<*>): Method? =
        mostSpecificMethods(owner) {
            it.name == GET_RELATE && it.parameterCount == 0 &&
                !it.returnType.isPrimitive &&
                (it.returnType == classOf<Any>() || it.returnType isSubclassOf relateClass)
        }.singleOrNull()

    private fun installRelateRenderHooks(
        environment: HookEnvironment,
        owner: Class<*>,
        relateClass: Class<*>
    ) {
        if (!attemptedRelateClasses.add(owner)) return
        if (!(owner isSubclassOf relateClass)) return
        val methods = mostSpecificMethods(owner) { method ->
            matchesDetailRelateAdRenderMethod(method)
        }
        if (methods.isEmpty()) {
            environment.reportStatus(CHANNEL_STATUS, "partial:ad-relate-method")
            environment.logError(
                "detail_app_promotion_relate_render_missing",
                "[BIL] ${owner.name} 未找到 getAdRelateView，已放行该实现"
            )
            return
        }
        var installed = 0
        methods.forEachIndexed { index, method ->
            runCatching {
                environment.registrar.exact(
                    "detail_app_promotion.relate_render.${owner.name}.$index",
                    method.declaringClass,
                    method.name,
                    *method.parameterTypes
                ) {
                    before {
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                        result = null
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                        if (relateHitLogged.compareAndSet(false, true)) {
                            environment.logInfo(
                                "detail_app_promotion_relate_hit",
                                "[BIL] 已隐藏视频详细页广告推荐视图(route=${method.name})"
                            )
                        }
                    }
                }
                installed += 1
            }.onFailure { throwable ->
                environment.logError(
                    "detail_app_promotion_relate_render_$index",
                    "[BIL] ${method.name} 注册失败，已放行该广告推荐入口: $throwable"
                )
            }
        }
        if (installed == 0) {
            environment.reportStatus(CHANNEL_STATUS, "failed:ad-relate-registration")
            return
        }
        relateRenderReady.set(true)
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        reportReadyStatus(environment)
        environment.logInfo(
            "detail_app_promotion_relate_ready",
            "[BIL] 视频详细页广告推荐渲染拦截已安装(methods=$installed)"
        )
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
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                        result = null
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
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
        underPlayerRouteSummary = routeSummary
        renderReady.set(true)
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        reportReadyStatus(environment)
        environment.logInfo(
            "detail_app_promotion_ready",
            "[BIL] 视频详细页话题推广渲染拦截已安装(route=$routeSummary)"
        )
    }

    private fun reportReadyStatus(environment: HookEnvironment) {
        val routes = buildList {
            underPlayerRouteSummary?.let { add("under-player:$it") }
            if (relateRenderReady.get()) add("relate")
        }
        if (routes.isNotEmpty()) {
            environment.reportStatus(CHANNEL_STATUS, routes.joinToString(prefix = "ready:", separator = "+"))
        }
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
        private const val I_AD_VIDEO_RELATE =
            "com.bilibili.gripper.api.ad.biz.videodetail.relate.IAdVideoRelate"
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
        private const val GET_RELATE = "getRelate"
        private const val GET_UPPER_NEST_VIEW = "getUpperNestView"
        private const val GET_UPPER_AD_VIEW = "getUpperAdView"
        private const val GET_UPPER_HD_VIEW = "getUpperHDView"
        private const val GET_SCENE = "getScene"
        private const val UNITE_DETAIL = "UNITE_DETAIL"
        private const val ANDROIDX_VIEW_BINDING = "androidx.viewbinding.ViewBinding"
        private const val GEMINI_SIMPLE_VIEW_ENTRY = "com.bilibili.app.gemini.ui.UIComponent\$b"
        private const val KOTLIN_CONTINUATION = "kotlin.coroutines.Continuation"
        private const val CREATE_VIEW_ENTRY = "createViewEntry"
        private const val BIND_TO_VIEW = "bindToView"
        private const val RELATE_GAME_PACKAGE =
            "com.bilibili.ship.theseus.united.page.intro.module.relate.game"
        private const val GEMINI_UI_PACKAGE = "com.bilibili.app.gemini.ui"

        /**
         * 相关游戏推广组件的类名随混淆在单字母之间漂移，实测：8.84.0–8.96.0 为未混淆的
         * `RelateGameComponent`、8.97.0=f、8.98.0=g、8.99.0=d、9.1.0/9.1.1=i、
         * 9.2.0/9.3.0/9.5.0/9.9.0=g、9.4.0=f、9.6.0=h、9.7.0/9.10.0=e。
         *
         * 旧的 `e/d/f/RelateGameComponent` 固定名单因此在 8.98.0、9.1.0、9.1.1、9.2.0、9.3.0、
         * 9.5.0、9.6.0、9.9.0 共 8 个宿主上全程未安装，而这些位置在别的版本上又分别是 synthetic
         * lambda 或接口——名单命中与否纯属巧合，且失败完全不可观测。改为在稳定包内按有界字母表
         * 列出候选，判定完全由 [installRelateGameComponentBlock] 的结构过滤承担（继承 gemini 基类
         * + 非接口非抽象 + `(Context, LayoutInflater, ViewGroup) -> ViewBinding` +
         * `(ViewBinding, Continuation)`），并保留 `singleOrNull()`：命中不唯一即按缺失处理。
         */
        private val RELATE_GAME_COMPONENT_CLASSES: List<String> =
            ('a'..'z').map { letter -> "$RELATE_GAME_PACKAGE.$letter" } +
                "$RELATE_GAME_PACKAGE.RelateGameComponent"

        /**
         * gemini 绑定基类同样漂移（8.84.0–8.96.0=k、8.97.0–9.6.0=m、9.7.0–9.10.0=l）。
         * 这里必须保留原有的显式优先顺序：
         * 同一个包里 `UIComponent`（接口）与 `DataBindingComponent` 也满足"两个双参方法"的形状，
         * 只有靠 `isAbstract && !isInterface` 加上先命中已验证候选才不会选错基类。字母表候选只
         * 追加在已验证名单之后，用于覆盖尚未遇到过的新字母。
         */
        private val GEMINI_BINDING_COMPONENT_CLASSES: List<String> = (
            listOf("l", "m", "k", "j", "c", "d", "e") + ('a'..'z').map(Char::toString)
            ).distinct().map { letter -> "$GEMINI_UI_PACKAGE.$letter" }
    }
}

/** 广告推荐接口只允许拦截明确的 View/回调生产方法；基础类型返回值一律不碰。 */
internal fun matchesDetailRelateAdRenderMethod(method: Method): Boolean =
    method.name == "getAdRelateView" &&
        !method.isStatic &&
        !method.isAbstract &&
        method.returnType != Void.TYPE &&
        !method.returnType.isPrimitive

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
