package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.core.graphics.ColorUtils
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

internal sealed interface LiquidBackgroundImportResult {
    data class Success(val config: LiquidBackgroundConfig) : LiquidBackgroundImportResult
    data class Failure(val reason: LiquidBackgroundImportFailure) : LiquidBackgroundImportResult
}

internal enum class LiquidBackgroundImportFailure {
    READ_FAILED,
    FILE_TOO_LARGE,
    UNSUPPORTED_IMAGE,
    DIMENSIONS_TOO_LARGE,
    ENCODE_FAILED,
    STORAGE_FAILED
}

internal data class LiquidBackgroundReadResult(
    val config: LiquidBackgroundConfig,
    val issue: LiquidBackgroundConfigIssue,
    val assetPresent: Boolean
)

/**
 * 自定义背景的唯一存储边界。
 *
 * 外部 URI 只在导入期间读取；成功后配置仅引用应用私有目录内的 generation 文件。新资产完整
 * 写入并回读验证后才提交配置，配置成功后才清理旧资产，从而在任意中断点保住旧背景。
 */
internal object LiquidBackgroundStore {
    private const val PREF_FILE = "liquid_background_preferences"
    private const val ASSET_DIRECTORY = "ui_skin_background"
    private const val ASSET_PREFIX = "background_"
    private const val ASSET_SUFFIX = ".img"
    private const val TEMP_PREFIX = "liquid_import_"
    private const val COPY_BUFFER_BYTES = 64 * 1024
    private const val JPEG_QUALITY = 92

    private val lock = Any()

    fun read(context: Context): LiquidBackgroundReadResult {
        val preferences = preferences(context)
        val decoded = runCatching {
            LiquidBackgroundConfigCodec.decode(preferences.all.toMap())
        }.getOrElse {
            LiquidBackgroundConfigDecodeResult(
                config = LiquidBackgroundConfig.AUTOMATIC,
                issue = LiquidBackgroundConfigIssue.TYPE_MISMATCH,
                needsRepair = true
            )
        }
        if (decoded.needsRepair) writeConfig(context, LiquidBackgroundConfig.AUTOMATIC)
        val config = decoded.config
        val assetPresent = config.mode == LiquidBackgroundMode.CUSTOM &&
            assetFile(context, requireNotNull(config.assetId)).let { it.isFile && it.length() > 0L }
        return LiquidBackgroundReadResult(config, decoded.issue, assetPresent)
    }

    fun importFromUri(context: Context, uri: Uri): LiquidBackgroundImportResult =
        synchronized(lock) {
            val appContext = context.applicationContext ?: context
            val directory = assetDirectory(appContext)
            if (!directory.exists() && !directory.mkdirs()) {
                return@synchronized LiquidBackgroundImportResult.Failure(
                    LiquidBackgroundImportFailure.STORAGE_FAILED
                )
            }
            val raw = File(directory, "$TEMP_PREFIX${UUID.randomUUID()}.raw")
            val encoded = File(directory, "$TEMP_PREFIX${UUID.randomUUID()}.encoded")
            var normalized: Bitmap? = null
            try {
                when (copyExternalInput(appContext, uri, raw)) {
                    CopyResult.TOO_LARGE -> return@synchronized LiquidBackgroundImportResult.Failure(
                        LiquidBackgroundImportFailure.FILE_TOO_LARGE
                    )
                    CopyResult.FAILED -> return@synchronized LiquidBackgroundImportResult.Failure(
                        LiquidBackgroundImportFailure.READ_FAILED
                    )
                    CopyResult.SUCCESS -> Unit
                }
                val decodeResult = decodeAndNormalize(raw)
                if (decodeResult.failure != null) {
                    return@synchronized LiquidBackgroundImportResult.Failure(decodeResult.failure)
                }
                normalized = requireNotNull(decodeResult.bitmap)
                if (!encodeNormalized(normalized, encoded)) {
                    return@synchronized LiquidBackgroundImportResult.Failure(
                        LiquidBackgroundImportFailure.ENCODE_FAILED
                    )
                }
                if (encoded.length() !in 1..LiquidBackgroundSizingPolicy.MAX_ASSET_BYTES) {
                    return@synchronized LiquidBackgroundImportResult.Failure(
                        LiquidBackgroundImportFailure.ENCODE_FAILED
                    )
                }
                val verifiedBounds = decodeBounds(encoded)
                    ?: return@synchronized LiquidBackgroundImportResult.Failure(
                        LiquidBackgroundImportFailure.ENCODE_FAILED
                    )
                val assetId = UUID.randomUUID().toString()
                val destination = assetFile(appContext, assetId)
                if (!encoded.renameTo(destination)) {
                    return@synchronized LiquidBackgroundImportResult.Failure(
                        LiquidBackgroundImportFailure.STORAGE_FAILED
                    )
                }
                val sha256 = hash(destination)
                    ?: run {
                        destination.delete()
                        return@synchronized LiquidBackgroundImportResult.Failure(
                            LiquidBackgroundImportFailure.STORAGE_FAILED
                        )
                    }
                val config = LiquidBackgroundConfig(
                    mode = LiquidBackgroundMode.CUSTOM,
                    assetId = assetId,
                    assetSha256 = sha256,
                    normalizedWidth = verifiedBounds.first,
                    normalizedHeight = verifiedBounds.second,
                    displayName = queryDisplayName(appContext, uri)
                )
                if (!writeConfig(appContext, config)) {
                    destination.delete()
                    return@synchronized LiquidBackgroundImportResult.Failure(
                        LiquidBackgroundImportFailure.STORAGE_FAILED
                    )
                }
                cleanupUnreferencedAssets(appContext, assetId)
                LiquidBackgroundImportResult.Success(config)
            } catch (_: SecurityException) {
                LiquidBackgroundImportResult.Failure(LiquidBackgroundImportFailure.READ_FAILED)
            } catch (_: OutOfMemoryError) {
                LiquidBackgroundImportResult.Failure(LiquidBackgroundImportFailure.DIMENSIONS_TOO_LARGE)
            } catch (_: Throwable) {
                LiquidBackgroundImportResult.Failure(LiquidBackgroundImportFailure.UNSUPPORTED_IMAGE)
            } finally {
                normalized?.recycle()
                raw.delete()
                encoded.delete()
            }
        }

