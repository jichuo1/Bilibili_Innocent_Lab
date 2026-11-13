package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor.SponsorVideoId
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** 31 样本共享的业务边界；仅安装期查找，运行期只用已验证的成员。 */
internal class SponsorPlayerAccess private constructor(
    val run: Method,
    val wrapper: Constructor<*>,
    val wrapperOwnerIndex: Int,
    val wrapperCoreIndex: Int,
    val scope: Constructor<*>?,
    val containerScope: ContainerScope?,
    val active: Method,
    private val continuation: Class<*>,
    private val continuationOwner: Field,
    private val runLabel: Field,
    private val params: Method,
    private val bvid: Method,
    private val cid: Method,
    private val aid: Method,
    private val business: Method,
    private val ugc: Any,
    val position: Method,
    val duration: Method,
    val state: Method,
    val seek: Method,
    val registerProgress: Method,
    val unregisterProgress: Method,
    val registerSeek: Method,
    val unregisterSeek: Method,
    val registerState: Method,
    val unregisterState: Method,
    val registerRelease: Method,
    val unregisterRelease: Method,
    val progressObserver: Class<*>,
    val seekObserver: Class<*>,
    val stateObserver: Class<*>,
    val releaseObserver: Class<*>
) {
    class ContainerScope(val constructor: Constructor<*>, val contextIndex: Int, val containerIndex: Int,
        val core: Method)
    class WrapperBinding(val constructor: Constructor<*>, val ownerIndex: Int, val coreIndex: Int)
    /** runPlayable 正常播放时保持挂起；只接受已激活的实参或 label=2 的播放续体。 */
    fun bound(owner: Any, requested: Any?, resumed: Any?): Boolean = runCatching {
        val current = active.invoke(owner) ?: return false
        if (requested != null) current === requested
        else resumed != null && continuation.isInstance(resumed) && continuationOwner.get(resumed) === owner &&
            runLabel.getInt(resumed) == 2
    }.getOrDefault(false)

    // 只缓存确定的 AID→BVID 映射；CID 和当前 playable 每次重新读取，不能沿用旧分 P。
    @Volatile private var derivedBvid: Pair<Long, String>? = null

    fun video(owner: Any, diagnostic: ((String) -> Unit)? = null): SponsorVideoId? = runCatching {
        val playable = active.invoke(owner) ?: return unavailable(diagnostic, "missing-active")
        val values = params.invoke(playable) ?: return unavailable(diagnostic, "missing-params")
        if (business.invoke(values) !== ugc) return unavailable(diagnostic, "non-ugc")
        val avid = aid.invoke(values) as? Long ?: return unavailable(diagnostic, "invalid-aid")
        if (avid <= 0) return unavailable(diagnostic, "invalid-aid")
        val part = cid.invoke(values) as? Long ?: return unavailable(diagnostic, "invalid-cid")
        if (part <= 0) return unavailable(diagnostic, "invalid-cid")
        val supplied = bvid.invoke(values) as? String ?: return unavailable(diagnostic, "missing-bvid")
        val identifier = if (supplied.isEmpty()) {
            // 普通 UGC 工厂不填 BVID，空值才使用同一个 active playable 的 AID。
            val cached = derivedBvid
            val converted = if (cached?.first == avid) cached.second else
                SponsorVideoId.bvidFromAvid(avid)?.also { derivedBvid = avid to it }
                    ?: return unavailable(diagnostic, "invalid-aid")
            diagnostic?.invoke("aid-derived")
            converted
        } else supplied
        runCatching { SponsorVideoId(identifier, part) }.getOrElse { unavailable(diagnostic, "invalid-bvid") }
    }.getOrElse { unavailable(diagnostic, "read-failed") }

    private fun unavailable(diagnostic: ((String) -> Unit)?, reason: String): SponsorVideoId? {
        diagnostic?.invoke(reason)
        return null
    }

    companion object {
        const val RUN_CLASS = "com.bilibili.ship.theseus.keel.player.TheseusKeelPlayer\$runPlayable\$1"
        const val PARAMS_CLASS = "com.bilibili.app.gemini.base.player.GeminiCommonPlayableParams"
        const val CORE_CLASS = "tv.danmaku.biliplayerv2.service.IPlayerCoreService"

        /**
         * `IPlayerCoreService.seekTo(int, boolean)` 的第二个参数是 accurate（包装器协程的字段名即 `$accurate`，
         * 一路传到 `MediaPlayContextImpl.seekTo(long, boolean)` 的日志 `seek[position=… accurate=…]` 和
         * `IjkMediaPlayer.seekTo(long, boolean)`；8.90.2 反编译核对）。宿主默认的 `seekTo(int)` 等价于 false，
         * 此时 ijk 只落到目标之前的关键帧：真机上自动跳到区间终点后，实际位置退回终点前约 3–5 秒，仍在广告里。
         * 商单跳过与撤销都必须精确落点，所以这里固定为 true。
         */
        const val ACCURATE_SEEK = true
        const val WRAPPER_CLASS = "com.bilibili.ship.theseus.united.player.oldway.playercontainer.TheseusPlayerContainerProvider\$providePlayerContainer\$playerContainer\$1\$1\$1"
        const val SCOPE_CLASS = "com.bilibili.ship.theseus.united.player.oldway.playercontainer.BadNetworkTipService"
        const val CONTAINER_SCOPE_CLASS = "com.bilibili.ship.theseus.united.player.oldway.playercontainer.TheseusPlayerContainerProvider\$providePlayerContainer\$2"
        const val CONTAINER_CLASS = "tv.danmaku.biliplayerv2.PlayerContainer"
        const val CONTAINER_INTERFACE = "tv.danmaku.biliplayerv2.IPlayerContainer"
        private const val FAMILY = "com.bilibili.ship.theseus.keel.player."

        /** 9190200/9190400 keep the run owner and move its playable interface to the root package. */
        internal fun acceptsPlayableClass(owner: String, method: String, type: String): Boolean =
            type.startsWith(FAMILY) || owner == "${FAMILY}b" && method == "r" && type == "hcg2"

        fun resolve(loader: ClassLoader, continuationName: String = RUN_CLASS,
            wrapperName: String = WRAPPER_CLASS, containerScopeName: String? = CONTAINER_SCOPE_CLASS): SponsorPlayerAccess? = runCatching {
            val continuation = Lookup.classOrNull(loader, continuationName) ?: return null
            val runLabel = Lookup.fieldOrNull(continuation, "label")?.takeIf {
                !Modifier.isStatic(it.modifiers) && it.type == classOf<Int>()
            } ?: return null
            val params = Lookup.classOrNull(loader, PARAMS_CLASS) ?: return null
            val core = Lookup.classOrNull(loader, CORE_CLASS)?.takeIf { it.isInterface } ?: return null
            val wrapper = Lookup.classOrNull(loader, wrapperName) ?: return null
            val progress = Lookup.classOrNull(loader, "tv.danmaku.biliplayerv2.service.PlayerProgressObserver") ?: return null
            val seekObserver = Lookup.classOrNull(loader, "tv.danmaku.biliplayerv2.service.PlayerSeekObserver") ?: return null
            val stateObserver = Lookup.classOrNull(loader, "tv.danmaku.biliplayerv2.service.PlayerStateObserver") ?: return null
            val release = Lookup.classOrNull(loader, "tv.danmaku.biliplayerv2.service.IPlayerReleaseObserver") ?: return null
            if (listOf(progress, seekObserver, stateObserver, release).any { !it.isInterface }) return null
            if (method(progress, "onPlayerProgressChange", Void.TYPE, classOf<Int>(), classOf<Int>()) == null ||
                method(seekObserver, "onSeekStart", Void.TYPE, classOf<Long>()) == null ||
                method(seekObserver, "onSeekComplete", Void.TYPE, classOf<Long>()) == null ||
                method(stateObserver, "onPlayerStateChanged", Void.TYPE, classOf<Int>()) == null ||
                method(release, "onPlayerWillRelease", Void.TYPE) == null) return null
            val continuationOwner = Lookup.declaredFields(continuation, true) {
                !Modifier.isStatic(it.modifiers) && it.type.name.startsWith(FAMILY) && it.type != continuation
            }.singleOrNull() ?: return null
            val owner = continuationOwner.type
            val run = Lookup.declaredMethods(owner, true) {
                !Modifier.isStatic(it.modifiers) && it.returnType == classOf<Any>() && it.parameterCount == 2 &&
                    it.parameterTypes[0].isInterface &&
                    acceptsPlayableClass(it.declaringClass.name, it.name, it.parameterTypes[0].name) &&
                    it.parameterTypes[1].name in setOf("kotlin.coroutines.Continuation", "kotlin.coroutines.jvm.internal.ContinuationImpl")
            }.singleOrNull() ?: return null
            val active = Lookup.declaredMethods(owner, true) {
                !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == run.parameterTypes[0]
            }.singleOrNull() ?: return null
            val parameters = Lookup.methods(run.parameterTypes[0], true, true) {
                !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == params
            }.distinctBy { it.name to it.parameterTypes.toList() }.singleOrNull() ?: return null
            val binding = resolveWrapper(wrapper, core, owner) ?: return null
            // 与 owner 显式成对的宿主 Context；不按当前 Activity 猜测播放器归属。
            val context = Lookup.classOrNull(loader, "android.content.Context") ?: return null
            val scope = Lookup.classOrNull(loader, SCOPE_CLASS)?.let { cls ->
                Lookup.declaredConstructors(cls, true) {
                    !it.isSynthetic && it.parameterCount in 3..12 &&
                        it.parameterTypes.take(3) == listOf(core, context, owner)
                }.singleOrNull()
            }
            val container = Lookup.classOrNull(loader, CONTAINER_CLASS)
            // PlayerContainer 为抽象类，getter 声明在 IPlayerContainer；只查父类会漏掉。
            val containerInterface = Lookup.classOrNull(loader, CONTAINER_INTERFACE)?.takeIf {
                it.isInterface && container != null && it.isAssignableFrom(container)
            }
            val containerCore = containerInterface?.let { method(it, "getPlayerCoreService", core) }
            val containerConstructor = containerScopeName?.let { Lookup.classOrNull(loader, it) }?.let { cls ->
                Lookup.declaredConstructors(cls, true) {
                    !it.isSynthetic && it.parameterCount in 3..16 &&
                        it.parameterTypes.count { type -> type == context } == 1 &&
                        it.parameterTypes.count { type -> type == container } == 1
                }.singleOrNull()
            }
            val containerScope = if (containerConstructor != null && containerCore != null) ContainerScope(
                containerConstructor, containerConstructor.parameterTypes.indexOf(context),
                containerConstructor.parameterTypes.indexOf(container), containerCore) else null
            if (scope == null && containerScope == null) return null
            val business = Lookup.inheritedMethodOrNull(params, "getBizType")?.takeIf {
                !Modifier.isStatic(it.modifiers) && it.returnType.isEnum
            } ?: return null
            val ugc = Lookup.fieldOrNull(business.returnType, "UGC")?.takeIf {
                Modifier.isStatic(it.modifiers) && it.type == business.returnType
            }?.get(null) ?: return null
            SponsorPlayerAccess(run, binding.constructor, binding.ownerIndex, binding.coreIndex,
                scope, containerScope, active, continuation, continuationOwner, runLabel, parameters,
                method(params, "getBvId", classOf<String>()) ?: return null,
                method(params, "getCid", classOf<Long>()) ?: return null,
                method(params, "getAvid", classOf<Long>()) ?: return null, business, ugc,
                method(core, "getCurrentPosition", classOf<Int>()) ?: return null,
                method(core, "getDuration", classOf<Int>()) ?: return null,
                method(core, "getState", classOf<Int>()) ?: return null,
                method(core, "seekTo", Void.TYPE, classOf<Int>(), classOf<Boolean>()) ?: return null,
                method(core, "registerPlayerProgressObserver", Void.TYPE, progress) ?: return null,
                method(core, "unregisterPlayerProgressObserver", Void.TYPE, progress) ?: return null,
                method(core, "registerSeekObserver", Void.TYPE, seekObserver) ?: return null,
                method(core, "unregisterSeekObserver", Void.TYPE, seekObserver) ?: return null,
                method(core, "registerState", Void.TYPE, stateObserver, IntArray::class.java) ?: return null,
                method(core, "unregisterState", Void.TYPE, stateObserver) ?: return null,
                method(core, "addPlayerReleaseObserver", Void.TYPE, release) ?: return null,
                method(core, "removePlayerReleaseObserver", Void.TYPE, release) ?: return null,
                progress, seekObserver, stateObserver, release)
        }.getOrNull()

        /** 9.12.0 为 owner/scope/core，其余样本为 core/owner/scope；仍只接受精确的三种类型。 */
        fun resolveWrapper(wrapper: Class<*>, core: Class<*>, owner: Class<*>): WrapperBinding? {
            if (core == owner || !core.isAssignableFrom(wrapper)) return null
            val constructor = Lookup.declaredConstructors(wrapper, true) {
                val types = it.parameterTypes
                types.size == 3 && types.count { type -> type == core } == 1 &&
                    types.count { type -> type == owner } == 1 &&
                    types.count { type -> type.name == "kotlinx.coroutines.CoroutineScope" } == 1
            }.singleOrNull() ?: return null
            return WrapperBinding(constructor, constructor.parameterTypes.indexOf(owner),
                constructor.parameterTypes.indexOf(core))
        }

        private fun method(owner: Class<*>, name: String, result: Class<*>, vararg parameters: Class<*>): Method? =
            Lookup.inheritedMethodOrNull(owner, name, *parameters)?.takeIf {
                !Modifier.isStatic(it.modifiers) && it.returnType == result
            }
    }
}
