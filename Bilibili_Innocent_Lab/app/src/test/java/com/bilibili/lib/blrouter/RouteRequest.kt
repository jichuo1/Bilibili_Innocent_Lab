package com.bilibili.lib.blrouter

import android.net.Uri

/** 游客收藏路由查找与拦截测试共用的宿主请求形状。 */
internal class RouteRequest(
    @Suppress("UNUSED_PARAMETER") builder: Builder
) {
    internal class Builder {
        private var targetUri: Uri? = null

        @Suppress("UNUSED_PARAMETER")
        constructor(uri: String)

        @Suppress("UNUSED_PARAMETER")
        constructor(uri: Uri)

        fun getTargetUri(): Uri? = targetUri

        fun setTargetUri(uri: Uri): Builder = apply { targetUri = uri }
    }
}
