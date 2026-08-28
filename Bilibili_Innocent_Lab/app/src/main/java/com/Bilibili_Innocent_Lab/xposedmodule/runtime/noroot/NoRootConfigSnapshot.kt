package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import org.json.JSONObject
import java.util.Collections

/** 免 Root 冷启动配置；只在模块设置页和宿主 attach 阶段编解码。 */
internal data class NoRootConfigSnapshot(
    val schemaVersion: Int,
    val catalogVersion: Int,
    val modulePackage: String,
    val moduleVersionCode: Long,
    val revision: Long,
    val adapterResetRevision: Long,
    val enabled: Boolean,
    val values: Map<String, Any>
) {
    init {
        require(schemaVersion > 0)
        require(catalogVersion > 0)
        require(modulePackage.isNotBlank())
        require(moduleVersionCode > 0L)
        require(revision > 0L)
        require(adapterResetRevision >= 0L)
    }

    fun hasSameContent(other: NoRootConfigSnapshot): Boolean =
        schemaVersion == other.schemaVersion &&
            catalogVersion == other.catalogVersion &&
            modulePackage == other.modulePackage &&
            moduleVersionCode == other.moduleVersionCode &&
            adapterResetRevision == other.adapterResetRevision &&
            enabled == other.enabled &&
            values == other.values
}

/**
 * 强类型、有限大小的 JSON 协议。类型标签用于避免 JSONObject 把 Int/Long 混为 Number，
 * 目标进程解析完成后只保留不可变 Map，不在 Hook 回调中再次解析。
 */
internal object NoRootConfigSnapshotCodec {
    const val CURRENT_SCHEMA_VERSION = 1
    const val MAX_PAYLOAD_BYTES = 256 * 1024
    const val MAX_STRING_LENGTH = 64 * 1024

    private const val TYPE_BOOLEAN = "boolean"
    private const val TYPE_INTEGER = "integer"
    private const val TYPE_LONG = "long"
    private const val TYPE_STRING = "string"

    fun encode(snapshot: NoRootConfigSnapshot): String {
        val settings = JSONObject()
        snapshot.values.toSortedMap().forEach { (key, value) ->
            require(isValidKey(key)) { "Invalid setting key" }
            val entry = JSONObject()
            when (value) {
                is Boolean -> {
                    entry.put("type", TYPE_BOOLEAN)
                    entry.put("value", value)
                }
                is Int -> {
                    entry.put("type", TYPE_INTEGER)
                    entry.put("value", value)
                }
                is Long -> {
                    entry.put("type", TYPE_LONG)
                    entry.put("value", value)
                }
                is String -> {
                    require(value.length <= MAX_STRING_LENGTH) { "Setting value is too long" }
                    entry.put("type", TYPE_STRING)
                    entry.put("value", value)
                }
                else -> error("Unsupported setting value type")
            }
            settings.put(key, entry)
        }
        val encoded = JSONObject()
            .put("schemaVersion", snapshot.schemaVersion)
            .put("catalogVersion", snapshot.catalogVersion)
            .put("modulePackage", snapshot.modulePackage)
            .put("moduleVersionCode", snapshot.moduleVersionCode)
            .put("revision", snapshot.revision)
            .put("adapterResetRevision", snapshot.adapterResetRevision)
            .put("enabled", snapshot.enabled)
            .put("settingCount", snapshot.values.size)
            .put("settings", settings)
            .toString()
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) {
            "No-root configuration is too large"
        }
        return encoded
    }

    fun decode(
        payload: String,
        expectedModulePackage: String? = null,
        expectedModuleVersionCode: Long? = null
    ): NoRootConfigSnapshot? = runCatching {
        if (payload.isBlank() || payload.toByteArray(Charsets.UTF_8).size > MAX_PAYLOAD_BYTES) {
            return@runCatching null
        }
        val root = JSONObject(payload)
        val schemaVersion = root.strictInt("schemaVersion") ?: return@runCatching null
        if (schemaVersion != CURRENT_SCHEMA_VERSION) return@runCatching null
        val catalogVersion = root.strictInt("catalogVersion") ?: return@runCatching null
        val modulePackage = root.strictString("modulePackage") ?: return@runCatching null
        val moduleVersionCode = root.strictLong("moduleVersionCode") ?: return@runCatching null
        val revision = root.strictLong("revision") ?: return@runCatching null
        val adapterResetRevision = root.strictLong("adapterResetRevision")
            ?: return@runCatching null
        val enabled = root.strictBoolean("enabled") ?: return@runCatching null
        val settingCount = root.strictInt("settingCount") ?: return@runCatching null
        if (catalogVersion <= 0 || modulePackage.isBlank() || moduleVersionCode <= 0L ||
            revision <= 0L || adapterResetRevision < 0L || settingCount < 0
        ) return@runCatching null
        if (expectedModulePackage != null && modulePackage != expectedModulePackage) {
            return@runCatching null
        }
        if (expectedModuleVersionCode != null && moduleVersionCode != expectedModuleVersionCode) {
            return@runCatching null
        }
        val settings = root.getJSONObject("settings")
        if (settings.length() != settingCount) return@runCatching null
        if (enabled && settingCount == 0) return@runCatching null
        if (!enabled && settingCount != 0) return@runCatching null

        val values = linkedMapOf<String, Any>()
        val keys = settings.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (!isValidKey(key)) return@runCatching null
            val entry = settings.getJSONObject(key)
            if (entry.length() != 2) return@runCatching null
            val value: Any = when (entry.strictString("type")) {
                TYPE_BOOLEAN -> entry.strictBoolean("value") ?: return@runCatching null
                TYPE_INTEGER -> entry.strictInt("value") ?: return@runCatching null
                TYPE_LONG -> entry.strictLong("value") ?: return@runCatching null
                TYPE_STRING -> (entry.strictString("value") ?: return@runCatching null).also {
                    if (it.length > MAX_STRING_LENGTH) return@runCatching null
                }
                else -> return@runCatching null
            }
            values[key] = value
        }
        NoRootConfigSnapshot(
            schemaVersion = schemaVersion,
            catalogVersion = catalogVersion,
            modulePackage = modulePackage,
            moduleVersionCode = moduleVersionCode,
            revision = revision,
            adapterResetRevision = adapterResetRevision,
            enabled = enabled,
            values = Collections.unmodifiableMap(values)
        )
    }.getOrNull()

    private fun isValidKey(key: String): Boolean =
        key.isNotBlank() && key.length <= 160 &&
            key.none { it == '/' || it == '\\' || it.code < 0x20 }

    private fun JSONObject.strictBoolean(key: String): Boolean? =
        opt(key) as? Boolean

    private fun JSONObject.strictInt(key: String): Int? = when (val value = opt(key)) {
        is Int -> value
        is Long -> value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
        else -> null
    }

    private fun JSONObject.strictLong(key: String): Long? = when (val value = opt(key)) {
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }

    private fun JSONObject.strictString(key: String): String? =
        opt(key) as? String
}
