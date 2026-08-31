package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

internal enum class DiagnosticStatusTone {
    OK,
    INFO,
    ATTENTION,
    ACTION_REQUIRED,
    UNKNOWN
}

/** 诊断状态使用固定语义色，不跟随可能偏灰的 Monet 主色，确保状态含义可辨。 */
internal object DiagnosticStatusPalette {
    fun color(tone: DiagnosticStatusTone, darkTheme: Boolean): Int = when (tone) {
        DiagnosticStatusTone.OK -> if (darkTheme) 0xFF4ADE80.toInt() else 0xFF15803D.toInt()
        DiagnosticStatusTone.INFO -> if (darkTheme) 0xFF60A5FA.toInt() else 0xFF1D4ED8.toInt()
        DiagnosticStatusTone.ATTENTION ->
            if (darkTheme) 0xFFFBBF24.toInt() else 0xFFB45309.toInt()
        DiagnosticStatusTone.ACTION_REQUIRED ->
            if (darkTheme) 0xFFF87171.toInt() else 0xFFB91C1C.toInt()
        DiagnosticStatusTone.UNKNOWN ->
            if (darkTheme) 0xFFCBD5E1.toInt() else 0xFF4B5563.toInt()
    }
}
