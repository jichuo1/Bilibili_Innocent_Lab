package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Let a guest open the library rows of the Compose mine page (V2): favorites, watch later, history.
 *
 * Tapping a row on `MinePageManagerV2` goes through two gates, both visible on a 9.16.1 device:
 *
 * 1. The menu item's own `getNeedLogin()`. A logged-out account with `needLogin == 1` is sent straight to the login route.
 *    For a library row the guest gets 0 instead, so the host opens the row the way it does for any row that needs no login.
 * 2. The route itself. The server sends `bilibili://user_center/favourite` for favorites. It is registered, but it needs a
 *    login, so the router's authenticator rewrites it to `bilibili://login`. `bilibili://compose/favorites/kntr` opens the
 *    native favorites page without one. The guest therefore gets that URI from `getUri()`, but only when the router says it
 *    has a route for it (`BLRouter.findRoutes(...).getInfo()` is non-empty): where it is not registered, or the router
 *    cannot be asked, the host's own URI is left alone.
 *
 * Nothing here talks to the router's interceptors or authenticator, and no host instance is held. Every member used is a
 * public, unobfuscated name: `MenuGroupV2$Item.getNeedLogin/getUri` (8.92-9.17), `BLRouter.findRoutes` (static, 38/38),
 * `MatchedRoutes.getInfo` (38/38), `RouteRequestKt.toRouteRequest(String)` (38/38).
 *
 * Two status keys keep the install report apart from evidence of use: `guest_mine_menu` is the install report, and
 * `guest_mine_menu_login_waived` / `guest_mine_menu_favorites_rewritten` are set only when a guest call was actually changed.
 *
 * Verified on a 9.16.1 device only. The favorites target is registered on 9.12-9.17 only; elsewhere the favorites row keeps
 * the host's behavior.
 */
internal class GuestMineMenuHooks(
    private val isGuest: () -> Boolean,
    private val itemClass: String = ITEM_CLASS
) {
    fun install(env: HookEnvironment): Int = runCatching {
        val loader = requireNotNull(env.classLoader)
        val item = PrivacyStructuralResolver.type(loader, itemClass)
        val getUri = item?.let { PrivacyStructuralResolver.method(it, "getUri") }?.takeIf { it.returnType == String::class.java }
        val needLogin = item?.let { PrivacyStructuralResolver.method(it, "getNeedLogin") }?.takeIf { it.returnType == Int::class.javaPrimitiveType }
        if (item == null || getUri == null || needLogin == null) {
            env.reportStatus("guest_mine_menu", "absent")
            return@runCatching 0
        }
        val router = RouteTable.resolve(loader)
        val waived = AtomicBoolean(false)
        val rewritten = AtomicBoolean(false)
        env.registrar.exact("guest.mine.menu.needLogin", item, needLogin.name) {
            before {
                if (!runCatching { isGuest() }.getOrDefault(false)) return@before
                val self = instance ?: return@before
                val uri = runCatching { getUri.invoke(self) as? String }.getOrNull()
                if (!isLibraryUri(uri)) return@before
                result = 0
                if (waived.compareAndSet(false, true)) env.reportStatus("guest_mine_menu_login_waived", "seen")
            }
        }
        env.registrar.exact("guest.mine.menu.uri", item, getUri.name) {
            after {
                if (hasThrowable || !runCatching { isGuest() }.getOrDefault(false)) return@after
                val uri = result as? String ?: return@after
                if (GuestLibraryHostHooks.sectionForUri(uri) != "favorites") return@after
                if (router?.isRegistered(FAVORITES_TARGET) != true) return@after
                result = FAVORITES_TARGET
                if (rewritten.compareAndSet(false, true)) env.reportStatus("guest_mine_menu_favorites_rewritten", "seen")
            }
        }
        env.reportStatus("guest_mine_menu", "installed")
        2
    }.getOrElse {
        env.logError("guest_mine_menu", "[BIL] Guest mine menu unavailable: ${it.javaClass.simpleName}")
        env.reportStatus("guest_mine_menu", "failed")
        0
    }

    /** True for a library row, whether it still carries the server's URI or the target this class substitutes. */
    private fun isLibraryUri(uri: String?): Boolean =
        GuestLibraryHostHooks.sectionForUri(uri) != null || uri?.substringBefore('?')?.trimEnd('/') == FAVORITES_TARGET

    /**
     * "Is there a registered route for this URI?", answered by the router itself without running its interceptors or
     * authenticator. The answer for a URI never changes within a process, so it is cached.
     */
    internal class RouteTable private constructor(
        private val findRoutes: Method,
        private val toRequest: Method,
        private val getInfo: Method
    ) {
        private val cache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

        fun isRegistered(uri: String): Boolean = cache.getOrPut(uri) {
            runCatching {
                val request = toRequest.invoke(null, uri)
                val matched = findRoutes.invoke(null, request)
                (getInfo.invoke(matched) as? Collection<*>)?.isNotEmpty() == true
            }.getOrDefault(false)
        }

        companion object {
            fun resolve(loader: ClassLoader): RouteTable? = runCatching {
                val blRouter = requireNotNull(PrivacyStructuralResolver.type(loader, "com.bilibili.lib.blrouter.BLRouter"))
                val request = requireNotNull(PrivacyStructuralResolver.type(loader, "com.bilibili.lib.blrouter.RouteRequest"))
                val matchedType = requireNotNull(PrivacyStructuralResolver.type(loader, "com.bilibili.lib.blrouter.MatchedRoutes"))
                val kt = requireNotNull(PrivacyStructuralResolver.type(loader, "com.bilibili.lib.blrouter.RouteRequestKt"))
                val find = requireNotNull(PrivacyStructuralResolver.method(blRouter, "findRoutes", request))
                    .takeIf { it.returnType == matchedType && Modifier.isStatic(it.modifiers) }
                val toRequest = requireNotNull(PrivacyStructuralResolver.method(kt, "toRouteRequest", String::class.java))
                val info = requireNotNull(PrivacyStructuralResolver.method(matchedType, "getInfo"))
                    .takeIf { Collection::class.java.isAssignableFrom(it.returnType) }
                RouteTable(requireNotNull(find), toRequest, requireNotNull(info))
            }.getOrNull()
        }
    }

    internal companion object {
        const val ITEM_CLASS = "com.bilibili.lib.homepage.mine.MenuGroupV2\$Item"
        const val FAVORITES_TARGET = "bilibili://compose/favorites/kntr"
    }
}
