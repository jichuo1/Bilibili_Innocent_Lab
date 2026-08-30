package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model

/**
 * 业务界面只依赖的语义颜色令牌。
 *
 * 这里不携带 [android.content.Context] 或 View，确保每个 Activity 可以持有一份不可变快照，
 * 同时避免皮肤状态反向持有界面生命周期对象。
 */
internal data class UiColorTokens(
    val background: Int,
    val surface: Int,
    val surfaceVariant: Int,
    val primary: Int,
    val secondary: Int,
    val tertiary: Int,
    val onAccent: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val textDisabled: Int,
    val outline: Int,
    val divider: Int,
    val ripple: Int,
    val warning: Int,
    val error: Int,
    val scrim: Int,
    val systemBarBackground: Int,
    val useLightSystemBarIcons: Boolean
)

/** 以 dp 为单位的语义形状令牌；实际像素值由具体 renderer 在使用时换算。 */
internal data class UiShapeTokens(
    val cardRadiusDp: Float,
    val modalRadiusDp: Float,
    val controlRadiusDp: Float,
    val chipRadiusDp: Float,
    val filledButtonRadiusDp: Float,
    val textButtonRippleRadiusDp: Float,
    val collapsedMotionRadiusDp: Float
)

/** 一次 Activity 皮肤会话使用的完整、不可变 UI 令牌。 */
internal data class UiTokens(
    val colors: UiColorTokens,
    val shapes: UiShapeTokens
)
