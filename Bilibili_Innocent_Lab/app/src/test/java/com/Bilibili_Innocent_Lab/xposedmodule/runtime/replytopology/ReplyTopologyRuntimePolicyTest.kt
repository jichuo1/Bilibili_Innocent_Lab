package com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology

import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyTopologyRuntimePolicyTest {

    @Test
    fun verifyAllCases() {
        ReplyTopologyClipboardWriteCases().verifyAllCases()
        ReplyTopologyExportTextCases().verifyAllCases()
        ReplyTopologyGraphBuilderCases().verifyAllCases()
        ReplyTopologyKeywordFilterCases().verifyAllCases()
        ReplyTopologyNodeStoreCases().verifyAllCases()
        ReplyTopologyPagingGuardCases().verifyAllCases()
        ReplyTopologySessionGateCases().verifyAllCases()
        ReplyTopologyTextCases().verifyAllCases()
        ReplyTopologyTreeLayoutCases().verifyAllCases()
        ReplyTopologyTreeSnapshotCases().verifyAllCases()
    }
}

private class ReplyTopologyClipboardWriteCases {
    fun onlyTheExactClipIsAllowedDuringItsSynchronousWrite() {
        val clip = Any()
        val other = Any()
        assertFalse(ReplyTopologyClipboardWrite.owns(clip))
        val result = ReplyTopologyClipboardWrite.write(clip) {
            assertTrue(ReplyTopologyClipboardWrite.owns(clip))
            assertFalse(ReplyTopologyClipboardWrite.owns(other))
            "written"
        }
        assertEquals("written", result)
        assertFalse(ReplyTopologyClipboardWrite.owns(clip))
    }

    fun failedWritesStillRemoveTheExemption() {
        val clip = Any()
        assertTrue(runCatching {
            ReplyTopologyClipboardWrite.write(clip) { error("clipboard failed") }
        }.isFailure)
        assertFalse(ReplyTopologyClipboardWrite.owns(clip))
    }

    fun nestedWritesRestoreThePreviousClipIdentity() {
        val outer = Any()
        val inner = Any()
        ReplyTopologyClipboardWrite.write(outer) {
            ReplyTopologyClipboardWrite.write(inner) {
                assertTrue(ReplyTopologyClipboardWrite.owns(inner))
                assertFalse(ReplyTopologyClipboardWrite.owns(outer))
            }
            assertTrue(ReplyTopologyClipboardWrite.owns(outer))
        }
        assertFalse(ReplyTopologyClipboardWrite.owns(outer))
        assertFalse(ReplyTopologyClipboardWrite.owns(inner))
    }

    fun anotherThreadCannotBorrowTheExemption() {
        val clip = Any()
        val allowed = AtomicBoolean(true)
        ReplyTopologyClipboardWrite.write(clip) {
            val thread = Thread { allowed.set(ReplyTopologyClipboardWrite.owns(clip)) }
            thread.start()
            thread.join(2_000L)
            assertFalse(thread.isAlive)
        }
        assertFalse(allowed.get())
    }

    fun verifyAllCases() {
        fun runIsolated(block: (ReplyTopologyClipboardWriteCases) -> Unit) {
            val s = ReplyTopologyClipboardWriteCases()
            block(s)
        }
        runIsolated { s -> s.onlyTheExactClipIsAllowedDuringItsSynchronousWrite() }
        runIsolated { s -> s.failedWritesStillRemoveTheExemption() }
        runIsolated { s -> s.nestedWritesRestoreThePreviousClipIdentity() }
        runIsolated { s -> s.anotherThreadCannotBorrowTheExemption() }
    }
}

private class ReplyTopologyExportTextCases {
    private val key = ReplyTopologyThreadKey(1L, 1L, 100L)
    private val labels = ReplyTopologyExportText.Labels("unknown", "empty", "filtered author",
        "unavailable author", "filtered reply", "unavailable reply")

