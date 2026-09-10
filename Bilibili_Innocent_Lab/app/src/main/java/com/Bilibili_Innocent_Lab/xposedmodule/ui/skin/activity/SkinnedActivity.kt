package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.activity

import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import androidx.appcompat.widget.SwitchCompat
import androidx.core.graphics.ColorUtils
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidChoiceDrawable
import androidx.annotation.MainThread
import com.highcapable.betterandroid.ui.component.activity.AppViewsActivity
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.runtime.ActivitySkinSession
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.runtime.SkinSessionDiagnostics
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.SkinId
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.SurfaceRole
import com.Bilibili_Innocent_Lab.xposedmodule.ui.theme.MonetColors

/**
 * 只管理 Activity 级皮肤会话的薄基类。
 *
 * 它故意不接管 onCreate、Window、contentView、系统栏、语言或重建时机，避免改变三个现有
 * Activity 的条款门禁和转场顺序。
 */
abstract class SkinnedActivity : AppViewsActivity() {

    private var skinSessionOrNull: ActivitySkinSession? = null
    private var materialPaletteOrNull: MonetColors? = null
    private var lifecycleEnded = false

    /**
     * 兼容现有调用点的纯 Material 调色板入口。
     *
     * 这里故意不读取 SkinPrefs 或启动 renderer；调色板只读取独立的配色规范用户设置，
     * 让条款页与授权后的界面保持同一组 Material 颜色。
     */
    // internal 而非 protected：设置页的弹窗正按主题外移成 `MainActivity` 的扩展函数，
    // 而 Kotlin 的扩展函数**拿不到 protected 成员**（protected 只对子类体内可见）。
    // internal 仍然限制在本模块内，不进入任何对外 API；调色板的来源约束不变
    // （见 docs/architecture.md：与皮肤仓库无关，仍是 Activity 作用域的 fromWallpaper）。
    // 只放宽外移代码真正需要的三个：monetColors / skinActionButton / skinCardBackground。
    // stylePreparedSkinControls 等仍是 protected——它们只被留在 Activity 里的底座调用。
    internal val monetColors: MonetColors
        get() = materialPaletteOrNull
            ?: MonetColors.fromWallpaper(this).also { materialPaletteOrNull = it }

    /** 条款授权后的皮肤装配点；未授权分支不得调用。 */
    @MainThread
    protected fun prepareSkinSession() {
        if (lifecycleEnded || skinSessionOrNull != null) return
        skinSessionOrNull = ActivitySkinSession.create(this, monetColors)
    }

    /**
     * 把已准备的 Liquid 会话绑定到 MainActivity 的可见根 View。
     *
     * Material You 是成功的 no-op；未 prepare 或 Activity 已结束返回 false。回调只表示完整
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
        if (!isLiquidSkinEffective || lifecycleEnded) return
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
                }
                is CheckBox -> {
                    val size = (view.buttonDrawable?.intrinsicWidth ?: 0).coerceAtLeast((24 * density).toInt())
                    view.buttonTintList = null
                    view.buttonDrawable = choice(size, size, checkbox = true)
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

    /** Called after the existing Material decoration: Material/unauthorized paths are exact no-ops. */
    internal fun skinActionButton(view: TextView, filled: Boolean, radiusDp: Float = 20f) {
        if (!isLiquidSkinEffective || lifecycleEnded) return
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
        get() = if (isLiquidSkinEffective) getColor(R.color.colorTextDark) else monetColors.onPrimary

    protected fun skinSelectionControl(view: View, radiusDp: Float, selected: Boolean) {
        if (!isLiquidSkinEffective || lifecycleEnded) return
        replaceControlBackground(view, skinBackground(monetColors.surface, radiusDp, false,
            if (selected) SurfaceRole.SELECTED_ITEM else SurfaceRole.CARD))
        view.foreground = controlOutline(radiusDp, selected)
    }

    protected fun skinUpdateBadge(view: TextView) {
        if (!isLiquidSkinEffective || lifecycleEnded) return
        view.setTextColor(getColor(R.color.colorTextDark))
        view.background = com.Bilibili_Innocent_Lab.xposedmodule.ui.widget.GithubUpdateBadgeDrawable(
            ColorUtils.setAlphaComponent(monetColors.surface, 220), resources.displayMetrics.density,
            ColorUtils.setAlphaComponent(monetColors.primary, 210))
    }

    protected fun skinStatusChip(view: TextView, accent: Int, radiusDp: Float = 9f) {
        if (!isLiquidSkinEffective || lifecycleEnded) return
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
                ColorUtils.setAlphaComponent(monetColors.primary, if (active || emphasized) 0xC0 else 0x55))
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

    /** 当前持久化选择是否请求 Liquid；未准备会话时保持 false。 */
    protected val isLiquidSkinRequested: Boolean
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
    protected val liquidBackendName: String?
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
            ?: GradientDrawable().apply {
                cornerRadius = radiusDp.coerceAtLeast(0f) * resources.displayMetrics.density
                setColor(color)
                if (materialOutline) {
                    setStroke(
                        resources.displayMetrics.density.toInt().coerceAtLeast(1),
                        androidx.core.graphics.ColorUtils.setAlphaComponent(
                            android.graphics.Color.WHITE,
                            0x18
                        )
                    )
                }
            }

    override fun onStart() {
        super.onStart()
        skinSessionOrNull?.onActivityStarted()
    }

    override fun onStop() {
        skinSessionOrNull?.onActivityStopped()
        super.onStop()
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
        val session = skinSessionOrNull
        skinSessionOrNull = null
        try {
            session?.close()
        } finally {
            super.onDestroy()
        }
    }
}
