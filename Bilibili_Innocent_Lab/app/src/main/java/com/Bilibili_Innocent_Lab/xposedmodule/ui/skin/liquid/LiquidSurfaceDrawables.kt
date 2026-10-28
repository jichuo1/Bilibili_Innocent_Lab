package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid

import android.content.pm.ApplicationInfo
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import com.lumen.coacervation.engine.host.LumenBackdropReadiness
import android.view.View
import android.view.WindowManager
import android.app.Activity
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.SurfaceRole
import kotlin.math.ceil
import kotlin.math.floor

// 高级材质引擎的 Drawable 层：业务 View 只持有这些 Drawable，几何与绘制全部回调给
// [LiquidActivityRenderer]，renderer 不泄漏给业务代码。

/** 动态形变表面向 Liquid Drawable 暴露当前帧，不把 renderer 泄漏给业务 View。 */
internal typealias LiquidMotionSurfaceFrameProvider = com.lumen.coacervation.engine.liquid.LiquidMotionSurfaceFrameProvider

/** Surface 的真实 Drawable 几何；只在已有对象上更新，实时反馈遮罩逐帧零分配。 */
internal class LiquidSurfaceFootprint {
    val refreshState = LiquidSurfaceRefreshState()
    val screenTransform = FloatArray(9)
    var hasTransform = false
    var left = 0
    var top = 0
    var right = 0
    var bottom = 0
    var radiusPx = 0f

    /**
     * 上一次录制 display list 时该表面在屏幕上的位置。
     *
     * `backdropOrigin` 这个 uniform 是在 draw 里按当时的 `getLocationOnScreen` 写入的，会被
     * Skia 快照进 display list。视图只是被移动（滚动改 RenderNode 位置、translation 动画）
     * 而没有失效时，display list 会带着**旧原点**重放，玻璃里的背景于是停在旧位置，直到下一次
     * 失效才突然对齐——这就是慢速滑动时控件内背景抖动的来源。记录原点是为了只失效真正移动过的
     * 表面。
     */
    var originX = Int.MIN_VALUE
        private set
    var originY = Int.MIN_VALUE
        private set

    val hasOrigin: Boolean
        get() = originX != Int.MIN_VALUE && originY != Int.MIN_VALUE

    fun update(bounds: Rect, radiusPx: Float, originX: Int, originY: Int) {
        left = bounds.left
        top = bounds.top
        right = bounds.right
        bottom = bounds.bottom
        this.radiusPx = radiusPx
        this.originX = originX
        this.originY = originY
    }

    fun matchesOrigin(x: Int, y: Int): Boolean = originX == x && originY == y
}

internal class LiquidRootDrawable(
    private val renderer: LiquidActivityRenderer,
    private val fallbackColor: Int
) : Drawable(), LumenBackdropReadiness {
    override val isReadyForMotion: Boolean get() = renderer.isReadyForMotion
    private val location = IntArray(2)
    private var drawableAlpha = 255

    override fun draw(canvas: Canvas) {
        val view = callback as? View
        if (view != null) view.getLocationOnScreen(location)
        else {
            location[0] = 0
            location[1] = 0
        }
        renderer.drawRoot(
            canvas, bounds, drawableAlpha, location[0], location[1], fallbackColor
        )
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun getAlpha(): Int = drawableAlpha
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

internal class LiquidSurfaceDrawable(
    private val renderer: LiquidActivityRenderer,
    private val fallbackColor: Int,
    private val radiusPx: Float,
    private val role: SurfaceRole
) : Drawable() {
    private val location = IntArray(2)
    private val motionBoundsF = RectF()
    private val motionBounds = Rect()
    private var drawableAlpha = 255

    override fun draw(canvas: Canvas) {
        val view = callback as? View
        if (view != null) {
            view.getLocationOnScreen(location)
        }
        else {
            location[0] = 0
            location[1] = 0
        }
        var drawBounds = bounds
        var drawRadiusPx = radiusPx
        var drawFallbackColor = fallbackColor
        var drawX = location[0]
        var drawY = location[1]
        val motionProvider = view as? LiquidMotionSurfaceFrameProvider
        if (motionProvider != null) {
            motionProvider.copyLiquidMotionBounds(motionBoundsF)
            if (motionBoundsF.width() > 0f && motionBoundsF.height() > 0f) {
                motionBounds.set(
                    floor(motionBoundsF.left).toInt(),
                    floor(motionBoundsF.top).toInt(),
                    ceil(motionBoundsF.right).toInt(),
                    ceil(motionBoundsF.bottom).toInt()
                )
                drawBounds = motionBounds
                drawRadiusPx = motionProvider.liquidMotionCornerRadiusPx()
                drawFallbackColor = motionProvider.liquidMotionFallbackColor()
                // drawX/drawY 保持 View 原点：两条采样链（drawOpticalRegion 的逆矩阵与折射
                // shader 的 backdropOrigin）都把画布坐标当作"原点+局部坐标"解算根坐标，
                // motionBounds 本身已是承载层画布内的绝对矩形，再叠 left/top 会让采样窗
                // 二次偏移到卡片右下方——形变全程显示的是偏离真实位置的底图区域，落定
                // 换回卡片 0 基 drawable 时采样区瞬移（"通透背景跳变加载"的来源）。
            }
        }
        if (view != null) {
            // 只有硬件画布的绘制才会进 display list。footprint 记的是"上次录制时的位置"，
            // 刷新判定靠它比对：可读性探针（GlowContentProbe）、截图等软件画布若也登记，会把
            // 位置刷成新值却没有重录，滚动后该表面不再被失效、玻璃里的背景停在旧位置。
            if (canvas.isHardwareAccelerated) {
                renderer.registerSurfaceView(view, drawBounds, drawRadiusPx, location[0], location[1])
            }
        }
        renderer.drawSurface(
            canvas,
            drawBounds,
            drawRadiusPx,
            drawableAlpha,
            drawX,
            drawY,
            drawFallbackColor,
            role,
            host = view
        )
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun getAlpha(): Int = drawableAlpha
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    /** 静态几何报告真实圆角；缺省实现是无半径矩形，会让弹性长按高光按方形裁剪。 */
    override fun getOutline(outline: Outline) {
        if (bounds.isEmpty) outline.setEmpty()
        else outline.setRoundRect(bounds, radiusPx)
    }
}

internal fun Activity.isHardwareAccelerationRequested(): Boolean {
    val windowFlag = window.attributes.flags and WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
    val appFlag = applicationInfo.flags and ApplicationInfo.FLAG_HARDWARE_ACCELERATED
    return windowFlag != 0 || appFlag != 0
}
