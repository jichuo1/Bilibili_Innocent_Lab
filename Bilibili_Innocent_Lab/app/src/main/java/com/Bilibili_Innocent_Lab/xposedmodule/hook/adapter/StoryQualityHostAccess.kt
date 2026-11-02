package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerQualitySessions.Admission
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerQualitySessions.ItemKey
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** 安装期解析 Story 运行所需的完整对象关系；回调只使用已解析的成员。 */
internal class StoryQualityHostAccess private constructor(
    val roles: StoryQualityLocator.HostAccess,
    val handler: Class<*>, val provider: Class<*>, val params: Class<*>,
    val select: Method, val request: Method, val requestParamsIndex: Int, val reload: Method?,
    val resolveConstructor: Constructor<*>, val resolveInvoke: Method, val resolveCreate: Method,
    val resolveTask: Field, val resolveProvider: Field, val resolveParams: Field?,
    val shared: SharedAccess?,
    private val serviceHandler: Field, private val handlerProvider: Field,
    private val current: Method, private val at: Method, private val pointer: Method,
    private val display: Method, private val avid: Method, private val cid: Method, private val from: Method,
    private val bizType: Method, private val ugc: Any,
    private val account: Field, private val login: Method,
    private val teenagersGet: Method, private val teenagersEnabled: Method,
    private val lessonsEnabled: Method, private val lessons: Any,
    private val accountInfo: Field, private val effectiveVip: Method,
    private val core: Field, private val media: Method,
    val consumerMethod: Method?, private val providerConsumer: Field?,
    val listenerSuccess: Method?, private val state: Method?, private val position: Method?,
    private val language: Method?, private val production: Method?, private val unit: Any
) {
    /** 共享接入独立于基础链路；新版需核对构建结果确实归当前 owner 持有。 */
    class SharedAccess(
        val ready: Method, private val pointerIndex: Method, private val resolverParams: Method,
        private val resolver: Class<*>, private val aid: Method, private val cid: Method,
        private val ownerContainer: Field? = null, private val builderParams: Field? = null,
        private val sharingBundle: Method? = null
    ) {
        val usesBuilder: Boolean get() = builderParams != null
        fun hasSharingBundle(builder: Any): Boolean = builderParams?.get(builder)?.let { sharingBundle?.invoke(it) } != null
        fun ownsContainer(owner: Any, container: Any): Boolean = ownerContainer?.get(owner) === container
        fun index(pointer: Any): Int? = (pointerIndex.invoke(pointer) as? Int)?.takeIf { it >= 0 }
        fun confirms(parameters: Any?, item: ItemKey): Boolean? {
            val resolved = resolverParams.invoke(parameters) ?: return null
            return resolved.javaClass == resolver && aid.invoke(resolved) == item.aid && cid.invoke(resolved) == item.cid
        }
    }
    data class ReplaySnapshot(val position: Long, val language: String?, val productionType: Int)
    val reparseReady: Boolean get() = reload != null && consumerMethod != null && providerConsumer != null &&
        listenerSuccess != null && state != null && position != null && language != null && production != null
    fun service(owner: Any): Any? = roles.serviceField.get(owner)
    fun handler(owner: Any): Any? = service(owner)?.let(serviceHandler::get)
    fun provider(owner: Any): Any? = handler(owner)?.let(handlerProvider::get)
    fun handlerOfService(service: Any): Any? = serviceHandler.get(service)
    fun current(owner: Any): Any? = current.invoke(owner)
    fun canSelect(handler: Any): Boolean = pointer.invoke(handler) != null
    fun currentIndex(handler: Any): Int? = pointer.invoke(handler)?.let { shared?.index(it) }
    fun mediaResource(owner: Any): Any? = service(owner)?.let(core::get)?.let { media.invoke(it) }
    fun consumer(owner: Any): Any? = provider(owner)?.let { providerConsumer?.get(it) }
    fun completed(value: Any?): Boolean = value === unit
    fun isPlaying(owner: Any): Boolean = runCatching {
        val service = service(owner) ?: return false
        state?.invoke(core.get(service)) == 4
    }.getOrDefault(false)
    fun currentPosition(owner: Any): Long? = runCatching {
        val service = service(owner) ?: return null
        (position?.invoke(core.get(service)) as? Number)?.toLong()?.takeIf { it >= 0L }
    }.getOrNull()
    fun replaySnapshot(owner: Any): ReplaySnapshot? = runCatching {
        if (!reparseReady || !isPlaying(owner)) return null
        val resource = mediaResource(owner) ?: return null
        ReplaySnapshot(currentPosition(owner) ?: return null, language?.invoke(resource) as? String,
            production?.invoke(resource) as? Int ?: return null)
    }.getOrNull()
    fun reparse(owner: Any, qn: Int, snapshot: ReplaySnapshot) {
        val provider = provider(owner) ?: return
        reload?.invoke(provider, qn, snapshot.position, snapshot.language, true, snapshot.productionType, null)
    }
    fun vip(owner: Any): Boolean? = runCatching {
        val service = service(owner) ?: return null
        val info = accountInfo.get(service) ?: return null
        effectiveVip.invoke(info) as? Boolean
    }.getOrNull()
    fun at(provider: Any, index: Int): Any? =
        if (at.parameterTypes[0] == Int::class.javaPrimitiveType) at.invoke(provider, index, null)
        else at.invoke(provider, null, index)

    fun item(parameters: Any?): ItemKey? = runCatching {
        if (!params.isInstance(parameters)) return null
        val details = display.invoke(parameters) ?: return null
        ItemKey(avid.invoke(details) as Long, cid.invoke(details) as Long).takeIf { it.aid > 0 && it.cid > 0 }
    }.getOrNull()

    /** 未知业务、登录或限制状态不能产生模块目标；不改变宿主的任何准入状态。 */
    fun admission(owner: Any?, parameters: Any?, confirmedShared: Boolean = false): Admission = runCatching {
        val item = item(parameters) ?: return Admission.UNKNOWN
        if (owner == null) return Admission.UNKNOWN
        if (bizType.invoke(parameters) !== ugc) return Admission.DENY
        // 普通详情页共享参数的 from 是来源编号（8.90.2 实测为 "6"），不是 Story 业务类型。
        // 仅已确认的共享接入允许改用同条目的 Gemini 解析参数作正向证据；其它未知来源继续拒绝。
        val origin = from.invoke(parameters)
        if (origin in setOf("live", "music", "bangumi", "pugv")) return Admission.DENY
        if (origin != "vupload") {
            if (!confirmedShared) return Admission.DENY
            val shared = shared ?: return Admission.DENY
            when (shared.confirms(parameters, item)) {
                true -> Unit
                false -> return Admission.DENY
                null -> return Admission.UNKNOWN
            }
        }
        val service = service(owner) ?: return Admission.UNKNOWN
        val currentAccount = account.get(service) ?: return Admission.UNKNOWN
        if (login.invoke(currentAccount) != true) return Admission.DENY
        val teen = teenagersGet.invoke(null) ?: return Admission.UNKNOWN
        if (teenagersEnabled.invoke(teen, "player") == true || lessonsEnabled.invoke(null, lessons, "player") == true)
            Admission.DENY else Admission.ALLOW
    }.getOrDefault(Admission.UNKNOWN)

    companion object {
        const val HANDLER = "com.bilibili.video.story.player.StoryVideoPlayHandler"
        const val PROVIDER = "com.bilibili.video.story.player.datasource.PlayableProvider"
        const val PARAMS = "tv.danmaku.biliplayerv2.service.Video\$PlayableParams"
        const val RESOLVE = "com.bilibili.video.story.player.datasource.PlayableProvider\$resolve\$1"
        const val TASK = "tv.danmaku.biliplayerv2.service.resolve.AbsMediaResourceResolveTask"
        private val INT = Int::class.javaPrimitiveType!!
        private val BOOL = Boolean::class.javaPrimitiveType!!

        fun resolve(loader: ClassLoader, roles: StoryQualityLocator.HostAccess): StoryQualityHostAccess? = runCatching {
            if (!roles.points.semanticComplete) return null
            fun cls(name: String) = Lookup.classOrNull(loader, name)
            val handler = cls(HANDLER) ?: return null
            val provider = cls(PROVIDER) ?: return null
            val params = cls(PARAMS) ?: return null
            val displayClass = cls("tv.danmaku.biliplayerv2.service.Video\$DisplayParams") ?: return null
            val resolver = cls(RESOLVE) ?: return null
            val task = cls(TASK) ?: return null
            val serviceHandler = typedField(roles.service, handler) ?: return null
            val handlerProvider = typedField(handler, provider) ?: return null
            val current = method(roles.owner, "getCurrentPlayableParam", params) ?: return null
            val pointer = method(handler, "getCurrentVideoItem", cls("tv.danmaku.biliplayerv2.service.CurrentVideoPointer") ?: return null) ?: return null
            val shared = resolveShared(loader, roles.owner, params, pointer.returnType)
            val select = Lookup.declaredMethods(handler, true) {
                concrete(it) && it.returnType == INT && it.parameterTypes.contentEquals(arrayOf(INT))
            }.singleOrNull() ?: return null
            val request = Lookup.declaredMethods(provider, true) {
                val p = it.parameterTypes
                concrete(it) && it.returnType == Void.TYPE && p.size in 6..7 &&
                    p[0] == Integer::class.java && p[1] == BOOL && p[2] == String::class.java && p[3] == INT &&
                    p.count { type -> type == params } == 1 && p.last().name.startsWith("com.bilibili.video.story.player.datasource.") &&
                    (p.size == 6 || p[4] == BOOL) && p[p.size - 2] == params
            }.singleOrNull() ?: return null
            val at = Lookup.declaredMethods(provider, true) {
                concrete(it) && it.returnType == params &&
                    (it.parameterTypes.contentEquals(arrayOf(INT, String::class.java)) ||
                        it.parameterTypes.contentEquals(arrayOf(String::class.java, INT)))
            }.singleOrNull() ?: return null
            val constructor = Lookup.declaredConstructors(resolver, true) {
                it.parameterTypes.count { type -> type == task } == 1 &&
                    it.parameterTypes.count { type -> type == provider } == 1 &&
                    it.parameterTypes.lastOrNull()?.name == StoryQualityLocator.CONTINUATION
            }.singleOrNull() ?: return null
            val resolveTask = typedField(resolver, task) ?: return null
            val resolveProvider = typedField(resolver, provider) ?: return null
            val resolveParams = Lookup.declaredFields(resolver, true) { !Modifier.isStatic(it.modifiers) && it.type == params }
            if (resolveParams.size > 1) return null
            val invoke = method(resolver, "invokeSuspend", Any::class.java, Any::class.java) ?: return null
            val continuation = cls(StoryQualityLocator.CONTINUATION) ?: return null
            val create = method(resolver, "create", continuation, Any::class.java, continuation) ?: return null
            val accounts = cls("com.bilibili.lib.accounts.BiliAccounts") ?: return null
            val account = typedField(roles.service, accounts) ?: return null
            val login = method(accounts, "isLogin", BOOL) ?: return null
            val accountInfoClass = cls("com.bilibili.lib.accountinfo.BiliAccountInfo") ?: return null
            val accountInfo = typedField(roles.service, accountInfoClass) ?: return null
            val effectiveVip = method(accountInfoClass, "isEffectiveVip", BOOL) ?: return null
            val coreClass = cls("tv.danmaku.biliplayerv2.service.IPlayerCoreService") ?: return null
            val core = typedField(roles.service, coreClass) ?: return null
            val resource = cls(StoryQualityLocator.MEDIA_RESOURCE) ?: return null
            val media = Lookup.methodOrNull(coreClass, "getMediaResource")?.takeIf {
                !Modifier.isStatic(it.modifiers) && it.returnType == resource && it.parameterCount == 0
            } ?: return null
            val function = cls("kotlin.jvm.functions.Function1") ?: return null
            val reload = Lookup.declaredMethods(provider, true) {
                concrete(it) && it.returnType == Void.TYPE && it.parameterTypes.contentEquals(
                    arrayOf(Int::class.javaObjectType, Long::class.javaObjectType, String::class.java, BOOL, INT, function))
            }.singleOrNull()
            fun coreGetter(name: String, result: Class<*>) = Lookup.methodOrNull(coreClass, name)?.takeIf {
                !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == result
            }
            val consumerClass = cls("$HANDLER\$mPlayableConsumer\$1")
            val mediaItem = cls("tv.danmaku.videoplayer.coreV2.MediaItem")
            val consumerMethod = consumerClass?.let { owner -> Lookup.declaredMethods(owner, true) {
                val p = it.parameterTypes
                val offset = if (p.size == 5 && p[0] == INT) 1 else 0
                concrete(it) && it.returnType == mediaItem && p.size == 4 + offset && p[offset] == params &&
                    p[offset + 1] == resource && p[offset + 2] == mediaItem && p.last() == Long::class.javaObjectType
            }.singleOrNull() }
            val providerConsumer = consumerClass?.let { consumer -> Lookup.declaredFields(provider, true) {
                !Modifier.isStatic(it.modifiers) && it.type != Any::class.java && it.type.isAssignableFrom(consumer)
            }.singleOrNull() }
            val listener = request.parameterTypes.last()
            val listenerSuccess = if (!listener.isInterface) null else listener.methods.filter {
                !Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE &&
                    it.parameterTypes.contentEquals(arrayOf(resource))
            }.singleOrNull()
            val unitClass = cls("kotlin.Unit") ?: return null
            val unit = Lookup.fieldOrNull(unitClass, "INSTANCE")?.takeIf {
                Modifier.isStatic(it.modifiers) && it.type == unitClass
            }?.get(null) ?: return null
            val teenagers = cls("com.bilibili.teenagersmode.TeenagersMode") ?: return null
            val restricted = cls("com.bilibili.app.comm.restrict.RestrictedMode") ?: return null
            val restrictedType = cls("com.bilibili.app.comm.restrict.RestrictedType") ?: return null
            val lessons = Lookup.fieldOrNull(restrictedType, "LESSONS")?.takeIf {
                Modifier.isStatic(it.modifiers) && it.type == restrictedType
            }?.get(null) ?: return null
            val type = cls("tv.danmaku.videoplayer.coreV2.VideoBizType") ?: return null
            val ugc = Lookup.fieldOrNull(type, "UGC")?.takeIf { Modifier.isStatic(it.modifiers) && it.type == type }
                ?.get(null) ?: return null
            StoryQualityHostAccess(roles, handler, provider, params, select, request,
                request.parameterTypes.indexOf(params), reload, constructor, invoke, create, resolveTask, resolveProvider,
                resolveParams.singleOrNull(), shared, serviceHandler, handlerProvider, current, at, pointer,
                // 8.90.2 在 PlayableParams 抽象基类声明，由当前业务参数实例实现；仅缓存调用，不能 Hook 抽象方法。
                method(params, "getDisplayParams", displayClass, abstractDispatch = true) ?: return null,
                method(displayClass, "getAvid", Long::class.javaPrimitiveType!!) ?: return null,
                method(displayClass, "getCid", Long::class.javaPrimitiveType!!) ?: return null,
                method(params, "getFrom", String::class.java) ?: return null,
                method(params, "getBizType", type) ?: return null, ugc, account, login,
                method(teenagers, "getInstance", teenagers, static = true) ?: return null,
                method(teenagers, "isEnable", BOOL, String::class.java) ?: return null,
                method(restricted, "isEnable", BOOL, restrictedType, String::class.java, static = true) ?: return null,
                lessons, accountInfo, effectiveVip, core, media, consumerMethod, providerConsumer, listenerSuccess,
                coreGetter("getState", INT), coreGetter("getRealCurrentPosition", INT),
                method(resource, "getCurrentLanguage", String::class.java), method(resource, "getCurProductionType", INT), unit)
        }.getOrNull()

        private fun resolveShared(loader: ClassLoader, owner: Class<*>, params: Class<*>, pointer: Class<*>): SharedAccess? = runCatching {
            fun cls(name: String) = Lookup.classOrNull(loader, name)
            val container = cls("tv.danmaku.biliplayerv2.IPlayerContainer") ?: return null
            val bundle = cls("tv.danmaku.biliplayerv2.PlayerSharingBundle") ?: return null
            // 8.84–8.96 独立接入；8.97 起内联在 onCreate。不能使用出站分享或返回时取回播放器的方法。
            val legacy = Lookup.declaredMethods(owner, true) {
                concrete(it) && it.returnType == Void.TYPE && it.parameterTypes.contentEquals(arrayOf(container, bundle))
            }
            if (legacy.size > 1) return null
            val builder = if (legacy.isEmpty()) cls("tv.danmaku.biliplayerv2.IPlayerContainer\$Builder") ?: return null else null
            val playerParams = if (builder != null) cls("tv.danmaku.biliplayerv2.PlayerParamsV2") ?: return null else null
            val ready = legacy.singleOrNull() ?: method(builder ?: return null, "build", container) ?: return null
            val resolverType = cls("com.bilibili.lib.media.resolver2.IResolveParams") ?: return null
            val resolver = cls("com.bilibili.app.gemini.base.player.GeminiCommonResolverParams") ?: return null
            SharedAccess(ready, method(pointer, "getIndex", INT) ?: return null,
                method(params, "getResolveParams", resolverType, abstractDispatch = true) ?: return null,
                resolver, method(resolver, "getAvid", Long::class.javaPrimitiveType!!) ?: return null,
                method(resolver, "getCid", Long::class.javaPrimitiveType!!) ?: return null,
                if (builder != null) typedField(owner, container) ?: return null else null,
                if (builder != null) typedField(builder, playerParams ?: return null) ?: return null else null,
                if (playerParams != null) method(playerParams, "getSharingBundle", bundle) ?: return null else null)
        }.getOrNull()

        private fun concrete(method: Method) = !Modifier.isStatic(method.modifiers) && !Modifier.isAbstract(method.modifiers) && !method.isBridge
        private fun typedField(owner: Class<*>, type: Class<*>): Field? =
            Lookup.declaredFields(owner, true) { !Modifier.isStatic(it.modifiers) && it.type == type }.singleOrNull()
        private fun method(owner: Class<*>, name: String, returns: Class<*>, vararg args: Class<*>, static: Boolean = false,
                           abstractDispatch: Boolean = false): Method? =
            Lookup.methods(owner, includeSuperclasses = true, makeAccessible = true) {
                it.name == name && it.returnType == returns && it.parameterTypes.contentEquals(args) &&
                    Modifier.isStatic(it.modifiers) == static &&
                    (!Modifier.isAbstract(it.modifiers) || (!static && abstractDispatch)) && !it.isBridge
            }.distinctBy { it.name to it.parameterTypes.toList() }.singleOrNull()
    }
}
