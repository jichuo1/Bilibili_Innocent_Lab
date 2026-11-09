package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.IdentityHashMap

/**
 * The interceptor injected at the end of the host's KMP gRPC chain.
 *
 * It sees every request after the host's common-header interceptor has attached identity, so it is
 * the one place where both local answers and the identity scrub are complete. Order of decisions:
 *  1. a guest/incognito session answers locally-served RPCs (history) from the local library;
 *  2. a guest-only (not incognito) session otherwise leaves the request untouched;
 *  3. an incognito session blocks anything its policy forbids and seals identity on the rest.
 * Any failure produces an error response; the original request is never sent unsealed.
 */
internal class KmpMossInterceptor(
    private val surface: KmpMossSurface,
    loader: ClassLoader,
    private val session: IncognitoSession?,
    private val isolationReady: () -> Boolean,
    private val sanitizer: KmpHeaderSanitizer?,
    private val guest: () -> Boolean,
    private val localReply: () -> ((String, ByteArray) -> ByteArray?)?,
    private val events: Events
) {
    interface Events {
        fun replied(local: Boolean, rpc: String?)
        fun sanitized(names: Set<String>)
        /** An incognito call that was sealed and sent on; [rpc] is the method name only. */
        fun passed(rpc: String)
        fun failed(reason: Throwable)
    }

    private sealed interface Decision {
        data object Proceed : Decision
        class Reply(val bytes: ByteArray) : Decision
        class Fail(val message: String) : Decision
    }

    private val descriptorNames = IdentityHashMap<Any, String?>()

    /** Implements the host's MossInterceptor interface; the host sorts it last (priority = MAX). */
    val proxy: Any = Proxy.newProxyInstance(loader, arrayOf(surface.interceptor)) { self, method, args ->
        when {
            method == surface.interceptMethod -> intercept(requireNotNull(args[0]), args[1])
            method == surface.nameMethod -> NAME
            method == surface.priorityMethod -> Int.MAX_VALUE
            method == surface.protocolMethod -> surface.protocolAll
            method.declaringClass == Any::class.java -> when (method.name) {
                "hashCode" -> System.identityHashCode(self)
                "equals" -> self === args?.getOrNull(0)
                else -> "BIL KMP moss boundary"
            }
            else -> throw UnsupportedOperationException(method.name)
        }
    }

    private fun intercept(chain: Any, continuation: Any?): Any? {
        val request = requireNotNull(surface.chainGetRequest.invoke(chain))
        val decision = try {
            decide(request)
        } catch (e: Throwable) {
            // Whatever went wrong, the request must not be sent with an identity we failed to seal.
            events.failed(e)
            Decision.Fail("KMP request isolation failed")
        }
        return try {
            when (decision) {
                Decision.Proceed -> surface.chainProceed.invoke(chain, request, continuation)
                is Decision.Reply -> response(request, decision.bytes, null)
                is Decision.Fail -> response(request, null, failure(decision.message))
            }
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun decide(request: Any): Decision {
        val payload = surface.requestPayload.get(request) as? ByteArray ?: return Decision.Fail("payload")
        val descriptor = surface.requestDescriptor.get(request) ?: return Decision.Fail("descriptor")
        val rpc = fullMethodName(descriptor)
        if (rpc != null && guest()) {
            val reader = localReply()
            if (reader != null) {
                val local = try {
                    reader(rpc, payload)
                } catch (e: Throwable) {
                    events.failed(e)
                    return Decision.Fail("Local library unavailable")
                }
                if (local != null) {
                    events.replied(true, rpc)
                    return Decision.Reply(local)
                }
            }
        }
        val active = session ?: return Decision.Proceed
        val identity = active.identity()
        if (identity == null || !isolationReady() || rpc == null || PrivacyNetworkPolicy.blockRpc(rpc)) {
            events.replied(false, rpc)
            return Decision.Reply(EMPTY)
        }
        val names = (sanitizer ?: return Decision.Fail("sanitizer")).sanitize(headerMaps(request), identity)
        events.sanitized(names)
        events.passed(rpc)
        return Decision.Proceed
    }

    @Suppress("UNCHECKED_CAST")
    private fun headerMaps(request: Any): List<MutableMap<Any?, Any?>> {
        val container = requireNotNull(surface.requestHeaders.invoke(request))
        return surface.headerMapGetters.map { it.invoke(container) as MutableMap<Any?, Any?> }
    }

    /** `bilibili.app.interface.v1.History/CursorV2`, the same shape as the Java MethodDescriptor name. */
    internal fun fullMethodName(descriptor: Any): String? = synchronized(descriptorNames) {
        if (descriptorNames.containsKey(descriptor)) return descriptorNames[descriptor]
        // The descriptor is a data class; its toString carries the three names as literals.
        val match = DESCRIPTOR.find(descriptor.toString())
        val name = match?.let { "${it.groupValues[1]}.${it.groupValues[2]}/${it.groupValues[3]}" }
        if (descriptorNames.size >= MAX_CACHED_DESCRIPTORS) descriptorNames.clear()
        descriptorNames[descriptor] = name
        name
    }

    private fun response(request: Any, payload: ByteArray?, error: Exception?): Any {
        val container = freshContainer(request)
        return if (surface.responseHasRequest) surface.responseCtor.newInstance(request, container, payload, error)
        else surface.responseCtor.newInstance(container, payload, error)
    }

    /** A new empty header container of the request's concrete type; the request's own as a last resort. */
    private fun freshContainer(request: Any): Any {
        val current = requireNotNull(surface.requestHeaders.invoke(request))
        return Lookup.constructorOrNull(current.javaClass)?.newInstance() ?: current
    }

    private fun failure(message: String): Exception =
        surface.exceptionCtor.newInstance(message, java.io.IOException(message)) as Exception

    companion object {
        const val NAME = "bil-incognito-boundary"
        private val EMPTY = ByteArray(0)
        private const val MAX_CACHED_DESCRIPTORS = 4096
        private val DESCRIPTOR = Regex("""packageName=([^,]+), serviceName=([^,]+), methodName=([^,]+),""")
    }
}
