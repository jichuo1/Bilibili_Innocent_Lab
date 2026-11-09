package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.net.toUri
import androidx.core.view.isVisible
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ReflectAccess
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.InjectedUiLocale
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.hostActivity
import io.github.jichuo1.meguviz.android.extensions.child
import io.github.jichuo1.meguviz.android.extensions.textToString
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isSubclassOf
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The guest's local follow list on 8.92 and later builds, shown in the host's own relation-list page.
 *
 * The host has no native follow list (its follow entry is H5), so its blacklist page is reused: `BlackListActivity` on
 * 8.84 to 8.91 and `BlackListFragment` from 8.92 (matrix q65), behind the route `bilibili://user_center/blackList`, with
 * members renamed on almost every build. So only stable API is used: both load through
 * `RelationApiManager.getBlackList(String, BiliApiDataCallback)`, whose callback holds the page, and for a page this class opened
 * that call is answered through the callback's public `onDataSuccess` with an `AttentionList` of the local follows. Rows
 * are relabelled by walking their views, the swipe-to-unblock helper is never attached, and a row opens the person's space,
 * whose own follow button (already local for guests) is how a guest unfollows; the list follows the library's changes.
 *
 * A page opened any other way (the real blacklist in settings) is left alone.
 */
internal class GuestFollowListPage(private val library: GuestLibraryRuntime, private val isGuest: () -> Boolean) {
    /** Until when the next blacklist page counts as the local follow page; expires so a failed route cannot leak. */
    private val pendingUntil = AtomicLong(0)
    private val pages = WeakHashMap<Any, Page>()
    private var opener: ((Context) -> Unit)? = null

    private class Page(val activity: WeakReference<Activity>) {
        var callback: WeakReference<Any>? = null
        var follows: List<LocalVirtualLibrary.Follow> = emptyList()
    }

    /** Start the local follow page; false when this build has no such page or the router refused. */
    fun open(context: Context): Boolean {
        val start = opener ?: return false
        pendingUntil.set(SystemClock.uptimeMillis() + PENDING_MS)
        return runCatching { start(context) }.isSuccess.also { if (!it) pendingUntil.set(0) }
    }

