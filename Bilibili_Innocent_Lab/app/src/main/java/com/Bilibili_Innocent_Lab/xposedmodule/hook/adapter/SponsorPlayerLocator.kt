package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import org.json.JSONObject
import java.lang.reflect.Modifier

/** 仅保存成员所属类名；缓存重新使用时必须在当前宿主 ClassLoader 完整验真。 */
data class SponsorPlayerClasses(val continuation: String, val wrapper: String,
    val containerScope: String?, val seek: String) {
    fun toJson() = JSONObject().put("continuation", continuation).put("wrapper", wrapper)
        .put("container", containerScope ?: JSONObject.NULL).put("seek", seek)

    companion object {
        fun fromJson(value: JSONObject): SponsorPlayerClasses? {
            fun name(key: String) = value.optString(key).takeIf {
                it.length in 1..512 && it.startsWith("com.bilibili.ship.theseus.") &&
                    it.all { c -> c.isLetterOrDigit() || c in "._$" }
            }
            val container = if (value.isNull("container")) null else name("container") ?: return null
            return SponsorPlayerClasses(name("continuation") ?: return null, name("wrapper") ?: return null,
                container, name("seek") ?: return null)
        }
    }
}

internal object SponsorPlayerLocator {
    const val DIAGNOSTIC = "dex.assist.sponsor_player"
    const val FAMILY = "com.bilibili.ship.theseus.keel.player"
    const val CONTAINER_PACKAGE = "com.bilibili.ship.theseus.united.player.oldway.playercontainer"
    const val COROUTINE_ERROR = "call to 'resume' before 'invoke' with coroutine"
    private const val MAX_CANDIDATES = 32
    val queries = setOf(DexAssistQuery.SPONSOR_RUN_PLAYABLE, DexAssistQuery.SPONSOR_PLAYER_WRAPPER,
        DexAssistQuery.SPONSOR_CONTAINER_SCOPE)

    data class Resolved(val player: SponsorPlayerAccess, val seek: SponsorSeekAccess, val classes: SponsorPlayerClasses)

    fun direct(loader: ClassLoader): Resolved? = resolve(loader, SponsorPlayerClasses(
        SponsorPlayerAccess.RUN_CLASS, SponsorPlayerAccess.WRAPPER_CLASS,
        SponsorPlayerAccess.CONTAINER_SCOPE_CLASS, SponsorSeekAccess.CLASS))

    fun resolve(loader: ClassLoader, classes: SponsorPlayerClasses): Resolved? {
        val player = SponsorPlayerAccess.resolve(loader, classes.continuation, classes.wrapper, classes.containerScope)
            ?: return null
        val seek = SponsorSeekAccess.resolve(loader, player, classes.seek) ?: return null
        return Resolved(player, seek, classes.copy(containerScope = player.containerScope?.constructor?.declaringClass?.name))
    }

    fun applicable(loader: ClassLoader): Boolean = Lookup.hasClass(loader, SponsorPlayerAccess.CORE_CLASS) &&
        Lookup.hasClass(loader, SponsorPlayerAccess.PARAMS_CLASS)

    fun needsQuery(enabled: Boolean, applicable: Boolean, directFound: Boolean) = enabled && applicable && !directFound
    fun refreshCache(enabled: Boolean, applicable: Boolean, directFound: Boolean, cachedFound: Boolean, attempted: Boolean) =
        needsQuery(enabled, applicable, directFound) && !cachedFound && !attempted

    fun assisted(loader: ClassLoader, session: DexAssistSession?): Resolved? {
        val run = session?.result(DexAssistQuery.SPONSOR_RUN_PLAYABLE) as? DexAssistResult.Candidates ?: return null
        val wrappers = session.result(DexAssistQuery.SPONSOR_PLAYER_WRAPPER) as? DexAssistResult.Candidates ?: return null
        val scopes = session.result(DexAssistQuery.SPONSOR_CONTAINER_SCOPE) as? DexAssistResult.Candidates
            ?: DexAssistResult.Candidates(emptyList())
        return select(loader, run, wrappers, scopes)
    }

    /** 运行边界、包装器和保护协程必须来自同一 owner；不按名称相似度拼接。 */
    fun select(loader: ClassLoader, run: DexAssistResult.Candidates, wrappers: DexAssistResult.Candidates,
        scopes: DexAssistResult.Candidates): Resolved? {
        if (listOf(run.methods.size, wrappers.methods.size, scopes.classes.size).any { it > MAX_CANDIDATES }) return null
        val runs = run.methods.flatMap { method ->
            if (Modifier.isStatic(method.modifiers) || method.returnType != Any::class.java ||
                method.parameterCount != 2 || !method.parameterTypes[0].isInterface ||
                !SponsorPlayerAccess.acceptsPlayableClass(method.declaringClass.name, method.name,
                    method.parameterTypes[0].name) || method.parameterTypes[1].name !in
                setOf("kotlin.coroutines.Continuation", "kotlin.coroutines.jvm.internal.ContinuationImpl")) return@flatMap emptyList()
            run.relatedClasses[method].orEmpty().takeIf { it.size <= MAX_CANDIDATES }.orEmpty().filter { cls ->
                cls.superclass?.name == "kotlin.coroutines.jvm.internal.ContinuationImpl" &&
                    Lookup.fieldOrNull(cls, "label")?.let { !Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType } == true &&
                    Lookup.declaredFields(cls, true) { !Modifier.isStatic(it.modifiers) && it.type.name.startsWith("$FAMILY.") && it.type != cls }
                        .singleOrNull()?.type == method.declaringClass
            }.map { method to it }
        }.distinct()
        val (runMethod, continuation) = runs.singleOrNull() ?: return null
        val core = Lookup.classOrNull(loader, SponsorPlayerAccess.CORE_CLASS) ?: return null
        val routes = wrappers.methods.filter { method ->
            method.name == "seekTo" && method.returnType == Void.TYPE && !Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.toList() == listOf(Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType) &&
                SponsorPlayerAccess.resolveWrapper(method.declaringClass, core, runMethod.declaringClass) != null
        }.distinct()
        val wrapperSeek = routes.singleOrNull() ?: return null
        val seekClasses = wrappers.relatedClasses[wrapperSeek].orEmpty().takeIf { it.size <= MAX_CANDIDATES } ?: return null
        val containers = (listOf(SponsorPlayerAccess.CONTAINER_SCOPE_CLASS) + scopes.classes.map(Class<*>::getName)).distinct()
        val verified = containers.flatMap { container -> seekClasses.mapNotNull { seek ->
            resolve(loader, SponsorPlayerClasses(continuation.name, wrapperSeek.declaringClass.name, container, seek.name))
                ?.takeIf { it.player.run == runMethod && it.player.wrapper.declaringClass == wrapperSeek.declaringClass &&
                    it.seek.constructor.declaringClass == seek }
        } }.distinctBy { it.classes }
        val canonical = verified.filter { it.classes.containerScope == SponsorPlayerAccess.CONTAINER_SCOPE_CLASS }
        return canonical.singleOrNull() ?: if (canonical.isNotEmpty()) null else verified.singleOrNull()
    }
}
