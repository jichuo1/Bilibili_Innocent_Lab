package com.Bilibili_Innocent_Lab.xposedmodule.ui.overlay

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 回复拓扑面板过滤、绘制、导出、剪贴板免抑、全树浏览器画布与生命周期契约。 */
class ReplyTopologyPanelContractTest {

    @Test
    fun panelFilteringDrawingExportClipboardAndHighlightContractsHold() {
        val adapter = SourceContract.read("ui/overlay/ReplyTopologyWorkflowAdapter.kt")
        val submit = adapter.after("fun submit(snapshot:").before("fun setKeywordQuery")
        assertTrue(submit.contains("val appendOnly = keywordQuery.isEmpty() && old != null"))
        assertTrue(submit.contains("prefixBoundaryMatches(old, snapshot.graph)"))
        assertTrue(submit.before("if (appendOnly)").contains("rebuildDisplayIndexes()"))
        assertTrue(submit.after("if (appendOnly)").contains("notifyItemRangeInserted(oldSize, inserted)"))
        assertTrue(submit.contains("notifySelectionChange(old, oldSelection, snapshot.graph, selectedRpid)"))

        assertTrue(adapter.contains("if (selectRpid(rpid) == RecyclerView.NO_POSITION) return@setOnClickListener"))
        assertTrue(adapter.contains("if (selectRpid(rpid) == RecyclerView.NO_POSITION) return@setOnMessageLongPress false"))
        assertTrue(
            adapter.after("override fun getItemId").before("override fun onCreateViewHolder")
                .contains("graphIndexAtDisplay(position)")
        )
        val bind = adapter.after("private fun bind(holder:").before("private fun buildMeta")
        assertTrue(bind.contains("current.rpids[graphIndex]"))
        assertTrue(bind.contains("current.messagePreviews[graphIndex]"))

        val draw = adapter.after("override fun onDrawOver").before("private fun laneX")
        assertTrue(draw.contains("adapter.graphIndexAtDisplay(position)"))
        assertTrue(draw.contains("adapter.displayPositionOfGraphIndex(parentIndex)"))
        assertTrue(draw.contains("branchGeometry.update(parentX, nodeX, top, bottom)"))
        assertFalse(draw.contains("graph.flags[position]"))
        assertFalse(draw.contains("findViewHolderForAdapterPosition"))
        assertFalse(draw.contains("parentCenterY"))
        assertTrue(adapter.before("override fun onDrawOver").contains("private val branchGeometry = ReplyTopologyBranchGeometry()"))
        assertFalse(draw.contains("ReplyTopologyBranchGeometry()"))
        assertTrue(draw.contains("child.top + child.translationY"))
        assertTrue(draw.contains("child.bottom + child.translationY"))
        assertTrue(draw.contains("branchPath.moveTo(branchGeometry.startX, branchGeometry.startY)"))

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

        assertTrue(panel.after("fun playExit").before("fun submit").contains("releaseKeywordControls()"))
        assertTrue(panel.after("fun releaseResources").before("override fun onDetachedFromWindow").contains("releaseKeywordControls()"))
        val release = panel.after("private fun releaseKeywordControls").before("private fun createWorkflowList")
        assertTrue(release.contains("removeTextChangedListener(keywordWatcher)"))
        assertTrue(release.contains("setOnEditorActionListener(null)"))
        assertTrue(release.contains("exportView.setOnClickListener(null)"))
        assertTrue(release.contains("clearKeywordFocus()"))
        val compact = panel.after("private fun finalizeCompactState").before("private fun setOpacityRowHeight")
        assertTrue(compact.contains("keywordRow.visibility = View.GONE"))
        assertTrue(compact.contains("keywordRow.visibility = View.VISIBLE"))

        val hook = SourceContract.read("hook/feature/freecopy/FreeCopyFeatureInstaller.kt")
            .after("val clip = args.getOrNull(0) as? ClipData")
            .before("free_copy_desc_ok")
        assertTrue(hook.before("val clipText").contains("ReplyTopologyClipboardWrite.owns(clip)"))
        val export = panel.after("private fun exportCurrentTopology").before("private fun clearKeywordFocus")
        assertTrue(export.contains("ReplyTopologyClipboardWrite.write(clip) { clipboard.setPrimaryClip(clip) }"))
        assertTrue(export.contains("strings.exportFailed"))
        assertTrue(export.contains("strings.exportTooLarge"))

        val contract = SourceContract.read("ui/overlay/ReplyTopologyPanelContract.kt")
        for ((start, end) in listOf(
            "private val SIMPLIFIED_CHINESE" to "private val TRADITIONAL_CHINESE",
            "private val TRADITIONAL_CHINESE" to "private val ENGLISH",
            "private val ENGLISH" to "internal data class ReplyTopologyPanelConfig"
        )) {
            val language = contract.after(start).before(end)
            for (field in listOf(
                "filterHint", "export", "exportSuccess", "exportEmpty", "exportFailed", "exportTooLarge",
                "pathScopeHint", "completeMessage", "memoryLimitMessage", "pageTruncatedMessage"
            )) {
                assertTrue(language.contains("$field ="))
            }
        }
        val highlights = SourceContract.read("ui/release/ReleaseHighlightsCatalog.kt")
        assertTrue(highlights.contains("reply-topology-keyword-export"))
        assertTrue(highlights.contains("HighlightDestination(\"comments.reply_topology.enabled\")"))
    }

