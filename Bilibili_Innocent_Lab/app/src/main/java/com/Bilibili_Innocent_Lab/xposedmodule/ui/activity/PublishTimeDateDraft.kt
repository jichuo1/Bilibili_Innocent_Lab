package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PublishTimePoint
import java.time.LocalDate

/** Raw date fields remain a draft, including incomplete or invalid input, until confirmation. */
internal class PublishTimeDateDraft(date: LocalDate? = null) {
    var yearText: String = date?.year?.toString().orEmpty()
    var monthText: String = date?.monthValue?.toString().orEmpty()
    var dayText: String = date?.dayOfMonth?.toString().orEmpty()

    fun toDate(): LocalDate? {
        fun number(raw: String): Int? = raw.takeIf { it.isNotEmpty() && it.all { char -> char in '0'..'9' } }?.toIntOrNull()
        val year = number(yearText)?.takeIf { it in PublishTimePoint.MIN_YEAR..PublishTimePoint.MAX_YEAR } ?: return null
        val month = number(monthText) ?: return null
        val day = number(dayText) ?: return null
        return runCatching { LocalDate.of(year, month, day) }.getOrNull()
    }

    /** Only an explicit user action, such as Today, replaces all three raw fields. */
    fun setDate(date: LocalDate) {
        yearText = date.year.toString()
        monthText = date.monthValue.toString()
        dayText = date.dayOfMonth.toString()
    }
}
