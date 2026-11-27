package com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.specs

import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticPresets
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticSurface
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.ImportEffect
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.RestorePolicy
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingSpec
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingValue
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingValueType

internal val DEFAULT_SPEC_EFFECTS: Set<ImportEffect> = setOf(
    ImportEffect.RECREATE_MODULE_UI,
    ImportEffect.RESTART_BILIBILI
)

internal fun boolSpec(
    id: String,
    storageKey: String,
    labelRes: Int,
    default: Boolean = false,
    restorePolicy: RestorePolicy = RestorePolicy.AUTOMATIC,
    introducedCatalogVersion: Int = 1,
    effects: Set<ImportEffect> = DEFAULT_SPEC_EFFECTS
) = SettingSpec(
    id = id,
    storageKey = storageKey,
    labelRes = labelRes,
    type = SettingValueType.BOOLEAN,
    defaultValue = SettingValue.Bool(default),
    restorePolicy = restorePolicy,
    introducedCatalogVersion = introducedCatalogVersion,
    effects = effects
)

internal fun textSpec(
    id: String,
    storageKey: String,
    labelRes: Int,
    default: String = "",
    allowed: Set<String>? = null,
    maxStringLength: Int = SettingSpec.DEFAULT_MAX_STRING_LENGTH,
    restorePolicy: RestorePolicy = RestorePolicy.AUTOMATIC,
    introducedCatalogVersion: Int = 1,
    effects: Set<ImportEffect> = DEFAULT_SPEC_EFFECTS
) = SettingSpec(
    id = id,
    storageKey = storageKey,
    labelRes = labelRes,
    type = SettingValueType.STRING,
    defaultValue = SettingValue.Text(default),
    restorePolicy = restorePolicy,
    allowedStrings = allowed,
    maxStringLength = maxStringLength,
    introducedCatalogVersion = introducedCatalogVersion,
    effects = effects
)

internal fun intSpec(
    id: String,
    storageKey: String,
    labelRes: Int,
    default: Int,
    allowed: Set<Int>? = null,
    range: IntRange? = null,
    introducedCatalogVersion: Int = 1,
    effects: Set<ImportEffect> = DEFAULT_SPEC_EFFECTS
) = SettingSpec(
    id = id,
    storageKey = storageKey,
    labelRes = labelRes,
    type = SettingValueType.INTEGER,
    defaultValue = SettingValue.IntValue(default),
    allowedIntegers = allowed,
    integerRange = range,
    introducedCatalogVersion = introducedCatalogVersion,
    effects = effects
)

internal fun semanticRulesSpec(
    id: String,
    storageKey: String,
    surface: SemanticSurface
) = textSpec(
    id = id,
    storageKey = storageKey,
    labelRes = R.string.semantic_rules_title,
    default = SemanticPresets.defaultSelection(surface),
    maxStringLength = SemanticPresets.MAX_SELECTION_LENGTH,
    introducedCatalogVersion = 32
)
