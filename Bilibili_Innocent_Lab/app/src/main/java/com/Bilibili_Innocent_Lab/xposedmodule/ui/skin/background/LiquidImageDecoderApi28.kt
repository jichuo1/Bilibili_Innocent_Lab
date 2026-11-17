package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.annotation.DoNotInline
import androidx.annotation.RequiresApi
import java.io.File

/** API 28 解码器及其生成的回调类型仅在版本门禁之后加载，不泄漏进通用存储类。 */
@RequiresApi(28)
internal object LiquidImageDecoderApi28 {
    /** 导入规范化：目标尺寸由共享尺寸策略决定。 */
    @DoNotInline
    fun decode(file: File): Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val orientedSize = LiquidBackgroundSizingPolicy.resolveNormalizedSize(
                info.size.width,
                info.size.height
            )
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            // LOW_RAM 可把不透明图片解码为 RGB_565，丢失原图色阶。
            decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_DEFAULT
            decoder.setTargetSize(orientedSize.width, orientedSize.height)
        }
    }.getOrNull()

    /**
     * 宿主背景的有界预览加载：同一解码器与格式支持（HEIF/WebP 等），只把最长边
     * 压到 [maxEdge]，等比不放大；EXIF 由 ImageDecoder 自动应用。
     */
    @DoNotInline
    fun decode(file: File, maxEdge: Int): Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            val scale = minOf(1f, maxEdge.toFloat() / maxOf(width, height))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_DEFAULT
            decoder.setTargetSize(
                (width * scale).toInt().coerceAtLeast(1),
                (height * scale).toInt().coerceAtLeast(1)
            )
        }
    }.getOrNull()
}
