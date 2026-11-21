package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy.LocalVirtualLibrary.Collections
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy.LocalVirtualLibrary.CorruptLibraryException
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy.LocalVirtualLibrary.Follow
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy.LocalVirtualLibrary.Video
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalVirtualLibraryTest {
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

    fun constructorLimitsCannotBeUnbounded() {
        val storage = Storage()
        assertThrows(IllegalArgumentException::class.java) { storage.library(maxEntries = 0) }
        assertThrows(IllegalArgumentException::class.java) { storage.library(maxEntries = LocalVirtualLibrary.MAX_ENTRIES + 1) }
        assertThrows(IllegalArgumentException::class.java) { storage.library(maxFolders = 0) }
        assertThrows(IllegalArgumentException::class.java) { storage.library(maxFolders = LocalVirtualLibrary.MAX_FOLDERS + 1) }
        assertEquals(0, storage.loads)
    }

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

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (LocalVirtualLibraryTest) -> Unit) {
            val s = LocalVirtualLibraryTest()
            block(s)
        }
        runIsolated { s -> s.ordinaryLibraryLoadsLazilyOnceAndDoesNotSaveReadsOrNoOps() }
        runIsolated { s -> s.firstIncognitoSessionNeverLoadsOrSavesEvenWhenStorageWouldFail() }
        runIsolated { s -> s.everyCollectionIsIsolatedAndOrdinaryMemoryIsRestoredAfterExit() }
        runIsolated { s -> s.resetDropsAllPrivateCollectionsAndInvalidatesSnapshotsWithoutTouchingGuest() }
        runIsolated { s -> s.corruptOrdinaryLibraryDoesNotPreventAnIndependentPrivateSession() }
        runIsolated { s -> s.jsonRoundTripPreservesAllCollectionsMetadataLargeIdsAndExactTimes() }
        runIsolated { s -> s.progressIsExactPerAidAndCidIncludingZeroCompletionAndRestart() }
        runIsolated { s -> s.progressCanUseCollectionMetadataButNeverInventsAnUnknownVideo() }
        runIsolated { s -> s.crudWorksInBothModesAndCollectionRemovalsAreIndependent() }
        runIsolated { s -> s.defaultFolderAndFolderNamesAreValidatedWithoutMutatingState() }
        runIsolated { s -> s.boundedHistoryEvictsByWriteOrderAndExplicitCollectionsRejectOverflowInBothModes() }
        runIsolated { s -> s.invalidHostValuesAreRejectedAndZeroMetadataIsNotMistakenForMissing() }
        runIsolated { s -> s.constructorLimitsCannotBeUnbounded() }
        runIsolated { s -> s.snapshotsAreDeeplyUnmodifiableAndDetachCallerOwnedCollections() }
        runIsolated { s -> s.staleSnapshotsCannotCrossWritesModesResetsOrInstances() }
        runIsolated { s -> s.invalidSnapshotsDoNotEvictOrReplaceExistingState() }
        runIsolated { s -> s.malformedJsonIsNeverSilentlyReplacedAndErrorsDoNotExposeBody() }
        runIsolated { s -> s.jsonSchemaTypesDuplicateEntriesAndLimitsAreStrict() }
        runIsolated { s -> s.failedLoadPropagatesAndCanBeRetriedWithoutAnEmptyFallback() }
        runIsolated { s -> s.failedSaveLeavesMemoryDiskAndSnapshotStampUnchangedAndCanRetry() }
        runIsolated { s -> s.firstFailedSaveDoesNotCreateAGhostEntry() }
        runIsolated { s -> s.reentrantPersistenceCannotChangeModeOrBypassCommitOrdering() }
        runIsolated { s -> s.concurrentUpdatesAreSerializedWithoutLostWritesInEitherMode() }
    
        NativeFavoriteProtocolCases().verifyAllCases()
        NativeFavoriteTransactionsCases().verifyAllCases()
        NativeFollowGroupProtocolCases().verifyAllCases()
        NativeLibraryCompletionCases().verifyAllCases()
        PrivacyInstallPlanCases().verifyAllCases()
    }
}

