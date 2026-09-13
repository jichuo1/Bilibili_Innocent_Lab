package com.Bilibili_Innocent_Lab.xposedmodule.runtime
import org.junit.Assert.*
import org.junit.Test
class HostAdmissionSourcePolicyTest {
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
}
