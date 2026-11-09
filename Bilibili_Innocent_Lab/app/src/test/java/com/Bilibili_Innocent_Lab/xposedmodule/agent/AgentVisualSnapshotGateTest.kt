package com.Bilibili_Innocent_Lab.xposedmodule.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * 性能报告 P1-05 的补充：截图编码挪到 worker 之后，编码期间到达的窗口变化事件必须仍然使“视觉快照有效”记录失效，
 * 交付不得把它写回。
 */
class AgentVisualSnapshotGateTest {
    @Test fun commitWithoutAnyWindowChangeRecordsTheSnapshot() {
        val gate = AgentVisualSnapshotGate()
        val token = gate.token()
        assertTrue(gate.commit(token, "s1", now = 1_000L))
        assertTrue(gate.allows("s1", now = 1_000L))
        assertTrue(gate.allows("s1", now = 61_000L))
        assertFalse("older than 60 s", gate.allows("s1", now = 61_001L))
        assertFalse("another snapshot", gate.allows("s2", now = 1_000L))
        assertFalse("clock going backwards is invalid", gate.allows("s1", now = 999L))
    }

    @Test fun aWindowChangeDuringEncodingStopsTheLateWriteBack() {
        val gate = AgentVisualSnapshotGate()
        gate.commit(gate.token(), "old", 100L)
        // 回调内记下 token，随后进入 worker 编码……
        val token = gate.token()
        // ……编码期间 TYPE_WINDOW_STATE_CHANGED 到达：记录立即失效。
        gate.invalidate()
        assertFalse(gate.allows("old", 150L))
        // 交付：旧实现会在这里把记录写回；现在代次变了，拒绝写回。
        assertFalse(gate.isCurrent(token))
        assertFalse(gate.commit(token, "shot", 200L))
        assertFalse("the late result must not become a valid visual snapshot", gate.allows("shot", 200L))
        assertFalse(gate.allows("old", 200L))
    }

    @Test fun anEventBeforeTheTokenDoesNotBlockTheCommit() {
        val gate = AgentVisualSnapshotGate()
        gate.invalidate()
        gate.invalidate()
        val token = gate.token()
        assertTrue(gate.commit(token, "s", 5L))
        assertTrue(gate.allows("s", 5L))
    }

    @Test fun anyInvalidationAfterCommitDropsTheRecord() {
        val gate = AgentVisualSnapshotGate()
        gate.commit(gate.token(), "s", 1L)
        gate.invalidate()
        assertFalse(gate.allows("s", 2L))
    }

    @Test fun tokensFromBeforeALaterCommitAreNotResurrected() {
        val gate = AgentVisualSnapshotGate()
        val first = gate.token()
        assertTrue(gate.commit(first, "a", 1L))
        // 同一个 token 的第二次提交仍然成立（代次没变）；变化后任何旧 token 都失效。
        assertTrue(gate.commit(first, "b", 2L))
        gate.invalidate()
        assertFalse(gate.commit(first, "c", 3L))
        assertFalse(gate.allows("c", 3L))
    }

    @Test fun differentialAgainstTheSynchronousLegacyModel() {
        // 旧同步实现：整段回调（采样 + 写入）不会被事件打断，事件只能排在回调之前或之后。
        // 把它与「token + 事件可能插在编码期间」的新实现比较：事件没有插入时结果一致；插入时新实现只会更保守（不写回）。
        val random = Random(20261029L)
        repeat(5_000) {
            val gate = AgentVisualSnapshotGate()
            var legacyId: String? = null
            var legacyAt = 0L
            val events = random.nextInt(4)
            repeat(events) { gate.invalidate(); legacyId = null }
            val token = gate.token()
            val interleaved = random.nextBoolean()
            if (interleaved) gate.invalidate() // 事件落在编码期间
            val committed = gate.commit(token, "shot", 10L)
            if (!interleaved) {
                legacyId = "shot"; legacyAt = 10L
                assertTrue(committed)
            } else {
                assertFalse(committed)
            }
            val now = 10L + random.nextInt(70_000)
            val legacyAllows = legacyId == "shot" && now - legacyAt in 0..60_000
            assertEquals("interleaved=$interleaved events=$events now=$now", legacyAllows, gate.allows("shot", now))
        }
    }
}
