package com.Bilibili_Innocent_Lab.xposedmodule.hook.modern

import io.github.jichuo1.meguviz.hook.api.ErrorPolicy
import io.github.jichuo1.meguviz.hook.api.HookId
import io.github.jichuo1.meguviz.hook.backend.libxposed102.LibXposed102Backend
import io.github.jichuo1.meguviz.hook.backend.libxposed102.LibXposed102Context
import io.github.jichuo1.meguviz.hook.core.HookEnvironment
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Proxy

/**
 * 只替换 libxposed 的框架接口；MeguvizHook 后端、调度器与本模块的适配层都是生产代码。
 * 记录框架侧观察到的注册参数，并保留每个 hook 的当前 [XposedInterface.Hooker]，供测试用链直接调用。
 */
internal class FakeXposedFramework(private val apiVersion: Int = 102) {
    class FakeHandle(var hooker: XposedInterface.Hooker)

    val handles = mutableListOf<FakeHandle>()
    val modes = mutableListOf<XposedInterface.ExceptionMode>()
    val ids = mutableListOf<String>()
    var replaced = 0
    var unhooks = 0

    val xposed: XposedInterface = proxy { name, _ ->
        when (name) {
            "getApiVersion" -> apiVersion
            "getFrameworkName" -> "fake"
            "getFrameworkVersion" -> "1"
            "getFrameworkVersionCode" -> 1L
            "hook" -> newBuilder()
            "log" -> null
            else -> error("unexpected framework call: $name")
        }
    }

    fun environment(): HookEnvironment =
        HookEnvironment.create(LibXposed102Backend(xposed, LibXposed102Context("test", javaClass.classLoader, 1L)))

    private fun newBuilder(): XposedInterface.HookBuilder {
        lateinit var builder: XposedInterface.HookBuilder
        builder = proxy { name, args ->
            when (name) {
                "setPriority" -> builder
                "setExceptionMode" -> builder.also { modes += args[0] as XposedInterface.ExceptionMode }
                "setId" -> builder.also { ids += args[0] as String }
                "intercept" -> handle(FakeHandle(args[0] as XposedInterface.Hooker).also { handles += it })
                else -> error("unexpected builder call: $name")
            }
        }
        return builder
    }

    private fun handle(state: FakeHandle): XposedInterface.HookHandle = proxy { name, args ->
        when (name) {
            "unhook" -> { unhooks++; null }
            "replaceHook" -> {
                replaced++
                state.hooker = args[0] as XposedInterface.Hooker
                handle(state)
            }
            else -> error("unexpected handle call: $name")
        }
    }

    private inline fun <reified T> proxy(crossinline invoke: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, member, args ->
            invoke(member.name, args ?: emptyArray())
        } as T
}

/** 经真实的 SDK 环境与 API 102 后端安装当前 DSL 状态，再用测试链调用框架 hooker。 */
internal fun ModernMemberHookCreator.invoke(
    chain: XposedInterface.Chain,
    engine: HookDispatchEngine = HookDispatchEngine.DIRECT
): Any? {
    // 与生产相同的引擎选择：直接引擎与构造器走框架链的入口，其余经真实的 SDK 调度器。
    if (usesDirectChain(engine)) return interceptChain(chain)
    val framework = FakeXposedFramework()
    val receipt = framework.environment().install(toSpec(HookId.of("test"), ErrorPolicy.FRAMEWORK_PROTECTIVE))
    check(receipt.isInstalled) { receipt.toString() }
    return framework.handles.single().hooker.intercept(chain)
}
