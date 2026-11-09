package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import org.junit.Assert.*
import org.junit.Test

class NativeFavoriteTransactionsTest {
    private fun video(cid: Long = 10) = LocalVirtualLibrary.Video(1, cid, "video", 200000, 12345, 1)

    @Test fun twoClicksAddThenRemoveInsteadOfReportingTwoSuccessfulAdds() {
        var saved: String? = null
        val store = LocalVirtualLibrary({ saved }, { saved = it })
        assertTrue(store.toggleFavorite(video()))
        assertEquals(1, store.readSnapshot().collections.favorites.getValue(LocalVirtualLibrary.DEFAULT_FOLDER).size)
        assertFalse(store.toggleFavorite(video(20)))
        assertTrue(store.readSnapshot().collections.favorites.values.all { it.isEmpty() })
        assertTrue(LocalVirtualLibrary({ saved }, {}).readSnapshot().collections.favorites.values.all { it.isEmpty() })
    }

    @Test fun nativeFolderSelectionReplacesEveryFolderAtomicallyAtArchiveScope() {
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

    @Test fun failedCommitKeepsPriorSelectionAndRejectsStaleFolderNames() {
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

    @Test fun incognitoFolderSelectionAndCancellationNeverTouchPersistentStorage() {
        val store = LocalVirtualLibrary({ error("disk read") }, { error("disk write") })
        store.enterIncognito()
        store.createFavoriteFolder("temporary")
        store.setFavoriteFolders(video(), setOf("temporary", LocalVirtualLibrary.DEFAULT_FOLDER))
        assertFalse(store.toggleFavorite(video(20)))
        store.resetIncognito()
        assertEquals(LocalVirtualLibrary.Collections(), store.readSnapshot().collections)
    }
    @Test fun folderMetadataRoundTripsAndKeepsIdentityAcrossRename() {
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

    @Test fun invalidOrFailedFolderEditsPreserveMetadataAndContents() {
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

    @Test fun incognitoFolderMetadataIsDiscardedWithoutLoadingOrSavingGuestLibrary() {
        val store = LocalVirtualLibrary({ error("disk read") }, { error("disk write") })
        store.enterIncognito()
        store.createFavoriteFolder("temporary", LocalVirtualLibrary.FolderInfo(42, "temporary"))
        store.resetIncognito()
        assertEquals(LocalVirtualLibrary.Collections(), store.readSnapshot().collections)
    }
}
