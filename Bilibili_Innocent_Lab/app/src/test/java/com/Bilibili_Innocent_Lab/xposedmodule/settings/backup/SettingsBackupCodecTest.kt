package com.Bilibili_Innocent_Lab.xposedmodule.settings.backup

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.charset.StandardCharsets

class SettingsBackupCodecTest {

    @Test
    fun `round trip preserves the typed document`() {
        val document = sampleDocument()
        val decoded = SettingsBackupCodec.decode(SettingsBackupCodec.encodeToBytes(document))
        assertEquals(document.copy(settings = document.settings.sortedBy { it.id }), decoded)
    }

    @Test
    fun `encoding is stable regardless of input setting order`() {
        val document = sampleDocument()
        val reversed = document.copy(settings = document.settings.reversed())
        assertTrue(
            SettingsBackupCodec.encodeToBytes(document)
                .contentEquals(SettingsBackupCodec.encodeToBytes(reversed))
        )
    }

    @Test
    fun `tampering with a value fails integrity validation`() {
        val root = JSONObject(SettingsBackupCodec.encode(sampleDocument()))
        root.getJSONArray("settings").getJSONObject(0).put("value", false)
        assertFormatError(BackupFormatError.INVALID_INTEGRITY) {
            SettingsBackupCodec.decode(root.toString().toByteArray(StandardCharsets.UTF_8))
        }
    }

    @Test
    fun `wrong product and future format are rejected before import planning`() {
        val wrongProduct = JSONObject(SettingsBackupCodec.encode(sampleDocument()))
            .put("productId", "another-product")
        assertFormatError(BackupFormatError.WRONG_PRODUCT) {
            SettingsBackupCodec.decode(wrongProduct.toString().toByteArray())
        }

        val future = JSONObject(SettingsBackupCodec.encode(sampleDocument()))
            .put("formatVersion", SettingsBackupCodec.CURRENT_FORMAT_VERSION + 1)
        assertFormatError(BackupFormatError.UNSUPPORTED_FORMAT) {
            SettingsBackupCodec.decode(future.toString().toByteArray())
        }
    }

    @Test
    fun `published v1 stays in the supported decoder dispatch`() {
        assertTrue(1 in SettingsBackupCodec.SUPPORTED_FORMAT_VERSIONS)
        assertEquals(1, SettingsBackupCodec.decode(
            SettingsBackupCodec.encodeToBytes(sampleDocument())
        ).formatVersion)
    }

    @Test
    fun `trailing data after a valid root is rejected`() {
        val withTrailingData = SettingsBackupCodec.encodeToBytes(sampleDocument()) +
            "\ntrailing-data".toByteArray(StandardCharsets.UTF_8)
        assertFormatError(BackupFormatError.INVALID_JSON) {
            SettingsBackupCodec.decode(withTrailingData)
        }
    }

    @Test
    fun `strict structure rejects unknown fields and record count mismatch`() {
        val unknown = JSONObject(SettingsBackupCodec.encode(sampleDocument())).put("extra", true)
        assertFormatError(BackupFormatError.INVALID_STRUCTURE) {
            SettingsBackupCodec.decode(unknown.toString().toByteArray())
        }

        val wrongCount = JSONObject(SettingsBackupCodec.encode(sampleDocument()))
        wrongCount.getJSONObject("scope").put("recordCount", 99)
        assertFormatError(BackupFormatError.INVALID_STRUCTURE) {
            SettingsBackupCodec.decode(wrongCount.toString().toByteArray())
        }
    }

    @Test
    fun `strict value types reject strings floats and duplicates`() {
        val booleanAsString = JSONObject(SettingsBackupCodec.encode(sampleDocument()))
        booleanAsString.getJSONArray("settings").getJSONObject(0).put("value", "false")
        assertFormatError(BackupFormatError.INVALID_STRUCTURE) {
            SettingsBackupCodec.decode(booleanAsString.toString().toByteArray())
        }

        val integerAsFloat = JSONObject(SettingsBackupCodec.encode(sampleDocument()))
            .toString()
            .replace("\"value\":3", "\"value\":3.5")
        assertFormatError(BackupFormatError.INVALID_STRUCTURE) {
            SettingsBackupCodec.decode(integerAsFloat.toByteArray())
        }

        val duplicate = JSONObject(SettingsBackupCodec.encode(sampleDocument()))
        duplicate.getJSONArray("settings").getJSONObject(1).put("id", "sample.boolean")
        assertFormatError(BackupFormatError.INVALID_STRUCTURE) {
            SettingsBackupCodec.decode(duplicate.toString().toByteArray())
        }
    }

    @Test
    fun `invalid utf8 oversized input and unpaired surrogate are rejected`() {
        assertFormatError(BackupFormatError.INVALID_UTF8) {
            SettingsBackupCodec.decode(byteArrayOf(0xC3.toByte(), 0x28))
        }
        assertFormatError(BackupFormatError.TOO_LARGE) {
            SettingsBackupCodec.decode(ByteArray(SettingsBackupCodec.MAX_FILE_BYTES + 1))
        }

        val escapedSurrogate = SettingsBackupCodec.encode(sampleDocument())
            .replace("hello 世界", "\\uD800")
        assertFormatError(BackupFormatError.INVALID_STRUCTURE) {
            SettingsBackupCodec.decode(escapedSurrogate.toByteArray(StandardCharsets.UTF_8))
        }
    }

    @Test
    fun `encoding refuses a document whose final utf8 file exceeds one mibibyte`() {
        val longText = "界".repeat(SettingSpec.DEFAULT_MAX_STRING_LENGTH)
        val records = (0 until 17).map { index ->
            BackupSetting(
                id = "large.value.$index",
                valueVersion = 1,
                explicit = true,
                value = SettingValue.Text(longText)
            )
        }
        val oversized = sampleDocument().copy(
            scope = BackupScope(SettingsCatalog.SCOPE_ID, true, records.size),
            settings = records
        )
        assertFormatError(BackupFormatError.TOO_LARGE) {
            SettingsBackupCodec.encodeToBytes(oversized)
        }
    }

    private fun sampleDocument(): SettingsBackupDocument = SettingsBackupDocument(
        productId = SettingsCatalog.PRODUCT_ID,
        formatVersion = SettingsBackupCodec.CURRENT_FORMAT_VERSION,
        catalogVersion = 1,
        createdAtEpochMs = 1_700_000_000_000L,
        source = BackupSource("1.2.3", 12, "com.example.module"),
        scope = BackupScope(SettingsCatalog.SCOPE_ID, complete = true, recordCount = 3),
        settings = listOf(
            BackupSetting("sample.boolean", 1, explicit = true, SettingValue.Bool(true)),
            BackupSetting("sample.integer", 1, explicit = true, SettingValue.IntValue(3)),
            BackupSetting("sample.text", 1, explicit = false, SettingValue.Text("hello 世界"))
        )
    )

    private fun assertFormatError(expected: BackupFormatError, block: () -> Unit) {
        try {
            block()
            fail("Expected SettingsBackupFormatException")
        } catch (exception: SettingsBackupFormatException) {
            assertEquals(expected, exception.error)
        }
    }
}
