package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.content.SharedPreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.AiDeclaredVideoPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentScanEntry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentSnapshotCodec
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PublishTimePoint
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.HostConfigChannel
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart.RestartRoute
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart.RestartRoutePolicy
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart.RootRestartAccess
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart.ShizukuAvailability
import java.lang.reflect.Proxy
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoRootSetupPolicyTest {
    fun `LSPatch uses its own manager without an ineffective NPatch opt in`() {
        val policy = NoRootSetupPolicy.forChannel(HostConfigChannel.LSPATCH)
        assertTrue(policy.lspatch)
        assertFalse(policy.needsOptIn)
        assertEquals("org.lsposed.lspatch", policy.managerPackage)
    }

    fun `NPatch and setup before patching retain explicit opt in`() {
        listOf(HostConfigChannel.NPATCH, HostConfigChannel.STANDARD).forEach { channel ->
            val policy = NoRootSetupPolicy.forChannel(channel)
            assertFalse(policy.lspatch)
            assertTrue(policy.needsOptIn)
            assertEquals("top.nkbe.npatch", policy.managerPackage)
        }
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (NoRootSetupPolicyTest) -> Unit) {
            val s = NoRootSetupPolicyTest()
            block(s)
        }
        runIsolated { s -> s.`LSPatch uses its own manager without an ineffective NPatch opt in`() }
        runIsolated { s -> s.`NPatch and setup before patching retain explicit opt in`() }
    
        PortraitContentFilterSelectionCases().verifyAllCases()
        PublishTimeDateDraftCases().verifyAllCases()
        RecommendationBlocklistDraftCases().verifyAllCases()
        RestartEntryPolicyCases().verifyAllCases()
        RuntimeSettingsNavigationCatalogCases().allSevenRuntimeControllersAreNavigationOnlyAndNeverGainBooleanAuthority()
    }
}

private class PortraitContentFilterSelectionCases {

    fun `catalog keeps thirteen unique existing preference keys`() {
        val options = PortraitContentFilterCatalog.options

        assertEquals(13, options.size)
        assertEquals(13, options.map { it.preferenceKey }.distinct().size)
        assertEquals(
            mapOf(
                PortraitContentFilterGroup.HOME to 1,
                PortraitContentFilterGroup.STORY to 7,
                PortraitContentFilterGroup.SERIES to 5
            ),
            options.groupingBy { it.group }.eachCount()
        )
    }

    fun `all series covers children without erasing their draft values`() {
        val movieKey = FeaturePreferences.REMOVE_STORY_MOVIES
        val parentKey = FeaturePreferences.REMOVE_STORY_BANGUMI
        val movie = PortraitContentFilterCatalog.options.first { it.preferenceKey == movieKey }
        val draft = PortraitContentFilterDraft(mapOf(movieKey to true))

        draft[parentKey] = true
        assertTrue(draft.isCovered(movie))
        assertTrue(draft[movieKey])

        draft[parentKey] = false
        assertFalse(draft.isCovered(movie))
        assertTrue(draft[movieKey])
    }

    fun `changed values contain only explicit draft differences`() {
        val adsKey = FeaturePreferences.REMOVE_STORY_ADS
        val liveKey = FeaturePreferences.REMOVE_STORY_LIVE
        val draft = PortraitContentFilterDraft(mapOf(adsKey to true, liveKey to false))

        draft[adsKey] = false
        draft[liveKey] = true

        assertEquals(mapOf(adsKey to false, liveKey to true), draft.changedValues())
        assertEquals(1, draft.selectedCount())
    }

    fun verifyAllCases() {
        fun runIsolated(block: (PortraitContentFilterSelectionCases) -> Unit) {
            val s = PortraitContentFilterSelectionCases()
            block(s)
        }
        runIsolated { s -> s.`catalog keeps thirteen unique existing preference keys`() }
        runIsolated { s -> s.`all series covers children without erasing their draft values`() }
        runIsolated { s -> s.`changed values contain only explicit draft differences`() }
    }
}

private class PublishTimeDateDraftCases {
    private fun draft(year: String, month: String, day: String) = PublishTimeDateDraft().apply {
        yearText = year; monthText = month; dayText = day
    }

