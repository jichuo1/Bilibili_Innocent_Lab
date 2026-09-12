package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.annotation.DoNotInline
import androidx.annotation.RequiresApi
import java.io.File

/** API 28 解码器及其生成的回调类型仅在版本门禁之后加载，不泄漏进通用存储类。 */
@RequiresApi(28)
internal object LiquidImageDecoderApi28 {
    @DoNotInline
    fun decode(file: File): Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val orientedSize = LiquidBackgroundSizingPolicy.resolveNormalizedSize(
                info.size.width,
                info.size.height
            )
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
            decoder.setTargetSize(orientedSize.width, orientedSize.height)
        }
    }.getOrNull()
}
