package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source layout contracts; device interaction and rendering remain separate acceptance steps. */
class TelemetryMenuStructureTest {
    private val source by lazy {
        val path = "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/activity/MainActivity.kt"
        sequenceOf(File(path), File("app/$path"))
            .first(File::isFile).readText()
    }

    @Test
    fun `github telemetry entry navigates before any switch is constructed`() {
        val row = source.substringAfter("private fun createTelemetryMenuRow(")
            .substringBefore("private fun showTelemetryInfoDialog()")
        assertTrue(row.contains("showControl: Boolean = false"))
        val entry = row.substringAfter("if (!showControl) {")
            .substringBefore("var programmaticChange")
        assertTrue(entry.contains("return NativeLinearLayout(this)"))
        assertTrue(entry.contains("infoButton.performClick()"))
        assertFalse(entry.contains("writeConsentChoice"))
        assertFalse(entry.contains("telemetrySwitch"))
    }

    @Test
    fun `detail owns the switch and retains disclosure and save failure checks`() {
        val detail = source.substringAfter("private fun showTelemetryInfoDialog()")
            .substringBefore("private fun showTelemetryExplanationDialog()")
        assertTrue(detail.contains("createTelemetryMenuRow(dialog, container, showControl = true)"))
        val row = source.substringAfter("private fun createTelemetryMenuRow(")
            .substringBefore("private fun showTelemetryInfoDialog()")
        assertTrue(row.contains("enabled && !TelemetryStore.hasCurrentDisclosure(applicationContext)"))
        assertTrue(row.contains("if (!saved)"))
        assertTrue(row.contains("telemetry_choice_save_failed"))
        assertTrue(row.contains("summary.text = getString("))
    }
}
