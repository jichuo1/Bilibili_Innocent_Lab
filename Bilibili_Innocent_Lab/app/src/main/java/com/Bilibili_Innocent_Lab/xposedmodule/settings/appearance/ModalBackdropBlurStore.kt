package com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance

import android.content.Context
import com.Bilibili_Innocent_Lab.xposedmodule.settings.modulePreferences

/**
 * 「面板窗口模糊」的用户意图，与其他模块界面设置共用模块权威偏好（可备份）。
 *
 * **未设置时默认关闭。** 真机实测（小米 2210132C，8 轮气泡开合，`dumpsys gfxinfo`）：
 * 开启后面板动画的掉帧率由 **3.62% 升到 10.41%**，UI 线程 90 分位由 9ms 升到 16ms，
 * 而 GPU 分位几乎不变——代价出在 UI 线程写 `WindowManager.LayoutParams`
 * 引发的 `relayoutWindow`（每次开合最多 7 次，见 `ModalBackdropBlurSpec.RADIUS_STEP_PX`），
 * 而不是模糊本身（模糊在 SurfaceFlinger 侧）。所以它归在实验性功能里、默认不开。
 */
internal object ModalBackdropBlurStore {
    const val PREF_KEY = "panel_window_blur_enabled"

    const val DEFAULT = false

    fun read(context: Context): Boolean = runCatching {
        context.modulePreferences().getBoolean(PREF_KEY, DEFAULT)
    }.getOrDefault(DEFAULT)
}
