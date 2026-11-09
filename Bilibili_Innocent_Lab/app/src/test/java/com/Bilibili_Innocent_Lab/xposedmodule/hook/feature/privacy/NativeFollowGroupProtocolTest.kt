package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeFollowGroupProtocolTest {
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

    @Test fun nativeRowsAndSelectedIdsReflectTheLocalLibrary() {
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

    @Test fun nativeSelectionCommitsGroupsAndSpecialInOneWriteAndRollsBackOnFailure() {
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

    @Test fun createRenameDeleteAndSpecialUseNativeParameters() {
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

    @Test fun staleUnknownOrMalformedRequestsNeverWrite() {
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

    @Test fun incognitoRequestsStayInMemory() {
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
}
