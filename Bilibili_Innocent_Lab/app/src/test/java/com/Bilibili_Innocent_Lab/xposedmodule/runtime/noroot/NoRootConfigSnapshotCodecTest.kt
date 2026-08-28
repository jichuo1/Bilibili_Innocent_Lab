package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class NoRootConfigSnapshotCodecTest {

    @Test
    fun `round trip preserves every supported value type`() {
        val source = enabledSnapshot(
            values = linkedMapOf(
                "boolean.value" to true,
                "integer.value" to 37,
                "long.value" to 9_090_300L,
                "string.value" to "emoji 🐶 与中文"
            )
        )

        val encoded = NoRootConfigSnapshotCodec.encode(source)
        val decoded = NoRootConfigSnapshotCodec.decode(
            encoded,
            expectedModulePackage = MODULE_PACKAGE,
            expectedModuleVersionCode = MODULE_VERSION
        )

        assertEquals(source, decoded)
        try {
            @Suppress("UNCHECKED_CAST")
            (decoded!!.values as MutableMap<String, Any>)["new.value"] = false
            fail("Decoded settings must be immutable")
        } catch (_: UnsupportedOperationException) {
            // Expected: target Hook code only receives an immutable startup snapshot.
        }
    }

    @Test
    fun `disabled tombstone round trips only with an empty value map`() {
        val tombstone = enabledSnapshot(values = emptyMap()).copy(enabled = false)

        assertEquals(
            tombstone,
            NoRootConfigSnapshotCodec.decode(NoRootConfigSnapshotCodec.encode(tombstone))
        )

        val invalidEnabledEmpty = JSONObject(NoRootConfigSnapshotCodec.encode(tombstone))
            .put("enabled", true)
        assertNull(NoRootConfigSnapshotCodec.decode(invalidEnabledEmpty.toString()))
    }

    @Test
    fun `decode rejects payload over the byte limit`() {
        val oversized = "x".repeat(NoRootConfigSnapshotCodec.MAX_PAYLOAD_BYTES + 1)

        assertNull(NoRootConfigSnapshotCodec.decode(oversized))
    }

    @Test
    fun `decode rejects unknown setting type`() {
        val root = JSONObject(NoRootConfigSnapshotCodec.encode(enabledSnapshot()))
        root.getJSONObject("settings")
            .getJSONObject("feature.enabled")
            .put("type", "double")

        assertNull(NoRootConfigSnapshotCodec.decode(root.toString()))
    }

    @Test
    fun `decode never coerces strings into typed values`() {
        val booleanRoot = JSONObject(NoRootConfigSnapshotCodec.encode(enabledSnapshot()))
        booleanRoot.getJSONObject("settings")
            .getJSONObject("feature.enabled")
            .put("value", "true")
        assertNull(NoRootConfigSnapshotCodec.decode(booleanRoot.toString()))

        val integerRoot = JSONObject(
            NoRootConfigSnapshotCodec.encode(
                enabledSnapshot(values = mapOf("feature.level" to 6))
            )
        )
        integerRoot.getJSONObject("settings")
            .getJSONObject("feature.level")
            .put("value", "6")
        assertNull(NoRootConfigSnapshotCodec.decode(integerRoot.toString()))
    }

    @Test
    fun `decode rejects unsupported schema`() {
        val root = JSONObject(NoRootConfigSnapshotCodec.encode(enabledSnapshot()))
            .put("schemaVersion", NoRootConfigSnapshotCodec.CURRENT_SCHEMA_VERSION + 1)

        assertNull(NoRootConfigSnapshotCodec.decode(root.toString()))
    }

    @Test
    fun `decode rejects a different module identity`() {
        val payload = NoRootConfigSnapshotCodec.encode(enabledSnapshot())

        assertNull(
            NoRootConfigSnapshotCodec.decode(
                payload,
                expectedModulePackage = "another.module",
                expectedModuleVersionCode = MODULE_VERSION
            )
        )
        assertNull(
            NoRootConfigSnapshotCodec.decode(
                payload,
                expectedModulePackage = MODULE_PACKAGE,
                expectedModuleVersionCode = MODULE_VERSION + 1L
            )
        )
    }

    private fun enabledSnapshot(
        values: Map<String, Any> = mapOf("feature.enabled" to true)
    ) = NoRootConfigSnapshot(
        schemaVersion = NoRootConfigSnapshotCodec.CURRENT_SCHEMA_VERSION,
        catalogVersion = 3,
        modulePackage = MODULE_PACKAGE,
        moduleVersionCode = MODULE_VERSION,
        revision = 1_700_000_000_001L,
        adapterResetRevision = 2L,
        enabled = true,
        values = values
    )

    private companion object {
        const val MODULE_PACKAGE = "com.Bilibili_Innocent_Lab.xposedmodule"
        const val MODULE_VERSION = 8L
    }
}
