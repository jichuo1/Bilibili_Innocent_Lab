package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingValue
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 设置页主页绑定、收藏同步、目录注册、悬浮工具栏、延迟搜索收集及跳转揭示契约。 */
class SettingsHomeAndNavigationContractTest {

    @Test
    fun homePagesFavoritesAndCatalogPreferenceKeysStayAlignedAndRegistered() {
        // 设置页所有文件读取的偏好键都必须在 SettingsCatalog 登记，且访问器类型与目录一致。
        val constants = constantValues()
        assertTrue("constant table looks empty: ${constants.size}", constants.size > 100)
        val mainFile = SourceContract.read("ui/activity/MainActivity.kt")
        val uiCallPattern = Regex("""\buiSettings(?:\(\))?\.(bool|string|int)\(\s*([A-Za-z_][\w.]*)\s*\)""")
        val used = SettingsUiSource.settingsUiFiles().flatMap { (_, text) ->
            uiCallPattern.findAll(SettingsUiSource.code(text))
                .map { it.groupValues[1] to it.groupValues[2] }
                .toList()
        }
        assertTrue("uiSettings.* call sites: ${used.size}", used.size >= 100)
        val unresolved = mutableListOf<String>()
        val unregistered = mutableListOf<String>()
        val typeMismatch = mutableListOf<String>()
        val expanded = used.flatMap { (accessor, symbol) ->
            if (symbol != "it.storageKey") return@flatMap listOf(accessor to symbol)
            assertEquals("UI model initialization must read Boolean descriptors", "bool", accessor)
            assertTrue(
                "Navigation descriptors must not be read as Boolean preferences",
                Regex("""descriptors\.filter\s*\{\s*it\.favoriteEligible\s*}\s*\.associate\s*\{\s*it\.storageKey to uiSettings\.bool\(it\.storageKey\)""")
                    .containsMatchIn(mainFile)
            )
            val catalog = SourceContract.read("ui/activity/SettingsUiCatalog.kt")
            val symbols = Regex("""\badd\(\s*((?:FeaturePreferences|HookEntry)\.\w+)\s*,""")
                .findAll(catalog).map { it.groupValues[1] }.toList()
            assertTrue("The explicit UI metadata must cover existing switches", symbols.size >= 100)
            symbols.map { "bool" to it }
        }
        expanded.forEach { (accessor, symbol) ->
            val key = constants[symbol] ?: run {
                if (symbol.contains('.')) unresolved += symbol
                return@forEach
            }
            val spec = SettingsCatalog.byStorageKey[key]
            if (spec == null) {
                unregistered += "$symbol ($key)"
                return@forEach
            }
            val expected = when (spec.defaultValue) {
                is SettingValue.Bool -> "bool"
                is SettingValue.Text -> "string"
                is SettingValue.IntValue -> "int"
            }
            if (expected != accessor) typeMismatch += "$symbol: read as $accessor, catalog is $expected"
        }
        assertEquals("unresolved constants", emptyList<String>(), unresolved)
        assertEquals("keys missing from SettingsCatalog", emptyList<String>(), unregistered)
        assertEquals("accessor/catalog type mismatch", emptyList<String>(), typeMismatch)

        // 常用收藏开关绑定读取已确认模型并回写业务命令适配器。
        val home = SettingsUiSource.file("SettingsHomePresenter")
        val callback = SettingsUiSource.functions(home, "renderFavorites").single()
            .after("peer.setOnCheckedChangeListener").before("card.addView(peer")
        val guardedWrite = Regex("""if\s*\(\s*store\.state\(id\)\.enabled\s*\)\s*activity\.requestFavoriteSetting\(id, checked, peer\)""")
        val write = guardedWrite.find(SettingsUiSource.code(callback))
        assertNotNull("Favorites must route enabled writes through the business command adapter", write)
        assertTrue(callback.indexOf("sync()") > requireNotNull(write).range.last)
        assertTrue(home.contains("peer.alpha = if (finalState.enabled) 1f else .45f"))
        assertTrue(home.contains("peer.isEnabled = finalState.enabled"))
        assertTrue(home.contains("peer.isChecked = finalState.checked"))
        assertTrue(home.contains("store.observe(id) { sync() }"))
        assertFalse(home.contains("val source: MaterialSwitch"))

        val adapter = SettingsUiSource.file("SettingsModelBindingsPresenter")
        assertTrue(adapter.contains("store.request(id, requested)"))
        assertTrue(adapter.contains("executeModeledToggle(descriptor.storageKey, requested)"))
        assertFalse(adapter.contains("source.isChecked"))
        assertTrue(adapter.contains("if (requested) showFrameInterpolationThermalConfirmDialog(anchor) { save(true) }"))
        assertTrue(adapter.contains("store.checked(id, freeCopyLightMode)"))
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

        val rows = SourceContract.read("ui/activity/CommentReplyButtonPresenter.kt")
        val binding = rows.after("bindFavoriteSwitch(this, FeaturePreferences.REMOVE_COMMENT_REPLY_BUTTON, directToggle = true)")
            .before("text = stringResource(R.string.remove_comment_reply_button)")
        assertTrue(binding.contains("bindSettingDestination(this, FeaturePreferences.REMOVE_COMMENT_REPLY_BUTTON)"))
        assertTrue(SettingsUiSource.function("purifyCommentsCategory").contains("commentReplyButtonSettingsRows(this, removeCommentReplyButton)"))

        assertTrue(home.contains("store.descriptors.filter { it.favoriteEligible }"))
        assertTrue(home.contains("planPage(index, contents[index])"))
        assertTrue(home.contains("builtPages[index] = true"))
        assertFalse(home.contains("collect(originalContent)"))
        assertFalse(home.contains("originalContent.removeAllViews()"))
        assertTrue(mainFile.contains("target.deferredSettingId"))
        assertTrue(mainFile.contains("settingsHome?.ensurePage(descriptor.page)"))
        assertTrue(mainFile.contains("it.deferredSettingId == null && id in it.settingIds"))
        assertTrue(mainFile.contains("SettingsSearchItem(\"model:${'$'}{descriptor.id}\""))
    }

