package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `select` 有七个互相影响的输入。这里枚举全部 128 种组合，逐条核对不变量；
 * 标准（LSPosed）通道另与改动前的实现逐组合对照，保证免 Root 的新分支不会改变它。
 */
class HostAdmissionSourcePolicyTruthTableTest {
    private data class Input(
        val noRootSelected: Boolean, val noRootDocument: Boolean, val fingerprintMatches: Boolean,
        val allowDirect: Boolean, val directEnabled: Boolean, val nPatch: Boolean, val lspatch: Boolean
    )

    private val all: List<Input> = buildList {
        val flags = listOf(false, true)
        for (selected in flags) for (document in flags) for (fingerprint in flags) for (allow in flags)
            for (enabled in flags) for (nPatch in flags) for (lspatch in flags)
                add(Input(selected, document, fingerprint, allow, enabled, nPatch, lspatch))
    }

    private fun Input.select() = HostAdmissionSourcePolicy.select(
        noRootSelected = noRootSelected,
        normalNoRootRevision = if (noRootDocument) 7L else 0L,
        expectedFingerprint = "current",
        normalFingerprint = if (fingerprintMatches) "current" else "old",
        allowDirect = allowDirect,
        directEnabled = directEnabled,
        nPatchChannel = nPatch,
        lspatchChannel = lspatch
    )

    /** 改动前（main）的实现，原样保留作为标准通道的对照基准。 */
    private fun Input.legacy(): String? = when {
        noRootSelected == noRootDocument && fingerprintMatches -> HostAdmissionContract.NORMAL
        allowDirect && directEnabled -> HostAdmissionContract.DIRECT
        else -> null
    }

    @Test fun theTableCoversEveryCombination() {
        assertEquals(128, all.size)
        assertEquals(128, all.toSet().size)
    }

    @Test fun lspatchOnlyEverGrantsTheDirectSourceAndOnlyWhenAsked() {
        for (input in all.filter { it.lspatch }) {
            assertEquals(input.toString(),
                if (input.allowDirect) HostAdmissionContract.DIRECT else null, input.select())
        }
    }

    @Test fun theNoRootSwitchIsTotalForNPatchAndForAnyNoRootDocument() {
        for (input in all.filter { !it.lspatch && !it.noRootSelected && (it.nPatch || it.noRootDocument) }) {
            assertNull(input.toString(), input.select())
        }
    }

    @Test fun nPatchPrefersTheCurrentDirectDocumentWhenSwitchedOn() {
        for (input in all.filter { !it.lspatch && it.nPatch && it.noRootSelected && it.allowDirect }) {
            assertEquals(input.toString(), HostAdmissionContract.DIRECT, input.select())
        }
    }

    @Test fun theStandardChannelBehavesExactlyAsBeforeExceptForTheClosedNoRootDocument() {
        for (input in all.filter { !it.lspatch && !it.nPatch }) {
            val closedNoRootDocument = !input.noRootSelected && input.noRootDocument
            if (closedNoRootDocument) assertNull(input.toString(), input.select())
            else assertEquals(input.toString(), input.legacy(), input.select())
        }
    }

    @Test fun grantsAreAlwaysJustifiedByTheInputs() {
        for (input in all) {
            val result = input.select()
            assertTrue(input.toString(), result in setOf(null, HostAdmissionContract.NORMAL, HostAdmissionContract.DIRECT))
            // A normal grant needs a document that matches the current publication.
            if (result == HostAdmissionContract.NORMAL) assertTrue(input.toString(), input.fingerprintMatches)
            // A direct grant is only ever issued to a host that asked for it.
            if (result == HostAdmissionContract.DIRECT) assertTrue(input.toString(), input.allowDirect)
        }
    }
}
