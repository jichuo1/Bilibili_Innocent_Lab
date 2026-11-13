package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

/**
 * 同一稿件内跨账号、跨主楼复述具体事实。响应层采样，列表判定只读发布后的分数。
 *
 * 首版识别「缺少某个参数，仍默认或反复给出同一个量化结果」这种关系。
 * 共享一个数字、单位或主题词不足以成组。内存中只保留评论身份与结构化特征。
 */
internal class FishPeerEcho(private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 }) {
    data class Sample(val rpid: Long, val mid: Long, val root: Long, val message: String)

    @Volatile var snapshot: Map<Long, Map<Long, Int>> = emptyMap()
        private set

    private val videos = LinkedHashMap<Long, VideoWindow>()

    @Synchronized
    fun observe(aid: Long, samples: List<Sample>) {
        if (aid <= 0 || samples.isEmpty()) return
        val now = clockMillis()
        videos.values.removeAll { now - it.updatedAt >= WINDOW_MILLIS }
        val window = videos[aid] ?: VideoWindow(now)
        var changed = false
        for (sample in samples.take(MAX_BATCH)) {
            if (sample.rpid <= 0 || sample.mid <= 0 || sample.root <= 0 || window.comments.containsKey(sample.rpid)) continue
            window.comments[sample.rpid] = Evidence(sample.rpid, sample.mid, sample.root, facts(sample.message))
            if (window.comments.size > MAX_COMMENTS) window.comments.remove(window.comments.keys.first())
            changed = true
        }
        if (changed) {
            window.updatedAt = now
            videos.remove(aid)
            videos[aid] = window
            if (videos.size > MAX_VIDEOS) videos.remove(videos.keys.first())
        }

        val points = if (changed) scores(window.comments.values) else snapshot[aid].orEmpty()
        if (snapshot.keys.all { it in videos } && points == snapshot[aid].orEmpty()) return
        snapshot = snapshot.filterKeys { it in videos }.toMutableMap().apply {
            if (points.isEmpty()) remove(aid) else put(aid, points)
        }
    }

    private class VideoWindow(var updatedAt: Long) {
        val comments = LinkedHashMap<Long, Evidence>()
    }

    private data class Evidence(val rpid: Long, val mid: Long, val root: Long, val facts: Map<Fact, Int>)
    private data class Fact(val parameter: Parameter, val thousandths: Int)

    private enum class Parameter(vararg val units: String) {
        FRAME_RATE("fps", "帧"),
        FRAME_COUNT("帧"),
        REFRESH_RATE("hz", "赫兹"),
        DURATION("s", "秒"),
        LATENCY("ms", "毫秒"),
        POWER("w", "瓦"),
        BITRATE("mbps", "kbps");

        companion object {
            fun from(value: String): Parameter? = when (value) {
                "帧率", "帧速率", "fps" -> FRAME_RATE
                "帧数" -> FRAME_COUNT
                "刷新率" -> REFRESH_RATE
                "时长", "持续时间" -> DURATION
                "延迟", "响应时间" -> LATENCY
                "功耗", "功率" -> POWER
                "码率", "比特率" -> BITRATE
                else -> null
            }
        }
    }

