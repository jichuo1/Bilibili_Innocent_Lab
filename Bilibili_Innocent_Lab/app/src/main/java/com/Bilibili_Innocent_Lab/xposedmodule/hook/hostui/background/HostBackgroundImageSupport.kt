package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.os.Build
import android.media.ExifInterface
import androidx.annotation.DoNotInline
import androidx.annotation.RequiresApi
import com.lumen.coacervation.engine.background.LiquidBackgroundImportFailure
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal data class HostBackgroundImageSize(val width: Int, val height: Int)

internal data class HostBackgroundNormalizeResult(
    val bitmap: Bitmap?,
    val failure: LiquidBackgroundImportFailure?
)

/**
 * 宿主自定义背景的尺寸策略、安全解码、EXIF 方向纠正与离线程模糊。
 * 保留 4096px / 16MP / 64MB 导入边界与 3 遍盒式模糊语义，不依赖模块皮肤内部实现。
 */
internal object HostBackgroundSizingPolicy {
    const val MAX_EDGE = 4096
    const val MAX_NORMALIZED_PIXELS = 16_000_000L
    const val MAX_INPUT_BYTES = 64L * 1024L * 1024L
    const val MAX_ASSET_BYTES = 96L * 1024L * 1024L
    const val MAX_DECLARED_EDGE = 32_768
    const val MAX_DECLARED_PIXELS = 128L * 1024L * 1024L

    fun resolveNormalizedSize(width: Int, height: Int): HostBackgroundImageSize {
        require(isSupportedDeclaredSize(width, height)) { "Image dimensions are not supported" }
        val edgeScale = MAX_EDGE.toDouble() / max(width, height).toDouble()
        val pixelScale = sqrt(
            MAX_NORMALIZED_PIXELS.toDouble() / (width.toLong() * height.toLong()).toDouble()
        )
        val scale = min(1.0, min(edgeScale, pixelScale))
        return HostBackgroundImageSize(
            width = (width * scale).toInt().coerceIn(1, MAX_EDGE),
            height = (height * scale).toInt().coerceIn(1, MAX_EDGE)
        )
    }

    fun isSupportedDeclaredSize(width: Int, height: Int): Boolean =
        width in 1..MAX_DECLARED_EDGE && height in 1..MAX_DECLARED_EDGE &&
            width.toLong() * height.toLong() <= MAX_DECLARED_PIXELS

    fun decodeSampleSize(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): Int {
        require(sourceWidth > 0 && sourceHeight > 0 && targetWidth > 0 && targetHeight > 0)
        var sample = 1
        while (sourceWidth / (sample * 2) >= targetWidth &&
            sourceHeight / (sample * 2) >= targetHeight
        ) {
            sample *= 2
        }
        return sample
    }
}

@RequiresApi(28)
internal object HostImageDecoderApi28 {
    @DoNotInline
    fun decode(file: File): Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val orientedSize = HostBackgroundSizingPolicy.resolveNormalizedSize(
                info.size.width,
                info.size.height
            )
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_DEFAULT
            decoder.setTargetSize(orientedSize.width, orientedSize.height)
        }
    }.getOrNull()

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

internal object HostBackgroundImageDecoder {
    fun normalizeForImport(file: File): HostBackgroundNormalizeResult {
        val bounds = decodeBounds(file)
            ?: return HostBackgroundNormalizeResult(null, LiquidBackgroundImportFailure.UNSUPPORTED_IMAGE)
        if (!HostBackgroundSizingPolicy.isSupportedDeclaredSize(bounds.first, bounds.second)) {
            return HostBackgroundNormalizeResult(null, LiquidBackgroundImportFailure.DIMENSIONS_TOO_LARGE)
        }
        val target = HostBackgroundSizingPolicy.resolveNormalizedSize(bounds.first, bounds.second)
        val decoded = if (Build.VERSION.SDK_INT >= 28) {
            HostImageDecoderApi28.decode(file)
        } else {
            decodeApi27(file, target)
        } ?: return HostBackgroundNormalizeResult(null, LiquidBackgroundImportFailure.UNSUPPORTED_IMAGE)

        val orientedTarget = HostBackgroundSizingPolicy.resolveNormalizedSize(
            decoded.width,
            decoded.height
        )
        val scaled = if (decoded.width == orientedTarget.width &&
            decoded.height == orientedTarget.height
        ) {
            decoded
        } else {
            Bitmap.createScaledBitmap(
                decoded,
                orientedTarget.width,
                orientedTarget.height,
                true
            ).also {
                if (it !== decoded) decoded.recycle()
            }
        }
        return HostBackgroundNormalizeResult(scaled, null)
    }

    @Suppress("DEPRECATION")
    private fun decodeApi27(file: File, target: HostBackgroundImageSize): Bitmap? {
        val bounds = decodeBounds(file) ?: return null
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = HostBackgroundSizingPolicy.decodeSampleSize(
                bounds.first,
                bounds.second,
                target.width,
                target.height
            )
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        return applyExifOrientation(decoded, file)
    }

    private fun decodeBounds(file: File): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return if (options.outWidth > 0 && options.outHeight > 0) {
            options.outWidth to options.outHeight
        } else null
    }

    @Suppress("DEPRECATION")
    fun applyExifOrientation(decoded: Bitmap, file: File): Bitmap? {
        val orientation = runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        }
        if (matrix.isIdentity) return decoded
        return runCatching {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                .also { if (it !== decoded) decoded.recycle() }
        }.getOrElse {
            decoded.recycle()
            null
        }
    }
}

internal object HostBackdropBlur {
    fun blurInto(pixels: IntArray, scratch: IntArray, width: Int, height: Int, radius: Int): IntArray {
        require(width > 0 && height > 0 && width.toLong() * height == pixels.size.toLong())
        require(scratch.size == pixels.size)
        require(radius in 1..32)
        var input = pixels
        var output = scratch
        repeat(3) {
            pass(input, output, width, height, radius, horizontal = true)
            val swap = input; input = output; output = swap
            pass(input, output, width, height, radius, horizontal = false)
            val next = input; input = output; output = next
        }
        return input
    }

    private fun pass(
        input: IntArray,
        output: IntArray,
        width: Int,
        height: Int,
        radius: Int,
        horizontal: Boolean
    ) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Backdrop replaced")
        val length = if (horizontal) width else height
        val lines = if (horizontal) height else width
        val stride = if (horizontal) 1 else width
        val window = radius * 2 + 1
        val last = length - 1
        for (line in 0 until lines) {
            val base = if (horizontal) line * width else line
            var a = 0; var r = 0; var g = 0; var b = 0
            for (offset in -radius..radius) {
                val index = if (offset < 0) 0 else if (offset > last) last else offset
                val color = input[base + index * stride]
                a += color ushr 24
                r += (color ushr 16) and 255
                g += (color ushr 8) and 255
                b += color and 255
            }
            for (position in 0 until length) {
                output[base + position * stride] = ((a / window) shl 24) or
                    ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
                val drop = position - radius
                val dropIndex = if (drop < 0) 0 else if (drop > last) last else drop
                val dropColor = input[base + dropIndex * stride]
                a -= dropColor ushr 24
                r -= (dropColor ushr 16) and 255
                g -= (dropColor ushr 8) and 255
                b -= dropColor and 255
                val add = position + radius + 1
                val addIndex = if (add < 0) 0 else if (add > last) last else add
                val addColor = input[base + addIndex * stride]
                a += addColor ushr 24
                r += (addColor ushr 16) and 255
                g += (addColor ushr 8) and 255
                b += addColor and 255
            }
        }
    }
}
