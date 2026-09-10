@file:Suppress("SetTextI18n")

package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

// MainActivity 的嵌套类型：扩展函数里嵌套 classifier 不在作用域内，必须显式导入。
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity.Companion.MINE_COMPONENT_SNAPSHOT_STALE_MS
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity.ComponentPickerSurface
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import androidx.core.view.setPadding
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentScanEntry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentSelectionCodec
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.RuleSetCodec
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.MineComponentSnapshotStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import com.highcapable.betterandroid.ui.extension.view.textColor
import com.highcapable.betterandroid.ui.extension.view.textToString
import com.highcapable.betterandroid.ui.extension.view.toast
import com.highcapable.hikage.core.layout.LayoutParams
import android.widget.EditText as NativeEditText
import android.widget.LinearLayout as NativeLinearLayout
import android.widget.ScrollView as NativeScrollView
import android.widget.TextView as NativeTextView

/*
 * 组件选择与手填规则相关的弹窗，从 MainActivity 外移而来（函数体逐字搬迁，未改行为）：
 * 组件选择、快照缺失回退、手填规则编辑器。
 *
 * 写成 `MainActivity` 的扩展函数，是为了原样调用设置页的共用底座——
 * createModalContainer() / presentModalDialog() / dismissWithAnimation()。
 *
 * ## 这里的锚点约定是门禁锁着的
 *
 * 两个入口都必须用**可点击的摘要行**（`spec.summaryView()`）作为形变锚点，
 * 不能退回整个设置分组（`it.parent` / `parentOrNull`）——否则弹窗会从一大块
 * 区域长出来。快照缺失的回退路径也必须走同一个带锚点的编辑器，且不得顺手
 * remove/clear 已有选择。这几条由 ModalAnchorRegressionTest 与
 * ModalMotionRefinementTest 按函数名精确断言，搬到哪个文件都跟得上。
 */

/**
 * 动态勾选只写稳定 selector；旧 id 和用户手写标题规则仅作为兼容输入，不会被覆盖。
 */
