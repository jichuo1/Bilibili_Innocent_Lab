package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPreset
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundTheme
import com.bilibili.lib.ui.util.MultipleThemeUtils
import org.junit.Assert.*
import org.junit.Test

class HostBackgroundThemeTest {
    private val loader = javaClass.classLoader!!
    private fun environment(registrar: PlayerPortTestRegistrar, process: String = "tv.danmaku.bili", hostLoader: ClassLoader = loader) =
        HookEnvironment(process, hostLoader, HookPointRegistry(hostLoader), registrar,
            logInfo = { _, _ -> }, logError = { _, _ -> }, reportStatus = { _, _ -> })

    fun celestialPresetsOverrideReadsAndPreserveTheSavedTheme() {
        MultipleThemeUtils.savedTheme = 8
        MultipleThemeUtils.followSystem = true
        for (preset in listOf(HostBackgroundPreset.STARRY, HostBackgroundPreset.NEBULA, HostBackgroundPreset.METEOR)) {
            val registrar = PlayerPortTestRegistrar()
            assertEquals(2, HostBackgroundTheme.install(environment(registrar), preset))
            assertEquals(1, registrar.invoke("host_background.theme", args = arrayOf(null)) {
                MultipleThemeUtils.getCurrentThemeId(null)
            })
            assertEquals(false, registrar.invoke("host_background.theme_follow_system", args = arrayOf(null)) {
                MultipleThemeUtils.isNightFollowSystem(null)
            })
            assertEquals(8, MultipleThemeUtils.getCurrentThemeId(null))
            assertTrue(MultipleThemeUtils.isNightFollowSystem(null))
        }
        for (preset in HostBackgroundPreset.entries.filterNot { it.isCelestial }) {
            val registrar = PlayerPortTestRegistrar()
            assertEquals(0, HostBackgroundTheme.install(environment(registrar), preset))
            assertTrue(registrar.hooks.isEmpty())
        }
    }

    fun webProcessUsesTheSamePolicyButUnrelatedProcessesRemainUntouched() {
        val web = PlayerPortTestRegistrar()
        assertEquals(2, HostBackgroundTheme.install(environment(web, "tv.danmaku.bili:web"), HostBackgroundPreset.STARRY))
        val other = PlayerPortTestRegistrar()
        assertEquals(0, HostBackgroundTheme.install(environment(other, "another.app"), HostBackgroundPreset.STARRY))
        assertTrue(other.hooks.isEmpty())
    }

    fun missingThemeApiLeavesNoHooksInstalled() {
        val unknown = object : ClassLoader(loader) {
            override fun loadClass(name: String): Class<*> {
                if (name == "com.bilibili.lib.ui.util.MultipleThemeUtils") throw ClassNotFoundException(name)
                return super.loadClass(name)
            }
        }
        val registrar = PlayerPortTestRegistrar()
        assertEquals(0, HostBackgroundTheme.install(environment(registrar, hostLoader = unknown), HostBackgroundPreset.STARRY))
        assertTrue(registrar.hooks.isEmpty())
    }

    fun originalThemeReadFailureIsNotSilenced() {
        val registrar = PlayerPortTestRegistrar()
        HostBackgroundTheme.install(environment(registrar), HostBackgroundPreset.STARRY)
        val failure = IllegalStateException("Native theme read failed")
        try {
            registrar.invoke("host_background.theme", args = arrayOf(null)) { throw failure }
            fail("Native failure must still be delivered")
        } catch (caught: IllegalStateException) { assertSame(failure, caught) }
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (HostBackgroundThemeTest) -> Unit) {
            val s = HostBackgroundThemeTest()
            block(s)
        }
        runIsolated { s -> s.celestialPresetsOverrideReadsAndPreserveTheSavedTheme() }
        runIsolated { s -> s.webProcessUsesTheSamePolicyButUnrelatedProcessesRemainUntouched() }
        runIsolated { s -> s.missingThemeApiLeavesNoHooksInstalled() }
        runIsolated { s -> s.originalThemeReadFailureIsNotSilenced() }
    }
}
