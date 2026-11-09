package com.Bilibili_Innocent_Lab.xposedmodule.hook

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 性能报告 P1-01a：适配成功回调只读一次缓存。
 *
 * 回调来自后台适配线程，每次 [VersionAdapter.loadCached] 都会重新取宿主身份并读取、解析整份缓存。
 * 六类 installer 共用同一份不可变结果；`requiresAdaptationRetry`、主线程安装和提交前的二次状态检查不变。
 */
class AdaptCompletionSnapshotContractTest {
    private fun finished() = SourceContract.read("hook/HookEntry.kt")
        .after("override fun onAdaptFinished(ok: Boolean) {")
        .before("startupCache = startupCache,")

    @Test fun successCallbackReadsTheCacheExactlyOnce() {
        val success = finished().before("} else {\n                                    VersionAdapter.showAdaptToast(")
        assertEquals(1, Regex("""VersionAdapter\.loadCached\(""").findAll(success).count())
        // 读取发生在所有 installer 分支之前，而不是藏在某个条件分支里。
        assertTrue(success.indexOf("VersionAdapter.loadCached(") < success.indexOf("qualityInstaller.requiresAdaptationRetry"))
    }

    @Test fun everyInstallerConsumesTheSharedSnapshot() {
        val body = finished()
        for (field in listOf("playerQuality", "playerPopupPromotion", "commentClassicStyle", "sponsorPlayer",
            "pageBoundaries", "homeChrome", "homeChromeSemantics")) {
            assertTrue("adapted?.$field", body.contains("adapted?.$field"))
        }
        assertFalse(body.contains("adaptedChrome"))
    }

    @Test fun retryGuardsAndMainThreadInstallationRemain() {
        val body = finished()
        for (installer in listOf("qualityInstaller", "popupInstaller", "classicInstaller", "sponsorInstaller")) {
            // 进入分支前一次、主线程提交前再核对一次。
            assertEquals(installer, 2, Regex("""$installer\.requiresAdaptationRetry""").findAll(body).count())
        }
        assertTrue(body.contains("hookEnvironment.postToMain?.invoke"))
        assertTrue(body.contains("featureInstallCoordinator.installAll("))
    }
}
