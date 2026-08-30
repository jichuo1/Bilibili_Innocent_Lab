package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.activity

import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import androidx.annotation.MainThread
import com.highcapable.betterandroid.ui.component.activity.AppViewsActivity
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.runtime.ActivitySkinSession
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
     * 这里故意不读取 SkinPrefs，保证 MainActivity 条款页在门禁前保持原有着色且无新增 I/O。
     */
    protected val monetColors: MonetColors
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
        return skinSessionOrNull?.bindRoot(root, onFailure) ?: false
    }

    /** 当前持久化选择是否请求 Liquid；未准备会话时保持 false。 */
    protected val isLiquidSkinRequested: Boolean
        get() = skinSessionOrNull?.requestedSkin == SkinId.LIQUID

    /** 当前 Activity 是否已安全装配 Liquid renderer。 */
    protected val isLiquidSkinEffective: Boolean
        get() = skinSessionOrNull?.effectiveSkin == SkinId.LIQUID

    /** 当前实际后端名称；Material You 或尚未准备时为 null。 */
    protected val liquidBackendName: String?
        get() = skinSessionOrNull?.liquidBackendName

    /** 卡片语义背景；不向公开/受保护 API 暴露 internal token 或 SurfaceRole 类型。 */
    protected fun skinCardBackground(
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
