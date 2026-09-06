package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedLocator
import com.bapis.bilibili.app.playerunite.v1.PlayViewUniteReply
import com.bilibili.lib.media.resource.PlayConfig
import com.bilibili.lib.moss.api.MossResponseHandler
import org.junit.Assert.*
import org.junit.Test

class PlayerPortInstallerTest {
    private val statuses = mutableListOf<String>()
    private val stages = mutableListOf<FeatureRuntimeStage>()
    private fun environment(registrar: HookRegistrar, loader: ClassLoader? = javaClass.classLoader) = HookEnvironment(
        "tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
        { _, _ -> }, { _, _ -> }, { _, value -> statuses += value },
        runtimeEvidence = { _, stage, _ -> stages += stage }
    )

    @Test fun `all off and non main processes register nothing`() {
        val registrar = PlayerPortTestRegistrar()
        assertEquals(FeatureInstallResult.Skipped("disabled"),
            PlayerCapabilityFeatureInstaller(PlayerCapabilityOptions(false, false, false)).install(environment(registrar)))
        assertEquals(FeatureInstallResult.Skipped("disabled"),
            PlayerSpeedFeatureInstaller(false, 0, 0).install(environment(registrar)))
        assertEquals(FeatureInstallResult.Skipped("non-main-process"),
            PlayerSpeedFeatureInstaller(true, 200, 0).install(environment(registrar).copy(processName = "tv.danmaku.bili:web")))
        assertTrue(registrar.hooks.isEmpty())
    }

    @Test fun `capability coverage includes both response families and mini constructors`() {
        val registrar = PlayerPortTestRegistrar()
        assertEquals(FeatureInstallResult.Installed(6),
            PlayerCapabilityFeatureInstaller(PlayerCapabilityOptions(true, true, true)).install(environment(registrar)))
        assertEquals("success", statuses.single())
        assertEquals(6, registrar.hooks.size)
        val reply = PlayViewUniteReply()
        assertSame(reply, registrar.invoke("player.capability.unite.sync") { reply })
        assertEquals(setOf(2, 9, 23), reply.getPlayArcConf().arcs.keys)
        assertTrue(FeatureRuntimeStage.APPLIED in stages)
    }

    @Test fun `missing and failed registrations stay in the coverage denominator`() {
        val registrar = PlayerPortTestRegistrar("player.capability.unite.async")
        PlayerCapabilityFeatureInstaller(PlayerCapabilityOptions(true, true, true)).install(environment(registrar))
        assertEquals("partial:11/14", statuses.single())
        statuses.clear()
        val missing = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String): Class<*> {
                if (name.startsWith("com.bapis.bilibili.app.playurl.v1.")) throw ClassNotFoundException(name)
                return super.loadClass(name)
            }
        }
        PlayerCapabilityFeatureInstaller(PlayerCapabilityOptions(true, false, false))
            .install(environment(PlayerPortTestRegistrar(), missing))
        assertEquals("partial:2/4", statuses.single())
    }

    @Test fun `async adapter keeps reply identity and error completion callbacks`() {
        val registrar = PlayerPortTestRegistrar()
        PlayerCapabilityFeatureInstaller(PlayerCapabilityOptions(true, false, false)).install(environment(registrar))
        val replies = mutableListOf<Any?>()
        val errors = mutableListOf<Throwable>()
        var completed = 0
        val handler = object : MossResponseHandler {
            override fun onNext(reply: Any?) { replies += reply }
            override fun onError(error: Throwable) { errors += error }
            override fun onCompleted() { completed++ }
        }
        val reply = PlayViewUniteReply()
        val error = IllegalStateException("host error")
        val originalArgs = arrayOf<Any?>(Any(), handler)
        registrar.invoke("player.capability.unite.async", args = originalArgs) { args ->
            (args[1] as MossResponseHandler).apply { onNext(reply); onError(error); onCompleted() }
        }
        assertSame(handler, originalArgs[1])
        assertSame(reply, replies.single())
        assertTrue(reply.getPlayArcConf().arcs.getValue(9).getIsSupport())
        assertEquals(listOf(error), errors)
        assertEquals(1, completed)
        assertThrows(IllegalStateException::class.java) {
            registrar.invoke("player.capability.unite.sync") { throw error }
        }
    }

    @Test fun `mini menu changes only its own permission and does not fabricate applied evidence`() {
        val registrar = PlayerPortTestRegistrar()
        PlayerCapabilityFeatureInstaller(PlayerCapabilityOptions(false, true, false)).install(environment(registrar))
        stages.clear()
        val untouched = Any()
        registrar.invoke("player.capability.mini.1",
            args = arrayOf(false, PlayConfig.PlayConfigType.MINIPLAYER, untouched)) {
            assertEquals(true, it[0]); assertSame(untouched, it[2])
        }
        registrar.invoke("player.capability.mini.0", args = arrayOf(false, PlayConfig.PlayConfigType.OTHER)) {
            assertEquals(false, it[0])
        }
        assertFalse(FeatureRuntimeStage.APPLIED in stages)
    }

    @Test fun `disable acceleration installs only its start callback and wins over custom speed`() {
        val registrar = PlayerPortTestRegistrar()
        assertEquals(FeatureInstallResult.Installed(1),
            PlayerSpeedFeatureInstaller(true, 300, 0).install(environment(registrar)))
        assertEquals(setOf("player.speed.disable_long_press"), registrar.hooks.keys)
        var calls = 0
        assertEquals(true, registrar.invoke("player.speed.disable_long_press", args = arrayOf(Any())) { calls++; false })
        assertEquals(0, calls)
        registrar.invoke("player.speed.disable_long_press", args = arrayOf(null)) { calls++ }
        assertEquals(1, calls)
    }

    @Test fun `custom speed edits only captured float and applied requires successful readback`() {
        val registrar = PlayerPortTestRegistrar()
        assertEquals(FeatureInstallResult.Installed(1),
            PlayerSpeedFeatureInstaller(false, 275, 0).install(environment(registrar)))
        val point = requireNotNull(PlayerSpeedLocator.longPressSpeed(javaClass.classLoader!!))
        val service = Any()
        val instance = point.constructor.newInstance(service, 2.75f, null)
        registrar.invoke("player.speed.long_press_value", instance, arrayOf(service, 3f, null)) {
            assertSame(service, it[0]); assertEquals(2.75f, it[1]); assertNull(it[2])
        }
        assertTrue(FeatureRuntimeStage.APPLIED in stages)
        stages.clear()
        assertThrows(IllegalStateException::class.java) {
            registrar.invoke("player.speed.long_press_value", instance, arrayOf(service, 3f, null)) { error("host constructor failed") }
        }
        assertFalse(FeatureRuntimeStage.APPLIED in stages)
    }
}
