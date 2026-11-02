package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

/** A fresh version observation is not an activation receipt. Old module rows never become live feature evidence. */
internal data class NPatchModuleObservation(
    val processes: List<NPatchProcessSnapshot> = emptyList(),
    val versions: List<Long> = emptyList()
) {
    companion object {
        fun fromRows(rows: List<NPatchProcessSnapshot>, installedVersion: Long): NPatchModuleObservation =
            NPatchModuleObservation(
                processes = rows.takeIf { it.all { row -> row.moduleVersionCode == installedVersion } }.orEmpty(),
                versions = rows.map { it.moduleVersionCode }.distinct().sorted()
            )
    }
}

internal enum class NPatchModuleDrift {
    CURRENT_VERSION, RUNTIME_DIFFERENT, EMBEDDED_DIFFERENT, PREVIOUS_DIFFERENT, UNKNOWN;

    companion object {
        fun assess(installedVersion: Long, observed: List<Long>, previous: Long,
            embedded: NPatchEmbeddedBuildState): NPatchModuleDrift = when {
            observed.any { it > 0L && it != installedVersion } -> RUNTIME_DIFFERENT
            embedded == NPatchEmbeddedBuildState.DIFFERENT || embedded == NPatchEmbeddedBuildState.MODULE_MISSING -> EMBEDDED_DIFFERENT
            observed.isNotEmpty() && observed.all { it == installedVersion } -> CURRENT_VERSION
            previous > 0L && previous != installedVersion -> PREVIOUS_DIFFERENT
            else -> UNKNOWN
        }
    }
}
