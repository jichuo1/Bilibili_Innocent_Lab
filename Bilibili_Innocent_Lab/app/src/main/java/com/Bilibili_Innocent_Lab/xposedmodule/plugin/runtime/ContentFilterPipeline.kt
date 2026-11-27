package com.Bilibili_Innocent_Lab.xposedmodule.plugin.runtime

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import com.bilab.plugin.api.spi.CommentView
import com.bilab.plugin.api.spi.ContentFilterExtension
import com.bilab.plugin.api.spi.DanmakuBatchFilter
import com.bilab.plugin.api.spi.DanmakuBatchView
import com.bilab.plugin.api.spi.DynamicItemView
import com.bilab.plugin.api.spi.FeedCardView
import com.bilab.plugin.api.spi.FilterDecision
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thread-safe, host-protected pipeline aggregating active [ContentFilterExtension] and
 * [DanmakuBatchFilter] instances from Tier-B and Tier-C plugins.
 *
 * When zero filters are registered, every evaluation performs only a single volatile reference
 * read and returns immediately with zero heap allocations.
 */
internal class ContentFilterPipeline(
    @Volatile private var faultRecorder: ((pluginId: String, Throwable) -> Unit)? = null
) {
    private class ContentFilterEntry(
        val pluginId: String,
        val extension: ContentFilterExtension,
        val onFault: ((Throwable) -> Unit)?
    )

    private class DanmakuFilterEntry(
        val pluginId: String,
        val filter: DanmakuBatchFilter,
        val onFault: ((Throwable) -> Unit)?
    )

    private val snapshotLock = Any()
    private val contentEntries = CopyOnWriteArrayList<ContentFilterEntry>()
    private val danmakuEntries = CopyOnWriteArrayList<DanmakuFilterEntry>()

    @Volatile
    private var contentSnapshot: Array<ContentFilterEntry> = EMPTY_CONTENT_ENTRIES

    @Volatile
    private var danmakuSnapshot: Array<DanmakuFilterEntry> = EMPTY_DANMAKU_ENTRIES

    @Volatile
    private var danmakuFiltersCache: List<DanmakuBatchFilter> = emptyList()

    fun setFaultRecorder(recorder: ((pluginId: String, Throwable) -> Unit)?) {
        faultRecorder = recorder
    }

    fun registerContentFilter(extension: ContentFilterExtension): AutoCloseable =
        registerContentFilter(ANONYMOUS_PLUGIN_ID, extension, onFault = null)

    fun registerContentFilter(
        pluginId: String,
        extension: ContentFilterExtension,
        onFault: ((Throwable) -> Unit)? = null
    ): AutoCloseable {
        val normalizedId = pluginId.ifBlank { ANONYMOUS_PLUGIN_ID }
        val entry = ContentFilterEntry(normalizedId, extension, onFault)
        synchronized(snapshotLock) {
            contentEntries.add(entry)
            refreshContentSnapshotLocked()
        }
        val closed = AtomicBoolean(false)
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                synchronized(snapshotLock) {
                    if (contentEntries.remove(entry)) {
                        refreshContentSnapshotLocked()
                    }
                }
            }
        }
    }

    fun registerDanmakuBatchFilter(filter: DanmakuBatchFilter): AutoCloseable =
        registerDanmakuBatchFilter(ANONYMOUS_PLUGIN_ID, filter, onFault = null)

    fun registerDanmakuBatchFilter(
        pluginId: String,
        filter: DanmakuBatchFilter,
        onFault: ((Throwable) -> Unit)? = null
    ): AutoCloseable {
        val normalizedId = pluginId.ifBlank { ANONYMOUS_PLUGIN_ID }
        val entry = DanmakuFilterEntry(normalizedId, filter, onFault)
        synchronized(snapshotLock) {
            danmakuEntries.add(entry)
            refreshDanmakuSnapshotLocked()
        }
        val closed = AtomicBoolean(false)
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                synchronized(snapshotLock) {
                    if (danmakuEntries.remove(entry)) {
                        refreshDanmakuSnapshotLocked()
                    }
                }
            }
        }
    }

    fun unregisterPlugin(pluginId: String) {
        if (pluginId.isBlank()) return
        synchronized(snapshotLock) {
            var changedContent = false
            for (entry in contentEntries) {
                if (entry.pluginId == pluginId && contentEntries.remove(entry)) {
                    changedContent = true
                }
            }
            if (changedContent) refreshContentSnapshotLocked()

            var changedDanmaku = false
            for (entry in danmakuEntries) {
                if (entry.pluginId == pluginId && danmakuEntries.remove(entry)) {
                    changedDanmaku = true
                }
            }
            if (changedDanmaku) refreshDanmakuSnapshotLocked()
        }
    }

    fun clear() {
        synchronized(snapshotLock) {
            contentEntries.clear()
            danmakuEntries.clear()
            contentSnapshot = EMPTY_CONTENT_ENTRIES
            danmakuSnapshot = EMPTY_DANMAKU_ENTRIES
            danmakuFiltersCache = emptyList()
        }
    }

    fun hasContentFilters(): Boolean = contentSnapshot.isNotEmpty()

    fun hasDanmakuFilters(): Boolean = danmakuSnapshot.isNotEmpty()

    fun contentFilterCount(): Int = contentSnapshot.size

    fun danmakuFilterCount(): Int = danmakuSnapshot.size

    fun danmakuFiltersSnapshot(): List<DanmakuBatchFilter> = danmakuFiltersCache

    fun shouldDropFeedCard(card: FeedCardView): Boolean {
        val entries = contentSnapshot
        if (entries.isEmpty()) return false
        for (entry in entries) {
            val decision = runCatching {
                entry.extension.filterFeedCard(card)
            }.getOrElse { throwable ->
                recordFault(entry.pluginId, entry.onFault, SURFACE_FEED, throwable)
                FilterDecision.KEEP
            }
            if (decision == FilterDecision.DROP) return true
        }
        return false
    }

    fun shouldDropComment(comment: CommentView): Boolean {
        val entries = contentSnapshot
        if (entries.isEmpty()) return false
        for (entry in entries) {
            val decision = runCatching {
                entry.extension.filterComment(comment)
            }.getOrElse { throwable ->
                recordFault(entry.pluginId, entry.onFault, SURFACE_COMMENT, throwable)
                FilterDecision.KEEP
            }
            if (decision == FilterDecision.DROP) return true
        }
        return false
    }

    fun shouldDropDynamic(item: DynamicItemView): Boolean {
        val entries = contentSnapshot
        if (entries.isEmpty()) return false
        for (entry in entries) {
            val decision = runCatching {
                entry.extension.filterDynamicItem(item)
            }.getOrElse { throwable ->
                recordFault(entry.pluginId, entry.onFault, SURFACE_DYNAMIC, throwable)
                FilterDecision.KEEP
            }
            if (decision == FilterDecision.DROP) return true
        }
        return false
    }

    fun applyDanmakuBatchFilters(
        view: DanmakuBatchView,
        actionsOut: ByteArray,
        colorsOut: IntArray
    ): Boolean {
        val entries = danmakuSnapshot
        if (entries.isEmpty()) return false
        val count = view.count
        if (count <= 0 || actionsOut.size < count || colorsOut.size < count) return false

        for (i in 0 until count) {
            actionsOut[i] = DanmakuBatchFilter.ACTION_KEEP
            colorsOut[i] = 0
        }

        if (entries.size == 1) {
            val entry = entries[0]
            val applied = runCatching {
                entry.filter.filterBatch(view, actionsOut, colorsOut)
            }.getOrElse { throwable ->
                recordFault(entry.pluginId, entry.onFault, SURFACE_DANMAKU, throwable)
                resetOutputs(actionsOut, colorsOut, count)
                return false
            }
            if (!applied) {
                resetOutputs(actionsOut, colorsOut, count)
                return false
            }
            return normalizeAndDetectChange(actionsOut, colorsOut, count)
        }

        val scratchActions = ByteArray(count)
        val scratchColors = IntArray(count)
        var anyModified = false
        for (entry in entries) {
            resetOutputs(scratchActions, scratchColors, count)
            val applied = runCatching {
                entry.filter.filterBatch(view, scratchActions, scratchColors)
            }.getOrElse { throwable ->
                recordFault(entry.pluginId, entry.onFault, SURFACE_DANMAKU, throwable)
                false
            }
            if (!applied) continue
            for (i in 0 until count) {
                when (scratchActions[i]) {
                    DanmakuBatchFilter.ACTION_DROP -> {
                        actionsOut[i] = DanmakuBatchFilter.ACTION_DROP
                        anyModified = true
                    }
                    DanmakuBatchFilter.ACTION_RECOLOR -> {
                        if (actionsOut[i] != DanmakuBatchFilter.ACTION_DROP) {
                            actionsOut[i] = DanmakuBatchFilter.ACTION_RECOLOR
                            colorsOut[i] = scratchColors[i]
                            anyModified = true
                        }
                    }
                }
            }
        }
        return anyModified
    }

    private fun refreshContentSnapshotLocked() {
        contentSnapshot = if (contentEntries.isEmpty()) {
            EMPTY_CONTENT_ENTRIES
        } else {
            contentEntries.toTypedArray()
        }
    }

    private fun refreshDanmakuSnapshotLocked() {
        if (danmakuEntries.isEmpty()) {
            danmakuSnapshot = EMPTY_DANMAKU_ENTRIES
            danmakuFiltersCache = emptyList()
        } else {
            val arr = danmakuEntries.toTypedArray()
            danmakuSnapshot = arr
            danmakuFiltersCache = Collections.unmodifiableList(arr.map { it.filter })
        }
    }

    private fun recordFault(
        pluginId: String,
        perEntryFault: ((Throwable) -> Unit)?,
        surface: String,
        throwable: Throwable
    ) {
        HostThreadGuard.report("plugin.filter.$surface.$pluginId", throwable)
        runCatching { perEntryFault?.invoke(throwable) }
        runCatching { faultRecorder?.invoke(pluginId, throwable) }
    }

    private fun resetOutputs(actionsOut: ByteArray, colorsOut: IntArray, count: Int) {
        for (i in 0 until count) {
            actionsOut[i] = DanmakuBatchFilter.ACTION_KEEP
            colorsOut[i] = 0
        }
    }

    private fun normalizeAndDetectChange(
        actionsOut: ByteArray,
        colorsOut: IntArray,
        count: Int
    ): Boolean {
        var anyModified = false
        for (i in 0 until count) {
            val action = actionsOut[i]
            if (action == DanmakuBatchFilter.ACTION_DROP || action == DanmakuBatchFilter.ACTION_RECOLOR) {
                anyModified = true
            } else if (action != DanmakuBatchFilter.ACTION_KEEP) {
                actionsOut[i] = DanmakuBatchFilter.ACTION_KEEP
                colorsOut[i] = 0
            }
        }
        return anyModified
    }

    private companion object {
        private const val ANONYMOUS_PLUGIN_ID = "anonymous"
        private const val SURFACE_FEED = "feed"
        private const val SURFACE_COMMENT = "comment"
        private const val SURFACE_DYNAMIC = "dynamic"
        private const val SURFACE_DANMAKU = "danmaku"

        private val EMPTY_CONTENT_ENTRIES = emptyArray<ContentFilterEntry>()
        private val EMPTY_DANMAKU_ENTRIES = emptyArray<DanmakuFilterEntry>()
    }
}
