package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import org.junit.Assert.*
import org.junit.Test

class PlayerBufferFeatureInstallerTest {
    private val loader = javaClass.classLoader!!
    private fun environment(process: String = "tv.danmaku.bili", capabilities: MutableList<Pair<String, FeatureInstallResult>> = mutableListOf()) =
        HookEnvironment(process, loader, HookPointRegistry(loader), TestHookRegistrar,
            { _, _ -> }, { _, _ -> }, { _, _ -> }, capabilityEvidence = { id, outcome -> capabilities.add(id to outcome) })

    fun `follow host and unknown mode never resolve register or schedule`() {
        for (mode in listOf(0, -1, Int.MAX_VALUE)) {
            val installer = PlayerBufferFeatureInstaller(mode, 30, 64, 1024, true) { error("unexpected resolution") }
            assertEquals(FeatureInstallResult.Skipped("disabled"), installer.install(environment()))
            assertTrue(installer.capabilityIds.isEmpty())
        }
    }
    fun `non main process and corrupt settings never reach native resolution`() {
        val valid = PlayerBufferFeatureInstaller(2, 30, 64, 1024, true) { error("unexpected resolution") }
        assertEquals(FeatureInstallResult.Skipped("non-main-process"), valid.install(environment("tv.danmaku.bili:ijkservice")))
        val corrupt = PlayerBufferFeatureInstaller(3, 0, 64, 1024, true) { error("unexpected resolution") }
        assertEquals(FeatureInstallResult.Skipped("invalid-settings"), corrupt.install(environment()))
    }
    fun `missing creator topology reports both whole capability dependencies`() {
        val outcomes = mutableListOf<Pair<String, FeatureInstallResult>>()
        val result = PlayerBufferFeatureInstaller(3, 30, 64, 1024, true) { null }.install(environment(capabilities = outcomes))
        assertEquals(FeatureInstallResult.Skipped("missing-host-structure"), result)
        assertEquals(setOf("player_buffer_strategy", "player_buffer_preload"), outcomes.map { it.first }.toSet())
        assertTrue(outcomes.all { it.second == result })
    }

    private fun plan() = PlayerBufferPolicy.decide(
        PlayerBufferPolicy.Settings(PlayerBufferMode.WHOLE, 30, 64, 1024, true),
        PlayerBufferPolicy.Media(true, true, true, 200L * PlayerBufferConfig.MIB, 120_000L),
        512L * PlayerBufferConfig.MIB, true)
    private fun changes(category: Int, source: Map<String, Any?>) = PlayerBufferFeatureInstaller.changes(category, source::get, plan(), "/data/user/0/tv.danmaku.bili/cache")

    fun `native categories and units cannot overwrite codec or protocol metadata`() {
        val source = mapOf<String, Any?>("max-buffer-size" to 150L * PlayerBufferConfig.MIB,
            "max-cache-time" to 3000L, "decoder-type" to 2L, "protocol_whitelist" to "host-protocols")
        assertEquals(mapOf("max-buffer-size" to 32L * PlayerBufferConfig.MIB), changes(3, source))
        assertEquals(mapOf("max-cache-time" to 30_000L, "max-cache-bytes" to 32L * PlayerBufferConfig.MIB), changes(4, source))
        assertTrue(changes(2, source).isEmpty())
        assertTrue(changes(9, source).isEmpty())
        assertEquals(2L, source["decoder-type"])
        assertEquals("host-protocols", source["protocol_whitelist"])
        assertEquals(3000L, source["max-cache-time"])
    }
    fun `unknown value types oversized consumer options and already bounded queues stay untouched`() {
        assertTrue(changes(3, mapOf("max-buffer-size" to "150000000")).isEmpty())
        assertTrue(changes(3, mapOf("max-buffer-size" to Long.MAX_VALUE)).isEmpty())
        assertTrue(changes(4, mapOf("max-cache-time" to -1L)).isEmpty())
        assertTrue(changes(3, mapOf("max-buffer-size" to 16L * PlayerBufferConfig.MIB)).isEmpty())
        assertEquals(mapOf("max-buffer-size" to 32L * PlayerBufferConfig.MIB), changes(3, mapOf("max-buffer-size" to 0L)))
    }
    fun `whole disk options require the original host private cache path and enabled cache`() {
        val source = mapOf<String, Any?>("cache_file_path" to "/data/user/0/tv.danmaku.bili/cache/ijkvideo/1/video_cache",
            "cache_map_path" to "/data/user/0/tv.danmaku.bili/cache/ijkvideo/1/video_cache_map",
            "cache_file_close" to 0L, "cache_max_capacity" to 512L * PlayerBufferConfig.MIB)
        assertEquals(mapOf("cache_file_forwards_capacity" to 200L * PlayerBufferConfig.MIB), changes(1, source))
        assertTrue(changes(1, source + ("cache_file_close" to 1L)).isEmpty())
        assertTrue(changes(1, source + ("cache_file_path" to "/sdcard/video_cache")).isEmpty())
        assertTrue(changes(1, source + ("cache_map_path" to "/data/user/0/tv.danmaku.bili/cache/../files/map")).isEmpty())
        assertTrue(changes(1, source - "cache_max_capacity").isEmpty())
        assertEquals("source-option-unsupported", PlayerBufferFeatureInstaller.cacheSourceReason((source - "cache_max_capacity")::get,
            "/data/user/0/tv.danmaku.bili/cache"))
        assertEquals("host-cache-disabled", PlayerBufferFeatureInstaller.cacheSourceReason((source + ("cache_file_close" to 1L))::get,
            "/data/user/0/tv.danmaku.bili/cache"))
        assertEquals("disk-path-unverified", PlayerBufferFeatureInstaller.cacheSourceReason((source + ("cache_file_path" to "/sdcard/video"))::get,
            "/data/user/0/tv.danmaku.bili/cache"))
        assertEquals(0L, source["cache_file_close"])
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (PlayerBufferFeatureInstallerTest) -> Unit) {
            val s = PlayerBufferFeatureInstallerTest()
            block(s)
        }
        runIsolated { s -> s.`follow host and unknown mode never resolve register or schedule`() }
        runIsolated { s -> s.`non main process and corrupt settings never reach native resolution`() }
        runIsolated { s -> s.`missing creator topology reports both whole capability dependencies`() }
        runIsolated { s -> s.`native categories and units cannot overwrite codec or protocol metadata`() }
        runIsolated { s -> s.`unknown value types oversized consumer options and already bounded queues stay untouched`() }
        runIsolated { s -> s.`whole disk options require the original host private cache path and enabled cache`() }
    }
}
