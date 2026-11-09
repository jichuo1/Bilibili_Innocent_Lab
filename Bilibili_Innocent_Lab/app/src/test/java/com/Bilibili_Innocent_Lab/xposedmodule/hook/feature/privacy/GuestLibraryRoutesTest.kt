package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import org.junit.Assert.*
import org.junit.Test

class GuestLibraryRoutesTest {
    @Test fun nativeMineMenuAndFinalProviderRoutesReachTheSameLocalSections() {
        assertEquals("history", GuestLibraryHostHooks.sectionForUri("bilibili://main/drawer/history"))
        listOf("bilibili://main/drawer/favorites", "bilibili://main/favorite",
            "bilibili://user_center/favourite", "bilibili://user_center/favourite2",
            "bilibili://user_center/favorite_v2/favorite", "bilibili://user_center/favorite_v2/favorite/folders").forEach {
            assertEquals("favorites", GuestLibraryHostHooks.sectionForUri(it))
        }
        listOf("bilibili://main/drawer/watch-later", "bilibili://user_center/watch_later_v2",
            "bilibili://compose/watch_later/kntr").forEach {
            assertEquals("watchLater", GuestLibraryHostHooks.sectionForUri(it))
        }
    }

    @Test fun optionalQueryAndTrailingSlashDoNotChangeTheMenuIdentity() {
        assertEquals("favorites", GuestLibraryHostHooks.sectionForUri(
            "bilibili://main/drawer/favorites/?prompt_scene=mine"))
    }

    @Test fun unrelatedRoutesAndActiveLoginAreNotRedirectedToTheLocalLibrary() {
        listOf(null, "", "bilibili://main/login", "activity://main/login/",
            "https://example.test/main/drawer/favorites", "bilibili://main/drawer/offline",
            "bilibili://main/favorite/cheese").forEach { assertNull(GuestLibraryHostHooks.sectionForUri(it)) }
    }
}
