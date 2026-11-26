package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isSubclassOf
import java.lang.reflect.Method

/**
 * 详情页推荐「加载更多」（`viewunite.v1.ViewMoss#executeRelatesFeed` / `relatesFeed`）里删掉已知 AI 视频卡。
 *
 * - 业务调用方（27 版宿主逐版核对）：`DetailMoreRelateRepository` 与听视频模式的
 *   `ListenModeRelateRepository`，都走同步 `executeRelatesFeed`；`KViewMoss` 无业务调用方。
 * - 续页卡片与首屏推荐同为 `viewunite.common.RelateCard`，"已知"判定复用首屏那一套
 *   （[AiDeclaredVideoRegistry] 里的 aid）。卡片本身不带声明字段，没见过的 AI 视频仍会出现。
 * - 一页全部命中时原样交付：空页可能被宿主当成"没有更多了"而停止翻页。
 * - 副本改写、排除默认实例、改完回读，任何异常交付原响应。
 */
internal class AiRelatesFeedStripper private constructor(
    private val reply: Class<*>,
    private val defaultInstance: Any?,
    private val relatesList: Method,
    private val plan: ProtobufBuilderPlan,
    private val clearRelates: Method,
    private val addAllRelates: Method
) {
    /** @return 删过卡的新响应与删掉的张数；无需改写时返回 null。 */
    fun strip(original: Any, isKnownAi: (Any) -> Boolean): Pair<Any, Int>? {
        if (!reply.isInstance(original)) return null
        if (defaultInstance != null && original === defaultInstance) return null
        val cards = relatesList.invoke(original) as? List<*> ?: return null
        val retained = ProtobufListRetention.retainOrNull(cards) { !isKnownAi(it) } ?: return null
        if (retained.isEmpty()) return null
        val updated = plan.edit(original) { target ->
            clearRelates.invoke(target)
            addAllRelates.invoke(target, retained)
        }
        val readback = relatesList.invoke(updated) as? List<*>
        check(readback != null && readback.size == retained.size && readback.none { it != null && isKnownAi(it) }) {
            "relates feed readback failed"
        }
        return updated to (cards.size - retained.size)
    }

    companion object {
        const val SYNC_METHOD = "executeRelatesFeed"
        const val ASYNC_METHOD = "relatesFeed"
        const val REQUEST_CLASS = "com.bapis.bilibili.app.viewunite.v1.RelatesFeedReq"
        const val REPLY_CLASS = "com.bapis.bilibili.app.viewunite.v1.RelatesFeedReply"

        fun resolve(loader: ClassLoader, replyClassName: String = REPLY_CLASS): AiRelatesFeedStripper? = runCatching {
            val reply = KavaMemberLookup.classOrNull(loader, replyClassName) ?: return null
            val relatesList = KavaMemberLookup.methodOrNull(reply, "getRelatesList")
                ?.takeIf { it.returnType isSubclassOf classOf<List<*>>() } ?: return null
            val plan = ProtobufBuilderPlan.resolve(reply) ?: return null
            AiRelatesFeedStripper(
                reply = reply,
                defaultInstance = runCatching {
                    KavaMemberLookup.methodOrNull(reply, "getDefaultInstance")?.invoke(null)
                }.getOrNull(),
                relatesList = relatesList,
                plan = plan,
                clearRelates = plan.method("clearRelates") ?: return null,
                addAllRelates = plan.method("addAllRelates", classOf<Iterable<*>>()) ?: return null
            )
        }.getOrNull()
    }
}
