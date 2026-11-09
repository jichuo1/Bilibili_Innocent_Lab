package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Collections as Immutable

/** 回调在锁内同步执行且不可重入；调用方负责本地原子持久化、失败抛出和 IO 线程调度。 */
class LocalVirtualLibrary(
    private val load: () -> String?,
    private val save: (String) -> Unit,
    private val maxEntries: Int = MAX_ENTRIES,
    private val maxFolders: Int = MAX_FOLDERS
) {
    data class Video(
        val aid: Long,
        val cid: Long,
        val title: String,
        val durationMs: Long,
        val positionMs: Long,
        val updatedAtMs: Long,
        val cover: String? = null,
        val authorMid: Long = 0,
        val authorName: String = "",
        val authorFace: String = ""
    ) {
        val isComplete: Boolean get() = durationMs > 0 && positionMs >= durationMs
    }

    data class Follow(val mid: Long, val name: String, val face: String = "")
    data class FollowGroup(val id: Long, val name: String)
    data class FolderInfo(val id: Long, val intro: String = "", val cover: String = "", val pinned: Boolean = false)

    // 视频以 aid + cid 唯一；列表按最近写入在前排列，收藏夹名称作为本地键。
    data class Collections(
        val history: List<Video> = emptyList(),
        val favorites: Map<String, List<Video>> = mapOf(DEFAULT_FOLDER to emptyList()),
        val watchLater: List<Video> = emptyList(),
        val follows: List<Follow> = emptyList(),
        val folderInfo: Map<String, FolderInfo> = emptyMap(),
        val favoriteOrder: List<Long> = emptyList(),
        val followGroups: List<FollowGroup> = emptyList(),
        val followGroupAssignments: Map<Long, Set<Long>> = emptyMap(),
        val specialFollows: Set<Long> = emptySet(),
        val nextFollowGroupId: Long = 1
    )

    data class Snapshot internal constructor(
        val incognito: Boolean,
        val collections: Collections,
        internal val stamp: Any
    )

    class CorruptLibraryException : IllegalArgumentException("Local library JSON is invalid or unsupported")

    private val lock = Any()
    private var guest: Collections? = null
    private var session: Collections? = null
    private var stamp = Any()
    private var inPersistenceCallback = false

    init {
        require(maxEntries in 1..MAX_ENTRIES) { "Invalid entry limit" }
        require(maxFolders in 1..MAX_FOLDERS) { "Invalid folder limit" }
    }

    val isIncognito: Boolean get() = locked { session != null }

    // 延迟加载使首次进入无痕也完全不触碰普通库。
    fun enterIncognito() = locked {
        if (session == null) {
            session = freeze(Collections())
            stamp = Any()
        }
    }

    fun exitIncognito() = locked {
        if (session != null) {
            session = null
            stamp = Any()
        }
    }

    fun resetIncognito() = locked {
        check(session != null) { "Not in incognito mode" }
        session = freeze(Collections())
        stamp = Any()
    }

    fun readSnapshot(): Snapshot = locked {
        Snapshot(session != null, freeze(current()), stamp)
    }

    /** 用 readSnapshot().copy(collections = ...) 替换当前库；拒绝过期、跨实例和跨会话快照。 */
    fun writeSnapshot(snapshot: Snapshot) = locked {
        check(snapshot.stamp === stamp && snapshot.incognito == (session != null)) { "Stale library snapshot" }
        val before = current()
        commit(before, snapshot.collections)
    }

    fun findHistory(aid: Long, cid: Long): Video? = locked {
        validateKey(aid, cid)
        current().history.find { it.aid == aid && it.cid == cid }
    }

    /** 完整元数据写入；仅进度事件应使用 saveProgress，避免空元数据覆盖已有记录。 */
    fun putHistory(video: Video) = update { it.copy(history = putVideo(it.history, video, evict = true)) }

    /** null duration 表示未提供；0 是精确值。完成或超出时长也不清零、不删除记录。 */
    fun saveProgress(
        aid: Long,
        cid: Long,
        positionMs: Long,
        updatedAtMs: Long,
        durationMs: Long? = null
    ): Video = locked {
        validateKey(aid, cid)
        require(positionMs in 0..MAX_MEDIA_MS && updatedAtMs in 0..MAX_TIMESTAMP_MS) { "Invalid progress" }
        require(durationMs == null || durationMs in 0..MAX_MEDIA_MS) { "Invalid duration" }
        val before = current()
        fun matches(video: Video) = video.aid == aid && video.cid == cid
        fun progress(video: Video) = video.copy(
            positionMs = positionMs,
            durationMs = durationMs ?: video.durationMs,
            updatedAtMs = updatedAtMs
        )
        val previous = before.history.find(::matches)
            ?: before.watchLater.find(::matches)
            ?: before.favorites.values.firstNotNullOfOrNull { videos -> videos.find(::matches) }
            ?: throw IllegalStateException("Record video metadata before saving progress")
        val updated = progress(previous)
        commit(before, before.copy(
            history = putVideo(before.history, updated, evict = true),
            favorites = before.favorites.mapValues { (_, videos) ->
                videos.map { if (matches(it)) progress(it) else it }
            },
            watchLater = before.watchLater.map { if (matches(it)) progress(it) else it }
        ))
        updated
    }

    fun removeHistory(aid: Long, cid: Long) = update { it.copy(history = removeVideo(it.history, aid, cid)) }

    fun clearHistory() = update { it.copy(history = emptyList()) }

    fun createFavoriteFolder(name: String) = update {
        validateFolderName(name)
        require(name !in it.favorites) { "Folder already exists" }
        require(it.favorites.size < maxFolders) { "Folder limit reached" }
        it.copy(favorites = it.favorites + (name to emptyList()))
    }

    fun createFavoriteFolder(name: String, info: FolderInfo) = update {
        validateFolderName(name)
        require(name !in it.favorites && it.favorites.size < maxFolders) { "Folder unavailable" }
        it.copy(favorites = it.favorites + (name to emptyList()), folderInfo = it.folderInfo + (name to info))
    }

    fun renameFavoriteFolder(name: String, newName: String) = update {
        validateFolderName(name)
        validateFolderName(newName)
        require(name != DEFAULT_FOLDER && name in it.favorites) { "Cannot rename this folder" }
        if (name == newName) return@update it
        require(newName !in it.favorites) { "Folder already exists" }
        it.copy(favorites = it.favorites.entries.associate { (key, videos) ->
            (if (key == name) newName else key) to videos
        }, folderInfo = it.folderInfo.entries.associate { (key, info) ->
            (if (key == name) newName else key) to info
        })
    }

    fun editFavoriteFolder(name: String, newName: String, info: FolderInfo) = update {
        validateFolderName(newName)
        require(name in it.favorites && (name == newName || newName !in it.favorites)) { "Folder unavailable" }
        require(name != DEFAULT_FOLDER || name == newName) { "Cannot rename default folder" }
        it.copy(favorites = it.favorites.entries.associate { (key, videos) ->
            (if (key == name) newName else key) to videos
        }, folderInfo = (it.folderInfo - name) + (newName to info))
    }

    fun deleteFavoriteFolder(name: String) = update {
        validateFolderName(name)
        require(name != DEFAULT_FOLDER) { "Cannot delete the default folder" }
        it.copy(favorites = it.favorites - name, folderInfo = it.folderInfo - name)
    }

    /** Official favorites apply to an archive, not one part (cid), and commit all folders atomically. */
    fun setFavoriteFolders(video: Video, selected: Set<String>) = update { before ->
        validateVideo(video)
        require(selected.all { it in before.favorites }) { "Folder does not exist" }
        before.copy(favorites = before.favorites.mapValues { (name, videos) ->
            val withoutArchive = videos.filterNot { it.aid == video.aid }
            if (name in selected) putVideo(withoutArchive, video) else withoutArchive
        })
    }

    fun toggleFavorite(video: Video): Boolean = locked {
        val selected = current().favorites.values.any { videos -> videos.any { it.aid == video.aid } }
        setFavoriteFolders(video, if (selected) emptySet() else setOf(DEFAULT_FOLDER))
        !selected
    }

    fun addFavorite(video: Video, folder: String = DEFAULT_FOLDER) = update {
        validateFolderName(folder)
        val videos = requireNotNull(it.favorites[folder]) { "Folder does not exist" }
        it.copy(favorites = it.favorites + (folder to putVideo(videos, video)))
    }

    fun removeFavorite(aid: Long, cid: Long, folder: String = DEFAULT_FOLDER) = update {
        validateFolderName(folder)
        val videos = requireNotNull(it.favorites[folder]) { "Folder does not exist" }
        it.copy(favorites = it.favorites + (folder to removeVideo(videos, aid, cid)))
    }

    fun addWatchLater(video: Video) = update { it.copy(watchLater = putVideo(it.watchLater, video)) }

    fun removeWatchLater(aid: Long, cid: Long) = update {
        it.copy(watchLater = removeVideo(it.watchLater, aid, cid))
    }

    fun follow(follow: Follow) = update {
        validateFollow(follow)
        val remaining = it.follows.filterNot { item -> item.mid == follow.mid }
        require(remaining.size < maxEntries) { "Follow limit reached" }
        it.copy(follows = listOf(follow) + remaining)
    }

    fun unfollow(mid: Long) = update {
        validateId(mid)
        it.copy(
            follows = it.follows.filterNot { follow -> follow.mid == mid },
            followGroupAssignments = it.followGroupAssignments - mid,
            specialFollows = it.specialFollows - mid
        )
    }

    /** Within one library lifetime, deleted group IDs are not reused by a later group. */
    fun createFollowGroup(name: String): FollowGroup = locked {
        validateFolderName(name)
        val before = current()
        require(before.followGroups.size < MAX_FOLLOW_GROUPS) { "Follow group limit reached" }
        require(before.followGroups.none { it.name == name }) { "Follow group already exists" }
        require(before.nextFollowGroupId < MAX_ID) { "Follow group id limit reached" }
        val group = FollowGroup(before.nextFollowGroupId, name)
        commit(before, before.copy(followGroups = before.followGroups + group, nextFollowGroupId = group.id + 1))
        group
    }

    fun renameFollowGroup(id: Long, name: String) = update { before ->
        validateId(id)
        validateFolderName(name)
        require(before.followGroups.any { it.id == id }) { "Follow group does not exist" }
        require(before.followGroups.none { it.id != id && it.name == name }) { "Follow group already exists" }
        before.copy(followGroups = before.followGroups.map { if (it.id == id) it.copy(name = name) else it })
    }

    fun deleteFollowGroup(id: Long) = update { before ->
        validateId(id)
        before.copy(
            followGroups = before.followGroups.filterNot { it.id == id },
            followGroupAssignments = before.followGroupAssignments.mapValues { (_, groups) -> groups - id }
                .filterValues { it.isNotEmpty() }
        )
    }

    /** Replaces each selected author's group assignments in one atomic library update. */
    fun setFollowGroups(mids: Set<Long>, groupIds: Set<Long>) = update { before ->
        assignFollowGroups(before, mids, groupIds)
    }

    /** The host's selection includes both custom groups and its special-follow group. */
    fun setFollowSelection(mids: Set<Long>, groupIds: Set<Long>, special: Boolean) = update { before ->
        val assigned = assignFollowGroups(before, mids, groupIds)
        assigned.copy(specialFollows = if (special) assigned.specialFollows + mids else assigned.specialFollows - mids)
    }

    private fun assignFollowGroups(before: Collections, mids: Set<Long>, groupIds: Set<Long>): Collections {
        require(mids.isNotEmpty()) { "No authors selected" }
        mids.forEach(::validateId)
        groupIds.forEach(::validateId)
        val followed = before.follows.mapTo(HashSet()) { it.mid }
        val groups = before.followGroups.mapTo(HashSet()) { it.id }
        require(mids.all { it in followed } && groupIds.all { it in groups }) { "Follow or group does not exist" }
        val assignments = before.followGroupAssignments.toMutableMap()
        mids.forEach { mid -> if (groupIds.isEmpty()) assignments.remove(mid) else assignments[mid] = groupIds }
        return before.copy(followGroupAssignments = assignments)
    }

    fun setSpecialFollow(mid: Long, special: Boolean) = update { before ->
        validateId(mid)
        require(before.follows.any { it.mid == mid }) { "Follow does not exist" }
        before.copy(specialFollows = if (special) before.specialFollows + mid else before.specialFollows - mid)
    }

    private fun update(transform: (Collections) -> Collections) = locked {
        val before = current()
        commit(before, transform(before))
    }

    private fun current(): Collections {
        session?.let { return it }
        guest?.let { return it }
        val text = persistence(load)
        return (if (text == null) freeze(Collections()) else decode(text)).also { guest = it }
    }

    private fun commit(before: Collections, candidate: Collections) {
        val next = freeze(candidate)
        if (before == next) return
        if (session != null) {
            session = next
        } else {
            val text = encode(next)
            persistence { save(text) }
            guest = next
        }
        stamp = Any()
    }

    private fun <T> locked(block: () -> T): T = synchronized(lock) {
        check(!inPersistenceCallback) { "Persistence callbacks must not reenter the library" }
        block()
    }

    private fun <T> persistence(callback: () -> T): T {
        inPersistenceCallback = true
        return try {
            callback()
        } finally {
            inPersistenceCallback = false
        }
    }

    // 历史自动淘汰最旧写入；主动收藏、稍后再看和关注满额时拒绝新增，不静默丢失用户数据。
    private fun putVideo(videos: List<Video>, video: Video, evict: Boolean = false): List<Video> {
        validateVideo(video)
        val remaining = videos.filterNot { it.aid == video.aid && it.cid == video.cid }
        require(evict || remaining.size < maxEntries) { "Video limit reached" }
        return listOf(video) + remaining.take(maxEntries - 1)
    }

    private fun removeVideo(videos: List<Video>, aid: Long, cid: Long): List<Video> {
        validateKey(aid, cid)
        return videos.filterNot { it.aid == aid && it.cid == cid }
    }

    private fun freeze(source: Collections): Collections {
        require(source.favorites.size in 1..maxFolders && DEFAULT_FOLDER in source.favorites) { "Invalid folders" }
        fun videos(items: List<Video>): List<Video> {
            require(items.size <= maxEntries) { "Video limit exceeded" }
            val seen = HashSet<Pair<Long, Long>>()
            items.forEach {
                validateVideo(it)
                require(seen.add(it.aid to it.cid)) { "Duplicate video" }
            }
            return Immutable.unmodifiableList(ArrayList(items))
        }
        val favorites = LinkedHashMap<String, List<Video>>()
        source.favorites.forEach { (name, items) ->
            validateFolderName(name)
            favorites[name] = videos(items)
        }
        require(source.follows.size <= maxEntries) { "Follow limit exceeded" }
        val mids = HashSet<Long>()
        source.follows.forEach {
            validateFollow(it)
            require(mids.add(it.mid)) { "Duplicate follow" }
        }
        require(source.followGroups.size <= MAX_FOLLOW_GROUPS) { "Follow group limit exceeded" }
        require(source.nextFollowGroupId in 1..MAX_ID) { "Invalid next follow group id" }
        val groupIds = HashSet<Long>()
        val groupNames = HashSet<String>()
        source.followGroups.forEach { group ->
            validateId(group.id)
            validateFolderName(group.name)
            require(group.id < source.nextFollowGroupId && groupIds.add(group.id) && groupNames.add(group.name)) { "Invalid follow group" }
        }
        require(source.specialFollows.all { it in mids }) { "Special follow is not followed" }
        require(source.followGroupAssignments.size <= maxEntries) { "Too many follow assignments" }
        val assignments = LinkedHashMap<Long, Set<Long>>()
        source.followGroupAssignments.forEach { (mid, groups) ->
            require(mid in mids && groups.isNotEmpty() && groups.size <= MAX_FOLLOW_GROUPS && groups.all { it in groupIds }) {
                "Invalid follow assignment"
            }
            assignments[mid] = Immutable.unmodifiableSet(LinkedHashSet(groups))
        }
        require(source.favoriteOrder.size <= maxEntries * maxFolders && source.favoriteOrder.distinct().size == source.favoriteOrder.size)
        source.favoriteOrder.forEach(::validateId)
        val ids = HashSet<Long>()
        source.folderInfo.forEach { (name, info) ->
            require(name in source.favorites && ids.add(info.id)) { "Invalid folder metadata" }
            validateId(info.id)
            validateText(info.intro, MAX_TITLE_LENGTH)
            validateText(info.cover, MAX_COVER_LENGTH)
        }
        return Collections(
            history = videos(source.history),
            favorites = Immutable.unmodifiableMap(favorites),
            watchLater = videos(source.watchLater),
            follows = Immutable.unmodifiableList(ArrayList(source.follows)),
            folderInfo = Immutable.unmodifiableMap(LinkedHashMap(source.folderInfo)),
            favoriteOrder = Immutable.unmodifiableList(ArrayList(source.favoriteOrder)),
            followGroups = Immutable.unmodifiableList(ArrayList(source.followGroups)),
            followGroupAssignments = Immutable.unmodifiableMap(assignments),
            specialFollows = Immutable.unmodifiableSet(LinkedHashSet(source.specialFollows)),
            nextFollowGroupId = source.nextFollowGroupId
        )
    }

    private fun validateId(id: Long) {
        require(id in 1..MAX_ID) { "Invalid id" }
    }

    private fun validateKey(aid: Long, cid: Long) {
        validateId(aid)
        validateId(cid)
    }

    private fun validateText(text: String, maxLength: Int) {
        require(text.length <= maxLength && '\u0000' !in text) { "Invalid text length or content" }
    }

    private fun validateVideo(video: Video) {
        validateKey(video.aid, video.cid)
        require(video.authorMid in 0..MAX_ID) { "Invalid author id" }
        require(video.durationMs in 0..MAX_MEDIA_MS && video.positionMs in 0..MAX_MEDIA_MS) { "Invalid media time" }
        require(video.updatedAtMs in 0..MAX_TIMESTAMP_MS) { "Invalid timestamp" }
        validateText(video.title, MAX_TITLE_LENGTH)
        validateText(video.authorName, MAX_NAME_LENGTH)
        validateText(video.authorFace, MAX_COVER_LENGTH)
        video.cover?.let { validateText(it, MAX_COVER_LENGTH) }
    }

    private fun validateFollow(follow: Follow) {
        validateId(follow.mid)
        validateText(follow.name, MAX_NAME_LENGTH)
        validateText(follow.face, MAX_COVER_LENGTH)
    }

    private fun validateFolderName(name: String) {
        validateText(name, MAX_FOLDER_NAME_LENGTH)
        require(name.isNotBlank() && name == name.trim()) { "Invalid folder name" }
    }

    private fun encode(collections: Collections): String {
        val extended = collections.favoriteOrder.isNotEmpty() || collections.follows.any { it.face.isNotEmpty() } || collections.folderInfo.values.any { it.pinned } ||
            (collections.history + collections.watchLater + collections.favorites.values.flatten()).any { it.authorFace.isNotEmpty() }
        val followGroups = collections.followGroups.isNotEmpty() || collections.specialFollows.isNotEmpty() || collections.nextFollowGroupId != 1L
        val version = if (followGroups) 4 else if (extended) 3 else if (collections.folderInfo.isEmpty()) 1 else 2
        fun videoJson(video: Video) = JSONObject()
            .put("aid", video.aid).put("cid", video.cid).put("title", video.title)
            .put("durationMs", video.durationMs).put("positionMs", video.positionMs)
            .put("updatedAtMs", video.updatedAtMs).put("cover", video.cover ?: JSONObject.NULL)
            .put("authorMid", video.authorMid).put("authorName", video.authorName)
            .apply { if (version >= 3) put("authorFace", video.authorFace) }
        fun videos(items: List<Video>) = JSONArray().apply { items.forEach { put(videoJson(it)) } }
        val root = JSONObject().put("version", version)
            .put("history", videos(collections.history))
            .put("favorites", JSONArray().apply {
                collections.favorites.forEach { (name, items) ->
                    put(JSONObject().put("name", name).put("videos", videos(items)).apply {
                        collections.folderInfo[name]?.let { info ->
                            put("info", JSONObject().put("id", info.id).put("intro", info.intro).put("cover", info.cover)
                                .apply { if (version >= 3) put("pinned", info.pinned) })
                        }
                    })
                }
            })
            .put("watchLater", videos(collections.watchLater))
            .put("follows", JSONArray().apply {
                collections.follows.forEach { put(JSONObject().put("mid", it.mid).put("name", it.name).apply { if (version >= 3) put("face", it.face) }) }
            })
        if (version >= 3) root.put("favoriteOrder", JSONArray(collections.favoriteOrder))
        if (version >= 4) {
            root.put("followGroups", JSONArray().apply {
                collections.followGroups.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) }
            })
            root.put("followGroupAssignments", JSONArray().apply {
                collections.followGroupAssignments.toSortedMap().forEach { (mid, groups) ->
                    put(JSONObject().put("mid", mid).put("groupIds", JSONArray(groups.sorted())))
                }
            })
            root.put("specialFollows", JSONArray(collections.specialFollows.sorted()))
            root.put("nextFollowGroupId", collections.nextFollowGroupId)
        }
        return root.toString().also { require(it.length <= MAX_JSON_LENGTH) { "Library JSON limit exceeded" } }
    }

    private fun decode(text: String): Collections = try {
        validateJsonSyntax(text)
        val root = JSONObject(text)
        val version = number(root, "version")
        require(version in 1L..4L)
        val rootKeys = setOf("version", "history", "favorites", "watchLater", "follows") +
            (if (version >= 3) setOf("favoriteOrder") else emptySet()) +
            (if (version >= 4) setOf("followGroups", "followGroupAssignments", "specialFollows", "nextFollowGroupId") else emptySet())
        requireKeys(root, rootKeys)
        fun videos(array: JSONArray): List<Video> {
            require(array.length() <= maxEntries)
            return List(array.length()) { index ->
                val video = array.getJSONObject(index)
                requireKeys(video, setOf("aid", "cid", "title", "durationMs", "positionMs", "updatedAtMs", "cover", "authorMid", "authorName") + if (version >= 3) setOf("authorFace") else emptySet())
                Video(
                    aid = number(video, "aid"), cid = number(video, "cid"), title = string(video, "title"),
                    durationMs = number(video, "durationMs"), positionMs = number(video, "positionMs"),
                    updatedAtMs = number(video, "updatedAtMs"),
                    cover = if (video.isNull("cover")) null else string(video, "cover"),
                    authorMid = number(video, "authorMid"), authorName = string(video, "authorName"),
                    authorFace = if (version >= 3) string(video, "authorFace") else ""
                )
            }
        }
        val folders = root.getJSONArray("favorites")
        require(folders.length() in 1..maxFolders)
        val favorites = LinkedHashMap<String, List<Video>>()
        val folderInfo = LinkedHashMap<String, FolderInfo>()
        repeat(folders.length()) { index ->
            val folder = folders.getJSONObject(index)
            val hasInfo = version >= 2L && folder.has("info")
            requireKeys(folder, if (hasInfo) setOf("name", "videos", "info") else setOf("name", "videos"))
            val name = string(folder, "name")
            if (hasInfo) {
                val info = folder.getJSONObject("info")
                requireKeys(info, setOf("id", "intro", "cover") + if (version >= 3) setOf("pinned") else emptySet())
                folderInfo[name] = FolderInfo(number(info, "id"), string(info, "intro"), string(info, "cover"),
                    if (version >= 3) info.get("pinned").also { require(it is Boolean) } as Boolean else false)
            }
            require(name !in favorites)
            favorites[name] = videos(folder.getJSONArray("videos"))
        }
        require(version != 2L || folderInfo.isNotEmpty())
        val follows = root.getJSONArray("follows")
        require(follows.length() <= maxEntries)
        fun idSet(array: JSONArray, limit: Int): Set<Long> {
            require(array.length() <= limit)
            val values = List(array.length()) { index ->
                (array.get(index).also { require(it is Int || it is Long) } as Number).toLong()
            }
            require(values.distinct().size == values.size) { "Duplicate id" }
            return values.toSet()
        }
        val groups = if (version >= 4) root.getJSONArray("followGroups").let { array ->
            require(array.length() <= MAX_FOLLOW_GROUPS)
            List(array.length()) { index ->
                val group = array.getJSONObject(index)
                requireKeys(group, setOf("id", "name"))
                FollowGroup(number(group, "id"), string(group, "name"))
            }
        } else emptyList()
        val assignments = LinkedHashMap<Long, Set<Long>>()
        if (version >= 4) {
            val array = root.getJSONArray("followGroupAssignments")
            require(array.length() <= maxEntries)
            repeat(array.length()) { index ->
                val assignment = array.getJSONObject(index)
                requireKeys(assignment, setOf("mid", "groupIds"))
                val mid = number(assignment, "mid")
                require(mid !in assignments)
                assignments[mid] = idSet(assignment.getJSONArray("groupIds"), MAX_FOLLOW_GROUPS)
            }
        }
        freeze(Collections(
            history = videos(root.getJSONArray("history")),
            favorites = favorites,
            folderInfo = folderInfo,
            favoriteOrder = if (version >= 3) root.getJSONArray("favoriteOrder").let { array ->
                List(array.length()) { (array.get(it).also { value -> require(value is Int || value is Long) } as Number).toLong() }
            } else emptyList(),
            watchLater = videos(root.getJSONArray("watchLater")),
            follows = List(follows.length()) { index ->
                val follow = follows.getJSONObject(index)
                requireKeys(follow, setOf("mid", "name") + if (version >= 3) setOf("face") else emptySet())
                Follow(number(follow, "mid"), string(follow, "name"), if (version >= 3) string(follow, "face") else "")
            },
            followGroups = groups,
            followGroupAssignments = assignments,
            specialFollows = if (version >= 4) idSet(root.getJSONArray("specialFollows"), maxEntries) else emptySet(),
            nextFollowGroupId = if (version >= 4) number(root, "nextFollowGroupId") else 1
        ))
    } catch (_: JSONException) {
        // 不保留解析异常的 cause：Android 的异常消息可能含整段库正文。
        throw CorruptLibraryException()
    } catch (_: IllegalArgumentException) {
        throw CorruptLibraryException()
    }

    private fun requireKeys(json: JSONObject, keys: Set<String>) {
        require(json.keys().asSequence().toSet() == keys)
    }

    private fun number(json: JSONObject, key: String): Long = when (val value = json.get(key)) {
        is Int -> value.toLong()
        is Long -> value
        else -> throw IllegalArgumentException("Expected integer")
    }

    private fun string(json: JSONObject, key: String): String =
        json.get(key) as? String ?: throw IllegalArgumentException("Expected string")

    // Android org.json 比 JVM 版更宽松；先拒绝重复键、非标准语法和过深嵌套。
    private fun validateJsonSyntax(text: String) {
        require(text.isNotEmpty() && text.length <= MAX_JSON_LENGTH)
        var offset = 0
        fun whitespace() {
            while (offset < text.length && text[offset] in " \t\r\n") offset++
        }
        fun consume(character: Char): Boolean {
            whitespace()
            return (offset < text.length && text[offset] == character).also { if (it) offset++ }
        }
        fun quoted(): String {
            whitespace()
            val start = offset
            require(offset < text.length && text[offset++] == '"')
            while (offset < text.length) {
                val character = text[offset++]
                require(character >= ' ')
                if (character == '"') return text.substring(start, offset)
                if (character == '\\') {
                    require(offset < text.length)
                    when (text[offset++]) {
                        '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                        'u' -> repeat(4) {
                            require(offset < text.length && text[offset++] in "0123456789abcdefABCDEF")
                        }
                        else -> throw IllegalArgumentException("Invalid escape")
                    }
                }
            }
            throw IllegalArgumentException("Unterminated string")
        }
        fun value(depth: Int) {
            require(depth <= 8)
            whitespace()
            require(offset < text.length)
            when (text[offset]) {
                '{' -> {
                    offset++
                    val keys = HashSet<String>()
                    if (!consume('}')) {
                        do {
                            val key = JSONTokener(quoted()).nextValue() as String
                            require(keys.add(key) && keys.size <= 16)
                            require(consume(':'))
                            value(depth + 1)
                            if (consume('}')) break
                            require(consume(','))
                        } while (true)
                    }
                }
                '[' -> {
                    offset++
                    var count = 0
                    if (!consume(']')) {
                        do {
                            require(++count <= maxEntries * maxFolders)
                            value(depth + 1)
                            if (consume(']')) break
                            require(consume(','))
                        } while (true)
                    }
                }
                '"' -> quoted()
                else -> {
                    val token = JSON_LITERAL.matchAt(text, offset)
                    require(token != null)
                    offset = token.range.last + 1
                }
            }
        }
        value(0)
        whitespace()
        require(offset == text.length)
    }

    companion object {
        const val DEFAULT_FOLDER = "默认收藏夹"
        const val MAX_ENTRIES = 500
        const val MAX_FOLDERS = 20
        const val MAX_FOLLOW_GROUPS = 100
        const val MAX_ID = 9_007_199_254_740_991L
        const val MAX_MEDIA_MS = 30L * 24 * 60 * 60 * 1000
        const val MAX_TIMESTAMP_MS = 253_402_300_799_999L
        const val MAX_TITLE_LENGTH = 512
        const val MAX_NAME_LENGTH = 128
        const val MAX_COVER_LENGTH = 2048
        const val MAX_FOLDER_NAME_LENGTH = 64
        const val MAX_JSON_LENGTH = 8 * 1024 * 1024
        private val JSON_LITERAL = Regex("(?:true|false|null|-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)")
    }
}