private class NativeFavoriteProtocolCases {
    private val video = LocalVirtualLibrary.Video(170001, 279786, "title", 199000, 92000, 123000, "cover", 7, "author")
    private fun library() = LocalVirtualLibrary({ null }, {}).also { it.addFavorite(video) }
    private fun response(library: LocalVirtualLibrary, path: String, vararg params: Pair<String, String>) =
        JSONObject(NativeFavoriteProtocol.respond("/x/v3/fav/$path", params.toMap(), library))

    fun tabsCarryEncodedNativeFragmentArguments() {
        val tab = response(library(), "tab").getJSONObject("data").getJSONArray("tabs").getJSONObject(0)
        val uri = java.net.URI(tab.getString("uri"))
        val query = java.net.URLDecoder.decode(uri.rawQuery, "UTF-8")
        assertTrue(query.contains("sub_tab=["))
        assertTrue(query.contains("favorite/folders"))
        assertEquals(100, tab.getJSONArray("sub_tab").getJSONObject(0).getInt("tab_id"))
    }
    fun folderRowsAndDetailUseSameStableIdentityAndResumeTarget() {
        val data = library()
        val row = response(data, "tab/my_fav").getJSONObject("data").getJSONArray("list").getJSONObject(0)
        val id = row.getLong("fid").toString()
        val detail = response(data, "folder/resources", "media_id" to id).getJSONObject("data")
        assertEquals(1, row.getInt("media_count"))
        assertEquals(92, detail.getJSONArray("list").getJSONObject(0).getInt("playback_position"))
        assertTrue(detail.getJSONArray("list").getJSONObject(0).getString("link").contains("cid=279786"))
        assertEquals(0, response(data, "folder/resources", "media_id" to id, "pn" to "2").getJSONObject("data").getJSONArray("list").length())
    }
    fun batchDeleteIsArchiveScopedAndDoesNotChangeOtherFolder() {
        val data = library()
        data.createFavoriteFolder("other")
        data.addFavorite(video.copy(cid=9), "other")
        val id = NativeFavoriteFolders.id(data.readSnapshot().collections, LocalVirtualLibrary.DEFAULT_FOLDER)
        response(data, "tab/dels", "media_id" to "$id", "resources" to "170001:2")
        assertTrue(data.readSnapshot().collections.favorites.getValue(LocalVirtualLibrary.DEFAULT_FOLDER).isEmpty())
        assertEquals(1, data.readSnapshot().collections.favorites.getValue("other").size)
    }
    fun moveCommitsBothFoldersTogetherAndRollsBackOnWriteFailure() {
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
    fun hostFolderPickerTicksAndCommitsOnlyTheChangedFolders() {
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
    fun unfavoriteAllRemovesTheArchiveEverywhere() {
        val data = library()
        data.createFavoriteFolder("other")
        data.addFavorite(video, "other")
        assertEquals(0, response(data, "resource/unfav-all", "rid" to "170001", "type" to "2").getInt("code"))
        assertTrue(data.readSnapshot().collections.favorites.values.all { it.isEmpty() })
        assertEquals(1, response(data, "folder/created/list-all").getJSONObject("data").getJSONArray("list").getJSONObject(0).getInt("fav_state"))
    }
    fun unknownMutationIsNotReportedAsSaved() {
        val data = library(); val before = data.readSnapshot()
        assertEquals(-400, response(data, "folder/unknown-operation").getInt("code"))
        assertEquals(before.collections, data.readSnapshot().collections)
    }
    fun incognitoNativeReadsAndEditsNeverOpenPersistentStorage() {
        val data = LocalVirtualLibrary({ error("disk read") }, { error("disk write") })
        data.enterIncognito(); data.addFavorite(video)
        response(data, "tab/my_fav")
        response(data, "tab/dels", "resources" to "170001:2")
        assertTrue(data.readSnapshot().collections.favorites.values.all { it.isEmpty() })
        data.resetIncognito()
        assertEquals(0, response(data, "tab/fav").getJSONObject("data").getInt("total"))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (NativeFavoriteProtocolCases) -> Unit) {
            val s = NativeFavoriteProtocolCases()
            block(s)
        }
        runIsolated { s -> s.tabsCarryEncodedNativeFragmentArguments() }
        runIsolated { s -> s.folderRowsAndDetailUseSameStableIdentityAndResumeTarget() }
        runIsolated { s -> s.batchDeleteIsArchiveScopedAndDoesNotChangeOtherFolder() }
        runIsolated { s -> s.moveCommitsBothFoldersTogetherAndRollsBackOnWriteFailure() }
        runIsolated { s -> s.hostFolderPickerTicksAndCommitsOnlyTheChangedFolders() }
        runIsolated { s -> s.unfavoriteAllRemovesTheArchiveEverywhere() }
        runIsolated { s -> s.unknownMutationIsNotReportedAsSaved() }
        runIsolated { s -> s.incognitoNativeReadsAndEditsNeverOpenPersistentStorage() }
    }
}

private class NativeFavoriteTransactionsCases {
    private fun video(cid: Long = 10) = LocalVirtualLibrary.Video(1, cid, "video", 200000, 12345, 1)

