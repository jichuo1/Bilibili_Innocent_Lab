package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostConfigState
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostInstallChainState
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostRuntimeBootstrapEvidence
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostRuntimeDiagnosticsSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LspatchActivationReceiptTrackerTest {
    @Test
    fun `same connection coalesces requests and retains a matching receipt`() {
        val tracker = LspatchActivationReceiptTracker()
        tracker.startSession()
        val request = requireNotNull(tracker.begin(7L))
        assertNull(tracker.begin(7L))

        val receipt = receipt()
        assertTrue(tracker.accept(request, 7L, receipt))
        assertSame(receipt, tracker.receiptFor(7L))
        assertNull(tracker.begin(7L))
    }

    @Test
    fun `cleared or superseded request cannot restore stale host evidence`() {
        val tracker = LspatchActivationReceiptTracker()
        tracker.startSession()
        val oldRequest = requireNotNull(tracker.begin(7L))
        tracker.clearConnectionEvidence()
        assertFalse(tracker.accept(oldRequest, 7L, receipt()))
        assertNull(tracker.receiptFor(7L))

        val newRequest = requireNotNull(tracker.begin(8L))
        assertFalse(tracker.accept(newRequest, 9L, receipt()))
        assertNull(tracker.receiptFor(8L))
        assertTrue(tracker.begin(9L) != null)
    }

    @Test
    fun `missing receipt remains retryable on a later lifecycle entry`() {
        val tracker = LspatchActivationReceiptTracker()
        tracker.startSession()
        val request = requireNotNull(tracker.begin(7L))
        assertTrue(tracker.accept(request, 7L, null))
        assertNull(tracker.receiptFor(7L))
        assertTrue(tracker.begin(7L) != null)
    }

    @Test
    fun `pause and resume on the same connection requires a fresh request`() {
        val tracker = LspatchActivationReceiptTracker()
        tracker.startSession()
        val oldRequest = requireNotNull(tracker.begin(7L))
        assertTrue(tracker.accept(oldRequest, 7L, receipt()))

        tracker.endSession()
        tracker.startSession()
        assertFalse(tracker.accept(oldRequest, 7L, receipt()))

        val newRequest = requireNotNull(tracker.begin(7L))
        assertTrue(newRequest.token != oldRequest.token)
        assertTrue(tracker.accept(newRequest, 7L, null))
        assertNull(tracker.receiptFor(7L))
    }

    @Test
    fun `service can arrive after resume while the foreground session stays active`() {
        val tracker = LspatchActivationReceiptTracker()
        tracker.startSession()
        assertTrue(tracker.begin(7L) != null)
    }

    @Test
    fun `disconnect clears connection evidence but allows a later reconnect`() {
        val tracker = LspatchActivationReceiptTracker()
        tracker.startSession()
        val first = requireNotNull(tracker.begin(7L))
        assertTrue(tracker.accept(first, 7L, receipt()))

        tracker.clearConnectionEvidence()
        assertNull(tracker.receiptFor(7L))
        val reconnected = requireNotNull(tracker.begin(8L))
        val reconnectedReceipt = receipt()
        assertTrue(tracker.accept(reconnected, 8L, reconnectedReceipt))
        assertSame(reconnectedReceipt, tracker.receiptFor(8L))
    }

    private fun receipt() = HostRuntimeDiagnosticsSnapshot(
        capturedAtEpochMs = 1L,
        processName = "tv.danmaku.bili",
        features = emptyList(),
        bootstrap = HostRuntimeBootstrapEvidence(
            bootstrapReached = true,
            configState = HostConfigState.ACCEPTED,
            configGeneration = 1L,
            installChainState = HostInstallChainState.COMPLETED
        )
    )
}
