package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchEmbeddedBuild
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchEmbeddedBuildState
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 显式指定安装顺序的期望值；只读已安装 APK，不改变账号、配置或安装状态。 */
@RunWith(AndroidJUnit4::class)
class NPatchEmbeddedBuildInstrumentedTest {
    @Test fun installedEmbeddedModuleMatchesExpectedBuildState() {
        val argument = InstrumentationRegistry.getArguments().getString("embeddedBuild")
        assumeTrue("Explicit installed package expectation required", argument != null)
        val expected = NPatchEmbeddedBuildState.valueOf(argument!!)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val actual = NPatchEmbeddedBuild.read(instrumentation.targetContext)
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nEmbedded package comparison: $actual\n") })
        assertEquals(expected, actual)
    }
}
