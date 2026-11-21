package com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance

import org.junit.Assert.assertEquals
import org.junit.Test

class MaterialColorSpecTest {

    fun `storage values decode only supported specifications`() {
        assertEquals(
            MaterialColorSpec.SPEC_2021,
            MaterialColorSpec.fromStorageValue("2021")
        )
        assertEquals(
            MaterialColorSpec.SPEC_2025,
            MaterialColorSpec.fromStorageValue("2025")
        )
    }

    fun `missing or unsupported values fall back to spec 2021`() {
        assertEquals(MaterialColorSpec.SPEC_2021, MaterialColorSpec.DEFAULT)
        assertEquals(MaterialColorSpec.SPEC_2021, MaterialColorSpec.fromStorageValue(null))
        assertEquals(MaterialColorSpec.SPEC_2021, MaterialColorSpec.fromStorageValue("2026"))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (MaterialColorSpecTest) -> Unit) {
            val s = MaterialColorSpecTest()
            block(s)
        }
        runIsolated { s -> s.`storage values decode only supported specifications`() }
        runIsolated { s -> s.`missing or unsupported values fall back to spec 2021`() }
    }
}
