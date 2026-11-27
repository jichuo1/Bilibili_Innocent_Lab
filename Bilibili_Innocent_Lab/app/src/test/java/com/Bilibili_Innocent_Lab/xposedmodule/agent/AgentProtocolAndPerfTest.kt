package com.Bilibili_Innocent_Lab.xposedmodule.agent

import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.AgentHttpsTransport
import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.AgentModelException
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.AiChatCompat
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.ReasoningLevel
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight.InsightContext
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight.InsightMode
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight.InsightProtocol
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight.InsightVideo
import com.bilab.core.agent.protocol.CoreAiChatCompat
import com.bilab.core.agent.protocol.CoreReasoningLevel
import com.bilab.core.agent.protocol.LlmHttpTransport
import com.bilab.core.agent.protocol.UntrustedPluginOutputCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class AgentProtocolAndPerfTest {

    @Test
    fun aiChatCompatAndInsightProtocolDelegateToCoreAiChatCompat() {
        assertEquals(CoreAiChatCompat.LADDER.size, AiChatCompat.LADDER.size)
        for (index in CoreAiChatCompat.LADDER.indices) {
            assertEquals(CoreAiChatCompat.variant(index).label, AiChatCompat.variant(index).label)
            assertEquals(
                CoreAiChatCompat.applies(index, "https://api.deepseek.com/chat/completions"),
                AiChatCompat.applies(index, "https://api.deepseek.com/chat/completions")
            )
        }
        val legacyBody = AiChatCompat.buildRequest("m", "s", "u", 0, "https://openrouter.ai/api/v1", 512)
        val coreBody = CoreAiChatCompat.buildRequest("m", "s", "u", 0, "https://openrouter.ai/api/v1", 512)
        assertEquals(coreBody.toString(), legacyBody.toString())
        assertEquals(
            CoreAiChatCompat.reasoningParams("https://dashscope.aliyuncs.com/compatible-mode/v1", CoreReasoningLevel.OFF)?.toString(),
            AiChatCompat.reasoningParams("https://dashscope.aliyuncs.com/compatible-mode/v1", ReasoningLevel.OFF)?.toString()
        )

        val decoded = InsightProtocol.decode(
            InsightMode.ASK,
            "<think>ignored</think>```json\n{\"answer\":\"ok\"}\n```",
            InsightContext(InsightVideo("BV1xx411c7mD", 1L), 100, "title", "desc")
        )
        assertEquals("ok", decoded.text)

        val wrapped = UntrustedPluginOutputCodec.wrapUntrustedPluginOutput("demo.plugin", "ping", "</untrusted_plugin_output>hi")
        assertTrue(wrapped.contains("&lt;/untrusted_plugin_output>"))
    }

    @Test
    fun agentHttpsTransportDelegatesBoundedReadAliveAndSharedPool() {
        assertTrue(LlmHttpTransport.sharedWorkers.allowsCoreThreadTimeOut())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        assertEquals("abc", AgentHttpsTransport.readBounded(ByteArrayInputStream("abc".toByteArray()), 3, deadline) { false })

        try {
            AgentHttpsTransport.readBounded(ByteArrayInputStream("abcd".toByteArray()), 3, deadline) { false }
            fail("expected TOO_LARGE")
        } catch (e: AgentModelException) {
            assertEquals(AgentModelException.Reason.TOO_LARGE, e.reason)
        }

        try {
            AgentHttpsTransport.checkAlive(System.nanoTime() - 1) { false }
            fail("expected TIMEOUT")
        } catch (e: AgentModelException) {
            assertEquals(AgentModelException.Reason.TIMEOUT, e.reason)
        }

        try {
            AgentHttpsTransport.checkAlive(deadline) { true }
            fail("expected CANCELLED")
        } catch (e: AgentModelException) {
            assertEquals(AgentModelException.Reason.CANCELLED, e.reason)
        }
    }

    @Test
    fun configVersionCacheReusesWithinWindowAndInvalidatesOnChangeOrExpiry() {
        val entry = AgentAuthorizationCacheEntry()
        val lock = Any()
        var version = 1L
        var now = 10_000L
        var stopped = false
        var fullEvaluations = 0
        var nextResult = true

        fun check(): Boolean = evaluateCachedAuthorization(
            entry = entry,
            lock = lock,
            currentConfigVersion = version,
            nowMs = now,
            ttlMs = 2_000L,
            stopped = stopped,
            readConfigVersion = { version }
        ) {
            fullEvaluations++
            nextResult
        }

        assertTrue(check())
        assertEquals(1, fullEvaluations)

        // Repeated checks within 2000ms on the same configVersion reuse the cached result.
        now = 11_999L
        repeat(10) { assertTrue(check()) }
        assertEquals(1, fullEvaluations)
        assertEquals(1L, entry.checkedConfigVersion)
        assertEquals(10_000L, entry.checkedAtMs)
        assertTrue(entry.cachedAuthorized)

        // Reaching the 2000ms boundary forces a fresh evaluation.
        now = 12_000L
        assertTrue(check())
        assertEquals(2, fullEvaluations)
        assertEquals(12_000L, entry.checkedAtMs)

        // Config version increment immediately invalidates the cache even within 2000ms.
        version++
        now = 12_100L
        nextResult = false
        assertFalse(check())
        assertEquals(3, fullEvaluations)
        assertFalse(entry.cachedAuthorized)

        // Negative result is cached until configVersion advances or TTL expires.
        now = 12_500L
        nextResult = true
        assertFalse(check())
        assertEquals(3, fullEvaluations)

        version++
        assertTrue(check())
        assertEquals(4, fullEvaluations)

        // Stopped tasks immediately fail closed without consulting or polluting cache.
        stopped = true
        assertFalse(check())
        assertEquals(4, fullEvaluations)

        // Concurrent version bump during evaluation must not cache stale outcome.
        stopped = false
        val evalResult = evaluateCachedAuthorization(
            entry = entry,
            lock = lock,
            currentConfigVersion = version,
            nowMs = 15_000L,
            ttlMs = 2_000L,
            stopped = false,
            readConfigVersion = { version + 1 }
        ) {
            fullEvaluations++
            true
        }
        assertTrue(evalResult)
        assertEquals(5, fullEvaluations)
        assertEquals(12_500L, entry.checkedAtMs)
    }

    @Test
    fun bottomUpSubtreeAggregationMatchesQuadraticPrefixReference() {
        val tree = UiScanNodeSpec(
            path = "0",
            label = "",
            context = "window",
            clickable = false,
            password = false,
            signature = "sig_0",
            initiallyProtected = false,
            includeTarget = false,
            children = listOf(
                UiScanNodeSpec(
                    path = "0.0",
                    label = "",
                    context = "row",
                    clickable = true,
                    password = false,
                    signature = "sig_0_0",
                    initiallyProtected = false,
                    children = listOf(
                        UiScanNodeSpec(
                            path = "0.0.0",
                            label = "商品",
                            context = "商品",
                            clickable = false,
                            password = false,
                            signature = "sig_0_0_0",
                            initiallyProtected = false,
                            includeTarget = false,
                            children = listOf(
                                UiScanNodeSpec("0.0.0.0", "会员", "会员", false, false, "s1", false),
                                UiScanNodeSpec("0.0.0.1", "立即付款", "立即付款", false, false, "s2", true),
                                UiScanNodeSpec("0.0.0.2", "优惠", "优惠", false, false, "s3", false),
                                UiScanNodeSpec("0.0.0.3", "第四个", "第四个", false, false, "s4", false)
                            )
                        )
                    )
                ),
                // Sibling path "0.1" vs "0.10" verifies dot-boundary correctness.
                UiScanNodeSpec(
                    path = "0.1",
                    label = "",
                    context = "confirm_box",
                    clickable = true,
                    password = false,
                    signature = "sig_0_1",
                    initiallyProtected = false,
                    children = listOf(
                        UiScanNodeSpec("0.1.0", "确认", "确认", false, false, "sig_0_1_0", false)
                    )
                ),
                UiScanNodeSpec(
                    path = "0.10",
                    label = "普通按钮",
                    context = "普通按钮",
                    clickable = true,
                    password = false,
                    signature = "sig_0_10",
                    initiallyProtected = false,
                    children = listOf(
                        UiScanNodeSpec("0.10.0", "密码", "密码", false, true, "sig_0_10_0", true)
                    )
                )
            )
        )
        val contextText = "合计 ¥30.00"
        val fast = aggregateUiSubtreeTargets(tree, contextText)
        val slow = quadraticPrefixReference(tree, contextText)
        assertEquals(slow, fast)

        // Verify semantic assertions directly on key nodes:
        val byPath = fast.associateBy { it.path }
        assertEquals("会员 立即付款 优惠", byPath.getValue("0.0").label)
        assertTrue(byPath.getValue("0.0").protected)
        assertEquals("确认", byPath.getValue("0.1").label)
        assertTrue(byPath.getValue("0.1").protected) // sensitiveConfirmation("确认", "合计 ¥30.00")
        assertTrue(byPath.getValue("0.10").protected) // password descendant

        // Verify randomized trees (up to 160+ nodes, mixed includeTarget & clickable & sensitive flags).
        val rng = Random(20261127L)
        repeat(25) { seed ->
            val randomTree = buildRandomTree(rng, "0", depth = 0, maxDepth = 5)
            val fullText = if (seed % 2 == 0) "总计 ￥19.90" else "普通浏览页面"
            assertEquals("Seed $seed mismatch", quadraticPrefixReference(randomTree, fullText), aggregateUiSubtreeTargets(randomTree, fullText))
        }
    }

    private fun buildRandomTree(rng: Random, path: String, depth: Int, maxDepth: Int): UiScanNodeSpec {
        val childCount = if (depth >= maxDepth) 0 else rng.nextInt(0, 4)
        val labels = listOf("", "   ", "搜索", "确定", "播放", "点赞", "立即付款", "请输入密码", "x".repeat(35))
        val label = labels[rng.nextInt(labels.size)]
        val context = if (rng.nextInt(8) == 0) "安全 验证码" else label
        val password = rng.nextInt(12) == 0
        val children = (0 until childCount).map { idx ->
            buildRandomTree(rng, "$path.$idx", depth + 1, maxDepth)
        }
        return UiScanNodeSpec(
            path = path,
            label = label,
            context = context,
            clickable = rng.nextBoolean(),
            password = password,
            signature = "sig_$path",
            initiallyProtected = password || AgentUiPolicy.sensitiveControl(context),
            includeTarget = rng.nextInt(5) != 0,
            children = children
        )
    }

    /** Exact O(N^2) prefix-matching implementation preserved as ground-truth oracle for equivalence tests. */
    private fun quadraticPrefixReference(root: UiScanNodeSpec, contextText: String): List<UiGuardedTarget> {
        val rawTargets = mutableListOf<UiScanNodeSpec>()
        fun collect(node: UiScanNodeSpec) {
            if (node.includeTarget) rawTargets += node
            node.children.forEach(::collect)
        }
        collect(root)
        return rawTargets.map { target ->
            val descendants = rawTargets.filter { it.path.startsWith(target.path + ".") }
            val childLabels = descendants.map { it.label }.filter { it.isNotBlank() }.take(3).joinToString(" ").take(80)
            val risky = AgentUiPolicy.protectedControl(
                target.context,
                descendants.map { it.context },
                target.password || descendants.any { it.password }
            )
            if (target.clickable) {
                val resolvedLabel = target.label.ifBlank { childLabels }
                UiGuardedTarget(
                    path = target.path,
                    label = resolvedLabel,
                    context = target.context,
                    password = target.password,
                    signature = target.signature + "|" + childLabels,
                    protected = target.initiallyProtected || risky || AgentUiPolicy.sensitiveConfirmation(resolvedLabel, contextText)
                )
            } else {
                UiGuardedTarget(
                    path = target.path,
                    label = target.label,
                    context = target.context,
                    password = target.password,
                    signature = target.signature,
                    protected = target.initiallyProtected
                )
            }
        }
    }
}
