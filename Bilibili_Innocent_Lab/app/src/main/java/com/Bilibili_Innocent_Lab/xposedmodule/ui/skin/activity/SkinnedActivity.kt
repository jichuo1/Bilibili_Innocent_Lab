package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.activity

import android.app.Dialog
import android.view.MotionEvent
import android.view.Window
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import androidx.appcompat.widget.SwitchCompat
import androidx.core.graphics.ColorUtils
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidChoiceDrawable
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatActivity
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.engine.GlowEngine
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.runtime.ActivitySkinSession
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.runtime.SkinSessionDiagnostics
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.SkinId
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.SurfaceRole
import com.Bilibili_Innocent_Lab.xposedmodule.ui.theme.MonetColors
import com.Bilibili_Innocent_Lab.xposedmodule.ui.interaction.ElasticInteractionController
import com.Bilibili_Innocent_Lab.xposedmodule.ui.theme.ModernPalette
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.material.ModernMaterialDrawables

/**
 * 只管理 Activity 级皮肤会话的薄基类。
 *
 * onCreate、语言、授权和重建时机由业务 Activity 决定；原父类的窗口初始化由组合委托保留。
 */
abstract class SkinnedActivity : AppCompatActivity() {

    private var windowDelegate: BilWindowDelegate? = null
    private var windowStarted = false

    private fun bindModuleWindow(root: View?) {
        if (windowDelegate != null || lifecycleEnded) return
        val content = root ?: findViewById<ViewGroup>(android.R.id.content)
        windowDelegate = BilWindowDelegate(this, content).also { it.setStarted(windowStarted) }
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

    internal fun handOffModuleInsets(root: View) { windowDelegate?.handOffInsets(root) }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        windowDelegate?.refreshConfiguration()
    }

    override fun setTheme(resId: Int) {
        super.setTheme(resId)
        windowDelegate?.invalidateTheme()
    }

