package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.hostProxyDefaultValue
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Pure rules for a request about to leave through the host's native ighttp stack (the "ktor-ignet"
 * engine of its KMP modules). The request is plain strings / bytes, so every rule here is a total
 * function that either returns the sealed value or throws, and a throw means the caller must not
 * send the request.
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
        val query = sanitizeFormString(beforeFragment.substring(queryAt + 1), identity, sign) ?: return url
        return base + (if (query.isEmpty()) "" else "?$query") + fragment
    }

    /**
     * Sanitize POST form or JSON bodies before ighttp copies them into native memory.
     * Returns the exact input [body] reference when unchanged.
     */
    fun formBody(
        body: ByteArray,
        contentType: String?,
        identity: IncognitoSession.Identity,
        sign: (Map<String, String>) -> String
    ): ByteArray {
        if (body.isEmpty()) return body
        val mediaType = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (mediaType == "application/x-www-form-urlencoded" ||
            (mediaType.isEmpty() && looksLikeFormEncoded(body))
        ) {
            val cleaned = sanitizeFormString(String(body, Charsets.UTF_8), identity, sign) ?: return body
            return cleaned.toByteArray(Charsets.UTF_8)
        }
        if (mediaType == "application/json" || (mediaType.isEmpty() && looksLikeJsonText(body))) {
            val parsed = runCatching { JSONTokener(String(body, Charsets.UTF_8)).nextValue() }
                .getOrElse { if (mediaType == "application/json") throw it else return body }
            if (parsed !is JSONObject && parsed !is JSONArray) {
                if (mediaType == "application/json") throw IllegalArgumentException("Malformed JSON body")
                return body
            }
            if (!sanitizeJsonValue(parsed, identity)) return body
            return parsed.toString().toByteArray(Charsets.UTF_8)
        }
        return body
    }

    /**
     * Wrap an ighttp `StreamDataProvider` proxy so streamed POST bodies are drained, sanitized via
     * [formBody], and sliced safely on the native reader thread instead of touching the coroutine channel on build().
     */
    fun wrapStreamProvider(
        loader: ClassLoader,
        providerType: Class<*>,
        original: Any,
        produce: Method,
        contentType: String?,
        identity: IncognitoSession.Identity,
        sign: (Map<String, String>) -> String,
        maxBytes: Int = MAX_STREAM_BYTES
    ): Any {
        require(providerType.isInterface) { "StreamDataProvider must be an interface" }
        val state = object {
            val lock = Any()
            var sanitized: ByteArray? = null
            var offset = 0
            var failure: Throwable? = null
        }
        return Proxy.newProxyInstance(providerType.classLoader ?: loader, arrayOf(providerType)) { proxy, method, args ->
            when {
                method.declaringClass == Any::class.java -> when (method.name) {
                    "equals" -> proxy === args?.getOrNull(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "BIL incognito stream body sanitizer"
                    else -> hostProxyDefaultValue(method.returnType)
                }
                method.name == "onProduceData" -> synchronized(state.lock) {
                    state.failure?.let { throw it }
                    val payload = state.sanitized ?: try {
                        val out = ByteArrayOutputStream()
                        while (true) {
                            val chunk = try {
                                produce.invoke(original, STREAM_CHUNK_BYTES) as? ByteArray
                            } catch (e: InvocationTargetException) {
                                throw e.targetException ?: e
                            }
                            if (chunk == null || chunk.isEmpty()) break
                            if (out.size() + chunk.size > maxBytes) {
                                throw IOException("Incognito stream body exceeds bound")
                            }
                            out.write(chunk)
                        }
                        formBody(out.toByteArray(), contentType, identity, sign).also { state.sanitized = it }
                    } catch (t: Throwable) {
                        state.failure = t
                        throw t
                    }
                    val remaining = payload.size - state.offset
                    if (remaining <= 0) ByteArray(0)
                    else {
                        val requested = (args?.getOrNull(0) as? Number)?.toInt()?.coerceAtLeast(1) ?: STREAM_CHUNK_BYTES
                        val take = minOf(requested, remaining)
                        ByteArray(take).also { slice ->
                            System.arraycopy(payload, state.offset, slice, 0, take)
                            state.offset += take
                        }
                    }
                }
                else -> hostProxyDefaultValue(method.returnType)
            }
        }
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

    fun findHeaderCaseInsensitive(map: Map<String, String>, name: String): String? =
        map.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    fun removeHeaderCaseInsensitive(map: MutableMap<String, String>, name: String) {
        val keys = map.keys.filter { it.equals(name, ignoreCase = true) }
        keys.forEach(map::remove)
    }

    fun updateContentLengthIfPresent(map: MutableMap<String, String>, length: Int) {
        val keys = map.keys.filter { it.equals("content-length", ignoreCase = true) }
        keys.forEach { map[it] = length.toString() }
    }

    private fun sanitizeFormString(
        encodedBody: String,
        identity: IncognitoSession.Identity,
        sign: (Map<String, String>) -> String
    ): String? {
        val raw = encodedBody.split('&').filter { it.isNotEmpty() }
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
        if (!changed) return null
        return if (signed) sign(LinkedHashMap<String, String>().apply { kept.forEach { (k, v) -> put(k, v) } })
        else encoded.joinToString("&")
    }

    private fun looksLikeFormEncoded(body: ByteArray): Boolean {
        if (0.toByte() in body) return false
        val text = runCatching {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(body)).toString()
        }.getOrNull() ?: return false
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("{") || trimmed.startsWith("[") || trimmed.startsWith("<")) return false
        if (trimmed.any { it.isISOControl() }) return false
        val parts = trimmed.split('&').filter { it.isNotEmpty() }
        return parts.isNotEmpty() && parts.all { '=' in it }
    }

    private fun looksLikeJsonText(body: ByteArray): Boolean {
        if (0.toByte() in body) return false
        val text = runCatching {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(body)).toString()
        }.getOrNull()?.trimStart() ?: return false
        return text.startsWith("{") || text.startsWith("[")
    }

    private fun sanitizeJsonValue(value: Any, identity: IncognitoSession.Identity): Boolean {
        var changed = false
        when (value) {
            is JSONObject -> {
                val keys = ArrayList<String>().apply { value.keys().forEachRemaining(::add) }
                for (key in keys) {
                    when (PrivacyNetworkPolicy.keyAction(key)) {
                        PrivacyNetworkPolicy.KeyAction.DROP -> {
                            value.remove(key)
                            changed = true
                        }
                        PrivacyNetworkPolicy.KeyAction.DEVICE_ID -> if (value.opt(key) is String) {
                            if (value.optString(key) != identity.buvid) {
                                value.put(key, identity.buvid)
                                changed = true
                            }
                        }
                        PrivacyNetworkPolicy.KeyAction.SESSION_ID -> if (value.opt(key) is String) {
                            if (value.optString(key) != identity.sessionId) {
                                value.put(key, identity.sessionId)
                                changed = true
                            }
                        }
                        PrivacyNetworkPolicy.KeyAction.KEEP -> {
                            val child = value.opt(key)
                            if (child != null && sanitizeJsonValue(child, identity)) changed = true
                        }
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until value.length()) {
                    val child = value.opt(i)
                    if (child != null && sanitizeJsonValue(child, identity)) changed = true
                }
            }
        }
        return changed
    }

    private fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")
    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private const val STREAM_CHUNK_BYTES = 8192
    internal const val MAX_STREAM_BYTES = 64 * 1024
}
