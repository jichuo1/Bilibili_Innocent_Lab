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

/**
 * View renderer adaptation of AndroidLiquidGlass `Shaders.kt` at commit `65ab177`, with the
 * safe rounded-rectangle gradient and color treatment from AndroidLiquidGlassView v1.0.5.
 *
 * Modifications: extracted the rounded-rectangle refraction program, removed Compose/Skia wrappers,
 * uses one Android RuntimeShader per Activity backend, binds the module-owned static Monet backdrop,
 * and supplies equal corner radii from the View Drawable contract.
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

    init {
        shader.setFloatUniform(
            "refractionHeight",
            (parameters.refractionHeightDp * density).coerceAtLeast(0.1f)
        )
        shader.setFloatUniform("refractionAmount", parameters.refractionAmountDp * density)
        shader.setFloatUniform("depthEffect", parameters.depthEffect)
        shader.setFloatUniform("chromaMultiplier", parameters.saturation)
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
    }

    override fun drawBackdrop(
        canvas: Canvas,
        bounds: Rect,
        radiusPx: Float,
        viewX: Int,
        viewY: Int
    ) {
        checkNotNull(source) { "Liquid refraction backdrop is not bound" }
        shader.setFloatUniform("size", bounds.width().toFloat(), bounds.height().toFloat())
        shader.setFloatUniform("offset", -bounds.left.toFloat(), -bounds.top.toFloat())
        shader.setFloatUniform("backdropOrigin", viewX.toFloat(), viewY.toFloat())
        shader.setFloatUniform("cornerRadii", radiusPx, radiusPx, radiusPx, radiusPx)
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
}

private const val ROUNDED_RECT_REFRACTION_SHADER = """
uniform shader content;

uniform float2 size;
uniform float2 offset;
uniform float2 backdropScale;
uniform float2 backdropOrigin;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;
uniform float chromaMultiplier;

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

float circleMap(float x) {
    return 1.0 - sqrt(1.0 - x * x);
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

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(centeredCoord, cornerRadii);

    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) {
        return saturateColor(sampleContent(coord), chromaMultiplier);
    }
    sd = min(sd, 0.0);

    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float smoothRadius = max(radius * 1.5, 30.0);
    float gradRadius = min(smoothRadius, min(halfSize.x, halfSize.y));
    float2 shapeGrad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);
    float2 depthGrad = safeNormalize(centeredCoord, shapeGrad);
    float2 grad = safeNormalize(shapeGrad + depthEffect * depthGrad, shapeGrad);

    float2 refractedCoord = coord + d * grad;
    return saturateColor(sampleContent(refractedCoord), chromaMultiplier);
}
"""
