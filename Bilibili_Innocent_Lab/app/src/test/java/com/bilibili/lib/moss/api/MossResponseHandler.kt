package com.bilibili.lib.moss.api

interface MossResponseHandler {
    fun onNext(reply: Any?)
    fun onError(error: Throwable)
    fun onCompleted()
}
