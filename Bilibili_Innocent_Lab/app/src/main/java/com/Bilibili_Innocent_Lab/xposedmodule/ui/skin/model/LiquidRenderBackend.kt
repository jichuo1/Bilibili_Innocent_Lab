package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model

/** Liquid 皮肤当前实际使用的渲染后端，顺序同时表示逐级降级优先级。 */
internal enum class LiquidRenderBackend {
    REFRACTION,
    BLUR,
    TRANSLUCENT
}
