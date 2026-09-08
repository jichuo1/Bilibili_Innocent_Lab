package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedSessionLocator
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.highcapable.kavaref.extension.classOf
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import com.highcapable.kavaref.extension.isStatic
import com.highcapable.kavaref.extension.isAbstract
import com.highcapable.kavaref.extension.isSubclassOf
import java.util.WeakHashMap

/** Per-player state only: no media identities leave this object or survive process lifetime. */
internal class PlayerSpeedSessions(private val requested: Float) {
    init { require(requested.isFinite() && requested in 0.25f..4f) }
    private val bases = WeakHashMap<Any, Field>()
    private val initialized = WeakHashMap<Any, Boolean>()
    private val media = WeakHashMap<Any, String>()
    private val coreManagers = WeakHashMap<Any, WeakReference<Any>>()
    private val contexts = WeakHashMap<Any, WeakReference<Any>>()
    private val preparedMedia = WeakHashMap<Any, String>()
    private val registrationLock = Any()
    private val registeredListeners = hashSetOf<Class<*>>()
    private val registeredCores = hashSetOf<Class<*>>()
    private val scope = ThreadLocal<Frame?>()
    private data class Frame(val core: Any, val context: Any, val id: String, val speed: Float,
        var consumed: Boolean = false, var changed: Boolean = false)

    fun capture(manager: Any, point: PlayerSpeedLocator.DefaultSpeedPoint) {
        val values = point.flows.map { it to point.readValue.invoke(it.get(manager)) }
        if (values.size != 2 || values.count { it.second == null } != 1) return
        val base = values.singleOrNull { it.second == 1f }?.first ?: return
        synchronized(bases) { bases[manager] = base }
    }

    internal fun bind(core: Any, manager: Any) {
        synchronized(coreManagers) { coreManagers[core] = WeakReference(manager) }
    }

    fun initialized(manager: Any) {
        synchronized(initialized) { initialized[manager] = true }
    }

    fun discardCapture(manager: Any) {
        synchronized(bases) { bases.remove(manager) }
        synchronized(initialized) { initialized.remove(manager) }
    }

    internal fun applyForMedia(manager: Any, identity: String, point: PlayerSpeedLocator.DefaultSpeedPoint): Boolean {
        val field = synchronized(bases) { bases[manager] } ?: return false
        // Serialize only this rare media transition. State values never strongly retain their weak key.
        synchronized(media) {
            if (media[manager] == identity) return false
            val flow = field.get(manager) ?: return false
            val original = point.readValue.invoke(flow) as? Float ?: return false
            if (!original.isFinite() || original <= 0f) return false
            // A new manager was already initialized. A later explicit host write before its
            // first binding can be a shared-player handoff or an early user choice: preserve it.
            if (media[manager] == null && synchronized(initialized) { initialized[manager] == true } &&
                original != requested) {
                media[manager] = identity
                return false
            }
            try {
                if (original != requested) point.writeValue.invoke(flow, requested)
                check(point.readValue.invoke(flow) == requested)
                media[manager] = identity
                return original != requested
            } catch (failure: Throwable) {
                runCatching { if (point.readValue.invoke(flow) == requested) point.writeValue.invoke(flow, original) }
                throw failure
            }
        }
    }

    fun installModern(env: HookEnvironment, point: PlayerSpeedSessionLocator.Modern,
        speed: PlayerSpeedLocator.DefaultSpeedPoint) {
        val method = point.run
        env.registrar.exact("player.speed.media_session", method.declaringClass, method.name, *method.parameterTypes) {
            after {
                if (hasThrowable) return@after
                val owner = instance ?: return@after
                runCatching {
                    // Both first invocation and coroutine resume re-enter this member. Inspect the
                    // actually bound playable, never the queued argument waiting for the host mutex.
                    val active = point.active.invoke(owner) ?: return@runCatching
                    val params = point.params.invoke(active) ?: return@runCatching
                    val aid = point.aid.invoke(params) as? Long ?: return@runCatching
                    val cid = point.cid.invoke(params) as? Long ?: return@runCatching
                    if (aid <= 0 || cid <= 0) return@runCatching
                    val manager = point.manager.get(owner) ?: return@runCatching
                    env.reportRuntimeEvidence(CAPABILITY, FeatureRuntimeStage.OBSERVED)
                    if (applyForMedia(manager, "$aid:$cid", speed)) {
                        env.reportRuntimeEvidence(CAPABILITY, FeatureRuntimeStage.APPLIED)
                    }
                }.onFailure { env.logError("player_speed_session", "[BIL] 默认倍速会话更新失败，保留宿主行为: $it") }
            }
        }
        point.wrapper?.let { wrapper ->
            env.registrar.constructor("player.speed.core_bridge", wrapper) {
                after {
                    if (hasThrowable) return@after
                    val core = argOrNull(0) ?: return@after
                    val owner = argOrNull(1) ?: return@after
                    runCatching { point.manager.get(owner)?.let { bind(core, it) } }
                        .onFailure { env.logError("player_speed_bridge", "[BIL] 默认倍速基础状态关联失败: $it") }
                }
            }
        }
    }

    private fun effective(manager: Any, point: PlayerSpeedLocator.DefaultSpeedPoint): Float? {
        val base = synchronized(bases) { bases[manager] } ?: return null
        val value = point.readValue.invoke(base.get(manager)) as? Float ?: return null
        val temporary = point.flows.singleOrNull { it != base } ?: return null
        val overlay = point.readValue.invoke(temporary.get(manager))
        if (overlay != null && overlay !is Float) return null
        return (overlay ?: value).takeIf { it.isFinite() && it > 0f && it <= 4f }
    }

