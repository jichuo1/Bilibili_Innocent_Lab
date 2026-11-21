package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsActionCatalogTest {
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
        listOf(FeaturePreferences.PLAYER_BUFFER_MEMORY_MB, FeaturePreferences.PLAYER_DEFAULT_SPEED_PERCENT,
            FeaturePreferences.COMMENT_FILTER_KEYWORDS, FeaturePreferences.HOST_BACKGROUND_PRESET).forEach { key ->
            assertFalse(descriptors.getValue(key).directToggle)
            assertFalse(descriptors.getValue(key).favoriteEligible)
        }
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (SettingsActionCatalogTest) -> Unit) {
            val s = SettingsActionCatalogTest()
            block(s)
        }
        runIsolated { s -> s.allRecommendationPanelOptionsCanNavigateBeforeThePageExists() }
        runIsolated { s -> s.navigationMetadataIsAvailableBeforeAnyPageOrControlExists() }
        runIsolated { s -> s.complexNonBooleanControlsRemainNavigationInsteadOfBooleanWrites() }
    
        SettingsExpansionStateCases().verifyAllCases()
        SettingsFavoritesPolicyCases().verifyAllCases()
        SettingsPagePlanCases().verifyAllCases()
        SettingsPageScrollRestoreCases().verifyAllCases()
        SettingsSearchMatcherCases().verifyAllCases()
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
        fun generate(random: java.util.Random, budget: IntArray, prefix: String, depth: Int): Tree {
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
            val random = java.util.Random(seed)
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
