/*
   Copyright 2025 Kyant

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */

/*
 * Shader stability and linear-sRGB saturation portions are adapted from
 * AndroidLiquidGlassView v1.0.5, Copyright (c) 2025 QmDeve / Donny Yale,
 * licensed under the MIT License. See THIRD_PARTY_NOTICES.md and
 * third_party/AndroidLiquidGlassView-LICENSE.txt.
 */

package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid

import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.LiquidParameters
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.LiquidRenderBackend
import kotlin.math.cos
import kotlin.math.sin

/**
 * View renderer adaptation of AndroidLiquidGlass `Shaders.kt` at commit `65ab177`, with the
 * safe rounded-rectangle gradient and color treatment from AndroidLiquidGlassView v1.0.5.
 *
 * Modifications: extracted the rounded-rectangle refraction program, removed Compose/Skia wrappers,
 * uses one Android RuntimeShader per Activity backend, binds the module-owned stable or real-time
 * backdrop, and supplies equal corner radii from the View Drawable contract.
 */
@RequiresApi(33)
internal class LiquidRefractionBackendApi33(
    private val parameters: LiquidParameters,
    private val density: Float
) : LiquidBackendDriver {
    override val backend = LiquidRenderBackend.REFRACTION
    override val requiresBackdrop = true

    private val shader = RuntimeShader(ROUNDED_RECT_REFRACTION_SHADER)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        this.shader = this@LiquidRefractionBackendApi33.shader
    }
    private var source: LiquidBackdropSource? = null
    private var appliedScatterTapMode = FULL_SCATTER_TAPS

    init {
        shader.setFloatUniform(
            "refractionHeight",
            (parameters.refractionHeightDp * density).coerceAtLeast(0.1f)
        )
        shader.setFloatUniform("refractionAmount", parameters.refractionAmountDp * density)
        shader.setFloatUniform("depthEffect", parameters.depthEffect)
        shader.setFloatUniform(
            "interiorDistortion",
            parameters.interiorDistortionDp * density
        )
        shader.setFloatUniform("chromaticShift", parameters.chromaticShiftDp * density)
        shader.setFloatUniform("scatteringRadius", parameters.scatteringRadiusDp * density)
        shader.setFloatUniform("scatteringStrength", parameters.scatteringStrength)
        shader.setFloatUniform("chromaMultiplier", parameters.saturation)
        // 屏幕 y 轴向下；约定 L = (cos θ, sin θ)，外法线朝向光源的边缘被点亮。
        val lightRadians = Math.toRadians(parameters.highlightAngleDegrees.toDouble())
        shader.setFloatUniform(
            "lightDirection",
            cos(lightRadians).toFloat(),
            sin(lightRadians).toFloat()
        )
        shader.setFloatUniform("specularStrength", parameters.specularStrength)
        shader.setFloatUniform("fresnelStrength", parameters.fresnelStrength)
        shader.setFloatUniform("causticLuminanceGain", parameters.causticLuminanceGain)
        shader.setFloatUniform("innerShadowStrength", parameters.innerShadowStrength)
        shader.setFloatUniform("scatterTapMode", FULL_SCATTER_TAPS)
    }

    override fun bindBackdrop(source: LiquidBackdropSource) {
        check(!source.isClosed) { "Cannot bind a closed Liquid backdrop" }
        this.source = source
        // RuntimeShader 子输入不会继承外层 Paint.FILTER_BITMAP_FLAG；DEFAULT 在此会退化为最近邻。
        source.bitmapShader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
        shader.setInputShader("content", source.bitmapShader)
        shader.setFloatUniform(
            "backdropScale",
            source.bitmap.width.toFloat() / source.fullWidth.toFloat(),
            source.bitmap.height.toFloat() / source.fullHeight.toFloat()
        )
        // 根空间下 backdrop 的有效范围；shader 用它把折射位移收在页面之内。
        shader.setFloatUniform(
            "backdropExtent",
            source.fullWidth.toFloat(),
            source.fullHeight.toFloat()
        )
    }

    override fun drawBackdrop(
        canvas: Canvas,
        bounds: Rect,
        radiusPx: Float,
        viewX: Int,
        viewY: Int,
        opticalIntensity: Float
    ) {
        checkNotNull(source) { "Liquid refraction backdrop is not bound" }
        shader.setFloatUniform("size", bounds.width().toFloat(), bounds.height().toFloat())
        shader.setFloatUniform("offset", -bounds.left.toFloat(), -bounds.top.toFloat())
        shader.setFloatUniform("backdropOrigin", viewX.toFloat(), viewY.toFloat())
        shader.setFloatUniform("cornerRadii", radiusPx, radiusPx, radiusPx, radiusPx)
        shader.setFloatUniform("opticalIntensity", opticalIntensity.coerceIn(1f, 1.85f))
        // 全屏模态按面积降到 2 抽样散射；卡片级表面保持 4 抽样，权重两侧都守恒。
        val reducedTaps = LiquidRealtimeCapturePolicy.useReducedScatterTaps(
            bounds.width(),
            bounds.height()
        )
        val tapMode = if (reducedTaps) REDUCED_SCATTER_TAPS else FULL_SCATTER_TAPS
        if (appliedScatterTapMode != tapMode) {
            shader.setFloatUniform("scatterTapMode", tapMode)
            appliedScatterTapMode = tapMode
        }
        canvas.drawRoundRect(
            bounds.left.toFloat(),
            bounds.top.toFloat(),
            bounds.right.toFloat(),
            bounds.bottom.toFloat(),
            radiusPx,
            radiusPx,
            paint
        )
    }

    override fun close() {
        source = null
        paint.shader = null
    }

    private companion object {
        const val FULL_SCATTER_TAPS = 1f
        const val REDUCED_SCATTER_TAPS = 0f
    }
}

