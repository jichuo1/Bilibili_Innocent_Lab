package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.content.SharedPreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import java.lang.reflect.Proxy
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsActivityPolicyTest {

    @Test
    fun backupLazyGroupPolicies() {
        SettingsBackupLazyGroupCases().verifyAllCases()
    }

    @Test
    fun stateHolderAndPolicyContracts() {
        SettingsStateHolderAndPolicyCases().verifyAllCases()
    }

    @Test
    fun adaptiveWindowLayoutBreakpointsAndConstraints() {
        AdaptiveWindowLayoutCases().verifyAllCases()
    }

    @Test
    fun settingsActionCatalogAndSearchPolicies() {
        SettingsActionCatalogCases().verifyActionAndNavigationCases()
        SettingsSearchMatcherCases().verifyAllCases()
    }

    @Test
    fun settingsExpansionAndFavoritesPolicies() {
        SettingsExpansionStateCases().verifyAllCases()
        SettingsFavoritesPolicyCases().verifyAllCases()
    }

    @Test
    fun settingsPageBuildPlanAndScrollRestore() {
        SettingsPagePlanCases().verifyAllCases()
        SettingsPageScrollRestoreCases().verifyAllCases()
    }
}

private class SettingsBackupLazyGroupCases {

    fun collapsedGroupDefersEntryViewConstructionUntilFirstExpandAndIsIdempotent() {
        val built = ArrayList<Pair<Int, String>>()
        val group = LazyEntryGroupState(listOf("alpha", "beta", "gamma")) { index, item ->
            built += index to item
        }

        // 默认收起期间不构建任何条目子视图。
        assertFalse(group.populated)
        assertTrue(built.isEmpty())

        // 首次点击展开时按序懒构建全部条目。
        assertTrue(group.ensurePopulated())
        assertTrue(group.populated)
        assertEquals(listOf(0 to "alpha", 1 to "beta", 2 to "gamma"), built)

        // 折叠再展开时不再重复构建子视图。
        assertFalse(group.ensurePopulated())
        assertEquals(3, built.size)
    }

    fun emptyGroupRemainsUnpopulatedWithoutInvokingBuilder() {
        var calls = 0
        val emptyGroup = LazyEntryGroupState(emptyList<String>()) { _, _ -> calls++ }
        assertFalse(emptyGroup.ensurePopulated())
        assertFalse(emptyGroup.populated)
        assertEquals(0, calls)
    }

    fun containerMorphDelegateGeometryHelpersTranslateAndValidateBounds() {
        val screenRect = SettingsBackupMotionRect(left = 120f, top = 260f, right = 420f, bottom = 360f)
        val local = ContainerMorphActivityDelegate.toLocal(screenRect, intArrayOf(20, 60))
        assertEquals(100f, local.left, 0.01f)
        assertEquals(200f, local.top, 0.01f)
        assertEquals(400f, local.right, 0.01f)
        assertEquals(300f, local.bottom, 0.01f)

        assertTrue(ContainerMorphActivityDelegate.isWithinWindow(local, hostWidth = 1080f, hostHeight = 2400f, tolerancePx = 8))
        val outOfBounds = SettingsBackupMotionRect(left = -30f, top = 0f, right = 400f, bottom = 300f)
        assertFalse(ContainerMorphActivityDelegate.isWithinWindow(outOfBounds, hostWidth = 1080f, hostHeight = 2400f, tolerancePx = 8))
    }

    fun verifyAllCases() {
        collapsedGroupDefersEntryViewConstructionUntilFirstExpandAndIsIdempotent()
        emptyGroupRemainsUnpopulatedWithoutInvokingBuilder()
        containerMorphDelegateGeometryHelpersTranslateAndValidateBounds()
    }
}

private class SettingsStateHolderAndPolicyCases {

