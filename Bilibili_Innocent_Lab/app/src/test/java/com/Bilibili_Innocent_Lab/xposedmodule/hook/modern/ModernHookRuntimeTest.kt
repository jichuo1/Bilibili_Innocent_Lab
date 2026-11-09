package com.Bilibili_Innocent_Lab.xposedmodule.hook.modern

import io.github.jichuo1.meguviz.hook.api.ErrorPolicy
import io.github.jichuo1.meguviz.hook.api.HookId
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * 业务安装器依赖的 before/after/replace 语义，经由真实的 MeguvizHook API 102 后端与调度器驱动：
 * 框架接口用代理替身，其余全部是生产代码。语义基准来自迁移前的实现（见差分测试）。
 */
@RunWith(Parameterized::class)
internal class ModernHookRuntimeTest(private val engine: HookDispatchEngine) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun engines(): List<HookDispatchEngine> = HookDispatchEngine.values().toList()
    }

    private fun ModernMemberHookCreator.invoke(chain: TestChain): Any? = invoke(chain, engine)

    private val method = Fixture::class.java.getDeclaredMethod("sum", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    private val pair = Fixture::class.java.getDeclaredMethod("pair", Any::class.java, Any::class.java)

    @Test
    fun `read only hook sees the incoming arguments and keeps its private copy unallocated`() {
        val incoming = arrayOf<Any?>(1, null)
        val chain = TestChain(pair, Fixture(), incoming) { _, args ->
            assertArrayEquals(incoming, args)
            3
        }
        val creator = ModernMemberHookCreator(pair).apply {
            before {
                assertEquals(1, argOrNull(0))
                assertNull(argOrNull(1))
                assertNull(argOrNull(-1))
                assertNull(argOrNull(2))
                assertNull(copiedArgsOrNull())
            }
            after {
                assertEquals(1, argOrNull(0))
                assertNull(copiedArgsOrNull())
            }
        }
        assertEquals(3, creator.invoke(chain))
        assertEquals(1, chain.calls)
    }

    @Test
    fun `array access stays private and read only accessor observes null writes`() {
        val incoming = arrayOf<Any?>(1, 2)
        var snapshot: Array<Any?>? = null
        val chain = TestChain(pair, Fixture(), incoming) { _, args ->
            assertSame(snapshot?.get(1), args[1])
            assertNull(args[0])
            5
        }
        val creator = ModernMemberHookCreator(pair).apply {
            before {
                assertEquals(1, argOrNull(0))
                snapshot = args
                args[0] = null
                assertSame(snapshot, args)
                assertNull(argOrNull(0))
            }
            after {
                assertSame(snapshot, args)
                assertNull(argOrNull(0))
            }
        }
        assertEquals(5, creator.invoke(chain))
        assertArrayEquals(arrayOf<Any?>(1, 2), incoming)
        assertEquals(1, chain.argumentOverrideCalls)
    }

    @Test
    fun `first array access in after cannot modify upstream arguments`() {
        val incoming = arrayOf<Any?>(1, 2)
        val chain = TestChain(method, Fixture(), incoming)
        val creator = ModernMemberHookCreator(method).apply {
            after { args[0] = 9; assertEquals(9, argOrNull(0)) }
        }
        assertEquals(3, creator.invoke(chain))
        assertEquals("an after-only hook keeps the downstream call on the original arguments", 0, chain.argumentOverrideCalls)
        assertArrayEquals(arrayOf<Any?>(1, 2), incoming)
    }

    @Test
    fun `extras remain unallocated on reads and support overwrites including null`() {
        val param = ModernHookParam(method, null, emptyList())
        val extras = ModernHookParam::class.java.getDeclaredField("extras").apply { isAccessible = true }
        assertNull(param.getObjectExtra("key"))
        assertNull(extras.get(param))
        param.setObjectExtra("key", 1)
        val map = extras.get(param)
        assertEquals(1, param.getObjectExtra("key"))
        param.setObjectExtra("key", null)
        assertSame(map, extras.get(param))
        assertNull(param.getObjectExtra("key"))
    }

    @Test
    fun `recursive invocations keep argument copies and extras isolated`() {
        lateinit var creator: ModernMemberHookCreator
        creator = ModernMemberHookCreator(method).apply {
            before {
                val value = argOrNull(0) as Int
                assertNull(getObjectExtra("value"))
                setObjectExtra("value", value)
                args[0] = value + 10
                if (value == 1) {
                    assertEquals(12, creator.invoke(TestChain(method, arguments = arrayOf(2, 0))))
                    assertEquals(11, argOrNull(0))
                    assertEquals(1, getObjectExtra("value"))
                }
            }
            after { result = argOrNull(0) }
        }
        assertEquals(11, creator.invoke(TestChain(method, arguments = arrayOf(1, 0))))
    }

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
        val chain = TestChain(pair)
        val creator = ModernMemberHookCreator(pair).apply {
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
    fun `constant replacements avoid argument reads and a later dynamic replacement still executes`() {
        val chain = TestChain(pair, arguments = arrayOf(5, 6))
        val creator = ModernMemberHookCreator(pair).apply { replaceTo(null) }
        assertNull(creator.invoke(chain))
        assertEquals(0, chain.argumentReads)
        assertEquals(0, chain.calls)

        creator.replaceAny { argOrNull(0) }
        assertEquals(5, creator.invoke(chain))
        assertEquals(1, chain.argumentReads)
        assertEquals(0, chain.calls)

        val failure = IllegalStateException("dynamic replacement failure")
        creator.replaceAny { throw failure }
        try {
            creator.invoke(chain)
            fail("dynamic failures must reach the framework")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
    }

    @Test
    fun `default interception returns correctly boxed zero values without reading arguments`() {
        val expected = mapOf(
            "booleanValue" to false, "byteValue" to 0.toByte(), "charValue" to 0.toChar(),
            "shortValue" to 0.toShort(), "intValue" to 0, "longValue" to 0L,
            "floatValue" to 0f, "doubleValue" to 0.0, "objectValue" to null, "voidValue" to null
        )
        for ((name, value) in expected) {
            val member = ReturnFixture::class.java.getDeclaredMethod(name)
            val chain = TestChain(member)
            assertEquals(name, value, ModernMemberHookCreator(member).apply { intercept() }.invoke(chain))
            assertEquals(name, 0, chain.argumentReads)
            assertEquals(name, 0, chain.calls)
        }
    }

    @Test
    fun `a replacement on a void method or constructor never hands a placeholder value to the framework`() {
        val voidMethod = ReturnFixture::class.java.getDeclaredMethod("voidValue")
        val chain = TestChain(voidMethod)
        assertNull(ModernMemberHookCreator(voidMethod).apply { replaceAny { Unit } }.invoke(chain))
        assertEquals(0, chain.calls)
        val constructor = Fixture::class.java.getDeclaredConstructor()
        assertNull(ModernMemberHookCreator(constructor).apply { replaceAny { "ignored" } }.invoke(TestChain(constructor, Fixture())))
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
        val framework = FakeXposedFramework()
        val environment = framework.environment()
        assertTrue(environment.install(creator.toSpec(HookId.of("extras"), ErrorPolicy.FRAMEWORK_PROTECTIVE)).isInstalled)
        val hooker = framework.handles.single().hooker
        assertEquals(1, hooker.intercept(TestChain(method)))
        assertEquals(2, hooker.intercept(TestChain(method)))
    }

    // --- 注册、身份与异常模式 ---------------------------------------------------------------------------

    @Test
    fun `registration never sets a framework id and requests protective mode`() {
        val framework = FakeXposedFramework()
        val runtime = ModernHookRuntime(framework.xposed, engine)
        runtime.install("feature:point", method) { replaceTo(1) }
        assertEquals(listOf(XposedInterface.ExceptionMode.PROTECTIVE), framework.modes)
        assertTrue("MeguvizHook scopes logical ids to its own environment", framework.ids.isEmpty())
        assertEquals(1, framework.handles.single().hooker.intercept(TestChain(method)))
    }

    @Test
    fun `repeating a logical point replaces the previous callback instead of stacking`() {
        val framework = FakeXposedFramework()
        val runtime = ModernHookRuntime(framework.xposed, engine)
        runtime.install("feature:point", method) { replaceTo(1) }
        runtime.install("feature:point", method) { replaceTo(2) }
        assertEquals("one framework hook, swapped atomically", 1, framework.handles.size)
        assertEquals(1, framework.replaced)
        assertEquals(2, framework.handles.single().hooker.intercept(TestChain(method)))
        // Re-created lambdas are different callbacks to the SDK; the latest one must still win.
        runtime.install("feature:dynamic", method) { before { setObjectExtra("a", 1) }; after { result = 5 } }
        runtime.install("feature:dynamic", method) { before { setObjectExtra("a", 2) }; after { result = 6 } }
        assertEquals(2, framework.handles.size)
        assertEquals(6, framework.handles.last().hooker.intercept(TestChain(method)))
    }

    @Test
    fun `different logical ids and different targets stay separate registrations`() {
        val framework = FakeXposedFramework()
        val runtime = ModernHookRuntime(framework.xposed, engine)
        runtime.install("a", method) { replaceTo(1) }
        runtime.install("b", method) { replaceTo(2) }
        runtime.install("a", pair) { replaceTo(3) }
        assertEquals(3, framework.handles.size)
        assertEquals(0, framework.replaced)
    }

    @Test
    fun `ids that the SDK would reject are normalised into stable unique ids`() {
        val awkward = "free-copy:comment-high:${method.toGenericString()}"
        val first = ModernHookIds.of(awkward, method)
        assertEquals(first, ModernHookIds.of(awkward, method))
        assertTrue(first.value().length <= 128)
        assertTrue(first.value().startsWith("bil:free-copy:comment-high:"))
        assertNotEquals(first, ModernHookIds.of(awkward, pair))
        assertNotEquals(first, ModernHookIds.of("$awkward!", method))
        val long = "x".repeat(400)
        assertNotEquals(ModernHookIds.of(long + "a", method), ModernHookIds.of(long + "b", method))
        assertTrue(ModernHookIds.of(long + "a", method).value().length <= 128)
        // Nothing the module registers today may be refused by the SDK's id grammar.
        listOf("", "plain", "a b", "<init>", "中文", "a/b:c.d-e_f").forEach { ModernHookIds.of(it, method) }
    }

    /**
     * `PROTECTIVE` 的框架契约是“捕获 hooker 异常并按没装 Hook 继续”，所以想让宿主方法以
     * 指定异常结束的 Hook 必须显式选 `PASSTHROUGH`；默认值不许漂移。
     */
    @Test
    fun `exception policy maps to the framework mode and defaults to protective`() {
        assertEquals(ErrorPolicy.FRAMEWORK_PROTECTIVE, HookExceptionPolicy.PROTECT_HOST.errorPolicy)
        assertEquals(ErrorPolicy.PROPAGATE, HookExceptionPolicy.DELIVER_TO_HOST.errorPolicy)
        val framework = FakeXposedFramework()
        val runtime = ModernHookRuntime(framework.xposed, engine)
        runtime.install("default", method) { replaceTo(1) }
        runtime.install("deliver", method, HookExceptionPolicy.DELIVER_TO_HOST) { replaceTo(1) }
        assertEquals(
            listOf(XposedInterface.ExceptionMode.PROTECTIVE, XposedInterface.ExceptionMode.PASSTHROUGH),
            framework.modes
        )
    }

    @Test
    fun `changing the exception policy of a logical point closes the old hook and installs a fresh one`() {
        val framework = FakeXposedFramework()
        val runtime = ModernHookRuntime(framework.xposed, engine)
        runtime.install("point", method) { replaceTo(1) }
        runtime.install("point", method, HookExceptionPolicy.DELIVER_TO_HOST) { replaceTo(2) }
        assertEquals(listOf(XposedInterface.ExceptionMode.PROTECTIVE, XposedInterface.ExceptionMode.PASSTHROUGH), framework.modes)
        assertEquals(1, framework.unhooks)
        assertEquals(0, framework.replaced)
    }

    @Test
    fun `the returned handle unhooks the framework hook`() {
        val framework = FakeXposedFramework()
        val handle = ModernHookRuntime(framework.xposed, engine).install("observer", method) { after { } }
        assertEquals(0, framework.unhooks)
        handle.unhook()
        assertEquals(1, framework.unhooks)
    }

    @Test
    fun `an API below 102 is rejected and nothing is installed`() {
        val framework = FakeXposedFramework(apiVersion = 101)
        try {
            ModernHookRuntime(framework.xposed, engine).install("unsupported", method) { intercept() }
            fail("API 101 is no longer supported")
        } catch (expected: IllegalStateException) {
            assertEquals("Modern API 102 or newer is required", expected.message)
        }
        assertTrue(framework.handles.isEmpty())
    }

    @Test
    fun `an install the SDK refuses fails loudly instead of leaving a silent gap`() {
        val framework = FakeXposedFramework()
        // A constant that cannot be returned by the target is rejected up front (it used to crash the host later).
        try {
            ModernHookRuntime(framework.xposed, engine).install("bad-constant", method) { replaceTo("not an int") }
            fail("an incompatible constant must not install")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message, expected.message!!.contains("was not installed"))
        }
        assertTrue(framework.handles.isEmpty())
    }

    @Test
    fun `constructor hooks go to the framework chain so the receiver stays visible and a repeat replaces`() {
        val constructor = Fixture::class.java.getDeclaredConstructor()
        val framework = FakeXposedFramework()
        val runtime = ModernHookRuntime(framework.xposed, engine)
        val seen = mutableListOf<Any?>()
        runtime.install("ctor", constructor) { after { seen += thisObject } }
        runtime.install("ctor", constructor) { after { seen += thisObject; seen += "second" } }
        assertEquals("constructors use the raw framework hook", 1, framework.handles.size)
        assertEquals(1, framework.replaced)
        assertEquals(listOf(XposedInterface.ExceptionMode.PROTECTIVE), framework.modes)
        val instance = Fixture()
        framework.handles.single().hooker.intercept(TestChain(constructor, instance) { _, _ -> null })
        assertEquals(listOf<Any?>(instance, "second"), seen)
        runtime.install("ctor", constructor, HookExceptionPolicy.DELIVER_TO_HOST) { after { } }
        assertEquals(2, framework.handles.size)
        assertEquals(1, framework.unhooks)
        assertEquals(XposedInterface.ExceptionMode.PASSTHROUGH, framework.modes.last())
    }

    @Test
    fun `a hook assigned throwable only reaches the host when the policy delivers it`() {
        val failure = IllegalStateException("assigned")
        fun hooker(policy: HookExceptionPolicy): XposedInterface.Hooker {
            val framework = FakeXposedFramework()
            ModernHookRuntime(framework.xposed, engine).install("p", method, policy) { after { throwable = failure } }
            return framework.handles.single().hooker
        }
        // Protective: the failure leaves the callback so the framework can swallow it and continue as unhooked.
        try {
            hooker(HookExceptionPolicy.PROTECT_HOST).intercept(TestChain(method))
            fail("the assigned throwable must leave the callback")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
        // Deliver: an explicit outcome, independent of how callback failures are treated.
        try {
            hooker(HookExceptionPolicy.DELIVER_TO_HOST).intercept(TestChain(method))
            fail("the assigned throwable must be delivered")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
    }

    // --- 故障归因 ---------------------------------------------------------------------------------------------

    private fun faults(runtime: ModernHookRuntime): List<String> = mutableListOf<String>().also { runtime.drainFaults(it::add) }

    private fun IllegalStateException.leaks(hooker: XposedInterface.Hooker, chain: TestChain) =
        try {
            hooker.intercept(chain)
            fail("the callback failure must reach the framework")
        } catch (actual: IllegalStateException) {
            assertSame(this, actual)
        }

    @Test
    fun `an exception escaping a protected feature callback is attributed to the feature once`() {
        val framework = FakeXposedFramework()
        val runtime = ModernHookRuntime(framework.xposed, engine)
        val failure = IllegalStateException("callback bug")
        runtime.install("a", method) { faultOwner = "feature_a"; after { throw failure } }
        runtime.install("b", pair) { faultOwner = "feature_b"; before { throw failure } }
        assertTrue(faults(runtime).isEmpty())
        failure.leaks(framework.handles[0].hooker, TestChain(method))
        failure.leaks(framework.handles[0].hooker, TestChain(method))
        failure.leaks(framework.handles[1].hooker, TestChain(pair, arguments = arrayOf(1, 2)))
        assertEquals(setOf("feature_a", "feature_b"), faults(runtime).toSet())
        assertTrue("drained records are not reported again", faults(runtime).isEmpty())
    }

    @Test
    fun `a failing original or a clean callback is not a feature fault`() {
        val framework = FakeXposedFramework()
        val runtime = ModernHookRuntime(framework.xposed, engine)
        runtime.install("a", method) { faultOwner = "feature_a"; after { result = (result as? Int ?: 0) + 1 } }
        framework.handles[0].hooker.intercept(TestChain(method))
        val original = java.io.IOException("host failure")
        try {
            framework.handles[0].hooker.intercept(TestChain(method) { _, _ -> throw original })
        } catch (_: Exception) {
        }
        assertTrue(faults(runtime).isEmpty())
    }

    @Test
    fun `only protected hooks with an owner are watched and an unhook ends the attribution`() {
        val framework = FakeXposedFramework()
        val runtime = ModernHookRuntime(framework.xposed, engine)
        val failure = IllegalStateException("bug")
        runtime.install("no-owner", method) { after { throw failure } }
        runtime.install("deliver", pair, HookExceptionPolicy.DELIVER_TO_HOST) { faultOwner = "feature_d"; before { throw failure } }
        val gone = runtime.install("gone", method) { faultOwner = "feature_g"; before { throw failure } }
        failure.leaks(framework.handles[0].hooker, TestChain(method))
        failure.leaks(framework.handles[1].hooker, TestChain(pair, arguments = arrayOf(1, 2)))
        failure.leaks(framework.handles[2].hooker, TestChain(method, arguments = arrayOf(1, 2)))
        gone.unhook()
        assertTrue(faults(runtime).isEmpty())
    }

    @Test
    fun `a constructor callback failure is attributed but a failing original constructor is not`() {
        val constructor = Fixture::class.java.getDeclaredConstructor()
        val framework = FakeXposedFramework()
        val runtime = ModernHookRuntime(framework.xposed, engine)
        val failure = IllegalStateException("callback bug")
        runtime.install("ctor", constructor) { faultOwner = "feature_c"; after { if (thisObject != null) throw failure } }
        val hooker = framework.handles.single().hooker
        val original = java.io.IOException("host constructor failed")
        try {
            hooker.intercept(TestChain(constructor, Fixture()) { _, _ -> throw original })
        } catch (_: Exception) {
        }
        failure.leaks(hooker, TestChain(constructor, Fixture()) { _, _ -> null })
        assertEquals(listOf("feature_c"), faults(runtime))
    }

    @Test
    fun `a point first installed as a constant and later as a callback leaves exactly one live hook`() {
        val framework = FakeXposedFramework()
        val runtime = ModernHookRuntime(framework.xposed, engine)
        runtime.install("shape", method) { replaceTo(1) }
        runtime.install("shape", method) { after { result = 2 } }
        val live = framework.handles.size - framework.unhooks
        assertEquals("no stacked registrations after the shape changed", 1, live)
    }

    @Test
    fun `the direct engine is the shipped default and the sdk engine stays selectable`() {
        assertEquals(HookDispatchEngine.DIRECT, HookDispatchEngine.values().first())
        val creator = ModernMemberHookCreator(method).apply { after { } }
        assertTrue(creator.usesDirectChain(HookDispatchEngine.DIRECT))
        assertFalse(creator.usesDirectChain(HookDispatchEngine.SDK))
        val constant = ModernMemberHookCreator(method).apply { replaceTo(1) }
        assertFalse("constants always use the sdk constant channel", constant.usesDirectChain(HookDispatchEngine.DIRECT))
        val constructor = ModernMemberHookCreator(Fixture::class.java.getDeclaredConstructor()).apply { after { } }
        assertTrue("constructor receivers are only visible on the framework chain", constructor.usesDirectChain(HookDispatchEngine.SDK))
    }

    // --- 差分：与迁移前实现逐组合比对 ---------------------------------------------------------------------------

    private enum class BeforeAction { NONE, NOOP, SET_RESULT, SET_THROWABLE, MUTATE_ARG, THROW, EXTRA }
    private enum class AfterAction { NONE, NOOP, SET_RESULT, SET_THROWABLE, RECOVER, DOUBLE_RESULT, THROW, READ_EXTRA }
    private enum class Original { RETURN, THROW }

    private val beforeFailure = IllegalStateException("before")
    private val beforeThrow = IllegalArgumentException("before throws")
    private val afterFailure = IllegalStateException("after")
    private val afterThrow = UnsupportedOperationException("after throws")
    private val originalFailure = java.io.IOException("original")

    private fun apply(action: BeforeAction, param: ModernHookParam) {
        when (action) {
            BeforeAction.NONE, BeforeAction.NOOP -> Unit
            BeforeAction.SET_RESULT -> param.result = 11
            BeforeAction.SET_THROWABLE -> param.throwable = beforeFailure
            BeforeAction.MUTATE_ARG -> param.args[0] = 100
            BeforeAction.THROW -> throw beforeThrow
            BeforeAction.EXTRA -> param.setObjectExtra("k", 7)
        }
    }

    private fun apply(action: AfterAction, param: ModernHookParam) {
        when (action) {
            AfterAction.NONE, AfterAction.NOOP -> Unit
            AfterAction.SET_RESULT -> param.result = 22
            AfterAction.SET_THROWABLE -> param.throwable = afterFailure
            AfterAction.RECOVER -> param.result = 33
            AfterAction.DOUBLE_RESULT -> (param.result as? Int)?.let { param.result = it * 2 }
            AfterAction.THROW -> throw afterThrow
            AfterAction.READ_EXTRA -> (param.getObjectExtra("k") as? Int)?.let { param.result = it }
        }
    }

    private data class Observed(val returned: Any?, val thrown: Throwable?, val originalCalls: Int, val originalArgs: List<Any?>?)

    private fun creatorFor(before: BeforeAction, after: AfterAction) = ModernMemberHookCreator(method).apply {
        if (before != BeforeAction.NONE) before { apply(before, this) }
        if (after != AfterAction.NONE) after { apply(after, this) }
    }

    private fun scripted(original: Original, calls: IntArray, seen: Array<List<Any?>?>) = { _: Any?, args: Array<Any?> ->
        calls[0]++
        seen[0] = args.toList()
        if (original == Original.THROW) throw originalFailure
        (args[0] as Int) + (args[1] as Int)
    }

    private fun observe(run: () -> Any?, calls: IntArray, seen: Array<List<Any?>?>): Observed =
        try {
            Observed(run(), null, calls[0], seen[0])
        } catch (failure: Throwable) {
            Observed(null, failure, calls[0], seen[0])
        }

    /** 迁移前 `ModernMemberHookCreator.invoke(chain)` 的控制流，原样保留作为参照。 */
    private fun legacy(before: BeforeAction, after: AfterAction, original: Original): Observed {
        val calls = intArrayOf(0)
        val seen = arrayOfNulls<List<Any?>>(1)
        val incoming = arrayOf<Any?>(1, 2)
        val run = scripted(original, calls, seen)
        return observe({
            val param = ModernHookParam(method, null, incoming.toList())
            if (before != BeforeAction.NONE) apply(before, param)
            if (!param.wasResultAssignedByHook() && !param.hasThrowable) {
                try {
                    val copied = param.copiedArgsOrNull()
                    param.assignOriginal(run(null, copied ?: incoming))
                } catch (failure: Throwable) {
                    param.assignOriginalFailure(failure)
                }
            }
            if (after != AfterAction.NONE) apply(after, param)
            param.throwable?.let { throw it }
            param.result
        }, calls, seen)
    }

    private fun migrated(before: BeforeAction, after: AfterAction, original: Original): Observed {
        val calls = intArrayOf(0)
        val seen = arrayOfNulls<List<Any?>>(1)
        val incoming = arrayOf<Any?>(1, 2)
        val chain = TestChain(method, null, incoming, scripted(original, calls, seen))
        return observe({ creatorFor(before, after).invoke(chain) }, calls, seen)
    }

    @Test
    fun `every before after and original combination matches the pre-migration behaviour`() {
        var combinations = 0
        for (before in BeforeAction.values()) for (after in AfterAction.values()) for (original in Original.values()) {
            val expected = legacy(before, after, original)
            val actual = migrated(before, after, original)
            val label = "before=$before after=$after original=$original"
            assertEquals(label + " original calls", expected.originalCalls, actual.originalCalls)
            assertEquals(label + " arguments seen by the original", expected.originalArgs, actual.originalArgs)
            // 唯一的有意差异（仅“只有 after”的形状）：原方法已失败时 after 又出错（抛出或写入 throwable），
            // SDK 契约还原原始异常、after 的异常进入诊断；迁移前由 after 的异常覆盖。该情形只在 Hook 自身出错时出现，
            // 保护模式下框架对“已执行下游后回调失败”同样交回下游结果，所以宿主看到的仍是原异常。
            val expectedThrown =
                if (engine == HookDispatchEngine.SDK && before == BeforeAction.NONE && original == Original.THROW &&
                    (after == AfterAction.THROW || after == AfterAction.SET_THROWABLE)) originalFailure
                else expected.thrown
            assertSame(label + " exception identity", expectedThrown, actual.thrown)
            assertEquals(label + " result", expected.returned, actual.returned)
            combinations++
        }
        assertEquals(7 * 8 * 2, combinations)
    }

    // --- 测试替身 ---------------------------------------------------------------------------------------------

    private class TestChain(
        private val member: Executable,
        private val receiver: Any? = null,
        private val arguments: Array<Any?> = emptyArray(),
        private val original: (Any?, Array<Any?>) -> Any? = { _, _ -> 3 }
    ) : XposedInterface.Chain {
        var calls = 0
        var argumentReads = 0
        var argumentOverrideCalls = 0
        override fun getExecutable(): Executable = member
        override fun getThisObject(): Any? = receiver
        override fun getArgs(): List<Any?> {
            argumentReads++
            return arguments.toList()
        }
        override fun getArg(index: Int): Any? = arguments[index]
        override fun proceed(): Any? {
            calls++
            return original(receiver, arguments)
        }
        override fun proceed(args: Array<Any?>): Any? {
            calls++
            argumentOverrideCalls++
            return original(receiver, args)
        }
        override fun proceedWith(thisObject: Any): Any? = proceedWith(thisObject, arguments)
        override fun proceedWith(thisObject: Any, args: Array<Any?>): Any? {
            calls++
            return original(thisObject, args)
        }
    }

    class ReturnFixture {
        fun booleanValue(): Boolean = true
        fun byteValue(): Byte = 1
        fun charValue(): Char = 'a'
        fun shortValue(): Short = 1
        fun intValue(): Int = 1
        fun longValue(): Long = 1L
        fun floatValue(): Float = 1f
        fun doubleValue(): Double = 1.0
        fun objectValue(): Any = this
        fun voidValue() = Unit
    }

    class Fixture {
        var value = 0
        fun sum(a: Int, b: Int): Int = a + b
        fun pair(a: Any?, b: Any?): Any? = a
    }
}
