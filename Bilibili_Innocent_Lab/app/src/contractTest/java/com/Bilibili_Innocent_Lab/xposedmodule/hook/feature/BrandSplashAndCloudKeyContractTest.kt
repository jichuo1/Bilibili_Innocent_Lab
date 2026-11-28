package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.SettingsUiSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 开屏配置/绘制隔离与云控键扫描面板的源码契约护栏。
 */
class BrandSplashAndCloudKeyContractTest {

    @Test
    fun `brand splash wiring preserves skip priority, page contracts, and ui thread safety`() {
        val hook = SourceContract.read("hook/HookEntry.kt")
        assertTrue(hook.contains("BrandSplashSkipFeatureInstaller("))
        assertTrue(hook.contains("BrandSplashCustomFeatureInstaller("))
        assertTrue(hook.contains("renderEnabled = !prefs.getBoolean(FeaturePreferences.BRAND_SPLASH_SKIP, false)"))
        val system = SettingsUiSource.function("enhanceSystemCategory")
        val appearance = SettingsUiSource.function("beautificationSettingsCard")
        assertTrue(system.contains("FeaturePreferences.BRAND_SPLASH_SKIP"))
        assertTrue(appearance.contains("FeaturePreferences.BRAND_SPLASH_CUSTOM"))

        val skip = SourceContract.read("hook/feature/BrandSplashSkipFeatureInstaller.kt")
        val native = skip.after("private fun installNative").before("private fun installKntr")
        assertTrue(native.contains("instance !== target"))
        assertTrue(native.contains("nativeFrames.leave()"))
        assertFalse(skip.contains("setDuration"))
        assertFalse(skip.contains("DelayKt"))
        assertFalse(skip.contains("finish("))
        assertTrue(skip.contains("result = hostUnit"))

        val painter = SourceContract.read("hook/feature/BrandSplashKntrRenderer.kt")
        assertFalse(painter.contains("args[1] ="))
        assertTrue(painter.contains("frame.mainConsumed"))
        assertTrue(painter.contains("drawModel::get"))
        assertTrue(painter.contains("WeakHashMap"))
        assertFalse(painter.contains("decodeFile("))
        val cache = SourceContract.read("hook/feature/BrandSplashImageCache.kt")
        assertTrue(cache.contains("removeOnCancelPolicy = true"))
        assertTrue(cache.contains("state.isCurrent(revision)"))
        assertTrue(cache.contains("brandSplashImageReloadAfterWrite(signature, cached?.signature"))
        assertTrue(cache.contains("cached?.exitRequested?.get() == true, cached?.image?.key"))
        assertTrue(cache.contains("request(snapshot, images, signature, reload.afterKey)"))
        assertFalse(cache.contains("bitmap.recycle()"))
        val decoder = SourceContract.read("hook/feature/BrandSplashBitmapDecoder.kt")
        assertTrue(decoder.contains("brandSplashBitmapSampleSize(bounds.outWidth, bounds.outHeight)"))
    }

    @Test
    fun `cloud key scan stays non-allocating on hot path, daemon-guarded, and modal-anchored`() {
        val scan = SourceContract.read("hook/feature/CloudKeyScan.kt")
        val installer = SourceContract.read("hook/feature/CloudKeyFreezeFeatureInstaller.kt")
        val dialogs = SourceContract.read("ui/activity/CloudKeyScanDialogs.kt")
        val settings = SourceContract.read("ui/activity/EnhanceSettingsSections.kt")

        val record = scan.after("fun record(key: String, value: Boolean)").before("private fun add(")
        for (forbidden in listOf(
            "lowercase()", "Regex", "format(", "listOf(", "mutableListOf", "synchronized",
            "Pair(", "\"", "StringBuilder", "log", "Log"
        )) {
            assertFalse("record() must not use $forbidden", record.contains(forbidden))
        }
        assertTrue(record.contains("states[key]"))
        // 校验只在新键的慢路径上做，并且已知被拒的键先于校验返回。
        val add = scan.after("private fun add(").before("private fun markDirty()")
        assertTrue(add.indexOf("rejected.containsKey(key)") < add.indexOf("CloudKeyFreezeRules.isFreezable(key)"))

        assertTrue(scan.contains("const val VALUE_TRUE = \"true\""))
        assertTrue(scan.contains("const val VALUE_FALSE = \"false\""))
        assertTrue(scan.contains("const val VALUE_MIXED = \"mixed\""))
        assertFalse(scan.contains("fun record(key: String, value: String"))
        val observe = installer.after("private fun observeKey(").before("val value = if (original is Boolean)")
        assertTrue(observe.contains("original is Boolean && kind != CloudKeyReadKind.TEXT"))
        assertTrue("frozen keys are not recorded", observe.contains("!frozen"))

        val start = installer.after("private fun newScanCollector(").before("private fun register(")
        assertTrue(start.contains("isDaemon = true"))
        assertTrue(start.contains("HostThreadGuard.run(\"cloud_key_scan.flush\") { task() }"))
        assertTrue(start.contains("ScanSnapshotPublisher("))
        assertTrue(start.contains("MineComponentSnapshotCodec.SURFACE_CLOUD_KEYS"))
        assertFalse("the hook callback never posts to a host scheduler", installer.contains("Handler("))

        val startScan = installer.after("private fun startScan(").before("private fun newScanCollector(")
        assertTrue(startScan.contains("runCatching"))
        assertTrue(startScan.contains("SCAN_STATUS"))
        assertTrue(installer.contains("private const val SCAN_STATUS = \"cloud_key_scan_status\""))
        assertTrue(installer.contains("if (observe) startScan(environment) else null"))

        assertTrue(
            installer.contains(
                "CloudKeyFreezeRules.parse(picked, CloudKeyFreezeRules.MAX_PICKED)\n" +
                    "            .overlaidWith(CloudKeyFreezeRules.parse(rules))"
            )
        )
        val hook = SourceContract.read("hook/HookEntry.kt")
        assertTrue(hook.contains("FeaturePreferences.CLOUD_KEY_FREEZE_PICKED"))

        val beforeRule = installer.after("fun onRead(param: ModernHookParam, target: Target)").before("val rule = table[key]\n")
        assertFalse(beforeRule.contains("observeKey("))
        assertTrue(installer.contains("if (observe) observeKey(key, target.kind, param.result, frozen = rule != null)"))

        assertTrue(dialogs.contains("val anchor = cloudKeyFreezePickedSummaryView"))
        assertTrue(dialogs.contains("presentModalDialog(dialog, container, anchor)"))
        assertTrue(dialogs.contains("createModalContainer()"))
        assertTrue(dialogs.contains("dismissWithAnimation(dialog, container) {}"))
        assertFalse(dialogs.contains("it.parent")); assertFalse(dialogs.contains("parentOrNull"))
        assertTrue(settings.contains("setOnClickListener { openCloudKeyScanPanel() }"))
        assertTrue(settings.contains("bindSettingDestination(this, FeaturePreferences.CLOUD_KEY_FREEZE_PICKED)"))

        val save = dialogs.after("val text = state.pinsText()").before("cloudKeyFreezePickedSummaryView?.text")
        assertTrue(save.contains("FeaturePreferences.CLOUD_KEY_FREEZE_PICKED"))
        assertTrue(save.contains("state.checkedCount > 0 && !cloudKeyFreeze"))
        assertTrue(save.contains("FeaturePreferences.CLOUD_KEY_FREEZE_ENABLED"))
    }
}
