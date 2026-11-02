package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal data class InsightSubtitleSource(val cid: Long, val url: String, val language: String)
internal data class InsightMetadata(val video: InsightVideo, val aid: Long, val title: String, val duration: Int)

/** 只保存不可变媒体数据；不保存 Activity、Cookie、宿主对象或模型凭据。 */
internal class InsightHostSnapshot {
    private var sequence = 0L
    @Synchronized fun revision(): Long = sequence
    private val subtitles = LinkedHashMap<Long, List<InsightSubtitleSource>>()
    private val official = LinkedHashMap<Long, Boolean>()
    private val metadata = LinkedHashMap<InsightVideo, InsightMetadata>()
    private val comments = LinkedHashMap<Long, List<String>>()
    private val subtitleArrivals = HashMap<Long, CountDownLatch>()
    /** 在后台等待原生字幕响应；等待时不持有快照锁，空弹幕响应不能抢先判定无字幕。 */
    fun awaitSubtitles(cid: Long, timeoutMs: Long): List<InsightSubtitleSource> {
        if (timeoutMs <= 0) return subtitles(cid)
        val arrival = synchronized(this) {
            if (subtitles[cid]?.isNotEmpty() == true) return subtitles(cid)
            subtitleArrivals.getOrPut(cid) { CountDownLatch(1) }
        }
        try { arrival.await(timeoutMs.coerceAtMost(2500), TimeUnit.MILLISECONDS) }
        finally { synchronized(this) { if (subtitleArrivals[cid] === arrival) subtitleArrivals.remove(cid) } }
        return subtitles(cid)
    }
    @Synchronized fun comments(aid: Long, values: List<String>) {
        val next = values.filter { it.isNotBlank() }.distinct().take(40).map { it.take(1000) }
        if (comments[aid] != next) sequence++
        comments[aid] = next
        while (comments.size > 16) comments.remove(comments.keys.first())
    }
    @Synchronized fun comments(aid: Long): List<String> = comments[aid].orEmpty()
    @Synchronized fun metadata(value: InsightMetadata) {
        if (metadata[value.video] != value) sequence++
        metadata[value.video] = value
        while (metadata.size > 16) metadata.remove(metadata.keys.first())
    }
    @Synchronized fun metadata(video: InsightVideo): InsightMetadata? = metadata[video]
    @Synchronized fun official(cid: Long, present: Boolean) {
        if (official[cid] != present) sequence++
        official[cid] = present
        while (official.size > 32) official.remove(official.keys.first())
    }
    @Synchronized fun official(cid: Long): Boolean = official[cid] == true
    @Synchronized fun subtitles(cid: Long, values: List<InsightSubtitleSource>) {
        if (values.isEmpty() && subtitles[cid]?.isNotEmpty() == true) return
        if (subtitles[cid] != values.take(16)) sequence++
        subtitles[cid] = values.take(16)
        if (values.isNotEmpty()) subtitleArrivals.remove(cid)?.countDown()
        while (subtitles.size > 16) subtitles.remove(subtitles.keys.first())
    }
    @Synchronized fun subtitles(cid: Long): List<InsightSubtitleSource> = subtitles[cid].orEmpty()
}

