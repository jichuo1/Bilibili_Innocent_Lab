package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.*
import org.junit.Test

/** Source contracts protect integration boundaries that are not exercised by JVM View stubs. */
class SettingsHomeIntegrationTest {
    @Test fun replyButtonVisibilitySwitchHasAHighlightDestinationOnThePurificationPage() {
        val rows = SourceContract.read("ui/activity/CommentReplyButtonPresenter.kt")
        val binding = rows.after("bindFavoriteSwitch(this, FeaturePreferences.REMOVE_COMMENT_REPLY_BUTTON, directToggle = true)")
            .before("text = stringResource(R.string.remove_comment_reply_button)")
        assertTrue(binding.contains("bindSettingDestination(this, FeaturePreferences.REMOVE_COMMENT_REPLY_BUTTON)"))
        assertTrue(SettingsUiSource.function("purifyCommentsCategory").contains("commentReplyButtonSettingsRows(this, removeCommentReplyButton)"))
    }

    @Test fun searchTraversesOriginalPagesAndSwitchesBeforeSchedulingCoordinates() {
        val collect = SettingsUiSource.function("collectSettingsSearchTargets")
        assertTrue(collect.contains("settingsHome?.searchRoots"))
        val reveal = SettingsUiSource.function("revealSettingsSearchTarget")
        assertTrue(reveal.indexOf("cancelSettingsReveal()") < reveal.indexOf("revealPageFor"))
        assertTrue(reveal.indexOf("revealPageFor") < reveal.indexOf("offsetDescendantRectToMyCoords"))
        assertTrue(reveal.contains("settingsRevealRequest.owns(token)"))
        assertTrue(reveal.contains("scrollView.isLayoutRequested"))
        assertTrue(reveal.contains("target.view.isSameOrDescendantOf(scrollView)"))
        assertTrue(reveal.contains("scrollView.scrollY == destinationY"))
        assertFalse(reveal.contains("postDelayed"))
        assertFalse(reveal.contains("revealPageFor(target.view) ?: settingsSearchScrollView"))
        val home = SettingsUiSource.file("SettingsHomePresenter")
        assertTrue(home.contains("searchRoots: List<ViewGroup> get() = contents.drop(1)"))
        assertTrue(home.contains("pager.selectPage(index, false)"))
        assertFalse(home.contains("settingsDestinations.bind"))
    }

    @Test fun favoritesReuseTheOriginalListenerAndConfirmationSwitchRemainsNavigationOnly() {
        val home = SettingsUiSource.file("SettingsHomePresenter")
        val callback = SettingsUiSource.functions(home, "renderFavorites").single()
            .after("peer.setOnCheckedChangeListener").before("card.addView(peer")
        // Preserve the enabled guard without fixing whitespace, line breaks or optional braces.
        val guardedWrite = Regex("""if\s*\(\s*source\.isEnabled\s*\)\s*(?:\{\s*)?source\.isChecked\s*=\s*checked\b""")
        val write = guardedWrite.find(SettingsUiSource.code(callback))
        assertNotNull("Favorites must route enabled writes through the original switch", write)
        assertTrue(callback.indexOf("sync()") > requireNotNull(write).range.last)
        assertTrue(home.contains("peer.alpha = source.alpha"))
        assertTrue(home.contains("peer.isEnabled = source.isEnabled"))
        assertTrue(home.contains("source.observeState(::sync)"))
        assertFalse(home.contains("putBoolean("))
        assertFalse(home.contains("prefs()"))
        val main = SettingsUiSource.settingsPage()
        assertTrue(main.contains("bindFavoriteSwitch(this, HookEntry.PREF_FREE_COPY_LIGHT_MODE, directToggle = false)"))
        assertTrue(main.contains("bindFavoriteSwitch(this, HookEntry.PREF_FREE_COPY_AUTO_LIGHT, directToggle = true)"))
        assertTrue(main.contains("SettingsCatalog.byStorageKey[storageKey]?.id"))
        val bindings = Regex("bindFavoriteSwitch\\(this, ([\\w.]+), directToggle = (true|false)\\)")
            .findAll(main).map { it.groupValues[1] }.toList()
        assertTrue("Expected explicit catalog bindings for the existing simple switches", bindings.size >= 70)
        assertEquals("Each control must use its own stable storage key", bindings.size, bindings.distinct().size)
        // Match each bound control's initial value to the key used to read that field.
        // This catches side-effect keys in confirmation listeners, such as manual light disabling auto light.
        val initialKeys = Regex("""(\w+)\s*=\s*uiSettings\.bool\(\s*([\w.]+)\s*\)""")
            .findAll(main).associate { it.groupValues[1] to it.groupValues[2] }
        val controls = Regex("""bindFavoriteSwitch\(this, ([\w.]+), directToggle = (true|false)\)(.*?)setOnCheckedChangeListener""",
            RegexOption.DOT_MATCHES_ALL).findAll(main).toList()
        assertEquals(bindings.size, controls.size)
        controls.forEach { control ->
            val field = Regex("""isChecked\s*=\s*(\w+)""").find(control.groupValues[3])!!.groupValues[1]
            assertEquals("$field uses the wrong preference identity", initialKeys[field], control.groupValues[1])
        }
        val playback = SettingsUiSource.function("enhancePlaybackCategory")
        assertTrue(playback.contains("bindFavoriteSwitch(this, key)"))
    }

    @Test fun arrangementRoundTripKeepsUnavailableRowsDuringReordering() {
        val state = SettingsFavoritesState(listOf("future", "A", "removed", "B", "C"))
        val first = SettingsFavoritesPolicy.move(state, "C", state.ids.indexOf("C") - 1)!!
        val second = SettingsFavoritesPolicy.move(first, "C", first.ids.indexOf("C") - 1)!!
        assertEquals(listOf("future", "A", "C", "removed", "B"), second.ids)
        val encoded = SettingsFavoritesPolicy.encode(second)!!
        assertEquals(second, SettingsFavoritesPolicy.decode(mapOf("schema" to 1, "ids" to encoded)))
        val management = SettingsUiSource.function("showSettingsFavoritesDialog")
        // 拖拽落点的 Move 索引必须取自存储序模型（displayIds），不可用行同样计入序号。
        assertTrue(management.contains("displayIds?.indexOf(it)"))
        assertTrue(management.contains("SettingsFavoritesRepository.Edit.Move(id, index)"))
        assertTrue(management.contains("settings_favorites_missing"))
    }

    @Test fun retainedPagesAndAsyncEditsHaveLifecycleCleanupAndRestoration() {
        val home = SettingsUiSource.file("SettingsHomePresenter")
        assertTrue(home.contains("settings_home_page"))
        assertTrue(home.contains("settings_home_scroll_"))
        assertTrue(home.contains("scrolls[0].doOnNextLayout"))
        assertTrue(home.contains("if (!disposed && userNavigationGeneration == 0L)"))
        assertTrue(home.contains("if (disposed || activity.isFinishing || activity.isDestroyed) return"))
        assertTrue(home.contains("stretches.forEach(finishStretch)"))
        assertTrue(home.contains("peerDisposers.forEach { it() }"))
        assertTrue(home.contains("pager.selectedPage == index"))
        assertTrue(home.contains("manageButton?.isEnabled = snapshot != null && !editing"))
        assertTrue(SettingsUiSource.function("showSettingsFavoritesDialog").contains("if (home.editing) return"))
        assertTrue(SettingsUiSource.function("onDestroy").contains("settingsHome?.dispose()"))
        assertTrue(SettingsUiSource.function("onSaveInstanceState").contains("settingsHome?.saveState(outState)"))
    }
}
