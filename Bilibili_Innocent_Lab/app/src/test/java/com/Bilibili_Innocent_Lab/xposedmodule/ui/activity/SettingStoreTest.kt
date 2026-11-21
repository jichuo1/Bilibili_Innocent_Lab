package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingStoreTest {
    private fun descriptor(id: String = "feature", page: Int = 1) =
        SettingDescriptor(id, "$id.key", 1, page, "PURIFICATION", true)

    fun descriptorsAndAuthorityExistWithoutConstructingAnyPage() {
        val store = SettingStore(listOf(descriptor(), descriptor("other", 2)), mapOf("feature.key" to true))
        assertEquals(listOf("feature", "other"), store.descriptors.map { it.id })
        assertEquals(2, store.descriptor("other")!!.page)
        assertTrue(store.state("feature").checked)
        assertFalse(store.state("other").checked)
        assertTrue(SettingStore::class.java.declaredFields.none { it.type.name.startsWith("android.") })
    }

    fun rejectedBusinessCommandMirrorsTheFinalAuthorityInsteadOfTheRequest() {
        val store = SettingStore(listOf(descriptor()), emptyMap())
        val mirrors = mutableListOf<Boolean>()
        store.observe("feature") { mirrors += it.checked }
        var invocations = 0
        val rejected = store.request("feature", true) { invocations++; false }
        assertFalse(rejected.accepted)
        assertFalse(rejected.state.checked)
        assertTrue(mirrors.isEmpty())
        val accepted = store.request("feature", true) { invocations++; true }
        assertTrue(accepted.accepted)
        assertEquals(listOf(true), mirrors)
        assertEquals(2, invocations)
    }

    fun disabledOrReentrantCommandsDoNotExecuteBusinessEffects() {
        val store = SettingStore(listOf(descriptor()), emptyMap())
        store.confirm("feature", SettingState(false, enabled = false))
        val disabled = store.request("feature", true) { fail("disabled command executed"); true }
        assertFalse(disabled.accepted)
        store.confirm("feature", SettingState(false))
        store.request("feature", true) {
            assertFalse(store.request("feature", true) { fail("reentrant command executed"); true }.accepted)
            true
        }
        assertTrue(store.state("feature").checked)
    }

    fun pendingConfirmationCanPublishLaterAndObserversSeeOnlyConfirmedData() {
        val store = SettingStore(listOf(descriptor()), emptyMap())
        val observed = mutableListOf<SettingState>()
        store.observe("feature", observed::add)
        val pending = store.request("feature", true) { false }
        assertFalse(pending.state.checked)
        assertTrue(observed.isEmpty())
        store.checked("feature", true)
        assertEquals(listOf(SettingState(true)), observed)
        store.checked("feature", true)
        assertEquals(1, observed.size)
    }

    fun failureDoesNotRetryAndDoesNotInventRollbackOfEarlierEffects() {
        val store = SettingStore(listOf(descriptor()), emptyMap())
        var effects = 0
        assertThrows(IllegalStateException::class.java) {
            store.request("feature", true) { effects++; store.checked("feature", true); error("after a real side effect") }
        }
        assertEquals(1, effects)
        assertTrue(store.state("feature").checked)
        assertTrue(store.request("feature", false) { false }.accepted)
    }

    fun closedBindingsStopDeliveryAndStoreCloseRejectsNewCommands() {
        val store = SettingStore(listOf(descriptor()), emptyMap())
        var deliveries = 0
        val stop = store.observe("feature") { deliveries++ }
        stop(); stop()
        store.checked("feature", true)
        assertEquals(0, deliveries)
        store.observe("feature") { deliveries++ }
        store.close(); store.close()
        store.checked("feature", false)
        assertEquals(0, deliveries)
        assertThrows(IllegalStateException::class.java) { store.request("feature", false) { false } }
    }

    fun identityConflictsAndUnknownCommandsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            SettingStore(listOf(descriptor(), descriptor()), emptyMap())
        }
        val store = SettingStore(listOf(descriptor()), emptyMap())
        assertThrows(IllegalStateException::class.java) { store.request("missing", true) { true } }
    }

    fun navigationMetadataDoesNotInventBooleanStateOrFavoriteEligibility() {
        val action = descriptor("action").copy(directToggle = false, favoriteEligible = false)
        val store = SettingStore(listOf(action), emptyMap())
        assertEquals(action, store.descriptor(action.id))
        assertTrue(store.snapshot().isEmpty())
        assertThrows(IllegalStateException::class.java) { store.state(action.id) }
    }

    fun intermediatePreferenceNotificationsDoNotPublishARejectedRequest() {
        val store = SettingStore(listOf(descriptor()), emptyMap())
        val observed = mutableListOf<Boolean>()
        store.observe("feature") { observed += it.checked }
        val result = store.request("feature", true) {
            store.checked("feature", true)
            assertTrue(observed.isEmpty())
            false
        }
        assertFalse(result.accepted)
        assertFalse(store.state("feature").checked)
        assertTrue(observed.isEmpty())
    }

    fun closingDuringNotificationPreventsLaterSnapshotCallbacksForBothWritePaths() {
        listOf(false, true).forEach { command ->
            val store = SettingStore(listOf(descriptor()), emptyMap())
            var later = 0
            store.observe("feature") { store.close() }
            store.observe("feature") { later++ }
            if (command) store.request("feature", true) { true } else store.checked("feature", true)
            assertEquals(0, later)
        }
    }

    fun removingAndReplacingSubscriptionDuringNotificationCannotReviveOldSnapshot() {
        val store = SettingStore(listOf(descriptor()), emptyMap())
        var later = 0
        lateinit var stop: () -> Unit
        val callback: (SettingState) -> Unit = { later++ }
        store.observe("feature") { stop(); store.observe("feature", callback) }
        stop = store.observe("feature", callback)
        store.checked("feature", true)
        assertEquals(0, later)
    }

    fun supersededNotificationCannotOverwriteLatestStateInALaterObserver() {
        val store = SettingStore(listOf(descriptor()), emptyMap())
        val values = mutableListOf<Boolean>()
        store.observe("feature") { if (it.checked) store.checked("feature", false) }
        store.observe("feature") { values += it.checked }
        store.checked("feature", true)
        assertEquals(listOf(false), values)
        assertFalse(store.state("feature").checked)
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (SettingStoreTest) -> Unit) {
            val s = SettingStoreTest()
            block(s)
        }
        runIsolated { s -> s.descriptorsAndAuthorityExistWithoutConstructingAnyPage() }
        runIsolated { s -> s.rejectedBusinessCommandMirrorsTheFinalAuthorityInsteadOfTheRequest() }
        runIsolated { s -> s.disabledOrReentrantCommandsDoNotExecuteBusinessEffects() }
        runIsolated { s -> s.pendingConfirmationCanPublishLaterAndObserversSeeOnlyConfirmedData() }
        runIsolated { s -> s.failureDoesNotRetryAndDoesNotInventRollbackOfEarlierEffects() }
        runIsolated { s -> s.closedBindingsStopDeliveryAndStoreCloseRejectsNewCommands() }
        runIsolated { s -> s.identityConflictsAndUnknownCommandsAreRejected() }
        runIsolated { s -> s.navigationMetadataDoesNotInventBooleanStateOrFavoriteEligibility() }
        runIsolated { s -> s.intermediatePreferenceNotificationsDoNotPublishARejectedRequest() }
        runIsolated { s -> s.closingDuringNotificationPreventsLaterSnapshotCallbacksForBothWritePaths() }
        runIsolated { s -> s.removingAndReplacingSubscriptionDuringNotificationCannotReviveOldSnapshot() }
        runIsolated { s -> s.supersededNotificationCannotOverwriteLatestStateInALaterObserver() }
    
        SettingsUiCompatibilityCases().verifyAllCases()
        SettingWritePolicyCases().verifyAllCases()
        VideoRelateFilterSelectionCases().verifyAllCases()
    }
}