    fun twoClicksAddThenRemoveInsteadOfReportingTwoSuccessfulAdds() {
        var saved: String? = null
        val store = LocalVirtualLibrary({ saved }, { saved = it })
        assertTrue(store.toggleFavorite(video()))
        assertEquals(1, store.readSnapshot().collections.favorites.getValue(LocalVirtualLibrary.DEFAULT_FOLDER).size)
        assertFalse(store.toggleFavorite(video(20)))
        assertTrue(store.readSnapshot().collections.favorites.values.all { it.isEmpty() })
        assertTrue(LocalVirtualLibrary({ saved }, {}).readSnapshot().collections.favorites.values.all { it.isEmpty() })
    }

    fun nativeFolderSelectionReplacesEveryFolderAtomicallyAtArchiveScope() {
        val store = LocalVirtualLibrary({ null }, {})
        store.createFavoriteFolder("second")
        store.addFavorite(video())
        store.addFavorite(video(20))
        store.setFavoriteFolders(video(30), setOf("second"))
        val state = store.readSnapshot().collections
        assertTrue(state.favorites.getValue(LocalVirtualLibrary.DEFAULT_FOLDER).isEmpty())
        assertEquals(listOf(video(30)), state.favorites.getValue("second"))
        store.setFavoriteFolders(video(), emptySet())
        assertTrue(store.readSnapshot().collections.favorites.values.all { it.isEmpty() })
    }

    fun failedCommitKeepsPriorSelectionAndRejectsStaleFolderNames() {
        var fail = false
        val store = LocalVirtualLibrary({ null }, { if (fail) error("disk failure") })
        store.toggleFavorite(video())
        val before = store.readSnapshot().collections
        fail = true
        assertThrows(IllegalStateException::class.java) { store.toggleFavorite(video()) }
        assertEquals(before, store.readSnapshot().collections)
        fail = false
        assertThrows(IllegalArgumentException::class.java) { store.setFavoriteFolders(video(), setOf("missing")) }
        assertEquals(before, store.readSnapshot().collections)
    }

    fun incognitoFolderSelectionAndCancellationNeverTouchPersistentStorage() {
        val store = LocalVirtualLibrary({ error("disk read") }, { error("disk write") })
        store.enterIncognito()
        store.createFavoriteFolder("temporary")
        store.setFavoriteFolders(video(), setOf("temporary", LocalVirtualLibrary.DEFAULT_FOLDER))
        assertFalse(store.toggleFavorite(video(20)))
        store.resetIncognito()
        assertEquals(LocalVirtualLibrary.Collections(), store.readSnapshot().collections)
    }
    fun folderMetadataRoundTripsAndKeepsIdentityAcrossRename() {
        var saved: String? = null
        val store = LocalVirtualLibrary({ saved }, { saved = it })
        store.addFavorite(video())
        val legacy = requireNotNull(saved)
        val restored = LocalVirtualLibrary({ legacy }, { saved = it })
        val id = NativeFavoriteFolders.id("first")
        restored.createFavoriteFolder("first", LocalVirtualLibrary.FolderInfo(id, "description"))
        restored.setFavoriteFolders(video(), setOf("first"))
        restored.editFavoriteFolder("first", "renamed", LocalVirtualLibrary.FolderInfo(id, "edited"))
        val state = LocalVirtualLibrary({ saved }, {}).readSnapshot().collections
        assertEquals(id, NativeFavoriteFolders.id(state, "renamed"))
        assertEquals("edited", state.folderInfo.getValue("renamed").intro)
        assertEquals(listOf(video()), state.favorites.getValue("renamed"))
        restored.deleteFavoriteFolder("renamed")
        assertTrue(LocalVirtualLibrary({ saved }, {}).readSnapshot().collections.folderInfo.isEmpty())
    }

