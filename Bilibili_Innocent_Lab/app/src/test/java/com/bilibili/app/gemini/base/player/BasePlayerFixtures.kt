package com.bilibili.app.gemini.base.player

import com.bilibili.lib.media.resolver2.IResolveParams

// From GeminiCommonResolverParams.kt
class GeminiCommonResolverParams(private val aid: Long, private val cid: Long) : IResolveParams {
    fun getAvid() = aid
    fun getCid() = cid
}

// From SponsorParamsFixture.kt
enum class SponsorBusiness { UGC, PGC }
open class SponsorParent(val bizType: SponsorBusiness)
class GeminiCommonPlayableParams(val bvId: String, val cid: Long, val avid: Long,
    business: SponsorBusiness = SponsorBusiness.UGC) : SponsorParent(business)
