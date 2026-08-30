package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal enum class LiquidBackgroundMode {
    AUTOMATIC,
    CUSTOM
}

internal enum class LiquidBackgroundConfigIssue {
    NONE,
    MISSING_OR_INVALID_SCHEMA,
    UNKNOWN_MODE,
    INVALID_CUSTOM_ASSET,
    TYPE_MISMATCH
}

/** 只保存内部规范化资产的稳定标识；绝不保存外部绝对路径或 content URI。 */
internal data class LiquidBackgroundConfig(
    val mode: LiquidBackgroundMode,
    val assetId: String? = null,
    val assetSha256: String? = null,
    val normalizedWidth: Int? = null,
    val normalizedHeight: Int? = null,
    val displayName: String? = null
) {
    init {
        require(isCanonical()) { "Liquid background config is not canonical" }
    }

    private fun isCanonical(): Boolean = when (mode) {
        LiquidBackgroundMode.AUTOMATIC ->
            assetId == null && assetSha256 == null && normalizedWidth == null &&
                normalizedHeight == null && displayName == null

        LiquidBackgroundMode.CUSTOM ->
            LiquidBackgroundConfigCodec.isValidAssetId(assetId) &&
                LiquidBackgroundConfigCodec.isValidSha256(assetSha256) &&
                normalizedWidth != null && normalizedWidth in 1..LiquidBackgroundSizingPolicy.MAX_EDGE &&
                normalizedHeight != null && normalizedHeight in 1..LiquidBackgroundSizingPolicy.MAX_EDGE &&
                normalizedWidth.toLong() * normalizedHeight.toLong() <=
                LiquidBackgroundSizingPolicy.MAX_NORMALIZED_PIXELS &&
                (displayName == null || displayName.length <= LiquidBackgroundConfigCodec.MAX_DISPLAY_NAME)
    }

    companion object {
        val AUTOMATIC = LiquidBackgroundConfig(LiquidBackgroundMode.AUTOMATIC)
    }
}

internal data class LiquidBackgroundConfigDecodeResult(
    val config: LiquidBackgroundConfig,
    val issue: LiquidBackgroundConfigIssue,
    val needsRepair: Boolean
)

/** SharedPreferences 边界外的纯 codec，便于完整覆盖损坏、旧版和未来值。 */
internal object LiquidBackgroundConfigCodec {
    const val CURRENT_SCHEMA_VERSION = 1
    const val MAX_DISPLAY_NAME = 160

    internal const val KEY_SCHEMA_VERSION = "schema_version"
    internal const val KEY_MODE = "mode"
    internal const val KEY_ASSET_ID = "asset_id"
    internal const val KEY_ASSET_SHA256 = "asset_sha256"
    internal const val KEY_NORMALIZED_WIDTH = "normalized_width"
    internal const val KEY_NORMALIZED_HEIGHT = "normalized_height"
    internal const val KEY_DISPLAY_NAME = "display_name"

    fun encode(config: LiquidBackgroundConfig): Map<String, Any> = buildMap {
        put(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION)
        put(KEY_MODE, config.mode.name)
        if (config.mode == LiquidBackgroundMode.CUSTOM) {
            put(KEY_ASSET_ID, requireNotNull(config.assetId))
            put(KEY_ASSET_SHA256, requireNotNull(config.assetSha256))
            put(KEY_NORMALIZED_WIDTH, requireNotNull(config.normalizedWidth))
            put(KEY_NORMALIZED_HEIGHT, requireNotNull(config.normalizedHeight))
            config.displayName?.let { put(KEY_DISPLAY_NAME, it) }
        }
    }

