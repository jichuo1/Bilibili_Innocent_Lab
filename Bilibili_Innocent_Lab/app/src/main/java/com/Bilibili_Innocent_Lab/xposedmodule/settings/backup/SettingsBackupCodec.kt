package com.Bilibili_Innocent_Lab.xposedmodule.settings.backup

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal enum class BackupFormatError {
    TOO_LARGE,
    INVALID_UTF8,
    INVALID_JSON,
    UNSUPPORTED_FORMAT,
    WRONG_PRODUCT,
    INVALID_STRUCTURE,
    INVALID_INTEGRITY
}

internal class SettingsBackupFormatException(
    val error: BackupFormatError,
    message: String,
    cause: Throwable? = null
) : IllegalArgumentException(message, cause)

/** JSON 文件编解码和语义完整性校验；不依赖当前 catalog，因而能保留跨版本未知项。 */
internal object SettingsBackupCodec {
    const val FORMAT_NAME = "bilab-settings-backup"
    private const val FORMAT_VERSION_V1 = 1
    const val CURRENT_FORMAT_VERSION = FORMAT_VERSION_V1
    const val MAX_FILE_BYTES = 1024 * 1024
    const val MAX_SETTINGS = 512
    /** 已发布格式必须永久保留在显式分派表中。 */
    val SUPPORTED_FORMAT_VERSIONS: Set<Int> = setOf(FORMAT_VERSION_V1)

    private const val MAX_JSON_NESTING = 16
    private const val MAX_ID_LENGTH = 128
    private const val MAX_VERSION_NAME_LENGTH = 128
    private const val MAX_VALUE_VERSION = 100_000
    private const val DIGEST_ALGORITHM = "SHA-256"
    private const val CANONICALIZATION = "bilab-settings-binary-v1"
    private val hexAlphabet = "0123456789abcdef".toCharArray()
    private val idPattern = Regex("[a-z0-9][a-z0-9._-]{0,127}")

    fun encode(document: SettingsBackupDocument): String {
        validateDocument(document, CURRENT_FORMAT_VERSION)
        val digest = digestHex(canonicalBytes(document))
        val root = JSONObject()
            .put("format", FORMAT_NAME)
            .put("formatVersion", document.formatVersion)
            .put("productId", document.productId)
            .put("catalogVersion", document.catalogVersion)
            .put("createdAtEpochMs", document.createdAtEpochMs)
            .put(
                "source",
                JSONObject()
                    .put("versionName", document.source.versionName)
                    .put("versionCode", document.source.versionCode)
                    .put("applicationId", document.source.applicationId)
            )
            .put(
                "scope",
                JSONObject()
                    .put("id", document.scope.id)
                    .put("complete", document.scope.complete)
                    .put("recordCount", document.scope.recordCount)
            )

        val settings = JSONArray()
        document.settings.sortedBy(BackupSetting::id).forEach { setting ->
            settings.put(
                JSONObject()
                    .put("id", setting.id)
                    .put("valueVersion", setting.valueVersion)
                    .put("type", setting.value.typeName)
                    .put("explicit", setting.explicit)
                    .put("value", setting.value.toJsonValue())
            )
        }
        root.put("settings", settings)
        root.put(
            "integrity",
            JSONObject()
                .put("algorithm", DIGEST_ALGORITHM)
                .put("canonicalization", CANONICALIZATION)
                .put("digest", digest)
        )
        val encoded = root.toString(2)
        if (encodeUtf8Strict(encoded).size > MAX_FILE_BYTES) {
            fail(BackupFormatError.TOO_LARGE, "Encoded backup exceeds the size limit")
        }
        return encoded
    }

    fun encodeToBytes(document: SettingsBackupDocument): ByteArray = encodeUtf8Strict(encode(document))

    fun decode(bytes: ByteArray): SettingsBackupDocument {
        if (bytes.isEmpty() || bytes.size > MAX_FILE_BYTES) {
            fail(BackupFormatError.TOO_LARGE, "Backup size is outside the supported range")
        }
        val text = decodeUtf8Strict(bytes)
        ensureNestingDepth(text)
        val root = parseRoot(text)
        if (requireString(root, "format") != FORMAT_NAME) {
            fail(BackupFormatError.UNSUPPORTED_FORMAT, "Unknown backup format")
        }
        val formatVersion = requireInt(root, "formatVersion")
        return when (formatVersion) {
            FORMAT_VERSION_V1 -> decodeVersion1(root)
            else -> fail(
                BackupFormatError.UNSUPPORTED_FORMAT,
                "Unsupported backup format version: $formatVersion"
            )
        }
    }

