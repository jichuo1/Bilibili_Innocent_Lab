package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Executable

/** 通过真实 Modern DSL 调用测试链，覆盖参数复制、原方法异常和 before/after 顺序。 */
internal class PlayerPortTestRegistrar(private val failId: String? = null) : HookRegistrar by TestHookRegistrar {
    data class Entry(val member: Executable, val callback: ModernMemberHookCreator)
    val hooks = linkedMapOf<String, Entry>()
    override fun exact(id: String, owner: Class<*>, methodName: String, vararg parameterTypes: Class<*>,
        block: ModernMemberHookCreator.() -> Unit) = record(id, owner.getDeclaredMethod(methodName, *parameterTypes), block)
    override fun constructor(id: String, constructor: Constructor<*>, block: ModernMemberHookCreator.() -> Unit) =
        record(id, constructor, block)
    private fun record(id: String, member: Executable, block: ModernMemberHookCreator.() -> Unit) {
        check(id != failId) { "simulated registration failure" }
        hooks[id] = Entry(member, ModernMemberHookCreator(member).apply(block))
    }
    fun invoke(id: String, receiver: Any? = null, args: Array<Any?> = emptyArray(),
        original: (Array<Any?>) -> Any? = { null }): Any? {
        val entry = hooks.getValue(id)
        return entry.callback.invoke(object : XposedInterface.Chain {
            override fun getExecutable() = entry.member
            override fun getThisObject() = receiver
            override fun getArgs() = args.toList()
            override fun getArg(index: Int) = args[index]
            override fun proceed() = original(args)
            override fun proceed(args: Array<Any?>) = original(args)
            override fun proceedWith(thisObject: Any) = original(args)
            override fun proceedWith(thisObject: Any, args: Array<Any?>) = original(args)
        })
    }
}
