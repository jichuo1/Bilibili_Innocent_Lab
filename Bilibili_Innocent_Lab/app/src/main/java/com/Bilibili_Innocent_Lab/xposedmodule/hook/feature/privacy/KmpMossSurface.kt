package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isAbstract
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Version-independent view of the host's KMP gRPC pipeline (`kntr.base.moss`).
 *
 * The host sends a large share of its gRPC traffic through `GrpcEngine` -> a chain of
 * `MossInterceptor`s -> the native ignet engine. None of it touches the Java `MossServiceImp` that
 * the incognito boundary hooks, so it needs its own boundary. Only `GrpcEngine` and `MossInterceptor`
 * keep their names; everything else (`$b` chain, `$e` request, `$f` response, field/method names)
 * is renamed per build, and the response constructor changed shape between 8.96 and 8.97. Every
 * piece is therefore located by structure, and any ambiguity returns [Result.Unresolved] — the
 * caller must then refuse to enable incognito instead of leaving this channel open.
 */
internal class KmpMossSurface(
    val engine: Class<*>,
    val engineList: Field,
    val engineEntry: Method,
    val interceptor: Class<*>,
    val nameMethod: Method,
    val priorityMethod: Method,
    val protocolMethod: Method,
    val interceptMethod: Method,
    val protocolAll: Any,
    val chainGetRequest: Method,
    val chainProceed: Method,
    val requestPayload: Member,
    val requestDescriptor: Member,
    val requestHeaders: Method,
    val headerMapGetters: List<Method>,
    val responseCtor: Constructor<*>,
    val responseHasRequest: Boolean,
    val exceptionCtor: Constructor<*>,
    /** Entry parameter positions of the method descriptor and the engine-level reply handler; -1 = unknown. */
    val entryDescriptorIndex: Int,
    val entryHandlerIndex: Int
) {
    /** A readable property backed by either a public accessor or a field. */
    class Member(private val method: Method?, private val field: Field?) {
        fun get(target: Any): Any? = method?.invoke(target) ?: field?.get(target)
    }

    sealed interface Result {
        /** The host has no KMP moss engine at all: nothing to protect on this build. */
        data object Absent : Result
        data class Unresolved(val reason: String) : Result
        data class Resolved(val surface: KmpMossSurface) : Result
    }

    /** Class names; overridable only so unit tests can point the same rules at fixture packages. */
    data class Names(
        val engine: String = "kntr.base.moss.ignet.impl.grpc.GrpcEngine",
        val interceptor: String = "kntr.base.moss.MossInterceptor",
        val exception: String = "kntr.base.moss.api.KMossException"
    )

    companion object {
        private const val CONTINUATION = "kotlin.coroutines.Continuation"
        private const val SERIALIZATION_STRATEGY = "kotlinx.serialization.SerializationStrategy"
        private const val KCLASS = "kotlin.reflect.KClass"

        fun resolve(loader: ClassLoader, names: Names = Names()): Result {
            val engine = Lookup.classOrNull(loader, names.engine) ?: return Result.Absent
            fun fail(reason: String): Result = Result.Unresolved(reason)

            val interceptor = Lookup.classOrNull(loader, names.interceptor)?.takeIf { it.isInterface }
                ?: return fail("interceptor")
            val continuation = Lookup.classOrNull(loader, CONTINUATION) ?: return fail("continuation")
            val nested = nestedClasses(loader, interceptor)
            fun isNested(type: Class<*>) = type in nested

            // --- engine: the interceptor list lives in the single List field; the call entry is the
            // single public void method that takes the serialized request bytes.
            val engineList = Lookup.declaredFields(engine, makeAccessible = true) {
                !it.isStatic && it.type == List::class.java
            }.singleOrNull() ?: return fail("engine-list")
            val engineEntry = Lookup.declaredMethods(engine, makeAccessible = true) {
                Modifier.isPublic(it.modifiers) && !it.isStatic && !it.isSynthetic && !it.isBridge &&
                    it.returnType == Void.TYPE && ByteArray::class.java in it.parameterTypes
            }.singleOrNull() ?: return fail("engine-entry")

            // --- MossInterceptor: name/priority/protocol accessors and the suspend intercept method.
            val methods = Lookup.declaredMethods(interceptor).filter { !it.isStatic && !it.isSynthetic }
            val nameMethod = methods.filter { it.parameterCount == 0 && it.returnType == String::class.java }
                .singleOrNull() ?: return fail("name")
            val priorityMethod = methods.filter { it.parameterCount == 0 && it.returnType == Int::class.javaPrimitiveType }
                .singleOrNull() ?: return fail("priority")
            val protocolMethod = methods.filter { it.parameterCount == 0 && isNested(it.returnType) && it.returnType.isEnum }
                .singleOrNull() ?: return fail("protocol")
            val interceptMethod = methods.filter {
                it.isAbstract && it.parameterCount == 2 && it.returnType == Any::class.java &&
                    it.parameterTypes[0].isInterface && isNested(it.parameterTypes[0]) &&
                    continuation.isAssignableFrom(it.parameterTypes[1])
            }.singleOrNull() ?: return fail("intercept")
            val protocolAll = protocolMethod.returnType.enumConstants
                ?.firstOrNull { (it as Enum<*>).name == "ALL" } ?: return fail("protocol-all")

            // --- chain: getRequest() and proceed(request, continuation).
            val chain = interceptMethod.parameterTypes[0]
            val chainMethods = Lookup.declaredMethods(chain, makeAccessible = true).filter { it.isAbstract }
            val chainGetRequest = chainMethods.filter {
                it.parameterCount == 0 && isNested(it.returnType) && !it.returnType.isInterface
            }.singleOrNull() ?: return fail("chain-request")
            val request = chainGetRequest.returnType
            val chainProceed = chainMethods.filter {
                it.parameterCount == 2 && it.parameterTypes[0] == request &&
                    continuation.isAssignableFrom(it.parameterTypes[1]) && it.returnType == Any::class.java
            }.singleOrNull() ?: return fail("chain-proceed")

            // --- response: Response(Container, byte[], Exception) on 8.97+, Response(Request, Container,
            // byte[], Exception) on 8.84-8.96. The container is the nested abstract class that owns two
            // abstract no-arg Map accessors (ascii and binary headers).
            data class Candidate(val ctor: Constructor<*>, val container: Class<*>, val hasRequest: Boolean)
            val candidates = nested.filter { !it.isInterface && !Modifier.isAbstract(it.modifiers) && !it.isEnum }
                .flatMap { type ->
                    Lookup.declaredConstructors(type) { Modifier.isPublic(it.modifiers) }.mapNotNull { ctor ->
                        val p = ctor.parameterTypes
                        val tail = p.size >= 3 && p[p.size - 2] == ByteArray::class.java &&
                            p[p.size - 1] == Exception::class.java
                        val container = if (tail) p[p.size - 3] else return@mapNotNull null
                        val hasRequest = p.size == 4 && p[0] == request
                        if ((p.size == 3 || hasRequest) && isNested(container) && !container.isInterface)
                            Candidate(ctor, container, hasRequest) else null
                    }
                }
            val response = candidates.singleOrNull() ?: return fail("response(${candidates.size})")
            val headerMapGetters = Lookup.declaredMethods(response.container, makeAccessible = true).filter {
                it.isAbstract && it.parameterCount == 0 && it.returnType == Map::class.java
            }
            if (headerMapGetters.size != 2) return fail("container-maps(${headerMapGetters.size})")

            // --- request: headers container, payload bytes, method descriptor.
            val requestMethods = Lookup.methods(request, includeSuperclasses = true, makeAccessible = true) {
                !it.isStatic && !it.isSynthetic && it.parameterCount == 0
            }.distinctBy { it.name }
            val requestFields = Lookup.fields(request, includeSuperclasses = true, makeAccessible = true) { !it.isStatic }
            val requestHeaders = requestMethods.filter {
                it.isAbstract && response.container.isAssignableFrom(it.returnType)
            }.singleOrNull() ?: return fail("request-headers")
            val payload = member(requestMethods, requestFields) { it == ByteArray::class.java }
                ?: return fail("request-payload")
            val descriptor = member(requestMethods, requestFields) { looksLikeDescriptor(it) }
                ?: return fail("request-descriptor")

            val exceptionType = Lookup.classOrNull(loader, names.exception) ?: return fail("exception")
            val exceptionCtor = Lookup.constructorOrNull(exceptionType, String::class.java, Throwable::class.java)
                ?: return fail("exception-ctor")

            // Reply observation is optional (it only feeds the local library), so an unknown position
            // never fails the resolution: the isolation boundary does not depend on it.
            val entryParams = engineEntry.parameterTypes
            val descriptorIndex = entryParams.indices.filter { looksLikeDescriptor(entryParams[it]) }.singleOrNull() ?: -1
            val handlerIndex = entryParams.indices.filter { index ->
                entryParams[index].isInterface && Lookup.declaredMethods(entryParams[index]).any {
                    it.name == "onNext" && it.parameterTypes.contentEquals(arrayOf(ByteArray::class.java))
                }
            }.singleOrNull() ?: -1

            return Result.Resolved(KmpMossSurface(
                engine, engineList, engineEntry, interceptor, nameMethod, priorityMethod, protocolMethod,
                interceptMethod, protocolAll, chainGetRequest, chainProceed, payload, descriptor, requestHeaders,
                headerMapGetters, response.ctor, response.hasRequest, exceptionCtor, descriptorIndex, handlerIndex))
        }

        /** Nested types, from the InnerClasses attribute or — if R8 stripped it — by probing `$a`..`$z`. */
        private fun nestedClasses(loader: ClassLoader, owner: Class<*>): Set<Class<*>> {
            val found = LinkedHashSet<Class<*>>(owner.declaredClasses.toList())
            for (c in 'a'..'z') Lookup.classOrNull(loader, "${owner.name}$$c")?.let(found::add)
            for (name in listOf("Protocol", "LogLevel")) Lookup.classOrNull(loader, "${owner.name}$$name")?.let(found::add)
            return found
        }

        /** Prefer a public accessor, then a field; require exactly one distinct member of the wanted type. */
        private fun member(methods: List<Method>, fields: List<Field>, wanted: (Class<*>) -> Boolean): Member? {
            val byMethod = methods.filter { Modifier.isPublic(it.modifiers) && !it.isAbstract && wanted(it.returnType) }
            if (byMethod.size == 1) return Member(byMethod[0], null)
            if (byMethod.isNotEmpty()) return null
            val byField = fields.filter { wanted(it.type) }
            return byField.singleOrNull()?.let { Member(null, it) }
        }

        /** The method-descriptor data class owns both a KClass and a SerializationStrategy field. */
        private fun looksLikeDescriptor(type: Class<*>): Boolean {
            if (type.isPrimitive || type.isArray || type.name.startsWith("java.") || type.name.startsWith("kotlin.")) return false
            val fields = Lookup.declaredFields(type).map { it.type.name }
            return KCLASS in fields && SERIALIZATION_STRATEGY in fields
        }
    }
}
