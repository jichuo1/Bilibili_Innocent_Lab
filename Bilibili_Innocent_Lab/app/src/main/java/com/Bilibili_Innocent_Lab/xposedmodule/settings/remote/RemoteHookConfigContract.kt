package com.Bilibili_Innocent_Lab.xposedmodule.settings.remote

import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingValue
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsConsentStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsDecision
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** LSPosed Remote Preferences 向宿主暴露的完整、不可变配置。 */
internal data class RemoteHookConfigSnapshot(
    val generation: Long,
    val moduleVersionCode: Long,
    val deliveryEnabled: Boolean,
    val noRootRevision: Long,
    val decision: UserTermsDecision,
    val values: Map<String, Any>
) {
    val authorized: Boolean
        get() = deliveryEnabled && decision.isAuthorized
}

/** 宿主解析结果；任何协议、类型或完整性异常都不能产生部分配置。 */
internal sealed interface RemoteHookConfigDecodeResult {
    data class Ready(val snapshot: RemoteHookConfigSnapshot) : RemoteHookConfigDecodeResult

    data class Invalid(val reason: String) : RemoteHookConfigDecodeResult
}

/**
 * API 102 Remote Preferences 的纯 Kotlin 协议。
 *
 * 仅包含 [SettingsCatalog] 白名单、两个非敏感运行时修订号和授权元数据。摘要用于
 * 发现截断、类型替换和半更新，不承担防恶意宿主进程篡改的职责。
 */
internal object RemoteHookConfigContract {
    const val GROUP = "hook_config"
    const val SCHEMA_VERSION = 4

    const val KEY_READY = "__remote_ready"
    const val KEY_SCHEMA_VERSION = "__remote_schema_version"
    const val KEY_CATALOG_VERSION = "__remote_catalog_version"
    const val KEY_GENERATION = "__remote_generation"
    const val KEY_MODULE_VERSION_CODE = "__remote_module_version_code"
    const val KEY_DELIVERY_ENABLED = "__remote_delivery_enabled"
    const val KEY_NO_ROOT_REVISION = "__remote_no_root_revision"
    const val KEY_TERMS_VERSION = "__remote_terms_version"
    const val KEY_TERMS_DECISION = "__remote_terms_decision"
    const val KEY_DIGEST = "__remote_digest"

    /** 自由复制派生镜像与设置源之间的修订号，不属于用户可备份设置。 */
    const val KEY_FREE_COPY_CONFIG_REVISION = "free_copy_config_revision"

    /** 用户手动请求重新适配的时间戳，只用于拒绝旧适配缓存。 */
    const val KEY_ADAPTER_RESET_TIMESTAMP = "adapt_reset_ts"

    private val metadataKeys = setOf(
        KEY_READY,
        KEY_SCHEMA_VERSION,
        KEY_CATALOG_VERSION,
        KEY_GENERATION,
        KEY_MODULE_VERSION_CODE,
        KEY_DELIVERY_ENABLED,
        KEY_NO_ROOT_REVISION,
        KEY_TERMS_VERSION,
        KEY_TERMS_DECISION,
        KEY_DIGEST
    )
    private val runtimeKeys = setOf(
        KEY_FREE_COPY_CONFIG_REVISION,
        KEY_ADAPTER_RESET_TIMESTAMP
    )
    val hookValueKeys: Set<String> = SettingsCatalog.specs
        .mapTo(linkedSetOf()) { it.storageKey }
        .apply { addAll(runtimeKeys) }
    val persistedKeys: Set<String> = hookValueKeys + metadataKeys

    fun resolveSourceValues(raw: Map<String, *>): Map<String, Any> =
        linkedMapOf<String, Any>().apply {
            SettingsCatalog.specs.forEach { spec ->
                val candidate = when (val stored = raw[spec.storageKey]) {
                    is Boolean -> SettingValue.Bool(stored)
                    is Int -> SettingValue.IntValue(stored)
                    is String -> SettingValue.Text(stored)
                    else -> spec.defaultValue
                }
                val normalized = spec.normalizeForBackup(candidate) ?: spec.defaultValue
                put(
                    spec.storageKey,
                    when (normalized) {
                        is SettingValue.Bool -> normalized.value
                        is SettingValue.IntValue -> normalized.value
                        is SettingValue.Text -> normalized.value
                    }
                )
            }
            put(
                KEY_FREE_COPY_CONFIG_REVISION,
                (raw[KEY_FREE_COPY_CONFIG_REVISION] as? Long)?.coerceAtLeast(0L) ?: 0L
            )
            put(
                KEY_ADAPTER_RESET_TIMESTAMP,
                (raw[KEY_ADAPTER_RESET_TIMESTAMP] as? Long)?.coerceAtLeast(0L) ?: 0L
            )
        }

