package com.bilab.plugin.api.model

/**
 * Versioning contract for the plugin ABI/SPI boundary.
 */
object PluginApiVersion {
    const val CURRENT_API_LEVEL: Int = 1
    const val MIN_SUPPORTED_API_LEVEL: Int = 1

    fun isCompatible(minApiLevel: Int, maxApiLevel: Int): Boolean =
        minApiLevel >= MIN_SUPPORTED_API_LEVEL &&
            minApiLevel <= CURRENT_API_LEVEL &&
            maxApiLevel >= CURRENT_API_LEVEL &&
            minApiLevel <= maxApiLevel
}

/**
 * Execution trust tier of a plugin package.
 */
enum class PluginTier {
    /** Pure data/rule bundle with no executable bytecode or native libraries. */
    DECLARATIVE_BUNDLE,

    /** Signature-verified executable bundle loaded under host isolation. */
    SIGNED_CODE
}

/**
 * Coarse capability levels requested by a plugin manifest.
 */
enum class PluginPermission(val level: Int) {
    L0_DATA_ONLY(0),
    L1_CONTENT_FILTER(1),
    L2_ADAPT_AND_HOOK(2),
    L3_NATIVE_OR_PRIVILEGED(3);

    fun includes(other: PluginPermission): Boolean = level >= other.level
}

/**
 * Standardized quarantine reason recorded when a plugin fails admission or runtime safety checks.
 */
enum class PluginQuarantineReason {
    INVALID_PACKAGE,
    SIGNATURE_MISMATCH,
    PERMISSION_DENIED,
    HOST_VERSION_INCOMPATIBLE,
    API_LEVEL_INCOMPATIBLE,
    ELF_ALIGNMENT_INVALID,
    BOOT_CRASH_LOOP,
    CALLBACK_FAULT_STORM
}

/**
 * Individual feature capability exposed by a plugin.
 */
data class PluginCapabilityDecl(
    val localId: String,
    val displayName: String,
    val permission: PluginPermission
)
