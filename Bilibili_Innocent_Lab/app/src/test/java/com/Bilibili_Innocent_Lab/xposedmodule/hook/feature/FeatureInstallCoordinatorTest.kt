package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FeatureInstallCoordinatorTest {

    @Test
    fun `keeps order and isolates installer failure`() {
        val order = mutableListOf<String>()
        val loggedFailures = mutableListOf<String>()
        val environment = HookEnvironment(
            processName = "test",
            classLoader = javaClass.classLoader,
            hookPoints = HookPointRegistry(javaClass.classLoader),
            log = { message, _ -> loggedFailures += message }
        )
        val records = FeatureInstallCoordinator(environment).installAll(
            listOf(
                FunctionalFeatureInstaller("first") {
                    order += "first"
                    FeatureInstallResult.Installed()
                },
                FunctionalFeatureInstaller("broken") {
                    order += "broken"
                    error("expected")
                },
                FunctionalFeatureInstaller("last") {
                    order += "last"
                    FeatureInstallResult.Skipped("disabled")
                }
            )
        )

        assertEquals(listOf("first", "broken", "last"), order)
        assertEquals(listOf("first", "broken", "last"), records.map { it.id })
        assertNull(records[0].failure)
        assertNotNull(records[1].failure)
        assertEquals(FeatureInstallResult.Skipped("disabled"), records[2].result)
        assertEquals(listOf("Feature installer failed: broken"), loggedFailures)
    }
}