    fun depthAndRepliedAuthorMatchTheVisibleTitles() {
        val graph = graph(node(100L, 0L, "root", "root text", "ignored"),
            node(201L, 100L, "child", "child text", "root"),
            node(202L, 201L, "leaf", "leaf text", "child"))
        val result = ready(graph)
        assertEquals("root: root text\n  child → root: child text\n    leaf → child: leaf text", result.text)
        assertEquals(3, result.count)
        assertFalse(result.text.endsWith('\n'))
    }

    fun onlyTheFilteredVisibleSetAndItsAncestorsAreExported() {
        val graph = graph(node(100L, 0L, "root", ""), node(201L, 100L, "other", "omit"),
            node(202L, 100L, "selected", "needle"))
        val indexes = ReplyTopologyKeywordFilter.resolve(graph, "needle")
        val result = ReplyTopologyExportText.render(graph, indexes, labels) as ReplyTopologyExportText.Result.Ready
        assertEquals("root: empty\n  selected: needle", result.text)
        assertEquals(2, result.count)
        assertFalse(result.text.contains("omit"))
    }

    fun blankAuthorsAndMessagesHaveLocalizedFallbacks() {
        assertEquals("unknown: empty", ready(graph(node(100L, 0L, "", ""))).text)
    }

    fun unavailableAndPurifiedPlaceholdersRemainRecognizable() {
        val graph = graph(node(100L, 0L, "root", "root"),
            node(201L, 100L, "", "", flags = ReplyTopologyNodeFlags.FILTERED),
            node(202L, 100L, "", "", flags = ReplyTopologyNodeFlags.UNAVAILABLE))
        assertEquals("root: root\n  filtered author: filtered reply\n  unavailable author: unavailable reply",
            ready(graph).text)
    }

    fun absentGraphAndEmptyDisplaySetReturnEmpty() {
        assertSame(ReplyTopologyExportText.Result.Empty, ReplyTopologyExportText.render(null, intArrayOf(), labels))
        assertSame(ReplyTopologyExportText.Result.Empty,
            ReplyTopologyExportText.render(graph(node(100L, 0L, "", "")), intArrayOf(), labels))
    }

    fun deepTreesDoNotExpandIndentationWithoutBound() {
        val nodes = (0..100).map { offset ->
            node(100L + offset, if (offset == 0) 0L else 99L + offset, "user", "text")
        }
        val text = ready(graph(*nodes.toTypedArray())).text
        val last = text.lineSequence().last()
        assertEquals(ReplyTopologyExportText.MAX_INDENT_DEPTH * 2, last.takeWhile { it == ' ' }.length)
    }

    fun oversizedExportsFailBeforeReturningATruncatedTree() {
        val nodes = (0..1_500).map { offset ->
            node(100L + offset, if (offset == 0) 0L else 100L, "a".repeat(48), "😀".repeat(120))
        }
        val graph = graph(*nodes.toTypedArray())
        assertSame(ReplyTopologyExportText.Result.TooLarge,
            ReplyTopologyExportText.render(graph, IntArray(graph.size) { it }, labels))
    }

    fun exportUsesTheBoundedPreviewAndDoesNotFetchOrRestoreFullText() {
        val graph = graph(node(100L, 0L, "root", "a".repeat(300)))
        val result = ready(graph)
        assertTrue(result.text.endsWith('…'))
        assertTrue(result.text.length < 160)
    }

    private fun ready(graph: ReplyTopologyGraph) = ReplyTopologyExportText.render(
        graph, IntArray(graph.size) { it }, labels
    ) as ReplyTopologyExportText.Result.Ready

    private fun graph(vararg nodes: ReplyTopologyNodeSnapshot) = ReplyTopologyGraphBuilder.build(key, nodes.asList())

    private fun node(id: Long, parent: Long, author: String, message: String, replied: String? = null,
        flags: Int = 0) = ReplyTopologyNodeSnapshot.fromRaw(
        rpid = id, rootRpid = if (id == 100L) 0L else 100L, parentRpid = parent,
        authorName = author, repliedAuthorName = replied, message = message, flags = flags
    )

