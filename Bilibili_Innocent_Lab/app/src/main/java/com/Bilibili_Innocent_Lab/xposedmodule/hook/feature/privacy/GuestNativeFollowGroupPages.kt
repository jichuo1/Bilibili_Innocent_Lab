package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import android.app.Activity
import android.os.Bundle
import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup

/** Installs dormant page and route guards before the local follow-group menu is made available. */
internal class GuestNativeFollowGroupPages(
    private val library: GuestLibraryRuntime,
    private val isGuest: () -> Boolean,
    private val bindings: GuestFollowGroupBindings<GuestLibraryRuntime.Token>
) {
    fun install(env: HookEnvironment) {
        var stage = "classes"
        try {
            val host = PrivacyHostAccess(requireNotNull(env.classLoader))
            val fragment = host.type("androidx.fragment.app.Fragment")
            val dialog = host.type("com.bilibili.relation.group.AttentionGroupDialog")
            val editor = host.type("com.bilibili.relation.group.CreateGroupFragment")
            val service = host.type("com.bilibili.relation.api.RelationService")
            val factory = host.type("com.bilibili.okretro.ServiceGenerator")
            val call = host.type("com.bilibili.okretro.call.BiliCall")
            stage = "fragment_types"
            require(fragment.isAssignableFrom(dialog) && fragment.isAssignableFrom(editor))
            stage = "fragment_access"
            val arguments = requireNotNull(Lookup.inheritedMethodOrNull(fragment, "getArguments"))
            val activity = requireNotNull(Lookup.inheritedMethodOrNull(fragment, "getActivity"))
            fun marker(page: Any): String? {
                val args = arguments.invoke(page) as? Bundle
                val intent = (activity.invoke(page) as? Activity)?.intent
                val key = GuestFollowGroupRouteHooks.MARKER
                val inArguments = args?.containsKey(key) == true
                val inIntent = intent?.hasExtra(key) == true
                return GuestFollowGroupRouteHooks.pageMarker(inArguments, if (inArguments) args.getString(key) else null,
                    inIntent, if (inIntent) intent.getStringExtra(key) else null, if (!inArguments && !inIntent) isGuest() else false)
            }
            GuestFollowGroupPageServices(bindings, ::marker).install(env.registrar,
                GuestFollowGroupPageServices.Types(dialog, editor, service, factory, call, View::class.java, Bundle::class.java,
                    android.view.LayoutInflater::class.java, android.view.ViewGroup::class.java, android.view.Menu::class.java,
                    android.view.MenuInflater::class.java)) { stage = it }
            GuestFollowGroupRouteHooks(::marker) { value ->
                bindings.lookup(value)?.let { library.accepts(it.token) } == true
            }.install(env.registrar, GuestFollowGroupRouteHooks.Types(
                host.type("com.bilibili.lib.blrouter.BLRouter"), host.type("com.bilibili.lib.blrouter.RouteRequest"),
                host.type("com.bilibili.lib.blrouter.RouteRequest\$Builder"), host.type("com.bilibili.lib.blrouter.MutableBundleLike"),
                fragment, dialog)) { stage = it }
        } catch (failure: Exception) {
            runCatching { env.reportStatus("guest_follow_group_install_failure", "$stage:${failure.javaClass.simpleName}") }
            throw failure
        }
    }
}
