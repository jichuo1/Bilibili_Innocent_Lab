package com.Bilibili_Innocent_Lab.xposedmodule.runtime

/** 进程启动的单调时刻只用于当前模块进程内排序；不落盘，不代替实时响应。 */
internal class ReceiptSessionGate<T> {
    companion object {
        fun isNewerSequence(previous: Long, candidate: Long): Boolean = candidate > 0 && candidate > previous
    }
    data class Session<T>(val started: Long, val pid: Int, val endpoint: T)
    private var latestStart = -1L
    private var current: Session<T>? = null

    @Synchronized fun accept(started: Long, pid: Int, endpoint: T): Boolean {
        if (started <= 0 || pid <= 0 || started < latestStart) return false
        if (started == latestStart && current != Session(started, pid, endpoint)) return false
        latestStart = started
        current = Session(started, pid, endpoint)
        return true
    }

    @Synchronized fun current(): Session<T>? = current
    @Synchronized fun matches(session: Session<T>): Boolean = current == session
    @Synchronized fun remove(session: Session<T>) {
        if (current == session) current = null
    }
}
