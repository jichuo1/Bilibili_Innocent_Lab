package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight

import android.app.Activity
import android.app.Instrumentation
import android.os.Handler
import android.os.Looper
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SponsorPlayerLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor.SponsorPlayerBindings
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor.SponsorSeekGuard
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.commentOwnerActivity
import android.content.Context
import java.lang.ref.WeakReference

internal class InsightPlayerSession(val video: InsightVideo, val valid: () -> Boolean, val seek: (Int) -> Boolean)

/** 复用现有播放器定位和延迟 seek 门禁；归属不明、切视频、切分 P、退后台时一律失效。 */
internal class InsightPlayerBridge(private val environment: HookEnvironment,
    private val resolved: SponsorPlayerLocator.Resolved, private val changed: (Activity, InsightPlayerSession?) -> Unit) {
    private val bindings = SponsorPlayerBindings()
    private val guard = SponsorSeekGuard()
    private val handler = Handler(Looper.getMainLooper())
    private var foreground = WeakReference<Activity>(null)
    private val access get() = resolved.player
    private fun main(block: () -> Unit) { handler.post(HostThreadGuard.runnable("insight.player", block)) }

    fun current(activity: Activity): InsightPlayerSession? {
        if (foreground.get() !== activity || activity.isFinishing || activity.isDestroyed) return null
        val (owner, candidate) = bindings.select(activity) ?: return null
        val wrapper = candidate.wrapper.get() ?: return null
        val video = access.video(owner)?.let { InsightVideo(it.bvid, it.cid) } ?: return null
        val epoch = candidate.epoch.get()
        val weakOwner = WeakReference(owner); val weakWrapper = WeakReference(wrapper); val weakActivity = WeakReference(activity)
        val valid = {
            val target = weakActivity.get(); val media = weakOwner.get(); val player = weakWrapper.get()
            target != null && media != null && player != null && foreground.get() === target &&
                !target.isFinishing && !target.isDestroyed && bindings.current(media, candidate, epoch, player, target) &&
                access.video(media)?.let { it.bvid == video.bvid && it.cid == video.cid } == true
        }
        return InsightPlayerSession(video, valid) { seconds ->
            HostThreadGuard.call("insight.seek", false) {
                val player = weakWrapper.get(); val media = weakOwner.get()
                val millis = seconds.toLong() * 1000
                val duration = player?.let { access.duration.invoke(it) as? Int } ?: 0
                if (player == null || media == null || !valid() || millis !in 0 until duration.toLong()) false
                else guard.submit(media, millis.toInt(), valid, {}) { access.seek.invoke(player, millis.toInt(), false) }
            }
        }
    }

    fun install(): Int {
        val env = environment
        env.registrar.constructor("insight.player.wrapper", access.wrapper) { after {
            if (hasThrowable) return@after
            val owner = argOrNull(access.wrapperOwnerIndex) ?: return@after
            val wrapper = instance ?: return@after
            val core = argOrNull(access.wrapperCoreIndex) ?: return@after
            bindings.wrapper(owner, wrapper, core); refresh()
        } }
        access.scope?.let { scope -> env.registrar.constructor("insight.player.scope", scope) { after {
            if (hasThrowable) return@after
            val owner = argOrNull(2) ?: return@after
            val activity = (argOrNull(1) as? Context)?.let(::commentOwnerActivity) ?: return@after
            bindings.activity(owner, activity); refresh()
        } } }
        access.containerScope?.let { scope -> env.registrar.constructor("insight.player.container", scope.constructor) { after {
            if (hasThrowable) return@after
            val activity = (argOrNull(scope.contextIndex) as? Context)?.let(::commentOwnerActivity) ?: return@after
            val container = argOrNull(scope.containerIndex) ?: return@after
            val core = scope.core.invoke(container) ?: return@after
            bindings.container(core, activity); refresh()
        } } }
        env.registrar.exact("insight.player.media", access.run.declaringClass, access.run.name, *access.run.parameterTypes) {
            before {
                val owner = instance ?: return@before
                setObjectExtra("insight.epoch", bindings.begin(owner)); refresh()
            }
            after {
                if (hasThrowable) return@after
                val owner = instance ?: return@after
                if (access.bound(owner, argOrNull(0), argOrNull(1))) {
                    val epoch = getObjectExtra("insight.epoch") as? Long ?: return@after
                    bindings.ready(owner, epoch); refresh()
                }
            }
        }
        val seek = resolved.seek
        env.registrar.constructor("insight.seek.capture", seek.constructor) { after {
            if (!hasThrowable) {
                val coroutine = instance; val owner = argOrNull(0); val target = argOrNull(1) as? Int
                if (coroutine != null && owner != null && target != null) guard.capture(coroutine, owner, target)
            }
        } }
        env.registrar.exact("insight.seek.clone", seek.create.declaringClass, seek.create.name, *seek.create.parameterTypes) { after {
            val original = instance; val clone = result
            if (!hasThrowable && original != null && clone != null) guard.carry(original, clone)
        } }
        env.registrar.exact("insight.seek.execute", seek.invoke.declaringClass, seek.invoke.name, *seek.invoke.parameterTypes) { before {
            val coroutine = instance ?: return@before
            if (seek.label.getInt(coroutine) == 0 && !guard.allow(coroutine)) result = seek.unit
        } }
        listOf("callActivityOnResume", "callActivityOnPause", "callActivityOnDestroy").forEachIndexed { index, name ->
            val method = Lookup.methodOrNull(Instrumentation::class.java, name, Activity::class.java) ?: return@forEachIndexed
            env.registrar.exact("insight.lifecycle.$index", method.declaringClass, name, *method.parameterTypes) {
                if (index == 0) after {
                    val activity = argOrNull(0) as? Activity ?: return@after
                    if (!hasThrowable && activity.javaClass.name == DETAIL) { foreground = WeakReference(activity); refresh() }
                } else before {
                    val activity = argOrNull(0) as? Activity ?: return@before
                    if (foreground.get() === activity) {
                        foreground = WeakReference(null)
                        main { changed(activity, null) }
                    }
                    if (index == 2) bindings.destroy(activity)
                }
            }
        }
        return 8 + (if (access.scope != null) 1 else 0) + (if (access.containerScope != null) 1 else 0)
    }
    private fun refresh() { main { foreground.get()?.let { changed(it, current(it)) } } }
    companion object { const val DETAIL = "com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity" }
}
