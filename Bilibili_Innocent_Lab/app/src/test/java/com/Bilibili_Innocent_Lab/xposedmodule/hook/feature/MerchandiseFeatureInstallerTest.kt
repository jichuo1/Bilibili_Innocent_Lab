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
        logError = { _, _ -> }
    )

    @Test
    fun `skips cleanly when disabled`() {
        assertEquals(
            FeatureInstallResult.Skipped("disabled"),
            MerchandiseFeatureInstaller(enabled = false).install(environment)
        )
    }

    @Test
    fun `skips cleanly when host component is absent`() {
        assertEquals(
            true,
            (MerchandiseFeatureInstaller(enabled = true).install(environment)
                as FeatureInstallResult.Skipped).reason.startsWith("missing:")
        )
    }
}
