package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import org.json.JSONObject
import java.lang.reflect.Field
import java.lang.reflect.Method
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isAbstract
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic

/** 仅保存已验真的 owner/wrapper 类名；缓存恢复时必须经当前 ClassLoader 以 modern 全 ABI 复验。 */
data class PlayerSpeedSessionClasses(val owner: String, val wrapper: String?) {
    fun toJson() = JSONObject().put("owner", owner).put("wrapper", wrapper ?: JSONObject.NULL)

    companion object {
        fun fromJson(value: JSONObject): PlayerSpeedSessionClasses? {
            fun name(key: String) = value.optString(key).takeIf {
                it.length in 1..512 && it.all { c -> c.isLetterOrDigit() || c in "._$" }
            }
            val wrapper = if (value.isNull("wrapper")) null else name("wrapper") ?: return null
            return PlayerSpeedSessionClasses(name("owner") ?: return null, wrapper)
        }
    }
}

/** Stable business anchors; obfuscated owners are derived from validated member types. */
internal object PlayerSpeedSessionLocator {
    const val RUN = "com.bilibili.ship.theseus.keel.player.TheseusKeelPlayer\$runPlayable\$1"
    const val PARAMS = "com.bilibili.app.gemini.base.player.GeminiCommonPlayableParams"
    const val CORE = "tv.danmaku.biliplayerv2.service.IPlayerCoreService"
    const val CONTEXT = "tv.danmaku.videoplayer.coreV2.MediaPlayContextImpl"
    const val PREPARED = "tv.danmaku.ijk.media.player.IMediaPlayer\$OnPreparedListener"
    const val PLAYER = "tv.danmaku.ijk.media.player.IMediaPlayer"
    const val RESOURCE = "com.bilibili.lib.media.resource.MediaResource"
    const val INDEX = "com.bilibili.lib.media.resource.PlayIndex"
    const val ITEM = "tv.danmaku.videoplayer.coreV2.MediaItem"
    const val WRAPPER = "com.bilibili.ship.theseus.united.player.oldway.playercontainer.TheseusPlayerContainerProvider\$providePlayerContainer\$playerContainer\$1\$1\$1"
    private const val KEEL_PACKAGE = "com.bilibili.ship.theseus.keel.player."
    private const val CONTINUATION = "kotlin.coroutines.Continuation"
    private const val CONTINUATION_IMPL = "kotlin.coroutines.jvm.internal.ContinuationImpl"

    data class Modern(val run: Method, val active: Method, val params: Method,
        val aid: Method, val cid: Method, val manager: Field, val wrapper: java.lang.reflect.Constructor<*>?)

    const val DIAGNOSTIC = "dex.assist.player_speed_session"
    private const val MAX_CANDIDATES = 32
    val queries = setOf(DexAssistQuery.SPONSOR_RUN_PLAYABLE, DexAssistQuery.SPONSOR_PLAYER_WRAPPER)

    fun applicable(loader: ClassLoader): Boolean =
        Lookup.hasClass(loader, PARAMS) && Lookup.hasClass(loader, PlayerSpeedLocator.SPEED_MANAGER)

    fun needsQuery(enabled: Boolean, applicable: Boolean, directFound: Boolean) = enabled && applicable && !directFound
    fun refreshCache(enabled: Boolean, applicable: Boolean, directFound: Boolean, cachedFound: Boolean, attempted: Boolean) =
        needsQuery(enabled, applicable, directFound) && !cachedFound && !attempted

    fun modern(loader: ClassLoader): Modern? = runCatching {
        val continuation = Lookup.classOrNull(loader, RUN) ?: return null
        val params = Lookup.classOrNull(loader, PARAMS) ?: return null
        val manager = Lookup.classOrNull(loader, PlayerSpeedLocator.SPEED_MANAGER) ?: return null
        val owner = Lookup.declaredFields(continuation, true) {
            !it.isStatic && it.type.name.startsWith(KEEL_PACKAGE) &&
                it.type != continuation
        }.singleOrNull()?.type ?: return null
        modern(owner, params, manager, Lookup.classOrNull(loader, WRAPPER))
    }.getOrNull()

