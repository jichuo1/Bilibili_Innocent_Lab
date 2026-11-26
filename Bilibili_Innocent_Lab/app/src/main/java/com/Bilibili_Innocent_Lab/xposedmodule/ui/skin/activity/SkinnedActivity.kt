package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.activity

import android.app.Dialog
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.graphics.ColorUtils
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.ui.interaction.ElasticInteractionController
import com.Bilibili_Innocent_Lab.xposedmodule.ui.theme.ModernPalette
import com.Bilibili_Innocent_Lab.xposedmodule.ui.theme.MonetColors
import com.Bilibili_Innocent_Lab.xposedmodule.ui.widget.GithubUpdateBadgeDrawable
import com.lumen.coacervation.engine.glow.GlowEngine
import com.lumen.coacervation.engine.host.LumenActivityDelegate
import com.lumen.coacervation.engine.liquid.LiquidChoiceDrawable
import com.lumen.coacervation.engine.model.LumenPalette
import com.lumen.coacervation.engine.model.SurfaceRole
import com.lumen.coacervation.engine.runtime.SkinSessionDiagnostics
import io.github.jichuo1.meguviz.android.runtime.core.Registration
import io.github.jichuo1.meguviz.android.window.InsetsSnapshot
import io.github.jichuo1.meguviz.android.window.WindowCallbackLease
import io.github.jichuo1.meguviz.android.window.WindowSession

/**
 * 只管理 Activity 级皮肤会话、模块窗口策略与交互边界的薄基类，皮肤/视效全部委托给 Lumen [LumenActivityDelegate]。
 *
 * onCreate、语言、授权和重建时机由业务 Activity 决定；原父类的窗口初始化由 [BilWindowDelegate] 保留。
 */
abstract class SkinnedActivity : AppCompatActivity() {

    private var windowDelegate: BilWindowDelegate? = null
    private var windowSession: WindowSession? = null
    private var windowStarted = false
    private var lifecycleEnded = false

    private val lumen: LumenActivityDelegate by lazy(LazyThreadSafetyMode.NONE) {
        LumenActivityDelegate(this, ::toLumenPalette)
    }
    private var materialPaletteOrNull: MonetColors? = null
    private var elasticInteraction: ElasticInteractionController? = null

    private class DialogWindowHost(
        val window: Window,
        val session: WindowSession,
        val detachTarget: View,
        val detachListener: View.OnAttachStateChangeListener
    ) {
        var controller: ElasticInteractionController? = null
        var callbackLease: WindowCallbackLease? = null
        var released = false
    }

    private val dialogWindows = linkedMapOf<Window, DialogWindowHost>()

    internal fun moduleWindowSession(): WindowSession {
        check(!lifecycleEnded) { "The Activity window has already been destroyed" }
        return windowSession ?: WindowSession.open(window, this, packageName, WindowSession.Permission.OWN_WINDOW)
            .also {
                windowSession = it
                if (windowStarted) it.setStarted(true)
            }
    }

