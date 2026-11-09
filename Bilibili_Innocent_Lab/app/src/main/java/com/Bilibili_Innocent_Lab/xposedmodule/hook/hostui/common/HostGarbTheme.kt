package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** 读取宿主已装备的装扮；只融合颜色，角色图标仍由宿主加载和播放。 */
internal object HostGarbTheme {
    private class Access(loader: ClassLoader) {
        private val manager = KavaMemberLookup.classOrNull(loader, "com.bilibili.lib.ui.garb.GarbManager")
        private val current = manager?.let { KavaMemberLookup.inheritedMethodOrNull(it, "getCurGarb") }
        private val equipped = manager?.let { KavaMemberLookup.inheritedMethodOrNull(it, "getGarbWithNightMode", Context::class.java) }
        private val garb = KavaMemberLookup.classOrNull(loader, "com.bilibili.lib.ui.garb.Garb")
        // 门面只走类继承链；Garb 的实例方法是否全在类上无法静态确认，保留 getMethod 的接口查找范围。
        private fun method(name: String) = runCatching { garb?.getMethod(name) }.getOrNull()
        private val pure = method("isPure")
        private val selected = method("getTailSelectedColor")
        private val secondary = method("getSecondaryPageColor")
        private val artwork = method("getHeadBgPath")
        @Volatile private var requestedPath: String? = null
        @Volatile private var sampled: Pair<String, Int?>? = null
        private val samples = HostLatestWorkQueue(worker::execute)

        fun read(context: Context): Int? = runCatching {
            // 夜间/夜空背景下 getCurGarb 是纯黑皮肤，实际装备仍由带 Context 的入口提供。
            val value = equipped?.invoke(null, context) ?: current?.invoke(null) ?: return null
            // 缺少识别 API 时保持普通主题；纯色皮肤不算个性装扮。
            if (pure?.invoke(value) != false) return null
            val accent = selected?.invoke(value) as? Int
            if (HostGarbColors.chromatic(accent)) return accent
            val base = secondary?.invoke(value) as? Int
            if (HostGarbColors.chromatic(base)) return base
            val path = artwork?.invoke(value) as? String
            if (!path.isNullOrEmpty() && requestedPath != path) {
                requestedPath = path
                val directory = File(context.applicationContext.filesDir, "garb")
                samples.submit {
                    val color = sample(path, directory)
                    if (requestedPath == path) sampled = path to color
                }
            }
            sampled?.takeIf { it.first == path }?.second
                ?: accent?.takeIf { it ushr 24 >= 128 }
                ?: base?.takeIf { it ushr 24 >= 128 }
                ?: 0xFF8A8A8A.toInt()
        }.getOrNull()
    }

    private val readers = ConcurrentHashMap<ClassLoader, Access>()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "host-garb-color").apply { isDaemon = true } }

    fun accent(context: Context): Int? = readers.getOrPut(context.classLoader) { Access(context.classLoader) }.read(context)

    /** 图片解码只在后台进行，限定在宿主装扮目录；不下载远程资源、不读取用户相册。 */
    private fun sample(path: String, directory: File): Int? = runCatching {
        val uri = Uri.parse(path)
        if (uri.scheme != "file") return null
        val file = File(uri.path ?: return null).canonicalFile
        if (!file.path.startsWith(directory.canonicalPath + File.separator)) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 64) sample *= 2
            inSampleSize = sample
        }
        val bitmap = BitmapFactory.decodeFile(file.path, options) ?: return null
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            HostGarbColors.dominant(pixels)
        } finally { bitmap.recycle() }
    }.getOrNull()
}

/** 不依赖 Android 的配色策略，便于验证透明图片、极端主题和文字对比度。 */
internal object HostGarbColors {
    fun chromatic(color: Int?): Boolean {
        if (color == null || color ushr 24 < 128) return false
        val r = color ushr 16 and 255
        val g = color ushr 8 and 255
        val b = color and 255
        return maxOf(r, g, b) - minOf(r, g, b) >= 20
    }

    fun blend(base: Int, color: Int, fraction: Float): Int {
        fun channel(shift: Int) = (((base ushr shift and 255) * (1f - fraction) +
            (color ushr shift and 255) * fraction).toInt().coerceIn(0, 255)) shl shift
        return 0xFF000000.toInt() or channel(16) or channel(8) or channel(0)
    }

    fun readable(color: Int, surface: Int, dark: Boolean): Int {
        val target = if (dark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        for (step in 0..20) {
            val candidate = blend(color, target, step / 20f)
            if (contrast(candidate, surface) >= 4.5) return candidate
        }
        return target
    }

    fun contrast(first: Int, second: Int): Double {
        fun luminance(color: Int): Double {
            fun linear(shift: Int): Double {
                val value = (color ushr shift and 255) / 255.0
                return if (value <= .04045) value / 12.92 else Math.pow((value + .055) / 1.055, 2.4)
            }
            return .2126 * linear(16) + .7152 * linear(8) + .0722 * linear(0)
        }
        val a = luminance(first)
        val b = luminance(second)
        return (maxOf(a, b) + .05) / (minOf(a, b) + .05)
    }

    fun dominant(pixels: IntArray): Int? {
        // 按优势通道分组，避免互补色平均成灰色；透明和黑白边框不参与取色。
        val weights = LongArray(6)
        val sums = Array(6) { LongArray(3) }
        for (color in pixels) {
            if (!chromatic(color)) continue
            val r = color ushr 16 and 255
            val g = color ushr 8 and 255
            val b = color and 255
            val bin = if (r >= g && r >= b) { if (g >= b) 0 else 1 }
                else if (g >= b) { if (r >= b) 2 else 3 }
                else { if (r >= g) 4 else 5 }
            val weight = (maxOf(r, g, b) - minOf(r, g, b)).toLong() * (color ushr 24)
            weights[bin] += weight
            sums[bin][0] += r * weight; sums[bin][1] += g * weight; sums[bin][2] += b * weight
        }
        val bin = weights.indices.maxByOrNull { weights[it] } ?: return null
        val weight = weights[bin].takeIf { it > 0 } ?: return null
        return 0xFF000000.toInt() or ((sums[bin][0] / weight).toInt() shl 16) or
            ((sums[bin][1] / weight).toInt() shl 8) or (sums[bin][2] / weight).toInt()
    }
}
