package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.lumen.coacervation.engine.motion.LumenAnimator

import android.app.Dialog
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.view.HapticFeedbackConstants
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.Bilibili_Innocent_Lab.xposedmodule.R
import io.github.jichuo1.meguviz.android.extensions.textColor
import io.github.jichuo1.meguviz.android.extensions.textToString
import kotlin.math.hypot

/** 拖拽拾起/放下的缓出曲线：快起缓落，落位末尾是减速而不是匀速急停。 */
private val SETTLE_EASE = PathInterpolator(0.2f, 0f, 0f, 1f)

/** 拖拽落位滑行：最短 220ms，随距离每像素 +0.16ms，封顶 420ms。 */
private const val DROP_SETTLE_MIN_MS = 220L
private const val DROP_SETTLE_PER_PX_MS = 0.16f
private const val DROP_SETTLE_MAX_MS = 420L

/** Edits arrangement only. Entries come from pure metadata even before their pages are created. */
internal fun MainActivity.showSettingsFavoritesDialog(anchor: View? = null) {
    val home = settingsHome ?: return
    if (home.editing) return
    val dialog = Dialog(this).also { installDialogElasticInteraction(it) }
    val container = createModalContainer()
    val density = resources.displayMetrics.density
    fun dp(value: Int) = (density * value).toInt()
    container.addView(TextView(this).apply {
        text = getString(R.string.settings_favorites_manage)
        textSize = 17f
        textColor = getColor(R.color.colorTextDark)
    }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    val search = EditText(this).apply {
        hint = getString(R.string.settings_favorites_search)
        textSize = 14f
        setSingleLine(true)
        textColor = getColor(R.color.colorTextDark)
        // 与"搜索设置"气泡同一套内边距——玻璃描边贴边的提示文字会显得贴框。
        setPadding(dp(14), dp(11), dp(14), dp(11))
    }
    container.addView(search, LinearLayout.LayoutParams(-1, -2))
    lateinit var render: () -> Unit
    // 仓库写入是异步串行的：展示层先按乐观模型即时更新（移动动画立刻开始、可被下一次
    // 操作打断重定向），操作排队逐条落库。若编辑仍在进行，控件保持可用，连点不会丢。
    // 任一条落库失败则回到权威状态——DiffUtil 会把回滚同样走成动画。
    var displayIds: List<String>? = null
    val pendingOps = ArrayDeque<SettingsFavoritesRepository.Edit>()
    var draining = false
    fun drainOps() {
        if (draining || pendingOps.isEmpty()) return
        // 与 home.edit 的静默丢弃条件一致，先拦在外面。若挡住我们的是弹窗外发起的
        // 在途编辑（例如主页开关），它没有义务回调本队列——挂一个延迟重试自解。
        // disposed 也必须挡：edit 对已销毁的 presenter 直接 return 不回调，
        // draining 会永远停在 true，pendingOps 只增不减，之后所有勾选都只活在本地。
        if (home.isDisposed) return
        if (home.editing || home.state?.canWrite != true) {
            container.postDelayed({
                if (dialog.isShowing) drainOps()
            }, 120)
            return
        }
        val op = pendingOps.removeFirst()
        draining = true
        home.edit(op) { success ->
            draining = false
            if (!success || pendingOps.isEmpty()) {
                if (!success) pendingOps.clear()
                displayIds = null
            }
            if (dialog.isShowing && !isFinishing && !isDestroyed) render()
            drainOps()
        }
    }
    fun submit(operation: SettingsFavoritesRepository.Edit) {
        val snapshot = home.state
        if (snapshot?.canWrite != true) {
            render()
            return
        }
        val base = displayIds ?: snapshot.ids
        val next = when (operation) {
            is SettingsFavoritesRepository.Edit.Add ->
                if (operation.id in base) base
                else if (base.size >= SettingsFavoritesPolicy.MAX_ITEMS) null
                else base + operation.id
            is SettingsFavoritesRepository.Edit.Remove -> base.filterNot { it == operation.id }
            is SettingsFavoritesRepository.Edit.Move ->
                if (operation.id !in base || operation.toIndex !in base.indices) null
                else base.toMutableList().apply {
                    remove(operation.id)
                    add(operation.toIndex, operation.id)
                }
        }
        if (next == null) {
            render()
            return
        }
        displayIds = next
        pendingOps.addLast(operation)
        drainOps()
        render()
    }
    // 勾选移动与排序动画由 RecyclerView 的 DefaultItemAnimator 承担（原生支持打断重定向）。
    // 排序改成长按拖拽：ItemTouchHelper 驱动被拖行之外的条目逐级补位，抬起时只落库一次
    // Move；搜索过滤下语义不直观，直接禁用拖拽。
    var dragging = false
    var draggedId: String? = null
    val adapter = FavoritesRowAdapter(
        host = this,
        onToggle = { id, checked ->
            submit(if (checked) SettingsFavoritesRepository.Edit.Add(id)
                else SettingsFavoritesRepository.Edit.Remove(id))
        },
        styleRow = { styleHomeControls(it) }
    )
    val list = RecyclerView(this).apply {
        layoutManager = LinearLayoutManager(context)
        this.adapter = adapter
        // 关掉默认的 change 交叉淡化：行的局部状态（对勾、把手显隐）由绑定路径自己渐变，
        // 不整行闪一下；移动动画不受影响。
        (itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        (itemAnimator as? DefaultItemAnimator)?.moveDuration = 240
    }
    ItemTouchHelper(object : ItemTouchHelper.Callback() {
        override fun getMovementFlags(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder
        ): Int {
            val item = adapter.itemAt(viewHolder.bindingAdapterPosition)
            val flags = if ((item as? FavoriteRowItem.Row)?.canDrag == true) {
                ItemTouchHelper.UP or ItemTouchHelper.DOWN
            } else 0
            return makeMovementFlags(flags, 0)
        }

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from < 0 || to < 0) return false
            // 已选块是 ordered 的前缀，拖出它语义上是"取消勾选"而非排序——拒绝交换。
            if (to >= adapter.selectedCount()) return false
            adapter.swapRows(from, to)
            // 乐观模型与视图顺序同步：拖动中途到来的写入回调不会把顺序弹回去。
            displayIds = adapter.currentSelectedIds()
            return true
        }

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                dragging = true
                draggedId = (adapter.itemAt(viewHolder.bindingAdapterPosition)
                    as? FavoriteRowItem.Row)?.id
                viewHolder.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                LumenAnimator.property(viewHolder.itemView)
                    .scaleX(1.03f).scaleY(1.03f).alpha(0.9f)
                    .setInterpolator(SETTLE_EASE)
                    .setDuration(140).start()
            }
        }

        /**
         * 松手回位滑行时长随距离走：默认实现只返回 itemAnimator 的固定 moveDuration，
         * 长拖后的落位在同样的时长里"撞回去"显得生硬；给长距离留足缓冲、短距离不拖沓。
         */
        override fun getAnimationDuration(
            recyclerView: RecyclerView,
            animationType: Int,
            animateDx: Float,
            animateDy: Float
        ): Long {
            if (animationType != ItemTouchHelper.ANIMATION_TYPE_DRAG) {
                return super.getAnimationDuration(recyclerView, animationType, animateDx, animateDy)
            }
            val distance = hypot(animateDx, animateDy)
            return (DROP_SETTLE_MIN_MS +
                (distance * DROP_SETTLE_PER_PX_MS).toLong())
                .coerceAtMost(DROP_SETTLE_MAX_MS)
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            LumenAnimator.property(viewHolder.itemView)
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setInterpolator(SETTLE_EASE)
                .setDuration(220).start()
            dragging = false
            val id = draggedId
            draggedId = null
            val index = id?.let { displayIds?.indexOf(it) } ?: -1
            if (id != null && index >= 0) {
                submit(SettingsFavoritesRepository.Edit.Move(id, index))
            } else {
                render()
            }
        }

        override fun isLongPressDragEnabled() = true
        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
    }).attachToRecyclerView(list)
    container.addView(list, LinearLayout.LayoutParams(-1,
        (resources.displayMetrics.heightPixels * 0.42f).toInt()))
    render = {
        val snapshot = home.state
        // 有乐观模型时以它为准；仓库权威状态追上（队列清空）后自动回落。
        val ids = displayIds ?: snapshot?.ids.orEmpty()
        val query = search.textToString().trim()
        val ordered = ids + home.entries.map { it.id }.filterNot { it in ids }
        // 写入期间不锁控件：点击进入队列，列表按乐观模型即时重排。
        val canWrite = snapshot?.canWrite == true
        // 过滤视图的可见顺序与存储顺序不再同构，此时不提供拖拽排序。
        val searchActive = query.isNotEmpty()
        val items = ordered.mapNotNull { id ->
            val entry = home.entries.singleOrNull { it.id == id }
            val title = entry?.title ?: getString(R.string.settings_favorites_missing, id)
            if (searchActive && !title.contains(query, ignoreCase = true)) {
                return@mapNotNull null
            }
            val selected = id in ids
            FavoriteRowItem.Row(
                id = id,
                title = title,
                selected = selected,
                canToggle = canWrite &&
                    (selected || ids.size < SettingsFavoritesPolicy.MAX_ITEMS),
                canDrag = canWrite && selected && !searchActive
            )
        }
        // 拖动中途不改数据：ItemTouchHelper 正持有被拖行的索引，外部提交会把顺序弹回。
        // 拖拽路径自己维护 displayIds 与视图顺序，松手后经 submit(Move) 回到正常管线。
        if (!dragging) {
            adapter.submit(if (items.isEmpty()) listOf(FavoriteRowItem.Empty(getString(
                if (snapshot?.canWrite == true) R.string.settings_favorites_no_matches
                else R.string.settings_favorites_unavailable))) else items)
        }
    }
    search.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render() }
        override fun afterTextChanged(s: Editable?) = Unit
    })
    render()
    container.addView(createPanelCloseButton { dismissWithAnimation(dialog, container) {} },
        LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
    // 弹窗必须给显式宽度：WRAP_CONTENT 容器会让列表和行全部拿到 AT_MOST 规格，
    // 每行收缩成自己的内容宽，把手跟随文字长度漂移（真机实证四行宽 992/840/802/728）。
    // 显式宽度使容器走 EXACTLY，行满宽、把手统一钉右缘（UpdateDialogs 同公式）。
    val width = minOf(dp(440),
        resources.displayMetrics.widthPixels - dp(64)).coerceAtLeast(1)
    presentSizedModalDialog(dialog, container, width, anchor)
}

