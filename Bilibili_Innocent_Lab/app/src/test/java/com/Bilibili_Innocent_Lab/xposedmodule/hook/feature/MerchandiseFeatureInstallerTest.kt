package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class MerchandiseFeatureInstallerTest {

    private val environment = HookEnvironment(
        processName = "test",
        classLoader = javaClass.classLoader,
        hookPoints = HookPointRegistry(javaClass.classLoader),
        registrar = TestHookRegistrar,
        logInfo = { _, _ -> },
        logError = { _, _ -> },
        reportStatus = { _, _ -> }
    )

    fun `skips cleanly when disabled`() {
        assertEquals(
            FeatureInstallResult.Skipped("disabled"),
            MerchandiseFeatureInstaller(enabled = false).install(environment)
        )
    }

    fun `skips cleanly when host component is absent`() {
        assertEquals(
            true,
            (MerchandiseFeatureInstaller(enabled = true).install(environment)
                as FeatureInstallResult.Skipped).reason.startsWith("missing:")
        )
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (MerchandiseFeatureInstallerTest) -> Unit) {
            val s = MerchandiseFeatureInstallerTest()
            block(s)
        }
        runIsolated { s -> s.`skips cleanly when disabled`() }
        runIsolated { s -> s.`skips cleanly when host component is absent`() }
    }
}
