package com.Bilibili_Innocent_Lab.xposedmodule.settings.backup

/** 备份协议允许的值类型。禁止把 SharedPreferences 的任意对象直接写入文件。 */
internal sealed interface SettingValue {
    val typeName: String

    data class Bool(val value: Boolean) : SettingValue {
        override val typeName: String = "boolean"
    }

    data class IntValue(val value: Int) : SettingValue {
        override val typeName: String = "integer"
    }

    data class Text(val value: String) : SettingValue {
        override val typeName: String = "string"
    }
}

internal enum class SettingValueType(val serializedName: String) {
    BOOLEAN("boolean"),
    INTEGER("integer"),
    STRING("string");

    fun accepts(value: SettingValue): Boolean = when (this) {
        BOOLEAN -> value is SettingValue.Bool
        INTEGER -> value is SettingValue.IntValue
        STRING -> value is SettingValue.Text
    }

    companion object {
        fun fromSerializedName(name: String): SettingValueType? = entries.firstOrNull {
            it.serializedName == name
        }
    }
}

/**
 * 自动恢复策略。
 *
 * MANUAL 仍会进入备份文件和导入预览，但导入器绝不写入它，适合存在额外权威状态、
 * 尚未完成闭环验证的配置。
 */
internal enum class RestorePolicy {
    AUTOMATIC,
    MANUAL
}

internal enum class ImportEffect {
    REBUILD_FREE_COPY_MIRROR,
    REAPPLY_PREDICTIVE_BACK,
    RESTART_BILIBILI,
    RECREATE_MODULE_UI
}

/** 逻辑设置定义。id 是备份协议身份，storageKey 只是当前版本的落盘位置。 */
internal data class SettingSpec(
    val id: String,
    val storageKey: String,
    val labelRes: Int,
    val type: SettingValueType,
    val defaultValue: SettingValue,
    val valueVersion: Int = 1,
    val introducedCatalogVersion: Int = 1,
    val restorePolicy: RestorePolicy = RestorePolicy.AUTOMATIC,
    val effects: Set<ImportEffect> = emptySet(),
    val allowedIntegers: Set<Int>? = null,
    val integerRange: IntRange? = null,
    val allowedStrings: Set<String>? = null,
    val maxStringLength: Int = DEFAULT_MAX_STRING_LENGTH
) {
    fun accepts(value: SettingValue): Boolean {
        if (!type.accepts(value)) return false
        return when (value) {
            is SettingValue.Bool -> true
            is SettingValue.IntValue ->
                (allowedIntegers == null || value.value in allowedIntegers) &&
                    (integerRange == null || value.value in integerRange)
            is SettingValue.Text ->
                value.value.length <= maxStringLength &&
                    (allowedStrings == null || value.value in allowedStrings)
        }
    }

    /**
     * 按当前模块实际读取语义导出旧版本遗留值：离散枚举回落到默认值，连续范围收敛到边界。
     * 类型错误和超长自由文本没有可靠含义，仍拒绝导出，避免静默猜测用户意图。
     */
    fun normalizeForBackup(value: SettingValue): SettingValue? {
        if (!type.accepts(value)) return null
        val normalized = when (value) {
            is SettingValue.Bool -> value
            is SettingValue.IntValue -> when {
                allowedIntegers != null && value.value !in allowedIntegers -> defaultValue
                integerRange != null -> SettingValue.IntValue(value.value.coerceIn(integerRange))
                else -> value
            }
            is SettingValue.Text -> when {
                value.value.length > maxStringLength -> return null
                allowedStrings != null && value.value !in allowedStrings -> defaultValue
                else -> value
            }
        }
        return normalized.takeIf(::accepts)
    }

    companion object {
        const val DEFAULT_MAX_STRING_LENGTH = 64 * 1024
    }
}

internal data class StoredSetting(
    val explicit: Boolean,
    val value: SettingValue
)

internal fun interface SettingsReader {
    fun read(spec: SettingSpec): StoredSetting
}

internal data class SettingsSnapshot(
    val values: Map<String, StoredSetting>
) {
    operator fun get(id: String): StoredSetting? = values[id]
}

internal data class BackupSource(
    val versionName: String,
    val versionCode: Long,
    val applicationId: String
)

internal data class BackupScope(
    val id: String,
    val complete: Boolean,
    val recordCount: Int
)

internal data class BackupSetting(
    val id: String,
    val valueVersion: Int,
    val explicit: Boolean,
    val value: SettingValue
)

internal data class SettingsBackupDocument(
    val productId: String,
    val formatVersion: Int,
    val catalogVersion: Int,
    val createdAtEpochMs: Long,
    val source: BackupSource,
    val scope: BackupScope,
    val settings: List<BackupSetting>
)

internal object SettingsBackupFactory {

    fun snapshot(
        reader: SettingsReader,
        catalog: List<SettingSpec> = SettingsCatalog.specs
    ): SettingsSnapshot = SettingsSnapshot(
        catalog.associate { spec -> spec.id to reader.read(spec) }
    )

    fun createDocument(
        reader: SettingsReader,
        source: BackupSource,
        createdAtEpochMs: Long = System.currentTimeMillis(),
        catalog: List<SettingSpec> = SettingsCatalog.specs,
        catalogVersion: Int = SettingsCatalog.CATALOG_VERSION
    ): SettingsBackupDocument {
        require(createdAtEpochMs > 0L) { "createdAtEpochMs must be positive" }
        val settings = catalog.map { spec ->
            val stored = reader.read(spec)
            val exportValue = checkNotNull(spec.normalizeForBackup(stored.value)) {
                "Invalid stored value for ${spec.id}"
            }
            BackupSetting(
                id = spec.id,
                valueVersion = spec.valueVersion,
                explicit = stored.explicit,
                value = exportValue
            )
        }
        return SettingsBackupDocument(
            productId = SettingsCatalog.PRODUCT_ID,
            formatVersion = SettingsBackupCodec.CURRENT_FORMAT_VERSION,
            catalogVersion = catalogVersion,
            createdAtEpochMs = createdAtEpochMs,
            source = source,
            scope = BackupScope(
                id = SettingsCatalog.SCOPE_ID,
                complete = true,
                recordCount = settings.size
            ),
            settings = settings
        )
    }
}
