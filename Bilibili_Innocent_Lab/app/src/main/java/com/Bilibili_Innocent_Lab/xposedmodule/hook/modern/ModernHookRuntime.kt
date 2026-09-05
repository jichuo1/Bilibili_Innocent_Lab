package com.Bilibili_Innocent_Lab.xposedmodule.hook.modern

import android.util.Log
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ModernApiSupport
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

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
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain -> reference.get().intercept(chain) }
                compatibilityHooks[key] = CompatibilityHook(reference, handle)
                handles += handle
                handle
            }
        }
        val handle = ModernHookIdsApi102.assign(module, module.hook(executable), id)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
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

internal class ModernHookParam internal constructor(
    val method: Executable,
    val instance: Any?,
    val args: Array<Any?>
) {
    private val extras = HashMap<String, Any?>()

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
        extras[key] = value
    }

    fun getObjectExtra(key: String): Any? = extras[key]

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
            args = chain.args.toTypedArray()
        )
        replacement?.let { return it(param) }

        beforeCallback?.invoke(param)
        if (!param.wasResultAssignedByHook() && !param.hasThrowable) {
            try {
                param.assignOriginal(chain.proceed(param.args))
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
