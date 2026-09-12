package com.bilibili.adcommon.data.model

class Card(private val type: Int) { fun getCardType(): Int = type }
class Dm(private val card: Card?, private val broken: Boolean = false) {
    fun getCard(): Card? { check(!broken) { "fixture read failure" }; return card }
}
class DmAdvert(private val ads: List<Dm>? = null) {
    fun getAds(): List<Dm>? = ads
    fun getFloatLayers(): List<Dm>? = ads
    fun getValidPanelData(): List<Dm>? = ads
    fun getDms(): List<Dm>? = ads
}
