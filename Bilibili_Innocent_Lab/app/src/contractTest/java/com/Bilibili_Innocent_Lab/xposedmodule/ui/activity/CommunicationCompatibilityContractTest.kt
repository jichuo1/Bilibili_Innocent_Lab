package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.RestorePolicy
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingValue
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalogHistoryFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 通信兼容模式目录边界、确认弹窗/待授权撤销提示以及兼容回执服务与中继验证契约。 */
class CommunicationCompatibilityContractTest {

    @Test
    fun compatibilitySettingCatalogUiEntryConfirmationAndPendingRevokeStayWired() {
        val spec = SettingsCatalog.byId.getValue("communication.compatibility.enabled")
        assertEquals(SettingValue.Bool(false), spec.defaultValue)
        assertEquals(RestorePolicy.MANUAL, spec.restorePolicy)
        assertEquals(22, spec.introducedCatalogVersion)
        assertEquals(
            SettingsCatalogHistoryFixture.idsAtVersion(22),
            SettingsCatalog.specs.filter { it.introducedCatalogVersion <= 22 }.map { it.id }.sorted()
        )

        val settings = SettingsUiSource.function("experimentalFeaturesCard")
        assertTrue(settings.contains("showCommunicationCompatibilityConfirmDialog(anchor = button)"))
        val hint = SettingsUiSource.function("createCommunicationCompatibilityHint")
        assertTrue(hint.contains("showCommunicationCompatibilityConfirmDialog(anchor = it)"))
        assertTrue(hint.contains("visibility = View.GONE"))
        assertTrue(hint.contains("setCommunicationCompatibilityEnabled(false)"))

        val dialog = SettingsUiSource.function("showCommunicationCompatibilityConfirmDialog")
        assertTrue(dialog.contains("R.string.communication_compatibility_warning"))
        assertTrue(dialog.contains("R.string.communication_compatibility_mode"))
        assertTrue(dialog.contains("dismissWithAnimation(dialog, container) { setCommunicationCompatibilityEnabled(true) }"))
        assertTrue(dialog.contains("presentModalDialog(dialog, container, anchor)"))

        assertTrue(SettingsUiSource.function("showPendingTermsPage").contains("retryPendingTermsWithCompatibility()"))
        val animation = SettingsUiSource.function("updateCommunicationCompatibilityHint")
        assertTrue(animation.contains("ChangeBounds()"))
        assertTrue(animation.contains("translationY"))
        assertTrue(animation.contains("shouldOffer(pending,"))
        assertTrue(animation.contains("enabled || compatibilityRetryTracker.shouldOffer"))
        assertTrue(animation.contains("communication_compatibility_disable"))
        assertTrue(SettingsUiSource.function("setCommunicationCompatibilityEnabled").contains("compatibilityPendingRetry?.cancel()"))
    }

    @Test
    fun compatibilityReceiptServiceAndRelayPreserveKernelValidationAndBounds() {
        val manifest = SourceContract.read("src/main/AndroidManifest.xml")
        assertTrue(Regex("CompatibilityReceiptService\"\\s+android:enabled=\"true\"\\s+android:exported=\"true\"").containsMatchIn(manifest))
        val service = SourceContract.read("runtime/compat/CompatibilityReceiptService.kt")
        assertTrue(service.contains("!CommunicationCompatibilityStore.hasConsent"))
        assertTrue(service.contains("flags and IBinder.FLAG_ONEWAY != 0"))
        assertTrue(service.contains("data.enforceInterface(DESCRIPTOR)"))
        assertTrue(service.contains("HostReceiptRegistry.receive(this@CompatibilityReceiptService, extras)"))
        assertFalse(Regex("Binder\\.clearCallingIdentity\\(").containsMatchIn(service))

        val registry = SourceContract.read("runtime/HostReceiptRegistry.kt")
        assertTrue(registry.contains("info.applicationInfo?.uid != uid"))
        assertTrue(registry.contains("HostReceiptPayload.validate"))

        val publisher = SourceContract.read("runtime/compat/CompatibilityReceiptPublisher.kt")
        assertTrue(publisher.contains("BIND_WINDOW_MS"))
        assertTrue(publisher.contains("BIND_COOLDOWN_MS"))
        assertTrue(SourceContract.read("runtime/compat/LatestReceiptOutbox.kt").contains("maxCount: Int = 8"))
        assertTrue(publisher.contains("unbindService"))
        assertFalse(publisher.contains("startService("))
        assertFalse(publisher.contains("startForegroundService("))
    }
}
