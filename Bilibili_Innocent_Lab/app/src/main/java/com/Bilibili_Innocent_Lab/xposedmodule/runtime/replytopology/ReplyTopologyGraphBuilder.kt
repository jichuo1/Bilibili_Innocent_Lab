package com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology

import java.util.concurrent.CancellationException

/** 纯 Kotlin、无递归的回复拓扑构建器；宿主版本适配和 UI 均不进入这里。 */
internal object ReplyTopologyGraphBuilder {

    fun build(
        key: ReplyTopologyThreadKey,
        nodes: Iterable<ReplyTopologyNodeSnapshot>
    ): ReplyTopologyGraph {
        require(key.isValid) { "Invalid reply topology thread key: $key" }

        val diagnostics = MutableDiagnostics()
        val deduplicated = HashMap<Long, MutableNode>()
        nodes.forEach { incoming ->
            checkNotCancelled()
            when {
                incoming.rpid <= 0L -> diagnostics.droppedInvalidNodes++
                incoming.rpid != key.rootRpid &&
                    incoming.rootRpid > 0L &&
                    incoming.rootRpid != key.rootRpid -> diagnostics.droppedWrongRootNodes++
                else -> mergeInto(deduplicated, incoming, diagnostics)
            }
        }

        val root = deduplicated[key.rootRpid]
        if (root == null) {
            deduplicated[key.rootRpid] = MutableNode(
                ReplyTopologyNodeSnapshot(
                    rpid = key.rootRpid,
                    rootRpid = 0L,
                    parentRpid = 0L,
                    dialogId = 0L,
                    ctime = 0L,
                    authorMid = 0L,
                    authorName = "",
                    repliedAuthorName = null,
                    messagePreview = "",
                    flags = ReplyTopologyNodeFlags.ROOT or
                        ReplyTopologyNodeFlags.PLACEHOLDER or
                        ReplyTopologyNodeFlags.MISSING_ROOT
                )
            )
        } else {
            root.snapshot = root.snapshot.copy(
                rootRpid = 0L,
                parentRpid = 0L,
                flags = root.snapshot.flags or ReplyTopologyNodeFlags.ROOT
            )
        }

        addMissingParentPlaceholders(key.rootRpid, deduplicated, diagnostics)
        checkNotCancelled()

        // HashMap 顺序不可作为渲染顺序；内部索引固定为 root + rpid 升序，保证诊断可复现。
        val orderedIds = deduplicated.keys
            .asSequence()
            .filter { it != key.rootRpid }
            .sorted()
            .toMutableList()
            .apply { add(0, key.rootRpid) }
        val internalNodes = ArrayList<MutableNode>(orderedIds.size)
        val internalIndexById = HashMap<Long, Int>(orderedIds.size * 4 / 3 + 1)
        orderedIds.forEachIndexed { index, rpid ->
            checkNotCancelled()
            internalIndexById[rpid] = index
            internalNodes += checkNotNull(deduplicated[rpid])
        }

        val parentIndexes = IntArray(internalNodes.size) { -1 }
        for (index in 1 until internalNodes.size) {
            checkNotCancelled()
            val node = internalNodes[index]
            val parentRpid = node.snapshot.parentRpid
            when {
                parentRpid <= 0L || parentRpid == key.rootRpid -> parentIndexes[index] = 0
                parentRpid == node.snapshot.rpid -> {
                    parentIndexes[index] = 0
                    node.addFlag(ReplyTopologyNodeFlags.SELF_PARENT)
                    diagnostics.selfParentNodes++
                }
                else -> parentIndexes[index] = internalIndexById[parentRpid] ?: 0
            }
        }

        breakCyclesIteratively(internalNodes, parentIndexes, diagnostics)

        val children = Array(internalNodes.size) { ArrayList<Int>() }
        for (index in 1 until internalNodes.size) {
            checkNotCancelled()
            children[parentIndexes[index]].add(index)
        }
        val siblingComparator = compareBy<Int>(
            { internalNodes[it].snapshot.ctime.takeIf { time -> time > 0L } ?: Long.MAX_VALUE },
            { internalNodes[it].snapshot.rpid }
        )
        children.forEach {
            checkNotCancelled()
            it.sortWith(siblingComparator)
        }

        val displayOrder = iterativePreOrder(children)
        val displayIndexByInternal = IntArray(internalNodes.size)
        displayOrder.forEachIndexed { displayIndex, internalIndex ->
            checkNotCancelled()
            displayIndexByInternal[internalIndex] = displayIndex
        }

        val size = displayOrder.size
        val rpids = LongArray(size)
        val displayParents = IntArray(size)
        val depths = IntArray(size)
        val childCounts = IntArray(size)
        val ctimes = LongArray(size)
        val authorMids = LongArray(size)
        val dialogIds = LongArray(size)
        val flags = IntArray(size)
        val authorNames = Array(size) { "" }
        val repliedAuthorNames = arrayOfNulls<String>(size)
        val messagePreviews = Array(size) { "" }

        displayOrder.forEachIndexed { displayIndex, internalIndex ->
            checkNotCancelled()
            val node = internalNodes[internalIndex].snapshot
            val internalParent = parentIndexes[internalIndex]
            rpids[displayIndex] = node.rpid
            displayParents[displayIndex] = if (internalParent < 0) -1 else displayIndexByInternal[internalParent]
            depths[displayIndex] = if (internalParent < 0) 0 else depths[displayParents[displayIndex]] + 1
            childCounts[displayIndex] = children[internalIndex].size
            ctimes[displayIndex] = node.ctime
            authorMids[displayIndex] = node.authorMid
            dialogIds[displayIndex] = node.dialogId
            flags[displayIndex] = node.flags
            authorNames[displayIndex] = node.authorName
            repliedAuthorNames[displayIndex] = node.repliedAuthorName
            messagePreviews[displayIndex] = node.messagePreview
        }

        return ReplyTopologyGraph(
            key = key,
            rpids = rpids,
            parentIndexes = displayParents,
            depths = depths,
            childCounts = childCounts,
            ctimes = ctimes,
            authorMids = authorMids,
            dialogIds = dialogIds,
            flags = flags,
            authorNames = authorNames,
            repliedAuthorNames = repliedAuthorNames,
            messagePreviews = messagePreviews,
            diagnostics = diagnostics.freeze()
        )
    }

