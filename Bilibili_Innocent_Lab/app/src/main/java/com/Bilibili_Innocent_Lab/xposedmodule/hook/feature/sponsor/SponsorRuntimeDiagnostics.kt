package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor

/** 每进程每种结果仅记一次，最多 32 项；不记录视频编号、响应正文或异常文本。 */
internal class SponsorRuntimeDiagnostics(
    private val scope: String = "sponsorblock_runtime",
    private val label: String = "SponsorBlock",
    private val write: (String, String, Boolean) -> Unit
) {
    private val seen = HashSet<String>()
    @Synchronized fun record(event: String, details: String = "") {
        if (seen.size >= 32 || !seen.add(event)) return
        val failure = event.startsWith("query.failed.") || event.startsWith("binding.failed.") ||
            event == "session.failed" || event == "observer.failed"
        write("$scope.$event", "[BIL] $label $event ${details.take(160)}".trimEnd(), failure)
    }
}
