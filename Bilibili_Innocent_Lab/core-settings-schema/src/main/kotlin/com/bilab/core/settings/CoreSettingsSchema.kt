package com.bilab.core.settings

/**
 * Typed setting value supported by the core settings schema and plugin setting namespace.
 */
sealed interface CoreSettingValue {
    val typeName: String

    data class Bool(val value: Boolean) : CoreSettingValue {
        override val typeName: String = "boolean"
    }

    data class IntValue(val value: Int) : CoreSettingValue {
        override val typeName: String = "integer"
    }

    data class Text(val value: String) : CoreSettingValue {
        override val typeName: String = "string"
    }
}

enum class CoreSettingValueType(val serializedName: String) {
    BOOLEAN("boolean"),
    INTEGER("integer"),
    STRING("string");

    fun accepts(value: CoreSettingValue): Boolean = when (this) {
        BOOLEAN -> value is CoreSettingValue.Bool
        INTEGER -> value is CoreSettingValue.IntValue
        STRING -> value is CoreSettingValue.Text
    }

    companion object {
        fun fromSerializedName(name: String): CoreSettingValueType? =
            entries.firstOrNull { it.serializedName == name }
    }
}

/**
 * Pure JVM descriptor for a core or plugin-namespaced setting entry.
 */
data class CoreSettingDefinition(
    val id: String,
    val storageKey: String,
    val type: CoreSettingValueType,
    val defaultValue: CoreSettingValue,
    val valueVersion: Int = 1,
    val introducedCatalogVersion: Int = 1,
    val allowedIntegers: Set<Int>? = null,
    val integerRange: IntRange? = null,
    val allowedStrings: Set<String>? = null,
    val maxStringLength: Int = DEFAULT_MAX_STRING_LENGTH
) {
    init {
        require(id.isNotBlank()) { "Setting id must not be blank" }
        require(storageKey.isNotBlank()) { "Setting storageKey must not be blank" }
        require(valueVersion > 0) { "valueVersion must be positive" }
        require(introducedCatalogVersion > 0) { "introducedCatalogVersion must be positive" }
        require(maxStringLength > 0) { "maxStringLength must be positive" }
        require(type.accepts(defaultValue)) { "defaultValue does not match declared type $type" }
        require(CoreSettingValidator.accepts(this, defaultValue)) {
            "defaultValue violates constraints for '$id'"
        }
    }

    fun accepts(value: CoreSettingValue): Boolean =
        CoreSettingValidator.accepts(this, value)

    fun normalizeForBackup(value: CoreSettingValue): CoreSettingValue? =
        CoreSettingValidator.normalizeForBackup(this, value)

    companion object {
        const val DEFAULT_MAX_STRING_LENGTH = 64 * 1024
    }
}

/**
 * Stateless validation and normalization rules shared by core and plugin settings.
 */
object CoreSettingValidator {
    fun accepts(definition: CoreSettingDefinition, value: CoreSettingValue): Boolean {
        if (!definition.type.accepts(value)) return false
        return when (value) {
            is CoreSettingValue.Bool -> true
            is CoreSettingValue.IntValue ->
                (definition.allowedIntegers == null || value.value in definition.allowedIntegers) &&
                    (definition.integerRange == null || value.value in definition.integerRange)
            is CoreSettingValue.Text ->
                value.value.length <= definition.maxStringLength &&
                    (definition.allowedStrings == null || value.value in definition.allowedStrings)
        }
    }

    fun normalizeForBackup(definition: CoreSettingDefinition, value: CoreSettingValue): CoreSettingValue? {
        if (!definition.type.accepts(value)) return null
        val normalized = when (value) {
            is CoreSettingValue.Bool -> value
            is CoreSettingValue.IntValue -> when {
                definition.allowedIntegers != null && value.value !in definition.allowedIntegers ->
                    definition.defaultValue
                definition.integerRange != null ->
                    CoreSettingValue.IntValue(value.value.coerceIn(definition.integerRange))
                else -> value
            }
            is CoreSettingValue.Text -> when {
                value.value.length > definition.maxStringLength -> return null
                definition.allowedStrings != null && value.value !in definition.allowedStrings ->
                    definition.defaultValue
                else -> value
            }
        }
        return normalized.takeIf { accepts(definition, it) }
    }
}

/**
 * Namespace isolation guard ensuring plugin preferences stay strictly inside `plg.<pluginId>.<localKey>`.
 */
object PluginSettingKeySpace {
    const val PREFIX = "plg."

    private val PLUGIN_ID_REGEX = Regex("[a-z0-9][a-z0-9._-]{2,63}")
    private val LOCAL_KEY_REGEX = Regex("[a-z0-9][a-z0-9._-]{0,63}")

    fun isPluginKey(key: String): Boolean =
        key.startsWith(PREFIX) && key.length > PREFIX.length && !key.contains("..")

    fun keyFor(pluginId: String, localKey: String): String {
        require(isValidPluginId(pluginId)) { "Invalid pluginId: '$pluginId'" }
        require(isValidLocalKey(localKey)) { "Invalid localKey: '$localKey'" }
        return "$PREFIX$pluginId.$localKey"
    }

    fun belongsToPlugin(key: String, pluginId: String): Boolean {
        if (!isValidPluginId(pluginId) || key.contains("..")) return false
        val expectedPrefix = "$PREFIX$pluginId."
        if (!key.startsWith(expectedPrefix)) return false
        val localPart = key.substring(expectedPrefix.length)
        return isValidLocalKey(localPart)
    }

    fun requireIsolatedKey(pluginId: String, key: String): String {
        require(belongsToPlugin(key, pluginId)) {
            "Key '$key' is outside isolated namespace '$PREFIX$pluginId.'"
        }
        return key
    }

    private fun isValidPluginId(pluginId: String): Boolean =
        PLUGIN_ID_REGEX.matches(pluginId) && !pluginId.contains("..")

    private fun isValidLocalKey(localKey: String): Boolean =
        LOCAL_KEY_REGEX.matches(localKey) && !localKey.contains("..")
}
