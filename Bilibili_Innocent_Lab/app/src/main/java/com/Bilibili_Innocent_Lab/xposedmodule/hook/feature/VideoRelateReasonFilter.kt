package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.highcapable.kavaref.extension.isSubclassOf
import java.lang.reflect.Method

/** 安装期校验并缓存的结构化推荐理由 getter 链。 */
internal data class VideoRelateReasonMethodPath(
    val methods: List<Method>
)

internal data class VideoRelateReasonObservation(
    val reasons: Set<String>,
    val attemptedPathCount: Int,
    val applicablePathCount: Int,
    val issueFlags: Int
) {
    val hasUsableReason: Boolean
        get() = reasons.isNotEmpty()
}

internal object VideoRelateReasonReader {
    private const val MAX_REASON_VALUES = 12
    private const val MAX_REASON_LENGTH = 256

    const val ISSUE_NO_PATH = 1 shl 0
    const val ISSUE_NO_APPLICABLE_PATH = 1 shl 1
    const val ISSUE_NULL_VALUE = 1 shl 2
    const val ISSUE_RECEIVER_MISMATCH = 1 shl 3
    const val ISSUE_INVOCATION_FAILED = 1 shl 4
    const val ISSUE_NON_TEXT_VALUE = 1 shl 5
    const val ISSUE_EMPTY_VALUE = 1 shl 6
    const val ISSUE_TOO_LONG = 1 shl 7

    fun buildMethodPaths(chains: List<List<Method>>): List<VideoRelateReasonMethodPath> =
        chains.asSequence()
            .filter { it.size in 1..3 }
            .filter { methods ->
                methods.zipWithNext().all { (container, value) ->
                    container.returnType isSubclassOf value.declaringClass
                }
            }
            .map(::VideoRelateReasonMethodPath)
            .distinctBy { path -> path.methods.joinToString("->", transform = Method::toGenericString) }
            .toList()

    /** 保留有界失败状态；不回退到标题、简介或 toString。 */
    fun observe(
        item: Any,
        paths: List<VideoRelateReasonMethodPath>
    ): VideoRelateReasonObservation {
        if (paths.isEmpty()) {
            return VideoRelateReasonObservation(
                reasons = emptySet(),
                attemptedPathCount = 0,
                applicablePathCount = 0,
                issueFlags = ISSUE_NO_PATH
            )
        }
        val reasons = linkedSetOf<String>()
        var applicablePathCount = 0
        var issueFlags = 0
        paths.forEach { path ->
            if (reasons.size >= MAX_REASON_VALUES) return@forEach
            var value: Any? = item
            var completed = true
            for ((index, method) in path.methods.withIndex()) {
                val receiver = value
                if (receiver == null) {
                    issueFlags = issueFlags or ISSUE_NULL_VALUE
                    completed = false
                    break
                }
                if (!method.declaringClass.isInstance(receiver)) {
                    issueFlags = issueFlags or ISSUE_RECEIVER_MISMATCH
                    completed = false
                    break
                }
                if (index == 0) applicablePathCount += 1
                value = try {
                    method.invoke(receiver)
                } catch (_: Throwable) {
                    issueFlags = issueFlags or ISSUE_INVOCATION_FAILED
                    completed = false
                    break
                }
            }
            if (!completed) return@forEach
            val raw = value as? CharSequence
            if (raw == null) {
                issueFlags = issueFlags or if (value == null) {
                    ISSUE_NULL_VALUE
                } else {
                    ISSUE_NON_TEXT_VALUE
                }
                return@forEach
            }
            val reason = raw.toString().trim()
            when {
                reason.isEmpty() -> issueFlags = issueFlags or ISSUE_EMPTY_VALUE
                reason.length > MAX_REASON_LENGTH -> issueFlags = issueFlags or ISSUE_TOO_LONG
                else -> reasons += reason
            }
        }
        if (applicablePathCount == 0) {
            issueFlags = issueFlags or ISSUE_NO_APPLICABLE_PATH
        }
        return VideoRelateReasonObservation(
            reasons = reasons,
            attemptedPathCount = paths.size,
            applicablePathCount = applicablePathCount,
            issueFlags = issueFlags
        )
    }

    /** 普通模式兼容入口：读取失败、类型错配或空理由继续表现为空集合并保守放行。 */
    fun read(item: Any, paths: List<VideoRelateReasonMethodPath>): Set<String> =
        observe(item, paths).reasons
}

/** 安装期校验并缓存的结构化商业布尔证据 getter 链。 */
internal data class VideoRelateBooleanMethodPath(
    val methods: List<Method>
)

internal object VideoRelateBooleanEvidenceReader {
    fun buildMethodPaths(chains: List<List<Method>>): List<VideoRelateBooleanMethodPath> =
        chains.asSequence()
            .filter { it.size in 1..2 }
            .filter { methods ->
                methods.zipWithNext().all { (container, value) ->
                    container.returnType isSubclassOf value.declaringClass
                }
            }
            .filter { methods ->
                methods.last().returnType == Boolean::class.javaPrimitiveType ||
                    methods.last().returnType == Boolean::class.javaObjectType
            }
            .map(::VideoRelateBooleanMethodPath)
            .distinctBy { path -> path.methods.joinToString("->", transform = Method::toGenericString) }
            .toList()

