// Handler 回调与 removeCallbacks/任务 generation 共用回收语义，保留原生调度以免改变取消边界。
@file:Suppress("ReplaceWithCoroutinesExtension")

package com.Bilibili_Innocent_Lab.xposedmodule.agent.host

import android.app.Activity
import android.app.Application
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.util.Base64
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import com.Bilibili_Innocent_Lab.xposedmodule.agent.AgentWire
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import io.github.jichuo1.meguviz.android.extensions.child
import io.github.jichuo1.meguviz.android.extensions.removeSelf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.json.JSONObject

internal class HostAgentFailure(val reason: String) : RuntimeException(reason)

/** 仅观察本进程 Activity；所有 View/Window 访问均由主线程执行。 */
internal class HostAgentWindow(
    application: Application,
    private val main: Handler,
    private val session: HostAgentSession,
    private val onStop: (HostAgentSession.Lease) -> Unit
) : Application.ActivityLifecycleCallbacks {
    private var resumed = WeakReference<Activity>(null)
    private var stopButton = WeakReference<Button>(null)
    private var ownedWindow = WeakReference<Window>(null)
    private var ownedCallback = WeakReference<TaskCallback>(null)
    private var navigationObserver = WeakReference<ViewTreeObserver>(null)
    private var navigationListener: ViewTreeObserver.OnPreDrawListener? = null
    private var windowSerial = 0L
    private var pageReadyElapsed = 0L
    private var ownershipGeneration: Long? = null
    private val control = HostAgentWindowPolicy()

    init { application.registerActivityLifecycleCallbacks(this) }

    override fun onActivityResumed(activity: Activity) {
        if (resumed.get() !== activity) windowSerial++
        resumed = WeakReference(activity)
        val lease = session.current(now()) ?: return
        // Binder 可先于宿主 onResume 到达；begin 的主线程步骤会建立首次窗口所有权。
        if (!control.tracks(lease.generation)) return
        runCatching {
            val observed = page(activity, lease)
            if (!control.resume(lease.generation, windowSerial, observed.kind, observed.identity, now())) {
                stopTask(lease)
            } else {
                pageReadyElapsed = now()
                removeNavigationObserver()
                installCallback(activity, lease)
                showStop(lease)
            }
        }.onFailure { stopTask(lease) }
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed.get() !== activity) return
        session.current(now())?.let { lease ->
            if (control.tracks(lease.generation) && !control.pause(lease.generation, windowSerial, now())) stopTask(lease)
        }
        resumed.clear()
        detachCallback()
        removeNavigationObserver()
        removeStop()
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (resumed.get() === activity) onActivityPaused(activity)
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit

    fun activity(): Activity? = resumed.get()?.takeIf {
        it.packageName == AgentWire.TARGET_PACKAGE && !it.isFinishing && !it.isDestroyed &&
            it.hasWindowFocus() && it.window?.decorView?.isShown == true
    }

    /** 只读续行只能使用此前拥有的同一窗口；不能从任意仍存活的后台 Activity 重建授权。 */
    private fun ownedActivity(lease: HostAgentSession.Lease, readOnly: Boolean): Activity? = resumed.get()?.takeIf {
        it.packageName == AgentWire.TARGET_PACKAGE && !it.isFinishing && !it.isDestroyed &&
            ownershipGeneration == lease.generation && ownedWindow.get() === it.window &&
            ownedCallback.get()?.let { callback -> it.window?.callback === callback && callback.lease.generation == lease.generation } == true &&
            control.canUseWindow(lease.generation, windowSerial, resumed = true,
                shown = it.window?.decorView?.isShown == true, focused = it.hasWindowFocus(), readOnly = readOnly)
    }

    /** 开始时只接受实际拥有焦点的宿主窗口，之后任何用户输入都会撤销这一所有权。 */
    private fun beginTask(lease: HostAgentSession.Lease) {
        val current = activity() ?: throw HostAgentFailure("host_not_foreground")
        removeNavigationObserver()
        ownershipGeneration = lease.generation
        control.begin(lease.generation, windowSerial)
        installCallback(current, lease)
        showStop(lease)
    }

    /** 后台调用；冷启动端点先于窗口出现时最多等 3 秒，不启动页面、不在主线程等待。 */
    fun awaitTaskWindow(lease: HostAgentSession.Lease) {
        awaitMainCondition(lease, 3000L, "host_foreground_timeout") {
            if (activity() == null) false else { beginTask(lease); true }
        }
    }

    /** 续租保留同一 resumed 可见窗口；系统浮层临时失焦不撤销只读权，真实离页仍停止。 */
    fun awaitTaskContext(lease: HostAgentSession.Lease) {
        awaitMainCondition(lease, HostAgentWindowPolicy.NAVIGATION_MS, "task_context_lost") {
            val current = ownedActivity(lease, readOnly = true)
            if (current == null) {
                if (!control.awaitingNavigation(lease.generation, now())) throw HostAgentFailure("host_not_foreground")
                false
            } else when (observeContext(current, lease)) {
                HostAgentWindowPolicy.Observation.READY -> true
                HostAgentWindowPolicy.Observation.NAVIGATING -> false
                HostAgentWindowPolicy.Observation.REVOKED -> throw HostAgentFailure("task_context_lost")
            }
        }
    }

    private fun awaitMainCondition(lease: HostAgentSession.Lease, limitMs: Long, reason: String, ready: () -> Boolean) {
        val future = CompletableFuture<Unit>()
        val deadline = minOf(lease.deadline, now() + limitMs)
        val poll = object : Runnable {
            override fun run() {
                if (future.isDone) return
                try {
                    if (!session.isActive(lease, now())) throw HostAgentFailure("task_inactive")
                    if (now() >= deadline) throw HostAgentFailure(reason)
                    if (ready()) future.complete(Unit) else main.postDelayed(this, 50L)
                } catch (failure: Throwable) { future.completeExceptionally(failure) }
            }
        }
        main.post(poll)
        try { future.get((deadline - now()).coerceAtLeast(1) + 100L, TimeUnit.MILLISECONDS) }
        catch (failure: Exception) {
            future.cancel(false)
            throw failure.cause as? HostAgentFailure ?: HostAgentFailure(reason)
        } finally { main.removeCallbacks(poll) }
    }

    /** 即使 Binder 已关闭租约，回收排队期间也不能丢失真实用户接管。仅主线程调用。 */
    fun canHandoff(lease: HostAgentSession.Lease): Boolean = ownershipGeneration == lease.generation &&
        control.ownsWindow(lease.generation, windowSerial) && !control.awaitingNavigation(lease.generation, now()) &&
        ownedActivity(lease, readOnly = false) != null

    fun releaseTask(lease: HostAgentSession.Lease): Boolean {
        if (ownershipGeneration != lease.generation) return false
        ownershipGeneration = null
        control.revoke(lease.generation)
        val released = detachCallback()
        removeNavigationObserver()
        removeStop()
        return released
    }

    /** 必须在 BLRouter.open 之前调用；仅租借一次短导航窗口，不允许模型从任意前台页重新接管。 */
    fun prepareNavigation(lease: HostAgentSession.Lease, query: String?, video: String?): Activity {
        val current = requireFocusedTaskContext(lease)
        val destination = if (query != null) HostAgentWindowPolicy.Target("search", query)
        else HostAgentWindowPolicy.Target("video", video ?: throw HostAgentFailure("invalid_navigation"))
        val token = control.expectNavigation(lease.generation, windowSerial, destination, now())
            ?: throw HostAgentFailure("navigation_pending")
        session.expectedPage(lease, query, video, now())
        observeNavigation(current, lease)
        main.post { runCatching { reconcileNavigation(lease) }.onFailure { stopTask(lease) } }
        main.postDelayed({
            if (control.expireNavigation(lease.generation, token, now())) {
                removeNavigationObserver()
                stopTask(lease)
            }
        }, HostAgentWindowPolicy.NAVIGATION_MS)
        return current
    }

    fun navigationFailed(lease: HostAgentSession.Lease) { stopTask(lease) }

    fun requireTaskContext(lease: HostAgentSession.Lease): Activity {
        val current = ownedActivity(lease, readOnly = true) ?: throw HostAgentFailure("host_not_foreground")
        return requireObservedContext(current, lease)
    }

    private fun requireFocusedTaskContext(lease: HostAgentSession.Lease): Activity {
        val current = ownedActivity(lease, readOnly = false) ?: throw HostAgentFailure("host_not_foreground")
        return requireObservedContext(current, lease)
    }

    private fun requireObservedContext(current: Activity, lease: HostAgentSession.Lease): Activity {
        when (observeContext(current, lease)) {
            HostAgentWindowPolicy.Observation.READY -> return current
            HostAgentWindowPolicy.Observation.NAVIGATING -> throw HostAgentFailure("navigation_pending")
            HostAgentWindowPolicy.Observation.REVOKED -> throw HostAgentFailure("task_context_lost")
        }
    }

    private fun observeContext(current: Activity, lease: HostAgentSession.Lease): HostAgentWindowPolicy.Observation {
        val callback = ownedCallback.get()
        if (!session.isActive(lease, now()) || callback?.lease?.generation != lease.generation ||
            ownedWindow.get() !== current.window || current.window.callback !== callback) {
            stopTask(lease)
            return HostAgentWindowPolicy.Observation.REVOKED
        }
        val observed = page(current, lease)
        val wasNavigating = control.awaitingNavigation(lease.generation, now())
        return control.observe(lease.generation, windowSerial, observed.kind, observed.identity, now()).also {
            if (wasNavigating && it == HostAgentWindowPolicy.Observation.READY) pageReadyElapsed = now()
            if (it == HostAgentWindowPolicy.Observation.REVOKED) stopTask(lease)
        }
    }

    private fun observeNavigation(activity: Activity, lease: HostAgentSession.Lease) {
        removeNavigationObserver()
        val observer = activity.window.decorView.viewTreeObserver
        val listener = ViewTreeObserver.OnPreDrawListener {
            runCatching { reconcileNavigation(lease) }.onFailure { stopTask(lease) }
            true
        }
        navigationObserver = WeakReference(observer)
        navigationListener = listener
        observer.addOnPreDrawListener(listener)
    }

    private fun reconcileNavigation(lease: HostAgentSession.Lease) {
        if (!session.isActive(lease, now()) || !control.tracks(lease.generation)) return
        val current = activity() ?: return
        if (observeContext(current, lease) != HostAgentWindowPolicy.Observation.NAVIGATING) removeNavigationObserver()
    }

    private fun removeNavigationObserver() {
        val listener = navigationListener
        val observer = navigationObserver.get()
        if (listener != null && observer?.isAlive == true) runCatching { observer.removeOnPreDrawListener(listener) }
        navigationListener = null
        navigationObserver.clear()
    }

    private fun installCallback(activity: Activity, lease: HostAgentSession.Lease) {
        detachCallback()
        val window = activity.window ?: throw HostAgentFailure("host_window_unavailable")
        val original = window.callback ?: throw HostAgentFailure("host_callback_unavailable")
        val callback = TaskCallback(original, lease)
        window.callback = callback
        ownedWindow = WeakReference(window)
        ownedCallback = WeakReference(callback)
    }

    private fun detachCallback(): Boolean {
        val callback = ownedCallback.get()
        val window = ownedWindow.get()
        // 宿主后续装入的包装器不属于我们，绝不用旧 callback 覆盖它。
        val released = if (callback == null || window == null) true
            else if (window.callback !== callback) false
            else runCatching { window.callback = callback.original; window.callback !== callback }.getOrDefault(false)
        ownedCallback.clear()
        ownedWindow.clear()
        return released
    }

    private fun stopTask(lease: HostAgentSession.Lease) {
        control.revoke(lease.generation)
        session.cancel(lease, now())
        onStop(lease)
    }

    /** 只覆盖用户接管入口，其余所有方法由 Kotlin 委托原样传给宿主 callback。 */
    private inner class TaskCallback(val original: Window.Callback, val lease: HostAgentSession.Lease) : Window.Callback by original {
        private fun takeOver() {
            if (ownedCallback.get() === this && ownershipGeneration == lease.generation) runCatching { stopTask(lease) }
        }
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) takeOver()
            return original.dispatchTouchEvent(event)
        }
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN) takeOver()
            return original.dispatchKeyEvent(event)
        }
        override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_SCROLL || event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS) takeOver()
            return original.dispatchGenericMotionEvent(event)
        }
        override fun onWindowFocusChanged(hasFocus: Boolean) {
            if (!hasFocus && ownedCallback.get() === this && !control.awaitingNavigation(lease.generation, now()) &&
                ownedActivity(lease, readOnly = true) == null) takeOver()
            original.onWindowFocusChanged(hasFocus)
        }
    }

    // 此 View 注入宿主窗口，不能用模块资源 ID 向宿主 Resources 查询；不为停止按钮新增跨进程资源 I/O。
    @SuppressLint("SetTextI18n")
    fun showStop(lease: HostAgentSession.Lease) {
        removeStop()
        if (!session.isActive(lease, now())) return
        val activity = resumed.get()?.takeIf { !it.isFinishing && !it.isDestroyed } ?: return
        val decor = activity.window?.decorView as? FrameLayout ?: return
        val density = activity.resources.displayMetrics.density
        val button = Button(activity).apply {
            text = "停止 AI 任务"
            contentDescription = "停止当前 AI 任务"
            textSize = 12f
            isAllCaps = false
            setOnClickListener { stopTask(lease) }
        }
        decor.addView(button, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.BOTTOM).apply {
            marginEnd = (8 * density).toInt()
            bottomMargin = (88 * density).toInt()
        })
        stopButton = WeakReference(button)
    }

    fun removeStop() {
        runCatching { stopButton.get()?.removeSelf() }
        stopButton.clear()
    }

    /** Intent 身份仅证明目标 Activity 的启动参数，不据此宣称视频已播放或页面正文已完成渲染。 */
    fun state(lease: HostAgentSession.Lease): JSONObject {
        val current = ownedActivity(lease, readOnly = true) ?: return JSONObject().put("foreground", false).put("page", "unknown")
        val observation = observeContext(current, lease)
        return page(current, lease).json().put("foreground", current.hasWindowFocus())
            .put("native_read_context", observation == HostAgentWindowPolicy.Observation.READY)
            .put("task_page", observation == HostAgentWindowPolicy.Observation.READY && control.isTaskPage(lease.generation))
            .put("navigation_pending", observation == HostAgentWindowPolicy.Observation.NAVIGATING)
            .put("interaction_guard", observation != HostAgentWindowPolicy.Observation.REVOKED)
            .put("identity_source", "activity_intent").put("rendered_content_verified", false)
    }

    private data class Page(val kind: String, val identity: String?, val activityName: String, val taskPage: Boolean) {
        fun json() = JSONObject().put("page", kind).put("activity", activityName)
            .put("task_page", taskPage).put(if (kind == "search") "query" else "video_id", identity ?: JSONObject.NULL)
    }

    private fun page(activity: Activity, lease: HostAgentSession.Lease): Page {
        val names = generateSequence(activity.javaClass as Class<*>?) { it.superclass }.take(16).map { it.name }.toSet()
        val kind = when {
            names.any { it in SEARCH_ACTIVITIES } -> "search"
            names.any { it in VIDEO_ACTIVITIES } -> "video"
            else -> "unknown"
        }
        val intent = activity.intent
        val identity = runCatching {
            when (kind) {
                "search" -> {
                    val data = intent?.data
                    val fromData = if (data?.scheme == "bilibili" && data.host in setOf("search", "search3"))
                        data.getQueryParameters("keyword").distinct().singleOrNull() else null
                    val extra = intent?.getStringExtra("keyword")
                    if (fromData != null && extra != null && fromData != extra) null
                    else HostAgentNavigationPolicy.searchQuery(fromData ?: extra)
                }
                "video" -> {
                    val identities = buildList {
                        HostAgentNavigationPolicy.videoFromUri(intent?.dataString)?.let(::add)
                        for (key in listOf("aid", "avid", "bvid")) {
                            @Suppress("DEPRECATION")
                            val value = intent?.extras?.get(key)
                            if (value is String || value is Number) HostAgentNavigationPolicy.videoId(value.toString())?.let(::add)
                        }
                    }.distinct()
                    if (identities.groupBy { it.startsWith("av") }.any { it.value.size > 1 }) null
                    else identities.firstOrNull { session.matchesPage(lease, kind, it, now()) } ?: identities.firstOrNull()
                }
                else -> null
            }
        }.getOrNull()
        return Page(kind, identity, activity.javaClass.name.take(200), session.matchesPage(lease, kind, identity, now()))
    }

    private data class Protection(val password: Boolean, val editing: Boolean, val complete: Boolean)

    private fun inspectViews(root: View): Protection {
        val stack = ArrayDeque<View>()
        stack.add(root)
        var visited = 0
        var editing = false
        while (stack.isNotEmpty()) {
            if (++visited > 4096) return Protection(false, editing, false)
            val view = stack.removeLast()
            if (!view.isShown) continue
            // Web/Compose 的密码语义不保证表现为 TextView；不能把“未看见”当成已排除密码。
            if (view is android.webkit.WebView || view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") {
                return Protection(false, editing, false)
            }
            if (view.isFocused && view.onCheckIsTextEditor()) editing = true
            if (view is TextView) {
                val type = view.inputType
                val inputClass = type and InputType.TYPE_MASK_CLASS
                val variation = type and InputType.TYPE_MASK_VARIATION
                val password = view.transformationMethod is PasswordTransformationMethod ||
                    (inputClass == InputType.TYPE_CLASS_TEXT && variation in PASSWORD_VARIATIONS) ||
                    (inputClass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
                if (password) return Protection(true, editing, true)
            }
            if (view is ViewGroup) {
                if (stack.size + view.childCount > 4096) return Protection(false, editing, false)
                for (index in 0 until view.childCount) stack.add(view.child(index))
            }
        }
        return Protection(false, editing, true)
    }

    private fun refusal(activity: Activity, lease: HostAgentSession.Lease): String? {
        val decor = activity.window?.decorView ?: return "screen_unavailable"
        val observation = observeContext(activity, lease)
        val protection = inspectViews(decor)
        return HostAgentScreenPolicy.refusal(session.allowVision(lease, now()), this.activity() === activity,
            page(activity, lease).taskPage && observation == HostAgentWindowPolicy.Observation.READY && control.isTaskPage(lease.generation),
            activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0,
            protection.password, protection.editing, protection.complete)
    }

    /** 在后台等待 PixelCopy；超时后由迟到回调回收 Bitmap，不在仍被原生写入时提前 recycle。 */
    fun capture(lease: HostAgentSession.Lease): JSONObject {
        awaitMainCondition(lease, 1200L, "screen_not_ready") {
            val current = activity()
            if (current == null) {
                if (!control.awaitingNavigation(lease.generation, now())) throw HostAgentFailure("host_not_foreground")
                false
            } else when (observeContext(current, lease)) {
                HostAgentWindowPolicy.Observation.REVOKED -> throw HostAgentFailure("task_context_lost")
                HostAgentWindowPolicy.Observation.NAVIGATING -> false
                HostAgentWindowPolicy.Observation.READY -> {
                    val decor = current.window.decorView
                    decor.isLaidOut && decor.width > 0 && decor.height > 0 && now() - pageReadyElapsed >= 150L
                }
            }
        }
        val future = CompletableFuture<Capture>()
        main.post {
            if (future.isDone) return@post
            try {
                if (!session.isActive(lease, now())) throw HostAgentFailure("task_inactive")
                val activity = activity() ?: throw HostAgentFailure("host_not_foreground")
                refusal(activity, lease)?.let { throw HostAgentFailure(it) }
                val before = page(activity, lease)
                val decor = activity.window.decorView
                if (decor.width <= 0 || decor.height <= 0) throw HostAgentFailure("screen_not_laid_out")
                val scale = minOf(1.0, 640.0 / maxOf(decor.width, decor.height))
                val bitmap = createBitmap((decor.width * scale).toInt().coerceAtLeast(1),
                    (decor.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                try {
                    PixelCopy.request(activity.window, Rect(0, 0, decor.width, decor.height), bitmap, { result ->
                        try {
                            val after = page(activity, lease)
                            if (result != PixelCopy.SUCCESS || !session.isActive(lease, now()) ||
                                before != after || refusal(activity, lease) != null) {
                                bitmap.recycle()
                                future.completeExceptionally(HostAgentFailure("screen_changed_or_unavailable"))
                            } else if (!future.complete(Capture(bitmap, after.json(), now()))) bitmap.recycle()
                        } catch (failure: Throwable) {
                            bitmap.recycle()
                            future.completeExceptionally(failure as? HostAgentFailure ?: HostAgentFailure("screen_unavailable"))
                        }
                    }, main)
                } catch (failure: Throwable) { bitmap.recycle(); throw failure }
            } catch (failure: Throwable) { future.completeExceptionally(failure) }
        }
        val capture = try {
            future.get(minOf(3000L, (lease.deadline - now()).coerceAtLeast(1)), TimeUnit.MILLISECONDS)
        } catch (failure: Exception) {
            // complete/cancel 竞争由 CompletableFuture 处理；若已完成则自行回收，避免无所有者 Bitmap。
            if (!future.cancel(false) && !future.isCompletedExceptionally) future.getNow(null)?.bitmap?.recycle()
            val cause = failure.cause
            throw if (cause is HostAgentFailure) cause else HostAgentFailure("screen_timeout")
        }
        return try {
            if (!session.isActive(lease, now())) throw HostAgentFailure("task_inactive")
            val bytes = sequenceOf(75, 55, 35).map { quality ->
                ByteArrayOutputStream().use { stream ->
                    check(capture.bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream))
                    stream.toByteArray()
                }
            }.firstOrNull { it.size <= MAX_JPEG_BYTES } ?: throw HostAgentFailure("screen_too_large")
            JSONObject().put("image_data_url", "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP))
                .put("page", capture.page).put("capture_elapsed", capture.elapsed)
                .put("width", capture.bitmap.width).put("height", capture.bitmap.height)
        } finally { capture.bitmap.recycle() }
    }

    private data class Capture(val bitmap: Bitmap, val page: JSONObject, val elapsed: Long)
    private fun now() = SystemClock.elapsedRealtime()

    companion object {
        // Binder 的 String 是 UTF-16；120 KiB JPEG -> 160 KiB base64 -> 320 KiB Parcel，留出元数据空间。
        const val MAX_JPEG_BYTES = 120 * 1024
        private val PASSWORD_VARIATIONS = setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
        private val SEARCH_ACTIVITIES = setOf("com.bilibili.search2.main.BiliMainSearchActivity",
            "com.bilibili.search2.main.BiliMainSearchActivityForInnerJump")
        private val VIDEO_ACTIVITIES = setOf("com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity",
            "com.bilibili.video.videodetail.VideoDetailsActivity")
    }
}

internal class HostAgentRouter private constructor(
    private val builder: java.lang.reflect.Constructor<*>,
    private val build: java.lang.reflect.Method,
    private val route: java.lang.reflect.Method
) {
    fun open(activity: Activity, canonicalUri: String) {
        val request = build.invoke(builder.newInstance(canonicalUri.toUri()))
        route.invoke(null, request, activity)
    }

    companion object {
        fun resolve(loader: ClassLoader): HostAgentRouter? = runCatching {
            val request = KavaMemberLookup.classOrNull(loader, "com.bilibili.lib.blrouter.RouteRequest") ?: return null
            val builder = KavaMemberLookup.classOrNull(loader, "com.bilibili.lib.blrouter.RouteRequest\$Builder") ?: return null
            val router = KavaMemberLookup.classOrNull(loader, "com.bilibili.lib.blrouter.BLRouter") ?: return null
            HostAgentRouter(KavaMemberLookup.constructorOrNull(builder, classOf<Uri>()) ?: return null,
                KavaMemberLookup.methodOrNull(builder, "build")?.takeIf { it.returnType == request } ?: return null,
                KavaMemberLookup.methodOrNull(router, "routeTo", request, classOf<Context>())
                    ?.takeIf { it.isStatic } ?: return null)
        }.getOrNull()
    }
}
