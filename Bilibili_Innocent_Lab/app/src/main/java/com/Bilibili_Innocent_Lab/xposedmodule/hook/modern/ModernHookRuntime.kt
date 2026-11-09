package com.Bilibili_Innocent_Lab.xposedmodule.hook.modern

import android.util.Log
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ModernApiSupport
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.StartupTrace
import io.github.jichuo1.meguviz.hook.api.AfterCallback
import io.github.jichuo1.meguviz.hook.api.AroundCallback
import io.github.jichuo1.meguviz.hook.api.BeforeCallback
import io.github.jichuo1.meguviz.hook.api.CallOutcome
import io.github.jichuo1.meguviz.hook.api.ErrorPolicy
import io.github.jichuo1.meguviz.hook.api.HookId
import io.github.jichuo1.meguviz.hook.api.HookOwner
import io.github.jichuo1.meguviz.hook.api.HookRegistration
import io.github.jichuo1.meguviz.hook.api.HookSpec
import io.github.jichuo1.meguviz.hook.api.MutableArgsInvocation
import io.github.jichuo1.meguviz.hook.api.ParameterAccess
import io.github.jichuo1.meguviz.hook.api.ReadArgsInvocation
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt
import io.github.jichuo1.meguviz.hook.api.TargetSpec
import io.github.jichuo1.meguviz.hook.backend.libxposed102.LibXposed102Backend
import io.github.jichuo1.meguviz.hook.backend.libxposed102.LibXposed102Context
import io.github.jichuo1.meguviz.hook.core.HookEnvironment
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.security.MessageDigest

/**
 * 模块侧的 Hook 异常语义；由 [ModernHookRuntime] 映射到 MeguvizHook 的 [ErrorPolicy]，
 * 业务安装器因此不直接持有框架或 SDK 类型。
 */
internal enum class HookExceptionPolicy {
    /**
     * 默认。回调抛出的任何异常由框架捕获并记录，调用**按“没装 Hook”继续**
     * （`FRAMEWORK_PROTECTIVE`，SDK 的 API 102 后端显式映射为 `ExceptionMode.PROTECTIVE`）。
     * 这是宿主稳定性的主要保障：模块回调里的反射失败、结构漂移、NPE 都不会传到宿主栈上。
     */
    PROTECT_HOST,

    /**
     * 回调异常原样传给宿主调用方（`PROPAGATE`，映射为 `ExceptionMode.PASSTHROUGH`）。
     *
     * **只用于“让被 Hook 方法以指定异常结束”这一种诉求**（使用者：屏蔽官方更新，
     * 它必须把宿主自己的 `LatestVersionException` 交回调用方；无痕模式的 ighttp 边界，
     * 被扣下的请求必须以 IOException 结束而不是继续发出）。选它的 Hook 有义务保证
     * 回调体内除了这条**刻意**的异常之外不会逃逸任何东西——因为这里没有框架兜底。
     *
     * 背景：`PROTECTIVE` 的契约是“捕获并按没装 Hook 继续”，所以在该模式下
     * `ModernHookParam.throwable` 永远到不了宿主。这与 2026-08-30 记录的
     * “模块回调异常 vs 传递给宿主的 throwable”是同一个坑在 Modern API 下的新形态。
     */
    DELIVER_TO_HOST;

    val errorPolicy: ErrorPolicy
        get() = when (this) {
            PROTECT_HOST -> ErrorPolicy.FRAMEWORK_PROTECTIVE
            DELIVER_TO_HOST -> ErrorPolicy.PROPAGATE
        }

    /** 不经 SDK、直接走框架拦截器的构造器 Hook 使用的同一语义。 */
    val frameworkMode: XposedInterface.ExceptionMode
        get() = when (this) {
            PROTECT_HOST -> XposedInterface.ExceptionMode.PROTECTIVE
            DELIVER_TO_HOST -> XposedInterface.ExceptionMode.PASSTHROUGH
        }
}