    fun install(env: HookEnvironment) {
        val host = PrivacyHostAccess(requireNotNull(env.classLoader))
        // 8.92 on, the relation list is a fragment; before, the activity itself (q65). Both load through getBlackList.
        val fragment = PrivacyStructuralResolver.type(host.loader, FRAGMENT)
        val owner = fragment ?: host.type(ACTIVITY)
        val manager = host.type("com.bilibili.relation.api.RelationApiManager")
        val callbackType = host.type("com.bilibili.okretro.BiliApiDataCallback")
        val getBlackList = requireNotNull(Lookup.methodOrNull(manager, "getBlackList", classOf<String>(), callbackType)
            ?.takeIf { it.isStatic }) { "RelationApiManager.getBlackList" }
        val onDataSuccess = requireNotNull(Lookup.methodOrNull(callbackType, "onDataSuccess", classOf<Any>())) { "onDataSuccess" }
        val listType = host.type("com.bilibili.relation.api.AttentionList")
        val listField = requireNotNull(Lookup.fieldOrNull(listType, "list")) { "AttentionList.list" }
        val attention = host.type("com.bilibili.relation.api.Attention")
        val mid = requireNotNull(Lookup.fieldOrNull(attention, "mid"))
        val uname = requireNotNull(Lookup.fieldOrNull(attention, "uname"))
        val face = requireNotNull(Lookup.fieldOrNull(attention, "face"))
        val created = if (fragment != null) requireNotNull(Lookup.methodOrNull(fragment, "onViewCreated", classOf<View>(), classOf<Bundle>()))
            else requireNotNull(Lookup.methodOrNull(owner, "onCreate", classOf<Bundle>()))
        val holderType = host.type("androidx.recyclerview.widget.RecyclerView\$ViewHolder")
        val adapterType = host.type("androidx.recyclerview.widget.RecyclerView\$Adapter")
        val adapterField = requireNotNull(Lookup.declaredFields(owner, makeAccessible = true) {
            !it.isStatic && it.type isSubclassOf adapterType
        }.singleOrNull()) { "relation list adapter" }
        val adapter = adapterField.type
        // The host answers an empty list with its empty tips only, keeping the old rows and its hidden tips view.
        val rowsField = Lookup.declaredFields(adapter, makeAccessible = true) { !it.isStatic && it.type == classOf<List<*>>() }.singleOrNull()
        val refresh = Lookup.methodOrNull(adapterType, "notifyDataSetChanged")
        val emptyTips = Lookup.declaredFields(owner, makeAccessible = true) {
            !it.isStatic && it.type.name == "tv.danmaku.bili.widget.LoadingImageView"
        }.singleOrNull()
        val bind = requireNotNull(Lookup.methodOrNull(adapter, "onBindViewHolder", holderType, classOf<Int>()))
        val itemView = requireNotNull(Lookup.fieldOrNull(holderType, "itemView"))
        val touchHelper = host.type("androidx.recyclerview.widget.ItemTouchHelper")
        val attach = requireNotNull(Lookup.methodOrNull(touchHelper, "attachToRecyclerView",
            host.type("androidx.recyclerview.widget.RecyclerView")))
        val getArguments = fragment?.let { requireNotNull(Lookup.inheritedMethodOrNull(it, "getArguments")) }
        val getActivity = fragment?.let { requireNotNull(Lookup.inheritedMethodOrNull(it, "getActivity")) }
        val title = Lookup.declaredFields(owner, makeAccessible = true) { !it.isStatic && it.type == classOf<TextView>() }.singleOrNull()
        val names = GuestVideoFavoriteHooks.RouterNames()
        val request = host.type(names.request)
        val routeTo = requireNotNull(Lookup.methodOrNull(host.type(names.router), "routeTo", request, classOf<Context>()))
        val builder = host.type("${names.request}\$Builder")
        val newBuilder = requireNotNull(Lookup.constructorOrNull(builder, classOf<String>()))
        val build = requireNotNull(Lookup.methodOrNull(builder, "build"))
        val applied = java.util.concurrent.atomic.AtomicBoolean(false)

        fun pageFor(activity: Activity?): Page? = activity?.let { synchronized(pages) { pages.values.firstOrNull { it.activity.get() === activity } } }

        fun deliver(target: Any, page: Page) {
            val callback = page.callback?.get() ?: return
            library.readCollections { collections ->
                page.follows = collections.follows
                val rows = collections.follows.map { follow -> host.create(attention).also { row ->
                    mid.setLong(row, follow.mid); uname.set(row, follow.name); face.set(row, follow.face)
                } }
                val list = host.create(listType).also { listField.set(it, rows) }
                if (synchronized(pages) { pages[target] } !== page) return@readCollections
                onDataSuccess.invoke(callback, list)
                if (rows.isEmpty()) {
                    val shown = adapterField.get(target) ?: return@readCollections
                    (rowsField?.get(shown) as? MutableList<*>)?.let { it.clear(); refresh?.invoke(shown) }
                    (emptyTips?.get(target) as? View)?.isVisible = true
                }
            }
        }
        library.observeCollections { synchronized(pages) { pages.toList() }.forEach { (target, page) -> deliver(target, page) } }

        env.registrar.exact("guest.follow.page.mark", owner, created.name, *created.parameterTypes) {
            before {
                val target = instance ?: return@before
                if (!isGuest()) return@before
                val opened = pendingUntil.getAndSet(0) >= SystemClock.uptimeMillis()
                if (getArguments != null && getActivity != null) {
                    val arguments = getArguments.invoke(target) as? Bundle
                    if (!opened && arguments?.getBoolean(EXTRA) != true) return@before
                    val activity = getActivity.invoke(target) as? Activity ?: return@before
                    // Kept in the arguments too, so a page recreated after a configuration change stays local.
                    arguments?.putBoolean(EXTRA, true)
                    synchronized(pages) { pages[target] = Page(WeakReference(activity)) }
                } else {
                    val activity = target as? Activity ?: return@before
                    if (!opened && activity.intent?.getBooleanExtra(EXTRA, false) != true) return@before
                    activity.intent?.putExtra(EXTRA, true)
                    synchronized(pages) { pages[target] = Page(WeakReference(activity)) }
                }
            }
            after {
                val target = instance ?: return@after
                val page = synchronized(pages) { pages[target] } ?: return@after
                val activity = page.activity.get() ?: return@after
                val label = GuestFollowListStrings.resolve(activity).title
                activity.title = label
                // The fragment's own toolbar title is its single TextView field; other shapes keep the host title.
                (title?.get(target) as? TextView)?.text = label
            }
        }
        env.registrar.exact("guest.follow.page.load", manager, getBlackList.name, *getBlackList.parameterTypes) {
            before {
                if (!isGuest()) return@before
                val callback = argOrNull(1) ?: return@before
                val target = Lookup.declaredFields(callback.javaClass, makeAccessible = true) {
                    !it.isStatic && it.type isSubclassOf owner
                }.singleOrNull()?.get(callback) ?: return@before
                val page = synchronized(pages) { pages[target] } ?: return@before
                result = null
                page.callback = WeakReference(callback)
                deliver(target, page)
                if (applied.compareAndSet(false, true)) env.reportStatus("guest_follow_page_applied", "seen")
            }
        }
        // The reused page's swipe gesture unblocks a user; on the local page it is never attached.
        env.registrar.exact("guest.follow.page.swipe", touchHelper, attach.name, *attach.parameterTypes) {
            before {
                val view = argOrNull(0) as? View ?: return@before
                if (isGuest() && pageFor(view.context.hostActivity) != null) args[0] = null
            }
        }
        env.registrar.exact("guest.follow.page.bind", adapter, bind.name, *bind.parameterTypes) {
            after {
                if (hasThrowable || !isGuest()) return@after
                val root = itemView.get(argOrNull(0) ?: return@after) as? View ?: return@after
                val activity = root.context.hostActivity ?: return@after
                val follow = pageFor(activity)?.follows?.getOrNull(argOrNull(1) as? Int ?: return@after) ?: return@after
                relabel(root, follow.name, GuestFollowListStrings.resolve(activity).following)
                root.setOnClickListener {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, "bilibili://space/${follow.mid}".toUri()).setPackage(activity.packageName))
                }
            }
        }
        opener = { context -> routeTo.invoke(null, build.invoke(newBuilder.newInstance(ROUTE)), context) }
        env.reportStatus("guest_follow_page", "installed")
    }

    internal companion object {
        const val FRAGMENT = "com.bilibili.relation.blacklist.BlackListFragment"
        const val ACTIVITY = "com.bilibili.relation.blacklist.BlackListActivity"
        const val ROUTE = "bilibili://user_center/blackList"
        const val EXTRA = "bil.local.follow.list"
        private const val PENDING_MS = 10_000L

        /** The reused row says when a user was blocked; on the local page every visible line except the name says "following". */
        fun relabel(root: View, name: String, following: String) {
            when (root) {
                is ViewGroup -> for (index in 0 until root.childCount) relabel(root.child(index), name, following)
                is TextView -> if (root.isVisible && root.textToString() != name) root.text = following
            }
        }
    }
}

