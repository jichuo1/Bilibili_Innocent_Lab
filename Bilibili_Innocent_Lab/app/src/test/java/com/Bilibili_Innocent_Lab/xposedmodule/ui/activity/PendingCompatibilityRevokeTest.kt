package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity
import org.junit.Assert.*
import org.junit.Test
class PendingCompatibilityRevokeTest {
    @Test fun pendingControlRetainsARevokeActionWhileTheModeIsEnabled() {
        val show=SettingsUiSource.function("updateCommunicationCompatibilityHint")
        assertTrue(show.contains("enabled || compatibilityRetryTracker.shouldOffer"))
        assertTrue(show.contains("communication_compatibility_disable"))
        val click=SettingsUiSource.function("createCommunicationCompatibilityHint")
        assertTrue(click.contains("setCommunicationCompatibilityEnabled(false)"))
        assertTrue(SettingsUiSource.function("setCommunicationCompatibilityEnabled").contains("compatibilityPendingRetry?.cancel()"))
    }
}