    fun `an unset date stays unset instead of defaulting to today`() {
        val value = PublishTimeDateDraft()
        assertNull(value.toDate())
        assertEquals(listOf("", "", ""), listOf(value.yearText, value.monthText, value.dayText))
    }
    fun `saved dates initialize all three fields and round trip`() {
        val date = LocalDate.of(2026, 10, 21)
        val value = PublishTimeDateDraft(date)
        assertEquals(listOf("2026", "10", "21"), listOf(value.yearText, value.monthText, value.dayText))
        assertEquals(date, value.toDate())
    }
    fun `incomplete and invalid input is preserved through validation and later editing`() {
        val value = draft("202", "02", "30")
        assertNull(value.toDate())
        value.yearText = "2024"
        assertNull(value.toDate())
        assertEquals(listOf("2024", "02", "30"), listOf(value.yearText, value.monthText, value.dayText))
        value.dayText = "29"
        assertEquals(LocalDate.of(2024, 2, 29), value.toDate())
        assertEquals("02", value.monthText)
    }
    fun `leap days and month lengths are validated without clamping`() {
        assertEquals(LocalDate.of(2024, 2, 29), draft("2024", "2", "29").toDate())
        for ((year, month, day) in listOf(Triple("2023", "2", "29"), Triple("2100", "2", "29"),
                Triple("2026", "4", "31"), Triple("2026", "0", "1"), Triple("2026", "13", "1"),
                Triple("2026", "1", "0"), Triple("2026", "1", "32"))) {
            val value = draft(year, month, day)
            assertNull(value.toDate())
            assertEquals(day, value.dayText)
        }
    }
    fun `date limits match the persisted publish time protocol`() {
        val first = LocalDate.of(PublishTimePoint.MIN_YEAR, 1, 1)
        val last = LocalDate.of(PublishTimePoint.MAX_YEAR, 12, 31)
        assertEquals(first, PublishTimeDateDraft(first).toDate())
        assertEquals(last, PublishTimeDateDraft(last).toDate())
        assertNull(draft((PublishTimePoint.MIN_YEAR - 1).toString(), "12", "31").toDate())
        assertNull(draft((PublishTimePoint.MAX_YEAR + 1).toString(), "1", "1").toDate())
    }
    fun `non integer fields cannot silently become a date`() {
        for (value in listOf(draft("+2026", "1", "1"), draft("2026 ", "1", "1"),
                draft("2026", "1.0", "1"), draft("2026", "1", "-1"), draft("2026", "", "1")))
            assertNull(value.toDate())
    }
    fun `explicit date replacement updates all fields together`() {
        val value = draft("202", "", "31")
        value.setDate(LocalDate.of(2026, 10, 21))
        assertEquals(LocalDate.of(2026, 10, 21), value.toDate())
        assertEquals(listOf("2026", "10", "21"), listOf(value.yearText, value.monthText, value.dayText))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (PublishTimeDateDraftCases) -> Unit) {
            val s = PublishTimeDateDraftCases()
            block(s)
        }
        runIsolated { s -> s.`an unset date stays unset instead of defaulting to today`() }
        runIsolated { s -> s.`saved dates initialize all three fields and round trip`() }
        runIsolated { s -> s.`incomplete and invalid input is preserved through validation and later editing`() }
        runIsolated { s -> s.`leap days and month lengths are validated without clamping`() }
        runIsolated { s -> s.`date limits match the persisted publish time protocol`() }
        runIsolated { s -> s.`non integer fields cannot silently become a date`() }
        runIsolated { s -> s.`explicit date replacement updates all fields together`() }
    }
}

private class RecommendationBlocklistDraftCases {
    private fun picks(token: String? = "event-1", id: String = "8318") = MineComponentSnapshot(
        targetPackage = "tv.danmaku.bili", processName = "tv.danmaku.bili",
        surface = MineComponentSnapshotCodec.SURFACE_SECTION_PICKS,
        generatedAt = 1L, capabilities = emptySet(),
        entries = listOf(checkNotNull(MineComponentScanEntry.create("section", "鬼畜", id, null, true))
            .copy(selectionToken = token))
    )

