package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.SkinId
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.runtime.SkinPreferenceCodec
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.runtime.SkinPreferenceDecodeIssue
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.runtime.SkinPreferenceDecodeResult
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.runtime.SkinPreferenceState
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.runtime.SkinProcessStateHolder
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.runtime.SkinRecoveryGuard
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.runtime.SkinRecoveryReason
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.runtime.SkinRenderSessionRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SkinIdTest {

    fun `storage values are stable protocol identifiers`() {
        assertEquals("material_you", SkinId.MATERIAL_YOU.storageValue)
        assertEquals("liquid_v1", SkinId.LIQUID.storageValue)
        assertEquals(
            SkinId.entries.size,
            SkinId.entries.map(SkinId::storageValue).distinct().size
        )
    }

    fun `storage value parsing is exact and rejects unknown aliases`() {
        SkinId.entries.forEach { skin ->
            assertEquals(skin, SkinId.fromStorageValue(skin.storageValue))
        }
        listOf(null, "", "MATERIAL_YOU", "liquid", "LIQUID_V1").forEach { raw ->
            assertNull("raw=$raw", SkinId.fromStorageValue(raw))
        }
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (SkinIdTest) -> Unit) {
            val s = SkinIdTest()
            block(s)
        }
        runIsolated { s -> s.`storage values are stable protocol identifiers`() }
        runIsolated { s -> s.`storage value parsing is exact and rejects unknown aliases`() }
    
        SkinPreferenceCodecCases().verifyAllCases()
        SkinProcessStateHolderCases().verifyAllCases()
        SkinRecoveryGuardCases().verifyAllCases()
        SkinRenderSessionRegistryCases().verifyAllCases()
    }
}

private class SkinPreferenceCodecCases {

    private val attemptId = "attempt-codec-primary"

    fun `schema version is a positive locked protocol value`() {
        assertEquals(1, SkinPreferenceCodec.CURRENT_SCHEMA_VERSION)
        assertTrue(SkinPreferenceCodec.CURRENT_SCHEMA_VERSION > 0)
    }

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

    fun `null optional pending field is equivalent to an absent pending field`() {
        val confirmed = SkinPreferenceState.confirmedLiquid(6, attemptId)
        val raw = SkinPreferenceCodec.encode(confirmed).toMutableMap().apply {
            put(SkinPreferenceCodec.KEY_PENDING_SKIN, null)
        }

        val decoded = SkinPreferenceCodec.decode(raw)

        assertEquals(confirmed, decoded.state)
        assertFalse(decoded.needsRepair)
    }

    fun `state factories reject negative renderer versions`() {
        assertThrows(IllegalArgumentException::class.java) {
            SkinPreferenceState.pendingLiquid(-1, attemptId)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SkinPreferenceState.confirmedLiquid(-1, attemptId)
        }
    }

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

    fun verifyAllCases() {
        fun runIsolated(block: (SkinPreferenceCodecCases) -> Unit) {
            val s = SkinPreferenceCodecCases()
            block(s)
        }
        runIsolated { s -> s.`schema version is a positive locked protocol value`() }
        runIsolated { s -> s.`all canonical states round trip without repair`() }
        runIsolated { s -> s.`missing preference is a normal material default and not corruption`() }
        runIsolated { s -> s.`every missing required field falls back to material and requests repair`() }
        runIsolated { s -> s.`wrong field types never coerce into a valid preference`() }
        runIsolated { s -> s.`unknown skin in any position falls back to material and requests repair`() }
        runIsolated { s -> s.`negative renderer version falls back to material and requests repair`() }
        runIsolated { s -> s.`unsupported schema falls back to material and requests repair`() }
        runIsolated { s -> s.`inconsistent state combinations cannot bypass pending protocol`() }
        runIsolated { s -> s.`null optional pending field is equivalent to an absent pending field`() }
        runIsolated { s -> s.`state factories reject negative renderer versions`() }
        runIsolated { s -> s.`invalid activation attempt ids fail closed`() }
    }
}

