package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.SkinId
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.runtime.SkinPreferenceCodec
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.runtime.SkinPreferenceDecodeIssue
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.runtime.SkinPreferenceDecodeResult
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.runtime.SkinPreferenceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SkinPreferenceCodecTest {

    private val attemptId = "attempt-codec-primary"

    @Test
    fun `schema version is a positive locked protocol value`() {
        assertEquals(1, SkinPreferenceCodec.CURRENT_SCHEMA_VERSION)
        assertTrue(SkinPreferenceCodec.CURRENT_SCHEMA_VERSION > 0)
    }

    @Test
    fun `all canonical states round trip without repair`() {
        listOf(
            SkinPreferenceState.MATERIAL_DEFAULT,
            SkinPreferenceState.pendingLiquid(0, attemptId),
            SkinPreferenceState.pendingLiquid(17, attemptId),
            SkinPreferenceState.confirmedLiquid(17, attemptId)
        ).forEach { state ->
            val decoded = SkinPreferenceCodec.decode(SkinPreferenceCodec.encode(state))

            assertEquals(state, decoded.state)
            assertFalse(decoded.needsRepair)
            assertEquals(SkinPreferenceDecodeIssue.NONE, decoded.issue)
        }
    }

    @Test
    fun `missing preference is a normal material default and not corruption`() {
        listOf<Map<String, Any?>>(
            emptyMap(),
            mapOf("unrelated_key" to "unrelated_value")
        ).forEach { raw ->
            val decoded = SkinPreferenceCodec.decode(raw)

            assertEquals(SkinPreferenceState.MATERIAL_DEFAULT, decoded.state)
            assertFalse(decoded.needsRepair)
            assertEquals(SkinPreferenceDecodeIssue.MISSING, decoded.issue)
        }
    }

    @Test
    fun `every missing required field falls back to material and requests repair`() {
        val encoded = SkinPreferenceCodec.encode(
            SkinPreferenceState.confirmedLiquid(4, attemptId)
        )
        listOf(
            SkinPreferenceCodec.KEY_SCHEMA_VERSION,
            SkinPreferenceCodec.KEY_SELECTED_SKIN,
            SkinPreferenceCodec.KEY_LAST_KNOWN_GOOD_SKIN,
            SkinPreferenceCodec.KEY_LIQUID_RENDERER_VERSION
        ).forEach { missingKey ->
            val raw = encoded.toMutableMap().apply { remove(missingKey) }

            assertRepair(
                result = SkinPreferenceCodec.decode(raw),
                issue = SkinPreferenceDecodeIssue.MISSING_FIELD
            )
        }
    }

    @Test
    fun `wrong field types never coerce into a valid preference`() {
        val encoded = SkinPreferenceCodec.encode(
            SkinPreferenceState.pendingLiquid(9, attemptId)
        )
        listOf<Pair<String, Any?>>(
            SkinPreferenceCodec.KEY_SCHEMA_VERSION to 1L,
            SkinPreferenceCodec.KEY_SELECTED_SKIN to true,
            SkinPreferenceCodec.KEY_LAST_KNOWN_GOOD_SKIN to 7,
            SkinPreferenceCodec.KEY_PENDING_SKIN to 1,
            SkinPreferenceCodec.KEY_LIQUID_RENDERER_VERSION to "9",
            SkinPreferenceCodec.KEY_ACTIVATION_ATTEMPT_ID to 9
        ).forEach { (key, wrongValue) ->
            val raw = encoded.toMutableMap().apply { put(key, wrongValue) }

            assertRepair(
                result = SkinPreferenceCodec.decode(raw),
                issue = SkinPreferenceDecodeIssue.TYPE_MISMATCH
            )
        }
    }

    @Test
    fun `unknown skin in any position falls back to material and requests repair`() {
        val encoded = SkinPreferenceCodec.encode(
            SkinPreferenceState.pendingLiquid(9, attemptId)
        )
        listOf(
            SkinPreferenceCodec.KEY_SELECTED_SKIN,
            SkinPreferenceCodec.KEY_LAST_KNOWN_GOOD_SKIN,
            SkinPreferenceCodec.KEY_PENDING_SKIN
        ).forEach { key ->
            val raw = encoded.toMutableMap().apply { put(key, "future_or_corrupt_skin") }

            assertRepair(
                result = SkinPreferenceCodec.decode(raw),
                issue = SkinPreferenceDecodeIssue.UNKNOWN_SKIN
            )
        }
    }

    @Test
    fun `negative renderer version falls back to material and requests repair`() {
        val raw = SkinPreferenceCodec.encode(
            SkinPreferenceState.confirmedLiquid(3, attemptId)
        )
            .toMutableMap()
            .apply { put(SkinPreferenceCodec.KEY_LIQUID_RENDERER_VERSION, -1) }

        assertRepair(
            result = SkinPreferenceCodec.decode(raw),
            issue = SkinPreferenceDecodeIssue.NEGATIVE_RENDERER_VERSION
        )
    }

    @Test
    fun `unsupported schema falls back to material and requests repair`() {
        listOf(0, 2, Int.MAX_VALUE).forEach { schema ->
            val raw = SkinPreferenceCodec.encode(SkinPreferenceState.MATERIAL_DEFAULT)
                .toMutableMap()
                .apply { put(SkinPreferenceCodec.KEY_SCHEMA_VERSION, schema) }

            assertRepair(
                result = SkinPreferenceCodec.decode(raw),
                issue = SkinPreferenceDecodeIssue.UNSUPPORTED_SCHEMA
            )
        }
    }

    @Test
    fun `inconsistent state combinations cannot bypass pending protocol`() {
        val validMaterial = SkinPreferenceCodec.encode(SkinPreferenceState.MATERIAL_DEFAULT)
        val validPending = SkinPreferenceCodec.encode(
            SkinPreferenceState.pendingLiquid(5, attemptId)
        )
        val invalidStates = listOf(
            validMaterial.toMutableMap().apply {
                put(SkinPreferenceCodec.KEY_LIQUID_RENDERER_VERSION, 5)
            },
            validPending.toMutableMap().apply {
                remove(SkinPreferenceCodec.KEY_PENDING_SKIN)
            },
            validPending.toMutableMap().apply {
                put(SkinPreferenceCodec.KEY_PENDING_SKIN, SkinId.MATERIAL_YOU.storageValue)
            },
            validPending.toMutableMap().apply {
                put(
                    SkinPreferenceCodec.KEY_LAST_KNOWN_GOOD_SKIN,
                    SkinId.LIQUID.storageValue
                )
            },
            validPending.toMutableMap().apply {
                remove(SkinPreferenceCodec.KEY_ACTIVATION_ATTEMPT_ID)
            }
        )

        invalidStates.forEach { raw ->
            assertRepair(
                result = SkinPreferenceCodec.decode(raw),
                issue = SkinPreferenceDecodeIssue.INCONSISTENT_STATE
            )
        }
    }

    @Test
    fun `null optional pending field is equivalent to an absent pending field`() {
        val confirmed = SkinPreferenceState.confirmedLiquid(6, attemptId)
        val raw = SkinPreferenceCodec.encode(confirmed).toMutableMap().apply {
            put(SkinPreferenceCodec.KEY_PENDING_SKIN, null)
        }

        val decoded = SkinPreferenceCodec.decode(raw)

        assertEquals(confirmed, decoded.state)
        assertFalse(decoded.needsRepair)
    }

    @Test
    fun `state factories reject negative renderer versions`() {
        assertThrows(IllegalArgumentException::class.java) {
            SkinPreferenceState.pendingLiquid(-1, attemptId)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SkinPreferenceState.confirmedLiquid(-1, attemptId)
        }
    }

    @Test
    fun `invalid activation attempt ids fail closed`() {
        listOf("", "   ", "x".repeat(129)).forEach { invalidAttemptId ->
            val raw = SkinPreferenceCodec.encode(
                SkinPreferenceState.pendingLiquid(5, attemptId)
            ).toMutableMap().apply {
                put(SkinPreferenceCodec.KEY_ACTIVATION_ATTEMPT_ID, invalidAttemptId)
            }

            assertRepair(
                result = SkinPreferenceCodec.decode(raw),
                issue = SkinPreferenceDecodeIssue.INVALID_ACTIVATION_ATTEMPT
            )
        }
    }

    private fun assertRepair(
        result: SkinPreferenceDecodeResult,
        issue: SkinPreferenceDecodeIssue
    ) {
        assertEquals(SkinPreferenceState.MATERIAL_DEFAULT, result.state)
        assertTrue(result.needsRepair)
        assertEquals(issue, result.issue)
    }
}