    fun removalPreservesOtherNamesIdsAndAuthors() {
        val prefs = MemoryPreferences("8318,东方 Project,163", "UP A,123")
        val draft = RecommendationBlocklistDraft("8318,东方 Project,163", "UP A,123", listOf(picks()), "")
        draft.setSelected(RecommendationBlockRule(RecommendationBlockKind.TAG, "8318"), false)
        draft.setSelected(RecommendationBlockRule(RecommendationBlockKind.AUTHOR, "123"), false)
        assertTrue(draft.save(prefs.instance))
        assertEquals("东方 project,163", prefs.tags())
        assertEquals("up a", prefs.authors())
    }

    fun cancelledDraftDoesNotWriteOrAcknowledge() {
        val prefs = MemoryPreferences("163", "UP A")
        val draft = RecommendationBlocklistDraft("163", "UP A", listOf(picks()), "")
        draft.rows.forEach { draft.setSelected(it.rule, false) }
        assertEquals("163", prefs.tags())
        assertEquals("UP A", prefs.authors())
        assertNull(prefs.values[RecommendationBlocklistDraft.REVIEWED_EVENTS_KEY])
    }

    fun rejectedPickDoesNotReturnWhenAnotherPickUpdatesTheSnapshot() {
        val prefs = MemoryPreferences("", "")
        val first = RecommendationBlocklistDraft("", "", listOf(picks()), "")
        first.rows.forEach { first.setSelected(it.rule, false) }
        assertTrue(first.save(prefs.instance))
        val updated = picks().copy(generatedAt = 2L, entries = picks().entries + picks("event-2", "163").entries)
        val reopened = RecommendationBlocklistDraft("", "", listOf(updated), prefs.reviewed())
        assertEquals(listOf("163"), reopened.rows.map { it.rule.value })
    }

    fun aNewExplicitPickOfTheSameTagCanBeConfirmedAgain() {
        val first = RecommendationBlocklistDraft("", "", listOf(picks()), "")
        val reopened = RecommendationBlocklistDraft("", "", listOf(picks("new-session-event")), first.acknowledgedValue())
        assertEquals("8318", reopened.selectedValue(RecommendationBlockKind.TAG))
        assertTrue(reopened.rows.single().pending)
    }

    fun reviewedSavedPickRemainsEditableButNotPending() {
        val first = RecommendationBlocklistDraft("", "", listOf(picks()), "")
        val reopened = RecommendationBlocklistDraft("8318", "", listOf(picks()), first.acknowledgedValue())
        assertFalse(reopened.rows.single().pending)
        assertEquals("鬼畜 (8318)", reopened.rows.single().label)
    }

    fun legacySnapshotsCanBeDismissedWithoutBlockingNewEvents() {
        val legacy = RecommendationBlocklistDraft("", "", listOf(picks(null)), "")
        assertTrue(RecommendationBlocklistDraft("", "", listOf(picks(null).copy(generatedAt = 20)), legacy.acknowledgedValue()).rows.isEmpty())
        assertEquals(1, RecommendationBlocklistDraft("", "", listOf(picks()), legacy.acknowledgedValue()).rows.size)
    }

    fun missingOrUnrelatedSnapshotsNeverClearManualRules() {
        val draft = RecommendationBlocklistDraft("东方 Project,8318", "UP A", listOf(picks().copy(surface = "mine")), "")
        assertEquals("东方 project,8318", draft.selectedValue(RecommendationBlockKind.TAG))
        assertEquals("up a", draft.selectedValue(RecommendationBlockKind.AUTHOR))
        assertTrue(draft.rows.none { it.pending })
    }

    fun failedCommitDoesNotAcknowledgeOrLoseLists() {
        val prefs = MemoryPreferences("163", "up a", fail = true)
        val draft = RecommendationBlocklistDraft("163", "up a", listOf(picks()), "")
        assertFalse(draft.save(prefs.instance))
        assertEquals("163", prefs.tags())
        assertEquals("", prefs.reviewed())
    }

    fun concurrentEditIsPreserved() {
        val prefs = MemoryPreferences("new tag", "up a")
        val draft = RecommendationBlocklistDraft("163", "up a", listOf(picks()), "")
        assertFalse(draft.save(prefs.instance))
        assertEquals("new tag", prefs.tags())
    }

