package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchMode
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchRuntimeProfiles
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 与无 Manager 的既有验收互补；显式运行会更改专项配置并冷启动真实宿主。 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 31)
class NPatchEmbeddedCoexistenceInstrumentedTest {
    @Test fun embeddedWithManagerPresentRetainsItsOwnAuthorityAndWebHooks() {
        assumeTrue("Explicit coexistence acceptance required", InstrumentationRegistry.getArguments()
            .getString("verifyEmbeddedCoexistence") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertNotNull("Install Manager for the coexistence scenario",
            NPatchRuntimeProfiles.environment(context).managerVersionCode)
        NPatchDirectHostInstrumentedTest().verifyCurrentDirectAuthority(NPatchMode.EMBEDDED)
    }
}