    private fun mergeInto(
        destination: MutableMap<Long, MutableNode>,
        incoming: ReplyTopologyNodeSnapshot,
        diagnostics: MutableDiagnostics
    ) {
        val existing = destination[incoming.rpid]
        if (existing == null) {
            destination[incoming.rpid] = MutableNode(incoming)
            return
        }

        diagnostics.duplicateNodes++
        val conflict = hasCriticalConflict(existing.snapshot, incoming)
        if (conflict) diagnostics.conflictingDuplicateNodes++
        existing.snapshot = mergeSnapshots(existing.snapshot, incoming).copy(
            flags = existing.snapshot.flags or incoming.flags or
                ReplyTopologyNodeFlags.DUPLICATE or
                (if (conflict) ReplyTopologyNodeFlags.DUPLICATE_CONFLICT else 0)
        )
    }

    private fun mergeSnapshots(
        first: ReplyTopologyNodeSnapshot,
        second: ReplyTopologyNodeSnapshot
    ): ReplyTopologyNodeSnapshot {
        val primary = if (compareQuality(first, second) >= 0) first else second
        val fallback = if (primary === first) second else first
        return primary.copy(
            rootRpid = primary.rootRpid.takeIf { it > 0L } ?: fallback.rootRpid,
            parentRpid = primary.parentRpid.takeIf { it > 0L } ?: fallback.parentRpid,
            dialogId = primary.dialogId.takeIf { it > 0L } ?: fallback.dialogId,
            ctime = primary.ctime.takeIf { it > 0L } ?: fallback.ctime,
            authorMid = primary.authorMid.takeIf { it > 0L } ?: fallback.authorMid,
            authorName = primary.authorName.ifEmpty { fallback.authorName },
            repliedAuthorName = primary.repliedAuthorName ?: fallback.repliedAuthorName,
            messagePreview = primary.messagePreview.ifEmpty { fallback.messagePreview }
        )
    }

