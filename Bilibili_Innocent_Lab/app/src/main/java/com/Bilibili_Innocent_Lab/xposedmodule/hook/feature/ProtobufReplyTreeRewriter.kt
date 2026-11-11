package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isSubclassOf
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.util.concurrent.ConcurrentHashMap

/**
 * 在一棵 Java protobuf 评论响应上删评论并重建（只在确有删除时复制）。
 *
 * Java 链路靠 getter 过滤（读列表时返回副本），Kotlin 新通道没有"之后再读 getter"这一步，只能把响应本身改掉。
 * 这里按**命名约定**在安装期/首次遇到某个消息类时解析：
 * - 评论列表字段：`get<X>List(): List<ReplyInfo>` + builder 的 `clear<X>()` / `addAll<X>(Iterable)`；
 * - 单条评论字段：`get<X>(): ReplyInfo` + `has<X>()` + builder 的 `set<X>(ReplyInfo)` / `clear<X>()`。
 * 这些名字是 protobuf 生成代码的固定形状，宿主里没有被混淆（2026-10-01 dexq 核对 9.14.0 的
 * `MainListReply` 与其 builder）。缺哪一样就跳过那个字段，不猜。
 *
 * 删除规则：列表里命中的直接去掉；单条字段只有 [removableSingles]（置顶类）命中才清掉，其余单条
 * （如 `DetailListReply.root`）只递归处理它的子回复。递归最多 [MAX_DEPTH] 层。
 *
 * [decide] 一次拿到同一层要判定的整批评论，返回要删的那些（按引用）；它负责判据与运行期证据。
 * 判据要看"这批评论挂在哪条消息下"时（子回复挂在主评论下，要拿主评论对照）给 [decideOwned]：
 * 第二个参数是装着这批评论的那条消息，给了就用它、不再调用 [decide]。
 */
