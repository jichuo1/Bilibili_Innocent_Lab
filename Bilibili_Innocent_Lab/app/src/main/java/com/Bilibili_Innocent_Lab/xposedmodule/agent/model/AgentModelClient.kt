package com.Bilibili_Innocent_Lab.xposedmodule.agent.model

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.AiChatCompat
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.ReasoningLevel
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale

/** 仅收发模型消息；宿主动作由任务执行器校验、串行执行，不从自由文本推导动作。 */
internal class AgentModelClient(
    private val transport: AgentHttpTransport = AgentHttpsTransport,
    private val outputTokenBudget: Int = 2048,
    private val clock: () -> Long = System::currentTimeMillis,
    private val inputTextLimit: Int = 65_536,
    /** AI Insight 走聚合中转时模型种类不固定，关闭思考控制参数注入，避免非推理模型被拒。 */
    private val allowReasoningParams: Boolean = true
) {
    private val capabilities = AgentBoundedCache<String, AgentModelCapabilities>(clock = clock)
    private val tokenParameters = AgentBoundedCache<String, String>(clock = clock)
    private val rejectedReasoning = AgentBoundedCache<String, Boolean>(clock = clock)
    private val nonStreaming = AgentBoundedCache<String, Boolean>(clock = clock)
    private val jsonObjectOnly = AgentBoundedCache<String, Boolean>(clock = clock)

    init { require(outputTokenBudget in 256..8192 && inputTextLimit in 65_536..500_000) }

    fun setCapabilities(source: AgentModelSource, value: AgentModelCapabilities) {
        capabilities[source.fingerprint] = value
    }

    /** 同一显式探测共享注入的传输实现；JVM 替身不能因切换协议意外连接真实网络。 */
    internal fun decisionClientForProbe(): AgentDecisionClient = AgentDecisionClient(transport, clock)

    fun generate(
        source: AgentModelSource,
        messages: JSONArray,
        tools: JSONArray,
        withVision: Boolean,
        timeoutMs: Int,
        cancelled: () -> Boolean
    ): AgentModelTurn = generateImpl(source, messages, tools, withVision, timeoutMs, cancelled, probing = false)

    /** 结构化业务输出；仅在服务端明确拒绝 schema 格式时退回 JSON 模式，仍保留契约和本地校验。 */
    fun generateStructured(source: AgentModelSource, messages: JSONArray, schema: JSONObject,
        timeoutMs: Int, cancelled: () -> Boolean): AgentModelTurn =
        generateImpl(source, messages, JSONArray(), false, timeoutMs, cancelled, probing = false, schema = schema)

    /** 保留非流式入口的尾随取消 lambda，流式能力由业务显式选择。 */
    fun generateStructuredStreaming(source: AgentModelSource, messages: JSONArray, schema: JSONObject,
        timeoutMs: Int, cancelled: () -> Boolean, onDelta: (String) -> Unit): AgentModelTurn =
        generateImpl(source, messages, JSONArray(), false, timeoutMs, cancelled, probing = false, schema = schema, onDelta = onDelta)

    /** 只供显式无副作用能力挑战；普通任务没有跳过视觉门禁的参数。 */
    internal fun probeGenerate(
        source: AgentModelSource,
        messages: JSONArray,
        tools: JSONArray,
        withVision: Boolean,
        timeoutMs: Int,
        cancelled: () -> Boolean
    ): AgentModelTurn = generateImpl(source, messages, tools, withVision, timeoutMs, cancelled, probing = true)

    private fun generateImpl(
        source: AgentModelSource, messages: JSONArray, tools: JSONArray, withVision: Boolean,
        timeoutMs: Int, cancelled: () -> Boolean, probing: Boolean, schema: JSONObject? = null,
        onDelta: ((String) -> Unit)? = null
    ): AgentModelTurn {
        if (cancelled()) throw AgentModelException(AgentModelException.Reason.CANCELLED)
        if (source.protocol !in setOf(AgentSourceProtocol.CHAT, AgentSourceProtocol.ANTHROPIC))
            throw AgentModelException(AgentModelException.Reason.INVALID_REQUEST)
        if (withVision && !probing && capabilities[source.fingerprint]?.let { it.vision && it.fresh(clock()) } != true) {
            throw AgentModelException(AgentModelException.Reason.VISION_UNVERIFIED)
        }
        val allowed = validateTools(tools)
        val historyIds = validateMessages(messages, withVision)
        var streaming = onDelta != null && nonStreaming[source.fingerprint] != true
        val anthropic = source.protocol == AgentSourceProtocol.ANTHROPIC
        // Anthropic Messages 把 system 单独成字段，且不接受 response_format。
        val systemText = if (anthropic) messages.optJSONObject(0)
            ?.takeIf { it.optString("role") == "system" }?.optString("content") else null
        val chatMessages = if (anthropic) JSONArray().apply {
            for (i in 0 until messages.length()) {
                val message = messages.getJSONObject(i)
                if (message.optString("role") != "system") put(message)
            }
        } else JSONArray(messages.toString())
        val body = JSONObject().put("model", source.model).put("messages", chatMessages)
            .put("stream", streaming).apply { systemText?.let { put("system", it) } }
        if (tools.length() > 0) body.put("tools", JSONArray(tools.toString())).put("tool_choice", "auto")
        val optionalReasoning = if (allowReasoningParams && !anthropic) knownReasoningParams(source)
            ?.takeIf { rejectedReasoning[source.fingerprint] != true } else null
        optionalReasoning?.keys()?.forEach { body.put(it, optionalReasoning.get(it)) }
        var removedReasoning = false
        var objectMode = schema != null && jsonObjectOnly[source.fingerprint] == true
        fun applyFormat() {
            if (schema == null) return
            if (anthropic) {
                // 没有 response_format：把同一份 schema 写进 system，完整契约仍由业务层本地校验。
                val schemaText = "Reply with one JSON object matching this schema: " + schema.getJSONObject("schema")
                body.put("system", listOf(systemText.orEmpty(), schemaText).filter(String::isNotEmpty).joinToString("\n"))
            } else {
                body.put("response_format", if (objectMode) JSONObject().put("type", "json_object") else
                    JSONObject().put("type", "json_schema").put("json_schema", schema))
                val formatted = JSONArray(messages.toString())
                if (objectMode) {
                    val last = formatted.getJSONObject(formatted.length() - 1)
                    requireRequest(last.optString("role") == "user" && last.opt("content") is String)
                    last.put("content", last.getString("content") + "\nJSON schema：" + schema.getJSONObject("schema"))
                }
                body.put("messages", formatted)
            }
        }
        applyFormat()
        val deadline = System.nanoTime() + timeoutMs.coerceIn(1, AgentHttpsTransport.MAX_TIMEOUT_MS) * 1_000_000L
        var parameter = if (anthropic) "max_tokens" else tokenParameters[source.fingerprint] ?: "max_tokens"
        val rejectedTokens = hashSetOf<String>()
        val attempts = if (schema == null) 3 else if (streaming) 5 else 4
        repeat(attempts) { attempt ->
            body.remove("max_tokens")
            body.remove("max_completion_tokens")
            body.put(parameter, outputTokenBudget)
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            if (bytes.size > AgentHttpsTransport.MAX_REQUEST_BYTES) throw AgentModelException(AgentModelException.Reason.TOO_LARGE)
            val remaining = ((deadline - System.nanoTime()) / 1_000_000L).toInt()
            if (remaining <= 0) throw AgentModelException(AgentModelException.Reason.TIMEOUT)
            if (cancelled()) throw AgentModelException(AgentModelException.Reason.CANCELLED)
            try {
                val payload = if (!streaming) transport.post(source, bytes, remaining, cancelled)
                    else transport.postStreaming(source, bytes, remaining, cancelled) { delta ->
                        if (cancelled()) throw AgentModelException(AgentModelException.Reason.CANCELLED)
                        checkNotNull(onDelta)(delta)
                    }
                if (cancelled()) throw AgentModelException(AgentModelException.Reason.CANCELLED)
                // 响应已完整到达，不再因总时限丢弃；此前每个流式增量已经交付给界面。
                // 流式 payload 已由 AgentChatStream 统一合成 OpenAI 形状，只有非流式才按来源协议解析。
                val turn = decode(payload, allowed, historyIds,
                    if (streaming) AgentSourceProtocol.CHAT else source.protocol)
                tokenParameters[source.fingerprint] = parameter
                if (removedReasoning) rejectedReasoning[source.fingerprint] = true
                if (schema != null && objectMode) jsonObjectOnly[source.fingerprint] = true
                return turn
            } catch (e: AgentModelException) {
                // 只对明确拒绝的参数做有界回退，共用截止时间；永不剥掉 tools 或输出预算。
                if (attempt == attempts - 1) throw e
                when {
                    e.reason == AgentModelException.Reason.STREAM_PARAMETER && streaming -> {
                        streaming = false; body.put("stream", false)
                        nonStreaming[source.fingerprint] = true
                    }
                    e.reason == AgentModelException.Reason.RESPONSE_FORMAT_PARAMETER && schema != null && !objectMode -> {
                        objectMode = true
                        applyFormat()
                    }
                    e.reason == AgentModelException.Reason.TOKEN_PARAMETER &&
                        (e.rejectedParameter == null || e.rejectedParameter == parameter) -> {
                        rejectedTokens += parameter
                        parameter = if (parameter == "max_tokens") "max_completion_tokens" else "max_tokens"
                        if (parameter in rejectedTokens) throw e
                    }
                    e.reason == AgentModelException.Reason.OPTIONAL_PARAMETER && optionalReasoning != null &&
                        !removedReasoning && e.rejectedParameter?.let { optionalReasoning.has(it) } == true -> {
                        optionalReasoning.keys().forEach(body::remove)
                        removedReasoning = true
                    }
                    else -> throw e
                }
            }
        }
        throw AgentModelException(AgentModelException.Reason.INVALID_RESPONSE)
    }

    /** 复用现有已验证厂商写法；未知中转不试探 reasoning_effort=none。 */
    private fun knownReasoningParams(source: AgentModelSource): JSONObject? {
        val host = URI(source.resolvedEndpoint).host.lowercase(Locale.ROOT)
        val known = host in setOf("openrouter.ai", "api.deepseek.com", "api.river.ai", "open.bigmodel.cn", "api.z.ai",
            "api.siliconflow.cn", "api.siliconflow.com") || host.endsWith(".volces.com") || host.endsWith(".aliyuncs.com")
        return if (known) AiChatCompat.reasoningParams(source.resolvedEndpoint, ReasoningLevel.OFF) else null
    }

    private fun validateTools(tools: JSONArray): Set<String> {
        requireRequest(tools.length() <= 24)
        val names = linkedSetOf<String>()
        for (index in 0 until tools.length()) {
            val tool = tools.optJSONObject(index) ?: invalidRequest()
            val function = tool.optJSONObject("function") ?: invalidRequest()
            val name = function.optString("name")
            requireRequest(tool.optString("type") == "function" && NAME.matches(name) && names.add(name))
            requireRequest(function.optJSONObject("parameters") != null)
        }
        return names
    }

    private fun validateMessages(messages: JSONArray, withVision: Boolean): Set<String> {
        requireRequest(messages.length() in 1..96)
        val seenIds = hashSetOf<String>()
        val pending = hashSetOf<String>()
        var images = 0
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: invalidRequest()
            val role = message.optString("role")
            requireRequest(role in setOf("system", "user", "assistant", "tool"))
            requireRequest(!message.has("function_call") || message.isNull("function_call"))
            if (role == "tool") {
                val id = message.opt("tool_call_id") as? String ?: invalidRequest()
                requireRequest(pending.remove(id))
                requireRequest(message.opt("content") is String)
            } else {
                requireRequest(pending.isEmpty())
                requireRequest(!message.has("tool_call_id"))
            }
            when (val content = message.opt("content")) {
                is String -> requireRequest(content.length <= inputTextLimit)
                is JSONArray -> {
                    requireRequest(role == "user" && content.length() in 1..8)
                    for (partIndex in 0 until content.length()) {
                        val part = content.optJSONObject(partIndex) ?: invalidRequest()
                        when (part.optString("type")) {
                            "text" -> requireRequest(part.opt("text") is String && part.getString("text").length <= inputTextLimit)
                            "image_url" -> {
                                requireRequest(withVision)
                                val image = part.optJSONObject("image_url") ?: invalidRequest()
                                requireRequest(validImageDataUrl(image.optString("url")))
                                images++
                                requireRequest(images <= 4)
                            }
                            else -> invalidRequest()
                        }
                    }
                }
                null -> requireRequest(role == "assistant")
                JSONObject.NULL -> requireRequest(role == "assistant")
                else -> invalidRequest()
            }
            if (message.has("tool_calls") && !message.isNull("tool_calls")) {
                requireRequest(role == "assistant")
                val calls = try { parseCalls(message.optJSONArray("tool_calls") ?: invalidRequest(), null) }
                catch (_: AgentModelException) { invalidRequest() }
                for (call in calls) {
                    requireRequest(seenIds.add(call.id))
                    pending.add(call.id)
                }
            }
            if (message.has("reasoning_content")) {
                requireRequest(role == "assistant" && message.opt("reasoning_content") is String &&
                    message.getString("reasoning_content").length <= MAX_REASONING_CHARS)
            }
        }
        requireRequest(pending.isEmpty())
        return seenIds
    }

    internal fun decode(payload: String, allowedNames: Set<String>, historyIds: Set<String> = emptySet(),
        protocol: AgentSourceProtocol = AgentSourceProtocol.CHAT): AgentModelTurn {
        if (protocol == AgentSourceProtocol.ANTHROPIC) return decodeAnthropic(payload)
        val root = AgentJson.objectOf(payload, AgentHttpsTransport.MAX_RESPONSE_BYTES)
        val choices = root.optJSONArray("choices") ?: invalidResponse()
        if (choices.length() != 1) invalidResponse()
        val choice = choices.optJSONObject(0) ?: invalidResponse()
        val finish = choice.optString("finish_reason")
        if (finish == "length") throw AgentModelException(AgentModelException.Reason.OUTPUT_LIMIT)
        if (finish !in setOf("stop", "tool_calls")) invalidResponse()
        val original = choice.optJSONObject("message") ?: invalidResponse()
        if (original.optString("role") != "assistant" || (original.has("function_call") && !original.isNull("function_call"))) invalidResponse()
        val text = when (val content = original.opt("content")) {
            is String -> content.takeIf { it.length <= MAX_TEXT_CHARS } ?: invalidResponse()
            is JSONArray -> {
                if (content.length() > 8) invalidResponse()
                buildString {
                    for (index in 0 until content.length()) {
                        val part = content.optJSONObject(index) ?: invalidResponse()
                        if (part.optString("type") != "text" || part.opt("text") !is String) invalidResponse()
                        append(part.getString("text"))
                        if (length > MAX_TEXT_CHARS) invalidResponse()
                    }
                }
            }
            null -> ""
            JSONObject.NULL -> ""
            else -> invalidResponse()
        }
        val calls = if (original.has("tool_calls") && !original.isNull("tool_calls")) {
            parseCalls(original.optJSONArray("tool_calls") ?: invalidResponse(), allowedNames)
        } else emptyList()
        if (calls.any { it.id in historyIds } || (finish == "tool_calls" && calls.isEmpty())) invalidResponse()
        val message = JSONObject().put("role", "assistant").put("content", if (text.isEmpty()) JSONObject.NULL else text)
        if (calls.isNotEmpty()) message.put("tool_calls", JSONArray(original.getJSONArray("tool_calls").toString()))
        if (original.has("reasoning_content")) {
            val reasoning = original.opt("reasoning_content")
            if (reasoning != null && reasoning != JSONObject.NULL) {
                if (reasoning !is String || reasoning.length > MAX_REASONING_CHARS) invalidResponse()
                message.put("reasoning_content", reasoning)
            }
        }
        val usage = root.optJSONObject("usage")?.let {
            AgentModelUsage(tokenCount(it, "prompt_tokens"), tokenCount(it, "completion_tokens"), tokenCount(it, "total_tokens"))
        }
        return AgentModelTurn(message, calls, text, usage)
    }

    /** Anthropic Messages 非流式响应：content[].text 收敛成正文，stop_reason 映射为完成状态。 */
    private fun decodeAnthropic(payload: String): AgentModelTurn {
        val root = AgentJson.objectOf(payload, AgentHttpsTransport.MAX_RESPONSE_BYTES)
        val stop = root.optString("stop_reason")
        if (stop == "max_tokens") throw AgentModelException(AgentModelException.Reason.OUTPUT_LIMIT)
        if (stop !in setOf("end_turn", "stop_sequence")) invalidResponse()
        val parts = root.optJSONArray("content") ?: invalidResponse()
        val text = buildString {
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                if (part.optString("type") == "text") append(part.optString("text"))
            }
        }.takeIf { it.length <= MAX_TEXT_CHARS } ?: invalidResponse()
        val message = JSONObject().put("role", "assistant").put("content", text)
        val usage = root.optJSONObject("usage")?.let {
            val input = it.optLong("input_tokens")
            val output = it.optLong("output_tokens")
            AgentModelUsage(input, output, input + output)
        }
        return AgentModelTurn(message, emptyList(), text, usage)
    }

    private fun parseCalls(array: JSONArray, allowed: Set<String>?): List<AgentModelToolCall> {
        if (array.length() !in 0..4) invalidResponse()
        val seen = hashSetOf<String>()
        return (0 until array.length()).map { index ->
            val call = array.optJSONObject(index) ?: invalidResponse()
            val id = call.opt("id") as? String ?: invalidResponse()
            if (id.length !in 1..128 || id.any { it.isWhitespace() || it.isISOControl() } || !seen.add(id) ||
                call.optString("type") != "function") invalidResponse()
            val function = call.optJSONObject("function") ?: invalidResponse()
            val name = function.optString("name")
            if (!NAME.matches(name) || (allowed != null && name !in allowed)) invalidResponse()
            val arguments = function.opt("arguments") as? String ?: invalidResponse()
            AgentModelToolCall(id, name, AgentJson.objectOf(arguments))
        }
    }

    private fun tokenCount(json: JSONObject, key: String): Long {
        val value = json.opt(key) as? Number ?: return 0
        return value.toLong().coerceIn(0, 1_000_000_000L)
    }

    private fun requireRequest(value: Boolean) { if (!value) invalidRequest() }
    private fun invalidRequest(): Nothing = throw AgentModelException(AgentModelException.Reason.INVALID_REQUEST)
    private fun invalidResponse(): Nothing = throw AgentModelException(AgentModelException.Reason.INVALID_RESPONSE)

    companion object {
        /** 不跨来源转发厂商私有推理字段；保留工具调用与结果配对，且不修改任务持有的历史。 */
        internal fun historyForSource(messages: JSONArray, previousSourceFingerprint: String?, source: AgentModelSource): JSONArray =
            JSONArray(messages.toString()).also { history ->
                if (previousSourceFingerprint != source.fingerprint) {
                    for (index in 0 until history.length()) history.optJSONObject(index)?.remove("reasoning_content")
                }
            }
        private val NAME = Regex("[A-Za-z_][A-Za-z0-9_-]{0,63}")
        private val IMAGE_DATA = Regex("data:image/(?:png|jpeg);base64,[A-Za-z0-9+/]+={0,2}")
        private const val MAX_TEXT_CHARS = 65_536
        private const val MAX_REASONING_CHARS = 131_072
        internal fun validImageDataUrl(value: String): Boolean = value.length <= 1_048_576 && IMAGE_DATA.matches(value)
    }
}
