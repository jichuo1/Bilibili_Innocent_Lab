package com.Bilibili_Innocent_Lab.xposedmodule.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Base64
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** 只在用户开启且显式启动任务后操作宿主。没有跨任务节点缓存，不保存节点、截图或输入文本。 */
class AgentAccessibilityService : AccessibilityService() {
    internal enum class Foreground { HOST, MODULE, OTHER }
    private data class Target(val path: String, val label: String, val context: String, val bounds: Rect,
                              val clickable: Boolean, val scrollable: Boolean, val editable: Boolean, val password: Boolean,
                              val enabled: Boolean, val signature: String, val protected: Boolean, val window: Int)
    private data class Tree(val window: Int, val bounds: Rect, val targets: List<Target>, val complete: Boolean,
                            val privateInput: Boolean, val text: String)
    private data class Snapshot(val id: String, val task: String, val at: Long, val tree: Tree)
    private val main = Handler(Looper.getMainLooper())
    private var snapshot: Snapshot? = null
    private var visualSnapshot: String? = null
    private var visualAt = 0L

    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不扫描事件流；只有实际工具调用才扫描当前窗口。
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) visualSnapshot = null
    }
    override fun onInterrupt() { revoke() }
    override fun onUnbind(intent: android.content.Intent?): Boolean { revoke(); return super.onUnbind(intent) }
    override fun onDestroy() { revoke(); super.onDestroy() }
    private fun revoke() {
        if (instance === this) instance = null
        snapshot = null; visualSnapshot = null
        if (AgentController.usesAccessibility()) AgentController.cancel(this, "accessibility_disconnected")
    }

    private fun authorized(task: String): Boolean = instance === this && AgentController.actionAuthorized(task)
    private fun ready(task: String) {
        check(authorized(task)) { "task_inactive" }
        check(getSystemService(PowerManager::class.java)?.isInteractive == true &&
            getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false) { "device_locked" }
    }
    private fun hostRoot(): AccessibilityNodeInfo {
        val window = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive && it.isFocused }
            ?: throw IllegalStateException("host_not_foreground")
        val root = window.root ?: throw IllegalStateException("ui_unavailable")
        if (root.packageName?.toString() != AgentWire.TARGET_PACKAGE) {
            release(root); throw IllegalStateException("host_not_foreground")
        }
        return root
    }

    private fun scan(): Tree {
        val root = hostRoot()
        val bounds = Rect().also(root::getBoundsInScreen)
        val targets = mutableListOf<Target>()
        var visited = 0
        var complete = true
        var privateInput = false
        val contextText = StringBuilder()
        fun visit(node: AccessibilityNodeInfo, path: String, ancestors: String, depth: Int) {
            if (++visited > 384 || depth > 24 || targets.size >= 160) { complete = false; return }
            if (!node.isVisibleToUser) return
            val rect = Rect().also(node::getBoundsInScreen)
            val type = node.inputType
            val variation = type and android.text.InputType.TYPE_MASK_VARIATION
            val credential = node.isPassword || type and android.text.InputType.TYPE_MASK_CLASS == android.text.InputType.TYPE_CLASS_TEXT &&
                variation in setOf(android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD, android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                    android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) ||
                type and android.text.InputType.TYPE_MASK_CLASS == android.text.InputType.TYPE_CLASS_NUMBER &&
                variation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            val raw = if (credential) "[受保护输入]" else listOf(node.text, node.contentDescription,
                node.hintText).filterNotNull().joinToString(" ").filterNot { it.isISOControl() }.take(120)
            val context = (ancestors.takeLast(180) + " " + raw).trim()
            val privateField = credential || AgentUiPolicy.credentialText(raw) || node.isEditable && AgentUiPolicy.sensitiveControl(context)
            privateInput = privateInput || privateField
            val label = if (privateField) "[受保护输入]" else raw
            // 文本输入只返回 hint/描述；用户已经输入的内容不发送给模型。
            val publicLabel = AgentUiPolicy.publicText(if (privateField) "[受保护输入]" else if (node.isEditable)
                (node.hintText ?: node.contentDescription)?.toString().orEmpty() else label)
            val renderer = node.className?.toString().orEmpty()
            if (node.childCount == 0 && publicLabel.isBlank() &&
                (renderer.contains("WebView", true) || renderer.contains("ComposeView", true))) complete = false
            if (contextText.length < 6_000) contextText.append(' ').append(publicLabel)
            if (!rect.isEmpty && Rect.intersects(bounds, rect) && (publicLabel.isNotBlank() || node.isClickable || node.isScrollable || node.isEditable)) {
                val signature = listOf(publicLabel, node.viewIdResourceName.orEmpty(), node.className?.toString().orEmpty(),
                    rect.toShortString(), node.isClickable, node.isScrollable, node.isEditable, privateField, node.isEnabled).joinToString("|")
                targets += Target(path, publicLabel, context, rect, node.isClickable, node.isScrollable, node.isEditable,
                    privateField, node.isEnabled, signature, AgentUiPolicy.sensitiveControl(context) || privateField, node.windowId)
            }
            for (i in 0 until node.childCount) {
                if (visited >= 384 || targets.size >= 160) { complete = false; break }
                val child = node.getChild(i)
                if (child == null) { complete = false; continue }
                try { visit(child, "$path.$i", if (node.isEditable) ancestors else context, depth + 1) } finally { release(child) }
            }
        }
        return try {
            visit(root, "0", "", 0)
            val guarded = targets.map { target ->
                // 可点击父容器也要检查静态子文案，不能用空 label 绕过付款等按钮保护。
                val descendants = targets.filter { it.path.startsWith(target.path + ".") }
                val childLabels = descendants.map { it.label }.filter { it.isNotBlank() }.take(3).joinToString(" ").take(80)
                val risky = AgentUiPolicy.protectedControl(target.context, descendants.map { it.context },
                    target.password || descendants.any { it.password })
                if (target.clickable) target.copy(label = target.label.ifBlank { childLabels },
                    signature = target.signature + "|" + childLabels,
                    protected = target.protected || risky || AgentUiPolicy.sensitiveConfirmation(target.label.ifBlank { childLabels }, contextText.toString()))
                else target
            }
            Tree(root.windowId, bounds, guarded, complete, privateInput, contextText.toString())
        }
        finally { release(root) }
    }

    private fun observe(task: String, offset: Int = 0): JSONObject {
        ready(task)
        val tree = scan()
        val current = Snapshot(UUID.randomUUID().toString(), task, SystemClock.elapsedRealtime(), tree)
        snapshot = current; visualSnapshot = null
        val nodes = JSONArray()
        val sorted = tree.targets.sortedByDescending { it.clickable || it.scrollable || it.editable }
        val visible = sorted.drop(offset).take(64)
        visible.forEach { target ->
            val denied = target.protected
            nodes.put(JSONObject().put("node_id", target.path).put("label", target.label)
                .put("clickable", target.clickable && AgentUiPolicy.mayClick(target.label, denied)).put("scrollable", target.scrollable)
                .put("editable", target.editable && !denied).put("enabled", target.enabled)
                .put("protected", denied).put("bounds", JSONArray(listOf(target.bounds.left, target.bounds.top, target.bounds.right, target.bounds.bottom))))
        }
        return ok(JSONObject().put("backend", "accessibility").put("snapshot_id", current.id)
            .put("window_id", tree.window).put("complete", tree.complete).put("nodes", nodes)
            .put("offset", offset).put("total_nodes", sorted.size)
            .put("next_offset", if (offset + visible.size < sorted.size) offset + visible.size else JSONObject.NULL)
            .put("coordinate_space", "0..1000 relative to host window").put("window_bounds",
                JSONArray(listOf(tree.bounds.left, tree.bounds.top, tree.bounds.right, tree.bounds.bottom))))
    }

    private fun current(task: String, args: JSONObject, allowProtectedBack: Boolean = false): Pair<Snapshot, Tree> {
        ready(task)
        val old = snapshot ?: throw IllegalStateException("ui_snapshot_required")
        check(old.task == task && old.id == args.optString("snapshot_id")) { "ui_snapshot_stale" }
        val tree = scan()
        check(AgentUiPolicy.snapshotCurrent(old.tree.window, tree.window, SystemClock.elapsedRealtime() - old.at, true) &&
            old.tree.bounds == tree.bounds) { "ui_snapshot_stale" }
        if (!allowProtectedBack) check(tree.complete && !tree.privateInput) { "ui_protected_or_incomplete" }
        return old to tree
    }
    private fun target(task: String, args: JSONObject): Pair<Target, Tree> {
        val (old, tree) = current(task, args)
        val previous = old.tree.targets.firstOrNull { it.path == args.optString("node_id") } ?: throw IllegalStateException("ui_target_missing")
        val now = tree.targets.firstOrNull { it.path == previous.path }
        check(now != null && now.signature == previous.signature) { "ui_snapshot_stale" }
        check(now.enabled && !now.protected) { "sensitive_action_blocked" }
        return now to tree
    }
    private fun withNode(target: Target, admit: () -> Unit, action: (AccessibilityNodeInfo) -> Boolean): Boolean {
        var node = hostRoot()
        try {
            target.path.split('.').drop(1).forEach { index ->
                val child = node.getChild(index.toInt()) ?: throw IllegalStateException("ui_snapshot_stale")
                release(node); node = child
            }
            val focused = hostRoot()
            try { check(focused.windowId == target.window && node.windowId == target.window) { "ui_snapshot_stale" } }
            finally { release(focused) }
            check(!node.isPassword && !AgentUiPolicy.sensitiveControl(listOf(node.hintText, node.contentDescription).filterNotNull().joinToString(" "))) {
                "sensitive_action_blocked"
            }
            admit()
            return action(node)
        } finally { release(node) }
    }
    private fun changed(task: String, admit: () -> Unit, result: CompletableFuture<JSONObject>) {
        snapshot = null; visualSnapshot = null
        val dispatched = JSONObject().put("action", "dispatched").put("verification_required", true)
        fun observeAfter(attempt: Int) {
            if (result.isDone) return
            try {
                admit()
                val data = observe(task).getJSONObject("data")
                admit()
                val verified = data.optBoolean("complete") && data.optJSONArray("nodes")?.length()?.let { it > 0 } == true
                result.complete(ok(data.put("action", "dispatched").put("observation_after_action", verified)
                    .put("verification_required", !verified)))
            } catch (failure: Exception) {
                val code = reason(failure)
                if (attempt < 3 && code in setOf("host_not_foreground", "ui_unavailable") && authorized(task))
                    main.postDelayed({ observeAfter(attempt + 1) }, 150)
                else result.complete(if (authorized(task)) ok(dispatched.put("observation_error", code)) else error("task_inactive"))
            }
        }
        // 一次动作后追加有界只读观测，不重放动作，也不等待网络内容完全加载。
        main.postDelayed({ observeAfter(1) }, 180)
    }
    private fun gesture(task: String, tree: Tree, path: Path, duration: Long, admit: () -> Unit, result: CompletableFuture<JSONObject>) {
        val focused = hostRoot()
        try { check(focused.windowId == tree.window && Rect().also(focused::getBoundsInScreen) == tree.bounds) { "ui_snapshot_stale" } }
        finally { release(focused) }
        admit()
        val accepted = dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (authorized(task)) changed(task, admit, result) else result.complete(error("task_inactive"))
                }
                override fun onCancelled(gestureDescription: GestureDescription?) { result.complete(error("gesture_cancelled")) }
            }, main)
        if (!accepted) result.complete(error("gesture_unavailable"))
    }

    private fun execute(task: String, operation: String, args: JSONObject, result: CompletableFuture<JSONObject>, expiresAt: Long) {
        fun admit() {
            check(AgentUiPolicy.mayAct(authorized(task), result.isDone, SystemClock.elapsedRealtime(), expiresAt)) { "ui_request_expired" }
            ready(task)
        }
        admit()
        when (operation) {
            "get_host_state", "get_ui_state" -> result.complete(observe(task, args.optString("offset").toIntOrNull() ?: 0))
            "click_ui", "input_ui_text" -> {
                val (target, _) = target(task, args)
                val applied = if (operation == "input_ui_text") {
                    check(target.editable && AgentUiPolicy.allowInput(target.context, target.password, args.optString("text"))) { "sensitive_action_blocked" }
                    withNode(target, ::admit) { it.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, args.optString("text"))
                    }) }
                } else {
                    check(target.clickable && AgentUiPolicy.mayClick(target.label, target.protected)) { "ui_target_not_clickable" }
                    withNode(target, ::admit) { it.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                }
                if (applied) changed(task, ::admit, result) else result.complete(error("ui_action_unavailable"))
            }
            "swipe_ui" -> {
                val id = args.optString("node_id")
                val selected = if (id.isNotBlank()) target(task, args) else null
                val tree = selected?.second ?: current(task, args).second
                val direction = args.optString("direction")
                val scrolling = selected?.first ?: tree.targets.filter { it.scrollable && it.enabled && !it.protected }
                    .maxByOrNull { it.bounds.width().toLong() * it.bounds.height() }
                if (scrolling != null) {
                    val target = scrolling
                    val action = when (direction) {
                        "up" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
                        "down" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
                        "left" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
                        else -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
                    }
                    if (target.scrollable && withNode(target, ::admit) { it.performAction(action) }) { changed(task, ::admit, result); return }
                }
                // 手势仅在完整、无敏感字段的宿主窗口内；不接受屏幕绝对坐标。
                check(!AgentUiPolicy.sensitiveControl(tree.text)) { "sensitive_action_blocked" }
                val b = tree.bounds
                val horizontal = direction == "left" || direction == "right"
                val reverse = direction == "down" || direction == "right"
                val x1 = b.left + b.width() * if (horizontal) (if (reverse) .25f else .75f) else .5f
                val y1 = b.top + b.height() * if (!horizontal) (if (reverse) .25f else .75f) else .5f
                val x2 = b.left + b.width() * if (horizontal) (if (reverse) .75f else .25f) else .5f
                val y2 = b.top + b.height() * if (!horizontal) (if (reverse) .75f else .25f) else .5f
                gesture(task, tree, Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, 420, ::admit, result)
            }
            "tap_ui" -> {
                val (old, tree) = current(task, args)
                check(visualSnapshot == old.id && SystemClock.elapsedRealtime() - visualAt in 0..60_000) { "visual_snapshot_required" }
                check(!AgentUiPolicy.sensitiveControl(tree.text) && tree.targets.none { it.editable }) { "sensitive_action_blocked" }
                val x = tree.bounds.left + tree.bounds.width() * args.optString("x").toInt() / 1000f
                val y = tree.bounds.top + tree.bounds.height() * args.optString("y").toInt() / 1000f
                val hits = tree.targets.filter { it.bounds.contains(x.toInt(), y.toInt()) }
                check(hits.none { it.protected } && hits.any { it.label.isNotBlank() && it.bounds.width() < tree.bounds.width() * .9f }) {
                    "opaque_target_unverified"
                }
                check(hits.all { now -> old.tree.targets.any { it.path == now.path && it.signature == now.signature } }) { "ui_snapshot_stale" }
                gesture(task, tree, Path().apply { moveTo(x, y) }, 80, ::admit, result)
            }
            "press_back" -> {
                val tree = current(task, args, allowProtectedBack = true).second
                val focused = hostRoot()
                try { check(focused.windowId == tree.window) { "ui_snapshot_stale" } } finally { release(focused) }
                admit()
                if (performGlobalAction(GLOBAL_ACTION_BACK)) changed(task, ::admit, result) else result.complete(error("ui_action_unavailable"))
            }
            "inspect_screen" -> if (Build.VERSION.SDK_INT >= 34) screenshot(task, result, ::admit)
                else result.complete(error("accessibility_screenshot_requires_android14"))
            else -> result.complete(error("use_ui_tools"))
        }
    }

    @RequiresApi(34)
    private fun screenshot(task: String, result: CompletableFuture<JSONObject>, admit: () -> Unit) {
        val state = observe(task).getJSONObject("data")
        val shot = snapshot ?: throw IllegalStateException("ui_snapshot_required")
        check(shot.tree.complete && !shot.tree.privateInput && !AgentUiPolicy.sensitiveControl(shot.tree.text)) { "screen_protected" }
        // API 34 的窗口截图不会把其它应用、键盘、通知或模块状态提示送入模型。
        admit()
        takeScreenshotOfWindow(shot.tree.window, mainExecutor, object : TakeScreenshotCallback {
            override fun onFailure(errorCode: Int) { result.complete(error("screen_unavailable")) }
            override fun onSuccess(screenshot: ScreenshotResult) {
                val buffer = screenshot.hardwareBuffer
                var bitmap: Bitmap? = null
                var scaled: Bitmap? = null
                try {
                    check(!result.isDone) { "task_inactive" }
                    ready(task)
                    val tree = scan()
                    check(tree.window == shot.tree.window && tree.bounds == shot.tree.bounds && tree.complete &&
                        !tree.privateInput && !AgentUiPolicy.sensitiveControl(tree.text)) { "ui_snapshot_stale" }
                    val hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace) ?: throw IllegalStateException("screen_unavailable")
                    bitmap = try {
                        check(hardware.width.toLong() * hardware.height <= 8_500_000) { "screen_too_large" }
                        hardware.copy(Bitmap.Config.ARGB_8888, false)
                    } finally { hardware.recycle() }
                    val source = bitmap ?: throw IllegalStateException("screen_unavailable")
                    val window = windows.firstOrNull { it.id == tree.window } ?: throw IllegalStateException("ui_snapshot_stale")
                    val frame = Rect().also(window::getBoundsInScreen)
                    check(frame.contains(tree.bounds) && !frame.isEmpty) { "screen_unavailable" }
                    val left = ((tree.bounds.left - frame.left).toLong() * source.width / frame.width()).toInt()
                    val top = ((tree.bounds.top - frame.top).toLong() * source.height / frame.height()).toInt()
                    val right = ((tree.bounds.right - frame.left).toLong() * source.width / frame.width()).toInt()
                    val bottom = ((tree.bounds.bottom - frame.top).toLong() * source.height / frame.height()).toInt()
                    val cropped = Bitmap.createBitmap(source, left, top, maxOf(1, right - left), maxOf(1, bottom - top))
                    val ratio = minOf(1f, 960f / maxOf(cropped.width, cropped.height))
                    scaled = Bitmap.createScaledBitmap(cropped, maxOf(1, (cropped.width * ratio).toInt()), maxOf(1, (cropped.height * ratio).toInt()), true)
                    if (cropped !== scaled && cropped !== source) cropped.recycle()
                    val bytes = ByteArrayOutputStream()
                    for (quality in listOf(70, 50, 30)) {
                        bytes.reset(); scaled.compress(Bitmap.CompressFormat.JPEG, quality, bytes)
                        if (bytes.size() <= 130_000) break
                    }
                    check(bytes.size() <= 130_000) { "screen_too_large" }
                    val image = "data:image/jpeg;base64," + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
                    visualSnapshot = shot.id; visualAt = SystemClock.elapsedRealtime()
                    admit()
                    result.complete(ok(state.put("image_data_url", image).put("capture_elapsed", visualAt)))
                } catch (_: OutOfMemoryError) { result.complete(error("screen_memory_unavailable")) }
                catch (failure: Exception) { result.complete(error(reason(failure))) }
                finally { if (scaled !== bitmap) scaled?.recycle(); bitmap?.recycle(); buffer.close() }
            }
        })
    }

    companion object {
        @Volatile private var instance: AgentAccessibilityService? = null
        internal fun connected(): Boolean = instance != null
        internal fun foreground(): Foreground {
            val service = instance ?: return Foreground.OTHER
            val result = CompletableFuture<Foreground>()
            service.main.post {
                if (result.isDone) return@post
                val value = runCatching {
                    val window = service.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive && it.isFocused }
                    val root = window?.root ?: return@runCatching Foreground.OTHER
                    try { when (root.packageName?.toString()) {
                        AgentWire.TARGET_PACKAGE -> Foreground.HOST
                        service.packageName -> Foreground.MODULE
                        else -> Foreground.OTHER
                    } } finally { release(root) }
                }.getOrDefault(Foreground.OTHER)
                result.complete(value)
            }
            return try { result.get(2, TimeUnit.SECONDS) } catch (_: Exception) { result.cancel(false); Foreground.OTHER }
        }
        internal fun clear(task: String) {
            instance?.let { service -> service.main.post {
                if (service.snapshot?.task == task) { service.snapshot = null; service.visualSnapshot = null }
            } }
        }
        internal fun request(task: String, operation: String, args: JSONObject, cancelled: () -> Boolean): JSONObject {
            val service = instance ?: return error("accessibility_not_connected")
            val result = CompletableFuture<JSONObject>()
            val timeout = if (operation == "inspect_screen") 8_000L else 4_000L
            val expiresAt = SystemClock.elapsedRealtime() + timeout
            service.main.post {
                if (result.isDone || cancelled()) { result.complete(error("task_inactive")); return@post }
                try { service.execute(task, operation, args, result, expiresAt) } catch (failure: Exception) { result.complete(error(reason(failure))) }
            }
            return try { result.get(timeout, TimeUnit.MILLISECONDS) }
            catch (_: Exception) { result.cancel(false); error(if (cancelled()) "task_inactive" else "ui_response_timeout") }
        }
        private fun ok(data: JSONObject): JSONObject = JSONObject().put("ok", true).put("data", data)
        private fun error(reason: String): JSONObject = JSONObject().put("ok", false).put("error", reason)
        private fun reason(failure: Exception): String = failure.message?.takeIf { it.matches(Regex("[a-z0-9_]{1,80}")) } ?: "ui_action_failed"
        @Suppress("DEPRECATION") private fun release(node: AccessibilityNodeInfo) { if (Build.VERSION.SDK_INT < 33) node.recycle() }
    }
}
