@file:Suppress("SetTextI18n")

package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.DetailModulePurifyPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
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
 * 净化过滤类的勾选/取值弹窗，从 MainActivity 外移而来（函数体逐字搬迁，未改行为）：
 * 首页推荐过滤、竖屏内容过滤、相关视频过滤、推荐视频时长区间。
 *
 * 写成 `MainActivity` 的扩展函数，是为了原样调用设置页的共用底座——
 * createModalContainer() / presentModalDialog() / dismissWithAnimation()，
 * 那套东西承载了返回手势接管、图标锚点形变、气泡摆放与背景毛玻璃的完整时序。
 * 只有被一级界面调用的入口是 internal，各自的取值与文案辅助函数一律文件私有。
 *
 * ## 这里的 internal 镜像字段是过渡态
 *
 * 这些弹窗仍直接读写 MainActivity 上那批以 removeHomeRecommend、removeStory、
 * removeRelate 开头的镜像字段，因此它们一并放宽成了 internal。
 * 这批字段本来就要在第 2 步（UI 树外移）里改成从设置快照读取——
 * 无论先搬弹窗还是先搬 UI 树，它们都必须放宽，所以这不是白做的一步；
 * 但等第 2 步落地后，这里的直接引用要跟着收敛回去。
 */

internal fun MainActivity.showHomeRecommendFilterDialog(
    focusPreferenceKey: String? = null,
    anchor: View? = null
) {
    val density = resources.displayMetrics.density
    val dialog = Dialog(this)
    val container = createModalContainer()
    val draft = HomeRecommendFilterDraft(homeRecommendFilterValues())
    container.addView(NativeTextView(this).apply {
        text = getString(R.string.home_recommend_filter_title)
        textColor = getColor(R.color.colorTextDark)
        textSize = 19f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    })
    container.addView(NativeTextView(this).apply {
        text = getString(R.string.home_recommend_filter_dialog_description)
        textColor = getColor(R.color.colorTextGray)
        textSize = 12f
        alpha = 0.72f
        setLineSpacing(4 * density, 1f)
    }, NativeLinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = (7 * density).toInt() })

    val quickActions = NativeLinearLayout(this).apply {
        orientation = NativeLinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }
    val selectAllButton = createTermsActionButton(
        getString(R.string.home_recommend_filter_select_all), filled = false
    ) {}
    val clearButton = createTermsActionButton(
        getString(R.string.home_recommend_filter_clear), filled = false
    ) {}
    quickActions.addView(selectAllButton,
        NativeLinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    quickActions.addView(clearButton,
        NativeLinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = (8 * density).toInt()
        })
    container.addView(quickActions, NativeLinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = (12 * density).toInt() })

    val rows = NativeLinearLayout(this).apply {
        orientation = NativeLinearLayout.VERTICAL
        setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
    }
    val checkboxes = linkedMapOf<String, android.widget.CheckBox>()
    HomeRecommendFilterCatalog.preferenceKeys.forEach { key ->
        val box = android.widget.CheckBox(this).apply {
            text = getString(homeRecommendFilterLabel(key))
            textSize = 14f
            textColor = getColor(R.color.colorTextDark)
            minimumHeight = (48 * density).toInt()
            isChecked = draft[key]
            isFocusable = true
        }
        checkboxes[key] = box
        rows.addView(box, NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }
    val listBody = NativeScrollView(this).apply {
        isFillViewport = true
        addView(rows)
    }
    val listHeight = minOf(
        (304 * density).toInt(), (resources.displayMetrics.heightPixels * 0.38f).toInt()
    )
    container.addView(listBody, NativeLinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, listHeight
    ).apply {
        topMargin = (7 * density).toInt()
        bottomMargin = (8 * density).toInt()
    })

    val buttonRow = NativeLinearLayout(this).apply {
        orientation = NativeLinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }
    val cancelButton = createTermsActionButton(getString(R.string.dialog_cancel), filled = false) {
        dismissWithAnimation(dialog, container) {}
    }
    val saveButton = createTermsActionButton("", filled = true) {
        val changed = draft.changedValues()
        if (changed.isEmpty()) {
            dismissWithAnimation(dialog, container) {}
            return@createTermsActionButton
        }
        val saved = runCatching {
            prefs().edit {
                changed.forEach { (key, value) -> putBoolean(key, value) }
            }
        }.isSuccess
        if (!saved) {
            toast(getString(R.string.home_recommend_filter_save_failed))
            return@createTermsActionButton
        }
        changed.forEach { (key, value) -> applyHomeRecommendFilterValue(key, value) }
        homeRecommendFilterSummaryView?.text = homeRecommendFilterSummary()
        toast(getString(R.string.home_recommend_filter_applied))
        dismissWithAnimation(dialog, container) {}
    }
    var updating = false
    fun refreshUi() {
        updating = true
        checkboxes.forEach { (key, box) -> box.isChecked = draft[key] }
        saveButton.text = getString(R.string.home_recommend_filter_save,
            draft.selectedCount(), HomeRecommendFilterCatalog.preferenceKeys.size)
        updating = false
    }
    checkboxes.forEach { (key, box) ->
        box.setOnCheckedChangeListener { _, checked ->
            if (!updating) {
                draft[key] = checked
                refreshUi()
            }
        }
    }
    selectAllButton.setOnClickListener { draft.selectAll(); refreshUi() }
    clearButton.setOnClickListener { draft.clear(); refreshUi() }
    buttonRow.addView(cancelButton,
        NativeLinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    buttonRow.addView(saveButton,
        NativeLinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = (8 * density).toInt()
        })
    container.addView(buttonRow, NativeLinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ))
    refreshUi()
    presentModalDialog(dialog, container, anchor)
    focusPreferenceKey?.let { key -> checkboxes[key]?.let { checkbox ->
        checkbox.doOnLayout {
            listBody.smoothScrollTo(0,(checkbox.top - (12 * density).toInt()).coerceAtLeast(0))
            scheduleSettingsSearchTargetHighlight(checkbox)
        }
    } }
}

