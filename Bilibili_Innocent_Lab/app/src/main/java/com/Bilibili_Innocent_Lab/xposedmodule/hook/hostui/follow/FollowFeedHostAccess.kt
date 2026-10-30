package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.follow

import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.FollowFeedAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/** 只缓存成员；不保存动态正文、模型实例或历史 ViewHolder。未知模型故障开放。 */
internal class FollowFeedHostAccess private constructor(
    private val module: Class<*>,
    private val owner: java.lang.reflect.Method,
    private val identity: java.lang.reflect.Method,
    private val first: java.lang.reflect.Method?,
    private val last: java.lang.reflect.Method?,
    private val localOwner: java.lang.reflect.Method?,
    private val quoted: java.lang.reflect.Method?,
    private val modules: java.lang.reflect.Method?,
    private val kind: java.lang.reflect.Method?,
    private val video: Class<*>?,
    private val current: Current? = null
) {
    private data class ModuleKinds(val projection: java.lang.reflect.Method, val viewType: Field,
        val names: Map<Int, String>) {
        fun read(module: Any): String? = projection.invoke(module)?.let { names[viewType.getInt(it)] }
    }

    private data class Current(val localOwner: Field, val localModules: Field, val parent: Field,
        val quoted: java.lang.reflect.Method, val kinds: ModuleKinds?) {
        // H.b递归父链；按已核对的j/n语义有界展开，异常循环不能交给宿主递归。
        fun top(local: Any): Any? {
            var cursor = local
            var slow: Any? = local
            var fast: Any? = local
            repeat(MAX_PARENT_DEPTH) {
                cursor = parent.get(cursor) ?: return cursor
                slow = slow?.let(parent::get)
                fast = fast?.let(parent::get)?.let(parent::get)
                if (fast != null && fast === slow) return null
            }
            return null
        }
    }

    val moduleClass: Class<*> get() = module
    val embeddedVideoSupported: Boolean get() = if (current != null) current.kinds != null else kind != null && video != null
    var unavailableReason: String? = null
        private set
    private val holders = HashMap<Class<*>, Field?>()

    fun handles(holder: Any): Boolean = holders[holder.javaClass] != null

    fun row(holder: Any, position: Int): FollowFeedRow? {
        unavailableReason = null
        val type = holder.javaClass
        val field = if (holders.containsKey(type)) holders[type] else {
            KavaMemberLookup.fields(type, includeSuperclasses = true, makeAccessible = true) {
                !Modifier.isStatic(it.modifiers) && it.type == module
            }.singleOrNull().also { holders[type] = it }
        } ?: return null
        val value = field.get(holder) ?: return null
        val item = owner.invoke(value) ?: return null
        val id = identity.invoke(item) as? Long ?: return null
        if (id <= 0) return null // 最常访问、排序和加载占位不属于动态卡片。
        val local = current?.localOwner?.get(value) ?: localOwner?.invoke(value) ?: return null
        val localModules = (current?.localModules?.get(local) ?: modules?.invoke(local)) as? List<*> ?: return null
        val localCount = localModules.size
        if (localCount > MAX_ROW_MODULES) return unavailable("local-module-budget")
        var moduleIndex = -1
        for (index in 0 until localCount) if (localModules[index] === value) { moduleIndex = index; break }
        if (moduleIndex < 0) return null
        val currentKind = current?.kinds?.read(value)
        if (current?.kinds != null && currentKind == null) return unavailable("module-descriptor-unavailable")
        val isQuoted = if (current != null) current.quoted.invoke(local) == true else quoted?.invoke(value) == true
        val localId = identity.invoke(local) as? Long ?: return null
        if (isQuoted && localId <= 0) return null
        var embeddedVideo = false
        if (!isQuoted && (if (current != null) currentKind == "Archive" else video?.isInstance(value) == true)) {
            for (index in 0 until moduleIndex) {
                val preceding = localModules[index] ?: continue
                val precedingKind = if (current != null) current.kinds?.read(preceding)
                    else (kind?.invoke(preceding) as? Enum<*>)?.name
                if (current?.kinds != null && precedingKind == null) return unavailable("module-descriptor-unavailable")
                if (precedingKind in FollowFeedModelPolicy.descriptionModules) {
                    embeddedVideo = true; break
                }
            }
        }
        val reference = FollowFeedModelPolicy.reference(value, id, localId, localModules, isQuoted, embeddedVideo)
        val topModules = current?.let { profile ->
            val top = profile.top(local) ?: return unavailable("parent-chain-unavailable")
            profile.localModules.get(top) as? List<*> ?: return null
        }
        if (topModules != null && topModules.size > MAX_ROW_MODULES) return unavailable("top-module-budget")
        val isFirst = if (current != null) topModules?.firstOrNull() === value else first?.invoke(value) == true
        val isLast = if (current != null) topModules?.lastOrNull() === value else last?.invoke(value) == true
        return FollowFeedRow(id, isFirst, isLast, position, reference)
    }

    /** 背景颜色入口也使用本地归属判断；M() 返回顶层，不能拿它判断引用。 */
    internal fun isQuotedModule(value: Any): Boolean? {
        if (!module.isInstance(value)) return null
        return runCatching {
            if (current != null) current.localOwner.get(value)?.let { current.quoted.invoke(it) as? Boolean }
            else quoted?.invoke(value) as? Boolean
        }.getOrNull()
    }

    private fun unavailable(reason: String): FollowFeedRow? { unavailableReason = reason; return null }

    companion object {
        internal const val MAX_ROW_MODULES = 256
        private const val MAX_PARENT_DEPTH = 32
        fun resolve(loader: ClassLoader): FollowFeedHostAccess? =
            FollowFeedAdapter.resolve(loader, FollowFeedAdapter.direct(loader))?.host

        internal fun resolve(module: Class<*>, item: Class<*>, family: String): FollowFeedHostAccess? = when (family) {
            "legacy" -> KavaMemberLookup.classOrNull(module.classLoader,
                "com.bilibili.bplus.followinglist.model.m7")?.let { legacy(module, item, it) }
            "current" -> current(module, item)
            else -> null
        }

        internal fun legacy(module: Class<*>, item: Class<*>, video: Class<*>): FollowFeedHostAccess? = runCatching {
            // 已实查的宿主模型：j0.M() 返回转发归属的顶层 q0，q0.f() 是动态稳定身份。
            // d0/g0 比较顶层模块列表首尾，包含引用中的模块；不能改用嵌套投稿身份 N()。
            // 同名无参方法可能同时有桥接方法，按返回类型筛选，不依赖枚举顺序。
            fun method(owner: Class<*>, name: String, accepts: (Class<*>) -> Boolean) =
                KavaMemberLookup.firstDeclaredMethodOrNull(owner) {
                    it.name == name && it.parameterCount == 0 && accepts(it.returnType)
                }
            val owner = method(module, "M") { it == item } ?: return null
            val identity = method(item, "f") { it == Long::class.javaPrimitiveType } ?: return null
            val first = method(module, "d0") { it == Boolean::class.javaPrimitiveType } ?: return null
            val last = method(module, "g0") { it == Boolean::class.javaPrimitiveType } ?: return null
            val localOwner = method(module, "O") { it == item } ?: return null
            val quoted = method(module, "f0") { it == Boolean::class.javaPrimitiveType } ?: return null
            val modules = method(item, "i") { List::class.java.isAssignableFrom(it) } ?: return null
            val kind = method(module, "T") { it.isEnum } ?: return null
            if (!module.isAssignableFrom(video)) return null
            FollowFeedHostAccess(module, owner, identity, first, last, localOwner, quoted, modules, kind, video)
        }.getOrNull()

        /** 9.15 G.a / 9.16 G.b 是本地 H；M 返回顶层，引用局部列表仍读 H.n。 */
        internal fun current(module: Class<*>, item: Class<*>): FollowFeedHostAccess? = runCatching {
            fun method(owner: Class<*>, name: String, result: Class<*>) =
                KavaMemberLookup.declaredMethods(owner, true) {
                    it.name == name && !Modifier.isStatic(it.modifiers) && !Modifier.isAbstract(it.modifiers) &&
                        it.parameterCount == 0 && it.returnType == result
                }.singleOrNull()
            val owner = method(module, "M", item) ?: return null
            val identity = method(item, "a", Long::class.javaPrimitiveType!!) ?: return null
            val localOwner = KavaMemberLookup.declaredFields(module, true) {
                !Modifier.isStatic(it.modifiers) && it.type == item
            }.singleOrNull()?.takeIf { it.name in setOf("a", "b") } ?: return null
            val localModules = KavaMemberLookup.fieldOrNull(item, "n")?.takeIf {
                !Modifier.isStatic(it.modifiers) && List::class.java.isAssignableFrom(it.type)
            } ?: return null
            method(item, "b", List::class.java) ?: return null
            val parent = KavaMemberLookup.fieldOrNull(item, "j")?.takeIf {
                !Modifier.isStatic(it.modifiers) && it.type == item
            } ?: return null
            val quoted = method(item, "h", Boolean::class.javaPrimitiveType!!) ?: return null
            // O已变成背景开关，不能把它当成本地owner；确认新家族完整的背景ABI。
            method(module, "O", Boolean::class.javaPrimitiveType!!) ?: return null
            FollowFeedHostAccess(module, owner, identity, null, null, null, null, null, null, null,
                Current(localOwner, localModules, parent, quoted, moduleKinds(module)))
        }.getOrNull()

        /** 新quick背景真实消费者用G.Q().a与ModuleEnum.Author.viewType比较；不猜混淆子类或数值。 */
        private fun moduleKinds(module: Class<*>): ModuleKinds? = runCatching {
            val projection = KavaMemberLookup.declaredMethods(module, true) {
                it.name == "Q" && !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 &&
                    !it.returnType.isPrimitive && it.returnType != Any::class.java
            }.singleOrNull() ?: return null
            val viewType = KavaMemberLookup.fieldOrNull(projection.returnType, "a")?.takeIf {
                !Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType
            } ?: return null
            val enumClass = KavaMemberLookup.classOrNull(module.classLoader,
                "com.bilibili.bplus.followinglist.module.item.ModuleEnum")?.takeIf { it.isEnum } ?: return null
            val reader = KavaMemberLookup.declaredMethods(enumClass, true) {
                it.name == "viewType" && !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 &&
                    it.returnType == Int::class.javaPrimitiveType
            }.singleOrNull() ?: return null
            val constants = enumClass.enumConstants?.takeIf { it.size in 4..256 } ?: return null
            val entries = constants.map { (reader.invoke(it) as? Int ?: return null) to (it as Enum<*>).name }
            if (entries.map { it.first }.distinct().size != entries.size) return null
            val names = entries.toMap()
            if (!names.values.containsAll(FollowFeedModelPolicy.descriptionModules + "Archive")) return null
            ModuleKinds(projection, viewType, names)
        }.getOrNull()
    }
}
