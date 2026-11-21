package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.*
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import org.junit.Assert.*
import org.junit.Test

class AudioRescueFeatureInstallerTest {
    private val loader = javaClass.classLoader!!
    private class Registrar(private val failure: String? = null) : HookRegistrar by TestHookRegistrar {
        val methods = mutableListOf<String>()
        override fun exact(id: String, owner: Class<*>, methodName: String, vararg parameterTypes: Class<*>, block: ModernMemberHookCreator.() -> Unit) {
            methods += "$methodName:${parameterTypes.joinToString { it.simpleName }}"
            if (methodName == failure) error("registration rejected")
            // "write-long" fails only the timestamped overload, as on Android versions without it.
            if (failure == "write-long" && methodName == "write" && parameterTypes.any { it.simpleName == "long" })
                error("registration rejected")
        }
    }
    private fun environment(registrar: HookRegistrar, process: String = AudioRescueFeatureInstaller.PLAYER_PROCESS) =
        HookEnvironment(process, loader, HookPointRegistry(loader), registrar, { _, _ -> }, { _, _ -> }, { _, _ -> })

    fun `disabled config and unrelated processes register nothing`() {
        val registrar = Registrar()
        assertEquals(FeatureInstallResult.Skipped("disabled"), AudioRescueFeatureInstaller(AudioRescueConfig()).install(environment(registrar)))
        assertEquals(FeatureInstallResult.Skipped("non-main-process"), AudioRescueFeatureInstaller(AudioRescueConfig(true))
            .install(environment(registrar, "tv.danmaku.bili:download")))
        assertTrue(registrar.methods.isEmpty())
    }
    fun `main process video backend registers the same PCM and lifecycle hooks`() {
        val registrar = Registrar()
        assertEquals(FeatureInstallResult.Installed(10), AudioRescueFeatureInstaller(AudioRescueConfig(true))
            .install(environment(registrar, "tv.danmaku.bili")))
        assertEquals(7, registrar.methods.count { it.startsWith("write:") })
        assertTrue(registrar.methods.contains("write:ByteBuffer, int, int, long"))
        assertTrue(registrar.methods.containsAll(listOf("flush:", "stop:", "release:")))
    }
    fun `dedicated service registers seven write forms and all reset paths`() {
        val registrar = Registrar()
        assertEquals(FeatureInstallResult.Installed(10), AudioRescueFeatureInstaller(AudioRescueConfig(true)).install(environment(registrar)))
        assertEquals(7, registrar.methods.count { it.startsWith("write:") })
        assertTrue(registrar.methods.contains("write:ByteBuffer, int, int, long"))
        assertTrue(registrar.methods.containsAll(listOf("flush:", "stop:", "release:")))
        assertTrue(registrar.methods.none { it.startsWith("setVolume:") || it.startsWith("setPlaybackParams:") })
    }
    fun `missing timestamped write keeps the required entries active`() {
        val registrar = Registrar("write-long")
        assertEquals(FeatureInstallResult.Installed(9), AudioRescueFeatureInstaller(AudioRescueConfig(true)).install(environment(registrar)))
        assertEquals(6, registrar.methods.count { it.startsWith("write:") && !it.contains("long") })
        assertTrue(registrar.methods.containsAll(listOf("flush:", "stop:", "release:")))
    }
    fun `missing lifecycle or write method leaves the whole processor inactive`() {
        for (method in listOf("write", "flush", "stop", "release")) {
            assertEquals(FeatureInstallResult.Skipped("registration-failed"),
                AudioRescueFeatureInstaller(AudioRescueConfig(true)).install(environment(Registrar(method))))
        }
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (AudioRescueFeatureInstallerTest) -> Unit) {
            val s = AudioRescueFeatureInstallerTest()
            block(s)
        }
        runIsolated { s -> s.`disabled config and unrelated processes register nothing`() }
        runIsolated { s -> s.`main process video backend registers the same PCM and lifecycle hooks`() }
        runIsolated { s -> s.`dedicated service registers seven write forms and all reset paths`() }
        runIsolated { s -> s.`missing timestamped write keeps the required entries active`() }
        runIsolated { s -> s.`missing lifecycle or write method leaves the whole processor inactive`() }
    }
}
