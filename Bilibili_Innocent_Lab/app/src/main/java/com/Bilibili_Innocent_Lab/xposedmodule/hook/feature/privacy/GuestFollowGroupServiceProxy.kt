package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/** Wraps the host's lazy Retrofit service and tags every returned call before it can be enqueued. */
internal class GuestFollowGroupServiceProxy<T : Any>(private val bindings: GuestFollowGroupBindings<T>) {
    fun wrap(serviceType: Class<*>, delegate: Any, callType: Class<*>, marker: String): Any {
        require(serviceType.isInterface && serviceType.isInstance(delegate)) { "Invalid host service" }
        return Proxy.newProxyInstance(serviceType.classLoader, arrayOf(serviceType)) { proxy, method, arguments ->
            when {
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
    }
}
