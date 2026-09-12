package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

internal object PlayerPopupPromotionPolicy {
    // HAR 的 x/v2/dm/ad 样本与宿主 Card.isValidCommon() 相互印证：
    // 80 是通用悬浮推广卡。只在 DmAdvert 广告模型内使用，不按文案、URL 或全局 Card 判定。
    const val COMMON_FLOAT_PROMOTION = 80

    fun filter(source: List<*>, cardType: (Any) -> Int?): List<*> =
        CopyOnFilter.list(source) { cardType(it) == COMMON_FLOAT_PROMOTION }
}
