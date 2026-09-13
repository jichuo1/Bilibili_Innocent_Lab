package com.Bilibili_Innocent_Lab.xposedmodule.runtime

/** 两个发送通道共用的本地排序点。read 只读取内存快照，不能进行 IPC。 */
internal class ReceiptEnvelopeSequencer {
    data class Stamped<T>(val sequence: Long, val value: T)
    private var sequence = 0L
    @Synchronized fun <T> capture(read: () -> T): Stamped<T> {
        check(sequence < Long.MAX_VALUE)
        val immutable = read()
        return Stamped(++sequence, immutable)
    }
}
