package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import org.junit.Assert.assertEquals
import org.junit.Test

class TeenagersModeFeatureInstallerTest {

    private val statuses = mutableListOf<Pair<String, String>>()
    private val environment = HookEnvironment(
        processName = "tv.danmaku.bili",
        classLoader = javaClass.classLoader,
        hookPoints = HookPointRegistry(javaClass.classLoader),
        registrar = TestHookRegistrar,
        logInfo = { _, _ -> },
        logError = { _, _ -> },
        reportStatus = { channel, status -> statuses += channel to status }
    )

    fun `installs only adapted prompt activity entry`() {
        val result = TeenagersModeFeatureInstaller(
            enabled = true,
            points = VersionAdapter.TeenagersModePoints(
                listOf(
                    VersionAdapter.HookPoint(
                        "com.bilibili.teenagersmode.ui.TeenagersModeDialogActivity",
                        "onCreate",
                        listOf("android.os.Bundle")
                    )
                )
            )
        ).install(environment)

        assertEquals(FeatureInstallResult.Installed(1), result)
        assertEquals(listOf("teenagers_mode_status" to "success"), statuses)
    }

    fun `rejects missing adapter point instead of broad activity hook`() {
        val result = TeenagersModeFeatureInstaller(enabled = true, points = null)
            .install(environment)

        assertEquals(FeatureInstallResult.Skipped("missing-adapter-point"), result)
        assertEquals(
            listOf("teenagers_mode_status" to "missing-adapter-point"),
            statuses
        )
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (TeenagersModeFeatureInstallerTest) -> Unit) {
            val s = TeenagersModeFeatureInstallerTest()
            block(s)
        }
        runIsolated { s -> s.`installs only adapted prompt activity entry`() }
        runIsolated { s -> s.`rejects missing adapter point instead of broad activity hook`() }
    }
}
