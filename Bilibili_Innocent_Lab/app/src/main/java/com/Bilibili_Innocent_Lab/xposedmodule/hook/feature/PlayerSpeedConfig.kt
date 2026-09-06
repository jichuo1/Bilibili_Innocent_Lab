package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import java.math.BigDecimal

/** 百分比整数沿用项目现有配置类型；0 表示跟随宿主，不引入 Float 偏好或协议类型。 */
internal object PlayerSpeedConfig {
    const val FOLLOW_HOST = 0
    val supportedPercents: Set<Int> = setOf(FOLLOW_HOST) + (25..400)
    private val decimalInput = Regex("(?:[0-9]{1,3}(?:\\.[0-9]{1,2})?|\\.[0-9]{1,2})")

    fun normalize(percent: Int): Int = percent.takeIf { it in supportedPercents } ?: FOLLOW_HOST

    fun multiplier(percent: Int): Float? = normalize(percent).takeIf { it != FOLLOW_HOST }?.div(100f)

    fun effectiveLongPressPercent(disabled: Boolean, percent: Int): Int =
        if (disabled) FOLLOW_HOST else normalize(percent)

    fun parseMultiplier(text: String): Int? = runCatching {
        val value = text.trim()
        if (!decimalInput.matches(value)) return@runCatching null
        BigDecimal(value).movePointRight(2).intValueExact().takeIf { it in supportedPercents }
    }.getOrNull()

    fun formatMultiplier(percent: Int): String =
        BigDecimal.valueOf(normalize(percent).toLong(), 2).stripTrailingZeros().toPlainString()
}
