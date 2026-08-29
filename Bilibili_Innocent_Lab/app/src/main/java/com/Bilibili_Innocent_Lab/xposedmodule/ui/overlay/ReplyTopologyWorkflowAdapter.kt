package com.Bilibili_Innocent_Lab.xposedmodule.ui.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology.ReplyTopologyGraph
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology.ReplyTopologyNodeFlags
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 直接读取后台构图器的紧凑数组，不为每个节点再分配 UI DTO。stable id 永远使用 rpid，
 * RecyclerView position 只在当前点击帧读取，绝不向外暴露或缓存。
 */
internal class ReplyTopologyWorkflowAdapter(
    private val theme: ReplyTopologyPanelTheme,
    private val strings: ReplyTopologyPanelStrings,
    onNodeClick: (Long) -> Unit,
    onNodeLongPress: (anchor: View, rpid: Long, text: String) -> Unit = { _, _, _ -> }
) : RecyclerView.Adapter<ReplyTopologyWorkflowAdapter.NodeHolder>() {

    private var graph: ReplyTopologyGraph? = null
    private var selectedRpid: Long? = null
    private var nodeClick: ((Long) -> Unit)? = onNodeClick

    /** 长按节点请求全文查看；anchor 为节点行（气泡定位锚点），text 为该行当前完整文本。 */
    private var nodeLongPress: ((anchor: View, rpid: Long, text: String) -> Unit)? = onNodeLongPress
    private val timeCache = LruCache<Long, String>(96)
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    init {
        setHasStableIds(true)
    }

    override fun getItemCount(): Int = graph?.size ?: 0

    override fun getItemId(position: Int): Long = graph?.rpids?.getOrNull(position) ?: RecyclerView.NO_ID

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NodeHolder {
        val row = ReplyTopologyNodeRow(parent.context, theme, strings)
        // LinearLayoutManager 的默认条目宽度是 WRAP_CONTENT，会让点击判定区只覆盖文字块；
        // 显式占满整行后，左侧轨道区和右侧留白都属于同一热区。
        row.layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        val holder = NodeHolder(row)
        row.setOnClickListener {
            val position = holder.bindingAdapterPosition
            val current = graph
            if (position == RecyclerView.NO_POSITION || current == null || position !in 0 until current.size) {
                return@setOnClickListener
            }
            val rpid = current.rpids[position]
            selectRpid(rpid)
            nodeClick?.invoke(rpid)
        }
        // 长按正文 = 选中该节点并请求全文气泡（选项 2：一次手势完成，不触发定位路由）。
        // 触感反馈先行确认手势，气泡从行下方以既有缩放+淡入动画生长，衔接不突兀。
        row.setOnMessageLongPress { anchor ->
            val current = graph ?: return@setOnMessageLongPress false
            val position = holder.bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION || position !in 0 until current.size) {
                return@setOnMessageLongPress false
            }
            val text = holder.boundMessage ?: return@setOnMessageLongPress false
            val rpid = current.rpids[position]
            selectRpid(rpid)
            anchor.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            nodeLongPress?.invoke(anchor, rpid, text)
            true
        }
        return holder
    }

    override fun onBindViewHolder(holder: NodeHolder, position: Int) {
        bind(holder, position, selectionOnly = false)
    }

    override fun onBindViewHolder(holder: NodeHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.any { it === SELECTION_PAYLOAD }) {
            bind(holder, position, selectionOnly = true)
        } else {
            bind(holder, position, selectionOnly = false)
        }
    }

    override fun onViewRecycled(holder: NodeHolder) {
        holder.boundMessage = null
        holder.row.clearContent()
    }

    fun submit(snapshot: ReplyTopologyRenderSnapshot) {
        val old = graph
        val oldSize = old?.size ?: 0
        val oldSelection = selectedRpid
        val appendOnly = old != null &&
            old.key == snapshot.graph.key &&
            snapshot.graph.size >= oldSize &&
            snapshot.stablePrefixLength >= oldSize &&
            prefixBoundaryMatches(old, snapshot.graph)

        graph = snapshot.graph
        selectedRpid = snapshot.selectedRpid

        if (appendOnly) {
            val inserted = snapshot.graph.size - oldSize
            if (inserted > 0) notifyItemRangeInserted(oldSize, inserted)
            notifySelectionChange(old, oldSelection, snapshot.graph, selectedRpid)
        } else {
            notifyDataSetChanged()
        }
    }

    /** 返回当前图中的 position；仅供立即滚动，不得被调用方保存。 */
    fun selectRpid(rpid: Long): Int {
        val current = graph ?: return RecyclerView.NO_POSITION
        val oldPosition = indexOf(current, selectedRpid)
        val newPosition = indexOf(current, rpid)
        if (newPosition == RecyclerView.NO_POSITION) return RecyclerView.NO_POSITION
        if (selectedRpid == rpid) return newPosition
        selectedRpid = rpid
        if (oldPosition != RecyclerView.NO_POSITION) notifyItemChanged(oldPosition, SELECTION_PAYLOAD)
        notifyItemChanged(newPosition, SELECTION_PAYLOAD)
        return newPosition
    }

    fun currentGraph(): ReplyTopologyGraph? = graph

    fun isSelected(position: Int): Boolean {
        val current = graph ?: return false
        return current.rpids.getOrNull(position) == selectedRpid
    }

    fun release() {
        nodeClick = null
        nodeLongPress = null
        selectedRpid = null
        graph = null
        timeCache.evictAll()
    }

    private fun bind(holder: NodeHolder, position: Int, selectionOnly: Boolean) {
        val current = graph ?: return
        if (position !in 0 until current.size) return
        val selected = current.rpids[position] == selectedRpid
        if (selectionOnly) {
            holder.row.setSelectedState(selected)
            return
        }

        val flags = current.flags[position]
        val placeholder = ReplyTopologyNodeFlags.has(flags, ReplyTopologyNodeFlags.PLACEHOLDER)
        val filtered = ReplyTopologyNodeFlags.has(flags, ReplyTopologyNodeFlags.FILTERED)
        val unavailable = ReplyTopologyNodeFlags.has(flags, ReplyTopologyNodeFlags.UNAVAILABLE)
        val root = ReplyTopologyNodeFlags.has(flags, ReplyTopologyNodeFlags.ROOT)
        val author = current.authorNames[position].ifBlank {
            when {
                filtered -> strings.filteredAuthor
                placeholder || unavailable -> strings.unavailableAuthor
                else -> strings.unknownAuthor
            }
        }
        val repliedAuthor = current.repliedAuthorNames[position]
        val title = if (!repliedAuthor.isNullOrBlank() && !root) "$author  →  $repliedAuthor" else author
        val message = current.messagePreviews[position].ifBlank {
            when {
                filtered -> strings.filteredMessage
                placeholder || unavailable -> strings.unavailableMessage
                else -> strings.emptyMessage
            }
        }
        val meta = buildMeta(current, position, flags)
        // 占位/被过滤/不可见节点展示的是提示文案而非评论文本，不提供全文查看入口。
        holder.boundMessage = if (placeholder || filtered || unavailable) null else message
        holder.row.bind(
            title = title,
            message = message,
            meta = meta,
            selected = selected,
            root = root,
            placeholder = placeholder || unavailable || filtered
        )
    }

    private fun buildMeta(graph: ReplyTopologyGraph, position: Int, flags: Int): String {
        val builder = StringBuilder(32)
        formatTime(graph.ctimes[position])?.let(builder::append)
        val children = graph.childCounts[position]
        if (children > 0) {
            if (builder.isNotEmpty()) builder.append("  ·  ")
            builder.append(strings.branchCount(children))
        }
        val anomaly = when {
            ReplyTopologyNodeFlags.has(flags, ReplyTopologyNodeFlags.CYCLE) -> strings.cycleMessage
            ReplyTopologyNodeFlags.has(flags, ReplyTopologyNodeFlags.SELF_PARENT) -> strings.selfParentMessage
            ReplyTopologyNodeFlags.has(flags, ReplyTopologyNodeFlags.MISSING_PARENT) -> strings.missingParentMessage
            ReplyTopologyNodeFlags.has(flags, ReplyTopologyNodeFlags.DUPLICATE_CONFLICT) -> strings.duplicateConflictMessage
            else -> null
        }
        if (anomaly != null) {
            if (builder.isNotEmpty()) builder.append("  ·  ")
            builder.append(anomaly)
        }
        return builder.toString()
    }

    private fun formatTime(ctimeSeconds: Long): String? {
        if (ctimeSeconds <= 0L || ctimeSeconds > Long.MAX_VALUE / 1000L) return null
        timeCache.get(ctimeSeconds)?.let { return it }
        return runCatching { timeFormat.format(Date(ctimeSeconds * 1000L)) }
            .getOrNull()
            ?.also { timeCache.put(ctimeSeconds, it) }
    }

    private fun prefixBoundaryMatches(old: ReplyTopologyGraph, new: ReplyTopologyGraph): Boolean {
        if (old.size == 0) return true
        return old.rpids[0] == new.rpids[0] && old.rpids[old.size - 1] == new.rpids[old.size - 1]
    }

    private fun notifySelectionChange(
        oldGraph: ReplyTopologyGraph?,
        oldRpid: Long?,
        newGraph: ReplyTopologyGraph,
        newRpid: Long?
    ) {
        if (oldRpid == newRpid) return
        val oldPosition = oldGraph?.let { indexOf(it, oldRpid) } ?: RecyclerView.NO_POSITION
        val newPosition = indexOf(newGraph, newRpid)
        if (oldPosition != RecyclerView.NO_POSITION && oldPosition < newGraph.size) {
            notifyItemChanged(oldPosition, SELECTION_PAYLOAD)
        }
        if (newPosition != RecyclerView.NO_POSITION) notifyItemChanged(newPosition, SELECTION_PAYLOAD)
    }

    private fun indexOf(graph: ReplyTopologyGraph, rpid: Long?): Int {
        if (rpid == null) return RecyclerView.NO_POSITION
        for (index in graph.rpids.indices) {
            if (graph.rpids[index] == rpid) return index
        }
        return RecyclerView.NO_POSITION
    }

    internal class NodeHolder(val row: ReplyTopologyNodeRow) : RecyclerView.ViewHolder(row) {

        /** 当前行绑定的完整评论文本；占位/被过滤/不可见节点为 null（无全文可看）。 */
        var boundMessage: String? = null
    }

    private companion object {
        val SELECTION_PAYLOAD = Any()
    }
}