    @Test
    fun favoritesRepositoryAndGesturesCoordinateRestorationWithoutStaleCallbacks() {
        val repository = SettingsUiSource.file("SettingsFavoritesRepository")
        val presenter = SettingsUiSource.file("SettingsHomePresenter")
        val dialog = SettingsUiSource.function("showSettingsFavoritesDialog")
        assertTrue(repository.contains("val application = context.applicationContext"))
        assertTrue(repository.contains("val recipient = WeakReference(observer)"))
        assertTrue(repository.contains("queue.execute"))
        assertTrue(repository.contains("recipient.get()?.onFavoritesResult(result)"))
        assertFalse(presenter.contains("newSingleThreadExecutor"))
        assertFalse(presenter.contains("SettingsFavoritesStore."))
        assertFalse(dialog.contains("SettingsFavoritesStore."))
        assertTrue(presenter.contains("afterEdit = null"))
        assertTrue(presenter.contains("if (disposed || activity.isFinishing || activity.isDestroyed) return"))

        val state = SettingsFavoritesState(listOf("future", "A", "removed", "B", "C"))
        val first = SettingsFavoritesPolicy.move(state, "C", state.ids.indexOf("C") - 1)!!
        val second = SettingsFavoritesPolicy.move(first, "C", first.ids.indexOf("C") - 1)!!
        assertEquals(listOf("future", "A", "C", "removed", "B"), second.ids)
        val encoded = SettingsFavoritesPolicy.encode(second)!!
        assertEquals(second, SettingsFavoritesPolicy.decode(mapOf("schema" to 1, "ids" to encoded)))
        assertTrue(dialog.contains("displayIds?.indexOf(it)"))
        assertTrue(dialog.contains("SettingsFavoritesRepository.Edit.Move(id, index)"))
        assertTrue(dialog.contains("settings_favorites_missing"))

        assertTrue(presenter.contains("settings_home_page"))
        assertTrue(presenter.contains("settings_home_scroll_"))
        assertTrue(presenter.contains("schedulePageRestore(0)"))
        assertTrue(presenter.contains("schedulePageRestore(index)"))
        assertTrue(presenter.contains("if (!disposed) scrollRestore.consume(ticket)"))
        assertTrue(presenter.contains("scrollRestore.savedOffset(index, scroll.scrollY)"))
        assertTrue(presenter.contains("scrollRestore.close()"))
        assertTrue(presenter.contains("stretches.forEach(finishStretch)"))
        assertTrue(presenter.contains("peerDisposers.forEach { it() }"))
        assertTrue(presenter.contains("pager.selectedPage == index"))
        assertTrue(presenter.contains("manageButton?.isEnabled = snapshot != null && !editing"))
        assertTrue(dialog.contains("if (home.editing) return"))
        assertTrue(SettingsUiSource.function("onDestroy").contains("settingsHome?.dispose()"))
        assertTrue(SettingsUiSource.function("onSaveInstanceState").contains("settingsHome?.saveState(outState)"))

        val scroll = SettingsUiSource.file("SettingsHomeScrollView")
        assertTrue(presenter.contains("pager.selectedPage == index && pager.isSettled"))
        assertTrue(presenter.contains("pager.onMotionStarted = { stretches.forEach(finishStretch) }"))
        assertTrue(presenter.contains("pager.onUserInteraction = { if (!revealingPage) userNavigated() }"))
        assertTrue(presenter.contains("{ cancelPageRestore(index); userNavigated() }"))
        assertTrue(presenter.contains("{ cancelPageRestore(index); navigationTouched() }"))
        assertTrue(presenter.contains("scrollRestore.consume(ticket)"))
        assertFalse(presenter.contains("userNavigationGeneration == 0L"))
        val down = scroll.after("MotionEvent.ACTION_DOWN ->").before("MotionEvent.ACTION_MOVE ->")
        assertFalse(down.contains("onUserScroll()"))
        val move = scroll.after("MotionEvent.ACTION_MOVE ->").before("MotionEvent.ACTION_POINTER_UP ->")
        assertTrue(move.contains("dy > slop && dy > dx * 1.2f"))
        assertTrue(move.contains("onUserScroll()"))
        assertTrue(scroll.contains("return super.dispatchTouchEvent(event)"))

        val key = SettingsUiSource.functions(scroll, "executeKeyEvent").single()
        assertTrue(key.contains("event.action == KeyEvent.ACTION_DOWN"))
        assertTrue(key.contains("handled = super.executeKeyEvent(event)"))
        assertTrue(key.contains("return handled"))
        val page = SettingsUiSource.functions(scroll, "pageScroll").single()
        assertTrue(page.contains("val handled = super.pageScroll(direction)"))
        assertTrue(page.contains("keySession.pageResult(handled)"))
        val accessibility = SettingsUiSource.functions(scroll, "performAccessibilityAction").single()
        assertTrue(accessibility.contains("if (handled && scrollAction) onUserScroll()"))
        assertTrue(accessibility.contains("ACTION_SCROLL_UP"))
        assertTrue(accessibility.contains("ACTION_SCROLL_DOWN"))
        val generic = SettingsUiSource.functions(scroll, "onGenericMotionEvent").single()
        assertTrue(generic.contains("val handled = super.onGenericMotionEvent(event)"))
        assertTrue(generic.contains("if (handled && event.actionMasked == MotionEvent.ACTION_SCROLL) onUserScroll()"))
    }

