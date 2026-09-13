package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.RestorePolicy
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingValue
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CommunicationCompatibilityIntegrationTest {
    private fun source(path: String): String {
        val root = sequenceOf(File("src/main"), File("app/src/main")).first(File::isDirectory)
        return File(root, path).readText()
    }
    private fun runtime(name: String) = source("java/com/Bilibili_Innocent_Lab/xposedmodule/runtime/compat/$name.kt")

    @Test fun newSettingIsDefaultOffAndCannotBeAutomaticallyRestored() {
        val spec = SettingsCatalog.byId.getValue("communication.compatibility.enabled")
        assertEquals(SettingValue.Bool(false), spec.defaultValue)
        assertEquals(RestorePolicy.MANUAL, spec.restorePolicy)
        assertEquals(22, spec.introducedCatalogVersion)
        val expected = checkNotNull(javaClass.classLoader.getResourceAsStream("settings-backup/catalog-v22.txt"))
            .bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
        assertEquals(expected, SettingsCatalog.specs.filter { it.introducedCatalogVersion <= 22 }.map { it.id }.sorted())
    }
    @Test fun switchAndRetryHintShareTheSameWarningAndTitle() {
        val settings = SettingsUiSource.function("experimentalFeaturesCard")
        assertTrue(settings.contains("showCommunicationCompatibilityConfirmDialog(anchor = button)"))
        assertTrue(SettingsUiSource.function("createCommunicationCompatibilityHint")
            .contains("showCommunicationCompatibilityConfirmDialog(anchor = it)"))
        val dialog = SettingsUiSource.function("showCommunicationCompatibilityConfirmDialog")
        assertTrue(dialog.contains("R.string.communication_compatibility_warning"))
        assertTrue(dialog.contains("R.string.communication_compatibility_mode"))
        assertTrue(dialog.contains("dismissWithAnimation(dialog, container) { setCommunicationCompatibilityEnabled(true) }"))
        assertTrue(dialog.contains("presentModalDialog(dialog, container, anchor)"))
    }
    @Test fun retryPageUsesResultTrackingAndAnAnimatedInitiallyHiddenHint() {
        assertTrue(SettingsUiSource.function("showPendingTermsPage").contains("retryPendingTermsWithCompatibility()"))
        assertTrue(SettingsUiSource.function("createCommunicationCompatibilityHint").contains("visibility = View.GONE"))
        val animation = SettingsUiSource.function("updateCommunicationCompatibilityHint")
        assertTrue(animation.contains("ChangeBounds()"))
        assertTrue(animation.contains("translationY"))
        assertTrue(animation.contains("shouldOffer(pending,"))
    }
    @Test fun bothModesUseTheOriginalKernelIdentityValidator() {
        val manifest = source("AndroidManifest.xml")
        assertTrue(Regex("CompatibilityReceiptService\"\\s+android:enabled=\"true\"\\s+android:exported=\"true\"").containsMatchIn(manifest))
        val service = runtime("CompatibilityReceiptService")
        assertTrue(service.contains("!CommunicationCompatibilityStore.hasConsent"))
        assertTrue(service.contains("flags and IBinder.FLAG_ONEWAY != 0"))
        assertTrue(service.contains("data.enforceInterface(DESCRIPTOR)"))
        assertTrue(service.contains("HostReceiptRegistry.receive(this@CompatibilityReceiptService, extras)"))
        assertFalse(Regex("Binder\\.clearCallingIdentity\\(").containsMatchIn(service))
        val registry = source("java/com/Bilibili_Innocent_Lab/xposedmodule/runtime/HostReceiptRegistry.kt")
        assertTrue(registry.contains("info.applicationInfo?.uid != uid"))
        assertTrue(registry.contains("HostReceiptPayload.validate"))
    }
    @Test fun relayIsBoundedAndDoesNotUseAStartedOrForegroundService() {
        val publisher = runtime("CompatibilityReceiptPublisher")
        assertTrue(publisher.contains("BIND_WINDOW_MS"))
        assertTrue(publisher.contains("BIND_COOLDOWN_MS"))
        assertTrue(runtime("LatestReceiptOutbox").contains("maxCount: Int = 8"))
        assertTrue(publisher.contains("unbindService"))
        assertFalse(publisher.contains("startService("))
        assertFalse(publisher.contains("startForegroundService("))
    }
}
