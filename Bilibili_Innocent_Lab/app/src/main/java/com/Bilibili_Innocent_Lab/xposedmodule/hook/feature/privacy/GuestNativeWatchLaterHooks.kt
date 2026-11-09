package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import android.os.Bundle
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf

/** Keep the official activity and KNTR Compose screen; adapt its repository before any account request. */
internal class GuestNativeWatchLaterHooks(private val library: GuestLibraryRuntime, private val isGuest: () -> Boolean) {
    private enum class Reply { WATCH_LIST, FOLDERS, BOOLEAN, UNIT }

    fun install(env: HookEnvironment) {
        val host = PrivacyHostAccess(requireNotNull(env.classLoader))
        val target = requireNotNull(Lookup.constructorOrNull(host.type("com.bilibili.lib.ui.Target"), classOf<Class<*>>(), classOf<Bundle>()))
        val page = host.type("tv.danmaku.bili.ui.watchlater.WatchLaterFragmentV4")
        env.registrar.exact("guest.native.later.route", host.type("com.bilibili.lib.ui.RouteConstKt"), "findRoute",
            host.type("com.bilibili.lib.blrouter.BLRouter"), host.type("com.bilibili.lib.blrouter.RouteRequest")) {
            before {
                if (!isGuest()) return@before
                val request = argOrNull(1) ?: return@before
                if (host.call(request, "getTargetUri").toString().trimEnd('/') != "bilibili://main/playset/watch-later") return@before
                result = target.newInstance(page, Bundle().apply { putString("activity_base_toolbar", "true") })
            }
        }
        // The official repository exposes suspend functions. Complete them locally before enqueue,
        // preserving its state machine and cancellation semantics even when the host rejects guest calls.
        val continuation = host.type("kotlin.coroutines.Continuation")
        val suspended = host.static(host.type("kotlin.coroutines.intrinsics.IntrinsicsKt"), "getCOROUTINE_SUSPENDED")
        val repository = host.type("kntr.watch.later.watchlaterold.api.WatchLaterApiKt")
        val modelCompanion = requireNotNull(Lookup.fieldOrNull(host.type("sz5.m"), "Companion")).get(null)
        val serializer = requireNotNull(host.call(requireNotNull(modelCompanion), "serializer"))
        val jsonReader = requireNotNull(Lookup.fieldOrNull(host.type("kotlinx.serialization.json.Json"), "Default")).get(null)
        val success = requireNotNull(Lookup.constructorOrNull(host.type("com.bilibili.ktor.KApiResponse\$c"), classOf<Any>()))
        val folderCompanion = requireNotNull(Lookup.fieldOrNull(host.type("kntr.watch.later.watchlaterold.edit.u"), "Companion")).get(null)
        val folderSerializer = requireNotNull(host.call(requireNotNull(folderCompanion), "serializer"))
        val unit = requireNotNull(Lookup.fieldOrNull(host.type("kotlin.Unit"), "INSTANCE")).get(null)
        fun reply(caller: Any, path: String, params: Map<String, String>, kind: Reply) {
            library.nativeRequest({ store ->
                if (kind == Reply.FOLDERS) NativeFavoriteProtocol.respond(path, params, store)
                else NativeWatchLaterProtocol.respond(path, params, store)
            }) { outcome ->
                val context = requireNotNull(host.call(caller, "getContext"))
                if (host.static(host.type("kotlinx.coroutines.JobKt"), "isActive", context) == false) return@nativeRequest
                val parsed = outcome.mapCatching { json ->
                    val response = org.json.JSONObject(json)
                    check(response.getInt("code") == 0) { "Local watch-later operation rejected" }
                    when (kind) {
                    Reply.WATCH_LIST -> {
                        // KNTR's serializer is strict; count/cid belong to the legacy HTTP shape.
                        val data = response.getJSONObject("data")
                        data.remove("count")
                        val rows = data.getJSONArray("list")
                        repeat(rows.length()) { rows.getJSONObject(it).remove("cid") }
                        success.newInstance(host.call(requireNotNull(jsonReader), "decodeFromString", serializer, data.toString()))
                    }
                    Reply.FOLDERS -> {
                        val data = response.getJSONObject("data")
                        val rows = data.getJSONArray("list")
                        // FavFolder is a different strict KNTR model from the HTTP favorite card.
                        val keys = arrayOf("id", "title", "cover", "intro", "media_count", "fav_state", "attr", "type", "link")
                        repeat(rows.length()) { index -> rows.put(index, org.json.JSONObject(rows.getJSONObject(index), keys)) }
                        success.newInstance(host.call(requireNotNull(jsonReader), "decodeFromString", folderSerializer, data.toString()))
                    }
                    Reply.BOOLEAN -> true
                    Reply.UNIT -> success.newInstance(unit)
                    }
                }
                val value = parsed.getOrElse { failure ->
                    env.logError("guest_native_watch_response", "[BIL] Native local watch-later response failed")
                    host.static(host.type("kotlin.ResultKt"), "createFailure", failure)
                }
                host.call(caller, "resumeWith", value)
            }
        }
        env.registrar.exact("guest.native.later.load", repository, "e", classOf<String>(), classOf<Boolean>(), classOf<Int>(), classOf<String>(), continuation) {
            before {
                if (!isGuest()) return@before
                result = suspended
                reply(requireNotNull(argOrNull(4)), "/x/v2/history/toview/v2/list", mapOf(
                    "start_key" to argOrNull(0).toString(), "asc" to argOrNull(1).toString(),
                    "sort_field" to argOrNull(2).toString(), "split_key" to argOrNull(3).toString()), Reply.WATCH_LIST)
            }
        }
        env.registrar.exact("guest.native.later.delete", repository, "c", classOf<String>(), continuation) {
            before {
                if (!isGuest()) return@before
                result = suspended
                reply(requireNotNull(argOrNull(1)), "/x/v2/history/toview/v2/dels", mapOf("resources" to argOrNull(0).toString()), Reply.BOOLEAN)
            }
        }
        env.registrar.exact("guest.native.later.clear", repository, "b", classOf<Int>(), continuation) {
            before {
                if (!isGuest()) return@before
                result = suspended
                reply(requireNotNull(argOrNull(1)), "/x/v2/history/toview/clear", mapOf("clean_type" to argOrNull(0).toString()), Reply.BOOLEAN)
            }
        }
        val favorites = host.type("kntr.watch.later.watchlaterold.api.FavoritesApiKt")
        env.registrar.exact("guest.native.later.folders", favorites, "e", classOf<Int>(), classOf<Int>(), continuation) {
            before {
                if (!isGuest()) return@before
                result = suspended
                reply(requireNotNull(argOrNull(2)), "/x/v3/fav/folder/created/list-all", emptyMap(), Reply.FOLDERS)
            }
        }
        mapOf("b" to "copy", "c" to "move").forEach { (method, action) ->
            env.registrar.exact("guest.native.later.$action", favorites, method, classOf<String>(), classOf<Long>(), continuation) {
                before {
                    if (!isGuest()) return@before
                    result = suspended
                    reply(requireNotNull(argOrNull(2)), "/x/v2/history/toview/$action", mapOf(
                        "resources" to argOrNull(0).toString(), "tar_media_id" to argOrNull(1).toString()), Reply.UNIT)
                }
            }
        }
        env.reportStatus("guest_native_watch_later", "adapted")
    }
}
