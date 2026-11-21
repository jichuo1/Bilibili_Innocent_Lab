package com.Bilibili_Innocent_Lab.xposedmodule.runtime.compat
import org.junit.Assert.*
import org.junit.Test
class ManagerConnectionPolicyTest {
    fun eachAdaptedRootFamilyUsesItsActualPushProtocol() {
        listOf("LSPosed", "LSPosed-Irena", "Irena", "Vector").forEach {
            assertEquals(it, ManagerConnectionPolicy.Route.FRAMEWORK_PUSH,
                ManagerConnectionPolicy.route(it, false, ManagerConnectionPolicy.packages))
        }
    }
    fun lspatchPullNeverMeansNPatchTakeover() {
        assertEquals(ManagerConnectionPolicy.Route.LSPATCH_PULL, ManagerConnectionPolicy.route("LSPatch", false, ManagerConnectionPolicy.packages))
        assertEquals(ManagerConnectionPolicy.Route.SELECTED_NPATCH, ManagerConnectionPolicy.route("", true, ManagerConnectionPolicy.packages))
        assertEquals(ManagerConnectionPolicy.Route.LSPATCH_PULL, ManagerConnectionPolicy.route("LSPatch", true, ManagerConnectionPolicy.packages))
        // NPatch embed 可与唯一安装的 LSPatch 管理器并存；仅安装列表不能抢占已选择的通道。
        assertEquals(ManagerConnectionPolicy.Route.SELECTED_NPATCH, ManagerConnectionPolicy.route("", true, setOf("org.lsposed.lspatch")))
    }
    fun absentRootServiceDoesNotSelectAnInstalledNPatch() {
        assertEquals(ManagerConnectionPolicy.Route.FRAMEWORK_PUSH, ManagerConnectionPolicy.route("", false, setOf("top.nkbe.npatch")))
        assertEquals(ManagerConnectionPolicy.Route.AMBIGUOUS, ManagerConnectionPolicy.route("", false, ManagerConnectionPolicy.packages))
    }
    fun onlyUnambiguousLspatchCanBeActivelyDiscoveredWithoutMetadata() {
        assertEquals(ManagerConnectionPolicy.Route.LSPATCH_PULL, ManagerConnectionPolicy.route("", false, setOf("org.lsposed.lspatch")))
        assertEquals(ManagerConnectionPolicy.Route.FRAMEWORK_PUSH, ManagerConnectionPolicy.route("unknown", false, emptySet()))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (ManagerConnectionPolicyTest) -> Unit) {
            val s = ManagerConnectionPolicyTest()
            block(s)
        }
        runIsolated { s -> s.eachAdaptedRootFamilyUsesItsActualPushProtocol() }
        runIsolated { s -> s.lspatchPullNeverMeansNPatchTakeover() }
        runIsolated { s -> s.absentRootServiceDoesNotSelectAnInstalledNPatch() }
        runIsolated { s -> s.onlyUnambiguousLspatchCanBeActivelyDiscoveredWithoutMetadata() }
    }
}
