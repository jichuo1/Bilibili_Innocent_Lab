package com.Bilibili_Innocent_Lab.xposedmodule.runtime.compat

/** 管理器选择与兼容开关无关。只有有公开拉取协议的家族才主动拉取。 */
internal object ManagerConnectionPolicy {
    enum class Route { FRAMEWORK_PUSH, LSPATCH_PULL, SELECTED_NPATCH, AMBIGUOUS }
    val packages = setOf("org.lsposed.manager", "org.matrix.vector.manager", "org.lsposed.lspatch", "top.nkbe.npatch")
    fun route(name: String, noRootSelected: Boolean, installed: Set<String>): Route = when {
        noRootSelected -> Route.SELECTED_NPATCH
        name.contains("lspatch", true) -> Route.LSPATCH_PULL
        name.contains("lsposed", true) || name.contains("irena", true) || name.contains("vector", true) -> Route.FRAMEWORK_PUSH
        installed == setOf("org.lsposed.lspatch") -> Route.LSPATCH_PULL
        installed.size > 1 -> Route.AMBIGUOUS
        else -> Route.FRAMEWORK_PUSH
    }
}
