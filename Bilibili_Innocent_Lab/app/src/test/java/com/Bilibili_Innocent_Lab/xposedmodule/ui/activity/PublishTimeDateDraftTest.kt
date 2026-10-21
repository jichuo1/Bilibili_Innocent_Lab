package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PublishTimePoint
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class PublishTimeDateDraftTest {
    private fun draft(year: String, month: String, day: String) = PublishTimeDateDraft().apply {
        yearText = year; monthText = month; dayText = day
    }

    @Test fun `an unset date stays unset instead of defaulting to today`() {
        val value = PublishTimeDateDraft()
        assertNull(value.toDate())
        assertEquals(listOf("", "", ""), listOf(value.yearText, value.monthText, value.dayText))
    }
    @Test fun `saved dates initialize all three fields and round trip`() {
        val date = LocalDate.of(2026, 10, 21)
        val value = PublishTimeDateDraft(date)
        assertEquals(listOf("2026", "10", "21"), listOf(value.yearText, value.monthText, value.dayText))
        assertEquals(date, value.toDate())
    }
    @Test fun `incomplete and invalid input is preserved through validation and later editing`() {
        val value = draft("202", "02", "30")
        assertNull(value.toDate())
        value.yearText = "2024"
        assertNull(value.toDate())
        assertEquals(listOf("2024", "02", "30"), listOf(value.yearText, value.monthText, value.dayText))
        value.dayText = "29"
        assertEquals(LocalDate.of(2024, 2, 29), value.toDate())
        assertEquals("02", value.monthText)
    }
    @Test fun `leap days and month lengths are validated without clamping`() {
        assertEquals(LocalDate.of(2024, 2, 29), draft("2024", "2", "29").toDate())
        for ((year, month, day) in listOf(Triple("2023", "2", "29"), Triple("2100", "2", "29"),
                Triple("2026", "4", "31"), Triple("2026", "0", "1"), Triple("2026", "13", "1"),
                Triple("2026", "1", "0"), Triple("2026", "1", "32"))) {
            val value = draft(year, month, day)
            assertNull(value.toDate())
            assertEquals(day, value.dayText)
        }
    }
    @Test fun `date limits match the persisted publish time protocol`() {
        val first = LocalDate.of(PublishTimePoint.MIN_YEAR, 1, 1)
        val last = LocalDate.of(PublishTimePoint.MAX_YEAR, 12, 31)
        assertEquals(first, PublishTimeDateDraft(first).toDate())
        assertEquals(last, PublishTimeDateDraft(last).toDate())
        assertNull(draft((PublishTimePoint.MIN_YEAR - 1).toString(), "12", "31").toDate())
        assertNull(draft((PublishTimePoint.MAX_YEAR + 1).toString(), "1", "1").toDate())
    }
    @Test fun `non integer fields cannot silently become a date`() {
        for (value in listOf(draft("+2026", "1", "1"), draft("2026 ", "1", "1"),
                draft("2026", "1.0", "1"), draft("2026", "1", "-1"), draft("2026", "", "1")))
            assertNull(value.toDate())
    }
    @Test fun `explicit date replacement updates all fields together`() {
        val value = draft("202", "", "31")
        value.setDate(LocalDate.of(2026, 10, 21))
        assertEquals(LocalDate.of(2026, 10, 21), value.toDate())
        assertEquals(listOf("2026", "10", "21"), listOf(value.yearText, value.monthText, value.dayText))
    }
}
