package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.agent.host.hostGetter
import com.Bilibili_Innocent_Lab.xposedmodule.agent.host.hostRead
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy.GuestNativeListHttpHooks
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit

/** 只使用宿主已经打开的作者空间。账号的原始资料和动态正文均不进入评分缓存。 */
internal class FishAccountMetadata(
    private val nowNanos: () -> Long = System::nanoTime
) {
    private val activity = FishAccountActivity()
    private val records = LinkedHashMap<Long, Account>()
    @Volatile private var scores: Map<Long, Int> = emptyMap()
    @Volatile private var nextExpiry: Long? = null

    val snapshot: Map<Long, Int>
        get() {
            val expiry = nextExpiry ?: return scores
            val now = nowNanos()
            if (now - expiry >= 0) synchronized(records) { publish(now) }
            return scores
        }

    private class Evidence(val points: Int, val expiry: Long)
    private class Account(val profile: Evidence?, val activity: Evidence?)

    private fun remember(uid: Long, profile: Int? = null, activity: Int? = null): Unit = synchronized(records) {
        if (uid <= 0L || profile == null && activity == null) return@synchronized
        val now = nowNanos()
        val expiry = now + TTL_NANOS
        val previous = records.remove(uid)
        val record = Account(
            profile?.takeIf { it > 0 }?.let { Evidence(it, expiry) }
                ?: previous?.profile?.takeIf { profile == null && now - it.expiry < 0 },
            activity?.takeIf { it > 0 }?.let { Evidence(it, expiry) }
                ?: previous?.activity?.takeIf { activity == null && now - it.expiry < 0 }
        )
        if (record.profile != null || record.activity != null) records[uid] = record
        if (records.size > MAX_ACCOUNTS) records.remove(records.keys.first())
        publish(now)
    }

    /** 调用方持有 records 锁，两个弱信号分别过期。 */
    private fun publish(now: Long) {
        val updated = LinkedHashMap<Long, Int>(records.size)
        var next: Long? = null
        val iterator = records.iterator()
        while (iterator.hasNext()) {
            val (uid, account) = iterator.next()
            val profile = account.profile?.takeIf { now - it.expiry < 0 }
            val activity = account.activity?.takeIf { now - it.expiry < 0 }
            if (profile == null && activity == null) {
                iterator.remove()
                continue
            }
            for (evidence in listOfNotNull(profile, activity)) {
                if (next == null || evidence.expiry - next < 0) next = evidence.expiry
            }
            updated[uid] = ((profile?.points ?: 0) + (activity?.points ?: 0)).coerceAtMost(MAX_POINTS)
        }
        if (updated != scores) scores = updated
        nextExpiry = next
    }

    internal fun observeSpaceResponse(uid: Long, text: String, nowMillis: Long) =
        observeResponse(Route(SPACE_HOST, SPACE_PATH, uid), text, nowMillis)

    private fun observeResponse(route: Route, text: String, nowMillis: Long) {
        if (text.length > MAX_BODY_BYTES) return
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (integer(root.opt("code")) != 0L) return
        val data = root.optJSONObject("data") ?: return
        synchronized(activity) {
            if (route.path == SPACE_PATH) {
                val card = data.optJSONObject("card") ?: return
                if (integer(card.opt("mid")) != route.uid) return
                val profile = profilePoints(data, card)
                val activityPoints = activity.observeSpace(route.uid, data, nowMillis)
                remember(route.uid, profile, activityPoints)
            } else {
                remember(route.uid, activity = activity.observe(route.host, route.path, route.uid, data, nowMillis))
            }
        }
    }

    fun install(environment: HookEnvironment) {
        runCatching { activity.install(environment) { uid, points -> remember(uid, activity = points) } }
        val loader = environment.classLoader ?: return
        val access = SpaceResponses.resolve(loader) ?: return
        val pending = ArrayDeque<Pair<WeakReference<Any>, Route>>(MAX_PENDING_BODIES)
        val streamInstalled = runCatching {
            val stream = access.byteStream
            environment.registrar.exact("comment.filter.account.stream", stream.declaringClass, stream.name) {
                after {
                    if (hasThrowable) return@after
                    val body = thisObject ?: return@after
                    val original = result as? InputStream ?: return@after
                    val route = synchronized(pending) {
                        pending.firstOrNull { it.first.get() === body }?.also(pending::remove)?.second
                    } ?: return@after
                    result = ObservedStream(original) { bytes ->
                        observeResponse(route, bytes.decodeToString(throwOnInvalidSequence = true), System.currentTimeMillis())
                    }
                }
            }
        }.isSuccess
        if (!streamInstalled) return
        runCatching {
            val convert = access.convert
            environment.registrar.exact(
                "comment.filter.account.response", convert.declaringClass, convert.name, *convert.parameterTypes
            ) {
                after {
                    if (hasThrowable) return@after
                    val route = access.route(argOrNull(0)) ?: return@after
                    val body = hostRead(access.body, result) ?: return@after
                    if (!access.byteStream.declaringClass.isInstance(body)) return@after
                    synchronized(pending) {
                        pending.removeAll { it.first.get() == null || it.first.get() === body }
                        if (pending.size >= MAX_PENDING_BODIES) pending.removeFirst()
                        pending.addLast(WeakReference(body) to route)
                    }
                }
            }
        }
    }

    private class SpaceResponses(
        val convert: java.lang.reflect.Method,
        val body: java.lang.reflect.Method,
        val byteStream: java.lang.reflect.Method,
        private val successful: java.lang.reflect.Method,
        private val request: java.lang.reflect.Method,
        private val method: java.lang.reflect.Method,
        private val url: java.lang.reflect.Method,
        private val scheme: java.lang.reflect.Method,
        private val port: java.lang.reflect.Method,
        private val host: java.lang.reflect.Method,
        private val path: java.lang.reflect.Method,
        private val query: java.lang.reflect.Method
    ) {
        fun route(response: Any?): Route? {
            val request = hostRead(request, response) ?: return null
            if (hostRead(method, request) != "GET") return null
            val url = hostRead(url, request) ?: return null
            val host = hostRead(host, url) as? String ?: return null
            if (host != SPACE_HOST && host != DYNAMIC_HOST) return null
            val path = hostRead(path, url) as? String ?: return null
            val uidParameter = when {
                host == SPACE_HOST && path == SPACE_PATH -> "vmid"
                host == DYNAMIC_HOST && path == DYNAMIC_PATH -> {
                    if (queryValue(url, "from") != "space") return null
                    "host_uid"
                }
                else -> return null
            }
            if (hostRead(scheme, url) != "https" || hostRead(port, url) != 443 ||
                hostRead(successful, response) != true
            ) return null
            val uid = queryValue(url, uidParameter)?.toLongOrNull()?.takeIf { it > 0L } ?: return null
            return Route(host, path, uid)
        }

        private fun queryValue(url: Any, key: String): String? =
            runCatching { (query.invoke(url, key) as? List<*>)?.singleOrNull() as? String }.getOrNull()

        companion object {
            fun resolve(loader: ClassLoader): SpaceResponses? {
                val call = Lookup.classOrNull(loader, "com.bilibili.okretro.call.BiliCall") ?: return null
                val response = Lookup.classOrNull(loader, "okhttp3.Response") ?: return null
                val converted = Lookup.classOrNull(loader, "retrofit2.Response") ?: return null
                val body = Lookup.classOrNull(loader, "okhttp3.ResponseBody") ?: return null
                val convert = GuestNativeListHttpHooks.locateConverter(call, response, converted)
                    ?.takeIf { it.returnType == converted } ?: return null
                val request = hostGetter(response, "request") ?: return null
                val url = hostGetter(request.returnType, "url") ?: return null
                val urlType = url.returnType
                return SpaceResponses(
                    convert, hostGetter(converted, "body", Any::class.java) ?: return null,
                    hostGetter(body, "byteStream", InputStream::class.java) ?: return null,
                    hostGetter(response, "isSuccessful", Boolean::class.javaPrimitiveType) ?: return null,
                    request, hostGetter(request.returnType, "method", String::class.java) ?: return null, url,
                    hostGetter(urlType, "scheme", String::class.java) ?: return null,
                    hostGetter(urlType, "port", Int::class.javaPrimitiveType) ?: return null,
                    hostGetter(urlType, "host", String::class.java) ?: return null,
                    hostGetter(urlType, "encodedPath", String::class.java) ?: return null,
                    Lookup.methodOrNull(urlType, "queryParameterValues", String::class.java)
                        ?.takeIf { it.returnType == List::class.java } ?: return null
                )
            }
        }
    }

    private data class Route(val host: String, val path: String, val uid: Long)

    /** 只复制宿主实际读取的字节。非顺序读取、超限或未读到 EOF 时放弃观察。 */
    internal class ObservedStream(input: InputStream, private val onComplete: (ByteArray) -> Unit) :
        FilterInputStream(input) {
        private var buffer: ByteArrayOutputStream? = ByteArrayOutputStream()

        override fun read(): Int {
            val value = super.read()
            if (value < 0) finish() else collect(1) { it.write(value) }
            return value
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            val count = super.read(bytes, offset, length)
            if (count < 0) finish() else if (count > 0) collect(count) { it.write(bytes, offset, count) }
            return count
        }

        private inline fun collect(size: Int, write: (ByteArrayOutputStream) -> Unit) {
            val capture = buffer ?: return
            if (size <= MAX_BODY_BYTES - capture.size()) write(capture) else buffer = null
        }

        private fun finish() {
            val capture = buffer ?: return
            buffer = null
            runCatching { onComplete(capture.toByteArray()) }
        }

        override fun skip(count: Long): Long = super.skip(count).also { if (it > 0) buffer = null }

        override fun reset() {
            super.reset()
            buffer = null
        }

        override fun close() {
            buffer = null
            super.close()
        }
    }

    private companion object {
        const val MAX_POINTS = 12
        const val MAX_ACCOUNTS = 64
        const val MAX_PENDING_BODIES = 16
        const val MAX_BODY_BYTES = 256 * 1024
        val TTL_NANOS = TimeUnit.MINUTES.toNanos(30)
        const val SPACE_HOST = "app.bilibili.com"
        const val SPACE_PATH = "/x/v2/space"
        const val DYNAMIC_HOST = "api.bilibili.com"
        const val DYNAMIC_PATH = "/x/v2/dynamic/dyn/space"

        fun profilePoints(data: JSONObject, card: JSONObject): Int? {
            val hidden = data.optJSONObject("hidden_attribute")
            val settings = data.optJSONObject("setting")
            if (enabled(hidden?.opt("is_space_hidden")) || enabled(settings?.opt("disable_following")) ||
                enabled(settings?.opt("disable_show_fans")) || integer(card.opt("is_deleted")) == 1L
            ) return 0
            val following = integer(card.opt("attention"))
            val fans = integer(card.opt("fans"))
            val likeCounts = listOfNotNull(
                integer(card.optJSONObject("likes")?.opt("like_num")),
                integer(card.optJSONObject("user_like")?.opt("like_num"))
            )
            if (following != null && following != 0L || fans != null && fans !in 0L..5L ||
                likeCounts.any { it !in 0L..20L }
            ) return 0
            if (following == null || fans == null || likeCounts.isEmpty()) return null
            val likes = likeCounts.maxOrNull()
            return when {
                fans == 0L && likes == 0L -> 8
                else -> 6
            }
        }

        fun integer(value: Any?): Long? = when (value) {
            is Int -> value.toLong()
            is Long -> value
            is String -> value.toLongOrNull()
            else -> null
        }

        fun enabled(value: Any?): Boolean = value == true || integer(value) == 1L
    }
}
