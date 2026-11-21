package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * access_key 只允许以"状态码"离开宿主侧探测层：不缓存、不记日志、不上报原文。
 * 强力模式里只有「获取 access_key」（推荐预检）以授权为前提，经同一个合成函数读取。
 */
class BiliAccessKeyContainmentContractTest {
    private val probe = SourceContract.read("hook/feature/BiliAccessKeyProbe.kt")
    private val installer = SourceContract.read("hook/feature/AiDeclaredVideoFeatureInstaller.kt")
    private val entry = SourceContract.read("hook/HookEntry.kt")

    fun probeNeverStoresOrLogsTheToken() {
        val body = probe.after("internal class BiliAccessKeyProbe").before("companion object")
        // 令牌只在一个表达式里判空，不落到字段或变量。
        assertTrue(body.contains("(getAccessKey.invoke(token) as? String)?.isNotBlank() == true"))
        listOf("log", "report", "Log.", "println", "putString", "var ").forEach { forbidden ->
            assertFalse("探测层不得出现 $forbidden", body.contains(forbidden))
        }
    }

    fun installerReportsOnlyTheStateCode() {
        val precheck = installer.after("private fun installPrechecker(").before("private fun installRelatesFeed(")
        assertTrue(precheck.contains("environment.reportStatus(ACCESS_KEY_STATUS, state.code)"))
        assertFalse(precheck.contains("getAccessKey"))
    }

    /** 强力模式拆成两项后：只有推荐预检以授权为前提，屏蔽发布者照旧只读自己的开关。 */
    fun onlyTheRecommendationPrecheckRequiresAuthorization() {
        assertEquals(1, Regex("""precheck = aiDeclaredPrecheckEffective\(prefs\)""").findAll(entry).count())
        assertTrue(entry.contains("accessKeyAuthorized = prefs.getBoolean(FeaturePreferences.BILI_ACCESS_KEY_AUTHORIZED, false)"))
        assertTrue(entry.contains("precheck = prefs.getBoolean(FeaturePreferences.BLOCK_AI_DECLARED_VIDEOS_PRECHECK, false)"))
        val install = installer.after("override fun install(").before("private fun installPrechecker(")
        assertTrue(install.contains("if (precheck) installPrechecker("))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (BiliAccessKeyContainmentContractTest) -> Unit) {
            val s = BiliAccessKeyContainmentContractTest()
            block(s)
        }
        runIsolated { s -> s.probeNeverStoresOrLogsTheToken() }
        runIsolated { s -> s.installerReportsOnlyTheStateCode() }
        runIsolated { s -> s.onlyTheRecommendationPrecheckRequiresAuthorization() }
    }
}