    @Test
    fun floatingToolbarSearchLazinessAndPagerChainWireAboveScrollContent() {
        val home = SourceContract.read("ui/activity/SettingsHomePresenter.kt")
        val main = SourceContract.read("ui/activity/MainActivity.kt")

        assertTrue(home.contains("floatingToolbar?.let { toolbar ->"))
        assertTrue(home.contains("toolbar.removeSelf()"))
        assertTrue(home.contains("background = activity.skinFloatingBackground(activity.monetColors.surface, 29f)"))
        assertTrue(home.contains("pageLayer.addView(header, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {"))
        assertTrue(home.contains("setMargins(dp(10), dp(4) + systemTopInset, dp(10), 0)"))
        assertTrue(home.contains("headerInset = bottom - top + dp(8)"))
        assertTrue(home.contains("private fun applyScrollInsets()"))
        assertTrue(home.contains("val top = (if (headerInset > 0) headerInset else dp(8)) + systemTopInset"))
        assertTrue(home.contains("internal val currentHeaderInset: Int get() = headerInset + systemTopInset"))

        val install = home.after("private fun installTopInset()").before("private fun applyScrollInsets()")
        assertTrue(install.contains("activity.observeModuleInsets(shell)"))
        assertTrue(install.contains("WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()"))
        assertTrue(install.contains("shell.setPadding(safe.left, 0, safe.right, safe.bottom)"))
        assertTrue(install.contains("params.topMargin = dp(4) + systemTopInset"))
        assertTrue(install.contains("applyScrollInsets()"))
        assertTrue(install.contains("ViewCompat.requestApplyInsets(shell)"))
        assertTrue(home.contains("SettingsFavoritesRepository.read(activity.applicationContext, this)\n        installTopInset()"))

        val header = home.after("val header = FrameLayout(activity).apply {").before("pageLayer.addView(header")
        assertTrue(header.contains("isClickable = true"))
        assertTrue(main.contains("floatingToolbar = settingsFloatingToolbar"))
        val rowInit = main.after("settingsFloatingToolbar = this").before("}")
        assertFalse(rowInit.contains("background ="))
        assertFalse(rowInit.contains("skinFloatingBackground"))
        assertFalse(rowInit.contains("skinTopBarBackground"))

        // 搜索弹窗首帧不遍历设置视图树；空白查询不触发匹配。
        val dialogs = SourceContract.read("ui/activity/ToolbarBubbleDialogs.kt")
        val search = dialogs.after("internal fun MainActivity.showSettingsSearchDialog(")
            .before("private fun MainActivity.highlightedSettingsSearchText(")
        assertTrue(search.contains("val runtimeTargets by lazy(LazyThreadSafetyMode.NONE) { collectSettingsSearchTargets() }"))
        assertTrue(search.contains("val targetByKey by lazy(LazyThreadSafetyMode.NONE)"))
        val render = search.after("fun renderResults(query: String) {").before("results.forEach")
        assertTrue(render.contains("val results = if (query.isBlank()) emptyList() else SettingsSearchMatcher.searchMatches("))

        // 页面位置转发与 TextChain 销毁接线。
        assertTrue(home.contains("textChain?.onPositionChanged()"))
        assertTrue(home.after("fun dispose() {").contains("textChain?.dispose()"))
    }