    fun verifyAllCases() {
        fun runIsolated(block: (ReplyTopologyExportTextCases) -> Unit) {
            val s = ReplyTopologyExportTextCases()
            block(s)
        }
        runIsolated { s -> s.depthAndRepliedAuthorMatchTheVisibleTitles() }
        runIsolated { s -> s.onlyTheFilteredVisibleSetAndItsAncestorsAreExported() }
        runIsolated { s -> s.blankAuthorsAndMessagesHaveLocalizedFallbacks() }
        runIsolated { s -> s.unavailableAndPurifiedPlaceholdersRemainRecognizable() }
        runIsolated { s -> s.absentGraphAndEmptyDisplaySetReturnEmpty() }
        runIsolated { s -> s.deepTreesDoNotExpandIndentationWithoutBound() }
        runIsolated { s -> s.oversizedExportsFailBeforeReturningATruncatedTree() }
        runIsolated { s -> s.exportUsesTheBoundedPreviewAndDoesNotFetchOrRestoreFullText() }
    }
}

private class ReplyTopologyGraphBuilderCases {

    private val key = ReplyTopologyThreadKey(oid = 1L, type = 1L, rootRpid = 100L)

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

    fun missingRootBecomesSyntheticRootWithoutDroppingChildren() {
        val graph = ReplyTopologyGraphBuilder.build(key, listOf(node(201L, parent = 100L)))

        assertArrayEquals(longArrayOf(100L, 201L), graph.rpids)
        assertTrue(ReplyTopologyNodeFlags.has(graph.flags[0], ReplyTopologyNodeFlags.MISSING_ROOT))
        assertEquals(0, graph.parentIndexes[1])
    }

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

    fun verifyAllCases() {
        fun runIsolated(block: (ReplyTopologyGraphBuilderCases) -> Unit) {
            val s = ReplyTopologyGraphBuilderCases()
            block(s)
        }
        runIsolated { s -> s.interruptedBuildStopsBeforeProducingAGraph() }
        runIsolated { s -> s.buildsBranchesWithStableChronologicalSiblingOrder() }
        runIsolated { s -> s.mergesDuplicateRpidAndKeepsRicherSnapshot() }
        runIsolated { s -> s.conflictingDuplicateIsDeterministicallyFlagged() }
        runIsolated { s -> s.insertsOnePlaceholderForSharedMissingParent() }
        runIsolated { s -> s.missingRootBecomesSyntheticRootWithoutDroppingChildren() }
        runIsolated { s -> s.selfParentFallsBackToRoot() }
        runIsolated { s -> s.cycleIsBrokenDeterministicallyAndAllCycleNodesAreMarked() }
        runIsolated { s -> s.invalidAndCrossThreadNodesAreDropped() }
        runIsolated { s -> s.veryDeepChainUsesIterativeTraversal() }
        runIsolated { s -> s.inputOrderDoesNotChangeRenderOrder() }
    }
}

private class ReplyTopologyKeywordFilterCases {
    private val key = ReplyTopologyThreadKey(1L, 1L, 100L)

    fun emptyQueryPreservesEveryOriginalPosition() {
        val graph = graph(node(201L), node(202L))
        assertArrayEquals(intArrayOf(0, 1, 2), ReplyTopologyKeywordFilter.resolve(graph, "  "))
        assertEquals("", ReplyTopologyKeywordFilter.normalize(null))
    }

    fun authorMatchKeepsItsAncestorsAndDropsTheOtherBranch() {
        val graph = graph(node(201L, author = "Alice"), node(202L, author = "Bob"))
        assertArrayEquals(longArrayOf(100L, 201L), visible(graph, "alice"))
    }

    fun messageMatchKeepsTheEntireParentChainInGraphOrder() {
        val graph = graph(node(201L), node(202L, parent = 201L, message = "needle"), node(203L))
        assertArrayEquals(longArrayOf(100L, 201L, 202L), visible(graph, "needle"))
    }

