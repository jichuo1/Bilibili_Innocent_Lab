package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import org.json.JSONArray
import org.json.JSONObject

/** JSON consumed by the host's own favorite parsers. Runs inside the local library transaction queue. */
internal object NativeFavoriteProtocol {
    fun handles(path: String) = path.startsWith("/x/v3/fav/") || path == "/x/v2/history/shadow"
    private fun obj(vararg values: Pair<String, Any?>) = JSONObject().apply { values.forEach { put(it.first, it.second ?: JSONObject.NULL) } }
    private fun array(values: Iterable<Any>) = JSONArray().apply { values.forEach(::put) }
    private fun row(v: LocalVirtualLibrary.Video) = obj("oid" to v.aid, "id" to v.aid, "otype" to 2, "type" to 2,
        "title" to v.title, "cover" to v.cover.orEmpty(), "link" to "bilibili://video/${v.aid}?cid=${v.cid}",
        "duration" to v.durationMs / 1000, "fav_time" to v.updatedAtMs / 1000, "fav_state" to 1,
        "playback_position" to v.positionMs / 1000, "playback_desc" to "",
        "ugc" to obj("first_cid" to v.cid), "upper" to obj("mid" to v.authorMid, "name" to v.authorName),
        "cnt_info" to obj("play" to 0, "collect" to 0), "left_text" to "",
        "right_text" to "${v.durationMs / 60000}:${(v.durationMs / 1000 % 60).toString().padStart(2, '0')}")

    private fun orderedFavorites(state: LocalVirtualLibrary.Collections): List<LocalVirtualLibrary.Video> {
        val videos = state.favorites.values.flatten().distinctBy { it.aid }
        val byId = videos.associateBy { it.aid }
        val order = state.favoriteOrder.toSet()
        return videos.filter { it.aid !in order } + state.favoriteOrder.mapNotNull(byId::get)
    }

    /** The favorite writes that add a video, so the caller can fetch metadata for an archive this session never opened. */
    fun isAdd(path: String, params: Map<String, String>) =
        path == "/x/v3/fav/resource/batch-deal" && params["add_media_ids"].orEmpty().isNotBlank()

    /** Archive ids of `aid:type` resources; only videos (type 2) are local. */
    fun resourceIds(params: Map<String, String>): List<Long> = params["resources"].orEmpty().split(',')
        .filter { it.isNotBlank() && it.substringAfter(':', "2") == "2" }.map { it.substringBefore(':').toLong() }