/** 已安装 Hook 的收回句柄；一次性观察者（如漫游缓存的类加载观察）用它在完成后卸载。 */
internal class ModernHookHandle internal constructor(private val close: () -> Unit) {
    fun unhook() {
        close()
    }
}

/** 功能回调由谁调度；两者语义相同，差别只在每次调用的开销。 */
internal enum class HookDispatchEngine {
    /** 直接走框架的拦截器，沿用迁移前的 before/after 控制流。 */
    DIRECT,

    /** 走 MeguvizHook 的调度器（API 102 后端）。 */
    SDK
}

/**
 * 把项目既有 before/after/replace 语义映射到 MeguvizHook（API 102 后端）。
 *
 * 该层只兼容项目实际使用的最小 DSL，不模拟 Legacy XposedBridge，也不允许回调保存框架或 SDK 的借用调用。
 * 业务安装器看到的仍是 [ModernMemberHookCreator]。登记、身份、原子替换、异常模式映射、收回句柄
 * 与故障归因始终由本层负责；只有**回调的调度**可以在两个引擎之间选择（[HookDispatchEngine]）：
 *
 * - 常量替换总是走 SDK 的常量通道：与直接实现一样快（约 4 ns 对 11 ns），并在安装期校验常量类型；
 * - 构造器总是走直接引擎：SDK 约定 `HookInvocation.thisObject()` 对构造器恒为 `null`，而本模块的
 *   构造器 Hook 要读取正在构造的对象（构造后登记、补写字段）；
 * - 其余回调默认走直接引擎。在 ART 模拟器上对同一替身链实测（中位数，ns/次）：
 *   `replaceAny` 154 对 701、`after` 79 对 585、`before` 80 对 1119、`before`+`after` 208 对 1612；
 *   SDK 1.0.0 的 `MUTABLE_ARGS` 与 `AROUND` 形状尤其贵（空回调 791 与 1160）。本模块有逐帧级的
 *   Compose 与列表 Hook，这笔开销会直接落在帧时间上，所以不为“统一走 SDK”而退步。
 *   SDK 回调路径同样完整可用并有相同的测试覆盖，调度成本降下来之后把默认值改成 [HookDispatchEngine.SDK] 即可。
 */
