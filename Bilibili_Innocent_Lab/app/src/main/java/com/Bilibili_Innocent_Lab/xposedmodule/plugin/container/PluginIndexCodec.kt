package com.Bilibili_Innocent_Lab.xposedmodule.plugin.container

import com.bilab.plugin.api.model.PluginManifest
import com.bilab.plugin.api.model.PluginPermission
import com.bilab.plugin.api.model.PluginQuarantineReason
import com.bilab.plugin.api.model.PluginTier
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/**
 * 持久化插件索引的单条记录，描述已验证安装包及其激活/隔离状态。
 */
internal data class PluginIndexEntry(
    val pluginId: String,
    val versionCode: Int,
    val versionName: String,
    val tier: PluginTier,
    val enabled: Boolean,
    val packageSha256: String,
    val publicKeySha256: String? = null,
    val permissions: Set<PluginPermission> = emptySet(),
    val quarantineReason: PluginQuarantineReason? = null
)

/**
 * 插件索引文档；通过 [PluginIndexCodec.canonicalDigest] 与 [RemoteHookConfigContract.KEY_PLUGIN_INDEX_DIGEST] 绑定。
 */
internal data class PluginIndexDocument(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val revision: Long = 0L,
    val entries: List<PluginIndexEntry> = emptyList()
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

/**
 * 插件索引 JSON 编解码与规范摘要校验器（fail-closed）。
 */
internal object PluginIndexCodec {
    const val CURRENT_SCHEMA_VERSION = PluginIndexDocument.CURRENT_SCHEMA_VERSION
    const val MAX_ENTRIES = 128
    const val MAX_INDEX_JSON_BYTES = 256 * 1024

    private val SHA256_HEX_REGEX = Regex("[0-9a-f]{64}")
    private val HEX_DIGITS = charArrayOf(
        '0', '1', '2', '3', '4', '5', '6', '7',
        '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
    )

    fun encodeJson(doc: PluginIndexDocument): String {
        require(doc.schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported plugin index schemaVersion=${doc.schemaVersion}"
        }
        require(doc.revision >= 0L) { "revision must not be negative: ${doc.revision}" }
        require(doc.entries.size <= MAX_ENTRIES) { "entries exceed MAX_ENTRIES=$MAX_ENTRIES" }
        val seenIds = HashSet<String>()
        val sortedEntries = doc.entries.sortedBy { it.pluginId }
        val entriesArray = JSONArray()
        for (entry in sortedEntries) {
            require(isValidEntry(entry)) { "Invalid plugin index entry: ${entry.pluginId}" }
            require(seenIds.add(entry.pluginId)) { "Duplicate pluginId '${entry.pluginId}'" }
            entriesArray.put(encodeEntry(entry))
        }
        return JSONObject()
            .put("schemaVersion", doc.schemaVersion)
            .put("revision", doc.revision)
            .put("entries", entriesArray)
            .toString()
    }

    fun decodeJson(json: String): PluginIndexDocument? {
        if (json.isBlank() || json.toByteArray(Charsets.UTF_8).size > MAX_INDEX_JSON_BYTES) {
            return null
        }
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val schemaVersion = asExactInt(root.opt("schemaVersion")) ?: return null
        if (schemaVersion != CURRENT_SCHEMA_VERSION) return null
        val revision = asExactLong(root.opt("revision")) ?: return null
        if (revision < 0L) return null

        val array = root.opt("entries") as? JSONArray ?: return null
        if (array.length() > MAX_ENTRIES) return null

        val entries = ArrayList<PluginIndexEntry>(array.length())
        val seenIds = HashSet<String>()
        for (i in 0 until array.length()) {
            val obj = array.opt(i) as? JSONObject ?: return null
            val entry = decodeEntry(obj) ?: return null
            if (!seenIds.add(entry.pluginId)) return null
            entries += entry
        }
        return PluginIndexDocument(
            schemaVersion = schemaVersion,
            revision = revision,
            entries = entries
        )
    }

    /**
     * 对完整索引文档（`schemaVersion`、`revision` 与全部已启用及已禁用条目）生成规范化 64-hex SHA-256 摘要。
     */
    fun canonicalDigest(doc: PluginIndexDocument): String {
        val sorted = doc.entries.sortedBy { it.pluginId }
        val canonicalBytes = ByteArrayOutputStream().use { baos ->
            DataOutputStream(baos).use { out ->
                out.writeInt(doc.schemaVersion)
                out.writeLong(doc.revision)
                out.writeInt(sorted.size)
                for (entry in sorted) {
                    out.writeString(entry.pluginId)
                    out.writeInt(entry.versionCode)
                    out.writeString(entry.versionName)
                    out.writeString(entry.tier.name)
                    out.writeBoolean(entry.enabled)
                    out.writeString(entry.packageSha256)
                    out.writeNullableString(entry.publicKeySha256)
                    val sortedPerms = entry.permissions.map { it.name }.sorted()
                    out.writeInt(sortedPerms.size)
                    for (perm in sortedPerms) {
                        out.writeString(perm)
                    }
                    out.writeNullableString(entry.quarantineReason?.name)
                }
            }
            baos.toByteArray()
        }
        return sha256Hex(MessageDigest.getInstance("SHA-256").digest(canonicalBytes))
    }

    /**
     * 计算索引文档的完整规范摘要；与 [canonicalDigest] 一致，保障启用和禁用列表均受完整校验。
     */
    fun computeDigest(doc: PluginIndexDocument): String = canonicalDigest(doc)

    /**
     * 生成绑定至 `RemoteHookConfigContract.KEY_PLUGIN_INDEX_DIGEST` 的摘要值：
     * 无活跃插件时返回 `""`（触发宿主 0 插件零开销短路），否则返回完整规范化 64-hex SHA-256。
     */
    fun expectedRemoteDigest(doc: PluginIndexDocument): String =
        if (doc.entries.none { it.enabled && it.quarantineReason == null }) "" else canonicalDigest(doc)

    /**
     * [expectedRemoteDigest] 的语义别名。
     */
    fun activeDigest(doc: PluginIndexDocument): String = expectedRemoteDigest(doc)

    /**
     * 严格校验原始索引 JSON 与远端信任锚摘要 [expectedDigest]：
     * - `expectedDigest` 为 `""` 时，仅允许可解码且不存在任何启用条目的索引；
     * - `expectedDigest` 非空时，必须为 64-hex 且常量时间匹配完整 [canonicalDigest]，防止篡改启用或禁用条目。
     */
    fun verifyIndexDigest(json: String, expectedDigest: String): Boolean {
        val doc = decodeJson(json) ?: return false
        return verifyDigest(doc, expectedDigest)
    }

    fun verifyDigest(doc: PluginIndexDocument, expectedDigest: String): Boolean {
        if (doc.schemaVersion != CURRENT_SCHEMA_VERSION || doc.revision < 0L) return false
        if (doc.entries.any { !isValidEntry(it) }) return false
        if (expectedDigest.isEmpty()) {
            return doc.entries.none { it.enabled }
        }
        if (!SHA256_HEX_REGEX.matches(expectedDigest)) return false
        val actual = canonicalDigest(doc)
        return MessageDigest.isEqual(
            actual.toByteArray(Charsets.US_ASCII),
            expectedDigest.toByteArray(Charsets.US_ASCII)
        )
    }

    private fun encodeEntry(entry: PluginIndexEntry): JSONObject {
        val obj = JSONObject()
            .put("pluginId", entry.pluginId)
            .put("versionCode", entry.versionCode)
            .put("versionName", entry.versionName)
            .put("tier", entry.tier.name)
            .put("enabled", entry.enabled)
            .put("packageSha256", entry.packageSha256)
            .put("permissions", JSONArray(entry.permissions.map { it.name }.sorted()))
        if (entry.publicKeySha256 != null) {
            obj.put("publicKeySha256", entry.publicKeySha256)
        }
        if (entry.quarantineReason != null) {
            obj.put("quarantineReason", entry.quarantineReason.name)
        }
        return obj
    }

    private fun decodeEntry(obj: JSONObject): PluginIndexEntry? {
        val pluginId = obj.opt("pluginId") as? String ?: return null
        val versionCode = asExactInt(obj.opt("versionCode")) ?: return null
        val versionName = obj.opt("versionName") as? String ?: return null
        val tierName = obj.opt("tier") as? String ?: return null
        val tier = PluginTier.entries.firstOrNull { it.name == tierName } ?: return null
        val enabled = obj.opt("enabled") as? Boolean ?: return null
        val packageSha256 = obj.opt("packageSha256") as? String ?: return null

        val publicKeySha256 = if (obj.has("publicKeySha256") && !obj.isNull("publicKeySha256")) {
            obj.opt("publicKeySha256") as? String ?: return null
        } else {
            null
        }

        val permsArray = obj.opt("permissions") as? JSONArray ?: return null
        val permissions = linkedSetOf<PluginPermission>()
        for (i in 0 until permsArray.length()) {
            val permName = permsArray.opt(i) as? String ?: return null
            val perm = PluginPermission.entries.firstOrNull { it.name == permName } ?: return null
            permissions += perm
        }

        val quarantineReason = if (obj.has("quarantineReason") && !obj.isNull("quarantineReason")) {
            val reasonName = obj.opt("quarantineReason") as? String ?: return null
            PluginQuarantineReason.entries.firstOrNull { it.name == reasonName } ?: return null
        } else {
            null
        }

        val entry = PluginIndexEntry(
            pluginId = pluginId,
            versionCode = versionCode,
            versionName = versionName,
            tier = tier,
            enabled = enabled,
            packageSha256 = packageSha256,
            publicKeySha256 = publicKeySha256,
            permissions = permissions,
            quarantineReason = quarantineReason
        )
        return entry.takeIf(::isValidEntry)
    }

    private fun isValidEntry(entry: PluginIndexEntry): Boolean {
        if (!PluginManifest.ID_REGEX.matches(entry.pluginId) || entry.pluginId.contains("..")) return false
        if (entry.versionCode <= 0 || entry.versionName.isBlank()) return false
        if (!SHA256_HEX_REGEX.matches(entry.packageSha256)) return false
        if (entry.publicKeySha256 != null && !SHA256_HEX_REGEX.matches(entry.publicKeySha256)) return false
        if (entry.tier == PluginTier.DECLARATIVE_BUNDLE &&
            PluginPermission.L3_NATIVE_OR_PRIVILEGED in entry.permissions
        ) {
            return false
        }
        if (entry.enabled && entry.quarantineReason != null) return false
        return true
    }

    private fun asExactInt(value: Any?): Int? = when (value) {
        is Int -> value
        is Long -> if (value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) value.toInt() else null
        else -> null
    }

    private fun asExactLong(value: Any?): Long? = when (value) {
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }

    private fun DataOutputStream.writeNullableString(value: String?) {
        if (value == null) {
            writeBoolean(false)
        } else {
            writeBoolean(true)
            writeString(value)
        }
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            chars[i * 2] = HEX_DIGITS[v ushr 4]
            chars[i * 2 + 1] = HEX_DIGITS[v and 0x0F]
        }
        return String(chars)
    }
}