    private var skinSessionOrNull: ActivitySkinSession? = null
    private var materialPaletteOrNull: MonetColors? = null
    private var lifecycleEnded = false
    private var elasticInteraction: ElasticInteractionController? = null
    private data class DialogInteraction(val controller: ElasticInteractionController, val release: () -> Unit)
    private val dialogInteractions = linkedMapOf<Window, DialogInteraction>()

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (lifecycleEnded) return super.dispatchTouchEvent(event)
        // 手势起止转给皮肤会话：抑制解除是一次"两次整组表面重录 + 一次全屏 PixelCopy"的
        // 重同步，落在新手势的头几帧上就是可感知的迟滞（回弹刚结束立刻反向滑最容易撞上）。
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> skinSessionOrNull?.notifyGestureActive(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                skinSessionOrNull?.notifyGestureActive(false)
        }
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
        dialogInteractions.values.forEach { it.controller.clear() }
    }

    /** Installed after setContentView; caller releases it from its existing dismiss listener. */
    internal fun installDialogElasticInteraction(dialog: Dialog): () -> Unit {
        val window = dialog.window ?: return {}
        dialogInteractions[window]?.let { return it.release }
        val original = window.callback ?: return {}
        val controller = ElasticInteractionController(window.decorView,
            notifyPositionChanged = { notifyPreparedSkinPositionChanged() },
            highlightColor = monetColors.primary)
        controller.travelPolicy = com.lumen.coacervation.engine.interaction.ElasticTravelPolicy.PARENT_BOUNDS
        val callback = object : Window.Callback by original {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean =
                controller.dispatch(event) { forwarded -> original.dispatchTouchEvent(forwarded) }
        }
        var released = false
        var detachListener: View.OnAttachStateChangeListener? = null
        val release = {
            if (!released) {
                released = true
                detachListener?.let { window.decorView.removeOnAttachStateChangeListener(it) }
                controller.dispose()
                if (window.callback === callback) window.callback = original
                dialogInteractions.remove(window)
            }
            Unit
        }
        // DecorView detaches when the dialog window is dismissed; release without
        // waiting for a caller-side dismiss listener so bulk installs stay leak-free.
        detachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) { release() }
        }.also(window.decorView::addOnAttachStateChangeListener)
        dialogInteractions[window] = DialogInteraction(controller, release)
        window.callback = callback
        return release
    }

    /**
     * Module-only palette: wallpaper accents with neutral modern window and surface colors.
     *
     * Does not read SkinPrefs or start a renderer; consent and fallback windows use the same palette.
     */
    // internal 而非 protected：设置页的弹窗正按主题外移成 `MainActivity` 的扩展函数，
    // 而 Kotlin 的扩展函数**拿不到 protected 成员**（protected 只对子类体内可见）。
    // internal 仍然限制在本模块内，不进入任何对外 API；调色板的来源约束不变
    // （见 docs/architecture.md：与皮肤仓库无关，仍是 Activity 作用域的 fromWallpaper）。
    // 只放宽外移代码真正需要的三个：monetColors / skinActionButton / skinCardBackground。
    // stylePreparedSkinControls 等仍是 protected——它们只被留在 Activity 里的底座调用。
    internal val monetColors: MonetColors
        get() = materialPaletteOrNull
            ?: ModernPalette.resolve(this).also { materialPaletteOrNull = it }

    /** 条款授权后的皮肤装配点；未授权分支不得调用。 */
    @MainThread
    protected fun prepareSkinSession() {
        if (lifecycleEnded || skinSessionOrNull != null) return
        skinSessionOrNull = ActivitySkinSession.create(this, monetColors)
    }

    /**
     * 把已准备的 Liquid 会话绑定到 MainActivity 的可见根 View。
     *
     * Soft surfaces share an asynchronously prepared static backdrop; Liquid retains its optical renderer.
     * 未 prepare 或 Activity 已结束返回 false。回调只表示完整
     * Liquid renderer 失败并已请求回退，不会把 BLUR/TRANSLUCENT 的正常降级误报为失败。
     */
    @MainThread
    protected fun bindPreparedSkinRoot(
        root: View,
        onFailure: (() -> Unit)? = null
    ): Boolean {
        if (lifecycleEnded) return false
        stylePreparedSkinControls(root)
        return skinSessionOrNull?.bindRoot(root, onFailure) ?: false
    }

    /** One construction-time pass. No hierarchy listener, polling or preference reads. */
    protected fun stylePreparedSkinControls(root: View) {
        if (skinSessionOrNull == null || lifecycleEnded) return
        val density = resources.displayMetrics.density
        fun choice(width: Int, height: Int, checkbox: Boolean = false, thumb: Boolean = false) =
            LiquidChoiceDrawable(width, height, density, monetColors.surface, monetColors.primary,
                monetColors.onPrimary, getColor(R.color.colorTextGray), checkbox, thumb)
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
                    // 新装的 drawable 起始状态是空的：`setThumbDrawable`/`setTrackDrawable`
                    // 都不会把 View 当前状态推给它，框架只在下一次 drawableStateChanged()
                    // 时推。冷启动时窗口获焦会补上那一次，而换皮肤走 recreate()——新视图
                    // 在**已获焦**的窗口里挂载，state_window_focused 没有变化，于是补不上：
                    // 已开启的开关滑块在右边、配色却停在未选中的灰（2026-09-22 真机实证）。
                    view.refreshDrawableState()
                }
                is CheckBox -> {
                    // 框架默认按钮在各 ROM 上尺寸发散（本机 ~32dp），钳到一个小区间：
                    // 控件实际盒体 = size − 2×inset，落回约 20dp 的紧凑尺度。
                    val size = (view.buttonDrawable?.intrinsicWidth ?: 0)
                        .coerceIn((20 * density).toInt(), (26 * density).toInt())
                    view.buttonTintList = null
                    view.buttonDrawable = choice(size, size, checkbox = true)
                    // 与开关同理：新 drawable 必须立刻拿到当前状态，否则已勾选的复选框
                    // 会画成未勾选配色。
                    view.refreshDrawableState()
                    // 框架默认 background 是 40dp 的按压涟漪（control_background_40dp_material），
                    // 勾选时会在按钮周围开一块浅色遮罩——皮肤接管视觉后这块就是杂讯，去掉。
                    view.background = null
                }
                is EditText -> {
                    view.backgroundTintList = null
                    replaceControlBackground(view, skinBackground(monetColors.surfaceVariant, 14f,
                        materialOutline = false, role = SurfaceRole.SELECTED_ITEM))
                    view.foreground = controlOutline(14f)
                }
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        visit(root)
    }

    /** Shared modern controls; unprepared consent paths stay free of renderer ownership. */
    internal fun skinActionButton(view: TextView, filled: Boolean, radiusDp: Float = 20f) {
        if (skinSessionOrNull == null || lifecycleEnded) return
        val text = getColor(R.color.colorTextDark)
        view.setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(ColorUtils.setAlphaComponent(text, 0x66), text)))
        view.backgroundTintList = null
        replaceControlBackground(view, skinBackground(monetColors.surface, radiusDp, false,
            if (filled) SurfaceRole.FILLED_BUTTON else SurfaceRole.TEXT_BUTTON))
        val mask = GradientDrawable().apply {
            cornerRadius = radiusDp * resources.displayMetrics.density
            setColor(android.graphics.Color.WHITE)
        }
        // Glass stays the direct background; a foreground ripple cannot sever its View callback.
        view.foreground = RippleDrawable(ColorStateList.valueOf(
            ColorUtils.setAlphaComponent(monetColors.primary, 0x33)), controlOutline(radiusDp, filled), mask)
    }

    protected val skinEmphasisTextColor: Int
        get() = getColor(R.color.colorTextDark)

    protected fun skinSelectionControl(view: View, radiusDp: Float, selected: Boolean) {
        if (skinSessionOrNull == null || lifecycleEnded) return
        replaceControlBackground(view, skinBackground(monetColors.surface, radiusDp, false,
            if (selected) SurfaceRole.SELECTED_ITEM else SurfaceRole.CARD))
        view.foreground = controlOutline(radiusDp, selected)
    }

    protected fun skinUpdateBadge(view: TextView) {
        if (skinSessionOrNull == null || lifecycleEnded) return
        view.setTextColor(getColor(R.color.colorTextDark))
        view.background = com.Bilibili_Innocent_Lab.xposedmodule.ui.widget.GithubUpdateBadgeDrawable(
            ColorUtils.setAlphaComponent(monetColors.surface, 220), resources.displayMetrics.density,
            ColorUtils.setAlphaComponent(monetColors.primary, 210))
    }

    protected fun skinStatusChip(view: TextView, accent: Int, radiusDp: Float = 9f) {
        if (skinSessionOrNull == null || lifecycleEnded) return
        val density = resources.displayMetrics.density
        // Small semantic labels retain a readable solid glyph; no per-label optical capture.
        replaceControlBackground(view, GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(ColorUtils.setAlphaComponent(monetColors.surface, 210),
                ColorUtils.setAlphaComponent(monetColors.surface, 160))).apply {
            cornerRadius = radiusDp * density
            setStroke(density.toInt().coerceAtLeast(1), ColorUtils.setAlphaComponent(accent, 180))
        })
    }

    private fun replaceControlBackground(view: View, drawable: Drawable) {
        val left = view.paddingLeft; val top = view.paddingTop
        val right = view.paddingRight; val bottom = view.paddingBottom
        view.background = drawable
        view.setPadding(left, top, right, bottom)
    }

    private fun controlOutline(radiusDp: Float, emphasized: Boolean = false): Drawable {
        fun border(active: Boolean) = GradientDrawable().apply {
            cornerRadius = radiusDp * resources.displayMetrics.density
            setColor(android.graphics.Color.TRANSPARENT)
            setStroke(((if (active) 2f else 1f) * resources.displayMetrics.density).toInt().coerceAtLeast(1),
                ColorUtils.setAlphaComponent(monetColors.primary, if (active || emphasized) 0x72 else 0x22))
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), border(true))
            addState(intArrayOf(android.R.attr.state_pressed), border(true))
            addState(intArrayOf(), border(false))
        }
    }

    /** 让一个已在层级中的滚动 View 仅以前景内容参与系统 stretch；Material/低版本为 no-op。 */
    @MainThread
    protected fun installPreparedLiquidStretch(
        scrollTarget: View,
        isStretchAllowed: () -> Boolean = { true }
    ): View? = skinSessionOrNull?.installStretchViewport(
        scrollTarget = scrollTarget,
        isStretchAllowed = isStretchAllowed
    )

    @MainThread
    protected fun finishPreparedLiquidStretch(view: View?) {
        skinSessionOrNull?.finishStretchViewport(view)
    }

    /** Translation moves retained display lists without firing a scroll callback. */
    @MainThread
    protected fun notifyPreparedSkinPositionChanged() {
        if (!lifecycleEnded) skinSessionOrNull?.notifyPositionChanged()
    }

    @MainThread
    protected fun notifyPreparedSkinScrollPositionChanged(scrollHost: View) {
        if (!lifecycleEnded) skinSessionOrNull?.notifyScrollPositionChanged(scrollHost)
    }

    /** 悬浮胶囊下方的内容层（必须是胶囊的兄弟而非祖先），供模糊/折射采样。 */
    @MainThread
    protected fun bindPreparedSkinContentSource(view: View) {
        if (!lifecycleEnded) skinSessionOrNull?.bindContentSource(view)
    }

    /**
     * 当前在画的凝光引擎，供悬浮栏可读性（`GlowFloatingChrome`）现取现用；生命周期结束或
     * 没有会话时为 null。调用方不得缓存——Liquid 失败回落后会换成柔光。
     */
    internal val glowEngine: GlowEngine?
        get() = if (lifecycleEnded) null else skinSessionOrNull?.engine

    /** 当前持久化选择是否请求 Liquid；未准备会话时保持 false。 */
    // internal 而非 protected：同上，外移的摘要文案（SkinSummaryPresenter）是扩展函数，
    // 拿不到 protected。两者都是只读 val，放宽的是"读"而不是"写"，
    // isLiquidSkinEffective / isMaterialYouSkinEffective 只被 Activity 体内调用，仍是 protected。
    internal val isLiquidSkinRequested: Boolean
        get() = skinSessionOrNull?.requestedSkin == SkinId.LIQUID

    /** 当前 Activity 是否已安全装配 Liquid renderer。 */
    protected val isLiquidSkinEffective: Boolean
        get() = skinSessionOrNull?.effectiveSkin == SkinId.LIQUID

    /**
     * Material You 美学是否生效。
     *
     * 没有皮肤会话时（回退路径）视觉上等价于 Material，所以也算 Material You——
     * 判定写成"不是 Liquid"，新增第三种皮肤时这里必须重新审视。
     */
    protected val isMaterialYouSkinEffective: Boolean
        get() = skinSessionOrNull?.effectiveSkin != SkinId.LIQUID

    /** 当前实际后端名称；Material You 或尚未准备时为 null。 */
    internal val liquidBackendName: String?
        get() = skinSessionOrNull?.liquidBackendName

    /** 当前 Activity 的无引用诊断摘要；调用方不能由此接触 renderer 或 View。 */
    internal fun currentSkinDiagnostics(): SkinSessionDiagnostics? =
        skinSessionOrNull?.diagnostics

    /** 卡片语义背景；不向公开/受保护 API 暴露 internal token 或 SurfaceRole 类型。 */
    internal fun skinCardBackground(
        color: Int,
        radiusDp: Float = 15f
    ): Drawable = skinBackground(
        color,
        radiusDp,
        materialOutline = false,
        role = SurfaceRole.CARD
    )

    /** Safe before consent: palette only; no SkinPrefs, bitmap work or Liquid session ownership. */
    internal fun neutralWindowBackground(): Drawable = ModernMaterialDrawables.neutralWindow(monetColors)

    internal fun skinFloatingBackground(color: Int, radiusDp: Float = 28f): Drawable =
        skinBackground(color, radiusDp, materialOutline = true, role = SurfaceRole.FLOATING)

    internal fun skinTopBarBackground(color: Int, radiusDp: Float = 0f): Drawable =
        skinBackground(color, radiusDp, materialOutline = false, role = SurfaceRole.TOP_BAR)

    internal fun skinSelectionBackground(color: Int, radiusDp: Float = 22f): Drawable =
        skinBackground(color, radiusDp, materialOutline = true, role = SurfaceRole.SELECTED_ITEM)

    internal fun skinChromeOverlayBackground(color: Int, radiusDp: Float, selected: Boolean = false): Drawable =
        ModernMaterialDrawables.chromeOverlay(color, radiusDp * resources.displayMetrics.density,
            resources.displayMetrics.density, if (selected) SurfaceRole.SELECTED_ITEM else SurfaceRole.FLOATING,
            ColorUtils.calculateLuminance(monetColors.background) < .5)

    /** 模态表面语义背景；保留既有 28dp 默认圆角。 */
    protected fun skinModalBackground(
        color: Int,
        radiusDp: Float = 28f
    ): Drawable = skinBackground(
        color,
        radiusDp,
        materialOutline = true,
        role = SurfaceRole.MODAL
    )

    /** 入口与全屏形变共享的语义表面；普通皮肤仍返回等价的 Material 背景。 */
    protected fun skinMotionSurfaceBackground(
        color: Int,
        radiusDp: Float
    ): Drawable = skinBackground(
        color,
        radiusDp,
        materialOutline = false,
        role = SurfaceRole.MOTION_SURFACE
    )

    /** 动态形变层只在 Liquid renderer 已生效时接管，避免普通 Drawable 覆盖自绘动态边界。 */
    protected fun liquidMotionSurfaceBackgroundOrNull(
        color: Int,
        radiusDp: Float
    ): Drawable? = if (isLiquidSkinEffective) {
        skinSessionOrNull?.surfaceBackground(
            color,
            radiusDp,
            materialOutline = false,
            role = SurfaceRole.MOTION_SURFACE
        )
    } else null

    private fun skinBackground(
        color: Int,
        radiusDp: Float,
        materialOutline: Boolean,
        role: SurfaceRole
    ): Drawable =
        skinSessionOrNull?.surfaceBackground(color, radiusDp, materialOutline, role)
            ?: ModernMaterialDrawables.fallback(color, radiusDp.coerceAtLeast(0f) * resources.displayMetrics.density,
                resources.displayMetrics.density, role, ColorUtils.calculateLuminance(monetColors.background) < .5)

    override fun onStart() {
        super.onStart()
        windowStarted = true
        windowDelegate?.setStarted(true)
        skinSessionOrNull?.onActivityStarted()
    }

    override fun onStop() {
        windowStarted = false
        windowDelegate?.setStarted(false)
        clearElasticInteractions()
        skinSessionOrNull?.onActivityStopped()
        super.onStop()
    }

    override fun onPause() {
        clearElasticInteractions()
        super.onPause()
    }

    override fun onTrimMemory(level: Int) {
        skinSessionOrNull?.onTrimMemory(level)
        super.onTrimMemory(level)
    }

    override fun onLowMemory() {
        skinSessionOrNull?.onLowMemory()
        super.onLowMemory()
    }

    override fun onDestroy() {
        lifecycleEnded = true
        elasticInteraction?.dispose()
        elasticInteraction = null
        dialogInteractions.values.toList().forEach { it.release() }
        val session = skinSessionOrNull
        skinSessionOrNull = null
        try {
            session?.close()
        } finally {
            try {
                val delegate = windowDelegate
                windowDelegate = null
                delegate?.close()
            } finally {
                super.onDestroy()
            }
        }
    }
}