/**
 * The 关注 count on the Compose mine page (8.92+) for a guest. Its click is a Kotlin object whose `toString()` is
 * `ToAttentionClick` (9.0.0: `mineV2.L$q`, 9.16: `mineV2.W$r`, class names renamed per build); signed out, it routes
 * `activity://main/login/` through `BLRouter.routeTo(request, context)`. When that login route comes from this click, the
 * local follow page opens instead and the router call is answered with `RouteResponse(OK, request)`. The caller is
 * recognised on the current stack by its object's `toString()`, so no renamed member is named. Every other login route,
 * and any failure to open the page, keeps the host's behavior.
 */
internal class GuestMineFollowEntry(private val isGuest: () -> Boolean, private val page: GuestFollowListPage) {
    private val clicks = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun install(env: HookEnvironment) {
        val loader = requireNotNull(env.classLoader)
        val names = GuestVideoFavoriteHooks.RouterNames()
        val host = PrivacyHostAccess(loader)
        val router = host.type(names.router)
        val request = host.type(names.request)
        val code = host.type(names.code)
        val routeTo = requireNotNull(PrivacyStructuralResolver.method(router, "routeTo", request, classOf<Context>()))
        val getTargetUri = requireNotNull(PrivacyStructuralResolver.method(request, "getTargetUri"))
        val ok = requireNotNull(code.enumConstants?.firstOrNull { (it as? Enum<*>)?.name == "OK" })
        val newResponse = requireNotNull(Lookup.constructorOrNull(host.type(names.response), code, request))
        val applied = java.util.concurrent.atomic.AtomicBoolean(false)
        env.registrar.exact("guest.mine.follow_entry", router, routeTo.name, *routeTo.parameterTypes) {
            before {
                if (!runCatching { isGuest() }.getOrDefault(false)) return@before
                val routeRequest = argOrNull(0) ?: return@before
                val uri = runCatching { getTargetUri.invoke(routeRequest)?.toString() }.getOrNull() ?: return@before
                if (!uri.startsWith(LOGIN_ROUTE)) return@before
                if (!fromAttentionClick(Thread.currentThread().stackTrace, loader)) return@before
                val handled = runCatching { newResponse.newInstance(ok, routeRequest) }.getOrNull() ?: return@before
                val context = argOrNull(1) as? Context ?: ReflectAccess.currentApplication() ?: return@before
                if (!page.open(context)) return@before
                result = handled
                if (applied.compareAndSet(false, true)) env.reportStatus("guest_mine_follow_entry_applied", "seen")
            }
        }
        env.reportStatus("guest_mine_follow_entry", "installed")
    }

