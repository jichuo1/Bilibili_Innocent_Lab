package com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyTopologySessionGateTest {

    private val firstKey = ReplyTopologyThreadKey(oid = 1L, type = 1L, rootRpid = 100L)
    private val secondKey = ReplyTopologyThreadKey(oid = 2L, type = 1L, rootRpid = 200L)

    fun allowsOnlyOneInFlightRequestAndReleasesItOnCompletion() {
        val gate = ReplyTopologySessionGate()
        val session = gate.open(firstKey, pageEpoch = 7L)
        val first = gate.beginRequest(session)

        assertNotNull(first)
        assertNull(gate.beginRequest(session))
        assertTrue(gate.accepts(first!!))
        assertTrue(gate.completeRequest(first))
        assertFalse(gate.accepts(first))
        assertNotNull(gate.beginRequest(session))
    }

    fun openingNewSessionInvalidatesLateResultFromOldRoot() {
        val gate = ReplyTopologySessionGate()
        val oldSession = gate.open(firstKey, pageEpoch = 1L)
        val oldRequest = checkNotNull(gate.beginRequest(oldSession))

        val newSession = gate.open(secondKey, pageEpoch = 2L)

        assertNotEquals(oldSession.sessionId, newSession.sessionId)
        assertFalse(gate.accepts(oldSession))
        assertFalse(gate.accepts(oldRequest))
        assertFalse(gate.completeRequest(oldRequest))
        assertNotNull(gate.beginRequest(newSession))
    }

    fun staleCloseCannotCloseNewSession() {
        val gate = ReplyTopologySessionGate()
        val oldSession = gate.open(firstKey, pageEpoch = 1L)
        val newSession = gate.open(secondKey, pageEpoch = 2L)

        assertFalse(gate.close(oldSession))
        assertTrue(gate.accepts(newSession))
        assertTrue(gate.close(newSession))
        assertFalse(gate.accepts(newSession))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (ReplyTopologySessionGateTest) -> Unit) {
            val s = ReplyTopologySessionGateTest()
            block(s)
        }
        runIsolated { s -> s.allowsOnlyOneInFlightRequestAndReleasesItOnCompletion() }
        runIsolated { s -> s.openingNewSessionInvalidatesLateResultFromOldRoot() }
        runIsolated { s -> s.staleCloseCannotCloseNewSession() }
    
        ReplyTopologyTextCases().verifyAllCases()
        ReplyTopologyTreeLayoutCases().verifyAllCases()
        ReplyTopologyTreeSnapshotCases().verifyAllCases()
    }
}

private class ReplyTopologyTextCases {

    fun collapsesWhitespaceAndRemovesHostLayoutSentinels() {
        assertEquals(
            "第一行 第二行[dog]",
            ReplyTopologyText.summarize("  第一行\n\t第二行\u200B[dog]  ", 120)
        )
    }

    fun neverSplitsUtf16SurrogatePair() {
        assertEquals("A😀…", ReplyTopologyText.summarize("A😀BC", 2))
    }

    fun keepsZwjEmojiSequenceTogether() {
        assertEquals("A👩‍💻…", ReplyTopologyText.summarize("A👩‍💻BC", 2))
    }

    fun keepsCombiningMarkWithItsBaseCharacter() {
        assertEquals("Ae\u0301…", ReplyTopologyText.summarize("Ae\u0301BC", 2))
    }

    fun keepsBilibiliBracketEmojiTokenWhole() {
        assertEquals("A[dog]…", ReplyTopologyText.summarize("A[dog]BC", 3))
    }

    fun rawSnapshotBoundsMessageAndAuthorImmediately() {
        val snapshot = ReplyTopologyNodeSnapshot.fromRaw(
            rpid = 1L,
            rootRpid = 2L,
            parentRpid = 2L,
            authorName = "作者名字",
            repliedAuthorName = "回复对象",
            message = "一二三四五",
            previewCodePoints = 3
        )

        assertEquals("一二三…", snapshot.messagePreview)
        assertEquals("作者名字", snapshot.authorName)
        assertEquals("回复对象", snapshot.repliedAuthorName)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (ReplyTopologyTextCases) -> Unit) {
            val s = ReplyTopologyTextCases()
            block(s)
        }
        runIsolated { s -> s.collapsesWhitespaceAndRemovesHostLayoutSentinels() }
        runIsolated { s -> s.neverSplitsUtf16SurrogatePair() }
        runIsolated { s -> s.keepsZwjEmojiSequenceTogether() }
        runIsolated { s -> s.keepsCombiningMarkWithItsBaseCharacter() }
        runIsolated { s -> s.keepsBilibiliBracketEmojiTokenWhole() }
        runIsolated { s -> s.rawSnapshotBoundsMessageAndAuthorImmediately() }
    }
}

