package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.runtime

import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.view.View
import androidx.annotation.MainThread
import android.app.Activity
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.engine.GlowEngine
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.engine.GlowEngineCallbacks
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.engine.installStretchViewport
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.engine.finishStretchViewport as finishGlowStretchViewport
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidActivityRenderer
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.material.FrostedMaterialRenderer
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.SkinId
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.SurfaceRole
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.UiTokens
import com.Bilibili_Innocent_Lab.xposedmodule.ui.theme.MonetColors

/** 当前 Activity 创建皮肤令牌时使用的配置摘要，不持有 Resources 或 Context。 */
internal data class SkinConfigurationSnapshot(
    val nightMode: Int,
    val orientation: Int,
    val densityDpi: Int,
    val fontScale: Float,
    val localeTags: String
)

/** Activity 皮肤的只读诊断摘要，不向界面暴露 renderer 或 View 实例。 */
internal data class SkinSessionDiagnostics(
    val requestedSkin: SkinId,
    val effectiveSkin: SkinId,
    val fallbackReason: String?,
    val liquidBackendName: String?,
    /** 高阶后端被驱动拒绝时的有界原因；未降级为 null。 */
    val liquidBackendDegradeReason: String?
)

/**
 * 一个 Activity 的凝光视效会话：持有请求的引擎与柔光回退引擎，负责健康确认与失败回退。
 *
 * 业务调用一律转给 [activeEngine]（见 [GlowEngine]）；只有"柔光作为高级材质的休眠回退"这层
 * 关系需要会话自己管——回退引擎始终跟随生命周期与内容源，Liquid 中途失败时可以无重建地接管。
 */
