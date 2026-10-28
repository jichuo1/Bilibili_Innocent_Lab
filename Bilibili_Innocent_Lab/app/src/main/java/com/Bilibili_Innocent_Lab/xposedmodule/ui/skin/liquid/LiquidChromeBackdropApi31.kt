package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.engine.GlowChromeBlurApi31
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.engine.GlowChromeGlassApi31
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.LiquidParameters
import io.github.jichuo1.meguviz.android.extensions.AndroidVersion
import kotlin.math.ceil

/**
 * 高级材质悬浮栏的内容节点玻璃（2026-09-23 可读性改造 B 期，API 31+），骨架见 [GlowChromeGlassApi31]。
 *
 * 效果链：先模糊、再用与窗口玻璃**同一段 AGSL**（同一份光学 uniform）折射（API 33+；31–32 只模糊）。
 * 节点里垫窗口底图（由渲染器传入），内容透明处与根背景一致。玻璃全不透——不再需要直透锐利
 * 内容来"对下取色"，下方内容以模糊 + 折射的形态出现。
 */
@RequiresApi(31)
internal object LiquidChromeBackdropApi31 {
    /**
     * 清透档：只把像素级锐边揉开（σ ≈ 0.58r ≈ 1.7dp），下方内容仍然看得出形状——液态玻璃是
     * "透明介质 + 边缘折射"，不是磨砂。原 12dp 把内容化成色块，真机观感与柔光趋同（2026-09-23 用户反馈）。
     * 可读性交给滚动边缘溶解与自适应着色。
     */
    private const val CHROME_BLUR_RADIUS_DP = LiquidChromeGlassPolicy.BLUR_RADIUS_DP
    /** 与窗口玻璃的浮动条常驻强度（1.15）和回弹上限（1.85）同一量级。 */
    private const val MAX_OPTICAL_INTENSITY = 1.85f
    private const val EDGE_SLACK_DP = 4f

    /** 构造里会编译 AGSL；调用方负责把失败当作"节点路径不可用"。 */
    fun create(parameters: LiquidParameters, density: Float): GlowChromeGlassApi31 {
        val blur = GlowChromeBlurApi31.create(CHROME_BLUR_RADIUS_DP * density)
        // Any 承载：RuntimeShader 是 API 33 类型，不能出现在本类（31+）的捕获字段里。
        val lens: Any? = if (AndroidVersion.isAtLeast(AndroidVersion.T)) LiquidChromeLensApi33.create(parameters, density) else null
        // 外沿覆盖折射 + 散射的最大取样距离，否则 shader 的越界收敛会把 rim 压平。
        val padding = ceil(
            ((parameters.refractionAmountDp + parameters.scatteringRadiusDp) * MAX_OPTICAL_INTENSITY + EDGE_SLACK_DP) * density
        ).toInt()
        return GlowChromeGlassApi31(padding) { width, height, pad, radiusPx, intensity, direction ->
            if (lens != null && AndroidVersion.isAtLeast(AndroidVersion.T)) {
                LiquidChromeLensApi33.effect(lens, blur, width, height, pad, radiusPx, intensity, direction)
            } else blur
        }
    }
}

/** API 33 的折射透镜：与窗口玻璃同一段 AGSL，`content` 由 RenderEffect 喂入（已模糊的节点内容）。 */
@RequiresApi(33)
internal object LiquidChromeLensApi33 {
    /** 返回不透明句柄，交回 [effect] 使用；调用方看不到 `RuntimeShader` 类型。 */
    fun create(parameters: LiquidParameters, density: Float): Any =
        RuntimeShader(ROUNDED_RECT_REFRACTION_SHADER).apply {
            applyLiquidOpticalUniforms(parameters, density)
        }

    /**
     * 节点坐标下的 uniform：表面在节点里偏移 [padding]，`offset = -padding` 让形状坐标回到
     * 表面局部；`backdropOrigin = +padding` 抵消它，`content` 按节点坐标原样取样；
     * `backdropExtent` 是整个节点——越界收敛只在节点外沿生效，表面 rim 处余量恒为 [padding]。
     */
    fun effect(
        lens: Any,
        blur: RenderEffect,
        width: Int,
        height: Int,
        padding: Int,
        radiusPx: Float,
        opticalIntensity: Float,
        stretchDirY: Float
    ): RenderEffect {
        val shader = lens as RuntimeShader
        shader.setFloatUniform("size", width.toFloat(), height.toFloat())
        shader.setFloatUniform("offset", -padding.toFloat(), -padding.toFloat())
        shader.setFloatUniform("backdropOrigin", padding.toFloat(), padding.toFloat())
        shader.setFloatUniform("backdropScale", 1f, 1f)
        shader.setFloatUniform("backdropExtent", (width + 2 * padding).toFloat(), (height + 2 * padding).toFloat())
        shader.setFloatUniform("cornerRadii", radiusPx, radiusPx, radiusPx, radiusPx)
        shader.setFloatUniform("opticalIntensity", opticalIntensity.coerceIn(1f, 1.85f))
        shader.setFloatUniform("stretchDirY", stretchDirY)
        shader.setFloatUniform("nodeInput", 1f)
        // 完整取样：清透档下内容基本是锐利的，色散与散射是液态玻璃边缘的特征，lite 会把它们砍掉。
        // 窗口任意一帧重绘都会让栏的效果层重算（见 GlowChromeGlassApi31），GPU 开销以真机 framestats 为准。
        shader.setFloatUniform("motionLite", 0f)
        // 先模糊、再折射：外层是透镜，内层是模糊（createChainEffect(outer, inner)）。
        return RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"), blur)
    }
}