private class SkinProcessStateHolderCases {

    private val attemptId = "attempt-holder-primary"

    fun `process start loader runs only once across activity recreation`() {
        val holder = SkinProcessStateHolder()
        var loadCount = 0
        val loader = {
            loadCount += 1
            SkinPreferenceState.MATERIAL_DEFAULT
        }

        holder.current(loader)
        holder.current(loader)

        assertEquals(1, loadCount)
    }

    fun `pending liquid remains visible after same process activity recreation`() {
        val holder = SkinProcessStateHolder()
        val loader = { SkinPreferenceState.MATERIAL_DEFAULT }
        val mutation = holder.mutate(
            loader = loader,
            decide = { current ->
                SkinRecoveryGuard.beginSelection(current, SkinId.LIQUID, 1, attemptId)
            },
            persist = { true }
        )

        val recreatedActivityState = holder.current {
            error("Activity recreation must not reload process-start state")
        }

        assertTrue(mutation.persisted)
        assertTrue(recreatedActivityState.isLiquidPending)
    }

    fun `failed commit keeps current process state`() {
        val holder = SkinProcessStateHolder()
        val result = holder.mutate(
            loader = { SkinPreferenceState.MATERIAL_DEFAULT },
            decide = { current ->
                SkinRecoveryGuard.beginSelection(current, SkinId.LIQUID, 1, attemptId)
            },
            persist = { false }
        )

        assertFalse(result.persisted)
        assertEquals(SkinPreferenceState.MATERIAL_DEFAULT, result.state)
        assertEquals(SkinPreferenceState.MATERIAL_DEFAULT, holder.current { error("unused") })
    }

    fun `no-op decision does not call persistence`() {
        val holder = SkinProcessStateHolder()
        var persistCalled = false
        val confirmed = SkinPreferenceState.confirmedLiquid(1, attemptId)

        val result = holder.mutate(
            loader = { confirmed },
            decide = { current ->
                SkinRecoveryGuard.beginSelection(current, SkinId.LIQUID, 1, attemptId)
            },
            persist = {
                persistCalled = true
                true
            }
        )

        assertTrue(result.persisted)
        assertFalse(persistCalled)
        assertEquals(confirmed, result.state)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (SkinProcessStateHolderCases) -> Unit) {
            val s = SkinProcessStateHolderCases()
            block(s)
        }
        runIsolated { s -> s.`process start loader runs only once across activity recreation`() }
        runIsolated { s -> s.`pending liquid remains visible after same process activity recreation`() }
        runIsolated { s -> s.`failed commit keeps current process state`() }
        runIsolated { s -> s.`no-op decision does not call persistence`() }
    }
}

private class SkinRecoveryGuardCases {

    private val attemptA = "attempt-a"
    private val attemptB = "attempt-b"
    private val processAttempt = "attempt-process"

    fun `liquid selection stays pending until matching health confirmation`() {
        val pending = SkinRecoveryGuard.beginSelection(
            current = SkinPreferenceState.MATERIAL_DEFAULT,
            target = SkinId.LIQUID,
            currentLiquidRendererVersion = 7,
            newActivationAttemptId = attemptA
        )

        assertEquals(SkinPreferenceState.pendingLiquid(7, attemptA), pending.state)
        assertEquals(SkinId.MATERIAL_YOU, pending.state.lastKnownGoodSkin)
        assertTrue(pending.shouldPersist)
        assertEquals(SkinRecoveryReason.LIQUID_VALIDATION_PENDING, pending.reason)

        val confirmed = SkinRecoveryGuard.confirmLiquidHealthy(
            current = pending.state,
            confirmedRendererVersion = 7,
            activationAttemptId = attemptA
        )

        assertEquals(SkinPreferenceState.confirmedLiquid(7, attemptA), confirmed.state)
        assertTrue(confirmed.shouldPersist)
        assertEquals(SkinRecoveryReason.LIQUID_HEALTH_CONFIRMED, confirmed.reason)
    }