    fun selectionIdentitySurvivesSnapshotEncodingAndOldPayloadsRemainReadable() {
        val token = picks().entries.single()
        assertEquals(token, MineComponentScanEntry.fromJsonOrNull(token.toJson()))
        val old = token.toJson().apply { remove("selectionToken") }
        assertNull(checkNotNull(MineComponentScanEntry.fromJsonOrNull(old)).selectionToken)
        assertNull(MineComponentScanEntry.fromJsonOrNull(old.put("selectionToken", "x".repeat(65))))
    }

    fun authorPicksAreReviewedSeparatelyAndNamesKeepTheirSpaces() {
        val author = picks().copy(surface = MineComponentSnapshotCodec.SURFACE_AUTHOR_PICKS,
            entries = listOf(checkNotNull(MineComponentScanEntry.create("author", "UP A", "UP A", null, true))
                .copy(selectionToken = "author-event")))
        val prefs = MemoryPreferences("8318", "")
        val draft = RecommendationBlocklistDraft("8318", "", listOf(author), "")
        assertEquals("up a", draft.selectedValue(RecommendationBlockKind.AUTHOR))
        draft.setSelected(RecommendationBlockRule(RecommendationBlockKind.AUTHOR, "up a"), false)
        assertTrue(draft.save(prefs.instance))
        assertEquals("8318", prefs.tags())
        assertTrue(RecommendationBlocklistDraft("", "", listOf(author), prefs.reviewed()).rows.isEmpty())
    }

    fun boundedReviewHistoryAlwaysKeepsTheCurrentSnapshotAcknowledged() {
        val first = RecommendationBlocklistDraft("", "", listOf(picks()), "")
        val reviewed = first.acknowledgedValue() + "\n" + (1..512).joinToString("\n") { "older-$it" }
        val draft = RecommendationBlocklistDraft("", "", listOf(picks(), picks("new-event", "163")), reviewed)
        val saved = draft.acknowledgedValue()
        assertEquals(512, saved.lines().size)
        assertTrue(RecommendationBlocklistDraft("", "", listOf(picks()), saved).rows.isEmpty())
    }

    // ===== 自动确认新增屏蔽标签 =====

    fun autoConfirmIsOffUntilItIsTurnedOnAndThenSurvivesAReread() {
        val prefs = MemoryPreferences("", "")
        assertFalse(RecommendationBlocklistDraft.isAutoConfirmEnabled(prefs.instance))
        assertFalse(RecommendationBlocklistDraft.autoConfirm(prefs.instance, listOf(picks())))
        assertEquals("", prefs.tags())

        assertTrue(RecommendationBlocklistDraft.setAutoConfirmEnabled(prefs.instance, true))
        assertTrue(RecommendationBlocklistDraft.isAutoConfirmEnabled(prefs.instance))
    }

    /** 开关打开后等价于"打开面板直接点确定"：待确认项进名单，且不再是待确认。 */
    fun autoConfirmMergesPendingPicksAndMarksThemReviewed() {
        val prefs = MemoryPreferences("163", "UP A")
        RecommendationBlocklistDraft.setAutoConfirmEnabled(prefs.instance, true)
        assertTrue(RecommendationBlocklistDraft.autoConfirm(prefs.instance, listOf(picks())))
        assertEquals("163,8318", prefs.tags())
        // 已保存项不会因为这次自动合并而丢。
        assertEquals("up a", prefs.authors())
        assertTrue(RecommendationBlocklistDraft.of(prefs.instance, listOf(picks()))
            .rows.none(RecommendationBlockRow::pending))
    }

    /** 没有待确认项时不写盘——否则模块每次前台都会打一次 commit。 */
    fun autoConfirmDoesNothingWhenThereIsNothingNew() {
        val prefs = MemoryPreferences("", "")
        RecommendationBlocklistDraft.setAutoConfirmEnabled(prefs.instance, true)
        assertTrue(RecommendationBlocklistDraft.autoConfirm(prefs.instance, listOf(picks())))
        val reviewedAfterFirst = prefs.reviewed()
        assertFalse(RecommendationBlocklistDraft.autoConfirm(prefs.instance, listOf(picks())))
        assertEquals(reviewedAfterFirst, prefs.reviewed())
        assertFalse(RecommendationBlocklistDraft.autoConfirm(prefs.instance, emptyList()))
    }

