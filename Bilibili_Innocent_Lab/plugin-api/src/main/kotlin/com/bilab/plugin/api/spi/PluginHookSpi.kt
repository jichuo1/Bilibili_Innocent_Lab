package com.bilab.plugin.api.spi

/**
 * Opaque handle to a resolved host method or constructor.
 *
 * Intentionally non-sealed so the host implementation across module boundaries can implement it,
 * while only host-issued instances may be passed back into [PluginHookRegistrar].
 */
interface ResolvedMemberRef {
    val ownerClassName: String
    val memberName: String
    val descriptor: String
}

/**
 * Opaque handle to a resolved host field.
 *
 * Intentionally non-sealed so the host implementation across module boundaries can implement it,
 * while only host-issued instances may be passed into host SPIs.
 */
interface ResolvedFieldRef {
    val ownerClassName: String
    val fieldName: String
    val fieldTypeName: String
}

/**
 * Error isolation policy enforced on all plugin-registered hooks.
 */
enum class PluginHookErrorPolicy {
    /** Escaping callback exceptions are isolated and never propagated into the host call stack. */
    PROTECT_HOST
}

/**
 * Per-invocation hook callback view restricted to safe argument/result inspection and mutation.
 */
interface HookCallbackContext {
    val member: ResolvedMemberRef
    val thisObject: Any?
    val argCount: Int
    val hasFailure: Boolean

    fun argOrNull(index: Int): Any?
    fun setArg(index: Int, value: Any?)
    fun hasResult(): Boolean
    fun resultOrNull(): Any?
    fun setResult(value: Any?)
    fun getExtra(key: String): Any?
    fun setExtra(key: String, value: Any?)
}

/**
 * Detachable registration handle for a single plugin hook.
 */
interface HookHandle : AutoCloseable {
    val hookId: String
    val isActive: Boolean
}

/**
 * Callback contract invoked before/after a hooked host member under [PluginHookErrorPolicy.PROTECT_HOST].
 */
interface PluginHookCallback {
    fun before(context: HookCallbackContext) {}
    fun after(context: HookCallbackContext) {}
}

/**
 * Restricted hook registrar exposed to plugins; always operates under [PluginHookErrorPolicy.PROTECT_HOST].
 */
interface PluginHookRegistrar {
    val errorPolicy: PluginHookErrorPolicy
        get() = PluginHookErrorPolicy.PROTECT_HOST

    fun register(
        hookId: String,
        member: ResolvedMemberRef,
        callback: PluginHookCallback
    ): HookHandle

    fun registerReplaceConstant(
        hookId: String,
        member: ResolvedMemberRef,
        constantResult: Any?
    ): HookHandle
}