    fun invalidOrFailedFolderEditsPreserveMetadataAndContents() {
        var fail = false
        val store = LocalVirtualLibrary({ null }, { if (fail) error("disk failure") })
        store.createFavoriteFolder("first", LocalVirtualLibrary.FolderInfo(42))
        store.setFavoriteFolders(video(), setOf("first"))
        val before = store.readSnapshot().collections
        assertThrows(IllegalArgumentException::class.java) {
            store.createFavoriteFolder("collision", LocalVirtualLibrary.FolderInfo(42))
        }
        assertEquals(before, store.readSnapshot().collections)
        fail = true
        assertThrows(IllegalStateException::class.java) {
            store.editFavoriteFolder("first", "renamed", LocalVirtualLibrary.FolderInfo(42, "changed"))
        }
        assertEquals(before, store.readSnapshot().collections)
    }

    fun incognitoFolderMetadataIsDiscardedWithoutLoadingOrSavingGuestLibrary() {
        val store = LocalVirtualLibrary({ error("disk read") }, { error("disk write") })
        store.enterIncognito()
        store.createFavoriteFolder("temporary", LocalVirtualLibrary.FolderInfo(42, "temporary"))
        store.resetIncognito()
        assertEquals(LocalVirtualLibrary.Collections(), store.readSnapshot().collections)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (NativeFavoriteTransactionsCases) -> Unit) {
            val s = NativeFavoriteTransactionsCases()
            block(s)
        }
        runIsolated { s -> s.twoClicksAddThenRemoveInsteadOfReportingTwoSuccessfulAdds() }
        runIsolated { s -> s.nativeFolderSelectionReplacesEveryFolderAtomicallyAtArchiveScope() }
        runIsolated { s -> s.failedCommitKeepsPriorSelectionAndRejectsStaleFolderNames() }
        runIsolated { s -> s.incognitoFolderSelectionAndCancellationNeverTouchPersistentStorage() }
        runIsolated { s -> s.folderMetadataRoundTripsAndKeepsIdentityAcrossRename() }
        runIsolated { s -> s.invalidOrFailedFolderEditsPreserveMetadataAndContents() }
        runIsolated { s -> s.incognitoFolderMetadataIsDiscardedWithoutLoadingOrSavingGuestLibrary() }
    }
}

private class NativeFollowGroupProtocolCases {
    private class Store {
        var text: String? = null
        var writes = 0
        var fail = false
        val library = LocalVirtualLibrary(load = { text }, save = {
            if (fail) error("write failed")
            text = it
            writes++
        })
        val labels = NativeFollowGroupProtocol.Labels("Default", "Special")
        fun reply(path: String, vararg params: Pair<String, String>, mid: Long = 11) =
            JSONObject(NativeFollowGroupProtocol.respond(path, mapOf(*params), library, mid, labels))
        init { library.follow(LocalVirtualLibrary.Follow(11, "first")); library.follow(LocalVirtualLibrary.Follow(12, "second")) }
    }

    fun nativeRowsAndSelectedIdsReflectTheLocalLibrary() {
        val store = Store()
        val first = store.library.createFollowGroup("one")
        val second = store.library.createFollowGroup("two")
        store.library.setFollowSelection(setOf(11), setOf(first.id, second.id), true)
        val data = store.reply("/x/relation/tag/m/tags").getJSONObject("data")
        assertEquals("-10", data.getJSONArray("special").getJSONObject(0).getString("tagid"))
        assertEquals("Special", data.getJSONArray("special").getJSONObject(0).getString("name"))
        assertEquals(1, data.getJSONArray("special").getJSONObject(0).getInt("count"))
        assertEquals("0", data.getJSONArray("default").getJSONObject(0).getString("tagid"))
        assertEquals(1, data.getJSONArray("default").getJSONObject(0).getInt("count"))
        assertEquals(2, data.getJSONArray("list").length())
        assertEquals(1, data.getJSONArray("list").getJSONObject(0).getInt("count"))
        val selected = store.reply("/x/relation/tag/user", "fid" to "11").getJSONObject("data")
        assertEquals("Special", selected.getString("-10"))
        assertEquals("one", selected.getString(first.id.toString()))
        assertEquals("two", selected.getString(second.id.toString()))
    }

