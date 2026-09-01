package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.highcapable.kavaref.extension.isSubclassOf
import java.lang.reflect.Method

/** 安装期校验并缓存的结构化推荐理由 getter 链。 */
internal data class VideoRelateReasonMethodPath(
    val methods: List<Method>
)

internal object VideoRelateReasonReader {
    private const val MAX_REASON_VALUES = 12
    private const val MAX_REASON_LENGTH = 256

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

    /** 读取失败、类型错配或空理由均保守放行；不回退到标题、简介或 toString。 */
    fun read(item: Any, paths: List<VideoRelateReasonMethodPath>): Set<String> = buildSet {
        paths.forEach { path ->
            if (size >= MAX_REASON_VALUES) return@forEach
            var value: Any? = item
            for (method in path.methods) {
                val receiver = value ?: break
                if (!method.declaringClass.isInstance(receiver)) {
                    value = null
                    break
                }
                value = runCatching { method.invoke(receiver) }.getOrNull()
                if (value == null) break
            }
            val reason = (value as? CharSequence)
                ?.toString()
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it.length <= MAX_REASON_LENGTH }
                ?: return@forEach
            add(reason)
        }
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

    fun parseCustom(raw: String): Set<String> = RuleSetCodec.parse(
        raw.take(MAX_RAW_KEYWORDS_LENGTH)
    ).asSequence()
        .filter { it.length <= MAX_KEYWORD_LENGTH }
        .take(MAX_KEYWORDS)
        .toCollection(linkedSetOf())

    fun matchesCustom(reasons: Set<String>, keywords: Set<String>): Boolean =
        reasons.any { reason -> RuleSetCodec.matches(keywords, reason) }

    fun matchesHighConfidencePromotion(reasons: Set<String>): Boolean = reasons.any { reason ->
        val normalized = reason.trim().lowercase()
        normalized == "广告" || highConfidencePromotionPhrases.any(normalized::contains)
    }
}
