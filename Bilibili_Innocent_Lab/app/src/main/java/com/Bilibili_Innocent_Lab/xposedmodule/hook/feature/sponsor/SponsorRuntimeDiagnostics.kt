package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor

/**
 * 进程级事件（安装、绑定链）每进程每种只记一次，最多 32 项。
 *
 * 播放会话级事件走 [session]：每个会话一个记录器，事件在会话内各记一次（最多 [SESSION_EVENTS] 项），
 * 全进程总行数以 [PROCESS_LINES] 封顶，所以第二个及之后的视频也有日志，且体量有界。
 * 宿主侧 `logInfo(key, …)` 自己也按 key 全进程去重，会话行的 key 因此带会话序号，不会被吞掉。
 *
 * 不记录视频编号、响应正文或异常文本。
 */
internal class SponsorRuntimeDiagnostics(
    private val scope: String = "sponsorblock_runtime",
    private val label: String = "SponsorBlock",
    private val write: (String, String, Boolean) -> Unit
) {
    private val seen = HashSet<String>()
    private var sessions = 0
    private var sessionLines = 0

    @Synchronized fun record(event: String, details: String = "") {
        if (seen.size >= 32 || !seen.add(event)) return
        write("$scope.$event", "[BIL] $label $event ${details.take(160)}".trimEnd(), isFailure(event))
    }

    /** 新会话的记录器；序号用于把同一会话的多行日志对到一起，不携带任何视频标识。 */
    fun session(): (String, String) -> Unit {
        val ordinal = synchronized(this) { ++sessions }
        val local = HashSet<String>()
        return { event, details -> recordSession(ordinal, local, event, details) }
    }

    @Synchronized private fun recordSession(ordinal: Int, local: HashSet<String>, event: String, details: String) {
        if (local.size >= SESSION_EVENTS || sessionLines >= PROCESS_LINES || !local.add(event)) return
        sessionLines++
        write("$scope.s$ordinal.$event", "[BIL] $label #$ordinal $event ${details.take(160)}".trimEnd(), isFailure(event))
    }

    private fun isFailure(event: String) = event.startsWith("query.failed.") || event.startsWith("binding.failed.") ||
        event == "session.failed" || event == "observer.failed"

    companion object {
        const val SESSION_EVENTS = 40
        const val PROCESS_LINES = 600
    }
}
