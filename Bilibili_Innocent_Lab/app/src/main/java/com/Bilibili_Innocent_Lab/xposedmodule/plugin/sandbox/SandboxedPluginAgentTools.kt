package com.Bilibili_Innocent_Lab.xposedmodule.plugin.sandbox

import com.bilab.core.agent.protocol.AgentToolParamSpec
import com.bilab.core.agent.protocol.AgentToolSpec
import com.bilab.core.agent.protocol.UntrustedPluginOutputCodec
import com.bilab.plugin.api.spi.AgentToolExtension
import com.bilab.plugin.api.spi.PluginAgentToolDescriptor
import com.bilab.plugin.api.spi.PluginAgentToolResult
import java.util.Collections
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal data class RegisteredPluginAgentTool(
    val pluginId: String,
    val localName: String,
    val qualifiedName: String,
    val spec: AgentToolSpec
)

internal data class SandboxedToolExecutionResult(
    val success: Boolean,
    val qualifiedName: String,
    val wrappedOutput: String,
    val errorReason: String? = null
)

internal typealias SandboxedPluginAgentToolRegistry = SandboxedPluginAgentTools

/**
 * Converts, isolates, executes, and escapes [AgentToolExtension] tools provided by plugins.
 *
 * - Tool names are rewritten with [AgentToolSpec.qualifiedName] (`plg_<slug>_<hash>_<name>`) to
 *   prevent shadowing built-in host tools.
 * - Execution runs on a bounded executor capped at [AgentToolSpec.MAX_TIMEOUT_MS] (5000ms).
 * - All outputs (success, failure, timeout, and validation errors) are passed through
 *   [UntrustedPluginOutputCodec.wrapUntrustedPluginOutput].
 */
