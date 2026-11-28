package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPreset
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostHomeCardSpacingSpec
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Constructor

class HostVideoCardSharedInstallerTest {
    private class Recorder(val failMeasurement: Boolean = false) : HookRegistrar {
        val ids = mutableListOf<String>()
        override fun exact(id: String, owner: Class<*>, methodName: String, vararg parameterTypes: Class<*>, block: ModernMemberHookCreator.() -> Unit) {
            ids += id
            if (failMeasurement && id.endsWith(".measure")) error("Simulated measurement hook failure")
        }
        override fun first(id: String, className: String, methodName: String, block: ModernMemberHookCreator.() -> Unit) = error("Unexpected first")
        override fun all(id: String, className: String, methodName: String, block: ModernMemberHookCreator.() -> Unit) = error("Unexpected all")
        override fun adapted(id: String, point: VersionAdapter.HookPoint, exceptionPolicy: HookExceptionPolicy, block: ModernMemberHookCreator.() -> Unit) = error("Unexpected adapted")
        override fun constructor(id: String, constructor: Constructor<*>, block: ModernMemberHookCreator.() -> Unit) = error("Unexpected constructor")
    }

    private fun install(
        cards: Boolean,
        background: Boolean,
        recorder: Recorder,
        spacing: HostHomeCardSpacingSpec = HostHomeCardSpacingSpec()
    ): List<FeatureInstallRecord> {
        val baseLoader = javaClass.classLoader!!
        val loader = object : ClassLoader(baseLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> =
                if (name == "com.bilibili.pegasus.PegasusFragment") throw ClassNotFoundException(name)
                else super.loadClass(name, resolve)
        }
        val environment = HookEnvironment(
            "tv.danmaku.bili",
            loader,
            HookPointRegistry(loader),
            recorder,
            logInfo = { _, _ -> },
            logError = { _, _ -> },
            reportStatus = { _, _ -> }
        )
        return FeatureInstallCoordinator(environment).installAll(
            HostVideoCardStyleFeatureInstaller.shared(
                cards,
                -1,
                HostBackgroundConfig(if (background) HostBackgroundPreset.AURORA else HostBackgroundPreset.OFF),
                spacing
            )
        )
    }

    @Test
    fun `cards spacing and background share one binding hook with independent states`() {
        val shared = Recorder()
        val bothRecords = install(true, true, shared)
        assertEquals(1, shared.ids.count { it.endsWith(".bind") })
        assertEquals(1, shared.ids.count { it.endsWith(".measure") })
        assertEquals(1, shared.ids.count { it.endsWith(".linear_measure") })
        assertEquals(listOf("host_video_cards.androidx.divider"), shared.ids.filter { it.endsWith(".divider") })
        assertEquals(FeatureInstallResult.Installed(8), bothRecords[0].result)
        assertEquals(FeatureInstallResult.Installed(5), bothRecords[1].result)

        val bgOnly = Recorder()
        val bgRecords = install(false, true, bgOnly)
        assertEquals(
            listOf(
                "host_background.native_page",
                "host_background.page",
                "host_background.list_attached",
                "host_background.background_scroll",
                "host_background.bind"
            ),
            bgOnly.ids
        )
        assertEquals(FeatureInstallResult.Skipped("disabled"), bgRecords[0].result)
        assertEquals(FeatureInstallResult.Installed(5), bgRecords[1].result)

        val cardsOnly = Recorder()
        val cardRecords = install(true, false, cardsOnly)
        assertEquals(1, cardsOnly.ids.count { it.endsWith(".bind") })
        assertEquals(FeatureInstallResult.Skipped("disabled"), cardRecords[1].result)

        val bothOff = Recorder()
        assertTrue(install(false, false, bothOff).all { it.result == FeatureInstallResult.Skipped("disabled") })
        assertTrue(bothOff.ids.isEmpty())

        val spacingOnly = Recorder()
        val spacingRecords = install(false, false, spacingOnly, HostHomeCardSpacingSpec(0, 40))
        assertEquals(
            setOf("host_video_cards.home_spacing", "host_video_cards.measure", "host_video_cards.linear_measure", "host_video_cards.bind"),
            spacingOnly.ids.toSet()
        )
        assertEquals(FeatureInstallResult.Installed(4), spacingRecords[0].result)
        assertEquals(FeatureInstallResult.Skipped("disabled"), spacingRecords[1].result)
    }

    @Test
    fun `measurement hook failures isolate cards and spacing without disabling background`() {
        val cardsFail = Recorder(failMeasurement = true)
        val cardFailRecords = install(true, true, cardsFail)
        assertEquals(FeatureInstallResult.Skipped("registration-failed"), cardFailRecords[0].result)
        assertEquals(FeatureInstallResult.Installed(5), cardFailRecords[1].result)
        assertEquals(1, cardsFail.ids.count { it.endsWith(".bind") })

        val spacingFail = Recorder(failMeasurement = true)
        val spacingFailRecords = install(false, true, spacingFail, HostHomeCardSpacingSpec(8, 12))
        assertEquals(FeatureInstallResult.Skipped("registration-failed"), spacingFailRecords[0].result)
        assertEquals(FeatureInstallResult.Installed(5), spacingFailRecords[1].result)
        assertEquals(1, spacingFail.ids.count { it.endsWith(".bind") })
    }
}
