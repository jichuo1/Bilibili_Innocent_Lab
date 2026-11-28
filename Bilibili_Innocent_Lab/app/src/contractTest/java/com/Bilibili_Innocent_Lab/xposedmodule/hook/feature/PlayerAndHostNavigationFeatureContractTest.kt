package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放器原生层消费数据与宿主导航开关的源码契约护栏：
 * - 弹幕会员渐变去样式必须同步重写引用它的弹幕，AV1 × 强制软解必须降级 H.264；
 * - 缓冲生命周期、网络权限预检与预测性返回接线保持完整。
 */
class PlayerAndHostNavigationFeatureContractTest {

    @Test
    fun `native consumed danmaku and av1 codec requests maintain self-consistent downgrade wiring`() {
        val danmaku = SourceContract.read("hook/feature/DanmakuPurifyFeatureInstaller.kt")
        val codec = SourceContract.read("hook/feature/PlayerCodecForceFeatureInstaller.kt")
        val codecPolicy = SourceContract.read("hook/feature/PlayerCodecForcePolicy.kt")

        val purify = danmaku.after("private fun purify(").before("private fun purifyElems(")
        assertTrue("删样式定义时必须同时给引用它的弹幕改色", purify.contains("neutralizeVipColorful("))
        val resolve = danmaku.after("private fun resolveMembers(").before("private fun resolveVipGradualColorValue(")
        // 改色能力缺失时整项不装——不许退回"只删样式定义"。
        assertTrue(resolve.contains("elemColorfulSetter == null"))
        assertTrue(resolve.contains("elemColorfulGetter == null"))

        assertTrue(codecPolicy.contains("fun effectivePreference("))
        assertTrue("请求改写必须用降级后的偏好", codec.contains("access.rewrite(original, requestPreference)"))
        assertTrue(codec.contains("RequestAccess.resolve(resolved.request, nestedVod, requestPreference)"))
        assertTrue("跟随宿主 + 强制软解也要清 AV1 位", codec.contains("|| stripAv1Only"))
    }

    @Test
    fun `player buffer lifecycle and host predictive back stay wired through guarded entry points`() {
        val hook = SourceContract.read("hook/HookEntry.kt")
        assertTrue(hook.contains("PlayerBufferFeatureInstaller("))
        listOf("MODE", "SECONDS", "MEMORY_MB", "DISK_MB", "WIFI_ONLY").forEach {
            assertTrue(hook.contains("FeaturePreferences.PLAYER_BUFFER_$it"))
        }

        val bufferSource = SourceContract.read("hook/feature/PlayerBufferFeatureInstaller.kt")
        assertTrue(bufferSource.contains("val copy = Bundle(source)"))
        assertTrue(bufferSource.contains("args[1] = copy"))
        assertTrue(bufferSource.contains("frame.previous == null"))
        assertTrue(bufferSource.contains("finally {"))
        assertTrue(bufferSource.contains("sessions.commit(ticket, actual)"))
        assertTrue(bufferSource.contains("sessions.current(entry)"))
        assertTrue(bufferSource.contains("!point.normalHandler.isInstance(instance)"))

        assertFalse(bufferSource.contains("setBufferControl("))
        assertFalse(bufferSource.contains("setOptionLong("))
        assertFalse(bufferSource.contains("postDelayed("))
        assertFalse(bufferSource.contains("scheduleAtFixedRate("))
        assertFalse(bufferSource.contains("putString(\"url\""))
        assertTrue(bufferSource.contains("ThreadPoolExecutor(1, 1"))
        assertTrue(bufferSource.contains("HostThreadGuard.run("))

        val network = SourceContract.read("hook/feature/PlayerBufferNetworkState.kt")
        assertTrue(network.contains("context.checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE)"))
        assertTrue(network.indexOf("context.checkSelfPermission") < network.indexOf("manager.registerDefaultNetworkCallback"))
        assertTrue(network.contains("PackageManager.PERMISSION_GRANTED"))

        val targetInstall = hook.after("systemEnvInstaller,").before("classicInstaller,")
        assertTrue(targetInstall.contains("HostPredictiveBackFeatureInstaller("))
        assertTrue(targetInstall.contains("FeaturePreferences.HOST_PREDICTIVE_BACK_ENABLED"))

        val sections = SourceContract.read("ui/activity/SettingsCardSections.kt")
        val hostBackSection = sections.after("SettingsCardSections:SystemEnvDetectionTip")
            .before("if (!PredictiveBack.isSystemEnforced)")
        assertTrue(hostBackSection.contains("SettingsCardSections:HostPredictiveBack"))
        assertTrue(hostBackSection.contains("FeaturePreferences.HOST_PREDICTIVE_BACK_ENABLED"))
        assertTrue(hostBackSection.contains("applyModeledToggle(hostPredictiveBackKey, checked, button)"))

        val presenter = SourceContract.read("ui/activity/SettingCommandsPresenter.kt")
        val executeToggle = presenter.after("internal fun MainActivity.executeModeledToggle(")
            .before("internal val modeledToggleKeys")
        assertTrue(executeToggle.contains("FeaturePreferences.HOST_PREDICTIVE_BACK_ENABLED"))
        val modeledKeys = presenter.after("internal val modeledToggleKeys =")
            .before("internal fun MainActivity.synchronizeSettingField")
        assertTrue(modeledKeys.contains("FeaturePreferences.HOST_PREDICTIVE_BACK_ENABLED"))
    }
}
