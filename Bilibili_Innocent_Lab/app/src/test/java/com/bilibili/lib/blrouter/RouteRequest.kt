package com.bilibili.lib.blrouter

import android.net.Uri

/** 仅用于验证功能安装器能在无首页 Adapter 点时独立找到路由创建与最终冻结边界。 */
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
