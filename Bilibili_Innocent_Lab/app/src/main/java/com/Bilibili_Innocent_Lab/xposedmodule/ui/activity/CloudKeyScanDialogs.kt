@file:Suppress("SetTextI18n")

package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.app.Dialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.ListView
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CloudKeyFreezeRules
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentSnapshotCodec
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.MineComponentSnapshotQueryClient
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.MineComponentSnapshotStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import io.github.jichuo1.meguviz.android.extensions.textColor
import io.github.jichuo1.meguviz.android.extensions.toast
import android.widget.EditText as NativeEditText
import android.widget.LinearLayout as NativeLinearLayout
import android.widget.TextView as NativeTextView

/*
 * 「冻结指定云控键」的扫描勾选面板。
 *
 * 链路与首页 Tab / 底栏等勾选面板一致：宿主扫描 → 跨进程查询 → 勾选 → 写回设置。
 * 差异在于云控键成百上千，所以多了搜索框和「只看已勾选」，列表用 ListView 复用行；
 * 每个勾选的键可以翻转固定值（默认固定成扫描到的当前值）。
 *
 * 弹窗锚点沿用设置页的约定：用可点击的摘要行（[cloudKeyFreezePickedSummaryView]）做形变锚点，
 * 不取父容器。
 */

/** 点开设置页的「扫描并勾选云控键」行：先向宿主要最新的扫描结果，再弹出勾选面板。 */
internal fun MainActivity.openCloudKeyScanPanel() {
    val anchor = cloudKeyFreezePickedSummaryView
    if (!componentSnapshotQueryInFlight.add(MineComponentSnapshotCodec.SURFACE_CLOUD_KEYS)) return
    toast(getString(R.string.cloud_key_scan_querying))
    // 查询本身抛出时回调不会再来；不兜住就会把 in-flight 标记永久锁死，面板再也点不开。
    runCatching {
        MineComponentSnapshotQueryClient.query(this, MineComponentSnapshotCodec.SURFACE_CLOUD_KEYS) { result ->
            componentSnapshotQueryInFlight.remove(MineComponentSnapshotCodec.SURFACE_CLOUD_KEYS)
            if (isFinishing || isDestroyed ||
                !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
            ) return@query
            // 云控键面没有勾选存储键（固定值另存在 cloud.key_freeze.picked），和反馈面板的草稿面一样
            // 不走 Store.write：STORE_FAILED 也带着已校验的快照，这次显示得出来，只是没落盘。
            val live = result.snapshot?.takeIf {
                it.surface == MineComponentSnapshotCodec.SURFACE_CLOUD_KEYS && it.entries.isNotEmpty()
            }
            val cached = if (live == null) cachedCloudKeySnapshot() else null
            when {
                live != null -> showCloudKeyScanDialog(live, anchor, fresh = true)
                // 宿主这会儿不在线或还没扫到：用上一次宿主推送过来的结果，让用户至少能取消已有的固定。
                cached != null -> {
                    toast(getString(R.string.cloud_key_scan_cached_notice))
                    showCloudKeyScanDialog(cached, anchor, fresh = false)
                }
                else -> showCloudKeyScanGuide(anchor, result.status)
            }
        }
    }.onFailure {
        componentSnapshotQueryInFlight.remove(MineComponentSnapshotCodec.SURFACE_CLOUD_KEYS)
        showCloudKeyScanGuide(anchor, MineComponentSnapshotQueryClient.Status.TARGET_UNAVAILABLE)
    }
}

private fun MainActivity.cachedCloudKeySnapshot(): MineComponentSnapshot? =
    MineComponentSnapshotStore.read(this, MineComponentSnapshotCodec.SURFACE_CLOUD_KEYS)
        ?.takeIf { it.entries.isNotEmpty() }

