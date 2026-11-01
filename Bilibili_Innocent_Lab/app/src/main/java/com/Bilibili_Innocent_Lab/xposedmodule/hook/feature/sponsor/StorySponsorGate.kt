package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor

/** 宿主 Story 数据源游标指向的播放条目；游标可能已经走到下一个条目，不等于已装载的媒体。 */
internal data class StoryPlayableIdentity(val id: String, val aid: Long, val cid: Long,
    val vupload: Boolean, val ugc: Boolean)

/** `StoryDetail` 的只读快照；只含准入判断所需的字段。 */
internal data class StoryDetailSnapshot(val aid: Long, val cid: Long, val bvid: String, val ugc: Boolean,
    val playable: Boolean, val ad: Boolean, val hasAdInfo: Boolean)

internal sealed interface StorySponsorVerdict {
    data class Allow(val video: SponsorVideoId, val playableId: String) : StorySponsorVerdict
    data class Deny(val reason: String) : StorySponsorVerdict
}

/**
 * Story 条目是否可以查询并执行商单跳过。纯函数：失败一律关闭，不退化到只看 playable。
 *
 * playable 层的 `from == "vupload"` 且 `bizType == UGC` 不够：宿主把直播、番剧、课程、图片广告之外的
 * 条目（含视频硬广、游戏卡、预览）都映射成这一对取值，所以必须再用 `StoryDetail.isUgc()`
 * （`goto == "vertical_av"`）和广告信息把它们挡在外面。
 */
internal object StorySponsorGate {
    fun evaluate(playable: StoryPlayableIdentity?, loadedId: String?, details: List<StoryDetailSnapshot>): StorySponsorVerdict {
        playable ?: return deny("no-playable")
        if (playable.id.isEmpty() || loadedId == null || loadedId != playable.id) return deny("cursor-mismatch")
        if (!playable.vupload || !playable.ugc) return deny("non-ugc")
        if (playable.aid <= 0 || playable.cid <= 0) return deny("invalid-identity")
        val bvid = SponsorVideoId.bvidFromAvid(playable.aid) ?: return deny("invalid-aid")
        val matches = details.filter { it.aid == playable.aid && it.cid == playable.cid }
        if (matches.isEmpty()) return deny("no-detail")
        for (detail in matches) {
            if (detail.ad || detail.hasAdInfo) return deny("ad")
            if (!detail.ugc || !detail.playable) return deny("non-ugc-detail")
            if (detail.bvid.isNotEmpty() && detail.bvid != bvid) return deny("bvid-mismatch")
        }
        val video = runCatching { SponsorVideoId(bvid, playable.cid) }.getOrNull() ?: return deny("invalid-bvid")
        return StorySponsorVerdict.Allow(video, playable.id)
    }

    private fun deny(reason: String) = StorySponsorVerdict.Deny(reason)
}

/** 滑动窗口内最多放行 [limit] 次查询，窗口外的请求直接拒绝，不触达网络。 */
internal class StoryLookupBudget(private val limit: Int, private val windowMs: Long, private val now: () -> Long) {
    private val stamps = ArrayDeque<Long>()

    @Synchronized fun tryAcquire(): Boolean {
        val current = now()
        while (stamps.isNotEmpty() && current - stamps.first() >= windowMs) stamps.removeFirst()
        if (stamps.size >= limit) return false
        stamps.addLast(current)
        return true
    }
}
