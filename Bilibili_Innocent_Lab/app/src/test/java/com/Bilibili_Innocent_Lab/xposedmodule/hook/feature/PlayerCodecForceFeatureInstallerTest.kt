package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.bapis.bilibili.app.playurl.v1.PlayURLMoss
import com.bapis.bilibili.app.playurl.v1.PlayViewReq
import com.bapis.bilibili.app.playerunite.v1.CodeType
import com.bapis.bilibili.app.playerunite.v1.PlayViewUniteReq
import com.bapis.bilibili.app.playerunite.v1.PlayerMoss
import com.bapis.bilibili.app.playerunite.v1.VideoVod
import com.bilibili.lib.moss.api.MossResponseHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerCodecForceFeatureInstallerTest {
    private val loader = javaClass.classLoader!!

    private fun environment(registrar: PlayerPortTestRegistrar) = HookEnvironment(
        processName = "tv.danmaku.bili",
        classLoader = loader,
        hookPoints = HookPointRegistry(loader),
        registrar = registrar,
        logInfo = { _, _ -> },
        logError = { _, _ -> },
        reportStatus = { _, _ -> }
    )

    fun `request hook copies the nested vod and sets av1 preference`() {
        val registrar = PlayerPortTestRegistrar()
        val result = PlayerCodecForceFeatureInstaller(
            PlayerCodecPreference.AV1.value,
            PlayerDecodeMode.FOLLOW_HOST.value
        ).install(environment(registrar))

        assertEquals(FeatureInstallResult.Installed(4), result)
        val source = PlayViewUniteReq(VideoVod(CodeType.CODE_UNKNOWN, 16))
        val args = arrayOf<Any?>(source)
        val actual = registrar.invoke(
            "player_codec_force.request.PlayViewUniteReq",
            PlayerMoss(), args
        ) { callbackArgs -> callbackArgs[0] } as PlayViewUniteReq

        assertFalse(actual === source)
        assertFalse(actual.getVod() === source.getVod())
        assertEquals(CodeType.CODEAV1, actual.getVod().getPreferCodecType())
        assertEquals(2064, actual.getVod().getFnval())
        assertEquals(CodeType.CODE_UNKNOWN, source.getVod().getPreferCodecType())
        assertEquals(16, source.getVod().getFnval())
    }

    fun `async and legacy request hooks preserve their independent request shapes`() {
        val registrar = PlayerPortTestRegistrar()
        PlayerCodecForceFeatureInstaller(
            PlayerCodecPreference.H265.value,
            PlayerDecodeMode.FOLLOW_HOST.value
        ).install(environment(registrar))

        val asyncSource = PlayViewUniteReq(VideoVod(CodeType.CODE_UNKNOWN, 2064))
        val callback = object : MossResponseHandler {
            override fun onNext(reply: Any?) = Unit
            override fun onError(error: Throwable) = Unit
            override fun onCompleted() = Unit
        }
        var asyncRequest: PlayViewUniteReq? = null
        registrar.invoke(
            "player_codec_force.request.PlayViewUniteReq.async",
            PlayerMoss(),
            arrayOf(asyncSource, callback)
        ) { args ->
            asyncRequest = args[0] as PlayViewUniteReq
            null
        }
        assertEquals(CodeType.CODE265, asyncRequest?.getVod()?.getPreferCodecType())
        assertEquals(16, asyncRequest?.getVod()?.getFnval())
        assertEquals(CodeType.CODE_UNKNOWN, asyncSource.getVod().getPreferCodecType())

        val legacySource = PlayViewReq()
        val legacy = registrar.invoke(
            "player_codec_force.request.PlayViewReq",
            PlayURLMoss(),
            arrayOf(legacySource)
        ) { args -> args[0] } as PlayViewReq
        assertEquals(com.bapis.bilibili.app.playurl.v1.CodeType.CODE265, legacy.getPreferCodecType())
        assertEquals(16, legacy.getFnval())
    }

    /** AV1 × 强制软解按方案硬约束降级：请求改成优先 H.264 并清 AV1 位。 */
    fun `av1 preference with forced software decoding requests h264 instead`() {
        val registrar = PlayerPortTestRegistrar()
        PlayerCodecForceFeatureInstaller(
            PlayerCodecPreference.AV1.value,
            PlayerDecodeMode.FORCE_SOFTWARE.value
        ).install(environment(registrar))
        val actual = registrar.invoke(
            "player_codec_force.request.PlayViewUniteReq",
            PlayerMoss(), arrayOf<Any?>(PlayViewUniteReq(VideoVod(CodeType.CODE_UNKNOWN, 2064)))
        ) { callbackArgs -> callbackArgs[0] } as PlayViewUniteReq
        assertEquals(CodeType.CODE264, actual.getVod().getPreferCodecType())
        assertEquals(16, actual.getVod().getFnval())
    }

    fun `decode mode registers bundle and native option guards`() {
        val registrar = PlayerPortTestRegistrar()
        val result = PlayerCodecForceFeatureInstaller(
            PlayerCodecPreference.FOLLOW_HOST.value,
            PlayerDecodeMode.FORCE_SOFTWARE.value
        ).install(environment(registrar))

        // 3 个 ijk 选项守卫 + 4 个请求入口（强制软解的硬约束：让服务端不下发 AV1 流）。
        assertEquals(FeatureInstallResult.Installed(7), result)
        assertEquals(7, registrar.hooks.size)
        assertTrue(registrar.hooks.containsKey("player_codec_force.bundle"))
        val source = PlayViewUniteReq(VideoVod(CodeType.CODE_UNKNOWN, 2064))
        val stripped = registrar.invoke(
            "player_codec_force.request.PlayViewUniteReq",
            PlayerMoss(), arrayOf<Any?>(source)
        ) { callbackArgs -> callbackArgs[0] } as PlayViewUniteReq
        // 跟随宿主：编码偏好原样，只清 AV1 能力位。
        assertEquals(CodeType.CODE_UNKNOWN, stripped.getVod().getPreferCodecType())
        assertEquals(16, stripped.getVod().getFnval())
        assertEquals(2064, source.getVod().getFnval())
        registrar.hooks.filterKeys { it.startsWith("player_codec_force.option.") }.forEach { (id, entry) ->
            val type = entry.member.parameterTypes[2]
            val args = if (type == String::class.java) {
                arrayOf<Any?>(4, "mediacodec", "1")
            } else {
                arrayOf<Any?>(4, "mediacodec", 1L)
            }
            val rewritten = registrar.invoke(
                id,
                tv.danmaku.ijk.media.player.services.IjkMediaPlayerItemClient(),
                args
            ) { callbackArgs -> callbackArgs[2] }
            assertEquals(if (type == String::class.java) "0" else 0L, rewritten)
        }
        registrar.hooks.filterKeys { it.startsWith("player_codec_force.option.") }.forEach { (id, entry) ->
            val type = entry.member.parameterTypes[2]
            val args = if (type == String::class.java) {
                arrayOf<Any?>(4, "mediacodec", "future-mode")
            } else {
                arrayOf<Any?>(4, "mediacodec", 2L)
            }
            val unchanged = registrar.invoke(
                id,
                tv.danmaku.ijk.media.player.services.IjkMediaPlayerItemClient(),
                args
            ) { callbackArgs -> callbackArgs[2] }
            assertEquals(if (type == String::class.java) "future-mode" else 2L, unchanged)
        }
    }

    fun `coordinator records codec and decode leaves without unverified overwrite`() {
        val registrar = PlayerPortTestRegistrar()
        val events = mutableListOf<FeatureInstallRecord>()
        val record = FeatureInstallCoordinator(
            environment(registrar).copy(installationEvidence = { events += it })
        ).installAll(
            listOf(
                PlayerCodecForceFeatureInstaller(
                    PlayerCodecPreference.AV1.value,
                    PlayerDecodeMode.FORCE_SOFTWARE.value
                )
            )
        ).single()

        assertEquals(FeatureInstallResult.Installed(7), record.result)
        val leaves = events.filter { it.id in setOf("player_codec_preference", "player_decode_mode") }
        assertEquals(setOf("player_codec_preference", "player_decode_mode"), leaves.map { it.id }.toSet())
        assertTrue(leaves.all { it.result !is FeatureInstallResult.Unverified })
        assertEquals(1, leaves.count { it.id == "player_codec_preference" })
        assertEquals(1, leaves.count { it.id == "player_decode_mode" })
    }

    fun `follow host installs nothing`() {
        val registrar = PlayerPortTestRegistrar()
        assertEquals(
            FeatureInstallResult.Skipped("disabled"),
            PlayerCodecForceFeatureInstaller(0, 0).install(environment(registrar))
        )
        assertTrue(registrar.hooks.isEmpty())
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (PlayerCodecForceFeatureInstallerTest) -> Unit) {
            val s = PlayerCodecForceFeatureInstallerTest()
            block(s)
        }
        runIsolated { s -> s.`request hook copies the nested vod and sets av1 preference`() }
        runIsolated { s -> s.`async and legacy request hooks preserve their independent request shapes`() }
        runIsolated { s -> s.`av1 preference with forced software decoding requests h264 instead`() }
        runIsolated { s -> s.`decode mode registers bundle and native option guards`() }
        runIsolated { s -> s.`coordinator records codec and decode leaves without unverified overwrite`() }
        runIsolated { s -> s.`follow host installs nothing`() }
    }
}
