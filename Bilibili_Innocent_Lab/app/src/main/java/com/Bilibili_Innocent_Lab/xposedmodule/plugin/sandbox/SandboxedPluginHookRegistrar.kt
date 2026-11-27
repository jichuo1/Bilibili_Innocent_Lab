package com.Bilibili_Innocent_Lab.xposedmodule.plugin.sandbox

import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernHookLog
import com.bilab.plugin.api.spi.HookCallbackContext
import com.bilab.plugin.api.spi.HookHandle
import com.bilab.plugin.api.spi.PluginHookCallback
import com.bilab.plugin.api.spi.PluginHookErrorPolicy
import com.bilab.plugin.api.spi.PluginHookRegistrar
import com.bilab.plugin.api.spi.ResolvedFieldRef
import com.bilab.plugin.api.spi.ResolvedMemberRef
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.util.IdentityHashMap
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Host-issued opaque reference to a resolved target method or constructor.
 *
 * Construction is restricted to host sandbox code and verified by object identity inside
 * [SandboxedPluginHookRegistrar] so plugins cannot forge arbitrary targets.
 */
internal class HostResolvedMemberRef internal constructor(
    override val ownerClassName: String,
    override val memberName: String,
    override val descriptor: String,
    val executable: Member? = null,
    internal val pluginId: String? = null,
    internal val registrarToken: Any
) : ResolvedMemberRef

/**
 * Host-issued opaque reference to a resolved target field.
 */
internal class HostResolvedFieldRef internal constructor(
    override val ownerClassName: String,
    override val fieldName: String,
    override val fieldTypeName: String,
    val field: Field? = null,
    internal val pluginId: String? = null,
    internal val registrarToken: Any
) : ResolvedFieldRef

/**
 * Controlled low-level hook installation bridge owned by the host runtime or test harness.
 */
internal fun interface PluginHookBridge {
    fun installHook(
        member: HostResolvedMemberRef,
        onBefore: (HookCallbackContext) -> Unit,
        onAfter: (HookCallbackContext) -> Unit
    ): HookHandle

    companion object {
        val NO_OP: PluginHookBridge = PluginHookBridge { member, _, _ ->
            object : HookHandle {
                override val hookId: String = "${member.ownerClassName}#${member.memberName}"
                override val isActive: Boolean = false
                override fun close() = Unit
            }
        }
    }
}

/**
 * Host-managed mutable callback view passed to sandboxed plugin hook callbacks.
 */
internal class HostHookCallbackContext(
    override val member: ResolvedMemberRef,
    override val thisObject: Any? = null,
    private val args: Array<Any?> = emptyArray(),
    initialResult: Any? = null,
    initialHasResult: Boolean = false,
    override val hasFailure: Boolean = false
) : HookCallbackContext {
    private var resultSet: Boolean = initialHasResult
    private var resultValue: Any? = initialResult
    private var extras: HashMap<String, Any?>? = null

    override val argCount: Int
        get() = args.size

    override fun argOrNull(index: Int): Any? = args.getOrNull(index)

    override fun setArg(index: Int, value: Any?) {
        if (index in args.indices) {
            args[index] = value
        }
    }

    override fun hasResult(): Boolean = resultSet

    override fun resultOrNull(): Any? = resultValue

    override fun setResult(value: Any?) {
        resultValue = value
        resultSet = true
    }

    override fun getExtra(key: String): Any? = extras?.get(key)

    override fun setExtra(key: String, value: Any?) {
        val map = extras ?: HashMap<String, Any?>().also { extras = it }
        map[key] = value
    }
}

/**
 * Sandboxed [PluginHookRegistrar] enforcing:
 * - Strict host-issued [HostResolvedMemberRef] identity verification (rejecting forged refs with [SecurityException])
 * - Mandatory [PluginHookErrorPolicy.PROTECT_HOST] (all callback Throwables caught and isolated)
 * - Automatic fault accounting into [PluginFaultCircuitBreaker] and immediate teardown on trip or close.
 */
