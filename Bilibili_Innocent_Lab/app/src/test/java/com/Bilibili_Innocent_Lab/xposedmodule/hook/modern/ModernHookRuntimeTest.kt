package com.Bilibili_Innocent_Lab.xposedmodule.hook.modern

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import io.github.jichuo1.meguviz.hook.api.ErrorPolicy
import io.github.jichuo1.meguviz.hook.api.HookId
import io.github.jichuo1.meguviz.hook.api.ParameterAccess
import io.github.jichuo1.meguviz.hook.core.HookEnvironment
import io.github.jichuo1.meguviz.hook.testing.ContractOracle
import io.github.jichuo1.meguviz.hook.testing.FakeBackend
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import org.junit.Assert.*
import org.junit.Test

/**
 * 业务安装器依赖的 before/after/replace/log/reflect 语义，经由真实的 MeguvizHook API 102 后端与调度器驱动：
 * 框架接口用代理替身，其余全部是生产代码。语义基准来自迁移前的实现（见差分测试）。
 */
class ModernHookRuntimeTest {
    private val method = Fixture::class.java.getDeclaredMethod("sum", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    private val pair = Fixture::class.java.getDeclaredMethod("pair", Any::class.java, Any::class.java)

    private fun faults(runtime: ModernHookRuntime): List<String> = mutableListOf<String>().also { runtime.drainFaults(it::add) }

    private fun IllegalStateException.leaks(hooker: XposedInterface.Hooker, chain: TestChain) =
        try {
            hooker.intercept(chain)
            fail("the callback failure must reach the framework")
        } catch (actual: IllegalStateException) {
            assertSame(this, actual)
        }

    @Test
    fun `read only and mutated argument copies stay isolated across before after and recursive invocations`() {
        for (engine in HookDispatchEngine.values()) {
            val readIncoming = arrayOf<Any?>(1, null)
            val readChain = TestChain(pair, Fixture(), readIncoming) { _, args ->
                assertArrayEquals(readIncoming, args)
                3
            }
            val readOnly = ModernMemberHookCreator(pair).apply {
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
            assertEquals(3, readOnly.invoke(readChain, engine))
            assertEquals(1, readChain.calls)

            val mutIncoming = arrayOf<Any?>(1, 2)
            var snapshot: Array<Any?>? = null
            val mutChain = TestChain(pair, Fixture(), mutIncoming) { _, args ->
                assertSame(snapshot?.get(1), args[1])
                assertNull(args[0])
                5
            }
            val mutator = ModernMemberHookCreator(pair).apply {
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
            assertEquals(5, mutator.invoke(mutChain, engine))
            assertArrayEquals(arrayOf<Any?>(1, 2), mutIncoming)
            assertEquals(1, mutChain.argumentOverrideCalls)

            val afterOnlyIncoming = arrayOf<Any?>(1, 2)
            val afterOnlyChain = TestChain(method, Fixture(), afterOnlyIncoming)
            val afterOnly = ModernMemberHookCreator(method).apply {
                after { args[0] = 9; assertEquals(9, argOrNull(0)) }
            }
            assertEquals(3, afterOnly.invoke(afterOnlyChain, engine))
            assertEquals("an after-only hook keeps the downstream call on the original arguments", 0, afterOnlyChain.argumentOverrideCalls)
            assertArrayEquals(arrayOf<Any?>(1, 2), afterOnlyIncoming)

            lateinit var recursive: ModernMemberHookCreator
            recursive = ModernMemberHookCreator(method).apply {
                before {
                    val value = argOrNull(0) as Int
                    assertNull(getObjectExtra("value"))
                    setObjectExtra("value", value)
                    args[0] = value + 10
                    if (value == 1) {
                        assertEquals(12, recursive.invoke(TestChain(method, arguments = arrayOf(2, 0)), engine))
                        assertEquals(11, argOrNull(0))
                        assertEquals(1, getObjectExtra("value"))
                    }
                }
                after { result = argOrNull(0) }
            }
            assertEquals(11, recursive.invoke(TestChain(method, arguments = arrayOf(1, 0)), engine))

            val transformIncoming = arrayOf<Any?>(1, 2)
            val transformChain = TestChain(method, Fixture(), transformIncoming) { _, args -> (args[0] as Int) + (args[1] as Int) }
            val transformer = ModernMemberHookCreator(method).apply {
                before { args[0] = 5; setObjectExtra("value", 2) }
                after { result = (result as Int) * (getObjectExtra("value") as Int) }
            }
            assertEquals(14, transformer.invoke(transformChain, engine))
            assertArrayEquals(arrayOf<Any?>(1, 2), transformIncoming)
            assertEquals(1, transformChain.calls)
        }

        // Direct chain avoids proceed(args) copy on read-only args[] access, and forwards setArg/args[] mutations.
        val combine = ReadAndWriteArgsTarget::class.java.getDeclaredMethod("combine", String::class.java, String::class.java)
        val receiver = ReadAndWriteArgsTarget()
        var readFirst: Any? = null
        val readOnlyHook = ModernMemberHookCreator(combine).apply { before { readFirst = args[0] } }
        val directReadChain = TestChain(combine, receiver, arrayOf("x", "y")) { _, a -> receiver.combine(a[0] as String, a[1] as String) }
        assertEquals("x:y", readOnlyHook.invoke(directReadChain))
        assertEquals("x", readFirst)
        assertEquals(1, directReadChain.calls)
        assertEquals(0, directReadChain.argumentOverrideCalls)

        val mutatedViaArray = ModernMemberHookCreator(combine).apply { before { args[0] = "z" } }
        val mutateArrayChain = TestChain(combine, receiver, arrayOf("x", "y")) { _, a -> receiver.combine(a[0] as String, a[1] as String) }
        assertEquals("z:y", mutatedViaArray.invoke(mutateArrayChain))
        assertEquals(1, mutateArrayChain.argumentOverrideCalls)
        assertArrayEquals(arrayOf("x", "y"), mutateArrayChain.args.toTypedArray())
        assertArrayEquals(arrayOf("z", "y"), mutateArrayChain.lastPassedArgs)

        val mutatedViaSetArg = ModernMemberHookCreator(combine).apply { before { setArg(1, "w") } }
        val mutateSetArgChain = TestChain(combine, receiver, arrayOf("x", "y")) { _, a -> receiver.combine(a[0] as String, a[1] as String) }
        assertEquals("x:w", mutatedViaSetArg.invoke(mutateSetArgChain))
        assertEquals(1, mutateSetArgChain.argumentOverrideCalls)
    }

    @Test
    fun `extras remain unallocated on reads support null overwrites and never leak across invocations`() {
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

    @Test
    fun `rewritten surface borrow is returned once even when the original setter throws`() {
        val target = BorrowPlayer::class.java.getDeclaredMethod("setOutputSurface", Any::class.java)
        val player = BorrowPlayer()
        val display = Any()
        val input = Any()
        val failure = IllegalArgumentException("invalid surface")
        for (throws in listOf(false, true)) {
            val backend = FakeBackend.create()
            backend.onTarget(target) { owner, arguments ->
                assertSame(player, owner)
                assertSame(input, arguments.single())
                if (throws) throw failure
                null
            }
            var releases = 0
            val setter = ModernMemberHookCreator(target).apply {
                before {
                    assertSame(display, argOrNull(0))
                    setObjectExtra("borrow", input)
                    args[0] = input
                }
                after {
                    assertSame(input, getObjectExtra("borrow"))
                    assertEquals(throws, hasThrowable)
                    releases++
                }
            }
            HookEnvironment.create(backend).use { environment ->
                val receipt = environment.install(setter.toSpec(HookId.of("bil.surface.repeat"), ErrorPolicy.PROPAGATE))
                assertTrue(receipt.message(), receipt.isInstalled)
                val observed = runCatching { backend.call(target, player, display) }.exceptionOrNull()
                val expected = if (throws) ContractOracle.originalThrows() else ContractOracle.originalReturns()
                assertSame(if (throws) failure else null, observed)
                assertEquals(expected.originalCalls(), backend.originalCallCount(target))
                assertEquals(if (expected.afterCallbacksRan()) 1 else 0, releases)
            }
        }
    }

    @Test
    fun `early results and exceptions respect before after and protective policies`() {
        for (engine in HookDispatchEngine.values()) {
            val earlyNullChain = TestChain(pair)
            val earlyNullCreator = ModernMemberHookCreator(pair).apply {
                before { result = null }
                after { assertNull(result); result = 7 }
            }
            assertEquals(7, earlyNullCreator.invoke(earlyNullChain, engine))
            assertEquals(0, earlyNullChain.calls)

            val hostFailure = IllegalStateException("host")
            val hostFailChain = TestChain(method) { _, _ -> throw hostFailure }
            val propagating = ModernMemberHookCreator(method).apply { after { assertSame(hostFailure, throwable) } }
            try {
                propagating.invoke(hostFailChain, engine)
                fail("host failure must propagate")
            } catch (actual: IllegalStateException) {
                assertSame(hostFailure, actual)
            }
            assertEquals(1, hostFailChain.calls)
            val recovered = ModernMemberHookCreator(method).apply { after { result = 12 } }
            assertEquals(12, recovered.invoke(TestChain(method) { _, _ -> throw hostFailure }, engine))

            val beforeFailure = IllegalArgumentException("requested failure")
            val beforeFailChain = TestChain(method)
            val beforeFailCreator = ModernMemberHookCreator(method).apply {
                before { throwable = beforeFailure }
                after { assertSame(beforeFailure, throwable); result = 9 }
            }
            assertEquals(9, beforeFailCreator.invoke(beforeFailChain, engine))
            assertEquals(0, beforeFailChain.calls)

            val hookFailure = IllegalStateException("hook failure")
            val hookFailChain = TestChain(method)
            val hookFailCreator = ModernMemberHookCreator(method).apply { after { throw hookFailure } }
            try {
                hookFailCreator.invoke(hookFailChain, engine)
                fail("protective recovery belongs to the framework")
            } catch (actual: IllegalStateException) {
                assertSame(hookFailure, actual)
            }
            assertEquals(1, hookFailChain.calls)

            val assignedFailure = IllegalStateException("assigned")
            fun hooker(policy: HookExceptionPolicy): XposedInterface.Hooker {
                val framework = FakeXposedFramework()
                ModernHookRuntime(framework.xposed, engine).install("p", method, policy) { after { throwable = assignedFailure } }
                return framework.handles.single().hooker
            }
            try {
                hooker(HookExceptionPolicy.PROTECT_HOST).intercept(TestChain(method))
                fail("the assigned throwable must leave the callback")
            } catch (actual: IllegalStateException) {
                assertSame(assignedFailure, actual)
            }
            try {
                hooker(HookExceptionPolicy.DELIVER_TO_HOST).intercept(TestChain(method))
                fail("the assigned throwable must be delivered")
            } catch (actual: IllegalStateException) {
                assertSame(assignedFailure, actual)
            }
        }
    }

    @Test
    fun `constant dynamic and default replacements skip original and box zero values without reading arguments`() {
        val expectedZeros = mapOf(
            "booleanValue" to false, "byteValue" to 0.toByte(), "charValue" to 0.toChar(),
            "shortValue" to 0.toShort(), "intValue" to 0, "longValue" to 0L,
            "floatValue" to 0f, "doubleValue" to 0.0, "objectValue" to null, "voidValue" to null
        )
        for (engine in HookDispatchEngine.values()) {
            val replaceChain = TestChain(method)
            assertEquals(0, ModernMemberHookCreator(method).apply { intercept() }.invoke(replaceChain, engine))
            assertEquals(42, ModernMemberHookCreator(method).apply { replaceTo(42) }.invoke(replaceChain, engine))
            assertEquals(0, replaceChain.calls)

            val dynChain = TestChain(pair, arguments = arrayOf(5, 6))
            val dynCreator = ModernMemberHookCreator(pair).apply { replaceTo(null) }
            assertNull(dynCreator.invoke(dynChain, engine))
            assertEquals(0, dynChain.argumentListReads)
            assertEquals(0, dynChain.argumentSlotReads)
            assertEquals(0, dynChain.calls)

            dynCreator.replaceAny { argOrNull(0) }
            assertEquals(5, dynCreator.invoke(dynChain, engine))
            assertEquals(0, dynChain.argumentListReads)
            assertEquals(1, dynChain.argumentSlotReads)
            assertEquals(0, dynChain.calls)

            val dynFailure = IllegalStateException("dynamic replacement failure")
            dynCreator.replaceAny { throw dynFailure }
            try {
                dynCreator.invoke(dynChain, engine)
                fail("dynamic failures must reach the framework")
            } catch (actual: IllegalStateException) {
                assertSame(dynFailure, actual)
            }

            for ((name, value) in expectedZeros) {
                val member = ReturnFixture::class.java.getDeclaredMethod(name)
                val chain = TestChain(member)
                assertEquals(name, value, ModernMemberHookCreator(member).apply { intercept() }.invoke(chain, engine))
                assertEquals(name, 0, chain.argumentListReads)
                assertEquals(name, 0, chain.argumentSlotReads)
                assertEquals(name, 0, chain.calls)
            }

            val voidMethod = ReturnFixture::class.java.getDeclaredMethod("voidValue")
            val voidChain = TestChain(voidMethod)
            assertNull(ModernMemberHookCreator(voidMethod).apply { replaceAny { Unit } }.invoke(voidChain, engine))
            assertEquals(0, voidChain.calls)
            val constructor = Fixture::class.java.getDeclaredConstructor()
            assertNull(ModernMemberHookCreator(constructor).apply { replaceAny { "ignored" } }.invoke(TestChain(constructor, Fixture()), engine))

            val successFramework = FakeXposedFramework()
            ModernHookRuntime(successFramework.xposed, engine).install("success", method) { replaceResultOnSuccess(42) }
            val successHooker = successFramework.handles.single().hooker
            assertEquals(42, successHooker.intercept(TestChain(method) { _, _ -> 7 }))
            val hostFailure = IllegalStateException("host failed")
            try {
                successHooker.intercept(TestChain(method) { _, _ -> throw hostFailure })
                fail("original exception must propagate")
            } catch (actual: IllegalStateException) {
                assertSame(hostFailure, actual)
            }
        }
    }

    @Test
    fun `constructors beforeOnly lazy slot reads and parameter access specs behave cleanly`() {
        val constructor = Fixture::class.java.getDeclaredConstructor()
        val zeroArg = ReturnFixture::class.java.getDeclaredMethod("intValue")
        for (engine in HookDispatchEngine.values()) {
            val instance = Fixture()
            val ctorChain = TestChain(constructor, instance) { receiver, _ ->
                assertSame(instance, receiver)
                instance.value = 5
                null
            }
            val ctorCreator = ModernMemberHookCreator(constructor).apply {
                before { assertSame(instance, thisObject); assertEquals(0, instance.value) }
                after { assertSame(instance, thisObject); assertEquals(5, instance.value) }
            }
            assertNull(ctorCreator.invoke(ctorChain, engine))
            assertEquals(1, ctorChain.calls)

            val ctorFramework = FakeXposedFramework()
            val ctorRuntime = ModernHookRuntime(ctorFramework.xposed, engine)
            val seen = mutableListOf<Any?>()
            ctorRuntime.install("ctor", constructor) { after { seen += thisObject } }
            ctorRuntime.install("ctor", constructor) { after { seen += thisObject; seen += "second" } }
            assertEquals("constructors use the raw framework hook", 1, ctorFramework.handles.size)
            assertEquals(1, ctorFramework.replaced)
            assertEquals(listOf(XposedInterface.ExceptionMode.PROTECTIVE), ctorFramework.modes)
            val ctorReceiver = Fixture()
            ctorFramework.handles.single().hooker.intercept(TestChain(constructor, ctorReceiver) { _, _ -> null })
            assertEquals(listOf<Any?>(ctorReceiver, "second"), seen)
            ctorRuntime.install("ctor", constructor, HookExceptionPolicy.DELIVER_TO_HOST) { after { } }
            assertEquals(2, ctorFramework.handles.size)
            assertEquals(1, ctorFramework.unhooks)
            assertEquals(XposedInterface.ExceptionMode.PASSTHROUGH, ctorFramework.modes.last())

            val beforeFramework = FakeXposedFramework()
            val beforeRuntime = ModernHookRuntime(beforeFramework.xposed, engine)
            var seen0: Any? = null
            beforeRuntime.install("before-only", pair) {
                faultOwner = "feature_before"
                before {
                    seen0 = argOrNull(0)
                    assertNull(copiedArgsOrNull())
                }
            }
            val beforeHooker = beforeFramework.handles.single().hooker
            val okChain = TestChain(pair, Fixture(), arrayOf("a", "b")) { _, args -> args[1] }
            assertEquals("b", beforeHooker.intercept(okChain))
            assertEquals("a", seen0)
            assertEquals(0, okChain.argumentListReads)
            assertEquals(if (engine == HookDispatchEngine.DIRECT) 1 else 2, okChain.argumentSlotReads)
            assertEquals(if (engine == HookDispatchEngine.DIRECT) 0 else 1, okChain.argumentOverrideCalls)
            val ioFailure = java.io.IOException("downstream failed")
            try {
                beforeHooker.intercept(TestChain(pair, Fixture(), arrayOf("a", "b")) { _, _ -> throw ioFailure })
                fail("downstream failure must propagate")
            } catch (actual: java.io.IOException) {
                assertSame(ioFailure, actual)
            }
            assertTrue(faults(beforeRuntime).isEmpty())

            val zeroBefore = ModernMemberHookCreator(zeroArg).apply { before { } }
                .toSpec(HookId.of("zero-before"), ErrorPolicy.FRAMEWORK_PROTECTIVE)
            assertEquals(ParameterAccess.NO_ARGS, zeroBefore.access())
            val noArgsBefore = ModernMemberHookCreator(pair).apply { before(ParameterAccess.NO_ARGS) { } }
                .toSpec(HookId.of("noargs-before"), ErrorPolicy.FRAMEWORK_PROTECTIVE)
            assertEquals(ParameterAccess.NO_ARGS, noArgsBefore.access())
            val readBefore = ModernMemberHookCreator(pair).apply { before(ParameterAccess.READ_ARGS_VIEW) { argOrNull(0) } }
                .toSpec(HookId.of("read-before"), ErrorPolicy.FRAMEWORK_PROTECTIVE)
            assertEquals(ParameterAccess.READ_ARGS_VIEW, readBefore.access())
            val mutableBefore = ModernMemberHookCreator(pair).apply { before { args[0] = "x" } }
                .toSpec(HookId.of("mutable-before"), ErrorPolicy.FRAMEWORK_PROTECTIVE)
            assertEquals(ParameterAccess.MUTABLE_ARGS, mutableBefore.access())
            val readAfter = ModernMemberHookCreator(pair).apply { after { argOrNull(0) } }
                .toSpec(HookId.of("read-after"), ErrorPolicy.FRAMEWORK_PROTECTIVE)
            assertEquals(ParameterAccess.READ_ARGS_VIEW, readAfter.access())

            val readFramework = FakeXposedFramework()
            var readSeen: Any? = null
            ModernHookRuntime(readFramework.xposed, engine).install("read-before-run", pair) {
                faultOwner = "feature_read"
                before(ParameterAccess.READ_ARGS_VIEW) { readSeen = argOrNull(0) }
            }
            val readChain = TestChain(pair, Fixture(), arrayOf("x", "y")) { _, args -> args[1] }
            assertEquals("y", readFramework.handles.single().hooker.intercept(readChain))
            assertEquals("x", readSeen)
            assertEquals(0, readChain.argumentListReads)
            assertEquals(1, readChain.argumentSlotReads)
            assertEquals(0, readChain.argumentOverrideCalls)
        }
    }

    @Test
    fun `registration replaces logical points isolates separate targets and rejects legacy api or invalid executables`() {
        val abstractMethod = AbstractFixture::class.java.getDeclaredMethod("render")
        val interfaceMethod = InterfaceFixture::class.java.getDeclaredMethod("applyModifier", Any::class.java)
        for (engine in HookDispatchEngine.values()) {
            val regFramework = FakeXposedFramework()
            val regRuntime = ModernHookRuntime(regFramework.xposed, engine)
            regRuntime.install("feature:point", method) { replaceTo(1) }
            assertEquals(listOf(XposedInterface.ExceptionMode.PROTECTIVE), regFramework.modes)
            assertTrue("MeguvizHook scopes logical ids to its own environment", regFramework.ids.isEmpty())
            assertEquals(1, regFramework.handles.single().hooker.intercept(TestChain(method)))

            regRuntime.install("feature:point", method) { replaceTo(2) }
            assertEquals("one framework hook, swapped atomically", 1, regFramework.handles.size)
            assertEquals(1, regFramework.replaced)
            assertEquals(2, regFramework.handles.single().hooker.intercept(TestChain(method)))
            regRuntime.install("feature:dynamic", method) { before { setObjectExtra("a", 1) }; after { result = 5 } }
            regRuntime.install("feature:dynamic", method) { before { setObjectExtra("a", 2) }; after { result = 6 } }
            assertEquals(2, regFramework.handles.size)
            assertEquals(6, regFramework.handles.last().hooker.intercept(TestChain(method)))

            val sepFramework = FakeXposedFramework()
            val sepRuntime = ModernHookRuntime(sepFramework.xposed, engine)
            sepRuntime.install("a", method) { replaceTo(1) }
            sepRuntime.install("b", method) { replaceTo(2) }
            sepRuntime.install("a", pair) { replaceTo(3) }
            assertEquals(3, sepFramework.handles.size)
            assertEquals(0, sepFramework.replaced)

            assertEquals(ErrorPolicy.FRAMEWORK_PROTECTIVE, HookExceptionPolicy.PROTECT_HOST.errorPolicy)
            assertEquals(ErrorPolicy.PROPAGATE, HookExceptionPolicy.DELIVER_TO_HOST.errorPolicy)
            val policyFramework = FakeXposedFramework()
            val policyRuntime = ModernHookRuntime(policyFramework.xposed, engine)
            policyRuntime.install("default", method) { replaceTo(1) }
            policyRuntime.install("deliver", method, HookExceptionPolicy.DELIVER_TO_HOST) { replaceTo(1) }
            assertEquals(
                listOf(XposedInterface.ExceptionMode.PROTECTIVE, XposedInterface.ExceptionMode.PASSTHROUGH),
                policyFramework.modes
            )

            val changeFramework = FakeXposedFramework()
            val changeRuntime = ModernHookRuntime(changeFramework.xposed, engine)
            changeRuntime.install("point", method) { replaceTo(1) }
            changeRuntime.install("point", method, HookExceptionPolicy.DELIVER_TO_HOST) { replaceTo(2) }
            assertEquals(
                listOf(XposedInterface.ExceptionMode.PROTECTIVE, XposedInterface.ExceptionMode.PASSTHROUGH),
                changeFramework.modes
            )
            assertEquals(1, changeFramework.unhooks)
            assertEquals(0, changeFramework.replaced)

            val unhookFramework = FakeXposedFramework()
            val handle = ModernHookRuntime(unhookFramework.xposed, engine).install("observer", method) { after { } }
            assertEquals(0, unhookFramework.unhooks)
            handle.unhook()
            assertEquals(1, unhookFramework.unhooks)

            val legacyFramework = FakeXposedFramework(apiVersion = 101)
            try {
                ModernHookRuntime(legacyFramework.xposed, engine).install("unsupported", method) { intercept() }
                fail("API 101 is no longer supported")
            } catch (expected: IllegalStateException) {
                assertEquals("Modern API 102 or newer is required", expected.message)
            }
            assertTrue(legacyFramework.handles.isEmpty())

            val badConstFramework = FakeXposedFramework()
            try {
                ModernHookRuntime(badConstFramework.xposed, engine).install("bad-constant", method) { replaceTo("not an int") }
                fail("an incompatible constant must not install")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message, expected.message!!.contains("was not installed"))
            }
            assertTrue(badConstFramework.handles.isEmpty())

            val invalidExeFramework = FakeXposedFramework()
            val invalidExeRuntime = ModernHookRuntime(invalidExeFramework.xposed, engine)
            assertThrows(IllegalArgumentException::class.java) { invalidExeRuntime.install("abstract", abstractMethod) { before { } } }
            assertThrows(IllegalArgumentException::class.java) { invalidExeRuntime.install("interface", interfaceMethod) { before { } } }
            assertThrows(IllegalArgumentException::class.java) { invalidExeFramework.xposed.hook(abstractMethod) }
            assertThrows(IllegalArgumentException::class.java) { invalidExeFramework.xposed.hook(interfaceMethod) }

            val shapeFramework = FakeXposedFramework()
            val shapeRuntime = ModernHookRuntime(shapeFramework.xposed, engine)
            shapeRuntime.install("shape", method) { replaceTo(1) }
            shapeRuntime.install("shape", method) { after { result = 2 } }
            assertEquals("no stacked registrations after the shape changed", 1, shapeFramework.handles.size - shapeFramework.unhooks)
        }
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
        val success = ModernMemberHookCreator(method).apply { replaceResultOnSuccess(99) }
        assertFalse("success replacements always use the sdk success channel", success.usesDirectChain(HookDispatchEngine.DIRECT))
    }

    @Test
    fun `hook ids normalize awkward inputs isolate class loaders and match sha256 hex table`() {
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
        listOf("", "plain", "a b", "<init>", "中文", "a/b:c.d-e_f").forEach { ModernHookIds.of(it, method) }

        fun referenceHashId(id: String, executable: Executable): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            fun feed(text: String) {
                digest.update(text.toByteArray(Charsets.UTF_8))
                digest.update(0)
            }
            feed(id)
            feed(executable.declaringClass.name)
            feed(executable.name)
            for (type in executable.parameterTypes) feed(type.name)
            val suffix = digest.digest().take(10).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val safe = buildString {
                for (c in id) {
                    if (length == 64) break
                    val ok = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' ||
                        c == '.' || c == '_' || c == '-' || c == ':' || c == '/'
                    append(if (ok) c else '_')
                }
            }
            return "bil:$safe:$suffix"
        }

        val executables = listOf<Executable>(
            method, pair, Fixture::class.java.getDeclaredConstructor(), ReturnFixture::class.java.getDeclaredMethod("voidValue")
        )
        val ids = mutableListOf(
            "", "plain", "free-copy:comment-high:${method.toGenericString()}", "中文/emoji:🎉#tag", "x".repeat(80)
        )
        for (i in 0 until 40) ids += "probe-$i"

        val seenHexChars = hashSetOf<Char>()
        for (exe in executables) {
            for (rawId in ids) {
                val expected = referenceHashId(rawId, exe)
                val actual = ModernHookIds.of(rawId, exe).value()
                assertEquals(expected, actual)
                val hexPart = actual.substringAfterLast(':')
                assertEquals(20, hexPart.length)
                hexPart.forEach { ch ->
                    assertTrue("hex char must be lowercase 0-9a-f: $ch", ch in '0'..'9' || ch in 'a'..'f')
                    seenHexChars += ch
                }
            }
        }
        assertEquals("all 16 lowercase hex digits should be exercised", 16, seenHexChars.size)

        val resource = SameNameTarget::class.java.name.replace('.', '/') + ".class"
        val classBytes = requireNotNull(javaClass.classLoader!!.getResourceAsStream(resource)).use { it.readBytes() }
        val loaderA = object : ClassLoader(null) {
            fun define(): Class<*> = defineClass(SameNameTarget::class.java.name, classBytes, 0, classBytes.size)
        }
        val loaderB = object : ClassLoader(null) {
            fun define(): Class<*> = defineClass(SameNameTarget::class.java.name, classBytes, 0, classBytes.size)
        }
        val methodA = loaderA.define().getDeclaredMethod("ping", String::class.java)
        val methodB = loaderB.define().getDeclaredMethod("ping", String::class.java)
        assertNotEquals(loaderA, loaderB)
        assertNotEquals(ModernHookIds.of("same", methodA).value(), ModernHookIds.of("same", methodB).value())
    }

    @Test
    fun `fault attribution reports protected feature callback bugs once and respects unhooks`() {
        val constructor = Fixture::class.java.getDeclaredConstructor()
        for (engine in HookDispatchEngine.values()) {
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

            val cleanFramework = FakeXposedFramework()
            val cleanRuntime = ModernHookRuntime(cleanFramework.xposed, engine)
            cleanRuntime.install("a", method) { faultOwner = "feature_a"; after { result = (result as? Int ?: 0) + 1 } }
            cleanFramework.handles[0].hooker.intercept(TestChain(method))
            try {
                cleanFramework.handles[0].hooker.intercept(TestChain(method) { _, _ -> throw java.io.IOException("host failure") })
            } catch (_: Exception) {
            }
            assertTrue(faults(cleanRuntime).isEmpty())

            val watchFramework = FakeXposedFramework()
            val watchRuntime = ModernHookRuntime(watchFramework.xposed, engine)
            val bug = IllegalStateException("bug")
            watchRuntime.install("no-owner", method) { after { throw bug } }
            watchRuntime.install("deliver", pair, HookExceptionPolicy.DELIVER_TO_HOST) { faultOwner = "feature_d"; before { throw bug } }
            val gone = watchRuntime.install("gone", method) { faultOwner = "feature_g"; before { throw bug } }
            bug.leaks(watchFramework.handles[0].hooker, TestChain(method))
            bug.leaks(watchFramework.handles[1].hooker, TestChain(pair, arguments = arrayOf(1, 2)))
            bug.leaks(watchFramework.handles[2].hooker, TestChain(method, arguments = arrayOf(1, 2)))
            gone.unhook()
            assertTrue(faults(watchRuntime).isEmpty())

            val ctorFramework = FakeXposedFramework()
            val ctorRuntime = ModernHookRuntime(ctorFramework.xposed, engine)
            ctorRuntime.install("ctor", constructor) { faultOwner = "feature_c"; after { if (thisObject != null) throw failure } }
            val ctorHooker = ctorFramework.handles.single().hooker
            try {
                ctorHooker.intercept(TestChain(constructor, Fixture()) { _, _ -> throw java.io.IOException("host constructor failed") })
            } catch (_: Exception) {
            }
            failure.leaks(ctorHooker, TestChain(constructor, Fixture()) { _, _ -> null })
            assertEquals(listOf("feature_c"), faults(ctorRuntime))

            val staleFramework = FakeXposedFramework()
            val staleRuntime = ModernHookRuntime(staleFramework.xposed, engine)
            val first = staleRuntime.install("rebound", method) { faultOwner = "feature_old"; before { throw bug } }
            val second = staleRuntime.install("rebound", method) { faultOwner = "feature_new"; before { throw bug } }
            val unhooksBeforeStale = staleFramework.unhooks
            first.unhook()
            first.unhook()
            assertEquals("stale handle must not unhook active replacement", unhooksBeforeStale, staleFramework.unhooks)
            bug.leaks(staleFramework.handles.last().hooker, TestChain(method, arguments = arrayOf(1, 2)))
            assertEquals("active fault owner must stay bound after stale unhook", listOf("feature_new"), faults(staleRuntime))
            second.unhook()
            second.unhook()
            assertEquals(unhooksBeforeStale + 1, staleFramework.unhooks)
            runCatching { staleFramework.handles.last().hooker.intercept(TestChain(method, arguments = arrayOf(1, 2))) }
            assertTrue(faults(staleRuntime).isEmpty())
        }
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

    private fun migrated(before: BeforeAction, after: AfterAction, original: Original, engine: HookDispatchEngine): Observed {
        val calls = intArrayOf(0)
        val seen = arrayOfNulls<List<Any?>>(1)
        val incoming = arrayOf<Any?>(1, 2)
        val chain = TestChain(method, null, incoming, scripted(original, calls, seen))
        return observe({ creatorFor(before, after).invoke(chain, engine) }, calls, seen)
    }

    @Test
    fun `every before after and original combination matches the pre-migration behaviour`() {
        for (engine in HookDispatchEngine.values()) {
            var combinations = 0
            for (before in BeforeAction.values()) for (after in AfterAction.values()) for (original in Original.values()) {
                val expected = legacy(before, after, original)
                val actual = migrated(before, after, original, engine)
                val label = "engine=$engine before=$before after=$after original=$original"
                assertEquals("$label original calls", expected.originalCalls, actual.originalCalls)
                assertEquals("$label arguments seen by the original", expected.originalArgs, actual.originalArgs)
                val expectedThrown =
                    if (engine == HookDispatchEngine.SDK && before == BeforeAction.NONE && original == Original.THROW &&
                        (after == AfterAction.THROW || after == AfterAction.SET_THROWABLE)) originalFailure
                    else expected.thrown
                assertSame("$label exception identity", expectedThrown, actual.thrown)
                assertEquals("$label result", expected.returned, actual.returned)
                combinations++
            }
            assertEquals(7 * 8 * 2, combinations)
        }
    }

    @Test
    fun `modern hook log and reflect access handle sinks primitives and concrete bridge resolution safely`() {
        try {
            val seen = mutableListOf<Pair<String, Throwable?>>()
            ModernHookLog.bindSink { message, throwable -> seen += message to throwable }
            val failure = IllegalStateException("x")
            ModernHookLog.info("a")
            ModernHookLog.error("b", failure)
            assertEquals(listOf("a" to null, "b" to failure), seen)

            ModernHookLog.bindSink { _, _ -> throw IllegalStateException("binder died") }
            ModernHookLog.info("a")
            ModernHookLog.error("b", RuntimeException())

            ModernHookLog.bindSink(null)
            ModernHookLog.info("a")
            ModernHookLog.error("b")
        } finally {
            ModernHookLog.bindSink(null)
        }

        val target = PrimitiveTarget()
        repeat(2) {
            assertEquals("bool:true", ReflectAccess.callMethod(target, "withBoolean", true))
            assertEquals("int:7", ReflectAccess.callMethod(target, "withInt", 7))
            assertEquals("long:8", ReflectAccess.callMethod(target, "withLong", 8L))
            assertEquals("double:1.5", ReflectAccess.callMethod(target, "withDouble", 1.5))
            assertEquals("char:x", ReflectAccess.callMethod(target, "withChar", 'x'))
            assertEquals("str:null", ReflectAccess.callMethod(target, "withNullable", null))
            assertEquals("str:ok", ReflectAccess.callMethod(target, "withNullable", "ok"))
            assertEquals("zero", ReflectAccess.callMethod(target, "zeroArg"))
            assertEquals(11, ReflectAccess.callStaticMethod(PrimitiveTarget::class.java, "staticSum", 4, 7))
        }
        assertTrue(classOf<java.lang.Boolean>().isPrimitive)
        assertEquals(java.lang.Boolean.TYPE, classOf<java.lang.Boolean>())
        assertEquals(java.lang.Boolean::class.java, classOf<java.lang.Boolean>(primitiveType = false))
        assertTrue(!classOf<java.lang.Boolean>(primitiveType = false).isPrimitive)
        assertEquals(Boolean::class.javaPrimitiveType, Boolean::class.java)
        assertEquals(Int::class.javaPrimitiveType, Int::class.java)
        assertTrue(Boolean::class.java.isPrimitive)

        val covariant = ConcreteCovariant()
        val chosen = ReflectAccess.findCompatibleMethod(ConcreteCovariant::class.java, "produce", arrayOf("hi"))
        assertNotNull(chosen)
        assertFalse(chosen!!.isBridge)
        assertFalse(chosen.isSynthetic)
        assertEquals(String::class.java, chosen.returnType)
        assertEquals("HI", chosen.invoke(covariant, "hi"))
        assertEquals("HI", ReflectAccess.callMethod(covariant, "produce", "hi"))
    }

    // --- 测试替身 ---------------------------------------------------------------------------------------------

    private class TestChain(
        private val member: Executable,
        private val receiver: Any? = null,
        private val arguments: Array<Any?> = emptyArray(),
        private val original: (Any?, Array<Any?>) -> Any? = { _, _ -> 3 }
    ) : XposedInterface.Chain {
        var calls = 0
        var argumentListReads = 0
        var argumentSlotReads = 0
        var argumentOverrideCalls = 0
        var lastPassedArgs: Array<Any?>? = null
        override fun getExecutable(): Executable = member
        override fun getThisObject(): Any? = receiver
        override fun getArgs(): List<Any?> {
            argumentListReads++
            return arguments.toList()
        }
        override fun getArg(index: Int): Any? {
            argumentSlotReads++
            return arguments[index]
        }
        override fun proceed(): Any? {
            calls++
            lastPassedArgs = arguments
            return original(receiver, arguments)
        }
        override fun proceed(args: Array<Any?>): Any? {
            calls++
            argumentOverrideCalls++
            lastPassedArgs = args
            return original(receiver, args)
        }
        override fun proceedWith(thisObject: Any): Any? = proceedWith(thisObject, arguments)
        override fun proceedWith(thisObject: Any, args: Array<Any?>): Any? {
            calls++
            lastPassedArgs = args
            return original(thisObject, args)
        }
    }

    private class BorrowPlayer {
        @Suppress("UNUSED_PARAMETER") fun setOutputSurface(surface: Any) = Unit
    }

    class SameNameTarget {
        @Suppress("unused")
        fun ping(value: String): String = value
    }

    class ReadAndWriteArgsTarget {
        fun combine(a: String, b: String): String = "$a:$b"
    }

    interface GenericBase<T> {
        fun produce(input: T): Any
    }

    open class ConcreteCovariant : GenericBase<String> {
        override fun produce(input: String): String = input.uppercase()
    }

    @Suppress("unused")
    private class PrimitiveTarget {
        fun withBoolean(value: Boolean): String = "bool:$value"
        fun withInt(value: Int): String = "int:$value"
        fun withLong(value: Long): String = "long:$value"
        fun withDouble(value: Double): String = "double:$value"
        fun withChar(value: Char): String = "char:$value"
        fun withNullable(value: String?): String = "str:$value"
        fun zeroArg(): String = "zero"

        companion object {
            @JvmStatic
            fun staticSum(a: Int, b: Int): Int = a + b
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

    abstract class AbstractFixture {
        abstract fun render()
    }

    interface InterfaceFixture {
        fun applyModifier(value: Any?)
    }
}
