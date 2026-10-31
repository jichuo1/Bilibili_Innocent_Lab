package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import android.app.Activity
import android.content.Context
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.highcapable.betterandroid.ui.extension.component.hostActivity
import com.highcapable.kavaref.extension.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import org.json.JSONObject

/** 8.90.2: retain the official coroutine, lifecycle checks and success popup, replacing only login and writes. */
internal class GuestNativeWatchLaterAddHooks(private val library: GuestLibraryRuntime, private val isGuest: () -> Boolean) {
    fun install(env: HookEnvironment) {
        val host = PrivacyHostAccess(requireNotNull(env.classLoader))
        val manager = host.type("tv.danmaku.bili.ui.watchlater.widget.WatchLaterManagerV2")
        val continuation = host.type("kotlin.coroutines.Continuation")
        val suspended = host.static(host.type("kotlin.coroutines.intrinsics.IntrinsicsKt"), "getCOROUTINE_SUSPENDED")
        val toastType = host.type("tv.danmaku.bili.ui.watchlater.data.WatchLaterAddToast")
        // j/k/p do nothing except loginRequire; m/l/r are their verified post-login callbacks.
        // Do not change a global login predicate: report/appeal/manual login share those helpers.
        val entryPoints = listOf(
            Triple("j", "m", arrayOf(classOf<Activity>(), classOf<String>(), classOf<String>(), classOf<String>(), classOf<Int>(), classOf<Boolean>(), classOf<Int>())),
            Triple("k", "l", arrayOf(host.type("androidx.fragment.app.Fragment"), classOf<String>(), classOf<String>(), classOf<String>(), classOf<Int>(), classOf<Int>())),
            Triple("p", "r", arrayOf(classOf<Activity>(), classOf<String>(), classOf<String>(), classOf<Int>(), classOf<Int>()))
        )
        // Resolve the entire adapter before removing a login gate. A missing host member must not
        // leave a partly adapted operation with a network fallback.
        entryPoints.forEach { (entry, callback, types) ->
            requireNotNull(Lookup.methodOrNull(manager, entry, *types))
            val reordered = arrayOf(types[1], types[0], *types.drop(2).toTypedArray())
            requireNotNull(Lookup.methodOrNull(manager, callback, *reordered))
        }
        val requests = listOf("i" to arrayOf(classOf<String>(), classOf<String>(), classOf<String>(), continuation),
            "u" to arrayOf(classOf<String>(), classOf<String>(), continuation))
        requests.forEach { (method, types) -> requireNotNull(Lookup.methodOrNull(manager, method, *types)) }
        requests.forEach { (method, types) ->
            env.registrar.exact("guest.native.later.add.$method", manager, method, *types) {
                before {
                    if (!isGuest()) return@before
                    result = suspended
                    val resources = argOrNull(0) as? String ?: ""
                    val caller = requireNotNull(argOrNull(types.lastIndex))
                    library.nativeRequest({ store ->
                        NativeWatchLaterProtocol.respond("/x/v2/history/toview/add", mapOf("resources" to resources), store) { aid ->
                            library.videoMetadata(aid, 0) ?: PrivacyVideoMetadata.fetch(host, aid)
                        }
                    }) { outcome ->
                        val context = requireNotNull(host.call(caller, "getContext"))
                        if (host.static(host.type("kotlinx.coroutines.JobKt"), "isActive", context) == false) return@nativeRequest
                        val reply = outcome.mapCatching { json ->
                            check(JSONObject(json).getInt("code") == 0)
                            host.create(toastType).also {
                                host.call(it, "setShowToast", true)
                                host.call(it, "setIconType", 1)
                                host.call(it, "setToast", "已添加至稍后再看")
                                host.call(it, "setJumpText", "查看")
                                host.call(it, "setAppJumpLink", "bilibili://main/playset/watch-later")
                                host.call(it, "setAvids", resources)
                            }
                        }
                        val value = reply.getOrElse { failure ->
                            env.logError("guest_native_watch_add", "[BIL] Native local watch-later add failed")
                            host.static(host.type("kotlin.ResultKt"), "createFailure", failure)
                        }
                        host.call(caller, "resumeWith", value)
                    }
                }
            }
        }
        entryPoints.forEach { (entry, callback, types) ->
            env.registrar.exact("guest.native.later.entry.$entry", manager, entry, *types) {
                before {
                    if (!isGuest()) return@before
                    result = null
                    val arguments = listOf(argOrNull(1), argOrNull(0)) + (2 until types.size).map { argOrNull(it) }
                    host.static(manager, callback, *arguments.toTypedArray())
                }
            }
        }
        // Older list/menu callers use this singleton instead of WatchLaterManagerV2.
        env.registrar.exact("guest.native.later.entry.legacy", host.type("te6.b"), "c", classOf<String>(), classOf<Context>(), classOf<String>()) {
            before {
                if (!isGuest()) return@before
                result = null
                val activity = (argOrNull(1) as? Context)?.hostActivity ?: return@before
                val singleton = requireNotNull(Lookup.fieldOrNull(manager, "a")).get(null)
                host.call(requireNotNull(singleton), "j", activity, argOrNull(0), argOrNull(2), "", 0, true, 0)
            }
        }
        env.reportStatus("guest_native_watch_later_add", "adapted")
    }
}
