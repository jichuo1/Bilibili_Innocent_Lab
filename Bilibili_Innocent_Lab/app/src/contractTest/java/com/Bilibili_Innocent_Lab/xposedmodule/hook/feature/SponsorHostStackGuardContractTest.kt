package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 商单跳过（详情页与 Story 共用）里直接落在宿主栈上的入口：点击监听挂在宿主窗口的 View 上，
 * 观察者代理由宿主内核直接调用，两者的逃逸异常都不在框架 PROTECTIVE 的保护范围内，会直接杀宿主进程。
 */
class SponsorHostStackGuardContractTest {
    @Test fun theHintButtonClickIsGuardedBecauseItRunsOnTheHostWindowsUiThread() {
        val overlay = SourceContract.read("hook/feature/sponsor/SponsorHintOverlay.kt")
        assertTrue(overlay.contains("setOnClickListener { HostThreadGuard.run(\"sponsorblock.hint.click\")"))
        assertFalse(overlay.contains("setOnClickListener { if (shown == hint) action(hint) }"))
    }

    @Test fun bothObserverProxiesAnswerUnhandledMethodsWithATypedZeroNeverNull() {
        for (path in listOf("hook/feature/sponsor/SponsorPlayerSessions.kt", "hook/feature/sponsor/StorySponsorSessions.kt")) {
            val proxy = SourceContract.read(path).after("Proxy.newProxyInstance(").before("val ")
            assertTrue(path, proxy.contains("hostProxyDefaultValue(method.returnType)"))
            assertFalse(path, proxy.contains("else -> null"))
            assertFalse(path, Regex("\\n\\s+null\\n\\s+\\}").containsMatchIn(proxy))
        }
    }
}
