package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.FrameLayout
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SponsorPlayerAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SponsorSeekAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureRuntimeStage
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SponsorBlockFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.hostProxyDefaultValue
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.reportRuntimeEvidence
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.InjectedUiLocale
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

/** 不轮询、不全局 Hook 触摸；只有显式归属前台详情 Activity 的普通 UGC 会话才查询。 */
internal class SponsorPlayerSessions(private val env: HookEnvironment, private val access: SponsorPlayerAccess,
    private val seekAccess: SponsorSeekAccess, private val detail: Class<*>, private val automatic: Boolean,
    private val repository: SponsorSegmentRepository) {
    private val bindings = SponsorPlayerBindings()
    private val diagnostics = SponsorRuntimeDiagnostics { key, message, failure ->
        if (failure) env.logError(key, message) else env.logInfo(key, message)
    }
    private val active = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private val guard = SponsorSeekGuard()
    @Volatile private var foreground = WeakReference<Activity>(null)
    @Volatile private var current: Session? = null

    fun install(lifecycle: List<Method>, seekMethod: Method): Int {
        var count = 9
        env.registrar.constructor("sponsorblock.wrapper", access.wrapper) {
            after {
                diagnostics.record("callback.wrapper", "active=${active.get()} receiver=${instance != null} failed=$hasThrowable")
                if (!active.get() || hasThrowable) return@after
                val owner = argOrNull(access.wrapperOwnerIndex) ?: return@after
                val wrapper = instance ?: return@after
                val core = argOrNull(access.wrapperCoreIndex) ?: return@after
                bindings.wrapper(owner, wrapper, core)
                diagnostics.record("binding.wrapper")
                main { reconcile() }
            }
        }
        access.scope?.let { scope ->
            count++
            env.registrar.constructor("sponsorblock.activity_scope", scope) {
                after {
                    diagnostics.record("callback.scope", "active=${active.get()} failed=$hasThrowable")
                    if (!active.get() || hasThrowable) return@after
                    val owner = argOrNull(2) ?: return@after
                    val activity = activity(argOrNull(1) as? Context)?.takeIf(detail::isInstance) ?: run {
                        diagnostics.record("binding.failed.scope-context"); return@after
                    }
                    bindings.activity(owner, activity)
                    diagnostics.record("binding.scope")
                    main { reconcile() }
                }
            }
        }
        access.containerScope?.let { scope ->
            count++
            env.registrar.constructor("sponsorblock.container_scope", scope.constructor) {
                after {
                    diagnostics.record("callback.container", "active=${active.get()} failed=$hasThrowable")
                    if (!active.get() || hasThrowable) return@after
                    val context = argOrNull(scope.contextIndex) as? Context
                    val activity = activity(context)?.takeIf(detail::isInstance) ?: run {
                        diagnostics.record("binding.failed.container-context"); return@after
                    }
                    val container = argOrNull(scope.containerIndex) ?: return@after
                    val core = runCatching { scope.core.invoke(container) }.getOrNull() ?: run {
                        diagnostics.record("binding.failed.container-core"); return@after
                    }
                    val matched = bindings.container(core, activity)
                    diagnostics.record(if (matched) "binding.container" else "binding.container-pending")
                    main { reconcile() }
                }
            }
        }
        env.registrar.exact("sponsorblock.media", access.run.declaringClass, access.run.name, *access.run.parameterTypes) {
            before {
                diagnostics.record("callback.media", "active=${active.get()} receiver=${instance != null}")
                if (!active.get()) return@before
                val owner = instance ?: return@before
                val epoch = bindings.begin(owner)
                setObjectExtra("sponsorblock_epoch", epoch)
                current?.takeIf { it.owner.get() === owner }?.invalid?.set(true)
                main { if (current?.invalid?.get() == true) detach() }
            }
            after {
                if (!active.get() || hasThrowable) return@after
                val owner = instance ?: return@after
                if (!access.bound(owner, argOrNull(0), argOrNull(1))) {
                    diagnostics.record("binding.media-pending"); return@after
                }
                val epoch = getObjectExtra("sponsorblock_epoch") as? Long ?: return@after
                bindings.ready(owner, epoch)
                diagnostics.record("binding.media")
                main { reconcile() }
            }
        }
        env.registrar.constructor("sponsorblock.seek_capture", seekAccess.constructor) {
            after {
                if (hasThrowable) return@after
                val coroutine = instance ?: return@after
                val owner = argOrNull(0) ?: return@after
                val target = argOrNull(1) as? Int ?: return@after
                guard.capture(coroutine, owner, target)
            }
        }
        env.registrar.exact("sponsorblock.seek_clone", seekAccess.create.declaringClass,
            seekAccess.create.name, *seekAccess.create.parameterTypes) {
            after { if (!hasThrowable) { val original = instance; val clone = result
                if (original != null && clone != null) guard.carry(original, clone) } }
        }
        env.registrar.exact("sponsorblock.seek_execute", seekAccess.invoke.declaringClass,
            seekAccess.invoke.name, *seekAccess.invoke.parameterTypes) {
            before {
                val coroutine = instance ?: return@before
                if (seekAccess.label.getInt(coroutine) == 0 && !guard.allow(coroutine)) result = seekAccess.unit
            }
        }
        env.registrar.exact("sponsorblock.user_seek", seekMethod.declaringClass, seekMethod.name, *seekMethod.parameterTypes) {
            before {
                if (!active.get()) return@before
                val session = current?.takeIf { it.wrapper.get() === instance } ?: return@before
                val owner = session.owner.get() ?: return@before
                if (!guard.isSubmitting(owner)) {
                    session.controller.invalidateExternalSeek()
                    main { if (current === session) session.controller.externalSeekStarted("call") }
                }
            }
        }
        lifecycle.forEachIndexed { index, method ->
            env.registrar.exact("sponsorblock.lifecycle.$index", method.declaringClass, method.name, *method.parameterTypes) {
                if (index == 0) {
                    before {
                        val target = argOrNull(0) as? Activity
                        if (target?.componentName?.className == detail.name || target?.javaClass?.name == detail.name) {
                            diagnostics.record("callback.resume", "active=${active.get()} type=${detail.isInstance(target)} " +
                                "name=${target.javaClass.name == detail.name} loader=${target.classLoader === env.classLoader}")
                        }
                    }
                    after {
                        val activity = argOrNull(0) as? Activity ?: return@after
                        if (!active.get() || hasThrowable || !detail.isInstance(activity)) return@after
                        foreground = WeakReference(activity)
                        diagnostics.record("binding.foreground")
                        main { if (foreground.get() === activity) reconcile() }
                    }
                } else before {
                    val activity = argOrNull(0) as? Activity ?: return@before
                    if (!active.get() || !detail.isInstance(activity)) return@before
                    if (foreground.get() === activity) {
                        foreground = WeakReference(null)
                        current?.invalid?.set(true)
                        main { if (current?.activity?.get() === activity) detach() }
                    }
                    if (index == 2) bindings.destroy(activity)
                }
            }
        }
        active.set(true)
        diagnostics.record("installed", "active=${active.get()} hooks=$count resume=${lifecycle.first().declaringClass.name}")
        return count
    }

    private fun main(block: () -> Unit) {
        // 始终排队：构造、宿主观察者和缓存回调不能重入尚未完成的初始化。
        handler.post { runCatching(block).onFailure {
            env.logError("sponsorblock_runtime", "[BIL] 商单提示已保持宿主行为: ${it.javaClass.simpleName}")
        } }
    }

    private fun reconcile() {
        if (!active.get()) return
        val activity = foreground.get()?.takeUnless { it.isFinishing || it.isDestroyed } ?: run { detach(); return }
        val entry = bindings.select(activity)
        val owner = entry?.first ?: run { diagnostics.record("binding.waiting"); detach(); return }
        val candidate = entry.second
        val wrapper = candidate.wrapper.get() ?: run { detach(); return }
        val video = access.video(owner) { diagnostics.record("binding.media.$it") }
            ?: run { diagnostics.record("binding.invalid-media"); detach(); return }
        val epoch = candidate.epoch.get()
        if (current?.let { !it.invalid.get() && it.owner.get() === owner && it.wrapper.get() === wrapper &&
                it.video == video && it.epoch == epoch && it.activity.get() === activity } == true) return
        detach()
        val root = activity.findViewById<FrameLayout>(android.R.id.content) ?: run {
            diagnostics.record("binding.failed.root"); return
        }
        val session = Session(owner, wrapper, candidate, epoch, video, activity, root)
        current = session
        if (runCatching { session.start() }.onFailure {
                diagnostics.record("session.failed", it.javaClass.simpleName)
            }.isFailure) { session.invalid.set(true); detach() }
    }

    private fun detach() {
        val old = current ?: return
        current = null
        old.stop()
    }

    private inner class Session(owner: Any, wrapper: Any, private val candidate: SponsorPlayerBindings.Candidate, val epoch: Long,
        val video: SponsorVideoId, activity: Activity, root: FrameLayout) : SponsorPlayerPort {
        val owner = WeakReference(owner)
        val wrapper = WeakReference(wrapper)
        val activity = WeakReference(activity)
        private val root = WeakReference(root)
        val invalid = AtomicBoolean(false)
        /** 会话级诊断：每个视频一份，不被先前视频的同名事件吞掉。 */
        private val log = diagnostics.session()
        private val progressQueued = AtomicBoolean(false)
        private val progressSeen = AtomicBoolean(false)
        private val unregister = mutableListOf<Pair<Method, Any>>()
        private val hintOverlay: SponsorHintOverlay
        init {
            val weak = WeakReference(this)
            hintOverlay = SponsorHintOverlay(root) { hint -> weak.get()?.let { session ->
                main { if (current === session) {
                    if (hint == SponsorPlaybackController.Hint.UNDO) session.controller.undo() else session.controller.skip()
                } }
            } }
        }
        val controller = SponsorPlaybackController(video, automatic, this, repository::lookup, ::main,
            { delay, block -> val task = Runnable(block); handler.postDelayed(task, delay)
                SponsorSegmentRepository.Ticket { handler.removeCallbacks(task) } }, SystemClock::uptimeMillis,
            ::render, { env.reportRuntimeEvidence(SponsorBlockFeatureInstaller.ID, FeatureRuntimeStage.APPLIED) },
            log)

        override fun valid(video: SponsorVideoId): Boolean {
            val owner = owner.get() ?: return false
            val wrapper = wrapper.get() ?: return false
            val activity = activity.get() ?: return false
            return !invalid.get() && current === this && foreground.get() === activity && root.get() != null &&
                access.video(owner) == video && bindings.current(owner, candidate, epoch, wrapper, activity)
        }
        override fun position(): Long = if (!progressSeen.get()) -1L else
            wrapper.get()?.let { access.position.invoke(it) as? Int }?.toLong() ?: -1L
        override fun duration(): Long = if (!progressSeen.get()) 0L else
            wrapper.get()?.let { access.duration.invoke(it) as? Int }?.toLong() ?: 0L
        override fun playing(): Boolean = wrapper.get()?.let { access.state.invoke(it) as? Int } == 4
        override fun seekRefusal(): String = guard.lastRefusal
        override fun seek(targetMs: Int, valid: () -> Boolean, executing: () -> Unit): Boolean {
            val owner = owner.get() ?: return false
            val wrapper = wrapper.get() ?: return false
            return guard.submit(owner, targetMs, { progressSeen.get() && valid() }, executing) {
                access.seek.invoke(wrapper, targetMs, false)
            }
        }

        fun start() {
            observe(access.releaseObserver, access.registerRelease, access.unregisterRelease) { name, _ ->
                if (name == "onPlayerWillRelease") {
                    invalid.set(true)
                    owner.get()?.let { bindings.release(it, epoch) }
                    main { if (current === this) detach() }
                } else if (name == "onPlayerItemWillChanged" || name == "onPlayerItemRelease") {
                    progressSeen.set(false)
                    controller.invalidateExternalSeek()
                    main { if (current === this) controller.refresh() }
                }
            }
            observe(access.progressObserver, access.registerProgress, access.unregisterProgress) { name, _ ->
                if (name == "onPlayerProgressChange") {
                    if (progressSeen.compareAndSet(false, true)) log("progress.observed", "")
                    if (progressQueued.compareAndSet(false, true)) main {
                        progressQueued.set(false)
                        if (current === this) { if (valid(video)) controller.refresh() else detach() }
                    }
                }
            }
            observe(access.seekObserver, access.registerSeek, access.unregisterSeek) { name, args ->
                when (name) {
                    "onSeekStart" -> (args?.firstOrNull() as? Long)?.let { target ->
                        controller.invalidateExternalSeek(target)
                        main { if (current === this) controller.seekStarted(target) }
                    }
                    "onSeekComplete" -> main { if (current === this) controller.seekCompleted() }
                }
            }
            observe(access.stateObserver, access.registerState, access.unregisterState,
                intArrayOf(4, 5)) { name, _ -> if (name == "onPlayerStateChanged") main {
                    if (current === this) controller.stateChanged()
                } }
            env.reportRuntimeEvidence(SponsorBlockFeatureInstaller.ID, FeatureRuntimeStage.OBSERVED)
            log("session.bound", "")
            controller.start()
        }

        private fun observe(type: Class<*>, register: Method, remove: Method, states: IntArray? = null,
            callback: (String, Array<out Any?>?) -> Unit) {
            val weak = WeakReference(this)
            val observer = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "toString" -> "InnocentLabSponsorObserver"
                    else -> {
                        if (weak.get()?.invalid?.get() == false) runCatching { callback(method.name, args) }
                            .onFailure { diagnostics.record("observer.failed", it.javaClass.simpleName) }
                        // 宿主直接调用这个代理，框架的 PROTECTIVE 兜不住：基本类型返回值不能给 null。
                        hostProxyDefaultValue(method.returnType)
                    }
                }
            }
            val wrapper = wrapper.get() ?: error("released-player")
            if (states == null) register.invoke(wrapper, observer) else register.invoke(wrapper, observer, states)
            unregister += remove to observer
        }

        fun stop() {
            invalid.set(true)
            controller.stop()
            wrapper.get()?.let { target -> unregister.asReversed().forEach { (method, observer) ->
                runCatching { method.invoke(target, observer) }
            } }
            unregister.clear()
            render(SponsorPlaybackController.Hint.NONE)
        }

        private fun render(hint: SponsorPlaybackController.Hint) {
            if (hint == SponsorPlaybackController.Hint.NONE) {
                hintOverlay.hide()
                return
            }
            val activity = activity.get() ?: return
            if (!valid(video)) return
            hintOverlay.render(hint, InjectedUiLocale.messages(activity))
        }
    }

    private fun activity(context: Context?): Activity? {
        var current = context
        repeat(8) {
            if (current is Activity) return current
            val next = (current as? ContextWrapper)?.baseContext ?: return null
            if (next === current) return null
            current = next
        }
        return null
    }
}
