package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Moss 异步响应回调的只读旁路。
 *
 * 宿主的 `MossResponseHandler` 实现全是匿名内部类，类名每版都漂，没法直接 Hook；而异步
 * 分片响应又只在回调里出现一次。这里用 [Proxy] 在**宿主 ClassLoader** 上包一层：`onNext`
 * 先交给观察者，再原样转发给宿主自己的回调，其余方法（含 `onError`/`onCompleted`/
 * `onNextForAck` 这类带返回值的默认方法与 Object 方法）全部透明转发。
 *
 * 边界纪律：
 * - 观察者抛异常绝不影响宿主收包——异常在这里被吞掉，由调用方自行上报诊断。
 * - 不缓存 reply、不缓存宿主回调实例，代理对象生命周期与单次请求一致。
 * - 只有在 `handlerClass` 真的是接口、且宿主回调确实是它的实例时才创建代理，否则返回
 *   null，让调用方走"没装上"的分支，而不是塞一个语义不符的对象给宿主。
 */
internal object MossResponseHandlerProxy {

    private const val ON_NEXT = "onNext"

    fun wrap(
        handlerClass: Class<*>,
        delegate: Any,
        onNext: (Any) -> Unit
    ): Any? {
        if (!handlerClass.isInterface || !handlerClass.isInstance(delegate)) return null
        val loader = handlerClass.classLoader ?: return null
        return runCatching {
            Proxy.newProxyInstance(
                loader,
                arrayOf(handlerClass),
                ForwardingHandler(delegate, onNext)
            )
        }.getOrNull()
    }

    private class ForwardingHandler(
        private val delegate: Any,
        private val onNext: (Any) -> Unit
    ) : InvocationHandler {

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            val arguments = args ?: EMPTY_ARGS
            if (method.name == ON_NEXT && arguments.size == 1) {
                arguments[0]?.let { reply -> runCatching { onNext(reply) } }
            }
            return try {
                method.invoke(delegate, *arguments)
            } catch (invocation: InvocationTargetException) {
                // 宿主回调自己抛出的异常必须原样上抛，否则调用方会误判为"处理成功"。
                throw invocation.targetException ?: invocation
            }
        }

        private companion object {
            val EMPTY_ARGS = emptyArray<Any?>()
        }
    }
}
