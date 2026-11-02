package com.bilibili.app.gemini.base.player

import com.bilibili.lib.media.resolver2.IResolveParams

class GeminiCommonResolverParams(private val aid: Long, private val cid: Long) : IResolveParams {
    fun getAvid() = aid
    fun getCid() = cid
}
