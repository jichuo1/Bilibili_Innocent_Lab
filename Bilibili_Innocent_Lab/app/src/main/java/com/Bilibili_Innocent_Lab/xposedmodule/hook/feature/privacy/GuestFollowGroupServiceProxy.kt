package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/** Wraps the host's lazy Retrofit service and tags every returned call before it can be enqueued. */
internal class GuestFollowGroupServiceProxy<T : Any>(private val bindings: GuestFollowGroupBindings<T>) {
    private class Handler<T : Any>(
        val bindings: GuestFollowGroupBindings<T>,
        val marker: String,
        val delegate: Any,
        val callType: Class<*>
    ) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, arguments: Array<out Any?>?): Any? = when {
            method.declaringClass == Any::class.java -> when (method.name) {
                "equals" -> proxy === arguments?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "LocalFollowGroupService"
                else -> throw UnsupportedOperationException(method.name)
            }
            else -> {
                // Unknown service shapes fail before invoking the host delegate.
                check(callType.isAssignableFrom(method.returnType)) { "Unexpected host service return type" }
                val call = try {
                    method.invoke(delegate, *(arguments ?: emptyArray()))
                } catch (error: InvocationTargetException) {
                    throw error.targetException
                }
                check(callType.isInstance(call)) { "Missing host call" }
                bindings.tag(call, marker)
                call
            }
        }
    }

    fun wraps(service: Any?, marker: String): Boolean {
        if (service == null || !Proxy.isProxyClass(service.javaClass)) return false
        val handler = Proxy.getInvocationHandler(service)
        return handler is Handler<*> && handler.bindings === bindings && handler.marker == marker
    }

    fun wrap(serviceType: Class<*>, delegate: Any, callType: Class<*>, marker: String): Any {
        require(serviceType.isInterface && serviceType.isInstance(delegate)) { "Invalid host service" }
        if (wraps(delegate, marker)) return delegate
        return Proxy.newProxyInstance(serviceType.classLoader, arrayOf(serviceType), Handler(bindings, marker, delegate, callType))
    }
}
