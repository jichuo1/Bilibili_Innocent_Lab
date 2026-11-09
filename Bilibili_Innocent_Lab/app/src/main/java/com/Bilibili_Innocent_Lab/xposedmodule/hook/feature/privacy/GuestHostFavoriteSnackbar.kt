package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.hostProxyDefaultValue
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import android.content.Context
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.hostActivity
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

/** The host's favorite snackbar and localized strings, with a callback into the guest folder picker. */
internal class GuestHostFavoriteSnackbar : GuestVideoFavoriteHooks.HostSnackbar {
    private class Parts(val activity: Class<*>, val callback: Class<*>, val show: ShowMethod, val toast: Method,
                        val scene: Any, val storyScene: Any, val favorite: Any)
    private var parts: Parts? = null
    private val resourceIds = ConcurrentHashMap<String, Int>()

    override fun install(env: HookEnvironment, loader: ClassLoader): Boolean = runCatching {
        val host = PrivacyHostAccess(loader)
        val activity = host.type("androidx.activity.ComponentActivity")
        val util = host.type("com.bilibili.playset.widget.favorite.FavSnackBarUtil")
        val scene = host.type("com.bilibili.playset.widget.favorite.FavSnackBarUtil\$Scene")
        val favorite = host.type("com.bilibili.playset.widget.favorite.FavFrom")
        val callback = host.type("com.bilibili.playset.widget.favorite.FavSnackBarUtil\$b")
        require(callback.isInterface && Lookup.methodOrNull(callback, "onClick")?.returnType == Void.TYPE)
        val signature = arrayOf<Class<*>>(activity, scene, classOf<String>(), classOf<Int>(),
            classOf<Boolean>(), favorite, classOf<String>(), classOf<Long>(),
            classOf<Long>(), classOf<String>(), callback)
        val show = requireNotNull(locateShow(util, signature))
        val toast = requireNotNull(Lookup.methodOrNull(host.type("com.bilibili.droid.ToastHelper"),
            "showToastShort", Context::class.java, String::class.java)?.takeIf { it.isStatic })
        val detail = requireNotNull(requireNotNull(Lookup.fieldOrNull(scene, "DetailPage")).get(null))
        val story = requireNotNull(requireNotNull(Lookup.fieldOrNull(scene, "Story")).get(null))
        val fav = requireNotNull(requireNotNull(Lookup.fieldOrNull(favorite, "Fav")).get(null))
        parts = Parts(activity, callback, show, toast, detail, story, fav)
        true
    }.getOrDefault(false)

    override fun showAdded(context: Context?, video: LocalVirtualLibrary.Video, story: Boolean, onModify: () -> Unit): Boolean {
        val found = parts ?: return false
        val activity = context?.hostActivity?.takeUnless { it.isFinishing || it.isDestroyed } ?: return false
        if (!found.activity.isInstance(activity)) return false
        return runCatching {
            val message = hostStringId(activity, "playerbaseres_global_string_825")
            val action = hostStringId(activity, "favorites_global_string_197")
            require(message != 0 && action != 0)
            val listener = Proxy.newProxyInstance(found.callback.classLoader, arrayOf(found.callback)) { proxy, method, args ->
                when (method.name) {
                    "onClick" -> { HostThreadGuard.run("guest.favorite.snackbar.click") { onModify() }; null }
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "toString" -> "GuestFavoriteSnackbarCallback"
                    else -> hostProxyDefaultValue(method.returnType)
                }
            }
            found.show.method.invoke(found.show.receiver, activity, if (story) found.storyScene else found.scene,
                activity.getString(message), action, true, found.favorite,
                "3", video.aid, video.cid, "", listener)
            true
        }.getOrDefault(false)
    }

    override fun showRemoved(context: Context?): Boolean {
        val found = parts ?: return false
        if (context == null) return false
        return runCatching {
            val message = hostStringId(context, "playerbaseres_global_string_1272")
            require(message != 0)
            found.toast.invoke(null, context, context.getString(message))
            true
        }.getOrDefault(false)
    }

    // Host APK IDs differ by version and are not available in the module's R class. Resolve once per process by name.
    private fun hostStringId(context: Context, name: String): Int = resourceIds.getOrPut(name) {
        context.resources.getIdentifier(name, "string", HOST_PACKAGE)
    }

    internal data class ShowMethod(val method: Method, val receiver: Any?)

    internal companion object {
        private const val HOST_PACKAGE = "tv.danmaku.bili"

        /** Older builds expose a Kotlin singleton instance method; newer builds use a static method. */
        fun locateShow(util: Class<*>, signature: Array<Class<*>>): ShowMethod? = runCatching {
            val method = PrivacyStructuralResolver.uniqueMethod(util) {
                it.returnType == Void.TYPE && it.parameterTypes.contentEquals(signature)
            } ?: return@runCatching null
            val receiver = if (method.isStatic) null else {
                val singleton = Lookup.declaredFields(util, makeAccessible = true) { it.isStatic && it.type == util }.singleOrNull()
                    ?: return@runCatching null
                singleton.get(null) ?: return@runCatching null
            }
            ShowMethod(method, receiver)
        }.getOrNull()
    }
}
