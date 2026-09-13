package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.content.SharedPreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentScanEntry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentSnapshotCodec
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class RecommendationBlocklistDraftTest {
    private fun picks(token: String? = "event-1", id: String = "8318") = MineComponentSnapshot(
        targetPackage = "tv.danmaku.bili", processName = "tv.danmaku.bili",
        surface = MineComponentSnapshotCodec.SURFACE_SECTION_PICKS,
        generatedAt = 1L, capabilities = emptySet(),
        entries = listOf(checkNotNull(MineComponentScanEntry.create("section", "鬼畜", id, null, true))
            .copy(selectionToken = token))
    )

    @Test fun removalPreservesOtherNamesIdsAndAuthors() {
        val prefs = MemoryPreferences("8318,东方 Project,163", "UP A,123")
        val draft = RecommendationBlocklistDraft("8318,东方 Project,163", "UP A,123", listOf(picks()), "")
        draft.setSelected(RecommendationBlockRule(RecommendationBlockKind.TAG, "8318"), false)
        draft.setSelected(RecommendationBlockRule(RecommendationBlockKind.AUTHOR, "123"), false)
        assertTrue(draft.save(prefs.instance))
        assertEquals("东方 project,163", prefs.tags())
        assertEquals("up a", prefs.authors())
    }

    @Test fun cancelledDraftDoesNotWriteOrAcknowledge() {
        val prefs = MemoryPreferences("163", "UP A")
        val draft = RecommendationBlocklistDraft("163", "UP A", listOf(picks()), "")
        draft.rows.forEach { draft.setSelected(it.rule, false) }
        assertEquals("163", prefs.tags())
        assertEquals("UP A", prefs.authors())
        assertNull(prefs.values[RecommendationBlocklistDraft.REVIEWED_EVENTS_KEY])
    }

    @Test fun rejectedPickDoesNotReturnWhenAnotherPickUpdatesTheSnapshot() {
        val prefs = MemoryPreferences("", "")
        val first = RecommendationBlocklistDraft("", "", listOf(picks()), "")
        first.rows.forEach { first.setSelected(it.rule, false) }
        assertTrue(first.save(prefs.instance))
        val updated = picks().copy(generatedAt = 2L, entries = picks().entries + picks("event-2", "163").entries)
        val reopened = RecommendationBlocklistDraft("", "", listOf(updated), prefs.reviewed())
        assertEquals(listOf("163"), reopened.rows.map { it.rule.value })
    }

    @Test fun aNewExplicitPickOfTheSameTagCanBeConfirmedAgain() {
        val first = RecommendationBlocklistDraft("", "", listOf(picks()), "")
        val reopened = RecommendationBlocklistDraft("", "", listOf(picks("new-session-event")), first.acknowledgedValue())
        assertEquals("8318", reopened.selectedValue(RecommendationBlockKind.TAG))
        assertTrue(reopened.rows.single().pending)
    }

    @Test fun reviewedSavedPickRemainsEditableButNotPending() {
        val first = RecommendationBlocklistDraft("", "", listOf(picks()), "")
        val reopened = RecommendationBlocklistDraft("8318", "", listOf(picks()), first.acknowledgedValue())
        assertFalse(reopened.rows.single().pending)
        assertEquals("鬼畜 (8318)", reopened.rows.single().label)
    }

    @Test fun legacySnapshotsCanBeDismissedWithoutBlockingNewEvents() {
        val legacy = RecommendationBlocklistDraft("", "", listOf(picks(null)), "")
        assertTrue(RecommendationBlocklistDraft("", "", listOf(picks(null).copy(generatedAt = 20)), legacy.acknowledgedValue()).rows.isEmpty())
        assertEquals(1, RecommendationBlocklistDraft("", "", listOf(picks()), legacy.acknowledgedValue()).rows.size)
    }

    @Test fun missingOrUnrelatedSnapshotsNeverClearManualRules() {
        val draft = RecommendationBlocklistDraft("东方 Project,8318", "UP A", listOf(picks().copy(surface = "mine")), "")
        assertEquals("东方 project,8318", draft.selectedValue(RecommendationBlockKind.TAG))
        assertEquals("up a", draft.selectedValue(RecommendationBlockKind.AUTHOR))
        assertTrue(draft.rows.none { it.pending })
    }

    @Test fun failedCommitDoesNotAcknowledgeOrLoseLists() {
        val prefs = MemoryPreferences("163", "up a", fail = true)
        val draft = RecommendationBlocklistDraft("163", "up a", listOf(picks()), "")
        assertFalse(draft.save(prefs.instance))
        assertEquals("163", prefs.tags())
        assertEquals("", prefs.reviewed())
    }

    @Test fun concurrentEditIsPreserved() {
        val prefs = MemoryPreferences("new tag", "up a")
        val draft = RecommendationBlocklistDraft("163", "up a", listOf(picks()), "")
        assertFalse(draft.save(prefs.instance))
        assertEquals("new tag", prefs.tags())
    }

    @Test fun selectionIdentitySurvivesSnapshotEncodingAndOldPayloadsRemainReadable() {
        val token = picks().entries.single()
        assertEquals(token, MineComponentScanEntry.fromJsonOrNull(token.toJson()))
        val old = token.toJson().apply { remove("selectionToken") }
        assertNull(checkNotNull(MineComponentScanEntry.fromJsonOrNull(old)).selectionToken)
        assertNull(MineComponentScanEntry.fromJsonOrNull(old.put("selectionToken", "x".repeat(65))))
    }

    @Test fun authorPicksAreReviewedSeparatelyAndNamesKeepTheirSpaces() {
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

    @Test fun boundedReviewHistoryAlwaysKeepsTheCurrentSnapshotAcknowledged() {
        val first = RecommendationBlocklistDraft("", "", listOf(picks()), "")
        val reviewed = first.acknowledgedValue() + "\n" + (1..512).joinToString("\n") { "older-$it" }
        val draft = RecommendationBlocklistDraft("", "", listOf(picks(), picks("new-event", "163")), reviewed)
        val saved = draft.acknowledgedValue()
        assertEquals(512, saved.lines().size)
        assertTrue(RecommendationBlocklistDraft("", "", listOf(picks()), saved).rows.isEmpty())
    }

    private class MemoryPreferences(tags: String, authors: String, private val fail: Boolean = false) {
        val values = mutableMapOf(
            FeaturePreferences.HOME_RECOMMEND_BLOCKED_TIDS to tags,
            FeaturePreferences.HOME_RECOMMEND_BLOCKED_AUTHORS to authors
        )
        fun tags() = values[FeaturePreferences.HOME_RECOMMEND_BLOCKED_TIDS]
        fun authors() = values[FeaturePreferences.HOME_RECOMMEND_BLOCKED_AUTHORS]
        fun reviewed() = values[RecommendationBlocklistDraft.REVIEWED_EVENTS_KEY].orEmpty()
        val instance = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString" -> values[args!![0]] ?: args[1]
                "edit" -> editor()
                else -> error(method.name)
            }
        } as SharedPreferences
        private fun editor(): SharedPreferences.Editor {
            val pending = mutableMapOf<String, String>()
            return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                when (method.name) {
                    "putString" -> { pending[args!![0] as String] = args[1] as String; proxy }
                    "commit" -> { values.putAll(pending); !fail }
                    else -> error(method.name)
                }
            } as SharedPreferences.Editor
        }
    }
}
