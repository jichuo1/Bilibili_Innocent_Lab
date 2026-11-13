package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor

/**
 * 在网络数据源前面加一层本地缓存，只在仓库的抓取线程上运行（磁盘读写不进主线程和宿主 Hook）。
 *
 * - 缓存未过期（[FRESH_MS] 内）：直接用，不联网——少发请求，断网也能识别查过的视频。
 * - 缓存较旧：先联网拿最新数据；联网失败（超时、DNS、TLS、5xx、限流……）才退回这份旧缓存，上限 [STALE_MAX_MS]。
 * - 联网成功且有区间：覆盖写入；联网明确说“没有区间”：删掉旧缓存，以网络为准。
 * - 被取消的请求不退回缓存（会话已经换了），没有缓存仓库时原样透传。
 */
internal class SponsorCachingSource(
    private val network: SponsorSegmentSource,
    private val store: SponsorSegmentStore?,
    private val clock: () -> Long = System::currentTimeMillis
) : SponsorSegmentSource {
    override fun fetch(video: SponsorVideoId, cancellation: SponsorRequestCancellation): SponsorFetchResult {
        var cached = store?.read(video)
        val age = cached?.let { clock() - it.savedAtMs }
        // 时钟倒退或过旧的条目不可信：删掉，当作没有缓存。
        if (cached != null && (age == null || age < 0 || age > STALE_MAX_MS)) {
            store?.delete(video)
            cached = null
        }
        if (cached != null && age != null && age < FRESH_MS) {
            return SponsorFetchResult.Available(cached.segments, origin = "disk")
        }
        val result = network.fetch(video, cancellation)
        when (result) {
            is SponsorFetchResult.Available -> {
                if (result.segments.isNotEmpty()) store?.write(video, result.segments)
                else if (cached != null) store?.delete(video)
            }
            is SponsorFetchResult.Unavailable -> if (cached != null && result.reason != "cancelled") {
                return SponsorFetchResult.Available(cached.segments, origin = "disk-stale")
            }
        }
        return result
    }

    companion object {
        const val FRESH_MS = 6L * 3_600_000
        const val STALE_MAX_MS = 14L * 86_400_000
    }
}
