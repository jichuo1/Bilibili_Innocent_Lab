package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.os.SystemClock
import android.util.Log

/** 固定阶段/枚举，每组合至多每分钟一条；不输出载荷、nonce、路径或异常正文。 */
internal object ReceiptQueryLog {
    private val last = hashMapOf<String, Long>()
    @Synchronized fun failure(stage: String, reason: ReceiptQueryFailure) {
        if (reason == ReceiptQueryFailure.NONE) return
        val key = "$stage:$reason"
        val now = SystemClock.elapsedRealtime()
        if (last[key]?.let { now - it < 60_000L } == true) return
        last[key] = now
        Log.w("BilibiliInnocentLab", "receipt stage=$stage reason=$reason")
    }
}