internal class ActivitySkinSession private constructor(
    val requestedSkin: SkinId,
    effectiveSkin: SkinId,
    val materialPalette: MonetColors,
    val tokens: UiTokens,
    val configuration: SkinConfigurationSnapshot,
    private val activity: Activity,
    private val liquidOwner: LiquidRenderSessionOwner?,
    private val liquidRenderer: LiquidActivityRenderer?,
    private val materialRenderer: FrostedMaterialRenderer,
    private val initialLiquidFailure: Boolean
) : AutoCloseable, ModuleMemoryPressureListener {

    var effectiveSkin: SkinId = effectiveSkin
        private set

    /** 当前真正在画的引擎。Liquid 失败后 [effectiveSkin] 回落，这里随之指向柔光。 */
    private val activeEngine: GlowEngine
        get() = liquidRenderer?.takeIf { effectiveSkin == SkinId.LIQUID } ?: materialRenderer

    /**
     * 供业务层直接使用引擎能力（悬浮栏可读性 `GlowFloatingChrome`）。会话关闭后为 null；
     * 每次现取——Liquid 失败回落后指向柔光。
     */
    val engine: GlowEngine?
        get() = if (isClosed) null else activeEngine

    /** 全部引擎，Liquid 在前：生命周期通知的顺序与原实现一致。 */
    private val engines: List<GlowEngine> = listOfNotNull(liquidRenderer, materialRenderer)

    val diagnostics: SkinSessionDiagnostics
        get() = SkinSessionDiagnostics(
            requestedSkin = requestedSkin,
            effectiveSkin = effectiveSkin,
            fallbackReason = when {
                requestedSkin == SkinId.LIQUID && effectiveSkin != SkinId.LIQUID ->
                    "liquid_renderer_initialization_failed"
                else -> null
            },
            liquidBackendName = liquidBackendName,
            liquidBackendDegradeReason = liquidBackendDegradeReason
        )

    val liquidBackendName: String?
        get() = activeEngine.backendName

    val liquidBackendDegradeReason: String?
        get() = activeEngine.backendDegradeReason

    var isClosed: Boolean = false
        private set

    private var healthConfirmed = false
    private var rendererFailureHandled = initialLiquidFailure
    private var failureNotified = false
    private var failureNotificationPosted = false
    private var onFailure: (() -> Unit)? = null
    private var failureRoot: View? = null

    @MainThread
    fun bindRoot(
        root: View,
        onFailure: (() -> Unit)?
    ): Boolean {
        if (isClosed) return false
        this.onFailure = onFailure
        failureRoot = root
        if (requestedSkin != SkinId.LIQUID) return materialRenderer.bindRoot(root)
        val renderer = liquidRenderer
        if (renderer == null || effectiveSkin != SkinId.LIQUID) {
            materialRenderer.bindRoot(root)
            scheduleFailureNotification()
            return false
        }
        val bound = renderer.bindRoot(
            root = root,
            callbacks = GlowEngineCallbacks(
                onFirstVisibleDraw = ::confirmRendererHealthy,
                onFatalFailure = ::handleRendererFailure
            )
        )
        if (!bound) handleRendererFailure()
        return bound
    }

    fun surfaceBackground(
        fallbackColor: Int,
        radiusDp: Float,
        materialOutline: Boolean,
        role: SurfaceRole
    ): Drawable = if (isClosed) {
        materialRenderer.surface(fallbackColor, radiusDp, role)
    } else activeEngine.surface(fallbackColor, radiusDp, role)

    /** 回弹视口与皮肤无关（视觉拉伸由平台 EdgeEffect 完成），距离回调接到当前引擎的光学增益。 */
    @MainThread
    fun installStretchViewport(
        scrollTarget: View,
        isStretchAllowed: () -> Boolean
    ): View? {
        if (isClosed) return null
        return activeEngine.installStretchViewport(scrollTarget, isStretchAllowed)
    }

    @MainThread
    fun finishStretchViewport(view: View?) {
        if (isClosed) return
        finishGlowStretchViewport(view)
    }

    @MainThread
    fun notifyPositionChanged() {
        if (!isClosed) activeEngine.notifyPositionChanged()
    }

    @MainThread
    fun notifyScrollPositionChanged(scrollHost: View) {
        if (!isClosed) activeEngine.notifyScrollPositionChanged(scrollHost)
    }

    /**
     * 手势起止。Liquid 用它把"抑制解除"这类重同步挪出手指按着的时段——那是一次
     * 两次整组表面重录加一次全屏 PixelCopy，落在新手势头几帧上就是可感知的迟滞。
     */
    @MainThread
    fun notifyGestureActive(active: Boolean) {
        if (!isClosed) activeEngine.notifyGestureActive(active)
    }

    /**
     * 悬浮表面下方的内容层。发给**全部**引擎：Liquid 自己抓屏用不上，但作为休眠回退的柔光
     * 必须事先拿到，否则 Liquid 中途失败切过来时悬浮表面只剩静态磨砂。
     */
    @MainThread
    fun bindContentSource(view: View) {
        if (!isClosed) engines.forEach { it.bindContentSource(view) }
    }

    /** 休眠的回退引擎同样跟随生命周期：Liquid 可能在本次前台会话中途失败。 */
    @MainThread
    fun onActivityStarted() {
        if (!isClosed) engines.forEach(GlowEngine::onStart)
    }

    @MainThread
    fun onActivityStopped() {
        if (!isClosed) engines.forEach(GlowEngine::onStop)
    }

    @MainThread
    fun onTrimMemory(level: Int) {
        if (!isClosed) engines.forEach { it.onTrimMemory(level) }
    }

    @MainThread
    fun onLowMemory() {
        if (!isClosed) engines.forEach(GlowEngine::onLowMemory)
    }

    @MainThread
    override fun onReleaseGraphics() {
        onLowMemory()
    }

    @MainThread
    private fun confirmRendererHealthy() {
        if (isClosed || healthConfirmed || rendererFailureHandled) return
        val owner = liquidOwner ?: return handleRendererFailure()
        val result = SkinRepository.confirmLiquidHealthy(activity, owner)
        if (result.reason == SkinRecoveryReason.STALE_HEALTH_CONFIRMATION_IGNORED) {
            retireStaleSession()
        } else if (result.persisted && result.state.isLiquidConfirmed) {
            healthConfirmed = true
        } else {
            handleRendererFailure()
        }
    }

    @MainThread
    private fun handleRendererFailure() {
        if (isClosed || rendererFailureHandled) {
            if (initialLiquidFailure) scheduleFailureNotification()
            return
        }
        rendererFailureHandled = true
        val owner = liquidOwner
        var staleOwner = false
        if (owner != null) {
            val result = SkinRepository.reportLiquidValidationFailure(activity, owner)
            staleOwner = result.reason == SkinRecoveryReason.STALE_VALIDATION_FAILURE_IGNORED
            SkinRepository.releaseLiquidRenderSession(owner)
        }
        effectiveSkin = SkinId.MATERIAL_YOU
        liquidRenderer?.close()
        failureRoot?.let(materialRenderer::bindRoot)
        if (!staleOwner) scheduleFailureNotification()
    }

    private fun retireStaleSession() {
        rendererFailureHandled = true
        effectiveSkin = SkinId.MATERIAL_YOU
        liquidRenderer?.close()
        failureRoot?.let(materialRenderer::bindRoot)
        liquidOwner?.let(SkinRepository::releaseLiquidRenderSession)
    }

    /** 失败提示/重建始终越过当前 onCreate/draw 调用栈，不同步回调界面。 */
    private fun scheduleFailureNotification() {
        if (failureNotified || failureNotificationPosted) return
        val root = failureRoot ?: return
        failureNotificationPosted = true
        root.post {
            failureNotificationPosted = false
            if (isClosed || failureNotified) return@post
            failureNotified = true
            onFailure?.invoke()
        }
    }

    @MainThread
    override fun close() {
        if (isClosed) return
        isClosed = true
        ModuleMemoryPressureHub.removeListener(this)
        onFailure = null
        failureRoot = null
        liquidRenderer?.close()
        materialRenderer.close()
        liquidOwner?.let(SkinRepository::releaseLiquidRenderSession)
    }

    companion object {
        @MainThread
        fun create(
            activity: Activity,
            materialPalette: MonetColors
        ): ActivitySkinSession {
            val requestedSkin = SkinRepository.resolveRequestedSkin(activity)
            val configuration = activity.resources.configuration
            val owner = if (requestedSkin == SkinId.LIQUID) {
                SkinRepository.claimLiquidRenderSession(activity)
            } else null
            val renderer = if (owner != null) {
                runCatching { LiquidActivityRenderer(activity, materialPalette) }.getOrNull()
            } else null
            val initializationFailed = requestedSkin == SkinId.LIQUID && renderer == null
            if (initializationFailed && owner != null) {
                SkinRepository.reportLiquidValidationFailure(activity, owner)
                SkinRepository.releaseLiquidRenderSession(owner)
            }
            return ActivitySkinSession(
                requestedSkin = requestedSkin,
                effectiveSkin = if (renderer != null) SkinId.LIQUID else SkinId.MATERIAL_YOU,
                materialPalette = materialPalette,
                tokens = MaterialYouTokenResolver.resolve(activity, materialPalette),
                configuration = configuration.toSkinSnapshot(),
                activity = activity,
                liquidOwner = owner.takeIf { renderer != null },
                liquidRenderer = renderer,
                materialRenderer = FrostedMaterialRenderer(materialPalette, activity.resources.displayMetrics.density,
                    backgroundContext = activity.applicationContext),
                initialLiquidFailure = initializationFailed
            ).also(ModuleMemoryPressureHub::addListener)
        }
    }
}

private fun Configuration.toSkinSnapshot() = SkinConfigurationSnapshot(
    nightMode = uiMode and Configuration.UI_MODE_NIGHT_MASK,
    orientation = orientation,
    densityDpi = densityDpi,
    fontScale = fontScale,
    localeTags = locales.toLanguageTags()
)
