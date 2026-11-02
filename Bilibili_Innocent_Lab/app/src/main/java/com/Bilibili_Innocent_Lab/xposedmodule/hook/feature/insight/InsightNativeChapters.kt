package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.ProtobufBuilderPlan
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import java.lang.reflect.Method

/** ViewProgressReply → VideoGuide → VideoViewPoint → VideoPoint；全链副本改写。 */
internal class InsightNativeChapters private constructor(
    private val replyDefault: Any, private val guide: Method, private val viewPoint: Method, private val points: Method,
    private val replyPlan: ProtobufBuilderPlan, private val guidePlan: ProtobufBuilderPlan,
    private val listPlan: ProtobufBuilderPlan, private val itemPlan: ProtobufBuilderPlan,
    private val itemDefault: Any, private val setGuide: Method, private val setViewPoint: Method,
    private val addAll: Method, private val from: Method, private val to: Method, private val content: Method,
    private val type: Method, private val permanent: Method, private val cover: Method?
) {
    fun hasChapters(reply: Any): Boolean = runCatching {
        (points.invoke(viewPoint.invoke(guide.invoke(reply))) as? List<*>)?.isNotEmpty() == true
    }.getOrDefault(false)
    fun apply(reply: Any, entry: InsightCache.Entry, thumbnail: (Int) -> String? = { null }): Any = runCatching {
        if (reply === replyDefault || entry.output.chapters.size !in 3..12 || !entry.timed ||
            entry.duration < InsightProtocol.MIN_CHAPTER_VIDEO_SECONDS) return reply
        val oldGuide = guide.invoke(reply) ?: return reply
        val oldList = viewPoint.invoke(oldGuide) ?: return reply
        // 官方响应一旦有任何章节，整份原样保留，绝不拼接或覆盖。
        if ((points.invoke(oldList) as? List<*>)?.isNotEmpty() != false) return reply
        val chapters = entry.output.chapters
        require(chapters.first().start == 0 && chapters.all { it.start in 0 until entry.duration && it.title.length in 1..50 })
        require(chapters.zipWithNext().all { (a, b) -> b.start - a.start >= 10 })
        val generated = chapters.mapIndexed { index, chapter -> itemPlan.edit(itemDefault) { builder ->
            from.invoke(builder, chapter.start.toLong())
            to.invoke(builder, (chapters.getOrNull(index + 1)?.start ?: entry.duration).toLong())
            content.invoke(builder, chapter.title)
            thumbnail(chapter.start)?.let { cover?.invoke(builder, it) }
            type.invoke(builder, CHAPTER_TYPE)
        } }
        val newList = listPlan.edit(oldList) { addAll.invoke(it, generated); permanent.invoke(it, true) }
        check((points.invoke(newList) as? List<*>)?.size == chapters.size)
        val newGuide = guidePlan.edit(oldGuide) { setViewPoint.invoke(it, newList) }
        replyPlan.edit(reply) { setGuide.invoke(it, newGuide) }.also { updated ->
            val actual = viewPoint.invoke(guide.invoke(updated))
            check((points.invoke(actual) as? List<*>)?.size == chapters.size)
        }
    }.getOrDefault(reply)

    companion object {
        const val REPLY = "com.bapis.bilibili.app.viewunite.v1.ViewProgressReply"
        // 官方 VideoPoint 的普通视频章节类型；时间字段为秒，交由宿主转成播放器毫秒。
        const val CHAPTER_TYPE = 2
        fun resolve(reply: Class<*>): InsightNativeChapters? = runCatching {
            fun getter(owner: Class<*>, name: String) = Lookup.inheritedMethodOrNull(owner, name)
                ?.takeUnless { it.isStatic } ?: error("missing-$name")
            fun default(owner: Class<*>) = Lookup.methodOrNull(owner, "getDefaultInstance")
                ?.takeIf { it.isStatic && it.returnType == owner }?.invoke(null) ?: error("missing-default")
            val readGuide = getter(reply, "getVideoGuide"); val readPoint = getter(readGuide.returnType, "getVideoPoint")
            val readPoints = getter(readPoint.returnType, "getPointsList")
            require(List::class.java.isAssignableFrom(readPoints.returnType))
            val item = Lookup.inheritedMethodOrNull(readPoint.returnType, "getPoints", Int::class.javaPrimitiveType!!)?.returnType
                ?: return null
            val replyPlan = ProtobufBuilderPlan.resolve(reply) ?: return null
            val guidePlan = ProtobufBuilderPlan.resolve(readGuide.returnType) ?: return null
            val listPlan = ProtobufBuilderPlan.resolve(readPoint.returnType) ?: return null
            val itemPlan = ProtobufBuilderPlan.resolve(item) ?: return null
            InsightNativeChapters(default(reply), readGuide, readPoint, readPoints, replyPlan, guidePlan, listPlan, itemPlan,
                default(item), replyPlan.method("setVideoGuide", readGuide.returnType) ?: return null,
                guidePlan.method("setVideoPoint", readPoint.returnType) ?: return null,
                listPlan.method("addAllPoints", Iterable::class.java) ?: return null,
                itemPlan.method("setFrom", Long::class.javaPrimitiveType!!) ?: return null,
                itemPlan.method("setTo", Long::class.javaPrimitiveType!!) ?: return null,
                itemPlan.method("setContent", String::class.java) ?: return null,
                itemPlan.method("setType", Int::class.javaPrimitiveType!!) ?: return null,
                listPlan.method("setPointPermanent", Boolean::class.javaPrimitiveType!!) ?: return null,
                itemPlan.method("setCover", String::class.java))
        }.getOrNull()
    }
}
