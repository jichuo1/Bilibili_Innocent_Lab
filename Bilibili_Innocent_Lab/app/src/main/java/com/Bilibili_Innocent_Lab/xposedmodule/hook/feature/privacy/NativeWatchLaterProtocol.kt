package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import org.json.JSONArray
import org.json.JSONObject

/** Replies are consumed by the official watch-later screen; no request reaches an account endpoint. */
internal object NativeWatchLaterProtocol {
    fun handles(path: String) = path == "/x/v2/history/toview" || path.startsWith("/x/v2/history/toview/") || path == "/x/v3/fav/toview/adds"
    fun isAdd(path: String) = path == "/x/v2/history/toview/add" || path == "/x/v3/fav/toview/adds"

    /** `resources` (`aid[:type],...`, batch) or `aid` (single add); every id must be positive. */
    fun resourceIds(params: Map<String, String>): Set<Long> = params["resources"].orEmpty().ifBlank { params["aid"].orEmpty() }
        .split(',').filter { it.isNotBlank() }.map { it.substringBefore(':').toLong().also { id -> require(id > 0) } }.toSet()
    private fun obj(vararg values: Pair<String, Any?>) = JSONObject().apply { values.forEach { put(it.first, it.second ?: JSONObject.NULL) } }
    private fun row(v: LocalVirtualLibrary.Video) = obj(
        "aid" to v.aid, "cid" to v.cid, "card_type" to 0, "title" to v.title, "pic" to v.cover.orEmpty(),
        "duration" to v.durationMs / 1000, "progress" to if (v.isComplete) -1 else v.positionMs / 1000,
        "uri" to "bilibili://video/${v.aid}?cid=${v.cid}", "arc_state" to 0, "show_up" to true,
        "owner" to obj("mid" to v.authorMid, "name" to v.authorName, "face" to v.authorFace),
        "right_text" to "${v.durationMs / 60000}:${(v.durationMs / 1000 % 60).toString().padStart(2, '0')}",
        "itemId" to "local-${v.aid}")

    fun respond(path: String, params: Map<String, String>, library: LocalVirtualLibrary,
        metadata: (Long) -> LocalVirtualLibrary.Video? = { null }): String {
        val snapshot = library.readSnapshot()
        val state = snapshot.collections
        fun resources(): Set<Long> = resourceIds(params)
        val data: Any = when (path) {
            "/x/v2/history/toview", "/x/v2/history/toview/v2/list" -> {
                var list = state.watchLater.distinctBy { it.aid }
                // Official V4 uses field 1 for all and 10 for unfinished; asc reverses addition order.
                if (params["sort_field"] == "10") list = list.filterNot { it.isComplete }
                if (params["asc"] == "true" || params["asc"] == "1") list = list.reversed()
                val first = params["start_key"].isNullOrBlank()
                obj("count" to list.size, "show_count" to "${list.size}个视频", "has_more" to false,
                    "next_key" to "", "split_key" to "", "play_url" to list.firstOrNull()?.let { "bilibili://video/${it.aid}?cid=${it.cid}" }.orEmpty(),
                    "list" to JSONArray().apply { if (first) list.forEach { put(row(it)) } })
            }
            "/x/v2/history/toview/v2/dels", "/x/v2/history/toview/del" -> {
                val ids = resources()
                require(ids.isNotEmpty())
                library.writeSnapshot(snapshot.copy(collections = state.copy(watchLater = state.watchLater.filterNot { it.aid in ids })))
                JSONObject()
            }
            "/x/v2/history/toview/clear" -> {
                // Host offers watched (1) and invalid (2); local records have no invalid remote entries.
                val remaining = when (params["clean_type"]) {
                    "1" -> state.watchLater.filterNot { it.isComplete }
                    "2" -> state.watchLater
                    null, "" -> emptyList()
                    else -> error("Unknown clean type")
                }
                library.writeSnapshot(snapshot.copy(collections = state.copy(watchLater = remaining)))
                JSONObject()
            }
            "/x/v2/history/toview/copy", "/x/v2/history/toview/move" -> {
                val ids = resources()
                require(ids.isNotEmpty())
                val targetId = params["tar_media_id"]?.toLongOrNull()
                val target = requireNotNull(state.favorites.keys.firstOrNull { NativeFavoriteFolders.id(state, it) == targetId })
                val selected = ids.map { aid -> requireNotNull(state.watchLater.firstOrNull { it.aid == aid }) }
                val folders = state.favorites + (target to (selected + state.favorites.getValue(target)).distinctBy { it.aid })
                val remaining = if (path.endsWith("/move")) state.watchLater.filterNot { it.aid in ids } else state.watchLater
                // One write makes a move atomic even if persistence fails.
                library.writeSnapshot(snapshot.copy(collections = state.copy(favorites = folders, watchLater = remaining)))
                JSONObject()
            }
            "/x/v2/history/toview/add", "/x/v3/fav/toview/adds" -> {
                val ids = resources()
                require(ids.isNotEmpty())
                val known = state.history + state.watchLater + state.favorites.values.flatten()
                val videos = ids.map { aid -> requireNotNull(known.firstOrNull { it.aid == aid } ?: metadata(aid)) }
                val remaining = state.watchLater.filterNot { it.aid in ids }
                library.writeSnapshot(snapshot.copy(collections = state.copy(watchLater = videos + remaining)))
                // The fields of the host's WatchLaterAddToast: with show_toast it shows its own "added, view" popup.
                obj("show_toast" to true, "icon_type" to 1, "toast" to "已添加至稍后再看", "jump_text" to "查看",
                    "app_jump_link" to "bilibili://main/playset/watch-later", "avids" to ids.joinToString(","))
            }
            else -> return obj("code" to -400, "ttl" to 1, "message" to "不支持的本地稍后再看操作", "data" to JSONObject()).toString()
        }
        return obj("code" to 0, "ttl" to 1, "message" to "0", "data" to data).toString()
    }
}