internal class SandboxedPluginAgentTools @JvmOverloads constructor(
    private val executor: ExecutorService? = null,
    private val defaultTimeoutMs: Long = AgentToolSpec.DEFAULT_TIMEOUT_MS,
    private val onFault: ((pluginId: String) -> Unit)? = null
) : AutoCloseable {

    private class ToolBinding(
        val registered: RegisteredPluginAgentTool,
        val extension: AgentToolExtension,
        val active: AtomicBoolean = AtomicBoolean(true)
    )

    private val lock = Any()
    private val ownsExecutor: Boolean = executor == null
    private val workerPool: ExecutorService by lazy(LazyThreadSafetyMode.PUBLICATION) {
        executor ?: createDefaultBoundedExecutor()
    }
    private val bindingsByQualifiedName = ConcurrentHashMap<String, ToolBinding>()
    private val closed = AtomicBoolean(false)

    init {
        require(defaultTimeoutMs in AgentToolSpec.MIN_TIMEOUT_MS..AgentToolSpec.MAX_TIMEOUT_MS) {
            "defaultTimeoutMs out of bounds: $defaultTimeoutMs"
        }
    }

    fun register(
        pluginId: String,
        extension: AgentToolExtension,
        requestedTimeoutMs: Long = defaultTimeoutMs
    ): AutoCloseable {
        require(pluginId.isNotBlank()) { "pluginId must not be blank" }
        require(!closed.get()) { "SandboxedPluginAgentTools is closed" }
        val descriptors = extension.tools
        require(descriptors.size <= MAX_TOOLS_PER_EXTENSION) {
            "Plugin '$pluginId' declared too many tools: ${descriptors.size}"
        }
        val effectiveTimeout = requestedTimeoutMs.coerceIn(
            AgentToolSpec.MIN_TIMEOUT_MS,
            AgentToolSpec.MAX_TIMEOUT_MS
        )
        val built = ArrayList<ToolBinding>(descriptors.size)
        val seenLocalNames = HashSet<String>()
        for (descriptor in descriptors) {
            require(seenLocalNames.add(descriptor.name)) {
                "Duplicate tool name '${descriptor.name}' in plugin '$pluginId'"
            }
            val spec = toAgentToolSpec(descriptor, effectiveTimeout)
            val qualifiedName = spec.qualifiedName(pluginId)
            built += ToolBinding(
                registered = RegisteredPluginAgentTool(
                    pluginId = pluginId,
                    localName = spec.name,
                    qualifiedName = qualifiedName,
                    spec = spec
                ),
                extension = extension
            )
        }
        synchronized(lock) {
            if (closed.get()) {
                return AutoCloseable { }
            }
            for (binding in built) {
                require(!bindingsByQualifiedName.containsKey(binding.registered.qualifiedName)) {
                    "Tool '${binding.registered.qualifiedName}' already registered for '$pluginId'"
                }
            }
            for (binding in built) {
                bindingsByQualifiedName[binding.registered.qualifiedName] = binding
            }
        }
        val handleClosed = AtomicBoolean(false)
        return AutoCloseable {
            if (!handleClosed.compareAndSet(false, true)) return@AutoCloseable
            synchronized(lock) {
                for (binding in built) {
                    binding.active.set(false)
                    bindingsByQualifiedName.remove(binding.registered.qualifiedName, binding)
                }
            }
        }
    }

    fun unregisterPlugin(pluginId: String) {
        synchronized(lock) {
            val iterator = bindingsByQualifiedName.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.value.registered.pluginId == pluginId) {
                    entry.value.active.set(false)
                    iterator.remove()
                }
            }
        }
    }

    fun listTools(): List<RegisteredPluginAgentTool> =
        Collections.unmodifiableList(
            bindingsByQualifiedName.values
                .filter { it.active.get() }
                .map { it.registered }
                .sortedBy { it.qualifiedName }
        )

    fun listToolsForPlugin(pluginId: String): List<RegisteredPluginAgentTool> =
        Collections.unmodifiableList(
            bindingsByQualifiedName.values
                .filter { it.active.get() && it.registered.pluginId == pluginId }
                .map { it.registered }
                .sortedBy { it.qualifiedName }
        )

    fun invokeQualified(
        qualifiedName: String,
        arguments: Map<String, String> = emptyMap()
    ): String = invoke(qualifiedName, arguments).wrappedOutput

    fun invoke(
        qualifiedName: String,
        arguments: Map<String, String> = emptyMap()
    ): SandboxedToolExecutionResult {
        val binding = bindingsByQualifiedName[qualifiedName]
        if (binding == null || !binding.active.get() || closed.get()) {
            val wrapped = UntrustedPluginOutputCodec.wrapUntrustedPluginOutput(
                pluginId = "unknown",
                toolName = qualifiedName,
                rawPayload = "TOOL_ERROR: tool unavailable"
            )
            return SandboxedToolExecutionResult(
                success = false,
                qualifiedName = qualifiedName,
                wrappedOutput = wrapped,
                errorReason = "unavailable"
            )
        }
        val reg = binding.registered
        val validationError = validateArguments(reg.spec, arguments)
        if (validationError != null) {
            return wrapFailure(reg, "TOOL_ERROR: $validationError", errorReason = validationError)
        }
        val safeArgs = Collections.unmodifiableMap(LinkedHashMap(arguments))
        val future = try {
            workerPool.submit(Callable {
                if (!binding.active.get()) {
                    PluginAgentToolResult.Failure("tool unregistered")
                } else {
                    binding.extension.invoke(reg.localName, safeArgs)
                }
            })
        } catch (_: Throwable) {
            return wrapFailure(reg, "TOOL_ERROR: executor busy", errorReason = "executor_rejected")
        }

        val effectiveTimeout = reg.spec.timeoutMs.coerceAtMost(AgentToolSpec.MAX_TIMEOUT_MS)
        return try {
            when (val result = future.get(effectiveTimeout, TimeUnit.MILLISECONDS)) {
                is PluginAgentToolResult.Success -> {
                    val wrapped = UntrustedPluginOutputCodec.wrapUntrustedPluginOutput(
                        pluginId = reg.pluginId,
                        toolName = reg.localName,
                        rawPayload = result.payload,
                        maxChars = reg.spec.maxOutputBytes
                    )
                    SandboxedToolExecutionResult(
                        success = true,
                        qualifiedName = reg.qualifiedName,
                        wrappedOutput = wrapped
                    )
                }
                is PluginAgentToolResult.Failure -> {
                    wrapFailure(reg, "TOOL_FAILURE: ${result.reason}", errorReason = result.reason)
                }
            }
        } catch (_: TimeoutException) {
            future.cancel(true)
            runCatching { onFault?.invoke(reg.pluginId) }
            wrapFailure(
                reg,
                "TOOL_ERROR: timeout after ${effectiveTimeout}ms",
                errorReason = "timeout"
            )
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            future.cancel(true)
            wrapFailure(reg, "TOOL_ERROR: interrupted", errorReason = "interrupted")
        } catch (_: Throwable) {
            runCatching { onFault?.invoke(reg.pluginId) }
            wrapFailure(reg, "TOOL_ERROR: execution failed", errorReason = "exception")
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) {
            for (binding in bindingsByQualifiedName.values) {
                binding.active.set(false)
            }
            bindingsByQualifiedName.clear()
        }
        if (ownsExecutor) {
            runCatching { workerPool.shutdownNow() }
        }
    }

    private fun wrapFailure(
        reg: RegisteredPluginAgentTool,
        rawPayload: String,
        errorReason: String
    ): SandboxedToolExecutionResult = SandboxedToolExecutionResult(
        success = false,
        qualifiedName = reg.qualifiedName,
        wrappedOutput = UntrustedPluginOutputCodec.wrapUntrustedPluginOutput(
            pluginId = reg.pluginId,
            toolName = reg.localName,
            rawPayload = rawPayload,
            maxChars = reg.spec.maxOutputBytes
        ),
        errorReason = errorReason
    )

    companion object {
        const val MAX_TOOLS_PER_EXTENSION = 16

        fun toAgentToolSpec(
            descriptor: PluginAgentToolDescriptor,
            timeoutMs: Long = AgentToolSpec.DEFAULT_TIMEOUT_MS
        ): AgentToolSpec {
            require(descriptor.maxOutputChars > 0) {
                "maxOutputChars must be positive: ${descriptor.maxOutputChars}"
            }
            val requiredSet = descriptor.requiredParameters.toSet()
            val paramNames = LinkedHashSet<String>().apply {
                addAll(descriptor.parameterDescriptions.keys)
                addAll(descriptor.requiredParameters)
            }
            val params = paramNames.map { paramName ->
                AgentToolParamSpec.StringParam(
                    name = paramName,
                    description = descriptor.parameterDescriptions[paramName].orEmpty(),
                    required = paramName in requiredSet
                )
            }
            return AgentToolSpec(
                name = descriptor.name,
                description = descriptor.description,
                params = params,
                timeoutMs = timeoutMs.coerceIn(
                    AgentToolSpec.MIN_TIMEOUT_MS,
                    AgentToolSpec.MAX_TIMEOUT_MS
                ),
                maxOutputBytes = descriptor.maxOutputChars.coerceIn(
                    AgentToolSpec.MIN_OUTPUT_BYTES,
                    AgentToolSpec.MAX_OUTPUT_BYTES
                )
            )
        }

        private fun validateArguments(spec: AgentToolSpec, arguments: Map<String, String>): String? {
            val paramByName = spec.params.associateBy { it.name }
            for (param in spec.params) {
                if (param.required && !arguments.containsKey(param.name)) {
                    return "missing required parameter '${param.name}'"
                }
            }
            for ((key, value) in arguments) {
                val param = paramByName[key] ?: return "unexpected parameter '$key'"
                if (param is AgentToolParamSpec.StringParam && value.length > param.maxLength) {
                    return "parameter '$key' exceeds maxLength=${param.maxLength}"
                }
            }
            return null
        }

        private fun createDefaultBoundedExecutor(): ExecutorService {
            val nextId = AtomicInteger(1)
            val factory = ThreadFactory { runnable ->
                Thread(runnable, "bil-plugin-tool-${nextId.getAndIncrement()}").apply {
                    isDaemon = true
                }
            }
            return ThreadPoolExecutor(
                2,
                4,
                30L,
                TimeUnit.SECONDS,
                ArrayBlockingQueue(16),
                factory,
                ThreadPoolExecutor.AbortPolicy()
            ).apply {
                allowCoreThreadTimeOut(true)
            }
        }
    }
}
