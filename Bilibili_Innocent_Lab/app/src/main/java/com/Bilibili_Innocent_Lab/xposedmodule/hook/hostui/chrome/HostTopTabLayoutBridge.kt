package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/** Delegates the host's complete tab measure/placement tree; only its outer alignment changes. */
internal class HostTopTabLayoutBridge(
    private val alignmentType: Class<*>,
    private val functionType: Class<*>,
    private val scopeType: Class<*>,
    private val subcompose: Method,
    private val align: Method,
    private val invoke: Method,
    private val center: (count: Int, contentWidth: Int, viewportWidth: Int) -> Int?,
    private val onApplied: () -> Unit,
    private val onError: (Throwable) -> Unit,
    private val onTabsMeasured: (Int) -> Unit = {}
) {
    private var count = 0

    fun alignment(original: Any): Any = Proxy.newProxyInstance(alignmentType.classLoader, arrayOf(alignmentType)) { proxy, method, args ->
        objectMethod(proxy, method, args)?.let { return@newProxyInstance it }
        // Native exceptions retain their original identity and are not retried.
        val native = call(method, original, args)
        if (method.name != align.name || method.parameterTypes.toList() != align.parameterTypes.toList()) return@newProxyInstance native
        try {
            val child = args?.getOrNull(0) as? Long ?: return@newProxyInstance native
            val space = args.getOrNull(1) as? Long ?: return@newProxyInstance native
            val x = center(count, (child shr 32).toInt(), (space shr 32).toInt()) ?: return@newProxyInstance native
            val old = native as? Long ?: return@newProxyInstance native
            if ((old shr 32).toInt() != x) runCatching(onApplied).onFailure(::report)
            (x.toLong() shl 32) or (old and 0xFFFFFFFFL)
        } catch (failure: Throwable) {
            report(failure)
            native
        }
    }

    fun measure(original: Any): Any = Proxy.newProxyInstance(functionType.classLoader, arrayOf(functionType)) { proxy, method, args ->
        objectMethod(proxy, method, args)?.let { return@newProxyInstance it }
        if (method.name != invoke.name || args?.size != 2 || !scopeType.isInstance(args[0])) return@newProxyInstance call(method, original, args)
        count = 0
        val scope = args[0]!!
        val observer = try { Proxy.newProxyInstance(scopeType.classLoader, arrayOf(scopeType)) scopeCall@ { scopeProxy, operation, values ->
            objectMethod(scopeProxy, operation, values)?.let { return@scopeCall it }
            val result = call(operation, scope, values)
            try { if (operation.name == subcompose.name && operation.parameterTypes.toList() == subcompose.parameterTypes.toList()) {
                // The enum name is native semantic data; its owner/field may be renamed by R8.
                // Unknown or unboxed slots decline centering rather than guessing a slot index.
                if ((values?.getOrNull(0) as? Enum<*>)?.name == "Tabs") count = (result as? List<*>)?.size ?: 0
            } } catch (failure: Throwable) { count = 0; report(failure) }
            result
        } } catch (failure: Throwable) { report(failure); scope }
        val result = call(method, original, arrayOf(observer, args[1]))
        try { onTabsMeasured(count) } catch (failure: Throwable) { report(failure) }
        result
    }

    private fun report(failure: Throwable) { runCatching { onError(failure) } }

    private fun objectMethod(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when {
        method.declaringClass != Any::class.java -> null
        method.name == "equals" -> proxy === args?.getOrNull(0)
        method.name == "hashCode" -> System.identityHashCode(proxy)
        method.name == "toString" -> "BIL native tab layout delegate"
        else -> null
    }

    private fun call(method: Method, receiver: Any, args: Array<out Any?>?): Any? = try {
        method.invoke(receiver, *(args ?: emptyArray()))
    } catch (failure: InvocationTargetException) {
        throw failure.targetException
    }
}

/** Only a change in the need to fill the viewport invalidates its native composition. */
internal class HostTopTabWidthState(private val changed: () -> Unit) {
    var fill = false
        private set
    fun measured(count: Int) {
        val next = count in 1..2
        if (next == fill) return
        val previous = fill
        fill = next
        try { changed() } catch (failure: Throwable) { fill = previous; throw failure }
    }
}
