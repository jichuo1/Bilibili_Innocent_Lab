package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.net.toUri
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.hostActivity
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.ref.WeakReference

/** The host follow entry is H5. Reuse its native relation-list container and cells in a separately tagged local route. */
internal class GuestNativeFollowListHooks(private val library: GuestLibraryRuntime, private val isGuest: () -> Boolean) {
    fun install(env: HookEnvironment) {
        val host = PrivacyHostAccess(requireNotNull(env.classLoader))
        val page = host.type(ACTIVITY)
        fun local(activity: Activity?) = isGuest() && activity?.intent?.getBooleanExtra(EXTRA, false) == true
        fun field(target: Any, name: String) = requireNotNull(Lookup.fieldOrNull(target.javaClass, name)).get(target)
        val pages = java.util.WeakHashMap<Activity, Unit>()
        fun refresh(activity: Activity) {
            val weak = WeakReference(activity)
            library.nativeRequest({ it.readSnapshot().collections.follows }) { outcome ->
                val live = weak.get()?.takeUnless { it.isFinishing || it.isDestroyed } ?: return@nativeRequest
                outcome.fold({ follows ->
                    val rows = follows.map { follow -> host.create(host.type("com.bilibili.relation.api.Attention")).also { row ->
                        requireNotNull(Lookup.fieldOrNull(row.javaClass, "mid")).setLong(row, follow.mid)
                        requireNotNull(Lookup.fieldOrNull(row.javaClass, "uname")).set(row, follow.name)
                        requireNotNull(Lookup.fieldOrNull(row.javaClass, "face")).set(row, follow.face)
                    } }
                    host.call(requireNotNull(field(live, "D")), "setList", rows)
                    host.call(live, if (rows.isEmpty()) "showEmpty" else "X8")
                }, { host.call(live, "P") })
            }
        }
        library.observeCollections { pages.keys.toList().filter(::local).forEach(::refresh) }
        env.registrar.exact("guest.native.follow.load", page, "g9") {
            before {
                val activity = instance as? Activity
                if (!local(activity)) return@before
                result = null
                pages[requireNotNull(activity)] = Unit
                refresh(activity)
            }
        }
        // The reused container's blacklist swipe action must never be attached on this local route.
        env.registrar.exact("guest.native.follow.swipe", page, "Y8") {
            before { if (local(instance as? Activity)) result = null }
        }
        env.registrar.exact("guest.native.follow.title", page, "onCreate", classOf<Bundle>()) {
            after { (instance as? Activity)?.takeIf(::local)?.title = "我的关注" }
        }
        val adapter = host.type("$ACTIVITY\$e")
        val holder = host.type("androidx.recyclerview.widget.RecyclerView\$ViewHolder")
        env.registrar.exact("guest.native.follow.bind", adapter, "onBindViewHolder", holder, classOf<Int>()) {
            after {
                val target = argOrNull(0) ?: return@after
                val root = requireNotNull(Lookup.fieldOrNull(holder, "itemView")).get(target) as View
                val activity = root.context.hostActivity ?: return@after
                if (!local(activity)) return@after
                val row = (field(requireNotNull(instance), "a") as List<*>).getOrNull(argOrNull(1) as Int) ?: return@after
                val mid = requireNotNull(Lookup.fieldOrNull(row.javaClass, "mid")).getLong(row)
                val name = field(row, "uname") as String
                val cell = requireNotNull(field(target, "a"))
                val action = field(cell, "c") as TextView
                action.text = "已关注"
                action.contentDescription = "取消关注$name"
                root.setOnClickListener {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, "bilibili://space/$mid".toUri()).setPackage(activity.packageName))
                }
                action.setOnClickListener {
                    // Host AppCompat dialog uses the same typography, buttons and theme as its native screens.
                    val builderType = host.type("androidx.appcompat.app.AlertDialog\$Builder")
                    val builder = requireNotNull(Lookup.constructorOrNull(builderType, classOf<Context>())).newInstance(activity)
                    host.call(builder, "setMessage", "确定取消关注$name？")
                    host.call(builder, "setNegativeButton", "取消", DialogInterface.OnClickListener { _, _ -> })
                    val weak = WeakReference(activity)
                    host.call(builder, "setPositiveButton", "确定", DialogInterface.OnClickListener { _, _ ->
                        library.nativeRequest({ data -> data.unfollow(mid) }) { outcome ->
                            weak.get()?.takeUnless { it.isFinishing || it.isDestroyed }?.let { live ->
                                if (outcome.isSuccess) refresh(live) else host.call(live, "P")
                            }
                        }
                    })
                    host.call(builder, "show")
                }
            }
        }
        env.reportStatus("guest_native_follow_list", "adapted-native-container")
    }
    companion object {
        const val ACTIVITY = "com.bilibili.relation.blacklist.BlackListActivity"
        const val EXTRA = "bil.local.follow.list"
    }
}