    @Test
    fun composeMenuTrackBudgetTreeExplorerCanvasAndLifecycleContractsHold() {
        val menu = SourceContract.read("hook/feature/ReplyTopologyComposeMenu.kt")
            .after("fun install(environment: HookEnvironment)")
            .before("private fun installLegacy(")
        val item = menu.after("val item =").before("val fields =")
        assertTrue(item.contains("!method.isStatic || method.isSynthetic || method.returnType != Void.TYPE"))
        assertTrue(item.contains("params.size == 5"))
        assertTrue(item.contains("\"kotlin.jvm.functions.Function0\""))
        assertTrue(item.contains("\"androidx.compose.ui.Modifier\""))
        assertTrue(item.contains("\"androidx.compose.runtime.Composer\""))
        assertTrue(item.contains("\"int\""))
        assertTrue(item.contains("isValidComposeMenuEntryClass("))
        assertTrue(item.contains(".distinct().singleOrNull()"))
        assertFalse(item.contains("\"v0f1\""))
        assertTrue(menu.contains("!it.isSynthetic && it.parameterTypes.contentEquals(fields.map"))
        assertTrue(menu.contains("source.any { !item.isInstance(it) }"))

        val adapter = SourceContract.read("ui/overlay/ReplyTopologyWorkflowAdapter.kt")
        assertTrue(adapter.contains("ReplyTopologyTrackLayout.resolve(w.toFloat(), density, resources.configuration.fontScale)"))
        assertTrue(adapter.contains("setPadding(layout.trackWidth.roundToInt()"))
        val adapterDraw = adapter.after("override fun onDrawOver").before("private fun laneX")
        assertTrue(adapterDraw.contains("canvas.clipRect(0f, 0f, layout.trackWidth"))
        assertTrue(adapterDraw.contains("canvas.restoreToCount(saved)"))
        assertTrue(adapterDraw.contains("trackLayout.width != parent.width.toFloat()"))
        assertTrue(adapter.contains("strings.depthLabel(trueDepth)"))
        assertTrue(adapter.contains("row.setOnPathRequested"))
        assertTrue(adapter.contains("row.setOnClickListener"))
        assertTrue(adapter.contains("row.setOnMessageLongPress"))
        assertTrue(adapter.contains("text = strings.viewPath"))
        assertTrue(adapter.after("fun bind(\n").before("fun setOnPathRequested").contains("pathView.visibility = if (root) View.GONE else View.VISIBLE"))
        val measure = adapter.after("override fun onMeasure").before("private fun updateTrackLayout")
        assertTrue(measure.before("super.onMeasure").contains("updateTrackLayout(MeasureSpec.getSize(widthMeasureSpec))"))
        assertFalse(
            adapter.after("internal class ReplyTopologyNodeRow").before("internal class ReplyTopologyTrackDecoration")
                .contains("override fun onSizeChanged")
        )

        val panel = SourceContract.read("ui/overlay/ReplyTopologyPanelView.kt")
        assertTrue(panel.contains("workflowAdapter.hasCompressedDepth(layout.maxLane)"))
        assertTrue(panel.contains("fullTreeAvailable = (workflowAdapter.currentGraph()?.size ?: 0) > 0"))
        assertFalse(panel.contains("deepTreeAvailable"))
        assertTrue(panel.contains("showTreeExplorer(rpid, path = true)"))
        assertTrue(panel.contains("showTreeExplorer(workflowAdapter.selectedNode(), path = false)"))
        assertTrue(panel.contains("treeExplorer?.submit(snapshot.graph"))
        assertTrue(panel.after("fun playExit").before("fun submit").contains("closeTreeExplorer()"))
        assertTrue(panel.after("fun releaseResources").before("override fun onDetachedFromWindow").contains("closeTreeExplorer()"))
        assertTrue(panel.contains("initialPrefetchItemCount = 8"))
        assertTrue(panel.contains("for (step in 1..4)"))
        assertTrue(panel.contains("setItemViewCacheSize(8)"))
        assertTrue(panel.contains("viewer.submit(graph, rpid, initialPath = rpid.takeIf { path })"))
        assertTrue(panel.after("fun updateState").before("fun setBackgroundOpacity").contains("treeExplorer?.updateState(state)"))

        val show = panel.after("private fun showTreeExplorer").before("private fun closeTreeExplorer")
        assertTrue(show.before("viewer.submit(graph").contains("viewer.updateState(currentState)"))
        assertTrue(show.after("viewer.submit(graph").contains("panelListener?.onFullTreeRequested()"))
        assertTrue(show.contains("onRetry = { panelListener?.onRetryRequested() }"))
        assertTrue(show.contains("onContinue = { panelListener?.onContinueRequested() }"))
        assertTrue(show.contains("it === this.parent && it.windowToken == windowToken"))
        assertTrue(show.contains("parent.addView(viewer, params)"))
        assertFalse(show.contains("SYSTEM_ALERT_WINDOW"))

        val listener = panel.after("private val parentLayoutListener").before("init {")
        assertTrue(listener.contains("updateDimensionsToParent()"))
        val resize = panel.after("private fun updateDimensionsToParent").before("private fun createKeywordRow")
        assertTrue(resize.contains("params.width = newWidth"))
        assertTrue(resize.contains("expandedHeightPx = newHeight"))
        assertTrue(resize.contains("compactAnimator = null"))

        val layout = SourceContract.read("runtime/replytopology/ReplyTopologyTreeLayout.kt")
        assertTrue(layout.contains("graph.depths[index] - baseDepth"))
        assertFalse(layout.contains("coerceAtMost(7)"))

        val canvas = SourceContract.read("ui/overlay/ReplyTopologyTreeCanvas.kt")
        assertTrue(canvas.contains("private val viewport = ReplyTopologyTreeViewport()"))
        assertTrue(canvas.contains("value.visibleRows("))
        assertTrue(canvas.contains("LruCache<Long, TextBlock>(96)"))
        assertTrue(canvas.contains("ScaleGestureDetector"))
        assertTrue(canvas.contains("viewport.pan(-distanceX.toDouble(), -distanceY.toDouble())"))
        assertTrue(canvas.contains("removeCallbacks(flingFrame)"))
        assertTrue(canvas.contains("MotionEvent.ACTION_CANCEL"))
        assertTrue(canvas.contains("textCache.evictAll()"))
        assertTrue(canvas.contains("HostThreadGuard.runnable(\"reply_topology.tree_fling\")"))
        assertTrue(canvas.contains("removeCallbacks(textWarmup)"))
        assertTrue(canvas.contains("HostThreadGuard.runnable(\"reply_topology.tree_text_warmup\")"))
        assertTrue(canvas.contains("HostThreadGuard.call(\"reply_topology.tree_accessibility\", false)"))
        assertTrue(canvas.contains("it.matches(value.graph, index)"))

        val canvasDraw = canvas.after("private fun drawScene").before("private fun textBlock")
        assertFalse(canvasDraw.contains("Bitmap.createBitmap"))
        assertFalse(canvasDraw.contains("TextView("))
        assertTrue(canvasDraw.contains("cardWidth * zoom < 24.0"))
        assertFalse(canvasDraw.contains("textBlock(value, index)"))
        assertFalse(canvasDraw.contains("StaticLayout.Builder"))
        assertTrue(canvasDraw.contains("value.collectVisibleBranches(visible.first, visible.last)"))
        assertTrue(canvasDraw.contains("val focusedLast = focusedChildRows.getOrNull(row)"))
        assertTrue(canvasDraw.contains("edgeColor(focused && focusMask?.get(value.indexAt(parentRow)) != false)"))
        assertTrue(canvasDraw.contains("text.title.paint.alpha = titleAlpha"))
        assertTrue(canvasDraw.contains("text.body.paint.alpha = bodyAlpha"))
        assertTrue(canvasDraw.contains("text.depth.paint.alpha = depthAlpha"))
        assertFalse(canvasDraw.contains("for (row in 0 until value.size)"))
        assertFalse(canvasDraw.contains("BooleanArray("))

        val warm = canvas.after("private fun warmViewportText").before("private fun pick")
        assertTrue(warm.contains("ReplyTopologyLoadPriority.rows"))
        assertTrue(warm.contains("if (onScreen != (rank == 0)) continue"))
        assertTrue(warm.contains("built >= 4"))
        assertTrue(warm.contains("SystemClock.uptimeMillis() - start >= 2L"))

        val mask = canvas.after("fun setFocus").before("fun fitView")
        assertTrue(mask.contains("focusedChildRows[parent] = row"))
        assertFalse(mask.contains("viewport."))

        val explorer = SourceContract.read("ui/overlay/ReplyTopologyTreeExplorer.kt")
        assertTrue(explorer.contains("tree.release()"))
        assertTrue(explorer.contains("controls.forEach { it.setOnClickListener(null) }"))
        assertTrue(explorer.contains("PredictiveBackApi33.unregister(dispatcher, callback)"))
        assertFalse(explorer.contains("android.window."))
        val graphUpdate = explorer.after("if (graph !== value) {").before("graph = value")
        assertTrue(graphUpdate.contains("pathIndex = ReplyTopologyPath.Index(value)"))
        assertTrue(graphUpdate.contains("layout = ReplyTopologyTreeLayout(value)"))
        assertFalse(explorer.contains("ReplyTopologyPath.resolve(value"))
        assertFalse(explorer.contains("pathIndex?.resolve("))
        assertFalse(explorer.contains("path.indexes"))

        val selection = explorer.after("private val tree = ReplyTopologyTreeCanvas").before("init {")
        assertTrue(selection.contains("selectedRpid = it"))
        assertTrue(selection.contains("pathRpid = it"))
        assertTrue(selection.contains("treeFocusChanged()"))
        assertFalse(selection.contains("rebuild("))
        val focus = explorer.after("private fun treeFocusChanged").before("fun updateState")
        assertTrue(focus.contains("pathIndex?.focus(it)"))
        assertTrue(focus.contains("tree.setFocus(focus)"))
        assertFalse(focus.contains("ReplyTopologyTreeLayout("))
        assertFalse(focus.contains("centerSelected("))
        assertTrue(explorer.contains("pathRpid = null; treeFocusChanged()"))

        val state = explorer.after("fun updateState").before("private fun updateSelection")
        for (field in listOf("state.loadedCount", "state.expectedCount", "state.phase", "state.canRetry", "state.canContinue")) {
            assertTrue(state.contains(field))
        }
        assertTrue(explorer.contains("retry = null; continueLoading = null"))
    }
}
