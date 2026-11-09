package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

/** Pure policy; never match an unrelated host by substring. */
internal object PrivacyNetworkPolicy {
    fun isBiliHost(host: String): Boolean = listOf("bilibili.com", "biliapi.net", "biliapi.com",
        "bilivideo.com", "hdslb.com", "biligame.com").any { host == it || host.endsWith(".$it") }

    /** Keep the tracker's real config: an empty reply can make its flush interval zero and OOM. */
    fun isGameTrackerReport(host: String, path: String): Boolean {
        val normalizedHost = host.lowercase().trimEnd('.')
        return (normalizedHost == "biligame.net" || normalizedHost.endsWith(".biligame.net")) &&
            path.lowercase().trimEnd('/') == "/collector/api/report"
    }

    /** Observed ad measurement beacon puts device-like identifiers directly in the URL path. */
    fun isAdMeasurementBeacon(host: String, path: String): Boolean =
        host.lowercase().trimEnd('.') == "g.cn.miaozhen.com" && path.startsWith("/x/k=", ignoreCase = true)

    /** Unlike a query string, the path of this beacon itself contains identifier values. */
    fun diagnosticTarget(host: String, path: String): String =
        if (isAdMeasurementBeacon(host, path)) host + "/x/[redacted]" else host + path

    fun blockHttp(host: String, path: String, method: String): Boolean {
        // Withhold the confirmed game upload and observed third-party measurement beacon.
        if (isGameTrackerReport(host, path) || isAdMeasurementBeacon(host, path)) return true
        val normalizedHost = host.lowercase().trimEnd('.')
        if (!isBiliHost(normalizedHost)) return false
        if (normalizedHost == "data.bilibili.com" || normalizedHost.endsWith(".data.bilibili.com") ||
            normalizedHost == "api.bilibili.com" && path.startsWith("/x/report/") ||
            normalizedHost == "dataflow.biliapi.com") return true
        val p = path.lowercase().trimEnd('/')
        // Endpoint names are path segments, including terminal /click and /report.
        if (p.split('/').any { it in setOf("click", "report", "feedback", "heartbeat",
                "neuron", "exposure", "impression") }) return true
        if (listOf("/history/report", "/history/heartbeat", "/history/add", "/history/upload",
                "/history/clear", "/history/delete", "/history/del", "/history/search",
                "/click/", "/report/", "/feedback/", "/search/history", "/search/sync",
                "/search/clear", "/neuron/", "/datacenter/", "/heartbeat", "/relation/modify",
                "/fav/resource/", "/fav/video/", "/toview/add", "/toview/del")
                .any(p::contains)) return true
        // Unknown writes never leave an incognito session. Read-only POSTs are explicitly allowed.
        if (method.uppercase() !in setOf("GET", "HEAD", "OPTIONS")) {
            return !listOf("/x/player/playurl", "/x/v2/playurl", "/x/player/wbi/playurl")
                .contains(p)
        }
        return false
    }

    fun blockRpc(fullName: String): Boolean {
        val service = fullName.substringBefore('/').lowercase()
        val method = fullName.substringAfter('/', "").lowercase()
        if (service.contains("heartbeat") || service.contains("report") ||
            service.contains("neuron")) return true
        if (method in setOf("room", "enter", "leave", "expose", "exposure", "impression", "record")) return true
        if (service.contains("history") && !method.startsWith("get")) return true
        if (service.contains("search") && listOf("addhistory", "delhistory", "clearhistory",
                "synchistory", "report").any(method::contains)) return true
        return listOf("report", "heartbeat", "click", "dislike", "feedback", "add", "delete",
            "modify", "follow", "like", "coin", "share", "send", "publish", "set", "update")
            .any(method::startsWith)
    }

    fun parameters(input: Map<String, String>, identity: IncognitoSession.Identity): Map<String, String> =
        input.filterKeys { it.lowercase() !in ACCOUNT_KEYS && it.lowercase() !in TRACKING_KEYS }
            .mapValues { (key, value) ->
                when (key.lowercase()) {
                    "buvid", "buvid3", "buvid4", "device_id", "deviceid", "fp_local", "fp_remote",
                    "fp", "guestid", "guest_id", "android_id", "oaid", "imei" -> identity.buvid
                    "session_id", "sessionid", "session" -> identity.sessionId
                    else -> value
                }
            }

    private val ACCOUNT_KEYS = setOf("access_key", "access_token", "authorization", "cookie",
        "sessdata", "dedeuserid", "dedeuserid__ckmd5", "bili_jct", "csrf", "csrf_token")
    private val TRACKING_KEYS = setOf("spmid", "from_spmid", "trackid", "track_id", "search_id",
        "from_trackid", "from_av", "from_viewaid", "from_bv", "from_cid", "recommend_id", "ad_extra")
}
