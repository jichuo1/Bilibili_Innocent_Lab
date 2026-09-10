// ReplaceWithAndroidVersion 定点抑制：项目自定义 lint 规则会劝我们把
// `Build.VERSION.SDK_INT` 换成那个第三方版本助手，但它不带 `@ChecksSdkIntAtLeast`，
// 换过去 `NewApi` 会直接报**错误**（四项门禁里 lint 必须 0 Error）。
// 这里两处版本判断是有意保留原生写法的，别"顺手修好"。
@file:Suppress("ReplaceWithAndroidVersion")

package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.content.Context
import android.os.Build
import android.view.Window
import android.view.WindowManager

/**
 * 弹窗背后那层浅毛玻璃的驱动器：跟着弹窗自己的进度渐进，不另开时钟。
 *
 * 用平台的**跨窗口模糊**（API 31+ 的 `FLAG_BLUR_BEHIND` + `blurBehindRadius`）：
 * 模糊由 SurfaceFlinger 完成，模块侧既不自绘也不截屏，逐帧成本只是改一个窗口属性。
 * 这也是为什么不复用 Liquid 那套实时采样——那条路的逐帧像素回读预算是硬约束，
 * 而这里只是要一层"背景退后"的层次感。
 *
 * 四道门缺一不可，任何一道不满足就**整个不启用**，不做退化模拟（糊不动就保持清晰，
 * 比自绘一层假模糊安全）：
 * 1. 用户在实验性功能里打开了「面板窗口模糊」（`ModalBackdropBlurStore`，**默认关闭**——
 *    真机实测开启后面板动画掉帧率 3.62% → 10.41%，见那个 store 的注释）；
 * 2. Material You 美学（Liquid 皮肤自己在做玻璃，叠加会打架且加倍开销）；
 * 3. API 31+；
 * 4. `WindowManager.isCrossWindowBlurEnabled()` —— 省电模式、"降低透明度"、
 *    以及部分 ROM 会整体关掉这个能力，此时写了属性也没有效果。
 *
 * 版本判断一律写成 `Build.VERSION.SDK_INT`，**不用项目里那个 `AndroidVersion` 助手**：
 * lint 的 `NewApi` 只认得前者，而且 [apply] 与工厂不在同一个方法里，
 * 工厂上的守卫管不到它——两处都得各自带一个本地守卫。
 */
internal class ModalBackdropBlur private constructor(
    private val window: Window,
    private val maxRadiusPx: Int
) {
    private var appliedRadius = -1

    /** @param progress 弹窗自己的展开进度；收起时反向推进即可。 */
    fun apply(progress: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val radius = ModalBackdropBlurSpec.radiusPx(progress, maxRadiusPx)
        // 量化之后才比较：改窗口属性是一次 binder 往返加一次 relayout，重复值一律不写。
        if (radius == appliedRadius) return
        appliedRadius = radius
        runCatching {
            val params = window.attributes
            if (radius <= 0) {
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
                params.blurBehindRadius = 0
            } else {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                params.blurBehindRadius = radius
            }
            window.attributes = params
        }
    }

    /** 窗口撤掉时模糊本来就会随之消失；这里显式归零，覆盖"硬关停在半路"的路径。 */
    fun clear() = apply(0f)

    companion object {
        /** @return 四道门都通过才返回驱动器，否则 null（调用方无需再判断）。 */
        fun createOrNull(
            window: Window?,
            userEnabled: Boolean,
            materialYouSkin: Boolean,
            density: Float
        ): ModalBackdropBlur? {
            if (!userEnabled) return null
            if (window == null || !materialYouSkin) return null
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
            if (!density.isFinite() || density <= 0f) return null
            val enabled = runCatching {
                (window.context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                    ?.isCrossWindowBlurEnabled
            }.getOrNull() ?: return null
            if (!enabled) return null
            val maxRadiusPx = (ModalBackdropBlurSpec.MAX_RADIUS_DP * density).toInt()
            if (maxRadiusPx <= 0) return null
            return ModalBackdropBlur(window, maxRadiusPx)
        }
    }
}
