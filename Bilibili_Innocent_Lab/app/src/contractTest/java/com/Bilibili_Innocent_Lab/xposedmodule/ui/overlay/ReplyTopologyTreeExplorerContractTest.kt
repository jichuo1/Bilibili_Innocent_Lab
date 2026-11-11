package com.Bilibili_Innocent_Lab.xposedmodule.ui.overlay

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.*
import org.junit.Test

class ReplyTopologyTreeExplorerContractTest {
    @Test fun rootPackageComposeMenuItemRequiresTheVerifiedRendererSignature() {
        val menu = SourceContract.read("hook/feature/ReplyTopologyComposeMenu.kt")
            .after("fun install(environment: HookEnvironment)")
            .before("private fun installLegacy(")
        val item = menu.after("val item =").before("val fields =")
        assertTrue(item.contains("row.name == \"d\" && method.name == \"b\" && method.isStatic"))
        assertTrue(item.contains("listOf(\"v0f1\", \"kotlin.jvm.functions.Function0\""))
        assertTrue(item.contains("\"androidx.compose.ui.Modifier\", \"androidx.compose.runtime.Composer\", \"int\""))
        assertTrue(item.contains(".distinct().singleOrNull()"))
        assertTrue(menu.contains("it.parameterTypes.contentEquals(fields.map"))
        assertTrue(menu.contains("source.any { !item.isInstance(it) }"))
    }

    @Test fun compactRowsAndTracksShareOneBudgetAndNeverPaintOverText() {
        val adapter = SourceContract.read("ui/overlay/ReplyTopologyWorkflowAdapter.kt")
        assertTrue(adapter.contains("ReplyTopologyTrackLayout.resolve(w.toFloat(), density, resources.configuration.fontScale)"))
        assertTrue(adapter.contains("setPadding(layout.trackWidth.roundToInt()"))
        val draw = adapter.after("override fun onDrawOver").before("private fun laneX")
        assertTrue(draw.contains("canvas.clipRect(0f, 0f, layout.trackWidth"))
        assertTrue(draw.contains("canvas.restoreToCount(saved)"))
        assertTrue(draw.contains("trackLayout.width != parent.width.toFloat()"))
        assertTrue(adapter.contains("strings.depthLabel(trueDepth)"))
    }

    @Test fun fullTreeIsAvailableForEveryNonemptyGraphAndPathHasItsOwnAction() {
        val panel = SourceContract.read("ui/overlay/ReplyTopologyPanelView.kt")
        assertTrue(panel.contains("workflowAdapter.hasCompressedDepth(layout.maxLane)"))
        assertTrue(panel.contains("fullTreeAvailable = (workflowAdapter.currentGraph()?.size ?: 0) > 0"))
        assertFalse(panel.contains("deepTreeAvailable"))
        assertTrue(panel.contains("showTreeExplorer(rpid, path = true)"))
        assertTrue(panel.contains("showTreeExplorer(workflowAdapter.selectedNode(), path = false)"))
        val adapter = SourceContract.read("ui/overlay/ReplyTopologyWorkflowAdapter.kt")
        assertTrue(adapter.contains("row.setOnPathRequested"))
        assertTrue(adapter.contains("row.setOnClickListener"))
        assertTrue(adapter.contains("row.setOnMessageLongPress"))
        assertTrue(adapter.contains("text = strings.viewPath"))
        assertTrue(panel.contains("treeExplorer?.submit(snapshot.graph"))
    }

    @Test fun fullTreeUsesVirtualCanvasAndOriginalGraphDepthsWithoutMassiveViews() {
        val layout = SourceContract.read("runtime/replytopology/ReplyTopologyTreeLayout.kt")
        assertTrue(layout.contains("graph.depths[index] - baseDepth"))
        assertFalse(layout.contains("coerceAtMost(7)"))
        val canvas = SourceContract.read("ui/overlay/ReplyTopologyTreeCanvas.kt")
        assertTrue(canvas.contains("private val viewport = ReplyTopologyTreeViewport()"))
        assertTrue(canvas.contains("value.visibleRows("))
        assertTrue(canvas.contains("LruCache<Long, TextBlock>(96)"))
        val draw = canvas.after("private fun drawScene").before("private fun textBlock")
        assertFalse(draw.contains("Bitmap.createBitmap"))
        assertFalse(draw.contains("TextView("))
        assertTrue(draw.contains("cardWidth * zoom < 24.0"))
        assertTrue(canvas.contains("ScaleGestureDetector"))
        assertTrue(canvas.contains("viewport.pan(-distanceX.toDouble(), -distanceY.toDouble())"))
    }

