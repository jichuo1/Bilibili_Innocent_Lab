package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isSubclassOf
import org.json.JSONObject
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * 评论净化在 Kotlin 新通道（KMP 评论页）上的协议层实现：在往返得到的 Java 评论响应上直接改，
 * 与 Java 链路的 getter 净化逐项对应。
 *
 * | 子项 | Java 链路 | 这里 |
 * | --- | --- | --- |
 * | 评论里的搜索跳转 | `Content.getUrlsMap` 摘条目 + `Url.getAppUrlSchema` 置空 | 每条评论（含子回复、置顶位）的 `Content.urls` 摘掉搜索条目后重建 |
 * | 空评论区引导 | `SubjectControl.getEmptyPage` 返回默认实例 | `subject_control.empty_page` 清掉 |
 * | 评论反馈（QoE） / 运营推广 | `hasX` 与 `getX` 成对返回"无" | `MainListReply` 上 `clearQoe` / `clearOperation` / `clearOperationV2` |
 *
 * 投票组件、关注按钮、快速回复是旧版 View 层的东西，Compose 评论页没有对应物，这里不做。
 *
 * 所有读取都在 [KotlinMossChannel.raw] 里进行（本功能的 getter 净化会把这些字段读成"无"）。
 * 名字全部是 protobuf 生成代码的固定形状（2026-10-01 dexq 核对 9.14.0）；缺哪一项就跳过那一项。
 */