private class SettingsUiCompatibilityCases {

    fun `accepts direct invalid XmlBlock document failure`() {
        assertTrue(SettingsUiCompatibility.isInvalidXmlDocumentFailure(invalidDocumentFailure()))
    }

    fun `accepts invalid XmlBlock document failure nested in cause chain`() {
        val failure = IllegalStateException("layout failed", invalidDocumentFailure())

        assertTrue(SettingsUiCompatibility.isInvalidXmlDocumentFailure(failure))
    }

    fun `rejects matching message outside XmlBlock parser`() {
        val failure = NullPointerException("Null document").apply {
            stackTrace = arrayOf(StackTraceElement("example.Parser", "getStyleAttribute", "Parser.kt", 1))
        }

        assertFalse(SettingsUiCompatibility.isInvalidXmlDocumentFailure(failure))
    }

    fun `rejects non matching exception type or message`() {
        val wrongType = IllegalStateException("Null document").apply {
            stackTrace = invalidDocumentStack()
        }
        val wrongMessage = NullPointerException("other").apply {
            stackTrace = invalidDocumentStack()
        }

        assertFalse(SettingsUiCompatibility.isInvalidXmlDocumentFailure(wrongType))
        assertFalse(SettingsUiCompatibility.isInvalidXmlDocumentFailure(wrongMessage))
    }

