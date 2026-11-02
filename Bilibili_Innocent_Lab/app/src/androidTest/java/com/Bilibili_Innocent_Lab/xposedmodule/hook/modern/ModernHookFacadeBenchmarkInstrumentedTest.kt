package com.Bilibili_Innocent_Lab.xposedmodule.hook.modern

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jichuo1.meguviz.hook.api.AfterCallback
import io.github.jichuo1.meguviz.hook.api.AroundCallback
import io.github.jichuo1.meguviz.hook.api.BeforeCallback
import io.github.jichuo1.meguviz.hook.api.ErrorPolicy
import io.github.jichuo1.meguviz.hook.api.HookId
import io.github.jichuo1.meguviz.hook.api.HookOwner
import io.github.jichuo1.meguviz.hook.api.HookSpec
import io.github.jichuo1.meguviz.hook.api.ParameterAccess
import io.github.jichuo1.meguviz.hook.api.TargetSpec
import io.github.jichuo1.meguviz.hook.backend.libxposed102.LibXposed102Backend
import io.github.jichuo1.meguviz.hook.backend.libxposed102.LibXposed102Context
import io.github.jichuo1.meguviz.hook.core.HookEnvironment
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import java.lang.reflect.Proxy
import java.util.Arrays
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 在 ART 上量化 Hook 门面开销：迁移前的控制流（现仍用于构造器）对比 MeguvizHook 调度器路径。
 * 框架链是固定数据的替身，所以只度量“门面 + 调度”本身，不含真实框架的拦截开销；
 * 数字用于相对比较，绝对值取决于设备，真机功耗与尾延迟未在此度量。
 */
@RunWith(AndroidJUnit4::class)
class ModernHookFacadeBenchmarkInstrumentedTest {
    class Target {
        fun sum(a: Int, b: Int): Int = a + b
        fun lookup(key: String?, fallback: Int): Int = fallback
    }

    private val lookup = Target::class.java.getDeclaredMethod("lookup", String::class.java, Int::class.javaPrimitiveType)