    fun `unfinished pending rolls back on next process start`() {
        val recovered = SkinRecoveryGuard.onProcessStart(
            persisted = SkinPreferenceState.pendingLiquid(11, attemptA),
            currentLiquidRendererVersion = 11,
            newActivationAttemptId = processAttempt
        )

        assertEquals(SkinPreferenceState.MATERIAL_DEFAULT, recovered.state)
        assertEquals(SkinId.MATERIAL_YOU, recovered.selectedSkin)
        assertTrue(recovered.shouldPersist)
        assertEquals(SkinRecoveryReason.INCOMPLETE_PENDING_ROLLED_BACK, recovered.reason)
    }

    fun `confirmed liquid remains ready for the same renderer version`() {
        val ready = SkinRecoveryGuard.onProcessStart(
            persisted = SkinPreferenceState.confirmedLiquid(3, attemptA),
            currentLiquidRendererVersion = 3,
            newActivationAttemptId = processAttempt
        )

        assertEquals(SkinPreferenceState.confirmedLiquid(3, attemptA), ready.state)
        assertFalse(ready.shouldPersist)
        assertEquals(SkinRecoveryReason.READY, ready.reason)
    }

    fun `renderer version change enters revalidation with material rollback point`() {
        val revalidation = SkinRecoveryGuard.onProcessStart(
            persisted = SkinPreferenceState.confirmedLiquid(3, attemptA),
            currentLiquidRendererVersion = 4,
            newActivationAttemptId = processAttempt
        )

        assertEquals(
            SkinPreferenceState.pendingLiquid(4, processAttempt),
            revalidation.state
        )
        assertEquals(SkinId.MATERIAL_YOU, revalidation.state.lastKnownGoodSkin)
        assertTrue(revalidation.shouldPersist)
        assertEquals(
            SkinRecoveryReason.RENDERER_REVALIDATION_REQUIRED,
            revalidation.reason
        )
    }

    fun `failed renderer revalidation returns to material`() {
        val pending = SkinRecoveryGuard.onProcessStart(
            persisted = SkinPreferenceState.confirmedLiquid(3, attemptA),
            currentLiquidRendererVersion = 4,
            newActivationAttemptId = processAttempt
        ).state

        val failed = SkinRecoveryGuard.onLiquidValidationFailed(
            current = pending,
            failedRendererVersion = 4,
            activationAttemptId = processAttempt
        )

        assertEquals(SkinPreferenceState.MATERIAL_DEFAULT, failed.state)
        assertTrue(failed.shouldPersist)
        assertEquals(SkinRecoveryReason.LIQUID_VALIDATION_FAILED, failed.reason)
    }

    fun `successful renderer revalidation promotes only the new version`() {
        val pending = SkinRecoveryGuard.onProcessStart(
            persisted = SkinPreferenceState.confirmedLiquid(3, attemptA),
            currentLiquidRendererVersion = 4,
            newActivationAttemptId = processAttempt
        ).state

        val confirmed = SkinRecoveryGuard.confirmLiquidHealthy(
            pending,
            4,
            processAttempt
        )

        assertEquals(
            SkinPreferenceState.confirmedLiquid(4, processAttempt),
            confirmed.state
        )
        assertTrue(confirmed.shouldPersist)
    }

    fun `switching to material is immediate and clears pending state`() {
        listOf(
            SkinPreferenceState.pendingLiquid(5, attemptA),
            SkinPreferenceState.confirmedLiquid(5, attemptA)
        ).forEach { current ->
            val material = SkinRecoveryGuard.beginSelection(
                current = current,
                target = SkinId.MATERIAL_YOU,
                currentLiquidRendererVersion = 5,
                newActivationAttemptId = attemptB
            )

            assertEquals(SkinPreferenceState.MATERIAL_DEFAULT, material.state)
            assertTrue(material.shouldPersist)
            assertEquals(SkinRecoveryReason.MATERIAL_SELECTED, material.reason)
        }
    }