    fun repliedAuthorCanMatchAndNullRepliedAuthorsAreSafe() {
        val graph = graph(node(201L), node(202L, replied = "Target"))
        assertArrayEquals(longArrayOf(100L, 202L), visible(graph, "target"))
    }

    fun keywordsCannotJoinFieldsEvenWhenTheyContainNul() {
        val graph = graph(node(201L, author = "alpha", replied = "beta", message = "gamma"))
        for (query in listOf("alphabeta", "betagamma", "ha\u0000be", "ta\u0000ga")) {
            assertArrayEquals(query, intArrayOf(), ReplyTopologyKeywordFilter.resolve(graph, query))
        }
        assertArrayEquals(longArrayOf(100L, 201L), visible(graph, "gamma"))
    }

    fun matchingSiblingsShareOnlyOneCopyOfTheirAncestor() {
        val graph = graph(node(201L), node(202L, parent = 201L, message = "hit"),
            node(203L, parent = 201L, message = "hit"), node(204L))
        assertArrayEquals(longArrayOf(100L, 201L, 202L, 203L), visible(graph, "hit"))
    }

    fun normalizationDoesNotDependOnTheSystemLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("i", ReplyTopologyKeywordFilter.normalize(" I "))
            assertArrayEquals(longArrayOf(100L, 201L), visible(graph(node(201L, author = "Iris")), " IRIS "))
        } finally { Locale.setDefault(previous) }
    }

    fun noMatchAndEmptyPlaceholderTextProduceNoRows() {
        val graph = graph(node(201L, flags = ReplyTopologyNodeFlags.FILTERED),
            node(202L, flags = ReplyTopologyNodeFlags.UNAVAILABLE))
        assertArrayEquals(intArrayOf(), ReplyTopologyKeywordFilter.resolve(graph, "missing"))
    }

    fun filteringDoesNotRewriteGraphDataOrPurificationFlags() {
        val graph = graph(node(201L, message = "match", flags = ReplyTopologyNodeFlags.FILTERED), node(202L))
        val ids = graph.rpids.clone()
        val parents = graph.parentIndexes.clone()
        val flags = graph.flags.clone()
        ReplyTopologyKeywordFilter.resolve(graph, "match")
        assertArrayEquals(ids, graph.rpids)
        assertArrayEquals(parents, graph.parentIndexes)
        assertArrayEquals(flags, graph.flags)
        assertEquals("match", graph.messagePreviews[1])
    }

    fun deepReplyChainsRemainIterativeAndRetainEveryAncestor() {
        val nodes = (1..3_000).map { offset ->
            node(100L + offset, parent = 99L + offset, message = if (offset == 3_000) "needle" else "")
        }
        val graph = graph(*nodes.toTypedArray())
        assertArrayEquals(IntArray(graph.size) { it }, ReplyTopologyKeywordFilter.resolve(graph, "needle"))
    }

    fun filteredPositionsCanDifferFromTheirStableGraphIndexes() {
        val graph = graph(node(201L), node(202L), node(203L, message = "visible"))
        val indexes = ReplyTopologyKeywordFilter.resolve(graph, "visible")
        assertArrayEquals(intArrayOf(0, 3), indexes)
        assertEquals(203L, graph.rpids[indexes[1]])
        assertEquals(1, indexes.binarySearch(3))
        assertTrue(indexes.binarySearch(2) < 0)
    }

    private fun graph(vararg nodes: ReplyTopologyNodeSnapshot) = ReplyTopologyGraphBuilder.build(
        key, listOf(node(100L, parent = 0L)) + nodes
    )

    private fun visible(graph: ReplyTopologyGraph, query: String): LongArray =
        ReplyTopologyKeywordFilter.resolve(graph, query).map { graph.rpids[it] }.toLongArray()

    private fun node(id: Long, parent: Long = 100L, author: String = "", replied: String? = null,
        message: String = "", flags: Int = 0) = ReplyTopologyNodeSnapshot.fromRaw(
        rpid = id, rootRpid = if (id == 100L) 0L else 100L, parentRpid = parent,
        authorName = author, repliedAuthorName = replied, message = message, flags = flags
    )

    fun verifyAllCases() {
        fun runIsolated(block: (ReplyTopologyKeywordFilterCases) -> Unit) {
            val s = ReplyTopologyKeywordFilterCases()
            block(s)
        }
        runIsolated { s -> s.emptyQueryPreservesEveryOriginalPosition() }
        runIsolated { s -> s.authorMatchKeepsItsAncestorsAndDropsTheOtherBranch() }
        runIsolated { s -> s.messageMatchKeepsTheEntireParentChainInGraphOrder() }
        runIsolated { s -> s.repliedAuthorCanMatchAndNullRepliedAuthorsAreSafe() }
        runIsolated { s -> s.keywordsCannotJoinFieldsEvenWhenTheyContainNul() }
        runIsolated { s -> s.matchingSiblingsShareOnlyOneCopyOfTheirAncestor() }
        runIsolated { s -> s.normalizationDoesNotDependOnTheSystemLocale() }
        runIsolated { s -> s.noMatchAndEmptyPlaceholderTextProduceNoRows() }
        runIsolated { s -> s.filteringDoesNotRewriteGraphDataOrPurificationFlags() }
        runIsolated { s -> s.deepReplyChainsRemainIterativeAndRetainEveryAncestor() }
        runIsolated { s -> s.filteredPositionsCanDifferFromTheirStableGraphIndexes() }
    }
}

