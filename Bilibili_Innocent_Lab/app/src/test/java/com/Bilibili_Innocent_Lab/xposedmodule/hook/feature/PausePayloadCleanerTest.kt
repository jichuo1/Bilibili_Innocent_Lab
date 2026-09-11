package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.bapis.bilibili.app.viewunite.v1.PlayPauseReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 暂停页第四道（协议响应层）。
 *
 * 这一层的全部价值在于"只清 `ads`、绝不碰 `bar`"——暂停进度条是正常功能。
 * P1/P2/P3 都是按宿主实现定位（混淆名一漂移就落空），这一层打在未混淆的
 * protobuf 生成类上，两者互为保底。
 */
class PausePayloadCleanerTest {

    private val evidence = mutableListOf<Pair<String, FeatureRuntimeStage>>()
    private val errors = mutableListOf<String>()

    private fun environment() = HookEnvironment(
        processName = "tv.danmaku.bili",
        classLoader = javaClass.classLoader,
        hookPoints = HookPointRegistry(javaClass.classLoader),
        registrar = TestHookRegistrar,
        logInfo = { _, _ -> },
        logError = { key, _ -> errors += key },
        reportStatus = { _, _ -> },
        runtimeEvidence = { id, stage, _ -> evidence += id to stage }
    )

    private fun cleaner() =
        requireNotNull(PausePayloadCleaner.resolve(PlayPauseReply::class.java))

    @Test fun `clears the ad payload and keeps the pause bar`() {
        val original = PlayPauseReply("广告", "进度条")
        val updated = cleaner().clearAds(original, environment()) as PlayPauseReply

        assertNotSame(original, updated)
        assertFalse(updated.hasAds())
        // 最重要的一条：进度条必须原样留下。
        assertTrue(updated.hasBar())
        assertEquals("进度条", updated.bar)
        // 原响应一字未动。
        assertTrue(original.hasAds())
    }

    /** 只有进度条、没有广告的响应：不分配副本，也不记 APPLIED。 */
    @Test fun `a reply with only a pause bar is returned unchanged`() {
        val original = PlayPauseReply(null, "进度条")
        assertSame(original, cleaner().clearAds(original, environment()))
        assertTrue(evidence.none { it.second == FeatureRuntimeStage.APPLIED })
    }

    @Test fun `evidence is reported only when an ad payload was actually removed`() {
        cleaner().clearAds(PlayPauseReply("广告", "进度条"), environment())
        val stages = evidence.filter { it.first == PausedAdFeatureInstaller.ID }.map { it.second }
        assertTrue(FeatureRuntimeStage.OBSERVED in stages)
        assertTrue(FeatureRuntimeStage.APPLIED in stages)
    }

    /** 默认实例是进程级单例，改写它会污染整个宿主进程。 */
    @Test fun `the default instance is never rewritten`() {
        val original = PlayPauseReply.getDefaultInstance()
        assertSame(original, cleaner().clearAds(original, environment()))
    }

    @Test fun `a foreign payload is passed through untouched`() {
        val original = "not a reply"
        assertSame(original, cleaner().clearAds(original, environment()))
        assertTrue(evidence.isEmpty())
    }

    /** build 失败必须交付原响应——暂停页宁可有广告，也不能白屏。 */
    @Test fun `fails open to the original reply when the copy cannot be built`() {
        val original = PlayPauseReply("广告", "进度条", true)
        assertSame(original, cleaner().clearAds(original, environment()))
        assertTrue("paused_p4_clean_err" in errors)
        assertTrue(evidence.any { it.second == FeatureRuntimeStage.ERROR })
    }

    /** 形状不符（没有 hasAds / clearAds）时整条降级，不硬上。 */
    @Test fun `resolution fails closed when the reply shape does not match`() {
        assertEquals(null, PausePayloadCleaner.resolve(String::class.java))
        assertNotNull(PausePayloadCleaner.resolve(PlayPauseReply::class.java))
    }

    /** 判据只许动 ads；`hasBar` 常量留在这里就是为了钉住"它不在改写范围内"。 */
    @Test fun `the policy touches the ad payload only`() {
        assertEquals("hasAds", PausedAdFeatureInstaller.HAS_ADS)
        assertEquals("clearAds", PausedAdFeatureInstaller.CLEAR_ADS)
        assertEquals("hasBar", PausedAdFeatureInstaller.PAUSE_BAR_PRESENCE)
        assertFalse(PausedAdFeatureInstaller.CLEAR_ADS.contains("Bar"))
    }
}
