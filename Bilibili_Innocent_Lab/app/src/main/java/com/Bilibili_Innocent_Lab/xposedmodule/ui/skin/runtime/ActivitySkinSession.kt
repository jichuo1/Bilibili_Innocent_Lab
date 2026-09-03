package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.runtime

import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import androidx.annotation.MainThread
import com.highcapable.betterandroid.ui.component.activity.AppViewsActivity
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidActivityRenderer
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
 * Activity 级皮肤会话：Material You 只提供既有令牌，Liquid 额外持有有界 renderer 生命周期。
 */
internal class ActivitySkinSession private constructor(
    val requestedSkin: SkinId,
    effectiveSkin: SkinId,
    val materialPalette: MonetColors,
    val tokens: UiTokens,
    val configuration: SkinConfigurationSnapshot,
    private val activity: AppViewsActivity,
    private val liquidOwner: LiquidRenderSessionOwner?,
    private val liquidRenderer: LiquidActivityRenderer?,
    private val initialLiquidFailure: Boolean
) : AutoCloseable {

    var effectiveSkin: SkinId = effectiveSkin
        private set

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
        get() = liquidRenderer?.backend?.name.takeIf { effectiveSkin == SkinId.LIQUID }

    val liquidBackendDegradeReason: String?
        get() = liquidRenderer?.backendDegradeReason.takeIf { effectiveSkin == SkinId.LIQUID }

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
        if (requestedSkin != SkinId.LIQUID) return true
        val renderer = liquidRenderer
        if (renderer == null || effectiveSkin != SkinId.LIQUID) {
            scheduleFailureNotification()
            return false
        }
        val bound = renderer.bindRoot(
            root = root,
            onFirstVisibleDraw = ::confirmRendererHealthy,
            onFatalFailure = ::handleRendererFailure
        )
        if (!bound) handleRendererFailure()
        return bound
    }

    fun surfaceBackground(
        fallbackColor: Int,
        radiusDp: Float,
        materialOutline: Boolean,
        role: SurfaceRole
    ): Drawable = if (!isClosed && effectiveSkin == SkinId.LIQUID) {
        liquidRenderer?.createSurfaceDrawable(fallbackColor, radiusDp, role)
            ?: materialBackground(
                fallbackColor,
                radiusDp * activity.resources.displayMetrics.density,
                materialOutline,
                activity.resources.displayMetrics.density
            )
    } else materialBackground(
        fallbackColor,
        radiusDp * activity.resources.displayMetrics.density,
        materialOutline,
        activity.resources.displayMetrics.density
    )

    @MainThread
    fun installStretchViewport(
        scrollTarget: View,
        isStretchAllowed: () -> Boolean
    ): View? = if (!isClosed && effectiveSkin == SkinId.LIQUID) {
        liquidRenderer?.installStretchViewport(
            scrollTarget = scrollTarget,
            isStretchAllowed = isStretchAllowed
        )
    } else null

    @MainThread
    fun finishStretchViewport(view: View?) {
        if (!isClosed) liquidRenderer?.finishStretchViewport(view)
    }

    @MainThread
    fun onActivityStarted() {
        if (!isClosed) liquidRenderer?.onActivityStarted()
    }

    @MainThread
    fun onActivityStopped() {
        if (!isClosed) liquidRenderer?.onActivityStopped()
    }

    @MainThread
    fun onTrimMemory(level: Int) {
        if (!isClosed) liquidRenderer?.onTrimMemory(level)
    }

    @MainThread
    fun onLowMemory() {
        if (!isClosed) liquidRenderer?.onLowMemory()
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
        if (!staleOwner) scheduleFailureNotification()
    }

    private fun retireStaleSession() {
        rendererFailureHandled = true
        effectiveSkin = SkinId.MATERIAL_YOU
        liquidRenderer?.close()
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
        onFailure = null
        failureRoot = null
        liquidRenderer?.close()
        liquidOwner?.let(SkinRepository::releaseLiquidRenderSession)
    }

    companion object {
        @MainThread
        fun create(
            activity: AppViewsActivity,
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
                initialLiquidFailure = initializationFailed
            )
        }
    }
}

private fun materialBackground(
    color: Int,
    radiusPx: Float,
    withOutline: Boolean,
    density: Float
) = GradientDrawable().apply {
    cornerRadius = radiusPx.coerceAtLeast(0f)
    setColor(color)
    if (withOutline) {
        setStroke(
            density.toInt().coerceAtLeast(1),
            androidx.core.graphics.ColorUtils.setAlphaComponent(android.graphics.Color.WHITE, 0x18)
        )
    }
}

private fun Configuration.toSkinSnapshot() = SkinConfigurationSnapshot(
    nightMode = uiMode and Configuration.UI_MODE_NIGHT_MASK,
    orientation = orientation,
    densityDpi = densityDpi,
    fontScale = fontScale,
    localeTags = locales.toLanguageTags()
)