private class ReplyTopologyNodeStoreCases {
    private val key = ReplyTopologyThreadKey(1L, 1L, 100L)
    private fun node(id: Long, text: String = "reply") = ReplyTopologyNodeSnapshot.fromRaw(
        id, if (id == key.rootRpid) 0L else key.rootRpid,
        if (id == key.rootRpid) 0L else key.rootRpid, message = text
    )

    fun aRejectedPageCannotPartlyMutateTheStore() {
        val store = ReplyTopologyNodeStore(key, maxEstimatedBytes = 7_000L)
        assertTrue(store.append(listOf(node(100L), node(101L))).accepted)
        assertFalse(store.append(listOf(node(102L), node(103L))).accepted)
        assertEquals(2, store.uniqueCount)
        assertEquals(1, store.replyCount)
        assertEquals(listOf(100L, 101L), store.snapshot().map { it.rpid })
        assertTrue(store.append(listOf(node(102L))).accepted)
    }

    fun duplicateSnapshotsAreBoundedAndDoNotCountAsNewReplies() {
        val store = ReplyTopologyNodeStore(key)
        store.append(listOf(node(100L), node(101L)))
        repeat(100) { assertEquals(0, store.append(listOf(node(101L, "more complete"))).newUniqueNodes) }
        assertEquals(1, store.replyCount)
        assertEquals(3, store.snapshot().size)
        val graph = ReplyTopologyGraphBuilder.build(key, store.snapshot())
        assertTrue(graph.messagePreviews.contains("more complete"))
    }

    fun duplicateAndFreshSnapshotsShareTheMemoryBudget() {
        val store = ReplyTopologyNodeStore(key, maxEstimatedBytes = 4_200L)
        store.append(listOf(node(100L), node(101L)))
        assertFalse(store.append(listOf(node(101L, "expanded"))).accepted)
        assertEquals(2, store.snapshot().size)
        store.clear()
        assertEquals(0, store.uniqueCount)
        assertTrue(store.append(listOf(node(100L))).accepted)
    }

    fun differentThreadNodesCannotPolluteTheLoadedCount() {
        val store = ReplyTopologyNodeStore(key)
        assertThrows(IllegalArgumentException::class.java) {
            store.append(listOf(node(101L), node(102L).copy(rootRpid = 999L)))
        }
        assertEquals(0, store.uniqueCount)
    }