    /** 用户在面板里手动撤销过的条目，不能被自动确认重新拉回来。 */
    fun autoConfirmDoesNotResurrectAnEntryTheUserAlreadyRemoved() {
        val prefs = MemoryPreferences("", "")
        val draft = RecommendationBlocklistDraft("", "", listOf(picks()), "")
        draft.rows.forEach { draft.setSelected(it.rule, false) }
        assertTrue(draft.save(prefs.instance))
        assertEquals("", prefs.tags())

        RecommendationBlocklistDraft.setAutoConfirmEnabled(prefs.instance, true)
        assertFalse(RecommendationBlocklistDraft.autoConfirm(prefs.instance, listOf(picks())))
        assertEquals("", prefs.tags())
        // 但用户**再点一次**（新的 selectionToken）就该重新出现。
        assertTrue(RecommendationBlocklistDraft.autoConfirm(prefs.instance, listOf(picks("event-2"))))
        assertEquals("8318", prefs.tags())
    }

    fun autoConfirmReportsFailureWhenTheStoreRejectsTheCommit() {
        val prefs = MemoryPreferences("", "", fail = true)
        prefs.values[RecommendationBlocklistDraft.AUTO_CONFIRM_KEY] = true
        assertFalse(RecommendationBlocklistDraft.autoConfirm(prefs.instance, listOf(picks())))
    }

    // ===== AI 生成声明：强力模式记下的 UP =====

    private fun authorPick(name: String, token: String, aiOrigin: Boolean) = checkNotNull(
        MineComponentScanEntry.create("author", name, name,
            if (aiOrigin) AiDeclaredVideoPolicy.AUTHOR_PICK_ORIGIN_URI else null, true)
    ).copy(selectionToken = token)

    private fun authorPicks(vararg entries: MineComponentScanEntry) = picks().copy(
        surface = MineComponentSnapshotCodec.SURFACE_AUTHOR_PICKS, entries = entries.toList()
    )

    private fun MemoryPreferences.enableAiStrongMode(main: Boolean = true) {
        values[FeaturePreferences.BLOCK_AI_DECLARED_VIDEOS] = main
        values[FeaturePreferences.BLOCK_AI_DECLARED_VIDEOS_STRONG_MODE] = true
    }

    /** 没开「自动确认」也要并入，但**只**并入强力模式来源；别的点选仍待用户确认。 */
    fun strongModeMergesOnlyAiOriginAuthorsAndLeavesOtherPicksPending() {
        val prefs = MemoryPreferences("163", "UP A")
        prefs.enableAiStrongMode()
        val snapshots = listOf(picks(), authorPicks(
            authorPick("AI UP", "ai-1", aiOrigin = true),
            authorPick("Manual UP", "manual-1", aiOrigin = false)
        ))
        assertFalse(RecommendationBlocklistDraft.isAutoConfirmEnabled(prefs.instance))
        assertTrue(RecommendationBlocklistDraft.needsBackgroundMerge(prefs.instance))
        assertTrue(RecommendationBlocklistDraft.autoConfirm(prefs.instance, snapshots))
        assertEquals("up a,ai up", prefs.authors())
        assertEquals("163", prefs.tags())
        val reopened = RecommendationBlocklistDraft.of(prefs.instance, snapshots)
        assertEquals(setOf("8318", "manual up"), reopened.rows.filter { it.pending }.map { it.rule.value }.toSet())
        // 再跑一次不写盘：强力模式来源已经处理过了。
        val reviewed = prefs.reviewed()
        assertFalse(RecommendationBlocklistDraft.autoConfirm(prefs.instance, snapshots))
        assertEquals(reviewed, prefs.reviewed())
    }

    /** 强力模式只在总开关也开着时算数，与宿主侧安装条件一致。 */
    fun strongModeWithoutTheMainSwitchDoesNothing() {
        val prefs = MemoryPreferences("", "")
        prefs.enableAiStrongMode(main = false)
        assertFalse(RecommendationBlocklistDraft.needsBackgroundMerge(prefs.instance))
        assertFalse(RecommendationBlocklistDraft.autoConfirm(prefs.instance,
            listOf(authorPicks(authorPick("AI UP", "ai-1", aiOrigin = true)))))
        assertEquals("", prefs.authors())
    }

