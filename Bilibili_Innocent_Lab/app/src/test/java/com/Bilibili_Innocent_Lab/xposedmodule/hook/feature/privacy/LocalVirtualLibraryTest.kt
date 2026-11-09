package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy.LocalVirtualLibrary.Collections
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy.LocalVirtualLibrary.CorruptLibraryException
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy.LocalVirtualLibrary.Follow
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy.LocalVirtualLibrary.Video
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LocalVirtualLibraryTest {
    @Test
    fun ordinaryLibraryLoadsLazilyOnceAndDoesNotSaveReadsOrNoOps() {
        val storage = Storage()
        val library = storage.library()
        assertFalse(library.isIncognito)
        library.exitIncognito()
        assertEquals(0, storage.loads)
        assertEquals(Collections(), library.readSnapshot().collections)
        assertEquals(Collections(), library.readSnapshot().collections)
        library.clearHistory()
        library.removeHistory(1, 1)
        library.removeFavorite(1, 1)
        library.removeWatchLater(1, 1)
        library.unfollow(1)
        library.deleteFavoriteFolder("missing")
        library.writeSnapshot(library.readSnapshot())
        assertEquals(1, storage.loads)
        assertEquals(0, storage.saves)
        assertNull(storage.text)
    }

    @Test
    fun firstIncognitoSessionNeverLoadsOrSavesEvenWhenStorageWouldFail() {
        val library = LocalVirtualLibrary(
            load = { throw AssertionError("Incognito must not load") },
            save = { throw AssertionError("Incognito must not save") }
        )
        library.enterIncognito()
        populate(library, 1)
        assertTrue(library.readSnapshot().incognito)
        library.resetIncognito()
        assertEquals(Collections(), library.readSnapshot().collections)
        library.follow(Follow(2, "private"))
        library.enterIncognito()
        assertEquals(listOf(Follow(2, "private")), library.readSnapshot().collections.follows)
        library.exitIncognito()
        library.exitIncognito()
        library.enterIncognito()
        assertEquals(Collections(), library.readSnapshot().collections)
    }

    @Test
    fun everyCollectionIsIsolatedAndOrdinaryMemoryIsRestoredAfterExit() {
        val storage = Storage()
        val library = storage.library()
        populate(library, 1)
        val ordinary = library.readSnapshot().collections
        val disk = storage.text
        val loads = storage.loads
        val saves = storage.saves
        library.enterIncognito()
        assertEquals(Collections(), library.readSnapshot().collections)
        assertNull(library.findHistory(1, 1))
        populate(library, 20)
        val privateSnapshot = library.readSnapshot()
        library.writeSnapshot(privateSnapshot.copy(collections = privateSnapshot.collections.copy(follows = listOf(Follow(99, "secret")))))
        library.clearHistory()
        assertEquals(listOf(Follow(99, "secret")), library.readSnapshot().collections.follows)
        assertEquals(disk, storage.text)
        assertEquals(loads, storage.loads)
        assertEquals(saves, storage.saves)
        library.exitIncognito()
        assertFalse(library.isIncognito)
        assertEquals(ordinary, library.readSnapshot().collections)
        assertEquals(loads, storage.loads)
        assertEquals(saves, storage.saves)
        library.enterIncognito()
        assertEquals(Collections(), library.readSnapshot().collections)
        library.exitIncognito()
        library.follow(Follow(100, "ordinary again"))
        assertEquals(saves + 1, storage.saves)
        assertFalse(storage.text!!.contains("secret"))
        assertEquals(library.readSnapshot().collections, storage.library().readSnapshot().collections)
    }

    @Test
    fun resetDropsAllPrivateCollectionsAndInvalidatesSnapshotsWithoutTouchingGuest() {
        val storage = Storage()
        val library = storage.library()
        populate(library, 1)
        val ordinary = library.readSnapshot().collections
        val disk = storage.text
        library.enterIncognito()
        populate(library, 9)
        val previousIdentity = library.readSnapshot()
        library.resetIncognito()
        assertEquals(Collections(), library.readSnapshot().collections)
        assertThrows(IllegalStateException::class.java) { library.writeSnapshot(previousIdentity) }
        library.resetIncognito()
        assertEquals(Collections(), library.readSnapshot().collections)
        library.exitIncognito()
        assertEquals(ordinary, library.readSnapshot().collections)
        assertEquals(disk, storage.text)
        assertThrows(IllegalStateException::class.java) { library.resetIncognito() }
        assertEquals(ordinary, library.readSnapshot().collections)
    }

    @Test
    fun corruptOrdinaryLibraryDoesNotPreventAnIndependentPrivateSession() {
        val storage = Storage(text = "private broken original")
        val library = storage.library()
        library.enterIncognito()
        populate(library, 9)
        library.exitIncognito()
        assertEquals(0, storage.loads)
        assertThrows(CorruptLibraryException::class.java) { library.readSnapshot() }
        library.enterIncognito()
        assertEquals(Collections(), library.readSnapshot().collections)
        assertEquals(1, storage.loads)
        assertEquals(0, storage.saves)
        assertEquals("private broken original", storage.text)
    }

    @Test
    fun jsonRoundTripPreservesAllCollectionsMetadataLargeIdsAndExactTimes() {
        val storage = Storage()
        val library = storage.library()
        val detailed = video(LocalVirtualLibrary.MAX_ID, cid = 4_294_967_297L).copy(
            title = "标题 \"quoted\" \\ slash\n多P",
            durationMs = LocalVirtualLibrary.MAX_MEDIA_MS,
            positionMs = 0,
            updatedAtMs = LocalVirtualLibrary.MAX_TIMESTAMP_MS,
            cover = "https://example.invalid/封面?a=1&b=2",
            authorMid = 8_000_000_000L,
            authorName = "作者\tName"
        )
        library.putHistory(detailed)
        library.putHistory(detailed.copy(cid = 2, cover = null, positionMs = 17))
        library.createFavoriteFolder("学习\"收藏")
        library.addFavorite(detailed, "学习\"收藏")
        library.addFavorite(detailed.copy(cid = 3, cover = ""))
        library.addWatchLater(detailed)
        library.follow(Follow(LocalVirtualLibrary.MAX_ID, "关注"))
        assertNotNull(storage.text)
        assertEquals(1, JSONObject(storage.text!!).getInt("version"))
        assertEquals(library.readSnapshot().collections, storage.library().readSnapshot().collections)
        val reloaded = storage.library()
        assertEquals(0L, reloaded.findHistory(detailed.aid, detailed.cid)!!.positionMs)
        assertNull(reloaded.findHistory(detailed.aid, 2)!!.cover)
        assertEquals("", reloaded.readSnapshot().collections.favorites.getValue(defaultFolder).single().cover)
    }

    @Test
    fun progressIsExactPerAidAndCidIncludingZeroCompletionAndRestart() {
        forEachMode { library, _ ->
            val first = video(1, 11)
            val second = video(1, 12)
            val otherAid = video(2, 11)
            library.putHistory(first)
            library.putHistory(second)
            library.putHistory(otherAid)
            library.addFavorite(first.copy(title = "favorite metadata"))
            library.addWatchLater(first.copy(title = "watch later metadata"))
            assertEquals(first.copy(positionMs = 0, updatedAtMs = 77), library.saveProgress(1, 11, 0, 77))
            assertEquals(0L, library.findHistory(1, 11)!!.positionMs)
            assertEquals(second, library.findHistory(1, 12))
            assertEquals(otherAid, library.findHistory(2, 11))
            val complete = library.saveProgress(1, 11, first.durationMs + 3, 78)
            assertEquals(first.durationMs + 3, complete.positionMs)
            assertTrue(complete.isComplete)
            assertEquals(first.title, complete.title)
            assertEquals(first.cover, complete.cover)
            assertEquals(first.authorMid, complete.authorMid)
            assertEquals(first.authorName, complete.authorName)
            val snapshot = library.readSnapshot().collections
            assertEquals("favorite metadata", snapshot.favorites.getValue(defaultFolder).single().title)
            assertEquals("watch later metadata", snapshot.watchLater.single().title)
            assertEquals(complete.positionMs, snapshot.watchLater.single().positionMs)
            assertEquals(complete.positionMs, snapshot.favorites.getValue(defaultFolder).single().positionMs)
            assertEquals(3, snapshot.history.size)
            assertFalse(library.saveProgress(1, 11, 0, 79).isComplete)
            val unknownDuration = library.saveProgress(1, 11, 123, 80, durationMs = 0)
            assertEquals(0L, unknownDuration.durationMs)
            assertEquals(123L, unknownDuration.positionMs)
            assertFalse(unknownDuration.isComplete)
            assertNull(library.findHistory(9, 11))
        }
    }

    @Test
    fun progressCanUseCollectionMetadataButNeverInventsAnUnknownVideo() {
        forEachMode { library, storage ->
            library.addFavorite(video(1))
            library.addWatchLater(video(2))
            assertEquals(video(1).copy(positionMs = 9, updatedAtMs = 10), library.saveProgress(1, 1, 9, 10))
            assertEquals(video(2).copy(positionMs = 0, updatedAtMs = 0), library.saveProgress(2, 1, 0, 0))
            val before = library.readSnapshot()
            val saves = storage.saves
            assertThrows(IllegalStateException::class.java) { library.saveProgress(3, 1, 0, 1) }
            assertEquals(before, library.readSnapshot())
            assertEquals(saves, storage.saves)
        }
    }

    @Test
    fun crudWorksInBothModesAndCollectionRemovalsAreIndependent() {
        forEachMode { library, _ ->
            val first = video(1)
            val second = video(1, 2)
            library.putHistory(first)
            library.putHistory(second)
            library.putHistory(first.copy(title = "updated"))
            assertEquals(2, library.readSnapshot().collections.history.size)
            assertEquals("updated", library.findHistory(1, 1)!!.title)
            library.createFavoriteFolder("学习")
            library.addFavorite(first)
            library.addFavorite(first, "学习")
            library.addFavorite(first.copy(title = "favorite update"), "学习")
            library.addFavorite(second, "学习")
            library.renameFavoriteFolder("学习", "工作")
            library.addWatchLater(first)
            library.addWatchLater(first.copy(title = "watch later update"))
            library.follow(Follow(1, "old"))
            library.follow(Follow(1, "renamed"))
            library.follow(Follow(2, "other"))
            assertEquals(listOf(Follow(2, "other"), Follow(1, "renamed")), library.readSnapshot().collections.follows)
            library.removeHistory(1, 1)
            assertNull(library.findHistory(1, 1))
            assertEquals(second, library.findHistory(1, 2))
            library.clearHistory()
            assertTrue(library.readSnapshot().collections.history.isEmpty())
            assertEquals(2, library.readSnapshot().collections.favorites.getValue("工作").size)
            library.removeFavorite(1, 1, "工作")
            assertEquals(listOf(second), library.readSnapshot().collections.favorites.getValue("工作"))
            assertEquals(listOf(first), library.readSnapshot().collections.favorites.getValue(defaultFolder))
            library.removeFavorite(1, 1)
            library.deleteFavoriteFolder("工作")
            library.removeWatchLater(1, 1)
            library.unfollow(1)
            library.unfollow(2)
            assertEquals(Collections(), library.readSnapshot().collections)
        }
    }

    @Test
    fun defaultFolderAndFolderNamesAreValidatedWithoutMutatingState() {
        val storage = Storage()
        val library = storage.library()
        library.createFavoriteFolder("existing")
        val before = library.readSnapshot()
        val saves = storage.saves
        val actions: List<() -> Unit> = listOf(
            { library.createFavoriteFolder("existing") },
            { library.createFavoriteFolder("") },
            { library.createFavoriteFolder(" ") },
            { library.createFavoriteFolder(" trailing ") },
            { library.createFavoriteFolder("x".repeat(LocalVirtualLibrary.MAX_FOLDER_NAME_LENGTH + 1)) },
            { library.deleteFavoriteFolder(defaultFolder) },
            { library.renameFavoriteFolder(defaultFolder, "other") },
            { library.renameFavoriteFolder("existing", defaultFolder) },
            { library.renameFavoriteFolder("missing", "new") },
            { library.addFavorite(video(1), "missing") },
            { library.removeFavorite(1, 1, "missing") }
        )
        actions.forEach { action -> assertThrows(IllegalArgumentException::class.java) { action() } }
        library.renameFavoriteFolder("existing", "existing")
        assertEquals(before, library.readSnapshot())
        assertEquals(saves, storage.saves)
    }

    @Test
    fun boundedHistoryEvictsByWriteOrderAndExplicitCollectionsRejectOverflowInBothModes() {
        listOf(false, true).forEach { incognito ->
            val storage = Storage()
            val library = storage.library(maxEntries = 2, maxFolders = 2)
            if (incognito) library.enterIncognito()
            library.putHistory(video(1))
            library.putHistory(video(2))
            library.saveProgress(1, 1, 0, 0)
            library.putHistory(video(3))
            assertEquals(listOf(3L, 1L), library.readSnapshot().collections.history.map { it.aid })
            library.createFavoriteFolder("second")
            library.addFavorite(video(1))
            library.addFavorite(video(2))
            library.addWatchLater(video(1))
            library.addWatchLater(video(2))
            library.follow(Follow(1, "one"))
            library.follow(Follow(2, "two"))
            val before = library.readSnapshot()
            val saves = storage.saves
            assertThrows(IllegalArgumentException::class.java) { library.createFavoriteFolder("overflow") }
            assertThrows(IllegalArgumentException::class.java) { library.addFavorite(video(3)) }
            assertThrows(IllegalArgumentException::class.java) { library.addWatchLater(video(3)) }
            assertThrows(IllegalArgumentException::class.java) { library.follow(Follow(3, "overflow")) }
            assertEquals(before, library.readSnapshot())
            assertEquals(saves, storage.saves)
            library.addFavorite(video(2).copy(title = "update at capacity"))
            library.addWatchLater(video(2).copy(title = "update at capacity"))
            library.follow(Follow(2, "update at capacity"))
            library.deleteFavoriteFolder("second")
            library.createFavoriteFolder("replacement")
            if (incognito) {
                assertEquals(0, storage.loads)
                assertEquals(0, storage.saves)
            }
        }
    }

    @Test
    fun invalidHostValuesAreRejectedAndZeroMetadataIsNotMistakenForMissing() {
        val storage = Storage()
        val library = storage.library()
        val before = library.readSnapshot()
        val invalidVideos = listOf(
            video(0), video(-1), video(LocalVirtualLibrary.MAX_ID + 1), video(1, 0), video(1, -1),
            video(1, LocalVirtualLibrary.MAX_ID + 1),
            video(1).copy(authorMid = -1), video(1).copy(authorMid = LocalVirtualLibrary.MAX_ID + 1),
            video(1).copy(positionMs = -1), video(1).copy(durationMs = -1),
            video(1).copy(positionMs = LocalVirtualLibrary.MAX_MEDIA_MS + 1),
            video(1).copy(durationMs = LocalVirtualLibrary.MAX_MEDIA_MS + 1),
            video(1).copy(updatedAtMs = -1), video(1).copy(updatedAtMs = LocalVirtualLibrary.MAX_TIMESTAMP_MS + 1),
            video(1).copy(title = "t".repeat(LocalVirtualLibrary.MAX_TITLE_LENGTH + 1)),
            video(1).copy(cover = "c".repeat(LocalVirtualLibrary.MAX_COVER_LENGTH + 1)),
            video(1).copy(authorName = "a".repeat(LocalVirtualLibrary.MAX_NAME_LENGTH + 1)),
            video(1).copy(title = "nul\u0000text")
        )
        invalidVideos.forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { library.putHistory(invalid) }
            assertThrows(IllegalArgumentException::class.java) { library.addFavorite(invalid) }
            assertThrows(IllegalArgumentException::class.java) { library.addWatchLater(invalid) }
        }
        listOf(Follow(0, "invalid"), Follow(-1, "invalid"), Follow(LocalVirtualLibrary.MAX_ID + 1, "invalid"),
            Follow(1, "n".repeat(LocalVirtualLibrary.MAX_NAME_LENGTH + 1))).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { library.follow(invalid) }
        }
        assertThrows(IllegalArgumentException::class.java) { library.findHistory(0, 1) }
        assertThrows(IllegalArgumentException::class.java) { library.removeHistory(0, 1) }
        assertThrows(IllegalArgumentException::class.java) { library.removeFavorite(1, 0) }
        assertThrows(IllegalArgumentException::class.java) { library.removeWatchLater(-1, 1) }
        assertThrows(IllegalArgumentException::class.java) { library.unfollow(0) }
        assertEquals(before, library.readSnapshot())
        assertEquals(0, storage.saves)
        val zero = Video(1, 1, "", 0, 0, 0)
        library.putHistory(zero)
        assertEquals(zero, storage.library().findHistory(1, 1))
        val valid = library.readSnapshot()
        assertThrows(IllegalArgumentException::class.java) { library.saveProgress(1, 1, -1, 1) }
        assertThrows(IllegalArgumentException::class.java) { library.saveProgress(1, 1, 1, -1) }
        assertThrows(IllegalArgumentException::class.java) { library.saveProgress(1, 1, 1, 1, -1) }
        assertEquals(valid, library.readSnapshot())
    }

    @Test
    fun constructorLimitsCannotBeUnbounded() {
        val storage = Storage()
        assertThrows(IllegalArgumentException::class.java) { storage.library(maxEntries = 0) }
        assertThrows(IllegalArgumentException::class.java) { storage.library(maxEntries = LocalVirtualLibrary.MAX_ENTRIES + 1) }
        assertThrows(IllegalArgumentException::class.java) { storage.library(maxFolders = 0) }
        assertThrows(IllegalArgumentException::class.java) { storage.library(maxFolders = LocalVirtualLibrary.MAX_FOLDERS + 1) }
        assertEquals(0, storage.loads)
    }

    @Test
    fun snapshotsAreDeeplyUnmodifiableAndDetachCallerOwnedCollections() {
        val storage = Storage()
        val library = storage.library()
        val history = mutableListOf(video(1))
        val favorites = mutableListOf(video(2))
        val folders = linkedMapOf<String, List<Video>>(defaultFolder to favorites)
        val watchLater = mutableListOf(video(3))
        val follows = mutableListOf(Follow(4, "follow"))
        library.writeSnapshot(library.readSnapshot().copy(collections = Collections(history, folders, watchLater, follows)))
        val snapshot = library.readSnapshot()
        val expected = snapshot.collections
        history.clear()
        favorites.clear()
        folders.clear()
        watchLater.clear()
        follows.clear()
        assertEquals(expected, library.readSnapshot().collections)
        assertEquals(expected, storage.library().readSnapshot().collections)
        assertThrows(UnsupportedOperationException::class.java) { (expected.history as MutableList<Video>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (expected.watchLater as MutableList<Video>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (expected.follows as MutableList<Follow>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (expected.favorites as MutableMap<String, List<Video>>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (expected.favorites.getValue(defaultFolder) as MutableList<Video>).clear() }
        library.clearHistory()
        library.removeFavorite(2, 1)
        library.removeWatchLater(3, 1)
        library.unfollow(4)
        assertEquals(1, snapshot.collections.history.size)
        assertEquals(1, snapshot.collections.favorites.getValue(defaultFolder).size)
        assertEquals(1, snapshot.collections.watchLater.size)
        assertEquals(1, snapshot.collections.follows.size)
    }

    @Test
    fun staleSnapshotsCannotCrossWritesModesResetsOrInstances() {
        val storage = Storage()
        val library = storage.library()
        val initial = library.readSnapshot()
        library.putHistory(video(1))
        assertThrows(IllegalStateException::class.java) { library.writeSnapshot(initial) }
        val ordinary = library.readSnapshot()
        assertThrows(IllegalStateException::class.java) { storage.library().writeSnapshot(ordinary) }
        library.enterIncognito()
        assertThrows(IllegalStateException::class.java) { library.writeSnapshot(ordinary) }
        library.putHistory(video(9))
        val privateSnapshot = library.readSnapshot()
        library.exitIncognito()
        assertThrows(IllegalStateException::class.java) { library.writeSnapshot(privateSnapshot) }
        assertThrows(IllegalStateException::class.java) { library.writeSnapshot(ordinary) }
        library.enterIncognito()
        assertThrows(IllegalStateException::class.java) { library.writeSnapshot(privateSnapshot) }
        library.resetIncognito()
        assertThrows(IllegalStateException::class.java) { library.writeSnapshot(privateSnapshot) }
        library.exitIncognito()
        assertEquals(listOf(video(1)), library.readSnapshot().collections.history)
        assertEquals(1, storage.saves)
    }

    @Test
    fun invalidSnapshotsDoNotEvictOrReplaceExistingState() {
        val storage = Storage()
        val library = storage.library(maxEntries = 2, maxFolders = 2)
        populate(library, 1)
        val before = library.readSnapshot()
        val saves = storage.saves
        val invalid = listOf(
            before.collections.copy(history = listOf(video(1), video(1))),
            before.collections.copy(history = listOf(video(1), video(2), video(3))),
            before.collections.copy(favorites = emptyMap()),
            before.collections.copy(favorites = mapOf("not default" to emptyList())),
            before.collections.copy(favorites = mapOf(defaultFolder to emptyList(), "a" to emptyList(), "b" to emptyList())),
            before.collections.copy(favorites = mapOf(defaultFolder to listOf(video(1), video(1)))),
            before.collections.copy(watchLater = listOf(video(1), video(1))),
            before.collections.copy(follows = listOf(Follow(1, "a"), Follow(1, "b"))),
            before.collections.copy(follows = listOf(Follow(0, "invalid")))
        )
        invalid.forEach { collections ->
            assertThrows(IllegalArgumentException::class.java) { library.writeSnapshot(before.copy(collections = collections)) }
            assertEquals(before, library.readSnapshot())
        }
        assertEquals(saves, storage.saves)
    }

    @Test
    fun malformedJsonIsNeverSilentlyReplacedAndErrorsDoNotExposeBody() {
        val valid = encodedPopulatedLibrary()
        val variants = listOf(
            "", " ", "null", "[]", "{}", "{", "sensitive original not json", valid + " trailing",
            valid.dropLast(1) + ",}", valid.replace("\"version\":1", "\"version\":1,\"version\":1"),
            valid.replace("\"version\":1", "\"version\":1,\"ver\\u0073ion\":1"),
            valid.replace("\"version\":1", "'version':1"),
            valid.replace("\"version\":1", "version:1"),
            valid.replace("\"version\":1", "\"version\":01"),
            valid.replace("\"version\":1", "\"version\":1/* comment */"),
            valid.replace("\"version\":1", "\"version\":1,"),
            "[".repeat(20) + "0" + "]".repeat(20),
            " ".repeat(LocalVirtualLibrary.MAX_JSON_LENGTH + 1)
        )
        variants.forEach { malformed ->
            val storage = Storage(text = malformed)
            val library = storage.library()
            val error = assertThrows(CorruptLibraryException::class.java) { library.readSnapshot() }
            assertEquals("Local library JSON is invalid or unsupported", error.message)
            assertNull(error.cause)
            assertThrows(CorruptLibraryException::class.java) { library.follow(Follow(2, "new")) }
            assertThrows(CorruptLibraryException::class.java) { library.clearHistory() }
            assertEquals(malformed, storage.text)
            assertEquals(0, storage.saves)
        }
    }

    @Test
    fun jsonSchemaTypesDuplicateEntriesAndLimitsAreStrict() {
        val valid = encodedPopulatedLibrary()
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("version", 2) },
            { it.put("version", "1") },
            { it.put("version", 1.5) },
            { it.put("unknown", true) },
            { it.remove("history") },
            { it.put("history", JSONObject.NULL) },
            { it.put("history", "[]") },
            { it.getJSONArray("history").getJSONObject(0).put("aid", "1") },
            { it.getJSONArray("history").getJSONObject(0).put("cid", 1.5) },
            { it.getJSONArray("history").getJSONObject(0).put("cid", LocalVirtualLibrary.MAX_ID + 1) },
            { it.getJSONArray("history").getJSONObject(0).put("positionMs", -1) },
            { it.getJSONArray("history").getJSONObject(0).remove("positionMs") },
            { it.getJSONArray("history").getJSONObject(0).put("title", JSONObject.NULL) },
            { it.getJSONArray("history").getJSONObject(0).put("cover", 99) },
            { it.getJSONArray("history").getJSONObject(0).put("authorName", false) },
            { it.getJSONArray("history").getJSONObject(0).put("title", "x".repeat(LocalVirtualLibrary.MAX_TITLE_LENGTH + 1)) },
            { it.getJSONArray("history").put(it.getJSONArray("history").getJSONObject(0)) },
            { it.getJSONArray("watchLater").put(it.getJSONArray("watchLater").getJSONObject(0)) },
            { it.getJSONArray("follows").put(it.getJSONArray("follows").getJSONObject(0)) },
            { it.getJSONArray("follows").getJSONObject(0).put("mid", 0) },
            { it.getJSONArray("favorites").put(it.getJSONArray("favorites").getJSONObject(0)) },
            { it.getJSONArray("favorites").getJSONObject(0).getJSONArray("videos").apply { put(getJSONObject(0)) } },
            { it.put("favorites", JSONArray()) }
        )
        mutations.forEach { mutate ->
            val json = JSONObject(valid)
            mutate(json)
            val storage = Storage(json.toString())
            assertThrows(CorruptLibraryException::class.java) { storage.library().putHistory(video(9)) }
            assertEquals(json.toString(), storage.text)
            assertEquals(0, storage.saves)
        }
        val tooMany = JSONObject(valid)
        val item = tooMany.getJSONArray("history").getJSONObject(0)
        tooMany.put("history", JSONArray().put(item).put(JSONObject(item.toString()).put("aid", 2)))
        val storage = Storage(tooMany.toString())
        assertThrows(CorruptLibraryException::class.java) { storage.library(maxEntries = 1).readSnapshot() }
        assertThrows(CorruptLibraryException::class.java) { Storage(valid).library(maxFolders = 1).readSnapshot() }
        assertEquals(0, storage.saves)
    }

    @Test
    fun failedLoadPropagatesAndCanBeRetriedWithoutAnEmptyFallback() {
        val failure = IllegalStateException("storage unavailable")
        val storage = Storage(encodedPopulatedLibrary()).apply { loadFailure = failure }
        val library = storage.library()
        assertSame(failure, assertThrows(IllegalStateException::class.java) { library.readSnapshot() })
        assertSame(failure, assertThrows(IllegalStateException::class.java) { library.clearHistory() })
        assertEquals(0, storage.saves)
        storage.loadFailure = null
        assertEquals(listOf(video(1)), library.readSnapshot().collections.history)
        assertEquals(3, storage.loads)
    }

    @Test
    fun failedSaveLeavesMemoryDiskAndSnapshotStampUnchangedAndCanRetry() {
        val storage = Storage()
        val library = storage.library()
        populate(library, 1)
        val before = library.readSnapshot()
        val disk = storage.text
        val failure = IllegalStateException("disk full")
        storage.saveFailure = failure
        val candidate = before.copy(collections = before.collections.copy(history = listOf(video(9))))
        val actions: List<() -> Unit> = listOf(
            { library.putHistory(video(2)) },
            { library.saveProgress(1, 1, 0, 9) },
            { library.clearHistory() },
            { library.removeFavorite(1, 1) },
            { library.removeWatchLater(1, 1) },
            { library.unfollow(1) },
            { library.deleteFavoriteFolder("folder") },
            { library.writeSnapshot(candidate) }
        )
        actions.forEach { action ->
            assertSame(failure, assertThrows(IllegalStateException::class.java) { action() })
            assertEquals(before, library.readSnapshot())
            assertEquals(disk, storage.text)
        }
        library.enterIncognito()
        populate(library, 20)
        library.exitIncognito()
        assertEquals(before.collections, library.readSnapshot().collections)
        storage.saveFailure = null
        val fresh = library.readSnapshot().copy(collections = candidate.collections)
        storage.saveFailure = failure
        assertThrows(IllegalStateException::class.java) { library.writeSnapshot(fresh) }
        storage.saveFailure = null
        library.writeSnapshot(fresh)
        assertEquals(listOf(video(9)), library.readSnapshot().collections.history)
        assertEquals(library.readSnapshot().collections, storage.library().readSnapshot().collections)
    }

    @Test
    fun firstFailedSaveDoesNotCreateAGhostEntry() {
        val storage = Storage().apply { saveFailure = IllegalStateException("write failed") }
        val library = storage.library()
        assertThrows(IllegalStateException::class.java) { library.follow(Follow(1, "not committed")) }
        assertEquals(Collections(), library.readSnapshot().collections)
        assertNull(storage.text)
        storage.saveFailure = null
        library.follow(Follow(2, "committed"))
        assertEquals(listOf(Follow(2, "committed")), library.readSnapshot().collections.follows)
    }

    @Test
    fun reentrantPersistenceCannotChangeModeOrBypassCommitOrdering() {
        lateinit var library: LocalVirtualLibrary
        var reenterLoad = true
        var reenterSave = false
        library = LocalVirtualLibrary(
            load = {
                if (reenterLoad) library.enterIncognito()
                null
            },
            save = { if (reenterSave) library.enterIncognito() }
        )
        assertThrows(IllegalStateException::class.java) { library.readSnapshot() }
        assertFalse(library.isIncognito)
        reenterLoad = false
        val before = library.readSnapshot()
        reenterSave = true
        assertThrows(IllegalStateException::class.java) { library.putHistory(video(1)) }
        assertFalse(library.isIncognito)
        assertEquals(before, library.readSnapshot())
        reenterSave = false
        library.putHistory(video(1))
        assertEquals(video(1), library.findHistory(1, 1))
    }

    @Test
    fun concurrentUpdatesAreSerializedWithoutLostWritesInEitherMode() {
        listOf(false, true).forEach { incognito ->
            val storage = Storage()
            val library = storage.library()
            if (incognito) library.enterIncognito()
            val pool = Executors.newFixedThreadPool(4)
            val start = CountDownLatch(1)
            try {
                val futures = (1..4).map { worker ->
                    pool.submit {
                        assertTrue(start.await(10, TimeUnit.SECONDS))
                        repeat(20) { index ->
                            library.follow(Follow(worker * 100L + index, "worker"))
                            val snapshot = library.readSnapshot()
                            assertEquals(snapshot.collections.follows.size, snapshot.collections.follows.map { it.mid }.toSet().size)
                        }
                    }
                }
                start.countDown()
                futures.forEach { it.get(30, TimeUnit.SECONDS) }
                assertEquals(80, library.readSnapshot().collections.follows.size)
                if (incognito) {
                    assertEquals(0, storage.loads)
                    assertEquals(0, storage.saves)
                } else {
                    assertEquals(1, storage.loads)
                    assertEquals(80, storage.saves)
                    assertEquals(library.readSnapshot().collections, storage.library().readSnapshot().collections)
                }
            } finally {
                start.countDown()
                pool.shutdownNow()
                assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
            }
        }
    }

    private fun forEachMode(block: (LocalVirtualLibrary, Storage) -> Unit) {
        listOf(false, true).forEach { incognito ->
            val storage = Storage()
            val library = storage.library()
            if (incognito) library.enterIncognito()
            block(library, storage)
            if (incognito) {
                assertEquals(0, storage.loads)
                assertEquals(0, storage.saves)
            }
        }
    }

    private fun video(aid: Long, cid: Long = 1) = Video(
        aid = aid, cid = cid, title = "video-$aid-$cid", durationMs = 100_003,
        positionMs = 12_345, updatedAtMs = 1_234_567_890_123,
        cover = "https://example.invalid/cover", authorMid = 42, authorName = "author"
    )

    private fun populate(library: LocalVirtualLibrary, aid: Long) {
        library.putHistory(video(aid))
        library.createFavoriteFolder("folder")
        library.addFavorite(video(aid))
        library.addFavorite(video(aid), "folder")
        library.addWatchLater(video(aid))
        library.follow(Follow(aid, "follow-$aid"))
    }

    private fun encodedPopulatedLibrary(): String {
        val storage = Storage()
        populate(storage.library(), 1)
        return storage.text!!
    }

    private class Storage(var text: String? = null) {
        var loads = 0
        var saves = 0
        var loadFailure: RuntimeException? = null
        var saveFailure: RuntimeException? = null

        fun library(maxEntries: Int = LocalVirtualLibrary.MAX_ENTRIES, maxFolders: Int = LocalVirtualLibrary.MAX_FOLDERS) =
            LocalVirtualLibrary(
                load = {
                    loads++
                    loadFailure?.let { throw it }
                    text
                },
                save = {
                    saves++
                    saveFailure?.let { throw it }
                    text = it
                },
                maxEntries = maxEntries,
                maxFolders = maxFolders
            )
    }

    private companion object {
        const val defaultFolder = LocalVirtualLibrary.DEFAULT_FOLDER
    }
}