    /** 只接受协议 getter 明确返回 true；读取失败不会被伪造成商业证据。 */
    fun hasPositiveEvidence(
        item: Any,
        paths: List<VideoRelateBooleanMethodPath>
    ): Boolean = paths.any { path ->
        var value: Any? = item
        for (method in path.methods) {
            val receiver = value ?: return@any false
            if (!method.declaringClass.isInstance(receiver)) return@any false
            value = runCatching { method.invoke(receiver) }.getOrNull()
        }
        value == true
    }
}

internal object VideoRelateReasonMatcher {
    private const val MAX_RAW_KEYWORDS_LENGTH = 4_096
    private const val MAX_KEYWORDS = 64
    private const val MAX_KEYWORD_LENGTH = 96

    private val highConfidencePromotionPhrases = setOf(
        "创作推广",
        "商业推广",
        "广告推广",
        "去小程序",
        "打开小程序",
        "立即下载",
        "下载游戏",
        "预约游戏",
        "游戏预约",
        "立即体验广告",
        "立即安装",
        "网友期待游戏",
        "全平台预约",
        "速来预约",
        "快来预约",
        "快来体验",
        "速来体验",
        "速速下载",
        "快来下载",
        "速来下载",
        "点击下载",
        "立即预约"
    )

    private val strongModePromotionPhrases = setOf(
        "大家都在看",
        "立即体验"
    )

    fun parseCustom(raw: String): Set<String> = RuleSetCodec.parse(
        raw.take(MAX_RAW_KEYWORDS_LENGTH)
    ).asSequence()
        .filter { it.length <= MAX_KEYWORD_LENGTH }
        .take(MAX_KEYWORDS)
        .toCollection(linkedSetOf())

    fun matchesCustom(reasons: Set<String>, keywords: Set<String>): Boolean =
        reasons.any { reason -> RuleSetCodec.matches(keywords, reason) }

    fun matchesHighConfidencePromotion(reasons: Set<String>): Boolean = reasons.any { reason ->
        isHighConfidencePromotion(reason.trim().lowercase())
    }

    fun matchesStrongModePromotion(reasons: Set<String>): Boolean = reasons.any { reason ->
        val normalized = reason.trim().lowercase()
        isHighConfidencePromotion(normalized) ||
            strongModePromotionPhrases.any(normalized::contains)
    }

    fun matchesLikeCount(reasons: Set<String>): Boolean =
        reasons.any(VideoRelateEngagementReasonParser::isLikeCountReason)

    private fun isHighConfidencePromotion(normalized: String): Boolean =
        normalized == "广告" || highConfidencePromotionPhrases.any(normalized::contains)
}

/** 完整识别“数量 + 可选万/亿单位 + 可选加号 + 点赞”，不匹配包含额外正文的理由。 */
internal object VideoRelateEngagementReasonParser {
    private const val MAX_NORMALIZED_LENGTH = 32
    private const val SUFFIX = "点赞"

    fun isLikeCountReason(reason: String): Boolean {
        val normalized = normalize(reason) ?: return false
        if (!normalized.endsWith(SUFFIX)) return false
        var amount = normalized.dropLast(SUFFIX.length)
        if (amount.endsWith('+')) amount = amount.dropLast(1)
        var hasUnit = false
        if (amount.endsWith('万') || amount.endsWith('亿')) {
            hasUnit = true
            amount = amount.dropLast(1)
        }
        if (amount.isEmpty()) return false

        val dotIndex = amount.indexOf('.')
        if (dotIndex >= 0) {
            if (!hasUnit || dotIndex != amount.lastIndexOf('.')) return false
            val integerPart = amount.substring(0, dotIndex)
            val fractionalPart = amount.substring(dotIndex + 1)
            return validInteger(integerPart) &&
                fractionalPart.isNotEmpty() && fractionalPart.all(Char::isDigit)
        }
        return validInteger(amount)
    }

    private fun normalize(reason: String): String? {
        if (reason.isEmpty() || reason.length > 48) return null
        val output = StringBuilder(reason.length)
        reason.forEach { raw ->
            if (raw.isWhitespace() || raw == '\u3000') return@forEach
            val normalized = when (raw) {
                in '０'..'９' -> '0' + (raw - '０')
                '，' -> ','
                '．' -> '.'
                '＋' -> '+'
                else -> raw
            }
            output.append(normalized)
            if (output.length > MAX_NORMALIZED_LENGTH) return null
        }
        return output.toString().takeIf(String::isNotEmpty)
    }

    private fun validInteger(value: String): Boolean {
        if (value.isEmpty()) return false
        if (',' !in value) return value.all(Char::isDigit)
        val groups = value.split(',')
        return groups.firstOrNull()?.length in 1..3 &&
            groups.first().all(Char::isDigit) &&
            groups.drop(1).all { group -> group.length == 3 && group.all(Char::isDigit) }
    }
}
