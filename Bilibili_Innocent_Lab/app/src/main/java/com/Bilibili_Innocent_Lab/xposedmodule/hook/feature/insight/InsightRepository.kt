package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight

import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.AgentModelClient
import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.AgentModelSource
import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.AgentModelException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** 只存生成结果，不持久化原始字幕、评论、问题或凭据。按视频+分 P+模型+协议隔离。 */
internal class InsightCache(private val directory: File, private val clock: () -> Long = System::currentTimeMillis) {
    data class Entry(val output: InsightOutput, val duration: Int, val timed: Boolean)
    private val memory = object : LinkedHashMap<String, Entry>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > 32
    }
    @Synchronized fun read(key: String, mode: InsightMode): Entry? {
        // 内存命中也检查落盘时间，评论不会在长驻进程里永久过期不了。
        val file = file(key)
        if (!file.isFile || file.length() > 80_000 || clock() - file.lastModified() !in 0..ttl(mode)) {
            memory.remove(key); return null
        }
        memory[key]?.let { return it }
        return runCatching {
            val root = JSONObject(file.readText())
            require(root.getString("key") == key && root.getInt("version") == InsightProtocol.VERSION)
            val chapters = root.getJSONArray("chapters")
            require(chapters.length() <= 12)
            Entry(InsightOutput(root.getString("text").also { require(it.length <= InsightProtocol.MAX_OUTPUT) }
                .let { if (mode == InsightMode.COMMENTS) InsightCommentText.cleanLegacy(it) else it },
                (0 until chapters.length()).map { i -> chapters.getJSONObject(i).let {
                    InsightChapter(it.getInt("start"), it.getString("title")) } }),
                root.getInt("duration"), root.getBoolean("timed")).also { memory[key] = it }
        }.getOrNull()
    }
    @Synchronized fun write(key: String, entry: Entry) {
        require(entry.output.text.length <= InsightProtocol.MAX_OUTPUT && entry.output.chapters.size <= 12)
        check(directory.isDirectory || directory.mkdirs())
        val json = JSONObject().put("key", key).put("version", InsightProtocol.VERSION)
            .put("text", entry.output.text).put("duration", entry.duration).put("timed", entry.timed)
            .put("chapters", JSONArray(entry.output.chapters.map { JSONObject().put("start", it.start).put("title", it.title) }))
        val target = file(key)
        val temporary = File.createTempFile("insight-", ".tmp", directory)
        try {
            temporary.writeText(json.toString())
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            target.setLastModified(clock())
            memory[key] = entry
        } finally { temporary.delete() }
        directory.listFiles()?.filter { it.extension == "json" }?.sortedByDescending { it.lastModified() }
            ?.drop(200)?.forEach { it.delete() }
    }
    fun restoreChapters(source: AgentModelSource, accept: (InsightVideo, Entry) -> Unit) {
        directory.listFiles()?.filter { it.extension == "json" && it.length() <= 80_000 }?.take(200)?.forEach { file ->
            runCatching {
                val key = JSONObject(file.readText()).getString("key")
                val parts = key.split('/'); if (parts.size < 4 || parts[2] != InsightMode.CHAPTERS.name) return@runCatching
                val videoPart = parts[1].split('-'); if (videoPart.size != 2) return@runCatching
                val video = InsightVideo(videoPart[0], videoPart[1].toLong())
                if (key == InsightCache.key(video, source, InsightMode.CHAPTERS)) read(key, InsightMode.CHAPTERS)?.let { accept(video, it) }
            }
        }
    }
    private fun file(key: String) = File(directory, "${digest(key)}.json")
    private fun ttl(mode: InsightMode) = if (mode == InsightMode.COMMENTS) 60 * 60_000L else 90 * 24 * 60 * 60_000L
    companion object {
        fun digest(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) }
        fun key(video: InsightVideo, source: AgentModelSource, mode: InsightMode): String =
            "${InsightProtocol.VERSION}/${video.key}/${mode.name}/${digest(source.resolvedEndpoint + '\u0000' + source.model)}"
    }
}