    private class FakePreferences(
        var commitResult: Boolean = true,
        var failWrite: Boolean = false,
        var simulateStaleReadback: Boolean = false
    ) {
        val data = LinkedHashMap<String, Any?>()

        val prefs: SharedPreferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getBoolean" -> {
                    val key = args[0] as String
                    val def = args[1] as Boolean
                    val stored = (data[key] as? Boolean) ?: def
                    if (simulateStaleReadback) !stored else stored
                }
                "getString" -> {
                    val key = args[0] as String
                    val def = args[1] as String?
                    val stored = (data[key] as? String) ?: def
                    if (simulateStaleReadback && stored != null) "$stored-stale" else stored
                }
                "getInt" -> {
                    val key = args[0] as String
                    val def = args[1] as Int
                    val stored = (data[key] as? Int) ?: def
                    if (simulateStaleReadback) stored + 1 else stored
                }
                "edit" -> editorProxy()
                "contains" -> (args[0] as String) in data
                "getAll" -> LinkedHashMap(data)
                else -> null
            }
        } as SharedPreferences

        private fun editorProxy(): SharedPreferences.Editor {
            val staged = LinkedHashMap<String, Any?>()
            return Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)
            ) { proxy, method, args ->
                when (method.name) {
                    "putBoolean" -> { staged[args[0] as String] = args[1] as Boolean; proxy }
                    "putString" -> { staged[args[0] as String] = args[1] as String?; proxy }
                    "putInt" -> { staged[args[0] as String] = args[1] as Int; proxy }
                    "putLong" -> { staged[args[0] as String] = args[1] as Long; proxy }
                    "apply" -> {
                        if (failWrite) error("apply failed")
                        data.putAll(staged)
                        null
                    }
                    "commit" -> {
                        if (failWrite) error("commit failed")
                        if (commitResult) data.putAll(staged)
                        commitResult
                    }
                    "clear" -> { data.clear(); staged.clear(); proxy }
                    else -> proxy
                }
            } as SharedPreferences.Editor
        }
    }

    fun settingWritePolicyVerifiesReadbackAndRollsBackOnAllFailureModes() {
        val fake = FakePreferences()

        val ok = SettingWritePolicy.attemptBoolean(
            preferencesProvider = { fake.prefs },
            key = "k_bool",
            previous = false,
            requested = true,
            verifyReadback = true,
            extraWrites = { putLong("k_rev", 42L) }
        )
        assertTrue(ok.saved)
        assertTrue(ok.confirmed)
        assertNull(ok.failure)
        assertEquals(true, fake.data["k_bool"])
        assertEquals(42L, fake.data["k_rev"])

        // 空 preferences 回滚到 previous 并记录异常。
        val missing = SettingWritePolicy.attemptBoolean(
            preferencesProvider = { null },
            key = "k_bool",
            previous = true,
            requested = false
        )
        assertFalse(missing.saved)
        assertTrue(missing.confirmed)
        assertNotNull(missing.failure)

        // sync commit 返回 false 回滚。
        fake.commitResult = false
        val failedCommit = SettingWritePolicy.attemptBoolean(
            preferencesProvider = { fake.prefs },
            key = "k_bool",
            previous = true,
            requested = false,
            commitSynchronously = true
        )
        assertFalse(failedCommit.saved)
        assertTrue(failedCommit.confirmed)

        // 回读校验不一致时阻止错误确认并回滚。
        fake.commitResult = true
        fake.simulateStaleReadback = true
        val stale = SettingWritePolicy.attemptBoolean(
            preferencesProvider = { fake.prefs },
            key = "k_bool",
            previous = true,
            requested = false,
            verifyReadback = true
        )
        assertFalse(stale.saved)
        assertTrue(stale.confirmed)

        // String 与 Int 边界值（含 Int.MIN_VALUE 哨兵）校验。
        fake.simulateStaleReadback = false
        val sOk = SettingWritePolicy.attemptString({ fake.prefs }, "k_str", "old", "new")
        assertTrue(sOk.saved)
        assertEquals("new", sOk.confirmed)
        val iOk = SettingWritePolicy.attemptInt({ fake.prefs }, "k_int", 0, Int.MIN_VALUE)
        assertTrue(iOk.saved)
        assertEquals(Int.MIN_VALUE, iOk.confirmed)
    }

    fun settingsStateHolderUnifiesMirrorsVerificationRollbackAndCallbacks() {
        val fake = FakePreferences()
        val errors = ArrayList<String>()
        val commits = ArrayList<Pair<String, Boolean>>()
        val holder = SettingsStateHolder(
            preferencesProvider = { fake.prefs },
            onWriteError = { message, _ -> errors += message },
            onStateCommitted = { key, saved -> commits += key to saved }
        )

        var mirrorField = false
        holder.registerBooleanMirror(
            key = "feature.sample",
            failureMessage = "write sample failed",
            getter = { mirrorField },
            setter = { mirrorField = it },
            verifyReadback = true
        )

        val enableOutcome = holder.persistRegisteredBoolean("feature.sample", true)!!
        assertTrue(enableOutcome.saved)
        assertTrue(mirrorField)
        assertTrue(holder.readBoolean("feature.sample"))
        assertEquals(listOf("feature.sample" to true), commits)

        // 模拟写入抛异常，镜像字段必须自动回滚为 true。
        fake.failWrite = true
        val failOutcome = holder.persistRegisteredBoolean("feature.sample", false)!!
        assertFalse(failOutcome.saved)
        assertTrue(failOutcome.confirmed)
        assertTrue(mirrorField)
        assertEquals(listOf("write sample failed"), errors)
        assertEquals("feature.sample" to false, commits.last())

        // 同步镜像方法更新字段并反映到状态读口。
        assertTrue(holder.syncMirror("feature.sample", false))
        assertFalse(mirrorField)
        assertFalse(holder.readBoolean("feature.sample"))
        assertFalse(holder.syncMirror("feature.unknown", true))

        // commitCustomBoolean 当二级镜像写入校验失败时也统一回滚。
        fake.failWrite = false
        val customFailed = holder.commitCustomBoolean(
            key = "feature.sample",
            previous = false,
            requested = true,
            failureMessage = "custom mirror write failed"
        ) {
            fake.prefs.edit().putBoolean("feature.sample", true).apply()
            error("atomic mirror write returned false")
        }
        assertFalse(customFailed.saved)
        assertFalse(mirrorField)
        assertFalse(holder.readBoolean("feature.sample"))
        assertEquals("custom mirror write failed", errors.last())
    }

    fun verifyAllCases() {
        settingWritePolicyVerifiesReadbackAndRollsBackOnAllFailureModes()
        settingsStateHolderUnifiesMirrorsVerificationRollbackAndCallbacks()
    }
}

private class AdaptiveWindowLayoutCases {
    fun classifyCoversFoldableAndTabletWindowBreakpoints() {
        val coverPortrait = AdaptiveWindowLayout.classify(widthDp = 393, heightDp = 851)
        assertEquals(WindowWidthClass.COMPACT, coverPortrait.widthClass)
        assertEquals(WindowHeightClass.MEDIUM, coverPortrait.heightClass)

        val splitHalf = AdaptiveWindowLayout.classify(widthDp = 340, heightDp = 800)
        assertEquals(WindowWidthClass.COMPACT, splitHalf.widthClass)
        assertEquals(WindowHeightClass.MEDIUM, splitHalf.heightClass)

        val coverLandscape = AdaptiveWindowLayout.classify(widthDp = 700, heightDp = 380)
        assertEquals(WindowWidthClass.MEDIUM, coverLandscape.widthClass)
        assertEquals(WindowHeightClass.COMPACT, coverLandscape.heightClass)

        val foldableUnfolded = AdaptiveWindowLayout.classify(widthDp = 840, heightDp = 920)
        assertEquals(WindowWidthClass.EXPANDED, foldableUnfolded.widthClass)
        assertEquals(WindowHeightClass.EXPANDED, foldableUnfolded.heightClass)

        val tabletLandscape = AdaptiveWindowLayout.classify(widthDp = 1280, heightDp = 800)
        assertEquals(WindowWidthClass.EXPANDED, tabletLandscape.widthClass)
        assertEquals(WindowHeightClass.MEDIUM, tabletLandscape.heightClass)

        val clampedFromPx = AdaptiveWindowLayout.classifyPx(widthPx = -10, heightPx = 0, density = 0f)
        assertEquals(0, clampedFromPx.widthDp)
        assertEquals(0, clampedFromPx.heightDp)
        assertEquals(WindowWidthClass.COMPACT, clampedFromPx.widthClass)
        assertEquals(WindowHeightClass.COMPACT, clampedFromPx.heightClass)
    }