    private fun compareQuality(
        first: ReplyTopologyNodeSnapshot,
        second: ReplyTopologyNodeSnapshot
    ): Int {
        val score = qualityScore(first).compareTo(qualityScore(second))
        if (score != 0) return score
        val messageLength = first.messagePreview.length.compareTo(second.messagePreview.length)
        if (messageLength != 0) return messageLength
        val authorLength = first.authorName.length.compareTo(second.authorName.length)
        if (authorLength != 0) return authorLength
        val parent = second.parentRpid.compareTo(first.parentRpid)
        if (parent != 0) return parent
        val time = second.ctime.compareTo(first.ctime)
        if (time != 0) return time
        val root = second.rootRpid.compareTo(first.rootRpid)
        if (root != 0) return root
        val dialog = second.dialogId.compareTo(first.dialogId)
        if (dialog != 0) return dialog
        val authorMid = second.authorMid.compareTo(first.authorMid)
        if (authorMid != 0) return authorMid
        val author = first.authorName.compareTo(second.authorName)
        if (author != 0) return author
        val repliedAuthor = first.repliedAuthorName.orEmpty()
            .compareTo(second.repliedAuthorName.orEmpty())
        if (repliedAuthor != 0) return repliedAuthor
        return first.messagePreview.compareTo(second.messagePreview)
    }

    private fun qualityScore(node: ReplyTopologyNodeSnapshot): Int =
        (if (node.rootRpid > 0L) 2 else 0) +
            (if (node.parentRpid > 0L) 2 else 0) +
            (if (node.dialogId > 0L) 1 else 0) +
            (if (node.ctime > 0L) 1 else 0) +
            (if (node.authorMid > 0L) 1 else 0) +
            (if (node.authorName.isNotEmpty()) 2 else 0) +
            (if (node.repliedAuthorName != null) 1 else 0) +
            (if (node.messagePreview.isNotEmpty()) 3 else 0)

    private fun hasCriticalConflict(
        first: ReplyTopologyNodeSnapshot,
        second: ReplyTopologyNodeSnapshot
    ): Boolean =
        differsWhenPresent(first.rootRpid, second.rootRpid) ||
            differsWhenPresent(first.parentRpid, second.parentRpid) ||
            differsWhenPresent(first.authorMid, second.authorMid)

    private fun differsWhenPresent(first: Long, second: Long): Boolean =
        first > 0L && second > 0L && first != second

    private fun addMissingParentPlaceholders(
        rootRpid: Long,
        nodes: MutableMap<Long, MutableNode>,
        diagnostics: MutableDiagnostics
    ) {
        val earliestChildTime = HashMap<Long, Long>()
        nodes.values.forEach { mutable ->
            checkNotCancelled()
            val node = mutable.snapshot
            val parent = node.parentRpid
            if (node.rpid == rootRpid || parent <= 0L || parent == rootRpid || parent == node.rpid) {
                return@forEach
            }
            if (nodes[parent] == null) {
                val current = earliestChildTime[parent]
                if (node.ctime > 0L && (current == null || current <= 0L || node.ctime < current)) {
                    earliestChildTime[parent] = node.ctime
                } else if (current == null) {
                    earliestChildTime[parent] = 0L
                }
            }
        }
        earliestChildTime.forEach { (missingParentRpid, childTime) ->
            checkNotCancelled()
            nodes[missingParentRpid] = MutableNode(
                ReplyTopologyNodeSnapshot(
                    rpid = missingParentRpid,
                    rootRpid = rootRpid,
                    parentRpid = rootRpid,
                    dialogId = 0L,
                    ctime = childTime,
                    authorMid = 0L,
                    authorName = "",
                    repliedAuthorName = null,
                    messagePreview = "",
                    flags = ReplyTopologyNodeFlags.PLACEHOLDER or
                        ReplyTopologyNodeFlags.MISSING_PARENT
                )
            )
            diagnostics.missingParentNodes++
        }
    }

