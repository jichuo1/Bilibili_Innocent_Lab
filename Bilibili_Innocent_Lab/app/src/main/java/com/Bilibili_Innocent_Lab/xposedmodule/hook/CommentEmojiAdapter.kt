package com.Bilibili_Innocent_Lab.xposedmodule.hook

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isStatic
import com.highcapable.kavaref.extension.isSubclassOf
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * 评论 Emoji 模型适配层。
 *
 * VersionAdapter 负责找到评论绑定入口；本适配层只在用户长按后，消费该入口已经捕获的
 * CommentItem。它不依赖 B 站版本号、混淆类名或字段名，而是按以下稳定结构识别：
 * - RichText：含 Iterable/List contents；
 * - Emote：节点中同时存在 raw 里的 `[表情名]` 与图片/动画 URL；
 * - 绘制 Span：类层级中保存了同一图片/动画 URL。
 *
 * 所有反射只发生在低频长按路径；Method/Field 按运行时 Class 缓存，不进入评论绑定、滚动
 * 或触摸 MOVE 热路径。
 */
internal object CommentEmojiAdapter {

    data class EmoteDescriptor(
        val token: String,
        val urls: Set<String>,
        val rawStart: Int,
        val rawEnd: Int,
        val ordinal: Int
    )

    data class SpanMatch(
        val span: Any,
        val emote: EmoteDescriptor
    )

    data class Resolution(
        val emotes: List<EmoteDescriptor> = emptyList(),
        val spanMatches: List<SpanMatch> = emptyList(),
        val inspectedSpanCount: Int = 0
    ) {
        val urlMatchedCount: Int get() = spanMatches.size
    }

    private val richTextMethodByCommentClass = ConcurrentHashMap<Class<*>, Method>()
    private val contentsFieldsByRichTextClass = ConcurrentHashMap<Class<*>, List<Field>>()
    private val stringFieldsByClass = ConcurrentHashMap<Class<*>, List<Field>>()

    fun resolve(commentItem: Any?, raw: String, spans: List<Any>): Resolution {
        // 普通纯文本评论不做任何模型反射；只有可见富文本绘制单元才进入适配层。
        if (spans.isEmpty()) return Resolution()
        val emotes = extractEmotes(commentItem, raw)
        if (emotes.isEmpty()) {
            return Resolution(emotes = emotes, inspectedSpanCount = spans.size)
        }

        val used = BooleanArray(emotes.size)
        val matches = ArrayList<SpanMatch>(minOf(emotes.size, spans.size))
        for (span in spans) {
            val spanValues = readStringValues(span)
            if (spanValues.isEmpty()) continue
            val emoteIndex = emotes.indices.firstOrNull { index ->
                !used[index] && emotes[index].urls.any(spanValues::contains)
            } ?: continue
            used[emoteIndex] = true
            matches += SpanMatch(span, emotes[emoteIndex])
        }
        return Resolution(emotes, matches, spans.size)
    }

    internal fun extractEmotes(commentItem: Any?, raw: String): List<EmoteDescriptor> {
        if (commentItem == null || raw.isBlank()) return emptyList()
        val commentClass = commentItem.javaClass
        val cached = richTextMethodByCommentClass[commentClass]
        if (cached != null) {
            extractFromRichText(runCatching { cached.invoke(commentItem) }.getOrNull(), raw)
                .takeIf { it.isNotEmpty() }
                ?.let { return it }
            richTextMethodByCommentClass.remove(commentClass, cached)
        }

        for (method in richTextCandidates(commentClass)) {
            val richText = runCatching {
                method.invoke(commentItem)
            }.getOrNull() ?: continue
            val emotes = extractFromRichText(richText, raw)
            if (emotes.isNotEmpty()) {
                richTextMethodByCommentClass[commentClass] = method
                return emotes
            }
        }
        return emptyList()
    }