    @Test fun callbacksAndGestureQueuesAreReleasedWhenTheWindowCloses() {
        val panel = SourceContract.read("ui/overlay/ReplyTopologyPanelView.kt")
        assertTrue(panel.after("fun playExit").before("fun submit").contains("closeTreeExplorer()"))
        assertTrue(panel.after("fun releaseResources").before("override fun onDetachedFromWindow").contains("closeTreeExplorer()"))
        val explorer = SourceContract.read("ui/overlay/ReplyTopologyTreeExplorer.kt")
        assertTrue(explorer.contains("tree.release()"))
        assertTrue(explorer.contains("controls.forEach { it.setOnClickListener(null) }"))
        assertTrue(explorer.contains("PredictiveBackApi33.unregister(dispatcher, callback)"))
        assertFalse(explorer.contains("android.window."))
        val canvas = SourceContract.read("ui/overlay/ReplyTopologyTreeCanvas.kt")
        assertTrue(canvas.contains("removeCallbacks(flingFrame)"))
        assertTrue(canvas.contains("MotionEvent.ACTION_CANCEL"))
        assertTrue(canvas.contains("textCache.evictAll()"))
        assertTrue(canvas.contains("HostThreadGuard.runnable(\"reply_topology.tree_fling\")"))
        assertTrue(canvas.contains("removeCallbacks(textWarmup)"))
        assertTrue(canvas.contains("HostThreadGuard.runnable(\"reply_topology.tree_text_warmup\")"))
        assertTrue(canvas.contains("HostThreadGuard.call(\"reply_topology.tree_accessibility\", false)"))
    }

    @Test fun visibleRowsAndNearbyRowsArePreparedWithoutDoingTextLayoutInDraw() {
        val panel = SourceContract.read("ui/overlay/ReplyTopologyPanelView.kt")
        assertTrue(panel.contains("initialPrefetchItemCount = 8"))
        assertTrue(panel.contains("for (step in 1..4)"))
        assertTrue(panel.contains("setItemViewCacheSize(8)"))
        val adapter = SourceContract.read("ui/overlay/ReplyTopologyWorkflowAdapter.kt")
        assertTrue(adapter.after("fun bind(\n").before("fun setOnPathRequested").contains("pathView.visibility = if (root) View.GONE else View.VISIBLE"))
        val measure = adapter.after("override fun onMeasure").before("private fun updateTrackLayout")
        assertTrue(measure.before("super.onMeasure").contains("updateTrackLayout(MeasureSpec.getSize(widthMeasureSpec))"))
        assertFalse(adapter.after("internal class ReplyTopologyNodeRow").before("internal class ReplyTopologyTrackDecoration")
            .contains("override fun onSizeChanged"))
        val canvas = SourceContract.read("ui/overlay/ReplyTopologyTreeCanvas.kt")
        val draw = canvas.after("private fun drawScene").before("private fun textBlock")
        assertFalse(draw.contains("textBlock(value, index)"))
        assertFalse(draw.contains("StaticLayout.Builder"))
        assertTrue(draw.contains("value.collectVisibleBranches(visible.first, visible.last)"))
        val warm = canvas.after("private fun warmViewportText").before("private fun pick")
        assertTrue(warm.contains("ReplyTopologyLoadPriority.rows"))
        assertTrue(warm.contains("if (onScreen != (rank == 0)) continue"))
        assertTrue(warm.contains("built >= 4"))
        assertTrue(warm.contains("SystemClock.uptimeMillis() - start >= 2L"))
        assertTrue(canvas.contains("it.matches(value.graph, index)"))
        val explorer = SourceContract.read("ui/overlay/ReplyTopologyTreeExplorer.kt")
        val graphUpdate = explorer.after("if (graph !== value) {").before("graph = value")
        assertTrue(graphUpdate.contains("pathIndex = ReplyTopologyPath.Index(value)"))
        assertTrue(graphUpdate.contains("layout = ReplyTopologyTreeLayout(value)"))
        assertFalse(explorer.contains("ReplyTopologyPath.resolve(value"))
        assertTrue(panel.contains("viewer.submit(graph, rpid, initialPath = rpid.takeIf { path })"))
    }