private class ReplyTopologyTreeLayoutCases {
    private val key = ReplyTopologyThreadKey(1L, 1L, 100L)

    fun fullTreeRetainsEveryTrueDepthAndParentLink() {
        val graph = chain(1_000)
        val original = graph.depths.clone()
        val layout = ReplyTopologyTreeLayout(graph)
        assertEquals(1_001, layout.size)
        assertEquals(1_000, layout.maxDepth)
        assertEquals(999, layout.parentRow(1_000))
        assertEquals(1_000, layout.depthAt(1_000))
        assertArrayEquals(original, graph.depths)
    }

    fun localPathRetainsSixAncestorsAndDirectChildrenButNotGrandchildren() {
        val graph = ReplyTopologyGraphBuilder.build(key, chainNodes(20) +
            listOf(node(121L, 120L), node(122L, 120L), node(123L, 121L)))
        val path = requireNotNull(ReplyTopologyPath.resolve(graph, 120L))
        assertEquals(14, path.omittedAncestors)
        assertEquals(14, path.baseDepth)
        val ids = path.indexes.map { graph.rpids[it] }
        assertTrue(ids.containsAll((114L..122L).toList()))
        assertFalse(ids.contains(123L))
        assertFalse(ids.contains(113L))
        val layout = ReplyTopologyTreeLayout(graph, path.indexes, path.baseDepth)
        assertEquals(-1, layout.parentRow(0))
        assertEquals(6, layout.depthAt(layout.rowOf(120L)))
        assertEquals(7, layout.depthAt(layout.rowOf(121L)))
    }

    fun shallowPathHasNoOmittedAncestorsAndMissingNodeReturnsNull() {
        val graph = chain(3)
        val path = requireNotNull(ReplyTopologyPath.resolve(graph, 103L))
        assertEquals(0, path.omittedAncestors)
        assertArrayEquals(intArrayOf(0, 1, 2, 3), path.indexes)
        assertNull(ReplyTopologyPath.resolve(graph, 999L))
    }

    fun branchesAndPlaceholderParentsUseGraphIdentityRatherThanAuthorText() {
        val graph = ReplyTopologyGraphBuilder.build(key, listOf(node(100L, 0L), node(201L, 999L), node(202L, 999L)))
        val path = requireNotNull(ReplyTopologyPath.resolve(graph, 999L))
        assertTrue(path.indexes.map { graph.rpids[it] }.containsAll(listOf(100L, 999L, 201L, 202L)))
        val layout = ReplyTopologyTreeLayout(graph)
        val parent = layout.rowOf(999L)
        assertEquals(parent, layout.parentRow(layout.rowOf(201L)))
        assertEquals(layout.rowOf(202L), layout.lastChildRows[parent])
    }

    fun viewportCullingIncludesPartialCardsButDoesNotScanAnEmptyRegion() {
        val layout = ReplyTopologyTreeLayout(chain(20))
        assertEquals(4..6, layout.visibleRows(500.0, 650.0, 100.0, 80.0))
        assertEquals(0..0, layout.visibleRows(-40.0, 20.0, 100.0, 80.0))
        assertTrue(layout.visibleRows(10_000.0, 20_000.0, 100.0, 80.0).isEmpty())
        assertTrue(layout.visibleRows(-200.0, -100.0, 100.0, 80.0).isEmpty())
    }

    fun subsetIndexesCannotDuplicateOrReorderStableNodes() {
        val graph = chain(3)
        assertTrue(runCatching { ReplyTopologyTreeLayout(graph, intArrayOf(2, 1)) }.isFailure)
        assertTrue(runCatching { ReplyTopologyTreeLayout(graph, intArrayOf(1, 1)) }.isFailure)
        assertTrue(runCatching { ReplyTopologyTreeLayout(graph, intArrayOf(50)) }.isFailure)
    }