    fun nativeSelectionCommitsGroupsAndSpecialInOneWriteAndRollsBackOnFailure() {
        val store = Store()
        val first = store.library.createFollowGroup("one")
        val second = store.library.createFollowGroup("two")
        val writes = store.writes
        store.reply("/x/relation/tags/addUsers", "fids" to "11", "tagids" to "${first.id},-10,${second.id}")
        assertEquals(writes + 1, store.writes)
        val before = store.library.readSnapshot()
        assertEquals(setOf(first.id, second.id), before.collections.followGroupAssignments[11])
        assertEquals(setOf(11L), before.collections.specialFollows)
        val disk = store.text
        store.fail = true
        assertThrows(IllegalStateException::class.java) {
            store.reply("/x/relation/tags/addUsers", "fids" to "11", "tagids" to "0")
        }
        assertEquals(before, store.library.readSnapshot())
        assertEquals(disk, store.text)
        store.fail = false
        store.reply("/x/relation/tags/addUsers", "fids" to "11", "tagids" to "0")
        assertTrue(store.library.readSnapshot().collections.followGroupAssignments.isEmpty())
        assertTrue(store.library.readSnapshot().collections.specialFollows.isEmpty())
    }

    fun createRenameDeleteAndSpecialUseNativeParameters() {
        val store = Store()
        val created = store.reply("/x/relation/tag/create", "tag" to " one ").getJSONObject("data")
        val id = created.getString("tagid")
        assertEquals("one", created.getString("name"))
        store.reply("/x/relation/tag/update", "tagid" to id, "name" to "renamed")
        store.reply("/x/relation/tags/addUsers", "fids" to "11", "tagids" to id)
        store.reply("/x/relation/tag/special/add", "fid" to "11")
        assertEquals("renamed", store.reply("/x/relation/tag/user", "fid" to "11").getJSONObject("data").getString(id))
        store.reply("/x/relation/tag/special/del", "fid" to "11")
        store.reply("/x/relation/tag/del", "tagid" to id)
        val state = store.library.readSnapshot().collections
        assertTrue(state.followGroups.isEmpty())
        assertTrue(state.followGroupAssignments.isEmpty())
        assertTrue(state.specialFollows.isEmpty())
    }

    fun staleUnknownOrMalformedRequestsNeverWrite() {
        val store = Store()
        val group = store.library.createFollowGroup("one")
        val before = store.library.readSnapshot()
        val writes = store.writes
        val malformed = listOf("", "0,-10", "0,${group.id}", "-10,-10", "${group.id},${group.id}", "-1", "999", "01", "${group.id},")
        malformed.forEach { tags ->
            assertThrows(IllegalArgumentException::class.java) {
                store.reply("/x/relation/tags/addUsers", "fids" to "11", "tagids" to tags)
            }
        }
        assertThrows(IllegalArgumentException::class.java) { store.reply("/x/relation/tag/user", "fid" to "12") }
        assertThrows(IllegalArgumentException::class.java) { store.reply("/x/relation/tag/special/add", "fid" to "12") }
        assertThrows(IllegalArgumentException::class.java) { store.reply("/x/relation/tags/addUsers", "fids" to "11,12", "tagids" to "0") }
        assertThrows(IllegalArgumentException::class.java) { store.reply("/x/relation/tag/del", "tagid" to "999") }
        assertThrows(IllegalArgumentException::class.java) { store.reply("/x/relation/tag/other") }
        assertEquals(before, store.library.readSnapshot())
        assertEquals(writes, store.writes)
        store.library.unfollow(11)
        assertThrows(IllegalArgumentException::class.java) { store.reply("/x/relation/tag/create", "tag" to "stale") }
    }

