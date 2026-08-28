package com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

class ReplyTopologyGraphBuilderTest {

    private val key = ReplyTopologyThreadKey(oid = 1L, type = 1L, rootRpid = 100L)

    @Test
    fun interruptedBuildStopsBeforeProducingAGraph() {
        Thread.currentThread().interrupt()
        try {
            assertThrows(CancellationException::class.java) {
                ReplyTopologyGraphBuilder.build(
                    key,
                    listOf(node(100L, root = 0L, parent = 0L))
                )
            }
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun buildsBranchesWithStableChronologicalSiblingOrder() {
        val graph = ReplyTopologyGraphBuilder.build(
            key,
            listOf(
                node(201L, parent = 100L, ctime = 20L),
                node(203L, parent = 202L, ctime = 15L),
                node(100L, root = 0L, parent = 0L, ctime = 1L),
                node(202L, parent = 100L, ctime = 10L)
            )
        )

        assertArrayEquals(longArrayOf(100L, 202L, 203L, 201L), graph.rpids)
        assertArrayEquals(intArrayOf(-1, 0, 1, 0), graph.parentIndexes)
        assertArrayEquals(intArrayOf(0, 1, 2, 1), graph.depths)
        assertArrayEquals(intArrayOf(2, 1, 0, 0), graph.childCounts)
        assertTrue(ReplyTopologyNodeFlags.has(graph.flags[0], ReplyTopologyNodeFlags.ROOT))
    }

    @Test
    fun mergesDuplicateRpidAndKeepsRicherSnapshot() {
        val graph = ReplyTopologyGraphBuilder.build(
            key,
            listOf(
                node(100L, root = 0L, parent = 0L),
                node(201L, parent = 100L, message = ""),
                node(
                    rpid = 201L,
                    parent = 100L,
                    ctime = 20L,
                    authorMid = 9L,
                    author = "作者",
                    message = "更完整的正文"
                )
            )
        )

        assertEquals(2, graph.size)
        assertEquals("更完整的正文", graph.messagePreviews[1])
        assertEquals("作者", graph.authorNames[1])
        assertTrue(ReplyTopologyNodeFlags.has(graph.flags[1], ReplyTopologyNodeFlags.DUPLICATE))
        assertEquals(1, graph.diagnostics.duplicateNodes)
        assertEquals(0, graph.diagnostics.conflictingDuplicateNodes)
    }

    @Test
    fun conflictingDuplicateIsDeterministicallyFlagged() {
        val duplicates = listOf(
            node(201L, parent = 100L, authorMid = 1L, author = "甲", message = "正文"),
            node(201L, parent = 999L, authorMid = 2L, author = "乙", message = "正文")
        )
        val graph = ReplyTopologyGraphBuilder.build(
            key,
            listOf(node(100L, root = 0L, parent = 0L)) + duplicates
        )
        val reversed = ReplyTopologyGraphBuilder.build(
            key,
            listOf(node(100L, root = 0L, parent = 0L)) + duplicates.reversed()
        )

        val index = graph.rpids.indexOf(201L)
        assertTrue(ReplyTopologyNodeFlags.has(graph.flags[index], ReplyTopologyNodeFlags.DUPLICATE_CONFLICT))
        assertEquals(1, graph.diagnostics.conflictingDuplicateNodes)
        assertArrayEquals(graph.rpids, reversed.rpids)
        assertArrayEquals(graph.parentIndexes, reversed.parentIndexes)
        assertEquals(graph.authorNames.toList(), reversed.authorNames.toList())
    }

    @Test
    fun insertsOnePlaceholderForSharedMissingParent() {
        val graph = ReplyTopologyGraphBuilder.build(
            key,
            listOf(
                node(100L, root = 0L, parent = 0L),
                node(201L, parent = 999L, ctime = 11L),
                node(202L, parent = 999L, ctime = 12L)
            )
        )

        assertArrayEquals(longArrayOf(100L, 999L, 201L, 202L), graph.rpids)
        assertArrayEquals(intArrayOf(-1, 0, 1, 1), graph.parentIndexes)
        assertTrue(ReplyTopologyNodeFlags.has(graph.flags[1], ReplyTopologyNodeFlags.PLACEHOLDER))
        assertTrue(ReplyTopologyNodeFlags.has(graph.flags[1], ReplyTopologyNodeFlags.MISSING_PARENT))
        assertEquals(11L, graph.ctimes[1])
        assertEquals(1, graph.diagnostics.missingParentNodes)
    }

    @Test
    fun missingRootBecomesSyntheticRootWithoutDroppingChildren() {
        val graph = ReplyTopologyGraphBuilder.build(key, listOf(node(201L, parent = 100L)))

        assertArrayEquals(longArrayOf(100L, 201L), graph.rpids)
        assertTrue(ReplyTopologyNodeFlags.has(graph.flags[0], ReplyTopologyNodeFlags.MISSING_ROOT))
        assertEquals(0, graph.parentIndexes[1])
    }

    @Test
    fun selfParentFallsBackToRoot() {
        val graph = ReplyTopologyGraphBuilder.build(
            key,
            listOf(
                node(100L, root = 0L, parent = 0L),
                node(201L, parent = 201L)
            )
        )

        assertEquals(0, graph.parentIndexes[1])
        assertTrue(ReplyTopologyNodeFlags.has(graph.flags[1], ReplyTopologyNodeFlags.SELF_PARENT))
        assertEquals(1, graph.diagnostics.selfParentNodes)
    }

    @Test
    fun cycleIsBrokenDeterministicallyAndAllCycleNodesAreMarked() {
        val graph = ReplyTopologyGraphBuilder.build(
            key,
            listOf(
                node(100L, root = 0L, parent = 0L),
                node(201L, parent = 202L, ctime = 20L),
                node(202L, parent = 201L, ctime = 10L)
            )
        )

        assertArrayEquals(longArrayOf(100L, 202L, 201L), graph.rpids)
        assertArrayEquals(intArrayOf(-1, 0, 1), graph.parentIndexes)
        assertTrue(ReplyTopologyNodeFlags.has(graph.flags[1], ReplyTopologyNodeFlags.CYCLE))
        assertTrue(ReplyTopologyNodeFlags.has(graph.flags[2], ReplyTopologyNodeFlags.CYCLE))
        assertEquals(2, graph.diagnostics.cycleNodes)
        assertEquals(1, graph.diagnostics.brokenCycleEdges)
    }

    @Test
    fun invalidAndCrossThreadNodesAreDropped() {
        val graph = ReplyTopologyGraphBuilder.build(
            key,
            listOf(
                node(100L, root = 0L, parent = 0L),
                node(0L, parent = 100L),
                node(201L, root = 777L, parent = 100L)
            )
        )

        assertArrayEquals(longArrayOf(100L), graph.rpids)
        assertEquals(1, graph.diagnostics.droppedInvalidNodes)
        assertEquals(1, graph.diagnostics.droppedWrongRootNodes)
    }

    @Test
    fun veryDeepChainUsesIterativeTraversal() {
        val depth = 10_000
        val nodes = ArrayList<ReplyTopologyNodeSnapshot>(depth + 1)
        nodes += node(100L, root = 0L, parent = 0L)
        repeat(depth) { offset ->
            val rpid = 101L + offset
            val parent = if (offset == 0) 100L else rpid - 1L
            nodes += node(rpid, parent = parent, ctime = offset.toLong())
        }

        val graph = ReplyTopologyGraphBuilder.build(key, nodes)

        assertEquals(depth + 1, graph.size)
        assertEquals(depth, graph.depths.last())
        assertEquals(100L + depth, graph.rpids.last())
    }

    @Test
    fun inputOrderDoesNotChangeRenderOrder() {
        val forward = listOf(
            node(100L, root = 0L, parent = 0L),
            node(201L, parent = 100L, ctime = 20L),
            node(202L, parent = 100L, ctime = 10L),
            node(203L, parent = 202L, ctime = 30L)
        )
        val first = ReplyTopologyGraphBuilder.build(key, forward)
        val second = ReplyTopologyGraphBuilder.build(key, forward.reversed())

        assertArrayEquals(first.rpids, second.rpids)
        assertArrayEquals(first.parentIndexes, second.parentIndexes)
        assertArrayEquals(first.depths, second.depths)
    }

    private fun node(
        rpid: Long,
        root: Long = key.rootRpid,
        parent: Long,
        ctime: Long = 0L,
        authorMid: Long = 0L,
        author: String = "",
        message: String = ""
    ) = ReplyTopologyNodeSnapshot.fromRaw(
        rpid = rpid,
        rootRpid = root,
        parentRpid = parent,
        ctime = ctime,
        authorMid = authorMid,
        authorName = author,
        message = message
    )
}
