package com.bilab.core.agent.protocol

import java.security.MessageDigest
import java.util.Locale

/**
 * Closed set of typed tool parameter schemas exposed by sandboxed plugin agent tools.
 */
sealed interface AgentToolParamSpec {
    val name: String
    val description: String
    val required: Boolean

    data class StringParam(
        override val name: String,
        override val description: String = "",
        override val required: Boolean = false,
        val maxLength: Int = 256,
        val regex: String? = null
    ) : AgentToolParamSpec {
        init {
            require(PARAM_NAME_PATTERN.matches(name)) { "Invalid parameter name: $name" }
            require(maxLength in 1..MAX_STRING_PARAM_LENGTH) { "String maxLength out of bounds: $maxLength" }
            if (regex != null) Regex(regex)
        }
    }

    data class IntParam(
        override val name: String,
        override val description: String = "",
        override val required: Boolean = false,
        val minValue: Int = Int.MIN_VALUE,
        val maxValue: Int = Int.MAX_VALUE
    ) : AgentToolParamSpec {
        init {
            require(PARAM_NAME_PATTERN.matches(name)) { "Invalid parameter name: $name" }
            require(minValue <= maxValue) { "Invalid int range: $minValue..$maxValue" }
        }
    }

    data class BoolParam(
        override val name: String,
        override val description: String = "",
        override val required: Boolean = false
    ) : AgentToolParamSpec {
        init {
            require(PARAM_NAME_PATTERN.matches(name)) { "Invalid parameter name: $name" }
        }
    }

    data class EnumParam(
        override val name: String,
        override val description: String = "",
        override val required: Boolean = false,
        val allowedValues: List<String>
    ) : AgentToolParamSpec {
        init {
            require(PARAM_NAME_PATTERN.matches(name)) { "Invalid parameter name: $name" }
            require(allowedValues.isNotEmpty() && allowedValues.size <= MAX_ENUM_VALUES) {
                "Invalid enum value count: ${allowedValues.size}"
            }
            require(allowedValues.all { it.isNotBlank() } && allowedValues.distinct().size == allowedValues.size) {
                "Enum values must be non-blank and distinct"
            }
        }
    }

    companion object {
        const val MAX_STRING_PARAM_LENGTH = 4096
        const val MAX_ENUM_VALUES = 32
        val PARAM_NAME_PATTERN = Regex("[a-z][a-z0-9_]{0,31}")
    }
}

/**
 * Typed specification for a sandboxed plugin-provided agent tool.
 */
data class AgentToolSpec(
    val name: String,
    val description: String,
    val params: List<AgentToolParamSpec> = emptyList(),
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    val maxOutputBytes: Int = DEFAULT_MAX_OUTPUT_BYTES
) {
    init {
        require(TOOL_NAME_PATTERN.matches(name)) { "Invalid tool name: $name" }
        require(description.isNotBlank() && description.length <= MAX_DESCRIPTION_CHARS) {
            "Invalid tool description length"
        }
        require(params.size <= MAX_PARAMS) { "Too many tool parameters: ${params.size}" }
        require(params.map { it.name }.distinct().size == params.size) { "Duplicate tool parameter name" }
        require(timeoutMs in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS) { "Tool timeout out of bounds: $timeoutMs" }
        require(maxOutputBytes in MIN_OUTPUT_BYTES..MAX_OUTPUT_BYTES) {
            "Tool maxOutputBytes out of bounds: $maxOutputBytes"
        }
    }

    /**
     * Produces a collision-free qualified tool identifier prefixed with `plg_` so plugin tools
     * can never collide with built-in catalog tool names.
     */
    fun qualifiedName(pluginId: String): String {
        require(pluginId.isNotBlank()) { "pluginId must not be blank" }
        val slug = pluginId.lowercase(Locale.ROOT)
            .replace(NON_ALNUM_REGEX, "_")
            .trim('_')
            .take(8)
            .ifEmpty { "plugin" }
        val hash = sha256Hex(pluginId).take(8)
        return "plg_${slug}_${hash}_$name"
    }

    companion object {
        const val MIN_TIMEOUT_MS = 100L
        const val DEFAULT_TIMEOUT_MS = 3_000L
        const val MAX_TIMEOUT_MS = 5_000L
        const val MIN_OUTPUT_BYTES = 64
        const val DEFAULT_MAX_OUTPUT_BYTES = 4096
        const val MAX_OUTPUT_BYTES = 4096
        const val MAX_PARAMS = 16
        const val MAX_DESCRIPTION_CHARS = 512

        private val TOOL_NAME_PATTERN = Regex("[a-z][a-z0-9_]{1,39}")
        private val NON_ALNUM_REGEX = Regex("[^a-z0-9]+")

        private fun sha256Hex(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xFF) }
    }
}

/**
 * Escapes untrusted plugin tool output and wraps it in explicit XML-style boundary tags to mitigate
 * prompt injection from plugin responses.
 */
object UntrustedPluginOutputCodec {
    private val UNTRUSTED_TAG_PATTERN = Regex("<(\\s*/?\\s*untrusted_plugin_output)", RegexOption.IGNORE_CASE)
    private const val TAG_PADDING = 32

    fun wrapUntrustedPluginOutput(
        pluginId: String,
        toolName: String,
        rawPayload: String,
        maxChars: Int = AgentToolSpec.MAX_OUTPUT_BYTES
    ): String {
        require(maxChars >= 0) { "maxChars must be non-negative" }
        val safePlugin = escapeAttribute(pluginId)
        val safeTool = escapeAttribute(toolName)
        val bounded = sanitizeAndBoundPayload(rawPayload, maxChars)
        return "<untrusted_plugin_output plugin=\"$safePlugin\" tool=\"$safeTool\">$bounded</untrusted_plugin_output>"
    }

    private fun escapeAttribute(value: String): String = buildString(value.length) {
        for (ch in value) {
            if (ch.isISOControl()) continue
            when (ch) {
                '&' -> append("&amp;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                else -> append(ch)
            }
        }
    }

    private fun sanitizeAndBoundPayload(rawPayload: String, maxChars: Int): String {
        if (maxChars == 0 || rawPayload.isEmpty()) return ""
        val windowLimit = if (maxChars > Int.MAX_VALUE - TAG_PADDING) Int.MAX_VALUE else maxChars + TAG_PADDING
        val slice = if (rawPayload.length > windowLimit) rawPayload.substring(0, windowLimit) else rawPayload
        val escaped = UNTRUSTED_TAG_PATTERN.replace(slice) { match ->
            "&lt;${match.groupValues[1]}"
        }
        if (escaped.length <= maxChars) return escaped
        var cut = maxChars
        // Avoid leaving a dangling surrogate or partial "&lt;" entity right at the truncation boundary.
        if (cut > 0 && Character.isHighSurrogate(escaped[cut - 1])) {
            cut--
        }
        for (back in 1..3) {
            val amp = cut - back
            if (amp >= 0 && escaped[amp] == '&' && "&lt;".startsWith(escaped.substring(amp, cut))) {
                cut = amp
                break
            }
        }
        return escaped.substring(0, cut)
    }
}