    companion object {
        const val MAX_POINTS = 12
        private const val MIN_POINTS = 8
        private const val MAX_VIDEOS = 4
        private const val MAX_COMMENTS = 256
        private const val MAX_BATCH = 128
        private const val WINDOW_MILLIS = 15 * 60 * 1_000L
        private const val MAX_TEXT = 400
        private const val MAX_FACTS = 3
        private const val MAX_TRANSITION = 32
        private const val MAX_WITNESSES = 5

        private val OMITTED_PARAMETER = Regex(
            "(?:没有|没|未|不)(?:明确)?(?:提及|提到|提|指定|说明|说|写|给出|给|要求|设置|设定|设|定义)" +
                "(?:任何|具体的?)?\\s*(帧速率|帧率|帧数|fps|刷新率|持续时间|时长|响应时间|延迟|功耗|功率|比特率|码率)"
        )
        private val MEASUREMENT = Regex(
            "(?<![0-9a-z.])([0-9]{1,5}(?:\\.[0-9]{1,3})?|[一二两三四五六七八九十]{1,3})[ \\t]*" +
                "(fps|帧|hz|赫兹|毫秒|ms|秒|s|瓦|w|mbps|kbps)(?![a-z0-9])"
        )
        private val TRANSITION = Regex(
            "[\\s,，:：、]*(?:(?:的情况下|的时候|情况下|之后|时|结果|于是|然后|也|却|仍然|还是)[\\s,，]*){0,3}" +
                "(?:(?:连续|一连|连着)?(?:[0-9]{1,2}|[两二三四五六七八九十多几])[ \\t]*" +
                "(?:次|回|道(?:题)?|个(?:题|测试)?)[\\s,，]*)?" +
                "((?:每次|全都|全|都|总是|统一|照样|就|直接|默认|自动){1,3})" +
                "(给(?:出|了)?|返回(?:了)?|生成(?:了)?|输出(?:了)?|设(?:为|成)|就是|是|用(?:了)?)?\\s*"
        )
        private val LEADING_REPLY = Regex("^\\s*回复\\s*@[^\\s@:：]{1,24}\\s*[:：]?")

        private fun facts(message: String): Map<Fact, Int> {
            val text = TextNormalizer.forMatching(message.take(MAX_TEXT))
                .replace(LEADING_REPLY, "")
                .lineSequence().filterNot { it.trimStart().startsWith('>') }.joinToString("\n")
            val found = LinkedHashMap<Fact, Int>()
            for (omitted in OMITTED_PARAMETER.findAll(text)) {
                val parameter = Parameter.from(omitted.groupValues[1]) ?: continue
                val start = omitted.range.last + 1
                val result = MEASUREMENT.find(text, start) ?: break
                val end = result.range.first
                if (end - start > MAX_TRANSITION || result.groupValues[2] !in parameter.units) continue
                val connection = TRANSITION.matchEntire(text.substring(start, end)) ?: continue
                if (connection.groups[2] == null && "默认" !in connection.groupValues[1]) continue
                val quantity = quantity(result.groupValues[1]) ?: continue
                // 统一量值和单位；帧数与帧率等不同参数仍各自成组。
                val amount = if (result.groupValues[2] == "mbps") quantity * 1_000L else quantity.toLong()
                if (amount > Int.MAX_VALUE) continue
                val fact = Fact(parameter, amount.toInt())
                val wording = text.substring(omitted.range.first, end)
                    .filter(Char::isLetterOrDigit).hashCode()
                found.putIfAbsent(fact, wording)
                if (found.size >= MAX_FACTS) break
            }
            return found
        }

        private fun quantity(value: String): Int? {
            if (value[0] in '0'..'9') {
                val whole = value.substringBefore('.').toIntOrNull() ?: return null
                val fraction = value.substringAfter('.', "").padEnd(3, '0').toIntOrNull() ?: return null
                return whole * 1_000 + fraction
            }
            fun digit(char: Char): Int? = if (char == '两') 2 else "一二三四五六七八九".indexOf(char)
                .takeIf { it >= 0 }?.plus(1)
            if (value.length == 1 && value[0] != '十') return digit(value[0])?.times(1_000)
            val tens = value.indexOf('十')
            if (tens !in 0..1 || value.length - tens > 2) return null
            val high = if (tens == 0) 1 else digit(value[0]) ?: return null
            val low = if (value.length == tens + 1) 0 else digit(value.last()) ?: return null
            return (high * 10 + low) * 1_000
        }

        private fun scores(evidence: Collection<Evidence>): Map<Long, Int> {
            val groups = HashMap<Fact, MutableList<Evidence>>()
            for (comment in evidence) for (fact in comment.facts.keys) {
                groups.getOrPut(fact) { ArrayList() } += comment
            }
            val scores = HashMap<Long, Int>()
            for ((fact, group) in groups) {
                if (group.size < 3) continue
                val witnesses = distinctWitnesses(group)
                if (witnesses.size < 3) continue
                for (comment in group) {
                    var votes = 1
                    var varied = false
                    for (witness in witnesses) {
                        if (witness.mid == comment.mid || witness.root == comment.root) continue
                        votes++
                        varied = varied || witness.facts[fact] != comment.facts[fact]
                    }
                    if (!varied || votes < 3) continue
                    val points = if (votes >= 4) MAX_POINTS else MIN_POINTS
                    scores[comment.rpid] = maxOf(scores[comment.rpid] ?: 0, points)
                }
            }
            return scores
        }

        /**
         * 楼与账号各占一票。对一个事实找最多五条两两不同的证据，避免账号与楼的独立计数误报。
         * 每账号保留至多五个楼，匹配时至多有四个已占用的楼，查找深度与分支数均固定。
         */
        private fun distinctWitnesses(group: List<Evidence>): Collection<Evidence> {
            val byAuthor = LinkedHashMap<Long, MutableMap<Long, Evidence>>()
            for (comment in group) {
                val roots = byAuthor.getOrPut(comment.mid) { LinkedHashMap() }
                if (roots.size < MAX_WITNESSES) roots.putIfAbsent(comment.root, comment)
            }
            val byRoot = LinkedHashMap<Long, Evidence>()
            fun assign(author: Long, tried: MutableSet<Long>): Boolean {
                for ((root, comment) in byAuthor.getValue(author)) {
                    if (!tried.add(root)) continue
                    val occupied = byRoot[root]
                    if (occupied == null || assign(occupied.mid, tried)) {
                        byRoot[root] = comment
                        return true
                    }
                }
                return false
            }
            for (author in byAuthor.keys) {
                assign(author, HashSet(MAX_WITNESSES))
                if (byRoot.size >= MAX_WITNESSES) break
            }
            return byRoot.values
        }
    }
}