    private fun breakCyclesIteratively(
        nodes: List<MutableNode>,
        parentIndexes: IntArray,
        diagnostics: MutableDiagnostics
    ) {
        val finished = BooleanArray(nodes.size)
        finished[0] = true
        val seenRun = IntArray(nodes.size)
        val seenPosition = IntArray(nodes.size)
        var runId = 0

        for (start in 1 until nodes.size) {
            checkNotCancelled()
            if (finished[start]) continue
            runId = if (runId == Int.MAX_VALUE) 1 else runId + 1
            if (runId == 1) seenRun.fill(0)
            val path = ArrayList<Int>()
            var current = start

            while (current != 0 && !finished[current] && seenRun[current] != runId) {
                checkNotCancelled()
                seenRun[current] = runId
                seenPosition[current] = path.size
                path += current
                current = parentIndexes[current]
            }

            if (current != 0 && !finished[current] && seenRun[current] == runId) {
                val cycleStart = seenPosition[current]
                val cycleIndexes = path.subList(cycleStart, path.size)
                val breaker = cycleIndexes.minWithOrNull(
                    compareBy<Int>(
                        { nodes[it].snapshot.ctime.takeIf { time -> time > 0L } ?: Long.MAX_VALUE },
                        { nodes[it].snapshot.rpid }
                    )
                ) ?: current
                parentIndexes[breaker] = 0
                cycleIndexes.forEach { nodes[it].addFlag(ReplyTopologyNodeFlags.CYCLE) }
                diagnostics.cycleNodes += cycleIndexes.size
                diagnostics.brokenCycleEdges++
            }
            path.forEach { finished[it] = true }
        }
    }

    private fun iterativePreOrder(children: Array<ArrayList<Int>>): IntArray {
        val order = IntArray(children.size)
        val stack = IntArray(children.size)
        var stackSize = 0
        var orderSize = 0
        stack[stackSize++] = 0

        while (stackSize > 0) {
            checkNotCancelled()
            val current = stack[--stackSize]
            order[orderSize++] = current
            val currentChildren = children[current]
            for (index in currentChildren.lastIndex downTo 0) {
                stack[stackSize++] = currentChildren[index]
            }
        }
        check(orderSize == children.size) { "Reply topology contains unreachable nodes" }
        return order
    }

    private fun checkNotCancelled() {
        if (Thread.currentThread().isInterrupted) {
            throw CancellationException("Reply topology graph build cancelled")
        }
    }

    private class MutableNode(var snapshot: ReplyTopologyNodeSnapshot) {
        fun addFlag(flag: Int) {
            snapshot = snapshot.copy(flags = snapshot.flags or flag)
        }
    }

    private class MutableDiagnostics {
        var droppedInvalidNodes = 0
        var droppedWrongRootNodes = 0
        var duplicateNodes = 0
        var conflictingDuplicateNodes = 0
        var missingParentNodes = 0
        var selfParentNodes = 0
        var cycleNodes = 0
        var brokenCycleEdges = 0

        fun freeze(): ReplyTopologyGraphDiagnostics = ReplyTopologyGraphDiagnostics(
            droppedInvalidNodes = droppedInvalidNodes,
            droppedWrongRootNodes = droppedWrongRootNodes,
            duplicateNodes = duplicateNodes,
            conflictingDuplicateNodes = conflictingDuplicateNodes,
            missingParentNodes = missingParentNodes,
            selfParentNodes = selfParentNodes,
            cycleNodes = cycleNodes,
            brokenCycleEdges = brokenCycleEdges
        )
    }
}
