package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

/** A local stream lifecycle: accepts activity without buffering it for a future network session. */
internal class IncognitoStreamSink(
    private val acknowledge: (Long) -> Unit,
    private val complete: () -> Unit
) {
    private var closed = false
    private var sequence = 0L

    @Synchronized fun acceptForAck(): Long {
        if (closed) return 0L
        val id = ++sequence
        acknowledge(id)
        return id
    }

    @Synchronized fun close() {
        if (closed) return
        closed = true
        complete()
    }
}