/** 还没有任何扫描结果：说明怎么得到它，并给一个一键打开冻结与扫描开关的入口。 */
internal fun MainActivity.showCloudKeyScanGuide(anchor: View?, status: MineComponentSnapshotQueryClient.Status) {
    val density = resources.displayMetrics.density
    val dialog = Dialog(this).also { installDialogElasticInteraction(it) }
    val container = createModalContainer()
    container.addView(textBlock(getString(R.string.cloud_key_scan_title), 17f, R.color.colorTextDark))
    val reason = when (status) {
        MineComponentSnapshotQueryClient.Status.TARGET_UNAVAILABLE -> R.string.cloud_key_scan_guide_unavailable
        MineComponentSnapshotQueryClient.Status.INVALID_RESPONSE,
        MineComponentSnapshotQueryClient.Status.STORE_FAILED -> R.string.cloud_key_scan_guide_invalid
        else -> R.string.cloud_key_scan_guide_waiting
    }
    container.addView(
        textBlock(getString(reason) + "\n\n" + getString(R.string.cloud_key_scan_guide_steps), 13f, R.color.colorTextGray)
            .apply { setLineSpacing(4 * density, 1f) },
        NativeLinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = (10 * density).toInt() }
    )
    val buttons = NativeLinearLayout(this).apply {
        orientation = NativeLinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }
    buttons.addView(createTermsActionButton(getString(R.string.dialog_cancel), filled = false) {
        dismissWithAnimation(dialog, container) {}
    })
    buttons.addView(
        createTermsActionButton(getString(R.string.cloud_key_scan_enable_scan), filled = true) {
            prefs().edit {
                putBoolean(FeaturePreferences.CLOUD_KEY_FREEZE_ENABLED, true)
                putBoolean(FeaturePreferences.CLOUD_KEY_FREEZE_OBSERVE, true)
            }
            cloudKeyFreeze = true
            cloudKeyFreezeObserve = true
            toast(getString(R.string.cloud_key_scan_enabled_restart))
            dismissWithAnimation(dialog, container) {}
        },
        NativeLinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = (8 * density).toInt() }
    )
    container.addView(
        buttons,
        NativeLinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = (14 * density).toInt() }
    )
    presentModalDialog(dialog, container, anchor)
}

internal fun MainActivity.showCloudKeyScanDialog(snapshot: MineComponentSnapshot, anchor: View?, fresh: Boolean) {
    val density = resources.displayMetrics.density
    val dialog = Dialog(this).also { installDialogElasticInteraction(it) }
    val container = createModalContainer()
    val state = CloudKeyPickState.build(
        entries = snapshot.entries,
        picked = CloudKeyFreezeRules.parsePins(prefs().getString(FeaturePreferences.CLOUD_KEY_FREEZE_PICKED, "")),
        manual = CloudKeyFreezeRules.parse(prefs().getString(FeaturePreferences.CLOUD_KEY_FREEZE_RULES, ""))
    )

    container.addView(textBlock(getString(R.string.cloud_key_scan_title), 17f, R.color.colorTextDark))
    val status = textBlock("", 12f, R.color.colorTextGray)
    container.addView(
        status,
        NativeLinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = (8 * density).toInt() }
    )
    container.addView(
        textBlock(getString(R.string.cloud_key_scan_hint), 12f, R.color.colorTextGray).apply {
            alpha = 0.8f
            setLineSpacing(3 * density, 1f)
        },
        NativeLinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = (4 * density).toInt() }
    )

    var query = ""
    var onlyChecked = false
    lateinit var adapter: BaseAdapter

    fun refreshStatus() {
        val scanned = state.rows.count { it.scanned }
        val staleNote = if (fresh) "" else getString(R.string.cloud_key_scan_status_cached)
        status.text = getString(R.string.cloud_key_scan_status, scanned, state.checkedCount) + staleNote
    }

    val search = NativeEditText(this).apply {
        hint = getString(R.string.cloud_key_scan_search_hint)
        textColor = getColor(R.color.colorTextDark)
        setHintTextColor(ColorUtils.setAlphaComponent(getColor(R.color.colorTextGray), 0x99))
        textSize = 14f
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        isSingleLine = true
        setPadding((14 * density).toInt(), (10 * density).toInt(), (14 * density).toInt(), (10 * density).toInt())
        background = GradientDrawable().apply {
            cornerRadius = 14 * density
            setColor(monetColors.surfaceVariant)
            setStroke(
                density.toInt().coerceAtLeast(1),
                ColorUtils.setAlphaComponent(getColor(R.color.colorTextGray), 0x38)
            )
        }
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                adapter.notifyDataSetChanged()
            }
        })
    }
    container.addView(
        search,
        NativeLinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = (10 * density).toInt() }
    )
    container.addView(
        CheckBox(this).apply {
            text = getString(R.string.cloud_key_scan_only_checked)
            textSize = 13f
            setTextColor(getColor(R.color.colorTextGray))
            setOnCheckedChangeListener { _, checked ->
                onlyChecked = checked
                adapter.notifyDataSetChanged()
            }
        },
        NativeLinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = (4 * density).toInt() }
    )

    adapter = object : BaseAdapter() {
        private var shown: List<CloudKeyPickState.Row> = emptyList()
        override fun notifyDataSetChanged() {
            shown = state.visible(query, onlyChecked)
            super.notifyDataSetChanged()
        }
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = shown[position]
            val view = (convertView as? CloudKeyRowView) ?: CloudKeyRowView(this@showCloudKeyScanDialog)
            view.bind(state, row) {
                refreshStatus()
                notifyDataSetChanged()
            }
            return view
        }
    }
    adapter.notifyDataSetChanged()
    refreshStatus()

    val list = ListView(this).apply {
        this.adapter = adapter
        divider = null
        isVerticalScrollBarEnabled = true
        // 行内自己处理点击；ListView 只负责滚动和复用。
        isFocusable = false
    }
    container.addView(
        list,
        NativeLinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = (8 * density).toInt()
            bottomMargin = (10 * density).toInt()
        }
    )

    val buttons = NativeLinearLayout(this).apply {
        orientation = NativeLinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }
    buttons.addView(createTermsActionButton(getString(R.string.dialog_cancel), filled = false) {
        dismissWithAnimation(dialog, container) {}
    })
    buttons.addView(
        createTermsActionButton(getString(R.string.dialog_confirm), filled = true) {
            val text = state.pinsText()
            cloudKeyFreezePicked = text
            prefs().edit { putString(FeaturePreferences.CLOUD_KEY_FREEZE_PICKED, text) }
            // 勾了东西但冻结总开关是关的，等于什么都没发生：一并打开，免得用户以为已生效。
            if (state.checkedCount > 0 && !cloudKeyFreeze) {
                cloudKeyFreeze = true
                prefs().edit { putBoolean(FeaturePreferences.CLOUD_KEY_FREEZE_ENABLED, true) }
            }
            cloudKeyFreezePickedSummaryView?.text = cloudKeyFreezePickedSummary()
            toast(getString(R.string.cloud_key_scan_saved_restart))
            dismissWithAnimation(dialog, container) {}
        },
        NativeLinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = (8 * density).toInt() }
    )
    container.addView(
        buttons,
        NativeLinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    )
    presentModalDialog(dialog, container, anchor)
}

