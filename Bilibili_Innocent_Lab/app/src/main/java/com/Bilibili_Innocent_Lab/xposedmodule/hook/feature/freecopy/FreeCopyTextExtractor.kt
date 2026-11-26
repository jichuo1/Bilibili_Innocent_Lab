package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.freecopy

import android.content.Context
import android.content.res.Resources
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.SpannedString
import android.text.style.ClickableSpan
import android.text.style.ReplacementSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.Bilibili_Innocent_Lab.xposedmodule.hook.CommentEmojiAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.CommentTextIdentity
import com.Bilibili_Innocent_Lab.xposedmodule.hook.FreeCopySelectionMapper
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentFullTextReader
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentNativeSupplementBridge
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentRawTextReader
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.TitleFreeCopyPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import io.github.jichuo1.meguviz.android.extensions.childOrNull
import io.github.jichuo1.meguviz.android.extensions.textToString
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Optional
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/** 自由复制三表面（评论 / 简介 / 标题）的文本读取、Span 快照与评论身份校验。 */
internal object FreeCopyTextExtractor {
    val commentRawTextReader = CommentRawTextReader()
    val commentFullTextReader = CommentFullTextReader()

    @Volatile
    var additionalNativeCopy: CommentNativeSupplementBridge? = null

    /** 视频详情页简介 TextView 的 view id（运行时按名解析）。 */
    @Volatile
    var descViewId: Int = View.NO_ID
        private set

    @Volatile
    private var descIdResolveAttempts: Int = 0

    /** 标题 View id（按资源名 "title" 解析）与同布局里的 "arrow" id；解析失败 3 次后放弃。 */
    @Volatile
    private var titleViewId: Int = View.NO_ID

    @Volatile
    private var titleArrowId: Int = View.NO_ID

    @Volatile
    private var titleIdResolveAttempts: Int = 0

    /**
     * 已确认的标题 View 弱集合。弱 key 不持有 View，页面销毁后自然消失。
     */
    private val titleViews = Collections.synchronizedMap(WeakHashMap<View, Boolean>())

    @Volatile
    private var commentMoreButtonId: Int = View.NO_ID

    @Volatile
    private var commentActionsContainerId: Int = View.NO_ID

    @Volatile
    private var commentPrimaryMessageId: Int = View.NO_ID

    @Volatile
    private var commentSecondaryMessageId: Int = View.NO_ID

    @Volatile
    private var commentNextMessageId: Int = View.NO_ID

    /** ExpandableTextView 里保存标题原文的 CharSequence 字段（按类缓存；Optional.empty 代表该类无可用字段）。 */
    private val titleOriginFieldByClass = ConcurrentHashMap<Class<*>, Optional<Field>>()

    private fun resolveResourceId(resources: Resources, name: String): Int =
        runCatching {
            resources.getIdentifier(name, "id", FREE_COPY_TARGET_PACKAGE)
        }.getOrDefault(0)

    fun isCommentMoreButton(view: View): Boolean {
        var id = commentMoreButtonId
        if (id == View.NO_ID) {
            id = resolveResourceId(view.resources, "more_button")
            commentMoreButtonId = id
        }
        return id > 0 && view.id == id
    }

    fun isCommentActionsContainer(view: View): Boolean {
        var id = commentActionsContainerId
        if (id == View.NO_ID) {
            id = resolveResourceId(view.resources, "item_include_actions")
            commentActionsContainerId = id
        }
        return id > 0 && view.id == id
    }

    fun isCommentBodyTextView(view: View): Boolean {
        if (view !is TextView) return false
        var primaryId = commentPrimaryMessageId
        if (primaryId == View.NO_ID) {
            primaryId = resolveResourceId(view.resources, "primary_message")
            commentPrimaryMessageId = primaryId
        }
        var secondaryId = commentSecondaryMessageId
        if (secondaryId == View.NO_ID) {
            secondaryId = resolveResourceId(view.resources, "secondary_message")
            commentSecondaryMessageId = secondaryId
        }
        var nextId = commentNextMessageId
        if (nextId == View.NO_ID) {
            nextId = resolveResourceId(view.resources, "comment_message")
            commentNextMessageId = nextId
        }
        return (primaryId > 0 && view.id == primaryId) ||
            (secondaryId > 0 && view.id == secondaryId) ||
            (nextId > 0 && view.id == nextId)
    }