    fun navigationChromeModeSwitchesBetweenBottomBarAndSideRailCleanly() {
        assertEquals(
            NavigationChromeMode.BOTTOM_BAR,
            AdaptiveWindowLayout.navigationChromeMode(AdaptiveWindowLayout.classify(393, 851))
        )
        assertEquals(
            NavigationChromeMode.SIDE_RAIL,
            AdaptiveWindowLayout.navigationChromeMode(AdaptiveWindowLayout.classify(600, 800))
        )
        assertEquals(
            NavigationChromeMode.SIDE_RAIL,
            AdaptiveWindowLayout.navigationChromeMode(AdaptiveWindowLayout.classify(840, 840))
        )
        // 横屏口袋手机 (>=520dp 宽且 340..479dp 高) 让出垂直空间给 SideRail
        assertEquals(
            NavigationChromeMode.SIDE_RAIL,
            AdaptiveWindowLayout.navigationChromeMode(AdaptiveWindowLayout.classify(540, 360))
        )
        // 极矮分屏 (<340dp 高) 退回 BottomBar 避免 SideRail 纵向挤扁
        assertEquals(
            NavigationChromeMode.BOTTOM_BAR,
            AdaptiveWindowLayout.navigationChromeMode(AdaptiveWindowLayout.classify(720, 320))
        )
    }

    fun contentWidthsSidePaddingAndClearanceStayCenteredWithoutOverflow() {
        val compact = AdaptiveWindowLayout.classify(393, 851)
        assertEquals(Int.MAX_VALUE, AdaptiveWindowLayout.contentMaxWidthDp(compact))
        assertEquals(Int.MAX_VALUE, AdaptiveWindowLayout.contentMaxWidthPx(compact, density = 3f))
        assertEquals(45, AdaptiveWindowLayout.horizontalContentPaddingPx(1179, Int.MAX_VALUE, minSidePaddingPx = 45))

        val medium = AdaptiveWindowLayout.classify(700, 900)
        assertEquals(680, AdaptiveWindowLayout.contentMaxWidthDp(medium, multiColumn = false))
        assertEquals(760, AdaptiveWindowLayout.contentMaxWidthDp(medium, multiColumn = true))

        val expanded = AdaptiveWindowLayout.classify(960, 900)
        assertEquals(720, AdaptiveWindowLayout.contentMaxWidthDp(expanded, multiColumn = false))
        assertEquals(920, AdaptiveWindowLayout.contentMaxWidthDp(expanded, multiColumn = true))
        assertEquals(2160, AdaptiveWindowLayout.contentMaxWidthPx(expanded, density = 3f, multiColumn = false))
        assertEquals(360, AdaptiveWindowLayout.horizontalContentPaddingPx(2880, 2160, minSidePaddingPx = 45))
        // 视口窄于 maxWidthPx 时退回 minSidePaddingPx，不得出现负内边距或溢出
        assertEquals(45, AdaptiveWindowLayout.horizontalContentPaddingPx(1200, 2160, minSidePaddingPx = 45))

        assertEquals(0, AdaptiveWindowLayout.sideRailClearancePx(NavigationChromeMode.BOTTOM_BAR, 3f))
        assertEquals(294, AdaptiveWindowLayout.sideRailClearancePx(NavigationChromeMode.SIDE_RAIL, 3f))
    }

    fun multiColumnGridSpanAndModalConstraintsFollowLceBounds() {
        assertEquals(1, AdaptiveWindowLayout.gridSpanCount(360, minColumnWidthDp = 280, maxSpans = 2))
        assertEquals(1, AdaptiveWindowLayout.gridSpanCount(500, minColumnWidthDp = 280, maxSpans = 2))
        assertEquals(2, AdaptiveWindowLayout.gridSpanCount(600, minColumnWidthDp = 280, maxSpans = 2))
        assertEquals(2, AdaptiveWindowLayout.gridSpanCount(1200, minColumnWidthDp = 280, maxSpans = 2))
        assertEquals(1, AdaptiveWindowLayout.gridSpanCount(900, minColumnWidthDp = 0, maxSpans = 2))

        // LCE 1.6.2 §15.4: 非明确定宽弹窗在大屏上不超过 560dp
        assertEquals(1680, AdaptiveWindowLayout.modalMaxWidthPx(windowWidthPx = 3000, density = 3f))
        assertEquals(960, AdaptiveWindowLayout.modalMaxWidthPx(windowWidthPx = 1152, density = 3f))

        // 弹窗滚动区受 capDp 与 windowFraction 双重限制，且极矮窗口下保持安全下限
        assertEquals(1200, AdaptiveWindowLayout.modalScrollMaxHeightPx(2800, 3f, capDp = 400, windowFraction = 0.55f))
        assertEquals(900, AdaptiveWindowLayout.modalScrollMaxHeightPx(1800, 3f, capDp = 520, windowFraction = 0.50f))
        assertEquals(360, AdaptiveWindowLayout.modalScrollMaxHeightPx(600, 3f, capDp = 520, windowFraction = 0.40f))
        assertEquals(240, AdaptiveWindowLayout.modalScrollMaxHeightPx(240, 3f, capDp = 520, windowFraction = 0.40f))

        // 气泡在 Compact 下 320dp、Medium/Expanded 下 360dp，极窄分屏下安全夹紧
        assertEquals(960f, AdaptiveWindowLayout.bubbleMaxWidthPx(usableWindowWidthPx = 1200, density = 3f), 0.5f)
        assertEquals(1080f, AdaptiveWindowLayout.bubbleMaxWidthPx(usableWindowWidthPx = 2400, density = 3f), 0.5f)
        assertTrue(AdaptiveWindowLayout.bubbleMaxWidthPx(usableWindowWidthPx = 900, density = 3f) <= 828f)
    }

    fun verifyAllCases() {
        classifyCoversFoldableAndTabletWindowBreakpoints()
        navigationChromeModeSwitchesBetweenBottomBarAndSideRailCleanly()
        contentWidthsSidePaddingAndClearanceStayCenteredWithoutOverflow()
        multiColumnGridSpanAndModalConstraintsFollowLceBounds()
    }
}

