@file:Suppress("unused", "ClassName")

package kmpfixture.modern

import kotlinx.serialization.SerializationStrategy
import kotlin.coroutines.Continuation
import kotlin.reflect.KClass

/** Stand-in for kntr.base.moss.MossInterceptor on 8.97+ (9.16.1 shape): 3-arg Response, public fields. */
interface MossInterceptor {
    enum class Protocol { GRPC, REST, ALL }

    interface Chain {
        val request: Request
        fun a(request: Request, c: Continuation<Any?>): Any?
    }

    abstract class Container {
        abstract fun a(): Map<String, String>
        abstract fun b(): Map<String, ByteArray>
    }

    abstract class Request(
        @JvmField val service: Service,
        @JvmField val descriptor: Descriptor,
        @JvmField val payload: ByteArray
    ) {
        abstract fun a(): RequestImpl
        abstract fun b(): ContainerImpl
    }

    class Response(val headers: Container, val payload: ByteArray?, val error: Exception?)

    fun a(): Protocol
    fun b(chain: Chain, c: Continuation<Any?>): Any?
    val name: String
    val priority: Int
}

class Service(val host: String)

open class Descriptor(
    @JvmField val packageName: String,
    @JvmField val serviceName: String,
    @JvmField val methodName: String,
    @JvmField val reqClass: KClass<*>,
    @JvmField val reqSerialization: SerializationStrategy<*>
) {
    override fun toString() = "KMethodDescriptor(packageName=$packageName, serviceName=$serviceName, " +
        "methodName=$methodName, reqClass=$reqClass, reqSerialization=$reqSerialization)"
}

class ContainerImpl : MossInterceptor.Container() {
    val ascii = LinkedHashMap<String, String>()
    val binary = LinkedHashMap<String, ByteArray>()
    override fun a(): Map<String, String> = ascii
    override fun b(): Map<String, ByteArray> = binary
}

class RequestImpl(service: Service, descriptor: Descriptor, payload: ByteArray) :
    MossInterceptor.Request(service, descriptor, payload) {
    val headers = ContainerImpl()
    override fun a(): RequestImpl = this
    override fun b(): ContainerImpl = headers
}

/** Stand-in for the engine-level reply handler (id1.d): serialized bytes in, completion and errors out. */
interface Handler {
    fun onNext(bytes: ByteArray?)
    fun onCompleted()
    fun onError(error: KMossException)
}

class GrpcEngine(@JvmField val interceptors: List<MossInterceptor>) {
    fun a(service: Service, descriptor: Descriptor, payload: ByteArray, handler: Handler?, rest: Any?) {}
}

class KMossException(message: String, cause: Throwable) : Exception(message, cause)