    fun restoreAutomatic(context: Context): Boolean = synchronized(lock) {
        val appContext = context.applicationContext ?: context
        if (!writeConfig(appContext, LiquidBackgroundConfig.AUTOMATIC)) return@synchronized false
        cleanupUnreferencedAssets(appContext, keepAssetId = null)
        true
    }

    /** 后台线程调用；返回的 Bitmap 已是最终 backdrop 尺寸并由调用方接管。 */
    fun decodeBackdrop(
        context: Context,
        config: LiquidBackgroundConfig,
        targetWidth: Int,
        targetHeight: Int,
        backgroundColor: Int,
        dark: Boolean
    ): Bitmap? {
        if (config.mode != LiquidBackgroundMode.CUSTOM || targetWidth <= 0 || targetHeight <= 0) {
            return null
        }
        val file = assetFile(context, requireNotNull(config.assetId))
        if (!file.isFile || hash(file) != config.assetSha256) return null
        val bounds = decodeBounds(file) ?: return null
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = LiquidBackgroundSizingPolicy.decodeSampleSize(
                bounds.first,
                bounds.second,
                targetWidth,
                targetHeight
            )
        }
        val source = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        return try {
            val target = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(target)
            canvas.drawColor(backgroundColor)
            val transform = LiquidBackgroundSizingPolicy.centerCrop(
                source.width,
                source.height,
                targetWidth,
                targetHeight
            )
            val matrix = Matrix().apply {
                setScale(transform.scale, transform.scale)
                postTranslate(transform.translateX, transform.translateY)
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
            canvas.drawBitmap(source, matrix, paint)
            paint.color = ColorUtils.setAlphaComponent(
                if (dark) Color.BLACK else backgroundColor,
                if (dark) 0x28 else 0x20
            )
            canvas.drawRect(0f, 0f, targetWidth.toFloat(), targetHeight.toFloat(), paint)
            target.prepareToDraw()
            target
        } finally {
            source.recycle()
        }
    }

    private data class NormalizeResult(
        val bitmap: Bitmap?,
        val failure: LiquidBackgroundImportFailure?
    )

    private enum class CopyResult { SUCCESS, TOO_LARGE, FAILED }