internal class ProtobufReplyTreeRewriter(
    private val replyInfoClass: Class<*>,
    private val removableSingles: Set<String> = DEFAULT_REMOVABLE_SINGLES,
    /** 对每条保留下来的评论（已处理完子回复）做的改写；返回同一对象表示不改。 */
    private val mapReply: (Any) -> Any = { it },
    private val decideOwned: ((List<Any>, Any) -> Set<Any>)? = null,
    private val decide: (List<Any>) -> Set<Any>
) {
    private class ListField(val getter: Method, val clear: Method, val addAll: Method)
    private class SingleField(val name: String, val getter: Method, val has: Method, val set: Method, val clear: Method)
    private class Shape(val plan: ProtobufBuilderPlan, val lists: List<ListField>, val singles: List<SingleField>)

    private val shapes = ConcurrentHashMap<Class<*>, Any>()

    /** 删除结果：新消息（无改动时是原对象）与删除条数。 */
    class Result(val message: Any, val removed: Int)

    fun rewrite(message: Any): Result = rewrite(message, 0)

    private fun rewrite(message: Any, depth: Int): Result {
        if (depth >= MAX_DEPTH) return Result(message, 0)
        val shape = shapeOf(message.javaClass) ?: return Result(message, 0)
        var removed = 0
        val newLists = HashMap<ListField, List<Any>>()
        shape.lists.forEach { field ->
            val source = KotlinMossChannel.raw { field.getter.invoke(message) as? List<*> }
                ?.filterNotNull().orEmpty()
            if (source.isEmpty()) return@forEach
            val drop = decideIn(source, message)
            var changed = false
            val kept = ArrayList<Any>(source.size)
            source.forEach { item ->
                if (item in drop) {
                    changed = true
                    removed++
                } else {
                    val child = rewrite(item, depth + 1)
                    removed += child.removed
                    val mapped = mapReply(child.message)
                    if (mapped !== item) changed = true
                    kept += mapped
                }
            }
            if (changed) newLists[field] = kept
        }
        val singleEdits = HashMap<SingleField, Any?>() // null = 清掉
        val present = shape.singles.mapNotNull { field ->
            val has = KotlinMossChannel.raw { runCatching { field.has.invoke(message) as? Boolean }.getOrNull() } == true
            if (!has) return@mapNotNull null
            KotlinMossChannel.raw { field.getter.invoke(message) }?.let { field to it }
        }
        val removableValues = present.filter { it.first.name in removableSingles }.map { it.second }
        val singleDrop = if (removableValues.isEmpty()) emptySet() else decideIn(removableValues, message)
        present.forEach { (field, value) ->
            if (value in singleDrop) {
                singleEdits[field] = null
                removed++
            } else {
                val child = rewrite(value, depth + 1)
                removed += child.removed
                val mapped = mapReply(child.message)
                if (mapped !== value) singleEdits[field] = mapped
            }
        }
        if (newLists.isEmpty() && singleEdits.isEmpty()) return Result(message, removed)
        val rebuilt = shape.plan.edit(message) { builder ->
            newLists.forEach { (field, items) ->
                field.clear.invoke(builder)
                field.addAll.invoke(builder, items)
            }
            singleEdits.forEach { (field, value) ->
                if (value == null) field.clear.invoke(builder) else field.set.invoke(builder, value)
            }
        }
        return Result(rebuilt, removed)
    }

    private fun decideIn(replies: List<Any>, owner: Any): Set<Any> =
        decideOwned?.invoke(replies, owner) ?: decide(replies)

    private fun shapeOf(type: Class<*>): Shape? {
        val cached = shapes[type]
        if (cached != null) return cached as? Shape
        val shape = resolveShape(type)
        shapes[type] = shape ?: NONE
        return shape
    }

    private fun resolveShape(type: Class<*>): Shape? {
        val plan = ProtobufBuilderPlan.resolve(type) ?: return null
        val methods = KavaMemberLookup.declaredMethods(type, makeAccessible = true) {
            !it.isStatic && it.parameterCount == 0 && it.name.startsWith("get")
        }
        val lists = methods.mapNotNull { getter ->
            val name = getter.name.removePrefix("get").removeSuffix("List")
            if (!getter.name.endsWith("List") || name.isEmpty() || !returnsReplyInfoList(getter)) return@mapNotNull null
            val clear = plan.method("clear$name") ?: return@mapNotNull null
            val addAll = plan.method("addAll$name", classOf<Iterable<*>>()) ?: return@mapNotNull null
            ListField(getter, clear, addAll)
        }
        val singles = methods.mapNotNull { getter ->
            if (getter.returnType != replyInfoClass) return@mapNotNull null
            val name = getter.name.removePrefix("get")
            val has = KavaMemberLookup.methodOrNull(type, "has$name")
                ?.takeIf { !it.isStatic && it.returnType == classOf<Boolean>() } ?: return@mapNotNull null
            val set = plan.method("set$name", replyInfoClass) ?: return@mapNotNull null
            val clear = plan.method("clear$name") ?: return@mapNotNull null
            SingleField(name, getter, has, set, clear)
        }
        if (lists.isEmpty() && singles.isEmpty()) return null
        return Shape(plan, lists, singles)
    }

    private fun returnsReplyInfoList(method: Method): Boolean {
        if (!(method.returnType isSubclassOf classOf<List<*>>())) return false
        val argument = (method.genericReturnType as? ParameterizedType)?.actualTypeArguments?.singleOrNull()
        val raw = when (argument) {
            is Class<*> -> argument
            is ParameterizedType -> argument.rawType as? Class<*>
            else -> null
        }
        return raw == replyInfoClass
    }

    companion object {
        /** `MainListReply` 的三个置顶位：与 Java 链路的置顶 getter 过滤一致。 */
        val DEFAULT_REMOVABLE_SINGLES = setOf("UpTop", "AdminTop", "VoteTop")
        const val MAX_DEPTH = 3
        private val NONE = Any()
    }
}