    fun incognitoRequestsStayInMemory() {
        val store = Store()
        val disk = store.text
        store.library.enterIncognito()
        store.library.follow(LocalVirtualLibrary.Follow(11, "private"))
        val id = store.reply("/x/relation/tag/create", "tag" to "private").getJSONObject("data").getString("tagid")
        store.reply("/x/relation/tags/addUsers", "fids" to "11", "tagids" to "$id,-10")
        assertEquals(disk, store.text)
        store.library.exitIncognito()
        val state = store.library.readSnapshot().collections
        assertTrue(state.followGroups.isEmpty())
        assertTrue(state.specialFollows.isEmpty())
    }

    fun verifyAllCases() {
        fun runIsolated(block: (NativeFollowGroupProtocolCases) -> Unit) {
            val s = NativeFollowGroupProtocolCases()
            block(s)
        }
        runIsolated { s -> s.nativeRowsAndSelectedIdsReflectTheLocalLibrary() }
        runIsolated { s -> s.nativeSelectionCommitsGroupsAndSpecialInOneWriteAndRollsBackOnFailure() }
        runIsolated { s -> s.createRenameDeleteAndSpecialUseNativeParameters() }
        runIsolated { s -> s.staleUnknownOrMalformedRequestsNeverWrite() }
        runIsolated { s -> s.incognitoRequestsStayInMemory() }
    }
}

private class NativeLibraryCompletionCases {
    private val first = LocalVirtualLibrary.Video(1, 11, "first", 100000, 50000, 10, "cover", 7, "author")
    private val second = first.copy(aid = 2, cid = 22, title = "second")
    private fun fav(store: LocalVirtualLibrary, path: String, vararg params: Pair<String, String>) =
        JSONObject(NativeFavoriteProtocol.respond("/x/v3/fav/$path", params.toMap(), store))
    private fun later(store: LocalVirtualLibrary, path: String, vararg params: Pair<String, String>) =
        JSONObject(NativeWatchLaterProtocol.respond("/x/v2/history/toview/$path", params.toMap(), store))

