package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import org.json.JSONArray
import org.json.JSONObject

/** Local responses for a single, session-bound host follow-group dialog. The caller must validate its session. */
internal object NativeFollowGroupProtocol {
    private const val USER_GROUPS = "/x/relation/tag/m/tags"
    private const val USER_SELECTION = "/x/relation/tag/user"
    private const val SET_SELECTION = "/x/relation/tags/addUsers"
    private const val CREATE = "/x/relation/tag/create"
    private const val RENAME = "/x/relation/tag/update"
    private const val DELETE = "/x/relation/tag/del"
    private const val SPECIAL_ADD = "/x/relation/tag/special/add"
    private const val SPECIAL_DELETE = "/x/relation/tag/special/del"
    private const val DEFAULT_ID = 0L
    private const val SPECIAL_ID = -10L
    private val paths = setOf(USER_GROUPS, USER_SELECTION, SET_SELECTION, CREATE, RENAME, DELETE, SPECIAL_ADD, SPECIAL_DELETE)

    data class Labels(val default: String, val special: String)

    fun handles(path: String): Boolean = path in paths

    /** A dialog may only inspect or change the author for which it was opened. */
    fun respond(path: String, params: Map<String, String>, library: LocalVirtualLibrary, mid: Long, labels: Labels): String {
        require(handles(path)) { "Unsupported group request" }
        require(mid > 0) { "Invalid author" }
        val state = library.readSnapshot().collections
        require(state.follows.any { it.mid == mid }) { "Author is no longer followed" }

        fun id(key: String): Long {
            val raw = requireNotNull(params[key]) { "Missing $key" }
            val value = requireNotNull(raw.toLongOrNull()) { "Invalid $key" }
            require(value.toString() == raw) { "Invalid $key" }
            return value
        }
        fun author() = require(id("fid") == mid) { "Unexpected author" }
        fun groupId(): Long = id("tagid").also { value ->
            require(value > 0 && state.followGroups.any { it.id == value }) { "Unknown group" }
        }
        fun row(id: Long, name: String, count: Int) = JSONObject().apply {
            put("tagid", id.toString())
            put("name", name)
            put("count", count)
            put("tip", "")
        }
        fun array(values: Iterable<JSONObject>) = JSONArray().apply { values.forEach(::put) }
        fun selection(): JSONObject = JSONObject().apply {
            if (mid in state.specialFollows) put(SPECIAL_ID.toString(), labels.special)
            val chosen = state.followGroupAssignments[mid].orEmpty()
            state.followGroups.filter { it.id in chosen }.forEach { put(it.id.toString(), it.name) }
        }
        val data: Any = when (path) {
            USER_GROUPS -> {
                val groups = state.followGroups.map { group ->
                    row(group.id, group.name, state.followGroupAssignments.values.count { group.id in it })
                }
                val unassigned = state.follows.count { it.mid !in state.followGroupAssignments && it.mid !in state.specialFollows }
                JSONObject().apply {
                    put("all", JSONArray())
                    put("special", array(listOf(row(SPECIAL_ID, labels.special, state.specialFollows.size))))
                    put("list", array(groups))
                    put("default", array(listOf(row(DEFAULT_ID, labels.default, unassigned))))
                }
            }
            USER_SELECTION -> { author(); selection() }
            SET_SELECTION -> {
                val rawMids = params["fids"].orEmpty().split(',')
                require(rawMids.size == 1 && rawMids.single() == mid.toString()) { "Unexpected authors" }
                val rawIds = params["tagids"].orEmpty().split(',')
                val ids = rawIds.map { raw ->
                    val value = requireNotNull(raw.toLongOrNull()) { "Invalid group selection" }
                    require(value.toString() == raw) { "Invalid group selection" }
                    value
                }
                require(ids.isNotEmpty() && ids.size == ids.toSet().size) { "Invalid group selection" }
                require(ids.all { it == DEFAULT_ID || it == SPECIAL_ID || it > 0 }) { "Invalid group selection" }
                require(DEFAULT_ID !in ids || ids.size == 1) { "Default group cannot be combined" }
                library.setFollowSelection(setOf(mid), ids.filter { it > 0 }.toSet(), SPECIAL_ID in ids)
                JSONObject.NULL
            }
            CREATE -> {
                val group = library.createFollowGroup(params["tag"].orEmpty().trim())
                row(group.id, group.name, 0)
            }
            RENAME -> { library.renameFollowGroup(groupId(), params["name"].orEmpty().trim()); JSONObject.NULL }
            DELETE -> { library.deleteFollowGroup(groupId()); JSONObject.NULL }
            SPECIAL_ADD, SPECIAL_DELETE -> {
                author()
                library.setSpecialFollow(mid, path == SPECIAL_ADD)
                JSONObject.NULL
            }
            else -> error("Unsupported group request")
        }
        return JSONObject().put("code", 0).put("message", "0").put("data", data).toString()
    }
}
