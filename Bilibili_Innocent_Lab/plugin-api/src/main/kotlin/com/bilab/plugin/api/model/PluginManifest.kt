package com.bilab.plugin.api.model

/**
 * Immutable plugin package descriptor validated before admission or activation.
 */
data class PluginManifest(
    val id: String,
    val versionCode: Int,
    val versionName: String,
    val displayName: String,
    val tier: PluginTier,
    val minApiLevel: Int = PluginApiVersion.CURRENT_API_LEVEL,
    val maxApiLevel: Int = PluginApiVersion.CURRENT_API_LEVEL,
    val minHostVersionCode: Long = 0L,
    val maxHostVersionCode: Long = Long.MAX_VALUE,
    val permissions: Set<PluginPermission> = emptySet(),
    val capabilities: List<PluginCapabilityDecl> = emptyList(),
    val entryClass: String? = null,
    val sha256: String? = null,
    val publicKeySha256: String? = null
) {
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (!ID_REGEX.matches(id) || id.contains("..")) {
            errors += "Invalid plugin id '$id'; expected pattern ${ID_REGEX.pattern} without '..'"
        }
        if (versionCode <= 0) {
            errors += "versionCode must be positive, got $versionCode"
        }
        if (versionName.isBlank()) {
            errors += "versionName must not be blank"
        }
        if (displayName.isBlank()) {
            errors += "displayName must not be blank"
        }
        if (minApiLevel <= 0 || maxApiLevel < minApiLevel) {
            errors += "Invalid API level range: $minApiLevel..$maxApiLevel"
        }
        if (minHostVersionCode < 0L || maxHostVersionCode < minHostVersionCode) {
            errors += "Invalid host version range: $minHostVersionCode..$maxHostVersionCode"
        }
        if (capabilities.size > MAX_CAPABILITIES) {
            errors += "Too many capabilities: ${capabilities.size} > $MAX_CAPABILITIES"
        }
        val seenCapabilityIds = HashSet<String>()
        for (cap in capabilities) {
            if (cap.localId.isBlank()) {
                errors += "Capability localId must not be blank"
            } else if (!seenCapabilityIds.add(cap.localId)) {
                errors += "Duplicate capability localId '${cap.localId}'"
            }
            if (cap.displayName.isBlank()) {
                errors += "Capability '${cap.localId}' displayName must not be blank"
            }
            if (permissions.none { it.includes(cap.permission) }) {
                errors += "Capability '${cap.localId}' requires ${cap.permission} not granted by manifest permissions"
            }
        }
        when (tier) {
            PluginTier.DECLARATIVE_BUNDLE -> {
                if (PluginPermission.L3_NATIVE_OR_PRIVILEGED in permissions) {
                    errors += "DECLARATIVE_BUNDLE cannot request ${PluginPermission.L3_NATIVE_OR_PRIVILEGED}"
                }
                if (entryClass != null) {
                    errors += "DECLARATIVE_BUNDLE must have null entryClass"
                }
            }
            PluginTier.SIGNED_CODE -> {
                if (entryClass.isNullOrBlank()) {
                    errors += "SIGNED_CODE requires non-blank entryClass"
                }
            }
        }
        if (sha256 != null && !SHA256_HEX_REGEX.matches(sha256)) {
            errors += "sha256 must be a 64-char lowercase/uppercase hex digest"
        }
        if (publicKeySha256 != null && !SHA256_HEX_REGEX.matches(publicKeySha256)) {
            errors += "publicKeySha256 must be a 64-char lowercase/uppercase hex digest"
        }
        return errors
    }

    fun isCompatibleWith(
        hostVersionCode: Long,
        apiLevel: Int = PluginApiVersion.CURRENT_API_LEVEL
    ): Boolean =
        apiLevel in minApiLevel..maxApiLevel &&
            hostVersionCode in minHostVersionCode..maxHostVersionCode

    companion object {
        const val MAX_CAPABILITIES = 8
        val ID_REGEX = Regex("[a-z0-9][a-z0-9._-]{2,63}")
        private val SHA256_HEX_REGEX = Regex("[0-9a-fA-F]{64}")
    }
}