    private fun richTextCandidates(commentClass: Class<*>): List<Method> {
        val out = LinkedHashSet<Method>()
        val hierarchyMethods = KavaMemberLookup.methods(
            commentClass,
            includeSuperclasses = true,
            makeAccessible = true
        )
        // 已知版本入口只用于排序加速；是否接受仍由返回对象的 contents/Emote 结构决定。
        for (name in listOf("f", "z")) {
            hierarchyMethods
                .filter { it.name == name && it.parameterCount == 0 }
                .forEach(out::add)
        }
        KavaMemberLookup.declaredMethods(commentClass, makeAccessible = true) { method ->
                method.parameterCount == 0 &&
                    !method.returnType.isPrimitive &&
                    method.returnType != classOf<String>() &&
                    hasIterableField(method.returnType)
            }.forEach(out::add)
        return out.toList()
    }

    private fun hasIterableField(type: Class<*>): Boolean =
        classHierarchy(type).any { cls ->
            KavaMemberLookup.declaredFields(cls).any { field ->
                !field.isStatic && field.type isSubclassOf classOf<Iterable<*>>()
            }
        }

    private fun extractFromRichText(richText: Any?, raw: String): List<EmoteDescriptor> {
        if (richText == null) return emptyList()
        val contentsFields = contentsFieldsByRichTextClass[richText.javaClass]
            ?: findContentsFields(richText.javaClass).also {
                contentsFieldsByRichTextClass[richText.javaClass] = it
            }
        for (field in contentsFields) {
            val contents = runCatching { field.get(richText) as? Iterable<*> }
                .getOrNull() ?: continue
            extractFromContents(contents, raw).takeIf { it.isNotEmpty() }?.let { return it }
        }
        return emptyList()
    }

    private fun extractFromContents(contents: Iterable<*>, raw: String): List<EmoteDescriptor> {
        val out = ArrayList<EmoteDescriptor>()
        var rawCursor = 0
        for (node in contents) {
            if (node == null) continue
            val values = readStringValues(node)
            if (values.isEmpty()) continue
            val tokens = values
                .asSequence()
                .filter { isCustomEmojiToken(it) && raw.contains(it) }
                .distinct()
                .toList()
            if (tokens.isEmpty()) continue
            val urls = values
                .asSequence()
                .filter(::looksLikeResourceUrl)
                .toCollection(LinkedHashSet())
            val modelNamedEmote = runCatching { node.toString().startsWith("Emote(") }
                .getOrDefault(false)
            if (!modelNamedEmote && urls.isEmpty()) continue

            val next = tokens
                .mapNotNull { token ->
                    raw.indexOf(token, rawCursor).takeIf { it >= 0 }?.let { it to token }
                }
                .minByOrNull { it.first }
                ?: continue
            val start = next.first
            val token = next.second
            val end = start + token.length
            out += EmoteDescriptor(token, urls, start, end, out.size)
            rawCursor = end
        }
        return out
    }

    private fun findContentsFields(type: Class<*>): List<Field> =
        classHierarchy(type)
            .flatMap { KavaMemberLookup.declaredFields(it, makeAccessible = true).asSequence() }
            .filter { field ->
                !field.isStatic && field.type isSubclassOf classOf<Iterable<*>>()
            }
            .toList()

    private fun readStringValues(instance: Any): Set<String> {
        val fields = stringFieldsByClass[instance.javaClass]
            ?: classHierarchy(instance.javaClass)
                .flatMap { KavaMemberLookup.declaredFields(it, makeAccessible = true).asSequence() }
                .filter { field ->
                    !field.isStatic && field.type == classOf<String>()
                }
                .toList()
                .also { stringFieldsByClass[instance.javaClass] = it }
        if (fields.isEmpty()) return emptySet()
        return fields.mapNotNullTo(LinkedHashSet()) { field ->
            runCatching { field.get(instance) as? String }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
        }
    }

    private fun isCustomEmojiToken(value: String): Boolean =
        value.length in 3..CommentTextIdentity.MAX_CUSTOM_EMOJI_TOKEN_LENGTH &&
            value.first() == '[' && value.last() == ']' &&
            value.none { it == '\r' || it == '\n' }

    private fun looksLikeResourceUrl(value: String): Boolean =
        value.length in 4..4096 &&
            (value.startsWith("http://", ignoreCase = true) ||
                value.startsWith("https://", ignoreCase = true) ||
                value.startsWith("//") || value.startsWith("bfs://", ignoreCase = true))

    private fun classHierarchy(type: Class<*>): Sequence<Class<*>> = generateSequence(type) {
        it.superclass?.takeUnless { parent -> parent == classOf<Any>() }
    }
}
