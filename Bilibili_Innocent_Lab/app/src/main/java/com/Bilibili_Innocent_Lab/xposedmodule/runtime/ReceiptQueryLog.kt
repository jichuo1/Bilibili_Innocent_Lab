package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.os.SystemClock
import android.util.Log

/** 固定阶段/枚举，每组合至多每分钟一条；不输出载荷、nonce、路径或异常正文。 */
internal object ReceiptQueryLog {
    private val last = hashMapOf<String, Long>()
    @Synchronized fun failure(stage: String, reason: ReceiptQueryFailure) {
        if (reason == ReceiptQueryFailure.NONE) return
        if (!claim("$stage:$reason")) return
        Log.w("BilibiliInnocentLab", "receipt stage=$stage reason=$reason")
    }

    @Synchronized fun superseded() {
        if (claim("push:superseded")) Log.d("BilibiliInnocentLab", "receipt stage=push result=superseded")
    }

    /** 只在上述同步入口中访问限流表。 */
    private fun claim(key: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (last[key]?.let { now - it < 60_000L } == true) return false
        last[key] = now
        return true
    }
}