    fun `late liquid health confirmation cannot override material selection`() {
        val material = SkinRecoveryGuard.beginSelection(
            current = SkinPreferenceState.pendingLiquid(8, attemptA),
            target = SkinId.MATERIAL_YOU,
            currentLiquidRendererVersion = 8,
            newActivationAttemptId = attemptB
        ).state

        val late = SkinRecoveryGuard.confirmLiquidHealthy(material, 8, attemptA)

        assertEquals(SkinPreferenceState.MATERIAL_DEFAULT, late.state)
        assertFalse(late.shouldPersist)
        assertEquals(
            SkinRecoveryReason.STALE_HEALTH_CONFIRMATION_IGNORED,
            late.reason
        )
    }

    fun `health confirmation from an older renderer cannot promote newer pending`() {
        val pending = SkinPreferenceState.pendingLiquid(9, attemptB)

        val stale = SkinRecoveryGuard.confirmLiquidHealthy(pending, 8, attemptA)

        assertEquals(pending, stale.state)
        assertFalse(stale.shouldPersist)
        assertEquals(
            SkinRecoveryReason.STALE_HEALTH_CONFIRMATION_IGNORED,
            stale.reason
        )
    }

    fun `selecting already confirmed liquid with same renderer is a no-op`() {
        val current = SkinPreferenceState.confirmedLiquid(12, attemptA)

        val decision = SkinRecoveryGuard.beginSelection(
            current = current,
            target = SkinId.LIQUID,
            currentLiquidRendererVersion = 12,
            newActivationAttemptId = attemptB
        )

        assertEquals(current, decision.state)
        assertFalse(decision.shouldPersist)
        assertEquals(SkinRecoveryReason.READY, decision.reason)
    }

    fun `old success from same renderer version cannot confirm a retry`() {
        val retry = SkinPreferenceState.pendingLiquid(7, attemptB)

        val stale = SkinRecoveryGuard.confirmLiquidHealthy(retry, 7, attemptA)

        assertEquals(retry, stale.state)
        assertFalse(stale.shouldPersist)
        assertEquals(
            SkinRecoveryReason.STALE_HEALTH_CONFIRMATION_IGNORED,
            stale.reason
        )
    }

    fun `old failure cannot roll back a newly confirmed attempt`() {
        val confirmed = SkinPreferenceState.confirmedLiquid(7, attemptB)

        val stale = SkinRecoveryGuard.onLiquidValidationFailed(
            current = confirmed,
            failedRendererVersion = 7,
            activationAttemptId = attemptA
        )

        assertEquals(confirmed, stale.state)
        assertFalse(stale.shouldPersist)
        assertEquals(
            SkinRecoveryReason.STALE_VALIDATION_FAILURE_IGNORED,
            stale.reason
        )
    }

    fun `failure from the active confirmed attempt rolls back to material`() {
        val confirmed = SkinPreferenceState.confirmedLiquid(7, attemptA)

        val failed = SkinRecoveryGuard.onLiquidValidationFailed(
            current = confirmed,
            failedRendererVersion = 7,
            activationAttemptId = attemptA
        )

        assertEquals(SkinPreferenceState.MATERIAL_DEFAULT, failed.state)
        assertTrue(failed.shouldPersist)
        assertEquals(SkinRecoveryReason.LIQUID_VALIDATION_FAILED, failed.reason)
    }

