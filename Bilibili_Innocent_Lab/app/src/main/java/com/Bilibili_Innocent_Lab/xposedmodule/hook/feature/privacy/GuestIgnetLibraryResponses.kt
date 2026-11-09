package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.net.URI
import java.net.URLDecoder
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Answer a guest's favorites and watch-later list reads on the KMP pages from the local library.
 *
 * The 9.x favorites and watch-later pages send their list requests through Ktor on the native "ktor-ignet" engine
 * (`kntr.base.ktor.ignet.IgnetHttpEngine`), not through okretro, so [GuestNativeListHttpHooks] never sees them. An
 * `IgHttpResponse` cannot be created on the Java side (its body lives in native memory), so the request still goes out
 * (sealed by [IgnetHttpBoundary] when incognito is on) and this class replaces what the engine reads from the response.
 * On all 38 supported builds the engine reads exactly `httpCode()`, `httpMessage()`, `protocol()`, `headers()` and then
 * `readDataV2(byte[], int)` in a loop until it returns a value that is not positive, then `close()` (hostlab q32).
 * `IgHttpRequest.execute()`, `url()` and `method()` have the same shape on all 38 builds as well.
 *
 * GET requests to [READ_PATHS] on [HOSTS] are answered from the local library. A request whose local answer fails keeps
 * the server's reply, which the page shows as a load failure.
 *
 * POST requests to [WRITE_PATHS] (delete, move, new folder, add to watch later and so on) must not reach the server at
 * all. The URL and body are copied into native memory when the request is constructed, so `Builder.build()` is the last
 * point to stop them: the builder is turned into a GET of [HARMLESS_READ], and when that read returns the write is
 * applied by the local protocol and its reply replaces the read's reply. A failed local write is answered with
 * [WRITE_FAILED], never with a success. When the form is streamed (`mStreamDataProvider` instead of `mBody`, the
 * default since `ktor_ignet_post_stream_body_enable`), the provider is detached from the request so native code never
 * reads it, and the module drains it itself after the read (see [PendingWrite]). The builder fields `mUrl`, `mMethod`,
 * `mBody`, `mStreamDataProvider` and the setters `url`, `get`, `build` have one shape on all 38 builds, and the Ktor
 * engine fills the builder through `streamDataProvider` and `method(String, byte[])` there (hostlab q34).
 * Status keys: `guest_ignet_library` (installed/absent/failed), `_answered` (a list was served), `_wrote` (a write was
 * applied locally), `_failed`, `_write` (absent when the builder cannot be adapted).
 */