internal fun MainActivity.showComponentPickerDialog(
    spec: ComponentPickerSurface,
    snapshot: MineComponentSnapshot
) {
    // 四种面的摘要 TextView 本身就是点击条目，其父级是整段设置分组，绝不能向上取父容器。
    val anchor = spec.summaryView()
    val entries = snapshot.entries
    if (entries.isEmpty()) return

    val density = resources.displayMetrics.density
    val dialog = Dialog(this)
    val container = createModalContainer()

    container.addView(
        NativeTextView(this).apply {
            text = getString(spec.titleRes)
            textColor = getColor(R.color.colorTextDark)
            textSize = 17f
            setLineSpacing(4 * density, 1f)
        },
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    )

    val snapshotAge = (System.currentTimeMillis() - snapshot.generatedAt)
        .coerceAtLeast(0L)
    container.addView(
        NativeTextView(this).apply {
            text = when {
                snapshot.generatedAt <= 0L -> spec.status.legacy
                snapshotAge > MINE_COMPONENT_SNAPSHOT_STALE_MS -> spec.status.stale
                else -> spec.status.ready(
                    entries.count(MineComponentScanEntry::selectable)
                )
            }
            textColor = getColor(R.color.colorTextGray)
            textSize = 12f
            setPadding(0, (8 * density).toInt(), 0, 0)
        },
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    )

    val initialSelectors = MineComponentSelectionCodec.decode(
        prefs().getString(spec.selectorsKey, "").orEmpty()
    )
    val initialHiddenIds = spec.legacyIdsKey
        ?.let { prefs().getString(it, "") }
        .orEmpty()
        .split(Regex("[,，;；\\r\\n]+"))
        .filter { it.isNotBlank() }
        .toSet()
    val initialHiddenRules = RuleSetCodec.parse(
        prefs().getString(spec.rulesKey, "").orEmpty()
    )
    fun legacyHidden(e: MineComponentScanEntry): Boolean =
        (e.id != null && e.id in initialHiddenIds) ||
            (e.title != null && RuleSetCodec.matches(initialHiddenRules, e.title))

    // 勾选行（标题 + 副标 uri/id）
    val listBody = NativeScrollView(this).apply {
        isFillViewport = true
    }
    val rowContainer = NativeLinearLayout(this).apply {
        orientation = NativeLinearLayout.VERTICAL
        setPadding(
            (4 * density).toInt(), (2 * density).toInt(),
            (4 * density).toInt(), (2 * density).toInt()
        )
    }
    val checkboxes = ArrayList<android.widget.CheckBox>()
    entries.forEach { entry ->
        val legacyLocked = legacyHidden(entry)
        val box = android.widget.CheckBox(this).apply {
            text = buildString {
                append(entry.title ?: entry.id ?: "(未命名)")
                entry.uri?.let { append("  ·  ").append(it) }
                if (legacyLocked) append(getString(R.string.custom_mine_component_legacy_locked))
                else if (!entry.selectable) {
                    append(getString(R.string.custom_mine_component_not_selectable))
                }
            }
            textSize = 14f
            setTextColor(getColor(R.color.colorTextDark))
            isChecked = legacyLocked || entry.key in initialSelectors
            isEnabled = entry.selectable && !legacyLocked
        }
        checkboxes.add(box)
        rowContainer.addView(
            box,
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (6 * density).toInt()
                bottomMargin = (6 * density).toInt()
            }
        )
    }
    listBody.addView(rowContainer)
    container.addView(
        listBody,
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ).apply {
            topMargin = (12 * density).toInt()
            bottomMargin = (12 * density).toInt()
        }
    )

    val buttonRow = NativeLinearLayout(this).apply {
        orientation = NativeLinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }
    buttonRow.addView(
        NativeTextView(this).apply {
            text = getString(R.string.custom_mine_component_manual_rules)
            textColor = getColor(R.color.colorTextGray)
            textSize = 14f
            setPadding(
                (16 * density).toInt(), (11 * density).toInt(),
                (16 * density).toInt(), (11 * density).toInt()
            )
            background = selfRippleBackground(14f)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dismissWithAnimation(dialog, container) {
                    showComponentManualRuleEditor(spec)
                }
            }
        },
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    )
    buttonRow.addView(
        NativeTextView(this).apply {
            text = getString(R.string.dialog_cancel)
            textColor = getColor(R.color.colorTextGray)
            textSize = 14f
            setPadding(
                (20 * density).toInt(), (11 * density).toInt(),
                (20 * density).toInt(), (11 * density).toInt()
            )
            background = selfRippleBackground(14f)
            isClickable = true
            isFocusable = true
            setOnClickListener { dismissWithAnimation(dialog, container) {} }
        },
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    )
    buttonRow.addView(
        createTermsActionButton(
            text = getString(R.string.dialog_confirm),
            filled = true
        ) {
            val editableKeys = entries.mapIndexedNotNull { index, entry ->
                if (entry.selectable && checkboxes.getOrNull(index)?.isEnabled == true) {
                    entry.key
                } else {
                    null
                }
            }.toSet()
            val checkedSelectors = entries.mapIndexedNotNull { index, entry ->
                if (entry.selectable &&
                    checkboxes.getOrNull(index)?.isEnabled == true &&
                    checkboxes[index].isChecked
                ) entry.key else null
            }.toSet()
            val hiddenSelectors = (initialSelectors - editableKeys) + checkedSelectors
            prefs().edit {
                putString(
                    spec.selectorsKey,
                    MineComponentSelectionCodec.encode(hiddenSelectors)
                )
            }
            spec.refreshSummary()
            toast(getString(R.string.custom_mine_component_restart_required))
            dismissWithAnimation(dialog, container) {}
        },
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = (8 * density).toInt() }
    )
    container.addView(
        buttonRow,
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (10 * density).toInt() }
    )

    presentModalDialog(dialog, container, anchor)
}

internal fun MainActivity.showComponentManualRuleEditor(spec: ComponentPickerSurface) {
    val anchor = spec.summaryView()
    showRuleEditorDialog(spec.titleRes, spec.hintRes, spec.currentRules(), anchor) { value ->
        spec.onRulesSaved(value)
        prefs().edit { putString(spec.rulesKey, value) }
        spec.refreshSummary()
    }
}

