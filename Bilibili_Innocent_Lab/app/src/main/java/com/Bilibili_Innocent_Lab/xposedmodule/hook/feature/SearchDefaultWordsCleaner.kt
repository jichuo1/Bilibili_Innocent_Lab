package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf
import java.lang.reflect.Method

/** 仅清推荐文案，在副本上操作；不修改 goto/uri/param 等点击目标。 */
internal class SearchDefaultWordsCleaner private constructor(
    private val owner: Class<*>, private val builder: ProtobufBuilderPlan,
    private val fields: List<Pair<Method, Method>>
) {
    val complete: Boolean get() = fields.size == TEXT_CLEARS.size
    fun clean(reply: Any, environment: HookEnvironment): Any {
        if (!owner.isInstance(reply)) return reply
        return runCatching {
            val present = fields.filter { (read, _) -> (read.invoke(reply) as? String)?.isNotEmpty() == true }
            if (present.isEmpty()) return@runCatching reply
            environment.reportRuntimeEvidence(CAPABILITY, FeatureRuntimeStage.OBSERVED)
            val updated = builder.edit(reply) { value -> present.forEach { (_, clear) -> clear.invoke(value) } }
            check(present.all { (read, _) -> read.invoke(updated) == "" })
            environment.reportRuntimeEvidence(CAPABILITY, FeatureRuntimeStage.APPLIED)
            updated
        }.getOrElse {
            environment.reportRuntimeEvidence(CAPABILITY, FeatureRuntimeStage.ERROR)
            environment.logError("search_default_words_copy", "[BIL] 搜索推荐词副本清理失败，保留原响应: ${it.javaClass.simpleName}")
            reply
        }
    }
    companion object {
        private const val CAPABILITY = "home_top_bar_search_word_hidden"
        private val TEXT_CLEARS = listOf("clearShow", "clearWord", "clearValue")
        fun resolve(owner: Class<*>): SearchDefaultWordsCleaner? {
            val builder = ProtobufBuilderPlan.resolve(owner) ?: return null
            val fields = TEXT_CLEARS.mapNotNull { name ->
                val read = KavaMemberLookup.methodOrNull(owner, "get" + name.removePrefix("clear"))
                    ?.takeIf { it.returnType == classOf<String>() } ?: return@mapNotNull null
                read to (builder.method(name) ?: return@mapNotNull null)
            }
            return fields.takeIf { it.isNotEmpty() }?.let { SearchDefaultWordsCleaner(owner, builder, it) }
        }
    }
}