    fun respond(path: String, params: Map<String, String>, library: LocalVirtualLibrary,
                metadata: (Long) -> LocalVirtualLibrary.Video? = { null }): String {
        val snapshot = library.readSnapshot()
        val state = snapshot.collections
        fun id(key: String) = params[key]?.toLongOrNull() ?: 0L
        fun folder(fid: Long): String = requireNotNull(state.favorites.keys.firstOrNull { NativeFavoriteFolders.id(state, it) == fid }) { "Unknown local folder" }
        // With `rid`, `fav_state` says whether that video is in the folder (the host's folder picker ticks from it).
        val rid = id("rid")
        fun folderRow(name: String): JSONObject {
            val videos = state.favorites.getValue(name).distinctBy { it.aid }
            val fid = NativeFavoriteFolders.id(state, name)
            return obj("fid" to fid, "id" to fid, "mid" to 0, "type" to 11,
                "attr" to if (name == LocalVirtualLibrary.DEFAULT_FOLDER) 1 else 3,
                "is_top" to (state.folderInfo[name]?.pinned == true), "title" to name, "intro" to state.folderInfo[name]?.intro.orEmpty(), "media_count" to videos.size,
                "cover" to (state.folderInfo[name]?.cover?.takeIf { it.isNotEmpty() } ?: videos.firstOrNull()?.cover.orEmpty()),
                "link" to "bilibili://music/playlist/detail/$fid?type=11", "recent_fav" to array(videos.take(3).map(::row)),
                "upper" to obj("mid" to 0, "name" to "本地账号"), "cnt_info" to obj("collect" to 0, "play" to 0),
                "fav_state" to if (rid <= 0 || videos.any { it.aid == rid }) 1 else 0)
        }
        fun videos(): List<LocalVirtualLibrary.Video> {
            val source = if (id("media_id") > 0) state.favorites.getValue(folder(id("media_id")))
                else orderedFavorites(state)
            val keyword = params["keyword"].orEmpty()
            return source.filter { it.title.contains(keyword, true) }
        }
        fun list(): JSONObject {
            val source = videos()
            val first = id("pn") <= 1 && id("start_oid") == 0L
            return obj("total" to source.size, "has_more" to false, "has_invalid" to false,
                "list" to array(if (first) source.map(::row) else emptyList()), "next_oid" to 0, "next_otype" to 0, "next_score" to 0)
        }
        fun resources(): Set<Long> = params["resources"].orEmpty().split(',').filter { it.isNotBlank() }
            .map { it.substringBefore(':').toLong() }.toSet()
        val data: Any = when (path) {
            "/x/v2/history/shadow" -> false
            "/x/v3/fav/tab" -> {
                val tabs = array(listOf(obj("tab_id" to 100, "tab_name" to "收藏夹", "uri" to "bilibili://user_center/favorite_v2/favorite/folders"),
                    obj("tab_id" to 2, "tab_name" to "视频", "uri" to "bilibili://user_center/favorite_v2/favorite/items")))
                // The activity forwards the URI; the top fragment reads sub_tab from route arguments.
                val uri = "bilibili://user_center/favorite_v2/favorite?sub_tab=" +
                    java.net.URLEncoder.encode(tabs.toString(), "UTF-8") + "&default_tab=100"
                obj("default_tab" to 1, "tabs" to array(listOf(obj("tab_id" to 1,
                    "tab_name" to "我的收藏", "uri" to uri, "red_dot" to false, "sub_tab" to tabs))))
            }
            "/x/v3/fav/tab/my_fav" -> obj("count" to state.favorites.size, "list" to array(state.favorites.keys.sortedWith(compareByDescending<String> { it == LocalVirtualLibrary.DEFAULT_FOLDER }.thenByDescending { state.folderInfo[it]?.pinned == true }).map(::folderRow)))
            "/x/v3/fav/tab/fav", "/x/v3/fav/tab/fav/search", "/x/v3/fav/folder/resources" -> list()
            "/x/v3/fav/resource/list" -> obj("info" to folderRow(folder(id("media_id"))),
                "medias" to array(if (id("pn") <= 1) videos().map(::row) else emptyList()), "has_more" to false)
            "/x/v3/fav/folder/info" -> folderRow(folder(id("media_id")))
            "/x/v3/fav/folder/created/list", "/x/v3/fav/folder/created/list-all" -> obj("count" to state.favorites.size,
                "list" to array(state.favorites.keys.sortedWith(compareByDescending<String> { it == LocalVirtualLibrary.DEFAULT_FOLDER }.thenByDescending { state.folderInfo[it]?.pinned == true }).map(::folderRow)), "has_more" to false)
            "/x/v3/fav/resource/ids" -> array(videos().map { obj("id" to it.aid, "type" to 2) })
            "/x/v3/fav/tab/fol", "/x/v3/fav/folder/collected/list" -> obj("list" to JSONArray(), "has_more" to false, "total" to 0, "count" to 0)
            "/x/v3/fav/folder/whitelist", "/x/v3/fav/folder/clean-state" -> 0
            "/x/v3/fav/tab/report", "/x/v3/fav/tab/red_dot/clean", "/x/v3/fav/statistics/count",
            "/x/v3/fav/tab/clean_invalid", "/x/v3/fav/resource/clean" -> JSONObject()
            "/x/v3/fav/folder/add", "/x/v3/fav/folder/edit" -> {
                val name = params["title"].orEmpty().trim()
                val creating = path.endsWith("/add")
                val previous = if (creating) null else folder(id("media_id"))
                val fid = if (creating) NativeFavoriteFolders.id(name) else id("media_id")
                val info = LocalVirtualLibrary.FolderInfo(fid, params["intro"].orEmpty(), params["cover"].orEmpty(),
                    pinned = previous?.let { state.folderInfo[it]?.pinned } == true)
                if (creating) {
                    require(state.favorites.keys.none { NativeFavoriteFolders.id(state, it) == fid })
                    library.createFavoriteFolder(name, info)
                } else library.editFavoriteFolder(requireNotNull(previous), name, info)
                // The legacy native editor consumes PlaySet, including its stable ID and private attr.
                obj("id" to fid, "fid" to fid, "title" to name, "intro" to info.intro, "cover" to info.cover,
                    "attr" to if (name == LocalVirtualLibrary.DEFAULT_FOLDER) 1 else 3, "type" to 11,
                    "media_count" to (previous?.let { state.favorites.getValue(it).distinctBy { video -> video.aid }.size } ?: 0))
            }
            "/x/v3/fav/folder/add_top" -> {
                val name = folder(id("fid"))
                val pinned = when (params["add_top"]) { "true", "1" -> true; "false", "0" -> false; else -> error("Invalid pin state") }
                val info = (state.folderInfo[name] ?: LocalVirtualLibrary.FolderInfo(NativeFavoriteFolders.id(state, name))).copy(pinned = pinned)
                val folders = if (pinned) linkedMapOf(name to state.favorites.getValue(name)).apply { putAll(state.favorites) } else state.favorites
                library.writeSnapshot(snapshot.copy(collections = state.copy(favorites = folders, folderInfo = state.folderInfo + (name to info))))
                JSONObject()
            }
            "/x/v3/fav/resource/sort" -> {
                val name = if (id("media_id") > 0) folder(id("media_id")) else null
                val source = if (name == null) orderedFavorites(state) else state.favorites.getValue(name).distinctBy { it.aid }
                val order = source.map { it.aid }.toMutableList()
                val moves = params["sort"].orEmpty().split(',').filter { it.isNotBlank() }
                require(moves.isNotEmpty())
                moves.forEach { record ->
                    val fields = record.split(':').map(String::toLong)
                    require(fields.size == 4 && fields[3] == 2L && (fields[1] == 2L || fields[0] == 0L && fields[1] == 0L))
                    val (previous, _, moved) = fields
                    require(moved in order && (previous == 0L || previous in order) && previous != moved)
                    order.remove(moved)
                    order.add(if (previous == 0L) 0 else order.indexOf(previous) + 1, moved)
                }
                val next = if (name == null) state.copy(favoriteOrder = order) else state.copy(
                    favorites = state.favorites + (name to order.flatMap { aid -> state.favorites.getValue(name).filter { it.aid == aid } }))
                library.writeSnapshot(snapshot.copy(collections = next))
                ""
            }
            "/x/v3/fav/tab/dels", "/x/v3/fav/resource/batch-del" -> {
                val ids = resources()
                val name = if (id("media_id") > 0) folder(id("media_id")) else null
                val folders = state.favorites.mapValues { (key, values) ->
                    if (name == null || key == name) values.filterNot { it.aid in ids } else values
                }
                library.writeSnapshot(snapshot.copy(collections = state.copy(favorites = folders)))
                JSONObject()
            }
            "/x/v3/fav/resource/batch-deal" -> {
                // The host's folder picker sends only the changed folders; "0" is the default folder.
                fun folders(key: String) = params[key].orEmpty().split(',').filter { it.isNotBlank() }
                    .map { if (it == "0") LocalVirtualLibrary.DEFAULT_FOLDER else folder(it.toLong()) }.toSet()
                val added = folders("add_media_ids")
                val removed = folders("del_media_ids")
                val ids = resourceIds(params)
                require(ids.isNotEmpty() && (added.isNotEmpty() || removed.isNotEmpty()))
                ids.forEach { aid ->
                    val current = state.favorites.filterValues { videos -> videos.any { it.aid == aid } }.keys
                    val video = state.favorites.values.flatten().firstOrNull { it.aid == aid } ?: requireNotNull(metadata(aid)) { "Unknown archive" }
                    library.setFavoriteFolders(video, current - removed + added)
                }
                obj("prompt" to false, "toast_msg" to "")
            }
            "/x/v3/fav/resource/unfav-all" -> {
                val aid = id("rid")
                require(aid > 0)
                library.writeSnapshot(snapshot.copy(collections = state.copy(
                    favorites = state.favorites.mapValues { (_, videos) -> videos.filterNot { it.aid == aid } })))
                JSONObject()
            }
            "/x/v3/fav/folder/del" -> {
                val ids = params["media_ids"].orEmpty().ifBlank { params["media_id"].orEmpty() }.split(',').map { it.toLong() }
                val names = ids.map(::folder).toSet()
                require(LocalVirtualLibrary.DEFAULT_FOLDER !in names)
                library.writeSnapshot(snapshot.copy(collections = state.copy(favorites = state.favorites - names, folderInfo = state.folderInfo - names)))
                JSONObject()
            }
            "/x/v3/fav/resource/copy", "/x/v3/fav/resource/move" -> {
                val from = folder(id("src_media_id"))
                val to = folder(id("tar_media_id"))
                val ids = resources()
                val selected = state.favorites.getValue(from).filter { it.aid in ids }
                val folders = state.favorites.toMutableMap()
                folders[to] = (selected + folders.getValue(to)).distinctBy { it.aid }
                if (path.endsWith("/move") && from != to) folders[from] = folders.getValue(from).filterNot { it.aid in ids }
                library.writeSnapshot(snapshot.copy(collections = state.copy(favorites = folders)))
                JSONObject()
            }
            else -> return obj("code" to -400, "message" to "此操作暂不支持本地收藏", "data" to JSONObject()).toString()
        }
        return obj("code" to 0, "message" to "0", "data" to data).toString()
    }
}