private sealed interface FavoriteRowItem {
    data class Row(
        val id: String,
        val title: String,
        val selected: Boolean,
        val canToggle: Boolean,
        val canDrag: Boolean
    ) : FavoriteRowItem

    data class Empty(val text: String) : FavoriteRowItem
}

private class FavoritesRowDiff(
    private val oldItems: List<FavoriteRowItem>,
    private val newItems: List<FavoriteRowItem>
) : DiffUtil.Callback() {
    override fun getOldListSize() = oldItems.size
    override fun getNewListSize() = newItems.size
    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        val oldItem = oldItems[oldItemPosition]
        val newItem = newItems[newItemPosition]
        return when {
            oldItem is FavoriteRowItem.Row && newItem is FavoriteRowItem.Row ->
                oldItem.id == newItem.id
            oldItem is FavoriteRowItem.Empty && newItem is FavoriteRowItem.Empty -> true
            else -> false
        }
    }
    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
        oldItems[oldItemPosition] == newItems[newItemPosition]
    /** 非空 payload 让绑定路径自己渐变（把手显隐等），而不是整行重建。 */
    override fun getChangePayload(oldItemPosition: Int, newItemPosition: Int): Any = Unit
}

private class FavoritesRowAdapter(
    private val host: MainActivity,
    private val onToggle: (String, Boolean) -> Unit,
    private val styleRow: (View) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var items = listOf<FavoriteRowItem>()

    fun submit(newItems: List<FavoriteRowItem>) {
        val oldItems = items
        items = newItems
        DiffUtil.calculateDiff(FavoritesRowDiff(oldItems, newItems), true)
            .dispatchUpdatesTo(this)
    }

    fun itemAt(position: Int): FavoriteRowItem? = items.getOrNull(position)

    fun selectedCount() = items.count { it is FavoriteRowItem.Row && it.selected }

    /** 拖动中途的视图内交换：只动 items 与通知，不碰仓库；顺序由 clearView 后的 Move 落库。 */
    fun swapRows(from: Int, to: Int) {
        val mutable = items.toMutableList()
        mutable.add(to, mutable.removeAt(from))
        items = mutable
        notifyItemMoved(from, to)
    }

    fun currentSelectedIds(): List<String> =
        items.mapNotNull { (it as? FavoriteRowItem.Row)?.takeIf { row -> row.selected }?.id }

    override fun getItemCount() = items.size
    override fun getItemViewType(position: Int) = when (items[position]) {
        is FavoriteRowItem.Row -> TYPE_ROW
        is FavoriteRowItem.Empty -> TYPE_EMPTY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val density = host.resources.displayMetrics.density
        fun dp(value: Int) = (density * value).toInt()
        if (viewType == TYPE_EMPTY) {
            return object : RecyclerView.ViewHolder(TextView(host).apply {
                layoutParams = RecyclerView.LayoutParams(-1, -2)
                textSize = 14f
                textColor = host.getColor(R.color.colorTextGray)
                gravity = Gravity.CENTER
                setPadding(0, dp(18), 0, dp(18))
            }) {}
        }
        // 行容器用 FrameLayout 而不是 LinearLayout+weight：勾选触发的重绑/移动动画会
        // 在瞬态下把 CheckBox 量成文本包裹宽度，weight 第二遍测量补不回来，把手就贴到
        // 文字尾巴上。END 重力钉在行右缘，与测量时序无关。
        val row = FrameLayout(host).apply {
            layoutParams = RecyclerView.LayoutParams(-1, -2)
        }
        val check = CheckBox(host).apply {
            textSize = 14f
            textColor = host.getColor(R.color.colorTextGray)
            minHeight = dp(52)
        }
        row.addView(check, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL).apply {
            // 勾选框占满把手左侧的全部宽度，文字永远不画进把手占位。
            marginEnd = dp(30)
        })
        // 拖拽把手常驻占位、只做 alpha 渐变：选中可排序时淡入提示"长按拖动"，
        // 不占交互空间——真正的手势是整行长按，把手只是状态提示。
        val grip = TextView(host).apply {
            text = "≡"
            textSize = 17f
            gravity = Gravity.CENTER
            textColor = host.getColor(R.color.colorTextGray)
            alpha = 0f
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        row.addView(grip, FrameLayout.LayoutParams(dp(30), dp(48),
            Gravity.END or Gravity.CENTER_VERTICAL))
        styleRow(row)
        return RowHolder(row, check, grip)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) =
        bind(holder, position, animate = false)

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) = bind(holder, position, animate = payloads.isNotEmpty())

    private fun bind(holder: RecyclerView.ViewHolder, position: Int, animate: Boolean) {
        when (val item = items[position]) {
            is FavoriteRowItem.Empty ->
                (holder.itemView as TextView).text = item.text
            is FavoriteRowItem.Row -> (holder as RowHolder).bind(item, animate, onToggle)
        }
    }

    private inner class RowHolder(
        row: View,
        private val check: CheckBox,
        private val grip: TextView
    ) : RecyclerView.ViewHolder(row) {
        private var boundId: String? = null

        fun bind(
            item: FavoriteRowItem.Row,
            animate: Boolean,
            onToggle: (String, Boolean) -> Unit
        ) {
            boundId = item.id
            check.setOnCheckedChangeListener(null)
            check.text = item.title
            check.isChecked = item.selected
            check.isEnabled = item.canToggle
            check.setOnCheckedChangeListener { _, checked ->
                boundId?.let { onToggle(it, checked) }
            }
            val target = if (item.canDrag) 1f else 0f
            if (grip.alpha != target) {
                if (animate) {
                    LumenAnimator.property(grip).alpha(target).setDuration(GRIP_FADE_MS).start()
                } else {
                    LumenAnimator.property(grip).cancel()
                    grip.alpha = target
                }
            }
        }
    }

    private companion object {
        const val TYPE_ROW = 0
        const val TYPE_EMPTY = 1
        const val GRIP_FADE_MS = 180L
    }
}
