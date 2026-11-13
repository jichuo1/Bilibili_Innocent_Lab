package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

/** 从动态自己的文字提取可在同一账号内复核的线索。单条动态本身不产生分数。 */
internal object FishDynamicStyle {
    data class Evidence(
        val introFingerprint: Int?,
        val composedIntro: Boolean,
        val narratedTimeline: Boolean,
        val browsingIntro: Boolean
    )

    private const val MAX_TEXT = 1_200
    private const val MAX_TIME_CODES = 16
    private const val MAX_NARRATION_LENGTH = 150
    private const val MIN_NARRATION_LENGTH = 6
    private const val MIN_NARRATED_TIMES = 3

    private val BRACKETED_INTRO = Regex("^\\s*[【\\[]([^】\\]\\r\\n]{4,64})[】\\]]")
    private val INTRO_DELIVERY = Regex("(?:视频|片段|视频安利|今日份).{0,6}(?:递上|送上|奉上|来啦|来了)")
    private val INTRO_GREETING = Regex("早(?:晨|上|安)|午(?:后|安)|晚(?:上|安)|今天|今日|[!！~～]")
    private val DURATION = Regex("^\\s*[0-9]{1,3}\\s*分\\s*[0-5]?[0-9]\\s*秒\\s*[,，。:：]")
    private val TIME = Regex("(?<![0-9:：])([0-9]{1,3})\\s*(?:分\\s*([0-5]?[0-9])\\s*秒|[:：]([0-5][0-9]))(?![0-9:：])")
    private val NARRATION = Regex(
        "镜头|画面|片中|视频里|接着|然后|最后|开头|转到|切到|出现|展示|演示|说(?:道|到|了)|讲(?:到|了)|" +
            "介绍|先.{1,10}再|给出|看到|切换|转向|开始|进入|揭晓|对比|提到|测试|跑出"
    )
    private val BROWSING = Regex("^\\s*(?:我|刚刚|刚才|今天|早上|昨晚|刚){1,3}\\s*(?:刷到|看到|刷见|刷出来)")
    private val VIDEO_CONTEXT = Regex("视频|镜头|画面|片段|bvid|bv[0-9a-z]{10}|bilibili\\.com")

    fun analyze(text: String): Evidence {
        val normalized = TextNormalizer.forMatching(text.take(MAX_TEXT)).trimStart()
        val intro = BRACKETED_INTRO.find(normalized)
        val body = intro?.let { normalized.substring(it.range.last + 1) } ?: normalized
        val opening = intro?.groupValues?.get(1).orEmpty()
        val delivery = INTRO_DELIVERY.containsMatchIn(opening)
        val duration = DURATION.find(body)
        val narration = duration?.let { body.substring(it.range.last + 1) } ?: body
        val introBody = narration.take(MAX_NARRATION_LENGTH)
        val composed = delivery && INTRO_GREETING.containsMatchIn(opening) && duration != null &&
            (narrated(introBody) || introBody.count(Char::isLetter) >= 16)
        val timeline = hasNarratedTimeline(narration)
        val browsing = BROWSING.containsMatchIn(body) &&
            (composed || timeline || (delivery && VIDEO_CONTEXT.containsMatchIn(body)))
        val fingerprint = if (delivery) {
            opening.filter(Char::isLetter).takeIf { it.length >= 4 }?.hashCode()
        } else null
        return Evidence(fingerprint, composed, timeline, browsing)
    }

    private fun hasNarratedTimeline(text: String): Boolean {
        val times = TIME.findAll(text).take(MAX_TIME_CODES).toList()
        if (times.size < MIN_NARRATED_TIMES) return false
        var previous = -1
        var count = 0
        for ((index, time) in times.withIndex()) {
            val minute = time.groupValues[1].toInt()
            val second = time.groupValues[2].ifEmpty { time.groupValues[3] }.toInt()
            val offset = minute * 60 + second
            val start = time.range.last + 1
            val end = minOf(times.getOrNull(index + 1)?.range?.first ?: text.length, start + MAX_NARRATION_LENGTH)
            if (!narrated(text.substring(start, end))) continue
            count = if (offset > previous) count + 1 else 1
            previous = offset
            if (count >= MIN_NARRATED_TIMES) return true
        }
        return false
    }

    private fun narrated(text: String): Boolean =
        text.count(Char::isLetter) >= MIN_NARRATION_LENGTH && NARRATION.containsMatchIn(text)
}
