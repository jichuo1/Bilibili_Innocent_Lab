package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/** 单次请求内合并同 RPC 的 Kotlin 变换，顺序与原来逐层包装完全一致；不做进程级响应缓存。 */
internal object KotlinMossResponseHandlerProxy {
    class ConfirmedReply(val message: Any, val onReplyTransformed: () -> Unit)
    class Key(private val endpoint: Method, private val replyClass: Class<*>,
        private val protoBuf: Any, private val serializer: Any) {
        fun matches(other: Key): Boolean = endpoint == other.endpoint && replyClass === other.replyClass &&
            protoBuf === other.protoBuf && serializer === other.serializer
    }
    private class Step(val bridge: KotlinMossReplyBridge, val transform: (Any) -> Any,
        val failed: (Throwable) -> Unit, val confirmedTransform: ((Any) -> ConfirmedReply)?)

    fun wrap(handlerClass: Class<*>, delegate: Any, key: Key, bridge: KotlinMossReplyBridge,
        failed: (Throwable) -> Unit, transform: (Any) -> Any,
        confirmedTransform: ((Any) -> ConfirmedReply)? = null): Any? {
        if (!handlerClass.isInterface || !handlerClass.isInstance(delegate)) return null
        val loader = handlerClass.classLoader ?: return null
        val previous = if (Proxy.isProxyClass(delegate.javaClass))
            runCatching { Proxy.getInvocationHandler(delegate) as? Handler }.getOrNull() else null
        val same = previous?.takeIf { it.key.matches(key) }
        val step = Step(bridge, transform, failed, confirmedTransform)
        val steps = if (same == null) listOf(step) else listOf(step) + same.steps
        return runCatching {
            Proxy.newProxyInstance(loader, arrayOf(handlerClass), Handler(same?.delegate ?: delegate, key, steps))
        }.getOrNull()
    }

    private class Handler(val delegate: Any, val key: Key, val steps: List<Step>) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            var arguments = args ?: EMPTY_ARGS
            var receipts: MutableList<() -> Unit>? = null
            if (method.name == "onNext" && arguments.size == 1) {
                arguments[0]?.let { original ->
                    var current = original
                    var mirror: Any? = null
                    for (step in steps) {
                        try {
                            val source = mirror ?: step.bridge.read(current).also { mirror = it }
                            val confirmed = step.confirmedTransform?.invoke(source)
                            val updated = confirmed?.message ?: step.transform(source)
                            if (updated !== source) {
                                // 变换后的 K 对象可能由宿主 codec 归一化；下一阶段重新解析，保持原语义。
                                val rewritten = step.bridge.write(updated)
                                val readback = if (confirmed != null) step.bridge.read(rewritten).also {
                                    check(it == updated) { "Kotlin reply readback changed the Java message" }
                                } else null
                                current = rewritten
                                mirror = readback
                                if (confirmed != null) {
                                    val pending = receipts ?: ArrayList<() -> Unit>(2).also { receipts = it }
                                    pending += confirmed.onReplyTransformed
                                }
                            }
                        } catch (failure: Throwable) {
                            // 此阶段失败仍将此前成功的响应交给下一阶段，不影响其他防线或宿主回调。
                            HostThreadGuard.run("kotlin_moss.handler.failed") { step.failed(failure) }
                        }
                    }
                    if (current !== original) arguments = arrayOf(current) else receipts = null
                }
            }
            val returned = try {
                method.invoke(delegate, *arguments)
            } catch (invocation: InvocationTargetException) {
                throw invocation.targetException ?: invocation
            }
            receipts?.forEach { receipt -> HostThreadGuard.run("kotlin_moss.handler.receipt") { receipt() } }
            return returned
        }
    }
    private val EMPTY_ARGS = emptyArray<Any?>()
}