    fun `negative runtime renderer versions are rejected as programmer errors`() {
        assertThrows(IllegalArgumentException::class.java) {
            SkinRecoveryGuard.onProcessStart(
                SkinPreferenceState.MATERIAL_DEFAULT,
                -1,
                processAttempt
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SkinRecoveryGuard.beginSelection(
                SkinPreferenceState.MATERIAL_DEFAULT,
                SkinId.LIQUID,
                -1,
                attemptA
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SkinRecoveryGuard.confirmLiquidHealthy(
                SkinPreferenceState.pendingLiquid(0, attemptA),
                -1,
                attemptA
            )
        }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (SkinRecoveryGuardCases) -> Unit) {
            val s = SkinRecoveryGuardCases()
            block(s)
        }
        runIsolated { s -> s.`liquid selection stays pending until matching health confirmation`() }
        runIsolated { s -> s.`unfinished pending rolls back on next process start`() }
        runIsolated { s -> s.`confirmed liquid remains ready for the same renderer version`() }
        runIsolated { s -> s.`renderer version change enters revalidation with material rollback point`() }
        runIsolated { s -> s.`failed renderer revalidation returns to material`() }
        runIsolated { s -> s.`successful renderer revalidation promotes only the new version`() }
        runIsolated { s -> s.`switching to material is immediate and clears pending state`() }
        runIsolated { s -> s.`late liquid health confirmation cannot override material selection`() }
        runIsolated { s -> s.`health confirmation from an older renderer cannot promote newer pending`() }
        runIsolated { s -> s.`selecting already confirmed liquid with same renderer is a no-op`() }
        runIsolated { s -> s.`old success from same renderer version cannot confirm a retry`() }
        runIsolated { s -> s.`old failure cannot roll back a newly confirmed attempt`() }
        runIsolated { s -> s.`failure from the active confirmed attempt rolls back to material`() }
        runIsolated { s -> s.`negative runtime renderer versions are rejected as programmer errors`() }
    }
}

private class SkinRenderSessionRegistryCases {

    private val pending = SkinPreferenceState.pendingLiquid(
        rendererVersion = 7,
        activationAttemptId = "activation-a"
    )

    fun `main and secondary activity owners remain active together`() {
        val registry = SkinRenderSessionRegistry()
        val oldActivity = requireNotNull(registry.claim(pending, "owner-old"))
        val newActivity = requireNotNull(registry.claim(pending, "owner-new"))

        assertTrue(registry.isActiveFor(oldActivity, pending))
        assertTrue(registry.isActiveFor(newActivity, pending))
    }

    fun `one activity close does not release another activity owner`() {
        val registry = SkinRenderSessionRegistry()
        val oldActivity = requireNotNull(registry.claim(pending, "owner-old"))
        val newActivity = requireNotNull(registry.claim(pending, "owner-new"))

        assertTrue(registry.release(oldActivity))
        assertFalse(registry.isActiveFor(oldActivity, pending))
        assertTrue(registry.isActiveFor(newActivity, pending))
    }

    fun `closed current owner makes its late failure stale`() {
        val registry = SkinRenderSessionRegistry()
        val owner = requireNotNull(registry.claim(pending, "owner-current"))

        assertTrue(registry.release(owner))
        assertFalse(registry.isActiveFor(owner, pending))
    }

    fun `skin selection invalidates every concurrent activity owner`() {
        val registry = SkinRenderSessionRegistry()
        val main = requireNotNull(registry.claim(pending, "owner-main"))
        val secondary = requireNotNull(registry.claim(pending, "owner-secondary"))

        registry.invalidate()

        assertFalse(registry.isActiveFor(main, pending))
        assertFalse(registry.isActiveFor(secondary, pending))
    }

    fun `material state never grants liquid renderer ownership`() {
        val registry = SkinRenderSessionRegistry()

        assertNull(
            registry.claim(
                SkinPreferenceState.MATERIAL_DEFAULT,
                "owner-material"
            )
        )
    }

    fun verifyAllCases() {
        fun runIsolated(block: (SkinRenderSessionRegistryCases) -> Unit) {
            val s = SkinRenderSessionRegistryCases()
            block(s)
        }
        runIsolated { s -> s.`main and secondary activity owners remain active together`() }
        runIsolated { s -> s.`one activity close does not release another activity owner`() }
        runIsolated { s -> s.`closed current owner makes its late failure stale`() }
        runIsolated { s -> s.`skin selection invalidates every concurrent activity owner`() }
        runIsolated { s -> s.`material state never grants liquid renderer ownership`() }
    }
}