    private fun bindModuleWindow(root: View?) {
        if (windowDelegate != null || lifecycleEnded) return
        val content = root ?: findViewById<ViewGroup>(android.R.id.content)
        windowDelegate = BilWindowDelegate(this, content, moduleWindowSession())
    }

    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        bindModuleWindow(findViewById<ViewGroup>(android.R.id.content).getChildAt(0))
    }

    override fun setContentView(view: View?) {
        super.setContentView(view)
        bindModuleWindow(view)
    }

    override fun setContentView(view: View?, params: ViewGroup.LayoutParams?) {
        super.setContentView(view, params)
        bindModuleWindow(view)
    }

    internal fun observeModuleInsets(root: View, observer: (InsetsSnapshot) -> Unit): Registration? =
        windowDelegate?.observeContentInsets(root, observer)

    /** Returns the shared `OWN_WINDOW` [WindowSession] for [dialog], creating it on first use. */
    internal fun dialogWindowSession(dialog: Dialog): WindowSession? {
        if (lifecycleEnded) return null
        val targetWindow = dialog.window ?: return null
        dialogWindows[targetWindow]?.let { return it.session }
        val session = WindowSession.open(targetWindow, dialog.context, packageName, WindowSession.Permission.OWN_WINDOW)
            .apply { setStarted(windowStarted) }
        val decor = targetWindow.decorView
        lateinit var host: DialogWindowHost
        val detachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) { releaseDialogHost(host) }
        }
        host = DialogWindowHost(targetWindow, session, decor, detachListener)
        decor.addOnAttachStateChangeListener(detachListener)
        dialogWindows[targetWindow] = host
        return session
    }

    private fun releaseDialogHost(host: DialogWindowHost) {
        if (host.released) return
        host.released = true
        host.detachTarget.removeOnAttachStateChangeListener(host.detachListener)
        runCatching { host.callbackLease?.close() }
        host.callbackLease = null
        runCatching { host.controller?.dispose() }
        host.controller = null
        runCatching { host.session.close() }
        dialogWindows.remove(host.window)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        windowSession?.refreshConfiguration()
    }

    override fun setTheme(resId: Int) {
        super.setTheme(resId)
        windowSession?.resources()?.invalidateTheme()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (lifecycleEnded) return super.dispatchTouchEvent(event)
        lumen.onDispatchTouchEvent(event)
        val controller = elasticInteraction ?: ElasticInteractionController(
            root = window.decorView,
            notifyPositionChanged = { notifyPreparedSkinPositionChanged() },
            highlightColor = monetColors.primary
        ).also { elasticInteraction = it }
        controller.travelPolicy = com.lumen.coacervation.engine.interaction.ElasticTravelPolicy.PARENT_BOUNDS
        return controller.dispatch(event) { original -> super.dispatchTouchEvent(original) }
    }

    /** Only restores visual ownership; original click/checked listeners stay authoritative. */
    protected fun clearElasticInteractions() {
        elasticInteraction?.clear()
        dialogWindows.values.forEach { it.controller?.clear() }
    }

    /** Installed after setContentView; caller releases it from its existing dismiss listener. */
    internal fun installDialogElasticInteraction(dialog: Dialog): () -> Unit {
        val targetWindow = dialog.window ?: return {}
        val session = dialogWindowSession(dialog) ?: return {}
        val host = dialogWindows[targetWindow] ?: return {}
        if (host.controller != null) return { releaseDialogHost(host) }
        if (targetWindow.callback == null) return { releaseDialogHost(host) }
        val controller = ElasticInteractionController(
            root = targetWindow.decorView,
            notifyPositionChanged = { notifyPreparedSkinPositionChanged() },
            highlightColor = monetColors.primary
        )
        controller.travelPolicy = com.lumen.coacervation.engine.interaction.ElasticTravelPolicy.PARENT_BOUNDS
        val lease = WindowCallbackLease.install(session) { original ->
            object : Window.Callback by original {
                override fun dispatchTouchEvent(event: MotionEvent): Boolean =
                    controller.dispatch(event) { forwarded -> original.dispatchTouchEvent(forwarded) }
            }
        }
        host.controller = controller
        host.callbackLease = lease
        return { releaseDialogHost(host) }
    }

    /**
     * Module-only palette: wallpaper accents with neutral modern window and surface colors.
     *
     * Does not start a renderer; consent and fallback windows use the same palette.
     */
    internal val monetColors: MonetColors
        get() = materialPaletteOrNull
            ?: ModernPalette.resolve(this).also { materialPaletteOrNull = it }

    internal fun toLumenPalette(): LumenPalette = monetColors.let { c ->
        LumenPalette(
            primary = c.primary,
            onPrimary = c.onPrimary,
            secondary = c.secondary,
            tertiary = c.tertiary,
            surface = c.surface,
            background = c.background,
            surfaceVariant = c.surfaceVariant,
            textPrimary = getColor(R.color.colorTextGray),
            textSecondary = getColor(R.color.colorTextDark)
        )
    }

    /** 条款授权后的皮肤装配点；未授权分支不得调用。 */
    @MainThread
    protected fun prepareSkinSession() {
        if (!lifecycleEnded) lumen.prepare()
    }

    /**
     * 把已准备的 Liquid 会话绑定到 Activity 的可见根 View。
     *
     * 未 prepare 或 Activity 已结束返回 false。回调只表示完整 Liquid renderer 失败并已请求回退。
     */
    @MainThread
    protected fun bindPreparedSkinRoot(
        root: View,
        onFailure: (() -> Unit)? = null
    ): Boolean {
        if (lifecycleEnded) return false
        stylePreparedSkinControls(root)
        return lumen.bindRoot(root, onFailure)
    }

    /** One construction-time pass. No hierarchy listener, polling or preference reads. */
    protected fun stylePreparedSkinControls(root: View) {
        if (!lumen.isPrepared || lifecycleEnded) return
        val density = resources.displayMetrics.density
        fun choice(width: Int, height: Int, checkbox: Boolean = false, thumb: Boolean = false) =
            LiquidChoiceDrawable(
                width, height, density, monetColors.surface, monetColors.primary,
                monetColors.onPrimary, getColor(R.color.colorTextGray), checkbox, thumb
            )
        fun visit(view: View) {
            when (view) {
                is SwitchCompat -> {
                    val width = (view.thumbDrawable?.intrinsicWidth ?: 0).coerceAtLeast((20 * density).toInt())
                    val height = (view.thumbDrawable?.intrinsicHeight ?: 0).coerceAtLeast((20 * density).toInt())
                    view.thumbTintList = null
                    view.trackTintList = null
                    view.thumbDrawable = choice(width, height, thumb = true)
                    view.trackDrawable = choice(width * 2, height)
                    view.splitTrack = false
                    view.refreshDrawableState()
                }
                is CheckBox -> {
                    val size = (view.buttonDrawable?.intrinsicWidth ?: 0)
                        .coerceIn((20 * density).toInt(), (26 * density).toInt())
                    view.buttonTintList = null
                    view.buttonDrawable = choice(size, size, checkbox = true)
                    view.refreshDrawableState()
                    view.background = null
                }
                is EditText -> {
                    view.backgroundTintList = null
                    replaceControlBackground(view, lumen.surface(monetColors.surfaceVariant, 14f, SurfaceRole.SELECTED_ITEM))
                    view.foreground = lumen.controlOutline(14f)
                }
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        visit(root)
    }

    /** Shared modern controls; unprepared consent paths stay free of renderer ownership. */
    internal fun skinActionButton(view: TextView, filled: Boolean, radiusDp: Float = 20f) =
        lumen.styleActionButton(view, filled, radiusDp)

    protected val skinEmphasisTextColor: Int
        get() = getColor(R.color.colorTextDark)

    protected fun skinSelectionControl(view: View, radiusDp: Float, selected: Boolean) =
        lumen.styleSelectionControl(view, radiusDp, selected)

    protected fun skinUpdateBadge(view: TextView) {
        if (!lumen.isPrepared || lifecycleEnded) return
        view.setTextColor(getColor(R.color.colorTextDark))
        view.background = GithubUpdateBadgeDrawable(
            ColorUtils.setAlphaComponent(monetColors.surface, 220),
            resources.displayMetrics.density,
            ColorUtils.setAlphaComponent(monetColors.primary, 210)
        )
    }

    protected fun skinStatusChip(view: TextView, accent: Int, radiusDp: Float = 9f) =
        lumen.styleStatusChip(view, accent, radiusDp)

    private fun replaceControlBackground(view: View, drawable: Drawable) {
        val left = view.paddingLeft; val top = view.paddingTop
        val right = view.paddingRight; val bottom = view.paddingBottom
        view.background = drawable
        view.setPadding(left, top, right, bottom)
    }

    @MainThread
    protected fun installPreparedLiquidStretch(
        scrollTarget: View,
        isStretchAllowed: () -> Boolean = { true }
    ): View? = lumen.installStretch(scrollTarget, isStretchAllowed)

    @MainThread
    protected fun finishPreparedLiquidStretch(view: View?) = lumen.finishStretch(view)

    /** Translation moves retained display lists without firing a scroll callback. */
    @MainThread
    protected fun notifyPreparedSkinPositionChanged() = lumen.notifyPositionChanged()

    @MainThread
    protected fun notifyPreparedSkinScrollPositionChanged(scrollHost: View) =
        lumen.notifyScrollPositionChanged(scrollHost)

    @MainThread
    protected fun bindPreparedSkinScrollHost(scrollHost: View) {
        scrollHost.setOnScrollChangeListener(View.OnScrollChangeListener { view, x, y, oldX, oldY ->
            if (x != oldX || y != oldY) notifyPreparedSkinScrollPositionChanged(view)
        })
    }

    /** 悬浮胶囊下方的内容层（必须是胶囊的兄弟而非祖先），供模糊/折射采样。 */
    @MainThread
    protected fun bindPreparedSkinContentSource(view: View) = lumen.bindContentSource(view)

    internal val glowEngine: GlowEngine?
        get() = if (lifecycleEnded) null else lumen.engine

    internal val isLiquidSkinRequested: Boolean
        get() = lumen.isLiquidRequested

    protected val isLiquidSkinEffective: Boolean
        get() = lumen.isLiquidEffective

    protected val isMaterialYouSkinEffective: Boolean
        get() = !lumen.isLiquidEffective

    internal val liquidBackendName: String?
        get() = lumen.backendName

    internal fun currentSkinDiagnostics(): SkinSessionDiagnostics? = lumen.diagnostics()

    internal fun skinCardBackground(color: Int, radiusDp: Float = 15f): Drawable =
        lumen.surface(color, radiusDp, SurfaceRole.CARD)

    /** Safe before consent: palette only; no bitmap work or Liquid session ownership. */
    internal fun neutralWindowBackground(): Drawable = lumen.neutralWindowBackground()

    internal fun skinFloatingBackground(color: Int, radiusDp: Float = 28f): Drawable =
        lumen.surface(color, radiusDp, SurfaceRole.FLOATING)

    internal fun skinTopBarBackground(color: Int, radiusDp: Float = 0f): Drawable =
        lumen.surface(color, radiusDp, SurfaceRole.TOP_BAR)

    internal fun skinSelectionBackground(color: Int, radiusDp: Float = 22f): Drawable =
        lumen.surface(color, radiusDp, SurfaceRole.SELECTED_ITEM)

    internal fun skinChromeOverlayBackground(color: Int, radiusDp: Float, selected: Boolean = false): Drawable =
        lumen.chromeOverlayBackground(color, radiusDp, selected)

    protected fun skinModalBackground(color: Int, radiusDp: Float = 28f): Drawable =
        lumen.surface(color, radiusDp, SurfaceRole.MODAL)

    protected fun skinMotionSurfaceBackground(color: Int, radiusDp: Float): Drawable =
        lumen.surface(color, radiusDp, SurfaceRole.MOTION_SURFACE)

    protected fun liquidMotionSurfaceBackgroundOrNull(color: Int, radiusDp: Float): Drawable? =
        lumen.liquidMotionSurfaceBackgroundOrNull(color, radiusDp)

    override fun onStart() {
        super.onStart()
        windowStarted = true
        windowSession?.setStarted(true)
        lumen.onStart()
    }

    override fun onStop() {
        windowStarted = false
        windowSession?.setStarted(false)
        clearElasticInteractions()
        lumen.onStop()
        super.onStop()
    }

    override fun onPause() {
        clearElasticInteractions()
        super.onPause()
    }

    override fun onTrimMemory(level: Int) {
        lumen.onTrimMemory(level)
        super.onTrimMemory(level)
    }

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() {
        lumen.onLowMemory()
        @Suppress("DEPRECATION")
        super.onLowMemory()
    }

    override fun onDestroy() {
        lifecycleEnded = true
        elasticInteraction?.dispose()
        elasticInteraction = null
        dialogWindows.values.toList().forEach(::releaseDialogHost)
        try {
            lumen.onDestroy()
        } finally {
            val windowOwner = windowSession
            windowSession = null
            val delegate = windowDelegate
            windowDelegate = null
            try {
                windowOwner?.close()
            } finally {
                try {
                    delegate?.close()
                } finally {
                    super.onDestroy()
                }
            }
        }
    }
}
