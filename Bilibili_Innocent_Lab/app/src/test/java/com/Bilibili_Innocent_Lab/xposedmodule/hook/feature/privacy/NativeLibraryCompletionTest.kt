package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeLibraryCompletionTest {
    private val first = LocalVirtualLibrary.Video(1, 11, "first", 100000, 50000, 10, "cover", 7, "author")
    private val second = first.copy(aid = 2, cid = 22, title = "second")
    private fun fav(store: LocalVirtualLibrary, path: String, vararg params: Pair<String, String>) =
        JSONObject(NativeFavoriteProtocol.respond("/x/v3/fav/$path", params.toMap(), store))
    private fun later(store: LocalVirtualLibrary, path: String, vararg params: Pair<String, String>) =
        JSONObject(NativeWatchLaterProtocol.respond("/x/v2/history/toview/$path", params.toMap(), store))

    @Test fun legacyLibraryUpgradesWithoutDroppingProgressAndPersistsAvatarsPinsAndOrder() {
        var disk: String? = null
        var store = LocalVirtualLibrary({ disk }, { disk = it })
        store.putHistory(first); store.addFavorite(first); store.addFavorite(second)
        assertEquals(1, JSONObject(disk!!).getInt("version"))
        store = LocalVirtualLibrary({ disk }, { disk = it })
        store.follow(LocalVirtualLibrary.Follow(7, "author", "https://example.test/avatar"))
        store.putHistory(first.copy(authorFace = "https://example.test/avatar"))
        store.createFavoriteFolder("pinned")
        val id = NativeFavoriteFolders.id(store.readSnapshot().collections, "pinned")
        fav(store, "folder/add_top", "fid" to "$id", "add_top" to "true")
        fav(store, "resource/sort", "sort" to "0:0:1:2")
        val restored = LocalVirtualLibrary({ disk }, { error("unexpected write") }).readSnapshot().collections
        assertEquals(3, JSONObject(disk!!).getInt("version"))
        assertEquals(50000L, restored.history.single().positionMs)
        assertEquals("https://example.test/avatar", restored.history.single().authorFace)
        assertEquals(restored.history.single().authorFace, restored.follows.single().face)
        assertTrue(restored.folderInfo.getValue("pinned").pinned)
        assertEquals(listOf(1L, 2L), restored.favoriteOrder)
    }

    @Test fun nativePinAndUnpinReturnCorrectMenuStateAndKeepFolderContents() {
        val store = LocalVirtualLibrary({ null }, {})
        store.addFavorite(first); store.createFavoriteFolder("other"); store.addFavorite(second, "other")
        val id = NativeFavoriteFolders.id(store.readSnapshot().collections, "other")
        fav(store, "folder/add_top", "fid" to "$id", "add_top" to "true")
        val rows = fav(store, "tab/my_fav").getJSONObject("data").getJSONArray("list")
        assertEquals(LocalVirtualLibrary.DEFAULT_FOLDER, rows.getJSONObject(0).getString("title"))
        assertEquals("other", rows.getJSONObject(1).getString("title"))
        assertTrue(rows.getJSONObject(1).getBoolean("is_top"))
        fav(store, "folder/add_top", "fid" to "$id", "add_top" to "false")
        assertFalse(store.readSnapshot().collections.folderInfo.getValue("other").pinned)
        assertEquals(listOf(second), store.readSnapshot().collections.favorites.getValue("other"))
    }

    @Test fun sequentialNativeDragMovesPersistAndInvalidBatchDoesNotPartiallyApply() {
        var fail = false
        val store = LocalVirtualLibrary({ null }, { if (fail) error("disk full") })
        store.addFavorite(first); store.addFavorite(second); store.addFavorite(first.copy(aid=3, cid=33))
        val id = NativeFavoriteFolders.id(store.readSnapshot().collections, LocalVirtualLibrary.DEFAULT_FOLDER).toString()
        fav(store, "resource/sort", "media_id" to id, "sort" to "0:0:1:2,1:2:2:2")
        assertEquals(listOf(1L, 2L, 3L), store.readSnapshot().collections.favorites.getValue(LocalVirtualLibrary.DEFAULT_FOLDER).map { it.aid })
        val before = store.readSnapshot().collections
        assertThrows(IllegalArgumentException::class.java) { fav(store, "resource/sort", "media_id" to id, "sort" to "0:0:3:2,999:2:2:2") }
        assertEquals(before, store.readSnapshot().collections)
        fail = true
        assertThrows(IllegalStateException::class.java) { fav(store, "resource/sort", "media_id" to id, "sort" to "0:0:3:2") }
        assertEquals(before, store.readSnapshot().collections)
    }

    @Test fun globalSortWorksAcrossFoldersAndSurvivesReopening() {
        var disk: String? = null
        val store = LocalVirtualLibrary({ disk }, { disk=it })
        store.addFavorite(first); store.createFavoriteFolder("other"); store.addFavorite(second, "other")
        fav(store, "resource/sort", "sort" to "0:0:2:2")
        val restored = LocalVirtualLibrary({ disk }, {})
        val rows = fav(restored, "tab/fav").getJSONObject("data").getJSONArray("list")
        assertEquals(2L, rows.getJSONObject(0).getLong("oid"))
        assertEquals(1L, rows.getJSONObject(1).getLong("oid"))
    }

    @Test fun watchLaterNativeCardsKeepProgressAndDeletionDoesNotTouchHistory() {
        val store = LocalVirtualLibrary({ null }, {})
        store.putHistory(first); store.addWatchLater(first); store.addWatchLater(second.copy(positionMs=100000))
        val response = later(store, "v2/list")
        assertEquals(1, response.getInt("ttl")) // Host parser reads this without a null check.
        val rows = response.getJSONObject("data").getJSONArray("list")
        val unfinished = later(store, "v2/list", "sort_field" to "10").getJSONObject("data").getJSONArray("list")
        assertEquals(1, unfinished.length())
        assertEquals(first.aid, unfinished.getJSONObject(0).getLong("aid"))
        val ascending = later(store, "v2/list", "asc" to "true").getJSONObject("data").getJSONArray("list")
        assertEquals(first.aid, ascending.getJSONObject(0).getLong("aid"))
        assertEquals(-1L, rows.getJSONObject(0).getLong("progress"))
        assertEquals(50L, rows.getJSONObject(1).getLong("progress"))
        assertTrue(rows.getJSONObject(1).getString("uri").contains("cid=11"))
        assertEquals(0, later(store, "v2/list", "start_key" to "next").getJSONObject("data").getJSONArray("list").length())
        later(store, "clear", "clean_type" to "1")
        assertEquals(listOf(first), store.readSnapshot().collections.watchLater)
        later(store, "v2/dels", "resources" to "1:2")
        assertTrue(store.readSnapshot().collections.watchLater.isEmpty())
        assertEquals(listOf(first), store.readSnapshot().collections.history)
    }

    @Test fun legacyNativeEditorCreatesAndRenamesPrivateFolderWithoutLosingPinOrVideos() {
        val store = LocalVirtualLibrary({ null }, {})
        val created = fav(store, "folder/add", "title" to "created", "privacy" to "0").getJSONObject("data")
        val id = created.getLong("id").toString()
        assertEquals(3, created.getInt("attr"))
        store.addFavorite(first, "created")
        fav(store, "folder/add_top", "fid" to id, "add_top" to "true")
        val edited = fav(store, "folder/edit", "title" to "renamed", "media_id" to id, "intro" to "local").getJSONObject("data")
        assertEquals(id, edited.getLong("id").toString())
        assertTrue(store.readSnapshot().collections.folderInfo.getValue("renamed").pinned)
        assertEquals(listOf(first), store.readSnapshot().collections.favorites.getValue("renamed"))
    }

    @Test fun watchLaterCopyAndMovePreserveProgressAndFailAtomically() {
        var fail = false
        val store = LocalVirtualLibrary({ null }, { if (fail) error("disk full") })
        store.putHistory(first); store.addWatchLater(first); store.createFavoriteFolder("target")
        val id = NativeFavoriteFolders.id(store.readSnapshot().collections, "target").toString()
        later(store, "copy", "resources" to "1:2", "tar_media_id" to id)
        assertEquals(listOf(first), store.readSnapshot().collections.favorites.getValue("target"))
        assertEquals(listOf(first), store.readSnapshot().collections.watchLater)
        val before = store.readSnapshot().collections
        assertThrows(IllegalArgumentException::class.java) { later(store, "move", "resources" to "1:2,999:2", "tar_media_id" to id) }
        assertEquals(before, store.readSnapshot().collections)
        fail = true
        assertThrows(IllegalStateException::class.java) { later(store, "move", "resources" to "1:2", "tar_media_id" to id) }
        assertEquals(before, store.readSnapshot().collections)
        fail = false
        later(store, "move", "resources" to "1:2", "tar_media_id" to id)
        assertTrue(store.readSnapshot().collections.watchLater.isEmpty())
        assertEquals(listOf(first), store.readSnapshot().collections.favorites.getValue("target"))
        assertEquals(listOf(first), store.readSnapshot().collections.history)
    }

    @Test fun unseenHomeCardCanBeAddedWithoutCreatingPlaybackHistoryAndReopened() {
        var disk: String? = null
        val store = LocalVirtualLibrary({ disk }, { disk = it })
        val unseen = second.copy(positionMs = 0)
        val result = JSONObject(NativeWatchLaterProtocol.respond("/x/v2/history/toview/add",
            mapOf("resources" to "2"), store) { aid -> unseen.takeIf { it.aid == aid } })
        assertEquals(0, result.getInt("code"))
        val restored = LocalVirtualLibrary({ disk }, { error("unexpected write") }).readSnapshot().collections
        assertEquals(listOf(unseen), restored.watchLater)
        assertTrue(restored.history.isEmpty())
    }

    @Test fun singleAddAnswersWithTheHostsOwnAddedPopupFields() {
        // 8.97+ addV2 posts one `aid` form field, not `resources`; the reply is decoded as WatchLaterAddToast.
        val store = LocalVirtualLibrary({ null }, {})
        val data = JSONObject(NativeWatchLaterProtocol.respond("/x/v2/history/toview/add",
            mapOf("aid" to "2", "from" to "", "spmid" to "tm.recommend.0.0", "toview_version" to "v2"), store) { second })
            .getJSONObject("data")
        assertTrue(data.getBoolean("show_toast"))
        assertEquals(1, data.getInt("icon_type"))
        assertEquals("已添加至稍后再看", data.getString("toast"))
        assertEquals("查看", data.getString("jump_text"))
        assertEquals("bilibili://main/playset/watch-later", data.getString("app_jump_link"))
        assertEquals("2", data.getString("avids"))
        assertEquals(listOf(second), store.readSnapshot().collections.watchLater)
    }

    @Test fun resourceIdsReadBatchAndSingleFormsAndRejectNonPositiveIds() {
        assertEquals(setOf(1L, 2L), NativeWatchLaterProtocol.resourceIds(mapOf("resources" to "1:2,2:2", "aid" to "9")))
        assertEquals(setOf(7L), NativeWatchLaterProtocol.resourceIds(mapOf("aid" to "7")))
        assertTrue(NativeWatchLaterProtocol.resourceIds(emptyMap()).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { NativeWatchLaterProtocol.resourceIds(mapOf("aid" to "0")) }
        assertTrue(NativeWatchLaterProtocol.isAdd("/x/v2/history/toview/add"))
        assertTrue(NativeWatchLaterProtocol.isAdd("/x/v3/fav/toview/adds"))
        assertFalse(NativeWatchLaterProtocol.isAdd("/x/v2/history/toview/v2/dels"))
    }

    @Test fun addingKnownVideoKeepsResumeProgressAndMissingMetadataFailsAtomically() {
        val store = LocalVirtualLibrary({ null }, {})
        store.putHistory(first)
        NativeWatchLaterProtocol.respond("/x/v2/history/toview/add", mapOf("resources" to "1"), store) {
            error("Known progress must not be replaced by a fresh metadata request")
        }
        assertEquals(listOf(first), store.readSnapshot().collections.watchLater)
        val before = store.readSnapshot().collections
        assertThrows(IllegalArgumentException::class.java) {
            NativeWatchLaterProtocol.respond("/x/v2/history/toview/add", mapOf("resources" to "2,3"), store) {
                if (it == 2L) second else null
            }
        }
        assertEquals(before, store.readSnapshot().collections)
    }

    @Test fun incognitoWatchLaterAddDoesNotReadOrModifyThePersistentGuestLibrary() {
        var disk: String? = null
        val guest = LocalVirtualLibrary({ disk }, { disk = it })
        guest.addWatchLater(first)
        val originalDisk = disk
        val privateSession = LocalVirtualLibrary({ error("private disk read") }, { error("private disk write") })
        privateSession.enterIncognito()
        NativeWatchLaterProtocol.respond("/x/v2/history/toview/add", mapOf("resources" to "2"), privateSession) { second }
        assertEquals(listOf(second), privateSession.readSnapshot().collections.watchLater)
        assertEquals(originalDisk, disk)
        privateSession.resetIncognito()
        assertTrue(privateSession.readSnapshot().collections.watchLater.isEmpty())
        assertEquals(listOf(first), LocalVirtualLibrary({ disk }, {}).readSnapshot().collections.watchLater)
    }

    @Test fun incognitoPinSortAndWatchLaterLeaveNoPersistentChanges() {
        val store = LocalVirtualLibrary({ error("disk read") }, { error("disk write") })
        store.enterIncognito(); store.addFavorite(first); store.addFavorite(second); store.addWatchLater(first)
        val id = NativeFavoriteFolders.id(store.readSnapshot().collections, LocalVirtualLibrary.DEFAULT_FOLDER)
        fav(store, "folder/add_top", "fid" to "$id", "add_top" to "true")
        fav(store, "resource/sort", "sort" to "0:0:1:2")
        later(store, "v2/list"); later(store, "v2/dels", "resources" to "1:2")
        store.resetIncognito()
        assertEquals(LocalVirtualLibrary.Collections(), store.readSnapshot().collections)
    }
}