internal class CommentKotlinPurifier(
    replyInfoClass: Class<*>,
    /** null = 不摘搜索跳转。 */
    private val isSearchUrl: ((Any?) -> Boolean)?,
    private val clearEmptyPage: Boolean,
    /** `MainListReply` 上要清的可选字段名（如 `Qoe`、`Operation`）→ 对应的能力 id。 */
    private val payloads: Map<String, String>,
    /** 形状解析不出来时留一条有界日志（单测不传）。必须排在 [evidence] 之前：Kotlin 的尾随 lambda 绑定到最后一个参数。 */
    private val logSkip: (reason: String) -> Unit = {},
    private val evidence: (capability: String, stage: FeatureRuntimeStage, count: Int) -> Unit
) {
    private val replyLinks = ConcurrentHashMap<Class<*>, Any>()
    private val topShapes = ConcurrentHashMap<Class<*>, Any>()
    private val rewriter = ProtobufReplyTreeRewriter(
        replyInfoClass,
        removableSingles = emptySet(),
        mapReply = { reply -> if (isSearchUrl == null) reply else stripLinks(reply) }
    ) { emptySet() }

    fun purify(message: Any): Any {
        var current = message
        if (isSearchUrl != null) current = rewriter.rewrite(current).message
        val shape = topShapeOf(current.javaClass) ?: return current
        if (shape.defaultInstance != null && current === shape.defaultInstance) return current
        val clears = shape.payloads.filter { field ->
            KotlinMossChannel.raw { field.has.invoke(current) as? Boolean } == true
        }
        val subject = shape.subject?.takeIf { clearEmptyPage }?.let { path ->
            val control = KotlinMossChannel.raw {
                if (path.has.invoke(current) as? Boolean == true) path.getter.invoke(current) else null
            } ?: return@let null
            if (path.controlDefaultInstance != null && control === path.controlDefaultInstance) return@let null
            val hasEmpty = KotlinMossChannel.raw { path.hasEmptyPage.invoke(control) as? Boolean } == true
            if (!hasEmpty) return@let null
            path.controlPlan.edit(control) { builder -> path.clearEmptyPage.invoke(builder) }
        }
        if (clears.isEmpty() && subject == null) return current
        val rebuilt = shape.plan.edit(current) { builder ->
            clears.forEach { it.clear.invoke(builder) }
            if (subject != null) shape.subject.set.invoke(builder, subject)
        }
        clears.forEach {
            evidence(it.capability, FeatureRuntimeStage.OBSERVED, 1)
            evidence(it.capability, FeatureRuntimeStage.APPLIED, 1)
        }
        if (subject != null) {
            evidence(EMPTY_GUIDE_CAPABILITY, FeatureRuntimeStage.OBSERVED, 1)
            evidence(EMPTY_GUIDE_CAPABILITY, FeatureRuntimeStage.APPLIED, 1)
        }
        return rebuilt
    }

    // —— 搜索跳转 ——

    private class Links(
        val contentGetter: Method,
        val urlsGetter: Method,
        val contentPlan: ProtobufBuilderPlan,
        val clearUrls: Method,
        val putAllUrls: Method,
        val replyPlan: ProtobufBuilderPlan,
        val setContent: Method,
        val replyDefaultInstance: Any?,
        val contentDefaultInstance: Any?
    )

    private fun stripLinks(reply: Any): Any {
        val predicate = isSearchUrl ?: return reply
        val links = linksOf(reply.javaClass) ?: return reply
        if (links.replyDefaultInstance != null && reply === links.replyDefaultInstance) return reply
        val content = KotlinMossChannel.raw { links.contentGetter.invoke(reply) } ?: return reply
        if (links.contentDefaultInstance != null && content === links.contentDefaultInstance) return reply
        @Suppress("UNCHECKED_CAST")
        val urls = KotlinMossChannel.raw { links.urlsGetter.invoke(content) as? Map<Any?, Any?> } ?: return reply
        if (urls.isEmpty()) return reply
        evidence(SEARCH_LINKS_CAPABILITY, FeatureRuntimeStage.OBSERVED, 1)
        val filtered = CommentPurifyFeatureInstaller.withoutSearchUrls(urls) { value -> predicate(value) }
        if (filtered === urls) return reply
        val newContent = links.contentPlan.edit(content) { builder ->
            links.clearUrls.invoke(builder)
            links.putAllUrls.invoke(builder, filtered)
        }
        evidence(SEARCH_LINKS_CAPABILITY, FeatureRuntimeStage.APPLIED, urls.size - filtered.size)
        return links.replyPlan.edit(reply) { builder -> links.setContent.invoke(builder, newContent) }
    }

    private fun linksOf(type: Class<*>): Links? {
        replyLinks[type]?.let { return it as? Links }
        val resolved = runCatching { resolveLinks(type) }.getOrNull()
        replyLinks[type] = resolved ?: NONE
        // 否定结论会被缓存到进程结束：不留痕迹的话，一次瞬时的反射失败（或宿主换了形状）会让这一层
        // 永远静默空转，而安装器照样报"新通道 3 个"。
        if (resolved == null) logSkip("links-shape:${type.simpleName}")
        return resolved
    }

    private fun resolveLinks(type: Class<*>): Links? {
        val contentGetter = noArg(type, "getContent")?.takeIf { !it.returnType.isPrimitive } ?: return null
        val contentClass = contentGetter.returnType
        val urlsGetter = noArg(contentClass, "getUrlsMap")
            ?.takeIf { it.returnType isSubclassOf classOf<Map<*, *>>() } ?: return null
        val contentPlan = ProtobufBuilderPlan.resolve(contentClass) ?: return null
        val clearUrls = contentPlan.method("clearUrls") ?: return null
        val putAllUrls = contentPlan.method("putAllUrls", classOf<Map<*, *>>()) ?: return null
        val replyPlan = ProtobufBuilderPlan.resolve(type) ?: return null
        val setContent = replyPlan.method("setContent", contentClass) ?: return null
        return Links(
            contentGetter, urlsGetter, contentPlan, clearUrls, putAllUrls, replyPlan, setContent,
            defaultInstanceOf(type), defaultInstanceOf(contentClass)
        )
    }

    // —— 顶层可选字段 ——

    private class PayloadField(val capability: String, val has: Method, val clear: Method)

    private class SubjectPath(
        val has: Method,
        val getter: Method,
        val set: Method,
        val hasEmptyPage: Method,
        val controlPlan: ProtobufBuilderPlan,
        val clearEmptyPage: Method,
        val controlDefaultInstance: Any?
    )

    private class TopShape(
        val plan: ProtobufBuilderPlan,
        val payloads: List<PayloadField>,
        val subject: SubjectPath?,
        val defaultInstance: Any?
    )

    private fun topShapeOf(type: Class<*>): TopShape? {
        topShapes[type]?.let { return it as? TopShape }
        val resolved = runCatching { resolveTop(type) }.getOrNull()
        topShapes[type] = resolved ?: NONE
        // 只开着"摘搜索跳转"时，顶层形状根本用不上（摘链接走 rewriter），解析不出形状是正常的，
        // 报出来会是一条假警报。只在顶层确实有事要做时才留痕。
        if (resolved == null && (payloads.isNotEmpty() || clearEmptyPage)) {
            logSkip("top-shape:${type.simpleName}")
        }
        return resolved
    }

    private fun resolveTop(type: Class<*>): TopShape? {
        val plan = ProtobufBuilderPlan.resolve(type) ?: return null
        // 缺失字段先收集、不立即上报：整体解析不出来时由 topShapeOf 报一次 top-shape
        // （有单测钉死"整体失败只报一次"）；只有形状成立但个别字段被丢的部分解析，
        // mapNotNull 时代才是真正的静默黑洞，这里逐字段留痕（每类只解析一次，天然有界）。
        val missing = ArrayList<String>()
        val fields = payloads.map { (name, capability) ->
            val has = noArg(type, "has$name")?.takeIf { it.returnType == classOf<Boolean>() }
            val clear = plan.method("clear$name")
            if (has == null || clear == null) {
                missing += name
                null
            } else {
                PayloadField(capability, has, clear)
            }
        }.filterNotNull()
        val subject = if (!clearEmptyPage) null else run {
            val has = noArg(type, "hasSubjectControl")?.takeIf { it.returnType == classOf<Boolean>() } ?: return@run null
            val getter = noArg(type, "getSubjectControl")?.takeIf { !it.returnType.isPrimitive } ?: return@run null
            val controlClass = getter.returnType
            val set = plan.method("setSubjectControl", controlClass) ?: return@run null
            val hasEmpty = noArg(controlClass, "hasEmptyPage")?.takeIf { it.returnType == classOf<Boolean>() } ?: return@run null
            val controlPlan = ProtobufBuilderPlan.resolve(controlClass) ?: return@run null
            val clearEmpty = controlPlan.method("clearEmptyPage") ?: return@run null
            SubjectPath(has, getter, set, hasEmpty, controlPlan, clearEmpty, defaultInstanceOf(controlClass))
        }
        if (fields.isEmpty() && subject == null) return null
        missing.forEach { logSkip("top-payload:${type.simpleName}#$it") }
        return TopShape(plan, fields, subject, defaultInstanceOf(type))
    }

    private fun noArg(owner: Class<*>, name: String): Method? =
        KavaMemberLookup.methodOrNull(owner, name)?.takeIf { !it.isStatic && it.parameterCount == 0 }

    private fun defaultInstanceOf(type: Class<*>): Any? =
        KavaMemberLookup.methodOrNull(type, "getDefaultInstance")
            ?.takeIf { it.isStatic && it.parameterCount == 0 }
            ?.let { method -> runCatching { method.invoke(null)?.takeIf { type.isInstance(it) } }.getOrNull() }

    companion object {
        const val SEARCH_LINKS_CAPABILITY = "comments_search_links_removed"
        const val EMPTY_GUIDE_CAPABILITY = "comments_empty_guide_removed"
        private val NONE = Any()

        /**
         * 请求侧：在 `MainListReq.extra`（JSON）里声明 `disable_underline=true`，与宿主
         * `search_word_disabled` 开关走到的那一步相同（见 [CommentPurifyFeatureInstaller] 的请求侧说明）。
         * 已有这个键就不覆盖；extra 不是 JSON 对象时不动。返回 null 表示不改。
         */
        internal fun withDisableUnderline(extra: String?): String? {
            val json = if (extra.isNullOrBlank()) JSONObject() else runCatching { JSONObject(extra) }.getOrNull() ?: return null
            if (json.has(DISABLE_UNDERLINE)) return null
            return json.put(DISABLE_UNDERLINE, true).toString()
        }

        const val DISABLE_UNDERLINE = "disable_underline"
    }
}
