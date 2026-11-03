package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy.LocalVirtualLibrary.CorruptLibraryException
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy.LocalVirtualLibrary.Follow
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy.LocalVirtualLibrary.FollowGroup
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LocalFollowGroupsTest {
    private class Store(var text: String? = null) {
        var writes = 0
        var failWrites = false
        fun library(maxEntries: Int = LocalVirtualLibrary.MAX_ENTRIES) = LocalVirtualLibrary(
            load = { text },
            save = {
                if (failWrites) error("write failed")
                text = it
                writes++
            },
            maxEntries = maxEntries
        )
    }

    @Test fun groupsAndSpecialFollowsRoundTripAndSurviveMetadataRefresh() {
        val store = Store()
        val library = store.library(maxEntries = 2)
        library.follow(Follow(11, "first"))
        library.follow(Follow(12, "second"))
        val first = library.createFollowGroup("学习")
        val second = library.createFollowGroup("朋友")
        val third = library.createFollowGroup("视频")
        library.setFollowGroups(setOf(11, 12), setOf(first.id, second.id, third.id))
        library.setSpecialFollow(11, true)
        library.follow(Follow(11, "renamed", "face"))
        val expected = library.readSnapshot().collections
        assertEquals(setOf(first.id, second.id, third.id), expected.followGroupAssignments[11])
        assertEquals(setOf(11L), expected.specialFollows)
        assertEquals(4, JSONObject(store.text!!).getInt("version"))
        assertEquals(expected, store.library(maxEntries = 2).readSnapshot().collections)
        assertThrows(UnsupportedOperationException::class.java) { (expected.followGroups as MutableList<FollowGroup>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (expected.specialFollows as MutableSet<Long>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (expected.followGroupAssignments.getValue(11) as MutableSet<Long>).clear() }
    }

    @Test fun deletingGroupsAndUnfollowingRemoveOnlyTheirAssociationsWithoutReusingIds() {
        val store = Store()
        val library = store.library()
        library.follow(Follow(11, "first"))
        library.follow(Follow(12, "second"))
        val first = library.createFollowGroup("one")
        val second = library.createFollowGroup("two")
        library.setFollowGroups(setOf(11), setOf(first.id, second.id))
        library.setFollowGroups(setOf(12), setOf(first.id))
        library.setSpecialFollow(11, true)
        library.setSpecialFollow(12, true)
        library.deleteFollowGroup(first.id)
        var state = library.readSnapshot().collections
        assertEquals(mapOf(11L to setOf(second.id)), state.followGroupAssignments)
        assertEquals(setOf(11L, 12L), state.specialFollows)
        assertEquals(3L, library.createFollowGroup("three").id)
        library.unfollow(11)
        state = library.readSnapshot().collections
        assertTrue(state.followGroupAssignments.isEmpty())
        assertEquals(setOf(12L), state.specialFollows)
        library.deleteFollowGroup(second.id)
        library.deleteFollowGroup(3)
        val reopened = store.library()
        assertEquals(4, JSONObject(store.text!!).getInt("version"))
        assertEquals(4L, reopened.createFollowGroup("after restart").id)
    }

    @Test fun privateGroupsNeverReadOrWriteTheOrdinaryLibrary() {
        val store = Store()
        val library = store.library()
        library.follow(Follow(1, "ordinary"))
        val ordinary = library.createFollowGroup("ordinary group")
        library.setFollowGroups(setOf(1), setOf(ordinary.id))
        library.setSpecialFollow(1, true)
        val original = library.readSnapshot().collections
        val disk = store.text
        val writes = store.writes
        library.enterIncognito()
        assertTrue(library.readSnapshot().collections.followGroups.isEmpty())
        library.follow(Follow(2, "private"))
        val temporary = library.createFollowGroup("private group")
        library.setFollowGroups(setOf(2), setOf(temporary.id))
        library.setSpecialFollow(2, true)
        assertEquals(disk, store.text)
        assertEquals(writes, store.writes)
        library.exitIncognito()
        assertEquals(original, library.readSnapshot().collections)
        library.enterIncognito()
        assertTrue(library.readSnapshot().collections.followGroups.isEmpty())
        assertTrue(library.readSnapshot().collections.specialFollows.isEmpty())
    }

    @Test fun failedPersistenceLeavesGroupsAssignmentsAndNextIdUnchanged() {
        val store = Store()
        val library = store.library()
        library.follow(Follow(1, "one"))
        val group = library.createFollowGroup("first")
        val before = library.readSnapshot()
        val disk = store.text
        store.failWrites = true
        assertThrows(IllegalStateException::class.java) { library.setFollowGroups(setOf(1), setOf(group.id)) }
        assertThrows(IllegalStateException::class.java) { library.setSpecialFollow(1, true) }
        assertThrows(IllegalStateException::class.java) { library.createFollowGroup("second") }
        assertEquals(before, library.readSnapshot())
        assertEquals(disk, store.text)
        store.failWrites = false
        assertEquals(2L, library.createFollowGroup("second").id)
    }

    @Test fun invalidAssignmentsAndSnapshotsNeverPartiallyChangeTheLibrary() {
        val store = Store()
        val library = store.library()
        library.follow(Follow(1, "one"))
        val group = library.createFollowGroup("first")
        val before = library.readSnapshot()
        val writes = store.writes
        assertThrows(IllegalArgumentException::class.java) { library.setFollowGroups(setOf(1, 2), setOf(group.id)) }
        assertThrows(IllegalArgumentException::class.java) { library.setFollowGroups(setOf(1), setOf(999)) }
        assertThrows(IllegalArgumentException::class.java) { library.setSpecialFollow(2, true) }
        assertThrows(IllegalArgumentException::class.java) { library.createFollowGroup("first") }
        val collections = before.collections
        val invalid = listOf(
            collections.copy(followGroups = listOf(group, group)),
            collections.copy(followGroups = listOf(group, FollowGroup(2, group.name)), nextFollowGroupId = 3),
            collections.copy(nextFollowGroupId = group.id),
            collections.copy(followGroupAssignments = mapOf(1L to emptySet())),
            collections.copy(followGroupAssignments = mapOf(1L to setOf(999L))),
            collections.copy(followGroupAssignments = mapOf(2L to setOf(group.id))),
            collections.copy(specialFollows = setOf(2L))
        )
        invalid.forEach { candidate ->
            assertThrows(IllegalArgumentException::class.java) { library.writeSnapshot(before.copy(collections = candidate)) }
            assertEquals(before, library.readSnapshot())
        }
        assertEquals(writes, store.writes)
    }

    @Test fun malformedVersionFourCannotSilentlyOverwriteStoredData() {
        val original = Store()
        val library = original.library()
        library.follow(Follow(1, "one"))
        val group = library.createFollowGroup("first")
        library.setFollowGroups(setOf(1), setOf(group.id))
        library.setSpecialFollow(1, true)
        val valid = original.text!!
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.getJSONArray("followGroups").put(it.getJSONArray("followGroups").getJSONObject(0)) },
            { it.getJSONArray("followGroupAssignments").getJSONObject(0).getJSONArray("groupIds").put(group.id) },
            { it.getJSONArray("followGroupAssignments").getJSONObject(0).put("groupIds", JSONArray().put(999)) },
            { it.getJSONArray("specialFollows").put(1) },
            { it.put("nextFollowGroupId", group.id) },
            { it.remove("followGroups") }
        )
        mutations.forEach { mutate ->
            val broken = JSONObject(valid).also(mutate).toString()
            val store = Store(broken)
            val reopened = store.library()
            assertThrows(CorruptLibraryException::class.java) { reopened.readSnapshot() }
            assertThrows(CorruptLibraryException::class.java) { reopened.createFollowGroup("another") }
            assertEquals(broken, store.text)
            assertEquals(0, store.writes)
        }
    }

    @Test fun olderSchemasUpgradeWithoutLosingExistingData() {
        for (oldVersion in 1..3) {
            val store = Store()
            val library = store.library()
            library.follow(Follow(1, "one", if (oldVersion == 3) "face" else ""))
            if (oldVersion == 2) library.createFavoriteFolder("folder", LocalVirtualLibrary.FolderInfo(99))
            assertEquals(oldVersion, JSONObject(store.text!!).getInt("version"))
            val original = store.library().readSnapshot().collections
            val reopened = store.library()
            reopened.createFollowGroup("new")
            val updated = store.library().readSnapshot().collections
            assertEquals(original.follows, updated.follows)
            assertEquals(original.favorites, updated.favorites)
            assertEquals(original.folderInfo, updated.folderInfo)
            assertEquals(4, JSONObject(store.text!!).getInt("version"))
        }
    }
}
