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
    private fun finished() = HookEntrySource.hookEntry()
        .after("override fun onAdaptFinished(ok: Boolean) {")
        .before("startupCache = startupCache,")

    fun successCallbackReadsTheCacheExactlyOnce() {
        val body = finished()
        val outcome = body.after("private fun applyAdaptOutcome(ok: Boolean, showToast: Boolean) {")
        val success = outcome.after("if (ok) {").before("if (showToast)")
        val failure = outcome.after("} else {").before("if (showToast)")
        assertEquals(1, Regex("""VersionAdapter\.loadCached\(""").findAll(success).count())
        // 读取发生在统一重适配分发之前，而不是藏在各 installer 分支或重适配循环里。
        assertTrue(
            success.indexOf("VersionAdapter.loadCached(") in
                0 until success.indexOf("featureInstallCoordinator.retryAdapted(adapted, ok = true)")
        )
        assertTrue(failure.contains("featureInstallCoordinator.retryAdapted(adapted = null, ok = false)"))
        assertTrue(
            HookEntrySource.hookEntry()
                .after("homeRecommendEarlyLoadInstaller.asAdaptRetryable(")
                .before("featureInstallCoordinator.registerRetryable(")
                .contains("onFail = { finishAdaptationWait() }")
        )
        assertFalse(
            HookEntrySource.featureInstaller().contains("VersionAdapter.loadCached(")
        )
        assertTrue(body.contains("override fun onSilentCacheReady(ok: Boolean)"))
        assertTrue(body.contains("applyAdaptOutcome(ok, showToast = false)"))
    }

    fun everyInstallerConsumesTheSharedSnapshot() {
        val hook = HookEntrySource.hookEntry()
        val installChain = hook.after("val featureInstallCoordinator = FeatureInstallCoordinator(hookEnvironment)")
            .before("val commentTopologyEnabled =")
        for (field in listOf("playerQuality", "playerPopupPromotion", "commentClassicStyle", "sponsorPlayer",
            "pageBoundaries", "homeChrome", "homeChromeSemantics",
            "blockUpdate", "weChatShare", "playerSpeedCalculator", "homeRecommendEarlyLoadListener",
            "cloudKeyFreezeContract", "homeTabs", "commentPurify",
            "aiPlaylistDirector", "followUpRecommendBodies", "detailRelateGameComponent", "legacyStoryDanmakuBind")) {
            assertTrue(
                "installChain consumes adapted snapshot field: $field",
                installChain.contains("adapted?.$field") || installChain.contains("adapted.$field")
            )
        }
        assertFalse(hook.contains("adaptedChrome"))
    }

    fun retryGuardsAndMainThreadInstallationRemain() {
        val hook = HookEntrySource.hookEntry()
        val featureInstaller = HookEntrySource.featureInstaller()
        val retryBlock = hook
            .after("val featureInstallCoordinator = FeatureInstallCoordinator(hookEnvironment)")
            .before("val commentTopologyEnabled =")
        val registrations = retryBlock
            .split("featureInstallCoordinator.registerRetryable(")
            .drop(1)
        assertTrue(registrations.size >= 16)
        for (installer in listOf("qualityInstaller", "popupInstaller", "classicInstaller", "sponsorInstaller",
            "pausedAdInstaller", "blockUpdateInstaller", "weChatShareInstaller", "playerSpeedInstaller",
            "homeRecommendEarlyLoadInstaller", "cloudKeyFreezeInstaller", "homeTabFilterInstaller",
            "commentPurifyInstaller", "aiDeclaredVideoInstaller", "followUpRecommendInstaller",
            "detailAppPromotionInstaller", "storyActionIconsInstaller")) {
            val registration = registrations.single { it.contains("$installer.asAdaptRetryable(") }
            assertTrue("$installer must gate retry on requiresAdaptationRetry", registration.contains("requiresRetry = { requiresAdaptationRetry }"))
        }
        // 双阶段检查与单一主线程提交收敛在 FeatureInstallCoordinator.retryAdapted 与 asAdaptRetryable。
        val asRetryable = HookEntrySource.function("asAdaptRetryable", listOf("hook/feature/FeatureInstaller.kt" to featureInstaller))
        assertEquals(2, Regex("""if \(!requiresRetry\(\)\) return false|return requiresRetry\(\)""").findAll(asRetryable.after("fun prepareAdaptedRetry(").before("override fun retryAdapted(")).count())
        val retryAdapted = HookEntrySource.function(
            "retryAdapted",
            listOf("hook/feature/FeatureInstaller.kt" to featureInstaller)
        ) { it.contains("adapted: VersionAdapter.AdaptResult?, ok: Boolean") }
        assertTrue(retryAdapted.indexOf("retryable.prepareAdaptedRetry(adapted)") in 0 until retryAdapted.indexOf("environment.postToMain"))
        assertTrue(retryAdapted.after("postToMain.invoke {").contains("if (retryable.isAdaptationRetryPending)"))
        assertTrue(featureInstaller.contains("coordinator.installAll(listOf(this))"))
        assertTrue(retryAdapted.after("if (!ok) {").contains("retryable.onAdaptationFailed()"))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (AdaptCompletionSnapshotContractTest) -> Unit) {
            val s = AdaptCompletionSnapshotContractTest()
            block(s)
        }
        runIsolated { s -> s.successCallbackReadsTheCacheExactlyOnce() }
        runIsolated { s -> s.everyInstallerConsumesTheSharedSnapshot() }
        runIsolated { s -> s.retryGuardsAndMainThreadInstallationRemain() }
    }
}
