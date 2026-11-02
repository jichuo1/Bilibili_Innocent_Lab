package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchMode
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** 专项备用机：必须先安装最新 embed 宿主并卸载 Manager，测试会改变设置并重启 B 站。 */
@RunWith(AndroidJUnit4::class)
class NPatchEmbeddedHostInstrumentedTest {
    @Test fun noManagerEmbeddedSettingsChangeDisableAndProcessReceipts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val manager = runCatching { context.packageManager.getPackageInfo("top.nkbe.npatch", 0) }.getOrNull()
        assertNull("Remove Manager for the embedded acceptance scenario", manager)
        NPatchDirectHostInstrumentedTest().verifyCurrentDirectAuthority(NPatchMode.EMBEDDED)
    }
}