internal class ModernHookRuntime(
    private val module: XposedInterface,
    private val callbackEngine: HookDispatchEngine = HookDispatchEngine.DIRECT
) {
    private class HostIdentity(val processName: String, val classLoader: ClassLoader)
    private class Installed(val registration: HookRegistration, val policy: ErrorPolicy)
    private class InstalledDirect(val handle: XposedInterface.HookHandle, val policy: HookExceptionPolicy)

    private val lock = Any()
    private var host: HostIdentity? = null
    private var environment: HookEnvironment? = null
    private val installed = HashMap<HookId, Installed>()
    private val installedDirect = HashMap<HookId, InstalledDirect>()
    private var faultBridge: HookFaultBridge? = null

    /**
     * 宿主进程与类加载器必须早于第一次安装登记：SDK 的计划把它们作为身份固定下来。
     * 环境一旦创建就不再接受更换，晚到的登记只会留下一条日志。
     */
    fun attachHost(processName: String, hostClassLoader: ClassLoader) {
        synchronized(lock) {
            if (environment != null) {
                log("[BIL] 宿主身份晚于首次安装登记，沿用已创建的 Hook 环境(process=$processName)")
                return
            }
            host = HostIdentity(processName, hostClassLoader)
        }
    }

    private fun faults(): HookFaultBridge =
        faultBridge ?: HookFaultBridge(host?.processName ?: "unknown").also { faultBridge = it }

    /**
     * 取走逃出功能回调的异常并按功能回调 [report]（每个功能一次）。
     * 在诊断快照的读取线程上调用；没有任何受守护的 Hook 时是空操作。
     */
    fun drainFaults(report: (String) -> Unit): Int {
        val bridge = synchronized(lock) { faultBridge } ?: return 0
        return bridge.drain(report)
    }

    private fun environment(): HookEnvironment {
        environment?.let { return it }
        val identity = host ?: HostIdentity("unknown", ModernHookRuntime::class.java.classLoader!!)
        val backend = LibXposed102Backend(module, LibXposed102Context(identity.processName, identity.classLoader, CODE_EPOCH))
        return HookEnvironment.create(backend).also { environment = it }
    }

    fun install(
        id: String,
        executable: Executable,
        exceptionPolicy: HookExceptionPolicy = HookExceptionPolicy.PROTECT_HOST,
        block: ModernMemberHookCreator.() -> Unit
    ): ModernHookHandle {
        return StartupTrace.measure("registration") { installMeasured(id, executable, exceptionPolicy, block) }
    }

    private fun installMeasured(
        id: String,
        executable: Executable,
        exceptionPolicy: HookExceptionPolicy,
        block: ModernMemberHookCreator.() -> Unit
    ): ModernHookHandle {
        check(module.apiVersion >= ModernApiSupport.MIN_API) { "Modern API 102 or newer is required" }
        val creator = ModernMemberHookCreator(executable).apply(block)
        val hookId = ModernHookIds.of(id, executable)
        // 只有保护模式的功能 Hook 才登记归属：DELIVER_TO_HOST 的异常是刻意交给宿主的，不是故障。
        val owner = creator.faultOwner?.takeIf { exceptionPolicy == HookExceptionPolicy.PROTECT_HOST }
        if (creator.usesDirectChain(callbackEngine)) {
            return synchronized(lock) { installDirect(hookId, executable, exceptionPolicy, creator, owner) }
        }
        return synchronized(lock) {
            installedDirect.remove(hookId)?.handle?.unhook()
            val bridge = if (owner != null) faults().also { it.bind(hookId, owner) } else faultBridge?.also { it.unbind(hookId) }
            val spec = creator.toSpec(hookId, exceptionPolicy.errorPolicy, bridge.takeIf { owner != null })
            val env = environment()
            val receipt = installOrReplace(env, spec, hookId)
            check(receipt.isInstalled) { "Hook $id was not installed: ${receipt.status()} (${receipt.message()})" }
            val registration = receipt.registration() ?: checkNotNull(installed[hookId]?.registration) {
                "Hook $id reported ${receipt.status()} without a registration"
            }
            installed[hookId] = Installed(registration, spec.errorPolicy())
            ModernHookHandle {
                registration.close()
                faultBridge?.unbind(hookId)
            }
        }
    }

    /** 直接引擎的登记：重复登记同一逻辑点时以最后一次为准，策略不变则原子换回调，策略改变则重装。 */
    private fun installDirect(
        hookId: HookId,
        executable: Executable,
        policy: HookExceptionPolicy,
        creator: ModernMemberHookCreator,
        owner: String?
    ): ModernHookHandle {
        // 同一逻辑点此前若由 SDK 引擎登记（例如先是常量、后改为回调），先收回，避免两条 Hook 叠在一起。
        installed.remove(hookId)?.registration?.close()
        val bridge = if (owner != null) faults().also { it.bind(hookId, owner) } else faultBridge?.also { it.unbind(hookId) }
        val onFault: ((Throwable) -> Unit)? = if (owner != null && bridge != null) { _ -> bridge.noteEscaped(hookId) } else null
        val hooker = XposedInterface.Hooker { chain -> creator.interceptChain(chain, onFault) }
        val previous = installedDirect[hookId]
        val handle = if (previous != null && previous.policy == policy) {
            previous.handle.replaceHook(hooker)
        } else {
            previous?.handle?.unhook()
            module.hook(executable).setExceptionMode(policy.frameworkMode).intercept(hooker)
        }
        installedDirect[hookId] = InstalledDirect(handle, policy)
        return ModernHookHandle {
            handle.unhook()
            faultBridge?.unbind(hookId)
        }
    }

    /**
     * 同一逻辑点（相同 ID 与相同目标）的重复安装以最后一次为准，与此前框架按 ID 替换的行为一致；
     * SDK 对同一逻辑 ID 默认不静默覆盖，所以这里显式走原子替换。
     */
    private fun installOrReplace(env: HookEnvironment, spec: HookSpec, hookId: HookId): RegistrationReceipt {
        val first = env.install(spec)
        if (first.status() != RegistrationReceipt.Status.ALREADY_INSTALLED &&
            first.status() != RegistrationReceipt.Status.CONFLICT) return first
        val previous = installed[hookId]
        if (previous != null && previous.policy != spec.errorPolicy()) {
            // 框架在替换时保留原 Hook 的异常模式，换策略只能关掉旧注册再装新的。
            previous.registration.close()
            return env.install(spec)
        }
        return env.replace(spec)
    }

    fun install(
        id: String,
        executable: Executable,
        callback: ModernMethodHook
    ): ModernHookHandle = install(id, executable) {
        before { callback.beforeHookedMethod(this) }
        after { callback.afterHookedMethod(this) }
    }

    fun log(message: String, throwable: Throwable? = null) {
        if (throwable == null) {
            module.log(Log.INFO, LOG_TAG, message)
        } else {
            module.log(Log.ERROR, LOG_TAG, message, throwable)
        }
    }

    private companion object {
        const val LOG_TAG = "BilibiliInnocentLab"

        /** 本代模块代码的代次；热重载接管后由新代使用更大的值。 */
        const val CODE_EPOCH = 1L
    }
}