    fun moreThanFiveThousandRepliesRemainAvailableForTheTree() {
        val store = ReplyTopologyNodeStore(key)
        assertTrue(store.append((100L..6_100L).map { node(it) }).accepted)
        assertEquals(6_000, store.replyCount)
        assertEquals(6_001, ReplyTopologyGraphBuilder.build(key, store.snapshot()).size)
    }

    fun laterPagesCanUpgradeTheSameReplyWithoutAddingUnboundedSnapshots() {
        val store = ReplyTopologyNodeStore(key)
        store.append(listOf(node(100L), node(101L, "")))
        store.append(listOf(node(101L, "")))
        val richer = node(101L, "third page complete content").copy(authorName = "Alice", ctime = 42L)
        assertTrue(store.append(listOf(richer)).accepted)
        repeat(100) { store.append(listOf(node(101L, ""))) }
        assertEquals(3, store.snapshot().size)
        assertEquals(1, store.replyCount)
        val graph = ReplyTopologyGraphBuilder.build(key, store.snapshot())
        val index = graph.rpids.indexOf(101L)
        assertEquals("Alice", graph.authorNames[index])
        assertEquals("third page complete content", graph.messagePreviews[index])
        assertEquals(42L, graph.ctimes[index])
    }

    fun rejectedReplacementAndNewReplyLeaveTheWholePreviousPageUnchanged() {
        val store = ReplyTopologyNodeStore(key, maxEstimatedBytes = 6_300L)
        store.append(listOf(node(100L), node(101L, "old")))
        store.append(listOf(node(101L, "short")))
        val before = store.snapshot()
        assertFalse(store.append(listOf(node(101L, "x".repeat(140)), node(102L))).accepted)
        assertEquals(before, store.snapshot())
        assertEquals(1, store.replyCount)
        assertTrue(store.append(listOf(node(101L, "slightly longer"))).accepted)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (ReplyTopologyNodeStoreCases) -> Unit) {
            val s = ReplyTopologyNodeStoreCases()
            block(s)
        }
        runIsolated { s -> s.aRejectedPageCannotPartlyMutateTheStore() }
        runIsolated { s -> s.duplicateSnapshotsAreBoundedAndDoNotCountAsNewReplies() }
        runIsolated { s -> s.duplicateAndFreshSnapshotsShareTheMemoryBudget() }
        runIsolated { s -> s.differentThreadNodesCannotPolluteTheLoadedCount() }
        runIsolated { s -> s.moreThanFiveThousandRepliesRemainAvailableForTheTree() }
        runIsolated { s -> s.laterPagesCanUpgradeTheSameReplyWithoutAddingUnboundedSnapshots() }
        runIsolated { s -> s.rejectedReplacementAndNewReplyLeaveTheWholePreviousPageUnchanged() }
    }
}

private class ReplyTopologyPagingGuardCases {

    fun emptyNextOffsetCompletesEvenAtNodeBudget() {
        val guard = ReplyTopologyPagingGuard(
            startedAtMs = 100L,
            budget = ReplyTopologyPagingBudget(maxUniqueNodes = 10)
        )

        val decision = guard.onPage("first", "", 10, 10, 101L) as ReplyTopologyPagingDecision.Stop

        assertEquals(ReplyTopologyPagingStopReason.COMPLETE, decision.reason)
        assertTrue(decision.complete)
    }

    fun repeatedOffsetStopsWithoutRequestingLoop() {
        val guard = ReplyTopologyPagingGuard(startedAtMs = 0L)
        assertEquals(
            ReplyTopologyPagingDecision.LoadNext("second"),
            guard.onPage("first", "second", 3, 3, 1L)
        )

        val decision = guard.onPage("second", "first", 2, 5, 2L) as ReplyTopologyPagingDecision.Stop

        assertEquals(ReplyTopologyPagingStopReason.REPEATED_OFFSET, decision.reason)
        assertFalse(decision.complete)
    }