    @Test
    fun searchRevealSwitchesPagesBeforeCoordinatesAndCancelsOwnershipCleanly() {
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

        val revealSection = SourceContract.read("ui/activity/MainActivity.kt")
            .after("internal fun revealSettingsSearchTarget(")
            .before("internal fun launchSettingsBackup()")
        assertTrue(revealSection.contains("home?.currentHeaderInset"))
        assertTrue(revealSection.indexOf("val topOffset") < revealSection.indexOf("val desiredY"))

        val cancel = SettingsUiSource.function("cancelSettingsReveal")
        assertTrue(cancel.contains("settingsRevealRequest.cancel()"))
        assertTrue(cancel.contains("clearSettingsSearchTargetHighlight()"))
        assertTrue(cancel.contains("highlightNavigationGeneration++"))
        assertTrue(cancel.contains("highlightNavigationInFlight = false"))
        assertTrue(cancel.contains("if (!keepPendingHighlight) pendingHighlightDestination = null"))
        assertTrue(SettingsUiSource.function("onPause").contains("cancelSettingsReveal(keepPendingHighlight = true)"))
        val highlight = SettingsUiSource.function("revealHighlightDestination")
        assertTrue(highlight.indexOf("cancelSettingsReveal()") < highlight.indexOf("highlightNavigationInFlight = true"))
        assertTrue(highlight.contains("fromHighlight = true"))
        val install = SettingsUiSource.function("installSettingsHome")
        assertTrue(install.contains("if (settingsRevealRequest.isActive) cancelSettingsReveal()"))
        val scroll = SettingsUiSource.file("SettingsHomeScrollView")
        val down = scroll.after("MotionEvent.ACTION_DOWN ->").before("MotionEvent.ACTION_MOVE ->")
        assertTrue(down.contains("onContentTouch()"))
        val release = reveal.after("token = settingsRevealRequest.begin {").before("observer.addOnPreDrawListener")
        assertTrue(release.contains("removeOnPreDrawListener(listener)"))
        assertTrue(release.contains("scrollMotion.cancel()"))
        assertTrue(release.contains("scrollAnimator?.cancel()"))
        assertFalse(release.contains("smoothScrollTo"))
        assertTrue(reveal.contains("scrollMotion.frame(scrollToken, it.animatedFraction)"))
    }

    private fun constantValues(): Map<String, String> {
        val files = mapOf(
            "FeaturePreferences" to "hook/feature/FeaturePreferences.kt",
            "HookEntry" to "hook/HookEntry.kt",
            "MaterialColorSpecStore" to "settings/appearance/MaterialColorSpecStore.kt",
            "ModalBackdropBlurStore" to "settings/appearance/ModalBackdropBlurStore.kt",
            "CommunicationCompatibilityStore" to "runtime/compat/CommunicationCompatibilityStore.kt"
        )
        val out = mutableMapOf<String, String>()
        files.forEach { (owner, path) ->
            Regex("""const val (\w+)\s*=\s*"([^"]*)"""").findAll(SourceContract.read(path)).forEach {
                out["$owner.${it.groupValues[1]}"] = it.groupValues[2]
            }
        }
        return out
    }
}