    private fun chain(count: Int) = ReplyTopologyGraphBuilder.build(key, chainNodes(count))
    fun indexedBranchesMatchBruteForceIncludingParentsAboveTheViewport() {
        val random = java.util.Random(812L)
        val nodes = (0..500).map { id -> node(100L + id, if (id == 0) 0L else 100L + random.nextInt(id)) }
        val layout = ReplyTopologyTreeLayout(ReplyTopologyGraphBuilder.build(key, nodes))
        for (first in 0 until layout.size step 7) {
            val last = minOf(first + 9, layout.size - 1)
            val expected = (0..last).filter { layout.lastChildRows[it] >= first }.toIntArray()
            val count = layout.collectVisibleBranches(first, last)
            assertArrayEquals(expected, layout.visibleBranchRows.copyOf(count))
        }
        assertEquals(0, layout.collectVisibleBranches(-1, -1))
        assertEquals(0, layout.collectVisibleBranches(layout.size, layout.size + 1))
    }

    fun longRootTrunkIsRetainedWithoutIncludingUnrelatedCompletedBranches() {
        val graph = ReplyTopologyGraphBuilder.build(key, listOf(node(100L, 0L)) +
            (1..10_000).map { node(100L + it, 100L) })
        val layout = ReplyTopologyTreeLayout(graph)
        assertEquals(1, layout.collectVisibleBranches(9_000, 9_010))
        assertEquals(0, layout.visibleBranchRows[0])
        assertEquals(9_990, layout.rowOf(10_090L))
    }

    fun reusedPathIndexPreservesDirectChildrenAndDoesNotIncludeOtherBranches() {
        val graph = ReplyTopologyGraphBuilder.build(key, chainNodes(20) +
            (1..2_000).map { node(1_000L + it, 100L) } + listOf(node(900L, 120L), node(901L, 900L)))
        val index = ReplyTopologyPath.Index(graph)
        repeat(10) {
            val path = requireNotNull(index.resolve(120L))
            assertEquals(8, path.indexes.size)
            assertTrue(path.indexes.map { graph.rpids[it] }.contains(900L))
            assertFalse(path.indexes.map { graph.rpids[it] }.contains(901L))
            assertEquals(14, path.omittedAncestors)
        }
        assertNull(index.resolve(Long.MAX_VALUE))
        assertEquals(1, requireNotNull(index.resolve(120L, 0)).indexes.count { graph.rpids[it] == 120L })
        assertTrue(runCatching { index.resolve(120L, -1) }.isFailure)
    }

    fun focusRetainsAllAncestorsAndEveryDescendantWithoutIncludingSiblingBranches() {
        val graph = ReplyTopologyGraphBuilder.build(key, chainNodes(20) + listOf(
            node(201L, 118L), node(202L, 201L), node(121L, 120L), node(122L, 121L), node(123L, 122L)
        ))
        val index = ReplyTopologyPath.Index(graph)
        val focus = requireNotNull(index.focus(120L))
        assertEquals(graph.size, focus.size)
        assertEquals((100L..123L).toList(),
            graph.rpids.indices.filter { focus[it] }.map { graph.rpids[it] })
        assertFalse(focus[index.indexOf(201L)])
        assertFalse(focus[index.indexOf(202L)])
        val layout = ReplyTopologyTreeLayout(graph)
        assertEquals(graph.size, layout.size)
        assertEquals(23, layout.depthAt(layout.rowOf(123L)))
        assertEquals(layout.rowOf(122L), layout.parentRow(layout.rowOf(123L)))
    }