    fun resolveDescViewId(viewContext: Context?) {
        if (descIdResolveAttempts >= 3) return
        val ctx = viewContext ?: return
        descIdResolveAttempts++
        val id = resolveResourceId(ctx.resources, "desc")
        if (id != 0) {
            descViewId = id
            FreeCopyRuntimeConfig.logInfo("free_copy_desc_id", "[BIL] 已解析简介 view id: 0x${Integer.toHexString(id)}")
        } else if (descIdResolveAttempts >= 3) {
            FreeCopyRuntimeConfig.logError(
                "free_copy_desc_id_err",
                "[BIL] 简介 view id 解析失败（当前版本无 id/desc 资源，功能不可用）"
            )
        }
    }

    fun resolveTitleViewIds(viewContext: Context?) {
        if (titleViewId != View.NO_ID || titleIdResolveAttempts >= 3) return
        val ctx = viewContext ?: return
        titleIdResolveAttempts++
        val title = resolveResourceId(ctx.resources, TitleFreeCopyPolicy.VIEW_ID_NAME)
        val arrow = resolveResourceId(ctx.resources, TitleFreeCopyPolicy.ARROW_ID_NAME)
        if (title != 0 && arrow != 0) {
            titleArrowId = arrow
            titleViewId = title
            FreeCopyRuntimeConfig.logInfo("free_copy_title_id", "[BIL] 已解析标题 view id: 0x${Integer.toHexString(title)}")
        } else if (titleIdResolveAttempts >= 3) {
            FreeCopyRuntimeConfig.logError(
                "free_copy_title_id_err",
                "[BIL] 标题 view id 解析失败（当前版本无 id/title 或 id/arrow，标题复制不可用）"
            )
        }
    }

    fun isTitleView(v: View): Boolean {
        val id = titleViewId
        return id != View.NO_ID && v.id == id && titleViews.containsKey(v)
    }

    fun onTitleCandidateTextSet(v: View) {
        if (!FreeCopyRuntimeConfig.runtimeTitleFreeCopyEnabled) return
        if (titleViewId == View.NO_ID) {
            resolveTitleViewIds(v.context)
            if (titleViewId == View.NO_ID) return
        }
        if (v.id != titleViewId) return
        if (titleViews.containsKey(v)) return
        val classChain = ArrayList<String>(4)
        var cls: Class<*>? = v.javaClass
        while (cls != null && cls != Any::class.java && classChain.size < 8) {
            classChain.add(cls.name)
            cls = cls.superclass
        }
        val hasArrowSibling = (v.parent as? ViewGroup)?.findViewById<View>(titleArrowId) != null
        if (!TitleFreeCopyPolicy.isTitleWidget(true, classChain, hasArrowSibling)) return
        // 只登记，不挂长按监听：长按识别由 dispatchTouchEvent + 到期 Runnable 承担，保留容器点击语义。
        titleViews[v] = true
    }

    fun titleOriginField(viewClass: Class<*>): Field? =
        titleOriginFieldByClass.getOrPut(viewClass) {
            var found: Field? = null
            var cls: Class<*>? = viewClass
            while (found == null && cls != null && cls != classOf<TextView>() && cls != Any::class.java) {
                val charSequenceFields = cls.declaredFields.filter {
                    !Modifier.isStatic(it.modifiers) && it.type == classOf<CharSequence>()
                }
                val index = TitleFreeCopyPolicy.originFieldIndex(charSequenceFields.map { it.name })
                if (index != null) {
                    found = charSequenceFields[index].also { it.isAccessible = true }
                }
                cls = cls.superclass
            }
            Optional.ofNullable(found)
        }.orElse(null)