private class SettingsActionCatalogCases {
    fun allRecommendationPanelOptionsCanNavigateBeforeThePageExists() {
        val descriptors = SettingsActionCatalog.descriptors().associateBy { it.storageKey }
        HomeRecommendFilterCatalog.preferenceKeys.forEach { key ->
            val descriptor = descriptors.getValue(key)
            assertEquals(SettingsCatalog.byStorageKey.getValue(key).id, descriptor.id)
            assertEquals(1, descriptor.page)
            assertFalse(descriptor.directToggle)
            assertFalse(descriptor.favoriteEligible)
        }
    }

    fun navigationMetadataIsAvailableBeforeAnyPageOrControlExists() {
        val descriptors = SettingsActionCatalog.descriptors()
        assertTrue(descriptors.size > 40)
        assertEquals(descriptors.size, descriptors.map { it.id }.distinct().size)
        assertTrue(descriptors.all { !it.directToggle && !it.favoriteEligible })
        descriptors.forEach { descriptor ->
            val spec = SettingsCatalog.byId.getValue(descriptor.id)
            assertEquals(spec.storageKey, descriptor.storageKey)
            assertEquals(spec.labelRes, descriptor.titleRes)
            assertTrue(descriptor.page in 1..4)
        }
    }

    fun complexNonBooleanControlsRemainNavigationInsteadOfBooleanWrites() {
        val descriptors = SettingsActionCatalog.descriptors().associateBy { it.storageKey }
        listOf(
            FeaturePreferences.PLAYER_BUFFER_MEMORY_MB,
            FeaturePreferences.PLAYER_DEFAULT_SPEED_PERCENT,
            FeaturePreferences.COMMENT_FILTER_KEYWORDS,
            FeaturePreferences.HOST_BACKGROUND_PRESET
        ).forEach { key ->
            assertFalse(descriptors.getValue(key).directToggle)
            assertFalse(descriptors.getValue(key).favoriteEligible)
        }
    }

    fun verifyActionAndNavigationCases() {
        fun runIsolated(block: (SettingsActionCatalogCases) -> Unit) {
            val s = SettingsActionCatalogCases()
            block(s)
        }
        runIsolated { s -> s.allRecommendationPanelOptionsCanNavigateBeforeThePageExists() }
        runIsolated { s -> s.navigationMetadataIsAvailableBeforeAnyPageOrControlExists() }
        runIsolated { s -> s.complexNonBooleanControlsRemainNavigationInsteadOfBooleanWrites() }
    }
}

private class SettingsExpansionStateCases {
    private val sections = setOf("PURIFICATION_ADVANCED", "ENHANCEMENT_ADVANCED", "APPEARANCE", "COMPATIBILITY")
    private val categories = (0 until 14).associate { "CATEGORY_$it" to false } + ("PURIFY_SHARED" to true)

    private fun newState(schema: Int = 1, saved: Map<String, Boolean> = emptyMap()) =
        SettingsExpansionState(sections, categories, setOf("PURIFY_SHARED"), schema, saved)

    fun defaultsIncludeEveryDeclaredStateAndKeepTheFixedCategoryVisible() {
        val state = newState()
        assertEquals(19, state.snapshot().size)
        assertTrue(sections.none(state::secondary))
        assertTrue(state.category("PURIFY_SHARED"))
        state.setCategory("PURIFY_SHARED", false)
        assertTrue(state.category("PURIFY_SHARED"))
    }

    fun anUnbuiltPageSurvivesMultipleSaveAndRecreateCycles() {
        val first = newState(saved = mapOf("secondary:ENHANCEMENT_ADVANCED" to true, "category:CATEGORY_7" to true))
        val second = newState(saved = first.snapshot())
        val third = newState(saved = second.snapshot())
        assertTrue(third.secondary("ENHANCEMENT_ADVANCED"))
        assertTrue(third.category("CATEGORY_7"))
        assertEquals(first.snapshot(), third.snapshot())
    }

    fun newCommandsReplacePendingRestoreBeforeThePageExists() {
        val state = newState(saved = mapOf("secondary:APPEARANCE" to true, "category:CATEGORY_3" to true))
        state.setSecondary("APPEARANCE", false)
        state.setCategory("CATEGORY_3", false)
        val recreated = newState(saved = state.snapshot())
        assertFalse(recreated.secondary("APPEARANCE"))
        assertFalse(recreated.category("CATEGORY_3"))
        state.setCategory("CATEGORY_3", true)
        assertTrue(state.category("CATEGORY_3"))
    }

    fun unknownNamesAreIgnoredAndUnknownSchemaUsesDeclaredDefaults() {
        val saved = mapOf("secondary:APPEARANCE" to true, "category:PURIFY_SHARED" to false,
            "secondary:REMOVED" to true, "category:FUTURE" to true)
        val compatible = newState(saved = saved)
        assertTrue(compatible.secondary("APPEARANCE"))
        assertTrue(compatible.category("PURIFY_SHARED"))
        assertFalse(compatible.snapshot().containsKey("secondary:REMOVED"))
        assertFalse(compatible.snapshot().containsKey("category:FUTURE"))
        assertEquals(newState().snapshot(), newState(schema = 2, saved = saved).snapshot())
    }

    fun inputAndOutputSnapshotsDoNotAliasLiveState() {
        val input = mutableMapOf("secondary:COMPATIBILITY" to true)
        val state = newState(saved = input)
        input["secondary:COMPATIBILITY"] = false
        assertTrue(state.secondary("COMPATIBILITY"))
        val saved = state.snapshot()
        state.setSecondary("COMPATIBILITY", false)
        assertEquals(true, saved["secondary:COMPATIBILITY"])
    }

    fun identicalNamesInDifferentDomainsRemainIndependent() {
        val state = SettingsExpansionState(setOf("SHARED"), mapOf("SHARED" to false))
        state.setSecondary("SHARED", true)
        assertTrue(state.secondary("SHARED"))
        assertFalse(state.category("SHARED"))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (SettingsExpansionStateCases) -> Unit) {
            val s = SettingsExpansionStateCases()
            block(s)
        }
        runIsolated { s -> s.defaultsIncludeEveryDeclaredStateAndKeepTheFixedCategoryVisible() }
        runIsolated { s -> s.anUnbuiltPageSurvivesMultipleSaveAndRecreateCycles() }
        runIsolated { s -> s.newCommandsReplacePendingRestoreBeforeThePageExists() }
        runIsolated { s -> s.unknownNamesAreIgnoredAndUnknownSchemaUsesDeclaredDefaults() }
        runIsolated { s -> s.inputAndOutputSnapshotsDoNotAliasLiveState() }
        runIsolated { s -> s.identicalNamesInDifferentDomainsRemainIndependent() }
    }
}

