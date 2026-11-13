package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 商单区间的本地缓存：宿主私有 `files/bil_sponsor/<摘要>.json`，只存“有区间”的视频，联网失败时的回退。
 *
 * - 文件名和内容里的校验键都是 `sha256(bvid:cid)` 的单向摘要，磁盘上不出现 BVID，也不记录观看顺序以外的任何信息。
 * - 只在后台线程（仓库的抓取线程、清理线程）调用；绝不能出现在宿主 Hook 回调或主线程上。
 * - 读写失败一律当作“没有缓存”，不影响联网结果；单文件有大小上限，总条数封顶，写入先临时文件再改名。
 * - 条目只信自己写的格式：版本、校验键、区间字段逐项核验，不合格的文件当场删除。
 */
internal class SponsorSegmentStore(root: File, private val clock: () -> Long = System::currentTimeMillis) {
    internal class Entry(val segments: List<SponsorSegment>, val savedAtMs: Long)

    private val directory = File(root, DIRECTORY)

    fun read(video: SponsorVideoId): Entry? {
        val file = fileFor(video)
        return runCatching {
            if (!file.isFile) return null
            val entry = if (file.length() in 1..MAX_FILE_BYTES) parse(file.readText(Charsets.UTF_8), keyOf(video), clock()) else null
            if (entry == null) file.delete()
            entry
        }.getOrNull()
    }

    fun write(video: SponsorVideoId, segments: List<SponsorSegment>) {
        if (segments.isEmpty() || segments.size > MAX_SEGMENTS) return
        runCatching {
            if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) return
            val file = fileFor(video)
            val temp = File(directory, file.name + "." + Thread.currentThread().id + TEMP_SUFFIX)
            val now = clock()
            temp.writeText(encode(keyOf(video), now, segments), Charsets.UTF_8)
            temp.setLastModified(now) // 淘汰按写入时间；改名保留修改时间
            if (!temp.renameTo(file)) {
                file.delete()
                if (!temp.renameTo(file)) temp.delete()
            }
            trim()
        }
    }

    fun delete(video: SponsorVideoId) { runCatching { fileFor(video).delete() } }

    /** 功能关闭后清空：只删目录里的普通文件，再删目录本身；不递归、不跟随目录链接。 */
    fun purge() {
        runCatching {
            directory.listFiles()?.forEach { if (it.isFile) it.delete() }
            directory.delete()
        }
    }

    @Synchronized private fun trim() {
        val files = directory.listFiles() ?: return
        val cutoff = clock() - TEMP_MAX_AGE_MS
        files.filter { it.name.endsWith(TEMP_SUFFIX) && it.lastModified() < cutoff }.forEach { it.delete() }
        val entries = files.filter { it.isFile && it.name.endsWith(SUFFIX) }
        if (entries.size > MAX_ENTRIES) {
            entries.sortedBy { it.lastModified() }.take(entries.size - MAX_ENTRIES).forEach { it.delete() }
        }
    }

    private fun fileFor(video: SponsorVideoId) = File(directory, keyOf(video) + SUFFIX)

    companion object {
        const val DIRECTORY = "bil_sponsor"
        const val MAX_ENTRIES = 256
        const val MAX_SEGMENTS = 512
        const val MAX_FILE_BYTES = 64L * 1024
        private const val VERSION = 1
        private const val SUFFIX = ".json"
        private const val TEMP_SUFFIX = ".tmp"
        private const val TEMP_MAX_AGE_MS = 600_000L
        private const val FUTURE_TOLERANCE_MS = 86_400_000L
        private val UUID = Regex("[A-Za-z0-9_-]{1,128}")

        /** 32 位十六进制：既是文件名也是文件内的校验键，换名复制的文件读不出来。 */
        internal fun keyOf(video: SponsorVideoId): String = MessageDigest.getInstance("SHA-256")
            .digest("sponsor-cache-v$VERSION:${video.bvid}:${video.cid}".toByteArray(Charsets.UTF_8))
            .take(16).joinToString("") { "%02x".format(it) }

        internal fun encode(key: String, savedAtMs: Long, segments: List<SponsorSegment>): String = JSONObject()
            .put("v", VERSION).put("k", key).put("t", savedAtMs)
            .put("s", JSONArray().also { array ->
                segments.forEach { array.put(JSONArray().put(it.uuid).put(it.startMs).put(it.endMs).put(it.declaredDurationMs)) }
            }).toString()

        internal fun parse(text: String, key: String, now: Long = System.currentTimeMillis()): Entry? = runCatching {
            val root = JSONObject(text)
            if (root.optInt("v", -1) != VERSION || root.optString("k") != key) return null
            val savedAt = root.getLong("t")
            if (savedAt <= 0 || savedAt > now + FUTURE_TOLERANCE_MS) return null
            val array = root.getJSONArray("s")
            if (array.length() !in 1..MAX_SEGMENTS) return null
            val segments = (0 until array.length()).map { index ->
                val item = array.getJSONArray(index)
                if (item.length() != 4) return null
                val uuid = item.getString(0)
                val start = item.getLong(1); val end = item.getLong(2); val declared = item.getLong(3)
                if (!UUID.matches(uuid) || start < 0 || start >= end || end > Int.MAX_VALUE || declared < 0 ||
                    declared > Int.MAX_VALUE) return null
                SponsorSegment(uuid, start, end, declared)
            }
            Entry(segments.distinctBy { it.uuid }.sortedBy { it.startMs }, savedAt)
        }.getOrNull()
    }
}
