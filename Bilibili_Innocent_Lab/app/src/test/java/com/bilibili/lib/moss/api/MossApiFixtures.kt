package com.bilibili.lib.moss.api

import java.util.concurrent.TimeUnit

// From MossResponseHandler.kt
interface MossResponseHandler {
    fun onNext(reply: Any?)
    fun onError(error: Throwable)
    fun onCompleted()
}

// From CallOptions.kt
/** 只用于验证跨 ClassLoader 反射超时协议，不包含宿主代码。 */
class CallOptions {
    private var timeout: Long? = null

    fun withTimeout(value: Long?, unit: TimeUnit): CallOptions = CallOptions().apply {
        timeout = if (ignoreTimeout) null else value?.let(unit::toMillis)
    }

    fun getTimeoutInMs(): Long? = timeout

    companion object {
        var ignoreTimeout = false
    }
}