    fun legacyLibraryUpgradesWithoutDroppingProgressAndPersistsAvatarsPinsAndOrder() {
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

    fun nativePinAndUnpinReturnCorrectMenuStateAndKeepFolderContents() {
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

    fun sequentialNativeDragMovesPersistAndInvalidBatchDoesNotPartiallyApply() {
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

    fun globalSortWorksAcrossFoldersAndSurvivesReopening() {
        var disk: String? = null
        val store = LocalVirtualLibrary({ disk }, { disk=it })
        store.addFavorite(first); store.createFavoriteFolder("other"); store.addFavorite(second, "other")
        fav(store, "resource/sort", "sort" to "0:0:2:2")
        val restored = LocalVirtualLibrary({ disk }, {})
        val rows = fav(restored, "tab/fav").getJSONObject("data").getJSONArray("list")
        assertEquals(2L, rows.getJSONObject(0).getLong("oid"))
        assertEquals(1L, rows.getJSONObject(1).getLong("oid"))
    }

    fun watchLaterNativeCardsKeepProgressAndDeletionDoesNotTouchHistory() {
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

    fun legacyNativeEditorCreatesAndRenamesPrivateFolderWithoutLosingPinOrVideos() {
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

    fun watchLaterCopyAndMovePreserveProgressAndFailAtomically() {
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

    fun unseenHomeCardCanBeAddedWithoutCreatingPlaybackHistoryAndReopened() {
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

    fun singleAddAnswersWithTheHostsOwnAddedPopupFields() {
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

    fun resourceIdsReadBatchAndSingleFormsAndRejectNonPositiveIds() {
        assertEquals(setOf(1L, 2L), NativeWatchLaterProtocol.resourceIds(mapOf("resources" to "1:2,2:2", "aid" to "9")))
        assertEquals(setOf(7L), NativeWatchLaterProtocol.resourceIds(mapOf("aid" to "7")))
        assertTrue(NativeWatchLaterProtocol.resourceIds(emptyMap()).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { NativeWatchLaterProtocol.resourceIds(mapOf("aid" to "0")) }
        assertTrue(NativeWatchLaterProtocol.isAdd("/x/v2/history/toview/add"))
        assertTrue(NativeWatchLaterProtocol.isAdd("/x/v3/fav/toview/adds"))
        assertFalse(NativeWatchLaterProtocol.isAdd("/x/v2/history/toview/v2/dels"))
    }

    fun addingKnownVideoKeepsResumeProgressAndMissingMetadataFailsAtomically() {
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

    fun incognitoWatchLaterAddDoesNotReadOrModifyThePersistentGuestLibrary() {
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

    fun incognitoPinSortAndWatchLaterLeaveNoPersistentChanges() {
        val store = LocalVirtualLibrary({ error("disk read") }, { error("disk write") })
        store.enterIncognito(); store.addFavorite(first); store.addFavorite(second); store.addWatchLater(first)
        val id = NativeFavoriteFolders.id(store.readSnapshot().collections, LocalVirtualLibrary.DEFAULT_FOLDER)
        fav(store, "folder/add_top", "fid" to "$id", "add_top" to "true")
        fav(store, "resource/sort", "sort" to "0:0:1:2")
        later(store, "v2/list"); later(store, "v2/dels", "resources" to "1:2")
        store.resetIncognito()
        assertEquals(LocalVirtualLibrary.Collections(), store.readSnapshot().collections)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (NativeLibraryCompletionCases) -> Unit) {
            val s = NativeLibraryCompletionCases()
            block(s)
        }
        runIsolated { s -> s.legacyLibraryUpgradesWithoutDroppingProgressAndPersistsAvatarsPinsAndOrder() }
        runIsolated { s -> s.nativePinAndUnpinReturnCorrectMenuStateAndKeepFolderContents() }
        runIsolated { s -> s.sequentialNativeDragMovesPersistAndInvalidBatchDoesNotPartiallyApply() }
        runIsolated { s -> s.globalSortWorksAcrossFoldersAndSurvivesReopening() }
        runIsolated { s -> s.watchLaterNativeCardsKeepProgressAndDeletionDoesNotTouchHistory() }
        runIsolated { s -> s.legacyNativeEditorCreatesAndRenamesPrivateFolderWithoutLosingPinOrVideos() }
        runIsolated { s -> s.watchLaterCopyAndMovePreserveProgressAndFailAtomically() }
        runIsolated { s -> s.unseenHomeCardCanBeAddedWithoutCreatingPlaybackHistoryAndReopened() }
        runIsolated { s -> s.singleAddAnswersWithTheHostsOwnAddedPopupFields() }
        runIsolated { s -> s.resourceIdsReadBatchAndSingleFormsAndRejectNonPositiveIds() }
        runIsolated { s -> s.addingKnownVideoKeepsResumeProgressAndMissingMetadataFailsAtomically() }
        runIsolated { s -> s.incognitoWatchLaterAddDoesNotReadOrModifyThePersistentGuestLibrary() }
        runIsolated { s -> s.incognitoPinSortAndWatchLaterLeaveNoPersistentChanges() }
    }
}

private class PrivacyInstallPlanCases {
    fun stepsRunInOrderOnlyWhenApplied() {
        val log = mutableListOf<Int>()
        val plan = PrivacyInstallPlan()
        plan.step { log += 1 }
        plan.step { log += 2 }
        plan.step { log += 3 }
        assertEquals(3, plan.size)
        // Nothing runs until apply(): this is the all-or-nothing guarantee — a caller can abandon
        // the plan after a failed preflight and no hook was registered.
        assertTrue(log.isEmpty())
        assertEquals(3, plan.apply())
        assertEquals(listOf(1, 2, 3), log)
    }

    fun emptyPlanAppliesNothing() {
        val plan = PrivacyInstallPlan()
        assertEquals(0, plan.size)
        assertEquals(0, plan.apply())
    }

    fun verifyAllCases() {
        fun runIsolated(block: (PrivacyInstallPlanCases) -> Unit) {
            val s = PrivacyInstallPlanCases()
            block(s)
        }
        runIsolated { s -> s.stepsRunInOrderOnlyWhenApplied() }
        runIsolated { s -> s.emptyPlanAppliesNothing() }
    }
}
