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
        val row = SettingsUiSource.function("createTelemetryMenuRow")
        assertTrue(row.contains("showControl: Boolean = false"))
        val entry = row.substringAfter("if (!showControl) {")
            .substringBefore("var programmaticChange")
        assertTrue(entry.contains("return NativeLinearLayout(this)"))
        assertFalse(entry.contains("setOnClickListener"))
        assertTrue(entry.contains("isClickable = false"))
        assertFalse(entry.contains("writeConsentChoice"))
        assertFalse(entry.contains("telemetrySwitch"))
    }

    @Test
    fun `new bubble shifts without moving GitHub and retains an outside hit target`() {
        val badge = source.substringAfter("// 只占原图标的空间")
            .substringBefore("activationCardView = this")
        assertTrue(badge.contains("LayoutParams(27.dp, 27.dp) { marginEnd = 5.dp }"))
        assertTrue(badge.contains("LayoutParams(22.dp, 15.dp)"))
        assertTrue(badge.contains("marginEnd = -5.dp"))
        assertTrue(badge.contains("topMargin = -5.dp"))
        assertTrue(badge.contains("GithubUpdateBadgeDrawable"))
        assertTrue(badge.contains("toolbar.touchDelegate"))
        assertTrue(badge.contains("badge.visibility == View.VISIBLE"))
    }

    @Test
    fun `detail owns the switch and retains disclosure and save failure checks`() {
        val detail = SettingsUiSource.function("showTelemetryInfoDialog")
        assertTrue(detail.contains("createTelemetryMenuRow(dialog, container, showControl = true)"))
        val row = SettingsUiSource.function("createTelemetryMenuRow")
        assertTrue(row.contains("enabled && !TelemetryStore.hasCurrentDisclosure(applicationContext)"))
        assertTrue(row.contains("if (!saved)"))
        assertTrue(row.contains("telemetry_choice_save_failed"))
        assertTrue(row.contains("animateTelemetrySummary(summary, getString("))
        assertTrue(row.contains("android.text.StaticLayout.Builder.obtain"))
    }
}
