package com.Bilibili_Innocent_Lab.xposedmodule.ui.overlay

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.*
import org.junit.Test

class ReplyTopologyPanelFilterContractTest {
    fun unfilteredPagingRetainsIncrementalInsertionAndSelectionUpdates() {
        val submit = SourceContract.read("ui/overlay/ReplyTopologyWorkflowAdapter.kt")
            .after("fun submit(snapshot:").before("fun setKeywordQuery")
        assertTrue(submit.contains("val appendOnly = keywordQuery.isEmpty() && old != null"))
        assertTrue(submit.contains("prefixBoundaryMatches(old, snapshot.graph)"))
        assertTrue(submit.before("if (appendOnly)").contains("rebuildDisplayIndexes()"))
        assertTrue(submit.after("if (appendOnly)").contains("notifyItemRangeInserted(oldSize, inserted)"))
        assertTrue(submit.contains("notifySelectionChange(old, oldSelection, snapshot.graph, selectedRpid)"))
    }

    fun drawingAndRowIdentityUseTheFilteredDisplayMapping() {
        val source = SourceContract.read("ui/overlay/ReplyTopologyWorkflowAdapter.kt")
        assertTrue(source.contains("if (selectRpid(rpid) == RecyclerView.NO_POSITION) return@setOnClickListener"))
        assertTrue(source.contains("if (selectRpid(rpid) == RecyclerView.NO_POSITION) return@setOnMessageLongPress false"))
        assertTrue(source.after("override fun getItemId").before("override fun onCreateViewHolder")
            .contains("graphIndexAtDisplay(position)"))
        val bind = source.after("private fun bind(holder:").before("private fun buildMeta")
        assertTrue(bind.contains("current.rpids[graphIndex]"))
        assertTrue(bind.contains("current.messagePreviews[graphIndex]"))
        val draw = source.after("override fun onDrawOver").before("private fun laneX")
        assertTrue(draw.contains("adapter.graphIndexAtDisplay(position)"))
        assertTrue(draw.contains("adapter.displayPositionOfGraphIndex(parentIndex)"))
        assertTrue(draw.contains("branchGeometry.update(parentX, nodeX, top, bottom)"))
        assertFalse(draw.contains("graph.flags[position]"))
    }

    fun branchShapeCannotDependOnTheParentViewBeingVisibleOrRecycled() {
        val source = SourceContract.read("ui/overlay/ReplyTopologyWorkflowAdapter.kt")
        val draw = source.after("override fun onDrawOver").before("private fun laneX")
        assertFalse(draw.contains("findViewHolderForAdapterPosition"))
        assertFalse(draw.contains("parentCenterY"))
        assertTrue(source.before("override fun onDrawOver").contains("private val branchGeometry = ReplyTopologyBranchGeometry()"))
        assertFalse(draw.contains("ReplyTopologyBranchGeometry()"))
        assertTrue(draw.contains("child.top + child.translationY"))
        assertTrue(draw.contains("child.bottom + child.translationY"))
        assertTrue(draw.contains("branchPath.moveTo(branchGeometry.startX, branchGeometry.startY)"))
    }

    fun keywordChangesRefreshExportAndTrackStateUnderTheHostGuard() {
        val panel = SourceContract.read("ui/overlay/ReplyTopologyPanelView.kt")
        val watcher = panel.after("private val keywordWatcher").before("private var panelListener")
        assertTrue(watcher.contains("HostThreadGuard.run(\"reply_topology.keyword_change\")"))
        assertTrue(watcher.contains("workflowAdapter.setKeywordQuery"))
        assertTrue(watcher.contains("updateExportAvailability()"))
        assertTrue(watcher.contains("recyclerView.invalidateItemDecorations()"))
        val row = panel.after("private fun createKeywordRow").before("private fun updateExportAvailability")
        assertTrue(row.contains("exportView.visibility = View.VISIBLE"))
        assertTrue(row.contains("filterInput, LayoutParams(0, dp(30), 1f)"))
        assertTrue(row.contains("HostThreadGuard.run(\"reply_topology.export\")"))
    }