internal class SandboxedPluginHookRegistrar(
    private val pluginId: String,
    private val hookBridge: PluginHookBridge,
    private val circuitBreaker: PluginFaultCircuitBreaker = PluginFaultCircuitBreaker(),
    private val onTrip: (() -> Unit)? = null,
    private val onFault: ((Throwable) -> Unit)? = null
) : PluginHookRegistrar, AutoCloseable {

    private inner class SandboxedHookHandle(
        override val hookId: String,
        private val delegate: HookHandle
    ) : HookHandle {
        private val closed = AtomicBoolean(false)

        override val isActive: Boolean
            get() = !closed.get() &&
                !registrarClosed.get() &&
                !circuitBreaker.isTripped(pluginId) &&
                delegate.isActive

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            synchronized(handlesLock) {
                activeHandles.remove(this)
            }
            runCatching { delegate.close() }
        }
    }

    override val errorPolicy: PluginHookErrorPolicy = PluginHookErrorPolicy.PROTECT_HOST

    private val registrarToken = Any()
    private val issuedLock = Any()
    private val issuedMembers = IdentityHashMap<HostResolvedMemberRef, Unit>()
    private val issuedFields = IdentityHashMap<HostResolvedFieldRef, Unit>()
    private val handlesLock = Any()
    private val activeHandles = LinkedHashSet<SandboxedHookHandle>()
    private val registrarClosed = AtomicBoolean(false)

    internal val token: Any
        get() = registrarToken

    val isClosed: Boolean
        get() = registrarClosed.get()

    val activeHookCount: Int
        get() = if (registrarClosed.get() || circuitBreaker.isTripped(pluginId)) {
            0
        } else {
            synchronized(handlesLock) { activeHandles.count { it.isActive } }
        }

    fun issueMemberRef(
        ownerClassName: String,
        memberName: String,
        descriptor: String,
        executable: Member? = null
    ): HostResolvedMemberRef {
        val ref = HostResolvedMemberRef(
            ownerClassName = ownerClassName,
            memberName = memberName,
            descriptor = descriptor,
            executable = executable,
            pluginId = pluginId,
            registrarToken = registrarToken
        )
        synchronized(issuedLock) {
            if (!registrarClosed.get()) {
                issuedMembers[ref] = Unit
            }
        }
        return ref
    }

    fun issueMemberRef(executable: Executable): HostResolvedMemberRef = issueMemberRef(
        ownerClassName = executable.declaringClass.name,
        memberName = if (executable is Constructor<*>) "<init>" else executable.name,
        descriptor = buildDescriptor(executable),
        executable = executable
    )

    fun issueFieldRef(
        ownerClassName: String,
        fieldName: String,
        fieldTypeName: String,
        field: Field? = null
    ): HostResolvedFieldRef {
        val ref = HostResolvedFieldRef(
            ownerClassName = ownerClassName,
            fieldName = fieldName,
            fieldTypeName = fieldTypeName,
            field = field,
            pluginId = pluginId,
            registrarToken = registrarToken
        )
        synchronized(issuedLock) {
            if (!registrarClosed.get()) {
                issuedFields[ref] = Unit
            }
        }
        return ref
    }

    fun issueFieldRef(field: Field): HostResolvedFieldRef = issueFieldRef(
        ownerClassName = field.declaringClass.name,
        fieldName = field.name,
        fieldTypeName = field.type.name,
        field = field
    )

    fun requireIssuedMember(member: ResolvedMemberRef): HostResolvedMemberRef {
        if (member !is HostResolvedMemberRef) {
            throw SecurityException("Forged ResolvedMemberRef rejected for plugin '$pluginId'")
        }
        if (member.registrarToken !== registrarToken) {
            throw SecurityException("Foreign or forged ResolvedMemberRef token rejected for plugin '$pluginId'")
        }
        if (member.pluginId != null) {
            val known = synchronized(issuedLock) { issuedMembers.containsKey(member) }
            if (!known || member.pluginId != pluginId) {
                throw SecurityException("Foreign or unissued ResolvedMemberRef rejected for plugin '$pluginId'")
            }
        }
        return member
    }

    fun requireIssuedField(fieldRef: ResolvedFieldRef): HostResolvedFieldRef {
        if (fieldRef !is HostResolvedFieldRef) {
            throw SecurityException("Forged ResolvedFieldRef rejected for plugin '$pluginId'")
        }
        if (fieldRef.registrarToken !== registrarToken) {
            throw SecurityException("Foreign or forged ResolvedFieldRef token rejected for plugin '$pluginId'")
        }
        if (fieldRef.pluginId != null) {
            val known = synchronized(issuedLock) { issuedFields.containsKey(fieldRef) }
            if (!known || fieldRef.pluginId != pluginId) {
                throw SecurityException("Foreign or unissued ResolvedFieldRef rejected for plugin '$pluginId'")
            }
        }
        return fieldRef
    }

    override fun register(
        hookId: String,
        member: ResolvedMemberRef,
        callback: PluginHookCallback
    ): HookHandle {
        require(hookId.isNotBlank()) { "hookId must not be blank" }
        val hostMember = requireIssuedMember(member)
        if (shouldShortCircuit()) {
            return inactiveHandle(hookId)
        }

        val onBefore: (HookCallbackContext) -> Unit = { context ->
            if (!shouldShortCircuit()) {
                try {
                    callback.before(context)
                } catch (throwable: Throwable) {
                    handleCallbackFailure(throwable)
                }
            }
        }
        val onAfter: (HookCallbackContext) -> Unit = { context ->
            if (!shouldShortCircuit()) {
                try {
                    callback.after(context)
                } catch (throwable: Throwable) {
                    handleCallbackFailure(throwable)
                }
            }
        }

        val delegate = hookBridge.installHook(hostMember, onBefore, onAfter)
        val handle = SandboxedHookHandle(hookId, delegate)
        val attached = synchronized(handlesLock) {
            if (registrarClosed.get() || circuitBreaker.isTripped(pluginId)) {
                false
            } else {
                activeHandles.add(handle)
                true
            }
        }
        if (!attached) {
            handle.close()
            return inactiveHandle(hookId)
        }
        return handle
    }

    override fun registerReplaceConstant(
        hookId: String,
        member: ResolvedMemberRef,
        constantResult: Any?
    ): HookHandle = register(
        hookId = hookId,
        member = member,
        callback = object : PluginHookCallback {
            override fun before(context: HookCallbackContext) {
                context.setResult(constantResult)
            }
        }
    )

    override fun close() {
        if (!registrarClosed.compareAndSet(false, true)) return
        val handles = synchronized(handlesLock) {
            activeHandles.toList().also { activeHandles.clear() }
        }
        for (handle in handles) {
            handle.close()
        }
        synchronized(issuedLock) {
            issuedMembers.clear()
            issuedFields.clear()
        }
    }

    private fun shouldShortCircuit(): Boolean =
        registrarClosed.get() || circuitBreaker.isTripped(pluginId)

    private fun handleCallbackFailure(throwable: Throwable) {
        try {
            onFault?.invoke(throwable) ?: ModernHookLog.error(
                "[BIL] 插件 Hook 回调异常已隔离(plugin=$pluginId)",
                throwable
            )
        } catch (_: Throwable) {
            // Never let logging failure propagate back into the host call stack.
        }
        val tripped = circuitBreaker.recordFault(pluginId)
        if (tripped) {
            close()
            runCatching { onTrip?.invoke() }
        }
    }

    private fun inactiveHandle(hookId: String): HookHandle = object : HookHandle {
        override val hookId: String = hookId
        override val isActive: Boolean = false
        override fun close() = Unit
    }

    companion object {
        private fun buildDescriptor(executable: Executable): String = buildString {
            append('(')
            for (param in executable.parameterTypes) {
                appendType(param)
            }
            append(')')
            when (executable) {
                is Method -> appendType(executable.returnType)
                is Constructor<*> -> append('V')
            }
        }

        private fun StringBuilder.appendType(type: Class<*>) {
            var current = type
            while (current.isArray) {
                append('[')
                current = current.componentType!!
            }
            when (current) {
                Void.TYPE -> append('V')
                java.lang.Boolean.TYPE -> append('Z')
                java.lang.Byte.TYPE -> append('B')
                java.lang.Character.TYPE -> append('C')
                java.lang.Short.TYPE -> append('S')
                Integer.TYPE -> append('I')
                java.lang.Long.TYPE -> append('J')
                java.lang.Float.TYPE -> append('F')
                java.lang.Double.TYPE -> append('D')
                else -> {
                    append('L')
                    append(current.name.replace('.', '/'))
                    append(';')
                }
            }
        }
    }
}