/**
 * 13 个既有开关使用草稿式编辑：返回/取消不落盘，保存时只在同一个 Editor 中写变化项。
 * “全部番剧影视”只改变子项的可编辑状态，不改写子项原值。
 */
internal fun MainActivity.showPortraitContentFilterDialog(anchor: View? = null) {
    val density = resources.displayMetrics.density
    val dialog = Dialog(this)
    val container = createModalContainer()
    val draft = PortraitContentFilterDraft(portraitContentFilterValues())

    container.addView(
        NativeTextView(this).apply {
            text = getString(R.string.portrait_content_filter_title)
            textColor = getColor(R.color.colorTextDark)
            textSize = 19f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    )
    container.addView(
        NativeTextView(this).apply {
            text = getString(R.string.portrait_content_filter_dialog_description)
            textColor = getColor(R.color.colorTextGray)
            textSize = 12f
            alpha = 0.72f
            setLineSpacing(4 * density, 1f)
        },
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (7 * density).toInt() }
    )

    val quickActions = NativeLinearLayout(this).apply {
        orientation = NativeLinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }
    quickActions.addView(
        createTermsActionButton(
            getString(R.string.portrait_content_filter_select_all),
            filled = false
        ) {},
        NativeLinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    )
    quickActions.addView(
        createTermsActionButton(
            getString(R.string.portrait_content_filter_clear),
            filled = false
        ) {},
        NativeLinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = (8 * density).toInt()
        }
    )
    container.addView(
        quickActions,
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (12 * density).toInt() }
    )

    val listBody = NativeScrollView(this).apply {
        isFillViewport = true
    }
    val rowContainer = NativeLinearLayout(this).apply {
        orientation = NativeLinearLayout.VERTICAL
        setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
    }
    val checkboxes = linkedMapOf<String, android.widget.CheckBox>()
    var currentGroup: PortraitContentFilterGroup? = null
    PortraitContentFilterCatalog.options.forEach { option ->
        if (currentGroup != option.group) {
            currentGroup = option.group
            rowContainer.addView(
                NativeTextView(this).apply {
                    text = getString(portraitContentFilterGroupLabel(option.group))
                    textColor = monetColors.primary
                    textSize = 12f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                },
                NativeLinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = ((if (checkboxes.isEmpty()) 8 else 15) * density).toInt()
                    bottomMargin = (4 * density).toInt()
                }
            )
        }
        val box = android.widget.CheckBox(this).apply {
            textSize = 14f
            setTextColor(getColor(R.color.colorTextDark))
            isChecked = draft[option.preferenceKey]
            isFocusable = true
        }
        checkboxes[option.preferenceKey] = box
        rowContainer.addView(
            box,
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (4 * density).toInt()
                bottomMargin = (4 * density).toInt()
            }
        )
    }
    listBody.addView(rowContainer)
    val listHeight = minOf(
        (390 * density).toInt(),
        (resources.displayMetrics.heightPixels * 0.48f).toInt()
    )
    container.addView(
        listBody,
        NativeLinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, listHeight).apply {
            topMargin = (7 * density).toInt()
            bottomMargin = (8 * density).toInt()
        }
    )

    val buttonRow = NativeLinearLayout(this).apply {
        orientation = NativeLinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }
    val cancelButton = createTermsActionButton(
        getString(R.string.dialog_cancel),
        filled = false
    ) { dismissWithAnimation(dialog, container) {} }
    lateinit var refreshUi: () -> Unit
    lateinit var saveButton: NativeTextView
    var updating = false

    refreshUi = {
        updating = true
        PortraitContentFilterCatalog.options.forEach { option ->
            checkboxes.getValue(option.preferenceKey).apply {
                val covered = draft.isCovered(option)
                isChecked = draft[option.preferenceKey]
                isEnabled = !covered
                alpha = if (covered) 0.55f else 1f
                text = buildString {
                    append(getString(portraitContentFilterLabel(option.preferenceKey)))
                    if (covered) {
                        append(getString(R.string.portrait_content_filter_covered_suffix))
                    }
                }
            }
        }
        saveButton.text = getString(
            R.string.portrait_content_filter_save,
            draft.selectedCount()
        )
        updating = false
    }
    checkboxes.forEach { (key, box) ->
        box.setOnCheckedChangeListener { _, checked ->
            if (!updating) {
                draft[key] = checked
                refreshUi()
            }
        }
    }
    quickActions.getChildAt(0).setOnClickListener {
        draft.selectAll()
        refreshUi()
    }
    quickActions.getChildAt(1).setOnClickListener {
        draft.clear()
        refreshUi()
    }

    saveButton = createTermsActionButton("", filled = true) {
        val changed = draft.changedValues()
        if (changed.isEmpty()) {
            dismissWithAnimation(dialog, container) {}
            return@createTermsActionButton
        }
        val saved = runCatching {
            prefs().edit {
                changed.forEach { (key, value) -> putBoolean(key, value) }
            }
        }.isSuccess
        if (!saved) {
            toast(getString(R.string.portrait_content_filter_save_failed))
            return@createTermsActionButton
        }
        changed.forEach { (key, value) -> applyPortraitContentFilterValue(key, value) }
        portraitContentFilterSummaryView?.text = portraitContentFilterSummary()
        toast(getString(R.string.portrait_content_filter_applied))
        dismissWithAnimation(dialog, container) {}
    }
    buttonRow.addView(
        cancelButton,
        NativeLinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    )
    buttonRow.addView(
        saveButton,
        NativeLinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = (8 * density).toInt()
        }
    )
    container.addView(
        buttonRow,
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    )
    refreshUi()
    presentModalDialog(dialog, container, anchor)
}