    /** 安装期入口：先直接定位，未命中才用后台已验真的缓存类名复核；从不触发 DEX 查询。 */
    fun modern(loader: ClassLoader, cached: PlayerSpeedSessionClasses?): Modern? =
        modern(loader) ?: cached?.let { resolve(loader, it) }

    fun resolve(loader: ClassLoader, classes: PlayerSpeedSessionClasses): Modern? = runCatching {
        val owner = Lookup.classOrNull(loader, classes.owner) ?: return null
        val params = Lookup.classOrNull(loader, PARAMS) ?: return null
        val manager = Lookup.classOrNull(loader, PlayerSpeedLocator.SPEED_MANAGER) ?: return null
        val wrapper = classes.wrapper?.let { Lookup.classOrNull(loader, it) ?: return null }
        modern(owner, params, manager, wrapper)
    }.getOrNull()

    /**
     * 复用 SPONSOR_RUN_PLAYABLE / SPONSOR_PLAYER_WRAPPER 的原始候选，不复用 Sponsor 的 Resolved：
     * 倍速会话只取 run owner 与容器 wrapper，不耦合 seek/container 全链的 singleOrNull 结论。
     * 每个候选组合都必须完整通过 modern 全 ABI 验真，且全局只能剩一个 (owner, wrapper) 组合。
     */
    fun assisted(loader: ClassLoader, session: DexAssistSession?): PlayerSpeedSessionClasses? {
        val run = session?.result(DexAssistQuery.SPONSOR_RUN_PLAYABLE) as? DexAssistResult.Candidates ?: return null
        val wrappers = session.result(DexAssistQuery.SPONSOR_PLAYER_WRAPPER) as? DexAssistResult.Candidates ?: return null
        return select(loader, run, wrappers)
    }

    fun select(loader: ClassLoader, run: DexAssistResult.Candidates, wrappers: DexAssistResult.Candidates): PlayerSpeedSessionClasses? {
        if (run.methods.size > MAX_CANDIDATES || wrappers.methods.size > MAX_CANDIDATES) return null
        val params = Lookup.classOrNull(loader, PARAMS) ?: return null
        val manager = Lookup.classOrNull(loader, PlayerSpeedLocator.SPEED_MANAGER) ?: return null
        val wrapperClasses = (wrappers.methods.map { it.declaringClass } + listOfNotNull(Lookup.classOrNull(loader, WRAPPER)))
            .distinct()
        val verified = run.methods.map { it.declaringClass }.distinct().flatMap { owner ->
            (wrapperClasses + listOf(null)).mapNotNull { wrapper ->
                // 以 modern 实际验真的构造器为准：输入 wrapper 类未命中构造器时结果等同无 wrapper，
                // 不能把未命中的类记成另一个具体 wrapper 造成假歧义。
                modern(owner, params, manager, wrapper)?.let { owner to it.wrapper?.declaringClass }
            }
        }.distinct()
        // wrapper 只影响容器作用域捕获：同一 owner 下具体 wrapper 优先于无 wrapper，
        // 但多个具体 wrapper 或多个 owner 同时通过仍是歧义，按缺失处理。
        val byOwner = verified.groupBy({ it.first }, { it.second })
        val (owner, passed) = byOwner.entries.singleOrNull() ?: return null
        val concrete = passed.filterNotNull().distinct()
        if (concrete.size > 1) return null
        return PlayerSpeedSessionClasses(owner.name, concrete.singleOrNull()?.name)
    }

