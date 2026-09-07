package com.Bilibili_Innocent_Lab.xposedmodule.telemetry

import android.os.Build
import android.os.Looper
import android.os.SystemClock
import java.util.Locale
import java.util.concurrent.TimeUnit

internal data class TelemetryDeviceProfile(
    val manufacturer: String,
    val model: String,
    val rom: String
)

/** Only product labels and a ROM family leave this boundary, never raw properties. */
internal object TelemetryDevicePolicy {
    private val labelPattern = Regex("^[A-Za-z0-9\\u3400-\\u9fff][A-Za-z0-9\\u3400-\\u9fff ._()+-]{0,63}$")
    val romCodes = setOf(
        "lineageos", "hyperos", "miui", "realme_ui", "oxygenos", "coloros",
        "originos", "funtouchos", "one_ui", "emui", "harmonyos", "flyme", "unknown"
    )
    // Read only known family indicators. A vendor name alone is never ROM evidence.
    val propertyKeys = listOf(
        "ro.lineage.version", "ro.mi.os.version.name", "ro.miui.ui.version.name",
        "ro.build.version.realmeui", "ro.oxygen.version", "ro.build.version.opporom",
        "ro.vivo.os.name", "ro.build.version.oneui", "hw_sc.build.platform.version",
        "ro.build.version.emui"
    )

    fun productLabel(raw: String?, lowercase: Boolean = false): String {
        if (raw == null || raw.length > 128) return "unknown"
        val normalized = raw.trim().replace(Regex(" +"), " ")
        if (!labelPattern.matches(normalized)) return "unknown"
        return if (lowercase) normalized.lowercase(Locale.ROOT) else normalized
    }

    fun romFamily(properties: Map<String, String>, display: String?): String {
        fun present(key: String) = properties[key]?.trim()?.let {
            it.isNotEmpty() && it.length <= 128 && it != "0" &&
                !it.equals("unknown", ignoreCase = true)
        } == true
        if (present("ro.lineage.version")) return "lineageos"
        if (present("ro.mi.os.version.name")) return "hyperos"
        if (present("ro.miui.ui.version.name")) return "miui"
        if (present("ro.build.version.realmeui")) return "realme_ui"
        if (present("ro.oxygen.version")) return "oxygenos"
        if (present("ro.build.version.opporom")) return "coloros"
        val vivo = properties["ro.vivo.os.name"].orEmpty().lowercase(Locale.ROOT)
        if (vivo.contains("origin")) return "originos"
        if (vivo.contains("funtouch")) return "funtouchos"
        if (present("ro.build.version.oneui")) return "one_ui"
        if (present("hw_sc.build.platform.version")) return "harmonyos"
        if (present("ro.build.version.emui")) return "emui"
        // DISPLAY is inspected locally only and is never serialized.
        val text = display?.takeIf { it.length <= 128 }?.lowercase(Locale.ROOT).orEmpty()
        return when {
            Regex("(^|[^a-z])lineage").containsMatchIn(text) -> "lineageos"
            Regex("(^|[^a-z])flyme").containsMatchIn(text) -> "flyme"
            else -> "unknown"
        }
    }
}

internal object TelemetryDeviceCollector {
    /** Called only by the existing telemetry worker after consent; no Hook or UI-thread work. */
    fun collect(): TelemetryDeviceProfile {
        check(Looper.myLooper() != Looper.getMainLooper())
        val deadline = SystemClock.elapsedRealtime() + 1_500L
        val properties = buildMap {
            for (key in TelemetryDevicePolicy.propertyKeys) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0L) break
                readProperty(key, minOf(remaining, 150L))?.let { put(key, it) }
            }
        }
        return TelemetryDeviceProfile(
            TelemetryDevicePolicy.productLabel(Build.MANUFACTURER, lowercase = true),
            TelemetryDevicePolicy.productLabel(Build.MODEL),
            TelemetryDevicePolicy.romFamily(properties, Build.DISPLAY)
        )
    }

    /** Fixed argv, no shell/root/reflection/property dump. Each child and output are bounded. */
    private fun readProperty(key: String, timeoutMs: Long): String? {
        val process = runCatching {
            ProcessBuilder("/system/bin/getprop", key).redirectErrorStream(true).start()
        }.getOrNull() ?: return null
        return try {
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) || process.exitValue() != 0) null
            else process.inputStream.use { input ->
                val bytes = ByteArray(129)
                var count = 0
                while (count < bytes.size) {
                    val size = input.read(bytes, count, bytes.size - count)
                    if (size < 0) break
                    count += size
                }
                if (count >= bytes.size) null else String(bytes, 0, count, Charsets.UTF_8).trim()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: Exception) {
            null
        } finally {
            if (process.isAlive) process.destroyForcibly()
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
        }
    }
}
