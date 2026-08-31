package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DiagnosticStatusPaletteTest {

    @Test
    fun semanticTonesStayDistinctInLightAndDarkThemes() {
        listOf(false, true).forEach { darkTheme ->
            val colors = DiagnosticStatusTone.entries.map { tone ->
                DiagnosticStatusPalette.color(tone, darkTheme)
            }
            assertEquals(DiagnosticStatusTone.entries.size, colors.distinct().size)
        }
    }

    @Test
    fun normalStateUsesGreenAndRequiredActionUsesRed() {
        assertEquals(0xFF15803D.toInt(), DiagnosticStatusPalette.color(DiagnosticStatusTone.OK, false))
        assertEquals(0xFF4ADE80.toInt(), DiagnosticStatusPalette.color(DiagnosticStatusTone.OK, true))
        assertEquals(
            0xFFB91C1C.toInt(),
            DiagnosticStatusPalette.color(DiagnosticStatusTone.ACTION_REQUIRED, false)
        )
        assertNotEquals(
            DiagnosticStatusPalette.color(DiagnosticStatusTone.OK, false),
            DiagnosticStatusPalette.color(DiagnosticStatusTone.ACTION_REQUIRED, false)
        )
    }
}