    fun closingAndDetachmentBothReleaseTextAndClickListeners() {
        val panel = SourceContract.read("ui/overlay/ReplyTopologyPanelView.kt")
        assertTrue(panel.after("fun playExit").before("fun submit").contains("releaseKeywordControls()"))
        assertTrue(panel.after("fun releaseResources").before("override fun onDetachedFromWindow")
            .contains("releaseKeywordControls()"))
        val release = panel.after("private fun releaseKeywordControls").before("private fun createWorkflowList")
        assertTrue(release.contains("removeTextChangedListener(keywordWatcher)"))
        assertTrue(release.contains("setOnEditorActionListener(null)"))
        assertTrue(release.contains("exportView.setOnClickListener(null)"))
        assertTrue(release.contains("clearKeywordFocus()"))
        val compact = panel.after("private fun finalizeCompactState").before("private fun setOpacityRowHeight")
        assertTrue(compact.contains("keywordRow.visibility = View.GONE"))
        assertTrue(compact.contains("keywordRow.visibility = View.VISIBLE"))
    }

    fun ownedClipboardWritesAreExemptBeforeAnySemanticSuppression() {
        val hook = SourceContract.read("hook/HookEntry.kt")
            .after("val clip = args.getOrNull(0) as? android.content.ClipData")
            .before("logInfo(\"free_copy_desc_ok\"")
        assertTrue(hook.before("val clipText").contains("ReplyTopologyClipboardWrite.owns(clip)"))
        val export = SourceContract.read("ui/overlay/ReplyTopologyPanelView.kt")
            .after("private fun exportCurrentTopology").before("private fun clearKeywordFocus")
        assertTrue(export.contains("ReplyTopologyClipboardWrite.write(clip) { clipboard.setPrimaryClip(clip) }"))
        assertTrue(export.contains("strings.exportFailed"))
        assertTrue(export.contains("strings.exportTooLarge"))
    }

    fun everyLanguageHasUsedExportMessagesAndTheFeatureHasAHighlight() {
        val source = SourceContract.read("ui/overlay/ReplyTopologyPanelContract.kt")
        for ((start, end) in listOf("private val SIMPLIFIED_CHINESE" to "private val TRADITIONAL_CHINESE",
                "private val TRADITIONAL_CHINESE" to "private val ENGLISH",
                "private val ENGLISH" to "internal data class ReplyTopologyPanelConfig")) {
            val language = source.after(start).before(end)
            for (field in listOf("filterHint", "export", "exportSuccess", "exportEmpty", "exportFailed", "exportTooLarge",
                    "pathScopeHint", "completeMessage", "memoryLimitMessage", "pageTruncatedMessage")) {
                assertTrue(language.contains("$field ="))
            }
        }
        val highlights = SourceContract.read("ui/release/ReleaseHighlightsCatalog.kt")
        assertTrue(highlights.contains("reply-topology-keyword-export"))
        assertTrue(highlights.contains("HighlightDestination(\"comments.reply_topology.enabled\")"))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (ReplyTopologyPanelFilterContractTest) -> Unit) {
            val s = ReplyTopologyPanelFilterContractTest()
            block(s)
        }
        runIsolated { s -> s.unfilteredPagingRetainsIncrementalInsertionAndSelectionUpdates() }
        runIsolated { s -> s.drawingAndRowIdentityUseTheFilteredDisplayMapping() }
        runIsolated { s -> s.branchShapeCannotDependOnTheParentViewBeingVisibleOrRecycled() }
        runIsolated { s -> s.keywordChangesRefreshExportAndTrackStateUnderTheHostGuard() }
        runIsolated { s -> s.closingAndDetachmentBothReleaseTextAndClickListeners() }
        runIsolated { s -> s.ownedClipboardWritesAreExemptBeforeAnySemanticSuppression() }
        runIsolated { s -> s.everyLanguageHasUsedExportMessagesAndTheFeatureHasAHighlight() }
    }
}