    fun changingFocusUsesRpidAndLeavesThePreviousMaskAndWholeGraphIntact() {
        val graph = ReplyTopologyGraphBuilder.build(key, listOf(node(100L, 0L),
            node(101L, 100L), node(102L, 100L), node(103L, 101L), node(104L, 102L)))
        val originalParents = graph.parentIndexes.clone()
        val index = ReplyTopologyPath.Index(graph)
        val first = requireNotNull(index.focus(101L))
        val previous = first.clone()
        val second = requireNotNull(index.focus(102L))
        assertTrue(first[index.indexOf(100L)])
        assertTrue(second[index.indexOf(100L)])
        assertTrue(first[index.indexOf(103L)])
        assertFalse(second[index.indexOf(103L)])
        assertFalse(first[index.indexOf(104L)])
        assertTrue(second[index.indexOf(104L)])
        assertArrayEquals(previous, first)
        assertArrayEquals(originalParents, graph.parentIndexes)
        assertTrue(requireNotNull(index.focus(100L)).all { it })
        assertNull(index.focus(Long.MAX_VALUE))
    }

    fun focusIncludesPlaceholderAncestorsAndTheirEntireDescendantBranch() {
        val graph = ReplyTopologyGraphBuilder.build(key, listOf(node(100L, 0L),
            node(201L, 999L), node(202L, 201L), node(203L, 202L), node(300L, 100L)))
        val index = ReplyTopologyPath.Index(graph)
        val focus = requireNotNull(index.focus(999L))
        assertTrue(focus[index.indexOf(100L)])
        assertTrue(focus[index.indexOf(999L)])
        assertTrue(focus[index.indexOf(203L)])
        assertFalse(focus[index.indexOf(300L)])
        assertNull(ReplyTopologyPath.Index(ReplyTopologyGraphBuilder.build(key, emptyList())).focus(9L))
    }

    fun focusWalksDeepAncestorsAndDescendantsWithoutRecursion() {
        val graph = chain(20_000)
        val focus = requireNotNull(ReplyTopologyPath.Index(graph).focus(10_100L))
        assertTrue(focus.all { it })
        assertEquals(20_001, focus.size)
    }

    private fun chainNodes(count: Int) = (0..count).map { node(100L + it, if (it == 0) 0L else 99L + it) }
    private fun node(id: Long, parent: Long) = ReplyTopologyNodeSnapshot.fromRaw(id,
        if (id == 100L) 0L else 100L, parent, authorName = "same author", message = "reply")

    fun verifyAllCases() {
        fun runIsolated(block: (ReplyTopologyTreeLayoutCases) -> Unit) {
            val s = ReplyTopologyTreeLayoutCases()
            block(s)
        }
        runIsolated { s -> s.fullTreeRetainsEveryTrueDepthAndParentLink() }
        runIsolated { s -> s.localPathRetainsSixAncestorsAndDirectChildrenButNotGrandchildren() }
        runIsolated { s -> s.shallowPathHasNoOmittedAncestorsAndMissingNodeReturnsNull() }
        runIsolated { s -> s.branchesAndPlaceholderParentsUseGraphIdentityRatherThanAuthorText() }
        runIsolated { s -> s.viewportCullingIncludesPartialCardsButDoesNotScanAnEmptyRegion() }
        runIsolated { s -> s.subsetIndexesCannotDuplicateOrReorderStableNodes() }
        runIsolated { s -> s.indexedBranchesMatchBruteForceIncludingParentsAboveTheViewport() }
        runIsolated { s -> s.longRootTrunkIsRetainedWithoutIncludingUnrelatedCompletedBranches() }
        runIsolated { s -> s.reusedPathIndexPreservesDirectChildrenAndDoesNotIncludeOtherBranches() }
        runIsolated { s -> s.focusRetainsAllAncestorsAndEveryDescendantWithoutIncludingSiblingBranches() }
        runIsolated { s -> s.changingFocusUsesRpidAndLeavesThePreviousMaskAndWholeGraphIntact() }
        runIsolated { s -> s.focusIncludesPlaceholderAncestorsAndTheirEntireDescendantBranch() }
        runIsolated { s -> s.focusWalksDeepAncestorsAndDescendantsWithoutRecursion() }
    }
}

private class ReplyTopologyTreeSnapshotCases {
    private class Node(val id: Long, val children: List<Any?> = emptyList())
    private fun read(root: Node, limit: Int) = snapshotReplyTopologyTree(root, limit,
        children = { (it as? Node)?.children },
        snapshot = { (it as? Node)?.let { n -> ReplyTopologyNodeSnapshot.fromRaw(n.id, 1L, 1L) } })

