package com.bilab.plugin.api.model

/**
 * Typed configuration field declared by a plugin for module-UI rendering and validation.
 */
sealed interface PluginSettingField {
    val key: String
    val title: String
    val description: String

    data class BoolField(
        override val key: String,
        override val title: String,
        val defaultValue: Boolean = false,
        override val description: String = ""
    ) : PluginSettingField

    data class IntField(
        override val key: String,
        override val title: String,
        val defaultValue: Int,
        val min: Int = Int.MIN_VALUE,
        val max: Int = Int.MAX_VALUE,
        override val description: String = ""
    ) : PluginSettingField {
        init {
            require(min <= max) { "min ($min) must be <= max ($max)" }
            require(defaultValue in min..max) { "defaultValue ($defaultValue) must be in $min..$max" }
        }
    }

    data class TextField(
        override val key: String,
        override val title: String,
        val defaultValue: String = "",
        val maxLength: Int = DEFAULT_MAX_LENGTH,
        override val description: String = ""
    ) : PluginSettingField {
        init {
            require(maxLength > 0) { "maxLength must be positive" }
            require(defaultValue.length <= maxLength) { "defaultValue exceeds maxLength=$maxLength" }
        }

        companion object {
            const val DEFAULT_MAX_LENGTH = 4096
        }
    }

    data class EnumField(
        override val key: String,
        override val title: String,
        val options: List<String>,
        val defaultValue: String = options.firstOrNull().orEmpty(),
        override val description: String = ""
    ) : PluginSettingField {
        init {
            require(options.isNotEmpty()) { "EnumField options must not be empty" }
            require(options.distinct().size == options.size) { "EnumField options must be unique" }
            require(defaultValue in options) { "defaultValue '$defaultValue' must be present in options" }
        }
    }
}

/**
 * Plugin-owned settings schema. Every field key must reside within `"plg.$pluginId."`.
 */
data class PluginSettingSchema(
    val pluginId: String,
    val fields: List<PluginSettingField>
) {
    val keyPrefix: String
        get() = "plg.$pluginId."

    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (!PluginManifest.ID_REGEX.matches(pluginId) || pluginId.contains("..")) {
            errors += "Invalid pluginId '$pluginId'"
        }
        val expectedPrefix = keyPrefix
        val seenKeys = HashSet<String>()
        for (field in fields) {
            if (!field.key.startsWith(expectedPrefix) || field.key.length <= expectedPrefix.length) {
                errors += "Setting key '${field.key}' must start with '$expectedPrefix'"
            }
            if (field.key.contains("..")) {
                errors += "Setting key '${field.key}' must not contain '..'"
            }
            if (!seenKeys.add(field.key)) {
                errors += "Duplicate setting key '${field.key}'"
            }
            if (field.title.isBlank()) {
                errors += "Setting '${field.key}' title must not be blank"
            }
        }
        return errors
    }

    fun requireValid() {
        val errors = validate()
        require(errors.isEmpty()) { "Invalid PluginSettingSchema for '$pluginId': ${errors.joinToString("; ")}" }
    }
}
