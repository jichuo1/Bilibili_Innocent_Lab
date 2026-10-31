package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isStatic
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.util.Collections
import java.util.WeakHashMap

/**
 * The host's own folder picker for the video page star, `com.bilibili.playset.widget.favorite.FavoriteDialog`, opened for a
 * guest instead of the module's [GuestVideoFavoriteHooks.FolderPicker].
 *
 * All 38 builds build it the same way, `FavoriteDialog(ComponentActivity, ExtraParams, launcher)` with one 20-parameter
 * `ExtraParams` constructor (matrices q69, q71, q72), which is what `KingPositionService` does after its login gate; the
 * parameter class is `FavoriteDialog$c` up to 8.96 and `$b` from 8.97, so it is taken from the constructor. The dialog lists
 * folders through `/x/v3/fav/folder/created/list-all` and commits through `/x/v3/fav/resource/batch-deal`, both `BiliCall`,
 * so [NativeFavoriteProtocol] answers them from the local library. It only loads its list when `BiliAccounts.isLogin()` is
 * true, so while one of the dialogs opened here runs its own code, `isLogin()` answers true on that thread ([GuestLoginScope]);
 * nothing outside the dialog sees it. A build without that shape keeps the module's picker.
 */
internal class GuestHostFavoriteDialog : GuestVideoFavoriteHooks.HostDialog {
    private class Parts(val params: Constructor<*>, val dialog: Constructor<*>, val activity: Class<*>, val ugc: Any, val scene: Any)

    private var parts: Parts? = null
    private var watchResults: ((Class<*>) -> Unit)? = null
    private val watched = HashSet<java.lang.reflect.Method>()
    /** Dialogs opened here, with the page each was opened on; the host's own dialogs never get the login answer. */
    private val opened = Collections.synchronizedMap(WeakHashMap<Any, WeakReference<Context>>())

