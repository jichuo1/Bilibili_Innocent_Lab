package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

internal enum class NPatchManagerReachability { NOT_APPLICABLE, REACHABLE, UNKNOWN }
internal data class NPatchRouteHealth(val route: String, val granted: Int, val completed: Int) {
    val grantedPercent: Int? get() = if (completed == 0) null else granted * 100 / completed
}
internal data class NPatchHealth(
    val observedBoots: Int,
    val grantedBoots: Int,
    val retryCount: Int,
    val measuredBoots: Int,
    val p50Ms: Long?,
    val maxMs: Long?,
    val routes: List<NPatchRouteHealth>
) {
    companion object {
        fun summarize(samples: List<NPatchStartupEvidence>): NPatchHealth {
            val measured = samples.filter { it.installMs != null }.map { it.totalMs }
            return NPatchHealth(samples.size, samples.count { it.result == "granted" },
                samples.sumOf { it.attempt - 1 }, measured.size, NPatchStartupCodec.p50(measured), measured.maxOrNull(),
                listOf("provider", "binder", "local", "broadcast").map { route ->
                    val observed = samples.flatMap { it.routes }.filter { it.route == route }
                    NPatchRouteHealth(route, observed.count { it.result == "granted" }, observed.size)
                })
        }
    }
}
