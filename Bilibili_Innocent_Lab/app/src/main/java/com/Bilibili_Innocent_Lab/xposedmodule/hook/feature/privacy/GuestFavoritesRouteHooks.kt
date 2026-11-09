package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ReflectAccess
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Open the host's own favorites page for a guest on builds that have no `compose/favorites/kntr` route (8.97 to 9.11).
 *
 * A guest tapping 我的收藏 passes the menu's `needLogin` gate ([GuestMineMenuHooks]) and then reaches
 * `BLRouter.routeTo(request, context)` with `bilibili://user_center/favourite`. The router's authenticator rewrites that to
 * the login route, which opens as a translucent overlay, so the screen looks unchanged. Where `kntr` is registered the menu
 * hook already swaps the target and this hook stays out of the way; where it is not, `FavoritesActivityV2` is started
 * directly (it lists the local library through the HTTP short circuit, checked on 9.0.0) and the router call is answered
 * with `RouteResponse(OK, request)` so the caller sees a handled route.
 *
 * Only guests, and only the favorites URIs of [GuestLibraryHostHooks.sectionForUri]. Everything else, and every failure to
 * answer or to start the page, leaves the host's own behavior in place.
 */
internal class GuestFavoritesRouteHooks(
    private val isGuest: () -> Boolean,
    private val routerNames: GuestVideoFavoriteHooks.RouterNames = GuestVideoFavoriteHooks.RouterNames(),
    private val open: (Any?) -> Boolean = { openFavorites(it) },
    private val kntrRegistered: (ClassLoader) -> Boolean = { loader ->
        GuestMineMenuHooks.RouteTable.resolve(loader)?.isRegistered(GuestMineMenuHooks.FAVORITES_TARGET) == true
    },
    private val openFolder: (Any?, String, Long) -> Boolean = { target, uri, id -> Companion.openFolder(target, uri, id) }
) {
    fun install(env: HookEnvironment): Int = runCatching {
        val loader = requireNotNull(env.classLoader)
        val router = requireNotNull(PrivacyStructuralResolver.type(loader, routerNames.router))
        val request = requireNotNull(PrivacyStructuralResolver.type(loader, routerNames.request))
        val response = requireNotNull(PrivacyStructuralResolver.type(loader, routerNames.response))
        val code = requireNotNull(PrivacyStructuralResolver.type(loader, routerNames.code))
        val routeTo = requireNotNull(PrivacyStructuralResolver.method(router, "routeTo", request, Context::class.java))
        val getTargetUri = requireNotNull(PrivacyStructuralResolver.method(request, "getTargetUri"))
        val ok = requireNotNull(code.enumConstants?.firstOrNull { (it as? Enum<*>)?.name == "OK" })
        val newResponse = requireNotNull(Lookup.constructorOrNull(response, code, request))
        val answered = AtomicBoolean(false)
        env.registrar.exact("guest.favorites.route", router, routeTo.name, *routeTo.parameterTypes) {
            before {
                if (!runCatching { isGuest() }.getOrDefault(false)) return@before
                val routeRequest = argOrNull(0) ?: return@before
                val uri = runCatching { getTargetUri.invoke(routeRequest)?.toString() }.getOrNull()
                if (GuestLibraryHostHooks.sectionForUri(uri) != "favorites") return@before
                if (kntrRegistered(loader)) return@before
                val context = argOrNull(1)
                val handled = runCatching { newResponse.newInstance(ok, routeRequest) }.getOrNull() ?: return@before
                if (!runCatching { open(context) }.getOrDefault(false)) return@before
                result = handled
                if (answered.compareAndSet(false, true)) env.reportStatus("guest_favorites_route_opened", "seen")
            }
        }
        env.reportStatus("guest_favorites_route", "installed")
        1 + installFolderRoute(env, loader, router, request, ok, newResponse)
    }.getOrElse {
        env.logError("guest_favorites_route", "[BIL] Guest favorites route unavailable: ${it.javaClass.simpleName}")
        env.reportStatus("guest_favorites_route", "absent")
        0
    }

    /**
     * A folder card in the favorites page routes `bilibili://music/playlist/detail/<id>` through
     * `BLRouter.routeTo(request, fragment)`; the router's login rewrite swallows it for a guest. Where `fa6.b` exists
     * [GuestNativeListHttpHooks] already opens the detail page, so this hook only matters on builds without it (9.0.0).
     */
    private fun installFolderRoute(
        env: HookEnvironment, loader: ClassLoader, router: Class<*>, request: Class<*>, ok: Any,
        newResponse: java.lang.reflect.Constructor<*>
    ): Int = runCatching {
        val fragment = requireNotNull(PrivacyStructuralResolver.type(loader, routerNames.fragment))
        val routeTo = requireNotNull(PrivacyStructuralResolver.method(router, "routeTo", request, fragment))
        val getTargetUri = requireNotNull(PrivacyStructuralResolver.method(request, "getTargetUri"))
        val answered = AtomicBoolean(false)
        // This overload is also used by the session-bound follow-group route. A protective retry could
        // discard its modified arguments, so preserve host exceptions without replaying the route.
        env.registrar.adapted("guest.favorites.folder_route",
            VersionAdapter.HookPoint(router.name, routeTo.name, routeTo.parameterTypes.map { it.name }),
            HookExceptionPolicy.DELIVER_TO_HOST) {
            before {
                try {
                    if (!isGuest()) return@before
                    val routeRequest = argOrNull(0) ?: return@before
                    val uri = getTargetUri.invoke(routeRequest)?.toString() ?: return@before
                    val id = folderIdOf(uri) ?: return@before
                    val handled = newResponse.newInstance(ok, routeRequest)
                    if (!openFolder(argOrNull(1), uri, id)) return@before
                    result = handled
                    if (answered.compareAndSet(false, true)) env.reportStatus("guest_favorites_folder_route_opened", "seen")
                } catch (_: Throwable) {
                    // The unrelated host route remains available when this optional folder adapter fails.
                }
            }
        }
        env.reportStatus("guest_favorites_folder_route", "installed")
        1
    }.getOrElse {
        env.reportStatus("guest_favorites_folder_route", "absent")
        0
    }

    internal companion object {
        const val ACTIVITY = "tv.danmaku.bili.ui.favorites.FavoritesActivityV2"
        const val DETAIL_ACTIVITY = "com.bilibili.playset.MultiTypeListDetailActivityV2"

        /** The request code the folder tab passes, so it refreshes when the detail page edits or deletes the folder. */
        const val FOLDER_REQUEST_CODE = 300

        /** The folder id of `bilibili://music/playlist/detail/<id>[?...]`; null for any other URI. */
        fun folderIdOf(uri: String?): Long? {
            val path = uri?.substringBefore('?')?.trimEnd('/') ?: return null
            val prefix = "bilibili://music/playlist/detail/"
            if (!path.startsWith(prefix)) return null
            return path.substring(prefix.length).toLongOrNull()?.takeIf { it > 0 }
        }

        /**
         * Start the host's folder detail page for [id], the same intent the host's own route builds (data URI plus the
         * `playlistId` extra). From a fragment it uses the fragment's own result channel so the tab refreshes afterwards.
         * [target] is the fragment the router was given, a Context, or null; the host application is the last resort.
         */
        fun openFolder(target: Any?, uri: String, id: Long): Boolean {
            val context = target as? Context ?: runCatching { ReflectAccess.callMethod(target, "getContext") as? Context }.getOrNull()
                ?: ReflectAccess.currentApplication() ?: return false
            val intent = Intent().setClassName(context.packageName, DETAIL_ACTIVITY)
                .setData(uri.toUri()).putExtra("playlistId", id)
            if (target != null && target !is Context &&
                runCatching { ReflectAccess.callMethod(target, "startActivityForResult", intent, FOLDER_REQUEST_CODE) }.isSuccess) return true
            if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return true
        }

        /**
         * Start the host's favorites page. The menu calls `BLRouter.routeTo$default`, which passes no context (seen as null on
         * 9.0.0), so the host application is the fallback. False when neither is available.
         */
        fun openFavorites(target: Any?): Boolean {
            val context = target as? Context ?: ReflectAccess.currentApplication() ?: return false
            val intent = Intent().setClassName(context.packageName, ACTIVITY)
            if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return true
        }
    }
}