private class SettingsFavoritesPolicyCases {
    private fun decode(ids: String) = SettingsFavoritesPolicy.decode(mapOf("schema" to 1, "ids" to ids))

    fun legacyAcrylicFavoriteOpensTheMaterialChoiceAndKeepsItsPosition() {
        val state = decode("other.setting\nhost.cards.acrylic.enabled\nhost.cards.material\nlast.setting")
        assertEquals(listOf("other.setting", "host.cards.material", "last.setting"), state.ids)
        assertEquals(state, decode(requireNotNull(SettingsFavoritesPolicy.encode(state))))
    }

    fun freshStateCanBeEditedAndRoundTripsInUserOrder() {
        val empty = SettingsFavoritesPolicy.decode(emptyMap<String, Any>())
        assertTrue(empty.canWrite)
        val first = SettingsFavoritesPolicy.add(empty, "player.speed.enabled")!!
        val second = SettingsFavoritesPolicy.add(first, "home.ad.hidden")!!
        assertEquals(listOf("player.speed.enabled", "home.ad.hidden"), second.ids)
        assertEquals(second, decode(SettingsFavoritesPolicy.encode(second)!!))
    }

    fun duplicateEntriesKeepFirstPositionAndAddingExistingDoesNotReorder() {
        val state = decode("feature.b\nfeature.a\nfeature.b")
        assertEquals(listOf("feature.b", "feature.a"), state.ids)
        assertEquals(state, SettingsFavoritesPolicy.add(state, "feature.b"))
        assertEquals("feature.b\nfeature.a", SettingsFavoritesPolicy.encode(state))
    }

    fun moveAndRemovePreserveOtherEntriesAndRejectInvalidPositions() {
        val original = decode("a\nb\nc")
        val moved = SettingsFavoritesPolicy.move(original, "a", 2)!!
        assertEquals(listOf("b", "c", "a"), moved.ids)
        assertEquals(original, SettingsFavoritesPolicy.move(moved, "a", 0))
        assertEquals(listOf("b", "a"), SettingsFavoritesPolicy.remove(moved, "c")!!.ids)
        assertEquals(original, SettingsFavoritesPolicy.remove(original, "missing"))
        assertNull(SettingsFavoritesPolicy.move(original, "a", -1))
        assertNull(SettingsFavoritesPolicy.move(original, "a", 3))
        assertNull(SettingsFavoritesPolicy.move(original, "missing", 0))
        assertEquals(listOf("a", "b", "c"), original.ids)
    }

    fun unavailableIdsAreOnlyHiddenFromDisplayAndSurviveEditsAndRoundTrip() {
        val original = decode("future.feature\navailable.one\nremoved.feature\navailable.two")
        assertEquals(listOf("available.one", "available.two"),
            SettingsFavoritesPolicy.visibleIds(original, setOf("available.one", "available.two")))
        assertEquals(emptyList<String>(), SettingsFavoritesPolicy.visibleIds(original, emptySet()))
        val added = SettingsFavoritesPolicy.add(original, "available.three")!!
        assertEquals(original.ids + "available.three", decode(SettingsFavoritesPolicy.encode(added)!!).ids)
        val moved = SettingsFavoritesPolicy.move(added, "available.two", 1)!!
        assertEquals(listOf("future.feature", "available.two", "available.one", "removed.feature", "available.three"), moved.ids)
    }

    fun malformedAndFutureDataNeverBecomeWritableEmptyStates() {
        val malformed = listOf(
            mapOf("schema" to 1), mapOf("ids" to "a"), mapOf("schema" to "1", "ids" to "a"),
            mapOf("schema" to 1, "ids" to setOf("a")), mapOf("schema" to 1, "ids" to "a\n"),
            mapOf("schema" to 1, "ids" to "a\n\nb"), mapOf("schema" to 1, "ids" to "a b"),
            mapOf("schema" to 1, "ids" to "a\rb"), mapOf("schema" to 1, "ids" to ".a")
        )
        malformed.forEach { values ->
            val state = SettingsFavoritesPolicy.decode(values)
            assertEquals(SettingsFavoritesStatus.CORRUPT, state.status)
            assertFalse(state.canWrite)
            assertNull(SettingsFavoritesPolicy.add(state, "a"))
            assertNull(SettingsFavoritesPolicy.remove(state, "a"))
            assertNull(SettingsFavoritesPolicy.move(state, "a", 0))
            assertNull(SettingsFavoritesPolicy.encode(state))
        }
        for (version in listOf(-1, 0, 2, Int.MAX_VALUE)) {
            val raw = mapOf("schema" to version, "ids" to "future.feature")
            val state = SettingsFavoritesPolicy.decode(raw)
            assertEquals(SettingsFavoritesStatus.UNSUPPORTED, state.status)
            assertNull(SettingsFavoritesPolicy.add(state, "a"))
            assertEquals("future.feature", raw["ids"])
        }
    }