    private val method = Target::class.java.getDeclaredMethod("sum", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    private val target = Target()
    private val arguments = arrayOf<Any?>(3, 4)
    private val original: Any? = 7

    @Volatile private var sink: Any? = null

    /** getArgs 每次新建一个列表包装，贴近真实框架链“按需生成参数视图”的分配行为。 */
    private inner class BenchChain : XposedInterface.Chain {
        override fun getExecutable(): Executable = method
        override fun getThisObject(): Any? = target
        override fun getArgs(): List<Any?> = Arrays.asList(*arguments)
        override fun getArg(index: Int): Any? = arguments[index]
        override fun proceed(): Any? = original
        override fun proceed(args: Array<Any?>): Any? = original
        override fun proceedWith(thisObject: Any): Any? = original
        override fun proceedWith(thisObject: Any, args: Array<Any?>): Any? = original
    }

    private inline fun <reified T> proxy(crossinline invoke: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, member, args ->
            invoke(member.name, args ?: emptyArray())
        } as T

    private fun sdkHooker(
        executable: Executable = method,
        engine: HookDispatchEngine = HookDispatchEngine.SDK,
        block: ModernMemberHookCreator.() -> Unit
    ): XposedInterface.Hooker {
        var captured: XposedInterface.Hooker? = null
        lateinit var builder: XposedInterface.HookBuilder
        builder = proxy { name, args ->
            when (name) {
                "setPriority", "setExceptionMode", "setId" -> builder
                "intercept" -> {
                    captured = args[0] as XposedInterface.Hooker
                    proxy<XposedInterface.HookHandle> { _, _ -> null }
                }
                else -> error(name)
            }
        }
        val framework: XposedInterface = proxy { name, _ ->
            when (name) {
                "getApiVersion" -> 102
                "getFrameworkName" -> "bench"
                "getFrameworkVersion" -> "1"
                "getFrameworkVersionCode" -> 1L
                "hook" -> builder
                "log" -> null
                else -> error(name)
            }
        }
        val runtime = ModernHookRuntime(framework, engine)
        runtime.attachHost("bench", javaClass.classLoader!!)
        runtime.install("bench", executable, block = block)
        return checkNotNull(captured)
    }

    private fun legacyHooker(block: ModernMemberHookCreator.() -> Unit): XposedInterface.Hooker {
        val creator = ModernMemberHookCreator(method).apply(block)
        return XposedInterface.Hooker { chain -> creator.interceptChain(chain) }
    }

    private fun measure(hooker: XposedInterface.Hooker, chain: XposedInterface.Chain = BenchChain()): Long {
        repeat(WARMUP) { sink = hooker.intercept(chain) }
        val rounds = LongArray(ROUNDS)
        for (round in 0 until ROUNDS) {
            val start = System.nanoTime()
            for (i in 0 until ITERATIONS) sink = hooker.intercept(chain)
            rounds[round] = (System.nanoTime() - start) / ITERATIONS
        }
        rounds.sort()
        return rounds[ROUNDS / 2]
    }

    private class Shape(val name: String, val block: ModernMemberHookCreator.() -> Unit)

    @Test
    fun compareFacadeShapes() {
        val shapes = listOf(
            Shape("constant") { replaceTo(1) },
            Shape("replace-dynamic-constant") { replaceAny { 1 } },
            Shape("after-observe") { after { sink = argOrNull(0) } },
            Shape("before-set-result") { before { result = 1 } },
            Shape("before-observe") { before { sink = argOrNull(0) } },
            Shape("before+after observe") { before { setObjectExtra("k", 1) }; after { sink = getObjectExtra("k") } },
            Shape("replace-read-arg") { replaceAny { argOrNull(0) } },
            Shape("after-read") { after { if (argOrNull(0) == 3) result = result } },
            Shape("before-read") { before { argOrNull(0) } },
            Shape("before-write-arg") { before { args[0] = 5 } },
            Shape("before+after") {
                before { setObjectExtra("k", 1) }
                after { result = (result as Int) + (getObjectExtra("k") as Int) }
            },
            Shape("before+after (watched)") {
                faultOwner = "bench"
                before { setObjectExtra("k", 1) }
                after { result = (result as Int) + (getObjectExtra("k") as Int) }
            },
            Shape("after-read (watched)") {
                faultOwner = "bench"
                after { if (argOrNull(0) == 3) result = result }
            }
        )
        val lines = StringBuilder("shape\tpre_migration_ns\truntime_direct_ns\truntime_sdk_ns\n")
        for (shape in shapes) {
            // 交替测量两次取较小，削弱 JIT 编译与调度噪声。
            val legacy = minOf(measure(legacyHooker(shape.block)), measure(legacyHooker(shape.block)))
            val direct = minOf(
                measure(sdkHooker(engine = HookDispatchEngine.DIRECT, block = shape.block)),
                measure(sdkHooker(engine = HookDispatchEngine.DIRECT, block = shape.block))
            )
            val sdk = minOf(measure(sdkHooker(block = shape.block)), measure(sdkHooker(block = shape.block)))
            lines.append("${shape.name}\t$legacy\t$direct\t$sdk\n")
        }
        Log.i(TAG, "\n$lines")
        assertTrue(lines.isNotEmpty())
    }

    private fun sdkSpecHooker(
        executable: Executable = method,
        configure: HookSpec.Builder.() -> Unit
    ): XposedInterface.Hooker {
        var captured: XposedInterface.Hooker? = null
        lateinit var builder: XposedInterface.HookBuilder
        builder = proxy { name, args ->
            when (name) {
                "setPriority", "setExceptionMode", "setId" -> builder
                "intercept" -> {
                    captured = args[0] as XposedInterface.Hooker
                    proxy<XposedInterface.HookHandle> { _, _ -> null }
                }
                else -> error(name)
            }
        }
        val framework: XposedInterface = proxy { name, _ ->
            when (name) {
                "getApiVersion" -> 102
                "getFrameworkName" -> "bench"
                "getFrameworkVersion" -> "1"
                "getFrameworkVersionCode" -> 1L
                "hook" -> builder
                "log" -> null
                else -> error(name)
            }
        }
        val environment = HookEnvironment.create(
            LibXposed102Backend(framework, LibXposed102Context("bench", javaClass.classLoader, 1L))
        )
        val spec = HookSpec.Builder(HookId.of("floor"), HookOwner.of("bench"), TargetSpec.ofMember(executable))
            .errorPolicy(ErrorPolicy.FRAMEWORK_PROTECTIVE).apply(configure).build()
        check(environment.install(spec).isInstalled)
        return checkNotNull(captured)
    }

    private inner class LookupChain(private val key: String?) : XposedInterface.Chain {
        private val values = arrayOf<Any?>(key, 9)
        override fun getExecutable(): Executable = lookup
        override fun getThisObject(): Any? = target
        override fun getArgs(): List<Any?> = Arrays.asList(*values)
        override fun getArg(index: Int): Any? = values[index]
        override fun proceed(): Any? = 9
        override fun proceed(args: Array<Any?>): Any? = 9
        override fun proceedWith(thisObject: Any): Any? = 9
        override fun proceedWith(thisObject: Any, args: Array<Any?>): Any? = 9
    }

    @Test
    fun sdkFloor() {
        val noop = XposedInterface.Hooker { chain -> chain.proceed() }
        val rows = linkedMapOf<String, Long>()
        rows["framework passthrough (hooker proceeds)"] = measure(noop)
        rows["sdk NO_ARGS before"] = measure(sdkSpecHooker { access(ParameterAccess.NO_ARGS).before(BeforeCallback { }) })
        rows["sdk READ_ARGS_VIEW before (no read)"] = measure(sdkSpecHooker { access(ParameterAccess.READ_ARGS_VIEW).before(BeforeCallback { }) })
        rows["sdk READ_ARGS_VIEW before (read arg)"] = measure(sdkSpecHooker {
            access(ParameterAccess.READ_ARGS_VIEW).before(BeforeCallback { sink = (it as io.github.jichuo1.meguviz.hook.api.ReadArgsInvocation).getArgument(0) })
        })
        rows["sdk READ_ARGS_VIEW after"] = measure(sdkSpecHooker { access(ParameterAccess.READ_ARGS_VIEW).after(AfterCallback { _, _ -> }) })
        rows["sdk MUTABLE_ARGS before"] = measure(sdkSpecHooker { access(ParameterAccess.MUTABLE_ARGS).before(BeforeCallback { }) })
        rows["sdk AROUND proceed"] = measure(sdkSpecHooker { around(AroundCallback { it.proceed() }) })
        rows["sdk replaceConstant"] = measure(sdkSpecHooker { replaceConstant(1) })
        rows["sdk replaceResultOnSuccess"] = measure(sdkSpecHooker { replaceResultOnSuccess(1) })
        val conditional = sdkSpecHooker(lookup) { replaceIfStringArgumentEquals(0, "hit", 5) }
        rows["sdk replaceIfStringArgumentEquals (match)"] = measure(conditional, LookupChain("hit"))
        rows["sdk replaceIfStringArgumentEquals (miss)"] = measure(conditional, LookupChain("other"))
        // 门面里“按键字符串比较后返回常量”的写法，作为条件通道的对照。
        val legacyConditional = legacyLookup()
        rows["legacy facade: string compare then constant (match)"] = measure(legacyConditional, LookupChain("hit"))
        rows["legacy facade: string compare then constant (miss)"] = measure(legacyConditional, LookupChain("other"))
        val sdkFacadeConditional = sdkLookup()
        rows["sdk facade: string compare then constant (match)"] = measure(sdkFacadeConditional, LookupChain("hit"))
        rows["sdk facade: string compare then constant (miss)"] = measure(sdkFacadeConditional, LookupChain("other"))
        val lines = StringBuilder("floor\tns\n")
        rows.forEach { (name, ns) -> lines.append("$name\t$ns\n") }
        Log.i(TAG, "\n$lines")
        assertTrue(rows.isNotEmpty())
    }

    private fun legacyLookup(): XposedInterface.Hooker {
        val creator = ModernMemberHookCreator(lookup).apply { replaceAny { if (argOrNull(0) == "hit") 5 else 9 } }
        return XposedInterface.Hooker { chain -> creator.interceptChain(chain) }
    }

    private fun sdkLookup(): XposedInterface.Hooker = sdkHooker(lookup) { before { if (argOrNull(0) == "hit") result = 5 } }

    private companion object {
        const val TAG = "HookFacadeBench"
        const val WARMUP = 300_000
        const val ROUNDS = 7
        const val ITERATIONS = 400_000
    }
}