    private fun copyExternalInput(context: Context, uri: Uri, destination: File): CopyResult {
        val declaredLength = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull()
        if (declaredLength != null && declaredLength > LiquidBackgroundSizingPolicy.MAX_INPUT_BYTES) {
            return CopyResult.TOO_LARGE
        }
        val input = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
            ?: return CopyResult.FAILED
        return runCatching {
            input.use { source ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var total = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > LiquidBackgroundSizingPolicy.MAX_INPUT_BYTES) {
                            return@runCatching CopyResult.TOO_LARGE
                        }
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            }
            if (destination.length() <= 0L) CopyResult.FAILED else CopyResult.SUCCESS
        }.getOrDefault(CopyResult.FAILED)
    }

    private fun decodeAndNormalize(file: File): NormalizeResult {
        val bounds = decodeBounds(file)
            ?: return NormalizeResult(null, LiquidBackgroundImportFailure.UNSUPPORTED_IMAGE)
        if (!LiquidBackgroundSizingPolicy.isSupportedDeclaredSize(bounds.first, bounds.second)) {
            return NormalizeResult(null, LiquidBackgroundImportFailure.DIMENSIONS_TOO_LARGE)
        }
        val target = LiquidBackgroundSizingPolicy.resolveNormalizedSize(bounds.first, bounds.second)
        val decoded = if (Build.VERSION.SDK_INT >= 28) {
            decodeApi28(file)
        } else {
            decodeApi27(file, target)
        } ?: return NormalizeResult(null, LiquidBackgroundImportFailure.UNSUPPORTED_IMAGE)
        // API 27 的 BitmapFactory 不自动应用 EXIF；旋转 90/270 度后必须按纠正后的宽高重新
        // 计算规范化尺寸，不能再拉伸回未旋转的 bounds 比例。
        val orientedTarget = LiquidBackgroundSizingPolicy.resolveNormalizedSize(
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
        return NormalizeResult(scaled, null)
    }

    @SuppressLint("NewApi")
    private fun decodeApi28(file: File): Bitmap? = runCatching {
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

    @Suppress("DEPRECATION")
    private fun decodeApi27(file: File, target: LiquidNormalizedImageSize): Bitmap? {
        val bounds = decodeBounds(file) ?: return null
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = LiquidBackgroundSizingPolicy.decodeSampleSize(
                bounds.first,
                bounds.second,
                target.width,
                target.height
            )
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
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

    @Suppress("DEPRECATION")
    private fun encodeNormalized(bitmap: Bitmap, destination: File): Boolean = runCatching {
        FileOutputStream(destination).use { output ->
            val format = if (bitmap.hasAlpha()) Bitmap.CompressFormat.PNG else {
                if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY
                else Bitmap.CompressFormat.WEBP
            }
            if (!bitmap.compress(format, JPEG_QUALITY, output)) return@runCatching false
            output.fd.sync()
        }
        true
    }.getOrDefault(false)

    private fun decodeBounds(file: File): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return if (options.outWidth > 0 && options.outHeight > 0) {
            options.outWidth to options.outHeight
        } else null
    }

    private fun hash(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }.getOrNull()

    private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index < 0) null else cursor.getString(index)
            }
    }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        ?.map { character ->
            if (character.isISOControl() || character == '/' || character == '\\') '_' else character
        }
        ?.joinToString("")
        ?.take(LiquidBackgroundConfigCodec.MAX_DISPLAY_NAME)

    @SuppressLint("UseKtx")
    private fun writeConfig(context: Context, config: LiquidBackgroundConfig): Boolean = runCatching {
        val preferences = preferences(context)
        val editor = preferences.edit().clear()
        LiquidBackgroundConfigCodec.encode(config).forEach { (key, value) ->
            when (value) {
                is Int -> editor.putInt(key, value)
                is String -> editor.putString(key, value)
                else -> error("Unsupported liquid background preference type")
            }
        }
        if (!editor.commit()) return@runCatching false
        val readBack = LiquidBackgroundConfigCodec.decode(preferences.all.toMap())
        !readBack.needsRepair && readBack.config == config
    }.getOrDefault(false)

    private fun cleanupUnreferencedAssets(context: Context, keepAssetId: String?) {
        assetDirectory(context).listFiles()?.forEach { file ->
            val keepName = keepAssetId?.let { "$ASSET_PREFIX$it$ASSET_SUFFIX" }
            if (file.name != keepName &&
                (file.name.startsWith(ASSET_PREFIX) || file.name.startsWith(TEMP_PREFIX))
            ) {
                file.delete()
            }
        }
    }

    private fun preferences(context: Context) =
        (context.applicationContext ?: context).getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    private fun assetDirectory(context: Context) =
        File((context.applicationContext ?: context).filesDir, ASSET_DIRECTORY)

    private fun assetFile(context: Context, assetId: String) =
        File(assetDirectory(context), "$ASSET_PREFIX$assetId$ASSET_SUFFIX")
}
