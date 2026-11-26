package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** 定位评论快捷回复 Intent Flow收集器；兼容 lambda 编号漂移与 R8 根包扁平化。 */
internal object CommentQuickReplyAdapter {
    const val DIAGNOSTIC = "dex.assist.comment_quick_reply"
    val query = DexAssistQuery.COMMENT_QUICK_REPLY_COLLECTOR
    const val COMMENT_CONTAINER = "com.bilibili.app.comment3.ui.CommentContainerImpl"
    private const val COMMENT3_PACKAGE = "com.bilibili.app.comment3."
    private const val CONTINUATION = "kotlin.coroutines.Continuation"
    private const val MAX_CANDIDATES = 64
    private const val MAX_VERIFIED_METHODS = 8

    fun applicable(loader: ClassLoader): Boolean = Lookup.hasClass(loader, COMMENT_CONTAINER)

    fun allowedOwnerName(name: String): Boolean = name.length in 1..512 &&
        (name.startsWith(COMMENT3_PACKAGE) || PlayerSpeedMenuAdapter.flattenedOwner(name))

    fun verifiedIntent(intentType: Class<*>): Boolean = runCatching {
        if (intentType.isInterface || intentType.isPrimitive || intentType.isArray ||
            !intentType.name.endsWith(".PublishDialogIntent")
        ) {
            return false
        }
        val fields = Lookup.declaredFields(intentType) { !Modifier.isStatic(it.modifiers) }
        val booleanCount = fields.count { it.type == classOf<Boolean>() }
        val hasPosEnum = fields.any { it.type.isEnum && it.type.simpleName == "Pos" }
        booleanCount >= 2 && hasPosEnum
    }.getOrDefault(false)

    fun verifiedMethod(method: Method): Boolean = runCatching {
        !Modifier.isStatic(method.modifiers) &&
            !Modifier.isAbstract(method.modifiers) &&
            !method.isBridge &&
            !method.isSynthetic &&
            method.parameterCount == 2 &&
            method.parameterTypes[1].name == CONTINUATION &&
            allowedOwnerName(method.declaringClass.name) &&
            verifiedIntent(method.parameterTypes[0])
    }.getOrDefault(false)

    private fun capturesCommentContainer(owner: Class<*>): Boolean = runCatching {
        Lookup.declaredFields(owner) {
            !Modifier.isStatic(it.modifiers) && it.type.name == COMMENT_CONTAINER
        }.isNotEmpty()
    }.getOrDefault(false)

    fun resolve(loader: ClassLoader, cached: List<HookPoint>): List<HookPoint> {
        if (cached.isEmpty() || cached.size > MAX_VERIFIED_METHODS) return emptyList()
        val verified = cached.mapNotNull { point ->
            if (point.viewField != null || !allowedOwnerName(point.className)) return@mapNotNull null
            val owner = Lookup.classOrNull(loader, point.className) ?: return@mapNotNull null
            Lookup.declaredMethods(owner) { method ->
                method.name == point.methodName &&
                    verifiedMethod(method) &&
                    (point.paramClassNames == null ||
                        method.parameterTypes.map(Class<*>::getName) == point.paramClassNames)
            }.singleOrNull()
        }.distinctBy(Method::toGenericString)
        return if (verified.size == cached.size) verified.map { it.toHookPoint() } else emptyList()
    }

    fun select(candidates: List<Method>): List<HookPoint> {
        if (candidates.size > MAX_CANDIDATES) return emptyList()
        val verified = candidates.asSequence()
            .filter(::verifiedMethod)
            .distinctBy(Method::toGenericString)
            .toList()
        if (verified.isEmpty()) return emptyList()

        val containerPrefix = "$COMMENT_CONTAINER$"
        val directContainer = verified.filter { it.declaringClass.name.startsWith(containerPrefix) }
        val chosen = when {
            directContainer.isNotEmpty() -> directContainer
            else -> {
                val commentPkg = verified.filter { it.declaringClass.name.startsWith(COMMENT3_PACKAGE) }
                val pool = commentPkg.ifEmpty { verified }
                val captured = pool.filter { capturesCommentContainer(it.declaringClass) }
                when {
                    captured.isNotEmpty() -> captured
                    pool.map { it.declaringClass.name }.distinct().size == 1 -> pool
                    else -> emptyList()
                }
            }
        }
        return if (chosen.size in 1..MAX_VERIFIED_METHODS) chosen.map { it.toHookPoint() } else emptyList()
    }

    fun needsQuery(
        loader: ClassLoader,
        directMethods: List<HookPoint>,
        cachedMethods: List<HookPoint>,
        enabled: Boolean,
        attempted: Boolean
    ): Boolean = enabled && !attempted && applicable(loader) &&
        directMethods.isEmpty() && resolve(loader, cachedMethods).isEmpty()

    fun locate(
        loader: ClassLoader,
        session: DexAssistSession?,
        enabled: Boolean,
        directMethods: List<HookPoint>,
        cachedMethods: List<HookPoint>,
        planned: Boolean
    ): List<HookPoint> {
        if (!enabled) return cachedMethods
        if (directMethods.isNotEmpty()) return directMethods
        val resolvedCached = resolve(loader, cachedMethods)
        if (resolvedCached.isNotEmpty()) return resolvedCached
        if (!planned) return emptyList()
        val candidates = (session?.result(query) as? DexAssistResult.Candidates)?.methods.orEmpty()
        return select(candidates)
    }

    private fun Method.toHookPoint(): HookPoint = HookPoint(
        className = declaringClass.name,
        methodName = name,
        paramClassNames = parameterTypes.map(Class<*>::getName)
    )
}
