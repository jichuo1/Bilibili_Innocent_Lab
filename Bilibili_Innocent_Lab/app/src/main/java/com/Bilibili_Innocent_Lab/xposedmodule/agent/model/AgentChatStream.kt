package com.Bilibili_Innocent_Lab.xposedmodule.agent.model

import org.json.JSONArray
import org.json.JSONObject
import java.io.FilterInputStream
import java.io.InputStream

/** SSE 只承载正文增量；推理字段不进入 UI。完成标记仍交由原有 turn/业务契约校验。 */
internal object AgentChatStream {
    const val MAX_WIRE_BYTES = 4 * 1024 * 1024
    fun read(input: InputStream, checkAlive: () -> Unit, onDelta: (String) -> Unit): String {
        val bounded = object : FilterInputStream(input) {
            var bytes = 0
            fun counted(count: Int): Int {
                if (count > 0) bytes += count
                if (bytes > MAX_WIRE_BYTES) throw AgentModelException(AgentModelException.Reason.TOO_LARGE)
                return count
            }
            override fun read(): Int = super.read().also { if (it >= 0) counted(1) }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = counted(`in`.read(buffer, offset, length))
        }
        val reader = bounded.bufferedReader(Charsets.UTF_8)
        val text = StringBuilder()
        val event = StringBuilder()
        var finish: String? = null
        var done = false
        var reasoningChars = 0
        var usage: JSONObject? = null
        fun invalid(): Nothing = throw AgentModelException(AgentModelException.Reason.INVALID_RESPONSE)
        fun dispatch() {
            if (event.isEmpty()) return
            checkAlive()
            val data = event.toString(); event.setLength(0)
            if (data == "[DONE]") { if (finish == null) invalid(); done = true; return }
            if (done) invalid()
            val root = AgentJson.objectOf(data, AgentHttpsTransport.MAX_RESPONSE_BYTES)
            if (root.has("error")) invalid()
            root.optJSONObject("usage")?.let { usage = it }
            val choices = root.optJSONArray("choices") ?: invalid()
            if (choices.length() == 0 && root.optJSONObject("usage") != null) return
            if (choices.length() != 1) invalid()
            val choice = choices.optJSONObject(0) ?: invalid()
            if (choice.optInt("index", 0) != 0) invalid()
            val delta = choice.optJSONObject("delta") ?: invalid()
            if (delta.has("tool_calls") || delta.has("function_call") ||
                (delta.has("refusal") && !delta.isNull("refusal"))) invalid()
            if (delta.has("role") && delta.optString("role") != "assistant") invalid()
            val content = delta.opt("content")
            if (content != null && content != JSONObject.NULL) {
                if (content !is String || (finish != null && content.isNotEmpty())) invalid()
                if (text.length + content.length > 65_536) throw AgentModelException(AgentModelException.Reason.TOO_LARGE)
                text.append(content)
                if (content.isNotEmpty()) onDelta(content)
            }
            (delta.opt("reasoning_content") as? String)?.let { reasoningChars += it.length }
            if (reasoningChars > 131_072) throw AgentModelException(AgentModelException.Reason.TOO_LARGE)
            if (!choice.isNull("finish_reason")) {
                val reason = choice.optString("finish_reason")
                if (reason == "length") throw AgentModelException(AgentModelException.Reason.OUTPUT_LIMIT)
                if (reason != "stop" || finish != null) invalid()
                finish = reason
            }
        }
        var firstLine = true
        while (!done) {
            checkAlive()
            val line = (reader.readLine() ?: break).let { if (firstLine) it.removePrefix("\uFEFF") else it }
            firstLine = false
            if (line.length > 131_072) throw AgentModelException(AgentModelException.Reason.TOO_LARGE)
            if (line.isEmpty()) dispatch()
            else if (line.startsWith("data:")) {
                if (event.isNotEmpty()) event.append('\n')
                event.append(line.substring(5).removePrefix(" "))
                if (event.length > 131_072) throw AgentModelException(AgentModelException.Reason.TOO_LARGE)
            }
        }
        // EOF 不能补发缺失空行的半个事件，否则截断响应可能被误当成完成。
        if (event.isNotEmpty()) invalid()
        checkAlive()
        if (finish != "stop") invalid()
        return JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", finish)
            .put("message", JSONObject().put("role", "assistant").put("content", text.toString()))))
            .apply { usage?.let { put("usage", it) } }.toString()
    }
}
