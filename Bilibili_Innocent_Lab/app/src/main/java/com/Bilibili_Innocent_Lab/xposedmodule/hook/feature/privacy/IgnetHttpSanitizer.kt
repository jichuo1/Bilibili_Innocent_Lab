package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Pure rules for a request about to leave through the host's native ighttp stack (the "ktor-ignet"
 * engine of its KMP modules). The request is plain strings — a URL and a String header map — so
 * every rule here is a total function that either returns the sealed value or throws, and a throw
 * means the caller must not send the request.
 */
internal object IgnetHttpSanitizer {
    data class Target(val host: String, val path: String)

    /** Host and encoded path of an absolute http(s) URL; throws on anything else. */
    fun target(url: String): Target {
        val scheme = url.substringBefore("://", "")
        require(scheme.equals("http", true) || scheme.equals("https", true)) { "Unsupported URL scheme" }
        val rest = url.substring(scheme.length + 3)
        val authorityEnd = rest.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, authorityEnd).substringAfterLast('@')
        val host = if (authority.startsWith("[")) authority.substringBefore(']') + "]" else authority.substringBefore(':')
        require(host.isNotEmpty()) { "URL without host" }
        val path = rest.substring(authorityEnd).substringBefore('?').substringBefore('#').ifEmpty { "/" }
        return Target(host.lowercase().trimEnd('.'), path)
    }

    /**
     * Remove account parameters and replace device parameters, exactly as the OkHttp boundary does
     * ([PrivacyNetworkPolicy.parameters]). An untouched URL is returned as-is so its encoding and
     * signature survive. A signed query whose parameters changed is re-signed with [sign], which
     * receives the decoded remaining parameters and returns the full encoded query.
     */
    fun url(url: String, identity: IncognitoSession.Identity, sign: (Map<String, String>) -> String): String {
        val fragmentAt = url.indexOf('#')
        val beforeFragment = if (fragmentAt < 0) url else url.substring(0, fragmentAt)
        val fragment = if (fragmentAt < 0) "" else url.substring(fragmentAt)
        val queryAt = beforeFragment.indexOf('?')
        if (queryAt < 0) return url
        val base = beforeFragment.substring(0, queryAt)
        val raw = beforeFragment.substring(queryAt + 1).split('&').filter { it.isNotEmpty() }
        var changed = false
        var signed = false
        val kept = ArrayList<Pair<String, String>>()
        val encoded = ArrayList<String>()
        for (pair in raw) {
            val name = decode(pair.substringBefore('='))
            val value = decode(pair.substringAfter('=', ""))
            if (name == "sign") { signed = true; continue }
            val sealed = PrivacyNetworkPolicy.parameters(mapOf(name to value), identity)[name]
            when {
                sealed == null -> changed = true
                sealed != value -> { changed = true; kept += name to sealed; encoded += encode(name) + "=" + encode(sealed) }
                else -> { kept += name to value; encoded += pair }
            }
        }
        if (!changed) return url
        val query = if (signed) sign(LinkedHashMap<String, String>().apply { kept.forEach { (k, v) -> put(k, v) } })
            else encoded.joinToString("&")
        return base + (if (query.isEmpty()) "" else "?$query") + fragment
    }

    /** Seal a live header map in place (ighttp keeps a case-insensitive TreeMap). */
    fun headers(map: MutableMap<String, String>, identity: IncognitoSession.Identity) {
        for (key in ArrayList(map.keys)) {
            when (key.lowercase()) {
                in KmpHeaderSanitizer.REMOVED -> map.remove(key)
                // Protobuf identity in a String header cannot be re-sealed here; it never leaves.
                KmpHeaderSanitizer.METADATA_BIN, KmpHeaderSanitizer.DEVICE_BIN -> map.remove(key)
                in KmpHeaderSanitizer.DEVICE_IDS -> map[key] = identity.buvid
                in KmpHeaderSanitizer.SESSION_IDS -> map[key] = identity.sessionId
            }
        }
    }

    private fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")
    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