/**
 * 相关推荐沿用既有五个布尔开关，以草稿式二级勾选面板集中编辑。
 * 匹配增强和理由关键词同批落盘，取消弹窗不会改变现有运行时配置。
 */
internal fun MainActivity.showVideoRelateFilterDialog(anchor: View? = null) {
    val density = resources.displayMetrics.density
    val dialog = Dialog(this)
    val container = createModalContainer()
    val draft = VideoRelateFilterDraft(
        videoRelateFilterValues(),
        videoRelateReasonFilterKeywords
    )

    container.addView(
        NativeTextView(this).apply {
            text = getString(R.string.video_relate_filter_settings)
            textColor = getColor(R.color.colorTextDark)
            textSize = 19f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    )
    container.addView(
        NativeTextView(this).apply {
            text = getString(R.string.video_relate_filter_dialog_description)
            textColor = getColor(R.color.colorTextGray)
            textSize = 12f
            alpha = 0.72f
            setLineSpacing(4 * density, 1f)
        },
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (7 * density).toInt() }
    )

    val quickActions = NativeLinearLayout(this).apply {
        orientation = NativeLinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }
    val selectAllButton = createTermsActionButton(
        getString(R.string.video_relate_filter_select_all),
        filled = false
    ) {}
    quickActions.addView(
        selectAllButton,
        NativeLinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    )
    val clearButton = createTermsActionButton(
        getString(R.string.video_relate_filter_clear),
        filled = false
    ) {}
    quickActions.addView(
        clearButton,
        NativeLinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = (8 * density).toInt()
        }
    )
    container.addView(
        quickActions,
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (12 * density).toInt() }
    )

    val listBody = NativeScrollView(this).apply { isFillViewport = true }
    val rowContainer = NativeLinearLayout(this).apply {
        orientation = NativeLinearLayout.VERTICAL
        setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
    }
    val checkboxes = linkedMapOf<String, android.widget.CheckBox>()
    VideoRelateFilterCatalog.panelOptions.forEach { option ->
        val box = android.widget.CheckBox(this).apply {
            text = getString(videoRelateFilterLabel(option.preferenceKey))
            textSize = 14f
            textColor = getColor(R.color.colorTextDark)
            isChecked = draft[option.preferenceKey]
            isFocusable = true
        }
        checkboxes[option.preferenceKey] = box
        rowContainer.addView(
            box,
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = ((if (option.isMatchingEnhancement) 13 else 4) * density).toInt()
                bottomMargin = (4 * density).toInt()
            }
        )
        if (option.isMatchingEnhancement) {
            rowContainer.addView(
                NativeTextView(this).apply {
                    text = getString(R.string.video_relate_matching_enhancement_tip)
                    textColor = getColor(R.color.colorTextGray)
                    textSize = 12f
                    alpha = 0.72f
                    setLineSpacing(4 * density, 1f)
                },
                NativeLinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = (12 * density).toInt()
                    marginEnd = (8 * density).toInt()
                    bottomMargin = (8 * density).toInt()
                }
            )
        }
    }

    val reasonGroup = NativeLinearLayout(this).apply {
        orientation = NativeLinearLayout.VERTICAL
        background = skinCardBackground(monetColors.surfaceVariant)
        setPadding(
            (12 * density).toInt(),
            (10 * density).toInt(),
            (12 * density).toInt(),
            (12 * density).toInt()
        )
    }
    val strongCheckbox = android.widget.CheckBox(this).apply {
        text = getString(R.string.video_relate_strong_mode)
        textSize = 14f
        textColor = getColor(R.color.colorTextDark)
        isChecked = draft[FeaturePreferences.VIDEO_RELATE_STRONG_MODE_ENABLED]
        isFocusable = true
    }
    reasonGroup.addView(
        strongCheckbox,
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    )
    reasonGroup.addView(
        NativeTextView(this).apply {
            text = getString(R.string.video_relate_strong_mode_tip)
            textColor = getColor(R.color.colorTextGray)
            textSize = 12f
            alpha = 0.72f
            setLineSpacing(4 * density, 1f)
        },
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = (12 * density).toInt()
            marginEnd = (8 * density).toInt()
            bottomMargin = (8 * density).toInt()
        }
    )
    val reasonCheckbox = android.widget.CheckBox(this).apply {
        text = getString(R.string.video_relate_reason_filter)
        textSize = 14f
        textColor = getColor(R.color.colorTextDark)
        isChecked = draft[FeaturePreferences.VIDEO_RELATE_REASON_FILTER_ENABLED]
        isFocusable = true
    }
    reasonGroup.addView(
        reasonCheckbox,
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    )
    reasonGroup.addView(
        NativeTextView(this).apply {
            text = getString(R.string.video_relate_reason_filter_tip)
            textColor = getColor(R.color.colorTextGray)
            textSize = 12f
            alpha = 0.72f
            setLineSpacing(4 * density, 1f)
        },
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = (12 * density).toInt()
            marginEnd = (8 * density).toInt()
            bottomMargin = (8 * density).toInt()
        }
    )
    val keywordContainer = NativeLinearLayout(this).apply {
        orientation = NativeLinearLayout.VERTICAL
    }
    keywordContainer.addView(
        NativeTextView(this).apply {
            text = getString(R.string.video_relate_reason_filter_keywords)
            textColor = monetColors.primary
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    )
    val keywordsEditor = NativeEditText(this).apply {
        hint = getString(R.string.video_relate_reason_filter_keywords_hint)
        setText(draft.reasonKeywords)
        textColor = getColor(R.color.colorTextDark)
        setHintTextColor(ColorUtils.setAlphaComponent(getColor(R.color.colorTextGray), 0xA0))
        textSize = 13f
        minLines = 3
        maxLines = 6
        gravity = Gravity.TOP or Gravity.START
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        filters = arrayOf(InputFilter.LengthFilter(VideoRelateFilterDraft.MAX_KEYWORDS_LENGTH))
        background = skinCardBackground(monetColors.surface)
        setPadding(
            (12 * density).toInt(),
            (10 * density).toInt(),
            (12 * density).toInt(),
            (10 * density).toInt()
        )
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                draft.reasonKeywords = s?.toString().orEmpty()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }
    keywordContainer.addView(
        keywordsEditor,
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (6 * density).toInt() }
    )
    reasonGroup.addView(
        keywordContainer,
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    )
    rowContainer.addView(
        reasonGroup,
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = (5 * density).toInt()
            bottomMargin = (6 * density).toInt()
        }
    )
    listBody.addView(rowContainer)
    val listHeight = minOf(
        (440 * density).toInt(),
        (resources.displayMetrics.heightPixels * 0.54f).toInt()
    )
    container.addView(
        listBody,
        NativeLinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, listHeight).apply {
            topMargin = (7 * density).toInt()
            bottomMargin = (8 * density).toInt()
        }
    )

    val buttonRow = NativeLinearLayout(this).apply {
        orientation = NativeLinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }
    val cancelButton = createTermsActionButton(
        getString(R.string.dialog_cancel),
        filled = false
    ) { dismissWithAnimation(dialog, container) {} }
    lateinit var refreshUi: () -> Unit
    lateinit var saveButton: NativeTextView
    var updating = false
    var initialRefresh = true

    refreshUi = {
        updating = true
        VideoRelateFilterCatalog.panelOptions.forEach { option ->
            checkboxes.getValue(option.preferenceKey).isChecked =
                draft[option.preferenceKey]
        }
        strongCheckbox.isChecked =
            draft[FeaturePreferences.VIDEO_RELATE_STRONG_MODE_ENABLED]
        reasonCheckbox.isChecked =
            draft[FeaturePreferences.VIDEO_RELATE_REASON_FILTER_ENABLED]
        saveButton.text = getString(
            R.string.video_relate_filter_save,
            draft.selectedContentCount()
        )
        val reasonGroupWasVisible = reasonGroup.isVisible
        if (!draft.reasonFilterVisible) {
            keywordContainer.visibility = View.GONE
            setModalSectionVisible(
                rowContainer,
                reasonGroup,
                visible = false,
                animate = !initialRefresh
            )
        } else {
            if (!reasonGroupWasVisible) {
                keywordContainer.visibility = if (draft.keywordEditorVisible) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
            }
            setModalSectionVisible(
                rowContainer,
                reasonGroup,
                visible = true,
                animate = !initialRefresh
            )
            if (reasonGroupWasVisible) {
                setModalSectionVisible(
                    reasonGroup,
                    keywordContainer,
                    visible = draft.keywordEditorVisible,
                    animate = !initialRefresh
                )
            }
        }
        updating = false
        initialRefresh = false
    }
    checkboxes.forEach { (key, box) ->
        box.setOnCheckedChangeListener { _, checked ->
            if (!updating) {
                draft[key] = checked
                refreshUi()
            }
        }
    }
    strongCheckbox.setOnCheckedChangeListener { _, checked ->
        if (!updating) {
            draft[FeaturePreferences.VIDEO_RELATE_STRONG_MODE_ENABLED] = checked
            refreshUi()
        }
    }
    reasonCheckbox.setOnCheckedChangeListener { _, checked ->
        if (!updating) {
            draft[FeaturePreferences.VIDEO_RELATE_REASON_FILTER_ENABLED] = checked
            refreshUi()
        }
    }
    selectAllButton.setOnClickListener {
        draft.selectAll()
        refreshUi()
    }
    clearButton.setOnClickListener {
        draft.clear()
        refreshUi()
    }

    saveButton = createTermsActionButton("", filled = true) {
        val changed = draft.changedValues()
        if (changed.isEmpty() && !draft.keywordsChanged()) {
            dismissWithAnimation(dialog, container) {}
            return@createTermsActionButton
        }
        val saved = runCatching {
            prefs().edit {
                changed.forEach { (key, value) -> putBoolean(key, value) }
                if (draft.keywordsChanged()) {
                    putString(
                        FeaturePreferences.VIDEO_RELATE_REASON_FILTER_KEYWORDS,
                        draft.reasonKeywords
                    )
                }
            }
        }.isSuccess
        if (!saved) {
            toast(getString(R.string.video_relate_filter_save_failed))
            return@createTermsActionButton
        }
        changed.forEach { (key, value) -> applyVideoRelateFilterValue(key, value) }
        videoRelateReasonFilterKeywords = draft.reasonKeywords
        videoRelateFilterSummaryView?.text = videoRelateFilterSummary()
        toast(getString(R.string.video_relate_filter_applied))
        dismissWithAnimation(dialog, container) {}
    }
    buttonRow.addView(
        cancelButton,
        NativeLinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    )
    buttonRow.addView(
        saveButton,
        NativeLinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = (8 * density).toInt()
        }
    )
    container.addView(
        buttonRow,
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    )
    refreshUi()
    presentModalDialog(dialog, container, anchor)
}

