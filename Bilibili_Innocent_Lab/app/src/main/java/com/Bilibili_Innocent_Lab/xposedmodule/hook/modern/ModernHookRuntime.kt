package com.Bilibili_Innocent_Lab.xposedmodule.hook.modern

import android.util.Log
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ModernApiSupport
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * 模块侧的 Hook 异常语义；由 [ModernHookRuntime] 映射到框架 `ExceptionMode`，
 * 业务安装器因此不直接持有框架类型。
 */
internal enum class HookExceptionPolicy {
    /**
     * 默认。回调抛出的任何异常由框架捕获并记录，调用**按“没装 Hook”继续**
     * （`ExceptionMode.PROTECTIVE`）。这是宿主稳定性的主要保障：模块回调里的反射失败、
     * 结构漂移、NPE 都不会传到宿主栈上。
     */
    PROTECT_HOST,

    /**
     * 回调异常原样传给宿主调用方（`ExceptionMode.PASSTHROUGH`）。
     *
     * **只用于“让被 Hook 方法以指定异常结束”这一种诉求**（当前唯一使用者是屏蔽官方更新，
     * 它必须把宿主自己的 `LatestVersionException` 交回调用方）。选它的 Hook 有义务保证
     * 回调体内除了这条**刻意**的异常之外不会逃逸任何东西——因为这里没有框架兜底。
     *
     * 背景：`PROTECTIVE` 的契约是“捕获并按没装 Hook 继续”，所以在该模式下
     * `ModernHookParam.throwable` 永远到不了宿主。这与 2026-08-30 记录的
     * “模块回调异常 vs 传递给宿主的 throwable”是同一个坑在 Modern API 下的新形态。
     */
    DELIVER_TO_HOST;

    val frameworkMode: XposedInterface.ExceptionMode
        get() = when (this) {
            PROTECT_HOST -> XposedInterface.ExceptionMode.PROTECTIVE
            DELIVER_TO_HOST -> XposedInterface.ExceptionMode.PASSTHROUGH
        }
}

/**
 * 把项目既有 before/after/replace 语义映射到 Modern API 101/102 interceptor chain。
 *
 * 该层只兼容项目实际使用的最小 DSL，不模拟 Legacy XposedBridge，也不允许回调保存 Chain。
 */
internal class ModernHookRuntime(
    private val module: XposedInterface
) {
    private val handles = CopyOnWriteArrayList<XposedInterface.HookHandle>()
    // HookEntry 构造时还没 attachFramework，必须等首次安装再查询。
    private val apiVersion by lazy { module.apiVersion }
    private data class CompatibilityKey(val executable: Executable, val id: String)
    private class CompatibilityHook(
        val callback: AtomicReference<XposedInterface.Hooker>,
        val handle: XposedInterface.HookHandle
    )
    private val compatibilityHooks by lazy { HashMap<CompatibilityKey, CompatibilityHook>() }

    fun install(
        id: String,
        executable: Executable,
        exceptionPolicy: HookExceptionPolicy = HookExceptionPolicy.PROTECT_HOST,
        block: ModernMemberHookCreator.() -> Unit
    ): XposedInterface.HookHandle {
        check(apiVersion >= ModernApiSupport.MIN_API) { "Modern API 101 or newer is required" }
        val creator = ModernMemberHookCreator(executable).apply(block)
        val callback = XposedInterface.Hooker { chain -> creator.invoke(chain) }
        if (apiVersion < ModernApiSupport.HOOK_IDS_API) {
            // 101 没有框架 Hook ID。相同逻辑点保留一条原生 Hook，只原子切换回调。
            // 在途调用已取到旧回调，before/after 始终属于同一次注册，不受替换影响。
            val key = CompatibilityKey(executable, id)
            return synchronized(compatibilityHooks) {
                compatibilityHooks[key]?.let { existing ->
                    existing.callback.set(callback)
                    return@synchronized existing.handle
                }
                val reference = AtomicReference(callback)
                val handle = module.hook(executable)
                    .setExceptionMode(exceptionPolicy.frameworkMode)
                    .intercept { chain -> reference.get().intercept(chain) }
                compatibilityHooks[key] = CompatibilityHook(reference, handle)
                handles += handle
                handle
            }
        }
        val handle = ModernHookIdsApi102.assign(module, module.hook(executable), id)
            .setExceptionMode(exceptionPolicy.frameworkMode)
            .intercept(callback)
        handles += handle
        return handle
    }

    fun install(
        id: String,
        executable: Executable,
        callback: ModernMethodHook
    ): XposedInterface.HookHandle = install(id, executable) {
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
    }
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
    initialArgs: Array<Any?>?
) {
    internal constructor(method: Executable, instance: Any?, args: Array<Any?>) :
        this(method, instance, null, args)

    internal constructor(method: Executable, instance: Any?, args: List<Any?>) :
        this(method, instance, args, null)

    private var argumentCopy: Array<Any?>? = initialArgs
    private var extras: HashMap<String, Any?>? = null

    /** 兼容既有数组读写；第一次访问才取私有副本，同次 before/after 共享它。 */
    val args: Array<Any?>
        get() = argumentCopy ?: checkNotNull(originalArgs).toTypedArray().also {
            argumentCopy = it
        }

    /** 只读热点不触发数组复制；若已通过 args 修改参数，则读取修改后的值（包括 null）。 */
    fun argOrNull(index: Int): Any? {
        val copy = argumentCopy
        return if (copy != null) copy.getOrNull(index) else originalArgs?.getOrNull(index)
    }

    internal fun copiedArgsOrNull(): Array<Any?>? = argumentCopy

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
    }

    fun replaceTo(value: Any?) = replaceAny { value }

    fun replaceToTrue() = replaceTo(true)

    fun replaceToFalse() = replaceTo(false)

    /** 跳过原方法并按返回类型给出安全零值。 */
    fun intercept() = replaceAny { defaultReturnValue(executable) }

    internal fun invoke(chain: XposedInterface.Chain): Any? {
        val param = ModernHookParam(
            method = chain.executable,
            instance = chain.thisObject,
            args = chain.args
        )
        replacement?.let { return it(param) }

        beforeCallback?.invoke(param)
        if (!param.wasResultAssignedByHook() && !param.hasThrowable) {
            try {
                val copiedArgs = param.copiedArgsOrNull()
                param.assignOriginal(
                    if (copiedArgs == null) chain.proceed() else chain.proceed(copiedArgs)
                )
            } catch (throwable: Throwable) {
                param.assignOriginalFailure(throwable)
            }
        }
        afterCallback?.invoke(param)
        param.throwable?.let { throw it }
        return param.result
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
}