    fun widePagesKeepEverySiblingWithoutSchedulingOnlyTheFirstNinetySix() {
        val root = Node(1L, (2L..202L).map { Node(it) })
        val result = read(root, 4_096)
        assertEquals(202, result.nodes.size)
        assertFalse(result.truncated)
        assertEquals(202L, result.nodes.last().rpid)
    }

    fun exactlyFullBudgetIsCompleteOnlyWhenThereIsNoUnvisitedReply() {
        assertFalse(read(Node(1L, listOf(Node(2L))), 2).truncated)
        val result = read(Node(1L, listOf(Node(2L), Node(3L))), 2)
        assertTrue(result.truncated)
        assertEquals(2, result.nodes.size)
    }

    fun invalidChildrenStillConsumeTheVisitBudget() {
        val result = read(Node(1L, listOf(null, "invalid", Node(2L))), 3)
        assertTrue(result.truncated)
        assertEquals(1, result.nodes.size)
    }

    fun deepTreesDoNotUseTheJvmCallStack() {
        var root = Node(5_000L)
        for (id in 4_999L downTo 1L) root = Node(id, listOf(root))
        val result = read(root, 6_000)
        assertFalse(result.truncated)
        assertEquals(5_000, result.nodes.size)
    }

    fun closingTheSessionInterruptsSnapshotWork() {
        Thread.currentThread().interrupt()
        try { assertThrows(CancellationException::class.java) { read(Node(1L), 4) } }
        finally { Thread.interrupted() }
    }

    fun skippedInvalidNodesCannotProduceACompleteSnapshot() {
        val result = read(Node(1L, listOf(null, "invalid", Node(2L))), 10)
        assertEquals(listOf(1L, 2L), result.nodes.map { it.rpid })
        assertTrue(result.truncated)
    }

    fun failingChildReadKeepsTheRootAndOtherReadableSiblings() {
        val root = Node(1L, listOf(Node(2L), Node(3L)))
        val result = snapshotReplyTopologyTree(root, 10,
            children = { if ((it as Node).id == 2L) error("getter failed") else it.children },
            snapshot = { ReplyTopologyNodeSnapshot.fromRaw((it as Node).id, 1L, 1L) })
        assertEquals(listOf(1L, 2L, 3L), result.nodes.map { it.rpid })
        assertTrue(result.truncated)
    }

    fun failingIteratorPreservesTheAlreadyReadNodesAsPartial() {
        val children = object : AbstractList<Any?>() {
            override val size = 2
            override fun get(index: Int): Any = if (index == 0) Node(2L) else error("iterator failed")
        }
        val result = read(Node(1L, children), 10)
        assertEquals(listOf(1L, 2L), result.nodes.map { it.rpid })
        assertTrue(result.truncated)
    }

    fun cancellationDuringAGetterStillStopsTraversal() {
        assertThrows(CancellationException::class.java) {
            snapshotReplyTopologyTree(Node(1L), 10,
                children = { throw CancellationException("closed") },
                snapshot = { ReplyTopologyNodeSnapshot.fromRaw(1L, 1L, 1L) })
        }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (ReplyTopologyTreeSnapshotCases) -> Unit) {
            val s = ReplyTopologyTreeSnapshotCases()
            block(s)
        }
        runIsolated { s -> s.widePagesKeepEverySiblingWithoutSchedulingOnlyTheFirstNinetySix() }
        runIsolated { s -> s.exactlyFullBudgetIsCompleteOnlyWhenThereIsNoUnvisitedReply() }
        runIsolated { s -> s.invalidChildrenStillConsumeTheVisitBudget() }
        runIsolated { s -> s.deepTreesDoNotUseTheJvmCallStack() }
        runIsolated { s -> s.closingTheSessionInterruptsSnapshotWork() }
        runIsolated { s -> s.skippedInvalidNodesCannotProduceACompleteSnapshot() }
        runIsolated { s -> s.failingChildReadKeepsTheRootAndOtherReadableSiblings() }
        runIsolated { s -> s.failingIteratorPreservesTheAlreadyReadNodesAsPartial() }
        runIsolated { s -> s.cancellationDuringAGetterStillStopsTraversal() }
    }
}