    fun twoPagesWithoutNewRpidStopAsNoProgress() {
        val guard = ReplyTopologyPagingGuard(startedAtMs = 0L)
        guard.onPage("a", "b", 0, 10, 1L)

        val decision = guard.onPage("b", "c", 0, 10, 2L) as ReplyTopologyPagingDecision.Stop

        assertEquals(ReplyTopologyPagingStopReason.NO_PROGRESS, decision.reason)
    }

    fun resourceBudgetsStopPartialLoading() {
        val nodeGuard = ReplyTopologyPagingGuard(
            0L,
            ReplyTopologyPagingBudget(maxUniqueNodes = 3)
        )
        val nodeStop = nodeGuard.onPage("a", "b", 3, 3, 1L) as ReplyTopologyPagingDecision.Stop
        assertEquals(ReplyTopologyPagingStopReason.NODE_LIMIT, nodeStop.reason)

        val pageGuard = ReplyTopologyPagingGuard(
            0L,
            ReplyTopologyPagingBudget(maxPages = 1)
        )
        val pageStop = pageGuard.onPage("a", "b", 1, 1, 1L) as ReplyTopologyPagingDecision.Stop
        assertEquals(ReplyTopologyPagingStopReason.PAGE_LIMIT, pageStop.reason)

        val timeGuard = ReplyTopologyPagingGuard(
            100L,
            ReplyTopologyPagingBudget(maxElapsedMs = 10L)
        )
        val timeStop = timeGuard.onPage("a", "b", 1, 1, 110L) as ReplyTopologyPagingDecision.Stop
        assertEquals(ReplyTopologyPagingStopReason.TIME_LIMIT, timeStop.reason)
    }

    fun invalidCountersFailClosedForThisFeatureOnly() {
        val guard = ReplyTopologyPagingGuard(startedAtMs = 100L)
        val decision = guard.onPage("a", "b", -1, 0, 99L) as ReplyTopologyPagingDecision.Stop

        assertEquals(ReplyTopologyPagingStopReason.INVALID_PAGE, decision.reason)
        assertFalse(decision.complete)
    }

    fun decreasingUniqueTotalIsRejectedAsStaleAccumulatorState() {
        val guard = ReplyTopologyPagingGuard(startedAtMs = 0L)
        guard.onPage("a", "b", newUniqueNodes = 4, totalUniqueNodes = 4, nowMs = 1L)

        val decision = guard.onPage(
            "b",
            "c",
            newUniqueNodes = 1,
            totalUniqueNodes = 3,
            nowMs = 2L
        ) as ReplyTopologyPagingDecision.Stop

        assertEquals(ReplyTopologyPagingStopReason.INVALID_PAGE, decision.reason)
    }

    fun continuedBatchesKeepCursorHistoryAndUseNewNodeBudgetPerBatch() {
        val budget = ReplyTopologyPagingBudget(maxUniqueNodes = 2)
        val guard = ReplyTopologyPagingGuard(0L, budget)
        assertEquals(ReplyTopologyPagingStopReason.NODE_LIMIT,
            (guard.onPage("", "a", 2, 2, 1L) as ReplyTopologyPagingDecision.Stop).reason)
        guard.beginBatch(2L, budget)
        assertEquals(ReplyTopologyPagingDecision.LoadNext("b"), guard.onPage("a", "b", 1, 3, 3L))
        assertEquals(ReplyTopologyPagingStopReason.NODE_LIMIT,
            (guard.onPage("b", "c", 1, 4, 4L) as ReplyTopologyPagingDecision.Stop).reason)
        guard.beginBatch(5L, budget)
        val loop = guard.onPage("c", "a", 1, 5, 6L) as ReplyTopologyPagingDecision.Stop
        assertEquals(ReplyTopologyPagingStopReason.REPEATED_OFFSET, loop.reason)
        assertFalse(loop.complete)
    }