    /** 用户在面板里移除了强力模式加进来的 UP：同一次记录不会被拉回。 */
    fun aRemovedAiOriginAuthorIsNotMergedAgainFromTheSameRecord() {
        val prefs = MemoryPreferences("", "")
        prefs.enableAiStrongMode()
        val snapshots = listOf(authorPicks(authorPick("AI UP", "ai-1", aiOrigin = true)))
        assertTrue(RecommendationBlocklistDraft.autoConfirm(prefs.instance, snapshots))
        val draft = RecommendationBlocklistDraft.of(prefs.instance, snapshots)
        draft.setSelected(RecommendationBlockRule(RecommendationBlockKind.AUTHOR, "ai up"), false)
        assertTrue(draft.save(prefs.instance))
        assertEquals("", prefs.authors())
        assertFalse(RecommendationBlocklistDraft.autoConfirm(prefs.instance, snapshots))
        assertEquals("", prefs.authors())
    }

    /** 通用自动确认开着时照旧全部并入，强力模式不改变那条路径。 */
    fun generalAutoConfirmStillMergesEverything() {
        val prefs = MemoryPreferences("", "")
        prefs.enableAiStrongMode()
        RecommendationBlocklistDraft.setAutoConfirmEnabled(prefs.instance, true)
        assertTrue(RecommendationBlocklistDraft.autoConfirm(prefs.instance, listOf(authorPicks(
            authorPick("AI UP", "ai-1", aiOrigin = true),
            authorPick("Manual UP", "manual-1", aiOrigin = false)
        ))))
        assertEquals("ai up,manual up", prefs.authors())
    }

    private class MemoryPreferences(tags: String, authors: String, private val fail: Boolean = false) {
        val values = mutableMapOf<String, Any>(
            FeaturePreferences.HOME_RECOMMEND_BLOCKED_TIDS to tags,
            FeaturePreferences.HOME_RECOMMEND_BLOCKED_AUTHORS to authors
        )
        fun tags() = values[FeaturePreferences.HOME_RECOMMEND_BLOCKED_TIDS] as? String
        fun authors() = values[FeaturePreferences.HOME_RECOMMEND_BLOCKED_AUTHORS] as? String
        fun reviewed() = (values[RecommendationBlocklistDraft.REVIEWED_EVENTS_KEY] as? String).orEmpty()
        val instance = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString" -> values[args!![0]] as? String ?: args[1]
                "getBoolean" -> values[args!![0]] as? Boolean ?: args[1]
                "edit" -> editor()
                else -> error(method.name)
            }
        } as SharedPreferences
        private fun editor(): SharedPreferences.Editor {
            val pending = mutableMapOf<String, Any>()
            return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                when (method.name) {
                    "putString" -> { pending[args!![0] as String] = args[1] as String; proxy }
                    "putBoolean" -> { pending[args!![0] as String] = args[1] as Boolean; proxy }
                    "commit" -> { values.putAll(pending); !fail }
                    else -> error(method.name)
                }
            } as SharedPreferences.Editor
        }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (RecommendationBlocklistDraftCases) -> Unit) {
            val s = RecommendationBlocklistDraftCases()
            block(s)
        }
        runIsolated { s -> s.removalPreservesOtherNamesIdsAndAuthors() }
        runIsolated { s -> s.cancelledDraftDoesNotWriteOrAcknowledge() }
        runIsolated { s -> s.rejectedPickDoesNotReturnWhenAnotherPickUpdatesTheSnapshot() }
        runIsolated { s -> s.aNewExplicitPickOfTheSameTagCanBeConfirmedAgain() }
        runIsolated { s -> s.reviewedSavedPickRemainsEditableButNotPending() }
        runIsolated { s -> s.legacySnapshotsCanBeDismissedWithoutBlockingNewEvents() }
        runIsolated { s -> s.missingOrUnrelatedSnapshotsNeverClearManualRules() }
        runIsolated { s -> s.failedCommitDoesNotAcknowledgeOrLoseLists() }
        runIsolated { s -> s.concurrentEditIsPreserved() }
        runIsolated { s -> s.selectionIdentitySurvivesSnapshotEncodingAndOldPayloadsRemainReadable() }
        runIsolated { s -> s.authorPicksAreReviewedSeparatelyAndNamesKeepTheirSpaces() }
        runIsolated { s -> s.boundedReviewHistoryAlwaysKeepsTheCurrentSnapshotAcknowledged() }
        runIsolated { s -> s.autoConfirmIsOffUntilItIsTurnedOnAndThenSurvivesAReread() }
        runIsolated { s -> s.autoConfirmMergesPendingPicksAndMarksThemReviewed() }
        runIsolated { s -> s.autoConfirmDoesNothingWhenThereIsNothingNew() }
        runIsolated { s -> s.autoConfirmDoesNotResurrectAnEntryTheUserAlreadyRemoved() }
        runIsolated { s -> s.autoConfirmReportsFailureWhenTheStoreRejectsTheCommit() }
        runIsolated { s -> s.strongModeMergesOnlyAiOriginAuthorsAndLeavesOtherPicksPending() }
        runIsolated { s -> s.strongModeWithoutTheMainSwitchDoesNothing() }
        runIsolated { s -> s.aRemovedAiOriginAuthorIsNotMergedAgainFromTheSameRecord() }
        runIsolated { s -> s.generalAutoConfirmStillMergesEverything() }
    }
}

