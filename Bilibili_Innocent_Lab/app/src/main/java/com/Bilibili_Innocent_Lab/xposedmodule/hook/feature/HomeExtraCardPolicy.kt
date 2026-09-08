package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import java.net.URI

/** 首页专用，不改变相关推荐等页面的共享分类语义；不读取标题或请求网络。 */
internal object HomeExtraCardPolicy {
    enum class Kind { PGC, SPECIAL, UNKNOWN }
    private val pgcTypes = setOf("BANGUMI", "BANGUMI_AV", "BANGUMI_P", "BANGUMI_RCMD", "PGC")
    private val specialTypes = setOf("SPECIAL", "SPECIAL_S", "SPECIAL_S_P")
    private val webHosts = setOf("bilibili.com", "www.bilibili.com", "m.bilibili.com")
    private val playbackPath = Regex("^/(?:play/(?:ep|ss)[1-9][0-9]*|season/[1-9][0-9]*)/?$")

    fun classify(cardType: String?, cardGoto: String?, goTo: String?, uri: String?): Kind {
        val tokens = listOfNotNull(cardType, cardGoto, goTo).mapNotNull(HostContentSemanticClassifier::normalizedToken)
        // 显式 UGC 类型不凭一个外观模板猜成正片；真正的番剧播放路由仍可独立确认。
        val ugc = tokens.any { it == "BANGUMI_UGC" }
        if ((!ugc && tokens.any { it in pgcTypes }) || isPgcPlayback(uri)) return Kind.PGC
        // 有 PGC 证据的 special 卡优先归影视，避免两个开关互相代替。
        if (tokens.any { it in specialTypes }) return Kind.SPECIAL
        return Kind.UNKNOWN
    }

    private fun isPgcPlayback(raw: String?): Boolean {
        if (raw.isNullOrBlank() || raw.length > 4096) return false
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return false
        if (uri.rawUserInfo != null || uri.port != -1) return false
        val scheme = uri.scheme?.lowercase() ?: return false
        val host = uri.host?.lowercase() ?: return false
        val path = uri.rawPath.orEmpty()
        return when {
            scheme == "bilibili" && (host == "bangumi" || host == "pgc") -> playbackPath.matches(path)
            (scheme == "http" || scheme == "https") && host in webHosts && path.startsWith("/bangumi/") ->
                playbackPath.matches(path.removePrefix("/bangumi"))
            else -> false
        }
    }
}
