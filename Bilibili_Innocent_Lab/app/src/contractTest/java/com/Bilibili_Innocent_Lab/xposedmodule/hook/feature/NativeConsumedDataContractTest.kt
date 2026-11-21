package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 被我们改写后交给**原生层**消费的数据必须自洽——原生层出错不留 Java 堆栈，线上只表现为黑屏/进程消失。
 *
 * - 弹幕分段：宿主 `chronoscommon.plugins.GrpcPlugin` 把 `DmSegMobileReply` 整包 `toByteArray()`
 *   交给 `libchronos.so`（9.12.0 反汇编）。抓包实测一个分段有 124 条弹幕引用唯一的会员渐变样式 60001。
 * - 解码选项：ijk 原生播放器。方案硬约束 AV1 × 强制软解必须降级 H.264。
 */
class NativeConsumedDataContractTest {
    private val danmaku = SourceContract.read("hook/feature/DanmakuPurifyFeatureInstaller.kt")
    private val codec = SourceContract.read("hook/feature/PlayerCodecForceFeatureInstaller.kt")
    private val codecPolicy = SourceContract.read("hook/feature/PlayerCodecForcePolicy.kt")

    fun removingTheGradientStyleAlsoRewritesTheDanmakuThatReferenceIt() {
        val purify = danmaku.after("private fun purify(").before("private fun purifyElems(")
        assertTrue("删样式定义时必须同时给引用它的弹幕改色", purify.contains("neutralizeVipColorful("))
        val resolve = danmaku.after("private fun resolveMembers(").before("private fun resolveVipGradualColorValue(")
        // 改色能力缺失时整项不装——不许退回"只删样式定义"。
        assertTrue(resolve.contains("elemColorfulSetter == null"))
        assertTrue(resolve.contains("elemColorfulGetter == null"))
    }

    fun av1WithForcedSoftwareDecodingIsDowngradedBeforeTheRequestIsRewritten() {
        assertTrue(codecPolicy.contains("fun effectivePreference("))
        assertTrue("请求改写必须用降级后的偏好", codec.contains("access.rewrite(original, requestPreference)"))
        assertTrue(codec.contains("RequestAccess.resolve(resolved.request, nestedVod, requestPreference)"))
        assertTrue("跟随宿主 + 强制软解也要清 AV1 位", codec.contains("|| stripAv1Only"))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (NativeConsumedDataContractTest) -> Unit) {
            val s = NativeConsumedDataContractTest()
            block(s)
        }
        runIsolated { s -> s.removingTheGradientStyleAlsoRewritesTheDanmakuThatReferenceIt() }
        runIsolated { s -> s.av1WithForcedSoftwareDecodingIsDowngradedBeforeTheRequestIsRewritten() }
    }
}