private class RestartEntryPolicyCases {
    fun `stopped Shizuku exposes recovery without becoming a restart capability`() {
        val stopped = ShizukuAvailability.Snapshot(ShizukuAvailability.Status.STOPPED)
        assertTrue(canOpenRestartControls(false, RestartRoute.SHIZUKU, stopped))
        assertFalse(stopped.recommended)
        assertEquals(RestartRoute.MANUAL, RestartRoutePolicy.preferred(
            RootRestartAccess.Snapshot(RootRestartAccess.Status.MISSING), stopped))
    }

    fun `unsettled checks never expose privileged controls`() {
        RestartRoute.entries.forEach { route ->
            ShizukuAvailability.Status.entries.forEach { status ->
                assertFalse(canOpenRestartControls(true, route, ShizukuAvailability.Snapshot(status)))
            }
        }
    }

    fun `existing authorization and unavailable service boundaries remain intact`() {
        ShizukuAvailability.Status.entries.forEach { status ->
            val expected = status in setOf(ShizukuAvailability.Status.READY,
                ShizukuAvailability.Status.UNAUTHORIZED, ShizukuAvailability.Status.DENIED,
                ShizukuAvailability.Status.STOPPED)
            assertEquals(expected, canOpenRestartControls(false, RestartRoute.SHIZUKU,
                ShizukuAvailability.Snapshot(status)))
        }
        assertFalse(canOpenRestartControls(false, RestartRoute.SHIZUKU, null))
        assertFalse(canOpenRestartControls(false, null, null))
        assertFalse(canOpenRestartControls(false, RestartRoute.MANUAL, null))
        assertTrue(canOpenRestartControls(false, RestartRoute.ROOT, null))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (RestartEntryPolicyCases) -> Unit) {
            val s = RestartEntryPolicyCases()
            block(s)
        }
        runIsolated { s -> s.`stopped Shizuku exposes recovery without becoming a restart capability`() }
        runIsolated { s -> s.`unsettled checks never expose privileged controls`() }
        runIsolated { s -> s.`existing authorization and unavailable service boundaries remain intact`() }
    }
}

private class RuntimeSettingsNavigationCatalogCases {
    fun allSevenRuntimeControllersAreNavigationOnlyAndNeverGainBooleanAuthority() {
        val descriptors = RuntimeSettingsNavigationCatalog.descriptors()
        assertEquals(7, descriptors.size)
        assertEquals(7, descriptors.map { it.id }.distinct().size)
        assertTrue(descriptors.all { !it.favoriteEligible && !it.directToggle && it.page == 4 })
        assertTrue(SettingStore(descriptors, emptyMap()).snapshot().isEmpty())
        assertEquals(setOf("APPEARANCE", "COMPATIBILITY"), descriptors.map { it.section }.toSet())
    }
}