    private fun invalidDocumentFailure() = NullPointerException("Null document").apply {
        stackTrace = invalidDocumentStack()
    }

    private fun invalidDocumentStack() = arrayOf(
        StackTraceElement(
            "android.content.res.XmlBlock\$Parser",
            "getStyleAttribute",
            "XmlBlock.java",
            620
        )
    )

    fun verifyAllCases() {
        fun runIsolated(block: (SettingsUiCompatibilityCases) -> Unit) {
            val s = SettingsUiCompatibilityCases()
            block(s)
        }
        runIsolated { s -> s.`accepts direct invalid XmlBlock document failure`() }
        runIsolated { s -> s.`accepts invalid XmlBlock document failure nested in cause chain`() }
        runIsolated { s -> s.`rejects matching message outside XmlBlock parser`() }
        runIsolated { s -> s.`rejects non matching exception type or message`() }
    }
}

private class SettingWritePolicyCases {
    private fun store(initial: Boolean) = SettingStore(
        listOf(SettingDescriptor("feature", "key", 1, 1, "PURIFICATION", true)),
        mapOf("key" to initial),
    )

    fun notificationBeforeReadbackFailureCannotTurnRejectionIntoAcceptance() {
        listOf(false, true).forEach { previous ->
            val requested = !previous
            val store = store(previous)
            var mirrorField = previous
            var writes = 0
            val observed = mutableListOf<Boolean>()
            store.observe("feature") { observed += it.checked }
            val readbackFailure = IllegalStateException("simulated readback failure")
            val result = store.request("feature", requested) {
                val outcome = SettingWritePolicy.attempt(mirrorField, requested) {
                    writes++
                    // SharedPreferences can synchronously notify before the command verifies the write.
                    mirrorField = requested
                    store.checked("feature", requested)
                    throw readbackFailure
                }
                assertSame(readbackFailure, outcome.failure)
                assertFalse(outcome.saved)
                mirrorField = outcome.confirmed
                outcome.confirmed
            }
            assertEquals(1, writes)
            assertFalse(result.accepted)
            assertEquals(previous, result.state.checked)
            assertEquals(previous, store.state("feature").checked)
            assertEquals(previous, mirrorField)
            assertTrue(observed.isEmpty())
        }
    }

    fun successfulVerifiedWritePublishesItsFinalValueOnce() {
        val store = store(false)
        val observed = mutableListOf<Boolean>()
        store.observe("feature") { observed += it.checked }
        var writes = 0
        val result = store.request("feature", true) {
            val outcome = SettingWritePolicy.attempt(false, true) {
                writes++
                store.checked("feature", true)
                assertTrue(observed.isEmpty())
            }
            assertTrue(outcome.saved)
            assertNull(outcome.failure)
            outcome.confirmed
        }
        assertTrue(result.accepted)
        assertEquals(1, writes)
        assertEquals(listOf(true), observed)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (SettingWritePolicyCases) -> Unit) {
            val s = SettingWritePolicyCases()
            block(s)
        }
        runIsolated { s -> s.notificationBeforeReadbackFailureCannotTurnRejectionIntoAcceptance() }
        runIsolated { s -> s.successfulVerifiedWritePublishesItsFinalValueOnce() }
    }
}

