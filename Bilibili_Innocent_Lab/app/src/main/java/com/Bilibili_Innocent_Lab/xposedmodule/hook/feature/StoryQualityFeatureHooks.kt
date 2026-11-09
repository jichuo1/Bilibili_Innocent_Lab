package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.StoryQualityHostAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.StoryQualityResourceAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerQualitySessions.Admission
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerQualitySessions.Operation
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernHookParam
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/** 生命周期、用户意图及同步作用域先就绪，再开放 Story 默认目标；不创建额外异步任务。 */
internal class StoryQualityFeatureHooks(private val runtime: StoryQualityRuntime) {
    private class Ticket(val task: WeakReference<Any>, val owner: WeakReference<Any>,
        val params: WeakReference<Any>, val item: PlayerQualitySessions.ItemKey,
        val token: PlayerQualitySessions.Token)
    private class RequestFrame(val owner: Any?, val params: Any?)
    private class CoroutineTicket(val coroutine: WeakReference<Any>, val ticket: Ticket)
    /** 仅存活于本次 onCreate 的同步帧，退出时连同 owner 一起清除。 */
    private class Creation(val owner: Any, val previous: Creation?) {
        var container: WeakReference<Any>? = null
        var sharedBuilds = 0
    }

    private val tickets = ArrayList<Ticket>()
    private val coroutines = ArrayList<CoroutineTicket>()
    private val registered = hashSetOf<String>()
    private val ready = AtomicBoolean(false)
    private val requesting = ThreadLocal<RequestFrame>()
    private val resolving = ThreadLocal<Ticket>()
    private val cloning = ThreadLocal<Ticket>()
    private val pendingReparse = ThreadLocal<StoryQualityReparseGate.Lease>()
    private val creating = ThreadLocal<Creation>()
    private var reparseGate: StoryQualityReparseGate? = null
    private var hostAccess: StoryQualityHostAccess? = null
    val complete: Boolean get() = ready.get()
    val hookCount: Int get() = registered.size

    fun globalTarget(qn: Int): Int? = if (!runtime.inStory()) qn else target()

    private fun admission(access: StoryQualityHostAccess, owner: Any?, parameters: Any?): Admission =
        access.admission(owner, parameters, owner != null && runtime.isShared(owner, parameters))

    private fun target(): Int? = runCatching {
        if (!ready.get()) return null
        val access = hostAccess ?: return null
        val qn = runtime.target() ?: return null
        val owner = runtime.owner() ?: return null
        val parameters = runtime.params() ?: return null
        val current = access.current(owner)
        val item = access.item(parameters) ?: return null
        val currentItem = access.item(current)
        val fresh = runtime.sessions.target(owner, currentItem, admission(access, owner, current))
        if (currentItem != item) return null
        fresh?.takeIf { it == qn }
    }.getOrNull()