    override fun install(env: HookEnvironment, loader: ClassLoader): Boolean = runCatching {
        val host = PrivacyHostAccess(loader)
        val dialog = host.type(DIALOG)
        val launcher = host.type("androidx.activity.result.ActivityResultLauncher")
        val bizType = host.type("$DIALOG\$BizType")
        val scene = host.type("com.bilibili.playset.widget.favorite.FavSnackBarUtil\$Scene")
        val activity = host.type("androidx.activity.ComponentActivity")
        val dialogConstructor = requireNotNull(Lookup.declaredConstructors(dialog, makeAccessible = true) {
            it.parameterCount == 3 && it.parameterTypes[0] == activity && it.parameterTypes[2] == launcher
        }.singleOrNull()) { "FavoriteDialog constructor" }
        val params = dialogConstructor.parameterTypes[1]
        val fields = arrayOf(classOf<Long>(), classOf<Long>(), bizType, classOf<Boolean>(), classOf<Int>(), classOf<Boolean>(),
            classOf<Boolean>(), classOf<Boolean>(), classOf<Map<*, *>>(), classOf<String>(), classOf<String>(), classOf<String>(),
            scene, Boolean::class.javaObjectType, classOf<Boolean>(), classOf<Boolean>(), classOf<String>(), classOf<String>(),
            Long::class.javaObjectType)
        // Up to 8.96 the 19 fields have a plain constructor; from 8.97 the only one also takes the defaults mask (passed 0).
        val paramsConstructor = requireNotNull(Lookup.constructorOrNull(params, *fields)
            ?: Lookup.constructorOrNull(params, *fields, classOf<Int>())) { "FavoriteDialog params" }
        val ugc = requireNotNull(Lookup.fieldOrNull(bizType, "UGC")).get(null)
        val detail = requireNotNull(Lookup.fieldOrNull(scene, "DetailPage")).get(null)
        val accounts = host.type("com.bilibili.lib.accounts.BiliAccounts")
        val isLogin = requireNotNull(Lookup.methodOrNull(accounts, "isLogin")) { "BiliAccounts.isLogin" }
        val methods = Lookup.declaredMethods(dialog) { !java.lang.reflect.Modifier.isAbstract(it.modifiers) }
        require(methods.isNotEmpty())
        for (method in methods) {
            env.registrar.exact("guest.host.favorite.dialog.${method.name}.${method.parameterCount}", dialog, method.name, *method.parameterTypes) {
                // A static accessor gets its dialog as the first argument.
                before {
                    if (opened.containsKey(instance ?: argOrNull(0))) {
                        GuestLoginScope.enter()
                        setObjectExtra(MARK, true)
                    }
                }
                after { if (getObjectExtra(MARK) == true) GuestLoginScope.exit() }
            }
        }
        env.registrar.exact("guest.host.favorite.login", accounts, isLogin.name) {
            before { if (GuestLoginScope.active) result = true }
        }
        // The dialog's "new folder" page reports back as the video page's activity result; the page reloads only its own
        // dialog (its `FavoriteDialog` field), so a dialog opened here reloads through the same list method. That method is
        // the dialog's only own no-argument one (overrides such as `onStart` keep their names); none means no reload.
        val reload = Lookup.declaredMethods(dialog, makeAccessible = true) {
            !it.isStatic && it.returnType == Void.TYPE && it.parameterCount == 0 &&
                Lookup.inheritedMethodOrNull(requireNotNull(dialog.superclass), it.name) == null
        }.singleOrNull()
        // The page's own override need not call up the chain, so the override that runs is hooked when a dialog first opens.
        watchResults = if (reload == null) null else { page ->
            val method = Lookup.inheritedMethodOrNull(page, "onActivityResult", classOf<Int>(), classOf<Int>(), classOf<Intent>())
            if (method != null && synchronized(watched) { watched.add(method) }) {
                env.registrar.exact("guest.host.favorite.result.${method.declaringClass.name}", method.declaringClass, method.name,
                    *method.parameterTypes) {
                    after {
                        if (argOrNull(0) != REQUEST_CODE || argOrNull(1) != Activity.RESULT_OK) return@after
                        val owner = instance ?: return@after
                        synchronized(opened) { opened.filter { (_, page) -> page.get() === owner }.keys.toList() }
                            .filterIsInstance<Dialog>().filter { it.isShowing }.forEach { reload.invoke(it) }
                    }
                }
            }
        }
        parts = Parts(paramsConstructor, dialogConstructor, activity, ugc, detail)
        true
    }.getOrElse {
        env.reportStatus("guest_video_favorite_host_dialog", "absent:${it.javaClass.simpleName}")
        false
    }

    override fun show(context: Context?, video: LocalVirtualLibrary.Video, favorited: Boolean, onClosed: () -> Unit): Boolean {
        val found = parts ?: return false
        if (context == null || !found.activity.isInstance(context)) return false
        return runCatching {
            val values = arrayOf<Any?>(video.aid, video.cid, found.ugc, favorited, REQUEST_CODE, false, false, false,
                emptyMap<String, String>(), "", "", "", found.scene, false, false, false, "", "", null)
            val params = found.params.newInstance(*(if (found.params.parameterCount == values.size) values else values + 0))
            val dialog = found.dialog.newInstance(context, params, null) as Dialog
            opened[dialog] = WeakReference(context)
            runCatching { watchResults?.invoke(context.javaClass) }
            dialog.setOnDismissListener { onClosed() }
            GuestLoginScope.enter()
            try { dialog.show() } finally { GuestLoginScope.exit() }
            true
        }.getOrDefault(false)
    }

    private companion object {
        const val DIALOG = "com.bilibili.playset.widget.favorite.FavoriteDialog"
        const val MARK = "guest.host.favorite.depth"
        /** What the video page passes; the dialog only hands it back in its activity result. */
        const val REQUEST_CODE = 209
    }
}
