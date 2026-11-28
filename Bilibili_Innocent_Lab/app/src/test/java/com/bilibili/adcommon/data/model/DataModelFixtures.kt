package com.bilibili.adcommon.data.model

// From PopupAdapterModelShapes.kt
/** 模拟模型 owner/getter 改名；候选来自有界假引擎，普通宿主模型链仍单独验真。 */
class RenamedPopupAdvert {
    fun floats(): List<Dm> = emptyList()
    fun getAds(): List<Dm> = emptyList()
    fun getValidPanelData(): List<Dm> = emptyList()
    fun wrong(): String = "unrelated"
}
class OtherPopupAdvert { fun floats(): List<Dm> = emptyList() }

// From PopupPromotionFixtures.kt
class Card(private val type: Int, private val broken: Boolean = false) {
    var reads = 0
        private set
    fun getCardType(): Int {
        reads++
        check(!broken) { "fixture card-type failure" }
        return type
    }
}
class Dm(private val card: Card?, private val broken: Boolean = false) {
    fun getCard(): Card? { check(!broken) { "fixture read failure" }; return card }
}
class DmAdvert(private val ads: List<Dm>? = null) {
    fun getAds(): List<Dm>? = ads
    fun getFloatLayers(): List<Dm>? = ads
    fun getValidPanelData(): List<Dm>? = ads
    fun getDms(): List<Dm>? = ads
}

/** 模拟广告 owner/getter 被改名后，后台 DexKit 缓存的精确入口。 */
class RenamedPopupAdvertFixture(private val ads: List<Dm>? = null) {
    fun f(): List<Dm>? = ads
}
