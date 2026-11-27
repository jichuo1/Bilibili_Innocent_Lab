package com.bilab.core.agent.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreAiChatCompatTest {

    @Test
    fun ladderCoercesIndexAndGeneratesLabels() {
        assertEquals(7, CoreAiChatCompat.LADDER.size)
        assertEquals("strict+nothink", CoreAiChatCompat.variant(-1).label)
        assertEquals("plain+no-system", CoreAiChatCompat.variant(999).label)
        assertEquals("plain+min-think", CoreAiChatCompat.variant(2).label)
        assertEquals("plain+low-think", CoreAiChatCompat.variant(3).label)
        assertEquals("strict", CoreAiChatCompat.variant(4).label)
    }

    @Test
    fun appliesRespectsProviderCapabilities() {
        val zhipu = "https://open.bigmodel.cn/api/paas/v4/chat/completions"
        assertTrue(CoreAiChatCompat.applies(0, zhipu))
        assertTrue(CoreAiChatCompat.applies(1, zhipu))
        assertFalse(CoreAiChatCompat.applies(2, zhipu))
        assertFalse(CoreAiChatCompat.applies(3, zhipu))
        assertTrue(CoreAiChatCompat.applies(4, zhipu))

        val deepseek = "https://api.deepseek.com/chat/completions"
        assertTrue(CoreAiChatCompat.applies(0, deepseek))
        assertFalse(CoreAiChatCompat.applies(2, deepseek))
        assertTrue(CoreAiChatCompat.applies(3, deepseek))

        val openrouter = "https://openrouter.ai/api/v1/chat/completions"
        assertEquals("none", CoreAiChatCompat.reasoningParams(openrouter, CoreReasoningLevel.OFF)!!.getJSONObject("reasoning").getString("effort"))
        assertEquals("minimal", CoreAiChatCompat.reasoningParams(openrouter, CoreReasoningLevel.MINIMAL)!!.getJSONObject("reasoning").getString("effort"))
        assertEquals("low", CoreAiChatCompat.reasoningParams(openrouter, CoreReasoningLevel.LOW)!!.getJSONObject("reasoning").getString("effort"))
        assertNull(CoreAiChatCompat.reasoningParams(openrouter, CoreReasoningLevel.DEFAULT))

        val bailian = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
        assertFalse(CoreAiChatCompat.reasoningParams(bailian, CoreReasoningLevel.OFF)!!.getBoolean("enable_thinking"))
        assertNull(CoreAiChatCompat.reasoningParams(bailian, CoreReasoningLevel.LOW))
    }

    @Test
    fun buildRequestCoversStrictPlainAndNoSystemVariants() {
        val strict = CoreAiChatCompat.buildRequest("m", "sys", "usr", 0, "https://api.deepseek.com/chat/completions", 256)
        assertEquals("disabled", strict.getJSONObject("thinking").getString("type"))
        assertEquals(256, strict.getInt("max_tokens"))
        assertEquals(0, strict.getInt("temperature"))
        assertEquals("json_object", strict.getJSONObject("response_format").getString("type"))
        assertEquals("system", strict.getJSONArray("messages").getJSONObject(0).getString("role"))

        val noSystem = CoreAiChatCompat.buildRequest("m", "sys", "usr", CoreAiChatCompat.LADDER.lastIndex, "not a url", 256)
        assertFalse(noSystem.has("max_tokens"))
        assertFalse(noSystem.has("response_format"))
        assertEquals(1, noSystem.getJSONArray("messages").length())
        assertEquals("sys\n\nusr", noSystem.getJSONArray("messages").getJSONObject(0).getString("content"))
    }

    @Test
    fun responseHelpersDecodeContentTruncationAndFencedJson() {
        val openai = JSONObject()
            .put("choices", org.json.JSONArray().put(JSONObject()
                .put("finish_reason", "length")
                .put("message", JSONObject().put("content", "<think>noise {bad</think>```json\n{\"ok\":true}\n```"))))
            .toString()
        val content = CoreAiChatCompat.contentOf(openai)
        assertNotNull(content)
        assertTrue(CoreAiChatCompat.truncated(openai))
        assertTrue(CoreAiChatCompat.findJsonObject(content!!) { it.optBoolean("ok") }!!.getBoolean("ok"))

        val workersAi = JSONObject().put("result", JSONObject().put("response", "{\"k\":1}")).toString()
        assertEquals("{\"k\":1}", CoreAiChatCompat.contentOf(workersAi))
        assertFalse(CoreAiChatCompat.truncated(workersAi))
        assertNull(CoreAiChatCompat.contentOf("not-json"))
    }
}