    @Test fun focusChangesKeepTheFullLayoutAndViewportAndRestoreCachedTextAlpha() {
        val explorer = SourceContract.read("ui/overlay/ReplyTopologyTreeExplorer.kt")
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
        val canvas = SourceContract.read("ui/overlay/ReplyTopologyTreeCanvas.kt")
        val mask = canvas.after("fun setFocus").before("fun fitView")
        assertTrue(mask.contains("focusedChildRows[parent] = row"))
        assertFalse(mask.contains("viewport."))
        val draw = canvas.after("private fun drawScene").before("private fun textBlock")
        assertTrue(draw.contains("val focusedLast = focusedChildRows.getOrNull(row)"))
        assertTrue(draw.contains("edgeColor(focused && focusMask?.get(value.indexAt(parentRow)) != false)"))
        assertTrue(draw.contains("text.title.paint.alpha = titleAlpha"))
        assertTrue(draw.contains("text.body.paint.alpha = bodyAlpha"))
        assertTrue(draw.contains("text.depth.paint.alpha = depthAlpha"))
        assertFalse(draw.contains("for (row in 0 until value.size)"))
        assertFalse(draw.contains("BooleanArray("))
        assertFalse(draw.contains("StaticLayout.Builder"))
    }

    @Test fun openingEitherTreeRequestsAllRepliesAndDisplaysCurrentStateAndControls() {
        val panel = SourceContract.read("ui/overlay/ReplyTopologyPanelView.kt")
        assertTrue(panel.after("fun updateState").before("fun setBackgroundOpacity").contains("treeExplorer?.updateState(state)"))
        val show = panel.after("private fun showTreeExplorer").before("private fun closeTreeExplorer")
        assertTrue(show.before("viewer.submit(graph").contains("viewer.updateState(currentState)"))
        assertTrue(show.after("viewer.submit(graph").contains("panelListener?.onFullTreeRequested()"))
        assertTrue(show.contains("onRetry = { panelListener?.onRetryRequested() }"))
        assertTrue(show.contains("onContinue = { panelListener?.onContinueRequested() }"))
        val explorer = SourceContract.read("ui/overlay/ReplyTopologyTreeExplorer.kt")
        val state = explorer.after("fun updateState").before("private fun updateSelection")
        for (field in listOf("state.loadedCount", "state.expectedCount", "state.phase", "state.canRetry", "state.canContinue")) {
            assertTrue(state.contains(field))
        }
        assertTrue(explorer.contains("retry = null; continueLoading = null"))
    }

    @Test fun resizingRecomputesSizeBeforePositionAndFullViewUsesTheSourceWindow() {
        val panel = SourceContract.read("ui/overlay/ReplyTopologyPanelView.kt")
        val listener = panel.after("private val parentLayoutListener").before("init {")
        assertTrue(listener.contains("updateDimensionsToParent()"))
        val resize = panel.after("private fun updateDimensionsToParent").before("private fun createKeywordRow")
        assertTrue(resize.contains("params.width = newWidth"))
        assertTrue(resize.contains("expandedHeightPx = newHeight"))
        assertTrue(resize.contains("compactAnimator = null"))
        val show = panel.after("private fun showTreeExplorer").before("private fun closeTreeExplorer")
        assertTrue(show.contains("it === this.parent && it.windowToken == windowToken"))
        assertTrue(show.contains("parent.addView(viewer, params)"))
        assertFalse(show.contains("SYSTEM_ALERT_WINDOW"))
    }
}
