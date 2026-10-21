package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top.HostTopBarFxController

import android.view.View
import android.view.ViewGroup
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostTopBarFxFeatureInstallerTest {

    private class Recorder : HookRegistrar by TestHookRegistrar {
        val ids = mutableListOf<String>()
        override fun adapted(
            id: String,
            point: VersionAdapter.HookPoint,
            exceptionPolicy: HookExceptionPolicy,
            block: ModernMemberHookCreator.() -> Unit
        ) {
            ids += id
        }
    }

    private fun createEnv(
        processName: String = "tv.danmaku.bili",
        recorder: HookRegistrar = TestHookRegistrar,
        statusReporter: (String, String) -> Unit = { _, _ -> },
        loader: ClassLoader = javaClass.classLoader!!,
        evidence: ((String, FeatureRuntimeStage, Int) -> Unit)? = null
    ): HookEnvironment = HookEnvironment(
        processName = processName,
        classLoader = loader,
        hookPoints = HookPointRegistry(loader),
        registrar = recorder,
        logInfo = { _, _ -> },
        logError = { _, _ -> },
        reportStatus = statusReporter,
        runtimeEvidence = evidence
    )

    @Test
    fun `skips installation when disabled`() {
        val installer = HostTopBarFxFeatureInstaller(
            liquidGlass = false,
            touchGlow = false,
            points = null
        )
        val result = installer.install(createEnv())
        assertTrue(result is FeatureInstallResult.Skipped)
        assertEquals("disabled", (result as FeatureInstallResult.Skipped).reason)
    }

    @Test
    fun `skips installation in non-main process`() {
        val installer = HostTopBarFxFeatureInstaller(
            liquidGlass = true,
            touchGlow = true,
            points = null
        )
        val result = installer.install(createEnv(processName = "tv.danmaku.bili:play"))
        assertTrue(result is FeatureInstallResult.Skipped)
        assertEquals("non-main-process", (result as FeatureInstallResult.Skipped).reason)
    }

    @Test
    fun `fragment dispatcher remains usable when direct callback point is absent`() {
        var channel = ""
        var status = ""
        val installer = HostTopBarFxFeatureInstaller(
            liquidGlass = true,
            touchGlow = true,
            points = null
        )
        val result = installer.install(createEnv(statusReporter = { c, s -> channel = c; status = s }))
        assertEquals(FeatureInstallResult.Installed(2), result)
        assertEquals(HostTopBarFxFeatureInstaller.CHANNEL_STATUS, channel)
        assertEquals("installed:legacy=2,compose=false", status)
    }

    @Test
    fun `missing direct callback and missing dispatcher report no safe entry`() {
        val unavailable = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name.startsWith("tv.danmaku.") || name.startsWith("androidx.")) throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }
        var status = ""
        val result = HostTopBarFxFeatureInstaller(true, true, null).install(createEnv(
            loader = unavailable, statusReporter = { _, value -> status = value }))
        assertEquals(FeatureInstallResult.Skipped("missing-view-created-point"), result)
        assertEquals("missing:missing-view-created-point", status)
    }

    @Test
    fun `successfully installs hook when baseOnViewCreated point is present`() {
        val recorder = Recorder()
        val stages = mutableListOf<FeatureRuntimeStage>()
        var reportedStatus = ""
        val point = VersionAdapter.HookPoint(
            className = "tv.danmaku.bili.ui.main2.basic.BaseMainFrameFragment",
            methodName = "onViewCreated",
            paramClassNames = null,
            viewField = "searchText"
        )
        val points = VersionAdapter.HomeTopBarPoints(
            gameMenu = null,
            composeGameMenu = null,
            baseOnViewCreated = point,
            defaultWordMethods = emptyList()
        )
        val installer = HostTopBarFxFeatureInstaller(
            liquidGlass = true,
            touchGlow = true,
            points = points
        )
        val result = installer.install(
            createEnv(recorder = recorder, statusReporter = { _, s -> reportedStatus = s },
                evidence = { _, stage, _ -> stages += stage })
        )
        assertTrue(result is FeatureInstallResult.Installed)
        assertEquals(3, (result as FeatureInstallResult.Installed).hookCount)
        assertEquals(listOf("host_top_bar_fx.view_created"), recorder.ids)
        assertEquals("installed:legacy=3,compose=false", reportedStatus)
        assertTrue("Registration must not report a controller attachment or material draw", stages.isEmpty())
    }

    @Test
    fun `isUnderlineIndicator identifies indicator characteristics and distinguishes dividers`() {
        open class MockView : View(null) {
            private var lp: ViewGroup.LayoutParams? = null
            override fun getLayoutParams(): ViewGroup.LayoutParams? = lp
            override fun setLayoutParams(params: ViewGroup.LayoutParams?) {
                this.lp = params
            }
        }

        // 1. Indicator dimensions with small height/width identify indicator
        val indicatorView = object : MockView() {
            override fun getId(): Int = 0x7f090001
        }
        val indicatorLp = ViewGroup.LayoutParams(0, 0)
        indicatorLp.width = 60
        indicatorLp.height = 6
        indicatorView.setLayoutParams(indicatorLp)
        assertEquals(6, indicatorView.layoutParams?.height)
        assertTrue(HostTopBarFxController.isUnderlineIndicator(indicatorView))

        // 2. Full-width divider is NOT an underline indicator
        val dividerView = object : MockView() {
            override fun getId(): Int = 0x7f090002
        }
        val dividerLp = ViewGroup.LayoutParams(0, 0)
        dividerLp.width = 1080
        dividerLp.height = 2
        dividerView.setLayoutParams(dividerLp)
        assertFalse(HostTopBarFxController.isUnderlineIndicator(dividerView))
    }
}
