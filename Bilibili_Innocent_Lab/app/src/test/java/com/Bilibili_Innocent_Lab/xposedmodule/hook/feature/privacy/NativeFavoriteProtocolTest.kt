package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeFavoriteProtocolTest {
    private val video = LocalVirtualLibrary.Video(170001, 279786, "title", 199000, 92000, 123000, "cover", 7, "author")
    private fun library() = LocalVirtualLibrary({ null }, {}).also { it.addFavorite(video) }
    private fun response(library: LocalVirtualLibrary, path: String, vararg params: Pair<String, String>) =
        JSONObject(NativeFavoriteProtocol.respond("/x/v3/fav/$path", params.toMap(), library))

    @Test fun tabsCarryEncodedNativeFragmentArguments() {
        val tab = response(library(), "tab").getJSONObject("data").getJSONArray("tabs").getJSONObject(0)
        val uri = java.net.URI(tab.getString("uri"))
        val query = java.net.URLDecoder.decode(uri.rawQuery, "UTF-8")
        assertTrue(query.contains("sub_tab=["))
        assertTrue(query.contains("favorite/folders"))
        assertEquals(100, tab.getJSONArray("sub_tab").getJSONObject(0).getInt("tab_id"))
    }
    @Test fun folderRowsAndDetailUseSameStableIdentityAndResumeTarget() {
        val data = library()
        val row = response(data, "tab/my_fav").getJSONObject("data").getJSONArray("list").getJSONObject(0)
        val id = row.getLong("fid").toString()
        val detail = response(data, "folder/resources", "media_id" to id).getJSONObject("data")
        assertEquals(1, row.getInt("media_count"))
        assertEquals(92, detail.getJSONArray("list").getJSONObject(0).getInt("playback_position"))
        assertTrue(detail.getJSONArray("list").getJSONObject(0).getString("link").contains("cid=279786"))
        assertEquals(0, response(data, "folder/resources", "media_id" to id, "pn" to "2").getJSONObject("data").getJSONArray("list").length())
    }
    @Test fun batchDeleteIsArchiveScopedAndDoesNotChangeOtherFolder() {
        val data = library()
        data.createFavoriteFolder("other")
        data.addFavorite(video.copy(cid=9), "other")
        val id = NativeFavoriteFolders.id(data.readSnapshot().collections, LocalVirtualLibrary.DEFAULT_FOLDER)
        response(data, "tab/dels", "media_id" to "$id", "resources" to "170001:2")
        assertTrue(data.readSnapshot().collections.favorites.getValue(LocalVirtualLibrary.DEFAULT_FOLDER).isEmpty())
        assertEquals(1, data.readSnapshot().collections.favorites.getValue("other").size)
    }
    @Test fun moveCommitsBothFoldersTogetherAndRollsBackOnWriteFailure() {
        var fail = false
        val data = LocalVirtualLibrary({ null }, { if (fail) error("disk full") })
        data.addFavorite(video); data.createFavoriteFolder("other")
        val before = data.readSnapshot()
        val from = NativeFavoriteFolders.id(before.collections, LocalVirtualLibrary.DEFAULT_FOLDER)
        val to = NativeFavoriteFolders.id(before.collections, "other")
        fail = true
        assertThrows(IllegalStateException::class.java) {
            response(data, "resource/move", "src_media_id" to "$from", "tar_media_id" to "$to", "resources" to "170001:2")
        }
        assertEquals(before.collections, data.readSnapshot().collections)
        fail = false
        response(data, "resource/move", "src_media_id" to "$from", "tar_media_id" to "$to", "resources" to "170001:2")
        assertTrue(data.readSnapshot().collections.favorites.getValue(LocalVirtualLibrary.DEFAULT_FOLDER).isEmpty())
        assertEquals(listOf(video), data.readSnapshot().collections.favorites.getValue("other"))
    }
    @Test fun hostFolderPickerTicksAndCommitsOnlyTheChangedFolders() {
        val data = library()
        data.createFavoriteFolder("other")
        val state = data.readSnapshot().collections
        val default = NativeFavoriteFolders.id(state, LocalVirtualLibrary.DEFAULT_FOLDER)
        val other = NativeFavoriteFolders.id(state, "other")
        fun ticks() = response(data, "folder/created/list-all", "rid" to "170001", "type" to "2").getJSONObject("data")
            .getJSONArray("list").let { list -> (0 until list.length()).associate { list.getJSONObject(it).getLong("id") to list.getJSONObject(it).getInt("fav_state") } }
        assertEquals(mapOf(default to 1, other to 0), ticks())

        val reply = response(data, "resource/batch-deal", "resources" to "170001:2", "add_media_ids" to "$other", "del_media_ids" to "$default")
        assertEquals(0, reply.getInt("code"))
        assertEquals("", reply.getJSONObject("data").getString("toast_msg"))
        assertEquals(mapOf(default to 0, other to 1), ticks())

        // "0" is the default folder; an archive this library never held comes from the caller's metadata.
        val fresh = video.copy(aid = 170002, title = "new")
        assertTrue(NativeFavoriteProtocol.isAdd("/x/v3/fav/resource/batch-deal", mapOf("add_media_ids" to "0")))
        assertFalse(NativeFavoriteProtocol.isAdd("/x/v3/fav/resource/batch-deal", mapOf("del_media_ids" to "0", "add_media_ids" to "")))
        JSONObject(NativeFavoriteProtocol.respond("/x/v3/fav/resource/batch-deal",
            mapOf("resources" to "170002:2", "add_media_ids" to "0", "del_media_ids" to ""), data) { if (it == 170002L) fresh else null })
        assertEquals(listOf(fresh), data.readSnapshot().collections.favorites.getValue(LocalVirtualLibrary.DEFAULT_FOLDER))

        // Unknown archives and empty commits never report success.
        val before = data.readSnapshot().collections
        assertThrows(IllegalArgumentException::class.java) {
            response(data, "resource/batch-deal", "resources" to "170003:2", "add_media_ids" to "0")
        }
        assertThrows(IllegalArgumentException::class.java) { response(data, "resource/batch-deal", "resources" to "170001:2") }
        assertEquals(before, data.readSnapshot().collections)
    }
    @Test fun unfavoriteAllRemovesTheArchiveEverywhere() {
        val data = library()
        data.createFavoriteFolder("other")
        data.addFavorite(video, "other")
        assertEquals(0, response(data, "resource/unfav-all", "rid" to "170001", "type" to "2").getInt("code"))
        assertTrue(data.readSnapshot().collections.favorites.values.all { it.isEmpty() })
        assertEquals(1, response(data, "folder/created/list-all").getJSONObject("data").getJSONArray("list").getJSONObject(0).getInt("fav_state"))
    }
    @Test fun unknownMutationIsNotReportedAsSaved() {
        val data = library(); val before = data.readSnapshot()
        assertEquals(-400, response(data, "folder/unknown-operation").getInt("code"))
        assertEquals(before.collections, data.readSnapshot().collections)
    }
    @Test fun incognitoNativeReadsAndEditsNeverOpenPersistentStorage() {
        val data = LocalVirtualLibrary({ error("disk read") }, { error("disk write") })
        data.enterIncognito(); data.addFavorite(video)
        response(data, "tab/my_fav")
        response(data, "tab/dels", "resources" to "170001:2")
        assertTrue(data.readSnapshot().collections.favorites.values.all { it.isEmpty() })
        data.resetIncognito()
        assertEquals(0, response(data, "tab/fav").getJSONObject("data").getInt("total"))
    }
}