/** 调用方使用一个有界后台队列；缓存先于上下文加载，二次打开不请求 B 站或模型。 */
internal class InsightRepository(
    private val source: AgentModelSource, private val cache: InsightCache,
    private val context: (InsightVideo) -> InsightContext,
    private val client: AgentModelClient = AgentModelClient(outputTokenBudget = 8192,
        inputTextLimit = InsightProtocol.MAX_CONTEXT, allowReasoningParams = false),
    private val trace: (String) -> Unit = {},
    private val contextRevision: () -> Long = { 0L },
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS
) {
    private var loaded: InsightContext? = null
    private var loadedAt = 0L
    private var loadedRevision = -1L
    // 缓存读取不拿生成锁；慢模型请求不能阻挡另一个视频的缓存命中。
    fun cached(video: InsightVideo, mode: InsightMode): InsightCache.Entry? =
        if (mode == InsightMode.ASK) null else cache.read(InsightCache.key(video, source, mode), mode)
            ?.also { trace("cache mode=$mode timed=${it.timed}") }
    @Synchronized fun generate(video: InsightVideo, mode: InsightMode, question: String = "",
        history: List<Pair<String, String>> = emptyList(), onProgress: ((String) -> Unit)? = null,
        cancelled: () -> Boolean = { false }): InsightCache.Entry {
        var stage = "context"
        try {
            check(!cancelled())
            val key = InsightCache.key(video, source, mode)
            if (mode != InsightMode.ASK) cache.read(key, mode)?.let { return it }
            val now = System.currentTimeMillis()
            val revision = contextRevision()
            val input = loaded?.takeIf { it.video == video && loadedRevision == revision && now - loadedAt in 0..300_000L &&
                (mode != InsightMode.COMMENTS || it.comments.isNotEmpty()) }
                ?: context(video).also { loaded = it; loadedAt = now; loadedRevision = revision }
            trace("context mode=$mode cues=${input.cues.size} comments=${input.comments.size} danmaku=${input.danmaku.size} official=${input.officialChapters}")
            check(!cancelled())
            if (mode == InsightMode.COMMENTS) require(input.comments.isNotEmpty()) { "no-comments" }
            if (mode == InsightMode.CHAPTERS) require(input.timed && !input.officialChapters &&
                input.duration >= InsightProtocol.MIN_CHAPTER_VIDEO_SECONDS) { "chapters-unavailable" }
            if (mode == InsightMode.CHAPTERS) {
                // 总结分段就是章节唯一来源；先点章节也复用同一次生成与缓存。
                val summary = generate(video, InsightMode.SUMMARY, cancelled = cancelled)
                require(summary.output.chapters.size in 3..8) { "chapters-unavailable" }
                cache.write(key, summary)
                return summary
            }
            stage = "prompt"
            val prompt = InsightProtocol.prompt(mode, input, question, history)
            trace("request mode=$mode cues=${if (mode == InsightMode.COMMENTS) 0 else InsightProtocol.validCues(input).size} chars=${prompt.length} bytes=${prompt.toByteArray(Charsets.UTF_8).size}")
            val original = JSONArray().put(JSONObject().put("role", "system").put("content", InsightProtocol.system(mode, input)))
                .put(JSONObject().put("role", "user").put("content", prompt))
            var messages = original
            var decoded: InsightOutput? = null
            for (attempt in 0..1) {
                stage = "model"
                // 每次生成独立计时；修正重放不能只拿到上一轮的残余时间。
                val remaining = timeoutMs
                // 修正重放不覆盖第一次已经显示给用户的正文，也不发送增量预览。
                val preview = if (attempt == 0) onProgress?.let { InsightStreamText(mode, input) } else null
                var previous = ""
                val turn = client.generateStructuredStreaming(source, messages, InsightProtocol.schema(mode, input), remaining, cancelled) { delta ->
                    check(!cancelled())
                    if (preview != null) {
                        val text = preview.append(delta)
                        if (text != previous) { previous = text; onProgress?.invoke(text) }
                    }
                }
                check(!cancelled())
                stage = "decode"
                try {
                    decoded = decodeChecked(mode, turn.text, input)
                    break
                } catch (invalid: InsightValidationException) {
                    // 结构或 JSON 完全认不出时不重放，避免为明显无效的输出再付一次模型请求。
                    if (attempt != 0 || cancelled() || invalid.rule in NON_REPAIRABLE_RULES) throw invalid
                    trace("repair mode=$mode rule=${invalid.rule}")
                    // 不清空预览：第一次的正文留在面板上，直到修正结果或明确失败为止。
                    messages = JSONArray(original.toString()).put(JSONObject().put("role", "user")
                        .put("content", "本地校验拒绝上次输出：${invalid.hint} 请据原材料修正输出。"))
                }
            }
            val result = InsightCache.Entry(checkNotNull(decoded), input.duration, input.timed)
            if (mode != InsightMode.ASK) cache.write(key, result)
            trace("success mode=$mode timed=${result.timed}")
            return result
        } catch (error: Exception) {
            // 只记固定阶段、异常类型与枚举；不把字幕、问题、模型正文或凭据写入日志。
            val reason = (error as? AgentModelException)?.let { "${it.reason}:${it.status}" }
                ?: (error as? InsightValidationException)?.rule
                ?: error.message?.takeIf { it in setOf("insight-shape", "insight-tags", "insight-reference", "insight-bare-time", "insight-point-content", "no-comments", "chapters-unavailable", "subtitle-context-too-large") }
                ?: error.javaClass.simpleName
            trace("failure mode=$mode stage=$stage reason=$reason")
            throw error
        }
    }

    /** 所有模型输出契约问题统一成可修正的校验异常，避免只有一种异常能进入修正流程。 */
    private fun decodeChecked(mode: InsightMode, raw: String, input: InsightContext): InsightOutput = try {
        InsightProtocol.decode(mode, raw, input)
    } catch (invalid: InsightValidationException) {
        throw invalid
    } catch (invalid: IllegalArgumentException) {
        throw InsightValidationException("invalid-contract", "输出须为符合字段、数量与长度要求的 JSON。")
    } catch (invalid: IllegalStateException) {
        throw InsightValidationException("invalid-response", "输出不是本任务要求的结构化 JSON。")
    } catch (invalid: org.json.JSONException) {
        throw InsightValidationException("invalid-json", "输出不是合法 JSON。")
    }

    companion object {
        /** 单次生成的时限；两次生成各自独立计时，不再是共享的 90 秒。 */
        const val DEFAULT_TIMEOUT_MS = 180_000
        /** 结构或 JSON 完全无法识别时直接失败，不做修正重放。 */
        private val NON_REPAIRABLE_RULES = setOf("invalid-response", "invalid-json")
    }
}