    fun installPrepared(env: HookEnvironment, point: PlayerSpeedSessionLocator.Prepared,
        speed: PlayerSpeedLocator.DefaultSpeedPoint?, onReady: () -> Unit = {}) {
        val register = point.register
        env.registrar.exact("player.speed.prepared_registration", register.declaringClass, register.name, *register.parameterTypes) {
            after {
                if (hasThrowable) return@after
                val context = instance ?: return@after
                val listener = argOrNull(0) ?: return@after
                runCatching {
                    // Lazy bounded discovery at listener registration, not a render/gesture hot path.
                    if (attachPrepared(env, point, speed, context, listener)) onReady()
                }.onFailure { env.logError("player_speed_prepare_register", "[BIL] 默认倍速准备链路未安装: $it") }
            }
        }
    }

    private fun attachPrepared(env: HookEnvironment, point: PlayerSpeedSessionLocator.Prepared,
        speed: PlayerSpeedLocator.DefaultSpeedPoint?, context: Any, listener: Any): Boolean {
        val ownerField = Lookup.declaredFields(listener.javaClass, true) {
            !it.isStatic && (it.type isSubclassOf point.core)
        }.singleOrNull() ?: return false
        val core = ownerField.get(listener) ?: return false
        val callback = Lookup.methodOrNull(listener.javaClass, "onPrepared", point.player)
            ?.takeIf { !it.isStatic && !it.isAbstract && it.returnType == Void.TYPE } ?: return false
        val setter = Lookup.inheritedMethodOrNull(core.javaClass, "setPlaySpeed", classOf<Float>())
            ?.takeIf { !it.isStatic && !it.isAbstract && it.returnType == Void.TYPE } ?: return false
        synchronized(contexts) { contexts[core] = WeakReference(context) }
        synchronized(registrationLock) {
            if (setter.declaringClass !in registeredCores) {
                env.registrar.exact("player.speed.prepared_value." + setter.declaringClass.name,
                    setter.declaringClass, setter.name, *setter.parameterTypes) {
                    before {
                        val frame = scope.get() ?: return@before
                        if (frame.core !== instance || frame.consumed) return@before
                        runCatching {
                            if (mediaId(point, frame.core) != frame.id) return@runCatching
                            val original = argOrNull(0) as? Float ?: return@runCatching
                            frame.consumed = true
                            setObjectExtra(FRAME, frame)
                            if (original != frame.speed) {
                                args[0] = frame.speed
                                frame.changed = true
                            }
                        }.onFailure { env.logError("player_speed_prepare_read", "[BIL] 默认倍速媒体校验失败，保留原速度: $it") }
                    }
                    after {
                        val frame = getObjectExtra(FRAME) as? Frame ?: return@after
                        if (hasThrowable) return@after
                        runCatching {
                            if (mediaId(point, frame.core) != frame.id) return@runCatching
                            if (point.speed.invoke(frame.core, false) != frame.speed) return@runCatching
                            synchronized(preparedMedia) { preparedMedia[frame.context] = frame.id }
                            if (frame.changed) env.reportRuntimeEvidence(CAPABILITY, FeatureRuntimeStage.APPLIED)
                        }.onFailure { env.logError("player_speed_prepare_verify", "[BIL] 默认倍速写后校验失败: $it") }
                    }
                }
                registeredCores += setter.declaringClass
            }
            if (callback.declaringClass !in registeredListeners) {
                env.registrar.exact("player.speed.prepared_scope." + callback.declaringClass.name,
                    callback.declaringClass, callback.name, *callback.parameterTypes) {
                    before {
                        // Always mask an outer scope, including unknown/nested callbacks.
                        val previous = scope.get()
                        setObjectExtra(PREVIOUS, previous)
                        scope.remove()
                        runCatching {
                            val target = instance?.let(ownerField::get) ?: return@runCatching
                            val targetContext = synchronized(contexts) { contexts[target]?.get() } ?: return@runCatching
                            val id = mediaId(point, target) ?: return@runCatching
                            val manager = synchronized(coreManagers) { coreManagers[target]?.get() }
                            val selected = if (manager != null && speed != null) effective(manager, speed)
                                else if (synchronized(preparedMedia) { preparedMedia[targetContext] != id }) requested else null
                            if (selected != null) {
                                scope.set(Frame(target, targetContext, id, selected))
                                env.reportRuntimeEvidence(CAPABILITY, FeatureRuntimeStage.OBSERVED)
                            }
                        }.onFailure { env.logError("player_speed_prepare_scope", "[BIL] 默认倍速准备状态不可用: $it") }
                    }
                    after {
                        val previous = getObjectExtra(PREVIOUS) as? Frame
                        if (previous == null) scope.remove() else scope.set(previous)
                    }
                }
                registeredListeners += callback.declaringClass
            }
        }
        return true
    }

    private fun mediaId(point: PlayerSpeedSessionLocator.Prepared, core: Any): String? {
        val resource = point.media.invoke(core) ?: return null
        val index = point.index.invoke(resource) ?: return null
        val from = point.from.get(index) as? String ?: return null
        if (from !in point.allowedSources) return null
        val item = point.item.invoke(core) ?: return null
        val id = point.itemId.invoke(item) as? String ?: return null
        return id.takeIf { it.isNotBlank() && it.length <= 512 }?.let { "$from:$it" }
    }

    companion object {
        const val CAPABILITY = "player_default_speed_percent"
        private const val FRAME = "player_speed_prepared_frame"
        private const val PREVIOUS = "player_speed_prepared_previous"
    }
}
