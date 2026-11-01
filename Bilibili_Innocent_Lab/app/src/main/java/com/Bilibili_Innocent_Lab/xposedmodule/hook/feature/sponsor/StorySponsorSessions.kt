package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.FrameLayout
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SponsorStoryAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureRuntimeStage
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.reportRuntimeEvidence
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.InjectedUiLocale
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Story（竖屏短视频）的商单跳过外壳：一个 `StoryPlayer` 一个会话，不轮询、不全局 Hook 触摸。
 *
 * 一个 StoryVideoActivity 只有一个 StoryPlayer 和一个内核，分页器在这个内核里反复换条目，
 * 所以会话按 StoryPlayer 归属，条目级的绑定与准入在 [StorySponsorSession]。
 * 模块自己投递到宿主调度器的任务、观察者回调体都经过 [HostThreadGuard]。
 */
internal class StorySponsorSessions(
    private val env: HookEnvironment,
    private val access: SponsorStoryAccess,
    private val automatic: Boolean,
    private val repository: SponsorSegmentRepository,
    private val featureId: String
) {
    private val diagnostics = SponsorRuntimeDiagnostics("sponsorblock_story_runtime", "SponsorBlock-Story") { key, message, failure ->
        if (failure) env.logError(key, message) else env.logInfo(key, message)
    }
    private val active = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private val budget = StoryLookupBudget(StorySponsorSession.BUDGET_LIMIT, StorySponsorSession.BUDGET_WINDOW_MS,
        SystemClock::uptimeMillis)
    private val sessions = WeakHashMap<Any, Shell>()
    private val pagers = ArrayList<WeakReference<Any>>()
    @Volatile private var foreground = WeakReference<Activity>(null)

    /** [lifecycle] 依次为 callActivityOnResume / callActivityOnPause / callActivityOnDestroy。 */
    fun install(lifecycle: List<Method>): Int {
        env.registrar.exact("$featureId.create", access.onCreate.declaringClass, access.onCreate.name,
            *access.onCreate.parameterTypes) {
            after {
                if (!active.get() || hasThrowable) return@after
                val owner = instance ?: return@after
                bind(owner, argOrNull(0))
            }
        }
        listOf("release" to access.release, "destroy" to access.onDestroy).forEach { (name, method) ->
            env.registrar.exact("$featureId.$name", method.declaringClass, method.name, *method.parameterTypes) {
                before { if (active.get()) instance?.let(::unbind) }
            }
        }
        env.registrar.constructor("$featureId.pager", access.pagerConstructor) {
            after { if (active.get() && !hasThrowable) instance?.let(::registerPager) }
        }
        lifecycle.forEachIndexed { index, method ->
            env.registrar.exact("$featureId.lifecycle.$index", method.declaringClass, method.name, *method.parameterTypes) {
                if (index == 0) {
                    after {
                        val activity = argOrNull(0) as? Activity ?: return@after
                        if (!active.get() || hasThrowable || !access.activityClass.isInstance(activity)) return@after
                        foreground = WeakReference(activity)
                        diagnostics.record("binding.foreground")
                        main { snapshot().forEach { it.session.resumed() } }
                    }
                } else before {
                    val activity = argOrNull(0) as? Activity ?: return@before
                    if (!active.get() || !access.activityClass.isInstance(activity)) return@before
                    if (foreground.get() === activity) foreground = WeakReference(null)
                    main {
                        snapshot().forEach { it.session.resumed() }
                        if (index == 2) snapshot().filter { it.activity.get() === activity }.forEach { unbindShell(it) }
                    }
                }
            }
        }
        active.set(true)
        diagnostics.record("installed", "hooks=${4 + lifecycle.size}")
        return 4 + lifecycle.size
    }

    private fun main(block: () -> Unit) {
        // 始终排队：构造与宿主回调不能重入尚未完成的初始化。
        handler.post(HostThreadGuard.runnable("sponsorblock_story.main") {
            try { block() } catch (failure: Throwable) {
                env.reportRuntimeEvidence(featureId, FeatureRuntimeStage.ERROR)
                throw failure
            }
        })
    }

    private fun bind(owner: Any, lifecycleOwner: Any?) {
        // StoryPlayer 还被侧边栏、快捷入口等页面使用；不是 Story 播放页就不是这个功能的范围，不算失败。
        val activity = access.activityOf(owner, lifecycleOwner) ?: run {
            diagnostics.record("binding.other-host"); return
        }
        val core = access.coreOf(owner) ?: run { diagnostics.record("binding.failed.core"); return }
        unbind(owner)
        val shell = Shell(owner, core, activity)
        synchronized(sessions) { sessions[owner] = shell }
        main { shell.start() }
    }

    private fun unbind(owner: Any) {
        val shell = synchronized(sessions) { sessions.remove(owner) } ?: return
        shell.invalid = true
        if (Looper.myLooper() == Looper.getMainLooper()) shell.stop() else main { shell.stop() }
    }

    private fun unbindShell(shell: Shell) {
        val owner = shell.owner.get()
        if (owner != null) unbind(owner) else { shell.invalid = true; main { shell.stop() } }
    }

    private fun snapshot(): List<Shell> = synchronized(sessions) { sessions.values.toList() }

    private fun registerPager(pager: Any) = synchronized(pagers) {
        pagers.removeAll { it.get() == null }
        if (pagers.none { it.get() === pager }) pagers += WeakReference(pager)
        while (pagers.size > MAX_PAGERS) pagers.removeAt(0)
    }

    private fun livePagers(): List<Any> = synchronized(pagers) { pagers.mapNotNull { it.get() } }

    private inner class Shell(owner: Any, core: Any, activity: Activity) : StorySponsorHost {
        val owner = WeakReference(owner)
        val core = WeakReference(core)
        val activity = WeakReference(activity)
        @Volatile var invalid = false
        private val progressQueued = AtomicBoolean(false)
        private val unregister = mutableListOf<Pair<Method, Any>>()
        private var overlay: SponsorHintOverlay? = null
        val session: StorySponsorSession

        init {
            session = StorySponsorSession(this, automatic, repository::lookup, budget, ::main,
                { delay, block -> val task = HostThreadGuard.runnable("sponsorblock_story.timer", block)
                    handler.postDelayed(task, delay)
                    SponsorSegmentRepository.Ticket { handler.removeCallbacks(task) } },
                SystemClock::uptimeMillis, ::render,
                { env.reportRuntimeEvidence(featureId, FeatureRuntimeStage.APPLIED) }, diagnostics::record)
        }

        override fun alive(): Boolean = !invalid && owner.get() != null && core.get() != null &&
            activity.get()?.let { !it.isFinishing && !it.isDestroyed } == true
        override fun resumed(): Boolean = foreground.get().let { it != null && it === activity.get() }
        override fun state(): Int = core.get()?.let { access.core.int(access.core.state, it) } ?: 0
        override fun position(): Long = core.get()?.let { access.core.int(access.core.position, it) }?.toLong() ?: -1L
        override fun duration(): Long = core.get()?.let { access.core.int(access.core.duration, it) }?.toLong() ?: 0L
        override fun realPosition(): Long = core.get()?.let { access.core.int(access.core.realPosition, it) }?.toLong() ?: -1L
        override fun realDuration(): Long = core.get()?.let { access.core.int(access.core.realDuration, it) }?.toLong() ?: 0L
        override fun playable(): StoryPlayableIdentity? = owner.get()?.let(access::playable)
        override fun loadedId(): String? = core.get()?.let(access.core::loadedId)
        override fun details(): List<StoryDetailSnapshot> {
            val player = owner.get() ?: return emptyList()
            return livePagers().filter { access.isPagerOf(it, player) }.mapNotNull(access::detail)
        }
        override fun seek(targetMs: Int) {
            core.get()?.let { access.core.seek.invoke(it, targetMs, false) }
        }

        fun start() {
            if (invalid) return
            try {
                register()
            } catch (failure: Throwable) {
                // 已注册的观察者不能留在内核上；宿主播放保持原样。
                diagnostics.record("session.failed", failure.javaClass.simpleName)
                env.reportRuntimeEvidence(featureId, FeatureRuntimeStage.ERROR)
                stop()
                return
            }
            env.reportRuntimeEvidence(featureId, FeatureRuntimeStage.OBSERVED)
            diagnostics.record("session.registered")
            session.resumed()
        }

        private fun register() {
            // Story 的内核跨条目存活，释放类回调只表示当前条目结束；整个播放器的拆除由
            // StoryPlayer.release / onDestroy 的 Hook 和 Activity 销毁负责，这里不据此结束会话。
            observe(access.core.releaseObserver, access.core.registerRelease, access.core.unregisterRelease) { name, _ ->
                when (name) {
                    "onPlayerWillRelease", "onPlayerItemWillChanged", "onPlayerItemRelease" ->
                        main { if (!invalid) session.itemChanged() }
                }
            }
            observe(access.core.progressObserver, access.core.registerProgress, access.core.unregisterProgress) { name, _ ->
                if (name == "onPlayerProgressChange" && progressQueued.compareAndSet(false, true)) main {
                    progressQueued.set(false)
                    if (!invalid) session.progressTick()
                }
            }
            observe(access.core.seekObserver, access.core.registerSeek, access.core.unregisterSeek) { name, args ->
                when (name) {
                    "onSeekStart" -> (args?.firstOrNull() as? Long)?.let { target ->
                        session.invalidateExternalSeek(target)
                        main { if (!invalid) session.seekStarted(target) }
                    }
                    "onSeekComplete" -> main { if (!invalid) session.seekCompleted() }
                }
            }
            observe(access.core.stateObserver, access.core.registerState, access.core.unregisterState,
                intArrayOf(4, 5)) { name, _ ->
                if (name == "onPlayerStateChanged") main { if (!invalid) session.stateChanged() }
            }
        }

        private fun observe(type: Class<*>, register: Method, remove: Method, states: IntArray? = null,
            callback: (String, Array<out Any?>?) -> Unit) {
            val weak = WeakReference(this)
            val observer = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "toString" -> "InnocentLabSponsorStoryObserver"
                    else -> {
                        if (weak.get()?.invalid == false) HostThreadGuard.run("sponsorblock_story.observer") {
                            callback(method.name, args)
                        }
                        null
                    }
                }
            }
            val target = core.get() ?: error("released-player")
            if (states == null) register.invoke(target, observer) else register.invoke(target, observer, states)
            unregister += remove to observer
        }

        fun stop() {
            invalid = true
            session.stop()
            core.get()?.let { target -> unregister.asReversed().forEach { (method, observer) ->
                HostThreadGuard.run("sponsorblock_story.unregister") { method.invoke(target, observer) }
            } }
            unregister.clear()
            overlay?.hide()
        }

        private fun render(hint: SponsorPlaybackController.Hint) {
            if (hint == SponsorPlaybackController.Hint.NONE) { overlay?.hide(); return }
            val activity = activity.get() ?: return
            if (invalid || session.bound == null) return
            // 内容根视图在 StoryPlayer.onCreate 时可能还没建好，首次显示时再取。
            val view = overlay ?: activity.findViewById<FrameLayout>(android.R.id.content)?.let { root ->
                val weak = WeakReference(this)
                SponsorHintOverlay(root, SponsorHintOverlay.Placement.STORY) { clicked ->
                    main {
                        val shell = weak.get()?.takeIf { !it.invalid } ?: return@main
                        if (clicked == SponsorPlaybackController.Hint.UNDO) shell.session.undo() else shell.session.skip()
                    }
                }.also { overlay = it }
            } ?: return
            view.render(hint, InjectedUiLocale.messages(activity))
        }
    }

    private companion object {
        const val MAX_PAGERS = 4
    }
}