/** 推荐视频时长范围编辑器：空输入表示不限制，非法区间保持弹窗等待修正。 */
internal fun MainActivity.showRecommendVideoDurationRangeDialog(anchor: View? = null) {
    val density = resources.displayMetrics.density
    val dialog = Dialog(this)
    val container = createModalContainer()

    container.addView(
        NativeTextView(this).apply {
            text = getString(R.string.recommend_video_duration_dialog_title)
            textColor = getColor(R.color.colorTextDark)
            textSize = 17f
            setLineSpacing(4 * density, 1f)
        },
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    )

    fun addDurationEditor(
        @StringRes labelRes: Int,
        initialValue: Int
    ): NativeEditText {
        val editorId = View.generateViewId()
        container.addView(
            NativeTextView(this).apply {
                text = getString(labelRes)
                textColor = getColor(R.color.colorTextGray)
                textSize = 13f
                labelFor = editorId
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (14 * density).toInt() }
        )
        return NativeEditText(this).apply {
            id = editorId
            setText(initialValue.takeIf { it > 0 }?.toString().orEmpty())
            setSelection(text.length)
            hint = getString(R.string.recommend_video_duration_input_hint)
            textColor = getColor(R.color.colorTextDark)
            setHintTextColor(
                ColorUtils.setAlphaComponent(getColor(R.color.colorTextGray), 0x99)
            )
            textSize = 14f
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            isSingleLine = true
            filters = arrayOf(android.text.InputFilter.LengthFilter(10))
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
            container.addView(
                this,
                NativeLinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (6 * density).toInt() }
            )
        }
    }

    val minEditor = addDurationEditor(
        R.string.recommend_video_min_duration,
        recommendVideoMinDurationSeconds
    ).apply {
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
    }
    val maxEditor = addDurationEditor(
        R.string.recommend_video_max_duration,
        recommendVideoMaxDurationSeconds
    ).apply {
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
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
    container.addView(
        errorView,
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (10 * density).toInt() }
    )

    fun showError(@StringRes messageRes: Int, target: NativeEditText) {
        errorView.text = getString(messageRes)
        errorView.visibility = View.VISIBLE
        errorView.announceForAccessibility(errorView.text)
        target.requestFocus()
        target.setSelection(target.text.length)
    }

    fun parseDuration(editor: NativeEditText): Int? {
        val raw = editor.textToString().trim()
        if (raw.isEmpty()) return 0
        val parsed = raw.toLongOrNull() ?: return null
        return parsed.takeIf { it in 1L..Int.MAX_VALUE.toLong() }?.toInt()
    }

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
                errorView.visibility = View.GONE
                val minSeconds = parseDuration(minEditor)
                if (minSeconds == null) {
                    showError(R.string.recommend_video_duration_invalid_number, minEditor)
                    return@setOnClickListener
                }
                val maxSeconds = parseDuration(maxEditor)
                if (maxSeconds == null) {
                    showError(R.string.recommend_video_duration_invalid_number, maxEditor)
                    return@setOnClickListener
                }
                if (minSeconds > 0 && maxSeconds > 0 && minSeconds > maxSeconds) {
                    showError(R.string.recommend_video_duration_invalid_range, maxEditor)
                    return@setOnClickListener
                }

                recommendVideoMinDurationSeconds = minSeconds
                recommendVideoMaxDurationSeconds = maxSeconds
                runCatching {
                    prefs().edit {
                        putInt(
                            FeaturePreferences.RECOMMEND_VIDEO_MIN_DURATION_SECONDS,
                            minSeconds
                        )
                        putInt(
                            FeaturePreferences.RECOMMEND_VIDEO_MAX_DURATION_SECONDS,
                            maxSeconds
                        )
                    }
                }.onFailure { throwable ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write recommended video duration prefs failed",
                        throwable
                    )
                }
                updateRecommendVideoDurationSummary()
                dismissWithAnimation(dialog, container) {}
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

