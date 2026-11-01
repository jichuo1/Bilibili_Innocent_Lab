package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 云控键扫描面板的护栏：热路径不分配不加锁、后台任务过 HostThreadGuard、扫描失败不拖累冻结、
 * 快照里不出现字符串值、弹窗沿用设置页的锚点约定。
 */
class CloudKeyScanWiringContractTest {
    private val scan = SourceContract.read("hook/feature/CloudKeyScan.kt")
    private val installer = SourceContract.read("hook/feature/CloudKeyFreezeFeatureInstaller.kt")
    private val dialogs = SourceContract.read("ui/activity/CloudKeyScanDialogs.kt")
    private val settings = SourceContract.read("ui/activity/EnhanceSettingsSections.kt")

    @Test fun theReadPathOnlyDoesAMapLookupWithoutAllocatingFormattingOrLocking() {
        val record = scan.after("fun record(key: String, value: Boolean)").before("private fun add(")
        for (forbidden in listOf("lowercase()", "Regex", "format(", "listOf(", "mutableListOf", "synchronized",
            "Pair(", "\"", "StringBuilder", "log", "Log")) {
            assertFalse("record() must not use $forbidden", record.contains(forbidden))
        }
        assertTrue(record.contains("states[key]"))
        // 校验只在新键的慢路径上做，并且已知被拒的键先于校验返回。
        val add = scan.after("private fun add(").before("private fun markDirty()")
        assertTrue(add.indexOf("rejected.containsKey(key)") < add.indexOf("CloudKeyFreezeRules.isFreezable(key)"))
    }

    @Test fun theSnapshotCarriesOnlyBooleanMarkersNeverStringContent() {
        assertTrue(scan.contains("const val VALUE_TRUE = \"true\""))
        assertTrue(scan.contains("const val VALUE_FALSE = \"false\""))
        assertTrue(scan.contains("const val VALUE_MIXED = \"mixed\""))
        assertFalse(scan.contains("fun record(key: String, value: String"))
        val observe = installer.after("private fun observeKey(").before("val value = if (original is Boolean)")
        assertTrue(observe.contains("original is Boolean && kind != CloudKeyReadKind.TEXT"))
        assertTrue("frozen keys are not recorded", observe.contains("!frozen"))
    }

    @Test fun theFlushRunsOnItsOwnDaemonThreadInsideTheHostThreadGuard() {
        val start = installer.after("private fun newScanCollector(").before("private fun register(")
        assertTrue(start.contains("isDaemon = true"))
        assertTrue(start.contains("HostThreadGuard.run(\"cloud_key_scan.flush\") { task() }"))
        assertTrue(start.contains("ScanSnapshotPublisher("))
        assertTrue(start.contains("MineComponentSnapshotCodec.SURFACE_CLOUD_KEYS"))
        assertFalse("the hook callback never posts to a host scheduler", installer.contains("Handler("))
    }

    @Test fun aScanThatCannotStartDoesNotStopTheFreezeAndHasItsOwnStatusChannel() {
        val startScan = installer.after("private fun startScan(").before("private fun newScanCollector(")
        assertTrue(startScan.contains("runCatching"))
        assertTrue(startScan.contains("SCAN_STATUS"))
        assertTrue(installer.contains("private const val SCAN_STATUS = \"cloud_key_scan_status\""))
        assertTrue(installer.contains("if (observe) startScan(environment) else null"))
    }

    @Test fun picksAreMergedUnderTheManualRulesAndWiredFromThePreference() {
        assertTrue(installer.contains("CloudKeyFreezeRules.parse(picked, CloudKeyFreezeRules.MAX_PICKED)\n" +
            "            .overlaidWith(CloudKeyFreezeRules.parse(rules))"))
        val hook = SourceContract.read("hook/HookEntry.kt")
        assertTrue(hook.contains("FeaturePreferences.CLOUD_KEY_FREEZE_PICKED"))
    }

    @Test fun theRuleLookupHappensBeforeObservationSoFrozenKeysAreKnown() {
        val beforeRule = installer.after("fun onRead(param: ModernHookParam, target: Target)").before("val rule = table[key]\n")
        assertFalse(beforeRule.contains("observeKey("))
        assertTrue(installer.contains("if (observe) observeKey(key, target.kind, param.result, frozen = rule != null)"))
    }

    @Test fun thePanelAnchorsOnTheClickableSummaryRowAndUsesTheSharedModalHelpers() {
        assertTrue(dialogs.contains("val anchor = cloudKeyFreezePickedSummaryView"))
        assertTrue(dialogs.contains("presentModalDialog(dialog, container, anchor)"))
        assertTrue(dialogs.contains("createModalContainer()"))
        assertTrue(dialogs.contains("dismissWithAnimation(dialog, container) {}"))
        assertFalse(dialogs.contains("it.parent")); assertFalse(dialogs.contains("parentOrNull"))
        assertTrue(settings.contains("setOnClickListener { openCloudKeyScanPanel() }"))
        assertTrue(settings.contains("bindSettingDestination(this, FeaturePreferences.CLOUD_KEY_FREEZE_PICKED)"))
    }

    @Test fun savingFromThePanelTurnsTheMasterSwitchOnSoTheTickIsNotSilentlyInert() {
        val save = dialogs.after("val text = state.pinsText()").before("cloudKeyFreezePickedSummaryView?.text")
        assertTrue(save.contains("FeaturePreferences.CLOUD_KEY_FREEZE_PICKED"))
        assertTrue(save.contains("state.checkedCount > 0 && !cloudKeyFreeze"))
        assertTrue(save.contains("FeaturePreferences.CLOUD_KEY_FREEZE_ENABLED"))
    }
}
