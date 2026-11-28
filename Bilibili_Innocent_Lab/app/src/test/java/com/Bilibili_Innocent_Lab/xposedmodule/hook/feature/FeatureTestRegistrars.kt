package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.invoke
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Modifier

// From ContentFilterTestRegistrar.kt
/** 让适配后的无参列表 getter 也经过真实 Modern 回调链。 */
internal class ContentFilterTestRegistrar(
    private val loader: ClassLoader,
    private val recorder: PlayerPortTestRegistrar = PlayerPortTestRegistrar()
) : HookRegistrar by recorder {
    override fun adapted(
        id: String,
        point: VersionAdapter.HookPoint,
        exceptionPolicy: HookExceptionPolicy,
        block: ModernMemberHookCreator.() -> Unit
    ) {
        require(point.paramClassNames.isNullOrEmpty())
        recorder.exact(id, loader.loadClass(point.className), point.methodName, block = block)
    }

    fun items(id: String, receiver: Any, source: List<*>): List<*> =
        recorder.invoke(id, receiver, original = { source }) as List<*>
}

// From PlayerPortTestRegistrar.kt
/** 通过真实 Modern DSL 调用测试链，覆盖参数复制、原方法异常和 before/after 顺序。 */
internal class PlayerPortTestRegistrar(var failId: String? = null) : HookRegistrar by TestHookRegistrar {
    override val rootRegistrar: HookRegistrar get() = this
    data class Entry(val member: Executable, val callback: ModernMemberHookCreator)
    val hooks = linkedMapOf<String, Entry>()
    // Map 会覆盖相同 id；另存实际尝试次数，避免重复注册被测试记录掩盖。
    val registrationAttempts = mutableListOf<Pair<String, Executable>>()
    override fun exact(id: String, owner: Class<*>, methodName: String, vararg parameterTypes: Class<*>,
        block: ModernMemberHookCreator.() -> Unit) = record(id, owner.getDeclaredMethod(methodName, *parameterTypes), block)
    override fun constructor(id: String, constructor: Constructor<*>, block: ModernMemberHookCreator.() -> Unit) =
        record(id, constructor, block)
    private fun record(id: String, member: Executable, block: ModernMemberHookCreator.() -> Unit) {
        registrationAttempts += id to member
        require(!Modifier.isAbstract(member.modifiers)) { "Cannot hook abstract executable: $member" }
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

// From TestHookRegistrar.kt
internal object TestHookRegistrar : HookRegistrar {
    override fun first(
        id: String,
        className: String,
        methodName: String,
        block: ModernMemberHookCreator.() -> Unit
    ) = Unit

    override fun all(
        id: String,
        className: String,
        methodName: String,
        block: ModernMemberHookCreator.() -> Unit
    ) = Unit

    override fun exact(
        id: String,
        owner: Class<*>,
        methodName: String,
        vararg parameterTypes: Class<*>,
        block: ModernMemberHookCreator.() -> Unit
    ) = Unit

    override fun adapted(
        id: String,
        point: VersionAdapter.HookPoint,
        exceptionPolicy: HookExceptionPolicy,
        block: ModernMemberHookCreator.() -> Unit
    ) = Unit

    override fun constructor(
        id: String,
        constructor: Constructor<*>,
        block: ModernMemberHookCreator.() -> Unit
    ) = Unit
}
