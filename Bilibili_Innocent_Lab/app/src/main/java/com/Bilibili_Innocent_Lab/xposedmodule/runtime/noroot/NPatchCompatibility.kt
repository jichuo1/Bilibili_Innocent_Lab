package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

/**
 * Loader builds that have real-device evidence in this repository.
 *
 * A build joins this set only together with that evidence (see docs/npatch-release-support.md): source
 * contracts, JVM tests and emulator results show that a build is compatible in the narrow sense they test,
 * but they do not make it a verified loader. Builds seen in the wild and not listed here (for example upstream
 * CI build 851 or release 857) therefore stay [NPatchCompatibility.UNVERIFIED_PARAMETERS].
 */
internal object VerifiedNPatchLoaders {
    /** The fixed 833 patcher: Java binder fix plus native TLS fix, verified on B站 8.90.2 / Android 16 / arm64. */
    val baseline: Set<Long> = setOf(833L)

    /** Loaders whose `--embed` signature-bypass upper bound (High, 2) was checked against the patcher. */
    val embeddedLevelCap: Set<Long> = baseline

    /** Loaders for which `LSPLoader.getActiveModuleApkPaths` and the module class loader shape were exercised. */
    val roamingCacheAdapter: Set<Long> = baseline

    fun contains(set: Set<Long>, code: Long?): Boolean = code != null && code in set
}

/** Installed parameters are observations, never proof of the patched loader bytes or host bitness. */
internal enum class NPatchCompatibility {
    UNKNOWN, BASELINE_PARAMETERS, UNVERIFIED_PARAMETERS, EMBEDDED_LEVEL_INVALID;

    companion object {
        fun assess(environment: NPatchEnvironment): NPatchCompatibility {
            val patch = environment.patch
            if (!patch.markerPresent || !patch.metadataValid || patch.mode == NPatchMode.UNKNOWN) return UNKNOWN
            // This upper bound belongs to the pinned patchers listed in VerifiedNPatchLoaders, not to future versions.
            if (patch.mode == NPatchMode.EMBEDDED &&
                VerifiedNPatchLoaders.contains(VerifiedNPatchLoaders.embeddedLevelCap, patch.loaderVersionCode) &&
                patch.signatureLevel != null && patch.signatureLevel > 2) return EMBEDDED_LEVEL_INVALID
            if (patch.signatureLevel == null || patch.loaderVersionCode == null || patch.loaderApi == null ||
                environment.hostVersionCode == null || environment.androidSdk == null || environment.deviceAbis.isEmpty()) return UNKNOWN
            return if (patch.signatureLevel == 2 &&
                VerifiedNPatchLoaders.contains(VerifiedNPatchLoaders.baseline, patch.loaderVersionCode) &&
                patch.loaderApi == 102 &&
                environment.hostVersionCode == 8902100L && environment.hostVersionName == "8.90.2" &&
                environment.androidSdk == 36 && environment.deviceAbis.first() == "arm64-v8a") BASELINE_PARAMETERS
            else UNVERIFIED_PARAMETERS
        }
    }
}
