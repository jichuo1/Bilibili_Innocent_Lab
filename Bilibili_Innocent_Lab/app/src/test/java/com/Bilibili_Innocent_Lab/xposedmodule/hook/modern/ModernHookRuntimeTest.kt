package com.Bilibili_Innocent_Lab.xposedmodule.hook.modern

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class ModernHookRuntimeTest {
    private val method = Fixture::class.java.getDeclaredMethod("sum", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)

    @Test
    fun `before edits a private argument copy and after transforms the downstream result`() {
        val incoming = arrayOf<Any?>(1, 2)
        val chain = TestChain(method, Fixture(), incoming) { _, args -> (args[0] as Int) + (args[1] as Int) }
        val creator = ModernMemberHookCreator(method).apply {
            before { args[0] = 5; setObjectExtra("value", 2) }
            after { result = (result as Int) * (getObjectExtra("value") as Int) }
        }
        assertEquals(14, creator.invoke(chain))
        assertArrayEquals(arrayOf<Any?>(1, 2), incoming)
        assertEquals(1, chain.calls)
    }

    @Test
    fun `early null result still skips original and reaches after`() {
        val chain = TestChain(method)
        val creator = ModernMemberHookCreator(method).apply {
            before { result = null }
            after { assertNull(result); result = 7 }
        }
        assertEquals(7, creator.invoke(chain))
        assertEquals(0, chain.calls)
    }

    @Test
    fun `original exception identity survives unless after explicitly handles it`() {
        val failure = IllegalStateException("host")
        val chain = TestChain(method) { _, _ -> throw failure }
        val creator = ModernMemberHookCreator(method).apply { after { assertSame(failure, throwable) } }
        try {
            creator.invoke(chain)
            fail("host failure must propagate")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
        assertEquals(1, chain.calls)
        val recovered = ModernMemberHookCreator(method).apply { after { result = 12 } }
        assertEquals(12, recovered.invoke(TestChain(method) { _, _ -> throw failure }))
    }

    @Test
    fun `before failure skips original and after can inspect it`() {
        val failure = IllegalArgumentException("requested failure")
        val chain = TestChain(method)
        val creator = ModernMemberHookCreator(method).apply {
            before { throwable = failure }
            after { assertSame(failure, throwable); result = 9 }
        }
        assertEquals(9, creator.invoke(chain))
        assertEquals(0, chain.calls)
    }

    @Test
    fun `hook callback exceptions reach the framework without retrying original`() {
        val failure = IllegalStateException("hook failure")
        val chain = TestChain(method)
        val creator = ModernMemberHookCreator(method).apply { after { throw failure } }
        try {
            creator.invoke(chain)
            fail("protective recovery belongs to the framework")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
        assertEquals(1, chain.calls)
    }

    @Test
    fun `replacement and default intercept do not call the original`() {
        val chain = TestChain(method)
        assertEquals(0, ModernMemberHookCreator(method).apply { intercept() }.invoke(chain))
        assertEquals(42, ModernMemberHookCreator(method).apply { replaceTo(42) }.invoke(chain))
        assertEquals(0, chain.calls)
    }

    @Test
    fun `constructor callbacks see the same receiver before and after downstream`() {
        val constructor = Fixture::class.java.getDeclaredConstructor()
        val instance = Fixture()
        val chain = TestChain(constructor, instance) { receiver, _ ->
            assertSame(instance, receiver)
            instance.value = 5
            null
        }
        val creator = ModernMemberHookCreator(constructor).apply {
            before { assertSame(instance, thisObject); assertEquals(0, instance.value) }
            after { assertSame(instance, thisObject); assertEquals(5, instance.value) }
        }
        assertNull(creator.invoke(chain))
        assertEquals(1, chain.calls)
    }

    @Test
    fun `invocation extras cannot leak to the next invocation`() {
        var sequence = 0
        val creator = ModernMemberHookCreator(method).apply {
            before {
                assertNull(getObjectExtra("sequence"))
                setObjectExtra("sequence", ++sequence)
            }
            after { result = getObjectExtra("sequence") }
        }
        assertEquals(1, creator.invoke(TestChain(method)))
        assertEquals(2, creator.invoke(TestChain(method)))
    }

    @Test
    fun `registration preserves the id and requests protective mode on the public API`() {
        var id: String? = null
        var mode: XposedInterface.ExceptionMode? = null
        var registered: XposedInterface.Hooker? = null
        val handle = proxy<XposedInterface.HookHandle> { _, _ -> null }
        lateinit var builder: XposedInterface.HookBuilder
        builder = proxy { name, args -> when (name) {
            "setId" -> builder.also { id = args[0] as String }
            "setExceptionMode" -> builder.also { mode = args[0] as XposedInterface.ExceptionMode }
            "intercept" -> handle.also { registered = args[0] as XposedInterface.Hooker }
            else -> error(name)
        } }
        val api = proxy<XposedInterface> { name, args ->
            if (name == "getApiVersion") 102 else {
                assertEquals("hook", name)
                assertSame(method, args[0])
                builder
            }
        }
        val runtime = ModernHookRuntime(api)
        assertSame(handle, runtime.install("feature:point", method) { replaceTo(1) })
        assertEquals("feature:point", id)
        assertEquals(XposedInterface.ExceptionMode.PROTECTIVE, mode)
        assertEquals(1, registered!!.intercept(TestChain(method)))
        runtime.install("feature:point", method) { replaceTo(2) }
        assertEquals("feature:point", id)
        assertEquals(2, registered!!.intercept(TestChain(method)))
    }

    private inline fun <reified T> proxy(crossinline invoke: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, member, args ->
            invoke(member.name, args ?: emptyArray())
        } as T

    @Test
    fun `API 101 never calls setId and repeated logical points replace rather than stack`() {
        val backend = Api101Backend()
        val runtime = ModernHookRuntime(backend.api)
        val first = runtime.install("same", method) { replaceTo(1) }
        val second = runtime.install("same", method) { replaceTo(2) }
        assertSame(first, second)
        assertEquals(1, backend.callbacks.size)
        assertEquals(2, backend.callbacks.single().intercept(TestChain(method)))
        assertEquals(XposedInterface.ExceptionMode.PROTECTIVE, backend.mode)
    }

    @Test
    fun `API 101 replacement keeps the current invocation on its original callback`() {
        val backend = Api101Backend()
        val runtime = ModernHookRuntime(backend.api)
        runtime.install("same", method) { after { result = (result as Int) + 10 } }
        val original = TestChain(method) { _, _ ->
            runtime.install("same", method) { replaceTo(99) }
            3
        }
        assertEquals(13, backend.callbacks.single().intercept(original))
        assertEquals(99, backend.callbacks.single().intercept(TestChain(method)))
        assertEquals(1, backend.callbacks.size)
    }

    @Test
    fun `API 101 separates different logical ids and executables`() {
        val backend = Api101Backend()
        val runtime = ModernHookRuntime(backend.api)
        runtime.install("first", method) { replaceTo(1) }
        runtime.install("second", method) { replaceTo(2) }
        runtime.install("first", Fixture::class.java.getDeclaredConstructor()) { intercept() }
        assertEquals(3, backend.callbacks.size)
    }

    @Test
    fun `API 101 installation failure leaves the logical point retryable`() {
        val backend = Api101Backend().apply { failInstall = true }
        val runtime = ModernHookRuntime(backend.api)
        try {
            runtime.install("same", method) { replaceTo(1) }
            fail("expected registration failure")
        } catch (_: IllegalStateException) {
        }
        backend.failInstall = false
        runtime.install("same", method) { replaceTo(2) }
        assertEquals(1, backend.callbacks.size)
        assertEquals(2, backend.callbacks.single().intercept(TestChain(method)))
    }

    @Test
    fun `unsupported API does not install a legacy fallback`() {
        val api = proxy<XposedInterface> { name, _ ->
            check(name == "getApiVersion") { "unsupported API attempted a hook" }
            100
        }
        try {
            ModernHookRuntime(api).install("unsupported", method) { intercept() }
            fail("API 100 must remain unsupported")
        } catch (expected: IllegalStateException) {
            assertEquals("Modern API 101 or newer is required", expected.message)
        }
    }

    private inner class Api101Backend {
        val callbacks = mutableListOf<XposedInterface.Hooker>()
        var mode: XposedInterface.ExceptionMode? = null
        var failInstall = false
        private val handle = proxy<XposedInterface.HookHandle> { _, _ -> null }
        val api: XposedInterface = proxy { name, _ -> when (name) {
            "getApiVersion" -> 101
            "hook" -> newBuilder()
            else -> error(name)
        } }

        private fun newBuilder(): XposedInterface.HookBuilder {
            lateinit var builder: XposedInterface.HookBuilder
            builder = proxy { name, args -> when (name) {
                "setExceptionMode" -> builder.also { mode = args[0] as XposedInterface.ExceptionMode }
                "intercept" -> {
                    check(!failInstall) { "simulated install failure" }
                    callbacks += args[0] as XposedInterface.Hooker
                    handle
                }
                // A 101 framework cannot resolve setId; even attempting it is an error.
                else -> error("API 101 does not support $name")
            } }
            return builder
        }
    }

    private class TestChain(
        private val member: Executable,
        private val receiver: Any? = null,
        private val arguments: Array<Any?> = emptyArray(),
        private val original: (Any?, Array<Any?>) -> Any? = { _, _ -> 3 }
    ) : XposedInterface.Chain {
        var calls = 0
        override fun getExecutable(): Executable = member
        override fun getThisObject(): Any? = receiver
        override fun getArgs(): List<Any?> = arguments.toList()
        override fun getArg(index: Int): Any? = arguments[index]
        override fun proceed(): Any? = proceed(arguments)
        override fun proceed(args: Array<Any?>): Any? {
            calls++
            return original(receiver, args)
        }
        override fun proceedWith(thisObject: Any): Any? = proceedWith(thisObject, arguments)
        override fun proceedWith(thisObject: Any, args: Array<Any?>): Any? {
            calls++
            return original(thisObject, args)
        }
    }

    class Fixture {
        var value = 0
        fun sum(a: Int, b: Int): Int = a + b
    }
}
