package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex

import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult.Reason
import io.github.jichuo1.dexmeguviz.DecisionReason
import io.github.jichuo1.dexmeguviz.EntityPredicate
import io.github.jichuo1.dexmeguviz.EntityRelation
import io.github.jichuo1.dexmeguviz.EntityTextField
import io.github.jichuo1.dexmeguviz.ExecutionStatus
import io.github.jichuo1.dexmeguviz.GraphExpression
import io.github.jichuo1.dexmeguviz.GraphPredicate
import io.github.jichuo1.dexmeguviz.GraphQuery
import io.github.jichuo1.dexmeguviz.TextOperation
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 3A Batch 0: 1.2.0 图结果完整性门禁与双通道失败合成逻辑测试。 */
class Phase3GraphLaneTest {
    private val snapshot = "snap-1"

    fun `entity graph and methodType validation allows partial source scope and fails closed on errors`() {
        assertNull(
            DexGraphResultPolicy.failureForEntity(
                expectedSnapshotId = snapshot,
                resultSnapshotId = snapshot,
                executionStatus = ExecutionStatus.COMPLETE.ordinal,
                reason = DecisionReason.SOURCE_SCOPE_INCOMPLETE.ordinal,
                unknownRows = 0L,
                visitedRows = 100,
                totalRows = 100,
                candidateCount = 3
            )
        )
        assertEquals(
            Reason.QUERY_FAILED,
            DexGraphResultPolicy.failureForEntity(
                snapshot, "other", ExecutionStatus.COMPLETE.ordinal, DecisionReason.NONE.ordinal, 0L, 10, 10, 1
            )
        )
        assertEquals(
            Reason.TOO_MANY_MATCHES,
            DexGraphResultPolicy.failureForEntity(
                snapshot, snapshot, ExecutionStatus.COMPLETE.ordinal, DecisionReason.RESULT_LIMIT.ordinal, 0L, 10, 10, 1
            )
        )
        assertEquals(
            Reason.TOO_MANY_MATCHES,
            DexGraphResultPolicy.failureForEntity(
                snapshot, snapshot, ExecutionStatus.COMPLETE.ordinal, DecisionReason.NONE.ordinal, 0L, 10, 10,
                DexGraphResultPolicy.MAX_MATCHES + 1
            )
        )
        assertEquals(
            Reason.QUERY_FAILED,
            DexGraphResultPolicy.failureForEntity(
                snapshot, snapshot, ExecutionStatus.BUDGET_EXCEEDED.ordinal, DecisionReason.NONE.ordinal, 0L, 10, 10, 1
            )
        )
        assertEquals(
            Reason.QUERY_FAILED,
            DexGraphResultPolicy.failureForEntity(
                snapshot, snapshot, ExecutionStatus.COMPLETE.ordinal, DecisionReason.NONE.ordinal, 1L, 10, 10, 1
            )
        )
        assertEquals(
            Reason.QUERY_FAILED,
            DexGraphResultPolicy.failureForEntity(
                snapshot, snapshot, ExecutionStatus.COMPLETE.ordinal, DecisionReason.NONE.ordinal, 0L, 9, 10, 1
            )
        )

        assertNull(
            DexGraphResultPolicy.failureForGraph(
                snapshot, snapshot, ExecutionStatus.COMPLETE.ordinal, DecisionReason.SOURCE_SCOPE_INCOMPLETE.ordinal, 0L, 4
            )
        )
        assertEquals(
            Reason.QUERY_FAILED,
            DexGraphResultPolicy.failureForGraph(
                snapshot, snapshot, ExecutionStatus.CORRUPT_INPUT.ordinal, DecisionReason.NONE.ordinal, 0L, 0
            )
        )
        assertEquals(
            Reason.TOO_MANY_MATCHES,
            DexGraphResultPolicy.failureForGraph(
                snapshot, snapshot, ExecutionStatus.COMPLETE.ordinal, DecisionReason.NONE.ordinal, 0L,
                DexGraphResultPolicy.MAX_MATCHES + 1
            )
        )

        assertNull(
            DexGraphResultPolicy.failureForMethodType(
                snapshot, snapshot, ExecutionStatus.COMPLETE.ordinal, DecisionReason.NONE.ordinal, 0L, 50, 50, 1
            )
        )
        assertEquals(
            Reason.QUERY_FAILED,
            DexGraphResultPolicy.failureForMethodType(
                snapshot, snapshot, ExecutionStatus.COMPLETE.ordinal, DecisionReason.NONE.ordinal, 0L, 49, 50, 1
            )
        )
    }

