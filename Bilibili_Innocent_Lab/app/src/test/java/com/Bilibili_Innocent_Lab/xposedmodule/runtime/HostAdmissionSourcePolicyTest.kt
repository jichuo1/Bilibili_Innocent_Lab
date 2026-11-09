package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import org.junit.Assert.*
import org.junit.Test

class HostAdmissionSourcePolicyTest {
    @Test fun lspatchUsesCurrentStandardDocumentRegardlessOfOldNpatchSelectionOrMirror() {
        for (selected in listOf(false, true)) for (revision in listOf(0L, 7L)) {
            assertEquals(HostAdmissionContract.DIRECT, HostAdmissionSourcePolicy.select(
                selected, revision, "current", "old", true, false, lspatchChannel = true))
            assertNull(HostAdmissionSourcePolicy.select(
                selected, revision, "current", "current", false, false, lspatchChannel = true))
        }
    }

    @Test fun npatchPrefersCurrentDirectDocumentWithoutCompatibilityModeOrRemoteMirror() {
        for (revision in listOf(0L, 7L)) {
            for (fingerprint in listOf(null, "old", "current")) {
                assertEquals("module_direct", HostAdmissionSourcePolicy.select(
                    true, revision, "current", fingerprint, true, false, nPatchChannel = true))
            }
        }
    }

    @Test fun oldNPatchDocumentCannotBeUsedAfterTurningOffItsSelectedSource() {
        assertEquals("manager",HostAdmissionSourcePolicy.select(true,7,"x","x",false,false))
        assertNull(HostAdmissionSourcePolicy.select(false,7,"x","x",true,false))
        assertNull(HostAdmissionSourcePolicy.select(true,0,"x","x",true,false))
    }

    @Test fun modeOffRejectsDirectButKeepsCurrentNormalDocument() {
        for (mode in listOf(false,true)) assertEquals("manager",HostAdmissionSourcePolicy.select(false,0,"x","x",true,mode))
        assertEquals("module_direct",HostAdmissionSourcePolicy.select(false,0,"new","old",true,true))
        assertNull(HostAdmissionSourcePolicy.select(false,0,"new","old",true,false))
        assertNull(HostAdmissionSourcePolicy.select(false,0,"new","old",false,true))
    }

    @Test fun noRootSwitchIsTheMasterSwitchForTheNpatchChannel() {
        // B1 回归防线：关闭态 + NPatch 通道，NORMAL 与 DIRECT 都不再发放授权。
        for (revision in listOf(0L, 7L)) {
            for (direct in listOf(false, true)) {
                assertNull(
                    "revision=$revision direct=$direct",
                    HostAdmissionSourcePolicy.select(
                        noRootSelected = false,
                        normalNoRootRevision = revision,
                        expectedFingerprint = "x",
                        normalFingerprint = "x",
                        allowDirect = true,
                        directEnabled = direct,
                        nPatchChannel = true
                    )
                )
            }
        }
        // 开启态不受影响。
        assertEquals(
            "manager",
            HostAdmissionSourcePolicy.select(
                noRootSelected = true,
                normalNoRootRevision = 7,
                expectedFingerprint = "x",
                normalFingerprint = "x",
                allowDirect = false,
                directEnabled = false,
                nPatchChannel = true
            )
        )
    }

    @Test fun unknownChannelFallsBackToTheDocumentKind() {
        // 框架元数据未就绪时（nPatchChannel=false）退回文档判据，LSPosed 不能被误伤。
        assertEquals(
            "manager",
            HostAdmissionSourcePolicy.select(
                noRootSelected = false,
                normalNoRootRevision = 0,
                expectedFingerprint = "x",
                normalFingerprint = "x",
                allowDirect = false,
                directEnabled = false,
                nPatchChannel = false
            )
        )
        // 免 Root 文档在关闭态下仍然被拒（不依赖框架元数据）。
        assertNull(
            HostAdmissionSourcePolicy.select(
                noRootSelected = false,
                normalNoRootRevision = 7,
                expectedFingerprint = "x",
                normalFingerprint = "x",
                allowDirect = true,
                directEnabled = true,
                nPatchChannel = false
            )
        )
    }
}