    fun extractTitleText(v: View): String {
        val visible = (v as? TextView)?.text
            ?.let { TitleFreeCopyPolicy.normalize(stripSpanPlaceholderChars(it)) }.orEmpty()
        val origin = runCatching {
            (titleOriginField(v.javaClass)?.get(v) as? CharSequence)
                ?.let { TitleFreeCopyPolicy.normalize(stripSpanPlaceholderChars(it)) }
        }.getOrNull().orEmpty()
        return TitleFreeCopyPolicy.choose(origin, visible)
    }

    fun extractDescText(v: View): String {
        val cs = (v as? TextView)?.text ?: return ""
        var s = stripSpanPlaceholderChars(cs).replace("\r\n", "\n").replace("\r", "\n")
        s = s.replace(Regex("未经(作者)?授权[，,、]?禁止转载"), "")
        s = s.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.joinToString("\n").trim()
        return s
    }

    fun readDescTextForClipboard(desc: View): String? {
        val raw = runCatching {
            (titleOriginField(desc.javaClass)?.get(desc) as? CharSequence)
                ?: KavaMemberLookup.fieldOrNull(
                    desc.javaClass,
                    "l",
                    includeSuperclasses = true
                )?.get(desc) as? CharSequence
        }.getOrNull()?.toString()
        return raw ?: runCatching { (desc as? TextView)?.textToString() }.getOrNull()
    }

    fun stripSpanPlaceholderChars(cs: CharSequence): String {
        val spanned = cs as? Spanned ?: return cs.toString()
        val spans = spanned.getSpans(0, spanned.length, classOf<ReplacementSpan>())
        if (spans.isEmpty()) return spanned.toString()
        val sb = StringBuilder(spanned)
        spans.sortedByDescending { spanned.getSpanStart(it) }.forEach { s ->
            val st = spanned.getSpanStart(s)
            val en = spanned.getSpanEnd(s)
            if (st in 0..en && en <= sb.length) sb.delete(st, en)
        }
        return sb.toString()
    }

    fun extractLongText(v: View, depth: Int = 0): String? {
        if (depth > 12) return null
        if (v.visibility != View.VISIBLE) return null
        var best: String? = null
        (v as? TextView)?.textToString()?.takeIf { it.length >= 2 }?.let { best = it }
        (v as? ViewGroup)?.let { vg ->
            for (i in 0 until vg.childCount) {
                val child = vg.childOrNull<View>(i) ?: continue
                val t = extractLongText(child, depth + 1) ?: continue
                if (best == null || t.length > best.length) best = t
            }
        }
        return best
    }