    internal fun modern(owner: Class<*>, params: Class<*>, manager: Class<*>, wrapperClass: Class<*>?): Modern? {
        val managerField = Lookup.declaredFields(owner, true) { !it.isStatic && it.type == manager }
            .singleOrNull() ?: return null
        val aid = Lookup.methodOrNull(params, "getAvid")?.takeIf { !it.isStatic && it.returnType == classOf<Long>() } ?: return null
        val cid = Lookup.methodOrNull(params, "getCid")?.takeIf { !it.isStatic && it.returnType == classOf<Long>() } ?: return null
        val wrapper = wrapperClass?.let { cls ->
            Lookup.declaredConstructors(cls, true) {
                val parameters = it.parameterTypes
                parameters.size == 3 && parameters[0].name == CORE && parameters[1] === owner &&
                    parameters[2].name == "kotlinx.coroutines.CoroutineScope"
            }.singleOrNull()
        }
        val methods = Lookup.declaredMethods(owner, true)
        return methods.mapNotNull { run ->
            if (run.isStatic || run.isAbstract || run.isBridge || run.returnType != classOf<Any>() || run.parameterCount != 2) {
                return@mapNotNull null
            }
            val (playable, continuation) = run.parameterTypes
            if (!playable.isInterface || (continuation.name != CONTINUATION && continuation.name != CONTINUATION_IMPL)) {
                return@mapNotNull null
            }
            // 9.19 moves the playable interface to the root package. Require the resume ABI and
            // the same keel owner in the core wrapper before considering that relocated interface.
            val relocated = wrapper != null && continuation.name == CONTINUATION_IMPL &&
                PlayerSpeedMenuAdapter.flattenedOwner(playable.name)
            if (!playable.name.startsWith(KEEL_PACKAGE) && !relocated) return@mapNotNull null
            val active = methods.singleOrNull {
                !it.isStatic && !it.isAbstract && !it.isBridge && it.parameterCount == 0 && it.returnType == playable
            } ?: return@mapNotNull null
            val getParams = Lookup.declaredMethods(playable, true) {
                !it.isStatic && !it.isBridge && it.parameterCount == 0 && it.returnType == params
            }.singleOrNull() ?: return@mapNotNull null
            Modern(run, active, getParams, aid, cid, managerField, wrapper)
        }.singleOrNull()
    }

    data class Prepared(val register: Method, val core: Class<*>, val listener: Class<*>, val player: Class<*>,
        val media: Method, val item: Method, val itemId: Method, val index: Method, val from: Field,
        val speed: Method, val allowedSources: Set<String>)

    fun prepared(loader: ClassLoader): Prepared? = runCatching {
        val core = Lookup.classOrNull(loader, CORE) ?: return null
        val listener = Lookup.classOrNull(loader, PREPARED) ?: return null
        val context = Lookup.classOrNull(loader, CONTEXT) ?: return null
        val player = Lookup.classOrNull(loader, PLAYER) ?: return null
        val resource = Lookup.classOrNull(loader, RESOURCE) ?: return null
        val item = Lookup.classOrNull(loader, ITEM) ?: return null
        val index = Lookup.classOrNull(loader, INDEX) ?: return null
        val register = Lookup.methodOrNull(context, "setOnPreparedListener", listener)
            ?.takeIf { !it.isStatic && it.returnType == Void.TYPE } ?: return null
        val sources = listOf("FROM__BANGUMI", "FROM__DOWNLOADED", "FROM__PUGV", "FROM__VOD_COMMON", "FROM__VUPLOAD")
            .mapNotNull { name -> Lookup.fieldOrNull(index, name)?.takeIf { it.isStatic && it.type == classOf<String>() }?.get(null) as? String }.toSet()
        if (sources.isEmpty()) return null
        Prepared(register, core, listener, player,
            Lookup.methodOrNull(core, "getMediaResource")?.takeIf { it.returnType == resource } ?: return null,
            Lookup.methodOrNull(core, "getCurrentMediaItem")?.takeIf { it.returnType == item } ?: return null,
            Lookup.methodOrNull(item, "getId")?.takeIf { it.returnType == classOf<String>() } ?: return null,
            Lookup.methodOrNull(resource, "getPlayIndex")?.takeIf { it.returnType == index } ?: return null,
            Lookup.fieldOrNull(index, "mFrom")?.takeIf { !it.isStatic && it.type == classOf<String>() } ?: return null,
            Lookup.methodOrNull(core, "getPlaySpeed", classOf<Boolean>())?.takeIf { it.returnType == classOf<Float>() } ?: return null,
            sources)
    }.getOrNull()
}
