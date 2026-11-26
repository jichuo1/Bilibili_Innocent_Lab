package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.FollowFeedPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostVideoCardGridAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostVideoCardHostAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostVideoCardStyle
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostVideoCardStyleSpec
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostHomeCardSpacingSpec
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostOgvCardHooks
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostCardHookCoverage
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundController
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundTheme
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPages
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPageHooks

import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostCardMaterial
import android.view.View
import android.view.ViewGroup
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPreset
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.util.concurrent.atomic.AtomicBoolean

internal class HostVideoCardStyleFeatureInstaller(
    private val enabled: Boolean,
    private val radiusDp: Int = HostVideoCardStyleSpec.DEFAULT_RADIUS,
    private val backgroundConfig: HostBackgroundConfig = HostBackgroundConfig(),
    override val id: String = ID,
    private val homeSpacing: HostHomeCardSpacingSpec = HostHomeCardSpacingSpec(),
    private val material: HostCardMaterial = HostCardMaterial.ORIGINAL,
    cardSurfaces: Map<String, HookPoint> = emptyMap(),
    followFeedPoints: FollowFeedPoints? = null
) : FeatureInstaller {
    private var backgroundEnvironment: HookEnvironment? = null
    private var pageHookCount = 0
    private val installLock = Any()
    @Volatile private var currentCardSurfaces: Map<String, HookPoint> = cardSurfaces
    @Volatile private var currentFollowFeedPoints: FollowFeedPoints? = followFeedPoints
    @Volatile private var baseAttempted = false
    @Volatile private var baseInstalled = false
    @Volatile private var installedOpusBackground = false
    @Volatile private var cardCoreHooks = 0
    @Volatile private var cardGridReady = false
    @Volatile private var cardSpacingHooks = 0
    @Volatile private var cardOgvCoverage = HostCardHookCoverage.NONE
    @Volatile private var cardDividerCoverage = HostCardHookCoverage.NONE
    @Volatile private var backgroundBaseHooks = 0
    @Volatile private var backgroundThemeHooks = 0
    @Volatile private var backgroundThemeReady = true
    private val installedOgvFamilies = hashSetOf<String>()
    private val installedDividerKeys = hashSetOf<String>()
    private var cachedStyleHost: HostVideoCardHostAccess? = null
    private var cachedStyle: HostVideoCardStyle? = null
    private var cachedFirstHit: AtomicBoolean? = null

    fun updateAdaptedPoints(
        newCardSurfaces: Map<String, HookPoint>,
        newFollowPoints: FollowFeedPoints?
    ): Boolean = synchronized(installLock) {
        if (!enabled && !backgroundConfig.enabled) return false
        if (baseAttempted && !baseInstalled) return false
        var updated = false
        if (enabled && newCardSurfaces.isNotEmpty()) {
            val merged = LinkedHashMap(currentCardSurfaces)
            for ((key, point) in newCardSurfaces) {
                val alreadyInstalled = if (key == PEGASUS_DIVIDER_KEY) {
                    PEGASUS_DIVIDER_FAMILY in installedDividerKeys
                } else {
                    key in installedOgvFamilies
                }
                if (!alreadyInstalled && merged[key] != point) {
                    merged[key] = point
                    updated = true
                }
            }
            if (updated) currentCardSurfaces = merged
        }
        if (backgroundConfig.enabled && !installedOpusBackground &&
            newFollowPoints != null && newFollowPoints != currentFollowFeedPoints
        ) {
            currentFollowFeedPoints = newFollowPoints
            updated = true
        }
        updated
    }

    private fun hasPendingCardRetry(): Boolean = synchronized(installLock) {
        enabled && baseInstalled && currentCardSurfaces.any { (key, _) ->
            if (key == PEGASUS_DIVIDER_KEY) PEGASUS_DIVIDER_FAMILY !in installedDividerKeys
            else key !in installedOgvFamilies
        }
    }

    private fun hasPendingBackgroundRetry(): Boolean = synchronized(installLock) {
        backgroundConfig.enabled && baseInstalled && !installedOpusBackground && currentFollowFeedPoints != null
    }

    override fun install(environment: HookEnvironment): FeatureInstallResult = synchronized(installLock) {
        if (!enabled && !backgroundConfig.enabled && !homeSpacing.enabled) return FeatureInstallResult.Skipped("disabled")
        if (environment.processName != "tv.danmaku.bili") return FeatureInstallResult.Skipped("non-main-process")
        if (baseInstalled) {
            val cardResult = installIncrementalCardSurfaces(environment)
            installIncrementalBackgroundSurfaces(environment)
            return FeatureInstallResult.Installed(
                cardCoreHooks + pageHookCount + cardDividerCoverage.hooks + cardOgvCoverage.hooks,
                complete = (cardResult as? FeatureInstallResult.Installed)?.complete ?: false
            )
        }
        baseAttempted = true
        val loader = environment.classLoader ?: return FeatureInstallResult.Skipped("missing-classloader")
        val adapter = KavaMemberLookup.classOrNull(loader, "androidx.recyclerview.widget.RecyclerView\$Adapter")
            ?: return FeatureInstallResult.Skipped("missing-recycler-adapter")
        val holder = KavaMemberLookup.classOrNull(loader, "androidx.recyclerview.widget.RecyclerView\$ViewHolder")
            ?: return FeatureInstallResult.Skipped("missing-recycler-holder")
        val itemView = KavaMemberLookup.fieldOrNull(holder, "itemView", includeSuperclasses = true)
            ?: return FeatureInstallResult.Skipped("missing-item-view")
        val firstHit = AtomicBoolean(false)
        val firstError = AtomicBoolean(false)
        val grid = if (enabled || homeSpacing.enabled) HostVideoCardGridAccess.resolve(loader) else null
        val styleHost = HostVideoCardHostAccess(loader)
        val background = HostBackgroundController(backgroundConfig.normalized(), onError = {
            (backgroundEnvironment ?: environment).logError("host_background_unavailable", "[BIL] 宿主背景加载失败，使用柔光预设: $it")
        }, onApplied = {
            val backgroundEnv = backgroundEnvironment ?: environment
            backgroundEnv.reportRuntimeEvidence(BACKGROUND_ID, FeatureRuntimeStage.APPLIED)
            backgroundEnv.logInfo("${BACKGROUND_ID}_applied", "[BIL] 宿主页面背景已生效")
        })
        val pages = HostBackgroundPages(background, styleHost) {
            if (firstError.compareAndSet(false, true)) environment.logError("host_background_page_error", "[BIL] 宿主页面背景失败: $it")
        }
        val style = HostVideoCardStyle(grid = grid, host = styleHost, decorateCards = enabled,
            background = background.takeIf { backgroundConfig.enabled },
            homeSpacing = homeSpacing.normalized(), material = material,
            radiusDp = HostVideoCardStyleSpec.normalizeRadius(radiusDp), onApplied = {
            if ((enabled || homeSpacing.enabled) && firstHit.compareAndSet(false, true)) {
                environment.reportRuntimeEvidence(id, FeatureRuntimeStage.APPLIED)
                environment.logInfo("${id}_applied", if (enabled) "[BIL] 视频卡片大圆角、柔影和留白已生效" else "[BIL] 首页视频卡片自定义间距已生效")
            }
        }, onError = {
            if (firstError.compareAndSet(false, true)) {
                environment.logError("${id}_runtime_error", "[BIL] 视频列表美化失败，保留宿主内容: $it")
            }
        })
        cachedStyleHost = styleHost
        cachedStyle = style
        cachedFirstHit = firstHit
        return runCatching {
            val ogvHooks = if (enabled) HostOgvCardHooks.install(environment, styleHost,
                HostVideoCardStyleSpec.normalizeRadius(radiusDp), material = material,
                cachedPoints = currentCardSurfaces, skipFamilies = installedOgvFamilies.toSet(),
                onFamilyInstalled = { installedOgvFamilies += it }) {
                if (firstHit.compareAndSet(false, true)) {
                    environment.reportRuntimeEvidence(id, FeatureRuntimeStage.APPLIED)
                    environment.logInfo("${id}_applied", "[BIL] 追番/影视卡片表面已生效")
                }
            } else HostCardHookCoverage.NONE
            val spacingHooks = if (homeSpacing.enabled) HostHomeCardSpacingHooks.install(environment, style::markHome) else 0
            if (backgroundConfig.enabled) {
                val bgEnv = backgroundEnvironment ?: environment
                val basePageHooks = HostBackgroundPageHooks.install(bgEnv, pages, currentFollowFeedPoints, includeOpus = false)
                val opusHooks = HostBackgroundPageHooks.installOpusBackground(bgEnv, currentFollowFeedPoints)
                if (opusHooks > 0) installedOpusBackground = true
                pageHookCount = basePageHooks + opusHooks
            }
            val dividerHooks = if (enabled) HostVideoCardDividerHooks.install(environment, id, style::replacesSeparators,
                currentCardSurfaces[PEGASUS_DIVIDER_KEY], skipKeys = installedDividerKeys.toSet(),
                onKeyInstalled = { installedDividerKeys += it }) else HostCardHookCoverage.NONE
            var scrollHooks = 0
            if (backgroundConfig.enabled && backgroundConfig.preset != HostBackgroundPreset.CUSTOM) {
                val recycler = KavaMemberLookup.classOrNull(loader, "androidx.recyclerview.widget.RecyclerView")
                    ?: error("Missing RecyclerView for background scrolling")
                environment.registrar.exact("$id.background_scroll", recycler, "dispatchOnScrolled",
                    Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!) {
                    after {
                        if (hasThrowable) return@after
                        val list = instance as? ViewGroup ?: return@after
                        val dy = argOrNull(1) as? Int ?: 0
                        if (!pages.scroll(list, dy)) background.scroll(list, dy)
                    }
                }
                scrollHooks = 1
            }
            if (grid != null) {
                // assignSpans 已完成，直接参与宿主当前测量，避免布局后再重测整列表。
                val measure = grid.measureChild
                environment.registrar.exact("$id.measure", measure.declaringClass,
                    measure.name, *measure.parameterTypes) {
                    before {
                        val manager = instance ?: return@before
                        val root = argOrNull(0) as? View ?: return@before
                        style.prepareMeasurement(manager, root)
                    }
                }
                val linearMeasure = grid.measureLinearChild
                environment.registrar.exact("$id.linear_measure", linearMeasure.declaringClass,
                    linearMeasure.name, *linearMeasure.parameterTypes) {
                    before {
                        val manager = instance ?: return@before
                        val root = argOrNull(0) as? View ?: return@before
                        style.prepareMeasurement(manager, root)
                    }
                }
            }
            // final bindViewHolder 统一覆盖各适配器，使用宿主 ClassLoader 的类型，避免跨加载器强转。
            environment.registrar.exact("$id.bind", adapter, "bindViewHolder", holder, Int::class.javaPrimitiveType!!) {
                after {
                    if (hasThrowable) return@after
                    val bound = argOrNull(0) ?: return@after
                    val root = itemView.get(bound) as? View ?: return@after
                    style.bind(root, feedback = styleHost.isDislikeHolder(bound))
                }
            }
            baseInstalled = true
            cardCoreHooks = (if (grid != null) 3 else 1) + scrollHooks + spacingHooks
            cardGridReady = grid != null
            cardSpacingHooks = spacingHooks
            cardOgvCoverage = ogvHooks
            cardDividerCoverage = dividerHooks
            val complete = (!(enabled || homeSpacing.enabled) || grid != null) &&
                (!homeSpacing.enabled || spacingHooks > 0) && ogvHooks.complete && dividerHooks.complete
            val layers = "grid=${grid != null},spacing=$spacingHooks,ogv=${ogvHooks.label},divider=${dividerHooks.label}"
            environment.reportStatus("${ID}_layers", "$layers;ogv-families=${ogvHooks.states};divider-families=${dividerHooks.states}")
            environment.logInfo("${id}_installed", "[BIL] 视频列表美化${if (complete) "完整" else "部分"}安装（$layers）")
            FeatureInstallResult.Installed(cardCoreHooks + dividerHooks.hooks + pageHookCount + ogvHooks.hooks,
                complete = complete)
        }.getOrElse {
            environment.logError("${id}_error", "[BIL] 视频列表美化安装失败: $it")
            FeatureInstallResult.Skipped("registration-failed")
        }
    }

    private fun installIncrementalCardSurfaces(environment: HookEnvironment): FeatureInstallResult = synchronized(installLock) {
        val styleHost = cachedStyleHost ?: return FeatureInstallResult.Installed(cardCoreHooks, complete = false)
        val style = cachedStyle ?: return FeatureInstallResult.Installed(cardCoreHooks, complete = false)
        val firstHit = cachedFirstHit ?: AtomicBoolean(false)
        if (enabled) {
            val hasNewOgv = currentCardSurfaces.any { (key, _) ->
                key != PEGASUS_DIVIDER_KEY && key !in installedOgvFamilies
            }
            if (hasNewOgv) {
                val nextOgv = HostOgvCardHooks.install(
                    environment, styleHost, HostVideoCardStyleSpec.normalizeRadius(radiusDp),
                    material = material, cachedPoints = currentCardSurfaces,
                    skipFamilies = installedOgvFamilies.toSet(),
                    onFamilyInstalled = { installedOgvFamilies += it }
                ) {
                    if (firstHit.compareAndSet(false, true)) {
                        environment.reportRuntimeEvidence(id, FeatureRuntimeStage.APPLIED)
                        environment.logInfo("${id}_applied", "[BIL] 追番/影视卡片表面已生效")
                    }
                }
                cardOgvCoverage = HostCardHookCoverage(
                    hooks = cardOgvCoverage.hooks + nextOgv.hooks,
                    expected = nextOgv.expected,
                    ready = nextOgv.ready,
                    states = cardOgvCoverage.states + nextOgv.states
                )
            }
            val hasNewDivider = PEGASUS_DIVIDER_FAMILY !in installedDividerKeys &&
                currentCardSurfaces[PEGASUS_DIVIDER_KEY] != null
            if (hasNewDivider) {
                val nextDivider = HostVideoCardDividerHooks.install(
                    environment, id, style::replacesSeparators,
                    currentCardSurfaces[PEGASUS_DIVIDER_KEY],
                    skipKeys = installedDividerKeys.toSet(),
                    onKeyInstalled = { installedDividerKeys += it }
                )
                cardDividerCoverage = HostCardHookCoverage(
                    hooks = cardDividerCoverage.hooks + nextDivider.hooks,
                    expected = nextDivider.expected,
                    ready = nextDivider.ready,
                    states = cardDividerCoverage.states + nextDivider.states
                )
            }
        }
        val complete = (!(enabled || homeSpacing.enabled) || cardGridReady) &&
            (!homeSpacing.enabled || cardSpacingHooks > 0) &&
            cardOgvCoverage.complete && cardDividerCoverage.complete
        FeatureInstallResult.Installed(
            cardCoreHooks + pageHookCount + cardDividerCoverage.hooks + cardOgvCoverage.hooks,
            complete = complete
        )
    }

    private fun installIncrementalBackgroundSurfaces(environment: HookEnvironment): FeatureInstallResult = synchronized(installLock) {
        if (backgroundConfig.enabled && !installedOpusBackground && currentFollowFeedPoints != null) {
            val opusHooks = HostBackgroundPageHooks.installOpusBackground(
                backgroundEnvironment ?: environment, currentFollowFeedPoints
            )
            if (opusHooks > 0) {
                installedOpusBackground = true
                pageHookCount += opusHooks
            }
        }
        FeatureInstallResult.Installed(
            backgroundBaseHooks + backgroundThemeHooks + pageHookCount,
            complete = backgroundThemeReady
        )
    }

    private class SharedSurfaceInstaller(
        override val id: String,
        val delegate: HostVideoCardStyleFeatureInstaller,
        private val action: (HookEnvironment) -> FeatureInstallResult
    ) : FeatureInstaller {
        override fun install(environment: HookEnvironment): FeatureInstallResult = action(environment)
    }

    companion object {
        const val ID = "host_video_cards"
        const val BACKGROUND_ID = "host_background"
        private const val PEGASUS_DIVIDER_KEY = "pegasus_divider"
        private const val PEGASUS_DIVIDER_FAMILY = "pegasus"
        private val STATIC_DIVIDER_KEYS = setOf("native", "feed", "playlist_under", "playlist_over", "playset", "androidx")

        /** 两项诊断仍独立，实际 bind Hook、卡片识别、主题读取和列表监听只安装一套。 */
        fun shared(enabled: Boolean, radiusDp: Int, backgroundConfig: HostBackgroundConfig,
            homeSpacing: HostHomeCardSpacingSpec = HostHomeCardSpacingSpec(), material: HostCardMaterial = HostCardMaterial.ORIGINAL,
            cardSurfaces: Map<String, HookPoint> = emptyMap(), followFeedPoints: FollowFeedPoints? = null): List<FeatureInstaller> {
            val delegate = HostVideoCardStyleFeatureInstaller(enabled, radiusDp, backgroundConfig,
                id = if (enabled || homeSpacing.enabled) ID else BACKGROUND_ID, homeSpacing = homeSpacing.normalized(),
                material = if (enabled) material else HostCardMaterial.ORIGINAL, cardSurfaces = cardSurfaces,
                followFeedPoints = followFeedPoints)
            var result: FeatureInstallResult? = null
            var cardInitialDone = false
            var backgroundInitialDone = false
            var themeInstalled = false
            var themeReady = !backgroundConfig.preset.isCelestial
            fun installBase(environment: HookEnvironment): FeatureInstallResult =
                result ?: delegate.install(environment).also { result = it }
            return listOf(
                SharedSurfaceInstaller(ID, delegate) { environment ->
                    when {
                        !enabled && !homeSpacing.enabled -> FeatureInstallResult.Skipped("disabled")
                        !cardInitialDone -> {
                            cardInitialDone = true
                            installBase(environment)
                        }
                        else -> delegate.installIncrementalCardSurfaces(environment)
                    }
                },
                SharedSurfaceInstaller(BACKGROUND_ID, delegate) { environment ->
                    delegate.backgroundEnvironment = environment
                    if (!backgroundConfig.enabled) FeatureInstallResult.Skipped("disabled")
                    else if (backgroundInitialDone) {
                        delegate.installIncrementalBackgroundSurfaces(environment)
                    } else {
                        backgroundInitialDone = true
                        val themeHooks = if (!themeInstalled) {
                            themeInstalled = true
                            HostBackgroundTheme.install(environment, backgroundConfig.preset).also { hooks ->
                                themeReady = !backgroundConfig.preset.isCelestial || hooks == 2
                            }
                        } else 0
                        delegate.backgroundThemeHooks = themeHooks
                        delegate.backgroundThemeReady = themeReady
                        when (val shared = installBase(environment)) {
                            // 背景覆盖同一个 bind Hook；不依赖卡片的 GridLayoutManager 测量点。
                            is FeatureInstallResult.Installed -> {
                                val baseHooks = if (backgroundConfig.preset == HostBackgroundPreset.CUSTOM) 1 else 2
                                delegate.backgroundBaseHooks = baseHooks
                                FeatureInstallResult.Installed(
                                    baseHooks + themeHooks + delegate.pageHookCount,
                                    complete = themeReady
                                )
                            }
                            is FeatureInstallResult.Skipped -> if ((enabled || homeSpacing.enabled) && shared.reason == "registration-failed") {
                                // 卡片测量点失败时，背景仍可独立使用 bind；保持原有故障隔离。
                                when (val fallback = HostVideoCardStyleFeatureInstaller(false, radiusDp, backgroundConfig, BACKGROUND_ID,
                                    followFeedPoints = followFeedPoints).install(environment)) {
                                    is FeatureInstallResult.Installed -> fallback.copy(hookCount = fallback.hookCount + themeHooks, complete = themeReady)
                                    else -> fallback
                                }
                            } else if (shared.reason == "non-main-process" && themeHooks > 0) FeatureInstallResult.Installed(themeHooks)
                            else shared
                            else -> shared
                        }
                    }
                }
            )
        }

        internal fun updateAdaptedPoints(
            installers: List<FeatureInstaller>,
            newCardSurfaces: Map<String, HookPoint>,
            newFollowPoints: FollowFeedPoints?
        ): List<FeatureInstaller> {
            val sharedInstallers = installers.filterIsInstance<SharedSurfaceInstaller>()
            val delegate = sharedInstallers.firstOrNull()?.delegate ?: return emptyList()
            if (!delegate.updateAdaptedPoints(newCardSurfaces, newFollowPoints)) return emptyList()
            return buildList {
                if (delegate.hasPendingCardRetry()) {
                    sharedInstallers.firstOrNull { it.id == ID }?.let(::add)
                }
                if (delegate.hasPendingBackgroundRetry()) {
                    sharedInstallers.firstOrNull { it.id == BACKGROUND_ID }?.let(::add)
                }
            }
        }
    }
}