    private fun fromAttentionClick(stack: Array<StackTraceElement>, loader: ClassLoader): Boolean = stack.any { frame ->
        frame.className.startsWith(MINE_PACKAGE) && '$' in frame.className &&
            clicks.getOrPut(frame.className) {
                runCatching { isAttentionClick(loader.loadClass(frame.className)) }.getOrDefault(false)
            }
    }

    internal companion object {
        const val LOGIN_ROUTE = "activity://main/login"
        private const val MINE_PACKAGE = "tv.danmaku.bili.ui.main2.mineV2."
        private const val ATTENTION_CLICK = "ToAttentionClick"

        /** A Kotlin object (a static field holding its own type) whose `toString()` names the 关注 click. */
        fun isAttentionClick(type: Class<*>): Boolean = Lookup.declaredFields(type, makeAccessible = true) {
            it.isStatic && it.type == type
        }.singleOrNull()?.get(null)?.toString() == ATTENTION_CLICK
    }
}

/** Page title and row label in the host's own wording. */
internal data class GuestFollowListStrings(val title: String, val following: String) {
    companion object {
        fun resolve(context: Context): GuestFollowListStrings = when (InjectedUiLocale.resolveEffectiveTag(context)) {
            "zh-CN" -> GuestFollowListStrings("我的关注", "已关注")
            "zh-Hant" -> GuestFollowListStrings("我的關注", "已關注")
            else -> GuestFollowListStrings("Following", "Following")
        }
    }
}