    fun capacityAndIdBoundsRejectDataWithoutTruncatingSelections() {
        val full = decode((0 until SettingsFavoritesPolicy.MAX_ITEMS).joinToString("\n") { "feature.$it" })
        assertEquals(SettingsFavoritesPolicy.MAX_ITEMS, full.ids.size)
        assertNull(SettingsFavoritesPolicy.add(full, "one.more"))
        assertEquals(full, SettingsFavoritesPolicy.add(full, "feature.0"))
        assertFalse(decode(full.ids.joinToString("\n") + "\nextra").canWrite)
        assertFalse(decode("x".repeat(SettingsFavoritesPolicy.MAX_ID_LENGTH + 1)).canWrite)
        assertTrue(decode("x".repeat(SettingsFavoritesPolicy.MAX_ID_LENGTH)).canWrite)
        assertNull(SettingsFavoritesPolicy.add(SettingsFavoritesState(), ""))
        assertNull(SettingsFavoritesPolicy.add(SettingsFavoritesState(), "injected\nid"))
        assertNull(SettingsFavoritesPolicy.add(SettingsFavoritesState(status = SettingsFavoritesStatus.UNAVAILABLE), "a"))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (SettingsFavoritesPolicyCases) -> Unit) {
            val s = SettingsFavoritesPolicyCases()
            block(s)
        }
        runIsolated { s -> s.legacyAcrylicFavoriteOpensTheMaterialChoiceAndKeepsItsPosition() }
        runIsolated { s -> s.freshStateCanBeEditedAndRoundTripsInUserOrder() }
        runIsolated { s -> s.duplicateEntriesKeepFirstPositionAndAddingExistingDoesNotReorder() }
        runIsolated { s -> s.moveAndRemovePreserveOtherEntriesAndRejectInvalidPositions() }
        runIsolated { s -> s.unavailableIdsAreOnlyHiddenFromDisplayAndSurviveEditsAndRoundTrip() }
        runIsolated { s -> s.malformedAndFutureDataNeverBecomeWritableEmptyStates() }
        runIsolated { s -> s.capacityAndIdBoundsRejectDataWithoutTruncatingSelections() }
    }
}

private class SettingsPagePlanCases {
    private fun plan(log: MutableList<String>, vararg steps: () -> Unit) =
        SettingsPagePlan(steps.toList()) { log += "finish" }

    fun completingRunsEveryStepInOrderThenFinishExactlyOnce() {
        val log = ArrayList<String>()
        val plan = plan(log, { log += "shell" }, { log += "body" })
        plan.complete()
        assertEquals(listOf("shell", "body", "finish"), log)
        assertTrue(plan.isComplete)
        plan.complete()
        assertEquals("finish is not repeated", 3, log.size)
    }

    fun eachAdvanceIsOneBoundedUnitAndFinishIsItsOwnUnit() {
        val log = ArrayList<String>()
        val plan = plan(log, { log += "a" }, { log += "b" }, { log += "c" })
        assertFalse(plan.advance()); assertEquals(listOf("a"), log)
        assertFalse(plan.advance()); assertEquals(listOf("a", "b"), log)
        assertFalse("the last step does not also finish the page", plan.advance()); assertEquals(listOf("a", "b", "c"), log)
        assertTrue(plan.advance()); assertEquals(listOf("a", "b", "c", "finish"), log)
        assertEquals(4, plan.stepNanos.size)
        assertEquals(3, plan.executed)
    }

    fun stepsRegisteredWithDeferStepRunRightAfterTheCurrentStepInRegistrationOrder() {
        val log = ArrayList<String>()
        val plan = plan(log,
            { log += "shell"; deferStep { log += "c1" }; deferStep { log += "c2" }; deferStep { log += "c3" } },
            { log += "tail" })
        plan.complete()
        // 与未拆分前把分类内联在壳后面逐语句一致，且排在其它初始步骤之前。
        assertEquals(listOf("shell", "c1", "c2", "c3", "tail", "finish"), log)
    }

    fun deferredStepsMayDeferAgainAndKeepDepthFirstOrder() {
        val log = ArrayList<String>()
        val plan = plan(log, { log += "a"; deferStep { log += "b"; deferStep { log += "b1" } }; deferStep { log += "c" } })
        plan.complete()
        assertEquals(listOf("a", "b", "b1", "c", "finish"), log)
    }

    fun deferStepOutsideAPlanRunsInlineSoLegacyCallersKeepWorking() {
        val log = ArrayList<String>()
        deferStep { log += "inline" }
        assertEquals(listOf("inline"), log)
    }

    fun interruptedPlanKeepsItsCursorAndResumesWhereItStopped() {
        val log = ArrayList<String>()
        val plan = plan(log, { log += "shell"; deferStep { log += "x" }; deferStep { log += "y" } })
        plan.advance()
        assertEquals(listOf("shell"), log)
        assertEquals(2, plan.remaining)
        // 打断（预建被取消）期间什么都不会发生；之后同步补完只跑剩余部分。
        plan.complete()
        assertEquals(listOf("shell", "x", "y", "finish"), log)
    }

    fun totalStepsAreBounded() {
        assertThrows(IllegalArgumentException::class.java) { SettingsPagePlan(emptyList()) {} }
        assertThrows(IllegalArgumentException::class.java) {
            SettingsPagePlan(List(SettingsPagePlan.MAX_STEPS + 1) { {} }) {}
        }
        // 推迟登记也计入上限：16 个初始步骤再登记一个就越界。
        val plan = SettingsPagePlan(List(SettingsPagePlan.MAX_STEPS) { index -> { if (index == 0) deferStep {} } }) {}
        assertThrows(IllegalStateException::class.java) { plan.advance() }
        assertEquals(SettingsPagePlan.MAX_STEPS + 1, SettingsPagePlan.MAX_UNITS)
    }

    fun aFailingStepPoisonsThePlanAndNeverFinishesAHalfBuiltPage() {
        val log = ArrayList<String>()
        val plan = plan(log, { log += "a" }, { throw IllegalStateException("boom") }, { log += "never" })
        plan.advance()
        assertThrows(IllegalStateException::class.java) { plan.advance() }
        assertFalse(plan.isComplete)
        assertThrows("failed plans stay failed", IllegalStateException::class.java) { plan.advance() }
        assertEquals(listOf("a"), log)
    }

    fun aFailingFinishAlsoPoisonsThePlan() {
        val plan = SettingsPagePlan(listOf({})) { throw IllegalStateException("finish failed") }
        plan.advance()
        assertThrows(IllegalStateException::class.java) { plan.advance() }
        assertFalse(plan.isComplete)
        assertThrows(IllegalStateException::class.java) { plan.complete() }
    }

    fun stepsCannotReenterTheirOwnPlan() {
        lateinit var plan: SettingsPagePlan
        plan = SettingsPagePlan(listOf({ plan.advance() })) {}
        assertThrows(IllegalStateException::class.java) { plan.advance() }
    }