    /** v1 解析器为已发布兼容入口；新增格式时增加分支，不得覆盖或删除此实现。 */
    private fun decodeVersion1(root: JSONObject): SettingsBackupDocument {
        requireKeys(
            root,
            setOf(
                "format",
                "formatVersion",
                "productId",
                "catalogVersion",
                "createdAtEpochMs",
                "source",
                "scope",
                "settings",
                "integrity"
            ),
            "root"
        )
        val formatVersion = requireInt(root, "formatVersion")
        if (formatVersion != FORMAT_VERSION_V1) {
            fail(BackupFormatError.UNSUPPORTED_FORMAT, "Not a v1 backup")
        }
        val productId = requireString(root, "productId")
        if (productId != SettingsCatalog.PRODUCT_ID) {
            fail(BackupFormatError.WRONG_PRODUCT, "Backup belongs to another product")
        }

        val sourceJson = requireObject(root, "source")
        requireKeys(sourceJson, setOf("versionName", "versionCode", "applicationId"), "source")
        val source = BackupSource(
            versionName = requireString(sourceJson, "versionName"),
            versionCode = requireLong(sourceJson, "versionCode"),
            applicationId = requireString(sourceJson, "applicationId")
        )

        val scopeJson = requireObject(root, "scope")
        requireKeys(scopeJson, setOf("id", "complete", "recordCount"), "scope")
        val scope = BackupScope(
            id = requireString(scopeJson, "id"),
            complete = requireBoolean(scopeJson, "complete"),
            recordCount = requireInt(scopeJson, "recordCount")
        )

        val settingsJson = requireArray(root, "settings")
        if (scope.recordCount != settingsJson.length()) {
            fail(BackupFormatError.INVALID_STRUCTURE, "Scope record count does not match settings")
        }
        if (settingsJson.length() > MAX_SETTINGS) {
            fail(BackupFormatError.INVALID_STRUCTURE, "Too many settings")
        }
        val seenIds = hashSetOf<String>()
        val settings = ArrayList<BackupSetting>(settingsJson.length())
        for (index in 0 until settingsJson.length()) {
            val item = settingsJson.opt(index) as? JSONObject
                ?: fail(BackupFormatError.INVALID_STRUCTURE, "settings[$index] must be an object")
            requireKeys(item, setOf("id", "valueVersion", "type", "explicit", "value"), "settings[$index]")
            val id = requireString(item, "id")
            if (!seenIds.add(id)) {
                fail(BackupFormatError.INVALID_STRUCTURE, "Duplicate setting id: $id")
            }
            val typeName = requireString(item, "type")
            val type = SettingValueType.fromSerializedName(typeName)
                ?: fail(BackupFormatError.INVALID_STRUCTURE, "Unknown value type: $typeName")
            val value = parseValue(item.getOrFormatFailure("value"), type, index)
            settings += BackupSetting(
                id = id,
                valueVersion = requireInt(item, "valueVersion"),
                explicit = requireBoolean(item, "explicit"),
                value = value
            )
        }

        val document = SettingsBackupDocument(
            productId = productId,
            formatVersion = formatVersion,
            catalogVersion = requireInt(root, "catalogVersion"),
            createdAtEpochMs = requireLong(root, "createdAtEpochMs"),
            source = source,
            scope = scope,
            settings = settings
        )
        validateDocument(document, FORMAT_VERSION_V1)

        val integrity = requireObject(root, "integrity")
        requireKeys(integrity, setOf("algorithm", "canonicalization", "digest"), "integrity")
        if (requireString(integrity, "algorithm") != DIGEST_ALGORITHM) {
            fail(BackupFormatError.INVALID_INTEGRITY, "Unsupported integrity algorithm")
        }
        if (requireString(integrity, "canonicalization") != CANONICALIZATION) {
            fail(BackupFormatError.INVALID_INTEGRITY, "Unsupported canonicalization")
        }
        val expected = requireString(integrity, "digest")
        if (!expected.matches(Regex("[0-9a-f]{64}"))) {
            fail(BackupFormatError.INVALID_INTEGRITY, "Invalid integrity digest")
        }
        val actual = digestHex(canonicalBytes(document))
        if (!MessageDigest.isEqual(hexToBytes(expected), hexToBytes(actual))) {
            fail(BackupFormatError.INVALID_INTEGRITY, "Backup integrity check failed")
        }
        return document
    }

