package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.material

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.engine.GlowChromeBlurApi31
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.engine.GlowChromeGlassApi31
import io.github.jichuo1.meguviz.android.extensions.AndroidVersion

/**
 * 柔光悬浮栏的内容节点玻璃（2026-09-23 可读性改造 C 期，API 31+）：[LiveBackdropSampler] 软件
 * 透镜的 GPU 等价实现，骨架见 [GlowChromeGlassApi31]。
 *
 * 逐步对应软件管线（[LensRefractionPolicy]）：
 * - **模糊**：软件是缩采样后三重盒式模糊，σ ≈ [LensRefractionPolicy.BLUR_DP]；RenderEffect 的
 *   σ = 0.57735·r + 0.5，取 [BLUR_RADIUS_DP] 使 σ 同量级。
 * - **提亮**：`illuminate` 的预乘空间 `c·gain + bias·a` 与非预乘空间 `c·gain + bias` 等价，
 *   API 31–32 用 ColorMatrix；API 33+ 合入透镜 shader，显式保持预乘 alpha。
 * - **透镜**（API 33+）：同一条 C¹ 连续的单轴透镜函数（中心放大 + 边沿外推），AGSL 逐像素求值；
 *   31–32 没有 RuntimeShader，只保留模糊与提亮。
 *
 * 节点里不垫底图：软件管线采的也只是内容层（透明处透明），静态磨砂由表面自己画在下面。
 * 结果与软件管线同层叠放：静态磨砂 → 本玻璃 → 色罩 → 描边。
 */
@RequiresApi(31)
internal object FrostedChromeGlassApi31 {
    private const val BLUR_RADIUS_DP = 17f
    private const val EDGE_SLACK_DP = 2f

    /** 构造里会编译 AGSL；调用方负责把失败当作"节点路径不可用"。 */
    fun create(density: Float, refraction: Boolean = true): GlowChromeGlassApi31 {
        val blur = GlowChromeBlurApi31.create(BLUR_RADIUS_DP * density)
        val gain = LensRefractionPolicy.LUMINANCE_GAIN
        val bias = LensRefractionPolicy.LUMINANCE_BIAS
        val illuminate = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            gain, 0f, 0f, 0f, bias,
            0f, gain, 0f, 0f, bias,
            0f, 0f, gain, 0f, bias,
            0f, 0f, 0f, 1f, 0f
        )))
        val lit = RenderEffect.createColorFilterEffect(illuminate, blur)
        // Any 承载：RuntimeShader 是 API 33 类型，不能出现在本类（31+）的捕获字段里。
        val lens: Any? = if (refraction && AndroidVersion.isAtLeast(AndroidVersion.T)) {
            FrostedChromeLensApi33.create()
        } else null
        // 边沿外推最多取到表面外 MARGIN_DP：与软件管线的外沿采样区一致。
        val padding = LensRefractionPolicy.marginPx(density) + (EDGE_SLACK_DP * density).toInt()
        return GlowChromeGlassApi31(padding) { width, height, pad, _, _, _ ->
            if (lens != null && AndroidVersion.isAtLeast(AndroidVersion.T)) {
                // API 33+ 在透镜中按预乘 alpha 提亮，避免额外颜色滤镜的透明边界处理。
                FrostedChromeLensApi33.effect(lens, blur, width, height, pad)
            } else lit
        }
    }
}

@RequiresApi(33)
internal object FrostedChromeLensApi33 {
    /** 返回不透明句柄，交回 [effect] 使用；调用方看不到 `RuntimeShader` 类型。 */
    fun create(): Any = RuntimeShader(SOFT_LENS_SHADER).apply {
        setFloatUniform("gain", LensRefractionPolicy.CENTER_GAIN_X, LensRefractionPolicy.CENTER_GAIN_Y)
        setFloatUniform("push", LensRefractionPolicy.RIM_PUSH_X, LensRefractionPolicy.RIM_PUSH_Y)
        setFloatUniform("rimStart", LensRefractionPolicy.RIM_START)
        setFloatUniform("luminanceGain", LensRefractionPolicy.LUMINANCE_GAIN)
        setFloatUniform("luminanceBias", LensRefractionPolicy.LUMINANCE_BIAS / 255f)
    }

    fun effect(lens: Any, input: RenderEffect, width: Int, height: Int, padding: Int): RenderEffect {
        val shader = lens as RuntimeShader
        shader.setFloatUniform("origin", padding.toFloat(), padding.toFloat())
        shader.setFloatUniform("size", width.toFloat(), height.toFloat())
        shader.setFloatUniform("travelBudget",
            LensRefractionPolicy.nodeTravelBudget(width.toFloat(), LensRefractionPolicy.CENTER_GAIN_X, LensRefractionPolicy.RIM_PUSH_X),
            LensRefractionPolicy.nodeTravelBudget(height.toFloat(), LensRefractionPolicy.CENTER_GAIN_Y, LensRefractionPolicy.RIM_PUSH_Y))
        return RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"), input)
    }
}

/** [LensRefractionPolicy.lens] 的逐像素版本：dest 归一化坐标 u ∈ [-1,1] 采样自 lens(u)。 */
private const val SOFT_LENS_SHADER = """
uniform shader content;
uniform float2 origin;
uniform float2 size;
uniform float2 travelBudget;
// RenderEffect 未提供扩大动态取样半径的公开参数；按表面输出区域限制 child shader 的取样。
uniform float2 gain;
uniform float2 push;
uniform float rimStart;
uniform float luminanceGain;
uniform float luminanceBias;

float smoothRim(float t) {
    float f = clamp(t, 0.0, 1.0);
    return f * f * (3.0 - 2.0 * f);
}

float lensAxis(float u, float centerGain, float rimPush) {
    float c = clamp(u, -1.0, 1.0);
    float magnified = c * (1.0 - centerGain * (1.0 - c * c));
    float rim = smoothRim((abs(c) - rimStart) / (1.0 - rimStart));
    float pushed = rimPush * rim * rim;
    return magnified + (c < 0.0 ? -pushed : pushed);
}

half4 main(float2 coord) {
    float2 u = (coord - origin) / size * 2.0 - 1.0;
    float2 lensed = float2(lensAxis(u.x, gain.x, push.x), lensAxis(u.y, gain.y, push.y));
    float2 source = origin + (lensed + 1.0) * 0.5 * size;
    float2 room = max(min(coord - origin - float2(0.5),
        origin + size - float2(0.5) - coord), float2(0.0));
    // 只在边缘逐渐减弱位移，不能将整段越界坐标都挤到同一列/同一行。
    source = coord + (source - coord) * clamp(room / travelBudget, 0.0, 1.0);
    // RuntimeShader RenderEffect 的子输入可能被裁到表面的输出区域；节点外沿不保证可采。
    half4 color = content.eval(clamp(source, origin + float2(0.5), origin + size - float2(0.5)));
    // AGSL 输入/输出均为预乘颜色；透明像素不能凭空获得提亮颜色。
    return half4(clamp(color.rgb * luminanceGain + luminanceBias * color.a,
        half3(0.0), half3(color.a)), color.a);
}
"""
