package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid

import android.graphics.Canvas
import android.graphics.Rect
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.LiquidRenderBackend

/** 高 API 图形类型不得出现在该公共接口中，保证 API 27 可以安全加载通用 renderer。 */
internal interface LiquidBackendDriver : AutoCloseable {
    val backend: LiquidRenderBackend
    val requiresBackdrop: Boolean

    /** 只在布局/尺寸变化路径调用，不在 draw 中创建或绑定图形资源。 */
    fun bindBackdrop(source: LiquidBackdropSource)

    /** 调用方保证在主线程绘制；实现不得在此创建 Bitmap、Shader 或 RenderEffect。 */
    fun drawBackdrop(
        canvas: Canvas,
        bounds: Rect,
        radiusPx: Float,
        viewX: Int,
        viewY: Int,
        opticalIntensity: Float
    )
}

/** API 27 通用的零资源后端；实际半透明表面由通用 renderer 绘制。 */
internal class LiquidTranslucentBackend : LiquidBackendDriver {
    override val backend = LiquidRenderBackend.TRANSLUCENT
    override val requiresBackdrop = false

    override fun bindBackdrop(source: LiquidBackdropSource) = Unit

    override fun drawBackdrop(
        canvas: Canvas,
        bounds: Rect,
        radiusPx: Float,
        viewX: Int,
        viewY: Int,
        opticalIntensity: Float
    ) = Unit

    override fun close() = Unit
}