    fun terminalCursorNeverCompletesATruncatedPage() {
        val guard = ReplyTopologyPagingGuard(0L)
        val stop = guard.onPage("", null, 4, 4, 1L, pageTruncated = true) as ReplyTopologyPagingDecision.Stop
        assertEquals(ReplyTopologyPagingStopReason.INVALID_PAGE, stop.reason)
        assertFalse(stop.complete)
    }

    fun batchingCannotResetTheNoProgressProtection() {
        val guard = ReplyTopologyPagingGuard(0L)
        guard.onPage("", "a", 1, 1, 1L)
        guard.onPage("a", "b", 0, 1, 2L)
        guard.beginBatch(3L, ReplyTopologyPagingBudget())
        val stop = guard.onPage("b", "c", 0, 1, 4L) as ReplyTopologyPagingDecision.Stop
        assertEquals(ReplyTopologyPagingStopReason.NO_PROGRESS, stop.reason)
    }

    fun manyBatchesCanFinishBeyondTheOldFiveThousandNodeLimit() {
        val budget = ReplyTopologyPagingBudget(maxUniqueNodes = 1_500)
        val guard = ReplyTopologyPagingGuard(0L, budget)
        for (page in 0..5) {
            val now = page * 2L + 1L
            val stop = guard.onPage(if (page == 0) "" else "p$page",
                if (page == 5) null else "p${page + 1}", 1_500, (page + 1) * 1_500, now) as ReplyTopologyPagingDecision.Stop
            assertEquals(page == 5, stop.complete)
            if (page < 5) guard.beginBatch(now + 1L, budget)
        }
    }

    fun openingTheFullTreeDuringLoadingUsesItsExtendedNoProgressBudget() {
        val guard = ReplyTopologyPagingGuard(0L)
        guard.onPage("", "a", 1, 1, 1L)
        guard.onPage("a", "b", 0, 1, 2L)
        guard.beginBatch(3L, ReplyTopologyPagingBudget(maxConsecutiveNoProgressPages = 3))
        assertEquals(ReplyTopologyPagingDecision.LoadNext("c"), guard.onPage("b", "c", 0, 1, 4L))
        assertEquals(ReplyTopologyPagingDecision.LoadNext("d"), guard.onPage("c", "d", 1, 2, 5L))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (ReplyTopologyPagingGuardCases) -> Unit) {
            val s = ReplyTopologyPagingGuardCases()
            block(s)
        }
        runIsolated { s -> s.emptyNextOffsetCompletesEvenAtNodeBudget() }
        runIsolated { s -> s.repeatedOffsetStopsWithoutRequestingLoop() }
        runIsolated { s -> s.twoPagesWithoutNewRpidStopAsNoProgress() }
        runIsolated { s -> s.resourceBudgetsStopPartialLoading() }
        runIsolated { s -> s.invalidCountersFailClosedForThisFeatureOnly() }
        runIsolated { s -> s.decreasingUniqueTotalIsRejectedAsStaleAccumulatorState() }
        runIsolated { s -> s.continuedBatchesKeepCursorHistoryAndUseNewNodeBudgetPerBatch() }
        runIsolated { s -> s.terminalCursorNeverCompletesATruncatedPage() }
        runIsolated { s -> s.batchingCannotResetTheNoProgressProtection() }
        runIsolated { s -> s.manyBatchesCanFinishBeyondTheOldFiveThousandNodeLimit() }
        runIsolated { s -> s.openingTheFullTreeDuringLoadingUsesItsExtendedNoProgressBudget() }
    }
}

private class ReplyTopologySessionGateCases {

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

    fun verifyAllCases() {
        fun runIsolated(block: (ReplyTopologySessionGateCases) -> Unit) {
            val s = ReplyTopologySessionGateCases()
            block(s)
        }
        runIsolated { s -> s.allowsOnlyOneInFlightRequestAndReleasesItOnCompletion() }
        runIsolated { s -> s.openingNewSessionInvalidatesLateResultFromOldRoot() }
        runIsolated { s -> s.staleCloseCannotCloseNewSession() }
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
