package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 宿主直接调用的动态代理不经过 Hook 链，框架的 PROTECTIVE 不兜底：
 * 回调体必须走 HostThreadGuard，未知方法必须按返回类型给零值（返回 null 会让基本类型调用处 NPE）。
 */
class HostProxyGuardContractTest {
    fun detailViewAttachCallbackIsGuarded() {
        val handler = SourceContract.read("hook/feature/DetailViewPurifyFeatureInstaller.kt")
            .after("private class Handler(").before("private companion object")
        assertTrue(handler.contains("HostThreadGuard.run(\"detail_view_purify.attached\")"))
        assertTrue(handler.contains("else -> hostProxyDefaultValue(method.returnType)"))
        assertFalse(handler.contains("else -> null"))
    }

    fun legacyMenuClickIsGuarded() {
        val proxy = SourceContract.read("hook/feature/LegacyFeedbackPanel.kt")
            .after("Proxy.newProxyInstance(").before("ctor.newInstance(")
        assertTrue(proxy.contains("HostThreadGuard.run(\"section_pick.legacy_click\") { entry.click() }"))
        assertTrue(proxy.contains("else -> hostProxyDefaultValue(method.returnType)"))
        assertFalse(proxy.contains("else -> null"))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (HostProxyGuardContractTest) -> Unit) {
            val s = HostProxyGuardContractTest()
            block(s)
        }
        runIsolated { s -> s.detailViewAttachCallbackIsGuarded() }
        runIsolated { s -> s.legacyMenuClickIsGuarded() }
    }
}
