package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFrameLockFeatureInstallerTest {
    /** Mirrors `kntr.base.config.KConfig#ff(String, boolean)`: the host answers with whatever the cloud sent. */
    class FakeConfig {
        var cloud = true
        fun ff(key: String, default: Boolean): Boolean = if (key == KEY) cloud else default
    }

    class OtherShape { fun ff(key: String, default: Boolean, extra: Int): Boolean = default }
    class FakeViewModel

    private fun loader(config: Class<*>? = FakeConfig::class.java, frame: Boolean = true) =
        object : ClassLoader(HomeFrameLockFeatureInstallerTest::class.java.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> = when (name) {
                "kntr.base.config.KConfig" -> config ?: throw ClassNotFoundException(name)
                HomeFrameTabLocator.VIEW_MODEL_CLASS -> if (frame) FakeViewModel::class.java else throw ClassNotFoundException(name)
                else -> super.loadClass(name, resolve)
            }
        }

    private fun environment(
        registrar: HookRegistrar = TestHookRegistrar,
        process: String = "tv.danmaku.bili",
        loader: ClassLoader? = loader(),
        status: (String, String) -> Unit = { _, _ -> },
        evidence: (String, FeatureRuntimeStage, Int) -> Unit = { _, _, _ -> }
    ) = HookEnvironment(
        processName = process,
        classLoader = loader,
        hookPoints = HookPointRegistry(loader),
        registrar = registrar,
        logInfo = { _, _ -> },
        logError = { _, _ -> },
        reportStatus = status,
        runtimeEvidence = evidence
    )

    private fun call(registrar: PlayerPortTestRegistrar, key: Any?, original: Boolean): Any? =
        registrar.invoke("home_frame_lock.flag", args = arrayOf(key, false)) { original }

    @Test fun `cloud request for the new frame is turned back into false`() {
        val registrar = PlayerPortTestRegistrar()
        val stages = mutableListOf<FeatureRuntimeStage>()
        val statuses = mutableListOf<String>()
        val result = HomeFrameLockFeatureInstaller(true)
            .install(environment(registrar, status = { _, value -> statuses += value }) { id, stage, _ ->
                assertEquals(HomeFrameLockFeatureInstaller.ID, id)
                stages += stage
            })
        assertEquals(FeatureInstallResult.Installed(1), result)
        assertEquals(1, registrar.hooks.size)
        assertEquals(false, call(registrar, KEY, true))
        assertEquals(false, call(registrar, KEY, true))
        assertEquals(listOf(FeatureRuntimeStage.ADAPTED, FeatureRuntimeStage.OBSERVED, FeatureRuntimeStage.APPLIED), stages)
        assertTrue("applied:blocked-cloud-switch" in statuses)
    }

    @Test fun `a flag the cloud already left off is observed but not reported as applied`() {
        val registrar = PlayerPortTestRegistrar()
        val stages = mutableListOf<FeatureRuntimeStage>()
        HomeFrameLockFeatureInstaller(true).install(environment(registrar) { _, stage, _ -> stages += stage })
        assertEquals(false, call(registrar, KEY, false))
        assertTrue(FeatureRuntimeStage.OBSERVED in stages)
        assertFalse(FeatureRuntimeStage.APPLIED in stages)
    }

    @Test fun `every other cloud flag keeps the value the host computed`() {
        val registrar = PlayerPortTestRegistrar()
        val stages = mutableListOf<FeatureRuntimeStage>()
        HomeFrameLockFeatureInstaller(true).install(environment(registrar) { _, stage, _ -> stages += stage })
        for (key in listOf(null, "use_new_home_frame", "dd.use_new_home_frame_v2", "dd.home_content_compat_enable",
            "DD.USE_NEW_HOME_FRAME", "dd.use_new_home_frame ")) {
            assertEquals(true, call(registrar, key, true))
        }
        assertEquals(listOf(FeatureRuntimeStage.ADAPTED), stages)
    }

    @Test fun `disabled secondary process and hosts without the new frame register nothing`() {
        val registrar = PlayerPortTestRegistrar()
        assertEquals(FeatureInstallResult.Skipped("disabled"), HomeFrameLockFeatureInstaller(false).install(environment(registrar)))
        assertEquals(FeatureInstallResult.Skipped("non-main-process"),
            HomeFrameLockFeatureInstaller(true).install(environment(registrar, process = "tv.danmaku.bili:download")))
        val statuses = mutableListOf<String>()
        assertEquals(FeatureInstallResult.Skipped("not-applicable-host"),
            HomeFrameLockFeatureInstaller(true).install(
                environment(registrar, loader = loader(frame = false), status = { _, value -> statuses += value })))
        assertEquals(listOf("not-applicable-host"), statuses)
        assertTrue(registrar.hooks.isEmpty())
    }

    @Test fun `a missing or reshaped config boundary fails closed instead of hooking something else`() {
        assertEquals(FeatureInstallResult.Skipped("missing-class-loader"),
            HomeFrameLockFeatureInstaller(true).install(environment(loader = null)))
        assertEquals(FeatureInstallResult.Skipped("missing-config-boundary"),
            HomeFrameLockFeatureInstaller(true).install(environment(loader = loader(config = null))))
        val registrar = PlayerPortTestRegistrar()
        assertEquals(FeatureInstallResult.Skipped("missing-config-boundary"),
            HomeFrameLockFeatureInstaller(true).install(environment(registrar, loader = loader(config = OtherShape::class.java))))
        assertTrue(registrar.hooks.isEmpty())
    }

    @Test fun `a registration failure is reported and not counted as installed`() {
        assertEquals(FeatureInstallResult.Skipped("registration-failed"),
            HomeFrameLockFeatureInstaller(true).install(environment(PlayerPortTestRegistrar("home_frame_lock.flag"))))
    }

    private companion object {
        const val KEY = "dd.use_new_home_frame"
    }
}
