package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background.LiquidBackgroundImportFailure
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background.LiquidBackgroundSizingPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background.LiquidBackgroundStore
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background.LiquidImageDecoderApi28
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.material.ModernBackdropBlur
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.roundToInt

internal object HostBackgroundImages {
    const val AUTHORITY = "${BuildConfig.APPLICATION_ID}.hostbackground"
    fun uri(asset: String): Uri = Uri.parse("content://$AUTHORITY/image/$asset")
    fun file(context: Context, asset: String): File {
        require(HostBackgroundConfig.validAsset(asset))
        return File(context.filesDir, "host_background/$asset.png")
    }

    /** 仅在后台导入；源 URI 不落盘，失败时旧资产不受影响。 */
    fun import(context: Context, uri: Uri): String {
        val folder = File(context.filesDir, "host_background").apply { check(isDirectory || mkdirs()) }
        val raw = File.createTempFile("import_", ".raw", folder)
        val asset = UUID.randomUUID().toString()
        val target = file(context, asset)
        var bitmap: Bitmap? = null
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(raw).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= LiquidBackgroundSizingPolicy.MAX_INPUT_BYTES)
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("Unreadable image")
            // 与模块皮肤同一导入解码入口；失败原因原样上抛，UI 据此区分超限/损坏文案。
            val normalized = LiquidBackgroundStore.normalizeForImport(raw)
            bitmap = normalized.bitmap ?: error(normalized.failure ?: LiquidBackgroundImportFailure.UNSUPPORTED_IMAGE)
            FileOutputStream(target).use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                it.fd.sync()
            }
            check(target.length() in 1..LiquidBackgroundSizingPolicy.MAX_ASSET_BYTES)
            return asset
        } catch (failure: Throwable) {
            target.delete()
            throw failure
        } finally {
            bitmap?.recycle()
            raw.delete()
        }
    }

    fun grant(context: Context, asset: String) {
        if (HostBackgroundConfig.validAsset(asset))
            context.grantUriPermission("tv.danmaku.bili", uri(asset), Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** 调用方在提交新设置后清理旧图；取消编辑只清理本次临时导入。 */
    fun cleanup(context: Context, keep: String) {
        File(context.filesDir, "host_background").listFiles()?.filter { it.name.endsWith(".png") && it.name != "$keep.png" }
            ?.forEach {
                val asset = it.name.removeSuffix(".png")
                if (HostBackgroundConfig.validAsset(asset)) {
                    context.revokeUriPermission(uri(asset), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    it.delete()
                }
            }
    }

    /**
     * 与模块皮肤同源的有界加载：边界校验用共享尺寸策略（导入产出多大就能读多大，
     * 不再另设 2048 硬门禁），采样按显示需求算 2 的幂；API 28+ 复用同一个 ImageDecoder
     * 入口（支持 HEIF/WebP 等 BitmapFactory 不认的资产编码），API 27 走 BitmapFactory
     * 并共用 EXIF 纠正。仅后台线程调用（模块预览 worker 与宿主 host-background 线程）。
     */
    fun load(context: Context, config: HostBackgroundConfig): Bitmap? {
        if (!HostBackgroundConfig.validAsset(config.asset)) return null
        return runCatching {
            val file = file(context, config.asset)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (!LiquidBackgroundSizingPolicy.isSupportedDeclaredSize(bounds.outWidth, bounds.outHeight)) {
                return@runCatching null
            }
            val source = if (Build.VERSION.SDK_INT >= 28) {
                LiquidImageDecoderApi28.decode(file, PREVIEW_MAX_EDGE)
            } else {
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inSampleSize = LiquidBackgroundSizingPolicy.decodeSampleSize(
                        bounds.outWidth, bounds.outHeight, PREVIEW_MAX_EDGE, PREVIEW_MAX_EDGE
                    )
                }
                BitmapFactory.decodeFile(file.absolutePath, options)
                    ?.let { LiquidBackgroundStore.applyExifOrientation(it, file) }
            } ?: return@runCatching null
            try { process(source, config) } finally { source.recycle() }
        }.getOrNull()
    }

    /** 有界位图上的三遍 box blur，API 27 也支持，始终在后台执行。 */
    internal fun process(source: Bitmap, config: HostBackgroundConfig): Bitmap {
        val scale = minOf(1f, PREVIEW_MAX_EDGE.toFloat() / maxOf(source.width, source.height))
        val width = (source.width * scale).roundToInt().coerceAtLeast(1)
        val height = (source.height * scale).roundToInt().coerceAtLeast(1)
        val target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(config.saturation / 100f) })
        }
        Canvas(target).drawBitmap(source, null, android.graphics.Rect(0, 0, width, height), paint)
        // 模糊复用皮肤家族同源的 ModernBackdropBlur（同为三遍双向盒式、边缘复制，
        // 输出逐位一致）；其半径上限 32，超出时先按比例降采样再模糊，观感等效。
        val radius = (config.blur * width / 360f / 2f).roundToInt()
        var processedPixels: IntArray? = null
        if (radius > 0) {
            if (radius <= MAX_BLUR_RADIUS) {
                // 直接像素通路：不经过 Bitmap 预乘往返，透明像素的模糊语义与旧实现一致。
                val pixels = IntArray(width * height)
                target.getPixels(pixels, 0, width, 0, 0, width, height)
                val result = ModernBackdropBlur.blurInto(pixels, IntArray(pixels.size), width, height, radius)
                target.setPixels(result, 0, width, 0, 0, width, height)
                processedPixels = result
            } else {
                val blurScale = MAX_BLUR_RADIUS.toFloat() / radius
                val blurWidth = (width * blurScale).roundToInt().coerceAtLeast(1)
                val blurHeight = (height * blurScale).roundToInt().coerceAtLeast(1)
                val blurred = Bitmap.createBitmap(blurWidth, blurHeight, Bitmap.Config.ARGB_8888)
                try {
                    Canvas(blurred).drawBitmap(target, null, android.graphics.Rect(0, 0, blurWidth, blurHeight),
                        Paint(Paint.FILTER_BITMAP_FLAG))
                    val pixels = IntArray(blurWidth * blurHeight)
                    blurred.getPixels(pixels, 0, blurWidth, 0, 0, blurWidth, blurHeight)
                    val result = ModernBackdropBlur.blurInto(pixels, IntArray(pixels.size), blurWidth, blurHeight,
                        (radius * blurScale).roundToInt().coerceIn(1, MAX_BLUR_RADIUS))
                    blurred.setPixels(result, 0, blurWidth, 0, 0, blurWidth, blurHeight)
                    Canvas(target).drawBitmap(blurred, null, android.graphics.Rect(0, 0, width, height),
                        Paint(Paint.FILTER_BITMAP_FLAG))
                } finally {
                    blurred.recycle()
                }
                processedPixels = IntArray(width * height).also {
                    target.getPixels(it, 0, width, 0, 0, width, height)
                }
            }
        }
        // PNG 可能带 alpha 通道但每个像素都不透明；只在后台确认一次，后续可省掉底色和重复遮罩。
        if (!source.hasAlpha()) target.setHasAlpha(false)
        else {
            val pixels = processedPixels ?: IntArray(width * height).also {
                target.getPixels(it, 0, width, 0, 0, width, height)
            }
            if (pixels.all { it ushr 24 == 255 }) target.setHasAlpha(false)
        }
        target.prepareToDraw()
        return target
    }

    /** 预览/壁纸工作副本的最长边；与旧实现的 960 口径一致。 */
    private const val PREVIEW_MAX_EDGE = 960

    /** ModernBackdropBlur 的半径上限。 */
    private const val MAX_BLUR_RADIUS = 32
}
