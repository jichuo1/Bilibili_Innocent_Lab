@file:Suppress("unused", "ClassName")

package kmpfixture.legacy

import kotlinx.serialization.SerializationStrategy
import kotlin.coroutines.Continuation
import kotlin.reflect.KClass

/** Stand-in for kntr.base.moss.MossInterceptor on 8.84-8.96: 4-arg Response, accessor methods, abstract protocol(). */
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

    abstract class Request(private val service: Service, private val descriptor: Descriptor, private val payload: ByteArray) {
        abstract fun a(): RequestImpl
        abstract fun d(): Container
        fun c(): ByteArray = payload
        fun e(): Descriptor = descriptor
        fun f(): Service = service
    }

    class Response(val request: Request, val headers: Container, val payload: ByteArray?, val error: Exception?)

    fun a(): Protocol
    fun b(chain: Chain, c: Continuation<Any?>): Any?
    val name: String
    val priority: Int
}

class Service(val host: String)

class Descriptor(
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
    override fun d(): MossInterceptor.Container = headers
}

class GrpcEngine(@JvmField val interceptors: List<MossInterceptor>) {
    fun a(service: Service, descriptor: Descriptor, payload: ByteArray, handler: Any?, rest: Any?) {}
}

class KMossException(message: String, cause: Throwable) : Exception(message, cause)