    fun unitTimesAreRecordedForEveryUnit() {
        var now = 0L
        val plan = SettingsPagePlan(listOf({ now += 5 }, { now += 7 })) { now += 3 }
        plan.complete()
        // 注入时钟的 advance 参数用默认值时无法断言具体数值，这里只验证数量与非负。
        assertEquals(3, plan.stepNanos.size)
        assertTrue(plan.stepNanos.all { it >= 0 })
        val timed = SettingsPagePlan(listOf({}, {})) {}
        val ticks = longArrayOf(10, 25, 40, 70, 100, 130).iterator()
        while (!timed.advance { ticks.next() }) Unit
        assertEquals(listOf(15L, 30L, 30L), timed.stepNanos)
    }

    fun nestedDeferralOfTheSplitCardsKeepsTheSourceOrderWhenCompletedOrInterrupted() {
        // 实验性功能卡的形状：壳登记外观两段和兼容性子卡；兼容性子卡在自己的步骤里再登记三段。
        fun build(log: MutableList<String>) = plan(log,
            {
                log += "shell"
                deferStep { log += "appearance-1" }
                deferStep { log += "appearance-2" }
                deferStep {
                    log += "compat-shell"
                    deferStep { log += "compat-1" }
                    deferStep { log += "compat-2" }
                    deferStep { log += "compat-3" }
                }
            },
            { log += "log-card" })
        val expected = listOf("shell", "appearance-1", "appearance-2", "compat-shell", "compat-1", "compat-2", "compat-3", "log-card", "finish")
        val completed = ArrayList<String>()
        val plan = build(completed)
        plan.complete()
        assertEquals(expected, completed)
        assertEquals(8, plan.executed)
        assertEquals("every step plus the finish unit", 9, plan.stepNanos.size)

        // 预建每帧一个单元，任何位置被打断后同步补完，结果与一口气补完相同。
        for (pauseAfter in 0..expected.size) {
            val log = ArrayList<String>()
            val interrupted = build(log)
            var advances = 0
            while (advances < pauseAfter && !interrupted.advance()) advances++
            interrupted.complete()
            assertEquals("interrupted after $pauseAfter units", expected, log)
        }
    }

    fun anyNestedStepTreeRunsInTextualOrderWithOneUnitPerStepPlusFinish() {
        class Tree(val name: String, val children: List<Tree>)
        fun generate(random: Random, budget: IntArray, prefix: String, depth: Int): Tree {
            val children = ArrayList<Tree>()
            if (depth < 3) repeat(random.nextInt(4)) { if (budget[0] > 0) { budget[0]--; children += generate(random, budget, "$prefix.${children.size}", depth + 1) } }
            return Tree(prefix, children)
        }
        fun preorder(tree: Tree): List<String> = listOf(tree.name) + tree.children.flatMap(::preorder)
        fun count(tree: Tree): Int = 1 + tree.children.sumOf(::count)
        fun step(tree: Tree, log: MutableList<String>): () -> Unit = {
            log += tree.name
            tree.children.forEach { child -> deferStep(step(child, log)) }
        }
        for (seed in 0L until 300L) {
            val random = Random(seed)
            val rootCount = 1 + random.nextInt(3)
            val budget = intArrayOf(SettingsPagePlan.MAX_STEPS - rootCount)
            val roots = List(rootCount) { generate(random, budget, "s$it", 0) }
            val total = roots.sumOf(::count)
            assertTrue("seed $seed stays within the bound", total <= SettingsPagePlan.MAX_STEPS)
            val expected = roots.flatMap(::preorder)
            for (mode in 0..2) {
                val log = ArrayList<String>()
                val plan = SettingsPagePlan(roots.map { step(it, log) }) { log += "finish" }
                when (mode) {
                    0 -> plan.complete()
                    1 -> while (!plan.advance()) Unit
                    else -> { repeat(random.nextInt(total + 1)) { plan.advance() }; plan.complete() }
                }
                assertEquals("seed $seed mode $mode", expected + "finish", log)
                assertEquals("seed $seed steps", total, plan.executed)
                assertEquals("seed $seed units", total + 1, plan.stepNanos.size)
                assertTrue(plan.isComplete)
            }
        }
    }

    fun deferredStepsReadStateWhenTheyRunNotWhenTheyWereRegistered() {
        var stored = "initial"
        val viaFunction = ArrayList<String>()
        val viaSnapshot = ArrayList<String>()
        // 与 enhancementSettingsCard / experimentalFeaturesCard 的做法相同：壳拿到读取函数，推迟的步骤执行时才调用。
        fun cardTakingReader(uiSettings: () -> String) = deferStep { viaFunction += uiSettings() }
        // 反例：壳构建时读好的快照，推迟的步骤只能看到过期值。
        fun cardTakingSnapshot(uiSettings: String) = deferStep { viaSnapshot += uiSettings }
        val plan = SettingsPagePlan(listOf({ cardTakingReader { stored }; cardTakingSnapshot(stored) })) {}
        plan.advance()
        stored = "written between frames"
        plan.complete()
        assertEquals(listOf("written between frames"), viaFunction)
        assertEquals(listOf("initial"), viaSnapshot)
    }

    fun aFailingDeferredStepDeepInTheTreeStopsEverythingAfterItAndNeverFinishes() {
        val log = ArrayList<String>()
        val plan = plan(log,
            { log += "shell"; deferStep { log += "a"; deferStep { throw IllegalStateException("boom") }; deferStep { log += "a2" } } },
            { log += "tail" })
        plan.advance(); plan.advance()
        assertThrows(IllegalStateException::class.java) { plan.advance() }
        assertFalse(plan.isComplete)
        assertThrows(IllegalStateException::class.java) { plan.complete() }
        assertEquals("siblings, later steps and the finish unit never run", listOf("shell", "a"), log)
    }

