package com.Bilibili_Innocent_Lab.xposedmodule.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class DiagnosticReportCodecTest {
    @Test
    fun `encoded report validates and declares its privacy exclusions`() {
        val snapshot = ModuleHealthEvaluator.evaluate(inputs())
        val bytes = DiagnosticReportCodec.encode(snapshot)
        val metadata = DiagnosticReportCodec.validate(bytes)
        val text = bytes.toString(StandardCharsets.UTF_8)

        assertTrue(bytes.size < DiagnosticReportCodec.MAX_FILE_BYTES)
        assertEquals(snapshot.inputs.collectedAtEpochMs, metadata.collectedAtEpochMs)
        assertEquals(snapshot.overallSeverity, metadata.overallSeverity)
        assertEquals(DiagnosticItemId.entries.size, metadata.itemCount)
        DiagnosticReportCodec.excludedCategories.forEach { category ->
            assertTrue(text.contains(category))
        }
    }

    @Test
    fun `report schema never contains user values paths logs or exception details`() {
        val text = DiagnosticReportCodec.encode(
            ModuleHealthEvaluator.evaluate(
                inputs(
                    remoteFailureCode = "C:\\private\\remote_failure.txt",
                    skinFallbackCode = "java.lang.IllegalStateException: secret"
                )
            )
        ).toString(StandardCharsets.UTF_8)

        listOf(
            "preferenceValue",
            "storageKey",
            "customRuleValue",
            "stackTrace",
            "exceptionMessage",
            "cachePath",
            "memberName",
            "logText"
        ).forEach { forbiddenKey ->
            assertFalse("forbidden key: $forbiddenKey", text.contains(forbiddenKey))
        }
        assertFalse(text.contains("private\\remote_failure"))
        assertFalse(text.contains("IllegalStateException"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `oversized report is rejected before parsing`() {
        DiagnosticReportCodec.validate(ByteArray(DiagnosticReportCodec.MAX_FILE_BYTES + 1))
    }
}