private const val ROUNDED_RECT_REFRACTION_SHADER = """
uniform shader content;

uniform float2 size;
uniform float2 offset;
uniform float2 backdropScale;
uniform float2 backdropOrigin;
uniform float2 backdropExtent;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;
uniform float interiorDistortion;
uniform float chromaticShift;
uniform float scatteringRadius;
uniform float scatteringStrength;
uniform float chromaMultiplier;
uniform float opticalIntensity;
uniform float2 lightDirection;
uniform float specularStrength;
uniform float fresnelStrength;
uniform float causticLuminanceGain;
uniform float innerShadowStrength;
uniform float scatterTapMode;

const half3 rgbToY = half3(0.2126, 0.7152, 0.0722);

float radiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) {
        if (coord.y <= 0.0) return radii.y;
        else return radii.z;
    } else {
        if (coord.y <= 0.0) return radii.x;
        else return radii.w;
    }
}

float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}

float safeSign(float value) {
    return value < 0.0 ? -1.0 : 1.0;
}

float2 safeNormalize(float2 value, float2 fallback) {
    float len = length(value);
    if (len > 0.001) return value / len;
    return fallback;
}

float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
        float2 outside = max(cornerCoord, 0.0);
        float outsideLength = length(outside);
        if (outsideLength > 0.001) {
            return sign(coord) * (outside / outsideLength);
        }
        float useX = step(cornerCoord.y, cornerCoord.x);
        return float2(
            useX * safeSign(coord.x),
            (1.0 - useX) * safeSign(coord.y)
        );
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}

half4 saturateColor(half4 color, float amount) {
    half3 linear = toLinearSrgb(color.rgb);
    float y = dot(linear, rgbToY);
    half3 gray = half3(y);
    half3 saturated = fromLinearSrgb(mix(gray, linear, amount));
    return half4(saturated, color.a);
}

half4 sampleContent(float2 canvasCoord) {
    float2 rootCoord = canvasCoord + offset + backdropOrigin;
    return content.eval(rootCoord * backdropScale);
}

half4 sampleRefracted(float2 canvasCoord, float2 direction) {
    half4 center = sampleContent(canvasCoord);
    if (chromaticShift <= 0.001) return center;
    float2 axis = safeNormalize(direction, float2(1.0, 0.0));
    float shift = chromaticShift * opticalIntensity;
    half red = sampleContent(canvasCoord + axis * shift).r;
    half blue = sampleContent(canvasCoord - axis * shift).b;
    return half4(red, center.g, blue, center.a);
}

half4 sampleScattered(
    float2 canvasCoord,
    float2 direction,
    float edgeWeight,
    float interiorLens,
    float edgeReach
) {
    half4 core = sampleRefracted(canvasCoord, direction);
    if (scatteringStrength <= 0.001 || scatteringRadius <= 0.001) return core;

    float spatialWeight = clamp(edgeWeight * 0.82 + interiorLens * 0.34, 0.0, 1.0);
    float amount = clamp(scatteringStrength * spatialWeight, 0.0, 0.72);
    // 权重可忽略时 mix 的结果与 core 在 8 位量化下不可分辨，直接省掉 2-4 次纹理取样。
    if (amount < 0.004) return core;

    float2 normal = safeNormalize(direction, float2(0.0, 1.0));
    float2 tangent = float2(-normal.y, normal.x);
    float radius = scatteringRadius * opticalIntensity * mix(0.42, 1.0, edgeWeight)
        * edgeReach;
    half4 tangentPositive = sampleContent(canvasCoord + tangent * radius);
    half4 tangentNegative = sampleContent(canvasCoord - tangent * radius);
    // 两条分支的权重都归一化到 1.0，降抽样不会改变整体亮度，只降低扩散的各向同性。
    half4 diffused;
    if (scatterTapMode > 0.5) {
        half4 normalPositive = sampleContent(canvasCoord + normal * radius * 0.58);
        half4 normalNegative = sampleContent(canvasCoord - normal * radius * 0.58);
        diffused = core * 0.46
            + (tangentPositive + tangentNegative) * 0.16
            + (normalPositive + normalNegative) * 0.11;
    } else {
        diffused = core * 0.56 + (tangentPositive + tangentNegative) * 0.22;
    }
    half4 scattered = mix(core, diffused, amount);
    // 内容感知焦散：背后越亮，边缘的白色焦散越强。亮度用 sRGB 近似，避免额外一次线性化。
    float backdropLuma = dot(scattered.rgb, rgbToY);
    float caustic = edgeWeight * scatteringStrength * 0.075 * opticalIntensity
        * (1.0 + causticLuminanceGain * backdropLuma);
    scattered.rgb = mix(scattered.rgb, half3(1.0), clamp(caustic, 0.0, 0.2));
    return scattered;
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(centeredCoord, cornerRadii);

    float2 safeHalfSize = max(halfSize, float2(1.0));
    float2 normalizedCoord = centeredCoord / safeHalfSize;
    float radial = clamp(length(normalizedCoord), 0.0, 1.0);
    float interiorLens = max(1.0 - radial * radial, 0.0);
    float2 interiorOffset = normalizedCoord * interiorDistortion * interiorLens
        * opticalIntensity;
    float2 interiorDirection = safeNormalize(centeredCoord, float2(1.0, 0.0));

    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    float insideDistance = max(-sd, 0.0);
    float edgePhase = clamp(1.0 - insideDistance / max(refractionHeight, 0.1), 0.0, 1.0);
    // Cubic smoothstep has zero derivatives at both ends, preventing a flashing band when
    // the real-time source advances to the next buffer.
    float edgeWeight = edgePhase * edgePhase * (3.0 - 2.0 * edgePhase);
    float d = edgeWeight * refractionAmount * opticalIntensity;
    float smoothRadius = max(radius * 1.5, min(refractionHeight * 1.6, 48.0));
    float gradRadius = min(smoothRadius, min(halfSize.x, halfSize.y));
    float2 shapeGrad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);
    float2 depthGrad = safeNormalize(centeredCoord, shapeGrad);
    float2 grad = safeNormalize(
        shapeGrad + depthEffect * edgeWeight * depthGrad,
        shapeGrad
    );
    float2 direction = safeNormalize(mix(interiorDirection, grad, edgeWeight), grad);

    // 页面最左右（及上下）边缘的收敛。
    //
    // 折射把采样点朝表面外侧推最多 `refractionAmount * opticalIntensity`，散射再加一圈半径。
    // 当玻璃贴着页面边缘时这些采样会越过 backdrop 范围，而 `content` 是 CLAMP 平铺的
    // BitmapShader——越界部分全部取到边缘那一列像素，于是整条带被横向抹开，表现为"背景被拉伸"。
    // 这里按当前像素到 backdrop 边界的可用余量线性收敛位移强度：远离边缘时 `edgeReach` 为 1，
    // 画面中部完全不受影响；贴边时收敛到 0，折射平滑变浅而不是让平铺模式去补像素。
    // 代价是每像素约 9 条 ALU，没有额外纹理读取、没有新的 pass。
    float2 rootCoord = coord + offset + backdropOrigin;
    float2 edgeMargin = min(rootCoord, backdropExtent - rootCoord);
    float nearestMargin = max(min(edgeMargin.x, edgeMargin.y), 0.0);
    float sampleReach = max(
        refractionAmount * opticalIntensity + scatteringRadius * opticalIntensity,
        1.0
    );
    // 线性收敛保证位移不超过余量；smoothstep 在 t>0.5 时会大于 t，反而重新越界。
    float edgeReach = clamp(nearestMargin / sampleReach, 0.0, 1.0);

    float2 refractedCoord = coord + (interiorOffset + d * grad) * edgeReach;
    half4 color = saturateColor(
        sampleScattered(refractedCoord, direction, edgeWeight, interiorLens, edgeReach),
        chromaMultiplier
    );

    // 折射带内侧的环境遮蔽：在带的中段最深、两端归零，制造"玻璃有厚度"的立体感。
    if (innerShadowStrength > 0.001) {
        float band = clamp(edgeWeight * (1.0 - edgeWeight) * 4.0, 0.0, 1.0);
        color.rgb *= half3(1.0 - innerShadowStrength * band);
    }

    // 掠射角增亮（Fresnel）：反射率随入射角上升，边缘因此比正面更亮。
    // 乘 edgeReach：贴着屏幕边缘时折射位移已收敛为 0，此处再保留全强度亮带就成了一条与玻璃
    // 无关的粗白边（折射带宽 refractionHeight 达 34dp）。让它随折射一起收敛，屏幕最外侧的
    // 高光因此变窄，画面中部的玻璃边缘逐像素不变。
    if (fresnelStrength > 0.001) {
        float fresnel = edgeWeight * edgeWeight * edgeWeight
            * fresnelStrength * opticalIntensity * edgeReach;
        color.rgb = mix(color.rgb, half3(1.0), clamp(fresnel, 0.0, 0.5));
    }

    // 定向镜面高光：三次曲线比可变指数更柔和且只需乘法；mix 避免加白后提前截断。
    if (specularStrength > 0.001) {
        float facing = clamp(dot(grad, lightDirection), 0.0, 1.0);
        float specularLobe = facing * facing * facing;
        float specular = specularLobe * edgeWeight
            * specularStrength * opticalIntensity * edgeReach;
        color.rgb = mix(color.rgb, half3(1.0), clamp(specular, 0.0, 0.35));
    }
    return color;
}
"""