/** 小范围模态内容使用系统 Transition；不把逐帧布局传播到外层设置滚动树。 */
private fun MainActivity.setModalSectionVisible(
    parent: ViewGroup,
    child: View,
    visible: Boolean,
    animate: Boolean
) {
    val targetVisibility = if (visible) View.VISIBLE else View.GONE
    if (child.visibility == targetVisibility) return
    if (!animate || !parent.isLaidOut || !child.isAttachedToWindow) {
        child.visibility = targetVisibility
        return
    }
    TransitionManager.endTransitions(parent)
    TransitionManager.beginDelayedTransition(
        parent,
        TransitionSet().apply {
            ordering = TransitionSet.ORDERING_TOGETHER
            addTransition(ChangeBounds())
            addTransition(Fade())
            duration = if (visible) 260L else 220L
            interpolator = if (visible) {
                secondaryExpandInterpolator
            } else {
                secondaryCollapseInterpolator
            }
        }
    )
    child.visibility = targetVisibility
}

private fun MainActivity.applyHomeRecommendFilterValue(preferenceKey: String, enabled: Boolean) {
    when (preferenceKey) {
        FeaturePreferences.REMOVE_HOME_RECOMMEND_ADS -> removeHomeRecommendAds = enabled
        FeaturePreferences.REMOVE_HOME_RECOMMEND_PICTURES -> removeHomeRecommendPictures = enabled
        FeaturePreferences.REMOVE_HOME_RECOMMEND_GAME_PROMOTIONS ->
            removeHomeRecommendGamePromotions = enabled
        FeaturePreferences.REMOVE_HOME_RECOMMEND_LIVE -> removeHomeRecommendLive = enabled
        FeaturePreferences.REMOVE_HOME_RECOMMEND_PGC -> removeHomeRecommendPgc = enabled
        FeaturePreferences.REMOVE_HOME_RECOMMEND_SPECIAL_CARDS -> removeHomeRecommendSpecialCards = enabled
        FeaturePreferences.REMOVE_HOME_RECOMMEND_COURSES -> removeHomeRecommendCourses = enabled
        FeaturePreferences.REMOVE_HOME_RECOMMEND_LARGE -> removeHomeRecommendLarge = enabled
        else -> error("Unknown home recommendation filter key: $preferenceKey")
    }
}

