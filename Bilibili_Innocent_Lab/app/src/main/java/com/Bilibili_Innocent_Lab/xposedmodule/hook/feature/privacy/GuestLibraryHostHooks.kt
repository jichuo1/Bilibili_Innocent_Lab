package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.highcapable.kavaref.extension.isSubclassOf
import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.TextView
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import androidx.core.net.toUri
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isStatic

internal class GuestLibraryHostHooks(
    private val library: GuestLibraryRuntime,
    private val isGuest: () -> Boolean,
    private val history: GuestNativeHistoryHooks
) {
    fun install(env: HookEnvironment): Int {
        val loader = env.classLoader ?: return 0
        library.nativePage = { activity, section ->
            if (section in setOf("history", "favorites", "follows", "watchLater")) { openNative(activity, section); true } else false
        }
        var installed = 0
        // Each entry reports its own outcome: the log channel keeps only the first line per key, so one shared key
        // hid which of these host-version-bound entries were actually missing. Status carries the failure kind only.
        fun attempt(name: String, action: () -> Unit): Boolean =
            runCatching(action).onSuccess {
                installed++
                env.reportStatus("guest_library_$name", "installed")
            }.onFailure {
                env.logError("guest_library_entry_$name", "[BIL] Local library entry unavailable: $name")
                env.reportStatus("guest_library_$name", "missing:${it.javaClass.simpleName}")
            }.isSuccess
        attempt("mine_menu") {
            val manager = requireNotNull(Lookup.classOrNull(loader, MINE_MANAGER))
            val item = requireNotNull(Lookup.classOrNull(loader, MINE_ITEM))
            val uri = requireNotNull(Lookup.fieldOrNull(item, "uri"))
            require(uri.type == classOf<String>())
            val needsLogin = requireNotNull(Lookup.fieldOrNull(item, "needLogin")).also {
                require(it.type == classOf<Int>())
            }
            // main2.a.l(Context) marks the local-capable favorites/watch-later menu items as
            // needLogin=1 before their final routes are selected by MinePageManager providers.
            env.registrar.exact("guest.library.mine_register", manager, "c", item) {
                before {
                    if (!isGuest()) return@before
                    val target = argOrNull(0) ?: return@before
                    if (sectionForUri(uri.get(target) as? String) != null) needsLogin.setInt(target, 0)
                }
            }
            val activity = requireNotNull(Lookup.declaredFields(manager, true) {
                !it.isStatic && it.type isSubclassOf classOf<Activity>()
            }.singleOrNull())
            env.registrar.exact("guest.library.mine", manager, "h", item) {
                before {
                    if (!isGuest()) return@before
                    val target = argOrNull(0) ?: return@before
                    val section = sectionForUri(uri.get(target) as? String) ?: return@before
                    result = null
                    val host = instance?.let(activity::get) as? Activity ?: return@before
                    openNative(host, section)
                }
            }
        }
        // These verified providers perform the login check inside MinePageManager's redirect
        // coroutine. Intercept the providers too, including host paths that bypass manager.h().
        for ((providerName, section) in listOf("b" to "favorites", "r" to "watchLater")) attempt("provider_$section") {
            val provider = requireNotNull(Lookup.classOrNull(loader,
                "tv.danmaku.bili.ui.main2.mine.provider.$providerName\$a"))
            val item = requireNotNull(Lookup.classOrNull(loader, MINE_ITEM))
            val observed = java.util.concurrent.atomic.AtomicBoolean(false)
            env.registrar.exact("guest.library.provider.$section.login", provider, "a", item) {
                before { if (isGuest()) result = false }
            }
            env.registrar.exact("guest.library.provider.$section.open", provider, "b", classOf<Activity>(), item) {
                before {
                    if (!isGuest()) return@before
                    result = null
                    if (observed.compareAndSet(false, true)) runCatching {
                        env.reportStatus("guest_library_$section", "applied")
                    }
                    (argOrNull(0) as? Activity)?.let { openNative(it, section) }
                }
            }
        }
        val fragment = Lookup.classOrNull(loader, MINE_FRAGMENT)
        if (fragment != null) attempt("follow_count") {
            val number = requireNotNull(Lookup.fieldOrNull(fragment, "p0"))
            val numbers = java.util.WeakHashMap<TextView, Unit>()
            fun update(state: LocalVirtualLibrary.Collections) {
                if (isGuest()) numbers.keys.toList().forEach { it.text = state.follows.size.toString() }
            }
            library.observeCollections(::update)
            fun bind(target: Any?) {
                if (!isGuest() || target == null) return
                val view = number.get(target) as? TextView ?: return
                numbers[view] = Unit
                library.readCollections(::update)
            }
            env.registrar.exact("guest.library.follow_count", fragment, "jr",
                requireNotNull(Lookup.classOrNull(loader, "tv.danmaku.bili.ui.main2.api.AccountMine")), classOf<Boolean>()) {
                after { if (!hasThrowable) bind(instance) }
            }
            env.registrar.exact("guest.library.follow_count_resume", fragment, "onResume") {
                after { if (!hasThrowable) bind(instance) }
            }
        }
        if (fragment != null) {
            attempt("follow_list") {
                val getActivity = requireNotNull(Lookup.inheritedMethodOrNull(fragment, "getActivity"))
                env.registrar.exact("guest.library.follows", fragment, "Ur") {
                    before {
                        if (!isGuest()) return@before
                        result = null
                        val host = instance?.let(getActivity::invoke) as? Activity ?: return@before
                        openNative(host, "follows")
                    }
                }
            }
            attempt("mine_header") {
                env.registrar.exact("guest.library.mine_header", fragment, "onViewCreated", classOf<View>(), classOf<Bundle>()) {
                    after {
                        if (!isGuest() || hasThrowable) return@after
                        val root = argOrNull(0) as? View ?: return@after
                        bindLocalHeader(root)
                    }
                }
            }
        }
        if (fragment != null) attempt("mine_header_update") {
            val account = requireNotNull(Lookup.classOrNull(loader, "tv.danmaku.bili.ui.main2.api.AccountMine"))
            val getView = requireNotNull(Lookup.inheritedMethodOrNull(fragment, "getView"))
            // lr(AccountMine) reassigns the avatar/header listeners after account updates.
            env.registrar.exact("guest.library.mine_header_update", fragment, "lr", account) {
                after {
                    if (!isGuest() || hasThrowable) return@after
                    (instance?.let(getView::invoke) as? View)?.let(::bindLocalHeader)
                }
            }
        }
        attempt("native_favorite") { GuestNativeFavoriteHooks(library, isGuest).install(env) }
        attempt("native_follow_list") { GuestNativeFollowListHooks(library, isGuest).install(env) }
        attempt("native_history") { history.install(env) }
        attempt("native_watch_later") { GuestNativeWatchLaterHooks(library, isGuest).install(env) }
        attempt("native_watch_later_add") { GuestNativeWatchLaterAddHooks(library, isGuest).install(env) }
        val localLists = attempt("native_list_http") { GuestNativeListHttpHooks(library, isGuest).install(env) }
        // The 8.97+ add flow posts a real request once the login gate is passed; only the short circuit keeps it local.
        if (localLists) attempt("watch_later_login_scope") { GuestWatchLaterLoginScope(isGuest).install(env) }
        else env.reportStatus("guest_library_watch_later_login_scope", "skipped-no-local-lists")

        attempt("follow_button") {
            val access = PrivacyHostAccess(loader)
            val helper = access.type("com.bilibili.relation.utils.FollowFlowHelper")
            val mid = requireNotNull(Lookup.fieldOrNull(helper, "c")).also { require(it.type == classOf<Long>()) }
            val callback = requireNotNull(Lookup.fieldOrNull(helper, "i"))
            // 8.84 declares only (Context, String); later builds add the (Context, String, Rect) overload.
            // Hook every overload this build declares (both are real click entries); none means skip.
            val overloads = listOf(arrayOf(classOf<android.content.Context>(), classOf<String>()),
                arrayOf(classOf<android.content.Context>(), classOf<String>(), classOf<android.graphics.Rect>()))
                .filter { PrivacyStructuralResolver.method(helper, "onButtonClick", *it) != null }
            require(overloads.isNotEmpty()) { "FollowFlowHelper.onButtonClick missing" }
            for (types in overloads) {
                env.registrar.exact("guest.library.follow.${types.size}", helper, "onButtonClick", *types) {
                    before {
                        if (!isGuest()) return@before
                        result = null
                        val current = instance ?: return@before
                        val uid = mid.getLong(current)
                        if (uid <= 0) return@before
                        val weak = java.lang.ref.WeakReference(current)
                        library.toggleFollow(uid) { following ->
                            weak.get()?.let { target ->
                                if (mid.getLong(target) == uid) {
                                    access.call(target, "setIsAttention", following)
                                    callback.get(target)?.let { cb ->
                                        access.call(cb, if (following) "onFollowSuccess" else "onUnFollowSuccess")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        attempt("video_menu_watch_later") {
            val access = PrivacyHostAccess(loader)
            val menu = access.type("com.bilibili.ship.theseus.united.page.toolbar.MenuService")
            val activity = requireNotNull(Lookup.fieldOrNull(menu, "b")).also {
                require(it.type isSubclassOf classOf<Activity>())
            }
            val unit = requireNotNull(Lookup.fieldOrNull(access.type("kotlin.Unit"), "INSTANCE")).get(null)
            val manager = access.type("tv.danmaku.bili.ui.watchlater.widget.WatchLaterManagerV2")
            val singleton = requireNotNull(Lookup.fieldOrNull(manager, "a")).get(null)
            env.registrar.exact("guest.library.watch_later", menu, "P1", menu) {
                before {
                    if (!isGuest()) return@before
                    result = unit
                    val target = argOrNull(0) ?: return@before
                    val host = activity.get(target) as? Activity ?: return@before
                    val repository = requireNotNull(Lookup.fieldOrNull(menu, "e")?.get(target))
                    val episode = requireNotNull(access.call(repository, "c"))
                    val aid = (access.call(episode, "a") as Number).toLong()
                    access.call(requireNotNull(singleton), "j", host, aid.toString(), null, "", 0, true, 0)
                }
            }
        }
        return installed
    }

    private fun openNative(activity: Activity, section: String) {
        when (section) {
            "history" -> activity.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                "bilibili://history".toUri()).setPackage(activity.packageName))
            "favorites" -> activity.startActivity(android.content.Intent().setClassName(activity.packageName,
                "tv.danmaku.bili.ui.favorites.FavoritesActivityV2"))
            "watchLater" -> activity.startActivity(android.content.Intent().setClassName(activity.packageName,
                "tv.danmaku.bili.ui.main2.WatchLaterActivity"))
            "follows" -> activity.startActivity(android.content.Intent().setClassName(activity.packageName,
                GuestNativeFollowListHooks.ACTIVITY).putExtra(GuestNativeFollowListHooks.EXTRA, true))
            else -> library.show(activity, section)
        }
    }

    private fun bindLocalHeader(root: View) {
        // These are verified host resource names; module R cannot reference the host's resource IDs.
        fun view(name: String): View? = root.findViewById(root.resources.getIdentifier(name, "id", "tv.danmaku.bili"))
        val strings = GuestLibraryStrings.resolve(root.context)
        view("mine_user_info_layout")?.setOnLongClickListener {
            if (!isGuest()) return@setOnLongClickListener false
            library.show(it.context)
            true
        }
        view("avatar_layout")?.apply {
            contentDescription = strings.title
            setOnClickListener { if (isGuest()) library.show(it.context) }
        }
        (view("tv_login") as? TextView)?.apply {
            // Incognito names the session (tapping explains how to sign in); a plain guest keeps the sign-in prompt.
            text = if (library.incognito) strings.incognitoTitle else strings.signIn
            contentDescription = text
            if (library.incognito) setOnClickListener {
                if (isGuest()) android.app.AlertDialog.Builder(it.context)
                    .setMessage(strings.exitToSignIn).setPositiveButton(android.R.string.ok, null).show()
            }
        }
    }

    companion object {
        private const val MINE_MANAGER = "tv.danmaku.bili.ui.main2.mine.MinePageManager"
        private const val MINE_ITEM = "com.bilibili.lib.homepage.mine.MenuGroup\$Item"
        private const val MINE_FRAGMENT = "tv.danmaku.bili.ui.main2.mine.HomeUserCenterFragment"

        fun sectionForUri(uri: String?): String? = when (uri?.substringBefore('?')?.trimEnd('/')) {
            "bilibili://main/drawer/history" -> "history"
            "bilibili://main/drawer/favorites", "bilibili://main/favorite",
            "bilibili://user_center/favourite", "bilibili://user_center/favourite2",
            "bilibili://user_center/favorite_v2/favorite",
            "bilibili://user_center/favorite_v2/favorite/folders" -> "favorites"
            "bilibili://main/drawer/watch-later", "bilibili://user_center/watch_later_v2",
            "bilibili://compose/watch_later/kntr" -> "watchLater"
            else -> null
        }
    }
}
