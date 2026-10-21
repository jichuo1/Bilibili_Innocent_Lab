@file:Suppress("SetTextI18n")

package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.app.Dialog
import android.graphics.Typeface
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.annotation.StringRes
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PublishTimeMode
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PublishTimePoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PublishTimeUnit
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.VideoPublishTimeRule
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import com.Bilibili_Innocent_Lab.xposedmodule.ui.widget.MaxHeightScrollView
import com.highcapable.betterandroid.ui.extension.view.textColor
import com.lumen.coacervation.engine.widget.LumenSlidingSelection
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import android.widget.EditText as NativeEditText
import android.widget.HorizontalScrollView as NativeHorizontalScrollView
import android.widget.LinearLayout as NativeLinearLayout
import android.widget.TextView as NativeTextView

/** 发布时间面板里一个时间节点的草稿；确认前不落盘。 */
private class PublishTimePointDraft(point: PublishTimePoint?) {
    var relative: Boolean = point is PublishTimePoint.Relative
    val date = PublishTimeDateDraft((point as? PublishTimePoint.Absolute)?.date)
    var amountText: String = (point as? PublishTimePoint.Relative)?.amount?.toString().orEmpty()
    var unit: PublishTimeUnit = (point as? PublishTimePoint.Relative)?.unit ?: PublishTimeUnit.DAY

    /** null 表示草稿还不完整；不抛异常，由调用方决定提示哪一条。 */
    fun toPoint(): PublishTimePoint? = if (relative) {
        amountText.trim().toIntOrNull()
            ?.takeIf { it in 1..PublishTimePoint.MAX_RELATIVE_AMOUNT }
            ?.let { PublishTimePoint.Relative(it, unit) }
    } else {
        date.toDate()?.let { PublishTimePoint.Absolute(it) }
    }
}