internal class GuestIgnetLibraryResponses(
    private val isGuest: () -> Boolean,
    /** Local JSON for a read path and its query, or null when the library has no answer for it. */
    private val answer: (path: String, params: Map<String, String>) -> String?,
    private val requestClass: String = REQUEST,
    private val responseClass: String = RESPONSE
) {
    /** A replaced body; read by the engine's single reader coroutine, guarded anyway. */
    private class Body(val bytes: ByteArray) {
        private var offset = 0

        @Synchronized
        fun read(target: ByteArray, max: Int): Int {
            val count = minOf(max, target.size, bytes.size - offset)
            if (count <= 0) return 0
            System.arraycopy(bytes, offset, target, 0, count)
            offset += count
            return count
        }
    }

    /**
     * A write held back at build(). Ktor streams POST bodies by default (`ktor_ignet_post_stream_body_enable`), so the
     * form often sits in the host's `StreamDataProvider` instead of `mBody`. The provider blocks on the host's coroutine
     * channel, so it is drained on its own thread with a time and size bound; a form that cannot be read fails the write.
     */
    internal class PendingWrite(val path: String, private val query: Map<String, String>, private val stream: ((Int) -> ByteArray?)?) {
        fun params(): Map<String, String> {
            val produce = stream ?: return query
            val out = java.io.ByteArrayOutputStream()
            val failure = arrayOfNulls<Throwable>(1)
            val reader = Thread({
                runCatching {
                    while (true) {
                        val chunk = produce(CHUNK) ?: break
                        if (chunk.isEmpty()) break
                        check(out.size() + chunk.size <= MAX_FORM) { "form too large" }
                        synchronized(out) { out.write(chunk) }
                    }
                }.onFailure { failure[0] = it }
            }, "bil-guest-form").apply { isDaemon = true }
            reader.start()
            reader.join(DRAIN_TIMEOUT_MS)
            check(!reader.isAlive) { "form not readable in time" }
            failure[0]?.let { throw it }
            val text = synchronized(out) { String(out.toByteArray(), Charsets.UTF_8) }
            return LinkedHashMap(query).apply { putAll(form(text)) }
        }
    }

    fun install(env: HookEnvironment): Int = runCatching {
        val loader = requireNotNull(env.classLoader)
        val request = PrivacyStructuralResolver.type(loader, requestClass)
        val response = PrivacyStructuralResolver.type(loader, responseClass)
        if (request == null || response == null) {
            env.reportStatus("guest_ignet_library", "absent")
            return@runCatching 0
        }
        val execute = PrivacyStructuralResolver.method(request, "execute")?.takeIf { it.returnType == response }
        val url = PrivacyStructuralResolver.method(request, "url")?.takeIf { it.returnType == String::class.java }
        val method = PrivacyStructuralResolver.method(request, "method")?.takeIf { it.returnType == String::class.java }
        val httpCode = PrivacyStructuralResolver.method(response, "httpCode")?.takeIf { it.returnType == Int::class.javaPrimitiveType }
        val httpMessage = PrivacyStructuralResolver.method(response, "httpMessage")?.takeIf { it.returnType == String::class.java }
        val headers = PrivacyStructuralResolver.method(response, "headers")?.takeIf { Map::class.java.isAssignableFrom(it.returnType) }
        val read = PrivacyStructuralResolver.method(response, "readDataV2", ByteArray::class.java, Int::class.javaPrimitiveType!!)
            ?.takeIf { it.returnType == Int::class.javaPrimitiveType }
        if (execute == null || url == null || method == null || httpCode == null || httpMessage == null || headers == null || read == null) {
            env.reportStatus("guest_ignet_library", "absent")
            return@runCatching 0
        }
        // Weak: a response the host drops is never kept alive here.
        val bodies: MutableMap<Any, Body> = Collections.synchronizedMap(WeakHashMap())
        // Writes turned into a harmless read at build(), keyed by the built request.
        val writes: MutableMap<Any, PendingWrite> = Collections.synchronizedMap(WeakHashMap())
        val answered = AtomicBoolean(false)
        val failed = AtomicBoolean(false)
        val wrote = AtomicBoolean(false)
        fun bodyOf(target: Any?): Body? = if (target == null || bodies.isEmpty()) null else bodies[target]
        var hooks = 5

        val builder = PrivacyStructuralResolver.type(loader, "$requestClass\$Builder")
        val build = builder?.let { PrivacyStructuralResolver.method(it, "build") }?.takeIf { it.returnType == request }
        val toGet = builder?.let { PrivacyStructuralResolver.method(it, "get") }?.takeIf { it.returnType == builder }
        val setUrl = builder?.let { PrivacyStructuralResolver.method(it, "url", String::class.java) }?.takeIf { it.returnType == builder }
        val urlField = builder?.let { Lookup.fieldOrNull(it, "mUrl") }?.takeIf { it.type == String::class.java }
        val methodField = builder?.let { Lookup.fieldOrNull(it, "mMethod") }?.takeIf { it.type == String::class.java }
        val bodyField = builder?.let { Lookup.fieldOrNull(it, "mBody") }?.takeIf { it.type == ByteArray::class.java }
        val streamField = builder?.let { Lookup.fieldOrNull(it, "mStreamDataProvider") }
        val produce = streamField?.type?.let { PrivacyStructuralResolver.method(it, "onProduceData", Int::class.javaPrimitiveType!!) }
            ?.takeIf { it.returnType == ByteArray::class.java }
        if (builder != null && build != null && toGet != null && setUrl != null && urlField != null && methodField != null &&
            bodyField != null && streamField != null && produce != null) {
            env.registrar.exact("guest.ignet.library.write", builder, build.name) {
                before {
                    if (!runCatching { isGuest() }.getOrDefault(false)) return@before
                    val target = instance ?: return@before
                    runCatching {
                        val body = bodyField.get(target) as ByteArray?
                        val write = listWrite(methodField.get(target) as? String, urlField.get(target) as? String, body) ?: return@runCatching
                        val stream = if (body == null) streamField.get(target) else null
                        // The URL and body are copied into native memory by the constructor: this is the last point to stop the
                        // write. With the provider detached, native code never reads the form; it is drained after the read.
                        setUrl.invoke(target, HARMLESS_READ)
                        streamField.set(target, null)
                        toGet.invoke(target)
                        setObjectExtra(WRITE, PendingWrite(write.first, write.second, stream?.let { s -> { size: Int -> produce.invoke(s, size) as ByteArray? } }))
                    }.onFailure { env.logError("guest_ignet_library_write", "[BIL] Local list write not prepared: ${it.javaClass.simpleName}") }
                }
                after {
                    val write = getObjectExtra(WRITE) as? PendingWrite ?: return@after
                    if (!hasThrowable) result?.let { writes[it] = write }
                }
            }
            hooks++
        } else {
            env.reportStatus("guest_ignet_library_write", "absent")
        }

        env.registrar.exact("guest.ignet.library.execute", request, execute.name) {
            after {
                val target = instance ?: return@after
                val write = writes.remove(target)
                if (hasThrowable || !runCatching { isGuest() }.getOrDefault(false)) return@after
                val reply = result ?: return@after
                if (write != null) {
                    // The reply of the harmless read must never reach the page as the reply of the write.
                    val local = runCatching { answer(write.path, write.params()) }
                        .onFailure { env.logError("guest_ignet_library_write", "[BIL] Local list write failed: ${it.javaClass.simpleName}") }
                        .getOrNull()
                    bodies[reply] = Body((local ?: WRITE_FAILED).toByteArray(Charsets.UTF_8))
                    if (local != null && wrote.compareAndSet(false, true)) env.reportStatus("guest_ignet_library_wrote", write.path)
                    if (local == null && failed.compareAndSet(false, true)) env.reportStatus("guest_ignet_library_failed", write.path)
                    return@after
                }
                val list = runCatching { listRead(method.invoke(target) as? String, url.invoke(target) as? String) }.getOrNull() ?: return@after
                runCatching { answer(list.first, list.second) }
                    .onSuccess { json ->
                        if (json == null) return@onSuccess
                        bodies[reply] = Body(json.toByteArray(Charsets.UTF_8))
                        if (answered.compareAndSet(false, true)) env.reportStatus("guest_ignet_library_answered", list.first)
                    }
                    .onFailure {
                        env.logError("guest_ignet_library_answer", "[BIL] Local list answer failed: ${it.javaClass.simpleName}")
                        if (failed.compareAndSet(false, true)) env.reportStatus("guest_ignet_library_failed", list.first)
                    }
            }
        }
        env.registrar.exact("guest.ignet.library.code", response, httpCode.name) {
            before { if (bodyOf(instance) != null) result = 200 }
        }
        env.registrar.exact("guest.ignet.library.message", response, httpMessage.name) {
            before { if (bodyOf(instance) != null) result = "OK" }
        }
        env.registrar.exact("guest.ignet.library.headers", response, headers.name) {
            // No Content-Encoding or Content-Length from the server reply: the body below is plain and has its own length.
            before { if (bodyOf(instance) != null) result = mapOf(CONTENT_TYPE to JSON_TYPE) }
        }
        env.registrar.exact("guest.ignet.library.read", response, read.name, *read.parameterTypes) {
            before {
                val body = bodyOf(instance) ?: return@before
                val target = argOrNull(0) as? ByteArray
                result = if (target == null) 0 else body.read(target, argOrNull(1) as? Int ?: 0)
            }
        }
        env.reportStatus("guest_ignet_library", "installed")
        hooks
    }.getOrElse {
        env.logError("guest_ignet_library", "[BIL] Guest ignet library unavailable: ${it.javaClass.simpleName}")
        env.reportStatus("guest_ignet_library", "failed")
        0
    }

    internal companion object {
        const val REQUEST = "com.bilibili.lib.ighttp.IgHttpRequest"
        const val RESPONSE = "com.bilibili.lib.ighttp.IgHttpResponse"
        const val CONTENT_TYPE = "Content-Type"
        const val JSON_TYPE = "application/json; charset=utf-8"
        val HOSTS = setOf("api.bilibili.com", "app.bilibili.com", "api.biliapi.net", "app.biliapi.net")

        /** List reads of the favorites and watch-later pages and the folder editor; all are answered by the local protocols. */
        val READ_PATHS = setOf(
            "/x/v3/fav/tab", "/x/v3/fav/tab/my_fav", "/x/v3/fav/tab/fav", "/x/v3/fav/tab/fav/search",
            "/x/v3/fav/tab/fol", "/x/v3/fav/folder/resources", "/x/v3/fav/folder/info",
            "/x/v3/fav/folder/whitelist", "/x/v3/fav/folder/clean-state", "/x/v3/fav/folder/created/list",
            "/x/v3/fav/folder/created/list-all", "/x/v3/fav/folder/collected/list", "/x/v3/fav/resource/list",
            "/x/v3/fav/resource/ids",
            "/x/v2/history/toview", "/x/v2/history/toview/v2/list"
        )

        /** List writes the local protocols apply (see NativeFavoriteProtocol and NativeWatchLaterProtocol). */
        val WRITE_PATHS = setOf(
            "/x/v3/fav/tab/dels", "/x/v3/fav/resource/batch-del", "/x/v3/fav/folder/del", "/x/v3/fav/folder/add",
            "/x/v3/fav/folder/edit", "/x/v3/fav/folder/add_top", "/x/v3/fav/resource/sort", "/x/v3/fav/resource/copy",
            "/x/v3/fav/resource/move", "/x/v3/fav/tab/clean_invalid", "/x/v3/fav/tab/red_dot/clean", "/x/v3/fav/tab/report",
            "/x/v3/fav/statistics/count", "/x/v3/fav/resource/clean",
            "/x/v2/history/toview/v2/dels", "/x/v2/history/toview/del", "/x/v2/history/toview/clear",
            "/x/v2/history/toview/copy", "/x/v2/history/toview/move", "/x/v2/history/toview/add", "/x/v3/fav/toview/adds"
        )

        /** What a guest write is sent as instead: a public read the host already calls for guests. Its reply is replaced, never shown. */
        const val HARMLESS_READ = "https://api.bilibili.com/x/web-interface/nav"
        /** Reply when the local write failed: the page shows its own error instead of a success that did not happen. */
        const val WRITE_FAILED = """{"code":-500,"message":"本地资料库写入失败","ttl":1,"data":null}"""
        private const val WRITE = "bil.guest.ignet.write"
        private const val CHUNK = 8192
        private const val MAX_FORM = 64 * 1024
        private const val DRAIN_TIMEOUT_MS = 3000L

        /** (path, query) of a guest list read this class answers, or null for anything else. */
        fun listRead(method: String?, url: String?): Pair<String, Map<String, String>>? {
            if (!method.equals("GET", ignoreCase = true)) return null
            val (path, params) = target(url) ?: return null
            return if (path in READ_PATHS) path to params else null
        }

        /**
         * (path, query plus form fields) of a guest list write the local protocols apply, or null for anything else.
         * The body must be a form (`a=1&b=2`); Ktor's submitForm sends exactly that.
         */
        fun listWrite(method: String?, url: String?, body: ByteArray?): Pair<String, Map<String, String>>? {
            if (!method.equals("POST", ignoreCase = true)) return null
            val (path, params) = target(url) ?: return null
            if (path !in WRITE_PATHS) return null
            val all = LinkedHashMap(params)
            body?.let { all.putAll(form(String(it, Charsets.UTF_8))) }
            return path to all
        }

        private fun target(url: String?): Pair<String, Map<String, String>>? {
            if (url.isNullOrBlank()) return null
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host?.lowercase() !in HOSTS) return null
            val path = uri.rawPath?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
            return path to form(uri.rawQuery.orEmpty())
        }

        internal fun form(encoded: String): Map<String, String> {
            val params = linkedMapOf<String, String>()
            encoded.split('&').filter { it.isNotEmpty() }.forEach { pair ->
                val name = decode(pair.substringBefore('=')) ?: return@forEach
                params[name] = decode(pair.substringAfter('=', "")) ?: ""
            }
            return params
        }

        private fun decode(value: String): String? = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull()
    }
}
