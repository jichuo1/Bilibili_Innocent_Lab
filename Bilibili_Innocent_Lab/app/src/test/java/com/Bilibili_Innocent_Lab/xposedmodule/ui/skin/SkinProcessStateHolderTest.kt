package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.SkinId
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.runtime.SkinPreferenceState
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.runtime.SkinProcessStateHolder
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.runtime.SkinRecoveryGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkinProcessStateHolderTest {

    private val attemptId = "attempt-holder-primary"

    @Test
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

    @Test
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

    @Test
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

    @Test
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
}
