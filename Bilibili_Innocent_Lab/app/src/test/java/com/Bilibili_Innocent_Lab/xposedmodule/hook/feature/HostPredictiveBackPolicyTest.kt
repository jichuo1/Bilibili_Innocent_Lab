package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostPredictiveBackPolicyTest {
    private open class PlainStandardActivity : ComponentActivity()

    private class ChildStandardActivity : PlainStandardActivity()

    private class CustomOnBackActivity : ComponentActivity() {
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onBackPressed() {
            // 模拟业务页拦截返回键（如退出全屏或 WebView 后退）。
        }
    }

    private class CustomOnKeyDownActivity : ComponentActivity() {
        override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = false
    }

    private class CustomOnKeyUpActivity : ComponentActivity() {
        override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean = false
    }

    private class CustomDispatchKeyEventActivity : ComponentActivity() {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean = false
    }

    @Test
    fun `pure hierarchy classification accepts standard host base classes and rejects custom handlers`() {
        val standardChain = listOf(
            "com.bilibili.app.preferences.BiliPreferencesActivity",
            "com.bilibili.lib.ui.BaseAppCompatActivity",
            "com.bilibili.lib.spy.generated.a",
            "androidx.appcompat.app.AppCompatActivity",
            "androidx.fragment.app.FragmentActivity",
            "androidx.activity.ComponentActivity",
            "androidx.core.app.ComponentActivity",
            "android.app.Activity"
        )
        assertEquals(
            HostPredictiveBackDecision(true, HostPredictiveBackReason.ELIGIBLE),
            HostPredictiveBackPolicy.classifyHierarchy(
                superclassNames = standardChain,
                owners = BackMethodOwners(
                    onBackPressed = "com.bilibili.lib.ui.BaseAppCompatActivity",
                    onKeyDown = "androidx.appcompat.app.AppCompatActivity",
                    onKeyUp = "android.app.Activity",
                    onKeyLongPress = "android.app.Activity",
                    dispatchKeyEvent = "androidx.appcompat.app.AppCompatActivity"
                )
            )
        )

        listOf(
            "com.bilibili.lib.ui.BaseToolbarActivity",
            "com.bilibili.biligame.widget.BaseTranslucentActivity"
        ).forEach { passthroughBase ->
            val decision = HostPredictiveBackPolicy.classifyHierarchy(
                superclassNames = listOf("com.bilibili.sample.SampleActivity", passthroughBase) + standardChain.drop(1),
                owners = BackMethodOwners(onBackPressed = passthroughBase)
            )
            assertTrue("Expected passthrough base $passthroughBase to be eligible", decision.eligible)
        }

        // 推送/支付/授权等直接继承 Activity 的页面不强制介入。
        assertEquals(
            HostPredictiveBackDecision(false, HostPredictiveBackReason.MISSING_COMPONENT_ACTIVITY_BASE),
            HostPredictiveBackPolicy.classifyHierarchy(
                superclassNames = listOf("com.bilibili.lib.push.HuaweiMessageActivity", "android.app.Activity"),
                owners = BackMethodOwners()
            )
        )

        assertEquals(
            HostPredictiveBackReason.CUSTOM_ON_BACK_PRESSED,
            HostPredictiveBackPolicy.classifyHierarchy(
                standardChain,
                BackMethodOwners(onBackPressed = "tv.danmaku.bili.MainActivityV2")
            ).reason
        )
        assertEquals(
            HostPredictiveBackReason.CUSTOM_ON_KEY_DOWN,
            HostPredictiveBackPolicy.classifyHierarchy(
                standardChain,
                BackMethodOwners(
                    onBackPressed = "com.bilibili.lib.ui.BaseAppCompatActivity",
                    onKeyDown = "com.bilibili.opd.app.bizcommon.context.KFCAppCompatActivity"
                )
            ).reason
        )
        assertEquals(
            HostPredictiveBackReason.CUSTOM_ON_KEY_UP,
            HostPredictiveBackPolicy.classifyHierarchy(
                standardChain,
                BackMethodOwners(onKeyUp = "com.bilibili.video.story.StoryVideoActivity")
            ).reason
        )
        assertEquals(
            HostPredictiveBackReason.CUSTOM_ON_KEY_LONG_PRESS,
            HostPredictiveBackPolicy.classifyHierarchy(
                standardChain,
                BackMethodOwners(onKeyLongPress = "com.bilibili.sample.LongPressActivity")
            ).reason
        )
        assertEquals(
            HostPredictiveBackReason.CUSTOM_DISPATCH_KEY_EVENT,
            HostPredictiveBackPolicy.classifyHierarchy(
                standardChain,
                BackMethodOwners(dispatchKeyEvent = "com.bilibili.lib.projection.ProjectionFullScreenActivity")
            ).reason
        )
    }

    @Test
    fun `reflection classification inspects overrides and honors bounded cache`() {
        val policy = HostPredictiveBackPolicy(maxCacheSize = 2)

        assertEquals(
            HostPredictiveBackDecision(true, HostPredictiveBackReason.ELIGIBLE),
            policy.classify(PlainStandardActivity::class.java)
        )
        assertNotNull(policy.cachedDecisionOrNull(PlainStandardActivity::class.java.name))

        assertEquals(
            HostPredictiveBackDecision(true, HostPredictiveBackReason.ELIGIBLE),
            policy.classify(ChildStandardActivity::class.java)
        )
        assertEquals(2, policy.cacheSize())

        // 超出上限先清空旧条目再写入，避免无界膨胀。
        val customBack = policy.classify(CustomOnBackActivity::class.java)
        assertFalse(customBack.eligible)
        assertEquals(HostPredictiveBackReason.CUSTOM_ON_BACK_PRESSED, customBack.reason)
        assertEquals(1, policy.cacheSize())
        assertNull(policy.cachedDecisionOrNull(PlainStandardActivity::class.java.name))

        assertEquals(
            HostPredictiveBackReason.CUSTOM_ON_KEY_DOWN,
            policy.classify(CustomOnKeyDownActivity::class.java).reason
        )
        assertEquals(
            HostPredictiveBackReason.CUSTOM_ON_KEY_UP,
            policy.classify(CustomOnKeyUpActivity::class.java).reason
        )
        assertEquals(
            HostPredictiveBackReason.CUSTOM_DISPATCH_KEY_EVENT,
            policy.classify(CustomDispatchKeyEventActivity::class.java).reason
        )
    }

    @Test
    fun `privateFlags helper sets enable bit clears disable bit and keeps unrelated bits`() {
        val enableBit = 1 shl 2
        val disableBit = 1 shl 3
        val unrelated = (1 shl 0) or (1 shl 1) or (1 shl 7)

        assertEquals(
            unrelated or enableBit,
            HostPredictiveBackPolicy.computeEnabledPrivateFlags(
                currentFlags = unrelated or disableBit,
                enableBit = enableBit,
                disableBit = disableBit
            )
        )
        assertNull(HostPredictiveBackPolicy.unwrapActivity(null))
    }
}
