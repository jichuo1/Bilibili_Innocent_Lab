package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import android.app.Activity
import android.content.Context
import android.os.Bundle
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.InjectedUiLocale
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.highcapable.betterandroid.ui.extension.component.hostActivity
import com.highcapable.betterandroid.ui.extension.view.toast
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/** Uses the host's follow menu and group dialog with a local, session-bound data source. */
internal class GuestNativeFollowGroupMenu(
    private val library: GuestLibraryRuntime,
    private val bindings: GuestFollowGroupBindings<GuestLibraryRuntime.Token>,
    private val confirmation: GuestHostFollowConfirmation
) : GuestHostFollowConfirmation.FollowedMenu {
    private data class Adapter(
        val host: PrivacyHostAccess,
        val menu: Method,
        val menuCallback: Class<*>,
        val dialogCallback: Class<*>,
        val dialogCallbackField: Field,
        val dialogConstructor: java.lang.reflect.Constructor<*>,
        val setArguments: Method,
        val show: Method,
        val fragmentActivity: Class<*>,
        val fragmentManager: Method
    )
    private lateinit var adapter: Adapter
    private lateinit var env: HookEnvironment
    private val relation = GuestFollowGroupMenuRelationGuard()

    fun install(environment: HookEnvironment) {
        val host = PrivacyHostAccess(requireNotNull(environment.classLoader))
        val helper = host.type("com.bilibili.relation.utils.FollowFlowHelper")
        val menu = requireNotNull(Lookup.declaredMethods(helper, makeAccessible = true) { method ->
            method.name == "showCommonGroupMenu" && Modifier.isStatic(method.modifiers) && method.returnType == Void.TYPE &&
                method.parameterTypes.size == 3 && method.parameterTypes[0] == Context::class.java &&
                method.parameterTypes[1] == String::class.java && method.parameterTypes[2].isInterface
        }.singleOrNull())
        val menuCallback = menu.parameterTypes[2]
        val menuMethods = Lookup.declaredMethods(menuCallback, makeAccessible = true) { !Modifier.isStatic(it.modifiers) }
        require(menuMethods.size == 3 && menuMethods.any { it.name == "a" && it.parameterCount == 0 && it.returnType == Void.TYPE } &&
            menuMethods.any { it.name == "b" && it.parameterCount == 0 && it.returnType == Void.TYPE } &&
            menuMethods.any { it.name == "c" && it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType)) && it.returnType == Void.TYPE })

        val dialog = host.type("com.bilibili.relation.group.AttentionGroupDialog")
        val launcher = requireNotNull(Lookup.declaredMethods(dialog, makeAccessible = true) { method ->
            val p = method.parameterTypes
            Modifier.isStatic(method.modifiers) && method.returnType == Void.TYPE && p.size == 4 &&
                p[0] == Context::class.java && p[1] == Long::class.javaPrimitiveType && p[2] == String::class.java && p[3].isInterface
        }.singleOrNull())
        val dialogCallback = launcher.parameterTypes[3]
        val callbackMethods = Lookup.declaredMethods(dialogCallback, makeAccessible = true) { !Modifier.isStatic(it.modifiers) }
        require(callbackMethods.size == 1 && callbackMethods.single().name == "a" &&
            callbackMethods.single().parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType)) &&
            callbackMethods.single().returnType == Void.TYPE)
        val dialogField = requireNotNull(Lookup.declaredFields(dialog, makeAccessible = true) {
            !Modifier.isStatic(it.modifiers) && it.type == dialogCallback
        }.singleOrNull())
        val constructor = requireNotNull(Lookup.constructorOrNull(dialog))
        val setArguments = requireNotNull(Lookup.inheritedMethodOrNull(dialog, "setArguments", Bundle::class.java))
        val fragmentActivity = host.type("androidx.fragment.app.FragmentActivity")
        val fragmentManager = host.type("androidx.fragment.app.FragmentManager")
        val getFragmentManager = requireNotNull(Lookup.inheritedMethodOrNull(fragmentActivity, "getSupportFragmentManager"))
        val show = requireNotNull(Lookup.inheritedMethodOrNull(dialog, "show", fragmentManager, String::class.java))
        val resolved = Adapter(host, menu, menuCallback, dialogCallback, dialogField, constructor,
            setArguments, show, fragmentActivity, getFragmentManager)
        relation.install(environment.registrar, GuestFollowGroupMenuRelationGuard.Types(
            host.type("com.bilibili.relation.api.RelationApiManager"), host.type("com.bilibili.okretro.BiliApiDataCallback"),
            host.type("com.bilibili.relation.api.Attention"), menuCallback))
        adapter = resolved
        env = environment
    }

    override fun open(target: Any, context: Any, uid: Long, token: Any, expected: WeakReference<Any>,
                      announce: Boolean, done: (Boolean) -> Unit): Boolean {
        val session = token as? GuestLibraryRuntime.Token ?: return false
        val hostContext = context as? Context ?: return false
        val activity = hostContext.hostActivity ?: return false
        if (!adapter.fragmentActivity.isInstance(activity) || activity.isFinishing || activity.isDestroyed) return false
        val weakTarget = WeakReference(target)
        val weakContext = WeakReference(hostContext)
        library.query(session) { state ->
            val current = weakTarget.get() ?: return@query
            val ui = weakContext.get() ?: return@query
            if (!confirmation.bound(current, uid, expected, session) || state.collections.follows.none { it.mid == uid }) return@query
            val actions = actions(weakTarget, weakContext, uid, session, expected, announce, done)
            val valid = {
                weakTarget.get()?.let { confirmation.bound(it, uid, expected, session) } == true
            }
            runCatching {
                relation.within(actions, uid, uid in state.collections.specialFollows, valid) {
                    adapter.menu.invoke(null, ui, uid.toString(), actions)
                }
            }.onSuccess { env.reportStatus("guest_follow_group_menu", "requested") }
                .onFailure { library.failure(session) }
        }
        return true
    }

    private fun actions(target: WeakReference<Any>, context: WeakReference<Context>, uid: Long,
                        token: GuestLibraryRuntime.Token, expected: WeakReference<Any>, announce: Boolean,
                        done: (Boolean) -> Unit): Any =
        Proxy.newProxyInstance(adapter.menuCallback.classLoader, arrayOf(adapter.menuCallback)) { proxy, method, args ->
            if (method.declaringClass == Any::class.java) return@newProxyInstance objectMethod(proxy, method, args)
            val current = target.get() ?: return@newProxyInstance null
            val ui = context.get() ?: return@newProxyInstance null
            if (!confirmation.bound(current, uid, expected, token)) return@newProxyInstance null
            runCatching {
                when (method.name) {
                    "a" -> showDialog(target, context, uid, token, expected)
                    "b" -> confirmation.confirmFromMenu(current, ui, uid, token, expected, announce, done)
                    "c" -> {
                        val special = !(args?.singleOrNull() as? Boolean ?: return@newProxyInstance null)
                        library.setHostSpecialFollow(token, uid, special) {
                            val updated = target.get() ?: return@setHostSpecialFollow
                            val hostContext = context.get() ?: return@setHostSpecialFollow
                            if (!confirmation.bound(updated, uid, expected, token)) return@setHostSpecialFollow
                            expected.get()?.let { adapter.host.call(it, "onSpecialStatusChange", special) }
                            hostContext.toast(specialMessage(hostContext, special))
                        }
                    }
                }
            }.onFailure { library.failure(token) }
            null
        }

    private fun showDialog(target: WeakReference<Any>, context: WeakReference<Context>, uid: Long,
                           token: GuestLibraryRuntime.Token, expected: WeakReference<Any>) {
        val current = target.get() ?: return
        val ui = context.get() ?: return
        val activity: Activity = ui.hostActivity ?: return
        if (!adapter.fragmentActivity.isInstance(activity) || activity.isFinishing || activity.isDestroyed ||
            !confirmation.bound(current, uid, expected, token)) return
        val marker = bindings.register(token, uid)
        val dialog = adapter.dialogConstructor.newInstance()
        val arguments = Bundle().apply {
            putLong("mid", uid)
            putString("spmid", "")
            putString(GuestFollowGroupRouteHooks.MARKER, marker)
        }
        adapter.setArguments.invoke(dialog, arguments)
        val callback = Proxy.newProxyInstance(adapter.dialogCallback.classLoader, arrayOf(adapter.dialogCallback)) { proxy, method, args ->
            if (method.declaringClass == Any::class.java) return@newProxyInstance objectMethod(proxy, method, args)
            val item = target.get() ?: return@newProxyInstance null
            if (!confirmation.bound(item, uid, expected, token)) return@newProxyInstance null
            val special = args?.singleOrNull() as? Boolean ?: return@newProxyInstance null
            runCatching { expected.get()?.let { adapter.host.call(it, "onSpecialStatusChange", special) } }
            null
        }
        adapter.dialogCallbackField.set(dialog, callback)
        if (!confirmation.bound(current, uid, expected, token)) return
        adapter.show.invoke(dialog, adapter.fragmentManager.invoke(activity), "bil_guest_follow_groups")
        expected.get()?.let { adapter.host.call(it, "onGroupClickListener") }
        env.reportStatus("guest_follow_group_dialog", "shown")
    }

    private fun objectMethod(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
        "equals" -> proxy === args?.firstOrNull()
        "hashCode" -> System.identityHashCode(proxy)
        "toString" -> "LocalFollowGroupCallback"
        else -> null
    }

    private fun specialMessage(context: Context, special: Boolean): String =
        when (InjectedUiLocale.resolveEffectiveTag(context)) {
            "zh-CN" -> if (special) "已设为特别关注" else "已取消特别关注"
            "zh-Hant" -> if (special) "已設為特別關注" else "已取消特別關注"
            else -> if (special) "Added to special follows" else "Removed from special follows"
        }
}
