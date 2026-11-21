package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

/** User-selectable frame interpolation backend when forced mode is enabled. */
internal enum class FrameInterpolationMode(
    val wireValue: String,
    val requiresModel: Boolean
) {
    QCOM(wireValue = "qcom", requiresModel = false),
    RIFE(wireValue = "rife", requiresModel = true);

    companion object {
        const val AUTO_DEFAULT = ""
        val ALLOWED_STORAGE_VALUES: Set<String> = setOf(AUTO_DEFAULT, QCOM.wireValue, RIFE.wireValue)

        fun fromWireValue(value: String?): FrameInterpolationMode? =
            entries.firstOrNull { it.wireValue == value }

        /** Ordered by device preference: QCOM hardware path first when genuinely available, then Vulkan RIFE. */
        fun supportedCandidates(capabilities: FrameGpuCapabilities): List<FrameInterpolationMode> = buildList {
            if (capabilities.qcomMotionSupported) add(QCOM)
            if (capabilities.rifeVulkanSupported) add(RIFE)
        }

        /**
         * Resolves the stored preference against detected hardware candidates.
         * Empty/unrecognized values default to the first supported candidate on this device.
         */
        fun resolveCandidate(
            stored: String?,
            candidates: List<FrameInterpolationMode>
        ): FrameInterpolationMode? {
            if (candidates.isEmpty()) return null
            val requested = fromWireValue(stored)
            return if (requested != null && requested in candidates) requested else candidates.first()
        }
    }
}

internal data class FrameInterpolationModePolicy(
    val forceMode: Boolean = false,
    val configuredMode: String = FrameInterpolationMode.AUTO_DEFAULT
) {
    /** Forced mode disables frame-rate/inference-budget fallback between or away from interpolation backends. */
    val bypassFrameBudget: Boolean get() = forceMode

    fun resolveForcedMode(capabilities: FrameGpuCapabilities): FrameInterpolationMode? {
        if (!forceMode) return null
        val candidates = FrameInterpolationMode.supportedCandidates(capabilities)
        return FrameInterpolationMode.resolveCandidate(configuredMode, candidates)
    }
}
