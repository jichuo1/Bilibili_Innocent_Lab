package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import org.junit.Assert.*
import org.junit.Test

class PlayerBufferWiringContractTest {
    private val source = SourceContract.read("hook/feature/PlayerBufferFeatureInstaller.kt")
    fun `buffer entry is registered inside authorized feature installation`() {
        val hook = SourceContract.read("hook/HookEntry.kt")
        assertTrue(hook.contains("PlayerBufferFeatureInstaller("))
        listOf("MODE", "SECONDS", "MEMORY_MB", "DISK_MB", "WIFI_ONLY").forEach {
            assertTrue(hook.contains("FeaturePreferences.PLAYER_BUFFER_$it"))
        }
    }
    fun `native dictionaries are copied and source scopes are restored even on host failure`() {
        assertTrue(source.contains("val copy = Bundle(source)"))
        assertTrue(source.contains("args[1] = copy"))
        assertTrue(source.contains("frame.previous == null"))
        assertTrue(source.contains("finally {"))
        assertTrue(source.contains("sessions.commit(ticket, actual)"))
        assertTrue(source.contains("sessions.current(entry)"))
        assertTrue(source.contains("!point.normalHandler.isInstance(instance)"))
    }
    fun `buffering uses native lifecycle without a polling loop or replacement URL`() {
        assertFalse(source.contains("setBufferControl("))
        assertFalse(source.contains("setOptionLong("))
        assertFalse(source.contains("postDelayed("))
        assertFalse(source.contains("scheduleAtFixedRate("))
        assertFalse(source.contains("putString(\"url\""))
        assertTrue(source.contains("ThreadPoolExecutor(1, 1"))
        assertTrue(source.contains("HostThreadGuard.run("))
    }
    fun `host network permission is checked before the scoped lint exception`() {
        val network = SourceContract.read("hook/feature/PlayerBufferNetworkState.kt")
        assertTrue(network.contains("context.checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE)"))
        assertTrue(network.indexOf("context.checkSelfPermission") < network.indexOf("manager.registerDefaultNetworkCallback"))
        assertTrue(network.contains("PackageManager.PERMISSION_GRANTED"))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (PlayerBufferWiringContractTest) -> Unit) {
            val s = PlayerBufferWiringContractTest()
            block(s)
        }
        runIsolated { s -> s.`buffer entry is registered inside authorized feature installation`() }
        runIsolated { s -> s.`native dictionaries are copied and source scopes are restored even on host failure`() }
        runIsolated { s -> s.`buffering uses native lifecycle without a polling loop or replacement URL`() }
        runIsolated { s -> s.`host network permission is checked before the scoped lint exception`() }
    }
}