    fun encode(
        generation: Long,
        moduleVersionCode: Long,
        deliveryEnabled: Boolean,
        noRootRevision: Long,
        decision: UserTermsDecision,
        values: Map<String, Any>
    ): Map<String, Any> {
        require(generation > 0L) { "generation must be positive" }
        require(moduleVersionCode > 0L) { "module version must be positive" }
        require(noRootRevision >= 0L) { "no-root revision must not be negative" }
        require(values.keys == hookValueKeys) { "remote hook values are incomplete" }
        require(validateHookValues(values) == null) { "remote hook values are invalid" }

        return linkedMapOf<String, Any>().apply {
            putAll(values)
            put(KEY_READY, true)
            put(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
            put(KEY_CATALOG_VERSION, SettingsCatalog.CATALOG_VERSION)
            put(KEY_GENERATION, generation)
            put(KEY_MODULE_VERSION_CODE, moduleVersionCode)
            put(KEY_DELIVERY_ENABLED, deliveryEnabled)
            put(KEY_NO_ROOT_REVISION, noRootRevision)
            put(KEY_TERMS_VERSION, UserTermsConsentStore.CURRENT_TERMS_VERSION)
            put(KEY_TERMS_DECISION, decision.name)
            put(
                KEY_DIGEST,
                digest(
                    generation = generation,
                    moduleVersionCode = moduleVersionCode,
                    deliveryEnabled = deliveryEnabled,
                    noRootRevision = noRootRevision,
                    decision = decision,
                    values = values
                )
            )
        }
    }

    fun decode(raw: Map<String, *>): RemoteHookConfigDecodeResult {
        if (raw.keys != persistedKeys) {
            return RemoteHookConfigDecodeResult.Invalid(
                "key-set(expected=${persistedKeys.size}, actual=${raw.keys.size})"
            )
        }
        if (raw[KEY_READY] != true) return RemoteHookConfigDecodeResult.Invalid("not-ready")
        if (raw[KEY_SCHEMA_VERSION] != SCHEMA_VERSION) {
            return RemoteHookConfigDecodeResult.Invalid("schema")
        }
        if (raw[KEY_CATALOG_VERSION] != SettingsCatalog.CATALOG_VERSION) {
            return RemoteHookConfigDecodeResult.Invalid("catalog")
        }
        if (raw[KEY_TERMS_VERSION] != UserTermsConsentStore.CURRENT_TERMS_VERSION) {
            return RemoteHookConfigDecodeResult.Invalid("terms-version")
        }
        val generation = raw[KEY_GENERATION] as? Long
            ?: return RemoteHookConfigDecodeResult.Invalid("generation-type")
        if (generation <= 0L) return RemoteHookConfigDecodeResult.Invalid("generation-value")
        val moduleVersionCode = raw[KEY_MODULE_VERSION_CODE] as? Long
            ?: return RemoteHookConfigDecodeResult.Invalid("module-version-type")
        if (moduleVersionCode != BuildConfig.VERSION_CODE.toLong()) {
            return RemoteHookConfigDecodeResult.Invalid("module-version")
        }
        val deliveryEnabled = raw[KEY_DELIVERY_ENABLED] as? Boolean
            ?: return RemoteHookConfigDecodeResult.Invalid("delivery-enabled-type")
        val noRootRevision = raw[KEY_NO_ROOT_REVISION] as? Long
            ?: return RemoteHookConfigDecodeResult.Invalid("no-root-revision-type")
        if (noRootRevision < 0L) {
            return RemoteHookConfigDecodeResult.Invalid("no-root-revision-value")
        }
        val decision = (raw[KEY_TERMS_DECISION] as? String)?.let { stored ->
            UserTermsDecision.entries.firstOrNull { it.name == stored }
        } ?: return RemoteHookConfigDecodeResult.Invalid("terms-decision")

        val values = hookValueKeys.associateWith { key ->
            raw[key] ?: return RemoteHookConfigDecodeResult.Invalid("missing:$key")
        }
        validateHookValues(values)?.let { return RemoteHookConfigDecodeResult.Invalid(it) }
        val storedDigest = raw[KEY_DIGEST] as? String
            ?: return RemoteHookConfigDecodeResult.Invalid("digest-type")
        if (storedDigest.length != SHA_256_HEX_LENGTH ||
            !MessageDigest.isEqual(
                storedDigest.toByteArray(Charsets.US_ASCII),
                digest(
                    generation = generation,
                    moduleVersionCode = moduleVersionCode,
                    deliveryEnabled = deliveryEnabled,
                    noRootRevision = noRootRevision,
                    decision = decision,
                    values = values
                ).toByteArray(Charsets.US_ASCII)
            )
        ) {
            return RemoteHookConfigDecodeResult.Invalid("digest")
        }
        return RemoteHookConfigDecodeResult.Ready(
            RemoteHookConfigSnapshot(
                generation = generation,
                moduleVersionCode = moduleVersionCode,
                deliveryEnabled = deliveryEnabled,
                noRootRevision = noRootRevision,
                decision = decision,
                values = values
            )
        )
    }

    private fun validateHookValues(values: Map<String, Any>): String? {
        SettingsCatalog.specs.forEach { spec ->
            val value = when (val raw = values[spec.storageKey]) {
                is Boolean -> SettingValue.Bool(raw)
                is Int -> SettingValue.IntValue(raw)
                is String -> SettingValue.Text(raw)
                else -> return "setting-type:${spec.storageKey}"
            }
            if (!spec.type.accepts(value)) return "setting-type:${spec.storageKey}"
            if (!spec.accepts(value) || spec.normalizeForBackup(value) != value) {
                return "setting-value:${spec.storageKey}"
            }
        }
        val freeCopyRevision = values[KEY_FREE_COPY_CONFIG_REVISION] as? Long
            ?: return "runtime-type:$KEY_FREE_COPY_CONFIG_REVISION"
        val adapterResetTimestamp = values[KEY_ADAPTER_RESET_TIMESTAMP] as? Long
            ?: return "runtime-type:$KEY_ADAPTER_RESET_TIMESTAMP"
        if (freeCopyRevision < 0L) return "runtime-value:$KEY_FREE_COPY_CONFIG_REVISION"
        if (adapterResetTimestamp < 0L) return "runtime-value:$KEY_ADAPTER_RESET_TIMESTAMP"
        return null
    }

    private fun digest(
        generation: Long,
        moduleVersionCode: Long,
        deliveryEnabled: Boolean,
        noRootRevision: Long,
        decision: UserTermsDecision,
        values: Map<String, Any>
    ): String {
        val canonical = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(SCHEMA_VERSION)
                output.writeInt(SettingsCatalog.CATALOG_VERSION)
                output.writeLong(generation)
                output.writeLong(moduleVersionCode)
                output.writeBoolean(deliveryEnabled)
                output.writeLong(noRootRevision)
                output.writeInt(UserTermsConsentStore.CURRENT_TERMS_VERSION)
                output.writeString(decision.name)
                SettingsCatalog.specs.forEach { spec ->
                    output.writeString(spec.storageKey)
                    output.writeInt(spec.valueVersion)
                    when (val value = values.getValue(spec.storageKey)) {
                        is Boolean -> {
                            output.writeByte(TYPE_BOOLEAN)
                            output.writeBoolean(value)
                        }
                        is Int -> {
                            output.writeByte(TYPE_INTEGER)
                            output.writeInt(value)
                        }
                        is String -> {
                            output.writeByte(TYPE_STRING)
                            output.writeString(value)
                        }
                        else -> error("Unsupported remote preference value type")
                    }
                }
                output.writeLong(values.getValue(KEY_FREE_COPY_CONFIG_REVISION) as Long)
                output.writeLong(values.getValue(KEY_ADAPTER_RESET_TIMESTAMP) as Long)
            }
            bytes.toByteArray()
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical)
            .joinToString(separator = "") { byte ->
                val value = byte.toInt() and 0xFF
                "${HEX[value ushr 4]}${HEX[value and 0x0F]}"
            }
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private const val TYPE_BOOLEAN = 1
    private const val TYPE_INTEGER = 2
    private const val TYPE_STRING = 3
    private const val SHA_256_HEX_LENGTH = 64
    private const val HEX = "0123456789abcdef"
}
