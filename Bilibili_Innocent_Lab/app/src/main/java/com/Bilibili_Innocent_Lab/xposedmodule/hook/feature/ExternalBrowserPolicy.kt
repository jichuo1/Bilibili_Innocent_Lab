package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import java.util.Locale

/**
 * 「站外链接改用系统浏览器」的纯归属判定层。
 *
 * 这里只回答一个问题：**这次跳转是不是宿主内置 WebView 打开的第三方网页**。判定只看
 * 组件类名、scheme 和域名三项，全部是归属信息；任何"长度/格式/参数"之类的启发式都不在
 * 这一层，免得阈值取错以后故障完全不可观测。
 *
 * 白名单里除了 B 站自家域名，还刻意包含支付与银联网关：登录态和收银台必须留在应用内，
 * 踢到外部浏览器会直接让支付流程走不通。
 */
internal object ExternalBrowserPolicy {

    /** 宿主内置浏览器 Activity 的类名后缀；多个业务线各有一个同后缀实现。 */
    private const val WEB_ACTIVITY_SUFFIX = "MWebActivity"

    /** 留在应用内的域名：B 站自有站点 + 支付/银联网关。 */
    private val IN_APP_HOSTS = listOf(
        "bilibili.com",
        "bilibili.tv",
        "bilibili.cn",
        "biliapi.net",
        "bilivideo.com",
        "hdslb.com",
        "b23.tv",
        "bili2233.cn",
        "acg.tv",
        "alipay.com",
        "alipayobjects.com",
        "tenpay.com",
        "unionpay.com",
        "95516.com"
    )

    fun shouldOpenExternally(
        componentClassName: String?,
        scheme: String?,
        host: String?
    ): Boolean {
        if (componentClassName?.endsWith(WEB_ACTIVITY_SUFFIX) != true) return false
        val normalizedScheme = scheme?.lowercase(Locale.ROOT)
        if (normalizedScheme != "http" && normalizedScheme != "https") return false
        val normalizedHost = host?.substringBefore(':')?.lowercase(Locale.ROOT)?.trimEnd('.')
        if (normalizedHost.isNullOrEmpty()) return false
        return IN_APP_HOSTS.none { normalizedHost == it || normalizedHost.endsWith(".$it") }
    }
}
