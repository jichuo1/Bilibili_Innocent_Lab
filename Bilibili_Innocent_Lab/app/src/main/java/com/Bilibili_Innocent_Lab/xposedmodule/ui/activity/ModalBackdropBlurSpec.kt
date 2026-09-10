package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import kotlin.math.roundToInt

/**
 * 弹窗背后那层浅毛玻璃的纯数学层（无 Android 依赖，可 JVM 直测）。
 *
 * 只服务 Material You 美学：Liquid 皮肤本身就在做实时液态玻璃采样，再叠一层跨窗口模糊
 * 既是双重代价，两种质感也会打架。
 */
internal object ModalBackdropBlurSpec {

    /**
     * 浅浅一层就够：目的是让面板与背景分层，不是把背景糊掉。
     *
     * 12dp（真机 42px）实测偏重，背景细节几乎认不出来了；7dp（约 24px）刚好能看出层次，
     * 背景仍然可辨。跨窗口模糊的开销随半径增长（SurfaceFlinger 侧的采样半径），
     * 调小也顺带省了 GPU。
     */
    const val MAX_RADIUS_DP = 7f

    /**
     * 模糊在 65% 进度处就到位，早于面板落位。
     *
     * 背景分层是"面板正在浮起来"的铺垫，等到形状停住才糊完就晚了；反向推进时同理，
     * 面板还没缩回原位背景就已经清晰，收尾才干净。
     */
    private const val RAMP_END = 0.65f

    /**
     * 半径的量化步长（像素）。
     *
     * 改模糊半径要写 `WindowManager.LayoutParams` —— 那是一次 binder 往返 + 一次
     * `relayoutWindow`，**不能每帧都来**。模糊半径又恰好是最不需要逐帧精度的量：
     * 按 4px 台阶推进，400ms 入场里只会推十来次，观感上依然是连续变糊。
     */
    const val RADIUS_STEP_PX = 4

    fun fraction(progress: Float): Float {
        val p = if (progress.isNaN()) 0f else progress.coerceIn(0f, 1f)
        if (RAMP_END <= 0f) return 1f
        val t = (p / RAMP_END).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * 本帧应该写进窗口的模糊半径，**已按 [RADIUS_STEP_PX] 量化**。
     *
     * 两端精确：0 进度必须是 0（否则关闭后背景永远留着一层糊），满进度必须是完整半径。
     */
    fun radiusPx(progress: Float, maxRadiusPx: Int): Int {
        if (maxRadiusPx <= 0) return 0
        val fraction = fraction(progress)
        if (fraction <= 0f) return 0
        if (fraction >= 1f) return maxRadiusPx
        val raw = maxRadiusPx * fraction
        val stepped = (raw / RADIUS_STEP_PX).roundToInt() * RADIUS_STEP_PX
        return stepped.coerceIn(0, maxRadiusPx)
    }
}