/** 自定义隐藏规则编辑器：沿用项目模态弹窗与统一退场动画。 */
internal fun MainActivity.showRuleEditorDialog(
    @StringRes titleRes: Int,
    @StringRes hintRes: Int,
    initialValue: String,
    anchor: View? = null,
    onConfirm: (String) -> Unit
) {
    val density = resources.displayMetrics.density
    val dialog = Dialog(this)
    val container = createModalContainer()

    container.addView(
        NativeTextView(this).apply {
            text = getString(titleRes)
            textColor = getColor(R.color.colorTextDark)
            textSize = 17f
            setLineSpacing(4 * density, 1f)
        },
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    )

    val editor = NativeEditText(this).apply {
        setText(initialValue)
        setSelection(text.length)
        hint = getString(hintRes)
        textColor = getColor(R.color.colorTextDark)
        setHintTextColor(ColorUtils.setAlphaComponent(getColor(R.color.colorTextGray), 0x99))
        textSize = 14f
        gravity = Gravity.TOP or Gravity.START
        inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        isSingleLine = false
        minLines = 3
        maxLines = 6
        setHorizontallyScrolling(false)
        setPadding(
            (14 * density).toInt(),
            (12 * density).toInt(),
            (14 * density).toInt(),
            (12 * density).toInt()
        )
        background = GradientDrawable().apply {
            cornerRadius = 14 * density
            setColor(monetColors.surfaceVariant)
            setStroke(
                density.toInt().coerceAtLeast(1),
                ColorUtils.setAlphaComponent(getColor(R.color.colorTextGray), 0x38)
            )
        }
    }
    container.addView(
        editor,
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (14 * density).toInt() }
    )

    val buttonRow = NativeLinearLayout(this).apply {
        orientation = NativeLinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }
    buttonRow.addView(
        NativeTextView(this).apply {
            text = getString(R.string.dialog_cancel)
            textColor = getColor(R.color.colorTextGray)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(
                (20 * density).toInt(),
                (11 * density).toInt(),
                (20 * density).toInt(),
                (11 * density).toInt()
            )
            background = selfRippleBackground(14f)
            isClickable = true
            isFocusable = true
            setOnClickListener { dismissWithAnimation(dialog, container) {} }
        }
    )
    buttonRow.addView(
        NativeTextView(this).apply {
            text = getString(R.string.dialog_confirm)
            textColor = monetColors.onPrimary
            textSize = 15f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(
                (22 * density).toInt(),
                (11 * density).toInt(),
                (22 * density).toInt(),
                (11 * density).toInt()
            )
            val radius = 20 * density
            val content = GradientDrawable().apply {
                cornerRadius = radius
                setColor(monetColors.primary)
            }
            val rippleMask = GradientDrawable().apply {
                cornerRadius = radius
                setColor(Color.WHITE)
            }
            background = RippleDrawable(
                ColorStateList.valueOf(
                    ColorUtils.setAlphaComponent(monetColors.onPrimary, 0x33)
                ),
                content,
                rippleMask
            )
            skinActionButton(this, filled = true)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val value = editor.textToString().trim()
                dismissWithAnimation(dialog, container) { onConfirm(value) }
            }
        },
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = (16 * density).toInt() }
    )
    container.addView(
        buttonRow,
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (18 * density).toInt() }
    )
    presentModalDialog(dialog, container, anchor)
}

internal fun MainActivity.showComponentSnapshotFallback(
    spec: ComponentPickerSurface,
    message: String,
    transientSnapshot: MineComponentSnapshot? = null
) {
    toast(message)
    val snapshot = transientSnapshot ?: readComponentSnapshot(spec)
    if (snapshot != null && snapshot.entries.isNotEmpty()) {
        showComponentPickerDialog(spec, snapshot)
    } else {
        showComponentManualRuleEditor(spec)
    }
}

/** 新协议快照同时校验来源版本；旧快照仍按原有协议兼容读取。 */
private fun MainActivity.readComponentSnapshot(spec: ComponentPickerSurface): MineComponentSnapshot? =
    MineComponentSnapshotStore.read(this, spec.surface)