    fun decode(values: Map<String, *>): LiquidBackgroundConfigDecodeResult {
        if (values.isEmpty()) return clean(LiquidBackgroundConfig.AUTOMATIC)
        val schema = values[KEY_SCHEMA_VERSION] as? Int
            ?: return repair(LiquidBackgroundConfigIssue.MISSING_OR_INVALID_SCHEMA)
        if (schema != CURRENT_SCHEMA_VERSION) {
            return repair(LiquidBackgroundConfigIssue.MISSING_OR_INVALID_SCHEMA)
        }
        val modeName = values[KEY_MODE] as? String
            ?: return repair(LiquidBackgroundConfigIssue.TYPE_MISMATCH)
        val mode = runCatching { LiquidBackgroundMode.valueOf(modeName) }.getOrNull()
            ?: return repair(LiquidBackgroundConfigIssue.UNKNOWN_MODE)
        if (mode == LiquidBackgroundMode.AUTOMATIC) {
            val canonical = encode(LiquidBackgroundConfig.AUTOMATIC)
            return LiquidBackgroundConfigDecodeResult(
                config = LiquidBackgroundConfig.AUTOMATIC,
                issue = LiquidBackgroundConfigIssue.NONE,
                needsRepair = values != canonical
            )
        }

        val assetId = values[KEY_ASSET_ID] as? String
            ?: return repair(LiquidBackgroundConfigIssue.TYPE_MISMATCH)
        val sha256 = values[KEY_ASSET_SHA256] as? String
            ?: return repair(LiquidBackgroundConfigIssue.TYPE_MISMATCH)
        val width = values[KEY_NORMALIZED_WIDTH] as? Int
            ?: return repair(LiquidBackgroundConfigIssue.TYPE_MISMATCH)
        val height = values[KEY_NORMALIZED_HEIGHT] as? Int
            ?: return repair(LiquidBackgroundConfigIssue.TYPE_MISMATCH)
        val displayName = when (val value = values[KEY_DISPLAY_NAME]) {
            null -> null
            is String -> value
            else -> return repair(LiquidBackgroundConfigIssue.TYPE_MISMATCH)
        }
        if (!isValidAssetId(assetId) || !isValidSha256(sha256) ||
            width !in 1..LiquidBackgroundSizingPolicy.MAX_EDGE ||
            height !in 1..LiquidBackgroundSizingPolicy.MAX_EDGE ||
            width.toLong() * height.toLong() > LiquidBackgroundSizingPolicy.MAX_NORMALIZED_PIXELS ||
            (displayName != null && displayName.length > MAX_DISPLAY_NAME)
        ) {
            return repair(LiquidBackgroundConfigIssue.INVALID_CUSTOM_ASSET)
        }
        return clean(
            LiquidBackgroundConfig(
                mode = LiquidBackgroundMode.CUSTOM,
                assetId = assetId,
                assetSha256 = sha256.lowercase(),
                normalizedWidth = width,
                normalizedHeight = height,
                displayName = displayName
            )
        )
    }

    fun isValidAssetId(value: String?): Boolean =
        value != null && value.length in 1..80 && value.all {
            it.isLetterOrDigit() || it == '-' || it == '_'
        }

    fun isValidSha256(value: String?): Boolean =
        value != null && value.length == 64 && value.all {
            it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F'
        }

    private fun clean(config: LiquidBackgroundConfig) = LiquidBackgroundConfigDecodeResult(
        config = config,
        issue = LiquidBackgroundConfigIssue.NONE,
        needsRepair = false
    )

    private fun repair(issue: LiquidBackgroundConfigIssue) = LiquidBackgroundConfigDecodeResult(
        config = LiquidBackgroundConfig.AUTOMATIC,
        issue = issue,
        needsRepair = true
    )
}

internal data class LiquidNormalizedImageSize(val width: Int, val height: Int)

internal data class LiquidCenterCropTransform(
    val scale: Float,
    val translateX: Float,
    val translateY: Float
)

/** 导入和运行时共享的纯尺寸策略；源图再大也不会突破规范化资产边界。 */
internal object LiquidBackgroundSizingPolicy {
    const val MAX_EDGE = 2048
    const val MAX_NORMALIZED_PIXELS = 4L * 1024L * 1024L
    const val MAX_INPUT_BYTES = 32L * 1024L * 1024L
    const val MAX_ASSET_BYTES = 16L * 1024L * 1024L
    const val MAX_DECLARED_EDGE = 16_384
    const val MAX_DECLARED_PIXELS = 64L * 1024L * 1024L

    fun resolveNormalizedSize(width: Int, height: Int): LiquidNormalizedImageSize {
        require(isSupportedDeclaredSize(width, height)) { "Image dimensions are not supported" }
        val edgeScale = MAX_EDGE.toDouble() / max(width, height).toDouble()
        val pixelScale = kotlin.math.sqrt(
            MAX_NORMALIZED_PIXELS.toDouble() / (width.toLong() * height.toLong()).toDouble()
        )
        val scale = min(1.0, min(edgeScale, pixelScale))
        return LiquidNormalizedImageSize(
            width = (width * scale).roundToInt().coerceIn(1, MAX_EDGE),
            height = (height * scale).roundToInt().coerceIn(1, MAX_EDGE)
        )
    }

    fun isSupportedDeclaredSize(width: Int, height: Int): Boolean =
        width in 1..MAX_DECLARED_EDGE && height in 1..MAX_DECLARED_EDGE &&
            width.toLong() * height.toLong() <= MAX_DECLARED_PIXELS

    fun centerCrop(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): LiquidCenterCropTransform {
        require(sourceWidth > 0 && sourceHeight > 0 && targetWidth > 0 && targetHeight > 0)
        val scale = max(
            targetWidth.toFloat() / sourceWidth.toFloat(),
            targetHeight.toFloat() / sourceHeight.toFloat()
        )
        return LiquidCenterCropTransform(
            scale = scale,
            translateX = (targetWidth - sourceWidth * scale) * 0.5f,
            translateY = (targetHeight - sourceHeight * scale) * 0.5f
        )
    }

    /** BitmapFactory 只使用 2 的幂采样，且确保解码结果不会小于目标裁剪缓冲。 */
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
