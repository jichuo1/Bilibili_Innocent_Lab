package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import android.app.AlertDialog
import android.content.Context
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.InjectedUiLocale
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.highcapable.betterandroid.ui.extension.component.hostActivity
import com.highcapable.kavaref.extension.isStatic
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reuses the host's own single-action unfollow confirmation for a video's locally followed author.
 * The host group menu can be supplied once its session-bound page and route guards are installed.
 *
 * The two verified host shapes contain exactly three Context-only methods: q/w/y (private, 8.84–8.96)
 * and a/b/c (public final, 8.97–9.17). HostLab matrix 2d33f3dd0ded450fbc7061b00e9def12 identifies
 * the middle method as the confirmation and the last as the confirmed network action. Never guess an
 * ambiguous shape. The confirmed action is intercepted before it can access an account or network.
 * If the host shape or hook is unavailable, a local confirmation preserves the same session checks.
 */
internal class GuestHostFollowConfirmation(
    private val source: Source,
    private val isGuest: () -> Boolean,
    private val names: Names = Names()
) {
    constructor(library: GuestLibraryRuntime, isGuest: () -> Boolean) : this(
        object : Source {
            override fun token(): Any? = library.currentToken()
            override fun valid(token: Any): Boolean = token is GuestLibraryRuntime.Token && library.accepts(token)
            override fun read(token: Any, mid: Long, done: (Boolean) -> Unit) {
                library.query(token as GuestLibraryRuntime.Token) { state ->
                    done(state.collections.follows.any { it.mid == mid })
                }
            }
            override fun set(token: Any, mid: Long, following: Boolean, announce: Boolean, done: () -> Unit) {
                library.setHostFollow(token as GuestLibraryRuntime.Token, mid, following, announce, done)
            }
            override fun fallback(context: Any, confirm: () -> Unit): Boolean {
                val activity = (context as? Context)?.hostActivity?.takeUnless { it.isFinishing || it.isDestroyed } ?: return false
                val (message, action) = when (InjectedUiLocale.resolveEffectiveTag(activity)) {
                    "zh-CN" -> "确认取消关注该 UP 主吗？" to "取消关注"
                    "zh-Hant" -> "確認取消關注這位創作者嗎？" to "取消關注"
                    else -> "Unfollow this creator?" to "Unfollow"
                }
                return runCatching {
                    AlertDialog.Builder(activity)
                        .setMessage(message)
                        .setPositiveButton(action) { _, _ -> confirm() }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                    true
                }.getOrDefault(false)
            }
            override fun fail(token: Any) = library.failure(token as GuestLibraryRuntime.Token)
        },
        isGuest
    )

    internal interface Source {
        fun token(): Any?
        fun valid(token: Any): Boolean
        fun read(token: Any, mid: Long, done: (Boolean) -> Unit)
        fun set(token: Any, mid: Long, following: Boolean, announce: Boolean, done: () -> Unit)
        fun fallback(context: Any, confirm: () -> Unit): Boolean
        fun fail(token: Any)
    }

    internal interface FollowedMenu {
        fun open(target: Any, context: Any, uid: Long, token: Any, expected: WeakReference<Any>,
                 announce: Boolean, done: (Boolean) -> Unit): Boolean
    }

    internal var followedMenu: FollowedMenu? = null
    internal data class Names(
        val helper: String = "com.bilibili.relation.utils.FollowFlowHelper",
        val callbackBase: String = GuestVideoOwnerCallbacks.CALLBACK_BASE,
        val ownerCallbacks: List<String> = listOf(
            "com.bilibili.ship.theseus.united.page.intro.module.owner.OwnerService\$a",
            "com.bilibili.ship.theseus.united.page.intro.module.owner.OwnerService\$b"
        ),
        val context: Class<*> = Context::class.java,
        val ownerService: String = GuestVideoOwnerCallbacks.OWNER_CLASS
    )

    private data class Pending(
        val token: Any,
        val mid: Long,
        val callback: WeakReference<Any>,
        val done: (Boolean) -> Unit,
        val announce: Boolean,
        var claimed: Boolean = false
    )

    private val pending = WeakHashMap<Any, Pending>()
    private lateinit var mid: java.lang.reflect.Field
    private lateinit var callback: java.lang.reflect.Field
    private var confirmation: Method? = null
    private lateinit var ownerCallbacks: GuestVideoOwnerCallbacks
    private lateinit var env: HookEnvironment
    private val reportedShown = AtomicBoolean()
    private val reportedConfirmed = AtomicBoolean()
    private val reportedFailure = AtomicBoolean()

    fun install(environment: HookEnvironment) {
        env = environment
        val loader = requireNotNull(environment.classLoader)
        val helper = requireNotNull(Lookup.classOrNull(loader, names.helper))
        val callbackBase = requireNotNull(Lookup.classOrNull(loader, names.callbackBase))
        mid = requireNotNull(Lookup.fieldOrNull(helper, "c")).also { require(!it.isStatic && it.type == Long::class.javaPrimitiveType) }
        callback = requireNotNull(Lookup.fieldOrNull(helper, "i")).also { require(!it.isStatic && it.type.isAssignableFrom(callbackBase)) }
        val knownCallbacks = names.ownerCallbacks.mapNotNull { Lookup.classOrNull(loader, it) }
            .filter { callbackBase.isAssignableFrom(it) }
        if (knownCallbacks.isEmpty()) {
            requireNotNull(Lookup.classOrNull(loader, names.ownerService)) { "Video owner service missing" }
            environment.reportStatus("guest_video_follow_callback_match", "owner-scope")
        }
        ownerCallbacks = GuestVideoOwnerCallbacks(knownCallbacks, callbackBase, names.ownerService)
        val methods = resolveMethods(helper, names.context)
        if (methods == null) {
            environment.reportStatus("guest_video_follow_confirm_mode", "fallback")
            return
        }
        val confirmed = methods.second
        val installed = runCatching {
            environment.registrar.exact("guest.video.follow.confirmed", helper, confirmed.name, *confirmed.parameterTypes) {
                before {
                    val target = instance ?: return@before
                    val claim = synchronized(pending) {
                        pending[target]?.let { value ->
                            val first = !value.claimed
                            value.claimed = true
                            value to first
                        }
                    } ?: return@before
                    // A repeated host callback must never fall through to the network.
                    result = null
                    val (request, first) = claim
                    if (!first || !bound(target, request.mid, request.callback, request.token)) return@before
                    source.set(request.token, request.mid, false, request.announce) {
                        if (bound(target, request.mid, request.callback, request.token)) {
                            request.done(false)
                            if (reportedConfirmed.compareAndSet(false, true)) environment.reportStatus("guest_video_follow_confirmed", "local")
                        }
                    }
                }
            }
        }.isSuccess
        if (installed) confirmation = methods.first
        environment.reportStatus("guest_video_follow_confirm_mode", if (installed) "host" else "fallback")
    }

    fun canHandle(target: Any): Boolean = runCatching {
        val owner = callback.get(target) ?: return@runCatching false
        ownerCallbacks.matches(owner)
    }.getOrDefault(false)

    fun click(target: Any, context: Any, uid: Long, announce: Boolean, done: (Boolean) -> Unit) {
        val token = source.token() ?: return
        val expected = callback.get(target) ?: return
        val weakTarget = WeakReference(target)
        val weakCallback = WeakReference(expected)
        val weakContext = WeakReference(context)
        source.read(token, uid) { following ->
            val current = weakTarget.get() ?: return@read
            if (!bound(current, uid, weakCallback, token)) return@read
            if (!following) {
                source.set(token, uid, true, announce) {
                    if (bound(current, uid, weakCallback, token)) done(true)
                }
                return@read
            }
            val hostContext = weakContext.get() ?: return@read
            val menu = followedMenu
            if (menu != null) {
                val handled = try { menu.open(current, hostContext, uid, token, weakCallback, announce, done) }
                    catch (_: Exception) { source.fail(token); true }
                if (handled) return@read
            }
            showConfirmation(current, hostContext, Pending(token, uid, weakCallback, done, announce))
        }
    }

    /** The menu must retain the token from its opening, not capture the session current at the later click. */
    internal fun confirmFromMenu(target: Any, context: Any, uid: Long, token: Any, expected: WeakReference<Any>,
                                 announce: Boolean, done: (Boolean) -> Unit) {
        if (!bound(target, uid, expected, token)) return
        showConfirmation(target, context, Pending(token, uid, expected, done, announce))
    }

    private fun showConfirmation(target: Any, context: Any, request: Pending) {
        val nativeConfirmation = confirmation
        if (nativeConfirmation == null) {
            showFallback(target, context, request)
            return
        }
        synchronized(pending) { pending[target] = request }
        runCatching { nativeConfirmation.invoke(target, context) }.onSuccess {
            if (reportedShown.compareAndSet(false, true)) env.reportStatus("guest_video_follow_confirmation", "shown")
        }.onFailure {
            // A throwing host method might already have shown its dialog. Keep the guard to block any late host action.
            synchronized(pending) { if (pending[target] === request) request.claimed = true }
            if (reportedFailure.compareAndSet(false, true)) env.reportStatus("guest_video_follow_confirmation_error", "fallback")
            showFallback(target, context, request)
        }
    }
    private fun showFallback(target: Any, context: Any, request: Pending) {
        val weakTarget = WeakReference(target)
        val claimed = AtomicBoolean()
        val shown = runCatching {
            source.fallback(context) fallbackConfirm@{
                if (!claimed.compareAndSet(false, true)) return@fallbackConfirm
                val current = weakTarget.get() ?: return@fallbackConfirm
                if (!bound(current, request.mid, request.callback, request.token)) return@fallbackConfirm
                source.set(request.token, request.mid, false, request.announce) {
                    if (bound(current, request.mid, request.callback, request.token)) request.done(false)
                }
            }
        }.getOrDefault(false)
        if (shown) {
            if (reportedShown.compareAndSet(false, true)) env.reportStatus("guest_video_follow_confirmation", "fallback")
        } else {
            if (reportedFailure.compareAndSet(false, true)) env.reportStatus("guest_video_follow_confirmation_error", "unavailable")
            source.fail(request.token)
        }
    }

    internal fun bound(target: Any, uid: Long, expected: WeakReference<Any>, token: Any): Boolean = runCatching {
        isGuest() && source.valid(token) && mid.getLong(target) == uid &&
            expected.get()?.let { callback.get(target) === it } == true
    }.getOrDefault(false)

    internal companion object {
        fun resolveMethods(helper: Class<*>, context: Class<*>): Pair<Method, Method>? {
            val candidates = Lookup.declaredMethods(helper, makeAccessible = true) {
                !it.isStatic && it.returnType == Void.TYPE && it.parameterTypes.contentEquals(arrayOf(context))
            }
            if (candidates.size != 3) return null
            val byName = candidates.associateBy { it.name }
            return when {
                byName.keys == setOf("q", "w", "y") && candidates.all { Modifier.isPrivate(it.modifiers) } ->
                    requireNotNull(byName["w"]) to requireNotNull(byName["y"])
                byName.keys == setOf("a", "b", "c") &&
                    candidates.all { Modifier.isPublic(it.modifiers) && Modifier.isFinal(it.modifiers) } ->
                    requireNotNull(byName["b"]) to requireNotNull(byName["c"])
                else -> null
            }
        }
    }
}
