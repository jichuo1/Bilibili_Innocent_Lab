package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.content.Context
import android.graphics.fonts.SystemFonts
import io.github.jichuo1.meguviz.android.extensions.AndroidVersion
import android.util.AtomicFile
import java.io.File
import org.json.JSONObject

/** Host-private, atomic style persistence. The module backup contains only the two enable switches. */
internal class SubtitleStyleStore(context: Context) {
    val file = File(context.filesDir, "innocent_lab_subtitle/style.json")
    fun read(): PlayerSubtitleStyle? = runCatching {
        val atomic = AtomicFile(file)
        atomic.openRead().use { input ->
            val bytes = input.boundedBytes(2048)
            if (bytes.size > 2048) null else PlayerSubtitleStyle.decode(String(bytes, Charsets.UTF_8))
        }
    }.getOrNull()

    fun save(style: PlayerSubtitleStyle?): Int {
        val atomic = AtomicFile(file)
        if (style == null) { atomic.delete(); return syncResources() }
        check(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true)
        val json = JSONObject(style.encode())
        SubtitleSystemFonts.resolve(style.font)?.let { json.put("fontPath", it.absolutePath) }
        val stream = atomic.startWrite()
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            throw error
        }
        return syncResources()
    }

    private fun syncResources(): Int {
        val payload = SubtitleStylePayload.read(file)
        val targets = synchronized(resources) {
            resources.entries.removeAll { it.value.parentFile?.isDirectory != true }
            resources.values.toList()
        }
        // A renderer can register on the main thread while font copying continues on the IO worker.
        return targets.count { directory -> runCatching {
                if (!directory.isDirectory) return@runCatching false
                val target = File(directory, SubtitleStylePayload.DIRECTORY)
                check(target.isDirectory || target.mkdirs())
                payload.font?.let { font ->
                    val output = AtomicFile(File(directory, checkNotNull(payload.fontEntry)))
                    val stream = output.startWrite()
                    try { font.inputStream().use { it.copyTo(stream) }; output.finishWrite(stream) }
                    catch (error: Throwable) { output.failWrite(stream); throw error }
                }
                val config = AtomicFile(File(directory, SubtitleStylePayload.CONFIG))
                val stream = config.startWrite()
                try { stream.write(payload.json.toByteArray(Charsets.UTF_8)); config.finishWrite(stream) }
                catch (error: Throwable) { config.failWrite(stream); throw error }
                true
            }.getOrDefault(false) }
    }

    companion object {
        private val resources = LinkedHashMap<String, File>()
        fun registerPrivatePackage(context: Context, sandboxPath: String) {
            val sandbox = File(sandboxPath).canonicalFile
            val parent = sandbox.parentFile ?: return
            if (sandbox.name != "sandbox" || parent.parentFile != File(context.filesDir, "cron").canonicalFile) return
            synchronized(resources) {
                if (resources.size >= 32) resources.remove(resources.keys.first())
                resources[sandbox.path] = File(parent, "res")
            }
        }
    }
}

internal object SubtitleSystemFonts {
    private val defaults = listOf("Roboto-Regular.ttf", "SysFont-Regular.ttf", "Roboto-VF.ttf", "MiSans-Regular.ttf")
    private val roots = listOf("/system/fonts/", "/product/fonts/", "/system_ext/fonts/")
    val files: List<File> by lazy {
        val available = if (AndroidVersion.isAtLeast(AndroidVersion.Q)) {
            runCatching { SystemFonts.getAvailableFonts().mapNotNull { it.file } }.getOrDefault(emptyList())
        } else roots.flatMap { File(it).listFiles()?.toList().orEmpty() }
        available.filter { file -> roots.any { file.path.startsWith(it) } &&
            PlayerSubtitleStyle.validFont(file.name) && file.isFile && file.length() in 1..50_000_000 }
            .distinctBy { it.name }.sortedWith(compareBy<File> { it.name !in defaults }.thenBy { it.name }).take(256)
    }
    fun resolve(name: String): File? {
        if (name != "sans-serif") return files.firstOrNull { it.name == name }
        return defaults.firstNotNullOfOrNull { candidate -> files.firstOrNull { it.name == candidate } }
    }
}