    fun nestedRegistrationsCountAgainstTheSameBound() {
        val plan = SettingsPagePlan(listOf({ deferStep { repeat(SettingsPagePlan.MAX_STEPS) { deferStep {} } } })) {}
        plan.advance()
        assertThrows(IllegalStateException::class.java) { plan.advance() }
        // 恰好填满上限是允许的：1 个初始步骤 + 1 个推迟步骤 + 14 个嵌套步骤。
        val exact = SettingsPagePlan(listOf({ deferStep { repeat(SettingsPagePlan.MAX_STEPS - 2) { deferStep {} } } })) {}
        exact.complete()
        assertEquals(SettingsPagePlan.MAX_STEPS, exact.executed)
        assertEquals(SettingsPagePlan.MAX_UNITS, exact.stepNanos.size)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (SettingsPagePlanCases) -> Unit) {
            val s = SettingsPagePlanCases()
            block(s)
        }
        runIsolated { s -> s.completingRunsEveryStepInOrderThenFinishExactlyOnce() }
        runIsolated { s -> s.eachAdvanceIsOneBoundedUnitAndFinishIsItsOwnUnit() }
        runIsolated { s -> s.stepsRegisteredWithDeferStepRunRightAfterTheCurrentStepInRegistrationOrder() }
        runIsolated { s -> s.deferredStepsMayDeferAgainAndKeepDepthFirstOrder() }
        runIsolated { s -> s.deferStepOutsideAPlanRunsInlineSoLegacyCallersKeepWorking() }
        runIsolated { s -> s.interruptedPlanKeepsItsCursorAndResumesWhereItStopped() }
        runIsolated { s -> s.totalStepsAreBounded() }
        runIsolated { s -> s.aFailingStepPoisonsThePlanAndNeverFinishesAHalfBuiltPage() }
        runIsolated { s -> s.aFailingFinishAlsoPoisonsThePlan() }
        runIsolated { s -> s.stepsCannotReenterTheirOwnPlan() }
        runIsolated { s -> s.unitTimesAreRecordedForEveryUnit() }
        runIsolated { s -> s.nestedDeferralOfTheSplitCardsKeepsTheSourceOrderWhenCompletedOrInterrupted() }
        runIsolated { s -> s.anyNestedStepTreeRunsInTextualOrderWithOneUnitPerStepPlusFinish() }
        runIsolated { s -> s.deferredStepsReadStateWhenTheyRunNotWhenTheyWereRegistered() }
        runIsolated { s -> s.aFailingDeferredStepDeepInTheTreeStopsEverythingAfterItAndNeverFinishes() }
        runIsolated { s -> s.nestedRegistrationsCountAgainstTheSameBound() }
    }
}

private class SettingsPageScrollRestoreCases {
    fun firstNavigationToALazyPageKeepsItsSavedOffset() {
        val restore = SettingsPageScrollRestore(listOf(0, 0, 500, 0, 0))
        restore.userInput(0)
        assertEquals(500, restore.savedOffset(2, 0))
        val ticket = requireNotNull(restore.prepare(2))
        assertEquals(500, restore.savedOffset(2, 0))
        assertEquals(500, restore.consume(ticket))
        assertEquals(520, restore.savedOffset(2, 520))
        assertNull(restore.prepare(2))
    }

    fun inputOnTheTargetPageCancelsOldRestoreAndPreservesNewScroll() {
        val restore = SettingsPageScrollRestore(listOf(300))
        val ticket = requireNotNull(restore.prepare(0))
        restore.userInput(0)
        assertNull(restore.consume(ticket))
        assertEquals(27, restore.savedOffset(0, 27))
    }

    fun replacementAndCloseInvalidateLayoutTickets() {
        val restore = SettingsPageScrollRestore(listOf(100))
        val first = requireNotNull(restore.prepare(0))
        val replacement = requireNotNull(restore.prepare(0))
        assertNull(restore.consume(first))
        restore.close()
        assertNull(restore.consume(replacement))
        assertNull(restore.prepare(0))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (SettingsPageScrollRestoreCases) -> Unit) {
            val s = SettingsPageScrollRestoreCases()
            block(s)
        }
        runIsolated { s -> s.firstNavigationToALazyPageKeepsItsSavedOffset() }
        runIsolated { s -> s.inputOnTheTargetPageCancelsOldRestoreAndPreservesNewScroll() }
        runIsolated { s -> s.replacementAndCloseInvalidateLayoutTickets() }
    }
}

private class SettingsSearchMatcherCases {

    private val items = listOf(
        SettingsSearchItem("portrait", "竖屏内容过滤", "选择需要过滤的内容", "进阶设置"),
        SettingsSearchItem("backup", "设置备份与恢复", "导出或导入备份", "基础设置"),
        SettingsSearchItem("duplicate", "竖屏内容过滤", "重复入口", "实验性功能")
    )

    fun `multiple tokens must all match localized text`() {
        assertEquals(
            listOf("portrait"),
            SettingsSearchMatcher.search("竖屏 过滤", items).map { it.key }
        )
        assertTrue(SettingsSearchMatcher.search("竖屏 备份", items).isEmpty())
    }

    fun `title matches rank ahead of detail matches and duplicates collapse`() {
        val results = SettingsSearchMatcher.search("设置", items)

        assertEquals("backup", results.first().key)
        assertEquals(results.map { it.title }.distinct(), results.map { it.title })
    }

    fun `blank query and zero limit return no result`() {
        assertTrue(SettingsSearchMatcher.search("  ", items).isEmpty())
        assertTrue(SettingsSearchMatcher.search("设置", items, limit = 0).isEmpty())
    }

    fun `search match exposes title and section keyword ranges`() {
        val match = SettingsSearchMatcher.searchMatches("竖屏 进阶", items).single()

        assertEquals(
            listOf("竖屏"),
            match.titleRanges.map { match.item.title.substring(it) }
        )
        assertEquals(
            listOf("进阶"),
            match.sectionRanges.map { match.item.section.substring(it) }
        )
        assertTrue(match.detailRanges.isEmpty())
    }

    fun `search match exposes every separated detail keyword range`() {
        val match = SettingsSearchMatcher.searchMatches("选择 的", items)
            .single { it.item.key == "portrait" }

        assertEquals(
            listOf("选择", "的"),
            match.detailRanges.map { match.item.detail.substring(it) }
        )
        assertTrue(match.titleRanges.isEmpty())
        assertTrue(match.sectionRanges.isEmpty())
    }

    fun verifyAllCases() {
        fun runIsolated(block: (SettingsSearchMatcherCases) -> Unit) {
            val s = SettingsSearchMatcherCases()
            block(s)
        }
        runIsolated { s -> s.`multiple tokens must all match localized text`() }
        runIsolated { s -> s.`title matches rank ahead of detail matches and duplicates collapse`() }
        runIsolated { s -> s.`blank query and zero limit return no result`() }
        runIsolated { s -> s.`search match exposes title and section keyword ranges`() }
        runIsolated { s -> s.`search match exposes every separated detail keyword range`() }
    }
}
