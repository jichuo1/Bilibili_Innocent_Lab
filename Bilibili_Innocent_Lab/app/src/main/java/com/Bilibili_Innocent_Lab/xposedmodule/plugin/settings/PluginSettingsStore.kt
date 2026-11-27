package com.Bilibili_Innocent_Lab.xposedmodule.plugin.settings

import android.content.SharedPreferences
import com.bilab.core.settings.CoreSettingDefinition
import com.bilab.core.settings.CoreSettingValidator
import com.bilab.core.settings.CoreSettingValue
import com.bilab.core.settings.CoreSettingValueType
import com.bilab.core.settings.PluginSettingKeySpace
import com.bilab.plugin.api.model.PluginManifest
import com.bilab.plugin.api.model.PluginSettingField
import com.bilab.plugin.api.model.PluginSettingSchema
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Abstract storage backend for plugin-namespaced preferences (`plg.<pluginId>.<localKey>`).
 */
internal interface PluginPreferenceBackend {
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getInt(key: String, default: Int): Int
    fun getString(key: String, default: String): String
    fun putBoolean(key: String, value: Boolean)
    fun putInt(key: String, value: Int)
    fun putString(key: String, value: String)
}

/**
 * Thread-safe in-memory backend for JVM tests and isolated plugin snapshots.
 */
internal class InMemoryPluginPreferenceBackend(
    initial: Map<String, Any> = emptyMap()
) : PluginPreferenceBackend {
    private val store = ConcurrentHashMap<String, Any>().apply {
        initial.forEach { (key, value) ->
            if (value is Boolean || value is Int || value is String) {
                put(key, value)
            }
        }
    }

    override fun getBoolean(key: String, default: Boolean): Boolean =
        store[key] as? Boolean ?: default

    override fun getInt(key: String, default: Int): Int =
        store[key] as? Int ?: default

    override fun getString(key: String, default: String): String =
        store[key] as? String ?: default

    override fun putBoolean(key: String, value: Boolean) {
        store[key] = value
    }

    override fun putInt(key: String, value: Int) {
        store[key] = value
    }

    override fun putString(key: String, value: String) {
        store[key] = value
    }

    fun contains(key: String): Boolean = store.containsKey(key)

    fun keys(): Set<String> = store.keys.toSet()

    fun remove(key: String): Any? = store.remove(key)

    fun clear() = store.clear()

    fun rawSnapshot(): Map<String, Any> = Collections.unmodifiableMap(LinkedHashMap(store))
}

/**
 * Android [SharedPreferences]-backed storage adapter strictly scoped for plugin preferences.
 */
internal class SharedPreferencesPluginPreferenceBackend(
    private val prefs: SharedPreferences
) : PluginPreferenceBackend {
    override fun getBoolean(key: String, default: Boolean): Boolean =
        prefs.getBoolean(key, default)

    override fun getInt(key: String, default: Int): Int =
        prefs.getInt(key, default)

    override fun getString(key: String, default: String): String =
        prefs.getString(key, default) ?: default

    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    override fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}

/**
 * Strictly isolated plugin settings store enforced by [PluginSettingKeySpace] and [CoreSettingValidator].
 *
 * Every read, write, default materialization, and snapshot export stays confined to
 * `"plg.<pluginId>.<localKey>"` and never accesses core preferences, backup catalogs, or runtime API keys.
 */
