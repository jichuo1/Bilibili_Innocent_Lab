package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

/** Installed parameters are observations, never proof of the patched loader bytes or host bitness. */
internal enum class NPatchCompatibility {
    UNKNOWN, BASELINE_PARAMETERS, UNVERIFIED_PARAMETERS, EMBEDDED_LEVEL_INVALID;

    companion object {
        fun assess(environment: NPatchEnvironment): NPatchCompatibility {
            val patch = environment.patch
            if (!patch.markerPresent || !patch.metadataValid || patch.mode == NPatchMode.UNKNOWN) return UNKNOWN
            // This upper bound belongs to the pinned 833 patcher, not an assumption about future versions.
            if (patch.mode == NPatchMode.EMBEDDED && patch.loaderVersionCode == 833L &&
                patch.signatureLevel != null && patch.signatureLevel > 2) return EMBEDDED_LEVEL_INVALID
            if (patch.signatureLevel == null || patch.loaderVersionCode == null || patch.loaderApi == null ||
                environment.hostVersionCode == null || environment.androidSdk == null || environment.deviceAbis.isEmpty()) return UNKNOWN
            return if (patch.signatureLevel == 2 && patch.loaderVersionCode == 833L && patch.loaderApi == 102 &&
                environment.hostVersionCode == 8902100L && environment.hostVersionName == "8.90.2" &&
                environment.androidSdk == 36 && environment.deviceAbis.first() == "arm64-v8a") BASELINE_PARAMETERS
            else UNVERIFIED_PARAMETERS
        }
    }
}
