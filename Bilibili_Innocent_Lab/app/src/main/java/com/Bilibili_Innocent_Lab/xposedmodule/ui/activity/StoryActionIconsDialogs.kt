package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.app.Dialog
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.core.content.edit
import androidx.core.view.doOnLayout
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.StoryActionIcon
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import io.github.jichuo1.meguviz.android.extensions.textColor
import io.github.jichuo1.meguviz.android.extensions.toast
import android.widget.LinearLayout as NativeLinearLayout
import android.widget.TextView as NativeTextView

/**
 * 「竖屏页互动图标」勾选面板的草稿：勾选只改草稿，保存才一次性写偏好。
 * 键清单取 [StoryActionIcon]（Hook 侧同一份目录），不在 UI 再抄一份。
 */
internal class StoryActionIconsDraft(initialValues: Map<String, Boolean>) {
    private val initial = StoryActionIcon.preferenceKeys.associateWith { initialValues[it] == true }
    private val current = initial.toMutableMap()

    operator fun get(preferenceKey: String): Boolean = current[preferenceKey] == true

    operator fun set(preferenceKey: String, enabled: Boolean) {
        require(preferenceKey in current) { "Unknown story action icon key: $preferenceKey" }
        current[preferenceKey] = enabled
    }

    fun selectedCount(): Int = current.values.count { it }

    fun selectAll() = current.keys.forEach { current[it] = true }

    fun clear() = current.keys.forEach { current[it] = false }

    fun changedValues(): Map<String, Boolean> = current.filter { (key, value) -> initial[key] != value }
}

internal fun MainActivity.storyActionIconValues(): Map<String, Boolean> =
    StoryActionIcon.preferenceKeys.associateWith { prefs().getBoolean(it, false) }

internal fun MainActivity.storyActionIconsSummary(): String {
    val selected = storyActionIconValues().values.count { it }
    return if (selected == 0) {
        getString(R.string.story_action_icons_summary_none)
    } else {
        getString(R.string.story_action_icons_summary_selected, selected, StoryActionIcon.entries.size)
    }
}

@StringRes
internal fun storyActionIconLabel(icon: StoryActionIcon): Int = when (icon) {
    StoryActionIcon.LIKE -> R.string.hide_story_action_like
    StoryActionIcon.COMMENT -> R.string.hide_story_action_comment
    StoryActionIcon.COIN -> R.string.hide_story_action_coin
    StoryActionIcon.FAVORITE -> R.string.hide_story_action_favorite
    StoryActionIcon.SHARE -> R.string.hide_story_action_share
    StoryActionIcon.DANMAKU_TOGGLE -> R.string.hide_story_action_danmaku_toggle
}

internal fun MainActivity.showStoryActionIconsDialog(
    focusPreferenceKey: String? = null,
    anchor: View? = null
) {
    val density = resources.displayMetrics.density
    val dialog = Dialog(this).also { installDialogElasticInteraction(it) }
    val container = createModalContainer()
    val draft = StoryActionIconsDraft(storyActionIconValues())
    container.addView(NativeTextView(this).apply {
        text = getString(R.string.story_action_icons_title)
        textColor = getColor(R.color.colorTextDark)
        textSize = 19f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    })
    container.addView(NativeTextView(this).apply {
        text = getString(R.string.story_action_icons_description)
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
    val selectAllButton = createTermsActionButton(getString(R.string.story_action_icons_select_all), filled = false) {}
    val clearButton = createTermsActionButton(getString(R.string.story_action_icons_clear), filled = false) {}
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
    StoryActionIcon.entries.forEach { icon ->
        val box = android.widget.CheckBox(this).apply {
            text = getString(storyActionIconLabel(icon))
            textSize = 14f
            textColor = getColor(R.color.colorTextDark)
            minimumHeight = (48 * density).toInt()
            isChecked = draft[icon.preferenceKey]
            isFocusable = true
        }
        checkboxes[icon.preferenceKey] = box
        rows.addView(box, NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }
    container.addView(rows, NativeLinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
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
            prefs().edit { changed.forEach { (key, value) -> putBoolean(key, value) } }
        }.isSuccess
        if (!saved) {
            toast(getString(R.string.story_action_icons_save_failed))
            return@createTermsActionButton
        }
        storyActionIconsSummaryView?.text = storyActionIconsSummary()
        toast(getString(R.string.story_action_icons_applied))
        dismissWithAnimation(dialog, container) {}
    }
    var updating = false
    fun refreshUi() {
        updating = true
        checkboxes.forEach { (key, box) -> box.isChecked = draft[key] }
        saveButton.text = getString(R.string.story_action_icons_save, draft.selectedCount())
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