    fun `outcome resolution preserves verified flat candidates and stays fail closed when empty`() {
        assertEquals(
            Reason.TOO_MANY_MATCHES,
            DexGraphResultPolicy.resolveOutcomeFailure(Reason.TOO_MANY_MATCHES, null, hasCandidates = true, bindFailures = 0)
        )
        assertEquals(
            Reason.TOO_MANY_MATCHES,
            DexGraphResultPolicy.resolveOutcomeFailure(null, Reason.TOO_MANY_MATCHES, hasCandidates = true, bindFailures = 0)
        )
        assertEquals(
            Reason.TOO_MANY_MATCHES to null,
            DexMeguvizAssistEngine.applyGraphRejection(
                currentFailure = null,
                currentGraphFailure = null,
                rejectedReason = Reason.TOO_MANY_MATCHES
            )
        )
        assertEquals(
            null to Reason.NO_MATCH,
            DexMeguvizAssistEngine.applyGraphRejection(
                currentFailure = null,
                currentGraphFailure = null,
                rejectedReason = Reason.NO_MATCH
            )
        )
        assertNull(
            DexGraphResultPolicy.resolveOutcomeFailure(null, Reason.QUERY_FAILED, hasCandidates = true, bindFailures = 0)
        )
        assertEquals(
            Reason.QUERY_FAILED,
            DexGraphResultPolicy.resolveOutcomeFailure(null, Reason.QUERY_FAILED, hasCandidates = false, bindFailures = 0)
        )
        assertEquals(
            Reason.TOO_MANY_MATCHES,
            DexGraphResultPolicy.resolveOutcomeFailure(null, Reason.TOO_MANY_MATCHES, hasCandidates = false, bindFailures = 0)
        )
        assertEquals(
            Reason.QUERY_FAILED,
            DexGraphResultPolicy.resolveOutcomeFailure(null, null, hasCandidates = false, bindFailures = 1)
        )
        assertEquals(
            Reason.NO_MATCH,
            DexGraphResultPolicy.resolveOutcomeFailure(null, null, hasCandidates = false, bindFailures = 0)
        )
        assertNull(
            DexGraphResultPolicy.resolveOutcomeFailure(null, null, hasCandidates = true, bindFailures = 1)
        )
    }

    fun `scopedAdvancedGraphQuery attaches snapshot slots and inherited_fields without altering limits`() {
        val sampleQuery = GraphQuery(
            relation = GraphExpression.groupByOwner(
                inputs = listOf(
                    GraphExpression.label(
                        GraphExpression.scan(
                            EntityRelation.FIELD_DEFS,
                            GraphPredicate.from(
                                EntityPredicate.text(EntityTextField.TYPE, TextOperation.EXACT, "Landroid/content/Context;")
                            )
                        ),
                        "ctx"
                    )
                ),
                requiredRoles = listOf("ctx")
            ),
            maxResults = 33,
            maxWork = DexMeguvizGraphPlans.GRAPH_MAX_WORK
        )
        val scoped = DexMeguvizGraphPlans.scopedAdvancedGraphQuery(sampleQuery, listOf("base", "split.feature"))
        val jsonText = scoped.toQueryIrJson()
        assertFalse("scoped QueryIR must stay single-line compact", jsonText.contains('\n'))
        val ir = JSONObject(jsonText)
        val slots = ir.getJSONObject("scope").getJSONArray("slots")
        assertEquals(listOf("base", "split.feature"), (0 until slots.length()).map(slots::getString))
        val caps = ir.getJSONArray("required_capabilities")
        assertTrue((0 until caps.length()).map(caps::getString).contains("inherited_fields"))
        assertEquals(33, ir.getInt("max_results"))
        assertEquals(DexMeguvizGraphPlans.GRAPH_MAX_WORK, ir.getLong("max_work"))

        var emptyRejected = false
        try {
            DexMeguvizGraphPlans.scopedAdvancedGraphQuery(sampleQuery, emptyList())
        } catch (_: IllegalArgumentException) {
            emptyRejected = true
        }
        assertTrue("empty slot list must be rejected", emptyRejected)

        var blankRejected = false
        try {
            DexMeguvizGraphPlans.scopedAdvancedGraphQuery(sampleQuery, listOf("base", " "))
        } catch (_: IllegalArgumentException) {
            blankRejected = true
        }
        assertTrue("blank slot must be rejected", blankRejected)

        var duplicateRejected = false
        try {
            DexMeguvizGraphPlans.scopedAdvancedGraphQuery(sampleQuery, listOf("base", "base"))
        } catch (_: IllegalArgumentException) {
            duplicateRejected = true
        }
        assertTrue("duplicate slot must be rejected", duplicateRejected)
    }

    @Test
    fun verifyAllCases() {
        fun runIsolated(block: (Phase3GraphLaneTest) -> Unit) {
            block(Phase3GraphLaneTest())
        }
        runIsolated { it.`entity graph and methodType validation allows partial source scope and fails closed on errors`() }
        runIsolated { it.`outcome resolution preserves verified flat candidates and stays fail closed when empty`() }
        runIsolated { it.`scopedAdvancedGraphQuery attaches snapshot slots and inherited_fields without altering limits`() }
    }
}