internal fun MainActivity.showRecommendVideoPublishTimeDialog(anchor: View? = null) {
    val density = resources.displayMetrics.density
    val dialog = Dialog(this).also { installDialogElasticInteraction(it) }
    val container = createModalContainer()
    val saved = VideoPublishTimeRule.parse(recommendVideoPublishTimeRule)
    var mode: PublishTimeMode? = saved.mode
    val drafts = listOf(PublishTimePointDraft(saved.first), PublishTimePointDraft(saved.second))
    fun dp(value: Int): Int = (value * density).toInt()
    // 模式、时间节点与预览放进限高滚动区，小屏上按钮行始终可见。
    val body = NativeLinearLayout(this).apply { orientation = NativeLinearLayout.VERTICAL }

    container.addView(
        NativeTextView(this).apply {
            text = getString(R.string.recommend_video_publish_time_range)
            textColor = getColor(R.color.colorTextDark)
            textSize = 17f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setLineSpacing(4 * density, 1f)
        },
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    )

    fun choiceGroup(labels: List<String>, selected: Int, horizontal: Boolean = false,
                    equalWidth: Boolean = false): LumenSlidingSelection {
        val choice = createSkinSlidingSelection()
        choice.options.orientation = if (horizontal) NativeLinearLayout.HORIZONTAL else NativeLinearLayout.VERTICAL
        labels.forEachIndexed { index, label ->
            val option = if (horizontal) NativeTextView(this).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                minimumHeight = dp(44)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = selfRippleBackground(14f)
                isFocusable = true
            } else createGitHubMenuRow(label, "", highlight = false) {}
            choice.addOption(option, NativeLinearLayout.LayoutParams(
                if (equalWidth) 0 else if (horizontal) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, if (equalWidth) 1f else 0f
            ).apply { if (horizontal && index > 0) marginStart = dp(6) })
        }
        val normalColor = getColor(R.color.colorTextGray)
        val selectedColor = monetColors.primary
        choice.setOnHighlightListener { index, weight ->
            val option = choice.options.getChildAt(index)
            val title = if (option is NativeTextView) option else (option as? ViewGroup)?.getChildAt(0) as? NativeTextView
            title?.textColor = ColorUtils.blendARGB(normalColor, selectedColor, weight)
        }
        choice.select(selected, animate = false)
        return choice
    }

    fun selectIfChanged(choice: LumenSlidingSelection, index: Int) {
        if (choice.selectedIndex != index) choice.select(index, animate = false)
    }

    val modeOptions = listOf(
        null to R.string.recommend_video_publish_time_mode_off,
        PublishTimeMode.BEFORE to R.string.recommend_video_publish_time_mode_before,
        PublishTimeMode.AFTER to R.string.recommend_video_publish_time_mode_after,
        PublishTimeMode.BETWEEN to R.string.recommend_video_publish_time_mode_between,
        PublishTimeMode.OUTSIDE to R.string.recommend_video_publish_time_mode_outside
    )
    val modeChoice = choiceGroup(modeOptions.map { getString(it.second) }, modeOptions.indexOfFirst { it.first == mode })
    body.addView(modeChoice, NativeLinearLayout.LayoutParams(-1, -2))

    val previewView = NativeTextView(this).apply {
        textColor = getColor(R.color.colorTextDark)
        textSize = 13f
        setLineSpacing(4 * density, 1f)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = skinCardBackground(monetColors.surfaceVariant, 14f)
    }
    val errorView = NativeTextView(this).apply {
        visibility = View.GONE
        textColor = if (ColorUtils.calculateLuminance(monetColors.surface) < 0.5) {
            0xFFFFB4AB.toInt()
        } else {
            0xFFBA1A1A.toInt()
        }
        textSize = 12f
        setLineSpacing(4 * density, 1f)
    }

    /** 当前草稿能否组成一条规则；不完整或倒置时返回 null。 */
    fun draftRule(): VideoPublishTimeRule? {
        val selected = mode ?: return VideoPublishTimeRule.DISABLED
        val first = drafts[0].toPoint() ?: return null
        val second = if (selected.pointCount == 2) drafts[1].toPoint() ?: return null else null
        return runCatching { VideoPublishTimeRule.of(selected, first, second) }.getOrNull()
    }

    fun refreshPreview() {
        val rule = draftRule()
        previewView.text = getString(R.string.recommend_video_publish_time_preview_label) + "\n" +
            when {
                rule == null -> "—"
                else -> recommendVideoPublishTimeSummary(rule, Instant.now())
            }
    }

    fun numberEditor(raw: String, maxLength: Int, hintText: String, action: Int,
                     onChanged: (String) -> Unit): NativeEditText = NativeEditText(this).apply {
        setText(raw)
        hint = hintText
        textColor = getColor(R.color.colorTextDark)
        setHintTextColor(ColorUtils.setAlphaComponent(getColor(R.color.colorTextGray), 0x99))
        textSize = 14f
        inputType = InputType.TYPE_CLASS_NUMBER
        imeOptions = action
        isSingleLine = true
        filters = arrayOf(InputFilter.LengthFilter(maxLength))
        minimumHeight = dp(44)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        // presentModalDialog applies the shared engine input surface and outline.
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                onChanged(s?.toString().orEmpty())
                refreshPreview()
            }
        })
    }

    val pointSections = drafts.mapIndexed { index, draft ->
        val section = NativeLinearLayout(this).apply { orientation = NativeLinearLayout.VERTICAL }
        val label = NativeTextView(this).apply {
            textColor = getColor(R.color.colorTextGray)
            textSize = 13f
        }
        section.addView(label)

        val kindChoice = choiceGroup(
            listOf(
                getString(R.string.recommend_video_publish_time_kind_date),
                getString(R.string.recommend_video_publish_time_kind_relative)
            ),
            if (draft.relative) 1 else 0,
            horizontal = true,
            equalWidth = true
        )
        section.addView(
            kindChoice,
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        )

        val dateGroup = NativeLinearLayout(this).apply { orientation = NativeLinearLayout.VERTICAL }
        val dateRow = NativeLinearLayout(this).apply { orientation = NativeLinearLayout.HORIZONTAL }
        fun dateField(@StringRes labelRes: Int, raw: String, maxLength: Int, weight: Float,
                      action: Int, onChanged: (String) -> Unit): NativeEditText {
            val fieldLabel = NativeTextView(this).apply {
                text = getString(labelRes)
                textColor = getColor(R.color.colorTextGray)
                textSize = 12f
            }
            val editor = numberEditor(raw, maxLength, getString(labelRes), action, onChanged)
            val column = NativeLinearLayout(this).apply {
                orientation = NativeLinearLayout.VERTICAL
                addView(fieldLabel)
                addView(editor, NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            }
            dateRow.addView(column, NativeLinearLayout.LayoutParams(0, -2, weight).apply {
                if (dateRow.childCount > 0) marginStart = dp(8)
            })
            return editor
        }
        val yearEditor = dateField(R.string.recommend_video_publish_time_date_year,
            draft.date.yearText, 4, 1.4f, EditorInfo.IME_ACTION_NEXT) { draft.date.yearText = it }
        val monthEditor = dateField(R.string.recommend_video_publish_time_date_month,
            draft.date.monthText, 2, 1f, EditorInfo.IME_ACTION_NEXT) { draft.date.monthText = it }
        val dayEditor = dateField(R.string.recommend_video_publish_time_date_day,
            draft.date.dayText, 2, 1f, EditorInfo.IME_ACTION_DONE) { draft.date.dayText = it }
        dateGroup.addView(dateRow, NativeLinearLayout.LayoutParams(-1, -2))
        dateGroup.addView(NativeTextView(this).apply {
            text = getString(R.string.recommend_video_publish_time_date_fields_hint,
                PublishTimePoint.MIN_YEAR, PublishTimePoint.MAX_YEAR)
            textColor = getColor(R.color.colorTextGray)
            textSize = 12f
            setLineSpacing(3 * density, 1f)
        }, NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        dateGroup.addView(createTermsActionButton(getString(R.string.recommend_video_publish_time_today), filled = false) {
            draft.date.setDate(LocalDate.now(ZoneId.systemDefault()))
            yearEditor.setText(draft.date.yearText)
            monthEditor.setText(draft.date.monthText)
            dayEditor.setText(draft.date.dayText)
        }, NativeLinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END; topMargin = dp(4) })
        section.addView(
            dateGroup,
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        )

        val relativeGroup = NativeLinearLayout(this).apply { orientation = NativeLinearLayout.VERTICAL }
        val amountEditor = numberEditor(draft.amountText, 4,
            getString(R.string.recommend_video_publish_time_amount_hint), EditorInfo.IME_ACTION_DONE) {
            draft.amountText = it
        }
        relativeGroup.addView(
            amountEditor,
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        )
        val unitLabels = mapOf(
            PublishTimeUnit.HOUR to R.string.recommend_video_publish_time_unit_hour,
            PublishTimeUnit.DAY to R.string.recommend_video_publish_time_unit_day,
            PublishTimeUnit.WEEK to R.string.recommend_video_publish_time_unit_week,
            PublishTimeUnit.MONTH to R.string.recommend_video_publish_time_unit_month,
            PublishTimeUnit.YEAR to R.string.recommend_video_publish_time_unit_year
        )
        val units = unitLabels.keys.toList()
        val unitChoice = choiceGroup(unitLabels.values.map { getString(it) }, units.indexOf(draft.unit), horizontal = true)
        relativeGroup.addView(
            NativeHorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(unitChoice, FrameLayout.LayoutParams(-2, -2))
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        )
        section.addView(relativeGroup)

        fun render() {
            label.text = getString(
                when {
                    mode?.pointCount != 2 -> R.string.recommend_video_publish_time_point_single
                    index == 0 -> R.string.recommend_video_publish_time_point_start
                    else -> R.string.recommend_video_publish_time_point_end
                }
            )
            selectIfChanged(kindChoice, if (draft.relative) 1 else 0)
            dateGroup.visibility = if (draft.relative) View.GONE else View.VISIBLE
            relativeGroup.visibility = if (draft.relative) View.VISIBLE else View.GONE
            selectIfChanged(unitChoice, units.indexOf(draft.unit))
            yearEditor.contentDescription = "${label.text} ${getString(R.string.recommend_video_publish_time_date_year)}"
            monthEditor.contentDescription = "${label.text} ${getString(R.string.recommend_video_publish_time_date_month)}"
            dayEditor.contentDescription = "${label.text} ${getString(R.string.recommend_video_publish_time_date_day)}"
            amountEditor.contentDescription = "${label.text} ${getString(R.string.recommend_video_publish_time_amount_hint)}"
        }

        kindChoice.onSelect = {
            draft.relative = it == 1
            render()
            refreshPreview()
        }
        unitChoice.onSelect = {
            draft.unit = units[it]
            refreshPreview()
        }
        render()
        body.addView(
            section,
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
        )
        section to { render() }
    }

    fun renderMode() {
        selectIfChanged(modeChoice, modeOptions.indexOfFirst { it.first == mode })
        pointSections[0].first.visibility = if (mode == null) View.GONE else View.VISIBLE
        pointSections[1].first.visibility = if (mode?.pointCount == 2) View.VISIBLE else View.GONE
        pointSections.forEach { (_, render) -> render() }
        errorView.visibility = View.GONE
        refreshPreview()
    }
    modeChoice.onSelect = {
        mode = modeOptions[it].first
        renderMode()
    }

    body.addView(
        previewView,
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) }
    )
    body.addView(
        errorView,
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) }
    )
    renderMode()
    val contentCap = minOf(dp(520), (resources.displayMetrics.heightPixels * 0.6f).toInt())
    container.addView(
        MaxHeightScrollView(this, contentCap).apply {
            addView(body, FrameLayout.LayoutParams(-1, -2))
        },
        NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
    )

    fun showError(@StringRes messageRes: Int) {
        errorView.text = getString(messageRes)
        errorView.visibility = View.VISIBLE
        errorView.announceForAccessibility(errorView.text)
    }

    /** 逐项校验；返回 null 表示已经展示了错误。 */
    fun validatedRule(): VideoPublishTimeRule? {
        val selected = mode ?: return VideoPublishTimeRule.DISABLED
        val needed = drafts.take(selected.pointCount)
        needed.forEach { draft ->
            if (draft.relative && draft.toPoint() == null) {
                showError(R.string.recommend_video_publish_time_invalid_amount)
                return null
            }
            if (!draft.relative && draft.toPoint() == null) {
                showError(R.string.recommend_video_publish_time_invalid_date)
                return null
            }
        }
        val rule = draftRule()
        // 混合"日期 + 相对"的区间只能按当前时刻判断先后；此刻倒置的规则不允许保存。
        if (rule == null || rule.resolveBounds(Instant.now(), ZoneId.systemDefault()) == null) {
            showError(R.string.recommend_video_publish_time_invalid_range)
            return null
        }
        return rule
    }

    val buttonRow = NativeLinearLayout(this).apply {
        orientation = NativeLinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }
    val cancelButton = createTermsActionButton(getString(R.string.dialog_cancel), filled = false) {
        dismissWithAnimation(dialog, container) {}
    }
    val confirmButton = createTermsActionButton(getString(R.string.dialog_confirm), filled = true) {
        errorView.visibility = View.GONE
        val rule = validatedRule() ?: return@createTermsActionButton
        val encoded = rule.encode()
        recommendVideoPublishTimeRule = encoded
        runCatching {
            prefs().edit {
                putString(FeaturePreferences.RECOMMEND_VIDEO_PUBLISH_TIME_RULE, encoded)
            }
        }.onFailure { throwable ->
            Log.e(
                "BilibiliInnocentLab",
                "write recommended video publish time prefs failed",
                throwable
            )
        }
        updateRecommendVideoPublishTimeSummary()
        dismissWithAnimation(dialog, container) {}
    }
    buttonRow.addView(
        cancelButton,
        NativeLinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    )
    buttonRow.addView(
        confirmButton,
        NativeLinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginStart = dp(8) }
    )
    container.addView(
        buttonRow,
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(18) }
    )
    presentModalDialog(dialog, container, anchor)
}

internal fun MainActivity.updateRecommendVideoPublishTimeSummary() {
    recommendVideoPublishTimeSummaryView?.text =
        getString(R.string.recommend_video_publish_time_range) + "\n" +
            recommendVideoPublishTimeSummary()
}