    fun findCommentBody(v: View, depth: Int = 0): CharSequence? {
        if (depth > 12) return null
        if (v.visibility != View.VISIBLE) return null
        if (v is TextView) {
            val cls = v.javaClass.name
            if (cls.contains("ExpandableTextView") || cls.contains("RichTextView") || cls.contains("CommentTextView")) {
                val t = v.text ?: ""
                if (t.isNotEmpty()) return t
            }
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                findCommentBody(v.childOrNull<View>(i) ?: continue, depth + 1)?.let { return it }
            }
        }
        return null
    }

    fun extractCommentText(root: View): CharSequence? {
        findCommentBody(root)?.let { return it }
        return extractLongText(root)
    }

    fun extractRawCommentText(commentItem: Any?): String? =
        commentRawTextReader.read(commentItem, modern = false)

    fun extractRawCommentTextV2(commentItem: Any?): String? =
        commentRawTextReader.read(commentItem)

    fun resolveCommentTextAtInteraction(v: View): FreeCopyContent? {
        var registeredRoot: View? = null
        var semanticState: CommentRootState? = null
        var fallbackState: CommentRootState? = null
        synchronized(FreeCopyBindScheduler.commentRootLock) {
            var cur: View? = v
            while (cur != null) {
                val state = FreeCopyBindScheduler.commentRootRefs[cur]?.get()
                if (state != null) {
                    if (registeredRoot == null) registeredRoot = cur
                    if (!state.rawText.isNullOrBlank()) {
                        if (fallbackState == null) fallbackState = state
                        if (state.rawTrusted) {
                            semanticState = state
                            break
                        }
                    }
                }
                cur = cur.parent as? View
            }
        }
        val image = additionalNativeCopy?.imageContent(v)
        val state = image?.let { CommentRootState(it.first, false, true, it.second, 0L) }
            ?: semanticState ?: fallbackState
        val raw = state?.rawText?.trim()?.takeIf { it.length in 1..3000 && it.isNotBlank() }
        val fullBody = if (raw != null && state.rawTrusted) {
            commentFullTextReader.read(v)?.let { full ->
                val clickable = (full as? Spanned)?.let { text ->
                    text.getSpans(0, text.length, classOf<Any>())
                        .filter { it is ClickableSpan || commentFullTextReader.isNicknameSpan(it) }
                        .map { text.getSpanStart(it) until text.getSpanEnd(it) }
                }.orEmpty()
                val start = CommentTextIdentity.replyBodyStart(
                    raw, full, visibleSpanRanges(full, replacementSpans(full)), clickable
                )
                FreeCopyRuntimeConfig.logInfo(
                    "free_copy_reply_projection",
                    "[BIL] 自由复制回复投影: full=${full.length} raw=${raw.length} " +
                        "clickable=${clickable.size} accepted=${start != null}"
                )
                start?.let { full.subSequence(it, full.length) }
            }
        } else null
        val renderedText = fullBody ?: extractCommentText(registeredRoot ?: v)
        val visibleText = renderedText
            ?.let(::snapshotCommentText)
            ?.takeIf(::isValidFreeCopyText)
        if (visibleText == null) return raw?.let(::FreeCopyContent)
        if (raw == null) {
            FreeCopyRuntimeConfig.logInfo("free_copy_raw_missing", "[BIL] 自由复制原文缺失: bound=${state != null}")
            return FreeCopyContent(visibleText)
        }
        val identityText = renderedText
            .let(::projectFoldedCommentBody)
            .let(::snapshotCommentText)
            .takeIf(::isValidFreeCopyText)
            ?: visibleText
        val visibleSpans = replacementSpans(identityText)
        val emojiResolution = CommentEmojiAdapter.resolve(
            state.commentItem,
            raw,
            visibleSpans.map { it as Any }
        )
        return if (isSameRenderedComment(raw, identityText, state.rawTrusted, emojiResolution, visibleSpans)) {
            mergeVisibleEmojiSpans(
                raw = raw,
                visible = identityText,
                spans = visibleSpans,
                emojiResolution = emojiResolution
            )
        } else {
            FreeCopyRuntimeConfig.logInfo(
                "free_copy_identity_rejected",
                "[BIL] 自由复制原文身份未通过: rawLength=${raw.length} visibleLength=${identityText.length} trusted=${state.rawTrusted}"
            )
            FreeCopyContent(visibleText)
        }
    }

    fun isSameRenderedComment(
        raw: String,
        visible: CharSequence,
        rawTrusted: Boolean = false,
        emojiResolution: CommentEmojiAdapter.Resolution? = null,
        visibleSpans: List<ReplacementSpan> = replacementSpans(visible)
    ): Boolean {
        if (raw == visible.toString()) return true
        val rawKey = renderedCommentMatchKey(raw)
        val visibleKey = renderedCommentMatchKey(visible)
        if (rawKey == visibleKey &&
            (rawTrusted || rawKey.any { it != CommentTextIdentity.EMOJI_SLOT })
        ) return true
        val rawLiteralKey = rawKey.filter { it != CommentTextIdentity.EMOJI_SLOT }
        val visibleLiteralKey = visibleKey.filter { it != CommentTextIdentity.EMOJI_SLOT }
        if (minOf(rawLiteralKey.length, visibleLiteralKey.length) >= 4 &&
            (rawKey.contains(visibleKey) || visibleKey.contains(rawKey) ||
                rawLiteralKey.contains(visibleLiteralKey) || visibleLiteralKey.contains(rawLiteralKey))
        ) return true
        if (rawTrusted && emojiResolution != null && emojiResolution.urlMatchedCount > 0) {
            val rawModelKey = CommentTextIdentity.matchKey(
                raw,
                emojiResolution.emotes.map { it.rawStart until it.rawEnd }
            ).filter { it != CommentTextIdentity.EMOJI_SLOT }
            val visibleModelKey = CommentTextIdentity.matchKey(
                visible,
                visibleSpanRanges(visible, visibleSpans)
            ).filter { it != CommentTextIdentity.EMOJI_SLOT }
            val ordinaryTextMatches = visibleModelKey.isEmpty() ||
                (visibleModelKey.length >= 4 && rawModelKey.contains(visibleModelKey))
            if (ordinaryTextMatches) return true
        }
        return false
    }

    fun replacementSpans(text: CharSequence): List<ReplacementSpan> {
        val spanned = text as? Spanned ?: return emptyList()
        return spanned.getSpans(0, text.length, classOf<ReplacementSpan>())
            .filter {
                CommentTextIdentity.isEmojiReplacement(
                    text, spanned.getSpanStart(it), spanned.getSpanEnd(it)
                )
            }
            .sortedBy { spanned.getSpanStart(it) }
    }

    fun projectFoldedCommentBody(text: CharSequence): CharSequence {
        val spanned = text as? Spanned ?: return text
        val decoratedRanges = spanned
            .getSpans(0, text.length, classOf<Any>())
            .asSequence()
            .filterNot { it is ReplacementSpan }
            .mapNotNull { span ->
                val start = spanned.getSpanStart(span)
                val end = spanned.getSpanEnd(span)
                if (start >= 0 && end > start) start until end else null
            }
            .toList()
        val controlStart = CommentTextIdentity.foldControlStart(text, decoratedRanges) ?: return text
        FreeCopyRuntimeConfig.logInfo(
            "free_copy_fold_projection",
            "[BIL] 自由复制折叠投影: visible=${text.length} body=$controlStart"
        )
        return text.subSequence(0, controlStart)
    }

    fun visibleSpanRanges(text: CharSequence, spans: List<ReplacementSpan>): List<IntRange> {
        val spanned = text as? Spanned ?: return emptyList()
        return spans.map { span -> spanned.getSpanStart(span) until spanned.getSpanEnd(span) }
    }

    fun renderedCommentMatchKey(text: CharSequence): String =
        CommentTextIdentity.matchKey(text, visibleSpanRanges(text, replacementSpans(text)))

    fun snapshotCommentText(text: CharSequence): CharSequence {
        val spanned = text as? Spanned
        val spans = spanned?.getSpans(0, text.length, classOf<ReplacementSpan>()).orEmpty()
        fun isUnspannedZeroWidth(index: Int): Boolean =
            text[index] == '\u200B' && (spanned == null ||
                spans.none { span ->
                    spanned.getSpanStart(span) <= index && spanned.getSpanEnd(span) > index
                })

        var start = 0
        var end = text.length
        while (start < end && (text[start].isWhitespace() || isUnspannedZeroWidth(start))) start++
        while (end > start && (text[end - 1].isWhitespace() || isUnspannedZeroWidth(end - 1))) end--
        val plain = text.subSequence(start, end).toString()
        if (spanned == null) return plain
        val out = SpannableString(plain)
        var copied = false
        spanned.getSpans(start, end, classOf<ReplacementSpan>()).forEach { span ->
            val spanStart = spanned.getSpanStart(span).coerceAtLeast(start)
            val spanEnd = spanned.getSpanEnd(span).coerceAtMost(end)
            if (spanEnd > spanStart) {
                out.setSpan(span, spanStart - start, spanEnd - start, spanned.getSpanFlags(span))
                copied = true
            }
        }
        return if (copied) SpannedString(out) else plain
    }

    fun isValidFreeCopyText(text: CharSequence?): Boolean {
        if (text == null || text.length !in 1..3000) return false
        if (text.any { !it.isWhitespace() && it != '\uFFFC' && it != '\u200B' }) return true
        val spanned = text as? Spanned ?: return false
        return spanned.getSpans(0, text.length, classOf<ReplacementSpan>())
            .any { spanned.getSpanStart(it) >= 0 && spanned.getSpanEnd(it) > spanned.getSpanStart(it) }
    }

    fun mergeVisibleEmojiSpans(
        raw: String,
        visible: CharSequence,
        spans: List<ReplacementSpan>,
        emojiResolution: CommentEmojiAdapter.Resolution
    ): FreeCopyContent {
        visible as? Spanned ?: return FreeCopyContent(raw)
        if (spans.isEmpty()) return FreeCopyContent(raw)
        val displayRanges = visibleSpanRanges(visible, spans)
        val aligned = FreeCopySelectionMapper.alignCustomEmojiTokens(
            rawText = raw,
            displayText = visible,
            displayReplacementRanges = displayRanges,
            expectedTokens = emojiResolution.emotes.map { it.token }
        ).orEmpty()
        val structuralByDisplayRange = aligned.associateBy { it.displayStart to it.displayEnd }
        val modelBySpan = IdentityHashMap<Any, CommentEmojiAdapter.EmoteDescriptor>()
        emojiResolution.spanMatches.forEach { modelBySpan[it.span] = it.emote }

        val out = SpannableString(raw)
        val copyValues = ArrayList<EmojiCopyValue>(spans.size)
        val occupiedRawRanges = ArrayList<IntRange>()
        var structuralApplied = 0
        var urlApplied = 0
        spans.forEach { span ->
            val displayStart = visible.getSpanStart(span)
            val displayEnd = visible.getSpanEnd(span)
            val structural = structuralByDisplayRange[displayStart to displayEnd]
            val model = modelBySpan[span]
            val mapping = when {
                model != null && structural != null && structural.copyText == model.token ->
                    Triple(structural.rawStart, structural.rawEnd, structural.copyText)
                model != null -> Triple(model.rawStart, model.rawEnd, model.token)
                structural != null && emojiResolution.urlMatchedCount == 0 ->
                    Triple(structural.rawStart, structural.rawEnd, structural.copyText)
                else -> null
            } ?: return@forEach
            val rawStart = mapping.first
            val rawEnd = mapping.second
            val copyText = mapping.third
            if (rawStart !in 0 until rawEnd || rawEnd > raw.length ||
                raw.substring(rawStart, rawEnd) != copyText
            ) return@forEach
            if (occupiedRawRanges.any { it.first < rawEnd && it.last + 1 > rawStart }) return@forEach
            occupiedRawRanges += rawStart until rawEnd
            out.setSpan(span, rawStart, rawEnd, visible.getSpanFlags(span))
            copyValues += EmojiCopyValue(span, copyText)
            if (structural != null) structuralApplied++ else urlApplied++
        }
        val fallbackTextCount = (emojiResolution.emotes.size - copyValues.size).coerceAtLeast(0)
        FreeCopyRuntimeConfig.logInfo(
            "emoji_map",
            "[BIL] 自由复制 Emoji 映射: model=${emojiResolution.emotes.size} " +
                "spans=${spans.size} url=${emojiResolution.urlMatchedCount} " +
                "structural=$structuralApplied urlFallback=$urlApplied applied=${copyValues.size} " +
                "textFallback=$fallbackTextCount"
        )
        return FreeCopyContent(SpannedString(out), copyValues)
    }

    fun normalizePopupText(rawContent: FreeCopyContent): FreeCopyContent {
        val safeText = snapshotCommentText(rawContent.displayText)
        if (!safeText.contains('\r')) return rawContent.copy(displayText = safeText)
        val builder = SpannableStringBuilder(safeText)
        var i = 0
        while (i < builder.length) {
            if (builder[i] != '\r') {
                i++
                continue
            }
            if (i + 1 < builder.length && builder[i + 1] == '\n') {
                builder.delete(i, i + 1)
            } else {
                builder.replace(i, i + 1, "\n")
                i++
            }
        }
        val hasReplacementSpans = builder
            .getSpans(0, builder.length, classOf<ReplacementSpan>())
            .isNotEmpty()
        val normalized = if (hasReplacementSpans) SpannedString(builder) else builder.toString()
        return rawContent.copy(displayText = normalized)
    }
}