    fun install(environment: HookEnvironment, access: StoryQualityHostAccess): Boolean {
        hostAccess = access
        val roles = access.roles
        val resources = environment.classLoader?.let(StoryQualityResourceAccess::resolve) ?: return false
        if (!access.reparseReady) return false
        fun observe() = runCatching { environment.reportRuntimeEvidence(PlayerQualityFeatureInstaller.ID, FeatureRuntimeStage.OBSERVED) }
        fun applied() = runCatching { environment.reportRuntimeEvidence(PlayerQualityFeatureInstaller.ID, FeatureRuntimeStage.APPLIED) }
        fun error() = runCatching { environment.reportRuntimeEvidence(PlayerQualityFeatureInstaller.ID, FeatureRuntimeStage.ERROR) }
        fun valid(lease: StoryQualityReparseGate.Lease, playing: Boolean): Boolean = runCatching {
            val owner = lease.owner() ?: return false
            val parameters = lease.params() ?: return false
            val current = access.current(owner)
            runtime.onOwnerThread(owner) &&
                runtime.sessions.valid(lease.token, owner, access.item(current), admission(access, owner, current)) &&
                access.item(parameters) == lease.item && access.item(current) == lease.item &&
                (!playing || access.isPlaying(owner))
        }.getOrDefault(false)
        fun failed(lease: StoryQualityReparseGate.Lease) {
            if (valid(lease, playing = false)) lease.owner()?.let(runtime::invalidate)
        }
        val gate = reparseGate ?: StoryQualityReparseGate(access.request.parameterTypes.last(),
            access.listenerSuccess ?: return false, { valid(it, playing = true) }, ::failed).also { reparseGate = it }
        if (!gate.available) return false
        fun enter(param: ModernHookParam, owner: Any?, parameters: Any?, allow: Boolean = true) {
            val current = owner?.let(access::current)
            val item = access.item(parameters)
            val valid = allow && owner != null && item != null && access.item(current) == item
            val admission = if (valid) admission(access, owner, current) else Admission.UNKNOWN
            param.setObjectExtra(SCOPE, runtime.enter(if (valid) owner else null, item, admission, parameters))
        }
        fun leave(param: ModernHookParam) {
            (param.getObjectExtra(CREATION) as? Creation)?.let { frame ->
                if (creating.get() === frame) {
                    frame.previous?.let(creating::set) ?: creating.remove()
                }
                param.setObjectExtra(CREATION, null)
            }
            if (param.getObjectExtra(REQUEST_FRAME) == true) {
                (param.getObjectExtra(PREVIOUS_REQUEST) as? RequestFrame)?.let(requesting::set) ?: requesting.remove()
                param.setObjectExtra(REQUEST_FRAME, false)
            }
            if (param.getObjectExtra(RESOLVE_FRAME) == true) {
                (param.getObjectExtra(PREVIOUS_RESOLVE) as? Ticket)?.let(resolving::set) ?: resolving.remove()
                param.setObjectExtra(RESOLVE_FRAME, false)
            }
            if (param.getObjectExtra(CLONE_FRAME) == true) {
                (param.getObjectExtra(PREVIOUS_CLONE) as? Ticket)?.let(cloning::set) ?: cloning.remove()
                param.setObjectExtra(CLONE_FRAME, false)
            }
            (param.getObjectExtra(SCOPE) as? StoryQualityRuntime.Scope)?.let { runtime.leave(it) }
        }
        fun ensureStoryScope(param: ModernHookParam) {
            if (!runtime.inStory()) enter(param, null, null)
        }
        fun com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator.safeBefore(block: ModernHookParam.() -> Unit) {
            before {
                try { block() } catch (_: Throwable) {
                    runtime.invalidateCurrent()
                    leave(this)
                    setObjectExtra(FAILED_BEFORE, true)
                    setObjectExtra(SCOPE, runtime.enter(null, null, Admission.UNKNOWN))
                    error()
                }
            }
        }
        fun com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator.safeAfter(block: ModernHookParam.() -> Unit) {
            after {
                try {
                    if (hasThrowable) runtime.invalidateCurrent()
                    if (getObjectExtra(FAILED_BEFORE) != true) block()
                } catch (_: Throwable) {
                    runtime.invalidateCurrent()
                    error()
                } finally { leave(this) }
            }
        }
        fun register(id: String, method: Method, block: com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator.() -> Unit) {
            if (id in registered) return
            environment.registrar.exact(id, method.declaringClass, method.name, *method.parameterTypes, block = block)
            registered += id
        }
        fun confirmShared(owner: Any) {
            if (!ready.get() || runtime.ownerFor(owner) !== owner || !runtime.onOwnerThread(owner)) return
            val handler = access.handler(owner) ?: return
            val provider = access.provider(owner) ?: return
            runtime.bind(owner, handler, provider, access.service(owner))
            val parameters = access.current(owner) ?: return
            val item = access.item(parameters) ?: return
            val index = access.currentIndex(handler) ?: return
            if (access.item(access.at(provider, index)) != item) return
            runtime.confirmShared(owner, parameters, item, index, access.admission(owner, parameters, confirmedShared = true))
        }
        try {
            register("player.story.create", roles.onCreate) {
                safeBefore {
                    instance?.let { runtime.close(it); runtime.bind(it, null, null) }
                    ensureStoryScope(this)
                    if (access.shared?.usesBuilder == true && "player.story.ready" in registered) instance?.let { owner ->
                        Creation(owner, creating.get()).also { creating.set(it); setObjectExtra(CREATION, it) }
                    }
                }
                safeAfter {
                    try {
                        if (!hasThrowable) instance?.let { owner ->
                            runtime.bind(owner, access.handler(owner), access.provider(owner), access.service(owner))
                            val frame = getObjectExtra(CREATION) as? Creation
                            val container = frame?.container?.get()
                            if (frame != null && frame.owner === owner && frame.sharedBuilds == 1 && container != null &&
                                access.shared?.ownsContainer(owner, container) == true) confirmShared(owner)
                        }
                        else instance?.let(runtime::close)
                    } finally { leave(this) }
                }
            }
            listOf("release" to roles.release, "destroy" to roles.onDestroy).forEach { (name, method) ->
                register("player.story.$name", method) {
                    safeBefore {
                        instance?.let(runtime::close)
                        ensureStoryScope(this)
                    }
                    safeAfter { leave(this) }
                }
            }
            register("player.story.manual", roles.manual) {
                safeBefore {
                    val owner = runtime.ownerFor(instance)
                    if (owner != null) {
                        val value = argOrNull(0)
                        val choice = when {
                            roles.preference.isAuto(value) -> PlayerQualitySessions.ManualChoice.AUTO
                            roles.preference.qualityOf(value) != null -> PlayerQualitySessions.ManualChoice.FIXED
                            else -> null
                        }
                        if (choice != null) runtime.manual(owner, choice) else runtime.invalidate(owner)
                    }
                    // 此时已同步撤销；后续宿主参数读取即使失败，也不能复活默认目标。
                    enter(this, null, null, allow = false)
                }
                safeAfter { leave(this) }
            }
            register("player.story.select", access.select) {
                safeBefore {
                    val owner = runtime.ownerFor(instance)
                    val provider = owner?.let(access::provider)
                    val index = argOrNull(0) as? Int
                    val parameters = if (provider != null && index != null) access.at(provider, index) else null
                    if (ready.get() && owner != null && index != null) {
                        val handler = instance
                        val item = access.item(parameters)
                        if (handler != null && access.canSelect(handler) && index >= 0 && item != null)
                            runtime.select(owner, item, index)
                        else runtime.start(owner, null)
                    }
                    // Cursor selection may launch UNDISPATCHED work before returning. Its request opens its own frame.
                    ensureStoryScope(this)
                }
                safeAfter {
                    try {
                        if (hasThrowable || (result as? Int)?.let { it < 0 || it == 2 } == true)
                            runtime.ownerFor(instance)?.let { runtime.start(it, null) }
                    } finally { leave(this) }
                }
            }
            register("player.story.request", access.request) {
                safeBefore {
                    val owner = runtime.ownerFor(instance)
                    val parameters = argOrNull(access.requestParamsIndex)
                    enter(this, owner, parameters, ready.get())
                    setObjectExtra(PREVIOUS_REQUEST, requesting.get())
                    setObjectExtra(REQUEST_FRAME, true)
                    requesting.set(RequestFrame(owner, parameters))
                    val replay = pendingReparse.get()
                    if (replay != null) {
                        val listenerIndex = access.request.parameterCount - 1
                        val listener = argOrNull(listenerIndex)
                        val wrapped = if (listener != null && replay.owner() === owner && replay.params() === parameters &&
                            valid(replay, playing = true)) gate.wrap(listener, replay) else null
                        if (wrapped == null) {
                            failed(replay)
                            result = null
                            return@safeBefore
                        }
                        args[listenerIndex] = wrapped
                    }
                    val qn = target() ?: return@safeBefore
                    val item = access.item(parameters) ?: return@safeBefore
                    val admission = admission(access, owner, parameters)
                    val token = owner?.let { runtime.sessions.reserve(it, item, admission, Operation.REQUEST_TARGET) }
                        ?: return@safeBefore
                    if (runtime.sessions.valid(token, owner, item, admission)) {
                        args[0] = qn
                        setObjectExtra(REQUEST, token)
                        observe()
                    }
                }
                safeAfter {
                    try {
                        val token = getObjectExtra(REQUEST) as? PlayerQualitySessions.Token
                        val owner = runtime.ownerFor(instance)
                        val parameters = owner?.let(access::current)
                        if (token != null && owner != null && runtime.sessions.finish(token, owner, access.item(parameters),
                                admission(access, owner, parameters), if (hasThrowable) PlayerQualitySessions.Outcome.FAILED else PlayerQualitySessions.Outcome.COMPLETED)) {
                            if (hasThrowable) error()
                        }
                    } finally { leave(this) }
                }
            }
            val constructorId = "player.story.resolve_capture"
            if (constructorId !in registered) {
                environment.registrar.constructor(constructorId, access.resolveConstructor) {
                    safeAfter {
                        if (hasThrowable || !ready.get()) return@safeAfter
                        val coroutine = instance ?: return@safeAfter
                        val task = access.resolveTask.get(coroutine) ?: return@safeAfter
                        val inherited = cloning.get()
                        if (inherited != null) {
                            if (inherited.task.get() === task) remember(coroutine, inherited)
                            return@safeAfter
                        }
                        val provider = access.resolveProvider.get(coroutine)
                        val owner = runtime.ownerFor(provider) ?: return@safeAfter
                        val existing = ticket(task)
                        val current = access.current(owner)
                        if (existing != null && existing.owner.get() === owner &&
                            runtime.sessions.valid(existing.token, owner, access.item(current), admission(access, owner, current)))
                            {
                                remember(coroutine, existing)
                                return@safeAfter
                            }
                        val frame = requesting.get()?.takeIf { it.owner === owner } ?: return@safeAfter
                        val parameters = frame.params ?: return@safeAfter
                        if (access.resolveParams != null && access.resolveParams.get(coroutine) !== parameters) return@safeAfter
                        if (runtime.owner() !== owner || target() == null) return@safeAfter
                        val item = access.item(parameters) ?: return@safeAfter
                        val token = runtime.sessions.reserve(owner, item, admission(access, owner, parameters), Operation.RESOURCE_SELECT)
                            ?: pendingReparse.get()?.takeIf { it.owner() === owner && it.params() === parameters && valid(it, false) }?.token
                            ?: return@safeAfter
                        val captured = Ticket(WeakReference(task), WeakReference(owner), WeakReference(parameters), item, token)
                        remember(captured)
                        remember(coroutine, captured)
                    }
                }
                registered += constructorId
            }
            register("player.story.resolve_clone", access.resolveCreate) {
                safeBefore {
                    setObjectExtra(PREVIOUS_CLONE, cloning.get())
                    setObjectExtra(CLONE_FRAME, true)
                    val inherited = instance?.let(::coroutineTicket)
                    if (inherited != null) cloning.set(inherited) else cloning.remove()
                }
                safeAfter { leave(this) }
            }
            register("player.story.resolve", access.resolveInvoke) {
                safeBefore {
                    val coroutine = instance
                    val task = coroutine?.let(access.resolveTask::get)
                    val ticket = coroutine?.let(::coroutineTicket)?.takeIf { it.task.get() === task }
                    val owner = ticket?.owner?.get()
                    val parameters = ticket?.params?.get()
                    val provider = coroutine?.let(access.resolveProvider::get)
                    val captured = coroutine?.let { access.resolveParams?.get(it) }
                    val current = owner?.let(access::current)
                    val ticketValid = ticket != null && owner != null &&
                        runtime.sessions.valid(ticket.token, owner, access.item(current), admission(access, owner, current))
                    val valid = ready.get() && ticket != null && owner != null && parameters != null &&
                        runtime.ownerFor(provider) === owner && (access.resolveParams == null || captured === parameters) &&
                        ticketValid
                    enter(this, owner, parameters, valid)
                    setObjectExtra(PREVIOUS_RESOLVE, resolving.get())
                    setObjectExtra(RESOLVE_FRAME, true)
                    if (valid) resolving.set(ticket) else resolving.remove()
                }
                safeAfter { leave(this) }
            }
            val consumer = access.consumerMethod ?: return false
            register("player.story.consume_reparse", consumer) {
                safeBefore {
                    val lease = gate.current() ?: return@safeBefore
                    val offset = if (consumer.parameterCount == 5) 1 else 0
                    val owner = lease.owner() ?: return@safeBefore
                    // 同步委托中发生其他宿主消费时不拦截；仅处理本次模块结果的完整身份。
                    if (argOrNull(offset) !== lease.params() || argOrNull(offset + 1) !== gate.resource() ||
                        instance !== access.consumer(owner)) return@safeBefore
                    val position = if (valid(lease, playing = true)) access.currentPosition(owner) else null
                    if (position == null || argOrNull(offset + 2) != null) {
                        failed(lease)
                        result = null
                        return@safeBefore
                    }
                    args[consumer.parameterCount - 1] = position
                    setObjectExtra(REPARSE_LEASE, lease)
                }
                safeAfter {
                    val lease = getObjectExtra(REPARSE_LEASE) as? StoryQualityReparseGate.Lease ?: return@safeAfter
                    if (hasThrowable || result == null) failed(lease)
                }
            }
            register("player.story.wifi", roles.settingIntReader) {
                safeBefore {
                    if (!ready.get() || !runtime.inStory() || argOrNull(0) != com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.StoryQualityLocator.WIFI_KEY)
                        return@safeBefore
                    target()?.let { result = it; applied() }
                }
            }
            roles.integerDefault?.let { method -> register("player.story.integer", method) {
                safeBefore { ensureStoryScope(this) }
                safeAfter {
                    try { if (!hasThrowable && ready.get()) target()?.let { result = it; applied() } }
                    finally { leave(this) }
                }
            } }
            val policy = roles.policy ?: return false
            register("player.story.policy", policy) {
                safeBefore { ensureStoryScope(this) }
                safeAfter {
                    try {
                        if (!hasThrowable && ready.get()) target()?.let { qn ->
                            val original = result
                            if (roles.preference.isAuto(original) || roles.preference.qualityOf(original) != null)
                                roles.preference.fixed(qn)?.let { result = it; applied() }
                        }
                    } finally { leave(this) }
                }
            }
            val resource = roles.resourceSelection ?: return false
            register("player.story.resource", resource) {
                // 原方法只使用宿主基线；权限核对完成后才对本代私有资源提交模块提案。
                safeBefore { enter(this, null, null) }
                safeAfter {
                    leave(this)
                    if (hasThrowable) { runtime.invalidateCurrent(); return@safeAfter }
                    val ticket = resolving.get() ?: return@safeAfter
                    val owner = runtime.owner() ?: return@safeAfter
                    val parameters = access.current(owner)
                    val admission = admission(access, owner, parameters)
                    val qn = target() ?: return@safeAfter
                    val value = argOrNull(0) ?: return@safeAfter
                    val vip = access.vip(owner) ?: return@safeAfter
                    if (ticket.owner.get() !== owner || !runtime.sessions.valid(ticket.token, owner, access.item(parameters), admission))
                        return@safeAfter
                    val candidate = if (resources.isPreloaded(value)) resources.selection(value, qn, login = true, vip = vip) else null
                    val changed = candidate != null && target() == qn && access.vip(owner) == vip &&
                        runtime.sessions.valid(ticket.token, owner, access.item(access.current(owner)), admission(access, owner, access.current(owner))) &&
                        resources.apply(value, candidate)
                    if (changed) applied()
                    if (ticket.token.operation == Operation.RESOURCE_SELECT &&
                        resources.currentEligible(value, login = true, vip = vip))
                        runtime.sessions.finish(ticket.token, owner, access.item(parameters), admission, PlayerQualitySessions.Outcome.COMPLETED)
                }
            }
            val match = roles.matchPreference ?: return false
            register("player.story.match", match) {
                safeBefore {
                    val owner = instance?.let(access::handlerOfService)?.let(runtime::ownerFor)
                    enter(this, owner, owner?.let(access::current), ready.get())
                    observe()
                }
                safeAfter {
                    if (hasThrowable || !access.completed(result)) return@safeAfter
                    val owner = runtime.owner() ?: return@safeAfter
                    val parameters = access.current(owner) ?: return@safeAfter
                    val item = access.item(parameters) ?: return@safeAfter
                    val admission = admission(access, owner, parameters)
                    val qn = target() ?: return@safeAfter
                    val resource = access.mediaResource(owner) ?: return@safeAfter
                    val vip = access.vip(owner) ?: return@safeAfter
                    if (!resources.currentEligible(resource, login = true, vip = vip) || !runtime.onOwnerThread(owner)) return@safeAfter
                    val currentQuality = resources.currentQuality(resource) ?: return@safeAfter
                    if (resources.isPreloaded(resource) && currentQuality < qn) {
                        val snapshot = access.replaySnapshot(owner) ?: return@safeAfter
                        val token = runtime.sessions.reserve(owner, item, admission, Operation.REPARSE) ?: return@safeAfter
                        val lease = StoryQualityReparseGate.Lease(owner, parameters, item, token)
                        if (!valid(lease, playing = true)) return@safeAfter
                        val previous = pendingReparse.get()
                        pendingReparse.set(lease)
                        try { access.reparse(owner, qn, snapshot) }
                        catch (_: Throwable) { failed(lease); error() }
                        finally { if (previous == null) pendingReparse.remove() else pendingReparse.set(previous) }
                    } else if (resources.accepted(resource, qn, login = true, vip = vip)) {
                        val token = runtime.sessions.reserve(owner, item, admission, Operation.PLAYBACK_SETTLE) ?: return@safeAfter
                        if (target() == qn && runtime.sessions.settle(token, owner, item, admission(access, owner, access.current(owner)))) applied()
                    }
                }
            }
            access.shared?.let { shared ->
                // 共享接入单独注册；缺失或框架拒绝此可选 Hook 时，基础链路仍然就绪。
                runCatching {
                    register("player.story.ready", shared.ready) {
                        safeBefore {
                            if (!shared.usesBuilder) ensureStoryScope(this)
                            else {
                                val frame = creating.get()
                                if (frame != null && runtime.inStory() && runtime.onOwnerThread(frame.owner) &&
                                    instance?.let(shared::hasSharingBundle) == true) setObjectExtra(SHARED_BUILD, frame)
                            }
                        }
                        safeAfter {
                            if (shared.usesBuilder) {
                                val frame = getObjectExtra(SHARED_BUILD) as? Creation ?: return@safeAfter
                                if (hasThrowable || creating.get() !== frame) return@safeAfter
                                result?.let { frame.container = WeakReference(it); frame.sharedBuilds++ }
                                return@safeAfter
                            }
                            if (hasThrowable) { instance?.let(runtime::invalidate); return@safeAfter }
                            if (!ready.get() || argOrNull(1) == null) return@safeAfter
                            val owner = instance ?: return@safeAfter
                            confirmShared(owner)
                        }
                    }
                }.onFailure {
                    environment.logError("player_story_shared_registration", "[BIL] Story 共享接入注册失败，保留基础画质链路")
                    error()
                }
            }
            ready.set(true)
            return true
        } catch (_: Throwable) {
            ready.set(false)
            error()
            return false
        }
    }

