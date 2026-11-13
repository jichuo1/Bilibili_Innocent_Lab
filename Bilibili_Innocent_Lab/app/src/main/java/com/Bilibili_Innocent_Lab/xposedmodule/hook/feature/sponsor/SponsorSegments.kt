package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor

import org.json.JSONArray
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToLong

internal data class SponsorVideoId(val bvid: String, val cid: Long) {
    init { require(BVID.matches(bvid) && cid > 0) }
    val hashPrefix: String get() = MessageDigest.getInstance("SHA-256")
        .digest(bvid.toByteArray(Charsets.UTF_8)).take(2).joinToString("") { "%02x".format(it) }

    companion object {
        private val BVID = Regex("BV[0-9A-Za-z]{10}")
        private const val MAX_AID = 2_251_799_813_685_248L
        private const val XOR_CODE = 23_442_827_791_579L
        private const val ALPHABET = "FcwAPNKTMug3GV5Lj7EJnHpWsx4tb8haYeviqBz6rkCy12mUSDQX9RdoZf"

        /** 与 BilibiliSponsorBlock 的 bvidAvidUtils 相同的编号映射，不请求额外接口。 */
        fun bvidFromAvid(avid: Long): String? {
            if (avid <= 0 || avid >= MAX_AID) return null
            val chars = "BV1000000000".toCharArray()
            var value = (MAX_AID or avid) xor XOR_CODE
            for (index in 11 downTo 3) {
                chars[index] = ALPHABET[(value % 58).toInt()]
                value /= 58
            }
            val third = chars[3]; chars[3] = chars[9]; chars[9] = third
            val fourth = chars[4]; chars[4] = chars[7]; chars[7] = fourth
            return String(chars)
        }
    }
}

internal data class SponsorSegment(val uuid: String, val startMs: Long, val endMs: Long, val declaredDurationMs: Long) {
    fun fits(durationMs: Long): Boolean = durationMs in 1..Int.MAX_VALUE.toLong() &&
        startMs >= 0 && startMs < endMs && endMs <= durationMs &&
        (declaredDurationMs == 0L || abs(declaredDurationMs - durationMs) <= 2_000L)
}

internal sealed interface SponsorFetchResult {
    /** [origin] 为空表示刚从网络解析；`disk` 是未过期的本地缓存，`disk-stale` 是联网失败后退回的较旧缓存。 */
    data class Available(val segments: List<SponsorSegment>, val summary: SponsorParseSummary? = null,
        val origin: String = "") : SponsorFetchResult
    data class Unavailable(val reason: String, val retryAfterMs: Long = 0) : SponsorFetchResult
}

internal data class SponsorParseSummary(val total: Int, val category: Int, val action: Int, val cid: Int, val invalid: Int)

/** 只接受普通商单 skip；CID 不匹配时不使用时长兜底，不收集描述或投稿身份。 */
internal object SponsorSegmentParser {
    private val UUID = Regex("[A-Za-z0-9_-]{1,128}")
    fun parse(body: String, video: SponsorVideoId): List<SponsorSegment> = parseDetailed(body, video).segments

    fun parseDetailed(body: String, video: SponsorVideoId): SponsorFetchResult.Available {
        val buckets = JSONArray(body)
        require(buckets.length() <= 256) { "response-budget" }
        val matches = (0 until buckets.length()).mapNotNull { buckets.optJSONObject(it) }
            .filter { it.optString("videoID") == video.bvid }
        require(matches.size <= 1) { "duplicate-video" }
        val array = matches.singleOrNull()?.getJSONArray("segments")
            ?: return SponsorFetchResult.Available(emptyList(), SponsorParseSummary(0, 0, 0, 0, 0))
        require(array.length() <= 512) { "segment-budget" }
        val result = LinkedHashMap<String, SponsorSegment>()
        var category = 0; var action = 0; var cid = 0; var invalid = 0
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index)
            if (item == null) { invalid++; continue }
            if (item.optString("category") != "sponsor") { category++; continue }
            if (item.optString("actionType") != "skip") { action++; continue }
            if (item.opt("cid")?.toString()?.toLongOrNull() != video.cid) { cid++; continue }
            val uuid = item.optString("UUID").takeIf(UUID::matches)
            val range = item.optJSONArray("segment")?.takeIf { it.length() == 2 }
            val start = range?.let { millis(it.opt(0)) }
            val end = range?.let { millis(it.opt(1)) }
            val duration = millis(item.opt("videoDuration") ?: 0)
            if (uuid == null || start == null || end == null || duration == null ||
                start >= end || (duration > 0 && end > duration + 2_000)) { invalid++; continue }
            val segment = SponsorSegment(uuid, start, end, duration)
            val previous = result.putIfAbsent(uuid, segment)
            require(previous == null || previous == segment) { "conflicting-segment" }
        }
        return SponsorFetchResult.Available(result.values.sortedBy { it.startMs },
            SponsorParseSummary(array.length(), category, action, cid, invalid))
    }

    private fun millis(value: Any?): Long? {
        val seconds = (value as? Number)?.toDouble() ?: return null
        if (!seconds.isFinite() || seconds < 0 || seconds > Int.MAX_VALUE / 1000.0) return null
        return (seconds * 1_000).roundToLong().takeIf { it <= Int.MAX_VALUE }
    }
}