internal class PluginSettingsStore(
    private val backend: PluginPreferenceBackend = InMemoryPluginPreferenceBackend()
) {
    private val lock = Any()
    private val definitionsByPlugin = ConcurrentHashMap<String, Map<String, CoreSettingDefinition>>()
    private val schemasByPlugin = ConcurrentHashMap<String, PluginSettingSchema>()
    private val trackedTypesByPlugin = ConcurrentHashMap<String, ConcurrentHashMap<String, CoreSettingValueType>>()

    fun registerSchema(schema: PluginSettingSchema) {
        schema.requireValid()
        val definitions = LinkedHashMap<String, CoreSettingDefinition>(schema.fields.size)
        for (field in schema.fields) {
            val isolatedKey = PluginSettingKeySpace.requireIsolatedKey(schema.pluginId, field.key)
            definitions[isolatedKey] = toCoreDefinition(schema.pluginId, field)
        }
        synchronized(lock) {
            schemasByPlugin[schema.pluginId] = schema
            definitionsByPlugin[schema.pluginId] = Collections.unmodifiableMap(definitions)
            val types = trackedTypesByPlugin.computeIfAbsent(schema.pluginId) { ConcurrentHashMap() }
            definitions.forEach { (key, def) -> types[key] = def.type }
        }
    }

    fun materializeDefaults(
        schema: PluginSettingSchema,
        overwriteExisting: Boolean = false
    ): Map<String, Any> {
        registerSchema(schema)
        val defs = definitionsByPlugin.getValue(schema.pluginId)
        for (field in schema.fields) {
            val isolatedKey = PluginSettingKeySpace.requireIsolatedKey(schema.pluginId, field.key)
            val def = defs.getValue(isolatedKey)
            when (field) {
                is PluginSettingField.BoolField -> {
                    val value = if (overwriteExisting) {
                        field.defaultValue
                    } else {
                        readExistingBoolean(isolatedKey) ?: field.defaultValue
                    }
                    if (overwriteExisting || readExistingBoolean(isolatedKey) != value) {
                        backend.putBoolean(isolatedKey, value)
                    }
                }
                is PluginSettingField.IntField -> {
                    val existing = if (overwriteExisting) null else readExistingInt(isolatedKey, field.defaultValue)
                    val normalized = if (existing == null) {
                        field.defaultValue
                    } else {
                        val candidate = CoreSettingValue.IntValue(existing)
                        val resolved = if (CoreSettingValidator.accepts(def, candidate)) {
                            candidate
                        } else {
                            CoreSettingValidator.normalizeForBackup(def, candidate) ?: def.defaultValue
                        }
                        (resolved as CoreSettingValue.IntValue).value
                    }
                    if (overwriteExisting || existing != normalized) {
                        backend.putInt(isolatedKey, normalized)
                    }
                }
                is PluginSettingField.TextField -> {
                    val existing = if (overwriteExisting) null else readExistingString(isolatedKey)
                    val normalized = if (existing == null) {
                        field.defaultValue
                    } else {
                        val candidate = CoreSettingValue.Text(existing)
                        val resolved = if (CoreSettingValidator.accepts(def, candidate)) {
                            candidate
                        } else {
                            CoreSettingValidator.normalizeForBackup(def, candidate) ?: def.defaultValue
                        }
                        (resolved as CoreSettingValue.Text).value
                    }
                    if (overwriteExisting || existing != normalized) {
                        backend.putString(isolatedKey, normalized)
                    }
                }
                is PluginSettingField.EnumField -> {
                    val existing = if (overwriteExisting) null else readExistingString(isolatedKey)
                    val normalized = if (existing == null) {
                        field.defaultValue
                    } else {
                        val candidate = CoreSettingValue.Text(existing)
                        val resolved = if (CoreSettingValidator.accepts(def, candidate)) {
                            candidate
                        } else {
                            CoreSettingValidator.normalizeForBackup(def, candidate) ?: def.defaultValue
                        }
                        (resolved as CoreSettingValue.Text).value
                    }
                    if (overwriteExisting || existing != normalized) {
                        backend.putString(isolatedKey, normalized)
                    }
                }
            }
        }
        return snapshot(schema.pluginId)
    }

    fun getBoolean(pluginId: String, key: String, defaultValue: Boolean = false): Boolean {
        val isolatedKey = PluginSettingKeySpace.requireIsolatedKey(pluginId, key)
        val def = definitionFor(pluginId, isolatedKey)
        if (def != null) {
            require(def.type == CoreSettingValueType.BOOLEAN) {
                "Setting '$isolatedKey' expects ${def.type.serializedName}, not boolean"
            }
        }
        return backend.getBoolean(isolatedKey, defaultValue)
    }

    fun getInt(pluginId: String, key: String, defaultValue: Int = 0): Int {
        val isolatedKey = PluginSettingKeySpace.requireIsolatedKey(pluginId, key)
        val def = definitionFor(pluginId, isolatedKey)
        if (def != null) {
            require(def.type == CoreSettingValueType.INTEGER) {
                "Setting '$isolatedKey' expects ${def.type.serializedName}, not integer"
            }
            val candidate = readExistingInt(isolatedKey, defaultValue) ?: defaultValue
            return if (CoreSettingValidator.accepts(def, CoreSettingValue.IntValue(candidate))) {
                candidate
            } else {
                (def.defaultValue as CoreSettingValue.IntValue).value
            }
        }
        return backend.getInt(isolatedKey, defaultValue)
    }

    fun getString(pluginId: String, key: String, defaultValue: String = ""): String {
        val isolatedKey = PluginSettingKeySpace.requireIsolatedKey(pluginId, key)
        val def = definitionFor(pluginId, isolatedKey)
        if (def != null) {
            require(def.type == CoreSettingValueType.STRING) {
                "Setting '$isolatedKey' expects ${def.type.serializedName}, not string"
            }
            val candidate = readExistingString(isolatedKey) ?: defaultValue
            return if (CoreSettingValidator.accepts(def, CoreSettingValue.Text(candidate))) {
                candidate
            } else {
                (def.defaultValue as CoreSettingValue.Text).value
            }
        }
        return backend.getString(isolatedKey, defaultValue)
    }

    fun putBoolean(pluginId: String, key: String, value: Boolean) {
        val isolatedKey = PluginSettingKeySpace.requireIsolatedKey(pluginId, key)
        val def = definitionFor(pluginId, isolatedKey)
        if (def != null) {
            require(CoreSettingValidator.accepts(def, CoreSettingValue.Bool(value))) {
                "Value '$value' violates schema constraints for '$isolatedKey'"
            }
        }
        backend.putBoolean(isolatedKey, value)
        trackKey(pluginId, isolatedKey, CoreSettingValueType.BOOLEAN)
    }

    fun putInt(pluginId: String, key: String, value: Int) {
        val isolatedKey = PluginSettingKeySpace.requireIsolatedKey(pluginId, key)
        val def = definitionFor(pluginId, isolatedKey)
        if (def != null) {
            require(CoreSettingValidator.accepts(def, CoreSettingValue.IntValue(value))) {
                "Value '$value' violates schema constraints for '$isolatedKey'"
            }
        }
        backend.putInt(isolatedKey, value)
        trackKey(pluginId, isolatedKey, CoreSettingValueType.INTEGER)
    }

    fun putString(pluginId: String, key: String, value: String) {
        val isolatedKey = PluginSettingKeySpace.requireIsolatedKey(pluginId, key)
        val def = definitionFor(pluginId, isolatedKey)
        if (def != null) {
            require(CoreSettingValidator.accepts(def, CoreSettingValue.Text(value))) {
                "Value for '$isolatedKey' violates schema constraints"
            }
        }
        backend.putString(isolatedKey, value)
        trackKey(pluginId, isolatedKey, CoreSettingValueType.STRING)
    }

    fun readFieldValue(pluginId: String, field: PluginSettingField): Any {
        val isolatedKey = PluginSettingKeySpace.requireIsolatedKey(pluginId, field.key)
        val def = toCoreDefinition(pluginId, field)
        return when (field) {
            is PluginSettingField.BoolField -> readExistingBoolean(isolatedKey) ?: field.defaultValue
            is PluginSettingField.IntField -> {
                val candidate = readExistingInt(isolatedKey, field.defaultValue) ?: field.defaultValue
                if (CoreSettingValidator.accepts(def, CoreSettingValue.IntValue(candidate))) {
                    candidate
                } else {
                    field.defaultValue
                }
            }
            is PluginSettingField.TextField -> {
                val candidate = readExistingString(isolatedKey) ?: field.defaultValue
                if (CoreSettingValidator.accepts(def, CoreSettingValue.Text(candidate))) {
                    candidate
                } else {
                    field.defaultValue
                }
            }
            is PluginSettingField.EnumField -> {
                val candidate = readExistingString(isolatedKey) ?: field.defaultValue
                if (CoreSettingValidator.accepts(def, CoreSettingValue.Text(candidate))) {
                    candidate
                } else {
                    field.defaultValue
                }
            }
        }
    }

    fun writeFieldValue(pluginId: String, field: PluginSettingField, value: Any) {
        val isolatedKey = PluginSettingKeySpace.requireIsolatedKey(pluginId, field.key)
        val def = toCoreDefinition(pluginId, field)
        when (field) {
            is PluginSettingField.BoolField -> {
                val boolVal = value as? Boolean
                    ?: throw IllegalArgumentException("Expected Boolean for '$isolatedKey'")
                require(CoreSettingValidator.accepts(def, CoreSettingValue.Bool(boolVal))) {
                    "Value '$boolVal' violates schema constraints for '$isolatedKey'"
                }
                backend.putBoolean(isolatedKey, boolVal)
                trackKey(pluginId, isolatedKey, CoreSettingValueType.BOOLEAN)
            }
            is PluginSettingField.IntField -> {
                val intVal = value as? Int
                    ?: throw IllegalArgumentException("Expected Int for '$isolatedKey'")
                require(CoreSettingValidator.accepts(def, CoreSettingValue.IntValue(intVal))) {
                    "Value '$intVal' outside ${field.min}..${field.max} for '$isolatedKey'"
                }
                backend.putInt(isolatedKey, intVal)
                trackKey(pluginId, isolatedKey, CoreSettingValueType.INTEGER)
            }
            is PluginSettingField.TextField -> {
                val textVal = value as? String
                    ?: throw IllegalArgumentException("Expected String for '$isolatedKey'")
                require(CoreSettingValidator.accepts(def, CoreSettingValue.Text(textVal))) {
                    "Text exceeds maxLength=${field.maxLength} for '$isolatedKey'"
                }
                backend.putString(isolatedKey, textVal)
                trackKey(pluginId, isolatedKey, CoreSettingValueType.STRING)
            }
            is PluginSettingField.EnumField -> {
                val enumVal = value as? String
                    ?: throw IllegalArgumentException("Expected String for '$isolatedKey'")
                require(CoreSettingValidator.accepts(def, CoreSettingValue.Text(enumVal))) {
                    "Option '$enumVal' not allowed for '$isolatedKey'"
                }
                backend.putString(isolatedKey, enumVal)
                trackKey(pluginId, isolatedKey, CoreSettingValueType.STRING)
            }
        }
    }

    /**
     * Exports a read-only snapshot strictly scoped to [pluginId]'s isolated namespace.
     */
    fun snapshot(pluginId: String): Map<String, Any> {
        require(PluginManifest.ID_REGEX.matches(pluginId) && !pluginId.contains("..")) {
            "Invalid pluginId '$pluginId'"
        }
        val result = LinkedHashMap<String, Any>()
        val schema = schemasByPlugin[pluginId]
        if (schema != null) {
            for (field in schema.fields) {
                val isolatedKey = PluginSettingKeySpace.requireIsolatedKey(pluginId, field.key)
                result[isolatedKey] = readFieldValue(pluginId, field)
            }
        }
        val tracked = trackedTypesByPlugin[pluginId]
        if (tracked != null) {
            for ((key, type) in tracked.entries.sortedBy { it.key }) {
                if (key in result) continue
                if (!PluginSettingKeySpace.belongsToPlugin(key, pluginId)) continue
                when (type) {
                    CoreSettingValueType.BOOLEAN -> readExistingBoolean(key)?.let { result[key] = it }
                    CoreSettingValueType.INTEGER -> readExistingInt(key, 0)?.let { result[key] = it }
                    CoreSettingValueType.STRING -> readExistingString(key)?.let { result[key] = it }
                }
            }
        }
        if (backend is InMemoryPluginPreferenceBackend) {
            for ((key, value) in backend.rawSnapshot().entries.sortedBy { it.key }) {
                if (key in result) continue
                if (!PluginSettingKeySpace.belongsToPlugin(key, pluginId)) continue
                result[key] = value
            }
        }
        return Collections.unmodifiableMap(result)
    }

    private fun definitionFor(pluginId: String, isolatedKey: String): CoreSettingDefinition? =
        definitionsByPlugin[pluginId]?.get(isolatedKey)

    private fun trackKey(pluginId: String, isolatedKey: String, type: CoreSettingValueType) {
        trackedTypesByPlugin.computeIfAbsent(pluginId) { ConcurrentHashMap() }[isolatedKey] = type
    }

    private fun readExistingBoolean(key: String): Boolean? {
        val asTrue = backend.getBoolean(key, true)
        val asFalse = backend.getBoolean(key, false)
        return if (asTrue == asFalse) asTrue else null
    }

    private fun readExistingInt(key: String, hint: Int): Int? {
        val s1 = if (hint == Int.MIN_VALUE) Int.MIN_VALUE + 1 else Int.MIN_VALUE
        val s2 = if (hint == Int.MAX_VALUE) Int.MAX_VALUE - 1 else Int.MAX_VALUE
        val v1 = backend.getInt(key, s1)
        val v2 = backend.getInt(key, s2)
        return if (v1 == v2) v1 else null
    }

    private fun readExistingString(key: String): String? {
        val v1 = backend.getString(key, UNSET_SENTINEL_A)
        val v2 = backend.getString(key, UNSET_SENTINEL_B)
        return if (v1 == v2) v1 else null
    }

    companion object {
        private const val UNSET_SENTINEL_A = "\u0000__bilab_plugin_unset_a__"
        private const val UNSET_SENTINEL_B = "\u0000__bilab_plugin_unset_b__"

        internal fun toCoreDefinition(
            pluginId: String,
            field: PluginSettingField
        ): CoreSettingDefinition {
            val isolatedKey = PluginSettingKeySpace.requireIsolatedKey(pluginId, field.key)
            return when (field) {
                is PluginSettingField.BoolField -> CoreSettingDefinition(
                    id = isolatedKey,
                    storageKey = isolatedKey,
                    type = CoreSettingValueType.BOOLEAN,
                    defaultValue = CoreSettingValue.Bool(field.defaultValue)
                )
                is PluginSettingField.IntField -> CoreSettingDefinition(
                    id = isolatedKey,
                    storageKey = isolatedKey,
                    type = CoreSettingValueType.INTEGER,
                    defaultValue = CoreSettingValue.IntValue(field.defaultValue),
                    integerRange = field.min..field.max
                )
                is PluginSettingField.TextField -> CoreSettingDefinition(
                    id = isolatedKey,
                    storageKey = isolatedKey,
                    type = CoreSettingValueType.STRING,
                    defaultValue = CoreSettingValue.Text(field.defaultValue),
                    maxStringLength = field.maxLength
                )
                is PluginSettingField.EnumField -> CoreSettingDefinition(
                    id = isolatedKey,
                    storageKey = isolatedKey,
                    type = CoreSettingValueType.STRING,
                    defaultValue = CoreSettingValue.Text(field.defaultValue),
                    allowedStrings = field.options.toSet()
                )
            }
        }
    }
}
