package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** 仅缓存方法描述；复用前用当前宿主 ClassLoader 验真，不保存实验值或宿主实例。 */
data class CommentClassicStylePoints(val nativeReaders: List<HookPoint>, val kotlinReaders: List<HookPoint>) {
    fun toJson() = JSONObject().put("native", JSONArray(nativeReaders.map { it.toJson() }))
        .put("kotlin", JSONArray(kotlinReaders.map { it.toJson() }))

    companion object {
        fun fromJson(value: JSONObject): CommentClassicStylePoints? = runCatching {
            fun read(key: String): List<HookPoint> {
                val array = value.getJSONArray(key)
                require(array.length() <= 16)
                return (0 until array.length()).map {
                    val item = array.getJSONObject(it)
                    require(item.getJSONArray("params").length() in 2..8)
                    HookPoint.fromJson(item).also { point ->
                        require(point.viewField == null && point.className.length in 1..512 &&
                            point.methodName.length in 1..256 && point.paramClassNames!!.all { name -> name.length in 1..512 })
                    }
                }.distinct()
            }
            CommentClassicStylePoints(read("native"), read("kotlin"))
        }.getOrNull()
    }
}

/** 安装期仅反射；语义 DEX 查询由 VersionAdapter 后台集中调度。 */
internal object CommentClassicStyleLocator {
    const val DIAGNOSTIC = "dex.assist.comment_classic"
    const val NATIVE_OWNER = "com.bilibili.lib.dd.DeviceDecision"
    const val NATIVE_ENTRY = "com.bilibili.app.comment3.CommentV3Fragment"
    const val KOTLIN_OWNER = "kntr.base.dd.IDeviceDecisionKt"
    const val COMPOSE_ENTRY = "kntr.common.comment.entry.CommentSectionComposeFragment"
    const val NATIVE_ANCHOR = "comment.next_appearance"
    const val KOTLIN_ANCHOR = "comment.kntr.enabled"
    private const val MAX_CANDIDATES = 32

    enum class Family(val keyIndex: Int, val query: DexAssistQuery) {
        NATIVE(0, DexAssistQuery.COMMENT_CLASSIC_NATIVE),
        KOTLIN(1, DexAssistQuery.COMMENT_CLASSIC_KOTLIN)
    }

    fun matchesKey(key: Any?): Boolean = when (key) {
        NATIVE_ANCHOR, "comment.next_appearance_experiment_3", KOTLIN_ANCHOR,
        "comment.kntr.route.enabled", "comment.kntr.landscape.enabled", "comment.kntr.story.enabled" -> true
        else -> false
    }

    fun isContainerKey(key: Any?): Boolean = when (key) {
        KOTLIN_ANCHOR, "comment.kntr.route.enabled", "comment.kntr.landscape.enabled", "comment.kntr.story.enabled" -> true
        else -> false
    }

    fun kotlinApplicable(loader: ClassLoader) = Lookup.hasClass(loader, COMPOSE_ENTRY) ||
        Lookup.hasClass(loader, "kntr.common.comment.card.model.comment.CommentModel")

    fun verified(method: Method, family: Family): Boolean {
        if (method.returnType != Boolean::class.javaPrimitiveType || Modifier.isAbstract(method.modifiers) || method.isBridge) return false
        val p = method.parameterTypes
        return when (family) {
            Family.NATIVE -> !Modifier.isStatic(method.modifiers) && p.size in 2..8 &&
                p[0] == String::class.java && p[1] == Boolean::class.javaPrimitiveType
            Family.KOTLIN -> Modifier.isStatic(method.modifiers) && p.size in listOf(4, 6) &&
                p[0].isInterface && p[1] == String::class.java && p[2] == Boolean::class.javaPrimitiveType &&
                !p[3].isPrimitive && (p.size == 4 || p[4] == Int::class.javaPrimitiveType && p[5] == Any::class.java)
        }
    }