private fun MainActivity.applyPortraitContentFilterValue(preferenceKey: String, enabled: Boolean) {
    when (preferenceKey) {
        FeaturePreferences.REMOVE_HOME_RECOMMEND_VERTICAL ->
            removeHomeRecommendVertical = enabled
        FeaturePreferences.REMOVE_STORY_ADS -> removeStoryAds = enabled
        FeaturePreferences.REMOVE_STORY_LIVE -> removeStoryLive = enabled
        FeaturePreferences.REMOVE_STORY_GAMES -> removeStoryGames = enabled
        FeaturePreferences.REMOVE_STORY_COURSES -> removeStoryCourses = enabled
        FeaturePreferences.REMOVE_STORY_SHORT_DRAMA -> removeStoryShortDrama = enabled
        FeaturePreferences.REMOVE_STORY_SHOPPING -> removeStoryShopping = enabled
        FeaturePreferences.REMOVE_STORY_MUSIC -> removeStoryMusic = enabled
        FeaturePreferences.REMOVE_STORY_BANGUMI -> removeStoryBangumi = enabled
        FeaturePreferences.REMOVE_STORY_MOVIES -> removeStoryMovies = enabled
        FeaturePreferences.REMOVE_STORY_DOCUMENTARIES -> removeStoryDocumentaries = enabled
        FeaturePreferences.REMOVE_STORY_TV -> removeStoryTv = enabled
        FeaturePreferences.REMOVE_STORY_VARIETY -> removeStoryVariety = enabled
        else -> error("Unknown portrait filter key: $preferenceKey")
    }
}

