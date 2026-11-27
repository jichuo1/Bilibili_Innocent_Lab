package com.Bilibili_Innocent_Lab.xposedmodule.plugin.ui

import com.Bilibili_Innocent_Lab.xposedmodule.plugin.settings.PluginSettingsStore
import com.bilab.core.settings.CoreSettingValidator
import com.bilab.core.settings.CoreSettingValue
import com.bilab.core.settings.PluginSettingKeySpace
import com.bilab.plugin.api.model.PluginSettingField
import com.bilab.plugin.api.model.PluginSettingSchema
import java.util.Collections

/**
 * Outcome returned when a declarative setting control attempts to persist a user change.
 */
internal sealed interface SettingUpdateResult {
    data class Accepted(val key: String, val value: Any) : SettingUpdateResult
    data class Rejected(val key: String, val reason: String) : SettingUpdateResult
}

/**
 * Pure view-model descriptor rendered by the module UI from a plugin's declarative [PluginSettingSchema].
 *
 * No third-party bytecode or Android [android.view.View] objects cross the plugin boundary.
 */
internal sealed interface DeclarativeSettingControl {
    val pluginId: String
    val key: String
    val title: String
    val description: String

    data class SwitchControl(
        override val pluginId: String,
        override val key: String,
        override val title: String,
        override val description: String,
        val checked: Boolean,
        val defaultValue: Boolean,
        val onChange: (Boolean) -> SettingUpdateResult
    ) : DeclarativeSettingControl

    data class SliderOrIntControl(
        override val pluginId: String,
        override val key: String,
        override val title: String,
        override val description: String,
        val value: Int,
        val min: Int,
        val max: Int,
        val defaultValue: Int,
        val onChange: (Int) -> SettingUpdateResult
    ) : DeclarativeSettingControl

    data class TextInputControl(
        override val pluginId: String,
        override val key: String,
        override val title: String,
        override val description: String,
        val text: String,
        val maxLength: Int,
        val defaultValue: String,
        val onChange: (String) -> SettingUpdateResult
    ) : DeclarativeSettingControl

    data class EnumSelectorControl(
        override val pluginId: String,
        override val key: String,
        override val title: String,
        override val description: String,
        val selectedOption: String,
        val options: List<String>,
        val defaultValue: String,
        val onChange: (String) -> SettingUpdateResult
    ) : DeclarativeSettingControl
}

internal data class DeclarativePluginSettingsScreen(
    val pluginId: String,
    val controls: List<DeclarativeSettingControl>
)

/**
 * Stateless declarative renderer transforming [PluginSettingSchema] fields into immutable UI control models
 * wired back to [PluginSettingsStore] with constraint validation.
 */
internal object DeclarativePluginSettingsRenderer {

    fun render(
        schema: PluginSettingSchema,
        store: PluginSettingsStore,
        onChanged: ((key: String, value: Any) -> Unit)? = null
    ): List<DeclarativeSettingControl> {
        schema.requireValid()
        store.registerSchema(schema)
        val controls = ArrayList<DeclarativeSettingControl>(schema.fields.size)
        for (field in schema.fields) {
            val isolatedKey = PluginSettingKeySpace.requireIsolatedKey(schema.pluginId, field.key)
            val definition = PluginSettingsStore.toCoreDefinition(schema.pluginId, field)
            controls += when (field) {
                is PluginSettingField.BoolField -> {
                    val live = store.readFieldValue(schema.pluginId, field) as Boolean
                    DeclarativeSettingControl.SwitchControl(
                        pluginId = schema.pluginId,
                        key = isolatedKey,
                        title = field.title,
                        description = field.description,
                        checked = live,
                        defaultValue = field.defaultValue,
                        onChange = { next ->
                            if (!CoreSettingValidator.accepts(definition, CoreSettingValue.Bool(next))) {
                                SettingUpdateResult.Rejected(isolatedKey, "invalid_boolean")
                            } else {
                                store.putBoolean(schema.pluginId, isolatedKey, next)
                                onChanged?.invoke(isolatedKey, next)
                                SettingUpdateResult.Accepted(isolatedKey, next)
                            }
                        }
                    )
                }
                is PluginSettingField.IntField -> {
                    val live = store.readFieldValue(schema.pluginId, field) as Int
                    DeclarativeSettingControl.SliderOrIntControl(
                        pluginId = schema.pluginId,
                        key = isolatedKey,
                        title = field.title,
                        description = field.description,
                        value = live,
                        min = field.min,
                        max = field.max,
                        defaultValue = field.defaultValue,
                        onChange = { next ->
                            if (!CoreSettingValidator.accepts(definition, CoreSettingValue.IntValue(next))) {
                                SettingUpdateResult.Rejected(
                                    isolatedKey,
                                    "int_out_of_range:${field.min}..${field.max}"
                                )
                            } else {
                                store.putInt(schema.pluginId, isolatedKey, next)
                                onChanged?.invoke(isolatedKey, next)
                                SettingUpdateResult.Accepted(isolatedKey, next)
                            }
                        }
                    )
                }
                is PluginSettingField.TextField -> {
                    val live = store.readFieldValue(schema.pluginId, field) as String
                    DeclarativeSettingControl.TextInputControl(
                        pluginId = schema.pluginId,
                        key = isolatedKey,
                        title = field.title,
                        description = field.description,
                        text = live,
                        maxLength = field.maxLength,
                        defaultValue = field.defaultValue,
                        onChange = { next ->
                            if (!CoreSettingValidator.accepts(definition, CoreSettingValue.Text(next))) {
                                SettingUpdateResult.Rejected(
                                    isolatedKey,
                                    "text_exceeds_max_length:${field.maxLength}"
                                )
                            } else {
                                store.putString(schema.pluginId, isolatedKey, next)
                                onChanged?.invoke(isolatedKey, next)
                                SettingUpdateResult.Accepted(isolatedKey, next)
                            }
                        }
                    )
                }
                is PluginSettingField.EnumField -> {
                    val live = store.readFieldValue(schema.pluginId, field) as String
                    val immutableOptions = Collections.unmodifiableList(field.options.toList())
                    DeclarativeSettingControl.EnumSelectorControl(
                        pluginId = schema.pluginId,
                        key = isolatedKey,
                        title = field.title,
                        description = field.description,
                        selectedOption = live,
                        options = immutableOptions,
                        defaultValue = field.defaultValue,
                        onChange = { next ->
                            if (!CoreSettingValidator.accepts(definition, CoreSettingValue.Text(next))) {
                                SettingUpdateResult.Rejected(isolatedKey, "enum_option_not_allowed")
                            } else {
                                store.putString(schema.pluginId, isolatedKey, next)
                                onChanged?.invoke(isolatedKey, next)
                                SettingUpdateResult.Accepted(isolatedKey, next)
                            }
                        }
                    )
                }
            }
        }
        return Collections.unmodifiableList(controls)
    }

    fun renderScreen(
        schema: PluginSettingSchema,
        store: PluginSettingsStore,
        onChanged: ((key: String, value: Any) -> Unit)? = null
    ): DeclarativePluginSettingsScreen = DeclarativePluginSettingsScreen(
        pluginId = schema.pluginId,
        controls = render(schema, store, onChanged)
    )
}