/** 设置页摘要行的文本：固定了几个键，没有时提示怎么开始。 */
internal fun MainActivity.cloudKeyFreezePickedSummary(): String {
    val count = CloudKeyFreezeRules.parsePins(cloudKeyFreezePicked).size
    return getString(R.string.cloud_key_freeze_picked) + "\n" + if (count == 0) {
        getString(R.string.cloud_key_freeze_picked_empty)
    } else {
        getString(R.string.cloud_key_freeze_picked_count, count)
    }
}

private fun MainActivity.textBlock(text: String, sizeSp: Float, colorRes: Int) = NativeTextView(this).apply {
    this.text = text
    textColor = getColor(colorRes)
    textSize = sizeSp
}

/** 一行：勾选框 + 键名 + 云控当前值 + （勾选后）可翻转的固定值。 */
private class CloudKeyRowView(context: Context) : NativeLinearLayout(context) {
    private val box = CheckBox(context)
    private val detail = NativeTextView(context)
    private val pin = NativeTextView(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val density = resources.displayMetrics.density
        val text = NativeLinearLayout(context).apply { orientation = VERTICAL }
        box.textSize = 14f
        box.setTextColor(context.getColor(R.color.colorTextDark))
        detail.textSize = 11f
        detail.setTextColor(context.getColor(R.color.colorTextGray))
        detail.setPadding((32 * density).toInt(), 0, 0, 0)
        text.addView(box, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        text.addView(detail, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(text, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        pin.textSize = 13f
        pin.setTextColor(context.getColor(R.color.colorTextDark))
        pin.setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
        pin.background = GradientDrawable().apply {
            cornerRadius = 12 * density
            setStroke(density.toInt().coerceAtLeast(1), ColorUtils.setAlphaComponent(context.getColor(R.color.colorTextGray), 0x66))
        }
        pin.isClickable = true
        addView(pin, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
    }

    fun bind(state: CloudKeyPickState, row: CloudKeyPickState.Row, onChanged: () -> Unit) {
        val context = context
        box.setOnCheckedChangeListener(null)
        box.text = row.key
        box.isChecked = row.checked
        box.isEnabled = !row.lockedByManual
        box.alpha = if (row.selectable || row.checked) 1f else 0.5f
        detail.text = when {
            row.lockedByManual -> context.getString(R.string.cloud_key_scan_row_manual)
            row.mixed -> context.getString(R.string.cloud_key_scan_row_mixed)
            row.observed == null -> context.getString(R.string.cloud_key_scan_row_not_seen)
            else -> context.getString(R.string.cloud_key_scan_row_cloud, onOff(row.observed))
        }
        pin.visibility = if (row.checked) View.VISIBLE else View.GONE
        pin.text = context.getString(R.string.cloud_key_scan_row_pin, onOff(row.pin))
        pin.isEnabled = !row.lockedByManual
        box.setOnClickListener {
            if (!state.toggle(row)) {
                box.isChecked = row.checked
                if (!row.lockedByManual) context.toast(context.getString(R.string.cloud_key_scan_cannot_check))
            }
            onChanged()
        }
        pin.setOnClickListener {
            state.flipPin(row)
            onChanged()
        }
    }

    private fun onOff(value: Boolean?) =
        context.getString(if (value == true) R.string.cloud_key_scan_on else R.string.cloud_key_scan_off)
}