/**
 * SDK 的逻辑 ID 只允许 `[A-Za-z0-9._:/-]` 且不超过 128 字符，并且在一个环境内全局唯一；
 * 本模块原先的身份是“框架里的（可执行体，ID）”，ID 里还有带空格和尖括号的签名文本。
 * 这里把二者合成一个稳定、可读前缀加摘要的 SDK ID。
 */
internal object ModernHookIds {
    private const val PREFIX = "bil:"
    private const val READABLE_MAX = 64

    fun of(id: String, executable: Executable): HookId {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(text: String) {
            digest.update(text.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        add(id)
        add(executable.declaringClass.name)
        add(executable.name)
        for (type in executable.parameterTypes) add(type.name)
        val hash = digest.digest().take(10).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return HookId.of(PREFIX + readable(id) + ":" + hash)
    }

    private fun readable(id: String): String {
        val builder = StringBuilder(minOf(id.length, READABLE_MAX))
        for (char in id) {
            if (builder.length == READABLE_MAX) break
            builder.append(if (allowed(char)) char else '_')
        }
        return builder.toString()
    }

    private fun allowed(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' ||
        c == '.' || c == '_' || c == '-' || c == ':' || c == '/'
}

/** 迁移复杂双阶段回调时使用的窄适配器；参数仍是 API 102 独立快照。 */
internal abstract class ModernMethodHook {
    open fun beforeHookedMethod(param: ModernHookParam) = Unit
    open fun afterHookedMethod(param: ModernHookParam) = Unit
}

internal class ModernHookParam private constructor(
    val method: Executable,
    val instance: Any?,
    private val originalArgs: List<Any?>?,
    initialArgs: Array<Any?>?,
    private val source: ReadArgsInvocation?
) {
    internal constructor(method: Executable, instance: Any?, args: Array<Any?>) :
        this(method, instance, null, args, null)

    internal constructor(method: Executable, instance: Any?, args: List<Any?>) :
        this(method, instance, args, null, null)

    /** SDK 借用调用：参数按需读取，回调结束后不得再访问本对象的参数。 */
    internal constructor(method: Executable, instance: Any?, invocation: ReadArgsInvocation) :
        this(method, instance, null, null, invocation)

    private var argumentCopy: Array<Any?>? = initialArgs
    private var extras: HashMap<String, Any?>? = null

    /** 兼容既有数组读写；第一次访问才取私有副本，同次 before/after 共享它。 */
    val args: Array<Any?>
        get() = argumentCopy ?: copyArguments().also { argumentCopy = it }

    private fun copyArguments(): Array<Any?> {
        val invocation = source
        if (invocation != null) {
            return Array(invocation.argumentCount()) { invocation.getArgument(it) }
        }
        return checkNotNull(originalArgs).toTypedArray()
    }

    /** 只读热点不触发数组复制；若已通过 args 修改参数，则读取修改后的值（包括 null）。 */
    fun argOrNull(index: Int): Any? {
        val copy = argumentCopy
        if (copy != null) return copy.getOrNull(index)
        val invocation = source
        if (invocation != null) {
            return if (index in 0 until invocation.argumentCount()) invocation.getArgument(index) else null
        }
        return originalArgs?.getOrNull(index)
    }

    internal fun copiedArgsOrNull(): Array<Any?>? = argumentCopy

    /** 只把真正改动过的参数槽写回下游；SDK 逐槽按声明类型校验。 */
    internal fun writeArgumentsTo(invocation: MutableArgsInvocation) {
        val copy = argumentCopy ?: return
        for (index in copy.indices) {
            if (copy[index] !== invocation.getArgument(index)) invocation.setArgument(index, copy[index])
        }
    }

    val thisObject: Any?
        get() = instance

    private var resultValue: Any? = null
    private var resultAssignedByHook = false
    private var throwableValue: Throwable? = null

    var result: Any?
        get() = resultValue
        set(value) {
            resultValue = value
            throwableValue = null
            resultAssignedByHook = true
        }

    var throwable: Throwable?
        get() = throwableValue
        set(value) {
            throwableValue = value
            if (value != null) resultAssignedByHook = false
        }

    val hasThrowable: Boolean
        get() = throwableValue != null

    fun setObjectExtra(key: String, value: Any?) {
        val values = extras ?: HashMap<String, Any?>().also { extras = it }
        values[key] = value
    }

    fun getObjectExtra(key: String): Any? = extras?.get(key)

    internal fun assignOriginal(value: Any?) {
        resultValue = value
        throwableValue = null
        resultAssignedByHook = false
    }

    internal fun assignOriginalFailure(value: Throwable) {
        resultValue = null
        throwableValue = value
        resultAssignedByHook = false
    }

    internal fun wasResultAssignedByHook(): Boolean = resultAssignedByHook
}

internal class ModernMemberHookCreator(
    private val executable: Executable
) {
    private var beforeCallback: (ModernHookParam.() -> Unit)? = null
    private var afterCallback: (ModernHookParam.() -> Unit)? = null
    private var replacement: (ModernHookParam.() -> Any?)? = null
    private var constantReplacement = false
    private var constantResult: Any? = null

    /**
     * 回调里逃出的异常归属哪个功能；由功能安装器的受限注册器设置，未设置则不做归因。
     * 只是登记归属，不改变任何 Hook 语义。
     */
    internal var faultOwner: String? = null

    fun before(callback: ModernHookParam.() -> Unit) {
        check(replacement == null) { "before and replacement cannot be combined" }
        beforeCallback = callback
    }

    fun after(callback: ModernHookParam.() -> Unit) {
        check(replacement == null) { "after and replacement cannot be combined" }
        afterCallback = callback
    }

    fun replaceAny(callback: ModernHookParam.() -> Any?) {
        check(beforeCallback == null && afterCallback == null) {
            "replacement cannot be combined with before/after"
        }
        replacement = callback
        constantResult = null
        constantReplacement = false
    }

    fun replaceTo(value: Any?) {
        replaceAny { value }
        constantResult = value
        constantReplacement = true
    }

    fun replaceToTrue() = replaceTo(true)

    fun replaceToFalse() = replaceTo(false)

    /** 跳过原方法并按返回类型给出安全零值。 */
    fun intercept() = replaceTo(defaultReturnValue(executable))

    /** 常量替换总是走 SDK 的常量通道；构造器的接收者只在框架链上可见；其余按引擎选择（见 [ModernHookRuntime]）。 */
    internal fun usesDirectChain(engine: HookDispatchEngine): Boolean =
        !constantReplacement && (executable is Constructor<*> || engine == HookDispatchEngine.DIRECT)

    /** 迁移前的 before/after/replace 控制流，直接引擎使用；语义上等价于 SDK 路径的 around。 */
    internal fun interceptChain(chain: XposedInterface.Chain, onFault: ((Throwable) -> Unit)? = null): Any? {
        if (constantReplacement) return constantResult
        val param = ModernHookParam(chain.executable, chain.thisObject, chain.args)
        replacement?.let { return normalizeReplacement(guarded(onFault) { it(param) }) }

        beforeCallback?.let { guarded(onFault) { it(param) } }
        if (!param.wasResultAssignedByHook() && !param.hasThrowable) {
            try {
                val copiedArgs = param.copiedArgsOrNull()
                param.assignOriginal(if (copiedArgs == null) chain.proceed() else chain.proceed(copiedArgs))
            } catch (failure: Throwable) {
                param.assignOriginalFailure(failure)
            }
        }
        afterCallback?.let { guarded(onFault) { it(param) } }
        param.throwable?.let { throw it }
        return param.result
    }

    /** 只拦截**回调**抛出的异常；原方法的异常在回调之外处理，不会被误记为功能故障。 */
    private inline fun <T> guarded(noinline onFault: ((Throwable) -> Unit)?, body: () -> T): T {
        if (onFault == null) return body()
        try {
            return body()
        } catch (failure: Throwable) {
            if (failure !is VirtualMachineError) onFault(failure)
            throw failure
        }
    }

    /**
     * 按 DSL 结构选定 SDK 形状（安装期一次决定，热路径不再判断）：
     * - 常量替换：SDK 常量通道，不读参数、不跑回调；
     * - 仅 `replaceAny`：只读参数视图 + 提前返回；
     * - 仅 `after`：只读参数视图，下游照常以原参数执行；after 里改参数本来就无意义；
     * - 仅 `before`：可写参数，因为 before 里可以改参数数组；
     * - `before` 与 `after` 同在：around，两阶段共享同一份参数、结果与额外值。
     */
    internal fun toSpec(id: HookId, policy: ErrorPolicy, faults: HookFaultBridge? = null): HookSpec {
        val builder = HookSpec.Builder(id, OWNER, TargetSpec.ofMember(executable)).errorPolicy(policy)
        // PROTECT_HOST 下 `throwable` 从来到不了宿主：它在回调尾部作为异常抛出，由框架的保护模式吞掉。
        // 只有 DELIVER_TO_HOST 才把它作为显式结果交给调用方。
        val deliver = policy == ErrorPolicy.PROPAGATE
        // 受守护的回调只多一层 try/catch；归属在安装时就绑定，热路径不查表。
        fun guardBefore(callback: BeforeCallback) = faults?.guard(callback) ?: callback
        fun guardAfter(callback: AfterCallback) = faults?.guard(callback) ?: callback
        fun guardAround(callback: AroundCallback) = faults?.guard(callback) ?: callback
        val before = beforeCallback
        val after = afterCallback
        val replace = replacement
        when {
            constantReplacement -> builder.replaceConstant(constantResult)
            replace != null -> builder.access(ParameterAccess.READ_ARGS_VIEW)
                .before(guardBefore(BeforeCallback { invocation ->
                    val param = ModernHookParam(invocation.target(), invocation.thisObject(), invocation as ReadArgsInvocation)
                    invocation.setEarlyResult(normalizeReplacement(replace(param)))
                }))
            before != null && after != null -> builder.around(guardAround(aroundCallback(before, after, deliver)))
            before != null -> builder.access(ParameterAccess.MUTABLE_ARGS)
                .before(guardBefore(beforeOnlyCallback(before, deliver)))
            after != null -> builder.access(ParameterAccess.READ_ARGS_VIEW)
                .after(guardAfter(afterOnlyCallback(after, deliver)))
            // 没有任何回调：保留一条空安装，让命中证据与安装计数不变。
            else -> builder.access(ParameterAccess.NO_ARGS).before(BeforeCallback { })
        }
        return builder.build()
    }

    private fun beforeOnlyCallback(before: ModernHookParam.() -> Unit, deliver: Boolean) = BeforeCallback { invocation ->
        val arguments = invocation as MutableArgsInvocation
        val param = ModernHookParam(invocation.target(), invocation.thisObject(), arguments)
        before(param)
        val failure = param.throwable
        when {
            failure != null -> if (deliver) invocation.setEarlyThrowable(failure) else throw failure
            param.wasResultAssignedByHook() -> invocation.setEarlyResult(param.result)
            else -> param.writeArgumentsTo(arguments)
        }
    }

    private fun afterOnlyCallback(after: ModernHookParam.() -> Unit, deliver: Boolean) = AfterCallback { invocation, outcome ->
        val param = ModernHookParam(invocation.target(), invocation.thisObject(), invocation as ReadArgsInvocation)
        val originalFailure = if (outcome.hasThrown()) outcome.throwable else null
        if (originalFailure != null) param.assignOriginalFailure(originalFailure) else param.assignOriginal(outcome.result)
        after(param)
        val failure = param.throwable
        when {
            failure != null -> if (failure !== originalFailure) {
                if (deliver) outcome.setThrowable(failure) else throw failure
            }
            // 原方法失败后 after 清掉了异常：按 BIL 既有语义以当前结果返回。
            param.wasResultAssignedByHook() || originalFailure != null -> outcome.setResult(param.result)
        }
    }

    private fun aroundCallback(
        before: ModernHookParam.() -> Unit,
        after: ModernHookParam.() -> Unit,
        deliver: Boolean
    ) = AroundCallback { invocation ->
        val param = ModernHookParam(invocation.target(), invocation.thisObject(), invocation)
        before(param)
        var originalFailure: Throwable? = null
        if (!param.wasResultAssignedByHook() && !param.hasThrowable) {
            param.writeArgumentsTo(invocation)
            val outcome = invocation.proceed()
            if (outcome.state() == CallOutcome.State.THREW) {
                originalFailure = outcome.throwable()
                param.assignOriginalFailure(originalFailure)
            } else {
                param.assignOriginal(outcome.value())
            }
        }
        after(param)
        val failure = param.throwable
        when {
            failure != null -> if (failure !== originalFailure) {
                if (deliver) invocation.setEarlyThrowable(failure) else throw failure
            }
            // 提前结果、after 赋值或清除原异常之后，都以当前结果为准；其余情形沿用下游结果。
            originalFailure != null || param.wasResultAssignedByHook() -> invocation.setEarlyResult(param.result)
        }
    }

    /** 构造器与 void 方法没有返回值：Kotlin 的 Unit 之类的占位值不能当结果交给 SDK 校验。 */
    private fun normalizeReplacement(value: Any?): Any? {
        val returnsNothing = executable is Constructor<*> || (executable as? Method)?.returnType == Void.TYPE
        return if (returnsNothing) null else value
    }

    private fun defaultReturnValue(executable: Executable): Any? {
        val type = (executable as? Method)?.returnType ?: return null
        return when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Character.TYPE -> 0.toChar()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            else -> null
        }
    }

    private companion object {
        val OWNER: HookOwner = HookOwner.of("bil")
    }
}