    private fun parseRoot(text: String): JSONObject = try {
        val tokener = JSONTokener(text)
        val root = tokener.nextValue() as? JSONObject
            ?: fail(BackupFormatError.INVALID_JSON, "Backup root must be an object")
        if (tokener.nextClean() != '\u0000') {
            fail(BackupFormatError.INVALID_JSON, "Trailing data after backup root")
        }
        root
    } catch (throwable: JSONException) {
        fail(BackupFormatError.INVALID_JSON, "Backup is not valid JSON", throwable)
    }

    private fun validateDocument(document: SettingsBackupDocument, expectedFormatVersion: Int) {
        if (document.productId != SettingsCatalog.PRODUCT_ID ||
            document.formatVersion != expectedFormatVersion ||
            document.catalogVersion <= 0 ||
            document.createdAtEpochMs <= 0L ||
            document.source.versionCode < 0L ||
            document.source.versionName.isBlank() ||
            document.source.versionName.length > MAX_VERSION_NAME_LENGTH ||
            document.source.applicationId.isBlank() ||
            document.source.applicationId.length > 256 ||
            document.scope.id != SettingsCatalog.SCOPE_ID ||
            document.scope.recordCount != document.settings.size ||
            document.scope.recordCount < 0 ||
            document.settings.size > MAX_SETTINGS
        ) {
            fail(BackupFormatError.INVALID_STRUCTURE, "Invalid backup metadata")
        }
        val ids = hashSetOf<String>()
        document.settings.forEach { setting ->
            if (setting.id.length > MAX_ID_LENGTH || !idPattern.matches(setting.id) || !ids.add(setting.id)) {
                fail(BackupFormatError.INVALID_STRUCTURE, "Invalid or duplicate setting id: ${setting.id}")
            }
            if (setting.valueVersion !in 1..MAX_VALUE_VERSION) {
                fail(BackupFormatError.INVALID_STRUCTURE, "Invalid value version for ${setting.id}")
            }
            if (setting.value is SettingValue.Text &&
                setting.value.value.length > SettingSpec.DEFAULT_MAX_STRING_LENGTH
            ) {
                fail(BackupFormatError.INVALID_STRUCTURE, "String value is too long for ${setting.id}")
            }
        }
    }

