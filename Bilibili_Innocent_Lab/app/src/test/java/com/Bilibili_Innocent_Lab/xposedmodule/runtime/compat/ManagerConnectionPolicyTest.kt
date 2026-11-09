package com.Bilibili_Innocent_Lab.xposedmodule.runtime.compat
import org.junit.Assert.*
import org.junit.Test
class ManagerConnectionPolicyTest {
    @Test fun eachAdaptedRootFamilyUsesItsActualPushProtocol() {
        listOf("LSPosed", "LSPosed-Irena", "Irena", "Vector").forEach {
            assertEquals(it, ManagerConnectionPolicy.Route.FRAMEWORK_PUSH,
                ManagerConnectionPolicy.route(it, false, ManagerConnectionPolicy.packages))
        }
    }
    @Test fun lspatchPullNeverMeansNPatchTakeover() {
        assertEquals(ManagerConnectionPolicy.Route.LSPATCH_PULL, ManagerConnectionPolicy.route("LSPatch", false, ManagerConnectionPolicy.packages))
        assertEquals(ManagerConnectionPolicy.Route.SELECTED_NPATCH, ManagerConnectionPolicy.route("", true, ManagerConnectionPolicy.packages))
        assertEquals(ManagerConnectionPolicy.Route.LSPATCH_PULL, ManagerConnectionPolicy.route("LSPatch", true, ManagerConnectionPolicy.packages))
        // NPatch embed 可与唯一安装的 LSPatch 管理器并存；仅安装列表不能抢占已选择的通道。
        assertEquals(ManagerConnectionPolicy.Route.SELECTED_NPATCH, ManagerConnectionPolicy.route("", true, setOf("org.lsposed.lspatch")))
    }
    @Test fun absentRootServiceDoesNotSelectAnInstalledNPatch() {
        assertEquals(ManagerConnectionPolicy.Route.FRAMEWORK_PUSH, ManagerConnectionPolicy.route("", false, setOf("top.nkbe.npatch")))
        assertEquals(ManagerConnectionPolicy.Route.AMBIGUOUS, ManagerConnectionPolicy.route("", false, ManagerConnectionPolicy.packages))
    }
    @Test fun onlyUnambiguousLspatchCanBeActivelyDiscoveredWithoutMetadata() {
        assertEquals(ManagerConnectionPolicy.Route.LSPATCH_PULL, ManagerConnectionPolicy.route("", false, setOf("org.lsposed.lspatch")))
        assertEquals(ManagerConnectionPolicy.Route.FRAMEWORK_PUSH, ManagerConnectionPolicy.route("unknown", false, emptySet()))
    }
}
