package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.highcapable.kavaref.extension.isStatic
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Let a guest add to watch later on 8.97 and later builds through the host's own flow.
 *
 * Every add there (`MainCommonService.addToWatchLater` for an Activity or a Fragment, and `batchAddToWatchLater`) wraps the
 * host's add action, a `Function0` in `tv.danmaku.bili.ui.watchlater.widget`, in `LoginScopeKt.loginRequire(...)`, which
 * runs it only when logged in and otherwise routes to the login page (matrix q64, 26 builds; 9.0.0 decompiled). For a guest
 * this runs that action directly, exactly what the helper does for a logged-in user. The action then posts
 * `/x/v2/history/toview/add` through `BiliCall`, which [GuestNativeListHttpHooks] answers from the local library, and the
 * host shows its own "added" popup. Other callers of the helper (report, appeal, manual login) keep the login gate:
 * only an action class declared directly in that widget package is let through.
 *
 * Install this only where the list short circuit is installed, so a guest add never reaches the network.
 */
internal class GuestWatchLaterLoginScope(private val isGuest: () -> Boolean) {
    fun install(env: HookEnvironment) {
        val loader = requireNotNull(env.classLoader)
        val scope = requireNotNull(PrivacyStructuralResolver.type(loader, SCOPE_CLASS)) { SCOPE_CLASS }
        val function0 = requireNotNull(PrivacyStructuralResolver.type(loader, FUNCTION0)) { FUNCTION0 }
        val gate = requireNotNull(locateGate(scope, function0)) { "LoginScopeKt.loginRequire" }
        val invoke = requireNotNull(Lookup.methodOrNull(function0, "invoke")) { "Function0.invoke" }
        val applied = AtomicBoolean(false)
        env.registrar.exact("guest.watch_later.login_scope", scope, gate.name, *gate.parameterTypes) {
            before {
                if (!runCatching { isGuest() }.getOrDefault(false)) return@before
                val action = argOrNull(gate.parameterCount - 1) ?: return@before
                if (!isWatchLaterAction(action.javaClass)) return@before
                // The helper returns no observer when it runs the action at once; nothing waits for a sign-in.
                result = null
                invoke.invoke(action)
                if (applied.compareAndSet(false, true)) env.reportStatus("guest_watch_later_login_scope_applied", "seen")
            }
        }
    }

    internal companion object {
        const val SCOPE_CLASS = "com.bilibili.app.comm.list.common.router.LoginScopeKt"
        private const val FUNCTION0 = "kotlin.jvm.functions.Function0"
        private const val ACTION_PACKAGE = "tv.danmaku.bili.ui.watchlater.widget."

        /** The single static `loginRequire(..., Function0)` with the action last; the `$default` bridge delegates to it. */
        fun locateGate(scope: Class<*>, function0: Class<*>): Method? = PrivacyStructuralResolver.uniqueMethod(scope) { m ->
            m.isStatic && m.name == "loginRequire" && m.parameterTypes.lastOrNull() == function0
        }

        /** An action class declared directly in the watch-later widget package (not a nested or unrelated class). */
        fun isWatchLaterAction(type: Class<*>): Boolean = type.name.startsWith(ACTION_PACKAGE) &&
            type.name.indexOf('.', ACTION_PACKAGE.length) < 0 && '$' !in type.name
    }
}