/** 独立的只读 B 站连接；绝不把模型的 Bearer 发往字幕 CDN，也不读取宿主登录态。 */
internal class InsightContextClient(private val snapshot: InsightHostSnapshot,
    private val subtitleWaitMs: Long = 2500,
    private val trace: (String) -> Unit = {},
    private val hostComments: (Long) -> List<String> = { emptyList() },
    private val request: (String) -> String = ::getPublic) {
    fun load(video: InsightVideo): InsightContext {
        val missing = mutableListOf<String>()
        val meta = runCatching { data(request("https://api.bilibili.com/x/web-interface/view?bvid=${video.bvid}")) }.getOrElse {
            val captured = snapshot.metadata(video) ?: throw it
            missing += "public-metadata-and-description"
            JSONObject().put("bvid", video.bvid).put("aid", captured.aid).put("title", captured.title)
                .put("pages", JSONArray().put(JSONObject().put("cid", video.cid).put("duration", captured.duration)))
        }
        require(meta.getString("bvid") == video.bvid)
        val aid = meta.getLong("aid"); require(aid > 0)
        val pages = meta.optJSONArray("pages") ?: JSONArray()
        val part = (0 until pages.length()).mapNotNull { pages.optJSONObject(it) }.singleOrNull { it.optLong("cid") == video.cid }
            ?: error("video-part-unavailable")
        val duration = part.getInt("duration"); require(duration > 0)
        val player = runCatching { data(request("https://api.bilibili.com/x/player/v2?aid=$aid&cid=${video.cid}")) }
            .getOrElse { missing += "player-metadata"; JSONObject() }
        val publicSources = run {
            val array = player.optJSONObject("subtitle")?.optJSONArray("subtitles") ?: JSONArray()
            (0 until array.length()).mapNotNull { array.optJSONObject(it) }.map {
                InsightSubtitleSource(video.cid, it.optString("subtitle_url"), it.optString("lan")) }
        }
        fun preferred(values: List<InsightSubtitleSource>) = values.sortedBy { if (it.language.contains("zh")) 0 else 1 }
        val captured = preferred(snapshot.awaitSubtitles(video.cid, if (publicSources.isEmpty()) subtitleWaitMs else 0))
        trace("subtitle-selection captured=${captured.size} public=${publicSources.size} cid=${video.cid}")
        val sources = (captured.take(1) + preferred(publicSources).take(2) + captured.drop(1)).distinctBy { it.url }
        var cues = emptyList<InsightCue>()
        for (source in sources.take(3)) {
            val url = subtitleUrl(source.url)
            if (url == null) {
                val host = runCatching { URL(if (source.url.startsWith("//")) "https:${source.url}" else source.url).host }
                    .getOrDefault("").take(100).filter { it.isLetterOrDigit() || it == '.' || it == '-' }
                trace("subtitle-url rejected host=$host")
                continue
            }
            cues = runCatching { parseSubtitle(request(url), duration) }.getOrElse { error ->
                if (error.message == "subtitle-context-too-large" || error.message == "public-response-too-large")
                    throw IllegalArgumentException("subtitle-context-too-large")
                trace("subtitle-load failure reason=${(error as? InsightContextHttpException)?.let { "http-${it.status}" } ?: error.javaClass.simpleName}")
                emptyList()
            }
            if (cues.isNotEmpty()) break
        }
        if (cues.isEmpty()) missing += "subtitle"
        val comments = snapshot.comments(aid).ifEmpty {
            runCatching { hostComments(aid).filter { it.isNotBlank() }.distinct().take(40) }
                .onSuccess { trace("comments-load source=host count=${it.size}") }
                .getOrElse { trace("comments-load source=host failure=${it.javaClass.simpleName}"); emptyList() }
        }.ifEmpty { runCatching {
            val reply = data(request("https://api.bilibili.com/x/v2/reply?type=1&oid=$aid&sort=2&pn=1&ps=30"))
            val array = reply.optJSONArray("replies") ?: JSONArray()
            (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optJSONObject("content")?.optString("message") }
                .filter { it.isNotBlank() }.distinct().take(40)
        }.getOrElse { missing += "comments"; emptyList() } }
        val danmaku = runCatching {
            // XML 仅做有界文本抽样；不执行外部实体/DTD，也不把弹幕时间当作字幕事实。
            Regex("<d\\s+p=\"[^\"]*\">([^<]*)</d>").findAll(request("https://comment.bilibili.com/${video.cid}.xml"))
                .take(80).map { it.groupValues[1].replace("&lt;", "<").replace("&gt;", ">")
                    .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&") }
                .filter { it.isNotBlank() }.distinct().take(40).toList()
        }.getOrElse { missing += "danmaku"; emptyList() }
        return InsightContext(video, duration, meta.getString("title"), meta.optString("desc"), cues, comments, danmaku,
            officialChapters = snapshot.official(video.cid) || (player.optJSONArray("view_points")?.length() ?: 0) > 0, missing = missing)
    }

    companion object {
        fun subtitleUrl(raw: String): String? {
            val value = if (raw.startsWith("//")) "https:$raw" else raw
            val url = runCatching { URL(value) }.getOrNull() ?: return null
            if (url.userInfo != null || url.ref != null ||
                (url.host != "hdslb.com" && !url.host.endsWith(".hdslb.com"))) return null
            // 原生字幕响应仍可能给出 HTTP CDN 地址；只升级已验证 CDN，实际连接始终走 HTTPS。
            return when {
                url.protocol == "https" && url.port in setOf(-1, 443) -> value
                url.protocol == "http" && url.port in setOf(-1, 80) -> URL("https", url.host, -1, url.file).toExternalForm()
                else -> null
            }
        }
        fun parseSubtitle(raw: String, duration: Int): List<InsightCue> {
            require(raw.length <= 2_000_000) { "subtitle-context-too-large" }
            val body = JSONObject(raw).getJSONArray("body"); require(body.length() <= 30_000) { "subtitle-context-too-large" }
            return (0 until body.length()).mapNotNull { i ->
                val item = body.optJSONObject(i) ?: return@mapNotNull null
                val start = item.optDouble("from", Double.NaN); val end = item.optDouble("to", Double.NaN)
                val text = item.optString("content").trim()
                if (start.isFinite() && end.isFinite() && start >= 0 && start < duration && end > start && text.isNotEmpty())
                    InsightCue(start, end.coerceAtMost(duration.toDouble()), text) else null
            }.distinct().sortedBy { it.start }
        }
        private fun data(raw: String): JSONObject {
            val root = JSONObject(raw); require(root.getInt("code") == 0)
            return root.getJSONObject("data")
        }
        private fun getPublic(address: String): String = getPublicBytes(address).toString(Charsets.UTF_8)
        internal fun getPublicBytes(address: String, maximum: Int = 2_000_000): ByteArray {
            val connection = URL(address).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 5_000; connection.readTimeout = 5_000; connection.useCaches = false
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 BIL-AI-Insight")
                connection.setRequestProperty("Referer", "https://www.bilibili.com/")
                if (connection.responseCode != 200) throw InsightContextHttpException(connection.responseCode)
                val deadline = System.nanoTime() + 8_000_000_000L
                val bytes = ByteArrayOutputStream()
                connection.inputStream.use { stream ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        check(System.nanoTime() < deadline)
                        val count = stream.read(buffer); if (count < 0) break
                        require(bytes.size() + count <= maximum) { "public-response-too-large" }
                        bytes.write(buffer, 0, count)
                    }
                }
                return bytes.toByteArray()
            } finally { connection.disconnect() }
        }
    }
}

private class InsightContextHttpException(val status: Int) : Exception()