    /**
     * 固定二进制规范化，不依赖 JSONObject 键迭代顺序，也不使用 DataOutputStream.writeUTF。
     */
    private fun canonicalBytes(document: SettingsBackupDocument): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeString(CANONICALIZATION)
            output.writeString(document.productId)
            output.writeInt(document.formatVersion)
            output.writeInt(document.catalogVersion)
            output.writeLong(document.createdAtEpochMs)
            output.writeString(document.source.versionName)
            output.writeLong(document.source.versionCode)
            output.writeString(document.source.applicationId)
            output.writeString(document.scope.id)
            output.writeBoolean(document.scope.complete)
            output.writeInt(document.scope.recordCount)
            val settings = document.settings.sortedBy(BackupSetting::id)
            output.writeInt(settings.size)
            settings.forEach { setting ->
                output.writeString(setting.id)
                output.writeInt(setting.valueVersion)
                output.writeBoolean(setting.explicit)
                when (val value = setting.value) {
                    is SettingValue.Bool -> {
                        output.writeByte(1)
                        output.writeBoolean(value.value)
                    }
                    is SettingValue.IntValue -> {
                        output.writeByte(2)
                        output.writeInt(value.value)
                    }
                    is SettingValue.Text -> {
                        output.writeByte(3)
                        output.writeString(value.value)
                    }
                }
            }
        }
        return bytes.toByteArray()
    }

    private fun DataOutputStream.writeString(value: String) {
        val encoded = encodeUtf8Strict(value)
        writeInt(encoded.size)
        write(encoded)
    }

    private fun SettingValue.toJsonValue(): Any = when (this) {
        is SettingValue.Bool -> value
        is SettingValue.IntValue -> value
        is SettingValue.Text -> value
    }

    private fun parseValue(raw: Any, type: SettingValueType, index: Int): SettingValue = when (type) {
        SettingValueType.BOOLEAN -> SettingValue.Bool(
            raw as? Boolean
                ?: fail(BackupFormatError.INVALID_STRUCTURE, "settings[$index].value must be a boolean")
        )
        SettingValueType.INTEGER -> SettingValue.IntValue(
            strictInt(raw, "settings[$index].value")
        )
        SettingValueType.STRING -> SettingValue.Text(
            raw as? String
                ?: fail(BackupFormatError.INVALID_STRUCTURE, "settings[$index].value must be a string")
        )
    }

    private fun decodeUtf8Strict(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (throwable: CharacterCodingException) {
        fail(BackupFormatError.INVALID_UTF8, "Backup is not valid UTF-8", throwable)
    }

    private fun encodeUtf8Strict(value: String): ByteArray = try {
        val buffer = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(value))
        ByteArray(buffer.remaining()).also(buffer::get)
    } catch (throwable: CharacterCodingException) {
        fail(BackupFormatError.INVALID_STRUCTURE, "Backup contains invalid Unicode", throwable)
    }

    private fun ensureNestingDepth(text: String) {
        var depth = 0
        var inString = false
        var escaped = false
        text.forEach { character ->
            if (inString) {
                if (escaped) escaped = false
                else if (character == '\\') escaped = true
                else if (character == '"') inString = false
            } else {
                when (character) {
                    '"' -> inString = true
                    '{', '[' -> {
                        depth += 1
                        if (depth > MAX_JSON_NESTING) {
                            fail(BackupFormatError.INVALID_JSON, "JSON nesting is too deep")
                        }
                    }
                    '}', ']' -> depth -= 1
                }
                if (depth < 0) fail(BackupFormatError.INVALID_JSON, "Unbalanced JSON")
            }
        }
        if (inString || depth != 0) fail(BackupFormatError.INVALID_JSON, "Incomplete JSON")
    }

    private fun requireKeys(value: JSONObject, expected: Set<String>, location: String) {
        val actual = buildSet {
            val iterator = value.keys()
            while (iterator.hasNext()) add(iterator.next())
        }
        if (actual != expected) {
            fail(BackupFormatError.INVALID_STRUCTURE, "$location contains missing or unknown fields")
        }
    }

    private fun requireString(value: JSONObject, key: String): String =
        value.getOrFormatFailure(key) as? String
            ?: fail(BackupFormatError.INVALID_STRUCTURE, "$key must be a string")

    private fun requireBoolean(value: JSONObject, key: String): Boolean =
        value.getOrFormatFailure(key) as? Boolean
            ?: fail(BackupFormatError.INVALID_STRUCTURE, "$key must be a boolean")

    private fun requireInt(value: JSONObject, key: String): Int =
        strictInt(value.getOrFormatFailure(key), key)

    private fun requireLong(value: JSONObject, key: String): Long =
        strictLong(value.getOrFormatFailure(key), key)

    private fun requireObject(value: JSONObject, key: String): JSONObject =
        value.getOrFormatFailure(key) as? JSONObject
            ?: fail(BackupFormatError.INVALID_STRUCTURE, "$key must be an object")

    private fun requireArray(value: JSONObject, key: String): JSONArray =
        value.getOrFormatFailure(key) as? JSONArray
            ?: fail(BackupFormatError.INVALID_STRUCTURE, "$key must be an array")

    private fun JSONObject.getOrFormatFailure(key: String): Any = try {
        get(key)
    } catch (throwable: JSONException) {
        fail(BackupFormatError.INVALID_STRUCTURE, "Missing field: $key", throwable)
    }

    private fun strictInt(raw: Any, location: String): Int {
        val number = when (raw) {
            is Int -> raw.toLong()
            is Long -> raw
            else -> fail(BackupFormatError.INVALID_STRUCTURE, "$location must be an integer")
        }
        if (number !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            fail(BackupFormatError.INVALID_STRUCTURE, "$location is outside Int range")
        }
        return number.toInt()
    }

    private fun strictLong(raw: Any, location: String): Long = when (raw) {
        is Int -> raw.toLong()
        is Long -> raw
        else -> fail(BackupFormatError.INVALID_STRUCTURE, "$location must be an integer")
    }

    private fun digestHex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance(DIGEST_ALGORITHM).digest(bytes)
        return CharArray(digest.size * 2).also { output ->
            digest.forEachIndexed { index, byte ->
                val value = byte.toInt() and 0xff
                output[index * 2] = hexAlphabet[value ushr 4]
                output[index * 2 + 1] = hexAlphabet[value and 0x0f]
            }
        }.concatToString()
    }

    private fun hexToBytes(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        val high = value[index * 2].digitToInt(16)
        val low = value[index * 2 + 1].digitToInt(16)
        ((high shl 4) or low).toByte()
    }

    private fun fail(
        error: BackupFormatError,
        message: String,
        cause: Throwable? = null
    ): Nothing = throw SettingsBackupFormatException(error, message, cause)
}
