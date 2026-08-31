package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

/** 首页诊断入口与全屏形变终点共用的视觉参数，避免两层交接时出现边界跳变。 */
internal object DiagnosticsEntryVisualSpec {
    const val CORNER_RADIUS_DP = 12f
    const val STROKE_WIDTH_DP = 2f
    const val SCRIM_ALPHA = 0x38
    const val STROKE_ALPHA = 0xA0
    const val SURFACE_HANDOFF_EXPANSION = 0.025f
}