private fun MainActivity.applyVideoRelateFilterValue(preferenceKey: String, enabled: Boolean) {
    when (preferenceKey) {
        FeaturePreferences.REMOVE_RELATE_COMMERCIAL -> removeRelateCommercial = enabled
        FeaturePreferences.REMOVE_RELATE_GAME -> removeRelateGame = enabled
        FeaturePreferences.REMOVE_RELATE_LIVE -> removeRelateLive = enabled
        FeaturePreferences.REMOVE_RELATE_COURSE -> removeRelateCourse = enabled
        FeaturePreferences.REMOVE_RELATE_SPECIAL -> removeRelateSpecial = enabled
        FeaturePreferences.VIDEO_RELATE_MATCHING_ENHANCEMENT_ENABLED ->
            videoRelateMatchingEnhancementEnabled = enabled
        FeaturePreferences.VIDEO_RELATE_STRONG_MODE_ENABLED ->
            videoRelateStrongModeEnabled = enabled
        FeaturePreferences.VIDEO_RELATE_REASON_FILTER_ENABLED ->
            videoRelateReasonFilterEnabled = enabled
        else -> error("Unknown video relate filter key: $preferenceKey")
    }
}

@StringRes
private fun MainActivity.portraitContentFilterLabel(preferenceKey: String): Int = when (preferenceKey) {
    FeaturePreferences.REMOVE_HOME_RECOMMEND_VERTICAL ->
        R.string.remove_home_recommend_vertical
    FeaturePreferences.REMOVE_STORY_ADS -> R.string.remove_story_ads
    FeaturePreferences.REMOVE_STORY_LIVE -> R.string.remove_story_live
    FeaturePreferences.REMOVE_STORY_GAMES -> R.string.remove_story_games
    FeaturePreferences.REMOVE_STORY_COURSES -> R.string.remove_story_courses
    FeaturePreferences.REMOVE_STORY_SHORT_DRAMA -> R.string.remove_story_short_drama
    FeaturePreferences.REMOVE_STORY_SHOPPING -> R.string.remove_story_shopping
    FeaturePreferences.REMOVE_STORY_MUSIC -> R.string.remove_story_music
    FeaturePreferences.REMOVE_STORY_BANGUMI -> R.string.remove_story_bangumi
    FeaturePreferences.REMOVE_STORY_MOVIES -> R.string.remove_story_movies
    FeaturePreferences.REMOVE_STORY_DOCUMENTARIES -> R.string.remove_story_documentaries
    FeaturePreferences.REMOVE_STORY_TV -> R.string.remove_story_tv
    FeaturePreferences.REMOVE_STORY_VARIETY -> R.string.remove_story_variety
    else -> error("Unknown portrait filter key: $preferenceKey")
}

@StringRes
private fun MainActivity.portraitContentFilterGroupLabel(group: PortraitContentFilterGroup): Int =
    when (group) {
        PortraitContentFilterGroup.HOME -> R.string.portrait_content_filter_group_home
        PortraitContentFilterGroup.STORY -> R.string.portrait_content_filter_group_story
        PortraitContentFilterGroup.SERIES -> R.string.portrait_content_filter_group_series
    }

@StringRes
private fun MainActivity.videoRelateFilterLabel(preferenceKey: String): Int = when (preferenceKey) {
    FeaturePreferences.REMOVE_RELATE_COMMERCIAL -> R.string.remove_relate_commercial
    FeaturePreferences.REMOVE_RELATE_GAME -> R.string.remove_relate_game
    FeaturePreferences.REMOVE_RELATE_LIVE -> R.string.remove_relate_live
    FeaturePreferences.REMOVE_RELATE_COURSE -> R.string.remove_relate_course
    FeaturePreferences.REMOVE_RELATE_SPECIAL -> R.string.remove_relate_special
    FeaturePreferences.VIDEO_RELATE_MATCHING_ENHANCEMENT_ENABLED ->
        R.string.video_relate_matching_enhancement
    else -> error("Unknown video relate filter key: $preferenceKey")
}

private fun MainActivity.updateRecommendVideoDurationSummary() {
    recommendVideoDurationSummaryView?.text =
        getString(R.string.recommend_video_duration_range) + "\n" +
            recommendVideoDurationSummary()
}

/**
 * 详细页组件净化的勾选面板。
 *
 * 与首页推荐 / 相关推荐两个面板同一套交互：勾选只改草稿，点保存才一次性写偏好，
 * 之后同步刷新一级界面的摘要行。键清单来自 [DetailModulePurifyPolicy]，UI 不另抄一份。
 */
