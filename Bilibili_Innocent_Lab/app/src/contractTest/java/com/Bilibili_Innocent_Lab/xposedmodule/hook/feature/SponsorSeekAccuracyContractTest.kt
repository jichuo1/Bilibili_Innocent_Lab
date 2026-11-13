package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 商单跳过的 seek 必须精确落点。宿主 `seekTo(int, boolean)` 的第二个参数是 accurate：传 false 时 ijk 只落到目标之前的
 * 关键帧，真机日志里“跳到区间终点”实际退回终点前 3–5 秒，仍在广告里，而区间已被标成已跳过，之后只剩手动提示。
 * 详情页与 Story 两条路径都要用同一个常量，任何一边改回字面量 false，真机上的失效方式都是“跳了但没跳到位”。
 */
class SponsorSeekAccuracyContractTest {
    private val access = SourceContract.read("hook/adapter/SponsorPlayerAccess.kt")
    private val detail = SourceContract.read("hook/feature/sponsor/SponsorPlayerSessions.kt")
    private val story = SourceContract.read("hook/feature/sponsor/StorySponsorSessions.kt")

    @Test fun theAccurateFlagIsOneNamedConstantThatIsTrue() {
        assertTrue(access.contains("const val ACCURATE_SEEK = true"))
        assertFalse(access.contains("const val ACCURATE_SEEK = false"))
    }

    @Test fun theDetailPageSeekPassesTheConstantAndNeverALiteralFalse() {
        val seek = detail.after("override fun seek(").before("fun start()")
        assertTrue(seek.contains("access.seek.invoke(wrapper, targetMs, SponsorPlayerAccess.ACCURATE_SEEK)"))
        assertFalse(seek.contains("targetMs, false"))
    }

    @Test fun theStorySeekPassesTheConstantAndNeverALiteralFalse() {
        val seek = story.after("override fun seek(targetMs: Int)").before("fun start()")
        assertTrue(seek.contains("access.core.seek.invoke(it, targetMs, SponsorPlayerAccess.ACCURATE_SEEK)"))
        assertFalse(seek.contains("targetMs, false"))
    }
}