    @Synchronized private fun ticket(task: Any): Ticket? {
        tickets.removeAll { it.task.get() == null || it.owner.get() == null || it.params.get() == null }
        return tickets.firstOrNull { it.task.get() === task }
    }

    @Synchronized private fun remember(ticket: Ticket) {
        tickets.removeAll { it.task.get() == null || it.task.get() === ticket.task.get() || it.owner.get() == null || it.params.get() == null }
        while (tickets.size >= 32) tickets.removeAt(0)
        tickets += ticket
    }

    @Synchronized private fun remember(coroutine: Any, ticket: Ticket) {
        coroutines.removeAll { it.coroutine.get() == null || it.coroutine.get() === coroutine }
        while (coroutines.size >= 64) coroutines.removeAt(0)
        coroutines += CoroutineTicket(WeakReference(coroutine), ticket)
    }

    @Synchronized private fun coroutineTicket(coroutine: Any): Ticket? {
        coroutines.removeAll { it.coroutine.get() == null }
        return coroutines.firstOrNull { it.coroutine.get() === coroutine }?.ticket
    }

    companion object {
        private const val CREATION = "story.quality.creation"
        private const val SHARED_BUILD = "story.quality.shared_build"
        private const val SCOPE = "story.quality.scope"
        private const val REQUEST = "story.quality.request"
        private const val REQUEST_FRAME = "story.quality.request_frame"
        private const val PREVIOUS_REQUEST = "story.quality.previous_request"
        private const val RESOLVE_FRAME = "story.quality.resolve_frame"
        private const val PREVIOUS_RESOLVE = "story.quality.previous_resolve"
        private const val FAILED_BEFORE = "story.quality.failed_before"
        private const val CLONE_FRAME = "story.quality.clone_frame"
        private const val PREVIOUS_CLONE = "story.quality.previous_clone"
        private const val REPARSE_LEASE = "story.quality.reparse_lease"
    }
}