internal fun MainActivity.showDetailModuleFilterDialog(
    focusPreferenceKey: String? = null,
    anchor: View? = null
) {
    val density = resources.displayMetrics.density
    val dialog = Dialog(this)
    val container = createModalContainer()
    val draft = DetailModuleFilterDraft(detailModuleFilterValues())
    container.addView(NativeTextView(this).apply {
        text = getString(R.string.detail_module_purify_settings)
        textColor = getColor(R.color.colorTextDark)
        textSize = 19f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    })
    container.addView(NativeTextView(this).apply {
        text = getString(R.string.detail_module_purify_dialog_description)
        textColor = getColor(R.color.colorTextGray)
        textSize = 12f
        alpha = 0.72f
        setLineSpacing(4 * density, 1f)
    }, NativeLinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = (7 * density).toInt() })

    val quickActions = NativeLinearLayout(this).apply {
        orientation = NativeLinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }
    val selectAllButton = createTermsActionButton(
        getString(R.string.detail_module_purify_select_all), filled = false
    ) {}
    val clearButton = createTermsActionButton(
        getString(R.string.detail_module_purify_clear), filled = false
    ) {}
    quickActions.addView(selectAllButton,
        NativeLinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    quickActions.addView(clearButton,
        NativeLinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = (8 * density).toInt()
        })
    container.addView(quickActions, NativeLinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = (12 * density).toInt() })

    val rows = NativeLinearLayout(this).apply {
        orientation = NativeLinearLayout.VERTICAL
        setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
    }
    val checkboxes = linkedMapOf<String, android.widget.CheckBox>()
    DetailComponentPanelCatalog.preferenceKeys.forEach { key ->
        val box = android.widget.CheckBox(this).apply {
            text = getString(detailModuleFilterLabel(key))
            textSize = 14f
            textColor = getColor(R.color.colorTextDark)
            minimumHeight = (48 * density).toInt()
            isChecked = draft[key]
            isFocusable = true
        }
        checkboxes[key] = box
        rows.addView(box, NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }
    container.addView(rows, NativeLinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply {
        topMargin = (7 * density).toInt()
        bottomMargin = (4 * density).toInt()
    })
    container.addView(NativeTextView(this).apply {
        text = getString(R.string.detail_module_purify_tip)
        textColor = getColor(R.color.colorTextGray)
        textSize = 11f
        alpha = 0.66f
        setLineSpacing(3 * density, 1f)
    }, NativeLinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = (8 * density).toInt() })

    val buttonRow = NativeLinearLayout(this).apply {
        orientation = NativeLinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }
    val cancelButton = createTermsActionButton(getString(R.string.dialog_cancel), filled = false) {
        dismissWithAnimation(dialog, container) {}
    }
    val saveButton = createTermsActionButton("", filled = true) {
        val changed = draft.changedValues()
        if (changed.isEmpty()) {
            dismissWithAnimation(dialog, container) {}
            return@createTermsActionButton
        }
        val saved = runCatching {
            prefs().edit {
                changed.forEach { (key, value) -> putBoolean(key, value) }
            }
        }.isSuccess
        if (!saved) {
            toast(getString(R.string.detail_module_purify_save_failed))
            return@createTermsActionButton
        }
        changed.forEach { (key, value) -> applyDetailModuleFilterValue(key, value) }
        detailModuleFilterSummaryView?.text = detailModuleFilterSummary()
        toast(getString(R.string.detail_module_purify_applied))
        dismissWithAnimation(dialog, container) {}
    }
    var updating = false
    fun refreshUi() {
        updating = true
        checkboxes.forEach { (key, box) -> box.isChecked = draft[key] }
        saveButton.text = getString(
            R.string.detail_module_purify_save, draft.selectedCount()
        )
        updating = false
    }
    checkboxes.forEach { (key, box) ->
        box.setOnCheckedChangeListener { _, checked ->
            if (!updating) {
                draft[key] = checked
                refreshUi()
            }
        }
    }
    selectAllButton.setOnClickListener { draft.selectAll(); refreshUi() }
    clearButton.setOnClickListener { draft.clear(); refreshUi() }
    buttonRow.addView(cancelButton,
        NativeLinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    buttonRow.addView(saveButton,
        NativeLinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = (8 * density).toInt()
        })
    container.addView(buttonRow, NativeLinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ))
    refreshUi()
    presentModalDialog(dialog, container, anchor)
    focusPreferenceKey?.let { key ->
        checkboxes[key]?.let { checkbox ->
            checkbox.doOnLayout { scheduleSettingsSearchTargetHighlight(checkbox) }
        }
    }
}

/** 保存后同步一级界面的镜像字段；未知键直接失败，避免面板与 Hook 名单静默漂移。 */
private fun MainActivity.applyDetailModuleFilterValue(preferenceKey: String, enabled: Boolean) {
    when (preferenceKey) {
        FeaturePreferences.REMOVE_DETAIL_HONOR -> removeDetailHonor = enabled
        FeaturePreferences.REMOVE_DETAIL_LIVE_ORDER -> removeDetailLiveOrder = enabled
        FeaturePreferences.REMOVE_DETAIL_UGC_SEASON -> removeDetailUgcSeason = enabled
        FeaturePreferences.REMOVE_DETAIL_UP_VIP_LABEL -> removeDetailUpVipLabel = enabled
        FeaturePreferences.REMOVE_DETAIL_TOPIC_TAGS -> removeDetailTopicTags = enabled
        FeaturePreferences.REMOVE_DETAIL_STAFF_FOLLOW -> removeDetailStaffFollow = enabled
        FeaturePreferences.REMOVE_DETAIL_HOT_BANNER -> removeDetailHotBanner = enabled
        else -> error("Unknown detail module filter key: $preferenceKey")
    }
}