private class VideoRelateFilterSelectionCases {

    fun `catalog keeps existing filters and matching enhancement last`() {
        assertEquals(5, VideoRelateFilterCatalog.contentOptions.size)
        assertEquals(6, VideoRelateFilterCatalog.panelOptions.size)
        assertEquals(
            6,
            VideoRelateFilterCatalog.panelOptions.map { it.preferenceKey }.distinct().size
        )
        assertEquals(
            FeaturePreferences.VIDEO_RELATE_MATCHING_ENHANCEMENT_ENABLED,
            VideoRelateFilterCatalog.panelOptions.last().preferenceKey
        )
        assertTrue(VideoRelateFilterCatalog.panelOptions.last().isMatchingEnhancement)
    }

    fun `reason controls follow enhancement without losing keywords`() {
        val draft = VideoRelateFilterDraft(emptyMap(), "商业推广\n去小程序")

        assertFalse(draft.reasonFilterVisible)
        assertFalse(draft.strongModeVisible)
        draft[FeaturePreferences.VIDEO_RELATE_REASON_FILTER_ENABLED] = true
        assertFalse(draft.keywordEditorVisible)

        draft[FeaturePreferences.VIDEO_RELATE_MATCHING_ENHANCEMENT_ENABLED] = true
        assertTrue(draft.reasonFilterVisible)
        assertTrue(draft.strongModeVisible)
        assertTrue(draft.keywordEditorVisible)

        draft[FeaturePreferences.VIDEO_RELATE_MATCHING_ENHANCEMENT_ENABLED] = false
        assertEquals("商业推广\n去小程序", draft.reasonKeywords)
        assertFalse(draft.keywordEditorVisible)
    }

    fun `strong mode requires explicit selection while clear disables it`() {
        val draft = VideoRelateFilterDraft(
            mapOf(FeaturePreferences.VIDEO_RELATE_STRONG_MODE_ENABLED to true),
            ""
        )

        draft.selectAll()
        assertTrue(draft[FeaturePreferences.VIDEO_RELATE_MATCHING_ENHANCEMENT_ENABLED])
        assertTrue(draft[FeaturePreferences.VIDEO_RELATE_STRONG_MODE_ENABLED])

        val fresh = VideoRelateFilterDraft(emptyMap(), "")
        fresh.selectAll()
        assertFalse(fresh[FeaturePreferences.VIDEO_RELATE_STRONG_MODE_ENABLED])

        draft.clear()
        assertFalse(draft[FeaturePreferences.VIDEO_RELATE_STRONG_MODE_ENABLED])
    }

    fun `changed values and keywords remain draft only`() {
        val commercial = FeaturePreferences.REMOVE_RELATE_COMMERCIAL
        val draft = VideoRelateFilterDraft(mapOf(commercial to true), "广告")

        draft[commercial] = false
        draft[FeaturePreferences.REMOVE_RELATE_GAME] = true
        draft.reasonKeywords = "推广"

        assertEquals(
            mapOf(
                commercial to false,
                FeaturePreferences.REMOVE_RELATE_GAME to true
            ),
            draft.changedValues()
        )
        assertEquals(1, draft.selectedContentCount())
        assertTrue(draft.keywordsChanged())
    }

    fun verifyAllCases() {
        fun runIsolated(block: (VideoRelateFilterSelectionCases) -> Unit) {
            val s = VideoRelateFilterSelectionCases()
            block(s)
        }
        runIsolated { s -> s.`catalog keeps existing filters and matching enhancement last`() }
        runIsolated { s -> s.`reason controls follow enhancement without losing keywords`() }
        runIsolated { s -> s.`strong mode requires explicit selection while clear disables it`() }
        runIsolated { s -> s.`changed values and keywords remain draft only`() }
    }
}