/** 单个虚拟化节点行：不加载头像、图片、富文本、Span 或 Emoji Bitmap。 */
internal class ReplyTopologyNodeRow(
    context: Context,
    private val theme: ReplyTopologyPanelTheme,
    private val strings: ReplyTopologyPanelStrings
) : LinearLayout(context) {

    private val density = resources.displayMetrics.density
    private val authorView = TextView(context)
    private val messageView = TextView(context)
    private val metaView = TextView(context)

    init {
        orientation = VERTICAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        minimumHeight = dp(66)
        setPadding(dp(TRACK_AREA_DP), dp(7), dp(12), dp(7))
        isClickable = true
        isFocusable = true
        foreground = RippleDrawable(ColorStateList.valueOf(theme.rippleColor), null, null)

        authorView.apply {
            textSize = 13f
            setTextColor(theme.authorTextColor)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        messageView.apply {
            textSize = 13f
            setTextColor(theme.primaryTextColor)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        metaView.apply {
            textSize = 11f
            setTextColor(theme.secondaryTextColor)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        addView(authorView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(messageView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(metaView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun bind(
        title: String,
        message: String,
        meta: String,
        selected: Boolean,
        root: Boolean,
        placeholder: Boolean
    ) {
        authorView.text = if (root) "${strings.rootPrefix} · $title" else title
        // 作者名相对正文弱化一档（root 作者名保留主题强调色作为主评论锚点）。
        authorView.setTextColor(if (root) theme.accentColor else theme.authorTextColor)
        messageView.text = message
        messageView.alpha = if (placeholder) 0.72f else 1f
        metaView.text = meta
        metaView.visibility = if (meta.isEmpty()) View.GONE else View.VISIBLE
        setSelectedState(selected)
        contentDescription = buildString {
            append(authorView.text).append(strings.descriptionSeparator).append(message)
            if (meta.isNotEmpty()) append(strings.descriptionSeparator).append(meta)
        }
    }

    fun setSelectedState(selected: Boolean) {
        isActivated = selected
        setBackgroundColor(if (selected) theme.selectedColor else Color.TRANSPARENT)
    }

    /** 长按正文请求全文查看；作者名/元信息行不触发，避免误触。 */
    fun setOnMessageLongPress(listener: ((anchor: View) -> Boolean)?) {
        messageView.setOnLongClickListener(
            listener?.let { block -> View.OnLongClickListener { view -> block(view) } }
        )
    }

    fun clearContent() {
        authorView.text = null
        messageView.text = null
        metaView.text = null
        contentDescription = null
        setSelectedState(false)
    }

    private fun dp(value: Int): Int = (value * density).roundToInt()

    private companion object {
        const val TRACK_AREA_DP = 78
    }
}

/**
 * 轻量工作流轨道：只遍历当前可见 child，不创建 Edge/View/Path；深度过大时折叠到末端轨道。
 */
internal class ReplyTopologyTrackDecoration(
    private val adapter: ReplyTopologyWorkflowAdapter,
    private val theme: ReplyTopologyPanelTheme,
    private val density: Float
) : RecyclerView.ItemDecoration() {

    private val trackLeft = 14f * density
    private val laneSpacing = 8f * density
    private val maxVisibleLane = 7
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.trackColor
        style = Paint.Style.STROKE
        strokeWidth = (1.25f * density).coerceAtLeast(1f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.accentColor
        style = Paint.Style.STROKE
        strokeWidth = (1.5f * density).coerceAtLeast(1f)
    }
    private val branchPath = Path()
    private val nodeRadius = 3.5f * density

    override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val graph = adapter.currentGraph() ?: return
        for (childIndex in 0 until parent.childCount) {
            val child = parent.getChildAt(childIndex)
            val position = parent.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION || position !in 0 until graph.size) continue

            val top = child.top + child.translationY
            val bottom = child.bottom + child.translationY
            val centerY = (top + bottom) * 0.5f
            val rawDepth = graph.depths[position].coerceAtLeast(0)
            val depth = rawDepth.coerceAtMost(maxVisibleLane)
            val nodeX = laneX(depth)

            // 当前行所属的每级祖先轨道贯穿整行；最多绘制 7 条，极深恶意数据不会放大开销。
            for (lane in 0 until depth) {
                val x = laneX(lane)
                canvas.drawLine(x, top, x, bottom, linePaint)
            }

            val parentIndex = graph.parentIndexes[position]
            if (parentIndex in 0 until graph.size) {
                val parentDepth = graph.depths[parentIndex].coerceIn(0, maxVisibleLane)
                val parentX = laneX(parentDepth)
                branchPath.reset()
                branchPath.moveTo(parentX, top)
                branchPath.cubicTo(parentX, centerY, nodeX, top, nodeX, centerY)
                canvas.drawPath(branchPath, linePaint)
            }
            if (graph.childCounts[position] > 0) {
                canvas.drawLine(nodeX, centerY, nodeX, bottom, linePaint)
            }

            val flags = graph.flags[position]
            val root = ReplyTopologyNodeFlags.has(flags, ReplyTopologyNodeFlags.ROOT)
            val placeholder = ReplyTopologyNodeFlags.has(flags, ReplyTopologyNodeFlags.PLACEHOLDER) ||
                ReplyTopologyNodeFlags.has(flags, ReplyTopologyNodeFlags.UNAVAILABLE) ||
                ReplyTopologyNodeFlags.has(flags, ReplyTopologyNodeFlags.FILTERED)
            nodePaint.color = if (root) theme.accentColor else theme.primaryTextColor
            nodePaint.style = if (placeholder) Paint.Style.STROKE else Paint.Style.FILL
            nodePaint.strokeWidth = linePaint.strokeWidth
            canvas.drawCircle(nodeX, centerY, if (root) nodeRadius * 1.2f else nodeRadius, nodePaint)
            if (adapter.isSelected(position)) {
                canvas.drawCircle(nodeX, centerY, nodeRadius + 3.5f * density, ringPaint)
            }
        }
    }

    private fun laneX(lane: Int): Float = trackLeft + lane.coerceIn(0, maxVisibleLane) * laneSpacing
}