    fun direct(loader: ClassLoader, family: Family): List<Method> {
        val owner = Lookup.classOrNull(loader, if (family == Family.NATIVE) NATIVE_OWNER else KOTLIN_OWNER)
            ?: return emptyList()
        return select(Lookup.declaredMethods(owner, true) {
            (if (family == Family.NATIVE) it.name == "getBoolean" else it.name in listOf("getBool", "getBool\$default")) && verified(it, family)
        }, family)
    }

    fun isDefaultDelegate(wrapper: Method, reader: Method): Boolean =
        verified(wrapper, Family.KOTLIN) && wrapper.parameterCount == 6 && verified(reader, Family.KOTLIN) &&
            reader.parameterCount == 4 && reader.declaringClass == wrapper.declaringClass &&
            reader.parameterTypes.toList() == wrapper.parameterTypes.take(4)

    fun resolve(loader: ClassLoader, points: List<HookPoint>, family: Family): List<Method> {
        if (points.size > 16) return emptyList()
        val methods = points.map { point ->
            val owner = Lookup.classOrNull(loader, point.className) ?: return emptyList()
            Lookup.declaredMethods(owner, true) {
                it.name == point.methodName && it.parameterTypes.map(Class<*>::getName) == point.paramClassNames && verified(it, family)
            }.singleOrNull() ?: return emptyList()
        }
        return select(methods, family)
    }

    /** 多 owner 是歧义；不能把包含实验常量的合并 lambda 当作布尔读取方法。 */
    fun select(methods: Collection<Method>, family: Family): List<Method> {
        if (methods.size > MAX_CANDIDATES) return emptyList()
        val groups = methods.filter { verified(it, family) }.distinctBy(Method::toGenericString).groupBy { it.declaringClass }
        return groups.values.singleOrNull()?.takeIf { it.size <= 16 }?.sortedBy(Method::toGenericString).orEmpty()
    }

    fun readers(loader: ClassLoader, cached: CommentClassicStylePoints?, family: Family): List<Method> =
        direct(loader, family).ifEmpty { resolve(loader, if (family == Family.NATIVE) cached?.nativeReaders.orEmpty() else cached?.kotlinReaders.orEmpty(), family) }

    fun missing(loader: ClassLoader, cached: CommentClassicStylePoints? = null): Set<Family> = buildSet {
        if (readers(loader, cached, Family.NATIVE).isEmpty()) add(Family.NATIVE)
        if (kotlinApplicable(loader) && readers(loader, cached, Family.KOTLIN).isEmpty()) add(Family.KOTLIN)
    }

    fun locate(
        loader: ClassLoader,
        session: DexAssistSession?,
        enabled: Boolean,
        previous: CommentClassicStylePoints? = null,
        planned: Set<Family> = if (enabled) Family.entries.toSet() else emptySet()
    ): CommentClassicStylePoints {
        fun family(family: Family): List<HookPoint> {
            if (family == Family.KOTLIN && !kotlinApplicable(loader)) return emptyList()
            val prior = if (family == Family.NATIVE) previous?.nativeReaders.orEmpty() else previous?.kotlinReaders.orEmpty()
            val methods = direct(loader, family)
                .ifEmpty { if (prior.isEmpty()) emptyList() else resolve(loader, prior, family) }
                .ifEmpty {
                    if (!enabled || family !in planned) emptyList()
                    else select((session?.result(family.query) as? DexAssistResult.Candidates)?.methods.orEmpty(), family)
                }
            return methods.map { HookPoint(it.declaringClass.name, it.name, it.parameterTypes.map(Class<*>::getName)) }
        }
        return CommentClassicStylePoints(family(Family.NATIVE), family(Family.KOTLIN))
    }

    fun merge(live: CommentClassicStylePoints?, cached: CommentClassicStylePoints?): CommentClassicStylePoints? =
        if (live == null) cached else CommentClassicStylePoints(live.nativeReaders.ifEmpty { cached?.nativeReaders.orEmpty() },
            live.kotlinReaders.ifEmpty { cached?.kotlinReaders.orEmpty() })
}
